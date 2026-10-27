package com.fertitrack.app.di

import android.content.Context
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.data.RobotRepositoryImpl
import com.fertitrack.app.data.red.EnlaceRed
import com.fertitrack.app.data.red.EnlaceRedAndroid
import com.fertitrack.app.data.remote.ClienteMaestroHttp
import com.fertitrack.app.data.remote.MaestroApi
import com.fertitrack.app.simulador.ProveedorSimulador
import com.fertitrack.app.simulador.Simulador
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Inyección manual: un solo repositorio para toda la app (vive con el proceso). */
class AppContainer(contexto: Context) {
    /** Ámbito de aplicación: sondeo y escucha de redes. Vive tanto como el proceso. */
    private val ambitoApp = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val simulador: Simulador = ProveedorSimulador.crear()

    private val clienteMaestro: MaestroApi = ClienteMaestroHttp()

    private val enlaceRed: EnlaceRed = EnlaceRedAndroid(contexto)

    val repositorio: RobotRepository = RobotRepositoryImpl(
        api = clienteMaestro,
        enlace = enlaceRed,
        simulador = simulador,
        ambito = ambitoApp,
    )
}
