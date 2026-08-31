package com.qhana.siku.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Totales de un periodo. Salen SOLO de `play_events`, así que incluyen canciones ya borradas. */
data class PeriodTotals(
    val plays: Int,
    val listenedMs: Long,
    val distinctSongs: Int,
    val distinctArtists: Int
)

/**
 * Una casilla del histograma de actividad. [bucket] es la clave que produjo `strftime` —un día
 * `"2026-08-27"` o un mes `"2026-08"`—, no un índice: quien la coloca en el eje es
 * [com.qhana.siku.ui.viewmodel.ActivityBucketing], que genera esas mismas claves en Kotlin.
 */
data class ActivityRow(
    val bucket: String,
    val plays: Int
)

/** Una canción del ranking, con la fila VIVA embebida para poder reproducirla y pintar su carátula. */
data class TopSongRow(
    @Embedded val song: SongEntity,
    val plays: Int,
    val listenedMs: Long
)

/** Un artista del ranking. `name` sale de la fila viva de `songs`, no del snapshot del evento. */
data class TopArtistRow(
    val name: String,
    val plays: Int,
    val listenedMs: Long,
    val songCount: Int,
    /** Carátula de alguno de sus álbumes: la foto de Deezer se resuelve aparte, como en el browse. */
    val fallbackArtUri: String?
)

/** Un álbum del ranking. */
data class TopAlbumRow(
    val name: String,
    val artist: String,
    val plays: Int,
    val listenedMs: Long,
    val albumArtUri: String?
)

/** Un género del ranking. */
data class TopGenreRow(
    val name: String,
    val plays: Int,
    val listenedMs: Long
)

/**
 * Acceso al log de escuchas (v28). Ver [PlayEventEntity] para por qué la tabla no tiene FK.
 *
 * **Todas las consultas de aquí mencionan una sola tabla, y eso es deliberado**: Room invalida por
 * TABLA, así que al no nombrar `songs` no las re-ejecuta ninguna de las decenas de escrituras por
 * segundo de un sync. Las que SÍ necesitan la biblioteca (los rankings) lo dicen con un JOIN y por
 * eso son las únicas que su consumidor envuelve en `sampledDuringSync`.
 *
 * Rango siempre `[since, until)`: un mes de calendario tiene fin, al revés que una ventana rodante
 * del tipo `now - N`.
 */
@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity)

    /**
     * Repunta los eventos de una canción a otra. Lo llama `SongRepository.mergePlayStats` junto al
     * merge del agregado: sin esto, el dedupe entre fuentes dejaría el historial apuntando a un id
     * que se borra en la línea siguiente. Sin FK, este UPDATE no viola nada.
     */
    @Query("UPDATE play_events SET songId = :winnerId WHERE songId = :loserId")
    suspend fun repointEvents(loserId: String, winnerId: String)

    /** Solo para `clearAllUserData`: borrar TODOS los datos del usuario incluye su historial. */
    @Query("DELETE FROM play_events")
    suspend fun clearAll()

    // ==================== RESUMEN ====================

    /**
     * Totales del periodo. `COUNT(DISTINCT songId)` cuenta por ID, así que una canción borrada y
     * re-indexada con otro id cuenta dos veces; la alternativa (`DISTINCT title||artist`)
     * inventaría una normalización de tags que la app no tiene.
     */
    @Query(
        """
        SELECT COUNT(*) AS plays,
               COALESCE(SUM(playedMs), 0) AS listenedMs,
               COUNT(DISTINCT songId) AS distinctSongs,
               COUNT(DISTINCT artist) AS distinctArtists
        FROM play_events
        WHERE playedAt >= :since AND playedAt < :until
        """
    )
    fun totalsFlow(since: Long, until: Long): Flow<PeriodTotals>

    /** Primer evento registrado: acota la navegación por meses y fecha la nota de "Siempre". */
    @Query("SELECT MIN(playedAt) FROM play_events")
    fun firstEventAtFlow(): Flow<Long?>

    /**
     * Días DISTINTOS con actividad, del más reciente al más antiguo, para calcular la racha.
     *
     * `'localtime'` y no aritmética con un offset fijo: el día es el del que MIRA (mismo
     * razonamiento que `pickGenreChipsOfTheDay`, que descarta `currentTimeMillis / 86_400_000`
     * porque cambiaría a medianoche UTC, o sea a media tarde en Perú), y un offset fijo además se
     * equivocaría si la ventana cruza un cambio de horario de verano.
     *
     * El recuento va en Kotlin y no en SQL: minSdk 26 ⇒ SQLite 3.19 en Android 8, y las window
     * functions llegaron en la 3.25, así que un gaps-and-islands honesto no se puede escribir aquí.
     */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%m-%d', playedAt / 1000, 'unixepoch', 'localtime') AS day
        FROM play_events
        ORDER BY day DESC
        LIMIT :maxDays
        """
    )
    fun recentDaysFlow(maxDays: Int): Flow<List<String>>

    /**
     * Escuchas agrupadas por casilla de tiempo: el histograma de actividad del periodo.
     *
     * [format] es el patrón de `strftime` que decide la RESOLUCIÓN: `'%Y-%m-%d'` para los días de una
     * semana o un mes, `'%Y-%m'` para los meses de un año. Va como parámetro y no como dos consultas
     * gemelas porque lo único que cambia entre ellas es esa cadena; quién elige cuál es
     * [com.qhana.siku.ui.viewmodel.ActivityBucketing], que además sabe generar las MISMAS claves en
     * Kotlin para rellenar los huecos. Los periodos "Día" y "Siempre" no llaman aquí: ver
     * `ActivityBucketing.forMode`.
     *
     * **Devuelve solo las casillas CON actividad**, que es lo que un `GROUP BY` puede dar: un día sin
     * escuchas no tiene filas que agrupar. Las casillas vacías las repone el consumidor, y tienen que
     * reponerse — un histograma al que le faltan los días flojos miente sobre la forma del mes.
     *
     * `'localtime'` por el mismo motivo que [recentDaysFlow]: la casilla es la del que mira.
     *
     * Menciona SOLO `play_events`, así que el sync no la invalida y no necesita muestreo.
     */
    @Query(
        """
        SELECT strftime(:format, playedAt / 1000, 'unixepoch', 'localtime') AS bucket,
               COUNT(*) AS plays
        FROM play_events
        WHERE playedAt >= :since AND playedAt < :until
        GROUP BY bucket
        ORDER BY bucket
        """
    )
    fun activityFlow(since: Long, until: Long, format: String): Flow<List<ActivityRow>>

    /**
     * Reproducciones del periodo cuya canción TIENE género, o sea el denominador de los porcentajes
     * de la sección de géneros.
     *
     * No es [totalsFlow]`.plays`: las canciones sin tag quedan fuera del ranking de géneros, así que
     * también tienen que quedar fuera de lo que suma 100 %. Con el total a secas, los porcentajes de
     * una biblioteca a medio etiquetar sumarían mucho menos de 100 y no habría forma de saber por qué.
     * Mismo filtro que [topGenresFlow], y si uno cambia, cambia el otro.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND s.genre IS NOT NULL AND TRIM(s.genre) != ''
        """
    )
    fun classifiedPlaysFlow(since: Long, until: Long): Flow<Int>

    // ==================== RANKINGS ====================
    // El JOIN con `songs` ES el filtro "solo lo que sigue en la biblioteca", y de paso agrupa por
    // el nombre VIVO: si el usuario re-etiqueta un artista, su historial no se parte en dos.

    @Query(
        """
        SELECT s.*, COUNT(*) AS plays, COALESCE(SUM(e.playedMs), 0) AS listenedMs
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
        GROUP BY e.songId
        ORDER BY plays DESC, listenedMs DESC
        LIMIT :limit
        """
    )
    fun topSongsFlow(since: Long, until: Long, limit: Int): Flow<List<TopSongRow>>

    /**
     * El filtro de nombre va EN EL SQL con [com.qhana.siku.data.config.AppConfig.UNKNOWN_ARTIST],
     * mismo criterio que `SongDao.getTopPlayedArtistFlow`: tener ese filtro escrito en dos sitios
     * distintos fue exactamente el bug del banner de red medida.
     */
    @Query(
        """
        SELECT s.artist AS name,
               COUNT(*) AS plays,
               COALESCE(SUM(e.playedMs), 0) AS listenedMs,
               COUNT(DISTINCT e.songId) AS songCount,
               MAX(s.albumArtUriString) AS fallbackArtUri
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND TRIM(s.artist) != '' AND s.artist != :unknownArtist
        GROUP BY s.artist
        ORDER BY plays DESC, listenedMs DESC
        LIMIT :limit
        """
    )
    fun topArtistsFlow(since: Long, until: Long, unknownArtist: String, limit: Int): Flow<List<TopArtistRow>>

    /**
     * El par `TRIM(album) != ''` + `!= :unknownAlbum COLLATE NOCASE` es
     * [com.qhana.siku.data.config.AppConfig.isUnknownAlbum] llevado a SQL, obligatorio antes de
     * agrupar por álbum: `UNKNOWN_ALBUM` es un string normal y agruparlo mezclaría en un solo
     * "álbum" todos los archivos sin tags.
     */
    @Query(
        """
        SELECT s.album AS name,
               MIN(s.artist) AS artist,
               COUNT(*) AS plays,
               COALESCE(SUM(e.playedMs), 0) AS listenedMs,
               MAX(s.albumArtUriString) AS albumArtUri
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND TRIM(s.album) != '' AND s.album != :unknownAlbum COLLATE NOCASE
        GROUP BY s.album
        ORDER BY plays DESC, listenedMs DESC
        LIMIT :limit
        """
    )
    fun topAlbumsFlow(since: Long, until: Long, unknownAlbum: String, limit: Int): Flow<List<TopAlbumRow>>

    /** `genre` distingue NULL (sin leer) de "" (analizado sin género), igual que `songs.genre`. */
    @Query(
        """
        SELECT s.genre AS name,
               COUNT(*) AS plays,
               COALESCE(SUM(e.playedMs), 0) AS listenedMs
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND s.genre IS NOT NULL AND TRIM(s.genre) != ''
        GROUP BY s.genre
        ORDER BY plays DESC, listenedMs DESC
        LIMIT :limit
        """
    )
    fun topGenresFlow(since: Long, until: Long, limit: Int): Flow<List<TopGenreRow>>

    /**
     * Cuántos ÁLBUMES distintos sonaron en el periodo. Mismo filtro de álbum desconocido que
     * [topAlbumsFlow]: si el ranking no cuenta esos archivos, el número que lo encabeza tampoco.
     *
     * Lleva JOIN, así que —al revés que [totalsFlow]— sí se re-ejecuta con cada escritura en
     * `songs` y su consumidor la muestrea durante el sync, igual que hace con los rankings.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT s.album)
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND TRIM(s.album) != '' AND s.album != :unknownAlbum COLLATE NOCASE
        """
    )
    fun distinctAlbumsFlow(since: Long, until: Long, unknownAlbum: String): Flow<Int>

    /** Cuántos GÉNEROS distintos sonaron en el periodo. Mismo filtro que [topGenresFlow]. */
    @Query(
        """
        SELECT COUNT(DISTINCT s.genre)
        FROM play_events e JOIN songs s ON s.id = e.songId
        WHERE e.playedAt >= :since AND e.playedAt < :until
          AND s.genre IS NOT NULL AND TRIM(s.genre) != ''
        """
    )
    fun distinctGenresFlow(since: Long, until: Long): Flow<Int>
}
