package com.fertitrack.app.domain.sesion

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ResultadoComando
import kotlinx.coroutines.flow.StateFlow

/**
 * Convierte la secuencia de respuestas de /api/estado en la historia de la sesión:
 * eventos, mediciones para la gráfica, riegos y la sugerencia de fertilizar.
 *
 * Lógica pura (sin Android). La sesión dura lo que dura el proceso de la app.
 */
class SesionTracker(private val reloj: () -> Long = System::currentTimeMillis) {

    val resumen: StateFlow<ResumenSesion> = TODO("Implementa: agente de dominio")

    /** Compara [nuevo] con el estado anterior y registra lo que cambió. */
    fun procesarEstado(nuevo: EstadoRobot): Unit = TODO("Implementa: agente de dominio")

    /** Registra pérdidas y recuperaciones de conexión (sin repetir eventos). */
    fun procesarConexion(conexion: EstadoConexion): Unit = TODO("Implementa: agente de dominio")

    /** Registra una orden enviada por el usuario y su resultado. */
    fun registrarComando(descripcion: String, resultado: ResultadoComando): Unit =
        TODO("Implementa: agente de dominio")

    fun descartarSugerenciaFertilizar(): Unit = TODO("Implementa: agente de dominio")

    fun reiniciarSesion(): Unit = TODO("Implementa: agente de dominio")
}
