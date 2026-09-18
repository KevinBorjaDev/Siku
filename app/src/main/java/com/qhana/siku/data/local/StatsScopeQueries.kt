package com.qhana.siku.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.StatsScope
import com.qhana.siku.data.model.StatsScopeChild
import com.qhana.siku.data.model.child
import com.qhana.siku.data.model.secondaryCount

/** Totales de un ámbito, medidos o estimados según de dónde salgan. Ver [StatsScopeQueries]. */
data class ScopeTotals(
    val plays: Int,
    val listenedMs: Long,
    val distinctSongs: Int,
    /** Álbumes o artistas distintos, según el ámbito (ver `StatsScope.secondaryCount`). */
    val distinctSecondary: Int
)

/**
 * Una sub-entidad del ámbito: un álbum de un artista, un artista de un género.
 *
 * [secondary] es la línea de apoyo de la tarjeta —el artista de un álbum— y llega `null` cuando la
 * sub-entidad es un artista, que no tiene ninguna. [albumArtist] es la otra mitad de la identidad de
 * un álbum (v29, `""` sin tag); `null` en un artista.
 */
data class ScopeChildRow(
    val name: String,
    val secondary: String?,
    val albumArtist: String?,
    val plays: Int,
    val listenedMs: Long,
    val artUri: String?
)

/**
 * Las consultas de estadísticas que dependen de un ÁMBITO o de la agrupación de artistas, construidas
 * a partir de su [StatsScope].
 *
 * **Por qué van en `RawQuery` y no en `@Query`.** Lo que cambia entre artista, álbum y género es el
 * predicado y la forma de agrupar, y ni una columna de `GROUP BY`, ni un `LIKE` opcional, ni el
 * fragmento de créditos (`ArtistCreditSql`) se pueden pasar como `bind`. Es el mismo criterio que ya
 * siguen `SongDao.buildPagingQuery` y `buildArtistsQuery`.
 *
 * Lo que se paga a cambio es la verificación de SQL en tiempo de compilación que Room hace sobre
 * `@Query`, y por eso el SQL está TODO aquí y en ningún otro sitio: un fallo de sintaxis solo se ve
 * al abrir la pantalla, así que al menos se ve entero de una vez.
 *
 * **El orden de los binds es el orden en que los `?` aparecen en el SQL.** Un `?` dentro del `SELECT`
 * o de un `JOIN` va antes que los del `WHERE`; equivocarlo no rompe la consulta, la hace devolver otra
 * cosa. Cada función arma sus args en el orden del texto, y así debe seguir.
 *
 * Desde la v29 los ARTISTAS se cuentan por CRÉDITOS (una canción de dos artistas suma para los dos) y
 * los ÁLBUMES por (nombre, artista del álbum): las mismas identidades que la biblioteca, para que el
 * número de una tarjeta de estadísticas sea el del detalle que abre.
 */
internal object StatsScopeQueries {

    /**
     * El predicado del ámbito sobre el alias dado, más sus argumentos.
     *
     * [alias] es `"s."`/`"s2."` en las consultas con alias y `""` en las que van directas a `songs`.
     * Los criterios son los MISMOS que usan las pantallas de detalle — si dejaran de coincidir, el
     * detalle y sus estadísticas contarían conjuntos distintos de canciones.
     */
    private fun predicate(scope: StatsScope, alias: String): Pair<String, List<Any>> = when (scope) {
        is StatsScope.Artist -> ArtistCreditSql.songsOf(scope.name, scope.artistRole, alias)
        is StatsScope.Album -> SongDao.albumPredicate(scope.name, scope.albumArtist, alias)
        is StatsScope.Genre ->
            if (scope.partialMatch) {
                "${alias}genre LIKE ? ESCAPE '\\'" to listOf("%${SongDao.escapeLike(scope.name)}%")
            } else {
                "${alias}genre = ? COLLATE NOCASE" to listOf(scope.name)
            }
    }

    /**
     * "Sin álbum" no es un álbum: `UNKNOWN_ALBUM` es un string normal, así que sin este filtro todos
     * los archivos sin tags se fundirían en uno. Un `?`: el sentinel.
     */
    private fun albumFilter(alias: String): String =
        "TRIM(${alias}album) != '' AND ${alias}album != ? COLLATE NOCASE"

    /** Lo mismo para un crédito de artista. Un `?`: el sentinel. */
    private const val CREDIT_FILTER = "TRIM(c.name) != '' AND c.name != ? COLLATE NOCASE"

    /** Clave de álbum dentro de un `COUNT(DISTINCT ...)`: nombre + artista del álbum, sin mayúsculas. */
    private fun albumCountKey(alias: String): String =
        "${alias}album || char(31) || LOWER(IFNULL(${alias}albumArtist, ''))"

    /** El artista a MOSTRAR de un álbum agrupado: el del álbum si hay tag, si no el de sus pistas. */
    private fun albumDisplayArtist(alias: String): String =
        "CASE WHEN IFNULL(${alias}albumArtist, '') != '' " +
            "THEN REPLACE(${alias}albumArtist, char(31), ', ') ELSE MIN(${alias}artist) END"

    private fun albumGroupBy(alias: String): String =
        "GROUP BY ${alias}album, IFNULL(${alias}albumArtist, '') COLLATE NOCASE"

    /**
     * La segunda magnitud del resumen y sus args, que van ANTES que los del `FROM`/`WHERE` externo
     * porque la expresión vive en el `SELECT`.
     *
     * [scopedSongIds] es una subconsulta que devuelve los ids de las canciones que cuentan (escuchas
     * del periodo o canciones con historial) escrita sobre el alias `s2.`, con sus args. La necesitan
     * los ARTISTAS: por créditos, una canción aporta varias filas, y contarlos con un `JOIN` en la
     * consulta principal multiplicaría las reproducciones.
     */
    private fun secondaryExpr(
        scope: StatsScope,
        alias: String,
        scopedSongIds: Pair<String, List<Any>>,
        unknownAlbum: String,
        unknownArtist: String
    ): Pair<String, List<Any>> = when (scope.secondaryCount) {
        StatsScopeChild.ALBUMS ->
            // Con un `CASE` y no llevándolo al `WHERE`: allí excluiría del conteo de reproducciones a
            // las canciones sin ese tag, que sí sonaron.
            "COUNT(DISTINCT CASE WHEN ${albumFilter(alias)} THEN ${albumCountKey(alias)} END)" to
                listOf(unknownAlbum)
        StatsScopeChild.ARTISTS ->
            "(SELECT COUNT(DISTINCT c.name) FROM ${ArtistCreditSql.ROWS} c " +
                "WHERE c.songId IN (${scopedSongIds.first}) AND $CREDIT_FILTER)" to
                ArtistCreditSql.rowsArgs(scope.artistRole) + scopedSongIds.second + unknownArtist
        StatsScopeChild.NONE -> "0" to emptyList<Any>()
    }

    // ==================== PERIODO (log de eventos) ====================

    fun periodTotals(
        scope: StatsScope,
        since: Long,
        until: Long,
        unknownAlbum: String,
        unknownArtist: String
    ): SimpleSQLiteQuery {
        val (where, whereArgs) = predicate(scope, "s.")
        val (where2, where2Args) = predicate(scope, "s2.")
        val scopedSongIds =
            "SELECT e2.songId FROM play_events e2 JOIN songs s2 ON s2.id = e2.songId " +
                "WHERE e2.playedAt >= ? AND e2.playedAt < ? AND $where2" to
                listOf<Any>(since, until) + where2Args
        val (secondary, secondaryArgs) = secondaryExpr(scope, "s.", scopedSongIds, unknownAlbum, unknownArtist)
        return SimpleSQLiteQuery(
            """
            SELECT COUNT(*) AS plays,
                   COALESCE(SUM(e.playedMs), 0) AS listenedMs,
                   COUNT(DISTINCT e.songId) AS distinctSongs,
                   $secondary AS distinctSecondary
            FROM play_events e JOIN songs s ON s.id = e.songId
            WHERE e.playedAt >= ? AND e.playedAt < ? AND $where
            """.trimIndent(),
            (secondaryArgs + since + until + whereArgs).toTypedArray()
        )
    }

    fun periodTopSongs(
        scope: StatsScope,
        since: Long,
        until: Long,
        limit: Int
    ): SimpleSQLiteQuery {
        val (where, whereArgs) = predicate(scope, "s.")
        return SimpleSQLiteQuery(
            """
            SELECT s.*, COUNT(*) AS plays, COALESCE(SUM(e.playedMs), 0) AS listenedMs
            FROM play_events e JOIN songs s ON s.id = e.songId
            WHERE e.playedAt >= ? AND e.playedAt < ? AND $where
            GROUP BY e.songId
            ORDER BY plays DESC, listenedMs DESC
            LIMIT ?
            """.trimIndent(),
            (listOf<Any>(since, until) + whereArgs + limit).toTypedArray()
        )
    }

    /**
     * Las sub-entidades del ámbito en el periodo. `null` cuando no tiene ninguna: quien llama no
     * debe inventarse una consulta vacía, tiene que NO preguntar.
     */
    fun periodTopChildren(
        scope: StatsScope,
        since: Long,
        until: Long,
        unknownAlbum: String,
        unknownArtist: String,
        limit: Int
    ): SimpleSQLiteQuery? {
        val (where, whereArgs) = predicate(scope, "s.")
        return when (scope.child) {
            StatsScopeChild.NONE -> null
            StatsScopeChild.ALBUMS -> SimpleSQLiteQuery(
                """
                SELECT s.album AS name,
                       ${albumDisplayArtist("s.")} AS secondary,
                       IFNULL(s.albumArtist, '') AS albumArtist,
                       COUNT(*) AS plays,
                       COALESCE(SUM(e.playedMs), 0) AS listenedMs,
                       MAX(s.albumArtUriString) AS artUri
                FROM play_events e JOIN songs s ON s.id = e.songId
                WHERE e.playedAt >= ? AND e.playedAt < ? AND $where AND ${albumFilter("s.")}
                ${albumGroupBy("s.")}
                ORDER BY plays DESC, listenedMs DESC
                LIMIT ?
                """.trimIndent(),
                (listOf<Any>(since, until) + whereArgs + unknownAlbum + limit).toTypedArray()
            )
            StatsScopeChild.ARTISTS -> SimpleSQLiteQuery(
                """
                SELECT c.name AS name,
                       NULL AS secondary,
                       NULL AS albumArtist,
                       COUNT(*) AS plays,
                       COALESCE(SUM(e.playedMs), 0) AS listenedMs,
                       MAX(s.albumArtUriString) AS artUri
                FROM play_events e JOIN songs s ON s.id = e.songId
                JOIN ${ArtistCreditSql.ROWS} c ON c.songId = e.songId
                WHERE e.playedAt >= ? AND e.playedAt < ? AND $where AND $CREDIT_FILTER
                GROUP BY c.name
                ORDER BY plays DESC, listenedMs DESC
                LIMIT ?
                """.trimIndent(),
                (ArtistCreditSql.rowsArgs(scope.artistRole) + since + until + whereArgs + unknownArtist + limit)
                    .toTypedArray()
            )
        }
    }

    /**
     * El histograma del periodo. Ver `PlayEventDao.activityFlow` para qué es [format] y por qué
     * `'localtime'`.
     */
    fun periodActivity(
        scope: StatsScope,
        since: Long,
        until: Long,
        format: String
    ): SimpleSQLiteQuery {
        val (where, whereArgs) = predicate(scope, "s.")
        return SimpleSQLiteQuery(
            """
            SELECT strftime(?, e.playedAt / 1000, 'unixepoch', 'localtime') AS bucket,
                   COUNT(*) AS plays
            FROM play_events e JOIN songs s ON s.id = e.songId
            WHERE e.playedAt >= ? AND e.playedAt < ? AND $where
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent(),
            (listOf<Any>(format, since, until) + whereArgs).toTypedArray()
        )
    }

    // ==================== RANKING GENERAL DE ARTISTAS ====================
    // Aquí y no como `@Query` en los DAO por lo mismo que el resto: el fragmento de créditos no es un
    // bind. Una escucha de una canción con dos artistas suma para los dos, que es lo que dice la
    // pestaña Artistas; por eso los puestos pueden sumar más reproducciones que el total del periodo.

    fun periodTopArtists(
        role: ArtistCreditRole,
        since: Long,
        until: Long,
        unknownArtist: String,
        limit: Int
    ): SimpleSQLiteQuery = SimpleSQLiteQuery(
        """
        SELECT c.name AS name,
               COUNT(*) AS plays,
               COALESCE(SUM(e.playedMs), 0) AS listenedMs,
               COUNT(DISTINCT e.songId) AS songCount,
               MAX(s.albumArtUriString) AS fallbackArtUri
        FROM play_events e JOIN songs s ON s.id = e.songId
        JOIN ${ArtistCreditSql.ROWS} c ON c.songId = e.songId
        WHERE e.playedAt >= ? AND e.playedAt < ? AND $CREDIT_FILTER
        GROUP BY c.name
        ORDER BY plays DESC, listenedMs DESC
        LIMIT ?
        """.trimIndent(),
        (ArtistCreditSql.rowsArgs(role) + since + until + unknownArtist + limit).toTypedArray()
    )

    fun allTimeTopArtists(role: ArtistCreditRole, unknownArtist: String, limit: Int): SimpleSQLiteQuery =
        SimpleSQLiteQuery(
            """
            SELECT c.name AS name,
                   SUM(s.playCount) AS plays,
                   COUNT(*) AS songCount,
                   MAX(s.albumArtUriString) AS fallbackArtUri
            FROM songs s JOIN ${ArtistCreditSql.ROWS} c ON c.songId = s.id
            WHERE s.playCount > 0 AND $CREDIT_FILTER
            GROUP BY c.name
            ORDER BY plays DESC, songCount DESC
            LIMIT ?
            """.trimIndent(),
            (ArtistCreditSql.rowsArgs(role) + unknownArtist + limit).toTypedArray()
        )

    // ==================== "SIEMPRE" (agregados de `songs`) ====================
    // Otra fuente, y la separación es deliberada: el log de eventos empieza el día de la v28, así
    // que sólo estos agregados llevan el pasado anterior. A cambio, el tiempo va ESTIMADO
    // (reproducciones x duración) — ver `SongDao.getAllTimeTotalsFlow` para el sesgo, que está
    // acotado, y la pantalla lo etiqueta como aproximado.

    fun allTimeTotals(
        scope: StatsScope,
        unknownAlbum: String,
        unknownArtist: String
    ): SimpleSQLiteQuery {
        val (where, whereArgs) = predicate(scope, "s.")
        val (where2, where2Args) = predicate(scope, "s2.")
        val scopedSongIds = "SELECT s2.id FROM songs s2 WHERE s2.playCount > 0 AND $where2" to where2Args
        val (secondary, secondaryArgs) = secondaryExpr(scope, "s.", scopedSongIds, unknownAlbum, unknownArtist)
        return SimpleSQLiteQuery(
            """
            SELECT COALESCE(SUM(s.playCount), 0) AS plays,
                   COALESCE(SUM(s.playCount * s.duration), 0) AS listenedMs,
                   COUNT(*) AS distinctSongs,
                   $secondary AS distinctSecondary
            FROM songs s WHERE s.playCount > 0 AND $where
            """.trimIndent(),
            (secondaryArgs + whereArgs).toTypedArray()
        )
    }

    /**
     * Las canciones más reproducidas de siempre del ámbito.
     *
     * `0 AS listenedMs` y no una estimación por canción: el repositorio devuelve `null` en este
     * periodo, porque una cifra por fila que nadie midió invita a compararla con la del periodo de
     * al lado, que sí está medida.
     */
    fun allTimeTopSongs(scope: StatsScope, limit: Int): SimpleSQLiteQuery {
        val (where, whereArgs) = predicate(scope, "")
        return SimpleSQLiteQuery(
            """
            SELECT *, playCount AS plays, 0 AS listenedMs
            FROM songs WHERE playCount > 0 AND $where
            ORDER BY plays DESC, lastPlayedAt DESC, title COLLATE NOCASE ASC
            LIMIT ?
            """.trimIndent(),
            (whereArgs + limit).toTypedArray()
        )
    }

    fun allTimeTopChildren(
        scope: StatsScope,
        unknownAlbum: String,
        unknownArtist: String,
        limit: Int
    ): SimpleSQLiteQuery? {
        val (where, whereArgs) = predicate(scope, "s.")
        return when (scope.child) {
            StatsScopeChild.NONE -> null
            StatsScopeChild.ALBUMS -> SimpleSQLiteQuery(
                """
                SELECT s.album AS name,
                       ${albumDisplayArtist("s.")} AS secondary,
                       IFNULL(s.albumArtist, '') AS albumArtist,
                       SUM(s.playCount) AS plays,
                       0 AS listenedMs,
                       MAX(s.albumArtUriString) AS artUri
                FROM songs s
                WHERE s.playCount > 0 AND $where AND ${albumFilter("s.")}
                ${albumGroupBy("s.")}
                ORDER BY plays DESC, name COLLATE NOCASE ASC
                LIMIT ?
                """.trimIndent(),
                (whereArgs + unknownAlbum + limit).toTypedArray()
            )
            StatsScopeChild.ARTISTS -> SimpleSQLiteQuery(
                """
                SELECT c.name AS name,
                       NULL AS secondary,
                       NULL AS albumArtist,
                       SUM(s.playCount) AS plays,
                       0 AS listenedMs,
                       MAX(s.albumArtUriString) AS artUri
                FROM songs s JOIN ${ArtistCreditSql.ROWS} c ON c.songId = s.id
                WHERE s.playCount > 0 AND $where AND $CREDIT_FILTER
                GROUP BY c.name
                ORDER BY plays DESC, name COLLATE NOCASE ASC
                LIMIT ?
                """.trimIndent(),
                (ArtistCreditSql.rowsArgs(scope.artistRole) + whereArgs + unknownArtist + limit).toTypedArray()
            )
        }
    }
}
