package com.qhana.siku.data.coordinator

import android.content.Context
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.manager.MusicDownloader
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.DiscoverResult
import com.qhana.siku.data.source.MusicSource
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.source.SourceAuthException
import com.qhana.siku.data.util.NetworkManager
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests del ORQUESTADOR genérico (Fase 2). El descubrimiento (delta) vive ahora en
 * `OneDriveMusicSource` y se testea aparte ([com.qhana.siku.data.source.OneDriveMusicSourceTest]);
 * aquí la fuente es un mock que sólo reporta cuántos cambios hubo. El foco es el pipeline de
 * descarga genérico (que resuelve URLs vía el registro) y el mapeo a SyncOutcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {

    @RelaxedMockK lateinit var context: Context
    @RelaxedMockK lateinit var musicRepository: IMusicRepository
    @RelaxedMockK lateinit var musicPreferences: MusicPreferences
    @RelaxedMockK lateinit var networkManager: NetworkManager
    @RelaxedMockK lateinit var musicDownloader: MusicDownloader
    @RelaxedMockK lateinit var requestCoordinator: RequestCoordinator
    @RelaxedMockK lateinit var authManager: AuthManager
    @RelaxedMockK lateinit var sourceRegistry: MusicSourceRegistry
    @RelaxedMockK lateinit var oneDriveSource: MusicSource
    @RelaxedMockK lateinit var artistImageRepository: com.qhana.siku.data.repository.ArtistImageRepository

    private lateinit var syncManager: SyncManager

    private val token = "test-token"

    private fun song(id: String, path: String = "", remoteId: String? = null) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        duration = 1000L,
        path = path,
        remoteId = remoteId
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        syncManager = SyncManager(
            context, musicRepository, musicPreferences, networkManager,
            musicDownloader, requestCoordinator, authManager, sourceRegistry,
            artistImageRepository
        )

        // Defaults seguros: sin trabajo pendiente, sin pausas, red y WiFi disponibles
        // Descargas ACTIVAS y sin tope de almacenamiento (comportamiento previo a la feature).
        every { musicPreferences.loadDownloadControlState() } returns com.qhana.siku.data.model.DownloadControlState.ACTIVE
        every { musicPreferences.loadStorageLimitBytes() } returns 0L
        every { requestCoordinator.shouldPauseScan() } returns false
        every { networkManager.isWifi() } returns true
        every { networkManager.isAvailable() } returns true
        coEvery { musicRepository.countSongsNeedingWork() } returns 0
        coEvery { musicRepository.getEarliestRetryAt() } returns null
        coEvery { musicRepository.getDownloadAttempts(any()) } returns 0
        every { authManager.getAccessToken() } returns flowOf(AuthResult.Success(token))
        // Sin esto, el mock relajado devolvería un File mockeado (no null) y TODAS las
        // canciones parecerían ya descargadas (el pipeline saltaría downloadFile).
        every { musicDownloader.findExistingDownload(any(), any()) } returns null

        // La fuente (OneDrive) está configurada y descubre 0 cambios por defecto; el registro no
        // resuelve URL (el pipeline cae a song.path, como antes).
        coEvery { sourceRegistry.activeSources() } returns listOf(oneDriveSource)
        coEvery { oneDriveSource.isConfigured() } returns true
        coEvery { oneDriveSource.discover(any(), any()) } returns DiscoverResult(0, 0)
        coEvery { sourceRegistry.resolveDownloadUrl(any(), any()) } returns null
    }

    // ===== Orquestación / auth =====

    @Test
    fun `si la fuente lanza SourceAuthException el sync termina en Error y Failed isAuthError`() = runTest {
        coEvery { oneDriveSource.discover(any(), any()) } throws SourceAuthException("token invalido")

        val outcome = syncManager.startSync(force = false)

        assertTrue(syncManager.state.value is SyncStatus.Error)
        assertTrue(outcome is SyncOutcome.Failed && outcome.isAuthError)
    }

    @Test
    fun `si una fuente falla las demas siguen y el sync completa`() = runTest {
        val localSource = io.mockk.mockk<MusicSource>(relaxed = true)
        coEvery { localSource.discover(any(), any()) } returns DiscoverResult(added = 5, deleted = 0)
        // OneDrive sin red; local sí escanea.
        coEvery { oneDriveSource.discover(any(), any()) } throws RuntimeException("sin red")
        coEvery { sourceRegistry.activeSources() } returns listOf(oneDriveSource, localSource)

        val outcome = syncManager.executeSync(force = false)

        assertTrue(outcome is SyncOutcome.Completed)
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(5, complete.newSongs)
    }

    @Test
    fun `discover se llama por cada fuente activa y los cambios se agregan`() = runTest {
        coEvery { oneDriveSource.discover(any(), any()) } returns DiscoverResult(added = 3, deleted = 2)

        syncManager.executeSync(force = false)

        coVerify(exactly = 1) { oneDriveSource.discover(false, any()) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(3, complete.newSongs)
        assertEquals(2, complete.deleted)
    }

    // ===== Cola de descargas =====

    @Test
    fun `sin WiFi no se descarga nada pero el sync completa`() = runTest {
        every { networkManager.isWifi() } returns false
        coEvery { musicRepository.countSongsNeedingWork() } returns 2
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns
            listOf(song("p1"), song("p2"))

        syncManager.executeSync(force = false)

        coVerify(exactly = 0) { musicDownloader.downloadFile(any(), any(), any(), any()) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(0, complete.downloaded)
        assertEquals(0, complete.failed)
    }

    @Test
    fun `si el archivo ya esta en disco no re-descarga, solo re-finaliza (healing)`() = runTest {
        // Caso healing: canción con needsMetadata=1 pero bytes ya descargados (p. ej. con la
        // extensión basura `.0`). No debe tocar la red — ni resolver URL ni downloadFile —
        // solo re-analizar vía finalizeDownload.
        val pending = song("onedrive:p1", path = "https://dl/p1")
        val existing = File("onedrive_p1.0")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        every { musicDownloader.findExistingDownload("onedrive:p1", any()) } returns existing
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(pending)

        syncManager.executeSync(force = false)

        coVerify(exactly = 0) { musicDownloader.downloadFile(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sourceRegistry.resolveDownloadUrl(any(), any()) }
        coVerify(exactly = 1) { musicDownloader.finalizeDownload(pending, existing) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(1, complete.downloaded)
    }

    @Test
    fun `con WiFi descarga las pendientes y reporta el contador`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Success(File("dummy.mp3"))
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(pending)

        syncManager.executeSync(force = false)

        coVerify(exactly = 1) { musicDownloader.downloadFile(pending, "https://dl/p1", false, any()) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(1, complete.downloaded)
        assertEquals(0, complete.failed)
    }

    @Test
    fun `una descarga fallida se registra en el contador y se persiste en BD`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Error("fallo de red")

        syncManager.executeSync(force = false)

        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(0, complete.downloaded)
        assertEquals(1, complete.failed)
        // Cola persistente: el fallo queda en BD (transient=true, 1 intento) con backoff.
        coVerify(exactly = 1) { musicRepository.markDownloadFailed("p1", any(), true, 1, any()) }
    }

    @Test
    fun `URL expirada con 4xx se refresca via la fuente y se reintenta una vez`() = runTest {
        val withRemote = song("p1", path = "", remoteId = "r1")
        coEvery { musicRepository.getSongById("p1") } returns AppResult.Success(withRemote)
        coEvery { sourceRegistry.resolveDownloadUrl(any(), false) } returns "https://dl/vieja"
        coEvery { sourceRegistry.resolveDownloadUrl(any(), true) } returns "https://dl/fresca"
        coEvery { musicDownloader.downloadFile(any(), "https://dl/vieja", any(), any()) } returns
            MusicDownloader.DownloadStage.Error("expired", httpCode = 401)
        coEvery { musicDownloader.downloadFile(any(), "https://dl/fresca", any(), any()) } returns
            MusicDownloader.DownloadStage.Success(File("dummy.mp3"))
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(withRemote)

        val result = syncManager.downloadSong("p1")

        assertTrue(result is MusicDownloader.Result.Success)
        coVerify(exactly = 1) { sourceRegistry.resolveDownloadUrl(any(), false) }
        coVerify(exactly = 1) { sourceRegistry.resolveDownloadUrl(any(), true) }
        coVerify(exactly = 1) { musicDownloader.downloadFile(any(), "https://dl/fresca", any(), any()) }
    }

    @Test
    fun `downloadSong con error de auth no toca la red`() = runTest {
        // La canción existe en BD (lectura local, sin red); el token es lo que falla.
        coEvery { musicRepository.getSongById("p1") } returns AppResult.Success(song("p1", remoteId = "r1"))
        every { authManager.getAccessToken() } returns flowOf(AuthResult.Error("sin sesion"))

        val result = syncManager.downloadSong("p1")

        assertTrue(result is MusicDownloader.Result.Error)
        coVerify(exactly = 0) { musicDownloader.downloadFile(any(), any(), any(), any()) }
    }

    // ===== Robustez de la cola (fase 1) =====

    @Test
    fun `un error transitorio se reintenta y puede terminar en exito`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returnsMany listOf(
            MusicDownloader.DownloadStage.Error("timeout", kind = MusicDownloader.ErrorKind.TRANSIENT),
            MusicDownloader.DownloadStage.Success(File("dummy.mp3"))
        )
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(pending)

        val outcome = syncManager.executeSync(force = false)

        assertTrue(outcome is SyncOutcome.Completed)
        coVerify(exactly = 2) { musicDownloader.downloadFile(any(), any(), any(), any()) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(1, complete.downloaded)
        assertEquals(0, complete.failed)
    }

    @Test
    fun `un error permanente no se reintenta dentro de la corrida`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Error("path traversal", kind = MusicDownloader.ErrorKind.PERMANENT)

        syncManager.executeSync(force = false)

        coVerify(exactly = 1) { musicDownloader.downloadFile(any(), any(), any(), any()) }
        assertEquals(1, (syncManager.state.value as SyncStatus.Complete).failed)
    }

    @Test
    fun `bateria baja detiene la cola y el outcome es Incomplete LOW_BATTERY`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returns
            MusicDownloader.DownloadStage.SkippedLowBattery

        val outcome = syncManager.executeSync(force = false)

        assertTrue(outcome is SyncOutcome.Incomplete)
        assertEquals(IncompleteReason.LOW_BATTERY, (outcome as SyncOutcome.Incomplete).reason)
    }

    @Test
    fun `sin WiFi el outcome es Incomplete NO_WIFI sin quemar la cola`() = runTest {
        every { networkManager.isWifi() } returns false
        coEvery { musicRepository.countSongsNeedingWork() } returns 2
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns
            listOf(song("p1"), song("p2"))

        val outcome = syncManager.executeSync(force = false)

        assertTrue(outcome is SyncOutcome.Incomplete)
        assertEquals(IncompleteReason.NO_WIFI, (outcome as SyncOutcome.Incomplete).reason)
        coVerify(exactly = 0) { musicDownloader.downloadFile(any(), any(), any(), any()) }
    }

    @Test
    fun `red perdida sin reconexion termina con outcome NETWORK_LOST`() = runTest {
        every { networkManager.isAvailable() } returns false
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns
            listOf(song("p1", path = "https://dl/p1"))

        val outcome = syncManager.executeSync(force = false)

        assertTrue(outcome is SyncOutcome.Incomplete)
        assertEquals(IncompleteReason.NETWORK_LOST, (outcome as SyncOutcome.Incomplete).reason)
        coVerify(exactly = 0) { musicDownloader.downloadFile(any(), any(), any(), any()) }
    }

    @Test
    fun `la ventana LIMIT llena de fallidas no detiene la cola - avanza con offset`() = runTest {
        // 50 canciones que fallan permanente al frente alfabético + 1 detrás del LIMIT.
        val failing = (1..50).map { song("f%02d".format(it), path = "https://dl/f$it") }
        val extra = song("zz-extra", path = "https://dl/extra")
        coEvery { musicRepository.countSongsNeedingWork() } returns 51
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(50, 0) } returns failing
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(50, 50) } returns listOf(extra)
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(50, 100) } returns emptyList()
        coEvery { musicDownloader.downloadFile(match { it.id != "zz-extra" }, any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Error("gone", httpCode = 404, kind = MusicDownloader.ErrorKind.PERMANENT)
        coEvery { musicDownloader.downloadFile(match { it.id == "zz-extra" }, any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Success(File("dummy.mp3"))
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(extra)

        syncManager.executeSync(force = false)

        coVerify(exactly = 1) { musicDownloader.downloadFile(match { it.id == "zz-extra" }, any(), any(), any()) }
        val complete = syncManager.state.value as SyncStatus.Complete
        assertEquals(1, complete.downloaded)
        assertEquals(50, complete.failed)
    }

    @Test
    fun `retryFailedDownloads sin productor activo pide reprogramacion al caller`() = runTest {
        coEvery { musicRepository.resetDownloadErrors() } returns listOf("p1")

        val handledInline = syncManager.retryFailedDownloads()

        assertEquals(false, handledInline)
        coVerify(exactly = 1) { musicRepository.resetDownloadErrors() }
    }

    @Test
    fun `una descarga exitosa limpia el estado de error persistido`() = runTest {
        val pending = song("p1", path = "https://dl/p1")
        coEvery { musicRepository.countSongsNeedingWork() } returns 1
        coEvery { musicRepository.getSongsNeedingMetadataOrDownload(any(), any()) } returns listOf(pending)
        coEvery { musicDownloader.downloadFile(any(), any(), any(), any()) } returns
            MusicDownloader.DownloadStage.Success(File("dummy.mp3"))
        coEvery { musicDownloader.finalizeDownload(any(), any()) } returns
            MusicDownloader.Result.Success(pending)

        syncManager.executeSync(force = false)

        coVerify(exactly = 1) { musicRepository.clearDownloadError("p1") }
    }

    @Test
    fun `con fallidas en backoff el outcome Completed trae el proximo reintento`() = runTest {
        coEvery { musicRepository.getEarliestRetryAt() } returns 123_456L

        val outcome = syncManager.executeSync(force = false)

        assertEquals(123_456L, (outcome as SyncOutcome.Completed).nextRetryAt)
    }
}
