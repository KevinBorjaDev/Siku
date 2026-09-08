package com.qhana.siku.player.manager

import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionStateManagerTest {

    private val prefs: MusicPreferences = mockk(relaxed = true)
    private val repo: IMusicRepository = mockk()
    private lateinit var manager: SessionStateManager

    private fun song(id: String) = Song(id = id, title = "T $id", artist = "A", album = "Al", duration = 1000L)

    @Before
    fun setup() {
        manager = SessionStateManager(prefs, repo)
    }

    // --- snapshotContext ---

    @Test
    fun `snapshotContext ignora cola vacia y clave en blanco`() {
        manager.snapshotContext("album:X", emptyList(), 0, 0, false, emptyList())
        manager.snapshotContext("", listOf(song("a")), 0, 0, false, emptyList())
        verify(exactly = 0) { prefs.saveContextResume(any(), any()) }
    }

    @Test
    fun `snapshotContext sin aleatorio no guarda el orden original`() {
        val slot = slot<ContextResumeSlot>()
        every { prefs.saveContextResume("album:X", capture(slot)) } just Runs

        val queue = listOf(song("a"), song("b"), song("c"))
        manager.snapshotContext("album:X", queue, 1, 45_000, shuffleEnabled = false, originalPlaylist = queue)

        assertEquals(listOf("a", "b", "c"), slot.captured.queueIds)
        assertTrue(slot.captured.originalQueueIds.isEmpty())
        assertEquals(1, slot.captured.index)
        assertEquals(45_000, slot.captured.position)
        assertEquals("b", slot.captured.songId)
        assertEquals(false, slot.captured.shuffleEnabled)
    }

    @Test
    fun `snapshotContext con aleatorio guarda cola barajada y orden original`() {
        val slot = slot<ContextResumeSlot>()
        every { prefs.saveContextResume(any(), capture(slot)) } just Runs

        val shuffled = listOf(song("c"), song("a"), song("b"))
        val original = listOf(song("a"), song("b"), song("c"))
        manager.snapshotContext("all", shuffled, 0, 0, shuffleEnabled = true, originalPlaylist = original)

        assertEquals(listOf("c", "a", "b"), slot.captured.queueIds)
        assertEquals(listOf("a", "b", "c"), slot.captured.originalQueueIds)
        assertTrue(slot.captured.shuffleEnabled)
    }

    // --- restoreContext ---

    @Test
    fun `restoreContext devuelve null si no hay slot`() = runTest {
        every { prefs.loadContextResume("album:X") } returns null
        assertNull(manager.restoreContext("album:X"))
    }

    @Test
    fun `restoreContext devuelve null si ninguna cancion sigue en la BD`() = runTest {
        every { prefs.loadContextResume("album:X") } returns
            ContextResumeSlot(listOf("a", "b"), emptyList(), false, 0, 0, "a")
        coEvery { repo.getSongsByIds(any()) } returns emptyList()
        assertNull(manager.restoreContext("album:X"))
    }

    @Test
    fun `restoreContext resuelve por songId y conserva la posicion`() = runTest {
        every { prefs.loadContextResume("album:X") } returns
            ContextResumeSlot(listOf("a", "b", "c"), emptyList(), false, 2, 30_000, "c")
        coEvery { repo.getSongsByIds(any()) } returns listOf(song("a"), song("b"), song("c"))

        val restored = manager.restoreContext("album:X")!!
        assertEquals(listOf("a", "b", "c"), restored.playlist.map { it.id })
        assertEquals(2, restored.index)
        assertEquals(30_000, restored.position)
        assertTrue(restored.originalPlaylist.isEmpty())
    }

    @Test
    fun `restoreContext reajusta el indice cuando una cancion previa fue borrada`() = runTest {
        // La cola era [a,b,c,d], sonaba "c" (índice 2). "a" se borró de la BD.
        every { prefs.loadContextResume("album:X") } returns
            ContextResumeSlot(listOf("a", "b", "c", "d"), emptyList(), false, 2, 12_000, "c")
        coEvery { repo.getSongsByIds(any()) } returns listOf(song("b"), song("c"), song("d"))

        val restored = manager.restoreContext("album:X")!!
        assertEquals(listOf("b", "c", "d"), restored.playlist.map { it.id })
        assertEquals(1, restored.index) // "c" ahora está en la posición 1
        assertEquals(12_000, restored.position)
    }

    @Test
    fun `restoreContext descarta la posicion si la cancion en curso ya no existe`() = runTest {
        every { prefs.loadContextResume("album:X") } returns
            ContextResumeSlot(listOf("a", "b", "c"), emptyList(), false, 1, 55_000, "b")
        coEvery { repo.getSongsByIds(any()) } returns listOf(song("a"), song("c"))

        val restored = manager.restoreContext("album:X")!!
        assertEquals(listOf("a", "c"), restored.playlist.map { it.id })
        assertEquals(1, restored.index) // cae al índice numérico guardado
        assertEquals(0, restored.position)
    }

    @Test
    fun `restoreContext reconstruye el orden barajado y el original`() = runTest {
        every { prefs.loadContextResume("all") } returns
            ContextResumeSlot(
                queueIds = listOf("c", "a", "b"),
                originalQueueIds = listOf("a", "b", "c"),
                shuffleEnabled = true,
                index = 0,
                position = 0,
                songId = "c"
            )
        coEvery { repo.getSongsByIds(any()) } returns listOf(song("a"), song("b"), song("c"))

        val restored = manager.restoreContext("all")!!
        assertEquals(listOf("c", "a", "b"), restored.playlist.map { it.id })
        assertEquals(listOf("a", "b", "c"), restored.originalPlaylist.map { it.id })
        assertTrue(restored.shuffleEnabled)
    }

    @Test
    fun `pruneContexts delega en preferencias`() {
        manager.pruneContexts(setOf("album:X", "favorites"))
        verify { prefs.pruneContextResume(setOf("album:X", "favorites")) }
    }
}
