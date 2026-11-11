package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.robot
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.componentes.Formatos
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextoAsistenteTest {

    private val ahora = 1_700_000_000_000L

    private fun construir(
        enVivo: EstadoRobot? = null,
        foto: FotoRobot? = null,
        resumen: ResumenSesion = ResumenSesion(),
        motivo: String? = null,
        destino: Destino = Destino.CONTROL,
    ) = ContextoAsistente.construir(EntradaContexto(destino, enVivo, foto, resumen, motivo), ahora)

    @Test
    fun enVivoDiceEnVivo() {
        val t = construir(enVivo = robot())
        assertTrue(t, t.contains("EN VIVO"))
        assertTrue(t, t.contains("Pantalla actual: Control"))
    }

    @Test
    fun soloFotoDiceAntiguedad() {
        val foto = FotoRobot.de(robot(), ahora - 7_500_000)
        val t = construir(foto = foto)
        assertTrue(t, t.contains("hace ${Formatos.antiguedad(7_500)}"))
        assertTrue(t, t.contains(Formatos.fechaHoraCorta(foto.momentoMs)))
        assertTrue(t, t.contains("No son datos en vivo"))
        assertFalse(t, t.contains("EN VIVO"))
    }

    @Test
    fun sinDatos() {
        val t = construir()
        assertTrue(t, t.contains("Sin datos del robot"))
    }

    @Test
    fun incluyeZonasMedicionesYUltimoRiego() {
        val zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA, NivelSuelo.SIN_MEDIR)
        val riego = RiegoRegistrado(ahora - 3_600_000, ahora - 3_000_000, listOf(1), completado = true)
        val muestra = MuestraHumedad(ahora - 4_000_000, zona = 2, humedadPct = 71f, adc = 1900, nivel = NivelSuelo.HUMEDA)
        val t = construir(
            enVivo = robot(totalZonas = 3, zonas = zonas),
            resumen = ResumenSesion(muestras = listOf(muestra), riegos = listOf(riego), riegosCompletados = 1),
        )
        assertTrue(t, t.contains("Zona 1: Seca"))
        assertTrue(t, t.contains("Zona 2: Húmeda"))
        assertTrue(t, t.contains(Formatos.fechaHoraCorta(riego.finMs)))
        assertTrue(t, t.contains("71 %"))
    }

    @Test
    fun incluyeFallaActiva() {
        val t = construir(enVivo = robot(EstadoMaquina.EMERGENCIA, falla = 8))
        assertTrue(t, t.contains(Traductor.describirFalla(8).titulo))
    }

    @Test
    fun incluyeMotivoRutinasODisponibles() {
        val ocupado = construir(enVivo = robot(), motivo = "El robot está ocupado: regando")
        assertTrue(ocupado, ocupado.contains("El robot está ocupado: regando"))
        val libre = construir(enVivo = robot())
        assertTrue(libre, libre.contains("Rutinas disponibles"))
    }
}
