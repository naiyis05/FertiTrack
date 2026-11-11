package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.ModoInicio

/** Pantallas a las que el asistente puede llevar; [titulo] es el nombre que ve el usuario. */
enum class Destino(val titulo: String) {
    INICIO("Inicio"),
    ZONAS("Zonas"),
    GRAFICAS("Gráficas"),
    CONTROL("Control"),
    ANALISIS("Análisis"),
    CONEXION("Conexión"),
}

/** Rutinas que el asistente puede proponer. Solo abre su confirmación: nunca la envía. */
enum class RutinaPropuesta(val modo: ModoInicio) {
    CICLO(ModoInicio.CICLO),
    SENSADO(ModoInicio.SENSADO),
    FERTILIZAR(ModoInicio.FERTILIZAR),
}

/** Lo que una respuesta del asistente pide hacer en la app. */
sealed interface AccionAsistente {
    data class IrA(val destino: Destino) : AccionAsistente

    data class ProponerRutina(val rutina: RutinaPropuesta) : AccionAsistente
}
