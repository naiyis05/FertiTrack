/*
 * =====================================================================
 * FertiTrack v3 — PANTALLA HMI (ESP32-S3, panel RGB 800x480)
 * Placa comprada: "Pantalla inteligente HMI ESP32-S3, LCD TFT RGB 7.0",
 *                 800x480, 8MB PSRAM, 16MB flash — variante Sunton
 *                 ESP32-8048S070 (touch GT911).
 *
 * MAC de ESTE ESP32:  AC:A7:04:EA:09:74
 * MAC del MAESTRO:    20:50:0D:10:1E:E0  (no se usa para enviar: esta
 *                      Pantalla es "esclavo puramente visual", solo
 *                      RECIBE — no necesita registrar peers para eso).
 *
 * *** CONFIGURACIÓN OBLIGATORIA DEL IDE (Tools) ANTES DE COMPILAR ***
 *   Board            : "ESP32S3 Dev Module"
 *   PSRAM            : "OPI PSRAM"   <-- CRÍTICO. El framebuffer de
 *                       800x480x16bit pesa ~750KB y NO cabe en la RAM
 *                       interna del S3. Sin esto, gfx->begin() falla o
 *                       el equipo se reinicia en bucle.
 *   Flash Size       : "16MB"
 *   Partition Scheme : cualquiera "16M Flash (...)" con al menos ~3MB
 *                       de APP (el sketch + librería gráfica pesan).
 *   USB CDC On Boot  : "Enabled"  (si no, no verás nada en el Monitor
 *                       Serie por el puerto USB-C nativo del S3).
 *
 * LIBRERÍA NECESARIA (Library Manager):
 *   "GFX Library for Arduino"  (autor: moononournation)  → da acceso a
 *   #include <Arduino_GFX_Library.h>
 *   (El touch NO necesita librería aparte: se lee por I2C a mano más
 *   abajo, para no sumar una dependencia extra que pueda fallar.)
 *
 * *** DECISIONES DE DISEÑO QUE DEBES CONOCER ***
 *  1) PINES RGB: verificados contra una configuración confirmada por
 *     otros usuarios para ESTA MISMA placa (Sunton ESP32-8048S070),
 *     traducida del formato LovyanGFX al formato Arduino_GFX. Si tu
 *     unidad es una variante distinta y la imagen sale con colores
 *     cruzados o corrida, ese es el primer sitio a revisar.
 *  2) RELOJ: esta Pantalla NO tiene RTC ni acceso a internet (el
 *     SoftAP del Maestro es una red cerrada), así que no hay forma de
 *     saber la hora real. El reloj del encabezado muestra el TIEMPO
 *     ACTIVO (HH:MM:SS desde que encendió), no la hora del día.
 *  3) DÍA/NOCHE: sin hora real ni sensor de luz, el cambio automático
 *     amanecer→atardecer del prompt original no es posible con este
 *     hardware. Se implementó como un botón manual (icono sol/luna)
 *     que alterna el tema en cualquier momento.
 *  4) MENÚ "ADMINISTRADOR": el concepto original (acceso oculto vía
 *     Bluetooth SOLO desde el celular del creador) es una app de
 *     celular aparte — no algo que este panel pueda auto-proteger por
 *     sí mismo. Aquí "Administrador" abre un panel de diagnóstico en
 *     el propio panel (MAC, canal, mensajes recibidos, uptime, RAM
 *     libre) que es útil para ti como creador; la protección real por
 *     Bluetooth queda pendiente para cuando exista esa app.
 *  5) TOUCH: lectura GT911 "best effort" (sondeo por I2C, sin pin de
 *     interrupción — igual que en la config. confirmada de esta placa,
 *     que tampoco lo usa). Si el touch no responde en tu unidad,
 *     TODO EL RESTO DE LA PANTALLA sigue funcionando igual: el touch
 *     solo controla el menú, nunca bloquea el dibujo de estados.
 *     Si no detecta toques, probar cambiando TOUCH_ADDR a 0x5D.
 *
 * COMANDOS QUE RECIBE DEL MAESTRO (idénticos a notificarPantalla* del
 * Maestro v4):
 *   "ESTADO_SUELO" param = 0 sin datos / 1 seca / 2 intermedia /
 *                           3 húmeda / 4 sobre-húmeda
 *   "ACTIVIDAD"    param = 0 nada / 1 censando / 2 regando /
 *                           3 fertilizando / 4 en movimiento
 *   "FALLA"        param = 0 limpiar / 1 timeout ESP_A / 2 timeout
 *                           ESP_B / 8 detenido desde app / 9 botón físico
 *   "MODO_RUTINA"  param = 0 ninguna / 1 sensado / 2 riego /
 *                           3 fertilización
 *   "ZONA_ACTUAL"  param = número de zona actual (1-based)
 *   "TOTAL_ZONAS"  param = número total de zonas del recorrido
 *
 * REGLA DE DISEÑO (misma que el resto del proyecto): el callback de
 * ESP-NOW SOLO guarda datos y levanta "banderas sucias" — nunca dibuja
 * directamente ahí. El dibujo ocurre en loop(), igual que el Maestro
 * evita hacer trabajo pesado dentro del callback ESP-NOW.
 * =====================================================================
 */

#include <Arduino_GFX_Library.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <Wire.h>

// ─────────────────────────────────────────────────────────────────────
// RED
// ─────────────────────────────────────────────────────────────────────
const int CANAL_WIFI = 1; // Debe ser el mismo en los 4 nodos

// ─────────────────────────────────────────────────────────────────────
// PANEL RGB 800x480 — Sunton ESP32-8048S070 (ver nota de diseño #1)
// ─────────────────────────────────────────────────────────────────────
Arduino_ESP32RGBPanel *rgbpanel = new Arduino_ESP32RGBPanel(
    41 /* DE */, 40 /* VSYNC */, 39 /* HSYNC */, 42 /* PCLK */,
    14 /* R0 */, 21 /* R1 */, 47 /* R2 */, 48 /* R3 */, 45 /* R4 */,
    9 /* G0 */, 46 /* G1 */, 3 /* G2 */, 8 /* G3 */, 16 /* G4 */, 1 /* G5 */,
    15 /* B0 */, 7 /* B1 */, 6 /* B2 */, 5 /* B3 */, 4 /* B4 */,
    0 /* hsync_polarity */, 80 /* hsync_front_porch */, 4 /* hsync_pulse_width */, 16 /* hsync_back_porch */,
    0 /* vsync_polarity */, 22 /* vsync_front_porch */, 13 /* vsync_pulse_width */, 10 /* vsync_back_porch */,
    1 /* pclk_active_neg */, 14000000 /* prefer_speed */
);

Arduino_RGB_Display *gfx = new Arduino_RGB_Display(
    800 /* width */, 480 /* height */, rgbpanel, 0 /* rotation */, true /* auto_flush */
);

#define GFX_BL 2 // Pin de backlight

const int PANTALLA_ANCHO = 800;
const int PANTALLA_ALTO = 480;

// ─────────────────────────────────────────────────────────────────────
// TOUCH GT911 — lectura mínima por I2C, sin librería externa
// ─────────────────────────────────────────────────────────────────────
#define HABILITAR_TOQUE 1
#define TOUCH_SDA 19
#define TOUCH_SCL 20
#define TOUCH_RST 38
#define TOUCH_ADDR 0x14 // si tu unidad no responde, probar 0x5D

bool leerToque(int &x, int &y)
{
  Wire.beginTransmission(TOUCH_ADDR);
  Wire.write(0x81);
  Wire.write(0x4E); // registro de estado (GT_POINT_INFO)
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
  Wire.write(0x50); // primer punto de contacto
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

// ─────────────────────────────────────────────────────────────────────
// ESTRUCTURA UNIFICADA (idéntica en los 4 nodos)
// ─────────────────────────────────────────────────────────────────────
typedef struct
{
  char emisor[10];
  char comando[20];
  int parametro;
} MensajeFertiTrack;

MensajeFertiTrack msgRecibido;

// ─────────────────────────────────────────────────────────────────────
// ESTADO RECIBIDO DEL MAESTRO
// ─────────────────────────────────────────────────────────────────────
volatile int nivelSuelo = 0;    // 0..4
volatile int actividad = 0;     // 0..4
volatile int codigoFalla = 0;   // 0 = sin falla
volatile int rutinaActual = 0;  // 0..3
volatile int zonaActual = 0;
volatile int totalZonas = 0;

volatile bool cambioEstado = true;
volatile bool cambioActividad = true;
volatile bool cambioFalla = true;
volatile bool cambioZona = true;

unsigned long contadorMensajes = 0;

// ─────────────────────────────────────────────────────────────────────
// UI / TEMA
// ─────────────────────────────────────────────────────────────────────
bool modoNoche = false;
bool menuAbierto = false;

uint16_t colorHeader, colorFondo, colorTarjeta, colorTexto, colorTextoSuave, colorBorde;
uint16_t colorSeca, colorIntermedia, colorHumeda, colorSobreHumeda, colorSinDatos;
uint16_t colorCensando, colorRegando, colorFertilizando, colorMoviendo;
uint16_t NEGRO, BLANCO, ROJO_FALLA;

void configurarPaleta()
{
  NEGRO = gfx->color565(0, 0, 0);
  BLANCO = gfx->color565(255, 255, 255);
  ROJO_FALLA = gfx->color565(220, 40, 40);

  colorSeca = gfx->color565(244, 67, 54);
  colorIntermedia = gfx->color565(255, 179, 0);
  colorHumeda = gfx->color565(76, 175, 80);
  colorSobreHumeda = gfx->color565(33, 150, 243);
  colorSinDatos = gfx->color565(158, 158, 158);

  colorCensando = gfx->color565(3, 169, 244);
  colorRegando = gfx->color565(33, 150, 243);
  colorFertilizando = gfx->color565(255, 152, 0);
  colorMoviendo = gfx->color565(120, 144, 156);

  colorHeader = gfx->color565(76, 175, 80);

  if (modoNoche)
  {
    colorFondo = gfx->color565(24, 28, 38);
    colorTarjeta = gfx->color565(42, 48, 62);
    colorTexto = gfx->color565(235, 235, 240);
    colorTextoSuave = gfx->color565(160, 165, 175);
    colorBorde = gfx->color565(60, 66, 80);
  }
  else
  {
    colorFondo = gfx->color565(245, 245, 220);
    colorTarjeta = gfx->color565(255, 255, 255);
    colorTexto = gfx->color565(35, 35, 35);
    colorTextoSuave = gfx->color565(110, 110, 110);
    colorBorde = gfx->color565(210, 205, 180);
  }
}

// ─────────────────────────────────────────────────────────────────────
// GEOMETRÍA DE PANELES (todo referenciado desde aquí para que sea
// fácil de reacomodar sin tener que perseguir números por el código)
// ─────────────────────────────────────────────────────────────────────
const int HDR_ALTO = 68;

const int TARJETA_X = 150, TARJETA_Y = 96, TARJETA_W = 500, TARJETA_H = 210;
const int ICONO_CX = TARJETA_X + TARJETA_W / 2;
const int ICONO_CY = TARJETA_Y + 95;
const int ICONO_R = 62;

const int BANNER_X = 60, BANNER_Y = 322, BANNER_W = 680, BANNER_H = 56;
const int INFO_Y = 392;
const int TIERRA_Y = 420, TIERRA_ALTO = PANTALLA_ALTO - 420;

// ─────────────────────────────────────────────────────────────────────
// AYUDANTES DE DIBUJO
// ─────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────
// ICONOS DE ESTADO DEL SUELO (dibujados con primitivas, sin bitmaps)
// ─────────────────────────────────────────────────────────────────────
void iconoHumeda(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 10, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 10, NEGRO);
  dibujarArco(cx, cy + r / 6, r / 2, 25, 155, NEGRO, 3); // sonrisa amplia
  gfx->drawLine(cx, cy + r, cx, cy + r + 16, color);
  gfx->drawLine(cx, cy + r + 8, cx - 12, cy + r - 2, color);
  gfx->drawLine(cx, cy + r + 8, cx + 12, cy + r - 2, color);
}

void iconoIntermedia(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 10, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 10, NEGRO);
  dibujarArco(cx, cy + r / 4, r / 3, 40, 140, NEGRO, 3); // sonrisa leve
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
  // ojos "mareados" (espirales simples)
  gfx->drawCircle(cx - r / 3, cy - r / 6, r / 7, NEGRO);
  gfx->drawCircle(cx - r / 3, cy - r / 6, r / 16, NEGRO);
  gfx->drawCircle(cx + r / 3, cy - r / 6, r / 7, NEGRO);
  gfx->drawCircle(cx + r / 3, cy - r / 6, r / 16, NEGRO);
  // boca ondulada (desmayo)
  int by = cy + r / 3;
  gfx->drawLine(cx - r / 2, by, cx - r / 5, by - 8, NEGRO);
  gfx->drawLine(cx - r / 5, by - 8, cx, by, NEGRO);
  gfx->drawLine(cx, by, cx + r / 5, by - 8, NEGRO);
  gfx->drawLine(cx + r / 5, by - 8, cx + r / 2, by, NEGRO);
}

void iconoSobreHumeda(int cx, int cy, int r, uint16_t color)
{
  gfx->fillCircle(cx, cy, r, color);
  // ojos grandes y asustados
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 6, BLANCO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 6, BLANCO);
  gfx->fillCircle(cx - r / 3, cy - r / 6, r / 12, NEGRO);
  gfx->fillCircle(cx + r / 3, cy - r / 6, r / 12, NEGRO);
  // boca abierta en "O"
  gfx->fillCircle(cx, cy + r / 3, r / 8, NEGRO);
  // gota de sudor
  gfx->fillCircle(cx + r / 2 + 6, cy - r / 3, 6, BLANCO);
}

void iconoSinDatos(int cx, int cy, int r, uint16_t color)
{
  gfx->drawCircle(cx, cy, r, color);
  gfx->drawCircle(cx, cy, r - 4, color);
  textoCentrado("?", cx, cy - 14, 4, color);
}

void dibujarIconoSuelo(int nivel)
{
  gfx->fillCircle(ICONO_CX, ICONO_CY, ICONO_R + 4, colorTarjeta); // borra icono anterior
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

// ─────────────────────────────────────────────────────────────────────
// PANEL PRINCIPAL: "La tierra se encuentra: <estado>"
// ─────────────────────────────────────────────────────────────────────
void pintarTarjetaSuelo()
{
  gfx->fillRoundRect(TARJETA_X, TARJETA_Y, TARJETA_W, TARJETA_H, 14, colorTarjeta);
  gfx->drawRoundRect(TARJETA_X, TARJETA_Y, TARJETA_W, TARJETA_H, 14, colorBorde);
  textoCentrado("La tierra se encuentra:", TARJETA_X + TARJETA_W / 2, TARJETA_Y + 14, 2, colorTextoSuave);
  dibujarIconoSuelo(nivelSuelo);
  textoCentrado(nombreEstadoSuelo(nivelSuelo), TARJETA_X + TARJETA_W / 2, TARJETA_Y + TARJETA_H - 34,
                3, colorEstadoSuelo(nivelSuelo));
}

// ─────────────────────────────────────────────────────────────────────
// BANNER DE ACTIVIDAD (Censando / Regando / Fertilizando / En movimiento)
// ─────────────────────────────────────────────────────────────────────
int frameAnimacion = 0;

void pintarBannerActividad()
{
  gfx->fillRect(BANNER_X, BANNER_Y, BANNER_W, BANNER_H, colorFondo); // borra
  if (actividad == 0 || codigoFalla != 0) return;

  uint16_t colorBanner;
  const char *texto;
  switch (actividad)
  {
  case 1: colorBanner = colorCensando; texto = "Censando..."; break;
  case 2: colorBanner = colorRegando; texto = "Regando..."; break;
  case 3: colorBanner = colorFertilizando; texto = "Fertilizando..."; break;
  default: colorBanner = colorMoviendo; texto = "En movimiento..."; break;
  }

  gfx->fillRoundRect(BANNER_X, BANNER_Y, BANNER_W, BANNER_H, 10, colorBanner);
  textoCentrado(texto, BANNER_X + BANNER_W / 2, BANNER_Y + 18, 2, BLANCO);
}

// Dibuja SOLO la parte animada del banner (llamado periódicamente)
void animarBannerActividad()
{
  if (actividad == 0 || codigoFalla != 0) return;
  int iconoX = BANNER_X + 30, iconoY = BANNER_Y + BANNER_H / 2;
  uint16_t colorBanner;
  switch (actividad)
  {
  case 1: colorBanner = colorCensando; break;
  case 2: colorBanner = colorRegando; break;
  case 3: colorBanner = colorFertilizando; break;
  default: colorBanner = colorMoviendo; break;
  }
  gfx->fillCircle(iconoX, iconoY, 14, colorBanner); // repinta el fondo del icono

  switch (actividad)
  {
  case 1: // censando: puntos que aparecen en secuencia
  {
    int puntos = (frameAnimacion % 3) + 1;
    for (int i = 0; i < puntos; i++)
      gfx->fillCircle(iconoX - 8 + i * 8, iconoY, 3, BLANCO);
    break;
  }
  case 2: // regando: gota cayendo
  {
    int gy = iconoY - 10 + (frameAnimacion % 4) * 7;
    gfx->fillCircle(iconoX, gy, 5, BLANCO);
    break;
  }
  case 3: // fertilizando: triángulo de alerta parpadeante
  {
    if (frameAnimacion % 2 == 0)
      gfx->fillTriangle(iconoX, iconoY - 10, iconoX - 10, iconoY + 8, iconoX + 10, iconoY + 8, BLANCO);
    else
      gfx->drawTriangle(iconoX, iconoY - 10, iconoX - 10, iconoY + 8, iconoX + 10, iconoY + 8, BLANCO);
    break;
  }
  default: // en movimiento: carrito deslizándose
  {
    int cx = iconoX - 10 + (frameAnimacion % 5) * 5;
    gfx->fillRoundRect(cx - 8, iconoY - 4, 16, 8, 2, BLANCO);
    break;
  }
  }
  frameAnimacion++;
}

// ─────────────────────────────────────────────────────────────────────
// LÍNEA DE RUTINA / ZONA
// ─────────────────────────────────────────────────────────────────────
void pintarInfoZona()
{
  gfx->fillRect(60, INFO_Y, 680, 24, colorFondo);
  gfx->setTextSize(2);
  gfx->setTextColor(colorTextoSuave);
  gfx->setCursor(60, INFO_Y);
  gfx->print("Rutina: ");
  gfx->print(nombreRutina(rutinaActual));
  if (totalZonas > 0)
  {
    gfx->print("   Zona ");
    gfx->print(zonaActual);
    gfx->print("/");
    gfx->print(totalZonas);
  }
}

// ─────────────────────────────────────────────────────────────────────
// FRANJA DECORATIVA DE INVERNADERO (solo dibujo vectorial, sin bitmaps)
// ─────────────────────────────────────────────────────────────────────
void pintarInvernadero()
{
  gfx->fillRect(0, TIERRA_Y, PANTALLA_ANCHO, TIERRA_ALTO, modoNoche ? gfx->color565(20, 30, 22) : gfx->color565(200, 224, 180));
  // silueta simple de invernadero a la izquierda
  int bx = 40, by = TIERRA_Y + TIERRA_ALTO - 6, bw = 140, bh = 46;
  gfx->drawRect(bx, by - bh, bw, bh, colorTextoSuave);
  gfx->drawTriangle(bx - 6, by - bh, bx + bw / 2, by - bh - 26, bx + bw + 6, by - bh, colorTextoSuave);
  for (int i = 1; i < 5; i++)
    gfx->drawLine(bx, by - bh + i * (bh / 5), bx + bw, by - bh + i * (bh / 5), colorBorde);
  // hileras de cultivo a la derecha
  for (int row = 0; row < 3; row++)
  {
    int ry = by - 4 - row * 12;
    for (int px = 260; px < PANTALLA_ANCHO - 40; px += 26)
      gfx->fillCircle(px, ry, 5, gfx->color565(60, 140, 70));
  }
}

// ─────────────────────────────────────────────────────────────────────
// ALERTA DE FALLA (superpuesta, oculta el panel normal mientras dure)
// ─────────────────────────────────────────────────────────────────────
void pintarFalla()
{
  if (codigoFalla == 0)
  {
    // limpiar overlay y restaurar la vista normal
    gfx->fillRect(TARJETA_X - 10, TARJETA_Y - 10, TARJETA_W + 20, TARJETA_H + 20, colorFondo);
    pintarTarjetaSuelo();
    pintarBannerActividad();
    return;
  }
  int x = TARJETA_X - 10, y = TARJETA_Y - 10, w = TARJETA_W + 20, h = TARJETA_H + 30;
  gfx->fillRoundRect(x, y, w, h, 14, ROJO_FALLA);
  gfx->drawRoundRect(x, y, w, h, 14, BLANCO);
  textoCentrado("¡FALLA EN EL SISTEMA!", x + w / 2, y + 40, 3, BLANCO);
  textoCentrado("Verifique Administrador", x + w / 2, y + 90, 2, BLANCO);
  char buf[24];
  snprintf(buf, sizeof(buf), "Codigo: %d", codigoFalla);
  textoCentrado(buf, x + w / 2, y + 130, 2, BLANCO);
}

// ─────────────────────────────────────────────────────────────────────
// ENCABEZADO (título, reloj de actividad, botón sol/luna, hamburguesa)
// ─────────────────────────────────────────────────────────────────────
const int BTN_TEMA_X = 660, BTN_MENU_X = 740, BTN_Y = HDR_ALTO / 2;

void pintarSolLuna()
{
  int cx = BTN_TEMA_X, cy = BTN_Y;
  gfx->fillRect(cx - 20, cy - 20, 40, 40, colorHeader); // limpia
  if (modoNoche)
  {
    gfx->fillCircle(cx, cy, 12, BLANCO);
    gfx->fillCircle(cx + 6, cy - 4, 12, colorHeader); // recorta media luna
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

// ─────────────────────────────────────────────────────────────────────
// MENÚ (General / Administrador) — panel de diagnóstico simplificado
// ─────────────────────────────────────────────────────────────────────
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
  gfx->fillRect(60, 96, 680, TARJETA_H, colorTarjeta);
  gfx->drawRect(60, 96, 680, TARJETA_H, colorBorde);
  gfx->setTextSize(2);
  gfx->setTextColor(colorTexto);
  int ly = 112;
  gfx->setCursor(80, ly); gfx->print("MAC Pantalla: "); gfx->print(WiFi.macAddress()); ly += 28;
  gfx->setCursor(80, ly); gfx->print("Canal WiFi: "); gfx->print(CANAL_WIFI); ly += 28;
  gfx->setCursor(80, ly); gfx->print("Mensajes recibidos: "); gfx->print(contadorMensajes); ly += 28;
  gfx->setCursor(80, ly); gfx->print("RAM libre (heap): "); gfx->print(ESP.getFreeHeap() / 1024); gfx->print(" KB"); ly += 28;
  gfx->setCursor(80, ly); gfx->print("PSRAM libre: "); gfx->print(ESP.getFreePsram() / 1024); gfx->print(" KB"); ly += 28;
  gfx->setCursor(80, ly); gfx->print("Tiempo activo: "); gfx->print(millis() / 1000); gfx->print(" s");
}

// ─────────────────────────────────────────────────────────────────────
// REDIBUJO COMPLETO (boot y cambio de tema)
// ─────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────
// CALLBACK ESP-NOW (firma obligatoria Core 3.x) — SOLO guarda datos
// ─────────────────────────────────────────────────────────────────────
void OnDataRecv(const esp_now_recv_info *info, const uint8_t *incomingData, int len)
{
  if (len != sizeof(MensajeFertiTrack)) return;
  memcpy(&msgRecibido, incomingData, sizeof(msgRecibido));
  String emisor = String(msgRecibido.emisor);
  if (emisor != "MAESTRO") return;

  String cmd = String(msgRecibido.comando);
  int p = msgRecibido.parametro;
  contadorMensajes++;

  if (cmd == "ESTADO_SUELO") { nivelSuelo = p; cambioEstado = true; }
  else if (cmd == "ACTIVIDAD") { actividad = p; frameAnimacion = 0; cambioActividad = true; }
  else if (cmd == "FALLA") { codigoFalla = p; cambioFalla = true; }
  else if (cmd == "MODO_RUTINA") { rutinaActual = p; cambioZona = true; }
  else if (cmd == "ZONA_ACTUAL") { zonaActual = p; cambioZona = true; }
  else if (cmd == "TOTAL_ZONAS") { totalZonas = p; cambioZona = true; }
}

// ─────────────────────────────────────────────────────────────────────
// TOUCH — abrir/cerrar menú (best effort, ver nota de diseño #5)
// ─────────────────────────────────────────────────────────────────────
void revisarTacto()
{
#if HABILITAR_TOQUE
  int tx, ty;
  if (!leerToque(tx, ty)) return;

  if (menuAbierto)
  {
    if (tx >= MENU_X && tx <= MENU_X + MENU_W && ty >= MENU_Y && ty <= MENU_Y + 50)
    {
      // "General" -> cerrar menú y volver a la vista normal
      menuAbierto = false;
      redibujarTodo();
    }
    else if (tx >= MENU_X && tx <= MENU_X + MENU_W && ty >= MENU_Y + 60 && ty <= MENU_Y + MENU_H)
    {
      // "Administrador" -> mostrar panel de diagnóstico
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

  // Botón sol/luna
  if (tx >= BTN_TEMA_X - 22 && tx <= BTN_TEMA_X + 22 && ty >= 4 && ty <= HDR_ALTO)
  {
    modoNoche = !modoNoche;
    redibujarTodo();
    return;
  }

  // Botón hamburguesa
  if (tx >= BTN_MENU_X - 22 && tx <= BTN_MENU_X + 22 && ty >= 4 && ty <= HDR_ALTO)
  {
    menuAbierto = true;
    pintarMenu();
    return;
  }
#endif
}

// ─────────────────────────────────────────────────────────────────────
// SETUP
// ─────────────────────────────────────────────────────────────────────
unsigned long ultimoTicReloj = 0;
unsigned long ultimoTicAnimacion = 0;
unsigned long ultimoTicTacto = 0;

void setup()
{
  Serial.begin(115200);
  delay(200);
  Serial.println("\n[FertiTrack v3] Iniciando PANTALLA HMI...");

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
  Serial.print(" MAC Pantalla: ");
  Serial.println(WiFi.macAddress());

  if (esp_now_init() != ESP_OK)
  {
    Serial.println("ERROR: esp_now_init() fallo.");
    while (1) delay(1000);
  }
  esp_now_register_recv_cb(OnDataRecv);
  // Nota: no se registra al Maestro como peer porque esta Pantalla
  // nunca envía datos, solo recibe (ver nota de diseño en el encabezado).

  Serial.println("\n============================================");
  Serial.println("  PANTALLA HMI LISTA -- esperando al Maestro");
  Serial.println("============================================\n");
}

// ─────────────────────────────────────────────────────────────────────
// LOOP — no bloqueante: banderas sucias + animaciones por millis()
// ─────────────────────────────────────────────────────────────────────
void loop()
{
  if (millis() - ultimoTicTacto > 60)
  {
    ultimoTicTacto = millis();
    revisarTacto();
  }

  if (menuAbierto) return; // no redibujar el fondo mientras el menú está abierto

  if (cambioFalla)
  {
    cambioFalla = false;
    pintarFalla();
    cambioEstado = false;
    cambioActividad = false;
  }
  else
  {
    if (cambioEstado) { cambioEstado = false; pintarTarjetaSuelo(); }
    if (cambioActividad) { cambioActividad = false; pintarBannerActividad(); }
  }

  if (cambioZona)
  {
    cambioZona = false;
    pintarInfoZona();
  }

  if (millis() - ultimoTicAnimacion > 400)
  {
    ultimoTicAnimacion = millis();
    if (codigoFalla == 0) animarBannerActividad();
  }

  if (millis() - ultimoTicReloj > 1000)
  {
    ultimoTicReloj = millis();
    pintarReloj();
  }
}
