package com.fertitrack.app.asistente

/** Texto listo para mostrar y leer, sin etiquetas, y las acciones que pedían. */
data class RespuestaInterpretada(val texto: String, val acciones: List<AccionAsistente>)

/**
 * Lee las etiquetas `[[ir:X]]` y `[[rutina:X]]` que [PromptSistema] pide a Gemini y las quita
 * del texto. Cualquier otra etiqueta también se quita, sin acción. Como mucho una rutina por
 * respuesta: la primera.
 */
object EtiquetasAccion {
    private val etiqueta = Regex("""\[\[\s*([A-Za-z_]+)\s*:\s*([A-Za-z_]+)\s*]]""")
    private val espaciosSeguidos = Regex("""[ \t]{2,}""")
    private val espacioAntesDePuntuacion = Regex("""[ \t]+([.,;:!?])""")

    fun interpretar(texto: String): RespuestaInterpretada {
        val acciones = mutableListOf<AccionAsistente>()
        for (m in etiqueta.findAll(texto)) {
            val valor = m.groupValues[2].uppercase()
            val accion = when (m.groupValues[1].lowercase()) {
                "ir" -> Destino.entries.firstOrNull { it.name == valor }?.let { AccionAsistente.IrA(it) }
                "rutina" -> RutinaPropuesta.entries.firstOrNull { it.name == valor }?.let { AccionAsistente.ProponerRutina(it) }
                else -> null
            } ?: continue
            val otraRutina = accion is AccionAsistente.ProponerRutina && acciones.any { it is AccionAsistente.ProponerRutina }
            if (!otraRutina && accion !in acciones) acciones += accion
        }
        val sinEtiquetas = texto.replace(etiqueta, "")
            .replace(espacioAntesDePuntuacion, "$1")
            .replace(espaciosSeguidos, " ")
        val limpio = sinEtiquetas.lines().joinToString("\n") { it.trim() }.trim()
        return RespuestaInterpretada(limpio, acciones)
    }
}
