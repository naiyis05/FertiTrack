package com.fertitrack.app.di

import android.content.Context
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.data.ControlSondeo
import com.fertitrack.app.data.RobotRepositoryImpl
import com.fertitrack.app.data.SeguimientoSesion
import com.fertitrack.app.data.exportar.Exportador
import com.fertitrack.app.data.exportar.ExportadorAndroid
import com.fertitrack.app.data.historial.AlmacenHistorialArchivo
import com.fertitrack.app.domain.sesion.SesionTracker
import com.fertitrack.app.data.red.EnlaceRed
import com.fertitrack.app.data.red.EnlaceRedAndroid
import com.fertitrack.app.data.remote.ClienteMaestroHttp
import com.fertitrack.app.data.remote.MaestroApi
import com.fertitrack.app.simulador.ProveedorSimulador
import com.fertitrack.app.simulador.Simulador
import com.fertitrack.app.vigilancia.Notificador
import com.fertitrack.app.vigilancia.Vigilancia
import com.fertitrack.app.vigilancia.VigilanciaAndroid
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

    /** Historial: se alimenta y se guarda mientras viva el proceso, con o sin pantalla. */
    val seguimiento = SeguimientoSesion(
        repositorio = repositorio,
        tracker = SesionTracker(),
        almacen = AlmacenHistorialArchivo(contexto.filesDir),
        ambito = ambitoApp,
    ).also { it.iniciar() }

    val exportador: Exportador = ExportadorAndroid(contexto)

    /** Sondeo con la app a la vista o con la vigilancia en segundo plano encendida. */
    val controlSondeo = ControlSondeo(repositorio)

    val vigilancia: Vigilancia = VigilanciaAndroid(contexto)

    val notificador = Notificador(contexto)
}
