package com.fertitrack.app.asistente.gemini

import com.fertitrack.app.asistente.gemini.TipoFallo.BLOQUEADA
import com.fertitrack.app.asistente.gemini.TipoFallo.CLAVE_INVALIDA
import com.fertitrack.app.asistente.gemini.TipoFallo.CUOTA
import com.fertitrack.app.asistente.gemini.TipoFallo.MODELO_NO_EXISTE
import com.fertitrack.app.asistente.gemini.TipoFallo.RESPUESTA_RARA
import com.fertitrack.app.asistente.gemini.TipoFallo.SERVIDOR
import com.fertitrack.app.asistente.gemini.TipoFallo.SIN_INTERNET
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiJsonTest {

    private fun objeto(texto: String): JsonObject = Json.parseToJsonElement(texto).jsonObject

    private fun textoDe(contenido: JsonObject): String =
        contenido["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun peticionTieneSistemaHerramientaYContexto() {
        val historial = listOf(TurnoChat(true, "hola"), TurnoChat(false, "¡Hola!"), TurnoChat(true, "¿zonas secas?"))
        val p = objeto(GeminiJson.peticion(historial, "CTX-123", "SISTEMA"))

        assertEquals("SISTEMA", textoDe(p["systemInstruction"]!!.jsonObject))
        assertTrue(p["tools"]!!.jsonArray[0].jsonObject.containsKey("google_search"))
        val contenidos = p["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contenidos.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("hola", textoDe(contenidos[0]))
        assertEquals("¡Hola!", textoDe(contenidos[1]))
        val ultimo = textoDe(contenidos[2])
        assertTrue(ultimo, ultimo.contains("CTX-123"))
        assertTrue(ultimo, ultimo.contains("¿zonas secas?"))
        assertFalse(textoDe(contenidos[0]).contains("CTX-123"))
    }

    @Test
    fun peticionSinBusquedaNoLlevaHerramientaYLoAvisa() {
        val historial = listOf(TurnoChat(true, "hola"))
        val p = objeto(GeminiJson.peticion(historial, "C", "SISTEMA", conBusqueda = false))
        assertNull(p["tools"])
        val sistema = p["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(sistema.startsWith("SISTEMA"))
        assertTrue(sistema.endsWith(GeminiJson.SIN_BUSQUEDA))
    }

    @Test
    fun partesDelModeloSeReenvianTalCual() {
        val partes = Json.parseToJsonElement("""[{"text":"hola","thoughtSignature":"abc"}]""").jsonArray
        val historial = listOf(TurnoChat(true, "a"), TurnoChat(false, "hola", partes), TurnoChat(true, "b"))
        val p = objeto(GeminiJson.peticion(historial, "C", "S"))
        assertEquals(partes, p["contents"]!!.jsonArray[1].jsonObject["parts"])
    }

    @Test
    fun respuestaConTextoFuentesYSugerencias() {
        val cuerpo = """{"candidates":[{"content":{"role":"model","parts":[
            {"text":"Voy a buscar el clima.","thought":true},
            {"text":"Mañana llueve en Bogotá.","thoughtSignature":"xyz"}]},
            "finishReason":"STOP","groundingMetadata":{"webSearchQueries":["clima bogota mañana"],
            "searchEntryPoint":{"renderedContent":"<div>chips</div>"},
            "groundingChunks":[{"web":{"uri":"https://a.com/1","title":"a.com"}},{"web":{"uri":"https://b.com/2","title":"b.com"}},
            {"web":{"uri":"https://a.com/1","title":"a.com"}}]}}]}"""
        val r = GeminiJson.interpretar(200, cuerpo) as RespuestaCerebro.Ok
        assertEquals("Mañana llueve en Bogotá.", r.texto)
        assertEquals(listOf(Fuente("a.com", "https://a.com/1"), Fuente("b.com", "https://b.com/2")), r.fuentes)
        assertEquals("<div>chips</div>", r.sugerenciasHtml)
        assertEquals(2, r.partesModelo.size)
    }

    @Test
    fun respuestaSinBusquedaNoTraeFuentes() {
        val r = GeminiJson.interpretar(200, """{"candidates":[{"content":{"parts":[{"text":"Ve a Control."}]},"finishReason":"STOP"}]}""")
            as RespuestaCerebro.Ok
        assertEquals("Ve a Control.", r.texto)
        assertTrue(r.fuentes.isEmpty())
        assertNull(r.sugerenciasHtml)
    }

    @Test
    fun errores() {
        fun tipo(codigo: Int, cuerpo: String) = (GeminiJson.interpretar(codigo, cuerpo) as RespuestaCerebro.Fallo).tipo
        assertEquals(
            CLAVE_INVALIDA,
            tipo(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""),
        )
        assertEquals(CLAVE_INVALIDA, tipo(403, """{"error":{"code":403,"message":"Permission denied","status":"PERMISSION_DENIED"}}"""))
        assertEquals(MODELO_NO_EXISTE, tipo(404, """{"error":{"code":404,"message":"models/x is not found","status":"NOT_FOUND"}}"""))
        assertEquals(CUOTA, tipo(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals(SERVIDOR, tipo(503, "{}"))
        assertEquals(BLOQUEADA, tipo(200, """{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertEquals(BLOQUEADA, tipo(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(RESPUESTA_RARA, tipo(200, """{"candidates":[]}"""))
        assertEquals(RESPUESTA_RARA, tipo(200, """{"candidates":[{"content":{"parts":[{"text":"solo pienso","thought":true}]},"finishReason":"STOP"}]}"""))
        assertEquals(RESPUESTA_RARA, tipo(200, "<html>"))
        assertEquals(TipoFallo.PETICION_RECHAZADA, tipo(400, """{"error":{"code":400,"message":"Invalid JSON payload"}}"""))
    }

    @Test
    fun errorConservaElMensajeDeGoogle() {
        val r = GeminiJson.interpretar(404, """{"error":{"code":404,"message":"models/x is not found"}}""") as RespuestaCerebro.Fallo
        assertEquals("models/x is not found", r.detalle)
    }

    @Test
    fun falloDeExcepcionSinRed() {
        assertEquals(SIN_INTERNET, GeminiJson.falloDe(UnknownHostException("generativelanguage.googleapis.com")).tipo)
        assertEquals(SIN_INTERNET, GeminiJson.falloDe(ConnectException("Connection refused")).tipo)
        assertEquals(SIN_INTERNET, GeminiJson.falloDe(NoRouteToHostException("No route")).tipo)
        assertEquals(SIN_INTERNET, GeminiJson.falloDe(SocketTimeoutException("failed to connect to /142.250.0.1 (port 443) after 10000ms")).tipo)
        assertEquals(SERVIDOR, GeminiJson.falloDe(SocketTimeoutException("timeout")).tipo)
        assertEquals(SERVIDOR, GeminiJson.falloDe(InterruptedIOException("timeout")).tipo)
    }
}
