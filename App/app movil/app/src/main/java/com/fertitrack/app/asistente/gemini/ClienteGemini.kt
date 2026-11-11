package com.fertitrack.app.asistente.gemini

import com.fertitrack.app.asistente.PromptSistema
import com.fertitrack.app.config.AsistenteConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Gemini por REST. Sale por la red por defecto del teléfono (datos móviles u otro Wi-Fi):
 * nunca por la del robot, que no da internet. Sin reintentos: la persona vuelve a preguntar.
 *
 * [hayInternet] se consulta antes de cada pregunta: unido solo al AP del robot, el DNS no
 * contesta y la llamada tardaría hasta el timeout en fallar.
 */
class ClienteGemini(
    private val clave: String,
    private val modelo: String,
    private val urlBase: String = AsistenteConfig.URL_BASE,
    private val http: OkHttpClient = clientePorDefecto(),
    private val sistema: String = PromptSistema.TEXTO,
    private val hayInternet: () -> Boolean = { true },
) : Cerebro {

    override suspend fun responder(historial: List<TurnoChat>, contexto: String): RespuestaCerebro {
        if (clave.isBlank()) return RespuestaCerebro.Fallo(TipoFallo.SIN_CLAVE)
        if (!hayInternet()) return RespuestaCerebro.Fallo(TipoFallo.SIN_INTERNET, "La red por defecto no tiene internet")
        val url = "$urlBase/models/$modelo:generateContent".toHttpUrlOrNull()
            ?: return RespuestaCerebro.Fallo(TipoFallo.RESPUESTA_RARA, "Dirección de Gemini no válida")
        val peticion = Request.Builder()
            .url(url)
            .header("x-goog-api-key", clave)
            .post(GeminiJson.peticion(historial, contexto, sistema).toRequestBody(TIPO_JSON))
            .build()
        val (codigo, cuerpo) = try {
            http.newCall(peticion).ejecutar()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return GeminiJson.falloDe(e)
        }
        return GeminiJson.interpretar(codigo, cuerpo)
    }

    /** enqueue + suspensión: la cancelación de la corrutina llega a OkHttp como Call.cancel(). */
    private suspend fun Call.ejecutar(): Pair<Int, String> = suspendCancellableCoroutine { continuacion ->
        continuacion.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuacion.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val leida = try {
                    response.use { it.code to it.body.string() }
                } catch (e: IOException) {
                    continuacion.resumeWithException(e)
                    return
                }
                continuacion.resume(leida)
            }
        })
    }

    companion object {
        private val TIPO_JSON = "application/json; charset=utf-8".toMediaType()

        fun clientePorDefecto(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(AsistenteConfig.TIMEOUT_CONEXION_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AsistenteConfig.TIMEOUT_LECTURA_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(AsistenteConfig.TIMEOUT_CONEXION_MS, TimeUnit.MILLISECONDS)
            .callTimeout(AsistenteConfig.TIMEOUT_CONEXION_MS + AsistenteConfig.TIMEOUT_LECTURA_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
