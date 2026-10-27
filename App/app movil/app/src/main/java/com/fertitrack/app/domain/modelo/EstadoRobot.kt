package com.fertitrack.app.domain.modelo

/** Valores de `estado` en /api/estado (nombreEstado() del Maestro v5.3). */
enum class EstadoMaquina {
    ESPERANDO,
    SENSADO_AVANZANDO, SENSADO_MIDIENDO, SENSADO_RETORNANDO,
    RIEGO_BAJANDO_BRAZO, RIEGO_AVANZANDO, RIEGO_SUBIENDO_BRAZO, RIEGO_RETORNANDO,
    FERTI_BAJANDO_BRAZO, FERTI_AVANZANDO, FERTI_SUBIENDO_BRAZO, FERTI_RETORNANDO,
    RECUP_ESPERA, RECUP_BRAZO_RIEGO, RECUP_BRAZO_FERTI, RECUP_RETORNANDO,
    ARRANQUE,
    PRUEBA_AVANZANDO, PRUEBA_LISTA, PRUEBA_RETORNANDO, PRUEBA_SENSOR,
    EMERGENCIA,

    /** Cualquier texto no reconocido (el firmware envía "?" en el default). */
    DESCONOCIDO;

    companion object {
        fun desdeCodigo(codigo: String): EstadoMaquina {
            val limpio = codigo.trim()
            return entries.firstOrNull { it != DESCONOCIDO && it.name == limpio } ?: DESCONOCIDO
        }
    }
}

/** Valores de `modo_rutina`. */
enum class ModoRutina {
    NINGUNA, SENSADO, RIEGO, FERTILIZACION, DESCONOCIDO;

    companion object {
        fun desdeCodigo(codigo: String): ModoRutina {
            val limpio = codigo.trim()
            return entries.firstOrNull { it != DESCONOCIDO && it.name == limpio } ?: DESCONOCIDO
        }
    }
}

/** `nivel_suelo` y cada elemento de `zonas[]`. Solo SECA se riega. */
enum class NivelSuelo(val codigo: Int) {
    SIN_MEDIR(0), SECA(1), INTERMEDIA(2), HUMEDA(3), SOBRE_HUMEDA(4), DESCONOCIDO(-1);

    companion object {
        fun desdeCodigo(codigo: Int): NivelSuelo =
            entries.firstOrNull { it != DESCONOCIDO && it.codigo == codigo } ?: DESCONOCIDO
    }
}

/** `actividad`. */
enum class Actividad(val codigo: Int) {
    REPOSO(0), SENSANDO(1), REGANDO(2), FERTILIZANDO(3), EN_MOVIMIENTO(4), DESCONOCIDA(-1);

    companion object {
        fun desdeCodigo(codigo: Int): Actividad =
            entries.firstOrNull { it != DESCONOCIDA && it.codigo == codigo } ?: DESCONOCIDA
    }
}

/** `falla`. Los únicos códigos que declara el firmware son 1, 2, 3, 8 y 9. */
enum class TipoFalla(val codigo: Int) {
    SIN_FALLA(0),
    ESP_A_NO_RESPONDE(1),
    ESP_B_NO_RESPONDE(2),
    SENSOR_SIN_LECTURA(3),
    PARADA_DESDE_APP(8),
    BOTON_EMERGENCIA(9),
    DESCONOCIDA(-1);

    companion object {
        fun desdeCodigo(codigo: Int): TipoFalla =
            entries.firstOrNull { it != DESCONOCIDA && it.codigo == codigo } ?: DESCONOCIDA
    }
}

/** Calibración del sensor tal como la reporta /api/estado (ADC alto = suelo seco). */
data class CalibracionSensor(
    val adcSeco: Int,
    val adcMojado: Int,
    val pctSeca: Int,
    val pctHumeda: Int,
    val pctSobre: Int,
    val umbralSeco: Int,
    val umbralHumedo: Int,
    val umbralSobre: Int,
)

/** Una respuesta de /api/estado ya validada y traducida a tipos del dominio. */
data class EstadoRobot(
    val estado: EstadoMaquina,
    /** Texto tal cual llegó; permite mostrar estados desconocidos sin perder información. */
    val estadoTexto: String,
    val modoRutina: ModoRutina,
    /** Zona en curso, 1..totalZonas; 0 si no hay zonas configuradas. */
    val zonaActual: Int,
    val totalZonas: Int,
    val distanciaCm: Float,
    val anchoZonaCm: Float,
    val pasosPorCm: Float,
    val posicionPasos: Long,
    /** Última lectura cruda del sensor; null si el firmware envía -1 (sin lectura). */
    val humedadAdc: Int?,
    /** Humedad calibrada por el firmware (0 = cal_seco, 100 = cal_mojado); null si no hay lectura. */
    val humedadPct: Float?,
    val calibracion: CalibracionSensor,
    /** Nivel de la ÚLTIMA zona medida; no es un nivel global del invernadero. */
    val nivelSuelo: NivelSuelo,
    val actividad: Actividad,
    val falla: TipoFalla,
    /** Código de falla tal cual llegó (0 = sin falla); útil si es un código desconocido. */
    val codigoFalla: Int,
    val espBConectada: Boolean,
    val espCConectada: Boolean,
    val autoActivo: Boolean,
    val descansoSeg: Long,
    /** Segundos para el próximo ciclo automático; null si no hay espera programada (-1). */
    val proximoCicloSeg: Long?,
    /** Clasificación por zona; índice 0 = zona 1. */
    val zonas: List<NivelSuelo>,
) {
    val hayFalla: Boolean get() = codigoFalla != 0

    /** Regla de seguridad: solo en ESPERANDO y sin falla se permiten rutinas. */
    val listoParaRutinas: Boolean get() = estado == EstadoMaquina.ESPERANDO && !hayFalla

    val distanciaConfigurada: Boolean get() = distanciaCm > 0f && totalZonas > 0

    /** El relé de riego solo se enciende sobre zonas secas con el brazo abajo (actividad 2). */
    val bombaRiegoEncendida: Boolean
        get() = estado == EstadoMaquina.RIEGO_AVANZANDO && actividad == Actividad.REGANDO

    /** El relé de fertilizante solo está encendido en FERTI_AVANZANDO. */
    val bombaFertilizanteEncendida: Boolean get() = estado == EstadoMaquina.FERTI_AVANZANDO

    /** Fracción 0..1 del riel recorrida según posicion_pasos; null si no hay distancia. */
    val fraccionRecorrido: Float?
        get() {
            val totalPasos = distanciaCm * pasosPorCm
            return if (totalPasos > 0f) (posicionPasos / totalPasos).coerceIn(0f, 1f) else null
        }
}

/** Parámetro `modo` de /api/iniciar. */
enum class ModoInicio(val parametro: String) {
    CICLO("ciclo"), SENSADO("sensado"), FERTILIZAR("fertilizar")
}

/** Parámetro `punto` de /api/cal_sensor. */
enum class PuntoCalibracion(val parametro: String) {
    SECO("seco"), MOJADO("mojado")
}
