package com.fertitrack.app.data.exportar

import com.fertitrack.app.domain.sesion.BombeoRiego
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.traduccion.Traductor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * CSV para abrir con doble clic en Excel en español: separador «;», coma decimal, BOM UTF-8
 * (tildes bien) y fin de línea CRLF. Google Sheets y LibreOffice también lo leen.
 */
object Csv {
    private const val BOM = "﻿"
    private const val FIN = "\r\n"
    private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun mediciones(muestras: List<MuestraHumedad>, zona: ZoneId = ZoneId.systemDefault()): String = documento(
        listOf("Fecha", "Hora", "Zona", "Humedad (%)", "ADC", "Nivel"),
        muestras.map { m ->
            val momento = Instant.ofEpochMilli(m.momentoMs).atZone(zona)
            listOf(
                FECHA.format(momento),
                HORA.format(momento),
                m.zona?.toString() ?: "Prueba de sensor",
                decimal(m.humedadPct),
                m.adc.toString(),
                Traductor.nombreNivel(m.nivel),
            )
        },
    )

    fun riegos(bombeos: List<BombeoRiego>, zona: ZoneId = ZoneId.systemDefault()): String = documento(
        listOf("Fecha", "Inicio", "Fin", "Duración (s)", "Zonas", "Estado"),
        bombeos.map { b ->
            val inicio = Instant.ofEpochMilli(b.inicioMs).atZone(zona)
            listOf(
                FECHA.format(inicio),
                HORA.format(inicio),
                HORA.format(Instant.ofEpochMilli(b.finMs).atZone(zona)),
                ((b.finMs - b.inicioMs) / 1000.0).roundToLong().toString(),
                b.zonas.joinToString(" "),
                if (b.interrumpido) "Interrumpido" else "Completado",
            )
        },
    )

    private fun documento(cabecera: List<String>, filas: List<List<String>>): String = buildString {
        append(BOM)
        (listOf(cabecera) + filas).forEach { fila -> append(fila.joinToString(";") { campo(it) }).append(FIN) }
    }

    private fun decimal(valor: Float): String = String.format(Locale.ROOT, "%.1f", valor).replace('.', ',')

    /** Entre comillas solo si hace falta (separador, comillas o saltos de línea). */
    private fun campo(texto: String): String =
        if (texto.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + texto.replace("\"", "\"\"") + "\"" else texto
}
