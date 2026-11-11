package com.fertitrack.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fertitrack.app.asistente.AccionAsistente
import com.fertitrack.app.asistente.AccionesAsistente
import com.fertitrack.app.asistente.AccionesAsistenteNulas
import com.fertitrack.app.asistente.Destino
import com.fertitrack.app.asistente.EstadoAsistente
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.ui.componentes.BarraParada
import com.fertitrack.app.ui.componentes.PanelAsistente
import com.fertitrack.app.ui.componentes.TAG_BOTON_ASISTENTE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.fertitrack.app.ui.componentes.ChipConexion
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.FranjaAviso
import com.fertitrack.app.ui.componentes.OverlayConfirmacion
import com.fertitrack.app.ui.pantallas.PantallaAnalisis
import com.fertitrack.app.ui.pantallas.PantallaConexion
import com.fertitrack.app.ui.pantallas.PantallaControl
import com.fertitrack.app.ui.pantallas.PantallaGraficas
import com.fertitrack.app.ui.pantallas.PantallaInicio
import com.fertitrack.app.ui.pantallas.PantallaZonas
import com.fertitrack.app.ui.tema.FertiTrackTema

/** Desde este ancho (tablet en horizontal) el asistente es un panel lateral. */
private val ANCHO_PANEL_LATERAL = 840.dp

const val TAG_FRANJA_DATOS = "franja_datos"
const val TAG_FRANJA_PERMISOS = "franja_permisos"
const val TAG_VOLVER = "boton_volver"
fun tagPestana(pestana: Pestana): String = "pestana_${pestana.name}"

/**
 * Raíz de la interfaz: barra superior con el chip de conexión, contenido de la pestaña,
 * barra DETENER siempre visible y navegación inferior. Sin estado propio salvo la pestaña
 * elegida y si se muestra Conexión: todo lo demás llega en [estado] y en [asistente].
 */
@Composable
fun FertiTrackPantalla(
    estado: UiState,
    acciones: AccionesUi,
    modifier: Modifier = Modifier,
    asistente: EstadoAsistente = EstadoAsistente(),
    accionesAsistente: AccionesAsistente = AccionesAsistenteNulas,
    eventosAsistente: Flow<AccionAsistente> = emptyFlow(),
) {
    FertiTrackPantalla(
        estado, acciones, modifier, pestanaInicial = Pestana.INICIO, conexionInicial = false,
        asistente = asistente, accionesAsistente = accionesAsistente, eventosAsistente = eventosAsistente,
    )
}

/** Variante para pruebas y capturas: permite abrir directamente una pestaña o Conexión. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FertiTrackPantalla(
    estado: UiState,
    acciones: AccionesUi,
    modifier: Modifier,
    pestanaInicial: Pestana,
    conexionInicial: Boolean,
    asistente: EstadoAsistente = EstadoAsistente(),
    accionesAsistente: AccionesAsistente = AccionesAsistenteNulas,
    eventosAsistente: Flow<AccionAsistente> = emptyFlow(),
) {
    var pestana by rememberSaveable { mutableStateOf(pestanaInicial) }
    var verConexion by rememberSaveable { mutableStateOf(conexionInicial) }
    val snackbar = remember { SnackbarHostState() }
    val estadosPestanas = rememberSaveableStateHolder()

    EfectoMensajes(estado.mensaje, snackbar, acciones::mensajeMostrado)

    // Un solo manejador de "atrás": la confirmación abierta, luego el asistente, luego Conexión.
    BackHandler(enabled = estado.confirmacion != null || asistente.abierto || verConexion) {
        when {
            estado.confirmacion != null -> acciones.cancelarConfirmacion()
            asistente.abierto -> accionesAsistente.cerrar()
            else -> verConexion = false
        }
    }

    // El asistente sabe en qué pantalla está la persona (sugerencias y contexto).
    LaunchedEffect(pestana, verConexion) {
        accionesAsistente.pantallaActual(if (verConexion) Destino.CONEXION else Destino.valueOf(pestana.name))
    }
    // Lo que pide una respuesta: ir a una pantalla o abrir la confirmación de una rutina.
    // La confirmación es la misma de los botones: nada se envía sin que la persona confirme.
    val accionesActuales by rememberUpdatedState(acciones)
    val asistenteActual by rememberUpdatedState(accionesAsistente)
    LaunchedEffect(eventosAsistente) {
        eventosAsistente.collect { accion ->
            when (accion) {
                is AccionAsistente.IrA -> if (accion.destino == Destino.CONEXION) {
                    verConexion = true
                } else {
                    pestana = Pestana.valueOf(accion.destino.name)
                    verConexion = false
                }
                is AccionAsistente.ProponerRutina -> {
                    pestana = Pestana.CONTROL
                    verConexion = false
                    asistenteActual.cerrar()
                    accionesActuales.pedirConfirmacion(AccionConfirmable.Iniciar(accion.rutina.modo))
                }
            }
        }
    }

    val contexto = LocalContext.current
    val pedirMicrofono = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        accionesAsistente.permisoMicrofono(concedido)
        if (concedido) accionesAsistente.alternarMicrofono()
    }
    val onMicrofono = {
        val concedido = ContextCompat.checkSelfPermission(contexto, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (concedido || asistente.escuchando) accionesAsistente.alternarMicrofono() else pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO)
    }

    val tecladoVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        // El teclado empuja la barra DETENER hacia arriba: nunca queda tapada.
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            BarraSuperior(
                estado = estado,
                verConexion = verConexion,
                onAbrirConexion = { verConexion = true },
                onVolver = { verConexion = false },
                onAsistente = accionesAsistente::abrir,
            )
        },
        bottomBar = {
            BarraInferior(
                estado = estado,
                pestana = pestana,
                mostrarNavegacion = !verConexion && !tecladoVisible,
                onDetener = acciones::detenerEmergencia,
                onPestana = {
                    pestana = it
                    verConexion = false
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { datos -> SnackbarFertiTrack(datos.visuals, onCerrar = datos::dismiss) }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { relleno ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(relleno)
                .consumeWindowInsets(relleno),
        ) {
            val anchoContenido = maxWidth
            val altoContenido = maxHeight
            if (verConexion) {
                PantallaConexion(estado, acciones)
            } else {
                estadosPestanas.SaveableStateProvider(pestana.name) {
                    when (pestana) {
                        Pestana.INICIO -> PantallaInicio(estado, acciones, irAPestana = { pestana = it })
                        Pestana.ZONAS -> PantallaZonas(estado, acciones)
                        Pestana.GRAFICAS -> PantallaGraficas(estado, acciones)
                        Pestana.CONTROL -> PantallaControl(estado, acciones)
                        Pestana.ANALISIS -> PantallaAnalisis(estado, acciones)
                    }
                }
            }
            // El asistente también va dentro del contenido: la barra DETENER sigue debajo, visible.
            AnimatedVisibility(
                visible = asistente.abierto,
                enter = slideInVertically { it / 3 } + fadeIn(),
                exit = slideOutVertically { it / 3 } + fadeOut(),
                modifier = Modifier.align(if (anchoContenido >= ANCHO_PANEL_LATERAL) Alignment.CenterEnd else Alignment.BottomCenter),
            ) {
                val ancho = if (anchoContenido >= ANCHO_PANEL_LATERAL) Modifier.width(420.dp) else Modifier.fillMaxWidth()
                // Con el teclado abierto el contenido se achica: entonces el panel lo ocupa todo.
                val alto = if (anchoContenido >= ANCHO_PANEL_LATERAL || tecladoVisible) altoContenido else altoContenido * 0.62f
                PanelAsistente(
                    estado = asistente,
                    acciones = accionesAsistente,
                    onMicrofono = onMicrofono,
                    modifier = ancho.height(alto).padding(8.dp),
                )
            }
            // Dentro del contenido: las barras superior e inferior siguen visibles y pulsables.
            OverlayConfirmacion(
                confirmacion = estado.confirmacion,
                onConfirmar = acciones::confirmar,
                onCancelar = acciones::cancelarConfirmacion,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarraSuperior(
    estado: UiState,
    verConexion: Boolean,
    onAbrirConexion: () -> Unit,
    onVolver: () -> Unit,
    onAsistente: () -> Unit,
) {
    val colores = FertiTrackTema.colores
    Column {
        TopAppBar(
            title = {
                Text(
                    if (verConexion) "Conexión" else "FertiTrack 🌿",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                if (verConexion) {
                    IconButton(onClick = onVolver, modifier = Modifier.testTag(TAG_VOLVER)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                    }
                }
            },
            actions = {
                IconButton(onClick = onAsistente, modifier = Modifier.testTag(TAG_BOTON_ASISTENTE)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = "Asistente", tint = MaterialTheme.colorScheme.primary)
                }
                if (!verConexion) {
                    ChipConexion(
                        conexion = estado.conexion,
                        usandoSimulador = estado.usandoSimulador,
                        onClick = onAbrirConexion,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
        )
        val robot = estado.robot
        when {
            robot == null -> FranjaAviso(
                texto = "Sin datos del robot todavía",
                icono = Icons.Rounded.LinkOff,
                tono = colores.neutro,
                modifier = Modifier.testTag(TAG_FRANJA_DATOS),
            )
            estado.datosObsoletos -> FranjaAviso(
                texto = estado.antiguedadDatosSeg
                    ?.let { "Datos de hace ${Formatos.antiguedad(it)}: no se están actualizando" }
                    ?: "Datos sin actualizar",
                icono = Icons.Rounded.History,
                tono = colores.aviso,
                modifier = Modifier.testTag(TAG_FRANJA_DATOS),
            )
        }
        if (estado.red is EstadoRed.PermisosFaltantes) {
            FranjaAviso(
                texto = "Faltan permisos: sin ellos el robot solo parecerá «sin respuesta».",
                icono = Icons.Rounded.Security,
                tono = colores.peligro,
                modifier = Modifier.testTag(TAG_FRANJA_PERMISOS),
                accion = if (verConexion) {
                    null
                } else {
                    { TextButton(onClick = onAbrirConexion) { Text("Revisar", color = colores.peligro.acento) } }
                },
            )
        }
    }
}

@Composable
private fun BarraInferior(
    estado: UiState,
    pestana: Pestana,
    mostrarNavegacion: Boolean,
    onDetener: () -> Unit,
    onPestana: (Pestana) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))) {
            BarraParada(enCurso = estado.paradaEnCurso, onDetener = onDetener)
            if (mostrarNavegacion) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    Pestana.entries.forEach { p ->
                        NavigationBarItem(
                            selected = p == pestana,
                            onClick = { onPestana(p) },
                            icon = { Icon(iconoPestana(p), contentDescription = null) },
                            label = { Text(p.titulo, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.testTag(tagPestana(p)),
                        )
                    }
                }
            } else {
                // Sin navegación (Conexión o teclado abierto): respeta la barra de gestos.
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

private fun iconoPestana(pestana: Pestana): ImageVector = when (pestana) {
    Pestana.INICIO -> Icons.Rounded.Home
    Pestana.ZONAS -> Icons.Rounded.GridView
    Pestana.GRAFICAS -> Icons.AutoMirrored.Rounded.ShowChart
    Pestana.CONTROL -> Icons.Rounded.Tune
    Pestana.ANALISIS -> Icons.Rounded.Insights
}

/** Muestra [mensaje] una sola vez y avisa al terminar para que el ViewModel lo descarte. */
@Composable
private fun EfectoMensajes(mensaje: MensajeUi?, host: SnackbarHostState, alMostrar: (Long) -> Unit) {
    val alMostrarActual by rememberUpdatedState(alMostrar)
    LaunchedEffect(mensaje?.id) {
        val actual = mensaje ?: return@LaunchedEffect
        host.showSnackbar(VisualesMensaje(actual))
        alMostrarActual(actual.id)
    }
}

private class VisualesMensaje(val mensaje: MensajeUi) : SnackbarVisuals {
    override val message: String get() = mensaje.texto
    override val actionLabel: String? get() = null
    override val withDismissAction: Boolean get() = mensaje.tipo == TipoMensaje.ERROR
    override val duration: SnackbarDuration
        get() = if (mensaje.tipo == TipoMensaje.ERROR) SnackbarDuration.Long else SnackbarDuration.Short
}

@Composable
private fun SnackbarFertiTrack(visuales: SnackbarVisuals, onCerrar: () -> Unit) {
    val tipo = (visuales as? VisualesMensaje)?.mensaje?.tipo ?: TipoMensaje.INFO
    val colores = FertiTrackTema.colores
    val (fondo, texto, icono) = when (tipo) {
        TipoMensaje.ERROR -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, Icons.Rounded.Error)
        TipoMensaje.AVISO -> Triple(colores.aviso.acento, colores.aviso.sobreAcento, Icons.Rounded.WarningAmber)
        TipoMensaje.EXITO -> Triple(colores.exito.acento, colores.exito.sobreAcento, Icons.Rounded.CheckCircle)
        TipoMensaje.INFO -> Triple(
            MaterialTheme.colorScheme.inverseSurface,
            MaterialTheme.colorScheme.inverseOnSurface,
            Icons.Rounded.Info,
        )
    }
    Snackbar(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = fondo,
        contentColor = texto,
        dismissAction = if (visuales.withDismissAction) {
            {
                IconButton(onClick = onCerrar, colors = IconButtonDefaults.iconButtonColors(contentColor = texto)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar mensaje")
                }
            }
        } else {
            null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Icon(icono, contentDescription = null, tint = texto, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(visuales.message, color = texto)
        }
    }
}
