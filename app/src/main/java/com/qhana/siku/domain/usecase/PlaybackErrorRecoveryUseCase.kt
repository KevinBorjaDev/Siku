package com.qhana.siku.domain.usecase

import android.content.Context
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.PlaybackErrorType
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.PlaybackCoordinator
import com.qhana.siku.worker.DownloadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

class PlaybackErrorRecoveryUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IMusicRepository,
    private val playbackCoordinator: PlaybackCoordinator,
    private val downloadScheduler: DownloadScheduler
) {

    sealed class Result {
        data class Retry(val song: Song) : Result()
        data class Skip(val reason: String) : Result()
        data class Healing(val message: String) : Result()
        object Ignore : Result()
    }

    /**
     * Decide cómo recuperarse de un error de reproducción basado en el código de Media3,
     * no en strings del mensaje (que son frágiles entre versiones).
     */
    suspend operator fun invoke(
        song: Song,
        error: PlaybackErrorInfo,
        currentRetryCount: Int,
        maxRetries: Int
    ): Result {
        // Validar que la canción tenga remoteId para intentar healing.
        val targetSong = if (song.remoteId == null) {
            val freshSong = repository.getSongById(song.id).getOrNull()
            when {
                freshSong?.remoteId != null -> freshSong
                // LOCAL no tiene copia en la nube: no hay healing posible, pero el mensaje
                // debe hablar del ARCHIVO (movido/borrado de la carpeta, permiso SAF caído),
                // no de "sincronización" — eso es vocabulario de la nube.
                (freshSong ?: song).sourceType == SourceType.LOCAL ->
                    return Result.Skip(context.getString(R.string.error_local_file_unavailable))
                else -> return Result.Skip(context.getString(R.string.error_song_not_synced))
            }
        } else song

        val type = error.type
        val isCorruptionError = type == PlaybackErrorType.DECODER ||
            type == PlaybackErrorType.LOOP_DETECTED ||
            type == PlaybackErrorType.FILE_NOT_FOUND

        // Fail fast si superamos reintentos o si el error es claramente de corrupción
        if (currentRetryCount > maxRetries || isCorruptionError) {
            // Si el archivo local existe con tamaño esperado, asumimos fallo de hardware/decoder
            val localPath = targetSong.path.replace("file://", "")
            val localFile = File(localPath)
            if (localFile.exists() && targetSong.size > 0 && abs(localFile.length() - targetSong.size) < 1024) {
                return Result.Skip(context.getString(R.string.error_hardware_skipping))
            }

            // Marcar como corrupto y limpiar.
            markAsCorrupted(targetSong)
            return Result.Skip(context.getString(R.string.error_incompatible_skipping))
        }

        // Solo intentamos healing para errores de red o desconocidos
        if (type != PlaybackErrorType.NETWORK && type != PlaybackErrorType.UNKNOWN) {
            return Result.Ignore
        }

        // Si la canción ya está descargada localmente y el archivo se ve íntegro,
        // el error NO es de red/URL — es un bug de orquestación (cola vacía, race, etc.).
        // No tiene sentido borrarla y re-descargarla; simplemente ignoramos y dejamos
        // que el siguiente playAt() la recargue normalmente.
        if (targetSong.path.startsWith("file://")) {
            val localFile = File(targetSong.path.removePrefix("file://"))
            if (localFile.exists() && localFile.length() > 0) {
                return Result.Ignore
            }
        }

        // Intento de healing: limpiar archivo local incompleto + refrescar URL
        cleanupLocalFile(targetSong)

        // Descarga prioritaria en background (fire & forget). Sin customWorkName: el unique
        // name por defecto es repairTag(songId), único POR CANCIÓN. El viejo nombre global
        // compartido (AUTO_PRIORITY_DOWNLOAD_NAME + REPLACE) hacía que dos errores seguidos
        // en canciones distintas cancelaran el healing de la primera.
        downloadScheduler.scheduleDownload(
            targetSong.id,
            forceRedownload = true
        )

        return try {
            val refreshedSong = playbackCoordinator.refreshSongUrl(targetSong)
            if (refreshedSong != null) Result.Retry(refreshedSong)
            else Result.Skip(context.getString(R.string.error_connection_skipping))
        } catch (e: Exception) {
            Result.Skip(context.getString(R.string.error_url_skipping))
        }
    }

    private suspend fun markAsCorrupted(song: Song) {
        repository.markSongAsCorrupted(song.id)
        repository.deleteAudioFileById(song.id)
        playbackCoordinator.clearCacheForSong(song)
    }

    private suspend fun cleanupLocalFile(song: Song) {
        repository.deleteAudioFileById(song.id)
        playbackCoordinator.clearCacheForSong(song)
    }
}
