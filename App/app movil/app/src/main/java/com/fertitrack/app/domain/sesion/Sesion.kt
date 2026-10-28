package com.fertitrack.app.domain.sesion

import com.fertitrack.app.domain.modelo.NivelSuelo

enum class TipoEvento { CONEXION, RUTINA, RIEGO, FERTILIZACION, MEDICION, FALLA, COMANDO, AVISO }

data class EventoSesion(
    val momentoMs: Long,
    val tipo: TipoEvento,
    val texto: String,
)

/** Una medición nueva del sensor (no una muestra por consulta: el robot mide por zona). */
data class MuestraHumedad(
    val momentoMs: Long,
    /** Zona medida (1..n); null si fue una prueba de sensor fuera de un sensado. */
    val zona: Int?,
    val humedadPct: Float,
    val adc: Int,
    val nivel: NivelSuelo,
)

data class RiegoRegistrado(
    val inicioMs: Long,
    val finMs: Long,
    /** Zonas que estaban SECAS al empezar el riego (1..n). */
    val zonasRegadas: List<Int>,
    /** false si terminó por una falla o una parada de emergencia. */
    val completado: Boolean,
)

/**
 * Un encendido de la bomba de riego, deducido de las consultas: inicio y fin tienen la
 * precisión del sondeo (≈ 1,5 s).
 */
data class BombeoRiego(
    /** Zonas del tramo regado (1..n): el firmware riega de una vez las zonas secas contiguas. */
    val zonas: List<Int>,
    val inicioMs: Long,
    val finMs: Long,
    /** true si se cortó por una falla o una parada de emergencia. */
    val interrumpido: Boolean,
)

data class ResumenSesion(
    /** Más reciente primero; como máximo RobotConfig.MAX_EVENTOS. */
    val eventos: List<EventoSesion> = emptyList(),
    /** Orden cronológico; como máximo RobotConfig.MAX_PUNTOS_GRAFICA. */
    val muestras: List<MuestraHumedad> = emptyList(),
    /** Orden cronológico. */
    val riegos: List<RiegoRegistrado> = emptyList(),
    /** Orden cronológico; como máximo RobotConfig.MAX_EVENTOS. */
    val bombeos: List<BombeoRiego> = emptyList(),
    val riegosCompletados: Int = 0,
    val totalMediciones: Int = 0,
    /** Promedio de TODAS las mediciones de la sesión (no solo las de la gráfica). */
    val humedadPromedio: Float? = null,
    /** true tras un ciclo que regó al menos una zona, hasta que el usuario responda. */
    val sugerirFertilizar: Boolean = false,
)
