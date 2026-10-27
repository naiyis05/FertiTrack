package com.fertitrack.app.data.remote

import com.fertitrack.app.data.FixturesMaestro
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ResultadoComando
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ClienteMaestroHttp contra un servidor HTTP mínimo en 127.0.0.1 (respuestas programadas). */
class ClienteMaestroHttpTest {

    /** Lo que contesta el servidor: null = acepta y no contesta (timeout). */
    private class Respuesta(val codigo: Int, val cuerpo: String)

    private lateinit var servidor: ServerSocket
    private lateinit var hilo: Thread

    @Volatile
    private var programada: Respuesta? = Respuesta(200, """{"ok":true}""")
    private val peticiones = CopyOnWriteArrayList<String>()
    private val abiertas = CopyOnWriteArrayList<Socket>()
    private val conexionCerrada = CountDownLatch(1)

    private lateinit var cliente: ClienteMaestroHttp

    @Before
    fun arrancar() {
        servidor = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        hilo = Thread {
            while (!servidor.isClosed) {
                val s = try {
                    servidor.accept()
                } catch (e: IOException) {
                    break
                }
                atender(s)
            }
        }.apply { isDaemon = true; start() }
        cliente = ClienteMaestroHttp(DestinoMaestro("http://127.0.0.1:${servidor.localPort}"), timeoutMs = 2_000)
    }

    @After
    fun parar() {
        servidor.close()
        abiertas.forEach { it.close() }
    }

    private fun atender(s: Socket) {
        val entrada = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val cabecera = generateSequence { entrada.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        peticiones += cabecera.joinToString("\n")
        val r = programada
        if (r == null) {
            abiertas += s
            // Espera a que el cliente cierre (timeout o Call.cancel()).
            Thread {
                try {
                    if (s.getInputStream().read() < 0) conexionCerrada.countDown()
                } catch (e: IOException) {
                    conexionCerrada.countDown()
                }
            }.apply { isDaemon = true; start() }
            return
        }
        val cuerpo = r.cuerpo.toByteArray()
        val salida = s.getOutputStream()
        salida.write(
            ("HTTP/1.1 ${r.codigo} X\r\nContent-Type: application/json\r\nContent-Length: ${cuerpo.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        salida.write(cuerpo)
        salida.flush()
        s.close()
    }

    @Test
    fun exito200ConDatosYPeticionComoElFirmware() = runBlocking {
        programada = Respuesta(200, """{"ok":true,"num_zonas":6,"ancho_zona_real_cm":50,"distancia_total_pasos":30000}""")
        val r = cliente.enviar(OrdenMaestro.Configurar(300f, 2.5f))
        assertEquals(
            ResultadoComando.Exito(null, mapOf("num_zonas" to "6", "ancho_zona_real_cm" to "50", "distancia_total_pasos" to "30000")),
            r,
        )
        val peticion = peticiones.single()
        assertTrue(peticion, peticion.startsWith("GET /api/configurar?distancia_cm=300&ancho_zona_cm=2.5 HTTP/1.1"))
        assertTrue(peticion, peticion.lines().any { it.equals("Connection: close", ignoreCase = true) })
    }

    @Test
    fun codigosDeError() = runBlocking {
        programada = Respuesta(409, """{"ok":false,"error":"sistema ocupado"}""")
        assertEquals(ResultadoComando.Ocupado("sistema ocupado"), cliente.enviar(OrdenMaestro.PruebaSensor))
        programada = Respuesta(400, """{"ok":false,"error":"cm debe estar entre 1 y 150"}""")
        assertEquals(
            ResultadoComando.Rechazado("cm debe estar entre 1 y 150"),
            cliente.enviar(OrdenMaestro.PruebaMovimiento(200f)),
        )
        programada = Respuesta(404, """{"error":"ruta no encontrada"}""")
        assertEquals(ResultadoComando.ErrorRobot(404, "ruta no encontrada"), cliente.enviar(OrdenMaestro.Reiniciar))
        programada = Respuesta(500, """{"ok":false,"error":"no se pudo calcular zonas"}""")
        assertEquals(
            ResultadoComando.ErrorRobot(500, "no se pudo calcular zonas"),
            cliente.enviar(OrdenMaestro.Configurar(300f)),
        )
    }

    @Test
    fun cuerpoNoJsonEsErrorRobot() = runBlocking {
        programada = Respuesta(200, "<html>hola</html>")
        val r = cliente.enviar(OrdenMaestro.Detener)
        assertTrue(r.toString(), r is ResultadoComando.ErrorRobot && r.codigoHttp == 200)
    }

    @Test
    fun timeoutEsSinRespuesta() = runBlocking {
        programada = null
        val inicio = System.nanoTime()
        val r = cliente.enviar(OrdenMaestro.Iniciar(com.fertitrack.app.domain.modelo.ModoInicio.CICLO))
        val ms = (System.nanoTime() - inicio) / 1_000_000
        assertTrue(r.toString(), r is ResultadoComando.SinRespuesta)
        assertTrue("tardó $ms ms", ms in 400..3_000)
        // Sin reintentos de OkHttp: una sola petición.
        assertEquals(1, peticiones.size)
        val e = cliente.consultarEstado()
        assertTrue(e.toString(), e is ConsultaEstado.Fallida)
    }

    @Test
    fun conexionRechazadaEsSinRespuesta() = runBlocking {
        val libre = ServerSocket(0).use { it.localPort } // puerto cerrado
        cliente.fijarDestino(DestinoMaestro("http://127.0.0.1:$libre"))
        val r = cliente.enviar(OrdenMaestro.Detener)
        assertTrue(r.toString(), r is ResultadoComando.SinRespuesta)
    }

    @Test
    fun consultaDeEstado() = runBlocking {
        programada = Respuesta(200, FixturesMaestro.ESTADO_SENSANDO)
        val r = cliente.consultarEstado()
        assertTrue(r.toString(), r is ConsultaEstado.Correcta)
        assertEquals(EstadoMaquina.SENSADO_MIDIENDO, (r as ConsultaEstado.Correcta).estado.estado)
        assertTrue(peticiones.single().startsWith("GET /api/estado HTTP/1.1"))

        programada = Respuesta(200, "{roto")
        assertTrue(cliente.consultarEstado() is ConsultaEstado.Fallida)
    }

    @Test
    fun cancelarLaCorrutinaCierraLaConexion() = runBlocking {
        programada = null
        val consulta = async { cliente.consultarEstado() }
        // Espera a que la petición llegue al servidor y cancela.
        withTimeout(2_000) { while (peticiones.isEmpty()) kotlinx.coroutines.delay(10) }
        consulta.cancel()
        assertTrue("el servidor no vio cerrarse la conexión", conexionCerrada.await(400, TimeUnit.MILLISECONDS))
    }
}
