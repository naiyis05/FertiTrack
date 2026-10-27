package com.fertitrack.app.ui.pantallas

import com.fertitrack.app.domain.analisis.Insight
import com.fertitrack.app.domain.analisis.Severidad
import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.CalibracionSensor
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.TipoFalla
import com.fertitrack.app.domain.sesion.EventoSesion
import com.fertitrack.app.domain.sesion.MuestraHumedad
import com.fertitrack.app.domain.sesion.ResumenSesion
import com.fertitrack.app.domain.sesion.RiegoRegistrado
import com.fertitrack.app.domain.sesion.TipoEvento
import com.fertitrack.app.domain.traduccion.CategoriaEstado
import com.fertitrack.app.domain.traduccion.DescripcionEstado
import com.fertitrack.app.domain.traduccion.DescripcionFalla
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.Confirmacion
import com.fertitrack.app.ui.MensajeUi
import com.fertitrack.app.ui.TipoMensaje
import com.fertitrack.app.ui.UiState
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Estados de muestra construidos a mano (sin ViewModel ni dominio: en este worktree son stubs).
 * Los textos imitan lo que producirá el Traductor.
 */
object EstadosMuestra {
    /** 10:30 del día de la prueba, en la zona del sistema: las horas salen legibles en las capturas. */
    private val BASE_MS: Long =
        LocalDateTime.of(2026, 10, 27, 10, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun min(m: Int, s: Int = 0): Long = BASE_MS + m * 60_000L + s * 1_000L

    val calibracion = CalibracionSensor(
        adcSeco = 3200,
        adcMojado = 1400,
        pctSeca = 30,
        pctHumeda = 60,
        pctSobre = 85,
        umbralSeco = 2660,
        umbralHumedo = 2120,
        umbralSobre = 1670,
    )

    private val zonasSeis = listOf(
        NivelSuelo.SECA, NivelSuelo.INTERMEDIA, NivelSuelo.HUMEDA,
        NivelSuelo.SECA, NivelSuelo.SOBRE_HUMEDA, NivelSuelo.INTERMEDIA,
    )

    val robotEspera = EstadoRobot(
        estado = EstadoMaquina.ESPERANDO,
        estadoTexto = "ESPERANDO",
        modoRutina = ModoRutina.NINGUNA,
        zonaActual = 0,
        totalZonas = 6,
        distanciaCm = 300f,
        anchoZonaCm = 50f,
        pasosPorCm = 80f,
        posicionPasos = 0,
        humedadAdc = 2354,
        humedadPct = 47f,
        calibracion = calibracion,
        nivelSuelo = NivelSuelo.INTERMEDIA,
        actividad = Actividad.REPOSO,
        falla = TipoFalla.SIN_FALLA,
        codigoFalla = 0,
        espBConectada = true,
        espCConectada = true,
        autoActivo = true,
        descansoSeg = 1800,
        proximoCicloSeg = 754,
        zonas = zonasSeis,
    )

    val robotRiego = robotEspera.copy(
        estado = EstadoMaquina.RIEGO_AVANZANDO,
        estadoTexto = "RIEGO_AVANZANDO",
        modoRutina = ModoRutina.RIEGO,
        zonaActual = 4,
        posicionPasos = 14_000,
        actividad = Actividad.REGANDO,
        nivelSuelo = NivelSuelo.SECA,
        humedadAdc = 2810,
        humedadPct = 21.7f,
        proximoCicloSeg = null,
    )

    val robotEmergencia = robotEspera.copy(
        estado = EstadoMaquina.EMERGENCIA,
        estadoTexto = "EMERGENCIA",
        modoRutina = ModoRutina.RIEGO,
        zonaActual = 3,
        posicionPasos = 11_000,
        falla = TipoFalla.PARADA_DESDE_APP,
        codigoFalla = 8,
        autoActivo = false,
        proximoCicloSeg = null,
    )

    val robotSinRecorrido = robotEspera.copy(
        totalZonas = 0,
        distanciaCm = 0f,
        anchoZonaCm = 0f,
        zonas = emptyList(),
        humedadAdc = null,
        humedadPct = null,
        nivelSuelo = NivelSuelo.SIN_MEDIR,
        autoActivo = false,
        proximoCicloSeg = null,
    )

    /** 150 zonas (máximo del firmware) con niveles variados. */
    val robot150 = robotRiego.copy(
        totalZonas = 150,
        distanciaCm = 1500f,
        anchoZonaCm = 10f,
        zonaActual = 87,
        posicionPasos = 69_200,
        zonas = List(150) { i ->
            when {
                i >= 120 -> NivelSuelo.SIN_MEDIR
                i % 7 == 0 -> NivelSuelo.SECA
                i % 5 == 0 -> NivelSuelo.SOBRE_HUMEDA
                i % 3 == 0 -> NivelSuelo.HUMEDA
                else -> NivelSuelo.INTERMEDIA
            }
        },
    )

    private val nivelesMuestra = listOf(22f, 41f, 66f, 18f, 88f, 52f)

    private fun nivelDe(pct: Float): NivelSuelo = when {
        pct < 30f -> NivelSuelo.SECA
        pct < 60f -> NivelSuelo.INTERMEDIA
        pct < 85f -> NivelSuelo.HUMEDA
        else -> NivelSuelo.SOBRE_HUMEDA
    }

    val muestras: List<MuestraHumedad> = (0 until 12).map { i ->
        val zona = i % 6 + 1
        val pct = nivelesMuestra[i % 6] + if (i >= 6) 3f else 0f
        MuestraHumedad(
            momentoMs = min(i * 2),
            zona = zona,
            humedadPct = pct,
            adc = (3200 - pct * 18).toInt(),
            nivel = nivelDe(pct),
        )
    }

    val eventos: List<EventoSesion> = listOf(
        EventoSesion(min(26), TipoEvento.RIEGO, "Riego iniciado en las zonas 1 y 4"),
        EventoSesion(min(24), TipoEvento.MEDICION, "Zona 6: 55 % (Intermedia)"),
        EventoSesion(min(22), TipoEvento.MEDICION, "Zona 5: 91 % (Sobre-húmeda)"),
        EventoSesion(min(14), TipoEvento.RUTINA, "Ciclo completo iniciado"),
        EventoSesion(min(13), TipoEvento.COMANDO, "Orden enviada: iniciar ciclo"),
        EventoSesion(min(9), TipoEvento.FALLA, "Parada de emergencia desde la app"),
        EventoSesion(min(5), TipoEvento.FERTILIZACION, "Fertilización completada"),
        EventoSesion(min(1), TipoEvento.AVISO, "Datos sin actualizar durante 12 s"),
        EventoSesion(min(0), TipoEvento.CONEXION, "Conectado al robot"),
    )

    val riegos: List<RiegoRegistrado> = listOf(
        RiegoRegistrado(min(2), min(8, 40), listOf(1, 4), completado = true),
        RiegoRegistrado(min(9), min(9, 35), listOf(2), completado = false),
    )

    val resumenCompleto = ResumenSesion(
        eventos = eventos,
        muestras = muestras,
        riegos = riegos,
        riegosCompletados = 1,
        totalMediciones = 12,
        humedadPromedio = 49.2f,
        sugerirFertilizar = false,
    )

    val insights: List<Insight> = listOf(
        Insight("2 zonas secas", "Las zonas 1 y 4 están por debajo del 30 %: el próximo ciclo las regará.", Severidad.AVISO),
        Insight("Zona 5 sobre-húmeda", "Lleva 2 mediciones por encima del 85 %. Revisa el drenaje.", Severidad.INFO),
        Insight("Conexión estable", "El robot respondió todas las consultas de la última hora.", Severidad.EXITO),
    )

    private val descripcionEspera = DescripcionEstado("En espera", "Listo para iniciar una rutina", CategoriaEstado.REPOSO)

    // 1. Sin datos: la app arranca y aún no recibió nada.
    val sinDatos = UiState(
        conexion = EstadoConexion.Conectando,
        red = EstadoRed.Solicitando,
    )

    // 2. Conectado y en espera, con datos de sesión.
    val conectadoEspera = UiState(
        conexion = EstadoConexion.Conectado,
        red = EstadoRed.Disponible(ModoEnlace.AUTOMATICO),
        robot = robotEspera,
        datosObsoletos = false,
        antiguedadDatosSeg = 1,
        descripcionEstado = descripcionEspera,
        resumen = resumenCompleto,
        insights = insights,
        rutinasHabilitadas = true,
        motivoRutinasDeshabilitadas = null,
        puedeActivarAuto = true,
        puedeDesactivarAuto = true,
        puedeGuardarDescanso = true,
        claveConfigurada = true,
    )

    const val MOTIVO_OCUPADO = "El robot está regando: espera a que termine la rutina"

    // 3. Riego en curso.
    val riegoEnCurso = conectadoEspera.copy(
        robot = robotRiego,
        descripcionEstado = DescripcionEstado("Regando zona 4 de 6", "Recorriendo zonas de riego", CategoriaEstado.RIEGO),
        rutinasHabilitadas = false,
        motivoRutinasDeshabilitadas = MOTIVO_OCUPADO,
        puedeActivarAuto = false,
    )

    // 4. Emergencia por parada desde la app (falla 8).
    val emergencia = conectadoEspera.copy(
        robot = robotEmergencia,
        descripcionEstado = DescripcionEstado(
            "Emergencia",
            "Bombas apagadas y robot detenido",
            CategoriaEstado.EMERGENCIA,
        ),
        falla = DescripcionFalla(
            codigo = 8,
            titulo = "Parada desde la app",
            explicacion = "Se pulsó DETENER en la app: el robot apagó las bombas y detuvo el movimiento.",
            accionSugerida = "Revisa que no haya obstáculos y pulsa «Reiniciar» para recoger los brazos y volver al origen.",
        ),
        rutinasHabilitadas = false,
        motivoRutinasDeshabilitadas = "Hay una emergencia activa: reinicia el sistema primero",
        reiniciarHabilitado = true,
        puedeActivarAuto = false,
    )

    // 5. Datos obsoletos: se perdió la conexión tras recibir datos.
    fun datosObsoletos(): UiState = conectadoEspera.copy(
        conexion = EstadoConexion.Reintentando(
            fallosSeguidos = 4,
            proximoIntentoMs = System.currentTimeMillis() + 6_000,
            detalle = "Tiempo de espera agotado (3 s)",
        ),
        datosObsoletos = true,
        antiguedadDatosSeg = 42,
        rutinasHabilitadas = false,
        motivoRutinasDeshabilitadas = "Sin conexión con el robot",
        puedeActivarAuto = false,
        puedeDesactivarAuto = false,
        puedeGuardarDescanso = false,
    )

    // 6. Permisos faltantes (Android 17: red local).
    val permisosFaltantes = UiState(
        conexion = EstadoConexion.SinRespuesta(fallosSeguidos = 2, detalle = "Tiempo de espera agotado (3 s)"),
        red = EstadoRed.PermisosFaltantes(
            listOf("android.permission.ACCESS_LOCAL_NETWORK", "android.permission.NEARBY_WIFI_DEVICES"),
        ),
        simuladorDisponible = true,
        claveConfigurada = true,
    )

    // 7. Simulador en uso (solo debug).
    val simulador = conectadoEspera.copy(
        red = EstadoRed.Simulador,
        simuladorDisponible = true,
        usandoSimulador = true,
    )

    val todos: List<Pair<String, () -> UiState>> = listOf(
        "sin_datos" to { sinDatos },
        "conectado_espera" to { conectadoEspera },
        "riego_en_curso" to { riegoEnCurso },
        "emergencia_falla8" to { emergencia },
        "datos_obsoletos" to { datosObsoletos() },
        "permisos_faltantes" to { permisosFaltantes },
        "simulador" to { simulador },
    )

    val sugerirFertilizar = conectadoEspera.copy(
        resumen = resumenCompleto.copy(sugerirFertilizar = true),
    )

    val confirmacionCiclo = Confirmacion(
        accion = AccionConfirmable.Iniciar(ModoInicio.CICLO),
        titulo = "¿Iniciar ciclo completo?",
        mensaje = "El robot recorrerá las 6 zonas midiendo la humedad y regará las que estén secas. " +
            "Las bombas se encenderán.",
        textoConfirmar = "Sí, iniciar",
        peligrosa = true,
    )

    val conConfirmacion = conectadoEspera.copy(confirmacion = confirmacionCiclo)

    val sinRecorrido = conectadoEspera.copy(
        robot = robotSinRecorrido,
        rutinasHabilitadas = false,
        motivoRutinasDeshabilitadas = "Configura primero la distancia del riel",
        resumen = ResumenSesion(),
        insights = emptyList(),
    )

    val zonas150 = riegoEnCurso.copy(robot = robot150)

    val conMensajeError = conectadoEspera.copy(
        mensaje = MensajeUi(7, "El robot no respondió a la orden. Revisa la conexión.", TipoMensaje.ERROR),
    )
}
