package com.qhana.siku.ui.screens

import androidx.compose.animation.*
// Explícito: el wildcard de animation trae la Animatable de COLOR; necesitamos la de Float.
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarAction
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

    // --- Extracted Section Composables ---

/**
 * Shape del botón de play: morph continuo píldora ↔ [MaterialShapes.Cookie9Sided].
 * A progress 0 delega en la píldora exacta (percent 50, idéntica al estado en pausa
 * original); con progress > 0 interpola con [Morph] entre una píldora real construida
 * al aspect actual del botón y la cookie escalada a los bounds. Se reconstruye por
 * frame porque el tamaño anima a la vez que la forma — el costo del matching de
 * features de Morph es despreciable para un botón.
 */
internal class PlayButtonMorphShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // El spring rebota fuera de [0,1]; Morph solo acepta ese rango.
        val p = progress.coerceIn(0f, 1f)
        if (p == 0f) {
            return RoundedCornerShape(percent = 50).createOutline(size, layoutDirection, density)
        }
        // Píldora en coordenadas reales del botón (rectángulo con radio = lado menor / 2).
        val pill = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(size.minDimension / 2f),
            centerX = size.width / 2f,
            centerY = size.height / 2f
        )
        // Cookie normalizada (unit square) escalada a los bounds del botón.
        val cookie = MaterialShapes.Cookie9Sided.transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        return Outline.Generic(Morph(pill, cookie).toPath(p))
    }
}

/**
 * Rotación efectiva del giro de la cookie del play. Con el morph completo (progress 1)
 * gira con el ángulo pleno; durante el morph se reduce al resto módulo 40° (la cookie
 * de 9 lados es idéntica cada 360/9 = 40°) escalado por el progreso, así el
 * "desenrosque" al volver a píldora nunca supera 40° y el recorte del ángulo es
 * invisible por simetría.
 */
internal fun cookieSpinDegrees(angle: Float, progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p >= 1f) angle else (angle % 40f) * p
}

@Composable
internal fun NowPlayingTopBar(
    onBackClick: () -> Unit,
    contentColor: Color,
    accentColor: Color,
    hazeState: HazeState,
    glassTint: Color,
    origin: PlaybackOrigin,
    onAmbientMode: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Icon buttons expressive: morph de forma al presionar (IconButtonDefaults.shapes)
        // + háptica, vía ExpressiveActionIcon.
        ExpressiveActionIcon(
            onClick = onBackClick,
            icon = "keyboard_arrow_down",
            description = stringResource(R.string.np_close_desc),
            contentColor = contentColor,
            iconSize = 32.sp
        )

        PlaybackSourceChip(
            origin = origin,
            contentColor = contentColor,
            accentColor = accentColor,
            hazeState = hazeState,
            glassTint = glassTint
        )

        ExpressiveActionIcon(
            onClick = onAmbientMode,
            icon = "expand_content",
            description = stringResource(R.string.np_ambient_mode_desc),
            contentColor = contentColor,
            iconSize = 28.sp
        )
    }
}

/**
 * Chip de ORIGEN del NowPlaying (compartido portrait/landscape): de dónde sale el audio que
 * suena. Pastilla de VIDRIO ESMERILADO ([GlassSurface]) — informativa, no accionable — con
 * sello M3 Expressive: el icono va sentado en una forma orgánica de [MaterialShapes]
 * (cookie) del color del contenido, que además MORFA de forma al cambiar el origen.
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
    contentColor: Color,
    accentColor: Color,
    hazeState: HazeState,
    glassTint: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // OUTLINED: fill `secondaryContainer` (armónico con el gradiente) + BORDE `outline` que define el
    // chip aunque el fill coincida con el fondo → se ve sin desentonar (tertiary) ni ser agresivo
    // (inverseSurface). Content = onSecondaryContainer.
    val chipContentColor = MaterialTheme.colorScheme.onSecondaryContainer

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
        PlaybackOrigin.DOWNLOADED -> "cloud_done"
        PlaybackOrigin.STREAMING -> "stream"
    }
    val originLabel = when (origin) {
        PlaybackOrigin.LOCAL -> stringResource(R.string.np_chip_local)
        PlaybackOrigin.DOWNLOADED -> stringResource(R.string.np_chip_downloaded)
        PlaybackOrigin.STREAMING -> stringResource(R.string.np_chip_stream)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    // Asiento con el ACENTO del álbum (mismo color que el botón de play):
                    // ata el chip al tema de la carátula en vez del blanco/negro neutro.
                    .background(accentColor)
            ) {
                MaterialSymbol(
                    originIcon,
                    color = onContainerColor(accentColor),
                    size = if (compact) 13.sp else 15.sp,
                    fill = true
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 10.dp))
            Text(
                text = originLabel,
                style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
                    .copy(fontWeight = FontWeight.Medium),
                color = chipContentColor
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AlbumArtSection(
    song: Song,
    variantColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isPlaying: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // "Respiración" al pausar: la carátula se encoge sutilmente y su forma morfea de
    // MaterialShapes.Square a MaterialShapes.Circle (estado de reposo); al reproducir recupera
    // plena presencia y vuelve a cuadrado. Springs suaves.
    val artMorphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "artMorph"
    )
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.93f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "artScale"
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val sharedElementModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(key = "album_art_${song.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
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

        LaunchedEffect(song.id, song.albumArtUriString) {
            val target = song.id to song.albumArtUriString
            if (displayedArt == target) return@LaunchedEffect
            incomingArt = target
            reveal.snapTo(0f)
            reveal.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
            displayedArt = target
            incomingArt = null
        }

        val revealShape = MaterialShapes.Cookie12Sided.toShape()
        Surface(
            modifier = Modifier
                .aspectRatio(1f)
                .then(sharedElementModifier)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onTap() }) },
            shape = AlbumArtMorphShape(artMorphProgress),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Carátula base (la mostrada).
                NowPlayingArtImage(
                    artId = displayedArt.first,
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
                                artId = inc.first,
                                artUri = inc.second,
                                albumName = song.album,
                                variantColor = variantColor
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
    artId: String,
    artUri: String?,
    albumName: String,
    variantColor: Color
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (artUri != null) {
            val context = LocalContext.current
            // memoryCacheKey/diskCacheKey por song.id: reusa bitmap decodificado entre
            // navegaciones Library ↔ NowPlaying y con el MiniPlayer (misma canción).
            val request = remember(artId, artUri) {
                ImageRequest.Builder(context)
                    .data(artUri)
                    .crossfade(false)
                    .size(800)
                    .memoryCacheKey("song_art_$artId")
                    .diskCacheKey("song_art_$artId")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.album_art_desc, albumName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MaterialSymbol("music_note", size = 80.sp, color = variantColor)
        }
    }
}

@Composable
internal fun SongInfoSection(
    song: Song,
    contentColor: Color,
    variantColor: Color,
    isFavorite: Boolean,
    playButtonColor: Color,
    onToggleFavorite: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 3 líneas (título / artista / álbum): artista y álbum son CLICKEABLES por separado
        // y navegan a sus pantallas de detalle.
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.titleMedium,
                color = variantColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onArtistClick(song.artist) }
                    .padding(vertical = 2.dp)
            )
            if (song.album.isNotBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.titleSmall,
                    color = variantColor.copy(alpha = variantColor.alpha * 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAlbumClick(song.album) }
                        .padding(vertical = 2.dp)
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
 * Tamaño de spec LARGE-narrow: 64×96 con icono de 32.
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
    val activeContainer = MaterialTheme.colorScheme.secondary
    val onActive = MaterialTheme.colorScheme.onSecondary
    val tonalContainer = MaterialTheme.colorScheme.secondaryContainer
    val onTonalContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val container by animateColorAsState(
        targetValue = if (isFavorite) activeContainer else tonalContainer,
        animationSpec = tween(durationMillis = 250),
        label = "heartContainer"
    )
    val heartColor by animateColorAsState(
        targetValue = if (isFavorite) onActive else onTonalContainer,
        animationSpec = tween(durationMillis = 250),
        label = "heartContent"
    )
    val favoriteDesc = if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)
    // FilledIconToggleButton REAL (M3 Expressive: shape-morph presionado/checked, como los
    // toggles de la floating toolbar) en vez de Surface artesanal. Los colores animados del
    // acento se pasan idénticos para ambos estados: la transición de color sigue siendo
    // nuestra (tween 250), el componente aporta ripple/formas/semántica de toggle.
    // Morph INVERTIDO a petición del usuario: squircle en reposo → redondo (píldora) activo.
    FilledIconToggleButton(
        checked = isFavorite,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
            scope.launch {
                heartScale.snapTo(0.7f)
                heartScale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
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
            .width(64.dp)
            .height(96.dp)
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

/**
 * SHAPE REVEAL de acento COMPARTIDO para controles + action bar: al cambiar de canción se
 * re-renderiza TODO el bloque con el acento nuevo dentro de UNA ventana cookie que crece
 * desde el centro (misma coreografía que el reveal de la carátula) — un solo efecto para
 * ambos componentes. [accent]/[accentContent] deben ser los colores TARGET sin animar:
 * la ventana ES la transición (un tween de color por debajo la desluciría).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccentRevealGroup(
    songId: String,
    accent: Color,
    accentContent: Color,
    modifier: Modifier = Modifier,
    content: @Composable (accent: Color, accentContent: Color) -> Unit
) {
    val currentAccent by rememberUpdatedState(accent)
    val currentAccentContent by rememberUpdatedState(accentContent)
    var displayed by remember { mutableStateOf(Triple(songId, accent, accentContent)) }
    var revealing by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }

    // Acento que cambia SIN cambiar de canción (histograma que llega tarde u override
    // manual del color): actualizar el snapshot directo, sin reveal.
    LaunchedEffect(accent, accentContent) {
        if (!revealing && displayed.first == songId) {
            displayed = Triple(songId, accent, accentContent)
        }
    }

    LaunchedEffect(songId) {
        if (displayed.first == songId) return@LaunchedEffect
        revealing = true
        reveal.snapTo(0f)
        reveal.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        )
        displayed = Triple(songId, currentAccent, currentAccentContent)
        revealing = false
    }

    val revealShape = MaterialShapes.Cookie12Sided.toShape()
    Box(modifier = modifier) {
        // Capa base: el bloque con el acento CONGELADO de la canción anterior.
        content(displayed.second, displayed.third)
        if (revealing) {
            // Ventana cookie creciente + escala inversa (el contenido queda estático,
            // solo crece la ventana). 1.7 de rango: el bloque es apaisado y la cookie
            // estirada necesita algo más que en el cuadrado de la carátula para cubrir
            // las esquinas.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val s = 0.08f + reveal.value * 1.7f
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
                            val s = 0.08f + reveal.value * 1.7f
                            scaleX = 1f / s
                            scaleY = 1f / s
                        }
                ) {
                    content(currentAccent, currentAccentContent)
                }
            }
        }
    }
}

/**
 * Estado del botón de play (progreso del morph píldora↔cookie + ángulo del giro continuo),
 * HOISTED fuera de [AccentRevealGroup]: el reveal compone [PlaybackControls] DOS veces
 * (capa congelada + capa entrante) y cada copia creaba su propio rememberInfiniteTransition,
 * así que la cookie entrante arrancaba en 0° mientras la congelada llevaba su giro
 * acumulado — se veían DOS botones de play superpuestos con ángulos distintos al cambiar
 * de canción. Compartiendo el mismo estado ambas capas dibujan el botón idéntico y el
 * solape es invisible.
 */
internal class PlayButtonSpinState(
    val morphProgress: State<Float>,
    val angle: State<Float>
)

@Composable
internal fun rememberPlayButtonSpin(isPlayingOrBuffering: Boolean): PlayButtonSpinState {
    // Morph continuo píldora (pausa) ↔ Cookie9Sided (reproduciendo); mismo spring que
    // las dimensiones del botón en PlaybackControls.
    val morphProgress = animateFloatAsState(
        targetValue = if (isPlayingOrBuffering) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "playShapeMorph"
    )
    // La transición infinita solo existe mientras el botón no es píldora pura en reposo.
    // derivedStateOf: el caller (layout) no recompone por frame durante el morph, solo
    // cuando el booleano realmente cambia.
    val playing = rememberUpdatedState(isPlayingOrBuffering)
    val spinning by remember {
        derivedStateOf { playing.value || morphProgress.value > 0f }
    }
    val angle: State<Float> = if (spinning) {
        rememberInfiniteTransition(label = "cookieSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
            label = "cookieAngle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    return PlayButtonSpinState(morphProgress, angle)
}

/**
 * Reveal del propio glifo: descubre el contenido (icono RELLENO activo) mediante un clip circular
 * que crece desde el centro según [fraction] (0→1). Va SOBRE una copia del icono outlined inactivo,
 * así el glifo se "rellena desde el centro" (misma coreografía que el reveal de cambio de canción,
 * pero acotada AL ICONO, no al botón). Radio = círculo inscrito del icono. [revealPath] se reutiliza
 * (remember) para no allocar un Path por frame.
 */
private fun Modifier.iconFillReveal(fraction: Float, revealPath: Path): Modifier =
    this.drawWithContent {
        // Radio hasta la ESQUINA (media diagonal), no el círculo inscrito: así el glifo queda
        // TOTALMENTE descubierto en fraction=1 (con minDimension/2 las esquinas no se revelaban).
        val hw = size.width / 2f
        val hh = size.height / 2f
        val r = kotlin.math.sqrt((hw * hw + hh * hh).toDouble()).toFloat() * fraction
        if (r <= 0f) return@drawWithContent
        revealPath.reset()
        revealPath.addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
        clipPath(revealPath) { this@drawWithContent.drawContent() }
    }

/**
 * Toggle del floating toolbar: CÍRCULO fijo (sin morph, spec). La animación de estado es UN reveal
 * que ABRE DESDE EL CENTRO: el contenedor circular ([checkedBg]) crece y, en sync (mismo fraction),
 * el glifo relleno/activo se descubre sobre el outlined inactivo ([iconFillReveal]). Ambos terminan
 * a la vez. Contenedores del botón transparentes: el relleno lo pinta esta función.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolbarToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    icon: String,
    description: String,
    checkedBg: Color,
    inactiveContent: Color,
    // Color del glifo activo (el par de contenido de [checkedBg]). Se pasa desde la barra para
    // que salga de la MISMA paleta (inversa del toolbar), no de un rol global fijo.
    activeContent: Color,
    isLoading: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "toolbarToggleReveal"
    )
    val revealPath = remember { Path() }
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
        },
        shapes = IconButtonDefaults.toggleableShapes(
            shape = CircleShape,
            pressedShape = CircleShape,
            checkedShape = CircleShape
        ),
        // El botón no pinta relleno propio (haría snap): el contenedor lo dibuja el drawBehind
        // (fade con [fraction]) y el reveal vive en el icono.
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = inactiveContent,
            checkedContainerColor = Color.Transparent,
            checkedContentColor = activeContent
        ),
        // 48dp = mismo diámetro que los botones de acción del toolbar (ExpressiveActionIcon).
        modifier = Modifier
            .size(48.dp)
            .drawBehind {
                // Contenedor circular y reveal del icono ABREN JUNTOS desde el centro (mismo
                // fraction): el círculo CRECE en vez de solo aparecer, así termina a la vez que el
                // icono. Antes se dibujaba a tamaño full con alpha → "acababa" antes que el icono.
                if (fraction > 0f) {
                    drawCircle(color = checkedBg, radius = size.minDimension / 2f * fraction, center = center)
                }
            }
            .semantics { contentDescription = description }
    ) {
        if (isLoading) {
            LoadingIndicator(modifier = Modifier.size(22.dp), color = if (checked) activeContent else inactiveContent)
        } else {
            Box(contentAlignment = Alignment.Center) {
                // Base: glifo OUTLINED inactivo (siempre por debajo).
                MaterialSymbol(icon, fill = false, color = inactiveContent)
                // Reveal: glifo activo RELLENO descubierto desde el centro del icono (outlined→filled,
                // igual que todos los toggles del toolbar).
                MaterialSymbol(
                    icon,
                    fill = true,
                    color = activeContent,
                    modifier = Modifier.iconFillReveal(fraction, revealPath)
                )
            }
        }
    }
}

/**
 * Toggle icon button Material 3 — variante STANDARD del spec (fila D del matrix): SIN contenedor ni
 * borde, es SOLO el icono. Más liviano que Filled/Tonal/Outlined, así el play (Filled/primary) y
 * prev/next (Tonal/secondaryContainer) PESAN más en la jerarquía (el inverseSurface del Outlined
 * competía con el play).
 *  - Inactivo (D2): icono `onSurfaceVariant` ([inactiveColor]), sin fondo.
 *  - Activo (D3): icono en el acento del álbum ([accentColor] = playButtonColor), sin fondo.
 * Se usa el acento CRUDO (no `MaterialTheme.primary`): con seeds grises, Fidelity lleva `primary` a
 * un neutro claro casi idéntico a `onSurfaceVariant` y el toggle "no cambiaba de color". El acento
 * crudo difiere en luminancia. El cambio activo↔inactivo se anima con spring. Feedback háptico.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveToggleIcon(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    icon: String,
    description: String,
    accentColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    fillWhenChecked: Boolean = true,
    iconSize: TextUnit = 24.sp
) {
    val haptic = LocalHapticFeedback.current
    // Activo = acento del álbum, SIN contenedor (Standard D3), pero pasado por ensureContrast contra
    // el `surface` para que SIEMPRE sea PROMINENTE (≥3:1) — si no, un acento claro (p.ej. el lila de
    // Orion) en tema claro pesa MENOS que el `onSurfaceVariant` oscuro del inactivo y se lee al
    // revés. Conserva el matiz: en claro lo oscurece, en oscuro lo deja claro.
    val activeColor = ensureContrast(accentColor, MaterialTheme.colorScheme.surface, minRatio = 3f)
    val iconColor by animateColorAsState(
        targetValue = if (checked) activeColor else inactiveColor,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "toggleIconColor"
    )
    IconToggleButton(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange()
        },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = inactiveColor,
            checkedContentColor = activeColor
        ),
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description }
    ) {
        // Punto indicador de "activo" (estilo nav/Spotify): deja claro cuál está activo sin un
        // contenedor pesado — el color solo era muy sutil (sobre todo en grises). Aparece con un
        // pequeño rebote; su espacio (4dp) se reserva siempre para no desplazar el icono.
        val dotScale by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "toggleDot"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MaterialSymbol(icon, size = iconSize, fill = checked && fillWhenChecked, color = iconColor)
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(4.dp)
                    .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                    .background(activeColor, CircleShape)
            )
        }
    }
}

/**
 * Botón de ACCIÓN (no-toggle) gemelo de [ExpressiveToggleIcon]: mismo tamaño (48.dp) y feedback
 * háptico, pero sin estado checked ni relleno. Lo usan las acciones que abren overlays (cola,
 * ecualizador) para que se vean idénticas a los toggles en estado inactivo.
 *
 * [morph]: si true (default), morfea de forma al presionar ([IconButtonDefaults.shapes]). El
 * floating toolbar del NowPlaying lo pasa en false → CÍRCULO fijo sin morph (decisión del usuario
 * para ESA barra; la barra superior conserva el morph con el default).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveActionIcon(
    onClick: () -> Unit,
    icon: String,
    description: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    iconSize: TextUnit = 24.sp,
    enabled: Boolean = true,
    morph: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shapes = if (morph) IconButtonDefaults.shapes()
                 else IconButtonDefaults.shapes(shape = CircleShape, pressedShape = CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor
        ),
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description }
    ) {
        MaterialSymbol(icon, size = iconSize)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackControls(
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    spin: PlayButtonSpinState
) {
    val haptic = LocalHapticFeedback.current
    // Contenedor de prev/next: FILLED TONAL de M3 (spec para este tipo de botón secundario) =
    // secondaryContainer + onSecondaryContainer. Contraste del icono garantizado por el par M3.
    // Jerarquía: play = Filled/primary (acento pleno) > prev/next = Tonal/secondary.
    val sideButtonContainer = MaterialTheme.colorScheme.secondaryContainer
    val sideButtonContent = MaterialTheme.colorScheme.onSecondaryContainer
    // Transporte = SOLO prev / play / next, centrado. Shuffle se movió a la cola y repeat al
    // toolbar (eran ajustes de la cola, no del transporte; y competían con el play en la jerarquía).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Grupo central prev/play/next como CONNECTED BUTTON GROUP M3 Expressive según la
        // referencia visual del spec: separación visible (GroupSpacing), extremos con lado
        // exterior en semicírculo e interiores generosos que se encogen al presionar, y la
        // física del grupo: el botón presionado se ensancha un 15% (ExpandedRatio) comprimiendo
        // a sus vecinos vía animateWidth.
        //
        // El overflow NUNCA se dispara por diseño: los 3 ítems llevan weight proporcional a su
        // tamaño objetivo (56 / ancho del play / 56) y el ancho del grupo se capa a esa suma con
        // widthIn(max) — el measure policy solo manda ítems al menú cuando los NO ponderados no
        // caben; los ponderados se reparten el espacio disponible, así que en pantallas normales
        // miden exacto y en ultra estrechas se comprimen proporcionalmente (un control de
        // transporte jamás debe esconderse). Los menuContent quedan como red de seguridad
        // funcional por si un cambio futuro rompe esa invariante.

        // REPRODUCIENDO: el play es una COOKIE de 9 lados (88×88, morph desde la píldora
        // vía PlayButtonMorphShape) y los laterales círculos de 64.dp. EN PAUSA: los
        // laterales se estiran a cápsulas verticales (56×80) y el play vuelve a píldora
        // (132×80, solo icono). Forma y dimensiones animan con el mismo spring.
        val playWidth by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 88.dp else 132.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "playButtonWidth"
        )
        val playHeight by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 88.dp else 80.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "playButtonHeight"
        )
        // Giro continuo de la cookie mientras suena. La rotación se aplica en un
        // graphicsLayer con lambda (solo invalida el draw, cero recomposición por
        // frame) y se escala por el progreso del morph vía cookieSpinDegrees, así al
        // pausar se desenrosca suavemente junto con el morph a píldora. El estado
        // (morph + ángulo) viene HOISTED de [rememberPlayButtonSpin] para que las dos
        // capas del AccentRevealGroup dibujen el botón con el MISMO giro.
        val playMorphProgress by spin.morphProgress
        val cookieAngle = spin.angle
        val sideWidth by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 64.dp else 56.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "sideButtonWidth"
        )
        val sideHeight by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 64.dp else 80.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "sideButtonHeight"
        )
        val sideShapes = IconButtonShapes(
            shape = RoundedCornerShape(percent = 50),
            pressedShape = RoundedCornerShape(NowPlayingConfig.GroupInnerCorner)
        )
        val prevShapes = sideShapes
        val nextShapes = sideShapes
        // El grupo central va dentro de un Box PONDERADO con padding propio: sin él, la Row
        // mide el grupo ANTES que el toggle de repeat, y con el play ensanchado (pausa) el
        // grupo se llevaba todo el ancho disponible dejando a los toggles pegados/aplastados.
        // Con weight(1f) el grupo solo puede ocupar lo que sobra tras los dos toggles menos
        // TransportSideGap por lado, y sus weights internos lo comprimen proporcionalmente en
        // pantallas estrechas en vez de invadir a los vecinos.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NowPlayingConfig.TransportSideGap),
            contentAlignment = Alignment.Center
        ) {
        ButtonGroup(
            overflowIndicator = { menuState ->
                val moreControlsDesc = stringResource(R.string.np_more_controls)
                FilledIconButton(
                    onClick = { menuState.show() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideButtonContainer,
                        contentColor = sideButtonContent
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = moreControlsDesc }
                ) {
                    MaterialSymbol("more_horiz", size = 30.sp)
                }
            },
            horizontalArrangement = Arrangement.spacedBy(NowPlayingConfig.GroupSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(
                // Cap exacto = suma animada de los tres botones + gaps: así los weights
                // (proporcionales) producen exactamente los dp objetivo.
                max = sideWidth * 2 + playWidth + NowPlayingConfig.GroupSpacing * 2
            )
        ) {
            // Anterior: shape conectada "leading" de spec.
            customItem(
                buttonGroupContent = {
                    val prevInteraction = remember { MutableInteractionSource() }
                    val prevDesc = stringResource(R.string.np_previous)
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPrevious()
                        },
                        shapes = prevShapes,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sideButtonContainer,
                            contentColor = sideButtonContent
                        ),
                        interactionSource = prevInteraction,
                        modifier = Modifier
                            .weight(sideWidth.value)
                            .animateWidth(prevInteraction)
                            .height(sideHeight)
                            .semantics { contentDescription = prevDesc }
                    ) {
                        MaterialSymbol("skip_previous", size = 32.sp, fill = true)
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.np_previous)) },
                        leadingIcon = { MaterialSymbol("skip_previous", fill = true) },
                        onClick = {
                            onPrevious()
                            state.dismiss()
                        }
                    )
                }
            )

            // Play/pausa: círculo reproduciendo, píldora ensanchada con texto en pausa.
            customItem(
                buttonGroupContent = {
                    val playInteraction = remember { MutableInteractionSource() }
                    val playStateDesc = when (playbackState) {
                        PlaybackState.BUFFERING -> stringResource(R.string.np_loading)
                        PlaybackState.PLAYING -> stringResource(R.string.common_pause)
                        else -> stringResource(R.string.common_play)
                    }
                    // Morph continuo píldora (pausa) ↔ Cookie9Sided (reproduciendo),
                    // conducido por el mismo spring que las dimensiones.
                    val playShape = PlayButtonMorphShape(playMorphProgress)
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPause()
                        },
                        shape = playShape,
                        color = playButtonColor,
                        interactionSource = playInteraction,
                        modifier = Modifier
                            .weight(playWidth.value)
                            .animateWidth(playInteraction)
                            .height(playHeight)
                            .graphicsLayer {
                                rotationZ = cookieSpinDegrees(cookieAngle.value, playMorphProgress)
                            }
                            .semantics { contentDescription = playStateDesc }
                    ) {
                        // Contra-rotación: el layer gira la superficie entera (forma incluida);
                        // el contenido se gira a la inversa para que el icono quede derecho.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    rotationZ = -cookieSpinDegrees(cookieAngle.value, playMorphProgress)
                                }
                        ) {
                            AnimatedContent(
                                targetState = playbackState,
                                transitionSpec = {
                                    scaleIn(
                                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                                    ) togetherWith scaleOut(
                                        animationSpec = tween(150, easing = FastOutLinearInEasing)
                                    )
                                },
                                label = "playPause"
                            ) { state ->
                                when (state) {
                                    PlaybackState.BUFFERING ->
                                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                                        LoadingIndicator(color = playButtonContentColor, modifier = Modifier.size(44.dp))
                                    PlaybackState.PLAYING ->
                                        MaterialSymbol("pause", size = 32.sp, color = playButtonContentColor, fill = true)
                                    else ->
                                        MaterialSymbol("play_arrow", size = 32.sp, color = playButtonContentColor, fill = true)
                                }
                            }
                        }
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(if (isPlayingOrBuffering) stringResource(R.string.common_pause) else stringResource(R.string.common_play)) },
                        leadingIcon = { MaterialSymbol(if (isPlayingOrBuffering) "pause" else "play_arrow") },
                        onClick = {
                            onPlayPause()
                            state.dismiss()
                        }
                    )
                }
            )

            // Siguiente: shape conectada "trailing" de spec.
            customItem(
                buttonGroupContent = {
                    val nextInteraction = remember { MutableInteractionSource() }
                    val nextDesc = stringResource(R.string.np_next)
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNext()
                        },
                        shapes = nextShapes,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sideButtonContainer,
                            contentColor = sideButtonContent
                        ),
                        interactionSource = nextInteraction,
                        modifier = Modifier
                            .weight(sideWidth.value)
                            .animateWidth(nextInteraction)
                            .height(sideHeight)
                            .semantics { contentDescription = nextDesc }
                    ) {
                        MaterialSymbol("skip_next", size = 32.sp, fill = true)
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.np_next)) },
                        leadingIcon = { MaterialSymbol("skip_next", fill = true) },
                        onClick = {
                            onNext()
                            state.dismiss()
                        }
                    )
                }
            )
        } // fin grupo central prev/play/next
        } // fin Box ponderado del transporte
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomActionBar(
    showLyrics: Boolean,
    isLyricsLoading: Boolean,
    keepScreenOn: Boolean,
    playButtonColor: Color,
    songRemoteId: String?,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenEqualizer: () -> Unit,
    /** Estado REAL del EQ propio: el botón del toolbar rellena su fondo cuando está encendido. */
    eqEnabled: Boolean,
    onRedownload: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    repeatMode: RepeatMode,
    onRepeatToggle: () -> Unit,
    config: List<ToolbarActionState>,
    modifier: Modifier = Modifier
) {
    // FLOATING TOOLBAR M3 Expressive (m3.material.io/components/toolbars), variante VIBRANT:
    // container = `primaryContainer`, content = `onPrimaryContainer` (defaults de
    // vibrantFloatingToolbarColors). Se eligió vibrant porque la standard (surfaceContainer) se
    // perdía contra el fondo del NowPlaying.
    val toolbarContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    // Activo = el PAR PROPIO de la barra INVERTIDO: contenedor `onPrimaryContainer`, icono
    // `primaryContainer`. Antes se usaba `inverseSurface`/`inverseOnSurface`, pero con carátulas
    // monocromas (esquema del álbum casi sin croma) inverseSurface cae del MISMO lado tonal que
    // el primaryContainer de la barra → el toggle activo quedaba gris sobre gris, casi invisible
    // en ambos temas. El par container/onContainer tiene contraste garantizado POR CONSTRUCCIÓN
    // (M3 los genera a ≥4.5:1) sea cual sea el seed, así el relleno activo siempre se ve.
    val checkedBg = MaterialTheme.colorScheme.onPrimaryContainer
    val activeContent = MaterialTheme.colorScheme.primaryContainer
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            // Spec floating toolbar: container de 64dp. La alpha18 NO lo respeta sola (medido
            // 72.5dp en dispositivo: acolcha 12dp alrededor de los touch targets de 48 aunque
            // se le pase contentPadding de 8) → altura FORZADA al token. Los visuales de 40dp
            // de los icon buttons caben exactos y quedan centrados.
            contentPadding = PaddingValues(ComponentConfig.FloatingBarInnerPadding),
            modifier = Modifier.height(ComponentConfig.FloatingToolbarHeight)
        ) {
            // Barra DINÁMICA: el usuario elige qué acciones van aquí y en qué orden (Ajustes →
            // Reproducción); el resto van al overflow (⋮). DOWNLOAD solo aplica a canciones de la
            // nube (songRemoteId != null): en locales se filtra de ambos lados. En la barra se
            // filtra también DURANTE la descarga (el anillo de progreso de abajo ya la comunica);
            // filtrarla acá y no dentro del `when` evita dejar un Spacer huérfano (hueco doble).
            val barActions = config.filter { it.inBar }.map { it.action }
                .filter { it != PlayerToolbarAction.DOWNLOAD || (songRemoteId != null && !isDownloading) }
            val overflowActions = config.filterNot { it.inBar }.map { it.action }
                .filter { it != PlayerToolbarAction.DOWNLOAD || songRemoteId != null }

            barActions.forEachIndexed { index, action ->
                // Gap explícito del spec entre acciones (el toolbar no espacia su Row solo).
                if (index > 0) Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                when (action) {
                    // Repeat: toggle de 3 estados (OFF→ALL→ONE). "Activo" = repeatMode != OFF; el
                    // icono cambia a repeat_one en modo ONE. Mismo tratamiento (reveal) que el resto.
                    PlayerToolbarAction.REPEAT -> ToolbarToggle(
                        checked = repeatMode != RepeatMode.OFF,
                        onToggle = onRepeatToggle,
                        icon = if (repeatMode == RepeatMode.ONE) "repeat_one" else "repeat",
                        description = when (repeatMode) {
                            RepeatMode.OFF -> stringResource(R.string.np_repeat_off)
                            RepeatMode.ONE -> stringResource(R.string.np_repeat_one)
                            RepeatMode.ALL -> stringResource(R.string.np_repeat_all)
                        },
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.LYRICS -> ToolbarToggle(
                        checked = showLyrics,
                        onToggle = onLyricsToggle,
                        icon = "lyrics",
                        description = if (showLyrics) stringResource(R.string.np_hide_lyrics) else stringResource(R.string.np_show_lyrics),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor,
                        isLoading = isLyricsLoading
                    )
                    PlayerToolbarAction.QUEUE -> ExpressiveActionIcon(
                        onClick = onShowQueue,
                        icon = "queue_music",
                        description = stringResource(R.string.np_view_queue),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    PlayerToolbarAction.KEEP_SCREEN_ON -> ToolbarToggle(
                        checked = keepScreenOn,
                        onToggle = onToggleKeepScreenOn,
                        // `wb_sunny` (no `visibility`): el ojo relleno quedaba como un blob sólido
                        // pesado; el sol hace un morph outlined→filled limpio y comunica "pantalla
                        // encendida/brillando".
                        icon = "wb_sunny",
                        description = if (keepScreenOn) stringResource(R.string.np_screen_off) else stringResource(R.string.np_screen_on),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    // Con look de toggle (fondo relleno) cuando el EQ está ENCENDIDO — era el único
                    // botón con estado persistente que no lo mostraba. El tap sigue abriendo la
                    // hoja/panel (el on/off vive dentro).
                    PlayerToolbarAction.EQUALIZER -> ToolbarToggle(
                        checked = eqEnabled,
                        onToggle = onOpenEqualizer,
                        icon = "graphic_eq",
                        description = stringResource(R.string.np_open_eq),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.SLEEP_TIMER -> ToolbarToggle(
                        checked = sleepTimerActive,
                        onToggle = onSleepTimerClick,
                        icon = "bedtime",
                        description = stringResource(R.string.sleep_timer_title),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.ADD_TO_PLAYLIST -> ExpressiveActionIcon(
                        onClick = onAddToPlaylistClick,
                        icon = "playlist_add",
                        description = stringResource(R.string.common_add_to_playlist),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    // Local o descargando ya se filtró al armar barActions.
                    PlayerToolbarAction.DOWNLOAD ->
                        ExpressiveActionIcon(
                            onClick = onRedownload,
                            icon = "download",
                            description = if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download),
                            contentColor = toolbarContentColor,
                            morph = false
                        )
                }
            }

            // Descarga EN CURSO: anillo determinado (o indeterminado mientras se resuelve la URL).
            // Independiente de la config: es ESTADO, no una acción reordenable.
            AnimatedVisibility(visible = isDownloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                    val downloadingDesc = downloadProgress?.let {
                        stringResource(R.string.status_downloading, (it * 100).toInt())
                    } ?: stringResource(R.string.download_status_preparing)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = downloadingDesc }
                    ) {
                        // LoadingIndicator expressive (morfea entre MaterialShapes) en sus dos
                        // variantes: determinada con progreso, indeterminada al preparar. Antes
                        // eran CircularProgressIndicator clásicos, el único indicador de
                        // descarga que quedaba fuera del lenguaje Expressive.
                        if (downloadProgress != null && downloadProgress > 0f) {
                            LoadingIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = playButtonColor
                            )
                        } else {
                            LoadingIndicator(
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = playButtonColor
                            )
                        }
                        MaterialSymbol("download", size = 14.sp, color = toolbarContentColor)
                    }
                }
            }

            // Overflow (⋮): las acciones que el usuario dejó fuera de la barra, en su orden. Se
            // oculta por completo si no queda ninguna.
            if (overflowActions.isNotEmpty()) {
                Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                Box {
                    var showMenu by remember { mutableStateOf(false) }
                    ExpressiveActionIcon(
                        onClick = { showMenu = true },
                        icon = "more_vert",
                        description = stringResource(R.string.np_more_options),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        overflowActions.forEach { action ->
                            when (action) {
                                PlayerToolbarAction.REPEAT -> DropdownMenuItem(
                                    text = {
                                        Text(when (repeatMode) {
                                            RepeatMode.OFF -> stringResource(R.string.np_repeat_off)
                                            RepeatMode.ONE -> stringResource(R.string.np_repeat_one)
                                            RepeatMode.ALL -> stringResource(R.string.np_repeat_all)
                                        })
                                    },
                                    leadingIcon = {
                                        MaterialSymbol(
                                            if (repeatMode == RepeatMode.ONE) "repeat_one" else "repeat",
                                            fill = repeatMode != RepeatMode.OFF
                                        )
                                    },
                                    onClick = { showMenu = false; onRepeatToggle() }
                                )
                                PlayerToolbarAction.LYRICS -> DropdownMenuItem(
                                    text = { Text(if (showLyrics) stringResource(R.string.np_hide_lyrics) else stringResource(R.string.np_show_lyrics)) },
                                    leadingIcon = { MaterialSymbol("lyrics", fill = showLyrics) },
                                    onClick = { showMenu = false; onLyricsToggle() }
                                )
                                PlayerToolbarAction.QUEUE -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.np_view_queue)) },
                                    leadingIcon = { MaterialSymbol("queue_music") },
                                    onClick = { showMenu = false; onShowQueue() }
                                )
                                PlayerToolbarAction.KEEP_SCREEN_ON -> DropdownMenuItem(
                                    text = { Text(if (keepScreenOn) stringResource(R.string.np_screen_off) else stringResource(R.string.np_screen_on)) },
                                    leadingIcon = { MaterialSymbol("wb_sunny", fill = keepScreenOn) },
                                    onClick = { showMenu = false; onToggleKeepScreenOn() }
                                )
                                PlayerToolbarAction.EQUALIZER -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.np_open_eq)) },
                                    leadingIcon = { MaterialSymbol("graphic_eq") },
                                    onClick = { showMenu = false; onOpenEqualizer() }
                                )
                                PlayerToolbarAction.ADD_TO_PLAYLIST -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                                    leadingIcon = { MaterialSymbol("playlist_add") },
                                    onClick = { showMenu = false; onAddToPlaylistClick() }
                                )
                                PlayerToolbarAction.SLEEP_TIMER -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sleep_timer_title)) },
                                    leadingIcon = {
                                        MaterialSymbol(
                                            "bedtime",
                                            fill = sleepTimerActive,
                                            color = if (sleepTimerActive) playButtonColor else LocalContentColor.current
                                        )
                                    },
                                    onClick = { showMenu = false; onSleepTimerClick() }
                                )
                                PlayerToolbarAction.DOWNLOAD -> DropdownMenuItem(
                                    text = { Text(if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download)) },
                                    leadingIcon = { MaterialSymbol(if (isDownloading) "hourglass_top" else "download") },
                                    enabled = !isDownloading,
                                    onClick = { showMenu = false; onRedownload() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

    // --- Helper Composables ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressSlider(
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    trackColor: Color,
    inactiveTrackColor: Color,
    textColor: Color,
    formatText: String,
    /** Ajustes → Reproducción: track ONDULADO (Expressive) en vez de la píldora plana. */
    wavy: Boolean = false,
    /** Solo para [wavy]: en pausa la onda se APLANA (como el reproductor de Android 16). */
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val displayPosition by remember {
        derivedStateOf {
            if (isDragging) sliderPosition
            else if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Decisión final del usuario tras iterar: barra de 12.dp SIN gap (thumbTrackGapSize
        // del spec descartado — con la canción por terminar el hueco se veía raro) y con el
        // fill en píldora de BORDE REDONDO superpuesta al riel + stop indicator, dibujados a
        // mano en el track. AL ARRASTRAR aparece el handle de barra vertical como indicador.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = displayPosition,
                onValueChange = { newValue ->
                    isDragging = true
                    sliderPosition = newValue
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek((sliderPosition * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    // "Palo" vertical (handle del spec) + GOTA con el tiempo, ambos solo
                    // mientras se arrastra. La gota es el pin clásico: cuadrado con 3 esquinas
                    // al 50% rotado 45° (la esquina viva apunta al palo), texto contra-rotado.
                    val thumbAlpha by animateFloatAsState(
                        targetValue = if (isDragging) 1f else 0f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "thumbAlpha"
                    )
                    Box(
                        modifier = Modifier.size(width = 5.dp, height = 26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = thumbAlpha }
                                .background(Color.White, RoundedCornerShape(percent = 50))
                        )
                        // requiredSize + offset: flota sobre el palo sin alterar la medida
                        // del thumb (que posiciona el gap/track del slider).
                        Box(
                            modifier = Modifier
                                .requiredSize(46.dp)
                                .offset(y = (-52).dp)
                                .graphicsLayer {
                                    alpha = thumbAlpha
                                    rotationZ = 45f
                                }
                                .background(
                                    trackColor,
                                    RoundedCornerShape(
                                        topStartPercent = 50, topEndPercent = 50,
                                        bottomEndPercent = 0, bottomStartPercent = 50
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatTime((sliderPosition * duration).toLong()),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = onContainerColor(trackColor),
                                modifier = Modifier.graphicsLayer { rotationZ = -45f }
                            )
                        }
                    }
                },
                track = { sliderState ->
                    val activeColor = trackColor
                    val inactiveColor = inactiveTrackColor.copy(alpha = 0.25f)
                    if (wavy) {
                        WavyTrack(
                            fraction = sliderState.value.coerceIn(0f, 1f),
                            isPlaying = isPlaying,
                            isDragging = isDragging,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor
                        )
                    } else {
                        // Track dibujado a mano: el fill es una PÍLDORA (borde redondo) superpuesta
                        // al riel inactivo. El Track oficial no superpone segmentos: redondear su
                        // borde interior (trackInsideCornerSize) con gap 0 deja muescas transparentes
                        // donde las curvas del fill y del riel se separan.
                        // Puntito de contraste dentro del fill (espejo del stop indicator del
                        // otro extremo): marca la posición actual aunque no haya thumb visible.
                        val fillDotColor = onContainerColor(activeColor)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Mismo alto que la onda: si divergen, alternar el ajuste de
                                // Apariencia movería el bloque de controles.
                                .height(ComponentConfig.ProgressTrackHeight)
                                .clip(RoundedCornerShape(percent = 50))
                                .drawBehind {
                                    val dotRadius = ComponentConfig.ProgressStopIndicatorSize.toPx() / 2f
                                    drawRect(inactiveColor)
                                    // Ancho mínimo = un círculo completo, para que la píldora no se
                                    // deforme al inicio de la canción.
                                    val fillWidth = (size.width * sliderState.value.coerceIn(0f, 1f))
                                        .coerceAtLeast(size.height)
                                    drawRoundRect(
                                        color = activeColor,
                                        size = Size(fillWidth, size.height),
                                        cornerRadius = CornerRadius(size.height / 2)
                                    )
                                    // Punta del fill: inset media altura, misma geometría que el
                                    // stop indicator pero en color de contraste.
                                    drawCircle(
                                        color = fillDotColor,
                                        radius = dotRadius,
                                        center = Offset(fillWidth - size.height / 2, size.height / 2)
                                    )
                                    // Stop indicator del spec: inset media altura, oculto cuando
                                    // el progreso lo alcanza (igual que el oficial).
                                    val indicatorX = size.width - size.height / 2
                                    if (fillWidth < indicatorX) {
                                        drawCircle(
                                            color = activeColor,
                                            radius = dotRadius,
                                            center = Offset(indicatorX, size.height / 2)
                                        )
                                    }
                                }
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Tiempos a los extremos y FORMATO al medio. Un Box (no una Row con SpaceBetween):
        // así el chip de formato queda centrado con la barra de verdad, sin depender de que
        // los dos tiempos midan lo mismo (no lo hacen: "0:07" vs "12:41").
        Box(modifier = Modifier.fillMaxWidth()) {
            TimeChip(
                text = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
                textColor = textColor,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            FormatChip(
                formatText = formatText,
                textColor = textColor,
                accentColor = trackColor,
                modifier = Modifier.align(Alignment.Center)
            )
            TimeChip(
                text = formatTime(duration),
                textColor = textColor,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Track ONDULADO del reproductor (Ajustes → Apariencia). Dibujado a mano A PROPÓSITO: el
 * `LinearWavyProgressIndicator` oficial anima el aplanado con `DecreasingAmplitudeAnimationSpec`,
 * una constante INTERNA FIJA (500 ms) que no expone por parámetro — al pausar terminaba
 * siempre después que el resto de las animaciones del reproductor y el desfase se notaba.
 * Aquí la amplitud usa el MISMO spring que el morph del botón play, así todo cierra a la vez.
 *
 * La FASE avanza solo mientras suena y se congela al pausar (un `Animatable` cancelado
 * conserva su valor): sin salto al reanudar y sin gastar frames con el audio detenido.
 * Geometría igual que la píldora plana (alto 12dp, stop indicator) para que alternar el
 * ajuste no mueva el layout.
 */
@Composable
private fun WavyTrack(
    fraction: Float,
    isPlaying: Boolean,
    /** El usuario está arrastrando: el track debe seguir al dedo sin interpolar. */
    isDragging: Boolean,
    activeColor: Color,
    inactiveColor: Color
) {
    val amplitudeFraction by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        // MISMA rigidez que el morph del botón play (ver rememberPlayButtonSpin) para que
        // ambos cierren a la vez; sin rebote, que en la amplitud invertiría la onda.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "waveAmplitude"
    )
    // Fase en "número de ondas recorridas"; el LaunchedEffect se cancela al pausar y el
    // Animatable se queda donde estaba.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        // UN ciclo por vuelta, en bucle. Antes se animaba a un horizonte lejano (600 ciclos ≈
        // 10 min) en una sola llamada, y al agotarse la onda SE CONGELABA: seguía moviéndose la
        // punta (que depende del progreso) pero no la ondulación, y solo se recuperaba al salir
        // y volver al reproductor, que recomponía el efecto. Con canciones largas —Dream
        // Theater— eso pasaba dentro de la misma pista.
        //
        // El `% 1f` antes de cada tramo mantiene la fase acotada: como entra en un seno, es
        // periódica en 1 vuelta, así que reencuadrarla no produce ningún salto visible y evita
        // que el Float pierda precisión decimal tras horas de reproducción.
        while (isPlaying) {
            phase.snapTo(phase.value % 1f)
            phase.animateTo(
                targetValue = phase.value + 1f,
                animationSpec = tween(
                    durationMillis = WAVE_MS_PER_CYCLE.toInt(),
                    easing = LinearEasing
                )
            )
        }
    }

    // El progreso llega a TIRONES: la posición se refresca una vez por segundo (el bucle de
    // MusicPlayerScreen, deliberadamente lento para no despertar el main thread cada frame).
    // En la píldora plana ese escalón se nota poco, pero aquí estira la onda de golpe y se ve
    // como un tropiezo — más aún porque el ciclo de la onda dura también 1s y el salto caía
    // siempre en la misma fase. Se interpola entre ticks a velocidad constante.
    val smoothFraction = remember { Animatable(fraction) }
    LaunchedEffect(fraction, isPlaying, isDragging) {
        val jump = kotlin.math.abs(fraction - smoothFraction.value)
        // Un seek (o el cambio de canción) NO se interpola: sería un barrido de un segundo
        // recorriendo toda la barra. Tampoco en pausa, donde no hay avance que suavizar, ni
        // arrastrando: ahí el track tiene que ir pegado al dedo y no un segundo por detrás.
        if (!isPlaying || isDragging || jump > PROGRESS_SNAP_THRESHOLD) {
            smoothFraction.snapTo(fraction)
        } else {
            smoothFraction.animateTo(
                targetValue = fraction,
                animationSpec = tween(POSITION_TICK_MS, easing = LinearEasing)
            )
        }
    }
    val drawnFraction = smoothFraction.value

    // Path reutilizado: este bloque se redibuja en cada frame mientras suena.
    val wavePath = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComponentConfig.ProgressTrackHeight)
    ) {
        val centerY = size.height / 2f
        val stroke = ComponentConfig.ProgressWaveStroke.toPx()
        val radius = stroke / 2f
        // El trazo es redondeado: el recorrido útil va de radio a ancho-radio, así los
        // extremos no se salen del contenedor.
        val startX = radius
        val endX = size.width - radius
        val activeEndX = startX + (endX - startX) * drawnFraction

        // Riel inactivo: recto siempre (solo la parte reproducida ondula, igual que el spec).
        // El gap de un trazo lo separa del fill; cuando ya no cabe, el riel simplemente no se
        // dibuja (la canción está por terminar).
        val inactiveStartX = activeEndX + stroke
        if (inactiveStartX < endX) {
            drawLine(
                color = inactiveColor,
                start = Offset(inactiveStartX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // Stop indicator del final (desaparece cuando el progreso lo alcanza).
            drawCircle(color = inactiveColor, radius = radius, center = Offset(endX, centerY))
        }

        // Tramo reproducido: sinusoide muestreada; con amplitud 0 queda una recta perfecta.
        val amplitudePx = ComponentConfig.ProgressWaveAmplitude.toPx() * amplitudeFraction
        val wavelengthPx = ComponentConfig.ProgressWaveLength.toPx()
        val phaseTurns = phase.value
        // Y de la sinusoide en una x dada (x = startX ⇒ solo la fase, así el ARRANQUE también
        // ondula: anclarlo a centerY lo dejaba clavado mientras el resto se movía, además de
        // meter un pico vertical en el primer segmento).
        fun waveY(atX: Float): Float {
            val theta = ((atX - startX) / wavelengthPx + phaseTurns) * FULL_TURN_RADIANS
            return centerY + sin(theta) * amplitudePx
        }
        val step = WAVE_SAMPLE_STEP_PX
        wavePath.reset()
        wavePath.moveTo(startX, waveY(startX))
        var x = startX + step
        while (x < activeEndX) {
            wavePath.lineTo(x, waveY(x))
            x += step
        }
        // Punto final exacto: con el paso de muestreo la punta quedaría corta.
        wavePath.lineTo(activeEndX, waveY(activeEndX))
        drawPath(
            path = wavePath,
            color = activeColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Diámetro del indicador de descarga de la barra del NowPlaying. Va dentro del hueco de 48dp
 * de una acción y por debajo rodea al glifo `download` centrado, sin tocarlo.
 */
private val ToolbarDownloadIndicatorSize = 32.dp

// --- Constantes de la onda del track (ver [WavyTrack]) ---
/** Cuánto tarda la onda en recorrer un ciclo: marca la velocidad del desplazamiento. */
private const val WAVE_MS_PER_CYCLE = 1000f

/**
 * Periodo con el que la UI refresca la posición de reproducción (el bucle de
 * `MusicPlayerScreen`). La interpolación del track dura exactamente eso: cada tick llega
 * justo cuando el anterior terminó de dibujarse, así el avance se ve continuo.
 */
private const val POSITION_TICK_MS = 1000

/**
 * Salto de progreso (fracción de la barra) por encima del cual NO se interpola. Un tick normal
 * avanza `1s / duración` — con la canción más corta de una biblioteca típica (~1 min) eso es
 * ~0.017, así que 0.05 deja pasar el avance natural y ataja solo los seeks y los cambios de
 * pista, que deben ser instantáneos.
 */
private const val PROGRESS_SNAP_THRESHOLD = 0.05f

/**
 * Paso de muestreo del path en píxeles. La sinusoide se dibuja como polilínea: 2px da una
 * curva suave a cualquier densidad sin inflar el número de segmentos.
 */
private const val WAVE_SAMPLE_STEP_PX = 2f

/** Una vuelta completa en radianes (la fase se mide en ciclos, no en radianes). */
private const val FULL_TURN_RADIANS = 2f * PI.toFloat()

/**
 * Formatos SIN PÉRDIDA que la app puede reportar. `M4A` queda FUERA a propósito: el contenedor
 * MP4 lleva tanto AAC (con pérdida, el caso normal) como ALAC (sin pérdida), y el formato se
 * deduce del MIME/extensión, que no distingue el códec de adentro. Ante la duda, no se promete
 * lossless.
 */
private val LOSSLESS_FORMATS = setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "WV")

/**
 * Chip de FORMATO del archivo (FLAC / MP3 / …), centrado entre los dos tiempos del slider.
 * Se separó del chip de origen: son dos datos distintos (QUÉ suena vs DE DÓNDE sale) y juntos
 * hacían una pastilla larga. Lleva el acento del álbum (el mismo del track activo de la barra
 * que tiene justo encima) para leerse como parte de ella y no como un tercer tiempo.
 *
 * SOLO TEXTO, sin icono (decisión del usuario): el formato ya se lee en la etiqueta. La calidad
 * se distingue por el peso del contenedor —los formatos sin pérdida ([LOSSLESS_FORMATS]) llevan
 * el acento más marcado— y por la descripción de accesibilidad.
 */
@Composable
private fun FormatChip(
    formatText: String,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val isLossless = formatText.uppercase() in LOSSLESS_FORMATS
    val desc = stringResource(
        if (isLossless) R.string.np_format_desc_lossless else R.string.np_format_desc,
        formatText
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.semantics { contentDescription = desc }
    ) {
        Text(
            text = formatText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * Chip de tiempo del slider: pastilla suave derivada del propio color del texto (translúcida),
 * así funciona sobre cualquier punto del gradiente/carátula sin plumbing de haze.
 */
@Composable
private fun TimeChip(text: String, textColor: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
