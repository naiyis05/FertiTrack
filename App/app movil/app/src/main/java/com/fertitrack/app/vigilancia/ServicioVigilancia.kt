package com.fertitrack.app.vigilancia

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.fertitrack.app.FertiTrackApp
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.sesion.AvisoSesion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano de la vigilancia: mientras corre, el sondeo sigue aunque la app
 * esté cerrada o la pantalla apagada (con un wake lock parcial), el historial se sigue
 * guardando y los avisos se notifican si la app no está a la vista.
 *
 * Es de tipo "dispositivo conectado": habla con el robot por su red Wi-Fi. Mientras corre,
 * Android mantiene la conexión pedida con WifiNetworkSpecifier aunque la app no esté visible.
 */
class ServicioVigilancia : Service() {

    private val contenedor get() = (application as FertiTrackApp).contenedor
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bloqueo: PowerManager.WakeLock? = null
    private var avisoCorte: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notificador = contenedor.notificador
        notificador.crearCanales()
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, Notificador.ID_VIGILANCIA, notificador.vigilancia("Conectando con el robot…"), tipo)

        bloqueo = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FertiTrack:vigilancia")
            .apply { setReferenceCounted(false) }
        ambito.launch {
            // Se renueva antes de que caduque: un wake lock sin plazo es peligroso si algo falla.
            while (true) {
                bloqueo?.acquire(PLAZO_WAKE_LOCK_MS)
                delay(PLAZO_WAKE_LOCK_MS / 2)
            }
        }
        contenedor.controlSondeo.vigilancia(true)

        val repositorio = contenedor.repositorio
        ambito.launch {
            combine(repositorio.estado, repositorio.conexion, TextosAviso::estadoVigilancia)
                .distinctUntilChanged()
                .collect { notificador.actualizarVigilancia(it) }
        }
        ambito.launch {
            contenedor.seguimiento.tracker.avisos.collect { aviso -> alAviso(aviso) }
        }
    }

    private fun alAviso(aviso: AvisoSesion) {
        val notificador = contenedor.notificador
        when (aviso) {
            AvisoSesion.ConexionPerdida -> {
                // Solo si el corte dura: los fallos sueltos no merecen notificación.
                avisoCorte?.cancel()
                avisoCorte = ambito.launch {
                    delay(TextosAviso.ESPERA_CORTE_SEG * 1_000L)
                    val conectado = contenedor.repositorio.conexion.value is EstadoConexion.Conectado
                    if (!conectado && !contenedor.controlSondeo.appVisible.value) notificador.mostrar(aviso)
                }
            }
            AvisoSesion.ConexionRecuperada -> {
                avisoCorte?.cancel()
                notificador.retirarCorte()
            }
            else -> if (!contenedor.controlSondeo.appVisible.value) notificador.mostrar(aviso)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) contenedor.vigilancia.activar(false) // detiene este servicio
        // Si el sistema lo mata, no se reinicia solo: se reanuda al volver a abrir la app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ambito.cancel()
        bloqueo?.takeIf { it.isHeld }?.release()
        contenedor.controlSondeo.vigilancia(false)
        super.onDestroy()
    }

    companion object {
        const val ACCION_DETENER = "com.fertitrack.app.vigilancia.DETENER"
        private const val PLAZO_WAKE_LOCK_MS = 6 * 60 * 60 * 1_000L
    }
}
