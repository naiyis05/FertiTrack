package com.fertitrack.app.data.red

import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisorRedTest {

    private val ssid = "FertiTrack_AP"

    @Test
    fun laRedPedidaGanaALaManual() {
        val d = DecisorRed.decidir(
            faltaPermisoRedLocal = false,
            peticion = PeticionAutomatica.Conectada("auto"),
            redesWifi = listOf(RedWifi("manual", esDelRobot = true)),
            ssid = ssid,
        )
        assertEquals(EstadoRed.Disponible(ModoEnlace.AUTOMATICO), d.estado)
        assertEquals("auto", d.red)
    }

    @Test
    fun manualSoloSiLaSubredEsLaDelRobot() {
        val otra = DecisorRed.decidir(false, PeticionAutomatica.Ninguna, listOf(RedWifi("casa", false)), ssid)
        assertNull(otra.red)
        assertEquals(EstadoRed.NoDisponible(DecisorRed.motivoOtraRed(ssid)), otra.estado)

        val robot = DecisorRed.decidir(false, PeticionAutomatica.Ninguna, listOf(RedWifi("casa", false), RedWifi("ap", true)), ssid)
        assertEquals("ap", robot.red)
        assertEquals(EstadoRed.Disponible(ModoEnlace.MANUAL), robot.estado)
    }

    @Test
    fun estadosSinRed() {
        assertEquals(EstadoRed.Inactiva, DecisorRed.decidir<String>(false, PeticionAutomatica.Ninguna, emptyList(), ssid).estado)
        assertEquals(EstadoRed.Solicitando, DecisorRed.decidir<String>(false, PeticionAutomatica.Pendiente, emptyList(), ssid).estado)
        assertEquals(
            EstadoRed.NoDisponible("x"),
            DecisorRed.decidir<String>(false, PeticionAutomatica.SinRed("x"), emptyList(), ssid).estado,
        )
    }

    @Test
    fun sinPermisoDeRedLocalSeAvisaPrimeroPeroSeConservaLaRed() {
        val d = DecisorRed.decidir(true, PeticionAutomatica.Conectada("auto"), emptyList(), ssid)
        assertEquals(EstadoRed.PermisosFaltantes(listOf("android.permission.ACCESS_LOCAL_NETWORK")), d.estado)
        assertEquals("auto", d.red)
    }

    @Test
    fun subredes() {
        val robot = DecisorRed.ipv4("192.168.4.1")!!
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 4, 1), robot)
        assertTrue(DecisorRed.subredContiene(DecisorRed.ipv4("192.168.4.2")!!, 24, robot))
        assertFalse(DecisorRed.subredContiene(DecisorRed.ipv4("192.168.1.20")!!, 24, robot))
        assertTrue(DecisorRed.subredContiene(DecisorRed.ipv4("192.168.1.20")!!, 16, robot))
        assertFalse(DecisorRed.subredContiene(ByteArray(16), 64, robot)) // IPv6 frente a IPv4
        assertNull(DecisorRed.ipv4("robot.local"))
        assertNull(DecisorRed.ipv4("192.168.4.256"))
        assertTrue(DecisorRed.esRedDelRobot(listOf(DecisorRed.ipv4("192.168.4.3")!! to 24), robot))
        assertTrue(DecisorRed.esRedDelRobot(emptyList(), null))
    }

    @Test
    fun claveWpa2() {
        assertTrue(DecisorRed.claveWpa2Valida("12345678")) // 8 caracteres: el mínimo de WPA2
        assertFalse(DecisorRed.claveWpa2Valida("1234567"))
        assertFalse(DecisorRed.claveWpa2Valida("a".repeat(64)))
        assertFalse(DecisorRed.claveWpa2Valida("contraseña1"))
    }
}
