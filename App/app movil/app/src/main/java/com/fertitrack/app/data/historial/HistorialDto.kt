package com.fertitrack.app.data.historial

import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.BombeoRiego
import com.fertitrack.app.domain.sesion.EventoSesion
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import com.fertitrack.app.domain.sesion.TipoEvento
import kotlinx.serialization.Serializable

/**
 * Archivo del historial. Los enumerados se guardan por nombre y se leen con tolerancia: un
 * nombre desconocido (de otra versión) no hace fallar la carga. La sugerencia de fertilizar
 * no se guarda: solo tiene sentido justo después del riego.
 */
@Serializable
internal data class HistorialDto(
    val version: Int = VERSION_ACTUAL,
    val eventos: List<EventoDto> = emptyList(),
    val muestras: List<MuestraDto> = emptyList(),
    val riegos: List<RiegoDto> = emptyList(),
    val bombeos: List<BombeoDto> = emptyList(),
    val riegosCompletados: Int = 0,
    val totalMediciones: Int = 0,
    val humedadPromedio: Float? = null,
) {
    fun aDominio(): ResumenSesion = ResumenSesion(
        eventos = eventos.map { EventoSesion(it.momentoMs, enumO(it.tipo, TipoEvento.AVISO), it.texto) },
        muestras = muestras.map { MuestraHumedad(it.momentoMs, it.zona, it.humedadPct, it.adc, enumO(it.nivel, NivelSuelo.DESCONOCIDO)) },
        riegos = riegos.map { RiegoRegistrado(it.inicioMs, it.finMs, it.zonasRegadas, it.completado) },
        bombeos = bombeos.map { BombeoRiego(it.zonas, it.inicioMs, it.finMs, it.interrumpido) },
        riegosCompletados = riegosCompletados,
        totalMediciones = totalMediciones,
        humedadPromedio = humedadPromedio,
    )

    companion object {
        const val VERSION_ACTUAL = 1

        fun desde(r: ResumenSesion) = HistorialDto(
            eventos = r.eventos.map { EventoDto(it.momentoMs, it.tipo.name, it.texto) },
            muestras = r.muestras.map { MuestraDto(it.momentoMs, it.zona, it.humedadPct, it.adc, it.nivel.name) },
            riegos = r.riegos.map { RiegoDto(it.inicioMs, it.finMs, it.zonasRegadas, it.completado) },
            bombeos = r.bombeos.map { BombeoDto(it.zonas, it.inicioMs, it.finMs, it.interrumpido) },
            riegosCompletados = r.riegosCompletados,
            totalMediciones = r.totalMediciones,
            humedadPromedio = r.humedadPromedio,
        )

        private inline fun <reified E : Enum<E>> enumO(nombre: String, porDefecto: E): E =
            enumValues<E>().firstOrNull { it.name == nombre } ?: porDefecto
    }
}

@Serializable
internal data class EventoDto(val momentoMs: Long, val tipo: String, val texto: String)

@Serializable
internal data class MuestraDto(val momentoMs: Long, val zona: Int? = null, val humedadPct: Float, val adc: Int, val nivel: String)

@Serializable
internal data class RiegoDto(val inicioMs: Long, val finMs: Long, val zonasRegadas: List<Int>, val completado: Boolean)

@Serializable
internal data class BombeoDto(val zonas: List<Int>, val inicioMs: Long, val finMs: Long, val interrumpido: Boolean)
