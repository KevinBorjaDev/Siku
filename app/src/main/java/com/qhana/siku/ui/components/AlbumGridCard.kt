package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary

/**
 * Celda de álbum para cuadrículas (pestaña Álbumes y grid del detalle de artista).
 * Contenedor tonal sólido (sin glassmorphism fuera de NowPlaying).
 * Con [sharedTransitionScope]+[animatedVisibilityScope], la carátula es shared element
 * (key `album_image_<nombre>`) hacia el header del detalle de álbum.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumGridCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtist: Boolean = true,
    onPlayClick: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "album_image_${album.name}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else Modifier
    // OJO: sin clip en el Column exterior — con las esquinas de 20dp el arco inferior
    // recortaba el label del artista (vive pegado al borde de abajo). La carátula ya
    // trae su propio recorte redondeado. SIN indication: el ripple rectangular gris se
    // hacía muy visible al mantener presionado (no hay acción de long-press que lo amerite).
    Column(
        modifier = modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = onClick
        )
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedModifier)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            if (album.albumArtUri != null) {
                AsyncImage(
                    model = album.albumArtUri,
                    contentDescription = stringResource(R.string.album_art_desc, album.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    MaterialSymbol(
                        "music_note",
                        size = 40.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Botón rápido de reproducir el álbum (esquina inferior derecha de la carátula).
            // SIN fondo: el glifo `play_circle` (disco relleno) ya se lee como botón. En el acento
            // AVIVADO (vividAccentColor) para no perderse sobre la carátula — el primary crudo salía
            // apagado. IconButton estándar (M3 Expressive: shape-morph al presionar).
            if (onPlayClick != null) {
                val playColor = vividAccentColor(MaterialTheme.colorScheme.primary)
                IconButton(
                    onClick = onPlayClick,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(44.dp)
                ) {
                    MaterialSymbol(
                        "play_circle",
                        size = 38.sp,
                        color = playColor,
                        fill = false
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name.ifBlank { stringResource(R.string.common_unknown_album) },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (showArtist) {
            Text(
                text = album.artist.ifBlank { "Artista desconocido" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
