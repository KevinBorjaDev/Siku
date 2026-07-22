package com.qhana.siku.player

import android.content.Context
import android.net.Uri
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import com.qhana.siku.data.util.FileAnalysisResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreparationChainTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val musicRepository: IMusicRepository = mockk(relaxed = true)
    private val sourceRegistry: MusicSourceRegistry = mockk(relaxed = true)
    private val audioFileAnalyzer: AudioFileAnalyzer = mockk(relaxed = true)
    private val androidContext: Context = mockk(relaxed = true)
    private lateinit var musicDir: File

    private fun song(
        id: String = "s1",
        path: String = "",
        remoteId: String? = null,
        artist: String = "Artist",
        album: String = "Album",
        albumArtUri: Uri? = mockk()
    ) = Song(
        id = id, title = "Song $id", artist = artist, album = album,
        duration = 1000L, path = path, remoteId = remoteId, albumArtUri = albumArtUri
    )

    private fun analysis(valid: Boolean): FileAnalysisResult =
        mockk(relaxed = true) { every { isValid } returns valid }

    @Before
    fun setup() {
        every { androidContext.filesDir } returns tempFolder.root
        musicDir = File(tempFolder.root, "music").apply { mkdirs() }
        coEvery { audioFileAnalyzer.analyzeFile(any()) } returns analysis(true)
    }

    // ===== LocalFileCheckStep =====

    @Test
    fun `si existe archivo local por id, actualiza el path a file y persiste`() = runTest {
        val localFile = File(musicDir, "s1.mp3").apply { writeText("audio data") }
        val step = LocalFileCheckStep(androidContext, musicRepository)
        val ctx = PreparationContext(song(id = "s1", path = "https://remota"))

        val result = step.execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals("file://${localFile.absolutePath}", ctx.song.path)
        coVerify { musicRepository.updateSongUrl("s1", "file://${localFile.absolutePath}") }
    }

    @Test
    fun `un path file roto se invalida en BD y se limpia del contexto`() = runTest {
        val step = LocalFileCheckStep(androidContext, musicRepository)
        val ctx = PreparationContext(song(id = "s1", path = "file:///no/existe/s1.mp3"))

        val result = step.execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals("", ctx.song.path)
        coVerify { musicRepository.updateSongUrl("s1", "") }
    }

    @Test
    fun `una descarga truncada de 0 bytes se borra e invalida`() = runTest {
        val localFile = File(musicDir, "s1.mp3").apply { createNewFile() }
        val step = LocalFileCheckStep(androidContext, musicRepository)
        val ctx = PreparationContext(song(id = "s1", path = "file://${localFile.absolutePath}"))

        step.execute(ctx)

        assertFalse(localFile.exists())
        assertEquals("", ctx.song.path)
        coVerify { musicRepository.updateSongUrl("s1", "") }
    }

    /**
     * Regresión: que el retriever no sepa leer el archivo NO es prueba de que esté roto (un
     * timeout o un contenedor exótico dan isValid=false sobre audio que ExoPlayer reproduce
     * bien). Antes se borraba el archivo y la canción volvía a "no descargada" → streaming.
     */
    @Test
    fun `un archivo que el analyzer no puede leer NO se borra`() = runTest {
        val localFile = File(musicDir, "s1.mp3").apply { writeText("garbage") }
        coEvery { audioFileAnalyzer.analyzeFile(any()) } returns analysis(false)
        val path = "file://${localFile.absolutePath}"
        val step = LocalFileCheckStep(androidContext, musicRepository)
        val ctx = PreparationContext(song(id = "s1", path = path))

        step.execute(ctx)

        assertTrue(localFile.exists())
        assertEquals(path, ctx.song.path)
        coVerify(exactly = 0) { musicRepository.updateSongUrl(any(), any()) }
    }

    @Test
    fun `un archivo local valido no se toca`() = runTest {
        val localFile = File(musicDir, "s1.mp3").apply { writeText("audio data") }
        val path = "file://${localFile.absolutePath}"
        val step = LocalFileCheckStep(androidContext, musicRepository)
        val ctx = PreparationContext(song(id = "s1", path = path))

        val result = step.execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals(path, ctx.song.path)
        assertTrue(localFile.exists())
        coVerify(exactly = 0) { musicRepository.updateSongUrl(any(), any()) }
    }

    // ===== UrlRefreshStep =====

    private fun urlStep(isCached: (String) -> Boolean = { false }) =
        UrlRefreshStep(musicRepository, sourceRegistry, isCached)

    @Test
    fun `cancion remota sin remoteId es un error`() = runTest {
        val ctx = PreparationContext(song(path = "", remoteId = null))

        val result = urlStep().execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Error)
    }

    @Test
    fun `cancion remota obtiene URL fresca, la persiste y marca streaming`() = runTest {
        coEvery { sourceRegistry.resolveDownloadUrl(any(), any()) } returns "https://dl/fresca"
        val ctx = PreparationContext(song(id = "s1", path = "", remoteId = "r1"))

        val result = urlStep().execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals("https://dl/fresca", ctx.song.path)
        assertTrue(ctx.urlRefreshed)
        assertTrue(ctx.willStream)
        coVerify { musicRepository.updateSongUrl("s1", "https://dl/fresca") }
    }

    @Test
    fun `si no se puede obtener URL devuelve error`() = runTest {
        coEvery { sourceRegistry.resolveDownloadUrl(any(), any()) } returns null
        val ctx = PreparationContext(song(path = "", remoteId = "r1"))

        val result = urlStep().execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Error)
    }

    @Test
    fun `cancion local no toca la red y no marca streaming`() = runTest {
        val ctx = PreparationContext(song(path = "file:///local/s1.mp3", remoteId = "r1"))

        val result = urlStep().execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertFalse(ctx.willStream)
        coVerify(exactly = 0) { sourceRegistry.resolveDownloadUrl(any(), any()) }
    }

    @Test
    fun `cancion cacheada en ExoPlayer no refresca URL pero si streamea`() = runTest {
        val ctx = PreparationContext(song(path = "https://dl/vieja", remoteId = "r1"))

        val result = urlStep(isCached = { true }).execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertFalse(ctx.urlRefreshed)
        assertTrue(ctx.willStream)
        coVerify(exactly = 0) { sourceRegistry.resolveDownloadUrl(any(), any()) }
    }

    // ===== MetadataFetchStep =====

    @Test
    fun `extrae y persiste metadata cuando falta`() = runTest {
        val incomplete = song(artist = "Unknown Artist", albumArtUri = null)
        val enriched = incomplete.copy(artist = "Artista Real")
        coEvery { sourceRegistry.extractMetadata(incomplete) } returns enriched
        val step = MetadataFetchStep(musicRepository, sourceRegistry)
        val ctx = PreparationContext(incomplete)

        val result = step.execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals(enriched, ctx.song)
        coVerify { musicRepository.updateSongMetadata(enriched) }
    }

    @Test
    fun `con metadata completa no llama a la red`() = runTest {
        val complete = song(artist = "Artista", album = "Disco")
        val step = MetadataFetchStep(musicRepository, sourceRegistry)

        val result = step.execute(PreparationContext(complete))

        assertTrue(result is PreparationStep.StepResult.Continue)
        coVerify(exactly = 0) { sourceRegistry.extractMetadata(any()) }
    }

    /**
     * Regresión: la falta de carátula es un estado PERMANENTE legítimo (el archivo no tiene
     * arte embebido). Antes disparaba extractMetadata en cada reproducción — y en streaming
     * eso es analyzeUrl por red en el camino crítico del play → "loading" eterno.
     */
    @Test
    fun `una cancion sin caratula pero con metadata completa no llama a la red`() = runTest {
        val noArt = song(artist = "Artista", album = "Disco", albumArtUri = null)
        val step = MetadataFetchStep(musicRepository, sourceRegistry)

        val result = step.execute(PreparationContext(noArt))

        assertTrue(result is PreparationStep.StepResult.Continue)
        coVerify(exactly = 0) { sourceRegistry.extractMetadata(any()) }
    }

    @Test
    fun `un fallo de metadata no es fatal y la cadena continua`() = runTest {
        val incomplete = song(artist = "Unknown Artist", albumArtUri = null)
        coEvery { sourceRegistry.extractMetadata(any()) } throws RuntimeException("red caida")
        val step = MetadataFetchStep(musicRepository, sourceRegistry)
        val ctx = PreparationContext(incomplete)

        val result = step.execute(ctx)

        assertTrue(result is PreparationStep.StepResult.Continue)
        assertEquals(incomplete, ctx.song)
        coVerify(exactly = 0) { musicRepository.updateSongMetadata(any()) }
    }
}
