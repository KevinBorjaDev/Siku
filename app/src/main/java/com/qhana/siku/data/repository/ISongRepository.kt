package com.qhana.siku.data.repository

import androidx.paging.PagingData
import com.qhana.siku.data.local.DuplicatePair
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import kotlinx.coroutines.flow.Flow

import com.qhana.siku.data.model.AppResult

/**
 * Descarga fallida con su diagnóstico (bookkeeping v18 de la tabla songs): causa cruda,
 * clase de error ("TRANSIENT"/"PERMANENT"), intentos y cuándo se reintentará sola.
 */
data class FailedDownload(
    val song: Song,
    val error: String?,
    val errorKind: String?,
    val attempts: Int,
    val nextRetryAt: Long?
) {
    val isTransient: Boolean get() = errorKind == "TRANSIENT"
}

interface ISongRepository {
    fun getSongsPaging(
        query: String = "",
        sortOrder: SortOrder = SortOrder.TITLE_ASC,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): Flow<PagingData<Song>>

    /**
     * Lista completa (no paginada) con la misma búsqueda + orden + filtros de origen que
     * [getSongsPaging]. Para construir la cola idéntica a lo visible.
     */
    suspend fun getSongsSnapshot(
        query: String = "",
        sortOrder: SortOrder = SortOrder.TITLE_ASC,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): List<Song>

    // Secciones de la pantalla de inicio (derivadas del historial v22). Reactivas: se
    // actualizan solas al contar una escucha o entrar canciones nuevas.
    /** Últimas reproducidas (historial no nulo, más reciente primero). */
    fun getRecentlyPlayed(limit: Int): Flow<List<Song>>
    /** Más reproducidas (por playCount, > 0). */
    fun getMostPlayed(limit: Int): Flow<List<Song>>
    /** Recién agregadas a la biblioteca (por dateAdded). */
    fun getRecentlyAdded(limit: Int): Flow<List<Song>>
    /** Canciones de un artista (sección generada "Porque escuchaste a X"). */
    fun getSongsByArtist(artist: String): Flow<List<Song>>
    /** "Vuelve a escucharlas": escuchadas antes de [before], más antiguas primero. */
    fun getRediscover(before: Long, limit: Int): Flow<List<Song>>
    /** Top de géneros (≥ [minCount] canciones) para los chips de acciones rápidas del inicio. */
    fun getTopGenres(minCount: Int, limit: Int): Flow<List<com.qhana.siku.data.local.GenreSummary>>
    /** Canciones de un género (para reproducir en aleatorio desde el chip). */
    suspend fun getSongsByGenre(genre: String): List<Song>
    /** Descargadas sin género leído (objetivo del backfill una-vez). */
    suspend fun getDownloadedSongsWithoutGenre(limit: Int): List<Song>
    /** Persiste el tag GENRE de una canción (pipeline de análisis + backfill). */
    suspend fun updateGenre(songId: String, genre: String?)
    /**
     * Artista más escuchado con al menos [minSongs] canciones (para "Porque escuchaste a X");
     * null si ninguno con historial califica.
     */
    fun getTopPlayedArtist(minSongs: Int): Flow<String?>
    /** Nº de canciones con última escucha desde [since] (stat del saludo del inicio). */
    fun getPlayedSinceCount(since: Long): Flow<Int>
    /** Tamaño total de la biblioteca (stat de respaldo del saludo). */
    fun getLibrarySize(): Flow<Int>
    fun getSongByIdFlow(songId: String): Flow<Song?>
    suspend fun getSongById(songId: String): AppResult<Song>
    suspend fun getSongsByIds(ids: List<String>): List<Song>
    suspend fun getAllSongIds(): List<String>
    /** IDs de las canciones de una fuente concreta (reconciliación por fuente). */
    suspend fun getSongIdsBySourceType(sourceType: SourceType): List<String>
    /** IDs de todas las canciones con el mismo tag de álbum (mismo criterio que el browse). */
    suspend fun getSongIdsByAlbum(album: String): List<String>
    suspend fun getCachedSongs(): List<Song>
    /** Conteo reactivo; con [sourceFilters] cuenta solo lo que pasa los chips de origen. */
    fun getSongCountFlow(sourceFilters: Set<SongSourceFilter> = emptySet()): Flow<Int>

    // --- Duplicados entre fuentes (v23, relativePath normalizada) ---
    /** Pares (perdedora de [loserSource] → ganadora de la otra fuente) por relativePath. */
    suspend fun findCrossSourceDuplicates(loserSource: SourceType): List<DuplicatePair>
    /** Cuántas canciones existen a la vez en la fuente local y en otra (para el diálogo). */
    suspend fun countCrossSourceDuplicates(): Int
    /** Rutas relativas presentes en fuentes DISTINTAS a [sourceType] (skip en discover). */
    suspend fun getRelativePathsOfOtherSources(sourceType: SourceType): Set<String>
    /** Suma el historial de la perdedora a la ganadora (playCount + lastPlayedAt más reciente). */
    suspend fun mergePlayStats(loserId: String, winnerId: String)

    // Visibilidad de los chips de origen: qué familias de canciones EXISTEN en la biblioteca.
    /** ¿Hay canciones de la fuente local? */
    fun hasLocalSongsFlow(): Flow<Boolean>
    /** ¿Hay canciones de nube (descargadas o no)? */
    fun hasCloudSongsFlow(): Flow<Boolean>
    suspend fun upsertSongs(songs: List<Song>): AppResult<Int>
    suspend fun getSongsNeedingMetadataOrDownload(limit: Int = 50, offset: Int = 0): List<Song>

    /** Healing: re-encola descargadas sin metadata (ver [SongDao.requeueDownloadedSongsWithoutMetadata]). */
    suspend fun requeueDownloadedSongsWithoutMetadata(): Int
    suspend fun deleteAudioFileById(songId: String)
    suspend fun deleteSongs(idsToDelete: List<String>)
    suspend fun countSongsNeedingWork(): Int

    // Tope de almacenamiento (caché LRU): bytes ocupados por audio descargado de la nube y
    // candidatos a desalojo ordenados de menos a más valiosos.
    suspend fun getTotalDownloadedBytes(): Long
    /** Pares (id, size) de descargas de la nube ordenados LRU-first, excluyendo [excludeId]. */
    suspend fun getEvictionCandidates(excludeId: String): List<Pair<String, Long>>

    // Cola de descargas persistente (v18): bookkeeping de fallos en la tabla songs.
    suspend fun getDownloadAttempts(songId: String): Int
    suspend fun markDownloadFailed(songId: String, error: String, transient: Boolean, attempts: Int, nextRetryAt: Long)
    suspend fun clearDownloadError(songId: String)
    /** Limpia el estado de error de todas las fallidas y retorna sus IDs. */
    suspend fun resetDownloadErrors(): List<String>
    fun getFailedDownloadsFlow(): Flow<List<FailedDownload>>
    /** Menor nextRetryAt futuro entre canciones pendientes, o null si no hay backoff activo. */
    suspend fun getEarliestRetryAt(): Long?
    suspend fun updateSongMetadata(song: Song)
    suspend fun updateAlbumArtUri(songId: String, uri: String?)
    suspend fun getSongsWithLocalArt(): List<Song>
    suspend fun getAllSongs(): List<Song>
    suspend fun updateSongUrl(songId: String, newUrl: String)
    suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?)
    /** Historial (v22): registra una escucha contada (+1 playCount, lastPlayedAt = at). */
    suspend fun recordPlay(songId: String, at: Long)
    suspend fun markSongAsCorrupted(songId: String)
    /** Camino de vuelta de [markSongAsCorrupted]; se llama tras una descarga exitosa. */
    suspend fun clearCorrupted(songId: String)
    suspend fun getCorruptedSongs(): List<Song>
    suspend fun deleteAll()
}