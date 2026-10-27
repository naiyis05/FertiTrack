package com.fertitrack.app.simulador

import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoMaquina.ARRANQUE
import com.fertitrack.app.domain.modelo.EstadoMaquina.EMERGENCIA
import com.fertitrack.app.domain.modelo.EstadoMaquina.ESPERANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.FERTI_AVANZANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.FERTI_BAJANDO_BRAZO
import com.fertitrack.app.domain.modelo.EstadoMaquina.FERTI_RETORNANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.FERTI_SUBIENDO_BRAZO
import com.fertitrack.app.domain.modelo.EstadoMaquina.PRUEBA_AVANZANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.PRUEBA_LISTA
import com.fertitrack.app.domain.modelo.EstadoMaquina.PRUEBA_RETORNANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.PRUEBA_SENSOR
import com.fertitrack.app.domain.modelo.EstadoMaquina.RECUP_BRAZO_FERTI
import com.fertitrack.app.domain.modelo.EstadoMaquina.RECUP_BRAZO_RIEGO
import com.fertitrack.app.domain.modelo.EstadoMaquina.RECUP_ESPERA
import com.fertitrack.app.domain.modelo.EstadoMaquina.RECUP_RETORNANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.RIEGO_AVANZANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.RIEGO_BAJANDO_BRAZO
import com.fertitrack.app.domain.modelo.EstadoMaquina.RIEGO_RETORNANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.RIEGO_SUBIENDO_BRAZO
import com.fertitrack.app.domain.modelo.EstadoMaquina.SENSADO_AVANZANDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.SENSADO_MIDIENDO
import com.fertitrack.app.domain.modelo.EstadoMaquina.SENSADO_RETORNANDO
import com.fertitrack.app.domain.modelo.ModoRutina
import java.util.Locale
import kotlin.random.Random
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Duraciones simuladas de cada paso (el robot real tarda bastante más). */
internal data class TiemposSimulados(
    val arranqueMs: Long = 3_000,
    val esperaRecuperacionMs: Long = 500,
    val avanceZonaMs: Long = 1_500,
    val medicionMs: Long = 2_000,
    val brazoMs: Long = 2_000,
    val tramoRiegoMs: Long = 3_000,
    val avanceFertiMs: Long = 4_000,
    val retornoMs: Long = 2_000,
    val pruebaSensorMs: Long = 3_000,
) {
    companion object {
        /** Para pruebas: cada paso dura [ms]. */
        fun todos(ms: Long) = TiemposSimulados(ms, ms, ms, ms, ms, ms, ms, ms, ms)
    }
}

internal class RespuestaSimulada(
    val codigo: Int,
    val cuerpo: String,
    val tipoContenido: String = "application/json",
)

/**
 * Máquina de estados del Maestro v5.3 (loop(), rutinas y ruta_*) con tiempos acortados.
 *
 * Es perezosa y determinista: cada paso que en el robot espera la respuesta de ESP_A/ESP_B
 * tiene un plazo ([finPasoMs]); [avanzarHasta] procesa en orden todos los plazos vencidos según
 * [reloj], así que da igual cada cuánto se la consulte. Las respuestas HTTP usan los mismos
 * campos, códigos y textos que el firmware. Todos los métodos públicos son synchronized.
 *
 * Diferencias deliberadas: arranca ya configurada (300 cm, 6 zonas), la humedad de cada zona
 * es un modelo pseudoaleatorio con [semilla] (se seca un poco antes de cada sensado y sube al
 * regarla) y no hay timeouts de esclavos (las fallas 1, 2, 3 y 9 se inyectan).
 */
internal class MaquinaRobotSimulada(
    private val tiempos: TiemposSimulados = TiemposSimulados(),
    semilla: Long = 2026L,
    private val espBConectada: Boolean = true,
    private val espCConectada: Boolean = true,
    private val reloj: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val aleatorio = Random(semilla)

    // ── Configuración (en el robot vive en la NVS) ──
    private var distanciaTotalCm = 300f
    private var anchoZonaCm = 50f
    private var pasosPorCm = 100f
    private var adcSeco = 4095
    private var adcMojado = 1200
    private var pctSeca = 35
    private var pctHumeda = 65
    private var pctSobre = 90
    private var umbralSeco = 0
    private var umbralHumedo = 0
    private var umbralSobreHumedo = 0
    private var cicloAutomaticoActivo = false
    private var intervaloDescansoMs = 60_000L

    // ── Zonas ──
    private var numZonas = 0
    private var distanciaTotalPasos = 0L
    private var limitesZonaPasos = LongArray(0)
    private var clasificacionZona = IntArray(0)

    /** Humedad "real" (%) de cada zona: lo que el sensor simulado mide. */
    private var humedadReal = DoubleArray(0)

    // ── Máquina de estados ──
    private var estadoActual = ARRANQUE
    private var modoRutina = ModoRutina.NINGUNA
    private var zonaActual = 0
    private var zonasEnTramo = 1
    private var posicionPasos = 0L
    private var encadenarRiegoTrasSensado = false
    private var brazoRiegoAbajo = false
    private var ultimaHumedadAdc = -1
    private var nivelSueloActual = 0
    private var actividadActual = 0
    private var huboFalla = false
    private var codigoFalla = 0
    private var esperandoProximoCiclo = false
    private var proximoCicloMs = 0L
    private var pruebaPedidoCm = 0f
    private var primeraSeca = -1
    private var ultimaSeca = -1

    /** Relés (solo para inspección en pruebas; el firmware no los publica). */
    var bombaRiegoEncendida = false
        private set
    var bombaFertilizanteEncendida = false
        private set

    /** Plazo del paso en curso (respuesta del esclavo o fin de una espera); null = nada pendiente. */
    private var finPasoMs: Long? = null

    /** Tiempo lógico: el del evento que se está procesando o el de la petición en curso. */
    private var t: Long = reloj()

    init {
        recalcularUmbrales()
        recalcularZonas()
        // Al encender: ARRANQUE (~3 s) → RECUP_* (recoge brazos, vuelve al origen) → ESPERANDO.
        estadoActual = ARRANQUE
        esperar(tiempos.arranqueMs)
    }

    // ── API para el servidor ─────────────────────────────────────────────

    @Synchronized
    fun atender(metodo: String, ruta: String, args: Map<String, String>): RespuestaSimulada {
        avanzarHasta(reloj())
        // El WebServer del ESP32 solo registra rutas GET: el resto cae en onNotFound.
        if (metodo != "GET") return noEncontrada()
        return when (ruta) {
            "/" -> RespuestaSimulada(200, PAGINA, "text/html; charset=utf-8")
            "/api/estado" -> rutaEstado()
            "/api/configurar" -> rutaConfigurar(args)
            "/api/auto" -> rutaAuto(args)
            "/api/iniciar" -> rutaIniciar(args)
            "/api/detener" -> {
                declararFalla(8)
                okSimple()
            }
            "/api/reiniciar" -> {
                reiniciarSistema()
                okSimple()
            }
            "/api/prueba_mov" -> rutaPruebaMov(args)
            "/api/calibrar_distancia" -> rutaCalibrarDistancia(args)
            "/api/prueba_retorno" -> rutaPruebaRetorno()
            "/api/prueba_sensor" -> rutaPruebaSensor()
            "/api/cal_sensor" -> rutaCalSensor(args)
            else -> noEncontrada()
        }
    }

    /** Falla 1 (ESP_A), 2 (ESP_B), 3 (sensor sin lectura válida) o 9 (botón físico). */
    @Synchronized
    fun inyectarFalla(codigo: Int) {
        avanzarHasta(reloj())
        // En el robot la falla 3 llega con una lectura inválida (ADC < 1): humedad_pct = 100.
        if (codigo == 3) ultimaHumedadAdc = 0
        declararFalla(codigo)
    }

    @Synchronized
    fun estado(): EstadoMaquina {
        avanzarHasta(reloj())
        return estadoActual
    }

    // ── Bucle (loop() del firmware) ──────────────────────────────────────

    /** Procesa en orden cronológico todos los eventos vencidos hasta [ahora]. */
    private fun avanzarHasta(ahora: Long) {
        var guarda = 0
        while (guarda++ < MAX_EVENTOS_POR_AVANCE) {
            val plazoPaso = finPasoMs
            val plazoAuto = if (estadoActual == ESPERANDO && esperandoProximoCiclo) proximoCicloMs else null
            val siguiente = listOfNotNull(plazoPaso, plazoAuto).minOrNull()
            if (siguiente == null || siguiente > ahora) break
            t = siguiente
            if (plazoAuto != null && plazoAuto == siguiente && plazoPaso != siguiente) {
                esperandoProximoCiclo = false
                iniciarSensado(encadenarRiego = true)
            } else {
                finPasoMs = null
                completarPaso()
            }
        }
        t = ahora
    }

    private fun esperar(ms: Long) {
        finPasoMs = t + ms
    }

    /** Lo que hace loop() cuando llega la respuesta del esclavo (respuestaLista) o vence la espera. */
    private fun completarPaso() {
        when (estadoActual) {
            SENSADO_AVANZANDO -> {
                estadoActual = SENSADO_MIDIENDO
                actividadActual = 1
                if (espBConectada) {
                    esperar(tiempos.medicionMs)
                } else {
                    clasificacionZona[zonaActual] = 0
                    nivelSueloActual = 0
                    avanzarZonaOTerminarSensado()
                }
            }
            SENSADO_MIDIENDO -> {
                ultimaHumedadAdc = medirAdc(zonaActual)
                if (ultimaHumedadAdc < ADC_MIN_VALIDO || ultimaHumedadAdc > ADC_MAX_VALIDO) {
                    declararFalla(3)
                    return
                }
                val nivel = clasificarHumedad(ultimaHumedadAdc)
                clasificacionZona[zonaActual] = nivel
                nivelSueloActual = nivel
                avanzarZonaOTerminarSensado()
            }
            SENSADO_RETORNANDO -> {
                posicionPasos = 0
                if (encadenarRiegoTrasSensado && hayZonaSeca()) iniciarRiegoSelectivo() else terminarRutina()
            }
            RIEGO_BAJANDO_BRAZO -> {
                brazoRiegoAbajo = true
                avanzarTramoRiego(riega = true)
            }
            RIEGO_SUBIENDO_BRAZO -> {
                brazoRiegoAbajo = false
                procesarZonaRiego()
            }
            RIEGO_AVANZANDO -> {
                if (bombaRiegoEncendida) regarZonas(zonaActual, zonasEnTramo)
                zonaActual += zonasEnTramo
                procesarZonaRiego()
            }
            RIEGO_RETORNANDO -> terminarRutina()
            FERTI_BAJANDO_BRAZO -> {
                bombaFertilizanteEncendida = true
                estadoActual = FERTI_AVANZANDO
                posicionPasos += distanciaTotalPasos
                esperar(tiempos.avanceFertiMs)
                actividadActual = 3
            }
            FERTI_AVANZANDO -> {
                bombaFertilizanteEncendida = false
                for (i in humedadReal.indices) humedadReal[i] = (humedadReal[i] + 3.0).coerceAtMost(100.0)
                estadoActual = FERTI_SUBIENDO_BRAZO
                esperar(tiempos.brazoMs)
            }
            FERTI_SUBIENDO_BRAZO -> {
                estadoActual = FERTI_RETORNANDO
                esperar(tiempos.retornoMs)
                actividadActual = 4
            }
            FERTI_RETORNANDO -> terminarRutina()
            RECUP_ESPERA -> {
                estadoActual = RECUP_BRAZO_RIEGO
                esperar(tiempos.brazoMs)
            }
            RECUP_BRAZO_RIEGO -> {
                estadoActual = RECUP_BRAZO_FERTI
                esperar(tiempos.brazoMs)
            }
            RECUP_BRAZO_FERTI -> {
                estadoActual = RECUP_RETORNANDO
                esperar(tiempos.retornoMs)
            }
            RECUP_RETORNANDO -> terminarRutina()
            ARRANQUE -> iniciarRecuperacion()
            PRUEBA_AVANZANDO -> {
                estadoActual = PRUEBA_LISTA
                actividadActual = 0
            }
            PRUEBA_RETORNANDO -> terminarRutina()
            PRUEBA_SENSOR -> {
                ultimaHumedadAdc = medirAdc(0)
                estadoActual = ESPERANDO
                actividadActual = 0
            }
            ESPERANDO, PRUEBA_LISTA, EMERGENCIA, EstadoMaquina.DESCONOCIDO -> Unit
        }
    }

    // ── Fallas y recuperación ────────────────────────────────────────────

    private fun declararFalla(codigo: Int) {
        bombaRiegoEncendida = false
        bombaFertilizanteEncendida = false
        huboFalla = true
        codigoFalla = codigo
        estadoActual = EMERGENCIA
        modoRutina = ModoRutina.NINGUNA
        finPasoMs = null // nodoEsperado = NODO_NINGUNO
        esperandoProximoCiclo = false
        actividadActual = 0
    }

    private fun iniciarRecuperacion() {
        modoRutina = ModoRutina.NINGUNA
        esperandoProximoCiclo = false
        actividadActual = 4
        estadoActual = RECUP_ESPERA
        esperar(tiempos.esperaRecuperacionMs)
    }

    /** Solo actúa en EMERGENCIA; apaga el automático y mueve el robot (RECUP_*). */
    private fun reiniciarSistema() {
        if (estadoActual != EMERGENCIA) return
        huboFalla = false
        codigoFalla = 0
        zonaActual = 0
        cicloAutomaticoActivo = false
        iniciarRecuperacion()
    }

    private fun programarProximoCicloSiAplica() {
        if (cicloAutomaticoActivo && distanciaTotalCm > 0) {
            esperandoProximoCiclo = true
            proximoCicloMs = t + intervaloDescansoMs
        } else {
            esperandoProximoCiclo = false
        }
    }

    private fun terminarRutina() {
        posicionPasos = 0
        brazoRiegoAbajo = false
        bombaRiegoEncendida = false
        modoRutina = ModoRutina.NINGUNA
        estadoActual = ESPERANDO
        actividadActual = 0
        programarProximoCicloSiAplica()
    }

    // ── Rutinas ──────────────────────────────────────────────────────────

    private fun iniciarSensado(encadenarRiego: Boolean): Boolean {
        if (estadoActual != ESPERANDO) return false
        if (distanciaTotalCm <= 0 || numZonas < 1) return false
        secarZonas()
        encadenarRiegoTrasSensado = encadenarRiego
        zonaActual = 0
        modoRutina = ModoRutina.SENSADO
        estadoActual = SENSADO_AVANZANDO
        ordenarAvance(pasosDeZona(0), tiempos.avanceZonaMs)
        actividadActual = 4
        return true
    }

    private fun avanzarZonaOTerminarSensado() {
        zonaActual++
        if (zonaActual >= numZonas) {
            estadoActual = SENSADO_RETORNANDO
            esperar(tiempos.retornoMs)
        } else {
            estadoActual = SENSADO_AVANZANDO
            ordenarAvance(pasosDeZona(zonaActual), tiempos.avanceZonaMs)
        }
        actividadActual = 4
    }

    private fun calcularRangoSeco() {
        primeraSeca = clasificacionZona.indexOfFirst { it == 1 }
        ultimaSeca = clasificacionZona.indexOfLast { it == 1 }
    }

    private fun avanzarTramoRiego(riega: Boolean) {
        zonasEnTramo = tramoMismaAccion(zonaActual, riega).coerceAtLeast(1)
        val pasos = pasosTramo(zonaActual, zonasEnTramo)
        estadoActual = RIEGO_AVANZANDO
        // La bomba SOLO funciona sobre zonas secas y con el brazo abajo.
        bombaRiegoEncendida = riega && brazoRiegoAbajo
        ordenarAvance(pasos, tiempos.tramoRiegoMs)
        actividadActual = if (riega) 2 else 4
    }

    private fun terminarRiegoYRetornar() {
        if (brazoRiegoAbajo) {
            bombaRiegoEncendida = false
            estadoActual = RIEGO_SUBIENDO_BRAZO
            esperar(tiempos.brazoMs)
        } else {
            estadoActual = RIEGO_RETORNANDO
            esperar(tiempos.retornoMs)
        }
        actividadActual = 4
    }

    private fun procesarZonaRiego() {
        if (zonaActual > ultimaSeca) {
            terminarRiegoYRetornar()
            return
        }
        val necesitaRiego = clasificacionZona[zonaActual] == 1
        if (!brazoRiegoAbajo) {
            if (necesitaRiego) {
                estadoActual = RIEGO_BAJANDO_BRAZO
                esperar(tiempos.brazoMs)
                actividadActual = 4
            } else {
                avanzarTramoRiego(riega = false)
            }
        } else {
            // MANTENER_BRAZO_RIEGO_ENTRE_TRAMOS = true en el firmware.
            avanzarTramoRiego(necesitaRiego)
        }
    }

    private fun iniciarRiegoSelectivo(): Boolean {
        if (numZonas < 1) return false
        calcularRangoSeco()
        if (primeraSeca < 0) return false
        zonaActual = 0
        modoRutina = ModoRutina.RIEGO
        brazoRiegoAbajo = false
        procesarZonaRiego()
        return true
    }

    private fun iniciarFertilizacion(): Boolean {
        if (estadoActual != ESPERANDO) return false
        if (distanciaTotalCm <= 0 || distanciaTotalPasos <= 0) return false
        modoRutina = ModoRutina.FERTILIZACION
        estadoActual = FERTI_BAJANDO_BRAZO
        esperar(tiempos.brazoMs)
        actividadActual = 3
        return true
    }

    private fun iniciarPruebaMovimiento(cm: Float): Boolean {
        if (estadoActual != ESPERANDO) return false
        val pasos = Math.round(cm.toDouble() * pasosPorCm)
        if (pasos < 1) return false
        pruebaPedidoCm = cm
        modoRutina = ModoRutina.NINGUNA
        estadoActual = PRUEBA_AVANZANDO
        ordenarAvance(pasos, tiempos.avanceZonaMs)
        actividadActual = 4
        return true
    }

    private fun iniciarPruebaRetorno(): Boolean {
        if (estadoActual != PRUEBA_LISTA) return false
        estadoActual = PRUEBA_RETORNANDO
        esperar(tiempos.retornoMs)
        actividadActual = 4
        return true
    }

    private fun iniciarPruebaSensor(): Boolean {
        if (estadoActual != ESPERANDO || !espBConectada) return false
        modoRutina = ModoRutina.NINGUNA
        estadoActual = PRUEBA_SENSOR
        esperar(tiempos.pruebaSensorMs)
        actividadActual = 1
        return true
    }

    /** posicionPasos es una cota superior: se suma al dar la orden, como en ordenarAvance(). */
    private fun ordenarAvance(pasos: Long, duracionMs: Long) {
        posicionPasos += pasos
        esperar(duracionMs)
    }

    // ── Zonas y sensor ───────────────────────────────────────────────────

    private fun recalcularUmbrales() {
        val span = (adcSeco - adcMojado).toFloat()
        umbralSeco = Math.round(adcSeco - span * pctSeca / 100f)
        umbralHumedo = Math.round(adcSeco - span * pctHumeda / 100f)
        umbralSobreHumedo = Math.round(adcSeco - span * pctSobre / 100f)
    }

    private fun recalcularZonas(): Boolean {
        if (distanciaTotalCm <= 0 || anchoZonaCm <= 0 || pasosPorCm <= 0) return false
        val nz = Math.round(distanciaTotalCm.toDouble() / anchoZonaCm).toInt().coerceIn(1, MAX_ZONAS)
        numZonas = nz
        distanciaTotalPasos = Math.round(distanciaTotalCm.toDouble() * pasosPorCm)
        limitesZonaPasos = LongArray(nz + 1) { i -> Math.round(distanciaTotalPasos.toDouble() * i / nz) }
        clasificacionZona = IntArray(nz)
        if (humedadReal.size != nz) humedadReal = humedadInicial(nz)
        return true
    }

    private fun pasosDeZona(i: Int): Long =
        if (i < 0 || i >= numZonas) 0 else limitesZonaPasos[i + 1] - limitesZonaPasos[i]

    private fun pasosTramo(desde: Int, n: Int): Long =
        if (desde < 0 || n < 1 || desde + n > numZonas) 0 else limitesZonaPasos[desde + n] - limitesZonaPasos[desde]

    private fun tramoMismaAccion(desde: Int, riega: Boolean): Int {
        var n = 0
        while (desde + n < numZonas && (clasificacionZona[desde + n] == 1) == riega) n++
        return n
    }

    private fun clasificarHumedad(adc: Int): Int = when {
        adc < 0 -> 0
        adc <= umbralSobreHumedo -> 4
        adc <= umbralHumedo -> 3
        adc < umbralSeco -> 2
        else -> 1
    }

    private fun hayZonaSeca() = clasificacionZona.any { it == 1 }

    /** humedadPorcentaje() del firmware: 0 = cal_seco, 100 = cal_mojado; −1 sin dato. */
    private fun humedadPorcentaje(adc: Int): Float {
        val span = (adcSeco - adcMojado).toFloat()
        if (adc < 0 || span <= 0) return -1f
        return (100f * (adcSeco - adc) / span).coerceIn(0f, 100f)
    }

    /** Valores iniciales con al menos una zona seca, para que el primer ciclo riegue. */
    private fun humedadInicial(n: Int): DoubleArray {
        val valores = DoubleArray(n) { 30.0 + aleatorio.nextDouble() * 55.0 }
        valores[minOf(1, n - 1)] = 12.0
        return valores
    }

    /** Evaporación antes de cada sensado: así la mayoría de los ciclos encuentra zonas secas. */
    private fun secarZonas() {
        for (i in humedadReal.indices) {
            humedadReal[i] = (humedadReal[i] - (4.0 + aleatorio.nextDouble() * 18.0)).coerceAtLeast(0.0)
        }
    }

    private fun regarZonas(desde: Int, n: Int) {
        for (i in desde until minOf(desde + n, humedadReal.size)) {
            humedadReal[i] = (humedadReal[i] + 40.0 + aleatorio.nextDouble() * 20.0).coerceAtMost(100.0)
        }
    }

    /** Sensor resistivo físico: ~4095 al aire/seco, ~1200 en agua, con algo de ruido. */
    private fun medirAdc(zona: Int): Int {
        val pct = humedadReal.getOrNull(zona) ?: 50.0
        val ruido = aleatorio.nextInt(-25, 26)
        val adc = Math.round(ADC_FISICO_SECO - pct / 100.0 * (ADC_FISICO_SECO - ADC_FISICO_MOJADO)).toInt() + ruido
        return adc.coerceIn(ADC_MIN_VALIDO, ADC_MAX_VALIDO)
    }

    // ── Rutas (ruta_* del firmware) ──────────────────────────────────────

    private fun rutaEstado(): RespuestaSimulada {
        val json = buildJsonObject {
            put("estado", estadoActual.name)
            put("modo_rutina", modoRutina.name)
            put("zona_actual", if (numZonas > 0) zonaActual + 1 else 0)
            put("total_zonas", numZonas)
            putNumero("distancia_cm", distanciaTotalCm)
            putNumero("ancho_zona_cm", if (numZonas > 0) distanciaTotalCm / numZonas else anchoZonaCm)
            putNumero("pasos_por_cm", pasosPorCm)
            put("posicion_pasos", posicionPasos)
            put("humedad_adc", ultimaHumedadAdc)
            putNumero("humedad_pct", humedadPorcentaje(ultimaHumedadAdc))
            put("cal_seco", adcSeco)
            put("cal_mojado", adcMojado)
            put("pct_seca", pctSeca)
            put("pct_humeda", pctHumeda)
            put("pct_sobre", pctSobre)
            put("umbral_seco", umbralSeco)
            put("umbral_humedo", umbralHumedo)
            put("umbral_sobre", umbralSobreHumedo)
            put("nivel_suelo", nivelSueloActual)
            put("actividad", actividadActual)
            put("falla", if (huboFalla) codigoFalla else 0)
            put("esp_b_conectada", espBConectada)
            put("esp_c_conectada", espCConectada)
            put("auto_activo", cicloAutomaticoActivo)
            put("descanso_seg", intervaloDescansoMs / 1000)
            if (esperandoProximoCiclo) {
                val restanteMs = proximoCicloMs - t
                put("proximo_ciclo_seg", if (restanteMs > 0) restanteMs / 1000 else 0)
            } else {
                put("proximo_ciclo_seg", -1)
            }
            putJsonArray("zonas") { clasificacionZona.forEach { add(it) } }
        }
        return RespuestaSimulada(200, json.toString())
    }

    private fun rutaConfigurar(args: Map<String, String>): RespuestaSimulada {
        if (estadoActual != ESPERANDO) return errorCrudo(409, "sistema ocupado")
        val distancia = args["distancia_cm"] ?: return errorCrudo(400, "falta distancia_cm")
        val nd = aFloatArduino(distancia)
        if (nd <= 0) return errorCrudo(400, "distancia_cm debe ser > 0")
        distanciaTotalCm = nd
        args["ancho_zona_cm"]?.let { aFloatArduino(it) }?.takeIf { it > 0 }?.let { anchoZonaCm = it }
        args["pasos_cm"]?.let { aFloatArduino(it) }?.takeIf { it > 0 }?.let { pasosPorCm = it }
        if (!recalcularZonas()) return errorCrudo(500, "no se pudo calcular zonas")
        val json = buildJsonObject {
            put("ok", true)
            put("num_zonas", numZonas)
            putNumero("ancho_zona_real_cm", distanciaTotalCm / numZonas)
            put("distancia_total_pasos", distanciaTotalPasos)
        }
        return RespuestaSimulada(200, json.toString())
    }

    private fun rutaAuto(args: Map<String, String>): RespuestaSimulada {
        args["activar"]?.let { cicloAutomaticoActivo = aEnteroArduino(it) != 0L }
        args["descanso_seg"]?.let { aEnteroArduino(it) }?.takeIf { it > 0 }?.let { intervaloDescansoMs = it * 1000 }
        if (cicloAutomaticoActivo && estadoActual == ESPERANDO && !esperandoProximoCiclo) programarProximoCicloSiAplica()
        if (!cicloAutomaticoActivo) esperandoProximoCiclo = false
        val json = buildJsonObject {
            put("ok", true)
            put("auto_activo", cicloAutomaticoActivo)
            put("descanso_seg", intervaloDescansoMs / 1000)
        }
        return RespuestaSimulada(200, json.toString())
    }

    private fun rutaIniciar(args: Map<String, String>): RespuestaSimulada {
        val modo = args["modo"] ?: ""
        var ok = false
        var error = ""
        when {
            estadoActual != ESPERANDO -> error = "sistema ocupado"
            distanciaTotalCm <= 0 || numZonas < 1 -> error = "configura primero la distancia con /api/configurar"
            modo == "ciclo" -> ok = iniciarSensado(encadenarRiego = true)
            modo == "sensado" -> ok = iniciarSensado(encadenarRiego = false)
            modo == "fertilizar" -> ok = iniciarFertilizacion()
            else -> error = "modo invalido (usa ciclo, sensado o fertilizar)"
        }
        if (ok) return okSimple()
        return errorCrudo(409, error.ifEmpty { "no se pudo iniciar" })
    }

    private fun rutaPruebaMov(args: Map<String, String>): RespuestaSimulada {
        val cm = args["cm"]?.let(::aFloatArduino) ?: 10f
        if (cm < 1 || cm > 150) return responderJson(400, false, "error", "cm debe estar entre 1 y 150")
        if (!iniciarPruebaMovimiento(cm)) return responderJson(409, false, "error", "sistema ocupado")
        return responderJson(
            200, true, "mensaje",
            "Moviendo ${decimales(cm, 1)} cm. Cuando se detenga, mide cuanto avanzo de verdad.",
        )
    }

    private fun rutaCalibrarDistancia(args: Map<String, String>): RespuestaSimulada {
        if (estadoActual != PRUEBA_LISTA && estadoActual != ESPERANDO) {
            return responderJson(409, false, "error", "sistema ocupado")
        }
        val real = args["real_cm"]?.let(::aFloatArduino) ?: 0f
        val pedido = args["pedido_cm"]?.let(::aFloatArduino) ?: pruebaPedidoCm
        if (real <= 0) return responderJson(400, false, "error", "falta real_cm (distancia medida)")
        if (pedido <= 0) {
            return responderJson(400, false, "error", "primero haz 'Mover prueba' (o indica pedido_cm)")
        }
        val nuevo = pasosPorCm * pedido / real
        if (nuevo < 1f || nuevo > 5000f) {
            return responderJson(400, false, "error", "valor fuera de rango: revisa la medida")
        }
        val antes = pasosPorCm
        pasosPorCm = nuevo
        pruebaPedidoCm = 0f
        if (distanciaTotalCm > 0) recalcularZonas()
        return responderJson(
            200, true, "mensaje",
            "Nueva calibracion: ${decimales(nuevo, 2)} pasos/cm (antes ${decimales(antes, 2)})." +
                "\nToca 'Volver al origen' y repite la prueba para confirmar.",
        )
    }

    private fun rutaPruebaRetorno(): RespuestaSimulada {
        if (!iniciarPruebaRetorno()) {
            return responderJson(409, false, "error", "solo despues de una prueba de movimiento")
        }
        return responderJson(200, true, "mensaje", "Regresando al origen...")
    }

    private fun rutaPruebaSensor(): RespuestaSimulada {
        if (!espBConectada) return responderJson(409, false, "error", "ESP_B no esta conectada")
        if (!iniciarPruebaSensor()) return responderJson(409, false, "error", "sistema ocupado")
        return responderJson(200, true, "mensaje", "Midiendo (~8 s). Luego veras el ADC en 'Estado'.")
    }

    private fun rutaCalSensor(args: Map<String, String>): RespuestaSimulada {
        if (estadoActual != ESPERANDO) return responderJson(409, false, "error", "sistema ocupado")
        var nS = adcSeco
        var nM = adcMojado
        var pS = pctSeca
        var pH = pctHumeda
        var pO = pctSobre
        val punto = args["punto"]
        if (punto != null) {
            val adc = args["adc"]?.let { aEnteroArduino(it).toInt() } ?: ultimaHumedadAdc
            if (adc < ADC_MIN_VALIDO || adc > 4095) {
                return responderJson(400, false, "error", "no hay lectura valida: toca 'Medir sensor ahora' primero")
            }
            when (punto) {
                "seco" -> nS = adc
                "mojado" -> nM = adc
                else -> return responderJson(400, false, "error", "punto debe ser seco o mojado")
            }
        }
        args["pct_seca"]?.let { pS = aEnteroArduino(it).toInt() }
        args["pct_humeda"]?.let { pH = aEnteroArduino(it).toInt() }
        args["pct_sobre"]?.let { pO = aEnteroArduino(it).toInt() }
        if (nS - nM < 300) {
            return responderJson(
                400, false, "error",
                "SECO debe ser al menos 300 mayor que MOJADO (ADC alto = seco). Revisa las lecturas.",
            )
        }
        if (!(pS > 0 && pS < pH && pH < pO && pO < 100)) {
            return responderJson(400, false, "error", "los porcentajes deben cumplir 0 < seca < humeda < sobre < 100")
        }
        adcSeco = nS
        adcMojado = nM
        pctSeca = pS
        pctHumeda = pH
        pctSobre = pO
        recalcularUmbrales()
        return responderJson(
            200, true, "mensaje",
            "Guardado. Seca: ADC >= $umbralSeco | Intermedia: entre $umbralHumedo y $umbralSeco | " +
                "Humeda: <= $umbralHumedo | Sobre-humeda: <= $umbralSobreHumedo",
        )
    }

    // ── Respuestas ───────────────────────────────────────────────────────

    private fun okSimple() = RespuestaSimulada(200, """{"ok":true}""")

    /** Como los server.send con el JSON escrito a mano en el firmware. */
    private fun errorCrudo(codigo: Int, error: String) =
        RespuestaSimulada(codigo, """{"ok":false,"error":"$error"}""")

    /** responderJson() del firmware. */
    private fun responderJson(codigo: Int, ok: Boolean, clave: String, texto: String): RespuestaSimulada {
        val json = buildJsonObject {
            put("ok", ok)
            put(clave, texto)
        }
        return RespuestaSimulada(codigo, json.toString())
    }

    private fun noEncontrada() = RespuestaSimulada(404, """{"error":"ruta no encontrada"}""")

    internal companion object {
        const val MAX_ZONAS = 150
        const val ADC_MIN_VALIDO = 1
        const val ADC_MAX_VALIDO = 4095
        private const val ADC_FISICO_SECO = 4095.0
        private const val ADC_FISICO_MOJADO = 1200.0
        private const val MAX_EVENTOS_POR_AVANCE = 10_000

        private const val PAGINA =
            "<!doctype html><html lang=\"es\"><meta charset=\"utf-8\"><title>FertiTrack (simulador)</title>" +
                "<p>Robot FertiTrack simulado. La app usa /api/estado y las demás rutas /api/*.</p></html>"

        /** Como String(valor, n) de Arduino: n decimales con punto. */
        fun decimales(valor: Float, n: Int): String = String.format(Locale.ROOT, "%.${n}f", valor)

        /** ArduinoJson escribe los float enteros sin decimales ("300", "-1"). */
        private fun JsonObjectBuilder.putNumero(clave: String, valor: Float) {
            val entero = valor.isFinite() && valor == Math.floor(valor.toDouble()).toFloat() && kotlin.math.abs(valor) < 1e9f
            put(clave, if (entero) JsonPrimitive(valor.toLong()) else JsonPrimitive(valor))
        }

        /** String.toFloat() de Arduino (atof): el prefijo numérico válido, o 0 si no hay. */
        fun aFloatArduino(texto: String): Float {
            val prefijo = Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(texto)?.value?.trim()
            return prefijo?.toFloatOrNull() ?: 0f
        }

        /** String.toInt() de Arduino (atol): el prefijo entero válido, o 0 si no hay. */
        fun aEnteroArduino(texto: String): Long {
            val prefijo = Regex("^\\s*[+-]?\\d+").find(texto)?.value?.trim() ?: return 0
            return prefijo.toLongOrNull() ?: 0
        }
    }
}
