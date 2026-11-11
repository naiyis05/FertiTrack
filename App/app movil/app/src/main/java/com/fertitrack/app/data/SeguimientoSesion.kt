package com.fertitrack.app.data

import com.fertitrack.app.asistente.FotoRobot
import com.fertitrack.app.data.historial.AlmacenFoto
import com.fertitrack.app.data.historial.AlmacenHistorial
import com.fertitrack.app.data.historial.FuenteDatos
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.SesionTracker
import com.fertitrack.app.domain.sesion.TipoEvento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Alimenta el [tracker] con cada respuesta del robot y guarda el historial mientras viva el
 * proceso, no la pantalla: con la vigilancia en segundo plano sigue registrando sin actividad.
 *
 * Cada fuente (robot real o simulado) tiene su historial. Al cambiar de fuente se guarda el
 * de la anterior y se carga el de la nueva. Cada cambio del resumen se escribe enseguida
 * (como mucho uno por consulta), así que un cierre brusco pierde como mucho la última escritura.
 *
 * También guarda la última lectura del robot ([ultimaFoto]) para que el asistente conteste
 * sin conexión: se escribe cuando cambia algo que la foto guarda o cada [INTERVALO_FOTO_MS].
 */
class SeguimientoSesion(
    private val repositorio: RobotRepository,
    val tracker: SesionTracker,
    private val almacen: AlmacenHistorial,
    private val almacenFoto: AlmacenFoto,
    private val ambito: CoroutineScope,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    private var iniciado = false

    private val _ultimaFoto = MutableStateFlow<FotoRobot?>(null)

    /** Última lectura de la fuente actual: en vivo si hay conexión, la guardada si no. */
    val ultimaFoto: StateFlow<FotoRobot?> = _ultimaFoto.asStateFlow()

    private val candadoFoto = Mutex()

    /** Fuente cuya foto ya se cargó; null mientras arranca (las lecturas se ignoran). */
    private var fuenteFoto: FuenteDatos? = null
    private var fotoEscrita: FotoRobot? = null

    fun iniciar() {
        synchronized(this) {
            if (iniciado) return
            iniciado = true
        }
        ambito.launch {
            var fuenteAnterior: FuenteDatos? = null
            repositorio.usandoSimulador.collectLatest { simulado ->
                val fuente = if (simulado) FuenteDatos.SIMULADOR else FuenteDatos.ROBOT
                fuenteAnterior?.let { anterior ->
                    // El tracker todavía tiene los datos de la fuente anterior.
                    guardar(anterior, tracker.resumen.value)
                    tracker.reiniciarSesion()
                    // Sin fuente mientras cambia: una lectura nueva no se guarda en la anterior.
                    val fotoAnterior = candadoFoto.withLock {
                        fuenteFoto = null
                        _ultimaFoto.value
                    }
                    fotoAnterior?.let { guardarFoto(anterior, it) }
                }
                almacen.cargar(fuente)?.let(tracker::restaurar)
                val fotoCargada = almacenFoto.cargar(fuente)
                candadoFoto.withLock {
                    fuenteFoto = fuente
                    fotoEscrita = fotoCargada
                    _ultimaFoto.value = fotoCargada
                }
                if (fuenteAnterior != null) {
                    tracker.registrarEvento(TipoEvento.AVISO, if (simulado) "Usando el robot simulado" else "Usando el robot real")
                }
                fuenteAnterior = fuente
                tracker.resumen
                    .map(::paraGuardar)
                    .distinctUntilChanged()
                    .drop(1) // Lo recién cargado ya está en el archivo.
                    .conflate()
                    .collect { guardar(fuente, it) }
            }
        }
        ambito.launch { repositorio.estado.filterNotNull().collect { tracker.procesarEstado(it) } }
        ambito.launch { repositorio.conexion.collect { tracker.procesarConexion(it) } }
        ambito.launch { repositorio.estado.filterNotNull().collect(::registrarFoto) }
    }

    private suspend fun registrarFoto(robot: EstadoRobot) {
        val foto = FotoRobot.de(robot, reloj())
        val fuente = candadoFoto.withLock {
            val fuente = fuenteFoto ?: return
            _ultimaFoto.value = foto
            val previa = fotoEscrita
            val toca = previa == null || !foto.mismoContenido(previa) || foto.momentoMs - previa.momentoMs >= INTERVALO_FOTO_MS
            if (!toca) return
            fotoEscrita = foto
            fuente
        }
        guardarFoto(fuente, foto)
    }

    private suspend fun guardarFoto(fuente: FuenteDatos, foto: FotoRobot) {
        withContext(NonCancellable) { almacenFoto.guardar(fuente, foto) }
    }

    /** Borra el historial de la fuente actual: en memoria y, enseguida, en el archivo. */
    fun borrarHistorial() = tracker.reiniciarSesion()

    /** Una escritura empezada se termina aunque se cambie de fuente a mitad. */
    private suspend fun guardar(fuente: FuenteDatos, resumen: ResumenSesion) {
        withContext(NonCancellable) { almacen.guardar(fuente, paraGuardar(resumen)) }
    }

    // La sugerencia de fertilizar no se guarda: al volver a abrir la app ya no corresponde.
    private fun paraGuardar(resumen: ResumenSesion) = resumen.copy(sugerirFertilizar = false)

    companion object {
        /** Aunque nada cambie, la foto se reescribe cada 5 min para que su hora no envejezca. */
        const val INTERVALO_FOTO_MS = 5 * 60_000L
    }
}
