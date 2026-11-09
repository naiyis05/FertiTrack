/*
  =========================================================================
  FertiTrack_Maestro_v5.5.ino — MAESTRO (arquitectura por zonas, monorriel)
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 : 20:50:0D:10:1E:E0
  =========================================================================

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.5 (control total desde la app)
  -------------------------------------------------------------------

  1) VELOCIDADES AJUSTABLES DESDE LA APP (tarjeta "Velocidades y Potencia").
     velRapida / velMedia / velLenta / acel dejan de ser constexpr y pasan a
     ser variables float guardadas en NVS (claves vel_r, vel_m, vel_l, acel).
     Al cambiarlas, el Maestro las sincroniza con ESP_A vía comandos nuevos:
       "VEL_R" / "VEL_M" / "VEL_L" / "ACEL"  parametro = valor en pasos/s (o s²)
     La app muestra la equivalencia en cm/s con los pasos/cm actuales.
     >>> ESP_A v5.4 ignora estos comandos con un aviso (compatibilidad OK).
     >>> ESP_A v5.5 los aplicará. Mientras tanto, ESP_A usa sus constantes
     >>> propias, así que sigue funcionando. Para sincronía real actualizar ESP_A.

  2) MODO FUERZA. Un checkbox en la app activa modoFuerza: todos los movimientos
     de avance usan "AVANZAR_LENTO" (vel. lenta) sin importar la rutina. Así se
     exprime al máximo el par de los NEMA 17 actuales mientras llegan los nuevos.
     El timeout se ajusta automáticamente (cálculo dinámico ya existente).
     Se guarda en NVS (clave modo_f).

  3) TIEMPOS DE ACTUADORES AJUSTABLES DESDE LA APP (tarjeta "Calibrar Actuadores").
     actExtenderMedidoMs / actRecogerMedidoMs / actMargenMs dejan de ser const
     y se guardan en NVS (claves act_ext, act_rec, act_mrg).
     Al cambiarlos se recalculan actExtenderMs / actRecogerMs y se sincronizan
     con ESP_A vía comandos:
       "ACT_EXT" / "ACT_REC"  parametro = tiempo total (ms, int)
     >>> ESP_A v5.4 los ignora (compatibilidad OK). ESP_A v5.5 los aplicará.
     Incluye botones para extender/recoger cada brazo manualmente desde la app
     (igual que "Mover prueba" para la distancia): nuevo estado EM_PRUEBA_BRAZO.

  4) DIÁMETRO DE RUEDA DESDE LA APP. Campo diametroRuedaCm en NVS (clave rueda_d).
     Al guardarlo el Maestro calcula el nuevo PASOS_POR_CM_DEFECTO (PI × d),
     lo muestra en la app y ofrece aplicarlo como pasosPorCm activo.

  5) SINCRONIZACIÓN AUTOMÁTICA AL ARRANCAR. En setup(), tras registrar los peers,
     el Maestro envía VEL_R/VEL_M/VEL_L/ACEL + ACT_EXT/ACT_REC a ESP_A con los
     valores guardados en NVS. ESP_A v5.5 los aplicará; v5.4 los ignora.

  -------------------------------------------------------------------
  PROTOCOLO COMPLETO v5.5 (lo marcado con [NUEVO] es addición; sin romper nada)
  -------------------------------------------------------------------
  Maestro -> ESP_A : AVANZAR_RAPIDO/MEDIO/LENTO (parametro = pasos)
                     RETORNAR (0)
                     BRAZO_RIEGO / BRAZO_FERTI (1 baja, 0 sube)
                     STOP, RESET
                     VEL_R / VEL_M / VEL_L (pasos/s)         [NUEVO v5.5]
                     ACEL (pasos/s²)                          [NUEVO v5.5]
                     ACT_EXT / ACT_REC (ms totales)           [NUEVO v5.5]
  ESP_A -> Maestro : LISTO (posición), BRAZO_OK (1/0)
  Maestro -> ESP_B : MEDIR, STOP, RESET
  ESP_B -> Maestro : HUMEDAD (ADC)
  Maestro -> ESP_C : INDICADOR (0-4,9), STOP, RESET
  Maestro -> Pantalla: ESTADO_SUELO, ACTIVIDAD, MODO_RUTINA, ZONA_ACTUAL,
                       TOTAL_ZONAS, FALLA, FASE_MOV

  COMPATIBILIDAD:
   - ESP_A v5.4: ignora VEL_R/VEL_M/VEL_L/ACEL/ACT_EXT/ACT_REC → funciona igual
   - ESP_B v5.3, ESP_C v5.1, Pantalla v5.2: sin cambios → funcionan igual
   - Estructura MensajeFertiTrack: SIN CAMBIOS (emisor[10], comando[20], parametro int)

  MACs confirmadas:
   MAESTRO : 20:50:0D:10:1E:E0  (este nodo)
   ESP_A   : C8:85:41:2C:A5:BC
   ESP_B   : CC:DB:A7:34:A0:FC
   ESP_C   : 20:50:0D:4D:5F:AC
   PANTALLA: AC:A7:04:EA:09:74

  LIBRERÍAS: ArduinoJson v7.x, Preferences, WiFi, WebServer, esp_now, esp_wifi
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
// TIPOS
// ─────────────────────────────────────────────────────────────────────
enum EstadoMaestro {
  EM_ESPERANDO,
  EM_SENSADO_AVANZANDO, EM_SENSADO_MIDIENDO, EM_SENSADO_RETORNANDO,
  EM_RIEGO_BAJANDO_BRAZO, EM_RIEGO_AVANZANDO,
  EM_RIEGO_SUBIENDO_BRAZO, EM_RIEGO_RETORNANDO,
  EM_FERTI_BAJANDO_BRAZO, EM_FERTI_AVANZANDO,
  EM_FERTI_SUBIENDO_BRAZO, EM_FERTI_RETORNANDO,
  EM_RECUP_ESPERA, EM_RECUP_BRAZO_RIEGO, EM_RECUP_BRAZO_FERTI, EM_RECUP_RETORNANDO,
  EM_ARRANQUE,
  EM_PRUEBA_AVANZANDO, EM_PRUEBA_LISTA, EM_PRUEBA_RETORNANDO,
  EM_PRUEBA_SENSOR,
  EM_PRUEBA_BRAZO,   // NUEVO v5.5: extensión/retracción manual de brazo
  EM_EMERGENCIA
};

enum ModoRutina   { RUTINA_NINGUNA, RUTINA_SENSADO, RUTINA_RIEGO, RUTINA_FERTILIZACION };
enum NodoEsperado { NODO_NINGUNO, NODO_A, NODO_B };

struct Notificacion { uint8_t destino; char cmd[20]; int param; };

typedef struct {
  char emisor[10];
  char comando[20];
  int  parametro;
} MensajeFertiTrack;

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const char *AP_SSID   = "FertiTrack_AP";
const char *AP_PASS   = "123456789";
const int   CANAL_WIFI = 1;

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_RELE_RIEGO       15
#define PIN_RELE_FERTI        4
#define PIN_BOTON_EMERGENCIA  5
#define RELE_ON  LOW
#define RELE_OFF HIGH

// ─────────────────────────────────────────────────────────────────────
// MACs
// ─────────────────────────────────────────────────────────────────────
uint8_t MAC_ESP_A[]    = {0xC8,0x85,0x41,0x2C,0xA5,0xBC};
uint8_t MAC_PANTALLA[] = {0xAC,0xA7,0x04,0xEA,0x09,0x74};
const bool ESP_B_CONECTADA = true;
uint8_t MAC_ESP_B[] = {0xCC,0xDB,0xA7,0x34,0xA0,0xFC};
const bool ESP_C_CONECTADA = true;
uint8_t MAC_ESP_C[] = {0x20,0x50,0x0D,0x4D,0x5F,0xAC};

// ─────────────────────────────────────────────────────────────────────
// VELOCIDADES Y MODO FUERZA (v5.5: variables en NVS, ajustables desde app)
// Espejo de ESP_A — deben ser iguales. Se sincronizan con VEL_R/M/L/ACEL.
// ─────────────────────────────────────────────────────────────────────
float velRapida  = 300.0f;   // pasos/s — retorno al origen
float velMedia   = 200.0f;   // pasos/s — sensado, fertilización
float velLenta   = 100.0f;   // pasos/s — riego sobre zona seca
float acel       = 200.0f;   // pasos/s²
bool  modoFuerza = false;    // true → todos los avances usan velLenta (máximo par)

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS FIJOS
// ─────────────────────────────────────────────────────────────────────
const unsigned long TIMEOUT_A_MS        = 8000;
const unsigned long TIMEOUT_B_MS        = 12000;
const unsigned long REFRESCO_IDLE_MS    = 5000;
const unsigned long ESPERA_RECUP_MS     = 500;
const unsigned long SEPARACION_COLA_MS  = 20;
const unsigned long DEBOUNCE_MS         = 60;
const unsigned long SEPARACION_STOP_MS  = 30;
const uint8_t       STOP_REPETICIONES_EXTRA  = 2;
const unsigned long SEPARACION_RESET_MS = 120;
const uint8_t       RESET_REPETICIONES_EXTRA = 2;

static_assert((unsigned long)RESET_REPETICIONES_EXTRA * SEPARACION_RESET_MS + 150UL < ESPERA_RECUP_MS,
              "Los RESET repetidos no caben dentro de ESPERA_RECUP_MS");

const int ADC_MIN_VALIDO = 1;
const int ADC_MAX_VALIDO = 4095;

const bool MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS = true;
const bool RECOGER_BRAZOS_AL_ARRANCAR        = true;
const unsigned long ESPERA_ARRANQUE_MS        = 3000;

// ─────────────────────────────────────────────────────────────────────
// ACTUADORES LINEALES (v5.5: ajustables desde app, se sincronizan con ESP_A)
// ─────────────────────────────────────────────────────────────────────
unsigned long actExtenderMedidoMs = 4710;   // tiempo MEDIDO (cronómetro)
unsigned long actRecogerMedidoMs  = 4820;
unsigned long actMargenMs         = 300;    // margen para asegurar el tope
unsigned long actExtenderMs       = 5010;   // = medido + margen (recalculado)
unsigned long actRecogerMs        = 5120;

void recalcularTiemposActuadores() {
  actExtenderMs = actExtenderMedidoMs + actMargenMs;
  actRecogerMs  = actRecogerMedidoMs  + actMargenMs;
}

// ─────────────────────────────────────────────────────────────────────
// MECÁNICA Y CALIBRACIÓN
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

float distanciaTotalCm = 0.0;
float anchoZonaCm      = 2.5;

// Paso completo: 200 pasos/vuelta.
// Diámetro de rueda ajustable desde app → CM_POR_REV = PI * d
constexpr float PASOS_POR_REV = 200.0f;
float diametroRuedaCm         = 10.19f;  // ≈ 32 cm / PI (supuesto inicial)
float pasosPorCm              = 6.25f;   // ajustado con calibración guiada

float calcularPasosPorCmDesdeRueda() {
  if (diametroRuedaCm <= 0) return 6.25f;
  return PASOS_POR_REV / (M_PI * diametroRuedaCm);
}

// Sensor de humedad resistivo
int adcSeco   = 4095;
int adcMojado = 1200;
int pctSeca   = 35;
int pctHumeda = 65;
int pctSobre  = 90;
int umbralSeco = 3082, umbralHumedo = 2213, umbralSobreHumedo = 1490;

void recalcularUmbrales() {
  float span = (float)(adcSeco - adcMojado);
  umbralSeco        = (int)lroundf(adcSeco - span * pctSeca   / 100.0f);
  umbralHumedo      = (int)lroundf(adcSeco - span * pctHumeda / 100.0f);
  umbralSobreHumedo = (int)lroundf(adcSeco - span * pctSobre  / 100.0f);
}

float humedadPorcentaje(int adc) {
  float span = (float)(adcSeco - adcMojado);
  if (adc < 0 || span <= 0) return -1.0f;
  float p = 100.0f * (adcSeco - adc) / span;
  return (p < 0) ? 0 : (p > 100 ? 100 : p);
}

bool  cicloAutomaticoActivo = false;
unsigned long intervaloDescansoMs = 60000UL;

const int MAX_ZONAS = 150;
long *limitesZonaPasos   = nullptr;
int  *clasificacionZona  = nullptr;
int   numZonas           = 0;
long  distanciaTotalPasos = 0;

// ─────────────────────────────────────────────────────────────────────
// NVS: guardar / cargar configuración completa
// ─────────────────────────────────────────────────────────────────────
void guardarConfiguracion() {
  prefs.putFloat("dist_cm",   distanciaTotalCm);
  prefs.putFloat("ancho_cm",  anchoZonaCm);
  prefs.putFloat("pasos_cm3", pasosPorCm);
  prefs.putInt("cal_seco",    adcSeco);
  prefs.putInt("cal_mojado",  adcMojado);
  prefs.putInt("pct_seca",    pctSeca);
  prefs.putInt("pct_hum",     pctHumeda);
  prefs.putInt("pct_sobre",   pctSobre);
  prefs.putBool("auto",       cicloAutomaticoActivo);
  prefs.putULong("descanso_ms", intervaloDescansoMs);
  // v5.5: velocidades y potencia
  prefs.putFloat("vel_r",   velRapida);
  prefs.putFloat("vel_m",   velMedia);
  prefs.putFloat("vel_l",   velLenta);
  prefs.putFloat("acel",    acel);
  prefs.putBool("modo_f",   modoFuerza);
  // v5.5: actuadores
  prefs.putULong("act_ext", actExtenderMedidoMs);
  prefs.putULong("act_rec", actRecogerMedidoMs);
  prefs.putULong("act_mrg", actMargenMs);
  // v5.5: rueda
  prefs.putFloat("rueda_d", diametroRuedaCm);
}

void cargarConfiguracion() {
  distanciaTotalCm      = prefs.getFloat("dist_cm",   0.0f);
  anchoZonaCm           = prefs.getFloat("ancho_cm",  2.5f);
  pasosPorCm            = prefs.getFloat("pasos_cm3", 6.25f);
  adcSeco               = prefs.getInt("cal_seco",   4095);
  adcMojado             = prefs.getInt("cal_mojado", 1200);
  pctSeca               = prefs.getInt("pct_seca",   35);
  pctHumeda             = prefs.getInt("pct_hum",    65);
  pctSobre              = prefs.getInt("pct_sobre",  90);
  recalcularUmbrales();
  cicloAutomaticoActivo = prefs.getBool("auto", false);
  intervaloDescansoMs   = prefs.getULong("descanso_ms", 60000UL);
  // v5.5
  velRapida  = prefs.getFloat("vel_r",  300.0f);
  velMedia   = prefs.getFloat("vel_m",  200.0f);
  velLenta   = prefs.getFloat("vel_l",  100.0f);
  acel       = prefs.getFloat("acel",   200.0f);
  modoFuerza = prefs.getBool("modo_f",  false);
  actExtenderMedidoMs = prefs.getULong("act_ext", 4710UL);
  actRecogerMedidoMs  = prefs.getULong("act_rec", 4820UL);
  actMargenMs         = prefs.getULong("act_mrg",  300UL);
  recalcularTiemposActuadores();
  diametroRuedaCm = prefs.getFloat("rueda_d", 10.19f);
}

// ─────────────────────────────────────────────────────────────────────
// CÁLCULO DE ZONAS
// ─────────────────────────────────────────────────────────────────────
bool recalcularZonas() {
  if (distanciaTotalCm <= 0 || anchoZonaCm <= 0 || pasosPorCm <= 0) return false;
  int nz = (int)round(distanciaTotalCm / anchoZonaCm);
  if (nz < 1) nz = 1;
  if (nz > MAX_ZONAS) nz = MAX_ZONAS;
  delete[] limitesZonaPasos;  limitesZonaPasos = nullptr;
  delete[] clasificacionZona; clasificacionZona = nullptr;
  numZonas = nz;
  distanciaTotalPasos = (long)round((double)distanciaTotalCm * pasosPorCm);
  limitesZonaPasos  = new long[numZonas + 1];
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
  return limitesZonaPasos[idx+1] - limitesZonaPasos[idx];
}
long pasosTramo(int desde, int n) {
  if (!limitesZonaPasos || desde < 0 || n < 1 || desde + n > numZonas) return 0;
  return limitesZonaPasos[desde+n] - limitesZonaPasos[desde];
}
int tramoMismaAccion(int desde, bool riega) {
  int n = 0;
  while (desde + n < numZonas && (clasificacionZona[desde+n] == 1) == riega) n++;
  return n;
}

// ─────────────────────────────────────────────────────────────────────
// VARIABLES DE LA MÁQUINA DE ESTADOS
// ─────────────────────────────────────────────────────────────────────
EstadoMaestro estadoActual    = EM_ESPERANDO;
ModoRutina    modoRutina      = RUTINA_NINGUNA;
volatile NodoEsperado nodoEsperado = NODO_NINGUNO;
volatile bool respuestaLista  = false;
volatile int  respuestaValor  = 0;
unsigned long tiempoInicioEspera = 0;
unsigned long limiteEsperaMs     = 8000;
unsigned long ultimoRefrescoIdle = 0;
unsigned long inicioRecuperacion = 0;
unsigned long tiempoArranque     = 0;
float  pruebaPedidoCm    = 0;
int    primeraSeca       = -1;
int    ultimaSeca        = -1;
int    zonaActual        = 0;
int    zonasEnTramo      = 1;
long   posicionPasos     = 0;
bool   encadenarRiegoTrasSensado = false;
bool   brazoRiegoAbajo   = false;
int    ultimaHumedadADC  = -1;
int    nivelSueloActual  = 0;
int    actividadActual   = 0;
bool   huboFalla         = false;
int    codigoFalla       = 0;
bool   esperandoProximoCiclo = false;
unsigned long proximoCicloMillis = 0;
bool   lecturaBotonCruda = HIGH;
bool   botonEstable      = HIGH;
unsigned long ultimoCambioBoton = 0;
uint8_t stopsPendientes   = 0;
unsigned long ultimoStopRepetido = 0;
uint8_t resetsPendientes  = 0;
unsigned long ultimoResetRepetido = 0;
int    indicadorActual    = 0;

MensajeFertiTrack msgEnviar;
MensajeFertiTrack msgRecibido;

WebServer server(80);

// ─────────────────────────────────────────────────────────────────────
// HELPERS DE NOMBRES
// ─────────────────────────────────────────────────────────────────────
const char *nombreEstado(EstadoMaestro e) {
  switch (e) {
    case EM_ESPERANDO:            return "ESPERANDO";
    case EM_SENSADO_AVANZANDO:    return "SENSADO_AVANZANDO";
    case EM_SENSADO_MIDIENDO:     return "SENSADO_MIDIENDO";
    case EM_SENSADO_RETORNANDO:   return "SENSADO_RETORNANDO";
    case EM_RIEGO_BAJANDO_BRAZO:  return "RIEGO_BAJANDO_BRAZO";
    case EM_RIEGO_AVANZANDO:      return "RIEGO_AVANZANDO";
    case EM_RIEGO_SUBIENDO_BRAZO: return "RIEGO_SUBIENDO_BRAZO";
    case EM_RIEGO_RETORNANDO:     return "RIEGO_RETORNANDO";
    case EM_FERTI_BAJANDO_BRAZO:  return "FERTI_BAJANDO_BRAZO";
    case EM_FERTI_AVANZANDO:      return "FERTI_AVANZANDO";
    case EM_FERTI_SUBIENDO_BRAZO: return "FERTI_SUBIENDO_BRAZO";
    case EM_FERTI_RETORNANDO:     return "FERTI_RETORNANDO";
    case EM_RECUP_ESPERA:         return "RECUP_ESPERA";
    case EM_RECUP_BRAZO_RIEGO:    return "RECUP_BRAZO_RIEGO";
    case EM_RECUP_BRAZO_FERTI:    return "RECUP_BRAZO_FERTI";
    case EM_RECUP_RETORNANDO:     return "RECUP_RETORNANDO";
    case EM_ARRANQUE:             return "ARRANQUE";
    case EM_PRUEBA_AVANZANDO:     return "PRUEBA_AVANZANDO";
    case EM_PRUEBA_LISTA:         return "PRUEBA_LISTA";
    case EM_PRUEBA_RETORNANDO:    return "PRUEBA_RETORNANDO";
    case EM_PRUEBA_SENSOR:        return "PRUEBA_SENSOR";
    case EM_PRUEBA_BRAZO:         return "PRUEBA_BRAZO";
    case EM_EMERGENCIA:           return "EMERGENCIA";
    default:                      return "?";
  }
}
const char *nombreRutina(ModoRutina r) {
  switch (r) {
    case RUTINA_NINGUNA:       return "NINGUNA";
    case RUTINA_SENSADO:       return "SENSADO";
    case RUTINA_RIEGO:         return "RIEGO";
    case RUTINA_FERTILIZACION: return "FERTILIZACION";
    default:                   return "?";
  }
}
int clasificarHumedad(int adc) {
  if (adc < 0)                    return 0;
  if (adc <= umbralSobreHumedo)   return 4;
  if (adc <= umbralHumedo)        return 3;
  if (adc <  umbralSeco)          return 2;
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
  strncpy(msgEnviar.emisor,  "MAESTRO", sizeof(msgEnviar.emisor)-1);
  strncpy(msgEnviar.comando, cmd,       sizeof(msgEnviar.comando)-1);
  msgEnviar.parametro = param;
  esp_err_t r = esp_now_send(mac, (uint8_t*)&msgEnviar, sizeof(msgEnviar));
  Serial.printf("[TX] %s(%d) -> %02X:%02X:%02X:%02X:%02X:%02X %s\n",
                cmd, param, mac[0],mac[1],mac[2],mac[3],mac[4],mac[5],
                r == ESP_OK ? "OK" : "ERR");
  return r;
}
void enviarCritico(uint8_t *mac, const char *cmd, int param) {
  for (int i = 0; i < 3; i++) {
    if (enviarA(mac, cmd, param) == ESP_OK) return;
    delay(3);
  }
  Serial.printf("[TX] !!! %s no pudo encolarse tras 3 intentos\n", cmd);
}

// ─────────────────────────────────────────────────────────────────────
// SINCRONIZACIÓN CON ESP_A (v5.5)
// ESP_A v5.4 ignora estos comandos con aviso en Serial → compatibilidad OK.
// ESP_A v5.5 los aplicará cuando se actualice.
// ─────────────────────────────────────────────────────────────────────
void sincronizarVelocidadesConEspA() {
  enviarCritico(MAC_ESP_A, "VEL_R", (int)velRapida);
  enviarCritico(MAC_ESP_A, "VEL_M", (int)velMedia);
  enviarCritico(MAC_ESP_A, "VEL_L", (int)velLenta);
  enviarCritico(MAC_ESP_A, "ACEL",  (int)acel);
  Serial.printf("[SYNC] Velocidades enviadas a ESP_A: R=%d M=%d L=%d A=%d\n",
                (int)velRapida, (int)velMedia, (int)velLenta, (int)acel);
}
void sincronizarActuadoresConEspA() {
  enviarCritico(MAC_ESP_A, "ACT_EXT", (int)actExtenderMs);
  enviarCritico(MAC_ESP_A, "ACT_REC", (int)actRecogerMs);
  Serial.printf("[SYNC] Actuadores enviados a ESP_A: EXT=%lu REC=%lu ms\n",
                actExtenderMs, actRecogerMs);
}
void sincronizarTodoConEspA() {
  sincronizarVelocidadesConEspA();
  sincronizarActuadoresConEspA();
}

// ─────────────────────────────────────────────────────────────────────
// COLA DE NOTIFICACIONES (Pantalla / LEDs)
// ─────────────────────────────────────────────────────────────────────
const int COLA_MAX = 24;
Notificacion cola[COLA_MAX];
int  colaIni    = 0;
int  colaCuenta = 0;
unsigned long ultimoEnvioCola = 0;

void encolar(uint8_t destino, const char *cmd, int param) {
  if (colaCuenta == COLA_MAX) { colaIni = (colaIni+1)%COLA_MAX; colaCuenta--; }
  int pos = (colaIni + colaCuenta) % COLA_MAX;
  cola[pos].destino = destino;
  strncpy(cola[pos].cmd, cmd, sizeof(cola[pos].cmd)-1);
  cola[pos].cmd[sizeof(cola[pos].cmd)-1] = '\0';
  cola[pos].param = param;
  colaCuenta++;
}
void vaciarCola() { colaIni = 0; colaCuenta = 0; }

uint8_t *macIndicador() {
  if (ESP_C_CONECTADA) return MAC_ESP_C;
  if (ESP_B_CONECTADA) return MAC_ESP_B;
  return nullptr;
}
void procesarCola() {
  if (colaCuenta == 0) return;
  if (millis() - ultimoEnvioCola < SEPARACION_COLA_MS) return;
  Notificacion n = cola[colaIni];
  colaIni = (colaIni+1) % COLA_MAX;
  colaCuenta--;
  ultimoEnvioCola = millis();
  if (n.destino == 0) enviarA(MAC_PANTALLA, n.cmd, n.param);
  else { uint8_t *m = macIndicador(); if (m) enviarA(m, n.cmd, n.param); }
}

void notificarPantallaEstado(int n)       { nivelSueloActual = n; encolar(0,"ESTADO_SUELO",n); }
void notificarPantallaActividad(int n)    { actividadActual  = n; encolar(0,"ACTIVIDAD",n); }
void notificarPantallaFalla(int n)        { encolar(0,"FALLA",n); }
void notificarPantallaRutina(ModoRutina r){ encolar(0,"MODO_RUTINA",(int)r); }
void notificarPantallaZona(int z)         { encolar(0,"ZONA_ACTUAL",z); }
void notificarPantallaTotalZonas(int t)   { encolar(0,"TOTAL_ZONAS",t); }
void notificarPantallaFase(int f)         { encolar(0,"FASE_MOV",f); }
void notificarIndicadorLED(int c)         { indicadorActual = c; encolar(1,"INDICADOR",c); }

// ─────────────────────────────────────────────────────────────────────
// TIMEOUT Y ÓRDENES A ESCLAVOS
// ─────────────────────────────────────────────────────────────────────
unsigned long timeoutMovimientoMs(long pasos, float vel) {
  float seg = (float)labs(pasos) / vel + vel / acel;
  return (unsigned long)(seg * 1000.0f * 1.5f) + 3000UL;
}
void iniciarEspera(NodoEsperado nodo, unsigned long ms) {
  nodoEsperado    = nodo;
  respuestaLista  = false;
  tiempoInicioEspera = millis();
  limiteEsperaMs  = (ms != 0) ? ms : ((nodo == NODO_B) ? TIMEOUT_B_MS : TIMEOUT_A_MS);
}
void ordenarA(NodoEsperado nodo, const char *cmd, int param, unsigned long ms) {
  iniciarEspera(nodo, ms);
  enviarCritico(nodo == NODO_B ? MAC_ESP_B : MAC_ESP_A, cmd, param);
}

// MODO FUERZA: si está activo, TODOS los avances usan AVANZAR_LENTO y velLenta.
// El retorno también obedece (ver ordenarRetorno). Ningún movimiento queda exento.
void ordenarAvance(const char *cmd, float vel, long pasos) {
  const char *cmdReal = modoFuerza ? "AVANZAR_LENTO" : cmd;
  float       velReal = modoFuerza ? velLenta         : vel;
  posicionPasos += pasos;
  ordenarA(NODO_A, cmdReal, (int)pasos, timeoutMovimientoMs(pasos, velReal));
}
void ordenarRetorno(long posEstimada) {
  // Con modoFuerza activo el retorno también usa velLenta: el timeout se calcula
  // con esa velocidad para no disparar FALLA 1 falsa mientras ESP_A regresa lento.
  // La velocidad física real cambia en ESP_A v5.5 (tras sincronizar VEL_R=velLenta).
  float velRet = modoFuerza ? velLenta : velRapida;
  ordenarA(NODO_A, "RETORNAR", 0, timeoutMovimientoMs(posEstimada, velRet));
}
unsigned long timeoutBrazoMs(int accion) {
  unsigned long t = (accion != 0) ? actExtenderMs : actRecogerMs;
  return t + t/2 + 3000UL;
}
void ordenarBrazo(const char *cmd, int accion) {
  ordenarA(NODO_A, cmd, accion, timeoutBrazoMs(accion));
}

// ─────────────────────────────────────────────────────────────────────
// FALLA / REINICIO
// ─────────────────────────────────────────────────────────────────────
void enviarStopATodos() {
  enviarCritico(MAC_ESP_A, "STOP", 0);
  if (ESP_B_CONECTADA) enviarCritico(MAC_ESP_B, "STOP", 0);
  if (ESP_C_CONECTADA) enviarCritico(MAC_ESP_C, "STOP", 0);
}
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
  enviarStopATodos();
  stopsPendientes      = STOP_REPETICIONES_EXTRA;
  ultimoStopRepetido   = millis();
  resetsPendientes     = 0;
  huboFalla            = true;
  codigoFalla          = cod;
  estadoActual         = EM_EMERGENCIA;
  modoRutina           = RUTINA_NINGUNA;
  nodoEsperado         = NODO_NINGUNO;
  esperandoProximoCiclo = false;
  vaciarCola();
  notificarPantallaActividad(0);
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  notificarPantallaFalla(cod);
  Serial.printf("\n[M] !!! FALLA codigo %d !!!\n", cod);
}
void iniciarRecuperacion() {
  nodoEsperado         = NODO_NINGUNO;
  modoRutina           = RUTINA_NINGUNA;
  esperandoProximoCiclo = false;
  stopsPendientes      = 0;
  enviarResetATodos();
  resetsPendientes     = RESET_REPETICIONES_EXTRA;
  ultimoResetRepetido  = millis();
  notificarPantallaFalla(0);
  notificarPantallaActividad(4);
  notificarPantallaFase(4);
  inicioRecuperacion   = millis();
  estadoActual         = EM_RECUP_ESPERA;
}
void reiniciarSistema() {
  if (estadoActual != EM_EMERGENCIA) return;
  huboFalla             = false;
  codigoFalla           = 0;
  zonaActual            = 0;
  cicloAutomaticoActivo = false;
  guardarConfiguracion();
  iniciarRecuperacion();
  Serial.println("[M] Reinicio: recuperando...");
}

// ─────────────────────────────────────────────────────────────────────
// MODO AUTOMÁTICO
// ─────────────────────────────────────────────────────────────────────
void programarProximoCicloSiAplica() {
  if (cicloAutomaticoActivo && distanciaTotalCm > 0) {
    esperandoProximoCiclo = true;
    proximoCicloMillis    = millis() + intervaloDescansoMs;
    Serial.printf("[AUTO] Proximo ciclo en %lu s\n", intervaloDescansoMs/1000);
  } else {
    esperandoProximoCiclo = false;
  }
}
void terminarRutina() {
  posicionPasos   = 0;
  brazoRiegoAbajo = false;
  modoRutina      = RUTINA_NINGUNA;
  estadoActual    = EM_ESPERANDO;
  notificarPantallaActividad(0);
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  programarProximoCicloSiAplica();
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 1: SENSADO
// ─────────────────────────────────────────────────────────────────────
bool iniciarSensado(bool encadenarRiego) {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || numZonas < 1) return false;
  encadenarRiegoTrasSensado = encadenarRiego;
  zonaActual   = 0;
  modoRutina   = RUTINA_SENSADO;
  estadoActual = EM_SENSADO_AVANZANDO;
  ordenarAvance("AVANZAR_MEDIO", velMedia, pasosDeZona(0));
  notificarPantallaRutina(RUTINA_SENSADO);
  notificarPantallaTotalZonas(numZonas);
  notificarPantallaZona(1);
  notificarPantallaActividad(4);
  notificarPantallaFase(1);
  notificarIndicadorLED(0);
  Serial.printf("[SENSADO] Iniciado (%d zonas)%s\n", numZonas, modoFuerza?" [MODO FUERZA]":"");
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
    ordenarAvance("AVANZAR_MEDIO", velMedia, pasosDeZona(zonaActual));
    notificarPantallaZona(zonaActual+1);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  }
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 2: RIEGO SELECTIVO
// ─────────────────────────────────────────────────────────────────────
void calcularRangoSeco() {
  primeraSeca = -1; ultimaSeca = -1;
  for (int i = 0; i < numZonas; i++) {
    if (clasificacionZona[i] == 1) {
      if (primeraSeca < 0) primeraSeca = i;
      ultimaSeca = i;
    }
  }
}
void avanzarTramoRiego(bool riega) {
  zonasEnTramo = tramoMismaAccion(zonaActual, riega);
  if (zonasEnTramo < 1) zonasEnTramo = 1;
  long pasos = pasosTramo(zonaActual, zonasEnTramo);
  estadoActual = EM_RIEGO_AVANZANDO;
  digitalWrite(PIN_RELE_RIEGO, (riega && brazoRiegoAbajo) ? RELE_ON : RELE_OFF);
  if (riega) {
    ordenarAvance("AVANZAR_LENTO", velLenta, pasos);
    notificarPantallaActividad(2);
    notificarPantallaFase(0);
  } else if (brazoRiegoAbajo) {
    ordenarAvance("AVANZAR_MEDIO", velMedia, pasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  } else {
    ordenarAvance("AVANZAR_RAPIDO", velRapida, pasos);
    notificarPantallaActividad(4);
    notificarPantallaFase(1);
  }
  Serial.printf("[RIEGO] Tramo zonas %d-%d (%s, %ld pasos)\n",
                zonaActual+1, zonaActual+zonasEnTramo, riega?"regar":"saltar", pasos);
}
void terminarRiegoYRetornar() {
  if (brazoRiegoAbajo) {
    digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
    estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
    ordenarBrazo("BRAZO_RIEGO", 0);
    notificarPantallaActividad(4); notificarPantallaFase(4);
    Serial.println("[RIEGO] Ultima zona seca. Recogiendo brazo...");
  } else {
    estadoActual = EM_RIEGO_RETORNANDO;
    ordenarRetorno(posicionPasos);
    notificarPantallaActividad(4); notificarPantallaFase(2);
    Serial.println("[RIEGO] Brazo arriba. Regresando al origen...");
  }
}
void procesarZonaRiego() {
  if (zonaActual > ultimaSeca) { terminarRiegoYRetornar(); return; }
  notificarPantallaZona(zonaActual+1);
  bool necesitaRiego = (clasificacionZona[zonaActual] == 1);
  if (!brazoRiegoAbajo) {
    if (necesitaRiego) {
      estadoActual = EM_RIEGO_BAJANDO_BRAZO;
      ordenarBrazo("BRAZO_RIEGO", 1);
      notificarPantallaActividad(4); notificarPantallaFase(3);
      Serial.printf("[RIEGO] Zona %d seca. Extendiendo brazo...\n", zonaActual+1);
    } else {
      avanzarTramoRiego(false);
    }
  } else {
    if (!necesitaRiego && !MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS) {
      digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
      estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
      ordenarBrazo("BRAZO_RIEGO", 0);
      notificarPantallaActividad(4); notificarPantallaFase(4);
    } else {
      avanzarTramoRiego(necesitaRiego);
    }
  }
}
bool iniciarRiegoSelectivo() {
  if (numZonas < 1) return false;
  calcularRangoSeco();
  if (primeraSeca < 0) return false;
  zonaActual      = 0;
  modoRutina      = RUTINA_RIEGO;
  brazoRiegoAbajo = false;
  procesarZonaRiego();
  notificarPantallaRutina(RUTINA_RIEGO);
  notificarPantallaTotalZonas(numZonas);
  notificarIndicadorLED(0);
  Serial.printf("[RIEGO] Iniciando (zonas secas %d a %d)%s...\n",
                primeraSeca+1, ultimaSeca+1, modoFuerza?" [MODO FUERZA]":"");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// RUTINA 3: FERTILIZACIÓN
// ─────────────────────────────────────────────────────────────────────
bool iniciarFertilizacion() {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || distanciaTotalPasos <= 0) return false;
  modoRutina   = RUTINA_FERTILIZACION;
  estadoActual = EM_FERTI_BAJANDO_BRAZO;
  ordenarBrazo("BRAZO_FERTI", 1);
  notificarPantallaRutina(RUTINA_FERTILIZACION);
  notificarPantallaActividad(3);
  notificarPantallaFase(3);
  notificarIndicadorLED(9);
  Serial.printf("[FERTI] Iniciando%s...\n", modoFuerza?" [MODO FUERZA]":"");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// PRUEBAS DE CALIBRACIÓN
// ─────────────────────────────────────────────────────────────────────
bool iniciarPruebaMovimiento(float cm) {
  if (estadoActual != EM_ESPERANDO) return false;
  long pasos = (long)round((double)cm * pasosPorCm);
  if (pasos < 1) return false;
  pruebaPedidoCm = cm;
  modoRutina     = RUTINA_NINGUNA;
  estadoActual   = EM_PRUEBA_AVANZANDO;
  ordenarAvance("AVANZAR_MEDIO", velMedia, pasos);
  notificarPantallaActividad(4); notificarPantallaFase(1);
  Serial.printf("[PRUEBA] Moviendo %.1f cm = %ld pasos (%.2f pasos/cm)\n", cm, pasos, pasosPorCm);
  return true;
}
bool iniciarPruebaRetorno() {
  if (estadoActual != EM_PRUEBA_LISTA) return false;
  estadoActual = EM_PRUEBA_RETORNANDO;
  ordenarRetorno(posicionPasos);
  notificarPantallaActividad(4); notificarPantallaFase(2);
  Serial.println("[PRUEBA] Regresando al origen...");
  return true;
}
bool iniciarPruebaSensor() {
  if (estadoActual != EM_ESPERANDO || !ESP_B_CONECTADA) return false;
  modoRutina   = RUTINA_NINGUNA;
  estadoActual = EM_PRUEBA_SENSOR;
  ordenarA(NODO_B, "MEDIR", 0, 0);
  notificarPantallaActividad(1); notificarPantallaFase(0);
  Serial.println("[PRUEBA] Midiendo sensor...");
  return true;
}

// NUEVO v5.5: prueba manual de brazo desde la app (igual que prueba de movimiento)
bool iniciarPruebaBrazo(bool esRiego, int accion) {
  if (estadoActual != EM_ESPERANDO) return false;
  modoRutina   = RUTINA_NINGUNA;
  estadoActual = EM_PRUEBA_BRAZO;
  const char *cmd = esRiego ? "BRAZO_RIEGO" : "BRAZO_FERTI";
  ordenarBrazo(cmd, accion);
  notificarPantallaActividad(4);
  notificarPantallaFase(accion == 1 ? 3 : 4);
  Serial.printf("[PRUEBA_BRAZO] Brazo %s: %s\n",
                esRiego ? "RIEGO" : "FERTI", accion == 1 ? "extendiendo" : "recogiendo");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));
  String emisor  = String(msgRecibido.emisor);
  String comando = String(msgRecibido.comando);
  Serial.printf("[RX] de %s: %s (%d)\n", emisor.c_str(), comando.c_str(), msgRecibido.parametro);
  if (nodoEsperado == NODO_A && emisor == "ESPA" &&
      (comando == "LISTO" || comando == "BRAZO_OK")) {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado   = NODO_NINGUNO;
  } else if (ESP_B_CONECTADA && nodoEsperado == NODO_B &&
             emisor == "ESPB" && comando == "HUMEDAD") {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado   = NODO_NINGUNO;
  }
}

// ─────────────────────────────────────────────────────────────────────
// REFRESCO PERIÓDICO
// ─────────────────────────────────────────────────────────────────────
void refrescarEstadoExterno() {
  notificarPantallaActividad(0);
  notificarPantallaFase(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  if (numZonas > 0) notificarPantallaTotalZonas(numZonas);
  notificarPantallaEstado(nivelSueloActual);
  if (estadoActual == EM_EMERGENCIA) {
    if (huboFalla) notificarPantallaFalla(codigoFalla);
    enviarStopATodos();
  } else {
    notificarPantallaFalla(0);
    notificarIndicadorLED(indicadorActual);
  }
}

// ─────────────────────────────────────────────────────────────────────
// BOTÓN DE EMERGENCIA
// ─────────────────────────────────────────────────────────────────────
void revisarBotonEmergencia() {
  bool lectura = digitalRead(PIN_BOTON_EMERGENCIA);
  if (lectura != lecturaBotonCruda) { lecturaBotonCruda = lectura; ultimoCambioBoton = millis(); }
  if (lecturaBotonCruda != botonEstable && (millis()-ultimoCambioBoton) > DEBOUNCE_MS) {
    botonEstable = lecturaBotonCruda;
    if (botonEstable == LOW && estadoActual != EM_EMERGENCIA) {
      Serial.println("[M] BOTON EMERGENCIA");
      declararFalla(9);
    }
  }
}

// ─────────────────────────────────────────────────────────────────────
// PÁGINA WEB — incluye todas las nuevas tarjetas v5.5
// ─────────────────────────────────────────────────────────────────────
const char PAGINA_PRINCIPAL[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>FertiTrack v5.5</title>
<style>
body{margin:0;font-family:Arial,sans-serif;background:#F0F4F0;color:#222;padding-bottom:30px;}
header{background:#2E7D32;color:#fff;padding:16px;text-align:center;font-size:20px;font-weight:bold;}
.ver{font-size:12px;opacity:.7;}
.tarjeta{background:#fff;margin:14px;padding:16px;border-radius:10px;box-shadow:0 2px 6px rgba(0,0,0,.12);}
.fila{display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #eee;font-size:14px;gap:8px;}
h3{margin:0 0 10px;font-size:15px;color:#2E7D32;}
input[type=number]{width:90px;padding:5px;border:1px solid #ccc;border-radius:6px;font-size:14px;}
input[type=number].corto{width:70px;}
button{width:100%;padding:13px;margin:5px 0;font-size:14px;border:none;border-radius:8px;color:#fff;font-weight:bold;cursor:pointer;}
.btn-ciclo{background:#1565C0;}.btn-sensado{background:#0288D1;}.btn-fumigar{background:#E65100;}
.btn-detener{background:#C62828;}.btn-reiniciar{background:#2E7D32;}.btn-guardar{background:#546E7A;}
.btn-prueba{background:#6A1B9A;}.btn-brazo{background:#00695C;flex:1;padding:10px;margin:3px;font-size:13px;}
.nota{font-size:12px;color:#555;margin:5px 0;line-height:1.4;}
.aviso-falla{background:#C62828;color:#fff;padding:14px;border-radius:10px;margin:14px;text-align:center;font-weight:bold;display:none;}
.mapa-zonas{display:flex;gap:3px;flex-wrap:wrap;margin-top:10px;}
.zona{flex:1 1 auto;min-width:16px;height:34px;border-radius:4px;display:flex;align-items:center;justify-content:center;font-size:10px;color:#fff;font-weight:bold;}
.z0{background:#bbb;}.z1{background:#E53935;}.z2{background:#FFB300;color:#333;}.z3{background:#43A047;}.z4{background:#1E88E5;}
.cms{font-size:12px;color:#888;min-width:60px;text-align:right;}
.fuerza-on{background:#BF360C!important;}
label{font-size:14px;}
.flex-row{display:flex;gap:6px;flex-wrap:wrap;}
</style>
</head>
<body>
<header>🌿 FertiTrack <span class="ver">v5.5</span></header>
<div id="avisoFalla" class="aviso-falla"></div>

<!-- Estado -->
<div class="tarjeta">
  <h3>📊 Estado del sistema</h3>
  <div class="fila"><span>Estado</span><span id="estado">--</span></div>
  <div class="fila"><span>Rutina activa</span><span id="rutina">--</span></div>
  <div class="fila"><span>Zona</span><span id="zona">--</span></div>
  <div class="fila"><span>Humedad ADC</span><span id="humedad">--</span></div>
  <div class="fila"><span>Humedad %</span><span id="humedadPct">--</span></div>
  <div class="fila"><span>Suelo (última zona)</span><span id="suelo">--</span></div>
  <div class="fila"><span>Próximo ciclo auto</span><span id="proximo">--</span></div>
  <div class="fila"><span>Modo Fuerza</span><span id="mfEstado" style="font-weight:bold;">--</span></div>
  <div class="mapa-zonas" id="mapaZonas"></div>
</div>

<!-- Configuración de recorrido -->
<div class="tarjeta">
  <h3>📐 Configuración del recorrido</h3>
  <div class="fila"><span>Distancia A→B (cm)</span><input type="number" id="distanciaCm" step="0.1" min="1"></div>
  <div class="fila"><span>Ancho de zona (cm)</span><input type="number" id="anchoZonaCm" step="0.1" min="0.5"></div>
  <button class="btn-guardar" onclick="guardarConfig()">Guardar configuración</button>
</div>

<!-- Modo automático -->
<div class="tarjeta">
  <h3>🔄 Modo automático</h3>
  <div class="fila"><span><input type="checkbox" id="autoActivo"> Repetir ciclo automáticamente</span></div>
  <div class="fila"><span>Descanso entre ciclos (seg)</span><input type="number" id="descansoSeg" min="5"></div>
  <button class="btn-guardar" onclick="guardarAuto()">Guardar modo automático</button>
</div>

<!-- === NUEVO v5.5: Velocidades y Modo Fuerza === -->
<div class="tarjeta">
  <h3>⚡ Velocidades y potencia de motores</h3>
  <div class="nota">Paso completo: máx ~1000 p/s seguro. Los valores se sincronizan con ESP_A automáticamente. Con <b>Modo Fuerza</b> activo, todo movimiento usa vel. lenta para máximo par.</div>
  <div class="fila"><span>Vel. rápida (p/s)</span><input type="number" id="velR" min="50" max="1000" step="10"><span id="velRcms" class="cms">--</span></div>
  <div class="fila"><span>Vel. media (p/s)</span><input type="number" id="velM" min="30" max="800" step="10"><span id="velMcms" class="cms">--</span></div>
  <div class="fila"><span>Vel. lenta (p/s)</span><input type="number" id="velL" min="20" max="500" step="10"><span id="velLcms" class="cms">--</span></div>
  <div class="fila"><span>Aceleración (p/s²)</span><input type="number" id="acelVal" min="50" max="2000" step="10"></div>
  <button class="btn-guardar" onclick="guardarVelocidades()">💾 Guardar y sincronizar con ESP_A</button>
  <div class="fila" style="margin-top:8px;">
    <span style="font-size:14px;font-weight:bold;">🔥 Modo Fuerza (usa vel. lenta siempre)</span>
    <input type="checkbox" id="modoFuerzaChk" style="width:20px;height:20px;" onchange="toggleFuerza()">
  </div>
  <div class="nota" id="notaFuerza">Desactivado — el robot usa la velocidad normal según la operación.</div>
</div>

<!-- === NUEVO v5.5: Calibrar actuadores === -->
<div class="tarjeta">
  <h3>🦾 Calibrar actuadores (brazos)</h3>
  <div class="nota">Mide con cronómetro cuánto tarda cada brazo en recorrer TODA la carrera. El margen asegura que llegue al tope (el actuador tiene final de carrera interno).</div>
  <div class="fila"><span>Tiempo extensión medido (ms)</span><input type="number" class="corto" id="actExt" min="500" max="15000" step="50"></div>
  <div class="fila"><span>Tiempo retracción medido (ms)</span><input type="number" class="corto" id="actRec" min="500" max="15000" step="50"></div>
  <div class="fila"><span>Margen de seguridad (ms)</span><input type="number" class="corto" id="actMrg" min="0" max="3000" step="50"></div>
  <div class="nota" id="totalAct">Total ext: -- ms | Total ret: -- ms</div>
  <button class="btn-guardar" onclick="guardarActuadores()">💾 Guardar tiempos y sincronizar con ESP_A</button>
  <div class="nota" style="margin-top:8px;">Prueba manual de brazos (robot debe estar en reposo):</div>
  <div class="flex-row">
    <button class="btn-brazo" onclick="pruebaBrazo('riego',1)">▼ Ext. Riego</button>
    <button class="btn-brazo" onclick="pruebaBrazo('riego',0)">▲ Rec. Riego</button>
    <button class="btn-brazo" style="background:#BF360C" onclick="pruebaBrazo('ferti',1)">▼ Ext. Ferti</button>
    <button class="btn-brazo" style="background:#BF360C" onclick="pruebaBrazo('ferti',0)">▲ Rec. Ferti</button>
  </div>
</div>

<!-- Calibrar distancia (con diámetro de rueda v5.5) -->
<div class="tarjeta">
  <h3>📏 Calibrar distancia</h3>
  <div class="nota">Pasos/cm actual: <b id="pasosCm">--</b>. 1) Mover prueba. 2) Medir distancia real. 3) Calibrar. 4) Volver al origen y repetir para confirmar.</div>
  <div class="fila"><span>Distancia de prueba (cm)</span><input type="number" class="corto" id="pruebaCm" step="0.5" min="1" value="10"></div>
  <button class="btn-prueba" onclick="pruebaMov()">▶ Mover prueba</button>
  <div class="fila"><span>Distancia REAL medida (cm)</span><input type="number" class="corto" id="realCm" step="0.1" min="0.1"></div>
  <button class="btn-guardar" onclick="calibrarDist()">✅ Calibrar con esa medida</button>
  <button class="btn-reiniciar" onclick="pruebaRetorno()">↩ Volver al origen</button>
  <!-- === NUEVO v5.5: Rueda === -->
  <div style="border-top:1px solid #eee;margin-top:10px;padding-top:10px;">
    <div class="nota">Opcional: ingresa el diámetro de la rueda para calcular los pasos/cm por defecto (PI × d). Útil si cambias la rueda.</div>
    <div class="fila"><span>Diámetro de rueda (cm)</span><input type="number" id="ruedaDiam" step="0.1" min="1"></div>
    <div class="nota" id="ruedaInfo">CM/vuelta: -- | Pasos/cm calculado: --</div>
    <button class="btn-guardar" onclick="guardarRueda(false)">💾 Guardar diámetro</button>
    <button class="btn-prueba" onclick="guardarRueda(true)">🔄 Recalcular y aplicar pasos/cm desde rueda</button>
  </div>
</div>

<!-- Calibrar sensor -->
<div class="tarjeta">
  <h3>💧 Calibrar sensor de humedad</h3>
  <div class="nota">1) Sensor al aire → Medir → Guardar como SECO. 2) Sensor en agua → Medir → Guardar como MOJADO.</div>
  <button class="btn-prueba" onclick="pruebaSensor()">🔍 Medir sensor ahora</button>
  <button class="btn-guardar" onclick="calSensor('seco')">Guardar lectura como SECO</button>
  <button class="btn-guardar" onclick="calSensor('mojado')">Guardar lectura como MOJADO</button>
  <div class="fila"><span>SECA si humedad &lt; (%)</span><input type="number" class="corto" id="pctSeca" min="1" max="98"></div>
  <div class="fila"><span>HÚMEDA desde (%)</span><input type="number" class="corto" id="pctHumeda" min="2" max="99"></div>
  <div class="fila"><span>SOBRE-HÚMEDA desde (%)</span><input type="number" class="corto" id="pctSobre" min="3" max="99"></div>
  <button class="btn-guardar" onclick="guardarPct()">Guardar porcentajes</button>
  <div class="nota" id="rangos">--</div>
</div>

<!-- Acciones -->
<div class="tarjeta">
  <button class="btn-ciclo"    onclick="iniciar('ciclo')">🌱 Iniciar Ciclo Completo (Sensar + Regar)</button>
  <button class="btn-sensado"  onclick="iniciar('sensado')">🔍 Solo Sensar</button>
  <button class="btn-fumigar"  onclick="iniciar('fertilizar')">🌿 Iniciar Fertilización</button>
  <button class="btn-detener"  onclick="detener()">🛑 DETENER (Emergencia)</button>
  <button class="btn-reiniciar" onclick="reiniciar()">🔄 Reiniciar (recuperar brazos y volver al origen)</button>
</div>

<script>
function iniciar(m){fetch('/api/iniciar?modo='+m).then(r=>r.json()).then(mostrarResultado).catch(()=>{});}
function detener(){fetch('/api/detener').catch(()=>{});}
function reiniciar(){fetch('/api/reiniciar').catch(()=>{});}
function mostrarResultado(d){if(d&&d.ok===false)alert('Error: '+d.error);}
function llamar(url){
  fetch(url).then(r=>r.json()).then(d=>{
    if(d&&d.ok===false)alert('Error: '+d.error);
    else if(d&&d.mensaje)alert(d.mensaje);
  }).catch(()=>{});
}
function pruebaMov(){llamar('/api/prueba_mov?cm='+(document.getElementById('pruebaCm').value||10));}
function pruebaRetorno(){llamar('/api/prueba_retorno');}
function calibrarDist(){
  const r=document.getElementById('realCm').value;
  if(!r||r<=0){alert('Escribe la distancia REAL medida en cm');return;}
  llamar('/api/calibrar_distancia?real_cm='+r);
}
function pruebaSensor(){llamar('/api/prueba_sensor');}
function calSensor(p){llamar('/api/cal_sensor?punto='+p);}
function guardarPct(){
  const a=document.getElementById('pctSeca').value,
        b=document.getElementById('pctHumeda').value,
        c=document.getElementById('pctSobre').value;
  if(!a||!b||!c){alert('Completa los tres porcentajes');return;}
  llamar('/api/cal_sensor?pct_seca='+a+'&pct_humeda='+b+'&pct_sobre='+c);
}
function guardarConfig(){
  const d=document.getElementById('distanciaCm').value,
        a=document.getElementById('anchoZonaCm').value;
  if(!d||d<=0){alert('Ingresa la distancia total en cm');return;}
  let url='/api/configurar?distancia_cm='+d;
  if(a)url+='&ancho_zona_cm='+a;
  fetch(url).then(r=>r.json()).then(mostrarResultado).catch(()=>{});
}
function guardarAuto(){
  const activo=document.getElementById('autoActivo').checked?1:0,
        seg=document.getElementById('descansoSeg').value||60;
  fetch('/api/auto?activar='+activo+'&descanso_seg='+seg).then(r=>r.json()).then(mostrarResultado).catch(()=>{});
}
// v5.5: velocidades
function guardarVelocidades(){
  const r=document.getElementById('velR').value,
        m=document.getElementById('velM').value,
        l=document.getElementById('velL').value,
        a=document.getElementById('acelVal').value;
  if(!r||!m||!l||!a){alert('Completa todos los campos de velocidad');return;}
  if(parseFloat(r)<parseFloat(m)||parseFloat(m)<parseFloat(l)){
    alert('Debe cumplirse: vel. rápida >= vel. media >= vel. lenta');return;
  }
  llamar('/api/velocidades?vel_r='+r+'&vel_m='+m+'&vel_l='+l+'&acel='+a);
}
function toggleFuerza(){
  const f=document.getElementById('modoFuerzaChk').checked?1:0;
  llamar('/api/velocidades?modo_fuerza='+f);
}
// v5.5: actuadores
function guardarActuadores(){
  const e=document.getElementById('actExt').value,
        r=document.getElementById('actRec').value,
        m=document.getElementById('actMrg').value;
  if(!e||!r||!m){alert('Completa los tres campos de tiempo');return;}
  llamar('/api/actuadores?ext_ms='+e+'&rec_ms='+r+'&mrg_ms='+m);
}
function pruebaBrazo(b,a){llamar('/api/prueba_brazo?brazo='+b+'&accion='+a);}
// v5.5: rueda
function guardarRueda(aplicar){
  const d=document.getElementById('ruedaDiam').value;
  if(!d||d<=0){alert('Ingresa el diámetro en cm');return;}
  llamar('/api/rueda?diametro_cm='+d+'&aplicar='+(aplicar?1:0));
}
// Refresco de estado
const nombresSuelo=['Sin datos','Seca','Intermedia','Húmeda','Sobre-húmeda'];
function refrescar(){
  fetch('/api/estado').then(r=>r.json()).then(d=>{
    document.getElementById('estado').innerText=d.estado;
    document.getElementById('rutina').innerText=d.modo_rutina;
    document.getElementById('zona').innerText=(d.total_zonas>0)?(d.zona_actual+' / '+d.total_zonas):'--';
    document.getElementById('humedad').innerText=(d.humedad_adc>=0)?d.humedad_adc:'--';
    document.getElementById('humedadPct').innerText=(d.humedad_pct>=0)?d.humedad_pct.toFixed(0)+' %':'--';
    document.getElementById('pasosCm').innerText=d.pasos_por_cm.toFixed(3);
    document.getElementById('suelo').innerText=nombresSuelo[d.nivel_suelo]||'--';
    document.getElementById('proximo').innerText=(d.proximo_ciclo_seg>=0)?(d.proximo_ciclo_seg+' s'):'--';
    // Modo fuerza
    const mf=d.modo_fuerza;
    document.getElementById('mfEstado').innerText=mf?'🔥 ACTIVO':'✅ Normal';
    document.getElementById('mfEstado').style.color=mf?'#BF360C':'#2E7D32';
    document.getElementById('modoFuerzaChk').checked=mf;
    document.getElementById('notaFuerza').innerText=mf?
      '🔥 ACTIVO — Todos los movimientos usan velocidad lenta (máximo par de los motores).':
      'Desactivado — velocidad normal según la operación.';
    // Velocidades (no sobreescribir si el usuario está editando)
    ['velR','velM','velL','acelVal'].forEach(function(id,i){
      var el=document.getElementById(id);
      if(!el.dataset.tocado)el.value=[d.vel_rapida,d.vel_media,d.vel_lenta,d.acel][i];
    });
    var ppc=d.pasos_por_cm||6.25;
    document.getElementById('velRcms').innerText='≈'+(d.vel_rapida/ppc).toFixed(1)+' cm/s';
    document.getElementById('velMcms').innerText='≈'+(d.vel_media/ppc).toFixed(1)+' cm/s';
    document.getElementById('velLcms').innerText='≈'+(d.vel_lenta/ppc).toFixed(1)+' cm/s';
    // Actuadores
    ['actExt','actRec','actMrg'].forEach(function(id,i){
      var el=document.getElementById(id);
      if(!el.dataset.tocado)el.value=[d.act_ext_medido,d.act_rec_medido,d.act_mrg_ms][i];
    });
    document.getElementById('totalAct').innerText=
      'Total ext: '+d.act_ext_ms+' ms | Total ret: '+d.act_rec_ms+' ms';
    // Rueda
    var ru=document.getElementById('ruedaDiam');
    if(!ru.dataset.tocado)ru.value=(d.diametro_rueda_cm||10.19).toFixed(2);
    var cmv=(3.14159*d.diametro_rueda_cm).toFixed(2);
    var pcd=(200/cmv).toFixed(3);
    document.getElementById('ruedaInfo').innerText='CM/vuelta: '+cmv+' | Pasos/cm calculado: '+pcd;
    // Distancia y zonas
    var cd=document.getElementById('distanciaCm');
    if(!cd.dataset.tocado){
      cd.value=d.distancia_cm||'';
      document.getElementById('anchoZonaCm').value=d.ancho_zona_cm?d.ancho_zona_cm.toFixed(2):'';
    }
    // Rangos sensor
    document.getElementById('rangos').innerHTML=
      'SECA: ADC &ge;'+d.umbral_seco+' (&lt;'+d.pct_seca+'%)<br>'+
      'INTER: '+d.umbral_humedo+'&ndash;'+d.umbral_seco+' ('+d.pct_seca+'&ndash;'+d.pct_humeda+'%)<br>'+
      'HÚMEDA: &le;'+d.umbral_humedo+' (&ge;'+d.pct_humeda+'%)<br>'+
      'SOBRE-HÚM: &le;'+d.umbral_sobre+' (&ge;'+d.pct_sobre+'%)<br>'+
      'Ref: seco='+d.cal_seco+' mojado='+d.cal_mojado;
    ['pctSeca','pctHumeda','pctSobre'].forEach(function(id){
      var el=document.getElementById(id);
      if(!el.dataset.tocado)el.value=d[{pctSeca:'pct_seca',pctHumeda:'pct_humeda',pctSobre:'pct_sobre'}[id]];
    });
    document.getElementById('autoActivo').checked=d.auto_activo;
    document.getElementById('descansoSeg').value=d.descanso_seg;
    // Falla
    var av=document.getElementById('avisoFalla');
    if(d.falla>0){av.style.display='block';av.innerText='⚠ FALLA (código '+d.falla+') — presiona Reiniciar';}
    else av.style.display='none';
    // Mapa de zonas
    var mp=document.getElementById('mapaZonas');
    mp.innerHTML='';
    if(d.zonas)d.zonas.forEach(function(niv,i){
      var dv=document.createElement('div');
      dv.className='zona z'+niv;dv.innerText=i+1;mp.appendChild(dv);
    });
  }).catch(()=>{});
}
// Marcar campos modificados manualmente
['distanciaCm','velR','velM','velL','acelVal','actExt','actRec','actMrg','ruedaDiam',
 'pctSeca','pctHumeda','pctSobre'].forEach(function(id){
  var el=document.getElementById(id);
  if(el)el.addEventListener('input',function(){this.dataset.tocado='1';});
});
setInterval(refrescar,2000);
refrescar();
</script>
</body>
</html>
)rawliteral";

// ─────────────────────────────────────────────────────────────────────
// RUTAS DEL SERVIDOR WEB
// ─────────────────────────────────────────────────────────────────────
void ruta_raiz() { server.send_P(200, "text/html", PAGINA_PRINCIPAL); }

void ruta_configurar() {
  if (estadoActual != EM_ESPERANDO) {
    server.send(409,"application/json","{\"ok\":false,\"error\":\"sistema ocupado\"}"); return;
  }
  if (!server.hasArg("distancia_cm")) {
    server.send(400,"application/json","{\"ok\":false,\"error\":\"falta distancia_cm\"}"); return;
  }
  float nd = server.arg("distancia_cm").toFloat();
  if (nd <= 0) { server.send(400,"application/json","{\"ok\":false,\"error\":\"distancia_cm debe ser > 0\"}"); return; }
  distanciaTotalCm = nd;
  if (server.hasArg("ancho_zona_cm")) { float az = server.arg("ancho_zona_cm").toFloat(); if (az > 0) anchoZonaCm = az; }
  if (server.hasArg("pasos_cm"))      { float pc = server.arg("pasos_cm").toFloat();      if (pc > 0) pasosPorCm  = pc; }
  if (!recalcularZonas()) { server.send(500,"application/json","{\"ok\":false,\"error\":\"no se pudo calcular zonas\"}"); return; }
  guardarConfiguracion();
  JsonDocument doc;
  doc["ok"] = true; doc["num_zonas"] = numZonas;
  doc["ancho_zona_real_cm"] = distanciaTotalCm/numZonas;
  doc["distancia_total_pasos"] = distanciaTotalPasos;
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_auto() {
  if (server.hasArg("activar"))      cicloAutomaticoActivo = server.arg("activar").toInt() != 0;
  if (server.hasArg("descanso_seg")) { long sg = server.arg("descanso_seg").toInt(); if (sg > 0) intervaloDescansoMs = (unsigned long)sg*1000UL; }
  guardarConfiguracion();
  if (cicloAutomaticoActivo && estadoActual == EM_ESPERANDO && !esperandoProximoCiclo) programarProximoCicloSiAplica();
  if (!cicloAutomaticoActivo) esperandoProximoCiclo = false;
  JsonDocument doc;
  doc["ok"] = true; doc["auto_activo"] = cicloAutomaticoActivo; doc["descanso_seg"] = intervaloDescansoMs/1000;
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_iniciar() {
  String modo  = server.hasArg("modo") ? server.arg("modo") : "";
  bool   ok    = false;
  String error = "";
  if (estadoActual != EM_ESPERANDO) {
    error = "sistema ocupado";
  } else if (distanciaTotalCm <= 0 || numZonas < 1) {
    error = "configura primero la distancia con /api/configurar";
  } else if (modo == "ciclo")        { ok = iniciarSensado(true); }
  else if (modo == "sensado")        { ok = iniciarSensado(false); }
  else if (modo == "fertilizar")     { ok = iniciarFertilizacion(); }
  else { error = "modo invalido (usa ciclo, sensado o fertilizar)"; }
  if (ok) server.send(200,"application/json","{\"ok\":true}");
  else {
    if (error.length() == 0) error = "no se pudo iniciar";
    server.send(409,"application/json","{\"ok\":false,\"error\":\""+error+"\"}");
  }
}

void responderJson(int codigo, bool ok, const char *clave, const String &texto) {
  JsonDocument doc; doc["ok"] = ok; doc[clave] = texto;
  String out; serializeJson(doc, out);
  server.send(codigo, "application/json", out);
}

void ruta_prueba_mov() {
  float cm = server.hasArg("cm") ? server.arg("cm").toFloat() : 10.0f;
  if (cm < 1 || cm > 150) { responderJson(400,false,"error","cm debe estar entre 1 y 150"); return; }
  if (!iniciarPruebaMovimiento(cm)) { responderJson(409,false,"error","sistema ocupado"); return; }
  responderJson(200,true,"mensaje","Moviendo "+String(cm,1)+" cm. Mide cuanto avanzo de verdad.");
}

void ruta_calibrar_distancia() {
  if (estadoActual != EM_PRUEBA_LISTA && estadoActual != EM_ESPERANDO) {
    responderJson(409,false,"error","sistema ocupado"); return;
  }
  float real   = server.hasArg("real_cm")   ? server.arg("real_cm").toFloat()   : 0;
  float pedido = server.hasArg("pedido_cm") ? server.arg("pedido_cm").toFloat() : pruebaPedidoCm;
  if (real <= 0)   { responderJson(400,false,"error","falta real_cm"); return; }
  if (pedido <= 0) { responderJson(400,false,"error","primero haz Mover prueba"); return; }
  float nuevo = pasosPorCm * pedido / real;
  if (nuevo < 0.1f || nuevo > 5000.0f) { responderJson(400,false,"error","valor fuera de rango"); return; }
  float antes = pasosPorCm;
  pasosPorCm      = nuevo;
  pruebaPedidoCm  = 0;
  if (distanciaTotalCm > 0) recalcularZonas();
  guardarConfiguracion();
  Serial.printf("[CALIB] pasos/cm %.3f -> %.3f\n", antes, nuevo);
  responderJson(200,true,"mensaje","Nueva calibracion: "+String(nuevo,3)+" pasos/cm (antes "+String(antes,3)+"). Repite la prueba para confirmar.");
}

void ruta_prueba_retorno() {
  if (!iniciarPruebaRetorno()) { responderJson(409,false,"error","solo despues de una prueba de movimiento"); return; }
  responderJson(200,true,"mensaje","Regresando al origen...");
}

void ruta_prueba_sensor() {
  if (!ESP_B_CONECTADA) { responderJson(409,false,"error","ESP_B no esta conectada"); return; }
  if (!iniciarPruebaSensor()) { responderJson(409,false,"error","sistema ocupado"); return; }
  responderJson(200,true,"mensaje","Midiendo (~8 s). El ADC aparecera en Estado.");
}

void ruta_cal_sensor() {
  if (estadoActual != EM_ESPERANDO) { responderJson(409,false,"error","sistema ocupado"); return; }
  int nS=adcSeco, nM=adcMojado, pS=pctSeca, pH=pctHumeda, pO=pctSobre;
  if (server.hasArg("punto")) {
    String p = server.arg("punto");
    int adc  = server.hasArg("adc") ? server.arg("adc").toInt() : ultimaHumedadADC;
    if (adc < ADC_MIN_VALIDO || adc > 4095) { responderJson(400,false,"error","no hay lectura valida: mide primero"); return; }
    if (p == "seco") nS = adc;
    else if (p == "mojado") nM = adc;
    else { responderJson(400,false,"error","punto debe ser seco o mojado"); return; }
  }
  if (server.hasArg("pct_seca"))   pS = server.arg("pct_seca").toInt();
  if (server.hasArg("pct_humeda")) pH = server.arg("pct_humeda").toInt();
  if (server.hasArg("pct_sobre"))  pO = server.arg("pct_sobre").toInt();
  if (nS - nM < 300) { responderJson(400,false,"error","SECO debe ser >= 300 mayor que MOJADO"); return; }
  if (!(pS > 0 && pS < pH && pH < pO && pO < 100)) { responderJson(400,false,"error","porcentajes deben cumplir 0 < seca < humeda < sobre < 100"); return; }
  adcSeco=nS; adcMojado=nM; pctSeca=pS; pctHumeda=pH; pctSobre=pO;
  recalcularUmbrales();
  guardarConfiguracion();
  Serial.printf("[CALIB] seco=%d mojado=%d | umbrales: seca>=%d humeda<=%d sobre<=%d\n", adcSeco,adcMojado,umbralSeco,umbralHumedo,umbralSobreHumedo);
  responderJson(200,true,"mensaje","Guardado. Seca: ADC >= "+String(umbralSeco)+" | Intermedia: "+String(umbralHumedo)+"-"+String(umbralSeco)+" | Humeda: <= "+String(umbralHumedo));
}

// === NUEVA RUTA v5.5: Velocidades ===
void ruta_velocidades() {
  bool cambio = false;
  if (server.hasArg("vel_r")) {
    float v = server.arg("vel_r").toFloat();
    if (v >= 50 && v <= 1000) { velRapida = v; cambio = true; }
    else { responderJson(400,false,"error","vel_r fuera de rango (50-1000)"); return; }
  }
  if (server.hasArg("vel_m")) {
    float v = server.arg("vel_m").toFloat();
    if (v >= 30 && v <= 800) { velMedia = v; cambio = true; }
    else { responderJson(400,false,"error","vel_m fuera de rango (30-800)"); return; }
  }
  if (server.hasArg("vel_l")) {
    float v = server.arg("vel_l").toFloat();
    if (v >= 20 && v <= 500) { velLenta = v; cambio = true; }
    else { responderJson(400,false,"error","vel_l fuera de rango (20-500)"); return; }
  }
  if (server.hasArg("acel")) {
    float v = server.arg("acel").toFloat();
    if (v >= 50 && v <= 2000) { acel = v; cambio = true; }
    else { responderJson(400,false,"error","acel fuera de rango (50-2000)"); return; }
  }
  if (server.hasArg("modo_fuerza")) {
    modoFuerza = server.arg("modo_fuerza").toInt() != 0;
    cambio = true;
    Serial.printf("[FUERZA] Modo fuerza: %s\n", modoFuerza ? "ACTIVO" : "inactivo");
  }
  // Validar orden de velocidades
  if (velRapida < velMedia || velMedia < velLenta) {
    responderJson(400,false,"error","orden: vel_r >= vel_m >= vel_l"); return;
  }
  if (cambio) {
    guardarConfiguracion();
    sincronizarVelocidadesConEspA();
  }
  JsonDocument doc;
  doc["ok"]          = true;
  doc["vel_rapida"]  = velRapida;
  doc["vel_media"]   = velMedia;
  doc["vel_lenta"]   = velLenta;
  doc["acel"]        = acel;
  doc["modo_fuerza"] = modoFuerza;
  doc["mensaje"]     = cambio ? "Guardado y sincronizado con ESP_A" : "Sin cambios";
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

// === NUEVA RUTA v5.5: Actuadores ===
void ruta_actuadores() {
  bool cambio = false;
  if (server.hasArg("ext_ms")) {
    long v = server.arg("ext_ms").toInt();
    if (v >= 500 && v <= 15000) { actExtenderMedidoMs = (unsigned long)v; cambio = true; }
    else { responderJson(400,false,"error","ext_ms fuera de rango (500-15000)"); return; }
  }
  if (server.hasArg("rec_ms")) {
    long v = server.arg("rec_ms").toInt();
    if (v >= 500 && v <= 15000) { actRecogerMedidoMs = (unsigned long)v; cambio = true; }
    else { responderJson(400,false,"error","rec_ms fuera de rango (500-15000)"); return; }
  }
  if (server.hasArg("mrg_ms")) {
    long v = server.arg("mrg_ms").toInt();
    if (v >= 0 && v <= 3000) { actMargenMs = (unsigned long)v; cambio = true; }
    else { responderJson(400,false,"error","mrg_ms fuera de rango (0-3000)"); return; }
  }
  if (cambio) {
    recalcularTiemposActuadores();
    guardarConfiguracion();
    sincronizarActuadoresConEspA();
    Serial.printf("[ACT] Tiempos: EXT=%lums REC=%lums (medido+margen)\n", actExtenderMs, actRecogerMs);
  }
  JsonDocument doc;
  doc["ok"]             = true;
  doc["act_ext_medido"] = actExtenderMedidoMs;
  doc["act_rec_medido"] = actRecogerMedidoMs;
  doc["act_mrg_ms"]     = actMargenMs;
  doc["act_ext_ms"]     = actExtenderMs;
  doc["act_rec_ms"]     = actRecogerMs;
  doc["mensaje"]        = cambio ? "Guardado y sincronizado con ESP_A" : "Sin cambios";
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

// === NUEVA RUTA v5.5: Rueda ===
void ruta_rueda() {
  if (!server.hasArg("diametro_cm")) { responderJson(400,false,"error","falta diametro_cm"); return; }
  float d = server.arg("diametro_cm").toFloat();
  if (d <= 0 || d > 200) { responderJson(400,false,"error","diametro_cm fuera de rango"); return; }
  diametroRuedaCm   = d;
  float cmPorRev    = M_PI * d;
  float ppcCalculado = PASOS_POR_REV / cmPorRev;
  bool aplicar      = server.hasArg("aplicar") && server.arg("aplicar").toInt() != 0;
  if (aplicar) {
    pasosPorCm = ppcCalculado;
    if (distanciaTotalCm > 0) recalcularZonas();
    Serial.printf("[RUEDA] Diametro=%.2fcm CM/vuelta=%.2f Pasos/cm=%.3f (APLICADO)\n", d, cmPorRev, ppcCalculado);
  } else {
    Serial.printf("[RUEDA] Diametro=%.2fcm CM/vuelta=%.2f Pasos/cm=%.3f (guardado, no aplicado)\n", d, cmPorRev, ppcCalculado);
  }
  guardarConfiguracion();
  JsonDocument doc;
  doc["ok"]             = true;
  doc["diametro_cm"]    = diametroRuedaCm;
  doc["cm_por_vuelta"]  = cmPorRev;
  doc["pasos_cm_calc"]  = ppcCalculado;
  doc["pasos_cm_activo"]= pasosPorCm;
  doc["aplicado"]       = aplicar;
  doc["mensaje"]        = aplicar
    ? "Rueda guardada y pasos/cm actualizados a "+String(ppcCalculado,3)+". Recalibra con prueba de movimiento."
    : "Rueda guardada. Toca 'Recalcular y aplicar' para usar estos pasos/cm.";
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

// === NUEVA RUTA v5.5: Prueba de brazo ===
void ruta_prueba_brazo() {
  if (estadoActual != EM_ESPERANDO) { responderJson(409,false,"error","sistema ocupado (robot debe estar en reposo)"); return; }
  String brazo  = server.hasArg("brazo")  ? server.arg("brazo")           : "";
  int    accion = server.hasArg("accion") ? server.arg("accion").toInt()  : -1;
  if (brazo != "riego" && brazo != "ferti") { responderJson(400,false,"error","brazo debe ser 'riego' o 'ferti'"); return; }
  if (accion != 0 && accion != 1)           { responderJson(400,false,"error","accion debe ser 0 (recoger) o 1 (extender)"); return; }
  bool esRiego = (brazo == "riego");
  if (!iniciarPruebaBrazo(esRiego, accion)) { responderJson(409,false,"error","no se pudo iniciar prueba de brazo"); return; }
  unsigned long tms = (accion == 1) ? actExtenderMs : actRecogerMs;
  responderJson(200,true,"mensaje",
    String("Brazo ") + brazo + (accion==1?" EXTENDIENDOSE":" RECOGIENDO") +
    " (" + String(tms) + " ms). Cuando termine podras ordenar otra accion.");
}

void ruta_detener()  { declararFalla(8); server.send(200,"application/json","{\"ok\":true}"); }
void ruta_reiniciar(){ reiniciarSistema(); server.send(200,"application/json","{\"ok\":true}"); }

void ruta_estado() {
  JsonDocument doc;
  doc["estado"]       = nombreEstado(estadoActual);
  doc["modo_rutina"]  = nombreRutina(modoRutina);
  doc["zona_actual"]  = (numZonas > 0) ? (zonaActual+1) : 0;
  doc["total_zonas"]  = numZonas;
  doc["distancia_cm"] = distanciaTotalCm;
  doc["ancho_zona_cm"]= (numZonas > 0) ? (distanciaTotalCm/numZonas) : anchoZonaCm;
  doc["pasos_por_cm"] = pasosPorCm;
  doc["posicion_pasos"]= posicionPasos;
  doc["humedad_adc"]  = ultimaHumedadADC;
  doc["humedad_pct"]  = humedadPorcentaje(ultimaHumedadADC);
  doc["cal_seco"]     = adcSeco;
  doc["cal_mojado"]   = adcMojado;
  doc["pct_seca"]     = pctSeca;
  doc["pct_humeda"]   = pctHumeda;
  doc["pct_sobre"]    = pctSobre;
  doc["umbral_seco"]  = umbralSeco;
  doc["umbral_humedo"]= umbralHumedo;
  doc["umbral_sobre"] = umbralSobreHumedo;
  doc["nivel_suelo"]  = nivelSueloActual;
  doc["actividad"]    = actividadActual;
  doc["falla"]        = huboFalla ? codigoFalla : 0;
  doc["esp_b_conectada"]= ESP_B_CONECTADA;
  doc["esp_c_conectada"]= ESP_C_CONECTADA;
  doc["auto_activo"]  = cicloAutomaticoActivo;
  doc["descanso_seg"] = intervaloDescansoMs/1000;
  // v5.5: velocidades
  doc["vel_rapida"]   = velRapida;
  doc["vel_media"]    = velMedia;
  doc["vel_lenta"]    = velLenta;
  doc["acel"]         = acel;
  doc["modo_fuerza"]  = modoFuerza;
  // v5.5: actuadores
  doc["act_ext_medido"]= actExtenderMedidoMs;
  doc["act_rec_medido"]= actRecogerMedidoMs;
  doc["act_mrg_ms"]   = actMargenMs;
  doc["act_ext_ms"]   = actExtenderMs;
  doc["act_rec_ms"]   = actRecogerMs;
  // v5.5: rueda
  doc["diametro_rueda_cm"] = diametroRuedaCm;
  if (esperandoProximoCiclo) {
    long r = (long)(proximoCicloMillis - millis());
    doc["proximo_ciclo_seg"] = (r > 0) ? (r/1000) : 0;
  } else { doc["proximo_ciclo_seg"] = -1; }
  JsonArray zonas = doc["zonas"].to<JsonArray>();
  for (int i = 0; i < numZonas; i++) zonas.add(clasificacionZona[i]);
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_no_encontrada() { server.send(404,"application/json","{\"error\":\"ruta no encontrada\"}"); }

void configurarServidor() {
  server.on("/",                        HTTP_GET, ruta_raiz);
  server.on("/api/configurar",          HTTP_GET, ruta_configurar);
  server.on("/api/auto",                HTTP_GET, ruta_auto);
  server.on("/api/iniciar",             HTTP_GET, ruta_iniciar);
  server.on("/api/detener",             HTTP_GET, ruta_detener);
  server.on("/api/reiniciar",           HTTP_GET, ruta_reiniciar);
  server.on("/api/estado",              HTTP_GET, ruta_estado);
  server.on("/api/prueba_mov",          HTTP_GET, ruta_prueba_mov);
  server.on("/api/calibrar_distancia",  HTTP_GET, ruta_calibrar_distancia);
  server.on("/api/prueba_retorno",      HTTP_GET, ruta_prueba_retorno);
  server.on("/api/prueba_sensor",       HTTP_GET, ruta_prueba_sensor);
  server.on("/api/cal_sensor",          HTTP_GET, ruta_cal_sensor);
  // NUEVAS v5.5
  server.on("/api/velocidades",         HTTP_GET, ruta_velocidades);
  server.on("/api/actuadores",          HTTP_GET, ruta_actuadores);
  server.on("/api/rueda",               HTTP_GET, ruta_rueda);
  server.on("/api/prueba_brazo",        HTTP_GET, ruta_prueba_brazo);
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
  if (esp_now_add_peer(&peer) == ESP_OK) Serial.printf("  OK peer %s\n", nombre);
  else Serial.printf("  ERROR registrando peer %s\n", nombre);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.5] Iniciando MAESTRO...");

  pinMode(PIN_RELE_RIEGO,       OUTPUT); digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
  pinMode(PIN_RELE_FERTI,       OUTPUT); digitalWrite(PIN_RELE_FERTI, RELE_OFF);
  pinMode(PIN_BOTON_EMERGENCIA, INPUT_PULLUP);
  lecturaBotonCruda = digitalRead(PIN_BOTON_EMERGENCIA);
  botonEstable      = lecturaBotonCruda;
  ultimoCambioBoton = millis();

  prefs.begin("ferti", false);
  cargarConfiguracion();

  if (distanciaTotalCm > 0) { recalcularZonas(); Serial.println("  Configuracion anterior restaurada."); }
  else Serial.println("  Sin distancia configurada. Usa la app.");

  Serial.printf("  Velocidades: R=%.0f M=%.0f L=%.0f ACEL=%.0f p/s%s\n",
                velRapida, velMedia, velLenta, acel, modoFuerza?" [MODO FUERZA ACTIVO]":"");
  Serial.printf("  CM/s: R=%.1f M=%.1f L=%.1f (con %.3f p/cm)\n",
                velRapida/pasosPorCm, velMedia/pasosPorCm, velLenta/pasosPorCm, pasosPorCm);
  Serial.printf("  Actuadores: EXT=%lums REC=%lums (incluye margen %lums)\n",
                actExtenderMs, actRecogerMs, actMargenMs);
  Serial.printf("  Rueda: d=%.2fcm CM/vuelta=%.2f | Calibracion: %.3f p/cm\n",
                diametroRuedaCm, M_PI*diametroRuedaCm, pasosPorCm);

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID, AP_PASS, CANAL_WIFI);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print("  AP creado. IP: "); Serial.println(WiFi.softAPIP());
  Serial.print("  MAC Maestro: "); Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("  ERROR: ESP-NOW no inicializo");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);
  registrarPeer(MAC_ESP_A,    "ESP_A");
  registrarPeer(MAC_PANTALLA, "Pantalla");
  if (ESP_B_CONECTADA) registrarPeer(MAC_ESP_B, "ESP_B");
  if (ESP_C_CONECTADA) registrarPeer(MAC_ESP_C, "ESP_C");

  // v5.5: sincronizar velocidades y actuadores con ESP_A al arrancar
  Serial.println("  Sincronizando configuracion con ESP_A...");
  sincronizarTodoConEspA();

  configurarServidor();
  Serial.println("  Servidor web en http://192.168.4.1");
  Serial.println("\n[MAESTRO v5.5] LISTO");
  Serial.println("  WiFi: FertiTrack_AP / 123456789");
  Serial.println("  Nuevas rutas: /api/velocidades /api/actuadores /api/rueda /api/prueba_brazo\n");

  if (RECOGER_BRAZOS_AL_ARRANCAR) {
    tiempoArranque = millis();
    estadoActual   = EM_ARRANQUE;
    Serial.println("  Arranque: en 3 s se recogeran los brazos y se regresara al origen.");
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

  NodoEsperado ne = nodoEsperado;
  if (ne != NODO_NINGUNO && !respuestaLista &&
      millis() - tiempoInicioEspera > limiteEsperaMs) {
    Serial.printf("[M] Timeout (%lu ms) esperando Nodo %s\n",
                  limiteEsperaMs, ne == NODO_B ? "B" : "A");
    declararFalla(ne == NODO_B ? 2 : 1);
    return;
  }

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
    // ── SENSADO ─────────────────────────────────────────────────────
    case EM_SENSADO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_SENSADO_MIDIENDO;
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
      respuestaLista  = false;
      ultimaHumedadADC = respuestaValor;
      if (ultimaHumedadADC < ADC_MIN_VALIDO || ultimaHumedadADC > ADC_MAX_VALIDO) {
        Serial.printf("[SENSADO] Zona %d/%d ADC=%d INVALIDO\n", zonaActual+1, numZonas, ultimaHumedadADC);
        declararFalla(3);
        break;
      }
      {
        int nivel = clasificarHumedad(ultimaHumedadADC);
        clasificacionZona[zonaActual] = nivel;
        notificarPantallaEstado(nivel);
        notificarIndicadorLED(nivel);
        Serial.printf("[SENSADO] Zona %d/%d ADC=%d nivel=%d\n", zonaActual+1, numZonas, ultimaHumedadADC, nivel);
        avanzarZonaOTerminarSensado();
      }
      break;

    case EM_SENSADO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      posicionPasos  = 0;
      Serial.println("[SENSADO] Completo. Robot en origen.");
      if (encadenarRiegoTrasSensado && hayZonaSeca()) iniciarRiegoSelectivo();
      else terminarRutina();
      break;

    // ── RIEGO ────────────────────────────────────────────────────────
    case EM_RIEGO_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista  = false;
      brazoRiegoAbajo = true;
      avanzarTramoRiego(true);
      break;

    case EM_RIEGO_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista  = false;
      brazoRiegoAbajo = false;
      procesarZonaRiego();
      break;

    case EM_RIEGO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      zonaActual    += zonasEnTramo;
      procesarZonaRiego();
      break;

    case EM_RIEGO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[RIEGO] Completo. Robot en origen.");
      terminarRutina();
      break;

    // ── FERTILIZACIÓN ────────────────────────────────────────────────
    case EM_FERTI_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_ON);
      estadoActual   = EM_FERTI_AVANZANDO;
      ordenarAvance("AVANZAR_MEDIO", velMedia, distanciaTotalPasos);
      notificarPantallaActividad(3);
      notificarPantallaFase(1);
      break;

    case EM_FERTI_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_OFF);
      estadoActual   = EM_FERTI_SUBIENDO_BRAZO;
      ordenarBrazo("BRAZO_FERTI", 0);
      notificarPantallaFase(4);
      break;

    case EM_FERTI_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_FERTI_RETORNANDO;
      ordenarRetorno(posicionPasos);
      notificarPantallaActividad(4);
      notificarPantallaFase(2);
      break;

    case EM_FERTI_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[FERTI] Completo. Robot en origen.");
      notificarIndicadorLED(0);
      terminarRutina();
      break;

    // ── RECUPERACIÓN ─────────────────────────────────────────────────
    case EM_RECUP_ESPERA:
      if (millis() - inicioRecuperacion < ESPERA_RECUP_MS) break;
      estadoActual = EM_RECUP_BRAZO_RIEGO;
      ordenarBrazo("BRAZO_RIEGO", 0);
      break;

    case EM_RECUP_BRAZO_RIEGO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_RECUP_BRAZO_FERTI;
      ordenarBrazo("BRAZO_FERTI", 0);
      break;

    case EM_RECUP_BRAZO_FERTI:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_RECUP_RETORNANDO;
      notificarPantallaFase(2);
      {
        long estimada = (posicionPasos > distanciaTotalPasos) ? posicionPasos : distanciaTotalPasos;
        ordenarRetorno(estimada);
      }
      break;

    case EM_RECUP_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[M] Recuperacion completa: brazos arriba, robot en origen.");
      notificarIndicadorLED(0);
      terminarRutina();
      break;

    // ── ARRANQUE ─────────────────────────────────────────────────────
    case EM_ARRANQUE:
      if (millis() - tiempoArranque < ESPERA_ARRANQUE_MS) break;
      Serial.println("[ARRANQUE] Recogiendo brazos y regresando al origen...");
      iniciarRecuperacion();
      break;

    // ── PRUEBAS DE CALIBRACIÓN ────────────────────────────────────────
    case EM_PRUEBA_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_PRUEBA_LISTA;
      notificarPantallaActividad(0); notificarPantallaFase(0);
      Serial.println("[PRUEBA] Listo. Mide la distancia y calibra desde la app.");
      break;

    case EM_PRUEBA_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[PRUEBA] En el origen.");
      terminarRutina();
      break;

    case EM_PRUEBA_SENSOR:
      if (!respuestaLista) break;
      respuestaLista   = false;
      ultimaHumedadADC = respuestaValor;
      Serial.printf("[PRUEBA] Sensor: ADC=%d (%.0f %%)\n",
                    ultimaHumedadADC, humedadPorcentaje(ultimaHumedadADC));
      estadoActual = EM_ESPERANDO;
      notificarPantallaActividad(0);
      break;

    // NUEVO v5.5: prueba manual de brazo
    case EM_PRUEBA_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[PRUEBA_BRAZO] Completado.");
      estadoActual = EM_ESPERANDO;
      notificarPantallaActividad(0); notificarPantallaFase(0);
      break;

    case EM_ESPERANDO:
    case EM_PRUEBA_LISTA:
    case EM_EMERGENCIA:
      break;
  }
}
