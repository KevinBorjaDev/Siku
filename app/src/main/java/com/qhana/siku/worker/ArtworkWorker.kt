package com.qhana.siku.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ArtworkWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: IMusicRepository,
    private val artworkRepository: ArtworkRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ArtworkWorker"
        private const val PREFS_NAME = "artwork_worker_prefs"
        private const val KEY_LAST_PROCESSED_ID = "last_processed_song_id"
        private const val KEY_PROCESSED_COUNT = "processed_count"
        private const val BATCH_SIZE = 10
        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
    }

    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val allSongs = musicRepository.getSongsWithoutColors()
            if (allSongs.isEmpty()) { prefs.edit().clear().apply(); return@withContext Result.success() }
            val total = allSongs.size
            val lastId = prefs.getString(KEY_LAST_PROCESSED_ID, null)
            var count = prefs.getInt(KEY_PROCESSED_COUNT, 0)
            val songs = if (!lastId.isNullOrBlank()) {
                val idx = allSongs.indexOfFirst { it.id == lastId }
                if (idx >= 0 && idx < allSongs.size - 1) allSongs.subList(idx + 1, allSongs.size) else allSongs
            } else allSongs
            if (songs.isEmpty()) { prefs.edit().clear().apply(); return@withContext Result.success() }

            songs.chunked(BATCH_SIZE).forEach { chunk ->
                if (isStopped) return@withContext Result.success()
                val batch = mutableListOf<Triple<String, Int, Int>>()
                var lastIdInBatch: String? = null
                chunk.forEach { song ->
                    try {
                        // Solo se persisten extracciones REALES. Sin artwork o con fallo de
                        // extracción, las columnas quedan NULL y la canción se reintenta en la
                        // próxima corrida (cuando el artwork exista). Persistir un color
                        // fallback aquí lo volvía indistinguible de un resultado legítimo.
                        val uri = song.albumArtUriString
                        val colors = if (uri != null) artworkRepository.extractColorsOptimized(song.id, uri) else null
                        if (colors != null) {
                            batch.add(Triple(song.id, colors.primary, colors.secondary))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error extracting colors for song ${song.id}", e)
                    }
                    lastIdInBatch = song.id
                    count++
                }
                if (batch.isNotEmpty()) musicRepository.saveColorsBatch(batch)
                lastIdInBatch?.let { lb ->
                    prefs.edit().putString(KEY_LAST_PROCESSED_ID, lb).putInt(KEY_PROCESSED_COUNT, count).apply()
                }
                setProgress(workDataOf(PROGRESS_CURRENT to count, PROGRESS_TOTAL to total))
            }
            prefs.edit().clear().apply()
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}