package com.fertitrack.app.data.remote

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.modelo.ResultadoComando
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [MaestroApi] con OkHttp, pensado para el WebServer síncrono del ESP32:
 * - connect/read/write/call timeout = [timeoutMs] (3 s);
 * - sin reintentos de OkHttp (retryOnConnectionFailure = false), sin redirecciones, sin caché;
 * - "Connection: close" y pool vacío: el ESP32 cierra tras cada respuesta;
 * - la [DestinoMaestro.fabricaSockets] hace que los sockets salgan por la red Wi-Fi del robot.
 *
 * Cancelar la corrutina que llama cancela la llamada HTTP (Call.cancel()).
 */
class ClienteMaestroHttp(
    destinoInicial: DestinoMaestro = DestinoMaestro(RobotConfig.URL_BASE_ROBOT),
    private val timeoutMs: Long = RobotConfig.TIMEOUT_MS,
) : MaestroApi {

    @Volatile
    private var destino: DestinoMaestro = destinoInicial

    private val clienteBase: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /** Último cliente construido y la fábrica de sockets con la que se construyó. */
    @Volatile
    private var clienteEnUso: Pair<SocketFactory?, OkHttpClient> = null to clienteBase

    override fun fijarDestino(destino: DestinoMaestro) {
        this.destino = destino
    }

    override suspend fun consultarEstado(): ConsultaEstado {
        val respuesta = try {
            obtener(RUTA_ESTADO, emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ConsultaEstado.Fallida(describirFallo(e))
        } catch (e: RuntimeException) {
            return ConsultaEstado.Fallida("Error inesperado al consultar el robot (${e.javaClass.simpleName})")
        }
        return ParserMaestro.interpretarEstado(respuesta.codigo, respuesta.cuerpo)
    }

    override suspend fun enviar(orden: OrdenMaestro): ResultadoComando {
        orden.problemaParametros()?.let { return ResultadoComando.Rechazado(it) }
        val respuesta = try {
            obtener(orden.ruta, orden.parametros)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ResultadoComando.SinRespuesta(describirFallo(e))
        } catch (e: RuntimeException) {
            return ResultadoComando.SinRespuesta("Error inesperado al enviar la orden (${e.javaClass.simpleName})")
        }
        return ParserMaestro.interpretarOrden(respuesta.codigo, respuesta.cuerpo)
    }

    private suspend fun obtener(ruta: String, parametros: List<Pair<String, String>>): RespuestaHttp {
        val destino = destino
        val url = construirUrl(destino.urlBase, ruta, parametros)
            ?: throw IOException("Dirección del robot no válida: ${destino.urlBase}")
        val peticion = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
            .build()
        return clientePara(destino.fabricaSockets).newCall(peticion).ejecutar()
    }

    private fun clientePara(fabrica: SocketFactory?): OkHttpClient {
        val actual = clienteEnUso
        if (actual.first === fabrica) return actual.second
        // newBuilder() comparte dispatcher y pool; solo cambia por dónde salen los sockets.
        val nuevo = if (fabrica == null) clienteBase else clienteBase.newBuilder().socketFactory(fabrica).build()
        clienteEnUso = fabrica to nuevo
        return nuevo
    }

    /** enqueue + suspensión: la cancelación de la corrutina llega a OkHttp como Call.cancel(). */
    private suspend fun Call.ejecutar(): RespuestaHttp = suspendCancellableCoroutine { continuacion ->
        continuacion.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuacion.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val leida = try {
                    response.use { RespuestaHttp(it.code, leerCuerpo(it)) }
                } catch (e: IOException) {
                    continuacion.resumeWithException(e)
                    return
                }
                continuacion.resume(leida)
            }
        })
    }

    private fun describirFallo(e: IOException): String = when (e) {
        is SocketTimeoutException -> sinRespuestaATiempo()
        is InterruptedIOException ->
            if (e.message == "timeout") sinRespuestaATiempo() else "Petición interrumpida"
        is NoRouteToHostException -> "No hay ruta hacia el robot: ¿el teléfono está en su red Wi-Fi?"
        is ConnectException -> "No se pudo conectar con el robot (${e.message ?: "conexión rechazada"})"
        is UnknownHostException -> "Dirección del robot desconocida (${e.message})"
        is SocketException -> "Falló la red hacia el robot (${e.message ?: "socket cerrado"})"
        else ->
            if (e.message.equals("Canceled", ignoreCase = true)) "Petición cancelada" else "Error de red: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun sinRespuestaATiempo(): String {
        val segundos = if (timeoutMs % 1000 == 0L) "${timeoutMs / 1000}" else "%.1f".format(timeoutMs / 1000.0)
        return "El robot no respondió a tiempo ($segundos s)"
    }

    private class RespuestaHttp(val codigo: Int, val cuerpo: String)

    internal companion object {
        const val RUTA_ESTADO = "api/estado"

        /** Las respuestas del Maestro ocupan unos pocos KB; más que esto no es el robot. */
        private const val LIMITE_CUERPO_BYTES = 64L * 1024

        fun construirUrl(urlBase: String, ruta: String, parametros: List<Pair<String, String>>): HttpUrl? {
            val base = urlBase.toHttpUrlOrNull() ?: return null
            val constructor = base.newBuilder().addPathSegments(ruta)
            parametros.forEach { (nombre, valor) -> constructor.addQueryParameter(nombre, valor) }
            return constructor.build()
        }

        private fun leerCuerpo(respuesta: Response): String {
            val fuente = respuesta.body.source()
            fuente.request(LIMITE_CUERPO_BYTES) // lee hasta el límite o hasta el final
            val bytes = minOf(fuente.buffer.size, LIMITE_CUERPO_BYTES)
            return fuente.buffer.readUtf8(bytes)
        }
    }
}
