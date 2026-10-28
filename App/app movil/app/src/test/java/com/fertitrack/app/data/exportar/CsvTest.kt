package com.fertitrack.app.data.exportar

import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.BombeoRiego
import com.fertitrack.app.domain.sesion.MuestraHumedad
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    private val utc = ZoneOffset.UTC
    private val BOM = Char(0xFEFF)
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 28, h, m, s).toInstant(utc).toEpochMilli()

    @Test
    fun medicionesConPuntoYComaYComaDecimal() {
        val csv = Csv.mediciones(
            listOf(
                MuestraHumedad(ms(9, 41, 5), zona = 3, humedadPct = 47.25f, adc = 2873, nivel = NivelSuelo.INTERMEDIA),
                MuestraHumedad(ms(9, 45), zona = null, humedadPct = 88f, adc = 1500, nivel = NivelSuelo.SOBRE_HUMEDA),
            ),
            utc,
        )
        assertEquals(
            "${BOM}Fecha;Hora;Zona;Humedad (%);ADC;Nivel\r\n" +
                "2026-10-28;09:41:05;3;47,3;2873;Intermedia\r\n" +
                "2026-10-28;09:45:00;Prueba de sensor;88,0;1500;Sobre-húmeda\r\n",
            csv,
        )
    }

    @Test
    fun riegosConDuracionYEstado() {
        val csv = Csv.riegos(
            listOf(
                BombeoRiego(listOf(2, 3), ms(10, 0), ms(10, 0, 12), interrumpido = false),
                BombeoRiego(listOf(5), ms(10, 5), ms(10, 5, 4), interrumpido = true),
            ),
            utc,
        )
        assertEquals(
            "${BOM}Fecha;Inicio;Fin;Duración (s);Zonas;Estado\r\n" +
                "2026-10-28;10:00:00;10:00:12;12;2 3;Completado\r\n" +
                "2026-10-28;10:05:00;10:05:04;4;5;Interrumpido\r\n",
            csv,
        )
    }

    @Test
    fun sinDatosSoloLaCabecera() {
        assertEquals("${BOM}Fecha;Hora;Zona;Humedad (%);ADC;Nivel\r\n", Csv.mediciones(emptyList(), utc))
    }
}
