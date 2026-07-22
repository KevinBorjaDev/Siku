package com.qhana.siku.player.manager

import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistManager @Inject constructor() {

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // Internal state - all mutations must be synchronized via lock
    private val lock = Any()
    private var playlistIds: List<String> = emptyList()
    private var originalPlaylistIds: List<String> = emptyList()
    private var internalPlaylist: List<Song> = emptyList()
    private var originalPlaylist: List<Song> = emptyList()

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        synchronized(lock) {
            val ids = songs.map { it.id }
            internalPlaylist = songs
            _playlist.value = songs
            playlistIds = ids

            // Reset shuffle state for new playlist
            _isShuffleEnabled.value = false
            originalPlaylist = songs.toList()
            originalPlaylistIds = ids

            _currentIndex.value = startIndex
        }
    }

    fun updateSong(newSong: Song): Boolean {
        synchronized(lock) {
            val index = internalPlaylist.indexOfFirst { it.id == newSong.id }
            if (index == -1) return false

            val newList = internalPlaylist.toMutableList().also { it[index] = newSong }
            internalPlaylist = newList
            _playlist.value = newList

            // Update original playlist if not shuffled
            if (!_isShuffleEnabled.value) {
                originalPlaylist = newList.toList()
            }
            return true
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int, currentSongId: String?): Int {
        synchronized(lock) {
            if (internalPlaylist.isEmpty()) return _currentIndex.value
            val currentList = internalPlaylist.toMutableList()
            if (fromIndex in currentList.indices && toIndex in currentList.indices) {
                val item = currentList.removeAt(fromIndex)
                currentList.add(toIndex, item)

                internalPlaylist = currentList
                _playlist.value = currentList
                playlistIds = currentList.map { it.id }

                if (!_isShuffleEnabled.value) {
                    originalPlaylist = currentList.toList()
                    originalPlaylistIds = playlistIds
                }

                if (currentSongId != null) {
                    val newIndex = currentList.indexOfFirst { it.id == currentSongId }
                    if (newIndex >= 0) {
                        _currentIndex.value = newIndex
                        return newIndex
                    }
                }
            }
            return _currentIndex.value
        }
    }

    fun setCurrentIndex(index: Int) {
        if (index in playlistIds.indices) {
            _currentIndex.value = index
        }
    }

    fun getCurrentSongId(): String? = synchronized(lock) {
        val idx = _currentIndex.value
        if (idx in playlistIds.indices) playlistIds[idx] else null
    }

    fun getSongAt(index: Int): Song? = synchronized(lock) {
        internalPlaylist.getOrNull(index)
    }

    fun setShuffle(enabled: Boolean) {
        synchronized(lock) {
            _isShuffleEnabled.value = enabled
            if (enabled) {
                // Save original order, then shuffle keeping current song at index 0
                originalPlaylist = internalPlaylist.toList()
                originalPlaylistIds = playlistIds.toList()
                val currentIdx = _currentIndex.value
                val currentSong = internalPlaylist.getOrNull(currentIdx) ?: return
                val others = internalPlaylist.filterIndexed { i, _ -> i != currentIdx }.shuffled()
                val shuffled = listOf(currentSong) + others
                internalPlaylist = shuffled
                _playlist.value = shuffled
                playlistIds = shuffled.map { it.id }
                _currentIndex.value = 0
            } else {
                // Restore original order, find current song in restored list
                val currentId = getCurrentSongId()
                internalPlaylist = originalPlaylist
                _playlist.value = originalPlaylist
                playlistIds = originalPlaylistIds
                val restoredIdx = if (currentId != null) playlistIds.indexOfFirst { it == currentId } else 0
                _currentIndex.value = if (restoredIdx >= 0) restoredIdx else 0
            }
        }
    }

    /**
     * Restaura el estado de aleatorio de una sesión persistida SIN re-barajar: la cola que se
     * acaba de cargar YA viene en el orden barajado que se guardó, así que volver a llamar a
     * [setShuffle] la barajaría otra vez (y perdería el orden original). [originalSongs] es la
     * cola en su orden real, para poder volver a él al apagar el aleatorio.
     *
     * Debe llamarse DESPUÉS de [setPlaylist] (que resetea el flag por diseño: una cola nueva
     * empieza sin aleatorio).
     */
    fun restoreShuffleState(enabled: Boolean, originalSongs: List<Song>) {
        synchronized(lock) {
            _isShuffleEnabled.value = enabled
            if (enabled && originalSongs.isNotEmpty()) {
                originalPlaylist = originalSongs.toList()
                originalPlaylistIds = originalSongs.map { it.id }
            }
        }
    }

    /** Cola en su orden REAL (sin barajar). Con shuffle apagado coincide con la cola actual. */
    fun getOriginalPlaylist(): List<Song> = synchronized(lock) { originalPlaylist }

    /**
     * Elimina de la cola actual Y del orden original las canciones que cumplan [predicate],
     * siguiendo a la canción actual. Si la actual también se elimina, el índice queda sobre
     * el siguiente superviviente. No toca el flag de shuffle.
     */
    fun removeSongs(predicate: (Song) -> Boolean) {
        synchronized(lock) {
            if (internalPlaylist.none(predicate)) return
            val currentId = getCurrentSongId()
            // Si la actual cae, su reemplazo natural es el siguiente superviviente: tras el
            // filtrado ocupa la posición = nº de supervivientes que había antes de la actual.
            val survivorsBefore = internalPlaylist
                .take(_currentIndex.value.coerceAtLeast(0))
                .count { !predicate(it) }

            val newList = internalPlaylist.filterNot(predicate)
            internalPlaylist = newList
            _playlist.value = newList
            playlistIds = newList.map { it.id }

            originalPlaylist = originalPlaylist.filterNot(predicate)
            originalPlaylistIds = originalPlaylist.map { it.id }

            val followedIdx = currentId?.let { id -> newList.indexOfFirst { it.id == id } } ?: -1
            _currentIndex.value = when {
                newList.isEmpty() -> -1
                followedIdx >= 0 -> followedIdx
                else -> survivorsBefore.coerceAtMost(newList.lastIndex)
            }
        }
    }

    /**
     * Elimina UNA posición de la cola (y del orden original la MISMA canción por id, una ocurrencia),
     * siguiendo a la actual: si cae la que se reproduce, el índice queda sobre quien ocupe esa
     * posición (el siguiente superviviente), en sync con `removeMediaItem` de ExoPlayer.
     */
    fun removeAt(index: Int) {
        synchronized(lock) {
            if (index !in internalPlaylist.indices) return
            val currentId = getCurrentSongId()
            val removedId = internalPlaylist[index].id

            val newList = internalPlaylist.toMutableList().apply { removeAt(index) }
            internalPlaylist = newList
            _playlist.value = newList
            playlistIds = newList.map { it.id }

            // Del orden original quitamos UNA ocurrencia del mismo id (no por índice: el orden
            // barajado no coincide posicionalmente con el original).
            val origIdx = originalPlaylist.indexOfFirst { it.id == removedId }
            if (origIdx >= 0) {
                originalPlaylist = originalPlaylist.toMutableList().apply { removeAt(origIdx) }
                originalPlaylistIds = originalPlaylist.map { it.id }
            }

            _currentIndex.value = when {
                newList.isEmpty() -> -1
                // La actual sigue viva (no era la eliminada, o hay duplicado): seguirla por id.
                currentId != null && currentId != removedId ->
                    newList.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else index.coerceAtMost(newList.lastIndex) }
                // Se eliminó la actual: el siguiente superviviente ocupa la misma posición.
                else -> index.coerceAtMost(newList.lastIndex)
            }
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }
    
    fun clear() {
        synchronized(lock) {
            _currentIndex.value = -1
            _playlist.value = emptyList()
            internalPlaylist = emptyList()
            originalPlaylist = emptyList()
            playlistIds = emptyList()
        }
    }

    fun getCurrentPlaylist(): List<Song> = synchronized(lock) { internalPlaylist }
}
