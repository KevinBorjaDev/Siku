package com.qhana.siku.data.repository

import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.PlayEventDao
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Totales de un periodo.
 *
 * [listenedMs] es NULLABLE y eso es el dato, no un descuido: el periodo "Siempre" sale de
 * `songs.playCount`, que nunca midió tiempo, así que ahí no hay minutos que enseñar. Un 0 sería
 * mentira ("escuchaste cero") y la pantalla prefiere no pintar la tarjeta.
 */
data class StatsSummary(
    val plays: Int,
    val listenedMs: Long?,
    val distinctSongs: Int,
    val distinctArtists: Int
) {
    val isEmpty: Boolean get() = plays == 0
}

/** Una canción del ranking, con su fila viva para poder reproducirla. */
data class StatsSongEntry(
    val song: Song,
    val plays: Int,
    val listenedMs: Long?
)

/** Una entrada del ranking de artistas, álbumes o géneros. */
data class StatsNameEntry(
    val name: String,
    val plays: Int,
    val listenedMs: Long?,
    /** Carátula o foto con la que pintarla; `null` cae al placeholder de su categoría. */
    val artUri: String?,
    /** Línea secundaria: el artista de un álbum. `null` en artistas y géneros. */
    val secondary: String? = null
)

/**
 * Lectura de las estadísticas de escucha. Clase concreta y sin interfaz, siguiendo a
 * [BrowseRepository]: las interfaces del repo existen porque tienen más de una implementación o
 * porque se mockean en tests, y ésta no está en ninguno de los dos casos.
 *
 * **Dos fuentes, y la separación es deliberada.** Mes y año salen del log `play_events` (v28), que
 * es el único con fechas. "Siempre" sale de los agregados `songs.playCount`, que son los únicos que
 * llevan el pasado anterior a la actualización — a cambio de no tener tiempo escuchado ni racha,
 * que por eso viajan como `null`. NO unificarlas después "por limpieza": el resultado sería
 * inventar los minutos que faltan o tirar todo el historial previo.
 *
 * **Qué observa cada consulta importa** (Room invalida por TABLA): el resumen y la racha mencionan
 * solo `play_events`, donde el sync nunca escribe, así que no hace falta muestrearlas. Los rankings
 * llevan `JOIN songs` y son las únicas que su consumidor envuelve en `sampledDuringSync`.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val playEventDao: PlayEventDao,
    private val songDao: SongDao
) {
    /** Totales del rango `[since, until)`. Incluye canciones que ya no están en la biblioteca. */
    fun summary(since: Long, until: Long): Flow<StatsSummary> =
        playEventDao.totalsFlow(since, until).map {
            StatsSummary(it.plays, it.listenedMs, it.distinctSongs, it.distinctArtists)
        }

    /** Totales de siempre, desde los agregados. Sin tiempo escuchado: ese dato no existe. */
    fun allTimeSummary(): Flow<StatsSummary> =
        songDao.getAllTimeTotalsFlow().map {
            StatsSummary(it.plays, listenedMs = null, it.distinctSongs, it.distinctArtists)
        }

    fun topSongs(since: Long, until: Long, limit: Int): Flow<List<StatsSongEntry>> =
        playEventDao.topSongsFlow(since, until, limit).map { rows ->
            rows.map { StatsSongEntry(it.song.toSong(), it.plays, it.listenedMs) }
        }

    fun allTimeTopSongs(limit: Int): Flow<List<StatsSongEntry>> =
        songDao.getMostPlayedFlow(limit).map { entities ->
            entities.map { StatsSongEntry(it.toSong(), it.playCount, listenedMs = null) }
        }

    fun topArtists(since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topArtistsFlow(since, until, AppConfig.UNKNOWN_ARTIST, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, it.fallbackArtUri) }
        }

    fun allTimeTopArtists(limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopArtistsFlow(AppConfig.UNKNOWN_ARTIST, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, it.fallbackArtUri) }
        }

    fun topAlbums(since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topAlbumsFlow(since, until, AppConfig.UNKNOWN_ALBUM, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, it.albumArtUri, it.artist) }
        }

    fun allTimeTopAlbums(limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopAlbumsFlow(AppConfig.UNKNOWN_ALBUM, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, it.albumArtUri, it.artist) }
        }

    fun topGenres(since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topGenresFlow(since, until, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, artUri = null) }
        }

    fun allTimeTopGenres(limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopGenresFlow(limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, artUri = null) }
        }

    /** Días con actividad, del más reciente al más antiguo. El recuento de la racha va en Kotlin. */
    fun recentDays(maxDays: Int): Flow<List<String>> = playEventDao.recentDaysFlow(maxDays)

    /**
     * Instante del primer evento registrado, o `null` si el log está vacío. Acota la navegación por
     * periodos y fecha la nota de "Siempre" — sin necesidad de una preferencia nueva que mantener,
     * porque el dato ya está en la tabla.
     */
    fun firstEventAt(): Flow<Long?> = playEventDao.firstEventAtFlow()

    /**
     * Borra el historial ENTERO. Solo lo llama `clearAllUserData`: borrar todos los datos del
     * usuario incluye su historial. `clearSourceData` (el logout de una fuente) NO pasa por aquí, y
     * ésa es la decisión de diseño de [com.qhana.siku.data.local.PlayEventEntity].
     */
    suspend fun clearHistory() = playEventDao.clearAll()
}
