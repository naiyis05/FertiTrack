package com.fertitrack.app.data.remote

import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ResultadoComando
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Traduce las respuestas HTTP del Maestro a tipos de la app. Lógica pura: nunca lanza
 * excepciones por un cuerpo raro (no JSON, JSON truncado, tipos inesperados).
 */
internal object ParserMaestro {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Estado validado, o null si [cuerpo] no es un JSON de /api/estado. */
    fun parsearEstado(cuerpo: String): EstadoRobot? = try {
        json.decodeFromString(EstadoDto.serializer(), cuerpo).aDominio()
    } catch (e: IllegalArgumentException) {
        // SerializationException hereda de IllegalArgumentException.
        null
    }

    fun interpretarEstado(codigoHttp: Int, cuerpo: String): ConsultaEstado {
        if (codigoHttp != HTTP_OK) {
            val detalle = textoError(objetoJson(cuerpo)) ?: "HTTP $codigoHttp"
            return ConsultaEstado.Fallida("El robot respondió con error a /api/estado ($detalle)")
        }
        val estado = parsearEstado(cuerpo)
            ?: return ConsultaEstado.Fallida("El robot envió un estado que no se pudo leer")
        return ConsultaEstado.Correcta(estado)
    }

    /**
     * 200 + ok:true → Exito; 409 → Ocupado; 400 → Rechazado; 404, 500 y el resto → ErrorRobot.
     * Un cuerpo que no es un objeto JSON siempre es ErrorRobot.
     */
    fun interpretarOrden(codigoHttp: Int, cuerpo: String): ResultadoComando {
        val objeto = objetoJson(cuerpo)
            ?: return ResultadoComando.ErrorRobot(codigoHttp, "Respuesta no válida del robot: ${recortar(cuerpo)}")
        val error = textoError(objeto)
        return when (codigoHttp) {
            HTTP_OK -> {
                val ok = objeto["ok"] as? JsonPrimitive
                if (ok != null && !ok.isString && ok.booleanOrNull == true) {
                    ResultadoComando.Exito(
                        mensaje = (objeto["mensaje"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                        datos = camposSimples(objeto),
                    )
                } else {
                    ResultadoComando.ErrorRobot(codigoHttp, error ?: "Respuesta inesperada del robot: ${recortar(cuerpo)}")
                }
            }
            HTTP_CONFLICTO -> ResultadoComando.Ocupado(error ?: "El robot no puede hacerlo en este momento")
            HTTP_PETICION_INCORRECTA -> ResultadoComando.Rechazado(error ?: "El robot rechazó los parámetros")
            else -> ResultadoComando.ErrorRobot(codigoHttp, error ?: "HTTP $codigoHttp")
        }
    }

    private fun objetoJson(cuerpo: String): JsonObject? = try {
        json.parseToJsonElement(cuerpo) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun textoError(objeto: JsonObject?): String? =
        (objeto?.get("error") as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /** Los campos primitivos que no son "ok" ni "mensaje", como texto (p. ej. num_zonas → "6"). */
    private fun camposSimples(objeto: JsonObject): Map<String, String> =
        objeto.filter { (clave, valor) -> clave != "ok" && clave != "mensaje" && valor.esPrimitivoNoNulo() }
            .mapValues { (_, valor) -> (valor as JsonPrimitive).content }

    private fun JsonElement.esPrimitivoNoNulo() = this is JsonPrimitive && this !is JsonNull

    private fun recortar(texto: String): String {
        val limpio = texto.trim().replace(Regex("\\s+"), " ")
        return when {
            limpio.isEmpty() -> "(vacía)"
            limpio.length <= MAX_TEXTO -> limpio
            else -> limpio.take(MAX_TEXTO) + "…"
        }
    }

    private const val HTTP_OK = 200
    private const val HTTP_PETICION_INCORRECTA = 400
    private const val HTTP_CONFLICTO = 409
    private const val MAX_TEXTO = 120
}
