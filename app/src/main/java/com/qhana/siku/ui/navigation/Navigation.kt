package com.qhana.siku.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry

/**
 * Rutas de navegación de la aplicación.
 * Las rutas se declaran como constantes para que todos los callers las referencien
 * y detectar typos en tiempo de compilación.
 */
sealed class Screen(val route: String) {
    /**
     * Primer arranque: elección de fuentes (OneDrive y/o carpeta local). Sustituye a la antigua
     * pantalla `login`, que forzaba una cuenta de OneDrive incluso a un usuario solo-local.
     */
    data object Onboarding : Screen("onboarding")
    data object Library : Screen("library")
    data object NowPlaying : Screen("now_playing")
    data object Favorites : Screen("favorites")
    data object Settings : Screen("settings")

    // Sub-pantallas de Ajustes (estilo Ajustes de Android: hub de categorías + una pantalla
    // por categoría).
    data object SettingsSources : Screen("settings/sources")
    data object SettingsBackup : Screen("settings/backup")
    data object SettingsPlayback : Screen("settings/playback")
    data object SettingsDownloads : Screen("settings/downloads")
    data object SettingsAppearance : Screen("settings/appearance")

    data object DownloadManager : Screen("download_manager")

    data object PlaylistDetail : Screen("playlist_detail/{playlistId}/{playlistName}") {
        const val ARG_PLAYLIST_ID = "playlistId"
        const val ARG_PLAYLIST_NAME = "playlistName"

        /**
         * Construye la ruta con URL-encoding del nombre, para que no se rompa
         * si el usuario nombra la playlist con "/", "?" o similares.
         */
        fun createRoute(playlistId: Long, playlistName: String): String {
            val encoded = Uri.encode(playlistName)
            return "playlist_detail/$playlistId/$encoded"
        }

        /**
         * Helper para extraer el nombre decodificado del NavBackStackEntry.
         */
        fun decodeName(raw: String?): String = raw?.let { Uri.decode(it) } ?: "Playlist"
    }

    data object ArtistDetail : Screen("artist_detail/{artistName}") {
        const val ARG_ARTIST_NAME = "artistName"
        fun createRoute(artistName: String): String = "artist_detail/${encodeNameArg(artistName)}"
        fun decodeName(raw: String?): String = decodeNameArg(raw)
    }

    data object AlbumDetail : Screen("album_detail/{albumName}") {
        const val ARG_ALBUM_NAME = "albumName"
        fun createRoute(albumName: String): String = "album_detail/${encodeNameArg(albumName)}"
        fun decodeName(raw: String?): String = decodeNameArg(raw)
    }
}

/**
 * Encoding de nombres de artista/álbum como segmento de ruta: URL-encode + sentinel para
 * el string VACÍO (un segmento vacío rompe el match de la ruta). Los tags pueden traer
 * cualquier cosa.
 */
private const val EMPTY_NAME_TOKEN = "__empty__"

private fun encodeNameArg(name: String): String =
    if (name.isEmpty()) EMPTY_NAME_TOKEN else Uri.encode(name)

private fun decodeNameArg(raw: String?): String = when (raw) {
    null, EMPTY_NAME_TOKEN -> ""
    else -> Uri.decode(raw)
}

// Extension functions for consistent transitions across the app
object Transitions {
    val enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition) = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) + fadeIn(tween(400))
    }

    val exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition) = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) + fadeOut(tween(200))
    }

    val popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition) = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) + fadeIn(tween(400))
    }

    val popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition) = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) + fadeOut(tween(200))
    }
    
    // Vertical slide for Now Playing screen (Modal feel)
    val enterTransitionVertical: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition) = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(400)) + fadeIn(tween(400))
    }

    val exitTransitionVertical: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition) = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(400)) + fadeOut(tween(200))
    }
}
