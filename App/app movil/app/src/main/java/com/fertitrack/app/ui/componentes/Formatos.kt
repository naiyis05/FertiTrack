package com.fertitrack.app.ui.componentes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Formatos de números, horas y duraciones para la interfaz. Funciones puras. */
object Formatos {
    const val SIN_DATO = "—"

    private val formatoHora: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val formatoHoraCorta: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val formatoFechaHoraCorta: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    /** "47 %" (sin decimales, como la página del Maestro) o "—". */
    fun porcentaje(valor: Float?): String =
        if (valor == null || valor.isNaN()) SIN_DATO else "${valor.roundToInt()} %"

    /** Número con hasta [decimales] decimales, sin ceros sobrantes: 200 → "200", 33.33 → "33,3". */
    fun decimal(valor: Float, decimales: Int = 1, locale: Locale = Locale.getDefault()): String {
        if (valor.isNaN() || valor.isInfinite()) return SIN_DATO
        val texto = String.format(locale, "%.${decimales}f", valor)
        if (decimales == 0) return texto
        val separador = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return texto.trimEnd('0').trimEnd(separador)
    }

    fun centimetros(cm: Float): String = "${decimal(cm)} cm"

    fun hora(momentoMs: Long, zona: ZoneId = ZoneId.systemDefault()): String =
        formatoHora.format(Instant.ofEpochMilli(momentoMs).atZone(zona))

    fun horaCorta(momentoMs: Long, zona: ZoneId = ZoneId.systemDefault()): String =
        formatoHoraCorta.format(Instant.ofEpochMilli(momentoMs).atZone(zona))

    /** "27/10 10:30". */
    fun fechaHoraCorta(momentoMs: Long, zona: ZoneId = ZoneId.systemDefault()): String =
        formatoFechaHoraCorta.format(Instant.ofEpochMilli(momentoMs).atZone(zona))

    /** Cuenta atrás "m:ss" (o "h:mm:ss" desde una hora). */
    fun cuentaAtras(segundos: Long): String {
        val s = segundos.coerceAtLeast(0)
        val horas = s / 3600
        val minutos = (s % 3600) / 60
        val resto = s % 60
        return if (horas > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, horas, minutos, resto)
        } else {
            "%d:%02d".format(Locale.ROOT, minutos, resto)
        }
    }

    /** Tiempo transcurrido legible: "12 s", "2 min 5 s", "1 h 3 min". */
    fun antiguedad(segundos: Long): String {
        val s = segundos.coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            else -> {
                val minutos = (s % 3600) / 60
                if (minutos == 0L) "${s / 3600} h" else "${s / 3600} h $minutos min"
            }
        }
    }

    /** Duración de un riego: "45 s", "6 min", "1 h 5 min". */
    fun duracion(ms: Long): String {
        val segundos = (abs(ms) / 1000.0).roundToLong()
        return when {
            segundos < 60 -> "$segundos s"
            segundos < 3600 -> {
                val minutos = segundos / 60
                val resto = segundos % 60
                if (resto == 0L || minutos >= 10) "$minutos min" else "$minutos min $resto s"
            }
            else -> antiguedad(segundos)
        }
    }

    /** "1, 2 y 5" para listas de zonas. */
    fun listaZonas(zonas: List<Int>): String = when (zonas.size) {
        0 -> "ninguna"
        1 -> zonas.first().toString()
        else -> zonas.dropLast(1).joinToString(", ") + " y " + zonas.last()
    }

    /** Segundos que faltan hasta [momentoMs] según [ahoraMs], redondeando hacia arriba. */
    fun segundosHasta(momentoMs: Long, ahoraMs: Long): Long {
        val restanteMs = momentoMs - ahoraMs
        return if (restanteMs <= 0) 0 else (restanteMs + 999) / 1000
    }
}
