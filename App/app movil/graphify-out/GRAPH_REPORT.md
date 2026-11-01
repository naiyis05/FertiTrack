# Graph Report - app movil  (2026-10-27)

## Corpus Check
- Corpus is ~11,906 words - fits in a single context window. You may not need a graph.

## Summary
- 261 nodes · 448 edges · 22 communities (12 shown, 10 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 72 edges (avg confidence: 0.83)
- Token cost: 214,462 input · 0 output

## Community Hubs (Navigation)
- Package Dependencies & Config
- App Shell & Views
- Dashboard/Graficas/Analisis Screens
- Design Export Components
- State, Protocol & Sensor Logic
- TypeScript Compiler Config
- Control & Device Connections
- Desktop-to-Mobile Mapping
- Design Tokens & HTML Entry
- Android Test Stubs
- WebSocket TCP Bridge
- Invernadero Screen
- Android Activity & Branding

## God Nodes (most connected - your core abstractions)
1. `SensorData` - 16 edges
2. `compilerOptions` - 16 edges
3. `useFertiTrack()` - 15 edges
4. `AppShell()` - 13 edges
5. `react` - 12 edges
6. `DanielaDrawer()` - 12 edges
7. `FertiTrackProvider()` - 11 edges
8. `FertiTrack Design Export (Claude Design System + 6 Vistas)` - 11 edges
9. `Control screen reference design` - 11 edges
10. `Vista 6: Daniela - Asistente de FertiTrack` - 9 edges

## Surprising Connections (you probably didn't know these)
- `Assistant Farmer FAB with Notification Badge` --references--> `DanielaDrawer()`  [INFERRED]
  design/reference-screens/01-dashboard.jpg → src/components/daniela/DanielaDrawer.tsx
- `Daniela floating action button (farmer avatar)` --references--> `DanielaDrawer()`  [INFERRED]
  design/reference-screens/04-control.jpeg → src/components/daniela/DanielaDrawer.tsx
- `Machine Status Tiles (Bomba Activa, Fertilizante En Espera, Global 47%, Robot En Ruta)` --conceptually_related_to--> `ControlView()`  [AMBIGUOUS]
  design/reference-screens/02-invernadero.jpg → src/components/views/ControlView.tsx
- `FertiTrackProvider()` --implements--> `Session Log CSV Export`  [INFERRED]
  src/context/FertiTrackContext.tsx → design/CLAUDE_DESIGN_EXPORT.md
- `Chat message bubbles with timestamps` --references--> `MensajeDaniela`  [INFERRED]
  design/reference-screens/06-daniela-chat.jpg → src/types/sensor.ts

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Desktop WPF to React port of FertiTrack** — design_desktop_to_mobile_mapping_sensordata_cs, src_types_sensor_sensordata, design_desktop_to_mobile_mapping_mainviewmodel_cs, src_context_fertitrackcontext_fertitrackprovider, design_desktop_to_mobile_mapping_wifiservice_cs, design_desktop_to_mobile_mapping_maestro_service, design_desktop_to_mobile_mapping_bridge_server [EXTRACTED 1.00]
- **FertiTrack six-view mobile UI** — design_claude_design_export_view_dashboard, design_claude_design_export_view_invernadero, design_claude_design_export_view_graficas, design_claude_design_export_view_control, design_claude_design_export_view_analisis, design_claude_design_export_view_daniela [EXTRACTED 1.00]
- **Daniela assistant flow (OmniRoute + fallback + voice)** — design_claude_design_export_view_daniela, design_claude_design_export_omniroute, design_claude_design_export_local_fallback, design_claude_design_export_mic_states, src_components_daniela_danieladrawer_danieladrawer, src_services_omniroutedanielaservice_consultardanielaomniroute [INFERRED 0.85]
- **Shared Optimo/Intermedio/Seco humidity threshold color coding** — design_reference_screens_01_dashboard_soil_humidity_card, design_reference_screens_02_invernadero_sector_cards, design_reference_screens_02_invernadero_irrigation_alert, design_reference_screens_03_graficas_threshold_legend, design_reference_screens_03_graficas_humidity_tank [INFERRED 0.85]
- **Reference screens mapped to AppShell tab views** — design_reference_screens_01_dashboard, design_reference_screens_02_invernadero, design_reference_screens_03_graficas, src_components_appshell_nav_items [INFERRED 0.85]
- **Daniela voice chat flow (FAB -> drawer -> mic -> LLM service)** — design_reference_screens_04_control_daniela_fab, design_reference_screens_06_daniela_chat_mic_button, design_reference_screens_06_daniela_chat_mic_states_legend, src_components_daniela_danieladrawer_danieladrawer, src_services_omniroutedanielaservice_consultardanielaomniroute [INFERRED 0.75]
- **Device connection card pattern (status pill, IP input, connect action)** — design_reference_screens_04_control_conexion_maestro_robot, design_reference_screens_04_control_conexion_esclavo_sensor, design_reference_screens_04_control_ip_input_numeric_keypad, design_reference_screens_04_control_connection_error_message [EXTRACTED 1.00]
- **Session analytics KPI card set** — design_reference_screens_05_analisis_humedad_promedio_card, design_reference_screens_05_analisis_riegos_sesion_card, design_reference_screens_05_analisis_estado_actual_card, design_reference_screens_05_analisis_insight_sistema_card [EXTRACTED 1.00]

## Communities (22 total, 10 thin omitted)

### Community 0 - "Package Dependencies & Config"
Cohesion: 0.06
Nodes (44): config, dependencies, @capacitor/android@^8.5.3, @capacitor/cli@^8.5.3, @capacitor/core@^8.5.3, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+36 more)

### Community 1 - "App Shell & Views"
Cohesion: 0.18
Nodes (21): MainWindow.xaml + Estilos.xaml, Bottom Tab Nav (Dashboard, Invernadero, Graficas, Control, Analisis), Bottom tab bar (Dashboard, Invernadero, Graficas, Control, Analisis), react, App(), AppShell(), NAV_ITEMS, DanielaDrawer() (+13 more)

### Community 2 - "Dashboard/Graficas/Analisis Screens"
Cohesion: 0.10
Nodes (27): Dashboard Reference Screen, Assistant Farmer FAB with Notification Badge, Pending Fertilization Prompt (Ignorar / Si, empezar), KPI Carousel (Humedad, Riegos Hoy, Suelo), Slave Sensor Real-time Row (ADC 2873, reading #128, every 3 s), Soil Humidity Card (47% with progress bar and Humedo/Intermedio/Seco chips), Graficas Reference Screen, Humidity Tank Gauge (47%, Nivel intermedio, ValorADC 2873) (+19 more)

### Community 3 - "Design Export Components"
Cohesion: 0.10
Nodes (23): FertiTrack Design Export (Claude Design System + 6 Vistas), Session Log CSV Export, Daniela Bottom Sheet / Modal, Daniela Floating Action Button, Esclavo Real-Time Sensor Card, Estado de la Maquina Card, frontend-design skill, Humidity Tank Card (+15 more)

### Community 4 - "State, Protocol & Sensor Logic"
Cohesion: 0.20
Nodes (15): Live Simulation Mode, FertiTrack Header with EN VIVO Badge and Sector/Point Chips, Humidity Trend Area Chart (1h/6h/24h toggle), FertiTrackContextType, FertiTrackProvider(), RespuestaDaniela, crearSensorDataInicial(), parsearRespuestaEsclavo() (+7 more)

### Community 5 - "TypeScript Compiler Config"
Cohesion: 0.11
Nodes (17): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleDetection, moduleResolution (+9 more)

### Community 6 - "Control & Device Connections"
Cohesion: 0.13
Nodes (15): Esclavo (sensor) Connection Card - HTTP 80 polling 3s, Fertilizacion Manual Card, Maestro (robot) Connection Card - TCP 8888, Riego Automatico Toggle, Vista 4: Control, Esclavo HTTP Polling /leer every 3s (+ /fert_on, /fert_off), Maestro Pipe-Delimited Line Protocol (TCP :8888), Control screen reference design (+7 more)

### Community 7 - "Desktop-to-Mobile Mapping"
Cohesion: 0.17
Nodes (16): Fertilizacion Pendiente Card (EsperandoFertilizar), FertiTrack Architecture Mapping: Desktop (WPF/.NET) to Web/Mobile, ADC Calibration (0%=3900, 100%=2800), bridge/server.mjs (Node TCP bridge), 1-to-1 Desktop to Mobile Equivalence Table, src/services/esclavoService.ts + /api/esclavo proxy, Services/EsclavoService.cs, Fertilization Confirmation (send S) (+8 more)

### Community 8 - "Design Tokens & HTML Entry"
Cohesion: 0.19
Nodes (9): Color Palette, mobile-native skill, Typography Scale (Plus Jakarta Sans / JetBrains Mono), Apple Mobile Web App Capable Meta, Google Fonts (JetBrains Mono, Plus Jakarta Sans), index.html (Vite entry page), #root mount div, Theme Color Meta (light #F3F7F4 / dark #0F172A) (+1 more)

### Community 10 - "WebSocket TCP Bridge"
Cohesion: 0.22
Nodes (7): broadcast(), conectarMaestroTCP(), desconectarMaestroTCP(), server, wss, ws@^8.18.0, ws

### Community 11 - "Invernadero Screen"
Cohesion: 0.20
Nodes (11): Dynamic Sky Header (IsNight), Robot Route Line, 4-Card Status Grid (Bomba/Fertilizante/Global/Robot), Vista 2: Invernadero, Invernadero Reference Screen, Sector 3 Needs Irrigation Alert (below SECO 35% threshold, Regar), Robot Route Track (Inicio - S2 - S3), Sector Cards (S1 68%, S2 47% active, S3 24% cactus) (+3 more)

### Community 12 - "Android Activity & Branding"
Cohesion: 0.47
Nodes (4): MainActivity, splash.png (default Capacitor splash: blue X logo on white), ic_launcher.png (default Capacitor launcher icon: blue X on grid), Default Capacitor placeholder branding (no custom FertiTrack art yet)

## Ambiguous Edges - Review These
- `ControlView()` → `Machine Status Tiles (Bomba Activa, Fertilizante En Espera, Global 47%, Robot En Ruta)`  [AMBIGUOUS]
  design/reference-screens/02-invernadero.jpg · relation: conceptually_related_to

## Knowledge Gaps
- **28 isolated node(s):** `frontend-design skill`, `Horizontal KPI Carousel`, `Esclavo Real-Time Sensor Card`, `Estado de la Maquina Card`, `Dynamic Sky Header (IsNight)` (+23 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 78 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `react` connect `App Shell & Views` to `Package Dependencies & Config`, `Design Tokens & HTML Entry`?**
  _High betweenness centrality (0.262) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `SensorData` (e.g. with `Slave Sensor Real-time Row (ADC 2873, reading #128, every 3 s)` and `Soil Humidity Card (47% with progress bar and Humedo/Intermedio/Seco chips)`) actually correct?**
  _`SensorData` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `frontend-design skill`, `Horizontal KPI Carousel`, `Esclavo Real-Time Sensor Card` to the rest of the system?**
  _28 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Package Dependencies & Config` be split into smaller, more focused modules?**
  _Cohesion score 0.05735430157261795 - nodes in this community are weakly interconnected._
- **Why does `SensorData` connect `Dashboard/Graficas/Analisis Screens` to `App Shell & Views`, `Design Export Components`, `State, Protocol & Sensor Logic`, `Control & Device Connections`, `Desktop-to-Mobile Mapping`, `Invernadero Screen`?**
  _High betweenness centrality (0.067) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `AppShell()` (e.g. with `Mobile-Native Rules` and `Bottom Tab Nav (Dashboard, Invernadero, Graficas, Control, Analisis)`) actually correct?**
  _`AppShell()` has 3 INFERRED edges - model-reasoned connections that need verification._

### Low-confidence Hints
_AMBIGUOUS edges — the extractor was unsure. Verify before acting on these._

- **What is the exact relationship between `ControlView()` and `Machine Status Tiles (Bomba Activa, Fertilizante En Espera, Global 47%, Robot En Ruta)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._