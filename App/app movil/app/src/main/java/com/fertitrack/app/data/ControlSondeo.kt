package com.fertitrack.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decide si se consulta al robot: con la app a la vista o con la vigilancia en segundo plano
 * encendida por el usuario. Sin ninguna de las dos, el sondeo se pausa: no se consulta al
 * robot en segundo plano salvo que se haya pedido expresamente.
 */
class ControlSondeo(private val repositorio: RobotRepository) {
    private var visible = false
    private var vigilando = false

    private val _appVisible = MutableStateFlow(false)

    /** Con la app a la vista no se notifica: la pantalla ya lo muestra. */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    @Synchronized
    fun appVisible(visible: Boolean) {
        this.visible = visible
        _appVisible.value = visible
        aplicar()
    }

    @Synchronized
    fun vigilancia(activa: Boolean) {
        vigilando = activa
        aplicar()
    }

    private fun aplicar() {
        if (visible || vigilando) repositorio.reanudarSondeo() else repositorio.pausarSondeo()
    }
}
