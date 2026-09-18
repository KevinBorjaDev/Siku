package com.qhana.siku.ui.state

import androidx.compose.runtime.Stable
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SortSpec
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
    val sortOrderAll: SortSpec<SortOrder> = SortOrder.DEFAULT,
    val sortOrderFavorites: SortSpec<SortOrder> = SortOrder.DEFAULT
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
    /** Fondo del reproductor grande: degradado del álbum, color sólido o carátula desenfocada. */
    val nowPlayingBackground: NowPlayingBackground = NowPlayingBackground.GRADIENT,
    val nowPlayingWavyProgress: Boolean = false,
    /** Grosor de la barra de progreso del NowPlaying, en dp (vale para los dos modos). */
    val nowPlayingProgressThickness: Int = MusicPreferences.DEFAULT_PROGRESS_THICKNESS_DP,
    /** Palo del handle permanente en la barra del NowPlaying; false = solo al arrastrar. */
    val nowPlayingProgressHandle: Boolean = MusicPreferences.DEFAULT_PROGRESS_HANDLE,
    /** MiniPlayer como rectángulo redondeado en vez de píldora (false = píldora, el diseño actual). */
    val miniPlayerRoundedRect: Boolean = false,
    /** Botón de play del MiniPlayer redondo en vez de squircle (false = squircle, el default). */
    val miniPlayerRoundPlayButton: Boolean = false,
    /** Pestañas de la biblioteca ABAJO (navigation bar) en vez de en la fila bajo la búsqueda. */
    val libraryBottomTabs: Boolean = false,
    /** Estilo moderno: tres destinos (Inicio · Biblioteca · Listas) en vez de las seis pestañas. */
    val libraryModernNav: Boolean = MusicPreferences.DEFAULT_LIBRARY_MODERN_NAV,
    /** Chip de formato del NowPlaying con la ficha técnica (bitrate/bits + frecuencia). */
    val nowPlayingDetailedFormat: Boolean = false,
    /** Deslizar la carátula/el player y el doble toque para saltar. Encendido por defecto. */
    val playerGestures: Boolean = true,
    val useSystemEq: Boolean = false,
    /** Nombre del `PaletteStyle` con el que se genera el tema desde el color del álbum. */
    val themePaletteStyle: String = MusicPreferences.DEFAULT_PALETTE_STYLE,
    /** Dónde guardar las letras; ASK vuelve a mostrar el diálogo en cada guardado. */
    val lyricsSaveMode: LyricsSaveMode = LyricsSaveMode.ASK,
    /** Carpeta para los `.lrc` de la música del dispositivo (SAF). null = sin elegir. */
    val lyricsFolderUri: String? = null
)

@Stable
data class LibraryUiState(
    val searchFilter: SearchFilterState = SearchFilterState(),
    val sorting: SortingState = SortingState(),
    val data: LibraryDataState = LibraryDataState(),
    val colorTuning: ColorTuningState = ColorTuningState(),
    val playbackSettings: PlaybackSettingsState = PlaybackSettingsState()
) {
    // Convenience accessors for backward compatibility during migration
    val searchQuery: String get() = searchFilter.searchQuery
    val showSearch: Boolean get() = searchFilter.showSearch
    val sourceFilters: Set<SongSourceFilter> get() = searchFilter.sourceFilters
    val sortOrderAll: SortSpec<SortOrder> get() = sorting.sortOrderAll
    val sortOrderFavorites: SortSpec<SortOrder> get() = sorting.sortOrderFavorites
    val playlists: List<Playlist> get() = data.playlists
    val favorites: Set<String> get() = data.favorites
    val favoriteSongs: List<Song> get() = data.favoriteSongs
    val bannerState: LibraryBannerState get() = data.bannerState
    val isRegeneratingColors: Boolean get() = colorTuning.isRegeneratingColors
    val replayGainMode: ReplayGainMode get() = playbackSettings.replayGainMode
    val replayGainPreamp: Float get() = playbackSettings.replayGainPreamp
    val nowPlayingBackground: NowPlayingBackground get() = playbackSettings.nowPlayingBackground
    val nowPlayingWavyProgress: Boolean get() = playbackSettings.nowPlayingWavyProgress
    val nowPlayingProgressThickness: Int get() = playbackSettings.nowPlayingProgressThickness
    val nowPlayingProgressHandle: Boolean get() = playbackSettings.nowPlayingProgressHandle
    val miniPlayerRoundedRect: Boolean get() = playbackSettings.miniPlayerRoundedRect
    val miniPlayerRoundPlayButton: Boolean get() = playbackSettings.miniPlayerRoundPlayButton
    val libraryBottomTabs: Boolean get() = playbackSettings.libraryBottomTabs
    val libraryModernNav: Boolean get() = playbackSettings.libraryModernNav
    val nowPlayingDetailedFormat: Boolean get() = playbackSettings.nowPlayingDetailedFormat
    val playerGestures: Boolean get() = playbackSettings.playerGestures
    val useSystemEq: Boolean get() = playbackSettings.useSystemEq
    val themePaletteStyle: String get() = playbackSettings.themePaletteStyle
}
