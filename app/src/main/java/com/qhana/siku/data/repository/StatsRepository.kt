package com.qhana.siku.data.repository

import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.PlayEventDao
import com.qhana.siku.data.local.ScopeChildRow
import com.qhana.siku.data.local.ScopeTotals
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.local.StatsScopeQueries
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.StatsScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

/**
 * Totales de UN ámbito (artista, álbum o género) en un periodo.
 *
 * No es [StatsSummary] con otro nombre: ahí la segunda dimensión son los artistas distintos, que
 * dentro de un artista vale siempre 1. Aquí es [distinctSecondary], y qué cuenta lo decide el
 * ámbito (`StatsScope.secondaryCount`): los álbumes de un artista, los artistas de un álbum o de un
 * género.
 *
 * [listenedMs] no es nullable, al revés que en [StatsSummary]: el periodo "Siempre" de esta pantalla
 * sí trae un número —la misma estimación acotada que la pantalla general— y [listenedIsEstimate]
 * dice cuándo lo es.
 */
data class ScopeStatsSummary(
    val plays: Int,
    val listenedMs: Long,
    val distinctSongs: Int,
    val distinctSecondary: Int,
    val listenedIsEstimate: Boolean = false
) {
    val isEmpty: Boolean get() = plays == 0
}

/** Una entrada del ranking de artistas, álbumes o géneros. */
data class StatsNameEntry(
    val name: String,
    val plays: Int,
    val listenedMs: Long?,
    /** Carátula o foto con la que pintarla; `null` cae al placeholder de su categoría. */
    val artUri: String?,
    /** Línea secundaria: el artista de un álbum. `null` en artistas y géneros. */
    val secondary: String? = null,
    /**
     * La otra mitad de la identidad de un ÁLBUM (v29): `""` sin tag. Viaja hasta la navegación para
     * que tocar un "Greatest Hits" abra ESE disco y no la mezcla de todos los homónimos. `null` en
     * artistas y géneros.
     */
    val albumArtist: String? = null
) {
    /** Clave estable para una lista: el nombre no basta con dos álbumes homónimos. */
    val key: String get() = albumArtist?.let { com.qhana.siku.data.local.albumIdentityKey(name, it) } ?: name
}

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

    fun topArtists(role: ArtistCreditRole, since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topArtistsFlow(
            StatsScopeQueries.periodTopArtists(role, since, until, AppConfig.UNKNOWN_ARTIST, limit)
        ).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, it.fallbackArtUri) }
        }

    fun allTimeTopArtists(role: ArtistCreditRole, limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopArtistsFlow(
            StatsScopeQueries.allTimeTopArtists(role, AppConfig.UNKNOWN_ARTIST, limit)
        ).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, it.fallbackArtUri) }
        }

    fun topAlbums(since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topAlbumsFlow(since, until, AppConfig.UNKNOWN_ALBUM, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, it.albumArtUri, it.artist, it.albumArtist) }
        }

    fun allTimeTopAlbums(limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopAlbumsFlow(AppConfig.UNKNOWN_ALBUM, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, it.albumArtUri, it.artist, it.albumArtist) }
        }

    fun topGenres(since: Long, until: Long, limit: Int): Flow<List<StatsNameEntry>> =
        playEventDao.topGenresFlow(since, until, limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, it.listenedMs, artUri = null) }
        }

    fun allTimeTopGenres(limit: Int): Flow<List<StatsNameEntry>> =
        songDao.getAllTimeTopGenresFlow(limit).map { rows ->
            rows.map { StatsNameEntry(it.name, it.plays, listenedMs = null, artUri = null) }
        }

    // ==================== UN SOLO ÁMBITO ====================
    // La misma pareja de fuentes que arriba —log de eventos para los periodos de calendario,
    // agregados de `songs` para "Siempre"— acotada a un artista, un álbum o un género. NO se
    // unifican, por lo mismo que explica el KDoc de la clase: "Siempre" es el único que lleva el
    // pasado anterior al log.
    //
    // El SQL de las dos fuentes lo arma `StatsScopeQueries`, no estas funciones: aquí solo se
    // decide de qué DAO sale cada una y cómo se mapea la fila.

    fun scopeSummary(scope: StatsScope, since: Long, until: Long): Flow<ScopeStatsSummary> =
        playEventDao.scopeTotalsFlow(
            StatsScopeQueries.periodTotals(
                scope, since, until, AppConfig.UNKNOWN_ALBUM, AppConfig.UNKNOWN_ARTIST
            )
        ).map { it.toSummary(isEstimate = false) }

    fun allTimeScopeSummary(scope: StatsScope): Flow<ScopeStatsSummary> =
        songDao.scopeTotalsFlow(
            StatsScopeQueries.allTimeTotals(scope, AppConfig.UNKNOWN_ALBUM, AppConfig.UNKNOWN_ARTIST)
        ).map { it.toSummary(isEstimate = true) }

    fun scopeTopSongs(
        scope: StatsScope,
        since: Long,
        until: Long,
        limit: Int
    ): Flow<List<StatsSongEntry>> =
        playEventDao.scopeTopSongsFlow(StatsScopeQueries.periodTopSongs(scope, since, until, limit))
            .map { rows -> rows.map { StatsSongEntry(it.song.toSong(), it.plays, it.listenedMs) } }

    /**
     * `listenedMs = null` y no el `0` que trae la consulta: en este periodo el tiempo por canción no
     * se midió nunca, y un cero se leería como "no la escuchaste".
     */
    fun allTimeScopeTopSongs(scope: StatsScope, limit: Int): Flow<List<StatsSongEntry>> =
        songDao.scopeTopSongsFlow(StatsScopeQueries.allTimeTopSongs(scope, limit))
            .map { rows -> rows.map { StatsSongEntry(it.song.toSong(), it.plays, listenedMs = null) } }

    /**
     * Las sub-entidades del ámbito. **Flujo vacío cuando no tiene ninguna** (un álbum): así el
     * consumidor no necesita saber cuáles la tienen — lo dice `StatsScope.child`, en un sitio.
     */
    fun scopeChildren(
        scope: StatsScope,
        since: Long,
        until: Long,
        limit: Int
    ): Flow<List<StatsNameEntry>> {
        val query = StatsScopeQueries.periodTopChildren(
            scope, since, until, AppConfig.UNKNOWN_ALBUM, AppConfig.UNKNOWN_ARTIST, limit
        ) ?: return flowOf(emptyList())
        return playEventDao.scopeChildrenFlow(query).map { rows -> rows.map { it.toEntry() } }
    }

    fun allTimeScopeChildren(scope: StatsScope, limit: Int): Flow<List<StatsNameEntry>> {
        val query = StatsScopeQueries.allTimeTopChildren(
            scope, AppConfig.UNKNOWN_ALBUM, AppConfig.UNKNOWN_ARTIST, limit
        ) ?: return flowOf(emptyList())
        return songDao.scopeChildrenFlow(query)
            .map { rows -> rows.map { it.toEntry().copy(listenedMs = null) } }
    }

    /** Histograma de actividad del ámbito. Ver [activity]: tampoco hay versión de "Siempre". */
    fun scopeActivity(
        scope: StatsScope,
        since: Long,
        until: Long,
        format: String
    ): Flow<Map<String, Int>> =
        playEventDao.scopeActivityFlow(StatsScopeQueries.periodActivity(scope, since, until, format))
            .map { rows -> rows.associate { it.bucket to it.plays } }

    private fun ScopeTotals.toSummary(isEstimate: Boolean) = ScopeStatsSummary(
        plays = plays,
        listenedMs = listenedMs,
        distinctSongs = distinctSongs,
        distinctSecondary = distinctSecondary,
        listenedIsEstimate = isEstimate
    )

    private fun ScopeChildRow.toEntry() =
        StatsNameEntry(name, plays, listenedMs, artUri, secondary, albumArtist)

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
