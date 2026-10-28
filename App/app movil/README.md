# FertiTrack para Android

App nativa para monitorear y controlar por Wi-Fi el robot monorriel de invernadero
FertiTrack. Sustituye a la app de escritorio (WPF) y a la app móvil anterior (React/Capacitor).

La app **solo habla con el Maestro** (ESP32): se conecta a su punto de acceso
`FertiTrack_AP`, consulta `/api/estado` y envía órdenes por HTTP a `192.168.4.1`. El contrato
HTTP sale de `App/Codigo/Maestro_version5_3.ino`; donde la app de escritorio y el firmware
difieren, manda el firmware.

## Requisitos

- Android Studio 2026.2 o posterior (trae su propio JDK).
- Android SDK 37 (Android 17).
- Teléfono con Android 8.0 (API 26) o superior. No hace falta emulador: se prueba en un
  celular por USB.

## Configuración

1. Abre la carpeta `App/app movil` en Android Studio (**File → Open**), la que contiene
   `settings.gradle.kts`.
2. La clave del AP **no se versiona**. Agrégala a `local.properties` (Android Studio crea el
   archivo con `sdk.dir`):

   ```properties
   fertitrack.ap.password=<clave del AP del Maestro>
   ```

   Sin clave la app funciona igual, pero solo con conexión manual al Wi-Fi.
3. La IP y el SSID están en `gradle.properties` (`fertitrack.robot.ip`,
   `fertitrack.ap.ssid`). Si cambias la IP, cambia también
   `app/src/main/res/xml/network_security_config.xml`, que solo permite HTTP sin TLS a esa
   dirección.
4. Para firmar la release, agrega a `local.properties` el almacén de claves (un `.jks` guardado
   **fuera del repositorio**):

   ```properties
   fertitrack.release.storeFile=C\:/ruta/fertitrack-release.jks
   fertitrack.release.storePassword=<clave del almacén>
   fertitrack.release.keyAlias=fertitrack
   fertitrack.release.keyPassword=<clave de la llave>
   ```

   Sin estas líneas, la release se compila sin firmar y Android no la instala. Respalda el
   `.jks` junto con sus claves: si se pierde, las nuevas versiones no se podrán instalar sobre
   la anterior.

### Debug y release

| | debug | release |
|---|---|---|
| Nombre en el teléfono | FertiTrack Debug | FertiTrack |
| Id de la app | `com.fertitrack.app.debug` | `com.fertitrack.app` |
| Simulador | Sí | No: solo funciona con el robot real |
| Tamaño | ~20 MB | ~1,7 MB (R8 quita código y recursos sin uso) |

Las dos se pueden tener instaladas a la vez. En Android Studio, la variante se elige en
**Build → Select Build Variant…** (columna *Active Build Variant* del módulo `app`).

## Probar en el celular

1. En el teléfono, activa las **opciones de desarrollador**: en *Ajustes → Acerca del
   teléfono*, toca 7 veces *Número de compilación*.
2. En las opciones de desarrollador, activa la **depuración USB**. En Xiaomi activa también
   *Instalar vía USB*.
3. Conéctalo por cable y acepta *¿Permitir depuración USB?*.
4. En Android Studio, elige el teléfono y la variante (**debug** para usar el simulador,
   **release** para el robot real) y pulsa **Run ▶**.

### Sin robot: simulador (solo builds debug)

En la app, abre **Conexión** (el indicador de la barra superior) y activa **Usar robot
simulado**. Un servidor local dentro de la propia app (`FakeFertiTrackServer`) imita el
firmware: arranque, ciclos con sensado y riego, fertilización, modo automático, respuestas 409,
fallas inyectables y pérdidas de conexión. Igual que el robot real, tarda unos 10 s en
arrancar y recoger los brazos antes de quedar *En espera*, con 300 cm y 6 zonas ya
configurados. La build release no incluye el simulador.

### Con el robot real

1. Enciende el Maestro y espera a que termine su arranque (recoge los brazos y vuelve al
   origen).
2. En **Conexión**:
   - **Android 10 o superior:** *Conectar al Wi-Fi del robot* y acepta el diálogo de Android.
     El sistema recuerda la aprobación para ese AP.
   - **Android 8 y 9**, o si prefieres hacerlo a mano: *Abrir ajustes de Wi-Fi*, elige
     `FertiTrack_AP` y acepta seguir conectado aunque la red no tenga internet.
3. **Android 17:** concede el permiso de **Dispositivos cercanos / red local**. Sin él,
   Android bloquea el tráfico a `192.168.4.1` y el robot aparece como "sin respuesta".
4. Para probar con el robot usa el **cable USB**. Con la depuración inalámbrica, la PC deja de
   ver al teléfono en cuanto este se une al Wi-Fi del robot.

Los detalles de red y permisos por versión de Android están en
[docs/RED_ANDROID.md](docs/RED_ANDROID.md).

## Historial, exportar y avisos

- **Historial guardado:** mediciones (hasta 300, unas 24 h con el automático cada 30 min),
  riegos, bombeos por zona y los últimos 30 eventos se guardan en los archivos privados de la
  app y siguen ahí al volver a abrirla. El robot real y el simulador tienen historiales
  separados. Se borra desde **Gráficas → Historial guardado → Borrar historial**.
- **Exportar a CSV:** en la misma tarjeta, *Mediciones CSV* y *Riegos CSV* abren el selector
  del sistema (Descargas, Drive…; no piden permisos). Los archivos usan `;`, coma decimal y
  BOM UTF-8: se abren con doble clic en Excel en español.
- **Avisos en segundo plano** (**Conexión → Avisos en segundo plano**, apagado por defecto):
  un servicio en primer plano sigue consultando al robot con la app cerrada o la pantalla
  apagada, deja una notificación fija con el estado del robot y avisa de fallas, fin de
  riego (con la sugerencia de fertilizar), zonas secas tras un sensado y cortes de conexión
  de más de 30 s. Ningún aviso mueve el robot: fertilizar o regar se confirman en la app.
  Gasta más batería (wake lock parcial); apágalo cuando no haga falta. En Android 13+ pide el
  permiso de notificaciones; sin él la vigilancia sigue guardando el historial, sin avisos.

## Compilar y probar desde la terminal

```bash
./gradlew :app:assembleDebug        # APK debug en app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # pruebas unitarias y de UI (Robolectric) en la JVM
./gradlew :app:assembleRelease      # release con R8, firmada si local.properties tiene la firma
./gradlew :app:lintDebug
```

Las pruebas de UI guardan capturas de cada pantalla, en tema claro y oscuro, en
`app/build/capturas/`.

## Arquitectura

MVVM con `StateFlow`, inyección manual (`di/AppContainer`) y una sola pantalla Compose.

| Paquete | Qué hace |
|---|---|
| `config` | IP, SSID, clave (vía `BuildConfig`), intervalos y timeouts. |
| `domain.modelo` | `EstadoRobot` y enumeraciones del firmware; resultados de órdenes. |
| `domain.conexion` | Estados de la conexión y de la red Wi-Fi. |
| `domain.sesion` | `SesionTracker`: eventos, mediciones, riegos y la sugerencia de fertilizar. |
| `domain.traduccion` | Textos legibles de estados, fallas y resultados. |
| `domain.analisis` | Análisis automático por reglas (insights). |
| `data` | Cliente HTTP del Maestro, sondeo con backoff, enlace con la red del AP, seguimiento y guardado del historial, exportación CSV. |
| `vigilancia` | Servicio en primer plano, notificaciones y sus textos. |
| `simulador` | Interfaz del simulador; en `src/debug`, el `FakeFertiTrackServer`. |
| `ui` | `MainViewModel`, tema, pantallas (Inicio, Zonas, Gráficas, Control, Análisis y Conexión). |

### Reglas de comunicación y seguridad

- **Una petición a la vez:** el servidor del ESP32 atiende de a una y cada petición retrasa su
  máquina de estados. Hay una sola petición en vuelo, `/api/estado` se consulta cada 1,5 s, el
  timeout es de 3 s y el sondeo se pausa con la app en segundo plano, salvo que el usuario
  encienda la vigilancia.
- **Solo se reintenta `/api/estado`**, con backoff. Ninguna orden que mueva el robot se
  reintenta sola.
- **Rutinas solo en espera:** ciclo, sensado, fertilización y activar el automático solo se
  habilitan en `ESPERANDO`, sin falla y con el recorrido configurado. Un 409 ("sistema
  ocupado") se muestra como aviso, no como error.
- **Confirmación explícita** antes de iniciar rutinas, reiniciar y activar el modo automático.
- **DETENER siempre visible y habilitado.** Las confirmaciones se dibujan dentro de la
  pantalla, no en otra ventana, para no taparlo. La parada cancela la consulta en curso y se
  envía de inmediato.
- **Gráfica por medición:** se registra una muestra por cada medición del robot (al salir de
  `SENSADO_MIDIENDO`), no una por consulta, porque el robot mide por zona.
- **Sugerencia de fertilizar:** el firmware no avisa cuando termina un riego. La app lo deduce
  del paso de `RIEGO` a `NINGUNA` y solo sugiere fertilizar si se regó al menos una zona.

## Dependencias

| Dependencia | Para qué |
|---|---|
| Compose BOM, UI, Material 3 y tooling | Interfaz declarativa con Material 3 (el stack pedido). |
| material-icons-extended | Iconos de pestañas y estados; R8 quita los que no se usan en release. |
| activity-compose | `setContent` y lanzadores de permisos. |
| lifecycle-runtime-compose y viewmodel-compose | `collectAsStateWithLifecycle` y `ViewModel` con Factory. |
| core-ktx | Extensiones de AndroidX (`getSystemService`, comprobación de permisos). |
| OkHttp | Timeouts, cancelación y `SocketFactory` por red, para salir por el Wi-Fi del robot. |
| kotlinx.serialization | JSON de `/api/estado` y del historial guardado, sin reflexión; compatible con R8. |
| kotlinx-coroutines | Sondeo y órdenes sin bloquear la interfaz. |
| Vico (compose-m3) | Gráficas nativas de Compose con tema Material 3. MPAndroidChart obligaría a envolver Views. |
| JUnit 4 y coroutines-test | Pruebas unitarias con tiempo virtual. |
| Robolectric y compose ui-test | Pruebas de UI y capturas en la JVM, sin emulador. |

## Estado

- **Fase 1:** paridad con la app de escritorio usando el contrato real del firmware.
- **Fase 2 (pendiente):** configuración del recorrido, calibraciones de distancia y sensor, y
  mapa de fallas ampliado. La capa de datos ya implementa esas rutas.
- **Fase 3:** historial guardado, exportación a CSV y avisos en segundo plano (sin
  dependencias nuevas: notificaciones con `core-ktx`, almacenamiento en JSON).

Lo que el firmware no ofrece y convendría añadir está en
[docs/PETICIONES_FIRMWARE.md](docs/PETICIONES_FIRMWARE.md).
