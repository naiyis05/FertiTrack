/*
  =========================================================================
  FertiTrack_ESP_B_v5.8.ino — ESP_B (sensor de humedad + solenoide JF-0530B)
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 (ESP_B) : CC:DB:A7:34:A0:FC
  MAC del MAESTRO (la vieja, AÚN SIN CAMBIAR): 20:50:0D:10:1E:E0
  =========================================================================

  -------------------------------------------------------------------
  QUÉ CAMBIA EN v5.8 (respecto a v5.7) — DRIVER MOSFET
  -------------------------------------------------------------------
  1) Nueva opción DRIVER_MOSFET (true por defecto). Con MOSFET de nivel
     lógico la lógica es la CONTRARIA al relé:
         GPIO26 = HIGH → solenoide ENERGIZADO
         GPIO26 = LOW  → solenoide apagado (pin siempre en OUTPUT/LOW)
     Si vuelves al módulo de relé azul, pon DRIVER_MOSFET false.
  2) Los tiempos largos de v5.7 NO ayudaron (el problema era eléctrico) y
     solo calientan la bobina: vuelven a 800 ms de inserción y 800 ms de
     retracción (ON por ciclo ≈ 3.3 s). Pulso de prueba "p": 1.5 s.
     Los tiempos guardados en NVS se reinician una vez (VERSION_NVS=58).
  3) Todo lo demás igual: sin enfriamiento, vigilante de 8 s, motivo del
     reinicio al arrancar, prueba de banco por Serial.

  CONEXIÓN DEL MOSFET (IRLZ44N, pines mirando de frente: G-D-S)
     GPIO26 → resistencia 220 Ω → G (compuerta)
     G → resistencia 10 kΩ → GND      (arranca siempre apagado)
     S (source) → GND común (GND del buck y GND del ESP32 unidos)
     D (drain)  → cable NEGRO del solenoide
     Cable ROJO del solenoide → +6 V del buck
     Diodo 1N4007 en antiparalelo en los bornes del solenoide
     (banda al rojo/+6 V). Condensador 470–2200 µF en la línea de 6 V.

  -------------------------------------------------------------------
  CAMBIOS ANTERIORES — v5.7 (respecto a v5.6)
  -------------------------------------------------------------------
  1) SE QUITÓ la espera de enfriamiento entre ciclos. Se pueden mandar
     "p" / "m" / MEDIR seguidos. Se conserva SOLO el vigilante de relé
     encendido (TIEMPO_ON_MAX_MS) para que un fallo nunca deje el
     solenoide energizado de forma indefinida.

  2) TIEMPOS MÁS LARGOS (por defecto):
        inserción 1500 ms (antes 500)    estabilizar 700 ms
        muestreo  1800 ms                retracción  1500 ms (antes 500)
        → relé ON por ciclo ≈ 4.0 s      vigilante: 8 s (antes 5 s)
     Pulso de prueba "p": 3 s (antes 1 s). En el Monitor Serie:
        '+' sube 0.5 s el pulso de prueba, '-' lo baja 0.5 s (0.5–6 s).
     Al subir de versión, los tiempos guardados en NVS se reinician a
     estos valores una sola vez (si el Maestro los calibra luego con
     SOL_*, se vuelven a guardar).

  3) DIAGNÓSTICO DEL REINICIO: al arrancar imprime el MOTIVO del reset.
        BROWNOUT → el 3.3 V del ESP32 cayó (problema de alimentación)
        POWERON  → la placa perdió y recuperó la alimentación por completo
        PANIC/WDT→ error de software
     Si tras cada "p" ves BROWNOUT o POWERON, el problema es eléctrico,
     no del código (ver abajo).

  4) Ya no se imprime por Serial antes de Serial.begin() (eso producía
     texto cortado como "[SOL] OFF (resorte sube el senso...").

  -------------------------------------------------------------------
  SI EL ESP32 SE REINICIA AL ACTIVAR EL SOLENOIDE (causa eléctrica)
  -------------------------------------------------------------------
  El solenoide pide ~1 A de golpe y al soltarlo genera un pico de
  voltaje inverso. Si comparte alimentación o cables de GND con el ESP32:
    - La alimentación cae (BROWNOUT) o el USB del PC se reinicia
      ("Not connected" en el Monitor Serie).
    - Con el voltaje caído, el solenoide tampoco tiene fuerza: por eso
      solo recorre una fracción de la carrera.
  Soluciones, de más a menos importante:
    a) Diodo 1N4007 EN ANTIPARALELO directo en los bornes del solenoide
       (banda al rojo/+6 V). Sin él, el pico reinicia el ESP32.
    b) El ESP32 por su USB, y el buck/solenoide desde una fuente APARTE
       (adaptador o batería) capaz de dar ≥2 A. Une solo los GND.
    c) Cables gruesos (≥22 AWG) y empalmados/atornillados para los 6 V y
       el solenoide. Los Dupont no sirven para 1 A (caen voltaje).
    d) Condensador de 470–2200 µF cerca del solenoide, en la línea de 6 V.
    e) Cable USB corto y bueno, directo al PC (sin hub).

  -------------------------------------------------------------------
  CONVENCIÓN MECÁNICA (igual que v5.6)
  -------------------------------------------------------------------
         relé OFF (reposo, frío) → sensor ARRIBA, fuera de la tierra
         relé ON  (pulso)        → sensor ABAJO, dentro de la tierra
  El sensor va en el extremo del émbolo que SALE cuando se activa el relé.

  -------------------------------------------------------------------
  PROTOCOLO — SIN CAMBIOS
  -------------------------------------------------------------------
  Maestro → ESP_B : "MEDIR", "STOP", "RESET",
                    "SOL_INS" / "SOL_EST" / "SOL_MED" / "SOL_RET" (ms)
  ESP_B  → Maestro : "HUMEDAD" (ADC promedio 0-4095, alto = suelo seco)

  -------------------------------------------------------------------
  HARDWARE
  -------------------------------------------------------------------
  PIN_PISTON_RELE    = 26  IN del módulo de relé de 1 canal (activo en LOW).
  PIN_SENSOR_HUMEDAD = 34  ADC1_CH6, sale del AO del módulo LM393.
  PIN_SENSOR_ALIM    = 25  alimenta el VCC del módulo solo al medir.

  CONEXIÓN DEL SOLENOIDE:
    Rojo del solenoide  → +6 V (salida del buck, medir con multímetro)
    Negro del solenoide → COM del relé
    NO del relé         → GND de la fuente de 6 V
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
  EB_REPOSO,          // relé OFF, esperando "MEDIR"
  EB_INSERTANDO,      // relé ON: sensor bajando a la tierra
  EB_MIDIENDO,        // relé ON: sensor en la tierra, tomando muestras
  EB_RETRACTANDO,     // relé OFF: el resorte sube el sensor
  EB_PRUEBA,          // relé ON (prueba de banco por Serial)
  EB_EMERGENCIA       // relé OFF, esperando RESET
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
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_PISTON_RELE    26
#define PIN_SENSOR_HUMEDAD 34
#define PIN_SENSOR_ALIM    25   // pon -1 si el VCC del sensor va directo a 3.3V

// true  = MOSFET de nivel lógico: HIGH enciende, LOW apaga.
// false = módulo de relé azul: LOW enciende, y apagado = pin suelto (INPUT).
#define DRIVER_MOSFET true

// (Solo relé) Relé activo en LOW. El "apagado" se hace soltando el pin (INPUT) para
// que un HIGH de 3.3 V no deje medio activado el optoacoplador de 5 V.
// Pon false solo si tu módulo corta bien con digitalWrite(HIGH).
#define RELE_OFF_COMO_ENTRADA true

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS DEL CICLO (ajustables desde el Maestro vía SOL_*, en NVS)
// ─────────────────────────────────────────────────────────────────────
const unsigned long DEF_INSERCION_MS   = 800;
const unsigned long DEF_ESTABILIZAR_MS = 700;
const unsigned long DEF_MUESTREO_MS    = 1800;
const unsigned long DEF_RETRACCION_MS  = 800;

unsigned long tiempoInsercionMs   = DEF_INSERCION_MS;    // sensor baja
unsigned long tiempoEstabilizarMs = DEF_ESTABILIZAR_MS;  // contacto estable
unsigned long tiempoMuestreoMs    = DEF_MUESTREO_MS;     // ventana de muestreo
unsigned long tiempoRetraccionMs  = DEF_RETRACCION_MS;   // el resorte sube

const uint32_t VERSION_NVS = 58;   // sube este número para forzar valores por defecto

// ─────────────────────────────────────────────────────────────────────
// SEGURIDAD
// ─────────────────────────────────────────────────────────────────────
const unsigned long TIEMPO_ON_MAX_MS = 8000;   // el relé jamás pasa de esto encendido
unsigned long pulsoPruebaMs = 1500;            // ajustable con '+' / '-'

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

void guardarTiemposSolenoide() {
  prefs.putULong("sol_ins", tiempoInsercionMs);
  prefs.putULong("sol_est", tiempoEstabilizarMs);
  prefs.putULong("sol_med", tiempoMuestreoMs);
  prefs.putULong("sol_ret", tiempoRetraccionMs);
}
void cargarTiemposSolenoide() {
  if (prefs.getUInt("ver", 0) != VERSION_NVS) {     // primera vez con v5.8
    tiempoInsercionMs   = DEF_INSERCION_MS;
    tiempoEstabilizarMs = DEF_ESTABILIZAR_MS;
    tiempoMuestreoMs    = DEF_MUESTREO_MS;
    tiempoRetraccionMs  = DEF_RETRACCION_MS;
    guardarTiemposSolenoide();
    prefs.putUInt("ver", VERSION_NVS);
    return;
  }
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
unsigned long relePrendidoDesde   = 0;   // 0 = relé apagado
unsigned long muestreoEfectivoMs  = 0;   // ventana real de este ciclo
bool          serialListo         = false;
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
// CONTROL DEL SOLENOIDE (única vía para tocar el pin del relé)
// ─────────────────────────────────────────────────────────────────────
void solenoidON() {
#if DRIVER_MOSFET
  digitalWrite(PIN_PISTON_RELE, HIGH);      // MOSFET: HIGH = conduce
  pinMode(PIN_PISTON_RELE, OUTPUT);
#else
  digitalWrite(PIN_PISTON_RELE, LOW);       // relé: LOW = activo
  pinMode(PIN_PISTON_RELE, OUTPUT);
#endif
  relePrendidoDesde = millis();
  if (relePrendidoDesde == 0) relePrendidoDesde = 1;
  if (serialListo) Serial.println("[SOL] ON  (pulso: sensor baja)");
}

void solenoidOFF() {
#if DRIVER_MOSFET
  digitalWrite(PIN_PISTON_RELE, LOW);       // MOSFET: LOW = apagado
  pinMode(PIN_PISTON_RELE, OUTPUT);
#elif RELE_OFF_COMO_ENTRADA
  pinMode(PIN_PISTON_RELE, INPUT);          // alta impedancia → relé suelta seguro
#else
  pinMode(PIN_PISTON_RELE, OUTPUT);
  digitalWrite(PIN_PISTON_RELE, HIGH);
#endif
  relePrendidoDesde = 0;
  if (serialListo) Serial.println("[SOL] OFF (resorte sube el sensor)");
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
// INICIO DEL CICLO DE MEDICIÓN
// ─────────────────────────────────────────────────────────────────────
void iniciarMedicion() {
  if (estadoActual != EB_REPOSO) {
    Serial.println("[ESP_B] MEDIR ignorado: ocupado o en emergencia.");
    return;
  }

  // Recorta la ventana de muestreo si la suma pasaría el tope de seguridad
  unsigned long fijos = tiempoInsercionMs + tiempoEstabilizarMs;
  muestreoEfectivoMs = tiempoMuestreoMs;
  if (fijos + muestreoEfectivoMs + 500UL > TIEMPO_ON_MAX_MS) {
    muestreoEfectivoMs = (TIEMPO_ON_MAX_MS > fijos + 1000UL) ? (TIEMPO_ON_MAX_MS - fijos - 500UL) : 500UL;
    Serial.printf("[ESP_B] AVISO: ventana de muestreo recortada a %lu ms por el tope de %lu ms.\n",
                  muestreoEfectivoMs, TIEMPO_ON_MAX_MS);
  }

  cantidadMuestras    = 0;
  ultimaMuestraMillis = 0;
  estadoActual        = EB_INSERTANDO;
  tiempoInicioEtapa   = millis();

  alimentarSensor(true);
  solenoidON();

  Serial.printf("[ESP_B] MEDIR iniciado. Tiempos: ins=%lu est=%lu med=%lu ret=%lu | ON total ~%lu ms\n",
                tiempoInsercionMs, tiempoEstabilizarMs, muestreoEfectivoMs, tiempoRetraccionMs,
                fijos + muestreoEfectivoMs);
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
    iniciarMedicion();
  }
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
//   p = pulso de prueba   + / - = alarga / acorta el pulso (0.5 s)
//   m = ciclo completo    s = stop     r = reset
// ─────────────────────────────────────────────────────────────────────
void atenderSerial() {
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == 'm' || ch == 'M') {
      iniciarMedicion();
    } else if (ch == 'p' || ch == 'P') {
      if (estadoActual != EB_REPOSO) { Serial.println("[PRUEBA] Ocupado."); continue; }
      estadoActual      = EB_PRUEBA;
      tiempoInicioEtapa = millis();
      Serial.printf("[PRUEBA] Pulso de %lu ms: observa qué extremo del émbolo SALE y cuánto.\n",
                    pulsoPruebaMs);
      solenoidON();
    } else if (ch == '+') {
      if (pulsoPruebaMs + 500UL <= 6000UL) pulsoPruebaMs += 500UL;
      Serial.printf("[PRUEBA] Pulso de prueba = %lu ms\n", pulsoPruebaMs);
    } else if (ch == '-') {
      if (pulsoPruebaMs >= 1000UL) pulsoPruebaMs -= 500UL;
      Serial.printf("[PRUEBA] Pulso de prueba = %lu ms\n", pulsoPruebaMs);
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
  // Solenoide apagado antes que nada (pin suelto = relé suelto). Sin prints aún.
  solenoidOFF();

#if PIN_SENSOR_ALIM >= 0
  pinMode(PIN_SENSOR_ALIM, OUTPUT);
#endif
  alimentarSensor(false);

  Serial.begin(115200);
  serialListo = true;
  delay(200);
  Serial.println("\n[FertiTrack v5.8] Iniciando ESP_B (sensor + solenoide en PULSO)...");
  Serial.printf("  Motivo del ultimo reinicio: %s\n", motivoReset());
  Serial.println("  Prueba de banco: p=pulso  +/-=duracion  m=medir  s=stop  r=reset");

  prefs.begin("espb", false);
  cargarTiemposSolenoide();

  Serial.printf("  Tiempos: ins=%lu est=%lu med=%lu ret=%lu ms | ON/ciclo ~%lu ms (tope %lu)\n",
                tiempoInsercionMs, tiempoEstabilizarMs, tiempoMuestreoMs, tiempoRetraccionMs,
                tiempoInsercionMs + tiempoEstabilizarMs + tiempoMuestreoMs, TIEMPO_ON_MAX_MS);
  Serial.printf("  Pulso de prueba: %lu ms\n", pulsoPruebaMs);

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

  Serial.println("  ESP_B v5.8 listo. Solenoide APAGADO. Esperando MEDIR.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 0) Vigilante: el relé nunca puede quedar ON más del tope
  if (relePrendidoDesde != 0 && millis() - relePrendidoDesde > TIEMPO_ON_MAX_MS) {
    Serial.println("[ESP_B] VIGILANTE: relé ON demasiado tiempo. Apagando.");
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

    case EB_INSERTANDO:
      if (ahora - tiempoInicioEtapa >= tiempoInsercionMs) {
        tiempoInicioEtapa   = ahora;
        ultimaMuestraMillis = ahora;
        estadoActual        = EB_MIDIENDO;
        Serial.printf("[ESP_B] Sensor en la tierra. Midiendo por %lu ms...\n",
                      tiempoEstabilizarMs + muestreoEfectivoMs);
      }
      break;

    case EB_MIDIENDO: {
      unsigned long elapsed = ahora - tiempoInicioEtapa;

      if (elapsed >= tiempoEstabilizarMs &&
          cantidadMuestras < MAX_MUESTRAS &&
          ahora - ultimaMuestraMillis >= INTERVALO_MUESTRA_MS) {
        muestras[cantidadMuestras++] = leerMuestraADC();
        ultimaMuestraMillis = ahora;
      }

      if (elapsed >= tiempoEstabilizarMs + muestreoEfectivoMs) {
        solenoidOFF();            // fin del pulso: el resorte sube el sensor
        alimentarSensor(false);
        tiempoInicioEtapa = ahora;
        estadoActual      = EB_RETRACTANDO;

        int mn, mx;
        ultimaLecturaPromedio = promedioRecortado(cantidadMuestras, mn, mx);
        Serial.printf("[ESP_B] Muestreo completo: %d muestras | promedio=%d | min=%d max=%d | rango=%d\n",
                      cantidadMuestras, ultimaLecturaPromedio, mn, mx, mx - mn);
        if (mx - mn > 800)
          Serial.println("  AVISO: rango alto (>800). Revisa el contacto del sensor con el suelo.");
        Serial.printf("[ESP_B] Retractando (%lu ms)...\n", tiempoRetraccionMs);
      }
      break;
    }

    case EB_RETRACTANDO:
      if (ahora - tiempoInicioEtapa >= tiempoRetraccionMs) {
        estadoActual = EB_REPOSO;
        Serial.printf("[ESP_B] Sensor arriba. Enviando HUMEDAD=%d al Maestro.\n",
                      ultimaLecturaPromedio);
        enviarAMaestro("HUMEDAD", (ultimaLecturaPromedio > 0) ? ultimaLecturaPromedio : 0);
      }
      break;

    case EB_PRUEBA:
      if (ahora - tiempoInicioEtapa >= pulsoPruebaMs) {
        solenoidOFF();
        estadoActual = EB_REPOSO;
        Serial.println("[PRUEBA] Fin del pulso. Solenoide apagado.");
      }
      break;

    case EB_EMERGENCIA:
      break;
  }
}
