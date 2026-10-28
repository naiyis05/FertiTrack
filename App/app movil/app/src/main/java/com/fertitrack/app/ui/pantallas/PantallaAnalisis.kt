package com.fertitrack.app.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.analisis.Severidad
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.traduccion.CategoriaEstado
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.ConteoNiveles
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.componentes.TarjetaInsight
import com.fertitrack.app.ui.componentes.nombreSeveridad
import com.fertitrack.app.ui.componentes.tonoSeveridad
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

const val TAG_PANTALLA_ANALISIS = "pantalla_analisis"

/** Análisis: indicadores de la sesión, insights por severidad y resumen de zonas por nivel. */
@Composable
fun PantallaAnalisis(estado: UiState, @Suppress("UNUSED_PARAMETER") acciones: AccionesUi, modifier: Modifier = Modifier) {
    val resumen = estado.resumen
    val robot = estado.robot
    val colores = FertiTrackTema.colores
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_ANALISIS),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("kpis") {
            Column(verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeparacionTarjetas)) {
                    Kpi(
                        titulo = "Humedad promedio",
                        valor = Formatos.porcentaje(resumen.humedadPromedio),
                        detalle = if (resumen.humedadPromedio == null) "Sin mediciones" else "de todas las mediciones",
                        icono = Icons.Rounded.Opacity,
                        tono = colores.agua,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    val interrumpidos = resumen.riegos.count { !it.completado }
                    Kpi(
                        titulo = "Riegos completados",
                        valor = resumen.riegosCompletados.toString(),
                        detalle = if (interrumpidos > 0) {
                            "completados · $interrumpidos ${if (interrumpidos == 1) "interrumpido" else "interrumpidos"}"
                        } else {
                            if (resumen.riegosCompletados == 1) "completado" else "completados"
                        },
                        icono = Icons.Rounded.WaterDrop,
                        tono = colores.agua,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeparacionTarjetas)) {
                    Kpi(
                        titulo = "Mediciones",
                        valor = resumen.totalMediciones.toString(),
                        detalle = "lecturas del sensor",
                        icono = Icons.Rounded.Sensors,
                        tono = colores.sensado,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    val descripcion = estado.descripcionEstado
                    Kpi(
                        titulo = "Estado actual",
                        valor = when {
                            robot == null -> Formatos.SIN_DATO
                            descripcion != null -> descripcion.titulo
                            else -> Traductor.nombreEstado(robot.estado, robot.estadoTexto)
                        },
                        detalle = when {
                            robot == null -> "Sin datos del robot"
                            estado.datosObsoletos -> "Datos sin actualizar"
                            else -> Traductor.nombreActividad(robot.actividad)
                        },
                        icono = Icons.Rounded.SmartToy,
                        tono = tonoCategoria(descripcion?.categoria ?: CategoriaEstado.DESCONOCIDO),
                        valorTexto = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        item("insights") {
            TarjetaFertiTrack(titulo = "Análisis automático", icono = Icons.Rounded.Lightbulb) {
                if (estado.insights.isEmpty()) {
                    EstadoVacio(
                        Icons.Rounded.Lightbulb,
                        "Sin observaciones por ahora",
                        detalle = "El análisis aparece cuando hay datos del robot y mediciones guardadas.",
                    )
                } else {
                    ResumenSeveridades(estado.insights.map { it.severidad })
                    estado.insights.forEach { TarjetaInsight(it) }
                }
            }
        }
        item("zonas") {
            TarjetaFertiTrack(titulo = "Zonas por nivel", icono = Icons.Rounded.GridView) {
                val zonas = robot?.zonas.orEmpty()
                if (zonas.isEmpty()) {
                    EstadoVacio(
                        Icons.Rounded.GridView,
                        if (robot == null) "Sin datos del robot todavía" else "Sin zonas configuradas",
                    )
                } else {
                    ConteoNiveles(zonas)
                    val secas = zonas.count { it == NivelSuelo.SECA }
                    Nota(
                        when (secas) {
                            0 -> "Ninguna zona seca: el próximo ciclo no regará salvo que cambie la humedad."
                            1 -> "1 zona seca: se regará en el próximo ciclo."
                            else -> "$secas zonas secas: se regarán en el próximo ciclo."
                        },
                    )
                }
            }
        }
    }
}

/** Chips "2 Crítico · 1 Aviso…" para ver de un vistazo la gravedad del análisis. */
@Composable
private fun ResumenSeveridades(severidades: List<Severidad>) {
    val orden = listOf(Severidad.CRITICO, Severidad.AVISO, Severidad.INFO, Severidad.EXITO)
    val conteo = orden.map { it to severidades.count { s -> s == it } }.filter { it.second > 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        conteo.forEach { (severidad, n) ->
            val tono = tonoSeveridad(severidad)
            Surface(shape = MaterialTheme.shapes.small, color = tono.contenedor, contentColor = tono.sobreContenedor) {
                Text(
                    "$n ${nombreSeveridad(severidad)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Kpi(
    titulo: String,
    valor: String,
    detalle: String,
    icono: ImageVector,
    tono: TonoSemantico,
    modifier: Modifier = Modifier,
    /** true si el valor es una frase (estado) y no una cifra: se dibuja más pequeño. */
    valorTexto: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = FertiTrackTema.colores.tarjeta,
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 136.dp).semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icono, contentDescription = null, tint = tono.acento, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    titulo,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                valor,
                style = if (valorTexto) MaterialTheme.typography.titleLarge else FertiTrackTema.estiloCifra,
                fontWeight = FontWeight.Bold,
                maxLines = if (valorTexto) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detalle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
