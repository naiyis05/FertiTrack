package com.fertitrack.app.ui.componentes

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fertitrack.app.ui.tema.FertiTrackTema

const val TAG_BOTON_DETENER = "boton_detener"

/**
 * Botón de parada de emergencia a todo el ancho.
 *
 * Regla de seguridad: NUNCA se deshabilita ni se oculta. Con [enCurso] muestra que la
 * orden se está enviando, pero sigue pulsable (repetir la parada es inofensivo).
 */
@Composable
fun BarraParada(
    enCurso: Boolean,
    onDetener: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colores = FertiTrackTema.colores
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = onDetener,
            enabled = true,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = colores.parada,
                contentColor = colores.sobreParada,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(min = 60.dp)
                .testTag(TAG_BOTON_DETENER)
                .semantics {
                    contentDescription = "Detener: parada de emergencia"
                    if (enCurso) stateDescription = "Enviando la parada"
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (enCurso) {
                        CircularProgressIndicator(
                            color = colores.sobreParada,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(26.dp),
                        )
                    } else {
                        Icon(Icons.Rounded.PanTool, contentDescription = null, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "DETENER",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        lineHeight = 26.sp,
                    )
                    Text(
                        text = if (enCurso) "Enviando parada…" else "Parada de emergencia",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
