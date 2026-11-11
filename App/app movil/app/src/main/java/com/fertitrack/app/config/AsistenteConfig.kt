package com.fertitrack.app.config

import com.fertitrack.app.BuildConfig

/**
 * Parámetros del asistente de IA.
 *
 * El modelo viene de gradle.properties; la clave de Gemini, de local.properties (no
 * versionado). Ningún otro archivo debe repetir estos valores.
 */
object AsistenteConfig {
    /** Vacía si no se configuró: el asistente avisa en lugar de llamar a Gemini. */
    val CLAVE_GEMINI: String = BuildConfig.GEMINI_API_KEY
    val MODELO: String = BuildConfig.GEMINI_MODELO

    const val URL_BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Turnos de conversación que se envían (10 preguntas con sus respuestas). */
    const val MAX_TURNOS = 20

    const val TIMEOUT_CONEXION_MS = 10_000L

    /** La búsqueda en Google puede tardar: más margen que para el robot. */
    const val TIMEOUT_LECTURA_MS = 45_000L
}
