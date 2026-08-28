package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.data.util.LrcSyntax
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.LyricsSearchSheet
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.maxContrastOn
import com.qhana.siku.ui.components.MIN_STEP_MS
import com.qhana.siku.ui.components.pixelPacedClock
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.LyricLine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appSlowSpatialSpec
import com.qhana.siku.ui.theme.appSlowEffectsSpec
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import androidx.compose.animation.core.tween

private enum class LyricsViewMode {
    SYNCED, PLAIN
}

/**
 * Alto de la zona de desvanecimiento que hay SOBRE los controles flotantes: es el recorrido
 * en el que el gradiente pasa de transparente a opaco. Generoso a propósito — con un recorrido
 * corto el fundido no se lee como tal, sino como una LÍNEA horizontal que corta el texto justo
 * encima de los botones.
 */
/**
 * Interlineado de un verso del karaoke: más aire que el del rol (`TitleLargeLineHeight`
 * = 28sp). Es espacio ENTRE VERSOS y no tipografía — con el del token las líneas se
 * apelmazan y cuesta seguir cuál está sonando.
 */
private val LyricLineHeight = 34.sp

/** Nanosegundos en un milisegundo: la conversión del reloj del barrido. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Transporte flotante de las letras: la MISMA gramática que el del NowPlaying (ver
 * `PlayButtonHeight` y vecinos en NowPlayingControls.kt) en una escala compacta, porque aquí los
 * controles flotan ENCIMA del texto y cada dp de alto tapa un verso.
 *
 * Las tres decisiones son las de allá, y son lo que hay que respetar si se toca esto:
 *  1. **Los ALTOS son fijos**: solo el ANCHO cambia con el estado, que es lo único que el spec mueve
 *     entre variantes de un icon button. Antes los laterales se estiraban a cápsula vertical
 *     (48→64) y eso hacía latir el alto de la fila entera al pausar.
 *  2. **El play mantiene la proporción 1,5:1** en pausa (96×64, como 120×80 allá): es lo que lo hace
 *     leerse como píldora y no como un óvalo.
 *  3. **Los laterales van a la talla VISUAL del play** (20 ago 2026): la cookie de 9 lados es una
 *     estrella con `innerRadius = 0.8` (`MaterialShapes.cookie9()`, leído del jar de material3
 *     1.5.0-alpha24 — no está expuesto como API) inscrita en sus bounds, así que sus lóbulos tocan
 *     el borde pero el cuerpo mide 0,8, y el contorno se percibe en la media de los dos radios. Un
 *     círculo que PESE lo mismo mide 0,9 de esos bounds — 64 × 0,9 = 57,6 → **56** en la rejilla de
 *     4, con el mismo glifo (28). En pausa se estrechan con la proporción narrow/uniform (0,8):
 *     56 × 0,8 = 44,8 → **44**. Antes eran un icon button `Small` (40 de alto, 40/32 de ancho,
 *     icono 24) y se leían como piezas de otro rango.
 *
 *     **Esta escala vive SOLO aquí.** El NowPlaying la usó un día y el 21 ago volvió al icon button
 *     Medium del spec: allá los laterales grandes le quitaban el protagonismo al play y alto a la
 *     carátula, dos problemas que esta pantalla no tiene —sus controles flotan sobre el texto y no
 *     compiten con ninguna portada—, así que aquí se queda.
 *
 * Del play, como en el NowPlaying, solo el ICONO sale de token; sus medidas son propias.
 */
private val LyricsPlayHeight = 64.dp

private val LyricsPlayPlayingWidth = 64.dp

private val LyricsPlayPausedWidth = 96.dp

private val LyricsSideHeight = 56.dp

private val LyricsSideUniformWidth = LyricsSideHeight

private val LyricsSideNarrowWidth = 44.dp

private val LyricsPlayIconSize = 28.sp

private val LyricsSideIconSize = LyricsPlayIconSize

private val ControlsFadeHeight = 132.dp

/** Separación de los controles flotantes respecto al borde inferior de la pantalla. */
private val ControlsBottomInset = 48.dp

// Paddings de SyncedLyricsView. Se reutilizan en `contentPadding` y en el cálculo
// del scroll offset para centrar la línea activa en el área visible "limpia"
// (entre el header y los controles flotantes), no en el viewport bruto.
private val SyncedTopPadding = 32.dp

/**
 * El último verso tiene que poder quedar por ENCIMA del fundido, así que el padding inferior
 * se deriva de la geometría de los controles en vez de fijarse a ojo: si cambia el alto del
 * fade o el inset, el hueco reservado en la lista lo sigue solo.
 */
private val SyncedBottomPadding = ControlsFadeHeight + ControlsBottomInset + 24.dp

/**
 * Puntos con los que se dibuja la rampa del fundido inferior. Van repartidos por IGUAL: la curva
 * la hace el alpha de cada punto (smoothstep), no su posición, que es lo que permite subir la
 * resolución sin recolocar nada a mano.
 */
private const val FadeStopCount = 13

/** Marca que LrcLib devuelve para las pistas sin letra por ser instrumentales. */
private const val INSTRUMENTAL_SENTINEL = "[INSTRUMENTAL]"

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
    /** Abre el guardado de la letra (diálogo o guardado directo, según la preferencia). */
    onSaveLyrics: () -> Unit,
    /** Hay un guardado en curso: el botón se apaga para no dispararlo dos veces. */
    isSavingLyrics: Boolean,
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
    // La posición NO se lee aquí: se pasa el flow hacia abajo y solo la vista sincronizada lo
    // colecta, dentro de su `derivedStateOf`. Leerla en este cuerpo con `by` suscribía la pantalla
    // ENTERA al tick de un segundo (ver [SyncedLyricsView]).

    // Auto-detect mode capability
    val hasSyncedData = remember(lyricLines) { lyricLines.any { it.startTime > 0 } }

    // "Sin letra y sin fallo" NO es "no se encontró": es que NADIE HA BUSCADO TODAVÍA. Al cambiar de
    // canción el ViewModel siembra el estado por-canción desde cero (`lyrics` y `lyricsFailure` a
    // null) y la búsqueda la dispara un `LaunchedEffect` del NowPlaying UN FRAME DESPUÉS, así que
    // leer esa ausencia como estado vacío pintaba "No se encontró la letra" y acto seguido el
    // loading — visible en cada salto con los controles de esta pantalla. Toda salida de
    // `fetchLyrics` deja letra o un `lyricsFailure`, de modo que la ausencia de ambos es PENDIENTE
    // y se dibuja como la carga que ya viene. Solo aquí: con la hoja cerrada ese mismo estado es
    // normal (el prefetch es silencioso) y el toolbar no debe girar por él.
    val isResolving = isLyricsLoading || (lyrics == null && lyricLines.isEmpty() && lyricsFailure == null)

    // Sin letra que mostrar: manda el estado vacío, que YA ofrece las acciones pertinentes a la
    // causa. Los iconos del header (lupa + refresco) se ocultan ahí: serían redundantes, y en el
    // caso sin red directamente engañosos.
    val showEmptyState = !isResolving && lyrics == null && lyricLines.isEmpty()

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
            // En horizontal la perforación cae en un costado: el fondo llega al borde (va antes),
            // el contenido se aparta.
            .safeSidePadding()
    ) {
        // **Ancho acotado y centrado** (27 ago 2026): girado, los versos se dibujaban sobre líneas
        // de ~825dp —el peor caso de medida de línea de la app— y el header dejaba el botón de
        // cerrar y las acciones a media pantalla de distancia. El tope es el mismo que el de las
        // hojas y en vertical no cambia nada.
        //
        // OJO al tocar esto: el karaoke recorta el barrido por ÍNDICE de carácter preguntándole al
        // `TextLayoutResult` dónde cae cada uno, así que cambiar el ancho cambia los saltos de
        // renglón y hay que mirar el resaltado en device.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
        ) {
            
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
                if (hasSyncedData && !lyrics.isNullOrBlank() && !isResolving) {
                    LyricsModeToggle(
                        viewMode = viewMode,
                        pillColor = safeAccent,
                        contentColor = contentColor,
                        onModeChange = { viewMode = it }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Búsqueda manual + guardar. Solo con letra en pantalla: en el estado vacío las
                // acciones las da EmptyStateView, adaptadas a la causa.
                //
                // Ya no hay botón de "volver a buscar": con la búsqueda manual al lado —que además
                // deja ELEGIR la versión en vez de repetir la misma consulta— era el mismo gesto
                // dos veces. Su hueco lo ocupa guardar. `onFetchLyrics` sigue vivo para el estado
                // vacío, donde sí significa algo distinto: "busca, que aquí no hay nada".
                if (!showEmptyState) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpressiveActionIcon(
                            onClick = onSearchManually,
                            icon = "manage_search",
                            description = stringResource(R.string.lyrics_search_manual_desc),
                            contentColor = safeAccent.copy(alpha = if (isResolving) 0.3f else 1f),
                            enabled = !isResolving
                        )
                        // Una canción marcada como instrumental no tiene nada que guardar.
                        val canSave = !isResolving && !isSavingLyrics && lyrics != INSTRUMENTAL_SENTINEL
                        ExpressiveActionIcon(
                            onClick = onSaveLyrics,
                            icon = "save",
                            description = stringResource(R.string.lyrics_save_desc),
                            contentColor = safeAccent.copy(alpha = if (canSave) 1f else 0.3f),
                            enabled = canSave
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
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 120.dp), // Subir visualmente
                        contentAlignment = Alignment.Center
                    ) {
                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                        LoadingIndicator(color = accentColor, modifier = Modifier.size(56.dp))
                    }
                } else if (lyrics == INSTRUMENTAL_SENTINEL) {
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
                    // Spec explícito: el default de `Crossfade` no mira el tema. Y va con los
                    // tokens de TRANSICIÓN (fade through) y no con un spring: lo que se cruza es el
                    // cuerpo entero de la pantalla, no un componente.
                    Crossfade(
                        targetState = viewMode,
                        // Fade through = puro EFFECTS: no se mueve nada, solo cruzan opacidades.
                        animationSpec = tween(
                            EXPRESSIVE_DEFAULT_EFFECTS_MS,
                            easing = ExpressiveDefaultEffectsEasing
                        ),
                        label = "LyricsMode"
                    ) { mode ->
                        when (mode) {
                            LyricsViewMode.SYNCED -> SyncedLyricsView(
                                lines = lyricLines,
                                currentPositionFlow = currentPositionFlow,
                                playbackState = playbackState,
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

        // Controles flotantes al fondo, sobre un fundido que tapa el texto que pasa por detrás.
        //
        // La rampa es de UN SOLO color —el fondo— con alpha creciente y perfil smoothstep. Antes
        // mezclaba a mitad de camino un "glow" con un 25% del acento: eso hace que la luminancia
        // suba y vuelva a bajar, y una rampa no monótona se lee como una FRANJA horizontal justo
        // encima de los controles (se veía en los dos temas; en claro además el acento es tono 40,
        // así que el "glow" ni siquiera aclaraba: ensuciaba). Smoothstep entra y sale con pendiente
        // cero, así que tampoco quedan bandas de Mach en los empalmes.
        val fadeBrush = remember(backgroundColor) {
            Brush.verticalGradient(
                List(FadeStopCount) { i ->
                    val t = i / (FadeStopCount - 1f)
                    backgroundColor.copy(alpha = t * t * (3f - 2f * t))
                }
            )
        }

        // El fundido es su PROPIO bloque y los controles van sobre color SÓLIDO, en vez de un
        // único degradado estirado sobre el conjunto: así el punto en que la rampa llega a opaco
        // coincide EXACTAMENTE con el borde superior de los botones, sin depender del alto que
        // acabe midiendo la fila (que cambia al pausar: los laterales pasan de 48 a 64dp).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ControlsFadeHeight)
                    .background(fadeBrush)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(bottom = ControlsBottomInset),
                // Misma separación que el grupo del NowPlaying (`NowPlayingConfig.GroupSpacing`):
                // es el mismo control, y el 10dp que había era el único valor de los dos transportes
                // fuera de la rejilla de 4 de Material.
                horizontalArrangement = Arrangement.spacedBy(
                    NowPlayingConfig.GroupSpacing,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val haptic = LocalHapticFeedback.current
                // Réplica COMPACTA del transporte del NowPlaying (medidas y porqués en
                // [LyricsPlayHeight]): reproduciendo = play como COOKIE de 9 lados girando (64×64) y
                // laterales círculos tenues (40×40); pausa = play a píldora (96×64) y laterales
                // estrechados (32×40). Los ALTOS no cambian: solo el ancho. El play comparte pieza
                // por pieza con el NowPlaying: [PlayButtonMorphShape] para la forma,
                // [rememberPlayButtonSpin] para el morph + el giro, y [cookieSpinDegrees] para
                // desenroscar el ángulo al pausar. Los laterales siguen con forma fija.
                val isPlaying = playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
                // Transporte alineado con el NowPlaying: play resaltado (primary), laterales
                // tonales (secondaryContainer). Roles del scheme sembrado con el álbum.
                val playContainer = AppColors.primary
                val playContent = AppColors.onPrimary
                val spin = rememberPlayButtonSpin(isPlaying)
                val playMorphProgress by spin.morphProgress
                val cookieAngle = spin.angle
                val playStateDesc = when (playbackState) {
                    PlaybackState.BUFFERING -> stringResource(R.string.np_loading)
                    PlaybackState.PLAYING -> stringResource(R.string.common_pause)
                    else -> stringResource(R.string.common_play)
                }
                val sideContainer = AppColors.secondaryContainer
                val sideContent = AppColors.onSecondaryContainer
                // Mismo token que el transporte del NowPlaying: este bloque es el MISMO control en
                // otra pantalla, y hasta ahora coincidía por tener los números copiados.
                val transportSpec = appSpatialSpec<Dp>()
                val playWidth by animateDpAsState(
                    targetValue = if (isPlaying) LyricsPlayPlayingWidth else LyricsPlayPausedWidth,
                    animationSpec = transportSpec,
                    label = "lyricsPlayWidth"
                )
                val sideWidth by animateDpAsState(
                    targetValue = if (isPlaying) LyricsSideUniformWidth else LyricsSideNarrowWidth,
                    animationSpec = transportSpec,
                    label = "lyricsSideWidth"
                )
                // Izados: `transitionSpec` no es composable (mismo patrón que en PlaybackControls).
                val glyphEnterScale = appSpatialSpec<Float>()
                val glyphExitScale = appFastSpatialSpec<Float>()

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
                    modifier = Modifier.width(sideWidth).height(LyricsSideHeight)
                ) {
                    MaterialSymbol("skip_previous", size = LyricsSideIconSize, color = sideContent, fill = true)
                }

                AppSurface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPause()
                    },
                    shape = PlayButtonMorphShape(playMorphProgress),
                    color = playContainer,
                    modifier = Modifier
                        .height(LyricsPlayHeight)
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
                                scaleIn(glyphEnterScale) togetherWith scaleOut(glyphExitScale)
                            },
                            label = "PlayPauseAnimation"
                        ) { state ->
                            when (state) {
                                PlaybackState.BUFFERING ->
                                    // LoadingIndicator expressive (morfea entre MaterialShapes),
                                    // igual que el NowPlaying: era un CircularProgressIndicator.
                                    LoadingIndicator(color = playContent, modifier = Modifier.size(32.dp))
                                PlaybackState.PLAYING ->
                                    MaterialSymbol("pause", size = LyricsPlayIconSize, color = playContent, fill = true)
                                else ->
                                    MaterialSymbol("play_arrow", size = LyricsPlayIconSize, color = playContent, fill = true)
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
                    modifier = Modifier.width(sideWidth).height(LyricsSideHeight)
                ) {
                    MaterialSymbol("skip_next", size = LyricsSideIconSize, color = sideContent, fill = true)
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
 * Toggle Karaoke/Texto con el connected button group de M3 Expressive (el segmentado que usaba
 * antes dejó de estar recomendado). El botón activo se tiñe con [pillColor] y morfea de forma;
 * los springs los pone el `MotionScheme` del tema.
 */
@Composable
private fun LyricsModeToggle(
    viewMode: LyricsViewMode,
    pillColor: Color,
    contentColor: Color,
    onModeChange: (LyricsViewMode) -> Unit
) {
    // Contenido del segmento activo: blanco/negro por CONTRASTE real (`maxContrastOn`), NO por el
    // umbral de luminancia 0.5. Sobre un acento medio (0.18 < L < 0.5) aquel umbral elegía blanco
    // cuando el negro contrasta el doble, así que "Karaoke" salía claro sobre la píldora clara del
    // acento mientras el play, del mismo color, iba con glifo oscuro — la inconsistencia reportada.
    val activeContentColor = remember(pillColor) { maxContrastOn(pillColor) }
    // Connected button group (el segmentado dejó de recomendarse en M3 Expressive). El fondo de
    // esta pantalla es `surfaceContainer` (rol del scheme), así que el segmento INACTIVO usa roles
    // del scheme y NO un alpha sobre el contenido: contenedor tenue `surfaceContainerHighest` (un
    // escalón por encima del fondo, para leerse como botón sin competir con la píldora activa) y
    // contenido `onSurfaceVariant`. La píldora ACTIVA sí es el acento dinámico del álbum (`pillColor`),
    // con su contenido decidido por contraste unas líneas más arriba.
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = AppColors.surfaceContainerHighest,
        contentColor = AppColors.onSurfaceVariant,
        checkedContainerColor = pillColor,
        checkedContentColor = activeContentColor
    )

    ConnectedChoiceGroup(
        options = LYRICS_VIEW_MODES,
        selected = viewMode,
        onSelect = onModeChange,
        labelFor = {
            when (it) {
                LyricsViewMode.SYNCED -> stringResource(R.string.lyrics_view_karaoke)
                LyricsViewMode.PLAIN -> stringResource(R.string.lyrics_plain)
            }
        },
        fillWidth = false,
        colors = colors
    )
}

/** Modos de visualización de la letra, en el orden del conmutador. */
private val LYRICS_VIEW_MODES = listOf(LyricsViewMode.SYNCED, LyricsViewMode.PLAIN)

@Composable
private fun SyncedLyricsView(
    lines: List<LyricLine>,
    currentPositionFlow: StateFlow<Long>,
    /**
     * Estado de reproducción, SOLO para saber si la posición avanza sola: es lo que decide si el
     * barrido del karaoke se interpola entre ticks o se limita a seguir los saltos. No es el
     * `isPlaying` de los controles, que cuenta `BUFFERING` como "sonando" para no parpadear el
     * botón — mientras se rellena el búfer la canción NO avanza, y extrapolar ahí adelantaría el
     * resaltado sobre lo que se oye.
     */
    playbackState: PlaybackState,
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

    // --- Karaoke palabra a palabra -------------------------------------------------------------
    //
    // Solo lo tienen las letras que traen tiempos por palabra (enhanced LRC), o sea las que el
    // usuario aporta en un `.lrc` o en los tags de su archivo: LrcLib sincroniza por verso y ahí
    // esto no se enciende ni cuesta nada.
    val hasWordTiming = remember(lines) { lines.any(LyricLine::hasWordTiming) }

    // Posición interpolada del barrido. `mutableLongState` y no un parámetro: sus DOS lectores son
    // diferidos —el `drawWithContent` del verso activo y el `derivedStateOf` del verso en curso—,
    // así que moverla invalida el dibujo de UN `Text` y nada más. Ningún composable la lee directa,
    // que es lo que la separa del tick de posición y evita recomponer la lista a 120 Hz.
    //
    // Arranca en la posición real y no en cero: es el valor del primer frame, antes de que el efecto
    // de abajo llegue a correr, y con un cero el karaoke abriría en el verso 1 para saltar acto
    // seguido al que suena.
    val karaokeMillis = remember { mutableLongStateOf(currentPositionFlow.value) }

    // **Éste es el ÚNICO reloj de la app al que la regla del píxel no le compra nada, y va con el
    // suelo a propósito.** Los otros tres calculan su intervalo con `stepMillisFor` porque mueven
    // uno o dos píxeles por segundo y así duermen entre medias; el barrido de un verso recorre
    // cientos, de modo que ese cálculo devuelve siempre menos de un frame y `coerceAtLeast` lo sube
    // igualmente a [MIN_STEP_MS]. Dárselo ya calculado sería un número que no llega a usarse
    // fingiendo que decide algo.
    //
    // Lo que sí acota el gasto es el GATE: sin tiempos por palabra no corre, en pausa tampoco, y con
    // el reproductor guardado tampoco. Fuera de esos tres casos este reloj no existe.
    val onScreen = LocalPlayerOnScreen.current
    LaunchedEffect(hasWordTiming, playbackState, onScreen, currentPositionFlow) {
        if (!hasWordTiming) return@LaunchedEffect
        // ¿Avanza la canción sola? Es lo único que decide si hay algo que interpolar. Con el
        // reproductor guardado detrás de la biblioteca tampoco: nadie está mirando el barrido.
        val advancing = playbackState == PlaybackState.PLAYING && onScreen
        if (!advancing) {
            // En reposo el barrido no se interpola, pero SÍ tiene que seguir los saltos: en pausa
            // se puede buscar, y el resaltado tiene que aterrizar donde aterrice la canción.
            currentPositionFlow.collect { karaokeMillis.longValue = it }
        } else {
            coroutineScope {
                var baseMillis = currentPositionFlow.value
                var baseNanos = System.nanoTime()
                // El tick de posición llega una vez por segundo, y cada uno REANCLA la
                // extrapolación: es lo que impide que el barrido acumule deriva a lo largo de la
                // canción y acabe adelantado respecto a lo que se oye.
                launch {
                    currentPositionFlow.collect {
                        baseMillis = it
                        baseNanos = System.nanoTime()
                    }
                }
                pixelPacedClock(MIN_STEP_MS) { now ->
                    karaokeMillis.longValue = baseMillis + (now - baseNanos) / NANOS_PER_MILLI
                }
            }
        }
    }

    // Línea activa.
    //
    // `collectAsStateWithLifecycle()` SIN `by`: se quiere el `State`, no su valor. Leerlo aquí
    // suscribiría este composable al tick de la posición —una vez por segundo— y con él el bloque
    // de contenido del `LazyColumn`, o sea las ~8 líneas visibles, cada una con sus tres
    // `animate*AsState` de alpha, escala y color, y todo eso mientras el auto-scroll está corriendo.
    //
    // Leído SOLO dentro del `derivedStateOf`, la suscripción vive en el derivado y no en el
    // composable, así que `activeIndex` solo notifica cuando de verdad cambia de línea: una vez cada
    // varios segundos en vez de una por segundo. Eso es exactamente para lo que existe
    // `derivedStateOf`, y antes no lo conseguía — llevaba la posición como CLAVE del `remember` y a
    // la vez la leía dentro, así que el derivado se destruía y se reconstruía en cada tick sin
    // cachear nada. La clave correcta es el `State` (identidad estable) y no su valor.
    val position = currentPositionFlow.collectAsStateWithLifecycle()
    val activeIndex by remember(lines, position, karaokeMillis, hasWordTiming) {
        derivedStateOf {
            // **Con tiempos por palabra el verso activo lo marca el reloj del BARRIDO**, no el tick
            // de un segundo. Si no, el resaltado llegaría al final del verso y se quedaría parado
            // hasta un segundo esperando a que el foco y el scroll lo siguieran — un desfase que
            // sin karaoke no se nota (el foco salta de verso en verso y no hay nada que lo delate) y
            // con él es justo lo que se está mirando.
            //
            // El cambio de ritmo lo absorbe el propio `derivedStateOf`: recalcula al ritmo del reloj
            // —un `indexOfLast` sobre la lista de versos, nada— pero solo NOTIFICA cuando el índice
            // cambia de verdad, o sea las mismas veces que antes.
            val millis = if (hasWordTiming) karaokeMillis.longValue else position.value
            val index = lines.indexOfLast { it.startTime <= millis }
            if (index < 0) 0 else index
        }
    }

    // ¿Es el PRIMER posicionamiento tras abrir la pantalla? Ése va sin animar (ver el bloque de
    // abajo). Un array y no un `mutableStateOf`: solo lo lee y escribe la corrutina del efecto, así
    // que no hace falta estado de snapshot — mismo patrón que `firstComposition` en `NowPlayingRoute`.
    // Se reinicia solo en cada apertura, porque la hoja descompone su contenido al cerrarse.
    val firstPositioning = remember { booleanArrayOf(true) }
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
                // **El PRIMERO va sin animar, y no es un detalle.** Al abrir las letras esta
                // pantalla está subiendo desde abajo, así que un scroll ANIMADO hacia la línea que
                // suena corre a la vez que la hoja entra: el `LazyColumn` compone y mide items en
                // cada frame de la animación (medido en release el 19 ago: 6 composiciones de item,
                // 3 de ellas `prefetch:execute:urgent` —o sea que no llegaron a tiempo y hubo que
                // hacerlas dentro del frame— y 5,6 ms de trabajo en la ventana de 430 ms). Y encima
                // no se ve: nadie está mirando cómo viaja el scroll de una pantalla que aún no ha
                // llegado. Colocado de golpe, la línea que suena ya está centrada cuando la pantalla
                // asienta, que es lo que el usuario esperaba ver.
                //
                // Solo el primero: a partir de ahí el desplazamiento SÍ se ve y es lo que hace que el
                // karaoke se lea como una atención que se mueve y no como un salto.
                val animateThisOne = !firstPositioning[0]
                firstPositioning[0] = false
                if (cleanArea > 0 && itemInfo != null) {
                    val centerOffset = -(cleanArea - itemInfo.size) / 2
                    if (animateThisOne) {
                        listState.animateScrollToItem(activeIndex, scrollOffset = centerOffset)
                    } else {
                        listState.scrollToItem(activeIndex, scrollOffset = centerOffset)
                    }
                } else {
                    // Item fuera del viewport (ej.: primera carga, salto grande): scroll directo.
                    // El próximo cambio de activeIndex centrará exacto, pero al menos lo trae a la vista.
                    if (animateThisOne) {
                        listState.animateScrollToItem(activeIndex)
                    } else {
                        listState.scrollToItem(activeIndex)
                    }
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

            // Tokens *slow* (los más blandos del scheme): el foco del karaoke tiene que sentirse
            // como un desplazamiento de atención, no como un cambio de estado. Alpha y color van
            // por `slowEffects` —y por eso cierran juntos, que es lo que evita el destello descrito
            // abajo— y la escala por `slowSpatial`, que es el que corresponde a una transformación.
            val focusFade = appSlowEffectsSpec<Float>()
            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = focusFade,
                label = "lyricAlpha"
            )
            val animatedScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = appSlowSpatialSpec(),
                label = "lyricScale"
            )
            // Animamos también el COLOR: sin esto, al perder el foco la línea saltaba de
            // golpe de `activeLineColor` (acento) a `contentColor` (blanco/negro) mientras
            // el alpha aún estaba alto -> destello blanco. Con la transición de color el
            // cambio es gradual y el flash desaparece.
            //
            // **En un verso que se barre palabra a palabra el destino es `contentColor`, no el
            // acento**: ahí el acento no lo pinta esta animación sino el barrido, y teñir además el
            // verso entero al activarse dejaría el resaltado sin nada contra lo que leerse — se vería
            // el acento sobre el acento. El destello que este `animateColor` evita tampoco puede
            // ocurrir en ese caso, porque el color de base ya es el mismo activo que inactivo.
            val sweeping = isActive && line.hasWordTiming
            val animatedColor by animateColorAsState(
                targetValue = if (isActive && !sweeping) activeLineColor else contentColor,
                animationSpec = appSlowEffectsSpec(),
                label = "lyricColor"
            )

            val interactionSource = remember { MutableInteractionSource() }

            // Layout del verso ya compuesto: de él sale DÓNDE cae cada carácter, que es lo que
            // convierte un tiempo por palabra en una x de la pantalla. Solo se guarda en los versos
            // que se pueden barrer; en los demás el `Text` no paga ni el callback.
            val textLayout = remember(line) { mutableStateOf<TextLayoutResult?>(null) }
            // Un `Path` por verso, reutilizado: el barrido lo reescribe en cada frame y asignar uno
            // nuevo ahí dentro sería una asignación por frame en el hilo de dibujo.
            val sungPath = remember(line) { Path() }

            // El verso es `titleLarge`, que YA son los 22sp que aquí se ponían a mano sobre un
            // `headlineSmall` (24sp) — o sea que de aquel rol no quedaba nada salvo la familia. El
            // realce de la línea en curso va por el par Regular/Emphasized del propio rol
            // (400 → 500) en vez de por dos pesos elegidos a ojo, y se suma al color, la opacidad y
            // la escala que ya se animan unas líneas más arriba.
            //
            // Lo ÚNICO que sigue apartándose del token es el `lineHeight` (34 frente a 28): es aire
            // ENTRE VERSOS, no tipografía — con el interlineado del rol las líneas del karaoke se
            // apelmazan y cuesta seguir cuál está sonando.
            val verseStyle = (
                if (isActive) MaterialTheme.typography.titleLargeEmphasized
                else MaterialTheme.typography.titleLarge
            ).copy(lineHeight = LyricLineHeight)

            // El verso se dibuja DOS VECES cuando se barre: abajo el texto normal y encima el mismo
            // texto en acento, recortado a lo ya cantado. Es la forma de que el resaltado corte a
            // mitad de palabra sin partir el texto en trozos — un `Text` por palabra reordenaría los
            // saltos de renglón y haría bailar el verso entero cada vez que avanza el barrido.
            //
            // Las dos copias comparten `verseStyle` y ancho, así que sus layouts son el MISMO: por
            // eso basta con medir una y el recorte cae exactamente sobre los glifos de la otra.
            Box(
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
                        // **`ModulateAlpha` y no el default `Auto`, que era el coste de dibujo más
                        // caro de la app** (medido con Perfetto en release el 19 ago). Con `Auto`, un
                        // `alpha < 1` obliga a HWUI a rasterizar el contenido en un buffer OFFSCREEN
                        // y luego componerlo — y aquí eso ocurre en TODAS las líneas menos la activa,
                        // que son las que llevan alpha 0.45/0.30/0.20. En el trace salían ~10
                        // `alpha caused saveLayer` de ANCHO COMPLETO por frame (1280×190 las líneas de
                        // un renglón, 1280×302 las de dos), o sea diez render passes extra en cada
                        // frame que se redibuje: durante la animación de apertura, durante el scroll y
                        // durante toda la reproducción con las letras abiertas, que es donde el
                        // usuario pasa los minutos.
                        //
                        // `ModulateAlpha` aplica la opacidad a los draw ops directamente, sin buffer.
                        // Su única pega es que da un resultado distinto si el contenido se SOLAPA
                        // consigo mismo. **Desde el karaoke sí se solapa** —el verso barrido son dos
                        // `Text` encajados—, pero solo en el ACTIVO, que va a alpha 1: ahí modular no
                        // hace nada y las dos copias se componen como si no hubiera capa. Los versos
                        // con alpha < 1 nunca llevan la segunda copia, así que el caso malo no existe.
                        // Pierde también el recorte implícito a los bounds que trae la capa offscreen
                        // — irrelevante aquí por lo mismo: el que se agranda (`scale` 1.12) es el
                        // activo, y ése no tiene capa.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .padding(vertical = 12.dp, horizontal = 32.dp)
            ) {
                Text(
                    text = line.text,
                    color = animatedColor,
                    textAlign = TextAlign.Center,
                    style = verseStyle,
                    modifier = Modifier.fillMaxWidth(),
                    // Medir cuesta lo mismo, pero guardarlo solo lo piden los versos que se barren.
                    onTextLayout = { if (line.hasWordTiming) textLayout.value = it }
                )

                if (sweeping) {
                    Text(
                        text = line.text,
                        color = activeLineColor,
                        textAlign = TextAlign.Center,
                        style = verseStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                val layout = textLayout.value ?: return@drawWithContent
                                // Lectura del reloj en fase de DIBUJO: mover el barrido invalida
                                // este `Text` y nada más — ni el item, ni la lista, ni la pantalla.
                                val hasSung = buildSungPath(
                                    sungPath, layout, line, karaokeMillis.longValue
                                )
                                if (!hasSung) return@drawWithContent
                                val content = this
                                clipPath(sungPath) { content.drawContent() }
                            }
                    )
                }
            }
        }
    }
}

/**
 * Escribe en [path] la parte del verso que YA se cantó en [positionMillis], y devuelve si había
 * algo que pintar.
 *
 * El recorte no es un rectángulo porque un verso puede ocupar dos renglones: los anteriores al del
 * cursor van enteros y el del cursor se corta por su x. Con un solo rectángulo de ancho creciente,
 * un verso de dos renglones se iluminaría por columnas en vez de por lectura.
 */
private fun buildSungPath(
    path: Path,
    layout: TextLayoutResult,
    line: LyricLine,
    positionMillis: Long
): Boolean {
    val offset = line.sungOffsetAt(positionMillis)
    if (offset <= 0f) return false

    path.rewind()
    val lastOffset = line.text.length
    val cursorLine = layout.getLineForOffset(offset.toInt().coerceIn(0, lastOffset))
    for (index in 0 until cursorLine) {
        path.addRect(
            Rect(
                left = layout.getLineLeft(index),
                top = layout.getLineTop(index),
                right = layout.getLineRight(index),
                bottom = layout.getLineBottom(index)
            )
        )
    }
    path.addRect(
        Rect(
            left = layout.getLineLeft(cursorLine),
            top = layout.getLineTop(cursorLine),
            right = layout.cursorX(offset, cursorLine, lastOffset),
            bottom = layout.getLineBottom(cursorLine)
        )
    )
    return true
}

/**
 * Cuántos caracteres del verso se han cantado en [positionMillis], con decimales: la parte
 * fraccionaria es lo que hace que el resaltado ATRAVIESE la palabra en curso en vez de encenderla
 * de golpe.
 *
 * En los huecos entre palabras el resultado se queda en el final de la última cantada, que es lo
 * correcto: el archivo no dice que ahí se cante nada.
 */
private fun LyricLine.sungOffsetAt(positionMillis: Long): Float {
    val word = words.lastOrNull { it.startTime <= positionMillis } ?: return 0f
    return word.startIndex + (word.endIndex - word.startIndex) * word.progressAt(positionMillis)
}

/**
 * La x donde cae un [offset] con decimales, interpolando entre el carácter y el siguiente.
 *
 * Si el siguiente ya está en otro renglón su x RETROCEDE (empieza por la izquierda), y entonces se
 * corta en el borde del renglón actual: lo que falta lo pinta el renglón de abajo en cuanto el
 * cursor llegue.
 */
private fun TextLayoutResult.cursorX(offset: Float, line: Int, lastOffset: Int): Float {
    val index = offset.toInt().coerceIn(0, lastOffset)
    val from = getHorizontalPosition(index, usePrimaryDirection = true)
    val nextIndex = (index + 1).coerceAtMost(lastOffset)
    val to = getHorizontalPosition(nextIndex, usePrimaryDirection = true)
    if (to < from || getLineForOffset(nextIndex) != line) return getLineRight(line).coerceAtLeast(from)
    return from + (to - from) * (offset - index)
}

@Composable
private fun PlainLyricsView(
    text: String,
    contentColor: Color
) {
    val scrollState = rememberScrollState()

    // La letra corrida es la MISMA de la vista sincronizada, sin ninguna de sus marcas. Quien sabe
    // cuáles son es [LrcSyntax] y nadie más: aquí vivía una copia del regex que solo conocía las de
    // línea, así que un archivo con tiempos por palabra pintaba los `<00:00.04>` como si fueran
    // letra — y en modo karaoke ese mismo archivo se ve perfecto, que es lo que hacía difícil de
    // creer el informe.
    val cleanText = remember(text) { LrcSyntax.stripTags(text) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = SyncedBottomPadding, start = 24.dp, end = 24.dp),
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
        // Icono decorativo de estado vacío → rol `outline`, NO un alpha sobre `onSurface`
        // (jerarquía por rol, no por opacidad).
        MaterialSymbol("music_note", size = 120.sp, color = AppColors.outline)
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
        // Jerarquía por ROL, no por alpha: icono decorativo → `outline`; título (mensaje
        // principal) → `contentColor` pleno; subtítulo → `onSurfaceVariant`.
        MaterialSymbol(icon, size = 120.sp, color = AppColors.outline)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = contentColor,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant,
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
        // Contenido sobre el acento: `maxContrastOn` (contraste WCAG real), no el umbral de
        // luminancia 0.5, por el mismo motivo que el toggle Karaoke y el icono del chip.
        val content = maxContrastOn(accentColor)
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
