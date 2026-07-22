package com.qhana.siku.data.backup

/**
 * Formato del backup de playlists (Fase 6). JSON propio, no M3U: hace falta guardar el
 * `sourceType` y la clave portable de cada canción, cosa que un M3U (rutas de archivo) no
 * expresa y que es lo que permite restaurar en otro dispositivo.
 *
 * Compatibilidad: [schemaVersion] permite migrar el formato más adelante sin romper backups viejos.
 */
data class PlaylistBackupFile(
    val schemaVersion: Int,
    val exportedAt: Long,
    val playlists: List<BackupPlaylist>
)

data class BackupPlaylist(
    /** Clave estable de la playlist entre dispositivos (favoritos tiene un UUID fijo). */
    val uuid: String,
    val name: String,
    val songs: List<BackupSong>
)

/**
 * Una canción dentro de una playlist respaldada.
 *
 * [id] es la clave portable (`onedrive:<item.id>` / `local:<ruta relativa>`), y es el modo
 * principal de resolución. Los tags son el *fallback* cuando el id ya no existe en la biblioteca
 * de destino (canción re-subida, movida de carpeta, o restaurada en otro teléfono).
 */
data class BackupSong(
    val id: String,
    val sourceType: String,
    val title: String?,
    val artist: String?,
    val album: String?
)

/** Resultado de una restauración, para informar al usuario de qué se pudo recuperar. */
data class ImportSummary(
    val playlistsCreated: Int,
    val playlistsMerged: Int,
    val songsAdded: Int,
    val songsMissing: Int
)
