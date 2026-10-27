package com.fertitrack.app.domain.traduccion

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.ResultadoComando
import com.fertitrack.app.domain.robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraductorTest {

    @Test
    fun todosLosEstadosTienenTituloYDetalle() {
        for (estado in EstadoMaquina.entries) {
            val d = Traductor.describirEstado(robot(estado = estado, zona = 3, falla = if (estado == EstadoMaquina.EMERGENCIA) 8 else 0))
            assertTrue("título vacío para $estado", d.titulo.isNotBlank())
            assertTrue("detalle vacío para $estado", d.detalle.isNotBlank())
            assertTrue(Traductor.nombreEstado(estado).isNotBlank())
        }
    }

    @Test
    fun categoriasPorFamiliaDeEstados() {
        assertEquals(CategoriaEstado.REPOSO, Traductor.describirEstado(robot()).categoria)
        assertEquals(CategoriaEstado.SENSADO, Traductor.describirEstado(robot(EstadoMaquina.SENSADO_MIDIENDO)).categoria)
        assertEquals(CategoriaEstado.RIEGO, Traductor.describirEstado(robot(EstadoMaquina.RIEGO_RETORNANDO)).categoria)
        assertEquals(CategoriaEstado.FERTILIZACION, Traductor.describirEstado(robot(EstadoMaquina.FERTI_AVANZANDO)).categoria)
        assertEquals(CategoriaEstado.RECUPERACION, Traductor.describirEstado(robot(EstadoMaquina.RECUP_RETORNANDO)).categoria)
        assertEquals(CategoriaEstado.ARRANQUE, Traductor.describirEstado(robot(EstadoMaquina.ARRANQUE)).categoria)
        assertEquals(CategoriaEstado.PRUEBA, Traductor.describirEstado(robot(EstadoMaquina.PRUEBA_LISTA)).categoria)
        assertEquals(CategoriaEstado.EMERGENCIA, Traductor.describirEstado(robot(EstadoMaquina.EMERGENCIA, falla = 9)).categoria)
        assertEquals(CategoriaEstado.DESCONOCIDO, Traductor.describirEstado(robot(EstadoMaquina.DESCONOCIDO)).categoria)
    }

    @Test
    fun riegoDistingueBombaEncendidaDeCruceSinRegar() {
        val regando = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 4, actividad = Actividad.REGANDO)
        assertEquals("Regando zona 4 de 6", Traductor.describirEstado(regando).titulo)
        val cruzando = robot(EstadoMaquina.RIEGO_AVANZANDO, ModoRutina.RIEGO, zona = 4, actividad = Actividad.EN_MOVIMIENTO)
        assertTrue(Traductor.describirEstado(cruzando).titulo.startsWith("Cruzando"))
    }

    @Test
    fun sensadoIncluyeZona() {
        assertEquals("Sensando zona 3 de 6", Traductor.describirEstado(robot(EstadoMaquina.SENSADO_MIDIENDO, zona = 3)).titulo)
    }

    @Test
    fun emergenciaYDesconocidoMuestranElMotivo() {
        assertTrue(Traductor.describirEstado(robot(EstadoMaquina.EMERGENCIA, falla = 8)).titulo.contains("Parada desde la app"))
        val raro = robot(EstadoMaquina.DESCONOCIDO, estadoTexto = "?")
        assertTrue(Traductor.describirEstado(raro).titulo.contains("(?)"))
    }

    @Test
    fun fallasConocidasYDesconocida() {
        val titulos = listOf(1, 2, 3, 8, 9).map { codigo ->
            val f = Traductor.describirFalla(codigo)
            assertEquals(codigo, f.codigo)
            assertTrue(f.explicacion.isNotBlank())
            assertTrue(f.accionSugerida.isNotBlank())
            f.titulo
        }
        assertEquals(5, titulos.toSet().size)
        assertTrue(Traductor.describirFalla(8).accionSugerida.contains("Reiniciar"))
        assertTrue(Traductor.describirFalla(5).titulo.contains("código 5"))
    }

    @Test
    fun nombresDeTodosLosEnums() {
        NivelSuelo.entries.forEach { assertTrue(Traductor.nombreNivel(it).isNotBlank()) }
        Actividad.entries.forEach { assertTrue(Traductor.nombreActividad(it).isNotBlank()) }
        ModoRutina.entries.forEach { assertTrue(Traductor.nombreRutina(it).isNotBlank()) }
        ModoInicio.entries.forEach { assertTrue(Traductor.nombreModoInicio(it).isNotBlank()) }
    }

    @Test
    fun motivoRutinasNoDisponibles() {
        val conectado = EstadoConexion.Conectado
        assertNull(Traductor.motivoRutinasNoDisponibles(robot(), conectado))
        assertTrue(Traductor.motivoRutinasNoDisponibles(robot(distanciaCm = 0f, totalZonas = 0), conectado)!!.contains("distancia"))
        assertTrue(Traductor.motivoRutinasNoDisponibles(robot(), EstadoConexion.SinRespuesta(1, "x"))!!.contains("Sin conexión"))
        assertTrue(Traductor.motivoRutinasNoDisponibles(null, conectado)!!.contains("Sin conexión"))
        assertTrue(Traductor.motivoRutinasNoDisponibles(robot(EstadoMaquina.EMERGENCIA, falla = 8), conectado)!!.contains("reinicia"))
        assertTrue(Traductor.motivoRutinasNoDisponibles(robot(EstadoMaquina.PRUEBA_LISTA), conectado)!!.contains("prueba"))
        assertTrue(Traductor.motivoRutinasNoDisponibles(robot(EstadoMaquina.RECUP_RETORNANDO), conectado)!!.contains("preparando"))
        val ocupado = Traductor.motivoRutinasNoDisponibles(robot(EstadoMaquina.SENSADO_MIDIENDO, zona = 2), conectado)
        assertNotNull(ocupado)
        assertTrue(ocupado!!.contains("ocupado"))
    }

    @Test
    fun mensajesDeResultadoAmistosos() {
        val ocupado = Traductor.mensajeResultado("Iniciar ciclo", ResultadoComando.Ocupado("sistema ocupado"))
        assertTrue(ocupado.contains("ocupado ahora mismo"))
        assertFalse(ocupado.contains("error", ignoreCase = true))
        assertTrue(
            Traductor.mensajeResultado("Iniciar ciclo", ResultadoComando.Ocupado("configura primero la distancia con /api/configurar"))
                .contains("configurar la distancia"),
        )
        assertTrue(Traductor.mensajeResultado("X", ResultadoComando.Exito()).contains("orden enviada"))
        assertTrue(Traductor.mensajeResultado("X", ResultadoComando.Exito("Moviendo 10 cm")).contains("Moviendo 10 cm"))
        assertTrue(Traductor.mensajeResultado("X", ResultadoComando.Rechazado("cm debe estar entre 1 y 150")).contains("rechazó"))
        assertTrue(Traductor.mensajeResultado("X", ResultadoComando.ErrorRobot(500, "no se pudo calcular zonas")).contains("500"))
        assertTrue(Traductor.mensajeResultado("X", ResultadoComando.SinRespuesta("timeout")).contains("no hubo respuesta"))
    }

    @Test
    fun explicaPermisos() {
        assertTrue(Traductor.explicarPermiso("android.permission.ACCESS_LOCAL_NETWORK").contains("red local"))
        assertTrue(Traductor.explicarPermiso("otro").isNotBlank())
    }
}
