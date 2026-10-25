/*
  =========================================================================
  FertiTrack_ESP_A_v5.2.ino — ESP_A (locomoción + brazos), compañero de
  FertiTrack_Maestro_v5.3.ino
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
          (Tools > Flash Size = 4MB, Partition Scheme = Default 4MB)
  MAC de ESTE ESP32 (ESP_A) : C8:85:41:2C:A5:BC
  MAC del MAESTRO           : 20:50:0D:10:1E:E0
  =========================================================================

  BASE: ESP_A v5 del informe. El protocolo con el Maestro NO cambia.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.2 (tiempos reales de los actuadores lineales)
  -------------------------------------------------------------------
  Se midió con cronómetro: el actuador tarda 4.71 s en EXTENDER todo el
  pistón y 4.82 s en RECOGERLO. Hasta v5.1 ESP_A usaba una sola constante de
  3 s para todo: el actuador solo recorría ~63 % de la carrera, nunca
  llegaba al tope, el Maestro seguía el ciclo con el brazo a medio camino y
  los brazos acababan "siempre extendidos".

  Ahora hay DOS tiempos, uno por sentido, cada uno = medido + margen:
     TIEMPO_EXTENDER_MS = 4710 + 300 = 5010 ms
     TIEMPO_RECOGER_MS  = 4820 + 300 = 5120 ms
  El margen asegura que el actuador llegue al tope; el actuador tiene final
  de carrera interno, así que seguir energizado 0.3 s más no lo daña. (Si el
  tuyo NO lo tuviera, pon ACT_MARGEN_MS = 0.)
  >>> Estos valores deben ser IGUALES a ACT_EXTENDER_MS / ACT_RECOGER_MS del
  >>> Maestro v5.3 (de ahí calcula el timeout de cada orden de brazo). Al
  >>> arrancar, ESP_A los imprime para que los compares.

  Nada más cambia: mismo protocolo, misma cola, mismo STOP/RESET.

  NOTA DE HARDWARE: la etiqueta del actuador dice "Duty Cycle 10 %" (≈ 5 s
  encendido por cada 45 s de descanso). Por eso el Maestro v5.3 extiende el
  brazo de riego UNA sola vez por ciclo. No lo muevas más de lo necesario.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.1 (revisión final; ningún cambio era obligatorio)
  -------------------------------------------------------------------
  A) STOP EN ORDEN + BANDERA. El Maestro v5.1 repite el STOP 3 veces
     (~30 ms). Antes cada repetición volvía a vaciar la cola, y un STOP
     podía pisar/ser pisado por un RESET mal ordenado. Ahora el STOP
     levanta la bandera (acción inmediata) Y TAMBIÉN entra a la cola en su
     lugar: la bandera lo detiene al instante y la cola conserva el orden
     real de llegada. Un STOP estando ya en emergencia es idempotente y la
     cola ya no se vacía (en emergencia todo salvo RESET se ignora igual).
     Ejemplo que ahora sale bien: RESET seguido de un STOP casi
     inmediato (botón de emergencia durante la recuperación) -> queda en
     EMERGENCIA, no en REPOSO.

  B) ARRANQUE SEGURO. Los pines de actuadores y ENABLE se configuran lo
     PRIMERO en setup(), antes de Serial.begin() y de cualquier delay().

  C) ESPERA AL ENERGIZAR. Al pasar de motores libres a energizados se
     espera ~2 ms antes del primer paso (los A4988/DRV8825 necesitan un
     instante tras ENABLE; sin esto el primer paso tras soltar puede
     perderse).

  D) AL ARRANCAR imprime VEL_* y ACEL para compararlos a simple vista con
     los del Maestro (si no coinciden, los timeouts del Maestro fallan).

  E) Comentario de hardware corregido: son 2 drivers (uno por motor) que
     comparten STEP, DIR y ENABLE.

  F) WATCHDOG: NO se agregó, a propósito. Todo lo que hace ESP_A está
     acotado en tiempo (los avances terminan solos; los actuadores corren
     TIEMPO_EXTENDER/RECOGER_MS y se paran), y el Maestro no manda ningún latido
     mientras espera, así que un "watchdog de silencio" dispararía falsos
     paros en movimientos largos. Si algún día se quiere, primero habría
     que agregar un latido en el Maestro.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5 (resultado de la auditoría)
  -------------------------------------------------------------------
  1) MOTORES SOSTENIDOS DURANTE EL CICLO. Antes ESP_A soltaba los
     motores (ENABLE=HIGH) al terminar CADA avance. Durante el sensado el
     robot quedaba sin torque unos 8 s mientras el pistón de ESP_B
     empujaba el sensor contra el suelo, y podía desplazarse. Ahora los
     motores quedan energizados entre movimientos y solo se sueltan:
       - al terminar RETORNAR (robot en el origen),
       - en una emergencia (configurable: SOLTAR_MOTORES_EN_EMERGENCIA),
       - tras TIEMPO_SOLTAR_MOTORES_MS (30 s) sin actividad, para que no
         se calienten si el Maestro desaparece.

  2) COMANDOS PROCESADOS EN loop(), NO EN EL CALLBACK. El callback de
     ESP-NOW corre en la tarea del WiFi, no en la del loop(). Antes
     llamaba directamente a iniciarAvance()/iniciarBrazo()/RESET, es
     decir, tocaba el motor y el estado desde dos tareas a la vez. Ahora
     el callback solo copia el comando a una cola protegida (portMUX) y
     el loop() lo ejecuta. STOP sigue siendo prioritario: pone una
     bandera y el loop() lo atiende antes que nada, descartando la cola.

  3) RESET SEGURO. Antes RESET ponía REPOSO aunque el motor siguiera con
     un destino pendiente. Ahora detiene el motor en seco CONSERVANDO el
     contador de posición (la recuperación del Maestro depende de que
     ESP_A sepa dónde está para poder hacer RETORNAR), apaga los
     actuadores y vuelve a REPOSO.

  4) RESPUESTAS CON REINTENTO. Si esp_now_send() falla al encolar, se
     reintenta hasta 3 veces. NUNCA se duplica una respuesta ya enviada:
     un LISTO duplicado lo tomaría el Maestro como respuesta a la
     SIGUIENTE orden y se desincronizaría.

  5) AVANZAR con pasos <= 0 responde LISTO de inmediato (antes podía
     retroceder con pasos negativos). RETORNAR estando ya en 0 también
     responde LISTO de inmediato, sin energizar los motores.

  6) Ajustes de hardware nuevos:
       INVERTIR_DIRECCION : si el robot avanza hacia el lado contrario,
                            pon true (no hace falta recablear).
       setMinPulseWidth(3): compatible con DRV8825 (A4988 también acepta).
       WiFi.setSleep(false): evita pérdida de paquetes ESP-NOW por ahorro
                            de energía del radio.

  -------------------------------------------------------------------
  PROTOCOLO (sin cambios respecto al informe)
  -------------------------------------------------------------------
  Maestro -> ESP_A:
    "AVANZAR_RAPIDO" / "AVANZAR_MEDIO" / "AVANZAR_LENTO"
                 parametro = pasos hacia adelante
    "RETORNAR"   parametro = 0   (vuelve a la posición absoluta 0, rápido)
    "BRAZO_RIEGO" / "BRAZO_FERTI"   parametro = 1 baja / 0 sube
                 (el actuador siempre corre TIEMPO_EXTENDER_MS al bajar o
                  TIEMPO_RECOGER_MS al subir, aunque el brazo ya esté en esa
                  posición: asegura el tope y el Maestro SIEMPRE recibe su
                  BRAZO_OK)
    "STOP"       parada de emergencia total
    "RESET"      vuelve a REPOSO (no mueve nada solo, conserva posición)
  ESP_A -> Maestro:
    "LISTO"      parametro = posición actual en pasos
    "BRAZO_OK"   parametro = 1 (quedó abajo) / 0 (quedó arriba)

  >>> VELOCIDAD_* y ACELERACION deben ser IGUALES a VEL_* y ACEL del
  >>> Maestro (de ahí calcula sus timeouts). Si cambias una aquí,
  >>> cámbiala allá.

  HARDWARE
   2x NEMA 17 en el mismo riel con 2 drivers A4988/DRV8825 (uno por motor,
   cada uno con SU corriente ajustada). STEP, DIR y ENABLE van en
   paralelo a los dos drivers.
   2x actuadores lineales por puente H (riego y fertilización):
     IN1=HIGH, IN2=LOW -> extiende (baja) | IN1=LOW, IN2=HIGH -> retrae
   PIN_STEP=14  PIN_DIR=27  PIN_ENABLE=26 (LOW = energizados)
   ACT_RIEGO: 16/17    ACT_FERTI: 18/19

  LIBRERÍA: AccelStepper (Mike McCauley), Library Manager.
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <AccelStepper.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS (arriba de todo: evita el bug de prototipos automáticos del IDE)
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
  int parametro;
} MensajeFertiTrack;

// Comando recibido, ya copiado fuera del callback
struct ComandoRx {
  char cmd[20];
  int param;
};

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const int CANAL_WIFI = 1;
uint8_t MAC_MAESTRO[] = {0x20, 0x50, 0x0D, 0x10, 0x1E, 0xE0};

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_STEP 14
#define PIN_DIR 27
#define PIN_ENABLE 26 // LOW = motores energizados, HIGH = libres/fríos

#define ACT_RIEGO_IN1 16
#define ACT_RIEGO_IN2 17
#define ACT_FERTI_IN1 18
#define ACT_FERTI_IN2 19

// ─────────────────────────────────────────────────────────────────────
// PARÁMETROS MECÁNICOS (VELOCIDAD_* y ACELERACION: espejo del Maestro)
// ─────────────────────────────────────────────────────────────────────
const float VELOCIDAD_RAPIDA = 6000; // pasos/s
const float VELOCIDAD_MEDIA = 3000;
const float VELOCIDAD_LENTA = 1500;
const float ACELERACION = 3000;      // pasos/s^2

// Actuadores lineales de los brazos: tiempos MEDIDOS con cronómetro para
// recorrer TODA la carrera (100 mm) + un margen para asegurar el tope.
// >>> IGUALES a ACT_EXTENDER_MS / ACT_RECOGER_MS del Maestro.
const unsigned long ACT_EXTENDER_MEDIDO_MS = 4710; // extender todo el pistón
const unsigned long ACT_RECOGER_MEDIDO_MS = 4820;  // recogerlo todo
const unsigned long ACT_MARGEN_MS = 300;           // 0 si el actuador NO tuviera final de carrera
const unsigned long TIEMPO_EXTENDER_MS = ACT_EXTENDER_MEDIDO_MS + ACT_MARGEN_MS; // 5010
const unsigned long TIEMPO_RECOGER_MS = ACT_RECOGER_MEDIDO_MS + ACT_MARGEN_MS;   // 5120
const unsigned long TIEMPO_SOLTAR_MOTORES_MS = 30000; // inactividad -> sin torque

const bool INVERTIR_DIRECCION = false;          // true si avanza al revés
const bool SOLTAR_MOTORES_EN_EMERGENCIA = true; // false = mantener torque

// ─────────────────────────────────────────────────────────────────────
// ESTADO
// ─────────────────────────────────────────────────────────────────────
MensajeFertiTrack msgEnviar;

AccelStepper motor(AccelStepper::DRIVER, PIN_STEP, PIN_DIR);

EstadoESP_A estadoActual = EA_REPOSO;
BrazoPendiente brazoPendiente = BRAZO_NINGUNO;
int accionBrazoActual = 0; // 1 = bajando, 0 = subiendo
unsigned long tiempoInicioBrazo = 0;
unsigned long duracionBrazoMs = 0; // cuánto debe correr el actuador en curso (según el sentido)

bool motoresEnergizados = false;
unsigned long ultimaActividadMs = 0;

volatile bool stopSolicitado = false; // la pone el callback

// Cola de comandos: el callback escribe, el loop() lee (protegida con portMUX)
const int COLA_CMD_MAX = 10; // STOP x3 + órdenes normales caben de sobra
ComandoRx colaCmd[COLA_CMD_MAX];
int colaIni = 0;
volatile int colaCuenta = 0;
volatile bool colaDesbordada = false;
portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

// ─────────────────────────────────────────────────────────────────────
// MOTORES (ENABLE) Y ACTUADORES
// ─────────────────────────────────────────────────────────────────────
void energizarMotores() {
  if (!motoresEnergizados) {
    digitalWrite(PIN_ENABLE, LOW);
    delayMicroseconds(2000); // el driver tarda un instante en activarse tras ENABLE
  }
  motoresEnergizados = true;
}

void liberarMotores() {
  digitalWrite(PIN_ENABLE, HIGH);
  motoresEnergizados = false;
}

void actuadorRiego(int accion) { // 1 extiende(baja), -1 retrae(sube), 0 libre
  digitalWrite(ACT_RIEGO_IN1, accion == 1 ? HIGH : LOW);
  digitalWrite(ACT_RIEGO_IN2, accion == -1 ? HIGH : LOW);
}

void actuadorFerti(int accion) {
  digitalWrite(ACT_FERTI_IN1, accion == 1 ? HIGH : LOW);
  digitalWrite(ACT_FERTI_IN2, accion == -1 ? HIGH : LOW);
}

void detenerActuadores() {
  actuadorRiego(0);
  actuadorFerti(0);
}

// ─────────────────────────────────────────────────────────────────────
// RESPUESTA AL MAESTRO (reintenta si NO pudo encolar; nunca duplica)
// ─────────────────────────────────────────────────────────────────────
void responderMaestro(const char *comando, int parametro) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor, "ESPA", sizeof(msgEnviar.emisor) - 1);
  strncpy(msgEnviar.comando, comando, sizeof(msgEnviar.comando) - 1);
  msgEnviar.parametro = parametro;

  esp_err_t r = ESP_FAIL;
  for (int i = 0; i < 3 && r != ESP_OK; i++) {
    r = esp_now_send(MAC_MAESTRO, (uint8_t *)&msgEnviar, sizeof(msgEnviar));
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
  if (pasos <= 0) { // nada que mover: no dejar al Maestro esperando
    responderMaestro("LISTO", (int)motor.currentPosition());
    return;
  }
  motor.setMaxSpeed(velocidad);
  motor.setAcceleration(ACELERACION);
  energizarMotores();
  motor.move(pasos);
  estadoActual = EA_AVANZANDO;
  Serial.printf("[ESP_A] Avanzando %ld pasos a %.0f pasos/s\n", pasos, velocidad);
}

void iniciarRetorno() {
  if (estadoActual != EA_REPOSO) {
    Serial.println("[ESP_A] RETORNAR ignorado: ocupado o en emergencia.");
    return;
  }
  ultimaActividadMs = millis();
  if (motor.currentPosition() == 0) { // ya está en el origen
    responderMaestro("LISTO", 0);
    return;
  }
  motor.setMaxSpeed(VELOCIDAD_RAPIDA);
  motor.setAcceleration(ACELERACION);
  energizarMotores();
  motor.moveTo(0);
  estadoActual = EA_RETORNANDO;
  Serial.printf("[ESP_A] Retornando al origen desde %ld\n", motor.currentPosition());
}

void iniciarBrazo(BrazoPendiente cual, int accion) {
  if (estadoActual != EA_REPOSO) {
    Serial.println("[ESP_A] BRAZO ignorado: ocupado o en emergencia.");
    return;
  }
  brazoPendiente = cual;
  accionBrazoActual = (accion != 0) ? 1 : 0;
  if (cual == BRAZO_RIEGO) actuadorRiego(accionBrazoActual == 1 ? 1 : -1);
  else actuadorFerti(accionBrazoActual == 1 ? 1 : -1);
  duracionBrazoMs = (accionBrazoActual == 1) ? TIEMPO_EXTENDER_MS : TIEMPO_RECOGER_MS;
  tiempoInicioBrazo = millis();
  ultimaActividadMs = millis();
  estadoActual = (accionBrazoActual == 1) ? EA_BAJANDO_BRAZO : EA_SUBIENDO_BRAZO;
  Serial.printf("[ESP_A] Brazo %s: %s (%lu ms)\n", cual == BRAZO_RIEGO ? "RIEGO" : "FERTI",
                accionBrazoActual == 1 ? "extendiendo" : "recogiendo", duracionBrazoMs);
}

// ─────────────────────────────────────────────────────────────────────
// EMERGENCIA Y RESET (siempre conservan el contador de posición)
// ─────────────────────────────────────────────────────────────────────
void ejecutarEmergencia() {
  stopSolicitado = false;
  if (estadoActual == EA_EMERGENCIA) return; // STOP repetido por el Maestro: ya está hecho
  motor.setCurrentPosition(motor.currentPosition()); // alto inmediato, posición intacta
  if (SOLTAR_MOTORES_EN_EMERGENCIA) liberarMotores();
  detenerActuadores();
  brazoPendiente = BRAZO_NINGUNO;
  // La cola NO se vacía: en emergencia todo salvo RESET se ignora, y así un
  // RESET que llegue justo después nunca se pierde.
  estadoActual = EA_EMERGENCIA;
  Serial.printf("[ESP_A] *** EMERGENCIA *** posicion=%ld. Esperando RESET.\n",
                motor.currentPosition());
}

void reiniciarNodo() {
  motor.setCurrentPosition(motor.currentPosition()); // detiene sin perder la posición
  detenerActuadores();
  brazoPendiente = BRAZO_NINGUNO;
  estadoActual = EA_REPOSO;
  ultimaActividadMs = millis();
  Serial.printf("[ESP_A] RESET. Listo en REPOSO (posicion=%ld).\n", motor.currentPosition());
}

// ─────────────────────────────────────────────────────────────────────
// COLA DE COMANDOS ESP-NOW
// ─────────────────────────────────────────────────────────────────────
bool sacarComando(ComandoRx &salida) {
  bool hay = false;
  portENTER_CRITICAL(&muxCola);
  if (colaCuenta > 0) {
    salida = colaCmd[colaIni];
    colaIni = (colaIni + 1) % COLA_CMD_MAX;
    colaCuenta = colaCuenta - 1;
    hay = true;
  }
  portEXIT_CRITICAL(&muxCola);
  return hay;
}

void ejecutarComando(const ComandoRx &c) {
  Serial.printf("[RX] %s (%d)\n", c.cmd, c.param);

  if (strcmp(c.cmd, "STOP") == 0) { ejecutarEmergencia(); return; } // idempotente
  if (strcmp(c.cmd, "RESET") == 0) { reiniciarNodo(); return; }

  if (estadoActual == EA_EMERGENCIA) {
    Serial.println("[ESP_A] En EMERGENCIA: comando ignorado hasta RESET.");
    return;
  }

  if (strcmp(c.cmd, "AVANZAR_MEDIO") == 0) iniciarAvance(c.param, VELOCIDAD_MEDIA);
  else if (strcmp(c.cmd, "AVANZAR_LENTO") == 0) iniciarAvance(c.param, VELOCIDAD_LENTA);
  else if (strcmp(c.cmd, "AVANZAR_RAPIDO") == 0) iniciarAvance(c.param, VELOCIDAD_RAPIDA);
  else if (strcmp(c.cmd, "RETORNAR") == 0) iniciarRetorno();
  else if (strcmp(c.cmd, "BRAZO_RIEGO") == 0) iniciarBrazo(BRAZO_RIEGO, c.param);
  else if (strcmp(c.cmd, "BRAZO_FERTI") == 0) iniciarBrazo(BRAZO_FERTI, c.param);
  else Serial.printf("[ESP_A] Comando desconocido: \"%s\"\n", c.cmd);
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW (firma Core 3.x). Corre en la tarea del WiFi:
// NO toca motor ni estado: solo encola (y levanta la bandera si es STOP).
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != (int)sizeof(MensajeFertiTrack)) return;

  MensajeFertiTrack m;
  memcpy(&m, incomingData, sizeof(m));
  m.emisor[sizeof(m.emisor) - 1] = '\0';
  m.comando[sizeof(m.comando) - 1] = '\0';

  if (strcmp(m.emisor, "MAESTRO") != 0) return; // solo el Maestro manda

  // STOP: la bandera lo detiene al instante (loop la mira primero) y además
  // entra a la cola para respetar el ORDEN real frente a un RESET cercano.
  if (strcmp(m.comando, "STOP") == 0) stopSolicitado = true;

  portENTER_CRITICAL(&muxCola);
  if (colaCuenta < COLA_CMD_MAX) {
    int pos = (colaIni + colaCuenta) % COLA_CMD_MAX;
    strncpy(colaCmd[pos].cmd, m.comando, sizeof(colaCmd[pos].cmd) - 1);
    colaCmd[pos].cmd[sizeof(colaCmd[pos].cmd) - 1] = '\0';
    colaCmd[pos].param = m.parametro;
    colaCuenta = colaCuenta + 1;
  } else {
    colaDesbordada = true;
  }
  portEXIT_CRITICAL(&muxCola);
}

// ─────────────────────────────────────────────────────────────────────
// SETUP
// ─────────────────────────────────────────────────────────────────────
void setup() {
  // PRIMERO los pines de potencia, antes del Serial y de cualquier delay:
  // actuadores parados y motores sin torque lo antes posible.
  pinMode(ACT_RIEGO_IN1, OUTPUT);
  pinMode(ACT_RIEGO_IN2, OUTPUT);
  pinMode(ACT_FERTI_IN1, OUTPUT);
  pinMode(ACT_FERTI_IN2, OUTPUT);
  detenerActuadores();
  pinMode(PIN_ENABLE, OUTPUT);
  liberarMotores(); // arranca sin torque (sin calor)

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.2] Iniciando ESP_A (locomocion + brazos)...");
  Serial.printf(" Velocidades: RAPIDA=%.0f MEDIA=%.0f LENTA=%.0f ACEL=%.0f (deben ser IGUALES a las del Maestro)\n",
                VELOCIDAD_RAPIDA, VELOCIDAD_MEDIA, VELOCIDAD_LENTA, ACELERACION);
  Serial.printf(" Actuadores: extender %lu ms, recoger %lu ms (deben ser IGUALES a ACT_EXTENDER_MS / ACT_RECOGER_MS del Maestro)\n",
                TIEMPO_EXTENDER_MS, TIEMPO_RECOGER_MS);
  motor.setPinsInverted(INVERTIR_DIRECCION, false, false);
  motor.setMinPulseWidth(3);
  motor.setMaxSpeed(VELOCIDAD_RAPIDA);
  motor.setAcceleration(ACELERACION);
  motor.setCurrentPosition(0); // al encender se asume el robot en el origen

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print(" MAC de este ESP_A: "); Serial.println(WiFi.macAddress());
  Serial.println(" (debe ser C8:85:41:2C:A5:BC)");

  if (esp_now_init() != ESP_OK) {
    Serial.println(" ERROR: ESP-NOW no inicializo");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, MAC_MAESTRO, 6);
  peerInfo.channel = CANAL_WIFI;
  peerInfo.encrypt = false;
  if (esp_now_add_peer(&peerInfo) != ESP_OK)
    Serial.println(" ERROR registrando al Maestro como peer");

  ultimaActividadMs = millis();
  Serial.println(" ESP_A listo. Esperando ordenes del Maestro.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// (motor.run() se llama en cada vuelta mientras haya distancia pendiente)
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 1) STOP: prioridad absoluta
  if (stopSolicitado) {
    ejecutarEmergencia();
    return;
  }

  // 2) Un comando de la cola por vuelta (lectura rápida sin bloquear el motor)
  if (colaCuenta > 0) {
    ComandoRx c;
    if (sacarComando(c)) ejecutarComando(c);
  }
  if (colaDesbordada) {
    colaDesbordada = false;
    Serial.println("[ESP_A] AVISO: cola de comandos llena, se descarto un comando.");
  }

  // 3) Máquina de estados
  switch (estadoActual) {
    case EA_AVANZANDO:
      if (motor.distanceToGo() != 0) {
        motor.run();
      } else {
        // Llegó. Los motores se QUEDAN energizados (sostienen al robot).
        estadoActual = EA_REPOSO;
        ultimaActividadMs = millis();
        Serial.printf("[ESP_A] Movimiento completado. Posicion=%ld\n", motor.currentPosition());
        responderMaestro("LISTO", (int)motor.currentPosition());
      }
      break;

    case EA_RETORNANDO:
      if (motor.distanceToGo() != 0) {
        motor.run();
      } else {
        motor.setCurrentPosition(0); // fija el origen con exactitud
        liberarMotores();            // en el origen ya no hace falta sostener
        estadoActual = EA_REPOSO;
        ultimaActividadMs = millis();
        Serial.println("[ESP_A] Origen alcanzado.");
        responderMaestro("LISTO", 0);
      }
      break;

    case EA_BAJANDO_BRAZO:
    case EA_SUBIENDO_BRAZO:
      if (millis() - tiempoInicioBrazo >= duracionBrazoMs) {
        if (brazoPendiente == BRAZO_RIEGO) actuadorRiego(0);
        else actuadorFerti(0);
        Serial.printf("[ESP_A] Brazo %s.\n", accionBrazoActual == 1 ? "extendido" : "retraido");
        brazoPendiente = BRAZO_NINGUNO;
        estadoActual = EA_REPOSO;
        ultimaActividadMs = millis();
        responderMaestro("BRAZO_OK", accionBrazoActual);
      }
      break;

    case EA_REPOSO:
      // Sin actividad prolongada: soltar motores para que no se calienten
      if (motoresEnergizados && millis() - ultimaActividadMs >= TIEMPO_SOLTAR_MOTORES_MS) {
        liberarMotores();
        Serial.println("[ESP_A] Inactividad: motores liberados.");
      }
      break;

    case EA_EMERGENCIA:
      break;
  }
}
