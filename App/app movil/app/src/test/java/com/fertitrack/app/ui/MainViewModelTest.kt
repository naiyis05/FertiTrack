package com.fertitrack.app.ui

import androidx.lifecycle.viewModelScope
import com.fertitrack.app.data.RobotRepository
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.robot
import com.fertitrack.app.simulador.Simulador
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var repo: RepositorioFalso
    private lateinit var simulador: SimuladorFalso
    private lateinit var vm: MainViewModel
    private val ui get() = vm.ui.value

    @Before
    fun preparar() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = RepositorioFalso()
        simulador = SimuladorFalso()
        vm = MainViewModel(repo, simulador, reloj = { 10_000L })
    }

    @After
    fun limpiar() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun conectadoEnEsperaHabilitaRutinas() {
        assertTrue(ui.rutinasHabilitadas)
        assertNull(ui.motivoRutinasDeshabilitadas)
        assertFalse(ui.datosObsoletos)
        assertTrue(ui.puedeActivarAuto)
        assertFalse(ui.reiniciarHabilitado)
        assertNotNull(ui.descripcionEstado)
    }

    @Test
    fun sinConexionTodoQuedaDeshabilitadoYObsoleto() {
        repo.conexion.value = EstadoConexion.SinRespuesta(1, "timeout")
        assertTrue(ui.datosObsoletos)
        assertFalse(ui.rutinasHabilitadas)
        assertFalse(ui.puedeGuardarDescanso)
        assertEquals("Sin conexión con el robot", ui.motivoRutinasDeshabilitadas)
    }

    @Test
    fun permisoFaltanteRemiteAConexion() {
        repo.conexion.value = EstadoConexion.SinRespuesta(1, "timeout")
        repo.red.value = EstadoRed.PermisosFaltantes(listOf("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(ui.motivoRutinasDeshabilitadas!!.contains("Conexión"))
    }

    @Test
    fun emergenciaHabilitaSoloReiniciar() {
        repo.estado.value = robot(EstadoMaquina.EMERGENCIA, falla = 8)
        assertFalse(ui.rutinasHabilitadas)
        assertTrue(ui.reiniciarHabilitado)
        assertEquals(8, ui.falla?.codigo)
    }

    @Test
    fun confirmarEnviaUnaSolaVez() {
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO))
        assertEquals("¿Iniciar el ciclo completo?", ui.confirmacion?.titulo)
        assertTrue(repo.iniciados.isEmpty())

        vm.confirmar()
        assertEquals(listOf(ModoInicio.CICLO), repo.iniciados)
        assertNull(ui.confirmacion)
        assertNull(ui.comandoEnCurso)
        assertEquals(TipoMensaje.EXITO, ui.mensaje?.tipo)

        vm.confirmar()
        assertEquals(1, repo.iniciados.size)
    }

    @Test
    fun noAbreConfirmacionSiNoSePuede() {
        repo.estado.value = robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 2)
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.FERTILIZAR))
        assertNull(ui.confirmacion)
        assertEquals(TipoMensaje.AVISO, ui.mensaje?.tipo)
    }

    @Test
    fun revalidaAlConfirmar() {
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO))
        repo.estado.value = robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 1)
        vm.confirmar()
        assertTrue(repo.iniciados.isEmpty())
        assertNull(ui.confirmacion)
        assertTrue(ui.mensaje!!.texto.startsWith("No se envió la orden"))
    }

    @Test
    fun ocupadoEsUnAvisoAmistoso() {
        repo.resultadoIniciar = ResultadoComando.Ocupado("sistema ocupado")
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.SENSADO))
        vm.confirmar()
        assertEquals(TipoMensaje.AVISO, ui.mensaje?.tipo)
        assertTrue(ui.mensaje!!.texto.contains("ocupado ahora mismo"))
    }

    @Test
    fun detenerFuncionaSinConexion() {
        repo.conexion.value = EstadoConexion.Reintentando(4, 0, "timeout")
        repo.resultadoDetener = ResultadoComando.SinRespuesta("timeout")
        vm.detenerEmergencia()
        assertEquals(1, repo.detenciones)
        assertEquals(TipoMensaje.ERROR, ui.mensaje?.tipo)
        assertTrue(ui.mensaje!!.texto.contains("botón físico"))
    }

    @Test
    fun detenerCierraLaConfirmacionAbierta() {
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO))
        vm.detenerEmergencia()
        assertNull(ui.confirmacion)
        assertEquals(1, repo.detenciones)
        assertTrue(repo.iniciados.isEmpty())
    }

    @Test
    fun detenerFuncionaConOtraOrdenEnCurso() {
        val bloqueo = CompletableDeferred<Unit>()
        repo.bloqueoIniciar = bloqueo
        vm.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO))
        vm.confirmar()
        assertEquals("Iniciar ciclo completo", ui.comandoEnCurso)
        assertFalse(ui.rutinasHabilitadas)

        vm.detenerEmergencia()
        assertEquals(1, repo.detenciones)

        bloqueo.complete(Unit)
        assertNull(ui.comandoEnCurso)
    }

    @Test
    fun toquesRepetidosDeDetenerSeIgnoranMientrasSeEnvia() {
        val bloqueo = CompletableDeferred<Unit>()
        repo.bloqueoDetener = bloqueo
        vm.detenerEmergencia()
        vm.detenerEmergencia()
        vm.detenerEmergencia()
        assertTrue(ui.paradaEnCurso)
        assertEquals(1, repo.detenciones)
        bloqueo.complete(Unit)
        assertFalse(ui.paradaEnCurso)
        vm.detenerEmergencia()
        assertEquals(2, repo.detenciones)
    }

    @Test
    fun reiniciarSoloTrasEmergencia() {
        vm.pedirConfirmacion(AccionConfirmable.Reiniciar)
        assertNull(ui.confirmacion)
        repo.estado.value = robot(EstadoMaquina.EMERGENCIA, falla = 9)
        vm.pedirConfirmacion(AccionConfirmable.Reiniciar)
        assertNotNull(ui.confirmacion)
        vm.confirmar()
        assertEquals(1, repo.reinicios)
    }

    @Test
    fun sugerenciaDeFertilizar() {
        val zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA)
        repo.estado.value = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, zonas = zonas)
        repo.estado.value = robot(zonas = zonas)
        assertTrue(ui.resumen.sugerirFertilizar)

        vm.responderSugerenciaFertilizar(true)
        assertEquals(AccionConfirmable.FertilizarTrasRiego, ui.confirmacion?.accion)
        vm.confirmar()
        assertEquals(listOf(ModoInicio.FERTILIZAR), repo.iniciados)
        assertFalse(ui.resumen.sugerirFertilizar)
    }

    @Test
    fun descartarSugerencia() {
        val zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA)
        repo.estado.value = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, zonas = zonas)
        repo.estado.value = robot(zonas = zonas)
        vm.responderSugerenciaFertilizar(false)
        assertFalse(ui.resumen.sugerirFertilizar)
        assertTrue(repo.iniciados.isEmpty())
    }

    @Test
    fun modoAutomatico() {
        vm.pedirConfirmacion(AccionConfirmable.ActivarAuto(120))
        vm.confirmar()
        assertEquals(listOf(true to 120L), repo.autos)

        repo.estado.value = robot(auto = true)
        assertTrue(ui.puedeDesactivarAuto)
        vm.desactivarAuto()
        assertEquals(false to null, repo.autos.last())

        vm.guardarDescanso(0)
        assertEquals(2, repo.autos.size)
        vm.guardarDescanso(300)
        assertEquals(true to 300L, repo.autos.last())
    }

    @Test
    fun mensajesEnColaDeAUno() {
        vm.pedirConfirmacion(AccionConfirmable.Reiniciar)
        val primero = ui.mensaje!!
        vm.simularFalla(2)
        val segundo = ui.mensaje!!
        assertEquals(primero.id, segundo.id)
        vm.mensajeMostrado(primero.id)
        assertEquals("Activa primero el robot simulado", ui.mensaje?.texto)
    }

    @Test
    fun simuladorYCicloDeVida() {
        vm.usarSimulador(true)
        assertTrue(repo.usandoSimulador.value)
        vm.simularFalla(3)
        assertEquals(listOf(3), simulador.fallas)
        vm.alSegundoPlano()
        vm.alPrimerPlano()
        assertEquals(listOf("pausar", "reanudar"), repo.sondeo)
    }
}

/** Doble del repositorio: registra las llamadas y permite congelar órdenes. */
private class RepositorioFalso : RobotRepository {
    override val conexion = MutableStateFlow<EstadoConexion>(EstadoConexion.Conectado)
    override val red = MutableStateFlow<EstadoRed>(EstadoRed.Disponible(ModoEnlace.MANUAL))
    override val estado = MutableStateFlow<EstadoRobot?>(robot())
    override val ultimaRespuestaMs = MutableStateFlow<Long?>(9_000L)
    override val usandoSimulador = MutableStateFlow(false)
    override val simuladorDisponible = true

    val iniciados = mutableListOf<ModoInicio>()
    val autos = mutableListOf<Pair<Boolean, Long?>>()
    val sondeo = mutableListOf<String>()
    var detenciones = 0
    var reinicios = 0
    var resultadoIniciar: ResultadoComando = ResultadoComando.Exito()
    var resultadoDetener: ResultadoComando = ResultadoComando.Exito()
    var bloqueoIniciar: CompletableDeferred<Unit>? = null
    var bloqueoDetener: CompletableDeferred<Unit>? = null

    override fun reanudarSondeo() { sondeo += "reanudar" }
    override fun pausarSondeo() { sondeo += "pausar" }
    override fun conectarWifiRobot() = Unit
    override fun reevaluarRed() = Unit
    override fun usarSimulador(activo: Boolean) { usandoSimulador.value = activo }

    override suspend fun iniciar(modo: ModoInicio): ResultadoComando {
        iniciados += modo
        bloqueoIniciar?.await()
        return resultadoIniciar
    }

    override suspend fun detener(): ResultadoComando {
        detenciones++
        bloqueoDetener?.await()
        bloqueoDetener = null
        return resultadoDetener
    }

    override suspend fun reiniciar(): ResultadoComando {
        reinicios++
        return ResultadoComando.Exito()
    }

    override suspend fun configurarAuto(activar: Boolean, descansoSeg: Long?): ResultadoComando {
        autos += activar to descansoSeg
        return ResultadoComando.Exito()
    }

    override suspend fun configurarRecorrido(distanciaCm: Float, anchoZonaCm: Float?, pasosPorCm: Float?) = ResultadoComando.Exito()
    override suspend fun pruebaMovimiento(cm: Float) = ResultadoComando.Exito()
    override suspend fun calibrarDistancia(realCm: Float, pedidoCm: Float?) = ResultadoComando.Exito()
    override suspend fun pruebaRetorno() = ResultadoComando.Exito()
    override suspend fun pruebaSensor() = ResultadoComando.Exito()
    override suspend fun calibrarSensor(
        punto: PuntoCalibracion?,
        adc: Int?,
        pctSeca: Int?,
        pctHumeda: Int?,
        pctSobre: Int?,
    ) = ResultadoComando.Exito()
}

private class SimuladorFalso : Simulador {
    val fallas = mutableListOf<Int>()
    override val disponible = true
    override fun iniciar() = "http://127.0.0.1:1"
    override fun detener() = Unit
    override fun inyectarFalla(codigo: Int) { fallas += codigo }
    override fun simularPerdidaConexion(segundos: Int) = Unit
}
