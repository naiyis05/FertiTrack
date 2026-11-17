package com.fertitrack.app.asistente.gemini

import kotlinx.serialization.json.JsonArray

/**
 * Un turno de la conversación. Las respuestas del modelo guardan sus [partesModelo] tal como
 * llegaron: Gemini exige que se le reenvíen intactas (pueden traer firmas de pensamiento).
 */
data class TurnoChat(
    val deUsuario: Boolean,
    val texto: String,
    val partesModelo: JsonArray? = null,
)

/** Página que Gemini consultó al buscar en Google. */
data class Fuente(val titulo: String, val url: String)

enum class TipoFallo {
    SIN_CLAVE, SIN_INTERNET, CLAVE_INVALIDA, CUOTA, BLOQUEADA, MODELO_NO_EXISTE, SERVIDOR, RESPUESTA_RARA,

    /** 400 que no es de la clave: Google no aceptó la petición (casi siempre, el historial). */
    PETICION_RECHAZADA,
}

sealed interface RespuestaCerebro {
    /**
     * [sugerenciasHtml]: las sugerencias de búsqueda de Google, que deben mostrarse sin
     * modificar junto a una respuesta que usó la búsqueda.
     */
    data class Ok(
        val texto: String,
        val fuentes: List<Fuente>,
        val sugerenciasHtml: String?,
        val partesModelo: JsonArray,
    ) : RespuestaCerebro

    data class Fallo(val tipo: TipoFallo, val detalle: String? = null) : RespuestaCerebro
}

/** Lo que responde las preguntas. La implementación real es [ClienteGemini]. */
fun interface Cerebro {
    /** [historial] termina con la pregunta del usuario; [contexto] solo acompaña a esa. */
    suspend fun responder(historial: List<TurnoChat>, contexto: String): RespuestaCerebro
}
