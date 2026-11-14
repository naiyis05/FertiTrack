/*
  =========================================================================
  FertiTrack_ESP_A_v5.5.ino — ESP_A (locomoción + brazos)
  Compañero de FertiTrack_Maestro_v5.5
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 (ESP_A) : C8:85:41:2C:A5:BC
  MAC del MAESTRO            : 20:50:0D:10:1E:E0
  =========================================================================

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.5 (control total de velocidades desde el Maestro)
  -------------------------------------------------------------------

  1) VELOCIDADES AJUSTABLES EN TIEMPO REAL.
     velRapida / velMedia / velLenta / acel dejan de ser constexpr y pasan
     a ser variables float. El Maestro v5.5 las sincroniza al arrancar y
     cada vez que se cambian desde la app, vía comandos nuevos:
       "VEL_R" parametro = pasos/s   → velRapida
       "VEL_M" parametro = pasos/s   → velMedia
       "VEL_L" parametro = pasos/s   → velLenta
       "ACEL"  parametro = pasos/s²  → acel
     ESP_A las aplica de inmediato en la variable y las guarda en NVS.
     Al reiniciar, las carga de NVS; si no hay nada guardado usa los
     valores por defecto del paso completo (300/200/100/200).

  2) TIEMPOS DE ACTUADORES AJUSTABLES.
     tiempoExtenderMs / tiempoRecogerMs dejan de ser const y se
     actualizan con:
       "ACT_EXT" parametro = ms totales (medido+margen) → tiempoExtenderMs
       "ACT_REC" parametro = ms totales                 → tiempoRecogerMs
     Se guardan en NVS igual que las velocidades.

  3) MODO FUERZA (modoFuerza): activado por el Maestro.
     Cuando el Maestro tiene modoFuerza activo, sincroniza
     velRapida = velLenta (valor bajo). Así, cuando ESP_A recibe
     "RETORNAR", usa su velRapida local (que el Maestro dejó en
     velLenta) y el retorno también va lento. No hace falta un comando
     explícito "MODO_FUERZA": el mecanismo de sincronía de VEL_R lo
     gestiona automáticamente. Cuando el Maestro desactiva modoFuerza,
     resincroniza VEL_R con el valor rápido original.

  4) iniciarAvance() y iniciarRetorno() usan las variables locales en
     lugar de las constexpr. Ningún movimiento queda exento de los
     valores sincronizados.

  5) NVS (Preferences). Nuevo include y namespace "espa". Claves:
     vel_r, vel_m, vel_l, acel, act_ext, act_rec.
     Garantiza que si ESP_A se reinicia solo (brownout) mientras el
     Maestro sigue corriendo, usa los últimos valores recibidos hasta
     la próxima sincronía.

  -------------------------------------------------------------------
  PROTOCOLO COMPLETO v5.5
  -------------------------------------------------------------------
  Maestro -> ESP_A : AVANZAR_RAPIDO / AVANZAR_MEDIO / AVANZAR_LENTO
                       parametro = pasos
                     RETORNAR   parametro = 0
                     BRAZO_RIEGO / BRAZO_FERTI   parametro = 1 baja / 0 sube
                     STOP
                     RESET
                     VEL_R / VEL_M / VEL_L  parametro = pasos/s   [NUEVO v5.5]
                     ACEL                    parametro = pasos/s²  [NUEVO v5.5]
                     ACT_EXT / ACT_REC       parametro = ms        [NUEVO v5.5]
  ESP_A -> Maestro : LISTO (posición), BRAZO_OK (1/0)

  COMPATIBILIDAD: sin cambios en la estructura MensajeFertiTrack ni
  en los comandos existentes. Maestro v5.4 no envía los comandos
  nuevos → ESP_A usa sus defaults de NVS (o valores iniciales).

  HARDWARE: 2× NEMA 17 con A4988/DRV8825, MS1=MS2=MS3=GND (paso
  completo). STEP=14, DIR=27, ENABLE=26.
  ACT_RIEGO: IN1=16 / IN2=17   ACT_FERTI: IN1=18 / IN2=19
  LIBRERÍA: AccelStepper (Mike McCauley)
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <AccelStepper.h>
#include <Preferences.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS
// ─────────────────────────────────────────────────────────────────────
enum EstadoESP_A {
  EA_REPOSO,
  EA_AVANZANDO,
  EA_RETORNANDO,
  EA_BAJANDO_BRAZO,
  EA_SUBIENDO_BRAZO,
  EA_EMERGENCIA
};
enum BrazoPendiente { BRAZO_NINGUNO, BRAZO_RIEGO, BRAZO_FERTI };

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
#define PIN_STEP       14
#define PIN_DIR        27
#define PIN_ENABLE     26   // LOW = motores energizados
#define ACT_RIEGO_IN1  16
#define ACT_RIEGO_IN2  17
#define ACT_FERTI_IN1  18
#define ACT_FERTI_IN2  19

// ─────────────────────────────────────────────────────────────────────
// VELOCIDADES Y TIEMPOS — variables (v5.5), ajustadas por el Maestro
// Espejo de los valores del Maestro. Se sincronizan vía VEL_R/M/L/ACEL.
// ─────────────────────────────────────────────────────────────────────
float velRapida = 300.0f;   // pasos/s — retorno al origen
float velMedia  = 200.0f;   // pasos/s — sensado, fertilización
float velLenta  = 100.0f;   // pasos/s — riego sobre zona seca
float acel      = 200.0f;   // pasos/s²

// MODO FUERZA: gestionado automáticamente por sincronía de VEL_R.
// Cuando el Maestro activa modoFuerza, sincroniza velRapida = velLenta.
// Así RETORNAR también usa la velocidad lenta. No se necesita flag extra.

// Actuadores lineales (v5.5): ajustados por ACT_EXT / ACT_REC
unsigned long tiempoExtenderMs = 5010;  // ms totales (medido + margen)
unsigned long tiempoRecogerMs  = 5120;

// Tiempo de inactividad antes de soltar los motores (siempre fijo)
const unsigned long TIEMPO_SOLTAR_MOTORES_MS = 30000;

// Dirección del motor (si avanza al revés, poner true)
const bool INVERTIR_DIRECCION = false;
// Soltar motores en emergencia
const bool SOLTAR_MOTORES_EN_EMERGENCIA = true;

// ─────────────────────────────────────────────────────────────────────
// NVS (v5.5): guardar y cargar configuración recibida del Maestro
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

void guardarVelocidades() {
  prefs.putFloat("vel_r", velRapida);
  prefs.putFloat("vel_m", velMedia);
  prefs.putFloat("vel_l", velLenta);
  prefs.putFloat("acel",  acel);
}
void guardarActuadores() {
  prefs.putULong("act_ext", tiempoExtenderMs);
  prefs.putULong("act_rec", tiempoRecogerMs);
}
void cargarConfiguracion() {
  velRapida       = prefs.getFloat("vel_r",   300.0f);
  velMedia        = prefs.getFloat("vel_m",   200.0f);
  velLenta        = prefs.getFloat("vel_l",   100.0f);
  acel            = prefs.getFloat("acel",    200.0f);
  tiempoExtenderMs = prefs.getULong("act_ext", 5010UL);
  tiempoRecogerMs  = prefs.getULong("act_rec", 5120UL);
}

// ─────────────────────────────────────────────────────────────────────
// ESTADO
// ─────────────────────────────────────────────────────────────────────
MensajeFertiTrack msgEnviar;
AccelStepper      motor(AccelStepper::DRIVER, PIN_STEP, PIN_DIR);
EstadoESP_A       estadoActual    = EA_REPOSO;
BrazoPendiente    brazoPendiente  = BRAZO_NINGUNO;
int               accionBrazoActual   = 0;
unsigned long     tiempoInicioBrazo   = 0;
unsigned long     duracionBrazoMs     = 0;
bool              motoresEnergizados  = false;
unsigned long     ultimaActividadMs   = 0;
volatile bool     stopSolicitado      = false;

// Cola de comandos (callback → loop)
const int COLA_CMD_MAX = 10;
ComandoRx       colaCmd[COLA_CMD_MAX];
int             colaIni      = 0;
volatile int    colaCuenta   = 0;
volatile bool   colaDesbordada = false;
portMUX_TYPE    muxCola      = portMUX_INITIALIZER_UNLOCKED;

// ─────────────────────────────────────────────────────────────────────
// MOTORES Y ACTUADORES
// ─────────────────────────────────────────────────────────────────────
void energizarMotores() {
  if (!motoresEnergizados) {
    digitalWrite(PIN_ENABLE, LOW);
    delayMicroseconds(2000);
  }
  motoresEnergizados = true;
}
void liberarMotores() {
  digitalWrite(PIN_ENABLE, HIGH);
  motoresEnergizados = false;
}
void actuadorRiego(int accion) {
  digitalWrite(ACT_RIEGO_IN1, accion ==  1 ? HIGH : LOW);
  digitalWrite(ACT_RIEGO_IN2, accion == -1 ? HIGH : LOW);
}
void actuadorFerti(int accion) {
  digitalWrite(ACT_FERTI_IN1, accion ==  1 ? HIGH : LOW);
  digitalWrite(ACT_FERTI_IN2, accion == -1 ? HIGH : LOW);
}
void detenerActuadores() { actuadorRiego(0); actuadorFerti(0); }

// ─────────────────────────────────────────────────────────────────────
// RESPUESTA AL MAESTRO
// ─────────────────────────────────────────────────────────────────────
void responderMaestro(const char *comando, int parametro) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor,  "ESPA",    sizeof(msgEnviar.emisor)-1);
  strncpy(msgEnviar.comando, comando,   sizeof(msgEnviar.comando)-1);
  msgEnviar.parametro = parametro;
  esp_err_t r = ESP_FAIL;
  for (int i = 0; i < 3 && r != ESP_OK; i++) {
    r = esp_now_send(MAC_MAESTRO, (uint8_t*)&msgEnviar, sizeof(msgEnviar));
    if (r != ESP_OK) delay(3);
  }
  Serial.printf("[TX] %s (%d) -> MAESTRO %s\n", comando, parametro, r == ESP_OK ? "OK" : "ERROR");
}

// ─────────────────────────────────────────────────────────────────────
// INICIO DE MOVIMIENTOS
// ─────────────────────────────────────────────────────────────────────
void iniciarAvance(long pasos, float velocidad) {
  if (estadoActual != EA_REPOSO) {
    Serial.println("[ESP_A] AVANZAR ignorado: ocupado o en emergencia.");
    return;
  }
  ultimaActividadMs = millis();
  if (pasos <= 0) {
    responderMaestro("LISTO", (int)motor.currentPosition());
    return;
  }
  // v5.5: aplica acel variable; velocidad ya viene correcta del Maestro
  // (si modoFuerza está activo en el Maestro, habrá enviado AVANZAR_LENTO)
  motor.setMaxSpeed(velocidad);
  motor.setAcceleration(acel);
  energizarMotores();
  motor.move(pasos);
  estadoActual = EA_AVANZANDO;
  Serial.printf("[ESP_A] Avanzando %ld pasos a %.0f p/s (acel=%.0f)\n", pasos, velocidad, acel);
}

void iniciarRetorno() {
  if (estadoActual != EA_REPOSO) {
    Serial.println("[ESP_A] RETORNAR ignorado: ocupado o en emergencia.");
    return;
  }
  ultimaActividadMs = millis();
  if (motor.currentPosition() == 0) {
    responderMaestro("LISTO", 0);
    return;
  }
  // v5.5: usa velRapida variable. Si el Maestro sincronizó velRapida = velLenta
  // (modo fuerza), el retorno también va lento. Sin cambios de protocolo.
  motor.setMaxSpeed(velRapida);
  motor.setAcceleration(acel);
  energizarMotores();
  motor.moveTo(0);
  estadoActual = EA_RETORNANDO;
  Serial.printf("[ESP_A] Retornando al origen desde %ld (vel=%.0f acel=%.0f)\n",
                motor.currentPosition(), velRapida, acel);
}

void iniciarBrazo(BrazoPendiente cual, int accion) {
  if (estadoActual != EA_REPOSO) {
    Serial.println("[ESP_A] BRAZO ignorado: ocupado o en emergencia.");
    return;
  }
  brazoPendiente     = cual;
  accionBrazoActual  = (accion != 0) ? 1 : 0;
  if (cual == BRAZO_RIEGO) actuadorRiego(accionBrazoActual == 1 ? 1 : -1);
  else                     actuadorFerti(accionBrazoActual == 1 ? 1 : -1);
  // v5.5: usa variables tiempoExtenderMs / tiempoRecogerMs
  duracionBrazoMs  = (accionBrazoActual == 1) ? tiempoExtenderMs : tiempoRecogerMs;
  tiempoInicioBrazo = millis();
  ultimaActividadMs = millis();
  estadoActual      = (accionBrazoActual == 1) ? EA_BAJANDO_BRAZO : EA_SUBIENDO_BRAZO;
  Serial.printf("[ESP_A] Brazo %s: %s (%lu ms)\n",
                cual == BRAZO_RIEGO ? "RIEGO" : "FERTI",
                accionBrazoActual == 1 ? "extendiendo" : "recogiendo",
                duracionBrazoMs);
}

// ─────────────────────────────────────────────────────────────────────
// EMERGENCIA Y RESET
// ─────────────────────────────────────────────────────────────────────
void ejecutarEmergencia() {
  stopSolicitado = false;
  if (estadoActual == EA_EMERGENCIA) return;
  motor.setCurrentPosition(motor.currentPosition());
  if (SOLTAR_MOTORES_EN_EMERGENCIA) liberarMotores();
  detenerActuadores();
  brazoPendiente = BRAZO_NINGUNO;
  estadoActual   = EA_EMERGENCIA;
  Serial.printf("[ESP_A] *** EMERGENCIA *** posicion=%ld. Esperando RESET.\n",
                motor.currentPosition());
}
void reiniciarNodo() {
  motor.setCurrentPosition(motor.currentPosition());
  detenerActuadores();
  brazoPendiente    = BRAZO_NINGUNO;
  estadoActual      = EA_REPOSO;
  ultimaActividadMs = millis();
  Serial.printf("[ESP_A] RESET. Listo en REPOSO (posicion=%ld).\n", motor.currentPosition());
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

  // Prioridad: STOP y RESET siempre se atienden
  if (strcmp(c.cmd, "STOP")  == 0) { ejecutarEmergencia(); return; }
  if (strcmp(c.cmd, "RESET") == 0) { reiniciarNodo();      return; }

  // En emergencia, todo lo demás se ignora (excepto RESET ya manejado)
  if (estadoActual == EA_EMERGENCIA) {
    Serial.println("[ESP_A] En EMERGENCIA: comando ignorado hasta RESET.");
    return;
  }

  // Comandos de movimiento
  if      (strcmp(c.cmd, "AVANZAR_MEDIO")  == 0) iniciarAvance(c.param, velMedia);
  else if (strcmp(c.cmd, "AVANZAR_LENTO")  == 0) iniciarAvance(c.param, velLenta);
  else if (strcmp(c.cmd, "AVANZAR_RAPIDO") == 0) iniciarAvance(c.param, velRapida);
  else if (strcmp(c.cmd, "RETORNAR")       == 0) iniciarRetorno();
  else if (strcmp(c.cmd, "BRAZO_RIEGO")    == 0) iniciarBrazo(BRAZO_RIEGO, c.param);
  else if (strcmp(c.cmd, "BRAZO_FERTI")    == 0) iniciarBrazo(BRAZO_FERTI, c.param);

  // === NUEVOS v5.5: sincronía de velocidades y actuadores desde el Maestro ===
  else if (strcmp(c.cmd, "VEL_R") == 0) {
    if (c.param >= 20 && c.param <= 1000) {
      velRapida = (float)c.param;
      guardarVelocidades();
      Serial.printf("[SYNC] velRapida=%.0f p/s guardada\n", velRapida);
    } else { Serial.printf("[ESP_A] VEL_R=%d fuera de rango (20-1000), ignorado\n", c.param); }
  }
  else if (strcmp(c.cmd, "VEL_M") == 0) {
    if (c.param >= 20 && c.param <= 800) {
      velMedia = (float)c.param;
      guardarVelocidades();
      Serial.printf("[SYNC] velMedia=%.0f p/s guardada\n", velMedia);
    } else { Serial.printf("[ESP_A] VEL_M=%d fuera de rango (20-800), ignorado\n", c.param); }
  }
  else if (strcmp(c.cmd, "VEL_L") == 0) {
    if (c.param >= 20 && c.param <= 500) {
      velLenta = (float)c.param;
      guardarVelocidades();
      Serial.printf("[SYNC] velLenta=%.0f p/s guardada\n", velLenta);
    } else { Serial.printf("[ESP_A] VEL_L=%d fuera de rango (20-500), ignorado\n", c.param); }
  }
  else if (strcmp(c.cmd, "ACEL") == 0) {
    if (c.param >= 50 && c.param <= 2000) {
      acel = (float)c.param;
      guardarVelocidades();
      Serial.printf("[SYNC] acel=%.0f p/s² guardada\n", acel);
    } else { Serial.printf("[ESP_A] ACEL=%d fuera de rango (50-2000), ignorado\n", c.param); }
  }
  else if (strcmp(c.cmd, "ACT_EXT") == 0) {
    if (c.param >= 500 && c.param <= 15000) {
      tiempoExtenderMs = (unsigned long)c.param;
      guardarActuadores();
      Serial.printf("[SYNC] tiempoExtenderMs=%lu ms guardado\n", tiempoExtenderMs);
    } else { Serial.printf("[ESP_A] ACT_EXT=%d fuera de rango (500-15000), ignorado\n", c.param); }
  }
  else if (strcmp(c.cmd, "ACT_REC") == 0) {
    if (c.param >= 500 && c.param <= 15000) {
      tiempoRecogerMs = (unsigned long)c.param;
      guardarActuadores();
      Serial.printf("[SYNC] tiempoRecogerMs=%lu ms guardado\n", tiempoRecogerMs);
    } else { Serial.printf("[ESP_A] ACT_REC=%d fuera de rango (500-15000), ignorado\n", c.param); }
  }
  else {
    Serial.printf("[ESP_A] Comando desconocido: \"%s\"\n", c.cmd);
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
  // PRIMERO los pines de potencia
  pinMode(ACT_RIEGO_IN1, OUTPUT); pinMode(ACT_RIEGO_IN2, OUTPUT);
  pinMode(ACT_FERTI_IN1, OUTPUT); pinMode(ACT_FERTI_IN2, OUTPUT);
  detenerActuadores();
  pinMode(PIN_ENABLE, OUTPUT);
  liberarMotores();

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.5] Iniciando ESP_A (locomocion + brazos)...");

  // NVS: cargar valores guardados
  prefs.begin("espa", false);
  cargarConfiguracion();

  Serial.printf("  Velocidades: R=%.0f M=%.0f L=%.0f ACEL=%.0f p/s (de NVS o defecto)\n",
                velRapida, velMedia, velLenta, acel);
  Serial.printf("  Actuadores:  EXT=%lu ms REC=%lu ms\n", tiempoExtenderMs, tiempoRecogerMs);
  Serial.println("  Nota: el Maestro v5.5 sobreescribe estos valores al arrancar.");
  Serial.println("  Modo Fuerza: gestionado por VEL_R (si Maestro sincroniza VEL_R=velLenta, el retorno tambien va lento).");

  motor.setPinsInverted(INVERTIR_DIRECCION, false, false);
  motor.setMinPulseWidth(3);
  motor.setMaxSpeed(velRapida);
  motor.setAcceleration(acel);
  motor.setCurrentPosition(0);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print("  MAC de este ESP_A: "); Serial.println(WiFi.macAddress());
  Serial.println("  (debe ser C8:85:41:2C:A5:BC)");

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

  ultimaActividadMs = millis();
  Serial.println("  ESP_A v5.5 listo. Esperando ordenes del Maestro.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 1) STOP: prioridad absoluta
  if (stopSolicitado) {
    ejecutarEmergencia();
    return;
  }

  // 2) Un comando de la cola por vuelta
  if (colaCuenta > 0) {
    ComandoRx c;
    if (sacarComando(c)) ejecutarComando(c);
  }
  if (colaDesbordada) {
    colaDesbordada = false;
    Serial.println("[ESP_A] AVISO: cola llena, se descarto un comando.");
  }

  // 3) Máquina de estados
  switch (estadoActual) {

    case EA_AVANZANDO:
      if (motor.distanceToGo() != 0) {
        motor.run();
      } else {
        // Llegó: motores quedan energizados (sostienen posición)
        estadoActual      = EA_REPOSO;
        ultimaActividadMs = millis();
        Serial.printf("[ESP_A] Movimiento completado. Posicion=%ld\n", motor.currentPosition());
        responderMaestro("LISTO", (int)motor.currentPosition());
      }
      break;

    case EA_RETORNANDO:
      if (motor.distanceToGo() != 0) {
        motor.run();
      } else {
        motor.setCurrentPosition(0);
        liberarMotores();   // en el origen no hace falta sostener
        estadoActual      = EA_REPOSO;
        ultimaActividadMs = millis();
        Serial.println("[ESP_A] Origen alcanzado.");
        responderMaestro("LISTO", 0);
      }
      break;

    case EA_BAJANDO_BRAZO:
    case EA_SUBIENDO_BRAZO:
      if (millis() - tiempoInicioBrazo >= duracionBrazoMs) {
        if (brazoPendiente == BRAZO_RIEGO) actuadorRiego(0);
        else                               actuadorFerti(0);
        Serial.printf("[ESP_A] Brazo %s.\n", accionBrazoActual == 1 ? "extendido" : "retraido");
        brazoPendiente    = BRAZO_NINGUNO;
        estadoActual      = EA_REPOSO;
        ultimaActividadMs = millis();
        responderMaestro("BRAZO_OK", accionBrazoActual);
      }
      break;

    case EA_REPOSO:
      // Inactividad prolongada: soltar motores para evitar calentamiento
      if (motoresEnergizados && millis() - ultimaActividadMs >= TIEMPO_SOLTAR_MOTORES_MS) {
        liberarMotores();
        Serial.println("[ESP_A] Inactividad: motores liberados.");
      }
      break;

    case EA_EMERGENCIA:
      break;
  }
}
