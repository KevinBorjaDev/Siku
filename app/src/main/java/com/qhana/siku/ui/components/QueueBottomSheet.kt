package com.qhana.siku.ui.components

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qhana.siku.ui.theme.appVibrantFloatingToolbarColors
import com.qhana.siku.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.model.SongUiModel
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

import androidx.compose.foundation.ExperimentalFoundationApi

/** Separación de la floating toolbar respecto al borde inferior de la hoja. */
private val QueueToolbarMargin = 16.dp

/** Aire entre la app bar y la primera canción. */
private val QueueListTopPadding = 8.dp

/**
 * Tamaño de los dos iconos de acción de la fila (estado de descarga y quitar). Es el tamaño de
 * icono de list item de M3 (20 dp), el MISMO que [ComponentConfig.StatusIconSize] usa ahora en la
 * biblioteca: el indicador de descarga se ve idéntico en la lista y en la cola. (Antes la lista lo
 * dibujaba a 18 dp "compacto" y quedaba más pequeño que el mismo glifo en la cola.)
 */
private val QueueRowActionIconSize = 20.dp

/**
 * Glifo de los tres botones de la barra de acciones de la cola. Son botones de la escala SMALL
 * —los que M3 usa dentro de una floating toolbar— y `ButtonSmallTokens.IconSize` son 20 dp, que es
 * de donde sale este valor; va en sp porque [MaterialSymbol] es una fuente variable.
 */
private val ToolbarIconSize = 20.sp

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
    /** Vacía la cola entera y detiene la reproducción (con deshacer en el snackbar). */
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color? = null
) {
    val materialPrimary = AppColors.primary
    // Énfasis homogéneo con el NowPlaying: usa el acento del álbum (si se pasa).
    val effectiveAccent = accentColor ?: materialPrimary

    // Lo ÚNICO propio de esta hoja es el acento (el del álbum que suena); el resto son los roles
    // del tema. Hasta el 10 ago 2026 los tres eran hexes fijos por modo (`#121212`/`#F5F5F5`,
    // blanco, `#B3B3B3`/`#666666`) bajo un comentario que decía que la hoja "usa la paleta de la
    // carátula": era falso —solo el acento venía de ahí— y el efecto real era una hoja gris
    // neutra flotando sobre una app teñida por el color dinámico. `surface` y sus `on*` ya llevan
    // ese tinte, así que ahora la hoja pertenece al mismo tema que la lista de debajo.
    // Las keys son los COLORES, nunca el `ColorScheme`: `MaterialTheme` conserva una única
    // instancia y le muta los estados internos al cambiar de tema, así que un `remember(scheme)`
    // no se invalidaría jamás y la hoja se quedaría con la paleta de la canción anterior.
    val background = AppColors.surface
    val onBackground = AppColors.onSurface
    val onBackgroundVariant = AppColors.onSurfaceVariant
    val colors = remember(background, onBackground, onBackgroundVariant, effectiveAccent) {
        QueueColors(
            backgroundColor = background,
            onSurfaceColor = onBackground,
            onSurfaceVariantColor = onBackgroundVariant,
            accentColor = effectiveAccent
        )
    }

    // Estado local mutable para reordenamiento visual inmediato
    var localPlaylist by remember { mutableStateOf(playlist) }
    var localCurrentIndex by remember { mutableIntStateOf(currentIndex) }

    // Una lista del controller que llegó a mitad de un arrastre y todavía no se aplicó. La
    // sincronización se pospone (ver más abajo) y hay que recordar que quedó pendiente.
    var syncDeferred by remember { mutableStateOf(false) }

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

    // Sincronizar solo cuando cambia la referencia de playlist (no en cada frame), y NUNCA con el
    // dedo puesto: la lista del controller reemite sola (p. ej. al refrescar la URL de la canción
    // siguiente), y pisar la copia local a mitad del arrastre dejaba `initialDragIndex`/
    // `currentDragIndex` apuntando a una lista que ya no es la que se ve — el reorden final movía
    // la canción equivocada. Lo que llegue durante el arrastre se aplica al soltar.
    LaunchedEffect(playlist) {
        if (reorderState.isAnyItemDragging) {
            syncDeferred = true
        } else {
            localPlaylist = playlist
            syncDeferred = false
        }
    }
    LaunchedEffect(currentIndex) {
        localCurrentIndex = currentIndex
    }

    // Detectar fin del arrastre
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) {
            val start = initialDragIndex
            val end = currentDragIndex

            val reordered = start != null && end != null && start != end
            if (reordered) {
                onReorder(start!!, end!!)
            }

            // Con reorden no se toca la copia local: el orden que se ve ES el que se acaba de
            // pedir, y la emisión que provoque `onReorder` lo confirmará. Sin él, en cambio, hay
            // que aplicar la lista que se dejó pasar durante el arrastre o la cola se queda
            // enseñando un orden que ya no existe.
            if (!reordered && syncDeferred) {
                localPlaylist = playlist
                syncDeferred = false
            }

            initialDragIndex = null
            currentDragIndex = null
        }
    }

    // Scroll inicial a la canción en curso, que queda ENCABEZANDO la lista: la cola se abre para ver
    // lo que viene y reordenarlo, así que todo lo que se ponga por encima son filas menos de lo que
    // sí se está mirando. Hubo un `- 3` (dejar tres reproducidas arriba "por contexto") y no había
    // nada que hiciera a 3 mejor que a 1 o a 5 — la señal de que el número no debía existir.
    //
    // **La clave NO puede ser `Unit`**, y ese era el bug de "la cola siempre abre por el principio":
    // la lista llega VACÍA a la primera composición —`uiPlaylist` es un `produceState` que mapea los
    // modelos en `Dispatchers.Default` (ver NowPlayingScreen), así que su primer valor es
    // `emptyList()`—, de modo que en el ÚNICO intento la guarda `isNotEmpty()` era falsa y no había
    // segundo. La lista aparecía unos ms después, ya en el ítem 0. La clave tiene que ser la
    // condición que el efecto necesita, no el momento del montaje.
    //
    // El flag es lo que lo deja en UN scroll por apertura: sin él, cualquier cambio de tamaño
    // (quitar una canción, o la reemisión del controller tras un reorden) devolvería la lista a la
    // canción en curso y se llevaría por delante el sitio donde el usuario estaba mirando. Alcanza
    // con `remember` porque la hoja se DESCOMPONE al cerrarse (ver [SheetOverlay]): la vida del
    // recuerdo es exactamente una apertura.
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(playlistSize, localCurrentIndex) {
        if (initialScrollDone || playlistSize <= 0 || localCurrentIndex < 0) return@LaunchedEffect
        initialScrollDone = true
        listState.scrollToItem(localCurrentIndex)
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

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
            // La perforación cae en un costado al girar: el fondo va antes y llega al borde.
            .safeSidePadding()
    ) {
        // Esta hoja NO pasa por `AppModalSheet` (es un overlay a pantalla completa montado por
        // `SheetOverlay`), así que se quedó sin el `sheetMaxWidth` que M3 le da gratis a las otras
        // cuatro y en horizontal repartía sus filas entre ~873dp. Mismo tope, puesto a mano.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
        ) {
            // Top Bar
            TopAppBar(
                title = {
                    // Sin peso propio: la app bar ya aplica el rol tipográfico de su spec.
                    Text(text = stringResource(R.string.queue_title))
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        MaterialSymbol("arrow_back")
                    }
                },
                // SIN acciones: las tres (aleatorio, guardar como lista, vaciar) viven en la
                // floating toolbar de abajo. Con ellas aquí la barra llevaba atrás + título +
                // píldora + un toggle CON etiqueta, y no admitía una cuarta — que es lo que
                // destapó querer añadir "vaciar". Bajarlas además las pone al alcance del pulgar
                // en una hoja a pantalla completa, y deja el título respirando.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onSurfaceColor,
                    navigationIconContentColor = colors.onSurfaceColor,
                    subtitleContentColor = colors.onSurfaceVariantColor
                )
            )

            if (localPlaylist.isEmpty()) {
                EmptyQueueContent(colors)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // El hueco de abajo lo DERIVA la toolbar flotante, que se dibuja encima: sin
                    // reservarlo, la última canción de la cola queda debajo y no se puede tocar
                    // ni arrastrar. Mismo patrón con el que las demás pantallas reservan el
                    // MiniPlayer, y derivado para que cambiar el alto de la barra no obligue a
                    // recalcular este número a mano.
                    contentPadding = PaddingValues(
                        top = QueueListTopPadding,
                        bottom = ComponentConfig.FloatingToolbarHeight + QueueToolbarMargin * 2
                    )
                ) {
                    itemsIndexed(
                        items = localPlaylist,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> "queue_song" }
                    ) { index, song ->
                        val isCurrentSong = index == localCurrentIndex

                        ReorderableItem(
                            state = reorderState,
                            key = song.id
                        ) { isDragging ->
                            // La forma se calcula DENTRO del `ReorderableItem` porque depende de
                            // `isDragging`: una fila levantada redondea entera (el `draggedShape`
                            // del spec, ver [rememberReorderableListItemShape]) en vez de conservar las
                            // esquinas de 4dp con las que encajaba entre unos vecinos que ya no
                            // tiene debajo.
                            val shape = rememberReorderableListItemShape(
                                index = index,
                                count = playlistSize,
                                isActive = isCurrentSong,
                                isDragging = isDragging
                            )
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

        // Acciones sobre la cola ENTERA, en una floating toolbar y no en la app bar (ver la nota
        // de `TopAppBar`). Solo con canciones: sobre una cola vacía las tres no tienen objeto, y
        // el empty state ya explica qué pasa.
        if (localPlaylist.isNotEmpty()) {
            QueueActionsToolbar(
                isShuffleEnabled = isShuffleEnabled,
                onShuffleToggle = onShuffleToggle,
                onSaveAsPlaylist = { showSaveDialog = true },
                onClearQueue = onClearQueue,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = QueueToolbarMargin)
            )
        }
    }
}

/**
 * Acciones que operan sobre la cola ENTERA, en una floating toolbar M3 Expressive — el componente
 * del spec para un grupo de acciones que la app bar no admite, y el mismo que usa el NowPlaying.
 *
 * **Las tres llevan ICONO Y ETIQUETA.** Con dos de ellas como iconos sueltos había que adivinar
 * qué hacía cada una, y son acciones sobre la cola ENTERA — una de ellas destructiva: el peor
 * sitio para un glifo ambiguo. Cabe porque las etiquetas van en su forma corta ("Guardar",
 * "Vaciar"); la larga vive en el `contentDescription`, que es quien debe decirlo completo.
 *
 * La jerarquía es la del toolbar del spec: **el que tiene ESTADO va con contenedor tonal, los
 * demás planos**. Aquí sale sola, porque el único con estado es el aleatorio —es un MODO, se
 * queda encendido— mientras que guardar y vaciar son acciones de una vez. Un contenedor tonal en
 * las tres las igualaría y volvería a hacer falta leerlas una por una para saber qué está activo.
 *
 * **Guardar y vaciar van en `onPrimaryContainer`**, que es el color del contenido del toolbar
 * vibrant (`VibrantButtonUnselectedTextColor` en los tokens): el contenedor es `primaryContainer`,
 * así que su `on-` es el único que garantiza contraste. Los `TextButton` de M3 fuerzan su propio
 * `contentColor = primary` y NO heredan el `LocalContentColor` del toolbar — sobre el
 * `primaryContainer` oscuro de un tema saturado, ese `primary` queda casi invisible (fue el bug del
 * "Guardar" lavado). Vaciar ya NO va en `error`: pedía un rojo que sobre este contenedor había que
 * corregir por contraste y que el usuario descartó; es destructiva pero no pide confirmación porque
 * el snackbar ofrece deshacer (ver `PlaybackViewModel.clearQueue`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueActionsToolbar(
    isShuffleEnabled: Boolean,
    onShuffleToggle: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shuffleDesc = if (isShuffleEnabled)
        stringResource(R.string.np_shuffle_off) else stringResource(R.string.np_shuffle_on)
    val saveDesc = stringResource(R.string.queue_save_as_playlist)
    val clearDesc = stringResource(R.string.queue_clear)

    // VIBRANT (`primaryContainer`), como la del NowPlaying y no la standard que tenía: sobre el
    // `surface` de esta hoja, un contenedor `surfaceContainer` queda a un par de pasos tonales del
    // fondo y la barra se leía como una mancha, no como una superficie flotante. El acento la
    // despega y además la marca como "esto actúa sobre TODA la cola", frente a las filas.
    //
    // Contenido de los botones planos = `onPrimaryContainer`: es el `on-` del contenedor real de la
    // barra y el token que M3 usa para su texto (`VibrantButtonUnselectedTextColor`). Los TextButton
    // fuerzan `primary`, que sobre este contenedor apenas se ve, así que se pasa explícito.
    val toolbarContent = AppColors.onPrimaryContainer

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = appVibrantFloatingToolbarColors(),
            // Mismo motivo que en el NowPlaying: el componente no respeta su token de altura por
            // sí solo (acolcha alrededor de los touch targets), así que se fuerza.
            contentPadding = PaddingValues(ComponentConfig.FloatingBarInnerPadding),
            modifier = Modifier.height(ComponentConfig.FloatingToolbarHeight)
        ) {
            ToggleButton(
                checked = isShuffleEnabled,
                onCheckedChange = { onShuffleToggle() },
                // Activo = par `onPrimaryContainer`/`primaryContainer` INVERTIDO respecto al toolbar.
                // El toolbar de esta barra es `primaryContainer` (vibrant); ni el `secondaryContainer`
                // del tonal por defecto (pegado al fondo en croma bajo) ni un relleno `secondary`
                // (que en un tema VÍVIDO comparte el matiz azul del toolbar y queda una píldora media
                // apagada) despegan en TODOS los temas. Lo que contrasta con `primaryContainer` por
                // construcción es su propio `onPrimaryContainer`: rellenar con él y poner el contenido
                // en `primaryContainer` da una píldora sólida de alto contraste con la barra en los
                // cuatro casos (vívido/apagado × claro/oscuro), sin depender del croma de la carátula.
                //
                // APAGADO = PLANO sobre la barra, igual que los otros dos items. Los cuatro roles
                // van explícitos porque **un rol que no se pasa NO se hereda: se resuelve contra
                // `MaterialTheme.colorScheme`**, que desde la migración a [AppColors] es el esquema
                // BASE (claro/oscuro + wallpaper) y no lleva el color del álbum. Aquí solo estaba
                // escrito el par marcado, así que el estado apagado —el que se ve casi siempre—
                // se pintaba con el `secondaryContainer` del wallpaper: una píldora AZUL dentro de
                // una barra teñida por la carátula. Y en un toolbar el contenedor tonal tampoco
                // corresponde: la barra ya es la superficie, el relleno es lo que marca el estado.
                colors = ToggleButtonDefaults.tonalToggleButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = toolbarContent,
                    checkedContainerColor = AppColors.onPrimaryContainer,
                    checkedContentColor = AppColors.primaryContainer
                ),
                // PÍLDORA también encendido. El default de `ToggleButton` lleva
                // `SelectedContainerShapeSquare` en `checkedShape`, así que al activarse morfeaba a
                // squircle — correcto en un grupo de toggles, donde la forma distingue al elegido,
                // pero aquí el squircle YA significa otra cosa (encolar, en la botonera de los
                // detalles) y el estado se lee de sobra por el contenedor tonal y el relleno del
                // glifo. Solo se sobrescribe `checkedShape`: el morph al presionar se conserva.
                shapes = ToggleButtonDefaults.shapes(
                    checkedShape = ToggleButtonDefaults.roundShape
                ),
                elevation = null,
                modifier = Modifier.semantics { contentDescription = shuffleDesc }
            ) {
                MaterialSymbol("shuffle", size = ToolbarIconSize, fill = isShuffleEnabled)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.queue_shuffle))
            }
            // Planos, como los items sin estado del toolbar del spec, pero CON icono: el glifo se
            // reconoce antes que la palabra y las tres quedan a la misma altura de lectura. El
            // `contentDescription` conserva la frase larga; el label corto es para la vista.
            TextButton(
                onClick = onSaveAsPlaylist,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = toolbarContent),
                modifier = Modifier.semantics { contentDescription = saveDesc }
            ) {
                MaterialSymbol("playlist_add", size = ToolbarIconSize, color = toolbarContent)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.queue_save_short))
            }
            TextButton(
                onClick = onClearQueue,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = toolbarContent),
                modifier = Modifier.semantics { contentDescription = clearDesc }
            ) {
                MaterialSymbol("clear_all", size = ToolbarIconSize, color = toolbarContent)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.queue_clear_short))
            }
        }
    }
}

/**
 * Row individual de la cola - Optimizado con Surface para mejor rendimiento de renderizado
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    // Resaltado del ítem en reproducción, por el helper compartido: un escalón de superficie teñido
    // con el croma de `primaryContainer` —la paleta primary lleva más color que la secondary— sobre
    // la base REAL de estas filas, que aquí es `surfaceContainerHigh` y no el `surface` de la
    // biblioteca. Con el rol a pelo, el mismo resaltado saltaba distinto en cada pantalla según de
    // qué fondo partiera. En un álbum monocromo el tema entero es neutro y el escalón se lee solo
    // por tono: es el límite del enfoque por contenedor cuando no hay color con el que teñir.
    val backgroundColor = songRowBackground(AppColors.surfaceContainerHigh, isCurrentSong)
    // Contenido de la fila activa: el `on-` de ese relleno con contraste garantizado (el porqué vive
    // en `rememberActiveRowContentColor`, que es la definición ÚNICA que comparten las cuatro
    // superficies donde se resalta la canción actual).
    val activeContent = rememberActiveRowContentColor(backgroundColor, isCurrentSong)
    val contentColor = if (isCurrentSong) activeContent else colors.onSurfaceColor
    val variantColor = if (isCurrentSong) activeContent else colors.onSurfaceVariantColor

    // Elevación de arrastre del SPEC, no un número elegido: `ListItemDefaults.elevation()` la
    // resuelve desde `ListTokens.ItemDraggedContainerElevation` (Level4) y su reposo desde
    // `ItemContainerElevation` (Level0). Da la casualidad de que el 8dp que había escrito a mano
    // es justo ese valor — lo que cambia es que ahora sigue al token si Material lo mueve, y que
    // el 0 de reposo también sale de ahí en vez de estar implícito.
    val elevation = ListItemDefaults.elevation()
    val itemElevation = if (isDragging) elevation.draggedElevation else elevation.elevation

    // Usar Surface es más eficiente que Modifier.clip().background()
    // Surface maneja el clipping y el dibujo de fondo en una sola pasada de renderizado cuando es posible.
    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            // La sombra usa la MISMA forma que la Surface, que ahora se interpola al agarrar la
            // fila: si se le pasara la de reposo, el halo se quedaría con las esquinas viejas.
            .then(if (isDragging) Modifier.shadow(itemElevation, shape) else Modifier),
        shape = shape,
        color = backgroundColor,
        contentColor = contentColor,
        tonalElevation = itemElevation,
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
                    color = when {
                        isCurrentSong -> variantColor
                        isDragging -> colors.accentColor
                        else -> colors.onSurfaceVariantColor
                    },
                    size = 20.sp
                )
            }

            // Reutiliza SongItem con la misma apariencia de Library. showActiveBackground=false:
            // el resaltado del item en reproducción lo pinta la Surface de TODA la fila (arriba),
            // no un recuadro de SongItem solo alrededor del contenido (weight 1f) que competía.
            //
            // "Quitar de la cola" va como trailingContent, NO como hermano del SongItem en este
            // Row: el `ListItem` de M3 aplica sus 16dp de padding end DESPUÉS de su trailing, así
            // que un botón colgado fuera quedaba con esos 16dp SUMADOS al aire propio de ambos
            // botones (≈45dp entre el glifo de nube y la X, contra los ~33 del resto de listas) y
            // rematando a 18dp del borde de la tarjeta en vez de a los 16 del spec. Dentro del
            // slot, el espaciado con el indicador de estado y el margen al borde son los mismos
            // que en artista/álbum/lista, que es de donde sale el ritmo de estas filas.
            val removeDesc = stringResource(R.string.queue_remove_song)
            val actionIconSp = with(androidx.compose.ui.platform.LocalDensity.current) {
                QueueRowActionIconSize.toSp()
            }
            SongItem(
                song = song,
                isPlaying = isCurrentSong,
                showActiveBackground = false,
                // El indicador de descarga se dibuja AQUÍ, en el mismo slot que la X, y no dentro de
                // SongItem: así los DOS iconos de acción de la fila salen al tamaño de icono de list
                // item del spec (24 dp) y con el mismo peso óptico. El de SongItem sale a 18 dp —el
                // compacto de la biblioteca— y quedaba más pequeño que la X y descuadrado con ella.
                showStatusIcon = false,
                // Texto en el `on-` del relleno cuando la fila está activa; en las demás, los roles
                // por defecto de SongItem. (El icono de estado de la cola se dibuja abajo, aparte.)
                activeContentColor = if (isCurrentSong) contentColor else Color.Unspecified,
                modifier = Modifier.weight(1f),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Solo la nube tiene estado de descarga; una canción local siempre está.
                        if (song.isCloud) {
                            SongStatusIcon(
                                isDownloaded = song.isDownloaded,
                                isDownloading = false,
                                downloadProgress = null,
                                size = QueueRowActionIconSize,
                                // En la fila activa el glifo va en onSecondary; en las demás, su
                                // color normal (primary para "descargada").
                                contrast = isCurrentSong,
                                contrastColor = contentColor
                            )
                        }
                        // Ancho 28dp (no el 48 por defecto): el `IconButton` estándar centra su
                        // glifo, así que dentro del slot trailing del ListItem (16dp de padding end)
                        // la X quedaba a ~28dp del borde de la tarjeta —metida hacia el contenido—.
                        // Con 28dp de ancho el glifo cae a ~18dp del borde, alineado con la píldora
                        // ⋮ de las otras listas. El área táctil sigue siendo ≥48dp (la refuerza
                        // `minimumInteractiveComponentSize` de M3), solo baja el tamaño VISUAL.
                        IconButton(
                            onClick = onRemove,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier
                                .width(28.dp)
                                .height(44.dp)
                                .semantics { contentDescription = removeDesc }
                        ) {
                            MaterialSymbol("close", color = variantColor, size = actionIconSp)
                        }
                    }
                }
            )
        }
    }
}

@Immutable
private data class QueueColors(
    val backgroundColor: Color,
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
