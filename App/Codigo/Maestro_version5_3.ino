/*
  =========================================================================
  FertiTrack_Maestro_v5.3.ino — MAESTRO (arquitectura por zonas, monorriel)
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 : 20:50:0D:10:1E:E0
  =========================================================================

  BASE: Maestro v4 del informe (WebServer del core, sin ESPAsyncWebServer,
  que causaba el crash "tcp_alloc ... Required to lock TCPIP core").

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.3 (correcciones tras las primeras pruebas reales)
  -------------------------------------------------------------------
  1) TIEMPOS DEL ACTUADOR LINEAL (medidos con cronómetro): extender todo el
     pistón 4.71 s, recogerlo 4.82 s. Antes el código suponía ~3 s. Con 3 s
     el actuador solo recorría ~63 % de la carrera: nunca llegaba al tope
     ni se recogía del todo, y quedaba "siempre extendido". El Maestro ahora
     conoce esos tiempos (ACT_EXTENDER_MS / ACT_RECOGER_MS) y calcula con
     ellos el timeout de cada orden de brazo.
     >>> ESP_A DEBE usar los MISMOS valores (ver ACT_* abajo). Mientras ESP_A
     >>> siga con 3000 ms el problema físico persiste: hay que actualizarla.

  2) CALIBRACIÓN DE DISTANCIA. Con 10 cm en la app el robot recorría 40 cm:
     los pasos por cm reales son 400/4 = 100, no 400. Nuevo valor por defecto
     100 y, como el valor viejo (400) quedó GUARDADO en la memoria, se usa una
     clave nueva ("pasos_cm2") para ignorarlo. Además, una calibración guiada
     desde la app: "Mover prueba X cm" -> mides con una cinta cuánto avanzó
     -> escribes la medida -> el Maestro corrige solo pasos/cm y lo guarda.
     OJO: con 100 pasos/cm las velocidades equivalen a 60 / 30 / 15 cm/s
     (RÁPIDA / MEDIA / LENTA). Si te parecen demasiado rápidas, bájalas
     (iguales en Maestro y ESP_A).

  3) SENSOR DE HUMEDAD RESISTIVO. Un sensor resistivo SECO en circuito
     abierto da ADC ~4095: es una lectura VÁLIDA, no un error. La regla
     "ADC >= 4090 -> FALLA 3" mandaba a emergencia con el suelo seco. Ahora
     solo se considera inválido ADC < 1 (sin muestras / cortocircuito).
     (Desventaja inevitable: un sensor desconectado también lee ~4095 y se
     vería como "seca".)

  4) CLASIFICACIÓN POR PORCENTAJE, CALIBRABLE. Se definen dos puntos de
     referencia medidos con el propio sensor: SECO (ADC en suelo seco/aire)
     y MOJADO (ADC en suelo saturado/agua), y de ahí se calcula el % de
     humedad. Rangos por defecto (editables en la app):
        SECA          < 35 %   -> es la ÚNICA que se riega
        INTERMEDIA    35-65 %  -> no se riega
        HÚMEDA        65-90 %
        SOBRE-HÚMEDA  > 90 %
     Los umbrales en ADC se calculan solos a partir de esos puntos. Desde la
     app: "Medir sensor ahora" (prueba sin mover el robot), "Guardar como
     SECO" y "Guardar como MOJADO". Ya no existen los parámetros
     umbral_seco/umbral_humedo/umbral_sobre_humedo de /api/configurar.

  5) BRAZO DE RIEGO. Solo se extiende durante el riego (después de sensar) y
     se recoge en cuanto termina la ÚLTIMA zona seca; después regresa al
     origen. Antes subía y bajaba entre cada tramo, y cada movimiento tarda
     ~5 s (los actuadores son de ciclo de trabajo 10 %: conviene moverlos lo
     menos posible). Ahora hay UNA sola extensión: el robot salta con el
     brazo arriba hasta la primera zona seca, lo baja, riega las zonas secas
     (bomba solo sobre las secas) y, cuando pasó la última, lo sube y vuelve.
     No recorre las zonas restantes. Si prefieres el comportamiento anterior
     (subir el brazo entre tramos secos), pon
     MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS = false.

  6) BRAZO DE FERTILIZACIÓN. Ya funcionaba así y se conserva: extiende,
     ESPERA a que ESP_A confirme (BRAZO_OK), recién entonces inicia el
     recorrido, al llegar al final lo recoge y después regresa al origen.

  7) BRAZOS ARRIBA AL ARRANCAR. Si el robot se enciende con un brazo
     extendido, nada lo recogía. Ahora, 3 s después de encender (para que
     los otros nodos arranquen), el Maestro ejecuta la misma recuperación
     del botón Reiniciar: RESET, sube riego, sube fertilizante y regresa al
     origen (ESP_A conserva su posición si solo se reinició el Maestro).
     Para pruebas con el Maestro solo, sin ESP_A: RECOGER_BRAZOS_AL_ARRANCAR
     = false (si no, a los ~10 s aparece la falla 1).

  8) PARA LA PANTALLA (sin romper la actual): mensaje nuevo "FASE_MOV" que
     indica qué hace el robot: 0 ninguna / 1 avanzando / 2 regresando al
     origen / 3 bajando brazo / 4 subiendo brazo. La Pantalla actual ignora
     los comandos que no conoce, así que sigue funcionando igual.

  Estados nuevos: ARRANQUE, PRUEBA_AVANZANDO, PRUEBA_LISTA, PRUEBA_RETORNANDO,
  PRUEBA_SENSOR.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.2 (huecos de recuperación tras mensajes perdidos)
  -------------------------------------------------------------------
  ESP-NOW no confirma la entrega a nivel de aplicación. En v5.1 el STOP se
  repetía pero otros avisos importantes salían UNA sola vez:

  A) RESET REPETIDO. Antes salía una vez. Si se perdía el RESET a ESP_C,
     ESP_C se quedaba con el rojo parpadeando para siempre (ignora INDICADOR
     hasta recibir RESET) sin que nada lo avisara; si se perdía a ESP_A o
     ESP_B, la recuperación terminaba en FALLA 1 o 2. Ahora el RESET sale
     1 + RESET_REPETICIONES_EXTRA veces (3 en total), separado
     SEPARACION_RESET_MS, SIEMPRE dentro de la pausa ESPERA_RECUP_MS (500 ms)
     que precede a la primera orden de la recuperación. Un RESET más tardío
     NO se manda a propósito: ESP_A lo tomaría como "aborta lo que haces" y
     cortaría el movimiento de un brazo en curso. Una comprobación en tiempo
     de compilación (static_assert) impide dejar tiempos que no quepan.

  B) "SIN FALLA" EN EL REFRESCO. Antes FALLA 0 se mandaba una sola vez al
     reiniciar; si se perdía, la Pantalla se quedaba con el cartel rojo
     mientras el robot trabajaba normal. Ahora el refresco de 5 s en
     reposo también manda FALLA 0 (la Pantalla solo repinta si el valor
     cambia, así que no hay parpadeos ni reinicio de animaciones).

  C) STOP PERIÓDICO EN EMERGENCIA. El refresco de 5 s en emergencia ahora
     reenvía también el STOP a ESP_A, ESP_B y ESP_C. Es idempotente en los
     tres. Cubre: (1) ESP_C o ESP_B que se reinicien (brownout) durante la
     emergencia y de otro modo quedarían como si no pasara nada, y (2) un
     STOP que no llegó a ESP_A. (Esto hace cierta la nota de ESP_C v5.1:
     "el Maestro reenvía el STOP cada 5 s mientras dure una emergencia".)

  Sin cambios de protocolo ni de formato de mensaje: los otros 4 nodos v5.1
  siguen siendo compatibles tal cual.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.1 (correcciones de la última revisión del informe)
  -------------------------------------------------------------------
  A) BOTÓN DE EMERGENCIA. La versión v5 NUNCA disparaba: actualizaba el
     "estado anterior" en cada vuelta del loop, así que la condición de
     flanco (HIGH -> LOW) jamás se cumplía. Ahora hay un antirrebote real
     con 'botonEstable' (cambia solo si la lectura se mantiene DEBOUNCE_MS)
     y se inicializa en setup().

  B) STOP REPETIDO. ESP-NOW no garantiza entrega. Al declarar una falla,
     el STOP sale de inmediato a ESP_A, ESP_B y ESP_C y se repite
     STOP_REPETICIONES_EXTRA veces más, una cada SEPARACION_STOP_MS (30 ms),
     sin delay() (lo gestiona procesarStopsRepetidos() desde el loop).
     Reiniciar cancela los STOP pendientes.

  C) VALIDACIÓN DEL SENSOR. En EM_SENSADO_MIDIENDO, un ADC de 0 o >= 4090
     (sensor desconectado, en corto, sin alimentación) ya no se clasifica
     como suelo: se declara FALLA 3.

  D) REFRESCO PERIÓDICO (cada 5 s, en reposo Y en emergencia). Se reenvían
     ACTIVIDAD, MODO_RUTINA, ESTADO_SUELO, TOTAL_ZONAS y, si hay falla
     activa, FALLA. En reposo también el INDICADOR actual para ESP_C. Así
     una Pantalla o ESP_C que se reinicie se pone al día sola. (En
     emergencia NO se reenvía INDICADOR, para no "des-latchar" a ESP_C.)

  E) COMENTARIOS corregidos: ESP_C ya tiene MAC y ESP_C_CONECTADA = true.

  CÓDIGOS DE FALLA: 1 timeout ESP_A | 2 timeout ESP_B | 3 lectura inválida
  del sensor (NUEVO: la Pantalla debe reconocerlo) | 8 detener desde app |
  9 botón físico.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5 (resultado de la auditoría de los 4 códigos)
  -------------------------------------------------------------------
  1) TIMEOUT DINÁMICO DE MOVIMIENTO. Antes: 8 s fijos para todo. Con 400
     pasos/cm la fertilización (un solo tramo a 3000 pasos/s) se pasaba
     de 8 s con >52 cm y el retorno a 6000 pasos/s con >90 cm: FALLA 1
     falsa a mitad de camino. Ahora el límite se calcula con
     pasos/velocidad + rampa de aceleración + 50 % de holgura + 3 s.
     >>> VEL_RAPIDA / VEL_MEDIA / VEL_LENTA / ACEL deben ser IGUALES a los
     >>> de ESP_A. Si cambias uno allá, cámbialo aquí.

  2) RECUPERACIÓN TRAS EMERGENCIA. Antes, al reiniciar, el Maestro creía
     que el robot estaba en el origen con los brazos arriba, pero ESP_A
     conserva su posición y deja los brazos donde estaban. Ahora
     "Reiniciar" ejecuta: RESET -> espera 0.5 s -> subir brazo de riego
     -> subir brazo de fertilizante -> RETORNAR al origen -> reposo.
     Mientras tanto el estado es RECUP_* y no se acepta ningún ciclo.

  3) RIEGO SELECTIVO POR TRAMOS. Las zonas consecutivas con la misma
     acción (todas secas / todas no-secas) se mandan como UN SOLO
     movimiento. Así las velocidades RÁPIDA y LENTA sí se distinguen
     (una zona suelta de ~1000 pasos nunca llega a la velocidad máxima).
     El SENSADO sigue parando en cada zona, como corresponde.

  4) COLA DE NOTIFICACIONES. Antes se disparaban ~6 mensajes ESP-NOW
     seguidos y la orden a ESP_A iba de última. Ahora la orden crítica
     (a ESP_A / ESP_B) sale PRIMERO e inmediatamente, y los avisos a
     Pantalla y LEDs salen por una cola, uno cada 20 ms. Las órdenes
     críticas se reintentan hasta 3 veces si esp_now_send() falla.

  5) iniciarEspera() se llama ANTES de enviar la orden (antes era después),
     para que una respuesta muy rápida no se pierda.

  6) ESP_C (solo LEDs indicadores). Con ESP_C_CONECTADA = true (valor
     actual) los INDICADOR van a ESP_C y ESP_B ya no los recibe. Si algún
     día se quita ESP_C, poner el flag en false y los LEDs vuelven a ESP_B.

  7) Estado del Maestro ampliado: RECUP_ESPERA, RECUP_BRAZO_RIEGO,
     RECUP_BRAZO_FERTI, RECUP_RETORNANDO.

  -------------------------------------------------------------------
  NO INCLUIDO (decisión consciente)
  -------------------------------------------------------------------
  - Si el Maestro se reinicia solo (brownout) con el robot a mitad de
    riel, no sabe dónde está. Solución real: final de carrera en A para
    hacer homing. Mientras tanto: apaga y enciende todo el robot junto
    con el robot en el origen, o usa Reiniciar tras una emergencia.

  -------------------------------------------------------------------
  PROTOCOLO (sin cambios de formato respecto a v4)
  -------------------------------------------------------------------
  Maestro -> ESP_A : AVANZAR_RAPIDO/MEDIO/LENTO (parametro = pasos),
                     RETORNAR (0), BRAZO_RIEGO / BRAZO_FERTI (1 baja, 0 sube),
                     STOP, RESET
  ESP_A -> Maestro : LISTO (posición), BRAZO_OK (1/0)
  Maestro -> ESP_B : MEDIR, STOP, RESET (+ INDICADOR solo si no hay ESP_C)
  ESP_B -> Maestro : HUMEDAD (ADC promedio)
  Maestro -> ESP_C : INDICADOR (0-4, 9), STOP, RESET (ESP_C nunca responde)
  Maestro -> Pantalla : ESTADO_SUELO, ACTIVIDAD, MODO_RUTINA, ZONA_ACTUAL,
                        TOTAL_ZONAS, FALLA, FASE_MOV (nuevo en v5.3)

  LIBRERÍAS:
   - ArduinoJson **v7.x** (Library Manager). v6 NO compila: el código usa
     JsonDocument sin capacidad y .to<JsonArray>().
   - Preferences, WiFi, WebServer, esp_now, esp_wifi: incluidas en el core.

  MACs confirmadas:
   MAESTRO : 20:50:0D:10:1E:E0 (este nodo)
   ESP_A   : C8:85:41:2C:A5:BC
   ESP_B   : CC:DB:A7:34:A0:FC
   ESP_C   : 20:50:0D:4D:5F:AC
   PANTALLA: AC:A7:04:EA:09:74
  =========================================================================
*/

#include <WiFi.h>
#include <WebServer.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <math.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS (arriba de todo, antes de cualquier función: evita el bug de
// prototipos automáticos del IDE de Arduino)
// ─────────────────────────────────────────────────────────────────────
enum EstadoMaestro {
  EM_ESPERANDO,
  EM_SENSADO_AVANZANDO, EM_SENSADO_MIDIENDO, EM_SENSADO_RETORNANDO,
  EM_RIEGO_BAJANDO_BRAZO, EM_RIEGO_AVANZANDO,
  EM_RIEGO_SUBIENDO_BRAZO, EM_RIEGO_RETORNANDO,
  EM_FERTI_BAJANDO_BRAZO, EM_FERTI_AVANZANDO,
  EM_FERTI_SUBIENDO_BRAZO, EM_FERTI_RETORNANDO,
  EM_RECUP_ESPERA, EM_RECUP_BRAZO_RIEGO, EM_RECUP_BRAZO_FERTI, EM_RECUP_RETORNANDO,
  EM_ARRANQUE,                                               // espera inicial antes de recoger brazos
  EM_PRUEBA_AVANZANDO, EM_PRUEBA_LISTA, EM_PRUEBA_RETORNANDO, // calibración de distancia
  EM_PRUEBA_SENSOR,                                          // medición suelta del sensor
  EM_EMERGENCIA
};

enum ModoRutina { RUTINA_NINGUNA, RUTINA_SENSADO, RUTINA_RIEGO, RUTINA_FERTILIZACION };
enum NodoEsperado { NODO_NINGUNO, NODO_A, NODO_B };

// Notificación pendiente de salir por la cola (destino 0 = Pantalla, 1 = LEDs)
struct Notificacion {
  uint8_t destino;
  char cmd[20];
  int param;
};

typedef struct {
  char emisor[10];
  char comando[20];
  int parametro;
} MensajeFertiTrack;

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const char *AP_SSID = "FertiTrack_AP";
const char *AP_PASS = "123456789";
const int CANAL_WIFI = 1;

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_RELE_RIEGO 15
#define PIN_RELE_FERTI 4
#define PIN_BOTON_EMERGENCIA 5
#define RELE_ON LOW
#define RELE_OFF HIGH

// ─────────────────────────────────────────────────────────────────────
// MACs DE LOS ESCLAVOS
// ─────────────────────────────────────────────────────────────────────
uint8_t MAC_ESP_A[] = {0xC8, 0x85, 0x41, 0x2C, 0xA5, 0xBC};
uint8_t MAC_PANTALLA[] = {0xAC, 0xA7, 0x04, 0xEA, 0x09, 0x74};

const bool ESP_B_CONECTADA = true;
uint8_t MAC_ESP_B[] = {0xCC, 0xDB, 0xA7, 0x34, 0xA0, 0xFC};

// ESP_C (solo LEDs indicadores de 12 V). Si se pone en false, los LEDs
// vuelven a ser manejados por ESP_B.
const bool ESP_C_CONECTADA = true;
uint8_t MAC_ESP_C[] = {0x20, 0x50, 0x0D, 0x4D, 0x5F, 0xAC}; // ESP_C

// ─────────────────────────────────────────────────────────────────────
// PARÁMETROS DE ESP_A (ESPEJO: deben ser IGUALES a los de ESP_A)
// ─────────────────────────────────────────────────────────────────────
const float VEL_RAPIDA = 6000; // pasos/s
const float VEL_MEDIA = 3000;
const float VEL_LENTA = 1500;
const float ACEL = 3000;       // pasos/s^2

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS
// ─────────────────────────────────────────────────────────────────────
const unsigned long TIMEOUT_A_MS = 8000;       // límite por defecto para ESP_A (brazos usan timeoutBrazoMs)

// Actuadores lineales de los brazos (riego y fertilización). Tiempos MEDIDOS
// con cronómetro para recorrer TODA la carrera (100 mm):
const unsigned long ACT_EXTENDER_MEDIDO_MS = 4710;  // extender todo el pistón
const unsigned long ACT_RECOGER_MEDIDO_MS = 4820;   // recogerlo todo
const unsigned long ACT_MARGEN_MS = 300;            // margen para asegurar el tope
// >>> ESP_A debe energizar el actuador exactamente este tiempo (medido + margen).
// >>> El actuador tiene final de carrera interno: el margen no lo daña.
const unsigned long ACT_EXTENDER_MS = ACT_EXTENDER_MEDIDO_MS + ACT_MARGEN_MS; // 5010
const unsigned long ACT_RECOGER_MS = ACT_RECOGER_MEDIDO_MS + ACT_MARGEN_MS;   // 5120
const unsigned long TIMEOUT_B_MS = 12000;      // MEDIR (~8 s nominales en ESP_B)
const unsigned long REFRESCO_IDLE_MS = 5000;
const unsigned long ESPERA_RECUP_MS = 500;     // pausa entre RESET y primera orden
const unsigned long SEPARACION_COLA_MS = 20;   // entre avisos a Pantalla/LEDs
const unsigned long DEBOUNCE_MS = 60;
const unsigned long SEPARACION_STOP_MS = 30;   // entre repeticiones del STOP
const uint8_t STOP_REPETICIONES_EXTRA = 2;     // STOP total = 1 + 2 = 3 envíos
const unsigned long SEPARACION_RESET_MS = 120; // entre repeticiones del RESET
const uint8_t RESET_REPETICIONES_EXTRA = 2;    // RESET total = 1 + 2 = 3 envíos

// Los RESET repetidos deben salir DENTRO de la pausa ESPERA_RECUP_MS (antes de
// la primera orden de la recuperación). Un RESET tardío haría que ESP_A aborte
// un brazo en curso. Si cambias estos tiempos y no caben, no compila.
static_assert((unsigned long)RESET_REPETICIONES_EXTRA * SEPARACION_RESET_MS + 150UL < ESPERA_RECUP_MS,
              "Los RESET repetidos no caben dentro de ESPERA_RECUP_MS");

// Rango válido del ADC del sensor de humedad (12 bits). SENSOR RESISTIVO:
// seco = circuito abierto = ~4095, y eso es una lectura VÁLIDA. Solo se
// rechaza ADC < 1 (ESP_B no obtuvo muestras, o cortocircuito) -> FALLA 3.
const int ADC_MIN_VALIDO = 1;
const int ADC_MAX_VALIDO = 4095;

// Comportamiento
const bool MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS = true; // true: una sola extensión por riego
const bool RECOGER_BRAZOS_AL_ARRANCAR = true;        // false para pruebas sin ESP_A
const unsigned long ESPERA_ARRANQUE_MS = 3000;       // deja arrancar a los demás nodos

MensajeFertiTrack msgEnviar;
MensajeFertiTrack msgRecibido;

// ─────────────────────────────────────────────────────────────────────
// CONFIGURACIÓN PERSISTENTE (NVS)
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

float distanciaTotalCm = 0.0;
float anchoZonaCm = 2.5;
const float PASOS_POR_CM_DEFECTO = 100.0; // medido: 400 daba 4x la distancia pedida
float pasosPorCm = PASOS_POR_CM_DEFECTO;  // se ajusta con la calibración guiada de la app

// Sensor de humedad RESISTIVO (ADC alto = seco). Dos puntos de referencia y
// tres porcentajes definen los umbrales (ver recalcularUmbrales()).
int adcSeco = 4095;      // ADC con el sensor en suelo seco / al aire
int adcMojado = 1200;    // ADC con el sensor en suelo saturado / agua (CALIBRAR)
int pctSeca = 35;        // humedad < 35 %       -> SECA (se riega)
int pctHumeda = 65;      // humedad >= 65 %      -> HÚMEDA   (entre 35 y 65 = INTERMEDIA)
int pctSobre = 90;       // humedad >= 90 %      -> SOBRE-HÚMEDA
// Umbrales en ADC, DERIVADOS de lo anterior (no se editan a mano):
int umbralSeco = 3082;        // ADC >= esto -> SECA
int umbralHumedo = 2213;      // ADC <= esto -> HUMEDA
int umbralSobreHumedo = 1490; // ADC <= esto -> SOBRE_HUMEDA

void recalcularUmbrales() {
  float span = (float)(adcSeco - adcMojado);
  umbralSeco = (int)lroundf(adcSeco - span * pctSeca / 100.0f);
  umbralHumedo = (int)lroundf(adcSeco - span * pctHumeda / 100.0f);
  umbralSobreHumedo = (int)lroundf(adcSeco - span * pctSobre / 100.0f);
}

// Humedad en % (0 = seco de referencia, 100 = mojado de referencia); -1 sin dato
float humedadPorcentaje(int adc) {
  float span = (float)(adcSeco - adcMojado);
  if (adc < 0 || span <= 0) return -1.0f;
  float p = 100.0f * (adcSeco - adc) / span;
  if (p < 0) p = 0;
  if (p > 100) p = 100;
  return p;
}
bool cicloAutomaticoActivo = false;
unsigned long intervaloDescansoMs = 60000UL;

const int MAX_ZONAS = 150;
long *limitesZonaPasos = nullptr;
int *clasificacionZona = nullptr;
int numZonas = 0;
long distanciaTotalPasos = 0;

void guardarConfiguracion() {
  prefs.putFloat("dist_cm", distanciaTotalCm);
  prefs.putFloat("ancho_cm", anchoZonaCm);
  prefs.putFloat("pasos_cm2", pasosPorCm); // clave nueva: ignora el 400 viejo guardado
  prefs.putInt("cal_seco", adcSeco);
  prefs.putInt("cal_mojado", adcMojado);
  prefs.putInt("pct_seca", pctSeca);
  prefs.putInt("pct_hum", pctHumeda);
  prefs.putInt("pct_sobre", pctSobre);
  prefs.putBool("auto", cicloAutomaticoActivo);
  prefs.putULong("descanso_ms", intervaloDescansoMs);
}

void cargarConfiguracion() {
  distanciaTotalCm = prefs.getFloat("dist_cm", 0.0);
  anchoZonaCm = prefs.getFloat("ancho_cm", 2.5);
  pasosPorCm = prefs.getFloat("pasos_cm2", PASOS_POR_CM_DEFECTO);
  adcSeco = prefs.getInt("cal_seco", 4095);
  adcMojado = prefs.getInt("cal_mojado", 1200);
  pctSeca = prefs.getInt("pct_seca", 35);
  pctHumeda = prefs.getInt("pct_hum", 65);
  pctSobre = prefs.getInt("pct_sobre", 90);
  recalcularUmbrales();
  cicloAutomaticoActivo = prefs.getBool("auto", false);
  intervaloDescansoMs = prefs.getULong("descanso_ms", 60000UL);
}

bool recalcularZonas() {
  if (distanciaTotalCm <= 0 || anchoZonaCm <= 0 || pasosPorCm <= 0) return false;
  int nz = (int)round(distanciaTotalCm / anchoZonaCm);
  if (nz < 1) nz = 1;
  if (nz > MAX_ZONAS) nz = MAX_ZONAS;

  delete[] limitesZonaPasos; limitesZonaPasos = nullptr;
  delete[] clasificacionZona; clasificacionZona = nullptr;

  numZonas = nz;
  distanciaTotalPasos = (long)round((double)distanciaTotalCm * pasosPorCm);
  limitesZonaPasos = new long[numZonas + 1];
  clasificacionZona = new int[numZonas];

  for (int i = 0; i <= numZonas; i++)
    limitesZonaPasos[i] = (long)round(((double)distanciaTotalPasos * i) / numZonas);
  for (int i = 0; i < numZonas; i++) clasificacionZona[i] = 0;

  Serial.printf("[CONFIG] %.1f cm -> %d zonas (~%.2f cm) %ld pasos\n",
                distanciaTotalCm, numZonas, distanciaTotalCm / numZonas, distanciaTotalPasos);
  return true;
}

long pasosDeZona(int idx) {
  if (!limitesZonaPasos || idx < 0 || idx >= numZonas) return 0;
  return limitesZonaPasos[idx + 1] - limitesZonaPasos[idx];
}

// Pasos de 'n' zonas consecutivas empezando en 'desde' (para tramos de riego)
long pasosTramo(int desde, int n) {
  if (!limitesZonaPasos || desde < 0 || n < 1 || desde + n > numZonas) return 0;
  return limitesZonaPasos[desde + n] - limitesZonaPasos[desde];
}

// Cuántas zonas consecutivas desde 'desde' comparten la misma acción de riego
int tramoMismaAccion(int desde, bool riega) {
  int n = 0;
  while (desde + n < numZonas && (clasificacionZona[desde + n] == 1) == riega) n++;
  return n;
}

// ─────────────────────────────────────────────────────────────────────
// VARIABLES DE LA MÁQUINA DE ESTADOS
// ─────────────────────────────────────────────────────────────────────
EstadoMaestro estadoActual = EM_ESPERANDO;
ModoRutina modoRutina = RUTINA_NINGUNA;
volatile NodoEsperado nodoEsperado = NODO_NINGUNO;
volatile bool respuestaLista = false;
volatile int respuestaValor = 0;

unsigned long tiempoInicioEspera = 0;
unsigned long limiteEsperaMs = TIMEOUT_A_MS;
unsigned long ultimoRefrescoIdle = 0;
unsigned long inicioRecuperacion = 0;
unsigned long tiempoArranque = 0;
float pruebaPedidoCm = 0;          // distancia de la última prueba de movimiento (0 = ya usada)
int primeraSeca = -1;              // rango de zonas secas del riego en curso
int ultimaSeca = -1;

int zonaActual = 0;
int zonasEnTramo = 1;              // zonas que cubre el movimiento de riego en curso
long posicionPasos = 0;            // cota superior de la posición de ESP_A (0 = origen)
bool encadenarRiegoTrasSensado = false;
bool brazoRiegoAbajo = false;
int ultimaHumedadADC = -1;
int nivelSueloActual = 0;
int actividadActual = 0;
bool huboFalla = false;
int codigoFalla = 0;
bool esperandoProximoCiclo = false;
unsigned long proximoCicloMillis = 0;

bool lecturaBotonCruda = HIGH;    // última lectura del pin (sin filtrar)
bool botonEstable = HIGH;         // estado ya filtrado (antirrebote)
unsigned long ultimoCambioBoton = 0;

uint8_t stopsPendientes = 0;      // repeticiones de STOP aún por enviar
unsigned long ultimoStopRepetido = 0;
uint8_t resetsPendientes = 0;     // repeticiones de RESET aún por enviar
unsigned long ultimoResetRepetido = 0;
int indicadorActual = 0;          // último código INDICADOR enviado (para refresco)

WebServer server(80);

// ─────────────────────────────────────────────────────────────────────
// HELPERS DE NOMBRES / CLASIFICACIÓN
// ─────────────────────────────────────────────────────────────────────
const char *nombreEstado(EstadoMaestro e) {
  switch (e) {
    case EM_ESPERANDO: return "ESPERANDO";
    case EM_SENSADO_AVANZANDO: return "SENSADO_AVANZANDO";
    case EM_SENSADO_MIDIENDO: return "SENSADO_MIDIENDO";
    case EM_SENSADO_RETORNANDO: return "SENSADO_RETORNANDO";
    case EM_RIEGO_BAJANDO_BRAZO: return "RIEGO_BAJANDO_BRAZO";
    case EM_RIEGO_AVANZANDO: return "RIEGO_AVANZANDO";
    case EM_RIEGO_SUBIENDO_BRAZO: return "RIEGO_SUBIENDO_BRAZO";
    case EM_RIEGO_RETORNANDO: return "RIEGO_RETORNANDO";
    case EM_FERTI_BAJANDO_BRAZO: return "FERTI_BAJANDO_BRAZO";
    case EM_FERTI_AVANZANDO: return "FERTI_AVANZANDO";
    case EM_FERTI_SUBIENDO_BRAZO: return "FERTI_SUBIENDO_BRAZO";
    case EM_FERTI_RETORNANDO: return "FERTI_RETORNANDO";
    case EM_RECUP_ESPERA: return "RECUP_ESPERA";
    case EM_RECUP_BRAZO_RIEGO: return "RECUP_BRAZO_RIEGO";
    case EM_RECUP_BRAZO_FERTI: return "RECUP_BRAZO_FERTI";
    case EM_RECUP_RETORNANDO: return "RECUP_RETORNANDO";
    case EM_ARRANQUE: return "ARRANQUE";
    case EM_PRUEBA_AVANZANDO: return "PRUEBA_AVANZANDO";
    case EM_PRUEBA_LISTA: return "PRUEBA_LISTA";
    case EM_PRUEBA_RETORNANDO: return "PRUEBA_RETORNANDO";
    case EM_PRUEBA_SENSOR: return "PRUEBA_SENSOR";
    case EM_EMERGENCIA: return "EMERGENCIA";
    default: return "?";
  }
}

const char *nombreRutina(ModoRutina r) {
  switch (r) {
    case RUTINA_NINGUNA: return "NINGUNA";
    case RUTINA_SENSADO: return "SENSADO";
    case RUTINA_RIEGO: return "RIEGO";
    case RUTINA_FERTILIZACION: return "FERTILIZACION";
    default: return "?";
  }
}

// Niveles: 1 SECA (ADC >= umbralSeco) | 2 INTERMEDIA (entre umbralHumedo y
// umbralSeco) | 3 HÚMEDA (<= umbralHumedo) | 4 SOBRE-HÚMEDA (<= umbralSobreHumedo)
int clasificarHumedad(int adc) {
  if (adc < 0) return 0;
  if (adc <= umbralSobreHumedo) return 4;
  if (adc <= umbralHumedo) return 3;
  if (adc < umbralSeco) return 2;
  return 1;
}

bool hayZonaSeca() {
  for (int i = 0; i < numZonas; i++) if (clasificacionZona[i] == 1) return true;
  return false;
}

// ─────────────────────────────────────────────────────────────────────
// ENVÍO ESP-NOW
// ─────────────────────────────────────────────────────────────────────
esp_err_t enviarA(uint8_t *mac, const char *cmd, int param) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor, "MAESTRO", sizeof(msgEnviar.emisor) - 1);
  strncpy(msgEnviar.comando, cmd, sizeof(msgEnviar.comando) - 1);
  msgEnviar.parametro = param;
  esp_err_t r = esp_now_send(mac, (uint8_t *)&msgEnviar, sizeof(msgEnviar));
  Serial.printf("[TX] %s(%d) -> %02X:%02X:%02X:%02X:%02X:%02X %s\n",
                cmd, param, mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
                r == ESP_OK ? "OK" : "ERR");
  return r;
}

// Orden crítica (movimiento, brazo, STOP, RESET, MEDIR): hasta 3 intentos
void enviarCritico(uint8_t *mac, const char *cmd, int param) {
  for (int i = 0; i < 3; i++) {
    if (enviarA(mac, cmd, param) == ESP_OK) return;
    delay(3);
  }
  Serial.printf("[TX] !!! %s no pudo encolarse tras 3 intentos\n", cmd);
}

// ─────────────────────────────────────────────────────────────────────
// COLA DE NOTIFICACIONES (Pantalla / LEDs): un aviso cada 20 ms
// ─────────────────────────────────────────────────────────────────────
const int COLA_MAX = 24;
Notificacion cola[COLA_MAX];
int colaIni = 0;
int colaCuenta = 0;
unsigned long ultimoEnvioCola = 0;

void encolar(uint8_t destino, const char *cmd, int param) {
  if (colaCuenta == COLA_MAX) {            // llena: descarta el aviso más viejo
    colaIni = (colaIni + 1) % COLA_MAX;
    colaCuenta--;
  }
  int pos = (colaIni + colaCuenta) % COLA_MAX;
  cola[pos].destino = destino;
  strncpy(cola[pos].cmd, cmd, sizeof(cola[pos].cmd) - 1);
  cola[pos].cmd[sizeof(cola[pos].cmd) - 1] = '\0';
  cola[pos].param = param;
  colaCuenta++;
}

void vaciarCola() {
  colaIni = 0;
  colaCuenta = 0;
}

// A quién van los INDICADOR: ESP_C si existe, si no ESP_B, si no a nadie
uint8_t *macIndicador() {
  if (ESP_C_CONECTADA) return MAC_ESP_C;
  if (ESP_B_CONECTADA) return MAC_ESP_B;
  return nullptr;
}

void procesarCola() {
  if (colaCuenta == 0) return;
  if (millis() - ultimoEnvioCola < SEPARACION_COLA_MS) return;
  Notificacion n = cola[colaIni];
  colaIni = (colaIni + 1) % COLA_MAX;
  colaCuenta--;
  ultimoEnvioCola = millis();
  if (n.destino == 0) {
    enviarA(MAC_PANTALLA, n.cmd, n.param);
  } else {
    uint8_t *m = macIndicador();
    if (m != nullptr) enviarA(m, n.cmd, n.param);
  }
}

void notificarPantallaEstado(int n) { nivelSueloActual = n; encolar(0, "ESTADO_SUELO", n); }
void notificarPantallaActividad(int n) { actividadActual = n; encolar(0, "ACTIVIDAD", n); }
void notificarPantallaFalla(int n) { encolar(0, "FALLA", n); }
void notificarPantallaRutina(ModoRutina r) { encolar(0, "MODO_RUTINA", (int)r); }
void notificarPantallaZona(int z) { encolar(0, "ZONA_ACTUAL", z); }
void notificarPantallaTotalZonas(int t) { encolar(0, "TOTAL_ZONAS", t); }
// Qué hace el robot: 0 ninguna, 1 avanzando, 2 regresando al origen,
// 3 bajando brazo, 4 subiendo brazo (la Pantalla actual ignora este comando)
void notificarPantallaFase(int f) { encolar(0, "FASE_MOV", f); }

// Códigos: 0 apagado, 1 SECO, 2 INTERMEDIA, 3 HUMEDA, 4 SOBRE_HUMEDA, 9 FERTILIZANDO
void notificarIndicadorLED(int codigo) { indicadorActual = codigo; encolar(1, "INDICADOR", codigo); }

// ─────────────────────────────────────────────────────────────────────
// ESPERA DE RESPUESTAS Y ÓRDENES A ESCLAVOS
// ─────────────────────────────────────────────────────────────────────
// Tiempo máximo razonable para mover 'pasos' a velocidad 'vel':
// tramo + rampa (trapecio), +50 % de holgura, +3 s de comunicación.
unsigned long timeoutMovimientoMs(long pasos, float vel) {
  float seg = (float)labs(pasos) / vel + vel / ACEL;
  return (unsigned long)(seg * 1000.0f * 1.5f) + 3000UL;
}

// ms == 0 -> límite por defecto según el nodo
void iniciarEspera(NodoEsperado nodo, unsigned long ms) {
  nodoEsperado = nodo;
  respuestaLista = false;
  tiempoInicioEspera = millis();
  limiteEsperaMs = (ms != 0) ? ms : ((nodo == NODO_B) ? TIMEOUT_B_MS : TIMEOUT_A_MS);
}

// Primero arma la espera, DESPUÉS envía (así una respuesta rápida no se pierde)
void ordenarA(NodoEsperado nodo, const char *cmd, int param, unsigned long ms) {
  iniciarEspera(nodo, ms);
  enviarCritico(nodo == NODO_B ? MAC_ESP_B : MAC_ESP_A, cmd, param);
}

void ordenarAvance(const char *cmd, float vel, long pasos) {
  posicionPasos += pasos;
  ordenarA(NODO_A, cmd, (int)pasos, timeoutMovimientoMs(pasos, vel));
}

void ordenarRetorno(long posEstimada) {
  ordenarA(NODO_A, "RETORNAR", 0, timeoutMovimientoMs(posEstimada, VEL_RAPIDA));
}

// Timeout de una orden de brazo: carrera completa + 50 % + 3 s de comunicación
unsigned long timeoutBrazoMs(int accion) {
  unsigned long t = (accion != 0) ? ACT_EXTENDER_MS : ACT_RECOGER_MS;
  return t + t / 2 + 3000UL;
}

void ordenarBrazo(const char *cmd, int accion) {
  ordenarA(NODO_A, cmd, accion, timeoutBrazoMs(accion));
}

// ─────────────────────────────────────────────────────────────────────
// FALLA / REINICIO CON RECUPERACIÓN
// Códigos: 1 timeout ESP_A, 2 timeout ESP_B, 3 lectura inválida del sensor,
//          8 detener desde app, 9 botón físico
// ─────────────────────────────────────────────────────────────────────
void enviarStopATodos() {
  enviarCritico(MAC_ESP_A, "STOP", 0);
  if (ESP_B_CONECTADA) enviarCritico(MAC_ESP_B, "STOP", 0);
  if (ESP_C_CONECTADA) enviarCritico(MAC_ESP_C, "STOP", 0);
}

// Repite el STOP sin bloquear (llamar en cada vuelta del loop)
void procesarStopsRepetidos() {
  if (stopsPendientes == 0) return;
  if (estadoActual != EM_EMERGENCIA) { stopsPendientes = 0; return; }
  if (millis() - ultimoStopRepetido < SEPARACION_STOP_MS) return;
  ultimoStopRepetido = millis();
  stopsPendientes--;
  enviarStopATodos();
}

void enviarResetATodos() {
  enviarCritico(MAC_ESP_A, "RESET", 0);
  if (ESP_B_CONECTADA) enviarCritico(MAC_ESP_B, "RESET", 0);
  if (ESP_C_CONECTADA) enviarCritico(MAC_ESP_C, "RESET", 0);
}

// Repite el RESET sin bloquear, SOLO mientras dura la pausa de recuperación.
// En cuanto la recuperación pasa a la siguiente etapa (o entra una nueva
// emergencia) se cancela: un RESET tardío abortaría un brazo en movimiento.
void procesarResetsRepetidos() {
  if (resetsPendientes == 0) return;
  if (estadoActual != EM_RECUP_ESPERA) { resetsPendientes = 0; return; }
  if (millis() - ultimoResetRepetido < SEPARACION_RESET_MS) return;
  ultimoResetRepetido = millis();
  resetsPendientes--;
  enviarResetATodos();
}

void declararFalla(int cod) {
  digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
  digitalWrite(PIN_RELE_FERTI, RELE_OFF);
  enviarStopATodos();                      // 1er envío inmediato
  stopsPendientes = STOP_REPETICIONES_EXTRA; // el resto sale desde el loop
  ultimoStopRepetido = millis();
  resetsPendientes = 0;                    // una emergencia nueva cancela cualquier RESET pendiente

  huboFalla = true;
  codigoFalla = cod;
  estadoActual = EM_EMERGENCIA;
  modoRutina = RUTINA_NINGUNA;
  nodoEsperado = NODO_NINGUNO;
  esperandoProximoCiclo = false;

  vaciarCola(); // descarta avisos viejos: lo último que ve la Pantalla es la falla
  notificarPantallaActividad(0);
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  notificarPantallaFalla(cod);
  Serial.printf("\n[M] !!! FALLA codigo %d !!!\n", cod);
}

// Recuperación física: RESET -> brazo riego arriba -> brazo ferti arriba ->
// RETORNAR al origen. La usan el botón Reiniciar y el arranque.
void iniciarRecuperacion() {
  nodoEsperado = NODO_NINGUNO;
  modoRutina = RUTINA_NINGUNA;
  esperandoProximoCiclo = false;
  stopsPendientes = 0;           // un STOP tardío no debe pisar al RESET

  enviarResetATodos();                         // 1er envío inmediato
  resetsPendientes = RESET_REPETICIONES_EXTRA; // el resto sale desde el loop
  ultimoResetRepetido = millis();

  notificarPantallaFalla(0);
  notificarPantallaActividad(4);
  notificarPantallaFase(4);
  inicioRecuperacion = millis();
  estadoActual = EM_RECUP_ESPERA;
}

// Reiniciar NO deja el sistema listo de inmediato: primero recupera el
// estado físico (brazos arriba + origen). Ver EM_RECUP_* en loop().
void reiniciarSistema() {
  if (estadoActual != EM_EMERGENCIA) return;
  huboFalla = false;
  codigoFalla = 0;
  zonaActual = 0;
  cicloAutomaticoActivo = false; // seguridad: no arrancar solo tras emergencia
  guardarConfiguracion();
  iniciarRecuperacion();
  Serial.println("[M] Reinicio: recuperando (brazos arriba y regreso al origen)...");
}

// ─────────────────────────────────────────────────────────────────────
// MODO AUTOMÁTICO
// ─────────────────────────────────────────────────────────────────────
void programarProximoCicloSiAplica() {
  if (cicloAutomaticoActivo && distanciaTotalCm > 0) {
    esperandoProximoCiclo = true;
    proximoCicloMillis = millis() + intervaloDescansoMs;
    Serial.printf("[AUTO] Proximo ciclo en %lu s\n", intervaloDescansoMs / 1000);
  } else {
    esperandoProximoCiclo = false;
  }
}

// Fin común de cualquier rutina: robot en origen, todo en reposo
void terminarRutina() {
  posicionPasos = 0;
  brazoRiegoAbajo = false;
  modoRutina = RUTINA_NINGUNA;
  estadoActual = EM_ESPERANDO;
  notificarPantallaActividad(0);
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  programarProximoCicloSiAplica();
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 1 — SENSADO (para en cada zona)
// ─────────────────────────────────────────────────────────────────────
bool iniciarSensado(bool encadenarRiego) {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || numZonas < 1) return false;
  encadenarRiegoTrasSensado = encadenarRiego;
  zonaActual = 0;
  modoRutina = RUTINA_SENSADO;
  estadoActual = EM_SENSADO_AVANZANDO;

  ordenarAvance("AVANZAR_MEDIO", VEL_MEDIA, pasosDeZona(0)); // la orden crítica, primero

  notificarPantallaRutina(RUTINA_SENSADO);
  notificarPantallaTotalZonas(numZonas);
  notificarPantallaZona(1);
  notificarPantallaActividad(4);
  notificarPantallaFase(1);
  notificarIndicadorLED(0);
  Serial.printf("[SENSADO] Iniciado (%d zonas)\n", numZonas);
  return true;
}

void avanzarZonaOTerminarSensado() {
  zonaActual++;
  if (zonaActual >= numZonas) {
    estadoActual = EM_SENSADO_RETORNANDO;
    ordenarRetorno(posicionPasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(2);
  } else {
    estadoActual = EM_SENSADO_AVANZANDO;
    ordenarAvance("AVANZAR_MEDIO", VEL_MEDIA, pasosDeZona(zonaActual));
    notificarPantallaZona(zonaActual + 1);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  }
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 2 — RIEGO SELECTIVO (por tramos de zonas con la misma acción)
// ─────────────────────────────────────────────────────────────────────
// El brazo solo trabaja entre la PRIMERA y la ÚLTIMA zona seca.
void calcularRangoSeco() {
  primeraSeca = -1;
  ultimaSeca = -1;
  for (int i = 0; i < numZonas; i++) {
    if (clasificacionZona[i] == 1) {
      if (primeraSeca < 0) primeraSeca = i;
      ultimaSeca = i;
    }
  }
}

// Manda UN movimiento que cubre todas las zonas consecutivas con la
// misma acción, empezando en zonaActual.
//  riega = true : zonas secas -> bomba ON, velocidad LENTA
//  riega = false: zonas no secas -> bomba OFF; rápido con el brazo arriba,
//                 velocidad media si el brazo va abajo (cruza entre secas)
void avanzarTramoRiego(bool riega) {
  zonasEnTramo = tramoMismaAccion(zonaActual, riega);
  if (zonasEnTramo < 1) zonasEnTramo = 1;
  long pasos = pasosTramo(zonaActual, zonasEnTramo);
  estadoActual = EM_RIEGO_AVANZANDO;
  // La bomba SOLO funciona sobre zonas secas y con el brazo abajo
  digitalWrite(PIN_RELE_RIEGO, (riega && brazoRiegoAbajo) ? RELE_ON : RELE_OFF);
  if (riega) {
    ordenarAvance("AVANZAR_LENTO", VEL_LENTA, pasos);
    notificarPantallaActividad(2);
    notificarPantallaFase(0);
  } else if (brazoRiegoAbajo) {
    ordenarAvance("AVANZAR_MEDIO", VEL_MEDIA, pasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  } else {
    ordenarAvance("AVANZAR_RAPIDO", VEL_RAPIDA, pasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  }
  Serial.printf("[RIEGO] Tramo zonas %d-%d (%s, %ld pasos)\n",
                zonaActual + 1, zonaActual + zonasEnTramo,
                riega ? "regar lento" : "saltar", pasos);
}

// Ya se regaron todas las zonas secas: recoger el brazo (si está abajo) y
// volver al origen. No se recorren las zonas restantes.
void terminarRiegoYRetornar() {
  if (brazoRiegoAbajo) {
    digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
    estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
    ordenarBrazo("BRAZO_RIEGO", 0);
    notificarPantallaActividad(4);
    notificarPantallaFase(4);
    Serial.println("[RIEGO] Ultima zona seca lista. Recogiendo brazo...");
  } else {
    estadoActual = EM_RIEGO_RETORNANDO;
    ordenarRetorno(posicionPasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(2);
    Serial.println("[RIEGO] Brazo arriba. Regresando al origen...");
  }
}

void procesarZonaRiego() {
  if (zonaActual > ultimaSeca) { // pasó la última zona seca (o terminó el riel)
    terminarRiegoYRetornar();
    return;
  }
  notificarPantallaZona(zonaActual + 1);
  bool necesitaRiego = (clasificacionZona[zonaActual] == 1);

  if (!brazoRiegoAbajo) {
    if (necesitaRiego) {                // llegó a una zona seca: extender el brazo
      estadoActual = EM_RIEGO_BAJANDO_BRAZO;
      ordenarBrazo("BRAZO_RIEGO", 1);
      notificarPantallaActividad(4);
      notificarPantallaFase(3);
      Serial.printf("[RIEGO] Zona %d seca. Extendiendo brazo...\n", zonaActual + 1);
    } else {
      avanzarTramoRiego(false);         // brazo arriba: salto rápido
    }
  } else {
    if (!necesitaRiego && !MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS) {
      digitalWrite(PIN_RELE_RIEGO, RELE_OFF);   // modo anterior: subir entre tramos
      estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
      ordenarBrazo("BRAZO_RIEGO", 0);
      notificarPantallaActividad(4);
      notificarPantallaFase(4);
    } else {
      avanzarTramoRiego(necesitaRiego);  // brazo abajo: riega o cruza una zona no seca
    }
  }
}

bool iniciarRiegoSelectivo() {
  if (numZonas < 1) return false;
  calcularRangoSeco();
  if (primeraSeca < 0) return false;    // nada que regar
  zonaActual = 0;
  modoRutina = RUTINA_RIEGO;
  brazoRiegoAbajo = false;
  procesarZonaRiego();                  // la orden crítica sale primero
  notificarPantallaRutina(RUTINA_RIEGO);
  notificarPantallaTotalZonas(numZonas);
  notificarIndicadorLED(0); // LEDs apagados durante todo el riego
  Serial.printf("[RIEGO] Iniciando riego selectivo (zonas secas %d a %d)...\n",
                primeraSeca + 1, ultimaSeca + 1);
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 3 — FERTILIZACIÓN (tramo único A->B a velocidad media)
// ─────────────────────────────────────────────────────────────────────
bool iniciarFertilizacion() {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || distanciaTotalPasos <= 0) return false;
  modoRutina = RUTINA_FERTILIZACION;
  estadoActual = EM_FERTI_BAJANDO_BRAZO;
  ordenarBrazo("BRAZO_FERTI", 1);

  notificarPantallaRutina(RUTINA_FERTILIZACION);
  notificarPantallaActividad(3);
  notificarPantallaFase(3);
  notificarIndicadorLED(9); // rojo fijo mientras dure
  Serial.println("[FERTI] Iniciando fertilizacion...");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// PRUEBAS DE CALIBRACIÓN (no son rutinas del ciclo)
// ─────────────────────────────────────────────────────────────────────
// Mueve 'cm' hacia adelante y se queda ahí para que midas cuánto avanzó.
bool iniciarPruebaMovimiento(float cm) {
  if (estadoActual != EM_ESPERANDO) return false;
  long pasos = (long)round((double)cm * pasosPorCm);
  if (pasos < 1) return false;
  pruebaPedidoCm = cm;
  modoRutina = RUTINA_NINGUNA;
  estadoActual = EM_PRUEBA_AVANZANDO;
  ordenarAvance("AVANZAR_MEDIO", VEL_MEDIA, pasos);
  notificarPantallaActividad(4);
  notificarPantallaFase(1);
  Serial.printf("[PRUEBA] Moviendo %.1f cm = %ld pasos (con %.2f pasos/cm)\n", cm, pasos, pasosPorCm);
  return true;
}

bool iniciarPruebaRetorno() {
  if (estadoActual != EM_PRUEBA_LISTA) return false;
  estadoActual = EM_PRUEBA_RETORNANDO;
  ordenarRetorno(posicionPasos);
  notificarPantallaActividad(4);
  notificarPantallaFase(2);
  Serial.println("[PRUEBA] Regresando al origen...");
  return true;
}

// Una medición suelta del sensor (baja el pistón, promedia, sube): sin mover
// el robot y sin clasificar. Sirve para obtener los puntos SECO y MOJADO.
bool iniciarPruebaSensor() {
  if (estadoActual != EM_ESPERANDO || !ESP_B_CONECTADA) return false;
  modoRutina = RUTINA_NINGUNA;
  estadoActual = EM_PRUEBA_SENSOR;
  ordenarA(NODO_B, "MEDIR", 0, 0);
  notificarPantallaActividad(1);
  notificarPantallaFase(0);
  Serial.println("[PRUEBA] Midiendo sensor...");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW (firma obligatoria Core 3.x)
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));
  String emisor = String(msgRecibido.emisor);
  String comando = String(msgRecibido.comando);
  Serial.printf("[RX] de %s: %s (%d)\n", emisor.c_str(), comando.c_str(), msgRecibido.parametro);

  if (nodoEsperado == NODO_A && emisor == "ESPA" &&
      (comando == "LISTO" || comando == "BRAZO_OK")) {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado = NODO_NINGUNO;
  } else if (ESP_B_CONECTADA && nodoEsperado == NODO_B &&
             emisor == "ESPB" && comando == "HUMEDAD") {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado = NODO_NINGUNO;
  }
}

// ─────────────────────────────────────────────────────────────────────
// REFRESCO PERIÓDICO DEL ESTADO A PANTALLA / ESP_C
// ─────────────────────────────────────────────────────────────────────
void refrescarEstadoExterno() {
  notificarPantallaActividad(0);                 // en reposo y en emergencia = 0
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  if (numZonas > 0) notificarPantallaTotalZonas(numZonas);
  notificarPantallaEstado(nivelSueloActual);
  if (estadoActual == EM_EMERGENCIA) {
    if (huboFalla) notificarPantallaFalla(codigoFalla); // siempre lo último
    // Reenvía el STOP (idempotente): ESP_C/ESP_B que se hayan reiniciado durante
    // la emergencia vuelven a quedar en modo STOP, y cubre un STOP no recibido.
    enviarStopATodos();
  } else {
    // En reposo: FALLA 0 por si el aviso "sin falla" se perdió al reiniciar
    // (la Pantalla solo repinta si el valor cambia).
    notificarPantallaFalla(0);
    // Solo en reposo: en emergencia ESP_C está en modo STOP y no debe recibir INDICADOR
    notificarIndicadorLED(indicadorActual);
  }
}

// ─────────────────────────────────────────────────────────────────────
// BOTÓN DE EMERGENCIA (sin delay)
// ─────────────────────────────────────────────────────────────────────
// Antirrebote: 'botonEstable' solo cambia cuando la lectura cruda lleva
// DEBOUNCE_MS sin variar. Se actúa en el flanco HIGH -> LOW estable.
void revisarBotonEmergencia() {
  bool lectura = digitalRead(PIN_BOTON_EMERGENCIA);
  if (lectura != lecturaBotonCruda) {
    lecturaBotonCruda = lectura;
    ultimoCambioBoton = millis();
  }
  if (lecturaBotonCruda != botonEstable &&
      (millis() - ultimoCambioBoton) > DEBOUNCE_MS) {
    botonEstable = lecturaBotonCruda;
    if (botonEstable == LOW && estadoActual != EM_EMERGENCIA) {
      Serial.println("[M] BOTON EMERGENCIA");
      declararFalla(9);
    }
  }
}

// ─────────────────────────────────────────────────────────────────────
// PÁGINA WEB (autocontenida: el SoftAP no tiene internet, nada de CDNs)
// ─────────────────────────────────────────────────────────────────────
const char PAGINA_PRINCIPAL[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FertiTrack</title>
<style>
body{margin:0;font-family:Arial,Helvetica,sans-serif;background:#F5F5DC;color:#222;padding-bottom:30px;}
header{background:#4CAF50;color:#fff;padding:16px;text-align:center;font-size:22px;font-weight:bold;}
.tarjeta{background:#fff;margin:16px;padding:16px;border-radius:10px;box-shadow:0 2px 6px rgba(0,0,0,0.15);}
.fila{display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #eee;font-size:14px;}
h3{margin:0 0 10px 0;font-size:15px;color:#4CAF50;}
input[type=number]{width:100px;padding:6px;border:1px solid #ccc;border-radius:6px;font-size:14px;}
button{width:100%;padding:14px;margin:6px 0;font-size:15px;border:none;border-radius:8px;color:#fff;font-weight:bold;}
.btn-ciclo{background:#2196F3;}
.btn-sensado{background:#03A9F4;}
.btn-fumigar{background:#FF9800;}
.btn-detener{background:#F44336;}
.btn-reiniciar{background:#4CAF50;}
.btn-guardar{background:#607D8B;}
.btn-prueba{background:#9C27B0;}
.nota{font-size:12px;color:#555;margin:6px 0;line-height:1.4;}
input[type=number].corto{width:70px;}
.aviso-falla{background:#F44336;color:#fff;padding:14px;border-radius:10px;margin:16px;text-align:center;font-weight:bold;display:none;}
.mapa-zonas{display:flex;gap:3px;flex-wrap:wrap;margin-top:10px;}
.zona{flex:1 1 auto;min-width:16px;height:36px;border-radius:4px;display:flex;align-items:center;justify-content:center;font-size:10px;color:#fff;font-weight:bold;}
.z0{background:#bbb;} .z1{background:#F44336;} .z2{background:#FFC107;color:#333;} .z3{background:#4CAF50;} .z4{background:#2196F3;}
label{font-size:14px;}
</style>
</head>
<body>
<header>FertiTrack — Monorriel por Zonas</header>

<div id="avisoFalla" class="aviso-falla"></div>

<div class="tarjeta">
  <h3>Estado</h3>
  <div class="fila"><span>Estado</span><span id="estado">--</span></div>
  <div class="fila"><span>Rutina activa</span><span id="rutina">--</span></div>
  <div class="fila"><span>Zona</span><span id="zona">--</span></div>
  <div class="fila"><span>Humedad (ADC)</span><span id="humedad">--</span></div>
  <div class="fila"><span>Humedad (%)</span><span id="humedadPct">--</span></div>
  <div class="fila"><span>Suelo (última zona)</span><span id="suelo">--</span></div>
  <div class="fila"><span>Próximo ciclo automático</span><span id="proximo">--</span></div>
  <div class="mapa-zonas" id="mapaZonas"></div>
</div>

<div class="tarjeta">
  <h3>Configuración del recorrido</h3>
  <div class="fila">
    <span>Distancia A&rarr;B (cm)</span>
    <input type="number" id="distanciaCm" step="0.1" min="1" placeholder="ej. 200">
  </div>
  <div class="fila">
    <span>Ancho de zona (cm)</span>
    <input type="number" id="anchoZonaCm" step="0.1" min="0.5" placeholder="2.5">
  </div>
  <button class="btn-guardar" onclick="guardarConfig()">Guardar configuración</button>
</div>

<div class="tarjeta">
  <h3>Modo automático (Sensado + Riego en bucle)</h3>
  <div class="fila">
    <span><input type="checkbox" id="autoActivo"> Repetir ciclo automáticamente</span>
  </div>
  <div class="fila">
    <span>Descanso entre ciclos (seg)</span>
    <input type="number" id="descansoSeg" min="5" placeholder="60">
  </div>
  <button class="btn-guardar" onclick="guardarAuto()">Guardar modo automático</button>
</div>

<div class="tarjeta">
  <h3>Calibrar distancia (pasos por cm)</h3>
  <div class="nota">Pasos por cm actual: <b id="pasosCm">--</b>. 1) Pon el robot en el origen y toca "Mover prueba". 2) Mide con una cinta cuánto avanzó de verdad. 3) Escríbelo y toca "Calibrar". 4) "Volver al origen" y repite la prueba para confirmar.</div>
  <div class="fila"><span>Distancia de prueba (cm)</span><input type="number" class="corto" id="pruebaCm" step="0.5" min="1" value="10"></div>
  <button class="btn-prueba" onclick="pruebaMov()">Mover prueba</button>
  <div class="fila"><span>Distancia REAL medida (cm)</span><input type="number" class="corto" id="realCm" step="0.1" min="0.1"></div>
  <button class="btn-guardar" onclick="calibrarDist()">Calibrar con esa medida</button>
  <button class="btn-reiniciar" onclick="pruebaRetorno()">Volver al origen (tras la prueba)</button>
</div>

<div class="tarjeta">
  <h3>Calibrar sensor de humedad</h3>
  <div class="nota">1) Deja el sensor en suelo SECO (o al aire), toca "Medir sensor ahora" y luego "Guardar como SECO". 2) Pon el sensor en suelo saturado o agua, mide de nuevo y toca "Guardar como MOJADO". La humedad en % se calcula entre esos dos puntos.</div>
  <button class="btn-prueba" onclick="pruebaSensor()">Medir sensor ahora</button>
  <button class="btn-guardar" onclick="calSensor('seco')">Guardar última lectura como SECO</button>
  <button class="btn-guardar" onclick="calSensor('mojado')">Guardar última lectura como MOJADO</button>
  <div class="fila"><span>SECA si humedad &lt; (%)</span><input type="number" class="corto" id="pctSeca" min="1" max="98"></div>
  <div class="fila"><span>HÚMEDA desde (%)</span><input type="number" class="corto" id="pctHumeda" min="2" max="99"></div>
  <div class="fila"><span>SOBRE-HÚMEDA desde (%)</span><input type="number" class="corto" id="pctSobre" min="3" max="99"></div>
  <button class="btn-guardar" onclick="guardarPct()">Guardar porcentajes</button>
  <div class="nota" id="rangos">--</div>
</div>

<div class="tarjeta">
  <button class="btn-ciclo" onclick="iniciar('ciclo')">Iniciar Ciclo Completo (Sensar + Regar)</button>
  <button class="btn-sensado" onclick="iniciar('sensado')">Solo Sensar (prueba, sin regar)</button>
  <button class="btn-fumigar" onclick="iniciar('fertilizar')">Iniciar Fertilización</button>
  <button class="btn-detener" onclick="detener()">DETENER (Emergencia)</button>
  <button class="btn-reiniciar" onclick="reiniciar()">Reiniciar (recupera brazos y vuelve al origen)</button>
</div>

<script>
function iniciar(modo){ fetch('/api/iniciar?modo='+modo).then(r=>r.json()).then(mostrarResultado).catch(()=>{}); }
function detener(){ fetch('/api/detener').catch(()=>{}); }
function reiniciar(){ fetch('/api/reiniciar').catch(()=>{}); }
function mostrarResultado(d){ if(d && d.ok===false) alert('Error: '+d.error); }
function llamar(url){
  fetch(url).then(r=>r.json()).then(d=>{
    if (d && d.ok===false) alert('Error: '+d.error);
    else if (d && d.mensaje) alert(d.mensaje);
  }).catch(()=>{});
}
function pruebaMov(){ llamar('/api/prueba_mov?cm='+(document.getElementById('pruebaCm').value||10)); }
function pruebaRetorno(){ llamar('/api/prueba_retorno'); }
function calibrarDist(){
  const r = document.getElementById('realCm').value;
  if (!r || r<=0){ alert('Escribe la distancia REAL que midió en cm'); return; }
  llamar('/api/calibrar_distancia?real_cm='+r);
}
function pruebaSensor(){ llamar('/api/prueba_sensor'); }
function calSensor(p){ llamar('/api/cal_sensor?punto='+p); }
function guardarPct(){
  const a=document.getElementById('pctSeca').value, b=document.getElementById('pctHumeda').value, c=document.getElementById('pctSobre').value;
  if(!a||!b||!c){ alert('Completa los tres porcentajes'); return; }
  llamar('/api/cal_sensor?pct_seca='+a+'&pct_humeda='+b+'&pct_sobre='+c);
}

function guardarConfig(){
  const d = document.getElementById('distanciaCm').value;
  const a = document.getElementById('anchoZonaCm').value;
  if (!d || d<=0){ alert('Ingresa la distancia total en cm'); return; }
  let url = '/api/configurar?distancia_cm='+d;
  if (a) url += '&ancho_zona_cm='+a;
  fetch(url).then(r=>r.json()).then(mostrarResultado).catch(()=>{});
}

function guardarAuto(){
  const activo = document.getElementById('autoActivo').checked ? 1 : 0;
  const seg = document.getElementById('descansoSeg').value || 60;
  fetch('/api/auto?activar='+activo+'&descanso_seg='+seg).then(r=>r.json()).then(mostrarResultado).catch(()=>{});
}

const nombresSuelo = ['Sin datos','Seca','Intermedia','Húmeda','Sobre-húmeda'];

function refrescar(){
  fetch('/api/estado').then(r=>r.json()).then(d=>{
    document.getElementById('estado').innerText = d.estado;
    document.getElementById('rutina').innerText = d.modo_rutina;
    document.getElementById('zona').innerText = (d.total_zonas>0) ? (d.zona_actual+' / '+d.total_zonas) : '--';
    document.getElementById('humedad').innerText = (d.humedad_adc >= 0) ? d.humedad_adc : '--';
    document.getElementById('humedadPct').innerText = (d.humedad_pct >= 0) ? d.humedad_pct.toFixed(0)+' %' : '--';
    document.getElementById('pasosCm').innerText = d.pasos_por_cm.toFixed(2);
    document.getElementById('rangos').innerHTML =
      'SECA (se riega): ADC &ge; '+d.umbral_seco+' (humedad &lt; '+d.pct_seca+' %)<br>'+
      'INTERMEDIA: ADC entre '+d.umbral_humedo+' y '+d.umbral_seco+' ('+d.pct_seca+'-'+d.pct_humeda+' %)<br>'+
      'HÚMEDA: ADC entre '+d.umbral_sobre+' y '+d.umbral_humedo+' ('+d.pct_humeda+'-'+d.pct_sobre+' %)<br>'+
      'SOBRE-HÚMEDA: ADC &le; '+d.umbral_sobre+' (&ge; '+d.pct_sobre+' %)<br>'+
      'Puntos de referencia: seco = '+d.cal_seco+', mojado = '+d.cal_mojado;
    ['pctSeca','pctHumeda','pctSobre'].forEach(function(id){
      const el = document.getElementById(id);
      if (!el.dataset.tocado) el.value = d[{pctSeca:'pct_seca',pctHumeda:'pct_humeda',pctSobre:'pct_sobre'}[id]];
    });
    document.getElementById('suelo').innerText = nombresSuelo[d.nivel_suelo] || '--';
    document.getElementById('proximo').innerText = (d.proximo_ciclo_seg >= 0) ? (d.proximo_ciclo_seg+' s') : '--';

    const aviso = document.getElementById('avisoFalla');
    if (d.falla > 0) {
      aviso.style.display = 'block';
      aviso.innerText = 'FALLA (código ' + d.falla + ') — presiona Reiniciar';
    } else {
      aviso.style.display = 'none';
    }

    const mapa = document.getElementById('mapaZonas');
    mapa.innerHTML = '';
    if (d.zonas) {
      d.zonas.forEach((niv, i) => {
        const div = document.createElement('div');
        div.className = 'zona z' + niv;
        div.innerText = (i + 1);
        mapa.appendChild(div);
      });
    }

    const campoDistancia = document.getElementById('distanciaCm');
    if (!campoDistancia.dataset.tocado) {
      campoDistancia.value = d.distancia_cm || '';
      document.getElementById('anchoZonaCm').value = d.ancho_zona_cm ? d.ancho_zona_cm.toFixed(2) : '';
    }
    document.getElementById('autoActivo').checked = d.auto_activo;
    document.getElementById('descansoSeg').value = d.descanso_seg;
  }).catch(()=>{});
}
document.getElementById('distanciaCm').addEventListener('input', function(){ this.dataset.tocado = '1'; });
['pctSeca','pctHumeda','pctSobre'].forEach(function(id){
  document.getElementById(id).addEventListener('input', function(){ this.dataset.tocado = '1'; });
});
setInterval(refrescar, 2000);
refrescar();
</script>
</body>
</html>
)rawliteral";

// ─────────────────────────────────────────────────────────────────────
// RUTAS DEL SERVIDOR WEB (WebServer del core: handleClient() en loop())
// ─────────────────────────────────────────────────────────────────────
void ruta_raiz() {
  server.send_P(200, "text/html", PAGINA_PRINCIPAL);
}

void ruta_configurar() {
  if (estadoActual != EM_ESPERANDO) {
    server.send(409, "application/json", "{\"ok\":false,\"error\":\"sistema ocupado\"}");
    return;
  }
  if (!server.hasArg("distancia_cm")) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"falta distancia_cm\"}");
    return;
  }
  float nd = server.arg("distancia_cm").toFloat();
  if (nd <= 0) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"distancia_cm debe ser > 0\"}");
    return;
  }
  distanciaTotalCm = nd;
  if (server.hasArg("ancho_zona_cm")) {
    float az = server.arg("ancho_zona_cm").toFloat();
    if (az > 0) anchoZonaCm = az;
  }
  // (los umbrales de humedad ya no se editan aquí: ver /api/cal_sensor)
  if (server.hasArg("pasos_cm")) {
    float pc = server.arg("pasos_cm").toFloat();
    if (pc > 0) pasosPorCm = pc;
  }
  if (!recalcularZonas()) {
    server.send(500, "application/json", "{\"ok\":false,\"error\":\"no se pudo calcular zonas\"}");
    return;
  }
  guardarConfiguracion();
  JsonDocument doc;
  doc["ok"] = true;
  doc["num_zonas"] = numZonas;
  doc["ancho_zona_real_cm"] = distanciaTotalCm / numZonas;
  doc["distancia_total_pasos"] = distanciaTotalPasos;
  String out;
  serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_auto() {
  if (server.hasArg("activar")) cicloAutomaticoActivo = server.arg("activar").toInt() != 0;
  if (server.hasArg("descanso_seg")) {
    long sg = server.arg("descanso_seg").toInt();
    if (sg > 0) intervaloDescansoMs = (unsigned long)sg * 1000UL;
  }
  guardarConfiguracion();
  if (cicloAutomaticoActivo && estadoActual == EM_ESPERANDO && !esperandoProximoCiclo)
    programarProximoCicloSiAplica();
  if (!cicloAutomaticoActivo) esperandoProximoCiclo = false;

  JsonDocument doc;
  doc["ok"] = true;
  doc["auto_activo"] = cicloAutomaticoActivo;
  doc["descanso_seg"] = intervaloDescansoMs / 1000;
  String out;
  serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_iniciar() {
  String modo = server.hasArg("modo") ? server.arg("modo") : "";
  bool ok = false;
  String error = "";

  if (estadoActual != EM_ESPERANDO) {
    error = "sistema ocupado";
  } else if (distanciaTotalCm <= 0 || numZonas < 1) {
    error = "configura primero la distancia con /api/configurar";
  } else if (modo == "ciclo") {
    ok = iniciarSensado(true);
  } else if (modo == "sensado") {
    ok = iniciarSensado(false);
  } else if (modo == "fertilizar") {
    ok = iniciarFertilizacion();
  } else {
    error = "modo invalido (usa ciclo, sensado o fertilizar)";
  }

  if (ok) {
    server.send(200, "application/json", "{\"ok\":true}");
  } else {
    if (error.length() == 0) error = "no se pudo iniciar";
    server.send(409, "application/json", "{\"ok\":false,\"error\":\"" + error + "\"}");
  }
}

// ── Calibración de distancia y sensor ───────────────────────────────
void responderJson(int codigo, bool ok, const char *clave, const String &texto) {
  JsonDocument doc;
  doc["ok"] = ok;
  doc[clave] = texto;
  String out;
  serializeJson(doc, out);
  server.send(codigo, "application/json", out);
}

void ruta_prueba_mov() {
  float cm = server.hasArg("cm") ? server.arg("cm").toFloat() : 10.0f;
  if (cm < 1 || cm > 150) { responderJson(400, false, "error", "cm debe estar entre 1 y 150"); return; }
  if (!iniciarPruebaMovimiento(cm)) { responderJson(409, false, "error", "sistema ocupado"); return; }
  responderJson(200, true, "mensaje", "Moviendo " + String(cm, 1) + " cm. Cuando se detenga, mide cuanto avanzo de verdad.");
}

void ruta_calibrar_distancia() {
  if (estadoActual != EM_PRUEBA_LISTA && estadoActual != EM_ESPERANDO) {
    responderJson(409, false, "error", "sistema ocupado"); return;
  }
  float real = server.hasArg("real_cm") ? server.arg("real_cm").toFloat() : 0;
  float pedido = server.hasArg("pedido_cm") ? server.arg("pedido_cm").toFloat() : pruebaPedidoCm;
  if (real <= 0) { responderJson(400, false, "error", "falta real_cm (distancia medida)"); return; }
  if (pedido <= 0) { responderJson(400, false, "error", "primero haz 'Mover prueba' (o indica pedido_cm)"); return; }
  float nuevo = pasosPorCm * pedido / real;
  if (nuevo < 1.0f || nuevo > 5000.0f) { responderJson(400, false, "error", "valor fuera de rango: revisa la medida"); return; }
  float antes = pasosPorCm;
  pasosPorCm = nuevo;
  pruebaPedidoCm = 0; // la prueba ya se usó: evita corregir dos veces con la misma medida
  if (distanciaTotalCm > 0) recalcularZonas();
  guardarConfiguracion();
  Serial.printf("[CALIB] pasos/cm %.3f -> %.3f (pedido %.1f cm, real %.1f cm)\n", antes, nuevo, pedido, real);
  responderJson(200, true, "mensaje", "Nueva calibracion: " + String(nuevo, 2) + " pasos/cm (antes " + String(antes, 2) +
                ").\nToca 'Volver al origen' y repite la prueba para confirmar.");
}

void ruta_prueba_retorno() {
  if (!iniciarPruebaRetorno()) { responderJson(409, false, "error", "solo despues de una prueba de movimiento"); return; }
  responderJson(200, true, "mensaje", "Regresando al origen...");
}

void ruta_prueba_sensor() {
  if (!ESP_B_CONECTADA) { responderJson(409, false, "error", "ESP_B no esta conectada"); return; }
  if (!iniciarPruebaSensor()) { responderJson(409, false, "error", "sistema ocupado"); return; }
  responderJson(200, true, "mensaje", "Midiendo (~8 s). Luego veras el ADC en 'Estado'.");
}

void ruta_cal_sensor() {
  if (estadoActual != EM_ESPERANDO) { responderJson(409, false, "error", "sistema ocupado"); return; }
  int nS = adcSeco, nM = adcMojado, pS = pctSeca, pH = pctHumeda, pO = pctSobre;
  if (server.hasArg("punto")) {
    String p = server.arg("punto");
    int adc = server.hasArg("adc") ? server.arg("adc").toInt() : ultimaHumedadADC;
    if (adc < ADC_MIN_VALIDO || adc > 4095) {
      responderJson(400, false, "error", "no hay lectura valida: toca 'Medir sensor ahora' primero"); return;
    }
    if (p == "seco") nS = adc;
    else if (p == "mojado") nM = adc;
    else { responderJson(400, false, "error", "punto debe ser seco o mojado"); return; }
  }
  if (server.hasArg("pct_seca")) pS = server.arg("pct_seca").toInt();
  if (server.hasArg("pct_humeda")) pH = server.arg("pct_humeda").toInt();
  if (server.hasArg("pct_sobre")) pO = server.arg("pct_sobre").toInt();
  if (nS - nM < 300) {
    responderJson(400, false, "error", "SECO debe ser al menos 300 mayor que MOJADO (ADC alto = seco). Revisa las lecturas."); return;
  }
  if (!(pS > 0 && pS < pH && pH < pO && pO < 100)) {
    responderJson(400, false, "error", "los porcentajes deben cumplir 0 < seca < humeda < sobre < 100"); return;
  }
  adcSeco = nS; adcMojado = nM; pctSeca = pS; pctHumeda = pH; pctSobre = pO;
  recalcularUmbrales();
  guardarConfiguracion();
  Serial.printf("[CALIB] seco=%d mojado=%d | umbrales ADC: seca>=%d humeda<=%d sobre<=%d\n",
                adcSeco, adcMojado, umbralSeco, umbralHumedo, umbralSobreHumedo);
  responderJson(200, true, "mensaje", "Guardado. Seca: ADC >= " + String(umbralSeco) + " | Intermedia: entre " +
                String(umbralHumedo) + " y " + String(umbralSeco) + " | Humeda: <= " + String(umbralHumedo) +
                " | Sobre-humeda: <= " + String(umbralSobreHumedo));
}

void ruta_detener() {
  declararFalla(8);
  server.send(200, "application/json", "{\"ok\":true}");
}

void ruta_reiniciar() {
  reiniciarSistema();
  server.send(200, "application/json", "{\"ok\":true}");
}

void ruta_estado() {
  JsonDocument doc;
  doc["estado"] = nombreEstado(estadoActual);
  doc["modo_rutina"] = nombreRutina(modoRutina);
  doc["zona_actual"] = (numZonas > 0) ? (zonaActual + 1) : 0;
  doc["total_zonas"] = numZonas;
  doc["distancia_cm"] = distanciaTotalCm;
  doc["ancho_zona_cm"] = (numZonas > 0) ? (distanciaTotalCm / numZonas) : anchoZonaCm;
  doc["pasos_por_cm"] = pasosPorCm;
  doc["posicion_pasos"] = posicionPasos;
  doc["humedad_adc"] = ultimaHumedadADC;
  doc["humedad_pct"] = humedadPorcentaje(ultimaHumedadADC);
  doc["cal_seco"] = adcSeco;
  doc["cal_mojado"] = adcMojado;
  doc["pct_seca"] = pctSeca;
  doc["pct_humeda"] = pctHumeda;
  doc["pct_sobre"] = pctSobre;
  doc["umbral_seco"] = umbralSeco;
  doc["umbral_humedo"] = umbralHumedo;
  doc["umbral_sobre"] = umbralSobreHumedo;
  doc["nivel_suelo"] = nivelSueloActual;
  doc["actividad"] = actividadActual;
  doc["falla"] = huboFalla ? codigoFalla : 0;
  doc["esp_b_conectada"] = ESP_B_CONECTADA;
  doc["esp_c_conectada"] = ESP_C_CONECTADA;
  doc["auto_activo"] = cicloAutomaticoActivo;
  doc["descanso_seg"] = intervaloDescansoMs / 1000;
  if (esperandoProximoCiclo) {
    long restanteMs = (long)(proximoCicloMillis - millis());
    doc["proximo_ciclo_seg"] = (restanteMs > 0) ? (restanteMs / 1000) : 0;
  } else {
    doc["proximo_ciclo_seg"] = -1;
  }
  JsonArray zonas = doc["zonas"].to<JsonArray>();
  for (int i = 0; i < numZonas; i++) zonas.add(clasificacionZona[i]);
  String out;
  serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_no_encontrada() {
  server.send(404, "application/json", "{\"error\":\"ruta no encontrada\"}");
}

void configurarServidor() {
  server.on("/", HTTP_GET, ruta_raiz);
  server.on("/api/configurar", HTTP_GET, ruta_configurar);
  server.on("/api/auto", HTTP_GET, ruta_auto);
  server.on("/api/iniciar", HTTP_GET, ruta_iniciar);
  server.on("/api/detener", HTTP_GET, ruta_detener);
  server.on("/api/reiniciar", HTTP_GET, ruta_reiniciar);
  server.on("/api/estado", HTTP_GET, ruta_estado);
  server.on("/api/prueba_mov", HTTP_GET, ruta_prueba_mov);
  server.on("/api/calibrar_distancia", HTTP_GET, ruta_calibrar_distancia);
  server.on("/api/prueba_retorno", HTTP_GET, ruta_prueba_retorno);
  server.on("/api/prueba_sensor", HTTP_GET, ruta_prueba_sensor);
  server.on("/api/cal_sensor", HTTP_GET, ruta_cal_sensor);
  server.onNotFound(ruta_no_encontrada);
  server.begin();
}

// ─────────────────────────────────────────────────────────────────────
// SETUP
// ─────────────────────────────────────────────────────────────────────
void registrarPeer(uint8_t *mac, const char *nombre) {
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, mac, 6);
  peer.channel = CANAL_WIFI;
  peer.encrypt = false;
  if (esp_now_add_peer(&peer) == ESP_OK) Serial.printf(" OK peer %s\n", nombre);
  else Serial.printf(" ERROR registrando peer %s\n", nombre);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.3] Iniciando MAESTRO...");

  pinMode(PIN_RELE_RIEGO, OUTPUT);
  digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
  pinMode(PIN_RELE_FERTI, OUTPUT);
  digitalWrite(PIN_RELE_FERTI, RELE_OFF);
  pinMode(PIN_BOTON_EMERGENCIA, INPUT_PULLUP);
  lecturaBotonCruda = digitalRead(PIN_BOTON_EMERGENCIA);
  botonEstable = lecturaBotonCruda;   // si arranca ya presionado, no dispara solo
  ultimoCambioBoton = millis();

  prefs.begin("ferti", false);
  cargarConfiguracion();
  if (distanciaTotalCm > 0) {
    recalcularZonas();
    Serial.println("Configuracion anterior restaurada.");
  } else {
    Serial.println("Sin distancia configurada. Usa la app antes de iniciar un ciclo.");
  }
  Serial.printf(" Calibracion: %.2f pasos/cm | sensor seco=%d mojado=%d | umbrales ADC seca>=%d humeda<=%d sobre<=%d\n",
                pasosPorCm, adcSeco, adcMojado, umbralSeco, umbralHumedo, umbralSobreHumedo);
  Serial.printf(" Actuadores: extender %lu ms, recoger %lu ms (ESP_A debe usar los mismos)\n",
                ACT_EXTENDER_MS, ACT_RECOGER_MS);

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID, AP_PASS, CANAL_WIFI);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print(" AP creado. IP: "); Serial.println(WiFi.softAPIP());
  Serial.print(" MAC Maestro: "); Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println(" ERROR: ESP-NOW no inicializo");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  registrarPeer(MAC_ESP_A, "ESP_A");
  registrarPeer(MAC_PANTALLA, "Pantalla");
  if (ESP_B_CONECTADA) registrarPeer(MAC_ESP_B, "ESP_B");
  else Serial.println(" ESP_B no conectada: sensado sin datos");
  if (ESP_C_CONECTADA) registrarPeer(MAC_ESP_C, "ESP_C");

  configurarServidor();
  Serial.println(" Servidor web en http://192.168.4.1");
  Serial.println("\n[MAESTRO v5.3] LISTO");
  Serial.println(" WiFi: FertiTrack_AP / 123456789\n");

  // Brazos arriba al arrancar: se espera ESPERA_ARRANQUE_MS (loop) y se ejecuta
  // la misma recuperación del botón Reiniciar.
  if (RECOGER_BRAZOS_AL_ARRANCAR) {
    tiempoArranque = millis();
    estadoActual = EM_ARRANQUE;
    Serial.println(" Arranque: en 3 s se recogeran los brazos y se regresara al origen.");
  }
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  server.handleClient();
  revisarBotonEmergencia();
  procesarStopsRepetidos();
  procesarResetsRepetidos();
  procesarCola();

  // Timeout de comunicación con esclavos (límite dinámico por orden)
  NodoEsperado ne = nodoEsperado;
  if (ne != NODO_NINGUNO && !respuestaLista &&
      millis() - tiempoInicioEspera > limiteEsperaMs) {
    Serial.printf("[M] Timeout (%lu ms) esperando Nodo %s\n",
                  limiteEsperaMs, ne == NODO_B ? "B" : "A");
    declararFalla(ne == NODO_B ? 2 : 1);
    return;
  }

  // Refresco periódico: Pantalla y ESP_C se ponen al día solos tras un reinicio
  if ((estadoActual == EM_ESPERANDO || estadoActual == EM_EMERGENCIA) &&
      millis() - ultimoRefrescoIdle > REFRESCO_IDLE_MS) {
    ultimoRefrescoIdle = millis();
    refrescarEstadoExterno();
  }

  if (estadoActual == EM_ESPERANDO && esperandoProximoCiclo && millis() >= proximoCicloMillis) {
    esperandoProximoCiclo = false;
    iniciarSensado(true);
  }

  switch (estadoActual) {

    // ── RUTINA 1: SENSADO ─────────────────────────────────────────
    case EM_SENSADO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual = EM_SENSADO_MIDIENDO;
      notificarPantallaActividad(1);
      if (ESP_B_CONECTADA) {
        ordenarA(NODO_B, "MEDIR", 0, 0);
      } else {
        clasificacionZona[zonaActual] = 0;
        notificarPantallaEstado(0);
        avanzarZonaOTerminarSensado();
      }
      break;

    case EM_SENSADO_MIDIENDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      ultimaHumedadADC = respuestaValor;
      if (ultimaHumedadADC < ADC_MIN_VALIDO || ultimaHumedadADC > ADC_MAX_VALIDO) {
        Serial.printf("[SENSADO] Zona %d/%d ADC=%d INVALIDO (sensor desconectado/en corto)\n",
                      zonaActual + 1, numZonas, ultimaHumedadADC);
        declararFalla(3);
        break;
      }
      {
        int nivel = clasificarHumedad(ultimaHumedadADC);
        clasificacionZona[zonaActual] = nivel;
        notificarPantallaEstado(nivel);
        notificarIndicadorLED(nivel);
        Serial.printf("[SENSADO] Zona %d/%d ADC=%d nivel=%d\n",
                      zonaActual + 1, numZonas, ultimaHumedadADC, nivel);
        avanzarZonaOTerminarSensado();
      }
      break;

    case EM_SENSADO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      posicionPasos = 0;
      Serial.println("[SENSADO] Completo. Robot en origen.");
      if (encadenarRiegoTrasSensado && hayZonaSeca()) {
        iniciarRiegoSelectivo();
      } else {
        terminarRutina();
      }
      break;

    // ── RUTINA 2: RIEGO SELECTIVO ─────────────────────────────────
    case EM_RIEGO_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      brazoRiegoAbajo = true;           // brazo extendido y confirmado por ESP_A
      avanzarTramoRiego(true);          // enciende la bomba y empieza a regar
      break;

    case EM_RIEGO_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      brazoRiegoAbajo = false;
      procesarZonaRiego();              // sigue el recorrido, o regresa al origen si ya terminó
      break;

    case EM_RIEGO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      zonaActual += zonasEnTramo;
      procesarZonaRiego();              // decide: otro tramo, recoger brazo o volver
      break;

    case EM_RIEGO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[RIEGO] Completo. Robot en origen.");
      terminarRutina();
      break;

    // ── RUTINA 3: FERTILIZACIÓN ───────────────────────────────────
    case EM_FERTI_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_ON);
      estadoActual = EM_FERTI_AVANZANDO;
      ordenarAvance("AVANZAR_MEDIO", VEL_MEDIA, distanciaTotalPasos);
      notificarPantallaActividad(3);
      notificarPantallaFase(1);
      break;

    case EM_FERTI_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_OFF);
      estadoActual = EM_FERTI_SUBIENDO_BRAZO;
      ordenarBrazo("BRAZO_FERTI", 0);
      notificarPantallaFase(4);
      break;

    case EM_FERTI_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual = EM_FERTI_RETORNANDO;
      ordenarRetorno(posicionPasos);
      notificarPantallaActividad(4);
      notificarPantallaFase(2);
      break;

    case EM_FERTI_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[FERTI] Completo. Robot en origen.");
      notificarIndicadorLED(0); // apaga el rojo
      terminarRutina();
      break;

    // ── RECUPERACIÓN TRAS EMERGENCIA ──────────────────────────────
    case EM_RECUP_ESPERA:
      if (millis() - inicioRecuperacion < ESPERA_RECUP_MS) break;
      estadoActual = EM_RECUP_BRAZO_RIEGO;
      ordenarBrazo("BRAZO_RIEGO", 0);
      break;

    case EM_RECUP_BRAZO_RIEGO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual = EM_RECUP_BRAZO_FERTI;
      ordenarBrazo("BRAZO_FERTI", 0);
      break;

    case EM_RECUP_BRAZO_FERTI:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual = EM_RECUP_RETORNANDO;
      notificarPantallaFase(2);
      {
        // posicionPasos es una cota superior de dónde quedó el robot
        long estimada = (posicionPasos > distanciaTotalPasos) ? posicionPasos : distanciaTotalPasos;
        ordenarRetorno(estimada);
      }
      break;

    case EM_RECUP_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[M] Recuperacion completa: brazos arriba, robot en origen.");
      notificarIndicadorLED(0);
      terminarRutina(); // posicion=0, estado ESPERANDO, programa ciclo si aplica (auto está off)
      break;

    // ── ARRANQUE: espera y luego recupera brazos / origen ─────────
    case EM_ARRANQUE:
      if (millis() - tiempoArranque < ESPERA_ARRANQUE_MS) break;
      Serial.println("[ARRANQUE] Recogiendo brazos y regresando al origen...");
      iniciarRecuperacion();
      break;

    // ── PRUEBAS DE CALIBRACIÓN ────────────────────────────────────
    case EM_PRUEBA_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual = EM_PRUEBA_LISTA;
      notificarPantallaActividad(0);
      notificarPantallaFase(0);
      Serial.println("[PRUEBA] Listo. Mide la distancia recorrida y calibra desde la app.");
      break;

    case EM_PRUEBA_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[PRUEBA] En el origen.");
      terminarRutina();
      break;

    case EM_PRUEBA_SENSOR:
      if (!respuestaLista) break;
      respuestaLista = false;
      ultimaHumedadADC = respuestaValor;
      Serial.printf("[PRUEBA] Sensor: ADC=%d (%.0f %%)\n", ultimaHumedadADC,
                    humedadPorcentaje(ultimaHumedadADC));
      estadoActual = EM_ESPERANDO;
      notificarPantallaActividad(0);
      break;

    case EM_ESPERANDO:
    case EM_PRUEBA_LISTA:
    case EM_EMERGENCIA:
      break;
  }
}
