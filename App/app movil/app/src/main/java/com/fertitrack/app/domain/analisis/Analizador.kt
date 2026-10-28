package com.fertitrack.app.domain.analisis

import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.Traductor
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Severidad { INFO, EXITO, AVISO, CRITICO }

data class Insight(
    val titulo: String,
    val detalle: String,
    val severidad: Severidad,
)

/**
 * Análisis automático basado en reglas. Es lógica NUEVA: la app de escritorio
 * tenía el panel "Insight" pero nunca lo rellenaba.
 */
object Analizador {

    /** Diferencia de humedad (en puntos) a partir de la cual se avisa de una tendencia. */
    private const val UMBRAL_TENDENCIA = 5f

    /** Insights ordenados del más al menos importante (CRITICO primero). */
    fun generar(
        robot: EstadoRobot?,
        resumen: ResumenSesion,
        conexion: EstadoConexion,
        ahoraMs: Long,
    ): List<Insight> {
        val lista = mutableListOf<Insight>()

        if (robot != null && robot.hayFalla) {
            val falla = Traductor.describirFalla(robot.codigoFalla)
            lista += Insight("Falla ${falla.codigo}: ${falla.titulo}", falla.accionSugerida, Severidad.CRITICO)
        }

        if (conexion !is EstadoConexion.Conectado && conexion !is EstadoConexion.Pausado) {
            lista += Insight(
                if (robot == null) "Sin datos del robot" else "Datos sin actualizar",
                "No hay respuesta del robot. Comprueba que el teléfono esté conectado al Wi-Fi " +
                    "${RobotConfig.SSID_AP} y que el Maestro esté encendido.",
                Severidad.AVISO,
            )
        }

        if (robot != null) {
            if (!robot.distanciaConfigurada) {
                lista += Insight(
                    "Recorrido sin configurar",
                    "Configura la distancia desde la página web del robot (http://${RobotConfig.IP_ROBOT}) " +
                        "para poder iniciar ciclos.",
                    Severidad.AVISO,
                )
            }
            if (!robot.espBConectada) {
                lista += Insight(
                    "Sensor no conectado",
                    "ESP_B está desactivada en el firmware: los sensados no tendrán mediciones.",
                    Severidad.AVISO,
                )
            }

            val secas = zonasCon(robot, NivelSuelo.SECA)
            if (secas.isNotEmpty() && robot.modoRutina == ModoRutina.NINGUNA && !robot.hayFalla) {
                val detalle = if (robot.autoActivo && robot.proximoCicloSeg != null) {
                    "El próximo ciclo automático las regará."
                } else {
                    "Inicia un ciclo completo para regarlas."
                }
                lista += Insight("${contar(secas.size, "zona seca", "zonas secas")} (${unir(secas)})", detalle, Severidad.AVISO)
            }

            val sobre = zonasCon(robot, NivelSuelo.SOBRE_HUMEDA)
            if (sobre.isNotEmpty()) {
                lista += Insight(
                    "Posible exceso de riego",
                    if (sobre.size == 1) {
                        "La zona ${unir(sobre)} está sobre-húmeda (≥ ${robot.calibracion.pctSobre} %)."
                    } else {
                        "Las zonas ${unir(sobre)} están sobre-húmedas (≥ ${robot.calibracion.pctSobre} %)."
                    },
                    Severidad.AVISO,
                )
            }

            robot.proximoCicloSeg?.let { seg ->
                lista += Insight(
                    "Próximo ciclo automático en ${formatoMinSeg(seg)}",
                    "Descanso configurado: ${robot.descansoSeg} s entre ciclos.",
                    Severidad.INFO,
                )
            }
        }

        tendencia(resumen)?.let { lista += it }

        if (resumen.riegosCompletados > 0) {
            val ultimo = resumen.riegos.lastOrNull { it.completado }
            val detalle = if (ultimo != null && ultimo.zonasRegadas.isNotEmpty()) {
                "Último riego: ${contar(ultimo.zonasRegadas.size, "zona", "zonas")} (${unir(ultimo.zonasRegadas)})."
            } else {
                "Los riegos registrados terminaron sin fallas."
            }
            lista += Insight(
                "${contar(resumen.riegosCompletados, "riego completado", "riegos completados")} en el historial",
                detalle,
                Severidad.EXITO,
            )
        }

        if (resumen.totalMediciones == 0) {
            lista += Insight(
                "Aún no hay mediciones guardadas",
                "Inicia un sensado o un ciclo para ver la humedad de cada zona.",
                Severidad.INFO,
            )
        }

        if (lista.none { it.severidad == Severidad.CRITICO || it.severidad == Severidad.AVISO } &&
            conexion is EstadoConexion.Conectado
        ) {
            val detalle = resumen.humedadPromedio?.let { "Humedad promedio del historial: ${it.roundToInt()} %." }
                ?: "El robot está conectado y sin fallas."
            lista += Insight("Todo en orden", detalle, Severidad.EXITO)
        }

        return lista.sortedBy { prioridad(it.severidad) }
    }

    private fun tendencia(resumen: ResumenSesion): Insight? {
        val m = resumen.muestras
        if (m.size < 6) return null
        val recientes = m.takeLast(3).map { it.humedadPct }.average().toFloat()
        val anteriores = m.dropLast(3).takeLast(3).map { it.humedadPct }.average().toFloat()
        val diferencia = recientes - anteriores
        if (abs(diferencia) < UMBRAL_TENDENCIA) return null
        val texto = "de ${anteriores.roundToInt()} % a ${recientes.roundToInt()} % en las últimas mediciones"
        return if (diferencia < 0) {
            Insight("La humedad está bajando", "Bajó ${(-diferencia).roundToInt()} puntos: $texto.", Severidad.AVISO)
        } else {
            Insight("La humedad está subiendo", "Subió ${diferencia.roundToInt()} puntos: $texto.", Severidad.INFO)
        }
    }

    private fun prioridad(s: Severidad): Int = when (s) {
        Severidad.CRITICO -> 0
        Severidad.AVISO -> 1
        Severidad.INFO -> 2
        Severidad.EXITO -> 3
    }

    private fun zonasCon(robot: EstadoRobot, nivel: NivelSuelo): List<Int> =
        robot.zonas.mapIndexedNotNull { i, n -> if (n == nivel) i + 1 else null }

    private fun contar(n: Int, singular: String, plural: String) = "$n ${if (n == 1) singular else plural}"

    private fun unir(zonas: List<Int>): String = when {
        zonas.size == 1 -> zonas.first().toString()
        zonas.size <= 6 -> zonas.dropLast(1).joinToString(", ") + " y " + zonas.last()
        else -> zonas.take(5).joinToString(", ") + " y ${zonas.size - 5} más"
    }

    private fun formatoMinSeg(segundos: Long): String {
        val s = segundos.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
