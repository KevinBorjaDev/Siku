package com.qhana.siku.data.repository

import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LocalMetadataRepository @Inject constructor(
    private val songDao: SongDao
) : ILocalMetadataRepository {

    override suspend fun getColors(songId: String): AlbumColors? = withContext(Dispatchers.IO) {
        val colors = songDao.getColorsById(songId)
        if (colors?.colorPrimary != null && colors.colorSecondary != null && colors.colorPrimary != 0 && colors.colorSecondary != 0)
            AlbumColors(colors.colorPrimary, colors.colorSecondary)
        else null
    }

    override suspend fun saveColors(songId: String, primary: Int?, secondary: Int?) = withContext(Dispatchers.IO) {
        songDao.updateColors(songId, primary, secondary)
    }

    override suspend fun getSongsWithoutColors(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsWithoutColorsDirectly().map { it.toSong() }
    }

    override suspend fun resetAllColors() = withContext(Dispatchers.IO) {
        songDao.resetColors()
    }

    override suspend fun saveColorsBatch(colors: List<Triple<String, Int, Int>>) = withContext(Dispatchers.IO) {
        songDao.updateColorsBatch(colors)
    }

    override suspend fun saveLyrics(songId: String, lyrics: String) = withContext(Dispatchers.IO) {
        songDao.updateLyrics(songId, lyrics, System.currentTimeMillis())
    }

    override suspend fun markLyricsNotFound(songId: String) = withContext(Dispatchers.IO) {
        songDao.markLyricsNotFound(songId, System.currentTimeMillis())
    }
}
