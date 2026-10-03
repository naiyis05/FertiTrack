/*
 * =====================================================================
 *  FertiTrack v3 — MAESTRO  (arquitectura por zonas, monorriel)
 *  Placa : ESP32 Dev Module — Core Espressif 3.x.x
 *  MAC de ESTE ESP32 : 20:50:0D:10:1E:E0
 *
 *  CORRECCIÓN CRÍTICA v4:
 *    ESPAsyncWebServer causaba crash en ejecución con el error:
 *    "assert failed: tcp_alloc ... Required to lock TCPIP core"
 *    porque llama funciones de red desde el callback de ESP-NOW
 *    sin el lock del stack TCP/IP que exige el core 3.x.
 *    Solución: reemplazado por WebServer (incluido en el core ESP32,
 *    sin librerías externas) + server.handleClient() en el loop().
 *    La lógica de zonas, rutinas y máquina de estados es IDÉNTICA
 *    al v3 que generó la otra IA.
 *
 *  LIBRERÍAS NECESARIAS:
 *    - ArduinoJson >= 6.x  (Library Manager)
 *    - Preferences         (incluida en el core, no instalar)
 *    - WiFi, WebServer, esp_now, esp_wifi  (incluidas en el core)
 *    SIN ESPAsyncWebServer  SIN AsyncTCP
 *
 *  MACs confirmadas:
 *    MAESTRO   : 20:50:0D:10:1E:E0  (este nodo)
 *    ESP_A     : C8:85:41:2C:A5:BC
 *    ESP_B     : CC:DB:A7:34:A0:FC
 *    PANTALLA  : AC:A7:04:EA:09:74
 * =====================================================================
 */

#include <WiFi.h>
#include <WebServer.h>          // ← reemplaza ESPAsyncWebServer
#include <esp_now.h>
#include <esp_wifi.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <math.h>

// ─────────────────────────────────────────────────────────────────────
//  MÁQUINA DE ESTADOS (declarados aquí arriba, ANTES de cualquier función,
//  para evitar el bug de generación automática de prototipos de Arduino:
//  el IDE inserta los prototipos de funciones justo después de los
//  #include, así que si el enum se declaraba más abajo, el prototipo
//  autogenerado usaba un tipo todavía no declarado -> error de compilación.
// ─────────────────────────────────────────────────────────────────────
enum EstadoMaestro {
  EM_ESPERANDO,
  EM_SENSADO_AVANZANDO, EM_SENSADO_MIDIENDO, EM_SENSADO_RETORNANDO,
  EM_RIEGO_BAJANDO_BRAZO, EM_RIEGO_AVANZANDO,
  EM_RIEGO_SUBIENDO_BRAZO, EM_RIEGO_RETORNANDO,
  EM_FERTI_BAJANDO_BRAZO, EM_FERTI_AVANZANDO,
  EM_FERTI_SUBIENDO_BRAZO, EM_FERTI_RETORNANDO,
  EM_EMERGENCIA
};

enum ModoRutina   { RUTINA_NINGUNA, RUTINA_SENSADO, RUTINA_RIEGO, RUTINA_FERTILIZACION };
enum NodoEsperado { NODO_NINGUNO, NODO_A, NODO_B };

// ─────────────────────────────────────────────────────────────────────
//  RED
// ─────────────────────────────────────────────────────────────────────
const char *AP_SSID   = "FertiTrack_AP";
const char *AP_PASS   = "123456789";
const int   CANAL_WIFI = 1;

// ─────────────────────────────────────────────────────────────────────
//  PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_RELE_RIEGO        15
#define PIN_RELE_FERTI         4
#define PIN_BOTON_EMERGENCIA   5
#define RELE_ON   LOW
#define RELE_OFF  HIGH

// ─────────────────────────────────────────────────────────────────────
//  MACs DE LOS ESCLAVOS
// ─────────────────────────────────────────────────────────────────────
uint8_t MAC_ESP_A[]    = {0xC8, 0x85, 0x41, 0x2C, 0xA5, 0xBC};
uint8_t MAC_PANTALLA[] = {0xAC, 0xA7, 0x04, 0xEA, 0x09, 0x74};

const bool ESP_B_CONECTADA = true;
uint8_t MAC_ESP_B[] = {0xCC, 0xDB, 0xA7, 0x34, 0xA0, 0xFC}; // ESP_B confirmada

// ─────────────────────────────────────────────────────────────────────
//  ESTRUCTURA UNIFICADA  (idéntica en los 4 nodos)
// ─────────────────────────────────────────────────────────────────────
typedef struct {
  char emisor[10];
  char comando[20];
  int  parametro;
} MensajeFertiTrack;

MensajeFertiTrack msgEnviar;
MensajeFertiTrack msgRecibido;

// ─────────────────────────────────────────────────────────────────────
//  CONFIGURACIÓN PERSISTENTE (NVS)
// ─────────────────────────────────────────────────────────────────────
Preferences prefs;

float         distanciaTotalCm        = 0.0;
float         anchoZonaCm             = 2.5;
float         pasosPorCm              = 400.0;
int           umbralSeco              = 3900;
int           umbralHumedo            = 3200;
int           umbralSobreHumedo       = 800;
bool          cicloAutomaticoActivo   = false;
unsigned long intervaloDescansoMs     = 60000UL;

const int MAX_ZONAS = 150;
long *limitesZonaPasos  = nullptr;
int  *clasificacionZona = nullptr;
int   numZonas               = 0;
long  distanciaTotalPasos    = 0;

void guardarConfiguracion() {
  prefs.putFloat("dist_cm",    distanciaTotalCm);
  prefs.putFloat("ancho_cm",   anchoZonaCm);
  prefs.putFloat("pasos_cm",   pasosPorCm);
  prefs.putInt  ("umb_seco",   umbralSeco);
  prefs.putInt  ("umb_hum",    umbralHumedo);
  prefs.putInt  ("umb_sobre",  umbralSobreHumedo);
  prefs.putBool ("auto",       cicloAutomaticoActivo);
  prefs.putULong("descanso_ms",intervaloDescansoMs);
}

void cargarConfiguracion() {
  distanciaTotalCm      = prefs.getFloat ("dist_cm",     0.0);
  anchoZonaCm           = prefs.getFloat ("ancho_cm",    2.5);
  pasosPorCm            = prefs.getFloat ("pasos_cm",    400.0);
  umbralSeco            = prefs.getInt   ("umb_seco",    3900);
  umbralHumedo          = prefs.getInt   ("umb_hum",     3200);
  umbralSobreHumedo     = prefs.getInt   ("umb_sobre",   800);
  cicloAutomaticoActivo = prefs.getBool  ("auto",        false);
  intervaloDescansoMs   = prefs.getULong ("descanso_ms", 60000UL);
}

bool recalcularZonas() {
  if (distanciaTotalCm <= 0 || anchoZonaCm <= 0 || pasosPorCm <= 0) return false;
  int nz = (int)round(distanciaTotalCm / anchoZonaCm);
  if (nz < 1)       nz = 1;
  if (nz > MAX_ZONAS) nz = MAX_ZONAS;

  delete[] limitesZonaPasos;  limitesZonaPasos  = nullptr;
  delete[] clasificacionZona; clasificacionZona = nullptr;

  numZonas             = nz;
  distanciaTotalPasos  = (long)round((double)distanciaTotalCm * pasosPorCm);
  limitesZonaPasos     = new long[numZonas + 1];
  clasificacionZona    = new int [numZonas];

  for (int i = 0; i <= numZonas; i++)
    limitesZonaPasos[i] = (long)round(((double)distanciaTotalPasos * i) / numZonas);
  for (int i = 0; i <  numZonas; i++)
    clasificacionZona[i] = 0;

  Serial.printf("[CONFIG] %.1fcm → %d zonas (~%.2fcm) %ld pasos\n",
                distanciaTotalCm, numZonas, distanciaTotalCm / numZonas, distanciaTotalPasos);
  return true;
}

long pasosDeZona(int idx) {
  if (!limitesZonaPasos || idx < 0 || idx >= numZonas) return 0;
  return limitesZonaPasos[idx + 1] - limitesZonaPasos[idx];
}

// ─────────────────────────────────────────────────────────────────────
//  VARIABLES DE LA MÁQUINA DE ESTADOS (los enum ya se declararon arriba)
// ─────────────────────────────────────────────────────────────────────
EstadoMaestro estadoActual = EM_ESPERANDO;

ModoRutina             modoRutina   = RUTINA_NINGUNA;
volatile NodoEsperado  nodoEsperado = NODO_NINGUNO;
volatile bool          respuestaLista = false;
volatile int           respuestaValor = 0;

const unsigned long TIMEOUT_A_MS        = 8000;
const unsigned long TIMEOUT_B_MS        = 12000;
const unsigned long REFRESCO_IDLE_MS    = 5000;

unsigned long tiempoInicioEspera = 0;
unsigned long ultimoRefrescoIdle = 0;

int  zonaActual              = 0;
bool encadenarRiegoTrasSensado = false;
bool brazoRiegoAbajo         = false;
int  ultimaHumedadADC        = -1;
int  nivelSueloActual        = 0;
int  actividadActual         = 0;
bool huboFalla               = false;
int  codigoFalla             = 0;
bool esperandoProximoCiclo   = false;
unsigned long proximoCicloMillis = 0;

bool  estadoBotonAnterior    = HIGH;
unsigned long ultimoCambioBoton = 0;
const unsigned long DEBOUNCE_MS = 60;

// ─────────────────────────────────────────────────────────────────────
//  SERVIDOR WEB  (WebServer del core — sin librería externa)
// ─────────────────────────────────────────────────────────────────────
WebServer server(80);

// ─────────────────────────────────────────────────────────────────────
//  HELPERS
// ─────────────────────────────────────────────────────────────────────
const char* nombreEstado(EstadoMaestro e) {
  switch(e) {
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
    case EM_EMERGENCIA:           return "EMERGENCIA";
    default:                      return "?";
  }
}

const char* nombreRutina(ModoRutina r) {
  switch(r) {
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
//  ENVIAR A ESCLAVO
// ─────────────────────────────────────────────────────────────────────
void enviarA(uint8_t *mac, const char *cmd, int param) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor,  "MAESTRO", sizeof(msgEnviar.emisor)  - 1);
  strncpy(msgEnviar.comando, cmd,       sizeof(msgEnviar.comando) - 1);
  msgEnviar.parametro = param;
  esp_err_t r = esp_now_send(mac, (uint8_t*)&msgEnviar, sizeof(msgEnviar));
  Serial.printf("[TX] %s(%d) → %02X:%02X:%02X:%02X:%02X:%02X %s\n",
                cmd, param, mac[0],mac[1],mac[2],mac[3],mac[4],mac[5],
                r == ESP_OK ? "OK" : "ERR");
}

// ─────────────────────────────────────────────────────────────────────
//  NOTIFICAR PANTALLA
// ─────────────────────────────────────────────────────────────────────
void notificarPantallaEstado   (int n) { nivelSueloActual = n; enviarA(MAC_PANTALLA,"ESTADO_SUELO",n); }
void notificarPantallaActividad(int n) { actividadActual  = n; enviarA(MAC_PANTALLA,"ACTIVIDAD",n);    }
void notificarPantallaFalla    (int n) { enviarA(MAC_PANTALLA,"FALLA",n);                             }
void notificarPantallaRutina   (ModoRutina r) { enviarA(MAC_PANTALLA,"MODO_RUTINA",(int)r);           }
void notificarPantallaZona     (int z)        { enviarA(MAC_PANTALLA,"ZONA_ACTUAL",z);                 }
void notificarPantallaTotalZonas(int t)       { enviarA(MAC_PANTALLA,"TOTAL_ZONAS",t);                 }

// ─────────────────────────────────────────────────────────────────────
//  INDICADOR LED DE ESP_B (azul/amarillo/rojo, alimentados por ESP_B
//  pero decididos aquí, igual que todo lo demás: el Maestro es la
//  única fuente de verdad de umbrales y de qué rutina está activa)
//  Códigos (mismos que clasificarHumedad(), + 9 para fertilización):
//    0 = apagado (sin datos / durante riego)
//    1 = SECO            -> amarillo fijo
//    2 = INTERMEDIA       -> azul + amarillo fijos
//    3 = HUMEDA           -> azul fijo
//    4 = SOBRE_HUMEDA     -> azul parpadeando (lo resuelve ESP_B solo)
//    9 = FERTILIZANDO     -> rojo fijo
// ─────────────────────────────────────────────────────────────────────
void notificarIndicadorLED(int codigo) {
  if (ESP_B_CONECTADA) enviarA(MAC_ESP_B, "INDICADOR", codigo);
}

// ─────────────────────────────────────────────────────────────────────
//  TIMEOUT
// ─────────────────────────────────────────────────────────────────────
void iniciarEspera(NodoEsperado nodo) {
  nodoEsperado       = nodo;
  respuestaLista     = false;
  tiempoInicioEspera = millis();
}

bool haVencidoTimeout() {
  if (nodoEsperado == NODO_NINGUNO) return false;
  unsigned long lim = (nodoEsperado == NODO_B) ? TIMEOUT_B_MS : TIMEOUT_A_MS;
  return (millis() - tiempoInicioEspera > lim);
}

// ─────────────────────────────────────────────────────────────────────
//  FALLA / REINICIO
// ─────────────────────────────────────────────────────────────────────
void declararFalla(int cod) {
  digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
  digitalWrite(PIN_RELE_FERTI, RELE_OFF);
  enviarA(MAC_ESP_A, "STOP", 0);
  if (ESP_B_CONECTADA) enviarA(MAC_ESP_B, "STOP", 0);
  huboFalla             = true;
  codigoFalla           = cod;
  estadoActual          = EM_EMERGENCIA;
  modoRutina            = RUTINA_NINGUNA;
  nodoEsperado          = NODO_NINGUNO;
  esperandoProximoCiclo = false;
  brazoRiegoAbajo       = false;
  notificarPantallaActividad(0);
  notificarPantallaRutina(RUTINA_NINGUNA);
  notificarPantallaFalla(cod);
  Serial.printf("\n⛔ FALLA código %d\n", cod);
}

void reiniciarSistema() {
  if (estadoActual != EM_EMERGENCIA) return;
  huboFalla             = false;
  codigoFalla           = 0;
  estadoActual          = EM_ESPERANDO;
  zonaActual            = 0;
  modoRutina            = RUTINA_NINGUNA;
  brazoRiegoAbajo       = false;
  cicloAutomaticoActivo = false;   // seguridad: no arrancar solo tras emergencia
  esperandoProximoCiclo = false;
  guardarConfiguracion();
  notificarPantallaFalla(0);
  enviarA(MAC_ESP_A, "RESET", 0);
  if (ESP_B_CONECTADA) enviarA(MAC_ESP_B, "RESET", 0);
  Serial.println("[M] Sistema reiniciado.");
}

// ─────────────────────────────────────────────────────────────────────
//  MODO AUTOMÁTICO
// ─────────────────────────────────────────────────────────────────────
void programarProximoCicloSiAplica() {
  if (cicloAutomaticoActivo && distanciaTotalCm > 0) {
    esperandoProximoCiclo = true;
    proximoCicloMillis    = millis() + intervaloDescansoMs;
    Serial.printf("[AUTO] Próximo ciclo en %lus\n", intervaloDescansoMs / 1000);
  } else {
    esperandoProximoCiclo = false;
  }
}

// ─────────────────────────────────────────────────────────────────────
//  RUTINA 1 — SENSADO
// ─────────────────────────────────────────────────────────────────────
bool iniciarSensado(bool encadenarRiego) {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || numZonas < 1) return false;
  encadenarRiegoTrasSensado = encadenarRiego;
  zonaActual = 0;
  modoRutina = RUTINA_SENSADO;
  notificarPantallaRutina(RUTINA_SENSADO);
  notificarPantallaTotalZonas(numZonas);
  notificarPantallaZona(1);
  notificarPantallaActividad(4);
  notificarIndicadorLED(0); // sin datos todavía: recién arranca la pasada
  enviarA(MAC_ESP_A, "AVANZAR_MEDIO", (int)pasosDeZona(0));
  estadoActual = EM_SENSADO_AVANZANDO;
  iniciarEspera(NODO_A);
  Serial.printf("[SENSADO] Iniciado (%d zonas)\n", numZonas);
  return true;
}

void avanzarZonaOTerminarSensado() {
  zonaActual++;
  if (zonaActual >= numZonas) {
    estadoActual = EM_SENSADO_RETORNANDO;
    notificarPantallaActividad(4);
    enviarA(MAC_ESP_A, "RETORNAR", 0);
    iniciarEspera(NODO_A);
  } else {
    notificarPantallaZona(zonaActual + 1);
    notificarPantallaActividad(4);
    enviarA(MAC_ESP_A, "AVANZAR_MEDIO", (int)pasosDeZona(zonaActual));
    estadoActual = EM_SENSADO_AVANZANDO;
    iniciarEspera(NODO_A);
  }
}

// ─────────────────────────────────────────────────────────────────────
//  RUTINA 2 — RIEGO SELECTIVO
// ─────────────────────────────────────────────────────────────────────
bool iniciarRiegoSelectivo() {
  if (numZonas < 1) return false;
  zonaActual     = 0;
  modoRutina     = RUTINA_RIEGO;
  brazoRiegoAbajo= false;
  notificarPantallaRutina(RUTINA_RIEGO);
  notificarPantallaTotalZonas(numZonas);
  notificarIndicadorLED(0); // apagados durante todo el riego (decisión del usuario)
  Serial.println("[RIEGO] Iniciando riego selectivo...");
  procesarZonaRiego();
  return true;
}

void procesarZonaRiego() {
  notificarPantallaZona(zonaActual + 1);
  bool necesitaRiego = (clasificacionZona[zonaActual] == 1);

  if (necesitaRiego == brazoRiegoAbajo) {
    // El brazo ya está en la posición correcta → avanzar directamente
    long pasos = pasosDeZona(zonaActual);
    if (necesitaRiego) {
      notificarPantallaActividad(2);
      enviarA(MAC_ESP_A, "AVANZAR_LENTO", (int)pasos);
    } else {
      notificarPantallaActividad(4);
      enviarA(MAC_ESP_A, "AVANZAR_RAPIDO", (int)pasos);
    }
    estadoActual = EM_RIEGO_AVANZANDO;
    iniciarEspera(NODO_A);
  } else if (necesitaRiego && !brazoRiegoAbajo) {
    // Hay que bajar el brazo antes de avanzar
    estadoActual = EM_RIEGO_BAJANDO_BRAZO;
    enviarA(MAC_ESP_A, "BRAZO_RIEGO", 1);
    iniciarEspera(NODO_A);
  } else {
    // Hay que subir el brazo (saliendo de zona seca)
    digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
    estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
    enviarA(MAC_ESP_A, "BRAZO_RIEGO", 0);
    iniciarEspera(NODO_A);
  }
}

// ─────────────────────────────────────────────────────────────────────
//  RUTINA 3 — FERTILIZACIÓN
// ─────────────────────────────────────────────────────────────────────
bool iniciarFertilizacion() {
  if (estadoActual != EM_ESPERANDO) return false;
  if (distanciaTotalCm <= 0 || distanciaTotalPasos <= 0) return false;
  modoRutina = RUTINA_FERTILIZACION;
  notificarPantallaRutina(RUTINA_FERTILIZACION);
  notificarPantallaActividad(3);
  notificarIndicadorLED(9); // rojo fijo mientras dure la fertilización
  estadoActual = EM_FERTI_BAJANDO_BRAZO;
  enviarA(MAC_ESP_A, "BRAZO_FERTI", 1);
  iniciarEspera(NODO_A);
  Serial.println("[FERTI] Iniciando fertilización...");
  return true;
}

// ─────────────────────────────────────────────────────────────────────
//  CALLBACK ESP-NOW  (firma obligatoria Core 3.x)
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info,
                const uint8_t *incomingData, int len) {
  if (len != sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));
  String emisor  = String(msgRecibido.emisor);
  String comando = String(msgRecibido.comando);
  Serial.printf("[RX] de %s: %s (%d)\n",
                emisor.c_str(), comando.c_str(), msgRecibido.parametro);

  if (nodoEsperado == NODO_A && emisor == "ESPA" &&
      (comando == "LISTO" || comando == "BRAZO_OK")) {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado   = NODO_NINGUNO;
  }
  if (ESP_B_CONECTADA && nodoEsperado == NODO_B &&
      emisor == "ESPB" && comando == "HUMEDAD") {
    respuestaValor = msgRecibido.parametro;
    respuestaLista = true;
    nodoEsperado   = NODO_NINGUNO;
  }
}

// ─────────────────────────────────────────────────────────────────────
//  BOTÓN DE EMERGENCIA  (sin delay)
// ─────────────────────────────────────────────────────────────────────
void revisarBotonEmergencia() {
  bool lectura = digitalRead(PIN_BOTON_EMERGENCIA);
  if (lectura != estadoBotonAnterior) ultimoCambioBoton = millis();
  if ((millis() - ultimoCambioBoton) > DEBOUNCE_MS) {
    if (lectura == LOW && estadoBotonAnterior == HIGH) {
      if (estadoActual != EM_EMERGENCIA) {
        Serial.println("[M] ⛔ BOTÓN EMERGENCIA");
        declararFalla(9);
      }
    }
  }
  estadoBotonAnterior = lectura;
}

// ─────────────────────────────────────────────────────────────────────
//  PÁGINA WEB
// ─────────────────────────────────────────────────────────────────────
const char PAGINA_PRINCIPAL[] PROGMEM = R"rawliteral(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FertiTrack</title>
<style>
body{margin:0;font-family:Arial,sans-serif;background:#F5F5DC;color:#222;padding-bottom:30px}
header{background:#4CAF50;color:#fff;padding:16px;text-align:center;font-size:22px;font-weight:bold}
.tarjeta{background:#fff;margin:16px;padding:16px;border-radius:10px;box-shadow:0 2px 6px rgba(0,0,0,.15)}
.fila{display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #eee;font-size:14px}
h3{margin:0 0 10px;font-size:15px;color:#4CAF50}
input[type=number]{width:100px;padding:6px;border:1px solid #ccc;border-radius:6px;font-size:14px}
button{width:100%;padding:14px;margin:6px 0;font-size:15px;border:none;border-radius:8px;color:#fff;font-weight:bold;cursor:pointer}
.btn-ciclo{background:#2196F3}.btn-sensado{background:#03A9F4}
.btn-fumigar{background:#FF9800}.btn-detener{background:#F44336}
.btn-reiniciar{background:#4CAF50}.btn-guardar{background:#607D8B}
.aviso-falla{background:#F44336;color:#fff;padding:14px;border-radius:10px;margin:16px;text-align:center;font-weight:bold;display:none}
.mapa-zonas{display:flex;gap:3px;flex-wrap:wrap;margin-top:10px}
.zona{flex:1 1 auto;min-width:16px;height:36px;border-radius:4px;display:flex;align-items:center;justify-content:center;font-size:10px;color:#fff;font-weight:bold}
.z0{background:#bbb}.z1{background:#F44336}.z2{background:#FFC107;color:#333}.z3{background:#4CAF50}.z4{background:#2196F3}
</style>
</head>
<body>
<header>🌱 FertiTrack — Monorriel por Zonas</header>
<div id="avisoFalla" class="aviso-falla"></div>

<div class="tarjeta">
  <h3>Estado</h3>
  <div class="fila"><span>Estado</span><span id="estado">--</span></div>
  <div class="fila"><span>Rutina activa</span><span id="rutina">--</span></div>
  <div class="fila"><span>Zona</span><span id="zona">--</span></div>
  <div class="fila"><span>Humedad (ADC)</span><span id="humedad">--</span></div>
  <div class="fila"><span>Suelo</span><span id="suelo">--</span></div>
  <div class="fila"><span>Próximo ciclo</span><span id="proximo">--</span></div>
  <div class="mapa-zonas" id="mapaZonas"></div>
</div>

<div class="tarjeta">
  <h3>Configuración del recorrido</h3>
  <div class="fila"><span>Distancia A→B (cm)</span>
    <input type="number" id="distanciaCm" step="0.1" min="1" placeholder="ej. 200"></div>
  <div class="fila"><span>Ancho de zona (cm)</span>
    <input type="number" id="anchoZonaCm" step="0.1" min="0.5" placeholder="2.5"></div>
  <button class="btn-guardar" onclick="guardarConfig()">Guardar configuración</button>
</div>

<div class="tarjeta">
  <h3>Modo automático</h3>
  <div class="fila"><span><input type="checkbox" id="autoActivo"> Repetir ciclo automáticamente</span></div>
  <div class="fila"><span>Descanso entre ciclos (seg)</span>
    <input type="number" id="descansoSeg" min="5" placeholder="60"></div>
  <button class="btn-guardar" onclick="guardarAuto()">Guardar modo automático</button>
</div>

<div class="tarjeta">
  <button class="btn-ciclo"    onclick="get('/api/iniciar?modo=ciclo')">Ciclo Completo (Sensar + Regar)</button>
  <button class="btn-sensado"  onclick="get('/api/iniciar?modo=sensado')">Solo Sensar</button>
  <button class="btn-fumigar"  onclick="get('/api/iniciar?modo=fertilizar')">Iniciar Fertilización</button>
  <button class="btn-detener"  onclick="get('/api/detener')">DETENER EMERGENCIA</button>
  <button class="btn-reiniciar"onclick="get('/api/reiniciar')">Reiniciar tras falla</button>
</div>

<script>
const SUELOS=['Sin datos','Seca','Intermedia','Húmeda','Sobre-húmeda'];
function get(url){fetch(url).then(r=>r.json()).then(d=>{if(d&&d.ok===false)alert('Error: '+d.error);refrescar();}).catch(()=>{});}
function guardarConfig(){
  const d=document.getElementById('distanciaCm').value;
  const a=document.getElementById('anchoZonaCm').value;
  if(!d||d<=0){alert('Ingresa la distancia en cm');return;}
  let url='/api/configurar?distancia_cm='+d;
  if(a)url+='&ancho_zona_cm='+a;
  get(url);
}
function guardarAuto(){
  const ac=document.getElementById('autoActivo').checked?1:0;
  const sg=document.getElementById('descansoSeg').value||60;
  get('/api/auto?activar='+ac+'&descanso_seg='+sg);
}
function refrescar(){
  fetch('/api/estado').then(r=>r.json()).then(d=>{
    document.getElementById('estado').innerText=d.estado;
    document.getElementById('rutina').innerText=d.modo_rutina;
    document.getElementById('zona').innerText=d.total_zonas>0?(d.zona_actual+' / '+d.total_zonas):'--';
    document.getElementById('humedad').innerText=d.humedad_adc>=0?d.humedad_adc:'--';
    document.getElementById('suelo').innerText=SUELOS[d.nivel_suelo]||'--';
    document.getElementById('proximo').innerText=d.proximo_ciclo_seg>=0?(d.proximo_ciclo_seg+' s'):'--';
    const av=document.getElementById('avisoFalla');
    if(d.falla>0){av.style.display='block';av.innerText='FALLA (código '+d.falla+') — presiona Reiniciar';}
    else av.style.display='none';
    const mapa=document.getElementById('mapaZonas');
    mapa.innerHTML='';
    if(d.zonas){d.zonas.forEach((nv,i)=>{const div=document.createElement('div');div.className='zona z'+nv;div.innerText=i+1;mapa.appendChild(div);});}
    if(!document.getElementById('distanciaCm').dataset.tocado){
      document.getElementById('distanciaCm').value=d.distancia_cm||'';
      document.getElementById('anchoZonaCm').value=d.ancho_zona_cm?d.ancho_zona_cm.toFixed(2):'';
    }
    document.getElementById('autoActivo').checked=d.auto_activo;
    document.getElementById('descansoSeg').value=d.descanso_seg;
  }).catch(()=>{});
}
document.getElementById('distanciaCm').addEventListener('input',function(){this.dataset.tocado='1';});
setInterval(refrescar,2000);
refrescar();
</script>
</body>
</html>
)rawliteral";

// ─────────────────────────────────────────────────────────────────────
//  RUTAS DEL SERVIDOR WEB
//  DIFERENCIA CLAVE vs AsyncWebServer:
//    - Los handlers se registran con funciones normales (no lambdas con AsyncWebServerRequest*)
//    - Los parámetros GET se leen con server.arg("nombre")
//    - La respuesta se envía con server.send(código, tipo, cuerpo)
//    - server.handleClient() se llama en loop() para atender peticiones
// ─────────────────────────────────────────────────────────────────────
void ruta_raiz() {
  server.send_P(200, "text/html", PAGINA_PRINCIPAL);
}

void ruta_configurar() {
  if (estadoActual != EM_ESPERANDO) {
    server.send(409, "application/json",
                "{\"ok\":false,\"error\":\"sistema ocupado\"}");
    return;
  }
  if (!server.hasArg("distancia_cm")) {
    server.send(400, "application/json",
                "{\"ok\":false,\"error\":\"falta distancia_cm\"}");
    return;
  }
  float nd = server.arg("distancia_cm").toFloat();
  if (nd <= 0) {
    server.send(400, "application/json",
                "{\"ok\":false,\"error\":\"distancia_cm debe ser > 0\"}");
    return;
  }
  distanciaTotalCm = nd;
  if (server.hasArg("ancho_zona_cm")) {
    float az = server.arg("ancho_zona_cm").toFloat();
    if (az > 0) anchoZonaCm = az;
  }
  if (server.hasArg("umbral_seco"))         umbralSeco         = server.arg("umbral_seco").toInt();
  if (server.hasArg("umbral_humedo"))       umbralHumedo       = server.arg("umbral_humedo").toInt();
  if (server.hasArg("umbral_sobre_humedo")) umbralSobreHumedo  = server.arg("umbral_sobre_humedo").toInt();
  if (server.hasArg("pasos_cm")) {
    float pc = server.arg("pasos_cm").toFloat();
    if (pc > 0) pasosPorCm = pc;
  }
  if (!recalcularZonas()) {
    server.send(500, "application/json",
                "{\"ok\":false,\"error\":\"no se pudo calcular zonas\"}");
    return;
  }
  guardarConfiguracion();
  JsonDocument doc;
  doc["ok"]                  = true;
  doc["num_zonas"]           = numZonas;
  doc["ancho_zona_real_cm"]  = distanciaTotalCm / numZonas;
  doc["distancia_total_pasos"] = distanciaTotalPasos;
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_auto() {
  if (server.hasArg("activar"))
    cicloAutomaticoActivo = server.arg("activar").toInt() != 0;
  if (server.hasArg("descanso_seg")) {
    long sg = server.arg("descanso_seg").toInt();
    if (sg > 0) intervaloDescansoMs = (unsigned long)sg * 1000UL;
  }
  guardarConfiguracion();
  if (cicloAutomaticoActivo && estadoActual == EM_ESPERANDO && !esperandoProximoCiclo)
    programarProximoCicloSiAplica();
  if (!cicloAutomaticoActivo) esperandoProximoCiclo = false;

  JsonDocument doc;
  doc["ok"]          = true;
  doc["auto_activo"] = cicloAutomaticoActivo;
  doc["descanso_seg"]= intervaloDescansoMs / 1000;
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
    server.send(409, "application/json",
                "{\"ok\":false,\"error\":\"" + error + "\"}");
  }
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
  doc["estado"]      = nombreEstado(estadoActual);
  doc["modo_rutina"] = nombreRutina(modoRutina);
  doc["zona_actual"] = (numZonas > 0) ? (zonaActual + 1) : 0;
  doc["total_zonas"] = numZonas;
  doc["distancia_cm"]= distanciaTotalCm;
  doc["ancho_zona_cm"]= (numZonas > 0) ? (distanciaTotalCm / numZonas) : anchoZonaCm;
  doc["pasos_por_cm"]= pasosPorCm;
  doc["humedad_adc"] = ultimaHumedadADC;
  doc["nivel_suelo"] = nivelSueloActual;
  doc["actividad"]   = actividadActual;
  doc["falla"]       = huboFalla ? codigoFalla : 0;
  doc["esp_b_conectada"] = ESP_B_CONECTADA;
  doc["auto_activo"] = cicloAutomaticoActivo;
  doc["descanso_seg"]= intervaloDescansoMs / 1000;
  doc["proximo_ciclo_seg"] = esperandoProximoCiclo
    ? max(0L, (long)(proximoCicloMillis - millis()) / 1000L)
    : -1;
  JsonArray zonas = doc["zonas"].to<JsonArray>();
  for (int i = 0; i < numZonas; i++) zonas.add(clasificacionZona[i]);
  String out; serializeJson(doc, out);
  server.send(200, "application/json", out);
}

void ruta_no_encontrada() {
  server.send(404, "application/json", "{\"error\":\"ruta no encontrada\"}");
}

void configurarServidor() {
  server.on("/",              HTTP_GET, ruta_raiz);
  server.on("/api/configurar",HTTP_GET, ruta_configurar);
  server.on("/api/auto",      HTTP_GET, ruta_auto);
  server.on("/api/iniciar",   HTTP_GET, ruta_iniciar);
  server.on("/api/detener",   HTTP_GET, ruta_detener);
  server.on("/api/reiniciar", HTTP_GET, ruta_reiniciar);
  server.on("/api/estado",    HTTP_GET, ruta_estado);
  server.onNotFound(ruta_no_encontrada);
  server.begin();
}

// ─────────────────────────────────────────────────────────────────────
//  SETUP
// ─────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v3] Iniciando MAESTRO...");

  pinMode(PIN_RELE_RIEGO,        OUTPUT); digitalWrite(PIN_RELE_RIEGO,        RELE_OFF);
  pinMode(PIN_RELE_FERTI,        OUTPUT); digitalWrite(PIN_RELE_FERTI,        RELE_OFF);
  pinMode(PIN_BOTON_EMERGENCIA,  INPUT_PULLUP);
  estadoBotonAnterior = digitalRead(PIN_BOTON_EMERGENCIA);

  prefs.begin("ferti", false);
  cargarConfiguracion();
  if (distanciaTotalCm > 0) {
    recalcularZonas();
    Serial.println("Configuración anterior restaurada.");
  } else {
    Serial.println("Sin distancia configurada. Usa la app antes de iniciar un ciclo.");
  }

  // WiFi AP+STA: AP para el teléfono, STA para ESP-NOW
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(AP_SSID, AP_PASS, CANAL_WIFI);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print("  ✓ AP creado. IP: "); Serial.println(WiFi.softAPIP());
  Serial.print("  ✓ MAC Maestro: ");  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK) {
    Serial.println("  ✗ ERROR: ESP-NOW no inicializó");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);

  esp_now_peer_info_t peer = {};
  peer.channel = CANAL_WIFI;
  peer.encrypt = false;

  memcpy(peer.peer_addr, MAC_ESP_A,    6); esp_now_add_peer(&peer);
  Serial.println("  ✓ Peer ESP_A: C8:85:41:2C:A5:BC");
  memcpy(peer.peer_addr, MAC_PANTALLA, 6); esp_now_add_peer(&peer);
  Serial.println("  ✓ Peer Pantalla: AC:A7:04:EA:09:74");

  if (ESP_B_CONECTADA) {
    memcpy(peer.peer_addr, MAC_ESP_B, 6); esp_now_add_peer(&peer);
    Serial.println("  ✓ Peer ESP_B registrado");
  } else {
    Serial.println("  ⚠ ESP_B pendiente — riego automático por humedad deshabilitado");
  }

  configurarServidor();
  Serial.println("  ✓ Servidor web en http://192.168.4.1");
  Serial.println("\n[MAESTRO] LISTO");
  Serial.println("  WiFi:     FertiTrack_AP / 123456789");
  Serial.println("  App:      http://192.168.4.1\n");
}

// ─────────────────────────────────────────────────────────────────────
//  LOOP — máquina de estados no bloqueante
// ─────────────────────────────────────────────────────────────────────
void loop() {
  server.handleClient();       // ← CRÍTICO: atiende la app web
  revisarBotonEmergencia();

  // Timeout de comunicación con esclavos
  if (haVencidoTimeout()) {
    int cod = (nodoEsperado == NODO_B) ? 2 : 1;
    Serial.printf("[M] Timeout esperando Nodo %s\n", cod == 2 ? "B" : "A");
    declararFalla(cod);
    return;
  }

  // Refresco idle de pantalla
  if (estadoActual == EM_ESPERANDO &&
      millis() - ultimoRefrescoIdle > REFRESCO_IDLE_MS) {
    ultimoRefrescoIdle = millis();
    notificarPantallaActividad(0);
  }

  // Ciclo automático programado
  if (estadoActual == EM_ESPERANDO &&
      esperandoProximoCiclo && millis() >= proximoCicloMillis) {
    esperandoProximoCiclo = false;
    iniciarSensado(true);
  }

  // ── MÁQUINA DE ESTADOS ──────────────────────────────────────────
  switch (estadoActual) {

    // ── RUTINA 1: SENSADO ─────────────────────────────────────────
    case EM_SENSADO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      estadoActual   = EM_SENSADO_MIDIENDO;
      notificarPantallaActividad(1);
      if (ESP_B_CONECTADA) {
        enviarA(MAC_ESP_B, "MEDIR", 0);
        iniciarEspera(NODO_B);
      } else {
        clasificacionZona[zonaActual] = 0;
        notificarPantallaEstado(0);
        avanzarZonaOTerminarSensado();
      }
      break;

    case EM_SENSADO_MIDIENDO:
      if (!respuestaLista) break;
      respuestaLista     = false;
      ultimaHumedadADC   = respuestaValor;
      {
        int nivel = clasificarHumedad(ultimaHumedadADC);
        clasificacionZona[zonaActual] = nivel;
        notificarPantallaEstado(nivel);
        notificarIndicadorLED(nivel);
        Serial.printf("[SENSADO] Zona %d/%d ADC=%d nivel=%d\n",
                      zonaActual+1, numZonas, ultimaHumedadADC, nivel);
        avanzarZonaOTerminarSensado();
      }
      break;

    case EM_SENSADO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[SENSADO] Completo. Robot en origen.");
      if (encadenarRiegoTrasSensado && hayZonaSeca()) {
        iniciarRiegoSelectivo();
      } else {
        modoRutina   = RUTINA_NINGUNA;
        estadoActual = EM_ESPERANDO;
        notificarPantallaActividad(0);
        notificarPantallaRutina(RUTINA_NINGUNA);
        programarProximoCicloSiAplica();
      }
      break;

    // ── RUTINA 2: RIEGO SELECTIVO ─────────────────────────────────
    case EM_RIEGO_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista  = false;
      digitalWrite(PIN_RELE_RIEGO, RELE_ON);
      brazoRiegoAbajo = true;
      notificarPantallaActividad(2);
      enviarA(MAC_ESP_A, "AVANZAR_LENTO", (int)pasosDeZona(zonaActual));
      estadoActual = EM_RIEGO_AVANZANDO;
      iniciarEspera(NODO_A);
      break;

    case EM_RIEGO_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista  = false;
      brazoRiegoAbajo = false;
      if (zonaActual >= numZonas) {
        estadoActual = EM_RIEGO_RETORNANDO;
        notificarPantallaActividad(4);
        enviarA(MAC_ESP_A, "RETORNAR", 0);
        iniciarEspera(NODO_A);
      } else {
        notificarPantallaActividad(4);
        enviarA(MAC_ESP_A, "AVANZAR_RAPIDO", (int)pasosDeZona(zonaActual));
        estadoActual = EM_RIEGO_AVANZANDO;
        iniciarEspera(NODO_A);
      }
      break;

    case EM_RIEGO_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      zonaActual++;
      if (zonaActual >= numZonas) {
        if (brazoRiegoAbajo) {
          digitalWrite(PIN_RELE_RIEGO, RELE_OFF);
          estadoActual = EM_RIEGO_SUBIENDO_BRAZO;
          enviarA(MAC_ESP_A, "BRAZO_RIEGO", 0);
          iniciarEspera(NODO_A);
        } else {
          estadoActual = EM_RIEGO_RETORNANDO;
          notificarPantallaActividad(4);
          enviarA(MAC_ESP_A, "RETORNAR", 0);
          iniciarEspera(NODO_A);
        }
      } else {
        procesarZonaRiego();
      }
      break;

    case EM_RIEGO_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[RIEGO] Completo. Robot en origen.");
      modoRutina   = RUTINA_NINGUNA;
      estadoActual = EM_ESPERANDO;
      notificarPantallaActividad(0);
      notificarPantallaRutina(RUTINA_NINGUNA);
      programarProximoCicloSiAplica();
      break;

    // ── RUTINA 3: FERTILIZACIÓN ───────────────────────────────────
    case EM_FERTI_BAJANDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_ON);
      notificarPantallaActividad(3);
      enviarA(MAC_ESP_A, "AVANZAR_MEDIO", (int)distanciaTotalPasos);
      estadoActual = EM_FERTI_AVANZANDO;
      iniciarEspera(NODO_A);
      break;

    case EM_FERTI_AVANZANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      digitalWrite(PIN_RELE_FERTI, RELE_OFF);
      estadoActual = EM_FERTI_SUBIENDO_BRAZO;
      enviarA(MAC_ESP_A, "BRAZO_FERTI", 0);
      iniciarEspera(NODO_A);
      break;

    case EM_FERTI_SUBIENDO_BRAZO:
      if (!respuestaLista) break;
      respuestaLista = false;
      notificarPantallaActividad(4);
      estadoActual = EM_FERTI_RETORNANDO;
      enviarA(MAC_ESP_A, "RETORNAR", 0);
      iniciarEspera(NODO_A);
      break;

    case EM_FERTI_RETORNANDO:
      if (!respuestaLista) break;
      respuestaLista = false;
      Serial.println("[FERTI] Completo. Robot en origen.");
      modoRutina   = RUTINA_NINGUNA;
      estadoActual = EM_ESPERANDO;
      notificarPantallaActividad(0);
      notificarPantallaRutina(RUTINA_NINGUNA);
      notificarIndicadorLED(0); // apaga el rojo al terminar
      programarProximoCicloSiAplica();
      break;

    case EM_ESPERANDO:
    case EM_EMERGENCIA:
      break;
  }
}
