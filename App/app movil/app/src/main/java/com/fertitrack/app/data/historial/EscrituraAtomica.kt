package com.fertitrack.app.data.historial

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Escribe en un temporal y lo renombra: si la app muere a mitad de una escritura, queda el
 * archivo anterior entero. Sin espacio o sin permiso no hace nada: los datos siguen en
 * memoria y se reintenta con el próximo cambio. Nunca debe tumbar el seguimiento del robot.
 */
internal fun escribirAtomico(destino: File, texto: String) {
    try {
        destino.parentFile?.mkdirs()
        val temporal = File(destino.parentFile, "${destino.name}.tmp")
        temporal.writeText(texto)
        Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: IOException) {
        // Ver arriba: se ignora a propósito.
    }
}
