package com.fertitrack.app.data.remote

import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import java.math.BigDecimal

/**
 * Órdenes del Maestro v6 que usa la app, con sus parámetros de consulta exactamente como los
 * leen las funciones ruta_* del firmware (server.arg). Los opcionales que valen null no se
 * envían, y entonces el firmware conserva su valor actual.
 */
sealed class OrdenMaestro(
    /** Ruta sin barra inicial, p. ej. "api/iniciar". */
    val ruta: String,
) {
    open val parametros: List<Pair<String, String>> get() = emptyList()

    /** /api/iniciar?modo=ciclo|sensado|fertilizar (solo en ESPERANDO; si no, 409). */
    data class Iniciar(val modo: ModoInicio) : OrdenMaestro("api/iniciar") {
        override val parametros get() = listOf("modo" to modo.parametro)
    }

    /** /api/detener: siempre 200 y declara la falla 8. */
    data object Detener : OrdenMaestro("api/detener")

    /** /api/reiniciar: siempre 200, pero solo actúa en EMERGENCIA (y mueve el robot). */
    data object Reiniciar : OrdenMaestro("api/reiniciar")

    /** /api/auto?activar=0|1[&descanso_seg=N]; el firmware ignora descanso_seg <= 0. */
    data class Auto(val activar: Boolean, val descansoSeg: Long? = null) : OrdenMaestro("api/auto") {
        override val parametros get() = buildList {
            add("activar" to if (activar) "1" else "0")
            if (descansoSeg != null) add("descanso_seg" to descansoSeg.toString())
        }
    }

    /** /api/configurar?distancia_cm=X[&ancho_zona_cm=Y][&pasos_cm=Z] (solo en ESPERANDO). */
    data class Configurar(
        val distanciaCm: Float,
        val anchoZonaCm: Float? = null,
        val pasosPorCm: Float? = null,
    ) : OrdenMaestro("api/configurar") {
        override val parametros get() = buildList {
            add("distancia_cm" to numero(distanciaCm))
            if (anchoZonaCm != null) add("ancho_zona_cm" to numero(anchoZonaCm))
            // El firmware lo llama pasos_cm (no pasos_por_cm, que es el nombre en /api/estado).
            if (pasosPorCm != null) add("pasos_cm" to numero(pasosPorCm))
        }
    }

    /** /api/prueba_mov?cm=1..150: deja el robot en PRUEBA_LISTA. */
    data class PruebaMovimiento(val cm: Float) : OrdenMaestro("api/prueba_mov") {
        override val parametros get() = listOf("cm" to numero(cm))
    }

    /** /api/calibrar_distancia?real_cm=X[&pedido_cm=Y] (en PRUEBA_LISTA o ESPERANDO). */
    data class CalibrarDistancia(val realCm: Float, val pedidoCm: Float? = null) :
        OrdenMaestro("api/calibrar_distancia") {
        override val parametros get() = buildList {
            add("real_cm" to numero(realCm))
            if (pedidoCm != null) add("pedido_cm" to numero(pedidoCm))
        }
    }

    /** /api/prueba_retorno: solo después de /api/prueba_mov. */
    data object PruebaRetorno : OrdenMaestro("api/prueba_retorno")

    /** /api/prueba_sensor: 409 si ESP_B no está conectada; mide unos 8 s. */
    data object PruebaSensor : OrdenMaestro("api/prueba_sensor")

    /**
     * /api/cal_sensor[?punto=seco|mojado[&adc=N]][&pct_seca=a&pct_humeda=b&pct_sobre=c]
     * (solo en ESPERANDO). Sin [adc], el firmware usa la última lectura; el firmware solo
     * lee adc si también llega punto.
     */
    data class CalibrarSensor(
        val punto: PuntoCalibracion? = null,
        val adc: Int? = null,
        val pctSeca: Int? = null,
        val pctHumeda: Int? = null,
        val pctSobre: Int? = null,
    ) : OrdenMaestro("api/cal_sensor") {
        override val parametros get() = buildList {
            if (punto != null) add("punto" to punto.parametro)
            if (adc != null) add("adc" to adc.toString())
            if (pctSeca != null) add("pct_seca" to pctSeca.toString())
            if (pctHumeda != null) add("pct_humeda" to pctHumeda.toString())
            if (pctSobre != null) add("pct_sobre" to pctSobre.toString())
        }
    }

    /**
     * Motivo por el que la orden no se puede codificar (NaN o infinito), o null si está bien.
     * El resto de validaciones las hace el firmware, que es la fuente de verdad.
     */
    fun problemaParametros(): String? {
        val numeros: List<Float?> = when (this) {
            is Configurar -> listOf(distanciaCm, anchoZonaCm, pasosPorCm)
            is PruebaMovimiento -> listOf(cm)
            is CalibrarDistancia -> listOf(realCm, pedidoCm)
            else -> emptyList()
        }
        return if (numeros.any { it != null && !it.isFinite() }) {
            "Valor numérico no válido: no se envió la orden"
        } else {
            null
        }
    }

    internal companion object {
        /**
         * Número decimal con punto, sin exponente ni ceros de más: 300f → "300", 2.5f → "2.5".
         * El firmware lo lee con String.toFloat() (atof), que no entiende comas.
         */
        fun numero(valor: Float): String {
            require(valor.isFinite()) { "Valor no finito: $valor" }
            return BigDecimal(valor.toString()).stripTrailingZeros().toPlainString()
        }
    }
}
