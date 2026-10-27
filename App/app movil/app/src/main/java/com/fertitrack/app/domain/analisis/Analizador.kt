package com.fertitrack.app.domain.analisis

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.sesion.ResumenSesion

enum class Severidad { INFO, EXITO, AVISO, CRITICO }

data class Insight(
    val titulo: String,
    val detalle: String,
    val severidad: Severidad,
)

/**
 * Análisis automático basado en reglas. Es lógica NUEVA: la app de escritorio
 * tenía el panel "Insight" pero nunca lo rellenaba.
 */
object Analizador {
    /** Insights ordenados del más al menos importante (CRITICO primero). */
    fun generar(
        robot: EstadoRobot?,
        resumen: ResumenSesion,
        conexion: EstadoConexion,
        ahoraMs: Long,
    ): List<Insight> = TODO("Implementa: agente de dominio")
}
