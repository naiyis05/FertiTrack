# Asistente de IA por voz y texto — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** un asistente dentro de FertiTrack al que se le habla o se le escribe. Responde en voz alta con Gemini, guía por la app, lleva a la pantalla pedida, abre la confirmación de una rutina (sin enviarla nunca solo) y busca en internet. Con el Wi-Fi del robot sin internet, responde con los últimos datos guardados y dice de cuándo son.

**Arquitectura:**
- Paquete nuevo `asistente` en dos capas.
  - Lógica pura, probable en la JVM: contexto, etiquetas de acción, prompt, JSON de Gemini y textos.
  - Adaptadores Android: `SpeechRecognizer`, `TextToSpeech` y OkHttp.
- `AsistenteViewModel` coordina voz → Gemini → voz.
- El panel se dibuja **dentro** del contenido, igual que `OverlayConfirmacion`, así que la barra DETENER sigue visible.
- La última lectura del robot se guarda en disco con el mismo patrón que el historial.

**Tecnologías:**
- Kotlin y Compose Material 3.
- OkHttp 5 y kotlinx.serialization, que ya están en el proyecto.
- API REST de Gemini (`generateContent` + `google_search`).
- `SpeechRecognizer` y `TextToSpeech` del sistema.
- **Ninguna dependencia nueva.**

**Spec:** sección «Decisiones» de este documento, acordadas en la conversación del 11/11/2026.

## Decisiones

1. **Gemini por REST con OkHttp,** no con el SDK. El "Google AI client SDK for Android" está descontinuado, y su reemplazo (Firebase AI Logic) exige un proyecto de Firebase.
2. **Acciones como etiquetas en el texto** (`[[ir:CONTROL]]`, `[[rutina:FERTILIZAR]]`), no con *function calling*.
   - Funcionan junto con la búsqueda en Google en cualquier modelo.
   - Evitan el ida y vuelta de firmas de pensamiento.
3. **El asistente nunca mueve el robot.**
   - `[[rutina:X]]` solo abre la confirmación que ya existe (`AccionesUi.pedirConfirmacion`) y solo si `Traductor.motivoRutinasNoDisponibles(...) == null`. Si no, explica el motivo.
   - DETENER no pasa por la IA.
4. **El panel va dentro del `Box` de contenido,** no en un `ModalBottomSheet` ni en un `Dialog`. Nunca tapa la barra DETENER.
5. **Sin internet,** el asistente muestra un aviso claro y los datos se siguen guardando. No hay respuestas sin IA.
6. **Datos guardados.**
   - Además del historial, se guarda la **última foto del robot** (`FotoRobot`) por fuente (robot/simulador).
   - Con el robot conectado, el contexto dice "EN VIVO".
   - Si no, dice "datos de hace X (dd/MM HH:mm)".
7. **El prompt describe la app real:** Inicio, Zonas, Gráficas, Control, Análisis y Conexión. No describe "registrar cultivos".
8. **La búsqueda exige mostrar las sugerencias de Google tal cual:** si llega `searchEntryPoint.renderedContent`, se muestra en un `WebView` sin modificar, y las fuentes como enlaces.
9. **Tráfico de Gemini por la red por defecto** (datos móviles u otro Wi-Fi). Usa un `OkHttpClient` propio, nunca la `SocketFactory` del robot.

## Restricciones globales

- **Idioma:** español en la interfaz, los comentarios, los mensajes de commit y el prompt.
- **Clave:** `fertitrack.gemini.apiKey` en `local.properties`. No se versiona y va a `BuildConfig.GEMINI_API_KEY` con el mismo helper que `AP_PASSWORD`.
- **Modelo:** `fertitrack.gemini.modelo` en `gradle.properties`, por defecto `gemini-3.6-flash`, que la documentación lista como estable. Va a `BuildConfig.GEMINI_MODELO`. Se verifica con la clave real en la tarea 7.
- **Endpoint y cabecera:** `POST {URL_BASE}/models/{modelo}:generateContent`, con `URL_BASE = "https://generativelanguage.googleapis.com/v1beta"` y la clave en la cabecera `x-goog-api-key`.
- **Memoria:** se envían como mucho `MAX_TURNOS = 20` turnos (10 intercambios).
- **Timeouts de Gemini:** conexión 10 s y lectura 45 s, sin reintentos automáticos.
- **Reglas de la app que no cambian:**
  - Una sola petición al robot en vuelo.
  - DETENER siempre visible y habilitado.
  - Confirmación explícita antes de mover el robot.
- **Tests y estilo:** las pruebas existentes siguen pasando. Los nuevos parámetros de `FertiTrackPantalla` tienen valores por defecto.
- **Compilar y probar** desde `App/app movil`:
  - `./gradlew :app:testDebugUnitTest` (o con `--tests "<patrón>"`).
  - `./gradlew :app:lintDebug`.
  - `./gradlew :app:assembleRelease`.

## Puntos de revisión

1. **Conectado solo al AP del robot, sin datos:** Gemini falla rápido con el aviso "Sin internet", sin colgarse 45 s y sin afectar el sondeo. → Pruebas `falloDeExcepcionSinRed` (tarea 3) y `sinInternetMuestraAvisoYNoHabla` (tarea 5).
2. **La IA propone una rutina con el robot ocupado o en falla:** no se abre la confirmación y el mensaje añade el motivo. → `rutinaNoDisponibleExplicaMotivo` (tarea 5).
3. **DETENER con el panel abierto y la voz hablando:** el botón sigue visible y funciona. → `detenerVisibleConPanelAbierto` (tarea 6).
4. **Sin reconocedor de voz o sin permiso de micrófono:** aviso claro, y escribir sigue funcionando. → `sinPermisoMicrofonoAvisaYPermiteEscribir` (tarea 5).
5. **Respuestas largas o con markdown y enlaces leídas en voz alta:** se limpian y se parten en trozos de 3.900 caracteres como máximo, porque `TextToSpeech` corta a unos 4.000. → `limpiarParaVozQuitaMarkdownYUrls` y `partirRespetaElMaximo` (tarea 4).

---

### Tarea 1: Configuración y última foto del robot guardada

**Archivos:**
- Modificar: `app/build.gradle.kts`, con dos `buildConfigField` junto a `AP_PASSWORD`.
- Modificar: `gradle.properties`, con `fertitrack.gemini.modelo=gemini-3.6-flash` y un comentario.
- Crear: `app/src/main/java/com/fertitrack/app/config/AsistenteConfig.kt`.
- Crear: `app/src/main/java/com/fertitrack/app/asistente/FotoRobot.kt`.
- Crear: `app/src/main/java/com/fertitrack/app/data/historial/AlmacenFoto.kt`, con la interfaz, `AlmacenFotoArchivo` y `FotoDto`.
- Modificar: `app/src/main/java/com/fertitrack/app/data/historial/AlmacenHistorialArchivo.kt`, para extraer `escribirAtomico`.
- Modificar: `app/src/main/java/com/fertitrack/app/data/SeguimientoSesion.kt` y `di/AppContainer.kt`.
- Pruebas: `app/src/test/java/com/fertitrack/app/data/historial/AlmacenFotoArchivoTest.kt` y `data/SeguimientoSesionTest.kt`. Para las pruebas, `AlmacenFotoEnMemoria` va junto a `AlmacenEnMemoria`.

**Interfaces:**
- Produce:
  - `object AsistenteConfig { val CLAVE_GEMINI: String; val MODELO: String; const val URL_BASE: String; const val MAX_TURNOS = 20; const val TIMEOUT_CONEXION_MS = 10_000L; const val TIMEOUT_LECTURA_MS = 45_000L }`
  - `data class FotoRobot(val momentoMs: Long, val estado: EstadoMaquina, val estadoTexto: String, val totalZonas: Int, val distanciaCm: Float, val zonas: List<NivelSuelo>, val humedadPct: Float?, val codigoFalla: Int, val autoActivo: Boolean, val descansoSeg: Long)`, con `companion fun de(robot: EstadoRobot, momentoMs: Long): FotoRobot` y `fun mismoContenido(otra: FotoRobot?): Boolean`, que compara todo menos `momentoMs`.
  - `interface AlmacenFoto { suspend fun cargar(fuente: FuenteDatos): FotoRobot?; suspend fun guardar(fuente: FuenteDatos, foto: FotoRobot) }`
  - `SeguimientoSesion(repositorio, tracker, almacen, almacenFoto: AlmacenFoto, ambito, reloj: () -> Long = System::currentTimeMillis)` con `val ultimaFoto: StateFlow<FotoRobot?>`.
- Consume: `FuenteDatos` y el patrón de `AlmacenHistorialArchivo`.

- [ ] **Paso 1: pruebas que fallan**
  - `AlmacenFotoArchivoTest`:
    - `guardaYCargaPorFuente`: guarda una foto en ROBOT y otra en SIMULADOR, y `cargar` devuelve cada una igual.
    - `archivoDanadoDevuelveNull`: escribe `"{roto"` en `ultimo_estado_robot.json` y `cargar(ROBOT) == null`.
    - `nivelDesconocidoSeLeeComoDesconocido`: un JSON con `"zonas":["SECA","XYZ"]` da `[SECA, DESCONOCIDO]`.
  - `SeguimientoSesionTest`, con `RepositorioQuieto` y reloj virtual:
    - `fotoSeGuardaAlCambiarYSeRestaura`: se emite el estado A, se guarda una vez y `ultimaFoto.value?.estado == A.estado`. Se emite A otra vez a +10 s y no se guarda de nuevo. Se emite A a +5 min y sí se guarda.
    - Con un `SeguimientoSesion` nuevo sobre el mismo almacén, `ultimaFoto.value` arranca con la guardada.
    - `cambioDeFuenteCargaSuFoto`: con una foto guardada solo en SIMULADOR, al pasar a simulador `ultimaFoto` es esa foto. Al volver al robot sin foto guardada, es `null`.
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "*AlmacenFoto*" --tests "*SeguimientoSesionTest*"` debe fallar al compilar.
- [ ] **Paso 3: implementar**
  - Archivos `ultimo_estado_${fuente.name.lowercase()}.json`. Los enumerados se guardan por nombre y se leen con tolerancia, como en `HistorialDto`.
  - `escribirAtomico(destino: File, texto: String)` se comparte con el historial: temporal y `ATOMIC_MOVE`, y una `IOException` se ignora.
  - En `SeguimientoSesion`:
    - Al cambiar de fuente: guardar la foto en memoria de la fuente anterior y cargar la de la nueva.
    - Con cada `estado` no nulo: `FotoRobot.de(it, reloj())` → `ultimaFoto`.
    - Persistir si `!mismoContenido(ultimaGuardada)` o si pasaron 5 min desde la última escritura.
- [ ] **Paso 4:** la misma orden; deben pasar todas, incluidas las de `SeguimientoSesionTest` que ya existían.
- [ ] **Paso 5: commit** `Asistente: configuración y última lectura del robot guardada`.

### Tarea 2: Contexto, etiquetas de acción, prompt y sugerencias (lógica pura)

**Archivos:**
- Crear en `app/src/main/java/com/fertitrack/app/asistente/`: `AccionAsistente.kt`, `EtiquetasAccion.kt`, `ContextoAsistente.kt`, `PromptSistema.kt` y `Sugerencias.kt`.
- Pruebas en `app/src/test/java/com/fertitrack/app/asistente/`: `EtiquetasAccionTest.kt`, `ContextoAsistenteTest.kt`, `PromptSistemaTest.kt` y `SugerenciasTest.kt`.

**Interfaces:**
- Produce:
  - `enum class Destino { INICIO, ZONAS, GRAFICAS, CONTROL, ANALISIS, CONEXION }`
  - `enum class RutinaPropuesta(val modo: ModoInicio) { CICLO(ModoInicio.CICLO), SENSADO(ModoInicio.SENSADO), FERTILIZAR(ModoInicio.FERTILIZAR) }`
  - `sealed interface AccionAsistente { data class IrA(val destino: Destino); data class ProponerRutina(val rutina: RutinaPropuesta) }`
  - `data class RespuestaInterpretada(val texto: String, val acciones: List<AccionAsistente>)`
  - `object EtiquetasAccion { fun interpretar(texto: String): RespuestaInterpretada }`
  - `data class EntradaContexto(val destino: Destino, val enVivo: EstadoRobot?, val foto: FotoRobot?, val resumen: ResumenSesion, val motivoRutinas: String?)`
  - `object ContextoAsistente { fun construir(e: EntradaContexto, ahoraMs: Long): String }`
  - `object PromptSistema { val TEXTO: String }`
  - `object Sugerencias { fun para(destino: Destino, robot: EstadoRobot?): List<String> }`, con 2 o 3 frases cortas.
- Consume:
  - `Traductor.nombreEstado`, `describirFalla` y `nombreNivel`.
  - `Formatos.antiguedad(seg)` y `Formatos.fechaHoraCorta(ms)`.
  - `FotoRobot`, de la tarea 1.

- [ ] **Paso 1: pruebas que fallan**
  - `EtiquetasAccionTest`:
    - `"Te llevo. [[ir:CONTROL]]"` da el texto `"Te llevo."` y `[IrA(CONTROL)]`.
    - Tolera mayúsculas, minúsculas y espacios: `"[[ Rutina : fertilizar ]]"` da `ProponerRutina(FERTILIZAR)`.
    - Con una etiqueta desconocida (`[[ir:MARTE]]`, `[[borrar:x]]`) no hay acción, y la etiqueta se quita igual.
    - Con dos rutinas, se queda solo la primera.
    - Un texto sin etiquetas sale igual, recortado.
  - `ContextoAsistenteTest`:
    - `enVivoDiceEnVivo`: contiene `"EN VIVO"` y `"Pantalla actual: Control"`.
    - `soloFotoDiceAntiguedad`: con `ahoraMs = foto.momentoMs + 2h5min` contiene `"hace ${Formatos.antiguedad(7500)}"` y `"No son datos en vivo"`.
    - `sinDatos`: contiene `"Sin datos del robot"`.
    - `incluyeZonasYUltimoRiego`: contiene `"Zona 1: Seca"` y la hora del último riego.
    - `incluyeMotivoRutinas`: contiene el texto del motivo.
  - `PromptSistemaTest`:
    - Cada `Destino.name` y cada `RutinaPropuesta.name` aparece en `TEXTO`.
    - Contiene `"[[ir:"`, `"[[rutina:"`, `"DETENER"` y `"de cuándo son los datos"`.
  - `SugerenciasTest`: cada `Destino` da entre 2 y 3 sugerencias no vacías. Con una zona `SECA`, ZONAS incluye una sugerencia que menciona "secas".
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "com.fertitrack.app.asistente.*"` debe fallar.
- [ ] **Paso 3: implementar**
  - `EtiquetasAccion`: expresión regular `\[\[\s*(ir|rutina)\s*:\s*([A-Za-z_]+)\s*]]`, sin distinguir mayúsculas. Se quitan las etiquetas y los espacios dobles que dejen.
  - `PromptSistema.TEXTO`, en este orden:
    - el rol del texto del usuario, adaptado a la app real;
    - el manual por pantalla: qué muestra cada una y cómo se hace cada proceso (conectar al AP, iniciar ciclo, sensado o fertilización, modo automático, reiniciar tras una falla, exportar CSV, avisos en segundo plano, y configurar recorrido y sensor en `http://192.168.4.1`);
    - las reglas de las etiquetas: como mucho una `rutina` y solo si el usuario la pide; "la persona confirma en pantalla"; nunca decir que el robot ya hizo algo;
    - "di siempre de cuándo son los datos";
    - "para parar, el botón rojo DETENER";
    - "respuestas de 1 a 3 frases, sin markdown, para leer en voz alta";
    - "busca en internet solo si la pregunta va más allá de la app".
- [ ] **Paso 4:** la misma orden; deben pasar.
- [ ] **Paso 5: commit** `Asistente: contexto, prompt y etiquetas de acción`.

### Tarea 3: Cliente de Gemini (REST)

**Archivos:**
- Crear en `app/src/main/java/com/fertitrack/app/asistente/gemini/`: `Cerebro.kt` (modelos e interfaz), `GeminiJson.kt` y `ClienteGemini.kt`.
- Pruebas: `app/src/test/java/com/fertitrack/app/asistente/gemini/GeminiJsonTest.kt` y `ClienteGeminiTest.kt`.

**Interfaces:**
- Produce:
  - `data class TurnoChat(val deUsuario: Boolean, val texto: String, val partesModelo: JsonArray? = null)`
  - `data class Fuente(val titulo: String, val url: String)`
  - `enum class TipoFallo { SIN_CLAVE, SIN_INTERNET, CLAVE_INVALIDA, CUOTA, BLOQUEADA, MODELO_NO_EXISTE, SERVIDOR, RESPUESTA_RARA }`
  - `sealed interface RespuestaCerebro { data class Ok(val texto: String, val fuentes: List<Fuente>, val sugerenciasHtml: String?, val partesModelo: JsonArray); data class Fallo(val tipo: TipoFallo, val detalle: String? = null) }`
  - `fun interface Cerebro { suspend fun responder(historial: List<TurnoChat>, contexto: String): RespuestaCerebro }`
  - `object GeminiJson { fun peticion(historial: List<TurnoChat>, contexto: String, sistema: String): String; fun interpretar(codigo: Int, cuerpo: String): RespuestaCerebro; fun falloDe(e: IOException): RespuestaCerebro.Fallo }`
  - `class ClienteGemini(clave: String, modelo: String, urlBase: String = AsistenteConfig.URL_BASE, http: OkHttpClient = <propio, con los timeouts de AsistenteConfig>) : Cerebro`
- Consume: `PromptSistema.TEXTO` (tarea 2) y `AsistenteConfig` (tarea 1).

- [ ] **Paso 1: pruebas que fallan**
  - `GeminiJsonTest`, para la petición:
    - `peticionTieneSistemaHerramientaYContexto`: el JSON tiene `systemInstruction.parts[0].text == sistema` y `tools[0]` con la clave `google_search`.
    - Los roles van alternados `user`/`model`.
    - **Solo el último** turno del usuario lleva `contexto`; los anteriores llevan solo su texto.
    - `partesDelModeloSeReenvianTalCual`: un turno de modelo con `partesModelo = [{"text":"hola","thoughtSignature":"abc"}]` se reenvía idéntico.
  - `GeminiJsonTest`, para la respuesta:
    - `respuestaConTextoYFuentes`: con un cuerpo de ejemplo (candidates → content.parts con un texto y una parte `"thought":true`, y `groundingMetadata` con dos `groundingChunks[].web{uri,title}` y `searchEntryPoint.renderedContent`), el resultado es `Ok`. El texto no incluye la parte de pensamiento, hay 2 fuentes sin duplicados, `sugerenciasHtml` está presente y `partesModelo` es el arreglo completo.
    - `errores`, por código y cuerpo:
      - 400 con `"API_KEY_INVALID"` → `CLAVE_INVALIDA`;
      - 403 → `CLAVE_INVALIDA`;
      - 404 → `MODELO_NO_EXISTE`;
      - 429 → `CUOTA`;
      - 503 → `SERVIDOR`;
      - 200 con `"finishReason":"SAFETY"` → `BLOQUEADA`;
      - 200 con `promptFeedback.blockReason` → `BLOQUEADA`;
      - 200 sin candidatos → `RESPUESTA_RARA`;
      - `"<html>"` → `RESPUESTA_RARA`.
    - `falloDeExcepcionSinRed`: `UnknownHostException`, `ConnectException` y `NoRouteToHostException` dan `SIN_INTERNET`; `SocketTimeoutException` da `SERVIDOR`.
  - `ClienteGeminiTest`:
    - `sinClaveNoLlamaALaRed`: `ClienteGemini("", ...)` responde `Fallo(SIN_CLAVE)`.
    - `puertoCerradoEsSinInternet`: con `urlBase = "http://127.0.0.1:1"` responde `Fallo(SIN_INTERNET)`, en menos de 5 s y sin excepciones.
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "com.fertitrack.app.asistente.gemini.*"` debe fallar.
- [ ] **Paso 3: implementar**
  - JSON con `JsonObject`/`buildJsonObject`, sin DTO.
  - El texto del último turno del usuario es `"Contexto de FertiTrack (úsalo, no lo recites):\n$contexto\n\nPregunta del usuario:\n$texto"`.
  - El `Call` se cancela con la corrutina, con el mismo patrón que `ClienteMaestroHttp.ejecutar`.
  - El cliente usa la red por defecto: **sin** `socketFactory`.
- [ ] **Paso 4:** la misma orden; deben pasar.
- [ ] **Paso 5: commit** `Asistente: cliente REST de Gemini con búsqueda en Google`.

### Tarea 4: Voz (STT y TTS)

**Archivos:**
- Crear en `app/src/main/java/com/fertitrack/app/asistente/voz/`: `Voz.kt` (interfaces y estados), `TextosVoz.kt`, `EntradaVozAndroid.kt` y `SalidaVozAndroid.kt`.
- Modificar `app/src/main/AndroidManifest.xml`:
  - Agregar `RECORD_AUDIO`.
  - Agregar `<queries>` con las acciones `android.speech.RecognitionService` y `android.intent.action.TTS_SERVICE`, que exige Android 11+.
- Prueba: `app/src/test/java/com/fertitrack/app/asistente/voz/TextosVozTest.kt`.

**Interfaces:**
- Produce:
  - `sealed interface EstadoEscucha { data object Inactiva; data class Escuchando(val parcial: String, val nivel: Float); data class Transcribiendo(val parcial: String); data class Terminada(val texto: String); data class Error(val mensaje: String) }`
  - `interface EntradaVoz { val disponible: Boolean; val estado: StateFlow<EstadoEscucha>; fun empezar(); fun detener(); fun cancelar(); fun liberar() }`
  - `interface SalidaVoz { val hablando: StateFlow<Boolean>; fun hablar(texto: String); fun detener(); fun liberar() }`
  - `object TextosVoz { const val MAX_TROZO = 3900; fun mensajeError(codigo: Int): String; fun limpiarParaVoz(texto: String): String; fun partir(texto: String, max: Int = MAX_TROZO): List<String> }`

- [ ] **Paso 1: pruebas que fallan** (`TextosVozTest`)
  - `mensajesDeError`: `ERROR_NO_MATCH` → contiene "No te entendí"; `ERROR_NETWORK` y `ERROR_NETWORK_TIMEOUT` → contienen "internet"; `ERROR_INSUFFICIENT_PERMISSIONS` → contiene "micrófono"; un código desconocido (99) → no vacío.
  - `limpiarParaVozQuitaMarkdownYUrls`: `"**Hola** _ya_ ve a https://x.com/a y # Título"` da `"Hola ya ve a y Título"`, con los espacios normalizados.
  - `partirRespetaElMaximo`: un texto de 9.000 caracteres da trozos de ≤ 3.900 que, unidos, reproducen el texto. Corta en un fin de frase (". ") cuando hay uno antes del máximo.
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "*TextosVozTest*"` debe fallar.
- [ ] **Paso 3: implementar**
  - `EntradaVozAndroid`: `SpeechRecognizer` en el hilo principal.
    - `LANGUAGE_MODEL_FREE_FORM`, resultados parciales y `MAX_RESULTS 1`.
    - Idioma: el del sistema si es `es`, si no `es-ES`.
    - `onRmsChanged` → `nivel = ((rms + 2) / 12).coerceIn(0f, 1f)`.
    - `onEndOfSpeech` → `Transcribiendo`.
    - `disponible = SpeechRecognizer.isRecognitionAvailable(ctx)`.
  - `SalidaVozAndroid`: `TextToSpeech` con el idioma elegido igual.
    - Si el idioma falta, sigue con el que haya.
    - `hablar` detiene lo anterior y encola los trozos de `partir(limpiarParaVoz(t))`.
    - `hablando` vale true hasta `onDone` del último trozo, `onStop` u `onError`.
    - El texto pedido antes de la inicialización queda pendiente.
- [ ] **Paso 4:** `./gradlew :app:testDebugUnitTest --tests "*TextosVozTest*"` y `./gradlew :app:lintDebug` deben pasar.
- [ ] **Paso 5: commit** `Asistente: reconocimiento y síntesis de voz del sistema`.

### Tarea 5: AsistenteViewModel

**Archivos:**
- Crear: `app/src/main/java/com/fertitrack/app/asistente/AsistenteViewModel.kt` (estado, acciones, `Factory`) y `asistente/TextosAsistente.kt` (mensajes de fallo).
- Pruebas:
  - `app/src/test/java/com/fertitrack/app/asistente/AsistenteViewModelTest.kt`.
  - Dobles `CerebroFalso`, `EntradaVozFalsa` y `SalidaVozFalsa` en el mismo paquete de pruebas.

**Interfaces:**
- Produce:
  - `data class MensajeChat(val id: Long, val deUsuario: Boolean, val texto: String, val fuentes: List<Fuente> = emptyList(), val sugerenciasHtml: String? = null, val acciones: List<AccionAsistente> = emptyList(), val esError: Boolean = false)`
  - `data class EstadoAsistente(val abierto: Boolean = false, val mensajes: List<MensajeChat> = emptyList(), val transcripcion: String = "", val escuchando: Boolean = false, val nivelVoz: Float = 0f, val pensando: Boolean = false, val hablando: Boolean = false, val vozActivada: Boolean = true, val vozDisponible: Boolean = false, val claveConfigurada: Boolean = false, val sugerencias: List<String> = emptyList())`
  - `interface AccionesAsistente { fun abrir(); fun cerrar(); fun enviarTexto(texto: String); fun alternarMicrofono(); fun cancelar(); fun alternarVoz(); fun pantallaActual(destino: Destino); fun permisoMicrofono(concedido: Boolean); fun repetirAccion(accion: AccionAsistente) }`, más `object AccionesAsistenteNulas : AccionesAsistente` sin efecto.
  - `class AsistenteViewModel(cerebro: Cerebro, entrada: EntradaVoz, salida: SalidaVoz, robot: StateFlow<EstadoRobot?>, conexion: StateFlow<EstadoConexion>, resumen: StateFlow<ResumenSesion>, ultimaFoto: StateFlow<FotoRobot?>, claveConfigurada: Boolean, reloj: () -> Long) : ViewModel(), AccionesAsistente`, con `val estado: StateFlow<EstadoAsistente>` y `val eventos: SharedFlow<AccionAsistente>`.
  - `object TextosAsistente { fun fallo(tipo: TipoFallo): String; fun rutinaNoDisponible(motivo: String): String }`
- Consume: las tareas 1 a 4 y `Traductor.motivoRutinasNoDisponibles(robot, conexion)`.

- [ ] **Paso 1: pruebas que fallan** (`runTest` con `StandardTestDispatcher`, `Dispatchers.setMain`)
  - `textoProduceRespuestaYHabla`: `enviarTexto("¿qué zonas están secas?")` con `CerebroFalso` → `Ok("La zona 2 está seca.")`.
    - Durante la llamada, `pensando == true`.
    - Después hay 2 mensajes y `salida.dichos == ["La zona 2 está seca."]`.
  - `vozDesactivadaNoHabla`: tras `alternarVoz()`, `salida.dichos` queda vacío.
  - `irAEmiteEvento`: con la respuesta `"Vamos. [[ir:GRAFICAS]]"`, `eventos` emite `IrA(GRAFICAS)` y el mensaje muestra `"Vamos."`.
  - `rutinaDisponibleEmiteEvento`: con el robot `ESPERANDO`, conectado y configurado, `[[rutina:FERTILIZAR]]` emite `ProponerRutina(FERTILIZAR)`.
  - `rutinaNoDisponibleExplicaMotivo`: con el robot en `RIEGO_AVANZANDO`, no se emite nada y el mensaje termina con `TextosAsistente.rutinaNoDisponible(motivo)`.
  - `sinInternetMuestraAvisoYNoHabla`: `Fallo(SIN_INTERNET)` da un mensaje `esError` con `TextosAsistente.fallo(SIN_INTERNET)`, `pensando == false` y la salida dice el aviso solo si la voz está activada.
  - `contextoUsaFotoSinConexion`: con conexión `Reintentando` y `ultimaFoto` presente, el `contexto` que recibe `CerebroFalso` contiene `"hace"` y no contiene `"EN VIVO"`.
  - `historialSeLimitaA20Turnos`: tras 15 intercambios, `CerebroFalso.ultimoHistorial.size <= 20` y el último turno es del usuario.
  - `microfonoInterrumpeLaVoz`: con `salida.hablando = true`, `alternarMicrofono()` llama a `salida.detener()` antes de `entrada.empezar()`.
  - `transcripcionEnVivoYEnvio`: `EntradaVozFalsa` emite `Escuchando("qué ho", 0.5f)` → `transcripcion == "qué ho"` y `escuchando`. Luego emite `Terminada("qué hora es")` → `transcripcion == ""` y se envía ese texto.
  - `sinPermisoMicrofonoAvisaYPermiteEscribir`: `permisoMicrofono(false)` agrega un mensaje con "micrófono", y `enviarTexto` sigue funcionando.
  - `nuevoMensajeCancelaElAnterior`: con un `CerebroFalso` que suspende, el segundo `enviarTexto` cancela la primera llamada, y solo llega la respuesta del segundo.
  - `cancelarDetieneTodo`: `cancelar()` deja `pensando`, `escuchando` y `hablando` en false y llama a `entrada.cancelar()` y `salida.detener()`.
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "*AsistenteViewModelTest*"` debe fallar.
- [ ] **Paso 3: implementar**
  - `sugerencias = Sugerencias.para(destino, robot)`, recalculadas al cambiar de pantalla o de robot.
  - `claveConfigurada` viene de `AsistenteConfig.CLAVE_GEMINI.isNotBlank()` en la `Factory`.
  - En `onCleared` se llama a `liberar()` de la entrada y de la salida.
  - La `Factory` sigue el patrón de `MainViewModel.Factory`:
    - `EntradaVozAndroid` y `SalidaVozAndroid` con el contexto de la aplicación;
    - `ClienteGemini(AsistenteConfig.CLAVE_GEMINI, AsistenteConfig.MODELO)` sale de `AppContainer`;
    - los flujos salen de `repositorio` y `seguimiento`.
- [ ] **Paso 4:** la misma orden; deben pasar.
- [ ] **Paso 5: commit** `Asistente: ViewModel que coordina voz, Gemini y acciones`.

### Tarea 6: Panel del asistente y conexión con la pantalla

**Archivos:**
- Crear: `app/src/main/java/com/fertitrack/app/ui/componentes/PanelAsistente.kt`.
- Modificar: `ui/FertiTrackPantalla.kt`, `MainActivity.kt` y `di/AppContainer.kt` (`val cerebro: Cerebro`).
- Pruebas:
  - `app/src/test/java/com/fertitrack/app/ui/pantallas/PruebasAsistente.kt` (Robolectric y Compose).
  - Capturas nuevas en `CapturasPantallas`, en tema claro y oscuro.

**Interfaces:**
- Produce:
  - `@Composable fun PanelAsistente(estado: EstadoAsistente, acciones: AccionesAsistente, onMicrofono: () -> Unit, modifier: Modifier = Modifier)`.
  - Etiquetas: `TAG_BOTON_ASISTENTE`, `TAG_PANEL_ASISTENTE`, `TAG_CAMPO_ASISTENTE`, `TAG_ENVIAR_ASISTENTE`, `TAG_MICROFONO`, `TAG_TRANSCRIPCION`, `TAG_CERRAR_ASISTENTE`, `TAG_VOZ_ASISTENTE`, `fun tagSugerencia(i: Int)` y `fun tagAccionAsistente(i: Int)`.
  - `FertiTrackPantalla(..., asistente: EstadoAsistente = EstadoAsistente(), accionesAsistente: AccionesAsistente = AccionesAsistenteNulas, eventosAsistente: Flow<AccionAsistente> = emptyFlow())`, también en la variante `internal`.
- Consume: la tarea 5, `AccionesUi.pedirConfirmacion` y `AccionConfirmable.Iniciar(modo)`.

- [ ] **Paso 1: pruebas que fallan** (`PruebasAsistente`)
  - `botonAbreElPanel`: tocar `TAG_BOTON_ASISTENTE` llama a `abrir()` en el doble. Con `asistente.abierto = true`, `TAG_PANEL_ASISTENTE` se ve.
  - `detenerVisibleConPanelAbierto`: con el panel abierto y `hablando = true`, el botón DETENER (la etiqueta de `BarraParada`) se ve; al tocarlo se llama a `acciones.detenerEmergencia()`.
  - `escribirYEnviar`: escribir en `TAG_CAMPO_ASISTENTE` y tocar `TAG_ENVIAR_ASISTENTE` llama a `enviarTexto("hola")`.
  - `sugerenciasConChatVacio`: con `sugerencias = ["A","B"]` y sin mensajes se ven `tagSugerencia(0)` y `tagSugerencia(1)`; al tocar una se envía su texto.
  - `transcripcionEnVivoVisible`: con `escuchando = true` y `transcripcion = "qué zonas"`, `TAG_TRANSCRIPCION` muestra "qué zonas".
  - `eventoIrANavega`: emitir `IrA(GRAFICAS)` en `eventosAsistente` deja la pestaña Gráficas seleccionada.
  - `eventoRutinaAbreConfirmacion`: emitir `ProponerRutina(FERTILIZAR)` llama a `pedirConfirmacion(Iniciar(FERTILIZAR))` y a `cerrar()`, y selecciona Control.
  - `fuentesComoEnlaces`: un mensaje con 2 `Fuente` muestra sus títulos.
  - `sinClaveAvisa`: con `claveConfigurada = false`, el panel muestra "Falta la clave de Gemini".
- [ ] **Paso 2:** `./gradlew :app:testDebugUnitTest --tests "*PruebasAsistente*"` debe fallar.
- [ ] **Paso 3: implementar**
  - **Panel:** va en el `Box` de contenido, alineado abajo, con `fillMaxWidth()` y alto máximo del 60 % (`BoxWithConstraints`). En pantallas anchas (≥ 840 dp) es un panel lateral de 420 dp a la derecha. `OverlayConfirmacion` se dibuja **después**, encima del panel.
  - **Contenido del panel:**
    - cabecera con título, `TAG_VOZ_ASISTENTE` y cerrar;
    - `LazyColumn` de burbujas que baja sola al último mensaje;
    - chips de fuentes que abren el navegador con `LocalUriHandler`;
    - `WebView` con `loadDataWithBaseURL(null, html, "text/html", "utf-8", null)` cuando hay `sugerenciasHtml`, cuyos enlaces se abren fuera con `ACTION_VIEW`;
    - chips de acción ("Ir a Gráficas", "Abrir confirmación: Fertilizar") que llaman a `repetirAccion`;
    - fila de transcripción en vivo;
    - fila con campo de texto, enviar y un micrófono grande con un anillo que crece con `nivelVoz`;
    - botón "Parar voz" visible mientras habla.
  - **Botón del asistente** (`Icons.Rounded.AutoAwesome`, descripción "Asistente") en `BarraSuperior`, antes de `ChipConexion`.
  - **Cambios en `FertiTrackPantalla`:**
    - `LaunchedEffect(pestana, verConexion)` llama a `pantallaActual(...)`.
    - Un `LaunchedEffect` recoge `eventosAsistente`:
      - `IrA(CONEXION)` → `verConexion = true`;
      - el resto de `IrA` → la pestaña correspondiente;
      - `ProponerRutina` → pestaña Control, `accionesAsistente.cerrar()` y `acciones.pedirConfirmacion(AccionConfirmable.Iniciar(r.modo))`.
    - `BackHandler` también cierra el panel.
    - El permiso de micrófono se pide con `rememberLauncherForActivityResult(RequestPermission())`. Si ya está concedido, se llama a `alternarMicrofono()` directamente. Si no, se pide y el resultado va a `permisoMicrofono(concedido)` y, si se concedió, a `alternarMicrofono()`.
  - **`MainActivity`:** `private val asistente: AsistenteViewModel by viewModels { AsistenteViewModel.Factory }` y le pasa a `FertiTrackPantalla` `asistente.estado` (recogido), `asistente` como `accionesAsistente` y `asistente.eventos`.
- [ ] **Paso 4:** `./gradlew :app:testDebugUnitTest` (completo) y `./gradlew :app:lintDebug` deben pasar.
- [ ] **Paso 5: commit** `Asistente: panel con voz y texto dentro de la pantalla`.

### Tarea 7: Documentación y verificación en un dispositivo

**Archivos:**
- Modificar `README.md`:
  - una sección "Asistente" con la clave, el modelo, que sin internet no responde, y que la búsqueda muestra las sugerencias de Google;
  - la tabla de dependencias dice que no hay dependencias nuevas;
  - "Estado".
- Modificar `docs/RED_ANDROID.md`: el tráfico de Gemini sale por la red por defecto, nunca por la del robot.

- [ ] **Paso 1:** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` debe dar `BUILD SUCCESSFUL`.
- [ ] **Paso 2: prueba en el S23 Ultra,** con la clave real en `local.properties`:
  - "¿qué zonas están secas?" responde, y si hay datos guardados dice de cuándo son.
  - "llévame a gráficas" cambia de pestaña.
  - "fertiliza" abre la confirmación o explica por qué no se puede.
  - "¿qué tiempo hará mañana en Bogotá?" busca en internet y muestra las sugerencias y las fuentes.
  - Hablar por el micrófono muestra la transcripción en vivo.
  - Tocar el micrófono mientras habla corta la voz.
  - Con el panel abierto, DETENER sigue visible.
  - En modo avión, o solo con `FertiTrack_AP`, aparece el aviso "Sin internet".
  - Si el modelo da 404, cambiar `fertitrack.gemini.modelo` y repetir.
- [ ] **Paso 3: commit** `Asistente: documentación y verificación`.
