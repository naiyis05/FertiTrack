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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.TipoFalla
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.traduccion.CategoriaEstado
import com.fertitrack.app.domain.traduccion.DescripcionEstado
import com.fertitrack.app.domain.traduccion.DescripcionFalla
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.Pestana
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.EscalaNiveles
import com.fertitrack.app.ui.componentes.EstadoVacio
import com.fertitrack.app.ui.componentes.FilaDato
import com.fertitrack.app.ui.componentes.FilaEvento
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.GraficaHumedad
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.Punto
import com.fertitrack.app.ui.componentes.ResumenInsight
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

const val TAG_PANTALLA_INICIO = "pantalla_inicio"
const val TAG_TARJETA_FALLA = "tarjeta_falla"
const val TAG_TARJETA_FERTILIZAR = "tarjeta_fertilizar"
const val TAG_FERTILIZAR_SI = "boton_fertilizar_si"
const val TAG_FERTILIZAR_NO = "boton_fertilizar_no"
const val TAG_REINICIAR_FALLA = "boton_reiniciar_falla"

/** Inicio: falla activa, sugerencia de fertilizar, humedad, estado, gráfica, análisis y eventos. */
@Composable
fun PantallaInicio(
    estado: UiState,
    acciones: AccionesUi,
    modifier: Modifier = Modifier,
    irAPestana: (Pestana) -> Unit = {},
) {
    // Una falla nueva o la sugerencia de fertilizar aparecen arriba del todo: si la lista
    // estaba desplazada, sube para que el aviso no quede fuera de la vista.
    val lista = rememberLazyListState()
    val sugerir = estado.resumen.sugerirFertilizar
    LaunchedEffect(estado.falla?.codigo, sugerir) {
        if (estado.falla != null || sugerir) lista.animateScrollToItem(0)
    }
    LazyColumn(
        state = lista,
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_INICIO),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        estado.falla?.let { falla ->
            item("falla") {
                TarjetaFalla(
                    falla = falla,
                    reiniciarHabilitado = estado.reiniciarHabilitado,
                    onReiniciar = { acciones.pedirConfirmacion(AccionConfirmable.Reiniciar) },
                )
            }
        }
        if (estado.resumen.sugerirFertilizar) {
            item("sugerencia") {
                TarjetaSugerenciaFertilizar(
                    habilitado = estado.rutinasHabilitadas,
                    motivo = estado.motivoRutinasDeshabilitadas,
                    onResponder = acciones::responderSugerenciaFertilizar,
                )
            }
        }
        item("humedad") { TarjetaHumedad(estado.robot, estado.resumen, obsoletos = estado.datosObsoletos) }
        item("maquina") { TarjetaEstadoMaquina(estado.robot, estado.descripcionEstado, obsoletos = estado.datosObsoletos) }
        item("grafica") {
            TarjetaFertiTrack(titulo = "Humedad reciente", icono = Icons.AutoMirrored.Rounded.ShowChart) {
                GraficaHumedad(
                    muestras = estado.resumen.muestras.takeLast(20),
                    calibracion = estado.robot?.calibracion,
                    altoArea = 120.dp,
                    compacta = true,
                )
                if (estado.resumen.muestras.isNotEmpty()) {
                    Nota("Últimas ${minOf(20, estado.resumen.muestras.size)} mediciones. Detalle en Gráficas.")
                }
            }
        }
        item("analisis") {
            TarjetaFertiTrack(
                titulo = "Análisis automático",
                icono = Icons.Rounded.Lightbulb,
                acciones = if (estado.insights.size > 1) {
                    { TextButton(onClick = { irAPestana(Pestana.ANALISIS) }) { Text("Ver todo") } }
                } else {
                    null
                },
            ) {
                ResumenInsight(estado.insights.firstOrNull())
            }
        }
        item("eventos") {
            TarjetaFertiTrack(
                titulo = "Eventos recientes",
                icono = Icons.Rounded.History,
                acciones = if (estado.resumen.eventos.size > 5) {
                    { TextButton(onClick = { irAPestana(Pestana.GRAFICAS) }) { Text("Ver todos") } }
                } else {
                    null
                },
            ) {
                val eventos = estado.resumen.eventos.take(5)
                if (eventos.isEmpty()) {
                    EstadoVacio(Icons.AutoMirrored.Rounded.EventNote, "Sin eventos todavía")
                } else {
                    eventos.forEachIndexed { i, evento ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilaEvento(evento)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TarjetaFalla(
    falla: DescripcionFalla,
    reiniciarHabilitado: Boolean,
    onReiniciar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tono = FertiTrackTema.colores.peligro
    TarjetaFertiTrack(
        modifier = modifier.testTag(TAG_TARJETA_FALLA),
        titulo = falla.titulo,
        icono = Icons.Rounded.Error,
        tono = tono,
    ) {
        Text("Código de falla ${falla.codigo}", style = MaterialTheme.typography.labelMedium)
        Text(falla.explicacion, style = MaterialTheme.typography.bodyMedium)
        Surface(shape = MaterialTheme.shapes.small, color = tono.acento.copy(alpha = 0.10f)) {
            Text(
                "Qué hacer: ${falla.accionSugerida}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(10.dp),
            )
        }
        Button(
            onClick = onReiniciar,
            enabled = reiniciarHabilitado,
            colors = ButtonDefaults.buttonColors(containerColor = tono.acento, contentColor = tono.sobreAcento),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_REINICIAR_FALLA),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reiniciar…")
        }
        if (!reiniciarHabilitado) {
            Nota(
                "Reiniciar se habilita cuando el robot está en emergencia y responde.",
                color = tono.sobreContenedor,
            )
        }
    }
}

@Composable
private fun TarjetaSugerenciaFertilizar(
    habilitado: Boolean,
    motivo: String?,
    onResponder: (Boolean) -> Unit,
) {
    val tono = FertiTrackTema.colores.fertilizante
    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_TARJETA_FERTILIZAR),
        titulo = "Riego completado",
        icono = Icons.Rounded.Science,
        tono = tono,
    ) {
        Text(
            "¿Deseas fertilizar ahora?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "El robot recorrerá todo el riel con el brazo de fertilización bajado.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = { onResponder(false) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(TAG_FERTILIZAR_NO),
            ) { Text("Ahora no", color = tono.sobreContenedor) }
            Button(
                onClick = { onResponder(true) },
                enabled = habilitado,
                colors = ButtonDefaults.buttonColors(containerColor = tono.acento, contentColor = tono.sobreAcento),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(TAG_FERTILIZAR_SI),
            ) { Text("Sí, fertilizar") }
        }
        if (!habilitado) {
            Nota(motivo ?: "Ahora no se puede iniciar la fertilización.", color = tono.sobreContenedor)
        }
    }
}

/** Una lectura es válida solo con ADC ≥ 1 y sin la falla 3 (el firmware da 100 % en ese caso). */
internal fun lecturaValida(robot: EstadoRobot): Boolean {
    val adc = robot.humedadAdc
    return adc != null && adc >= 1 && robot.humedadPct != null && robot.falla != TipoFalla.SENSOR_SIN_LECTURA
}

@Composable
private fun TarjetaHumedad(robot: EstadoRobot?, resumen: ResumenSesion, obsoletos: Boolean) {
    val agua = FertiTrackTema.colores.agua
    TarjetaFertiTrack(titulo = "Humedad del suelo", icono = Icons.Rounded.WaterDrop) {
        val pctValido = robot?.takeIf { lecturaValida(it) }?.humedadPct
        val valida = pctValido != null
        val valor = Formatos.porcentaje(pctValido)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                valor,
                style = FertiTrackTema.estiloCifra,
                color = if (valida && !obsoletos) agua.acento else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = if (valida) "Humedad $valor" else "Humedad sin lectura"
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(if (obsoletos) "Última lectura conocida" else "Última lectura", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val adc = robot?.humedadAdc
                Text(
                    when {
                        robot == null -> "Sin datos"
                        robot.falla == TipoFalla.SENSOR_SIN_LECTURA -> "Sensor sin lectura válida"
                        adc == null -> "ADC sin lectura"
                        else -> "ADC $adc"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        EscalaNiveles(actual = robot?.nivelSuelo)
        val ultimaZona = resumen.muestras.lastOrNull { it.zona != null }?.zona
        Nota(
            when {
                robot == null -> "Esperando datos del robot."
                ultimaZona != null && robot.totalZonas > 0 -> "Última zona medida: $ultimaZona de ${robot.totalZonas}"
                robot.nivelSuelo == NivelSuelo.SIN_MEDIR -> "Todavía no hay zonas medidas."
                else -> "El nivel resaltado es el de la última zona medida."
            },
        )
    }
}

@Composable
internal fun tonoCategoria(categoria: CategoriaEstado): TonoSemantico {
    val c = FertiTrackTema.colores
    return when (categoria) {
        CategoriaEstado.REPOSO -> c.exito
        CategoriaEstado.SENSADO -> c.sensado
        CategoriaEstado.RIEGO -> c.agua
        CategoriaEstado.FERTILIZACION -> c.fertilizante
        CategoriaEstado.RECUPERACION, CategoriaEstado.ARRANQUE -> c.neutro
        CategoriaEstado.PRUEBA -> c.prueba
        CategoriaEstado.EMERGENCIA -> c.peligro
        CategoriaEstado.DESCONOCIDO -> c.neutro
    }
}

@Composable
private fun TarjetaEstadoMaquina(robot: EstadoRobot?, descripcion: DescripcionEstado?, obsoletos: Boolean) {
    val colores = FertiTrackTema.colores
    TarjetaFertiTrack(titulo = "Estado de la máquina", icono = Icons.Rounded.SmartToy) {
        if (robot == null) {
            EstadoVacio(
                Icons.Rounded.SmartToy,
                "Sin datos del robot todavía",
                detalle = "Toca el indicador de conexión de arriba para ver qué pasa.",
            )
            return@TarjetaFertiTrack
        }
        val categoria = descripcion?.categoria ?: CategoriaEstado.DESCONOCIDO
        val tono = tonoCategoria(categoria)
        if (obsoletos) {
            Nota("Último estado conocido: el robot puede haber cambiado desde entonces.", color = colores.aviso.acento)
        }
        Surface(shape = MaterialTheme.shapes.medium, color = tono.contenedor, contentColor = tono.sobreContenedor) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Punto(tono.acento, tamano = 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        descripcion?.titulo ?: Traductor.nombreEstado(robot.estado, robot.estadoTexto),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val detalle = descripcion?.detalle
                    if (!detalle.isNullOrBlank()) {
                        Text(detalle, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        FilaDato("Rutina", Traductor.nombreRutina(robot.modoRutina))
        FilaDato("Actividad", Traductor.nombreActividad(robot.actividad))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            IndicadorBomba(
                nombre = "Bomba de riego",
                encendida = robot.bombaRiegoEncendida,
                tono = colores.agua,
                icono = Icons.Rounded.WaterDrop,
                modifier = Modifier.weight(1f),
            )
            IndicadorBomba(
                nombre = "Fertilizante",
                encendida = robot.bombaFertilizanteEncendida,
                tono = colores.fertilizante,
                icono = Icons.Rounded.Science,
                modifier = Modifier.weight(1f),
            )
        }
        val fraccion = robot.fraccionRecorrido
        if (fraccion != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Recorrido ${Formatos.porcentaje(fraccion * 100)}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (robot.totalZonas > 0 && robot.zonaActual > 0) {
                        Text(
                            "Zona ${robot.zonaActual} de ${robot.totalZonas}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { fraccion },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp),
                    color = tono.acento,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        } else {
            Nota("Recorrido sin configurar: el robot no sabe la distancia del riel.")
        }
        // Con datos viejos la cuenta atrás estaría congelada: mejor no mostrarla.
        robot.proximoCicloSeg?.takeIf { !obsoletos }?.let { segundos ->
            FilaDato(
                etiqueta = "Próximo ciclo automático",
                valor = "en ${Formatos.cuentaAtras(segundos)}",
                icono = Icons.Rounded.Schedule,
                colorIcono = colores.agua.acento,
            )
        }
    }
}

@Composable
private fun IndicadorBomba(
    nombre: String,
    encendida: Boolean,
    tono: TonoSemantico,
    icono: ImageVector,
    modifier: Modifier = Modifier,
) {
    val estadoTexto = if (encendida) "Encendida" else "Apagada"
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (encendida) tono.contenedor else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (encendida) tono.sobreContenedor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$nombre: $estadoTexto" },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icono,
                contentDescription = null,
                tint = if (encendida) tono.acento else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(nombre, style = MaterialTheme.typography.labelMedium)
                Text(
                    estadoTexto,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (encendida) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
