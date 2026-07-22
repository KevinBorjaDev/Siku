package com.qhana.siku.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Playlist del usuario. `uuid` sigue siendo la clave estable de la playlist de
 * favoritos (UUID fijo `AppConfig.FAVORITES_PLAYLIST_UUID`); el `playlistId`
 * autogenerado es de uso local.
 */
@Entity(
    tableName = "playlists",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val uuid: String,
    val name: String,
    val dateCreated: Long = System.currentTimeMillis()
)
