package com.qhana.siku.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.imageLoader
import com.qhana.siku.R
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.coordinator.WorkerStatus
import com.qhana.siku.data.model.EqCustomPreset
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.ILyricsRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.data.repository.LyricsCandidatesResult
import com.qhana.siku.data.repository.LyricsResult
import com.qhana.siku.data.util.NetworkManager
import com.qhana.siku.domain.usecase.ParseLyricsUseCase
import com.qhana.siku.domain.usecase.PlaybackErrorRecoveryUseCase
import com.qhana.siku.domain.usecase.MusicPlaybackUseCase
import com.qhana.siku.player.MusicController
import com.qhana.siku.player.PlaybackCoordinator
import com.qhana.siku.player.audio.EqualizerAudioProcessor
import com.qhana.siku.ui.components.EqPresets
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.state.NowPlayingUiState
import com.qhana.siku.worker.DownloadScheduler
import com.qhana.siku.worker.WorkerTags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * ViewModel dedicado a la reproducción y UI de NowPlaying.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    val musicController: MusicController,
    private val artworkRepository: ArtworkRepository,
    private val lyricsRepository: ILyricsRepository,
    private val repository: IMusicRepository,
    private val parseLyricsUseCase: ParseLyricsUseCase,
    private val playbackErrorRecoveryUseCase: PlaybackErrorRecoveryUseCase,
    private val playbackUseCase: MusicPlaybackUseCase,
    private val musicPreferences: MusicPreferences,
    private val playbackCoordinator: PlaybackCoordinator,
    private val requestCoordinator: RequestCoordinator,
    private val networkManager: NetworkManager,
    private val downloadScheduler: DownloadScheduler,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager,
    private val syncManager: com.qhana.siku.data.coordinator.SyncManager,
    private val equalizerProcessor: com.qhana.siku.player.audio.EqualizerAudioProcessor,
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val TAG = "PlaybackViewModel"
        private const val MAX_RETRIES = 1
        // TTL para reintentar buscar letras tras un NotFound persistido. Pasado este
        // tiempo asumimos que algún colaborador pudo subirlas a LrcLib.
        private const val LYRICS_NOT_FOUND_RETRY_TTL_MS = 14L * 24 * 60 * 60 * 1000
    }

    private val retryCount = AtomicInteger(0)
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null

    private val _nowPlayingUiState = MutableStateFlow(NowPlayingUiState())
    val nowPlayingUiState: StateFlow<NowPlayingUiState> = _nowPlayingUiState.asStateFlow()

    // El feedback de red y del refresh de letras ahora va por el SnackbarManager centralizado
    // (bus singleton), no por eventos por-instancia colectados en MainActivity.

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loadingStatus = MutableStateFlow<String?>(null)
    val loadingStatus: StateFlow<String?> = _loadingStatus.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(musicPreferences.loadKeepScreenOn())
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    // Fondo del NowPlaying (sólido vs degradado). Observado desde DataStore porque el ajuste
    // se cambia en Ajustes (LibraryViewModel, otra instancia) y debe reflejarse en vivo aquí.
    val nowPlayingSolidBackground: StateFlow<Boolean> =
        musicPreferences.nowPlayingSolidBackgroundFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingSolidBackground())

    /** Barra de progreso ondulada del NowPlaying (mismo motivo de observación que el fondo). */
    val nowPlayingWavyProgress: StateFlow<Boolean> =
        musicPreferences.nowPlayingWavyProgressFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingWavyProgress())

    /**
     * Estilo de paleta del tema (nombre del enum `PaletteStyle`). Lo consume MainActivity, que
     * es quien monta `MusicPlayerTheme`; se observa del DataStore para que el cambio hecho en
     * Ajustes se vea al instante y sin recrear la Activity.
     */
    val themePaletteStyle: StateFlow<String> =
        musicPreferences.themePaletteStyleFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadThemePaletteStyle())

    val currentSong: StateFlow<Song?> = musicController.currentSong
    val playbackState: StateFlow<PlaybackState> = musicController.playbackState
    val isShuffleEnabled: StateFlow<Boolean> = musicController.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = musicController.repeatMode
    val currentPosition: StateFlow<Long> = musicController.currentPosition
    val duration: StateFlow<Long> = musicController.duration
    val playlist: StateFlow<List<Song>> = musicController.playlist
    val currentIndex: StateFlow<Int> = musicController.currentIndex
    val sleepTimer: StateFlow<MusicController.SleepTimerState?> = musicController.sleepTimer

    fun startSleepTimer(minutes: Int, finishSong: Boolean) =
        musicController.startSleepTimer(minutes * 60_000L, finishSong)

    fun cancelSleepTimer() = musicController.cancelSleepTimer()

    // --- Ecualizador ---
    // El toggle se PERSISTE y lo aplica MusicPlaybackService (observa eqEnabledFlow y
    // reconstruye la pipeline). Las ganancias y el nº de bandas van EN VIVO al processor
    // singleton (audibles al instante; cambiar 5↔10 no exige rebuild) y se persisten al
    // soltar el slider (commitEqGains) o al alternar el modo.

    // Observado del flow (no un MutableStateFlow local): Ajustes puede apagar el EQ propio
    // desde OTRA instancia de ViewModel (al activar "usar EQ del sistema") y la hoja debe
    // reflejarlo.
    val eqEnabled: StateFlow<Boolean> = musicPreferences.eqEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadEqEnabled())

    private val _eqBandCount = MutableStateFlow(musicPreferences.loadEqBandCount())
    val eqBandCount: StateFlow<Int> = _eqBandCount.asStateFlow()

    private val _eqGains = MutableStateFlow(
        musicPreferences.loadEqBandGains(musicPreferences.loadEqBandCount()).toList()
    )
    val eqGains: StateFlow<List<Float>> = _eqGains.asStateFlow()

    /** Preferencia de Ajustes: el botón EQ del NowPlaying abre el panel del sistema. */
    val useSystemEq: StateFlow<Boolean> = musicPreferences.useSystemEqFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadUseSystemEq())

    /** Config del toolbar (orden + barra/overflow). La barra del NowPlaying la observa en vivo. */
    val toolbarConfig: StateFlow<List<com.qhana.siku.data.model.ToolbarActionState>> =
        musicPreferences.toolbarConfigFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadToolbarConfig())

    fun setEqEnabled(enabled: Boolean) = musicPreferences.saveEqEnabled(enabled)

    // "No volver a mostrar" del aviso de doble ecualización al encender el EQ propio.
    // MutableStateFlow respaldado por la preferencia: la hoja debe reaccionar en la misma
    // sesión si el usuario lo marca (no basta con leer la preferencia al componer).
    private val _eqConflictWarningSuppressed =
        MutableStateFlow(musicPreferences.loadEqConflictWarningSuppressed())
    val eqConflictWarningSuppressed: StateFlow<Boolean> = _eqConflictWarningSuppressed.asStateFlow()

    fun suppressEqConflictWarning() {
        _eqConflictWarningSuppressed.value = true
        musicPreferences.saveEqConflictWarningSuppressed(true)
    }

    /**
     * Alterna 5↔10 bandas. Si la curva ACTUAL coincide con un preset (de fábrica o propio), ese
     * preset "sigue" al nuevo modo re-interpolado (y se persiste como curva de ese modo), para que
     * el selector siga marcándolo. Si es una curva manual, se recupera la propia guardada del modo
     * destino (cada modo recuerda la suya: las frecuencias centrales no se corresponden).
     */
    fun setEqBandCount(count: Int) {
        if (count == _eqBandCount.value) return
        val followed = followPresetGains(count)
        val gains = followed ?: musicPreferences.loadEqBandGains(count)
        equalizerProcessor.setBands(EqualizerAudioProcessor.bandsFor(count), gains)
        _eqBandCount.value = count
        _eqGains.value = gains.toList()
        musicPreferences.saveEqBandCount(count)
        // Solo persistimos si venimos siguiendo un preset (así no pisamos la curva manual del modo).
        if (followed != null) musicPreferences.saveEqBandGains(count, gains)
    }

    /**
     * Si la curva actual coincide con algún preset, devuelve ese preset re-interpolado a
     * [targetCount]; si no, null (curva manual). Cubre presets de fábrica y personalizados.
     */
    private fun followPresetGains(targetCount: Int): FloatArray? {
        val current = _eqGains.value
        val oldCount = _eqBandCount.value
        fun matches(g: FloatArray) = current.size == g.size &&
            current.indices.all { kotlin.math.abs(current[it] - g[it]) < 0.1f }

        EqPresets.ALL.forEach { p ->
            if (matches(EqPresets.gainsFor(p, oldCount))) return EqPresets.gainsFor(p, targetCount)
        }
        customEqPresets.value.forEach { p ->
            val freqs = EqualizerAudioProcessor.bandsFor(p.bandCount)
            if (matches(EqPresets.resample(p.gains, freqs, oldCount)))
                return EqPresets.resample(p.gains, freqs, targetCount)
        }
        return null
    }

    fun setEqBand(band: Int, db: Float) {
        equalizerProcessor.setBandGain(band, db)
        _eqGains.update { gains -> gains.mapIndexed { i, g -> if (i == band) db else g } }
    }

    /** Curva completa de una vez (presets): en vivo + persistida. */
    fun setEqGains(gains: FloatArray) {
        equalizerProcessor.setBandGains(gains)
        _eqGains.value = gains.toList()
        musicPreferences.saveEqBandGains(_eqBandCount.value, gains)
    }

    fun commitEqGains() =
        musicPreferences.saveEqBandGains(_eqBandCount.value, _eqGains.value.toFloatArray())

    fun resetEq() {
        val flat = FloatArray(_eqBandCount.value)
        equalizerProcessor.setBandGains(flat)
        _eqGains.value = flat.toList()
        // Aplana AMBOS modos, no solo el visible: "Restablecer" = EQ neutro; que el otro
        // modo conserve una curva escondida sorprendería al alternar 5↔10 después.
        musicPreferences.saveEqBandGains(5, FloatArray(5))
        musicPreferences.saveEqBandGains(10, FloatArray(10))
    }

    // --- Presets personalizados ---
    // Observado del flow (la hoja del EQ puede vivir en otra instancia del ViewModel).
    val customEqPresets: StateFlow<List<EqCustomPreset>> =
        musicPreferences.customEqPresetsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadCustomEqPresets())

    /** Captura la curva actual (con su modo de bandas) como preset con [name]. */
    fun saveCurrentAsEqPreset(name: String) {
        val trimmed = name.trim().ifBlank { return }
        val preset = EqCustomPreset(
            id = java.util.UUID.randomUUID().toString(),
            name = trimmed,
            bandCount = _eqBandCount.value,
            gains = _eqGains.value.toFloatArray()
        )
        musicPreferences.saveCustomEqPresets(customEqPresets.value + preset)
    }

    fun deleteEqPreset(id: String) {
        musicPreferences.saveCustomEqPresets(customEqPresets.value.filterNot { it.id == id })
    }

    /** Aplica un preset propio remuestreado al modo de bandas actual. */
    fun applyCustomEqPreset(preset: EqCustomPreset) {
        val gains = EqPresets.resample(
            preset.gains,
            EqualizerAudioProcessor.bandsFor(preset.bandCount),
            _eqBandCount.value
        )
        setEqGains(gains)
    }

    init {
        musicController.initialize()
        observeCurrentSong()
        observePlaybackErrors()
    }

    /**
     * El estado del NowPlaying se alimenta de TRES dominios con cadencia y criticidad
     * distintas. Fusionarlos en un solo combine+debounce fue la causa del skeleton de
     * varios segundos tras un arranque frío: el churn del scan reseteaba el debounce y
     * retenía también la identidad del tema.
     *  1. Identidad — qué canción suena. La sabe el controller EN MEMORIA: se pinta al
     *     instante, sin esperar a Room ni a WorkManager.
     *  2. Enriquecimiento — la fila de BD del tema (letras, colores, path local) y los
     *     side-effects por-canción. Cadencia baja, sin coalescing.
     *  3. Badges de descarga — WorkManager/coordinator/progreso. Churnea durante un sync;
     *     puede llegar tarde sin costo visual, así que se muestrea.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeCurrentSong() {
        // Dominio 1: identidad. Al cambiar el id se siembra el estado por-canción completo
        // con lo que ya hay en memoria (la sesión restaurada sale de la BD, así que trae
        // letras/colores reales); los otros dominios lo refinan después.
        viewModelScope.launch {
            currentSong.collect { song ->
                val previousId = _nowPlayingUiState.value.song?.id
                when {
                    song == null -> _nowPlayingUiState.value = NowPlayingUiState()
                    song.id != previousId -> _nowPlayingUiState.value = NowPlayingUiState(
                        song = song,
                        albumColors = song.colors,
                        hasManualColor = song.id in musicPreferences.loadManualColorIds(),
                        lyrics = song.lyrics,
                        lyricLines = parseLyricsUseCase(song.lyrics),
                        isDownloaded = song.isLocalAudio
                    )
                }
            }
        }

        // Dominio 2: la verdad de la BD para el tema actual. `distinctUntilChanged` es
        // obligatorio: Room invalida por TABLA y re-emite la misma fila con cada escritura
        // a `songs` (upserts del scan, letras/colores de otros temas).
        viewModelScope.launch {
            var previousDbSong: Song? = null
            currentSong
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId == null) flowOf(null)
                    else repository.getSongByIdFlow(songId).filterNotNull().distinctUntilChanged()
                }
                .collectLatest { dbSong ->
                    if (dbSong == null) {
                        previousDbSong = null
                    } else {
                        val previous = previousDbSong
                        previousDbSong = dbSong
                        onDbSongChanged(dbSong, previous)
                    }
                }
        }

        // Dominio 3: badges de descarga. Solo parchea sus campos; nunca toca `song`.
        viewModelScope.launch {
            currentSong
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId == null) flowOf(null) else getDownloadStatusFlow(songId)
                }
                .collect { status ->
                    _nowPlayingUiState.update {
                        // Redescarga (worker por-canción) cuenta como "descargando" AUNQUE el
                        // archivo local siga existiendo. El scan global solo cuenta como
                        // descargando si el tema aún NO está local.
                        it.copy(
                            isDownloading = status != null &&
                                (status.isSingleActive || (!it.isDownloaded && status.isGlobalActive)),
                            downloadProgress = status?.progress,
                            downloadStatusMessage = status?.statusMessage
                        )
                    }
                }
        }
    }

    private fun getDownloadStatusFlow(songId: String): Flow<DownloadStatus> {
        val downloadTag = WorkerTags.downloadTag(songId)
        val globalWorkFlow = workManager.getWorkInfosForUniqueWorkFlow(WorkerTags.SCAN_WORK_NAME)
        val singleDownloadFlow = workManager.getWorkInfosByTagFlow(downloadTag)
        val coordinatorStatusFlow = requestCoordinator.workerStatus
        // Progreso REAL: lo publica `SyncManager.activeDownloads` (el downloader lo alimenta
        // byte a byte). NO se lee de `WorkInfo.progress`: el SingleSongDownloadWorker delega
        // toda la descarga en SyncManager y nunca llama a `setProgress`, así que ese dato es
        // siempre 0f — por eso la barra de progreso no aparecía nunca.
        val activeDownloadsFlow = syncManager.activeDownloads
            .map { list -> list.firstOrNull { it.song.id == songId }?.progress }
            .distinctUntilChanged()

        return combine(
            globalWorkFlow, singleDownloadFlow, coordinatorStatusFlow, activeDownloadsFlow
        ) { globalInfos, singleInfos, coordinatorStatus, liveProgress ->
            val isGlobalRunning = globalInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            val activeSingleWork = singleInfos.find { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

            val statusMessage = when {
                activeSingleWork?.state == WorkInfo.State.ENQUEUED -> when (coordinatorStatus) {
                    is WorkerStatus.ThrottledByOneDrive -> context.getString(R.string.download_status_throttled)
                    is WorkerStatus.PausedForPriorityDownload -> context.getString(R.string.download_status_waiting_other)
                    else -> context.getString(R.string.download_status_queued)
                }
                activeSingleWork?.state == WorkInfo.State.RUNNING && liveProgress == null -> when (coordinatorStatus) {
                    is WorkerStatus.ThrottledByOneDrive -> context.getString(R.string.download_status_throttled)
                    else -> context.getString(R.string.download_status_preparing)
                }
                else -> null
            }
            DownloadStatus(isGlobalRunning, activeSingleWork != null, liveProgress, statusMessage)
        }
            // sample, NO debounce: estas fuentes no callan durante un sync (workerStatus
            // alterna por request, los WorkInfos transicionan) y debounce se resetea con
            // cada emisión → inanición. sample garantiza a lo sumo una emisión cada 200ms
            // sin poder retener el estado indefinidamente.
            .sample(200)
            .distinctUntilChanged()
    }

    /**
     * Dominio 2: la fila de BD del tema actual cambió (o es la primera emisión de un tema
     * nuevo). [previous] es la fila de BD anterior — null en la primera emisión — y sirve
     * para detectar transiciones REALES (carátula nueva, descarga completada) sin depender
     * del uiState, que el dominio 1 ya sembró con el mismo id.
     */
    private suspend fun onDbSongChanged(dbSong: Song, previous: Song?) {
        val isNewSong = dbSong.id != previous?.id
        // "Disponible offline": descargada de la nube (file://) o de la fuente local (content://).
        val isDownloaded = dbSong.isLocalAudio

        if (isNewSong && !dbSong.path.startsWith("file://") && dbSong.remoteId != null) {
            startAutoDownload(dbSong)
        }

        val parsedLines = parseLyricsUseCase(dbSong.lyrics)
        _nowPlayingUiState.update {
            it.copy(
                song = dbSong,
                lyrics = dbSong.lyrics,
                lyricLines = parsedLines,
                isDownloaded = isDownloaded
            )
        }

        if (isNewSong && shouldAttemptLyricsFetch(dbSong)) {
            prefetchLyrics(dbSong)
        }

        val artUriChanged = !isNewSong && dbSong.albumArtUri != null && dbSong.albumArtUri != previous?.albumArtUri
        val downloadCompleted = !isNewSong && previous?.isLocalAudio == false && isDownloaded
        val missingColors = _nowPlayingUiState.value.albumColors == null

        if (isNewSong || artUriChanged || downloadCompleted || missingColors) {
            if (downloadCompleted) {
                artworkRepository.invalidateCache(dbSong.id)
                dbSong.albumArtUri?.let { uri ->
                    val request = ImageRequest.Builder(context).data(uri).memoryCachePolicy(CachePolicy.WRITE_ONLY).diskCachePolicy(CachePolicy.WRITE_ONLY).build()
                    context.imageLoader.enqueue(request)
                }
            }
            // Sin chequeo de "color centinela": las extracciones fallidas ya no se persisten
            // (ArtworkRepository), así que un color guardado siempre es un resultado real —
            // incluido el negro legítimo de carátulas monocromas.
            val colors = dbSong.colors ?: artworkRepository.getAlbumColors(dbSong)
            _nowPlayingUiState.update { it.copy(albumColors = colors) }
            preloadVisualArt(dbSong)
            if (isNewSong) preloadNextSong(dbSong)
        }
    }

    fun toggleDownload() {
        val song = currentSong.value ?: return
        // LOCAL nunca se (re)descarga: ya vive en el dispositivo y no hay copia en la nube.
        // La UI lo filtra, pero el guard cubre cualquier camino que llegue igual.
        // OJO: por sourceType, NO por isLocalAudio — isLocalAudio también es true para una
        // canción de NUBE ya descargada (file://), y ahí "Redescargar" sí tiene que funcionar
        // (con isLocalAudio el botón quedaba visible pero muerto: la rama de abajo era código
        // inalcanzable).
        if (song.sourceType == SourceType.LOCAL) return
        val state = _nowPlayingUiState.value
        if (state.isDownloaded) {
            if (!isNetworkAvailable()) { snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG); return }
            // La re-descarga BORRA el archivo local: la canción que suena pasa a streaming ANTES
            // de encolarla, o se quedaría sin fuente a mitad de reproducción.
            musicController.switchCurrentToStreaming(song.id)
            viewModelScope.launch(Dispatchers.IO) {
                _nowPlayingUiState.update { it.copy(isDownloaded = false, isDownloading = true, downloadStatusMessage = context.getString(R.string.download_status_restarting)) }
                downloadScheduler.scheduleDownload(song.id, forceRedownload = true)
            }
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        } else if (state.isDownloading) {
            // Solo cancelamos el work: el finally del SingleSongDownloadWorker cancelado ya
            // llama endPriorityDownload(). Llamarlo también acá decrementaba el contador dos
            // veces y reanudaba el scan masivo aunque otra descarga prioritaria siguiera viva.
            workManager.cancelAllWorkByTag(WorkerTags.downloadTag(song.id))
            snackbarManager.show(context.getString(R.string.download_msg_cancelled, song.title))
        } else {
            if (!isNetworkAvailable()) { snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG); return }
            downloadScheduler.scheduleDownload(song.id)
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        }
    }

    private fun observePlaybackErrors() {
        viewModelScope.launch {
            musicController.playbackError.collect { errorInfo -> handlePlaybackError(errorInfo) }
        }
    }

    private fun preloadNextSong(currentSong: Song) {
        val currentList = playlist.value
        if (currentList.isEmpty()) return
        val nextIdx = (currentIndex.value + 1) % currentList.size
        val nextSong = currentList[nextIdx]
        if (nextSong.id == currentSong.id) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = playbackCoordinator.prepareSongForPlayback(nextSong)
                if (result is PlaybackCoordinator.PrepareResult.Success && result.urlRefreshed) {
                    withContext(Dispatchers.Main) { musicController.updateSong(result.song) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading next song ${nextSong.id}", e)
            }
        }
    }

    private fun preloadVisualArt(song: Song) {
        val uri = song.albumArtUriString ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { 
                ImageRequest.Builder(context).data(uri).size(900).build().also { context.imageLoader.enqueue(it) } 
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading visual art for song ${song.id}", e)
            }
        }
    }

    private suspend fun handlePlaybackError(errorInfo: PlaybackErrorInfo) {
        val song = currentSong.value ?: return
        val currentRetry = retryCount.incrementAndGet()

        _loadingStatus.value = context.getString(R.string.common_verifying)

        when (val result = playbackErrorRecoveryUseCase(song, errorInfo, currentRetry, MAX_RETRIES)) {
            is PlaybackErrorRecoveryUseCase.Result.Skip -> {
                // El motivo de skip ya viene localizado desde el UseCase y siempre se muestra
                // como estado (antes se decidía por prefijo del texto, frágil con i18n).
                _error.value = result.reason
                _loadingStatus.value = result.reason
                withContext(Dispatchers.Main) { musicController.next() }
                retryCount.set(0)
            }
            is PlaybackErrorRecoveryUseCase.Result.Healing -> {
                _loadingStatus.value = result.message
            }
            is PlaybackErrorRecoveryUseCase.Result.Retry -> {
                _loadingStatus.value = context.getString(R.string.common_recovering)
                withContext(Dispatchers.Main) { musicController.retryCurrentWithFreshUrl(result.song) }
                monitorPlaybackRecovery(song.id)
            }
            is PlaybackErrorRecoveryUseCase.Result.Ignore -> {
                _error.value = errorInfo.message
                _loadingStatus.value = null
            }
        }
    }

    private fun monitorPlaybackRecovery(songId: String) {
        viewModelScope.launch {
            // Espera acotada a que el retry arranque. Si no arranca, se SALTA la canción,
            // pero NO se marca corrupta: "no empezó a sonar en 15s" puede ser red lenta o
            // buffering largo — un diagnóstico permanente por un síntoma transitorio dejaba
            // canciones sanas marcadas para siempre. La corrupción real la detecta el
            // recovery use case por código de error del decoder.
            val started = kotlinx.coroutines.withTimeoutOrNull(15000) {
                musicController.playbackState.first { it == PlaybackState.PLAYING }
            } != null
            if (!started && musicController.currentSong.value?.id == songId) {
                withContext(Dispatchers.Main) { musicController.next() }
            }
        }
    }

    fun playSongs(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        retryCount.set(0)
        
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            
            val errorMsg = playbackUseCase.playSongs(songs, index)
            if (errorMsg != null) {
                _error.value = errorMsg
            } else {
                _loadingStatus.value = null
                val targetSong = songs.getOrNull(index)
                if (targetSong != null && !targetSong.path.startsWith("file://") && targetSong.remoteId != null) {
                    startAutoDownload(targetSong)
                }
            }
        }
    }

    fun playSongFromLibrary(
        clickedSong: Song,
        filter: SongFilter,
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ) {
        retryCount.set(0)

        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()

            when (val result = playbackUseCase.playFromLibrary(clickedSong, filter, query, sortOrder, sourceFilters)) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    _loadingStatus.value = null
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> {
                    _error.value = result.message
                }
                is MusicPlaybackUseCase.PlayResult.RetryWithSingle -> {
                    // playSongs reemplaza este job a propósito: se aborta el intento de
                    // reproducir la biblioteca y se reproduce solo este tema.
                    playSongs(listOf(result.song), 0)
                }
            }
        }
    }

    private suspend fun startAutoDownload(song: Song, forcePriority: Boolean = false, allowLocal: Boolean = false, forceRedownload: Boolean = false) {
        if (!forceRedownload && !allowLocal && song.path.startsWith("file://")) return
        if (!isNetworkAvailable()) return
        if (!forcePriority && !forceRedownload && !networkManager.isWifi()) return
        // getWorkInfosForUniqueWork(...).get() es bloqueante; fuera del main thread para no
        // congelarlo en cada cambio de canción (esta función corre desde viewModelScope).
        val existingWork = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(WorkerTags.downloadTag(song.id)).get()
        }
        if (existingWork.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } && !forceRedownload) return
        // Unique name por canción (downloadTag/repairTag por defecto). El viejo nombre global
        // compartido con KEEP hacía que, mientras se auto-descargaba la canción A, el auto-
        // download de la canción B se ignorara silenciosamente.
        downloadScheduler.scheduleDownload(song.id, forceRedownload = forceRedownload)
    }

    fun shufflePlay(songs: List<Song>) {
        if (songs.isEmpty()) return
        retryCount.set(0)
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            when (val result = playbackUseCase.playShuffled(songs)) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    _loadingStatus.value = null
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> _error.value = result.message
                else -> {}
            }
        }
    }

    /** Reproduce TODA la biblioteca en su orden natural (contraparte de [shuffleAllFromLibrary]). */
    fun playAllFromLibrary() {
        viewModelScope.launch {
            val songs = repository.getAllSongs()
            if (songs.isNotEmpty()) {
                musicPreferences.recordContext(PlaybackContext.LibraryAll)
                playSongs(songs, 0)
            }
        }
    }

    fun shuffleAllFromLibrary() {
        retryCount.set(0)
        playJob?.cancel()
        musicPreferences.recordContext(PlaybackContext.LibraryShuffle)
        playJob = viewModelScope.launch {
            when (val result = playbackUseCase.shuffleAllFromLibrary()) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    _loadingStatus.value = null
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> {
                    _error.value = result.message
                }
                else -> {}
            }
        }
    }

    private var fetchLyricsJob: kotlinx.coroutines.Job? = null
    private var prefetchLyricsJob: kotlinx.coroutines.Job? = null
    private var searchCandidatesJob: kotlinx.coroutines.Job? = null

    /**
     * Decide si vale la pena pegarle a la red por las letras de esta canción:
     * - Si no hay metadata REAL de tags, no (ver [hasRealMetadata]).
     * - Si ya tenemos lyrics (incluyendo el sentinel "[INSTRUMENTAL]"), no.
     * - Si nunca intentamos, sí.
     * - Si intentamos hace menos de TTL, no (cacheamos el NotFound).
     * - Si pasaron más de TTL desde el último intento, reintentar.
     */
    private fun shouldAttemptLyricsFetch(song: Song): Boolean {
        if (!hasRealMetadata(song)) return false
        if (song.lyrics != null) return false
        val attempted = song.lyricsAttemptedAt ?: return true
        return (System.currentTimeMillis() - attempted) > LYRICS_NOT_FOUND_RETRY_TTL_MS
    }

    /**
     * La búsqueda de letras solo tiene sentido con los TAGS del archivo. Antes de extraerlos
     * (canción de nube recién escaneada) el título es el NOMBRE DE ARCHIVO y el artista el
     * placeholder [AppConfig.UNKNOWN_ARTIST]: consultar LrcLib con eso es basura y, peor,
     * sellaba `lyricsAttemptedAt` (NotFound, TTL 14 días) bloqueando la búsqueda con la
     * metadata real que llegaba minutos después. Sin tags (el placeholder persiste tras la
     * extracción) no se busca nada automáticamente; queda la búsqueda manual.
     */
    private fun hasRealMetadata(song: Song): Boolean =
        song.artist.isNotBlank() && song.artist != AppConfig.UNKNOWN_ARTIST

    /**
     * Prefetch silencioso: busca lyrics en background sin mostrar loading en la UI.
     * Si el usuario abre el panel de lyrics después, las encuentra ya cacheadas.
     */
    private fun prefetchLyrics(song: Song) {
        prefetchLyricsJob?.cancel()
        prefetchLyricsJob = viewModelScope.launch {
            try {
                if (!isNetworkAvailable()) return@launch
                val result = lyricsRepository.getLyricsWithResult(song.title, song.artist, song.album, song.duration / 1000.0)
                if (currentSong.value?.id != song.id) return@launch
                when (result) {
                    is LyricsResult.Found -> {
                        repository.saveLyrics(song.id, result.lyrics)
                        // Update UI state only if no explicit fetch is in progress
                        if (!_nowPlayingUiState.value.isLyricsLoading) {
                            _nowPlayingUiState.update { it.copy(lyrics = result.lyrics, lyricLines = parseLyricsUseCase(result.lyrics)) }
                        }
                    }
                    is LyricsResult.NotFound -> {
                        // Persistir el NotFound para evitar re-pegarle a LrcLib en cada reproducción.
                        repository.markLyricsNotFound(song.id)
                    }
                    is LyricsResult.Error -> { /* Silencio: errores transitorios no se persisten */ }
                }
            } catch (_: Exception) { /* Silent — prefetch failures are not user-visible */ }
        }
    }

    fun fetchLyrics(force: Boolean = false) {
        val song = currentSong.value ?: return
        val currentState = _nowPlayingUiState.value
        // Solo NOT_FOUND corta el prefetch: un fallo de red o del proveedor SÍ debe reintentarse
        // solo (al volver a la canción), porque no dice nada sobre si la letra existe.
        if (!force && (currentState.isLyricsLoading || currentState.lyrics != null ||
                currentState.lyricsFailure == LyricsFailure.NOT_FOUND)) return
        // Sin tags reales no hay nada que preguntarle a LrcLib (el título sería el nombre de
        // archivo). Empty state de "no encontrada" — desde ahí queda la búsqueda manual — y
        // SIN persistir el intento: cuando la extracción traiga los tags, se buscará normal.
        if (!hasRealMetadata(song)) {
            _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NOT_FOUND, isLyricsLoading = false) }
            if (force) snackbarManager.show(context.getString(R.string.lyrics_no_metadata))
            return
        }
        // Snapshot de la letra anterior (antes del reset a null) para que un refresh manual
        // pueda decirle al usuario si LrcLib devolvió contenido distinto o el mismo.
        val previousLyrics = currentState.lyrics ?: song.lyrics
        // Cancel prefetch and wait for it to finish to avoid race conditions
        val prefetchToCancel = prefetchLyricsJob
        fetchLyricsJob?.cancel()
        fetchLyricsJob = viewModelScope.launch {
            try {
                prefetchToCancel?.cancelAndJoin()
                _nowPlayingUiState.update { it.copy(isLyricsLoading = true, lyricsFailure = null, lyricsError = null, lyrics = null, lyricLines = emptyList()) }
                if (!force) {
                    val cached = song.lyrics ?: repository.getSongById(song.id).getOrNull()?.lyrics
                    if (!cached.isNullOrBlank()) {
                        _nowPlayingUiState.update { it.copy(lyrics = cached, lyricLines = parseLyricsUseCase(cached), isLyricsLoading = false) }
                        return@launch
                    }
                }
                if (!isNetworkAvailable()) {
                    val offlineMsg = context.getString(R.string.common_no_offline_connection)
                    _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NO_NETWORK, lyricsError = offlineMsg) }
                    snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG)
                    if (force) snackbarManager.show(context.getString(R.string.common_error_format, offlineMsg))
                    return@launch
                }
                when (val result = lyricsRepository.getLyricsWithResult(song.title, song.artist, song.album, song.duration / 1000.0)) {
                    is LyricsResult.Found -> {
                        if (currentSong.value?.id != song.id) return@launch
                        _nowPlayingUiState.update { it.copy(lyrics = result.lyrics, lyricLines = parseLyricsUseCase(result.lyrics)) }
                        repository.saveLyrics(song.id, result.lyrics)
                        if (force) {
                            snackbarManager.show(context.getString(
                                if (result.lyrics == previousLyrics) R.string.lyrics_refresh_unchanged
                                else R.string.lyrics_refresh_updated
                            ))
                        }
                    }
                    is LyricsResult.NotFound -> {
                        if (currentSong.value?.id != song.id) return@launch
                        _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NOT_FOUND) }
                        repository.markLyricsNotFound(song.id)
                        if (force) snackbarManager.show(context.getString(R.string.lyrics_refresh_notfound))
                    }
                    is LyricsResult.Error -> {
                        if (currentSong.value?.id != song.id) return@launch
                        _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.PROVIDER_ERROR, lyricsError = result.message) }
                        if (force) snackbarManager.show(context.getString(R.string.common_error_format, result.message))
                    }
                }
            } catch (e: Exception) {
                if (currentSong.value?.id == song.id) {
                    _nowPlayingUiState.update {
                        it.copy(lyricsFailure = LyricsFailure.PROVIDER_ERROR, lyricsError = e.message ?: context.getString(R.string.common_error))
                    }
                }
                if (force) snackbarManager.show(context.getString(R.string.common_error_format, e.message ?: context.getString(R.string.common_error)))
            } finally {
                if (currentSong.value?.id == song.id) {
                    _nowPlayingUiState.update { it.copy(isLyricsLoading = false) }
                }
            }
        }
    }

    /**
     * Búsqueda manual: pide candidatos a LrcLib y los expone en el UI state para
     * que el usuario elija. No modifica las lyrics actuales hasta que se selecciona uno.
     */
    fun searchLyricsCandidates() {
        val song = currentSong.value ?: return
        searchCandidatesJob?.cancel()
        searchCandidatesJob = viewModelScope.launch {
            _nowPlayingUiState.update { it.copy(isSearchingCandidates = true, lyricsSearchError = null, lyricsCandidates = null) }
            if (!isNetworkAvailable()) {
                _nowPlayingUiState.update { it.copy(isSearchingCandidates = false, lyricsSearchError = context.getString(R.string.common_no_offline_connection)) }
                snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG)
                return@launch
            }
            val result = lyricsRepository.searchCandidates(song.title, song.artist)
            if (currentSong.value?.id != song.id) return@launch
            when (result) {
                is LyricsCandidatesResult.Found -> _nowPlayingUiState.update {
                    it.copy(isSearchingCandidates = false, lyricsCandidates = result.candidates)
                }
                is LyricsCandidatesResult.Empty -> _nowPlayingUiState.update {
                    it.copy(isSearchingCandidates = false, lyricsCandidates = emptyList())
                }
                is LyricsCandidatesResult.Error -> _nowPlayingUiState.update {
                    it.copy(isSearchingCandidates = false, lyricsSearchError = result.message)
                }
            }
        }
    }

    fun selectLyricsFromCandidate(candidate: LyricsCandidate) {
        val song = currentSong.value ?: return
        val resolved = candidate.resolvedLyrics ?: return
        viewModelScope.launch {
            repository.saveLyrics(song.id, resolved)
            if (currentSong.value?.id != song.id) return@launch
            _nowPlayingUiState.update {
                it.copy(
                    lyrics = resolved,
                    lyricLines = parseLyricsUseCase(resolved),
                    lyricsFailure = null,
                    lyricsError = null,
                    lyricsCandidates = null,
                    lyricsSearchError = null
                )
            }
        }
    }

    fun dismissLyricsSearch() {
        searchCandidatesJob?.cancel()
        _nowPlayingUiState.update {
            it.copy(isSearchingCandidates = false, lyricsCandidates = null, lyricsSearchError = null)
        }
    }

    fun playPause() = musicController.playPause()
    fun next() = musicController.next()
    fun previous() = musicController.previous()
    fun seekTo(position: Long) = musicController.seekTo(position)
    fun toggleShuffle() = musicController.toggleShuffle()
    fun toggleRepeatMode() = musicController.toggleRepeatMode()
    fun skipToIndex(index: Int) = musicController.playAt(index)
    fun reorderQueue(from: Int, to: Int) = musicController.reorderQueue(from, to)
    fun removeFromQueue(index: Int) = musicController.removeFromQueue(index)

    /** Guarda la cola actual como una lista nueva (crea + agrega todas las canciones). */
    fun saveQueueAsPlaylist(name: String) {
        val trimmed = name.trim()
        val ids = musicController.playlist.value.map { it.id }
        if (trimmed.isEmpty() || ids.isEmpty()) return
        viewModelScope.launch {
            val playlistId = repository.createPlaylist(trimmed)
            val added = repository.addSongsToPlaylist(playlistId, ids)
            snackbarManager.show(
                context.resources.getQuantityString(R.plurals.playlist_songs_added, added, added)
            )
        }
    }
    fun getAudioSessionId(): Int = musicController.getAudioSessionId()
    fun updatePosition() = musicController.updatePosition()
    fun toggleKeepScreenOn() { val v = !_keepScreenOn.value; _keepScreenOn.value = v; musicPreferences.saveKeepScreenOn(v) }
    fun toggleFavorite(id: String) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }
    private fun isNetworkAvailable(): Boolean = networkManager.isAvailable()

    fun refreshCurrentSongColors() {
        val id = currentSong.value?.id ?: return
        viewModelScope.launch {
            // force: esto solo corre tras "regenerar colores" (Ajustes) — acción explícita,
            // así que también pisa un color manual (y limpia su marca).
            artworkRepository.invalidateCache(id, force = true)
            val song = repository.getSongById(id).getOrNull() ?: return@launch
            val colors = artworkRepository.getAlbumColors(song)
            // force borró también la marca de manual: el color vuelve a ser una lectura del
            // análisis y el tema puede volver a aplicarle el criterio de acromático.
            _nowPlayingUiState.update { it.copy(albumColors = colors, hasManualColor = false) }
        }
    }

    fun showDebugInfo(isDarkTheme: Boolean) {
        val uri = currentSong.value?.albumArtUriString ?: return
        val savedColors = _nowPlayingUiState.value.albumColors
        viewModelScope.launch {
            artworkRepository.debugExtractColors(uri, isDarkTheme, savedColors)?.let { d ->
                _nowPlayingUiState.update { it.copy(debugInfo = d) }
            }
        }
    }
    fun clearDebugInfo() { _nowPlayingUiState.update { it.copy(debugInfo = null) } }

    fun overrideSongColor(color: Int, isDarkTheme: Boolean) {
        val current = currentSong.value ?: return
        viewModelScope.launch {
            artworkRepository.saveManualColor(current.id, current.album, color, isDarkTheme)
            val song = currentSong.value ?: return@launch
            val colors = artworkRepository.getAlbumColors(song)
            // hasManualColor: el tema debe aplicar ESTE color aunque tenga poca saturación —
            // es una elección explícita, no una lectura dudosa de la carátula.
            _nowPlayingUiState.update {
                it.copy(albumColors = colors, hasManualColor = true, debugInfo = null)
            }
        }
    }
    override fun onCleared() {
        super.onCleared()
        preloadJob?.cancel()
        fetchLyricsJob?.cancel()
        prefetchLyricsJob?.cancel()
        searchCandidatesJob?.cancel()
        playJob?.cancel()
    }
}

private data class DownloadStatus(val isGlobalActive: Boolean, val isSingleActive: Boolean, val progress: Float?, val statusMessage: String?)