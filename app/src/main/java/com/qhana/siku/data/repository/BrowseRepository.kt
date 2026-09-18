package com.qhana.siku.data.repository

import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistDao
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.local.GenreSummary
import com.qhana.siku.data.local.RelatedArtist
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.DetailSongSort
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.local.SongEntity
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortSpec
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
    private companion object {
        /** Un género ya reproducido merece su collage aunque tenga una sola canción. */
        const val GENRE_ARTS_MIN_COUNT = 1
    }

    /**
     * ¿Sigue teniendo sentido elegir origen? Misma consulta —y por tanto misma definición— que la
     * que consume la pestaña Todas por `ISongRepository`; ver [SongDao.buildSourceSplitQuery]. La
     * necesitan Artistas y Álbumes para SANEAR sus propios filtros: si la última canción sin
     * descargar termina de bajarse con el chip "Nube" puesto, la lista quedaría vacía y sin ningún
     * control a la vista para deshacerlo.
     */
    fun hasSourceSplit(): Flow<Boolean> =
        songDao.hasSongsMatchingFlow(SongDao.buildSourceSplitQuery())

    /** ¿Hay canciones de la fuente local? Condición extra del chip "Local". */
    fun hasLocalSongs(): Flow<Boolean> =
        songDao.hasSongsMatchingFlow(SongDao.buildExistsQuery(setOf(SongSourceFilter.LOCAL)))

    fun getArtists(
        sort: SortSpec<ArtistSortOrder>,
        role: ArtistCreditRole,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): Flow<List<ArtistSummary>> =
        artistDao.getArtistsFlow(SongDao.buildArtistsQuery(sort, role, sourceFilters))

    fun getArtistInfo(name: String): Flow<ArtistEntity?> = artistDao.getArtistFlow(name)

    /**
     * Foto de cada artista pedido (miniatura si la hay, si no la grande), omitiendo los que no
     * tienen ninguna. Reactivo: si el backfill la resuelve más tarde —o el usuario la cambia o
     * la quita en el picker— quien observe se entera.
     */
    fun getArtistPhotos(names: List<String>): Flow<Map<String, String>> =
        artistDao.getArtistsByNameFlow(names).map { rows ->
            rows.mapNotNull { row -> (row.thumbUrl ?: row.imageUrl)?.let { row.name to it } }.toMap()
        }

    fun getAlbums(
        sort: SortSpec<AlbumSortOrder>,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ): Flow<List<AlbumSummary>> =
        songDao.getAlbumsFlow(SongDao.buildAlbumsQuery(sort, sourceFilters))

    fun getAlbumsByArtist(artist: String, role: ArtistCreditRole): Flow<List<AlbumSummary>> =
        songDao.getAlbumsByArtistFlow(SongDao.buildArtistAlbumsQuery(artist, role))

    /**
     * Canciones de un artista, con el orden que pida el caller.
     *
     * **[sort] no tiene default a propósito**: `DetailSongSort.DEFAULT` (el orden del catálogo) es
     * lo que quiere quien va a REPRODUCIR —la tarjeta de un artista en la biblioteca, el "seguir
     * escuchando" del inicio—, y el del chip solo lo quiere la pantalla de detalle. Con un default,
     * un caller nuevo heredaría en silencio el criterio que el usuario eligió en otra pantalla.
     *
     * El orden va en SQL y no ordenando la lista en Kotlin porque dos de los criterios
     * —reproducciones y última escucha— viven en columnas que el modelo de dominio [Song] **no
     * lleva**: son de `SongEntity`. Meterlas en el modelo para poder ordenar en memoria sería
     * ensanchar lo que consume media app por una necesidad de tres pantallas.
     */
    fun getSongsByArtist(
        artist: String,
        role: ArtistCreditRole,
        sort: SortSpec<DetailSongSort>
    ): Flow<List<Song>> =
        songDao.getSongsRawFlow(SongDao.buildArtistSongsQuery(artist, role, sort))
            .map { list -> list.map(SongEntity::toSong) }

    /** Carátula de un álbum, reactiva. Ver [SongDao.getAlbumArtUriFlow]. */
    fun getAlbumArt(album: String, albumArtist: String?): Flow<String?> =
        songDao.getAlbumArtUriFlow(album, albumArtist)

    /**
     * Canciones de un álbum. Ver [getSongsByArtist] para por qué [sort] es obligatorio, y
     * [SongDao.albumPredicate] para [albumArtist] (`null` = el álbum por nombre, de cualquier artista).
     */
    fun getSongsByAlbum(
        album: String,
        albumArtist: String?,
        sort: SortSpec<DetailSongSort>
    ): Flow<List<Song>> =
        songDao.getSongsRawFlow(SongDao.buildAlbumSongsQuery(album, albumArtist, sort))
            .map { list -> list.map(SongEntity::toSong) }

    /**
     * Todos los géneros de la biblioteca (agrupados sin distinguir mayúsculas), con su conteo y
     * las carátulas del collage. Sin tope: la pestaña Géneros los lista enteros — los chips del
     * inicio, que sí piden un top, van por `ISongRepository.getTopGenres`.
     */
    fun getGenres(minCount: Int): Flow<List<GenreSummary>> = songDao.getGenresFlow(
        minCount = minCount,
        limit = SongDao.NO_LIMIT,
        artsLimit = SongDao.ARTS_PER_GENRE,
        artsSeparator = SongDao.ARTS_SEPARATOR
    )

    /**
     * Carátulas (para el collage) de unos géneros concretos, por nombre en minúsculas.
     *
     * Lo usa "Seguir escuchando" del inicio por la MISMA razón que la foto del artista se
     * resuelve en vivo: un género no tiene carátula propia, tiene muchas, y el snapshot que se
     * guardaba al reproducirlo —el arte de su primera canción— era arbitrario (cambia con el
     * orden) y quedaba en `null` en cuanto esa canción no tenía carátula, que es lo que dejaba
     * la tarjeta con el glifo de relleno. La clave va normalizada porque el agrupamiento de
     * géneros ignora mayúsculas y devuelve un nombre representativo que no tiene por qué
     * coincidir letra a letra con el que se guardó en el contexto.
     *
     * `minCount = 1`: un género ya reproducido se pinta aunque tenga pocas canciones (el tope
     * de los chips del inicio es otra cosa).
     */
    fun getGenreArts(names: Set<String>): Flow<Map<String, List<String>>> {
        val wanted = names.map { it.lowercase() }.toSet()
        return getGenres(minCount = GENRE_ARTS_MIN_COUNT).map { genres ->
            genres.asSequence()
                .filter { it.name.lowercase() in wanted }
                .associate { it.name.lowercase() to it.arts }
        }
    }

    /**
     * Canciones de un género para su pantalla de detalle. [partialMatch] = ajuste "incluir
     * géneros compuestos": "Rock" trae también "Rock/Metal" y "Hard Rock".
     */
    fun getSongsByGenre(
        genre: String,
        partialMatch: Boolean,
        sort: SortSpec<DetailSongSort>
    ): Flow<List<Song>> =
        songDao.getSongsRawFlow(SongDao.buildGenreSongsQuery(genre, partialMatch, sort))
            .map { list -> list.map(SongEntity::toSong) }

    /**
     * Álbumes de un género, para la vista "Álbumes" de su detalle. Mismo [partialMatch] que
     * [getSongsByGenre] — las dos vistas del mismo detalle tienen que seguir el mismo ajuste.
     */
    fun getAlbumsByGenre(genre: String, partialMatch: Boolean): Flow<List<AlbumSummary>> =
        if (partialMatch) {
            songDao.getAlbumsByGenreLikeFlow("%${SongDao.escapeLike(genre)}%")
        } else {
            songDao.getAlbumsByGenreFlow(genre)
        }

    /**
     * Artistas que comparten género con [seedArtist], para "Porque escuchaste a X". Ver
     * [SongDao.getRelatedArtistsFlow] — incluido el límite de lo que este criterio puede relacionar.
     */
    fun getRelatedArtists(seedArtist: String, limit: Int): Flow<List<RelatedArtist>> =
        songDao.getRelatedArtistsFlow(seedArtist, AppConfig.UNKNOWN_ARTIST, limit)

    /** Limpia el cache de artistas (fotos Deezer + selecciones manuales). Se usa en logout. */
    suspend fun clearArtistCache() = artistDao.deleteAll()
}
