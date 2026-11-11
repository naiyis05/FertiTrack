package com.fertitrack.app.asistente.voz

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reconocimiento de voz del sistema ([SpeechRecognizer]), con resultados parciales para
 * mostrar la transcripción en vivo. SpeechRecognizer solo funciona en el hilo principal.
 */
class EntradaVozAndroid(contexto: Context) : EntradaVoz {

    private val contexto = contexto.applicationContext
    private val principal = Handler(Looper.getMainLooper())

    private val _estado = MutableStateFlow<EstadoEscucha>(EstadoEscucha.Inactiva)
    override val estado: StateFlow<EstadoEscucha> = _estado.asStateFlow()

    override val disponible: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(contexto)

    private var reconocedor: SpeechRecognizer? = null
    private var parcial = ""

    /** Tras cancelar, el reconocedor avisa ERROR_CLIENT: no es un error para el usuario. */
    private var cancelado = false

    override fun empezar() = enPrincipal {
        val r = reconocedor ?: SpeechRecognizer.createSpeechRecognizer(contexto).also {
            it.setRecognitionListener(oyente)
            reconocedor = it
        }
        cancelado = false
        parcial = ""
        _estado.value = EstadoEscucha.Escuchando("", 0f)
        r.startListening(peticion())
    }

    override fun detener() = enPrincipal { reconocedor?.stopListening() }

    override fun cancelar() = enPrincipal {
        cancelado = true
        reconocedor?.cancel()
        _estado.value = EstadoEscucha.Inactiva
    }

    override fun liberar() = enPrincipal {
        cancelado = true
        reconocedor?.destroy()
        reconocedor = null
        _estado.value = EstadoEscucha.Inactiva
    }

    private fun peticion() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, idiomaVoz().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun enPrincipal(accion: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) accion() else principal.post(accion)
    }

    private fun primerResultado(resultados: Bundle?): String =
        resultados?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private val oyente = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _estado.value = EstadoEscucha.Escuchando(parcial, 0f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val actual = _estado.value
            if (actual is EstadoEscucha.Escuchando) _estado.value = actual.copy(nivel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texto = primerResultado(partialResults)
            if (texto.isEmpty()) return
            parcial = texto
            val actual = _estado.value
            _estado.value = if (actual is EstadoEscucha.Escuchando) actual.copy(parcial = texto) else EstadoEscucha.Escuchando(texto, 0f)
        }

        override fun onEndOfSpeech() {
            _estado.value = EstadoEscucha.Transcribiendo(parcial)
        }

        override fun onResults(results: Bundle?) {
            val texto = primerResultado(results)
            _estado.value = if (texto.isEmpty()) {
                EstadoEscucha.Error(TextosVoz.mensajeError(SpeechRecognizer.ERROR_NO_MATCH))
            } else {
                EstadoEscucha.Terminada(texto)
            }
        }

        override fun onError(error: Int) {
            if (cancelado && error == SpeechRecognizer.ERROR_CLIENT) return
            _estado.value = EstadoEscucha.Error(TextosVoz.mensajeError(error))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** Español del sistema si el teléfono está en español; si no, español de España. */
internal fun idiomaVoz(): Locale = Locale.getDefault().takeIf { it.language == "es" } ?: Locale.forLanguageTag("es-ES")
