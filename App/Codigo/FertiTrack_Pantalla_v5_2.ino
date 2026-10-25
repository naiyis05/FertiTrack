/*
 * ============================================================================
 * FertiTrack v5.2 - PANTALLA HMI (ESP32-S3, panel RGB 800x480)
 * Placa: "Pantalla inteligente HMI ESP32-S3, LCD TFT RGB 7.0" 800x480,
 *        8MB PSRAM, 16MB flash - variante Sunton ESP32-8048S070 (touch GT911)
 *
 * MAC de ESTE ESP32 : AC:A7:04:EA:09:74
 * MAC del MAESTRO   : 20:50:0D:10:1E:E0 (no se usa: esta Pantalla es
 *                     "esclavo puramente visual", solo RECIBE)
 *
 * ============================================================================
 * CONFIGURACION OBLIGATORIA DEL IDE (Tools) ANTES DE COMPILAR:
 *  - Board            : "ESP32S3 Dev Module"
 *  - PSRAM            : "OPI PSRAM"  <-- CRITICO. El framebuffer de
 *                       800x480x16bit pesa ~750KB y NO cabe en la RAM interna.
 *                       Sin esto gfx->begin() falla o reinicia en bucle.
 *  - Flash Size       : "16MB"
 *  - Partition Scheme : cualquiera "16M Flash (...)" con >= ~3MB de APP
 *  - USB CDC On Boot  : "Enabled" (si no, no ves nada en el Monitor Serie)
 *
 * LIBRERIA (Library Manager): "GFX Library for Arduino" (moononournation).
 * El touch GT911 se lee por I2C a mano: no necesita libreria extra.
 *
 * ============================================================================
 * QUE CAMBIO EN v5.2 (comunicacion Maestro <-> Pantalla):
 *  a) COMANDO NUEVO "DIRECCION": param = 0 ninguno / 1 avanzando / 2 regresando.
 *     El Maestro v5.2 lo manda junto a cada orden de movimiento (ver parche
 *     del Maestro). Con eso la escena "En movimiento" ya no es un banner
 *     simple: es una ESCENA GRANDE con el robot colgado del riel, sus 3
 *     brazos, flecha de direccion y texto "Avanzando..." o
 *     "Regresando al origen...".
 *  b) "Censando" tambien es ahora una ESCENA GRANDE dinamica: una lupa barre
 *     la franja de cultivo, amplia la plantita bajo el lente, un haz de
 *     escaneo baja desde el sensor y hay puntos "procesando lectura".
 *  c) El protocolo anterior NO cambia: ESTADO_SUELO, ACTIVIDAD, FALLA,
 *     MODO_RUTINA, ZONA_ACTUAL y TOTAL_ZONAS se siguen manejando igual.
 *     El callback sigue sin dibujar: solo levanta banderas sucias.
 *  d) MODO_DEMO actualizado para recorrer tambien las nuevas escenas.
 *
 * RECUERDA: para uso real pon MODO_DEMO en 0.
 * ============================================================================
 */

#include <Arduino_GFX_Library.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <Wire.h>

// ---------------------------------------------------------------------------
// RED
// ---------------------------------------------------------------------------
const int CANAL_WIFI = 1;   // Debe ser el mismo en los 4 nodos

// ---------------------------------------------------------------------------
// PANEL RGB 800x480 - Sunton ESP32-8048S070 (pines verificados)
// ---------------------------------------------------------------------------
Arduino_ESP32RGBPanel *rgbpanel = new Arduino_ESP32RGBPanel(
  41 /* DE */, 40 /* VSYNC */, 39 /* HSYNC */, 42 /* PCLK */,
  14 /* R0 */, 21 /* R1 */, 47 /* R2 */, 48 /* R3 */, 45 /* R4 */,
  9  /* G0 */, 46 /* G1 */, 3  /* G2 */, 8  /* G3 */, 16 /* G4 */, 1  /* G5 */,
  15 /* B0 */, 7  /* B1 */, 6  /* B2 */, 5  /* B3 */, 4  /* B4 */,
  0 /* hsync_polarity */, 180 /* hsync_front_porch */, 30 /* hsync_pulse_width */, 16 /* hsync_back_porch */,
  0 /* vsync_polarity */, 12 /* vsync_front_porch */, 13 /* vsync_pulse_width */, 10 /* vsync_back_porch */,
  1 /* pclk_active_neg */, 12000000 /* prefer_speed */
);
Arduino_RGB_Display *gfx = new Arduino_RGB_Display(
  800 /* width */, 480 /* height */, rgbpanel, 0 /* rotation */, true /* auto_flush */
);

#define GFX_BL 2   // Pin de backlight

const int PANTALLA_ANCHO = 800;
const int PANTALLA_ALTO  = 480;

// ---------------------------------------------------------------------------
// TOUCH GT911 - lectura minima por I2C, sin libreria externa
// ---------------------------------------------------------------------------
#define HABILITAR_TOQUE 1
#define TOUCH_SDA 19
#define TOUCH_SCL 20
#define TOUCH_RST 38
#define TOUCH_ADDR 0x14   // si tu unidad no responde, probar 0x5D

bool leerToque(int &x, int &y)
{
  Wire.beginTransmission(TOUCH_ADDR);
  Wire.write(0x81);
  Wire.write(0x4E);                    // registro de estado (GT_POINT_INFO)
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)TOUCH_ADDR, (uint8_t)1) < 1) return false;
  uint8_t status = Wire.read();
  uint8_t puntos = status & 0x0F;
  if (!(status & 0x80) || puntos == 0)
  {
    if (status != 0)
    {
      Wire.beginTransmission(TOUCH_ADDR);
      Wire.write(0x81); Wire.write(0x4E); Wire.write((uint8_t)0);
      Wire.endTransmission();
    }
    return false;
  }
  Wire.beginTransmission(TOUCH_ADDR);
  Wire.write(0x81);
  Wire.write(0x50);                    // primer punto de contacto
  Wire.endTransmission(false);
  if (Wire.requestFrom((uint8_t)TOUCH_ADDR, (uint8_t)4) < 4) return false;
  uint8_t xl = Wire.read(), xh = Wire.read(), yl = Wire.read(), yh = Wire.read();
  x = xl | (xh << 8);
  y = yl | (yh << 8);
  Wire.beginTransmission(TOUCH_ADDR);
  Wire.write(0x81); Wire.write(0x4E); Wire.write((uint8_t)0);
  Wire.endTransmission();
  return true;
}

// ---------------------------------------------------------------------------
// ESTRUCTURA UNIFICADA (identica en los 4 nodos)
// ---------------------------------------------------------------------------
typedef struct {
  char emisor[10];
  char comando[20];
  int  parametro;
} MensajeFertiTrack;

MensajeFertiTrack msgRecibido;

// ---------------------------------------------------------------------------
// ESTADO RECIBIDO DEL MAESTRO
// ---------------------------------------------------------------------------
volatile int nivelSuelo  = 0;    // 0..4
volatile int actividad   = 0;    // 0..4  (0 nada,1 censando,2 regando,
                                 //        3 fertilizando,4 en movimiento)
volatile int codigoFalla = 0;    // 0 = sin falla
volatile int rutinaActual = 0;   // 0..3
volatile int zonaActual   = 0;
volatile int totalZonas   = 0;
volatile int direccion    = 0;   // NUEVO v5.2: 0 ninguna / 1 avanzando /
                                 //                    2 regresando al origen

volatile bool cambioEstado    = true;
volatile bool cambioActividad = true;
volatile bool cambioFalla       = true;
volatile bool cambioZona        = true;
volatile bool cambioDireccion   = true;   // NUEVO v5.2

unsigned long contadorMensajes = 0;

// ---------------------------------------------------------------------------
// MODO DEMO: recorre todos los estados mientras no llegue el Maestro
// ---------------------------------------------------------------------------
#define MODO_DEMO 1
volatile bool demoActivo = (MODO_DEMO != 0);

typedef struct {
  int nivel; int act; int falla; int rutina; int zona; int total; int dir;
} PasoDemo;

const PasoDemo PASOS_DEMO[] = {
  {3, 0, 0, 0, 0, 0, 0},   // humeda
  {2, 0, 0, 0, 0, 0, 0},   // intermedia
  {1, 0, 0, 0, 0, 0, 0},   // seca
  {4, 0, 0, 0, 0, 0, 0},   // sobre-humeda
  {3, 1, 0, 1, 2, 4, 0},   // CENSANDO (escena dinamica, lupa)
  {1, 2, 0, 2, 2, 4, 1},   // regando (escena grande, avanzando)
  {3, 3, 0, 3, 0, 0, 1},   // fertilizando (escena grande)
  {3, 4, 0, 1, 3, 4, 1},   // EN MOVIMIENTO avanzando
  {3, 4, 0, 1, 3, 4, 2},   // EN MOVIMIENTO regresando al origen
  {3, 0, 9, 0, 0, 0, 0},   // FALLA (boton fisico)
  {3, 0, 3, 0, 0, 0, 0},   // FALLA 3 (sensor sin lectura valida)
  {0, 0, 0, 0, 0, 0, 0}    // vuelve a "sin datos"
};
const int NUM_PASOS_DEMO = sizeof(PASOS_DEMO) / sizeof(PASOS_DEMO[0]);
unsigned long ultimoPasoDemo = 0;
int pasoDemo = 0;

// ---------------------------------------------------------------------------
// UI / TEMA
// ---------------------------------------------------------------------------
bool modoNoche = false;
bool menuAbierto = false;
bool panelAdminAbierto = false;
bool toqueActivo = false;

uint16_t colorHeader, colorFondo, colorTarjeta, colorTexto, colorTextoSuave, colorBorde;
uint16_t colorSeca, colorIntermedia, colorHumeda, colorSobreHumeda, colorSinDatos;
uint16_t colorCensando, colorRegando, colorFertilizando, colorMoviendo;
uint16_t colorVidrio, colorMadera, colorSuelo, colorFlorPastel, colorFallaFondo, colorBlush;
uint16_t colorNube, colorSol, colorLuna, colorPasto;
uint16_t NEGRO, BLANCO, ROJO_FALLA;

void configurarPaleta()
{
  NEGRO      = gfx->color565(40, 35, 45);
  BLANCO     = gfx->color565(255, 255, 255);
  ROJO_FALLA = gfx->color565(228, 70, 66);

  colorSeca       = gfx->color565(238, 105, 95);
  colorIntermedia = gfx->color565(255, 192, 70);
  colorHumeda     = gfx->color565(105, 196, 118);
  colorSobreHumeda= gfx->color565(95, 168, 235);
  colorSinDatos   = gfx->color565(160, 160, 172);
  colorCensando   = gfx->color565(105, 198, 235);
  colorRegando    = gfx->color565(125, 188, 242);
  colorFertilizando = gfx->color565(255, 172, 88);
  colorMoviendo   = gfx->color565(140, 165, 182);
  colorVidrio     = gfx->color565(200, 230, 242);
  colorMadera     = gfx->color565(188, 138, 98);
  colorFlorPastel = gfx->color565(236, 125, 198);
  colorFallaFondo = ROJO_FALLA;
  colorBlush      = gfx->color565(255, 155, 172);
  colorSol        = gfx->color565(255, 206, 60);
  colorLuna       = gfx->color565(240, 234, 200);
  colorHeader     = gfx->color565(105, 192, 138);

  if (modoNoche)
  {
    colorFondo      = gfx->color565(36, 40, 54);
    colorTarjeta    = gfx->color565(62, 68, 92);
    colorTexto      = gfx->color565(230, 230, 238);
    colorTextoSuave = gfx->color565(190, 195, 210);
    colorBorde      = gfx->color565(105, 113, 138);
    colorSuelo      = gfx->color565(45, 58, 48);
    colorNube       = gfx->color565(92, 98, 124);
    colorPasto      = gfx->color565(62, 84, 68);
  }
  else
  {
    colorFondo      = gfx->color565(250, 238, 212);
    colorTarjeta    = gfx->color565(255, 255, 255);
    colorTexto      = gfx->color565(52, 45, 48);
    colorTextoSuave = gfx->color565(105, 95, 98);
    colorBorde      = gfx->color565(205, 185, 155);
    colorSuelo      = gfx->color565(165, 212, 160);
    colorNube       = gfx->color565(255, 255, 255);
    colorPasto      = gfx->color565(145, 208, 125);
  }
}

// ---------------------------------------------------------------------------
// GEOMETRIA DE PANELES
// ---------------------------------------------------------------------------
const int HDR_ALTO = 68;

// Tarjeta de estado a la IZQUIERDA; el invernadero ocupa la DERECHA.
const int TARJETA_X = 30, TARJETA_Y = 90, TARJETA_W = 400, TARJETA_H = 250;
const int ICONO_CX  = TARJETA_X + TARJETA_W / 2;
const int ICONO_CY  = TARJETA_Y + 105;
const int ICONO_R   = 62;

const int BANNER_X = 30, BANNER_Y = 352, BANNER_W = 400, BANNER_H = 52;
const int INFO_Y = 414;
const int TIERRA_Y = 442, TIERRA_ALTO = PANTALLA_ALTO - 442;

// Overlay grande para FALLA y para las escenas grandes (censando, regando,
// fertilizando, en movimiento): ocupan casi toda la pantalla.
const int OVERLAY_X = 40, OVERLAY_Y = 86, OVERLAY_W = 720, OVERLAY_H = 296;

// Invernadero isometrico (lado derecho)
const int SL_LX = 450, SL_LY = 330, SL_TX = 610, SL_TY = 250;
const int SL_RX = 770, SL_RY = 330, SL_BX = 610, SL_BY = 410, SL_G = 14;
const int GH_LX = 500, GH_LY = 328, GH_VX = 110, GH_VY = 55, GH_H = 70, GH_RH = 40;

const int ASTRO_X = 740, ASTRO_Y = 120, NUBE_Y = 108;

// ---------------------------------------------------------------------------
// AYUDANTES DE DIBUJO
// ---------------------------------------------------------------------------
void dibujarArco(int cx, int cy, int radio, int anguloIniDeg, int anguloFinDeg, uint16_t color, int grosor)
{
  for (int a = anguloIniDeg; a <= anguloFinDeg; a += 4)
  {
    float rad = a * PI / 180.0;
    int x = cx + cos(rad) * radio;
    int y = cy + sin(rad) * radio;
    gfx->fillCircle(x, y, grosor, color);
  }
}

void textoCentrado(const char *txt, int cx, int y, int tamano, uint16_t color)
{
  int16_t x1, y1;
  uint16_t w, h;
  gfx->setTextSize(tamano);
  gfx->getTextBounds(txt, 0, 0, &x1, &y1, &w, &h);
  gfx->setTextColor(color);
  gfx->setCursor(cx - w / 2, y);
  gfx->print(txt);
}

// ---------------------------------------------------------------------------
// ICONOS DE ESTADO DEL SUELO (dibujados con primitivas, sin bitmaps)
// ---------------------------------------------------------------------------
void iconoHumeda(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  gfx->fillCircle(cx - r / 2, cy + r / 6, r / 6, colorBlush);
  gfx->fillCircle(cx + r / 2, cy + r / 6, r / 6, colorBlush);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 9, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 9, NEGRO);
  gfx->fillCircle(cx - r / 3 - 2, cy - r / 6 - 2, r / 24, BLANCO);
  gfx->fillCircle(cx + r / 3 - 2, cy - r / 6 - 2, r / 24, BLANCO);
  dibujarArco(cx, cy + r / 6, r / 2, 25, 155, NEGRO, 3);
  gfx->drawLine(cx, cy + r, cx, cy + r + 16, colorHumeda);
  gfx->drawLine(cx, cy + r + 8, cx - 12, cy + r - 2, colorHumeda);
  gfx->drawLine(cx, cy + r + 8, cx + 12, cy + r - 2, colorHumeda);
}

void iconoIntermedia(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  gfx->fillCircle(cx - r / 2, cy + r / 6, r / 7, colorBlush);
  gfx->fillCircle(cx + r / 2, cy + r / 6, r / 7, colorBlush);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 9, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 9, NEGRO);
  dibujarArco(cx, cy + r / 4, r / 3, 40, 140, NEGRO, 3);
}

void iconoSeca(int cx, int cy, int r, uint16_t color)
{
  int baseY = cy + r + 6;
  gfx->drawLine(cx - r, baseY, cx + r, baseY, colorTextoSuave);
  for (int i = -1; i <= 1; i++)
  {
    int gx = cx + i * (r / 2);
    gfx->drawLine(gx, baseY, gx - 8, baseY + 16, colorTextoSuave);
    gfx->drawLine(gx, baseY, gx + 6, baseY + 12, colorTextoSuave);
  }
  gfx->fillCircle(cx, cy, r, color);
  gfx->drawCircle(cx - r / 3, cy - r / 6, r / 7, NEGRO);
  gfx->drawCircle(cx - r / 3, cy - r / 6, r / 16, NEGRO);
  gfx->drawCircle(cx + r / 3, cy - r / 6, r / 7, NEGRO);
  gfx->drawCircle(cx + r / 3, cy - r / 6, r / 16, NEGRO);
  int by = cy + r / 3;
  gfx->drawLine(cx - r / 2, by, cx - r / 5, by - 8, NEGRO);
  gfx->drawLine(cx - r / 5, by - 8, cx, by, NEGRO);
  gfx->drawLine(cx, by, cx + r / 5, by - 8, NEGRO);
  gfx->drawLine(cx + r / 5, by - 8, cx + r / 2, by, NEGRO);
}

void iconoSobreHumeda(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  gfx->fillCircle(cx - r / 2, cy + r / 5, r / 7, colorBlush);
  gfx->fillCircle(cx + r / 2, cy + r / 5, r / 7, colorBlush);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 6, BLANCO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 6, BLANCO);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 12, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 12, NEGRO);
  gfx->fillCircle(cx, cy + r / 3, r / 8, NEGRO);
  gfx->fillCircle(cx + r / 2 + 6, cy - r / 3, 6, BLANCO);
  gfx->drawCircle(cx + r / 2 + 6, cy - r / 3, 6, colorSobreHumeda);
}

void iconoSinDatos(int cx, int cy, int r, uint16_t color)
{
  gfx->drawCircle(cx, cy, r, color);
  gfx->drawCircle(cx, cy, r - 4, color);
  textoCentrado("?", cx, cy - 14, 4, color);
}

void dibujarIconoSuelo(int nivel)
{
  gfx->fillCircle(ICONO_CX, ICONO_CY, ICONO_R + 4, colorTarjeta);
  switch (nivel)
  {
    case 1: iconoSeca(ICONO_CX, ICONO_CY, ICONO_R, colorSeca); break;
    case 2: iconoIntermedia(ICONO_CX, ICONO_CY, ICONO_R, colorIntermedia); break;
    case 3: iconoHumeda(ICONO_CX, ICONO_CY, ICONO_R, colorHumeda); break;
    case 4: iconoSobreHumeda(ICONO_CX, ICONO_CY, ICONO_R, colorSobreHumeda); break;
    default: iconoSinDatos(ICONO_CX, ICONO_CY, ICONO_R, colorSinDatos); break;
  }
}

const char *nombreEstadoSuelo(int n)
{
  switch (n)
  {
    case 1: return "Seca";
    case 2: return "Intermedia";
    case 3: return "Humeda";
    case 4: return "Sobre-Humeda";
    default: return "Sin datos";
  }
}

uint16_t colorEstadoSuelo(int n)
{
  switch (n)
  {
    case 1: return colorSeca;
    case 2: return colorIntermedia;
    case 3: return colorHumeda;
    case 4: return colorSobreHumeda;
    default: return colorSinDatos;
  }
}

const char *nombreRutina(int r)
{
  switch (r)
  {
    case 1: return "Sensado";
    case 2: return "Riego";
    case 3: return "Fertilizacion";
    default: return "En espera";
  }
}

// ---------------------------------------------------------------------------
// PANEL PRINCIPAL: "La tierra se encuentra: <estado>"
// ---------------------------------------------------------------------------
void pintarTarjetaSuelo()
{
  // Si hay falla o una escena grande activa (censando/regando/ferti/moviendo),
  // ese espacio ya esta ocupado por el overlay correspondiente.
  if (codigoFalla != 0 || actividad >= 1) return;

  gfx->fillRoundRect(TARJETA_X, TARJETA_Y, TARJETA_W, TARJETA_H, 14, colorTarjeta);
  gfx->drawRoundRect(TARJETA_X, TARJETA_Y, TARJETA_W, TARJETA_H, 14, colorBorde);
  textoCentrado("La tierra se encuentra:", TARJETA_X + TARJETA_W / 2, TARJETA_Y + 14, 2, colorTextoSuave);
  dibujarIconoSuelo(nivelSuelo);
  textoCentrado(nombreEstadoSuelo(nivelSuelo), TARJETA_X + TARJETA_W / 2, TARJETA_Y + TARJETA_H - 34,
                3, colorEstadoSuelo(nivelSuelo));
}

// ---------------------------------------------------------------------------
// ESCENA GRANDE 1: CENSANDO (lupa que barre la franja de cultivo)
// ---------------------------------------------------------------------------
int frameAnimacion = 0;
bool escenaGrandeActiva = false;

const int SCAN_SUELO_Y = OVERLAY_Y + 196;          // franja de cultivo
const int SCAN_LUPA_Y  = OVERLAY_Y + 166;          // centro del lente

void dibujarPlantita(int x, int y, int escala, uint16_t colorHoja)
{
  // tallo
  gfx->drawLine(x, y, x, y - 16 * escala, colorHoja);
  // hojas
  gfx->fillCircle(x - 5 * escala, y - 12 * escala, 4 * escala, colorHoja);
  gfx->fillCircle(x + 5 * escala, y - 12 * escala, 4 * escala, colorHoja);
  // flor
  gfx->fillCircle(x, y - 20 * escala, 4 * escala, colorFlorPastel);
}

void pintarEscenaCensando()
{
  int cx = OVERLAY_X + OVERLAY_W / 2;
  gfx->fillRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorTarjeta);
  gfx->drawRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorBorde);

  textoCentrado("Censando...", cx, OVERLAY_Y + 14, 3, colorCensando);

  // "Procesando lectura" con puntitos que avanzan
  gfx->setTextSize(2);
  gfx->setTextColor(colorTextoSuave);
  gfx->setCursor(OVERLAY_X + 30, OVERLAY_Y + 52);
  gfx->print("Midiendo humedad del suelo");

  // Sensor tipo sonda que cuelga desde arriba (centro)
  int sx = cx;
  gfx->fillRect(sx - 4, OVERLAY_Y, 8, 60, colorMarco());
  gfx->fillTriangle(sx - 10, OVERLAY_Y + 60, sx + 10, OVERLAY_Y + 60, sx, OVERLAY_Y + 82,
                    colorSeca);

  // Franja de cultivo
  int fx = OVERLAY_X + 50, fw = OVERLAY_W - 100, fh = 56;
  gfx->fillRoundRect(fx, SCAN_SUELO_Y, fw, fh, 12, colorSuelo);
  gfx->drawRoundRect(fx, SCAN_SUELO_Y, fw, fh, 12, colorBorde);
  for (int i = 0; i < 6; i++)
  {
    int px = fx + 42 + i * (fw - 84) / 5;
    dibujarPlantita(px, SCAN_SUELO_Y, 1, colorHumeda);
  }
}

void animarEscenaCensando()
{
  int cx = OVERLAY_X + OVERLAY_W / 2;
  int fx = OVERLAY_X + 50, fw = OVERLAY_W - 100;

  // 1) puntitos "procesando" (1..3 puntos en secuencia)
  int baseX = OVERLAY_X + 30 + 24 * 13;   // tras el texto (~312 px a size 2)
  gfx->fillRect(baseX + 6, OVERLAY_Y + 50, 70, 20, colorTarjeta);
  int puntos = (frameAnimacion % 3) + 1;
  for (int i = 0; i < puntos; i++)
    gfx->fillCircle(baseX + 12 + i * 16, OVERLAY_Y + 60, 4, colorCensando);

  // 2) lupa que barre de izquierda a derecha y vuelve (seno)
  float t = sin(frameAnimacion * 0.28f);
  int lx = fx + 46 + (int)((fw - 92) * (0.5f + 0.5f * t));

  // borra lupa + haz anteriores
  gfx->fillRect(fx - 30, OVERLAY_Y + 92, fw + 60, SCAN_SUELO_Y - (OVERLAY_Y + 92) + 6, colorTarjeta);

  // haz de escaneo: abanico del sensor a la lente
  uint16_t cHaz = (frameAnimacion % 2 == 0) ? colorCensando : colorVidrio;
  gfx->drawLine(cx, OVERLAY_Y + 82, lx, SCAN_LUPA_Y - 20, cHaz);
  gfx->drawLine(cx - 8, OVERLAY_Y + 78, lx - 14, SCAN_LUPA_Y - 24, cHaz);
  gfx->drawLine(cx + 8, OVERLAY_Y + 78, lx + 14, SCAN_LUPA_Y - 24, cHaz);

  // planta bajo la lupa: ampliada dentro del lente
  dibujarPlantita(lx, SCAN_LUPA_Y + 12, 2, colorHumeda);

  // lente
  gfx->fillCircle(lx, SCAN_LUPA_Y, 30, gfx->color565(225, 243, 252));
  gfx->drawCircle(lx, SCAN_LUPA_Y, 30, colorMarco());
  gfx->drawCircle(lx, SCAN_LUPA_Y, 34, colorMarco());
  // plantita ampliada DENTRO del lente (se dibuja tras el lente: "aumenta")
  dibujarPlantita(lx, SCAN_LUPA_Y + 12, 2, colorHumeda);
  // brillo del lente
  gfx->drawCircle(lx - 10, SCAN_LUPA_Y - 12, 10, BLANCO);

  // repinta la planta original que quedo bajo el lente (borrada arriba)
  for (int i = 0; i < 6; i++)
  {
    int px = fx + 42 + i * (fw - 84) / 5;
    if (abs(px - lx) > 34)
      dibujarPlantita(px, SCAN_SUELO_Y, 1, colorHumeda);
  }

  // zona actual (si hay rutina en curso)
  if (rutinaActual != 0 && totalZonas > 0)
  {
    char buf[24];
    snprintf(buf, sizeof(buf), "Zona %d/%d", zonaActual, totalZonas);
    gfx->fillRect(OVERLAY_X + 20, OVERLAY_Y + OVERLAY_H - 36, OVERLAY_W - 40, 26, colorTarjeta);
    textoCentrado(buf, cx, OVERLAY_Y + OVERLAY_H - 34, 2, colorTextoSuave);
  }

  frameAnimacion++;
}

// ---------------------------------------------------------------------------
// ESCENA GRANDE 2: REGANDO (plantita feliz en la tina)
// ---------------------------------------------------------------------------
void pintarEscenaRegando()
{
  gfx->fillRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorRegando);
  gfx->drawRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorBorde);
  int cx = OVERLAY_X + OVERLAY_W / 2;
  int tinaW = 220, tinaH = 70;
  int tinaX = cx - tinaW / 2, tinaY = OVERLAY_Y + OVERLAY_H - 90;

  gfx->fillRoundRect(tinaX, tinaY, tinaW, tinaH, 26, BLANCO);
  gfx->drawRoundRect(tinaX, tinaY, tinaW, tinaH, 26, colorBorde);
  gfx->fillRoundRect(tinaX + 8, tinaY + 18, tinaW - 16, tinaH - 26, 18, gfx->color565(165, 214, 245));

  int pcx = cx, pcy = tinaY + 2, pr = 54;
  gfx->fillCircle(pcx, pcy, pr, colorHumeda);
  gfx->fillCircle(pcx - 30, pcy + 10, 9, colorBlush);
  gfx->fillCircle(pcx + 30, pcy + 10, 9, colorBlush);
  dibujarArco(pcx - 16, pcy - 8, 10, 200, 340, NEGRO, 2);
  dibujarArco(pcx + 16, pcy - 8, 10, 200, 340, NEGRO, 2);
  dibujarArco(pcx, pcy + 6, 18, 25, 155, NEGRO, 2);
  gfx->fillTriangle(pcx, pcy - pr - 20, pcx - 10, pcy - pr, pcx + 10, pcy - pr, colorHumeda);

  textoCentrado("Regando...", cx, OVERLAY_Y + 20, 3, colorTexto);
}

void animarEscenaRegando()
{
  int cx = OVERLAY_X + OVERLAY_W / 2;
  int yTope = OVERLAY_Y + 56, yFondo = OVERLAY_Y + 150;
  for (int i = 0; i < 3; i++)
  {
    int fase = (frameAnimacion + i * 4) % 12;
    int gx = cx - 36 + i * 36;
    int gyPrev = yTope + ((fase + 11) % 12) * 8;
    int gy = yTope + fase * 8;
    gfx->fillCircle(gx, gyPrev, 6, colorRegando);
    if (gy < yFondo)
      gfx->fillCircle(gx, gy, 6, BLANCO);
  }
  frameAnimacion++;
}

// ---------------------------------------------------------------------------
// ESCENA GRANDE 3: FERTILIZANDO (triangulo de alerta amarillo + texto)
// ---------------------------------------------------------------------------
void dibujarTrianguloAlerta(bool brillante)
{
  int cx = OVERLAY_X + OVERLAY_W / 2;
  int topY = OVERLAY_Y + 26;
  uint16_t amarillo = brillante ? gfx->color565(255, 214, 10) : gfx->color565(255, 236, 110);
  gfx->fillTriangle(cx, topY, cx - 106, topY + 166, cx + 106, topY + 166, NEGRO);
  gfx->fillTriangle(cx, topY + 16, cx - 92, topY + 156, cx + 92, topY + 156, amarillo);
  gfx->fillRoundRect(cx - 8, topY + 70, 16, 54, 6, NEGRO);
  gfx->fillCircle(cx, topY + 138, 9, NEGRO);
}

void pintarEscenaFertilizando()
{
  gfx->fillRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorFertilizando);
  gfx->drawRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, NEGRO);
  dibujarTrianguloAlerta(true);
  int cx = OVERLAY_X + OVERLAY_W / 2;
  textoCentrado("Fertilizando...", cx, OVERLAY_Y + 218, 4, NEGRO);
  textoCentrado("Aplicando nutrientes a la tierra", cx, OVERLAY_Y + 262, 2, NEGRO);
}

void animarEscenaFertilizando()
{
  dibujarTrianguloAlerta(frameAnimacion % 2 == 0);
  int cx = OVERLAY_X + OVERLAY_W / 2;
  int yFondo = OVERLAY_Y + 190, paso = 22;
  for (int lado = 0; lado < 2; lado++)
  {
    int x = (lado == 0) ? cx - 170 : cx + 170;
    for (int i = 0; i < 2; i++)
    {
      int fase = (frameAnimacion + i * 4 + lado * 2) % 8;
      int yPrev = yFondo - ((fase + 7) % 8) * paso;
      int y = yFondo - fase * paso;
      gfx->fillCircle(x + (i == 0 ? 0 : 26), yPrev, 7, colorFertilizando);
      gfx->fillCircle(x + (i == 0 ? 0 : 26), y, 6, gfx->color565(70, 170, 90));
    }
  }
  frameAnimacion++;
}

// ---------------------------------------------------------------------------
// ESCENA GRANDE 4: EN MOVIMIENTO (robot colgado del riel, con direccion)
// NUEVO v5.2 - usa el comando "DIRECCION" (1=avanzando, 2=regresando)
// ---------------------------------------------------------------------------
const int RAIL_Y = OVERLAY_Y + 96;   // altura de la viga del riel

void pintarVigaRiel()
{
  int rx0 = OVERLAY_X + 36, rx1 = OVERLAY_X + OVERLAY_W - 36;
  // viga
  gfx->fillRect(rx0, RAIL_Y, rx1 - rx0, 10, colorMarco());
  gfx->drawLine(rx0, RAIL_Y - 3, rx1, RAIL_Y - 3, colorMarco());
  // colgantes/techo
  for (int x = rx0 + 10; x < rx1; x += 40)
  {
    gfx->drawLine(x, RAIL_Y - 16, x, RAIL_Y, colorMarco());
    gfx->fillRect(x - 3, RAIL_Y - 20, 6, 6, colorMarco());
  }
}

void pintarTextoMovimiento()
{
  int cx = OVERLAY_X + OVERLAY_W / 2;
  gfx->fillRect(OVERLAY_X + 20, OVERLAY_Y + 10, OVERLAY_W - 40, 36, colorTarjeta);
  if (direccion == 2)
    textoCentrado("Regresando al origen...", cx, OVERLAY_Y + 16, 3, colorMoviendo);
  else
    textoCentrado("Avanzando...", cx, OVERLAY_Y + 16, 3, colorMoviendo);

  // zona (si hay rutina en curso)
  if (rutinaActual != 0 && totalZonas > 0)
  {
    char buf[24];
    snprintf(buf, sizeof(buf), "Zona %d/%d", zonaActual, totalZonas);
    gfx->fillRect(OVERLAY_X + 20, OVERLAY_Y + OVERLAY_H - 40, OVERLAY_W - 40, 28, colorTarjeta);
    textoCentrado(buf, cx, OVERLAY_Y + OVERLAY_H - 36, 2, colorTextoSuave);
  }
}

void pintarEscenaMovimiento()
{
  gfx->fillRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorTarjeta);
  gfx->drawRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorBorde);
  pintarVigaRiel();
  pintarTextoMovimiento();
}

// Dibuja el robot en la posicion px (debajo de la viga, colgado)
void dibujarRobotRiel(int px)
{
  int bw = 120, bh = 44, by = RAIL_Y + 16;
  uint16_t cCuerpo = modoNoche ? gfx->color565(120, 145, 165) : gfx->color565(150, 175, 195);

  // ganchos que agarran la viga
  gfx->fillRect(px + 14, RAIL_Y + 10, 10, 8, colorMarco());
  gfx->fillRect(px + 96, RAIL_Y + 10, 10, 8, colorMarco());

  // cuerpo del robot (carrito)
  gfx->fillRoundRect(px, by, bw, bh, 10, cCuerpo);
  gfx->drawRoundRect(px, by, bw, bh, 10, colorMarco());
  // carita/ventana
  gfx->fillRoundRect(px + 36, by + 9, 48, 24, 7, BLANCO);
  gfx->fillCircle(px + 52, by + 21, 4, NEGRO);
  gfx->fillCircle(px + 68, by + 21, 4, NEGRO);
  // mejillas
  gfx->fillCircle(px + 42, by + 28, 3, colorBlush);
  gfx->fillCircle(px + 78, by + 28, 3, colorBlush);

  // 3 brazos colgando
  for (int i = 0; i < 3; i++)
  {
    int ax = px + 24 + i * 36;
    gfx->drawLine(ax, by + bh, ax, by + bh + 42, colorMarco());
    gfx->fillCircle(ax, by + bh + 45, 3, colorMarco());
    if (i == 0)      // brazo 1: riego (gota)
    {
      gfx->fillCircle(ax, by + bh + 54, 6, colorRegando);
      gfx->drawCircle(ax, by + bh + 54, 9, colorRegando);
    }
    else if (i == 1) // brazo 2: sensor (punta roja)
    {
      gfx->fillTriangle(ax - 7, by + bh + 62, ax + 7, by + bh + 62, ax, by + bh + 46, colorSeca);
    }
    else             // brazo 3: fertilizante (triangulito amarillo)
    {
      gfx->fillTriangle(ax - 8, by + bh + 62, ax + 8, by + bh + 62, ax, by + bh + 44, colorFertilizando);
    }
  }
}

void animarEscenaMovimiento()
{
  int rx0 = OVERLAY_X + 36, rx1 = OVERLAY_X + OVERLAY_W - 36;
  int recorrido = (rx1 - rx0) - 120;

  // borra la zona del robot (sin tocar la viga ni el texto)
  gfx->fillRect(OVERLAY_X + 8, RAIL_Y + 12, OVERLAY_W - 16, 132, colorTarjeta);

  // posicion: avanzando = izquierda->derecha, regresando = derecha->izquierda
  int fase = frameAnimacion * 7;
  int px;
  if (direccion == 2)
    px = rx1 - 120 - (fase % (recorrido + 1));
  else
    px = rx0 + (fase % (recorrido + 1));

  dibujarRobotRiel(px);

  // flecha de direccion (debajo del titulo, sobre la viga)
  int ay = RAIL_Y - 38;
  int cx = OVERLAY_X + OVERLAY_W / 2;
  gfx->fillRect(OVERLAY_X + 30, ay - 13, OVERLAY_W - 60, 26, colorTarjeta);
  if (direccion == 2)
  {
    gfx->fillTriangle(cx - 78, ay, cx - 54, ay - 12, cx - 54, ay + 12, colorMoviendo);
    gfx->fillRect(cx - 54, ay - 5, 96, 10, colorMoviendo);
  }
  else
  {
    gfx->fillTriangle(cx + 78, ay, cx + 54, ay - 12, cx + 54, ay + 12, colorMoviendo);
    gfx->fillRect(cx - 42, ay - 5, 96, 10, colorMoviendo);
  }

  frameAnimacion++;
}

// Prototipo: restaurarFondoOverlay se define mas abajo (despues del invernadero)
void restaurarFondoOverlay();

// ---------------------------------------------------------------------------
// BANNER / DISPATCHER DE ACTIVIDAD
// ---------------------------------------------------------------------------
void pintarBannerActividad()
{
  if (codigoFalla != 0) return;   // la falla manda, no se toca nada aqui

  // Escenas grandes: censando (1), regando (2), fertilizando (3), movimiento (4)
  switch (actividad)
  {
    case 1: escenaGrandeActiva = true; pintarEscenaCensando();   return;
    case 2: escenaGrandeActiva = true; pintarEscenaRegando();   return;
    case 3: escenaGrandeActiva = true; pintarEscenaFertilizando(); return;
    case 4: escenaGrandeActiva = true; pintarEscenaMovimiento();  return;
    default: break;
  }

  // actividad == 0: volver a la vista normal
  if (escenaGrandeActiva)
  {
    escenaGrandeActiva = false;
    restaurarFondoOverlay();     // declarada mas abajo (forward)
  }
  pintarTarjetaSuelo();
  gfx->fillRect(BANNER_X, BANNER_Y, BANNER_W, BANNER_H, colorFondo);   // borra banner
}

// Dibuja SOLO la parte animada de la escena activa (llamado cada 400 ms)
void animarBannerActividad()
{
  if (codigoFalla != 0) return;
  switch (actividad)
  {
    case 1: animarEscenaCensando();      return;
    case 2: animarEscenaRegando();       return;
    case 3: animarEscenaFertilizando();  return;
    case 4: animarEscenaMovimiento();    return;
    default: return;
  }
}

// ---------------------------------------------------------------------------
// LINEA DE RUTINA / ZONA
// ---------------------------------------------------------------------------
void pintarInfoZona()
{
  gfx->fillRect(30, INFO_Y, 410, 24, colorFondo);
  gfx->setTextSize(2);
  gfx->setTextColor(colorTextoSuave);
  gfx->setCursor(30, INFO_Y);
  gfx->print("Rutina: ");
  gfx->print(nombreRutina(rutinaActual));
  if (rutinaActual != 0 && totalZonas > 0)
  {
    gfx->print("  Zona ");
    gfx->print(zonaActual);
    gfx->print("/");
    gfx->print(totalZonas);
  }
}

// ---------------------------------------------------------------------------
// INVERNADERO ISOMETRICO + CIELO ANIMADO
// ---------------------------------------------------------------------------
bool ventilaAbierta = false;
int nubeX = 470;
int faseCielo = 0;

const int VENT1[8] = {538, 234, 558, 244, 569, 230, 549, 220};
const int VENT2[8] = {593, 261, 613, 271, 624, 258, 604, 248};

int ghX(float a, float b) { return (int)(GH_LX + GH_VX * a + GH_VX * b); }
int ghY(float a, float b) { return (int)(GH_LY - GH_VY * a + GH_VY * b); }

uint16_t colorMarco()
{
  return modoNoche ? gfx->color565(120, 130, 150) : gfx->color565(92, 108, 124);
}

void rellenarCuad(int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3, uint16_t c)
{
  gfx->fillTriangle(x0, y0, x1, y1, x2, y2, c);
  gfx->fillTriangle(x0, y0, x2, y2, x3, y3, c);
}

void contornoCuad(int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3, uint16_t c)
{
  gfx->drawLine(x0, y0, x1, y1, c);
  gfx->drawLine(x1, y1, x2, y2, c);
  gfx->drawLine(x2, y2, x3, y3, c);
  gfx->drawLine(x3, y3, x0, y0, c);
}

void dibujarUnaVentila(const int *q, bool abierta)
{
  uint16_t relleno;
  if (abierta)      relleno = modoNoche ? gfx->color565(30, 38, 50)  : gfx->color565(65, 98, 112);
  else              relleno = modoNoche ? gfx->color565(100, 116, 138) : gfx->color565(250, 252, 255);
  rellenarCuad(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], relleno);
  contornoCuad(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], colorMarco());
  if (abierta)
  {
    gfx->drawLine(q[6], q[7], q[4], q[5], colorMadera);
    gfx->drawLine(q[6], q[7] - 1, q[4], q[5] - 1, colorMadera);
  }
}

void dibujarNube(int x, int y)
{
  gfx->fillCircle(x - 14, y + 4, 10, colorNube);
  gfx->fillCircle(x, y - 4, 13, colorNube);
  gfx->fillCircle(x + 15, y + 4, 10, colorNube);
  gfx->fillRoundRect(x - 24, y + 2, 48, 12, 6, colorNube);
}

void dibujarAstro()
{
  gfx->fillRect(ASTRO_X - 36, ASTRO_Y - 36, 72, 72, colorFondo);
  if (modoNoche)
  {
    gfx->fillCircle(ASTRO_X, ASTRO_Y, 15, colorLuna);
    gfx->fillCircle(ASTRO_X + 7, ASTRO_Y - 4, 13, colorFondo);
    uint16_t e = (faseCielo % 2 == 0) ? colorLuna : colorTextoSuave;
    gfx->fillCircle(ASTRO_X - 28, ASTRO_Y - 22, 2, e);
    gfx->fillCircle(ASTRO_X + 26, ASTRO_Y + 24, 2, e);
    gfx->fillCircle(ASTRO_X - 24, ASTRO_Y + 26, 2, colorTextoSuave);
  }
  else
  {
    gfx->fillCircle(ASTRO_X, ASTRO_Y, 15, colorSol);
    int largo = (faseCielo % 2 == 0) ? 24 : 29;
    for (int a = 0; a < 360; a += 45)
    {
      float rad = a * PI / 180.0;
      gfx->drawLine(ASTRO_X + cos(rad) * 19, ASTRO_Y + sin(rad) * 19,
                    ASTRO_X + cos(rad) * largo, ASTRO_Y + sin(rad) * largo, colorSol);
    }
  }
}

void animarCielo()
{
  gfx->fillRect(nubeX - 26, NUBE_Y - 19, 56, 36, colorFondo);
  nubeX += 14;
  if (nubeX > 670) nubeX = 470;
  dibujarNube(nubeX, NUBE_Y);
  faseCielo++;
  dibujarAstro();
}

void pintarInvernadero()
{
  const uint16_t cMarco = colorMarco();
  const uint16_t cVidrioTras  = modoNoche ? gfx->color565(70, 84, 102)  : gfx->color565(178, 219, 235);
  const uint16_t cVidrioTecho = modoNoche ? gfx->color565(78, 92, 112)  : gfx->color565(204, 232, 243);
  const uint16_t cCama        = modoNoche ? gfx->color565(88, 68, 58)  : gfx->color565(148, 103, 72);
  const uint16_t ladoIzq      = modoNoche ? gfx->color565(92, 70, 56)  : gfx->color565(168, 118, 78);
  const uint16_t ladoDer      = modoNoche ? gfx->color565(110, 86, 68)  : gfx->color565(194, 144, 98);

  // 1) Franja inferior de cultivos
  gfx->fillRect(0, TIERRA_Y, PANTALLA_ANCHO, TIERRA_ALTO, colorSuelo);
  for (int px = 15; px < PANTALLA_ANCHO; px += 30)
  {
    gfx->fillCircle(px, TIERRA_Y + 16, 5, colorHumeda);
    gfx->fillCircle(px + 15, TIERRA_Y + 24, 4, colorFlorPastel);
  }

  // 2) Losa de pasto con borde de tierra
  rellenarCuad(SL_LX, SL_LY, SL_BX, SL_BY, SL_BX, SL_BY + SL_G, SL_LX, SL_LY + SL_G, ladoIzq);
  rellenarCuad(SL_BX, SL_BY, SL_RX, SL_RY, SL_RX, SL_RY + SL_G, SL_BX, SL_BY + SL_G, ladoDer);
  rellenarCuad(SL_LX, SL_LY, SL_TX, SL_TY, SL_RX, SL_RY, SL_BX, SL_BY, colorPasto);

  // Esquinas de la huella
  int Lx = ghX(0, 0), Ly = ghY(0, 0), Tx = ghX(1, 0), Ty = ghY(1, 0);
  int Rx = ghX(1, 1), Ry = ghY(1, 1), Bx = ghX(0, 1), By = ghY(0, 1);
  int H = GH_H;
  int M1x = ghX(0.5f, 0), M1y = ghY(0.5f, 0) - H - GH_RH;
  int M2x = ghX(0.5f, 1), M2y = ghY(0.5f, 1) - H - GH_RH;

  // 3) Muros traseros y techo (vidrio)
  rellenarCuad(Lx, Ly, Tx, Ty, Tx, Ty - H, Lx, Ly - H, cVidrioTras);
  rellenarCuad(Tx, Ty, Rx, Ry, Rx, Ry - H, Tx, Ty - H, cVidrioTras);
  gfx->fillTriangle(Lx, Ly - H, Tx, Ty - H, M1x, M1y, cVidrioTras);
  rellenarCuad(Tx, Ty - H, Rx, Ry - H, M2x, M2y, M1x, M1y, cVidrioTecho);
  rellenarCuad(Lx, Ly - H, Bx, By - H, M2x, M2y, M1x, M1y, cVidrioTecho);
  gfx->fillTriangle(Bx, By - H, Rx, Ry - H, M2x, M2y, cVidrioTecho);

  // 4) Camas de cultivo con plantitas
  for (int k = 0; k < 2; k++)
  {
    float a0 = (k == 0) ? 0.20f : 0.64f;
    float a1 = a0 + 0.16f;
    rellenarCuad(ghX(a0, 0.06f), ghY(a0, 0.06f), ghX(a1, 0.06f), ghY(a1, 0.06f),
                 ghX(a1, 0.94f), ghY(a1, 0.94f), ghX(a0, 0.94f), ghY(a0, 0.94f), cCama);
    float am = a0 + 0.08f;
    for (int i = 0; i < 7; i++)
    {
      float b = 0.12f + i * 0.126f;
      int px = ghX(am, b), py = ghY(am, b);
      gfx->fillCircle(px, py - 4, 4, colorHumeda);
      gfx->fillCircle(px - 4, py - 1, 3, colorHumeda);
      gfx->fillCircle(px + 4, py - 1, 3, colorHumeda);
      if ((i + k) % 2 == 0) gfx->fillCircle(px, py - 9, 3, colorFlorPastel);
    }
  }

  // 5) Muros frontales: solo marco
  contornoCuad(Lx, Ly, Bx, By, Bx, By - H, Lx, Ly - H, cMarco);
  contornoCuad(Bx, By, Rx, Ry, Rx, Ry - H, Bx, By - H, cMarco);
  gfx->drawLine(Lx, Ly - H / 2, Bx, By - H / 2, cMarco);
  gfx->drawLine(Bx, By - H / 2, Rx, Ry - H / 2, cMarco);
  for (int i = 1; i < 4; i++)
  {
    float t = i / 4.0f;
    gfx->drawLine(ghX(0, t), ghY(0, t), ghX(0, t), ghY(0, t) - H, cMarco);
    gfx->drawLine(ghX(t, 1), ghY(t, 1), ghX(t, 1), ghY(t, 1) - H, cMarco);
  }

  // 6) Marco del techo
  gfx->drawLine(M1x, M1y, M2x, M2y, cMarco);
  gfx->drawLine(M1x, M1y + 1, M2x, M2y + 1, cMarco);
  contornoCuad(Lx, Ly - H, Bx, By - H, M2x, M2y, M1x, M1y, cMarco);
  gfx->drawLine(Tx, Ty - H, M1x, M1y, cMarco);
  gfx->drawLine(Tx, Ty - H, Rx, Ry - H, cMarco);
  gfx->drawLine(Rx, Ry - H, M2x, M2y, cMarco);
  for (int i = 1; i < 4; i++)
  {
    float t = i / 4.0f;
    gfx->drawLine(ghX(0, t), ghY(0, t) - H,
                  M1x + (int)((M2x - M1x) * t), M1y + (int)((M2y - M1y) * t), cMarco);
  }

  // 7) Ventilas del techo
  dibujarUnaVentila(VENT1, ventilaAbierta);
  dibujarUnaVentila(VENT2, !ventilaAbierta);

  // 8) Puerta de madera en el muro frontal izquierdo
  int dx0 = ghX(0, 0.08f), dy0 = ghY(0, 0.08f);
  int dx1 = ghX(0, 0.26f), dy1 = ghY(0, 0.26f);
  int dh = 50;
  rellenarCuad(dx0, dy0, dx1, dy1, dx1, dy1 - dh, dx0, dy0 - dh, colorMadera);
  contornoCuad(dx0, dy0, dx1, dy1, dx1, dy1 - dh, dx0, dy0 - dh, cMarco);
  gfx->fillCircle(dx1 - 4, dy1 - dh / 2 - 2, 2, cMarco);

  // 9) Cielo
  dibujarAstro();
  dibujarNube(nubeX, NUBE_Y);
}

// Borra el overlay grande y repone el fondo
void restaurarFondoOverlay()
{
  gfx->fillRect(OVERLAY_X - 6, OVERLAY_Y - 6, OVERLAY_W + 12, OVERLAY_H + 12, colorFondo);
  pintarInvernadero();
}

void animarInvernadero()
{
  if (codigoFalla != 0 || actividad >= 1) return;   // hay un overlay encima
  ventilaAbierta = !ventilaAbierta;
  dibujarUnaVentila(VENT1, ventilaAbierta);
  dibujarUnaVentila(VENT2, !ventilaAbierta);
  animarCielo();
}

// ---------------------------------------------------------------------------
// ALERTA DE FALLA (superpuesta, oculta el panel normal mientras dure)
// Texto corto por codigo, sin tildes (la fuente integrada no las dibuja)
// ---------------------------------------------------------------------------
const char *descripcionFalla(int cod)
{
  switch (cod)
  {
    case 1: return "ESP_A no responde";
    case 2: return "ESP_B no responde";
    case 3: return "Sensor sin lectura valida";
    case 8: return "Detenido desde la app";
    case 9: return "Boton de emergencia";
    default: return "Falla desconocida";
  }
}

void pintarFalla()
{
  if (codigoFalla == 0)
  {
    escenaGrandeActiva = false;
    restaurarFondoOverlay();
    pintarBannerActividad();
    return;
  }
  gfx->fillRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, colorFallaFondo);
  gfx->drawRoundRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, 18, BLANCO);
  int cx = OVERLAY_X + OVERLAY_W / 2;

  gfx->fillTriangle(cx, OVERLAY_Y + 34, cx - 36, OVERLAY_Y + 96, cx + 36, OVERLAY_Y + 96, BLANCO);
  gfx->fillRect(cx - 4, OVERLAY_Y + 58, 8, 24, colorFallaFondo);
  gfx->fillCircle(cx, OVERLAY_Y + 88, 4, colorFallaFondo);

  textoCentrado("FALLA EN EL SISTEMA", cx, OVERLAY_Y + 116, 3, BLANCO);
  textoCentrado(descripcionFalla(codigoFalla), cx, OVERLAY_Y + 160, 2, BLANCO);
  textoCentrado("Verifique Administrador", cx, OVERLAY_Y + 196, 2, BLANCO);
  char buf[24];
  snprintf(buf, sizeof(buf), "Codigo: %d", codigoFalla);
  textoCentrado(buf, cx, OVERLAY_Y + 236, 2, BLANCO);
}

// ---------------------------------------------------------------------------
// ENCABEZADO (titulo, reloj de actividad, boton sol/luna, hamburguesa)
// ---------------------------------------------------------------------------
const int BTN_TEMA_X = 660, BTN_MENU_X = 740, BTN_Y = HDR_ALTO / 2;

void pintarSolLuna()
{
  int cx = BTN_TEMA_X, cy = BTN_Y;
  gfx->fillRect(cx - 20, cy - 20, 40, 40, colorHeader);
  if (modoNoche)
  {
    gfx->fillCircle(cx, cy, 12, BLANCO);
    gfx->fillCircle(cx + 6, cy - 4, 12, colorHeader);
  }
  else
  {
    gfx->fillCircle(cx, cy, 10, gfx->color565(255, 235, 59));
    for (int a = 0; a < 360; a += 45)
    {
      float rad = a * PI / 180.0;
      int x1 = cx + cos(rad) * 14, y1 = cy + sin(rad) * 14;
      int x2 = cx + cos(rad) * 19, y2 = cy + sin(rad) * 19;
      gfx->drawLine(x1, y1, x2, y2, gfx->color565(255, 235, 59));
    }
  }
}

void pintarHamburguesa()
{
  int cx = BTN_MENU_X, cy = BTN_Y;
  for (int i = -1; i <= 1; i++)
    gfx->fillRoundRect(cx - 16, cy + i * 8 - 2, 32, 4, 2, BLANCO);
}

void pintarEncabezado()
{
  gfx->fillRect(0, 0, PANTALLA_ANCHO, HDR_ALTO, colorHeader);
  gfx->setTextSize(3);
  gfx->setTextColor(BLANCO);
  gfx->setCursor(20, 22);
  gfx->print("FertiTrack");
  pintarSolLuna();
  pintarHamburguesa();
}

void pintarReloj()
{
  unsigned long s = millis() / 1000;
  int hh = (s / 3600) % 24, mm = (s / 60) % 60, ss = s % 60;
  char buf[12];
  snprintf(buf, sizeof(buf), "%02d:%02d:%02d", hh, mm, ss);
  gfx->fillRect(520, 12, 120, 30, colorHeader);
  gfx->setTextSize(2);
  gfx->setTextColor(BLANCO);
  gfx->setCursor(520, 22);
  gfx->print(buf);
}

// ---------------------------------------------------------------------------
// MENU (General / Administrador)
// ---------------------------------------------------------------------------
const int MENU_X = 560, MENU_Y = HDR_ALTO, MENU_W = 220, MENU_H = 110;

void pintarMenu()
{
  if (!menuAbierto) return;
  gfx->fillRoundRect(MENU_X, MENU_Y, MENU_W, MENU_H, 8, colorTarjeta);
  gfx->drawRoundRect(MENU_X, MENU_Y, MENU_W, MENU_H, 8, colorBorde);
  gfx->setTextSize(2);
  gfx->setTextColor(colorTexto);
  gfx->setCursor(MENU_X + 16, MENU_Y + 16);
  gfx->print("General");
  gfx->drawLine(MENU_X + 8, MENU_Y + 55, MENU_X + MENU_W - 8, MENU_Y + 55, colorBorde);
  gfx->setCursor(MENU_X + 16, MENU_Y + 70);
  gfx->print("Administrador");
}

void pintarPanelAdministrador()
{
  panelAdminAbierto = true;
  gfx->fillRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, colorTarjeta);
  gfx->drawRect(OVERLAY_X, OVERLAY_Y, OVERLAY_W, OVERLAY_H, colorBorde);
  gfx->setTextSize(2);
  gfx->setTextColor(colorTexto);
  int ly = OVERLAY_Y + 20;
  gfx->setCursor(80, ly); gfx->print("MAC Pantalla: "); gfx->print(WiFi.macAddress()); ly += 28;
  gfx->setCursor(80, ly); gfx->print("Canal WiFi: ");   gfx->print(CANAL_WIFI);            ly += 28;
  gfx->setCursor(80, ly); gfx->print("Mensajes recibidos: "); gfx->print(contadorMensajes); ly += 28;
  gfx->setCursor(80, ly); gfx->print("RAM libre (heap): ");  gfx->print(ESP.getFreeHeap() / 1024);  gfx->print(" KB"); ly += 28;
  gfx->setCursor(80, ly); gfx->print("PSRAM libre: ");      gfx->print(ESP.getFreePsram() / 1024); gfx->print(" KB"); ly += 28;
  gfx->setCursor(80, ly); gfx->print("Tiempo activo: ");    gfx->print(millis() / 1000);           gfx->print(" s");
  gfx->setTextColor(colorTextoSuave);
  gfx->setCursor(80, OVERLAY_Y + OVERLAY_H - 34);
  gfx->print("(toca la pantalla para cerrar)");
}

// ---------------------------------------------------------------------------
// MODO DEMO
// ---------------------------------------------------------------------------
void avanzarDemo()
{
  const PasoDemo &p = PASOS_DEMO[pasoDemo];
  if (p.nivel != nivelSuelo)              { nivelSuelo = p.nivel;             cambioEstado = true; }
  if (p.act != actividad)                 { actividad = p.act; frameAnimacion = 0; cambioActividad = true; }
  if (p.falla != codigoFalla)             { codigoFalla = p.falla;            cambioFalla = true; }
  if (p.rutina != rutinaActual || p.zona != zonaActual || p.total != totalZonas)
  {
    rutinaActual = p.rutina; zonaActual = p.zona; totalZonas = p.total;
    cambioZona = true;
  }
  if (p.dir != direccion)                 { direccion = p.dir;                cambioDireccion = true; }
  pasoDemo = (pasoDemo + 1) % NUM_PASOS_DEMO;
}

// ---------------------------------------------------------------------------
// REDIBUJO COMPLETO (boot y cambio de tema)
// ---------------------------------------------------------------------------
void redibujarTodo()
{
  configurarPaleta();
  gfx->fillScreen(colorFondo);
  pintarEncabezado();
  pintarInvernadero();
  if (codigoFalla != 0) pintarFalla();
  else
  {
    pintarTarjetaSuelo();
    pintarBannerActividad();
  }
  pintarInfoZona();
  if (menuAbierto) pintarMenu();
}

// ---------------------------------------------------------------------------
// CALLBACK ESP-NOW (firma Core 3.x) - SOLO guarda datos y banderas
// ---------------------------------------------------------------------------
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len)
{
  if (len != (int)sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));
  msgRecibido.emisor[sizeof(msgRecibido.emisor) - 1] = '\0';
  msgRecibido.comando[sizeof(msgRecibido.comando) - 1] = '\0';
  if (strcmp(msgRecibido.emisor, "MAESTRO") != 0) return;
  const char *cmd = msgRecibido.comando;
  int p = msgRecibido.parametro;
  contadorMensajes++;

  if (demoActivo)
  {
    // Primer mensaje REAL: apagar demo y volver al estado neutro
    demoActivo = false;
    nivelSuelo = 0; actividad = 0; codigoFalla = 0;
    rutinaActual = 0; zonaActual = 0; totalZonas = 0; direccion = 0;
    cambioEstado = true; cambioActividad = true; cambioFalla = true;
    cambioZona = true; cambioDireccion = true;
  }

  // Solo "sucio" si el valor CAMBIO (el Maestro reenvia todo cada 5 s)
  if      (strcmp(cmd, "ESTADO_SUELO") == 0) { if (nivelSuelo != p)    { nivelSuelo = p;               cambioEstado = true; } }
  else if (strcmp(cmd, "ACTIVIDAD")    == 0) { if (actividad != p)     { actividad = p; frameAnimacion = 0; cambioActividad = true; } }
  else if (strcmp(cmd, "FALLA")        == 0) { if (codigoFalla != p)   { codigoFalla = p;              cambioFalla = true; } }
  else if (strcmp(cmd, "MODO_RUTINA")  == 0) { if (rutinaActual != p)  { rutinaActual = p;             cambioZona = true; } }
  else if (strcmp(cmd, "ZONA_ACTUAL")  == 0) { if (zonaActual != p)    { zonaActual = p;               cambioZona = true; } }
  else if (strcmp(cmd, "TOTAL_ZONAS")  == 0) { if (totalZonas != p)    { totalZonas = p;               cambioZona = true; } }
  else if (strcmp(cmd, "DIRECCION")     == 0) { if (direccion != p)    { direccion = p;                cambioDireccion = true; } }
}

// ---------------------------------------------------------------------------
// TOUCH (best effort)
// ---------------------------------------------------------------------------
void revisarTacto()
{
#if HABILITAR_TOQUE
  int tx, ty;
  if (!leerToque(tx, ty))
  {
    toqueActivo = false;
    return;
  }
  if (toqueActivo) return;
  toqueActivo = true;

  if (panelAdminAbierto)
  {
    panelAdminAbierto = false;
    redibujarTodo();
    return;
  }

  if (menuAbierto)
  {
    if (tx >= MENU_X && tx <= MENU_X + MENU_W && ty >= MENU_Y && ty <= MENU_Y + 50)
    {
      menuAbierto = false;
      redibujarTodo();
    }
    else if (tx >= MENU_X && tx <= MENU_X + MENU_W && ty >= MENU_Y + 60 && ty <= MENU_Y + MENU_H)
    {
      menuAbierto = false;
      pintarPanelAdministrador();
    }
    else
    {
      menuAbierto = false;
      redibujarTodo();
    }
    return;
  }

  // Boton sol/luna
  if (tx >= BTN_TEMA_X - 22 && tx <= BTN_TEMA_X + 22 && ty >= 4 && ty <= HDR_ALTO)
  {
    modoNoche = !modoNoche;
    redibujarTodo();
    return;
  }

  // Boton hamburguesa
  if (tx >= BTN_MENU_X - 22 && tx <= BTN_MENU_X + 22 && ty >= 4 && ty <= HDR_ALTO)
  {
    menuAbierto = true;
    pintarMenu();
    return;
  }
#endif
}

// ---------------------------------------------------------------------------
// SETUP
// ---------------------------------------------------------------------------
unsigned long ultimoTicReloj = 0;
unsigned long ultimoTicAnimacion = 0;
unsigned long ultimoTicTacto = 0;
unsigned long ultimoTicInvernadero = 0;

void setup()
{
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v5.2] Iniciando PANTALLA");

  pinMode(GFX_BL, OUTPUT);
  digitalWrite(GFX_BL, HIGH);

  if (!gfx->begin())
  {
    Serial.println("ERROR: gfx->begin() fallo. Revisa PSRAM (Tools > PSRAM > OPI PSRAM).");
    while (1) delay(1000);
  }

#if HABILITAR_TOQUE
  Wire.begin(TOUCH_SDA, TOUCH_SCL);
  pinMode(TOUCH_RST, OUTPUT);
  digitalWrite(TOUCH_RST, LOW);
  delay(10);
  digitalWrite(TOUCH_RST, HIGH);
  delay(50);
#endif

  redibujarTodo();

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(CANAL_WIFI, WIFI_SECOND_CHAN_NONE);
  delay(100);
  Serial.print("MAC Pantalla: ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK)
  {
    Serial.println("ERROR: esp_now_init() fallo");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);
  // No se registra al Maestro como peer: esta Pantalla nunca envia datos.

  Serial.println("\n============================================");
  Serial.println("PANTALLA HMI v5.2 LISTA -- esperando al Maestro");
  Serial.println("============================================");
}

// ---------------------------------------------------------------------------
// LOOP - no bloqueante: banderas sucias + animaciones por millis()
// ---------------------------------------------------------------------------
void loop()
{
  if (millis() - ultimoTicTacto > 60)
  {
    ultimoTicTacto = millis();
    revisarTacto();
  }

  if (menuAbierto || panelAdminAbierto) return;

#if MODO_DEMO
  if (demoActivo && millis() - ultimoPasoDemo > 3500)
  {
    ultimoPasoDemo = millis();
    avanzarDemo();
  }
#endif

  // Pintado SOLO cuando algo cambio
  if (cambioFalla)
  {
    cambioFalla = false;
    pintarFalla();
    cambioEstado = false;
    cambioActividad = false;
  }
  else
  {
    if (cambioActividad) { cambioActividad = false; pintarBannerActividad(); }
    if (cambioEstado)    { cambioEstado = false;    pintarTarjetaSuelo(); }
  }

  // Si cambio la direccion mientras se mueve, repintar titulo/flecha
  if (cambioDireccion)
  {
    cambioDireccion = false;
    if (codigoFalla == 0 && actividad == 4) pintarEscenaMovimiento();
  }

  if (cambioZona)
  {
    cambioZona = false;
    pintarInfoZona();
    // La zona tambien se muestra DENTRO de las escenas grandes
    if (codigoFalla == 0)
    {
      if (actividad == 4) pintarEscenaMovimiento();
      else if (actividad == 1) pintarEscenaCensando();
    }
  }

  // Animaciones (cada 400 ms)
  if (millis() - ultimoTicAnimacion > 400)
  {
    ultimoTicAnimacion = millis();
    if (codigoFalla == 0) animarBannerActividad();
  }

  // Reloj de actividad (cada 1 s)
  if (millis() - ultimoTicReloj > 1000)
  {
    ultimoTicReloj = millis();
    pintarReloj();
  }

  // Invernadero / cielo (cada 1.2 s)
  if (millis() - ultimoTicInvernadero > 1200)
  {
    ultimoTicInvernadero = millis();
    animarInvernadero();
  }
}
