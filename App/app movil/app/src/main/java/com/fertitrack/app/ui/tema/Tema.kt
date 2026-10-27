package com.fertitrack.app.ui.tema

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LocalColoresFertiTrack = staticCompositionLocalOf { ColoresClaros }

/** Esquinas: tarjetas de 20 dp (large) y elementos internos de 12–16 dp. */
private val Formas = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Tipografía de Material 3 con números y títulos más firmes: la app se usa en el
 * invernadero, a menudo al sol, y las cifras deben leerse de un vistazo.
 */
private val Tipografia: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
}

/**
 * Tema de FertiTrack: esquemas claro y oscuro propios (sin color dinámico, para conservar
 * la marca) y colores semánticos en [FertiTrackTema.colores].
 */
@Composable
fun FertiTrackTema(
    oscuro: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalColoresFertiTrack provides if (oscuro) ColoresOscuros else ColoresClaros) {
        MaterialTheme(
            colorScheme = if (oscuro) EsquemaOscuro else EsquemaClaro,
            shapes = Formas,
            typography = Tipografia,
            content = content,
        )
    }
}

object FertiTrackTema {
    /** Colores con significado (agua, fertilizante, peligro, niveles de suelo…). */
    val colores: ColoresFertiTrack
        @Composable @ReadOnlyComposable get() = LocalColoresFertiTrack.current

    /** Estilo de las cifras grandes (humedad, KPIs). */
    val estiloCifra: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.displayMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
}
