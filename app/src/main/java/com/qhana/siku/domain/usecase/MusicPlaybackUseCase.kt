package com.qhana.siku.domain.usecase

import android.content.Context
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import com.qhana.siku.player.PlaybackCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MusicPlaybackUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicController: MusicController,
    private val playbackCoordinator: PlaybackCoordinator,
    private val repository: IMusicRepository
) {
    suspend fun playSongs(songs: List<Song>, index: Int): String? {
        if (songs.isEmpty()) return null
        val safeIndex = index.coerceIn(0, songs.lastIndex)
        val targetSong = songs[safeIndex]

        musicController.setBuffering()
        val result = playbackCoordinator.prepareSongForPlayback(targetSong)
        return when (result) {
            is PlaybackCoordinator.PrepareResult.Success -> {
                val updatedSongs = songs.toMutableList().also { it[safeIndex] = result.song }
                // Pre-cachear la canción preparada para evitar que playAt vuelva a fetchear la DB.
                musicController.cacheSongs(listOf(result.song))
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlay(updatedSongs, safeIndex)
                }
                null
            }
            is PlaybackCoordinator.PrepareResult.Error -> {
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlay(songs, safeIndex)
                }
                result.message
            }
        }
    }

    /**
     * Reproduce desde la biblioteca construyendo la cola con EXACTAMENTE la misma
     * búsqueda + orden + filtros que se ven en pantalla (no el orden alfabético fijo de
     * `getAllSongs`). `filter` decide la fuente: favoritos vs. todas; `sourceFilters`
     * son los chips de origen de la pestaña Todas (no aplican a favoritos).
     */
    suspend fun playFromLibrary(
        clickedSong: Song,
        filter: SongFilter,
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): PlayResult {
        musicController.setBuffering()
        val allSongs = when (filter) {
            SongFilter.FAVORITES -> repository.getFavoritesSnapshot(query)
            else -> repository.getSongsSnapshot(query, sortOrder, sourceFilters)
        }
        if (allSongs.isEmpty()) return PlayResult.Error(context.getString(R.string.songs_empty_title))

        val targetIndex = allSongs.indexOfFirst { it.id == clickedSong.id }
        if (targetIndex == -1) return PlayResult.RetryWithSingle(clickedSong)

        val songResult = repository.getSongById(clickedSong.id)
        val targetSong = songResult.getOrNull() ?: clickedSong

        val result = playbackCoordinator.prepareSongForPlayback(targetSong)
        return when (result) {
            is PlaybackCoordinator.PrepareResult.Success -> {
                // Pre-cachear para evitar que playAt vuelva a fetchear la DB para esta canción.
                musicController.cacheSongs(listOf(result.song))
                withContext(Dispatchers.Main) {
                    musicController.setPlaylist(allSongs, result.song, targetIndex)
                }
                PlayResult.Success(result.song, result.willStream)
            }
            is PlaybackCoordinator.PrepareResult.Error -> {
                withContext(Dispatchers.Main) {
                    musicController.setPlaylist(allSongs, targetSong, targetIndex)
                }
                PlayResult.Error(result.message)
            }
        }
    }

    suspend fun shuffleAllFromLibrary(): PlayResult {
        val allSongs = repository.getAllSongs()
        return playShuffled(allSongs)
    }

    /**
     * Reproduce [songs] en aleatorio dejando el modo shuffle ACTIVO (ver
     * [MusicController.setPlaylistAndPlayShuffled]). Elige un primer tema al azar, lo
     * prepara para arranque inmediato y delega en el controller conservando el orden
     * original de la lista.
     */
    suspend fun playShuffled(songs: List<Song>): PlayResult {
        if (songs.isEmpty()) return PlayResult.Error(context.getString(R.string.songs_empty_title))
        musicController.setBuffering()

        val startIndex = songs.indices.random()
        val chosen = songs[startIndex]
        val result = playbackCoordinator.prepareSongForPlayback(chosen)

        return when (result) {
            is PlaybackCoordinator.PrepareResult.Success -> {
                val prepared = songs.toMutableList().also { it[startIndex] = result.song }
                musicController.cacheSongs(prepared)
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlayShuffled(prepared, startIndex)
                }
                PlayResult.Success(result.song, result.willStream)
            }
            is PlaybackCoordinator.PrepareResult.Error -> {
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlayShuffled(songs, startIndex)
                }
                PlayResult.Error(result.message)
            }
        }
    }

    sealed class PlayResult {
        data class Success(val song: Song, val willStream: Boolean) : PlayResult()
        data class Error(val message: String) : PlayResult()
        data class RetryWithSingle(val song: Song) : PlayResult()
    }
}
