# FertiTrack — Exportación Oficial de Diseño (Claude Design System + 6 Vistas)

> **Origen**: Fase 1 (Design Specification & Token System) aplicando las skills `frontend-design` y `mobile-native` de Claude junto con el enrutamiento `OmniRoute`.
> **Imágenes de Referencia**: `design/reference-screens/01-dashboard.jpg` a `06-daniela-chat.jpg`.

---

## 1. Sistema de Tokens Visuales (`Design Tokens`)

### Paleta de Colores
- **Fondo Base App (`--bg-app`)**: `#F4F8F5` (Verde salvia ultra claro, limpio y agrícola)
- **Superficie de Tarjetas (`--surface-card`)**: `#FFFFFF` con borde `#E8EFEA` y sombra suave `0 4px 20px rgba(16, 42, 26, 0.04)`
- **Verde Primario FertiTrack (`--green-primary`)**: `#16A34A` / `#22C55E` (Botones principales, indicador EN VIVO, pestaña activa, burbujas de usuario en Daniela)
- **Verde Oscuro Insight / Daniela Header (`--green-deep`)**: `#15733B` a `#136534`
- **Ámbar / Nivel Intermedio / Fertilización (`--amber-accent`)**: `#F59E0B` ( Fondo de alerta: `#FEF9E7`, borde `#FDE68A`, texto `#92400E`)
- **Rojo / Estado Seco / Error ESP32 (`--red-alert`)**: `#EF4444` (Fondo suave `#FEF2F2`, texto `#DC2626`)
- **Azul Riego / Agua (`--blue-water`)**: `#2563EB` / `#0EA5E9` (Fondo suave `#EFF6FF`)
- **Cielo Día (`Invernadero`)**: Gradiente `#2F9PF5` → `#7DD3FC` → `#E0F2FE`
- **Cielo Noche (`Invernadero` `IsNight`)**: Gradiente `#0F172A` → `#1E293B` → `#334155`

### Tipografía y Escala
- **Familia Principal**: `'Plus Jakarta Sans', 'Inter', system-ui, -apple-system, sans-serif`
- **Monoespaciada (IPs, valores ADC, métricas técnicas)**: `'JetBrains Mono', ui-monospace, monospace`
- **Reglas Mobile-Native obligatorias**:
  - `font-size: 16px` mínimo en todos los `<input>` para evitar auto-zoom en iOS Safari.
  - `100dvh` en el contenedor raíz `.app-shell` y `overscroll-behavior: none`.
  - `-webkit-tap-highlight-color: transparent` y feedback táctil `:active { transform: scale(0.97) }`.

---

## 2. Especificación Detallada de las 6 Vistas

### Vista 1: `Dashboard` (`01-dashboard.jpg`)
- **Header**: Título `FertiTrack`, subtítulo `Invernadero automatizado`, badge superior derecho `• EN VIVO` (fondo `#DCFCE7`, texto `#15803D`).
- **Chips de estado activo**:
  - `📍 Sector {SectorActual} activo` (borde verde claro)
  - `💧 Punto {PuntoSensado}/3` (fondo azul claro `#E0F2FE`, texto `#0369A1`)
- **Carrusel horizontal de KPIs (`touch-action: pan-y`)**:
  1. `💧 HUMEDAD`: `{Humedad}%`
  2. `🚿 RIEGOS HOY`: `{ConteoRiegos}`
  3. `🪴 SUELO`: `{EstadoSuelo}` (`MEDIA` / `SECO` / `HUMEDO`)
- **Tarjeta Principal — Humedad del suelo**:
  - Etiqueta derecha en monoespaciada: `HumedadPorcentaje`
  - Valor grande `{Humedad}%` + descripción (`Nivel intermedio, riego programado`).
  - Barra horizontal de progreso coloreada según umbral (`>=60%` verde, `35-59%` ámbar, `<35%` rojo).
  - 3 Píldoras selectoras/indicadoras: `• Húmedo`, `• Intermedio`, `• Seco`.
- **Tarjeta Esclavo — sensor en tiempo real**:
  - Icono antena/sensor, título `Esclavo — sensor en tiempo real`, subtítulo `ADC {ValorADC} · lectura #{NumeroLectura} · cada 3 s`, punto de estado a la derecha.
- **Tarjeta Condicional — Fertilización pendiente (`EsperandoFertilizar`)**:
  - Fondo ámbar claro `#FEF9E7`, borde `#FCD34D`, icono espiga naranja.
  - Texto: `Fertilización pendiente` / `El ciclo terminó. ¿Aplicar fertilizante al Sector {SectorActual}?`
  - Botones: `Ignorar` (`IgnorarFertilizacion()`) y `Sí, empezar` (`ConfirmarFertilizacion()`).
- **Tarjeta Inferior — ESTADO DE LA MÁQUINA**:
  - Borde izquierdo verde, muestra `EstadoMaquina` en tiempo real y el historial reciente.

### Vista 2: `Invernadero` (`02-invernadero.jpg`)
- **Cabecera Cielo Dinámico (`IsNight`)**:
  - Reloj grande en vivo (`09:41`), fecha (`Lunes 27 de julio`), píldora derecha `☀️ Mañana` / `🌙 Noche` con sol/luna animados.
  - Barra horizontal de 8 iconos de ciclo solar/horario.
- **Tarjetas de los 3 Sectores**:
  - `SECTOR 1` (`68%` verde, 2 iconos planta), `• SECTOR 2` (`47%` activo con borde verde destacado), `SECTOR 3` (`24%` rojo con iconos cactus).
- **Línea de Ruta del Robot**:
  - Nodos `Inicio` ➔ `S2` ➔ `S3` con el avatar del robot `🤖` posicionado dinámicamente sobre el sector activo (`SectorActual`).
- **Grid de 4 Tarjetas de Estado**:
  - `🚿 BOMBA`: `ACTIVA` (azul) / `INACTIVA`
  - `🌾 FERTILIZANTE`: `EN ESPERA` (marrón/ámbar) / `ACTIVO`
  - `💧 GLOBAL`: `{Humedad}%`
  - `🤖 ROBOT`: `EN RUTA` (verde) / `DETENIDO`
- **Banner de Acción Rápida**:
  - `🌵 Sector 3 necesita riego — Por debajo del umbral SECO (35%)` + acción `Regar`.

### Vista 3: `Gráficas` (`03-graficas.jpg`)
- **Header**: `Gráficas` + badge `• EN VIVO`.
- **Tarjeta Tanque de Humedad**:
  - Cápsula vertical izquierda con líquido amarillo/verde animado al nivel `{Humedad}%` y marcas de graduación.
  - Columna derecha: `TANQUE DE HUMEDAD`, título `Nivel intermedio`, leyenda con puntos de color (`🟢 Óptimo ≥ 60%`, `🟠 Intermedio 35–59%`, `🔴 Seco < 35%`) y `ValorADC {ValorADC}` en monoespaciada.
- **Tarjeta Tendencia de humedad**:
  - Selector de rango (`1 h`, `6 h`, `24 h`) + gráfico SVG de área suavizada con gradiente verde y punto final pulsante.
- **Sección Eventos de riego**:
  - Subtítulo derecho `ConteoRiegos · {ConteoRiegos}` y tarjetas de eventos (`🚿 Sector 2 · punto 2 — 09:38 · 12 s de bomba`).

### Vista 4: `Control` (`04-control.jpeg`)
- **Header**: `Control`.
- **Tarjeta Riego automático**:
  - Icono ducha azul, título `Riego automático`, subtítulo verde `Activo · umbral 35%`, switch toggle estilo iOS verde.
- **Tarjeta Fertilización manual**:
  - Icono espiga, título `Fertilización manual`, subtítulo `Dosis única en el sector activo`, botón ancho naranja `Fertilizar ahora` (`EnviarFertilizacion(true)`).
- **Sección CONEXIONES**:
  - **Tarjeta Maestro (robot)**:
    - Icono robot `🤖`, título `Maestro (robot)`, subtítulo `TCP · puerto 8888`, badge `• CONECTADO` / `DESCONECTADO`.
    - Input monoespaciado (`inputmode="decimal"`, ej. `192.168.1.50` / `192.168.4.1`) + botón `Conectar` / `Desconectar`.
    - Pie de ayuda: `Teclado numérico con punto · autocompletado de subred`.
  - **Tarjeta Esclavo (sensor)**:
    - Icono antena `📡`, título `Esclavo (sensor)`, subtítulo `HTTP · puerto 80 · polling 3 s`, badge `• SIN SEÑAL` (rojo) o `• CONECTADO` (verde).
    - Input monoespaciado (`192.168.1.7` / `192.168.4.2`) + botón verde `Conectar` / `Detener`.
    - Alerta condicional roja: `⚠️ No se pudo alcanzar el host. Verifica que el ESP32 esté en la misma red.`
- **Nota de seguridad**:
  - `💡 Las acciones destructivas (detener ciclo, reiniciar placas) viven en un menú secundario para evitar toques accidentales.` + botón desplegable de acciones de emergencia.

### Vista 5: `Análisis` (`05-analisis.jpg`)
- **Header**: `Análisis`, derecha `Sesión · 3 h 12 min`, subtítulo `Desliza hacia abajo para refrescar`.
- **3 Tarjetas KPI Horizontales**:
  1. `💧 HUMEDAD PROMEDIO`: `52%` (`-12 pts vs. ayer` en rojo).
  2. `🚿 RIEGOS DE LA SESIÓN`: `{ConteoRiegos}` (`Ciclo ✓ completo` en verde).
  3. `🪴 ESTADO ACTUAL`: `{EstadoSuelo}` (`MEDIA` en marrón/ámbar con indicador).
- **Tarjeta Verde Oscura — 🧠 INSIGHT DEL SISTEMA**:
  - Generado dinámicamente según los sectores y lecturas (o vía OmniRoute): *"El sector 3 concentra el 70% de la pérdida de humedad de hoy. Sugerencia: adelantar su riego a las 16:00 y aplicar fertilizante después del segundo ciclo."*
  - Botones: `Descartar` y `Aplicar sugerencia`.
- **Tarjeta Exportación**:
  - `📥 Exportar bitácora de la sesión — CSV con lecturas ADC y eventos` (descarga archivo `.csv` real con las lecturas y eventos de la sesión).

### Vista 6: `Daniela — Asistente de FertiTrack` (`06-daniela-chat.jpg`)
- **Botón Flotante (FAB)**: Siempre visible arriba de la barra de navegación inferior a la derecha, círculo verde con avatar de agricultora y badge rojo de notificación.
- **Bottom Sheet / Modal de Daniela**:
  - Cabecera verde `#15803D` con avatar, título `Daniela`, subtítulo `• Asistente de FertiTrack · en línea`, botón cerrar `✕`.
  - Historial de mensajes con soporte para **tarjetas de estado incrustadas** (ej. `🌵 Sector 3 · 24% · SECO · Último riego 07:40`) y etiquetas `por voz 🎤`.
  - Barra inferior con botón circular de micrófono (rojo al grabar, verde inactivo), campo `"Escuchando... habla ahora"` y botón enviar `➤`.
  - Pie informativo `ESTADOS DEL MIC`: `🟢 Inactivo`, `🔴 Grabando`, `⚪ Sin permiso`.
  - Conectado al enrutador **OmniRoute** (`http://localhost:20128/v1/chat/completions`) con fallback inteligente local que lee el estado real de `SensorData` y ejecuta acciones como regar un sector o activar fertilización.
