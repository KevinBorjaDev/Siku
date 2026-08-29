package com.qhana.siku.data.repository

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.DuplicatePair
import com.qhana.siku.data.local.MusicDatabase
import com.qhana.siku.data.local.PlayEventDao
import com.qhana.siku.data.local.PlayEventEntity
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.util.FuzzyMatch
import com.qhana.siku.data.local.SongEntity
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.runCatchingAsAppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val playEventDao: PlayEventDao,
    private val database: MusicDatabase
) : ISongRepository {

    // Caché del snapshot de cola (toda la biblioteca en el orden/búsqueda actual). Evita
    // re-leer y mapear la tabla entera en cada tap de reproducción. Se invalida SOLA ante
    // cualquier escritura en `songs` (sync, descargas, metadata, colores...) vía el
    // InvalidationTracker de Room, así que nunca sirve una lista obsoleta.
    private val snapshotLock = Any()
    @Volatile private var cachedSnapshotKey: Triple<String, SortOrder, Set<SongSourceFilter>>? = null
    @Volatile private var cachedSnapshot: List<Song>? = null

    /**
     * Sube con cada invalidación de `songs`. Sirve para saber si la tabla cambió MIENTRAS corría
     * una query: limpiar el caché no basta cuando el que va a escribirlo ya leyó datos viejos.
     */
    @Volatile private var invalidationGeneration = 0L

    // Aviso de borrado (ver [ISongRepository.songsDeleted]). `tryEmit` para no suspender dentro
    // de deleteSongs; el búfer cubre de sobra los borrados en ráfaga de una reconciliación.
    private val _songsDeleted = MutableSharedFlow<List<String>>(extraBufferCapacity = 16)
    override val songsDeleted: Flow<List<String>> = _songsDeleted.asSharedFlow()

    init {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("songs") {
            override fun onInvalidated(tables: Set<String>) {
                synchronized(snapshotLock) {
                    invalidationGeneration++
                    cachedSnapshotKey = null
                    cachedSnapshot = null
                }
            }
        })
    }

    override fun getSongsPaging(
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter>,
        approximateIds: List<String>
    ): Flow<PagingData<Song>> {
        val (sortColumn, sortAsc) = sortColumnFor(sortOrder)

        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = true),
            pagingSourceFactory = {
                val sqlQuery =
                    SongDao.buildPagingQuery(query, sortColumn, sortAsc, sourceFilters, approximateIds)
                songDao.getSongsPagingRaw(sqlQuery)
            }
        ).flow.map { pagingData -> pagingData.map { it.toSong() } }
    }

    override suspend fun countSongsMatching(
        query: String,
        sourceFilters: Set<SongSourceFilter>
    ): Int = withContext(Dispatchers.IO) {
        songDao.getSongCountRaw(SongDao.buildSearchCountQuery(query, sourceFilters))
    }

    override suspend fun findApproximateSongIds(query: String): List<String> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) return@withContext emptyList()
            // El texto se lee en IO y la comparación corre en Default: son miles de distancias de
            // edición, o sea CPU, y el dispatcher de IO está para esperas.
            val rows = withContext(Dispatchers.IO) { songDao.getSearchableText() }
            rows.asSequence()
                .map { row ->
                    // El mejor de los tres campos: da igual si la errata fue en el título o en el
                    // artista, y el que mejor casa es el que decide la posición de la canción.
                    val best = minOf(
                        FuzzyMatch.score(row.title, query),
                        FuzzyMatch.score(row.artist, query),
                        FuzzyMatch.score(row.album, query)
                    )
                    row.id to best
                }
                .filter { it.second != FuzzyMatch.NO_MATCH }
                .sortedBy { it.second }
                .take(APPROXIMATE_SEARCH_LIMIT)
                .map { it.first }
                .toList()
        }

    override suspend fun getSongsSnapshot(
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter>
    ): List<Song> =
        withContext(Dispatchers.IO) {
            val key = Triple(query, sortOrder, sourceFilters)
            val generationAtRead = synchronized(snapshotLock) {
                if (cachedSnapshotKey == key) cachedSnapshot?.let { return@withContext it }
                invalidationGeneration
            }
            val (sortColumn, sortAsc) = sortColumnFor(sortOrder)
            val result = songDao.getSongsList(SongDao.buildPagingQuery(query, sortColumn, sortAsc, sourceFilters)).map { it.toSong() }
            synchronized(snapshotLock) {
                // Solo se cachea si nadie tocó `songs` mientras corría la query. Si alguien lo
                // hizo, el observer ya limpió el caché pero esta lectura es ANTERIOR a ese cambio,
                // y guardarla lo resucitaría: la siguiente llamada serviría una lista con canciones
                // ya borradas (y con ellas se construyen colas de reproducción). Este resultado se
                // devuelve igual —el caller lo pidió y ya no hay nada mejor que darle—, pero no se
                // convierte en la respuesta de todos los que vengan detrás.
                if (invalidationGeneration == generationAtRead) {
                    cachedSnapshotKey = key
                    cachedSnapshot = result
                }
            }
            result
        }

    override fun getRecentlyPlayed(limit: Int): Flow<List<Song>> =
        songDao.getRecentlyPlayedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getRecentlyAdded(limit: Int): Flow<List<Song>> =
        songDao.getRecentlyAddedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getRecentlyAddedSince(since: Long): Flow<List<Song>> =
        songDao.getRecentlyAddedSinceFlow(since).map { list -> list.map { it.toSong() } }

    override fun getSongsByArtist(artist: String): Flow<List<Song>> =
        songDao.getSongsByArtistFlow(artist).map { list -> list.map { it.toSong() } }

    override fun getRediscover(before: Long, limit: Int): Flow<List<Song>> =
        songDao.getRediscoverFlow(before, limit).map { list -> list.map { it.toSong() } }

    override fun getNeverPlayed(limit: Int): Flow<List<Song>> =
        songDao.getNeverPlayedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getTopGenres(minCount: Int, limit: Int): Flow<List<com.qhana.siku.data.local.GenreSummary>> =
        songDao.getGenresFlow(minCount, limit, SongDao.ARTS_PER_GENRE, SongDao.ARTS_SEPARATOR)

    override suspend fun getSongsByGenre(genre: String, partialMatch: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            val entities = if (partialMatch) {
                songDao.getSongsByGenreLike("%${SongDao.escapeLike(genre)}%")
            } else {
                songDao.getSongsByGenre(genre)
            }
            entities.map { it.toSong() }
        }

    override suspend fun updateGenre(songId: String, genre: String?) = withContext(Dispatchers.IO) {
        songDao.updateGenre(songId, genre)
    }

    override fun getTopPlayedArtist(minSongs: Int): Flow<String?> =
        songDao.getTopPlayedArtistFlow(AppConfig.UNKNOWN_ARTIST, minSongs)

    override fun getLibrarySize(): Flow<Int> = songDao.getSongCountFlow()

    // Mapea el SortOrder al (columna, asc) del builder de queries. Compartido entre el
    // paging y el snapshot para que la cola use exactamente el mismo orden que la UI.
    private fun sortColumnFor(sortOrder: SortOrder): Pair<String, Boolean> = when (sortOrder) {
        SortOrder.TITLE_ASC -> "title" to true
        SortOrder.TITLE_DESC -> "title" to false
        SortOrder.DATE_ADDED_ASC -> "dateAdded" to true
        SortOrder.DATE_ADDED_DESC -> "dateAdded" to false
        SortOrder.MOST_PLAYED -> "playCount" to false
        SortOrder.RECENTLY_PLAYED -> "lastPlayedAt" to false
    }

    override fun getSongByIdFlow(songId: String): Flow<Song?> = songDao.getSongByIdFlow(songId).map { entity ->
        entity?.toSong()
    }

    override suspend fun getSongById(songId: String): AppResult<Song> = withContext(Dispatchers.IO) {
        runCatchingAsAppResult {
            songDao.getSongById(songId)?.toSong() ?: throw Exception("Song not found")
        }
    }

    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        // Chunk a <999: SQLite limita ~999 variables host por query (IN (:ids)).
        ids.chunked(SQLITE_VAR_LIMIT).flatMap { songDao.getSongsByIds(it) }.map { it.toSong() }
    }

    override suspend fun getAllSongIds(): List<String> = withContext(Dispatchers.IO) {
        songDao.getAllSongIds()
    }

    override suspend fun getSongIdsBySourceType(sourceType: SourceType): List<String> = withContext(Dispatchers.IO) {
        songDao.getSongIdsBySourceType(sourceType.name)
    }

    // --- Duplicados entre fuentes ---

    override suspend fun findCrossSourceDuplicates(loserSource: SourceType): List<DuplicatePair> =
        withContext(Dispatchers.IO) { songDao.findCrossSourceDuplicates(loserSource.name) }

    override suspend fun countCrossSourceDuplicates(): Int =
        withContext(Dispatchers.IO) { songDao.countCrossSourceDuplicates() }

    override suspend fun getRelativePathsOfOtherSources(sourceType: SourceType): Set<String> =
        withContext(Dispatchers.IO) { songDao.getRelativePathsOfOtherSources(sourceType.name).toHashSet() }

    /**
     * Fusiona el historial de la perdedora en la ganadora: el agregado Y el log de eventos, en una
     * transacción. El repunte de eventos va DENTRO de esta función y no como una llamada suelta al
     * lado de cada `mergePlayStats`: hay dos call sites hoy (el dedupe entre fuentes y la migración
     * de ids locales viejos) y un tercero futuro se olvidaría de ella. Sin FK, el UPDATE del id no
     * viola nada — mismo motivo por el que existe `repointSongRefs`.
     */
    override suspend fun mergePlayStats(loserId: String, winnerId: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                songDao.mergePlayStats(loserId, winnerId)
                playEventDao.repointEvents(loserId, winnerId)
            }
        }

    override suspend fun getSongIdsByAlbum(album: String): List<String> = withContext(Dispatchers.IO) {
        songDao.getSongIdsByAlbum(album)
    }

    override suspend fun getCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getAllSongs().map { it.toSong() }
    }

    override fun getSongCountFlow(sourceFilters: Set<SongSourceFilter>): Flow<Int> =
        if (sourceFilters.isEmpty()) songDao.getSongCountFlow()
        else songDao.getSongCountRawFlow(SongDao.buildCountQuery(sourceFilters))

    override fun hasLocalSongsFlow(): Flow<Boolean> =
        songDao.hasSongsMatchingFlow(SongDao.buildExistsQuery(setOf(SongSourceFilter.LOCAL)))

    override fun hasSourceSplitFlow(): Flow<Boolean> =
        songDao.hasSongsMatchingFlow(SongDao.buildSourceSplitQuery())

    override suspend fun upsertSongs(songs: List<Song>): AppResult<Int> = withContext(Dispatchers.IO) {
        runCatchingAsAppResult {
            if (songs.isEmpty()) return@runCatchingAsAppResult 0
            val existing = songs.map { it.id }.chunked(SQLITE_VAR_LIMIT)
                .flatMap { songDao.getSongsByIds(it) }.associateBy { it.id }
            val newEntities = mutableListOf<SongEntity>()
            var modifiedCount = 0

            songs.forEach { song ->
                val current = existing[song.id]
                // Backfill v23: las filas de nube pre-migración no tienen relativePath; el
                // scan la reporta y aquí se completa sin tocar el resto de la fila.
                if (current != null && current.relativePath == null && song.relativePath != null) {
                    songDao.backfillRelativePath(song.id, song.relativePath!!)
                }
                if (current == null) {
                    val needsMeta = song.artist == AppConfig.UNKNOWN_ARTIST || song.title == song.path.substringAfterLast("/")
                    newEntities.add(SongEntity.fromSong(song, albumId = 0, needsMetadata = needsMeta))
                } else if (song.size > 0 && current.size > 0 && current.size != song.size) {
                    // Cambio de contenido detectado por diferencia de tamaño (típicamente edición
                    // de tags ID3 desde otro cliente). Borramos el archivo local y lo marcamos
                    // para re-descarga; finalizeDownload extraerá los nuevos tags al terminar.
                    if (current.uriString.startsWith("file://")) {
                        try {
                            val file = java.io.File(current.uriString.removePrefix("file://"))
                            if (file.exists()) file.delete()
                        } catch (e: Exception) {
                            Log.w("SongRepository", "Error deleting modified file ${current.id}", e)
                        }
                    }
                    songDao.markSongAsModifiedForRedownload(current.id, song.size)
                    modifiedCount++
                }
            }
            if (newEntities.isNotEmpty()) songDao.insertSongs(newEntities)
            newEntities.size + modifiedCount
        }
    }

    override suspend fun getSongsNeedingMetadataOrDownload(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsNeedingWork(limit, offset, System.currentTimeMillis()).map { it.toSong() }
    }

    override suspend fun requeueDownloadedSongsWithoutMetadata(): Int = withContext(Dispatchers.IO) {
        songDao.requeueDownloadedSongsWithoutMetadata()
    }

    // ==================== Cola de descargas persistente ====================

    override suspend fun getDownloadAttempts(songId: String): Int = withContext(Dispatchers.IO) {
        songDao.getDownloadAttempts(songId) ?: 0
    }

    override suspend fun markDownloadFailed(songId: String, error: String, transient: Boolean, attempts: Int, nextRetryAt: Long): Unit = withContext(Dispatchers.IO) {
        songDao.markDownloadFailed(songId, attempts, error.take(MAX_STORED_ERROR_CHARS), if (transient) "TRANSIENT" else "PERMANENT", nextRetryAt)
    }

    override suspend fun clearDownloadError(songId: String): Unit = withContext(Dispatchers.IO) {
        songDao.clearDownloadError(songId)
    }

    override suspend fun resetDownloadErrors(): List<String> = withContext(Dispatchers.IO) {
        val ids = songDao.getFailedDownloadIds()
        if (ids.isNotEmpty()) songDao.resetDownloadErrors()
        ids
    }

    override fun getFailedDownloadsFlow(): Flow<List<FailedDownload>> =
        songDao.getFailedDownloadsFlow().map { list ->
            list.map { entity ->
                FailedDownload(
                    song = entity.toSong(),
                    error = entity.lastDownloadError,
                    errorKind = entity.downloadErrorKind,
                    attempts = entity.downloadAttempts,
                    nextRetryAt = entity.nextRetryAt
                )
            }
        }

    override suspend fun getEarliestRetryAt(): Long? = withContext(Dispatchers.IO) {
        songDao.getEarliestRetryAt(System.currentTimeMillis())
    }

    override suspend fun deleteAudioFileById(songId: String): Unit = withContext(Dispatchers.IO) {
        try {
            val musicDir = java.io.File(context.filesDir, "music")
            if (musicDir.exists()) {
                // Patrón unificado: "${id}.${ext}". El "." final evita colisiones con IDs
                // que sean prefijo de otros (que sí ocurría con el viejo "${id}_").
                val filesToDelete = musicDir.listFiles()?.filter { it.name.startsWith("${songId}.") } ?: emptyList()
                filesToDelete.forEach { it.delete() }
            }
            songDao.updateSongPath(songId, "")
        } catch (e: Exception) {
            Log.w("SongRepository", "Error eliminando archivos por ID $songId", e)
        }
    }

    override suspend fun deleteSongs(idsToDelete: List<String>) = withContext(Dispatchers.IO) {
        if (idsToDelete.isEmpty()) return@withContext
        val songs = idsToDelete.chunked(SQLITE_VAR_LIMIT).flatMap { songDao.getSongsByIds(it) }

        // Las FILAS primero: el borrado de carátulas pregunta a la BD quién sigue usando cada
        // archivo, y esa respuesta solo es correcta cuando las que se van ya no cuentan.
        idsToDelete.chunked(SQLITE_VAR_LIMIT).forEach { songDao.deleteSongsByIds(it) }

        songs.forEach { song ->
            if (song.uriString.startsWith("file://")) {
                try {
                    val file = java.io.File(song.uriString.removePrefix("file://"))
                    if (file.exists()) file.delete()
                } catch (e: Exception) {
                    Log.w("SongRepository", "Error deleting file for song ${song.id}", e)
                }
            }
            try {
                // La carátula se localiza por el URI que guarda la FILA, no reconstruyendo un
                // nombre a partir del id: desde que el archivo se llama por su contenido, el id
                // ya no lo determina.
                // Vale igual para las carátulas guardadas por versiones anteriores (`<songId>.jpg`):
                // la fila las referencia por URI, que es lo único que se consulta aquí. Y las que
                // ya no referencie nadie las barre la poda del sync, así que no hace falta ningún
                // caso especial por el esquema de nombres viejo.
                song.albumArtUriString
                    ?.takeIf { it.startsWith("file://") }
                    ?.let { deleteCoverIfUnused(java.io.File(it.removePrefix("file://")), it) }
            } catch (e: Exception) {
                Log.w("SongRepository", "Error deleting cover for song ${song.id}", e)
            }
        }

        // Se avisa al FINAL: quien escucha (el reproductor, para sacarlas de la cola) debe verlo
        // cuando las filas ya no están, no a mitad del borrado.
        _songsDeleted.tryEmit(idsToDelete)
    }

    /**
     * Borra un archivo de carátula SOLO si ya no queda ninguna fila apuntando a él.
     *
     * Una portada se comparte: converge por contenido entre las canciones de un álbum y la
     * metadata ligera la propaga con `setAlbumArt`, cruzando incluso fuentes distintas. Borrarla
     * al retirar a la canción que la extrajo —dedupe entre fuentes, reconciliación, quitar una
     * carpeta— dejaba al resto del álbum apuntando a un archivo muerto, y el healing lo traducía
     * a "sin carátula" de forma permanente.
     */
    private suspend fun deleteCoverIfUnused(cover: java.io.File, uri: String) {
        if (!cover.exists()) return
        if (songDao.countSongsWithArt(uri) == 0) cover.delete()
    }

    override suspend fun countSongsNeedingWork(): Int = withContext(Dispatchers.IO) {
        songDao.countSongsNeedingWork(System.currentTimeMillis())
    }

    override suspend fun getTotalDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        songDao.getTotalDownloadedBytes()
    }

    override fun getTotalDownloadedBytesFlow(): Flow<Long> = songDao.getTotalDownloadedBytesFlow()

    override suspend fun getEvictionCandidates(excludeId: String): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        songDao.getEvictionCandidates(excludeId).map { it.id to it.size }
    }

    override suspend fun updateSongMetadata(song: Song) = withContext(Dispatchers.IO) {
        songDao.updateSongMetadata(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = song.duration,
            albumArtUri = song.albumArtUri?.toString(),
            trackNumber = song.trackNumber,
            year = song.year
        )
    }

    override suspend fun getSongsNeedingTrackInfo(localOnly: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            songDao.getSongsNeedingTrackInfo(localOnly).map { it.toSong() }
        }

    override suspend fun updateTrackInfo(songId: String, trackNumber: Int, year: Int) =
        withContext(Dispatchers.IO) {
            songDao.updateTrackInfo(songId, trackNumber, year)
        }

    override suspend fun getSongsNeedingLightMetadata(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsNeedingLightMetadata(AppConfig.UNKNOWN_ARTIST).map { it.toSong() }
    }

    override suspend fun updateLightMetadata(
        songId: String, title: String, artist: String, album: String, genre: String?,
        trackNumber: Int, year: Int, durationMs: Long
    ) = withContext(Dispatchers.IO) {
        songDao.updateLightMetadata(songId, title, artist, album, genre, trackNumber, year, durationMs)
    }

    override suspend fun getAlbumArtUri(album: String): String? = withContext(Dispatchers.IO) {
        songDao.getAlbumArtUri(album)
    }

    override suspend fun setAlbumArt(album: String, uri: String) = withContext(Dispatchers.IO) {
        songDao.setAlbumArt(album, uri)
    }

    override suspend fun updateAlbumArtUri(songId: String, uri: String?) = withContext(Dispatchers.IO) {
        songDao.updateAlbumArtUri(songId, uri)
    }

    override suspend fun getSongsWithLocalArt(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsWithLocalArt().map { it.toSong() }
    }

    override suspend fun getSongsWithPendingArtwork(localOnly: Boolean): List<Song> = withContext(Dispatchers.IO) {
        val pending = if (localOnly) {
            songDao.getLocalSongsWithPendingArtwork()
        } else {
            songDao.getSongsWithPendingArtwork()
        }
        pending.map { it.toSong() }
    }

    override suspend fun markArtworkAttempted(songIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        songIds.chunked(SQLITE_VAR_LIMIT).forEach { songDao.markArtworkAttempted(it, now) }
    }

    override suspend fun markLightTagsAttempted(songIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        songIds.chunked(SQLITE_VAR_LIMIT).forEach { songDao.markLightTagsAttempted(it, now) }
    }

    override suspend fun clearArtworkAttempted(songIds: List<String>): Unit = withContext(Dispatchers.IO) {
        songIds.chunked(SQLITE_VAR_LIMIT).forEach { songDao.clearArtworkAttempted(it) }
    }

    override suspend fun getReferencedArtUris(): Set<String> = withContext(Dispatchers.IO) {
        songDao.getReferencedArtUris().toHashSet()
    }

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        getCachedSongs()
    }

    override suspend fun updateSongUrl(songId: String, newUrl: String) = withContext(Dispatchers.IO) { songDao.updateSongPath(songId, newUrl) }

    override suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?) =
        withContext(Dispatchers.IO) { songDao.updateReplayGain(songId, trackGainDb, trackPeak, albumGainDb, albumPeak) }

    /**
     * Registra una escucha contada: el agregado de la v22 y el evento del log de la v28.
     *
     * **UNA sola transacción**, porque son el MISMO hecho a dos resoluciones. Si pudieran divergir,
     * el saludo del inicio ("escuchaste N canciones esta semana", que sale de `lastPlayedAt`) y la
     * pantalla de estadísticas se contradirían y nadie podría decir cuál miente — la familia de
     * bugs de "dos verdades" de la convención 14. Efecto colateral buscado: UNA sola ráfaga de
     * invalidación de Room en lugar de dos.
     *
     * Si la canción se borró entre el play y este registro, no se escribe nada (ni evento ni
     * agregado): hoy `incrementPlayStats` sobre una fila inexistente ya es un no-op.
     */
    override suspend fun recordPlay(songId: String, at: Long, playedMs: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val snapshot = songDao.getPlaySnapshot(songId) ?: return@withTransaction
            songDao.incrementPlayStats(songId, at)
            playEventDao.insert(
                PlayEventEntity(
                    songId = songId,
                    playedAt = at,
                    // Acotado a la duración: `playedMs` es la POSICIÓN alcanzada, así que un salto
                    // hacia adelante inflaría el tiempo escuchado del periodo.
                    playedMs = if (snapshot.duration > 0) playedMs.coerceIn(0L, snapshot.duration)
                    else playedMs.coerceAtLeast(0L),
                    artist = snapshot.artist
                )
            )
        }
    }
    override suspend fun markSongAsCorrupted(songId: String) = withContext(Dispatchers.IO) { songDao.markSongAsCorrupted(songId) }
    override suspend fun clearCorrupted(songId: String) = withContext(Dispatchers.IO) { songDao.clearCorrupted(songId) }
    override suspend fun getCorruptedSongs(): List<Song> = withContext(Dispatchers.IO) { songDao.getCorruptedSongs().map { it.toSong() } }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        songDao.deleteAll()
    }

    private companion object {
        // SQLite limita SQLITE_MAX_VARIABLE_NUMBER a 999 en Android < 12 (API < 31).
        // Chunkeamos por debajo para que IN (:ids) nunca lance "too many SQL variables".
        const val SQLITE_VAR_LIMIT = 900

        /**
         * Tope del mensaje de error que se guarda con una descarga fallida.
         *
         * **Se dimensiona contra los mensajes que la app REALMENTE produce**, que están medidos: el
         * más largo de los `dl_err_*` son 58 caracteres (`dl_err_content_type`), y con su parámetro
         * relleno ronda los 80; un `"HTTP 500: Internal Server Error"` son 31. O sea que doscientos
         * es más del doble del peor caso legítimo y ningún mensaje propio llega a truncarse.
         *
         * Lo que recorta es la cola imprevisible. Aquí NO llegan stacktraces —los mensajes se
         * componen a mano— pero `dl_err_exception` interpola el `message` de la excepción, y las de
         * OkHttp arrastran a veces la URL firmada de OneDrive: cientos de caracteres de token que
         * nadie va a leer. Multiplicado por las canciones que fallan a la vez cuando un sync se cae
         * en bloque, es lo que se queda en la tabla.
         *
         * Coincide además con lo que la UI puede enseñar (dos líneas de `bodySmall` en el gestor de
         * descargas, del orden de cien caracteres), así que el `Text` corta antes que esto: no hay
         * forma de que el truncado esconda algo visible.
         */
        const val MAX_STORED_ERROR_CHARS = 200

        /**
         * Tope de canciones que puede rescatar la búsqueda por aproximación.
         *
         * Sale de [SQLITE_VAR_LIMIT]: esos ids viajan como parámetros de un `IN (...)` dentro de la
         * consulta paginada, y SQLite no admite más de 999 en Android por debajo de la 12. Se queda
         * holgadamente por debajo porque a la consulta le hacen falta además sus propios binds (el
         * texto del LIKE y los filtros de origen).
         *
         * No es una restricción sentida: son coincidencias APROXIMADAS ordenadas de más a menos
         * parecida, y quien buscaba algo concreto lo tiene en las primeras. Si una búsqueda de dos
         * letras se pareciera a media biblioteca, cortar es además lo correcto.
         */
        const val APPROXIMATE_SEARCH_LIMIT = 500
    }
}
