package com.fertitrack.app.simulador

import com.fertitrack.app.data.remote.ParserMaestro
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoMaquina.ESPERANDO
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.TipoFalla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Máquina de estados del simulador con reloj falso: determinista e instantánea. */
class MaquinaRobotSimuladaTest {

    private var ahora = 0L
    private val maquina = MaquinaRobotSimulada(TiemposSimulados.todos(PASO_MS), semilla = 2026L, reloj = { ahora })

    private fun get(ruta: String, vararg args: Pair<String, String>) = maquina.atender("GET", ruta, mapOf(*args))

    private fun estado(): EstadoRobot = checkNotNull(ParserMaestro.parsearEstado(get("/api/estado").cuerpo))

    /** Avanza el reloj de a poco hasta ESPERANDO; devuelve los estados vistos (sin repetir seguidos). */
    private fun hastaEsperando(alVer: (EstadoRobot) -> Unit = {}): List<EstadoMaquina> {
        val vistos = mutableListOf<EstadoMaquina>()
        repeat(10_000) {
            val e = estado()
            alVer(e)
            if (vistos.lastOrNull() != e.estado) vistos += e.estado
            if (e.estado == ESPERANDO && vistos.size > 1) return vistos
            ahora += PASO_MS / 4
        }
        error("No volvió a ESPERANDO: $vistos")
    }

    private fun arrancado() {
        hastaEsperando()
    }

    @Test
    fun alEncenderArrancaRecuperaYQuedaEnEspera() {
        val e = estado()
        assertEquals(EstadoMaquina.ARRANQUE, e.estado)
        assertEquals(6, e.totalZonas)
        assertEquals(300f, e.distanciaCm)
        assertEquals(50f, e.anchoZonaCm)
        assertNull(e.humedadAdc)
        assertNull(e.humedadPct)
        assertEquals(List(6) { NivelSuelo.SIN_MEDIR }, e.zonas)
        assertEquals(409 to """{"ok":false,"error":"sistema ocupado"}""", get("/api/iniciar", "modo" to "ciclo").let { it.codigo to it.cuerpo })

        val vistos = hastaEsperando()
        assertEquals(
            listOf(
                EstadoMaquina.ARRANQUE, EstadoMaquina.RECUP_ESPERA, EstadoMaquina.RECUP_BRAZO_RIEGO,
                EstadoMaquina.RECUP_BRAZO_FERTI, EstadoMaquina.RECUP_RETORNANDO, ESPERANDO,
            ),
            vistos,
        )
        assertTrue(estado().listoParaRutinas)
    }

    @Test
    fun cicloCompletoConRiego() {
        arrancado()
        assertEquals(200 to """{"ok":true}""", get("/api/iniciar", "modo" to "ciclo").let { it.codigo to it.cuerpo })
        var vioBombaDeRiego = false
        var ocupadoProbado = false
        val vistos = hastaEsperando { e ->
            if (e.bombaRiegoEncendida && maquina.bombaRiegoEncendida) {
                vioBombaDeRiego = true
                assertEquals(Actividad.REGANDO, e.actividad)
                assertEquals(ModoRutina.RIEGO, e.modoRutina)
            }
            if (!ocupadoProbado && e.estado == EstadoMaquina.SENSADO_MIDIENDO) {
                ocupadoProbado = true
                assertEquals(409, get("/api/iniciar", "modo" to "sensado").codigo)
                assertEquals(409, get("/api/configurar", "distancia_cm" to "100").codigo)
                assertEquals(409, get("/api/cal_sensor", "pct_seca" to "30").codigo)
                assertEquals(Actividad.SENSANDO, e.actividad)
            }
        }
        val esperados = listOf(
            EstadoMaquina.SENSADO_AVANZANDO, EstadoMaquina.SENSADO_MIDIENDO, EstadoMaquina.SENSADO_RETORNANDO,
            EstadoMaquina.RIEGO_BAJANDO_BRAZO, EstadoMaquina.RIEGO_AVANZANDO, EstadoMaquina.RIEGO_SUBIENDO_BRAZO,
            EstadoMaquina.RIEGO_RETORNANDO,
        )
        esperados.forEach { assertTrue("faltó $it en $vistos", it in vistos) }
        // El orden: todo el sensado antes del riego.
        assertTrue(vistos.indexOf(EstadoMaquina.SENSADO_RETORNANDO) < vistos.indexOf(EstadoMaquina.RIEGO_BAJANDO_BRAZO))
        assertTrue(vioBombaDeRiego)
        assertTrue(ocupadoProbado)

        val fin = estado()
        assertEquals(ESPERANDO, fin.estado)
        assertEquals(ModoRutina.NINGUNA, fin.modoRutina)
        assertEquals(0L, fin.posicionPasos)
        assertEquals(Actividad.REPOSO, fin.actividad)
        assertTrue(fin.zonas.none { it == NivelSuelo.SIN_MEDIR })
        assertTrue(NivelSuelo.SECA in fin.zonas)
        assertEquals(fin.zonas.last(), fin.nivelSuelo) // nivel de la ÚLTIMA zona medida
        assertTrue(fin.humedadAdc!! in 1..4095)
        assertFalse(maquina.bombaRiegoEncendida)
    }

    @Test
    fun sensadoSoloNoRiega() {
        arrancado()
        assertEquals(200, get("/api/iniciar", "modo" to "sensado").codigo)
        val vistos = hastaEsperando()
        assertTrue(EstadoMaquina.SENSADO_RETORNANDO in vistos)
        assertTrue(vistos.none { it.name.startsWith("RIEGO") })
    }

    @Test
    fun fertilizacion() {
        arrancado()
        assertEquals(200, get("/api/iniciar", "modo" to "fertilizar").codigo)
        var vioBomba = false
        val vistos = hastaEsperando { e -> if (e.bombaFertilizanteEncendida && maquina.bombaFertilizanteEncendida) vioBomba = true }
        assertEquals(
            listOf(
                EstadoMaquina.FERTI_BAJANDO_BRAZO, EstadoMaquina.FERTI_AVANZANDO, EstadoMaquina.FERTI_SUBIENDO_BRAZO,
                EstadoMaquina.FERTI_RETORNANDO, ESPERANDO,
            ),
            vistos,
        )
        assertTrue(vioBomba)
        assertFalse(maquina.bombaFertilizanteEncendida)
    }

    @Test
    fun detenerDeclaraFalla8YReiniciarRecupera() {
        arrancado()
        get("/api/auto", "activar" to "1")
        get("/api/iniciar", "modo" to "ciclo")
        ahora += PASO_MS * 3
        assertEquals(200 to """{"ok":true}""", get("/api/detener").let { it.codigo to it.cuerpo })
        val parado = estado()
        assertEquals(EstadoMaquina.EMERGENCIA, parado.estado)
        assertEquals(TipoFalla.PARADA_DESDE_APP, parado.falla)
        assertEquals(8, parado.codigoFalla)
        assertEquals(Actividad.REPOSO, parado.actividad)
        assertNull(parado.proximoCicloSeg)
        assertFalse(maquina.bombaRiegoEncendida)
        assertEquals(409, get("/api/iniciar", "modo" to "ciclo").codigo)
        ahora += 60_000
        assertEquals(EstadoMaquina.EMERGENCIA, estado().estado) // no sale sola

        assertEquals(200 to """{"ok":true}""", get("/api/reiniciar").let { it.codigo to it.cuerpo })
        val vistos = hastaEsperando()
        assertEquals(EstadoMaquina.RECUP_ESPERA, vistos.first())
        assertTrue(EstadoMaquina.RECUP_RETORNANDO in vistos)
        val fin = estado()
        assertEquals(TipoFalla.SIN_FALLA, fin.falla)
        assertFalse(fin.autoActivo) // reiniciar apaga el automático
        assertTrue(fin.listoParaRutinas)
    }

    @Test
    fun reiniciarFueraDeEmergenciaNoHaceNada() {
        arrancado()
        assertEquals(200, get("/api/reiniciar").codigo)
        assertEquals(ESPERANDO, estado().estado)
    }

    @Test
    fun modoAutomaticoConProximoCiclo() {
        arrancado()
        val r = get("/api/auto", "activar" to "1", "descanso_seg" to "5")
        assertEquals(200 to """{"ok":true,"auto_activo":true,"descanso_seg":5}""", r.codigo to r.cuerpo)
        assertEquals(5L, estado().proximoCicloSeg)
        ahora += 2_500
        assertEquals(2L, estado().proximoCicloSeg)
        ahora += 2_500
        val e = estado()
        assertEquals(EstadoMaquina.SENSADO_AVANZANDO, e.estado)
        assertNull(e.proximoCicloSeg)
        // Al terminar el ciclo vuelve a programar el siguiente.
        hastaEsperando()
        assertTrue(estado().proximoCicloSeg!! in 4L..5L)
        assertEquals("""{"ok":true,"auto_activo":false,"descanso_seg":5}""", get("/api/auto", "activar" to "0").cuerpo)
        assertNull(estado().proximoCicloSeg)
        // descanso_seg <= 0 se ignora.
        assertEquals("""{"ok":true,"auto_activo":false,"descanso_seg":5}""", get("/api/auto", "descanso_seg" to "0").cuerpo)
    }

    @Test
    fun configurarRecalculaZonas() {
        arrancado()
        assertEquals(400 to """{"ok":false,"error":"falta distancia_cm"}""", get("/api/configurar").let { it.codigo to it.cuerpo })
        assertEquals(
            """{"ok":false,"error":"distancia_cm debe ser > 0"}""",
            get("/api/configurar", "distancia_cm" to "abc").cuerpo,
        )
        val r = get("/api/configurar", "distancia_cm" to "100", "ancho_zona_cm" to "30")
        assertEquals(200, r.codigo)
        assertEquals("""{"ok":true,"num_zonas":3,"ancho_zona_real_cm":33.333332,"distancia_total_pasos":10000}""", r.cuerpo)
        val e = estado()
        assertEquals(3, e.totalZonas)
        assertEquals(List(3) { NivelSuelo.SIN_MEDIR }, e.zonas)
        assertEquals(
            """{"ok":true,"num_zonas":150,"ancho_zona_real_cm":2,"distancia_total_pasos":30000}""",
            get("/api/configurar", "distancia_cm" to "300", "ancho_zona_cm" to "1").cuerpo, // tope de 150 zonas
        )
    }

    @Test
    fun pruebaDeMovimientoYCalibracionDeDistancia() {
        arrancado()
        assertEquals(
            """{"ok":false,"error":"primero haz 'Mover prueba' (o indica pedido_cm)"}""",
            get("/api/calibrar_distancia", "real_cm" to "10").cuerpo,
        )
        assertEquals(
            """{"ok":false,"error":"solo despues de una prueba de movimiento"}""",
            get("/api/prueba_retorno").cuerpo,
        )
        assertEquals(400, get("/api/prueba_mov", "cm" to "200").codigo)
        val mov = get("/api/prueba_mov", "cm" to "10")
        assertEquals(
            """{"ok":true,"mensaje":"Moviendo 10.0 cm. Cuando se detenga, mide cuanto avanzo de verdad."}""",
            mov.cuerpo,
        )
        assertEquals(EstadoMaquina.PRUEBA_AVANZANDO, estado().estado)
        assertEquals(1_000L, estado().posicionPasos)
        ahora += PASO_MS
        assertEquals(EstadoMaquina.PRUEBA_LISTA, estado().estado)
        assertEquals(409, get("/api/iniciar", "modo" to "ciclo").codigo) // PRUEBA_LISTA no es ESPERANDO

        val cal = get("/api/calibrar_distancia", "real_cm" to "9.5")
        assertEquals(200, cal.codigo)
        assertTrue(cal.cuerpo, cal.cuerpo.contains("Nueva calibracion: 105.26 pasos/cm (antes 100.00)"))
        assertEquals(105.26316f, estado().pasosPorCm, 0.001f)

        assertEquals("""{"ok":true,"mensaje":"Regresando al origen..."}""", get("/api/prueba_retorno").cuerpo)
        assertEquals(EstadoMaquina.PRUEBA_RETORNANDO, estado().estado)
        ahora += PASO_MS
        assertEquals(ESPERANDO, estado().estado)
        assertEquals(0L, estado().posicionPasos)
    }

    @Test
    fun pruebaDeSensorYCalibracionDelSensor() {
        arrancado()
        assertEquals(
            """{"ok":false,"error":"no hay lectura valida: toca 'Medir sensor ahora' primero"}""",
            get("/api/cal_sensor", "punto" to "seco").cuerpo,
        )
        assertEquals(200, get("/api/prueba_sensor").codigo)
        assertEquals(EstadoMaquina.PRUEBA_SENSOR, estado().estado)
        assertEquals(409, get("/api/prueba_sensor").codigo)
        ahora += PASO_MS
        val medido = estado()
        assertEquals(ESPERANDO, medido.estado)
        assertTrue(medido.humedadAdc!! in 1..4095)
        assertTrue(medido.humedadPct!! in 0f..100f)

        assertEquals(400, get("/api/cal_sensor", "punto" to "mojado", "adc" to "3900").codigo) // SECO − MOJADO < 300
        assertEquals(
            """{"ok":false,"error":"los porcentajes deben cumplir 0 < seca < humeda < sobre < 100"}""",
            get("/api/cal_sensor", "pct_seca" to "50", "pct_humeda" to "40", "pct_sobre" to "90").cuerpo,
        )
        assertEquals(
            """{"ok":false,"error":"punto debe ser seco o mojado"}""",
            get("/api/cal_sensor", "punto" to "humedo", "adc" to "2000").cuerpo,
        )
        val ok = get("/api/cal_sensor", "punto" to "seco", "adc" to "4000")
        assertEquals(200, ok.codigo)
        assertTrue(ok.cuerpo, ok.cuerpo.startsWith("""{"ok":true,"mensaje":"Guardado. Seca: ADC >= 3020 |"""))
        val c = estado().calibracion
        assertEquals(4000, c.adcSeco)
        assertEquals(3020, c.umbralSeco) // round(4000 − 2800 · 0,35)
    }

    @Test
    fun fallasInyectadas() {
        arrancado()
        maquina.inyectarFalla(3)
        val e = estado()
        assertEquals(EstadoMaquina.EMERGENCIA, e.estado)
        assertEquals(TipoFalla.SENSOR_SIN_LECTURA, e.falla)
        assertEquals(0, e.humedadAdc)
        assertEquals(100f, e.humedadPct) // en el firmware tampoco es una medición válida
        get("/api/reiniciar")
        hastaEsperando()
        maquina.inyectarFalla(9)
        assertEquals(TipoFalla.BOTON_EMERGENCIA, estado().falla)
    }

    @Test
    fun rutasDesconocidasYModoInvalido() {
        arrancado()
        assertEquals(404 to """{"error":"ruta no encontrada"}""", get("/api/nada").let { it.codigo to it.cuerpo })
        assertEquals(404, maquina.atender("POST", "/api/estado", emptyMap()).codigo)
        assertEquals(
            """{"ok":false,"error":"modo invalido (usa ciclo, sensado o fertilizar)"}""",
            get("/api/iniciar", "modo" to "regar").cuerpo,
        )
        assertEquals(409, get("/api/iniciar").codigo)
        assertEquals(200, get("/").codigo)
    }

    @Test
    fun laMayoriaDeLosCiclosRiegaYAlgunosNo() {
        arrancado()
        var conRiego = 0
        val ciclos = 12
        repeat(ciclos) {
            assertEquals(200, get("/api/iniciar", "modo" to "ciclo").codigo)
            if (hastaEsperando().contains(EstadoMaquina.RIEGO_AVANZANDO)) conRiego++
        }
        assertTrue("ciclos con riego: $conRiego de $ciclos", conRiego > ciclos / 2)
        assertTrue("ciclos con riego: $conRiego de $ciclos", conRiego < ciclos)
    }

    @Test
    fun numerosComoArduino() {
        assertEquals(12.5f, MaquinaRobotSimulada.aFloatArduino(" 12.5cm"))
        assertEquals(0f, MaquinaRobotSimulada.aFloatArduino("abc"))
        assertEquals(-3f, MaquinaRobotSimulada.aFloatArduino("-3"))
        assertEquals(42L, MaquinaRobotSimulada.aEnteroArduino("42.9"))
        assertEquals(0L, MaquinaRobotSimulada.aEnteroArduino(""))
        assertEquals("10.0", MaquinaRobotSimulada.decimales(10f, 1))
    }

    private companion object {
        const val PASO_MS = 100L
    }
}
