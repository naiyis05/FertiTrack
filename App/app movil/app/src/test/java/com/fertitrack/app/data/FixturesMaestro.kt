package com.fertitrack.app.data

import com.fertitrack.app.data.remote.ParserMaestro
import com.fertitrack.app.domain.modelo.EstadoRobot

/** Respuestas reales del Maestro v5.3 (formato de ruta_estado()). */
internal object FixturesMaestro {

    /** Ciclo en curso: sensando la zona 3 de 6, con lectura válida. */
    const val ESTADO_SENSANDO = """{"estado":"SENSADO_MIDIENDO","modo_rutina":"SENSADO","zona_actual":3,
        "total_zonas":6,"distancia_cm":300,"ancho_zona_cm":50,"pasos_por_cm":100,"posicion_pasos":15000,
        "humedad_adc":2650,"humedad_pct":49.91364,"cal_seco":4095,"cal_mojado":1200,"pct_seca":35,
        "pct_humeda":65,"pct_sobre":90,"umbral_seco":3082,"umbral_humedo":2213,"umbral_sobre":1490,
        "nivel_suelo":2,"actividad":1,"falla":0,"esp_b_conectada":true,"esp_c_conectada":true,
        "auto_activo":false,"descanso_seg":60,"proximo_ciclo_seg":-1,"zonas":[1,2,0,0,0,0]}"""

    /** Recién encendido: sin lectura, sin espera programada, sin zonas. */
    const val ESTADO_SIN_DATOS = """{"estado":"ESPERANDO","modo_rutina":"NINGUNA","zona_actual":0,
        "total_zonas":0,"distancia_cm":0,"ancho_zona_cm":2.5,"pasos_por_cm":100,"posicion_pasos":0,
        "humedad_adc":-1,"humedad_pct":-1,"cal_seco":4095,"cal_mojado":1200,"pct_seca":35,
        "pct_humeda":65,"pct_sobre":90,"umbral_seco":3082,"umbral_humedo":2213,"umbral_sobre":1490,
        "nivel_suelo":0,"actividad":0,"falla":0,"esp_b_conectada":true,"esp_c_conectada":true,
        "auto_activo":false,"descanso_seg":60,"proximo_ciclo_seg":-1,"zonas":[]}"""

    /** En espera, configurado y sin falla: se permiten rutinas. */
    const val ESTADO_LISTO = """{"estado":"ESPERANDO","modo_rutina":"NINGUNA","zona_actual":1,
        "total_zonas":6,"distancia_cm":300,"ancho_zona_cm":50,"pasos_por_cm":100,"posicion_pasos":0,
        "humedad_adc":3300,"humedad_pct":27.46114,"cal_seco":4095,"cal_mojado":1200,"pct_seca":35,
        "pct_humeda":65,"pct_sobre":90,"umbral_seco":3082,"umbral_humedo":2213,"umbral_sobre":1490,
        "nivel_suelo":1,"actividad":0,"falla":0,"esp_b_conectada":true,"esp_c_conectada":true,
        "auto_activo":true,"descanso_seg":120,"proximo_ciclo_seg":95,"zonas":[1,2,3,4,1,2]}"""

    /** Parada desde la app (falla 8). */
    const val ESTADO_EMERGENCIA = """{"estado":"EMERGENCIA","modo_rutina":"NINGUNA","zona_actual":2,
        "total_zonas":6,"distancia_cm":300,"ancho_zona_cm":50,"pasos_por_cm":100,"posicion_pasos":10000,
        "humedad_adc":3300,"humedad_pct":27.46114,"nivel_suelo":1,"actividad":0,"falla":8,
        "esp_b_conectada":true,"esp_c_conectada":true,"auto_activo":false,"descanso_seg":60,
        "proximo_ciclo_seg":-1,"zonas":[1,0,0,0,0,0]}"""

    fun estado(json: String): EstadoRobot = checkNotNull(ParserMaestro.parsearEstado(json)) { "fixture inválida" }
}
