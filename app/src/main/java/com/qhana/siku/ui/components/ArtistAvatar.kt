package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.qhana.siku.ui.theme.AppColors

/**
 * La foto de un artista con su forma cookie, o el placeholder si no hay ninguna.
 *
 * **La forma va HORNEADA en el bitmap** (`ArtistPhotoMask`, una transformación de Coil que se
 * cachea) y no como `clip`: recortar un `Path` arbitrario por fila se paga en cada frame del scroll
 * y era el jank de la pestaña Artistas. El placeholder sí la dibuja como relleno de fondo, que es
 * más barato que clipear una capa y solo lo hacen unas pocas filas.
 *
 * El tamaño lo pone el [modifier] del caller. El `.size()` del request es fijo a propósito
 * ([ComponentConfig.ThumbnailSize], mismo patrón que `AlbumArt`): decode chico y hit de caché
 * determinista en vez de decodificar los 250px que sirve Deezer.
 *
 * @param art la imagen YA resuelta por la cascada de la app (foto de Deezer → carátula de alguno de
 *   sus álbumes). `null` = placeholder. El paso intermedio existe porque "ninguno de estos" en el
 *   picker es una decisión del usuario y no un fallo de carga.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistAvatar(
    art: String?,
    modifier: Modifier = Modifier,
    placeholderIconSize: TextUnit = 24.sp
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (art != null) {
            val context = LocalContext.current
            val request = remember(art) {
                ImageRequest.Builder(context)
                    .data(art)
                    .size(ComponentConfig.ThumbnailSize)
                    .transformations(ArtistPhotoMask)
                    .memoryCacheKey(artistPhotoCacheKey(art))
                    .crossfade(200)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.surfaceContainerHighest, ArtistPhotoShape.toShape()),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("artist", size = placeholderIconSize, color = AppColors.onSurfaceVariant)
            }
        }
    }
}

/**
 * Key de caché en memoria de la foto de un artista. La comparten [ArtistAvatar] (que la escribe) y
 * el header del detalle de artista (que la lee como `placeholderMemoryCacheKey`).
 *
 * **Sale de la IMAGEN y NUNCA del nombre del artista.** Con una key explícita Coil no mira la URL:
 * si hay un bitmap bajo esa key lo sirve y no descarga nada. Por nombre, cambiar la foto en el
 * picker (o que el backfill sustituya la carátula de respaldo) dejaba el avatar con la imagen
 * vieja en todas las superficies. El prefijo existe porque este bitmap lleva la máscara cookie
 * horneada: la misma carátula sin máscara tiene su propia entrada.
 */
fun artistPhotoCacheKey(art: String): String = "artist_photo_$art"
