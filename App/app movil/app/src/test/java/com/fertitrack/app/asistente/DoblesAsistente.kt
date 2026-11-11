package com.fertitrack.app.asistente

import com.fertitrack.app.asistente.gemini.Cerebro
import com.fertitrack.app.asistente.gemini.RespuestaCerebro
import com.fertitrack.app.asistente.gemini.TurnoChat
import com.fertitrack.app.asistente.voz.EntradaVoz
import com.fertitrack.app.asistente.voz.EstadoEscucha
import com.fertitrack.app.asistente.voz.SalidaVoz
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun ok(texto: String) = RespuestaCerebro.Ok(
    texto,
    emptyList(),
    null,
    JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(texto))))),
)

/** Gemini de mentira: responde lo encolado; si hay una puerta para la llamada, espera a que se abra. */
class CerebroFalso : Cerebro {
    val respuestas = ArrayDeque<RespuestaCerebro>()
    val puertas = ArrayDeque<CompletableDeferred<Unit>>()
    var llamadas = 0
    var ultimoHistorial: List<TurnoChat> = emptyList()
    var ultimoContexto = ""

    override suspend fun responder(historial: List<TurnoChat>, contexto: String): RespuestaCerebro {
        llamadas++
        ultimoHistorial = historial
        ultimoContexto = contexto
        puertas.removeFirstOrNull()?.await()
        return respuestas.removeFirstOrNull() ?: ok("Respuesta $llamadas")
    }
}

/** Reconocimiento de voz de mentira: la prueba fija [estado]; las llamadas quedan en [registro]. */
class EntradaVozFalsa(private val registro: MutableList<String>) : EntradaVoz {
    override var disponible = true
    override val estado = MutableStateFlow<EstadoEscucha>(EstadoEscucha.Inactiva)

    override fun empezar() {
        registro += "entrada.empezar"
        estado.value = EstadoEscucha.Escuchando("", 0f)
    }

    override fun detener() {
        registro += "entrada.detener"
    }

    override fun cancelar() {
        registro += "entrada.cancelar"
        estado.value = EstadoEscucha.Inactiva
    }

    override fun liberar() {
        registro += "entrada.liberar"
    }
}

/** Síntesis de voz de mentira: guarda lo dicho. */
class SalidaVozFalsa(private val registro: MutableList<String>) : SalidaVoz {
    override val hablando = MutableStateFlow(false)
    val dichos = mutableListOf<String>()

    override fun hablar(texto: String) {
        registro += "salida.hablar"
        dichos += texto
        hablando.value = true
    }

    override fun detener() {
        registro += "salida.detener"
        hablando.value = false
    }

    override fun liberar() {
        registro += "salida.liberar"
    }
}
