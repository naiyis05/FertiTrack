package com.fertitrack.app.asistente

import com.fertitrack.app.asistente.gemini.TipoFallo

/** Mensajes propios del asistente (no vienen de Gemini). */
object TextosAsistente {

    const val SIN_PERMISO_MICROFONO = "Sin permiso de micrófono. Puedes escribir tu pregunta."
    const val SIN_RECONOCEDOR = "Este teléfono no tiene reconocimiento de voz. Puedes escribir tu pregunta."
    const val SOLO_ACCION = "Listo."

    fun fallo(tipo: TipoFallo): String = when (tipo) {
        TipoFallo.SIN_CLAVE ->
            "Falta la clave de Gemini: agrégala en local.properties (fertitrack.gemini.apiKey) y vuelve a instalar la app."
        TipoFallo.SIN_INTERNET ->
            "Sin internet. El Wi-Fi del robot no da internet: usa datos móviles u otra red y vuelve a preguntar. Los datos del robot se siguen guardando."
        TipoFallo.CLAVE_INVALIDA -> "Google rechazó la clave de Gemini. Revisa que esté bien copiada en local.properties."
        TipoFallo.CUOTA -> "Se alcanzó el límite de uso de Gemini. Prueba de nuevo en unos minutos."
        TipoFallo.BLOQUEADA -> "Gemini no quiso responder a eso. Prueba a preguntarlo de otra forma."
        TipoFallo.MODELO_NO_EXISTE -> "El modelo de Gemini configurado no existe. Revisa fertitrack.gemini.modelo en gradle.properties."
        TipoFallo.SERVIDOR -> "Gemini no respondió a tiempo. Vuelve a intentarlo."
        TipoFallo.RESPUESTA_RARA -> "Gemini respondió algo que la app no entiende. Vuelve a intentarlo."
    }

    fun rutinaNoDisponible(motivo: String): String = "Ahora no se puede: ${motivo.trimEnd('.')}."
}
