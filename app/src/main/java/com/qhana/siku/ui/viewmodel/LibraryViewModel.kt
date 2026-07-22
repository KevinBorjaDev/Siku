package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.state.LibraryUiState
import com.qhana.siku.worker.DownloadScheduler
import com.qhana.siku.worker.WorkerTags
import com.qhana.siku.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Dato de bienvenida del inicio: escuchas de la semana + tamaño de biblioteca (respaldo). */
data class HomeStats(val playedThisWeek: Int, val librarySize: Int)

/** Sección generada del inicio: catálogo del artista más escuchado. */
data class HomeArtistPick(val artist: String, val songs: List<Song>)

sealed class LibraryBannerState {
    data class Scanning(val progress: Int, val message: String) : LibraryBannerState()
    data class Downloading(val current: Int, val total: Int, val failed: Int, val status: String) : LibraryBannerState()
    data class Complete(val newSongs: Int, val downloaded: Int, val failed: Int, val deleted: Int = 0) : LibraryBannerState()
    data class Error(val message: String, val canRetry: Boolean = true) : LibraryBannerState()
    object Hidden : LibraryBannerState()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager,
    private val repository: IMusicRepository,
    private val artworkRepository: ArtworkRepository,
    private val musicPreferences: MusicPreferences,
    private val musicController: MusicController,
    private val syncManager: SyncManager,
    private val downloadScheduler: DownloadScheduler,
    private val workManager: WorkManager,
    private val networkManager: com.qhana.siku.data.util.NetworkManager,
    private val sourceRegistry: com.qhana.siku.data.source.MusicSourceRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var bannerDismissJob: Job? = null

    // Evento tipado "colores regenerados" (antes MainActivity lo detectaba por el texto del
    // mensaje con `contains("regenerados")`, frágil con i18n).
    private val _colorsRegeneratedEvent = MutableSharedFlow<Unit>()
    val colorsRegeneratedEvent: SharedFlow<Unit> = _colorsRegeneratedEvent.asSharedFlow()

    // Canciones con re-descarga en curso (para deshabilitar el botón en la UI).
    private val _redownloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val redownloadingIds: StateFlow<Set<String>> = _redownloadingIds.asStateFlow()

    /**
     * Progreso por canción de las descargas EN VUELO (0..1), para pintarlo en la lista.
     * La fuente es `SyncManager.activeDownloads` —el downloader lo alimenta byte a byte—, NO
     * `WorkInfo.progress`: el worker delega la descarga en SyncManager y nunca llama a
     * `setProgress`, así que ese dato es siempre 0 (por eso no se veía ningún progreso).
     *
     * Solo descargas INDIVIDUALES (pedidas por el usuario / prioritarias): durante un sync
     * masivo hasta 32 filas animándose a la vez era ruido redundante con el banner y el
     * Download Manager, que ya muestran ese avance.
     */
    val downloadProgressById: StateFlow<Map<String, Float>> = syncManager.activeDownloads
        .map { active -> active.filter { it.individual }.associate { it.song.id to it.progress } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Indicador del pull-to-refresh. Vive acá (no en la pantalla) porque quien sabe
    // cuándo el sync realmente arrancó es el colector de syncManager.state.
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // --- Paging Flows (Derived from UiState) ---
    // IMPORTANT: .catch must be INSIDE flatMapLatest, not outside.
    // If placed outside, catching an error terminates the entire flow permanently,
    // making it impossible to recover (e.g., clearing search query has no effect).
    val pagedSongs: Flow<PagingData<Song>> = _uiState
        .map { Triple(it.searchQuery, it.sortOrderAll, it.sourceFilters) }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder, sourceFilters) ->
            repository.getSongsPaging(query, sortOrder, sourceFilters)
                .catch { e ->
                    snackbarManager.show(context.getString(R.string.library_error_loading, e.message ?: e.javaClass.simpleName))
                    emit(PagingData.empty())
                }
        }
        .cachedIn(viewModelScope)

    // Búsqueda DESACOPLADA de los chips de origen: el overlay busca SIEMPRE en toda la
    // biblioteca. Compartiendo pagedSongs heredaba los filtros activos y su "sin
    // resultados" podía ser mentira (las canciones existían, las escondía un chip que el
    // overlay ni muestra).
    val pagedSearchSongs: Flow<PagingData<Song>> = _uiState
        .map { it.searchQuery to it.sortOrderAll }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder) ->
            repository.getSongsPaging(query, sortOrder)
                .catch { e ->
                    snackbarManager.show(context.getString(R.string.library_error_loading, e.message ?: e.javaClass.simpleName))
                    emit(PagingData.empty())
                }
        }
        .cachedIn(viewModelScope)

    val pagedFavorites: Flow<PagingData<Song>> = _uiState
        .map { Pair(it.searchQuery, it.sortOrderFavorites) }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder) ->
            repository.getFavoritesPaging(query, sortOrder)
                .catch { e ->
                    snackbarManager.show(context.getString(R.string.library_error_loading_favorites, e.message ?: e.javaClass.simpleName))
                    emit(PagingData.empty())
                }
        }
        .cachedIn(viewModelScope)

    // Reactivo a los chips de origen: es el número del chip "N canciones" sobre la lista,
    // así que debe contar lo mismo que la lista muestra (sin filtros = total).
    val songCount: StateFlow<Int> = _uiState
        .map { it.sourceFilters }
        .distinctUntilChanged()
        .flatMapLatest { repository.getSongCountFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Chip de origen de la pestaña Todas: alterna su presencia en el set (unión). */
    fun toggleSourceFilter(filter: SongSourceFilter) {
        _uiState.update { state ->
            val current = state.searchFilter.sourceFilters
            val updated = if (filter in current) current - filter else current + filter
            state.copy(searchFilter = state.searchFilter.copy(sourceFilters = updated))
        }
    }

    // Visibilidad de los chips por CONTENIDO real: con biblioteca solo-local no hay nada
    // que filtrar (todo es local); con solo-nube el chip Local sobra pero Descargadas/Nube
    // siguen distinguiendo offline vs streaming.
    val hasLocalSongs: StateFlow<Boolean> = repository.hasLocalSongsFlow()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasCloudSongs: StateFlow<Boolean> = repository.hasCloudSongsFlow()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Saneo: si una familia desaparece (p. ej. logout de la nube o quitar la carpeta
        // local) con su chip seleccionado, el filtro quedaría ACTIVO pero INVISIBLE —
        // una lista filtrada sin forma de quitar el filtro. Se limpia solo.
        viewModelScope.launch {
            combine(hasLocalSongs, hasCloudSongs) { local, cloud -> local to cloud }
                .collect { (local, cloud) ->
                    _uiState.update { state ->
                        val sanitized = state.searchFilter.sourceFilters.filterTo(mutableSetOf()) { f ->
                            if (f == SongSourceFilter.LOCAL) local else cloud
                        }
                        if (sanitized == state.searchFilter.sourceFilters) state
                        else state.copy(searchFilter = state.searchFilter.copy(sourceFilters = sanitized))
                    }
                }
        }
    }

    // --- Secciones de la pantalla de inicio ---
    // Reactivas al historial (v22): cada escucha contada actualiza estas listas sin recargar
    // la biblioteca. Se mantienen vivas 5s tras perder el último suscriptor (cambio de tab).
    // "Seguir escuchando" = últimos CONTEXTOS reproducidos (álbum/artista/lista/favoritos/
    // aleatorio/biblioteca), no canciones sueltas del historial. Cada uno reanudable como tal.
    val recentContexts: StateFlow<List<PlaybackContext>> = musicPreferences.recentContextsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Antepone un contexto al historial de "Seguir escuchando" (dedup + tope). */
    fun recordContext(ctx: PlaybackContext) = musicPreferences.recordContext(ctx)

    val homeMostPlayed: StateFlow<List<Song>> = repository.getMostPlayed(HOME_SECTION_LIMIT)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeRecentlyAdded: StateFlow<List<Song>> = repository.getRecentlyAdded(HOME_SECTION_LIMIT)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Datos del saludo del inicio: escuchas de la última semana + tamaño de biblioteca (respaldo
    // cuando la semana está vacía). La ventana se fija al construir el ViewModel (se refresca al
    // reiniciar el proceso), suficiente para un dato de bienvenida.
    val homeStats: StateFlow<HomeStats> = combine(
        repository.getPlayedSinceCount(System.currentTimeMillis() - WEEK_MILLIS),
        repository.getLibrarySize()
    ) { week, size -> HomeStats(week, size) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats(0, 0))

    // Sección generada "Porque escuchaste a X": el catálogo del artista más escuchado. Reactiva
    // en dos niveles — cambia de artista cuando el historial lo hace, y refleja altas/bajas de
    // canciones de ese artista.
    val homeArtistPick: StateFlow<HomeArtistPick?> = repository.getTopPlayedArtist(MIN_ARTIST_PICK_SONGS)
        .distinctUntilChanged()
        .flatMapLatest { artist ->
            if (artist.isNullOrBlank()) flowOf(null)
            else repository.getSongsByArtist(artist).map { songs ->
                // Guarda de respaldo: el propio query ya exige MIN_ARTIST_PICK_SONGS, pero por si
                // acaso no mostramos un carrusel de menos de ese tamaño.
                songs.take(HOME_SECTION_LIMIT)
                    .takeIf { it.size >= MIN_ARTIST_PICK_SONGS }
                    ?.let { HomeArtistPick(artist, it) }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // "Vuelve a escucharlas": escuchadas alguna vez pero no en las últimas ~2 semanas.
    val homeRediscover: StateFlow<List<Song>> =
        repository.getRediscover(System.currentTimeMillis() - REDISCOVER_MILLIS, HOME_SECTION_LIMIT)
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Top de géneros para los chips de acciones rápidas del inicio (≥ GENRE_MIN_COUNT canciones,
    // máx. GENRE_CHIP_LIMIT). Reactivo: aparecen solos a medida que el backfill puebla la columna.
    //
    // El top SOLO se publica con la biblioteca QUIETA: durante un scan/descargas la tabla
    // `songs` cambia miles de veces y Room reemite en cada cambio; como el orden es por
    // cantidad y los géneros entran al cruzar el umbral, la fila de chips se reordenaba y
    // saltaba de línea sin parar (parpadeo). Gatearlo por el estado real del sync evita
    // inventar un intervalo de refresco: mientras la biblioteca se asienta no se toca nada, y
    // al terminar se publica el top definitivo de una sola vez.
    //
    // Se emiten solo los NOMBRES, que es lo único que pinta el chip: el `songCount` de
    // GenreSummary sube con cada canción analizada, así que comparando el modelo completo el
    // distinctUntilChanged no filtraba NADA (misma fila en pantalla, emisión nueva).
    val homeTopGenres: StateFlow<List<String>> =
        combine(
            repository.getTopGenres(GENRE_MIN_COUNT, GENRE_CHIP_LIMIT),
            syncManager.state
        ) { genres, sync ->
            val settling = sync is SyncStatus.Scanning || sync is SyncStatus.Downloading
            if (settling) null else genres.map { it.name }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Canciones de un género (para el chip: se reproducen en aleatorio). */
    suspend fun getSongsByGenre(genre: String): List<Song> = repository.getSongsByGenre(genre)

    init {
        // Initial Prefs Load
        _uiState.update {
            it.copy(
                sorting = it.sorting.copy(
                    sortOrderAll = musicPreferences.loadSortOrder(SongFilter.ALL),
                    sortOrderFavorites = musicPreferences.loadSortOrder(SongFilter.FAVORITES)
                ),
                playbackSettings = it.playbackSettings.copy(
                    replayGainMode = musicPreferences.loadReplayGainMode(),
                    replayGainPreamp = musicPreferences.loadReplayGainPreamp(),
                    nowPlayingSolidBackground = musicPreferences.loadNowPlayingSolidBackground(),
                    nowPlayingWavyProgress = musicPreferences.loadNowPlayingWavyProgress(),
                    themePaletteStyle = musicPreferences.loadThemePaletteStyle(),
                    useSystemEq = musicPreferences.loadUseSystemEq()
                )
            )
        }

        // Collect Repository Flows (Combined) — flowOn IO to avoid main thread work
        viewModelScope.launch {
            combine(
                repository.getUserPlaylists(),
                repository.getFavoritesIds()
            ) { playlists, favorites ->
                playlists to favorites
            }.flowOn(Dispatchers.IO).collect { (playlists, favorites) ->
                _uiState.update {
                    it.copy(data = it.data.copy(
                        playlists = playlists,
                        favorites = favorites
                    ))
                }
            }
        }

        // Collect Favorite Songs separately (heavy list, ensure IO thread)
        viewModelScope.launch {
            repository.getFavoritesSongs()
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    _uiState.update { it.copy(data = it.data.copy(favoriteSongs = list)) }
                }
        }

        // Collect Sync Manager State (estados EN CURSO). El "sync terminado" es un EVENTO,
        // no estado: llega por completedEvents (SharedFlow sin replay), así que un ViewModel
        // nacido después de un sync ya terminado no re-muestra el banner de Complete retenido.
        viewModelScope.launch {
            syncManager.state.collect { status ->
                // El sync publicó estado: el pull-to-refresh ya no espera nada.
                if (status !is SyncStatus.Idle) _isManualRefreshing.value = false
                val banner = when (status) {
                    is SyncStatus.Scanning -> LibraryBannerState.Scanning(status.found, status.message)
                    is SyncStatus.Downloading -> LibraryBannerState.Downloading(status.current, status.total, status.failed, status.message)
                    // El banner de Complete lo pone el colector de completedEvents.
                    is SyncStatus.Complete -> return@collect
                    is SyncStatus.Error -> LibraryBannerState.Error(status.message)
                    is SyncStatus.Idle -> LibraryBannerState.Hidden
                }
                bannerDismissJob?.cancel()
                _uiState.update { it.copy(data = it.data.copy(bannerState = banner)) }
            }
        }
        viewModelScope.launch {
            syncManager.completedEvents.collect { status ->
                // "Biblioteca al día" (sync sin cambio alguno) solo aporta con una fuente de
                // NUBE (confirma que se consultó el servidor). Con biblioteca 100% local el
                // re-escaneo es silencioso: sin novedades no hay nada que anunciar. Si SÍ
                // hubo cambios (canciones nuevas/borradas de la carpeta), se muestra igual.
                val nothingToReport = status.newSongs == 0 && status.downloaded == 0 &&
                    status.failed == 0 && status.deleted == 0
                if (nothingToReport && sourceRegistry.activeSources().none { it.type != SourceType.LOCAL }) {
                    return@collect
                }
                bannerDismissJob?.cancel()
                _uiState.update {
                    it.copy(data = it.data.copy(bannerState = LibraryBannerState.Complete(
                        status.newSongs, status.downloaded, status.failed, status.deleted
                    )))
                }
                bannerDismissJob = viewModelScope.launch {
                    delay(6000)
                    _uiState.update { it.copy(data = it.data.copy(bannerState = LibraryBannerState.Hidden)) }
                }
            }
        }

        // La restauración de sesión vive en MusicController.syncCurrentState (al conectar el
        // MediaController, resolviendo SOLO los IDs de la cola contra la BD). El viejo restore
        // desde aquí cargaba la biblioteca ENTERA en memoria para lo mismo.
    }

    // --- Actions ---

    /**
     * Pull-to-refresh. El indicador se apaga por SEÑALES reales, no por timer:
     * - el colector de `syncManager.state` lo baja en cuanto el sync publica cualquier estado;
     * - las precondiciones que impedirían que el scan arranque (sin red, batería baja — las
     *   mismas constraints del ScanWorker) se detectan ACÁ y se comunican con snackbar,
     *   en vez de dejar el spinner girando contra un worker que WorkManager no va a correr.
     */
    fun onRefresh() {
        viewModelScope.launch {
            // La red solo es requisito si hay alguna fuente de NUBE configurada: re-escanear
            // una carpeta local es 100% offline y no tiene por qué fallar en modo avión.
            val needsNetwork = sourceRegistry.activeSources().any { it.type != SourceType.LOCAL }
            if (needsNetwork && !networkManager.isAvailable()) {
                snackbarManager.show(context.getString(R.string.common_connection_error))
                return@launch
            }
            if (isBatteryLow()) {
                // Se agenda igual (WorkManager lo correrá al recuperar batería), pero sin
                // spinner: no hay sync inminente que esperar.
                downloadScheduler.scheduleScan(force = true, requiresNetwork = needsNetwork)
                snackbarManager.show(context.getString(R.string.sync_postponed_low_battery))
                return@launch
            }
            _isManualRefreshing.value = true
            downloadScheduler.scheduleScan(force = true, requiresNetwork = needsNetwork)
        }
    }

    /** Espejo de la constraint BATTERY_NOT_LOW del ScanWorker (~15% sin cargar). */
    private fun isBatteryLow(): Boolean = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) <= 15 && !bm.isCharging
    } catch (_: Exception) {
        false
    }

    fun redownloadSong(song: Song) {
        // LOCAL nunca se re-descarga: no hay copia en la nube y switchCurrentToStreaming
        // le quitaría la fuente a la canción sonando. La UI oculta la opción; esto es la red.
        // Por sourceType, NO por isLocalAudio: una canción de NUBE ya descargada (file://)
        // también es isLocalAudio, y re-descargarla (reparar un archivo dañado) SÍ aplica.
        if (song.sourceType == SourceType.LOCAL) return
        // Idempotente: si ya hay una re-descarga en curso para esta canción, no hacer nada.
        var added = false
        _redownloadingIds.update { current ->
            if (song.id in current) current
            else { added = true; current + song.id }
        }
        if (!added) return
        // Si es la canción que está sonando, pasarla a streaming ANTES de encolar: la
        // re-descarga borra su archivo local y el player se quedaría sin fuente. No-op si no
        // es la actual.
        musicController.switchCurrentToStreaming(song.id)
        viewModelScope.launch {
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        }
        downloadScheduler.scheduleDownload(songId = song.id, forceRedownload = true, isUserInitiated = true)
        viewModelScope.launch {
            try {
                // Espera al estado terminal con timeout: si el WorkInfo desaparece (pruned o
                // similar) o el flow nunca emite finished, el timeout nos libera y el finally
                // limpia el id de _redownloadingIds (no se queda pegado el botón).
                val finalInfo = kotlinx.coroutines.withTimeoutOrNull(5 * 60_000L) {
                    workManager
                        .getWorkInfosForUniqueWorkFlow(WorkerTags.repairTag(song.id))
                        .mapNotNull { it.firstOrNull() }
                        .first { it.state.isFinished }
                }
                val message = when (finalInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> context.getString(R.string.download_msg_complete, song.title)
                    WorkInfo.State.CANCELLED -> context.getString(R.string.download_msg_cancelled, song.title)
                    null -> context.getString(R.string.download_msg_unknown, song.title)
                    else -> {
                        val err = finalInfo.outputData.getString("error")
                        if (err != null) context.getString(R.string.download_msg_failed_reason, song.title, err)
                        else context.getString(R.string.download_msg_failed, song.title)
                    }
                }
                snackbarManager.show(message)
            } finally {
                _redownloadingIds.update { it - song.id }
            }
        }
    }

    /**
     * Feedback puntual desde la UI (p.ej. el estado de descarga al tocar una canción). Va por el
     * bus singleton, no por un `SnackbarHostState` de pantalla: el host único vive en el root y
     * así el mensaje sobrevive a overlays y cambios de destino.
     */
    fun showMessage(message: String) =
        snackbarManager.show(message, withDismissAction = true, replaceCurrent = true)

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchFilter = it.searchFilter.copy(searchQuery = query)) }
    }

    fun onShowSearchChanged(show: Boolean) {
        _uiState.update { it.copy(searchFilter = it.searchFilter.copy(showSearch = show)) }
    }

    fun onSortOrderChanged(order: SortOrder, filter: SongFilter) {
        when (filter) {
            SongFilter.ALL -> {
                _uiState.update { it.copy(sorting = it.sorting.copy(sortOrderAll = order)) }
                musicPreferences.saveSortOrder(order, SongFilter.ALL)
            }
            SongFilter.FAVORITES -> {
                _uiState.update { it.copy(sorting = it.sorting.copy(sortOrderFavorites = order)) }
                musicPreferences.saveSortOrder(order, SongFilter.FAVORITES)
            }
            else -> {}
        }
    }

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            repository.toggleFavorite(songId)
        }
    }

    fun startSelection(id: String) {
        _uiState.update { it.copy(selection = it.selection.copy(isSelectionMode = true, selectedSongs = setOf(id))) }
    }

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val current = state.selectedSongs
            val newSet = if (id in current) current - id else current + id
            state.copy(selection = state.selection.copy(selectedSongs = newSet, isSelectionMode = newSet.isNotEmpty()))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selection = it.selection.copy(isSelectionMode = false, selectedSongs = emptySet())) }
    }

    // --- Settings Updates ---

    // --- ReplayGain ---

    /**
     * Preferir el ecualizador del sistema. Al activarlo se APAGA el EQ propio (ecualizar dos
     * veces suma coloración sin control); MusicPlaybackService observa eqEnabledFlow, deshace
     * la pipeline float y recupera el offload solo.
     */
    fun setUseSystemEq(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(useSystemEq = enabled)) }
        musicPreferences.saveUseSystemEq(enabled)
        if (enabled) musicPreferences.saveEqEnabled(false)
    }

    // --- Toolbar del NowPlaying ---
    val toolbarConfig: StateFlow<List<com.qhana.siku.data.model.ToolbarActionState>> =
        musicPreferences.toolbarConfigFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), musicPreferences.loadToolbarConfig())

    fun setToolbarConfig(list: List<com.qhana.siku.data.model.ToolbarActionState>) =
        musicPreferences.saveToolbarConfig(list)

    fun setReplayGainMode(mode: com.qhana.siku.data.model.ReplayGainMode) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(replayGainMode = mode)) }
        musicPreferences.saveReplayGainMode(mode)
        // Recalcular el volumen del item actual de inmediato (no solo en la próxima transición).
        musicController.refreshReplayGain()
    }

    fun setReplayGainPreamp(db: Float) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(replayGainPreamp = db)) }
        musicPreferences.saveReplayGainPreamp(db)
        musicController.refreshReplayGain()
    }

    // --- Apariencia ---

    fun setNowPlayingSolidBackground(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingSolidBackground = enabled)) }
        // PlaybackViewModel lo observa vía nowPlayingSolidBackgroundFlow (DataStore).
        musicPreferences.saveNowPlayingSolidBackground(enabled)
    }

    /** Barra ondulada (Expressive) en el NowPlaying; el MiniPlayer no se toca (ver prefs). */
    fun setNowPlayingWavyProgress(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingWavyProgress = enabled)) }
        musicPreferences.saveNowPlayingWavyProgress(enabled)
    }

    /**
     * Estilo de paleta del tema. NO hace falta regenerar colores al cambiarlo: el estilo actúa
     * sobre el seed ya guardado, así que el tema se repinta solo (MainActivity observa el flow).
     */
    fun setThemePaletteStyle(styleName: String) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(themePaletteStyle = styleName)) }
        musicPreferences.saveThemePaletteStyle(styleName)
    }

    fun regenerateColors() {
        viewModelScope.launch {
            if (_uiState.value.isRegeneratingColors) return@launch
            _uiState.update { it.copy(colorTuning = it.colorTuning.copy(isRegeneratingColors = true)) }
            try {
                repository.resetAllColors()
                artworkRepository.clearCache()
                // Regenerar es explícito: las marcas de color manual también se van (si no,
                // quedarían huérfanas apuntando a colores que acaban de resetearse a NULL).
                musicPreferences.clearManualColorIds()
                snackbarManager.show(context.getString(R.string.colors_regenerated))
                _colorsRegeneratedEvent.emit(Unit)
            } catch (e: Exception) {
                snackbarManager.show(context.getString(R.string.colors_regenerate_error, e.message ?: ""))
            } finally {
                _uiState.update { it.copy(colorTuning = it.colorTuning.copy(isRegeneratingColors = false)) }
            }
        }
    }

    // --- Playlists ---
    // (Eliminado PlaylistUseCase: era pass-through puro al repositorio; ahora se llama directo).

    /**
     * Crea la lista y entrega su id por [onCreated] (en Main): la pestaña Listas lo usa para
     * navegar directo al detalle recién creado (donde vive "añadir canciones") y la hoja
     * "agregar a lista" para meter la canción pendiente en la lista nueva.
     */
    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repository.createPlaylist(name.trim())
        onCreated(id)
    }
    fun deletePlaylist(playlistId: Long) = viewModelScope.launch {
        repository.deletePlaylist(playlistId)
    }
    fun renamePlaylist(playlistId: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.renamePlaylist(playlistId, name.trim())
    }
    fun addSongToPlaylist(playlistId: Long, songId: String) = viewModelScope.launch {
        repository.addSongToPlaylist(playlistId, songId)
    }
    fun addSongsToPlaylist(playlistId: Long, songIds: List<String>) = viewModelScope.launch {
        notifySongsAdded(repository.addSongsToPlaylist(playlistId, songIds))
    }
    fun addSongsToFavorites(songIds: List<String>) = viewModelScope.launch {
        notifySongsAdded(repository.addSongsToFavorites(songIds))
    }
    private fun notifySongsAdded(added: Int) {
        if (added > 0) {
            snackbarManager.show(
                context.resources.getQuantityString(R.plurals.playlist_songs_added, added, added)
            )
        }
    }
    fun removeSongFromPlaylist(playlistId: Long, songId: String) = viewModelScope.launch {
        repository.removeSongFromPlaylist(playlistId, songId)
    }
    fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>) = viewModelScope.launch {
        repository.reorderPlaylistSongs(playlistId, songIds)
    }
    fun getPlaylistSongs(playlistId: Long) = repository.getSongsForPlaylist(playlistId)

    /** Conteo + carátulas por playlist para los thumbnails de la pestaña Listas. */
    val playlistsCoverMeta = repository.getPlaylistsCoverMeta()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // --- Selector "añadir canciones a la lista" ---
    // Búsqueda propia, independiente de la de la biblioteca: la hoja se abre desde el detalle
    // de una lista y no debe alterar lo que el usuario tenía filtrado en la pantalla de inicio.

    private val _songPickerQuery = MutableStateFlow("")
    val songPickerQuery: StateFlow<String> = _songPickerQuery.asStateFlow()

    val songPickerResults: StateFlow<List<Song>> = _songPickerQuery
        .debounce(200)
        .flatMapLatest { query ->
            flow { emit(repository.getSongsSnapshot(query, SortOrder.TITLE_ASC)) }
                // El catch va DENTRO del flatMapLatest: fuera mataría el flow para siempre
                // tras el primer error y la búsqueda dejaría de responder.
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSongPickerQuery(query: String) {
        _songPickerQuery.value = query
    }

    fun playPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val songs = repository.getSongsForPlaylist(playlistId).first()
            if (songs.isNotEmpty()) {
                // Graba el contexto (nombre de la lista viva + carátula de la primera canción)
                // para "Seguir escuchando".
                val name = _uiState.value.data.playlists.firstOrNull { it.id == playlistId }?.name
                if (name != null) {
                    recordContext(
                        PlaybackContext.Playlist(playlistId, name, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                }
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlay(songs, 0)
                }
            }
        }
    }

    private companion object {
        // Tope de ítems por carrusel de la home (canciones). Suficiente para llenar la fila
        // horizontal sin traer listas grandes a memoria.
        const val HOME_SECTION_LIMIT = 15
        // Mínimo de canciones para que un artista alimente "Porque escuchaste a X": con menos no
        // justifica un carrusel de "más de este artista" (1 sola = tarjeta suelta absurda).
        private const val MIN_ARTIST_PICK_SONGS = 3
        // Chips de género del inicio: hasta 5 géneros con al menos 5 canciones cada uno.
        private const val GENRE_MIN_COUNT = 5
        private const val GENRE_CHIP_LIMIT = 5
        // Ventanas de tiempo del inicio: "esta semana" para el stat del saludo, "no escuchada
        // en ~2 semanas" para la sección de redescubrimiento.
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val REDISCOVER_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
