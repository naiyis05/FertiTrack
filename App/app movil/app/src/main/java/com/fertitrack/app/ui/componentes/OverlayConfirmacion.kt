package com.fertitrack.app.ui.componentes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fertitrack.app.ui.Confirmacion
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_OVERLAY_CONFIRMACION = "overlay_confirmacion"
const val TAG_BOTON_CONFIRMAR = "boton_confirmar"
const val TAG_BOTON_CANCELAR = "boton_cancelar"

/**
 * Confirmación de una acción física dibujada DENTRO del contenido (scrim + tarjeta).
 *
 * No usa AlertDialog, Dialog, ModalBottomSheet ni Popup: esos abren otra ventana que
 * taparía la barra DETENER. Así, las barras superior e inferior siguen visibles y
 * pulsables mientras la confirmación está abierta. Tocar fuera de la tarjeta cancela.
 */
@Composable
fun OverlayConfirmacion(
    confirmacion: Confirmacion?,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (confirmacion == null) return
    // Solo anima la entrada: al confirmarse o cancelarse desaparece en el acto, para que no
    // quede una tarjeta pulsable durante una animación de salida.
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }

    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(),
        exit = ExitTransition.None,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().testTag(TAG_OVERLAY_CONFIRMACION), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onCancelar() }) }
                    .clearAndSetSemantics { },
            )
            TarjetaConfirmacion(
                confirmacion = confirmacion,
                onConfirmar = onConfirmar,
                onCancelar = onCancelar,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .widthIn(max = 480.dp)
                    .animateEnterExit(enter = scaleIn(initialScale = 0.92f)),
            )
        }
    }
}

@Composable
private fun TarjetaConfirmacion(
    confirmacion: Confirmacion,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    val tono = if (confirmacion.peligrosa) colores.aviso else colores.exito
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colores.tarjeta),
        border = BorderStroke(1.dp, tono.acento.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            // Los toques sobre la tarjeta no deben llegar al scrim (que cancela).
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics {
                paneTitle = confirmacion.titulo
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(tono.contenedor, MaterialTheme.shapes.extraLarge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (confirmacion.peligrosa) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                    contentDescription = if (confirmacion.peligrosa) "Advertencia" else null,
                    tint = tono.acento,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                confirmacion.titulo,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                confirmacion.mensaje,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancelar,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(TAG_BOTON_CANCELAR),
                ) { Text("Cancelar", textAlign = TextAlign.Center) }
                Button(
                    onClick = onConfirmar,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tono.acento,
                        contentColor = tono.sobreAcento,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(TAG_BOTON_CONFIRMAR),
                ) { Text(confirmacion.textoConfirmar, textAlign = TextAlign.Center) }
            }
        }
    }
}
