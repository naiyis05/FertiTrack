# FertiTrack — Mapeo de Arquitectura: App de Escritorio (WPF/.NET) ➔ App Web/Móvil Responsive

> **Origen**: Fase 2 (Nuevo contexto de arquitectura cargando `CLAUDE_DESIGN_EXPORT.md` y asociando las clases de `D:\Downloads\FertiTrackApp\App\app escritorio\InvernaderoApp`).

---

## 1. Tabla de Equivalencia 1 a 1 (Escritorio ➔ Móvil)

| Archivo en `app escritorio` (C# / WPF) | Equivalente en `app movil` (React + TS + Node Bridge) | Función Asociada |
|---|---|---|
| `Models/SensorData.cs` | `src/types/sensor.ts` | Estructura `SensorData`, cálculo `calcularHumedadPorcentaje(valorADC)` (`0% = 3900`, `100% = 2800`), estados `SECO`, `MEDIA`, `HUMEDO`. |
| `Services/WiFiService.cs` | `bridge/server.mjs` + `src/services/maestroService.ts` | Conexión TCP al Maestro (`:8888`), parser de líneas separadas por `\|` (`SISTEMA:...`, `SECTOR:N`, `PUNTO:N`, `ADC:XXXX`, `ESTADO:...`, `RIEGO:...`, `BOMBA:ON/OFF`, `FERTI_SECTOR:N`, `FERTI_BOMBA:ON/OFF`, `ESPERANDO_FERTILIZAR:SI/NO`). |
| `Services/EsclavoService.cs` | `src/services/esclavoService.ts` + proxy `/api/esclavo` | Polling HTTP cada 3 segundos a `http://<ip>/leer` (`ADC:XXXX\|ESTADO:SECO\|LECTURA:N`) y endpoints `/fert_on` y `/fert_off`. |
| `Services/SerialService.cs` | `src/services/maestroService.ts` (Comandos) | Confirmación de fertilización (`EnviarConfirmacionFertilizacion` enviando `"S"`) y umbrales `UMBRAL_SECO = 3900`, `UMBRAL_HUMEDO = 3200`. |
| `ViewModels/MainViewModel.cs` | `src/context/FertiTrackContext.tsx` | Estado reactivo global (`Conectado`, `Ip`, `EsclavoConectado`, `IpEsclavo`, `Humedad`, `ValorADC`, `EstadoSuelo`, `NumeroLectura`, `EstadoMaquina`, `BombaRiegoActiva`, `BombaFertilizanteActiva`, `MostrarAlertaFertilizacion`, `ConteoRiegos`, `Eventos`, `ValoresHumedad`, `IsNight`, `SectorActual`, `PuntoSensado`) + exportación CSV y modo Simulación en vivo. |
| `MainWindow.xaml` + `Estilos.xaml` | `src/components/views/*` + `DanielaDrawer.tsx` | Las 5 vistas principales (`Dashboard`, `Invernadero`, `Gráficas`, `Control`, `Análisis`) + el asistente flotante **Daniela** conectado con **OmniRoute** y Web Speech API. |
