package com.qhana.siku.player.manager

import android.util.LruCache
import com.qhana.siku.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongCacheManager @Inject constructor() {
    // LRU Cache: Thread-safe (LruCache synchronizes internally on get/put).
    private val songCache = object : LruCache<String, Song>(500) {}

    /**
     * Lookup síncrono SOLO en memoria. Retorna null si no hay hit. Útil en listeners
     * no-suspend (p.ej. `Player.Listener.onMediaItemTransition`).
     */
    fun getSongSync(id: String): Song? = songCache.get(id)

    fun cacheSong(song: Song) {
        updateCaches(song)
    }

    fun cacheSongs(songs: List<Song>) {
        songs.forEach { updateCaches(it) }
    }

    private fun updateCaches(song: Song) {
        // Synchronized compound read-then-write via LruCache's internal lock
        synchronized(songCache) {
            val existing = songCache.get(song.id)
            val finalPath = if (existing != null) {
                selectBestPath(song.path, existing.path)
            } else {
                song.path
            }
            songCache.put(song.id, song.copy(path = finalPath))
        }
    }

    private fun selectBestPath(newPath: String, existingPath: String?): String {
        return when {
            newPath.startsWith("file://") -> newPath
            existingPath?.startsWith("file://") == true -> existingPath
            existingPath?.isNotEmpty() == true -> existingPath
            else -> newPath
        }
    }
}
