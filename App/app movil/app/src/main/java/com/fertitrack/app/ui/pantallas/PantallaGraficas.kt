package com.fertitrack.app.ui.pantallas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.sesion.BombeoRiego
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.TipoExportacion
import com.fertitrack.app.ui.UiState
import java.time.LocalDate
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.FilaEvento
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.GraficaHumedad
import com.fertitrack.app.ui.componentes.LeyendaTanque
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.PildoraEnVivo
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TanqueHumedad
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.componentes.TextoAdc
import com.fertitrack.app.ui.componentes.nivelDePorcentaje
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_PANTALLA_GRAFICAS = "pantalla_graficas"
const val TAG_EVENTOS_RIEGO = "eventos_riego"
fun tagRango(rango: RangoGrafica): String = "rango_${rango.name}"

/** Ventanas de la tendencia; terminan en la medición más reciente. */
enum class RangoGrafica(val etiqueta: String, val descripcion: String, val duracionMs: Long) {
    HORA("1 h", "la última hora", 3_600_000L),
    SEIS_HORAS("6 h", "las últimas 6 horas", 6 * 3_600_000L),
    DIA("24 h", "las últimas 24 horas", 24 * 3_600_000L),
}

/**
 * Mediciones de las [rango] que terminan en la más reciente. Se ancla en la última medición y
 * no en el reloj: sin conexión la gráfica sigue mostrando lo último medido, con sus horas.
 */
internal fun muestrasEnRango(muestras: List<MuestraHumedad>, rango: RangoGrafica): List<MuestraHumedad> {
    val fin = muestras.maxOfOrNull { it.momentoMs } ?: return emptyList()
    return muestras.filter { it.momentoMs >= fin - rango.duracionMs }
}

/**
 * Gráficas: tanque con la humedad actual, tendencia por ventana, riegos por zona, eventos y
 * el historial guardado (exportar a CSV o borrar).
 */
@Composable
fun PantallaGraficas(estado: UiState, acciones: AccionesUi, modifier: Modifier = Modifier) {
    val resumen = estado.resumen
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_GRAFICAS),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("tanque") { TarjetaTanque(estado) }
        item("tendencia") { TarjetaTendencia(resumen.muestras, estado) }
        item("riegos") { TarjetaEventosRiego(resumen) }
        item("eventos") {
            TarjetaFertiTrack(titulo = "Últimos eventos", icono = Icons.AutoMirrored.Rounded.EventNote) {
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
                    Nota("Se guardan los últimos ${RobotConfig.MAX_EVENTOS} eventos, el más reciente arriba.")
                }
            }
        }
        item("historial") { TarjetaHistorial(resumen, acciones) }
    }
}

@Composable
private fun TarjetaTanque(estado: UiState) {
    val robot = estado.robot
    val ultima = estado.resumen.muestras.lastOrNull()
    // Sin lectura actual (robot sin datos o sensor sin medir), la última guardada.
    val pct = robot?.humedadPct ?: ultima?.humedadPct
    val adc = robot?.humedadAdc ?: ultima?.adc
    val calibracion = robot?.calibracion
    val nivel = when {
        pct == null -> NivelSuelo.SIN_MEDIR
        robot?.humedadPct != null && robot.nivelSuelo in NIVELES_CON_DATO -> robot.nivelSuelo
        calibracion != null -> nivelDePorcentaje(pct, calibracion)
        else -> ultima?.nivel ?: NivelSuelo.SIN_MEDIR
    }
    val enVivo = estado.conexion == EstadoConexion.Conectado && !estado.datosObsoletos && robot?.humedadPct != null

    TarjetaFertiTrack {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val anchoTanque = if (maxWidth >= 520.dp) 150.dp else 116.dp
            val altoTanque = if (maxWidth >= 520.dp) 236.dp else 208.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                TanqueHumedad(
                    pct = pct,
                    nivel = nivel,
                    marcas = calibracion?.let { listOf(it.pctSeca, it.pctHumeda, it.pctSobre) }.orEmpty(),
                    modifier = Modifier.size(width = anchoTanque, height = altoTanque),
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "TANQUE DE HUMEDAD",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PildoraEnVivo(enVivo)
                    Text(
                        if (pct == null) "Sin lectura" else tituloNivel(nivel),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (calibracion != null) {
                        LeyendaTanque(actual = nivel.takeIf { pct != null }, calibracion = calibracion)
                    }
                    TextoAdc(adc)
                    if (robot?.humedadPct == null && ultima != null) {
                        Nota("Última medición guardada: ${Formatos.fechaHoraCorta(ultima.momentoMs)}.")
                    }
                }
            }
        }
    }
}

private val NIVELES_CON_DATO = setOf(NivelSuelo.SECA, NivelSuelo.INTERMEDIA, NivelSuelo.HUMEDA, NivelSuelo.SOBRE_HUMEDA)

/** Título del tanque ("Suelo seco", "Nivel intermedio"…): los nombres de nivel son femeninos. */
private fun tituloNivel(nivel: NivelSuelo): String = when (nivel) {
    NivelSuelo.SECA -> "Suelo seco"
    NivelSuelo.INTERMEDIA -> "Nivel intermedio"
    NivelSuelo.HUMEDA -> "Suelo húmedo"
    NivelSuelo.SOBRE_HUMEDA -> "Suelo sobre-húmedo"
    NivelSuelo.SIN_MEDIR, NivelSuelo.DESCONOCIDO -> Traductor.nombreNivel(nivel)
}

@Composable
private fun TarjetaTendencia(muestras: List<MuestraHumedad>, estado: UiState) {
    var rango by rememberSaveable { mutableStateOf(RangoGrafica.HORA) }
    val visibles = remember(muestras, rango) { muestrasEnRango(muestras, rango) }
    // Sin icono: así el título y el selector caben en una línea en un teléfono.
    TarjetaFertiTrack(
        titulo = "Tendencia de humedad",
        acciones = { SelectorRango(rango, onElegir = { rango = it }) },
    ) {
        GraficaHumedad(
            muestras = visibles,
            calibracion = estado.robot?.calibracion,
            altoArea = 220.dp,
            tituloVacio = "Sin mediciones en ${rango.descripcion}",
        )
        if (visibles.isNotEmpty()) {
            Nota(
                "${visibles.size} ${if (visibles.size == 1) "medición" else "mediciones"} hasta las " +
                    "${Formatos.horaCorta(visibles.last().momentoMs)}. Una muestra por medición del robot, " +
                    "no por consulta.",
            )
        }
    }
}

/** Control segmentado 1 h · 6 h · 24 h. */
@Composable
private fun SelectorRango(elegido: RangoGrafica, onElegir: (RangoGrafica) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        RangoGrafica.entries.forEach { rango ->
            val activo = rango == elegido
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (activo) FertiTrackTema.colores.tarjeta else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = activo, role = Role.Tab, onClick = { onElegir(rango) })
                    .padding(horizontal = 12.dp)
                    .testTag(tagRango(rango)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rango.etiqueta,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (activo) FontWeight.Bold else FontWeight.Medium,
                    color = if (activo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TarjetaEventosRiego(resumen: ResumenSesion) {
    val bombeos = resumen.bombeos
    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_EVENTOS_RIEGO),
        titulo = "Eventos de riego",
        icono = Icons.Rounded.WaterDrop,
        acciones = {
            if (bombeos.isNotEmpty()) {
                Text(
                    "${bombeos.size} ${if (bombeos.size == 1) "riego" else "riegos"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (bombeos.isEmpty()) {
            EstadoVacio(
                Icons.Rounded.WaterDrop,
                "Sin riegos registrados",
                detalle = "Aparecen aquí, zona por zona, cuando un ciclo encuentra zonas secas y las riega.",
            )
            return@TarjetaFertiTrack
        }
        bombeos.asReversed().forEachIndexed { i, bombeo ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilaBombeo(bombeo)
        }
        val completos = resumen.riegos.count { it.completado }
        val interrumpidos = resumen.riegos.size - completos
        Nota(
            buildString {
                append("Duración aproximada (±1,5 s). ")
                append("$completos ${if (completos == 1) "riego completado" else "riegos completados"}")
                if (interrumpidos > 0) append(", $interrumpidos interrumpido${if (interrumpidos == 1) "" else "s"}")
                append(" en el historial.")
            },
        )
    }
}

@Composable
private fun FilaBombeo(bombeo: BombeoRiego) {
    val colores = FertiTrackTema.colores
    val zonas = when (bombeo.zonas.size) {
        0 -> "Zona sin identificar"
        1 -> "Zona ${bombeo.zonas.first()}"
        else -> "Zonas ${bombeo.zonas.first()}–${bombeo.zonas.last()}"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = colores.agua.contenedor, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("🚿", fontSize = 20.sp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(zonas, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                "${Formatos.horaCorta(bombeo.inicioMs)} · ≈ ${Formatos.duracion(bombeo.finMs - bombeo.inicioMs)} de bomba",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bombeo.interrumpido) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = colores.aviso.contenedor,
                contentColor = colores.aviso.sobreContenedor,
            ) {
                Text(
                    "Interrumpido",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

const val TAG_TARJETA_HISTORIAL = "tarjeta_historial"
fun tagExportar(tipo: TipoExportacion): String = "exportar_${tipo.name}"
const val TAG_BORRAR_HISTORIAL = "borrar_historial"

/**
 * Lo guardado en el teléfono, con exportación a CSV (el selector del sistema elige dónde:
 * Descargas, Drive…, sin pedir permisos) y la opción de borrarlo.
 */
@Composable
private fun TarjetaHistorial(resumen: ResumenSesion, acciones: AccionesUi) {
    var pendiente by rememberSaveable { mutableStateOf<TipoExportacion?>(null) }
    val selector = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val tipo = pendiente
        pendiente = null
        if (uri != null && tipo != null) acciones.exportar(tipo, uri.toString())
    }
    fun exportar(tipo: TipoExportacion) {
        pendiente = tipo
        selector.launch(tipo.nombreArchivo(LocalDate.now()))
    }
    val desde = listOfNotNull(
        resumen.muestras.firstOrNull()?.momentoMs,
        resumen.bombeos.firstOrNull()?.inicioMs,
        resumen.eventos.lastOrNull()?.momentoMs,
    ).minOrNull()
    val hayDatos = desde != null

    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_TARJETA_HISTORIAL),
        titulo = "Historial guardado",
        icono = Icons.Rounded.History,
    ) {
        Text(
            "${resumen.muestras.size} mediciones · ${resumen.bombeos.size} riegos · ${resumen.eventos.size} eventos",
            style = MaterialTheme.typography.titleSmall,
        )
        Nota(
            if (desde == null) {
                "Todavía no hay nada guardado. Se guarda en este teléfono aunque cierres la app."
            } else {
                "Desde el ${Formatos.fechaHoraCorta(desde)}. Se guarda en este teléfono aunque cierres la app " +
                    "(hasta ${RobotConfig.MAX_PUNTOS_GRAFICA} mediciones; las más antiguas salen primero)."
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { exportar(TipoExportacion.MEDICIONES) },
                enabled = resumen.muestras.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tagExportar(TipoExportacion.MEDICIONES)),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Mediciones CSV", maxLines = 1)
            }
            OutlinedButton(
                onClick = { exportar(TipoExportacion.RIEGOS) },
                enabled = resumen.bombeos.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(tagExportar(TipoExportacion.RIEGOS)),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Riegos CSV", maxLines = 1)
            }
        }
        TextButton(
            onClick = { acciones.pedirConfirmacion(AccionConfirmable.BorrarHistorial) },
            enabled = hayDatos,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BORRAR_HISTORIAL),
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Borrar historial", color = if (hayDatos) FertiTrackTema.colores.peligro.acento else Color.Unspecified)
        }
    }
}
