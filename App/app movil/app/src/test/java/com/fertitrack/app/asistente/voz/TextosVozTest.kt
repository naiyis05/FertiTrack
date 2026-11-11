package com.fertitrack.app.asistente.voz

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextosVozTest {

    @Test
    fun mensajesDeError() {
        assertTrue(TextosVoz.mensajeError(SpeechRecognizer.ERROR_NO_MATCH).contains("No te entendí"))
        assertTrue(TextosVoz.mensajeError(SpeechRecognizer.ERROR_NETWORK).contains("internet"))
        assertTrue(TextosVoz.mensajeError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT).contains("internet"))
        assertTrue(TextosVoz.mensajeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("micrófono"))
        assertTrue(TextosVoz.mensajeError(99).isNotBlank())
    }

    @Test
    fun limpiarParaVozQuitaMarkdownYUrls() {
        assertEquals("Hola ya ve a y Título", TextosVoz.limpiarParaVoz("**Hola** _ya_ ve a https://x.com/a y # Título"))
        assertEquals("Mira la guía de riego.", TextosVoz.limpiarParaVoz("Mira la [guía de riego](https://ejemplo.com/guia)."))
        assertEquals("Usa el botón Regar", TextosVoz.limpiarParaVoz("Usa el botón `Regar`"))
    }

    @Test
    fun partirRespetaElMaximoYCortaEnFinDeFrase() {
        val texto = (1..600).joinToString("") { "Frase número $it. " }
        assertTrue(texto.length > 9_000)
        val trozos = TextosVoz.partir(texto)
        assertTrue(trozos.size >= 3)
        trozos.forEach { assertTrue(it.length.toString(), it.length <= TextosVoz.MAX_TROZO) }
        assertEquals(texto, trozos.joinToString(""))
        trozos.dropLast(1).forEach { assertTrue(it.takeLast(5), it.endsWith(". ")) }
    }

    @Test
    fun partirSinFinesDeFraseCortaAlMaximo() {
        val texto = "a".repeat(9_000)
        val trozos = TextosVoz.partir(texto)
        assertEquals(listOf(3_900, 3_900, 1_200), trozos.map { it.length })
        assertEquals(texto, trozos.joinToString(""))
    }

    @Test
    fun textoCortoEsUnSoloTrozo() {
        assertEquals(listOf("Hola."), TextosVoz.partir("Hola."))
        assertEquals(emptyList<String>(), TextosVoz.partir(""))
    }
}
