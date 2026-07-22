package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

// ============== CONSTANTES ==============

private val PlaceholderDark = Color(0xFF2A2A2A)
private val PlaceholderLight = Color(0xFFE0E0E0)

// ============== ALBUM ART ==============

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumArt(
    albumArtUri: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    shape: Shape? = null, // Si se pasa, tiene prioridad sobre cornerRadius
    cacheKey: String? = null,
    // Resolución PEDIDA a Coil. El default (thumbnail de 156px) es correcto para list items
    // chicos, pero pixela en tarjetas grandes (p.ej. carruseles del home de 150dp ≈ 410px):
    // pasar `null` deja que Coil pida el tamaño MEDIDO del composable → nítido sin desperdiciar.
    requestSizePx: Int? = ComponentConfig.ThumbnailSize,
    downloadProgress: Float? = null,
    isDownloading: Boolean = false,
    useFillAnimation: Boolean = false // New parameter
) {
    val isDarkTheme = isSystemInDarkTheme()
    val primaryColor = MaterialTheme.colorScheme.primary

    val colors = remember(isDarkTheme) {
        AlbumArtColors(
            placeholderColor = if (isDarkTheme) PlaceholderDark else PlaceholderLight,
            iconTint = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF666666)
        )
    }

    val density = LocalDensity.current
    val iconSizeSp = remember(size, density) { with(density) { (size / 2).toSp() } }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape ?: RoundedCornerShape(cornerRadius))
            .background(colors.placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        // ... (AsyncImage logic) ...
        if (albumArtUri != null) {
            val context = LocalContext.current
            val imageRequest = remember(albumArtUri, cacheKey, requestSizePx) {
                ImageRequest.Builder(context)
                    .data(albumArtUri)
                    .apply {
                        // requestSizePx != null → tamaño fijo (thumbnails de listas). null → sin
                        // .size(), Coil resuelve al tamaño medido del composable (tarjetas grandes).
                        if (requestSizePx != null) size(requestSizePx)
                        if (cacheKey != null) {
                            memoryCacheKey(cacheKey)
                            diskCacheKey(cacheKey)
                        }
                    }
                    .crossfade(200)
                    .build()
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = stringResource(R.string.common_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // LOGICA DE ESTADO (Spinner vs Animation)
        if (isDownloading || (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f)) {

             // A. FILLING ANIMATION (Solo si se solicita explícitamente y hay progreso)
             if (useFillAnimation && downloadProgress != null && downloadProgress > 0f) {
                 // Fondo transparente (Overlay directo)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                )

                // Icono de relleno progresivo
                Box(contentAlignment = Alignment.Center) {
                    // Fondo del icono (Color sutil del tema para que sea visible en Dark Mode)
                    MaterialSymbol("download", color = colors.iconTint.copy(alpha = 0.3f), size = iconSizeSp)

                    // Frente del icono (Color primario) - Recortado (Top-to-Bottom)
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(object : androidx.compose.ui.graphics.Shape {
                                 override fun createOutline(
                                    size: androidx.compose.ui.geometry.Size,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    density: androidx.compose.ui.unit.Density
                                ): androidx.compose.ui.graphics.Outline {
                                    return androidx.compose.ui.graphics.Outline.Rectangle(
                                        androidx.compose.ui.geometry.Rect(
                                            0f,
                                            0f, // Start from Top
                                            size.width,
                                            size.height * downloadProgress // Fill downwards
                                        )
                                    )
                                }
                            })
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                             MaterialSymbol("download", color = primaryColor, size = iconSizeSp)
                        }
                    }
                }
             }
             // B. SPINNER ESTÁNDAR (Default)
             else {
                 // Estado "En Cola", "Preparando" o "Descargando": LoadingIndicator expressive
                 // (morfea entre MaterialShapes) en sus dos variantes — determinada cuando hay
                 // progreso, indeterminada mientras se prepara. Mismo lenguaje que el resto de
                 // los indicadores de descarga de la app.
                 if (downloadProgress != null && downloadProgress > 0f) {
                     LoadingIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(size / 2),
                        color = primaryColor
                     )
                 } else {
                     LoadingIndicator(
                        modifier = Modifier.size(size / 2),
                        color = primaryColor
                     )
                 }
             }
        } else {
             // Solo mostrar icono de nota musical si NO hay descarga activa ni en cola Y no hay carátula
             if (albumArtUri == null) {
                 MaterialSymbol(
                    icon = "music_note",
                    size = iconSizeSp,
                    color = colors.iconTint
                )
             }
        }
    }
}

@Immutable
private data class AlbumArtColors(
    val placeholderColor: Color,
    val iconTint: Color
)
