/*
  =========================================================================
  FertiTrack_ESP_B_v5.9.ino — ESP_B (sensor de humedad + solenoide JF-0530B)
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 (ESP_B) : CC:DB:A7:34:A0:FC
  MAC del MAESTRO (la nueva, igual que en ESP_A y ESP_C): 6C:C8:40:4E:57:D0
  =========================================================================

  -------------------------------------------------------------------
  QUÉ CAMBIA EN v5.9 — CICLO DE "DISPARO"
  -------------------------------------------------------------------
  Hasta v5.8 el solenoide se energizaba durante TODA la medición. Como al
  energizar el émbolo se RECOGE, el sensor medía recogido, fuera de la
  tierra. Ahora el solenoide solo "arma" y "suelta":

     1) ARMANDO     relé/MOSFET ON  (tiempoArmadoMs, ~250 ms)
                    el émbolo se recoge y carga el resorte.
     2) DISPARANDO  OFF. El resorte suelta el émbolo y clava el sensor
                    en la tierra (tiempoInsercionMs, ~300 ms). Aquí se
                    ENCIENDE la alimentación del sensor.
     3) MIDIENDO    OFF (bobina fría). Estabiliza y toma muestras con el
                    sensor ya enterrado.
     4) EXTRAYENDO  ON brevemente (tiempoRetraccionMs, ~1 s) para sacar
                    el sensor de la tierra. Pon EXTRAER_AL_FINAL false si
                    no quieres este paso. Luego OFF y envía HUMEDAD.

  Tiempo ON total por ciclo ≈ 1.3 s (antes ≈ 3.3 s): la bobina trabaja
  mucho menos y se calienta mucho menos.

  PRUEBA DE BANCO por el Monitor Serie (115200):
     p  → solo el DISPARO (arma y suelta), sin medir. Pon la mano o la
          tierra delante del sensor para ver si se clava.
     +  → sube el tiempo de armado 50 ms      - → lo baja 50 ms
          (si el émbolo no alcanza a recogerse del todo, súbelo; el
           valor se guarda). Rango 100–1500 ms.
     m  → ciclo completo      s → STOP      r → RESET

  PROTOCOLO con el Maestro — SIN CAMBIOS (MEDIR/STOP/RESET/HUMEDAD).
  SOL_INS = espera tras soltar   SOL_RET = tiempo de extracción
  SOL_EST / SOL_MED = estabilizar / muestrear
  NUEVO opcional: SOL_ARM = tiempo de armado (el Maestro no lo necesita).

  -------------------------------------------------------------------
  DRIVER (MOSFET por defecto, como v5.8)
  -------------------------------------------------------------------
     GPIO26 → 220 Ω → G del IRLZ44N, G → 10 kΩ → GND
     S → GND común   D → cable NEGRO del solenoide   ROJO → +6 V
     Diodo 1N4007 en antiparalelo en los bornes del solenoide.
  DRIVER_MOSFET false = módulo de relé azul (activo en LOW).
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <esp_system.h>
#include <Preferences.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS
// ─────────────────────────────────────────────────────────────────────
enum EstadoB {
  EB_REPOSO,          // solenoide OFF, esperando "MEDIR"
  EB_ARMANDO,         // solenoide ON: el émbolo se recoge (carga el resorte)
  EB_DISPARANDO,      // solenoide OFF: el resorte clava el sensor
  EB_MIDIENDO,        // solenoide OFF: sensor enterrado, tomando muestras
  EB_EXTRAYENDO,      // solenoide ON: saca el sensor de la tierra
  EB_EMERGENCIA       // solenoide OFF, esperando RESET
};

typedef struct {
  char emisor[10];
  char comando[20];
  int  parametro;
} MensajeFertiTrack;

struct ComandoRx { char cmd[20]; int param; };

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const int CANAL_WIFI = 1;
uint8_t MAC_MAESTRO[] = {0x6C, 0xC8, 0x40, 0x4E, 0x57, 0xD0};

// ─────────────────────────────────────────────────────────────────────
// PINES Y DRIVER
// ─────────────────────────────────────────────────────────────────────
#define PIN_PISTON_RELE    26
#define PIN_SENSOR_HUMEDAD 34
#define PIN_SENSOR_ALIM    25   // pon -1 si el VCC del sensor va directo a 3.3V

// true  = MOSFET de nivel lógico: HIGH enciende, LOW apaga.
// false = módulo de relé azul: LOW enciende, y apagado = pin suelto (INPUT).
#define DRIVER_MOSFET true
#define RELE_OFF_COMO_ENTRADA true   // solo se usa con relé

// Sacar el sensor de la tierra al terminar de medir (energiza ~1 s)
#define EXTRAER_AL_FINAL true

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS DEL CICLO (ajustables, se guardan en NVS)
// ─────────────────────────────────────────────────────────────────────
const unsigned long DEF_ARMADO_MS      = 250;   // ON: recoger el émbolo
const unsigned long DEF_INSERCION_MS   = 300;   // OFF: el resorte clava el sensor
const unsigned long DEF_ESTABILIZAR_MS = 500;   // OFF: contacto estable antes de muestrear
const unsigned long DEF_MUESTREO_MS    = 1800;  // OFF: ventana de muestreo
const unsigned long DEF_RETRACCION_MS  = 1000;  // ON: sacar el sensor de la tierra

unsigned long tiempoArmadoMs      = DEF_ARMADO_MS;
unsigned long tiempoInsercionMs   = DEF_INSERCION_MS;
unsigned long tiempoEstabilizarMs = DEF_ESTABILIZAR_MS;
unsigned long tiempoMuestreoMs    = DEF_MUESTREO_MS;
unsigned long tiempoRetraccionMs  = DEF_RETRACCION_MS;

const uint32_t VERSION_NVS = 59;   // sube este número para forzar valores por defecto

// ─────────────────────────────────────────────────────────────────────
// SEGURIDAD: ninguna fase con el solenoide ON puede pasar de esto
// ─────────────────────────────────────────────────────────────────────
const unsigned long TIEMPO_ON_MAX_MS = 4000;

// ─────────────────────────────────────────────────────────────────────
// MUESTREO
// ─────────────────────────────────────────────────────────────────────
const unsigned long INTERVALO_MUESTRA_MS = 100;
const int           LECTURAS_POR_MUESTRA = 4;
const int           MAX_MUESTRAS         = 64;
const int           RECORTE_PCT          = 10;

// ─────────────────────────────────────────────────────────────────────
// NVS
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

void guardarTiempos() {
  prefs.putULong("sol_arm", tiempoArmadoMs);
  prefs.putULong("sol_ins", tiempoInsercionMs);
  prefs.putULong("sol_est", tiempoEstabilizarMs);
  prefs.putULong("sol_med", tiempoMuestreoMs);
  prefs.putULong("sol_ret", tiempoRetraccionMs);
}
void cargarTiempos() {
  if (prefs.getUInt("ver", 0) != VERSION_NVS) {     // primera vez con esta versión
    tiempoArmadoMs      = DEF_ARMADO_MS;
    tiempoInsercionMs   = DEF_INSERCION_MS;
    tiempoEstabilizarMs = DEF_ESTABILIZAR_MS;
    tiempoMuestreoMs    = DEF_MUESTREO_MS;
    tiempoRetraccionMs  = DEF_RETRACCION_MS;
    guardarTiempos();
    prefs.putUInt("ver", VERSION_NVS);
    return;
  }
  tiempoArmadoMs      = prefs.getULong("sol_arm", DEF_ARMADO_MS);
  tiempoInsercionMs   = prefs.getULong("sol_ins", DEF_INSERCION_MS);
  tiempoEstabilizarMs = prefs.getULong("sol_est", DEF_ESTABILIZAR_MS);
  tiempoMuestreoMs    = prefs.getULong("sol_med", DEF_MUESTREO_MS);
  tiempoRetraccionMs  = prefs.getULong("sol_ret", DEF_RETRACCION_MS);
}

// ─────────────────────────────────────────────────────────────────────
// ESTADO
// ─────────────────────────────────────────────────────────────────────
MensajeFertiTrack msgEnviar;
EstadoB       estadoActual        = EB_REPOSO;
unsigned long tiempoInicioEtapa   = 0;
unsigned long ultimaMuestraMillis = 0;
unsigned long relePrendidoDesde   = 0;   // 0 = solenoide apagado
bool          serialListo         = false;
bool          soloDisparo         = false;   // true = prueba 'p': arma, suelta y termina
int           muestras[MAX_MUESTRAS];
int           cantidadMuestras    = 0;
int           ultimaLecturaPromedio = -1;
volatile bool stopSolicitado = false;

const int    COLA_CMD_MAX   = 10;
ComandoRx    colaCmd[COLA_CMD_MAX];
int          colaIni        = 0;
volatile int colaCuenta     = 0;
volatile bool colaDesbordada = false;
portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

// ─────────────────────────────────────────────────────────────────────
// CONTROL DEL SOLENOIDE (única vía para tocar el pin del driver)
// ─────────────────────────────────────────────────────────────────────
void solenoidON() {
#if DRIVER_MOSFET
  digitalWrite(PIN_PISTON_RELE, HIGH);
  pinMode(PIN_PISTON_RELE, OUTPUT);
#else
  digitalWrite(PIN_PISTON_RELE, LOW);
  pinMode(PIN_PISTON_RELE, OUTPUT);
#endif
  relePrendidoDesde = millis();
  if (relePrendidoDesde == 0) relePrendidoDesde = 1;
  if (serialListo) Serial.println("[SOL] ON  (se recoge el embolo)");
}

void solenoidOFF() {
#if DRIVER_MOSFET
  digitalWrite(PIN_PISTON_RELE, LOW);
  pinMode(PIN_PISTON_RELE, OUTPUT);
#elif RELE_OFF_COMO_ENTRADA
  pinMode(PIN_PISTON_RELE, INPUT);
#else
  pinMode(PIN_PISTON_RELE, OUTPUT);
  digitalWrite(PIN_PISTON_RELE, HIGH);
#endif
  relePrendidoDesde = 0;
  if (serialListo) Serial.println("[SOL] OFF (el resorte suelta el embolo)");
}

void solenoidEmergencia() { solenoidOFF(); }

// ─────────────────────────────────────────────────────────────────────
// ALIMENTACIÓN DEL SENSOR
// ─────────────────────────────────────────────────────────────────────
void alimentarSensor(bool encender) {
#if PIN_SENSOR_ALIM >= 0
  digitalWrite(PIN_SENSOR_ALIM, encender ? HIGH : LOW);
#else
  (void)encender;
#endif
}

// ─────────────────────────────────────────────────────────────────────
// LECTURA DEL ADC
// ─────────────────────────────────────────────────────────────────────
int leerMuestraADC() {
  long suma = 0;
  for (int i = 0; i < LECTURAS_POR_MUESTRA; i++) suma += analogRead(PIN_SENSOR_HUMEDAD);
  return (int)(suma / LECTURAS_POR_MUESTRA);
}

int promedioRecortado(int n, int &minimo, int &maximo) {
  if (n <= 0) { minimo = 0; maximo = 0; return 0; }
  int t[MAX_MUESTRAS];
  for (int i = 0; i < n; i++) t[i] = muestras[i];
  for (int i = 1; i < n; i++) {
    int v = t[i], j = i - 1;
    while (j >= 0 && t[j] > v) { t[j+1] = t[j]; j--; }
    t[j+1] = v;
  }
  minimo = t[0]; maximo = t[n-1];
  int k = (n >= 10) ? (n * RECORTE_PCT) / 100 : 0;
  long suma = 0; int cuenta = 0;
  for (int i = k; i < n - k; i++) { suma += t[i]; cuenta++; }
  return (cuenta > 0) ? (int)(suma / cuenta) : 0;
}

// ─────────────────────────────────────────────────────────────────────
// RESPUESTA AL MAESTRO
// ─────────────────────────────────────────────────────────────────────
void enviarAMaestro(const char *comando, int parametro) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor,  "ESPB",   sizeof(msgEnviar.emisor)-1);
  strncpy(msgEnviar.comando, comando,  sizeof(msgEnviar.comando)-1);
  msgEnviar.parametro = parametro;
  esp_err_t r = ESP_FAIL;
  for (int i = 0; i < 3 && r != ESP_OK; i++) {
    r = esp_now_send(MAC_MAESTRO, (uint8_t*)&msgEnviar, sizeof(msgEnviar));
    if (r != ESP_OK) delay(3);
  }
  Serial.printf("[TX] %s (%d) -> MAESTRO %s\n",
                comando, parametro, r == ESP_OK ? "OK" : "ERROR");
}

// ─────────────────────────────────────────────────────────────────────
// INICIO DEL CICLO (disparo + medición, o solo disparo de prueba)
// ─────────────────────────────────────────────────────────────────────
void iniciarCiclo(bool solo) {
  if (estadoActual != EB_REPOSO) {
    Serial.println("[ESP_B] Ocupado o en emergencia: orden ignorada.");
    return;
  }
  soloDisparo      = solo;
  cantidadMuestras = 0;
  estadoActual     = EB_ARMANDO;
  tiempoInicioEtapa = millis();
  solenoidON();   // recoge el émbolo y carga el resorte

  Serial.printf("[ESP_B] %s: arm=%lu ins=%lu est=%lu med=%lu ret=%lu ms\n",
                solo ? "DISPARO DE PRUEBA" : "MEDIR iniciado",
                tiempoArmadoMs, tiempoInsercionMs, tiempoEstabilizarMs,
                tiempoMuestreoMs, tiempoRetraccionMs);
}

// ─────────────────────────────────────────────────────────────────────
// EMERGENCIA Y RESET
// ─────────────────────────────────────────────────────────────────────
void ejecutarEmergencia() {
  stopSolicitado = false;
  solenoidEmergencia();
  alimentarSensor(false);
  if (estadoActual == EB_EMERGENCIA) return;
  cantidadMuestras = 0;
  estadoActual     = EB_EMERGENCIA;
  Serial.println("[ESP_B] *** EMERGENCIA: solenoide apagado. Esperando RESET. ***");
}
void reiniciarNodo() {
  solenoidEmergencia();
  alimentarSensor(false);
  cantidadMuestras = 0;
  estadoActual     = EB_REPOSO;
  Serial.println("[ESP_B] RESET. En REPOSO — solenoide apagado.");
}

// ─────────────────────────────────────────────────────────────────────
// COLA DE COMANDOS
// ─────────────────────────────────────────────────────────────────────
bool sacarComando(ComandoRx &salida) {
  bool hay = false;
  portENTER_CRITICAL(&muxCola);
  if (colaCuenta > 0) {
    salida    = colaCmd[colaIni];
    colaIni   = (colaIni + 1) % COLA_CMD_MAX;
    colaCuenta--;
    hay = true;
  }
  portEXIT_CRITICAL(&muxCola);
  return hay;
}

void ejecutarComando(const ComandoRx &c) {
  Serial.printf("[RX] %s (%d)\n", c.cmd, c.param);

  if (strcmp(c.cmd, "STOP")  == 0) { ejecutarEmergencia(); return; }
  if (strcmp(c.cmd, "RESET") == 0) { reiniciarNodo();      return; }

  if (estadoActual == EB_EMERGENCIA) {
    Serial.println("[ESP_B] En EMERGENCIA: comando ignorado hasta RESET.");
    return;
  }

  if (strcmp(c.cmd, "MEDIR") == 0) {
    iniciarCiclo(false);
  }
  else if (strcmp(c.cmd, "SOL_ARM") == 0) {
    if (c.param >= 100 && c.param <= 1500) {
      tiempoArmadoMs = (unsigned long)c.param;
      guardarTiempos();
      Serial.printf("[SOL] tiempoArmadoMs=%lu ms\n", tiempoArmadoMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_INS") == 0) {
    if (c.param >= 100 && c.param <= 3000) {
      tiempoInsercionMs = (unsigned long)c.param;
      guardarTiempos();
      Serial.printf("[SOL] tiempoInsercionMs=%lu ms\n", tiempoInsercionMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_EST") == 0) {
    if (c.param >= 100 && c.param <= 5000) {
      tiempoEstabilizarMs = (unsigned long)c.param;
      guardarTiempos();
      Serial.printf("[SOL] tiempoEstabilizarMs=%lu ms\n", tiempoEstabilizarMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_MED") == 0) {
    if (c.param >= 500 && c.param <= 8000) {
      tiempoMuestreoMs = (unsigned long)c.param;
      guardarTiempos();
      Serial.printf("[SOL] tiempoMuestreoMs=%lu ms\n", tiempoMuestreoMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_RET") == 0) {
    if (c.param >= 100 && c.param <= 3000) {
      tiempoRetraccionMs = (unsigned long)c.param;
      guardarTiempos();
      Serial.printf("[SOL] tiempoRetraccionMs=%lu ms\n", tiempoRetraccionMs);
    }
  }
  else if (strcmp(c.cmd, "INDICADOR") == 0) {
    Serial.println("[ESP_B] INDICADOR ignorado: lo maneja ESP_C.");
  }
  else {
    Serial.printf("[ESP_B] Comando desconocido: \"%s\"\n", c.cmd);
  }
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != (int)sizeof(MensajeFertiTrack)) return;
  MensajeFertiTrack m;
  memcpy(&m, incomingData, sizeof(m));
  m.emisor[sizeof(m.emisor)-1]   = '\0';
  m.comando[sizeof(m.comando)-1] = '\0';
  if (strcmp(m.emisor, "MAESTRO") != 0) return;
  if (strcmp(m.comando, "STOP") == 0) stopSolicitado = true;
  portENTER_CRITICAL(&muxCola);
  if (colaCuenta < COLA_CMD_MAX) {
    int pos = (colaIni + colaCuenta) % COLA_CMD_MAX;
    strncpy(colaCmd[pos].cmd, m.comando, sizeof(colaCmd[pos].cmd)-1);
    colaCmd[pos].cmd[sizeof(colaCmd[pos].cmd)-1] = '\0';
    colaCmd[pos].param = m.parametro;
    colaCuenta++;
  } else {
    colaDesbordada = true;
  }
  portEXIT_CRITICAL(&muxCola);
}

// ─────────────────────────────────────────────────────────────────────
// SERIAL: prueba de banco sin Maestro
// ─────────────────────────────────────────────────────────────────────
void atenderSerial() {
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == 'm' || ch == 'M') {
      iniciarCiclo(false);
    } else if (ch == 'p' || ch == 'P') {
      iniciarCiclo(true);
    } else if (ch == '+') {
      if (tiempoArmadoMs + 50UL <= 1500UL) tiempoArmadoMs += 50UL;
      guardarTiempos();
      Serial.printf("[PRUEBA] Tiempo de armado = %lu ms\n", tiempoArmadoMs);
    } else if (ch == '-') {
      if (tiempoArmadoMs >= 150UL) tiempoArmadoMs -= 50UL;
      guardarTiempos();
      Serial.printf("[PRUEBA] Tiempo de armado = %lu ms\n", tiempoArmadoMs);
    } else if (ch == 's' || ch == 'S') {
      ejecutarEmergencia();
    } else if (ch == 'r' || ch == 'R') {
      reiniciarNodo();
    }
  }
}

// ─────────────────────────────────────────────────────────────────────
// MOTIVO DEL ÚLTIMO REINICIO
// ─────────────────────────────────────────────────────────────────────
const char* motivoReset() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "POWERON  (la placa perdio y recupero la alimentacion)";
    case ESP_RST_BROWNOUT: return "BROWNOUT (cayo el 3.3 V del ESP32: problema de alimentacion)";
    case ESP_RST_SW:       return "SW       (reinicio por software)";
    case ESP_RST_PANIC:    return "PANIC    (error de software)";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT:      return "WDT      (watchdog)";
    case ESP_RST_EXT:      return "EXT      (pin EN / boton reset)";
    case ESP_RST_DEEPSLEEP:return "DEEPSLEEP";
    default:               return "otro / desconocido";
  }
}

// ─────────────────────────────────────────────────────────────────────
// SETUP
// ─────────────────────────────────────────────────────────────────────
void setup() {
  solenoidOFF();   // apagado antes que nada, sin prints aún

#if PIN_SENSOR_ALIM >= 0
  pinMode(PIN_SENSOR_ALIM, OUTPUT);
#endif
  alimentarSensor(false);

  Serial.begin(115200);
  serialListo = true;
  delay(200);
  Serial.println("\n[FertiTrack v5.9] Iniciando ESP_B (ciclo de DISPARO)...");
  Serial.printf("  Motivo del ultimo reinicio: %s\n", motivoReset());
  Serial.println("  Prueba de banco: p=solo disparo  +/-=armado  m=medir  s=stop  r=reset");

  prefs.begin("espb", false);
  cargarTiempos();

  Serial.printf("  Tiempos: arm=%lu ins=%lu est=%lu med=%lu ret=%lu ms\n",
                tiempoArmadoMs, tiempoInsercionMs, tiempoEstabilizarMs,
                tiempoMuestreoMs, tiempoRetraccionMs);
  Serial.printf("  Solenoide ON por ciclo: ~%lu ms | Driver: %s | Extraer al final: %s\n",
                tiempoArmadoMs + (EXTRAER_AL_FINAL ? tiempoRetraccionMs : 0UL),
                DRIVER_MOSFET ? "MOSFET" : "RELE",
                EXTRAER_AL_FINAL ? "si" : "no");

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print("  MAC de este ESP_B: "); Serial.println(WiFi.macAddress());
  Serial.println("  (debe ser CC:DB:A7:34:A0:FC)");

  if (esp_now_init() != ESP_OK) {
    Serial.println("  ERROR: ESP-NOW no inicializo");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, MAC_MAESTRO, 6);
  peerInfo.channel = CANAL_WIFI;
  peerInfo.encrypt = false;
  if (esp_now_add_peer(&peerInfo) != ESP_OK)
    Serial.println("  ERROR registrando al Maestro como peer");

  Serial.println("  ESP_B v5.9 listo. Solenoide APAGADO. Esperando MEDIR.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 0) Vigilante: ninguna fase con solenoide ON puede pasar del tope
  if (relePrendidoDesde != 0 && millis() - relePrendidoDesde > TIEMPO_ON_MAX_MS) {
    Serial.println("[ESP_B] VIGILANTE: solenoide ON demasiado tiempo. Apagando.");
    ejecutarEmergencia();
    return;
  }

  // 1) STOP: prioridad absoluta
  if (stopSolicitado) { ejecutarEmergencia(); return; }

  // 2) Serial de prueba + comandos pendientes
  atenderSerial();
  {
    ComandoRx c;
    while (sacarComando(c)) ejecutarComando(c);
  }
  if (colaDesbordada) {
    colaDesbordada = false;
    Serial.println("[ESP_B] AVISO: cola llena, se descartó un comando.");
  }

  // 3) Máquina de estados
  unsigned long ahora = millis();

  switch (estadoActual) {

    case EB_REPOSO:
      break;

    // ── 1) ARMAR: el émbolo se recoge y carga el resorte ────────────
    case EB_ARMANDO:
      if (ahora - tiempoInicioEtapa >= tiempoArmadoMs) {
        solenoidOFF();                       // ¡DISPARO! el resorte clava el sensor
        if (!soloDisparo) alimentarSensor(true);   // el sensor se activa ahora
        tiempoInicioEtapa = ahora;
        estadoActual      = EB_DISPARANDO;
      }
      break;

    // ── 2) DISPARAR: esperar a que el sensor quede enterrado ────────
    case EB_DISPARANDO:
      if (ahora - tiempoInicioEtapa >= tiempoInsercionMs) {
        if (soloDisparo) {
          estadoActual = EB_REPOSO;
          Serial.println("[PRUEBA] Disparo terminado. Solenoide apagado.");
        } else {
          tiempoInicioEtapa   = ahora;
          ultimaMuestraMillis = ahora;
          estadoActual        = EB_MIDIENDO;
          Serial.printf("[ESP_B] Sensor enterrado. Midiendo por %lu ms...\n",
                        tiempoEstabilizarMs + tiempoMuestreoMs);
        }
      }
      break;

    // ── 3) MEDIR con el solenoide OFF (bobina fría) ─────────────────
    case EB_MIDIENDO: {
      unsigned long elapsed = ahora - tiempoInicioEtapa;

      if (elapsed >= tiempoEstabilizarMs &&
          cantidadMuestras < MAX_MUESTRAS &&
          ahora - ultimaMuestraMillis >= INTERVALO_MUESTRA_MS) {
        muestras[cantidadMuestras++] = leerMuestraADC();
        ultimaMuestraMillis = ahora;
      }

      if (elapsed >= tiempoEstabilizarMs + tiempoMuestreoMs) {
        alimentarSensor(false);

        int mn, mx;
        ultimaLecturaPromedio = promedioRecortado(cantidadMuestras, mn, mx);
        Serial.printf("[ESP_B] Muestreo completo: %d muestras | promedio=%d | min=%d max=%d | rango=%d\n",
                      cantidadMuestras, ultimaLecturaPromedio, mn, mx, mx - mn);
        if (mx - mn > 800)
          Serial.println("  AVISO: rango alto (>800). Revisa el contacto del sensor con el suelo.");

#if EXTRAER_AL_FINAL
        tiempoInicioEtapa = ahora;
        estadoActual      = EB_EXTRAYENDO;
        solenoidON();                        // recoge el émbolo: saca el sensor
        Serial.printf("[ESP_B] Extrayendo sensor (%lu ms)...\n", tiempoRetraccionMs);
#else
        estadoActual = EB_REPOSO;
        Serial.printf("[ESP_B] Enviando HUMEDAD=%d al Maestro.\n", ultimaLecturaPromedio);
        enviarAMaestro("HUMEDAD", (ultimaLecturaPromedio > 0) ? ultimaLecturaPromedio : 0);
#endif
      }
      break;
    }

    // ── 4) EXTRAER y enviar el resultado ────────────────────────────
    case EB_EXTRAYENDO:
      if (ahora - tiempoInicioEtapa >= tiempoRetraccionMs) {
        solenoidOFF();
        estadoActual = EB_REPOSO;
        Serial.printf("[ESP_B] Ciclo completo. Enviando HUMEDAD=%d al Maestro.\n",
                      ultimaLecturaPromedio);
        enviarAMaestro("HUMEDAD", (ultimaLecturaPromedio > 0) ? ultimaLecturaPromedio : 0);
      }
      break;

    case EB_EMERGENCIA:
      break;
  }
}
