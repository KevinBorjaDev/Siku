package com.qhana.siku.ui.viewmodel

import androidx.work.WorkManager
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import io.mockk.*
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Cerrar sesión de OneDrive desconecta UNA fuente, no la biblioteca entera: desde que existe la
 * fuente local (Fase 3), un `clearAllUserData()` aquí borraría la música local y las playlists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @RelaxedMockK lateinit var authManager: AuthManager
    @RelaxedMockK lateinit var repository: IMusicRepository
    @RelaxedMockK lateinit var artworkRepository: ArtworkRepository
    @RelaxedMockK lateinit var musicController: MusicController
    @RelaxedMockK lateinit var syncManager: SyncManager
    @RelaxedMockK lateinit var workManager: WorkManager
    @RelaxedMockK lateinit var musicPreferences: MusicPreferences

    private val testDispatcher = UnconfinedTestDispatcher()
    private val currentSong = MutableStateFlow<Song?>(null)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        every { musicController.currentSong } returns currentSong
        coEvery { authManager.tryRestoreSession() } returns true
        every { authManager.signOut() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun buildViewModel() = AuthViewModel(
        authManager = authManager,
        repository = { repository },
        artworkRepository = { artworkRepository },
        musicController = { musicController },
        syncManager = { syncManager },
        workManager = { workManager },
        musicPreferences = musicPreferences
    )

    @Test
    fun `logout borra solo las canciones de OneDrive, nunca toda la biblioteca`() = runTest {
        val viewModel = buildViewModel()

        viewModel.logout()

        coVerify(exactly = 1) { repository.clearSourceData(SourceType.ONEDRIVE) }
        coVerify(exactly = 0) { repository.clearAllUserData() }
        coVerify(exactly = 0) { repository.deleteAllPlaylists() }
        assertFalse(viewModel.isLoggedIn.value!!)
    }

    @Test
    fun `logout limpia el delta token para que el proximo scan sea completo`() = runTest {
        // Sin esto, al reconectar la cuenta el delta (que vive en DataStore, no en la BD) seguiría
        // apuntando al último cambio: el scan sería incremental sobre una tabla ya vacía y la
        // biblioteca quedaría en blanco hasta un pull-to-refresh manual.
        val viewModel = buildViewModel()

        viewModel.logout()

        verify(exactly = 1) { musicPreferences.clearDeltaToken() }
    }

    @Test
    fun `logout purga de la cola las canciones de OneDrive (no un stop global)`() = runTest {
        // El contrato cambió: en vez de parar el player si sonaba OneDrive, se purga la
        // fuente entera de la cola (purgeSource) — la música local sigue sonando y los
        // temas de OneDrive desaparecen aunque no fueran el actual.
        currentSong.value = song(SourceType.ONEDRIVE)
        val viewModel = buildViewModel()

        viewModel.logout()

        verify(exactly = 1) { musicController.purgeSource(SourceType.ONEDRIVE) }
        verify(exactly = 0) { musicController.stop() }
    }

    private fun song(sourceType: SourceType) = Song(
        id = sourceType.buildId("clave"),
        title = "T",
        artist = "A",
        album = "Al",
        duration = 1000L,
        sourceType = sourceType
    )
}
