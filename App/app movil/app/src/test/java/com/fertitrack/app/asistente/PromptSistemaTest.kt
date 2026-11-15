package com.fertitrack.app.asistente

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSistemaTest {

    private val texto = PromptSistema.TEXTO

    @Test
    fun explicaCadaEtiquetaQueLaAppEntiende() {
        Destino.entries.forEach { assertTrue(it.name, texto.contains("[[ir:${it.name}]]")) }
        RutinaPropuesta.entries.forEach { assertTrue(it.name, texto.contains("[[rutina:${it.name}]]")) }
    }

    @Test
    fun reglasDeSeguridadYDatos() {
        assertTrue(texto.contains("DETENER"))
        assertTrue(texto.contains("de cuándo son los datos"))
        assertTrue(texto.contains("http://192.168.4.1"))
    }

    @Test
    fun incluyeLaGuiaDeLechugaConElNombreRealDelUmbral() {
        assertTrue(texto.endsWith(ConocimientoLechuga.TEXTO))
        // Debe coincidir con la etiqueta de la página web del Maestro v6.2.
        assertTrue(texto.contains("SECA si humedad < (%)"))
    }
}
