package com.qhana.siku.ui.navigation

import android.app.Activity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.ui.MusicAppState
import com.qhana.siku.ui.screens.AlbumDetailScreen
import com.qhana.siku.ui.screens.ArtistDetailScreen
import com.qhana.siku.ui.screens.DownloadManagerScreen
import com.qhana.siku.ui.screens.LibraryScreen
import com.qhana.siku.ui.screens.OnboardingScreen
import com.qhana.siku.ui.screens.PlaylistDetailScreen
import com.qhana.siku.ui.screens.SettingsAppearanceScreen
import com.qhana.siku.ui.screens.SettingsBackupScreen
import com.qhana.siku.ui.screens.SettingsDownloadsScreen
import com.qhana.siku.ui.screens.SettingsPlaybackScreen
import com.qhana.siku.ui.screens.SettingsScreen
import com.qhana.siku.ui.screens.SettingsSourcesScreen
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel

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
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    /** Encola un scan de fuentes (KEEP: no pisa uno ya encolado por elegir carpeta local). */
    onRequestSync: () -> Unit,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    sourcesViewModel: SourcesViewModel,
    sharedTransitionScope: SharedTransitionScope
) {
    val navController = appState.navController

    // Estado compartido por varias rutas (cada capa lo colecta de su ViewModel; StateFlow
    // hace que ambas vean lo mismo sin acoplarse entre sí).
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(
            route = Screen.Onboarding.route,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) }
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

        composable(
            route = Screen.Library.route,
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(300)) }
        ) {
            LibraryScreen(
                isLoggedIn = loggedIn,
                onLogoutClick = onDisconnectOneDrive,
                onDownloadManagerClick = { navController.navigate(Screen.DownloadManager.route) },
                onPlaylistClick = { id, name -> navController.navigate(Screen.PlaylistDetail.createRoute(id, name)) },
                onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
                onAlbumClick = { name -> navController.navigate(Screen.AlbumDetail.createRoute(name)) },
                onNavigateToNowPlaying = { appState.playerExpanded = true },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                playbackViewModel = playbackViewModel,
                onPlaylistsTabActive = { appState.homeOnPlaylistsTab = it },
                // Shared elements foto/carátula → headers de los detalles.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.PlaylistDetail.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
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
                playbackState = playbackState,
                isFavoritesList = false,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.playerExpanded = true
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.playerExpanded = true
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onReorderSongs = { songIds -> libraryViewModel.reorderPlaylistSongs(playlistId, songIds) },
                onRemoveSong = { songId -> libraryViewModel.removeSongFromPlaylist(playlistId, songId) },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        composable(
            route = Screen.ArtistDetail.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) { backStackEntry ->
            val artistName = Screen.ArtistDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.ArtistDetail.ARG_ARTIST_NAME)
            )
            ArtistDetailScreen(
                artistName = artistName,
                currentSong = currentSong,
                playbackState = playbackState,
                favorites = libraryUiState.favorites,
                playlists = libraryUiState.playlists,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album -> appState.navigateToAlbum(album) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.playerExpanded = true
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.playerExpanded = true
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
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
            route = Screen.AlbumDetail.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) { backStackEntry ->
            val albumName = Screen.AlbumDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.AlbumDetail.ARG_ALBUM_NAME)
            )
            AlbumDetailScreen(
                albumName = albumName,
                currentSong = currentSong,
                playbackState = playbackState,
                favorites = libraryUiState.favorites,
                playlists = libraryUiState.playlists,
                onBackClick = { navController.popBackStack() },
                onArtistClick = { artist -> appState.navigateToArtist(artist) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.playerExpanded = true
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.playerExpanded = true
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
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
            route = Screen.Favorites.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            PlaylistDetailScreen(
                playlistName = "Favoritos",
                songs = libraryUiState.favoriteSongs,
                currentSong = currentSong,
                playbackState = playbackState,
                isFavoritesList = true,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.playSongs(songs, index)
                    appState.playerExpanded = true
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.shufflePlay(songs)
                    appState.playerExpanded = true
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
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
            route = Screen.SettingsSources.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            SettingsSourcesScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                authLoading = authLoading,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsBackup.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            SettingsBackupScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn
            )
        }

        composable(
            route = Screen.SettingsPlayback.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            SettingsPlaybackScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SettingsDownloads.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            SettingsDownloadsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SettingsAppearance.route,
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) }
        ) {
            SettingsAppearanceScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.DownloadManager.route) {
            DownloadManagerScreen(onBackClick = { navController.popBackStack() })
        }
    }
}
