package com.fertitrack.app.ui.pantallas

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace
import com.fertitrack.app.domain.traduccion.Traductor
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.FilaDato
import com.fertitrack.app.ui.componentes.Formatos
import com.fertitrack.app.ui.componentes.MargenPantalla
import com.fertitrack.app.ui.componentes.Nota
import com.fertitrack.app.ui.componentes.Punto
import com.fertitrack.app.ui.componentes.SeparacionTarjetas
import com.fertitrack.app.ui.componentes.TarjetaFertiTrack
import com.fertitrack.app.ui.componentes.rememberAhoraHasta
import com.fertitrack.app.ui.componentes.textoConexion
import com.fertitrack.app.ui.componentes.tonoConexion
import com.fertitrack.app.ui.tema.FertiTrackTema
import com.fertitrack.app.ui.tema.TonoSemantico

const val TAG_PANTALLA_CONEXION = "pantalla_conexion"
const val TAG_TARJETA_PERMISOS = "tarjeta_permisos"
const val TAG_BOTON_CONCEDER = "boton_conceder"
const val TAG_BOTON_AJUSTES_APP = "boton_ajustes_app"
const val TAG_BOTON_CONECTAR_WIFI = "boton_conectar_wifi"
const val TAG_BOTON_AJUSTES_WIFI = "boton_ajustes_wifi"
const val TAG_SECCION_DEBUG = "seccion_debug"
const val TAG_SWITCH_SIMULADOR = "switch_simulador"
const val TAG_SIMULAR_PERDIDA = "boton_simular_perdida"
fun tagSimularFalla(codigo: Int): String = "boton_simular_falla_$codigo"

/** Segundos de pérdida de conexión que simula el botón de la sección de depuración. */
private const val SEGUNDOS_PERDIDA_SIMULADA = 10

/**
 * Conexión (pantalla secundaria): salud del sondeo, red Wi-Fi, permisos, datos fijos del
 * robot, conexión automática o manual al AP y, en debug, el robot simulado.
 */
@Composable
fun PantallaConexion(estado: UiState, acciones: AccionesUi, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val accionesActuales by rememberUpdatedState(acciones)

    // Al volver de los ajustes (Wi-Fi o permisos de la app) se revisa todo otra vez.
    val lanzadorAjustes = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        accionesActuales.permisosActualizados()
    }
    val abrirAjustesWifi: () -> Unit = {
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        try {
            lanzadorAjustes.launch(panel)
        } catch (_: ActivityNotFoundException) {
            try {
                lanzadorAjustes.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Sin pantalla de ajustes de Wi-Fi: las instrucciones siguen a la vista.
            }
        }
    }
    val abrirAjustesApp: () -> Unit = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contexto.packageName, null))
        try {
            lanzadorAjustes.launch(intent)
        } catch (_: ActivityNotFoundException) {
            // Muy raro; el usuario puede abrir los ajustes de la app a mano.
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TAG_PANTALLA_CONEXION),
        contentPadding = PaddingValues(MargenPantalla),
        verticalArrangement = Arrangement.spacedBy(SeparacionTarjetas),
    ) {
        item("conexion") { TarjetaEstadoConexion(estado) }
        val red = estado.red
        if (red is EstadoRed.PermisosFaltantes) {
            item("permisos") {
                TarjetaPermisos(
                    permisos = red.permisos,
                    onConcedidos = acciones::permisosActualizados,
                    onAbrirAjustesApp = abrirAjustesApp,
                )
            }
        }
        item("red") { TarjetaRed(estado) }
        item("wifi") { TarjetaConectarWifi(estado, onConectar = acciones::conectarWifiRobot, onAbrirAjustes = abrirAjustesWifi) }
        item("vigilancia") {
            TarjetaVigilancia(activa = estado.vigilanciaActiva, onCambiar = acciones::activarVigilancia, onAbrirAjustesApp = abrirAjustesApp)
        }
        if (estado.simuladorDisponible) {
            item("debug") { TarjetaSimulador(estado, acciones) }
        }
    }
}

@Composable
private fun TarjetaEstadoConexion(estado: UiState) {
    val conexion = estado.conexion
    val ahora = rememberAhoraHasta((conexion as? EstadoConexion.Reintentando)?.proximoIntentoMs)
    val tono = tonoConexion(conexion, estado.usandoSimulador)
    val texto = textoConexion(conexion, estado.usandoSimulador, ahora)
    val intervalo = Formatos.decimal(RobotConfig.INTERVALO_SONDEO_MS / 1000f)
    TarjetaFertiTrack(titulo = "Estado de la conexión", icono = Icons.Rounded.SettingsInputAntenna) {
        Surface(shape = MaterialTheme.shapes.medium, color = tono.contenedor, contentColor = tono.sobreContenedor) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Punto(tono.acento, tamano = 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(texto, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (conexion) {
                            EstadoConexion.Pausado ->
                                "La consulta al robot está en pausa: la app está en segundo plano o aún no empezó."
                            EstadoConexion.Conectando -> "Esperando la primera respuesta del robot…"
                            EstadoConexion.Conectado ->
                                if (estado.usandoSimulador) {
                                    "El robot simulado responde. Se consulta cada $intervalo s."
                                } else {
                                    "El robot responde con normalidad. Se consulta cada $intervalo s."
                                }
                            is EstadoConexion.SinRespuesta ->
                                "El robot no respondió. Se vuelve a consultar al ritmo normal."
                            is EstadoConexion.Reintentando ->
                                "Varias consultas sin respuesta: se espera un poco más entre intentos."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        when (conexion) {
            is EstadoConexion.SinRespuesta -> {
                FilaDato("Fallos seguidos", conexion.fallosSeguidos.toString())
                FilaDato("Detalle", conexion.detalle.ifBlank { Formatos.SIN_DATO })
            }
            is EstadoConexion.Reintentando -> {
                FilaDato("Fallos seguidos", conexion.fallosSeguidos.toString())
                val segundos = Formatos.segundosHasta(conexion.proximoIntentoMs, ahora)
                FilaDato("Próximo intento", if (segundos > 0) "en $segundos s" else "ahora")
                FilaDato("Detalle", conexion.detalle.ifBlank { Formatos.SIN_DATO })
            }
            else -> Unit
        }
        FilaDato(
            "Última respuesta válida",
            estado.antiguedadDatosSeg?.let { "hace ${Formatos.antiguedad(it)}" } ?: "Nunca en esta sesión",
            colorValor = if (estado.datosObsoletos && estado.robot != null) {
                FertiTrackTema.colores.aviso.acento
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (estado.datosObsoletos && estado.robot != null) {
            Nota("Lo que ves en las pestañas es la última información recibida, no el estado en vivo.")
        }
    }
}

/** Texto y tono del estado de la red Wi-Fi del robot. */
@Composable
private fun descripcionRed(red: EstadoRed, ssid: String): Pair<String, TonoSemantico> {
    val c = FertiTrackTema.colores
    return when (red) {
        EstadoRed.Inactiva -> "Todavía no se buscó la red del robot." to c.neutro
        EstadoRed.Solicitando ->
            "Android está buscando el Wi-Fi del robot. Si aparece un aviso, elige $ssid y acepta." to c.aviso
        is EstadoRed.Disponible -> when (red.modo) {
            ModoEnlace.AUTOMATICO -> "Conectado al Wi-Fi del robot (lo pidió la app)." to c.exito
            ModoEnlace.MANUAL -> "Conectado al Wi-Fi del robot desde los ajustes." to c.exito
        }
        is EstadoRed.NoDisponible -> "No se pudo usar el Wi-Fi del robot: ${red.motivo}" to c.peligro
        is EstadoRed.PermisosFaltantes ->
            "Bloqueada hasta conceder los permisos de arriba." to c.peligro
        EstadoRed.Simulador -> "Usando el robot simulado: no se usa el Wi-Fi." to c.prueba
    }
}

@Composable
private fun TarjetaRed(estado: UiState) {
    val (texto, tono) = descripcionRed(estado.red, estado.ssidRobot)
    TarjetaFertiTrack(titulo = "Red del robot", icono = Icons.Rounded.Router) {
        Row(verticalAlignment = Alignment.Top) {
            Punto(tono.acento, modifier = Modifier.padding(top = 6.dp), tamano = 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        FilaDato("Red Wi-Fi (SSID)", estado.ssidRobot)
        FilaDato("Dirección del robot", estado.ipRobot)
        FilaDato(
            "Clave del Wi-Fi",
            if (estado.claveConfigurada) "Configurada en la app" else "No configurada",
        )
    }
}

private fun nombrePermiso(permiso: String): String = when (permiso) {
    "android.permission.NEARBY_WIFI_DEVICES" -> "Dispositivos Wi-Fi cercanos"
    "android.permission.ACCESS_FINE_LOCATION" -> "Ubicación precisa"
    "android.permission.ACCESS_COARSE_LOCATION" -> "Ubicación aproximada"
    "android.permission.ACCESS_LOCAL_NETWORK" -> "Red local"
    else -> permiso.substringAfterLast('.')
}

@Composable
private fun TarjetaPermisos(
    permisos: List<String>,
    onConcedidos: () -> Unit,
    onAbrirAjustesApp: () -> Unit,
) {
    val tono = FertiTrackTema.colores.peligro
    val actividad = LocalActivity.current
    val alTerminar by rememberUpdatedState(onConcedidos)
    var rechazos by rememberSaveable { mutableIntStateOf(0) }
    var bloqueados by rememberSaveable { mutableStateOf(false) }
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
        val negados = resultado.filterValues { !it }.keys
        if (negados.isNotEmpty()) {
            rechazos++
            // Sin "justificación" tras negarlo, Android ya no volverá a mostrar el diálogo.
            bloqueados = actividad != null &&
                negados.any { !ActivityCompat.shouldShowRequestPermissionRationale(actividad, it) }
        }
        alTerminar()
    }
    val ofrecerAjustes = rechazos >= 2 || (rechazos >= 1 && bloqueados)

    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_TARJETA_PERMISOS),
        titulo = "Permisos necesarios",
        icono = Icons.Rounded.Security,
        tono = tono,
    ) {
        Text(
            "Sin estos permisos Android bloquea la comunicación con el robot y la app solo verá " +
                "«sin respuesta».",
            style = MaterialTheme.typography.bodyMedium,
        )
        permisos.forEach { permiso ->
            Surface(shape = MaterialTheme.shapes.small, color = tono.acento.copy(alpha = 0.08f)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(nombrePermiso(permiso), style = MaterialTheme.typography.titleSmall)
                    Text(Traductor.explicarPermiso(permiso), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(
            onClick = { lanzador.launch(permisos.toTypedArray()) },
            enabled = permisos.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = tono.acento, contentColor = tono.sobreAcento),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_BOTON_CONCEDER),
        ) {
            Icon(Icons.Rounded.Security, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Conceder")
        }
        if (ofrecerAjustes) {
            Text(
                "Android ya no muestra el aviso de permisos. Actívalos desde los ajustes de la app " +
                    "(Permisos) y vuelve aquí.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = onAbrirAjustesApp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_BOTON_AJUSTES_APP),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Abrir ajustes de la app")
            }
        }
    }
}

@Composable
private fun TarjetaConectarWifi(estado: UiState, onConectar: () -> Unit, onAbrirAjustes: () -> Unit) {
    val soportaAutomatico = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val buscando = estado.red is EstadoRed.Solicitando
    val habilitado = soportaAutomatico && estado.claveConfigurada && !buscando && !estado.usandoSimulador
    TarjetaFertiTrack(titulo = "Conectarse al robot", icono = Icons.Rounded.Wifi) {
        Button(
            onClick = onConectar,
            enabled = habilitado,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_BOTON_CONECTAR_WIFI),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (buscando) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Rounded.WifiFind, contentDescription = null)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(if (buscando) "Buscando el Wi-Fi del robot…" else "Conectar al Wi-Fi del robot")
        }
        when {
            !soportaAutomatico -> Nota("La conexión automática requiere Android 10 o superior: conéctate a mano.")
            !estado.claveConfigurada ->
                Nota("Esta versión de la app no tiene la clave del Wi-Fi del robot: conéctate a mano.")
            estado.usandoSimulador -> Nota("Con el robot simulado no hace falta el Wi-Fi.")
            else -> Nota("Android te pedirá permiso para unirte a ${estado.ssidRobot} la primera vez.")
        }
        OutlinedButton(
            onClick = onAbrirAjustes,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TAG_BOTON_AJUSTES_WIFI),
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Abrir ajustes de Wi-Fi")
        }
        Text("Conexión manual", style = MaterialTheme.typography.titleSmall)
        PasoInstruccion(1, "Abre los ajustes de Wi-Fi y elige la red ${estado.ssidRobot}.")
        PasoInstruccion(2, "Escribe la clave del robot si te la pide.")
        PasoInstruccion(3, "Si Android avisa que la red no tiene internet, elige mantener la conexión.")
        PasoInstruccion(4, "Vuelve a FertiTrack: la app usará esa red para hablar con el robot.")
    }
}

@Composable
private fun PasoInstruccion(numero: Int, texto: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(numero.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 2.dp))
    }
}

@Composable
private fun TarjetaSimulador(estado: UiState, acciones: AccionesUi) {
    val tono = FertiTrackTema.colores.prueba
    val activo = estado.usandoSimulador
    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_SECCION_DEBUG),
        titulo = "Solo debug",
        icono = Icons.Rounded.BugReport,
        tono = tono,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Usar robot simulado", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Prueba la app sin el robot real. No se envía nada al invernadero.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = activo,
                onCheckedChange = acciones::usarSimulador,
                modifier = Modifier
                    .testTag(TAG_SWITCH_SIMULADOR)
                    .semantics {
                        contentDescription = "Usar robot simulado"
                        stateDescription = if (activo) "Activado" else "Desactivado"
                    },
            )
        }
        Text("Simular", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSimulacion("Falla ESP_B (2)", activo, tagSimularFalla(2), Modifier.weight(1f)) { acciones.simularFalla(2) }
            BotonSimulacion("Sensor sin lectura (3)", activo, tagSimularFalla(3), Modifier.weight(1f)) {
                acciones.simularFalla(3)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSimulacion("Botón físico (9)", activo, tagSimularFalla(9), Modifier.weight(1f)) { acciones.simularFalla(9) }
            BotonSimulacion(
                "Perder conexión $SEGUNDOS_PERDIDA_SIMULADA s",
                activo,
                TAG_SIMULAR_PERDIDA,
                Modifier.weight(1f),
            ) { acciones.simularPerdidaConexion(SEGUNDOS_PERDIDA_SIMULADA) }
        }
        if (!activo) Nota("Activa el robot simulado para usar estos botones.")
    }
}

@Composable
private fun BotonSimulacion(
    texto: String,
    habilitado: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = 52.dp).testTag(tag),
    ) {
        Text(texto, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

const val TAG_TARJETA_VIGILANCIA = "tarjeta_vigilancia"
const val TAG_SWITCH_VIGILANCIA = "switch_vigilancia"

private fun permisoNotificaciones(contexto: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Vigilancia en segundo plano: apagada por defecto. Al encenderla en Android 13+ se pide antes
 * el permiso de notificaciones; si se niega, la vigilancia funciona igual (guarda el historial)
 * pero sin avisos.
 */
@Composable
private fun TarjetaVigilancia(activa: Boolean, onCambiar: (Boolean) -> Unit, onAbrirAjustesApp: () -> Unit) {
    val contexto = LocalContext.current
    var conPermiso by remember { mutableStateOf(permisoNotificaciones(contexto)) }
    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        conPermiso = concedido
        onCambiar(true)
    }
    val tono = FertiTrackTema.colores.aviso
    TarjetaFertiTrack(
        modifier = Modifier.testTag(TAG_TARJETA_VIGILANCIA),
        titulo = "Avisos en segundo plano",
        icono = Icons.Rounded.NotificationsActive,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Vigilar el robot con la app cerrada", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Avisa de fallas, fin de riego, zonas secas y cortes de conexión, y sigue guardando el historial.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = activa,
                onCheckedChange = { encender ->
                    if (encender && !conPermiso && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onCambiar(encender)
                    }
                },
                modifier = Modifier
                    .testTag(TAG_SWITCH_VIGILANCIA)
                    .semantics { contentDescription = "Vigilar el robot con la app cerrada" },
            )
        }
        Nota(
            "Mientras vigila, la app consulta al robot cada 1,5 s aunque la pantalla esté apagada y deja " +
                "una notificación fija: gasta más batería. Apágala cuando no la necesites.",
        )
        if (activa && !conPermiso) {
            Text(
                "Sin permiso de notificaciones: el historial se sigue guardando, pero no verás los avisos.",
                style = MaterialTheme.typography.bodyMedium,
                color = tono.acento,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = onAbrirAjustesApp, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Abrir ajustes de la app")
            }
        }
    }
}
