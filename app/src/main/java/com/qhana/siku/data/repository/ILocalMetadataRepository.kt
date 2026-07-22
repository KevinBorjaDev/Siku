package com.qhana.siku.data.repository

import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song

interface ILocalMetadataRepository {
    suspend fun getColors(songId: String): AlbumColors?
    suspend fun saveColors(songId: String, primary: Int?, secondary: Int?)
    suspend fun getSongsWithoutColors(): List<Song>
    suspend fun resetAllColors()
    suspend fun saveColorsBatch(colors: List<Triple<String, Int, Int>>)
    suspend fun saveLyrics(songId: String, lyrics: String)
    suspend fun markLyricsNotFound(songId: String)
}
