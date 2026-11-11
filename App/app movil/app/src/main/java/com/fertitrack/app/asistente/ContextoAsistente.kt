package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.componentes.Formatos

/** Lo que el asistente sabe de la app en el momento de la pregunta. */
data class EntradaContexto(
    val destino: Destino,
    /** Estado actual si hay conexión con el robot; null si no. */
    val enVivo: EstadoRobot?,
    /** Última lectura guardada, para cuando no hay conexión. */
    val foto: FotoRobot?,
    val resumen: ResumenSesion,
    /** Por qué no se pueden iniciar rutinas; null si se puede. */
    val motivoRutinas: String?,
)

/**
 * Arma el texto de contexto que acompaña cada pregunta a Gemini: pantalla, estado del robot
 * (en vivo o guardado y de cuándo), zonas, mediciones, riegos y si las rutinas están
 * disponibles. Lógica pura.
 */
object ContextoAsistente {
    private const val MAX_MEDICIONES = 6
    private const val MAX_EVENTOS = 5

    fun construir(e: EntradaContexto, ahoraMs: Long): String = buildString {
        appendLine("Pantalla actual: ${e.destino.titulo}")
        val robot = e.enVivo
        val foto = e.foto
        when {
            robot != null -> {
                appendLine("Datos: EN VIVO (la app está conectada al robot ahora).")
                val descripcion = Traductor.describirEstado(robot)
                describir(FotoRobot.de(robot, ahoraMs), "${descripcion.titulo}. ${descripcion.detalle}")
            }
            foto != null -> {
                val antiguedad = Formatos.antiguedad((ahoraMs - foto.momentoMs) / 1000)
                appendLine(
                    "Datos guardados de hace $antiguedad (${Formatos.fechaHoraCorta(foto.momentoMs)}). " +
                        "No son datos en vivo: ahora no hay conexión con el robot.",
                )
                describir(foto, Traductor.nombreEstado(foto.estado, foto.estadoTexto))
            }
            else -> appendLine("Sin datos del robot: la app no tiene lecturas en vivo ni guardadas.")
        }
        historial(e.resumen)
        append(e.motivoRutinas?.let { "Rutinas no disponibles ahora: $it" } ?: "Rutinas disponibles: el robot está en espera y sin fallas.")
    }

    private fun StringBuilder.describir(foto: FotoRobot, estado: String) {
        appendLine("Estado del robot: $estado")
        if (foto.codigoFalla != 0) appendLine("Falla activa: ${Traductor.describirFalla(foto.codigoFalla).titulo}")
        if (foto.totalZonas > 0) {
            appendLine("Recorrido: ${Formatos.centimetros(foto.distanciaCm)} en ${foto.totalZonas} zonas.")
            appendLine(
                "Zonas (último nivel medido): " +
                    foto.zonas.mapIndexed { i, nivel -> "Zona ${i + 1}: ${Traductor.nombreNivel(nivel)}" }.joinToString("; "),
            )
        } else {
            appendLine("Recorrido sin configurar: se configura en la página web del Maestro.")
        }
        foto.humedadPct?.let { appendLine("Última humedad leída: ${Formatos.porcentaje(it)}") }
        appendLine(
            if (foto.autoActivo) "Riego automático: activado, cada ${Formatos.antiguedad(foto.descansoSeg)}." else "Riego automático: desactivado.",
        )
    }

    private fun StringBuilder.historial(r: ResumenSesion) {
        val mediciones = r.muestras.sortedBy { it.momentoMs }.takeLast(MAX_MEDICIONES)
        if (mediciones.isEmpty()) {
            appendLine("Mediciones guardadas: ninguna.")
        } else {
            appendLine(
                "Mediciones recientes (${r.totalMediciones} en total): " + mediciones.joinToString("; ") { m ->
                    val donde = m.zona?.let { "zona $it" } ?: "prueba de sensor"
                    "$donde ${Formatos.porcentaje(m.humedadPct)} (${Traductor.nombreNivel(m.nivel)}) el ${Formatos.fechaHoraCorta(m.momentoMs)}"
                },
            )
        }
        val riego = r.riegos.maxByOrNull { it.finMs }
        if (riego == null) {
            appendLine("Riegos guardados: ninguno.")
        } else {
            val zonas = riego.zonasRegadas.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "zonas $it" } ?: "sin zonas secas"
            val final = if (riego.completado) "completado" else "interrumpido"
            appendLine("Último riego: $zonas, $final el ${Formatos.fechaHoraCorta(riego.finMs)} (${r.riegosCompletados} completados en total).")
        }
        if (r.sugerirFertilizar) appendLine("La app sugiere fertilizar: acaba de terminar un riego.")
        val eventos = r.eventos.sortedByDescending { it.momentoMs }.take(MAX_EVENTOS)
        if (eventos.isNotEmpty()) {
            appendLine("Eventos recientes: " + eventos.joinToString("; ") { "${Formatos.fechaHoraCorta(it.momentoMs)} ${it.texto}" })
        }
    }
}
