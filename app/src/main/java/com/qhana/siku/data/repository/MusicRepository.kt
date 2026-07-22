package com.qhana.siku.data.repository

import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Facade que delega en repositorios especializados (ISP).
 */
class MusicRepository @Inject constructor(
    private val songRepository: ISongRepository,
    private val playlistRepository: IPlaylistRepository,
    private val localMetadataRepository: ILocalMetadataRepository,
    private val browseRepository: BrowseRepository
) : IMusicRepository {

    // --- Song ---
    override fun getSongsPaging(query: String, sortOrder: SortOrder, sourceFilters: Set<SongSourceFilter>): Flow<PagingData<Song>> =
        songRepository.getSongsPaging(query, sortOrder, sourceFilters)
    override suspend fun getSongsSnapshot(query: String, sortOrder: SortOrder, sourceFilters: Set<SongSourceFilter>): List<Song> =
        songRepository.getSongsSnapshot(query, sortOrder, sourceFilters)
    override fun getRecentlyPlayed(limit: Int): Flow<List<Song>> = songRepository.getRecentlyPlayed(limit)
    override fun getMostPlayed(limit: Int): Flow<List<Song>> = songRepository.getMostPlayed(limit)
    override fun getRecentlyAdded(limit: Int): Flow<List<Song>> = songRepository.getRecentlyAdded(limit)
    override fun getSongsByArtist(artist: String): Flow<List<Song>> = songRepository.getSongsByArtist(artist)
    override fun getRediscover(before: Long, limit: Int): Flow<List<Song>> = songRepository.getRediscover(before, limit)
    override fun getTopGenres(minCount: Int, limit: Int): Flow<List<com.qhana.siku.data.local.GenreSummary>> = songRepository.getTopGenres(minCount, limit)
    override suspend fun getSongsByGenre(genre: String): List<Song> = songRepository.getSongsByGenre(genre)
    override suspend fun getDownloadedSongsWithoutGenre(limit: Int): List<Song> = songRepository.getDownloadedSongsWithoutGenre(limit)
    override suspend fun updateGenre(songId: String, genre: String?) = songRepository.updateGenre(songId, genre)
    override fun getTopPlayedArtist(minSongs: Int): Flow<String?> = songRepository.getTopPlayedArtist(minSongs)
    override fun getPlayedSinceCount(since: Long): Flow<Int> = songRepository.getPlayedSinceCount(since)
    override fun getLibrarySize(): Flow<Int> = songRepository.getLibrarySize()

    override fun getSongByIdFlow(songId: String): Flow<Song?> = songRepository.getSongByIdFlow(songId)
    override suspend fun getSongById(songId: String): AppResult<Song> = songRepository.getSongById(songId)
    override suspend fun getSongsByIds(ids: List<String>): List<Song> = songRepository.getSongsByIds(ids)
    override suspend fun getAllSongIds(): List<String> = songRepository.getAllSongIds()
    override suspend fun getSongIdsBySourceType(sourceType: com.qhana.siku.data.model.SourceType): List<String> =
        songRepository.getSongIdsBySourceType(sourceType)
    override suspend fun getSongIdsByAlbum(album: String): List<String> = songRepository.getSongIdsByAlbum(album)
    override suspend fun getCachedSongs(): List<Song> = songRepository.getCachedSongs()
    override fun getSongCountFlow(sourceFilters: Set<SongSourceFilter>): Flow<Int> =
        songRepository.getSongCountFlow(sourceFilters)
    override fun hasLocalSongsFlow(): Flow<Boolean> = songRepository.hasLocalSongsFlow()
    override suspend fun findCrossSourceDuplicates(loserSource: SourceType): List<com.qhana.siku.data.local.DuplicatePair> =
        songRepository.findCrossSourceDuplicates(loserSource)
    override suspend fun countCrossSourceDuplicates(): Int = songRepository.countCrossSourceDuplicates()
    override suspend fun getRelativePathsOfOtherSources(sourceType: SourceType): Set<String> =
        songRepository.getRelativePathsOfOtherSources(sourceType)
    override suspend fun mergePlayStats(loserId: String, winnerId: String) =
        songRepository.mergePlayStats(loserId, winnerId)
    override suspend fun repointSongRefs(loserId: String, winnerId: String) =
        playlistRepository.repointSongRefs(loserId, winnerId)
    override fun hasCloudSongsFlow(): Flow<Boolean> = songRepository.hasCloudSongsFlow()
    override suspend fun upsertSongs(songs: List<Song>): AppResult<Int> = songRepository.upsertSongs(songs)
    override suspend fun getSongsNeedingMetadataOrDownload(limit: Int, offset: Int): List<Song> = songRepository.getSongsNeedingMetadataOrDownload(limit, offset)
    override suspend fun requeueDownloadedSongsWithoutMetadata(): Int = songRepository.requeueDownloadedSongsWithoutMetadata()
    override suspend fun deleteAudioFileById(songId: String) = songRepository.deleteAudioFileById(songId)
    override suspend fun deleteSongs(idsToDelete: List<String>) = songRepository.deleteSongs(idsToDelete)
    override suspend fun countSongsNeedingWork(): Int = songRepository.countSongsNeedingWork()
    override suspend fun getTotalDownloadedBytes(): Long = songRepository.getTotalDownloadedBytes()
    override suspend fun getEvictionCandidates(excludeId: String): List<Pair<String, Long>> = songRepository.getEvictionCandidates(excludeId)
    override suspend fun getDownloadAttempts(songId: String): Int = songRepository.getDownloadAttempts(songId)
    override suspend fun markDownloadFailed(songId: String, error: String, transient: Boolean, attempts: Int, nextRetryAt: Long) =
        songRepository.markDownloadFailed(songId, error, transient, attempts, nextRetryAt)
    override suspend fun clearDownloadError(songId: String) = songRepository.clearDownloadError(songId)
    override suspend fun resetDownloadErrors(): List<String> = songRepository.resetDownloadErrors()
    override fun getFailedDownloadsFlow(): Flow<List<FailedDownload>> = songRepository.getFailedDownloadsFlow()
    override suspend fun getEarliestRetryAt(): Long? = songRepository.getEarliestRetryAt()
    override suspend fun updateSongMetadata(song: Song) = songRepository.updateSongMetadata(song)
    override suspend fun updateAlbumArtUri(songId: String, uri: String?) = songRepository.updateAlbumArtUri(songId, uri)
    override suspend fun getSongsWithLocalArt(): List<Song> = songRepository.getSongsWithLocalArt()
    override suspend fun getAllSongs(): List<Song> = songRepository.getAllSongs()
    override suspend fun updateSongUrl(songId: String, newUrl: String) = songRepository.updateSongUrl(songId, newUrl)
    override suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?) = songRepository.updateReplayGain(songId, trackGainDb, trackPeak, albumGainDb, albumPeak)
    override suspend fun recordPlay(songId: String, at: Long) = songRepository.recordPlay(songId, at)
    override suspend fun markSongAsCorrupted(songId: String) = songRepository.markSongAsCorrupted(songId)
    override suspend fun clearCorrupted(songId: String) = songRepository.clearCorrupted(songId)
    override suspend fun getCorruptedSongs(): List<Song> = songRepository.getCorruptedSongs()
    override suspend fun deleteAll() = songRepository.deleteAll()

    // --- Playlists (incluye favoritos como una playlist más) ---
    override fun getUserPlaylists(): Flow<List<Playlist>> = playlistRepository.getUserPlaylists()
    override fun getPlaylistsCoverMeta(): Flow<Map<Long, PlaylistCoverMeta>> = playlistRepository.getPlaylistsCoverMeta()
    override suspend fun createPlaylist(name: String): Long = playlistRepository.createPlaylist(name)
    override suspend fun deletePlaylist(playlistId: Long) = playlistRepository.deletePlaylist(playlistId)
    override suspend fun renamePlaylist(playlistId: Long, name: String) = playlistRepository.renamePlaylist(playlistId, name)
    override suspend fun addSongToPlaylist(playlistId: Long, songId: String) = playlistRepository.addSongToPlaylist(playlistId, songId)
    override suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<String>): Int = playlistRepository.addSongsToPlaylist(playlistId, songIds)
    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) = playlistRepository.removeSongFromPlaylist(playlistId, songId)
    override fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>> = playlistRepository.getSongsForPlaylist(playlistId)
    override suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>) = playlistRepository.reorderPlaylistSongs(playlistId, songIds)
    override suspend fun ensureFavoritesPlaylist() = playlistRepository.ensureFavoritesPlaylist()
    override suspend fun toggleFavorite(songId: String) = playlistRepository.toggleFavorite(songId)
    override suspend fun addSongsToFavorites(songIds: List<String>): Int = playlistRepository.addSongsToFavorites(songIds)
    override fun getFavoritesIds(): Flow<Set<String>> = playlistRepository.getFavoritesIds()
    override fun getFavoritesSongs(): Flow<List<Song>> = playlistRepository.getFavoritesSongs()
    override fun getFavoritesPaging(query: String, sortOrder: SortOrder): Flow<PagingData<Song>> = playlistRepository.getFavoritesPaging(query, sortOrder)
    override suspend fun getFavoritesSnapshot(query: String): List<Song> = playlistRepository.getFavoritesSnapshot(query)
    override suspend fun deleteAllPlaylists() = playlistRepository.deleteAllPlaylists()

    override suspend fun clearAllUserData() {
        songRepository.deleteAll()
        playlistRepository.deleteAllPlaylists()
        localMetadataRepository.resetAllColors()
        browseRepository.clearArtistCache()
    }

    override suspend fun clearSourceData(sourceType: SourceType) {
        // deleteSongs (no un DELETE crudo): además de las filas, borra el audio descargado
        // (`file://`) y las carátulas en disco. Un DELETE dejaría los FLAC huérfanos.
        songRepository.deleteSongs(songRepository.getSongIdsBySourceType(sourceType))
    }

    // --- Metadata ---
    override suspend fun getColors(songId: String): AlbumColors? = localMetadataRepository.getColors(songId)
    override suspend fun saveColors(songId: String, primary: Int?, secondary: Int?) = localMetadataRepository.saveColors(songId, primary, secondary)
    override suspend fun getSongsWithoutColors(): List<Song> = localMetadataRepository.getSongsWithoutColors()
    override suspend fun resetAllColors() = localMetadataRepository.resetAllColors()
    override suspend fun saveColorsBatch(colors: List<Triple<String, Int, Int>>) = localMetadataRepository.saveColorsBatch(colors)
    override suspend fun saveLyrics(songId: String, lyrics: String) = localMetadataRepository.saveLyrics(songId, lyrics)
    override suspend fun markLyricsNotFound(songId: String) = localMetadataRepository.markLyricsNotFound(songId)
}
