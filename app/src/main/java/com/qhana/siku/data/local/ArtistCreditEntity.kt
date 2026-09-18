package com.qhana.siku.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qhana.siku.data.model.ArtistCreditRole
import kotlinx.coroutines.flow.Flow

/**
 * A qué artistas pertenece cada canción (v29): una fila por (canción, rol, artista).
 *
 * **Es una tabla DERIVADA**, y eso decide todo lo demás. Su contenido es una función de tres columnas
 * de `songs` (`artist`, `artistCredits`, `albumArtist`) y de un ajuste del usuario (la separación de
 * colaboraciones), y existe porque desde que una canción puede ser de VARIOS artistas la pestaña
 * Artistas ya no cabe en un `GROUP BY songs.artist`. La regla de a quién pertenece una canción vive
 * en `ArtistCredits` (`:core`) y en ningún otro sitio.
 *
 * Los dos roles ([ArtistCreditRole]) se guardan SIEMPRE, los dos: el ajuste "agrupar por artista del
 * álbum" solo elige cuál se lee. Así cambiarlo es instantáneo y no obliga a recalcular nada — solo la
 * separación de colaboraciones, que sí cambia el contenido, re-encola la biblioteca.
 *
 * **Quién la escribe: UN solo productor**, `ArtistCreditIndexer`. Y quién le avisa: triggers de SQLite
 * sobre `songs` (ver [ArtistCreditTriggers]), no los caminos de escritura de la app. Hay media docena
 * de sitios que escriben `songs.artist` —el upsert del escaneo, la metadata ligera, el análisis tras
 * descargar, la migración de ids locales…— y confiar en que cada uno se acuerde de avisar es
 * exactamente el tipo de invariante que se rompe con el siguiente camino que alguien añada.
 *
 * **Sin foreign key**, igual que `play_events` aunque por otro motivo: el borrado lo hace un trigger,
 * que no depende de `PRAGMA foreign_keys` ni de que un `REPLACE` dispare o no el CASCADE.
 */
@Entity(
    tableName = "song_artists",
    primaryKeys = ["songId", "role", "name"],
    // Toda lectura de la biblioteca filtra por rol y agrupa o busca por nombre.
    indices = [Index(value = ["role", "name"])]
)
data class ArtistCreditEntity(
    val songId: String,
    /** [ArtistCreditRole.role]. */
    val role: Int,
    val name: String
)

/**
 * Canciones cuyos créditos hay que (re)calcular. La llenan los triggers de [ArtistCreditTriggers] y la
 * vacía `ArtistCreditIndexer`.
 *
 * [seq] resuelve la única carrera del diseño: el indexador lee la canción, calcula y borra su fila de
 * la cola; si entre la lectura y el borrado la canción vuelve a cambiar, el trigger la re-encola con
 * un [seq] mayor, y el borrado —que exige el [seq] que se leyó— no la toca. Sin él, ese cambio se
 * perdería y la canción quedaría con los créditos viejos hasta el siguiente cambio de tags.
 */
@Entity(tableName = "artist_credit_queue")
data class ArtistCreditQueueEntity(
    @PrimaryKey val songId: String,
    val seq: Long
)

/** Lo que hace falta de una canción para calcular sus créditos, sin traerse la fila entera. */
data class ArtistCreditSource(
    val id: String,
    val artist: String,
    val albumArtist: String?,
    val artistCredits: String?
)

/**
 * Los triggers que mantienen la cola. Viven aquí, en UN sitio, porque se crean desde DOS: la
 * migración 28→29 (quien actualiza) y el `onCreate` de la base (quien instala de cero). Room no
 * gestiona triggers propios, así que si una de las dos rutas los olvidara, esa población de usuarios
 * se quedaría con una pestaña Artistas que no se entera de nada — sin error visible.
 *
 * Room tampoco los VALIDA al abrir (compara columnas, claves e índices), así que convivir con ellos no
 * rompe la validación de esquema.
 */
object ArtistCreditTriggers {

    /** Encola con un `seq` mayor que cualquiera vivo. Ver [ArtistCreditQueueEntity.seq]. */
    private const val ENQUEUE_NEW =
        "INSERT OR REPLACE INTO artist_credit_queue (songId, seq) " +
            "VALUES (NEW.id, (SELECT IFNULL(MAX(seq), 0) + 1 FROM artist_credit_queue))"

    val STATEMENTS: List<String> = listOf(
        "CREATE TRIGGER IF NOT EXISTS song_artists_on_insert AFTER INSERT ON songs " +
            "BEGIN $ENQUEUE_NEW; END",
        // `UPDATE OF` + `WHEN`: los updates de metadata reescriben `artist` con el mismo valor muy a
        // menudo, y el recuento de reproducciones o la carátula no tocan ninguna de estas columnas.
        // Sin el `WHEN` cada análisis re-encolaría canciones que no cambiaron.
        "CREATE TRIGGER IF NOT EXISTS song_artists_on_update " +
            "AFTER UPDATE OF artist, albumArtist, artistCredits ON songs " +
            "WHEN OLD.artist IS NOT NEW.artist OR OLD.albumArtist IS NOT NEW.albumArtist " +
            "OR OLD.artistCredits IS NOT NEW.artistCredits " +
            "BEGIN $ENQUEUE_NEW; END",
        "CREATE TRIGGER IF NOT EXISTS song_artists_on_delete AFTER DELETE ON songs " +
            "BEGIN " +
            "DELETE FROM song_artists WHERE songId = OLD.id; " +
            "DELETE FROM artist_credit_queue WHERE songId = OLD.id; " +
            "END"
    )

    fun create(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}

/**
 * El SQL que LEE los créditos, en un solo sitio: lo comparten la pestaña Artistas, el detalle, sus
 * álbumes y las estadísticas de un artista. Si cada consumidor escribiera su propia versión, dos de
 * ellos podrían no estar de acuerdo sobre qué canciones son de quién — y el número de la tarjeta no
 * sería el de la lista que se abre.
 *
 * Todo va con `bind`; lo único que se concatena son estos fragmentos fijos.
 */
object ArtistCreditSql {

    /**
     * Las filas (songId, name) del [role], **más las canciones que aún no tienen ninguna** con su
     * `artist` de siempre.
     *
     * Esa segunda mitad no es un parche de transición, es lo que hace correcta a la tabla derivada:
     * entre que una canción se inserta y el indexador la procesa pasan milisegundos —o segundos, al
     * actualizar una biblioteca grande—, y sin ella la canción no existiría para la pestaña Artistas en
     * ese intervalo. Con ella, lo peor que se ve es la agrupación de antes, un instante.
     *
     * Args: `role, role`.
     */
    const val ROWS =
        "(SELECT songId, name FROM song_artists WHERE role = ? " +
            "UNION ALL SELECT id AS songId, artist AS name FROM songs " +
            "WHERE id NOT IN (SELECT songId FROM song_artists WHERE role = ?))"

    fun rowsArgs(role: ArtistCreditRole): List<Any> = listOf(role.role, role.role)

    /**
     * Predicado "esta canción es de [name]" sobre el alias de `songs` dado (`"s."` o `""`).
     *
     * La segunda rama —el `artist` exacto cuando [name] no es ningún crédito— es la que mantiene vivas
     * las entradas que no salen de la pestaña: el inicio, el reproductor o las estadísticas navegan con
     * el `artist` tal cual está en la fila, y con la separación activa "A feat. B" ya no es el nombre de
     * ningún artista. Sin esa rama, tocar al artista desde el reproductor abriría un detalle vacío.
     * Solo entra cuando el nombre NO es un crédito, para que el número de la tarjeta de la pestaña siga
     * siendo el de la lista.
     *
     * Devuelve el fragmento y sus args, en el orden de sus `?`.
     */
    fun songsOf(name: String, role: ArtistCreditRole, alias: String): Pair<String, List<Any>> =
        "(${alias}id IN (SELECT songId FROM $ROWS WHERE name = ?) " +
            "OR (${alias}artist = ? AND NOT EXISTS (SELECT 1 FROM $ROWS WHERE name = ?)))" to
            rowsArgs(role) + name + name + rowsArgs(role) + name
}

@Dao
interface ArtistCreditDao {

    /** Cuántas canciones esperan créditos. Observa SOLO la cola, así que el resto del sync no lo despierta. */
    @Query("SELECT COUNT(*) FROM artist_credit_queue")
    fun pendingCountFlow(): Flow<Int>

    @Query("SELECT songId, seq FROM artist_credit_queue LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<ArtistCreditQueueEntity>

    @Query("SELECT id, artist, albumArtist, artistCredits FROM songs WHERE id IN (:ids)")
    suspend fun sources(ids: List<String>): List<ArtistCreditSource>

    /**
     * Re-encola la biblioteca entera: la separación de colaboraciones cambió y con ella el contenido
     * de todos los créditos. Un único `seq` para todas —la subconsulta no está correlacionada—, mayor
     * que cualquiera en curso.
     */
    @Query(
        "INSERT OR REPLACE INTO artist_credit_queue (songId, seq) " +
            "SELECT id, (SELECT IFNULL(MAX(seq), 0) + 1 FROM artist_credit_queue) FROM songs"
    )
    suspend fun enqueueAll()

    @Query("DELETE FROM song_artists WHERE songId IN (:ids)")
    suspend fun deleteCredits(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCredits(credits: List<ArtistCreditEntity>)

    @Query("DELETE FROM artist_credit_queue WHERE songId = :songId AND seq = :seq")
    suspend fun dequeue(songId: String, seq: Long)

    /** Créditos de canciones del lote que ya no existen. Ver [applyBatch]. */
    @Query("DELETE FROM song_artists WHERE songId IN (:ids) AND songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphanCredits(ids: List<String>)

    /**
     * Aplica un lote en UNA transacción: créditos viejos fuera, nuevos dentro y la cola descontada.
     *
     * El último paso cubre la carrera del borrado: si una canción desaparece entre que el indexador la
     * lee y este lote se aplica, sus créditos recién calculados no tienen ya a quién pertenecer — el
     * trigger de borrado corrió ANTES de que existieran.
     */
    @Transaction
    suspend fun applyBatch(
        processed: List<ArtistCreditQueueEntity>,
        credits: List<ArtistCreditEntity>
    ) {
        val ids = processed.map { it.songId }
        deleteCredits(ids)
        if (credits.isNotEmpty()) insertCredits(credits)
        processed.forEach { dequeue(it.songId, it.seq) }
        deleteOrphanCredits(ids)
    }
}
