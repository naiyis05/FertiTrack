package com.fertitrack.app.ui.componentes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay

const val TAG_CIELO = "cielo_horario"
const val TAG_VOLVER_HORA_REAL = "cielo_hora_real"
fun tagFaseCielo(fase: FaseDia): String = "cielo_fase_${fase.name}"

/** Hora fija para pruebas y capturas; null = reloj del teléfono. */
val LocalHoraCielo = staticCompositionLocalOf<LocalDateTime?> { null }

/** Una vista previa de otra fase vuelve sola a la hora real pasado este tiempo. */
private const val DURACION_VISTA_PREVIA_MS = 12_000L

private val ESPANOL: Locale = Locale.forLanguageTag("es")
private val FORMATO_FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ESPANOL)
private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

private val TextoCielo = Color.White
private val TextoPildora = Color(0xFF1E3A5F)
private val SombraTexto = Shadow(Color(0x66000000), Offset(0f, 2f), blurRadius = 8f)

/** Hora del teléfono, actualizada al cambiar cada minuto. */
@Composable
fun rememberHoraLocal(): LocalDateTime {
    val fija = LocalHoraCielo.current
    var ahora by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(fija) {
        if (fija != null) return@LaunchedEffect
        while (true) {
            ahora = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return fija ?: ahora
}

/**
 * Cielo con la hora del teléfono: el degradado cambia con el momento del día y el sol y la
 * luna cruzan en arco. Tocar una fase la muestra un momento (el cielo avanza hasta ella) y
 * luego vuelve sola a la hora real. Las últimas [reservaInferior] quedan para la tarjeta que
 * se monta encima.
 */
@Composable
fun CieloHorario(modifier: Modifier = Modifier, alto: Dp = 252.dp, reservaInferior: Dp = 44.dp) {
    val hora = rememberHoraLocal()
    val minutoReal = hora.hour * 60f + hora.minute
    val faseReal = CicloDia.fase(minutoReal)
    var vistaPrevia by rememberSaveable { mutableStateOf<FaseDia?>(null) }
    LaunchedEffect(vistaPrevia) {
        if (vistaPrevia != null) {
            delay(DURACION_VISTA_PREVIA_MS)
            vistaPrevia = null
        }
    }

    val objetivo = vistaPrevia?.minutoMuestra?.toFloat() ?: minutoReal
    val minuto = remember { Animatable(objetivo) }
    LaunchedEffect(objetivo) {
        val destino = CicloDia.destinoAnimacion(minuto.value, objetivo)
        // El paso de cada minuto real es imperceptible: sin animación.
        if (abs(destino - minuto.value) <= 2f) {
            minuto.snapTo(destino)
        } else {
            minuto.animateTo(destino, tween(1_800, easing = FastOutSlowInEasing))
        }
    }
    val faseMostrada = vistaPrevia ?: faseReal
    val textoHora = FORMATO_HORA.format(hora)
    val textoFecha = FORMATO_FECHA.format(hora).replaceFirstChar { it.titlecase(ESPANOL) }

    Box(
        modifier
            .fillMaxWidth()
            .height(alto)
            .testTag(TAG_CIELO),
    ) {
        FondoCielo(
            minuto = { CicloDia.normalizar(minuto.value) },
            reservaInferior = reservaInferior,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Son las $textoHora, $textoFecha. ${faseReal.nombre}."
                        },
                ) {
                    Text(
                        textoHora,
                        style = TextStyle(
                            color = TextoCielo,
                            fontSize = 44.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = SombraTexto,
                        ),
                    )
                    Text(
                        textoFecha,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = SombraTexto),
                        color = TextoCielo,
                        fontWeight = FontWeight.Medium,
                    )
                }
                PildoraFase(faseMostrada)
            }
            SelectorFases(
                elegida = faseMostrada,
                onElegir = { fase -> vistaPrevia = if (fase == faseReal) null else fase },
            )
            AnimatedVisibility(visible = vistaPrevia != null, enter = fadeIn(), exit = fadeOut()) {
                // Fondo oscuro: el texto blanco se lee aunque pase una nube por detrás.
                Surface(
                    onClick = { vistaPrevia = null },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.32f),
                    contentColor = TextoCielo,
                    modifier = Modifier.heightIn(min = 36.dp).testTag(TAG_VOLVER_HORA_REAL),
                ) {
                    Text(
                        "Vista previa · Volver a la hora real",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PildoraFase(fase: FaseDia) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.92f),
        contentColor = TextoPildora,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fase.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(fase.nombre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** Franja con las seis fases; la mostrada queda resaltada. Con espacio, lleva sus nombres. */
@Composable
private fun SelectorFases(elegida: FaseDia, onElegir: (FaseDia) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val conNombres = maxWidth >= 560.dp
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FaseDia.entries.forEach { fase ->
                val activa = fase == elegida
                Row(
                    Modifier
                        .heightIn(min = 44.dp)
                        .widthIn(min = 44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (activa) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                        .clickable { onElegir(fase) }
                        .semantics {
                            role = Role.Button
                            selected = activa
                            contentDescription = "Ver el cielo de ${fase.nombre.lowercase(ESPANOL)}"
                        }
                        .padding(horizontal = if (conNombres) 12.dp else 8.dp)
                        .testTag(tagFaseCielo(fase)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(fase.emoji, fontSize = 20.sp)
                    if (conNombres) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            fase.nombre,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (activa) TextoPildora else TextoCielo,
                            fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private class Estrella(val x: Float, val y: Float, val radioDp: Float, val brillo: Float, val parpadea: Boolean)

/** Nube: posición inicial (fracción del ancho), altura (fracción del horizonte), tamaño y velocidad. */
private class Nube(val x: Float, val y: Float, val escala: Float, val velocidad: Float)

private val ESTRELLAS: List<Estrella> = Random(2026).let { r ->
    List(42) {
        Estrella(r.nextFloat(), r.nextFloat(), 0.7f + r.nextFloat() * 1.1f, 0.45f + r.nextFloat() * 0.55f, r.nextBoolean())
    }
}

// Bajas, entre el selector y las colinas: así no pasan por detrás del reloj.
private val NUBES = listOf(
    Nube(0.10f, 0.86f, 0.9f, 1.0f),
    Nube(0.55f, 0.78f, 0.7f, 0.7f),
    Nube(0.92f, 0.90f, 0.8f, 1.25f),
)

private val ColinaLejanaDia = Color(0xFF8CC88A)
private val ColinaLejanaNoche = Color(0xFF1D3A33)
private val ColinaCercanaDia = Color(0xFF5DAE5C)
private val ColinaCercanaNoche = Color(0xFF13281F)
private val ColorLuna = Color(0xFFF4F1E4)
private val SolBajo = Color(0xFFFF8A3D)
private val SolAlto = Color(0xFFFFD54F)

/**
 * Dibujo del cielo para el [minuto] (0..1440) que devuelve la lambda: degradado, estrellas,
 * luna, sol, nubes y colinas. Las formas se calculan una vez por tamaño; cada fotograma solo
 * mueve nubes y astros.
 */
@Composable
private fun FondoCielo(minuto: () -> Float, reservaInferior: Dp, modifier: Modifier) {
    val transicion = rememberInfiniteTransition(label = "cielo")
    val deriva by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(150_000, easing = LinearEasing)),
        label = "nubes",
    )
    val centelleo by transicion.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200), RepeatMode.Reverse),
        label = "estrellas",
    )
    Box(
        modifier.drawWithCache {
            val u = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val horizonte = h - reservaInferior.toPx() - 18 * u
            val cima = 26 * u
            val nube = formaNube(u)
            val radioLuna = 19 * u
            val luna = formaLuna(radioLuna)
            val colinaLejana = Path().apply {
                moveTo(0f, horizonte - 4 * u)
                cubicTo(w * 0.25f, horizonte - 26 * u, w * 0.45f, horizonte + 2 * u, w * 0.65f, horizonte - 14 * u)
                cubicTo(w * 0.8f, horizonte - 24 * u, w * 0.92f, horizonte - 10 * u, w, horizonte - 16 * u)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            val colinaCercana = Path().apply {
                moveTo(0f, horizonte + 10 * u)
                cubicTo(w * 0.2f, horizonte - 6 * u, w * 0.4f, horizonte + 14 * u, w * 0.6f, horizonte + 6 * u)
                cubicTo(w * 0.78f, horizonte, w * 0.9f, horizonte + 12 * u, w, horizonte + 4 * u)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }

            // El arco ocupa la mitad derecha: sale por el este (derecha) y se pone hacia el
            // centro, lejos del reloj. A mediodía queda arriba, detrás de la píldora.
            fun posicion(avance: Float) = Offset(
                x = w * (0.94f - 0.54f * avance),
                y = horizonte - CicloDia.altura(avance) * (horizonte - cima),
            )

            onDrawBehind {
                val m = minuto()
                val colores = CicloDia.cielo(m)
                val noche = CicloDia.oscuridad(m)
                drawRect(Brush.verticalGradient(listOf(colores.arriba, colores.abajo), endY = horizonte))

                if (noche > 0.01f) {
                    ESTRELLAS.forEach { e ->
                        val alfa = noche * e.brillo * (if (e.parpadea) centelleo else 1f)
                        drawCircle(
                            Color.White.copy(alpha = alfa.coerceIn(0f, 1f)),
                            radius = e.radioDp * u,
                            center = Offset(e.x * w, e.y * horizonte * 0.92f),
                        )
                    }
                }
                CicloDia.avanceLuna(m)?.let { avance ->
                    val centro = posicion(avance)
                    drawCircle(
                        Brush.radialGradient(listOf(ColorLuna.copy(alpha = 0.3f), Color.Transparent), centro, radioLuna * 3f),
                        radius = radioLuna * 3f,
                        center = centro,
                    )
                    translate(centro.x, centro.y) { drawPath(luna, ColorLuna) }
                }
                CicloDia.avanceSol(m)?.let { avance -> dibujarSol(posicion(avance), 23 * u, CicloDia.altura(avance)) }

                val colorNube = lerp(Color.White, colores.abajo, 0.15f + 0.35f * noche)
                    .copy(alpha = 0.92f - 0.6f * noche)
                NUBES.forEach { n ->
                    val x = ((n.x + deriva * n.velocidad) % 1.3f - 0.15f) * w
                    translate(x, n.y * horizonte) {
                        scale(n.escala, n.escala, pivot = Offset.Zero) { drawPath(nube, colorNube) }
                    }
                }

                drawPath(colinaLejana, lerp(lerp(ColinaLejanaDia, ColinaLejanaNoche, noche), colores.abajo, 0.15f))
                drawPath(colinaCercana, lerp(ColinaCercanaDia, ColinaCercanaNoche, noche))
            }
        },
    )
}

private fun DrawScope.dibujarSol(centro: Offset, radio: Float, altura: Float) {
    // Naranja junto al horizonte, amarillo en lo alto.
    val color = lerp(SolBajo, SolAlto, (altura * 1.6f).coerceIn(0f, 1f))
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), centro, radio * 3.4f),
        radius = radio * 3.4f,
        center = centro,
    )
    drawCircle(color, radius = radio, center = centro)
    drawCircle(Color.White.copy(alpha = 0.3f), radius = radio * 0.5f, center = centro + Offset(-radio * 0.25f, -radio * 0.25f))
}

/** Silueta de nube (unión de círculos sobre una base) con origen en su base izquierda. */
private fun formaNube(u: Float): Path {
    var forma = Path().apply {
        addRoundRect(RoundRect(Rect(-10 * u, 0f, 66 * u, 16 * u), CornerRadius(8 * u)))
    }
    listOf(Offset(4f, 2f) to 13f, Offset(22f, -6f) to 18f, Offset(42f, 0f) to 14f, Offset(56f, 6f) to 10f)
        .forEach { (centro, radio) ->
            val bulto = Path().apply { addOval(Rect(Offset(centro.x * u, centro.y * u), radio * u)) }
            forma = Path.combine(PathOperation.Union, forma, bulto)
        }
    return forma
}

/** Luna creciente centrada en el origen: un disco menos otro desplazado. */
private fun formaLuna(radio: Float): Path {
    val disco = Path().apply { addOval(Rect(Offset.Zero, radio)) }
    val sombra = Path().apply { addOval(Rect(Offset(radio * 0.45f, -radio * 0.22f), radio * 0.9f)) }
    return Path.combine(PathOperation.Difference, disco, sombra)
}
