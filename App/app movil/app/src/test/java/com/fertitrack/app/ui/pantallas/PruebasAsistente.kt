package com.fertitrack.app.ui.pantallas

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fertitrack.app.asistente.AccionAsistente
import com.fertitrack.app.asistente.AccionAsistente.IrA
import com.fertitrack.app.asistente.AccionAsistente.ProponerRutina
import com.fertitrack.app.asistente.Destino
import com.fertitrack.app.asistente.EstadoAsistente
import com.fertitrack.app.asistente.MensajeChat
import com.fertitrack.app.asistente.RutinaPropuesta
import com.fertitrack.app.asistente.gemini.Fuente
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.FertiTrackPantalla
import com.fertitrack.app.ui.Pestana
import com.fertitrack.app.ui.TAG_VOLVER
import com.fertitrack.app.ui.componentes.LocalHoraCielo
import com.fertitrack.app.ui.componentes.TAG_BOTON_ASISTENTE
import com.fertitrack.app.ui.componentes.TAG_BOTON_DETENER
import com.fertitrack.app.ui.componentes.TAG_CAMPO_ASISTENTE
import com.fertitrack.app.ui.componentes.TAG_ENVIAR_ASISTENTE
import com.fertitrack.app.ui.componentes.TAG_MICROFONO
import com.fertitrack.app.ui.componentes.TAG_PANEL_ASISTENTE
import com.fertitrack.app.ui.componentes.TAG_TRANSCRIPCION
import com.fertitrack.app.ui.componentes.tagSugerencia
import com.fertitrack.app.ui.tagPestana
import com.fertitrack.app.ui.tema.FertiTrackTema
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** El panel del asistente y su relación con la navegación, las confirmaciones y DETENER. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class PruebasAsistente {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val acciones = DobleAcciones()
    private val asistente = DobleAsistente()
    private val eventos = MutableSharedFlow<AccionAsistente>(extraBufferCapacity = 4)

    private val abierto = EstadoAsistente(abierto = true, claveConfigurada = true, vozDisponible = true)

    private companion object {
        /** Misma hora fija que PruebasPantallas: el cielo de Zonas no depende del reloj. */
        val HORA_PRUEBA: LocalDateTime = LocalDateTime.of(2026, 10, 27, 9, 41)
    }

    private fun mostrar(estadoAsistente: EstadoAsistente = EstadoAsistente(claveConfigurada = true)) {
        compose.setContent {
            CompositionLocalProvider(LocalHoraCielo provides HORA_PRUEBA) {
                FertiTrackTema {
                    FertiTrackPantalla(
                        EstadosMuestra.conectadoEspera,
                        acciones,
                        Modifier,
                        pestanaInicial = Pestana.INICIO,
                        conexionInicial = false,
                        asistente = estadoAsistente,
                        accionesAsistente = asistente,
                        eventosAsistente = eventos,
                    )
                }
            }
        }
    }

    @Test
    fun botonAbreElPanel() {
        mostrar()
        compose.onNodeWithTag(TAG_BOTON_ASISTENTE).performClick()
        assertTrue(asistente.llamadas.toString(), "abrir" in asistente.llamadas)
    }

    @Test
    fun panelAbiertoSeVe() {
        mostrar(abierto)
        compose.onNodeWithTag(TAG_PANEL_ASISTENTE).assertIsDisplayed()
    }

    @Test
    fun detenerVisibleConPanelAbierto() {
        mostrar(abierto.copy(hablando = true, mensajes = listOf(MensajeChat(1, false, "Regando la zona 2."))))
        compose.onNodeWithTag(TAG_BOTON_DETENER).assertIsDisplayed().performClick()
        assertEquals(1, acciones.veces("detenerEmergencia"))
    }

    @Test
    fun escribirYEnviar() {
        mostrar(abierto)
        compose.onNodeWithTag(TAG_CAMPO_ASISTENTE).performTextInput("hola")
        compose.onNodeWithTag(TAG_ENVIAR_ASISTENTE).performClick()
        assertTrue(asistente.llamadas.toString(), "enviarTexto(hola)" in asistente.llamadas)
    }

    @Test
    fun sugerenciasConChatVacio() {
        mostrar(abierto.copy(sugerencias = listOf("¿Cómo está el invernadero?", "¿Qué tiempo hará hoy?")))
        compose.onNodeWithTag(tagSugerencia(0)).assertIsDisplayed()
        compose.onNodeWithTag(tagSugerencia(1)).assertIsDisplayed().performClick()
        assertTrue(asistente.llamadas.toString(), "enviarTexto(¿Qué tiempo hará hoy?)" in asistente.llamadas)
    }

    @Test
    fun transcripcionEnVivoVisible() {
        mostrar(abierto.copy(escuchando = true, transcripcion = "qué zonas"))
        compose.onNodeWithTag(TAG_TRANSCRIPCION).assertIsDisplayed().assertTextContains("qué zonas")
    }

    @Test
    fun microfonoConPermisoAlterna() {
        mostrar(abierto)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithTag(TAG_MICROFONO).performClick()
        assertTrue(asistente.llamadas.toString(), "alternarMicrofono" in asistente.llamadas)
    }

    @Test
    fun informaLaPantallaActual() {
        mostrar()
        compose.onNodeWithTag(tagPestana(Pestana.ZONAS)).performClick()
        compose.waitForIdle()
        assertTrue(asistente.llamadas.toString(), "pantallaActual(INICIO)" in asistente.llamadas)
        assertEquals("pantallaActual(ZONAS)", asistente.llamadas.last { it.startsWith("pantallaActual") })
    }

    @Test
    fun eventoIrANavega() {
        mostrar()
        compose.runOnIdle { eventos.tryEmit(IrA(Destino.GRAFICAS)) }
        compose.onNodeWithTag(tagPestana(Pestana.GRAFICAS)).assertIsSelected()
    }

    @Test
    fun eventoIrAConexionAbreConexion() {
        mostrar()
        compose.runOnIdle { eventos.tryEmit(IrA(Destino.CONEXION)) }
        compose.onNodeWithTag(TAG_VOLVER).assertIsDisplayed()
    }

    @Test
    fun eventoRutinaAbreConfirmacion() {
        mostrar(abierto)
        compose.runOnIdle { eventos.tryEmit(ProponerRutina(RutinaPropuesta.FERTILIZAR)) }
        compose.waitForIdle()
        assertEquals(listOf<AccionConfirmable>(AccionConfirmable.Iniciar(ModoInicio.FERTILIZAR)), acciones.confirmacionesPedidas)
        assertTrue(asistente.llamadas.toString(), "cerrar" in asistente.llamadas)
        compose.onNodeWithTag(tagPestana(Pestana.CONTROL)).assertIsSelected()
    }

    @Test
    fun fuentesComoEnlaces() {
        val respuesta = MensajeChat(
            1,
            false,
            "Mañana llueve en Bogotá.",
            fuentes = listOf(Fuente("clima.com", "https://clima.com/bogota"), Fuente("ideam.gov.co", "https://ideam.gov.co")),
        )
        mostrar(abierto.copy(mensajes = listOf(MensajeChat(0, true, "¿Llueve mañana?"), respuesta)))
        compose.onNodeWithText("clima.com").assertIsDisplayed()
        compose.onNodeWithText("ideam.gov.co").assertIsDisplayed()
    }

    @Test
    fun sinClaveAvisa() {
        mostrar(abierto.copy(claveConfigurada = false))
        compose.onNodeWithText("Falta la clave de Gemini", substring = true).assertIsDisplayed()
    }
}
