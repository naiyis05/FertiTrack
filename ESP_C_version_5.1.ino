/*
  =========================================================================
  FertiTrack_ESP_C_v5.1.ino — ESP_C (INDICADORES LED DE 12 V — SOLO RECEPTOR)
  Placa : ESP32 Dev Module (WROOM-32) — Core Espressif 3.x.x (OBLIGATORIO)
  MAC de ESTE ESP32 : 20:50:0D:4D:5F:AC   (ya registrada en el Maestro)
  =========================================================================

  ROL: enciende/apaga 3 canales de LED de 12 V en la carcasa del robot
  según la orden del Maestro. No tiene sensores ni motores, no decide nada
  y NO le responde al Maestro (el Maestro no espera acuse de recibo).

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.1 (correcciones del informe)
  -------------------------------------------------------------------
  1) PATRÓN DE COLA + BANDERA DE STOP (igual que ESP_A y ESP_B).
     El callback de ESP-NOW ya NO toca los pines ni imprime: solo copia el
     comando a una cola (protegida con spinlock, porque el callback corre
     en la tarea WiFi y loop() en otro núcleo) y, si es STOP, levanta
     además la bandera 'stopPendiente'. loop() atiende primero esa bandera
     y luego vacía la cola EN ORDEN. Resultado: ya no puede quedar un LED
     encendido tras un STOP por una carrera entre callback y loop().
  2) SIN String NI Serial.printf EN EL CALLBACK (causaban latencia y
     fragmentación de memoria en la tarea WiFi). Se usa strcmp() sobre
     copias con terminador garantizado. Los mensajes de depuración los
     imprime loop().
  3) WiFi.setSleep(false): el radio no se duerme y no se pierden paquetes
     ESP-NOW entre ráfagas.
  4) FALLA VISIBLE: en emergencia (STOP) el LED ROJO PARPADEA en vez de
     apagar todo, para que el robot no parezca simplemente apagado.
     Si prefieres todo apagado como en v5, pon FALLA_ROJO_PARPADEA = false.
  5) INDICADOR REPETIDO = SIN EFECTO. El Maestro v5.1 reenvía el INDICADOR
     actual cada 5 s (refresco). Si el código es el mismo que ya está
     activo, se ignora; así el azul parpadeante (código 4) no se reinicia
     cada 5 s.
  6) Encabezado actualizado: el Maestro YA tiene la MAC de ESP_C y
     ESP_C_CONECTADA = true.

  -------------------------------------------------------------------
  COMANDOS (solo si emisor == "MAESTRO")
  -------------------------------------------------------------------
    "INDICADOR" (parametro 0-4, 9)  -> ver tabla
    "STOP"   -> emergencia: ignora INDICADOR hasta RESET; rojo parpadea
                (o todo apagado si FALLA_ROJO_PARPADEA = false)
    "RESET"  -> sale de emergencia, todo apagado ("sin datos")

  TABLA DE CÓDIGOS (igual que clasificarHumedad() del Maestro):
    0 = apagado / sin datos
    1 = SECA            -> amarillo fijo
    2 = INTERMEDIA      -> amarillo + azul fijos
    3 = HÚMEDA          -> azul fijo
    4 = SOBRE-HÚMEDA    -> azul PARPADEANDO (millis, sin delay)
    9 = FUMIGANDO       -> rojo fijo
    (FALLA/emergencia   -> rojo PARPADEANDO, lo decide el STOP, no el código)

  COLORES EN LA CARCASA: ROJO = fumigación / falla | AZUL = húmedo |
  AMARILLO = seco | amarillo + azul = intermedio.

  NOTA SOBRE RECUPERACIÓN: el Maestro v5.1 reenvía el INDICADOR cada 5 s en
  reposo y el STOP cada 5 s mientras dure una emergencia. Si ESP_C se
  reinicia, se pone al día sola en máximo ~5 s.

  -------------------------------------------------------------------
  HARDWARE
  -------------------------------------------------------------------
  Los LED son de 12 V: el GPIO (3.3 V) NUNCA los enciende directo. Cada
  canal pasa por un transistor NPN (base con resistencia) o un módulo
  relé/opto. Las macros LED_ON/LED_OFF asumen driver activo en HIGH; si
  tu módulo es activo en LOW, invierte solo esas dos macros.
    GPIO 25 -> LED AZUL | GPIO 32 -> LED AMARILLO | GPIO 33 -> LED ROJO
  (Con driver activo en LOW, los pines pueden dar un pulso al arrancar;
   apagarLEDs() se llama lo antes posible en setup().)

  IDE: Board "ESP32 Dev Module", Upload 921600, Flash 80 MHz, QIO (o DIO),
  Partition Default. Core 3.x (el callback usa esp_now_recv_info).
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const int CANAL_WIFI = 1; // mismo canal en los 5 nodos

// MAC del MAESTRO (confirmada)
uint8_t MAC_MAESTRO[] = {0x20, 0x50, 0x0D, 0x10, 0x1E, 0xE0};

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_LED_AZUL     25 // HUMEDA (fijo) / SOBRE_HUMEDA (parpadeando)
#define PIN_LED_AMARILLO 32 // SECA (fijo) / INTERMEDIA (junto con azul)
#define PIN_LED_ROJO     33 // FUMIGANDO (fijo) / FALLA (parpadeando)
#define LED_ON  HIGH
#define LED_OFF LOW

// ─────────────────────────────────────────────────────────────────────
// COMPORTAMIENTO
// ─────────────────────────────────────────────────────────────────────
const bool FALLA_ROJO_PARPADEA = true;          // false = todo apagado en falla
const unsigned long INTERVALO_BLINK_MS = 400;   // medio período del parpadeo

// ─────────────────────────────────────────────────────────────────────
// ESTRUCTURA UNIFICADA (idéntica en los 5 sketches — NO modificar)
// ─────────────────────────────────────────────────────────────────────
typedef struct {
  char emisor[10];   // "MAESTRO", "ESPA", "ESPB", "ESPC", "PANTALLA"
  char comando[20];  // "INDICADOR", "STOP", "RESET"
  int  parametro;
} MensajeFertiTrack;

// ─────────────────────────────────────────────────────────────────────
// COLA DE ÓRDENES (callback -> loop)
// ─────────────────────────────────────────────────────────────────────
enum TipoOrden : uint8_t { ORD_INDICADOR, ORD_STOP, ORD_RESET };

struct Orden {
  TipoOrden tipo;
  int param;
};

const int COLA_MAX = 16;
Orden cola[COLA_MAX];
volatile int colaIni = 0;
volatile int colaCuenta = 0;
volatile uint32_t ordenesPerdidas = 0;      // solo diagnóstico
volatile bool stopPendiente = false;        // STOP tiene prioridad absoluta
volatile uint32_t paquetesIgnorados = 0;    // emisor distinto de MAESTRO o tamaño malo

portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

// Solo la llama el callback (tarea WiFi)
void encolarOrden(TipoOrden t, int p) {
  portENTER_CRITICAL(&muxCola);
  if (colaCuenta < COLA_MAX) {
    int pos = (colaIni + colaCuenta) % COLA_MAX;
    cola[pos].tipo = t;
    cola[pos].param = p;
    colaCuenta++;
  } else {
    ordenesPerdidas++;
  }
  portEXIT_CRITICAL(&muxCola);
}

// Solo la llama loop()
bool sacarOrden(Orden &o) {
  bool hay = false;
  portENTER_CRITICAL(&muxCola);
  if (colaCuenta > 0) {
    o = cola[colaIni];
    colaIni = (colaIni + 1) % COLA_MAX;
    colaCuenta--;
    hay = true;
  }
  portEXIT_CRITICAL(&muxCola);
  return hay;
}

// ─────────────────────────────────────────────────────────────────────
// ESTADO DE LOS LED (solo lo toca loop())
// ─────────────────────────────────────────────────────────────────────
int indicadorActual = 0;
bool enEmergencia = false;
bool blinkEncendido = false;
unsigned long ultimoBlinkMillis = 0;

void apagarLEDs() {
  digitalWrite(PIN_LED_AZUL, LED_OFF);
  digitalWrite(PIN_LED_AMARILLO, LED_OFF);
  digitalWrite(PIN_LED_ROJO, LED_OFF);
}

// Aplica un código del Maestro. El parpadeo del azul (4) lo alterna
// actualizarParpadeo() con millis().
void actualizarIndicador(int codigo) {
  indicadorActual = codigo;
  blinkEncendido = false;
  ultimoBlinkMillis = millis();

  digitalWrite(PIN_LED_ROJO, codigo == 9 ? LED_ON : LED_OFF);

  switch (codigo) {
    case 1: // SECO -> amarillo
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_ON);
      break;
    case 2: // INTERMEDIA -> azul + amarillo
      digitalWrite(PIN_LED_AZUL, LED_ON);
      digitalWrite(PIN_LED_AMARILLO, LED_ON);
      break;
    case 3: // HUMEDA -> azul
      digitalWrite(PIN_LED_AZUL, LED_ON);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
    case 4: // SOBRE_HUMEDA -> azul parpadeando (lo alterna el loop)
    case 9: // FUMIGANDO -> solo rojo (ya encendido arriba)
    default: // 0 u otro -> apagado
      digitalWrite(PIN_LED_AZUL, LED_OFF);
      digitalWrite(PIN_LED_AMARILLO, LED_OFF);
      break;
  }
}

void entrarEmergencia() {
  enEmergencia = true;
  indicadorActual = 0;
  apagarLEDs();
  blinkEncendido = false;
  ultimoBlinkMillis = millis();
}

void salirEmergencia() {
  enEmergencia = false;
  indicadorActual = 0;
  apagarLEDs();
}

// Parpadeo no bloqueante: rojo en emergencia, azul en SOBRE_HUMEDA
void actualizarParpadeo() {
  if (enEmergencia) {
    if (!FALLA_ROJO_PARPADEA) return;
    if (millis() - ultimoBlinkMillis >= INTERVALO_BLINK_MS) {
      ultimoBlinkMillis = millis();
      blinkEncendido = !blinkEncendido;
      digitalWrite(PIN_LED_ROJO, blinkEncendido ? LED_ON : LED_OFF);
    }
    return;
  }
  if (indicadorActual != 4) return;
  if (millis() - ultimoBlinkMillis >= INTERVALO_BLINK_MS) {
    ultimoBlinkMillis = millis();
    blinkEncendido = !blinkEncendido;
    digitalWrite(PIN_LED_AZUL, blinkEncendido ? LED_ON : LED_OFF);
  }
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW (firma Core 3.x) — SOLO copia y encola. Sin pines,
// sin String, sin Serial.
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != (int)sizeof(MensajeFertiTrack)) { paquetesIgnorados++; return; }

  MensajeFertiTrack m;
  memcpy(&m, incomingData, sizeof(m));
  m.emisor[sizeof(m.emisor) - 1] = '\0';       // terminador garantizado
  m.comando[sizeof(m.comando) - 1] = '\0';

  if (strcmp(m.emisor, "MAESTRO") != 0) { paquetesIgnorados++; return; }

  if (strcmp(m.comando, "STOP") == 0) {
    stopPendiente = true;                      // prioridad absoluta
    encolarOrden(ORD_STOP, 0);                 // y queda en orden para el RESET que venga
  } else if (strcmp(m.comando, "RESET") == 0) {
    encolarOrden(ORD_RESET, 0);
  } else if (strcmp(m.comando, "INDICADOR") == 0) {
    encolarOrden(ORD_INDICADOR, m.parametro);
  } else {
    paquetesIgnorados++;
  }
}

// ─────────────────────────────────────────────────────────────────────
// SETUP
// ─────────────────────────────────────────────────────────────────────
void setup() {
  // Primero los pines: que ningún LED quede encendido por azar
  pinMode(PIN_LED_AZUL, OUTPUT);
  pinMode(PIN_LED_AMARILLO, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);
  apagarLEDs();

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.1] Iniciando ESP_C (indicadores LED de 12V)...");

  WiFi.mode(WIFI_STA);                 // sin AP: solo ESP-NOW con el Maestro
  WiFi.disconnect();
  WiFi.setSleep(false);                // el radio no se duerme: no se pierden paquetes
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(50);

  Serial.print("MAC de este ESP_C: ");
  Serial.println(WiFi.macAddress());
  Serial.println("(debe ser 20:50:0D:4D:5F:AC, la que tiene el Maestro)");

  if (esp_now_init() != ESP_OK) {
    Serial.println("ERROR: no se pudo inicializar ESP-NOW.");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  // Recibir no exige peer; se registra el Maestro por consistencia con los demás nodos
  esp_now_peer_info_t peerInfo = {};
  peerInfo.channel = CANAL_WIFI;
  peerInfo.encrypt = false;
  memcpy(peerInfo.peer_addr, MAC_MAESTRO, 6);
  if (esp_now_add_peer(&peerInfo) != ESP_OK) {
    Serial.println("AVISO: no se pudo registrar al Maestro como peer (no afecta la recepción).");
  }

  Serial.println("ESP_C listo. Esperando INDICADOR / STOP / RESET del Maestro.\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — atiende STOP primero, luego la cola en orden, luego parpadeos
// ─────────────────────────────────────────────────────────────────────
void loop() {
  // 1) STOP: prioridad absoluta, antes de mirar cualquier otra orden
  if (stopPendiente) {
    stopPendiente = false;
    if (!enEmergencia) {
      entrarEmergencia();
      Serial.println("[ESP_C] *** STOP: emergencia (rojo parpadeando) hasta RESET ***");
    }
  }

  // 2) Cola en orden de llegada
  Orden o;
  while (sacarOrden(o)) {
    switch (o.tipo) {
      case ORD_STOP:
        if (!enEmergencia) entrarEmergencia();   // idempotente (el Maestro repite el STOP)
        break;

      case ORD_RESET:
        if (enEmergencia) Serial.println("[ESP_C] RESET: sale de emergencia.");
        salirEmergencia();
        break;

      case ORD_INDICADOR:
        if (enEmergencia) break;                 // ignorado hasta RESET
        if (o.param == indicadorActual) break;   // refresco del Maestro: sin efecto
        actualizarIndicador(o.param);
        Serial.printf("[ESP_C] Indicador -> codigo %d\n", o.param);
        break;
    }
  }

  // 3) Parpadeos no bloqueantes
  actualizarParpadeo();

  // 4) Diagnóstico ocasional (no bloquea)
  static uint32_t perdidasVistas = 0;
  if (ordenesPerdidas != perdidasVistas) {
    perdidasVistas = ordenesPerdidas;
    Serial.printf("[ESP_C] AVISO: cola llena, ordenes perdidas = %lu\n", (unsigned long)perdidasVistas);
  }
}
