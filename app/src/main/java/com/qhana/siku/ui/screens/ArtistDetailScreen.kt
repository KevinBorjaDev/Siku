package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AlbumGridCard
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ArtistPickerSheet
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.viewmodel.ArtistPickerState
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Detalle de artista, estilo INMERSIVO (referencia visual del usuario): foto grande
 * edge-to-edge con nombre y chips superpuestos, botonera compacta círculo+cuadrado
 * ([DetailPlayButtons]), carrusel horizontal de álbumes con quick-play y lista de
 * canciones en tarjetas segmentadas con overflow (favoritos / añadir a lista).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    currentSong: Song?,
    playbackState: PlaybackState,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val songs by remember(artistName) { viewModel.getArtistSongs(artistName) }
        .collectAsStateWithLifecycle(emptyList())
    val albums by remember(artistName) { viewModel.getArtistAlbums(artistName) }
        .collectAsStateWithLifecycle(emptyList())
    val artistInfo by remember(artistName) { viewModel.getArtistInfo(artistName) }
        .collectAsStateWithLifecycle(null)
    val pickerState by viewModel.pickerState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    // Estado de los sheets de "añadir a lista".
    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // Canción retenida cuando el diálogo de crear lista viene de la hoja "agregar a lista".
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // Fetch perezoso de la foto al entrar (idempotente, con TTL y rate-limit en el repo).
    LaunchedEffect(artistName) { viewModel.onArtistShown(artistName) }

    // Quick-play de un álbum del carrusel: obtiene sus canciones y reproduce (navega al player).
    val playAlbum: (String) -> Unit = { albumName ->
        scope.launch {
            val albumSongs = viewModel.getAlbumSongs(albumName).first()
            if (albumSongs.isNotEmpty()) onPlayAll(albumSongs, 0)
        }
    }

    // TopBar mínima al scrollear: fondo que se funde + título con transición tipo
    // SHARED ELEMENT (réplica del morph de carátula MiniPlayer→NowPlaying): un único
    // Text viaja y ESCALA desde el nombre grande del header hasta el hueco de la topbar,
    // interpolando entre ambas posiciones medidas, conducido por el offset de scroll.
    val listState = rememberLazyListState()
    val titleFadePx = with(LocalDensity.current) { 300.dp.toPx() }
    val rawTitleFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / titleFadePx).coerceIn(0f, 1f)
        }
    }
    // SNAP al soltar: con pocas canciones el scroll disponible no alcanza el rango del
    // fade y la transición quedaba a medias. Mientras se arrastra sigue al dedo; al
    // soltar se completa al extremo más cercano — y si la lista llegó a su fondo, se
    // completa SIEMPRE hacia la topbar aunque el recorrido haya sido corto.
    val topBarAlpha by animateFloatAsState(
        targetValue = when {
            listState.isScrollInProgress -> rawTitleFraction
            !listState.canScrollForward && rawTitleFraction > 0.05f -> 1f
            rawTitleFraction >= 0.5f -> 1f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "topBarFraction"
    )
    // Anclas medidas de la transición del título (coordenadas en root).
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        // El MiniPlayer global (MainActivity) FLOTA sobre esta pantalla: se reserva su
        // alto como contentPadding para que el final de la lista scrollee por encima.
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ComponentConfig.FloatingBarListInset
        Box(modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomInset)
        ) {
            // Header inmersivo: foto edge-to-edge + scrim + back/picker flotantes +
            // nombre y chips superpuestos.
            item {
                ArtistImmersiveHeader(
                    artistName = artistName,
                    imageUrl = artistInfo?.imageUrl,
                    albumCount = albums.size,
                    songCount = songs.size,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // El nombre del header es un PLACEHOLDER invisible que solo aporta
                    // layout y su posición: el texto real lo dibuja el título viajero.
                    onTitlePositioned = { pos, height ->
                        headerTitleAnchor = pos
                        headerTitleHeight = height
                    }
                )
            }

            item {
                DetailPlayButtons(
                    onPlayAll = { if (songs.isNotEmpty()) onPlayAll(songs, 0) },
                    onShuffle = { if (songs.isNotEmpty()) onShufflePlay(songs) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }

            if (albums.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.common_albums),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums, key = { it.name }) { album ->
                            AlbumGridCard(
                                album = album,
                                showArtist = false,
                                onClick = { onAlbumClick(album.name) },
                                onPlayClick = { playAlbum(album.name) },
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                }
            }

            if (songs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.songs_header),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        TonalChip {
                            MaterialSymbol("music_note", size = 14.sp, color = colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${songs.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    Surface(
                        color = colorScheme.surfaceContainer,
                        shape = rememberListItemShape(index, songs.size),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        SongItem(
                            song = song,
                            isPlaying = currentSong?.id == song.id && playbackState == PlaybackState.PLAYING,
                            modifier = Modifier.clickable { onPlayAll(songs, index) },
                            trailingContent = {
                                SongOverflowButton(
                                    isFavorite = song.id in favorites,
                                    onToggleFavorite = { onToggleFavorite(song.id) },
                                    onAddToPlaylist = { songIdForPlaylist = song.id }
                                )
                            }
                        )
                    }
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MaterialSymbol(
                            "artist",
                            size = 64.sp,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.artist_no_songs),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // TopBar estilo stock, PINEADA: back + título + picker. Los botones están siempre
        // visibles (flotan sobre la foto en reposo); el fondo y el título se funden a la
        // vista con el scroll, cuando el nombre grande del header sale de pantalla.
        // overSharedElements: durante la transición de entrada la foto vuela en el OVERLAY
        // y tapaba esto (aparecía de golpe al terminar) — se dibuja encima.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope))
                .background(colorScheme.surface.copy(alpha = topBarAlpha))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                FilledIconButton(
                    onClick = onBackClick,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colorScheme.surfaceContainer,
                        contentColor = colorScheme.onSurface
                    )
                ) {
                    MaterialSymbol("arrow_back")
                }
                // Hueco del título: caja de alto cero centrada verticalmente en la fila —
                // su posición marca el DESTINO del título viajero (centro vertical exacto).
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                )
                FilledIconButton(
                    onClick = { viewModel.searchArtistCandidates(artistName) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colorScheme.surfaceContainer,
                        contentColor = colorScheme.onSurface
                    )
                ) {
                    MaterialSymbol("person_search")
                }
            }
        }

        // TÍTULO VIAJERO (shared element manual): un único Text que interpola posición y
        // escala entre el ancla del header y el hueco de la topbar según el scroll.
        // Layout con el mismo ancho que el nombre del header (márgenes 20dp) para que el
        // ellipsis coincida; el movimiento/escala van en graphicsLayer (fase de draw).
        Text(
            text = artistName.ifBlank { stringResource(R.string.common_unknown_artist) },
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 40.dp)
                // Encima de la foto voladora del overlay o aparecía de golpe (ver Álbum).
                .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope))
                .graphicsLayer {
                    val f = FastOutSlowInEasing.transform(topBarAlpha)
                    // titleMedium (16sp) / displaySmall (36sp)
                    val endScale = 16f / 36f
                    val scale = androidx.compose.ui.util.lerp(1f, endScale, f)
                    val startX = headerTitleAnchor.x - overlayOrigin.x
                    val startY = headerTitleAnchor.y - overlayOrigin.y
                    // El ancla del hueco es el centro vertical de la fila (caja de alto 0).
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

    if (pickerState != ArtistPickerState.Hidden) {
        ArtistPickerSheet(
            artistName = artistName,
            isLoading = pickerState is ArtistPickerState.Loading,
            candidates = (pickerState as? ArtistPickerState.Loaded)?.candidates,
            errorMessage = (pickerState as? ArtistPickerState.Error)?.message,
            onCandidateSelected = { viewModel.selectArtistCandidate(artistName, it) },
            onDismiss = { viewModel.dismissArtistPicker() }
        )
    }

    if (songIdForPlaylist != null) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                onAddSongToPlaylist(playlistId, songIdForPlaylist!!)
                songIdForPlaylist = null
            },
            onCreateNewPlaylist = {
                // Retener la canción: la lista nueva nace con ella (antes se descartaba).
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
private fun ArtistImmersiveHeader(
    artistName: String,
    imageUrl: String?,
    albumCount: Int,
    songCount: Int,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> }
) {
    // La foto llega volando desde la fila de la pestaña Artistas (sharedBounds: el
    // contenido difiere — thumb chico vs foto grande — y así cross-fadea).
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "artist_image_$artistName"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp)
            .then(sharedModifier)
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = stringResource(R.string.artist_photo_desc, artistName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("artist", size = 96.sp, color = colorScheme.onSurfaceVariant)
            }
        }

        // Scrim inferior: funde la foto con el fondo de la pantalla y da contraste al texto.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to colorScheme.surface
                    )
                )
        )

        // Nombre + chips superpuestos en la parte baja (back/picker viven en la topbar
        // pineada del caller).
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = artistName.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: aporta el layout (posición de los chips) y reporta
                // su ancla; el texto visible es el título viajero del overlay.
                modifier = Modifier
                    .graphicsLayer { alpha = 0f }
                    .onGloballyPositioned { onTitlePositioned(it.positionInRoot(), it.size.height) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalChip {
                    MaterialSymbol("album", size = 14.sp, color = colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (albumCount == 1) "1 álbum" else "$albumCount álbumes",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                TonalChip {
                    MaterialSymbol("music_note", size = 14.sp, color = colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (songCount == 1) "1 canción" else "$songCount canciones",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
