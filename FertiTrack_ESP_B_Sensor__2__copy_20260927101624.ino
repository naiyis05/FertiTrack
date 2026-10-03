/*
  =========================================================================
  FertiTrack_ESP_B_Sensor.ino — FertiTrack v3 (MONORRIEL — ARQUITECTURA POR ZONAS)
  Nodo: ESP32 "B" (sensor de humedad + pistón)
  =========================================================================

  Este sketch es el compañero del FertiTrack_Maestro.ino ya corregido (el
  que trae "hermana Claude" en sus comentarios). Sigue EXACTAMENTE el mismo
  protocolo ESP-NOW documentado ahí, para que los 4 nodos "hablen el mismo
  idioma":

  Comandos que ESTE nodo (ESP_B) RECIBE del Maestro:
    "MEDIR"  parametro = 0  -> baja el pistón, promedia ~5s de lectura ADC,
                               sube el pistón y responde "HUMEDAD".
    "STOP"   parametro = 0  -> parada de emergencia total: recoge el
                               pistón (posición segura) y apaga los LEDs.
    "RESET"  parametro = 0  -> recuperación tras falla: vuelve a REPOSO.

  Respuesta que ESTE nodo ENVÍA al Maestro:
    "HUMEDAD"  parametro = valor ADC promedio (0-4095)

  -------------------------------------------------------------------
  ROL DE ESTE NODO (según el informe del proyecto)
  -------------------------------------------------------------------
  ESP_B es un esclavo puramente sensor: no decide nada por sí mismo, solo
  ejecuta la orden "MEDIR" que le manda el Maestro cada vez que el robot
  se detiene en una zona, y le contesta con el dato. El Maestro es quien
  compara ese ADC contra sus umbrales (SECO/INTERMEDIA/HUMEDA/SOBRE_HUMEDA)
  y decide si esa zona necesita riego. ESP_B no necesita saber nada de
  zonas, distancias ni umbrales: solo mide cuando se lo piden.

  -------------------------------------------------------------------
  HARDWARE DE ESTE NODO
  -------------------------------------------------------------------
    - 1x sensor de humedad de suelo ANALÓGICO (higrómetro capacitivo o
      resistivo) -> pin ADC de solo lectura (ADC1, funciona con WiFi
      activo a diferencia de ADC2).
    - 1x pistón/actuador que entierra el sensor en la tierra y luego lo
      retrae. YA NO usa puente H: se controla con UN SOLO RELÉ de 1 canal,
      porque el mecanismo es de retorno automático (tipo solenoide con
      resorte), no bidireccional:
        Relé DESACTIVADO (sin corriente) -> el pistón se DISPARA (baja,
                                             entierra el sensor)
        Relé ACTIVADO (con corriente)    -> el pistón se RECOGE (sube)
      Importante: como "sin corriente" ya es un estado válido (disparado),
      si el ESP32 se reinicia o pierde alimentación, el pistón queda
      abajo por defecto. Es una falla segura del propio mecanismo, no
      un error del código.

  Pines por defecto (AJUSTA estos valores a tu cableado real, son solo
  un punto de partida razonable, igual que hace el Maestro con los suyos):
    PIN_PISTON_RELE    = 26  (único pin: IN del módulo de relé)
    PIN_SENSOR_HUMEDAD = 34   (ADC1_CH6, pin de solo entrada, ideal para
                                sensores analógicos)

  -------------------------------------------------------------------
  CÓMO CERRAR EL "ESPACIO PARA LA MAC" QUE PIDE EL INFORME
  -------------------------------------------------------------------
  El informe pide dejar en cada código el espacio para agregar la MAC de
  ESP_B en cuanto exista hardware real. Ese espacio ya está reservado en
  el Maestro (MAC_ESP_B[] y ESP_B_CONECTADA). Lo que este archivo necesita
  al revés es la MAC del MAESTRO (para poder contestarle), y esa YA está
  confirmada en el informe, así que va fija abajo.

  Pasos para dejar el sistema 100% operativo el día que sueldes este ESP32:
    1) Sube este sketch tal cual a la placa que hará de ESP_B.
    2) Abre el Monitor Serie (115200 baudios). Al arrancar imprime su
       propia MAC, algo como:  "MAC de este ESP_B: XX:XX:XX:XX:XX:XX"
    3) Copia esa MAC y pégala en FertiTrack_Maestro.ino, reemplazando los
       6 bytes de MAC_ESP_B[].
    4) Cambia ESP_B_CONECTADA a true en el Maestro y vuelve a subirlo.
    No hace falta tocar nada más ni en este archivo ni en el Maestro.

  -------------------------------------------------------------------
  REGLAS ESTRICTAS DEL PROYECTO (las mismas del Maestro, respetadas aquí)
  -------------------------------------------------------------------
   - Firma ESP-NOW obligatoria para Core v3.x del ESP32:
       void OnDataRecv(const esp_now_recv_info *info,
                        const uint8_t *incomingData, int len)
   - Cero delay() largos ni while() bloqueantes en loop(): todo se
     resuelve con máquina de estados (switch/case) + millis().
   - Canal WiFi fijo (1), igual en los 4 nodos.
   - El 'typedef struct MensajeFertiTrack' es IDÉNTICO al de los otros 3
     sketches. Si algún nodo usa un struct distinto, sizeof() no
     coincidirá y OnDataRecv() descartará el paquete en silencio.
  -------------------------------------------------------------------
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

// =========================================================================
// CONFIG_RED
// =========================================================================
const int CANAL_WIFI = 1; // Debe ser el mismo canal en los 4 ESP32

// =========================================================================
// CONFIG_PINES (ESP_B) — AJUSTA a tu cableado real
// =========================================================================
#define PIN_PISTON_RELE 26    // IN del módulo de relé de 1 canal (pistón)
#define PIN_SENSOR_HUMEDAD 34 // ADC1_CH6 (solo lectura), sensor de humedad

// Polaridad del módulo de relé. La mayoría de módulos de 1 canal baratos
// (los de la placa azul con optoacoplador) son "activos en LOW": el relé
// se energiza cuando el pin IN está en LOW, no en HIGH. Si tu módulo es
// al revés (activo en HIGH), simplemente invierte estas 2 macros; el
// resto del código no necesita ningún otro cambio.
#define RELE_ACTIVADO  LOW
#define RELE_APAGADO   HIGH

// Indicadores LED de 12V (uno por color). Como son de 12V, cada uno va a
// través de un transistor/relé de potencia: el GPIO solo maneja la base
// del transistor (o la bobina del relé/opto), NO el LED directamente.
// Si tu driver es "activo en HIGH" (transistor NPN, base a través de
// resistencia) deja estas macros tal cual; si es "activo en LOW" (por
// ejemplo un módulo de relé optoacoplado típico), invierte LED_ON/LED_OFF.
#define PIN_LED_AZUL      25 // HUMEDA (fijo) / SOBRE_HUMEDA (parpadeando)
#define PIN_LED_AMARILLO  32 // SECA (fijo) / INTERMEDIA (junto con azul)
#define PIN_LED_ROJO      33 // FERTILIZANDO (fijo)
#define LED_ON  HIGH
#define LED_OFF LOW

// =========================================================================
// TIEMPOS DEL CICLO DE MEDICIÓN (ajustables)
// =========================================================================
// Cuánto tarda el pistón en llegar a su tope al dispararse/recogerse.
// AJUSTA este valor midiendo tu mecanismo real (cronómetro en mano). Al
// cambiar de puente H a relé con resorte, es probable que el movimiento
// sea más rápido que antes: no asumas que 1500 ms sigue siendo correcto,
// vuelve a medirlo.
const unsigned long TIEMPO_PISTON_MS = 1500;

// El informe pide "promediar ~5 segundos" de lectura -> se respeta tal cual.
const unsigned long TIEMPO_PROMEDIO_MS = 5000;

// Cada cuánto se toma una muestra ADC durante esos 5s (50 muestras en total).
const unsigned long INTERVALO_MUESTRA_MS = 100;

// Nota de diseño: 1500 (bajar) + 5000 (medir) + 1500 (subir) = 8000 ms,
// muy por debajo del TIMEOUT_MEDICION_MS = 12000 ms que el Maestro le da a
// este nodo. Ese margen de ~4s cubre el tiempo de ida/vuelta de ESP-NOW y
// cualquier pequeño retraso mecánico. Si tu pistón es más lento, puedes
// subir TIEMPO_PISTON_MS con tranquilidad mientras la suma no pase de
// ~11000 ms.

// =========================================================================
// MAC del MAESTRO (confirmada en el informe del proyecto)
// =========================================================================
uint8_t MAC_MAESTRO[] = {0x20, 0x50, 0x0D, 0x10, 0x1E, 0xE0};

// =========================================================================
// ESTRUCTURA UNIFICADA (idéntica en los 4 sketches — NO modificar)
// =========================================================================
typedef struct
{
  char emisor[10];  // "MAESTRO", "ESPA", "ESPB", "PANTALLA"
  char comando[20]; // "MEDIR", "HUMEDAD", "STOP", "RESET"
  int parametro;    // 0, o valor ADC promedio (0-4095)
} MensajeFertiTrack;

MensajeFertiTrack msgEnviar;
MensajeFertiTrack msgRecibido;

// =========================================================================
// MÁQUINA DE ESTADOS DE ESP_B (no bloqueante: nada de delay() largos)
// =========================================================================
enum EstadoB
{
  EB_REPOSO,          // esperando la orden "MEDIR"
  EB_BAJANDO_PISTON,  // pistón extendiéndose hacia la tierra
  EB_MIDIENDO,        // pistón abajo, promediando ~5s de ADC
  EB_SUBIENDO_PISTON, // pistón retrayéndose
  EB_EMERGENCIA       // detenido por STOP, esperando RESET
};
EstadoB estadoActual = EB_REPOSO;

unsigned long tiempoInicioEtapa = 0;
unsigned long ultimaMuestraMillis = 0;
long sumaADC = 0;
int cantidadMuestras = 0;
int ultimaLecturaPromedio = -1; // último promedio calculado (para depurar)

// =========================================================================
// INDICADOR LED — código ordenado por el Maestro (0-4 = clasificarHumedad(),
// 9 = fertilizando). ESP_B NO decide nada, solo enciende lo que le piden.
//   0 = apagado          1 = SECO (amarillo)      2 = INTERMEDIA (azul+amar.)
//   3 = HUMEDA (azul)    4 = SOBRE_HUMEDA (azul parpadeando)   9 = FERTILIZANDO (rojo)
// =========================================================================
int indicadorActual = 0;
bool blinkAzulEncendido = false;
unsigned long ultimoBlinkMillis = 0;
const unsigned long INTERVALO_BLINK_MS = 400; // medio período del parpadeo

// =========================================================================
// CONTROL DEL PISTÓN (relé de 1 canal, mecanismo de retorno por resorte)
// accion:  1 = disparar/bajar (entierra el sensor) -> relé DESACTIVADO
//         -1 = recoger/subir                       -> relé ACTIVADO
//          0 = sin cambio (ya no existe "frenar": un relé solo tiene 2
//              estados posibles, y el que ya tenga puesto sostiene la
//              posición actual sin necesidad de tocar el pin de nuevo)
// =========================================================================
void actuarPiston(int accion)
{
  if (accion == 1)       digitalWrite(PIN_PISTON_RELE, RELE_APAGADO);  // dispara/baja
  else if (accion == -1) digitalWrite(PIN_PISTON_RELE, RELE_ACTIVADO); // recoge/sube
  // accion == 0: no se hace nada a propósito (ver comentario arriba).
}

// =========================================================================
// INDICADOR LED — aplica el código recibido del Maestro. El parpadeo del
// azul (código 4) NO se resuelve aquí: solo se arma el estado y el propio
// loop() lo va alternando con millis() (nada de delay()).
// =========================================================================
void apagarLEDs()
{
  digitalWrite(PIN_LED_AZUL, LED_OFF);
  digitalWrite(PIN_LED_AMARILLO, LED_OFF);
  digitalWrite(PIN_LED_ROJO, LED_OFF);
}

void actualizarIndicador(int codigo)
{
  indicadorActual = codigo;
  blinkAzulEncendido = false;
  ultimoBlinkMillis = millis();
  digitalWrite(PIN_LED_ROJO, codigo == 9 ? LED_ON : LED_OFF);

  switch (codigo)
  {
    case 1: // SECO -> amarillo fijo
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_ON);
      break;
    case 2: // INTERMEDIA -> azul + amarillo fijos
      digitalWrite(PIN_LED_AZUL, LED_ON);
      digitalWrite(PIN_LED_AMARILLO, LED_ON);
      break;
    case 3: // HUMEDA -> azul fijo
      digitalWrite(PIN_LED_AZUL, LED_ON);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
    case 4: // SOBRE_HUMEDA -> azul parpadeando (lo alterna el loop)
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
    case 9: // FERTILIZANDO -> rojo fijo (ya encendido arriba)
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
    default: // 0 u otro cualquiera -> todo apagado
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
  }
}

// Se llama una vez por vuelta de loop(); solo hace algo mientras el
// código activo sea 4 (SOBRE_HUMEDA), alternando el azul sin bloquear.
void actualizarBlinkIndicador()
{
  if (indicadorActual != 4) return;
  if (millis() - ultimoBlinkMillis >= INTERVALO_BLINK_MS)
  {
    ultimoBlinkMillis = millis();
    blinkAzulEncendido = !blinkAzulEncendido;
    digitalWrite(PIN_LED_AZUL, blinkAzulEncendido ? LED_ON : LED_OFF);
  }
}

// =========================================================================
// ENVÍO DE RESPUESTAS AL MAESTRO
// =========================================================================
void enviarAMaestro(const char *comando, int parametro)
{
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor, "ESPB", sizeof(msgEnviar.emisor) - 1);
  strncpy(msgEnviar.comando, comando, sizeof(msgEnviar.comando) - 1);
  msgEnviar.parametro = parametro;
  esp_err_t r = esp_now_send(MAC_MAESTRO, (uint8_t *)&msgEnviar, sizeof(msgEnviar));
  Serial.printf("[TX] %s (%d) -> MAESTRO %s\n",
                comando, parametro, r == ESP_OK ? "OK" : "ERROR");
}

// =========================================================================
// INICIA UN CICLO DE MEDICIÓN (llamado al recibir "MEDIR")
// =========================================================================
void iniciarMedicion()
{
  if (estadoActual != EB_REPOSO)
  {
    Serial.println("[ESP_B] MEDIR ignorado: ya hay un ciclo en curso o estamos en emergencia.");
    return;
  }

  sumaADC = 0;
  cantidadMuestras = 0;
  actuarPiston(1); // extender / bajar
  tiempoInicioEtapa = millis();
  estadoActual = EB_BAJANDO_PISTON;
  Serial.println("[ESP_B] MEDIR recibido. Bajando pistón...");
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

  // Solo el Maestro tiene autoridad sobre ESP_B; se ignora cualquier otro origen.
  if (emisor != "MAESTRO") return;

  // STOP tiene prioridad absoluta: frena el pistón donde esté, sin forzarlo
  // a terminar de subir ni de bajar (igual que ESP_A hace con sus motores).
  if (comando == "STOP")
  {
    // Con un solo relé no existe "frenar en el sitio": decidimos que ante
    // una emergencia lo más seguro es recoger el pistón (sacar el sensor
    // de la tierra), no dejarlo abajo.
    actuarPiston(-1);
    actualizarIndicador(0); // apaga los 3 LEDs por seguridad
    estadoActual = EB_EMERGENCIA;
    Serial.println("[ESP_B] *** STOP recibido: pistón recogido, esperando RESET ***");
    return;
  }

  if (comando == "RESET")
  {
    actuarPiston(-1); // asegurar posición recogida antes de aceptar nuevas órdenes
    actualizarIndicador(0); // vuelve a "sin datos" tras el reinicio
    sumaADC = 0;
    cantidadMuestras = 0;
    estadoActual = EB_REPOSO;
    Serial.println("[ESP_B] RESET recibido. Listo para nueva orden.");
    return;
  }

  // Mientras estemos en emergencia, cualquier otra orden se ignora hasta RESET.
  if (estadoActual == EB_EMERGENCIA)
  {
    Serial.println("[ESP_B] En EMERGENCIA: comando ignorado hasta recibir RESET.");
    return;
  }

  if (comando == "MEDIR")
  {
    iniciarMedicion();
  }
  else if (comando == "INDICADOR")
  {
    actualizarIndicador(parametro);
    Serial.printf("[ESP_B] Indicador LED actualizado a código %d\n", parametro);
  }
}

// =========================================================================
// SETUP
// =========================================================================
void setup()
{
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v3] Iniciando ESP_B (sensor de humedad + pistón)...");

  pinMode(PIN_PISTON_RELE, OUTPUT);
  actuarPiston(-1); // arranca recogido (posición segura, lejos de la tierra)

  pinMode(PIN_LED_AZUL, OUTPUT);
  pinMode(PIN_LED_AMARILLO, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);
  apagarLEDs(); // arranca sin ningún indicador encendido

  analogReadResolution(12);       // 0-4095, igual que espera clasificarHumedad() del Maestro
  analogSetAttenuation(ADC_11db); // rango completo 0-3.3V aprox.

  // Solo modo estación: ESP_B no crea AP, únicamente habla ESP-NOW con el Maestro.
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(50); // pequeño respiro antes de leer la MAC (evita 00:00:00 en el log)
  Serial.print("MAC de este ESP_B: "); Serial.println(WiFi.macAddress());
  Serial.println("(cópiala en MAC_ESP_B del Maestro y activa ESP_B_CONECTADA=true)");

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

  Serial.println("ESP_B listo. Esperando orden \"MEDIR\" del Maestro.\n");
}

// =========================================================================
// LOOP — máquina de estados no bloqueante
// =========================================================================
void loop()
{
  actualizarBlinkIndicador(); // parpadeo del azul (SOBRE_HUMEDA); no bloquea nada

  switch (estadoActual)
  {
  case EB_BAJANDO_PISTON:
    if (millis() - tiempoInicioEtapa >= TIEMPO_PISTON_MS)
    {
      actuarPiston(0); // ya llegó abajo: frena
      sumaADC = 0;
      cantidadMuestras = 0;
      ultimaMuestraMillis = millis();
      tiempoInicioEtapa = millis();
      estadoActual = EB_MIDIENDO;
      Serial.println("[ESP_B] Pistón abajo. Promediando ~5s de lectura ADC...");
    }
    break;

  case EB_MIDIENDO:
    // Toma una muestra cada INTERVALO_MUESTRA_MS mientras dura el promedio.
    if (millis() - ultimaMuestraMillis >= INTERVALO_MUESTRA_MS)
    {
      ultimaMuestraMillis = millis();
      sumaADC += analogRead(PIN_SENSOR_HUMEDAD);
      cantidadMuestras++;
    }

    if (millis() - tiempoInicioEtapa >= TIEMPO_PROMEDIO_MS)
    {
      ultimaLecturaPromedio = (cantidadMuestras > 0) ? (int)(sumaADC / cantidadMuestras) : 0;
      actuarPiston(-1); // retraer / subir
      tiempoInicioEtapa = millis();
      estadoActual = EB_SUBIENDO_PISTON;
      Serial.printf("[ESP_B] Promedio=%d (%d muestras). Subiendo pistón...\n",
                    ultimaLecturaPromedio, cantidadMuestras);
    }
    break;

  case EB_SUBIENDO_PISTON:
    if (millis() - tiempoInicioEtapa >= TIEMPO_PISTON_MS)
    {
      actuarPiston(0); // ya llegó arriba: frena
      enviarAMaestro("HUMEDAD", ultimaLecturaPromedio);
      estadoActual = EB_REPOSO;
      Serial.println("[ESP_B] Pistón arriba. Respuesta enviada. En reposo.\n");
    }
    break;

  case EB_REPOSO:
  case EB_EMERGENCIA:
    // No hacer nada: se espera la próxima orden ESP-NOW del Maestro.
    break;
  }
}
