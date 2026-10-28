package com.fertitrack.app.ui.componentes

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.tema.FertiTrackTema
import kotlin.math.PI
import kotlin.math.sin

const val TAG_TANQUE = "tanque_humedad"

/** Misma regla que clasificarHumedad() del firmware, expresada en porcentaje. */
fun nivelDePorcentaje(pct: Float, calibracion: CalibracionSensor): NivelSuelo = when {
    pct >= calibracion.pctSobre -> NivelSuelo.SOBRE_HUMEDA
    pct >= calibracion.pctHumeda -> NivelSuelo.HUMEDA
    pct > calibracion.pctSeca -> NivelSuelo.INTERMEDIA
    else -> NivelSuelo.SECA
}

/**
 * Tanque en forma de cápsula lleno hasta [pct] con el color de [nivel]; la superficie ondea.
 * [marcas] son los umbrales (%) que se marcan en el costado. Sin [pct], queda vacío.
 */
@Composable
fun TanqueHumedad(
    pct: Float?,
    nivel: NivelSuelo,
    marcas: List<Int>,
    modifier: Modifier = Modifier,
) {
    val llenado by animateFloatAsState(
        targetValue = (pct ?: 0f).coerceIn(0f, 100f) / 100f,
        animationSpec = tween(1_200, easing = FastOutSlowInEasing),
        label = "llenado",
    )
    val ola by rememberInfiniteTransition(label = "tanque").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
        label = "ola",
    )
    val base = if (pct == null) MaterialTheme.colorScheme.outline else FertiTrackTema.colores.nivel(nivel).fondo
    val vidrio = MaterialTheme.colorScheme.surfaceContainerLow
    val contorno = MaterialTheme.colorScheme.surfaceContainerHighest
    val colorMarca = MaterialTheme.colorScheme.outline
    val halo = FertiTrackTema.colores.tarjeta

    Box(
        modifier
            .testTag(TAG_TANQUE)
            .semantics {
                contentDescription = if (pct == null) {
                    "Tanque de humedad sin lectura"
                } else {
                    "Tanque de humedad al ${Formatos.porcentaje(pct)}, ${Traductor.nombreNivel(nivel)}"
                }
            }
            .drawWithCache {
                val borde = 3.dp.toPx()
                val radio = CornerRadius(size.width / 2)
                val interior = Path().apply {
                    addRoundRect(
                        RoundRect(borde, borde, size.width - borde, size.height - borde, CornerRadius(size.width / 2 - borde)),
                    )
                }
                val amplitud = 5.dp.toPx()
                val paso = 3.dp.toPx()
                val claro = lerp(base, Color.White, 0.4f)

                fun superficie(nivelY: Float, fase: Float): Path = Path().apply {
                    moveTo(0f, size.height)
                    var x = 0f
                    while (x <= size.width + paso) {
                        lineTo(x, nivelY + sin(x / size.width * 2.4f * PI.toFloat() + fase) * amplitud)
                        x += paso
                    }
                    lineTo(size.width, size.height)
                    close()
                }

                onDrawBehind {
                    drawRoundRect(vidrio, cornerRadius = radio)
                    clipPath(interior) {
                        if (pct != null && llenado > 0f) {
                            // Margen para que la ola no asome por arriba con el tanque lleno.
                            val nivelY = borde + (size.height - 2 * borde) * (1f - llenado) + amplitud * llenado
                            drawPath(superficie(nivelY, ola + PI.toFloat()), base.copy(alpha = 0.4f))
                            drawPath(
                                superficie(nivelY + amplitud * 0.6f, ola),
                                Brush.verticalGradient(listOf(claro, base), startY = nivelY, endY = size.height),
                            )
                        }
                        // Reflejo del vidrio.
                        drawRoundRect(
                            Color.White.copy(alpha = 0.22f),
                            topLeft = Offset(size.width * 0.17f, size.height * 0.08f),
                            size = Size(size.width * 0.11f, size.height * 0.7f),
                            cornerRadius = CornerRadius(size.width * 0.06f),
                        )
                    }
                    marcas.forEach { p ->
                        val y = size.height * (1f - p / 100f)
                        drawLine(
                            colorMarca.copy(alpha = 0.7f),
                            Offset(borde + 7.dp.toPx(), y),
                            Offset(borde + 19.dp.toPx(), y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    drawRoundRect(contorno, cornerRadius = radio, style = Stroke(borde))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (pct == null) Formatos.SIN_DATO else Formatos.porcentaje(pct),
            style = MaterialTheme.typography.headlineMedium.copy(
                // Halo del color de la tarjeta: se lee sobre el líquido y sobre el vidrio.
                shadow = Shadow(halo, Offset.Zero, blurRadius = 12f),
            ),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Leyenda del tanque: de más húmeda a más seca, con los rangos reales y el nivel actual en negrita. */
@Composable
fun LeyendaTanque(actual: NivelSuelo?, calibracion: CalibracionSensor, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NivelesMedidos.reversed().forEach { nivel ->
            val elegido = nivel == actual
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 24.dp).semantics(mergeDescendants = true) {},
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(FertiTrackTema.colores.nivel(nivel).fondo),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${Traductor.nombreNivel(nivel)} ${rangoNivel(nivel, calibracion)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (elegido) FontWeight.Bold else FontWeight.Normal,
                    color = if (elegido) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Píldora "En vivo" (datos al día) o "Sin actualizar". */
@Composable
fun PildoraEnVivo(enVivo: Boolean, modifier: Modifier = Modifier) {
    val tono = if (enVivo) FertiTrackTema.colores.exito else FertiTrackTema.colores.neutro
    Surface(shape = CircleShape, color = tono.contenedor, contentColor = tono.sobreContenedor, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Punto(tono.acento, tamano = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (enVivo) "EN VIVO" else "SIN ACTUALIZAR",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

/** "Valor ADC 2873" en monoespaciada, como en el mockup. */
@Composable
fun TextoAdc(adc: Int?, modifier: Modifier = Modifier) {
    Text(
        "Valor ADC ${adc ?: Formatos.SIN_DATO}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
