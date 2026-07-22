package com.qhana.siku.data.source

import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.cache.UrlCache
import com.qhana.siku.data.coordinator.ArtworkHealingManager
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.remote.DeletedFacet
import com.qhana.siku.data.remote.FileFacet
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.remote.OneDriveItem
import com.qhana.siku.data.remote.OneDriveResponse
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.repository.OneDriveRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Tests del DESCUBRIMIENTO (delta de Graph) que antes vivía en `SyncManager.syncWithDelta` y
 * ahora es `OneDriveMusicSource.discover`. Verifican el procesamiento del delta, el manejo del
 * token, el 410 y la reconciliación del full scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneDriveMusicSourceTest {

    @RelaxedMockK lateinit var context: android.content.Context
    @RelaxedMockK lateinit var oneDriveApi: OneDriveApi
    @RelaxedMockK lateinit var authManager: AuthManager
    @RelaxedMockK lateinit var musicRepository: IMusicRepository
    @RelaxedMockK lateinit var musicPreferences: MusicPreferences
    @RelaxedMockK lateinit var artworkHealingManager: ArtworkHealingManager
    @RelaxedMockK lateinit var oneDriveRepository: OneDriveRepository

    private lateinit var urlCache: UrlCache
    private lateinit var source: OneDriveMusicSource

    private val token = "test-token"

    // Contexto de descubrimiento: no reporta ni detiene (equivalente a lo que hace el orquestador).
    private fun ctx() = DiscoverContext(reportScanning = { _, _ -> }, isStopped = { false })

    private fun audioItem(id: String, name: String = "$id.mp3", url: String? = "https://dl/$id") =
        OneDriveItem(id = id, name = name, file = FileFacet(), deleted = null, size = 100L, downloadUrl = url)

    private fun deletedItem(id: String) =
        OneDriveItem(id = id, name = null, file = null, deleted = DeletedFacet(), size = null, downloadUrl = null)

    private fun emptyDeltaResponse(deltaLink: String = "https://delta/final") =
        OneDriveResponse(value = emptyList(), nextLink = null, deltaLink = deltaLink)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        urlCache = UrlCache()
        source = OneDriveMusicSource(
            context, oneDriveApi, authManager, musicRepository, musicPreferences,
            artworkHealingManager, oneDriveRepository, urlCache
        )
        every { musicPreferences.loadDeltaToken() } returns null
        coEvery { musicRepository.getAllSongIds() } returns emptyList()
        coEvery { musicRepository.upsertSongs(any()) } returns AppResult.Success(0)
        coEvery { oneDriveApi.getDelta(any(), any()) } returns emptyDeltaResponse()
        every { authManager.getAccessToken() } returns flowOf(AuthResult.Success(token))
    }

    @Test
    fun `auth error lanza SourceAuthException y no toca la red`() = runTest {
        every { authManager.getAccessToken() } returns flowOf(AuthResult.Error("token invalido"))

        var thrown = false
        try { source.discover(force = false, ctx()) } catch (e: SourceAuthException) { thrown = true }

        assertTrue(thrown)
        coVerify(exactly = 0) { oneDriveApi.getDelta(any(), any()) }
    }

    @Test
    fun `force refresh limpia el delta token y ejecuta artwork healing`() = runTest {
        source.discover(force = true, ctx())

        coVerify(exactly = 1) { musicPreferences.clearDeltaToken() }
        coVerify(exactly = 1) { artworkHealingManager.heal() }
    }

    @Test
    fun `scan incremental usa el delta token guardado como URL`() = runTest {
        every { musicPreferences.loadDeltaToken() } returns "https://saved-delta-link"

        source.discover(force = false, ctx())

        coVerify(exactly = 1) { oneDriveApi.getDelta(token, "https://saved-delta-link") }
        coVerify(exactly = 0) { musicPreferences.clearDeltaToken() }
    }

    @Test
    fun `el delta procesa altas de audio, ignora no-audio y cuenta bajas`() = runTest {
        every { musicPreferences.loadDeltaToken() } returns "https://saved-delta-link"
        coEvery { oneDriveApi.getDelta(any(), any()) } returns OneDriveResponse(
            value = listOf(
                audioItem("s1", "uno.flac"),
                audioItem("doc", "documento.pdf"), // no es audio: se ignora
                deletedItem("borrada")
            ),
            nextLink = null,
            deltaLink = "https://delta/final"
        )
        val upsertSlot = slot<List<Song>>()
        coEvery { musicRepository.upsertSongs(capture(upsertSlot)) } returns AppResult.Success(1)

        val result = source.discover(force = false, ctx())

        // Fase 0: el id se guarda namespaced (`onedrive:<item.id>`); remoteId sigue crudo.
        assertEquals(listOf("onedrive:s1"), upsertSlot.captured.map { it.id })
        assertEquals(listOf("s1"), upsertSlot.captured.map { it.remoteId })
        coVerify(exactly = 1) { musicRepository.deleteSongs(listOf("onedrive:borrada")) }
        assertEquals(1, result.added)
        assertEquals(1, result.deleted)
    }

    @Test
    fun `al recibir deltaLink se persiste para el proximo scan incremental`() = runTest {
        source.discover(force = false, ctx())

        coVerify(exactly = 1) { musicPreferences.saveDeltaToken("https://delta/final") }
    }

    @Test
    fun `HTTP 410 resetea el delta token y reintenta el scan completo`() = runTest {
        every { musicPreferences.loadDeltaToken() } returns "https://expired-delta"
        val gone = HttpException(
            Response.error<Any>(410, "gone".toResponseBody("application/json".toMediaType()))
        )
        coEvery { oneDriveApi.getDelta(token, "https://expired-delta") } throws gone
        coEvery { oneDriveApi.getDelta(token, match { it.contains("/delta?") }) } returns emptyDeltaResponse()

        source.discover(force = false, ctx())

        coVerify(exactly = 1) { musicPreferences.clearDeltaToken() }
        coVerify(exactly = 1) { oneDriveApi.getDelta(token, match { it.contains("/delta?") }) }
    }

    @Test
    fun `full scan reconcilia eliminando canciones locales que ya no existen en remoto`() = runTest {
        every { musicPreferences.loadDeltaToken() } returns null // full scan
        coEvery { oneDriveApi.getDelta(any(), any()) } returns OneDriveResponse(
            value = listOf(audioItem("remota1")),
            nextLink = null,
            deltaLink = "https://delta/final"
        )
        coEvery { musicRepository.getAllSongIds() } returns listOf("onedrive:remota1", "onedrive:huerfana")
        coEvery { musicRepository.upsertSongs(any()) } returns AppResult.Success(1)

        val result = source.discover(force = false, ctx())

        coVerify(exactly = 1) { musicRepository.deleteSongs(listOf("onedrive:huerfana")) }
        assertEquals(1, result.deleted)
    }
}
