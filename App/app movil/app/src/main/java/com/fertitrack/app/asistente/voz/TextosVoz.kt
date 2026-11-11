package com.fertitrack.app.asistente.voz

import android.speech.SpeechRecognizer

/** Textos y preparación del texto para la voz. Lógica pura. */
object TextosVoz {
    /** TextToSpeech corta hacia los 4000 caracteres por enunciado: se deja margen. */
    const val MAX_TROZO = 3_900

    fun mensajeError(codigo: Int): String = when (codigo) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No te entendí. Toca el micrófono y vuelve a hablar."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "El reconocimiento de voz necesita internet o el paquete de español sin conexión. Mientras tanto, escribe tu pregunta."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso de micrófono. Puedes escribir tu pregunta."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocimiento de voz está ocupado. Prueba otra vez en un momento."
        SpeechRecognizer.ERROR_AUDIO -> "No se pudo usar el micrófono. Puedes escribir tu pregunta."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "El reconocimiento de voz de este teléfono no tiene español. Puedes escribir tu pregunta."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Demasiados intentos seguidos. Espera un momento y vuelve a hablar."
        else -> "No se pudo reconocer la voz. Puedes escribir tu pregunta."
    }

    private val enlaceMarkdown = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val marcas = Regex("""[*_`#>~]+""")
    private val espacios = Regex("""\s+""")

    /** Quita lo que suena mal en voz alta: markdown, enlaces y direcciones web. */
    fun limpiarParaVoz(texto: String): String = texto
        .replace(enlaceMarkdown, "$1")
        .replace(url, "")
        .replace(marcas, "")
        .replace(espacios, " ")
        .trim()

    /**
     * Parte [texto] en trozos de como mucho [max] caracteres que, unidos, lo reproducen.
     * Corta tras un fin de frase (". ") si hay uno en el trozo; si no, tras un espacio.
     */
    fun partir(texto: String, max: Int = MAX_TROZO): List<String> {
        val trozos = mutableListOf<String>()
        var inicio = 0
        while (inicio < texto.length) {
            if (texto.length - inicio <= max) {
                trozos += texto.substring(inicio)
                break
            }
            val limite = inicio + max
            val finFrase = texto.lastIndexOf(". ", limite - 2)
            val corte = when {
                finFrase >= inicio -> finFrase + 2
                else -> texto.lastIndexOf(' ', limite - 1).takeIf { it >= inicio }?.plus(1) ?: limite
            }
            trozos += texto.substring(inicio, corte)
            inicio = corte
        }
        return trozos
    }
}
