package com.fertitrack.app.vigilancia

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.sesion.AvisoSesion
import com.fertitrack.app.domain.traduccion.Traductor

/** Canal de notificación de cada aviso: el usuario puede silenciarlos por separado. */
enum class CanalAviso { FALLAS, RIEGO, CONEXION }

data class TextoAviso(val canal: CanalAviso, val titulo: String, val texto: String)

/**
 * Textos de las notificaciones. Ninguna ofrece mover el robot desde la notificación: fertilizar
 * o regar se confirman siempre dentro de la app.
 */
object TextosAviso {

    /** Segundos sin respuesta antes de avisar del corte (un corte breve no merece notificación). */
    const val ESPERA_CORTE_SEG = 30

    /** null: no se notifica (la conexión recuperada solo retira el aviso de corte). */
    fun de(aviso: AvisoSesion): TextoAviso? = when (aviso) {
        is AvisoSesion.Falla -> {
            val falla = Traductor.describirFalla(aviso.codigo)
            TextoAviso(CanalAviso.FALLAS, "Falla ${falla.codigo}: ${falla.titulo}", falla.accionSugerida)
        }
        is AvisoSesion.RiegoTerminado -> when {
            !aviso.completado -> TextoAviso(
                CanalAviso.RIEGO,
                "Riego interrumpido",
                "El riego no terminó. Abre FertiTrack para revisar el robot.",
            )
            else -> TextoAviso(
                CanalAviso.RIEGO,
                "Riego completado",
                buildString {
                    append(if (aviso.zonas.isEmpty()) "El robot terminó el riego." else "Se ${regaron(aviso.zonas)}.")
                    if (aviso.sugerirFertilizar) append(" ¿Fertilizar ahora? Abre FertiTrack para confirmarlo.")
                },
            )
        }
        is AvisoSesion.ZonasSecas -> TextoAviso(
            CanalAviso.RIEGO,
            if (aviso.zonas.size == 1) "Zona ${aviso.zonas.first()} seca" else "${aviso.zonas.size} zonas secas",
            "El sensado encontró ${zonasTexto(aviso.zonas)} por debajo del umbral. Abre FertiTrack para regar.",
        )
        AvisoSesion.ConexionPerdida -> TextoAviso(
            CanalAviso.CONEXION,
            "Sin conexión con el robot",
            "No llegan datos desde hace más de $ESPERA_CORTE_SEG s. Revisa el Wi-Fi ${RobotConfig.SSID_AP} " +
                "y que el Maestro esté encendido.",
        )
        AvisoSesion.ConexionRecuperada -> null
    }

    /** Línea de la notificación fija de la vigilancia. */
    fun estadoVigilancia(robot: EstadoRobot?, conexion: EstadoConexion): String = when {
        conexion is EstadoConexion.Conectando || conexion is EstadoConexion.Pausado -> "Conectando con el robot…"
        conexion !is EstadoConexion.Conectado -> "Sin respuesta del robot"
        robot == null -> "Conectando con el robot…"
        else -> Traductor.describirEstado(robot).titulo
    }

    private fun regaron(zonas: List<Int>): String =
        if (zonas.size == 1) "regó la zona ${zonas.first()}" else "regaron las zonas ${lista(zonas)}"

    private fun zonasTexto(zonas: List<Int>): String =
        if (zonas.size == 1) "la zona ${zonas.first()}" else "las zonas ${lista(zonas)}"

    private fun lista(zonas: List<Int>): String =
        if (zonas.size == 1) zonas.first().toString() else zonas.dropLast(1).joinToString(", ") + " y " + zonas.last()
}
