package com.fertitrack.app.ui.pantallas

import com.fertitrack.app.asistente.AccionAsistente
import com.fertitrack.app.asistente.AccionesAsistente
import com.fertitrack.app.asistente.Destino

/** Doble de [AccionesAsistente] que solo registra las llamadas, en orden. */
class DobleAsistente : AccionesAsistente {
    val llamadas = mutableListOf<String>()

    override fun abrir() { llamadas += "abrir" }
    override fun cerrar() { llamadas += "cerrar" }
    override fun enviarTexto(texto: String) { llamadas += "enviarTexto($texto)" }
    override fun alternarMicrofono() { llamadas += "alternarMicrofono" }
    override fun cancelar() { llamadas += "cancelar" }
    override fun alternarVoz() { llamadas += "alternarVoz" }
    override fun pantallaActual(destino: Destino) { llamadas += "pantallaActual($destino)" }
    override fun permisoMicrofono(concedido: Boolean) { llamadas += "permisoMicrofono($concedido)" }
    override fun repetirAccion(accion: AccionAsistente) { llamadas += "repetirAccion($accion)" }
}
