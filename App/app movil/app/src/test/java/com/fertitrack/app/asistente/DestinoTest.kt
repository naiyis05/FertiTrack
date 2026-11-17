package com.fertitrack.app.asistente

import com.fertitrack.app.ui.Pestana
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinoTest {

    /**
     * FertiTrackPantalla traduce la pestaña con Destino.valueOf(pestana.name): una pestaña nueva
     * sin su Destino haría fallar la app al abrirla. Esta prueba falla antes.
     */
    @Test
    fun cadaPestanaTieneSuDestinoConElMismoTitulo() {
        Pestana.entries.forEach { pestana ->
            assertEquals(pestana.name, pestana.titulo, Destino.valueOf(pestana.name).titulo)
        }
    }

    @Test
    fun losDestinosSonLasPestanasMasConexion() {
        assertEquals(
            Pestana.entries.map { it.name } + Destino.CONEXION.name,
            Destino.entries.map { it.name },
        )
    }
}
