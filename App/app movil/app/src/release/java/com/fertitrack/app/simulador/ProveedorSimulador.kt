package com.fertitrack.app.simulador

/** Release: el simulador no existe; la app solo habla con el robot real. */
object ProveedorSimulador {
    fun crear(): Simulador = SimuladorNoDisponible
}

private object SimuladorNoDisponible : Simulador {
    override val disponible: Boolean = false
    override fun iniciar(): String = error("El simulador solo existe en builds debug")
    override fun detener() = Unit
    override fun inyectarFalla(codigo: Int) = Unit
    override fun simularPerdidaConexion(segundos: Int) = Unit
}
