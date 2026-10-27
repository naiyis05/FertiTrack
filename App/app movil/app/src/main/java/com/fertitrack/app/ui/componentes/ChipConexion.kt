package com.fertitrack.app.ui.componentes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico
import kotlinx.coroutines.delay

const val TAG_CHIP_CONEXION = "chip_conexion"

/** Texto corto del chip de conexión. */
fun textoConexion(conexion: EstadoConexion, usandoSimulador: Boolean, ahoraMs: Long): String =
    when (conexion) {
        EstadoConexion.Pausado -> "En pausa"
        EstadoConexion.Conectando -> "Conectando…"
        EstadoConexion.Conectado -> if (usandoSimulador) "Simulador" else "Conectado"
        is EstadoConexion.SinRespuesta -> "Sin respuesta"
        is EstadoConexion.Reintentando -> {
            val segundos = Formatos.segundosHasta(conexion.proximoIntentoMs, ahoraMs)
            if (segundos > 0) "Reintentando en $segundos s" else "Reintentando…"
        }
    }

@Composable
fun tonoConexion(conexion: EstadoConexion, usandoSimulador: Boolean): TonoSemantico {
    val colores = FertiTrackTema.colores
    return when (conexion) {
        EstadoConexion.Pausado -> colores.neutro
        EstadoConexion.Conectando -> colores.aviso
        EstadoConexion.Conectado -> if (usandoSimulador) colores.prueba else colores.exito
        is EstadoConexion.SinRespuesta, is EstadoConexion.Reintentando -> colores.peligro
    }
}

/**
 * Reloj de pared que avanza cada segundo mientras falte tiempo para [hastaMs]. Solo sirve
 * para la cuenta atrás del reintento: el bucle termina solo y no deja tareas colgadas.
 */
@Composable
fun rememberAhoraHasta(hastaMs: Long?): Long {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hastaMs) {
        ahora = System.currentTimeMillis()
        if (hastaMs == null) return@LaunchedEffect
        while (ahora < hastaMs) {
            delay((hastaMs - ahora).coerceIn(1L, 1_000L))
            ahora = System.currentTimeMillis()
        }
    }
    return ahora
}

/** Chip de la barra superior: punto de color + estado. Toca para abrir Conexión. */
@Composable
fun ChipConexion(
    conexion: EstadoConexion,
    usandoSimulador: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ahora = rememberAhoraHasta((conexion as? EstadoConexion.Reintentando)?.proximoIntentoMs)
    val texto = textoConexion(conexion, usandoSimulador, ahora)
    val tono = tonoConexion(conexion, usandoSimulador)
    AssistChip(
        onClick = onClick,
        label = { Text(texto, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Punto(tono.acento) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = tono.contenedor,
            labelColor = tono.sobreContenedor,
        ),
        border = BorderStroke(1.dp, tono.acento.copy(alpha = 0.5f)),
        modifier = modifier
            .heightIn(min = 36.dp)
            .testTag(TAG_CHIP_CONEXION)
            .semantics { contentDescription = "Conexión: $texto. Toca para ver detalles" },
    )
}
