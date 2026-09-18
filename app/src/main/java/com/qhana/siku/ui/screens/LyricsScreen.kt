package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.data.util.LrcSyntax
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ChoiceGroupWidth
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.LyricsSearchSheet
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.MIN_STEP_MS
import com.qhana.siku.ui.components.pixelPacedClock
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.LyricLine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appOutlinedButtonBorder
import com.qhana.siku.ui.theme.appOutlinedButtonColors
import com.qhana.siku.ui.theme.appTextButtonColors
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
 * Transporte flotante de las letras: **el del NowPlaying a 0,8**, redondeado a la rejilla de 4.
 *
 * Es una versión más chica A PROPÓSITO: estos controles flotan ENCIMA del texto y cada dp de alto
 * tapa un verso, mientras que allá tienen una fila para ellos solos. Lo que se copia son las
 * RELACIONES —play contra lateral, alto contra ancho, glifo contra botón—, nunca los valores. Se
 * probó igualarlos y se revirtió el mismo día (31 ago 2026).
 *
 * Las decisiones que hay que respetar si se toca esto:
 *  1. **Los ALTOS son fijos**: solo el ANCHO cambia con el estado, que es lo único que el spec mueve
 *     entre variantes de un icon button. Antes los laterales se estiraban a cápsula vertical
 *     (48→64) y eso hacía latir el alto de la fila entera al pausar.
 *  2. **Un solo factor para todo.** Cambiar una medida suelta es lo que ya rompió esto una vez: los
 *     laterales estuvieron a 56 —el valor ABSOLUTO de allá— junto a un play de 64, y la relación
 *     play/lateral caía a 1,14 contra 1,43. Con los laterales casi tan altos como el play, éste
 *     dejaba de leerse como la acción principal; se reportó como "el play se ve chico" y no lo era.
 *  3. **NO devolver los laterales a 56.** Estuvieron así desde el 20 ago derivados del PESO VISUAL
 *     de la cookie: la de 9 lados es una estrella con `innerRadius = 0.8`
 *     (`MaterialShapes.cookie9()`, leído del jar de material3 1.5.0-alpha24 — no está expuesto como
 *     API) inscrita en sus bounds, así que sus lóbulos tocan el borde pero el cuerpo mide 0,8 y el
 *     contorno se percibe en la media de los dos radios; un círculo que PESE lo mismo mide 0,9 de
 *     esos bounds, y 64 × 0,9 ≈ 56. La cuenta es correcta y el CRITERIO es el equivocado: iguala el
 *     bulto de las tres piezas, que es justo lo que borra la jerarquía entre una acción principal y
 *     dos secundarias. El NowPlaying recorrió lo mismo —subió los laterales a la talla del play el
 *     20 ago y lo revirtió el 21— por esta razón.
 *
 * Del play, como en el NowPlaying, solo el ICONO sale de token; el glifo del lateral es una talla
 * menor que el suyo, igual que allá (24 contra 32).
 */
/** 80 × 0,8. */
private val LyricsPlayHeight = 64.dp

/** 80 × 0,8. */
private val LyricsPlayPlayingWidth = 64.dp

/** 120 × 0,8; conserva la proporción 1,5:1 que lo hace píldora y no óvalo. */
private val LyricsPlayPausedWidth = 96.dp

/** 56 × 0,8 = 44,8. */
private val LyricsSideHeight = 44.dp

private val LyricsSideUniformWidth = LyricsSideHeight

/** 48 × 0,8 = 38,4. */
private val LyricsSideNarrowWidth = 40.dp

/** 32 × 0,8 = 25,6. */
private val LyricsPlayIconSize = 26.sp

/**
 * 24 × 0,8 = 19,2.
 *
 * **El glifo escala con su botón y no se queda quieto**, y tampoco es el del play — allá tampoco lo
 * es (24 contra 32): el lateral es una pieza menor. Los dos compartían aquí el mismo tamaño, así
 * que al bajar el lateral a 44 un glifo de 28 habría ocupado 0,64 del contenedor contra el 0,43 de
 * allá: apretado contra el borde, y con el mismo peso visual que el del play.
 */
private val LyricsSideIconSize = 20.sp

private val ControlsFadeHeight = 132.dp

/** Separación de los controles flotantes respecto al borde inferior de la pantalla. */
private val ControlsBottomInset = 48.dp

/**
 * Costado de la fila de controles: el mismo que el de la cabecera, para que el corrector y la
 * búsqueda queden a plomo con el cerrar y el guardar de arriba.
 */
private val ControlsSideInset = 16.dp

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
    /** Compensación aplicada, en ms: positivo = la letra sale más tarde. */
    lyricsShiftMs: Long,
    /** Corre la letra N ms; `null` la deja a cero. */
    onShiftLyrics: (Long?) -> Unit,
    /** Escribe ya el ajuste pendiente. Se llama al salir de la pantalla; ver el `DisposableEffect`. */
    onFlushLyricsShift: () -> Unit,
    onDismissSearch: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    accentColor: Color,
    /**
     * Contenido sobre [accentColor], DECIDIDO POR QUIEN LO PINTA (el play del NowPlaying) y no aquí.
     *
     * El acento de esta pantalla es el mismo `primary` que el botón de play que hay debajo, así que
     * las piezas que lo usan de relleno —la píldora del toggle, el botón de acción del estado
     * vacío— no pueden elegir su contenido por su cuenta: cuando lo hacían (`maxContrastOn`, o sea
     * blanco/negro por contraste bruto) la etiqueta salía NEGRA sobre el mismo azul donde el play
     * llevaba glifo BLANCO. A tono ~50 los dos lados están empatados (4,65:1 el negro, 4,52:1 el
     * blanco), y ese empate no se puede resolver dos veces por separado.
     */
    accentContentColor: Color,
    modifier: Modifier = Modifier
) {
    // La posición NO se lee aquí: se pasa el flow hacia abajo y solo la vista sincronizada lo
    // colecta, dentro de su `derivedStateOf`. Leerla en este cuerpo con `by` suscribía la pantalla
    // ENTERA al tick de un segundo (ver [SyncedLyricsView]).

    // El ajuste de sincronía se escribe con retraso para no invalidar la tabla `songs` en cada
    // toque (ver `PlaybackViewModel.shiftLyrics`), y salir de aquí es el final real de esa
    // interacción. Va en el `onDispose` y no colgado del botón de cerrar porque hay más salidas
    // —el gesto de atrás, cambiar de canción, cerrar el reproductor— y todas tienen que volcar.
    // La lambda se lee con `rememberUpdatedState`: el efecto no se relanza nunca, así que capturar
    // la del primer frame lo dejaría llamando a un callback viejo.
    val flushShift by rememberUpdatedState(onFlushLyricsShift)
    DisposableEffect(Unit) { onDispose { flushShift() } }

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

    // **El acento y su contenido llegan TAL CUAL del reproductor**: `primary` y `onPrimary` (ver el
    // caller en `NowPlayingScreen`), el par que M3 tabula.
    //
    // Hubo un `safeAccentColor` que comprobaba 3,0:1 contra el fondo y, si no llegaba, caía a
    // `contentColor`; y un `maxContrastOn` que reponía el contenido en blanco o negro para ese caso.
    // La guarda no podía saltar nunca: **el fondo de esta pantalla es `surfaceContainer`**, un rol,
    // y con el color spec 2021 el tono de los dos es una constante — peor caso medido sobre los
    // cuatro esquemas y los dos temas, 5,51:1. Era una rama muerta que además obligaba a resolver
    // el contenido por contraste en vez de por el par.
    val safeAccent = accentColor
    val onSafeAccent = accentContentColor

    // State initialization
    var viewMode by remember { mutableStateOf(if (hasSyncedData) LyricsViewMode.SYNCED else LyricsViewMode.PLAIN) }

    // Ajuste de sincronía: cerrado por defecto y por canción. Es una corrección puntual, no un
    // control de uso continuo, así que dejarlo abierto le robaría alto a la letra para siempre.
    var showTiming by remember(songId) { mutableStateOf(false) }

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
                        activeContentColor = onSafeAccent,
                        onModeChange = { viewMode = it }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // **Arriba solo queda GUARDAR** (device, 31 ago 2026). El corrector de sincronía y
                // la búsqueda manual se fueron ABAJO, flanqueando el transporte: los tres juntos
                // aquí llenaban la barra al lado del conmutador de vista, mientras abajo había una
                // fila con el play centrado y los dos costados vacíos. Guardar se queda porque es
                // el único de los tres que actúa sobre el ARCHIVO y no sobre lo que se está viendo.
                //
                // Solo con letra en pantalla: en el estado vacío las acciones las da
                // `EmptyStateView`, adaptadas a la causa. Y no hay botón de "volver a buscar" — con
                // la búsqueda manual, que además deja ELEGIR la versión, era el mismo gesto dos
                // veces; `onFetchLyrics` sigue vivo para el estado vacío, donde sí significa otra
                // cosa: "busca, que aquí no hay nada".
                if (!showEmptyState) {
                    // Una canción marcada como instrumental no tiene nada que guardar.
                    val canSave = !isResolving && !isSavingLyrics && lyrics != INSTRUMENTAL_SENTINEL
                    ExpressiveActionIcon(
                        onClick = onSaveLyrics,
                        icon = "save",
                        description = stringResource(R.string.lyrics_save_desc),
                        // Sin alfa a mano: `FilledIconButton` deshabilitado ya usa el
                        // `disabledContentColor` que M3 tabula, así que el `copy(alpha = …)` que
                        // había aquí no llegaba a aplicarse.
                        contentColor = safeAccent,
                        enabled = canSave
                    )
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
                        accentContentColor = onSafeAccent,
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
            // El panel del corrector, PEGADO a su disparador: el toggle vive ahora abajo, así que
            // abrirlo arriba dejaría el mando y su efecto en dos extremos de la pantalla.
            AnimatedVisibility(
                visible = showTiming && hasSyncedData,
                // Mismo fondo OPACO que la fila de controles: la barra ya no flota sobre la letra,
                // es parte del área de mandos. Sin esto quedaba una franja de versos asomando entre
                // la píldora y el transporte, que se lee como un fallo de dibujo y no como un
                // elemento flotante.
                modifier = Modifier.background(backgroundColor)
            ) {
                LyricsTimingBar(
                    shiftMs = lyricsShiftMs,
                    onShift = onShiftLyrics,
                    accentColor = safeAccent
                )
            }

            // **`Box` y no una fila de tres huecos**: así el play sigue centrado en la PANTALLA y no
            // en el espacio que dejen los laterales, que es lo que pasaría repartiendo pesos —y el
            // transporte cambia de ancho al reproducir/pausar, de modo que el centro se movería.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    // El MISMO costado que la barra de arriba (`ControlsSideInset`): mientras el
                    // transporte era el único hijo iba centrado y el costado daba igual, pero los
                    // dos flanqueantes se alinean a los BORDES y sin esto quedaban pegados al canto
                    // de la pantalla — el toggle marcado, que lleva contenedor lleno, se leía
                    // directamente como cortado.
                    // `start`/`end`/`bottom` y no `horizontal` + `bottom`: son sobrecargas
                    // DISTINTAS de `padding` y no se pueden mezclar en la misma llamada.
                    .padding(
                        start = ControlsSideInset,
                        end = ControlsSideInset,
                        bottom = ControlsBottomInset
                    )
            ) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center),
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

            // Corrector de sincronía, al COSTADO del transporte. Solo con letra sincronizada:
            // sobre texto plano no hay ningún tiempo que correr.
            //
            // **Toggle de M3 y no un icono al que se le baja el alfa**: esto tiene ESTADO —el panel
            // abierto, o un ajuste puesto con el panel cerrado— y M3 ya tabula cómo se ve un toggle
            // marcado (contenedor lleno y shape-morph). Bajarle la opacidad al glifo sería inventar
            // un número para decir lo que el componente dice solo, y por el camino que este
            // proyecto tiene prohibido: la jerarquía va por TONO, no por alfa.
            if (hasSyncedData) {
                // Fuera del `semantics {}`, que no es un ámbito composable.
                val timingDescription = stringResource(R.string.lyrics_timing_desc)
                FilledIconToggleButton(
                    checked = showTiming,
                    onCheckedChange = { showTiming = !showTiming },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        // El juego COMPLETO de roles visibles, que es la convención de
                        // `AppComponentColors`: pasar solo el par marcado dejaría el estado normal
                        // —el que casi siempre se ve— con el color del tema base en vez del acento.
                        containerColor = Color.Transparent,
                        contentColor = safeAccent,
                        checkedContainerColor = safeAccent,
                        checkedContentColor = backgroundColor
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(ACTION_ICON_SIZE)
                        .semantics { contentDescription = timingDescription }
                ) {
                    MaterialSymbol("schedule", size = LyricsFlankIconSize)
                }
            }

            // Búsqueda manual, al otro costado. Solo con letra en pantalla: en el estado vacío la
            // ofrece `EmptyStateView`, con el texto adaptado a la causa.
            if (!showEmptyState) {
                ExpressiveActionIcon(
                    onClick = onSearchManually,
                    icon = "manage_search",
                    description = stringResource(R.string.lyrics_search_manual_desc),
                    contentColor = safeAccent,
                    enabled = !isResolving,
                    iconSize = LyricsFlankIconSize,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
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
 * Toggle Karaoke/Texto con el connected button group de M3 Expressive (el segmentado que usaba
 * antes dejó de estar recomendado). El botón activo se tiñe con [pillColor] y morfea de forma;
 * los springs los pone el `MotionScheme` del tema.
 */
@Composable
private fun LyricsModeToggle(
    viewMode: LyricsViewMode,
    pillColor: Color,
    /**
     * Contenido del segmento ACTIVO. Llega decidido desde fuera y no se calcula aquí: la píldora es
     * el mismo color que el play del transporte, y dos piezas del mismo color no pueden resolver por
     * separado un empate que a tono ~50 se juega en centésimas. Lo hizo dos veces, y cada vez el
     * arreglo dejó al otro lado mal: primero "Karaoke" claro con el play oscuro (de ahí que naciera
     * el `maxContrastOn` que estaba aquí), y el 10 sep 2026 al revés.
     */
    activeContentColor: Color,
    onModeChange: (LyricsViewMode) -> Unit
) {
    // Connected button group (el segmentado dejó de recomendarse en M3 Expressive). El fondo de
    // esta pantalla es `surfaceContainer` (rol del scheme), así que el segmento INACTIVO usa roles
    // del scheme y NO un alpha sobre el contenido: contenedor tenue `surfaceContainerHighest` (un
    // escalón por encima del fondo, para leerse como botón sin competir con la píldora activa) y
    // contenido `onSurfaceVariant`. La píldora ACTIVA sí es el acento dinámico del álbum (`pillColor`),
    // con su contenido puesto desde fuera (ver [activeContentColor]).
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
        width = ChoiceGroupWidth.Content,
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
    // Línea activa: el acento tal cual lo manda el reproductor (ver el bloque del header). El
    // `safeAccentColor` que había aquí medía contra el mismo fondo de rol y nunca podía caer.
    val activeLineColor = accentColor

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

    // Posición INTERPOLADA de la letra. `mutableLongState` y no un parámetro: sus DOS lectores son
    // diferidos —el `drawWithContent` del verso activo y el `derivedStateOf` del verso en curso—,
    // así que moverla invalida el dibujo de UN `Text` y nada más. Ningún composable la lee directa,
    // que es lo que la separa del tick de posición y evita recomponer la lista a 120 Hz.
    //
    // **Se interpola SIEMPRE, no solo con karaoke.** `currentPositionFlow` late una vez por segundo
    // y con FASE LIBRE respecto a la canción, así que un verso que empieza justo después de un tick
    // no se marcaba hasta el siguiente: hasta un segundo de retraso en CADA cambio de línea, que es
    // lo que se reportó. Sin tiempos por palabra no hay barrido que lo delate, pero el foco y el
    // auto-scroll llegan igual de tarde.
    //
    // Arranca en la posición real y no en cero: es el valor del primer frame, antes de que el efecto
    // de abajo llegue a correr, y con un cero la letra abriría en el verso 1 para saltar acto
    // seguido al que suena.
    val lyricsMillis = remember { mutableLongStateOf(currentPositionFlow.value) }

    val onScreen = LocalPlayerOnScreen.current
    LaunchedEffect(lines, hasWordTiming, playbackState, onScreen, currentPositionFlow) {
        // ¿Avanza la canción sola? Es lo único que decide si hay algo que interpolar. Con el
        // reproductor guardado detrás de la biblioteca tampoco: nadie está mirando la letra.
        val advancing = playbackState == PlaybackState.PLAYING && onScreen
        if (!advancing) {
            // En reposo no se interpola, pero SÍ hay que seguir los saltos: en pausa se puede
            // buscar, y el resaltado tiene que aterrizar donde aterrice la canción.
            currentPositionFlow.collect { lyricsMillis.longValue = it }
            return@LaunchedEffect
        }
        if (hasWordTiming) {
            // **Éste es el ÚNICO reloj de la app al que la regla del píxel no le compra nada, y va
            // con el suelo a propósito.** Los otros tres calculan su intervalo con `stepMillisFor`
            // porque mueven uno o dos píxeles por segundo y así duermen entre medias; el barrido de
            // un verso recorre cientos, de modo que ese cálculo devuelve siempre menos de un frame y
            // `coerceAtLeast` lo sube igualmente a [MIN_STEP_MS]. Dárselo ya calculado sería un
            // número que no llega a usarse fingiendo que decide algo.
            //
            // Lo que sí acota el gasto es el GATE de arriba: sin tiempos por palabra no corre, en
            // pausa tampoco, y con el reproductor guardado tampoco.
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
                    lyricsMillis.longValue = baseMillis + (now - baseNanos) / NANOS_PER_MILLI
                }
            }
        } else {
            // Sin barrido no hace falta un reloj continuo: lo único que tiene que ocurrir a tiempo
            // es el CAMBIO de verso, y su instante se conoce de antemano. Se duerme hasta el borde
            // del verso siguiente (convención 13: esperar por SEÑAL, no por intervalo), así que esto
            // despierta una vez por verso en lugar de decenas de veces por segundo — y el cambio cae
            // en su milisegundo, no en el del próximo tick.
            //
            // `collectLatest` sobre el tick: cada uno CANCELA la espera en curso y vuelve a anclar,
            // que es lo que absorbe a la vez la deriva del reloj y los seeks, sin un caso especial
            // para cada uno.
            currentPositionFlow.collectLatest { tick ->
                val anchorNanos = System.nanoTime()
                lyricsMillis.longValue = tick
                var cursor = tick
                while (true) {
                    val next = lines.firstOrNull { it.startTime > cursor }?.startTime ?: break
                    val elapsed = (System.nanoTime() - anchorNanos) / NANOS_PER_MILLI
                    delay((next - (tick + elapsed)).coerceAtLeast(0L))
                    cursor = next
                    // `maxOf` y no la extrapolación a secas: si el `delay` despierta un pelo antes
                    // por redondeo, el verso al que se venía no entraría en el `indexOfLast` y el
                    // foco se quedaría atrás hasta el siguiente tick — justo el desfase que esto
                    // viene a quitar.
                    lyricsMillis.longValue =
                        maxOf(next, tick + (System.nanoTime() - anchorNanos) / NANOS_PER_MILLI)
                }
            }
        }
    }

    // Línea activa.
    //
    // Sale SIEMPRE de [lyricsMillis], que es la posición interpolada: leer aquí el tick de un
    // segundo suscribiría además este composable —y con él el bloque de contenido del `LazyColumn`,
    // o sea las ~8 líneas visibles con sus tres `animate*AsState` cada una— a una emisión por
    // segundo. Leído solo dentro del `derivedStateOf`, la suscripción vive en el derivado y
    // `activeIndex` únicamente notifica cuando el índice cambia de verdad: una vez por verso.
    //
    // Eso es exactamente para lo que existe `derivedStateOf`, y antes no lo conseguía — llevaba la
    // posición como CLAVE del `remember` y a la vez la leía dentro, así que el derivado se destruía
    // y se reconstruía en cada tick sin cachear nada. La clave correcta es el estado (identidad
    // estable) y no su valor.
    val activeIndex by remember(lines, lyricsMillis) {
        derivedStateOf {
            val millis = lyricsMillis.longValue
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
                                    sungPath, layout, line, lyricsMillis.longValue
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
    /** Contenido sobre [accentColor]; ver el parámetro homónimo de [LyricsScreen]. */
    accentContentColor: Color,
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
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, accentContentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                }
                LyricsFailure.PROVIDER_ERROR -> {
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, accentContentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, accentContentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    LyricsActionButton("manage_search", stringResource(R.string.lyrics_search_alternatives), accentColor, accentContentColor, primary = true, onClick = onSearchManually, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, accentContentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
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
    /** Contenido sobre [accentColor] cuando el botón es LLENO; ver [LyricsScreen]. */
    accentContentColor: Color,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Button/OutlinedButton REALES (M3 Expressive: shape-morph al presionar) con los colores
    // del acento del álbum en vez de Surface artesanal.
    val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    if (primary) {
        // El contenido NO se decide aquí: este relleno es el mismo `primary` que el play, y el
        // empate a tono ~50 ya lo resolvió quien pinta aquél (ver [LyricsScreen.accentContentColor]).
        val content = accentContentColor
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
            // El borde por el helper de la app y no a mano: era `BorderStroke(1.dp, accento al
            // 50 %)`, o sea el ancho tabulado escrito como literal y una atenuación inventada donde
            // M3 ya define un rol (`outlineVariant`). Ver [appOutlinedButtonBorder]. El acento se
            // queda donde toca, en la etiqueta y el glifo.
            colors = appOutlinedButtonColors(accentColor),
            border = appOutlinedButtonBorder(),
            contentPadding = contentPadding
        ) {
            MaterialSymbol(icon, size = 18.sp, color = accentColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = accentColor)
        }
    }
}

/**
 * Ajuste de la sincronía de la letra: la corre adelante o atrás y enseña cuánto lleva corrida.
 *
 * **Existe porque el dato que lo decidiría no está en ningún metadato.** LrcLib guarda varias
 * transcripciones de la misma canción, hechas sobre másters distintos, y su `/api/get` empareja con
 * ±2 s de tolerancia: un remaster que arranca dos segundos más tarde que el original casa igual y
 * devuelve tiempos corridos. No hay forma de elegir mejor —las dos versiones duran lo mismo— y lo
 * único que sabe cuál cuadra es el oído del usuario, así que la app le da el mando en vez de
 * adivinar. Caso que lo motivó: *The Zephyr Song* del remaster de 2014, 2,03 s (31 ago 2026).
 *
 * **Dos escalones y no uno**: el grueso ([TIMING_STEP_COARSE_MS]) es para esto —desfases de
 * segundos entre másters— y el fino ([TIMING_STEP_FINE_MS]) para el retardo de unos auriculares
 * Bluetooth, que es de décimas. Con un solo paso, o corregir dos segundos son veinte toques o el
 * ajuste fino no existe.
 *
 * Solo se ofrece con letra SINCRONIZADA: sobre texto plano no hay ningún tiempo que correr.
 */
@Composable
private fun LyricsTimingBar(
    shiftMs: Long,
    onShift: (Long?) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    // **Contenedor propio y no controles sueltos sobre el fondo** (device, 31 ago 2026). Sin él la
    // fila flotaba sobre la letra con el mismo fondo, así que las cifras se leían como un verso más
    // —y encima quedaban a la altura donde el usuario está buscando texto—.
    //
    // **El escalón superior de la escala neutra, `surfaceContainerHighest`.** La página de las
    // letras es `surfaceContainer` (ver el caller en `NowPlayingScreen`), así que la barra queda un
    // peldaño por encima: 4 puntos de tono en claro y 10 en oscuro. Modesto a propósito — su trabajo
    // es AGRUPAR los cinco controles, no destacar.
    val barContainer = AppColors.surfaceContainerHighest

    // **La píldora del valor dice con su COLOR si hay ajuste puesto**, y eso reemplaza a la capa
    // tonal que llevaba antes:
    //  - Sin ajuste, `surfaceContainer` — el color de la propia página, así que se lee como un
    //    HUECO en la barra: no hay nada puesto. Su cifra va en `onSurfaceVariant`, el secundario
    //    tabulado de la escala neutra.
    //  - Con ajuste, el par lleno `primary`/`onPrimary`: es el ancla de la fila, lo que hay que leer
    //    de un vistazo.
    //
    // **Estuvieron DERIVADAS las dos capas** con `rememberTonalLayerColors` desde el mismo fondo,
    // una a 8 puntos y otra a 16, más un `rememberSecondaryContentColor` para la cifra en reposo. El
    // motivo era que "aquí el fondo lo pone la carátula", y eso NO es cierto: el fondo es un rol
    // (`surfaceContainer`), lo que la carátula tiñe es el esquema entero. Lo que sí sigue siendo
    // verdad es que **dos superficies neutras anidadas no caben en tema claro** — medido, toda la
    // familia vive entre T90 y T94—, y por eso el segundo nivel es de acento y no otro neutro.
    val hasShift = shiftMs != 0L
    val valueContainer = if (hasShift) AppColors.primary else AppColors.surfaceContainer
    val valueContentColor = if (hasShift) AppColors.onPrimary else AppColors.onSurfaceVariant
    AppSurface(
        modifier = modifier
            .fillMaxWidth()
            // Separada de los controles: pegada al transporte las dos piezas se leían como un solo
            // bloque de mandos, y el ajuste es de otra naturaleza —corrige el DATO, no reproduce—.
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = TimingBarBottomGap),
        color = barContainer,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimingStepButton("−${TIMING_STEP_COARSE_MS / MILLIS_PER_SECOND}", accentColor) {
                onShift(-TIMING_STEP_COARSE_MS)
            }
            TimingStepButton("−0,${TIMING_STEP_FINE_MS / TENTHS_DIVISOR}", accentColor) {
                onShift(-TIMING_STEP_FINE_MS)
            }

            // **Contenedor circular propio para el valor.** Es el ancla de la fila —lo que hay que
            // poder leer de un vistazo— y rodeado de cuatro botones del mismo acento se perdía entre
            // ellos: el peso en negrita ayudaba, pero la jerarquía la da la SUPERFICIE.
            //
            // Con ancho MÍNIMO y no envuelto: si la píldora creciera con las cifras, los cuatro
            // botones se moverían de sitio en cada toque y apuntar dos veces al mismo sería una
            // persecución.
            AppSurface(
                    color = valueContainer,
                    shape = CircleShape,
                    modifier = Modifier.padding(vertical = TimingValuePadding)
                ) {
                    Text(
                        text = stringResource(
                            R.string.lyrics_timing_seconds_short,
                            formatLyricsShift(shiftMs)
                        ),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        // **Desviación DECLARADA del rol** (device, 31 ago 2026), que es la única forma
                        // admitida de salirse de la escala: `labelLargeEmphasized` deja el valor al mismo
                        // peso aparente que los cuatro pasos, y como además comparten el acento la fila se
                        // leía como cinco cifras iguales. El valor es el ANCLA —lo que hay que leer de un
                        // vistazo— y los pasos son los mandos: el contraste tipográfico es lo que los
                        // separa. Se toca aquí y solo aquí; la escala global de `Type.kt` no cambia.
                        fontWeight = FontWeight.Bold,
                        color = valueContentColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(min = TIMING_VALUE_MIN_WIDTH)
                            .padding(horizontal = 12.dp, vertical = TimingValuePadding)
                    )
            }

            TimingStepButton("+0,${TIMING_STEP_FINE_MS / TENTHS_DIVISOR}", accentColor) {
                onShift(TIMING_STEP_FINE_MS)
            }
            TimingStepButton("+${TIMING_STEP_COARSE_MS / MILLIS_PER_SECOND}", accentColor) {
                onShift(TIMING_STEP_COARSE_MS)
            }

            // **Siempre presente y APAGADO cuando no hay nada que quitar**, no oculto. Apareciendo y
            // desapareciendo movía los cuatro botones de sitio justo mientras el usuario los está
            // pulsando en serie, y el primer toque que cambia el ajuste corría el que ibas a dar
            // después. El mismo criterio que el botón de guardar de la barra de arriba.
            ExpressiveActionIcon(
                onClick = { onShift(null) },
                icon = "restart_alt",
                description = stringResource(R.string.lyrics_timing_reset),
                // El estado deshabilitado lo resuelve [ExpressiveActionIcon] con los cuatro roles
                // explícitos; aquí solo va el color de reposo.
                contentColor = accentColor,
                iconSize = 20.sp,
                enabled = shiftMs != 0L
            )
        }
    }
}

/** Un escalón del ajuste. Botón de TEXTO porque lo que lo identifica es la cifra, no un glifo. */
@Composable
private fun TimingStepButton(label: String, accentColor: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    TextButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
        // Por el helper de la app y no `ButtonDefaults.textButtonColors` a pelo: es la misma llamada,
        // pero centralizada es donde se arregla de una vez si algún rol de este botón deja de salir
        // de `AppColors` (ver AppComponentColors.kt).
        colors = appTextButtonColors(accentColor),
        modifier = Modifier.widthIn(min = TIMING_STEP_MIN_WIDTH)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * El desplazamiento como NÚMERO con su signo: `0`, `+1,5`, `−2`.
 *
 * Con signo SIEMPRE (menos en el cero) porque la dirección es la mitad del dato, y con el menos
 * tipográfico (`−`) y no el guion del teclado, que a este tamaño se lee como un separador.
 *
 * **La unidad NO se pega aquí**: la pone `R.string.lyrics_timing_seconds_short`, porque el símbolo
 * de una unidad es texto de interfaz y esta era, con la duración de las listas, la única cadena
 * visible de la app escrita en el código. Que hoy la `s` coincida en los dos idiomas no la vuelve
 * neutra: la vuelve invisible para quien traduzca al tercero.
 */
private fun formatLyricsShift(millis: Long): String {
    if (millis == 0L) return "0"
    val sign = if (millis > 0) "+" else "−"
    val magnitude = abs(millis)
    // Un ajuste redondo se enseña sin decimal ("2 s" y no "2,0 s"); el resto con una cifra, que es
    // toda la precisión que el paso fino puede producir.
    val text = if (magnitude % MILLIS_PER_SECOND == 0L) {
        (magnitude / MILLIS_PER_SECOND).toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", magnitude / MILLIS_PER_SECOND.toFloat())
    }
    return "$sign$text"
}

/** Paso grueso: los desfases entre másters se miden en segundos. */
private const val TIMING_STEP_COARSE_MS = 1_000L

/** Paso fino: el retardo de unos auriculares Bluetooth se mide en décimas. */
private const val TIMING_STEP_FINE_MS = 100L

private const val MILLIS_PER_SECOND = 1_000L
private const val TENTHS_DIVISOR = 100L

/** Lado de los iconos de la barra de acciones; el que fija `ExpressiveActionIcon`. */
private val ACTION_ICON_SIZE = 48.dp

/**
 * Glifo de los dos iconos que flanquean el transporte.
 *
 * Más chico que el de la barra de arriba (24sp) a propósito: ahí compiten con el conmutador de
 * vista y aquí con el play, que es la pieza dominante de la pantalla. Al mismo tamaño le robaban
 * atención al control principal.
 */
private val LyricsFlankIconSize = 22.sp

/** Aire de la píldora del valor dentro de la barra: la deja como pieza inscrita, no como parche. */
private val TimingValuePadding = 4.dp

/** Separación entre la barra de ajuste y la fila de controles. */
private val TimingBarBottomGap = 12.dp

private val TIMING_VALUE_MIN_WIDTH = 64.dp
private val TIMING_STEP_MIN_WIDTH = 52.dp

