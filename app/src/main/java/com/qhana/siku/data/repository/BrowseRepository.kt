package com.qhana.siku.data.repository

import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistDao
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.local.SongEntity
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fachada de navegación por Artistas/Álbumes: agregaciones reactivas derivadas de la
 * tabla `songs` (GROUP BY artist/album) + metadatos de artista (`artists`).
 */
@Singleton
class BrowseRepository @Inject constructor(
    private val songDao: SongDao,
    private val artistDao: ArtistDao
) {
    fun getArtists(
        sort: ArtistSortOrder,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): Flow<List<ArtistSummary>> =
        artistDao.getArtistsFlow(SongDao.buildArtistsQuery(sort.name, sourceFilters))

    fun getArtistInfo(name: String): Flow<ArtistEntity?> = artistDao.getArtistFlow(name)

    fun getAlbums(
        sort: AlbumSortOrder,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): Flow<List<AlbumSummary>> =
        songDao.getAlbumsFlow(SongDao.buildAlbumsQuery(sort.name, sourceFilters))

    /** Álbumes del momento (por total de reproducciones) para la home. */
    fun getTopAlbums(limit: Int): Flow<List<AlbumSummary>> = songDao.getTopAlbumsFlow(limit)

    fun getAlbumsByArtist(artist: String): Flow<List<AlbumSummary>> =
        songDao.getAlbumsByArtistFlow(artist)

    fun getSongsByArtist(artist: String): Flow<List<Song>> =
        songDao.getSongsByArtistFlow(artist).map { list -> list.map(SongEntity::toSong) }

    fun getSongsByAlbum(album: String): Flow<List<Song>> =
        songDao.getSongsByAlbumFlow(album).map { list -> list.map(SongEntity::toSong) }

    /** Limpia el cache de artistas (fotos Deezer + selecciones manuales). Se usa en logout. */
    suspend fun clearArtistCache() = artistDao.deleteAll()
}
