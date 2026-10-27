package com.fertitrack.app.data

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.PuntoCalibracion
import com.fertitrack.app.domain.modelo.ResultadoComando
import kotlinx.coroutines.flow.StateFlow

/**
 * Única puerta hacia el Maestro.
 *
 * Reglas: una sola petición en vuelo, sondeo de /api/estado cada ~1,5 s con
 * timeout de 3 s, backoff al fallar y pausa en segundo plano. Solo /api/estado
 * se reintenta; ninguna orden se reintenta automáticamente.
 */
interface RobotRepository {
    val conexion: StateFlow<EstadoConexion>
    val red: StateFlow<EstadoRed>

    /** Último estado recibido. Puede estar obsoleto: ver [ultimaRespuestaMs]. */
    val estado: StateFlow<EstadoRobot?>

    /** Momento (reloj del sistema, ms) de la última respuesta válida; null si nunca hubo. */
    val ultimaRespuestaMs: StateFlow<Long?>

    val usandoSimulador: StateFlow<Boolean>
    val simuladorDisponible: Boolean

    /** Arranca el sondeo (idempotente). Se llama al pasar a primer plano. */
    fun reanudarSondeo()

    /** Detiene el sondeo (idempotente). Se llama al pasar a segundo plano. */
    fun pausarSondeo()

    /** Pide al sistema la red del AP del robot (WifiNetworkSpecifier en Android 10+). */
    fun conectarWifiRobot()

    /** Vuelve a evaluar permisos y redes (tras conceder permisos o volver de Ajustes). */
    fun reevaluarRed()

    /** Activa o desactiva el simulador local; sin efecto si no está disponible. */
    fun usarSimulador(activo: Boolean)

    suspend fun iniciar(modo: ModoInicio): ResultadoComando

    /** Parada de emergencia: cancela la consulta en curso y se envía de inmediato. */
    suspend fun detener(): ResultadoComando

    suspend fun reiniciar(): ResultadoComando

    suspend fun configurarAuto(activar: Boolean, descansoSeg: Long? = null): ResultadoComando

    // ── Fase 2: la capa de datos ya los implementa; la UI llegará después ──

    suspend fun configurarRecorrido(
        distanciaCm: Float,
        anchoZonaCm: Float? = null,
        pasosPorCm: Float? = null,
    ): ResultadoComando

    suspend fun pruebaMovimiento(cm: Float): ResultadoComando

    suspend fun calibrarDistancia(realCm: Float, pedidoCm: Float? = null): ResultadoComando

    suspend fun pruebaRetorno(): ResultadoComando

    suspend fun pruebaSensor(): ResultadoComando

    suspend fun calibrarSensor(
        punto: PuntoCalibracion? = null,
        adc: Int? = null,
        pctSeca: Int? = null,
        pctHumeda: Int? = null,
        pctSobre: Int? = null,
    ): ResultadoComando
}
