package com.qhana.siku.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Proyección para la pestaña/listas de artistas: agregados de `songs` + foto cacheada.
 */
data class ArtistSummary(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val imageUrl: String?,
    val thumbUrl: String?,
    /**
     * Carátula de alguno de sus álbumes, para cuando no hay foto — y muy en particular cuando no
     * la hay A PROPÓSITO (el usuario respondió "ninguno de estos" en el picker porque Deezer solo
     * ofrecía homónimos). Sale de la misma agregación, así que no cuesta una consulta aparte.
     */
    val fallbackArtUri: String? = null
)

@Dao
interface ArtistDao {

    /**
     * Lista de artistas derivada de las canciones (GROUP BY artist) con su foto Deezer
     * si existe, con orden + filtro de origen dinámicos ([SongDao.buildArtistsQuery]).
     * RawQuery para inyectar el WHERE de origen antes del GROUP BY; `observedEntities`
     * cubre AMBAS tablas del SQL → reactivo tanto al scan como a la selección manual de foto.
     */
    @RawQuery(observedEntities = [SongEntity::class, ArtistEntity::class, ArtistCreditEntity::class])
    fun getArtistsFlow(query: SupportSQLiteQuery): Flow<List<ArtistSummary>>

    @Query("SELECT * FROM artists WHERE name = :name")
    fun getArtistFlow(name: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE name = :name")
    suspend fun getArtist(name: String): ArtistEntity?

    /**
     * Fotos de un puñado de artistas concretos. Lo usa "Seguir escuchando" del inicio, que
     * necesita la foto del ARTISTA y no la carátula que se guardó en el contexto: el snapshot
     * del contexto es de cuando se reprodujo, y la foto puede llegar después (backfill) o
     * cambiar (picker manual, "ninguno de estos").
     */
    @Query("SELECT * FROM artists WHERE name IN (:names)")
    fun getArtistsByNameFlow(names: List<String>): Flow<List<ArtistEntity>>

    /**
     * Los nombres salen de los CRÉDITOS (v29, ambos roles) y no de `songs.artist`: con la separación
     * de colaboraciones activa, "A feat. B" ya no es el nombre de ningún artista de la pestaña, y
     * buscarle foto sería una petición a Deezer para nadie. Las canciones aún sin créditos calculados
     * aportan su `artist`, igual que en `ArtistCreditSql.ROWS`.
     *
     * Artistas de la biblioteca con foto pendiente de resolver: sin fila en `artists`, o sin
     * imageUrl y con el not-found ya expirado. Alimenta el backfill en background; las
     * selecciones manuales y los not-found dentro de su espera quedan fuera.
     *
     * **El filtro de nombre no es cosmético**: `ArtistImageRepository.ensureArtistImage`
     * descarta el placeholder de "sin tag" y los blancos SIN escribir nada en la tabla, así
     * que sin excluirlos aquí volverían en cada consulta y "pendiente" nunca sería vacío —
     * con una sola canción sin tags, el backfill se creía eternamente a medias. El criterio
     * de esta consulta y el de aquella función tienen que ser el MISMO.
     *
     * La espera del not-found se DUPLICA con cada fallo ([ArtistEntity.notFoundAttempts]):
     * `base · 2^(intentos-1)`, topada en `2^maxBackoffShift`. Va en SQL y no en Kotlin
     * porque es el propio criterio de "pendiente" — calcularlo fuera obligaría a traerse
     * toda la tabla para filtrarla en memoria.
     */
    @Query(
        """
        SELECT DISTINCT c.name FROM (
            SELECT name FROM song_artists
            UNION SELECT artist AS name FROM songs WHERE id NOT IN (SELECT songId FROM song_artists)
        ) c
        LEFT JOIN artists a ON a.name = c.name
        WHERE TRIM(c.name) != '' AND c.name != :unknownArtist
          AND LOWER(TRIM(c.name)) NOT IN (:variousArtists)
          AND (a.name IS NULL
               OR (a.imageUrl IS NULL AND a.manuallySet = 0
                   AND (a.fetchedAt IS NULL
                        OR a.fetchedAt + :baseTtlMs *
                           (1 << MIN(MAX(a.notFoundAttempts - 1, 0), :maxBackoffShift)) <= :now)))
        """
    )
    suspend fun getArtistNamesNeedingImage(
        now: Long,
        baseTtlMs: Long,
        maxBackoffShift: Int,
        unknownArtist: String,
        variousArtists: List<String>
    ): List<String>

    /**
     * Artistas a los que **nunca se llegó a preguntar** por su foto (sin fila, o con fila sin
     * `fetchedAt` porque el intento murió en un fallo de red).
     *
     * Es lo único que justifica pedirle datos móviles al usuario: son las fotos que de verdad
     * se pueden ganar. Un artista que Deezer ya dijo no tener seguirá saliendo en
     * [getArtistNamesNeedingImage] cuando venza su espera —y está bien, es mantenimiento que
     * corre solo cuando ya hay WiFi—, pero ofrecer gastar megas por él es prometer fotos que
     * no van a llegar.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT c.name) FROM (
            SELECT name FROM song_artists
            UNION SELECT artist AS name FROM songs WHERE id NOT IN (SELECT songId FROM song_artists)
        ) c
        LEFT JOIN artists a ON a.name = c.name
        WHERE TRIM(c.name) != '' AND c.name != :unknownArtist
          AND LOWER(TRIM(c.name)) NOT IN (:variousArtists)
          AND (a.name IS NULL OR a.fetchedAt IS NULL)
        """
    )
    suspend fun countArtistsNeverAttempted(unknownArtist: String, variousArtists: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(artist: ArtistEntity)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
