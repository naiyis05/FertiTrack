package com.fertitrack.app.ui.componentes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Momentos del día del cielo de Zonas, en el orden en que pasan. [inicioMin] es el minuto
 * del día en que empieza cada fase; [minutoMuestra], el que se muestra al elegirla.
 */
enum class FaseDia(val nombre: String, val emoji: String, val inicioMin: Int, val minutoMuestra: Int) {
    AMANECER("Amanecer", "🌅", 5 * 60, 6 * 60 + 15),
    MANANA("Mañana", "🌤️", 7 * 60, 9 * 60),
    MEDIODIA("Mediodía", "☀️", 11 * 60 + 30, 12 * 60 + 30),
    TARDE("Tarde", "⛅", 14 * 60, 15 * 60 + 45),
    ATARDECER("Atardecer", "🌇", 17 * 60 + 30, 18 * 60),
    NOCHE("Noche", "🌙", 19 * 60, 21 * 60),
}

/** Degradado vertical del cielo. */
data class ColoresCielo(val arriba: Color, val abajo: Color)

/**
 * Reloj del cielo: fase, posición del sol y de la luna y colores para cualquier minuto del
 * día (0 ≤ minuto < 1440, con fracciones para animar). Funciones puras.
 *
 * Horas fijas pensadas para el trópico, donde el sol sale y se pone cerca de las 6:00 y las
 * 18:00 todo el año: así no hace falta pedir la ubicación del teléfono.
 */
object CicloDia {
    const val MINUTOS_DIA = 1440

    const val SALIDA_SOL = 5 * 60 + 45
    const val PUESTA_SOL = 18 * 60 + 15

    // La luna sale antes de que se ponga el sol y se pone después de que salga: en el
    // amanecer y el atardecer se ven los dos.
    const val SALIDA_LUNA = 18 * 60
    const val PUESTA_LUNA = 6 * 60 + 15

    /** Las animaciones más cortas que esto (en minutos de cielo) retroceden en vez de dar la vuelta. */
    private const val RETROCESO_MAXIMO = 3 * 60

    fun normalizar(minuto: Float): Float = ((minuto % MINUTOS_DIA) + MINUTOS_DIA) % MINUTOS_DIA

    fun fase(minuto: Float): FaseDia {
        val m = normalizar(minuto)
        return FaseDia.entries.lastOrNull { it.inicioMin <= m } ?: FaseDia.NOCHE
    }

    /** Recorrido 0..1 del sol entre su salida y su puesta; null si está bajo el horizonte. */
    fun avanceSol(minuto: Float): Float? = avance(normalizar(minuto), SALIDA_SOL, PUESTA_SOL)

    /** Recorrido 0..1 de la luna; null si está bajo el horizonte. */
    fun avanceLuna(minuto: Float): Float? = avance(normalizar(minuto), SALIDA_LUNA, PUESTA_LUNA)

    /** Altura 0..1 sobre el horizonte para un recorrido 0..1 (arco). */
    fun altura(avance: Float): Float = sin(PI * avance.coerceIn(0f, 1f)).toFloat()

    /** 0 a pleno día, 1 en noche cerrada; sube y baja de forma continua. */
    fun oscuridad(minuto: Float): Float = interpolar(OSCURIDAD, normalizar(minuto)) { a, b, t -> a + (b - a) * t }

    fun cielo(minuto: Float): ColoresCielo = interpolar(CIELO, normalizar(minuto)) { a, b, t ->
        ColoresCielo(lerp(a.arriba, b.arriba, t), lerp(a.abajo, b.abajo, t))
    }

    /**
     * Destino sin dar vueltas para animar de [actual] (sin normalizar) a [objetivo] (0..1440):
     * avanza como el reloj, salvo que retroceder sea más corto que [RETROCESO_MAXIMO].
     */
    fun destinoAnimacion(actual: Float, objetivo: Float): Float {
        val adelante = normalizar(objetivo - normalizar(actual))
        val atras = MINUTOS_DIA - adelante
        return if (adelante != 0f && atras < RETROCESO_MAXIMO) actual - atras else actual + adelante
    }

    private fun avance(m: Float, salida: Int, puesta: Int): Float? {
        val duracion = normalizar((puesta - salida).toFloat())
        val transcurrido = normalizar(m - salida)
        return if (transcurrido <= duracion) transcurrido / duracion else null
    }

    private fun <T> interpolar(claves: List<Pair<Int, T>>, m: Float, mezclar: (T, T, Float) -> T): T {
        val i = claves.indexOfLast { it.first <= m }.coerceIn(0, claves.size - 2)
        val (desde, a) = claves[i]
        val (hasta, b) = claves[i + 1]
        val t = ((m - desde) / (hasta - desde)).coerceIn(0f, 1f)
        return mezclar(a, b, t)
    }

    private val OSCURIDAD: List<Pair<Int, Float>> = listOf(
        0 to 1f,
        5 * 60 to 1f,
        6 * 60 + 30 to 0f,
        17 * 60 + 30 to 0f,
        19 * 60 + 30 to 1f,
        MINUTOS_DIA to 1f,
    )

    // Arriba lo bastante oscuro para que el reloj blanco se lea a cualquier hora.
    private val NOCHE_CERRADA = ColoresCielo(Color(0xFF0B1026), Color(0xFF1C2A4D))
    private val CIELO: List<Pair<Int, ColoresCielo>> = listOf(
        0 to NOCHE_CERRADA,
        5 * 60 to ColoresCielo(Color(0xFF151C40), Color(0xFF3B3F6E)),
        5 * 60 + 45 to ColoresCielo(Color(0xFF353A75), Color(0xFFE58F6E)),
        6 * 60 + 40 to ColoresCielo(Color(0xFF3F67AE), Color(0xFFF7C28C)),
        8 * 60 to ColoresCielo(Color(0xFF2A78CF), Color(0xFFA8D5F2)),
        12 * 60 to ColoresCielo(Color(0xFF1C6CCB), Color(0xFF8CC7F2)),
        15 * 60 to ColoresCielo(Color(0xFF2A70C0), Color(0xFFB3D9F2)),
        17 * 60 + 15 to ColoresCielo(Color(0xFF3F5FA6), Color(0xFFF2B27A)),
        18 * 60 to ColoresCielo(Color(0xFF33397A), Color(0xFFEE8558)),
        18 * 60 + 45 to ColoresCielo(Color(0xFF1C2150), Color(0xFF6B4877)),
        20 * 60 to ColoresCielo(Color(0xFF0E1430), Color(0xFF202C52)),
        MINUTOS_DIA to NOCHE_CERRADA,
    )
}
