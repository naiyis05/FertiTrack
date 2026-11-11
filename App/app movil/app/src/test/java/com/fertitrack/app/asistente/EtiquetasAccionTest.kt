package com.fertitrack.app.asistente

import com.fertitrack.app.asistente.AccionAsistente.IrA
import com.fertitrack.app.asistente.AccionAsistente.ProponerRutina
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtiquetasAccionTest {

    @Test
    fun irAQuitaLaEtiqueta() {
        val r = EtiquetasAccion.interpretar("Te llevo. [[ir:CONTROL]]")
        assertEquals("Te llevo.", r.texto)
        assertEquals(listOf(IrA(Destino.CONTROL)), r.acciones)
    }

    @Test
    fun toleraMayusculasYEspacios() {
        val r = EtiquetasAccion.interpretar("Abro la confirmación.\n[[ Rutina : fertilizar ]]")
        assertEquals("Abro la confirmación.", r.texto)
        assertEquals(listOf(ProponerRutina(RutinaPropuesta.FERTILIZAR)), r.acciones)
    }

    @Test
    fun etiquetaDesconocidaSeQuitaSinAccion() {
        val r = EtiquetasAccion.interpretar("Hola [[ir:MARTE]] y [[borrar:todo]] fin.")
        assertEquals("Hola y fin.", r.texto)
        assertTrue(r.acciones.isEmpty())
    }

    @Test
    fun soloSeQuedaLaPrimeraRutina() {
        val r = EtiquetasAccion.interpretar("Vamos [[rutina:CICLO]] [[ir:ZONAS]] [[rutina:SENSADO]]")
        assertEquals("Vamos", r.texto)
        assertEquals(listOf(ProponerRutina(RutinaPropuesta.CICLO), IrA(Destino.ZONAS)), r.acciones)
    }

    @Test
    fun sinEtiquetasQuedaIgualRecortado() {
        val r = EtiquetasAccion.interpretar("  Hola, ¿qué tal?  ")
        assertEquals("Hola, ¿qué tal?", r.texto)
        assertTrue(r.acciones.isEmpty())
    }
}
