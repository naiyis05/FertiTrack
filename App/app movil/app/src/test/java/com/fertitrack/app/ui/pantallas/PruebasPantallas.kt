package com.fertitrack.app.ui.pantallas

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.FertiTrackPantalla
import com.fertitrack.app.ui.Pestana
import com.fertitrack.app.ui.TAG_FRANJA_DATOS
import com.fertitrack.app.ui.TAG_FRANJA_PERMISOS
import com.fertitrack.app.ui.TAG_VOLVER
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.componentes.TAG_BOTON_CANCELAR
import com.fertitrack.app.ui.componentes.TAG_BOTON_CONFIRMAR
import com.fertitrack.app.ui.componentes.TAG_BOTON_DETENER
import com.fertitrack.app.ui.componentes.TAG_CHIP_CONEXION
import com.fertitrack.app.ui.componentes.FaseDia
import com.fertitrack.app.ui.componentes.LocalHoraCielo
import com.fertitrack.app.ui.componentes.TAG_AVISO_RIEGO
import com.fertitrack.app.ui.componentes.TAG_BOTON_REGAR
import com.fertitrack.app.ui.componentes.TAG_OVERLAY_CONFIRMACION
import com.fertitrack.app.ui.componentes.TAG_VOLVER_HORA_REAL
import com.fertitrack.app.ui.componentes.tagFaseCielo
import com.fertitrack.app.ui.tagPestana
import com.fertitrack.app.ui.tema.FertiTrackTema
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reglas de seguridad y dibujo de todas las pantallas (Robolectric, sin emulador). */
@RunWith(RobolectricTestRunner::class)
// Application simple: FertiTrackApp crearía el contenedor real (fuera del alcance de estas pruebas).
@Config(qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class PruebasPantallas {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val acciones = DobleAcciones()

    private fun mostrar(
        estado: UiState,
        pestana: Pestana = Pestana.INICIO,
        conexion: Boolean = false,
        oscuro: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalHoraCielo provides HORA_PRUEBA) {
                FertiTrackTema(oscuro = oscuro) {
                    FertiTrackPantalla(estado, acciones, Modifier, pestanaInicial = pestana, conexionInicial = conexion)
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        /** El cielo de Zonas siempre a la misma hora: martes 27 de octubre, 9:41 (mañana). */
        val HORA_PRUEBA: LocalDateTime = LocalDateTime.of(2026, 10, 27, 9, 41)
    }

    private fun detenerVisibleYHabilitado() {
        compose.onNodeWithTag(TAG_BOTON_DETENER).assertIsDisplayed().assertIsEnabled()
    }

    // 1. DETENER con una confirmación abierta.

    @Test
    fun conConfirmacionAbierta_detenerSeVeEstaHabilitadoYDetiene() {
        mostrar(EstadosMuestra.conConfirmacion, Pestana.CONTROL)

        compose.onNodeWithTag(TAG_OVERLAY_CONFIRMACION).assertIsDisplayed()
        compose.onNodeWithText(EstadosMuestra.confirmacionCiclo.titulo).assertIsDisplayed()
        detenerVisibleYHabilitado()

        compose.onNodeWithTag(TAG_BOTON_DETENER).performClick()

        assertEquals(1, acciones.veces("detenerEmergencia"))
        // Pulsar DETENER no confirma ni cancela la acción pendiente por accidente.
        assertEquals(0, acciones.veces("confirmar"))
    }

    @Test
    fun conConfirmacionAbierta_laNavegacionSigueVisible() {
        mostrar(EstadosMuestra.conConfirmacion, Pestana.CONTROL)
        Pestana.entries.forEach { compose.onNodeWithTag(tagPestana(it)).assertIsDisplayed() }
        compose.onNodeWithTag(TAG_CHIP_CONEXION).assertIsDisplayed()
    }

    @Test
    fun paradaEnCurso_detenerSigueHabilitado() {
        mostrar(EstadosMuestra.conectadoEspera.copy(paradaEnCurso = true))
        detenerVisibleYHabilitado()
        compose.onNodeWithTag(TAG_BOTON_DETENER).performClick()
        assertEquals(1, acciones.veces("detenerEmergencia"))
    }

    @Test
    fun overlay_botonesYAtrasLlamanALasAcciones() {
        mostrar(EstadosMuestra.conConfirmacion, Pestana.CONTROL)

        compose.onNodeWithTag(TAG_BOTON_CONFIRMAR).performClick()
        assertEquals(1, acciones.veces("confirmar"))

        compose.onNodeWithTag(TAG_BOTON_CANCELAR).performClick()
        assertEquals(1, acciones.veces("cancelarConfirmacion"))

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(2, acciones.veces("cancelarConfirmacion"))
    }

    // 2. Rutinas deshabilitadas.

    @Test
    fun rutinasDeshabilitadas_botonesDeshabilitadosYMotivoVisible() {
        mostrar(EstadosMuestra.riegoEnCurso, Pestana.CONTROL)

        compose.onNodeWithTag(TAG_AVISO_DISPONIBILIDAD).assertIsDisplayed()
        compose.onNodeWithText(EstadosMuestra.MOTIVO_OCUPADO).assertIsDisplayed()
        ModoInicio.entries.forEach { modo ->
            compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(tagRutina(modo)))
            compose.onNodeWithTag(tagRutina(modo)).assertIsNotEnabled().performClick()
        }
        assertTrue(acciones.llamadas.toString(), acciones.confirmacionesPedidas.isEmpty())
    }

    @Test
    fun riegoAutomatico_sinPoderActivar_switchDeshabilitado() {
        // Apagado y sin poder activarse: el Switch no responde.
        val apagado = EstadosMuestra.riegoEnCurso.copy(robot = EstadosMuestra.robotRiego.copy(autoActivo = false))
        mostrar(apagado, Pestana.CONTROL)
        compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(TAG_SWITCH_AUTO))
        compose.onNodeWithTag(TAG_SWITCH_AUTO).assertIsNotEnabled().performClick()
        assertTrue(acciones.llamadas.toString(), acciones.llamadas.isEmpty())
    }

    @Test
    fun riegoAutomatico_encenderPideConfirmacionConElDescanso() {
        val apagado = EstadosMuestra.conectadoEspera.copy(robot = EstadosMuestra.robotEspera.copy(autoActivo = false))
        mostrar(apagado, Pestana.CONTROL)
        compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(TAG_SWITCH_AUTO))
        compose.onNodeWithTag(TAG_SWITCH_AUTO).assertIsEnabled().performClick()
        assertEquals(listOf<AccionConfirmable>(AccionConfirmable.ActivarAuto(1800)), acciones.confirmacionesPedidas)
    }

    @Test
    fun riegoAutomatico_apagarNoPideConfirmacion() {
        mostrar(EstadosMuestra.riegoEnCurso, Pestana.CONTROL)
        compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(TAG_SWITCH_AUTO))
        compose.onNodeWithTag(TAG_SWITCH_AUTO).assertIsEnabled().performClick()
        assertEquals(listOf("desactivarAuto"), acciones.llamadas)
    }

    @Test
    fun comandoEnCurso_tambienDeshabilitaLasRutinas() {
        mostrar(EstadosMuestra.conectadoEspera.copy(comandoEnCurso = "iniciar ciclo"), Pestana.CONTROL)
        compose.onNodeWithText("Enviando: iniciar ciclo…").assertIsDisplayed()
        ModoInicio.entries.forEach { modo ->
            compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(tagRutina(modo)))
            compose.onNodeWithTag(tagRutina(modo)).assertIsNotEnabled()
        }
    }

    @Test
    fun rutinasHabilitadas_pidenConfirmacionSinEnviarNada() {
        mostrar(EstadosMuestra.conectadoEspera, Pestana.CONTROL)
        compose.onNodeWithText("Listo para recibir órdenes").assertIsDisplayed()
        ModoInicio.entries.forEach { modo ->
            compose.onNodeWithTag(TAG_PANTALLA_CONTROL).performScrollToNode(hasTestTag(tagRutina(modo)))
            compose.onNodeWithTag(tagRutina(modo)).assertIsEnabled().performClick()
        }
        assertEquals(
            ModoInicio.entries.map { AccionConfirmable.Iniciar(it) },
            acciones.confirmacionesPedidas,
        )
        assertEquals(0, acciones.veces("confirmar"))
    }

    // 3. Sugerencia "¿Deseas fertilizar?".

    @Test
    fun sugerirFertilizar_tarjetaVisibleYBotonesResponden() {
        mostrar(EstadosMuestra.sugerirFertilizar)

        compose.onNodeWithTag(TAG_TARJETA_FERTILIZAR).assertIsDisplayed()
        compose.onNodeWithText("¿Deseas fertilizar ahora?").assertIsDisplayed()

        compose.onNodeWithTag(TAG_FERTILIZAR_NO).performClick()
        compose.onNodeWithTag(TAG_FERTILIZAR_SI).assertIsEnabled().performClick()

        assertEquals(listOf(false, true), acciones.respuestasFertilizar)
    }

    @Test
    fun sugerirFertilizar_sinRutinas_siDeshabilitadoConMotivo() {
        mostrar(EstadosMuestra.riegoEnCurso.copy(resumen = EstadosMuestra.resumenCompleto.copy(sugerirFertilizar = true)))

        compose.onNodeWithTag(TAG_FERTILIZAR_SI).assertIsNotEnabled()
        compose.onNodeWithText(EstadosMuestra.MOTIVO_OCUPADO).assertIsDisplayed()
        compose.onNodeWithTag(TAG_FERTILIZAR_NO).assertIsEnabled().performClick()
        assertEquals(listOf(false), acciones.respuestasFertilizar)
    }

    @Test
    fun sinSugerencia_noHayTarjeta() {
        mostrar(EstadosMuestra.conectadoEspera)
        compose.onNodeWithTag(TAG_TARJETA_FERTILIZAR).assertDoesNotExist()
    }

    // 4. Todas las pestañas y Conexión en cada estado de muestra.

    private val tagsPantallas = mapOf(
        Pestana.INICIO to TAG_PANTALLA_INICIO,
        Pestana.ZONAS to TAG_PANTALLA_ZONAS,
        Pestana.GRAFICAS to TAG_PANTALLA_GRAFICAS,
        Pestana.CONTROL to TAG_PANTALLA_CONTROL,
        Pestana.ANALISIS to TAG_PANTALLA_ANALISIS,
    )

    /** Desplaza la lista hasta abajo para que se compongan también los elementos ocultos. */
    private fun recorrerLista(tag: String) {
        repeat(5) { compose.onNodeWithTag(tag).performTouchInput { swipeUp() } }
        compose.waitForIdle()
    }

    private fun recorrerTodo(estado: UiState) {
        mostrar(estado)
        Pestana.entries.forEach { pestana ->
            compose.onNodeWithTag(tagPestana(pestana)).performClick()
            val tag = tagsPantallas.getValue(pestana)
            compose.onNodeWithTag(tag).assertIsDisplayed()
            recorrerLista(tag)
            detenerVisibleYHabilitado()
        }
        compose.onNodeWithTag(TAG_CHIP_CONEXION).performClick()
        compose.onNodeWithTag(TAG_PANTALLA_CONEXION).assertIsDisplayed()
        recorrerLista(TAG_PANTALLA_CONEXION)
        detenerVisibleYHabilitado()
        compose.onNodeWithTag(TAG_VOLVER).performClick()
        compose.onNodeWithTag(TAG_PANTALLA_ANALISIS).assertIsDisplayed()
    }

    @Test
    fun todo_sinDatos() {
        recorrerTodo(EstadosMuestra.sinDatos)
        compose.onNode(hasText("Sin datos del robot todavía") and hasAnyAncestor(hasTestTag(TAG_FRANJA_DATOS)))
            .assertIsDisplayed()
    }

    @Test
    fun todo_conectadoEnEspera() {
        recorrerTodo(EstadosMuestra.conectadoEspera)
        compose.onNodeWithTag(TAG_FRANJA_DATOS).assertDoesNotExist()
    }

    @Test
    fun todo_riegoEnCurso() = recorrerTodo(EstadosMuestra.riegoEnCurso)

    @Test
    fun todo_emergenciaFalla8() {
        recorrerTodo(EstadosMuestra.emergencia)
        compose.onNodeWithTag(tagPestana(Pestana.INICIO)).performClick()
        compose.onNodeWithTag(TAG_PANTALLA_INICIO).performScrollToNode(hasTestTag(TAG_TARJETA_FALLA))
        compose.onNodeWithTag(TAG_TARJETA_FALLA).assertIsDisplayed()
        compose.onNodeWithTag(TAG_REINICIAR_FALLA).assertIsEnabled().performClick()
        assertEquals(listOf<AccionConfirmable>(AccionConfirmable.Reiniciar), acciones.confirmacionesPedidas)
    }

    @Test
    fun todo_datosObsoletos() {
        recorrerTodo(EstadosMuestra.datosObsoletos())
        compose.onNodeWithTag(TAG_FRANJA_DATOS).assertIsDisplayed()
        compose.onNodeWithText("Datos de hace 42 s", substring = true).assertIsDisplayed()
    }

    @Test
    fun todo_permisosFaltantes() {
        recorrerTodo(EstadosMuestra.permisosFaltantes)
        compose.onNodeWithTag(TAG_FRANJA_PERMISOS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_CHIP_CONEXION).performClick()
        compose.onNodeWithTag(TAG_TARJETA_PERMISOS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_BOTON_CONCEDER).assertIsEnabled()
    }

    @Test
    fun todo_simulador() {
        recorrerTodo(EstadosMuestra.simulador)
        compose.onNodeWithTag(TAG_CHIP_CONEXION).performClick()
        compose.onNodeWithTag(TAG_PANTALLA_CONEXION).performScrollToNode(hasTestTag(TAG_SECCION_DEBUG))
        compose.onNodeWithTag(TAG_PANTALLA_CONEXION).performScrollToNode(hasTestTag(TAG_SIMULAR_PERDIDA))
        compose.onNodeWithTag(tagSimularFalla(2)).performClick()
        compose.onNodeWithTag(tagSimularFalla(3)).performClick()
        compose.onNodeWithTag(tagSimularFalla(9)).performClick()
        compose.onNodeWithTag(TAG_SIMULAR_PERDIDA).performClick()
        compose.onNodeWithTag(TAG_SWITCH_SIMULADOR).performClick()
        assertEquals(
            listOf(
                "simularFalla(2)", "simularFalla(3)", "simularFalla(9)",
                "simularPerdidaConexion(10)", "usarSimulador(false)",
            ),
            acciones.llamadas,
        )
    }

    @Test
    fun zonas_conSinRecorridoYCon150Zonas() {
        mostrar(EstadosMuestra.sinRecorrido, Pestana.ZONAS)
        compose.onNodeWithTag(TAG_AVISO_SIN_RECORRIDO).assertIsDisplayed()
        compose.onNodeWithText("http://192.168.4.1", substring = true).assertIsDisplayed()
    }

    @Test
    fun zonas_150ZonasSeDibujan() {
        mostrar(EstadosMuestra.zonas150, Pestana.ZONAS)
        compose.onNodeWithContentDescription("Riel con 150 zonas", substring = true).assertIsDisplayed()
        // La fila de zonas se desplaza sola hasta la zona actual.
        compose.onNodeWithContentDescription("Zona 87: Intermedia, zona actual").assertIsDisplayed()
        recorrerLista(TAG_PANTALLA_ZONAS)
        detenerVisibleYHabilitado()
    }

    // 5. Zonas rediseñada: cielo, tarjetas de zona y Regar.

    @Test
    fun zonas_tarjetasConPorcentajeMedidoYZonaActual() {
        mostrar(EstadosMuestra.riegoEnCurso, Pestana.ZONAS)
        // Con 6 zonas en 411 dp la fila se desplaza hasta la actual (la 4, seca, medida al 21 %).
        compose.onNodeWithContentDescription("Zona 3: Húmeda, 69 %").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zona 4: Seca, 21 %, zona actual").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bomba: Activa").assertIsDisplayed()
        compose.onNodeWithContentDescription("Robot: En ruta").assertIsDisplayed()
    }

    @Test
    fun zonas_cieloMuestraLaHoraYPrevisualizaOtraFase() {
        mostrar(EstadosMuestra.conectadoEspera, Pestana.ZONAS)
        compose.onNodeWithText("09:41").assertIsDisplayed()
        compose.onNodeWithText("Martes 27 de octubre").assertIsDisplayed()
        compose.onNodeWithText("Mañana").assertIsDisplayed()
        compose.onNodeWithTag(TAG_VOLVER_HORA_REAL).assertDoesNotExist()

        compose.onNodeWithTag(tagFaseCielo(FaseDia.ATARDECER)).performClick()
        compose.onNodeWithText("Atardecer").assertIsDisplayed()
        compose.onNodeWithText("09:41").assertIsDisplayed()
        compose.onNodeWithTag(TAG_VOLVER_HORA_REAL).assertIsDisplayed().performClick()
        compose.onNodeWithText("Mañana").assertIsDisplayed()
    }

    @Test
    fun zonas_regarPideConfirmarUnCiclo() {
        mostrar(EstadosMuestra.conectadoEspera, Pestana.ZONAS)
        compose.onNodeWithTag(TAG_PANTALLA_ZONAS).performScrollToNode(hasTestTag(TAG_AVISO_RIEGO))
        compose.onNodeWithText("Zonas 1 y 4 necesitan riego").assertIsDisplayed()
        compose.onNodeWithTag(TAG_BOTON_REGAR).assertIsEnabled().performClick()
        assertEquals(listOf<AccionConfirmable>(AccionConfirmable.Iniciar(ModoInicio.CICLO)), acciones.confirmacionesPedidas)
    }

    @Test
    fun zonas_regarDeshabilitadoConMotivoSinConexion() {
        mostrar(EstadosMuestra.datosObsoletos(), Pestana.ZONAS)
        compose.onNodeWithTag(TAG_PANTALLA_ZONAS).performScrollToNode(hasTestTag(TAG_AVISO_RIEGO))
        compose.onNodeWithTag(TAG_BOTON_REGAR).assertIsNotEnabled()
        compose.onNodeWithText("Sin conexión con el robot").assertIsDisplayed()
    }

    @Test
    fun zonas_duranteElRiegoNoHayBotonRegar() {
        mostrar(EstadosMuestra.riegoEnCurso, Pestana.ZONAS)
        compose.onNodeWithTag(TAG_PANTALLA_ZONAS).performScrollToNode(hasTestTag(TAG_AVISO_RIEGO))
        compose.onNodeWithText("El ciclo en curso las está regando.").assertIsDisplayed()
        compose.onNodeWithTag(TAG_BOTON_REGAR).assertDoesNotExist()
    }

    // 6. Gráficas rediseñada: tanque, rangos y riegos por zona.

    @Test
    fun graficas_tanqueConNivelYLeyenda() {
        mostrar(EstadosMuestra.conectadoEspera, Pestana.GRAFICAS)
        compose.onNodeWithContentDescription("Tanque de humedad al 47 %, Intermedia").assertIsDisplayed()
        compose.onNodeWithText("Nivel intermedio").assertIsDisplayed()
        compose.onNodeWithText("EN VIVO").assertIsDisplayed()
        compose.onNodeWithText("Valor ADC 2354").assertIsDisplayed()
    }

    @Test
    fun graficas_elRangoFiltraLaTendencia() {
        val vieja = EstadosMuestra.muestras.first().let { it.copy(momentoMs = it.momentoMs - 3 * 3_600_000L) }
        val estado = EstadosMuestra.conectadoEspera.copy(
            resumen = EstadosMuestra.resumenCompleto.copy(muestras = listOf(vieja) + EstadosMuestra.muestras),
        )
        mostrar(estado, Pestana.GRAFICAS)
        compose.onNodeWithText("12 mediciones hasta las", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(tagRango(RangoGrafica.SEIS_HORAS)).performClick()
        compose.onNodeWithText("13 mediciones hasta las", substring = true).assertIsDisplayed()
    }

    @Test
    fun graficas_riegosPorZonaConDuracion() {
        mostrar(EstadosMuestra.conectadoEspera, Pestana.GRAFICAS)
        compose.onNodeWithTag(TAG_PANTALLA_GRAFICAS).performScrollToNode(hasTestTag(TAG_EVENTOS_RIEGO))
        compose.onNodeWithText("3 riegos").assertIsDisplayed()
        compose.onNodeWithText("Zona 4").assertIsDisplayed()
        compose.onNodeWithText("≈ 18 s de bomba", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Interrumpido").assertIsDisplayed()
    }
}
