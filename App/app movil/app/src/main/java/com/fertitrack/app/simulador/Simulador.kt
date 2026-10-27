package com.fertitrack.app.simulador

/**
 * Robot simulado para probar sin hardware. Solo existe de verdad en builds debug
 * (FakeFertiTrackServer en src/debug); en release, [disponible] es false.
 */
interface Simulador {
    val disponible: Boolean

    /** Arranca el servidor local (idempotente) y devuelve su URL base, p. ej. "http://127.0.0.1:8080". */
    fun iniciar(): String

    fun detener()

    /** Fuerza una falla del firmware (códigos 1, 2, 3 o 9). */
    fun inyectarFalla(codigo: Int)

    /** El servidor deja de responder durante [segundos]. */
    fun simularPerdidaConexion(segundos: Int)
}
