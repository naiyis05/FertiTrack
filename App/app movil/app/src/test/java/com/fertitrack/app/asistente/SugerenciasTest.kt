package com.fertitrack.app.asistente

import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.robot
import org.junit.Assert.assertTrue
import org.junit.Test

class SugerenciasTest {

    @Test
    fun cadaPantallaTieneDosOTres() {
        Destino.entries.forEach { destino ->
            val sugerencias = Sugerencias.para(destino, null)
            assertTrue("$destino: $sugerencias", sugerencias.size in 2..3)
            sugerencias.forEach { assertTrue(destino.name, it.isNotBlank()) }
        }
    }

    @Test
    fun conZonasSecasZonasLasSugiere() {
        val r = robot(totalZonas = 2, zonas = listOf(NivelSuelo.SECA, NivelSuelo.HUMEDA))
        val sugerencias = Sugerencias.para(Destino.ZONAS, r)
        assertTrue(sugerencias.toString(), sugerencias.any { it.contains("secas") })
    }
}
