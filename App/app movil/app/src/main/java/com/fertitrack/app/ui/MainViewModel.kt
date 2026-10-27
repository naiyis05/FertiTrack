package com.fertitrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fertitrack.app.FertiTrackApp
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.domain.sesion.SesionTracker
import com.fertitrack.app.simulador.Simulador
import kotlinx.coroutines.flow.StateFlow

/**
 * Une repositorio, sesión y análisis en un único [UiState] y aplica las reglas
 * de seguridad antes de enviar cualquier orden.
 */
class MainViewModel(
    private val repositorio: RobotRepository,
    private val simulador: Simulador,
    private val tracker: SesionTracker = SesionTracker(),
    private val reloj: () -> Long = System::currentTimeMillis,
) : ViewModel(), AccionesUi {

    val ui: StateFlow<UiState> = TODO("Implementa: agente de dominio")

    override fun pedirConfirmacion(accion: AccionConfirmable): Unit = TODO("Implementa: agente de dominio")
    override fun confirmar(): Unit = TODO("Implementa: agente de dominio")
    override fun cancelarConfirmacion(): Unit = TODO("Implementa: agente de dominio")
    override fun detenerEmergencia(): Unit = TODO("Implementa: agente de dominio")
    override fun desactivarAuto(): Unit = TODO("Implementa: agente de dominio")
    override fun guardarDescanso(descansoSeg: Long): Unit = TODO("Implementa: agente de dominio")
    override fun responderSugerenciaFertilizar(aceptar: Boolean): Unit = TODO("Implementa: agente de dominio")
    override fun conectarWifiRobot(): Unit = TODO("Implementa: agente de dominio")
    override fun permisosActualizados(): Unit = TODO("Implementa: agente de dominio")
    override fun usarSimulador(activo: Boolean): Unit = TODO("Implementa: agente de dominio")
    override fun simularFalla(codigo: Int): Unit = TODO("Implementa: agente de dominio")
    override fun simularPerdidaConexion(segundos: Int): Unit = TODO("Implementa: agente de dominio")
    override fun mensajeMostrado(id: Long): Unit = TODO("Implementa: agente de dominio")
    override fun alPrimerPlano(): Unit = TODO("Implementa: agente de dominio")
    override fun alSegundoPlano(): Unit = TODO("Implementa: agente de dominio")

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FertiTrackApp
                MainViewModel(app.contenedor.repositorio, app.contenedor.simulador)
            }
        }
    }
}
