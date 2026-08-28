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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSideInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.bleedHorizontally
import com.qhana.siku.ui.components.rememberDetailHeaderMetrics
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.BrowseViewModel

import com.qhana.siku.ui.theme.appEffectsSpec

/**
 * Detalle de un género: mismo diseño inmersivo que el detalle de álbum/artista (header
 * edge-to-edge con scrim, botonera compacta, canciones en tarjetas segmentadas y título que
 * viaja a la topbar con el scroll). La imagen del header es el COLLAGE de portadas del género,
 * que llega volando desde su tarjeta en la pestaña.
 *
 * Las canciones salen de [BrowseViewModel.getGenreSongs], que sigue en vivo el ajuste "incluir
 * géneros compuestos": activarlo desde la pestaña reordena esta lista sin salir de la pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun GenreDetailScreen(
    genreName: String,
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
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

    // Hasta ARTS_PER_GENRE portadas distintas para el collage del header, derivadas de las
    // canciones que ya están en pantalla (no hace falta volver a la BD).
    val arts = remember(songs) {
        songs.mapNotNull { it.albumArtUriString }.distinct().take(HEADER_ARTS)
    }
    val artistCount = remember(songs) { songs.map { it.artist }.distinct().size }

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // Título viajero: mismas anclas y mismo criterio de snap que el detalle de álbum.
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
        // Fondo `surfaceContainer` (94), el nivel MEDIO de la escala: el contenido de la pantalla
        // —filas y tarjetas— sube desde aquí a `surface` (98) y las barras bajan a
        // `surfaceContainerHigh` (92). Mismo reparto que la biblioteca; el porqué, en `headerColor`
        // de LibraryScreen. Si se cambia, hay que mover CON él el degradado del header inmersivo,
        // que funde la imagen contra este color y dejaría costura.
        containerColor = AppColors.surfaceContainer
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            ) {
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
                            onAddToQueue = { onAddAllToQueue(songs) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }

                    itemsIndexed(
                        items = songs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y
                        // la lista de canciones (el resaltado marca "cargada", no "sonando ahora").
                        val isPlaying = currentSong?.id == song.id
                        val rowBackground = songRowBackground(AppColors.surface, isPlaying)
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
                                color = AppColors.surface,
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
                                containerColor = AppColors.surface,
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

        // Scrim inferior: funde el collage con el fondo y da contraste al texto.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to AppColors.surfaceContainer
                    )
                )
        )

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
                TonalChip {
                    MaterialSymbol("music_note", size = 14.sp, color = AppColors.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                        color = AppColors.onSecondaryContainer
                    )
                }
                TonalChip {
                    MaterialSymbol("artist", size = 14.sp, color = AppColors.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.artist_count, artistCount, artistCount),
                        color = AppColors.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** Portadas del collage del header (las mismas que la tarjeta de la pestaña). */
private const val HEADER_ARTS = 4


