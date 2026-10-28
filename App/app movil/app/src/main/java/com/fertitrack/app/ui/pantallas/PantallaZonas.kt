package com.fertitrack.app.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.AvisoZonasSecas
import com.fertitrack.app.ui.componentes.CieloHorario
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.MosaicoEstado
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.RielRecorrido
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.componentes.TarjetasZona
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_PANTALLA_ZONAS = "pantalla_zonas"
const val TAG_AVISO_SIN_RECORRIDO = "aviso_sin_recorrido"

/** Alto del cielo y cuánto de él tapa la tarjeta del invernadero. */
private val AltoCielo = 252.dp
private val SolapeTarjeta = 44.dp

/**
 * Zonas (la vista "Invernadero" del escritorio): el cielo con la hora del teléfono y, encima,
 * el invernadero con una tarjeta por zona y el riel con el robot; debajo, bombas, humedad
 * promedio, qué hace el robot y el aviso de zonas secas con el botón Regar.
 */
@Composable
fun PantallaZonas(estado: UiState, acciones: AccionesUi, modifier: Modifier = Modifier) {
    val robot = estado.robot
    val medidas = remember(estado.resumen.muestras) { ultimaMuestraPorZona(estado.resumen.muestras) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_ZONAS),
        contentPadding = PaddingValues(bottom = MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("invernadero") {
            Box {
                CieloHorario(alto = AltoCielo, reservaInferior = SolapeTarjeta)
                Box(Modifier.padding(start = MargenPantalla, end = MargenPantalla, top = AltoCielo - SolapeTarjeta)) {
                    when {
                        robot == null -> TarjetaFertiTrack(titulo = "Zonas del invernadero", icono = Icons.Rounded.GridView) {
                            EstadoVacio(
                                Icons.Rounded.GridView,
                                "Sin datos del robot todavía",
                                detalle = "Las zonas aparecen cuando la app recibe el estado del robot.",
                            )
                        }
                        !robot.distanciaConfigurada -> AvisoSinRecorrido(estado.ipRobot)
                        else -> TarjetaInvernadero(robot, medidas)
                    }
                }
            }
        }
        if (robot == null || !robot.distanciaConfigurada) return@LazyColumn

        item("estado") {
            MosaicoEstado(
                robot = robot,
                categoria = estado.descripcionEstado?.categoria,
                humedadPromedio = humedadPromedio(robot, medidas),
                modifier = Modifier.padding(horizontal = MargenPantalla),
            )
        }
        val secas = robot.zonas.mapIndexedNotNull { i, nivel -> if (nivel == NivelSuelo.SECA) i + 1 else null }
        if (secas.isNotEmpty()) {
            item("secas") {
                AvisoZonasSecas(
                    secas = secas,
                    calibracion = robot.calibracion,
                    // En EMERGENCIA el modo puede seguir en RIEGO, pero ya no riega.
                    regandoAhora = robot.modoRutina == ModoRutina.RIEGO && !robot.hayFalla,
                    habilitado = estado.rutinasHabilitadas,
                    motivo = estado.motivoRutinasDeshabilitadas,
                    onRegar = { acciones.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO)) },
                    modifier = Modifier.padding(horizontal = MargenPantalla),
                )
            }
        }
    }
}

@Composable
private fun TarjetaInvernadero(robot: EstadoRobot, medidas: Map<Int, MuestraHumedad>) {
    val zonas = robot.zonas.ifEmpty { List(robot.totalZonas) { NivelSuelo.SIN_MEDIR } }
    TarjetaFertiTrack {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Invernadero", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${zonas.size} ${if (zonas.size == 1) "zona" else "zonas"} · ${Formatos.centimetros(robot.distanciaCm)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TarjetasZona(zonas = zonas, zonaActual = robot.zonaActual, medidas = medidas)
        RielRecorrido(
            zonas = zonas,
            zonaActual = robot.zonaActual,
            fraccion = robot.fraccionRecorrido,
            distanciaCm = robot.distanciaCm,
            robot = robot,
        )
        Nota(
            "El color y la planta los da el robot; el porcentaje es la última medición de cada zona " +
                "en esta sesión. Zonas de ${Formatos.centimetros(robot.anchoZonaCm)}.",
        )
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

/** Última medición de cada zona (de las muestras de la sesión), por número de zona. */
internal fun ultimaMuestraPorZona(muestras: List<MuestraHumedad>): Map<Int, MuestraHumedad> =
    muestras.filter { it.zona != null }.associateBy { it.zona!! }

/**
 * Promedio de la última medición de cada zona que sigue en el nivel que reporta el robot;
 * sin ninguna, la última lectura del sensor.
 */
private fun humedadPromedio(robot: EstadoRobot, medidas: Map<Int, MuestraHumedad>): Float? {
    val vigentes = medidas.filter { (zona, m) -> robot.zonas.getOrNull(zona - 1) == m.nivel }.values
    return if (vigentes.isEmpty()) robot.humedadPct else vigentes.map { it.humedadPct }.average().toFloat()
}
