package com.fertitrack.app.data.red

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.fertitrack.app.config.RobotConfig
import com.fertitrack.app.domain.conexion.EstadoRed
import javax.net.SocketFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [EnlaceRed] con ConnectivityManager. Detalles y fuentes en docs/RED_ANDROID.md.
 *
 * - Modo manual (todas las versiones): registerNetworkCallback de redes Wi-Fi SIN exigir
 *   INTERNET; la que tiene una dirección en la subred de la IP del robot es la del AP.
 * - Modo automático (Android 10+): requestNetwork con WifiNetworkSpecifier (SSID exacto + WPA2).
 *   Con SSID exacto Android recuerda la aprobación del usuario para ese AP.
 * - Las peticiones salen por Network.getSocketFactory() de la red elegida; no se usa
 *   bindProcessToNetwork para no desviar el resto del tráfico de la app.
 * - Android 17+ (API 37): sin ACCESS_LOCAL_NETWORK el estado es PermisosFaltantes.
 *
 * Los pedidos siguen vivos con la app en segundo plano: solo se pausa el sondeo. Los callbacks
 * llegan en el hilo interno de ConnectivityManager; el estado se protege con [cerrojo].
 */
class EnlaceRedAndroid(
    contexto: Context,
    private val ssid: String = RobotConfig.SSID_AP,
    private val clave: String = RobotConfig.CLAVE_AP,
    ipRobot: String = RobotConfig.IP_ROBOT,
) : EnlaceRed {

    private val app: Context = contexto.applicationContext
    private val conectividad: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private val ipRobotBytes: ByteArray? = DecisorRed.ipv4(ipRobot)

    private val cerrojo = Any()

    /** Redes Wi-Fi conectadas → ¿su subred contiene la IP del robot? */
    private val redesWifi = LinkedHashMap<Network, Boolean>()
    private var peticion: PeticionAutomatica<Network> = PeticionAutomatica.Ninguna
    private var escucha: ConnectivityManager.NetworkCallback? = null
    private var callbackPeticion: ConnectivityManager.NetworkCallback? = null

    private val _estado = MutableStateFlow<EstadoRed>(EstadoRed.Inactiva)
    override val estado: StateFlow<EstadoRed> = _estado.asStateFlow()

    private val _fabricaSockets = MutableStateFlow<SocketFactory?>(null)
    override val fabricaSockets: StateFlow<SocketFactory?> = _fabricaSockets.asStateFlow()

    override fun activar() {
        synchronized(cerrojo) {
            if (escucha == null) registrarEscucha()
            publicar()
        }
    }

    override fun conectarAutomatico() {
        synchronized(cerrojo) {
            val bloqueo = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> DecisorRed.motivoAndroidAntiguo(ssid)
                clave.isEmpty() -> DecisorRed.motivoSinClave(ssid)
                !DecisorRed.claveWpa2Valida(clave) -> DecisorRed.motivoClaveInvalida(ssid)
                else -> null
            }
            // Pendiente o Conectada: no se repite el pedido (ni el diálogo del sistema).
            val yaPedida = peticion is PeticionAutomatica.Pendiente || peticion is PeticionAutomatica.Conectada
            if (bloqueo != null) {
                peticion = PeticionAutomatica.SinRed(bloqueo)
            } else if (!yaPedida && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pedirAp()
            }
            if (escucha == null) registrarEscucha()
            publicar()
        }
    }

    override fun reevaluar() {
        synchronized(cerrojo) {
            if (escucha == null) registrarEscucha()
            publicar()
        }
    }

    override fun liberar() {
        synchronized(cerrojo) {
            soltarPeticion()
            escucha?.let(::desregistrar)
            escucha = null
            redesWifi.clear()
            peticion = PeticionAutomatica.Ninguna
            publicar()
        }
    }

    private fun registrarEscucha() {
        val cm = conectividad ?: return
        val pedido = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = EscuchaWifi()
        try {
            cm.registerNetworkCallback(pedido, callback)
            escucha = callback
        } catch (e: RuntimeException) {
            // SecurityException (falta ACCESS_NETWORK_STATE) o demasiados callbacks: sin modo
            // manual; reevaluar() lo vuelve a intentar.
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun pedirAp() {
        soltarPeticion()
        val cm = conectividad
        if (cm == null) {
            peticion = PeticionAutomatica.SinRed(DecisorRed.motivoNoPermitido(ssid, "sin servicio de conectividad"))
            return
        }
        val pedido = try {
            val especificador = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(clave)
                .build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(especificador)
                .build()
        } catch (e: IllegalArgumentException) {
            peticion = PeticionAutomatica.SinRed(DecisorRed.motivoClaveInvalida(ssid))
            return
        } catch (e: IllegalStateException) {
            peticion = PeticionAutomatica.SinRed(DecisorRed.motivoClaveInvalida(ssid))
            return
        }
        val callback = PeticionAp()
        callbackPeticion = callback
        peticion = PeticionAutomatica.Pendiente
        try {
            cm.requestNetwork(pedido, callback)
        } catch (e: SecurityException) {
            // Exige CHANGE_NETWORK_STATE (permiso normal, declarado): no debería pasar.
            callbackPeticion = null
            peticion = PeticionAutomatica.SinRed(DecisorRed.motivoNoPermitido(ssid, e.message))
        } catch (e: RuntimeException) {
            // Por ejemplo, demasiados callbacks registrados por la app.
            callbackPeticion = null
            peticion = PeticionAutomatica.SinRed(DecisorRed.motivoNoPermitido(ssid, e.message))
        }
    }

    /** Libera el pedido anterior (si el sistema no lo liberó ya con onUnavailable). */
    private fun soltarPeticion() {
        val callback = callbackPeticion ?: return
        callbackPeticion = null
        desregistrar(callback)
    }

    private fun desregistrar(callback: ConnectivityManager.NetworkCallback) {
        try {
            conectividad?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Ya no estaba registrado.
        }
    }

    private fun faltaPermisoRedLocal(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            app.checkSelfPermission(DecisorRed.PERMISO_RED_LOCAL) != PackageManager.PERMISSION_GRANTED

    /** Se llama con [cerrojo] tomado: así los estados se publican en orden. */
    private fun publicar() {
        val decision = DecisorRed.decidir(
            faltaPermisoRedLocal = faltaPermisoRedLocal(),
            peticion = peticion,
            redesWifi = redesWifi.map { (red, delRobot) -> RedWifi(red, delRobot) },
            ssid = ssid,
        )
        _fabricaSockets.value = decision.red?.socketFactory
        _estado.value = decision.estado
    }

    /** Modo manual: cualquier Wi-Fi conectada, tenga o no internet. */
    private inner class EscuchaWifi : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(cerrojo) {
                if (escucha !== this) return
                // Hasta conocer sus direcciones (onLinkPropertiesChanged llega justo después).
                if (network !in redesWifi) redesWifi[network] = false
                publicar()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val direcciones = linkProperties.linkAddresses.map { it.address.address to it.prefixLength }
            val delRobot = DecisorRed.esRedDelRobot(direcciones, ipRobotBytes)
            synchronized(cerrojo) {
                if (escucha !== this) return
                redesWifi[network] = delRobot
                publicar()
            }
        }

        override fun onLost(network: Network) {
            synchronized(cerrojo) {
                if (escucha !== this) return
                redesWifi.remove(network)
                publicar()
            }
        }
    }

    /** Modo automático: el AP pedido con WifiNetworkSpecifier. */
    private inner class PeticionAp : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(cerrojo) {
                if (callbackPeticion !== this) return
                peticion = PeticionAutomatica.Conectada(network)
                publicar()
            }
        }

        override fun onLost(network: Network) {
            synchronized(cerrojo) {
                if (callbackPeticion !== this) return
                // El pedido sigue registrado: si el AP vuelve, Android puede llamar otra vez a onAvailable.
                peticion = PeticionAutomatica.SinRed(DecisorRed.motivoPerdida(ssid))
                publicar()
            }
        }

        override fun onUnavailable() {
            synchronized(cerrojo) {
                if (callbackPeticion !== this) return
                // El usuario canceló o no se encontró el AP; el sistema ya liberó el pedido.
                callbackPeticion = null
                peticion = PeticionAutomatica.SinRed(DecisorRed.motivoNoEncontrada(ssid))
                publicar()
            }
        }
    }
}
