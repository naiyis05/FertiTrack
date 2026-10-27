package com.fertitrack.app.data

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.data.red.EnlaceRed
import com.fertitrack.app.data.remote.ConsultaEstado
import com.fertitrack.app.data.remote.DestinoMaestro
import com.fertitrack.app.data.remote.MaestroApi
import com.fertitrack.app.data.remote.OrdenMaestro
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.simulador.Simulador
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RobotRepository] sobre [MaestroApi]. Reglas (ver también docs/RED_ANDROID.md):
 *
 * - **Una petición en vuelo**: [turno] (Mutex justo, FIFO) lo comparten el sondeo y las órdenes.
 *   El sondeo usa tryLock: si hay una orden en curso, ese turno se salta.
 * - **Sondeo**: /api/estado cada [RobotConfig.INTERVALO_SONDEO_MS] contados desde que terminó la
 *   consulta anterior. 1–2 fallos seguidos → SinRespuesta al ritmo normal; desde el 3.º →
 *   Reintentando con backoff 2 s, 4 s, 8 s… hasta [RobotConfig.BACKOFF_MAXIMO_MS]. El primer
 *   éxito vuelve a Conectado y reinicia el backoff.
 * - **Órdenes**: esperan su turno (como mucho una consulta, ≤ 3 s) y se envían UNA vez. Tras un
 *   éxito se adelanta la siguiente consulta para que la UI se actualice enseguida.
 * - **Detener**: no espera turno. Cancela la consulta en curso (Call.cancel()) y envía
 *   /api/detener al instante, aunque haya otra orden en vuelo: el ESP32 atiende las conexiones
 *   de a una y en orden de llegada, así que la parada se procesa justo después de esa orden y
 *   gana. Es la única excepción a "una petición en vuelo" (como mucho dos, un instante).
 * - **Seguridad**: iniciar y activar el modo automático solo se envían si el último estado
 *   conocido es ESPERANDO sin falla; si no, Ocupado sin tocar la red (el firmware también lo
 *   valida, salvo /api/auto, que acepta en cualquier estado).
 *
 * [ambito] (SupervisorJob + Dispatchers.IO en la app) y [reloj] se inyectan para probar con
 * tiempo virtual. [reloj] es el reloj del sistema en ms (ultimaRespuestaMs, proximoIntentoMs).
 */
class RobotRepositoryImpl(
    private val api: MaestroApi,
    private val enlace: EnlaceRed,
    private val simulador: Simulador,
    private val ambito: CoroutineScope,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val urlRobot: String = RobotConfig.URL_BASE_ROBOT,
) : RobotRepository {

    private val _conexion = MutableStateFlow<EstadoConexion>(EstadoConexion.Pausado)
    override val conexion: StateFlow<EstadoConexion> = _conexion.asStateFlow()

    private val _red = MutableStateFlow<EstadoRed>(enlace.estado.value)
    override val red: StateFlow<EstadoRed> = _red.asStateFlow()

    private val _estado = MutableStateFlow<EstadoRobot?>(null)
    override val estado: StateFlow<EstadoRobot?> = _estado.asStateFlow()

    private val _ultimaRespuestaMs = MutableStateFlow<Long?>(null)
    override val ultimaRespuestaMs: StateFlow<Long?> = _ultimaRespuestaMs.asStateFlow()

    private val _usandoSimulador = MutableStateFlow(false)
    override val usandoSimulador: StateFlow<Boolean> = _usandoSimulador.asStateFlow()

    override val simuladorDisponible: Boolean get() = simulador.disponible

    /** Una sola petición en vuelo (salvo /api/detener, ver arriba). */
    private val turno = Mutex()

    /** Protege el estado mutable de abajo. */
    private val cerrojo = Any()
    private var trabajoSondeo: Job? = null
    private var consultaEnCurso: Deferred<ConsultaEstado>? = null
    private var detencionesEnCurso = 0
    private var fallosSeguidos = 0

    /** Cambia al pasar de robot a simulador o al revés: una consulta al destino anterior se descarta. */
    private var generacionDestino = 0
    private var generacionConsulta = 0

    /** Señal para adelantar la próxima consulta (tras una orden con éxito o un cambio de red). */
    private val consultaInmediata = Channel<Unit>(Channel.CONFLATED)

    init {
        api.fijarDestino(DestinoMaestro(urlRobot, enlace.fabricaSockets.value))
        enlace.activar()
        ambito.launch {
            combine(enlace.estado, _usandoSimulador) { estadoEnlace, simulado ->
                if (simulado) EstadoRed.Simulador else estadoEnlace
            }.collect { _red.value = it }
        }
        ambito.launch {
            enlace.fabricaSockets.collect { fabrica ->
                val cambio = synchronized(cerrojo) {
                    if (_usandoSimulador.value) {
                        false
                    } else {
                        api.fijarDestino(DestinoMaestro(urlRobot, fabrica))
                        true
                    }
                }
                // Apareció la red del robot: no esperar al backoff para volver a intentarlo.
                if (cambio && fabrica != null) consultaInmediata.trySend(Unit)
            }
        }
    }

    // ── Sondeo ────────────────────────────────────────────────────────────

    override fun reanudarSondeo() {
        synchronized(cerrojo) {
            if (trabajoSondeo?.isActive == true) return
            fallosSeguidos = 0
            _conexion.value = EstadoConexion.Conectando
            trabajoSondeo = ambito.launch(start = CoroutineStart.LAZY) { bucleSondeo() }
            trabajoSondeo?.start()
        }
    }

    override fun pausarSondeo() {
        synchronized(cerrojo) {
            val trabajo = trabajoSondeo ?: return
            trabajoSondeo = null
            trabajo.cancel() // también cancela la consulta en curso (es hija del bucle)
            _conexion.value = EstadoConexion.Pausado
        }
    }

    private suspend fun bucleSondeo() {
        val miTrabajo = currentCoroutineContext()[Job]
        while (true) {
            val espera = turnoDeSondeo(miTrabajo)
            withTimeoutOrNull(espera) { consultaInmediata.receive() }
        }
    }

    /** Un turno del sondeo; devuelve cuánto esperar hasta el siguiente. */
    private suspend fun turnoDeSondeo(miTrabajo: Job?): Long {
        if (!turno.tryLock()) return RobotConfig.INTERVALO_SONDEO_MS // orden en curso: se salta
        val resultado: ConsultaEstado? = try {
            coroutineScope {
                val consulta = synchronized(cerrojo) {
                    if (detencionesEnCurso > 0) {
                        null
                    } else {
                        generacionConsulta = generacionDestino
                        async(start = CoroutineStart.LAZY) { api.consultarEstado() }
                            .also { consultaEnCurso = it }
                    }
                } ?: return@coroutineScope null
                try {
                    consulta.await()
                } catch (e: CancellationException) {
                    // Si el bucle sigue activo, la canceló detener(): no cuenta como fallo.
                    currentCoroutineContext().ensureActive()
                    null
                } finally {
                    synchronized(cerrojo) { if (consultaEnCurso === consulta) consultaEnCurso = null }
                }
            }
        } finally {
            turno.unlock()
        }
        return when (resultado) {
            null -> RobotConfig.INTERVALO_SONDEO_MS
            else -> registrar(resultado, miTrabajo)
        }
    }

    /** Aplica el resultado si este bucle sigue siendo el vigente; devuelve la espera siguiente. */
    private fun registrar(resultado: ConsultaEstado, miTrabajo: Job?): Long = synchronized(cerrojo) {
        // Pausado o reanudado entretanto, o la respuesta es del destino anterior: se descarta.
        if (trabajoSondeo !== miTrabajo || generacionConsulta != generacionDestino) {
            return RobotConfig.INTERVALO_SONDEO_MS
        }
        when (resultado) {
            is ConsultaEstado.Correcta -> {
                fallosSeguidos = 0
                _estado.value = resultado.estado
                _ultimaRespuestaMs.value = reloj()
                _conexion.value = EstadoConexion.Conectado
                RobotConfig.INTERVALO_SONDEO_MS
            }
            is ConsultaEstado.Fallida -> {
                fallosSeguidos++
                if (fallosSeguidos < FALLOS_PARA_BACKOFF) {
                    _conexion.value = EstadoConexion.SinRespuesta(fallosSeguidos, resultado.detalle)
                    RobotConfig.INTERVALO_SONDEO_MS
                } else {
                    val espera = esperaBackoff(fallosSeguidos)
                    _conexion.value = EstadoConexion.Reintentando(
                        fallosSeguidos = fallosSeguidos,
                        proximoIntentoMs = reloj() + espera,
                        detalle = resultado.detalle,
                    )
                    espera
                }
            }
        }
    }

    // ── Red y simulador ───────────────────────────────────────────────────

    override fun conectarWifiRobot() = enlace.conectarAutomatico()

    override fun reevaluarRed() = enlace.reevaluar()

    override fun usarSimulador(activo: Boolean) {
        if (!simulador.disponible) return
        synchronized(cerrojo) {
            if (_usandoSimulador.value == activo) return
            if (activo) {
                val url = try {
                    simulador.iniciar()
                } catch (e: RuntimeException) {
                    return // el simulador no pudo arrancar: se sigue con el robot
                }
                api.fijarDestino(DestinoMaestro(url, fabricaSockets = null))
            } else {
                simulador.detener()
                api.fijarDestino(DestinoMaestro(urlRobot, enlace.fabricaSockets.value))
            }
            _usandoSimulador.value = activo
            generacionDestino++
            // Lo recibido del otro destino no vale para este.
            _estado.value = null
            _ultimaRespuestaMs.value = null
            fallosSeguidos = 0
            if (trabajoSondeo?.isActive == true) _conexion.value = EstadoConexion.Conectando
        }
        consultaInmediata.trySend(Unit)
    }

    // ── Órdenes ───────────────────────────────────────────────────────────

    override suspend fun iniciar(modo: ModoInicio): ResultadoComando =
        motivoNoListo()?.let { ResultadoComando.Ocupado(it) } ?: ordenar(OrdenMaestro.Iniciar(modo))

    override suspend fun detener(): ResultadoComando {
        synchronized(cerrojo) {
            detencionesEnCurso++
            consultaEnCurso?.cancel() // llega a OkHttp como Call.cancel()
        }
        try {
            return api.enviar(OrdenMaestro.Detener).also(::adelantarConsultaSiExito)
        } finally {
            synchronized(cerrojo) { detencionesEnCurso-- }
        }
    }

    override suspend fun reiniciar(): ResultadoComando = ordenar(OrdenMaestro.Reiniciar)

    override suspend fun configurarAuto(activar: Boolean, descansoSeg: Long?): ResultadoComando {
        if (activar) motivoNoListo()?.let { return ResultadoComando.Ocupado(it) }
        return ordenar(OrdenMaestro.Auto(activar, descansoSeg))
    }

    override suspend fun configurarRecorrido(
        distanciaCm: Float,
        anchoZonaCm: Float?,
        pasosPorCm: Float?,
    ): ResultadoComando = ordenar(OrdenMaestro.Configurar(distanciaCm, anchoZonaCm, pasosPorCm))

    override suspend fun pruebaMovimiento(cm: Float): ResultadoComando =
        ordenar(OrdenMaestro.PruebaMovimiento(cm))

    override suspend fun calibrarDistancia(realCm: Float, pedidoCm: Float?): ResultadoComando =
        ordenar(OrdenMaestro.CalibrarDistancia(realCm, pedidoCm))

    override suspend fun pruebaRetorno(): ResultadoComando = ordenar(OrdenMaestro.PruebaRetorno)

    override suspend fun pruebaSensor(): ResultadoComando = ordenar(OrdenMaestro.PruebaSensor)

    override suspend fun calibrarSensor(
        punto: PuntoCalibracion?,
        adc: Int?,
        pctSeca: Int?,
        pctHumeda: Int?,
        pctSobre: Int?,
    ): ResultadoComando = ordenar(OrdenMaestro.CalibrarSensor(punto, adc, pctSeca, pctHumeda, pctSobre))

    /** Espera su turno y envía [orden] UNA vez (nunca se reintenta). */
    private suspend fun ordenar(orden: OrdenMaestro): ResultadoComando =
        turno.withLock { api.enviar(orden) }.also(::adelantarConsultaSiExito)

    private fun adelantarConsultaSiExito(resultado: ResultadoComando) {
        if (resultado is ResultadoComando.Exito) consultaInmediata.trySend(Unit)
    }

    /** Regla 1 de seguridad: rutinas y modo automático solo en ESPERANDO y sin falla. */
    private fun motivoNoListo(): String? {
        val robot = _estado.value
        return when {
            robot == null -> "Todavía no hay datos del robot: espera a que se conecte."
            robot.hayFalla -> "El robot tiene una falla activa: revísala y reinícialo antes de empezar."
            !robot.listoParaRutinas -> "El robot está ocupado: espera a que termine y quede en espera."
            else -> null
        }
    }

    internal companion object {
        /** Desde este número de fallos seguidos se espera con backoff. */
        const val FALLOS_PARA_BACKOFF = 3
        const val BACKOFF_INICIAL_MS = 2_000L

        /** 3 fallos → 2 s, 4 → 4 s, 5 → 8 s… hasta [RobotConfig.BACKOFF_MAXIMO_MS]. */
        fun esperaBackoff(fallos: Int): Long {
            val exponente = (fallos - FALLOS_PARA_BACKOFF).coerceIn(0, 20)
            return (BACKOFF_INICIAL_MS shl exponente).coerceAtMost(RobotConfig.BACKOFF_MAXIMO_MS)
        }
    }
}
