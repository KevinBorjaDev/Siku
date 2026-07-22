package com.qhana.siku.ui.state

import androidx.compose.runtime.Stable
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.ui.viewmodel.LyricLine

/**
 * Por qué no hay letra en pantalla. Las tres causas piden acciones DISTINTAS, así que no pueden
 * colapsarse en un `lyricsError: String?`: sin red no tiene sentido ofrecer "buscar en Google"
 * (abre el navegador, que tampoco tiene red) ni una búsqueda manual contra LrcLib; y si LrcLib
 * respondió "no la tengo", reintentar solo repite la misma respuesta.
 */
enum class LyricsFailure {
    /** LrcLib respondió, pero no tiene letra para esta canción. */
    NOT_FOUND,
    /** No hay conexión: ni siquiera se llegó a preguntar. */
    NO_NETWORK,
    /** LrcLib falló (5xx, timeout, parseo). El detalle va en `lyricsError`. */
    PROVIDER_ERROR
}

@Stable
data class NowPlayingUiState(
    val song: Song? = null,
    val albumColors: AlbumColors? = null,
    /**
     * El color de esta canción lo eligió el usuario a mano. Lo necesita el tema: un color
     * elegido explícitamente se respeta AUNQUE sea de baja saturación, mientras que uno
     * extraído con tan poco croma se manda al esquema neutro (ver `isAchromatic` en
     * MainActivity). Sin este flag, elegir un verde grisáceo del selector no cambiaba nada.
     */
    val hasManualColor: Boolean = false,
    val lyrics: String? = null,
    val lyricLines: List<LyricLine> = emptyList(),
    val isLyricsLoading: Boolean = false,
    val lyricsFailure: LyricsFailure? = null,
    /** Detalle textual del fallo del proveedor; null salvo en [LyricsFailure.PROVIDER_ERROR]. */
    val lyricsError: String? = null,
    // Búsqueda manual de letras (LrcLib /api/search)
    val isSearchingCandidates: Boolean = false,
    val lyricsCandidates: List<LyricsCandidate>? = null, // null = sheet cerrado, vacía = sin resultados
    val lyricsSearchError: String? = null,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadStatusMessage: String? = null,
    val debugInfo: Any? = null // ArtworkRepository.DebugColorInfo
)

