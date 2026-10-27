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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

const val TAG_PANTALLA_CONTROL = "pantalla_control"
const val TAG_AVISO_DISPONIBILIDAD = "aviso_disponibilidad"
const val TAG_SWITCH_AUTO = "switch_auto"
const val TAG_CAMPO_DESCANSO = "campo_descanso"
const val TAG_GUARDAR_DESCANSO = "boton_guardar_descanso"
const val TAG_REINICIAR_SISTEMA = "boton_reiniciar_sistema"
fun tagRutina(modo: ModoInicio): String = "boton_rutina_${modo.name}"

/** Límites del descanso entre ciclos automáticos (el firmware solo exige > 0). */
const val DESCANSO_MIN_SEG = 1L
const val DESCANSO_MAX_SEG = 86_400L

/** Valida el texto del campo de descanso; devuelve los segundos o null si no es válido. */
fun descansoValido(texto: String): Long? =
    texto.trim().toLongOrNull()?.takeIf { it in DESCANSO_MIN_SEG..DESCANSO_MAX_SEG }

/** Control: rutinas, riego automático, emergencia y nota de la Fase 2. */
@Composable
fun PantallaControl(estado: UiState, acciones: AccionesUi, modifier: Modifier = Modifier) {
    val colores = FertiTrackTema.colores
    val rutinasActivas = estado.rutinasHabilitadas && estado.comandoEnCurso == null
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_CONTROL),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("disponibilidad") { AvisoDisponibilidad(estado) }
        item("rutinas") {
            TarjetaFertiTrack(titulo = "Rutinas", icono = Icons.Rounded.Autorenew) {
                BotonRutina(
                    titulo = "Ciclo completo",
                    detalle = "Sensa cada zona y riega las secas",
                    icono = Icons.Rounded.WaterDrop,
                    tono = colores.agua,
                    habilitado = rutinasActivas,
                    modo = ModoInicio.CICLO,
                    onClick = { acciones.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.CICLO)) },
                )
                BotonRutina(
                    titulo = "Solo sensar",
                    detalle = "Mide la humedad de cada zona, sin regar",
                    icono = Icons.Rounded.Sensors,
                    tono = colores.sensado,
                    habilitado = rutinasActivas,
                    modo = ModoInicio.SENSADO,
                    onClick = { acciones.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.SENSADO)) },
                )
                BotonRutina(
                    titulo = "Fertilizar",
                    detalle = "Recorre todo el riel aplicando fertilizante",
                    icono = Icons.Rounded.Science,
                    tono = colores.fertilizante,
                    habilitado = rutinasActivas,
                    modo = ModoInicio.FERTILIZAR,
                    onClick = { acciones.pedirConfirmacion(AccionConfirmable.Iniciar(ModoInicio.FERTILIZAR)) },
                )
                Nota("Cada rutina pide confirmación antes de mover el robot.")
            }
        }
        item("auto") { TarjetaRiegoAutomatico(estado, acciones) }
        item("emergencia") {
            TarjetaFertiTrack(titulo = "Emergencia", icono = Icons.Rounded.PanTool, tono = colores.peligro) {
                Text(
                    "Para detener el robot en cualquier momento usa DETENER, siempre visible abajo: " +
                        "apaga las bombas y para el movimiento al instante.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "«Reiniciar sistema» solo tiene efecto después de una emergencia: recoge los brazos, " +
                        "vuelve al origen y apaga el riego automático.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { acciones.pedirConfirmacion(AccionConfirmable.Reiniciar) },
                    enabled = estado.reiniciarHabilitado,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colores.peligro.acento,
                        contentColor = colores.peligro.sobreAcento,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_REINICIAR_SISTEMA),
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reiniciar sistema…")
                }
                if (!estado.reiniciarHabilitado) {
                    Nota("Disponible solo cuando el robot está en emergencia.", color = colores.peligro.sobreContenedor)
                }
            }
        }
        item("fase2") {
            TarjetaFertiTrack(titulo = "Configuración y calibración", icono = Icons.Rounded.Construction, tono = colores.neutro) {
                Text(
                    "La configuración del recorrido y la calibración de distancia y del sensor llegarán " +
                        "a la app en la Fase 2. Mientras tanto, usa la página web del robot: " +
                        "http://${estado.ipRobot}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AvisoDisponibilidad(estado: UiState) {
    val colores = FertiTrackTema.colores
    val enCurso = estado.comandoEnCurso
    val (tono, icono, texto) = when {
        enCurso != null -> Triple(colores.agua, Icons.Rounded.HourglassTop, "Enviando: $enCurso…")
        estado.rutinasHabilitadas -> Triple(colores.exito, Icons.Rounded.CheckCircle, "Listo para recibir órdenes")
        else -> Triple(
            colores.aviso,
            Icons.Rounded.Block,
            estado.motivoRutinasDeshabilitadas ?: "Las rutinas no están disponibles ahora",
        )
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = tono.contenedor,
        contentColor = tono.sobreContenedor,
        modifier = Modifier.fillMaxWidth().testTag(TAG_AVISO_DISPONIBILIDAD),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icono, contentDescription = null, tint = tono.acento, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(texto, style = MaterialTheme.typography.titleMedium)
                if (enCurso == null && estado.rutinasHabilitadas) {
                    Text("El robot está en espera y sin fallas.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BotonRutina(
    titulo: String,
    detalle: String,
    icono: ImageVector,
    tono: TonoSemantico,
    habilitado: Boolean,
    modo: ModoInicio,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = tono.acento, contentColor = tono.sobreAcento),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(tagRutina(modo)),
    ) {
        Icon(icono, contentDescription = null, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detalle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TarjetaRiegoAutomatico(estado: UiState, acciones: AccionesUi) {
    val colores = FertiTrackTema.colores
    val robot = estado.robot
    val autoActivo = robot?.autoActivo == true
    val descansoRobot = robot?.descansoSeg

    // Texto del campo: estado local permitido. Mientras no se edita, refleja el valor del robot.
    var texto by rememberSaveable { mutableStateOf(descansoRobot?.toString().orEmpty()) }
    var editado by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(descansoRobot) {
        if (descansoRobot != null && (!editado || descansoValido(texto) == descansoRobot)) {
            texto = descansoRobot.toString()
            editado = false
        }
    }
    val segundos = descansoValido(texto)
    val focus = LocalFocusManager.current

    TarjetaFertiTrack(titulo = "Riego automático", icono = Icons.Rounded.Schedule) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (autoActivo) "Activado" else "Desactivado",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (autoActivo) colores.exito.acento else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Repite el ciclo completo (sensar + regar) dejando un descanso entre ciclos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            val habilitado = if (autoActivo) estado.puedeDesactivarAuto else estado.puedeActivarAuto && segundos != null
            Switch(
                checked = autoActivo,
                enabled = habilitado,
                onCheckedChange = { activar ->
                    if (activar) {
                        val descanso = segundos ?: descansoRobot
                        if (estado.puedeActivarAuto && descanso != null) {
                            acciones.pedirConfirmacion(AccionConfirmable.ActivarAuto(descanso))
                        }
                    } else if (estado.puedeDesactivarAuto) {
                        acciones.desactivarAuto()
                    }
                },
                modifier = Modifier
                    .testTag(TAG_SWITCH_AUTO)
                    .semantics {
                        contentDescription = "Riego automático"
                        stateDescription = if (autoActivo) "Activado" else "Desactivado"
                    },
            )
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = texto,
                onValueChange = { nuevo ->
                    texto = nuevo.filter { it.isDigit() }.take(6)
                    editado = true
                },
                label = { Text("Descanso entre ciclos") },
                suffix = { Text("s") },
                singleLine = true,
                isError = texto.isNotEmpty() && segundos == null,
                supportingText = {
                    Text(
                        when {
                            texto.isEmpty() -> "Entre 1 y 86 400 segundos"
                            segundos == null -> "Escribe un número entre 1 y 86 400"
                            else -> "= ${Formatos.antiguedad(segundos)}"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).testTag(TAG_CAMPO_DESCANSO),
            )
            Button(
                onClick = {
                    segundos?.let(acciones::guardarDescanso)
                    focus.clearFocus()
                },
                enabled = estado.puedeGuardarDescanso && segundos != null,
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 52.dp).testTag(TAG_GUARDAR_DESCANSO),
            ) { Text("Guardar") }
        }
        val proximo = robot?.proximoCicloSeg
        when {
            // Con datos viejos la cuenta atrás estaría congelada y parecería en vivo.
            estado.datosObsoletos && robot != null ->
                Nota("Sin datos actuales del robot: la cuenta atrás vuelve al reconectar.")
            proximo != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = colores.agua.acento)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Próximo ciclo en ${Formatos.cuentaAtras(proximo)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colores.agua.acento,
                )
            }
            autoActivo -> Nota("El próximo ciclo se programa cuando el robot vuelve a quedar en espera.")
            else -> Nota("Al activarlo, el robot iniciará solo un ciclo completo al terminar cada descanso.")
        }
    }
}
