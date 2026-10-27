package com.fertitrack.app.domain.modelo

/** Resultado de una orden al Maestro. Las órdenes NUNCA se reintentan automáticamente. */
sealed interface ResultadoComando {
    /** 200 con ok:true. [mensaje] = campo "mensaje" si vino; [datos] = otros campos simples. */
    data class Exito(
        val mensaje: String? = null,
        val datos: Map<String, String> = emptyMap(),
    ) : ResultadoComando

    /** 409: robot ocupado o estado que no lo permite. Es un caso normal, no un fallo de red. */
    data class Ocupado(val mensaje: String) : ResultadoComando

    /** 400: parámetros rechazados por el firmware. */
    data class Rechazado(val mensaje: String) : ResultadoComando

    /** 404, 500 o cualquier otra respuesta inesperada. */
    data class ErrorRobot(val codigoHttp: Int, val mensaje: String) : ResultadoComando

    /** No hubo respuesta: sin red, timeout o conexión rechazada. */
    data class SinRespuesta(val detalle: String) : ResultadoComando
}
