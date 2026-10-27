package com.fertitrack.app.data

import com.fertitrack.app.data.red.EnlaceRed
import com.fertitrack.app.data.remote.ConsultaEstado
import com.fertitrack.app.data.remote.DestinoMaestro
import com.fertitrack.app.data.remote.MaestroApi
import com.fertitrack.app.data.remote.OrdenMaestro
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.simulador.Simulador
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotRepositoryImplTest {

    /** MaestroApi falso: registra cada llamada con su instante virtual. */
    private class ApiFalsa(private val ahora: () -> Long) : MaestroApi {
        val llamadas = mutableListOf<Pair<Long, String>>()
        var destino: DestinoMaestro? = null
        var enVuelo = 0
        var maxEnVuelo = 0
        var consultasCanceladas = 0
        var estado: suspend () -> ConsultaEstado = { ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO)) }
        var orden: suspend (OrdenMaestro) -> ResultadoComando = { ResultadoComando.Exito() }

        override fun fijarDestino(destino: DestinoMaestro) {
            this.destino = destino
        }

        override suspend fun consultarEstado(): ConsultaEstado = medir("estado") {
            try {
                estado()
            } catch (e: CancellationException) {
                consultasCanceladas++
                throw e
            }
        }

        override suspend fun enviar(orden: OrdenMaestro): ResultadoComando = medir(orden.ruta) { this.orden(orden) }

        private suspend fun <T> medir(nombre: String, bloque: suspend () -> T): T {
            llamadas += ahora() to nombre
            enVuelo++
            maxEnVuelo = maxOf(maxEnVuelo, enVuelo)
            try {
                return bloque()
            } finally {
                enVuelo--
            }
        }

        fun instantes(nombre: String) = llamadas.filter { it.second == nombre }.map { it.first }
    }

    private class EnlaceFalso : EnlaceRed {
        override val estado = MutableStateFlow<EstadoRed>(EstadoRed.Disponible(ModoEnlace.MANUAL))
        override val fabricaSockets = MutableStateFlow<SocketFactory?>(null)
        var conexionesAutomaticas = 0
        var reevaluaciones = 0
        override fun activar() = Unit
        override fun conectarAutomatico() {
            conexionesAutomaticas++
        }
        override fun reevaluar() {
            reevaluaciones++
        }
        override fun liberar() = Unit
    }

    private class SimuladorFalso(override val disponible: Boolean) : Simulador {
        var activo = false
        override fun iniciar(): String {
            activo = true
            return "http://127.0.0.1:4567"
        }
        override fun detener() {
            activo = false
        }
        override fun inyectarFalla(codigo: Int) = Unit
        override fun simularPerdidaConexion(segundos: Int) = Unit
    }

    private class Escenario(prueba: TestScope, simuladorDisponible: Boolean = true) {
        val api = ApiFalsa { prueba.currentTime }
        val enlace = EnlaceFalso()
        val simulador = SimuladorFalso(simuladorDisponible)
        val repo = RobotRepositoryImpl(
            api = api,
            enlace = enlace,
            simulador = simulador,
            ambito = prueba.backgroundScope,
            reloj = { prueba.currentTime },
            urlRobot = "http://192.168.4.1",
        )
    }

    private val fallo: suspend () -> ConsultaEstado = { ConsultaEstado.Fallida("El robot no respondió a tiempo (3 s)") }

    @Test
    fun sondeoCadaIntervaloDesdeQueTerminaLaAnterior() = runTest {
        val e = Escenario(this)
        e.api.estado = {
            delay(200) // la petición tarda 200 ms
            ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO))
        }
        assertEquals(EstadoConexion.Pausado, e.repo.conexion.value)
        e.repo.reanudarSondeo()
        assertEquals(EstadoConexion.Conectando, e.repo.conexion.value)
        runCurrent()
        assertEquals(EstadoConexion.Conectando, e.repo.conexion.value)
        advanceTimeBy(5_400)
        runCurrent()
        assertEquals(listOf(0L, 1_700L, 3_400L, 5_100L), e.api.instantes("estado"))
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value)
        assertEquals(5_300L, e.repo.ultimaRespuestaMs.value)
        assertTrue(e.repo.estado.value!!.listoParaRutinas)
        assertEquals(1, e.api.maxEnVuelo)
    }

    @Test
    fun transicionesConBackoffYRecuperacion() = runTest {
        val e = Escenario(this)
        var respuestas = 0
        e.api.estado = {
            respuestas++
            if (respuestas == 1 || respuestas >= 8) {
                ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO))
            } else {
                fallo()
            }
        }
        e.repo.reanudarSondeo()
        runCurrent()
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value) // t = 0

        val esperados = listOf(
            1_500L to EstadoConexion.SinRespuesta(1, "El robot no respondió a tiempo (3 s)"),
            3_000L to EstadoConexion.SinRespuesta(2, "El robot no respondió a tiempo (3 s)"),
            4_500L to EstadoConexion.Reintentando(3, 6_500L, "El robot no respondió a tiempo (3 s)"),
            6_500L to EstadoConexion.Reintentando(4, 10_500L, "El robot no respondió a tiempo (3 s)"),
            10_500L to EstadoConexion.Reintentando(5, 18_500L, "El robot no respondió a tiempo (3 s)"),
            18_500L to EstadoConexion.Reintentando(6, 28_500L, "El robot no respondió a tiempo (3 s)"), // tope 10 s
            28_500L to EstadoConexion.Conectado,
        )
        for ((instante, conexion) in esperados) {
            advanceTimeBy(instante - currentTime)
            runCurrent()
            assertEquals("t=$instante", conexion, e.repo.conexion.value)
        }
        // El estado se conserva durante los fallos y el backoff se reinicia tras el éxito.
        assertTrue(e.repo.estado.value != null)
        assertEquals(28_500L, e.repo.ultimaRespuestaMs.value)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(0L, 1_500L, 3_000L, 4_500L, 6_500L, 10_500L, 18_500L, 28_500L, 30_000L), e.api.instantes("estado"))
    }

    @Test
    fun pausaYReanudacionIdempotentes() = runTest {
        val e = Escenario(this)
        e.repo.reanudarSondeo()
        e.repo.reanudarSondeo()
        runCurrent()
        assertEquals(1, e.api.instantes("estado").size)
        e.repo.pausarSondeo()
        e.repo.pausarSondeo()
        assertEquals(EstadoConexion.Pausado, e.repo.conexion.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, e.api.instantes("estado").size)
        // "estado" conserva el último valor mientras está en pausa.
        assertTrue(e.repo.estado.value != null)

        e.repo.reanudarSondeo()
        assertEquals(EstadoConexion.Conectando, e.repo.conexion.value)
        runCurrent()
        assertEquals(listOf(0L, 10_000L), e.api.instantes("estado"))
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value)
    }

    @Test
    fun pausarCancelaLaConsultaEnCursoSinPisarElEstado() = runTest {
        val e = Escenario(this)
        e.api.estado = { awaitCancellation() }
        e.repo.reanudarSondeo()
        runCurrent()
        e.repo.pausarSondeo()
        runCurrent()
        assertEquals(1, e.api.consultasCanceladas)
        assertEquals(EstadoConexion.Pausado, e.repo.conexion.value)
    }

    @Test
    fun lasOrdenesNoSeReintentan() = runTest {
        val e = Escenario(this)
        e.api.orden = { ResultadoComando.SinRespuesta("El robot no respondió a tiempo (3 s)") }
        val estadoListo = FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO)
        e.repo.reanudarSondeo()
        runCurrent()
        assertEquals(estadoListo, e.repo.estado.value)

        val r = e.repo.iniciar(ModoInicio.CICLO)
        assertTrue(r is ResultadoComando.SinRespuesta)
        e.api.orden = { ResultadoComando.Ocupado("sistema ocupado") }
        assertEquals(ResultadoComando.Ocupado("sistema ocupado"), e.repo.reiniciar())
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, e.api.instantes("api/iniciar").size)
        assertEquals(1, e.api.instantes("api/reiniciar").size)
    }

    @Test
    fun laOrdenEsperaALaConsultaEnCursoYAdelantaLaSiguiente() = runTest {
        val e = Escenario(this)
        e.api.estado = {
            delay(1_000)
            ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO))
        }
        e.api.orden = {
            delay(100)
            ResultadoComando.Exito()
        }
        e.repo.reanudarSondeo()
        runCurrent()
        advanceTimeBy(500)
        val r = async { e.repo.pruebaRetorno() }
        advanceTimeBy(700)
        runCurrent()
        assertTrue(r.isCompleted)
        assertEquals(ResultadoComando.Exito(), r.await())
        assertEquals(listOf(1_000L), e.api.instantes("api/prueba_retorno")) // esperó a la consulta
        // Tras el éxito, consulta inmediata (en 1 100 ms, sin esperar el intervalo).
        assertEquals(listOf(0L, 1_100L), e.api.instantes("estado"))
        assertEquals(1, e.api.maxEnVuelo)
    }

    @Test
    fun conUnaOrdenEnCursoElTurnoDeSondeoSeSalta() = runTest {
        val e = Escenario(this)
        e.api.orden = {
            delay(2_500)
            ResultadoComando.Ocupado("sistema ocupado")
        }
        e.repo.reanudarSondeo()
        runCurrent() // consulta en t = 0
        advanceTimeBy(100)
        launch { e.repo.pruebaSensor() } // t = 100 → 2 600
        advanceTimeBy(3_000) // turno de t = 1 500 saltado; el siguiente, t = 3 000
        runCurrent()
        assertEquals(listOf(0L, 3_000L), e.api.instantes("estado"))
        assertEquals(1, e.api.maxEnVuelo)
    }

    @Test
    fun detenerCancelaLaConsultaYSaleEnseguida() = runTest {
        val e = Escenario(this)
        var primera = true
        e.api.estado = {
            if (primera) {
                primera = false
                awaitCancellation() // el robot no contesta
            }
            ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_EMERGENCIA))
        }
        e.repo.reanudarSondeo()
        runCurrent()
        advanceTimeBy(300)
        val inicio = currentTime
        val r = e.repo.detener()
        assertEquals(inicio, currentTime) // no esperó turno
        assertEquals(ResultadoComando.Exito(), r)
        assertEquals(listOf(300L), e.api.instantes("api/detener"))
        runCurrent()
        assertEquals(1, e.api.consultasCanceladas) // Call.cancel() en el cliente real
        // La consulta cancelada no cuenta como fallo y la siguiente se adelanta.
        assertEquals(listOf(0L, 300L), e.api.instantes("estado"))
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value)
        assertTrue(e.repo.estado.value!!.hayFalla)
    }

    @Test
    fun detenerNoEsperaAOtraOrdenEnVuelo() = runTest {
        val e = Escenario(this)
        e.api.orden = { orden ->
            if (orden is OrdenMaestro.PruebaMovimiento) delay(3_000)
            ResultadoComando.Exito()
        }
        val prueba = async { e.repo.pruebaMovimiento(10f) }
        runCurrent()
        advanceTimeBy(500)
        e.repo.detener()
        assertEquals(listOf(500L), e.api.instantes("api/detener"))
        assertFalse(prueba.isCompleted)
        prueba.await()
    }

    @Test
    fun rutinasSoloEnEsperaYSinFalla() = runTest {
        val e = Escenario(this)
        // Sin datos del robot todavía: no se envía nada.
        assertTrue(e.repo.iniciar(ModoInicio.SENSADO) is ResultadoComando.Ocupado)
        assertTrue(e.repo.configurarAuto(activar = true) is ResultadoComando.Ocupado)

        e.api.estado = { ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_EMERGENCIA)) }
        e.repo.reanudarSondeo()
        runCurrent()
        assertTrue(e.repo.iniciar(ModoInicio.FERTILIZAR) is ResultadoComando.Ocupado)
        assertTrue(e.repo.configurarAuto(activar = true, descansoSeg = 60) is ResultadoComando.Ocupado)
        // Desactivar el automático y reiniciar sí se permiten en EMERGENCIA.
        assertEquals(ResultadoComando.Exito(), e.repo.configurarAuto(activar = false))
        assertEquals(ResultadoComando.Exito(), e.repo.reiniciar())
        assertTrue(e.api.instantes("api/iniciar").isEmpty())

        e.api.estado = { ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO)) }
        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ResultadoComando.Exito(), e.repo.iniciar(ModoInicio.CICLO))
        assertEquals(1, e.api.instantes("api/iniciar").size)
    }

    @Test
    fun simuladorYRed() = runTest {
        val e = Escenario(this)
        runCurrent()
        assertEquals(DestinoMaestro("http://192.168.4.1", null), e.api.destino)
        assertEquals(EstadoRed.Disponible(ModoEnlace.MANUAL), e.repo.red.value)
        assertTrue(e.repo.simuladorDisponible)

        e.repo.reanudarSondeo()
        runCurrent()
        assertTrue(e.repo.estado.value != null)

        e.repo.usarSimulador(true)
        runCurrent()
        assertTrue(e.simulador.activo)
        assertTrue(e.repo.usandoSimulador.value)
        assertEquals(EstadoRed.Simulador, e.repo.red.value)
        assertEquals(DestinoMaestro("http://127.0.0.1:4567", null), e.api.destino)
        // Lo del robot no se mezcla con el simulador; se consulta enseguida.
        assertEquals(listOf(0L, 0L), e.api.instantes("estado"))
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value)

        // Con el simulador activo, la red Wi-Fi no cambia el destino.
        val fabrica = SocketFactory.getDefault()
        e.enlace.fabricaSockets.value = fabrica
        runCurrent()
        assertEquals("http://127.0.0.1:4567", e.api.destino?.urlBase)

        e.repo.usarSimulador(false)
        runCurrent()
        assertFalse(e.simulador.activo)
        assertEquals(DestinoMaestro("http://192.168.4.1", fabrica), e.api.destino)
        assertEquals(EstadoRed.Disponible(ModoEnlace.MANUAL), e.repo.red.value)

        e.enlace.estado.value = EstadoRed.PermisosFaltantes(listOf("android.permission.ACCESS_LOCAL_NETWORK"))
        runCurrent()
        assertEquals(EstadoRed.PermisosFaltantes(listOf("android.permission.ACCESS_LOCAL_NETWORK")), e.repo.red.value)

        e.repo.conectarWifiRobot()
        e.repo.reevaluarRed()
        assertEquals(1, e.enlace.conexionesAutomaticas)
        assertEquals(1, e.enlace.reevaluaciones)
    }

    @Test
    fun simuladorNoDisponibleNoHaceNada() = runTest {
        val e = Escenario(this, simuladorDisponible = false)
        e.repo.usarSimulador(true)
        runCurrent()
        assertFalse(e.simulador.activo)
        assertFalse(e.repo.usandoSimulador.value)
        assertEquals("http://192.168.4.1", e.api.destino?.urlBase)
    }

    @Test
    fun laRedDelRobotSeUsaEnCuantoAparece() = runTest {
        val e = Escenario(this)
        e.api.estado = fallo
        e.repo.reanudarSondeo()
        runCurrent()
        advanceTimeBy(3_100) // 3 fallos: backoff de 2 s hasta t = 5 000
        runCurrent()
        assertTrue(e.repo.conexion.value is EstadoConexion.Reintentando)
        val fabrica = SocketFactory.getDefault()
        e.enlace.fabricaSockets.value = fabrica
        runCurrent()
        assertEquals(fabrica, e.api.destino?.fabricaSockets)
        assertEquals(listOf(0L, 1_500L, 3_000L, 3_100L), e.api.instantes("estado"))
    }

    @Test
    fun unaConsultaAlRobotEnCursoNoSeMezclaConElSimulador() = runTest {
        val e = Escenario(this)
        e.api.estado = {
            delay(1_000)
            ConsultaEstado.Correcta(FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO))
        }
        e.repo.reanudarSondeo()
        runCurrent()
        advanceTimeBy(500)
        e.repo.usarSimulador(true) // la consulta al robot sigue en vuelo hasta t = 1 000
        advanceTimeBy(600)
        runCurrent()
        assertEquals(null, e.repo.estado.value)
        assertEquals(EstadoConexion.Conectando, e.repo.conexion.value)
        assertEquals(listOf(0L, 1_000L), e.api.instantes("estado")) // la del simulador, enseguida
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(e.repo.estado.value != null)
        assertEquals(EstadoConexion.Conectado, e.repo.conexion.value)
    }

    @Test
    fun esperaDeBackoff() {
        assertEquals(2_000L, RobotRepositoryImpl.esperaBackoff(3))
        assertEquals(4_000L, RobotRepositoryImpl.esperaBackoff(4))
        assertEquals(8_000L, RobotRepositoryImpl.esperaBackoff(5))
        assertEquals(10_000L, RobotRepositoryImpl.esperaBackoff(6))
        assertEquals(10_000L, RobotRepositoryImpl.esperaBackoff(60))
    }

}
