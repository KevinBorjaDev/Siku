package com.qhana.siku.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las migraciones de `music_cache.db`, ejercitadas sobre los esquemas exportados en `app/schemas/`.
 *
 * **Por qué existe.** La BD no tiene `fallbackToDestructiveMigration` desde que la app se publicó,
 * y eso fue lo correcto: con usuarios reales, ese fallback convertía un olvido en el borrado
 * silencioso de la biblioteca de todos. El precio es que ahora una migración mal escrita es un
 * `IllegalStateException` al ABRIR la base — la app que no arranca, en el teléfono de quien
 * actualice. Y ese fallo no lo puede ver el compilador: el SQL de una migración es un string.
 *
 * Qué cubre cada test, y por qué ese reparto:
 *
 * - [migratesAllTheWayUp] recorre 21 → 28 en cadena. `runMigrationsAndValidate` compara el esquema
 *   resultante contra el JSON de destino, así que de una sola pasada cubre el error más fácil de
 *   cometer —una columna con otro tipo, otro default o sin su índice— en todas las migraciones.
 * - Los otros van a los sitios donde una migración toca DATOS —o decide NO tocarlos— y no solo la
 *   forma de la tabla. Un backfill equivocado pasa la validación de esquema sin despeinarse: la
 *   columna existe y es del tipo correcto, solo que con el contenido mal.
 *
 * Es un test INSTRUMENTADO porque necesita un SQLite de verdad; corre con
 * `./gradlew :app:connectedDebugAndroidTest`. **No toca los datos del dispositivo**: la base que
 * abre es temporal y del proceso de test, no `music_cache.db`.
 */
@RunWith(AndroidJUnit4::class)
class MusicDatabaseMigrationTest {

    // El helper localiza los esquemas por el nombre de la clase dentro de los assets del APK de
    // test; de ahí el `assets.srcDirs("$projectDir/schemas")` de app/build.gradle.kts.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MusicDatabase::class.java
    )

    @Test
    fun migratesAllTheWayUp() {
        helper.createDatabase(TEST_DB, FIRST_MIGRATED_VERSION).use { db ->
            db.insertSong(id = "local:primary/music/x.flac", sourceType = "LOCAL")
        }

        // `validateDroppedTables = true`: una tabla que se dejó de declarar pero sigue en disco es
        // justo el residuo que la limpieza de la v20 vino a quitar.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            MUSIC_DB_VERSION,
            true,
            *MusicDatabase.ALL_MIGRATIONS
        )

        db.query("SELECT id, playCount, genre FROM songs").use { cursor ->
            assertEquals("la canción no sobrevivió a la cadena de migraciones", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("local:primary/music/x.flac", cursor.getString(0))
            assertEquals("el historial nace a cero, no a NULL", 0, cursor.getInt(1))
            assertNull("genre nace vacío: lo rellena el análisis", cursor.getString(2))
        }
    }

    /**
     * v22 → v23 rellena `relativePath` para las locales derivándolo del id. Es aritmética de
     * strings en SQL (`substr(id, 7)` sobre el prefijo `local:`), el tipo de expresión que se
     * escribe una vez y nadie vuelve a mirar: con el índice desplazado en uno, el dedup entre
     * fuentes quedaría ciego sin que nada fallara.
     */
    @Test
    fun backfillsRelativePathForLocalSongsOnly() {
        helper.createDatabase(TEST_DB, 22).use { db ->
            db.insertSong(id = "local:primary/Music/Rock/x.flac", sourceType = "LOCAL")
            db.insertSong(id = "onedrive:ABC123", sourceType = "ONEDRIVE")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT id, relativePath FROM songs ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            // La de nube queda NULL a propósito: su ruta no se puede derivar del id.
            assertEquals("onedrive:ABC123", cursor.getString(0))
            assertNull("una canción de nube no tiene ruta que derivar", cursor.getString(1))
            cursor.moveToNext()
            assertEquals("local:primary/Music/Rock/x.flac", cursor.getString(0))
            assertEquals("primary/music/rock/x.flac", cursor.getString(1))
        }
    }

    /**
     * v25 → v26 sella en 1 los artistas que YA eran un not-found conocido, y solo esos. Dejarlos en
     * 0 los trataría como "nunca preguntado" y Deezer volvería a consultarlos de inmediato, que es
     * el ciclo que esa columna existe para cortar; sellar de más apagaría artistas que sí tienen
     * foto por ganar.
     */
    @Test
    fun sealsOnlyKnownNotFoundArtists() {
        helper.createDatabase(TEST_DB, 25).use { db ->
            db.insertArtist("NotFound", imageUrl = null, manuallySet = 0, fetchedAt = 1_000L)
            db.insertArtist("NuncaPreguntado", imageUrl = null, manuallySet = 0, fetchedAt = null)
            db.insertArtist("Rechazado", imageUrl = null, manuallySet = 1, fetchedAt = 1_000L)
            db.insertArtist("ConFoto", imageUrl = "https://x/y.jpg", manuallySet = 0, fetchedAt = 1_000L)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, *MusicDatabase.ALL_MIGRATIONS)

        val attempts = mutableMapOf<String, Int>()
        db.query("SELECT name, notFoundAttempts FROM artists").use { cursor ->
            while (cursor.moveToNext()) attempts[cursor.getString(0)] = cursor.getInt(1)
        }
        assertEquals("Deezer ya dijo que no lo tiene", 1, attempts["NotFound"])
        assertEquals("nunca se preguntó: sigue teniendo foto por ganar", 0, attempts["NuncaPreguntado"])
        assertEquals("lo rechazó el usuario a mano, no Deezer", 0, attempts["Rechazado"])
        assertEquals("ya tiene foto", 0, attempts["ConFoto"])
    }

    /**
     * v27 → v28 crea `play_events` VACÍA y deja los agregados intactos.
     *
     * **El no-backfill es la decisión, no un olvido**: de `playCount` solo se conoce la fecha de la
     * ÚLTIMA escucha, así que cualquier evento sintético llevaría una fecha inventada — y ese error
     * pasaría desapercibido porque los números "se ven razonables".
     *
     * Se comprueba además que la tabla **no tiene foreign key**: Room abre con
     * `PRAGMA foreign_keys = ON`, así que este INSERT para una canción inexistente fallaría si
     * alguien se la añadiera. Y esa FK, con `clearSourceData` en el logout, borraría el historial de
     * la nube entera en silencio.
     */
    @Test
    fun startsPlayHistoryEmptyAndKeepsAggregates() {
        helper.createDatabase(TEST_DB, 27).use { db ->
            db.insertSong(id = "onedrive:ABC123", sourceType = "ONEDRIVE")
            db.execSQL("UPDATE songs SET playCount = 7, lastPlayedAt = 1700000000000")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 28, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM play_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals("el historial arranca vacío: no hay fechas que inventar", 0, cursor.getInt(0))
        }
        db.query("SELECT playCount FROM songs").use { cursor ->
            cursor.moveToFirst()
            assertEquals("el agregado sigue vivo: alimenta el inicio, los sorts y el LRU", 7, cursor.getInt(0))
        }
        // Un evento cuya canción NO existe tiene que poder insertarse: es la ausencia de FK.
        db.execSQL(
            "INSERT INTO play_events (songId, playedAt, playedMs, artist) " +
                "VALUES ('onedrive:BORRADA', 1, 1, 'A')"
        )
        db.query("SELECT COUNT(*) FROM play_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals("un evento colgado es legítimo: es un hecho, no una referencia", 1, cursor.getInt(0))
        }
    }

    // --- Inserción por SQL crudo -----------------------------------------------------------------
    // A propósito, y no con las entidades de Room: una fila de la versión N tiene las columnas de la
    // versión N, mientras que la entidad es siempre la de la versión ACTUAL. Usarla aquí haría que
    // el test dejara de compilar —o peor, de decir la verdad— con el siguiente bump.

    /** Columnas de la v21: todas las NOT NULL sin default, que son las que el INSERT debe traer. */
    private fun SupportSQLiteDatabase.insertSong(id: String, sourceType: String) {
        execSQL(
            "INSERT INTO songs (id, title, artist, album, albumId, duration, uriString, " +
                "trackNumber, year, dateAdded, needsMetadata, isCorrupted, size, " +
                "downloadAttempts, sourceType, remoteId) " +
                "VALUES (?, 'T', 'A', 'Al', 0, 0, '', 0, 0, 0, 0, 0, 0, 0, ?, ?)",
            arrayOf(id, sourceType, id.substringAfter(':'))
        )
    }

    private fun SupportSQLiteDatabase.insertArtist(
        name: String,
        imageUrl: String?,
        manuallySet: Int,
        fetchedAt: Long?
    ) {
        execSQL(
            "INSERT INTO artists (name, imageUrl, thumbUrl, manuallySet, fetchedAt) " +
                "VALUES (?, ?, NULL, ?, ?)",
            arrayOf<Any?>(name, imageUrl, manuallySet, fetchedAt)
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /**
         * La primera versión con migración REAL. Por debajo no hay ruta de subida —los esquemas
         * 12-20 son de cuando existía el destructive fallback—, así que empezar más abajo probaría
         * un camino que en producción no existe.
         */
        const val FIRST_MIGRATED_VERSION = 21
    }
}
