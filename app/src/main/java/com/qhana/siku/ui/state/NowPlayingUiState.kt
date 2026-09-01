package com.qhana.siku.ui.state

import androidx.compose.runtime.Stable
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.ui.viewmodel.LyricLine
import com.qhana.siku.data.util.LrcSyntax

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
     * elegido explícitamente se respeta AUNQUE apenas tenga croma, mientras que uno extraído
     * igual de apagado se manda al esquema neutro (ver `ArtworkRepository.isAchromatic`).
     * Sin este flag, elegir un verde grisáceo del selector no cambiaba nada.
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
) {
    /**
     * Retraso aplicado a la letra, en ms: positivo = los versos salen más tarde.
     *
     * **Derivado y no un campo**, que es lo que impide que pueda mentir: sale de la marca
     * `[offset:]` del propio [lyrics] (ver [LrcSyntax.shiftMillisOf]), o sea de lo mismo que se
     * persiste y que se acaba escribiendo en el `.lrc` del usuario. Como campo habría que
     * recordarlo en cada `copy` que toque la letra —el prefetch, la búsqueda manual, el candidato
     * elegido— y el primer olvido dejaría el control enseñando un número que no es el aplicado.
     *
     * **`by lazy` y no un `get()`**, que es lo que era: un getter corre el Regex sobre la letra
     * ENTERA en cada lectura, y quien lo lee es la composición de la pantalla de letras. Como
     * `copy()` fabrica una instancia nueva, un `lazy` por instancia caduca exactamente cuando el
     * texto puede haber cambiado — o sea que conserva intacto el "no puede mentir" de arriba y solo
     * quita el trabajo repetido. `PUBLICATION` porque el cálculo es puro: en el peor caso dos hilos
     * lo hacen a la vez y llegan al mismo número, que sale más barato que un lock.
     */
    val lyricsShiftMs: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lyrics?.let { LrcSyntax.shiftMillisOf(it) } ?: 0L
    }
}

