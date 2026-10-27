package com.fertitrack.app.domain.conexion

/** Salud del sondeo de /api/estado. Nunca debe dejar la UI en un estado engañoso. */
sealed interface EstadoConexion {
    /** Sondeo detenido (app en segundo plano o aún no iniciado). */
    data object Pausado : EstadoConexion

    /** Sondeo activo pero todavía sin respuesta válida desde que se reanudó. */
    data object Conectando : EstadoConexion

    /** La última consulta respondió bien. */
    data object Conectado : EstadoConexion

    /** Fallaron 1 o 2 consultas seguidas; se reintenta al ritmo normal. */
    data class SinRespuesta(val fallosSeguidos: Int, val detalle: String) : EstadoConexion

    /** 3 o más fallos seguidos; se espera con backoff antes del próximo intento. */
    data class Reintentando(
        val fallosSeguidos: Int,
        /** Momento (reloj del sistema, ms) del próximo intento. */
        val proximoIntentoMs: Long,
        val detalle: String,
    ) : EstadoConexion
}

/** Cómo llegan las peticiones a la red del robot. */
sealed interface EstadoRed {
    /** Todavía no se buscó la red del robot. */
    data object Inactiva : EstadoRed

    /** Se pidió la red al sistema; Android está mostrando su diálogo o buscando el AP. */
    data object Solicitando : EstadoRed

    /** Hay una red Wi-Fi utilizable y las peticiones salen por ella. */
    data class Disponible(val modo: ModoEnlace) : EstadoRed

    /** El sistema no encontró el AP, el usuario canceló o se perdió la red. */
    data class NoDisponible(val motivo: String) : EstadoRed

    /** Faltan permisos de ejecución (nombres completos de android.permission.*). */
    data class PermisosFaltantes(val permisos: List<String>) : EstadoRed

    /** Simulador local en uso (solo builds debug): no se usa el Wi-Fi. */
    data object Simulador : EstadoRed
}

enum class ModoEnlace {
    /** La app pidió el AP con WifiNetworkSpecifier (Android 10+). */
    AUTOMATICO,

    /** El usuario se conectó al AP desde los ajustes de Wi-Fi. */
    MANUAL,
}
