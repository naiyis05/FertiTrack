package com.fertitrack.app.domain.sesion

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.traduccion.Traductor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * Convierte la secuencia de respuestas de /api/estado en la historia de la sesión:
 * eventos, mediciones para la gráfica, riegos y la sugerencia de fertilizar.
 *
 * Lógica pura (sin Android). La sesión dura lo que dura el proceso de la app.
 *
 * Decisiones:
 * - **Una muestra por medición, no por consulta.** El robot mide por zona y entre
 *   mediciones `humedad_pct` no cambia; graficar cada consulta repetiría el mismo valor.
 *   Se registra al salir de `SENSADO_MIDIENDO` (zona = la del estado anterior) o de
 *   `PRUEBA_SENSOR` (sin zona). Si al salir hay EMERGENCIA o falla, la lectura era
 *   inválida (falla 3) y no se registra.
 * - **Fin de riego deducido.** El firmware no avisa: un riego termina bien cuando
 *   `modo_rutina` deja de ser RIEGO sin falla. Si la app no estaba consultando en ese
 *   momento (segundo plano o sin conexión) la siguiente respuesta ya llega en otro
 *   estado y el riego se da por terminado entonces; si el ciclo terminó por completo
 *   mientras la app no miraba, no se entera.
 * - **Bombeos por tramo.** La bomba se da por encendida en `RIEGO_AVANZANDO` regando y se
 *   apaga al cambiar de estado o de zona. El firmware riega de una vez las zonas secas
 *   contiguas, así que cada bombeo se asigna a ese tramo de zonas.
 */
class SesionTracker(private val reloj: () -> Long = System::currentTimeMillis) {

    private val _resumen = MutableStateFlow(ResumenSesion())
    val resumen: StateFlow<ResumenSesion> = _resumen.asStateFlow()

    // Sin suscriptor (vigilancia apagada) se descartan: solo sirven para notificar al momento.
    private val _avisos = MutableSharedFlow<AvisoSesion>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fallas, fin de riego, zonas secas y cortes de conexión, para las notificaciones. */
    val avisos: SharedFlow<AvisoSesion> = _avisos.asSharedFlow()

    private var anterior: EstadoRobot? = null
    private var riegoActual: RiegoEnCurso? = null
    private var bombeoActual: BombeoEnCurso? = null
    private var sumaHumedad = 0.0
    private var huboConexion = false
    private var conexionPerdida = false

    private data class RiegoEnCurso(val inicioMs: Long, val zonasSecas: List<Int>)
    private data class BombeoEnCurso(val zona: Int, val inicioMs: Long)

    /** Compara [nuevo] con el estado anterior y registra lo que cambió. */
    @Synchronized
    fun procesarEstado(nuevo: EstadoRobot) {
        val ahora = reloj()
        val previo = anterior
        anterior = nuevo

        if (previo == null) {
            agregarEvento(ahora, TipoEvento.RUTINA, "Estado inicial: ${Traductor.describirEstado(nuevo).titulo}")
            if (nuevo.hayFalla) registrarFalla(ahora, nuevo)
            if (nuevo.modoRutina == ModoRutina.RIEGO) riegoActual = RiegoEnCurso(ahora, zonasSecas(nuevo))
            seguirBomba(ahora, nuevo)
            return
        }

        if (nuevo.hayFalla && nuevo.codigoFalla != previo.codigoFalla) registrarFalla(ahora, nuevo)
        // Antes de cerrar la rutina: el tramo regado sale de las zonas secas del riego en curso.
        seguirBomba(ahora, nuevo)

        if (previo.estado == EstadoMaquina.SENSADO_MIDIENDO && nuevo.estado != EstadoMaquina.SENSADO_MIDIENDO) {
            registrarMedicion(ahora, previo.zonaActual.takeIf { it > 0 }, nuevo)
        }
        if (previo.estado == EstadoMaquina.PRUEBA_SENSOR && nuevo.estado != EstadoMaquina.PRUEBA_SENSOR) {
            registrarMedicion(ahora, null, nuevo)
        }

        if (previo.modoRutina != nuevo.modoRutina) {
            terminarRutina(ahora, previo, nuevo)
            empezarRutina(ahora, nuevo)
        }

        val enRecuperacion = nuevo.estado in ESTADOS_RECUPERACION
        if (previo.estado == EstadoMaquina.EMERGENCIA && enRecuperacion) {
            agregarEvento(ahora, TipoEvento.RUTINA, "Recuperación: el robot recoge los brazos y vuelve al origen")
        }
        if (previo.estado in ESTADOS_RECUPERACION && nuevo.estado == EstadoMaquina.ESPERANDO) {
            agregarEvento(ahora, TipoEvento.RUTINA, "Robot listo tras la recuperación")
        }

        if (previo.autoActivo != nuevo.autoActivo) {
            val texto = if (nuevo.autoActivo) {
                "Modo automático activado (descanso de ${nuevo.descansoSeg} s)"
            } else {
                "Modo automático desactivado"
            }
            agregarEvento(ahora, TipoEvento.AVISO, texto)
        }
    }

    /** Registra pérdidas y recuperaciones de conexión (sin repetir eventos). */
    @Synchronized
    fun procesarConexion(conexion: EstadoConexion) {
        val ahora = reloj()
        when (conexion) {
            EstadoConexion.Conectado -> when {
                !huboConexion -> {
                    huboConexion = true
                    conexionPerdida = false
                    agregarEvento(ahora, TipoEvento.CONEXION, "Conectado al robot")
                }
                conexionPerdida -> {
                    conexionPerdida = false
                    agregarEvento(ahora, TipoEvento.CONEXION, "Conexión recuperada")
                    _avisos.tryEmit(AvisoSesion.ConexionRecuperada)
                }
            }
            is EstadoConexion.SinRespuesta, is EstadoConexion.Reintentando -> if (huboConexion && !conexionPerdida) {
                conexionPerdida = true
                agregarEvento(ahora, TipoEvento.CONEXION, "Se perdió la conexión con el robot")
                _avisos.tryEmit(AvisoSesion.ConexionPerdida)
            }
            EstadoConexion.Pausado, EstadoConexion.Conectando -> Unit
        }
    }

    /** Registra una orden enviada por el usuario y su resultado. */
    @Synchronized
    fun registrarComando(descripcion: String, resultado: ResultadoComando) {
        val texto = when (resultado) {
            is ResultadoComando.Exito -> "$descripcion: enviada"
            is ResultadoComando.Ocupado -> "$descripcion: robot ocupado"
            is ResultadoComando.Rechazado -> "$descripcion: rechazada"
            is ResultadoComando.ErrorRobot -> "$descripcion: error ${resultado.codigoHttp}"
            is ResultadoComando.SinRespuesta -> "$descripcion: sin respuesta"
        }
        agregarEvento(reloj(), TipoEvento.COMANDO, texto)
    }

    /** Agrega un evento libre (p. ej. avisos del ViewModel). */
    @Synchronized
    fun registrarEvento(tipo: TipoEvento, texto: String) {
        agregarEvento(reloj(), tipo, texto)
    }

    @Synchronized
    fun descartarSugerenciaFertilizar() {
        _resumen.update { it.copy(sugerirFertilizar = false) }
    }

    /**
     * Antepone el historial [guardado] a lo registrado desde que arrancó la app (por si llegó
     * algún estado antes de terminar de leerlo) y respeta los topes.
     */
    @Synchronized
    fun restaurar(guardado: ResumenSesion) {
        _resumen.update { actual ->
            val total = guardado.totalMediciones + actual.totalMediciones
            sumaHumedad += (guardado.humedadPromedio ?: 0f).toDouble() * guardado.totalMediciones
            actual.copy(
                eventos = (actual.eventos + guardado.eventos).take(RobotConfig.MAX_EVENTOS),
                muestras = (guardado.muestras + actual.muestras).takeLast(RobotConfig.MAX_PUNTOS_GRAFICA),
                riegos = (guardado.riegos + actual.riegos).takeLast(RobotConfig.MAX_PUNTOS_GRAFICA),
                bombeos = (guardado.bombeos + actual.bombeos).takeLast(RobotConfig.MAX_EVENTOS),
                riegosCompletados = guardado.riegosCompletados + actual.riegosCompletados,
                totalMediciones = total,
                humedadPromedio = if (total > 0) (sumaHumedad / total).toFloat() else null,
            )
        }
    }

    @Synchronized
    fun reiniciarSesion() {
        anterior = null
        riegoActual = null
        bombeoActual = null
        sumaHumedad = 0.0
        huboConexion = false
        conexionPerdida = false
        _resumen.value = ResumenSesion()
    }

    // ── Internos ────────────────────────────────────────────────────────────

    private fun registrarFalla(ahora: Long, robot: EstadoRobot) {
        val falla = Traductor.describirFalla(robot.codigoFalla)
        agregarEvento(ahora, TipoEvento.FALLA, "Falla ${falla.codigo}: ${falla.titulo}")
        _resumen.update { it.copy(sugerirFertilizar = false) }
        _avisos.tryEmit(AvisoSesion.Falla(robot.codigoFalla))
    }

    private fun registrarMedicion(ahora: Long, zona: Int?, nuevo: EstadoRobot) {
        if (nuevo.estado == EstadoMaquina.EMERGENCIA || nuevo.hayFalla) return
        val pct = nuevo.humedadPct ?: return
        val adc = nuevo.humedadAdc ?: return
        val nivel = if (zona != null) {
            nuevo.zonas.getOrNull(zona - 1)?.takeIf { it != NivelSuelo.SIN_MEDIR } ?: clasificar(adc, nuevo)
        } else {
            clasificar(adc, nuevo)
        }
        val muestra = MuestraHumedad(ahora, zona, pct, adc, nivel)
        sumaHumedad += pct
        _resumen.update { r ->
            val total = r.totalMediciones + 1
            r.copy(
                muestras = (r.muestras + muestra).takeLast(RobotConfig.MAX_PUNTOS_GRAFICA),
                totalMediciones = total,
                humedadPromedio = (sumaHumedad / total).toFloat(),
            )
        }
        val donde = if (zona != null) "Zona $zona" else "Prueba de sensor"
        agregarEvento(ahora, TipoEvento.MEDICION, "$donde: ${pct.roundToInt()} % (${Traductor.nombreNivel(nivel)})")
    }

    /** Misma regla que clasificarHumedad() del firmware (ADC alto = suelo seco). */
    private fun clasificar(adc: Int, robot: EstadoRobot): NivelSuelo {
        val c = robot.calibracion
        return when {
            adc < 0 -> NivelSuelo.SIN_MEDIR
            adc <= c.umbralSobre -> NivelSuelo.SOBRE_HUMEDA
            adc <= c.umbralHumedo -> NivelSuelo.HUMEDA
            adc < c.umbralSeco -> NivelSuelo.INTERMEDIA
            else -> NivelSuelo.SECA
        }
    }

    private fun terminarRutina(ahora: Long, previo: EstadoRobot, nuevo: EstadoRobot) {
        val interrumpida = nuevo.hayFalla || nuevo.estado == EstadoMaquina.EMERGENCIA
        when (previo.modoRutina) {
            ModoRutina.SENSADO -> if (interrumpida) {
                agregarEvento(ahora, TipoEvento.RUTINA, "Sensado interrumpido")
            } else {
                agregarEvento(ahora, TipoEvento.RUTINA, "Sensado completado: ${resumenNiveles(nuevo)}")
                // Solo sensado (sin riego detrás): las zonas secas quedan sin regar.
                val secas = zonasSecas(nuevo)
                if (nuevo.modoRutina == ModoRutina.NINGUNA && secas.isNotEmpty()) _avisos.tryEmit(AvisoSesion.ZonasSecas(secas))
            }
            ModoRutina.RIEGO -> {
                val riego = riegoActual ?: RiegoEnCurso(ahora, zonasSecas(previo))
                riegoActual = null
                val registro = RiegoRegistrado(riego.inicioMs, ahora, riego.zonasSecas, completado = !interrumpida)
                if (interrumpida) {
                    _resumen.update {
                        it.copy(riegos = (it.riegos + registro).takeLast(RobotConfig.MAX_PUNTOS_GRAFICA), sugerirFertilizar = false)
                    }
                    agregarEvento(ahora, TipoEvento.RIEGO, "Riego interrumpido")
                    _avisos.tryEmit(AvisoSesion.RiegoTerminado(riego.zonasSecas, completado = false, sugerirFertilizar = false))
                } else {
                    val sugerir = riego.zonasSecas.isNotEmpty() && nuevo.modoRutina == ModoRutina.NINGUNA
                    _resumen.update {
                        it.copy(
                            riegos = (it.riegos + registro).takeLast(RobotConfig.MAX_PUNTOS_GRAFICA),
                            riegosCompletados = it.riegosCompletados + 1,
                            sugerirFertilizar = sugerir,
                        )
                    }
                    agregarEvento(ahora, TipoEvento.RIEGO, "Riego completado${textoZonas(riego.zonasSecas)}")
                    _avisos.tryEmit(AvisoSesion.RiegoTerminado(riego.zonasSecas, completado = true, sugerirFertilizar = sugerir))
                }
            }
            ModoRutina.FERTILIZACION -> agregarEvento(
                ahora,
                TipoEvento.FERTILIZACION,
                if (interrumpida) "Fertilización interrumpida" else "Fertilización completada",
            )
            ModoRutina.NINGUNA, ModoRutina.DESCONOCIDO -> Unit
        }
    }

    private fun empezarRutina(ahora: Long, nuevo: EstadoRobot) {
        when (nuevo.modoRutina) {
            ModoRutina.SENSADO -> {
                _resumen.update { it.copy(sugerirFertilizar = false) }
                agregarEvento(ahora, TipoEvento.RUTINA, "Sensado iniciado: ${nuevo.totalZonas} zonas")
            }
            ModoRutina.RIEGO -> {
                val secas = zonasSecas(nuevo)
                riegoActual = RiegoEnCurso(ahora, secas)
                _resumen.update { it.copy(sugerirFertilizar = false) }
                agregarEvento(ahora, TipoEvento.RIEGO, "Riego iniciado${textoZonas(secas)}")
            }
            ModoRutina.FERTILIZACION -> {
                _resumen.update { it.copy(sugerirFertilizar = false) }
                agregarEvento(ahora, TipoEvento.FERTILIZACION, "Fertilización iniciada")
            }
            ModoRutina.NINGUNA, ModoRutina.DESCONOCIDO -> Unit
        }
    }

    /** Abre o cierra el bombeo según la bomba de riego de [nuevo]. */
    private fun seguirBomba(ahora: Long, nuevo: EstadoRobot) {
        val encendida = nuevo.bombaRiegoEncendida
        val actual = bombeoActual
        if (actual != null && (!encendida || nuevo.zonaActual != actual.zona)) {
            val interrumpido = nuevo.hayFalla || nuevo.estado == EstadoMaquina.EMERGENCIA
            val registro = BombeoRiego(tramoRegado(actual.zona), actual.inicioMs, ahora, interrumpido)
            _resumen.update { it.copy(bombeos = (it.bombeos + registro).takeLast(RobotConfig.MAX_EVENTOS)) }
            bombeoActual = null
        }
        if (encendida && bombeoActual == null) bombeoActual = BombeoEnCurso(nuevo.zonaActual, ahora)
    }

    /** Zonas secas contiguas desde [zona]: el tramo que el firmware riega sin apagar la bomba. */
    private fun tramoRegado(zona: Int): List<Int> {
        if (zona <= 0) return emptyList()
        val secas = riegoActual?.zonasSecas?.toSet().orEmpty()
        if (zona !in secas) return listOf(zona)
        return generateSequence(zona) { it + 1 }.takeWhile { it in secas }.toList()
    }

    private fun zonasSecas(robot: EstadoRobot): List<Int> =
        robot.zonas.mapIndexedNotNull { i, nivel -> if (nivel == NivelSuelo.SECA) i + 1 else null }

    private fun textoZonas(zonas: List<Int>): String = when (zonas.size) {
        0 -> ""
        1 -> " (zona ${zonas.first()})"
        else -> " (zonas ${zonas.joinToString(", ")})"
    }

    private fun resumenNiveles(robot: EstadoRobot): String {
        val conteo = robot.zonas.groupingBy { it }.eachCount()
        val partes = listOf(
            NivelSuelo.SECA to "seca" to "secas",
            NivelSuelo.INTERMEDIA to "intermedia" to "intermedias",
            NivelSuelo.HUMEDA to "húmeda" to "húmedas",
            NivelSuelo.SOBRE_HUMEDA to "sobre-húmeda" to "sobre-húmedas",
            NivelSuelo.SIN_MEDIR to "sin medir" to "sin medir",
        ).mapNotNull { (par, plural) ->
            val (nivel, singular) = par
            val n = conteo[nivel] ?: 0
            if (n == 0) null else "$n ${if (n == 1) singular else plural}"
        }
        return if (partes.isEmpty()) "sin zonas" else partes.joinToString(", ")
    }

    private fun agregarEvento(ahora: Long, tipo: TipoEvento, texto: String) {
        _resumen.update { r ->
            if (r.eventos.firstOrNull()?.texto == texto) {
                r
            } else {
                r.copy(eventos = (listOf(EventoSesion(ahora, tipo, texto)) + r.eventos).take(RobotConfig.MAX_EVENTOS))
            }
        }
    }

    private companion object {
        val ESTADOS_RECUPERACION = setOf(
            EstadoMaquina.RECUP_ESPERA,
            EstadoMaquina.RECUP_BRAZO_RIEGO,
            EstadoMaquina.RECUP_BRAZO_FERTI,
            EstadoMaquina.RECUP_RETORNANDO,
        )
    }
}
