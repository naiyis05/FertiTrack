package com.fertitrack.app.data.red

import com.fertitrack.app.domain.conexion.EstadoRed
import javax.net.SocketFactory
import kotlinx.coroutines.flow.StateFlow

/**
 * Elige por qué red salen las peticiones al robot. El AP del ESP32 no tiene internet, así que
 * Android puede preferir los datos móviles: las peticiones deben ir ligadas a la red Wi-Fi del
 * robot con su [fabricaSockets]. Nunca pide permisos (eso lo hace la UI).
 */
interface EnlaceRed {
    /** Estado para la UI, sin contar el simulador (eso lo decide el repositorio). */
    val estado: StateFlow<EstadoRed>

    /** Sockets ligados a la red Wi-Fi del robot; null = ruta por defecto del sistema. */
    val fabricaSockets: StateFlow<SocketFactory?>

    /** Empieza a vigilar las redes Wi-Fi (modo manual). Idempotente. */
    fun activar()

    /** Pide el AP del robot al sistema (WifiNetworkSpecifier, Android 10+). */
    fun conectarAutomatico()

    /** Vuelve a comprobar permisos y redes. */
    fun reevaluar()

    /** Suelta los callbacks y el pedido de red. */
    fun liberar()
}
