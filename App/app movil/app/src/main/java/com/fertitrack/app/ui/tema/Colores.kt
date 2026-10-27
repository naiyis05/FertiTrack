package com.fertitrack.app.ui.tema

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.fertitrack.app.domain.modelo.NivelSuelo

/*
 * Paleta FertiTrack. Parte de la app de escritorio (#22C55E y fondos #F0FDF4, #EFF6FF,
 * #FFFBEB, #F3F4F6) y de la página web del Maestro (niveles .z0–.z4), ajustada para que
 * todo texto llegue a 4,5:1. El #22C55E puro con texto blanco no llega (2,3:1), así que
 * se usa solo como acento; el primario claro es el verde 700 (#15803D, 5,0:1).
 */

/** Verde de marca de la app de escritorio: solo acento (bordes, puntos, logotipo). */
val VerdeMarca = Color(0xFF22C55E)

private val Verde50 = Color(0xFFF0FDF4)
private val Verde100 = Color(0xFFDCFCE7)
private val Verde200 = Color(0xFFBBF7D0)
private val Verde300 = Color(0xFF86EFAC)
private val Verde400 = Color(0xFF4ADE80)
private val Verde700 = Color(0xFF15803D)
private val Verde800 = Color(0xFF166534)
private val Verde900 = Color(0xFF14532D)

val EsquemaClaro: ColorScheme = lightColorScheme(
    primary = Verde700,
    onPrimary = Color.White,
    primaryContainer = Verde100,
    onPrimaryContainer = Verde900,
    inversePrimary = Verde400,
    secondary = Color(0xFF3F6B52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF0F3D26),
    tertiary = Color(0xFF1D4ED8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFF4F8F5),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF4F8F5),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE3EAE5),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceTint = Verde700,
    inverseSurface = Color(0xFF1F2A23),
    inverseOnSurface = Color(0xFFEEF5F0),
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFD5DDD8),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFAFCFB),
    surfaceDim = Color(0xFFDCE3DE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFB),
    surfaceContainer = Color(0xFFEDF3EF),
    surfaceContainerHigh = Color(0xFFE7EEE9),
    surfaceContainerHighest = Color(0xFFE1E8E3),
)

val EsquemaOscuro: ColorScheme = darkColorScheme(
    primary = Verde400,
    onPrimary = Color(0xFF04301A),
    primaryContainer = Verde900,
    onPrimaryContainer = Verde200,
    inversePrimary = Verde700,
    secondary = Color(0xFF9ED3B4),
    onSecondary = Color(0xFF0A2E1C),
    secondaryContainer = Color(0xFF1E3B2B),
    onSecondaryContainer = Color(0xFFC8EED7),
    tertiary = Color(0xFF93C5FD),
    onTertiary = Color(0xFF0B2A5B),
    tertiaryContainer = Color(0xFF1E3A8A),
    onTertiaryContainer = Color(0xFFDBEAFE),
    background = Color(0xFF0D1611),
    onBackground = Color(0xFFE3EAE5),
    surface = Color(0xFF0D1611),
    onSurface = Color(0xFFE3EAE5),
    surfaceVariant = Color(0xFF2A362F),
    onSurfaceVariant = Color(0xFFBAC6BE),
    surfaceTint = Verde400,
    inverseSurface = Color(0xFFE3EAE5),
    inverseOnSurface = Color(0xFF1F2A23),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF5B0B0B),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = Color(0xFF8A978F),
    outlineVariant = Color(0xFF34413A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C3730),
    surfaceDim = Color(0xFF0D1611),
    surfaceContainerLowest = Color(0xFF08100B),
    surfaceContainerLow = Color(0xFF141E18),
    surfaceContainer = Color(0xFF1C2820),
    surfaceContainerHigh = Color(0xFF232F28),
    surfaceContainerHighest = Color(0xFF2A3730),
)

/**
 * Un tono con significado (agua, fertilizante, peligro…).
 *
 * [acento] sirve para iconos, puntos y rellenos de botón con texto [sobreAcento];
 * [contenedor] es el fondo suave de tarjetas y franjas, con texto [sobreContenedor].
 */
@Immutable
data class TonoSemantico(
    val acento: Color,
    val sobreAcento: Color,
    val contenedor: Color,
    val sobreContenedor: Color,
)

/** Colores de un nivel de suelo: fondo de la celda y texto legible encima. */
@Immutable
data class ColoresNivel(val fondo: Color, val texto: Color)

@Immutable
data class ColoresFertiTrack(
    val exito: TonoSemantico,
    /** Agua y riego. */
    val agua: TonoSemantico,
    val sensado: TonoSemantico,
    val fertilizante: TonoSemantico,
    /** Advertencias: acciones con bombas o movimiento, datos obsoletos. */
    val aviso: TonoSemantico,
    val peligro: TonoSemantico,
    val neutro: TonoSemantico,
    /** Pruebas y calibración (violeta, como .btn-prueba de la página del Maestro). */
    val prueba: TonoSemantico,
    /** Botón DETENER: igual en claro y oscuro para que siempre se reconozca. */
    val parada: Color,
    val sobreParada: Color,
    /** Fondo de tarjetas: blanco en claro; en oscuro, un tono más claro que el fondo (no más oscuro). */
    val tarjeta: Color,
    val niveles: Map<NivelSuelo, ColoresNivel>,
) {
    fun nivel(nivel: NivelSuelo): ColoresNivel = niveles.getValue(nivel)
}

/*
 * Niveles con los mismos tonos que la página del Maestro (.z0 gris, .z1 rojo, .z2 ámbar,
 * .z3 verde, .z4 azul) en una variante más oscura para que el número de zona en blanco se
 * lea a 4,5:1 (#F44336, #4CAF50 y #2196F3 originales no llegan).
 */
private val NivelesComunes: Map<NivelSuelo, ColoresNivel> = mapOf(
    NivelSuelo.SIN_MEDIR to ColoresNivel(Color(0xFFBDBDBD), Color(0xFF1F2937)),
    NivelSuelo.SECA to ColoresNivel(Color(0xFFD32F2F), Color.White),
    NivelSuelo.INTERMEDIA to ColoresNivel(Color(0xFFFFC107), Color(0xFF1F2937)),
    NivelSuelo.HUMEDA to ColoresNivel(Color(0xFF2E7D32), Color.White),
    NivelSuelo.SOBRE_HUMEDA to ColoresNivel(Color(0xFF1565C0), Color.White),
    NivelSuelo.DESCONOCIDO to ColoresNivel(Color(0xFF757575), Color.White),
)

private val RojoParada = Color(0xFFC62828)

val ColoresClaros = ColoresFertiTrack(
    exito = TonoSemantico(Verde700, Color.White, Verde50, Verde800),
    agua = TonoSemantico(Color(0xFF1D4ED8), Color.White, Color(0xFFEFF6FF), Color(0xFF1E3A8A)),
    sensado = TonoSemantico(Color(0xFF0E7490), Color.White, Color(0xFFECFEFF), Color(0xFF164E63)),
    fertilizante = TonoSemantico(Color(0xFFB45309), Color.White, Color(0xFFFFFBEB), Color(0xFF78350F)),
    aviso = TonoSemantico(Color(0xFFC2410C), Color.White, Color(0xFFFFF4E5), Color(0xFF7C2D12)),
    peligro = TonoSemantico(Color(0xFFB91C1C), Color.White, Color(0xFFFEF2F2), Color(0xFF7F1D1D)),
    neutro = TonoSemantico(Color(0xFF475569), Color.White, Color(0xFFF3F4F6), Color(0xFF1F2937)),
    prueba = TonoSemantico(Color(0xFF7E22CE), Color.White, Color(0xFFFAF5FF), Color(0xFF581C87)),
    parada = RojoParada,
    sobreParada = Color.White,
    tarjeta = Color.White,
    niveles = NivelesComunes,
)

private val TextoSobreAcentoOscuro = Color(0xFF0B1410)

val ColoresOscuros = ColoresFertiTrack(
    exito = TonoSemantico(Verde400, TextoSobreAcentoOscuro, Color(0xFF12321F), Verde200),
    agua = TonoSemantico(Color(0xFF60A5FA), TextoSobreAcentoOscuro, Color(0xFF13243F), Color(0xFFDBEAFE)),
    sensado = TonoSemantico(Color(0xFF22D3EE), TextoSobreAcentoOscuro, Color(0xFF0C2A31), Color(0xFFCFFAFE)),
    fertilizante = TonoSemantico(Color(0xFFFBBF24), TextoSobreAcentoOscuro, Color(0xFF34250A), Color(0xFFFEF3C7)),
    aviso = TonoSemantico(Color(0xFFFB923C), TextoSobreAcentoOscuro, Color(0xFF361C0B), Color(0xFFFFEDD5)),
    peligro = TonoSemantico(Color(0xFFF87171), TextoSobreAcentoOscuro, Color(0xFF3A1414), Color(0xFFFEE2E2)),
    neutro = TonoSemantico(Color(0xFF94A3B8), TextoSobreAcentoOscuro, Color(0xFF1E2621), Color(0xFFE2E8F0)),
    prueba = TonoSemantico(Color(0xFFC084FC), TextoSobreAcentoOscuro, Color(0xFF2A1A3D), Color(0xFFF3E8FF)),
    parada = RojoParada,
    sobreParada = Color.White,
    tarjeta = Color(0xFF141E18),
    niveles = NivelesComunes,
)
