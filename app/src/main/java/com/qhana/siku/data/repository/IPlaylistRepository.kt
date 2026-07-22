package com.qhana.siku.data.repository

import androidx.paging.PagingData
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SortOrder
import kotlinx.coroutines.flow.Flow

/** Metadatos de portada de una playlist para la pestaña Listas: conteo + hasta 4 carátulas. */
data class PlaylistCoverMeta(
    val songCount: Int,
    val arts: List<String>
)

interface IPlaylistRepository {

    // --- Playlists "de usuario" (excluye la de favoritos por defecto) ---
    fun getUserPlaylists(): Flow<List<Playlist>>

    /** Conteo y carátulas (máx. 4 distintas) por playlistId, reactivo y en una sola query. */
    fun getPlaylistsCoverMeta(): Flow<Map<Long, PlaylistCoverMeta>>

    suspend fun createPlaylist(name: String): Long

    /** Borra la playlist y sus cross-refs de la BD (hard-delete). */
    suspend fun deletePlaylist(playlistId: Long)

    /**
     * Fusión de duplicados: re-apunta TODAS las refs de playlists (favoritos incluidos —
     * son una playlist) de la canción perdedora a la ganadora, sin duplicar en listas que
     * ya la tuvieran, y limpia las refs restantes de la perdedora.
     */
    suspend fun repointSongRefs(loserId: String, winnerId: String)

    suspend fun renamePlaylist(playlistId: Long, name: String)

    suspend fun addSongToPlaylist(playlistId: Long, songId: String)

    /**
     * Alta en lote al final de la lista. Devuelve cuántas canciones se añadieron REALMENTE
     * (las que ya estaban se ignoran), para poder informar del resultado.
     */
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<String>): Int

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)
    fun getSongsForPlaylist(playlistId: Long): Flow<List<Song>>
    suspend fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>)

    // --- API de favoritos: opera sobre la playlist con FAVORITES_PLAYLIST_UUID ---
    /**
     * Garantiza que existe la playlist de favoritos (la crea con UUID fijo si falta).
     * Idempotente — seguro llamarla en cada arranque.
     */
    suspend fun ensureFavoritesPlaylist()

    suspend fun toggleFavorite(songId: String)

    /** Alta en lote a favoritos (mismo contrato que [addSongsToPlaylist]). */
    suspend fun addSongsToFavorites(songIds: List<String>): Int

    fun getFavoritesIds(): Flow<Set<String>>
    fun getFavoritesSongs(): Flow<List<Song>>
    fun getFavoritesPaging(query: String = "", sortOrder: SortOrder = SortOrder.TITLE_ASC): Flow<PagingData<Song>>

    /** Lista completa (no paginada) de favoritos con la misma búsqueda + orden que [getFavoritesPaging]. Para construir la cola. */
    suspend fun getFavoritesSnapshot(query: String = ""): List<Song>

    suspend fun deleteAllPlaylists()
}
