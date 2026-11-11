package com.fertitrack.app.data

import com.fertitrack.app.asistente.FotoRobot
import com.fertitrack.app.data.historial.AlmacenFoto
import com.fertitrack.app.data.historial.FuenteDatos

/** Almacén de la última lectura en memoria para pruebas: registra cada escritura. */
class AlmacenFotoEnMemoria(inicial: Map<FuenteDatos, FotoRobot> = emptyMap()) : AlmacenFoto {
    val guardado = inicial.toMutableMap()
    val escrituras = mutableListOf<Pair<FuenteDatos, FotoRobot>>()

    override suspend fun cargar(fuente: FuenteDatos): FotoRobot? = guardado[fuente]

    override suspend fun guardar(fuente: FuenteDatos, foto: FotoRobot) {
        guardado[fuente] = foto
        escrituras += fuente to foto
    }
}
