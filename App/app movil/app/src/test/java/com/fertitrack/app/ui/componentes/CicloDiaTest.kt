package com.fertitrack.app.ui.componentes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CicloDiaTest {

    private fun min(h: Int, m: Int = 0) = h * 60f + m

    @Test
    fun fasesSegunLaHora() {
        assertEquals(FaseDia.NOCHE, CicloDia.fase(min(0)))
        assertEquals(FaseDia.NOCHE, CicloDia.fase(min(4, 59)))
        assertEquals(FaseDia.AMANECER, CicloDia.fase(min(5)))
        assertEquals(FaseDia.MANANA, CicloDia.fase(min(9, 41)))
        assertEquals(FaseDia.MEDIODIA, CicloDia.fase(min(12)))
        assertEquals(FaseDia.TARDE, CicloDia.fase(min(15)))
        assertEquals(FaseDia.ATARDECER, CicloDia.fase(min(18)))
        assertEquals(FaseDia.NOCHE, CicloDia.fase(min(23, 59)))
        // Cada fase muestra un minuto que le pertenece.
        FaseDia.entries.forEach { assertEquals(it, CicloDia.fase(it.minutoMuestra.toFloat())) }
    }

    @Test
    fun elSolCruzaDeDiaYLaLunaDeNoche() {
        assertNull(CicloDia.avanceSol(min(3)))
        assertNotNull(CicloDia.avanceLuna(min(3)))
        assertNull(CicloDia.avanceLuna(min(12)))
        val mediodia = CicloDia.avanceSol((CicloDia.SALIDA_SOL + CicloDia.PUESTA_SOL) / 2f)!!
        assertEquals(0.5f, mediodia, 0.001f)
        assertEquals(1f, CicloDia.altura(mediodia), 0.001f)
        assertEquals(0f, CicloDia.avanceSol(CicloDia.SALIDA_SOL.toFloat())!!, 0.001f)
        // La luna cruza la medianoche sin cortes.
        val antes = CicloDia.avanceLuna(min(23, 59))!!
        val despues = CicloDia.avanceLuna(min(0, 1))!!
        assertTrue(despues > antes && despues - antes < 0.01f)
        // Al amanecer y al atardecer se ven los dos.
        assertNotNull(CicloDia.avanceSol(min(6)))
        assertNotNull(CicloDia.avanceLuna(min(6)))
    }

    @Test
    fun coloresContinuosAlrededorDelDia() {
        val a = CicloDia.cielo(min(23, 59))
        val b = CicloDia.cielo(min(0))
        assertEquals(a.arriba.red, b.arriba.red, 0.01f)
        assertEquals(a.abajo.blue, b.abajo.blue, 0.01f)
        // Entre dos claves, un color intermedio.
        val alba = CicloDia.cielo(min(6, 10)).abajo.red
        assertTrue(alba > CicloDia.cielo(min(4)).abajo.red)
        assertEquals(1f, CicloDia.oscuridad(min(0)), 0.001f)
        assertEquals(0f, CicloDia.oscuridad(min(12)), 0.001f)
        val amanecer = CicloDia.oscuridad(min(5, 45))
        assertTrue(amanecer > 0f && amanecer < 1f)
    }

    @Test
    fun laAnimacionAvanzaComoElRelojSalvoRetrocesosCortos() {
        // 9:41 → 9:00: 41 min hacia atrás.
        assertEquals(min(9), CicloDia.destinoAnimacion(min(9, 41), min(9)), 0.001f)
        // 9:41 → 18:00: hacia adelante.
        assertEquals(min(18), CicloDia.destinoAnimacion(min(9, 41), min(18)), 0.001f)
        // 18:00 → 9:41: da la vuelta por la noche (avanza 15 h 41 min).
        assertEquals(min(18) + min(15, 41), CicloDia.destinoAnimacion(min(18), min(9, 41)), 0.001f)
        // Funciona con valores ya desenrollados (más de un día).
        assertEquals(1440f + min(12), CicloDia.destinoAnimacion(1440f + min(9), min(12)), 0.001f)
        assertEquals(min(7), CicloDia.destinoAnimacion(min(7), min(7)), 0.001f)
    }
}
