package com.qhana.siku.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSideInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.bleedHorizontally
import com.qhana.siku.ui.components.rememberDetailHeaderMetrics
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.rememberActiveRowContentColor
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.rememberReorderableListItemShape
import com.qhana.siku.ui.components.rememberRowAccentColor
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.components.sort
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appItemPlacementSpec

/**
 * Detalle de lista de reproducción / favoritos, con el MISMO lenguaje inmersivo que álbum y
 * artista: cabecera edge-to-edge (collage de carátulas) con scrim, título viajero que escala
 * hasta la topbar mínima al scrollear, botonera compacta y canciones en tarjetas segmentadas.
 *
 * Antes tenía TopAppBar clásica + collage centrado en tarjeta: era la única pantalla de detalle
 * fuera del patrón.
 */
@Composable
fun PlaylistDetailScreen(
    playlistName: String,
    songs: List<Song>,
    currentSong: Song?,
    isFavoritesList: Boolean = false,
    /** Qué canciones son favoritas, para el toggle del overflow de cada fila. */
    favorites: Set<String>,
    /** Listas destino de "añadir a lista de reproducción" (overflow de cada fila). */
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones de la lista, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    onReorderSongs: ((List<String>) -> Unit)? = null,
    onRemoveSong: ((String) -> Unit)? = null,
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Copia local para que el arrastre se vea fluido; el orden se persiste al soltar.
    var localSongs by remember(songs) { mutableStateOf(songs) }

    // Motion de las filas al añadirse y quitarse, con el reparto que manda la convención: la
    // POSICIÓN por un token spatial (rebota, damping 0.8) y las OPACIDADES por effects (damping
    // 1.0). La salida va con el token FAST porque lo que se va no debe hacerse esperar — es
    // literalmente para lo que existe ese token— y a la vez es lo que impide que el hueco tarde
    // más en cerrarse que la fila en desaparecer. Los tres se izan aquí y no se piden por fila:
    // el valor es el mismo para todas y la lista los pediría en cada recomposición.
    //
    // Sustituyen al default de `animateItem` (los tres a `spring(stiffness = 400)` sin rebote),
    // que es de `compose-foundation` y no mira el `MaterialTheme`: su fundido iba a 400 contra los
    // 1600 del `effects` del tema, o sea cuatro veces más lento que el resto de la app.
    val rowFadeInSpec = appEffectsSpec<Float>()
    val rowPlacementSpec = appItemPlacementSpec()
    val rowFadeOutSpec = appFastEffectsSpec<Float>()

    // Flujo "añadir a lista de reproducción" desde el overflow de una fila, igual que en el detalle
    // de artista: hoja de listas → (opcional) diálogo de lista nueva, que nace con la canción.
    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // Los dos primeros ítems del LazyColumn son la cabecera y la botonera: los índices que
    // reporta la librería de reordenado son de la LISTA LAZY, no de las canciones.
    val headerItems = 2
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index - headerItems
        val toIndex = to.index - headerItems
        if (fromIndex in localSongs.indices && toIndex in localSongs.indices) {
            localSongs = localSongs.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    // Título viajero: interpola posición y escala entre el nombre del header y el hueco de la
    // topbar según el scroll (mismo mecanismo que AlbumDetailScreen).
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            if (songs.isEmpty()) {
                EmptyPlaylistState(
                    isFavoritesList = isFavoritesList,
                    onAddSongs = onAddSongs,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            } else {
                // El MiniPlayer global FLOTA sobre esta pantalla: se reserva su alto para que
                // el último ítem pueda scrollear por encima.
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

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = sideInsets.calculateStartPadding(layoutDirection),
                        end = sideInsets.calculateEndPadding(layoutDirection),
                        bottom = listBottomInset
                    )
                ) {
                    item(key = "header") {
                        PlaylistImmersiveHeader(
                            playlistName = playlistName,
                            isFavoritesList = isFavoritesList,
                            songs = localSongs,
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

                    item(key = "actions") {
                        // "Añadir canciones" = botón redondo junto al aleatorio (onAddSongs);
                        // reemplazó al FAB flotante. Solo en listas editables (no Favoritos).
                        DetailPlayButtons(
                            onPlayAll = { onPlayAll(localSongs, 0) },
                            onShuffle = { onShufflePlay(localSongs) },
                            // Solo esta pantalla lo pasa: es la única cuyo orden lo puso el
                            // usuario, así que es la única donde "al revés" quiere decir algo.
                            onPlayInOrder = { order -> onPlayAll(order.sort(localSongs), 0) },
                            onAddToQueue = { onAddAllToQueue(localSongs) },
                            onAddSongs = onAddSongs,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }

                    itemsIndexed(
                        items = localSongs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y
                        // la lista de canciones (el resaltado marca "cargada", no "sonando ahora").
                        val isPlaying = currentSong?.id == song.id
                        val canReorder = onReorderSongs != null && !isFavoritesList

                        if (canReorder) {
                            // `animateItemModifier` explícito: el default del componente es
                            // `Modifier.animateItem()` a secas, y con él las dos ramas de esta
                            // misma pantalla quitarían filas a velocidades distintas. La librería
                            // solo lo aplica a los items que NO se están arrastrando, así que
                            // sigue sin pisar el offset del arrastre.
                            ReorderableItem(
                                reorderState,
                                key = song.id,
                                animateItemModifier = Modifier.animateItem(
                                    fadeInSpec = rowFadeInSpec,
                                    placementSpec = rowPlacementSpec,
                                    fadeOutSpec = rowFadeOutSpec
                                )
                            ) { isDragging ->
                                // isActive: el ítem en reproducción usa la forma redondeada (16 dp),
                                // igual que en la cola y la lista de canciones, en vez de la esquina
                                // agrupada. isDragging hace lo mismo mientras la fila está levantada
                                // (el `draggedShape` del spec; ver [rememberReorderableListItemShape]): fuera
                                // del bloque, no debe conservar las esquinas con las que encajaba.
                                val shape = rememberReorderableListItemShape(
                                    index = index,
                                    count = localSongs.size,
                                    isActive = isPlaying,
                                    isDragging = isDragging
                                )
                                // Elevación del SPEC (`ListTokens.ItemDraggedContainerElevation` /
                                // `ItemContainerElevation`, vía `ListItemDefaults.elevation()`) y no
                                // un 8dp escrito a mano — que resultaba ser ese mismo valor, pero
                                // sin quedar atado a él. Mismo cambio que en la hoja de la cola.
                                val listElevation = ListItemDefaults.elevation()
                                val elevation =
                                    if (isDragging) listElevation.draggedElevation else listElevation.elevation
                                // El resaltado del ítem en reproducción lo pinta la Surface de TODA
                                // la fila —igual que en la cola, que tiene este mismo layout— y no
                                // el `ListItem` de SongItem: aquí SongItem es un `weight(1f)` entre
                                // el grip y los botones, así que su tinte cubría solo el trozo
                                // central, con esquinas rectas, y la fila activa se leía distinta
                                // que en el resto de listas de la app.
                                val rowBackground = songRowBackground(AppColors.surface, isPlaying)
                                val activeContent = rememberActiveRowContentColor(rowBackground, isPlaying)
                                val rowVariantColor =
                                    if (isPlaying) activeContent else AppColors.onSurfaceVariant
                                // Punta ORIGEN del container transform hacia el reproductor (ver
                                // [SongRowContainer]). Va DENTRO del `ReorderableItem`: lo que se
                                // arrastra es la fila, y lo que morfa es esa misma superficie.
                                SongRowContainer(
                                    songId = song.id,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 1.dp)
                                ) {
                                    AppSurface(
                                        color = rowBackground,
                                        contentColor = if (isPlaying) activeContent else AppColors.onSurface,
                                        shape = shape,
                                        tonalElevation = elevation,
                                        shadowElevation = elevation,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Mismo layout que la cola: grip de reordenado LEADING (Box
                                        // de 32dp con draggableHandle) + SongItem + quitar TRAILING.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStopped = {
                                                            onReorderSongs?.invoke(localSongs.map { it.id })
                                                        }
                                                    )
                                                    .width(32.dp)
                                                    .fillMaxHeight(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                MaterialSymbol("drag_indicator", color = rowVariantColor, size = 20.sp)
                                            }
                                            SongItem(
                                                song = song,
                                                isPlaying = isPlaying,
                                                showStatusIcon = false,
                                                // La Surface de arriba ya rellena la fila entera: aquí
                                                // solo se tiñe el texto con el `on-` de ese relleno.
                                                showActiveBackground = false,
                                                activeContentColor = activeContent,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onPlayAll(localSongs, index) }
                                            )
                                            SongOverflowButton(
                                                isFavorite = song.id in favorites,
                                                onToggleFavorite = { onToggleFavorite(song.id) },
                                                onAddToPlaylist = { songIdForPlaylist = song.id },
                                                onAddToQueue = { onAddToQueue(song) },
                                                rowBackground = rowBackground
                                            )
                                            onRemoveSong?.let { remove ->
                                                val removeDesc = stringResource(R.string.playlist_remove_song)
                                                IconButton(
                                                    onClick = { remove(song.id) },
                                                    modifier = Modifier
                                                        .padding(end = 4.dp)
                                                        .semantics { contentDescription = removeDesc }
                                                ) {
                                                    MaterialSymbol("close", color = rowVariantColor, size = 20.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Sin arrastre no hay estado `dragged`: solo la posición en el grupo y
                            // si la canción es la que está cargada.
                            val shape = rememberListItemShape(
                                index = index,
                                count = localSongs.size,
                                isActive = isPlaying
                            )
                            // Punta ORIGEN del container transform hacia el reproductor; ver
                            // [SongRowContainer].
                            //
                            // `animateItem()` es lo que hace que quitar una canción no sea un
                            // corte: la fila se funde y las de abajo suben deslizándose, en vez de
                            // desaparecer entre dos frames. Aquí va FUERA porque esta rama no pasa
                            // por `ReorderableItem`, que es quien lo aplica en la otra (por eso
                            // allí se le pasa el mismo juego de specs por `animateItemModifier`, y
                            // no se añade un segundo `animateItem` encima).
                            SongRowContainer(
                                songId = song.id,
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = rowFadeInSpec,
                                        placementSpec = rowPlacementSpec,
                                        fadeOutSpec = rowFadeOutSpec
                                    )
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            ) {
                                AppSurface(
                                    color = AppColors.surface,
                                    shape = shape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SongItem(
                                        song = song,
                                        isPlaying = isPlaying,
                                        showStatusIcon = false,
                                        modifier = Modifier.clickable { onPlayAll(localSongs, index) },
                                        // El corazón va PRIMERO y el ⋮ detrás: es la acción
                                        // principal de la fila en esta lista, y estaba puesta
                                        // DESPUÉS del cajón de las secundarias. Al estar fuera,
                                        // el menú no repite el favorito (`onToggleFavorite` en
                                        // null) — serían dos controles para lo mismo a un
                                        // centímetro uno del otro.
                                        trailingContent = {
                                            val rowBackground =
                                                songRowBackground(AppColors.surface, isPlaying)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isFavoritesList) {
                                                    IconButton(onClick = { onToggleFavorite(song.id) }) {
                                                        MaterialSymbol(
                                                            "favorite",
                                                            fill = true,
                                                            // Del fondo REAL de la fila: en la que
                                                            // suena, `primary` se funde con el
                                                            // `primaryContainer` que la rellena.
                                                            color = rememberRowAccentColor(rowBackground)
                                                        )
                                                    }
                                                }
                                                SongOverflowButton(
                                                    isFavorite = song.id in favorites,
                                                    // Sin corazón fuera (una lista normal que
                                                    // cayera en esta rama por no ser reordenable),
                                                    // el favorito vuelve al menú.
                                                    onToggleFavorite = if (isFavoritesList) null else {
                                                        { onToggleFavorite(song.id) }
                                                    },
                                                    onAddToPlaylist = { songIdForPlaylist = song.id },
                                                    onAddToQueue = { onAddToQueue(song) },
                                                    rowBackground = rowBackground
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TopBar mínima: el back siempre visible (flota sobre el collage); fondo y título
            // se funden a la vista cuando el nombre grande sale de pantalla.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(AppColors.surfaceContainerHigh.copy(alpha = if (songs.isEmpty()) 1f else topBarAlpha))
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
                    // Con canciones: caja de alto cero cuya posición es el DESTINO del título
                    // viajero. Sin canciones no hay cabecera de la que viajar, así que el
                    // nombre se escribe aquí directamente.
                    if (songs.isEmpty()) {
                        Text(
                            text = playlistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                        )
                    }
                }
            }

            if (songs.isNotEmpty()) {
                Text(
                    text = playlistName,
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 40.dp)
                        .graphicsLayer {
                            val f = FastOutSlowInEasing.transform(topBarAlpha)
                            // titleMedium (16sp) / headlineLarge (32sp)
                            val endScale = 16f / 32f
                            val scale = lerp(1f, endScale, f)
                            val startX = headerTitleAnchor.x - overlayOrigin.x
                            val startY = headerTitleAnchor.y - overlayOrigin.y
                            val endX = barTitleAnchor.x - overlayOrigin.x
                            val endY = (barTitleAnchor.y - overlayOrigin.y) - (headerTitleHeight * endScale) / 2f
                            translationX = lerp(startX, endX, f)
                            translationY = lerp(startY, endY, f)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }
        }
    }

    // Flujo "añadir a lista de reproducción" del overflow de fila, idéntico al del detalle de
    // artista: si el usuario pide lista nueva, la canción se RETIENE para que nazca dentro.
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

@Composable
private fun EmptyPlaylistState(
    isFavoritesList: Boolean,
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Glifos que dicen VACÍO, no glifos de la entidad: `heart_broken` y `playlist_remove`
            // en vez de `favorite` y `queue_music`. Los de la entidad son los mismos que marcan una
            // lista CON contenido (el corazón de Favoritos, el icono de lista de cada fila), así
            // que aquí no añadían nada al mensaje que hay justo debajo; estos sí.
            MaterialSymbol(
                if (isFavoritesList) "heart_broken" else "playlist_remove",
                fill = true,
                color = AppColors.outline,
                size = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isFavoritesList) stringResource(R.string.favorites_empty)
                else stringResource(R.string.playlist_empty),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.onSurface
            )
            if (!isFavoritesList) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.playlist_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSurfaceVariant
                )
            }
            // Botón de alta AQUÍ: con la lista vacía no se dibuja la cabecera con
            // DetailPlayButtons (donde vive el "añadir canciones" del caso con canciones),
            // así que sin esto una lista recién creada no tenía NINGUNA forma de llenarse
            // desde su propio detalle. Vale también para Favoritos: la hoja soporta
            // addSongsToFavorites y sin botón quedaba inalcanzable con la lista vacía.
            if (onAddSongs != null) {
                Spacer(modifier = Modifier.height(24.dp))
                // Glifo y separación DERIVADOS de la altura del botón (`iconSizeFor` /
                // `iconSpacingFor`), no literales — mismo trato que su hermano "Crear lista" en la
                // pestaña Listas, que es el otro extremo de esta misma acción.
                val addButtonHeight = ButtonDefaults.MinHeight
                Button(colors = appButtonColors(), onClick = onAddSongs, shapes = ButtonDefaults.shapes()) {
                    MaterialSymbol(
                        // "music_note_add" y no "playlist_add": con la lista VACÍA lo que se
                        // ofrece es meter canciones, no añadir una lista a algo.
                        "music_note_add",
                        size = ButtonDefaults.iconSizeFor(addButtonHeight).value.sp
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(addButtonHeight)))
                    Text(stringResource(R.string.playlist_add_songs))
                }
            }
        }
    }
}

/**
 * Cabecera inmersiva: [AdaptiveCollage] de hasta 4 carátulas distintas, con su propio placeholder
 * cuando la lista no tiene ninguna.
 */
@Composable
private fun PlaylistImmersiveHeader(
    playlistName: String,
    isFavoritesList: Boolean,
    songs: List<Song>,
    onTitlePositioned: (Offset, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val arts = remember(songs) { songs.mapNotNull { it.albumArtUriString }.distinct().take(4) }
    val totalDurationMin = remember(songs) { songs.sumOf { it.duration } / 60_000 }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rememberDetailHeaderMetrics().headerHeight)
    ) {
        when (arts.size) {
            0 -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AppColors.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol(
                    if (isFavoritesList) "favorite" else "queue_music",
                    fill = true,
                    size = 96.sp,
                    color = AppColors.onSurfaceVariant
                )
            }
            else -> AdaptiveCollage(arts, Modifier.matchParentSize())
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
                text = playlistName,
                style = MaterialTheme.typography.headlineLargeEmphasized,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: solo aporta layout y su ancla; el texto visible es el
                // título viajero del overlay.
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
                        text = pluralStringResource(R.plurals.song_count, songs.size, songs.size),
                        color = AppColors.onSecondaryContainer
                    )
                }
                if (totalDurationMin > 0) {
                    TonalChip {
                        MaterialSymbol("schedule", size = 14.sp, color = AppColors.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$totalDurationMin min",
                            color = AppColors.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
