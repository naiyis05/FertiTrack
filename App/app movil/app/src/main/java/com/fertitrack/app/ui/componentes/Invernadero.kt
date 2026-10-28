package com.fertitrack.app.ui.componentes

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.traduccion.CategoriaEstado
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.tema.FertiTrackTema
import kotlin.math.roundToInt

const val TAG_TARJETAS_ZONA = "tarjetas_zona"
const val TAG_RIEL = "mapa_riel"
const val TAG_MOSAICO_ESTADO = "mosaico_estado"
const val TAG_AVISO_RIEGO = "aviso_riego"
const val TAG_BOTON_REGAR = "boton_regar"

/** Hasta cuántas zonas el riel dibuja un nodo y una etiqueta por zona. */
private const val MAX_NODOS_RIEL = 10

private val AnchoTarjetaZona = 112.dp
private val SeparacionZonas = 10.dp

/** Plantas que ilustran cada nivel (los emojis del sistema ya traen color). */
fun emojiNivel(nivel: NivelSuelo): String = when (nivel) {
    NivelSuelo.SECA -> "🌵🌵"
    NivelSuelo.INTERMEDIA -> "🌱🌱"
    NivelSuelo.HUMEDA -> "🌿🌿"
    NivelSuelo.SOBRE_HUMEDA -> "🌿💧"
    NivelSuelo.SIN_MEDIR -> "🌱"
    NivelSuelo.DESCONOCIDO -> "❔"
}

/** Relleno de la barra de una zona sin porcentaje medido: el centro aproximado de su nivel. */
private fun fraccionTipica(nivel: NivelSuelo): Float = when (nivel) {
    NivelSuelo.SECA -> 0.2f
    NivelSuelo.INTERMEDIA -> 0.45f
    NivelSuelo.HUMEDA -> 0.72f
    NivelSuelo.SOBRE_HUMEDA -> 0.92f
    NivelSuelo.SIN_MEDIR, NivelSuelo.DESCONOCIDO -> 0f
}

/**
 * Una tarjeta por zona con su planta, su humedad y una barra del color de su nivel. Si caben
 * todas, ocupan el ancho; si no (hasta 150 zonas), se desplazan y siguen a la zona actual.
 * [medidas] trae la última medición de la sesión de cada zona (se usa solo si coincide con
 * el nivel que reporta el robot: si no, el porcentaje sería de una medición vieja).
 */
@Composable
fun TarjetasZona(
    zonas: List<NivelSuelo>,
    zonaActual: Int,
    medidas: Map<Int, MuestraHumedad>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag(TAG_TARJETAS_ZONA)) {
        val caben = AnchoTarjetaZona * zonas.size + SeparacionZonas * (zonas.size - 1) <= maxWidth
        if (caben) {
            Row(horizontalArrangement = Arrangement.spacedBy(SeparacionZonas)) {
                zonas.forEachIndexed { i, nivel ->
                    TarjetaZona(i + 1, nivel, medidas[i + 1], actual = zonaActual == i + 1, Modifier.weight(1f))
                }
            }
        } else {
            val lista = rememberLazyListState()
            LaunchedEffect(zonaActual, zonas.size) {
                if (zonaActual in 1..zonas.size) lista.animateScrollToItem((zonaActual - 2).coerceAtLeast(0))
            }
            LazyRow(state = lista, horizontalArrangement = Arrangement.spacedBy(SeparacionZonas)) {
                itemsIndexed(zonas) { i, nivel ->
                    TarjetaZona(i + 1, nivel, medidas[i + 1], actual = zonaActual == i + 1, Modifier.width(AnchoTarjetaZona))
                }
            }
        }
    }
}

@Composable
private fun TarjetaZona(numero: Int, nivel: NivelSuelo, medida: MuestraHumedad?, actual: Boolean, modifier: Modifier) {
    val colores = FertiTrackTema.colores
    val colorNivel = colores.nivel(nivel).fondo
    val pct = medida?.takeIf { it.nivel == nivel }?.humedadPct
    val valor = pct?.let { Formatos.porcentaje(it) } ?: Traductor.nombreNivel(nivel)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (actual) colores.exito.contenedor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (actual) BorderStroke(2.dp, colores.exito.acento) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = buildString {
                append("Zona $numero: ${Traductor.nombreNivel(nivel)}")
                if (pct != null) append(", ${Formatos.porcentaje(pct)}")
                if (actual) append(", zona actual")
            }
        },
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actual) {
                    Punto(colores.exito.acento, tamano = 7.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    "ZONA $numero",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.sp,
                    fontWeight = if (actual) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (actual) colores.exito.sobreContenedor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                emojiNivel(nivel),
                fontSize = 24.sp,
                modifier = if (nivel == NivelSuelo.SIN_MEDIR) Modifier.alpha(0.45f) else Modifier,
            )
            Text(
                valor,
                style = if (pct != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (nivel == NivelSuelo.SECA) colores.peligro.acento else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            BarraNivel(
                fraccion = pct?.let { it / 100f } ?: fraccionTipica(nivel),
                color = colorNivel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BarraNivel(fraccion: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraccion.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Riel visto de lado: la pista con un nodo por zona (coloreado por su nivel), la zona actual
 * resaltada y el robot sobre su posición real ([fraccion] 0..1 del recorrido), que se desliza
 * entre consultas. Con más de [MAX_NODOS_RIEL] zonas la pista se pinta por tramos.
 */
@Composable
fun RielRecorrido(
    zonas: List<NivelSuelo>,
    zonaActual: Int,
    fraccion: Float?,
    distanciaCm: Float,
    robot: EstadoRobot,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    val colorPista = MaterialTheme.colorScheme.surfaceContainerHighest
    val colorNodo = MaterialTheme.colorScheme.outline
    val colorFondoNodo = colores.tarjeta
    val colorActual = colores.exito.acento
    val coloresNivel = colores.niveles
    val fraccionAnimada by animateFloatAsState(
        targetValue = fraccion ?: 0f,
        animationSpec = tween(1_400, easing = LinearOutSlowInEasing),
        label = "robot",
    )
    val n = zonas.size
    val conNodos = n in 1..MAX_NODOS_RIEL
    val descripcion = buildString {
        append("Riel con $n ${if (n == 1) "zona" else "zonas"}")
        if (zonaActual in 1..n) append(", zona actual $zonaActual")
        if (fraccion != null) append(", robot al ${(fraccion * 100).roundToInt()} % del recorrido")
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .testTag(TAG_RIEL)
            .semantics { contentDescription = descripcion },
    ) {
        val margen = 20.dp
        val largo = maxWidth - margen * 2
        val yPista = 60.dp
        fun xDe(f: Float): Dp = margen + largo * f.coerceIn(0f, 1f)
        fun centroZona(i: Int): Float = (i + 0.5f) / n

        Canvas(Modifier.fillMaxSize()) {
            val y = yPista.toPx()
            val x0 = margen.toPx()
            val x1 = size.width - margen.toPx()
            val grosor = 6.dp.toPx()
            drawLine(colorPista, Offset(x0, y), Offset(x1, y), strokeWidth = grosor, cap = StrokeCap.Round)
            if (fraccion != null) {
                // Tramo ya recorrido desde el origen.
                drawLine(
                    colorActual.copy(alpha = 0.35f),
                    Offset(x0, y),
                    Offset(x0 + (x1 - x0) * fraccionAnimada, y),
                    strokeWidth = grosor,
                    cap = StrokeCap.Round,
                )
            }
            if (conNodos) {
                val radio = 8.dp.toPx()
                drawCircle(colorFondoNodo, radius = radio, center = Offset(x0, y))
                drawCircle(colorNodo, radius = radio, center = Offset(x0, y), style = Stroke(2.5.dp.toPx()))
                zonas.forEachIndexed { i, nivel ->
                    val centro = Offset(x0 + (x1 - x0) * centroZona(i), y)
                    val actual = i + 1 == zonaActual
                    val r = if (actual) radio * 1.35f else radio
                    drawCircle(colorFondoNodo, radius = r, center = centro)
                    drawCircle(
                        if (actual) colorActual else colorNodo,
                        radius = r,
                        center = centro,
                        style = Stroke((if (actual) 3.5.dp else 2.5.dp).toPx()),
                    )
                    drawCircle(
                        (coloresNivel[nivel] ?: coloresNivel.getValue(NivelSuelo.DESCONOCIDO)).fondo,
                        radius = r * 0.45f,
                        center = centro,
                    )
                }
            } else if (n > 0) {
                val ancho = (x1 - x0) / n
                zonas.forEachIndexed { i, nivel ->
                    drawLine(
                        (coloresNivel[nivel] ?: coloresNivel.getValue(NivelSuelo.DESCONOCIDO)).fondo,
                        Offset(x0 + i * ancho, y),
                        Offset(x0 + (i + 1) * ancho, y),
                        strokeWidth = grosor,
                    )
                }
                if (zonaActual in 1..n) {
                    val cx = x0 + (zonaActual - 0.5f) * ancho
                    drawCircle(colorActual, radius = 6.dp.toPx(), center = Offset(cx, y), style = Stroke(3.dp.toPx()))
                }
            }
        }

        val estiloEtiqueta = MaterialTheme.typography.labelMedium
        val colorEtiqueta = MaterialTheme.colorScheme.onSurfaceVariant
        val yEtiqueta = yPista + 14.dp
        if (conNodos) {
            // Entre el origen y la zona 1 hay media zona: si no cabe "Inicio", basta la casita.
            val etiquetaOrigen = if (largo / (2 * n) >= 40.dp) "Inicio" else "🏠"
            Text(etiquetaOrigen, style = estiloEtiqueta, color = colorEtiqueta, modifier = Modifier.centradoEn(margen, yEtiqueta, maxWidth))
            zonas.indices.forEach { i ->
                val actual = i + 1 == zonaActual
                Text(
                    "Z${i + 1}",
                    style = estiloEtiqueta,
                    fontWeight = if (actual) FontWeight.Bold else FontWeight.Medium,
                    color = if (actual) colores.exito.acento else colorEtiqueta,
                    modifier = Modifier.centradoEn(xDe(centroZona(i)), yEtiqueta, maxWidth),
                )
            }
        } else {
            Text("Inicio", style = estiloEtiqueta, color = colorEtiqueta, modifier = Modifier.centradoEn(margen, yEtiqueta, maxWidth))
            Text(
                "Fin · ${Formatos.centimetros(distanciaCm)}",
                style = estiloEtiqueta,
                color = colorEtiqueta,
                modifier = Modifier.centradoEn(maxWidth - margen, yEtiqueta, maxWidth),
            )
        }

        if (fraccion != null) {
            BurbujaRobot(robot, Modifier.centradoEn(xDe(fraccionAnimada), 0.dp, maxWidth))
        }
    }
}

/** Robot sobre el riel: azul si riega, ámbar si fertiliza, oscuro en reposo. */
@Composable
private fun BurbujaRobot(robot: EstadoRobot, modifier: Modifier) {
    val colores = FertiTrackTema.colores
    val fondo = when {
        robot.bombaRiegoEncendida -> colores.agua.acento
        robot.bombaFertilizanteEncendida -> colores.fertilizante.acento
        robot.hayFalla -> colores.peligro.acento
        else -> MaterialTheme.colorScheme.inverseSurface
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(14.dp), color = fondo, shadowElevation = 4.dp, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("🤖", fontSize = 22.sp) }
        }
        Canvas(Modifier.size(width = 12.dp, height = 7.dp)) {
            val punta = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(punta, fondo)
        }
    }
}

/** Coloca el elemento con su centro horizontal en [x] y su borde superior en [y], sin salirse de [ancho]. */
private fun Modifier.centradoEn(x: Dp, y: Dp, ancho: Dp): Modifier = layout { medible, restricciones ->
    val p = medible.measure(restricciones.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) {
        val izquierda = (x.roundToPx() - p.width / 2).coerceIn(0, (ancho.roundToPx() - p.width).coerceAtLeast(0))
        p.place(izquierda, y.roundToPx())
    }
}

private data class Mosaico(val emoji: String, val etiqueta: String, val valor: String, val color: Color)

/**
 * Cuatro mosaicos de un vistazo: bomba de riego, fertilizante, humedad promedio y qué hace
 * el robot. En pantallas angostas van de dos en dos.
 */
@Composable
fun MosaicoEstado(
    robot: EstadoRobot,
    categoria: CategoriaEstado?,
    humedadPromedio: Float?,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val (textoRobot, colorRobot) = when (categoria) {
        CategoriaEstado.REPOSO -> "En espera" to colores.exito.acento
        CategoriaEstado.SENSADO, CategoriaEstado.RIEGO, CategoriaEstado.FERTILIZACION -> "En ruta" to colores.agua.acento
        CategoriaEstado.RECUPERACION -> "Recuperando" to colores.aviso.acento
        CategoriaEstado.ARRANQUE -> "Arrancando" to colores.neutro.acento
        CategoriaEstado.PRUEBA -> "En prueba" to colores.prueba.acento
        CategoriaEstado.EMERGENCIA -> "Emergencia" to colores.peligro.acento
        CategoriaEstado.DESCONOCIDO, null -> Formatos.SIN_DATO to apagado
    }
    val mosaicos = listOf(
        Mosaico(
            "🚿",
            "Bomba",
            if (robot.bombaRiegoEncendida) "Activa" else "Apagada",
            if (robot.bombaRiegoEncendida) colores.agua.acento else apagado,
        ),
        Mosaico(
            "🌾",
            "Fertilizante",
            if (robot.bombaFertilizanteEncendida) "Activo" else "En espera",
            if (robot.bombaFertilizanteEncendida) colores.fertilizante.acento else apagado,
        ),
        Mosaico("💧", "Promedio", Formatos.porcentaje(humedadPromedio), MaterialTheme.colorScheme.onSurface),
        Mosaico("🤖", "Robot", textoRobot, colorRobot),
    )
    BoxWithConstraints(modifier.fillMaxWidth().testTag(TAG_MOSAICO_ESTADO)) {
        val porFila = if (maxWidth >= 440.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(SeparacionZonas)) {
            mosaicos.chunked(porFila).forEach { fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(SeparacionZonas)) {
                    fila.forEach { MosaicoDato(it, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MosaicoDato(dato: Mosaico, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = FertiTrackTema.colores.tarjeta,
        shadowElevation = 1.dp,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "${dato.etiqueta}: ${dato.valor}"
        },
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(dato.emoji, fontSize = 22.sp)
            Text(
                dato.etiqueta.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                dato.valor.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = dato.color,
                maxLines = 1,
            )
        }
    }
}

/**
 * Aviso de zonas secas con el botón Regar. El firmware no riega una zona suelta: Regar inicia
 * un ciclo, que mide todas las zonas y riega las secas (la confirmación lo explica).
 */
@Composable
fun AvisoZonasSecas(
    secas: List<Int>,
    calibracion: CalibracionSensor,
    regandoAhora: Boolean,
    habilitado: Boolean,
    motivo: String?,
    onRegar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    val titulo = when {
        secas.size == 1 -> "Zona ${secas.first()} necesita riego"
        secas.size <= 4 -> "Zonas ${Formatos.listaZonas(secas)} necesitan riego"
        else -> "${secas.size} zonas necesitan riego"
    }
    TarjetaFertiTrack(modifier = modifier.testTag(TAG_AVISO_RIEGO)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = colores.peligro.contenedor, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("🌵", fontSize = 24.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (regandoAhora) {
                        "El ciclo en curso las está regando."
                    } else {
                        "Por debajo del umbral SECA (${calibracion.pctSeca} %)."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!regandoAhora) {
                TextButton(
                    onClick = onRegar,
                    enabled = habilitado,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BOTON_REGAR),
                ) {
                    Text("Regar", fontWeight = FontWeight.Bold, color = if (habilitado) colores.exito.acento else Color.Unspecified)
                }
            }
        }
        if (!regandoAhora) {
            Nota(
                if (habilitado || motivo == null) {
                    "Regar inicia un ciclo: el robot mide todas las zonas y riega las secas."
                } else {
                    motivo
                },
            )
        }
    }
}
