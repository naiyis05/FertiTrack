package com.fertitrack.app.simulador

/**
 * [Simulador] de los builds debug: un [FakeFertiTrackServer] en 127.0.0.1.
 *
 * Cada [iniciar] tras un [detener] equivale a encender el robot: ARRANQUE → RECUP_* → ESPERANDO,
 * con 300 cm y 6 zonas ya configurados. La semilla fija hace que las humedades se repitan
 * entre sesiones (útil para reproducir lo que se ve en pantalla).
 */
internal class SimuladorDebug(
    private val tiempos: TiemposSimulados = TiemposSimulados(),
    private val semilla: Long = SEMILLA,
) : Simulador {

    private var servidor: FakeFertiTrackServer? = null

    override val disponible: Boolean = true

    @Synchronized
    override fun iniciar(): String {
        servidor?.urlBase?.let { return it }
        val nuevo = FakeFertiTrackServer(MaquinaRobotSimulada(tiempos, semilla))
        val url = nuevo.iniciar()
        servidor = nuevo
        return url
    }

    @Synchronized
    override fun detener() {
        servidor?.detener()
        servidor = null
    }

    @Synchronized
    override fun inyectarFalla(codigo: Int) {
        servidor?.inyectarFalla(codigo)
    }

    @Synchronized
    override fun simularPerdidaConexion(segundos: Int) {
        servidor?.simularPerdidaConexion(segundos)
    }

    private companion object {
        const val SEMILLA = 2026L
    }
}
