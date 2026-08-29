package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.ui.theme.AppColors

/** Radio del `Card` del tile. La punta del shared element deriva de él sus esquinas superiores. */
private val AlbumTileCorner = 20.dp

/**
 * Tarjeta de álbum: `Card` tonal con la carátula arriba (esquinas superiores redondeadas por el
 * recorte del card) llevando un badge con el nº de canciones, y debajo el contenido —nombre +
 * artista a la izquierda y botón de play a la derecha—.
 *
 * Es la ÚNICA tarjeta de álbum de la app: la usan tanto la cuadrícula de la pestaña Álbumes como
 * el carrusel del detalle de artista, que antes tenía su propio formato (carátula suelta con el
 * play flotando encima y el texto colgando debajo, sin contenedor). Dos diseños para la misma
 * entidad se leían como dos cosas distintas.
 *
 * La carátula es shared element (key `album_image_<nombre>`) hacia el header del detalle de
 * álbum cuando se le pasan los dos scopes.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumTileCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Encolar el álbum entero. Null = sin overflow (superficies donde la acción no aplica). */
    onAddToQueue: (() -> Unit)? = null,
    /** En el detalle de artista sobra: todos los álbumes son de ese mismo artista. */
    showArtist: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(AlbumTileCorner),
        colors = CardDefaults.cardColors(containerColor = AppColors.contentSurface),
        modifier = modifier.fillMaxWidth()
    ) {
        // La forma que viaja son las esquinas SUPERIORES del card: la carátula ocupa su borde de
        // arriba, así que abajo es un corte recto contra el contenido. Sin declararla, al despegar
        // salían las cuatro esquinas vivas — el recorte lo pone el `Card`, que es un ANCESTRO, y el
        // overlay del `SharedTransitionScope` se los salta. Ver [entityImageSharedBounds].
        val sharedModifier = entityImageSharedBounds(
            key = "album_image_${album.name}",
            shape = RoundedCornerShape(topStart = AlbumTileCorner, topEnd = AlbumTileCorner),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )

        // Carátula cuadrada + badge de nº de canciones.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedModifier)
                .background(AppColors.surfaceContainerHighest)
        ) {
            if (album.albumArtUri != null) {
                AsyncImage(
                    model = album.albumArtUri,
                    contentDescription = stringResource(R.string.album_art_desc, album.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol("music_note", size = 40.sp, color = AppColors.onSurfaceVariant)
                }
            }
            // Badge tonal sólido (sin glassmorphism) con el conteo de canciones.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                MaterialSymbol("music_note", size = 13.sp, color = AppColors.onSecondaryContainer)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${album.songCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.onSecondaryContainer
                )
            }
            // Overflow (encolar el álbum) SOBRE la carátula, no en la fila de abajo: en una
            // cuadrícula de dos columnas esa fila ya reparte su ancho entre el título y el play, y
            // meter una píldora más dejaba el nombre del álbum en ~70 dp, o sea elipsado casi
            // siempre. Aquí no le quita ancho a nada.
            //
            // Con el par `secondaryContainer` SÓLIDO, igual que el badge de conteo que ya vive en
            // esta misma imagen: sobre una carátula no hay fondo del que derivar un tono (el color
            // de debajo cambia con cada píxel), así que la única separación garantizada es poner
            // una superficie propia — que es lo que M3 hace con un par contenedor/contenido.
            if (onAddToQueue != null) {
                QueueOverflowButton(
                    onAddToQueue = onAddToQueue,
                    colors = TonalLayerColors(
                        container = AppColors.secondaryContainer,
                        content = AppColors.onSecondaryContainer
                    ),
                    contentDescription = stringResource(R.string.common_album_options),
                    menuLabel = stringResource(R.string.detail_add_all_to_queue),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }

        // Contenido: nombre + artista (izquierda) y botón de play (derecha).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name.ifBlank { stringResource(R.string.common_unknown_album) },
                    // `titleSmallEmphasized` en vez de `titleSmall + SemiBold`: el rol del scale
                    // tiene las MISMAS métricas (14sp, alto 20, tracking 0.1) y solo sube el peso,
                    // así que es exactamente lo que la copia a mano quería decir.
                    //
                    // El color se queda en `onSurface` A PROPÓSITO, no por omisión: el acento
                    // marca lo accionable, y esta tarjeta ya gasta `primary` en el botón de play.
                    // Un título en `primary` competiría con él y el play dejaría de leerse como
                    // EL control de la tarjeta. Además `onSurface` no es "sin color" — en M3 los
                    // neutros llevan croma del seed, así que el título ya está teñido por la
                    // carátula, al nivel que el spec reserva para texto.
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showArtist) {
                    Text(
                        text = album.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // SIN fondo; el glifo `play_circle` (disco relleno) se lee como botón. En primary.
            IconButton(
                onClick = onPlayClick,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(40.dp)
            ) {
                MaterialSymbol("play_circle", size = 30.sp, color = AppColors.primary, fill = true)
            }
        }
    }
}
