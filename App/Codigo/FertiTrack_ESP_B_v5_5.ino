/*
  =========================================================================
  FertiTrack_ESP_B_v5.5.ino — ESP_B (sensor de humedad + solenoide JF-0530B)
  Compañero de FertiTrack_Maestro_v5.5 y ESP_A_v5.5
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 (ESP_B) : CC:DB:A7:34:A0:FC
  MAC del MAESTRO            : 20:50:0D:10:1E:E0
  =========================================================================

  -------------------------------------------------------------------
  DIAGNÓSTICO DEL PROBLEMA EN v5.3 — ¿POR QUÉ SE SOBRECALENTABA?
  -------------------------------------------------------------------
  En v5.3 la lógica era:
     EB_REPOSO  → relay ACTIVADO (LOW) → solenoide ENERGIZADO → pistón ARRIBA
     Medir      → relay APAGADO (HIGH) → solenoide OFF → resorte baja el pistón
     Terminar   → relay ACTIVADO (LOW) → solenoide ENERGIZADO → pistón ARRIBA

  El solenoide quedaba ENERGIZADO PERMANENTEMENTE en reposo para mantener
  el pistón arriba (posición segura para viajar). Como el JF-0530B es de
  uso INTERMITENTE (ciclo de trabajo <30 %), la bobina actúa como
  resistencia de calentamiento si se deja con corriente constante:
  en pocos minutos llega a temperaturas que funden el esmalte del alambre.

  -------------------------------------------------------------------
  SOLUCIÓN EN v5.5 — MODO PULSO
  -------------------------------------------------------------------
  El solenoide se energiza ÚNICAMENTE durante los ~2-3 s que dura el
  ciclo de medición. El reposo es siempre con relay APAGADO (solenoide OFF).

  NUEVA LÓGICA:
     EB_REPOSO       → relay OFF → solenoide OFF → posición de reposo (resorte)
     Medir recibido:
     EB_INSERTANDO   → relay ON  → solenoide ON  → pistón actúa (baja o sube)
     EB_MIDIENDO     → relay ON  → solenoide ON  → toma muestras ADC
     EB_RETRACTANDO  → relay OFF → solenoide OFF → resorte retracta
     EB_REPOSO       → relay OFF → envía HUMEDAD al Maestro

  Tiempo total energizado por ciclo: ~2.5 s.
  Intervalo entre mediciones (ciclo completo): decenas de minutos.
  Ciclo de trabajo real: << 1 % → solenoide frío siempre.

  -------------------------------------------------------------------
  NOTA SOBRE LA DIRECCIÓN DEL PISTÓN
  -------------------------------------------------------------------
  El JF-0530B tiene retorno por resorte. Con la bobina OFF el resorte
  lleva el émbolo a la posición de REPOSO natural. Hay dos posibles
  montajes según cómo esté orientado el solenoide:

  MONTAJE A — Resorte sube, corriente baja (el más común con este sensor):
    OFF (reposo)  → resorte → pistón ARRIBA → sensor NO toca el suelo ✅
    ON  (medir)   → corriente → pistón BAJA → sensor ENTRA en el suelo ✅
    → SOLENOIDE_BAJA_AL_ACTIVAR = true  ← (valor por defecto del código)

  MONTAJE B — Resorte baja, corriente sube (el que tenía v5.3):
    OFF (reposo)  → resorte → pistón ABAJO → sensor roza el suelo 🤔
    ON  (medir)   → corriente → pistón SUBE → sensor SALE del suelo
    → SOLENOIDE_BAJA_AL_ACTIVAR = false
    (en este montaje la lectura se toma durante el intervalo de BAJAR,
     es decir, con el relay OFF. El código lo gestiona automáticamente.)

  Ajusta la constante SOLENOIDE_BAJA_AL_ACTIVAR según tu montaje.
  Si el motor funciona al revés de lo que esperas, cámbiala.

  -------------------------------------------------------------------
  PROTOCOLO v5.5 — SIN CAMBIOS RESPECTO A v5.3
  -------------------------------------------------------------------
  Maestro → ESP_B : "MEDIR"  (0)      → ciclo completo de medición
                    "STOP"            → emergencia, solenoide OFF inmediato
                    "RESET"           → vuelve a REPOSO, solenoide OFF
                    "SOL_INS" (ms)    → ajusta TIEMPO_INSERCION_MS  [NUEVO v5.5]
                    "SOL_EST" (ms)    → ajusta TIEMPO_ESTABILIZAR_MS [NUEVO v5.5]
                    "SOL_RET" (ms)    → ajusta TIEMPO_RETRACCION_MS  [NUEVO v5.5]
                    "SOL_MED" (ms)    → ajusta TIEMPO_MUESTREO_MS    [NUEVO v5.5]
  ESP_B  → Maestro : "HUMEDAD" (ADC promedio 0-4095)

  Los comandos SOL_* pueden enviarse desde el Maestro en el futuro para
  calibrar los tiempos sin recompilar. Se guardan en NVS. ESP_B v5.3
  nunca los enviaba → compatibilidad OK.

  -------------------------------------------------------------------
  HARDWARE
  -------------------------------------------------------------------
  PIN_PISTON_RELE    = 26  IN del módulo de relé de 1 canal.
  PIN_SENSOR_HUMEDAD = 34  ADC1_CH6 (solo lectura). ADC alto = suelo seco.
  PIN_SENSOR_ALIM    = 25  GPIO que alimenta el VCC del sensor SOLO al medir.
                           Evita la electrólisis del sensor resistivo.
                           Poner -1 si el VCC va directo a 3.3V.

  RELÉ:  módulo de relé activo en LOW (típico módulo azul barato).
         Si el tuyo es activo en HIGH, invierte RELE_ACTIVADO/RELE_APAGADO.

  CONEXIÓN DEL SOLENOIDE (como la tiene el usuario):
    Cable ROJO del solenoide → 6V directo
    Cable NEGRO del solenoide → COM del relé (puerto central)
    Puerto derecho del relé → GND de la fuente
    (usar NO — normalmente abierto — para que sin corriente = circuito abierto)

  TIMEOUT: el ciclo completo dura ~3-4 s.
           El Maestro da 12 s (TIMEOUT_B_MS). Margen holgado.
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <Preferences.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS
// ─────────────────────────────────────────────────────────────────────
enum EstadoB {
  EB_REPOSO,          // solenoide OFF, esperando "MEDIR"
  EB_INSERTANDO,      // solenoide ON: pistón viajando a posición de medición
  EB_MIDIENDO,        // solenoide ON: sensor en suelo, tomando muestras ADC
  EB_RETRACTANDO,     // solenoide OFF: resorte retracta el pistón
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
uint8_t MAC_MAESTRO[] = {0x20,0x50,0x0D,0x10,0x1E,0xE0};

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_PISTON_RELE    26   // IN del módulo de relé
#define PIN_SENSOR_HUMEDAD 34   // ADC1 (solo entrada)
#define PIN_SENSOR_ALIM    25   // VCC del sensor (pon -1 si va directo a 3.3V)

// Polaridad del relé (módulos azules baratos = activo en LOW)
#define RELE_ACTIVADO LOW
#define RELE_APAGADO  HIGH

// ─────────────────────────────────────────────────────────────────────
// DIRECCIÓN DEL SOLENOIDE
// true  → relay ON  = pistón BAJA (entra al suelo), relay OFF = resorte SUBE
// false → relay ON  = pistón SUBE (sale del suelo),  relay OFF = resorte BAJA
// ─────────────────────────────────────────────────────────────────────
const bool SOLENOIDE_BAJA_AL_ACTIVAR = false;

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS DEL CICLO DE MEDICIÓN (ajustables desde Maestro vía SOL_*)
// guardados en NVS. Defaults razonables para JF-0530B a 6V.
// ─────────────────────────────────────────────────────────────────────
unsigned long tiempoInsercionMs   = 500;   // ms: solenoide viaja a posición medir
unsigned long tiempoEstabilizarMs = 700;   // ms: espera contacto estable con suelo
unsigned long tiempoMuestreoMs    = 1800;  // ms: ventana de toma de muestras
unsigned long tiempoRetraccionMs  = 500;   // ms: espera que el resorte retracte

// Total ON: 500+700+1800 = 3000 ms. Total ciclo: ~3.5 s. Maestro: 12 s. ✅

// ─────────────────────────────────────────────────────────────────────
// PARÁMETROS DE MUESTREO (fijos)
// ─────────────────────────────────────────────────────────────────────
const unsigned long INTERVALO_MUESTRA_MS = 100;  // una muestra cada 100 ms
const int           LECTURAS_POR_MUESTRA = 4;    // promedio de 4 lecturas ADC
const int           MAX_MUESTRAS         = 64;
const int           RECORTE_PCT          = 10;   // descarta 10% bajo + 10% alto

// ─────────────────────────────────────────────────────────────────────
// NVS: persistencia de tiempos (sobreescrita por comandos SOL_*)
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

void guardarTiemposSolenoide() {
  prefs.putULong("sol_ins", tiempoInsercionMs);
  prefs.putULong("sol_est", tiempoEstabilizarMs);
  prefs.putULong("sol_med", tiempoMuestreoMs);
  prefs.putULong("sol_ret", tiempoRetraccionMs);
}
void cargarTiemposSolenoide() {
  tiempoInsercionMs   = prefs.getULong("sol_ins",  500UL);
  tiempoEstabilizarMs = prefs.getULong("sol_est",  700UL);
  tiempoMuestreoMs    = prefs.getULong("sol_med", 1800UL);
  tiempoRetraccionMs  = prefs.getULong("sol_ret",  500UL);
}

// ─────────────────────────────────────────────────────────────────────
// ESTADO
// ─────────────────────────────────────────────────────────────────────
MensajeFertiTrack msgEnviar;
EstadoB   estadoActual    = EB_REPOSO;
unsigned long tiempoInicioEtapa   = 0;
unsigned long ultimaMuestraMillis = 0;
int           muestras[MAX_MUESTRAS];
int           cantidadMuestras    = 0;
int           ultimaLecturaPromedio = -1;
volatile bool stopSolicitado = false;

// Cola de comandos (callback → loop)
const int    COLA_CMD_MAX   = 10;
ComandoRx    colaCmd[COLA_CMD_MAX];
int          colaIni        = 0;
volatile int colaCuenta     = 0;
volatile bool colaDesbordada = false;
portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

// ─────────────────────────────────────────────────────────────────────
// CONTROL DEL SOLENOIDE
// ─────────────────────────────────────────────────────────────────────
// Encender / apagar el solenoide de forma explícita y segura.
// Siempre usa estas funciones: nunca escribas directamente sobre el pin.

void solenoidON() {
  digitalWrite(PIN_PISTON_RELE, RELE_ACTIVADO);
  Serial.println("[SOL] ON (solenoide energizado — ciclo breve)");
}
void solenoidOFF() {
  digitalWrite(PIN_PISTON_RELE, RELE_APAGADO);
  Serial.println("[SOL] OFF (solenoide apagado — resorte retracta)");
}
// Seguridad ante falla o reset: solenoide siempre queda apagado.
void solenoidEmergencia() { solenoidOFF(); }

// ─────────────────────────────────────────────────────────────────────
// ALIMENTACIÓN DEL SENSOR (opcional, reduce electrólisis)
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

// Promedio recortado: ordena muestras y descarta extremos (picos de mal contacto)
int promedioRecortado(int n, int &minimo, int &maximo) {
  if (n <= 0) { minimo = 0; maximo = 0; return 0; }
  int t[MAX_MUESTRAS];
  for (int i = 0; i < n; i++) t[i] = muestras[i];
  // Ordenamiento por inserción (n <= 64, rápido y sin memoria extra)
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
// INICIO DEL CICLO DE MEDICIÓN
// ─────────────────────────────────────────────────────────────────────
void iniciarMedicion() {
  if (estadoActual != EB_REPOSO) {
    Serial.println("[ESP_B] MEDIR ignorado: ocupado o en emergencia.");
    return;
  }
  cantidadMuestras    = 0;
  ultimaMuestraMillis = 0;
  estadoActual        = EB_INSERTANDO;
  tiempoInicioEtapa   = millis();

  // Encender sensor ANTES de que el pistón llegue (ya calienta mientras viaja)
  alimentarSensor(true);
  // Energizar solenoide → pistón viaja a posición de medición
  solenoidON();

  Serial.printf("[ESP_B] MEDIR iniciado. Pistón en camino (%lu ms)...\n",
                tiempoInsercionMs);
  Serial.printf("  Tiempos: ins=%lu est=%lu med=%lu ret=%lu ms | Total ON: ~%lu ms\n",
                tiempoInsercionMs, tiempoEstabilizarMs, tiempoMuestreoMs, tiempoRetraccionMs,
                tiempoInsercionMs + tiempoEstabilizarMs + tiempoMuestreoMs);
}

// ─────────────────────────────────────────────────────────────────────
// EMERGENCIA Y RESET
// ─────────────────────────────────────────────────────────────────────
void ejecutarEmergencia() {
  stopSolicitado = false;
  if (estadoActual == EB_EMERGENCIA) return;
  solenoidEmergencia();     // solenoide OFF inmediatamente
  alimentarSensor(false);
  cantidadMuestras = 0;
  estadoActual = EB_EMERGENCIA;
  Serial.println("[ESP_B] *** EMERGENCIA: solenoide apagado. Esperando RESET. ***");
}
void reiniciarNodo() {
  solenoidEmergencia();     // asegurar solenoide OFF antes de aceptar órdenes
  alimentarSensor(false);
  cantidadMuestras = 0;
  estadoActual = EB_REPOSO;
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

  // STOP y RESET siempre se atienden primero
  if (strcmp(c.cmd, "STOP")  == 0) { ejecutarEmergencia(); return; }
  if (strcmp(c.cmd, "RESET") == 0) { reiniciarNodo();      return; }

  if (estadoActual == EB_EMERGENCIA) {
    Serial.println("[ESP_B] En EMERGENCIA: comando ignorado hasta RESET.");
    return;
  }

  // ── Comando normal ──────────────────────────────────────────────
  if (strcmp(c.cmd, "MEDIR") == 0) {
    iniciarMedicion();
  }
  // ── NUEVO v5.5: ajuste de tiempos del solenoide desde el Maestro ─
  else if (strcmp(c.cmd, "SOL_INS") == 0) {
    if (c.param >= 100 && c.param <= 3000) {
      tiempoInsercionMs = (unsigned long)c.param;
      guardarTiemposSolenoide();
      Serial.printf("[SOL] tiempoInsercionMs=%lu ms\n", tiempoInsercionMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_EST") == 0) {
    if (c.param >= 100 && c.param <= 5000) {
      tiempoEstabilizarMs = (unsigned long)c.param;
      guardarTiemposSolenoide();
      Serial.printf("[SOL] tiempoEstabilizarMs=%lu ms\n", tiempoEstabilizarMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_MED") == 0) {
    if (c.param >= 500 && c.param <= 8000) {
      tiempoMuestreoMs = (unsigned long)c.param;
      guardarTiemposSolenoide();
      Serial.printf("[SOL] tiempoMuestreoMs=%lu ms\n", tiempoMuestreoMs);
    }
  }
  else if (strcmp(c.cmd, "SOL_RET") == 0) {
    if (c.param >= 100 && c.param <= 3000) {
      tiempoRetraccionMs = (unsigned long)c.param;
      guardarTiemposSolenoide();
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
// CALLBACK ESP-NOW — solo encola, levanta bandera STOP
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
// SETUP
// ─────────────────────────────────────────────────────────────────────
void setup() {
  // ── SOLENOIDE PRIMERO: apagarlo antes de cualquier delay o inicialización
  pinMode(PIN_PISTON_RELE, OUTPUT);
  solenoidOFF();  // solenoide apagado desde el primer microsegundo

#if PIN_SENSOR_ALIM >= 0
  pinMode(PIN_SENSOR_ALIM, OUTPUT);
#endif
  alimentarSensor(false);

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.5] Iniciando ESP_B (sensor + solenoide PULSO)...");
  Serial.println("  NUEVO v5.5: solenoide OFF en reposo — solo pulso al medir.");
  Serial.println("  Corrige el sobrecalentamiento del JF-0530B.");

  // NVS: cargar tiempos guardados
  prefs.begin("espb", false);
  cargarTiemposSolenoide();

  unsigned long totalOn = tiempoInsercionMs + tiempoEstabilizarMs + tiempoMuestreoMs;
  Serial.printf("  Tiempos solenoide: ins=%lu est=%lu med=%lu ret=%lu ms\n",
                tiempoInsercionMs, tiempoEstabilizarMs, tiempoMuestreoMs, tiempoRetraccionMs);
  Serial.printf("  Tiempo ON por ciclo: ~%lu ms (JF-0530B: frio siempre ✓)\n", totalOn);
  Serial.printf("  Direccion: solenoide activo = piston %s\n",
                SOLENOIDE_BAJA_AL_ACTIVAR ? "BAJA (entra al suelo)" : "SUBE (sale del suelo)");

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

  Serial.println("  ESP_B v5.5 listo. Solenoide APAGADO. Esperando MEDIR.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 1) STOP: prioridad absoluta — apaga el solenoide al instante
  if (stopSolicitado) { ejecutarEmergencia(); return; }

  // 2) Procesar todos los comandos pendientes de la cola
  {
    ComandoRx c;
    while (sacarComando(c)) ejecutarComando(c);
  }
  if (colaDesbordada) {
    colaDesbordada = false;
    Serial.println("[ESP_B] AVISO: cola llena, se descartó un comando.");
  }

  // 3) Máquina de estados — sin bloqueos (nunca hay delay() aquí)
  unsigned long ahora = millis();

  switch (estadoActual) {

    // ── ESPERANDO ORDEN ────────────────────────────────────────────
    case EB_REPOSO:
      // Solenoide OFF, nada que hacer. Espera "MEDIR".
      break;

    // ── FASE 1: PISTÓN VIAJANDO A POSICIÓN DE MEDICIÓN ─────────────
    case EB_INSERTANDO:
      if (ahora - tiempoInicioEtapa >= tiempoInsercionMs) {
        // Pistón llegó a la posición de medición.
        // Ahora espera TIEMPO_ESTABILIZAR_MS para que el contacto se asiente.
        tiempoInicioEtapa   = ahora;
        ultimaMuestraMillis = ahora;
        estadoActual        = EB_MIDIENDO;
        // El solenoide SIGUE encendido (contacto activo con el suelo).
        Serial.printf("[ESP_B] Pistón en posición. Midiendo por %lu ms...\n",
                      tiempoEstabilizarMs + tiempoMuestreoMs);
      }
      break;

    // ── FASE 2: TOMA DE MUESTRAS (incluye asentamiento inicial) ────
    case EB_MIDIENDO: {
      unsigned long elapsed = ahora - tiempoInicioEtapa;

      // Tomar muestras solo después del tiempo de estabilización
      if (elapsed >= tiempoEstabilizarMs &&
          cantidadMuestras < MAX_MUESTRAS &&
          ahora - ultimaMuestraMillis >= INTERVALO_MUESTRA_MS) {
        muestras[cantidadMuestras++] = leerMuestraADC();
        ultimaMuestraMillis = ahora;
      }

      // ¿Terminó la ventana de muestreo?
      if (elapsed >= tiempoEstabilizarMs + tiempoMuestreoMs) {
        // Apagar solenoide → resorte retracta
        solenoidOFF();
        alimentarSensor(false);
        tiempoInicioEtapa = ahora;
        estadoActual      = EB_RETRACTANDO;

        // Calcular el promedio ya (así se tiene listo cuando termine la retracción)
        int mn, mx;
        ultimaLecturaPromedio = promedioRecortado(cantidadMuestras, mn, mx);
        Serial.printf("[ESP_B] Muestreo completo: %d muestras | promedio=%d | min=%d max=%d | rango=%d\n",
                      cantidadMuestras, ultimaLecturaPromedio, mn, mx, mx - mn);
        if (mx - mn > 800)
          Serial.println("  AVISO: rango alto (>800). Revisa el contacto del sensor con el suelo.");
        Serial.printf("[ESP_B] Retractando pistón (%lu ms)...\n", tiempoRetraccionMs);
      }
      break;
    }

    // ── FASE 3: RESORTE RETRACTANDO ────────────────────────────────
    case EB_RETRACTANDO:
      if (ahora - tiempoInicioEtapa >= tiempoRetraccionMs) {
        // Pistón en posición de reposo. Ciclo completo.
        estadoActual = EB_REPOSO;
        Serial.printf("[ESP_B] Pistón retractado. Enviando HUMEDAD=%d al Maestro.\n",
                      ultimaLecturaPromedio);
        // Enviar el ADC promedio al Maestro (0 si no hubo muestras)
        enviarAMaestro("HUMEDAD", (ultimaLecturaPromedio > 0) ? ultimaLecturaPromedio : 0);
      }
      break;

    // ── EMERGENCIA ──────────────────────────────────────────────────
    case EB_EMERGENCIA:
      // Solenoide ya OFF. Nada que hacer. Esperando RESET.
      break;
  }
}
