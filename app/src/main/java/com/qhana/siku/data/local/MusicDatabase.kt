package com.qhana.siku.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versión del esquema. Top-level y no en el companion porque `@Database` no puede leer una
 * constante de la clase que ella misma anota.
 *
 * v27: `songs.lightTagsAttemptedAt` (sello de la metadata ligera sin tags).
 * v28: tabla `play_events` (log de escuchas con fecha; ver [PlayEventEntity]).
 */
const val MUSIC_DB_VERSION = 28

/**
 * Base de datos Room para cache local de canciones y colores.
 *
 * **TODO bump de `version` DEBE traer su `Migration`.** No hay
 * `fallbackToDestructiveMigration`, y su ausencia es deliberada: con la app publicada, ese
 * fallback convertía un olvido en el BORRADO SILENCIOSO de la biblioteca de cada usuario
 * (playlists, favoritos, historial, colores manuales) sin crash ni aviso. Sin él, una
 * migración faltante lanza `IllegalStateException` al abrir la BD — un fallo ruidoso que
 * aparece en el primer arranque de quien subió la versión, no semanas después en el
 * teléfono de otro. Es preferible un crash reproducible a datos irrecuperables.
 *
 * Antes de publicar un bump: instalar la versión anterior, usarla, actualizar encima y
 * verificar que la biblioteca sigue completa. Eso lo cubre además
 * `MusicDatabaseMigrationTest` (androidTest), que corre la cadena entera sobre los schemas
 * exportados en `app/schemas/` — añadir ahí el caso de todo bump que toque DATOS y no solo la
 * forma de la tabla, porque un backfill equivocado pasa la validación de esquema sin ruido.
 */
@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        ArtistEntity::class,
        PlayEventEntity::class
    ],
    version = MUSIC_DB_VERSION,
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun artistDao(): ArtistDao
    abstract fun playEventDao(): PlayEventDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        // v21 -> v22: columnas de historial de reproducción. Aditiva: no toca datos.
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN lastPlayedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_playCount ON songs(playCount)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_lastPlayedAt ON songs(lastPlayedAt)")
            }
        }

        // v22 -> v23: ruta relativa normalizada para detección de duplicados entre fuentes.
        // Aditiva. Backfill inmediato para las LOCAL (su id ES "local:<relpath>": el prefijo
        // "local:" mide 6, y con substr 1-based de SQLite la ruta empieza en el índice 7);
        // las de nube quedan NULL hasta que el próximo full scan las reporte.
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN relativePath TEXT")
                db.execSQL(
                    "UPDATE songs SET relativePath = LOWER(REPLACE(TRIM(substr(id, 7), '/'), '\\', '/')) " +
                        "WHERE sourceType = 'LOCAL'"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_relativePath ON songs(relativePath)")
            }
        }

        // v23 -> v24: tag GENRE por canción. Aditiva, no toca datos. Lo rellena el pipeline de
        // análisis (locales al indexar, de nube al descargar). Las solo-streaming quedan NULL
        // hasta que se descarguen o las lea la metadata ligera.
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN genre TEXT")
            }
        }

        // v24 -> v25: marca de "ya se intentó resolver la carátula". Aditiva y deliberadamente
        // SIN backfill: dejar la columna en NULL para todas es justo lo que hace que la fase de
        // resolución del sync revise una vez cada canción existente. Así, las bibliotecas donde
        // la 1.0.1 no llegó a guardar la portada (su id local llevaba '/' y la escritura fallaba
        // en silencio) se reparan solas, con el MISMO mecanismo que atiende a las canciones
        // nuevas — no hay un camino de migración aparte que retirar más adelante.
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN artworkAttemptedAt INTEGER")
            }
        }

        // v25 -> v26: contador de búsquedas fallidas en Deezer por artista. Aditiva.
        // CON backfill, al revés que la v25 y por el motivo opuesto: una fila que ya tiene
        // fetchedAt sin imageUrl y sin marca manual ES un not-found conocido, así que
        // dejarla en 0 la trataría como "nunca preguntado" y la volvería a consultar de
        // inmediato — justo el ciclo que esta columna existe para cortar. Se sella en 1
        // (un fallo constatado), que le da el mismo TTL de 14 días que tenía antes.
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artists ADD COLUMN notFoundAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE artists SET notFoundAttempts = 1 " +
                        "WHERE imageUrl IS NULL AND manuallySet = 0 AND fetchedAt IS NOT NULL"
                )
            }
        }

        // v26 -> v27: sello de la metadata ligera (ver `SongEntity.lightTagsAttemptedAt`). Aditiva y
        // SIN backfill, igual que la v25 y por el mismo motivo: dejarla en NULL hace que la fase
        // mire una vez cada canción que hoy está pendiente y selle las que de verdad no tienen tags.
        // Un backfill a "ya intentado" sería mentira —nunca se constató— y condenaría a quedarse sin
        // texto a las que sí lo tienen pero aún no se habían leído.
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN lightTagsAttemptedAt INTEGER")
            }
        }

        // v27 -> v28: log de escuchas (`play_events`). Aditiva —una tabla NUEVA, no toca ninguna
        // existente— y deliberadamente SIN BACKFILL.
        //
        // De `playCount`/`lastPlayedAt` solo se puede afirmar honestamente esto: hubo N escuchas, y
        // de todas ellas se conoce la fecha de UNA, la última. Sintetizar N eventos ahí dibujaría un
        // pico de cientos de reproducciones en un solo día, inventaría una racha y —como `playedMs`
        // no existe para el pasado— habría que inventar también el tiempo escuchado. Un backfill
        // equivocado PASA la validación de esquema sin despeinarse (la columna existe y es del tipo
        // correcto), y éste además pasaría desapercibido porque los números "se ven razonables".
        //
        // El historial por fecha arranca vacío y la pantalla de estadísticas lo dice con una nota
        // fechada; el pasado sigue disponible en su periodo "Siempre", que sale de `songs.playCount`
        // — que por eso NO se sustituye (alimenta además el inicio, los sorts y el LRU de desalojo).
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`songId` TEXT NOT NULL, " +
                        "`playedAt` INTEGER NOT NULL, " +
                        "`playedMs` INTEGER NOT NULL, " +
                        "`artist` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_events_playedAt` " +
                        "ON `play_events` (`playedAt`)"
                )
            }
        }

        /**
         * Todas las migraciones, en una sola lista. La consumen el builder y
         * `MusicDatabaseMigrationTest`: si el test declarara la suya, una migración nueva podría
         * quedarse fuera del test justo cuando más falta hace, sin que nada avisara.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28
        )

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_cache.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // SIN fallbackToDestructiveMigration a propósito: ver el KDoc de la clase.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
