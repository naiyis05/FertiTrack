package com.fertitrack.app.ui.componentes

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.fertitrack.app.asistente.AccionAsistente
import com.fertitrack.app.asistente.AccionesAsistente
import com.fertitrack.app.asistente.EstadoAsistente
import com.fertitrack.app.asistente.MensajeChat
import com.fertitrack.app.asistente.RutinaPropuesta
import com.fertitrack.app.asistente.TextosAsistente
import com.fertitrack.app.asistente.gemini.TipoFallo
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_BOTON_ASISTENTE = "boton_asistente"
const val TAG_PANEL_ASISTENTE = "panel_asistente"
const val TAG_CAMPO_ASISTENTE = "campo_asistente"
const val TAG_ENVIAR_ASISTENTE = "enviar_asistente"
const val TAG_MICROFONO = "microfono_asistente"
const val TAG_TRANSCRIPCION = "transcripcion_asistente"
const val TAG_CERRAR_ASISTENTE = "cerrar_asistente"
const val TAG_VOZ_ASISTENTE = "voz_asistente"

fun tagSugerencia(i: Int) = "sugerencia_asistente_$i"

fun tagAccionAsistente(idMensaje: Long, i: Int) = "accion_asistente_${idMensaje}_$i"

/**
 * Panel del asistente dibujado DENTRO del contenido, como [OverlayConfirmacion]: no abre otra
 * ventana, así que la barra DETENER sigue visible y pulsable debajo.
 */
@Composable
fun PanelAsistente(
    estado: EstadoAsistente,
    acciones: AccionesAsistente,
    onMicrofono: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    Surface(
        color = colores.tarjeta,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        modifier = modifier
            .testTag(TAG_PANEL_ASISTENTE)
            .semantics { paneTitle = "Daniela, asistente de FertiTrack" },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Cabecera(estado, acciones)
            if (!estado.claveConfigurada) {
                Aviso(TextosAsistente.fallo(TipoFallo.SIN_CLAVE))
            }
            Conversacion(estado, acciones, Modifier.weight(1f, fill = true))
            if (estado.escuchando) Transcripcion(estado.transcripcion)
            Entrada(estado, acciones, onMicrofono)
        }
    }
}

@Composable
private fun Cabecera(estado: EstadoAsistente, acciones: AccionesAsistente) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Daniela", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                when {
                    estado.escuchando -> "Te escucho…"
                    estado.pensando -> "Pensando…"
                    estado.hablando -> "Hablando…"
                    else -> "Pregúntame por voz o por escrito"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (estado.hablando) {
            TextButton(onClick = acciones::cancelar) {
                Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Parar voz", modifier = Modifier.padding(start = 4.dp))
            }
        }
        IconButton(onClick = acciones::alternarVoz, modifier = Modifier.testTag(TAG_VOZ_ASISTENTE)) {
            Icon(
                if (estado.vozActivada) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = if (estado.vozActivada) "Leer respuestas en voz alta: activado" else "Leer respuestas en voz alta: desactivado",
            )
        }
        IconButton(onClick = acciones::cerrar, modifier = Modifier.testTag(TAG_CERRAR_ASISTENTE)) {
            Icon(Icons.Rounded.Close, contentDescription = "Cerrar el asistente")
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    val tono = FertiTrackTema.colores.aviso
    Text(
        texto,
        style = MaterialTheme.typography.bodyMedium,
        color = tono.acento,
        modifier = Modifier
            .fillMaxWidth()
            .background(tono.contenedor, MaterialTheme.shapes.medium)
            .padding(12.dp),
    )
}

@Composable
private fun Conversacion(estado: EstadoAsistente, acciones: AccionesAsistente, modifier: Modifier) {
    val lista = rememberLazyListState()
    val total = estado.mensajes.size + if (estado.pensando) 1 else 0
    LaunchedEffect(total) { if (total > 0) lista.animateScrollToItem(total - 1) }

    if (estado.mensajes.isEmpty()) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Hola. Puedo explicarte la app, llevarte a una pantalla, contarte cómo están las zonas o buscar en internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            estado.sugerencias.forEachIndexed { i, sugerencia ->
                SuggestionChip(
                    onClick = { acciones.enviarTexto(sugerencia) },
                    label = { Text(sugerencia) },
                    modifier = Modifier.testTag(tagSugerencia(i)),
                )
            }
            if (estado.pensando) Pensando()
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = lista, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(estado.mensajes, key = { it.id }) { mensaje -> Burbuja(mensaje, acciones) }
        if (estado.pensando) item(key = "pensando") { Pensando() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Burbuja(mensaje: MensajeChat, acciones: AccionesAsistente) {
    val esquema = MaterialTheme.colorScheme
    val fondo = when {
        mensaje.deUsuario -> esquema.primaryContainer
        mensaje.esError -> esquema.errorContainer
        else -> esquema.surfaceContainerHigh
    }
    val texto = when {
        mensaje.deUsuario -> esquema.onPrimaryContainer
        mensaje.esError -> esquema.onErrorContainer
        else -> esquema.onSurface
    }
    val forma = if (mensaje.deUsuario) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mensaje.deUsuario) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .background(fondo, forma)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(mensaje.texto, style = MaterialTheme.typography.bodyLarge, color = texto)
            if (mensaje.fuentes.isNotEmpty()) {
                val abrir = LocalUriHandler.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mensaje.fuentes.forEach { fuente ->
                        AssistChip(
                            onClick = { abrir.openUri(fuente.url) },
                            label = { Text(fuente.titulo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
            }
            mensaje.sugerenciasHtml?.let { SugerenciasBusqueda(it) }
            if (mensaje.acciones.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    mensaje.acciones.forEachIndexed { i, accion ->
                        AssistChip(
                            onClick = { acciones.repetirAccion(accion) },
                            label = { Text(textoAccion(accion)) },
                            modifier = Modifier.testTag(tagAccionAsistente(mensaje.id, i)),
                        )
                    }
                }
            }
        }
    }
}

private fun textoAccion(accion: AccionAsistente): String = when (accion) {
    is AccionAsistente.IrA -> "Ir a ${accion.destino.titulo}"
    is AccionAsistente.ProponerRutina -> "Abrir confirmación: " + when (accion.rutina) {
        RutinaPropuesta.CICLO -> "ciclo completo"
        RutinaPropuesta.SENSADO -> "solo sensar"
        RutinaPropuesta.FERTILIZAR -> "fertilizar"
    }
}

/**
 * Sugerencias de búsqueda de Google: las condiciones de uso exigen mostrarlas sin modificar
 * junto a la respuesta que usó la búsqueda. Sus enlaces se abren en el navegador.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SugerenciasBusqueda(html: String) {
    AndroidView(
        factory = { contexto ->
            WebView(contexto).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // El HTML de Google se ajusta al tema y al ancho con JavaScript.
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url.toString().toUri()))
                        return true
                    }
                }
            }
        },
        // update corre en cada recomposición (cada mensaje nuevo, cada cambio de voz): solo se
        // recarga si el HTML cambió, para que no parpadee ni vuelva a ejecutar su JavaScript.
        update = { vista ->
            if (vista.tag != html) {
                vista.tag = html
                vista.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(64.dp),
    )
}

@Composable
private fun Pensando() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Pensando…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Transcripcion(texto: String) {
    Text(
        texto.ifBlank { "Te escucho…" },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag(TAG_TRANSCRIPCION)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Entrada(estado: EstadoAsistente, acciones: AccionesAsistente, onMicrofono: () -> Unit) {
    var texto by rememberSaveable { mutableStateOf("") }
    val enviar = {
        if (texto.isNotBlank()) {
            acciones.enviarTexto(texto)
            texto = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            placeholder = { Text("Escribe tu pregunta") },
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { enviar() }),
            trailingIcon = {
                IconButton(onClick = enviar, enabled = texto.isNotBlank(), modifier = Modifier.testTag(TAG_ENVIAR_ASISTENTE)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Enviar")
                }
            },
            modifier = Modifier.weight(1f).testTag(TAG_CAMPO_ASISTENTE),
        )
        BotonMicrofono(estado, onMicrofono)
    }
}

/** Micrófono grande; mientras escucha, un anillo crece con el volumen de la voz. */
@Composable
private fun BotonMicrofono(estado: EstadoAsistente, onMicrofono: () -> Unit) {
    val escala by animateFloatAsState(if (estado.escuchando) 1f + 0.35f * estado.nivelVoz else 1f, label = "anillo")
    val esquema = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center) {
        if (estado.escuchando) {
            Box(Modifier.size(56.dp).scale(escala).background(esquema.primary.copy(alpha = 0.25f), CircleShape))
        }
        // Siempre habilitado: sin reconocimiento de voz, el asistente explica por qué no escucha.
        FilledIconButton(
            onClick = onMicrofono,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (estado.escuchando) esquema.error else esquema.primary,
            ),
            modifier = Modifier.size(56.dp).testTag(TAG_MICROFONO),
        ) {
            Icon(
                if (estado.escuchando) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (estado.escuchando) "Terminar de hablar" else "Hablar con el asistente",
            )
        }
    }
}
