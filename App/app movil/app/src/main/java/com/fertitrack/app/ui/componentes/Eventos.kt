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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.analisis.Insight
import com.fertitrack.app.domain.analisis.Severidad
import com.fertitrack.app.domain.sesion.EventoSesion
import com.fertitrack.app.domain.sesion.TipoEvento
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

fun iconoEvento(tipo: TipoEvento): ImageVector = when (tipo) {
    TipoEvento.CONEXION -> Icons.Rounded.Wifi
    TipoEvento.RUTINA -> Icons.Rounded.PlayCircle
    TipoEvento.RIEGO -> Icons.Rounded.WaterDrop
    TipoEvento.FERTILIZACION -> Icons.Rounded.Science
    TipoEvento.MEDICION -> Icons.Rounded.Sensors
    TipoEvento.FALLA -> Icons.Rounded.Error
    TipoEvento.COMANDO -> Icons.Rounded.TouchApp
    TipoEvento.AVISO -> Icons.Rounded.WarningAmber
}

fun nombreTipoEvento(tipo: TipoEvento): String = when (tipo) {
    TipoEvento.CONEXION -> "Conexión"
    TipoEvento.RUTINA -> "Rutina"
    TipoEvento.RIEGO -> "Riego"
    TipoEvento.FERTILIZACION -> "Fertilización"
    TipoEvento.MEDICION -> "Medición"
    TipoEvento.FALLA -> "Falla"
    TipoEvento.COMANDO -> "Orden"
    TipoEvento.AVISO -> "Aviso"
}

@Composable
fun tonoEvento(tipo: TipoEvento): TonoSemantico {
    val c = FertiTrackTema.colores
    return when (tipo) {
        TipoEvento.CONEXION -> c.neutro
        TipoEvento.RUTINA -> c.exito
        TipoEvento.RIEGO -> c.agua
        TipoEvento.FERTILIZACION -> c.fertilizante
        TipoEvento.MEDICION -> c.sensado
        TipoEvento.FALLA -> c.peligro
        TipoEvento.COMANDO -> c.prueba
        TipoEvento.AVISO -> c.aviso
    }
}

/** Una línea del registro de eventos: icono por tipo, hora y texto. */
@Composable
fun FilaEvento(evento: EventoSesion, modifier: Modifier = Modifier) {
    val tono = tonoEvento(evento.tipo)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = tono.contenedor, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    iconoEvento(evento.tipo),
                    contentDescription = nombreTipoEvento(evento.tipo),
                    tint = tono.acento,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            evento.texto,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            Formatos.hora(evento.momentoMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun tonoSeveridad(severidad: Severidad): TonoSemantico {
    val c = FertiTrackTema.colores
    return when (severidad) {
        Severidad.INFO -> c.agua
        Severidad.EXITO -> c.exito
        Severidad.AVISO -> c.aviso
        Severidad.CRITICO -> c.peligro
    }
}

fun iconoSeveridad(severidad: Severidad): ImageVector = when (severidad) {
    Severidad.INFO -> Icons.Rounded.Info
    Severidad.EXITO -> Icons.Rounded.CheckCircle
    Severidad.AVISO -> Icons.Rounded.WarningAmber
    Severidad.CRITICO -> Icons.Rounded.Error
}

fun nombreSeveridad(severidad: Severidad): String = when (severidad) {
    Severidad.INFO -> "Información"
    Severidad.EXITO -> "Todo bien"
    Severidad.AVISO -> "Aviso"
    Severidad.CRITICO -> "Crítico"
}

/** Tarjeta de un insight del análisis automático, coloreada por severidad. */
@Composable
fun TarjetaInsight(insight: Insight, modifier: Modifier = Modifier) {
    val tono = tonoSeveridad(insight.severidad)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tono.contenedor,
        contentColor = tono.sobreContenedor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                iconoSeveridad(insight.severidad),
                contentDescription = nombreSeveridad(insight.severidad),
                tint = tono.acento,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(insight.titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(insight.detalle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Fila de tarjeta "Análisis automático" en Inicio. */
@Composable
fun ResumenInsight(insight: Insight?, modifier: Modifier = Modifier) {
    if (insight == null) {
        Text(
            "Esperando datos para generar el análisis…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        TarjetaInsight(insight, modifier)
    }
}
