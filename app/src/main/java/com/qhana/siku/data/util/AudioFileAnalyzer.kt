package com.qhana.siku.data.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.encodeMultiValue
import com.qhana.siku.data.util.tags.PartialTagReaders
import com.qhana.siku.data.util.tags.TagFragment
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.tags.parseTrackNumber
import com.qhana.siku.data.util.tags.parseYear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
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
    /**
     * Tag ALBUMARTIST codificado con `encodeMultiValue`: `""` = el archivo se leyó y no lo trae, `null`
     * = no se leyó (análisis fallido). Es la misma distinción de tres estados que `songs.albumArtist`.
     */
    val albumArtist: String? = null,
    /** Artistas multivalor de la pista codificados, o `null` si el archivo no declara más que [artist]. */
    val artistCredits: String? = null,
    /** Número de pista; 0 = el archivo no lo declara (ver [parseTrackNumber]). */
    val trackNumber: Int = 0,
    /** Año de publicación; 0 = el archivo no lo declara (ver [parseYear]). */
    val year: Int = 0,
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
        if (albumArtist != other.albumArtist) return false
        if (artistCredits != other.artistCredits) return false
        if (trackNumber != other.trackNumber) return false
        if (year != other.year) return false
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
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (artistCredits?.hashCode() ?: 0)
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + duration.hashCode()
        result = 31 * result + (embeddedArt?.contentHashCode() ?: 0)
        return result
    }
}

@Singleton
class AudioFileAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    // Para los tags de artista MULTIVALOR, que `MediaMetadataRetriever` aplana en un solo string.
    private val tagReaders: PartialTagReaders
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

    // El healing NO usa un atajo "re-extrae y devuelve el URI": necesita distinguir "el archivo
    // no trae portada" de "no pude leer el archivo", y un String? colapsa los dos en null. Llama
    // a [analyzeFile]/[analyzeContentUri] y mira `isValid` (ver ArtworkHealingManager.Extraction).

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
                // Ver [analyzePath]: sin `runInterruptible` el timeout no puede cortar nada.
                runInterruptible(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        extractMetadataFromRetriever(retriever, fileName) {
                            context.contentResolver.openInputStream(uri)
                        }
                    } finally {
                        safeRelease(retriever)
                    }
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

    /**
     * Persiste una carátula y devuelve su URI `file://` (o null si la escritura falla).
     *
     * NO recibe la canción a propósito: el archivo se identifica por su contenido, así que la
     * misma portada extraída de doce pistas de un álbum converge en UN archivo, escrito la
     * primera vez y reutilizado las once siguientes.
     */
    fun persistArtwork(artData: ByteArray): String? = saveArtwork(artData)

    /**
     * Borra las carátulas que ya no referencia ninguna canción y devuelve cuántas.
     *
     * Hace falta porque el nombre lo da el contenido: una portada que cambia (tags reeditados en
     * el origen) no sobrescribe a la anterior, se escribe al lado, y la vieja se queda.
     *
     * El ORDEN es la garantía, y por eso las referencias llegan como lambda en vez de como dato
     * ya resuelto: primero se fotografía el directorio y DESPUÉS se consulta la BD. Así, una
     * carátula escrita mientras esto corre no está en la foto (a salvo), y una cuya fila se
     * inserta mientras esto corre sí aparece entre las referencias (a salvo también). Al revés
     * —referencias primero— existiría una ventana en la que un archivo nuevo parece basura.
     *
     * Llamarlo cuando no haya un escaneo a medias sigue siendo lo correcto: sus lotes escriben
     * la carátula antes de insertar la fila, y esa ventana no la cierra ningún orden de lectura.
     */
    /**
     * Cuántos archivos hay en el directorio de carátulas, o −1 si no se pudo listar.
     *
     * Es la señal barata con la que `ArtworkHealingManager.heal()` decide si tiene algo que buscar:
     * una carátula huérfana solo puede aparecer si un archivo DESAPARECIÓ del directorio, y eso se
     * ve en el conteo con una sola operación de disco, en vez de con una consulta de la tabla
     * entera más un `stat` por canción. Que el conteo SUBA (un sync que añade portadas) no crea
     * huérfanos, y `pruneUnreferencedCovers` solo borra lo que ya no referencia nadie, así que
     * tampoco: las bajadas que importan son las de fuera de la app (limpiar datos, borrado
     * externo) y esas son masivas, no de un archivo.
     */
    fun coverFileCount(): Int = coversDir.list()?.size ?: -1

    suspend fun pruneUnreferencedCovers(fetchReferencedUris: suspend () -> Set<String>): Int {
        val files = coversDir.listFiles() ?: return 0
        if (files.isEmpty()) return 0
        val referenced = fetchReferencedUris()
        var deleted = 0
        for (file in files) {
            if (Uri.fromFile(file).toString() in referenced) continue
            if (file.delete()) deleted++
        }
        return deleted
    }

    private suspend fun analyzePath(path: String, fileName: String): FileAnalysisResult = retrieverSemaphore.withPermit {
        val isRemote = path.startsWith("http")
        val timeoutMs = if (isRemote) REMOTE_ANALYSIS_TIMEOUT_MS else LOCAL_ANALYSIS_TIMEOUT_MS

        return try {
            withTimeout(timeoutMs) {
                // `runInterruptible` es lo que hace que el timeout SIRVA. Todo lo de dentro es
                // bloqueante y sin puntos de suspensión, así que `withTimeout` a secas se limitaba
                // a marcar la corrutina como cancelada mientras el hilo seguía atascado en la
                // llamada nativa: el retriever colgado —justo el caso contra el que este timeout
                // defiende— retenía su permiso del semáforo PARA SIEMPRE, y con
                // MAX_CONCURRENT_RETRIEVERS cuelgues se paraba TODO el análisis de la biblioteca.
                // Ahora la cancelación interrumpe el hilo y `withPermit` puede devolver el permiso.
                runInterruptible(Dispatchers.IO) {
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
                        // Remoto: la cabecera costaría otra petición, y esa lectura ya la hace la
                        // metadata ligera.
                        extractMetadataFromRetriever(retriever, fileName) {
                            if (isRemote) null else java.io.FileInputStream(path)
                        }
                    } finally {
                        safeRelease(retriever)
                    }
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
        fileName: String,
        openHeader: () -> java.io.InputStream?
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
        val trackNumber = parseTrackNumber(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        )
        // DATE como respaldo de YEAR: con Vorbis comments (FLAC) el retriever suele exponer el
        // tag `DATE` en METADATA_KEY_DATE y dejar METADATA_KEY_YEAR vacío. [parseYear] acepta las
        // dos formas, así que basta con preguntar por las dos claves.
        val year = parseYear(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        )
        val embeddedArt = retriever.embeddedPicture

        // Artistas. El retriever da UN string por clave, así que lo que el archivo declara como varios
        // valores se lee de la cabecera con los mismos lectores de la metadata ligera. Si el formato no
        // tiene lector o la cabecera no lo trae, manda el retriever — que es lo que había.
        val header = readHeaderTags(extension, openHeader)
        val retrieverAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val albumArtist = encodeMultiValue(
            header?.albumArtists?.takeIf { it.isNotEmpty() } ?: listOfNotNull(retrieverAlbumArtist)
        ) ?: ""
        val artistCredits = header?.artists?.takeIf { it.isNotEmpty() }?.let(::encodeMultiValue)
        // Con créditos multivalor, el texto a mostrar sale de la misma lectura que los créditos: el
        // retriever puede haberse quedado con un solo intérprete de los varios.
        val displayArtist = if (artistCredits != null) header?.artist ?: artist else artist

        // Nombrados a propósito: [FileAnalysisResult] tiene campos opcionales intercalados y una
        // llamada posicional se desalinea en silencio al añadir el siguiente.
        return FileAnalysisResult(
            extension = extension,
            isValid = isValid,
            title = title,
            artist = displayArtist,
            album = album,
            genre = genre,
            albumArtist = albumArtist,
            artistCredits = artistCredits,
            trackNumber = trackNumber,
            year = year,
            duration = duration,
            embeddedArt = embeddedArt
        )
    }

    /**
     * Los tags de la cabecera del archivo, leídos con el lector de su formato, o `null` si no hay
     * lector o no se pudo leer. Nunca lanza: los artistas multivalor son un enriquecimiento, y su
     * fallo no puede costarle a la canción el resto de su metadata.
     */
    private fun readHeaderTags(extension: String, open: () -> java.io.InputStream?): TagFragment? {
        val reader = tagReaders.forExtension(extension) ?: return null
        return try {
            open()?.use { input ->
                val buffer = ByteArray(HEADER_TAG_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                reader.read(if (read == buffer.size) buffer else buffer.copyOf(read))
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la cabecera de tags: ${e.message}")
            null
        }
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
            val freshArtUri = analysis.embeddedArt?.let { saveArtwork(it) }
            val artUriString = freshArtUri ?: originalSong.albumArtUriString

            val updatedSong = originalSong.copy(
                path = localUri,
                title = analysis.title ?: originalSong.title,
                artist = analysis.artist ?: originalSong.artist,
                album = analysis.album ?: originalSong.album,
                albumArtist = analysis.albumArtist ?: originalSong.albumArtist,
                artistCredits = if (analysis.albumArtist != null) analysis.artistCredits else originalSong.artistCredits,
                // Un 0 del análisis significa "el archivo no lo declara": no debe borrar lo que
                // la fila ya supiera (p. ej. lo que trajo el índice de MediaStore).
                trackNumber = analysis.trackNumber.takeIf { it > 0 } ?: originalSong.trackNumber,
                year = analysis.year.takeIf { it > 0 } ?: originalSong.year,
                duration = if (originalSong.duration == 0L) analysis.duration else originalSong.duration,
                albumArtUri = if (artUriString != null) Uri.parse(artUriString) else originalSong.albumArtUri
            )
            musicRepository.updateSongMetadata(updatedSong)

            // La portada recién salida del archivo vale para todo el disco, igual que en la
            // metadata ligera. Sin esto, de un álbum del que el tope de almacenamiento solo deja
            // bajar unas pocas pistas, únicamente esas mostraban carátula: las hermanas se
            // quedaban con el placeholder teniendo el jpg ya en disco.
            if (freshArtUri != null && !AppConfig.isUnknownAlbum(updatedSong.album)) {
                musicRepository.setAlbumArt(updatedSong.album, updatedSong.albumArtist.orEmpty(), freshArtUri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating metadata after download: ${e.message}")
        }
    }

    /**
     * Guarda la carátula en el directorio de covers, bajo el nombre que le da su contenido.
     *
     * Si el archivo ya existe no se reescribe: mismo nombre ⇒ mismos bytes, así que copiarlos
     * otra vez solo gastaría I/O. Es lo que hace que un álbum entero cueste una escritura.
     */
    private fun saveArtwork(artData: ByteArray): String? {
        return try {
            val coverFile = File(coversDir, coverFileName(artData))
            if (!coverFile.exists() || coverFile.length() != artData.size.toLong()) {
                FileOutputStream(coverFile).use { fos -> fos.write(artData) }
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
         * criterio que `SyncManager.initialParallelism`, que mide el enlace en vez de fijarlo).
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

        /**
         * Cuánto de la cabecera se lee para los artistas multivalor. Menos que los 256 KB de la
         * metadata ligera a propósito: aquí se lee CADA archivo local al indexar, y los frames de texto
         * van casi siempre antes que la carátula. Si una carátula enorme los empuja fuera, se pierde
         * solo el multivalor — el retriever sigue dando artista y artista del álbum.
         */
        private const val HEADER_TAG_BYTES = 64 * 1024
        private const val REMOTE_ANALYSIS_TIMEOUT_MS = 60_000L
        private const val CONTENT_URI_ANALYSIS_TIMEOUT_MS = 30_000L
    }
}
