package com.qhana.siku.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.ScrollbarSelectionMode
import my.nanihadesuka.compose.ScrollbarSettings

@Composable
fun SongsScreen(
    currentFilter: SongFilter,
    contentPadding: PaddingValues,
    onNavigateToNowPlaying: () -> Unit,
    onAddToPlaylistRequest: (String) -> Unit,
    viewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    playingAccent: Color? = null,
    // Chip "N canciones" + chip de orden + chips de origen (solo pestaña Todas, fuera de la búsqueda).
    songCount: Int = 0,
    sortOrder: SortOrder = SortOrder.TITLE_ASC,
    onSortOrderChange: (SortOrder) -> Unit = {},
    onToggleSourceFilter: (SongSourceFilter) -> Unit = {},
    // Búsqueda seccionada (solo pestaña Todas): artistas/álbumes que matchean la query,
    // renderizados como carruseles encima de las canciones.
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    searchArtists: List<ArtistSummary> = emptyList(),
    searchAlbums: List<AlbumSummary> = emptyList(),
    onSearchArtistClick: (String) -> Unit = {},
    onSearchAlbumClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()

    // Data Sources
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // El overlay de búsqueda usa el paging SIN chips de origen (busca en toda la
    // biblioteca); la pestaña Todas, el filtrado. isSearchActive es fijo por instancia,
    // así que la elección no cambia en runtime.
    val pagedSongs = (if (isSearchActive) viewModel.pagedSearchSongs else viewModel.pagedSongs)
        .collectAsLazyPagingItems()
    val pagedFavorites = viewModel.pagedFavorites.collectAsLazyPagingItems()

    // Properties from UiState
    val favorites = uiState.favorites
    val isSelectionMode = uiState.isSelectionMode
    val selectedSongs = uiState.selectedSongs
    val redownloadingIds by viewModel.redownloadingIds.collectAsStateWithLifecycle()
    val downloadProgressById by viewModel.downloadProgressById.collectAsStateWithLifecycle()

    // State
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()

    // Derived State (optimized to prevent unnecessary recompositions)
    val currentSongId by remember { derivedStateOf { currentSong?.id } }
    val primaryColor = MaterialTheme.colorScheme.primary
    val neutralColor = if (isDarkTheme) Color(0xFF4A4A4A) else MaterialTheme.colorScheme.surfaceDim

    // Scrollbar Configuration
    val listState = rememberLazyListState()
    
    // Optimización: Recrear settings solo cuando cambia el tema (Oscuro/Claro)
    // Evita recomposiciones por cambios menores en colores dinámicos si no cambia el modo
    val scrollbarThumbColor = if (isDarkTheme) Color(0xFF4A4A4A) else MaterialTheme.colorScheme.surfaceDim
    val scrollbarActiveColor = MaterialTheme.colorScheme.primary
    val scrollbarSettings = remember(scrollbarThumbColor, scrollbarActiveColor) {
        ScrollbarSettings(
            thumbUnselectedColor = scrollbarThumbColor,
            thumbSelectedColor = scrollbarActiveColor,
            selectionMode = ScrollbarSelectionMode.Thumb,
            thumbThickness = 6.dp,
            thumbShape = CircleShape,
        )
    }

    val itemCount = when (currentFilter) {
        SongFilter.ALL -> pagedSongs.itemCount
        SongFilter.FAVORITES -> pagedFavorites.itemCount
        else -> 0
    }

    val hasSearchSections = searchArtists.isNotEmpty() || searchAlbums.isNotEmpty()

    // Los chips viven en la pestaña Todas (no en favoritos ni en el overlay de búsqueda).
    val sourceFilters = uiState.sourceFilters
    val showSourceChips = currentFilter == SongFilter.ALL && !isSearchActive
    // Visibilidad por familias presentes en la biblioteca (ver SourceFilterRow).
    val hasLocalSongs by viewModel.hasLocalSongs.collectAsStateWithLifecycle()
    val hasCloudSongs by viewModel.hasCloudSongs.collectAsStateWithLifecycle()
    // Local solo si hay AMBAS familias (con solo-local no hay nada que filtrar);
    // Descargadas/Nube con que haya nube (solo-nube: offline vs streaming sigue valiendo).
    val showLocalChip = hasLocalSongs && hasCloudSongs
    val showCloudChips = hasCloudSongs

    // Al cambiar la query, volver arriba: si no, el scroll se queda donde estaba y las
    // secciones de artistas/álbumes (que van al principio) quedan fuera de pantalla.
    LaunchedEffect(searchQuery, isSearchActive) {
        if (isSearchActive) listState.scrollToItem(0)
    }

    // Vacío CONFIRMADO por Paging, no "0 ítems en este frame". `itemCount` arranca en 0 al montar
    // (LazyPagingItems recién creado) y el empty state se pintaba en ese primer frame: eso es el
    // parpadeo. `append.endOfPaginationReached` solo es true cuando el PagingSource confirmó que
    // no hay más páginas — en `InitialLoadStates` es false, así que cubre el montaje sin recurrir
    // a un delay. `refresh is NotLoading` descarta además el refresh en curso.
    val loadState = when (currentFilter) {
        SongFilter.FAVORITES -> pagedFavorites.loadState
        else -> pagedSongs.loadState
    }
    val isEmptyConfirmed = itemCount == 0 &&
        loadState.refresh is androidx.paging.LoadState.NotLoading &&
        loadState.append.endOfPaginationReached

    // HISTÉRESIS del vacío: durante un sync, CADA escritura en `songs` (cada descarga que
    // termina) invalida el PagingSource y `refresh` pasa por Loading un instante →
    // `isEmptyConfirmed` oscila true/false y la UI parpadeaba entre el empty state y la
    // rama en blanco mientras durara el sync. El latch solo cambia con evidencia firme
    // (items presentes o vacío confirmado) y se mantiene durante los Loading transitorios.
    var confirmedEmpty by remember { mutableStateOf<Boolean?>(null) }
    // Cambió la QUERY (búsqueda/orden/filtros/pestaña): el veredicto anterior no vale para
    // la nueva — volver a "sin veredicto" evita mostrar unos frames el empty state viejo
    // (p. ej. quitar el último chip mostraba "sincroniza…" hasta que cargaban los items).
    LaunchedEffect(uiState.searchQuery, uiState.sortOrderAll, sourceFilters, currentFilter) {
        confirmedEmpty = null
    }
    LaunchedEffect(itemCount, isEmptyConfirmed) {
        when {
            itemCount > 0 -> confirmedEmpty = false
            isEmptyConfirmed -> confirmedEmpty = true
        }
    }

    if (itemCount == 0 && !hasSearchSections) {
        when {
            // Aún sin veredicto (primer montaje): LazyColumn vacía (no un Box) para que el
            // PullToRefreshBox padre conserve su descendiente scrollable mientras Paging
            // resuelve.
            confirmedEmpty != true -> LazyColumn(modifier = Modifier.fillMaxSize()) {}
            isSearchActive -> SearchNoResults()
            // Con chips activos el vacío DEBE conservar la fila de chips (sin ella no hay
            // cómo quitar el filtro que dejó la lista en cero) y su mensaje es el de
            // filtros sin resultados, NO el de biblioteca vacía ("sincroniza…").
            showSourceChips && sourceFilters.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding
            ) {
                item(key = "source_filters", contentType = "sourceFilters") {
                    SourceFilterRow(songCount, sortOrder, onSortOrderChange, sourceFilters, showLocalChip, showCloudChips, onToggleSourceFilter)
                }
                item { FilteredEmptyBody() }
            }
            else -> EmptyContent(currentFilter)
        }
    } else {
        LazyColumnScrollbar(
            state = listState,
            settings = scrollbarSettings,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding
            ) {
                if (showSourceChips) {
                    item(key = "source_filters", contentType = "sourceFilters") {
                        SourceFilterRow(songCount, sortOrder, onSortOrderChange, sourceFilters, showLocalChip, showCloudChips, onToggleSourceFilter)
                    }
                }
                if (searchArtists.isNotEmpty()) {
                    item(key = "search_header_artists", contentType = "searchHeader") {
                        SearchSectionHeader(stringResource(R.string.common_artists))
                    }
                    item(key = "search_row_artists", contentType = "searchArtists") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(searchArtists, key = { it.name }) { artist ->
                                SearchArtistCard(artist) { onSearchArtistClick(artist.name) }
                            }
                        }
                    }
                }
                if (searchAlbums.isNotEmpty()) {
                    item(key = "search_header_albums", contentType = "searchHeader") {
                        SearchSectionHeader(stringResource(R.string.common_albums))
                    }
                    item(key = "search_row_albums", contentType = "searchAlbums") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(searchAlbums, key = { it.name }) { album ->
                                SearchAlbumCard(album) { onSearchAlbumClick(album.name) }
                            }
                        }
                    }
                }
                if (hasSearchSections) {
                    item(key = "search_header_songs", contentType = "searchHeader") {
                        SearchSectionHeader(if (itemCount > 0) stringResource(R.string.songs_header) else stringResource(R.string.search_no_songs))
                    }
                }
                items(
                    count = itemCount,
                    key = { index ->
                        when (currentFilter) {
                            SongFilter.ALL -> {
                                if (index < pagedSongs.itemCount) {
                                    pagedSongs.peek(index)?.id ?: index
                                } else index
                            }
                            SongFilter.FAVORITES -> {
                                if (index < pagedFavorites.itemCount) {
                                    pagedFavorites.peek(index)?.id ?: index
                                } else index
                            }
                            else -> index
                        }
                    },
                    contentType = { "song" }
                ) { index ->
                    val song = when (currentFilter) {
                        SongFilter.ALL -> if (index < pagedSongs.itemCount) pagedSongs[index] else null
                        SongFilter.FAVORITES -> if (index < pagedFavorites.itemCount) pagedFavorites[index] else null
                        else -> null
                    }

                    if (song != null) {
                        val songId = song.id
                        val isSelected = songId in selectedSongs
                        val isPlaying = songId == currentSongId

                        val shape = rememberListItemShape(
                            index = index,
                            count = itemCount,
                            isActive = isPlaying || isSelected
                        )

                        SongItemOptimized(
                            modifier = Modifier.animateItem(),
                            song = song,
                            isPlaying = isPlaying,
                            playingAccent = playingAccent,
                            isFavorite = songId in favorites,
                            isRedownloading = songId in redownloadingIds,
                            downloadProgress = downloadProgressById[songId],
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            shape = shape,
                            onSongClick = {
                                // La cola debe ser EXACTAMENTE la lista visible (misma
                                // búsqueda + orden + filtros), no el orden alfabético fijo.
                                playbackViewModel.playSongFromLibrary(
                                    it,
                                    currentFilter,
                                    uiState.searchQuery,
                                    if (currentFilter == SongFilter.FAVORITES) uiState.sortOrderFavorites
                                    else uiState.sortOrderAll,
                                    // La cola espeja la lista visible: el overlay de búsqueda
                                    // no aplica chips, así que su cola tampoco.
                                    if (currentFilter == SongFilter.ALL && !isSearchActive) sourceFilters else emptySet()
                                )
                                onNavigateToNowPlaying()
                            },
                            onRedownload = { viewModel.redownloadSong(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            songId = songId,
                            onSelectionChange = { id, selected -> viewModel.toggleSelection(id) },
                            onStartSelection = { viewModel.startSelection(it) },
                            onAddToPlaylistRequest = onAddToPlaylistRequest,
                            onStatusClick = {
                                viewModel.showMessage(
                                    if (song.isLocalAudio) context.getString(R.string.status_ready_offline)
                                    else context.getString(R.string.status_will_stream)
                                )
                            }
                        )
                    } else {
                        if (currentFilter == SongFilter.ALL) LoadingSongItem()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongItemOptimized(
    song: Song,
    isPlaying: Boolean,
    playingAccent: Color?,
    isFavorite: Boolean,
    isRedownloading: Boolean,
    downloadProgress: Float?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onSongClick: (Song) -> Unit,
    onRedownload: (Song) -> Unit,
    onToggleFavorite: (String) -> Unit,
    songId: String,
    onSelectionChange: (String, Boolean) -> Unit,
    onStartSelection: (String) -> Unit,
    onAddToPlaylistRequest: (String) -> Unit,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = isSelected || isPlaying
    val surfaceHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val backgroundColor = when {
        // Activo (reproduciendo o seleccionado): resaltado teñido con el acento del álbum.
        isActive && playingAccent != null ->
            Color(androidx.core.graphics.ColorUtils.blendARGB(surfaceHigh.toArgb(), playingAccent.toArgb(), 0.30f))
        isActive -> primaryContainer
        else -> surfaceHigh
    }

    val trailingContent: @Composable (() -> Unit)? = remember(isSelectionMode, isFavorite, songId, isRedownloading, isPlaying, song.isLocalAudio) {
        if (!isSelectionMode) {
            {
                SongItemMenu(
                    isPlaying = isPlaying,
                    isFavorite = isFavorite,
                    isRedownloading = isRedownloading,
                    songId = songId,
                    // Por sourceType, no por isLocalAudio: la nube DESCARGADA (file://) también
                    // es isLocalAudio y re-descargarla (reparar) sí tiene sentido. Solo la
                    // fuente LOCAL queda fuera (no hay copia en la nube que bajar).
                    showRedownload = song.sourceType != SourceType.LOCAL,
                    isDownloaded = song.isLocalAudio,
                    onRedownload = { onRedownload(song) },
                    onToggleFavorite = onToggleFavorite,
                    onAddToPlaylistRequest = onAddToPlaylistRequest
                )
            }
        } else null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 1dp por fila = 2dp de gap entre tarjetas, como en los detalles de
            // artista/álbum y el patrón agrupado Expressive de referencia.
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(backgroundColor)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onSelectionChange(songId, !isSelected)
                    else onSongClick(song)
                },
                onLongClick = {
                    if (!isSelectionMode) onStartSelection(songId)
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelectionChange(songId, !isSelected) },
                modifier = Modifier.padding(start = 8.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = playingAccent ?: MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        SongItem(
            song = song,
            isPlaying = isPlaying,
            modifier = Modifier.weight(1f),
            // Progreso real de la descarga en vuelo: anillo sobre la carátula + "Descargando N%".
            isDownloading = downloadProgress != null,
            downloadProgress = downloadProgress,
            onStatusClick = onStatusClick,
            trailingContent = trailingContent
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SongItemMenu(
    isPlaying: Boolean,
    isFavorite: Boolean,
    isRedownloading: Boolean,
    songId: String,
    showRedownload: Boolean,
    isDownloaded: Boolean,
    onRedownload: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylistRequest: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        // Píldora vertical tonal pequeña, mismo estilo que el overflow de las pantallas de
        // detalle de artista/álbum (SongOverflowButton). Sobre el fondo TEÑIDO del ítem
        // en reproducción el tonal se pierde → sube a acento. En tema CLARO el primary
        // seeded es un tono oscuro (quedaba un botón casi negro): se aclara hacia blanco
        // manteniendo el matiz del álbum.
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        val container = when {
            !isPlaying -> MaterialTheme.colorScheme.secondaryContainer
            // En oscuro el primary seedeado es un tono CLARO (quedaba una píldora casi
            // blanca): se oscurece hacia negro manteniendo el matiz del álbum — espejo
            // del aclarado hacia blanco del tema claro. 30% = punto medio: más del 40%
            // se confundía con el fondo teñido de la fila en reproducción.
            isDark -> androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.primary, Color.Black, 0.30f
            )
            else -> androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.primary, Color.White, 0.55f
            )
        }
        val content = if (isPlaying) onContainerColor(container)
                      else MaterialTheme.colorScheme.onSecondaryContainer
        // FilledIconButton real (M3 Expressive: shape-morph al presionar) en vez de
        // Surface+Box artesanal. Píldora VERTICAL, igual que SongOverflowButton en los detalles.
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = content)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_add_to_playlist)) },
                onClick = { onAddToPlaylistRequest(songId); showMenu = false },
                leadingIcon = { MaterialSymbol("playlist_add") }
            )
            // Sin sentido para música LOCAL (no hay copia en la nube que volver a bajar).
            if (showRedownload) {
                DropdownMenuItem(
                    // "Redescargar" solo si YA está en disco; para una canción en streaming
                    // la acción es una primera descarga y la etiqueta debe decirlo. En curso
                    // siempre "Descargando…": la re-descarga borra el archivo primero, así
                    // que en ese momento se está descargando, a secas.
                    text = { Text(when {
                        isRedownloading -> stringResource(R.string.status_downloading_generic)
                        isDownloaded -> stringResource(R.string.common_redownload)
                        else -> stringResource(R.string.np_download)
                    }) },
                    onClick = { onRedownload(); showMenu = false },
                    enabled = !isRedownloading,
                    leadingIcon = { MaterialSymbol("sync") }
                )
            }
            DropdownMenuItem(
                text = { Text(if (isFavorite) stringResource(R.string.menu_remove_favorite) else stringResource(R.string.menu_favorite)) },
                onClick = { onToggleFavorite(songId); showMenu = false },
                leadingIcon = {
                    MaterialSymbol("favorite", fill = isFavorite)
                }
            )
        }
    }
}

/**
 * Fila sobre la lista de Todas: chip informativo "N canciones" (el conteo que antes vivía
 * de subtítulo en la TopBar) + chip de ORDEN (menú desplegable, antes en la barra de
 * búsqueda) + FilterChips de origen, combinables por UNIÓN (Local + Descargadas = todo lo
 * offline). "Todas" no existe como chip: es el estado sin filtros (set vacío).
 *
 * Visibilidad por CONTENIDO: con biblioteca solo-local no hay chips de origen (solo conteo +
 * orden); Local solo aparece si además hay nube; Descargadas/Nube solo si hay nube.
 * FlowRow (no scroll horizontal): conteo + orden + 3 chips envuelven a la siguiente fila.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceFilterRow(
    songCount: Int,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    sourceFilters: Set<SongSourceFilter>,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    onToggle: (SongSourceFilter) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // El chip de conteo (TonalChip) es más alto que los AssistChip/FilterChip; centrar
        // cada fila los alinea (si no, el default Top deja los chips cortos pegados arriba).
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp)
    ) {
        TonalChip {
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.song_count, songCount, songCount
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        SortChip(
            current = sortOrder,
            options = listOf(
                R.string.sort_title_asc to SortOrder.TITLE_ASC,
                R.string.sort_title_desc to SortOrder.TITLE_DESC,
                R.string.sort_recent_first to SortOrder.DATE_ADDED_DESC,
                R.string.sort_oldest_first to SortOrder.DATE_ADDED_ASC,
                R.string.sort_most_played to SortOrder.MOST_PLAYED,
                R.string.sort_recently_played to SortOrder.RECENTLY_PLAYED
            ),
            onChange = onSortOrderChange
        )
        SourceFilterChips(sourceFilters, showLocalChip, showCloudChips, onToggle)
    }
}

// --- Búsqueda seccionada ---

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun SearchArtistCard(artist: ArtistSummary, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (artist.thumbUrl != null) {
                AsyncImage(
                    model = artist.thumbUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MaterialSymbol("artist", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name.ifBlank { stringResource(R.string.common_unknown) },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchAlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        AlbumArt(
            albumArtUri = album.albumArtUri,
            size = 72.dp,
            cornerRadius = 16.dp,
            cacheKey = "search_album_${album.name}"
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name.ifBlank { stringResource(R.string.common_unknown) },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchNoResults() {
    // LazyColumn por la misma razón que EmptyContent: el PullToRefreshBox padre necesita
    // un descendiente scrollable.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    MaterialSymbol(
                        "search_off",
                        size = 64.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.search_no_results),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyContent(currentFilter: SongFilter) {
    // LazyColumn aunque sea un único item: el padre PullToRefreshBox necesita un
    // descendiente scrollable para capturar el gesto, si no, no se dispara.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            EmptyContentBody(currentFilter)
        }
    }
}

/** Vacío por CHIPS de origen: hay biblioteca, pero ninguna canción pasa los filtros. */
@Composable
private fun LazyItemScope.FilteredEmptyBody() {
    Box(
        modifier = Modifier.fillParentMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            MaterialSymbol(
                "filter_alt_off",
                size = 64.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.filter_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.filter_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun LazyItemScope.EmptyContentBody(currentFilter: SongFilter) {
    Box(
        modifier = Modifier.fillParentMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            val (icon, title, subtitle) = when (currentFilter) {
                SongFilter.ALL -> Triple(
                    "cloud_download",
                    stringResource(R.string.songs_empty_title),
                    stringResource(R.string.songs_empty_subtitle)
                )
                SongFilter.FAVORITES -> Triple(
                    "favorite",
                    stringResource(R.string.favorites_empty_title),
                    stringResource(R.string.favorites_empty_subtitle)
                )
                else -> Triple(
                    "music_note",
                    stringResource(R.string.songs_empty_generic),
                    ""
                )
            }

            MaterialSymbol(
                icon,
                size = 64.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

