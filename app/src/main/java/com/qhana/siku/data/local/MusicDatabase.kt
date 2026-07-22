package com.qhana.siku.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 * verificar que la biblioteca sigue completa. Los schemas de `app/schemas/` permiten además
 * escribir tests con `MigrationTestHelper`.
 */
@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        ArtistEntity::class
    ],
    version = 24, // v24: genre (tag GENRE para los chips de género del inicio)
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun artistDao(): ArtistDao

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

        // v23 -> v24: tag GENRE por canción. Aditiva, no toca datos. Se rellena perezosamente:
        // las locales/descargadas por el pipeline de análisis + un backfill una-vez que re-lee
        // los archivos locales (ver MusicDownloader.backfillGenres). Las solo-streaming quedan
        // NULL hasta que se descarguen/analicen.
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN genre TEXT")
            }
        }

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_cache.db"
                )
                    .addMigrations(MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
                    // SIN fallbackToDestructiveMigration a propósito: ver el KDoc de la clase.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
