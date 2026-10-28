package com.fertitrack.app.data

import com.fertitrack.app.data.historial.FuenteDatos
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.robot
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.SesionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeguimientoSesionTest {

    private val ambito = CoroutineScope(UnconfinedTestDispatcher())
    private val repo = RepositorioQuieto()
    private var ahora = 1_000L
    private val tracker = SesionTracker { ahora }

    private val muestraGuardada = MuestraHumedad(500L, zona = 2, humedadPct = 40f, adc = 2600, nivel = NivelSuelo.INTERMEDIA)
    private val historialRobot = ResumenSesion(muestras = listOf(muestraGuardada), totalMediciones = 1, humedadPromedio = 40f)

    @After
    fun limpiar() = ambito.cancel()

    private fun iniciar(almacen: AlmacenEnMemoria) = SeguimientoSesion(repo, tracker, almacen, ambito).also { it.iniciar() }

    private fun medirZona1(pct: Float) {
        ahora += 1_500
        repo.estado.value = robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 1)
        ahora += 1_500
        repo.estado.value = robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 2, adc = 2000, pct = pct)
    }

    @Test
    fun alIniciarCargaElHistorialYGuardaCadaCambio() {
        val almacen = AlmacenEnMemoria(mapOf(FuenteDatos.ROBOT to historialRobot))
        iniciar(almacen)
        assertEquals(listOf(muestraGuardada), tracker.resumen.value.muestras.take(1))

        medirZona1(70f)
        val guardado = almacen.guardado.getValue(FuenteDatos.ROBOT)
        assertEquals(listOf(40f, 70f), guardado.muestras.map { it.humedadPct })
        assertEquals(2, guardado.totalMediciones)
        assertEquals(55f, guardado.humedadPromedio!!, 0.01f)
    }

    @Test
    fun cadaFuenteTieneSuHistorial() {
        val almacen = AlmacenEnMemoria(mapOf(FuenteDatos.ROBOT to historialRobot))
        iniciar(almacen)
        medirZona1(70f)

        repo.usandoSimulador.value = true
        assertEquals(2, almacen.guardado.getValue(FuenteDatos.ROBOT).muestras.size)
        assertTrue(tracker.resumen.value.muestras.isEmpty())
        assertEquals("Usando el robot simulado", tracker.resumen.value.eventos.first().texto)
        medirZona1(15f)

        repo.usandoSimulador.value = false
        assertEquals(listOf(15f), almacen.guardado.getValue(FuenteDatos.SIMULADOR).muestras.map { it.humedadPct })
        assertEquals(listOf(40f, 70f), tracker.resumen.value.muestras.map { it.humedadPct })
        assertEquals("Usando el robot real", tracker.resumen.value.eventos.first().texto)
    }

    @Test
    fun laSugerenciaDeFertilizarNoSeGuarda() {
        val almacen = AlmacenEnMemoria()
        iniciar(almacen)
        val zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA, NivelSuelo.HUMEDA)
        repo.estado.value = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, zonas = zonas)
        repo.estado.value = robot(zonas = zonas)
        assertTrue(tracker.resumen.value.sugerirFertilizar)
        val guardado = almacen.guardado.getValue(FuenteDatos.ROBOT)
        assertEquals(1, guardado.riegosCompletados)
        assertEquals(false, guardado.sugerirFertilizar)
    }

    @Test
    fun borrarDejaElArchivoVacio() {
        val almacen = AlmacenEnMemoria(mapOf(FuenteDatos.ROBOT to historialRobot))
        val seguimiento = iniciar(almacen)
        seguimiento.borrarHistorial()
        assertEquals(ResumenSesion(), almacen.guardado.getValue(FuenteDatos.ROBOT))
    }
}

/** Repositorio que solo expone flujos; las órdenes no hacen nada. */
private class RepositorioQuieto : RobotRepository {
    override val conexion = MutableStateFlow<EstadoConexion>(EstadoConexion.Conectado)
    override val red = MutableStateFlow<EstadoRed>(EstadoRed.Inactiva)
    override val estado = MutableStateFlow<EstadoRobot?>(robot())
    override val ultimaRespuestaMs = MutableStateFlow<Long?>(null)
    override val usandoSimulador = MutableStateFlow(false)
    override val simuladorDisponible = true

    override fun reanudarSondeo() = Unit
    override fun pausarSondeo() = Unit
    override fun conectarWifiRobot() = Unit
    override fun reevaluarRed() = Unit
    override fun usarSimulador(activo: Boolean) { usandoSimulador.value = activo }
    override suspend fun iniciar(modo: ModoInicio) = ResultadoComando.Exito()
    override suspend fun detener() = ResultadoComando.Exito()
    override suspend fun reiniciar() = ResultadoComando.Exito()
    override suspend fun configurarAuto(activar: Boolean, descansoSeg: Long?) = ResultadoComando.Exito()
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
