package com.fertitrack.app.data.remote

import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.TipoFalla
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON de /api/estado tal como lo arma ruta_estado() del Maestro v5.3.
 *
 * Solo "estado" es obligatorio: sin él la respuesta no se considera del robot. El resto usa
 * valores por defecto seguros si falta o llega null (−1 = sin dato, DESCONOCIDO para códigos).
 * ArduinoJson escribe los float enteros sin decimales ("100", "-1"): Float los acepta.
 */
@Serializable
internal data class EstadoDto(
    @SerialName("estado") val estado: String,
    @SerialName("modo_rutina") val modoRutina: String = "?",
    @SerialName("zona_actual") val zonaActual: Int = 0,
    @SerialName("total_zonas") val totalZonas: Int = 0,
    @SerialName("distancia_cm") val distanciaCm: Float = 0f,
    @SerialName("ancho_zona_cm") val anchoZonaCm: Float = 0f,
    @SerialName("pasos_por_cm") val pasosPorCm: Float = 0f,
    @SerialName("posicion_pasos") val posicionPasos: Long = 0L,
    @SerialName("humedad_adc") val humedadAdc: Int = SIN_DATO,
    @SerialName("humedad_pct") val humedadPct: Float = SIN_DATO.toFloat(),
    @SerialName("cal_seco") val calSeco: Int = 0,
    @SerialName("cal_mojado") val calMojado: Int = 0,
    @SerialName("pct_seca") val pctSeca: Int = 0,
    @SerialName("pct_humeda") val pctHumeda: Int = 0,
    @SerialName("pct_sobre") val pctSobre: Int = 0,
    @SerialName("umbral_seco") val umbralSeco: Int = 0,
    @SerialName("umbral_humedo") val umbralHumedo: Int = 0,
    @SerialName("umbral_sobre") val umbralSobre: Int = 0,
    @SerialName("nivel_suelo") val nivelSuelo: Int = 0,
    @SerialName("actividad") val actividad: Int = SIN_DATO,
    @SerialName("falla") val falla: Int = 0,
    @SerialName("esp_b_conectada") val espBConectada: Boolean = false,
    @SerialName("esp_c_conectada") val espCConectada: Boolean = false,
    @SerialName("auto_activo") val autoActivo: Boolean = false,
    @SerialName("descanso_seg") val descansoSeg: Long = 0L,
    @SerialName("proximo_ciclo_seg") val proximoCicloSeg: Long = SIN_DATO.toLong(),
    @SerialName("zonas") val zonas: List<Int> = emptyList(),
) {
    fun aDominio(): EstadoRobot = EstadoRobot(
        estado = EstadoMaquina.desdeCodigo(estado),
        estadoTexto = estado,
        modoRutina = ModoRutina.desdeCodigo(modoRutina),
        // El firmware no reinicia zonaActual al terminar y puede enviar total_zonas + 1
        // (docs/PETICIONES_FIRMWARE.md): se acota para no mostrar "zona 7 de 6".
        zonaActual = zonaActual.coerceIn(0, maxOf(totalZonas, 0)),
        totalZonas = totalZonas,
        distanciaCm = finitoOCero(distanciaCm),
        anchoZonaCm = finitoOCero(anchoZonaCm),
        pasosPorCm = finitoOCero(pasosPorCm),
        posicionPasos = posicionPasos,
        humedadAdc = humedadAdc.takeIf { it >= 0 },
        humedadPct = humedadPct.takeIf { it.isFinite() && it >= 0f },
        calibracion = CalibracionSensor(
            adcSeco = calSeco,
            adcMojado = calMojado,
            pctSeca = pctSeca,
            pctHumeda = pctHumeda,
            pctSobre = pctSobre,
            umbralSeco = umbralSeco,
            umbralHumedo = umbralHumedo,
            umbralSobre = umbralSobre,
        ),
        nivelSuelo = NivelSuelo.desdeCodigo(nivelSuelo),
        actividad = Actividad.desdeCodigo(actividad),
        falla = TipoFalla.desdeCodigo(falla),
        codigoFalla = falla,
        espBConectada = espBConectada,
        espCConectada = espCConectada,
        autoActivo = autoActivo,
        descansoSeg = descansoSeg,
        // Si se inicia una rutina a mano con el automático activo, el firmware sigue
        // descontando durante la rutina y reprograma al terminar: fuera de ESPERANDO
        // esa cuenta no significa nada y no se muestra.
        proximoCicloSeg = proximoCicloSeg.takeIf { it >= 0 && EstadoMaquina.desdeCodigo(estado) == EstadoMaquina.ESPERANDO },
        zonas = zonas.map(NivelSuelo::desdeCodigo),
    )
}

/** El firmware usa −1 para "sin lectura" / "sin espera programada". */
private const val SIN_DATO = -1

private fun finitoOCero(valor: Float): Float = if (valor.isFinite()) valor else 0f
