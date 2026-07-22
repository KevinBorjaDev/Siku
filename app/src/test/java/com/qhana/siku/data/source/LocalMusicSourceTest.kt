package com.qhana.siku.data.source

import android.content.Context
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.AudioFileAnalyzer
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMusicSourceTest {

    @RelaxedMockK lateinit var context: Context
    @RelaxedMockK lateinit var musicPreferences: MusicPreferences
    @RelaxedMockK lateinit var musicRepository: IMusicRepository
    @RelaxedMockK lateinit var audioFileAnalyzer: AudioFileAnalyzer

    private lateinit var source: LocalMusicSource

    private fun ctx() = DiscoverContext(reportScanning = { _, _ -> }, isStopped = { false })

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        source = LocalMusicSource(context, musicPreferences, musicRepository, audioFileAnalyzer)
    }

    @Test
    fun `no esta configurada si no hay carpeta elegida`() = runTest {
        every { musicPreferences.loadLocalFolderUri() } returns null

        assertFalse(source.isConfigured())
    }

    @Test
    fun `esta configurada cuando hay carpeta elegida`() = runTest {
        every { musicPreferences.loadLocalFolderUri() } returns "content://tree/primary%3AMusic"

        assertTrue(source.isConfigured())
    }

    @Test
    fun `sin carpeta configurada discover no toca la BD y devuelve cero`() = runTest {
        every { musicPreferences.loadLocalFolderUri() } returns null

        val result = source.discover(force = false, ctx())

        assertEquals(0, result.added)
        assertEquals(0, result.deleted)
        coVerify(exactly = 0) { musicRepository.upsertSongs(any()) }
        coVerify(exactly = 0) { musicRepository.deleteSongs(any()) }
    }

    @Test
    fun `resolveDownloadUrl devuelve el content uri tal cual (no hay URL que firmar)`() = runTest {
        val local = Song(
            id = SourceType.LOCAL.buildId("Artista/tema.flac"),
            title = "tema", artist = "Artista", album = "Disco", duration = 1000L,
            path = "content://tree/doc/tema.flac",
            sourceType = SourceType.LOCAL
        )

        assertEquals("content://tree/doc/tema.flac", source.resolveDownloadUrl(local, forceRefresh = false))
    }

    @Test
    fun `el tipo de fuente es LOCAL`() {
        assertEquals(SourceType.LOCAL, source.type)
    }
}
