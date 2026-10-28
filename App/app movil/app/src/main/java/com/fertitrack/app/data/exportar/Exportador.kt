package com.fertitrack.app.data.exportar

import android.content.Context
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Escribe un archivo exportado en el documento que eligió el usuario. */
fun interface Exportador {
    /** [destino]: URI content:// del selector de archivos. false si no se pudo escribir. */
    suspend fun escribir(destino: String, contenido: String): Boolean
}

/** Con el selector del sistema (Storage Access Framework): no hace falta ningún permiso. */
class ExportadorAndroid(contexto: Context) : Exportador {
    private val resolver = contexto.applicationContext.contentResolver

    override suspend fun escribir(destino: String, contenido: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // "wt": reemplaza el contenido si el usuario eligió un archivo existente.
            resolver.openOutputStream(destino.toUri(), "wt")?.use { it.write(contenido.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
