package com.qhana.siku.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SourceType
import kotlinx.coroutines.flow.Flow

/**
 * DAO para canciones. En v13 los IDs pasaron a `TEXT` (era `INTEGER`): ahora son
 * el `remoteId` de OneDrive directo para canciones cloud, o `"local_<mediaStoreId>"`
 * para las locales.
 *
 * Las queries FTS4 fueron eliminadas — búsqueda ahora es LIKE (más robusto tras
 * migraciones, ver memoria del proyecto).
 */
/** Par (perdedora → ganadora) de la resolución de duplicados entre fuentes. */
data class DuplicatePair(val loserId: String, val winnerId: String)

@Dao
interface SongDao {

    // ==================== PAGING ====================

    @RawQuery(observedEntities = [SongEntity::class])
    fun getSongsPagingRaw(query: SupportSQLiteQuery): PagingSource<Int, SongEntity>

    /**
     * Versión no paginada del mismo query dinámico (mismo builder [buildPagingQuery]):
     * la lista COMPLETA con la búsqueda + orden actuales. Se usa para construir la cola
     * de reproducción idéntica a lo que se ve en pantalla (no solo las páginas cargadas).
     */
    @RawQuery
    suspend fun getSongsList(query: SupportSQLiteQuery): List<SongEntity>

    /** Conteo reactivo con query dinámica (chip "N canciones" con filtros de origen). */
    @RawQuery(observedEntities = [SongEntity::class])
    fun getSongCountRawFlow(query: SupportSQLiteQuery): Flow<Int>

    // ==================== DUPLICADOS ENTRE FUENTES (v23) ====================

    /** Backfill de relativePath en filas existentes (la nube pre-v23 no la tenía). */
    @Query("UPDATE songs SET relativePath = :relativePath WHERE id = :songId AND relativePath IS NULL")
    suspend fun backfillRelativePath(songId: String, relativePath: String)

    /**
     * Pares (perdedora, ganadora) de la política de duplicados: filas de [loserSource] cuya
     * relativePath (ya normalizada) existe también en OTRA fuente. La ganadora es la de la
     * fuente contraria; si hubiera más de una, LIMITa el join con MIN(id) determinista.
     */
    @Query(
        """
        SELECT loser.id AS loserId,
               (SELECT MIN(w.id) FROM songs w
                WHERE w.relativePath = loser.relativePath AND w.sourceType != loser.sourceType) AS winnerId
        FROM songs loser
        WHERE loser.sourceType = :loserSource AND loser.relativePath IS NOT NULL
          AND EXISTS(SELECT 1 FROM songs w2
                     WHERE w2.relativePath = loser.relativePath AND w2.sourceType != loser.sourceType)
        """
    )
    suspend fun findCrossSourceDuplicates(loserSource: String): List<DuplicatePair>

    /** Conteo de duplicados entre fuentes en CUALQUIER dirección (para el diálogo de decisión). */
    @Query(
        """
        SELECT COUNT(*) FROM songs a
        WHERE a.sourceType = 'LOCAL' AND a.relativePath IS NOT NULL
          AND EXISTS(SELECT 1 FROM songs b
                     WHERE b.relativePath = a.relativePath AND b.sourceType != a.sourceType)
        """
    )
    suspend fun countCrossSourceDuplicates(): Int

    /** Rutas relativas (normalizadas) presentes en fuentes DISTINTAS a [sourceType]. */
    @Query("SELECT DISTINCT relativePath FROM songs WHERE sourceType != :sourceType AND relativePath IS NOT NULL")
    suspend fun getRelativePathsOfOtherSources(sourceType: String): List<String>

    /**
     * Fusiona el historial de la perdedora en la ganadora antes de retirarla: suma
     * playCount y conserva el lastPlayedAt más reciente (MAX de agregado ignora NULLs y
     * devuelve NULL si ambos lo son — no inventa una escucha en el epoch).
     */
    @Query(
        """
        UPDATE songs SET
          playCount = playCount + (SELECT playCount FROM songs WHERE id = :loserId),
          lastPlayedAt = (SELECT MAX(lastPlayedAt) FROM songs WHERE id IN (:winnerId, :loserId))
        WHERE id = :winnerId
        """
    )
    suspend fun mergePlayStats(loserId: String, winnerId: String)

    // Visibilidad de los chips de origen: derivada del CONTENIDO real de la biblioteca
    // (no de qué fuentes están configuradas — una fuente conectada pero vacía no debe
    // mostrar filtros inútiles).
    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE sourceType = :sourceType)")
    fun hasSongsOfSourceFlow(sourceType: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE sourceType != :sourceType)")
    fun hasSongsNotOfSourceFlow(sourceType: String): Flow<Boolean>

    companion object {
        /**
         * Construye una query dinámica para paging con búsqueda LIKE.
         *
         * @param searchQuery Texto de búsqueda (vacío para todas)
         * @param sortColumn "title" o "dateAdded"
         * @param sortAsc true para ASC, false para DESC
         * @param sourceFilters Chips de origen de la pestaña Todas (vacío = sin filtro)
         */
        fun buildPagingQuery(
            searchQuery: String,
            sortColumn: String,
            sortAsc: Boolean,
            sourceFilters: Set<SongSourceFilter> = emptySet()
        ): SimpleSQLiteQuery {
            val sb = StringBuilder()
            val args = mutableListOf<Any>()

            sb.append("SELECT * FROM songs WHERE 1=1")

            if (searchQuery.isNotBlank()) {
                sb.append(" AND (title LIKE ? OR artist LIKE ? OR album LIKE ?)")
                val likeQuery = "%$searchQuery%"
                args.add(likeQuery)
                args.add(likeQuery)
                args.add(likeQuery)
            }

            appendSourceFilters(sb, args, sourceFilters)

            // Whitelist de columnas de orden
            val allowedColumns = setOf("title", "dateAdded", "artist", "album", "playCount", "lastPlayedAt")
            val safeSortColumn = if (allowedColumns.contains(sortColumn)) sortColumn else "title"
            val collate = if (safeSortColumn == "title") " COLLATE NOCASE" else ""
            val order = if (sortAsc) "ASC" else "DESC"
            // Desempate estable por título: sin él, playCount/lastPlayedAt (llenos de empates
            // en 0/NULL — en SQLite los NULL son "menores" y en DESC van al final) dejan el
            // resto de la lista en orden arbitrario.
            val tieBreak = if (safeSortColumn == "title") "" else ", title COLLATE NOCASE ASC"
            sb.append(" ORDER BY $safeSortColumn$collate $order$tieBreak")

            return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
        }

        /** Conteo con los mismos filtros de origen (para el chip "N canciones" del tab). */
        fun buildCountQuery(sourceFilters: Set<SongSourceFilter>): SimpleSQLiteQuery {
            val sb = StringBuilder("SELECT COUNT(*) FROM songs WHERE 1=1")
            val args = mutableListOf<Any>()
            appendSourceFilters(sb, args, sourceFilters)
            return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
        }

        /**
         * Álbumes agrupados (mismo SELECT/ORDER que la versión estática de [getAlbumsFlow])
         * pero con filtro de origen OPCIONAL: el WHERE va ANTES del GROUP BY, así que un
         * álbum aparece si tiene AL MENOS UNA canción que cumple el filtro, y sus agregados
         * (songCount, carátula) reflejan solo esas canciones — misma semántica que la pestaña
         * Todas. [sort] = nombre de un [com.qhana.siku.data.model.AlbumSortOrder].
         */
        fun buildAlbumsQuery(sort: String, sourceFilters: Set<SongSourceFilter>): SimpleSQLiteQuery {
            val sb = StringBuilder(
                "SELECT album AS name, MIN(artist) AS artist, COUNT(*) AS songCount, " +
                    "MAX(albumArtUriString) AS albumArtUri FROM songs WHERE 1=1"
            )
            val args = mutableListOf<Any>()
            appendSourceFilters(sb, args, sourceFilters)
            sb.append(" GROUP BY album ORDER BY ")
            sb.append("CASE WHEN ? = 'ARTIST' THEN MIN(artist) END COLLATE NOCASE, ")
            args.add(sort)
            sb.append("CASE WHEN ? = 'RECENTLY_ADDED' THEN -MAX(dateAdded) END, ")
            args.add(sort)
            sb.append("album COLLATE NOCASE ASC")
            return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
        }

        /**
         * Artistas agrupados (mismo SELECT/JOIN/ORDER que [ArtistDao.getArtistsFlow]) con
         * filtro de origen OPCIONAL. Como en [buildAlbumsQuery], el WHERE precede al GROUP BY
         * (semántica "≥1 canción que cumple"). Los nombres de columna del filtro (`sourceType`,
         * `uriString`) son inequívocos: la tabla `artists` no los tiene.
         * [sort] = nombre de un [com.qhana.siku.data.model.ArtistSortOrder].
         */
        fun buildArtistsQuery(sort: String, sourceFilters: Set<SongSourceFilter>): SimpleSQLiteQuery {
            val sb = StringBuilder(
                "SELECT s.artist AS name, COUNT(*) AS songCount, COUNT(DISTINCT s.album) AS albumCount, " +
                    "a.imageUrl AS imageUrl, a.thumbUrl AS thumbUrl " +
                    "FROM songs s LEFT JOIN artists a ON a.name = s.artist WHERE 1=1"
            )
            val args = mutableListOf<Any>()
            appendSourceFilters(sb, args, sourceFilters)
            sb.append(" GROUP BY s.artist ORDER BY ")
            sb.append("CASE WHEN ? = 'SONG_COUNT' THEN -COUNT(*) END, ")
            args.add(sort)
            sb.append("CASE WHEN ? = 'RECENTLY_ADDED' THEN -MAX(s.dateAdded) END, ")
            args.add(sort)
            sb.append("s.artist COLLATE NOCASE ASC")
            return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
        }

        /**
         * Chips combinables por UNIÓN (LOCAL + DOWNLOADED = todo lo offline). DOWNLOADED
         * es nube con audio en disco: el criterio va por `file://` (solo las descargas de
         * nube producen file://; la fuente LOCAL vía SAF usa content://), excluyendo
         * sourceType LOCAL para que una selección de solo-DOWNLOADED no arrastre locales.
         * STREAMING es el complemento: nube sin archivo en disco.
         */
        private fun appendSourceFilters(
            sb: StringBuilder,
            args: MutableList<Any>,
            sourceFilters: Set<SongSourceFilter>
        ) {
            if (sourceFilters.isEmpty()) return
            val clauses = mutableListOf<String>()
            if (SongSourceFilter.LOCAL in sourceFilters) {
                clauses.add("sourceType = ?")
                args.add(SourceType.LOCAL.name)
            }
            if (SongSourceFilter.DOWNLOADED in sourceFilters) {
                clauses.add("(sourceType != ? AND uriString LIKE 'file://%')")
                args.add(SourceType.LOCAL.name)
            }
            if (SongSourceFilter.STREAMING in sourceFilters) {
                clauses.add("(sourceType != ? AND uriString NOT LIKE 'file://%')")
                args.add(SourceType.LOCAL.name)
            }
            sb.append(" AND (${clauses.joinToString(" OR ")})")
        }
    }

    // ==================== CRUD DE SONGS ====================

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdFlow(songId: String): Flow<SongEntity?>

    @Query("SELECT id FROM songs")
    suspend fun getAllSongIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    /**
     * Fallback de resolución al restaurar un backup: la misma canción puede haber cambiado de id
     * (se re-subió a OneDrive, o cambió de ruta en la carpeta local), pero conserva sus tags.
     */
    @Query("SELECT id FROM songs WHERE title = :title COLLATE NOCASE AND artist = :artist COLLATE NOCASE LIMIT 1")
    suspend fun findIdByTitleAndArtist(title: String, artist: String): String?

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCountFlow(): Flow<Int>

    /**
     * Historial de reproducción (v22): una escucha contada = +1 y timestamp. Único punto
     * de escritura de estas columnas; el criterio de "escucha" vive en MusicController.
     */
    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :songId")
    suspend fun incrementPlayStats(songId: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<String>)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    // ==================== COLORS ====================

    @Query("SELECT colorPrimary, colorSecondary FROM songs WHERE id = :songId")
    suspend fun getColorsById(songId: String): ColorPair?

    @Query("UPDATE songs SET colorPrimary = NULL, colorSecondary = NULL")
    suspend fun resetColors()

    @Query("UPDATE songs SET colorPrimary = :primary, colorSecondary = :secondary WHERE id = :songId")
    suspend fun updateColors(songId: String, primary: Int?, secondary: Int?)

    @Query("SELECT * FROM songs WHERE colorPrimary IS NULL ORDER BY title COLLATE NOCASE ASC LIMIT 500")
    suspend fun getSongsWithoutColorsDirectly(): List<SongEntity>

    @Transaction
    suspend fun updateColorsBatch(colors: List<Triple<String, Int, Int>>) {
        colors.forEach { (songId, primary, secondary) ->
            updateColors(songId, primary, secondary)
        }
    }

    @Query("UPDATE songs SET lyrics = :lyrics, lyricsAttemptedAt = :attemptedAt WHERE id = :songId")
    suspend fun updateLyrics(songId: String, lyrics: String, attemptedAt: Long)

    @Query("UPDATE songs SET lyricsAttemptedAt = :attemptedAt WHERE id = :songId")
    suspend fun markLyricsNotFound(songId: String, attemptedAt: Long)

    @Query("UPDATE songs SET uriString = :newPath WHERE id = :songId")
    suspend fun updateSongPath(songId: String, newPath: String)

    // ==================== METADATA SYNC ====================

    // Fase 3: las canciones LOCAL nunca se descargan (ya viven en el dispositivo) y su
    // metadata se extrae durante el escaneo de la carpeta → se excluyen de la cola de trabajo.
    @Query("""
        SELECT * FROM songs WHERE
            sourceType != 'LOCAL' AND
            (uriString NOT LIKE 'file://%' OR needsMetadata = 1) AND
            (nextRetryAt IS NULL OR nextRetryAt <= :now)
        ORDER BY title COLLATE NOCASE ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSongsNeedingWork(limit: Int, offset: Int, now: Long): List<SongEntity>

    @Query("""
        SELECT COUNT(*) FROM songs WHERE
            sourceType != 'LOCAL' AND
            (uriString NOT LIKE 'file://%' OR needsMetadata = 1) AND
            (nextRetryAt IS NULL OR nextRetryAt <= :now)
    """)
    suspend fun countSongsNeedingWork(now: Long): Int

    /**
     * Healing: re-encola para análisis las canciones DESCARGADAS cuyo metadata nunca se
     * extrajo (duration=0 = el análisis falló y aun así se marcó needsMetadata=0). El
     * pipeline las repara en local sin re-descargar (los bytes ya están). Idempotente y
     * barato; corre al inicio de cada sync.
     */
    @Query("""
        UPDATE songs SET needsMetadata = 1
        WHERE uriString LIKE 'file://%' AND duration = 0 AND needsMetadata = 0 AND isCorrupted = 0
    """)
    suspend fun requeueDownloadedSongsWithoutMetadata(): Int

    /** IDs de las canciones de una fuente concreta (reconciliación del scan local). */
    @Query("SELECT id FROM songs WHERE sourceType = :sourceType")
    suspend fun getSongIdsBySourceType(sourceType: String): List<String>

    // ==================== COLA DE DESCARGAS PERSISTENTE (v18) ====================

    @Query("SELECT downloadAttempts FROM songs WHERE id = :songId")
    suspend fun getDownloadAttempts(songId: String): Int?

    @Query("""
        UPDATE songs SET
            downloadAttempts = :attempts,
            lastDownloadError = :error,
            downloadErrorKind = :kind,
            nextRetryAt = :nextRetryAt
        WHERE id = :songId
    """)
    suspend fun markDownloadFailed(songId: String, attempts: Int, error: String, kind: String, nextRetryAt: Long)

    @Query("UPDATE songs SET downloadAttempts = 0, lastDownloadError = NULL, downloadErrorKind = NULL, nextRetryAt = NULL WHERE id = :songId")
    suspend fun clearDownloadError(songId: String)

    @Query("SELECT id FROM songs WHERE lastDownloadError IS NOT NULL")
    suspend fun getFailedDownloadIds(): List<String>

    @Query("UPDATE songs SET downloadAttempts = 0, lastDownloadError = NULL, downloadErrorKind = NULL, nextRetryAt = NULL WHERE lastDownloadError IS NOT NULL")
    suspend fun resetDownloadErrors()

    @Query("""
        SELECT * FROM songs WHERE
            sourceType != 'LOCAL' AND
            lastDownloadError IS NOT NULL AND uriString NOT LIKE 'file://%'
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun getFailedDownloadsFlow(): kotlinx.coroutines.flow.Flow<List<SongEntity>>

    @Query("""
        SELECT MIN(nextRetryAt) FROM songs WHERE
            sourceType != 'LOCAL' AND
            (uriString NOT LIKE 'file://%' OR needsMetadata = 1) AND
            nextRetryAt > :now
    """)
    suspend fun getEarliestRetryAt(now: Long): Long?

    // `lyricsAttemptedAt = NULL`: aquí llegan los TAGS reales del archivo — un NotFound de
    // letras sellado antes (con el nombre de archivo como título) quedó cacheado bajo una
    // identidad que ya no existe, así que se rehabilita la búsqueda automática.
    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album, duration = :duration, albumArtUriString = :albumArtUri, needsMetadata = 0, lyricsAttemptedAt = NULL WHERE id = :songId")
    suspend fun updateSongMetadata(songId: String, title: String, artist: String, album: String, duration: Long, albumArtUri: String?)

    /**
     * Persiste los tags ReplayGain leídos del archivo local tras la descarga.
     */
    @Query("UPDATE songs SET trackGainDb = :trackGainDb, trackPeak = :trackPeak, albumGainDb = :albumGainDb, albumPeak = :albumPeak WHERE id = :songId")
    suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?)

    // ==================== GÉNERO (v24: chips de acciones rápidas del inicio) ====================

    /** Persiste el tag GENRE leído del archivo. Escrito por el pipeline de análisis y el backfill. */
    @Query("UPDATE songs SET genre = :genre WHERE id = :songId")
    suspend fun updateGenre(songId: String, genre: String?)

    /**
     * Top de géneros por cantidad de canciones (solo los que llegan a [minCount]). Reactivo:
     * los chips se actualizan solos a medida que el backfill/análisis rellena la columna.
     */
    @Query(
        """
        SELECT genre AS name, COUNT(*) AS songCount
        FROM songs WHERE genre IS NOT NULL AND genre != ''
        GROUP BY genre HAVING COUNT(*) >= :minCount
        ORDER BY songCount DESC, genre COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    fun getTopGenresFlow(minCount: Int, limit: Int): Flow<List<GenreSummary>>

    /** Canciones de un género (para reproducir en aleatorio desde el chip). */
    @Query("SELECT * FROM songs WHERE genre = :genre ORDER BY title COLLATE NOCASE ASC")
    suspend fun getSongsByGenre(genre: String): List<SongEntity>

    /**
     * Descargadas (archivo local) SIN género leído todavía: objetivo del backfill una-vez que
     * re-lee el tag de los archivos ya en disco (sin re-descargar).
     */
    @Query("SELECT * FROM songs WHERE genre IS NULL AND uriString LIKE 'file://%' LIMIT :limit")
    suspend fun getDownloadedSongsWithoutGenre(limit: Int): List<SongEntity>

    /**
     * Devuelve canciones cuyo URI de carátula apunta a un archivo local (`file://`).
     * Usado por el healing para detectar carátulas huérfanas tras pérdida de archivos
     * (p. ej. cuando el usuario limpia caché desde Ajustes de Android antes de que
     * los covers se movieran a filesDir).
     */
    @Query("SELECT * FROM songs WHERE albumArtUriString LIKE 'file://%'")
    suspend fun getSongsWithLocalArt(): List<SongEntity>

    /**
     * Actualiza únicamente el URI de carátula sin tocar el resto de metadata.
     */
    @Query("UPDATE songs SET albumArtUriString = :uri WHERE id = :songId")
    suspend fun updateAlbumArtUri(songId: String, uri: String?)

    /**
     * Marca una canción como modificada en el origen (OneDrive): vacía la URI local
     * para forzar re-descarga, activa needsMetadata para que finalizeDownload re-extraiga
     * los nuevos tags ID3, y actualiza el tamaño esperado.
     */
    @Query("UPDATE songs SET uriString = '', needsMetadata = 1, size = :newSize WHERE id = :songId")
    suspend fun markSongAsModifiedForRedownload(songId: String, newSize: Long)

    // ==================== TOPE DE ALMACENAMIENTO (caché LRU) ====================

    /**
     * Bytes ocupados por el audio DESCARGADO de la nube (`file://`, excluye LOCAL, que ya
     * vive en el dispositivo y no cuenta contra el tope). Base para el gate del tope.
     */
    @Query("SELECT COALESCE(SUM(size), 0) FROM songs WHERE uriString LIKE 'file://%' AND sourceType != 'LOCAL'")
    suspend fun getTotalDownloadedBytes(): Long

    /**
     * Candidatos a desalojo (audio descargado de la nube), ordenados de MENOS valioso a más:
     * primero las nunca reproducidas, luego por escucha más antigua y menor conteo. Excluye
     * [excludeId] (la canción que se está por descargar/reproducir).
     */
    @Query("""
        SELECT id, size FROM songs
        WHERE uriString LIKE 'file://%' AND sourceType != 'LOCAL' AND id != :excludeId
        ORDER BY (lastPlayedAt IS NULL) DESC, lastPlayedAt ASC, playCount ASC
    """)
    suspend fun getEvictionCandidates(excludeId: String): List<SongIdSize>

    @Query("UPDATE songs SET isCorrupted = 1 WHERE id = :songId")
    suspend fun markSongAsCorrupted(songId: String)

    /**
     * Camino de VUELTA de [markSongAsCorrupted]. Sin él, una canción marcada quedaba corrupta
     * PARA SIEMPRE: la reparación automática de SyncViewModel la redescargaba en cada arranque
     * (bytes nuevos y perfectos) pero el flag nunca se limpiaba → bucle infinito de redescargas.
     * Se llama al finalizar CUALQUIER descarga exitosa (MusicDownloader.finalizeDownload).
     */
    @Query("UPDATE songs SET isCorrupted = 0 WHERE id = :songId")
    suspend fun clearCorrupted(songId: String)

    @Query("SELECT * FROM songs WHERE isCorrupted = 1 LIMIT 100")
    suspend fun getCorruptedSongs(): List<SongEntity>

    // ==================== BROWSE: ÁLBUMES / ARTISTAS (v19) ====================
    // Álbumes agrupados SOLO por nombre (decisión de producto: los feats no parten el
    // álbum). Artista representativo = MIN(artist); carátula = MAX(albumArtUriString)
    // (los agregados de SQLite ignoran NULL → cualquier carátula no-nula disponible).

    /**
     * Álbumes con orden + filtro de origen dinámicos ([buildAlbumsQuery]). RawQuery para
     * poder inyectar el WHERE de origen antes del GROUP BY; `observedEntities` mantiene la
     * reactividad de Room sobre `songs`.
     */
    @RawQuery(observedEntities = [SongEntity::class])
    fun getAlbumsFlow(query: SupportSQLiteQuery): Flow<List<AlbumSummary>>

    @Query(
        """
        SELECT album AS name, MIN(artist) AS artist, COUNT(*) AS songCount,
               MAX(albumArtUriString) AS albumArtUri
        FROM songs WHERE artist = :artist GROUP BY album ORDER BY album COLLATE NOCASE ASC
        """
    )
    fun getAlbumsByArtistFlow(artist: String): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album COLLATE NOCASE ASC, trackNumber ASC, title COLLATE NOCASE ASC")
    fun getSongsByArtistFlow(artist: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY trackNumber ASC, title COLLATE NOCASE ASC")
    fun getSongsByAlbumFlow(album: String): Flow<List<SongEntity>>

    @Query("SELECT id FROM songs WHERE album = :album")
    suspend fun getSongIdsByAlbum(album: String): List<String>

    // ==================== HOME (v22: derivadas del historial de reproducción) ====================
    // Todas reactivas (Flow) y con LIMIT: la home se actualiza sola cuando incrementPlayStats
    // toca playCount/lastPlayedAt, sin recargar la biblioteca entera.

    /** Últimas reproducidas: las que tienen historial, más reciente primero. */
    @Query("SELECT * FROM songs WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun getRecentlyPlayedFlow(limit: Int): Flow<List<SongEntity>>

    /** Más reproducidas: por conteo, desempatando por escucha más reciente. */
    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun getMostPlayedFlow(limit: Int): Flow<List<SongEntity>>

    /** Recién agregadas a la biblioteca (mismo criterio de fecha que el sort DATE_ADDED). */
    @Query("SELECT * FROM songs ORDER BY dateAdded DESC LIMIT :limit")
    fun getRecentlyAddedFlow(limit: Int): Flow<List<SongEntity>>

    /**
     * Álbumes del momento: agrupados por nombre (igual que el browse) y ordenados por el total
     * de reproducciones de sus canciones. Solo álbumes con historial (SUM(playCount) > 0).
     */
    @Query(
        """
        SELECT album AS name, MIN(artist) AS artist, COUNT(*) AS songCount,
               MAX(albumArtUriString) AS albumArtUri
        FROM songs GROUP BY album HAVING SUM(playCount) > 0
        ORDER BY SUM(playCount) DESC LIMIT :limit
        """
    )
    fun getTopAlbumsFlow(limit: Int): Flow<List<AlbumSummary>>

    /** Nº de canciones cuya ÚLTIMA escucha cae dentro de [since..now] (stat del saludo del inicio). */
    @Query("SELECT COUNT(*) FROM songs WHERE lastPlayedAt IS NOT NULL AND lastPlayedAt >= :since")
    fun getPlayedSinceCountFlow(since: Long): Flow<Int>

    /**
     * Artista más escuchado (por total de reproducciones de sus canciones), para la sección
     * generada "Porque escuchaste a X". Excluye el sentinel de artista desconocido y blancos, y
     * exige al menos [minSongs] canciones en la biblioteca (una sola canción no justifica un
     * carrusel "más de este artista"): si el #1 no califica, cae al siguiente que sí.
     */
    @Query(
        """
        SELECT artist FROM songs
        WHERE playCount > 0 AND TRIM(artist) != '' AND artist != :unknownArtist
        GROUP BY artist HAVING COUNT(*) >= :minSongs
        ORDER BY SUM(playCount) DESC, MAX(lastPlayedAt) DESC LIMIT 1
        """
    )
    fun getTopPlayedArtistFlow(unknownArtist: String, minSongs: Int): Flow<String?>

    /**
     * "Vuelve a escucharlas": canciones ya escuchadas alguna vez pero cuya última escucha es
     * anterior a [before] (no tocadas en un buen rato), de la más antigua a la más nueva.
     */
    @Query("SELECT * FROM songs WHERE playCount > 0 AND lastPlayedAt IS NOT NULL AND lastPlayedAt < :before ORDER BY lastPlayedAt ASC LIMIT :limit")
    fun getRediscoverFlow(before: Long, limit: Int): Flow<List<SongEntity>>
}

/**
 * Proyección id+tamaño para el gate del tope de almacenamiento (LRU): evita cargar la
 * SongEntity entera al calcular cuánto liberar por desalojo.
 */
data class SongIdSize(
    val id: String,
    val size: Long
)

/**
 * Data class para proyección de colores.
 */
data class ColorPair(
    val colorPrimary: Int?,
    val colorSecondary: Int?
)

/**
 * Proyección para las vistas de álbumes (pestaña y grid del detalle de artista).
 */
data class AlbumSummary(
    val name: String,
    val artist: String,
    val songCount: Int,
    val albumArtUri: String?
)

/** Proyección para los chips de género del inicio (nombre + cuántas canciones). */
data class GenreSummary(
    val name: String,
    val songCount: Int
)
