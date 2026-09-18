package com.qhana.siku.data.model

/**
 * Criterio de orden de la lista de canciones dentro de una pantalla de DETALLE (artista, álbum,
 * género). La dirección va aparte, en [SortSpec].
 *
 * Es un enum aparte de [SortOrder] —el de la pestaña Todas— y no una reutilización, por [DEFAULT]:
 * ahí el orden natural depende de la pantalla (álbum + pista en un artista, pista en un álbum,
 * título en un género) y eso no es una columna, así que no cabe en un enum cuyos valores nombran
 * una. El resto de criterios sí coinciden y comparten su traducción a SQL
 * (`SongDao.songOrderClause`), que es donde vive el collation y el desempate.
 *
 * **Se persiste UNO solo para las tres pantallas** (`MusicPreferences.loadDetailSongSort`), no uno
 * por artista: guardarlo por entidad serían N claves en DataStore, que reescribe el archivo entero
 * en cada volcado. Por eso [DEFAULT] tiene que seguir siendo válido en las tres — cada una lo
 * traduce a su propio orden de catálogo y lo etiqueta con su nombre.
 *
 * El orden en el que se declaran es el del menú del chip.
 */
enum class DetailSongSort(override val defaultAscending: Boolean) : SortCriterion {
    /**
     * El orden del catálogo, distinto en cada pantalla. Ver el KDoc de la clase.
     *
     * **Es el único criterio que no es una columna**, así que invertirlo invierte el orden
     * COMPLETO (en un artista: álbum, pista y título a la vez). Se ordena igual, pero la flecha
     * aquí no significa "de la A a la Z" sino "del derecho o del revés".
     */
    CATALOG(defaultAscending = true),
    TITLE(defaultAscending = true),
    PLAY_COUNT(defaultAscending = false),
    LAST_PLAYED(defaultAscending = false),
    DATE_ADDED(defaultAscending = false);

    companion object {
        val DEFAULT: SortSpec<DetailSongSort> = SortSpec.of(CATALOG)

        fun fromLegacy(name: String): SortSpec<DetailSongSort>? = when (name) {
            "DEFAULT" -> SortSpec(CATALOG, true)
            "TITLE_ASC" -> SortSpec(TITLE, true)
            "TITLE_DESC" -> SortSpec(TITLE, false)
            "MOST_PLAYED" -> SortSpec(PLAY_COUNT, false)
            "RECENTLY_PLAYED" -> SortSpec(LAST_PLAYED, false)
            "RECENTLY_ADDED" -> SortSpec(DATE_ADDED, false)
            else -> null
        }
    }
}
