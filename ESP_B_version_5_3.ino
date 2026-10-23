/*
  =========================================================================
  FertiTrack_ESP_B_v5.3.ino — ESP_B (sensor de humedad + pistón),
  compañero de FertiTrack_Maestro_v5.3, ESP_A v5.2 y ESP_C v5.1
  Placa : ESP32 Dev Module — Core Espressif 3.x.x
  MAC de ESTE ESP32 (ESP_B) : CC:DB:A7:34:A0:FC
  MAC del MAESTRO           : 20:50:0D:10:1E:E0
  =========================================================================

  ROL: baja el pistón con el sensor de humedad, promedia el ADC, sube el
  pistón y le responde HUMEDAD al Maestro. Los LED indicadores YA NO están
  aquí: ahora son de ESP_C.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.3 (sensor RESISTIVO: lectura más confiable)
  -------------------------------------------------------------------
  Contexto: con un sensor resistivo, el suelo seco es un circuito abierto y
  el ADC lee ~4095. Esa lectura es VÁLIDA (el Maestro v5.3 ya no la toma
  como falla). Lo que ESP_B puede mejorar es la calidad del promedio:

  1) PROMEDIO RECORTADO en vez de promedio simple. Un sensor resistivo en
     tierra da picos (contacto intermitente con la raíz, una piedra, una
     burbuja de aire). Ahora se guardan las muestras, se ordenan y se
     descarta el 10 % más bajo y el 10 % más alto antes de promediar. Un
     par de picos ya no desplazan el resultado. Con lectura estable el
     resultado es idéntico al promedio simple.

  2) CADA MUESTRA = PROMEDIO DE 4 LECTURAS del ADC (reduce el ruido propio
     del ADC del ESP32). Cuesta ~0.5 ms por muestra, no afecta el tiempo.

  3) DIAGNÓSTICO EN EL MONITOR SERIE: además del promedio imprime mínimo,
     máximo y cantidad de muestras. Un (máx - mín) muy grande indica mal
     contacto del sensor con la tierra. Sirve para calibrar SECO / MOJADO
     (la calibración ahora se hace desde la app del Maestro).

  4) ALIMENTACIÓN OPCIONAL DEL SENSOR (PIN_SENSOR_ALIM). Un sensor resistivo
     alimentado todo el tiempo se corroe (electrólisis) en pocas semanas.
     Si conectas el VCC del sensor a un GPIO (ej. 25) en lugar de a 3.3 V y
     pones PIN_SENSOR_ALIM = 25, ESP_B lo enciende SOLO mientras mide
     (el primer segundo de asentamiento ya lo cubre). Por defecto está en
     -1 = desactivado (el sensor se queda alimentado como hasta ahora).
     Un GPIO entrega poca corriente: si tu módulo consume más de ~12 mA,
     usa un transistor.

  Sin cambios: protocolo, tiempos del ciclo (~8 s), cola, STOP/RESET,
  pistón. ADC 4095 se envía tal cual: el Maestro decide.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.2 (el STOP ahora respeta el ORDEN real de llegada)
  -------------------------------------------------------------------
  En v5.1 el STOP solo levantaba la bandera 'stopSolicitado' y NO entraba a
  la cola (a diferencia de ESP_A y ESP_C). Un RESET seguido de un STOP casi
  inmediato (botón de emergencia pulsado justo al reiniciar) salía mal: la
  bandera se consumía ANTES de que el RESET saliera de la cola, y ESP_B
  terminaba en REPOSO en vez de EMERGENCIA, con el pistón libre para bajar.

  Ahora el STOP hace las dos cosas, igual que ESP_A y ESP_C:
   - levanta la bandera: loop() lo atiende primero y el pistón se recoge al
     instante (acción inmediata, sin esperar su turno en la cola);
   - TAMBIÉN entra a la cola en su lugar: así RESET -> STOP termina en
     EMERGENCIA y STOP -> RESET termina en REPOSO, como debe ser.
  Un STOP estando ya en emergencia es idempotente (no hace nada). Esto
  importa porque el Maestro v5.2 repite el STOP 3 veces al declarar la falla
  y además lo reenvía cada 5 s mientras dure la emergencia.

  La cola pasa de 6 a 10 posiciones (igual que ESP_A) para que entren sin
  perder nada los STOP repetidos junto con las órdenes normales.

  Sin cambios de protocolo: es compatible con el resto de los nodos v5.1.

  -------------------------------------------------------------------
  QUÉ CAMBIÓ EN v5.1 (ESP_C ya existe)
  -------------------------------------------------------------------
  1) SE QUITÓ TODO EL CÓDIGO DE LEDs (pines, parpadeo, INDICADOR). Con
     ESP_C_CONECTADA = true el Maestro ya no le manda INDICADOR a ESP_B.
     Si llegara uno de todos modos (por ejemplo, si alguien pone
     ESP_C_CONECTADA = false en el Maestro), se IGNORA con un aviso: no
     hay nada que encender. Quedan libres los GPIO 25, 32 y 33.

  2) DESCARTE DE LAS PRIMERAS LECTURAS. Al clavar el sensor en la tierra,
     el primer segundo es de asentamiento y baja el promedio. Ahora las
     lecturas de los primeros TIEMPO_DESCARTE_MS (1 s) NO se suman. El
     tiempo total del ciclo NO cambia (el promedio se calcula con ~4 s de
     muestras, ~40 lecturas), así que el timeout del Maestro (12 s) sigue
     igual de holgado.

  3) STOP MÁS ROBUSTO. El Maestro ahora repite el STOP 3 veces (~30 ms).
     Antes, cada STOP repetido volvía a ejecutar la emergencia y vaciaba
     la cola. Ahora un STOP estando ya en emergencia no hace nada, y la
     cola ya no se vacía: en emergencia todo lo que no sea RESET se ignora
     igualmente, así que un RESET legítimo nunca se pierde.

  4) La cola se vacía completa en cada vuelta del loop() (antes, un
     comando por vuelta).

  5) Heredado de v5 (sin cambios): comandos procesados en loop() y no en
     el callback (cola con portMUX), respuesta con reintento SIN duplicar,
     pistón como PRIMERA acción de setup(), WiFi.setSleep(false).

  -------------------------------------------------------------------
  PROTOCOLO
  -------------------------------------------------------------------
  Maestro -> ESP_B:
    "MEDIR"      parametro = 0 -> baja el pistón, promedia el ADC, sube el
                                  pistón y responde HUMEDAD.
    "STOP"       parada de emergencia: recoge el pistón.
    "RESET"      vuelve a REPOSO: recoge el pistón.
    ("INDICADOR" ya no se usa: se ignora.)
  ESP_B -> Maestro:
    "HUMEDAD"    parametro = ADC promedio (0-4095)
                 0 = no hubo lecturas válidas. El Maestro valida el valor:
                 ADC 0 o >= 4090 -> FALLA 3 (sensor desconectado/en corto).

  TIEMPOS: bajar (TIEMPO_PISTON_MS) + promediar (TIEMPO_PROMEDIO_MS, que
  incluye el descarte) + subir (TIEMPO_PISTON_MS) = ~8 s. El Maestro le da
  12 s (TIMEOUT_B_MS). Si cambias estos tiempos, la suma debe quedar por
  debajo de ~11 s.

  HARDWARE
    PIN_PISTON_RELE    = 26  IN del módulo de relé de 1 canal.
                              Relé SIN corriente -> pistón DISPARADO (baja)
                              Relé CON corriente -> pistón RECOGIDO (sube)
    PIN_SENSOR_HUMEDAD = 34  ADC1, solo entrada. Valor ALTO = suelo SECO.
    PIN_SENSOR_ALIM    = -1  (opcional) GPIO que alimenta el sensor solo al medir.
  NOTA DE HARDWARE: el relé queda energizado TODO el reposo (pistón
  recogido) y el pistón baja si ESP_B se reinicia o pierde alimentación.
  Revisa el ciclo de trabajo del solenoide/relé y si te conviene cablear
  normalmente cerrado o normalmente abierto. Mientras el ESP32 arranca
  (~0.3-0.5 s) el pin flota y el pistón puede bajar un instante; si te
  molesta, pon una resistencia pull-up/pull-down en IN (según tu módulo)
  para que el estado por defecto sea "recogido".

  CALIBRACIÓN
   - TIEMPO_PISTON_MS: mide cuánto tarda tu pistón en bajar y en subir.
   - Umbrales de humedad: este nodo imprime el promedio ADC en cada
     medición. Los puntos SECO y MOJADO y los porcentajes se calibran desde
     la app del Maestro (tarjeta "Calibrar sensor humedad"); aquí no hay
     que tocar nada.
   - RELE_ACTIVADO / RELE_APAGADO: si tu relé es "activo en HIGH",
     invierte esas macros.
  =========================================================================
*/

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

// ─────────────────────────────────────────────────────────────────────
// TIPOS (arriba de todo: evita el bug de prototipos automáticos del IDE)
// ─────────────────────────────────────────────────────────────────────
enum EstadoB {
  EB_REPOSO,          // esperando la orden "MEDIR"
  EB_BAJANDO_PISTON,  // pistón extendiéndose hacia la tierra
  EB_MIDIENDO,        // pistón abajo, promediando el ADC
  EB_SUBIENDO_PISTON, // pistón retrayéndose
  EB_EMERGENCIA       // detenido por STOP, esperando RESET
};

typedef struct {
  char emisor[10];  // "MAESTRO", "ESPA", "ESPB", "ESPC", "PANTALLA"
  char comando[20]; // "MEDIR", "HUMEDAD", "STOP", "RESET"
  int parametro;    // 0, o ADC promedio (0-4095)
} MensajeFertiTrack;

// Comando recibido, ya copiado fuera del callback
struct ComandoRx {
  char cmd[20];
  int param;
};

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const int CANAL_WIFI = 1; // el mismo en los 5 nodos

// ─────────────────────────────────────────────────────────────────────
// PINES
// ─────────────────────────────────────────────────────────────────────
#define PIN_PISTON_RELE 26    // IN del módulo de relé de 1 canal (pistón)
#define PIN_SENSOR_HUMEDAD 34 // ADC1_CH6 (solo lectura)
#define PIN_SENSOR_ALIM -1    // GPIO que alimenta el sensor solo al medir; -1 = desactivado

// Polaridad del módulo de relé (los módulos azules baratos suelen ser
// "activos en LOW"). Si el tuyo es al revés, invierte estas dos macros.
#define RELE_ACTIVADO LOW   // con corriente -> pistón RECOGIDO (sube)
#define RELE_APAGADO HIGH   // sin corriente -> pistón DISPARADO (baja)

// ─────────────────────────────────────────────────────────────────────
// TIEMPOS DEL CICLO DE MEDICIÓN
// ─────────────────────────────────────────────────────────────────────
const unsigned long TIEMPO_PISTON_MS = 1500;     // MIDE tu pistón real
const unsigned long TIEMPO_PROMEDIO_MS = 5000;   // duración total de la etapa de medición
const unsigned long TIEMPO_DESCARTE_MS = 1000;   // primeras lecturas: asentamiento, NO se suman
const unsigned long INTERVALO_MUESTRA_MS = 100;  // ~40 muestras útiles en 4 s
const int LECTURAS_POR_MUESTRA = 4;              // lecturas del ADC promediadas en cada muestra
const int MAX_MUESTRAS = 64;                     // tope de la tabla (con 4 s / 100 ms sobran)
const int RECORTE_PCT = 10;                      // % bajo y % alto que se descartan al promediar

// ─────────────────────────────────────────────────────────────────────
// MAESTRO
// ─────────────────────────────────────────────────────────────────────
uint8_t MAC_MAESTRO[] = {0x20, 0x50, 0x0D, 0x10, 0x1E, 0xE0};

// ─────────────────────────────────────────────────────────────────────
// ESTADO
// ─────────────────────────────────────────────────────────────────────
MensajeFertiTrack msgEnviar;

EstadoB estadoActual = EB_REPOSO;

unsigned long tiempoInicioEtapa = 0;
unsigned long ultimaMuestraMillis = 0;
int muestras[MAX_MUESTRAS];
int cantidadMuestras = 0;
int ultimaLecturaPromedio = -1;

volatile bool stopSolicitado = false; // la pone el callback

// Cola de comandos: el callback escribe, el loop() lee (protegida con portMUX)
const int COLA_CMD_MAX = 10; // STOP x3 + refrescos + órdenes normales caben de sobra
ComandoRx colaCmd[COLA_CMD_MAX];
int colaIni = 0;
volatile int colaCuenta = 0;
volatile bool colaDesbordada = false;
portMUX_TYPE muxCola = portMUX_INITIALIZER_UNLOCKED;

// ─────────────────────────────────────────────────────────────────────
// PISTÓN (relé de 1 canal, retorno por resorte)
//   accion  1 = disparar/bajar (entierra el sensor) -> relé SIN corriente
//          -1 = recoger/subir                       -> relé CON corriente
//           0 = sin cambio (un relé solo tiene 2 estados: el que ya tiene
//               puesto sostiene la posición, no hay que tocar el pin)
// ─────────────────────────────────────────────────────────────────────
void actuarPiston(int accion) {
  if (accion == 1) digitalWrite(PIN_PISTON_RELE, RELE_APAGADO);
  else if (accion == -1) digitalWrite(PIN_PISTON_RELE, RELE_ACTIVADO);
}

// ─────────────────────────────────────────────────────────────────────
// SENSOR DE HUMEDAD (resistivo: ADC alto = seco)
// ─────────────────────────────────────────────────────────────────────
void alimentarSensor(bool encendido) {
#if PIN_SENSOR_ALIM >= 0
  digitalWrite(PIN_SENSOR_ALIM, encendido ? HIGH : LOW);
#else
  (void)encendido;
#endif
}

// Una muestra = promedio de varias lecturas seguidas (baja el ruido del ADC)
int leerMuestraADC() {
  long suma = 0;
  for (int i = 0; i < LECTURAS_POR_MUESTRA; i++) suma += analogRead(PIN_SENSOR_HUMEDAD);
  return (int)(suma / LECTURAS_POR_MUESTRA);
}

// Promedio RECORTADO: ordena las muestras y descarta el RECORTE_PCT % más bajo
// y más alto (picos por mal contacto). Con menos de 10 muestras no recorta.
int promedioRecortado(int n, int &minimo, int &maximo) {
  if (n <= 0) { minimo = 0; maximo = 0; return 0; }
  int t[MAX_MUESTRAS];
  for (int i = 0; i < n; i++) t[i] = muestras[i];
  for (int i = 1; i < n; i++) { // ordenamiento por inserción (n <= 64)
    int v = t[i], j = i - 1;
    while (j >= 0 && t[j] > v) { t[j + 1] = t[j]; j--; }
    t[j + 1] = v;
  }
  minimo = t[0];
  maximo = t[n - 1];
  int k = (n >= 10) ? (n * RECORTE_PCT) / 100 : 0;
  long suma = 0;
  int cuenta = 0;
  for (int i = k; i < n - k; i++) { suma += t[i]; cuenta++; }
  return (cuenta > 0) ? (int)(suma / cuenta) : 0;
}

// ─────────────────────────────────────────────────────────────────────
// RESPUESTA AL MAESTRO (reintenta si NO pudo encolar; nunca duplica)
// ─────────────────────────────────────────────────────────────────────
void enviarAMaestro(const char *comando, int parametro) {
  memset(&msgEnviar, 0, sizeof(msgEnviar));
  strncpy(msgEnviar.emisor, "ESPB", sizeof(msgEnviar.emisor) - 1);
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
// INICIO DE UN CICLO DE MEDICIÓN
// ─────────────────────────────────────────────────────────────────────
void iniciarMedicion() {
  if (estadoActual != EB_REPOSO) {
    Serial.println("[ESP_B] MEDIR ignorado: ocupado o en emergencia.");
    return;
  }
  cantidadMuestras = 0;
  actuarPiston(1); // dispara / baja
  tiempoInicioEtapa = millis();
  estadoActual = EB_BAJANDO_PISTON;
  Serial.println("[ESP_B] MEDIR recibido. Bajando piston...");
}

// ─────────────────────────────────────────────────────────────────────
// EMERGENCIA Y RESET
// ─────────────────────────────────────────────────────────────────────
void ejecutarEmergencia() {
  stopSolicitado = false;
  if (estadoActual == EB_EMERGENCIA) return; // STOP repetido por el Maestro: ya está hecho

  // Con un solo relé no existe "frenar en el sitio": ante una emergencia lo
  // más seguro es RECOGER el pistón (sacar el sensor de la tierra).
  // La cola NO se vacía: en emergencia todo salvo RESET se ignora, y así un
  // RESET que llegue justo después nunca se pierde.
  actuarPiston(-1);
  alimentarSensor(false);
  estadoActual = EB_EMERGENCIA;
  Serial.println("[ESP_B] *** EMERGENCIA: piston recogido. Esperando RESET. ***");
}

void reiniciarNodo() {
  actuarPiston(-1); // asegurar posición recogida antes de aceptar órdenes
  alimentarSensor(false);
  cantidadMuestras = 0;
  estadoActual = EB_REPOSO;
  Serial.println("[ESP_B] RESET. Listo en REPOSO.");
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

  // STOP en su lugar de la cola (la bandera ya lo ejecutó al instante; esto es
  // idempotente y solo asegura el orden frente a un RESET cercano).
  if (strcmp(c.cmd, "STOP") == 0) {
    ejecutarEmergencia();
    return;
  }

  if (strcmp(c.cmd, "RESET") == 0) {
    reiniciarNodo();
    return;
  }

  // En emergencia, cualquier otra orden se ignora hasta RESET.
  if (estadoActual == EB_EMERGENCIA) {
    Serial.println("[ESP_B] En EMERGENCIA: comando ignorado hasta RESET.");
    return;
  }

  if (strcmp(c.cmd, "MEDIR") == 0) {
    iniciarMedicion();
  } else if (strcmp(c.cmd, "INDICADOR") == 0) {
    Serial.println("[ESP_B] INDICADOR ignorado: los LED ahora los maneja ESP_C.");
  } else {
    Serial.printf("[ESP_B] Comando desconocido: \"%s\"\n", c.cmd);
  }
}

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW (firma Core 3.x). Corre en la tarea del WiFi:
// NO toca pistón ni estado; solo encola (o levanta la bandera STOP).
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len) {
  (void)info;
  if (len != (int)sizeof(MensajeFertiTrack)) return;

  MensajeFertiTrack m;
  memcpy(&m, incomingData, sizeof(m));
  m.emisor[sizeof(m.emisor) - 1] = '\0';
  m.comando[sizeof(m.comando) - 1] = '\0';

  if (strcmp(m.emisor, "MAESTRO") != 0) return; // solo el Maestro manda

  // STOP: la bandera lo detiene al instante (loop() la mira primero) y además
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
  // PISTÓN PRIMERO: lo antes posible a "recogido", antes del Serial y de
  // cualquier delay (ver nota de hardware en la cabecera sobre el arranque).
  pinMode(PIN_PISTON_RELE, OUTPUT);
  actuarPiston(-1);
#if PIN_SENSOR_ALIM >= 0
  pinMode(PIN_SENSOR_ALIM, OUTPUT);
#endif
  alimentarSensor(false);

  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.3] Iniciando ESP_B (sensor + piston)...");

  analogReadResolution(12);       // 0-4095, igual que espera el Maestro
  analogSetAttenuation(ADC_11db); // rango completo ~0-3.3 V

  // Solo modo estación: ESP_B no crea AP, solo habla ESP-NOW con el Maestro.
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print(" MAC de este ESP_B: "); Serial.println(WiFi.macAddress());
  Serial.println(" (debe ser CC:DB:A7:34:A0:FC)");

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

  Serial.println(" ESP_B listo. Esperando ordenes del Maestro.\n");
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

  // 2) Toda la cola, en orden de llegada (si entra un STOP a mitad, se corta)
  ComandoRx c;
  while (!stopSolicitado && sacarComando(c)) ejecutarComando(c);
  if (colaDesbordada) {
    colaDesbordada = false;
    Serial.println("[ESP_B] AVISO: cola de comandos llena, se descarto un comando.");
  }

  // 3) Máquina de estados de la medición
  switch (estadoActual) {
    case EB_BAJANDO_PISTON:
      if (millis() - tiempoInicioEtapa >= TIEMPO_PISTON_MS) {
        actuarPiston(0); // ya llegó abajo (el relé sin corriente lo sostiene)
        alimentarSensor(true); // (opcional) el sensor solo se alimenta mientras mide
        cantidadMuestras = 0;
        ultimaMuestraMillis = millis();
        tiempoInicioEtapa = millis();
        estadoActual = EB_MIDIENDO;
        Serial.println("[ESP_B] Piston abajo. Midiendo (se descarta el 1er segundo)...");
      }
      break;

    case EB_MIDIENDO:
      if (millis() - ultimaMuestraMillis >= INTERVALO_MUESTRA_MS) {
        ultimaMuestraMillis = millis();
        int lectura = leerMuestraADC();
        // Lecturas de asentamiento: se leen pero NO se guardan
        if (millis() - tiempoInicioEtapa >= TIEMPO_DESCARTE_MS && cantidadMuestras < MAX_MUESTRAS) {
          muestras[cantidadMuestras++] = lectura;
        }
      }
      if (millis() - tiempoInicioEtapa >= TIEMPO_PROMEDIO_MS) {
        int minimo, maximo;
        ultimaLecturaPromedio = promedioRecortado(cantidadMuestras, minimo, maximo);
        alimentarSensor(false);
        actuarPiston(-1); // recoger / subir
        tiempoInicioEtapa = millis();
        estadoActual = EB_SUBIENDO_PISTON;
        Serial.printf("[ESP_B] ADC promedio recortado=%d (min=%d max=%d, %d muestras utiles). Subiendo piston...\n",
                      ultimaLecturaPromedio, minimo, maximo, cantidadMuestras);
      }
      break;

    case EB_SUBIENDO_PISTON:
      if (millis() - tiempoInicioEtapa >= TIEMPO_PISTON_MS) {
        actuarPiston(0); // ya llegó arriba (el relé con corriente lo sostiene)
        estadoActual = EB_REPOSO;
        Serial.println("[ESP_B] Piston arriba. En reposo.");
        enviarAMaestro("HUMEDAD", ultimaLecturaPromedio);
      }
      break;

    case EB_REPOSO:
    case EB_EMERGENCIA:
      break; // se espera la próxima orden del Maestro
  }
}
