# Peticiones de cambio al firmware (Maestro v5.3)

La app no modifica el firmware ni simula lo que este no ofrece. Esta lista recoge lo que
mejoraría la app si se añadiera al Maestro. Ninguna es imprescindible para la Fase 1.

| # | Petición | Por qué | Cómo lo resuelve hoy la app |
|---|----------|---------|-----------------------------|
| 1 | Contador de mediciones en `/api/estado` (`mediciones_total`) y `ultima_zona_medida` | Hoy una medición solo se detecta al ver la transición `SENSADO_MIDIENDO → otro estado` en el sondeo. Si la app no la observa (segundo plano, sin conexión), la medición se pierde para la gráfica. | Registra una muestra al salir de `SENSADO_MIDIENDO` o de `PRUEBA_SENSOR`. |
| 2 | Contadores `riegos_completados` y `ciclos_completados`, y las zonas regadas en el último ciclo | El firmware no avisa cuando termina un riego. La app lo deduce por el paso de `modo_rutina` de `RIEGO` a `NINGUNA`; si ocurre mientras no sondea, no lo cuenta ni sugiere fertilizar. | Deduce el fin del riego por transición y documenta la limitación. |
| 3 | Humedad por zona (`zonas_pct[]` o `zonas_adc[]`), además del nivel | `zonas[]` solo trae el nivel (0–4). Para un mapa con porcentajes reales o una gráfica por zona hace falta el valor medido. | Muestra el nivel por zona y la última humedad que observó durante la sesión. |
| 4 | `/api/reiniciar` fuera de `EMERGENCIA` debería responder 409 ("no hay emergencia") | Hoy responde `200 {"ok":true}` sin hacer nada, y la app podría mostrar un éxito que no ocurrió. | Solo habilita Reiniciar cuando el estado es `EMERGENCIA`. |
| 5 | `/api/iniciar` con un modo inválido debería responder 400 | Hoy responde 409, el mismo código que "sistema ocupado". | La app solo envía `ciclo`, `sensado` o `fertilizar`. |
| 6 | Uniformar el 404: `{"ok":false,"error":"ruta no encontrada"}` | Es la única respuesta de error sin el campo `ok`. | Trata cualquier 404 como error del robot. |
| 7 | Versión del firmware y tiempo encendido (`version`, `uptime_s`) en `/api/estado` | Permitiría detectar firmware incompatible y saber si el Maestro se reinició (la sesión de la app seguiría contando como si nada). | Sin solución; la sesión de la app dura lo que dura el proceso. |
| 8 | Estado real de los relés y del brazo (`rele_riego`, `rele_ferti`, `brazo_riego_abajo`) | Los indicadores de bomba se deducen de `estado` + `actividad`, fiel al código actual, pero frágil si cambia la lógica. | Bomba de riego = `RIEGO_AVANZANDO` con `actividad == 2`; fertilizante = `FERTI_AVANZANDO`. |
| 9 | Servidor web no bloqueante, o un tiempo máximo por petición | `server.handleClient()` corre dentro de `loop()`: cada petición retrasa la máquina de estados del robot. | Una sola petición en vuelo, sondeo cada 1,5 s y timeout de 3 s. |
| 10 | Acotar `zona_actual` a `total_zonas` en `ruta_estado` | Tras el sensado `zonaActual` queda en `numZonas` (no se reinicia en `terminarRutina`) y en el riego avanza por tramos, así que puede llegar `total_zonas + 1`. | La app lo acota al traducir el JSON. |
| 11 | Poner `esperandoProximoCiclo = false` en `iniciarSensado` e `iniciarFertilizacion` | Con el automático activo, si se inicia una rutina a mano, `proximo_ciclo_seg` sigue descontando durante la rutina (y se reprograma al terminar), así que la cuenta no significa nada. | La app solo muestra la cuenta atrás en `ESPERANDO`. |
| 12 | Autenticación mínima para las órdenes (por ejemplo, un token) y una clave de AP más robusta | Cualquiera conectado al AP puede mover el robot desde la app o desde la página web del Maestro. | Fuera del alcance de la app. |
