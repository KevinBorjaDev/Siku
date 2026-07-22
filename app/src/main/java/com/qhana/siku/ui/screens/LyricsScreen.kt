package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.ui.components.LyricsSearchSheet
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.onContainerColor
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.viewmodel.LyricLine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class LyricsViewMode {
    SYNCED, PLAIN
}

// Paddings de SyncedLyricsView. Se reutilizan en `contentPadding` y en el cálculo
// del scroll offset para centrar la línea activa en el área visible "limpia"
// (entre el header y los controles flotantes), no en el viewport bruto.
private val SyncedTopPadding = 32.dp
private val SyncedBottomPadding = 180.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsScreen(
    lyrics: String?,
    lyricLines: List<LyricLine>,
    isLyricsLoading: Boolean,
    /** Causa de que no haya letra; gobierna qué acciones ofrece el estado vacío. */
    lyricsFailure: LyricsFailure?,
    /** Detalle del fallo del proveedor (solo con [LyricsFailure.PROVIDER_ERROR]). */
    lyricsError: String?,
    songId: String,
    currentPositionFlow: StateFlow<Long>,
    playbackState: PlaybackState,
    lyricsCandidates: List<LyricsCandidate>?,
    isSearchingCandidates: Boolean,
    lyricsSearchError: String?,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onFetchLyrics: () -> Unit,
    onGoogleSearch: () -> Unit,
    onSearchManually: () -> Unit,
    onSelectCandidate: (LyricsCandidate) -> Unit,
    onDismissSearch: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()

    // Auto-detect mode capability
    val hasSyncedData = remember(lyricLines) { lyricLines.any { it.startTime > 0 } }

    // Sin letra que mostrar: manda el estado vacío, que YA ofrece las acciones pertinentes a la
    // causa. Los iconos del header (lupa + refresco) se ocultan ahí: serían redundantes, y en el
    // caso sin red directamente engañosos.
    val showEmptyState = !isLyricsLoading && lyrics == null && lyricLines.isEmpty()

    // Acento "seguro": el del álbum si contrasta con el fondo, si no contentColor.
    // Se usa para los iconos del header, el pill del toggle y la línea activa.
    val safeAccent = remember(accentColor, backgroundColor, contentColor) {
        safeAccentColor(accentColor, backgroundColor, contentColor)
    }

    // State initialization
    var viewMode by remember { mutableStateOf(if (hasSyncedData) LyricsViewMode.SYNCED else LyricsViewMode.PLAIN) }

    // Reset viewMode cuando cambia la canción O las letras (ej.: selección manual de candidato).
    // Sin la dependencia en `lyrics`, el viewMode podía quedar pegado en PLAIN si la canción
    // arrancó sin letras y luego el usuario eligió una versión sincronizada.
    LaunchedEffect(songId, lyrics) {
        viewMode = if (hasSyncedData) LyricsViewMode.SYNCED else LyricsViewMode.PLAIN
    }

    // Gradient Background Wrapper
    Box(
        modifier = modifier
            .fillMaxSize()
            // Fondo SÓLIDO (surfaceContainer, provisto por el caller): superficie plana estable,
            // igual que el ajuste "fondo sólido" del NowPlaying.
            .background(backgroundColor)
            // Interceptar toques para evitar click-through
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* No-op to block touches */ }
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ExpressiveActionIcon(
                    onClick = onClose,
                    icon = "expand_more",
                    description = stringResource(R.string.lyrics_close_desc),
                    contentColor = safeAccent,
                    iconSize = 32.sp
                )

                // Mode Toggle - pill deslizante custom sólido (sin bordes)
                if (hasSyncedData && !lyrics.isNullOrBlank() && !isLyricsLoading) {
                    LyricsModeToggle(
                        viewMode = viewMode,
                        pillColor = safeAccent,
                        contentColor = contentColor,
                        onModeChange = { viewMode = it }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Manual search + Refetch. Solo con letra en pantalla: en el estado vacío las
                // acciones las da EmptyStateView, adaptadas a la causa.
                if (!showEmptyState) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveActionIcon(
                            onClick = onSearchManually,
                            icon = "manage_search",
                            description = stringResource(R.string.lyrics_search_manual_desc),
                            contentColor = safeAccent.copy(alpha = if (isLyricsLoading) 0.3f else 1f),
                            enabled = !isLyricsLoading
                        )
                        ExpressiveActionIcon(
                            onClick = onFetchLyrics,
                            icon = "refresh",
                            description = stringResource(R.string.lyrics_refetch_desc),
                            contentColor = safeAccent.copy(alpha = if (isLyricsLoading) 0.3f else 1f),
                            enabled = !isLyricsLoading
                        )
                    }
                } else {
                    // Mantiene el balance del Row (el cerrar queda a la izquierda, no centrado).
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }

            // Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (isLyricsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 120.dp), // Subir visualmente
                        contentAlignment = Alignment.Center
                    ) {
                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                        LoadingIndicator(color = accentColor, modifier = Modifier.size(56.dp))
                    }
                } else if (lyrics == "[INSTRUMENTAL]") {
                    InstrumentalView(contentColor)
                } else if (showEmptyState) {
                    EmptyStateView(
                        failure = lyricsFailure,
                        errorDetail = lyricsError,
                        contentColor = contentColor,
                        accentColor = safeAccent,
                        onFetchLyrics = onFetchLyrics,
                        onSearchManually = onSearchManually,
                        onGoogleSearch = onGoogleSearch
                    )
                } else {
                    Crossfade(targetState = viewMode, label = "LyricsMode") { mode ->
                        when (mode) {
                            LyricsViewMode.SYNCED -> SyncedLyricsView(
                                lines = lyricLines,
                                currentPosition = currentPosition,
                                contentColor = contentColor,
                                accentColor = accentColor,
                                backgroundColor = backgroundColor,
                                onSeek = onSeek
                            )
                            LyricsViewMode.PLAIN -> PlainLyricsView(
                                text = lyrics ?: lyricLines.joinToString("\n") { it.text },
                                contentColor = contentColor
                            )
                        }
                    }
                }
            }
        }

        // Floating Controls at Bottom (Lifted Up + Colored Fade Effect)
        val bottomGlow = remember(backgroundColor, accentColor) {
            // Mezclamos el fondo oscuro con un 25% del color de acento para que "brille" con color
            Color(androidx.core.graphics.ColorUtils.blendARGB(backgroundColor.toArgb(), accentColor.toArgb(), 0.25f))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Gradiente Fade: glow de color en la transición, pero aterriza en el
                // backgroundColor SÓLIDO (sin tinte de acento) para que los botones —que
                // son de acento— contrasten más contra su fondo inmediato. Se vuelve opaco
                // antes para separar mejor los controles del texto que scrollea detrás.
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0f),   // Transparente
                            bottomGlow.copy(alpha = 0.5f),      // Glow de color en la transición
                            backgroundColor.copy(alpha = 0.9f), // Casi opaco (ya sin tinte)
                            backgroundColor,                    // Opaco sólido
                            backgroundColor                     // Opaco sólido (tapa el texto)
                        )
                    )
                )
                .padding(bottom = 48.dp, top = 80.dp)
        ) {
             Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val haptic = LocalHapticFeedback.current
                // Réplica COMPACTA del transporte del NowPlaying: reproduciendo = play como
                // COOKIE de 9 lados girando (64×64) y laterales círculos tenues; pausa = play a
                // píldora ensanchada (108×64) y laterales a cápsula vertical. El play comparte
                // pieza por pieza con el NowPlaying: [PlayButtonMorphShape] para la forma,
                // [rememberPlayButtonSpin] para el morph + el giro, y [cookieSpinDegrees] para
                // desenroscar el ángulo al pausar. Los laterales siguen con forma fija.
                val isPlaying = playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
                // Transporte alineado con el NowPlaying: play resaltado (primary), laterales
                // tonales (secondaryContainer). Roles del scheme sembrado con el álbum.
                val playContainer = MaterialTheme.colorScheme.primary
                val playContent = MaterialTheme.colorScheme.onPrimary
                val spin = rememberPlayButtonSpin(isPlaying)
                val playMorphProgress by spin.morphProgress
                val cookieAngle = spin.angle
                val playStateDesc = when (playbackState) {
                    PlaybackState.BUFFERING -> stringResource(R.string.np_loading)
                    PlaybackState.PLAYING -> stringResource(R.string.common_pause)
                    else -> stringResource(R.string.common_play)
                }
                val sideContainer = MaterialTheme.colorScheme.secondaryContainer
                val sideContent = MaterialTheme.colorScheme.onSecondaryContainer
                val playWidth by animateDpAsState(
                    targetValue = if (isPlaying) 64.dp else 108.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "lyricsPlayWidth"
                )
                val sideWidth by animateDpAsState(
                    targetValue = if (isPlaying) 48.dp else 44.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "lyricsSideWidth"
                )
                val sideHeight by animateDpAsState(
                    targetValue = if (isPlaying) 48.dp else 64.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "lyricsSideHeight"
                )

                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPrevious()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideContainer,
                        contentColor = sideContent
                    ),
                    modifier = Modifier.width(sideWidth).height(sideHeight)
                ) {
                    MaterialSymbol("skip_previous", size = 26.sp, color = sideContent)
                }

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPause()
                    },
                    shape = PlayButtonMorphShape(playMorphProgress),
                    color = playContainer,
                    modifier = Modifier
                        .height(64.dp)
                        .width(playWidth)
                        // Lambda: solo invalida el draw, cero recomposición por frame del giro.
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
                                scaleIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) togetherWith
                                scaleOut(animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                            },
                            label = "PlayPauseAnimation"
                        ) { state ->
                            when (state) {
                                PlaybackState.BUFFERING ->
                                    // LoadingIndicator expressive (morfea entre MaterialShapes),
                                    // igual que el NowPlaying: era un CircularProgressIndicator.
                                    LoadingIndicator(color = playContent, modifier = Modifier.size(32.dp))
                                PlaybackState.PLAYING ->
                                    MaterialSymbol("pause", size = 28.sp, color = playContent, fill = true)
                                else ->
                                    MaterialSymbol("play_arrow", size = 28.sp, color = playContent, fill = true)
                            }
                        }
                    }
                }

                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNext()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideContainer,
                        contentColor = sideContent
                    ),
                    modifier = Modifier.width(sideWidth).height(sideHeight)
                ) {
                    MaterialSymbol("skip_next", size = 26.sp, color = sideContent)
                }
            }
        }

        // Sheet de búsqueda manual (solo se muestra cuando hay actividad)
        if (isSearchingCandidates || lyricsCandidates != null || lyricsSearchError != null) {
            LyricsSearchSheet(
                isLoading = isSearchingCandidates,
                candidates = lyricsCandidates,
                errorMessage = lyricsSearchError,
                onCandidateSelected = onSelectCandidate,
                // Reintentar relanza la misma búsqueda manual de candidatos (LrcLib).
                onRetry = onSearchManually,
                onDismiss = onDismissSearch
            )
        }
    }
}

/**
 * Acento "seguro": devuelve [accent] si contrasta bien con [background]; si no,
 * cae a [fallback] (blanco/negro) para garantizar legibilidad.
 * Umbral 3.0 ≈ mínimo WCAG para texto grande.
 */
private fun safeAccentColor(accent: Color, background: Color, fallback: Color): Color {
    val contrast = androidx.core.graphics.ColorUtils.calculateContrast(
        accent.toArgb(),
        // calculateContrast exige fondo opaco; albumPrimary debería serlo, pero forzamos.
        androidx.core.graphics.ColorUtils.setAlphaComponent(background.toArgb(), 255)
    )
    return if (contrast >= 3.0) accent else fallback
}

/**
 * Toggle Karaoke/Texto con el SegmentedButton oficial M3 (selección con checkmark
 * animado y springs del MotionScheme). El segmento activo se tiñe con [pillColor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsModeToggle(
    viewMode: LyricsViewMode,
    pillColor: Color,
    contentColor: Color,
    onModeChange: (LyricsViewMode) -> Unit
) {
    // Contenido del segmento activo: negro o blanco según luminancia del acento.
    val activeContentColor = remember(pillColor) {
        if (androidx.core.graphics.ColorUtils.calculateLuminance(pillColor.toArgb()) > 0.5) Color.Black else Color.White
    }
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = pillColor,
        activeContentColor = activeContentColor,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = contentColor.copy(alpha = 0.7f)
    )

    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = viewMode == LyricsViewMode.SYNCED,
            onClick = { onModeChange(LyricsViewMode.SYNCED) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = colors
        ) {
            Text(stringResource(R.string.lyrics_view_karaoke), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        SegmentedButton(
            selected = viewMode == LyricsViewMode.PLAIN,
            onClick = { onModeChange(LyricsViewMode.PLAIN) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = colors
        ) {
            Text(stringResource(R.string.lyrics_plain), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SyncedLyricsView(
    lines: List<LyricLine>,
    currentPosition: Long,
    contentColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    onSeek: (Long) -> Unit
) {
    // Color de la línea activa: acento del álbum con fallback de legibilidad.
    val activeLineColor = remember(accentColor, backgroundColor, contentColor) {
        safeAccentColor(accentColor, backgroundColor, contentColor)
    }

    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPaddingPx = with(density) { SyncedTopPadding.toPx().toInt() }
    val bottomPaddingPx = with(density) { SyncedBottomPadding.toPx().toInt() }

    // Find active line
    val activeIndex by remember(lines, currentPosition) {
        derivedStateOf {
            val index = lines.indexOfLast { it.startTime <= currentPosition }
            if (index < 0) 0 else index
        }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            try {
                // Centrar la línea activa en el área "limpia" (entre header arriba y
                // controles flotantes abajo), NO en el viewport bruto. El bottom padding
                // es mucho mayor que el top, así que centrar respecto al viewport completo
                // dejaba la línea activa más abajo del centro visual percibido.
                //
                // `animateScrollToItem(index, scrollOffset)`: con scrollOffset = 0 el top
                // del item queda en el inicio del padding superior. Negativo lo mueve
                // HACIA ABAJO. Para centrarlo en el área limpia:
                //   offset = -(cleanArea - itemHeight) / 2
                // donde cleanArea = viewport - topPadding - bottomPadding.
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                val cleanArea = viewportHeight - topPaddingPx - bottomPaddingPx
                if (cleanArea > 0 && itemInfo != null) {
                    val centerOffset = -(cleanArea - itemInfo.size) / 2
                    listState.animateScrollToItem(activeIndex, scrollOffset = centerOffset)
                } else {
                    // Item fuera del viewport (ej.: primera carga, salto grande): scroll directo.
                    // El próximo cambio de activeIndex centrará exacto, pero al menos lo trae a la vista.
                    listState.animateScrollToItem(activeIndex)
                }
            } catch (e: Exception) {
                android.util.Log.w("LyricsScreen", "Error scrolling to active line", e)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Bottom incluye altura de los controles + zona de fade colorido + margen
        contentPadding = PaddingValues(top = SyncedTopPadding, bottom = SyncedBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(
            items = lines,
            key = { index, line -> "${line.startTime}_$index" }
        ) { index, line ->
            val isActive = index == activeIndex
            val distance = abs(index - activeIndex)

            // Profundidad tipo "foco": la línea activa va a plena opacidad y las vecinas
            // se atenúan progresivamente según su distancia. Da sensación de enfoque en la
            // línea que suena (estilo Apple Music) en vez de un alpha plano para todas.
            // `contentColor` ya es blanco en dark / negro en light (lo arma NowPlayingScreen),
            // así que controlamos la visibilidad sólo con el alpha del graphicsLayer.
            val targetAlpha = when {
                isActive -> 1f
                distance == 1 -> 0.45f
                distance == 2 -> 0.30f
                else -> 0.20f
            }
            // La activa crece levemente. Usamos SÓLO scale (graphicsLayer), nunca el fontSize,
            // para que no haya reflow vertical y el centrado del scroll quede estable.
            val targetScale = if (isActive) 1.12f else 1f

            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                label = "lyricAlpha"
            )
            val animatedScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                label = "lyricScale"
            )
            // Animamos también el COLOR: sin esto, al perder el foco la línea saltaba de
            // golpe de `activeLineColor` (acento) a `contentColor` (blanco/negro) mientras
            // el alpha aún estaba alto -> destello blanco. Con la transición de color el
            // cambio es gradual y el flash desaparece.
            val animatedColor by animateColorAsState(
                targetValue = if (isActive) activeLineColor else contentColor,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                label = "lyricColor"
            )

            val interactionSource = remember { MutableInteractionSource() }

            Text(
                text = line.text,
                color = animatedColor,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp,
                    fontSize = 22.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (line.startTime >= 0) onSeek(line.startTime)
                    }
                    .graphicsLayer {
                        alpha = animatedAlpha
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .padding(vertical = 12.dp, horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun PlainLyricsView(
    text: String,
    contentColor: Color
) {
    val scrollState = rememberScrollState()
    
    // Clean timestamps [00:00.00] for readability
    val cleanText = remember(text) {
        text.replace(Regex("\\[\\d{2}:\\d{2}(\\.\\d{2,3})?\\] ?"), "")
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 180.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = cleanText,
            color = contentColor,
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        )
    }
}

@Composable
private fun InstrumentalView(contentColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 120.dp), // Subir visualmente para no chocar con controles
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // El glifo escala con el parámetro `size` (fontSize), NO con Modifier.size —
        // ese solo agrandaba la caja dejando el icono en 24sp (default).
        MaterialSymbol("music_note", size = 120.sp, color = contentColor.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.lyrics_badge_instrumental),
            style = MaterialTheme.typography.headlineMedium,
            color = contentColor
        )
    }
}

/**
 * Estado vacío de las letras, con acciones PROPIAS de cada causa ([LyricsFailure]). Antes era un
 * único `SplitButton` con chevron que ofrecía siempre lo mismo — incluido "buscar en Google" sin
 * conexión, o "reintentar" cuando LrcLib ya había dicho que no la tiene.
 *
 * - [LyricsFailure.NOT_FOUND]: reintentar no sirve → buscar alternativas (candidatos de LrcLib)
 *   y buscar en Google.
 * - [LyricsFailure.NO_NETWORK]: ninguna acción de red sirve → solo reintentar (cuando vuelva).
 * - [LyricsFailure.PROVIDER_ERROR]: el fallo puede ser transitorio → reintentar, y Google como
 *   salida. La búsqueda manual también pega contra LrcLib, así que no se ofrece.
 */
@Composable
private fun EmptyStateView(
    failure: LyricsFailure?,
    errorDetail: String?,
    contentColor: Color,
    accentColor: Color,
    onFetchLyrics: () -> Unit,
    onSearchManually: () -> Unit,
    onGoogleSearch: () -> Unit
) {
    val icon = when (failure) {
        LyricsFailure.NO_NETWORK -> "wifi_off"
        LyricsFailure.PROVIDER_ERROR -> "cloud_off"
        else -> "lyrics"
    }
    val title = when (failure) {
        LyricsFailure.NO_NETWORK -> stringResource(R.string.lyrics_error_offline_title)
        LyricsFailure.PROVIDER_ERROR -> stringResource(R.string.lyrics_error_provider_title)
        else -> stringResource(R.string.lyrics_none_available)
    }
    val subtitle = when (failure) {
        LyricsFailure.NO_NETWORK -> stringResource(R.string.lyrics_error_offline_subtitle)
        // El detalle crudo del proveedor si lo hay; si no, el genérico.
        LyricsFailure.PROVIDER_ERROR -> errorDetail ?: stringResource(R.string.lyrics_error_provider_subtitle)
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MaterialSymbol(icon, size = 120.sp, color = contentColor.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = contentColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Botones APILADOS (uno encima del otro): con etiquetas largas en español ("Buscar
        // alternativas" + "Buscar en Google") una fila los apretaba/truncaba. En columna van a
        // ancho completo y respiran.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (failure) {
                LyricsFailure.NO_NETWORK -> {
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                }
                LyricsFailure.PROVIDER_ERROR -> {
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    LyricsActionButton("manage_search", stringResource(R.string.lyrics_search_alternatives), accentColor, primary = true, onClick = onSearchManually, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Botón del estado vacío. Tonal para la acción principal, con contorno para la secundaria. Los
 * colores salen del acento del álbum, no del ColorScheme: el fondo de la pantalla de letras es el
 * de la carátula, y un `primaryContainer` del tema desentonaría.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsActionButton(
    icon: String,
    label: String,
    accentColor: Color,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Button/OutlinedButton REALES (M3 Expressive: shape-morph al presionar) con los colores
    // del acento del álbum en vez de Surface artesanal.
    val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    if (primary) {
        val content = onContainerColor(accentColor)
        Button(
            onClick = onClick,
            modifier = modifier,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = content
            ),
            contentPadding = contentPadding
        ) {
            MaterialSymbol(icon, size = 18.sp, color = content)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
            contentPadding = contentPadding
        ) {
            MaterialSymbol(icon, size = 18.sp, color = accentColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = accentColor)
        }
    }
}
