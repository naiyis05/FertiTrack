package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo

/**
 * Última lectura del robot que se guarda en el teléfono: lo que el asistente necesita para
 * contestar sin conexión con el robot, con la hora en que se leyó.
 */
data class FotoRobot(
    val momentoMs: Long,
    val estado: EstadoMaquina,
    val estadoTexto: String,
    val totalZonas: Int,
    val distanciaCm: Float,
    val zonas: List<NivelSuelo>,
    val humedadPct: Float?,
    val codigoFalla: Int,
    val autoActivo: Boolean,
    val descansoSeg: Long,
) {
    /** Igual salvo la hora: sirve para no reescribir el archivo con cada consulta. */
    fun mismoContenido(otra: FotoRobot?): Boolean = otra != null && copy(momentoMs = 0) == otra.copy(momentoMs = 0)

    companion object {
        fun de(robot: EstadoRobot, momentoMs: Long) = FotoRobot(
            momentoMs = momentoMs,
            estado = robot.estado,
            estadoTexto = robot.estadoTexto,
            totalZonas = robot.totalZonas,
            distanciaCm = robot.distanciaCm,
            zonas = robot.zonas,
            humedadPct = robot.humedadPct,
            codigoFalla = robot.codigoFalla,
            autoActivo = robot.autoActivo,
            descansoSeg = robot.descansoSeg,
        )
    }
}
