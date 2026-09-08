package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.detailHeaderScrim
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSideInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.bleedHorizontally
import com.qhana.siku.ui.components.rememberDetailHeaderMetrics
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.CountChip
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.BrowseViewModel

import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.DetailContentTheme

/**
 * Detalle de álbum, estilo INMERSIVO (misma familia visual que el detalle de artista): carátula
 * grande edge-to-edge con nombre/artista superpuestos, botonera compacta [DetailPlayButtons] que
 * scrollea CON el contenido, y canciones en tarjetas segmentadas con overflow. La topbar es mínima
 * y solo lleva el nombre, que aparece con el scroll.
 *
 * **La imagen NO se desvanece y la botonera NO se queda pegada arriba**: las dos cosas se probaron
 * y el usuario las descartó (ver la sección de detalles en CLAUDE.md, "NO reintroducir"). Este KDoc
 * las describía como si siguieran ahí, con un enlace a un composable que ya no existe.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun AlbumDetailScreen(
    albumName: String,
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onArtistClick: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones del álbum, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val songs by remember(albumName) { viewModel.getAlbumSongs(albumName) }
        .collectAsStateWithLifecycle(emptyList())

    // La pista de la que sale la CARÁTULA del header. Se calcula una vez y se usa para las dos
    // cosas —la imagen y el seed del tema— justamente para que no puedan discrepar: el color de
    // esta pantalla tiene que ser el de la portada que se está viendo, no el de otra pista del
    // álbum que quizá tenga una portada distinta.
    val headerSong = remember(songs) { songs.firstOrNull { it.albumArtUriString != null } }

    // Seed del tema local. Sale de los colores YA persistidos en `songs` (extraídos en su día por
    // ArtworkRepository), así que no cuesta ni una consulta ni una extracción: viajan en el propio
    // modelo. `secondary` en oscuro y `primary` en claro es el mismo reparto por tema que usa el
    // resaltado de la biblioteca; son seeds CRUDOS, que es lo que MaterialKolor espera.
    val isDark = isSystemInDarkTheme()
    val albumSeed = remember(headerSong, isDark) {
        headerSong?.colors?.let { if (isDark) it.secondary else it.primary }
    }

    // Artista del header: si el álbum mezcla artists (feats), "Varios artistas" (no
    // clickeable); si es uno solo, clickeable → detalle del artista.
    val distinctArtists = remember(songs) { songs.map { it.artist }.distinct() }
    val singleArtist = distinctArtists.singleOrNull()

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // Canción retenida cuando el diálogo de crear lista viene de la hoja "agregar a lista".
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

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
        // Ver el kdoc de este mismo bloque en ArtistDetailScreen: effects (sin rebote) porque la
        // fracción conduce a la vez la posición del título y un alpha.
        animationSpec = appEffectsSpec(),
        label = "topBarFraction"
    )
    // Anclas medidas de la transición del título (coordenadas en root).
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    // Estado compartido de la CARÁTULA del header, HOISTADO: lo usa la cabecera para su `sharedBounds`
    // y el título/topbar para saber si deben elevarse al overlay (solo si la portada morfa de verdad,
    // o sea si venimos de un tile — ver `overSharedElementsModifier`). Uno solo, dos lectores: crear
    // un segundo con la misma key sería un target duplicado.
    val headerImageSharedState = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) { rememberSharedContentState(key = "album_image_$albumName") }
    } else null

    DetailContentTheme(albumSeed) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Fondo de página: `surface` (M3 spec — 4 sep 2026). El degradado del header inmersivo funde
        // la imagen contra este color vía `detailHeaderScrim`, que se cambió a la par. Las filas se
        // separan de él porque son TARJETAS (`AppColors.contentSurface` = `surfaceContainerHigh`,
        // el token de card y no el de list item — leer su KDoc antes de tocar esto).
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
                        "album",
                        size = 64.sp,
                        color = AppColors.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.album_empty_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }
        } else {
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
                item {
                    AlbumImmersiveHeader(
                        albumName = albumName,
                        albumArtUri = headerSong?.albumArtUriString,
                        singleArtist = singleArtist,
                        songCount = songs.size,
                        // La primera pista que lo declare: dentro de un álbum el año es el mismo
                        // para todas, y basta con que UNA esté etiquetada.
                        year = songs.firstOrNull { it.year > 0 }?.year ?: 0,
                        onArtistClick = onArtistClick,
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
            }

            // TopBar estilo stock, PINEADA: back + título. El back está siempre visible
            // (flota sobre la carátula en reposo); el fondo y el título se funden a la
            // vista con el scroll, cuando el nombre grande del header sale de pantalla.
            // overSharedElements: durante la transición de entrada la carátula vuela en el
            // OVERLAY y tapaba esto (aparecía de golpe al terminar) — se dibuja encima.
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
                    // Hueco del título: caja de alto cero centrada verticalmente en la
                    // fila — su posición marca el DESTINO del título viajero.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                    )
                }
            }

            // TÍTULO VIAJERO (shared element manual): un único Text que interpola posición
            // y escala entre el ancla del header y el hueco de la topbar según el scroll.
            // overSharedElements: si no, la carátula voladora del overlay lo tapa durante
            // la transición de entrada y el título aparecía DE GOLPE al terminar.
            Text(
                text = albumName.ifBlank { stringResource(R.string.common_unknown_album) },
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
private fun AlbumImmersiveHeader(
    albumName: String,
    albumArtUri: String?,
    singleArtist: String?,
    songCount: Int,
    /** Año de publicación; 0 = ninguna pista del álbum lo declara y el chip no se dibuja. */
    year: Int,
    onArtistClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // HOISTADO desde el parent (mismo objeto que gatea la elevación del título): así el
    // `isMatchFound` que decide el morph y el que decide elevar son EL MISMO.
    headerImageSharedState: SharedTransitionScope.SharedContentState? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // La carátula llega volando desde la celda de la pestaña Álbumes o del carrusel del inicio
    // (misma key en las dos: ver AlbumTileCard y AlbumCarousel).
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
        if (albumArtUri != null) {
            AsyncImage(
                model = albumArtUri,
                contentDescription = stringResource(R.string.album_art_desc, albumName),
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
                MaterialSymbol("album", size = 96.sp, color = AppColors.onSurfaceVariant)
            }
        }

        // Scrim inferior: el MISMO de los cuatro detalles, y por eso vive en [detailHeaderScrim] y
        // no aquí — estaba copiado cuatro veces y el refuerzo solo había llegado a dos.
        Box(modifier = Modifier.matchParentSize().background(detailHeaderScrim()))

        // Nombre + artista superpuestos (el back vive en la topbar pineada del caller).
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                // El header sangra hasta el borde, pero su TEXTO no: mismo reparto que en la
                // topbar overlay — fondo hasta el borde, contenido apartado.
                .safeSidePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = albumName.ifBlank { stringResource(R.string.common_unknown_album) },
                style = MaterialTheme.typography.headlineLargeEmphasized,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: aporta el layout (artista/chip debajo) y reporta
                // su ancla; el texto visible es el título viajero del overlay.
                modifier = Modifier
                    .graphicsLayer { alpha = 0f }
                    .onGloballyPositioned { onTitlePositioned(it.positionInRoot(), it.size.height) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (singleArtist != null) {
                Text(
                    text = singleArtist.ifBlank { stringResource(R.string.common_unknown_artist) },
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onArtistClick(singleArtist) }
                        .padding(vertical = 2.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.common_various_artists),
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountChip {
                    MaterialSymbol("music_note", size = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (songCount == 1) "1 canción" else "$songCount canciones",
                    )
                }
                // El año solo aparece si alguna pista lo declara: un chip "0" o vacío sería peor
                // que no tenerlo, y hay bibliotecas enteras sin ese tag.
                if (year > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CountChip {
                        Text(
                            text = year.toString(),
                        )
                    }
                }
            }
        }
    }
}
