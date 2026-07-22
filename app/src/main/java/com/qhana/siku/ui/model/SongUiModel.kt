package com.qhana.siku.ui.model

import androidx.compose.runtime.Immutable
import com.qhana.siku.data.model.Song

/**
 * Modelo optimizado para la UI.
 * Anotado con @Immutable para garantizar que Compose salte recomposiciones si los datos no cambian.
 * Solo contiene lo necesario para pintar la celda.
 */
@Immutable
data class SongUiModel(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationText: String,
    val imageUrl: String?,
    val isDownloaded: Boolean,
    val isActive: Boolean = false // Si está sonando o seleccionada
)

/**
 * Extension para convertir Song a SongUiModel
 */
fun Song.toUiModel(isActive: Boolean = false): SongUiModel {
    // Formatear duración aquí para no hacerlo en cada frame
    val totalSeconds = duration / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val durationFormatted = "%d:%02d".format(minutes, seconds)

    return SongUiModel(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationText = durationFormatted,
        imageUrl = albumArtUriString,
        isDownloaded = isLocalAudio,
        isActive = isActive
    )
}
