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
    val distinctArtists: Int,
    /**
     * `true` cuando [listenedMs] no está MEDIDO sino derivado de reproducciones x duración, que es
     * lo único que se puede saber del pasado anterior al log de eventos (periodo "Siempre"). La
     * pantalla lo dice en la etiqueta: presentar un estimado como si fuera un dato exacto sería
     * peor que no darlo.
     */
    val listenedIsEstimate: Boolean = false
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

    /**
     * Totales de siempre, desde los agregados de `songs`.
     *
     * El tiempo escuchado va ESTIMADO (ver [SongDao.getAllTimeTotalsFlow]): medido no existe para el
     * pasado anterior al log de eventos. Estuvo en `null` —la pantalla directamente no pintaba la
     * tarjeta— hasta el 28 ago 2026; el criterio de entonces era "no inventar un dato", y sigue
     * valiendo, pero una estimación acotada y ETIQUETADA como tal informa más que un hueco.
     */
    fun allTimeSummary(): Flow<StatsSummary> =
        songDao.getAllTimeTotalsFlow().map {
            StatsSummary(
                plays = it.plays,
                listenedMs = it.estimatedListenedMs,
                distinctSongs = it.distinctSongs,
                distinctArtists = it.distinctArtists,
                listenedIsEstimate = true
            )
        }

    /**
     * Álbumes y géneros distintos del periodo. Van APARTE de [summary] porque llevan JOIN con
     * `songs` y aquélla es de una sola tabla a propósito (ver `PlayEventDao`): mezclarlos volvería
     * sensible al sync un resumen que hoy no lo es.
     */
    fun distinctAlbums(since: Long, until: Long): Flow<Int> =
        playEventDao.distinctAlbumsFlow(since, until, AppConfig.UNKNOWN_ALBUM)

    fun distinctGenres(since: Long, until: Long): Flow<Int> =
        playEventDao.distinctGenresFlow(since, until)

    fun allTimeDistinctAlbums(): Flow<Int> =
        songDao.getAllTimeDistinctAlbumsFlow(AppConfig.UNKNOWN_ALBUM)

    fun allTimeDistinctGenres(): Flow<Int> = songDao.getAllTimeDistinctGenresFlow()

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
     * Escuchas por casilla de tiempo, para el histograma de actividad. Ver
     * [PlayEventDao.activityFlow]: devuelve solo las casillas con actividad, y los huecos los repone
     * el consumidor.
     *
     * **No hay versión de "Siempre"**, y no es un olvido: ese periodo sale de `songs.playCount`, que
     * no sabe CUÁNDO sonó nada. Dibujar ahí el histograma del log sería poner una forma sacada de
     * `play_events` debajo de un total sacado de `songs` — las dos fuentes que esta clase separa a
     * propósito—, y las barras no sumarían el número de encima.
     */
    fun activity(since: Long, until: Long, format: String): Flow<Map<String, Int>> =
        playEventDao.activityFlow(since, until, format).map { rows ->
            rows.associate { it.bucket to it.plays }
        }

    /** Denominador de los porcentajes de género. Ver [PlayEventDao.classifiedPlaysFlow]. */
    fun classifiedPlays(since: Long, until: Long): Flow<Int> =
        playEventDao.classifiedPlaysFlow(since, until)

    fun allTimeClassifiedPlays(): Flow<Int> = songDao.getAllTimeClassifiedPlaysFlow()

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
