package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo

/** Preguntas sugeridas según la pantalla, para cuando la conversación está vacía. */
object Sugerencias {

    fun para(destino: Destino, robot: EstadoRobot?): List<String> {
        val haySecas = robot?.zonas?.any { it == NivelSuelo.SECA } == true
        val hayFalla = robot?.hayFalla == true
        return when (destino) {
            Destino.INICIO -> listOf(
                if (hayFalla) "¿Qué significa la falla y cómo la soluciono?" else "¿Cómo está el invernadero?",
                "¿Cuándo fue el último riego?",
                "¿Qué tiempo hará hoy?",
            )
            Destino.ZONAS -> listOf(
                if (haySecas) "¿Qué zonas están secas y qué hago?" else "¿Cómo está cada zona?",
                "¿Qué significa cada color?",
            )
            Destino.GRAFICAS -> listOf(
                "¿Cómo exporto los datos a Excel?",
                "¿Cómo ha cambiado la humedad hoy?",
            )
            Destino.CONTROL -> listOf(
                "¿Qué diferencia hay entre ciclo completo y solo sensar?",
                "¿Cómo activo el riego automático?",
                "Quiero fertilizar",
            )
            Destino.ANALISIS -> listOf(
                "Resúmeme el análisis",
                "¿Qué me recomiendas hacer hoy?",
            )
            Destino.CONEXION -> listOf(
                "¿Cómo me conecto al robot?",
                "¿Por qué no tengo internet con el robot?",
            )
        }
    }
}
