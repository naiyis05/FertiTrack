package com.fertitrack.app.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.ConteoNiveles
import com.fertitrack.app.ui.componentes.CuadriculaZonas
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.ExtremosRiel
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.LeyendaNiveles
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.MuestraNivel
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.RielZonas
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_PANTALLA_ZONAS = "pantalla_zonas"
const val TAG_AVISO_SIN_RECORRIDO = "aviso_sin_recorrido"

/**
 * Zonas (la vista "Invernadero" del escritorio): resumen del recorrido, mapa del riel con la
 * posición del robot, leyenda con los rangos reales de la calibración, conteo por nivel y la
 * última humedad medida en cada zona.
 */
@Composable
fun PantallaZonas(estado: UiState, @Suppress("UNUSED_PARAMETER") acciones: AccionesUi, modifier: Modifier = Modifier) {
    val robot = estado.robot
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_ZONAS),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        if (robot == null) {
            item("vacio") {
                TarjetaFertiTrack(titulo = "Zonas del invernadero", icono = Icons.Rounded.GridView) {
                    EstadoVacio(
                        Icons.Rounded.GridView,
                        "Sin datos del robot todavía",
                        detalle = "Las zonas aparecen cuando la app recibe el estado del robot.",
                    )
                }
            }
            return@LazyColumn
        }
        val zonas = robot.zonas.ifEmpty { List(robot.totalZonas) { NivelSuelo.SIN_MEDIR } }

        if (!robot.distanciaConfigurada) {
            item("sin_recorrido") { AvisoSinRecorrido(estado.ipRobot) }
        }
        item("resumen") { TarjetaResumenRecorrido(robot, zonas.size) }
        if (zonas.isNotEmpty()) {
            item("mapa") {
                TarjetaFertiTrack(titulo = "Mapa del riel", icono = Icons.Rounded.Map) {
                    RielZonas(zonas = zonas, zonaActual = robot.zonaActual, fraccion = robot.fraccionRecorrido)
                    ExtremosRiel(distancia = Formatos.centimetros(robot.distanciaCm))
                    CuadriculaZonas(zonas = zonas, zonaActual = robot.zonaActual)
                    Nota(
                        if (robot.zonaActual in 1..zonas.size) {
                            "El recuadro marca la zona actual (${robot.zonaActual}); el marcador verde, " +
                                "la posición del robot en el riel."
                        } else {
                            "El marcador verde indica la posición del robot en el riel."
                        },
                    )
                }
            }
        }
        item("leyenda") {
            TarjetaFertiTrack(titulo = "Leyenda", icono = Icons.Rounded.Palette) {
                LeyendaNiveles(robot.calibracion)
                Nota("Rangos de la calibración actual del sensor. Solo las zonas secas se riegan.")
            }
        }
        if (zonas.isNotEmpty()) {
            item("conteo") {
                TarjetaFertiTrack(titulo = "Zonas por nivel", icono = Icons.Rounded.PieChart) {
                    ConteoNiveles(zonas)
                }
            }
        }
        item("ultimas") { TarjetaUltimaHumedad(estado.resumen.muestras) }
    }
}

@Composable
private fun AvisoSinRecorrido(ipRobot: String) {
    val tono = FertiTrackTema.colores.aviso
    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_AVISO_SIN_RECORRIDO),
        titulo = "Recorrido sin configurar",
        icono = Icons.Rounded.SettingsEthernet,
        tono = tono,
    ) {
        Text(
            "El robot todavía no sabe la distancia del riel, así que no hay zonas ni rutinas.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Configúralo desde la página web del robot: http://$ipRobot (con el teléfono conectado " +
                "a su Wi-Fi). La pantalla de configuración llega a la app en la Fase 2.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TarjetaResumenRecorrido(robot: EstadoRobot, numZonas: Int) {
    TarjetaFertiTrack(titulo = "Recorrido", icono = Icons.Rounded.Straighten) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CifraResumen(
                valor = numZonas.toString(),
                etiqueta = if (numZonas == 1) "zona" else "zonas",
                modifier = Modifier.weight(1f),
            )
            CifraResumen(
                valor = if (robot.distanciaCm > 0f) Formatos.centimetros(robot.distanciaCm) else Formatos.SIN_DATO,
                etiqueta = "distancia",
                modifier = Modifier.weight(1f),
            )
            CifraResumen(
                valor = if (robot.anchoZonaCm > 0f) Formatos.centimetros(robot.anchoZonaCm) else Formatos.SIN_DATO,
                etiqueta = "ancho de zona",
                modifier = Modifier.weight(1f),
            )
        }
        if (robot.zonaActual in 1..numZonas) {
            Nota("Zona actual: ${robot.zonaActual} de $numZonas")
        }
    }
}

@Composable
private fun CifraResumen(valor: String, etiqueta: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                valor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                etiqueta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Última medición de cada zona (de las muestras de la sesión), ordenada por zona. */
internal fun ultimaMuestraPorZona(muestras: List<MuestraHumedad>): List<MuestraHumedad> =
    muestras.filter { it.zona != null }
        .associateBy { it.zona!! }
        .toSortedMap()
        .values
        .toList()

@Composable
private fun TarjetaUltimaHumedad(muestras: List<MuestraHumedad>) {
    val ultimas = remember(muestras) { ultimaMuestraPorZona(muestras) }
    TarjetaFertiTrack(titulo = "Última humedad por zona", icono = Icons.Rounded.WaterDrop) {
        if (ultimas.isEmpty()) {
            EstadoVacio(
                Icons.Rounded.WaterDrop,
                "Sin mediciones en esta sesión",
                detalle = "Aparecen aquí cuando el robot sensa las zonas.",
            )
            return@TarjetaFertiTrack
        }
        ultimas.forEachIndexed { i, muestra ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
            ) {
                MuestraNivel(muestra.nivel)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Zona ${muestra.zona}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(76.dp),
                )
                Text(
                    Traductor.nombreNivel(muestra.nivel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Formatos.porcentaje(muestra.humedadPct),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    Formatos.horaCorta(muestra.momentoMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Nota("Valores de la última medición de cada zona en esta sesión.")
    }
}
