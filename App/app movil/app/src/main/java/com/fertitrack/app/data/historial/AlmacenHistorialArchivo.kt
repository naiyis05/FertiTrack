package com.fertitrack.app.data.historial

import com.fertitrack.app.domain.sesion.ResumenSesion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Historial en un JSON por fuente dentro de [carpeta] (los archivos privados de la app, fuera
 * de las copias de seguridad). Se escribe en un temporal y se renombra: si la app muere a
 * mitad de una escritura, queda el archivo anterior entero.
 */
class AlmacenHistorialArchivo(private val carpeta: File) : AlmacenHistorial {

    private val json = Json { ignoreUnknownKeys = true }
    private val escritura = Mutex()

    private fun archivo(fuente: FuenteDatos) = File(carpeta, "historial_${fuente.name.lowercase()}.json")

    override suspend fun cargar(fuente: FuenteDatos): ResumenSesion? = withContext(Dispatchers.IO) {
        val f = archivo(fuente)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString(HistorialDto.serializer(), f.readText()).aDominio()
        } catch (e: SerializationException) {
            null // Archivo dañado o de una versión incompatible: se empieza de cero.
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun guardar(fuente: FuenteDatos, resumen: ResumenSesion) {
        withContext(Dispatchers.IO) {
            escritura.withLock {
                escribirAtomico(archivo(fuente), json.encodeToString(HistorialDto.serializer(), HistorialDto.desde(resumen)))
            }
        }
    }
}
