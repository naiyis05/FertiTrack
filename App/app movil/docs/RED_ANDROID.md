# Red en Android: cómo habla FertiTrack con el robot

La app solo habla con la placa **Maestro** (ESP32) por HTTP GET en `http://192.168.4.1` (puerto 80),
a través del punto de acceso Wi-Fi que crea el propio robot (`FertiTrack_AP`, canal 1). Ese AP **no
tiene internet**, y eso condiciona todo lo que sigue.

Código: `data/red/` (elección de red), `data/remote/` (cliente HTTP), `data/RobotRepositoryImpl.kt`
(sondeo y órdenes), `di/AppContainer.kt`. Simulador (solo debug): `src/debug/.../simulador/`.

## 1. Permisos y por qué

| Permiso | Tipo | Desde | Para qué |
|---|---|---|---|
| `INTERNET` | normal | siempre | Abrir sockets hacia el robot. |
| `ACCESS_NETWORK_STATE` | normal | siempre | `registerNetworkCallback`: detectar la red Wi-Fi del robot (modo manual). |
| `CHANGE_NETWORK_STATE` | normal | API 29 (se declara siempre) | `ConnectivityManager.requestNetwork` con `WifiNetworkSpecifier` (modo automático). |
| `ACCESS_LOCAL_NETWORK` | **de ejecución** (grupo `NEARBY_DEVICES`) | API 37 | Con targetSdk 37, en Android 17+ la "protección de red local" bloquea por defecto todo el tráfico a la LAN (también OkHttp y sockets propios). Sin él, las peticiones al robot solo agotan el tiempo de espera. |

- `ACCESS_LOCAL_NETWORK` solo existe y solo se comprueba con `Build.VERSION.SDK_INT >= 37`
  (`Build.VERSION_CODES.CINNAMON_BUN`). Si falta, `EstadoRed` es
  `PermisosFaltantes(["android.permission.ACCESS_LOCAL_NETWORK"])` aunque haya red: así la UI explica
  el motivo en vez de mostrar "sin respuesta". En Android 16 la protección es opcional (opt-in) y en
  versiones anteriores no aplica. Si el usuario ya concedió otro permiso del grupo `NEARBY_DEVICES`,
  el sistema no vuelve a preguntar.
  Fuente: <https://developer.android.com/privacy-and-security/local-network-permission>
- `requestNetwork` exige `CHANGE_NETWORK_STATE` (o poder escribir ajustes del sistema) y lanza
  `SecurityException` si falta. Fuente: javadoc de `ConnectivityManager.requestNetwork` en el SDK 37
  y <https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,%20android.net.ConnectivityManager.NetworkCallback)>
- **No** se piden `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, `ACCESS_WIFI_STATE` ni
  `CHANGE_WIFI_STATE`: la documentación oficial de permisos Wi-Fi solo exige `NEARBY_WIFI_DEVICES`
  para hotspot local, Wi-Fi Aware, Wi-Fi Direct y RTT, y `ACCESS_FINE_LOCATION` para escaneos
  (`getScanResults`, `startScan`); ni esa página ni la guía de `WifiNetworkSpecifier` mencionan
  permisos para `requestNetwork` con un especificador, y el código fuente de `WifiNetworkSpecifier`
  (SDK 37) no declara ninguno.
  Fuentes: <https://developer.android.com/develop/connectivity/wifi/wifi-permissions>,
  <https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap>
- La capa de datos **nunca pide permisos**: la UI los pide y luego llama a `reevaluarRed()`.

`android:dataExtractionRules="@xml/data_extraction_rules"` excluye todo de la copia en la nube y de
la transferencia entre equipos (la configuración vive en el robot; la app no guarda nada que valga la
pena copiar). En Android 8–11 lo mismo lo hacen `allowBackup="false"` y `fullBackupContent="false"`.
Fuente: <https://developer.android.com/identity/data/autobackup#include-exclude-android-12>

## 2. Cómo se elige la red

El AP del robot no tiene internet: Android puede desconectarse de él, o seguir conectado pero mandar
el tráfico por los datos móviles (la red "por defecto"). Por eso la app elige la red explícitamente.

1. **Automático (Android 10+, API 29)** — `conectarWifiRobot()`:
   `requestNetwork` con `TRANSPORT_WIFI`, sin `NET_CAPABILITY_INTERNET` y un `WifiNetworkSpecifier`
   con el SSID exacto (`setSsid`) y la clave WPA2 (`setWpa2Passphrase`). Android muestra su diálogo;
   con SSID exacto guarda la aprobación para ese AP y la próxima vez conecta sin preguntar (si el
   usuario "olvida" la red, vuelve a preguntar). La red resultante es solo local.
   - `onAvailable` → `Disponible(AUTOMATICO)`; `onUnavailable` (no se encontró o el usuario canceló)
     y `onLost` → `NoDisponible(motivo)`. Un `SecurityException` o el límite de 100 callbacks no
     revientan la app: quedan como `NoDisponible(motivo)` y queda el modo manual.
   - Si `fertitrack.ap.password` no está en `local.properties` (`RobotConfig.CLAVE_AP` vacía) o no
     es una frase WPA2 válida (8 a 63 caracteres ASCII), el modo automático no está disponible
     (`NoDisponible` con el motivo) y queda el manual.
2. **Manual (todas las versiones)**: `registerNetworkCallback` de redes Wi-Fi **sin exigir
   INTERNET**. La red cuya subred (`LinkProperties.linkAddresses`) contiene `192.168.4.1` es la del
   robot → `Disponible(MANUAL)`. Otra Wi-Fi → `NoDisponible("El teléfono está en otra red…")`.
3. Prioridad: red pedida por la app > red manual del robot > ruta por defecto del sistema (si no hay
   ninguna Wi-Fi conocida). Lógica pura y probada en `DecisorRed`.

**Las peticiones salen por esa red con `Network.getSocketFactory()`** (en OkHttp,
`OkHttpClient.Builder.socketFactory`), no con `bindProcessToNetwork`: este último desviaría todo el
tráfico del proceso (y cualquier otra conexión de la app se quedaría sin internet). La fábrica crea
sockets ya ligados a esa red; como el destino es una IP literal no hace falta DNS.
Fuentes: <https://developer.android.com/reference/android/net/Network#getSocketFactory()>,
<https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)>

## 3. Tráfico HTTP sin cifrar, solo al robot

El ESP32 no tiene TLS. `res/xml/network_security_config.xml` permite HTTP en claro **solo** a
`192.168.4.1`; la variante debug añade `127.0.0.1` y `localhost` para el simulador. Cualquier otro
destino en claro queda bloqueado por el sistema.
Fuente: <https://developer.android.com/privacy-and-security/security-config>

## 4. Reglas de comunicación (RobotRepositoryImpl)

- **Una sola petición en vuelo** para toda la app (Mutex justo). El ESP32 atiende una conexión a la
  vez dentro de `loop()`, y cada petición retrasa su máquina de estados.
- Cliente OkHttp: connect/read/write/call timeout 3 s, `retryOnConnectionFailure(false)`, sin
  redirecciones, sin caché, pool vacío y `Connection: close`.
- **Sondeo** de `/api/estado` cada 1,5 s contados desde que terminó la consulta anterior; si hay una
  orden en curso ese turno se salta. 1–2 fallos → `SinRespuesta` al ritmo normal; desde el 3.º →
  `Reintentando` con backoff 2 s, 4 s, 8 s, 10 s (tope). El primer éxito → `Conectado`.
- **Órdenes**: esperan su turno (como mucho una consulta, ≤ 3 s) y se envían **una vez**; nunca se
  reintentan. Tras un éxito se adelanta la siguiente consulta.
- **Detener**: cancela la consulta en curso (`Call.cancel()`) y envía `/api/detener` al instante,
  **sin esperar** a otra orden que esté en vuelo. Como el ESP32 atiende las conexiones en orden de
  llegada, la parada se procesa justo después de esa orden y gana. Es la única excepción a "una
  petición en vuelo" (como mucho dos, un instante).
- **Seguridad**: `iniciar` y `configurarAuto(true)` no se envían si el último estado conocido no es
  `ESPERANDO` sin falla (devuelven `Ocupado` con el motivo). El firmware valida `iniciar`, pero
  `/api/auto` lo acepta en cualquier estado.
- Al cambiar de red (aparece la del robot) se intenta enseguida, sin esperar el backoff.

## 5. Segundo plano

- Al pasar a segundo plano la UI llama a `pausarSondeo()`: se cancela el bucle y la consulta en curso
  y no sale ninguna petición más (`EstadoConexion.Pausado`). Al volver, `reanudarSondeo()` →
  `Conectando` y una consulta inmediata.
- El pedido de red (`requestNetwork`) y la escucha de redes **siguen vivos** en segundo plano, para
  no repetir el diálogo del sistema ni perder la red del robot al volver. Viven con el proceso;
  `EnlaceRed.liberar()` los suelta si alguna vez hace falta un cierre explícito.

## 6. Conexión manual (Android 8–9, y como alternativa siempre)

1. Abrir **Ajustes → Wi-Fi** y elegir `FertiTrack_AP` (la clave está en la documentación del robot).
2. Si Android avisa de que la red **no tiene acceso a internet**, elegir **"Mantener conexión" /
   "Sí"** (y, si aparece, "No volver a preguntar"). En algunos teléfonos hay que desactivar
   "Cambiar a datos móviles automáticamente" / "Wi-Fi inteligente" para que no se desconecte solo.
3. Volver a FertiTrack: la app detecta la red sola (y la UI puede forzarlo con `reevaluarRed()`).
   Aunque Android siga usando los datos móviles como red por defecto, las peticiones salen por el
   Wi-Fi del robot gracias a su `SocketFactory`.

## 7. Simulador (solo builds debug)

`FakeFertiTrackServer`: servidor HTTP/1.1 hecho a mano en `127.0.0.1` (puerto efímero), una conexión
a la vez, `Connection: close`, mismos campos, códigos y textos que las `ruta_*` del firmware v5.3, y
una máquina de estados fiel a `loop()` con tiempos acortados (arranque 3 s, avance 1,5 s, medición
2 s, brazo 2 s, tramo de riego 3 s, retorno 2 s). Arranca ya configurado (300 cm, 6 zonas) y la
humedad de cada zona es pseudoaleatoria con semilla: se seca antes de cada sensado y sube al regarla,
así que la mayoría de los ciclos riega y algunos no. `inyectarFalla(1|2|3|9)` y
`simularPerdidaConexion(seg)` (acepta y no contesta). Con el simulador, `EstadoRed.Simulador` y
sockets normales (sin la red Wi-Fi). En release no existe.

## 8. Lo que no se pudo probar sin el robot ni sin un teléfono

Probado en la JVM (pruebas unitarias): parseo de `/api/estado`, mapeo de respuestas de órdenes contra
un servidor local, timeouts y cancelación con OkHttp, reglas del repositorio con tiempo virtual,
reglas de elección de red (`DecisorRed`) y el simulador completo por HTTP.

**Sin probar** (hace falta hardware):

- El diálogo de `WifiNetworkSpecifier` y que la aprobación se recuerde para `FertiTrack_AP`; el
  comportamiento de cada fabricante (algunos cortan redes sin internet o la red pedida al pasar a
  segundo plano).
- `ACCESS_LOCAL_NETWORK` en un Android 17 real: que sin él el tráfico se bloquee y que, concedido,
  pase por la `SocketFactory` de la red pedida.
- Que `registerNetworkCallback` vea la red manual del AP en Android 8–9 y que el ESP32 entregue una
  dirección en `192.168.4.0/24` (la detección de "red del robot" depende de eso).
- Tiempos reales del ESP32: que 3 s de timeout bastan mientras el WebServer está ocupado con
  ESP-NOW, y que un `/api/detener` enviado mientras se atiende otra conexión se procese enseguida.
- `EnlaceRedAndroid` no tiene pruebas instrumentadas (requiere `ConnectivityManager` real).

## 9. Observaciones del firmware v5.3 que afectan a la app

- `zona_actual` puede valer `total_zonas + 1`: tras el sensado `zonaActual` queda en `numZonas` y no
  se reinicia al terminar (`terminarRutina`), y en el riego avanza de tramo en tramo. La UI debe
  acotarlo a `total_zonas`. *(Petición de cambio: acotarlo en `ruta_estado`.)*
- Si se activa el modo automático en `ESPERANDO` y luego se inicia un ciclo a mano,
  `esperandoProximoCiclo` sigue en true durante la rutina: `proximo_ciclo_seg` cuenta hacia 0
  mientras el robot trabaja. Mostrar la cuenta atrás solo en `ESPERANDO`. *(Petición: ponerlo en
  false en `iniciarSensado`/`iniciarFertilizacion`.)*
- "modo invalido" responde 409 aunque es un error de parámetros (la app lo trata como `Ocupado`).
- No hay campo de versión del firmware ni indicador de "riego terminado" / "conviene fertilizar".
