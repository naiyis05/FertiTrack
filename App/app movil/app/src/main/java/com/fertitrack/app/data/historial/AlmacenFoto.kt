package com.fertitrack.app.data.historial

import com.fertitrack.app.asistente.FotoRobot
import com.fertitrack.app.domain.modelo.EstadoMaquina
import com.fertitrack.app.domain.modelo.NivelSuelo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Guarda la última lectura del robot de cada fuente entre aperturas de la app. */
interface AlmacenFoto {
    /** null si no hay nada guardado o el archivo no se puede leer. */
    suspend fun cargar(fuente: FuenteDatos): FotoRobot?

    suspend fun guardar(fuente: FuenteDatos, foto: FotoRobot)
}

/** Un JSON por fuente en [carpeta], escrito igual que el historial (temporal y renombrado). */
class AlmacenFotoArchivo(private val carpeta: File) : AlmacenFoto {

    private val json = Json { ignoreUnknownKeys = true }
    private val escritura = Mutex()

    private fun archivo(fuente: FuenteDatos) = File(carpeta, "ultimo_estado_${fuente.name.lowercase()}.json")

    override suspend fun cargar(fuente: FuenteDatos): FotoRobot? = withContext(Dispatchers.IO) {
        val f = archivo(fuente)
        if (!f.exists()) return@withContext null
        try {
            json.decodeFromString(FotoDto.serializer(), f.readText()).aDominio()
        } catch (e: SerializationException) {
            null // Archivo dañado o de una versión incompatible: sin foto.
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun guardar(fuente: FuenteDatos, foto: FotoRobot) {
        withContext(Dispatchers.IO) {
            escritura.withLock { escribirAtomico(archivo(fuente), json.encodeToString(FotoDto.serializer(), FotoDto.desde(foto))) }
        }
    }
}

/** Los enumerados se guardan por nombre y se leen con tolerancia, como en [HistorialDto]. */
@Serializable
internal data class FotoDto(
    val momentoMs: Long,
    val estado: String,
    val estadoTexto: String,
    val totalZonas: Int,
    val distanciaCm: Float,
    val zonas: List<String>,
    val humedadPct: Float? = null,
    val codigoFalla: Int = 0,
    val autoActivo: Boolean = false,
    val descansoSeg: Long = 0,
) {
    fun aDominio() = FotoRobot(
        momentoMs = momentoMs,
        estado = EstadoMaquina.desdeCodigo(estado),
        estadoTexto = estadoTexto,
        totalZonas = totalZonas,
        distanciaCm = distanciaCm,
        zonas = zonas.map { nombre -> NivelSuelo.entries.firstOrNull { it.name == nombre } ?: NivelSuelo.DESCONOCIDO },
        humedadPct = humedadPct,
        codigoFalla = codigoFalla,
        autoActivo = autoActivo,
        descansoSeg = descansoSeg,
    )

    companion object {
        fun desde(f: FotoRobot) = FotoDto(
            momentoMs = f.momentoMs,
            estado = f.estado.name,
            estadoTexto = f.estadoTexto,
            totalZonas = f.totalZonas,
            distanciaCm = f.distanciaCm,
            zonas = f.zonas.map { it.name },
            humedadPct = f.humedadPct,
            codigoFalla = f.codigoFalla,
            autoActivo = f.autoActivo,
            descansoSeg = f.descansoSeg,
        )
    }
}
