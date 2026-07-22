package com.qhana.siku.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists WHERE uuid = :uuid LIMIT 1")
    suspend fun getPlaylistByUuid(uuid: String): PlaylistEntity?

    @Query("SELECT playlistId FROM playlists WHERE uuid = :uuid LIMIT 1")
    suspend fun getPlaylistIdByUuid(uuid: String): Long?

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)

    @Query("UPDATE playlists SET name = :name WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    /**
     * Borrado real de una playlist: sus cross-refs y la fila, en una transacción.
     */
    @Transaction
    suspend fun deletePlaylistWithSongs(playlistId: Long) {
        clearPlaylistSongs(playlistId)
        deletePlaylistById(playlistId)
    }

    /**
     * Lista de playlists "visibles" para el usuario: excluye la playlist de
     * favoritos (se muestra en su propia pantalla).
     */
    @Query("""
        SELECT * FROM playlists
        WHERE uuid != :favoritesUuid
        ORDER BY name COLLATE NOCASE ASC
    """)
    fun getUserPlaylistsFlow(favoritesUuid: String): Flow<List<PlaylistEntity>>

    // ==================== CROSS-REF ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongsToPlaylist(crossRefs: List<PlaylistSongCrossRef>)

    @Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getSongIdsInPlaylist(playlistId: Long): List<String>

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun clearPlaylistSongs(playlistId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun isSongInPlaylist(playlistId: Long, songId: String): Boolean

    // ==================== DUPLICADOS ENTRE FUENTES (v23) ====================

    /**
     * Re-apunta las refs de la canción perdedora a la ganadora antes de retirarla (fusión
     * de duplicados). OR IGNORE: si la lista ya contiene a la ganadora, la ref duplicada
     * choca con la PK (playlistId, songId) y se salta — [deleteRefsForSong] barre el resto.
     */
    @Query("UPDATE OR IGNORE playlist_song_cross_ref SET songId = :winnerId WHERE songId = :loserId")
    suspend fun repointSongRefs(loserId: String, winnerId: String)

    /** Refs restantes de la perdedora (las que chocaron en [repointSongRefs]). */
    @Query("DELETE FROM playlist_song_cross_ref WHERE songId = :loserId")
    suspend fun deleteRefsForSong(loserId: String)

    @Transaction
    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex ASC
    """)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("""
        SELECT s.id FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
    """)
    fun getSongIdsByPlaylistUuidFlow(uuid: String): Flow<List<String>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
        ORDER BY ref.orderIndex ASC
    """)
    fun getSongsByPlaylistUuidFlow(uuid: String): Flow<List<SongEntity>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
        ORDER BY s.title COLLATE NOCASE ASC
    """)
    fun getSongsByPlaylistUuidPaging(uuid: String): PagingSource<Int, SongEntity>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
            AND (s.title LIKE '%' || :query || '%'
                OR s.artist LIKE '%' || :query || '%'
                OR s.album LIKE '%' || :query || '%')
        ORDER BY s.title COLLATE NOCASE ASC
    """)
    fun searchSongsByPlaylistUuidPaging(uuid: String, query: String): PagingSource<Int, SongEntity>

    // Versiones no paginadas de las dos anteriores (mismo ORDER BY): construyen la cola
    // de favoritos idéntica a lo que se ve en pantalla.
    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
        ORDER BY s.title COLLATE NOCASE ASC
    """)
    suspend fun getSongsByPlaylistUuidList(uuid: String): List<SongEntity>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId
        INNER JOIN playlists p ON p.playlistId = ref.playlistId
        WHERE p.uuid = :uuid
            AND (s.title LIKE '%' || :query || '%'
                OR s.artist LIKE '%' || :query || '%'
                OR s.album LIKE '%' || :query || '%')
        ORDER BY s.title COLLATE NOCASE ASC
    """)
    suspend fun searchSongsByPlaylistUuidList(uuid: String, query: String): List<SongEntity>

    /**
     * Una fila por canción de cada playlist con su carátula (null si la canción ya no está o
     * no tiene). Alimenta los thumbnails/conteos de la pestaña Listas en UNA sola query
     * reactiva, en vez de N queries por playlist.
     */
    @Query("""
        SELECT ref.playlistId AS playlistId, s.albumArtUriString AS art
        FROM playlist_song_cross_ref ref
        LEFT JOIN songs s ON s.id = ref.songId
        ORDER BY ref.playlistId, ref.orderIndex ASC
    """)
    fun getPlaylistArtRowsFlow(): Flow<List<PlaylistArtRow>>

    @Query("SELECT MAX(orderIndex) FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getMaxOrderIndex(playlistId: Long): Int?

    @Query("UPDATE playlist_song_cross_ref SET orderIndex = :newIndex WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongOrder(playlistId: Long, songId: String, newIndex: Int)

    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>) {
        songIds.forEachIndexed { index, songId ->
            updateSongOrder(playlistId, songId, index)
        }
    }

    // ==================== BACKUP (Fase 6) ====================

    /** TODAS las playlists, incluida la de favoritos (a diferencia de [getUserPlaylistsFlow]). */
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    /**
     * Canciones de una playlist en su orden manual, para exportar.
     *
     * LEFT JOIN a propósito: una cross-ref puede apuntar a una canción que ya no está en `songs`
     * (p. ej. tras desconectar OneDrive). Esas entradas se exportan igual, con los tags a null —
     * si se perdieran, el backup no podría restaurarlas al reconectar la cuenta.
     */
    @Query("""
        SELECT ref.songId AS id, s.title AS title, s.artist AS artist, s.album AS album
        FROM playlist_song_cross_ref ref
        LEFT JOIN songs s ON s.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex ASC
    """)
    suspend fun getBackupRowsForPlaylist(playlistId: Long): List<BackupSongRow>

    @Query("DELETE FROM playlist_song_cross_ref")
    suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM playlists")
    suspend fun deleteAllPlaylists()
}

/**
 * Fila de exportación: el id portable de la canción más sus tags como *fallback* de resolución.
 * Los tags son null si la canción ya no está en la biblioteca local.
 */
data class BackupSongRow(
    val id: String,
    val title: String?,
    val artist: String?,
    val album: String?
)

/** Fila de [PlaylistDao.getPlaylistArtRowsFlow]: una canción de una playlist con su carátula. */
data class PlaylistArtRow(
    val playlistId: Long,
    val art: String?
)
