package com.fertitrack.app.simulador

import com.fertitrack.app.data.remote.ClienteMaestroHttp
import com.fertitrack.app.data.remote.ConsultaEstado
import com.fertitrack.app.data.remote.DestinoMaestro
import com.fertitrack.app.data.remote.OrdenMaestro
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.modelo.TipoFalla
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FakeFertiTrackServer de punta a punta con el cliente real (OkHttp) y un reloj falso. */
class FakeFertiTrackServerTest {

    private val reloj = AtomicLong(0)
    private lateinit var servidor: FakeFertiTrackServer
    private lateinit var cliente: ClienteMaestroHttp

    @Before
    fun arrancar() {
        val maquina = MaquinaRobotSimulada(TiemposSimulados.todos(PASO_MS), semilla = 2026L, reloj = reloj::get)
        servidor = FakeFertiTrackServer(maquina, reloj::get)
        val url = servidor.iniciar()
        assertTrue(url, url.startsWith("http://127.0.0.1:"))
        assertEquals(url, servidor.iniciar()) // idempotente
        cliente = ClienteMaestroHttp(DestinoMaestro(url), timeoutMs = TIMEOUT_MS)
    }

    @After
    fun parar() {
        servidor.detener()
    }

    private suspend fun estado(): EstadoRobot {
        val r = cliente.consultarEstado()
        assertTrue(r.toString(), r is ConsultaEstado.Correcta)
        return (r as ConsultaEstado.Correcta).estado
    }

    /** Avanza el reloj falso hasta ESPERANDO, consultando por HTTP; devuelve los estados vistos. */
    private suspend fun hastaEsperando(): List<EstadoMaquina> {
        val vistos = mutableListOf<EstadoMaquina>()
        repeat(2_000) {
            val e = estado().estado
            if (vistos.lastOrNull() != e) vistos += e
            if (e == EstadoMaquina.ESPERANDO && vistos.size > 1) return vistos
            reloj.addAndGet(PASO_MS / 2)
        }
        error("No volvió a ESPERANDO: $vistos")
    }

    @Test
    fun cicloCompletoConRiegoYOcupado() = runBlocking {
        assertEquals(EstadoMaquina.ARRANQUE, estado().estado)
        assertEquals(ResultadoComando.Ocupado("sistema ocupado"), cliente.enviar(OrdenMaestro.Iniciar(ModoInicio.CICLO)))
        hastaEsperando()

        assertEquals(ResultadoComando.Exito(), cliente.enviar(OrdenMaestro.Iniciar(ModoInicio.CICLO)))
        assertEquals(ResultadoComando.Ocupado("sistema ocupado"), cliente.enviar(OrdenMaestro.Iniciar(ModoInicio.FERTILIZAR)))
        assertEquals(ResultadoComando.Ocupado("sistema ocupado"), cliente.enviar(OrdenMaestro.Configurar(200f)))
        val vistos = hastaEsperando()
        listOf(
            EstadoMaquina.SENSADO_AVANZANDO, EstadoMaquina.SENSADO_MIDIENDO, EstadoMaquina.SENSADO_RETORNANDO,
            EstadoMaquina.RIEGO_BAJANDO_BRAZO, EstadoMaquina.RIEGO_AVANZANDO, EstadoMaquina.RIEGO_RETORNANDO,
        ).forEach { assertTrue("faltó $it en $vistos", it in vistos) }
        assertTrue(estado().listoParaRutinas)
    }

    @Test
    fun detenerDeclaraFalla8YReiniciarVuelveAEspera() = runBlocking {
        hastaEsperando()
        cliente.enviar(OrdenMaestro.Iniciar(ModoInicio.FERTILIZAR))
        reloj.addAndGet(PASO_MS)
        assertEquals(ResultadoComando.Exito(), cliente.enviar(OrdenMaestro.Detener))
        val parado = estado()
        assertEquals(EstadoMaquina.EMERGENCIA, parado.estado)
        assertEquals(TipoFalla.PARADA_DESDE_APP, parado.falla)

        assertEquals(ResultadoComando.Exito(), cliente.enviar(OrdenMaestro.Reiniciar))
        val vistos = hastaEsperando()
        assertEquals(EstadoMaquina.RECUP_ESPERA, vistos.first())
        val fin = estado()
        assertEquals(EstadoMaquina.ESPERANDO, fin.estado)
        assertEquals(TipoFalla.SIN_FALLA, fin.falla)
    }

    @Test
    fun configurarDevuelveLosDatosDelFirmware() = runBlocking {
        hastaEsperando()
        assertEquals(
            ResultadoComando.Exito(
                null,
                mapOf("num_zonas" to "8", "ancho_zona_real_cm" to "25", "distancia_total_pasos" to "20000"),
            ),
            cliente.enviar(OrdenMaestro.Configurar(200f, 25f)),
        )
        assertEquals(ResultadoComando.Rechazado("distancia_cm debe ser > 0"), cliente.enviar(OrdenMaestro.Configurar(0f)))
        assertEquals(8, estado().totalZonas)
    }

    @Test
    fun perdidaDeConexionProvocaTimeouts() = runBlocking {
        hastaEsperando()
        servidor.simularPerdidaConexion(2)
        val inicio = System.nanoTime()
        val r = cliente.consultarEstado()
        val ms = (System.nanoTime() - inicio) / 1_000_000
        assertTrue(r.toString(), r is ConsultaEstado.Fallida)
        assertTrue("tardó $ms ms", ms >= TIMEOUT_MS - 50)
        val orden = cliente.enviar(OrdenMaestro.Detener)
        assertTrue(orden.toString(), orden is ResultadoComando.SinRespuesta)

        reloj.addAndGet(2_000) // termina la pérdida
        val vuelta = cliente.consultarEstado()
        assertTrue(vuelta.toString(), vuelta is ConsultaEstado.Correcta)
        // La orden enviada durante la pérdida nunca llegó al robot.
        assertEquals(EstadoMaquina.ESPERANDO, (vuelta as ConsultaEstado.Correcta).estado.estado)
    }

    @Test
    fun fallaInyectada() = runBlocking {
        hastaEsperando()
        servidor.inyectarFalla(2)
        val e = estado()
        assertEquals(EstadoMaquina.EMERGENCIA, e.estado)
        assertEquals(TipoFalla.ESP_B_NO_RESPONDE, e.falla)
    }

    @Test
    fun simuladorDebugArrancaYSeDetiene() = runBlocking {
        val simulador = SimuladorDebug()
        assertTrue(simulador.disponible)
        val url = simulador.iniciar()
        try {
            assertEquals(url, simulador.iniciar())
            val c = ClienteMaestroHttp(DestinoMaestro(url), timeoutMs = 1_000)
            val r = c.consultarEstado()
            assertTrue(r.toString(), r is ConsultaEstado.Correcta)
            assertEquals(EstadoMaquina.ARRANQUE, (r as ConsultaEstado.Correcta).estado.estado)
            simulador.detener()
            assertTrue(c.consultarEstado() is ConsultaEstado.Fallida)
            val otra = simulador.iniciar()
            assertTrue(otra, otra.startsWith("http://127.0.0.1:"))
        } finally {
            simulador.detener()
        }
    }

    private companion object {
        const val PASO_MS = 100L
        const val TIMEOUT_MS = 400L
    }
}
