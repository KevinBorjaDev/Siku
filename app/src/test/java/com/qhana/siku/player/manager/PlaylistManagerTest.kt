package com.qhana.siku.player.manager

import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistManagerTest {

    private lateinit var manager: PlaylistManager

    private fun song(id: String, title: String = "Song $id") = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 1000L
    )

    private val songs = listOf(song("a"), song("b"), song("c"), song("d"))

    @Before
    fun setup() {
        manager = PlaylistManager()
    }

    @Test
    fun `setPlaylist establece lista, indice y resetea shuffle`() {
        manager.setPlaylist(songs, startIndex = 2)

        assertEquals(songs, manager.playlist.value)
        assertEquals(2, manager.currentIndex.value)
        assertFalse(manager.isShuffleEnabled.value)
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `setCurrentIndex ignora indices fuera de rango`() {
        manager.setPlaylist(songs, startIndex = 0)

        manager.setCurrentIndex(99)
        assertEquals(0, manager.currentIndex.value)

        manager.setCurrentIndex(-1)
        assertEquals(0, manager.currentIndex.value)

        manager.setCurrentIndex(3)
        assertEquals(3, manager.currentIndex.value)
    }

    @Test
    fun `getSongAt devuelve null fuera de rango`() {
        manager.setPlaylist(songs)

        assertEquals("b", manager.getSongAt(1)?.id)
        assertNull(manager.getSongAt(10))
        assertNull(manager.getSongAt(-1))
    }

    @Test
    fun `updateSong reemplaza la cancion sin alterar orden ni indice`() {
        manager.setPlaylist(songs, startIndex = 1)

        val updated = song("b", title = "Nueva B")
        val result = manager.updateSong(updated)

        assertEquals(1, result)
        assertEquals("Nueva B", manager.playlist.value[1].title)
        assertEquals(1, manager.currentIndex.value)
        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
    }

    @Test
    fun `updateSong devuelve -1 si la cancion no esta en la lista`() {
        manager.setPlaylist(songs)

        assertEquals(-1, manager.updateSong(song("zzz")))
    }

    @Test
    fun `updateSong tambien alcanza al orden original con aleatorio activo`() {
        manager.setPlaylist(songs, startIndex = 0)
        manager.setShuffle(true)

        manager.updateSong(song("d", title = "Nueva D"))
        manager.setShuffle(false)

        // Sin esto, apagar el aleatorio restauraba la versión vieja de la canción: la cola
        // barajada y el orden original guardan los mismos objetos, no solo los mismos ids.
        assertEquals("Nueva D", manager.playlist.value.first { it.id == "d" }.title)
    }

    @Test
    fun `shuffle mantiene la cancion actual en indice 0 y conserva todas las canciones`() {
        manager.setPlaylist(songs, startIndex = 2)

        manager.setShuffle(true)

        assertTrue(manager.isShuffleEnabled.value)
        assertEquals(0, manager.currentIndex.value)
        assertEquals("c", manager.getCurrentSongId())
        assertEquals(songs.map { it.id }.toSet(), manager.playlist.value.map { it.id }.toSet())
        assertEquals(songs.size, manager.playlist.value.size)
    }

    @Test
    fun `desactivar shuffle restaura el orden original y reubica el indice`() {
        manager.setPlaylist(songs, startIndex = 2)
        manager.setShuffle(true)

        manager.setShuffle(false)

        assertFalse(manager.isShuffleEnabled.value)
        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
        // La canción actual ("c") debe seguir siendo la actual tras restaurar
        assertEquals("c", manager.getCurrentSongId())
        assertEquals(2, manager.currentIndex.value)
    }

    @Test
    fun `moveItem reordena y devuelve el nuevo indice de la cancion actual`() {
        manager.setPlaylist(songs, startIndex = 0)

        // Mover "a" (índice 0) al final; la actual sigue siendo "a"
        val newIndex = manager.moveItem(fromIndex = 0, toIndex = 3, currentSongId = "a")

        assertEquals(3, newIndex)
        assertEquals(3, manager.currentIndex.value)
        assertEquals(listOf("b", "c", "d", "a"), manager.playlist.value.map { it.id })
    }

    @Test
    fun `moveItem con indices invalidos no altera nada`() {
        manager.setPlaylist(songs, startIndex = 1)

        val result = manager.moveItem(fromIndex = 10, toIndex = 0, currentSongId = "b")

        assertEquals(1, result)
        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
    }

    @Test
    fun `moveItem en lista vacia devuelve el indice actual`() {
        assertEquals(-1, manager.moveItem(0, 1, null))
    }

    @Test
    fun `clear vacia la lista y resetea el indice`() {
        manager.setPlaylist(songs, startIndex = 2)

        manager.clear()

        assertTrue(manager.playlist.value.isEmpty())
        assertEquals(-1, manager.currentIndex.value)
        assertNull(manager.getCurrentSongId())
        assertTrue(manager.getCurrentPlaylist().isEmpty())
    }

    @Test
    fun `setRepeatMode actualiza el estado`() {
        assertEquals(RepeatMode.OFF, manager.repeatMode.value)

        manager.setRepeatMode(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, manager.repeatMode.value)

        manager.setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, manager.repeatMode.value)
    }

    @Test
    fun `removeSongs sigue a la cancion actual cuando sobrevive`() {
        manager.setPlaylist(songs, startIndex = 2) // actual = "c"

        manager.removeSongs { it.id == "a" || it.id == "b" }

        assertEquals(listOf("c", "d"), manager.playlist.value.map { it.id })
        assertEquals(0, manager.currentIndex.value)
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `removeSongs deja el indice en el siguiente superviviente si la actual cae`() {
        manager.setPlaylist(songs, startIndex = 1) // actual = "b"

        manager.removeSongs { it.id == "b" || it.id == "c" }

        assertEquals(listOf("a", "d"), manager.playlist.value.map { it.id })
        // El siguiente superviviente tras "b" es "d" (posición 1 en la lista filtrada)
        assertEquals(1, manager.currentIndex.value)
        assertEquals("d", manager.getCurrentSongId())
    }

    @Test
    fun `removeSongs de la ultima cancion actual clampa al final`() {
        manager.setPlaylist(songs, startIndex = 3) // actual = "d"

        manager.removeSongs { it.id == "d" }

        assertEquals(listOf("a", "b", "c"), manager.playlist.value.map { it.id })
        assertEquals(2, manager.currentIndex.value)
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `removeSongs que vacia la lista resetea el indice`() {
        manager.setPlaylist(songs, startIndex = 0)

        manager.removeSongs { true }

        assertTrue(manager.playlist.value.isEmpty())
        assertEquals(-1, manager.currentIndex.value)
        assertNull(manager.getCurrentSongId())
    }

    @Test
    fun `removeSongs sin coincidencias no altera nada`() {
        manager.setPlaylist(songs, startIndex = 2)

        manager.removeSongs { it.id == "zzz" }

        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals(2, manager.currentIndex.value)
    }

    @Test
    fun `removeSongs con shuffle activo purga tambien el orden original`() {
        manager.setPlaylist(songs, startIndex = 2) // actual = "c"
        manager.setShuffle(true) // "c" queda en índice 0

        manager.removeSongs { it.id == "a" }

        assertEquals(3, manager.playlist.value.size)
        assertEquals("c", manager.getCurrentSongId())

        // Al apagar shuffle, el orden original restaurado tampoco contiene "a"
        manager.setShuffle(false)
        assertEquals(listOf("b", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `shuffle con una sola cancion no rompe nada`() {
        manager.setPlaylist(listOf(song("solo")), startIndex = 0)

        manager.setShuffle(true)

        assertEquals(0, manager.currentIndex.value)
        assertEquals("solo", manager.getCurrentSongId())
        assertEquals(1, manager.playlist.value.size)
    }

    // --- La cola y sus ids no pueden separarse -------------------------------------------------
    //
    // Hasta el 31 ago 2026 la clase guardaba las dos listas y los ids de cada una en cuatro campos
    // sueltos, sincronizados a mano en 24 sitios. `clear()` vaciaba tres de ellos y se dejaba
    // `originalPlaylistIds` con los ids de la cola anterior; al apagar el aleatorio esos ids viejos
    // pasaban a ser los de la cola VIVA, que estaba vacía. A partir de ahí el manager creía tener
    // cuatro posiciones que no existían.

    @Test
    fun `tras clear, apagar el aleatorio no resucita la cola anterior`() {
        manager.setPlaylist(songs, startIndex = 2)
        manager.clear()

        manager.setShuffle(false)

        assertTrue(manager.playlist.value.isEmpty())
        assertTrue(manager.getOriginalPlaylist().isEmpty())
        assertNull(manager.getCurrentSongId())
    }

    @Test
    fun `tras clear, un indice de la cola anterior sigue estando fuera de rango`() {
        // La consecuencia observable del descuadre: `setCurrentIndex` validaba contra los ids
        // viejos, así que aceptaba una posición sobre una lista vacía.
        manager.setPlaylist(songs, startIndex = 0)
        manager.clear()
        manager.setShuffle(false)

        manager.setCurrentIndex(2)

        assertEquals(-1, manager.currentIndex.value)
        assertNull(manager.getCurrentSongId())
    }

    // --- addToQueue / removeAt: no tenían cobertura y se reescribieron en la misma limpieza -----

    @Test
    fun `addToQueue anexa al final sin mover el indice actual`() {
        manager.setPlaylist(songs, startIndex = 1)

        val added = manager.addToQueue(listOf(song("e"), song("f")))

        assertEquals(listOf("e", "f"), added.map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), manager.playlist.value.map { it.id })
        assertEquals(1, manager.currentIndex.value)
        assertEquals("b", manager.getCurrentSongId())
    }

    @Test
    fun `addToQueue descarta las que ya estan y los duplicados del propio lote`() {
        manager.setPlaylist(songs, startIndex = 0)

        val added = manager.addToQueue(listOf(song("b"), song("e"), song("e")))

        assertEquals(listOf("e"), added.map { it.id })
        assertEquals(listOf("a", "b", "c", "d", "e"), manager.playlist.value.map { it.id })
    }

    @Test
    fun `addToQueue sin nada que anadir no toca la cola`() {
        manager.setPlaylist(songs, startIndex = 3)

        val added = manager.addToQueue(listOf(song("a"), song("c")))

        assertTrue(added.isEmpty())
        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals(3, manager.currentIndex.value)
    }

    @Test
    fun `addToQueue tambien crece el orden original`() {
        // Si no, apagar el aleatorio dejaría fuera lo encolado.
        manager.setPlaylist(songs, startIndex = 0)
        manager.setShuffle(true)

        manager.addToQueue(listOf(song("e")))

        assertTrue(manager.getOriginalPlaylist().any { it.id == "e" })
    }

    @Test
    fun `removeAt sigue a la cancion actual cuando cae una anterior`() {
        manager.setPlaylist(songs, startIndex = 2) // suena "c"

        manager.removeAt(0)

        assertEquals(listOf("b", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `removeAt de la cancion actual deja el indice sobre el siguiente superviviente`() {
        manager.setPlaylist(songs, startIndex = 1) // suena "b"

        manager.removeAt(1)

        assertEquals(listOf("a", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals("c", manager.getCurrentSongId())
    }

    @Test
    fun `removeAt de la ultima cancion actual clampa al final`() {
        manager.setPlaylist(songs, startIndex = 3) // suena "d"

        manager.removeAt(3)

        assertEquals(listOf("a", "b", "c"), manager.playlist.value.map { it.id })
        assertEquals(2, manager.currentIndex.value)
    }

    @Test
    fun `removeAt que vacia la lista resetea el indice`() {
        manager.setPlaylist(listOf(song("solo")), startIndex = 0)

        manager.removeAt(0)

        assertTrue(manager.playlist.value.isEmpty())
        assertEquals(-1, manager.currentIndex.value)
    }

    @Test
    fun `removeAt fuera de rango no altera nada`() {
        manager.setPlaylist(songs, startIndex = 1)

        manager.removeAt(9)

        assertEquals(listOf("a", "b", "c", "d"), manager.playlist.value.map { it.id })
        assertEquals(1, manager.currentIndex.value)
    }

    @Test
    fun `removeAt quita del orden original por id y no por posicion`() {
        // Con el aleatorio puesto las posiciones no se corresponden, así que quitar por índice
        // borraría del original una canción distinta de la que se quitó de la cola.
        manager.setPlaylist(songs, startIndex = 0)
        manager.setShuffle(true)
        val removedId = manager.playlist.value[1].id

        manager.removeAt(1)

        assertFalse(manager.getOriginalPlaylist().any { it.id == removedId })
        assertEquals(3, manager.getOriginalPlaylist().size)
    }
}
