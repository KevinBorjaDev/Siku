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
    val thumbUrl: String?
)

@Dao
interface ArtistDao {

    /**
     * Lista de artistas derivada de las canciones (GROUP BY artist) con su foto Deezer
     * si existe, con orden + filtro de origen dinámicos ([SongDao.buildArtistsQuery]).
     * RawQuery para inyectar el WHERE de origen antes del GROUP BY; `observedEntities`
     * cubre AMBAS tablas del SQL → reactivo tanto al scan como a la selección manual de foto.
     */
    @RawQuery(observedEntities = [SongEntity::class, ArtistEntity::class])
    fun getArtistsFlow(query: SupportSQLiteQuery): Flow<List<ArtistSummary>>

    @Query("SELECT * FROM artists WHERE name = :name")
    fun getArtistFlow(name: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE name = :name")
    suspend fun getArtist(name: String): ArtistEntity?

    /**
     * Artistas de la biblioteca con foto pendiente de resolver: sin fila en `artists`, o sin
     * imageUrl y con el not-found ya expirado. Alimenta el backfill en background; las
     * selecciones manuales y los not-found dentro del TTL quedan fuera.
     */
    @Query(
        """
        SELECT DISTINCT s.artist FROM songs s
        LEFT JOIN artists a ON a.name = s.artist
        WHERE a.name IS NULL
           OR (a.imageUrl IS NULL AND a.manuallySet = 0
               AND (a.fetchedAt IS NULL OR a.fetchedAt < :expiredBefore))
        """
    )
    suspend fun getArtistNamesNeedingImage(expiredBefore: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(artist: ArtistEntity)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}
