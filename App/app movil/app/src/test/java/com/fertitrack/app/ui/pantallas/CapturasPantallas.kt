package com.fertitrack.app.ui.pantallas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fertitrack.app.ui.FertiTrackPantalla
import com.fertitrack.app.ui.Pestana
import com.fertitrack.app.ui.UiState
import com.fertitrack.app.ui.tema.FertiTrackTema
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas PNG de cada pestaña, de Conexión y del overlay de confirmación, en tema claro y
 * oscuro, en app/build/capturas/. Usa los gráficos nativos de Robolectric (dibujo real).
 * Pantalla de 411 × 1200 dp: algo más alta que un teléfono para revisar más contenido de una vez.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1200dp-xhdpi", application = Application::class)
class CapturasPantallas {

    @get:Rule
    val compose = createComposeRule()

    private data class Escena(
        val nombre: String,
        val estado: UiState,
        val pestana: Pestana = Pestana.INICIO,
        val conexion: Boolean = false,
    )

    private val principales = listOf(
        Escena("inicio", EstadosMuestra.riegoEnCurso, Pestana.INICIO),
        Escena("zonas", EstadosMuestra.riegoEnCurso, Pestana.ZONAS),
        Escena("graficas", EstadosMuestra.conectadoEspera, Pestana.GRAFICAS),
        Escena("control", EstadosMuestra.conectadoEspera, Pestana.CONTROL),
        Escena("analisis", EstadosMuestra.conectadoEspera, Pestana.ANALISIS),
        Escena("conexion_permisos", EstadosMuestra.permisosFaltantes, conexion = true),
        Escena("confirmacion", EstadosMuestra.conConfirmacion, Pestana.CONTROL),
    )

    private val adicionales = listOf(
        Escena("inicio_emergencia", EstadosMuestra.emergencia),
        Escena("inicio_sugerir_fertilizar", EstadosMuestra.sugerirFertilizar),
        Escena("inicio_sin_datos", EstadosMuestra.sinDatos),
        Escena("inicio_datos_obsoletos", EstadosMuestra.datosObsoletos()),
        Escena("inicio_mensaje_error", EstadosMuestra.conMensajeError),
        Escena("zonas_150", EstadosMuestra.zonas150, Pestana.ZONAS),
        Escena("zonas_sin_recorrido", EstadosMuestra.sinRecorrido, Pestana.ZONAS),
        Escena("control_riego_en_curso", EstadosMuestra.riegoEnCurso, Pestana.CONTROL),
        Escena("graficas_vacia", EstadosMuestra.sinDatos, Pestana.GRAFICAS),
        Escena("conexion_simulador", EstadosMuestra.simulador, conexion = true),
    )

    @Test
    fun capturasTemaClaro() = capturar(principales + adicionales, oscuro = false)

    @Test
    fun capturasTemaOscuro() = capturar(
        principales + adicionales.filter { it.nombre in setOf("inicio_emergencia", "zonas_150", "conexion_simulador") },
        oscuro = true,
    )

    private fun capturar(escenas: List<Escena>, oscuro: Boolean) {
        val acciones = DobleAcciones()
        var actual by mutableStateOf(escenas.first())
        compose.setContent {
            // key: cada escena empieza de cero (pestaña inicial, scroll, mensajes).
            key(actual.nombre) {
                FertiTrackTema(oscuro = oscuro) {
                    FertiTrackPantalla(
                        actual.estado,
                        acciones,
                        Modifier,
                        pestanaInicial = actual.pestana,
                        conexionInicial = actual.conexion,
                    )
                }
            }
        }
        val dir = File(System.getProperty("user.dir"), "build/capturas").apply { mkdirs() }
        val sufijo = if (oscuro) "oscuro" else "claro"
        escenas.forEach { escena ->
            compose.runOnIdle { actual = escena }
            compose.waitForIdle()
            val archivo = File(dir, "${escena.nombre}_$sufijo.png")
            guardarPng(compose.onRoot().captureToImage(), archivo)
            assertTrue("No se escribió $archivo", archivo.length() > 0)
        }
    }

    private fun guardarPng(imagen: ImageBitmap, archivo: File) {
        val pixeles = IntArray(imagen.width * imagen.height)
        imagen.readPixels(pixeles)
        val buffer = BufferedImage(imagen.width, imagen.height, BufferedImage.TYPE_INT_ARGB)
        buffer.setRGB(0, 0, imagen.width, imagen.height, pixeles, 0, imagen.width)
        ImageIO.write(buffer, "png", archivo)
    }
}
