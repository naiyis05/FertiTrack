package com.fertitrack.app.asistente.gemini

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClienteGeminiTest {

    private val pregunta = listOf(TurnoChat(true, "hola"))

    @Test
    fun sinClaveNoLlamaALaRed() = runBlocking {
        val r = ClienteGemini("", "gemini-x", urlBase = "http://127.0.0.1:1").responder(pregunta, "contexto")
        assertEquals(TipoFallo.SIN_CLAVE, (r as RespuestaCerebro.Fallo).tipo)
    }

    @Test
    fun sinRedConInternetAvisaSinLlamar() = runBlocking {
        // Unido solo al AP del robot, el DNS de Google no contesta: no hay que esperar a que venza.
        var llamadas = 0
        val http = OkHttpClient.Builder().addInterceptor { cadena ->
            llamadas++
            cadena.proceed(cadena.request())
        }.build()
        val r = ClienteGemini("clave", "gemini-x", urlBase = "http://127.0.0.1:1", http = http, hayInternet = { false })
            .responder(pregunta, "contexto")
        assertEquals(TipoFallo.SIN_INTERNET, (r as RespuestaCerebro.Fallo).tipo)
        assertEquals(0, llamadas)
    }

    @Test
    fun puertoCerradoEsSinInternet() = runBlocking {
        val inicio = System.nanoTime()
        val r = ClienteGemini("clave", "gemini-x", urlBase = "http://127.0.0.1:1").responder(pregunta, "contexto")
        assertEquals(TipoFallo.SIN_INTERNET, (r as RespuestaCerebro.Fallo).tipo)
        assertTrue((System.nanoTime() - inicio) / 1_000_000 < 5_000)
    }

    @Test
    fun enviaClaveEnCabeceraYModeloEnLaRuta() = runBlocking {
        val servidor = ServidorUnaRespuesta(
            """{"candidates":[{"content":{"parts":[{"text":"Hola, soy el asistente."}]},"finishReason":"STOP"}]}""",
        )
        val r = ClienteGemini("clave-secreta", "gemini-x", urlBase = "http://127.0.0.1:${servidor.puerto}/v1beta")
            .responder(pregunta, "contexto")
        servidor.esperar()

        assertTrue(r.toString(), r is RespuestaCerebro.Ok)
        assertEquals("Hola, soy el asistente.", (r as RespuestaCerebro.Ok).texto)
        assertEquals("POST /v1beta/models/gemini-x:generateContent HTTP/1.1", servidor.lineaPeticion)
        assertEquals("clave-secreta", servidor.cabeceras["x-goog-api-key"])
        assertTrue(servidor.cuerpo, servidor.cuerpo.contains("\"google_search\""))
    }
}

/** Servidor HTTP mínimo: atiende una sola petición, guarda lo que llegó y responde [respuesta]. */
private class ServidorUnaRespuesta(respuesta: String) {
    private val socket = ServerSocket(0)
    val puerto: Int = socket.localPort
    var lineaPeticion = ""
    val cabeceras = mutableMapOf<String, String>()
    var cuerpo = ""

    private val hilo = thread {
        socket.use { s ->
            s.accept().use { conexion ->
                // Por bytes: Content-Length cuenta bytes y el prompt lleva tildes.
                val entrada = conexion.getInputStream()
                val cabecera = StringBuilder()
                while (!cabecera.endsWith("\r\n\r\n")) {
                    val b = entrada.read()
                    if (b < 0) break
                    cabecera.append(b.toChar())
                }
                val lineas = cabecera.toString().split("\r\n").filter { it.isNotEmpty() }
                lineaPeticion = lineas.first()
                lineas.drop(1).forEach { linea ->
                    val (nombre, valor) = linea.split(":", limit = 2)
                    cabeceras[nombre.trim().lowercase()] = valor.trim()
                }
                val largo = cabeceras["content-length"]?.toInt() ?: 0
                cuerpo = String(entrada.readNBytes(largo), Charsets.UTF_8)
                val bytes = respuesta.toByteArray(Charsets.UTF_8)
                conexion.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    fun esperar() = hilo.join(5_000)
}
