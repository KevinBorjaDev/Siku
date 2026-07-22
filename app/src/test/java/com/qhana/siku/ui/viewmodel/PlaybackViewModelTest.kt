package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.work.WorkManager
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.coordinator.WorkerStatus
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.LyricsRepository
import com.qhana.siku.data.repository.MusicRepository
import com.qhana.siku.data.util.NetworkManager
import com.qhana.siku.domain.usecase.MusicPlaybackUseCase
import com.qhana.siku.domain.usecase.ParseLyricsUseCase
import com.qhana.siku.domain.usecase.PlaybackErrorRecoveryUseCase
import com.qhana.siku.player.MusicController
import com.qhana.siku.player.PlaybackCoordinator
import com.qhana.siku.worker.DownloadScheduler
import io.mockk.*
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    @RelaxedMockK lateinit var musicController: MusicController
    @RelaxedMockK lateinit var artworkRepository: ArtworkRepository
    @RelaxedMockK lateinit var lyricsRepository: LyricsRepository
    @RelaxedMockK lateinit var repository: MusicRepository
    @RelaxedMockK lateinit var parseLyricsUseCase: ParseLyricsUseCase
    @RelaxedMockK lateinit var playbackErrorRecoveryUseCase: PlaybackErrorRecoveryUseCase
    @RelaxedMockK lateinit var playbackUseCase: MusicPlaybackUseCase
    @RelaxedMockK lateinit var musicPreferences: MusicPreferences
    @RelaxedMockK lateinit var playbackCoordinator: PlaybackCoordinator
    @RelaxedMockK lateinit var requestCoordinator: RequestCoordinator
    @RelaxedMockK lateinit var networkManager: NetworkManager
    @RelaxedMockK lateinit var downloadScheduler: DownloadScheduler
    @RelaxedMockK lateinit var snackbarManager: com.qhana.siku.data.util.SnackbarManager
    @RelaxedMockK lateinit var syncManager: com.qhana.siku.data.coordinator.SyncManager
    @RelaxedMockK lateinit var context: Context
    @RelaxedMockK lateinit var workManager: WorkManager

    private lateinit var viewModel: PlaybackViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { musicController.currentSong } returns MutableStateFlow(null)
        every { musicController.playbackState } returns MutableStateFlow(com.qhana.siku.data.model.PlaybackState.IDLE)
        // Ahora playbackError es SharedFlow<PlaybackErrorInfo>
        every { musicController.playbackError } returns MutableSharedFlow<PlaybackErrorInfo>()
        every { musicController.isShuffleEnabled } returns MutableStateFlow(false)
        every { musicController.repeatMode } returns MutableStateFlow(com.qhana.siku.data.model.RepeatMode.OFF)
        every { musicController.currentPosition } returns MutableStateFlow(0L)
        every { musicController.duration } returns MutableStateFlow(0L)
        every { musicController.playlist } returns MutableStateFlow(emptyList())
        every { musicController.currentIndex } returns MutableStateFlow(0)

        every { musicPreferences.loadKeepScreenOn() } returns false
        every { requestCoordinator.workerStatus } returns MutableStateFlow(WorkerStatus.Idle)
        every { syncManager.activeDownloads } returns MutableStateFlow(emptyList())

        viewModel = PlaybackViewModel(
            musicController = musicController,
            artworkRepository = artworkRepository,
            lyricsRepository = lyricsRepository,
            repository = repository,
            parseLyricsUseCase = parseLyricsUseCase,
            playbackErrorRecoveryUseCase = playbackErrorRecoveryUseCase,
            playbackUseCase = playbackUseCase,
            musicPreferences = musicPreferences,
            playbackCoordinator = playbackCoordinator,
            requestCoordinator = requestCoordinator,
            networkManager = networkManager,
            downloadScheduler = downloadScheduler,
            snackbarManager = snackbarManager,
            syncManager = syncManager,
            equalizerProcessor = com.qhana.siku.player.audio.EqualizerAudioProcessor(),
            context = context,
            workManager = workManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playSongs delegates to playbackUseCase`() = runTest {
        val songs = listOf(Song(id = "1", title = "Song 1", artist = "A", album = "B", path = "", dateAdded = 0, duration = 0))
        coEvery { playbackUseCase.playSongs(any(), any()) } returns null

        viewModel.playSongs(songs, 0)
        advanceUntilIdle()

        coVerify { playbackUseCase.playSongs(songs, 0) }
    }

    @Test
    fun `shufflePlay delegates to playShuffled`() = runTest {
        val songs = listOf(
            Song(id = "1", title = "Song 1", artist = "A", album = "B", path = "", dateAdded = 0, duration = 0),
            Song(id = "2", title = "Song 2", artist = "A", album = "B", path = "", dateAdded = 0, duration = 0),
            Song(id = "3", title = "Song 3", artist = "A", album = "B", path = "", dateAdded = 0, duration = 0)
        )
        coEvery { playbackUseCase.playShuffled(any()) } returns
            MusicPlaybackUseCase.PlayResult.Success(songs[0], willStream = false)

        viewModel.shufflePlay(songs)
        advanceUntilIdle()

        // Delega la lista COMPLETA (sin barajar a mano): el modo shuffle real lo activa
        // el use case → controller, conservando el orden original.
        coVerify { playbackUseCase.playShuffled(match { it.size == 3 }) }
    }

    @Test
    fun `playPause delegates to musicController`() {
        viewModel.playPause()
        verify { musicController.playPause() }
    }
}
