package com.qhana.siku.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.PlaylistDao
import com.qhana.siku.data.local.PlaylistEntity
import com.qhana.siku.data.local.PlaylistSongCrossRef
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SortSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) : IPlaylistRepository {

    override suspend fun repointSongRefs(loserId: String, winnerId: String) =
        // Atómico en el DAO: son dos statements (re-apuntar + borrar los que el UPDATE ignoró por
        // duplicado) y morir entre medias dejaba refs de la perdedora apuntando a una fila que el
        // dedupe va a borrar.
        playlistDao.repointAndCleanupSongRefs(loserId, winnerId)

    override fun getUserPlaylists(): Flow<List<Playlist>> =
        playlistDao.getUserPlaylistsFlow(AppConfig.FAVORITES_PLAYLIST_UUID).map { entities ->
            entities.map { Playlist(it.playlistId, it.name, it.dateCreated) }
        }

    override fun getPlaylistsCoverMeta(): Flow<Map<Long, PlaylistCoverMeta>> =
        playlistDao.getPlaylistArtRowsFlow().map { rows ->
            rows.groupBy { it.playlistId }.mapValues { (_, songRows) ->
                PlaylistCoverMeta(
                    songCount = songRows.size,
                    arts = songRows.mapNotNull { it.art }.distinct().take(4)
                )
            }
        }

    override suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.upsertPlaylist(
            PlaylistEntity(
                uuid = UUID.randomUUID().toString(),
                name = name,
                dateCreated = System.currentTimeMillis()
            )
        )
    }

    override suspend fun getPlaylistName(playlistId: Long): String? = withContext(Dispatchers.IO) {
        playlistDao.getPlaylistById(playlistId)?.name
    }

    override suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        // Hard-delete: la fila y sus cross-refs en una transacción (el soft-delete con
        // tombstone existía solo para el sync con la app de escritorio ya descartada).
        playlistDao.deletePlaylistWithSongs(playlistId)
    }

    override suspend fun renamePlaylist(playlistId: Long, name: String) = withContext(Dispatchers.IO) {
        playlistDao.renamePlaylist(playlistId, name)
    }

    override suspend fun addSongToPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        val currentMax = playlistDao.getMaxOrderIndex(playlistId) ?: -1
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, currentMax + 1))
    }

    override suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<String>): Int = withContext(Dispatchers.IO) {
        // Las ya presentes se descartan ANTES de insertar: el INSERT es IGNORE, así que si se
        // colaran consumirían un orderIndex sin crear fila y dejarían huecos en el orden.
        val existing = playlistDao.getSongIdsInPlaylist(playlistId).toSet()
        val toAdd = songIds.filterNot { it in existing }
        if (toAdd.isEmpty()) return@withContext 0

        val startIndex = (playlistDao.getMaxOrderIndex(playlistId) ?: -1) + 1
        playlistDao.addSongsToPlaylist(
            toAdd.mapIndexed { offset, songId ->
                PlaylistSongCrossRef(playlistId, songId, startIndex + offset)
            }
        )
        toAdd.size
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    override fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>> =
        playlistDao.getSongsForPlaylist(playlistId).map { entities -> entities.map { it.toSong() } }

    override suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>) = withContext(Dispatchers.IO) {
        playlistDao.reorderPlaylistSongs(playlistId, songIds)
    }

    // --- Favoritos ---

    override suspend fun ensureFavoritesPlaylist() = withContext(Dispatchers.IO) {
        val existing = playlistDao.getPlaylistByUuid(AppConfig.FAVORITES_PLAYLIST_UUID)
        if (existing == null) {
            playlistDao.upsertPlaylist(
                PlaylistEntity(
                    uuid = AppConfig.FAVORITES_PLAYLIST_UUID,
                    name = AppConfig.FAVORITES_PLAYLIST_NAME,
                    dateCreated = System.currentTimeMillis()
                )
            )
        }
        Unit
    }

    override suspend fun toggleFavorite(songId: String): Boolean = withContext(Dispatchers.IO) {
        val playlistId = favoritesPlaylistIdOrCreate()
        if (playlistDao.isSongInPlaylist(playlistId, songId)) {
            playlistDao.removeSongFromPlaylist(playlistId, songId)
            false
        } else {
            val currentMax = playlistDao.getMaxOrderIndex(playlistId) ?: -1
            playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, currentMax + 1))
            true
        }
    }

    override suspend fun addSongsToFavorites(songIds: List<String>): Int =
        addSongsToPlaylist(favoritesPlaylistIdOrCreate(), songIds)

    override fun getFavoritesIds(): Flow<Set<String>> =
        playlistDao.getSongIdsByPlaylistUuidFlow(AppConfig.FAVORITES_PLAYLIST_UUID).map { it.toSet() }

    override fun getFavoritesSongs(): Flow<List<Song>> =
        playlistDao.getSongsByPlaylistUuidFlow(AppConfig.FAVORITES_PLAYLIST_UUID).map { entities ->
            entities.map { it.toSong() }
        }

    override fun getFavoritesPaging(query: String, sortOrder: SortSpec<SortOrder>): Flow<PagingData<Song>> {
        // sortOrder no se aplica aún en favoritos paging — pendiente si el usuario lo pide.
        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = true),
            pagingSourceFactory = {
                if (query.isNotBlank())
                    // El DAO pone los `%` y el `ESCAPE '\'`; aquí solo hay que neutralizar los
                    // comodines que traiga el texto del usuario (buscar "100%" es literal).
                    playlistDao.searchSongsByPlaylistUuidPaging(
                        AppConfig.FAVORITES_PLAYLIST_UUID,
                        SongDao.escapeLike(query)
                    )
                else
                    playlistDao.getSongsByPlaylistUuidPaging(AppConfig.FAVORITES_PLAYLIST_UUID)
            }
        ).flow.map { pagingData -> pagingData.map { it.toSong() } }
    }

    override suspend fun getFavoritesSnapshot(query: String): List<Song> = withContext(Dispatchers.IO) {
        val entities = if (query.isNotBlank())
            playlistDao.searchSongsByPlaylistUuidList(
                AppConfig.FAVORITES_PLAYLIST_UUID,
                SongDao.escapeLike(query)
            )
        else
            playlistDao.getSongsByPlaylistUuidList(AppConfig.FAVORITES_PLAYLIST_UUID)
        entities.map { it.toSong() }
    }

    override suspend fun deleteAllPlaylists() = withContext(Dispatchers.IO) {
        playlistDao.deleteAllCrossRefs()
        playlistDao.deleteAllPlaylists()
    }

    private suspend fun favoritesPlaylistIdOrCreate(): Long {
        playlistDao.getPlaylistIdByUuid(AppConfig.FAVORITES_PLAYLIST_UUID)?.let { return it }
        ensureFavoritesPlaylist()
        return playlistDao.getPlaylistIdByUuid(AppConfig.FAVORITES_PLAYLIST_UUID)
            ?: error("Failed to create favorites playlist")
    }
}
