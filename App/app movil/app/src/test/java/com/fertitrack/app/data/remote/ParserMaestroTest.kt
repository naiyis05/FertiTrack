package com.fertitrack.app.data.remote

import com.fertitrack.app.data.FixturesMaestro
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.modelo.TipoFalla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserMaestroTest {

    @Test
    fun estadoCompletoRealista() {
        val e = FixturesMaestro.estado(FixturesMaestro.ESTADO_SENSANDO)
        assertEquals(EstadoMaquina.SENSADO_MIDIENDO, e.estado)
        assertEquals("SENSADO_MIDIENDO", e.estadoTexto)
        assertEquals(ModoRutina.SENSADO, e.modoRutina)
        assertEquals(3, e.zonaActual)
        assertEquals(6, e.totalZonas)
        assertEquals(300f, e.distanciaCm)
        assertEquals(50f, e.anchoZonaCm)
        assertEquals(100f, e.pasosPorCm)
        assertEquals(15_000L, e.posicionPasos)
        assertEquals(2650, e.humedadAdc)
        assertEquals(49.91364f, e.humedadPct!!, 0.0001f)
        assertEquals(4095, e.calibracion.adcSeco)
        assertEquals(1200, e.calibracion.adcMojado)
        assertEquals(3082, e.calibracion.umbralSeco)
        assertEquals(1490, e.calibracion.umbralSobre)
        assertEquals(NivelSuelo.INTERMEDIA, e.nivelSuelo)
        assertEquals(Actividad.SENSANDO, e.actividad)
        assertEquals(TipoFalla.SIN_FALLA, e.falla)
        assertFalse(e.hayFalla)
        assertTrue(e.espBConectada)
        assertEquals(60L, e.descansoSeg)
        assertNull(e.proximoCicloSeg)
        assertEquals(
            listOf(NivelSuelo.SECA, NivelSuelo.INTERMEDIA) + List(4) { NivelSuelo.SIN_MEDIR },
            e.zonas,
        )
        assertEquals(0.5f, e.fraccionRecorrido!!, 0.0001f)
    }

    @Test
    fun menosUnoEsSinDato() {
        val e = FixturesMaestro.estado(FixturesMaestro.ESTADO_SIN_DATOS)
        assertNull(e.humedadAdc)
        assertNull(e.humedadPct)
        assertNull(e.proximoCicloSeg)
        assertFalse(e.distanciaConfigurada)
        assertTrue(e.zonas.isEmpty())
    }

    @Test
    fun proximoCicloYAutoActivo() {
        val e = FixturesMaestro.estado(FixturesMaestro.ESTADO_LISTO)
        assertEquals(95L, e.proximoCicloSeg)
        assertTrue(e.autoActivo)
        assertTrue(e.listoParaRutinas)
    }

    @Test
    fun estadoDesconocidoConservaElTexto() {
        val e = FixturesMaestro.estado("""{"estado":"?","modo_rutina":"?","actividad":7,"falla":5,"nivel_suelo":9,"zonas":[1,6]}""")
        assertEquals(EstadoMaquina.DESCONOCIDO, e.estado)
        assertEquals("?", e.estadoTexto)
        assertEquals(ModoRutina.DESCONOCIDO, e.modoRutina)
        assertEquals(Actividad.DESCONOCIDA, e.actividad)
        assertEquals(TipoFalla.DESCONOCIDA, e.falla)
        assertEquals(5, e.codigoFalla)
        assertTrue(e.hayFalla)
        assertEquals(NivelSuelo.DESCONOCIDO, e.nivelSuelo)
        assertEquals(listOf(NivelSuelo.SECA, NivelSuelo.DESCONOCIDO), e.zonas)
    }

    @Test
    fun camposDeMasSeIgnoran() {
        val e = FixturesMaestro.estado("""{"estado":"ESPERANDO","version":"5.4","extra":{"a":[1,2]},"total_zonas":4}""")
        assertEquals(EstadoMaquina.ESPERANDO, e.estado)
        assertEquals(4, e.totalZonas)
    }

    @Test
    fun maestroV6PruebaDeBrazoConCamposNuevos() {
        val e = FixturesMaestro.estado(FixturesMaestro.ESTADO_PRUEBA_BRAZO_V6)
        assertEquals(EstadoMaquina.PRUEBA_BRAZO, e.estado)
        assertEquals(Actividad.EN_MOVIMIENTO, e.actividad)
        assertEquals(6, e.totalZonas)
        assertEquals(300f, e.distanciaCm)
        assertEquals(3082, e.calibracion.umbralSeco)
        assertTrue(e.distanciaConfigurada)
        assertFalse(e.listoParaRutinas) // solo en ESPERANDO
    }

    @Test
    fun camposQueFaltanONulosUsanValoresSeguros() {
        val e = FixturesMaestro.estado("""{"estado":"ESPERANDO","humedad_pct":null,"zonas":null}""")
        assertEquals(0, e.totalZonas)
        assertNull(e.humedadAdc)
        assertNull(e.humedadPct)
        assertNull(e.proximoCicloSeg)
        assertEquals(Actividad.DESCONOCIDA, e.actividad)
        assertEquals(TipoFalla.SIN_FALLA, e.falla)
        assertFalse(e.autoActivo)
        assertTrue(e.zonas.isEmpty())
    }

    @Test
    fun floatsQueLleganComoEnteros() {
        val e = FixturesMaestro.estado("""{"estado":"EMERGENCIA","humedad_adc":0,"humedad_pct":100,"distancia_cm":300,"falla":3}""")
        assertEquals(100f, e.humedadPct)
        assertEquals(0, e.humedadAdc)
        assertEquals(300f, e.distanciaCm)
        assertEquals(TipoFalla.SENSOR_SIN_LECTURA, e.falla)
    }

    @Test
    fun jsonRotoOSinEstadoEsFalloControlado() {
        listOf(
            "",
            "no es json",
            """{"estado":"ESPERANDO","total_zonas":""",
            """{"total_zonas":6}""",
            """[1,2,3]""",
            """{"estado":"ESPERANDO","zonas":"muchas"}""",
            "<html>404</html>",
        ).forEach { cuerpo ->
            assertNull(cuerpo, ParserMaestro.parsearEstado(cuerpo))
            assertTrue(cuerpo, ParserMaestro.interpretarEstado(200, cuerpo) is ConsultaEstado.Fallida)
        }
    }

    @Test
    fun estadoConCodigoDeErrorEsFallido() {
        val r = ParserMaestro.interpretarEstado(404, """{"error":"ruta no encontrada"}""")
        assertTrue(r is ConsultaEstado.Fallida)
        assertTrue((r as ConsultaEstado.Fallida).detalle.contains("ruta no encontrada"))
    }

    @Test
    fun ordenExitoConDatos() {
        val r = ParserMaestro.interpretarOrden(
            200, """{"ok":true,"num_zonas":6,"ancho_zona_real_cm":50,"distancia_total_pasos":30000}""",
        )
        assertEquals(
            ResultadoComando.Exito(null, mapOf("num_zonas" to "6", "ancho_zona_real_cm" to "50", "distancia_total_pasos" to "30000")),
            r,
        )
        assertEquals(
            ResultadoComando.Exito("Regresando al origen...", emptyMap()),
            ParserMaestro.interpretarOrden(200, """{"ok":true,"mensaje":"Regresando al origen..."}"""),
        )
        assertEquals(
            ResultadoComando.Exito(null, mapOf("auto_activo" to "true", "descanso_seg" to "60")),
            ParserMaestro.interpretarOrden(200, """{"ok":true,"auto_activo":true,"descanso_seg":60}"""),
        )
    }

    @Test
    fun ordenCodigosDeError() {
        assertEquals(
            ResultadoComando.Ocupado("sistema ocupado"),
            ParserMaestro.interpretarOrden(409, """{"ok":false,"error":"sistema ocupado"}"""),
        )
        assertEquals(
            ResultadoComando.Rechazado("falta distancia_cm"),
            ParserMaestro.interpretarOrden(400, """{"ok":false,"error":"falta distancia_cm"}"""),
        )
        assertEquals(
            ResultadoComando.ErrorRobot(404, "ruta no encontrada"),
            ParserMaestro.interpretarOrden(404, """{"error":"ruta no encontrada"}"""),
        )
        assertEquals(
            ResultadoComando.ErrorRobot(500, "no se pudo calcular zonas"),
            ParserMaestro.interpretarOrden(500, """{"ok":false,"error":"no se pudo calcular zonas"}"""),
        )
    }

    @Test
    fun ordenCuerpoRaroEsErrorRobot() {
        assertTrue(ParserMaestro.interpretarOrden(200, "OK") is ResultadoComando.ErrorRobot)
        assertTrue(ParserMaestro.interpretarOrden(200, """{"ok":"true"}""") is ResultadoComando.ErrorRobot)
        assertTrue(ParserMaestro.interpretarOrden(200, """{"ok":false}""") is ResultadoComando.ErrorRobot)
        assertTrue(ParserMaestro.interpretarOrden(409, "") is ResultadoComando.ErrorRobot)
        assertTrue(ParserMaestro.interpretarOrden(503, "<html>") is ResultadoComando.ErrorRobot)
    }

    @Test
    fun parametrosComoLosLeeElFirmware() {
        assertEquals(listOf("modo" to "fertilizar"), OrdenMaestro.Iniciar(ModoInicio.FERTILIZAR).parametros)
        assertEquals(
            listOf("distancia_cm" to "300", "ancho_zona_cm" to "2.5", "pasos_cm" to "98.75"),
            OrdenMaestro.Configurar(300f, 2.5f, 98.75f).parametros,
        )
        assertEquals(listOf("distancia_cm" to "300"), OrdenMaestro.Configurar(300f).parametros)
        assertEquals(listOf("activar" to "1", "descanso_seg" to "90"), OrdenMaestro.Auto(true, 90).parametros)
        assertEquals(listOf("activar" to "0"), OrdenMaestro.Auto(false).parametros)
        assertEquals(listOf("cm" to "10"), OrdenMaestro.PruebaMovimiento(10f).parametros)
        assertEquals(
            listOf("real_cm" to "9.5", "pedido_cm" to "10"),
            OrdenMaestro.CalibrarDistancia(9.5f, 10f).parametros,
        )
        assertEquals(
            listOf("punto" to "seco", "adc" to "3900"),
            OrdenMaestro.CalibrarSensor(PuntoCalibracion.SECO, 3900).parametros,
        )
        assertEquals(
            listOf("pct_seca" to "30", "pct_humeda" to "60", "pct_sobre" to "85"),
            OrdenMaestro.CalibrarSensor(pctSeca = 30, pctHumeda = 60, pctSobre = 85).parametros,
        )
        assertTrue(OrdenMaestro.Detener.parametros.isEmpty())
        assertEquals("Valor numérico no válido: no se envió la orden", OrdenMaestro.PruebaMovimiento(Float.NaN).problemaParametros())
    }
}
