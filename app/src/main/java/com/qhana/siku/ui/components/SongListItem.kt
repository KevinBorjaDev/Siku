package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.model.SongUiModel
import com.qhana.siku.ui.model.toUiModel

// ============== SONG STATUS ICON ==============

/**
 * Indicador de estado de descarga de una canción.
 * Icon button ESTÁNDAR M3 Expressive (sin contenedor): ripple + shape-morph al presionar
 * vía `IconButtonDefaults.shapes()`, mismo patrón que los toggles con `toggleableShapes()`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongStatusIcon(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = ComponentConfig.StatusIconSize,
    onClick: () -> Unit = {},
    contrast: Boolean = false,
    contrastColor: Color = MaterialTheme.colorScheme.primary
) {
    val primaryColor = if (!contrast) MaterialTheme.colorScheme.primary else contrastColor

    // Cache del tamaño en Sp para evitar recálculos
    val density = LocalDensity.current
    val sizeSp = remember(size, density) { with(density) { size.toSp() } }

    val description = when {
        isDownloaded -> stringResource(R.string.status_downloaded)
        isDownloading -> stringResource(R.string.status_downloading, ((downloadProgress ?: 0f) * 100).toInt())
        else -> stringResource(R.string.status_download_song)
    }

    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.semantics { contentDescription = description }
    ) {
        when {
            isDownloaded -> {
                MaterialSymbol("offline_pin", color = primaryColor, size = sizeSp)
            }
            isDownloading -> {
                // LoadingIndicator expressive en AMBOS estados (morfea entre MaterialShapes):
                // la variante DETERMINADA avanza el morph con el progreso. Antes el caso con
                // progreso usaba un CircularProgressIndicator clásico, así que el mismo icono
                // cambiaba de lenguaje visual a mitad de descarga.
                if (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f) {
                    LoadingIndicator(
                        progress = { downloadProgress },
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                } else {
                    LoadingIndicator(
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                }
            }
            else -> {
                MaterialSymbol("cloud_download", color = Color.Gray.copy(alpha = 0.5f), size = sizeSp)
            }
        }
    }
}

// ============== SONG ITEM ==============

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useFillAnimation: Boolean = false, // New parameter
    showActiveBackground: Boolean = true,
    onStatusClick: () -> Unit = {},
    trailingContent: @Composable (() -> Unit)? = null
) {
    // Convertir a modelo UI para reutilizar la lógica de renderizado
    val uiModel = remember(song) { song.toUiModel(isActive = isPlaying) }

    SongItem(
        song = uiModel,
        isPlaying = isPlaying,
        modifier = modifier,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        showDuration = showDuration,
        showStatusIcon = showStatusIcon,
        useFillAnimation = useFillAnimation, // Pass through
        showActiveBackground = showActiveBackground,
        onStatusClick = onStatusClick,
        trailingContent = trailingContent
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongItem(
    song: SongUiModel,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useFillAnimation: Boolean = false, // New parameter
    // El item activo dibuja su propio tinte de contenedor. Ponerlo en false cuando un CONTENEDOR
    // externo ya pinta el resaltado de TODA la fila (p. ej. la cola), para no duplicar el énfasis.
    showActiveBackground: Boolean = true,
    onStatusClick: () -> Unit = {},
    trailingContent: @Composable (() -> Unit)? = null
) {
    // `ListItem` REAL de M3 (anatomía + tokens por spec): headline `bodyLarge`/onSurface,
    // supporting `bodyMedium`/onSurfaceVariant, leading/trailing en sus slots. El resaltado del
    // item activo va por el `containerColor` de ListItemColors (secondary 12%, mismo criterio de
    // antes). El look de LISTA AGRUPADA (esquinas + gaps + fondo tonal) lo sigue poniendo el
    // contenedor EXTERNO de cada pantalla; aquí el container es transparente salvo el activo.
    val activeContainer = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
    val listColors = ListItemDefaults.colors(
        containerColor = if (isPlaying && showActiveBackground) activeContainer else Color.Transparent
    )

    // contentDescription/stateDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playingStateDesc = if (isPlaying) stringResource(R.string.status_playing) else ""
    val durationDesc = stringResource(R.string.common_duration, song.durationText)

    // Trailing (duración + estado de descarga + menú). null si no hay nada → ListItem no reserva slot.
    val trailing: @Composable (() -> Unit)? = if (showDuration || showStatusIcon || trailingContent != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDuration) {
                    Text(
                        text = song.durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = durationDesc }
                    )
                }
                // Estado de descarga junto al menú, como icon button estándar (sin fondo).
                if (showStatusIcon) {
                    SongStatusIcon(
                        isDownloaded = song.isDownloaded,
                        isDownloading = isDownloading,
                        downloadProgress = null, // Hide progress here (moved to AlbumArt)
                        onClick = onStatusClick
                    )
                }
                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    trailingContent()
                }
            }
        }
    } else null

    ListItem(
        headlineContent = {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium, // Medium por defecto
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${song.artist} • ${song.album}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            // Carátula: cookie de 9 lados (M3 Expressive) en vez de círculo.
            // toShape() ya es @Composable y memoiza internamente (no envolver en remember).
            AlbumArt(
                albumArtUri = song.imageUrl,
                size = ComponentConfig.SongItemIconSize,
                shape = MaterialShapes.Cookie9Sided.toShape(),
                cacheKey = song.id,
                downloadProgress = downloadProgress,
                isDownloading = isDownloading,
                useFillAnimation = useFillAnimation
            )
        },
        trailingContent = trailing,
        colors = listColors,
        modifier = modifier
            // Clip para que el tinte del contenedor activo respete la esquina del item (el
            // contenedor externo agrupado recorta a su vez la forma de la lista).
            .clip(RoundedCornerShape(ComponentConfig.SongItemCornerRadius))
            .semantics { stateDescription = playingStateDesc }
    )
}
