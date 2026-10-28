package com.fertitrack.app.ui

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.analisis.Insight
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.DescripcionEstado
import com.fertitrack.app.domain.traduccion.DescripcionFalla
import java.time.LocalDate

enum class Pestana(val titulo: String) {
    INICIO("Inicio"),
    ZONAS("Zonas"),
    GRAFICAS("Gráficas"),
    CONTROL("Control"),
    ANALISIS("Análisis"),
}

/** Acción física que exige confirmación explícita antes de enviarse al robot. */
sealed interface AccionConfirmable {
    data class Iniciar(val modo: ModoInicio) : AccionConfirmable

    /** Respuesta "Sí" a la tarjeta "¿Deseas fertilizar?" tras un riego. */
    data object FertilizarTrasRiego : AccionConfirmable

    data object Reiniciar : AccionConfirmable

    data class ActivarAuto(val descansoSeg: Long) : AccionConfirmable

    /** No mueve el robot, pero no se puede deshacer. */
    data object BorrarHistorial : AccionConfirmable
}

/** Qué se exporta a CSV; [clave] va en el nombre del archivo. */
enum class TipoExportacion(val clave: String) {
    MEDICIONES("mediciones"),
    RIEGOS("riegos"),
    ;

    /** Nombre sugerido en el selector de archivos: "fertitrack_mediciones_2026-10-28.csv". */
    fun nombreArchivo(dia: LocalDate): String = "fertitrack_${clave}_$dia.csv"
}

/** Confirmación pendiente; sus textos ya vienen listos para mostrarse. */
data class Confirmacion(
    val accion: AccionConfirmable,
    val titulo: String,
    val mensaje: String,
    val textoConfirmar: String,
    /** true para acciones con bombas o movimiento: se muestra con estilo de advertencia. */
    val peligrosa: Boolean,
)

enum class TipoMensaje { INFO, EXITO, AVISO, ERROR }

data class MensajeUi(
    val id: Long,
    val texto: String,
    val tipo: TipoMensaje,
)

/** Todo lo que la interfaz necesita para dibujarse. Lo produce MainViewModel. */
data class UiState(
    val conexion: EstadoConexion = EstadoConexion.Pausado,
    val red: EstadoRed = EstadoRed.Inactiva,
    val robot: EstadoRobot? = null,
    /** true si lo que se muestra no viene de la última consulta (sin conexión). */
    val datosObsoletos: Boolean = true,
    /** Segundos desde la última respuesta válida; null si nunca hubo. */
    val antiguedadDatosSeg: Long? = null,
    val descripcionEstado: DescripcionEstado? = null,
    /** Falla activa traducida; null si no hay falla. */
    val falla: DescripcionFalla? = null,
    val resumen: ResumenSesion = ResumenSesion(),
    val insights: List<Insight> = emptyList(),

    /** Ciclo, sensado y fertilización: solo conectado, en ESPERANDO, sin falla y sin otra orden en curso. */
    val rutinasHabilitadas: Boolean = false,
    val motivoRutinasDeshabilitadas: String? = "Sin conexión con el robot",
    /** Reiniciar solo tiene efecto en EMERGENCIA. */
    val reiniciarHabilitado: Boolean = false,
    /** Activar el modo automático sigue las mismas reglas que las rutinas. */
    val puedeActivarAuto: Boolean = false,
    /** Desactivarlo siempre se permite si hay conexión: es una acción de seguridad. */
    val puedeDesactivarAuto: Boolean = false,
    val puedeGuardarDescanso: Boolean = false,

    /** Descripción de la orden que se está enviando; bloquea dobles toques. */
    val comandoEnCurso: String? = null,
    /** La parada nunca se deshabilita; esto solo muestra que se está enviando. */
    val paradaEnCurso: Boolean = false,
    val confirmacion: Confirmacion? = null,
    val mensaje: MensajeUi? = null,

    val ipRobot: String = RobotConfig.IP_ROBOT,
    val ssidRobot: String = RobotConfig.SSID_AP,
    val claveConfigurada: Boolean = RobotConfig.CLAVE_AP.isNotEmpty(),
    val simuladorDisponible: Boolean = false,
    val usandoSimulador: Boolean = false,

    /** Vigilancia en segundo plano encendida por el usuario (sondeo y avisos con la app cerrada). */
    val vigilanciaActiva: Boolean = false,
)
