package com.fertitrack.app.ui.componentes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.tema.FertiTrackTema
import kotlin.math.roundToInt

const val TAG_RIEL = "mapa_riel"

/**
 * Riel visto desde arriba: una franja con cada zona coloreada por nivel, la zona actual
 * recuadrada y la posición del robot ([fraccion] 0..1 del recorrido). Sirve igual con
 * 1 zona que con 150: con muchas zonas las celdas se juntan sin separación.
 */
@Composable
fun RielZonas(
    zonas: List<NivelSuelo>,
    zonaActual: Int,
    fraccion: Float?,
    modifier: Modifier = Modifier,
) {
    val coloresNivel = FertiTrackTema.colores.niveles
    val colorPista = MaterialTheme.colorScheme.surfaceContainerHighest
    val colorMarco = MaterialTheme.colorScheme.onSurface
    val colorRobot = MaterialTheme.colorScheme.primary
    val colorFondoRobot = FertiTrackTema.colores.tarjeta
    val descripcion = buildString {
        append("Riel con ${zonas.size} ${if (zonas.size == 1) "zona" else "zonas"}")
        if (zonaActual in 1..zonas.size) append(", zona actual $zonaActual")
        if (fraccion != null) append(", robot al ${(fraccion * 100).roundToInt()} % del recorrido")
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(TAG_RIEL)
            .semantics { contentDescription = descripcion },
    ) {
        val altoBarra = 22.dp.toPx()
        val arriba = size.height - altoBarra
        val radio = CornerRadius(6.dp.toPx())
        drawRoundRect(colorPista, topLeft = Offset(0f, arriba), size = Size(size.width, altoBarra), cornerRadius = radio)
        if (zonas.isNotEmpty()) {
            val ancho = size.width / zonas.size
            val hueco = if (ancho > 6.dp.toPx()) 1.5.dp.toPx() else 0f
            zonas.forEachIndexed { i, nivel ->
                val color = (coloresNivel[nivel] ?: coloresNivel.getValue(NivelSuelo.DESCONOCIDO)).fondo
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * ancho + hueco / 2, arriba),
                    size = Size((ancho - hueco).coerceAtLeast(1f), altoBarra),
                    cornerRadius = if (hueco > 0f) CornerRadius(3.dp.toPx()) else CornerRadius.Zero,
                )
            }
            if (zonaActual in 1..zonas.size) {
                val trazo = 2.5.dp.toPx()
                drawRoundRect(
                    color = colorMarco,
                    topLeft = Offset((zonaActual - 1) * ancho + trazo / 2, arriba - trazo / 2),
                    size = Size((ancho - trazo).coerceAtLeast(trazo), altoBarra + trazo),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = trazo),
                )
            }
        }
        if (fraccion != null) {
            val x = (fraccion.coerceIn(0f, 1f) * size.width).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
            val tam = 9.dp.toPx()
            val punta = arriba - 3.dp.toPx()
            drawLine(colorRobot, Offset(x, arriba), Offset(x, size.height), strokeWidth = 3.dp.toPx())
            val triangulo = Path().apply {
                moveTo(x - tam, punta - tam * 1.4f)
                lineTo(x + tam, punta - tam * 1.4f)
                lineTo(x, punta)
                close()
            }
            drawPath(triangulo, colorRobot)
            drawCircle(colorRobot, radius = tam * 0.85f, center = Offset(x, punta - tam * 1.6f))
            drawCircle(colorFondoRobot, radius = tam * 0.38f, center = Offset(x, punta - tam * 1.6f))
        }
    }
}

/** Etiquetas "Origen" y "Fin" debajo del riel. */
@Composable
fun ExtremosRiel(distancia: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Origen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Fin · $distancia", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Celdas numeradas, una por zona, con el color de su nivel. */
@Composable
fun CuadriculaZonas(zonas: List<NivelSuelo>, zonaActual: Int, modifier: Modifier = Modifier) {
    val lado: Dp = when {
        zonas.size <= 12 -> 52.dp
        zonas.size <= 40 -> 44.dp
        else -> 38.dp
    }
    val colorMarco = MaterialTheme.colorScheme.onSurface
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        zonas.forEachIndexed { i, nivel ->
            val numero = i + 1
            val colores = FertiTrackTema.colores.nivel(nivel)
            val actual = numero == zonaActual
            Box(
                modifier = Modifier
                    .size(lado)
                    .clip(MaterialTheme.shapes.small)
                    .background(colores.fondo)
                    .then(
                        if (actual) Modifier.border(3.dp, colorMarco, MaterialTheme.shapes.small) else Modifier,
                    )
                    .semantics {
                        contentDescription = "Zona $numero: ${Traductor.nombreNivel(nivel)}" +
                            if (actual) ", zona actual" else ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        numero.toString(),
                        color = colores.texto,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (lado >= 44.dp) 16.sp else 13.sp,
                    )
                    // En celdas pequeñas no cabe: basta con el recuadro.
                    if (actual && lado >= 44.dp) {
                        Text("aquí", color = colores.texto, fontSize = 9.sp, lineHeight = 10.sp)
                    }
                }
            }
        }
    }
}
