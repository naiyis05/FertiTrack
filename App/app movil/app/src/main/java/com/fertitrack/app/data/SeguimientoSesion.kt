package com.fertitrack.app.data

import com.fertitrack.app.data.historial.AlmacenHistorial
import com.fertitrack.app.data.historial.FuenteDatos
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.SesionTracker
import com.fertitrack.app.domain.sesion.TipoEvento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alimenta el [tracker] con cada respuesta del robot y guarda el historial mientras viva el
 * proceso, no la pantalla: con la vigilancia en segundo plano sigue registrando sin actividad.
 *
 * Cada fuente (robot real o simulado) tiene su historial. Al cambiar de fuente se guarda el
 * de la anterior y se carga el de la nueva. Cada cambio del resumen se escribe enseguida
 * (como mucho uno por consulta), así que un cierre brusco pierde como mucho la última escritura.
 */
class SeguimientoSesion(
    private val repositorio: RobotRepository,
    val tracker: SesionTracker,
    private val almacen: AlmacenHistorial,
    private val ambito: CoroutineScope,
) {
    private var iniciado = false

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
                }
                almacen.cargar(fuente)?.let(tracker::restaurar)
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
    }

    /** Borra el historial de la fuente actual: en memoria y, enseguida, en el archivo. */
    fun borrarHistorial() = tracker.reiniciarSesion()

    /** Una escritura empezada se termina aunque se cambie de fuente a mitad. */
    private suspend fun guardar(fuente: FuenteDatos, resumen: ResumenSesion) {
        withContext(NonCancellable) { almacen.guardar(fuente, paraGuardar(resumen)) }
    }

    // La sugerencia de fertilizar no se guarda: al volver a abrir la app ya no corresponde.
    private fun paraGuardar(resumen: ResumenSesion) = resumen.copy(sugerirFertilizar = false)
}
