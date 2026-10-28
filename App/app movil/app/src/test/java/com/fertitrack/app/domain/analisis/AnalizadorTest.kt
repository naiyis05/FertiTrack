package com.fertitrack.app.domain.analisis

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.robot
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalizadorTest {

    private val conectado = EstadoConexion.Conectado
    private val HUM = NivelSuelo.HUMEDA

    private fun generar(
        robot: com.fertitrack.app.domain.modelo.EstadoRobot? = robot(zonas = List(6) { HUM }),
        resumen: ResumenSesion = ResumenSesion(totalMediciones = 1, humedadPromedio = 70f),
        conexion: EstadoConexion = conectado,
    ) = Analizador.generar(robot, resumen, conexion, 0L)

    @Test
    fun fallaActivaVaPrimeroComoCritica() {
        val lista = generar(robot = robot(EstadoMaquina.EMERGENCIA, falla = 8, zonas = List(6) { NivelSuelo.SECA }))
        assertEquals(Severidad.CRITICO, lista.first().severidad)
        assertTrue(lista.first().titulo.contains("Parada desde la app"))
    }

    @Test
    fun sinConexionAvisa() {
        val lista = generar(robot = null, conexion = EstadoConexion.SinRespuesta(1, "timeout"))
        assertTrue(lista.any { it.severidad == Severidad.AVISO && it.titulo == "Sin datos del robot" })
    }

    @Test
    fun zonasSecasPendientesConRobotEnReposo() {
        val zonas = listOf(NivelSuelo.SECA, HUM, HUM, NivelSuelo.SECA, HUM, HUM)
        val lista = generar(robot = robot(zonas = zonas))
        assertTrue(lista.any { it.titulo == "2 zonas secas (1 y 4)" && it.severidad == Severidad.AVISO })
        val ocupado = generar(robot = robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 2, zonas = zonas))
        assertTrue(ocupado.none { it.titulo.contains("zonas secas") })
    }

    @Test
    fun zonasSobreHumedas() {
        val lista = generar(robot = robot(zonas = listOf(HUM, NivelSuelo.SOBRE_HUMEDA, HUM, HUM, HUM, HUM)))
        assertTrue(lista.any { it.titulo == "Posible exceso de riego" && it.detalle.contains("zona 2") })
    }

    @Test
    fun tendenciaALaBaja() {
        val muestras = listOf(60f, 60f, 60f, 40f, 40f, 40f).mapIndexed { i, p ->
            MuestraHumedad(i.toLong(), 1, p, 2000, HUM)
        }
        val lista = generar(resumen = ResumenSesion(muestras = muestras, totalMediciones = 6, humedadPromedio = 50f))
        assertTrue(lista.any { it.titulo == "La humedad está bajando" && it.detalle.contains("20 puntos") })
    }

    @Test
    fun riegosCompletadosYProximoCiclo() {
        val resumen = ResumenSesion(
            riegos = listOf(RiegoRegistrado(0, 10, listOf(1, 3), completado = true)),
            riegosCompletados = 1,
            totalMediciones = 3,
            humedadPromedio = 55f,
        )
        val lista = generar(robot = robot(zonas = List(6) { HUM }, auto = true, proximo = 125), resumen = resumen)
        assertTrue(lista.any { it.severidad == Severidad.EXITO && it.titulo == "1 riego completado en el historial" })
        assertTrue(lista.any { it.titulo == "Próximo ciclo automático en 2:05" })
    }

    @Test
    fun distanciaSinConfigurarYSensorDesconectado() {
        val lista = generar(robot = robot(distanciaCm = 0f, totalZonas = 0, espB = false))
        assertTrue(lista.any { it.titulo == "Recorrido sin configurar" })
        assertTrue(lista.any { it.titulo == "Sensor no conectado" })
    }

    @Test
    fun sinMedicionesYTodoEnOrden() {
        val vacia = generar(resumen = ResumenSesion())
        assertTrue(vacia.any { it.titulo == "Aún no hay mediciones guardadas" })
        val bien = generar()
        assertTrue(bien.any { it.titulo == "Todo en orden" && it.detalle.contains("70 %") })
    }

    @Test
    fun ordenadoPorSeveridad() {
        val zonas = listOf(NivelSuelo.SECA, HUM, HUM, HUM, HUM, HUM)
        val lista = generar(
            robot = robot(EstadoMaquina.EMERGENCIA, falla = 2, zonas = zonas, auto = true, proximo = 30),
            resumen = ResumenSesion(riegosCompletados = 1, totalMediciones = 1),
        )
        val orden = lista.map { it.severidad }
        val esperado = orden.sortedBy { listOf(Severidad.CRITICO, Severidad.AVISO, Severidad.INFO, Severidad.EXITO).indexOf(it) }
        assertEquals(esperado, orden)
    }
}
