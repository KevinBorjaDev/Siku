package com.qhana.siku.data.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resultado del análisis de un archivo de audio.
 */
data class FileAnalysisResult(
    val extension: String,
    val isValid: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val duration: Long,
    val embeddedArt: ByteArray?,
    val error: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileAnalysisResult

        if (extension != other.extension) return false
        if (isValid != other.isValid) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (genre != other.genre) return false
        if (duration != other.duration) return false
        if (embeddedArt != null) {
            if (other.embeddedArt == null) return false
            if (!embeddedArt.contentEquals(other.embeddedArt)) return false
        } else if (other.embeddedArt != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = extension.hashCode()
        result = 31 * result + isValid.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + duration.hashCode()
        result = 31 * result + (embeddedArt?.contentHashCode() ?: 0)
        return result
    }
}

@Singleton
class AudioFileAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val retrieverSemaphore = Semaphore(MAX_CONCURRENT_RETRIEVERS)
    private val TAG = "AudioFileAnalyzer"

    // Covers se guardan en filesDir (no en cacheDir) para sobrevivir a "limpiar caché"
    // del sistema. Los audios viven en filesDir/music y las carátulas extraídas no son
    // realmente caché: regenerarlas requiere re-leer cada audio o re-descargarlo.
    private val coversDir: File by lazy {
        File(context.filesDir, "covers").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Re-extrae únicamente la carátula embebida del audio local, sin tocar el resto de
     * metadata. Pensado para el healing tras pérdida del directorio de covers.
     * Devuelve el URI `file://` recién escrito o null si el audio no contiene arte.
     */
    suspend fun reExtractEmbeddedArt(songId: String, audioFile: File): String? {
        val analysis = analyzeFile(audioFile)
        return analysis.embeddedArt?.let { saveEmbeddedArt(songId, it) }
    }

    suspend fun analyzeFile(file: File): FileAnalysisResult {
        return analyzePath(file.absolutePath, file.name)
    }

    suspend fun analyzeUrl(url: String): FileAnalysisResult {
        return analyzePath(url, url.substringAfterLast('/', "unknown"))
    }

    /**
     * Analiza un audio accesible por `content://` (SAF, fuente local). `setDataSource(String)`
     * no sirve para content URIs: hay que usar la sobrecarga con Context+Uri.
     */
    suspend fun analyzeContentUri(uri: Uri, fileName: String): FileAnalysisResult = retrieverSemaphore.withPermit {
        return try {
            withTimeout(CONTENT_URI_ANALYSIS_TIMEOUT_MS) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    extractMetadataFromRetriever(retriever, fileName)
                } finally {
                    safeRelease(retriever)
                }
            }
        } catch (e: Exception) {
            FileAnalysisResult(
                extension = fileName.substringAfterLast('.', "").take(4),
                isValid = false,
                title = null, artist = null, album = null, genre = null,
                duration = 0L, embeddedArt = null, error = e.message
            )
        }
    }

    /** Persiste la carátula embebida y devuelve su URI `file://` (o null). */
    fun persistEmbeddedArt(songId: String, artData: ByteArray): String? = saveEmbeddedArt(songId, artData)

    private suspend fun analyzePath(path: String, fileName: String): FileAnalysisResult = retrieverSemaphore.withPermit {
        val isRemote = path.startsWith("http")
        val timeoutMs = if (isRemote) REMOTE_ANALYSIS_TIMEOUT_MS else LOCAL_ANALYSIS_TIMEOUT_MS

        return try {
            withTimeout(timeoutMs) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (isRemote) {
                        retriever.setDataSource(path, HashMap())
                    } else {
                        // NUNCA setDataSource(String) con rutas locales: esa sobrecarga hace
                        // Uri.parse(path) SIN validar el esquema. Con ids namespaced (Fase 0)
                        // el nombre lleva ':' (`onedrive:<id>.flac`), así que la ruta entera
                        // "adquiere" un esquema basura y el framework la trata como URL de
                        // red → BAD_VALUE (-22) SIEMPRE, con el archivo perfecto en disco.
                        // Vía FileDescriptor el nombre es irrelevante (el retriever dup()ea
                        // el fd, cerrar el stream tras setDataSource es seguro).
                        java.io.FileInputStream(path).use { retriever.setDataSource(it.fd) }
                    }
                    extractMetadataFromRetriever(retriever, fileName)
                } finally {
                    safeRelease(retriever)
                }
            }
        } catch (e: Exception) {
            if (!isRemote) {
                val f = File(path)
                Log.w(TAG, "analyzePath falló (exists=${f.exists()} len=${f.length()}): " +
                    "${e.javaClass.simpleName}: ${e.message} — $path")
            }
            val extension = if (isRemote) path.substringAfterLast('.', "").take(4) else File(path).extension
            FileAnalysisResult(
                extension = extension,
                isValid = false,
                title = null,
                artist = null,
                album = null,
                genre = null,
                duration = 0L,
                embeddedArt = null,
                error = e.message
            )
        }
    }

    /**
     * Extrae la metadata del MediaMetadataRetriever.
     */
    private fun extractMetadataFromRetriever(
        retriever: MediaMetadataRetriever,
        fileName: String
    ): FileAnalysisResult {
        // 1. Extension / MIME
        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        val extension = mimeTypeToExtension(mimeType)

        // 2. Validation (Duration + Has Audio)
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        val duration = durationStr?.toLongOrNull() ?: 0L
        val isValid = duration > 0 || hasAudio != null

        // 3. Metadata
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        // Género normalizado: trim + null si viene vacío (no ensuciar el GROUP BY con "").
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ?.trim()?.takeIf { it.isNotBlank() }
        val embeddedArt = retriever.embeddedPicture

        return FileAnalysisResult(extension, isValid, title, artist, album, genre, duration, embeddedArt)
    }

    /**
     * Convierte un MIME type a extensión de archivo.
     */
    fun mimeTypeToExtension(mimeType: String?): String {
        return when {
            mimeType == null -> "mp3"
            mimeType.contains("audio/mpeg", ignoreCase = true) -> "mp3"
            mimeType.contains("audio/mp4", ignoreCase = true) || 
            mimeType.contains("audio/m4a", ignoreCase = true) ||
            mimeType.contains("audio/x-m4a", ignoreCase = true) -> "m4a"
            mimeType.contains("audio/flac", ignoreCase = true) || 
            mimeType.contains("audio/x-flac", ignoreCase = true) -> "flac"
            mimeType.contains("audio/ogg", ignoreCase = true) || 
            mimeType.contains("audio/vorbis", ignoreCase = true) -> "ogg"
            mimeType.contains("audio/wav", ignoreCase = true) || 
            mimeType.contains("audio/x-wav", ignoreCase = true) -> "wav"
            mimeType.contains("audio/x-ms-wma", ignoreCase = true) -> "wma"
            mimeType.contains("audio/aac", ignoreCase = true) || 
            mimeType.contains("audio/x-aac", ignoreCase = true) -> "aac"
            mimeType.contains("audio/opus", ignoreCase = true) -> "opus"
            else -> "mp3"
        }
    }

    /**
     * Actualiza una canción con los datos del análisis y guarda la carátula si existe.
     */
    suspend fun updateSongWithAnalysis(
        originalSong: Song,
        localUri: String,
        analysis: FileAnalysisResult,
        musicRepository: IMusicRepository
    ) {
        try {
            // Guardar carátula si existe
            val artUriString = if (analysis.embeddedArt != null) {
                saveEmbeddedArt(originalSong.id, analysis.embeddedArt)
            } else {
                originalSong.albumArtUriString
            }

            val updatedSong = originalSong.copy(
                path = localUri,
                title = analysis.title ?: originalSong.title,
                artist = analysis.artist ?: originalSong.artist,
                album = analysis.album ?: originalSong.album,
                duration = if (originalSong.duration == 0L) analysis.duration else originalSong.duration,
                albumArtUri = if (artUriString != null) Uri.parse(artUriString) else originalSong.albumArtUri
            )
            musicRepository.updateSongMetadata(updatedSong)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating metadata after download: ${e.message}")
        }
    }

    /**
     * Guarda la carátula embebida en el directorio de covers.
     */
    private fun saveEmbeddedArt(songId: String, artData: ByteArray): String? {
        return try {
            val coverFile = File(coversDir, "$songId.jpg")
            FileOutputStream(coverFile).use { fos ->
                fos.write(artData)
            }
            Uri.fromFile(coverFile).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving cover art: ${e.message}")
            null
        }
    }

    /**
     * Libera el MediaMetadataRetriever de forma segura.
     */
    private fun safeRelease(retriever: MediaMetadataRetriever) {
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
        }
    }

    companion object {
        /**
         * Retrievers en paralelo. `MediaMetadataRetriever` abre un decoder por instancia, así
         * que el límite real es la CPU del dispositivo: se deriva de los núcleos en vez de
         * fijar un número que sería pesimista en gama alta y optimista en gama baja (mismo
         * criterio que `SyncManager.computeParallelism`, que mide el enlace en vez de fijarlo).
         * Se reserva capacidad para el resto del pipeline de sync, con un mínimo de 2.
         */
        private val MAX_CONCURRENT_RETRIEVERS =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        /**
         * Los timeouts de análisis son DEFENSA contra un retriever colgado (ver el bug de
         * `setDataSource` con ':' en el nombre), NO una estimación de cuánto debería tardar.
         * Por eso son GENEROSOS a propósito: quedarse esperando unos segundos de más en un
         * caso raro es inofensivo, mientras que dispararse de menos deja la canción sin
         * metadata (título, artista, duración y carátula vacíos) — un fallo silencioso que
         * solo repara el healing del siguiente sync. Un archivo local sano se analiza en
         * decenas de ms; si tarda segundos es que el dispositivo está saturado (justo lo que
         * pasa durante un sync masivo), no que el archivo esté roto.
         */
        private const val LOCAL_ANALYSIS_TIMEOUT_MS = 30_000L
        private const val REMOTE_ANALYSIS_TIMEOUT_MS = 60_000L
        private const val CONTENT_URI_ANALYSIS_TIMEOUT_MS = 30_000L
    }
}
