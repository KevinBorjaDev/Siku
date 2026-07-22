package com.qhana.siku.ui.state

import androidx.compose.runtime.Stable
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.ui.viewmodel.LibraryBannerState

@Stable
data class SearchFilterState(
    val searchQuery: String = "",
    val showSearch: Boolean = false,
    /** Chips de origen de la pestaña Todas (combinables por unión; vacío = todas). */
    val sourceFilters: Set<SongSourceFilter> = emptySet()
)

@Stable
data class SortingState(
    val sortOrderAll: SortOrder = SortOrder.TITLE_ASC,
    val sortOrderFavorites: SortOrder = SortOrder.TITLE_ASC
)

@Stable
data class SelectionState(
    val isSelectionMode: Boolean = false,
    val selectedSongs: Set<String> = emptySet()
)

@Stable
data class LibraryDataState(
    val playlists: List<Playlist> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val favoriteSongs: List<Song> = emptyList(),
    val bannerState: LibraryBannerState = LibraryBannerState.Hidden
)

@Stable
/**
 * Ya no hay parámetros de extracción que exponer (QuantizerCelebi + Score no los tiene): solo
 * queda el progreso de "regenerar colores".
 */
data class ColorTuningState(
    val isRegeneratingColors: Boolean = false
)

@Stable
data class PlaybackSettingsState(
    val replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
    val replayGainPreamp: Float = 0f,
    val nowPlayingSolidBackground: Boolean = false,
    val nowPlayingWavyProgress: Boolean = false,
    val useSystemEq: Boolean = false,
    /** Nombre del `PaletteStyle` con el que se genera el tema desde el color del álbum. */
    val themePaletteStyle: String = MusicPreferences.DEFAULT_PALETTE_STYLE
)

@Stable
data class LibraryUiState(
    val searchFilter: SearchFilterState = SearchFilterState(),
    val sorting: SortingState = SortingState(),
    val selection: SelectionState = SelectionState(),
    val data: LibraryDataState = LibraryDataState(),
    val colorTuning: ColorTuningState = ColorTuningState(),
    val playbackSettings: PlaybackSettingsState = PlaybackSettingsState()
) {
    // Convenience accessors for backward compatibility during migration
    val searchQuery: String get() = searchFilter.searchQuery
    val showSearch: Boolean get() = searchFilter.showSearch
    val sourceFilters: Set<SongSourceFilter> get() = searchFilter.sourceFilters
    val sortOrderAll: SortOrder get() = sorting.sortOrderAll
    val sortOrderFavorites: SortOrder get() = sorting.sortOrderFavorites
    val isSelectionMode: Boolean get() = selection.isSelectionMode
    val selectedSongs: Set<String> get() = selection.selectedSongs
    val playlists: List<Playlist> get() = data.playlists
    val favorites: Set<String> get() = data.favorites
    val favoriteSongs: List<Song> get() = data.favoriteSongs
    val bannerState: LibraryBannerState get() = data.bannerState
    val isRegeneratingColors: Boolean get() = colorTuning.isRegeneratingColors
    val replayGainMode: ReplayGainMode get() = playbackSettings.replayGainMode
    val replayGainPreamp: Float get() = playbackSettings.replayGainPreamp
    val nowPlayingSolidBackground: Boolean get() = playbackSettings.nowPlayingSolidBackground
    val nowPlayingWavyProgress: Boolean get() = playbackSettings.nowPlayingWavyProgress
    val useSystemEq: Boolean get() = playbackSettings.useSystemEq
    val themePaletteStyle: String get() = playbackSettings.themePaletteStyle
}
