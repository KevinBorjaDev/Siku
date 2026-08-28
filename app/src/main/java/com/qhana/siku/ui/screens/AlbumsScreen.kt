package com.qhana.siku.ui.screens

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.compose.ui.res.pluralStringResource
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.ui.components.AdaptiveGridCells
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.AlbumTileCard
import com.qhana.siku.ui.components.FilteredEmptyHint
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SortChip
import com.qhana.siku.ui.components.SourceFilterChips
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.theme.AppColors

/**
 * Pestaña "Álbumes": cuadrícula de 2 columnas con carátula representativa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumsScreen(
    albums: List<AlbumSummary>,
    onAlbumClick: (String) -> Unit,
    onPlayAlbum: (String) -> Unit,
    /** Encola TODAS las canciones del álbum al final de la cola, desde el overflow de la tarjeta. */
    onAddAlbumToQueue: (String) -> Unit,
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
                    color = AppColors.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.album_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        return
    }

    // 2 columnas de tarjetas (Card): carátula con badge de nº de canciones arriba, y en el
    // contenido el nombre/artista + botón de play.
    LazyVerticalGrid(
        // Adaptativa CON SUELO de dos columnas, no `GridCells.Adaptive` a secas: en vertical
        // salen siempre las dos de siempre —en cualquier teléfono, no solo en los de 393dp— y solo
        // se añaden columnas cuando la ventana da de sobra. Ver [AdaptiveGridCells].
        columns = AdaptiveGridCells(ComponentConfig.GridTileMinWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // Se SUMA lo que llega en vez de descartarlo: si el contenedor inyecta un inset lateral
            // (la perforación en horizontal), un 16 fijo lo tiraría. Es lo que ya hace PlaylistList.
            start = 16.dp + contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = 16.dp + contentPadding.calculateEndPadding(LocalLayoutDirection.current),
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
                        color = AppColors.onSecondaryContainer
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
                // La rejilla se reordena al cambiar el orden o el filtro de origen: sin esto las
                // celdas saltan de sitio en un frame.
                modifier = Modifier.animateItem(),
                onClick = { onAlbumClick(album.name) },
                onPlayClick = { onPlayAlbum(album.name) },
                onAddToQueue = { onAddAlbumToQueue(album.name) },
                // Carátula = shared element hacia el header del detalle del álbum.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

