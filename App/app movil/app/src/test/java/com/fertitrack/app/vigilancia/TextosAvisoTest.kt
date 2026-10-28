package com.fertitrack.app.vigilancia

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.robot
import com.fertitrack.app.domain.sesion.AvisoSesion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextosAvisoTest {

    @Test
    fun fallaConSuAccionSugerida() {
        val t = TextosAviso.de(AvisoSesion.Falla(8))!!
        assertEquals(CanalAviso.FALLAS, t.canal)
        assertEquals("Falla 8: Parada desde la app", t.titulo)
        assertTrue(t.texto.isNotBlank())
    }

    @Test
    fun riegoCompletadoSugiereFertilizarSinHacerlo() {
        val t = TextosAviso.de(AvisoSesion.RiegoTerminado(listOf(1, 4), completado = true, sugerirFertilizar = true))!!
        assertEquals(CanalAviso.RIEGO, t.canal)
        assertEquals("Riego completado", t.titulo)
        assertEquals("Se regaron las zonas 1 y 4. ¿Fertilizar ahora? Abre FertiTrack para confirmarlo.", t.texto)

        val sinSugerencia = TextosAviso.de(AvisoSesion.RiegoTerminado(listOf(3), completado = true, sugerirFertilizar = false))!!
        assertEquals("Se regó la zona 3.", sinSugerencia.texto)
        assertFalse(sinSugerencia.texto.contains("Fertilizar"))

        assertEquals("Riego interrumpido", TextosAviso.de(AvisoSesion.RiegoTerminado(listOf(1), false, false))!!.titulo)
    }

    @Test
    fun zonasSecasYConexion() {
        assertEquals("Zona 2 seca", TextosAviso.de(AvisoSesion.ZonasSecas(listOf(2)))!!.titulo)
        val varias = TextosAviso.de(AvisoSesion.ZonasSecas(listOf(1, 4, 6)))!!
        assertEquals("3 zonas secas", varias.titulo)
        assertTrue(varias.texto.contains("las zonas 1, 4 y 6"))
        assertEquals(CanalAviso.CONEXION, TextosAviso.de(AvisoSesion.ConexionPerdida)!!.canal)
        assertNull(TextosAviso.de(AvisoSesion.ConexionRecuperada))
    }

    @Test
    fun lineaDeLaNotificacionFija() {
        assertEquals("Sin respuesta del robot", TextosAviso.estadoVigilancia(robot(), EstadoConexion.SinRespuesta(2, "x")))
        assertEquals("Conectando con el robot…", TextosAviso.estadoVigilancia(null, EstadoConexion.Conectando))
        val regando = robot(EstadoMaquina.SENSADO_MIDIENDO, ModoRutina.SENSADO, zona = 3)
        assertTrue(TextosAviso.estadoVigilancia(regando, EstadoConexion.Conectado).contains("zona 3"))
    }
}
