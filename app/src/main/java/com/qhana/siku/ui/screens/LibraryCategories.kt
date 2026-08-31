package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.ui.PlayerArtOrigin
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Las cuatro vistas del catálogo —Canciones, Artistas, Álbumes y Géneros— con TODO su cableado:
 * los estados que colectan de `BrowseViewModel`, los quick-play que registran contexto de escucha
 * y el encolado que a propósito NO lo registra.
 *
 * **Existen porque desde el estilo moderno hay DOS sitios que las montan**: la página del pager
 * (modo clásico) y la ruta `library_category/{category}` a pantalla completa (modo moderno). Sin
 * esto, ese cableado —unas doscientas líneas de lambdas con criterio dentro— viviría escrito dos
 * veces, con dos sitios donde arreglar un bug y uno donde olvidarse.
 *
 * Lo único que decide el caller es el **contentPadding** (el colchón cambia: en la biblioteca hay
 * toolbar del que despegarse y en la categoría no) y, en Canciones, si se pintan o no las acciones
 * de reproducir la lista entera.
 */

/**
 * La pestaña/pantalla **Canciones**: la lista completa con sus chips de conteo, orden y origen.
 *
 * **Las acciones de reproducir la lista entera se pasan SIEMPRE, y quien decide si se ven es
 * `SongsScreen`**, que lee `LibraryViewModel.homeTabHidden` por su cuenta: son el repuesto de lo
 * que solo vive en la pestaña Inicio, y esa regla es una decisión sobre cuándo EXISTE un control,
 * así que vive en el ViewModel y en un solo sitio (convención 9b). Pasar aquí un flag de
 * visibilidad sería una segunda verdad sobre lo mismo, capaz de discrepar. En el estilo moderno
 * `homeTabHidden` es siempre `false` —Inicio es uno de los tres destinos fijos—, así que en una
 * categoría la botonera no aparece sin que haga falta decírselo.
 */
@Composable
internal fun SongsCategory(
    contentPadding: PaddingValues,
    onNavigateToNowPlaying: (PlayerArtOrigin) -> Unit,
    onAddToPlaylistRequest: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    songCount: Int,
    sortOrder: SortOrder,
    sourceFilters: Set<SongSourceFilter>
) {
    SongsScreen(
        currentFilter = SongFilter.ALL,
        contentPadding = contentPadding,
        onNavigateToNowPlaying = { onNavigateToNowPlaying(PlayerArtOrigin.ROW) },
        onAddToPlaylistRequest = onAddToPlaylistRequest,
        viewModel = libraryViewModel,
        playbackViewModel = playbackViewModel,
        songCount = songCount,
        sortOrder = sortOrder,
        onSortOrderChange = { libraryViewModel.onSortOrderChanged(it, SongFilter.ALL) },
        onToggleSourceFilter = libraryViewModel::toggleSourceFilter,
        // Reproducen LA LISTA QUE SE VE: su orden y sus chips de origen. No es lo que significan
        // las del inicio, donde no hay ninguna lista delante. `NONE` y no `ROW`: no se pulsó
        // ninguna fila, así que no hay superficie de la que crecer.
        onPlayAll = {
            playbackViewModel.playLibraryList(
                sortOrder = sortOrder,
                sourceFilters = sourceFilters,
                shuffled = false
            )
            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
        },
        onShuffleAll = {
            playbackViewModel.playLibraryList(
                sortOrder = sortOrder,
                sourceFilters = sourceFilters,
                shuffled = true
            )
            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
        }
    )
}

/** La pestaña/pantalla **Artistas**. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ArtistsCategory(
    artists: List<ArtistSummary>,
    contentPadding: PaddingValues,
    onArtistClick: (String) -> Unit,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    browseViewModel: BrowseViewModel,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val scope = rememberCoroutineScope()
    val artistPhotosPaused by browseViewModel.artistPhotosPausedOnMobile.collectAsStateWithLifecycle()
    val artistSortOrder by browseViewModel.artistSortOrder.collectAsStateWithLifecycle()
    val artistSourceFilters by browseViewModel.artistSourceFilters.collectAsStateWithLifecycle()
    ArtistsScreen(
        artists = artists,
        onArtistClick = onArtistClick,
        sortOrder = artistSortOrder,
        onSortOrderChange = browseViewModel::setArtistSortOrder,
        sourceFilters = artistSourceFilters,
        onToggleSourceFilter = browseViewModel::toggleArtistSourceFilter,
        showLocalChip = showLocalChip,
        showCloudChips = showCloudChips,
        meteredBannerVisible = artistPhotosPaused,
        onDownloadOnMobile = browseViewModel::downloadArtistPhotosOnMobile,
        onDismissMeteredBanner = browseViewModel::dismissArtistPhotosBanner,
        // Quick-play: reproduce todo el artista sin navegar (el MiniPlayer aparece como
        // feedback), como en Álbumes.
        onPlayArtist = { name ->
            scope.launch {
                val artistSongs = browseViewModel.getArtistSongs(name).first()
                if (artistSongs.isNotEmpty()) {
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(name, artistSongs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(artistSongs, 0)
                }
            }
        },
        // Encolar NO es reproducir: no toca el contexto de "seguir escuchando" (que describe de
        // dónde salió lo que SUENA) ni abre el player. El feedback lo da el snackbar de addToQueue.
        onAddArtistToQueue = { name ->
            scope.launch {
                playbackViewModel.addToQueue(browseViewModel.getArtistSongs(name).first())
            }
        },
        contentPadding = contentPadding,
        modifier = modifier,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
}

/** La pestaña/pantalla **Álbumes**. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AlbumsCategory(
    albums: List<AlbumSummary>,
    contentPadding: PaddingValues,
    onAlbumClick: (String) -> Unit,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    browseViewModel: BrowseViewModel,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val scope = rememberCoroutineScope()
    val albumSortOrder by browseViewModel.albumSortOrder.collectAsStateWithLifecycle()
    val albumSourceFilters by browseViewModel.albumSourceFilters.collectAsStateWithLifecycle()
    AlbumsScreen(
        albums = albums,
        onAlbumClick = onAlbumClick,
        sortOrder = albumSortOrder,
        onSortOrderChange = browseViewModel::setAlbumSortOrder,
        sourceFilters = albumSourceFilters,
        onToggleSourceFilter = browseViewModel::toggleAlbumSourceFilter,
        showLocalChip = showLocalChip,
        showCloudChips = showCloudChips,
        // Quick-play: reproduce el álbum sin navegar (el MiniPlayer aparece como feedback).
        onPlayAlbum = { name ->
            scope.launch {
                val albumSongs = browseViewModel.getAlbumSongs(name).first()
                if (albumSongs.isNotEmpty()) {
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(name, albumSongs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(albumSongs, 0)
                }
            }
        },
        onAddAlbumToQueue = { name ->
            scope.launch {
                playbackViewModel.addToQueue(browseViewModel.getAlbumSongs(name).first())
            }
        },
        contentPadding = contentPadding,
        modifier = modifier,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
}

/** La pestaña/pantalla **Géneros**. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun GenresCategory(
    contentPadding: PaddingValues,
    onGenreClick: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    browseViewModel: BrowseViewModel,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val scope = rememberCoroutineScope()
    val genres by browseViewModel.genres.collectAsStateWithLifecycle()
    val partialMatch by browseViewModel.genrePartialMatch.collectAsStateWithLifecycle()
    GenresScreen(
        genres = genres,
        onGenreClick = onGenreClick,
        partialMatch = partialMatch,
        onPartialMatchChange = browseViewModel::setGenrePartialMatch,
        // Quick-play: reproduce el género sin navegar (el MiniPlayer aparece como feedback),
        // igual que Artistas/Álbumes.
        onPlayGenre = { name ->
            scope.launch {
                val genreSongs = browseViewModel.getGenreSongs(name).first()
                if (genreSongs.isNotEmpty()) {
                    libraryViewModel.recordContext(
                        PlaybackContext.Genre(name, genreSongs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(genreSongs, 0)
                }
            }
        },
        onAddGenreToQueue = { name ->
            scope.launch {
                playbackViewModel.addToQueue(browseViewModel.getGenreSongs(name).first())
            }
        },
        contentPadding = contentPadding,
        modifier = modifier,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
}
