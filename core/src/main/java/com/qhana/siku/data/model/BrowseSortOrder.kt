package com.qhana.siku.data.model

/**
 * Criterios de la pestaña **Artistas** (lista derivada del GROUP BY de canciones).
 * La dirección va aparte, en [SortSpec]; los criterios desempatan por nombre.
 */
enum class ArtistSortOrder(override val defaultAscending: Boolean) : SortCriterion {
    NAME(defaultAscending = true),
    SONG_COUNT(defaultAscending = false),
    DATE_ADDED(defaultAscending = false);

    companion object {
        val DEFAULT: SortSpec<ArtistSortOrder> = SortSpec.of(NAME)

        fun fromLegacy(name: String): SortSpec<ArtistSortOrder>? = when (name) {
            "NAME" -> SortSpec(NAME, true)
            "SONG_COUNT" -> SortSpec(SONG_COUNT, false)
            "RECENTLY_ADDED" -> SortSpec(DATE_ADDED, false)
            else -> null
        }
    }
}

/**
 * Criterios de la pestaña **Álbumes** (agrupados solo por nombre; artista = representativo).
 * La dirección va aparte, en [SortSpec]; los criterios desempatan por nombre de álbum.
 *
 * [YEAR] sale de `songs.year`, donde un **0 significa "el archivo no lo declara"** y no un año: por
 * eso los álbumes sin año quedan al final en las DOS direcciones (ver `SongDao.buildAlbumsQuery`).
 */
enum class AlbumSortOrder(override val defaultAscending: Boolean) : SortCriterion {
    NAME(defaultAscending = true),
    ARTIST(defaultAscending = true),
    YEAR(defaultAscending = false),
    DATE_ADDED(defaultAscending = false);

    companion object {
        val DEFAULT: SortSpec<AlbumSortOrder> = SortSpec.of(NAME)

        fun fromLegacy(name: String): SortSpec<AlbumSortOrder>? = when (name) {
            "NAME" -> SortSpec(NAME, true)
            "ARTIST" -> SortSpec(ARTIST, true)
            "RECENTLY_ADDED" -> SortSpec(DATE_ADDED, false)
            else -> null
        }
    }
}
