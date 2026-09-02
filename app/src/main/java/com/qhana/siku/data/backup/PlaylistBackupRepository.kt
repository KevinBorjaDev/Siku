package com.qhana.siku.data.backup

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.local.PlaylistDao
import com.qhana.siku.data.local.PlaylistEntity
import com.qhana.siku.data.local.PlaylistSongCrossRef
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.model.AppError
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.remote.OneDriveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup de playlists a OneDrive (Fase 6).
 *
 * El JSON vive en la **carpeta de la app** (`/drive/special/approot`, visible para el usuario como
 * `Apps/<app>/` en su OneDrive): es la misma carpeta para la misma app en cualquier dispositivo,
 * así que el backup es multi-dispositivo sin pedir permiso de escritura sobre toda la nube
 * (scope `Files.ReadWrite.AppFolder`, ver [AuthManager]).
 *
 * Restaurar **fusiona**: nunca borra playlists ni canciones existentes.
 */
@Singleton
class PlaylistBackupRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager
) {
    private val gson = Gson()

    /**
     * Exporta TODAS las playlists (incluidos los favoritos) al JSON de la nube, reemplazando el
     * backup anterior. Devuelve cuántas playlists se guardaron.
     */
    suspend fun export(): AppResult<Int> = withContext(Dispatchers.IO) {
        runCatchingBackup {
            val playlists = playlistDao.getAllPlaylists().map { playlist ->
                val songs = playlistDao.getBackupRowsForPlaylist(playlist.playlistId).map { row ->
                    BackupSong(
                        id = row.id,
                        // Derivado del prefijo del id, no de la fila de `songs`: así sigue siendo
                        // correcto para las canciones que ya no están en la biblioteca.
                        sourceType = SourceType.fromId(row.id).name,
                        title = row.title,
                        artist = row.artist,
                        album = row.album
                    )
                }
                BackupPlaylist(uuid = playlist.uuid, name = playlist.name, songs = songs)
            }

            val payload = PlaylistBackupFile(
                schemaVersion = SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                playlists = playlists
            )
            val body = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE.toMediaType())
            oneDriveApi.uploadFile(bearerToken(), BACKUP_FILE_URL, body)
            playlists.size
        }
    }

    /**
     * Restaura el backup de la nube fusionándolo con la biblioteca actual: las playlists se
     * identifican por `uuid` (se crean si faltan) y las canciones se añaden al final sin duplicar.
     *
     * Cada canción se resuelve primero por su id portable y, si ese id ya no existe en la
     * biblioteca, por (título, artista) — resiliente a renombrados y a re-subidas a la nube.
     */
    suspend fun import(): AppResult<ImportSummary> = withContext(Dispatchers.IO) {
        runCatchingBackup {
            val json = oneDriveApi.downloadFile(bearerToken(), BACKUP_FILE_URL).string()
            val backup = gson.fromJson(json, PlaylistBackupFile::class.java)
                ?: throw JsonSyntaxException("Backup vacío")

            var created = 0
            var merged = 0
            var added = 0
            var missing = 0

            // Los ids que ESTA biblioteca tiene, de una vez. Antes era un `existsById` por canción
            // del backup: restaurar mil canciones eran mil consultas para preguntar mil veces lo
            // mismo a una tabla que no cambia durante el import.
            val libraryIds = songDao.getAllSongIds().toHashSet()

            for (backupPlaylist in backup.playlists) {
                val existingId = playlistDao.getPlaylistIdByUuid(backupPlaylist.uuid)
                val playlistId = if (existingId != null) {
                    merged++
                    existingId
                } else {
                    created++
                    playlistDao.upsertPlaylist(
                        PlaylistEntity(uuid = backupPlaylist.uuid, name = backupPlaylist.name)
                    )
                }

                // Lo que la lista ya tiene, de una vez por LISTA en lugar de una consulta por
                // canción. Es un `Set` mutable porque el propio backup puede repetir una canción
                // dentro de la misma lista, y ahí el cross-ref (clave compuesta) la rechazaría en
                // silencio y el conteo mentiría.
                val alreadyIn = playlistDao.getSongIdsInPlaylist(playlistId).toHashSet()
                var orderIndex = (playlistDao.getMaxOrderIndex(playlistId) ?: -1) + 1
                val crossRefs = ArrayList<PlaylistSongCrossRef>(backupPlaylist.songs.size)

                for (song in backupPlaylist.songs) {
                    val resolvedId = resolveSongId(song, libraryIds)
                    if (resolvedId == null) {
                        missing++
                        continue
                    }
                    if (!alreadyIn.add(resolvedId)) continue
                    crossRefs.add(PlaylistSongCrossRef(playlistId, resolvedId, orderIndex++))
                }

                // UNA escritura por lista. Cada `addSongToPlaylist` suelto era su propia
                // transacción y su propia invalidación de `playlist_song_cross_ref`, que la UI
                // observa: la pantalla de listas se repintaba una vez por canción restaurada,
                // sobre una lista a medio construir.
                playlistDao.addSongsToPlaylist(crossRefs)
                added += crossRefs.size
            }

            ImportSummary(created, merged, added, missing)
        }
    }

    /**
     * Id de la canción en ESTA biblioteca: por clave portable y, si no, por tags.
     *
     * @param libraryIds todos los ids de la biblioteca, leídos UNA vez por el llamante. El camino
     *   normal —la clave portable existe— se resuelve así en memoria, sin tocar la BD.
     *
     * **El rescate por tags sigue siendo una consulta por canción, y es deliberado.** Se podría
     * indexar en memoria con `getSearchableText()`, pero la búsqueda es `COLLATE NOCASE` de SQLite
     * y replicarla en Kotlin sería escribir la misma regla de comparación por segunda vez, con un
     * plegado de mayúsculas que no es el mismo (SQLite solo pliega ASCII; `lowercase()` es
     * Unicode). Dos verdades para "¿es la misma canción?" a cambio de acelerar la RAMA DE
     * EXCEPCIÓN: solo entra aquí lo que el id portable no encontró.
     */
    private suspend fun resolveSongId(song: BackupSong, libraryIds: Set<String>): String? {
        if (song.id in libraryIds) return song.id
        val title = song.title ?: return null
        val artist = song.artist ?: return null
        return songDao.findIdByTitleAndArtist(title, artist)
    }

    private suspend fun bearerToken(): String {
        // Token con el scope de escritura en la carpeta de la app, NO el de la operación normal.
        val result = authManager.getBackupAccessToken().first()
        if (result !is AuthResult.Success || result.token.isBlank()) {
            throw IllegalStateException("Sin sesión de OneDrive")
        }
        return "Bearer ${result.token}"
    }

    /**
     * Mapea los fallos propios del backup a [AppError] con significado: un 404 es "aún no hay
     * backup" (no un error de red), y un 401/403 pide reconectar la cuenta — probablemente porque
     * el scope `Files.ReadWrite.AppFolder` es nuevo y la sesión guardada no lo tiene.
     */
    private inline fun <T> runCatchingBackup(block: () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (e: HttpException) {
        when (e.code()) {
            404 -> AppResult.Error(AppError.NotFound("No hay backup guardado"))
            401, 403 -> AppResult.Error(AppError.Auth(needsRelogin = true))
            else -> AppResult.Error(AppError.Network("Error de OneDrive (${e.code()})", e))
        }
    } catch (e: JsonSyntaxException) {
        AppResult.Error(AppError.Data("El backup está corrupto", e))
    } catch (e: IllegalStateException) {
        AppResult.Error(AppError.Auth(needsRelogin = true))
    } catch (e: Exception) {
        AppResult.Error(AppError.fromException(e))
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val BACKUP_FILE_NAME = "playlists_backup.json"

        /**
         * URL absoluta: la ruta de Graph lleva `:` literales que un `@Path` de Retrofit
         * codificaría (`approot%3A`), rompiendo el endpoint.
         */
        private const val BACKUP_FILE_URL =
            "https://graph.microsoft.com/v1.0/me/drive/special/approot:/$BACKUP_FILE_NAME:/content"
    }
}
