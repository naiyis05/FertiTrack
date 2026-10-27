package com.fertitrack.app.simulador

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Servidor HTTP/1.1 mínimo que imita al WebServer del ESP32 (solo debug, sin dependencias):
 * 127.0.0.1 con puerto efímero, UNA conexión a la vez y en orden de llegada, "Connection: close"
 * y las respuestas de [MaquinaRobotSimulada]. Un único hilo daemon.
 *
 * [simularPerdidaConexion]: durante ese tiempo acepta las conexiones pero no contesta (como un
 * ESP32 colgado o fuera de alcance), así que el cliente agota su timeout.
 */
internal class FakeFertiTrackServer(
    private val maquina: MaquinaRobotSimulada = MaquinaRobotSimulada(),
    /** Mismo reloj monotónico que la máquina: decide cuándo termina la pérdida de conexión. */
    private val reloj: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** Solo lo toman iniciar() y detener(); el hilo del servidor nunca (iniciar() lo espera). */
    private val cerrojo = Any()

    @Volatile
    private var servidor: ServerSocket? = null
    private var hilo: Thread? = null

    @Volatile
    private var url: String? = null

    /** Conexiones aceptadas y sin contestar durante la pérdida simulada. */
    private val silenciadas = mutableListOf<Socket>()

    @Volatile
    private var sinRespuestaHastaMs = Long.MIN_VALUE

    /** URL base mientras está en marcha, p. ej. "http://127.0.0.1:40123". */
    val urlBase: String? get() = url

    /**
     * Arranca (idempotente) y devuelve la URL base. El socket se abre en el hilo del servidor:
     * así se puede llamar desde el hilo principal sin NetworkOnMainThreadException.
     */
    fun iniciar(): String {
        synchronized(cerrojo) {
            url?.let { return it }
            val listo = CountDownLatch(1)
            var error: Throwable? = null
            val nuevo = Thread({ ejecutar(listo) { error = it } }, "FakeFertiTrackServer").apply { isDaemon = true }
            hilo = nuevo
            nuevo.start()
            if (!listo.await(ESPERA_ARRANQUE_MS, TimeUnit.MILLISECONDS) || servidor == null) {
                detenerInterno()
                throw IllegalStateException("No se pudo abrir el simulador", error)
            }
            return checkNotNull(url)
        }
    }

    fun detener() {
        synchronized(cerrojo) { detenerInterno() }
    }

    fun inyectarFalla(codigo: Int) = maquina.inyectarFalla(codigo)

    fun simularPerdidaConexion(segundos: Int) {
        sinRespuestaHastaMs = reloj() + segundos.coerceAtLeast(0) * 1_000L
    }

    private fun detenerInterno() {
        val actual = servidor
        servidor = null
        url = null
        try {
            actual?.close() // desbloquea accept()
        } catch (e: IOException) {
            // Ya cerrado.
        }
        cerrarSilenciadas()
        hilo?.let { if (it !== Thread.currentThread()) it.join(ESPERA_PARADA_MS) }
        hilo = null
    }

    private fun ejecutar(listo: CountDownLatch, alFallar: (Throwable) -> Unit) {
        val socketServidor = try {
            ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")).apply { soTimeout = SONDEO_ACCEPT_MS }
        } catch (e: IOException) {
            alFallar(e)
            listo.countDown()
            return
        }
        servidor = socketServidor
        url = "http://127.0.0.1:${socketServidor.localPort}"
        listo.countDown()
        while (!socketServidor.isClosed) {
            if (reloj() >= sinRespuestaHastaMs) cerrarSilenciadas()
            val cliente = try {
                socketServidor.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                break // cerrado por detener()
            }
            if (reloj() < sinRespuestaHastaMs) {
                synchronized(silenciadas) { silenciadas += cliente }
            } else {
                atender(cliente)
            }
        }
        cerrarSilenciadas()
    }

    private fun cerrarSilenciadas() {
        val copia = synchronized(silenciadas) { silenciadas.toList().also { silenciadas.clear() } }
        copia.forEach { cerrarSinError(it) }
    }

    /** Lee la petición, la pasa a la máquina y responde; siempre cierra la conexión. */
    private fun atender(cliente: Socket) {
        try {
            cliente.soTimeout = TIMEOUT_LECTURA_MS
            val cabecera = leerCabecera(cliente.getInputStream()) ?: return
            val primeraLinea = cabecera.lineSequence().firstOrNull().orEmpty().split(' ')
            val respuesta = if (primeraLinea.size < 2) {
                RespuestaSimulada(400, """{"error":"peticion mal formada"}""")
            } else {
                val (ruta, args) = separarDestino(primeraLinea[1])
                maquina.atender(primeraLinea[0].uppercase(), ruta, args)
            }
            escribir(cliente, respuesta)
        } catch (e: IOException) {
            // El cliente se fue (p. ej. Call.cancel()): nada que contestar.
        } finally {
            cerrarSinError(cliente)
        }
    }

    private fun escribir(cliente: Socket, respuesta: RespuestaSimulada) {
        val cuerpo = respuesta.cuerpo.toByteArray(Charsets.UTF_8)
        val cabeceras = buildString {
            append("HTTP/1.1 ").append(respuesta.codigo).append(' ').append(frase(respuesta.codigo)).append("\r\n")
            append("Content-Type: ").append(respuesta.tipoContenido).append("\r\n")
            append("Content-Length: ").append(cuerpo.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val salida = cliente.getOutputStream()
        salida.write(cabeceras.toByteArray(Charsets.ISO_8859_1))
        salida.write(cuerpo)
        salida.flush()
        cliente.shutdownOutput()
    }

    private fun cerrarSinError(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Ya cerrado.
        }
    }

    internal companion object {
        private const val BACKLOG = 8
        private const val SONDEO_ACCEPT_MS = 50
        private const val TIMEOUT_LECTURA_MS = 2_000
        private const val MAX_CABECERA = 8 * 1024
        private const val ESPERA_ARRANQUE_MS = 3_000L
        private const val ESPERA_PARADA_MS = 1_000L

        /** Hasta la línea en blanco que cierra las cabeceras; null si el cliente cerró antes. */
        fun leerCabecera(entrada: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var coincidencias = 0 // de "\r\n\r\n"
            while (bytes.size() < MAX_CABECERA) {
                val b = entrada.read()
                if (b < 0) return if (bytes.size() > 0) bytes.toString(Charsets.ISO_8859_1.name()) else null
                bytes.write(b)
                coincidencias = when {
                    (coincidencias == 0 || coincidencias == 2) && b == '\r'.code -> coincidencias + 1
                    (coincidencias == 1 || coincidencias == 3) && b == '\n'.code -> coincidencias + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (coincidencias == 4) break
            }
            return bytes.toString(Charsets.ISO_8859_1.name())
        }

        /** "/api/x?a=1&b=2" → ("/api/x", {a=1, b=2}); el primer valor de cada nombre gana. */
        fun separarDestino(destino: String): Pair<String, Map<String, String>> {
            val corte = destino.indexOf('?')
            val ruta = decodificar(if (corte < 0) destino else destino.substring(0, corte))
            val args = LinkedHashMap<String, String>()
            if (corte >= 0) {
                destino.substring(corte + 1).split('&').filter { it.isNotEmpty() }.forEach { par ->
                    val igual = par.indexOf('=')
                    val nombre = decodificar(if (igual < 0) par else par.substring(0, igual))
                    val valor = if (igual < 0) "" else decodificar(par.substring(igual + 1))
                    args.putIfAbsent(nombre, valor)
                }
            }
            return ruta to args
        }

        private fun decodificar(texto: String): String = try {
            URLDecoder.decode(texto, "UTF-8")
        } catch (e: IllegalArgumentException) {
            texto
        }

        private fun frase(codigo: Int) = when (codigo) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "Status"
        }
    }
}
