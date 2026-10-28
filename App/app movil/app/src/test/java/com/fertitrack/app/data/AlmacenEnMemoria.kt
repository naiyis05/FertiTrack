package com.fertitrack.app.data

import com.fertitrack.app.data.historial.AlmacenHistorial
import com.fertitrack.app.data.historial.FuenteDatos
import com.fertitrack.app.domain.sesion.ResumenSesion

/** Almacén de historial en memoria para pruebas: registra cada escritura. */
class AlmacenEnMemoria(inicial: Map<FuenteDatos, ResumenSesion> = emptyMap()) : AlmacenHistorial {
    val guardado = inicial.toMutableMap()
    val escrituras = mutableListOf<Pair<FuenteDatos, ResumenSesion>>()

    override suspend fun cargar(fuente: FuenteDatos): ResumenSesion? = guardado[fuente]

    override suspend fun guardar(fuente: FuenteDatos, resumen: ResumenSesion) {
        guardado[fuente] = resumen
        escrituras += fuente to resumen
    }
}
