package com.fertitrack.app.domain.sesion

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SesionTrackerTest {

    private var ahora = 1_000L
    private val tracker = SesionTracker { ahora }
    private val r get() = tracker.resumen.value

    private fun paso(estado: com.fertitrack.app.domain.modelo.EstadoRobot) {
        ahora += 1_500
        tracker.procesarEstado(estado)
    }

    private val S = NivelSuelo.SIN_MEDIR
    private val SECA = NivelSuelo.SECA
    private val HUM = NivelSuelo.HUMEDA

    /** Sensado de 2 zonas: la 1 queda seca y la 2 húmeda. */
    private fun sensadoDosZonas(zona1: NivelSuelo, zona2: NivelSuelo, pct1: Float, pct2: Float) {
        paso(robot(totalZonas = 2))
        paso(robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 1, totalZonas = 2, zonas = listOf(S, S)))
        paso(robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 1, totalZonas = 2, zonas = listOf(S, S)))
        paso(robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 2, totalZonas = 2, zonas = listOf(zona1, S), adc = 3500, pct = pct1))
        paso(robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 2, totalZonas = 2, zonas = listOf(zona1, S), adc = 3500, pct = pct1))
        paso(robot(EstadoMaquina.SENSADO_RETORNANDO, ModoRutina.SENSADO, zona = 2, totalZonas = 2, zonas = listOf(zona1, zona2), adc = 2000, pct = pct2))
    }

    @Test
    fun cicloConRiegoRegistraMuestrasRiegoYSugerencia() {
        sensadoDosZonas(SECA, HUM, 15f, 70f)
        val zonas = listOf(SECA, HUM)
        paso(robot(EstadoMaquina.RIEGO_BAJANDO_BRAZO, ModoRutina.RIEGO, zona = 1, totalZonas = 2, zonas = zonas, adc = 2000, pct = 70f))
        paso(robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, totalZonas = 2, zonas = zonas, adc = 2000, pct = 70f, actividad = Actividad.REGANDO))
        paso(robot(EstadoMaquina.RIEGO_RETORNANDO, ModoRutina.RIEGO, zona = 2, totalZonas = 2, zonas = zonas, adc = 2000, pct = 70f))
        paso(robot(totalZonas = 2, zonas = zonas, adc = 2000, pct = 70f))

        assertEquals(listOf(1, 2), r.muestras.map { it.zona })
        assertEquals(listOf(SECA, HUM), r.muestras.map { it.nivel })
        assertEquals(listOf(15f, 70f), r.muestras.map { it.humedadPct })
        assertEquals(2, r.totalMediciones)
        assertEquals(42.5f, r.humedadPromedio!!, 0.01f)
        assertEquals(1, r.riegosCompletados)
        assertEquals(listOf(1), r.riegos.single().zonasRegadas)
        assertTrue(r.riegos.single().completado)
        assertTrue(r.sugerirFertilizar)
        val textos = r.eventos.map { it.texto }
        assertTrue(textos.any { it == "Riego completado (zona 1)" })
        assertTrue(textos.any { it == "Sensado completado: 1 seca, 1 húmeda" })
        assertTrue(textos.any { it == "Zona 1: 15 % (Seca)" })
    }

    @Test
    fun cicloSinZonasSecasNoRiegaNiSugiere() {
        sensadoDosZonas(HUM, HUM, 70f, 72f)
        paso(robot(totalZonas = 2, zonas = listOf(HUM, HUM), adc = 2000, pct = 72f))
        assertEquals(0, r.riegosCompletados)
        assertTrue(r.riegos.isEmpty())
        assertFalse(r.sugerirFertilizar)
        assertEquals(2, r.muestras.size)
    }

    @Test
    fun fallaEnPlenoRiegoLoMarcaInterrumpido() {
        paso(robot())
        paso(robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 2, zonas = listOf(SECA, SECA, S, S, S, S), actividad = Actividad.REGANDO))
        paso(robot(EstadoMaquina.EMERGENCIA, ModoRutina.NINGUNA, zona = 2, zonas = listOf(SECA, SECA, S, S, S, S), falla = 8))
        assertEquals(0, r.riegosCompletados)
        assertFalse(r.riegos.single().completado)
        assertEquals(listOf(1, 2), r.riegos.single().zonasRegadas)
        assertFalse(r.sugerirFertilizar)
        assertTrue(r.eventos.any { it.tipo == TipoEvento.FALLA && it.texto == "Falla 8: Parada desde la app" })
        assertTrue(r.eventos.any { it.texto == "Riego interrumpido" })
    }

    @Test
    fun falla3AlMedirNoRegistraMuestra() {
        paso(robot())
        paso(robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 1))
        paso(robot(EstadoMaquina.EMERGENCIA, ModoRutina.NINGUNA, zona = 1, falla = 3, adc = 0, pct = 100f))
        assertTrue(r.muestras.isEmpty())
        assertEquals(0, r.totalMediciones)
        assertTrue(r.eventos.any { it.texto == "Falla 3: Sensor sin lectura válida" })
        assertTrue(r.eventos.any { it.texto == "Sensado interrumpido" })
    }

    @Test
    fun pruebaDeSensorRegistraMuestraSinZona() {
        paso(robot())
        paso(robot(EstadoMaquina.PRUEBA_SENSOR))
        paso(robot(adc = 2500, pct = 52f))
        val m = r.muestras.single()
        assertNull(m.zona)
        assertEquals(NivelSuelo.INTERMEDIA, m.nivel)
        assertTrue(r.eventos.first().texto.startsWith("Prueba de sensor: 52 %"))
    }

    @Test
    fun respetaTopesYPromediaTodasLasMediciones() {
        paso(robot())
        repeat(70) { i ->
            paso(robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 1))
            paso(robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 2, adc = 2000, pct = i.toFloat()))
        }
        assertEquals(RobotConfig.MAX_PUNTOS_GRAFICA, r.muestras.size)
        assertEquals(70, r.totalMediciones)
        assertEquals(34.5f, r.humedadPromedio!!, 0.01f)
        assertEquals(69f, r.muestras.last().humedadPct)
        assertTrue(r.eventos.size <= RobotConfig.MAX_EVENTOS)
    }

    @Test
    fun noRepiteEventosConsecutivos() {
        tracker.registrarEvento(TipoEvento.AVISO, "Hola")
        tracker.registrarEvento(TipoEvento.AVISO, "Hola")
        assertEquals(1, r.eventos.count { it.texto == "Hola" })
    }

    @Test
    fun conexionPerdidaYRecuperadaSinRepetir() {
        tracker.procesarConexion(EstadoConexion.Conectando)
        tracker.procesarConexion(EstadoConexion.Conectado)
        tracker.procesarConexion(EstadoConexion.SinRespuesta(1, "timeout"))
        tracker.procesarConexion(EstadoConexion.SinRespuesta(2, "timeout"))
        tracker.procesarConexion(EstadoConexion.Reintentando(3, 0, "timeout"))
        tracker.procesarConexion(EstadoConexion.Conectado)
        tracker.procesarConexion(EstadoConexion.Pausado)
        tracker.procesarConexion(EstadoConexion.Conectando)
        tracker.procesarConexion(EstadoConexion.Conectado)
        val textos = r.eventos.filter { it.tipo == TipoEvento.CONEXION }.map { it.texto }.reversed()
        assertEquals(listOf("Conectado al robot", "Se perdió la conexión con el robot", "Conexión recuperada"), textos)
    }

    @Test
    fun laSugerenciaSeBorraAlEmpezarOtraRutinaOAlDescartar() {
        sensadoDosZonas(SECA, HUM, 15f, 70f)
        val zonas = listOf(SECA, HUM)
        paso(robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, totalZonas = 2, zonas = zonas))
        paso(robot(totalZonas = 2, zonas = zonas))
        assertTrue(r.sugerirFertilizar)
        paso(robot(EstadoMaquina.SENSADO_AVANZANDO, ModoRutina.SENSADO, zona = 1, totalZonas = 2, zonas = zonas))
        assertFalse(r.sugerirFertilizar)

        paso(robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, totalZonas = 2, zonas = zonas))
        paso(robot(totalZonas = 2, zonas = zonas))
        assertTrue(r.sugerirFertilizar)
        tracker.descartarSugerenciaFertilizar()
        assertFalse(r.sugerirFertilizar)
    }

    @Test
    fun primeraRespuestaEnRiegoUsaEseMomentoComoInicio() {
        val zonas = listOf(SECA, S, S, S, S, S)
        paso(robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 1, zonas = zonas))
        val inicio = ahora
        paso(robot(zonas = zonas))
        assertEquals(inicio, r.riegos.single().inicioMs)
        assertEquals(1, r.riegosCompletados)
        assertTrue(r.eventos.last().texto.startsWith("Estado inicial"))
    }

    @Test
    fun registraOrdenesYModoAutomatico() {
        paso(robot())
        tracker.registrarComando("Iniciar ciclo completo", ResultadoComando.Ocupado("sistema ocupado"))
        assertEquals("Iniciar ciclo completo: robot ocupado", r.eventos.first().texto)
        paso(robot(auto = true, descanso = 120))
        assertEquals("Modo automático activado (descanso de 120 s)", r.eventos.first().texto)
    }

    @Test
    fun reiniciarSesionLimpiaTodo() {
        sensadoDosZonas(SECA, HUM, 15f, 70f)
        tracker.reiniciarSesion()
        assertEquals(ResumenSesion(), r)
    }
}
