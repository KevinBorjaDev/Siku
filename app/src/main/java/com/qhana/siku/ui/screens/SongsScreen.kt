package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.qhana.siku.ui.theme.appSelectableMenuItemColors
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appMenuItemColors
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
    // Chip "N canciones" + chip de orden + chips de origen (solo pestaña Todas, fuera de la búsqueda).
    songCount: Int = 0,
    sortOrder: SortOrder = SortOrder.TITLE_ASC,
    onSortOrderChange: (SortOrder) -> Unit = {},
    onToggleSourceFilter: (SongSourceFilter) -> Unit = {},
    // Reproducir la lista ENTERA, en orden o al azar (solo pestaña Todas, fuera de la búsqueda).
    // Los defaults vacíos son para el overlay de búsqueda, donde la botonera no se pinta.
    onPlayAll: () -> Unit = {},
    onShuffleAll: () -> Unit = {},
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
    val redownloadingIds by viewModel.redownloadingIds.collectAsStateWithLifecycle()
    val downloadProgressById by viewModel.downloadProgressById.collectAsStateWithLifecycle()

    // State
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()

    // Derived State (optimized to prevent unnecessary recompositions)
    val currentSongId by remember { derivedStateOf { currentSong?.id } }
    val primaryColor = AppColors.primary

    // Scrollbar Configuration
    val listState = rememberLazyListState()

    // `outlineVariant` (el rol de los elementos decorativos/limítrofes) en los DOS modos. Antes
    // era `#4A4A4A` en oscuro y `surfaceDim` en claro: además de un gris fijo sin tinte, eran dos
    // roles distintos según el modo, así que el thumb no tenía un peso comparable en cada tema.
    val scrollbarThumbColor = AppColors.outlineVariant
    val scrollbarActiveColor = AppColors.primary
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
    // La REGLA de cuándo se ven los chips vive en el ViewModel (ver [LibraryViewModel.hasSourceSplit]);
    // aquí solo se lee. Estaba calculada a mano aquí Y en LibraryScreen —para Artistas/Álbumes— con
    // la misma expresión copiada, o sea dos sitios capaces de discrepar sobre cuándo hay control.
    val showLocalChip by viewModel.showLocalSourceChip.collectAsStateWithLifecycle()
    val showCloudChips by viewModel.showCloudSourceChips.collectAsStateWithLifecycle()
    // Los botones de reproducir/aleatorio de esta fila son el REPUESTO de las acciones rápidas del
    // inicio: solo existen si esa pestaña no está. La regla, igual que la de los chips de origen,
    // vive en el ViewModel (ver [LibraryViewModel.homeTabHidden]).
    val showPlayActions by viewModel.homeTabHidden.collectAsStateWithLifecycle()

    // Al cambiar la query, volver arriba: si no, el scroll se queda donde estaba y las
    // secciones de artistas/álbumes (que van al principio) quedan fuera de pantalla.
    LaunchedEffect(searchQuery, isSearchActive) {
        if (isSearchActive) listState.scrollToItem(0)
    }

    // Sonda de frames largos (solo debug): se arma al empezar cada scroll de esta lista y anota los
    // refresh del PagingSource, para ver qué se compone antes de cada frame largo. Ver JankProbe.
    //
    // **Gateada por `JankProbe.isEnabled`**, porque estos dos no son marcas sueltas sino
    // `snapshotFlow(...).collect`: colectores PERMANENTES, uno de ellos sobre el `loadState` de
    // Paging, en la lista con más presión de scroll de la app. El `inline` + early return de la
    // sonda no puede ahorrar nada ahí — el coste está en el efecto, no en la marca. Con la sonda
    // apagada (lo normal) no se registra ninguno de los dos.
    if (JankProbe.isEnabled) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) JankProbe.arm { "scroll Todas" } }
        }
        // Room invalidó el PagingSource (refresh en vuelo): las filas se recomponen con instancias nuevas.
        LaunchedEffect(pagedSongs) {
            snapshotFlow { pagedSongs.loadState.refresh }.collect { JankProbe.mark { "paging refresh=$it" } }
        }
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
                    SourceFilterRow(
                        songCount, sortOrder, onSortOrderChange, sourceFilters,
                        showLocalChip, showCloudChips, onToggleSourceFilter,
                        // Lista vacía por los filtros: ver arriba.
                        showPlayActions = false, onPlayAll = {}, onShuffleAll = {}
                    )
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
                        SourceFilterRow(
                            songCount, sortOrder, onSortOrderChange, sourceFilters,
                            showLocalChip, showCloudChips, onToggleSourceFilter,
                            // Con la lista vacía no hay nada que reproducir: en la rama de "ningún
                            // resultado" quitar un filtro tiene que seguir siendo lo único que se
                            // puede hacer ahí.
                            showPlayActions = showPlayActions && itemCount > 0,
                            onPlayAll = onPlayAll,
                            onShuffleAll = onShuffleAll
                        )
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
                                SearchArtistCard(artist, Modifier.animateItem()) {
                                    onSearchArtistClick(artist.name)
                                }
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
                                SearchAlbumCard(album, Modifier.animateItem()) {
                                    onSearchAlbumClick(album.name)
                                }
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
                        val isPlaying = songId == currentSongId

                        val shape = rememberListItemShape(
                            index = index,
                            count = itemCount,
                            isActive = isPlaying
                        )

                        SongItemOptimized(
                            modifier = Modifier.animateItem(),
                            song = song,
                            isPlaying = isPlaying,
                            isFavorite = songId in favorites,
                            isRedownloading = songId in redownloadingIds,
                            downloadProgress = downloadProgressById[songId],
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
                            onAddToPlaylistRequest = onAddToPlaylistRequest,
                            onAddToQueue = { playbackViewModel.addToQueue(song) },
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

@Composable
private fun SongItemOptimized(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    isRedownloading: Boolean,
    downloadProgress: Float?,
    shape: androidx.compose.ui.graphics.Shape,
    onSongClick: (Song) -> Unit,
    onRedownload: (Song) -> Unit,
    onToggleFavorite: (String) -> Unit,
    songId: String,
    onAddToPlaylistRequest: (String) -> Unit,
    onAddToQueue: () -> Unit,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fondo de la fila: `surface` (98) — el lado del CONTENIDO de la horquilla. La fila es MÁS
    // CLARA que la página (`surfaceContainer`, 94) y flota sobre ella, igual que las tarjetas de
    // llamada del Dialer de Google, que es la referencia de la que salió este reparto (ver
    // `headerColor` en LibraryScreen). Estuvo en `surfaceContainerHigh` (92) y luego en
    // `surfaceContainer` (94), las dos apilando hacia el lado oscuro: así la cabecera nunca
    // conseguía separarse de la banda de filas que le pasa por debajo al scrollear.
    val rowSurface = AppColors.contentSurface
    // Resaltado del ítem en reproducción, por el helper compartido y NO por el rol a pelo: es el
    // mismo tratamiento que la cola y los detalles, y tiene que salir de una sola definición. El
    // blend del acento del álbum al 30% que hubo antes quedaba casi idéntico al resto de filas en un
    // álbum monocromo (ese acento venía ya proyectado a un tono cercano a la superficie).
    val backgroundColor = songRowBackground(rowSurface, isPlaying)

    // Fondo REAL bajo el contenido de la fila, contra el que se mide la píldora de acciones: es
    // `backgroundColor` a secas porque el resaltado del ítem activo lo pinta el `Row` de abajo, no
    // `SongItem` (ver `showActiveBackground = false`). En los detalles —contenedor `surfaceContainer`
    // plano— sigue siendo `songRowBackground`, porque allí el tinte del `ListItem` es el único.
    val rowBackground = backgroundColor
    // Contenido de la fila activa: el `on-` del relleno con contraste garantizado (misma definición
    // que la cola y los detalles); en las demás filas, los roles por defecto de SongItem.
    val activeContentColor = rememberActiveRowContentColor(backgroundColor, isPlaying)

    val trailingContent: @Composable (() -> Unit)? = remember(isFavorite, songId, isRedownloading, rowBackground, song.isLocalAudio) {
        {
            SongItemMenu(
                rowBackground = rowBackground,
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
                onAddToPlaylistRequest = onAddToPlaylistRequest,
                onAddToQueue = onAddToQueue
            )
        }
    }

    // La fila es la punta ORIGEN del container transform hacia el reproductor: al tocarla, ESTA
    // superficie crece hasta ser el player (ver [SongRowContainer]). Lo que va en el envoltorio es lo
    // que la coloca en la lista (ancho y gap); lo que morfa es la superficie de dentro, con su forma
    // y su relleno — el hueco entre filas no forma parte de la tarjeta.
    SongRowContainer(
        songId = songId,
        modifier = modifier
            .fillMaxWidth()
            // 1dp por fila = 2dp de gap entre tarjetas, como en los detalles de
            // artista/álbum y el patrón agrupado Expressive de referencia.
            .padding(horizontal = 16.dp, vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(backgroundColor)
                .clickable { onSongClick(song) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongItem(
                song = song,
                isPlaying = isPlaying,
                // El `Row` de arriba ya tiñe TODA la fila con el acento del álbum; con el default en
                // true, `SongItem` sumaba encima su `secondary` al 12 % y el resaltado se pintaba dos
                // veces (el mismo motivo por el que la cola lo apaga). El propio parámetro lo dice:
                // se pone en false cuando un contenedor externo pinta el resaltado de la fila entera.
                showActiveBackground = false,
                // Texto e icono de estado en `onPrimaryContainer` cuando la fila suena (el `Row` de
                // arriba la rellena con `primaryContainer`), igual que la cola.
                activeContentColor = activeContentColor,
                modifier = Modifier.weight(1f),
                // Progreso real de la descarga en vuelo: anillo sobre la carátula + "Descargando N%".
                isDownloading = downloadProgress != null,
                downloadProgress = downloadProgress,
                onStatusClick = onStatusClick,
                trailingContent = trailingContent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SongItemMenu(
    isFavorite: Boolean,
    isRedownloading: Boolean,
    songId: String,
    showRedownload: Boolean,
    isDownloaded: Boolean,
    // Fondo REAL de la fila (con el tinte del ítem activo ya compuesto): de él salen los colores
    // de la píldora. Ver `rememberRowActionColors` — un rol fijo no garantiza separación.
    rowBackground: Color,
    onRedownload: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylistRequest: (String) -> Unit,
    onAddToQueue: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    // Píldora vertical, MISMO componente y mismos colores que el overflow de las pantallas de
    // detalle (SongOverflowButton): un solo criterio de color para las dos listas.
    val colors = rememberRowActionColors(rowBackground)
    Box {
        // FilledIconButton real (M3 Expressive: shape-morph al presionar) en vez de
        // Surface+Box artesanal. Píldora VERTICAL, igual que SongOverflowButton en los detalles.
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        AppMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = { onAddToPlaylistRequest(songId); showMenu = false },
                    text = { Text(stringResource(R.string.menu_add_to_playlist)) },
                    shape = MenuDefaults.leadingItemShape,
                    leadingIcon = { MenuItemIcon("playlist_add") }
                )
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = { onAddToQueue(); showMenu = false },
                    text = { Text(stringResource(R.string.common_add_to_queue)) },
                    shape = MenuDefaults.middleItemShape,
                    leadingIcon = { MenuItemIcon("low_priority") }
                )
                // Sin sentido para música LOCAL (no hay copia en la nube que volver a bajar).
                if (showRedownload) {
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
                        onClick = { onRedownload(); showMenu = false },
                        // "Redescargar" solo si YA está en disco; para una canción en streaming
                        // la acción es una primera descarga y la etiqueta debe decirlo. En curso
                        // siempre "Descargando…": la re-descarga borra el archivo primero, así
                        // que en ese momento se está descargando, a secas.
                        text = { Text(when {
                            isRedownloading -> stringResource(R.string.status_downloading_generic)
                            isDownloaded -> stringResource(R.string.common_redownload)
                            else -> stringResource(R.string.np_download)
                        }) },
                        shape = MenuDefaults.middleItemShape,
                        enabled = !isRedownloading,
                        // Mismo criterio que la etiqueta de arriba, que ya distingue los tres
                        // casos: en curso, redescarga y primera descarga.
                        leadingIcon = {
                            MenuItemIcon(
                                when {
                                    isRedownloading -> "hourglass_top"
                                    isDownloaded -> "sync_arrow_down"
                                    else -> "download"
                                }
                            )
                        }
                    )
                }
                // Favorito es un TOGGLE: la sobrecarga `checked` le da contenedor marcado, morph
                // de forma y `Role.Checkbox`. Antes el único indicio de estado era el relleno del
                // corazón, invisible para un lector de pantalla.
                DropdownMenuItem(
                    colors = appSelectableMenuItemColors(),
                    checked = isFavorite,
                    onCheckedChange = { onToggleFavorite(songId); showMenu = false },
                    text = { Text(if (isFavorite) stringResource(R.string.menu_remove_favorite) else stringResource(R.string.menu_favorite)) },
                    shapes = MenuDefaults.itemShapes(shape = MenuDefaults.trailingItemShape),
                    leadingIcon = { MenuItemIcon(if (isFavorite) "heart_minus" else "heart_plus") }
                )
            }
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
    onToggle: (SongSourceFilter) -> Unit,
    showPlayActions: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
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
        // El conteo va SIEMPRE primero: es lo que dice qué lista es ésta, y su sitio no puede
        // depender de si hay acciones o no — si se corriera, la misma fila cambiaría de anclaje
        // según una preferencia de pestañas que no tiene nada que ver con ella.
        TonalChip {
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.song_count, songCount, songCount
                ),
                color = AppColors.onSecondaryContainer
            )
        }
        // Detrás del conteo y delante del orden y los filtros: son lo único de esta fila que HACE
        // algo —el resto describe o acota, y por eso el resto SÍ son chips y esto no—, y solo
        // existen cuando la pestaña Inicio no está para ofrecerlas (la regla vive en
        // [LibraryViewModel.homeTabHidden]). Son los MISMOS `QuickActionButton` del
        // inicio, el mismo componente y no una copia con el mismo aspecto.
        if (showPlayActions) {
            QuickActionButton(
                "shuffle",
                stringResource(R.string.home_action_shuffle),
                onClick = onShuffleAll
            )
            QuickActionButton(
                "play_arrow",
                stringResource(R.string.home_action_play_order),
                fill = true,
                onClick = onPlayAll
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
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = AppColors.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun SearchArtistCard(
    artist: ArtistSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppColors.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            // Foto del artista → carátula de alguno de sus álbumes → placeholder (misma cascada
            // que la pestaña Artistas y el detalle).
            val artistArt = artist.thumbUrl ?: artist.fallbackArtUri
            if (artistArt != null) {
                AsyncImage(
                    model = artistArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MaterialSymbol("artist", color = AppColors.onSurfaceVariant)
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
private fun SearchAlbumCard(
    album: AlbumSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        AlbumArt(
            albumArtUri = album.albumArtUri,
            size = 72.dp,
            cornerRadius = 16.dp
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
                        color = AppColors.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.search_no_results),
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.onSurfaceVariant
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
                color = AppColors.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.filter_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.filter_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant,
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
                color = AppColors.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.onSurfaceVariant
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

