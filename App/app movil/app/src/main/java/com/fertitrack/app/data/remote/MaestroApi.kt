package com.fertitrack.app.data.remote

import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ResultadoComando
import javax.net.SocketFactory

/**
 * Cliente HTTP del Maestro (ESP32). Solo GET, una ruta por orden, tal como las registra
 * configurarServidor() del firmware v6 (sin las rutas nuevas de velocidades, actuadores, rueda
 * y prueba de brazo, que solo usa la página web del Maestro).
 *
 * No aplica reglas de reintento ni de turno: eso lo hace el repositorio. Nunca lanza
 * excepciones por fallos de red ni por respuestas raras; solo propaga la cancelación de la
 * corrutina que lo llama (y en ese caso cancela la llamada HTTP en curso).
 */
interface MaestroApi {

    /** Cambia a dónde van las próximas peticiones. No afecta a la que ya esté en curso. */
    fun fijarDestino(destino: DestinoMaestro)

    /** GET /api/estado. */
    suspend fun consultarEstado(): ConsultaEstado

    /** Envía [orden] UNA sola vez. */
    suspend fun enviar(orden: OrdenMaestro): ResultadoComando
}

/** A dónde salen las peticiones: el robot (por la red Wi-Fi elegida) o el simulador local. */
data class DestinoMaestro(
    /** Por ejemplo "http://192.168.4.1" o "http://127.0.0.1:40123", sin barra final. */
    val urlBase: String,
    /** Sockets ligados a la red Wi-Fi del robot (Network.getSocketFactory); null = ruta por defecto. */
    val fabricaSockets: SocketFactory? = null,
)

/** Resultado de una consulta a /api/estado. */
sealed interface ConsultaEstado {
    data class Correcta(val estado: EstadoRobot) : ConsultaEstado

    /** Sin respuesta (red, timeout, cancelación ajena) o respuesta que no es un estado válido. */
    data class Fallida(val detalle: String) : ConsultaEstado
}
