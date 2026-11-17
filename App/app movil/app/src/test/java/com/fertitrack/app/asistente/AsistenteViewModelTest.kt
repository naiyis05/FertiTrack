package com.fertitrack.app.asistente

import com.fertitrack.app.asistente.AccionAsistente.IrA
import com.fertitrack.app.asistente.AccionAsistente.ProponerRutina
import com.fertitrack.app.asistente.gemini.RespuestaCerebro
import com.fertitrack.app.asistente.gemini.TipoFallo
import com.fertitrack.app.asistente.voz.EstadoEscucha
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.robot
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.Traductor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsistenteViewModelTest {

    private val despachador = UnconfinedTestDispatcher()
    private val registro = mutableListOf<String>()
    private val cerebro = CerebroFalso()
    private val entrada = EntradaVozFalsa(registro)
    private val salida = SalidaVozFalsa(registro)
    private val robotFlujo = MutableStateFlow<EstadoRobot?>(robot())
    private val conexion = MutableStateFlow<EstadoConexion>(EstadoConexion.Conectado)
    private val resumen = MutableStateFlow(ResumenSesion())
    private val foto = MutableStateFlow<FotoRobot?>(null)
    private var ahora = 1_700_000_000_000L
    private lateinit var vm: AsistenteViewModel

    @Before
    fun preparar() {
        Dispatchers.setMain(despachador)
        vm = AsistenteViewModel(cerebro, entrada, salida, robotFlujo, conexion, resumen, foto, claveConfigurada = true) { ahora }
    }

    @After
    fun limpiar() = Dispatchers.resetMain()

    private val estado get() = vm.estado.value

    @Test
    fun textoProduceRespuestaYHabla() = runTest(despachador) {
        val puerta = CompletableDeferred<Unit>()
        cerebro.puertas += puerta
        cerebro.respuestas += ok("La zona 2 está seca.")

        vm.enviarTexto("¿qué zonas están secas?")
        assertTrue(estado.pensando)
        puerta.complete(Unit)

        assertFalse(estado.pensando)
        assertEquals(listOf(true, false), estado.mensajes.map { it.deUsuario })
        assertEquals("La zona 2 está seca.", estado.mensajes.last().texto)
        assertEquals(listOf("La zona 2 está seca."), salida.dichos)
    }

    @Test
    fun vozDesactivadaNoHabla() = runTest(despachador) {
        vm.alternarVoz()
        assertFalse(estado.vozActivada)
        vm.enviarTexto("hola")
        assertEquals(2, estado.mensajes.size)
        assertTrue(salida.dichos.isEmpty())
    }

    @Test
    fun irAEmiteEvento() = runTest(despachador) {
        val eventos = mutableListOf<AccionAsistente>()
        backgroundScope.launch(despachador) { vm.eventos.collect { eventos += it } }
        cerebro.respuestas += ok("Vamos. [[ir:GRAFICAS]]")

        vm.enviarTexto("llévame a gráficas")
        assertEquals(listOf<AccionAsistente>(IrA(Destino.GRAFICAS)), eventos)
        assertEquals("Vamos.", estado.mensajes.last().texto)
        assertEquals(listOf<AccionAsistente>(IrA(Destino.GRAFICAS)), estado.mensajes.last().acciones)
    }

    @Test
    fun rutinaDisponibleEmiteEvento() = runTest(despachador) {
        val eventos = mutableListOf<AccionAsistente>()
        backgroundScope.launch(despachador) { vm.eventos.collect { eventos += it } }
        cerebro.respuestas += ok("Te abro la confirmación. [[rutina:FERTILIZAR]]")

        vm.enviarTexto("fertiliza")
        assertEquals(listOf<AccionAsistente>(ProponerRutina(RutinaPropuesta.FERTILIZAR)), eventos)
    }

    @Test
    fun rutinaNoDisponibleExplicaMotivo() = runTest(despachador) {
        val eventos = mutableListOf<AccionAsistente>()
        backgroundScope.launch(despachador) { vm.eventos.collect { eventos += it } }
        val regando = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 2)
        robotFlujo.value = regando
        cerebro.respuestas += ok("Te abro la confirmación. [[rutina:FERTILIZAR]]")

        vm.enviarTexto("fertiliza")
        assertTrue(eventos.isEmpty())
        val motivo = Traductor.motivoRutinasNoDisponibles(regando, EstadoConexion.Conectado)!!
        assertTrue(estado.mensajes.last().texto, estado.mensajes.last().texto.endsWith(TextosAsistente.rutinaNoDisponible(motivo)))
        assertTrue(estado.mensajes.last().acciones.isEmpty())
    }

    @Test
    fun sinInternetMuestraAvisoYLoDiceSoloConVoz() = runTest(despachador) {
        cerebro.respuestas += RespuestaCerebro.Fallo(TipoFallo.SIN_INTERNET)
        vm.enviarTexto("hola")
        val aviso = estado.mensajes.last()
        assertTrue(aviso.esError)
        assertEquals(TextosAsistente.fallo(TipoFallo.SIN_INTERNET), aviso.texto)
        assertFalse(estado.pensando)
        assertEquals(listOf(TextosAsistente.falloHablado(TipoFallo.SIN_INTERNET)), salida.dichos)

        vm.alternarVoz()
        cerebro.respuestas += RespuestaCerebro.Fallo(TipoFallo.SIN_INTERNET)
        vm.enviarTexto("hola otra vez")
        assertEquals(1, salida.dichos.size)
    }

    @Test
    fun avisosDeConfiguracionSeEscribenConDetalleYSeDicenSinNombresTecnicos() = runTest(despachador) {
        for (tipo in listOf(TipoFallo.SIN_CLAVE, TipoFallo.CLAVE_INVALIDA, TipoFallo.MODELO_NO_EXISTE)) {
            cerebro.respuestas += RespuestaCerebro.Fallo(tipo)
            vm.enviarTexto("hola")
            assertEquals(TextosAsistente.fallo(tipo), estado.mensajes.last().texto)
            val dicho = salida.dichos.last()
            assertFalse(dicho, dicho.contains("properties") || dicho.contains("fertitrack.") || dicho.contains("."+"gemini"))
        }
    }

    @Test
    fun peticionRechazadaEmpiezaUnaConversacionNueva() = runTest(despachador) {
        vm.enviarTexto("primera")
        cerebro.respuestas += RespuestaCerebro.Fallo(TipoFallo.PETICION_RECHAZADA)
        vm.enviarTexto("segunda")
        assertEquals(TextosAsistente.fallo(TipoFallo.PETICION_RECHAZADA), estado.mensajes.last().texto)

        // Gemini ya no recibe lo anterior; en pantalla sigue todo.
        vm.enviarTexto("tercera")
        assertEquals(listOf("tercera"), cerebro.ultimoHistorial.map { it.texto })
        assertTrue(estado.mensajes.any { it.texto == "primera" })
    }

    @Test
    fun contextoUsaFotoSinConexion() = runTest(despachador) {
        conexion.value = EstadoConexion.Reintentando(3, ahora + 5_000, "sin respuesta")
        foto.value = FotoRobot.de(robot(), ahora - 7_200_000)
        vm.enviarTexto("¿cómo está el invernadero?")
        assertTrue(cerebro.ultimoContexto, cerebro.ultimoContexto.contains("hace"))
        assertFalse(cerebro.ultimoContexto, cerebro.ultimoContexto.contains("EN VIVO"))
    }

    @Test
    fun conConexionElContextoEsEnVivo() = runTest(despachador) {
        vm.enviarTexto("¿cómo está el invernadero?")
        assertTrue(cerebro.ultimoContexto, cerebro.ultimoContexto.contains("EN VIVO"))
    }

    @Test
    fun historialSeLimitaA20TurnosYEmpiezaPorElUsuario() = runTest(despachador) {
        repeat(15) { vm.enviarTexto("pregunta $it") }
        val historial = cerebro.ultimoHistorial
        assertTrue(historial.size.toString(), historial.size <= 20)
        assertTrue(historial.first().deUsuario)
        assertTrue(historial.last().deUsuario)
        assertEquals("pregunta 14", historial.last().texto)
    }

    @Test
    fun microfonoInterrumpeLaVoz() = runTest(despachador) {
        salida.hablando.value = true
        registro.clear()
        vm.alternarMicrofono()
        assertEquals(listOf("salida.detener", "entrada.empezar"), registro)
    }

    @Test
    fun transcripcionEnVivoYEnvio() = runTest(despachador) {
        entrada.estado.value = EstadoEscucha.Escuchando("qué ho", 0.5f)
        assertEquals("qué ho", estado.transcripcion)
        assertTrue(estado.escuchando)
        assertEquals(0.5f, estado.nivelVoz)

        entrada.estado.value = EstadoEscucha.Terminada("qué hora es")
        assertEquals("", estado.transcripcion)
        assertFalse(estado.escuchando)
        assertEquals("qué hora es", cerebro.ultimoHistorial.last().texto)
    }

    @Test
    fun sinPermisoMicrofonoAvisaYPermiteEscribir() = runTest(despachador) {
        vm.permisoMicrofono(false)
        assertTrue(estado.mensajes.last().texto.contains("micrófono"))
        vm.enviarTexto("hola")
        assertEquals(1, cerebro.llamadas)
    }

    @Test
    fun sinReconocedorAvisa() = runTest(despachador) {
        entrada.disponible = false
        vm.alternarMicrofono()
        assertTrue(estado.mensajes.last().esError)
        assertFalse(registro.contains("entrada.empezar"))
    }

    @Test
    fun nuevoMensajeCancelaElAnterior() = runTest(despachador) {
        cerebro.puertas += CompletableDeferred()
        cerebro.respuestas += ok("Respuesta a la segunda")

        vm.enviarTexto("primera")
        vm.enviarTexto("segunda")
        assertEquals(listOf("primera", "segunda", "Respuesta a la segunda"), estado.mensajes.map { it.texto })
        // La primera quedó sin respuesta: no se envía, así el historial sigue alternando.
        assertEquals(listOf("segunda"), cerebro.ultimoHistorial.map { it.texto })
    }

    @Test
    fun cancelarDetieneTodo() = runTest(despachador) {
        cerebro.puertas += CompletableDeferred()
        vm.enviarTexto("hola")
        entrada.estado.value = EstadoEscucha.Escuchando("algo", 0.3f)
        salida.hablando.value = true

        vm.cancelar()
        assertFalse(estado.pensando)
        assertFalse(estado.escuchando)
        assertFalse(estado.hablando)
        assertTrue(registro.contains("entrada.cancelar"))
        assertTrue(registro.contains("salida.detener"))
    }

    @Test
    fun sugerenciasSiguenLaPantalla() = runTest(despachador) {
        vm.pantallaActual(Destino.CONTROL)
        assertEquals(Sugerencias.para(Destino.CONTROL, robotFlujo.value), estado.sugerencias)
    }
}
