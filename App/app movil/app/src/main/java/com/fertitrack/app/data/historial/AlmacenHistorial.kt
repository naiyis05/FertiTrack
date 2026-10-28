package com.fertitrack.app.data.historial

import com.fertitrack.app.domain.sesion.ResumenSesion

/** De dónde vienen los datos: cada fuente guarda su propio historial (el simulado no ensucia el real). */
enum class FuenteDatos { ROBOT, SIMULADOR }

/** Guarda el historial (mediciones, riegos, bombeos y eventos) entre aperturas de la app. */
interface AlmacenHistorial {
    /** null si no hay nada guardado o el archivo no se puede leer. */
    suspend fun cargar(fuente: FuenteDatos): ResumenSesion?

    suspend fun guardar(fuente: FuenteDatos, resumen: ResumenSesion)
}
