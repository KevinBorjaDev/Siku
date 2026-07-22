package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
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
import androidx.compose.ui.res.pluralStringResource
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.ui.components.FilteredEmptyHint
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SortChip
import com.qhana.siku.ui.components.SourceFilterChips
import com.qhana.siku.ui.components.TonalChip

/**
 * Pestaña "Álbumes": cuadrícula de 2 columnas con carátula representativa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumsScreen(
    albums: List<AlbumSummary>,
    onAlbumClick: (String) -> Unit,
    onPlayAlbum: (String) -> Unit,
    contentPadding: PaddingValues,
    sortOrder: AlbumSortOrder,
    onSortOrderChange: (AlbumSortOrder) -> Unit,
    sourceFilters: Set<SongSourceFilter>,
    onToggleSourceFilter: (SongSourceFilter) -> Unit,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Solo el vacío REAL sale por aquí; con filtro activo el toolbar se conserva (ver Artistas).
    if (albums.isEmpty() && sourceFilters.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaterialSymbol(
                    "album",
                    size = 64.sp,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.album_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // 2 columnas de tarjetas (Card): carátula con badge de nº de canciones arriba, y en el
    // contenido el nombre/artista + botón de play.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            // El top que llega incluye el alto del header (el contenido pasa por debajo
            // de TopBar+tabs): NO descartarlo o la cuadrícula nace tapada por las tabs.
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Toolbar de la cuadrícula: conteo + orden + chips de origen, mismo FlowRow (envuelve,
        // sin scroll horizontal) que Artistas y la pestaña Todas. El conteo refleja lo filtrado.
        item(key = "album_toolbar", span = { GridItemSpan(maxLineSpan) }, contentType = "listToolbar") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Centrar cada fila: el TonalChip de conteo es más alto que los demás chips.
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TonalChip {
                    Text(
                        text = pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                SortChip(
                    current = sortOrder,
                    options = listOf(
                        R.string.sort_name_asc to AlbumSortOrder.NAME,
                        R.string.sort_by_artist to AlbumSortOrder.ARTIST,
                        R.string.sort_recent_first to AlbumSortOrder.RECENTLY_ADDED
                    ),
                    onChange = onSortOrderChange
                )
                SourceFilterChips(sourceFilters, showLocalChip, showCloudChips, onToggleSourceFilter)
            }
        }
        // Filtro de origen sin resultados: aviso que conserva el toolbar.
        if (albums.isEmpty()) {
            item(key = "album_filtered_empty", span = { GridItemSpan(maxLineSpan) }) {
                FilteredEmptyHint()
            }
        }
        items(albums, key = { it.name }) { album ->
            AlbumTileCard(
                album = album,
                onClick = { onAlbumClick(album.name) },
                onPlayClick = { onPlayAlbum(album.name) },
                // Carátula = shared element hacia el header del detalle del álbum.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

/**
 * Tarjeta de álbum de la pestaña Álbumes (grilla de 2 columnas): `Card` tonal con la carátula
 * arriba (esquinas superiores redondeadas por el recorte del card) llevando un badge con el nº
 * de canciones, y debajo el contenido —nombre + artista a la izquierda y botón de play a la
 * derecha—. Dedicada a esta pestaña (no comparte diseño con el grid del detalle de artista, que
 * sigue usando `AlbumGridCard`). La carátula es shared element (key `album_image_<nombre>`) hacia
 * el header del detalle de álbum.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlbumTileCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth()
    ) {
        val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "album_image_${album.name}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        } else Modifier

        // Carátula cuadrada + badge de nº de canciones.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedModifier)
                .background(colorScheme.surfaceContainerHighest)
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
                    MaterialSymbol("music_note", size = 40.sp, color = colorScheme.onSurfaceVariant)
                }
            }
            // Badge tonal sólido (sin glassmorphism) con el conteo de canciones.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                MaterialSymbol("music_note", size = 13.sp, color = colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${album.songCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSecondaryContainer
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist.ifBlank { "Artista desconocido" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // SIN fondo; el glifo `play_circle` (disco relleno) se lee como botón. En primary.
            IconButton(
                onClick = onPlayClick,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(40.dp)
            ) {
                MaterialSymbol("play_circle", size = 30.sp, color = colorScheme.primary, fill = false)
            }
        }
    }
}
