package com.fertitrack.app.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.FilaEvento
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.GraficaHumedad
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_PANTALLA_GRAFICAS = "pantalla_graficas"

/** Gráficas: humedad por medición, historial de riegos y registro de eventos de la sesión. */
@Composable
fun PantallaGraficas(estado: UiState, @Suppress("UNUSED_PARAMETER") acciones: AccionesUi, modifier: Modifier = Modifier) {
    val resumen = estado.resumen
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_GRAFICAS),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("grafica") {
            TarjetaFertiTrack(titulo = "Humedad del suelo", icono = Icons.AutoMirrored.Rounded.ShowChart) {
                GraficaHumedad(
                    muestras = resumen.muestras,
                    calibracion = estado.robot?.calibracion,
                    altoArea = 220.dp,
                )
                if (resumen.muestras.isNotEmpty()) {
                    Text(
                        "${resumen.muestras.size} de ${resumen.totalMediciones.coerceAtLeast(resumen.muestras.size)} " +
                            "mediciones de la sesión",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Nota("Se registra una muestra por medición del robot, no por consulta.")
            }
        }
        item("riegos") {
            TarjetaFertiTrack(titulo = "Historial de riegos", icono = Icons.Rounded.WaterDrop) {
                if (resumen.riegos.isEmpty()) {
                    EstadoVacio(
                        Icons.Rounded.WaterDrop,
                        "Sin riegos en esta sesión",
                        detalle = "Aparecen aquí cuando un ciclo encuentra zonas secas y las riega.",
                    )
                } else {
                    resumen.riegos.asReversed().forEachIndexed { i, riego ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilaRiego(riego)
                    }
                }
            }
        }
        item("eventos") {
            TarjetaFertiTrack(titulo = "Eventos de la sesión", icono = Icons.AutoMirrored.Rounded.EventNote) {
                if (resumen.eventos.isEmpty()) {
                    EstadoVacio(
                        Icons.AutoMirrored.Rounded.EventNote,
                        "Sin eventos todavía",
                        detalle = "Conexiones, rutinas, riegos y fallas quedan registrados aquí.",
                    )
                } else {
                    resumen.eventos.forEachIndexed { i, evento ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilaEvento(evento)
                    }
                    Nota("Se guardan los últimos ${resumen.eventos.size} eventos de esta sesión, el más reciente arriba.")
                }
            }
        }
    }
}

@Composable
private fun FilaRiego(riego: RiegoRegistrado) {
    val colores = FertiTrackTema.colores
    val tono = if (riego.completado) colores.exito else colores.aviso
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Surface(shape = CircleShape, color = colores.agua.contenedor, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.WaterDrop,
                    contentDescription = null,
                    tint = colores.agua.acento,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                "${Formatos.horaCorta(riego.inicioMs)} – ${Formatos.horaCorta(riego.finMs)}" +
                    " · ${Formatos.duracion(riego.finMs - riego.inicioMs)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (riego.zonasRegadas.size) {
                    0 -> "Sin zonas secas registradas"
                    1 -> "Zona regada: ${Formatos.listaZonas(riego.zonasRegadas)}"
                    else -> "Zonas regadas: ${Formatos.listaZonas(riego.zonasRegadas)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(shape = MaterialTheme.shapes.small, color = tono.contenedor, contentColor = tono.sobreContenedor) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (riego.completado) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                    contentDescription = null,
                    tint = tono.acento,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (riego.completado) "Completado" else "Interrumpido",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
