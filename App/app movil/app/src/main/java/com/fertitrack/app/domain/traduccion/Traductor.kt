package com.fertitrack.app.domain.traduccion

import com.fertitrack.app.domain.conexion.EstadoConexion
import com.fertitrack.app.domain.modelo.Actividad
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.EstadoRobot
import com.fertitrack.app.domain.modelo.ModoInicio
import com.fertitrack.app.domain.modelo.ModoRutina
import com.fertitrack.app.domain.modelo.NivelSuelo
import com.fertitrack.app.domain.modelo.ResultadoComando

enum class CategoriaEstado {
    REPOSO, SENSADO, RIEGO, FERTILIZACION, RECUPERACION, ARRANQUE, PRUEBA, EMERGENCIA, DESCONOCIDO
}

data class DescripcionEstado(
    /** Frase corta y legible, p. ej. "Regando zona 3 de 8". */
    val titulo: String,
    /** Detalle de la fase, p. ej. "Bajando el brazo de riego". */
    val detalle: String,
    val categoria: CategoriaEstado,
)

data class DescripcionFalla(
    val codigo: Int,
    val titulo: String,
    val explicacion: String,
    val accionSugerida: String,
)

/** Traduce los códigos del firmware a textos en español para la interfaz. Lógica pura. */
object Traductor {

    fun describirEstado(robot: EstadoRobot): DescripcionEstado {
        val zona = textoZona(robot)
        return when (robot.estado) {
            EstadoMaquina.ESPERANDO -> DescripcionEstado(
                titulo = if (robot.proximoCicloSeg != null) "En espera del próximo ciclo" else "En espera",
                detalle = if (robot.distanciaConfigurada) {
                    "En el origen, listo para recibir órdenes"
                } else {
                    "Falta configurar la distancia del recorrido"
                },
                categoria = CategoriaEstado.REPOSO,
            )
            EstadoMaquina.SENSADO_AVANZANDO -> DescripcionEstado(
                "Avanzando a la $zona", "Se mueve a la siguiente zona para medir", CategoriaEstado.SENSADO,
            )
            EstadoMaquina.SENSADO_MIDIENDO -> DescripcionEstado(
                "Sensando $zona", "Midiendo la humedad del suelo", CategoriaEstado.SENSADO,
            )
            EstadoMaquina.SENSADO_RETORNANDO -> DescripcionEstado(
                "Volviendo al origen", "Sensado terminado", CategoriaEstado.SENSADO,
            )
            EstadoMaquina.RIEGO_BAJANDO_BRAZO -> DescripcionEstado(
                "Preparando el riego en la $zona", "Bajando el brazo de riego", CategoriaEstado.RIEGO,
            )
            EstadoMaquina.RIEGO_AVANZANDO -> if (robot.bombaRiegoEncendida) {
                DescripcionEstado("Regando $zona", "Bomba de riego encendida", CategoriaEstado.RIEGO)
            } else {
                DescripcionEstado(
                    "Cruzando zonas sin regar",
                    "Avanza sobre zonas que no están secas, con la bomba apagada",
                    CategoriaEstado.RIEGO,
                )
            }
            EstadoMaquina.RIEGO_SUBIENDO_BRAZO -> DescripcionEstado(
                "Terminando el riego", "Subiendo el brazo de riego", CategoriaEstado.RIEGO,
            )
            EstadoMaquina.RIEGO_RETORNANDO -> DescripcionEstado(
                "Volviendo al origen", "Riego terminado", CategoriaEstado.RIEGO,
            )
            EstadoMaquina.FERTI_BAJANDO_BRAZO -> DescripcionEstado(
                "Preparando la fertilización", "Bajando el brazo de fertilización", CategoriaEstado.FERTILIZACION,
            )
            EstadoMaquina.FERTI_AVANZANDO -> DescripcionEstado(
                "Fertilizando",
                "Recorre el riel con la bomba de fertilizante encendida",
                CategoriaEstado.FERTILIZACION,
            )
            EstadoMaquina.FERTI_SUBIENDO_BRAZO -> DescripcionEstado(
                "Terminando la fertilización", "Subiendo el brazo de fertilización", CategoriaEstado.FERTILIZACION,
            )
            EstadoMaquina.FERTI_RETORNANDO -> DescripcionEstado(
                "Volviendo al origen", "Fertilización terminada", CategoriaEstado.FERTILIZACION,
            )
            EstadoMaquina.RECUP_ESPERA -> DescripcionEstado(
                "Recuperación tras emergencia", "Preparando la recuperación", CategoriaEstado.RECUPERACION,
            )
            EstadoMaquina.RECUP_BRAZO_RIEGO -> DescripcionEstado(
                "Recuperación tras emergencia", "Recogiendo el brazo de riego", CategoriaEstado.RECUPERACION,
            )
            EstadoMaquina.RECUP_BRAZO_FERTI -> DescripcionEstado(
                "Recuperación tras emergencia", "Recogiendo el brazo de fertilización", CategoriaEstado.RECUPERACION,
            )
            EstadoMaquina.RECUP_RETORNANDO -> DescripcionEstado(
                "Recuperación tras emergencia", "Volviendo al origen", CategoriaEstado.RECUPERACION,
            )
            EstadoMaquina.ARRANQUE -> DescripcionEstado(
                "Arrancando", "En unos segundos recoge los brazos y vuelve al origen", CategoriaEstado.ARRANQUE,
            )
            EstadoMaquina.PRUEBA_AVANZANDO -> DescripcionEstado(
                "Prueba de distancia", "Avanzando la distancia pedida", CategoriaEstado.PRUEBA,
            )
            EstadoMaquina.PRUEBA_LISTA -> DescripcionEstado(
                "Prueba de distancia lista",
                "Mide cuánto avanzó y luego vuelve al origen",
                CategoriaEstado.PRUEBA,
            )
            EstadoMaquina.PRUEBA_RETORNANDO -> DescripcionEstado(
                "Prueba de distancia", "Volviendo al origen", CategoriaEstado.PRUEBA,
            )
            EstadoMaquina.PRUEBA_SENSOR -> DescripcionEstado(
                "Prueba de sensor", "Midiendo la humedad (unos segundos)", CategoriaEstado.PRUEBA,
            )
            EstadoMaquina.PRUEBA_BRAZO -> DescripcionEstado(
                "Prueba de brazo",
                "Extendiendo o recogiendo un brazo desde la página del Maestro",
                CategoriaEstado.PRUEBA,
            )
            EstadoMaquina.EMERGENCIA -> {
                val falla = describirFalla(robot.codigoFalla)
                DescripcionEstado(
                    "Emergencia: ${falla.titulo}",
                    "Bombas apagadas y motores detenidos",
                    CategoriaEstado.EMERGENCIA,
                )
            }
            EstadoMaquina.DESCONOCIDO -> DescripcionEstado(
                "Estado desconocido (${robot.estadoTexto})",
                "El firmware envió un estado que la app no reconoce",
                CategoriaEstado.DESCONOCIDO,
            )
        }
    }

    private fun textoZona(robot: EstadoRobot): String =
        if (robot.totalZonas > 0 && robot.zonaActual > 0) {
            "zona ${robot.zonaActual} de ${robot.totalZonas}"
        } else {
            "siguiente zona"
        }

    /** Nombre legible de un estado; para DESCONOCIDO incluye [textoOriginal]. */
    fun nombreEstado(estado: EstadoMaquina, textoOriginal: String = estado.name): String = when (estado) {
        EstadoMaquina.ESPERANDO -> "En espera"
        EstadoMaquina.SENSADO_AVANZANDO -> "Avanzando a la zona"
        EstadoMaquina.SENSADO_MIDIENDO -> "Midiendo humedad"
        EstadoMaquina.SENSADO_RETORNANDO -> "Volviendo al origen tras sensar"
        EstadoMaquina.RIEGO_BAJANDO_BRAZO -> "Bajando brazo de riego"
        EstadoMaquina.RIEGO_AVANZANDO -> "Recorriendo zonas de riego"
        EstadoMaquina.RIEGO_SUBIENDO_BRAZO -> "Subiendo brazo de riego"
        EstadoMaquina.RIEGO_RETORNANDO -> "Volviendo al origen tras regar"
        EstadoMaquina.FERTI_BAJANDO_BRAZO -> "Bajando brazo de fertilización"
        EstadoMaquina.FERTI_AVANZANDO -> "Fertilizando"
        EstadoMaquina.FERTI_SUBIENDO_BRAZO -> "Subiendo brazo de fertilización"
        EstadoMaquina.FERTI_RETORNANDO -> "Volviendo al origen tras fertilizar"
        EstadoMaquina.RECUP_ESPERA -> "Recuperación: preparando"
        EstadoMaquina.RECUP_BRAZO_RIEGO -> "Recuperación: recogiendo brazo de riego"
        EstadoMaquina.RECUP_BRAZO_FERTI -> "Recuperación: recogiendo brazo de fertilización"
        EstadoMaquina.RECUP_RETORNANDO -> "Recuperación: volviendo al origen"
        EstadoMaquina.ARRANQUE -> "Arrancando"
        EstadoMaquina.PRUEBA_AVANZANDO -> "Prueba de distancia: avanzando"
        EstadoMaquina.PRUEBA_LISTA -> "Prueba de distancia lista"
        EstadoMaquina.PRUEBA_RETORNANDO -> "Prueba de distancia: volviendo"
        EstadoMaquina.PRUEBA_SENSOR -> "Prueba de sensor: midiendo"
        EstadoMaquina.PRUEBA_BRAZO -> "Prueba de brazo"
        EstadoMaquina.EMERGENCIA -> "Emergencia"
        EstadoMaquina.DESCONOCIDO -> "Estado desconocido ($textoOriginal)"
    }

    fun nombreNivel(nivel: NivelSuelo): String = when (nivel) {
        NivelSuelo.SIN_MEDIR -> "Sin medir"
        NivelSuelo.SECA -> "Seca"
        NivelSuelo.INTERMEDIA -> "Intermedia"
        NivelSuelo.HUMEDA -> "Húmeda"
        NivelSuelo.SOBRE_HUMEDA -> "Sobre-húmeda"
        NivelSuelo.DESCONOCIDO -> "Desconocido"
    }

    fun nombreActividad(actividad: Actividad): String = when (actividad) {
        Actividad.REPOSO -> "En reposo"
        Actividad.SENSANDO -> "Sensando"
        Actividad.REGANDO -> "Regando"
        Actividad.FERTILIZANDO -> "Fertilizando"
        Actividad.EN_MOVIMIENTO -> "En movimiento"
        Actividad.DESCONOCIDA -> "Desconocida"
    }

    fun nombreRutina(modo: ModoRutina): String = when (modo) {
        ModoRutina.NINGUNA -> "Ninguna"
        ModoRutina.SENSADO -> "Sensado"
        ModoRutina.RIEGO -> "Riego"
        ModoRutina.FERTILIZACION -> "Fertilización"
        ModoRutina.DESCONOCIDO -> "Desconocida"
    }

    fun nombreModoInicio(modo: ModoInicio): String = when (modo) {
        ModoInicio.CICLO -> "Ciclo completo (sensar + regar)"
        ModoInicio.SENSADO -> "Solo sensar (sin regar)"
        ModoInicio.FERTILIZAR -> "Fertilización"
    }

    /** Significado de un código de falla y la acción sugerida (también para códigos desconocidos). */
    fun describirFalla(codigo: Int): DescripcionFalla = when (codigo) {
        0 -> DescripcionFalla(0, "Sin falla", "El robot funciona con normalidad.", "Ninguna.")
        1 -> DescripcionFalla(
            1,
            "ESP_A no responde",
            "La placa del motor y los brazos no contestó a tiempo y el robot se detuvo por seguridad.",
            "Revisa la alimentación de ESP_A y que esté encendida cerca del Maestro; luego pulsa Reiniciar.",
        )
        2 -> DescripcionFalla(
            2,
            "ESP_B no responde",
            "La placa del sensor de humedad no contestó a tiempo y el robot se detuvo por seguridad.",
            "Revisa la alimentación y el enlace de ESP_B; luego pulsa Reiniciar.",
        )
        3 -> DescripcionFalla(
            3,
            "Sensor sin lectura válida",
            "El sensor de humedad devolvió una lectura imposible (desconectado o en cortocircuito).",
            "Revisa el cable y el sensor de humedad; luego pulsa Reiniciar.",
        )
        8 -> DescripcionFalla(
            8,
            "Parada desde la app",
            "Se pulsó DETENER: se apagaron las bombas y se detuvieron los motores.",
            "Comprueba que el riel esté despejado y pulsa Reiniciar: el robot recogerá los brazos y " +
                "volverá al origen. El modo automático quedará apagado.",
        )
        9 -> DescripcionFalla(
            9,
            "Botón de emergencia",
            "Se pulsó el botón físico de emergencia del robot.",
            "Rearma (suelta) el botón físico y luego pulsa Reiniciar.",
        )
        else -> DescripcionFalla(
            codigo,
            "Falla desconocida (código $codigo)",
            "El firmware informó un código de falla que la app no conoce.",
            "Revisa el robot y pulsa Reiniciar; si se repite, revisa el monitor serie del Maestro.",
        )
    }

    /** Por qué las rutinas no se pueden iniciar ahora; null si sí se pueden. */
    fun motivoRutinasNoDisponibles(robot: EstadoRobot?, conexion: EstadoConexion): String? {
        if (conexion !is EstadoConexion.Conectado || robot == null) {
            return when (conexion) {
                EstadoConexion.Pausado -> "La app está en pausa"
                EstadoConexion.Conectando -> "Conectando con el robot…"
                else -> "Sin conexión con el robot"
            }
        }
        if (robot.hayFalla || robot.estado == EstadoMaquina.EMERGENCIA) {
            return "Hay una falla activa: reinicia primero"
        }
        return when (robot.estado) {
            EstadoMaquina.ESPERANDO -> if (robot.distanciaConfigurada) {
                null
            } else {
                "Configura primero la distancia del recorrido desde la página web del robot " +
                    "(http://192.168.4.1); la pantalla de configuración llega en la Fase 2"
            }
            EstadoMaquina.PRUEBA_LISTA -> "Hay una prueba de distancia pendiente: vuelve al origen primero"
            EstadoMaquina.ARRANQUE,
            EstadoMaquina.RECUP_ESPERA,
            EstadoMaquina.RECUP_BRAZO_RIEGO,
            EstadoMaquina.RECUP_BRAZO_FERTI,
            EstadoMaquina.RECUP_RETORNANDO,
            -> "El robot se está preparando; espera unos segundos"
            EstadoMaquina.DESCONOCIDO -> "El robot está en un estado desconocido (${robot.estadoTexto})"
            else -> "El robot está ocupado: ${describirEstado(robot).titulo.replaceFirstChar { it.lowercase() }}"
        }
    }

    /** Texto amistoso para el resultado de [accion] (p. ej. un 409 no suena a error técnico). */
    fun mensajeResultado(accion: String, resultado: ResultadoComando): String = when (resultado) {
        is ResultadoComando.Exito ->
            if (resultado.mensaje.isNullOrBlank()) "$accion: orden enviada" else "$accion: ${resultado.mensaje}"
        is ResultadoComando.Ocupado -> "$accion: ${traducirTextoFirmware(resultado.mensaje)}"
        is ResultadoComando.Rechazado ->
            "$accion: el robot rechazó la orden (${traducirTextoFirmware(resultado.mensaje)})"
        is ResultadoComando.ErrorRobot -> buildString {
            append("$accion: el robot respondió con un error (código ${resultado.codigoHttp})")
            if (resultado.mensaje.isNotBlank()) append(": ").append(resultado.mensaje)
        }
        is ResultadoComando.SinRespuesta -> "$accion: no hubo respuesta del robot; revisa la conexión Wi-Fi"
    }

    /** Traduce los textos de error conocidos del firmware a frases claras. */
    fun traducirTextoFirmware(texto: String): String {
        val t = texto.trim().lowercase()
        return when {
            t.isEmpty() -> "el robot no aceptó la orden ahora"
            "sistema ocupado" in t ->
                "el robot está ocupado ahora mismo; espera a que termine y vuelve a intentarlo"
            "configura primero la distancia" in t -> "antes hay que configurar la distancia del recorrido"
            "esp_b no esta conectada" in t -> "el sensor (ESP_B) no está conectado"
            "solo despues de una prueba" in t -> "primero hay que hacer una prueba de movimiento"
            "modo invalido" in t -> "el modo pedido no es válido"
            "no hay lectura valida" in t -> "no hay una lectura válida del sensor: mide primero"
            "no se pudo iniciar" in t -> "el robot no pudo iniciar la rutina"
            else -> texto.trim()
        }
    }

    /** Explicación para el usuario de por qué la app pide [permiso] (nombre android.permission.*). */
    fun explicarPermiso(permiso: String): String = when (permiso) {
        "android.permission.NEARBY_WIFI_DEVICES" ->
            "Android lo exige para que la app pida la conexión al Wi-Fi del robot. " +
                "No se usa para conocer tu ubicación."
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION" ->
            "En esta versión de Android, conectarse a una red Wi-Fi concreta requiere el permiso " +
                "de ubicación. La app no registra ni guarda tu ubicación."
        "android.permission.ACCESS_LOCAL_NETWORK" ->
            "Permite hablar con dispositivos de la red local, como el robot en su red Wi-Fi."
        else -> "Necesario para comunicarse con el robot."
    }
}
