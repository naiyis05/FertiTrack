package com.fertitrack.app.ui.componentes

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import kotlin.math.roundToInt

const val TAG_GRAFICA_HUMEDAD = "grafica_humedad"

/**
 * Humedad (0–100 %) de las últimas mediciones del robot, una muestra por medición.
 * Con [calibracion] dibuja las líneas de umbral de SECA y HÚMEDA.
 * [compacta] quita las etiquetas de umbral (mini gráfica de Inicio).
 */
@Composable
fun GraficaHumedad(
    muestras: List<MuestraHumedad>,
    calibracion: CalibracionSensor?,
    modifier: Modifier = Modifier,
    altoArea: Dp = 200.dp,
    compacta: Boolean = false,
) {
    val ultimas = remember(muestras) { muestras.takeLast(RobotConfig.MAX_PUNTOS_GRAFICA) }
    if (ultimas.isEmpty()) {
        EstadoVacio(
            icono = Icons.AutoMirrored.Rounded.ShowChart,
            titulo = "Todavía no hay mediciones",
            detalle = "La gráfica se llena cuando el robot mide la humedad de una zona.",
            modifier = modifier,
        )
        return
    }

    val colores = FertiTrackTema.colores
    val colorLinea = colores.agua.acento
    val colorTexto = MaterialTheme.colorScheme.onSurfaceVariant
    val colorGuia = MaterialTheme.colorScheme.outlineVariant

    val modelo = remember(ultimas) {
        CartesianChartModel(
            LineCartesianLayerModel.build {
                series(x = ultimas.indices.toList(), y = ultimas.map { it.humedadPct.coerceIn(0f, 100f) })
            },
        )
    }

    val componentePunto = rememberShapeComponent(fill = Fill(colorLinea), shape = CircleShape)
    val puntos = remember(componentePunto, compacta) {
        LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(componentePunto, size = if (compacta) 6.dp else 8.dp),
        )
    }
    val linea = LineCartesianLayer.rememberLine(
        fill = remember(colorLinea) { LineCartesianLayer.LineFill.single(Fill(colorLinea)) },
        stroke = remember { LineCartesianLayer.LineStroke.Continuous(thickness = 3.dp) },
        areaFill = remember(colorLinea) {
            LineCartesianLayer.AreaFill.single(
                Fill(Brush.verticalGradient(listOf(colorLinea.copy(alpha = 0.28f), colorLinea.copy(alpha = 0.02f)))),
            )
        },
        pointProvider = puntos,
    )
    val capa = rememberLineCartesianLayer(
        lineProvider = remember(linea) { LineCartesianLayer.LineProvider.series(linea) },
        rangeProvider = remember { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = 100.0) },
    )

    val estiloEtiqueta = TextStyle(color = colorTexto, fontSize = if (compacta) 10.sp else 11.sp)
    val ejeY = VerticalAxis.rememberStart(
        line = rememberAxisLineComponent(fill = Fill(colorGuia)),
        label = rememberAxisLabelComponent(style = estiloEtiqueta),
        guideline = rememberAxisGuidelineComponent(fill = Fill(colorGuia)),
        valueFormatter = remember { CartesianValueFormatter.decimal(decimalCount = 0, suffix = " %") },
        itemPlacer = remember(compacta) { VerticalAxis.ItemPlacer.step({ if (compacta) 50.0 else 25.0 }) },
    )
    val formatoHora = remember(ultimas) {
        CartesianValueFormatter { _, x, _ ->
            ultimas.getOrNull(x.roundToInt())?.let { Formatos.horaCorta(it.momentoMs) } ?: " "
        }
    }
    val ejeX = HorizontalAxis.rememberBottom(
        line = rememberAxisLineComponent(fill = Fill(colorGuia)),
        label = rememberAxisLabelComponent(style = estiloEtiqueta),
        guideline = null,
        valueFormatter = formatoHora,
    )

    val decoraciones = if (calibracion == null) {
        emptyList()
    } else {
        listOf(
            lineaUmbral(
                y = calibracion.pctSeca.toDouble(),
                color = colores.peligro.acento,
                etiqueta = if (compacta) null else "Seca < ${calibracion.pctSeca} %",
            ),
            lineaUmbral(
                y = calibracion.pctHumeda.toDouble(),
                color = colores.exito.acento,
                etiqueta = if (compacta) null else "Húmeda ≥ ${calibracion.pctHumeda} %",
            ),
        )
    }

    val grafica = rememberCartesianChart(
        capa,
        startAxis = ejeY,
        bottomAxis = ejeX,
        decorations = decoraciones,
    )

    val descripcion = remember(ultimas) {
        val valores = ultimas.map { it.humedadPct }
        "Gráfica de humedad: ${ultimas.size} mediciones. Última ${Formatos.porcentaje(valores.last())}, " +
            "mínima ${Formatos.porcentaje(valores.min())}, máxima ${Formatos.porcentaje(valores.max())}."
    }

    ProvideVicoTheme(rememberM3VicoTheme(lineColor = colorGuia, textColor = colorTexto)) {
        CartesianChartHost(
            chart = grafica,
            model = modelo,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            chartAreaHeight = altoArea,
            modifier = modifier
                .fillMaxWidth()
                .testTag(TAG_GRAFICA_HUMEDAD)
                .semantics { contentDescription = descripcion },
        )
    }
}

@Composable
private fun lineaUmbral(y: Double, color: Color, etiqueta: String?): HorizontalLine {
    // Fondo del color de la tarjeta: la etiqueta se lee aunque la serie pase por debajo.
    val fondoEtiqueta = rememberShapeComponent(
        fill = Fill(FertiTrackTema.colores.tarjeta.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(4.dp),
    )
    val linea = rememberLineComponent(
        fill = Fill(color.copy(alpha = 0.85f)),
        thickness = 1.5.dp,
        shape = remember { DashedShape(dashLength = 6.dp, gapLength = 4.dp) },
    )
    val texto = if (etiqueta == null) {
        null
    } else {
        rememberTextComponent(
            style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            padding = Insets(horizontal = 4.dp, vertical = 1.dp),
            background = fondoEtiqueta,
        )
    }
    return remember(y, linea, texto, etiqueta) {
        HorizontalLine(
            y = { y },
            line = linea,
            labelComponent = texto,
            label = { etiqueta.orEmpty() },
        )
    }
}
