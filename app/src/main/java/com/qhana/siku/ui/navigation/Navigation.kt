package com.qhana.siku.ui.navigation

import android.net.Uri

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
    // El reproductor a pantalla completa NO es una ruta: es una capa que hace container transform con
    // la píldora (ver [com.qhana.siku.ui.PlayerOverlay]), gobernada por `MusicAppState.playerExpanded`.
    data object Favorites : Screen("favorites")
    data object Settings : Screen("settings")

    // Sub-pantallas de Ajustes (estilo Ajustes de Android: hub de categorías + una pantalla
    // por categoría).
    data object SettingsSources : Screen("settings/sources")
    // Cuelga de Fuentes y no del hub: es configuración DEL escaneo del dispositivo, no una
    // categoría propia, y solo tiene sentido con ese modo activo.
    data object SettingsExcludedFolders : Screen("settings/excluded_folders")
    data object SettingsBackup : Screen("settings/backup")
    data object SettingsPlayback : Screen("settings/playback")
    data object SettingsDownloads : Screen("settings/downloads")
    data object SettingsAppearance : Screen("settings/appearance")
    // Categoría propia y NO una tarjeta dentro de Reproducción: los gestos son la forma de
    // interactuar con la app, no un ajuste de audio — bajo el encabezado "Volumen (ReplayGain)"
    // nadie los iba a encontrar.
    data object SettingsGestures : Screen("settings/gestures")

    // Personalizaciones con lista propia: viven fuera de Apariencia para que esa pantalla no
    // se convierta en un muro de opciones (dos listas drag & drop la ocupaban entera).
    data object SettingsTabs : Screen("settings/tabs")
    data object SettingsPlayerBar : Screen("settings/player_bar")
    data object SettingsProgressBar : Screen("settings/progress_bar")

    // Gestión de presets del EQ (ocultar/restaurar/borrar). Cuelga de Reproducción y no del hub:
    // es mantenimiento del ecualizador, no una categoría de ajustes por sí misma. Fuera de la hoja
    // del EQ porque esa pantalla es de uso constante y esto se hace una vez.
    data object SettingsEqPresets : Screen("settings/eq_presets")

    data object DownloadManager : Screen("download_manager")

    /** Estadísticas de escucha. Se llega desde la tarjeta del inicio. */
    data object Stats : Screen("stats")

    /**
     * "Recién agregadas" completa: el destino del "Ver todo" de esa sección del inicio.
     *
     * Es una lista VIRTUAL —no hay tabla ni entidad detrás, solo una consulta con ventana— igual
     * que un género, y por eso es una ruta y no una playlist de Room: una playlist es dato del
     * usuario (se reordena, se le quitan canciones) y una que se reescribiera sola pelearía con
     * eso, además de no tener respuesta para "¿qué pasa cuando una canción cumple 30 días?".
     */
    data object RecentlyAdded : Screen("recently_added")

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

    data object GenreDetail : Screen("genre_detail/{genreName}") {
        const val ARG_GENRE_NAME = "genreName"
        fun createRoute(genreName: String): String = "genre_detail/${encodeNameArg(genreName)}"
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

// Las transiciones de navegación viven en `ui/theme/Motion.kt` (`appNavForwardEnter` y compañía) y
// se aplican como DEFAULTS del NavHost en `AppNavHost`. Aquí había un objeto `Transitions` con seis
// lambdas de `tween(400)`/`tween(200)`: no lo referenciaba nadie —las rutas declaraban las suyas a
// mano— así que era una tercera copia muerta de la misma decisión.
