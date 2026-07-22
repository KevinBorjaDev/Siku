package com.qhana.siku.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qhana.siku.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.model.SongUiModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * Pantalla completa que muestra la lista de reproducción actual con reordenamiento
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueBottomSheet(
    playlist: List<SongUiModel>,
    currentIndex: Int,
    onSongClick: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    isShuffleEnabled: Boolean,
    onShuffleToggle: () -> Unit,
    onRemoveSong: (Int) -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color? = null
) {
    val isDarkTheme = isSystemInDarkTheme()
    val materialPrimary = MaterialTheme.colorScheme.primary
    // Énfasis homogéneo con el NowPlaying: usa el acento del álbum (si se pasa).
    val effectiveAccent = accentColor ?: materialPrimary

    // Colores cacheados una sola vez
    val colors = remember(isDarkTheme, effectiveAccent) {
        QueueColors(
            backgroundColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF5F5F5),
            surfaceColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White,
            onSurfaceColor = if (isDarkTheme) Color.White else Color(0xFF1A1A1A),
            onSurfaceVariantColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF666666),
            accentColor = effectiveAccent
        )
    }

    // Estado local mutable para reordenamiento visual inmediato
    var localPlaylist by remember { mutableStateOf(playlist) }
    var localCurrentIndex by remember { mutableIntStateOf(currentIndex) }

    // Sincronizar solo cuando cambia la referencia de playlist (no en cada frame)
    LaunchedEffect(playlist) {
        localPlaylist = playlist
    }
    LaunchedEffect(currentIndex) {
        localCurrentIndex = currentIndex
    }

    // Tamaño cacheado como estado derivado
    val playlistSize by remember { derivedStateOf { localPlaylist.size } }

    val listState = rememberLazyListState()

    // Estado para rastrear el movimiento completo
    var initialDragIndex by remember { mutableStateOf<Int?>(null) }
    var currentDragIndex by remember { mutableStateOf<Int?>(null) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (initialDragIndex == null) {
            initialDragIndex = from.index
        }
        currentDragIndex = to.index

        localPlaylist = localPlaylist.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }

        localCurrentIndex = when {
            from.index == localCurrentIndex -> to.index
            from.index < localCurrentIndex && to.index >= localCurrentIndex -> localCurrentIndex - 1
            from.index > localCurrentIndex && to.index <= localCurrentIndex -> localCurrentIndex + 1
            else -> localCurrentIndex
        }
    }

    // Detectar fin del arrastre
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) {
            val start = initialDragIndex
            val end = currentDragIndex

            if (start != null && end != null && start != end) {
                onReorder(start, end)
            }

            initialDragIndex = null
            currentDragIndex = null
        }
    }

    // Scroll inicial al elemento actual
    LaunchedEffect(Unit) {
        if (localCurrentIndex >= 0 && localPlaylist.isNotEmpty()) {
            listState.scrollToItem((localCurrentIndex - 3).coerceAtLeast(0))
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

    val saveQueueDesc = stringResource(R.string.queue_save_as_playlist)

    // Diálogo "guardar cola como lista".
    var showSaveDialog by remember { mutableStateOf(false) }
    if (showSaveDialog) {
        CreatePlaylistDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                onSaveAsPlaylist(name)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.queue_title),
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        MaterialSymbol("arrow_back")
                    }
                },
                actions = {
                    // Guardar la cola como lista nueva (solo si hay canciones). PÍLDORA VERTICAL
                    // tonal (secondaryContainer), mismo idiom que el overflow vertical de las
                    // listas: comparte la familia tonal del shuffle sin ser otra píldora horizontal
                    // con label; la forma (vertical) la distingue como acción de una-vez.
                    if (localPlaylist.isNotEmpty()) {
                        FilledTonalIconButton(
                            onClick = { showSaveDialog = true },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .width(32.dp)
                                .height(44.dp)
                                .semantics { contentDescription = saveQueueDesc }
                        ) {
                            MaterialSymbol("playlist_add", size = 20.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // Shuffle = TONAL ToggleButton (spec Expressive): morfea de PÍLDORA (off) a
                    // RECTÁNGULO REDONDEADO (on) al seleccionarse, con colores tonales. El estado se
                    // lee por la forma + el color del contenedor (+ relleno del glifo).
                    // padding end 12dp para alinear el borde con el margen 16dp de la lista.
                    val shuffleDesc = if (isShuffleEnabled)
                        stringResource(R.string.np_shuffle_off) else stringResource(R.string.np_shuffle_on)
                    ToggleButton(
                        checked = isShuffleEnabled,
                        onCheckedChange = { onShuffleToggle() },
                        colors = ToggleButtonDefaults.tonalToggleButtonColors(),
                        elevation = null,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .semantics { contentDescription = shuffleDesc }
                    ) {
                        MaterialSymbol("shuffle", size = 18.sp, fill = isShuffleEnabled)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.queue_shuffle))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onSurfaceColor,
                    navigationIconContentColor = colors.onSurfaceColor,
                    // La hoja usa la paleta de la carátula, no el tema: el default del token
                    // (onSurfaceVariant del tema) desentonaría con el resto del sheet.
                    subtitleContentColor = colors.onSurfaceVariantColor
                )
            )

            if (localPlaylist.isEmpty()) {
                EmptyQueueContent(colors)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
                ) {
                    itemsIndexed(
                        items = localPlaylist,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> "queue_song" }
                    ) { index, song ->
                        val isCurrentSong = index == localCurrentIndex

                        // Shape calculado fuera del ReorderableItem
                        val shape = rememberListItemShape(index, playlistSize, isCurrentSong)

                        ReorderableItem(
                            state = reorderState,
                            key = song.id
                        ) { isDragging ->
                            QueueItemRow(
                                song = song,
                                isCurrentSong = isCurrentSong,
                                isDragging = isDragging,
                                shape = shape,
                                colors = colors,
                                onClick = {
                                    onSongClick(index)
                                    onDismiss()
                                },
                                onRemove = {
                                    val removeIdx = index
                                    val newSize = localPlaylist.size - 1
                                    // Ajuste del índice actual con la lista VIEJA, luego se remueve.
                                    localCurrentIndex = when {
                                        newSize <= 0 -> -1
                                        removeIdx < localCurrentIndex -> localCurrentIndex - 1
                                        removeIdx == localCurrentIndex -> removeIdx.coerceAtMost(newSize - 1)
                                        else -> localCurrentIndex
                                    }
                                    localPlaylist = localPlaylist.toMutableList().apply { removeAt(removeIdx) }
                                    onRemoveSong(removeIdx)
                                },
                                dragHandleModifier = Modifier.draggableHandle()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Row individual de la cola - Optimizado con Surface para mejor rendimiento de renderizado
 */
@Composable
private fun QueueItemRow(
    song: SongUiModel,
    isCurrentSong: Boolean,
    isDragging: Boolean,
    shape: Shape,
    colors: QueueColors,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier
) {
    val backgroundColor = if (isCurrentSong) {
        // Resaltado del tema actual teñido con el acento del álbum (sólido, sin translucidez).
        Color(
            androidx.core.graphics.ColorUtils.blendARGB(
                MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
                colors.accentColor.toArgb(),
                0.30f
            )
        )
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    // Usar Surface es más eficiente que Modifier.clip().background()
    // Surface maneja el clipping y el dibujo de fondo en una sola pasada de renderizado cuando es posible.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .then(if (isDragging) Modifier.shadow(8.dp, shape) else Modifier), // Shadow solo si arrastra
        shape = shape,
        color = backgroundColor,
        tonalElevation = if (isDragging) 8.dp else 0.dp, // Elevación visual
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Drag Handle
            Box(
                modifier = dragHandleModifier
                    .width(32.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol(
                    icon = "drag_indicator",
                    color = if (isDragging) colors.accentColor else colors.onSurfaceVariantColor,
                    size = 20.sp
                )
            }

            // Reutiliza SongItem con la misma apariencia de Library. showActiveBackground=false:
            // el resaltado del item en reproducción lo pinta la Surface de TODA la fila (arriba),
            // no un recuadro de SongItem solo alrededor del contenido (weight 1f) que competía.
            SongItem(
                song = song,
                isPlaying = isCurrentSong,
                showActiveBackground = false,
                modifier = Modifier.weight(1f)
            )

            // Quitar de la cola.
            val removeDesc = stringResource(R.string.queue_remove_song)
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .semantics { contentDescription = removeDesc }
            ) {
                MaterialSymbol("close", color = colors.onSurfaceVariantColor, size = 20.sp)
            }
        }
    }
}

@Immutable
private data class QueueColors(
    val backgroundColor: Color,
    val surfaceColor: Color,
    val onSurfaceColor: Color,
    val onSurfaceVariantColor: Color,
    val accentColor: Color
)

@Composable
private fun EmptyQueueContent(colors: QueueColors) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MaterialSymbol("music_note", size = 48.sp, color = colors.onSurfaceVariantColor)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.queue_empty), color = colors.onSurfaceVariantColor)
        }
    }
}
