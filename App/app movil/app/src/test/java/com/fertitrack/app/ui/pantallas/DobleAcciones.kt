package com.fertitrack.app.ui.pantallas

import com.fertitrack.app.ui.AccionConfirmable
import com.fertitrack.app.ui.AccionesUi
import com.fertitrack.app.ui.TipoExportacion

/** Doble de [AccionesUi] que solo registra las llamadas, en orden. */
class DobleAcciones : AccionesUi {
    val llamadas = mutableListOf<String>()
    val confirmacionesPedidas = mutableListOf<AccionConfirmable>()
    val respuestasFertilizar = mutableListOf<Boolean>()
    val mensajesMostrados = mutableListOf<Long>()

    fun veces(nombre: String): Int = llamadas.count { it == nombre || it.startsWith("$nombre(") }

    override fun pedirConfirmacion(accion: AccionConfirmable) {
        llamadas += "pedirConfirmacion($accion)"
        confirmacionesPedidas += accion
    }

    override fun confirmar() { llamadas += "confirmar" }
    override fun cancelarConfirmacion() { llamadas += "cancelarConfirmacion" }
    override fun detenerEmergencia() { llamadas += "detenerEmergencia" }
    override fun desactivarAuto() { llamadas += "desactivarAuto" }
    override fun guardarDescanso(descansoSeg: Long) { llamadas += "guardarDescanso($descansoSeg)" }

    override fun responderSugerenciaFertilizar(aceptar: Boolean) {
        llamadas += "responderSugerenciaFertilizar($aceptar)"
        respuestasFertilizar += aceptar
    }

    override fun conectarWifiRobot() { llamadas += "conectarWifiRobot" }
    override fun permisosActualizados() { llamadas += "permisosActualizados" }
    override fun usarSimulador(activo: Boolean) { llamadas += "usarSimulador($activo)" }
    override fun simularFalla(codigo: Int) { llamadas += "simularFalla($codigo)" }
    override fun simularPerdidaConexion(segundos: Int) { llamadas += "simularPerdidaConexion($segundos)" }

    override fun exportar(tipo: TipoExportacion, destino: String) { llamadas += "exportar($tipo, $destino)" }

    override fun mensajeMostrado(id: Long) {
        llamadas += "mensajeMostrado($id)"
        mensajesMostrados += id
    }

    override fun alPrimerPlano() { llamadas += "alPrimerPlano" }
    override fun alSegundoPlano() { llamadas += "alSegundoPlano" }
}
