package com.fertitrack.app.ui

/** Lo que la interfaz puede pedir. MainViewModel lo implementa; las pruebas usan un doble. */
interface AccionesUi {
    /** Abre la confirmación de una acción física; nada se envía hasta [confirmar]. */
    fun pedirConfirmacion(accion: AccionConfirmable)

    /** Ejecuta la acción confirmada si el robot sigue disponible. */
    fun confirmar()

    fun cancelarConfirmacion()

    /** Parada de emergencia: sin confirmación y siempre disponible. */
    fun detenerEmergencia()

    fun desactivarAuto()

    /** Cambia el descanso entre ciclos sin cambiar si el modo automático está activo. */
    fun guardarDescanso(descansoSeg: Long)

    /** Respuesta a la tarjeta "¿Deseas fertilizar?"; aceptar abre la confirmación. */
    fun responderSugerenciaFertilizar(aceptar: Boolean)

    fun conectarWifiRobot()

    /** Llamar tras el diálogo de permisos o al volver de los ajustes de Wi-Fi. */
    fun permisosActualizados()

    fun usarSimulador(activo: Boolean)

    fun simularFalla(codigo: Int)

    fun simularPerdidaConexion(segundos: Int)

    fun mensajeMostrado(id: Long)

    fun alPrimerPlano()

    fun alSegundoPlano()
}
