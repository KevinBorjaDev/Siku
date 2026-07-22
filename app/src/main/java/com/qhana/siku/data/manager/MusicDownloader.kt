package com.qhana.siku.data.manager

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.StatFs
import android.util.Log
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.NetworkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Componente especializado en la descarga y procesamiento de archivos de música.
 * Encapsula la lógica de:
 * 1. Descarga HTTP (con soporte para rangos parciales si se requiere).
 * 2. Gestión de archivos temporales y finales.
 * 3. Extracción de Metadatos (Duración, Artista, Album).
 * 4. Extracción de Carátulas incrustadas.
 * 5. Actualización de la Base de Datos.
 */
@Singleton
class MusicDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: IMusicRepository,
    @Named("download") private val okHttpClient: OkHttpClient,
    private val networkManager: NetworkManager,
    private val audioFileAnalyzer: com.qhana.siku.data.util.AudioFileAnalyzer
) {

    companion object {
        private const val TAG = "MusicDownloader"
        private const val IO_BUFFER_SIZE = 64 * 1024 // 64KB: mejor throughput en WiFi moderno (menos llamadas read/write).
        private const val MIN_BATTERY_LEVEL = 20
        private const val MIN_DISK_SPACE_BYTES = 50L * 1024 * 1024 // 50MB mínimo
        // Stall detector: si no llegan bytes nuevos en este tiempo, abortar la descarga.
        // Cubre TCP half-open (FIN packet perdido), URL OneDrive expirada mid-stream,
        // o cualquier estancamiento que el `readTimeout(5min)` de OkHttp tarda en detectar.
        //
        // 60s (no 20s): los FLAC grandes (50-250 MB) a la velocidad por-conexión que OneDrive
        // permite (~0.5 MB/s) tardan minutos en bajar, y son normales pausas de >20s por
        // throttling/re-transmisión TCP sin que la conexión esté realmente muerta. 20s
        // mataba descargas válidas. 60s sigue cazando cuelgues reales (un FIN perdido no se
        // arregla solo) y deja margen para la lentitud legítima.
        internal const val STALL_TIMEOUT_MS = 60_000L
        private const val STALL_CHECK_INTERVAL_MS = 10_000L

        /**
         * Techo de silencio del socket para el cliente "download" (lo consume AppModule).
         * Mide LO MISMO que el watchdog —tiempo sin recibir bytes, no duración total de la
         * descarga— así que ambos deben venir del mismo número en vez de fijarse por separado.
         *
         * Se le da el DOBLE de margen a propósito: el watchdog debe ganar siempre la carrera
         * porque su diagnóstico es mejor (marca `wasStalled`, lo que clasifica el fallo como
         * TRANSIENT y programa reintento con backoff). Si cortara antes OkHttp, el mismo
         * cuelgue llegaría como una IOException genérica.
         */
        internal const val SOCKET_IDLE_TIMEOUT_MS = STALL_TIMEOUT_MS * 2

        // Tolerancia al comparar el archivo local contra el tamaño reportado por el
        // proveedor. Un corte de conexión "limpio" (EOF prematuro sin excepción) deja un
        // archivo truncado que pasaría el viejo check de `length() > 0` para siempre.
        private const val SIZE_TOLERANCE_BYTES = 1024L

        private val KNOWN_AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "m4a", "wav", "ogg", "opus", "aac", "wma", "aif", "aiff", "ape", "wv"
        )
    }

    /**
     * Clasificación del error de descarga. TRANSIENT = vale la pena reintentar
     * (red caída, timeout, stall, 5xx/429, página de error de OneDrive).
     * PERMANENT = reintentar de inmediato no va a cambiar nada (4xx tras refresh
     * de URL, path inválido, disco lleno).
     */
    enum class ErrorKind { TRANSIENT, PERMANENT }

    /**
     * Resultado de una operación completa (descarga + análisis).
     */
    sealed class Result {
        data class Success(val song: Song) : Result()
        data class Error(val message: String, val exception: Exception? = null, val transient: Boolean = false) : Result()
        object Cancelled : Result()
        object SkippedLowBattery : Result()
    }

    /**
     * Resultado de la fase de red (descarga del archivo). Permite que el caller
     * suelte el slot de paralelismo antes de la fase pesada (analysis + BD).
     */
    sealed class DownloadStage {
        data class Success(val targetFile: File) : DownloadStage()
        data class Error(
            val message: String,
            val httpCode: Int? = null,
            val exception: Exception? = null,
            val kind: ErrorKind = ErrorKind.TRANSIENT
        ) : DownloadStage()
        object Cancelled : DownloadStage()
        object SkippedLowBattery : DownloadStage()
    }

    /**
     * Fase 1: descarga el archivo a disco. No analiza ni toca la BD.
     */
    suspend fun downloadFile(
        song: Song,
        downloadUrl: String? = null,
        isPriority: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): DownloadStage = withContext(Dispatchers.IO) {
        if (!networkManager.isAvailable()) {
            return@withContext DownloadStage.Error(context.getString(R.string.dl_err_no_internet))
        }
        if (!isPriority && isLowBattery()) {
            Log.w(TAG, "Download postponed: low battery (<$MIN_BATTERY_LEVEL%)")
            return@withContext DownloadStage.SkippedLowBattery
        }
        if (!hasEnoughDiskSpace()) {
            Log.w(TAG, "Download postponed: insufficient disk space (<${MIN_DISK_SPACE_BYTES / 1024 / 1024}MB)")
            return@withContext DownloadStage.Error(context.getString(R.string.dl_err_no_space), kind = ErrorKind.PERMANENT)
        }

        val url = downloadUrl ?: song.path
        if (url.isEmpty()) return@withContext DownloadStage.Error(context.getString(R.string.dl_err_empty_url))

        val baseName = song.id
        var tempFile: File? = null
        val wasStalled = AtomicBoolean(false)

        try {
            val request = Request.Builder().url(url).build()

            return@withContext okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 408/429/5xx son recuperables; el resto de 4xx solo se arregla con URL
                    // fresca (lo maneja runDownloadWithRetry) — si reincide, es permanente.
                    val kind = if (response.code == 408 || response.code == 429 || response.code >= 500) {
                        ErrorKind.TRANSIENT
                    } else {
                        ErrorKind.PERMANENT
                    }
                    return@use DownloadStage.Error("HTTP ${response.code}: ${response.message}", httpCode = response.code, kind = kind)
                }

                val contentType = response.header("Content-Type")
                // Content-Type validation: si OneDrive devuelve una página de error en vez
                // del audio (HTML/JSON), abortamos antes de escribir basura a disco.
                // Locale.ROOT: normalización de datos de protocolo. Sin él, en locale turco/azerí
                // "APPLICATION/JSON".lowercase() da "applıcatıon/json" (i sin punto) y el startsWith falla.
                val ctLower = contentType?.lowercase(Locale.ROOT)
                if (ctLower != null && (ctLower.startsWith("text/") || ctLower.startsWith("application/json"))) {
                    return@use DownloadStage.Error(context.getString(R.string.dl_err_content_type, contentType))
                }
                val extension = determineExtension(contentType, url, song.title)

                val fileName = "$baseName.$extension"
                val musicDir = File(context.filesDir, "music")
                if (!musicDir.exists()) musicDir.mkdirs()

                val targetFile = File(musicDir, fileName)
                // Capturamos en un val no-null para evitar `!!` en los usos siguientes;
                // el outer var `tempFile` se mantiene para la limpieza en el catch.
                val tmpFile = File(musicDir, "$fileName.tmp")
                tempFile = tmpFile

                // Path-traversal check: defensa en profundidad. Aunque baseName=song.id es
                // controlado por nosotros, validamos que las rutas resueltas no escapen del
                // sandbox de la app antes de abrir ningún FileOutputStream.
                try {
                    val safeDir = context.filesDir.canonicalPath
                    if (!targetFile.canonicalPath.startsWith(safeDir) || !tmpFile.canonicalPath.startsWith(safeDir)) {
                        return@use DownloadStage.Error("Path traversal detectado: ${targetFile.path}", kind = ErrorKind.PERMANENT)
                    }
                } catch (e: Exception) {
                    return@use DownloadStage.Error(context.getString(R.string.dl_err_path_validation, e.message), exception = e, kind = ErrorKind.PERMANENT)
                }

                // Idempotencia: si ya existe con el tamaño esperado, asumimos éxito previo.
                // Un resto truncado de una corrida anterior se borra y se re-descarga.
                if (targetFile.exists() && targetFile.length() > 0) {
                    if (looksTruncated(targetFile.length(), song.size)) {
                        Log.w(TAG, "Archivo previo truncado (${targetFile.length()}/${song.size} bytes) para ${song.title}, re-descargando")
                        targetFile.delete()
                    } else {
                        onProgress(1.0f)
                        return@use DownloadStage.Success(targetFile)
                    }
                }

                val totalBytes = response.body?.contentLength() ?: -1L
                val bytesCopiedAtomic = AtomicLong(0L)
                var lastProgress = 0f
                var lastProgressEmitAt = 0L

                val body = response.body ?: return@use DownloadStage.Error(context.getString(R.string.dl_err_empty_body))
                val inputStream = body.byteStream()

                coroutineScope {
                    val watchdog = launch {
                        var lastBytes = 0L
                        var lastProgressAt = System.currentTimeMillis()
                        while (isActive) {
                            delay(STALL_CHECK_INTERVAL_MS)
                            val current = bytesCopiedAtomic.get()
                            val now = System.currentTimeMillis()
                            if (current > lastBytes) {
                                lastBytes = current
                                lastProgressAt = now
                            } else if (now - lastProgressAt > STALL_TIMEOUT_MS) {
                                Log.w(TAG, "Stall detected for ${song.title}: no progress in ${STALL_TIMEOUT_MS / 1000}s at $current bytes")
                                wasStalled.set(true)
                                try { inputStream.close() } catch (_: Exception) {}
                                break
                            }
                        }
                    }

                    try {
                        inputStream.use { input ->
                            tmpFile.outputStream().use { output ->
                                val buffer = ByteArray(IO_BUFFER_SIZE)
                                var bytes = input.read(buffer)

                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    val currentBytes = bytesCopiedAtomic.addAndGet(bytes.toLong())

                                    if (totalBytes > 0) {
                                        val currentProgress = currentBytes.toFloat() / totalBytes.toFloat()
                                        val now = System.currentTimeMillis()
                                        // Emite cada 5% o cada 250ms (lo que ocurra antes), y siempre al 100%.
                                        // Reduce el spam al StateFlow con N descargas paralelas sin perder
                                        // suavidad perceptible en la UI.
                                        val byPercent = currentProgress - lastProgress >= 0.05f
                                        val byTime = now - lastProgressEmitAt >= 250L
                                        val isFinal = currentBytes == totalBytes
                                        if (byPercent || byTime || isFinal) {
                                            onProgress(currentProgress)
                                            lastProgress = currentProgress
                                            lastProgressEmitAt = now
                                        }
                                    }

                                    bytes = input.read(buffer)
                                }
                            }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }

                val tmp = tmpFile
                if (!tmp.exists() || tmp.length() == 0L) {
                    return@use DownloadStage.Error(context.getString(R.string.dl_err_empty_file))
                }

                // Validar bytes recibidos vs Content-Length: un stream que terminó "limpio"
                // antes de tiempo (conexión cortada sin excepción) deja un archivo truncado
                // que de otro modo se renombraría como bueno y quedaría así para siempre.
                if (totalBytes > 0 && tmp.length() < totalBytes) {
                    tmp.delete()
                    return@use DownloadStage.Error(
                        context.getString(R.string.dl_err_truncated, tmp.length(), totalBytes),
                        kind = ErrorKind.TRANSIENT
                    )
                }

                if (targetFile.exists()) targetFile.delete()
                val renamed = tmp.renameTo(targetFile)
                if (!renamed) {
                    tmp.copyTo(targetFile, overwrite = true)
                    tmp.delete()
                }

                DownloadStage.Success(targetFile)
            }
        } catch (e: Exception) {
            try { tempFile?.delete() } catch (_: Exception) {}
            if (wasStalled.get()) {
                Log.w(TAG, "Download stalled for ${song.title}, marking as failed and continuing queue")
                return@withContext DownloadStage.Error(context.getString(R.string.dl_err_stalled, (STALL_TIMEOUT_MS / 1000).toInt()))
            }
            if (e is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Download cancelled: ${song.title}")
                return@withContext DownloadStage.Cancelled
            }
            Log.e(TAG, "Error downloading ${song.title}", e)
            // IOException (DNS, socket, timeout) es recuperable; el resto (estado inválido,
            // bugs) no se arregla reintentando.
            val kind = if (e is java.io.IOException) ErrorKind.TRANSIENT else ErrorKind.PERMANENT
            return@withContext DownloadStage.Error(context.getString(R.string.dl_err_exception, e.message), exception = e, kind = kind)
        }
    }

    /**
     * Fase 2: analiza el archivo descargado, corrige extensión y actualiza la BD.
     * Sin red. Pensado para correr fuera del semáforo de paralelismo.
     */
    suspend fun finalizeDownload(song: Song, targetFile: File): Result = withContext(Dispatchers.IO) {
        try {
            val baseName = song.id
            val analysis = audioFileAnalyzer.analyzeFile(targetFile)
            val correctExt = analysis.extension
            val currentExt = targetFile.extension
            val finalFile = if (correctExt.isNotEmpty() && !correctExt.equals(currentExt, ignoreCase = true)) {
                val correctedFile = File(targetFile.parent, "$baseName.$correctExt")
                if (targetFile.renameTo(correctedFile)) correctedFile else targetFile
            } else targetFile

            val newPath = "file://${finalFile.absolutePath}"
            musicRepository.updateSongUrl(song.id, newPath)
            audioFileAnalyzer.updateSongWithAnalysis(song, newPath, analysis, musicRepository)
            // Bytes recién bajados de la fuente = la canción ya NO está corrupta. Sin esto,
            // una marcada por PlaybackErrorRecoveryUseCase quedaba corrupta PARA SIEMPRE (no
            // existía el camino de vuelta) y la reparación automática de SyncViewModel la
            // redescargaba en CADA arranque de la app — bucle infinito.
            musicRepository.clearCorrupted(song.id)

            // ReplayGain: leer los tags del archivo local ya descargado y persistirlos.
            // Solo se hace una vez (al indexar); la reproducción luego solo lee de la DB.
            val rg = com.qhana.siku.data.util.ReplayGainReader.read(finalFile.absolutePath)
            if (!rg.isEmpty || rg.trackPeak != null || rg.albumPeak != null) {
                musicRepository.updateReplayGain(song.id, rg.trackGainDb, rg.trackPeak, rg.albumGainDb, rg.albumPeak)
            }

            // Género: el tag ya lo leyó el análisis de arriba. "" = analizado sin género (evita
            // que el backfill lo reprocese). Alimenta los chips de género del inicio.
            musicRepository.updateGenre(song.id, analysis.genre ?: "")

            val finalSong = musicRepository.getSongById(song.id).getOrNull() ?: song.copy(path = newPath)
            Result.Success(finalSong)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error finalizing ${song.title}", e)
            Result.Error("Finalize exception: ${e.message}", e)
        }
    }

    /**
     * Backfill una-vez de géneros: re-lee el tag GENRE de las canciones YA descargadas (archivo
     * local, sin re-descargar) que aún no lo tienen. Escribe "" cuando el archivo no existe o no
     * trae género, así el WHERE `genre IS NULL` deja de tomarlas (sin bucles). Devuelve cuántas
     * quedaron con un género real. Lo dispara SyncManager tras el scan, gateado por preferencia.
     */
    suspend fun backfillGenres(): Int = withContext(Dispatchers.IO) {
        var tagged = 0
        while (true) {
            val batch = musicRepository.getDownloadedSongsWithoutGenre(200)
            if (batch.isEmpty()) break
            for (song in batch) {
                val path = song.path.removePrefix("file://")
                val file = File(path)
                if (!file.exists()) {
                    musicRepository.updateGenre(song.id, "") // sin archivo: marcar analizado
                    continue
                }
                val genre = audioFileAnalyzer.analyzeFile(file).genre
                musicRepository.updateGenre(song.id, genre ?: "")
                if (genre != null) tagged++
            }
        }
        tagged
    }

    private fun hasEnoughDiskSpace(): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes > MIN_DISK_SPACE_BYTES
        } catch (e: Exception) {
            true // En caso de error, permitir la descarga
        }
    }

    private fun isLowBattery(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging // API 23+ (MinSdk 26 OK)
        return pct < MIN_BATTERY_LEVEL && !isCharging
    }

    /**
     * Extensión inicial del archivo. OneDrive suele responder `application/octet-stream`
     * para FLAC, así que tras el Content-Type se intenta la extensión del TÍTULO (el scan
     * guarda el nombre de archivo original, p. ej. "05. War.flac") y solo después la de la
     * URL — y únicamente si es una extensión de audio conocida. Antes el fallback tomaba
     * lo que hubiera tras el último '.' de la URL firmada de Graph, que producía basura
     * como `.0`. Sea cual sea el resultado, `finalizeDownload` la corrige tras el análisis.
     */
    private fun determineExtension(contentType: String?, url: String, songTitle: String): String {
        if (contentType != null) {
            when {
                contentType.contains("flac", ignoreCase = true) -> return "flac"
                contentType.contains("audio/mp4", ignoreCase = true) ||
                contentType.contains("audio/m4a", ignoreCase = true) -> return "m4a"
                contentType.contains("audio/mpeg", ignoreCase = true) -> return "mp3"
                contentType.contains("audio/wav", ignoreCase = true) -> return "wav"
                contentType.contains("audio/ogg", ignoreCase = true) -> return "ogg"
            }
        }
        val fromTitle = songTitle.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromTitle in KNOWN_AUDIO_EXTENSIONS) return fromTitle
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromUrl in KNOWN_AUDIO_EXTENSIONS) return fromUrl
        return "mp3"
    }

    /**
     * Archivo ya descargado para esta canción, con CUALQUIER extensión — incluida la basura
     * histórica `.0` del bug de octet-stream. El contenido manda; la extensión la corrige
     * `finalizeDownload` al re-analizar. Permite al pipeline saltarse red y re-descarga
     * cuando los bytes ya están en disco.
     */
    fun findExistingDownload(songId: String, expectedSize: Long = 0L): File? {
        val musicDir = File(context.filesDir, "music")
        val prefix = "$songId."
        val existing = musicDir.listFiles { f: File ->
            f.name.startsWith(prefix) && !f.name.endsWith(".tmp") && f.length() > 0L
        }?.firstOrNull() ?: return null
        // Un resto truncado no cuenta como descarga: se borra para que el pipeline
        // vuelva a bajar los bytes en vez de darlo por bueno indefinidamente.
        if (looksTruncated(existing.length(), expectedSize)) {
            Log.w(TAG, "Descarga previa truncada (${existing.length()}/$expectedSize bytes): ${existing.name}")
            try { existing.delete() } catch (_: Exception) {}
            return null
        }
        return existing
    }

    /**
     * true si [length] es notablemente menor que [expectedSize] (tamaño del proveedor).
     * Con expectedSize desconocido (<= 0) no se puede juzgar: false.
     */
    fun looksTruncated(length: Long, expectedSize: Long): Boolean =
        expectedSize > 0 && expectedSize - length > SIZE_TOLERANCE_BYTES

    /** Borra TODOS los archivos de esta canción (cualquier extensión). Para re-descarga forzada. */
    fun deleteExistingDownloads(songId: String) {
        val musicDir = File(context.filesDir, "music")
        val prefix = "$songId."
        musicDir.listFiles { f: File -> f.name.startsWith(prefix) }?.forEach { f ->
            try { f.delete() } catch (_: Exception) {}
        }
    }

}
