package com.fertitrack.app.data.historial

import com.fertitrack.app.asistente.FotoRobot
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.NivelSuelo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlmacenFotoArchivoTest {

    @get:Rule
    val carpeta = TemporaryFolder()

    private fun foto(momentoMs: Long, estado: EstadoMaquina = EstadoMaquina.ESPERANDO) = FotoRobot(
        momentoMs = momentoMs,
        estado = estado,
        estadoTexto = estado.name,
        totalZonas = 3,
        distanciaCm = 150f,
        zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA, NivelSuelo.SIN_MEDIR),
        humedadPct = 42.5f,
        codigoFalla = 0,
        autoActivo = true,
        descansoSeg = 1_800,
    )

    @Test
    fun guardaYCargaPorFuente() = runBlocking {
        val robot = foto(1_000)
        val simulado = foto(2_000, EstadoMaquina.RIEGO_AVANZANDO)
        AlmacenFotoArchivo(carpeta.root).apply {
            guardar(FuenteDatos.ROBOT, robot)
            guardar(FuenteDatos.SIMULADOR, simulado)
        }
        // Otra instancia: lo que se lee viene del archivo, no de memoria.
        val leido = AlmacenFotoArchivo(carpeta.root)
        assertEquals(robot, leido.cargar(FuenteDatos.ROBOT))
        assertEquals(simulado, leido.cargar(FuenteDatos.SIMULADOR))
    }

    @Test
    fun sinArchivoDevuelveNull() = runBlocking {
        assertNull(AlmacenFotoArchivo(carpeta.root).cargar(FuenteDatos.ROBOT))
    }

    @Test
    fun archivoDanadoDevuelveNull() = runBlocking {
        File(carpeta.root, "ultimo_estado_robot.json").writeText("{roto")
        assertNull(AlmacenFotoArchivo(carpeta.root).cargar(FuenteDatos.ROBOT))
    }

    @Test
    fun nivelYEstadoDesconocidosSeLeenComoDesconocidos() = runBlocking {
        File(carpeta.root, "ultimo_estado_robot.json").writeText(
            """{"momentoMs":5,"estado":"PRUEBA_NUEVA","estadoTexto":"PRUEBA_NUEVA","totalZonas":2,"distanciaCm":100,
               "zonas":["SECA","XYZ"],"humedadPct":null,"codigoFalla":0,"autoActivo":false,"descansoSeg":60}""",
        )
        val foto = AlmacenFotoArchivo(carpeta.root).cargar(FuenteDatos.ROBOT)!!
        assertEquals(EstadoMaquina.DESCONOCIDO, foto.estado)
        assertEquals("PRUEBA_NUEVA", foto.estadoTexto)
        assertEquals(listOf(NivelSuelo.SECA, NivelSuelo.DESCONOCIDO), foto.zonas)
        assertNull(foto.humedadPct)
    }
}
