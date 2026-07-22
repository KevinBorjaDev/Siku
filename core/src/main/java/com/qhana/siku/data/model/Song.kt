package com.qhana.siku.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * Modelo de datos para representar una canción
 *
 * IMPORTANTE: Marcado como @Immutable para que Compose pueda
 * hacer skip de recomposiciones cuando los datos no cambian.
 */
@Immutable
data class Song(
    /**
     * ID único y portable entre dispositivos, namespaced por fuente (ver [SourceType]):
     * `onedrive:<item.id>` o `local:<ruta relativa a la carpeta raíz>`.
     *
     * Las llamadas a la API del proveedor usan [remoteId] (el handle CRUDO, sin prefijo), NO este id.
     */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String = "", // URL de OneDrive o path local
    val albumArtUri: Uri? = null,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null, // Tag GENRE del archivo (null hasta leerlo del tag)
    val dateAdded: Long = 0, // Timestamp en segundos de cuando se añadió
    val lyrics: String? = null,
    /**
     * Timestamp (epoch ms) del último intento de obtener letras desde el servidor.
     * - null: nunca se intentó (hay que buscar)
     * - valor + lyrics != null: tenemos letras (no buscar)
     * - valor + lyrics == null: NotFound; reintentar solo si pasaron > TTL (14 días)
     */
    val lyricsAttemptedAt: Long? = null,
    val remoteId: String? = null,
    val isCorrupted: Boolean = false,
    val size: Long = 0,
    val colors: AlbumColors? = null,
    // --- ReplayGain (leído de los tags del archivo local; null si no etiquetado) ---
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
    // --- Origen de la canción (Fase 0: identidad multi-proveedor) ---
    /** De qué fuente viene la canción. Default ONEDRIVE (provisional hasta que existan más fuentes). */
    val sourceType: SourceType = SourceType.ONEDRIVE,
    /** Identificador de la instancia de la fuente: qué cuenta OneDrive o qué carpeta raíz local. null = única/por defecto. */
    val sourceId: String? = null,
    /**
     * Ruta relativa a la raíz de la fuente, NORMALIZADA (ver [normalizeRelativePath]).
     * Es la clave de detección de duplicados entre fuentes. null = aún no reportada
     * (filas de nube pre-v23 hasta el próximo full scan).
     */
    val relativePath: String? = null
) {
    val uri: Uri get() = if (path.isNotBlank()) Uri.parse(path) else Uri.EMPTY

    /**
     * El audio ya está en el dispositivo y se reproduce sin red: o bien se descargó de la nube
     * (`file://`), o bien viene de la fuente LOCAL vía SAF (`content://`). Usar esto en vez de
     * comparar contra `file://` a mano — si no, las canciones locales se muestran como streaming.
     */
    val isLocalAudio: Boolean
        get() = path.startsWith("file://") || path.startsWith("content://")

    /**
     * De dónde sale el audio que suena AHORA: combina la fuente ([sourceType]) con el estado del
     * archivo ([isLocalAudio]). Una canción de OneDrive puede sonar por streaming o desde el
     * archivo ya descargado, y eso es lo que distingue el chip de Now Playing.
     */
    val playbackOrigin: PlaybackOrigin
        get() = when {
            sourceType == SourceType.LOCAL -> PlaybackOrigin.LOCAL
            isLocalAudio -> PlaybackOrigin.DOWNLOADED
            else -> PlaybackOrigin.STREAMING
        }

    /** Agrupa los 4 tags ReplayGain, o null si la canción no tiene ninguno. */
    val replayGain: ReplayGainData?
        get() = if (trackGainDb == null && albumGainDb == null && trackPeak == null && albumPeak == null) null
        else ReplayGainData(trackGainDb, trackPeak, albumGainDb, albumPeak)

    // Lazy: solo se calcula cuando se necesita (ahorra CPU al cargar 5000 canciones)
    val formattedDuration: String by lazy {
        val totalSeconds = duration / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    // Lazy: solo se convierte cuando se necesita
    val albumArtUriString: String? by lazy { albumArtUri?.toString() }
}

/**
 * Fuente de la que proviene una canción. El `id` de [Song] es una clave namespaced y
 * portable entre dispositivos: `<prefix><clave estable>`.
 *
 * - ONEDRIVE: la clave estable es el `item.id` de Graph (estable por cuenta) → `onedrive:<item.id>`.
 * - LOCAL: la clave estable es la ruta relativa desde la carpeta raíz elegida → `local:Artista/Álbum/01 Tema.flac`.
 *
 * El `remoteId` sigue siendo el handle CRUDO específico del proveedor (sin prefijo) que
 * usan las llamadas a la API (Graph). No confundir con `id`.
 */
enum class SourceType(val prefix: String) {
    ONEDRIVE("onedrive:"),
    LOCAL("local:");

    /**
     * ¿La fuente vive en la nube? Gobierna las features de descarga (cola, tope de GB):
     * solo existen si hay al menos una fuente cloud configurada. Todo proveedor nuevo
     * es cloud salvo LOCAL, así que esto no exige tocar cada feature al agregar uno.
     */
    val isCloud: Boolean get() = this != LOCAL

    /** Construye el `id` namespaced a partir de la clave estable cruda del proveedor. */
    fun buildId(stableKey: String): String = "$prefix$stableKey"

    /** Devuelve la clave estable cruda quitando el prefijo (o el id tal cual si no lo tiene). */
    fun stableKeyOf(id: String): String = id.removePrefix(prefix)

    companion object {
        /** Deduce el SourceType por el prefijo del id; ONEDRIVE por defecto (compatibilidad). */
        fun fromId(id: String): SourceType = entries.firstOrNull { id.startsWith(it.prefix) } ?: ONEDRIVE
    }
}

/**
 * Origen del audio en reproducción, para el chip de Now Playing (Fase 5).
 *
 * - LOCAL: carpeta del dispositivo (`content://`), nunca pasa por la red.
 * - DOWNLOADED: viene de la nube pero ya está en disco (`file://`), suena offline.
 * - STREAMING: se lee de la URL remota de la nube.
 */
enum class PlaybackOrigin {
    LOCAL,
    DOWNLOADED,
    STREAMING
}

/**
 * Representa los colores extraídos de una carátula
 */
@androidx.compose.runtime.Immutable
data class AlbumColors(
    val primary: Int,
    val secondary: Int
)

/**
 * Estado de reproducción
 */
enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    BUFFERING
}

/**
 * Modo de repetición
 */
enum class RepeatMode {
    OFF,
    ONE,
    ALL
}
