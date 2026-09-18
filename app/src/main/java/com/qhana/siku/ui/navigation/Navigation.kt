package com.qhana.siku.ui.navigation

import android.net.Uri
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsScopeKind
import com.qhana.siku.data.model.StatsSelection
import java.time.LocalDate

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
    /** Agrupación de artistas y separación de colaboraciones (v29). */
    data object SettingsArtists : Screen("settings/artists")

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

    /**
     * Estadísticas de UNA entidad: un artista, un álbum o un género. Se llega tocándola en los
     * rankings de [Stats].
     *
     * **Una sola ruta con el tipo como argumento**, no tres: las tres pantallas son la misma con
     * otro `WHERE` (ver `StatsScope`), así que tres rutas serían tres formas de decir lo mismo y
     * tres sitios donde olvidarse de un argumento.
     *
     * **El periodo viaja en la ruta**, y por eso son cuatro argumentos: se entra desde una pantalla
     * que está mirando un periodo concreto ("agosto de 2026") y abrir la entidad en otro distinto
     * rompería la pregunta que el usuario acababa de hacerse. Después la pantalla lo navega por su
     * cuenta, con su propio selector — de ahí que el argumento sea el periodo INICIAL y no un
     * estado compartido, que además no podría existir: cada ruta resuelve su propio ViewModel
     * (convención 9).
     *
     * Lo que NO viaja es el ajuste de géneros compuestos: es una preferencia VIVA y meterla aquí la
     * congelaría en el valor que tuviera al navegar. La pantalla la observa.
     */
    data object EntityStats : Screen(
        "entity_stats/{kind}/{entityName}/{periodMode}/{periodAnchor}?albumArtist={albumArtist}"
    ) {
        const val ARG_KIND = "kind"
        const val ARG_ENTITY_NAME = "entityName"
        const val ARG_PERIOD_MODE = "periodMode"
        const val ARG_PERIOD_ANCHOR = "periodAnchor"
        /** Solo para un álbum, y OPCIONAL por lo mismo que en [AlbumDetail]. */
        const val ARG_ALBUM_ARTIST = "albumArtist"

        fun createRoute(
            kind: StatsScopeKind,
            name: String,
            selection: StatsSelection,
            albumArtist: String?
        ): String = "entity_stats/${kind.name}/${encodeNameArg(name)}/" +
            "${selection.mode.name}/${selection.anchor}" +
            if (albumArtist == null) "" else "?albumArtist=${encodeNameArg(albumArtist)}"

        fun decodeName(raw: String?): String = decodeNameArg(raw)

        fun decodeAlbumArtist(raw: String?): String? = raw?.let(::decodeNameArg)

        /** Ver [decodeSelection] para por qué los `catch`. */
        fun decodeKind(raw: String?): StatsScopeKind = try {
            StatsScopeKind.valueOf(raw ?: "")
        } catch (_: Exception) {
            StatsScopeKind.ARTIST
        }

        /**
         * El periodo de vuelta desde sus dos segmentos. Vive aquí, pegado a [createRoute], para que
         * el formato esté escrito una sola vez: son la misma decisión por los dos lados.
         *
         * Los `catch` no son defensa contra el usuario —la ruta la construye la app— sino contra
         * que un enum cambie de valores entre versiones y el sistema restaure un back stack viejo:
         * con eso, abrir la app tras actualizar sería un crash.
         */
        fun decodeSelection(mode: String?, anchor: String?): StatsSelection {
            val periodMode = try {
                StatsPeriodMode.valueOf(mode ?: "")
            } catch (_: Exception) {
                StatsPeriodMode.ALL
            }
            val date = try {
                LocalDate.parse(anchor)
            } catch (_: Exception) {
                LocalDate.now()
            }
            return StatsSelection(periodMode, date)
        }
    }

    /**
     * El artista del álbum viaja como argumento OPCIONAL (v29), no como segmento: quien solo conoce el
     * nombre —el reproductor antes de leer el tag, las estadísticas, un contexto guardado— sigue
     * navegando igual y abre el álbum por nombre, como siempre. Ausente = `null` = "cualquier artista
     * del álbum"; presente y vacío = el grupo de las pistas SIN ese tag, que es otro conjunto.
     */
    data object AlbumDetail : Screen("album_detail/{albumName}?albumArtist={albumArtist}") {
        const val ARG_ALBUM_NAME = "albumName"
        const val ARG_ALBUM_ARTIST = "albumArtist"
        fun createRoute(albumName: String, albumArtist: String?): String =
            "album_detail/${encodeNameArg(albumName)}" +
                if (albumArtist == null) "" else "?albumArtist=${encodeNameArg(albumArtist)}"
        fun decodeName(raw: String?): String = decodeNameArg(raw)
        /** `null` se conserva: es "sin clave", no la clave vacía. */
        fun decodeAlbumArtist(raw: String?): String? = raw?.let(::decodeNameArg)
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
