package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.graphics.shapes.Morph
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import coil3.compose.AsyncImage
import com.qhana.siku.ui.components.nowPlayingArtRequest
import coil3.request.crossfade
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appSlowSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appSlowEffectsSpec
import com.qhana.siku.ui.theme.AppRevealSpec

/*
 * Carátula, cabecera e identidad de la canción en el NowPlaying: la barra superior con el chip
 * de origen, la carátula (con su reveal al cambiar de tema y los gestos), el bloque de
 * título/artista/álbum y el botón de favorito.
 *
 * Separado de [NowPlayingControls] —transporte y barra de acciones— y de [NowPlayingProgress]
 * —barra de progreso y ficha del formato— porque los tres crecieron hasta no caber juntos.
 */

@Composable
internal fun NowPlayingTopBar(
    onBackClick: () -> Unit,
    origin: PlaybackOrigin,
    /**
     * La barra está sobre el DEGRADADO (true) o sobre el fondo sólido (false). No pinta fondo
     * propio: esto solo decide el color de sus iconos.
     */
    onGradient: Boolean,
    onAmbientMode: () -> Unit,
    /** Chip de origen sin etiqueta: en horizontal la barra vive en media pantalla. */
    compactChip: Boolean = false,
    /**
     * En vertical la barra es el `topBar` del Scaffold y pone ella el inset del status bar; en
     * horizontal cuelga dentro del contenido, que ya lo recibió por `innerPadding`.
     */
    applyStatusBarPadding: Boolean = true,
    /** La línea con la que deben alinearse los GLIFOS de los extremos (ver [opticalEdgePadding]). */
    contentKeyline: Dp = NowPlayingConfig.ContentKeyline
) {
    // Sobre el degradado la barra cae en su primera parada, `secondaryContainer`, así que sus iconos
    // van en su `on…`; sobre el fondo sólido (`surfaceContainer`), en `onSurface`.
    val iconColor = if (onGradient) AppColors.onSecondaryContainer else AppColors.onSurface

    // El padding lateral NO es de la fila sino de cada botón, y sale del tamaño de SU glifo: los
    // dos iconos tienen cuerpos distintos (32 y 28), así que a igual margen quedan a distinta
    // distancia visual del borde. Ver [opticalEdgePadding].
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Icon buttons expressive: morph de forma al presionar (IconButtonDefaults.shapes)
        // + háptica, vía ExpressiveActionIcon.
        ExpressiveActionIcon(
            onClick = onBackClick,
            icon = "keyboard_arrow_down",
            description = stringResource(R.string.np_close_desc),
            contentColor = iconColor,
            iconSize = 32.sp,
            modifier = Modifier.padding(start = opticalEdgePadding(32.sp, contentKeyline))
        )

        PlaybackSourceChip(origin = origin, compact = compactChip)

        ExpressiveActionIcon(
            onClick = onAmbientMode,
            icon = "expand_content",
            description = stringResource(R.string.np_ambient_mode_desc),
            contentColor = iconColor,
            iconSize = 28.sp,
            modifier = Modifier.padding(end = opticalEdgePadding(28.sp, contentKeyline))
        )
    }
}

/**
 * Cuánto separar del borde un icon button para que su GLIFO —y no su caja— caiga en [keyline].
 *
 * Un `ExpressiveActionIcon` es un cuadrado táctil de 48dp con el dibujo centrado, así que pegarlo
 * a la keyline mete el glifo `(48 - tamaño) / 2` más adentro: con los cuerpos de esta barra, 24 y
 * 26dp donde el título está a 16. Se leía como si los dos iconos estuvieran corridos hacia el
 * centro, que es exactamente lo que son. Restando esa mitad, el dibujo se alinea con el texto y el
 * área táctil sigue midiendo 48.
 *
 * Es el mismo reparto que hace M3 en sus app bars, donde el icon button va a 4dp del borde para
 * dejar su glifo de 24 en la keyline de 16 — solo que aquí el tamaño del glifo es un parámetro, así
 * que la cuenta se hace en vez de tabularse.
 */
@Composable
private fun opticalEdgePadding(iconSize: TextUnit, keyline: Dp): Dp {
    val glyph = with(LocalDensity.current) { iconSize.toDp() }
    return (keyline - (NowPlayingConfig.ActionIconTouchSize - glyph) / 2).coerceAtLeast(0.dp)
}

/**
 * Chip de ORIGEN del NowPlaying (compartido portrait/landscape): de dónde sale el audio que
 * suena. Pastilla de contenedor SÓLIDO tonal — informativa, no accionable — con sello M3
 * Expressive: el icono va sentado en una forma orgánica de [MaterialShapes] (cookie) que MORFA de
 * forma al cambiar el origen.
 *
 * El FORMATO del archivo ya no vive acá: es otro dato (qué suena, no de dónde) y tiene su
 * propio chip centrado entre los tiempos del [ProgressSlider].
 *
 * Los tres orígenes de [PlaybackOrigin] tienen icono, forma y texto propios: una canción de la
 * nube ya descargada suena offline igual que una local, pero no es lo mismo, y antes ambas se
 * mostraban como "LOCAL".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackSourceChip(
    origin: PlaybackOrigin,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // **Chip RELLENO con el par tabulado al revés**: `onSecondaryContainer` de píldora y
    // `secondaryContainer` de contenido, y el asiento con esos dos intercambiados otra vez. M3
    // garantiza el par en los dos sentidos, así que ninguno de los cuatro colores se calcula.
    //
    // Hasta el 11 sep 2026 las cuatro piezas eran el MISMO color a cuatro tonos, todos derivados del
    // fondo real: la píldora con `tonalLayerContainer`, el asiento con `recessedLayerContainer` —al
    // otro lado del fondo, porque es un recorte y no una capa— y los dos contenidos con
    // `ensureContrast`. Hacía falta porque el chip se apoyaba en el degradado y, en su primera
    // parada, sobre el rol `secondaryContainer` mismo: un rol no se separa de sí mismo, así que
    // ningún rol de esa banda podía verse. Con la barra superior en su propia banda sólida el chip
    // ya sabe qué tiene debajo.
    //
    // **A prueba en device**: si el relleno pesa demasiado —en tema claro `onSecondaryContainer` es
    // casi negro—, la alternativa acordada es el chip con CONTORNO (contenedor transparente, borde
    // `outline`, etiqueta `onSecondaryContainer` y asiento `secondary`/`onSecondary`).
    val chipContainerColor = AppColors.onSecondaryContainer
    val chipContentColor = AppColors.secondaryContainer
    val seatColor = AppColors.secondaryContainer
    val seatContentColor = AppColors.onSecondaryContainer

    // Forma expressive del asiento del icono, una por origen: cookie de 9 lados en local (disco
    // "dentado"), soft burst en descargado, sunny en stream (rayos). El cambio
    // de forma es el acento expressive del chip. toShape() ya es @Composable y memoiza internamente.
    val seatShape = when (origin) {
        PlaybackOrigin.LOCAL -> MaterialShapes.Cookie7Sided
        PlaybackOrigin.DOWNLOADED -> MaterialShapes.Cookie9Sided
        PlaybackOrigin.STREAMING -> MaterialShapes.Sunny
    }.toShape()
    val seatSize = if (compact) 24.dp else 28.dp

    val originIcon = when (origin) {
        PlaybackOrigin.LOCAL -> "sd_card"
        // `offline_pin` y no `cloud_done`: lo que el chip afirma es que la canción está EN EL
        // DISPOSITIVO y suena sin red, no que la nube terminó de sincronizar.
        PlaybackOrigin.DOWNLOADED -> "offline_pin"
        PlaybackOrigin.STREAMING -> "stream"
    }
    val originLabel = when (origin) {
        PlaybackOrigin.LOCAL -> stringResource(R.string.np_chip_local)
        PlaybackOrigin.DOWNLOADED -> stringResource(R.string.np_chip_downloaded)
        PlaybackOrigin.STREAMING -> stringResource(R.string.np_chip_stream)
    }

    AppSurface(
        shape = RoundedCornerShape(50),
        color = chipContainerColor,
        modifier = modifier.height(if (compact) 36.dp else 40.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(seatSize)
                    .clip(seatShape)
                    .background(seatColor)
            ) {
                MaterialSymbol(
                    originIcon,
                    color = seatContentColor,
                    size = if (compact) 13.sp else 15.sp,
                    fill = true
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 10.dp))
            // Sin `.copy(fontWeight = Medium)`: los dos roles `label` YA son Medium por token
            // (`LabelSmallWeight`/`LabelMediumWeight`), así que ese override no cambiaba nada y
            // solo hacía creer que aquí había una decisión de peso.
            Text(
                text = originLabel,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = chipContentColor
            )
        }
    }
}

// ExpressiveApi: `MaterialShapes.Cookie12Sided` del reveal, que pasó a exigir opt-in en alpha24.
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlbumArtSection(
    song: Song,
    variantColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    artSharedKey: Any?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isPlaying: Boolean,
    onTap: () -> Unit,
    /** Ajustes → Reproducción: deslizar para cambiar/cerrar y doble toque para saltar. */
    gesturesEnabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** Salto YA firmado del doble toque (ver [PlayerGestureConfig.SeekStepMs]). */
    onSeekBy: (Long) -> Unit,
    /** Arrastre de cierre compartido con el resto del reproductor; null si no aplica. */
    dismiss: PlayerDismissState?,
    modifier: Modifier = Modifier
) {
    // "Respiración" al pausar: la carátula se encoge sutilmente y su forma morfea de
    // MaterialShapes.Square a MaterialShapes.Circle (estado de reposo); al reproducir recupera
    // plena presencia y vuelve a cuadrado.
    //
    // Token `slowSpatial` (el más blando del scheme) para los dos, sustituyendo a un
    // `spring(LowBouncy, StiffnessLow)` a mano: es el elemento más grande de la pantalla y el que
    // peor lleva ir deprisa. Un solo spec compartido, porque forma y escala son UN gesto.
    val artBreathSpec = appSlowSpatialSpec<Float>()
    val artMorphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 0f else 1f,
        animationSpec = artBreathSpec,
        label = "artMorph"
    )
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.93f,
        animationSpec = artBreathSpec,
        label = "artScale"
    )

    // Arrastre horizontal del gesto de cambiar canción: la carátula sigue al dedo y vuelve al
    // centro al soltar (el cambio lo cuenta el reveal de abajo, no una salida por el borde).
    val gestureScope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    val maxDragPx = with(LocalDensity.current) { PlayerGestureConfig.SwipeSongMaxDrag.toPx() }

    // Destello del doble toque. El disparo es un CONTADOR y no el lado tocado: dos saltos
    // seguidos hacia el mismo lado no cambiarían un estado booleano y el segundo se quedaría
    // sin destello.
    var flashTick by remember { mutableIntStateOf(0) }
    var flashForward by remember { mutableStateOf(true) }
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flashVisible = true
        delay(PlayerGestureConfig.SeekFlashMs.toLong())
        flashVisible = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val sharedElementModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null && artSharedKey != null) {
                with(sharedTransitionScope) {
                    // Visibilidad GESTIONADA POR NOSOTROS, igual que en el otro extremo del par (ver el
                    // comentario largo en `MiniPlayer`): del scope se lee solo la INTENCIÓN
                    // (`targetState`), nunca su duración. Atar la punta al scope hacía que el morph
                    // muriera cuando esa transición terminaba —y la del player y la de la píldora ni
                    // duran lo mismo ni arrancan a la vez, porque viven en sistemas distintos—, así que
                    // la portada acababa apareciendo quieta en su destino.
                    val artVisible =
                        animatedVisibilityScope.transition.targetState == EnterExitState.Visible
                    Modifier.sharedElementWithCallerManagedVisibility(
                        // La key la ELIGE quien abrió el reproductor: la constante de la píldora o
                        // la de la fila tocada (ver `artSharedKey` en NowPlayingRoute). Es lo que
                        // decide de cuál de las dos puntas —ambas declaradas de antes— sale la
                        // portada.
                        sharedContentState = rememberSharedContentState(key = artSharedKey),
                        visible = artVisible,
                        // El MISMO spring que la superficie que la lleva (píldora o fila): la portada
                        // aterriza CON el contenedor, no 150 ms después. Cuando esto llevaba el tween
                        // de 500 que entonces era `AppBoundsTransform`, el contenedor asentaba a ~350
                        // y la portada seguía flotando sola el resto — y esa cola era además lo que
                        // mantenía activo el `SharedTransitionScope` (overlay por frame) ya con todo
                        // quieto. Desde el 8 sep 2026 los dos leen el MISMO spec del `MotionScheme`,
                        // así que esta llamada explícita ya no separa nada; se queda porque dice a
                        // qué se está atando la portada.
                        boundsTransform = AppContainerBoundsTransform,
                        // Por encima de las dos superficies del container transform; ver
                        // [CONTAINER_ART_OVERLAY_Z]. Vale igual para los dos orígenes: desde la
                        // píldora y desde una fila la portada viaja ANIDADA dentro de una superficie
                        // que morfa, así que en ninguno de los dos casos puede ir por debajo.
                        zIndexInOverlay = CONTAINER_ART_OVERLAY_Z
                    )
                }
            } else Modifier

        // Animación del CAMBIO DE CANCIÓN — SHAPE REVEAL (M3 Expressive, elegida tras
        // probar variantes): la carátula nueva se revela desde el centro con una VENTANA
        // MaterialShapes (cookie) que crece hasta cubrir el cuadrado; la imagen queda
        // estática (escala inversa) — solo crece la ventana. La carátula MOSTRADA va por
        // detrás del estado real para poder coreografiar el intercambio.
        var displayedArt by remember { mutableStateOf(song.id to song.albumArtUriString) }
        var incomingArt by remember { mutableStateOf<Pair<String, String?>?>(null) }
        val reveal = remember { Animatable(0f) }

        // ¿El reproductor está ABIERTO, quieto y a la vista? Mismo gate que el reveal del transporte
        // en [NowPlayingLayouts] — ver [playerRevealEnabled] para las tres condiciones.
        val playerSettled = playerRevealEnabled(animatedVisibilityScope)

        // Izado: dentro del `LaunchedEffect` ya no hay composición donde leer el tema.
        //
        // Spec propio y no un token del scheme: ver el kdoc de [AppRevealSpec].
        val artRevealSpec = AppRevealSpec
        LaunchedEffect(song.id, song.albumArtUriString) {
            val target = song.id to song.albumArtUriString
            if (displayedArt == target) return@LaunchedEffect
            // El reveal cookie SOLO tiene sentido con el reproductor ya abierto: es la coreografía
            // de "cambió la canción bajo tus ojos" (siguiente/anterior/notificación). Al ABRIR desde
            // la lista nunca viste la carátula anterior en grande, así que no hay nada que revelar —
            // y correr el reveal (doble composición + clip de Path por frame) ENCIMA del slide de
            // apertura es lo que apilaba dos animaciones caras y producía el tartamudeo. Aquí se
            // salta el reveal y se muestra la carátula destino directamente.
            if (!playerSettled) {
                displayedArt = target
                return@LaunchedEffect
            }
            incomingArt = target
            reveal.snapTo(0f)
            reveal.animateTo(1f, artRevealSpec)
            displayedArt = target
            incomingArt = null
        }

        val revealShape = MaterialShapes.Cookie12Sided.toShape()
        val albumArtOptionsLabel = stringResource(R.string.np_album_art_options)
        AppSurface(
            modifier = Modifier
                .aspectRatio(1f)
                .then(sharedElementModifier)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                    translationX = dragOffsetX.value
                }
                .albumArtSwipe(
                    enabled = gesturesEnabled,
                    offsetX = dragOffsetX,
                    dismiss = dismiss,
                    scope = gestureScope,
                    maxDragPx = maxDragPx,
                    onNext = onNext,
                    onPrevious = onPrevious
                )
                .albumArtTaps(
                    gesturesEnabled = gesturesEnabled,
                    onSeek = onSeekBy,
                    onFlash = { forward ->
                        flashForward = forward
                        flashTick++
                    },
                    onLongPress = onTap
                )
                // El long-press abre el selector de color y hasta ahora era invisible para un
                // lector de pantalla: sin acción semántica declarada, el gesto no existe.
                .semantics {
                    onLongClick(label = albumArtOptionsLabel) {
                        onTap()
                        true
                    }
                },
            shape = AlbumArtMorphShape(artMorphProgress),
            color = AppColors.surfaceContainerHighest
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Carátula base (la mostrada).
                NowPlayingArtImage(
                    artUri = displayedArt.second,
                    albumName = song.album,
                    variantColor = variantColor
                )

                // Capa entrante del reveal: ventana cookie creciente + escala
                // inversa en la imagen para que solo se mueva la ventana.
                val inc = incomingArt
                if (inc != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                val s = 0.08f + reveal.value * 1.55f
                                scaleX = s
                                scaleY = s
                                clip = true
                                shape = revealShape
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val s = 0.08f + reveal.value * 1.55f
                                    scaleX = 1f / s
                                    scaleY = 1f / s
                                }
                        ) {
                            NowPlayingArtImage(
                                artUri = inc.second,
                                albumName = song.album,
                                variantColor = variantColor
                            )
                        }
                    }
                }

                // Destello del salto por doble toque, del lado que se tocó. Va DENTRO del
                // recorte de la carátula para que el círculo no se salga de la forma cuando
                // ésta morfea a redonda (en pausa).
                // Asimetría preservada con tokens en vez de con dos duraciones a mano: `fastEffects`
                // es el más rápido del scheme (entra ya visible bajo el dedo) y `slowEffects` el más
                // lento sin rebote (se va sin pedir atención).
                androidx.compose.animation.AnimatedVisibility(
                    visible = flashVisible,
                    enter = fadeIn(appFastEffectsSpec()),
                    exit = fadeOut(appSlowEffectsSpec()),
                    modifier = Modifier
                        .align(if (flashForward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = SeekFlashSidePadding)
                ) {
                    AppSurface(
                        shape = CircleShape,
                        color = AppColors.scrim.copy(alpha = SEEK_FLASH_SCRIM_ALPHA),
                        modifier = Modifier.size(SeekFlashCircleSize)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            MaterialSymbol(
                                if (flashForward) "forward_10" else "replay_10",
                                size = SeekFlashIconSize,
                                color = Color.White,
                                fill = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingArtImage(
    artUri: String?,
    albumName: String,
    variantColor: Color
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (artUri != null) {
            val context = LocalContext.current
            // Sin clave propia: la de Coil (URI + tamaño) reúne solo lo que de verdad produce el
            // mismo bitmap. La clave por CANCIÓN que había aquí guardaba una copia de 800×800
            // (2,5 MB en ARGB_8888) por cada pista de un mismo álbum, y encima no compartía nada
            // con el MiniPlayer, que usaba otro prefijo y otro tamaño — el "reuso" que prometía no
            // existía. Ver [AlbumArt] para el porqué completo.
            // La petición sale de UNA función compartida con el precalentamiento: repetir aquí sus
            // parámetros es lo que hizo que las dos rutas divergieran en el `scale` y decodificaran
            // el mismo JPEG dos veces. Ver [nowPlayingArtRequest].
            val request = remember(artUri) { nowPlayingArtRequest(context, artUri) }
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.album_art_desc, albumName),
                contentScale = ContentScale.Crop,
                // Sonda (solo debug): cuándo aterriza el bitmap grande respecto del tap.
                onSuccess = { com.qhana.siku.data.util.JankProbe.mark { "carátula 800px lista (${it.result.dataSource})" } },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MaterialSymbol("music_note", size = 80.sp, color = variantColor)
        }
    }
}

/** Aire lateral del área pulsable de artista/álbum, acotado por el margen del contenido. */
private val TextChipPadding = 8.dp

/** Vertical aparte y menor: de más, las tres líneas de la ficha dejan de leerse como un bloque. */
private val TextChipVerticalPadding = 4.dp

private val TextChipCorner = 8.dp

/**
 * Una línea de texto que se puede pulsar y que además LO PARECE al pulsarla: el `clickable` se
 * queda pegado a las letras si el padding no está dentro de él, y entonces el ripple sale del
 * mismo tamaño que el texto, sin un milímetro de aire.
 *
 * El detalle que lo hace no trivial es que el aire no puede correr el texto: artista y álbum se
 * alinean con el título, que no es pulsable y por tanto no lleva padding. Así que el inset se
 * compensa con un `offset` NEGATIVO del mismo valor — el contenedor empieza antes del margen y el
 * texto acaba justo donde estaba. Lo que crece es la superficie que reacciona, no el bloque.
 *
 * El desplazamiento se come parte del margen lateral de la pantalla ([NowPlayingConfig.ContentKeyline]),
 * que es de donde sale el tope: pedir más aire que margen sacaría el ripple fuera de la pantalla.
 *
 * Es `@Composable` porque la sobrecarga simple de `clickable` resuelve `LocalIndication` y lo
 * necesita; devolver un `Modifier` desde una función composable es válido y es el patrón habitual.
 */
@Composable
private fun Modifier.clickableTextChip(onClick: () -> Unit): Modifier = this
    .offset(x = -TextChipPadding)
    .clip(RoundedCornerShape(TextChipCorner))
    .clickable(onClick = onClick)
    .padding(horizontal = TextChipPadding, vertical = TextChipVerticalPadding)

@Composable
internal fun SongInfoSection(
    song: Song,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String, String?) -> Unit
) {
    // El marquee del título es una animación INFINITA, así que se apaga con el reproductor guardado:
    // el subárbol es persistente (ver [com.qhana.siku.ui.LocalPlayerOnScreen]) y un título largo
    // desplazándose donde no se ve produce un frame por vsync para nadie. Al reaparecer arranca desde
    // el principio, que es como se lee mejor de todas formas.
    val titleMarquee = if (LocalPlayerOnScreen.current) {
        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
    } else Modifier
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 3 líneas (título / artista / álbum): artista y álbum son CLICKEABLES por separado
        // y navegan a sus pantallas de detalle.
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            // JERARQUÍA POR PESO, y no solo por tamaño y color. El título es el rol `*Emphasized`
            // de Expressive (24sp Medium) y las dos líneas de apoyo son roles `body`, que nacen en
            // Regular: 500 → 400 → 400.
            //
            // Hasta el 19 ago 2026 el apoyo usaba roles `title` (`titleMedium`/`titleSmall`), que
            // en M3 ya vienen en Medium, así que **las tres líneas eran peso 500** y el bloque no
            // tenía un solo salto de peso — la jerarquía se sostenía únicamente por tamaño y color.
            // Se notaba comparándolo con el MiniPlayer, donde el mismo dato sí destaca porque allí
            // el par es `titleSmallEmphasized` (Bold 700) sobre `bodySmall` (Regular 400).
            //
            // Subir el título NO era la salida: la escala de M3 sube el énfasis a Bold solo de
            // 16sp hacia abajo (`titleMedium`/`titleSmall`/`labelLarge`); de `titleLarge` hacia
            // arriba el `*Emphasized` se queda en Medium, porque a ese cuerpo la masa óptica ya la
            // pone el tamaño. La palanca es bajar el apoyo, no engordar el titular.
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                // Los TRES textos comparten rol (`onSecondaryContainer`) y la jerarquía la lleva la
                // ESCALA TIPOGRÁFICA: headline enfatizado / bodyLarge / bodyMedium.
                //
                // Dos cosas que ya se probaron aquí y fallaron en device, las dos por el mismo
                // motivo —dar por hecho un orden entre roles que M3 no promete—:
                //
                //  1. `on*FixedVariant`: los `*Fixed` son INVARIANTES al tema, así que
                //     `onPrimaryFixedVariant` es ≈T30 (oscuro) también en tema oscuro —está
                //     calculado contra `primaryFixedDim`, que es claro— y suelto sobre el degradado
                //     oscuro medía ~1,4:1. Ilegible.
                //  2. Título en `onPrimaryContainer` y subtítulos en `onSecondaryContainer`: entre
                //     esos dos no hay orden garantizado, y en una paleta cian el secundario salió
                //     MÁS claro que el primario — el título pesando menos que el artista.
                //
                // El único par de contenido que M3 sí ordena es `onSurface`/`onSurfaceVariant`, y es
                // neutro. Mientras el criterio sea "tintado y sin derivar", la jerarquía tiene que
                // salir del tipo, no del color.
                color = AppColors.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleMarquee
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickableTextChip { onArtistClick(song.artist) }
            )
            if (song.album.isNotBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickableTextChip { onAlbumClick(song.album, song.albumArtist.orEmpty()) }
                )
            }
        }

        // Favorito: PÍLDORA VERTICAL (misma familia que los overflow de las listas) con
        // rebote del corazón al marcar/desmarcar.
        FavoriteHeartPill(
            isFavorite = isFavorite,
            onToggle = onToggleFavorite
        )
    }
}

/**
 * Botón de favorito en PÍLDORA VERTICAL: contenedor tonal translúcido inactivo que se
 * rellena con el acento del álbum al activarse, y el corazón da un pequeño REBOTE
 * (snap 0.7 → spring con overshoot) en cada cambio de estado.
 *
 * Tamaño de spec LARGE-narrow ([FavoriteHeartWidth]×[FavoriteHeartHeight]) con icono de 32.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoriteHeartPill(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val heartScale = remember { Animatable(1f) }
    // TONAL toggle de M3 (tokens): activo = secondary/onSecondary; inactivo = secondaryContainer/
    // onSecondaryContainer. El activo NO usa primary — ese es el color del PLAY, por eso antes el
    // corazón activo se veía idéntico al play.
    val activeContainer = AppColors.secondary
    val onActive = AppColors.onSecondary
    val tonalContainer = AppColors.secondaryContainer
    val onTonalContainer = AppColors.onSecondaryContainer
    val heartColorSpec = appEffectsSpec<Color>()
    val container by animateColorAsState(
        targetValue = if (isFavorite) activeContainer else tonalContainer,
        animationSpec = heartColorSpec,
        label = "heartContainer"
    )
    val heartColor by animateColorAsState(
        targetValue = if (isFavorite) onActive else onTonalContainer,
        animationSpec = heartColorSpec,
        label = "heartContent"
    )
    val favoriteDesc = if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)
    // El rebote del latido: `fastSpatial` es el token con más rebote del scheme (dampingRatio 0.6),
    // que es lo que pedía el `DampingRatioMediumBouncy` a mano que había aquí.
    val heartPopSpec = appFastSpatialSpec<Float>()
    // FilledIconToggleButton REAL (M3 Expressive: shape-morph presionado/checked, como los
    // toggles de la floating toolbar) en vez de Surface artesanal. Los colores animados del
    // acento se pasan idénticos para ambos estados: la transición de color sigue siendo nuestra
    // (token `defaultEffects`), el componente aporta ripple/formas/semántica de toggle.
    // Morph INVERTIDO a petición del usuario: squircle en reposo → redondo (píldora) activo.
    FilledIconToggleButton(
        checked = isFavorite,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
            scope.launch {
                heartScale.snapTo(0.7f)
                heartScale.animateTo(1f, heartPopSpec)
            }
        },
        shapes = IconButtonDefaults.toggleableShapes(
            shape = RoundedCornerShape(percent = 30),
            pressedShape = RoundedCornerShape(percent = 20),
            checkedShape = RoundedCornerShape(percent = 50)
        ),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = container,
            contentColor = heartColor,
            checkedContainerColor = container,
            checkedContentColor = heartColor
        ),
        modifier = modifier
            .width(FavoriteHeartWidth)
            .height(FavoriteHeartHeight)
            .semantics {
                contentDescription = favoriteDesc
            }
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = heartScale.value
                scaleY = heartScale.value
            }
        ) {
            MaterialSymbol("favorite", fill = isFavorite, color = heartColor, size = 32.sp)
        }
    }
}

// --- Botón de favorito (ver [FavoriteHeartPill]) ---

/** Ancho del icon button LARGE-narrow del spec (64×96 con glifo de 32). */
private val FavoriteHeartWidth = 64.dp

/**
 * Alto del corazón: el del contenedor Large, **tal cual lo tabula el spec**.
 *
 * **Se probó bajarlo a 72 el 24 ago 2026 y el usuario lo descartó en device — NO reintroducirlo.**
 * El argumento a favor era de layout y sigue siendo cierto: es este botón —y no el texto, que pide
 * ~70— quien fija el alto de toda la región de info, y en esta columna cada dp sale de la CARÁTULA
 * (`weight(1f)` y cuadrada), así que eran 24dp de lado de portada. Pero a 72 el botón deja de leerse
 * como la PÍLDORA VERTICAL que es (64×96 es 1:1,5, la misma proporción que el play en pausa; 64×72
 * queda en 1:1,125, o sea casi un cuadrado redondeado) y esa forma es lo que lo distingue del resto
 * de la pantalla. Los dp de la portada se buscan en otro lado: ver la barra superior y la fila de
 * chips de tiempo en `docs/NOWPLAYING.md`.
 */
private val FavoriteHeartHeight = 96.dp

// --- Destello del doble toque de salto (ver [AlbumArtSection]) ---

/**
 * Círculo del destello. Grande a propósito: no es un control, es una confirmación que hay que
 * leer de reojo mientras el dedo sigue encima de la carátula.
 */
private val SeekFlashCircleSize = 88.dp
private val SeekFlashIconSize = 36.sp

/** Separación del borde de la carátula, para que el círculo no toque el canto redondeado. */
private val SeekFlashSidePadding = 12.dp

/**
 * Velo del destello sobre la carátula. `scrim` del esquema —el mismo rol que M3 usa para
 * oscurecer contenido bajo una capa— a media opacidad: tapa lo justo para que el glifo blanco
 * se lea sobre CUALQUIER carátula, incluida una blanca.
 */
private const val SEEK_FLASH_SCRIM_ALPHA = 0.45f

// El chip de origen ya no lleva sombra ni borde: su relleno se DERIVA del fondo (ver
// [PlaybackSourceChip]), así que se separa por color y no por relieve.

