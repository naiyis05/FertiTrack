package com.fertitrack.app.data.historial

import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.BombeoRiego
import com.fertitrack.app.domain.sesion.EventoSesion
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import com.fertitrack.app.domain.sesion.TipoEvento
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlmacenHistorialArchivoTest {

    @get:Rule
    val carpeta = TemporaryFolder()

    private val completo = ResumenSesion(
        eventos = listOf(EventoSesion(3_000, TipoEvento.RIEGO, "Riego completado (zona 1)")),
        muestras = listOf(
            MuestraHumedad(1_000, zona = 1, humedadPct = 21.5f, adc = 3100, nivel = NivelSuelo.SECA),
            MuestraHumedad(2_000, zona = null, humedadPct = 66f, adc = 2000, nivel = NivelSuelo.HUMEDA),
        ),
        riegos = listOf(RiegoRegistrado(2_500, 3_000, listOf(1), completado = true)),
        bombeos = listOf(BombeoRiego(listOf(1, 2), 2_600, 2_900, interrumpido = false)),
        riegosCompletados = 1,
        totalMediciones = 2,
        humedadPromedio = 43.75f,
        sugerirFertilizar = true,
    )

    @Test
    fun guardaYCargaCadaFuenteEnSuArchivo() = runBlocking {
        val almacen = AlmacenHistorialArchivo(carpeta.root)
        assertNull(almacen.cargar(FuenteDatos.ROBOT))
        almacen.guardar(FuenteDatos.ROBOT, completo)

        val leido = AlmacenHistorialArchivo(carpeta.root).cargar(FuenteDatos.ROBOT)
        // La sugerencia de fertilizar no se guarda.
        assertEquals(completo.copy(sugerirFertilizar = false), leido)
        assertNull(almacen.cargar(FuenteDatos.SIMULADOR))
        assertFalse(File(carpeta.root, "historial_robot.json.tmp").exists())
    }

    @Test
    fun unArchivoDanadoSeIgnora() = runBlocking {
        File(carpeta.root, "historial_robot.json").writeText("{ esto no es json")
        assertNull(AlmacenHistorialArchivo(carpeta.root).cargar(FuenteDatos.ROBOT))
    }

    @Test
    fun nombresDesconocidosDeOtraVersionNoRompenLaCarga() = runBlocking {
        File(carpeta.root, "historial_robot.json").writeText(
            """{"version":2,"campoNuevo":1,"eventos":[{"momentoMs":1,"tipo":"TIPO_FUTURO","texto":"x"}],""" +
                """"muestras":[{"momentoMs":2,"zona":3,"humedadPct":50.0,"adc":2400,"nivel":"NIVEL_FUTURO"}]}""",
        )
        val leido = AlmacenHistorialArchivo(carpeta.root).cargar(FuenteDatos.ROBOT)!!
        assertEquals(TipoEvento.AVISO, leido.eventos.single().tipo)
        assertEquals(NivelSuelo.DESCONOCIDO, leido.muestras.single().nivel)
    }
}
