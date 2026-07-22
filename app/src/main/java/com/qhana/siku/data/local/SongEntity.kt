package com.qhana.siku.data.local

import android.net.Uri
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType

/**
 * Entidad de Room para almacenar canciones en cache local.
 * Incluye metadatos y colores extraídos de la carátula.
 *
 * Índices optimizados para consultas frecuentes:
 * - title: ordenamiento principal
 * - colorPrimary: filtrado de canciones sin colores
 * - artist, album: búsquedas y agrupación
 * - dateAdded: ordenamiento por fecha
 * - uriString: filtrado por path local (file://) vs remoto (https://)
 * - remoteId: filtrado de canciones cloud
 * - needsMetadata: identificar canciones pendientes de metadata
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"]),
        Index(value = ["colorPrimary"]),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["dateAdded"]),
        Index(value = ["uriString"]),
        Index(value = ["remoteId"]),
        Index(value = ["needsMetadata"]),
        Index(value = ["isCorrupted"]),
        Index(value = ["sourceType"]),
        Index(value = ["playCount"]),
        Index(value = ["lastPlayedAt"]),
        Index(value = ["relativePath"])
    ]
)
data class SongEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val uriString: String,
    val albumArtUriString: String?,
    val trackNumber: Int,
    val year: Int,
    // Tag GENRE del archivo (null hasta leerlo). Alimenta los chips de género del inicio.
    val genre: String? = null,
    val dateAdded: Long,
    // Colores extraídos de la carátula (null si aún no se han extraído)
    val colorPrimary: Int? = null,
    val colorSecondary: Int? = null,
    val lyrics: String? = null,
    val lyricsAttemptedAt: Long? = null,
    val needsMetadata: Boolean = false,
    val remoteId: String? = null,
    val isCorrupted: Boolean = false, // Nuevo campo para persistir fallos de reproducción
    val size: Long = 0, // Tamaño esperado del archivo en bytes
    // ReplayGain leído de los tags del archivo (null hasta que se descarga e indexa)
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
    // Cola de descargas persistente (v18): bookkeeping de fallos que sobrevive al proceso.
    // La lista de "fallidas" de la UI y el backoff entre reintentos salen de acá, no de
    // memoria. Se limpia al completar la descarga (clearDownloadError).
    val downloadAttempts: Int = 0,
    val lastDownloadError: String? = null,
    val downloadErrorKind: String? = null, // "TRANSIENT" | "PERMANENT"
    val nextRetryAt: Long? = null, // epoch ms; el productor salta la canción hasta entonces
    // --- Origen (Fase 0: identidad multi-proveedor). `id` es namespaced (`onedrive:`/`local:`). ---
    val sourceType: String = SourceType.ONEDRIVE.name, // nombre del enum SourceType
    val sourceId: String? = null, // qué cuenta OneDrive / qué carpeta raíz local
    // --- Historial de reproducción (v22). Solo se escribe vía incrementPlayStats: el upsert
    // del sync no toca filas existentes, así que los contadores sobreviven a los scans. ---
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null, // epoch ms de la última escucha contada
    // --- Duplicados entre fuentes (v23): ruta relativa a la raíz de la fuente, NORMALIZADA
    // (ver normalizeRelativePath en :core). null = nube pre-v23 hasta el próximo full scan. ---
    val relativePath: String? = null
) {
    /**
     * Convierte la entidad de Room al modelo de dominio Song
     */
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        path = uriString,
        albumArtUri = albumArtUriString?.let { Uri.parse(it) },
        trackNumber = trackNumber,
        year = year,
        genre = genre,
        dateAdded = dateAdded,
        lyrics = lyrics,
        lyricsAttemptedAt = lyricsAttemptedAt,
        remoteId = remoteId,
        isCorrupted = isCorrupted,
        size = size,
        colors = getAlbumColors(),
        trackGainDb = trackGainDb,
        trackPeak = trackPeak,
        albumGainDb = albumGainDb,
        albumPeak = albumPeak,
        sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.ONEDRIVE),
        sourceId = sourceId,
        relativePath = relativePath
    )

    /**
     * Obtiene los colores como AlbumColors, o null si no están disponibles
     */
    fun getAlbumColors(): AlbumColors? {
        return if (colorPrimary != null && colorSecondary != null) {
            AlbumColors(colorPrimary, colorSecondary)
        } else null
    }

    companion object {
        /**
         * Crea una SongEntity desde un Song del dominio (sin colores)
         */
        fun fromSong(song: Song, albumId: Long, needsMetadata: Boolean = false): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = albumId,
            duration = song.duration,
            uriString = song.path,
            albumArtUriString = song.albumArtUri?.toString(),
            trackNumber = song.trackNumber,
            year = song.year,
            genre = song.genre,
            dateAdded = song.dateAdded,
            colorPrimary = null,
            colorSecondary = null,
            lyrics = song.lyrics,
            lyricsAttemptedAt = song.lyricsAttemptedAt,
            needsMetadata = needsMetadata,
            remoteId = song.remoteId,
            isCorrupted = song.isCorrupted,
            size = song.size,
            trackGainDb = song.trackGainDb,
            trackPeak = song.trackPeak,
            albumGainDb = song.albumGainDb,
            albumPeak = song.albumPeak,
            sourceType = song.sourceType.name,
            sourceId = song.sourceId,
            relativePath = song.relativePath
        )
    }
}

