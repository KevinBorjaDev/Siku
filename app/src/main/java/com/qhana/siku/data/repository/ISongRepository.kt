package com.qhana.siku.data.repository

import androidx.paging.PagingData
import com.qhana.siku.data.local.DuplicatePair
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SortSpec
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
        sortOrder: SortSpec<SortOrder> = SortOrder.DEFAULT,
        sourceFilters: Set<SongSourceFilter> = emptySet(),
        approximateIds: List<String> = emptyList()
    ): Flow<PagingData<Song>>

    /**
     * Ids de canciones cuyo título, artista o álbum se PARECEN a [query] sin coincidir literalmente
     * (ver `FuzzyMatch`): lo que rescata a quien escribe `deram` buscando `Dream`.
     *
     * Se resuelve en memoria porque SQLite no sabe medir distancias entre palabras, y por eso el
     * llamador debe pedirlo **solo cuando la búsqueda normal se quedó sin resultados**: recorre el
     * texto de la biblioteca entera, que es barato de una vez y caro por tecla pulsada.
     *
     * Devuelve como mucho [APPROXIMATE_SEARCH_LIMIT] ids, ordenados de más a menos parecido.
     */
    suspend fun findApproximateSongIds(query: String): List<String>

    /**
     * Cuántas canciones coinciden LITERALMENTE con [query]. Responde a "¿hace falta el rescate por
     * aproximación?" sin traerse ninguna fila.
     */
    suspend fun countSongsMatching(query: String, sourceFilters: Set<SongSourceFilter>): Int

    /**
     * Lista completa (no paginada) con la misma búsqueda + orden + filtros de origen que
     * [getSongsPaging]. Para construir la cola idéntica a lo visible.
     */
    suspend fun getSongsSnapshot(
        query: String = "",
        sortOrder: SortSpec<SortOrder> = SortOrder.DEFAULT,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): List<Song>

    // Secciones de la pantalla de inicio (derivadas del historial v22). Reactivas: se
    // actualizan solas al contar una escucha o entrar canciones nuevas.
    /** Últimas reproducidas (historial no nulo, más reciente primero). */
    fun getRecentlyPlayed(limit: Int): Flow<List<Song>>
    /** Recién agregadas a la biblioteca (por dateAdded). */
    fun getRecentlyAdded(limit: Int): Flow<List<Song>>
    /** Recién agregadas desde [since], sin tope: la lista completa tras la sección del inicio. */
    fun getRecentlyAddedSince(since: Long): Flow<List<Song>>
    /** Canciones de un artista (sección generada "Porque escuchaste a X"). */
    fun getSongsByArtist(artist: String): Flow<List<Song>>
    /** "Vuelve a escucharlas": escuchadas antes de [before], más antiguas primero. */
    fun getRediscover(before: Long, limit: Int): Flow<List<Song>>
    /** "Aún no las escuchaste": nunca reproducidas, lo más nuevo primero. */
    fun getNeverPlayed(limit: Int): Flow<List<Song>>
    /** Top de géneros (≥ [minCount] canciones) para los chips de acciones rápidas del inicio. */
    fun getTopGenres(minCount: Int, limit: Int): Flow<List<com.qhana.siku.data.local.GenreSummary>>
    /**
     * Canciones de un género. [partialMatch] = ajuste del usuario "incluir géneros compuestos":
     * con él, "Rock" trae además "Rock/Metal" y "Hard Rock" (LIKE en vez de tag exacto).
     */
    suspend fun getSongsByGenre(genre: String, partialMatch: Boolean): List<Song>
    /** Descargadas sin género leído (objetivo del backfill una-vez). */
    /** Persiste el tag GENRE de una canción (pipeline de análisis + backfill). */
    suspend fun updateGenre(songId: String, genre: String?)
    /**
     * Artista más escuchado con al menos [minSongs] canciones (para "Porque escuchaste a X");
     * null si ninguno con historial califica.
     */
    fun getTopPlayedArtist(minSongs: Int): Flow<String?>
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

    // Visibilidad de todo lo que habla de ORIGEN: qué contiene la biblioteca de verdad.
    /** ¿Hay canciones de la fuente local? */
    fun hasLocalSongsFlow(): Flow<Boolean>
    /**
     * ¿Está la biblioteca partida entre lo que suena sin red y lo que la necesita? Ver
     * [SongDao.buildSourceSplitQuery]: es la puerta de los chips de origen y de las acciones de
     * origen del inicio, que aparecen y desaparecen JUNTAS por este mismo valor.
     */
    fun hasSourceSplitFlow(): Flow<Boolean>
    suspend fun upsertSongs(songs: List<Song>): AppResult<Int>
    suspend fun getSongsNeedingMetadataOrDownload(limit: Int = 50, offset: Int = 0): List<Song>

    /** Healing: re-encola descargadas sin metadata (ver [SongDao.requeueDownloadedSongsWithoutMetadata]). */
    suspend fun requeueDownloadedSongsWithoutMetadata(): Int
    suspend fun deleteAudioFileById(songId: String)
    suspend fun deleteSongs(idsToDelete: List<String>)

    /**
     * Ids que acaban de salir de la biblioteca, emitidos por [deleteSongs] — el único camino de
     * borrado de la app (ver convención 8: nunca un `DELETE` crudo).
     *
     * Existe porque una canción borrada puede seguir EN LA COLA del reproductor, y ahí no se
     * cura sola: al quitar una carpeta local los archivos no se tocan (son `content://`, siguen
     * en el dispositivo), así que el tema sigue sonando perfectamente aunque ya no esté en la
     * biblioteca. Que el consumidor sea un evento y no cada llamador es lo que hace que valga
     * para TODOS los caminos —quitar una carpeta, apagar el escaneo del dispositivo, la
     * reconciliación de un scan, desconectar la nube— sin tener que acordarse en cada uno.
     */
    val songsDeleted: Flow<List<String>>
    suspend fun countSongsNeedingWork(): Int

    // Tope de almacenamiento (caché LRU): bytes ocupados por audio descargado de la nube y
    // candidatos a desalojo ordenados de menos a más valiosos.
    suspend fun getTotalDownloadedBytes(): Long
    /** Los mismos bytes, observables (gestor de descargas: consumo frente al tope). */
    fun getTotalDownloadedBytesFlow(): Flow<Long>
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

    // Metadata ligera (tags leídos de la cabecera remota, sin descargar el audio).

    /** Canciones a las que les falta el número de pista (ver [SongDao.getSongsNeedingTrackInfo]). */
    suspend fun getSongsNeedingTrackInfo(localOnly: Boolean): List<Song>

    /** Escribe solo número de pista y año (ver [SongDao.updateTrackInfo]). */
    suspend fun updateTrackInfo(songId: String, trackNumber: Int, year: Int)

    /** Canciones de nube que siguen con el centinela de "sin tags". */
    suspend fun getSongsNeedingLightMetadata(): List<Song>

    /** Escribe los tags de cabecera SIN marcar la fila como analizada (ver [SongDao.updateLightMetadata]). */
    suspend fun updateLightMetadata(
        songId: String,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        trackNumber: Int,
        year: Int,
        durationMs: Long,
        albumArtist: String,
        artistCredits: String?
    )

    /** Canciones con los tags de artista sin leer (ver [SongDao.getSongsNeedingArtistTags]). */
    suspend fun getSongsNeedingArtistTags(localOnly: Boolean): List<Song>

    /** Escribe solo los tags de artista (ver [SongDao.updateArtistTags]). */
    suspend fun updateArtistTags(songId: String, albumArtist: String, artistCredits: String?)

    /** URI de la carátula ya conocida para ese álbum, o null si ninguna canción suya tiene. */
    suspend fun getAlbumArtUri(album: String, albumArtist: String): String?

    /** Aplica una carátula a todo el álbum sin pisar las canciones que ya tuvieran la suya. */
    suspend fun setAlbumArt(album: String, albumArtist: String, uri: String)
    suspend fun updateAlbumArtUri(songId: String, uri: String?)
    suspend fun getSongsWithLocalArt(): List<Song>

    /** Canciones sin carátula cuya resolución sigue pendiente, agrupadas por álbum. */
    suspend fun getSongsWithPendingArtwork(localOnly: Boolean): List<Song>

    /** Sella el intento de resolver carátula: solo tras una lectura que contestó. */
    suspend fun markArtworkAttempted(songIds: List<String>)

    /**
     * Sella el intento de leer TAGS en la metadata ligera: solo tras una cabecera que se leyó entera
     * y no traía texto. Gemelo de [markArtworkAttempted] (ver `SongEntity.lightTagsAttemptedAt`).
     */
    suspend fun markLightTagsAttempted(songIds: List<String>)

    /** Devuelve las canciones a la cola de pendientes (su portada se perdió). */
    suspend fun clearArtworkAttempted(songIds: List<String>)

    /** URIs de carátula en uso; lo que no esté aquí es basura que se puede barrer. */
    suspend fun getReferencedArtUris(): Set<String>
    suspend fun getAllSongs(): List<Song>
    suspend fun updateSongUrl(songId: String, newUrl: String)
    suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?)
    /**
     * Historial: registra una escucha contada. Escribe el agregado de la v22 (+1 `playCount`,
     * `lastPlayedAt = at`) Y el evento del log de la v28, en una sola transacción.
     *
     * [playedMs] es lo que sonó en esta pasada; se acota a la duración de la pista al guardarlo.
     * El criterio de qué cuenta como escucha vive en `MusicController.maybeRecordPlay`.
     */
    suspend fun recordPlay(songId: String, at: Long, playedMs: Long)
    suspend fun markSongAsCorrupted(songId: String)
    /** Camino de vuelta de [markSongAsCorrupted]; se llama tras una descarga exitosa. */
    suspend fun clearCorrupted(songId: String)
    suspend fun getCorruptedSongs(): List<Song>
    suspend fun deleteAll()
}