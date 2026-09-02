package com.qhana.siku.ui.navigation

import android.app.Activity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.MusicAppState
import com.qhana.siku.ui.PlayerArtOrigin
import com.qhana.siku.ui.screens.AlbumDetailScreen
import com.qhana.siku.ui.screens.ArtistDetailScreen
import com.qhana.siku.ui.screens.DownloadManagerScreen
import com.qhana.siku.ui.screens.GenreDetailScreen
import com.qhana.siku.ui.screens.LibraryScreen
import com.qhana.siku.ui.screens.OnboardingScreen
import com.qhana.siku.ui.screens.PlaylistDetailScreen
import com.qhana.siku.ui.screens.RecentlyAddedScreen
import com.qhana.siku.ui.screens.SettingsAppearanceScreen
import com.qhana.siku.ui.screens.SettingsBackupScreen
import com.qhana.siku.ui.screens.SettingsDownloadsScreen
import com.qhana.siku.ui.screens.SettingsExcludedFoldersScreen
import com.qhana.siku.ui.screens.SettingsEqPresetsScreen
import com.qhana.siku.ui.screens.SettingsGesturesScreen
import com.qhana.siku.ui.screens.SettingsPlaybackScreen
import com.qhana.siku.ui.screens.SettingsPlayerBarScreen
import com.qhana.siku.ui.screens.SettingsProgressBarScreen
import com.qhana.siku.ui.screens.SettingsScreen
import com.qhana.siku.ui.screens.SettingsSourcesScreen
import com.qhana.siku.ui.screens.SettingsTabsScreen
import com.qhana.siku.ui.screens.StatsScreen
import com.qhana.siku.ui.theme.appNavBackEnter
import com.qhana.siku.ui.theme.appNavBackExit
import com.qhana.siku.ui.theme.appNavFadeEnter
import com.qhana.siku.ui.theme.appNavFadeExit
import com.qhana.siku.ui.theme.appNavForwardEnter
import com.qhana.siku.ui.theme.appNavForwardExit
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel

/**
 * Grafo de navegación de la app. Las pantallas reciben TODO por parámetro: los ViewModels
 * compartidos llegan desde la raíz (instancias de la Activity), nunca vía `hiltViewModel()`
 * dentro de una ruta — eso resolvería al scope del NavBackStackEntry y crearía una segunda
 * instancia ciega al estado global (el gotcha documentado del logout invisible).
 *
 * El estado de sesión (loggedIn/authLoading/authError) y las acciones de auth llegan como
 * valores y lambdas por la misma razón.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(
    appState: MusicAppState,
    startDestination: String,
    loggedIn: Boolean,
    // Avatar de cuenta del header de la biblioteca (foto de perfil cacheada + inicial de fallback).
    accountPhotoPath: String? = null,
    accountInitial: String? = null,
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    /** Encola un scan de fuentes (KEEP: no pisa uno ya encolado por elegir carpeta local). */
    onRequestSync: () -> Unit,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    sourcesViewModel: SourcesViewModel,
    syncViewModel: SyncViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope
) {
    val navController = appState.navController

    // Estado compartido por varias rutas (cada capa lo colecta de su ViewModel; StateFlow
    // hace que ambas vean lo mismo sin acoplarse entre sí).
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    // Vistas de UN campo, no el `uiState` entero. Esta es la raíz del grafo: colectando el objeto
    // completo, cualquier emisión —una tecla de la búsqueda, un chip de origen, el slider de
    // ReplayGain de Ajustes, un tick del banner— recomponía el NavHost aunque solo hicieran falta
    // estos tres campos, todos de `data`. Ver el bloque de vistas en `LibraryViewModel`.
    val favorites by libraryViewModel.favorites.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val favoriteSongs by libraryViewModel.favoriteSongs.collectAsStateWithLifecycle()

    // Transiciones del grafo, declaradas UNA vez aquí y no ruta por ruta. Antes cada `composable`
    // repetía el mismo par `slideInHorizontally(tween(300))` / `slideOutHorizontally(tween(300))`
    // —14 veces, con la duración a mano— y solo declaraba `enterTransition` + `popExitTransition`.
    // Faltaban las OTRAS dos, así que la pantalla que quedaba detrás no se movía: no había shared
    // axis, solo una capa entrando sobre un fondo congelado. Los cuatro specs salen del MotionScheme
    // (ver ui/theme/Motion.kt).
    //
    // Las cuatro SE QUEDAN aunque el predictive back esté desactivado desde el 18 ago 2026 (ver el
    // manifest): con el gesto, `popEnter`/`popExit` eran además lo que se recorría con el dedo, pero
    // el motivo por el que existen es el shared axis, y ese vale igual cuando el "atrás" es un
    // evento — la pantalla de atrás tiene que moverse se llegue como se llegue.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Shared axis horizontal por defecto para TODA navegación entre pantallas. El reproductor ya
        // no es una ruta (es una capa que hace container transform con la píldora), así que ninguna
        // ruta necesita ya el caso especial "no mover la de debajo" que hacía falta cuando el player
        // subía como hoja vertical.
        enterTransition = appNavForwardEnter,
        exitTransition = appNavForwardExit,
        popEnterTransition = appNavBackEnter,
        popExitTransition = appNavBackExit
    ) {
        composable(
            route = Screen.Onboarding.route,
            // Única ruta que NO usa el eje horizontal: no tiene "atrás" ni jerarquía con la
            // biblioteca (se sustituyen mutuamente según haya fuentes), así que un desplazamiento
            // le inventaría una dirección que no existe.
            enterTransition = appNavFadeEnter,
            exitTransition = appNavFadeExit,
            popEnterTransition = appNavFadeEnter,
            popExitTransition = appNavFadeExit
        ) {
            OnboardingScreen(
                isLoggedIn = loggedIn,
                authLoading = authLoading,
                authError = authError,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                onFinish = {
                    onRequestSync()
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = sourcesViewModel
            )
        }

        // Sin overrides: la biblioteca es la raíz del eje horizontal, así que CEDE con paralaje al
        // abrir un detalle y vuelve con él. Antes se desvanecía, que es lo que rompía el eje.
        composable(route = Screen.Library.route) {
            LibraryScreen(
                isLoggedIn = loggedIn,
                accountPhotoPath = accountPhotoPath,
                accountInitial = accountInitial,
                onLogoutClick = onDisconnectOneDrive,
                onDownloadManagerClick = { navController.navigate(Screen.DownloadManager.route) },
                onPlaylistClick = { id, name -> navController.navigate(Screen.PlaylistDetail.createRoute(id, name)) },
                onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
                onAlbumClick = { name -> navController.navigate(Screen.AlbumDetail.createRoute(name)) },
                onGenreClick = { name -> navController.navigate(Screen.GenreDetail.createRoute(name)) },
                onStatsClick = { navController.navigate(Screen.Stats.route) },
                onSeeAllRecentClick = { navController.navigate(Screen.RecentlyAdded.route) },
                onNavigateToNowPlaying = { origin -> appState.openPlayer(origin) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                playbackViewModel = playbackViewModel,
                // El MISMO LibraryViewModel que reciben los detalles. Antes esta ruta se quedaba
                // con el default `hiltViewModel()` de la pantalla, que resuelve al scope del
                // NavBackStackEntry: había dos instancias vivas de la nada.
                libraryViewModel = libraryViewModel,
                // Shared elements foto/carátula → headers de los detalles.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.PlaylistDetail.route
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments
                ?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_ID)
                ?.toLongOrNull() ?: return@composable
            val playlistName = Screen.PlaylistDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_NAME)
            )
            val playlistSongs by libraryViewModel.getPlaylistSongs(playlistId).collectAsStateWithLifecycle(emptyList())

            PlaylistDetailScreen(
                playlistName = playlistName,
                songs = playlistSongs,
                currentSong = currentSong,
                isFavoritesList = false,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { targetId, songId -> libraryViewModel.addSongToPlaylist(targetId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                onReorderSongs = { songIds -> libraryViewModel.reorderPlaylistSongs(playlistId, songIds) },
                onRemoveSong = { songId -> libraryViewModel.removeSongFromPlaylist(playlistId, songId) },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        composable(
            route = Screen.ArtistDetail.route
        ) { backStackEntry ->
            val artistName = Screen.ArtistDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.ArtistDetail.ARG_ARTIST_NAME)
            )
            ArtistDetailScreen(
                artistName = artistName,
                currentSong = currentSong,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album -> appState.navigateToAlbum(album) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.AlbumDetail.route
        ) { backStackEntry ->
            val albumName = Screen.AlbumDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.AlbumDetail.ARG_ALBUM_NAME)
            )
            AlbumDetailScreen(
                albumName = albumName,
                currentSong = currentSong,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onArtistClick = { artist -> appState.navigateToArtist(artist) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.GenreDetail.route
        ) { backStackEntry ->
            val genreName = Screen.GenreDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.GenreDetail.ARG_GENRE_NAME)
            )
            GenreDetailScreen(
                genreName = genreName,
                currentSong = currentSong,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album -> appState.navigateToAlbum(album) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Genre(genreName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Genre(genreName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.Favorites.route
        ) {
            PlaylistDetailScreen(
                playlistName = "Favoritos",
                songs = favoriteSongs,
                currentSong = currentSong,
                isFavoritesList = true,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { targetId, songId -> libraryViewModel.addSongToPlaylist(targetId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        // El reproductor a pantalla completa YA NO es una ruta: es una capa que hace container
        // transform con la píldora (ver [com.qhana.siku.ui.PlayerOverlay] / `NowPlayingLayer`). Se abre
        // con `appState.openPlayer(...)` —que setea un booleano— desde los mismos call sites de siempre.

        composable(
            route = Screen.Settings.route
        ) {
            // Hub de categorías: cada una navega a su propia sub-pantalla.
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                onNavigate = { route -> appState.navigate(route) },
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsSources.route
        ) {
            SettingsSourcesScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                authLoading = authLoading,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                onNavigate = { route -> appState.navigate(route) },
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsExcludedFolders.route
        ) {
            SettingsExcludedFoldersScreen(
                onBackClick = { navController.popBackStack() },
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsBackup.route
        ) {
            SettingsBackupScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                libraryViewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsPlayback.route
        ) {
            SettingsPlaybackScreen(
                onBackClick = { navController.popBackStack() },
                onNavigate = { route -> appState.navigate(route) },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsEqPresets.route
        ) {
            SettingsEqPresetsScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsDownloads.route
        ) {
            SettingsDownloadsScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = syncViewModel,
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsAppearance.route
        ) {
            SettingsAppearanceScreen(
                onBackClick = { navController.popBackStack() },
                onNavigate = { route -> appState.navigate(route) },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsGestures.route
        ) {
            SettingsGesturesScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsTabs.route
        ) {
            SettingsTabsScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsPlayerBar.route
        ) {
            SettingsPlayerBarScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = libraryViewModel
            )
        }

        composable(
            route = Screen.SettingsProgressBar.route
        ) {
            SettingsProgressBarScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = libraryViewModel
            )
        }

        composable(Screen.DownloadManager.route) {
            DownloadManagerScreen(onBackClick = { navController.popBackStack() })
        }

        composable(Screen.RecentlyAdded.route) {
            RecentlyAddedScreen(
                currentSong = currentSong,
                favorites = favorites,
                playlists = playlists,
                onBackClick = { navController.popBackStack() },
                onPlaySongs = { songs, index ->
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddToQueue = { playbackViewModel.addToQueue(it) },
                // Misma función, otra sobrecarga: la de lista cuenta cuántas entraron de verdad.
                onAddAllToQueue = { playbackViewModel.addToQueue(it) },
                onAddSongToPlaylist = { playlistId, songId ->
                    libraryViewModel.addSongToPlaylist(playlistId, songId)
                },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                // El COMPARTIDO, no uno por ruta (convención 9).
                libraryViewModel = libraryViewModel
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(
                onBackClick = { navController.popBackStack() },
                currentSong = currentSong,
                onPlaySongs = { songs, index ->
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onArtistClick = { name -> appState.navigateToArtist(name) },
                onAlbumClick = { name -> appState.navigateToAlbum(name) },
                onGenreClick = { name -> appState.navigateToGenre(name) }
            )
        }
    }
}
