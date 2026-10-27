package com.fertitrack.app.domain

import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.TipoFalla

/** Calibración por defecto del firmware: seco 3900, mojado 1200, 35/65/90 %. */
val CALIBRACION_PRUEBA = CalibracionSensor(
    adcSeco = 3900,
    adcMojado = 1200,
    pctSeca = 35,
    pctHumeda = 65,
    pctSobre = 90,
    umbralSeco = 2955,
    umbralHumedo = 2145,
    umbralSobre = 1470,
)

/** Construye un EstadoRobot coherente con valores por defecto de "en espera, 6 zonas". */
fun robot(
    estado: EstadoMaquina = EstadoMaquina.ESPERANDO,
    modo: ModoRutina = ModoRutina.NINGUNA,
    zona: Int = 0,
    totalZonas: Int = 6,
    zonas: List<NivelSuelo> = List(totalZonas) { NivelSuelo.SIN_MEDIR },
    adc: Int? = null,
    pct: Float? = null,
    falla: Int = 0,
    actividad: Actividad = Actividad.REPOSO,
    auto: Boolean = false,
    descanso: Long = 60,
    proximo: Long? = null,
    distanciaCm: Float = 300f,
    espB: Boolean = true,
    estadoTexto: String = estado.name,
): EstadoRobot = EstadoRobot(
    estado = estado,
    estadoTexto = estadoTexto,
    modoRutina = modo,
    zonaActual = zona,
    totalZonas = totalZonas,
    distanciaCm = distanciaCm,
    anchoZonaCm = if (totalZonas > 0) distanciaCm / totalZonas else 0f,
    pasosPorCm = 40f,
    posicionPasos = 0,
    humedadAdc = adc,
    humedadPct = pct,
    calibracion = CALIBRACION_PRUEBA,
    nivelSuelo = NivelSuelo.SIN_MEDIR,
    actividad = actividad,
    falla = TipoFalla.desdeCodigo(falla),
    codigoFalla = falla,
    espBConectada = espB,
    espCConectada = true,
    autoActivo = auto,
    descansoSeg = descanso,
    proximoCicloSeg = proximo,
    zonas = zonas,
)
