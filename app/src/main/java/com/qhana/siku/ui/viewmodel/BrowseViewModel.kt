package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtistImageRepository
import com.qhana.siku.data.repository.BrowseRepository
import com.qhana.siku.data.repository.DeezerArtistCandidate
import com.qhana.siku.data.util.SnackbarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado del picker manual de artista (molde del flujo de búsqueda de letras). */
sealed interface ArtistPickerState {
    data object Hidden : ArtistPickerState
    data object Loading : ArtistPickerState
    data class Loaded(val candidates: List<DeezerArtistCandidate>) : ArtistPickerState
    data class Error(val message: String) : ArtistPickerState
}

/**
 * ViewModel de navegación por Artistas/Álbumes (pestañas + pantallas de detalle).
 * Separado de LibraryViewModel a propósito: ese ya concentra búsqueda/orden/selección/
 * playlists/ajustes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val browseRepository: BrowseRepository,
    private val artistImageRepository: ArtistImageRepository,
    private val musicPreferences: MusicPreferences,
    private val snackbarManager: SnackbarManager
) : ViewModel() {

    init {
        // Backfill de fotos pendientes al arrancar la sesión de navegación: cubre bibliotecas
        // ya sincronizadas donde no correrá ningún scan que dispare el hook de SyncManager.
        // Idempotente y con rate-limit en el repo; dispararlo dos veces es gratis.
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.backfillMissingImages()
        }
    }

    // --- Orden de las pestañas de browse (persistido, como el de canciones) ---

    private val _artistSortOrder = MutableStateFlow(musicPreferences.loadArtistSortOrder())
    val artistSortOrder: StateFlow<ArtistSortOrder> = _artistSortOrder.asStateFlow()

    fun setArtistSortOrder(order: ArtistSortOrder) {
        musicPreferences.saveArtistSortOrder(order)
        _artistSortOrder.value = order
    }

    private val _albumSortOrder = MutableStateFlow(musicPreferences.loadAlbumSortOrder())
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder.asStateFlow()

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        musicPreferences.saveAlbumSortOrder(order)
        _albumSortOrder.value = order
    }

    // --- Filtros de origen de las pestañas Artistas/Álbumes (unión, igual que en Todas) ---
    // Semántica "≥1 canción que cumple": el WHERE va antes del GROUP BY (ver
    // SongDao.buildArtistsQuery/buildAlbumsQuery). NO se persisten: son un filtro de sesión,
    // como los chips de la pestaña Todas.

    private val _artistSourceFilters = MutableStateFlow<Set<SongSourceFilter>>(emptySet())
    val artistSourceFilters: StateFlow<Set<SongSourceFilter>> = _artistSourceFilters.asStateFlow()

    fun toggleArtistSourceFilter(filter: SongSourceFilter) {
        _artistSourceFilters.value = _artistSourceFilters.value.toMutableSet().apply {
            if (!add(filter)) remove(filter)
        }
    }

    private val _albumSourceFilters = MutableStateFlow<Set<SongSourceFilter>>(emptySet())
    val albumSourceFilters: StateFlow<Set<SongSourceFilter>> = _albumSourceFilters.asStateFlow()

    fun toggleAlbumSourceFilter(filter: SongSourceFilter) {
        _albumSourceFilters.value = _albumSourceFilters.value.toMutableSet().apply {
            if (!add(filter)) remove(filter)
        }
    }

    // distinctUntilChanged: Room re-emite en CADA invalidación de las tablas del SQL aunque
    // el resultado sea idéntico. En Artistas eso pasa constantemente durante el fetch Deezer:
    // cada not-found persiste su intento en `artists` → requery → lista IGUAL (thumbUrl sigue
    // null) → recomposición inútil en pleno scroll. Los flows de álbumes lo heredan gratis
    // (playCount escribe en `songs` sin cambiar los agregados).
    val artists: StateFlow<List<ArtistSummary>> =
        combine(_artistSortOrder, _artistSourceFilters) { sort, filters -> sort to filters }
            .flatMapLatest { (sort, filters) -> browseRepository.getArtists(sort, filters) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumSummary>> =
        combine(_albumSortOrder, _albumSourceFilters) { sort, filters -> sort to filters }
            .flatMapLatest { (sort, filters) -> browseRepository.getAlbums(sort, filters) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Álbumes del momento (por total de reproducciones) para la sección de la home. */
    val topAlbums: StateFlow<List<AlbumSummary>> = browseRepository.getTopAlbums(TOP_ALBUMS_LIMIT)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Detalle (coleccionar con collectAsStateWithLifecycle en la pantalla) ---

    fun getArtistSongs(artist: String): Flow<List<Song>> = browseRepository.getSongsByArtist(artist)

    fun getArtistAlbums(artist: String): Flow<List<AlbumSummary>> =
        browseRepository.getAlbumsByArtist(artist)

    fun getArtistInfo(artist: String): Flow<ArtistEntity?> = browseRepository.getArtistInfo(artist)

    fun getAlbumSongs(album: String): Flow<List<Song>> = browseRepository.getSongsByAlbum(album)

    // --- Banner "fotos en pausa por red móvil" (pestaña Artistas) ---

    /** true = el backfill se abstuvo por estar en datos móviles; la pestaña muestra el banner. */
    val artistPhotosPausedOnMobile: StateFlow<Boolean> = artistImageRepository.meteredBackfillPending

    /** "Descargar" del banner: permite la red medida esta sesión y relanza el backfill. */
    fun downloadArtistPhotosOnMobile() {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.resumeBackfillOnMetered()
        }
    }

    /** "Ahora no" del banner: dismiss de sesión + pista de dónde vive el control permanente. */
    fun dismissArtistPhotosBanner() {
        artistImageRepository.dismissMeteredBackfillBanner()
        snackbarManager.show(context.getString(R.string.artist_photos_banner_hint))
    }

    // --- Ajustes de fotos de artistas (Ajustes → Descargas) ---

    private val _artistPhotosOnMetered = MutableStateFlow(musicPreferences.loadArtistPhotosOnMetered())
    val artistPhotosOnMetered: StateFlow<Boolean> = _artistPhotosOnMetered.asStateFlow()

    private val _artistPhotosBannerEnabled = MutableStateFlow(musicPreferences.loadArtistPhotosBannerEnabled())
    val artistPhotosBannerEnabled: StateFlow<Boolean> = _artistPhotosBannerEnabled.asStateFlow()

    private val _artistPhotoDetailOnMetered = MutableStateFlow(musicPreferences.loadArtistPhotoDetailOnMetered())
    val artistPhotoDetailOnMetered: StateFlow<Boolean> = _artistPhotoDetailOnMetered.asStateFlow()

    /** "Descargar con datos móviles": al activarlo, lo pendiente se resuelve al momento. */
    fun setArtistPhotosOnMetered(enabled: Boolean) {
        musicPreferences.saveArtistPhotosOnMetered(enabled)
        _artistPhotosOnMetered.value = enabled
        if (enabled) {
            artistImageRepository.clearMeteredBannerPending()
            viewModelScope.launch(Dispatchers.IO) { artistImageRepository.backfillMissingImages() }
        }
    }

    /** "Preguntar en red móvil": apagar oculta un banner ya visible sin dismiss de sesión. */
    fun setArtistPhotosBannerEnabled(enabled: Boolean) {
        musicPreferences.saveArtistPhotosBannerEnabled(enabled)
        _artistPhotosBannerEnabled.value = enabled
        if (!enabled) artistImageRepository.clearMeteredBannerPending()
    }

    fun setArtistPhotoDetailOnMetered(enabled: Boolean) {
        musicPreferences.saveArtistPhotoDetailOnMetered(enabled)
        _artistPhotoDetailOnMetered.value = enabled
    }

    /**
     * Fire-and-forget: fetch de la foto Deezer al entrar al DETALLE de un artista. Con el
     * backfill en background esto es normalmente redundante; queda como reintento dirigido
     * para cuando aquel falló por red (el repo limpia el intento de sesión en ese caso).
     * La pestaña de artistas ya NO llama esto por fila.
     */
    fun onArtistShown(artistName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.ensureArtistImageOnDemand(artistName)
        }
    }

    // --- Picker manual de artista (Deezer) ---

    private val _pickerState = MutableStateFlow<ArtistPickerState>(ArtistPickerState.Hidden)
    val pickerState: StateFlow<ArtistPickerState> = _pickerState.asStateFlow()

    private var searchJob: Job? = null

    fun searchArtistCandidates(artistName: String) {
        searchJob?.cancel()
        _pickerState.value = ArtistPickerState.Loading
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.searchCandidates(artistName)
                .onSuccess { _pickerState.value = ArtistPickerState.Loaded(it) }
                .onFailure { _pickerState.value = ArtistPickerState.Error(context.getString(R.string.error_no_deezer)) }
        }
    }

    fun selectArtistCandidate(artistName: String, candidate: DeezerArtistCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.setManualArtist(artistName, candidate)
            _pickerState.value = ArtistPickerState.Hidden
        }
    }

    fun dismissArtistPicker() {
        searchJob?.cancel()
        _pickerState.value = ArtistPickerState.Hidden
    }

    private companion object {
        const val TOP_ALBUMS_LIMIT = 12
    }
}
