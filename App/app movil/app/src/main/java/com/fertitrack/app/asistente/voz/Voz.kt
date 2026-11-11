package com.fertitrack.app.asistente.voz

import kotlinx.coroutines.flow.StateFlow

/** Momento del reconocimiento de voz: escuchando, transcribiendo, terminado o con error. */
sealed interface EstadoEscucha {
    data object Inactiva : EstadoEscucha

    /** [parcial]: lo transcrito hasta ahora; [nivel]: volumen de 0 a 1 para la animación. */
    data class Escuchando(val parcial: String, val nivel: Float) : EstadoEscucha

    /** Terminó de hablar; el reconocedor está cerrando el texto. */
    data class Transcribiendo(val parcial: String) : EstadoEscucha

    data class Terminada(val texto: String) : EstadoEscucha

    data class Error(val mensaje: String) : EstadoEscucha
}

/** Voz a texto. La implementación real es [EntradaVozAndroid]. */
interface EntradaVoz {
    /** false si el teléfono no tiene un servicio de reconocimiento de voz. */
    val disponible: Boolean
    val estado: StateFlow<EstadoEscucha>

    fun empezar()

    /** Deja de escuchar y entrega lo transcrito. */
    fun detener()

    /** Deja de escuchar sin entregar nada. */
    fun cancelar()

    fun liberar()
}

/** Texto a voz. La implementación real es [SalidaVozAndroid]. */
interface SalidaVoz {
    val hablando: StateFlow<Boolean>

    /** Corta lo que se esté diciendo y dice [texto], ya limpio de markdown y enlaces. */
    fun hablar(texto: String)

    fun detener()

    fun liberar()
}
