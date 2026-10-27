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

    fun describirEstado(robot: EstadoRobot): DescripcionEstado = TODO("Implementa: agente de dominio")

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
    fun describirFalla(codigo: Int): DescripcionFalla = TODO("Implementa: agente de dominio")

    /** Por qué las rutinas no se pueden iniciar ahora; null si sí se pueden. */
    fun motivoRutinasNoDisponibles(robot: EstadoRobot?, conexion: EstadoConexion): String? =
        TODO("Implementa: agente de dominio")

    /** Texto amistoso para el resultado de [accion] (p. ej. un 409 no suena a error técnico). */
    fun mensajeResultado(accion: String, resultado: ResultadoComando): String =
        TODO("Implementa: agente de dominio")

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
