package com.fertitrack.app.di

import android.content.Context
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.simulador.ProveedorSimulador
import com.fertitrack.app.simulador.Simulador

/** Inyección manual: un solo repositorio para toda la app (vive con el proceso). */
class AppContainer(contexto: Context) {
    val simulador: Simulador = ProveedorSimulador.crear()

    val repositorio: RobotRepository = TODO("Implementa: agente de datos")
}
