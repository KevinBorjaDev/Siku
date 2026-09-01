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
import androidx.compose.runtime.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.detailHeaderScrim
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AlbumTileCard
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSideInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.bleedHorizontally
import com.qhana.siku.ui.components.rememberDetailHeaderMetrics
import com.qhana.siku.ui.components.ArtistPickerSheet
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
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
import com.qhana.siku.ui.viewmodel.ArtistPickerState
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.DetailContentTheme

/**
 * Ancho de cada álbum del carrusel. Algo más que los 140dp que llevaba con la tarjeta vieja:
 * ahora el nombre y el play conviven en una fila DENTRO de la tarjeta, y con el ancho anterior
 * al título le quedaban ~80dp útiles (todo elipsis). Sigue dejando ver el arranque del
 * siguiente álbum, que es lo que invita a deslizar el carrusel.
 */
private val ArtistAlbumCardWidth = 168.dp

/**
 * Detalle de artista, estilo INMERSIVO (referencia visual del usuario): foto grande
 * edge-to-edge con nombre y chips superpuestos, botonera compacta círculo+cuadrado
 * ([DetailPlayButtons]), carrusel horizontal de álbumes con quick-play y lista de
 * canciones en tarjetas segmentadas con overflow (favoritos / añadir a lista).
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones del artista, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
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

    // Imagen del header, calculada UNA vez y usada para las dos cosas: pintarla y seedear el tema
    // local de la pantalla. Es la cascada de siempre —foto de Deezer → carátula de alguno de sus
    // álbumes— y el color tiene que salir de la que gane, no de la foto a secas: con "ninguno de
    // estos" en el picker el header enseña una carátula, y un tema que la ignorase pintaría un
    // acento sin relación con lo que se ve.
    val headerImageUrl = artistInfo?.imageUrl ?: albums.firstNotNullOfOrNull { it.albumArtUri }
    // La foto puede LLEGAR TARDE (la baja el backfill de Deezer con la pantalla ya abierta), de ahí
    // que el seed sea un flow y no un valor calculado una vez.
    LaunchedEffect(headerImageUrl) { viewModel.requestArtistSeed(headerImageUrl) }
    val artistSeed by viewModel.artistSeed.collectAsStateWithLifecycle()

    // Quick-play de un álbum del carrusel: obtiene sus canciones y reproduce (navega al player).
    val playAlbum: (String) -> Unit = { albumName ->
        scope.launch {
            val albumSongs = viewModel.getAlbumSongs(albumName).first()
            if (albumSongs.isNotEmpty()) onPlayAll(albumSongs, 0)
        }
    }

    // Encolar un álbum del carrusel. Misma resolución perezosa que [playAlbum]: la tarjeta solo
    // conoce el NOMBRE del álbum, las canciones se piden al pulsar.
    val queueAlbum: (String) -> Unit = { albumName ->
        scope.launch { onAddAllToQueue(viewModel.getAlbumSongs(albumName).first()) }
    }

    // TopBar mínima al scrollear: fondo que se funde + título con transición tipo
    // SHARED ELEMENT (réplica del morph de carátula MiniPlayer→NowPlaying): un único
    // Text viaja y ESCALA desde el nombre grande del header hasta el hueco de la topbar,
    // interpolando entre ambas posiciones medidas, conducido por el offset de scroll.
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
        // `defaultEffects` y no un token spatial, aunque esta fracción también MUEVA el título: es
        // el único juego de tokens sin rebote, y aquí el rebote sería una oscilación del título cada
        // vez que se suelta el scroll. Además alimenta un alpha, que no puede pasar de 1.
        // (Físicamente equivale al `spring(StiffnessMedium)` que había a mano: 1500 → 1600.)
        animationSpec = appEffectsSpec(),
        label = "topBarFraction"
    )
    // Anclas medidas de la transición del título (coordenadas en root).
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    // Estado compartido de la FOTO del header, HOISTADO: la cabecera lo usa para su `sharedBounds`
    // y el título/topbar para saber si elevarse al overlay (solo si la foto morfa de verdad, o sea
    // si venimos de la pestaña Artistas — ver `overSharedElementsModifier`).
    val headerImageSharedState = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) { rememberSharedContentState(key = "artist_image_$artistName") }
    } else null

    DetailContentTheme(artistSeed) {
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
        // El MiniPlayer global (MainActivity) FLOTA sobre esta pantalla: se reserva su
        // alto como contentPadding para que el final de la lista scrollee por encima.
        // EL COSTADO: en vertical no existe y en horizontal es donde cae todo — la perforación
        // de pantalla y, con navegación de tres botones, la barra del sistema. Aquí NO se
        // puede apartar la pantalla entera: el header inmersivo es un `item` de esta misma
        // lista y un padding lateral le quitaría el sangrado justo a la pieza que tiene que
        // sangrar. Así que el inset va en el `contentPadding` —o sea a todas las filas— y el
        // header se sale de él con `bleedHorizontally`, el mismo recurso con el que la
        // portada del reproductor se salta su keyline.
        val layoutDirection = LocalLayoutDirection.current
        val sideInsets = safeSideInsets()
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ComponentConfig.FloatingBarListInset
        Box(modifier = Modifier
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
            // Header inmersivo: foto edge-to-edge + scrim + back/picker flotantes +
            // nombre y chips superpuestos.
            item {
                ArtistImmersiveHeader(
                    artistName = artistName,
                    // Cascada: foto del artista → carátula de su primer álbum → placeholder. El
                    // paso intermedio existe porque el usuario puede DECIDIR que no tenga foto
                    // ("ninguno de estos" en el picker) cuando Deezer solo ofrece homónimos, y
                    // un header con el icono genérico se lee como un fallo de carga.
                    imageUrl = headerImageUrl,
                    albumCount = albums.size,
                    songCount = songs.size,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    headerImageSharedState = headerImageSharedState,
                    // El nombre del header es un PLACEHOLDER invisible que solo aporta
                    // layout y su posición: el texto real lo dibuja el título viajero.
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
                    onPlayAll = { if (songs.isNotEmpty()) onPlayAll(songs, 0) },
                    onShuffle = { if (songs.isNotEmpty()) onShufflePlay(songs) },
                    onAddToQueue = { onAddAllToQueue(songs) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }

            if (albums.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.common_albums),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums, key = { it.name }) { album ->
                            // MISMA tarjeta que la pestaña Álbumes. El artista se omite: acá
                            // todos son del mismo y repetirlo bajo cada carátula es ruido.
                            AlbumTileCard(
                                album = album,
                                showArtist = false,
                                onClick = { onAlbumClick(album.name) },
                                onPlayClick = { playAlbum(album.name) },
                                onAddToQueue = { queueAlbum(album.name) },
                                modifier = Modifier
                                    .animateItem()
                                    .width(ArtistAlbumCardWidth)
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
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                        CountChip {
                            MaterialSymbol("music_note", size = 14.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${songs.size}",
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y la
                    // lista de canciones (el resaltado marca "cargada", no "reproduciendo ahora").
                    val isPlaying = currentSong?.id == song.id
                    val rowBackground = songRowBackground(AppColors.contentSurface, isPlaying)
                    // Punta ORIGEN del container transform hacia el reproductor: la fila crece hasta
                    // ser el player. Fuera del envoltorio va lo que la coloca en la lista; dentro, la
                    // superficie que morfa (ver [SongRowContainer]).
                    SongRowContainer(
                        songId = song.id,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        AppSurface(
                            color = AppColors.contentSurface,
                            // isActive: el ítem en reproducción usa la forma redondeada (16 dp), igual
                            // que en la cola y la lista de canciones, en vez de la esquina agrupada.
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
                            color = AppColors.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.artist_no_songs),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.onSurfaceVariant
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
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = AppColors.contentSurface,
                        contentColor = AppColors.onSurface
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
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = AppColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 40.dp)
                // Encima de la foto voladora del overlay o aparecía de golpe (ver Álbum).
                .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope, headerImageSharedState))
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
            onNoneSelected = { viewModel.clearArtistImage(artistName) },
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
    } // DetailContentTheme
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
    // HOISTADO desde el parent (mismo objeto que gatea la elevación del título).
    headerImageSharedState: SharedTransitionScope.SharedContentState? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // La foto llega volando desde la fila de la pestaña Artistas o de "Porque escuchaste a X"
    // (sharedBounds: el contenido difiere — thumb chico vs foto grande — y así cross-fadea).
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
        if (imageUrl != null) {
            val context = LocalContext.current
            val request = remember(imageUrl, artistName) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    // Placeholder = la MISMA foto que ya cargó la fila de la pestaña Artistas (misma
                    // key de memoria, ver [artistPhotoCacheKey]). Al abrir, el header enseña al
                    // instante ese thumb ya decodificado y solo cambia a la versión grande cuando
                    // termina de bajar — se acaba el parpadeo de "vuelve a hacer fetch". Son dos
                    // resoluciones de Deezer (fila = pictureMedium, header = pictureXl), así que el
                    // binario grande SÍ se baja, pero por DETRÁS del placeholder. El thumb lleva
                    // forma cookie; al recortarse (Crop) a un header ancho solo se ve su centro opaco.
                    .placeholderMemoryCacheKey(artistPhotoCacheKey(artistName))
                    .crossfade(200)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.artist_photo_desc, artistName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AppColors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("artist", size = 96.sp, color = AppColors.onSurfaceVariant)
            }
        }

        // Scrim inferior: el MISMO de los cuatro detalles, y por eso vive en [detailHeaderScrim].
        Box(modifier = Modifier.matchParentSize().background(detailHeaderScrim()))

        // Nombre + chips superpuestos en la parte baja (back/picker viven en la topbar
        // pineada del caller).
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                // El header sangra hasta el borde, pero su TEXTO no: mismo reparto que en la
                // topbar overlay — fondo hasta el borde, contenido apartado.
                .safeSidePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = artistName.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = AppColors.onSurface,
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
                CountChip {
                    MaterialSymbol("album", size = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (albumCount == 1) "1 álbum" else "$albumCount álbumes",
                    )
                }
                CountChip {
                    MaterialSymbol("music_note", size = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (songCount == 1) "1 canción" else "$songCount canciones",
                    )
                }
            }
        }
    }
}
