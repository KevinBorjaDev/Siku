package com.qhana.siku.data.model

/**
 * Órdenes de la pestaña Artistas (lista derivada del GROUP BY de canciones).
 * NAME es el default; los demás desempatan por nombre.
 */
enum class ArtistSortOrder {
    NAME,
    SONG_COUNT,
    RECENTLY_ADDED
}

/**
 * Órdenes de la pestaña Álbumes (agrupados solo por nombre; artista = representativo).
 * NAME es el default; los demás desempatan por nombre.
 */
enum class AlbumSortOrder {
    NAME,
    ARTIST,
    RECENTLY_ADDED
}
