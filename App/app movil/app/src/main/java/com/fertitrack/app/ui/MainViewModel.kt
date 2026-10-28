package com.fertitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fertitrack.app.FertiTrackApp
import com.fertitrack.app.data.ControlSondeo
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.data.SeguimientoSesion
import com.fertitrack.app.data.exportar.Csv
import com.fertitrack.app.data.exportar.Exportador
import com.fertitrack.app.domain.analisis.Analizador
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.SesionTracker
import com.fertitrack.app.domain.sesion.TipoEvento
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.simulador.Simulador
import com.fertitrack.app.vigilancia.Vigilancia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Une repositorio, historial y análisis en un único [UiState] y aplica las reglas
 * de seguridad antes de enviar cualquier orden. El historial lo alimenta [sesion], que vive
 * con el proceso: este ViewModel solo lo lee y registra las órdenes.
 */
class MainViewModel(
    private val repositorio: RobotRepository,
    private val simulador: Simulador,
    private val sesion: SeguimientoSesion,
    private val exportador: Exportador,
    private val controlSondeo: ControlSondeo,
    private val vigilancia: Vigilancia,
    private val reloj: () -> Long = System::currentTimeMillis,
) : ViewModel(), AccionesUi {

    private val tracker: SesionTracker get() = sesion.tracker

    /** Estado propio de la interfaz que no viene del robot. */
    private data class Interno(
        val confirmacion: Confirmacion? = null,
        val comandoEnCurso: String? = null,
        val paradaEnCurso: Boolean = false,
        val mensajes: List<MensajeUi> = emptyList(),
    )

    private data class Fuentes(
        val conexion: EstadoConexion,
        val red: EstadoRed,
        val robot: EstadoRobot?,
        val ultimaRespuestaMs: Long?,
        val usandoSimulador: Boolean,
    )

    private val interno = MutableStateFlow(Interno())
    private var siguienteIdMensaje = 1L

    /** Reloj de 1 s para la antigüedad de los datos y las cuentas atrás. */
    private val tick: Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(reloj())
            delay(1_000)
        }
    }

    private val fuentes: Flow<Fuentes> = combine(
        repositorio.conexion,
        repositorio.red,
        repositorio.estado,
        repositorio.ultimaRespuestaMs,
        repositorio.usandoSimulador,
    ) { conexion, red, robot, ultima, simulado -> Fuentes(conexion, red, robot, ultima, simulado) }

    val ui: StateFlow<UiState> = combine(fuentes, tracker.resumen, interno, tick, vigilancia.activa) { f, resumen, i, ahora, vigila ->
        construir(f, resumen, i, ahora).copy(vigilanciaActiva = vigila)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiState(simuladorDisponible = repositorio.simuladorDisponible),
    )

    // ── Construcción del estado ─────────────────────────────────────────────

    private fun construir(f: Fuentes, resumen: ResumenSesion, i: Interno, ahora: Long): UiState {
        val robot = f.robot
        val conectado = f.conexion is EstadoConexion.Conectado
        val ordenEnCurso = i.comandoEnCurso != null
        val rutinas = rutinasPermitidas(robot, f.conexion) && !ordenEnCurso
        val motivo = when {
            rutinas -> null
            ordenEnCurso -> "Enviando orden…"
            f.red is EstadoRed.PermisosFaltantes && !conectado ->
                "Falta un permiso de red: concédelo en la pantalla Conexión"
            else -> Traductor.motivoRutinasNoDisponibles(robot, f.conexion) ?: "No disponible en este momento"
        }
        return UiState(
            conexion = f.conexion,
            red = f.red,
            robot = robot,
            datosObsoletos = !conectado || robot == null,
            antiguedadDatosSeg = f.ultimaRespuestaMs?.let { ((ahora - it) / 1_000).coerceAtLeast(0) },
            descripcionEstado = robot?.let(Traductor::describirEstado),
            falla = robot?.takeIf { it.hayFalla }?.let { Traductor.describirFalla(it.codigoFalla) },
            resumen = resumen,
            insights = Analizador.generar(robot, resumen, f.conexion, ahora),
            rutinasHabilitadas = rutinas,
            motivoRutinasDeshabilitadas = motivo,
            reiniciarHabilitado = conectado && robot?.estado == EstadoMaquina.EMERGENCIA && !ordenEnCurso,
            puedeActivarAuto = rutinas && robot?.autoActivo == false,
            puedeDesactivarAuto = conectado && robot?.autoActivo == true && !ordenEnCurso,
            puedeGuardarDescanso = conectado && robot != null && !ordenEnCurso,
            comandoEnCurso = i.comandoEnCurso,
            paradaEnCurso = i.paradaEnCurso,
            confirmacion = i.confirmacion,
            mensaje = i.mensajes.firstOrNull(),
            simuladorDisponible = repositorio.simuladorDisponible,
            usandoSimulador = f.usandoSimulador,
        )
    }

    private fun rutinasPermitidas(robot: EstadoRobot?, conexion: EstadoConexion): Boolean =
        conexion is EstadoConexion.Conectado && robot != null && robot.listoParaRutinas && robot.distanciaConfigurada

    /** null si [accion] se puede enviar ahora; si no, el motivo. Se calcula con los valores más recientes. */
    private fun motivoNoPermitida(accion: AccionConfirmable): String? {
        // Borrar el historial no habla con el robot: siempre se puede.
        if (accion == AccionConfirmable.BorrarHistorial) return null
        val robot = repositorio.estado.value
        val conexion = repositorio.conexion.value
        if (interno.value.comandoEnCurso != null) return "Espera: hay otra orden en curso"
        return when (accion) {
            is AccionConfirmable.Iniciar, AccionConfirmable.FertilizarTrasRiego ->
                if (rutinasPermitidas(robot, conexion)) null else motivoRutinas(robot, conexion)
            is AccionConfirmable.ActivarAuto -> when {
                !rutinasPermitidas(robot, conexion) -> motivoRutinas(robot, conexion)
                robot?.autoActivo == true -> "El modo automático ya está activo"
                accion.descansoSeg !in DESCANSO_VALIDO -> "El descanso debe estar entre 1 y 86400 s"
                else -> null
            }
            AccionConfirmable.Reiniciar -> when {
                conexion !is EstadoConexion.Conectado || robot == null -> "Sin conexión con el robot"
                robot.estado != EstadoMaquina.EMERGENCIA -> "Reiniciar solo se usa después de una emergencia"
                else -> null
            }
            AccionConfirmable.BorrarHistorial -> null
        }
    }

    private fun motivoRutinas(robot: EstadoRobot?, conexion: EstadoConexion): String =
        Traductor.motivoRutinasNoDisponibles(robot, conexion) ?: "No disponible en este momento"

    private fun construirConfirmacion(accion: AccionConfirmable): Confirmacion {
        val robot = repositorio.estado.value
        val zonas = robot?.totalZonas?.takeIf { it > 0 }
        val textoZonas = zonas?.let { "las $it zonas" } ?: "todas las zonas"
        return when (accion) {
            is AccionConfirmable.Iniciar -> when (accion.modo) {
                ModoInicio.CICLO -> Confirmacion(
                    accion, "¿Iniciar el ciclo completo?",
                    "El robot recorrerá $textoZonas midiendo la humedad y después regará solo las zonas " +
                        "secas. Comprueba que el riel esté despejado.",
                    "Iniciar ciclo", peligrosa = true,
                )
                ModoInicio.SENSADO -> Confirmacion(
                    accion, "¿Iniciar solo el sensado?",
                    "El robot recorrerá $textoZonas midiendo la humedad, sin regar. " +
                        "Comprueba que el riel esté despejado.",
                    "Iniciar sensado", peligrosa = true,
                )
                ModoInicio.FERTILIZAR -> confirmacionFertilizar(accion, robot)
            }
            AccionConfirmable.FertilizarTrasRiego -> confirmacionFertilizar(accion, robot)
            AccionConfirmable.Reiniciar -> Confirmacion(
                accion, "¿Reiniciar el sistema?",
                "El robot recogerá los brazos de riego y de fertilización y volverá al origen. El modo " +
                    "automático quedará desactivado. Asegúrate de que no haya nada ni nadie en el riel.",
                "Reiniciar", peligrosa = true,
            )
            is AccionConfirmable.ActivarAuto -> Confirmacion(
                accion, "¿Activar el riego automático?",
                "El robot iniciará un ciclo completo (sensar y regar las zonas secas) tras cada " +
                    "${formatoDescanso(accion.descansoSeg)} de descanso, sin volver a pedir confirmación. " +
                    "Puedes desactivarlo en cualquier momento.",
                "Activar", peligrosa = true,
            )
            AccionConfirmable.BorrarHistorial -> {
                val r = tracker.resumen.value
                Confirmacion(
                    accion, "¿Borrar el historial?",
                    "Se borrarán del teléfono ${r.muestras.size} mediciones, ${r.bombeos.size} riegos y los " +
                        "eventos guardados. No se puede deshacer; si los necesitas, expórtalos antes. " +
                        "El robot no se ve afectado.",
                    "Borrar", peligrosa = false,
                )
            }
        }
    }

    private fun confirmacionFertilizar(accion: AccionConfirmable, robot: EstadoRobot?): Confirmacion {
        val recorrido = robot?.distanciaCm?.takeIf { it > 0f }?.let { " (${it.toInt()} cm)" } ?: ""
        return Confirmacion(
            accion, "¿Iniciar la fertilización?",
            "Se bajará el brazo de fertilización y el robot recorrerá todo el riel$recorrido con la bomba " +
                "de fertilizante encendida. Comprueba que el riel esté despejado.",
            "Fertilizar", peligrosa = true,
        )
    }

    private fun descripcionDe(accion: AccionConfirmable): String = when (accion) {
        is AccionConfirmable.Iniciar -> when (accion.modo) {
            ModoInicio.CICLO -> "Iniciar ciclo completo"
            ModoInicio.SENSADO -> "Iniciar sensado"
            ModoInicio.FERTILIZAR -> "Iniciar fertilización"
        }
        AccionConfirmable.FertilizarTrasRiego -> "Iniciar fertilización"
        AccionConfirmable.Reiniciar -> "Reiniciar sistema"
        is AccionConfirmable.ActivarAuto -> "Activar modo automático"
        AccionConfirmable.BorrarHistorial -> "Borrar historial"
    }

    // ── Acciones ────────────────────────────────────────────────────────────

    override fun pedirConfirmacion(accion: AccionConfirmable) {
        val motivo = motivoNoPermitida(accion)
        if (motivo != null) {
            mostrar(motivo, TipoMensaje.AVISO)
            return
        }
        interno.update { it.copy(confirmacion = construirConfirmacion(accion)) }
    }

    override fun confirmar() {
        val confirmacion = interno.value.confirmacion ?: return
        val accion = confirmacion.accion
        val motivo = motivoNoPermitida(accion)
        if (motivo != null) {
            interno.update { it.copy(confirmacion = null) }
            mostrar("No se envió la orden: $motivo", TipoMensaje.AVISO)
            return
        }
        if (accion == AccionConfirmable.BorrarHistorial) {
            interno.update { it.copy(confirmacion = null) }
            sesion.borrarHistorial()
            mostrar("Historial borrado", TipoMensaje.EXITO)
            return
        }
        val descripcion = descripcionDe(accion)
        interno.update { it.copy(confirmacion = null, comandoEnCurso = descripcion) }
        viewModelScope.launch {
            val resultado = ejecutar {
                when (accion) {
                    is AccionConfirmable.Iniciar -> repositorio.iniciar(accion.modo)
                    AccionConfirmable.FertilizarTrasRiego -> repositorio.iniciar(ModoInicio.FERTILIZAR)
                    AccionConfirmable.Reiniciar -> repositorio.reiniciar()
                    is AccionConfirmable.ActivarAuto -> repositorio.configurarAuto(true, accion.descansoSeg)
                    AccionConfirmable.BorrarHistorial -> error("Se resuelve antes, sin el robot")
                }
            }
            val esFertilizacion = accion == AccionConfirmable.FertilizarTrasRiego ||
                (accion is AccionConfirmable.Iniciar && accion.modo == ModoInicio.FERTILIZAR)
            if (resultado is ResultadoComando.Exito && esFertilizacion) tracker.descartarSugerenciaFertilizar()
            terminarOrden(descripcion, resultado)
        }
    }

    override fun cancelarConfirmacion() {
        interno.update { it.copy(confirmacion = null) }
    }

    override fun detenerEmergencia() {
        if (interno.value.paradaEnCurso) return
        interno.update { it.copy(paradaEnCurso = true, confirmacion = null) }
        viewModelScope.launch {
            val resultado = ejecutar { repositorio.detener() }
            tracker.registrarComando("DETENER", resultado)
            when (resultado) {
                is ResultadoComando.Exito -> mostrar(
                    "Parada enviada: el robot se detuvo. Para continuar, usa Reiniciar.",
                    TipoMensaje.AVISO,
                    urgente = true,
                )
                is ResultadoComando.SinRespuesta -> mostrar(
                    "¡No se pudo enviar la parada! Usa el botón físico de emergencia del robot.",
                    TipoMensaje.ERROR,
                    urgente = true,
                )
                else -> mostrar(
                    "La parada recibió una respuesta inesperada. Si el robot sigue moviéndose, " +
                        "usa el botón físico de emergencia.",
                    TipoMensaje.ERROR,
                    urgente = true,
                )
            }
            interno.update { it.copy(paradaEnCurso = false) }
        }
    }

    override fun desactivarAuto() {
        if (!ui.value.puedeDesactivarAuto) {
            mostrar("Ahora no se puede cambiar el modo automático", TipoMensaje.AVISO)
            return
        }
        lanzarOrden("Desactivar modo automático") { repositorio.configurarAuto(false) }
    }

    override fun guardarDescanso(descansoSeg: Long) {
        if (descansoSeg !in DESCANSO_VALIDO) {
            mostrar("El descanso debe estar entre 1 y 86400 s", TipoMensaje.AVISO)
            return
        }
        val robot = repositorio.estado.value
        if (!ui.value.puedeGuardarDescanso || robot == null) {
            mostrar("Ahora no se puede guardar el descanso", TipoMensaje.AVISO)
            return
        }
        lanzarOrden("Guardar descanso de ${formatoDescanso(descansoSeg)}") {
            repositorio.configurarAuto(robot.autoActivo, descansoSeg)
        }
    }

    override fun responderSugerenciaFertilizar(aceptar: Boolean) {
        if (aceptar) {
            pedirConfirmacion(AccionConfirmable.FertilizarTrasRiego)
        } else {
            tracker.descartarSugerenciaFertilizar()
            tracker.registrarEvento(TipoEvento.FERTILIZACION, "Fertilización sugerida descartada")
        }
    }

    override fun conectarWifiRobot() = repositorio.conectarWifiRobot()

    override fun permisosActualizados() = repositorio.reevaluarRed()

    /** El cambio de historial (y su evento) lo hace [SeguimientoSesion] al ver el cambio de fuente. */
    override fun usarSimulador(activo: Boolean) {
        if (activo == repositorio.usandoSimulador.value) return
        repositorio.usarSimulador(activo)
    }

    override fun simularFalla(codigo: Int) {
        if (!repositorio.usandoSimulador.value) {
            mostrar("Activa primero el robot simulado", TipoMensaje.AVISO)
            return
        }
        simulador.inyectarFalla(codigo)
    }

    override fun simularPerdidaConexion(segundos: Int) {
        if (!repositorio.usandoSimulador.value) {
            mostrar("Activa primero el robot simulado", TipoMensaje.AVISO)
            return
        }
        simulador.simularPerdidaConexion(segundos)
    }

    override fun exportar(tipo: TipoExportacion, destino: String) {
        val resumen = tracker.resumen.value
        val (contenido, texto) = when (tipo) {
            TipoExportacion.MEDICIONES -> Csv.mediciones(resumen.muestras) to
                "${resumen.muestras.size} ${if (resumen.muestras.size == 1) "medición" else "mediciones"}"
            TipoExportacion.RIEGOS -> Csv.riegos(resumen.bombeos) to
                "${resumen.bombeos.size} ${if (resumen.bombeos.size == 1) "riego" else "riegos"}"
        }
        viewModelScope.launch {
            if (exportador.escribir(destino, contenido)) {
                mostrar("Archivo guardado: $texto", TipoMensaje.EXITO)
            } else {
                mostrar("No se pudo guardar el archivo. Prueba con otra carpeta.", TipoMensaje.ERROR)
            }
        }
    }

    override fun mensajeMostrado(id: Long) {
        interno.update { i -> i.copy(mensajes = i.mensajes.filterNot { it.id == id }) }
    }

    override fun alPrimerPlano() {
        controlSondeo.appVisible(true)
        // Si la vigilancia estaba encendida pero su servicio no corre (teléfono reiniciado, el
        // sistema lo cerró), se vuelve a arrancar: solo se puede con la app a la vista.
        vigilancia.reanudarSiCorresponde()
    }

    /** Sin vigilancia, el sondeo se pausa; con ella, sigue en el servicio. */
    override fun alSegundoPlano() = controlSondeo.appVisible(false)

    override fun activarVigilancia(activa: Boolean) {
        if (activa == vigilancia.activa.value) return
        vigilancia.activar(activa)
        mostrar(
            if (activa) {
                "Vigilancia activada: verás una notificación fija mientras vigila."
            } else {
                "Vigilancia desactivada: con la app cerrada ya no se consulta al robot."
            },
            TipoMensaje.INFO,
        )
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    private fun lanzarOrden(descripcion: String, orden: suspend () -> ResultadoComando) {
        if (interno.value.comandoEnCurso != null) {
            mostrar("Espera: hay otra orden en curso", TipoMensaje.AVISO)
            return
        }
        interno.update { it.copy(comandoEnCurso = descripcion) }
        viewModelScope.launch { terminarOrden(descripcion, ejecutar(orden)) }
    }

    private fun terminarOrden(descripcion: String, resultado: ResultadoComando) {
        tracker.registrarComando(descripcion, resultado)
        mostrar(Traductor.mensajeResultado(descripcion, resultado), tipoMensaje(resultado))
        interno.update { it.copy(comandoEnCurso = null) }
    }

    /** El repositorio no debería lanzar excepciones; si lo hace, se trata como "sin respuesta". */
    private suspend fun ejecutar(orden: suspend () -> ResultadoComando): ResultadoComando = try {
        orden()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ResultadoComando.SinRespuesta(e.message ?: e.javaClass.simpleName)
    }

    private fun tipoMensaje(resultado: ResultadoComando): TipoMensaje = when (resultado) {
        is ResultadoComando.Exito -> TipoMensaje.EXITO
        is ResultadoComando.Ocupado, is ResultadoComando.Rechazado -> TipoMensaje.AVISO
        is ResultadoComando.ErrorRobot, is ResultadoComando.SinRespuesta -> TipoMensaje.ERROR
    }

    private fun mostrar(texto: String, tipo: TipoMensaje, urgente: Boolean = false) {
        interno.update { i ->
            if (i.mensajes.any { it.texto == texto }) return@update i
            val mensaje = MensajeUi(siguienteIdMensaje++, texto, tipo)
            i.copy(mensajes = if (urgente) listOf(mensaje) + i.mensajes else i.mensajes + mensaje)
        }
    }

    private fun formatoDescanso(segundos: Long): String = when {
        segundos < 60 -> "$segundos s"
        segundos % 60 == 0L -> "${segundos / 60} min"
        else -> "${segundos / 60} min ${segundos % 60} s"
    }

    companion object {
        private val DESCANSO_VALIDO = 1L..86_400L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FertiTrackApp
                MainViewModel(
                    app.contenedor.repositorio,
                    app.contenedor.simulador,
                    app.contenedor.seguimiento,
                    app.contenedor.exportador,
                    app.contenedor.controlSondeo,
                    app.contenedor.vigilancia,
                )
            }
        }
    }
}
