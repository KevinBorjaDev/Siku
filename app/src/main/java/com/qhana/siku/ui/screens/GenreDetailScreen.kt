package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.detailHeaderScrim
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.AdaptiveGridCells
import com.qhana.siku.ui.components.AlbumTileCard
import com.qhana.siku.ui.components.ChoiceGroupWidth
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.safeSideInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.bleedHorizontally
import com.qhana.siku.ui.components.rememberDetailHeaderMetrics
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.DetailPlayOrder
import com.qhana.siku.ui.components.sort
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.components.CountChip
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import com.qhana.siku.ui.theme.appEffectsSpec

/**
 * Detalle de un género: mismo diseño inmersivo que el detalle de álbum/artista (header
 * edge-to-edge con scrim, botonera compacta, título que viaja a la topbar con el scroll), con un
 * toggle Canciones/Álbumes ([GenreDetailView]) debajo de la botonera.
 *
 * **Un solo `LazyColumn`, siempre** —nunca dos contenedores que se intercambian—: cabecera,
 * botonera y toggle son items FIJOS que no dependen de [GenreDetailView] y por tanto nunca se
 * recomponen al alternar, así que el colapso y el título viajero (que dependen de leer el scroll
 * de ESTA misma lista) siguen intactos. Los álbumes NO van en un `LazyVerticalGrid` propio —eso
 * exigiría un contenedor distinto y, para animar el cambio, sacar la cabecera de la lista (se
 * probó dos veces, con `AnimatedContent`, y las dos se descartó por eso)— sino en filas armadas
 * a mano (`AlbumTileCard` × [albumColumns] dentro de un `Row`), items más de la MISMA lista.
 *
 * Alternar la vista es entonces un cambio de qué items siguen a la botonera, y Compose lo anima
 * SOLO con `Modifier.animateItem()` (el mismo que ya usan las filas de canción): lo que sale hace
 * fade-out, lo que entra fade-in, y el resto se reacomoda — nativo, sin `AnimatedContent`, sin
 * tocar la cabecera.
 *
 * Las canciones salen de [BrowseViewModel.getGenreSongs] y los álbumes de
 * [BrowseViewModel.getGenreAlbums], las dos en vivo con el ajuste "incluir géneros compuestos":
 * activarlo desde la pestaña reordena la vista activa sin salir de la pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun GenreDetailScreen(
    genreName: String,
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones del género, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val songs by remember(genreName) { viewModel.getGenreSongs(genreName) }
        .collectAsStateWithLifecycle(emptyList())
    val albums by remember(genreName) { viewModel.getGenreAlbums(genreName) }
        .collectAsStateWithLifecycle(emptyList())

    // Hasta ARTS_PER_GENRE portadas distintas para el collage del header, derivadas de las
    // canciones que ya están en pantalla (no hace falta volver a la BD).
    val arts = remember(songs) {
        songs.mapNotNull { it.albumArtUriString }.distinct().take(HEADER_ARTS)
    }
    val artistCount = remember(songs) { songs.map { it.artist }.distinct().size }

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // Alternar Canciones/Álbumes: estado local, no persistido — cada entrada al género arranca
    // en Canciones, como siempre. Ver [GenreDetailView].
    var viewMode by remember { mutableStateOf(GenreDetailView.SONGS) }

    // Quick-play de un álbum de una fila: pide sus canciones y reproduce (misma resolución
    // perezosa que ArtistDetailScreen — la tarjeta solo conoce el NOMBRE del álbum).
    val scope = rememberCoroutineScope()
    val playAlbum: (String) -> Unit = { albumName ->
        scope.launch {
            val albumSongs = viewModel.getAlbumSongs(albumName).first()
            if (albumSongs.isNotEmpty()) onPlayAll(albumSongs, 0)
        }
    }
    val queueAlbum: (String) -> Unit = { albumName ->
        scope.launch { onAddAllToQueue(viewModel.getAlbumSongs(albumName).first()) }
    }

    // Título viajero: mismas anclas y mismo criterio de snap que el detalle de álbum. UN solo
    // `LazyListState` para las dos vistas (ver el kdoc de la función).
    val listState = rememberLazyListState()
    // El recorrido del fade sale del alto REAL del header en esta ventana, y no de la constante:
    // girado el header se acota, y con el recorrido de vertical el titulo no llegaria nunca a la
    // barra. Ver `detailHeaderMetrics`.
    val headerMetrics = rememberDetailHeaderMetrics()
    val titleFadePx = with(LocalDensity.current) { headerMetrics.titleFadeRange.toPx() }
    val rawTitleFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / titleFadePx).coerceIn(0f, 1f)
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = when {
            listState.isScrollInProgress -> rawTitleFraction
            !listState.canScrollForward && rawTitleFraction > 0.05f -> 1f
            rawTitleFraction >= 0.5f -> 1f
            else -> 0f
        },
        // Ver el kdoc de este mismo bloque en ArtistDetailScreen: effects (sin rebote) porque la
        // fracción conduce a la vez la posición del título y un alpha.
        animationSpec = appEffectsSpec(),
        label = "topBarFraction"
    )
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    // Estado compartido del collage del header, HOISTADO: la cabecera lo usa para su `sharedBounds`
    // y el título/topbar para saber si elevarse al overlay (solo si morfa de verdad, o sea si
    // venimos de la pestaña Géneros — ver `overSharedElementsModifier`).
    val headerImageSharedState = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) { rememberSharedContentState(key = "genre_image_$genreName") }
    } else null

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Fondo de página: `surface` (M3 spec — 4 sep 2026). Las filas se separan de él porque son
        // TARJETAS (`AppColors.contentSurface` = `surfaceContainerHigh`, el token de card y no el
        // de list item — leer su KDoc antes de tocar esto).
        containerColor = AppColors.surface
    ) { paddingValues ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MaterialSymbol(
                        "genres",
                        size = 64.sp,
                        color = AppColors.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.genre_empty_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }
        } else {
            // EL COSTADO: en vertical no existe y en horizontal es donde cae todo — la perforación
            // de pantalla y, con navegación de tres botones, la barra del sistema. Aquí NO se
            // puede apartar la pantalla entera: el header inmersivo es un `item` de esta misma
            // lista y un padding lateral le quitaría el sangrado justo a la pieza que tiene que
            // sangrar. Así que el inset va en el `contentPadding` —o sea a todas las filas— y el
            // header se sale de él con `bleedHorizontally`, el mismo recurso con el que la
            // portada del reproductor se salta su keyline.
            val layoutDirection = LocalLayoutDirection.current
            val sideInsets = safeSideInsets()
            val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                ComponentConfig.FloatingBarListInset
            // `BoxWithConstraints` en vez de `Box`: los álbumes se arman en filas A MANO (no un
            // `LazyVerticalGrid`, ver el kdoc de la función) y para eso hace falta saber cuántas
            // entran — el ancho disponible real, no `screenWidthDp`, que en multi-ventana no es
            // la ventana de la app.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            ) {
                // MISMA cuenta que `AlbumsScreen`/`AdaptiveGridCells` (adaptativa, con el SUELO de
                // dos columnas) — se reusa la clase para no duplicar la fórmula ni su umbral.
                val density = LocalDensity.current
                val albumRowMargin = 16.dp
                val albumSpacing = 12.dp
                val albumColumns = remember(maxWidth, sideInsets, layoutDirection, density) {
                    val available = maxWidth -
                        sideInsets.calculateStartPadding(layoutDirection) -
                        sideInsets.calculateEndPadding(layoutDirection) -
                        albumRowMargin * 2
                    // `calculateCrossAxisCellSizes` es un miembro de `AdaptiveGridCells` CON
                    // receptor de extensión `Density`: llamarlo por punto sobre la instancia
                    // (`cells.calculateCrossAxisCellSizes(...)`) no resuelve ese receptor
                    // implícito. Hay que anidar los `with` y llamarla SIN punto — el mismo patrón
                    // que usa Compose puertas adentro para `GridCells.Fixed`/`Adaptive`.
                    with(density) {
                        with(AdaptiveGridCells(ComponentConfig.GridTileMinWidth)) {
                            calculateCrossAxisCellSizes(available.roundToPx(), albumSpacing.roundToPx())
                        }
                    }.size
                }
                val albumRows = remember(albums, albumColumns) { albums.chunked(albumColumns) }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = sideInsets.calculateStartPadding(layoutDirection),
                        end = sideInsets.calculateEndPadding(layoutDirection),
                        bottom = listBottomInset
                    )
                ) {
                    item {
                        GenreImmersiveHeader(
                            genreName = genreName,
                            arts = arts,
                            songCount = songs.size,
                            artistCount = artistCount,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            headerImageSharedState = headerImageSharedState,
                            onTitlePositioned = { pos, height ->
                                headerTitleAnchor = pos
                                headerTitleHeight = height
                            },
                            // A SANGRE: el header es la única pieza de esta lista que llega al borde, así
                            // que se sale del inset lateral que el `contentPadding` acaba de poner a todas
                            // las filas. Ver [bleedHorizontally].
                            modifier = Modifier.bleedHorizontally(
                                start = sideInsets.calculateStartPadding(layoutDirection),
                                end = sideInsets.calculateEndPadding(layoutDirection)
                            )
                        )
                    }

                    item {
                        DetailPlayButtons(
                            onPlayAll = { onPlayAll(songs, 0) },
                            onShuffle = { onShufflePlay(songs) },
                            // Mismas opciones que el detalle de lista: invertir/A-Z/Z-A. Ver el
                            // kdoc de DetailPlayOrder para el porqué (aquí el orden de partida
                            // ya es alfabético, pero invertirlo o rehacerlo sigue siendo útil).
                            onPlayInOrder = { order -> onPlayAll(order.sort(songs), 0) },
                            onAddToQueue = { onAddAllToQueue(songs) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }

                    // A partir de acá es lo ÚNICO que cambia con [viewMode] — la cabecera y la
                    // botonera de arriba son items fijos que NUNCA se recomponen al alternar
                    // (el toggle vive en la topbar, ver más abajo — no hay uno grande acá).
                    // `animateItem()` (ya en cada fila/tarjeta) anima la transición: lo que sale
                    // hace fade-out, lo que entra fade-in.
                    when (viewMode) {
                        GenreDetailView.SONGS -> itemsIndexed(
                            items = songs,
                            key = { _, song -> song.id }
                        ) { index, song ->
                            // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y
                            // la lista de canciones (el resaltado marca "cargada", no "sonando ahora").
                            val isPlaying = currentSong?.id == song.id
                            val rowBackground = songRowBackground(AppColors.contentSurface, isPlaying)
                            // Punta ORIGEN del container transform hacia el reproductor: la fila crece
                            // hasta ser el player. Fuera del envoltorio va lo que la coloca en la lista;
                            // dentro, la superficie que morfa (ver [SongRowContainer]).
                            SongRowContainer(
                                songId = song.id,
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            ) {
                                AppSurface(
                                    color = AppColors.contentSurface,
                                    // isActive: el ítem en reproducción usa la forma redondeada (16 dp),
                                    // igual que en la cola y la lista de canciones.
                                    shape = rememberListItemShape(index, songs.size, isActive = isPlaying),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SongItem(
                                        song = song,
                                        isPlaying = isPlaying,
                                        modifier = Modifier.clickable { onPlayAll(songs, index) },
                                        trailingContent = {
                                            SongOverflowButton(
                                                isFavorite = song.id in favorites,
                                                onToggleFavorite = { onToggleFavorite(song.id) },
                                                onAddToPlaylist = { songIdForPlaylist = song.id },
                                                onAddToQueue = { onAddToQueue(song) },
                                                rowBackground = rowBackground
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        GenreDetailView.ALBUMS -> itemsIndexed(
                            items = albumRows,
                            key = { index, _ -> "album_row_$index" }
                        ) { _, rowAlbums ->
                            // MISMAS tarjetas que la pestaña Álbumes y el carrusel de Artista: carátula
                            // con badge de nº de canciones, nombre/artista y su propio play. Tocarla
                            // lleva al álbum COMPLETO (sin filtrar por género — un álbum con pistas de
                            // dos géneros no se parte). El último renglón, si queda incompleto, deja
                            // los huecos vacíos (Spacer) en vez de estirar las tarjetas que sí hay —
                            // igual que una rejilla de verdad.
                            //
                            // SIN scopes de shared element (igual que el carrusel de Artista, y a
                            // diferencia de la pestaña Álbumes): el mismo álbum puede estar compuesto
                            // a la vez en la pestaña Álbumes o en el carrusel de su artista con la
                            // MISMA key ("album_image_<nombre>"), y dos puntas con la misma key no
                            // emparejan con ninguna (ver Motion.kt, regla 2).
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(albumSpacing),
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .padding(horizontal = albumRowMargin, vertical = albumSpacing / 2)
                            ) {
                                rowAlbums.forEach { album ->
                                    AlbumTileCard(
                                        album = album,
                                        onClick = { onAlbumClick(album.name) },
                                        onPlayClick = { playAlbum(album.name) },
                                        onAddToQueue = { queueAlbum(album.name) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(albumColumns - rowAlbums.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope, headerImageSharedState))
                        .background(AppColors.surfaceContainerHigh.copy(alpha = topBarAlpha))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    // El fondo de la barra lo pinta el Box de fuera y llega al borde;
                    // lo que se aparta de la perforación es su CONTENIDO.
                    .safeSidePadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        FilledIconButton(
                            onClick = onBackClick,
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = AppColors.contentSurface,
                                contentColor = AppColors.onSurface
                            )
                        ) {
                            MaterialSymbol("arrow_back")
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                        )
                        // Toggle compacto: SOLO iconos, sin etiqueta — como el selector
                        // lista/rejilla de Drive. Es el ÚNICO selector de la pantalla (el grande
                        // con texto se sacó del cuerpo): vive acá, siempre visible junto al back,
                        // así que alternar Canciones/Álbumes nunca exige volver a subir.
                        GenreViewModeToggle(
                            viewMode = viewMode,
                            onViewModeChange = { viewMode = it },
                            iconOnly = true
                        )
                    }
                }

                Text(
                    text = genreName,
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 40.dp)
                        .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope, headerImageSharedState))
                        .graphicsLayer {
                            val f = FastOutSlowInEasing.transform(topBarAlpha)
                            // titleMedium (16sp) / headlineLarge (32sp)
                            val endScale = 16f / 32f
                            val scale = androidx.compose.ui.util.lerp(1f, endScale, f)
                            val startX = headerTitleAnchor.x - overlayOrigin.x
                            val startY = headerTitleAnchor.y - overlayOrigin.y
                            val endX = barTitleAnchor.x - overlayOrigin.x
                            val endY = (barTitleAnchor.y - overlayOrigin.y) - (headerTitleHeight * endScale) / 2f
                            translationX = androidx.compose.ui.util.lerp(startX, endX, f)
                            translationY = androidx.compose.ui.util.lerp(startY, endY, f)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }
        }
    }

    if (songIdForPlaylist != null) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                onAddSongToPlaylist(playlistId, songIdForPlaylist!!)
                songIdForPlaylist = null
            },
            onCreateNewPlaylist = {
                pendingSongForNewPlaylist = songIdForPlaylist
                songIdForPlaylist = null
                showCreatePlaylistDialog = true
            },
            onDismiss = { songIdForPlaylist = null }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreatePlaylistDialog = false
                pendingSongForNewPlaylist = null
            },
            onConfirm = { name ->
                onCreatePlaylist(name, pendingSongForNewPlaylist)
                pendingSongForNewPlaylist = null
                showCreatePlaylistDialog = false
            }
        )
    }
}

/** Las dos vistas del detalle de género — ver el toggle en [GenreViewModeToggle]. */
private enum class GenreDetailView { SONGS, ALBUMS }

/**
 * Selector Canciones/Álbumes del detalle de género (`ConnectedChoiceGroup`, el reemplazo de
 * `SegmentedButton` en toda la app). Único selector de la pantalla: vive en la topbar
 * ([GenreDetailScreen]), `iconOnly` —como el switch lista/rejilla de Drive—, siempre visible
 * junto al back sin importar el scroll.
 */
@Composable
private fun GenreViewModeToggle(
    viewMode: GenreDetailView,
    onViewModeChange: (GenreDetailView) -> Unit,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false
) {
    ConnectedChoiceGroup(
        options = listOf(GenreDetailView.SONGS, GenreDetailView.ALBUMS),
        selected = viewMode,
        onSelect = onViewModeChange,
        labelFor = { mode ->
            when (mode) {
                GenreDetailView.SONGS -> stringResource(R.string.songs_header)
                GenreDetailView.ALBUMS -> stringResource(R.string.common_albums)
            }
        },
        iconFor = { mode ->
            when (mode) {
                GenreDetailView.SONGS -> "music_note"
                GenreDetailView.ALBUMS -> "album"
            }
        },
        // Content, no ancho completo: ceñido a su contenido, como el resto de botones de la
        // topbar (mismo criterio que el doc de ConnectedChoiceGroup para no leerse como una
        // barra que "debería" llegar a los bordes).
        width = ChoiceGroupWidth.Content,
        iconOnly = iconOnly,
        modifier = modifier
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun GenreImmersiveHeader(
    genreName: String,
    arts: List<String>,
    songCount: Int,
    artistCount: Int,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // HOISTADO desde el parent (mismo objeto que gatea la elevación del título).
    headerImageSharedState: SharedTransitionScope.SharedContentState? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // El collage llega volando desde el tile de la pestaña Géneros.
    // El header es un rectángulo a sangre y así viaja: declararlo explícito ACOTA la punta a sus
    // bounds animados (sin clip, el contenido escalado se sale del rect) y deja escrito que la forma
    // de este extremo del morph es "ninguna". La celda de la que viene declara la suya, y el cruce de
    // contenidos hace el paso de una a otra. Ver [entityImageSharedBounds].
    val sharedModifier = entityImageSharedBounds(
        state = headerImageSharedState,
        shape = RectangleShape,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rememberDetailHeaderMetrics().headerHeight)
            .then(sharedModifier)
    ) {
        if (arts.isEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AppColors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("genres", size = 96.sp, color = AppColors.onSurfaceVariant)
            }
        } else {
            AdaptiveCollage(arts = arts, modifier = Modifier.matchParentSize())
        }

        // Scrim inferior: el MISMO de los cuatro detalles, y por eso vive en [detailHeaderScrim].
        // Aquí había una copia con el degradado de DOS paradas, o sea la versión anterior al
        // refuerzo que artista y álbum sí recibieron: sobre un collage de portadas claras el nombre
        // del género quedaba con la parte alta de las letras tapada.
        Box(modifier = Modifier.matchParentSize().background(detailHeaderScrim()))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                // El header sangra hasta el borde, pero su TEXTO no: mismo reparto que en la
                // topbar overlay — fondo hasta el borde, contenido apartado.
                .safeSidePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = genreName,
                style = MaterialTheme.typography.headlineLargeEmphasized,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: aporta el layout y reporta su ancla; el texto visible
                // es el título viajero del overlay.
                modifier = Modifier
                    .graphicsLayer { alpha = 0f }
                    .onGloballyPositioned { onTitlePositioned(it.positionInRoot(), it.size.height) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip {
                    MaterialSymbol("music_note", size = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                    )
                }
                CountChip {
                    MaterialSymbol("artist", size = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.artist_count, artistCount, artistCount),
                    )
                }
            }
        }
    }
}

/** Portadas del collage del header (las mismas que la tarjeta de la pestaña). */
private const val HEADER_ARTS = 4
