package com.qhana.siku.player

import android.content.Context
import android.util.Log
import androidx.media3.datasource.cache.Cache
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordina la preparación de canciones para reproducción usando una cadena de responsabilidad.
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: Cache,
    private val sourceRegistry: MusicSourceRegistry,
    private val musicRepository: IMusicRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer
) {
    companion object {
        private const val TAG = "PlaybackCoordinator"
    }

    private val chain: List<PreparationStep> = listOf(
        LocalFileCheckStep(context, musicRepository),
        UrlRefreshStep(musicRepository, sourceRegistry) { isSongCached(it) },
        MetadataFetchStep(musicRepository, sourceRegistry)
    )

    fun isSongCached(songId: String): Boolean {
        return cache.getCachedSpans(songId).isNotEmpty()
    }

    fun clearCacheForSong(song: Song) {
        try {
            cache.removeResource(song.id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache for song ${song.id}: ${e.message}")
        }
    }

    suspend fun prepareSongForPlayback(song: Song): PrepareResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Iniciando cadena de preparación para: ${song.title}")

        val freshSong = musicRepository.getSongById(song.id).getOrNull() ?: song
        val preparationContext = PreparationContext(freshSong)

        for (step in chain) {
            when (val result = step.execute(preparationContext)) {
                is PreparationStep.StepResult.Continue -> continue
                is PreparationStep.StepResult.Abort -> break
                is PreparationStep.StepResult.Error -> return@withContext PrepareResult.Error(result.message)
            }
        }

        PrepareResult.Success(
            song = preparationContext.song,
            urlRefreshed = preparationContext.urlRefreshed,
            willStream = preparationContext.willStream
        )
    }

    suspend fun refreshSongUrl(song: Song): Song? = withContext(Dispatchers.IO) {
        if (song.path.startsWith("file://")) {
            val file = File(song.path.removePrefix("file://"))
            if (file.exists()) file.delete()
        }
        if (song.remoteId == null) return@withContext null
        // forceRefresh invalida la caché y resuelve una URL fresca vía la fuente.
        val freshUrl = sourceRegistry.resolveDownloadUrl(song, forceRefresh = true)
        if (freshUrl != null) {
            musicRepository.updateSongUrl(song.id, freshUrl)
            song.copy(path = freshUrl)
        } else null
    }

    sealed class PrepareResult {
        data class Success(
            val song: Song,
            val urlRefreshed: Boolean,
            val willStream: Boolean = false
        ) : PrepareResult()
        data class Error(val message: String) : PrepareResult()
    }
}
