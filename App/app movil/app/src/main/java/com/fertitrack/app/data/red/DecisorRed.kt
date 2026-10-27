package com.fertitrack.app.data.red

import com.fertitrack.app.domain.conexion.EstadoRed
import com.fertitrack.app.domain.conexion.ModoEnlace

/** Estado del pedido automático del AP (WifiNetworkSpecifier). [T] es la red (android.net.Network). */
internal sealed interface PeticionAutomatica<out T> {
    data object Ninguna : PeticionAutomatica<Nothing>

    /** requestNetwork en curso: Android muestra su diálogo o busca el AP. */
    data object Pendiente : PeticionAutomatica<Nothing>

    data class Conectada<T>(val red: T) : PeticionAutomatica<T>

    /** No hay red: no se pudo pedir, el sistema no encontró el AP, el usuario canceló o se perdió. */
    data class SinRed(val motivo: String) : PeticionAutomatica<Nothing>
}

/** Una red Wi-Fi conectada; [esDelRobot] = su subred contiene la IP del robot. */
internal data class RedWifi<T>(val red: T, val esDelRobot: Boolean)

internal data class DecisionRed<T>(val estado: EstadoRed, val red: T?)

/** Reglas puras para elegir la red del robot y describirla. Sin Android: se prueba en la JVM. */
internal object DecisorRed {

    /** Android 17 (API 37) con targetSdk 37: sin él, el tráfico a la red local se bloquea. */
    const val PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * Prioridad: la red pedida por la app (AUTOMATICO) > una red Wi-Fi cuya subred contiene la
     * IP del robot (MANUAL) > ninguna (ruta por defecto). El permiso que falta se muestra
     * primero porque sin él el robot solo parecería "sin respuesta".
     */
    fun <T> decidir(
        faltaPermisoRedLocal: Boolean,
        peticion: PeticionAutomatica<T>,
        redesWifi: List<RedWifi<T>>,
        ssid: String,
    ): DecisionRed<T> {
        val automatica = if (peticion is PeticionAutomatica.Conectada) peticion.red else null
        val manual = redesWifi.firstOrNull { it.esDelRobot }?.red
        val red = automatica ?: manual
        val estado = when {
            faltaPermisoRedLocal -> EstadoRed.PermisosFaltantes(listOf(PERMISO_RED_LOCAL))
            automatica != null -> EstadoRed.Disponible(ModoEnlace.AUTOMATICO)
            manual != null -> EstadoRed.Disponible(ModoEnlace.MANUAL)
            peticion is PeticionAutomatica.Pendiente -> EstadoRed.Solicitando
            peticion is PeticionAutomatica.SinRed -> EstadoRed.NoDisponible(peticion.motivo)
            redesWifi.isNotEmpty() -> EstadoRed.NoDisponible(motivoOtraRed(ssid))
            else -> EstadoRed.Inactiva
        }
        return DecisionRed(estado, red)
    }

    fun motivoAndroidAntiguo(ssid: String) =
        "En Android 8 y 9 la app no puede conectarse sola al robot: abre Ajustes > Wi-Fi, " +
            "elige «$ssid» y acepta seguir conectado aunque no tenga internet."

    fun motivoSinClave(ssid: String) =
        "Esta versión de la app no tiene la clave del Wi-Fi del robot: conéctate a «$ssid» " +
            "desde los ajustes de Wi-Fi."

    fun motivoClaveInvalida(ssid: String) =
        "La clave configurada para «$ssid» no sirve para WPA2 (8 a 63 caracteres ASCII): " +
            "conéctate desde los ajustes de Wi-Fi."

    fun motivoNoEncontrada(ssid: String) =
        "No se encontró «$ssid» o se canceló la conexión. Comprueba que el robot esté encendido " +
            "y cerca, y vuelve a intentarlo."

    fun motivoPerdida(ssid: String) =
        "Se perdió la conexión con «$ssid». Acércate al robot y vuelve a intentarlo."

    fun motivoNoPermitido(ssid: String, detalle: String?) =
        "Android no permitió pedir la red del robot${detalle?.let { " ($it)" } ?: ""}. " +
            "Conéctate a «$ssid» desde los ajustes de Wi-Fi."

    fun motivoOtraRed(ssid: String) =
        "El teléfono está en otra red Wi-Fi. Conéctate a «$ssid» para hablar con el robot."

    /** Frase WPA2-PSK, como la acepta setWpa2Passphrase: de 8 a 63 caracteres ASCII imprimibles. */
    fun claveWpa2Valida(clave: String): Boolean = clave.length in 8..63 && clave.all { it in ' '..'~' }

    /** "192.168.4.1" → 4 bytes; null si no es una IPv4 literal. */
    fun ipv4(texto: String): ByteArray? {
        val partes = texto.trim().split('.')
        if (partes.size != 4) return null
        val bytes = ByteArray(4)
        partes.forEachIndexed { i, parte ->
            if (parte.isEmpty() || parte.length > 3 || !parte.all { it in '0'..'9' }) return null
            val valor = parte.toInt()
            if (valor > 255) return null
            bytes[i] = valor.toByte()
        }
        return bytes
    }

    /** true si [ip] cae en la subred [direccion]/[prefijo] (misma familia de direcciones). */
    fun subredContiene(direccion: ByteArray, prefijo: Int, ip: ByteArray): Boolean {
        if (direccion.size != ip.size || prefijo < 1 || prefijo > direccion.size * 8) return false
        var bits = prefijo
        for (i in direccion.indices) {
            if (bits <= 0) break
            val mascara = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((direccion[i].toInt() and mascara) != (ip[i].toInt() and mascara)) return false
            bits -= 8
        }
        return true
    }

    /**
     * ¿Alguna dirección de la red (LinkProperties.linkAddresses) está en la subred del robot?
     * Si la IP del robot no es una IPv4 literal no se puede comprobar y se acepta la red.
     */
    fun esRedDelRobot(direcciones: List<Pair<ByteArray, Int>>, ipRobot: ByteArray?): Boolean =
        ipRobot == null || direcciones.any { (direccion, prefijo) -> subredContiene(direccion, prefijo, ipRobot) }
}
