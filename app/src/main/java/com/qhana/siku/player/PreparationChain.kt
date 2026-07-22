package com.qhana.siku.player

import android.content.Context
import android.util.Log
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import java.io.File

/**
 * Representa el contexto compartido a través de la cadena de preparación.
 */
data class PreparationContext(
    var song: Song,
    var urlRefreshed: Boolean = false,
    var willStream: Boolean = false
)

/**
 * Interfaz para un paso en la cadena de preparación de reproducción.
 */
interface PreparationStep {
    suspend fun execute(context: PreparationContext): StepResult

    sealed class StepResult {
        object Continue : StepResult()
        data class Error(val message: String) : StepResult()
        object Abort : StepResult() // Éxito inmediato o parada controlada
    }
}

/**
 * Paso 1: Verifica si el archivo existe localmente (por path o por ID en caché).
 *
 * SOLO comprueba existencia y tamaño. NO analiza el archivo con [AudioFileAnalyzer]: que el
 * retriever no pueda leerlo NO prueba que el audio esté roto (un timeout, un FLAC grande o un
 * contenedor que MediaMetadataRetriever no digiere dan `isValid=false` sobre archivos que
 * ExoPlayer reproduce perfectamente). Antes, ese falso negativo BORRABA el archivo descargado
 * y limpiaba el path → la canción pasaba a "no descargada" y se ponía a streamear en cada
 * reproducción. Si el audio está de verdad corrupto, lo detecta ExoPlayer al reproducir y lo
 * maneja `PlaybackErrorRecoveryUseCase` (que sí sabe reintentar, saltar o marcar corrupta).
 */
class LocalFileCheckStep(
    private val context: Context,
    private val musicRepository: IMusicRepository
) : PreparationStep {
    override suspend fun execute(context: PreparationContext): PreparationStep.StepResult {
        val song = context.song

        // Búsqueda por ID en filesDir/music/. Tras la unificación, los archivos descargados
        // siguen el patrón "${id}.${ext}" (sin título saneado). El startsWith("${id}.") es
        // estrictamente más selectivo que el viejo "${id}_" (que podía colisionar entre IDs
        // que fueran prefijo de otros).
        val musicDir = File(this.context.filesDir, "music")
        val prefix = "${song.id}."
        val existingLocalFile = if (musicDir.exists()) {
            musicDir.listFiles { file -> file.name.startsWith(prefix) && file.length() > 0 }?.firstOrNull()
        } else null

        if (existingLocalFile != null && !song.path.startsWith("file://")) {
            val localUri = "file://${existingLocalFile.absolutePath}"
            musicRepository.updateSongUrl(song.id, localUri)
            context.song = song.copy(path = localUri)
            return PreparationStep.StepResult.Continue
        }

        // El path apunta a un archivo que ya no está (borrado a mano, limpieza del sistema) o
        // a una descarga truncada de 0 bytes: solo en ESOS casos se invalida.
        if (song.path.startsWith("file://")) {
            val file = File(song.path.removePrefix("file://"))
            if (!file.exists() || file.length() == 0L) {
                if (file.exists()) file.delete()
                musicRepository.updateSongUrl(song.id, "") // Invalidar path en DB
                context.song = song.copy(path = "")
            }
        }

        return PreparationStep.StepResult.Continue
    }
}

/**
 * Paso 2: Refresca la URL si no es local y no está en caché.
 */
class UrlRefreshStep(
    private val musicRepository: IMusicRepository,
    private val sourceRegistry: MusicSourceRegistry,
    private val isCachedPredicate: (String) -> Boolean
) : PreparationStep {
    override suspend fun execute(context: PreparationContext): PreparationStep.StepResult {
        val song = context.song
        val isLocal = song.path.startsWith("file://") || song.path.startsWith("content://")
        val isCached = isCachedPredicate(song.id)

        if (!isLocal && !isCached && (song.path.isBlank() || song.remoteId != null)) {
            val remoteId = song.remoteId
            if (remoteId == null) {
                Log.w("UrlRefreshStep", "Song ${song.id} needs URL but has no remoteId (path='${song.path.take(30)}')")
                return PreparationStep.StepResult.Error("Remote song without sync ID")
            }
            // El registro rutea a la fuente correcta y encapsula la caché de URL.
            val freshUrl = sourceRegistry.resolveDownloadUrl(song)
            if (freshUrl != null) {
                context.song = song.copy(path = freshUrl)
                musicRepository.updateSongUrl(song.id, freshUrl)
                context.urlRefreshed = true
            } else {
                return PreparationStep.StepResult.Error("No se pudo obtener enlace de descarga")
            }
        }
        
        context.willStream = !isLocal && !context.song.path.startsWith("file://")
        return PreparationStep.StepResult.Continue
    }
}

/**
 * Paso 3: Extrae metadatos faltantes si es necesario.
 *
 * Nota: los errores aquí se consideran no-fatales. La canción se puede reproducir
 * con metadata incompleta (título = nombre de archivo, artista = "Unknown Artist").
 * Por eso se loguea con WARN y se continúa con la cadena.
 */
class MetadataFetchStep(
    private val musicRepository: IMusicRepository,
    private val sourceRegistry: MusicSourceRegistry
) : PreparationStep {
    override suspend fun execute(context: PreparationContext): PreparationStep.StepResult {
        val song = context.song
        if (needsMetadata(song)) {
            try {
                val withMetadata = sourceRegistry.extractMetadata(song)
                if (withMetadata != song) {
                    context.song = withMetadata
                    musicRepository.updateSongMetadata(withMetadata)
                }
            } catch (e: Exception) {
                // No bloqueamos la reproducción si falla la metadata.
                // Registramos con clase de excepción para diagnosticar (red vs parser vs otro).
                val err = "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
                Log.w("MetadataFetchStep", "Metadata no disponible para songId=${song.id} ($err). Reproduciendo sin ella.")
            }
        }
        return PreparationStep.StepResult.Continue
    }

    /**
     * NO incluye `albumArtUri == null`: un archivo puede legítimamente NO tener carátula
     * embebida, y ese estado es PERMANENTE. Usarlo como criterio hacía que toda canción sin
     * carátula re-analizara el audio en CADA reproducción — y si estaba en streaming, eso es
     * `analyzeUrl` (hasta 15s × 2 reintentos) en el camino crítico del play: el botón se
     * quedaba en "loading" eterno. La carátula ausente la recuperan `ArtworkWorker` /
     * `ArtworkHealingManager`, fuera del camino de reproducción, que es donde corresponde.
     */
    private fun needsMetadata(song: Song): Boolean {
        return song.artist == AppConfig.UNKNOWN_ARTIST ||
            song.album == AppConfig.UNKNOWN_ALBUM ||
            song.duration == 0L
    }
}
