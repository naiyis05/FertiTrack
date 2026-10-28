package com.fertitrack.app.vigilancia

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fertitrack.app.MainActivity
import com.fertitrack.app.R
import com.fertitrack.app.domain.sesion.AvisoSesion

/** Canales y notificaciones de FertiTrack. Tocar cualquiera abre la app. */
class Notificador(contexto: Context) {
    private val app = contexto.applicationContext
    private val gestor = NotificationManagerCompat.from(app)

    fun crearCanales() {
        gestor.createNotificationChannelsCompat(
            listOf(
                canal(CANAL_VIGILANCIA, "Vigilancia activa", NotificationManagerCompat.IMPORTANCE_LOW,
                    "Notificación fija mientras la app vigila el robot en segundo plano."),
                canal(CANAL_FALLAS, "Fallas del robot", NotificationManagerCompat.IMPORTANCE_HIGH,
                    "Emergencias y fallas: el robot se detuvo."),
                canal(CANAL_RIEGO, "Riego y zonas secas", NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    "Fin de cada riego y zonas secas encontradas al sensar."),
                canal(CANAL_CONEXION, "Conexión con el robot", NotificationManagerCompat.IMPORTANCE_LOW,
                    "Cortes largos de conexión con el robot."),
            ),
        )
    }

    /** Muestra el aviso; la conexión recuperada solo retira el aviso de corte. */
    fun mostrar(aviso: AvisoSesion) {
        if (aviso == AvisoSesion.ConexionRecuperada) {
            gestor.cancel(ID_CONEXION)
            return
        }
        val texto = TextosAviso.de(aviso) ?: return
        val (canal, id, prioridad) = when (texto.canal) {
            CanalAviso.FALLAS -> Triple(CANAL_FALLAS, ID_FALLA, NotificationCompat.PRIORITY_HIGH)
            CanalAviso.RIEGO -> Triple(CANAL_RIEGO, if (aviso is AvisoSesion.ZonasSecas) ID_ZONAS else ID_RIEGO, NotificationCompat.PRIORITY_DEFAULT)
            CanalAviso.CONEXION -> Triple(CANAL_CONEXION, ID_CONEXION, NotificationCompat.PRIORITY_LOW)
        }
        val notificacion = NotificationCompat.Builder(app, canal)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(texto.titulo)
            .setContentText(texto.texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto.texto))
            .setPriority(prioridad)
            .setCategory(if (texto.canal == CanalAviso.FALLAS) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        publicar(id, notificacion)
    }

    /** Notificación fija del servicio, con la acción de dejar de vigilar. */
    fun vigilancia(estado: String): Notification =
        NotificationCompat.Builder(app, CANAL_VIGILANCIA)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("FertiTrack vigila el robot")
            .setContentText(estado)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(abrirApp())
            .addAction(0, "Dejar de vigilar", dejarDeVigilar())
            .build()

    fun actualizarVigilancia(estado: String) = publicar(ID_VIGILANCIA, vigilancia(estado))

    fun retirarCorte() = gestor.cancel(ID_CONEXION)

    private fun publicar(id: Int, notificacion: Notification) {
        // Android 13+: sin el permiso no se publica nada (el servicio sigue funcionando igual).
        val permitido = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (permitido) gestor.notify(id, notificacion)
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dejarDeVigilar(): PendingIntent = PendingIntent.getService(
        app,
        1,
        Intent(app, ServicioVigilancia::class.java).setAction(ServicioVigilancia.ACCION_DETENER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canal(id: String, nombre: String, importancia: Int, descripcion: String) =
        NotificationChannelCompat.Builder(id, importancia).setName(nombre).setDescription(descripcion).build()

    companion object {
        const val ID_VIGILANCIA = 1
        private const val ID_FALLA = 10
        private const val ID_RIEGO = 11
        private const val ID_ZONAS = 12
        private const val ID_CONEXION = 13

        private const val CANAL_VIGILANCIA = "vigilancia"
        private const val CANAL_FALLAS = "fallas"
        private const val CANAL_RIEGO = "riego"
        private const val CANAL_CONEXION = "conexion"
    }
}
