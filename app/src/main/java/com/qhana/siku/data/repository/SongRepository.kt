package com.qhana.siku.data.repository

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.InvalidationTracker
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.DuplicatePair
import com.qhana.siku.data.local.MusicDatabase
import com.qhana.siku.data.local.SongDao
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    database: MusicDatabase
) : ISongRepository {

    // Caché del snapshot de cola (toda la biblioteca en el orden/búsqueda actual). Evita
    // re-leer y mapear la tabla entera en cada tap de reproducción. Se invalida SOLA ante
    // cualquier escritura en `songs` (sync, descargas, metadata, colores...) vía el
    // InvalidationTracker de Room, así que nunca sirve una lista obsoleta.
    private val snapshotLock = Any()
    @Volatile private var cachedSnapshotKey: Triple<String, SortOrder, Set<SongSourceFilter>>? = null
    @Volatile private var cachedSnapshot: List<Song>? = null

    init {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("songs") {
            override fun onInvalidated(tables: Set<String>) {
                synchronized(snapshotLock) {
                    cachedSnapshotKey = null
                    cachedSnapshot = null
                }
            }
        })
    }

    override fun getSongsPaging(
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter>
    ): Flow<PagingData<Song>> {
        val (sortColumn, sortAsc) = sortColumnFor(sortOrder)

        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = true),
            pagingSourceFactory = {
                val sqlQuery = SongDao.buildPagingQuery(query, sortColumn, sortAsc, sourceFilters)
                songDao.getSongsPagingRaw(sqlQuery)
            }
        ).flow.map { pagingData -> pagingData.map { it.toSong() } }
    }

    override suspend fun getSongsSnapshot(
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter>
    ): List<Song> =
        withContext(Dispatchers.IO) {
            val key = Triple(query, sortOrder, sourceFilters)
            synchronized(snapshotLock) {
                if (cachedSnapshotKey == key) cachedSnapshot?.let { return@withContext it }
            }
            val (sortColumn, sortAsc) = sortColumnFor(sortOrder)
            val result = songDao.getSongsList(SongDao.buildPagingQuery(query, sortColumn, sortAsc, sourceFilters)).map { it.toSong() }
            synchronized(snapshotLock) {
                cachedSnapshotKey = key
                cachedSnapshot = result
            }
            result
        }

    override fun getRecentlyPlayed(limit: Int): Flow<List<Song>> =
        songDao.getRecentlyPlayedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getMostPlayed(limit: Int): Flow<List<Song>> =
        songDao.getMostPlayedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getRecentlyAdded(limit: Int): Flow<List<Song>> =
        songDao.getRecentlyAddedFlow(limit).map { list -> list.map { it.toSong() } }

    override fun getSongsByArtist(artist: String): Flow<List<Song>> =
        songDao.getSongsByArtistFlow(artist).map { list -> list.map { it.toSong() } }

    override fun getRediscover(before: Long, limit: Int): Flow<List<Song>> =
        songDao.getRediscoverFlow(before, limit).map { list -> list.map { it.toSong() } }

    override fun getTopGenres(minCount: Int, limit: Int): Flow<List<com.qhana.siku.data.local.GenreSummary>> =
        songDao.getTopGenresFlow(minCount, limit)

    override suspend fun getSongsByGenre(genre: String): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsByGenre(genre).map { it.toSong() }
    }

    override suspend fun getDownloadedSongsWithoutGenre(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        songDao.getDownloadedSongsWithoutGenre(limit).map { it.toSong() }
    }

    override suspend fun updateGenre(songId: String, genre: String?) = withContext(Dispatchers.IO) {
        songDao.updateGenre(songId, genre)
    }

    override fun getTopPlayedArtist(minSongs: Int): Flow<String?> =
        songDao.getTopPlayedArtistFlow(AppConfig.UNKNOWN_ARTIST, minSongs)

    override fun getPlayedSinceCount(since: Long): Flow<Int> =
        songDao.getPlayedSinceCountFlow(since)

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

    override suspend fun mergePlayStats(loserId: String, winnerId: String) =
        withContext(Dispatchers.IO) { songDao.mergePlayStats(loserId, winnerId) }

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
        songDao.hasSongsOfSourceFlow(SourceType.LOCAL.name)

    override fun hasCloudSongsFlow(): Flow<Boolean> =
        songDao.hasSongsNotOfSourceFlow(SourceType.LOCAL.name)

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
        songDao.markDownloadFailed(songId, attempts, error.take(200), if (transient) "TRANSIENT" else "PERMANENT", nextRetryAt)
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
                // Covers se guardan en filesDir desde el cambio de ubicación;
                // limpiamos también el legacy en cacheDir para instalaciones previas.
                val cover = java.io.File(context.filesDir, "covers/${song.id}.jpg")
                if (cover.exists()) cover.delete()
                val legacyCover = java.io.File(context.cacheDir, "covers/${song.id}.jpg")
                if (legacyCover.exists()) legacyCover.delete()
            } catch (e: Exception) {
                Log.w("SongRepository", "Error deleting cover for song ${song.id}", e)
            }
        }
        idsToDelete.chunked(SQLITE_VAR_LIMIT).forEach { songDao.deleteSongsByIds(it) }
    }

    override suspend fun countSongsNeedingWork(): Int = withContext(Dispatchers.IO) {
        songDao.countSongsNeedingWork(System.currentTimeMillis())
    }

    override suspend fun getTotalDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        songDao.getTotalDownloadedBytes()
    }

    override suspend fun getEvictionCandidates(excludeId: String): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        songDao.getEvictionCandidates(excludeId).map { it.id to it.size }
    }

    override suspend fun updateSongMetadata(song: Song) = withContext(Dispatchers.IO) {
        songDao.updateSongMetadata(song.id, song.title, song.artist, song.album, song.duration, song.albumArtUri?.toString())
    }

    override suspend fun updateAlbumArtUri(songId: String, uri: String?) = withContext(Dispatchers.IO) {
        songDao.updateAlbumArtUri(songId, uri)
    }

    override suspend fun getSongsWithLocalArt(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsWithLocalArt().map { it.toSong() }
    }

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        getCachedSongs()
    }

    override suspend fun updateSongUrl(songId: String, newUrl: String) = withContext(Dispatchers.IO) { songDao.updateSongPath(songId, newUrl) }

    override suspend fun updateReplayGain(songId: String, trackGainDb: Float?, trackPeak: Float?, albumGainDb: Float?, albumPeak: Float?) =
        withContext(Dispatchers.IO) { songDao.updateReplayGain(songId, trackGainDb, trackPeak, albumGainDb, albumPeak) }

    override suspend fun recordPlay(songId: String, at: Long) = withContext(Dispatchers.IO) { songDao.incrementPlayStats(songId, at) }
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
    }
}
