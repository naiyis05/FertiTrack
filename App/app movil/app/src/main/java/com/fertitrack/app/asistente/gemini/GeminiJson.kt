package com.fertitrack.app.asistente.gemini

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Cuerpo de `models/{modelo}:generateContent` y lectura de su respuesta, sin red. Con
 * [peticion] `conBusqueda`, la búsqueda en Google va activada y el modelo decide si la usa.
 */
object GeminiJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Motivos de corte que significan "no quiso responder". */
    private val bloqueos = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

    fun peticion(historial: List<TurnoChat>, contexto: String, sistema: String, conBusqueda: Boolean = true): String {
        val ultimaPregunta = historial.indexOfLast { it.deUsuario }
        val instrucciones = if (conBusqueda) sistema else "$sistema\n$SIN_BUSQUEDA"
        return buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", instrucciones) } } }
            putJsonArray("contents") {
                historial.forEachIndexed { i, turno ->
                    addJsonObject {
                        put("role", if (turno.deUsuario) "user" else "model")
                        val partes = turno.partesModelo
                        if (!turno.deUsuario && partes != null) {
                            put("parts", partes)
                        } else {
                            val texto = if (i == ultimaPregunta) conContexto(turno.texto, contexto) else turno.texto
                            putJsonArray("parts") { addJsonObject { put("text", texto) } }
                        }
                    }
                }
            }
            if (conBusqueda) putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
        }.toString()
    }

    /** Se añade a las instrucciones cuando la búsqueda no está disponible (p. ej., plan gratuito). */
    const val SIN_BUSQUEDA =
        "Ahora no tienes búsqueda en internet: responde con lo que sabes y, si un dato puede haber cambiado (clima, precios, noticias), dilo."

    private fun conContexto(pregunta: String, contexto: String) =
        "Contexto de FertiTrack (úsalo, no lo recites):\n$contexto\n\nPregunta del usuario:\n$pregunta"

    fun interpretar(codigo: Int, cuerpo: String): RespuestaCerebro {
        val raiz = try {
            json.parseToJsonElement(cuerpo) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        if (codigo !in 200..299) return falloHttp(codigo, raiz)
        raiz ?: return RespuestaCerebro.Fallo(TipoFallo.RESPUESTA_RARA, "La respuesta no es JSON")

        raiz.objeto("promptFeedback")?.texto("blockReason")?.let { return RespuestaCerebro.Fallo(TipoFallo.BLOQUEADA, it) }
        val candidato = raiz.arreglo("candidates")?.firstOrNull() as? JsonObject
            ?: return RespuestaCerebro.Fallo(TipoFallo.RESPUESTA_RARA, "Gemini no devolvió ninguna respuesta")
        val fin = candidato.texto("finishReason")
        if (fin in bloqueos) return RespuestaCerebro.Fallo(TipoFallo.BLOQUEADA, fin)

        val partes = candidato.objeto("content")?.arreglo("parts") ?: JsonArray(emptyList())
        val texto = partes.mapNotNull { it as? JsonObject }
            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .mapNotNull { it.texto("text") }
            .joinToString("")
            .trim()
        if (texto.isEmpty()) return RespuestaCerebro.Fallo(TipoFallo.RESPUESTA_RARA, "Respuesta vacía (fin: $fin)")

        val meta = candidato.objeto("groundingMetadata")
        val fuentes = meta?.arreglo("groundingChunks").orEmpty()
            .mapNotNull { (it as? JsonObject)?.objeto("web") }
            .mapNotNull { web -> web.texto("uri")?.let { url -> Fuente(web.texto("title") ?: url, url) } }
            .distinctBy { it.url }
        val sugerencias = meta?.objeto("searchEntryPoint")?.texto("renderedContent")?.takeIf { it.isNotBlank() }
        return RespuestaCerebro.Ok(texto, fuentes, sugerencias, partes)
    }

    private fun falloHttp(codigo: Int, raiz: JsonObject?): RespuestaCerebro.Fallo {
        val error = raiz?.objeto("error")
        val mensaje = error?.texto("message")
        val claveInvalida = error?.toString()?.contains("API_KEY_INVALID") == true ||
            mensaje?.contains("API key", ignoreCase = true) == true
        val tipo = when {
            codigo == 400 && claveInvalida -> TipoFallo.CLAVE_INVALIDA
            codigo == 400 -> TipoFallo.PETICION_RECHAZADA
            codigo == 401 || codigo == 403 -> TipoFallo.CLAVE_INVALIDA
            codigo == 404 -> TipoFallo.MODELO_NO_EXISTE
            codigo == 429 -> TipoFallo.CUOTA
            codigo >= 500 -> TipoFallo.SERVIDOR
            else -> TipoFallo.RESPUESTA_RARA
        }
        return RespuestaCerebro.Fallo(tipo, mensaje ?: "HTTP $codigo")
    }

    /** Fallo de red: no llegar a Google es "sin internet"; llegar y no recibir respuesta, del servidor. */
    fun falloDe(e: IOException): RespuestaCerebro.Fallo {
        val tipo = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> TipoFallo.SIN_INTERNET
            is SocketTimeoutException ->
                if (e.message?.contains("connect", ignoreCase = true) == true) TipoFallo.SIN_INTERNET else TipoFallo.SERVIDOR
            is InterruptedIOException -> TipoFallo.SERVIDOR
            else -> TipoFallo.SIN_INTERNET
        }
        return RespuestaCerebro.Fallo(tipo, e.message)
    }

    private fun JsonObject.objeto(clave: String) = this[clave] as? JsonObject
    private fun JsonObject.arreglo(clave: String) = this[clave] as? JsonArray
    private fun JsonObject.texto(clave: String) = (this[clave] as? JsonPrimitive)?.contentOrNull
}
