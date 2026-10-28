package com.fertitrack.app.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.tema.FertiTrackTema
import kotlin.math.roundToInt

/** Niveles que el firmware clasifica, de más seco a más húmedo. */
val NivelesMedidos = listOf(NivelSuelo.SECA, NivelSuelo.INTERMEDIA, NivelSuelo.HUMEDA, NivelSuelo.SOBRE_HUMEDA)

/** Rango real de cada nivel según la calibración que reporta /api/estado. */
fun rangoNivel(nivel: NivelSuelo, calibracion: CalibracionSensor): String = when (nivel) {
    NivelSuelo.SECA -> "< ${calibracion.pctSeca} % (se riega)"
    NivelSuelo.INTERMEDIA -> "${calibracion.pctSeca}–${calibracion.pctHumeda} %"
    NivelSuelo.HUMEDA -> "${calibracion.pctHumeda}–${calibracion.pctSobre} %"
    NivelSuelo.SOBRE_HUMEDA -> "≥ ${calibracion.pctSobre} %"
    NivelSuelo.SIN_MEDIR -> "Todavía sin medición"
    NivelSuelo.DESCONOCIDO -> "Código no reconocido"
}

/**
 * Escala Seca · Intermedia · Húmeda · Sobre-húmeda con el nivel [actual] resaltado
 * en su color (el de la página web del Maestro).
 */
@Composable
fun EscalaNiveles(actual: NivelSuelo?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NivelesMedidos.forEach { nivel ->
            val colores = FertiTrackTema.colores.nivel(nivel)
            val elegido = nivel == actual
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (elegido) colores.fondo else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (elegido) colores.texto else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (elegido) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .semantics {
                        selected = elegido
                        contentDescription = Traductor.nombreNivel(nivel) + if (elegido) ", nivel actual" else ""
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Punto(if (elegido) colores.texto else colores.fondo, tamano = 10.dp)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        // Espacio de ancho cero tras el guion: "Sobre-húmeda" se parte por el guion, no a media palabra.
                        Traductor.nombreNivel(nivel).replace("-", "-​"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (elegido) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Muestra de color de un nivel (celda pequeña redondeada). */
@Composable
fun MuestraNivel(nivel: NivelSuelo, modifier: Modifier = Modifier) {
    val colores = FertiTrackTema.colores.nivel(nivel)
    Box(
        modifier
            .size(width = 22.dp, height = 16.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colores.fondo),
    )
}

/** Cuántas zonas hay en cada nivel (incluye "Sin medir"; ignora códigos desconocidos si no hay). */
fun conteoPorNivel(zonas: List<NivelSuelo>): Map<NivelSuelo, Int> {
    val conteo = zonas.groupingBy { it }.eachCount()
    val orden = NivelesMedidos + NivelSuelo.SIN_MEDIR + NivelSuelo.DESCONOCIDO
    return orden.associateWith { conteo[it] ?: 0 }.filter { (nivel, n) -> n > 0 || nivel != NivelSuelo.DESCONOCIDO }
}

/**
 * Conteo de zonas por nivel: barra apilada proporcional y una fila por nivel con su número.
 * Funciona igual con 1 zona que con 150.
 */
@Composable
fun ConteoNiveles(zonas: List<NivelSuelo>, modifier: Modifier = Modifier) {
    val conteo = conteoPorNivel(zonas)
    val total = zonas.size.coerceAtLeast(1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 14.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics {
                    contentDescription = conteo.entries.filter { it.value > 0 }
                        .joinToString(", ") { "${Traductor.nombreNivel(it.key)}: ${it.value}" }
                },
        ) {
            conteo.forEach { (nivel, n) ->
                if (n > 0) {
                    Box(
                        Modifier
                            .weight(n.toFloat() / total)
                            .heightIn(min = 14.dp)
                            .background(FertiTrackTema.colores.nivel(nivel).fondo),
                    )
                }
            }
        }
        conteo.forEach { (nivel, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 28.dp)) {
                MuestraNivel(nivel)
                Spacer(Modifier.width(10.dp))
                Text(
                    Traductor.nombreNivel(nivel),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    n.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    " · ${(n * 100f / total).roundToInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
