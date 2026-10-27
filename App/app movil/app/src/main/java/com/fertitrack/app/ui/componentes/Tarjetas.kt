package com.fertitrack.app.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

/** Margen lateral de las pantallas y separación entre tarjetas. */
val MargenPantalla: Dp = 16.dp
val SeparacionTarjetas: Dp = 12.dp

/**
 * Tarjeta base: esquinas de 20 dp y sombra suave, como las de la app de escritorio.
 * Con [tono] se pinta con el color de contenedor de ese tono (avisos, fallas…).
 */
@Composable
fun TarjetaFertiTrack(
    modifier: Modifier = Modifier,
    titulo: String? = null,
    icono: ImageVector? = null,
    tono: TonoSemantico? = null,
    acciones: @Composable (RowScope.() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    val fondo = tono?.contenedor ?: FertiTrackTema.colores.tarjeta
    val texto = tono?.sobreContenedor ?: MaterialTheme.colorScheme.onSurface
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = texto),
        elevation = CardDefaults.cardElevation(defaultElevation = if (tono == null) 1.dp else 0.dp),
        border = tono?.let { BorderStroke(1.dp, it.acento.copy(alpha = 0.45f)) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (titulo != null) {
                EncabezadoTarjeta(
                    titulo = titulo,
                    icono = icono,
                    colorIcono = tono?.acento ?: MaterialTheme.colorScheme.primary,
                    acciones = acciones,
                )
            }
            contenido()
        }
    }
}

@Composable
fun EncabezadoTarjeta(
    titulo: String,
    icono: ImageVector?,
    colorIcono: Color,
    acciones: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono != null) {
            Icon(icono, contentDescription = null, tint = colorIcono, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (acciones != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = acciones)
        }
    }
}

/** Fila "etiqueta ........ valor". */
@Composable
fun FilaDato(
    etiqueta: String,
    valor: String,
    modifier: Modifier = Modifier,
    icono: ImageVector? = null,
    colorIcono: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    colorValor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono != null) {
            Icon(icono, contentDescription = null, tint = colorIcono, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = valor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colorValor,
            textAlign = TextAlign.End,
        )
    }
}

/** Punto de color (estado de conexión, bombas, niveles). */
@Composable
fun Punto(color: Color, modifier: Modifier = Modifier, tamano: Dp = 10.dp) {
    Box(modifier.size(tamano).clip(CircleShape).background(color))
}

/** Mensaje amable para listas o gráficas sin datos. */
@Composable
fun EstadoVacio(
    icono: ImageVector,
    titulo: String,
    modifier: Modifier = Modifier,
    detalle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icono, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            titulo,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        if (detalle != null) {
            Text(
                detalle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Franja de aviso a todo el ancho (debajo de la barra superior). */
@Composable
fun FranjaAviso(
    texto: String,
    icono: ImageVector,
    tono: TonoSemantico,
    modifier: Modifier = Modifier,
    accion: @Composable (RowScope.() -> Unit)? = null,
) {
    Surface(color = tono.contenedor, contentColor = tono.sobreContenedor, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icono, contentDescription = null, tint = tono.acento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                texto,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (accion != null) Row(verticalAlignment = Alignment.CenterVertically, content = accion)
        }
    }
}

/** Texto secundario pequeño (notas al pie de una tarjeta). */
@Composable
fun Nota(texto: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(texto, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}
