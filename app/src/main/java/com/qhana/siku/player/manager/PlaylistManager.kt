package com.qhana.siku.player.manager

import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La verdad LÓGICA de la cola: qué canciones hay, en qué orden, dónde estamos y cuál era el orden
 * real antes de barajar. Quien reproduce es ExoPlayer, que tiene su propia copia posicional y a la
 * que `MusicController` replica cada cambio de aquí — ver la sección "Queue architecture" de
 * `CLAUDE.md`.
 *
 * **Aquí dentro hay DOS colas y ni una copia más** ([current] y [original]), y eso es el resultado
 * de una limpieza del 31 ago 2026. Antes eran CUATRO campos —la lista actual, sus ids, la original y
 * los suyos— más el `StateFlow`, o sea 24 sitios de asignación sosteniendo a mano tres derivados de
 * los otros dos. Nada obligaba a que coincidieran, y **ya no coincidían**: `clear()` vaciaba
 * `originalPlaylist` pero se dejaba `originalPlaylistIds` con los ids de la cola anterior. Ese fallo
 * es del tipo que no se ve —una búsqueda por id devuelve la posición equivocada— y ahora no puede
 * escribirse, porque los ids se calculan en el constructor de [Queue] y no hay forma de tener una
 * lista sin ellos o con los de otra.
 */
@Singleton
class PlaylistManager @Inject constructor() {

    /**
     * Una lista de canciones CON sus ids, atados.
     *
     * Los ids no son un adorno: `getCurrentSongId` se llama en cada transición de pista y
     * `addToQueue` necesita un conjunto para deduplicar, así que recalcular `map { it.id }` en cada
     * consulta sería O(n) sobre una cola que puede tener miles. Eran una caché mantenida a mano; al
     * vivir dentro de la clase siguen siendo caché y además no pueden quedar viejos.
     *
     * Es inmutable, así que compartir una instancia entre [current] y [original] es seguro: divergen
     * por reasignación, nunca por mutación.
     */
    private class Queue private constructor(
        val songs: List<Song>,
        /** Los ids de [songs], en su mismo orden. */
        val ids: List<String>
    ) {
        constructor(songs: List<Song>) : this(songs, songs.map { it.id })

        /**
         * La misma cola con [song] en la posición [index].
         *
         * **Reutiliza los ids** porque una sustitución no los cambia: sustituye la canción por otra
         * versión de SÍ MISMA (la que acaba de descargarse). Es lo que hacía la versión a mano —
         * `updateSong` actualizaba las listas y a propósito NO tocaba los ids— y se conserva porque
         * corre una vez por canción terminada durante un sync masivo.
         */
        fun replacingAt(index: Int, song: Song): Queue =
            Queue(songs.toMutableList().also { it[index] = song }, ids)

        /** La cola con [added] anexadas al final. Reutiliza los ids ya calculados. */
        fun appending(added: List<Song>): Queue =
            Queue(songs + added, ids + added.map { it.id })

        companion object {
            val EMPTY = Queue(emptyList(), emptyList())
        }
    }

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // Estado interno: toda mutación va dentro de `lock`.
    private val lock = Any()

    /** La cola que se está reproduciendo, en el orden en que se reproduce. */
    private var current: Queue = Queue.EMPTY

    /** La misma cola en su orden REAL, para poder volver a él al apagar el aleatorio. */
    private var original: Queue = Queue.EMPTY

    /**
     * ÚNICO sitio que publica la cola hacia fuera.
     *
     * Que sea uno es lo que mantiene [current] y `_playlist` imposibles de separar: antes eran dos
     * asignaciones seguidas repetidas en nueve métodos, y bastaba con olvidar una para que la lista
     * interna y la que ve la UI dejaran de ser la misma. Se llama siempre dentro de [lock].
     */
    private fun publish(queue: Queue, index: Int) {
        current = queue
        _playlist.value = queue.songs
        _currentIndex.value = index
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        synchronized(lock) {
            // `toList()` una sola vez: si el llamante conserva una referencia mutable, ni la cola
            // actual ni la original deben moverse bajo nuestros pies. La versión anterior protegía
            // solo la original, que era una asimetría sin motivo.
            val queue = Queue(songs.toList())
            // Una cola nueva empieza sin aleatorio, por diseño.
            _isShuffleEnabled.value = false
            original = queue
            publish(queue, startIndex)
        }
    }

    /**
     * Reemplaza una canción de la cola conservando orden e índice actual.
     *
     * @return la posición que ocupa en la cola, o -1 si no está. El índice, y no un booleano,
     *         porque quien actualiza la lista lógica tiene que replicar el cambio en la cola de
     *         ExoPlayer —que es posicional— y volver a buscarlo sería repetir esta misma búsqueda.
     */
    fun updateSong(newSong: Song): Int {
        synchronized(lock) {
            val index = current.ids.indexOfFirst { it == newSong.id }
            if (index == -1) return -1

            publish(current.replacingAt(index, newSong), _currentIndex.value)

            // El orden original guarda los MISMOS objetos: si se actualiza solo la cola visible,
            // apagar el aleatorio restauraría la versión vieja de la canción.
            val originalIdx = original.ids.indexOfFirst { it == newSong.id }
            if (originalIdx >= 0) original = original.replacingAt(originalIdx, newSong)
            return index
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int, currentSongId: String?): Int {
        synchronized(lock) {
            if (current.songs.isEmpty()) return _currentIndex.value
            val currentList = current.songs.toMutableList()
            if (fromIndex in currentList.indices && toIndex in currentList.indices) {
                val item = currentList.removeAt(fromIndex)
                currentList.add(toIndex, item)

                // Un movimiento conserva el CONJUNTO de ids pero no su orden, así que aquí sí hay
                // que recalcularlos (al revés que en `replacingAt`).
                val moved = Queue(currentList)
                val newIndex = currentSongId?.let { id -> currentList.indexOfFirst { it.id == id } } ?: -1
                publish(moved, if (newIndex >= 0) newIndex else _currentIndex.value)

                if (!_isShuffleEnabled.value) original = moved

                if (newIndex >= 0) return newIndex
            }
            return _currentIndex.value
        }
    }

    /**
     * Va dentro de [lock] como todo lo demás. Antes no lo estaba: leía los ids sin sincronizar
     * desde el hilo del player mientras cualquier otro los podía estar reemplazando.
     */
    fun setCurrentIndex(index: Int) {
        synchronized(lock) {
            if (index in current.songs.indices) {
                _currentIndex.value = index
            }
        }
    }

    fun getCurrentSongId(): String? = synchronized(lock) {
        val idx = _currentIndex.value
        if (idx in current.ids.indices) current.ids[idx] else null
    }

    fun getSongAt(index: Int): Song? = synchronized(lock) {
        current.songs.getOrNull(index)
    }

    fun setShuffle(enabled: Boolean) {
        synchronized(lock) {
            _isShuffleEnabled.value = enabled
            if (enabled) {
                // Se guarda el orden actual y se baraja dejando la canción en curso la primera.
                original = current
                val currentIdx = _currentIndex.value
                // Sin canción en curso no hay nada que barajar alrededor: el flag queda puesto y la
                // cola intacta, que es el comportamiento de siempre.
                val currentSong = current.songs.getOrNull(currentIdx) ?: return
                val others = current.songs.filterIndexed { i, _ -> i != currentIdx }.shuffled()
                publish(Queue(listOf(currentSong) + others), index = 0)
            } else {
                // Se vuelve al orden real y se busca en él la canción en curso. El id se pide ANTES
                // de publicar: después, el índice ya apuntaría a la lista restaurada.
                val currentId = getCurrentSongId()
                val restoredIdx =
                    if (currentId != null) original.ids.indexOfFirst { it == currentId } else 0
                publish(
                    original,
                    index = when {
                        // Cola vacía = SIN posición. Es el mismo -1 que usan `clear`, `removeAt` y
                        // `removeSongs`, y aquí faltaba: los dos caminos de abajo devuelven 0, así
                        // que apagar el aleatorio sobre una cola vacía dejaba el índice apuntando a
                        // una posición que no existe. Es la misma clase de descuadre que esta
                        // limpieza persigue —cola y posición no pueden contradecirse— solo que entre
                        // dos campos que sí sobreviven.
                        original.songs.isEmpty() -> -1
                        restoredIdx >= 0 -> restoredIdx
                        else -> 0
                    }
                )
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
                original = Queue(originalSongs.toList())
            }
        }
    }

    /** Cola en su orden REAL (sin barajar). Con shuffle apagado coincide con la cola actual. */
    fun getOriginalPlaylist(): List<Song> = synchronized(lock) { original.songs }

    /**
     * Elimina de la cola actual Y del orden original las canciones que cumplan [predicate],
     * siguiendo a la canción actual. Si la actual también se elimina, el índice queda sobre
     * el siguiente superviviente. No toca el flag de shuffle.
     */
    fun removeSongs(predicate: (Song) -> Boolean) {
        synchronized(lock) {
            if (current.songs.none(predicate)) return
            val currentId = getCurrentSongId()
            // Si la actual cae, su reemplazo natural es el siguiente superviviente: tras el
            // filtrado ocupa la posición = nº de supervivientes que había antes de la actual.
            val survivorsBefore = current.songs
                .take(_currentIndex.value.coerceAtLeast(0))
                .count { !predicate(it) }

            val newList = current.songs.filterNot(predicate)
            original = Queue(original.songs.filterNot(predicate))

            val followedIdx = currentId?.let { id -> newList.indexOfFirst { it.id == id } } ?: -1
            publish(
                Queue(newList),
                when {
                    newList.isEmpty() -> -1
                    followedIdx >= 0 -> followedIdx
                    else -> survivorsBefore.coerceAtMost(newList.lastIndex)
                }
            )
        }
    }

    /**
     * Elimina UNA posición de la cola (y del orden original la MISMA canción por id, una ocurrencia),
     * siguiendo a la actual: si cae la que se reproduce, el índice queda sobre quien ocupe esa
     * posición (el siguiente superviviente), en sync con `removeMediaItem` de ExoPlayer.
     */
    fun removeAt(index: Int) {
        synchronized(lock) {
            if (index !in current.songs.indices) return
            val currentId = getCurrentSongId()
            val removedId = current.ids[index]

            val newList = current.songs.toMutableList().apply { removeAt(index) }

            // Del orden original quitamos UNA ocurrencia del mismo id (no por índice: el orden
            // barajado no coincide posicionalmente con el original).
            val origIdx = original.ids.indexOfFirst { it == removedId }
            if (origIdx >= 0) {
                original = Queue(original.songs.toMutableList().apply { removeAt(origIdx) })
            }

            publish(
                Queue(newList),
                when {
                    newList.isEmpty() -> -1
                    // La actual sigue viva (no era la eliminada, o hay duplicado): seguirla por id.
                    currentId != null && currentId != removedId ->
                        newList.indexOfFirst { it.id == currentId }
                            .let { if (it >= 0) it else index.coerceAtMost(newList.lastIndex) }
                    // Se eliminó la actual: el siguiente superviviente ocupa la misma posición.
                    else -> index.coerceAtMost(newList.lastIndex)
                }
            )
        }
    }

    /**
     * Anexa [songs] al FINAL de la cola (y del orden original), SALTANDO las que ya están por id
     * —y los duplicados dentro del propio lote—. El id es la clave única de la cola (key de la
     * `LazyColumn` de la cola y `mediaId` de ExoPlayer), así que un duplicado rompería la lista
     * visible. No cambia el índice actual: se anexa detrás de todo, así que las posiciones previas
     * (incluida la que suena) no se mueven.
     *
     * Devuelve las canciones REALMENTE añadidas (tras el filtro), para que el llamador replique
     * EXACTAMENTE esos items en la cola de ExoPlayer. El orden original crece también (si no, apagar
     * el aleatorio dejaría fuera lo encolado); su posición ahí es el final, arbitraria bajo shuffle
     * —el mismo criterio que [removeAt], que quita por id sin mirar la posición barajada—.
     */
    fun addToQueue(songs: List<Song>): List<Song> {
        synchronized(lock) {
            val seen = current.ids.toHashSet()
            val toAdd = ArrayList<Song>(songs.size)
            for (song in songs) {
                if (seen.add(song.id)) toAdd.add(song)
            }
            if (toAdd.isEmpty()) return emptyList()

            publish(current.appending(toAdd), _currentIndex.value)
            original = original.appending(toAdd)

            return toAdd
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun clear() {
        synchronized(lock) {
            // Las DOS colas, y sus ids con ellas. La versión a mano vaciaba cuatro campos de cinco
            // y se dejaba `originalPlaylistIds` con los ids de la cola anterior; ese descuadre ya no
            // se puede escribir.
            original = Queue.EMPTY
            publish(Queue.EMPTY, index = -1)
        }
    }

    fun getCurrentPlaylist(): List<Song> = synchronized(lock) { current.songs }
}
