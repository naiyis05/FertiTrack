/*
  =========================================================================
  FertiTrack_ESP_A_Movimiento.ino — FertiTrack v3 (MONORRIEL — ARQUITECTURA POR ZONAS)
  Nodo: ESP32 "A" (locomoción + brazos/actuadores lineales)
  =========================================================================

  Este sketch es el compañero de FertiTrack_Maestro_v4.ino (ya corregido) y
  de FertiTrack_ESP_B_Sensor.ino. Sigue EXACTAMENTE el mismo protocolo
  ESP-NOW que el Maestro ya está enviando de verdad en su código, para que
  los 4 nodos "hablen el mismo idioma".

  -------------------------------------------------------------------
  COMANDOS QUE ESTE NODO (ESP_A) RECIBE DEL MAESTRO
  -------------------------------------------------------------------
    "AVANZAR_MEDIO"  parametro = pasos -> avanza esa cantidad de pasos a
                                          velocidad media (sensado y el
                                          único tramo de fertilización)
    "AVANZAR_LENTO"  parametro = pasos -> avanza esa cantidad de pasos a
                                          velocidad lenta (riego, zona SECA)
    "AVANZAR_RAPIDO" parametro = pasos -> avanza esa cantidad de pasos a
                                          velocidad rápida (riego, zona que
                                          NO necesita agua)
    "RETORNAR"       parametro = 0     -> vuelve al origen (posición 0) a
                                          velocidad rápida
    "BRAZO_RIEGO"    parametro = 1/0   -> baja (1) / sube (0) el brazo
                                          (actuador lineal) del aspersor
                                          de riego
    "BRAZO_FERTI"    parametro = 1/0   -> baja (1) / sube (0) el brazo
                                          (actuador lineal) del aspersor
                                          de fertilización
    "STOP"           parametro = 0     -> parada de emergencia total
    "RESET"          parametro = 0     -> recuperación tras falla: vuelve
                                          a REPOSO (no mueve nada solo)

  -------------------------------------------------------------------
  RESPUESTAS QUE ESTE NODO ENVÍA AL MAESTRO
  -------------------------------------------------------------------
    "LISTO"     parametro = posición actual en pasos -> terminó de
                moverse (AVANZAR_* o RETORNAR)
    "BRAZO_OK"  parametro = 1/0                       -> terminó de mover
                el brazo que se le haya pedido (el que esté en curso)

  El Maestro solo mira si llegó CUALQUIERA de estas dos respuestas
  (comando == "LISTO" || comando == "BRAZO_OK") para levantar su bandera
  de "respuesta lista"; el valor del parámetro no cambia su lógica, así
  que basta con que sea consistente para depurar por Monitor Serie.

  IMPORTANTE sobre "pasos": a diferencia de versiones viejas de este
  proyecto (donde ESP_A guardaba una constante fija "pasos por punto"),
  en esta versión monorriel por ZONAS es el MAESTRO quien calcula cuántos
  pasos mide cada zona (según la distancia total y el ancho de zona que
  llegan desde la app) y se los manda en el campo "parametro". ESP_A NO
  necesita saber nada de zonas, cm ni umbrales: solo mueve exactamente
  los pasos que le piden, en la dirección que le piden.

  -------------------------------------------------------------------
  HARDWARE DE ESTE NODO (según el informe del proyecto)
  -------------------------------------------------------------------
    - 2x motor NEMA 17 en paralelo, mismo riel -> comparten físicamente
      las mismas líneas STEP y DIR de un solo driver A4988/DRV8825, así
      que basta con UN objeto AccelStepper para mover a los dos a la vez.
    - 2x actuadores lineales vía puente H (L293D/L298N u otro driver de
      2 canales), igual que el pistón de ESP_B:
        Actuador 1 = brazo del aspersor de RIEGO
        Actuador 2 = brazo del aspersor de FERTILIZACIÓN
        IN1=HIGH, IN2=LOW  -> extiende (baja el brazo)
        IN1=LOW,  IN2=HIGH -> retrae   (sube el brazo)
        IN1=LOW,  IN2=LOW  -> frena / libre (ni sube ni baja)

  Pines por defecto (AJUSTA a tu cableado real si es distinto; son los
  mismos que ya aparecen documentados en el informe del proyecto):
    PIN_STEP        = 14   (compartido por los 2 A4988)
    PIN_DIR         = 27   (compartido por los 2 A4988)
    PIN_ENABLE      = 26   (LOW = motores energizados, HIGH = libres/fríos)
    ACT_RIEGO_IN1   = 16
    ACT_RIEGO_IN2   = 17
    ACT_FERTI_IN1   = 18
    ACT_FERTI_IN2   = 19

  -------------------------------------------------------------------
  MACs (confirmadas en la tabla del informe — NO usar otras)
  -------------------------------------------------------------------
    MAC de ESTE ESP32 (ESP_A) : C8:85:41:2C:A5:BC  (ya registrada en el
                                 Maestro como MAC_ESP_A)
    MAC del MAESTRO           : 20:50:0D:10:1E:E0  (va fija abajo)

  -------------------------------------------------------------------
  LIBRERÍAS NECESARIAS
  -------------------------------------------------------------------
    - AccelStepper (Arduino Library Manager -> "AccelStepper" de Mike
      McCauley). WiFi, esp_now y esp_wifi ya vienen con el core de ESP32.

  -------------------------------------------------------------------
  REGLAS ESTRICTAS DEL PROYECTO (las mismas del Maestro y de ESP_B)
  -------------------------------------------------------------------
   - Firma ESP-NOW obligatoria para Core v3.x del ESP32:
       void OnDataRecv(const esp_now_recv_info *info,
                        const uint8_t *incomingData, int len)
   - Cero delay() largos ni while() bloqueantes en loop(): todo se
     resuelve con máquina de estados (switch/case) + millis(), igual
     que en el Maestro y en ESP_B. motor.run() se llama una vez por
     vuelta de loop() mientras haya distancia pendiente.
   - Canal WiFi fijo (1), igual en los 4 nodos.
   - El 'typedef struct MensajeFertiTrack' es IDÉNTICO al de los otros
     3 sketches. Si algún nodo usa un struct distinto, sizeof() no
     coincidirá y OnDataRecv() descartará el paquete en silencio.
   - Los enum se declaran aquí ARRIBA, justo después de los #include,
     para evitar el bug de generación automática de prototipos de
     Arduino (el mismo que causó el error de compilación en el Maestro):
     si una función usa un enum como parámetro y ese enum se declara
     más abajo en el archivo, el prototipo autogenerado por el IDE
     queda con un tipo todavía no declarado y el sketch no compila.
  -------------------------------------------------------------------
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <AccelStepper.h>

// =========================================================================
// ENUMS (declarados primero — ver nota anti-bug de prototipos más arriba)
// =========================================================================
enum EstadoESP_A
{
  EA_REPOSO,          // esperando orden del Maestro
  EA_AVANZANDO,       // moviéndose hacia adelante (AVANZAR_MEDIO/LENTO/RAPIDO)
  EA_RETORNANDO,      // volviendo al origen (RETORNAR)
  EA_BAJANDO_BRAZO,   // un actuador lineal extendiéndose
  EA_SUBIENDO_BRAZO,  // un actuador lineal retrayéndose
  EA_EMERGENCIA       // detenido por STOP, esperando RESET
};

enum BrazoPendiente { BRAZO_NINGUNO, BRAZO_RIEGO, BRAZO_FERTI };

// =========================================================================
// CONFIG_RED
// =========================================================================
const int CANAL_WIFI = 1; // Debe ser el mismo canal en los 4 ESP32

// =========================================================================
// CONFIG_PINES (ESP_A) — AJUSTA a tu cableado real
// =========================================================================
#define PIN_STEP   14 // STEP compartido por los 2 A4988 (2 Nema 17 en paralelo)
#define PIN_DIR    27 // DIR  compartido por los 2 A4988
#define PIN_ENABLE 26 // LOW = motores energizados, HIGH = libres/fríos

#define ACT_RIEGO_IN1 16 // Puente H, brazo del aspersor de RIEGO
#define ACT_RIEGO_IN2 17
#define ACT_FERTI_IN1 18 // Puente H, brazo del aspersor de FERTILIZACIÓN
#define ACT_FERTI_IN2 19

// =========================================================================
// PARÁMETROS MECÁNICOS (ajustables)
// =========================================================================
// Velocidades en pasos/segundo. AJUSTA según tu microstepping real.
const float VELOCIDAD_RAPIDA = 6000; // RETORNAR y riego en zona húmeda/media
const float VELOCIDAD_MEDIA  = 3000; // sensado y el único tramo de ferti
const float VELOCIDAD_LENTA  = 1500; // riego en zona SECA (con bomba encendida)
const float ACELERACION      = 3000; // pasos/s^2, igual para las 3 velocidades

// Tiempo que tarda un actuador lineal en llegar a su tope al extender o
// retraer. AJUSTA este valor midiendo tu mecanismo real (cronómetro en mano).
const unsigned long TIEMPO_ACTUADOR_MS = 3000;

// Nota de diseño: con la configuración por defecto del Maestro
// (anchoZonaCm=2.5, pasosPorCm=400 -> ~1000 pasos por zona), incluso a
// VELOCIDAD_LENTA (1500 pasos/s) un tramo de zona tarda ~0.7s, muy por
// debajo del TIMEOUT_A_MS = 8000ms que el Maestro le da a este nodo. Si
// tu ancho de zona real es mucho mayor, revisa que pasos/velocidad no
// se acerque a esos 8s (o pide que se suba TIMEOUT_A_MS en el Maestro).

// =========================================================================
// MAC del MAESTRO (confirmada en la tabla del informe del proyecto)
// =========================================================================
uint8_t MAC_MAESTRO[] = {0x20, 0x50, 0x0D, 0x10, 0x1E, 0xE0};

// =========================================================================
// ESTRUCTURA UNIFICADA (idéntica en los 4 sketches — NO modificar)
// =========================================================================
typedef struct
{
  char emisor[10];  // "MAESTRO", "ESPA", "ESPB", "PANTALLA"
  char comando[20]; // "AVANZAR_MEDIO", "RETORNAR", "BRAZO_RIEGO", "STOP", etc.
  int parametro;    // pasos, 1/0 de brazo, o valor ADC (no usado aquí)
} MensajeFertiTrack;

MensajeFertiTrack msgEnviar;
MensajeFertiTrack msgRecibido;

// =========================================================================
// MOTOR (2 Nema 17 en paralelo -> un solo objeto AccelStepper)
// =========================================================================
AccelStepper motor(AccelStepper::DRIVER, PIN_STEP, PIN_DIR);

// =========================================================================
// MÁQUINA DE ESTADOS DE ESP_A (no bloqueante: nada de delay() largos)
// =========================================================================
EstadoESP_A estadoActual = EA_REPOSO;

BrazoPendiente brazoPendiente = BRAZO_NINGUNO;
int accionBrazoActual = 0; // 1=bajando, 0=subiendo (para el reporte BRAZO_OK)
unsigned long tiempoInicioBrazo = 0;

volatile bool stopSolicitado = false; // bandera que pone el callback ESP-NOW

// =========================================================================
// CONTROL DE ACTUADORES LINEALES (puente H — 2 canales cada uno)
// accion:  1 = extender (bajar brazo)
//         -1 = retraer (subir brazo)
//          0 = frenar / libre (no se mueve)
// =========================================================================
void actuadorRiego(int accion)
{
  digitalWrite(ACT_RIEGO_IN1, accion == 1 ? HIGH : LOW);
  digitalWrite(ACT_RIEGO_IN2, accion == -1 ? HIGH : LOW);
}

void actuadorFerti(int accion)
{
  digitalWrite(ACT_FERTI_IN1, accion == 1 ? HIGH : LOW);
  digitalWrite(ACT_FERTI_IN2, accion == -1 ? HIGH : LOW);
}

void detenerActuadores()
{
  actuadorRiego(0);
  actuadorFerti(0);
}

// =========================================================================
// ENVÍO DE RESPUESTAS AL MAESTRO
// =========================================================================
void responderMaestro(const char *comando, int parametro)
{
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor, "ESPA", sizeof(msgEnviar.emisor) - 1);
  strncpy(msgEnviar.comando, comando, sizeof(msgEnviar.comando) - 1);
  msgEnviar.parametro = parametro;
  esp_err_t r = esp_now_send(MAC_MAESTRO, (uint8_t *)&msgEnviar, sizeof(msgEnviar));
  Serial.printf("[TX] %s (%d) -> MAESTRO %s\n",
                comando, parametro, r == ESP_OK ? "OK" : "ERROR");
}

// =========================================================================
// INICIA UN MOVIMIENTO DEL RIEL (llamado al recibir AVANZAR_* o RETORNAR)
// retorno: relativo (pasos hacia adelante) | origen: absoluto (moveTo 0)
// =========================================================================
void iniciarAvance(long pasos, float velocidad)
{
  if (estadoActual != EA_REPOSO)
  {
    Serial.println("[ESP_A] Orden de avance ignorada: ya hay un movimiento en curso o estamos en emergencia.");
    return;
  }
  motor.setMaxSpeed(velocidad);
  motor.setAcceleration(ACELERACION);
  digitalWrite(PIN_ENABLE, LOW); // habilita los drivers
  motor.move(pasos);             // movimiento relativo hacia adelante
  estadoActual = EA_AVANZANDO;
  Serial.printf("[ESP_A] Avanzando %ld pasos a %.0f pasos/s...\n", pasos, velocidad);
}

void iniciarRetorno()
{
  if (estadoActual != EA_REPOSO)
  {
    Serial.println("[ESP_A] RETORNAR ignorado: ya hay un movimiento en curso o estamos en emergencia.");
    return;
  }
  motor.setMaxSpeed(VELOCIDAD_RAPIDA);
  motor.setAcceleration(ACELERACION);
  digitalWrite(PIN_ENABLE, LOW);
  motor.moveTo(0); // posición absoluta = origen
  estadoActual = EA_RETORNANDO;
  Serial.println("[ESP_A] Retornando al origen...");
}

// =========================================================================
// INICIA UN MOVIMIENTO DE BRAZO (llamado al recibir BRAZO_RIEGO/BRAZO_FERTI)
// =========================================================================
void iniciarBrazo(BrazoPendiente cual, int accion)
{
  if (estadoActual != EA_REPOSO)
  {
    Serial.println("[ESP_A] Orden de brazo ignorada: ya hay un movimiento en curso o estamos en emergencia.");
    return;
  }
  brazoPendiente = cual;
  accionBrazoActual = accion; // 1=bajar, 0=subir

  if (cual == BRAZO_RIEGO) actuadorRiego(accion == 1 ? 1 : -1);
  else                     actuadorFerti(accion == 1 ? 1 : -1);

  tiempoInicioBrazo = millis();
  estadoActual = (accion == 1) ? EA_BAJANDO_BRAZO : EA_SUBIENDO_BRAZO;
  Serial.printf("[ESP_A] Brazo %s: %s...\n",
                (cual == BRAZO_RIEGO) ? "RIEGO" : "FERTI",
                (accion == 1) ? "bajando" : "subiendo");
}

// =========================================================================
// PARADA DE EMERGENCIA
// =========================================================================
void ejecutarEmergencia()
{
  motor.stop();
  motor.setCurrentPosition(motor.currentPosition()); // frena donde esté, sin forzar
  digitalWrite(PIN_ENABLE, HIGH);                     // libera los steppers (sin calor)
  detenerActuadores();
  estadoActual = EA_EMERGENCIA;
  stopSolicitado = false;
  Serial.println("[ESP_A] *** EMERGENCIA: motores y brazos detenidos, esperando RESET ***");
}

// =========================================================================
// CALLBACK ESP-NOW — firma OBLIGATORIA para Core v3.x del ESP32
// =========================================================================
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len)
{
  (void)info; // no se usa el origen del paquete; el struct ya trae "emisor"

  if (len != sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));

  String emisor = String(msgRecibido.emisor);
  String comando = String(msgRecibido.comando);
  int parametro = msgRecibido.parametro;
  Serial.printf("[RX] de %s: %s (%d)\n", emisor.c_str(), comando.c_str(), parametro);

  // Solo el Maestro tiene autoridad sobre ESP_A; se ignora cualquier otro origen.
  if (emisor != "MAESTRO") return;

  // STOP tiene prioridad absoluta y se ejecuta de inmediato (sin bloquear).
  if (comando == "STOP")
  {
    stopSolicitado = true;
    return;
  }

  if (comando == "RESET")
  {
    detenerActuadores();
    brazoPendiente = BRAZO_NINGUNO;
    estadoActual = EA_REPOSO;
    Serial.println("[ESP_A] RESET recibido. Listo para nueva orden.");
    return;
  }

  // Mientras estemos en emergencia, cualquier otra orden se ignora hasta RESET.
  if (estadoActual == EA_EMERGENCIA)
  {
    Serial.println("[ESP_A] En EMERGENCIA: comando ignorado hasta recibir RESET.");
    return;
  }

  if (comando == "AVANZAR_MEDIO")       iniciarAvance(parametro, VELOCIDAD_MEDIA);
  else if (comando == "AVANZAR_LENTO")  iniciarAvance(parametro, VELOCIDAD_LENTA);
  else if (comando == "AVANZAR_RAPIDO") iniciarAvance(parametro, VELOCIDAD_RAPIDA);
  else if (comando == "RETORNAR")       iniciarRetorno();
  else if (comando == "BRAZO_RIEGO")    iniciarBrazo(BRAZO_RIEGO, parametro);
  else if (comando == "BRAZO_FERTI")    iniciarBrazo(BRAZO_FERTI, parametro);
  else Serial.printf("[ESP_A] Comando desconocido: \"%s\"\n", comando.c_str());
}

// =========================================================================
// SETUP
// =========================================================================
void setup()
{
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v3] Iniciando ESP_A (locomoción + brazos)...");

  // Actuadores lineales
  pinMode(ACT_RIEGO_IN1, OUTPUT); pinMode(ACT_RIEGO_IN2, OUTPUT);
  pinMode(ACT_FERTI_IN1, OUTPUT); pinMode(ACT_FERTI_IN2, OUTPUT);
  detenerActuadores(); // asegurar que ambos arrancan frenados/quietos

  // Stepper (2 Nema 17 en paralelo)
  pinMode(PIN_ENABLE, OUTPUT);
  digitalWrite(PIN_ENABLE, HIGH); // desactivado al inicio (sin calor)
  motor.setMaxSpeed(VELOCIDAD_RAPIDA);
  motor.setAcceleration(ACELERACION);
  motor.setCurrentPosition(0);

  // Solo modo estación: ESP_A no crea AP, únicamente habla ESP-NOW con el Maestro.
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(50); // pequeño respiro antes de leer la MAC (evita 00:00:00 en el log)
  Serial.print("MAC de este ESP_A: "); Serial.println(WiFi.macAddress());
  Serial.println("(debe coincidir con MAC_ESP_A en el Maestro: C8:85:41:2C:A5:BC)");

  if (esp_now_init() != ESP_OK)
  {
    Serial.println("ERROR: no se pudo inicializar ESP-NOW.");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  esp_now_peer_info_t peerInfo = {};
  peerInfo.channel = CANAL_WIFI;
  peerInfo.encrypt = false;
  memcpy(peerInfo.peer_addr, MAC_MAESTRO, 6);
  if (esp_now_add_peer(&peerInfo) != ESP_OK)
  {
    Serial.println("ERROR: no se pudo registrar al Maestro como peer ESP-NOW.");
  }

  Serial.println("ESP_A listo. Esperando órdenes del Maestro.\n");
}

// =========================================================================
// LOOP — máquina de estados no bloqueante
// =========================================================================
void loop()
{
  // Emergencia tiene prioridad absoluta sobre cualquier otra cosa.
  if (stopSolicitado)
  {
    ejecutarEmergencia();
    return;
  }

  switch (estadoActual)
  {
  case EA_AVANZANDO:
    if (motor.distanceToGo() != 0)
    {
      motor.run(); // un paso por vuelta de loop, nunca bloquea
    }
    else
    {
      digitalWrite(PIN_ENABLE, HIGH); // llegó: ahorra corriente/calor
      estadoActual = EA_REPOSO;
      Serial.printf("[ESP_A] Movimiento completado. Posición=%ld. Reportando LISTO.\n",
                    motor.currentPosition());
      responderMaestro("LISTO", (int)motor.currentPosition());
    }
    break;

  case EA_RETORNANDO:
    if (motor.distanceToGo() != 0)
    {
      motor.run();
    }
    else
    {
      digitalWrite(PIN_ENABLE, HIGH);
      motor.setCurrentPosition(0); // fija el origen con exactitud
      estadoActual = EA_REPOSO;
      Serial.println("[ESP_A] Origen alcanzado. Reportando LISTO.");
      responderMaestro("LISTO", 0);
    }
    break;

  case EA_BAJANDO_BRAZO:
  case EA_SUBIENDO_BRAZO:
    if (millis() - tiempoInicioBrazo >= TIEMPO_ACTUADOR_MS)
    {
      if (brazoPendiente == BRAZO_RIEGO) actuadorRiego(0);
      else                               actuadorFerti(0);
      Serial.printf("[ESP_A] Brazo %s. Reportando BRAZO_OK.\n",
                    (accionBrazoActual == 1) ? "extendido" : "retraído");
      responderMaestro("BRAZO_OK", accionBrazoActual);
      brazoPendiente = BRAZO_NINGUNO;
      estadoActual = EA_REPOSO;
    }
    break;

  case EA_REPOSO:
  case EA_EMERGENCIA:
    // No hacer nada: se espera la próxima orden ESP-NOW del Maestro.
    break;
  }
}
