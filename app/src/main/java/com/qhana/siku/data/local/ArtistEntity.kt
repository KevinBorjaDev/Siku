package com.qhana.siku.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache de metadatos de artista (foto de Deezer + selección manual).
 *
 * La PK es el string EXACTO de `songs.artist` (case-sensitive): es la clave de join
 * con la tabla de canciones — no normalizar.
 */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val name: String,
    val deezerId: Long? = null,
    /** Foto grande para headers (picture_xl ?: picture_big de Deezer). */
    val imageUrl: String? = null,
    /** Miniatura para listas (picture_medium). */
    val thumbUrl: String? = null,
    /** true = elegido por el usuario en el picker; el auto-match nunca lo pisa. */
    val manuallySet: Boolean = false,
    /**
     * Epoch ms del último intento de fetch. Con imageUrl == null actúa como cache de
     * not-found con TTL de 14 días (mismo patrón que songs.lyricsAttemptedAt).
     */
    val fetchedAt: Long? = null
)
