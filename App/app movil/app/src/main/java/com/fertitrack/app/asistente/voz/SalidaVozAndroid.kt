package com.fertitrack.app.asistente.voz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Síntesis de voz del sistema ([TextToSpeech]), sin servicios de terceros. Lo pedido antes
 * de que el motor termine de arrancar se dice en cuanto está listo. Se llama desde el hilo
 * principal.
 */
class SalidaVozAndroid(contexto: Context) : SalidaVoz {

    private val principal = Handler(Looper.getMainLooper())

    private val _hablando = MutableStateFlow(false)
    override val hablando: StateFlow<Boolean> = _hablando.asStateFlow()

    private var listo = false
    private var pendiente: String? = null
    private var contador = 0L

    /** Solo el fin del último trozo de lo último pedido apaga [hablando]. */
    @Volatile
    private var ultimoId: String? = null

    private val tts = TextToSpeech(contexto.applicationContext) { estado -> principal.post { alIniciar(estado) } }

    private fun alIniciar(estado: Int) {
        if (estado != TextToSpeech.SUCCESS) return
        // Si falta el español, sigue con la voz que tenga el motor.
        tts.setLanguage(idiomaVoz())
        tts.setSpeechRate(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == ultimoId) _hablando.value = false
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == ultimoId) _hablando.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == ultimoId) _hablando.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == ultimoId) _hablando.value = false
            }
        })
        listo = true
        pendiente?.let {
            pendiente = null
            hablar(it)
        }
    }

    override fun hablar(texto: String) {
        val trozos = TextosVoz.partir(TextosVoz.limpiarParaVoz(texto))
        if (trozos.isEmpty()) return
        if (!listo) {
            pendiente = texto
            return
        }
        ultimoId = null
        tts.stop()
        trozos.forEachIndexed { i, trozo ->
            val id = "fertitrack-${contador++}"
            if (i == trozos.lastIndex) ultimoId = id
            tts.speak(trozo, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        _hablando.value = true
    }

    override fun detener() {
        pendiente = null
        ultimoId = null
        if (listo) tts.stop()
        _hablando.value = false
    }

    override fun liberar() {
        detener()
        tts.shutdown()
        listo = false
    }
}
