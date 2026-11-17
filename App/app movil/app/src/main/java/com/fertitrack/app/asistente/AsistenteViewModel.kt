package com.fertitrack.app.asistente

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fertitrack.app.FertiTrackApp
import com.fertitrack.app.asistente.gemini.Cerebro
import com.fertitrack.app.asistente.gemini.Fuente
import com.fertitrack.app.asistente.gemini.RespuestaCerebro
import com.fertitrack.app.asistente.gemini.TipoFallo
import com.fertitrack.app.asistente.gemini.TurnoChat
import com.fertitrack.app.asistente.voz.EntradaVoz
import com.fertitrack.app.asistente.voz.EntradaVozAndroid
import com.fertitrack.app.asistente.voz.EstadoEscucha
import com.fertitrack.app.asistente.voz.SalidaVoz
import com.fertitrack.app.asistente.voz.SalidaVozAndroid
import com.fertitrack.app.config.AsistenteConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.Traductor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MensajeChat(
    val id: Long,
    val deUsuario: Boolean,
    val texto: String,
    val fuentes: List<Fuente> = emptyList(),
    /** HTML de las sugerencias de búsqueda de Google: se muestra tal cual. */
    val sugerenciasHtml: String? = null,
    val acciones: List<AccionAsistente> = emptyList(),
    val esError: Boolean = false,
)

data class EstadoAsistente(
    val abierto: Boolean = false,
    val mensajes: List<MensajeChat> = emptyList(),
    /** Lo que el reconocimiento de voz lleva transcrito mientras la persona habla. */
    val transcripcion: String = "",
    val escuchando: Boolean = false,
    val nivelVoz: Float = 0f,
    val pensando: Boolean = false,
    val hablando: Boolean = false,
    /** Leer las respuestas en voz alta. */
    val vozActivada: Boolean = true,
    val vozDisponible: Boolean = false,
    val claveConfigurada: Boolean = false,
    val sugerencias: List<String> = emptyList(),
)

/** Lo que el panel del asistente puede pedir. [AsistenteViewModel] lo implementa. */
interface AccionesAsistente {
    fun abrir()
    fun cerrar()
    fun enviarTexto(texto: String)

    /** Empieza a escuchar o, si ya escucha, termina y envía lo transcrito. */
    fun alternarMicrofono()

    /** Corta la respuesta en curso, la escucha y la voz. */
    fun cancelar()

    /** Activa o desactiva leer las respuestas en voz alta. */
    fun alternarVoz()
    fun pantallaActual(destino: Destino)
    fun permisoMicrofono(concedido: Boolean)

    /** Repite una acción de un mensaje (chip "Ir a…" o "Abrir confirmación"). */
    fun repetirAccion(accion: AccionAsistente)
}

/** Para pruebas y vistas previas: no hace nada. */
object AccionesAsistenteNulas : AccionesAsistente {
    override fun abrir() = Unit
    override fun cerrar() = Unit
    override fun enviarTexto(texto: String) = Unit
    override fun alternarMicrofono() = Unit
    override fun cancelar() = Unit
    override fun alternarVoz() = Unit
    override fun pantallaActual(destino: Destino) = Unit
    override fun permisoMicrofono(concedido: Boolean) = Unit
    override fun repetirAccion(accion: AccionAsistente) = Unit
}

/**
 * Coordina voz → Gemini → voz. Las acciones que pide una respuesta salen por [eventos]; la
 * pantalla las ejecuta. Una rutina solo se propone si el robot puede hacerla ahora: si no,
 * la respuesta explica el motivo. El asistente nunca envía órdenes al robot.
 */
class AsistenteViewModel(
    private val cerebro: Cerebro,
    private val entrada: EntradaVoz,
    private val salida: SalidaVoz,
    private val robot: StateFlow<EstadoRobot?>,
    private val conexion: StateFlow<EstadoConexion>,
    private val resumen: StateFlow<ResumenSesion>,
    private val ultimaFoto: StateFlow<FotoRobot?>,
    claveConfigurada: Boolean,
    private val reloj: () -> Long = System::currentTimeMillis,
) : ViewModel(), AccionesAsistente {

    private var destino = Destino.INICIO

    private val _estado = MutableStateFlow(
        EstadoAsistente(
            vozDisponible = entrada.disponible,
            claveConfigurada = claveConfigurada,
            sugerencias = Sugerencias.para(destino, robot.value),
        ),
    )
    val estado: StateFlow<EstadoAsistente> = _estado.asStateFlow()

    private val _eventos = MutableSharedFlow<AccionAsistente>(extraBufferCapacity = 8)
    val eventos: SharedFlow<AccionAsistente> = _eventos.asSharedFlow()

    /** Conversación que se envía a Gemini: alterna usuario y modelo. */
    private val turnos = mutableListOf<TurnoChat>()
    private var trabajo: Job? = null
    private var siguienteId = 1L

    init {
        viewModelScope.launch { entrada.estado.collect(::alEscuchar) }
        viewModelScope.launch { salida.hablando.collect { h -> _estado.update { it.copy(hablando = h) } } }
        viewModelScope.launch { robot.collect { r -> _estado.update { it.copy(sugerencias = Sugerencias.para(destino, r)) } } }
    }

    override fun abrir() = _estado.update { it.copy(abierto = true, vozDisponible = entrada.disponible) }

    override fun cerrar() {
        cancelar()
        _estado.update { it.copy(abierto = false) }
    }

    override fun enviarTexto(texto: String) {
        val pregunta = texto.trim()
        if (pregunta.isEmpty()) return
        trabajo?.cancel()
        quitarPreguntaSinRespuesta()
        salida.detener()
        agregar(MensajeChat(nuevoId(), deUsuario = true, texto = pregunta))
        turnos += TurnoChat(deUsuario = true, texto = pregunta)
        _estado.update { it.copy(pensando = true, transcripcion = "") }

        val historial = turnos.takeLast(AsistenteConfig.MAX_TURNOS).dropWhile { !it.deUsuario }
        val contexto = ContextoAsistente.construir(
            EntradaContexto(destino, robotEnVivo(), ultimaFoto.value, resumen.value, motivoRutinas()),
            reloj(),
        )
        trabajo = viewModelScope.launch {
            when (val r = cerebro.responder(historial, contexto)) {
                is RespuestaCerebro.Ok -> alResponder(r)
                is RespuestaCerebro.Fallo -> alFallar(r)
            }
            _estado.update { it.copy(pensando = false) }
        }
    }

    private fun alResponder(r: RespuestaCerebro.Ok) {
        turnos += TurnoChat(deUsuario = false, texto = r.texto, partesModelo = r.partesModelo)
        val interpretada = EtiquetasAccion.interpretar(r.texto)
        var texto = interpretada.texto.ifBlank { TextosAsistente.SOLO_ACCION }
        val acciones = mutableListOf<AccionAsistente>()
        for (accion in interpretada.acciones) {
            val motivo = (accion as? AccionAsistente.ProponerRutina)?.let { motivoRutinas() }
            if (motivo == null) acciones += accion else texto = "$texto ${TextosAsistente.rutinaNoDisponible(motivo)}"
        }
        agregar(MensajeChat(nuevoId(), deUsuario = false, texto = texto, fuentes = r.fuentes, sugerenciasHtml = r.sugerenciasHtml, acciones = acciones))
        if (_estado.value.vozActivada) salida.hablar(texto)
        acciones.forEach { _eventos.tryEmit(it) }
    }

    private fun alFallar(f: RespuestaCerebro.Fallo) {
        quitarPreguntaSinRespuesta()
        // Un 400 suele venir del historial (p. ej., partes del modelo que Google ya no acepta):
        // repetir con el mismo historial fallaría siempre. Se empieza de cero para Gemini; los
        // mensajes en pantalla se conservan.
        if (f.tipo == TipoFallo.PETICION_RECHAZADA) turnos.clear()
        agregar(MensajeChat(nuevoId(), deUsuario = false, texto = TextosAsistente.fallo(f.tipo), esError = true))
        if (_estado.value.vozActivada) salida.hablar(TextosAsistente.falloHablado(f.tipo))
    }

    override fun alternarMicrofono() {
        if (_estado.value.escuchando) {
            entrada.detener()
            return
        }
        if (!entrada.disponible) {
            agregar(MensajeChat(nuevoId(), deUsuario = false, texto = TextosAsistente.SIN_RECONOCEDOR, esError = true))
            return
        }
        salida.detener()
        entrada.empezar()
    }

    private fun alEscuchar(e: EstadoEscucha) {
        when (e) {
            EstadoEscucha.Inactiva -> _estado.update { it.copy(escuchando = false, nivelVoz = 0f) }
            is EstadoEscucha.Escuchando -> _estado.update { it.copy(escuchando = true, transcripcion = e.parcial, nivelVoz = e.nivel) }
            is EstadoEscucha.Transcribiendo -> _estado.update { it.copy(escuchando = true, transcripcion = e.parcial, nivelVoz = 0f) }
            is EstadoEscucha.Terminada -> {
                _estado.update { it.copy(escuchando = false, transcripcion = "", nivelVoz = 0f) }
                enviarTexto(e.texto)
            }
            is EstadoEscucha.Error -> {
                _estado.update { it.copy(escuchando = false, transcripcion = "", nivelVoz = 0f) }
                agregar(MensajeChat(nuevoId(), deUsuario = false, texto = e.mensaje, esError = true))
            }
        }
    }

    override fun cancelar() {
        trabajo?.cancel()
        trabajo = null
        quitarPreguntaSinRespuesta()
        entrada.cancelar()
        salida.detener()
        _estado.update { it.copy(pensando = false, escuchando = false, transcripcion = "", nivelVoz = 0f) }
    }

    override fun alternarVoz() {
        val activada = !_estado.value.vozActivada
        if (!activada) salida.detener()
        _estado.update { it.copy(vozActivada = activada) }
    }

    override fun pantallaActual(destino: Destino) {
        this.destino = destino
        _estado.update { it.copy(sugerencias = Sugerencias.para(destino, robot.value)) }
    }

    override fun permisoMicrofono(concedido: Boolean) {
        if (!concedido) agregar(MensajeChat(nuevoId(), deUsuario = false, texto = TextosAsistente.SIN_PERMISO_MICROFONO, esError = true))
    }

    override fun repetirAccion(accion: AccionAsistente) {
        val motivo = (accion as? AccionAsistente.ProponerRutina)?.let { motivoRutinas() }
        if (motivo == null) {
            _eventos.tryEmit(accion)
        } else {
            agregar(MensajeChat(nuevoId(), deUsuario = false, texto = TextosAsistente.rutinaNoDisponible(motivo), esError = true))
        }
    }

    /** En vivo solo si la última consulta respondió: si no, el contexto usa la foto guardada. */
    private fun robotEnVivo(): EstadoRobot? = robot.value.takeIf { conexion.value is EstadoConexion.Conectado }

    private fun motivoRutinas(): String? = Traductor.motivoRutinasNoDisponibles(robot.value, conexion.value)

    /** Una pregunta cancelada o fallida no se reenvía: el historial sigue alternando. */
    private fun quitarPreguntaSinRespuesta() {
        if (turnos.lastOrNull()?.deUsuario == true) turnos.removeAt(turnos.lastIndex)
    }

    private fun agregar(mensaje: MensajeChat) = _estado.update { it.copy(mensajes = it.mensajes + mensaje) }

    private fun nuevoId() = siguienteId++

    override fun onCleared() {
        trabajo?.cancel()
        entrada.liberar()
        salida.liberar()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FertiTrackApp
                val contenedor = app.contenedor
                AsistenteViewModel(
                    cerebro = contenedor.cerebro,
                    entrada = EntradaVozAndroid(app),
                    salida = SalidaVozAndroid(app),
                    robot = contenedor.repositorio.estado,
                    conexion = contenedor.repositorio.conexion,
                    resumen = contenedor.seguimiento.tracker.resumen,
                    ultimaFoto = contenedor.seguimiento.ultimaFoto,
                    claveConfigurada = AsistenteConfig.CLAVE_GEMINI.isNotBlank(),
                )
            }
        }
    }
}
