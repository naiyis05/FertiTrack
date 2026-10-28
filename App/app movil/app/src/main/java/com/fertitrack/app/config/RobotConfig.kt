package com.fertitrack.app.config

import com.fertitrack.app.BuildConfig

/**
 * Parámetros del robot y de la comunicación.
 *
 * IP y SSID vienen de gradle.properties; la clave del AP, de local.properties
 * (no versionado). Ningún otro archivo debe repetir estos valores.
 */
object RobotConfig {
    val IP_ROBOT: String = BuildConfig.ROBOT_IP
    val SSID_AP: String = BuildConfig.AP_SSID

    /** Vacía si no se configuró: entonces solo funciona la conexión manual al Wi-Fi. */
    val CLAVE_AP: String = BuildConfig.AP_PASSWORD

    const val PUERTO_HTTP = 80
    val URL_BASE_ROBOT: String get() = "http://$IP_ROBOT"

    /** El servidor del ESP32 atiende una petición a la vez: nunca más rápido que esto. */
    const val INTERVALO_SONDEO_MS = 1_500L

    /** Tiempo máximo de cada petición (conexión + respuesta). */
    const val TIMEOUT_MS = 3_000L

    /** Tope del backoff entre reintentos de /api/estado. */
    const val BACKOFF_MAXIMO_MS = 10_000L

    /** Mediciones guardadas: ≈ 24 h con el automático cada 30 min sobre 6 zonas. */
    const val MAX_PUNTOS_GRAFICA = 300
    const val MAX_EVENTOS = 30
}
