package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.appSelectableMenuItemColors
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.ui.theme.appVibrantFloatingToolbarColors
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarAction
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.components.*

import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appMenuItemColors
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appShrinkWidthFadeOut
import com.qhana.siku.ui.theme.appExpandWidthFadeIn
import com.qhana.siku.ui.theme.AppRevealSpec
import com.qhana.siku.ui.components.pixelPacedClock
import com.qhana.siku.ui.components.stepMillisFor

/*
 * TRANSPORTE y BARRA DE ACCIONES del NowPlaying: el grupo prev/play/next con sus formas
 * animadas, el reveal de acento que comparten con el toolbar, y la barra flotante configurable.
 *
 * La carátula y la identidad de la canción viven en [NowPlayingArt]; la barra de progreso y el
 * chip de formato, en [NowPlayingProgress].
 */

/**
 * Shape del botón de play: morph continuo píldora ↔ [MaterialShapes.Cookie9Sided].
 * A progress 0 delega en la píldora exacta (percent 50, idéntica al estado en pausa
 * original); con progress > 0 interpola con [Morph] entre una píldora real construida
 * al aspect actual del botón y la cookie escalada a los bounds. Durante el morph se reconstruye
 * por frame porque el tamaño anima a la vez que la forma — el costo del matching de
 * features de Morph es despreciable para un botón.
 *
 * La igualdad por valor no es opcional: los controles recomponen con cada tick de posición y con
 * cada frame de la animación de colores del tema, y sin [equals] cada recomposición creaba una
 * instancia "distinta" que invalidaba el outline — o sea un Morph completo reconstruido por frame
 * con el botón QUIETO en cookie. Con equals, el outline solo se recalcula cuando el progreso o el
 * tamaño cambian de verdad (mismo criterio que `AlbumArtMorphShape`).
 */
// `MaterialShapes` pasó a exigir opt-in explícito en material3 1.5.0-alpha24. Va en la CLASE (no
// en un @Composable): esto es un `Shape`, se resuelve en el hilo de dibujo.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    override fun equals(other: Any?): Boolean =
        other is PlayButtonMorphShape && other.progress == progress

    override fun hashCode(): Int = progress.hashCode()
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
    /**
     * `false` mientras el reproductor SUBE desde la píldora. Ahí el reveal se salta y el acento se
     * aplica directo: correr el reveal (que compone el bloque de controles DOS veces, con todos sus
     * componentes Expressive) encima del slide de apertura es lo que apilaba dos animaciones caras
     * y se veía como tartamudeo. El reveal solo tiene sentido con el player ya abierto — cambio de
     * canción por siguiente/anterior/notificación. Ver el mismo gateo en `AlbumArtSection`.
     */
    revealEnabled: Boolean = true,
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

    // Izado: `LaunchedEffect` no es composable, así que el token se resuelve aquí fuera.
    //
    // Spec propio y no un token del scheme: ver el kdoc de [AppRevealSpec].
    val revealSpec = AppRevealSpec
    LaunchedEffect(songId) {
        if (displayed.first == songId) return@LaunchedEffect
        // Player abriéndose: sin reveal, se adopta el acento destino de una (ver [revealEnabled]).
        if (!revealEnabled) {
            displayed = Triple(songId, currentAccent, currentAccentContent)
            return@LaunchedEffect
        }
        revealing = true
        reveal.snapTo(0f)
        reveal.animateTo(1f, revealSpec)
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

/**
 * Periodo de una vuelta completa de la cookie del play. Lento a propósito: es un latido de fondo
 * que acompaña la reproducción, no una animación que reclame atención.
 */
private const val COOKIE_SPIN_PERIOD_MS = 18_000

/** Una vuelta completa, en grados. */
private const val FULL_TURN_DEGREES = 360f

/**
 * ¿Toca la coreografía de **"cambió la canción bajo tus ojos"** (el reveal del acento del transporte y
 * el de la carátula)? Solo con el reproductor **abierto, quieto y a la vista**, que son tres
 * condiciones y no una:
 *
 *  - **Quieto** (`currentState == targetState`): durante el morph la pantalla entera ya se está
 *    moviendo, y encadenar encima un reveal —que compone su bloque DOS veces, con todos sus
 *    componentes Expressive— es lo que apilaba dos animaciones caras y se leía como tartamudeo.
 *  - **A la vista** ([com.qhana.siku.ui.LocalPlayerOnScreen]): desde que el reproductor es persistente,
 *    "quieto" también es cierto con él GUARDADO, así que sin esta parte cada "siguiente" desde la
 *    píldora o la notificación correría los dos reveals enteros donde no los ve nadie.
 *  - **Abriendo desde una lista** nunca viste la carátula anterior en grande: no hay nada que revelar.
 *
 * Sin scope compartido (el modo ambiental, una vista previa) no hay morph del que protegerse y el
 * reveal va como siempre.
 */
@Composable
internal fun playerRevealEnabled(animatedVisibilityScope: AnimatedVisibilityScope?): Boolean =
    LocalPlayerOnScreen.current &&
        (animatedVisibilityScope?.transition?.let { it.currentState == it.targetState } ?: true)

@Composable
internal fun rememberPlayButtonSpin(isPlayingOrBuffering: Boolean): PlayButtonSpinState {
    // Morph continuo píldora (pausa) ↔ Cookie9Sided (reproduciendo); mismo token que las dimensiones
    // del botón en PlaybackControls, que es lo que hace que forma y tamaño cierren juntos.
    // `defaultSpatial` del scheme expressive rebota (dampingRatio 0.8), igual que el
    // `DampingRatioLowBouncy` escrito a mano que había aquí, y por eso `AlbumArtMorphShape` recorta
    // el progreso a [0,1] antes de pasárselo a `Morph`.
    val morphProgress = animateFloatAsState(
        targetValue = if (isPlayingOrBuffering) 1f else 0f,
        animationSpec = appSpatialSpec(),
        label = "playShapeMorph"
    )
    // La transición infinita solo existe mientras el botón no es píldora pura en reposo **y el
    // reproductor se ve**. Lo segundo hace falta desde que el subárbol del player es PERSISTENTE (ver
    // [com.qhana.siku.ui.LocalPlayerOnScreen]): girar detrás de la biblioteca es exactamente el
    // productor continuo de frames que la regla del 17 ago prohíbe, y encima invisible. El ángulo se
    // repone a 0 al parar, así que reabrir no enseña un salto.
    //
    // derivedStateOf: el caller (layout) no recompone por frame durante el morph, solo
    // cuando el booleano realmente cambia.
    val playing = rememberUpdatedState(isPlayingOrBuffering)
    val onScreen = rememberUpdatedState(LocalPlayerOnScreen.current)
    val spinning by remember {
        derivedStateOf { onScreen.value && (playing.value || morphProgress.value > 0f) }
    }
    // El giro NO es un `rememberInfiniteTransition`, y el motivo está MEDIDO (Perfetto, 17 ago):
    // una animación infinita invalida el dibujo en CADA vsync, así que con el reproductor abierto
    // la app producía 120 frames por segundo sin parar por una rotación de 0,17° por frame — que en
    // el borde de la cookie es un tercio de píxel, o sea nada visible. Dos costes: batería durante
    // toda la reproducción, y peor, **buffer stuffing**: en cuanto un frame llega tarde (el morph de
    // apertura), la app queda un buffer por delante de SurfaceFlinger, y mientras no deje de producir
    // la cola no drena; ese estado sobrevivía al cierre y se comía el primer scroll de la lista
    // (frames presentados tarde y tirados hasta la primera pausa). La regla que sale de ahí:
    // **no dibujar lo que no mueve ni un píxel**. El ángulo publicado solo cambia cuando el borde de
    // la cookie se ha desplazado al menos uno.
    //
    // Y desde el 20 ago 2026 **tampoco se DESPIERTA de más**: el reloj era `withFrameNanos` con el
    // argumento de que "no produce frames si nadie invalida", y eso resultó ser media verdad —no
    // invalida, pero mantiene un frame callback pendiente y con él el Choreographer pidiendo vsyncs.
    // Medido: 45 ms de cada segundo en el hilo principal sin dibujar nada. Ahora el ritmo lo pone
    // [pixelPacedClock].
    val density = LocalDensity.current
    // Paso angular mínimo = un píxel en el borde de la cookie: atan(1 px / radio en px). Derivado
    // del tamaño real del botón y de la densidad, no un número elegido.
    val stepDegrees = remember(density) {
        with(density) {
            Math.toDegrees(kotlin.math.atan(1.0 / (PlayButtonHeight.toPx() / 2.0))).toFloat()
        }
    }
    val angleState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning, stepDegrees) {
        if (!spinning) {
            angleState.floatValue = 0f
            return@LaunchedEffect
        }
        var startNanos = 0L
        var published = 0f
        angleState.floatValue = 0f
        // El borde recorre un píxel cada `stepDegrees`, y la cookie gira
        // `360 / COOKIE_SPIN_PERIOD_MS` grados por segundo: de ahí sale a qué ritmo hay algo que
        // enseñar —unas 38 veces por segundo con el botón a 80dp— y, desde el 20 ago 2026, también
        // a qué ritmo se DESPIERTA. Ver [pixelPacedClock]: el bucle iba con `withFrameNanos`, o sea
        // 120 despertares por segundo, la mayoría sin nada que publicar.
        val degreesPerSecond = FULL_TURN_DEGREES / (COOKIE_SPIN_PERIOD_MS / 1000f)
        pixelPacedClock(stepMillisFor(degreesPerSecond / stepDegrees)) { now ->
            if (startNanos == 0L) startNanos = now
            val turns = (now - startNanos) / (COOKIE_SPIN_PERIOD_MS * 1_000_000.0)
            val continuous = (turns * FULL_TURN_DEGREES).toFloat()
            // La regla del píxel se queda como RED: el intervalo ya la garantiza, pero `delay` no
            // promete cadencia y un tick temprano no debe publicar de más.
            if (continuous - published >= stepDegrees) {
                published = continuous
                // Módulo una vuelta: la rotación es periódica y así el Float no pierde precisión
                // tras horas de reproducción.
                angleState.floatValue = continuous % FULL_TURN_DEGREES
            }
        }
    }
    return PlayButtonSpinState(morphProgress, angleState)
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
    // Spatial (crece un radio y un clip, no es un color), pero RECORTADO a [0,1]: los springs
    // spatial del scheme expressive sobrepasan el objetivo, y aquí el valor se usa como fracción
    // geométrica — un 1.05 pintaría el círculo del contenedor fuera del botón y el clip del glifo
    // más allá del propio icono. Es el mismo cuidado que ya toma `AlbumArtMorphShape`.
    val rawFraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = appSpatialSpec(),
        label = "toolbarToggleReveal"
    )
    val fraction = rawFraction.coerceIn(0f, 1f)
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
    val activeColor = ensureContrast(accentColor, AppColors.surface, minRatio = 3f)
    val iconColor by animateColorAsState(
        targetValue = if (checked) activeColor else inactiveColor,
        // Effects: es un color, y los tokens effects son los únicos sin rebote.
        animationSpec = appEffectsSpec(),
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
        // `fastSpatial` y no `defaultSpatial`: es el token con más rebote del scheme
        // (dampingRatio 0.6) y el más rígido, que es exactamente el "pequeño rebote" que pedía este
        // punto de 4dp. Aquí el overshoot SÍ se quiere y no molesta — escala un círculo suelto, no
        // recorta nada.
        val dotScale by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = appFastSpatialSpec(),
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
    //
    // El par va CRUDO a propósito. Con el estilo "vibrante" el glifo se va al blanco casi puro (tono
    // 97.5, saturación 0.05, MEDIDO), y eso NO es un defecto que haya que corregir aquí: el resto de
    // la pantalla —textos, chips, títulos— también es blanco en ese estilo, así que un icono con más
    // croma se leería apagado entre ellos. Se probó reencuadrarlo y se revirtió; ver el porqué
    // completo junto a `ensureContrast` en PlayerWidgets.kt.
    val sideButtonContainer = AppColors.secondaryContainer
    val sideButtonContent = AppColors.onSecondaryContainer
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

        // En los tres botones lo único que cambia con el estado es el ANCHO —lo único que el spec
        // mueve entre variantes—; los ALTOS son fijos, cada uno el de SU talla: 80 el play
        // ([PlayButtonHeight]) y 56 los laterales, que son Medium ([SideButtonHeight]).
        // REPRODUCIENDO: el play es una COOKIE de 9 lados (80×80, morph desde la píldora vía
        // PlayButtonMorphShape) y los laterales círculos de 56×56 (variante Uniform).
        // EN PAUSA: el play crece a píldora (120×80, medidas propias — ver [PlayButtonHeight]) y
        // los laterales se estrechan a su variante Narrow (48×56). Forma y ancho animan con el
        // mismo spring.
        // UN solo spec para los dos anchos animados y para el morph de la forma
        // ([rememberPlayButtonSpin] usa el mismo token): es lo que hace que el grupo se mueva como
        // una pieza en vez de como animaciones que casualmente duran parecido. Los altos ya no se
        // animan —son constantes—, así que hay dos `animateDpAsState` menos por frame de morph.
        val transportSpec = appSpatialSpec<Dp>()
        // Cruce del glifo play/pause/buffering. Izados porque `transitionSpec` de `AnimatedContent`
        // no es un lambda composable. El que entra con el token *default*, el que sale con *fast*.
        val glyphEnterScale = appSpatialSpec<Float>()
        val glyphExitScale = appFastSpatialSpec<Float>()
        val playWidth by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) PlayButtonPlayingWidth else PlayButtonPausedWidth,
            animationSpec = transportSpec,
            label = "playButtonWidth"
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
            targetValue = if (isPlayingOrBuffering) SideButtonUniformWidth else SideButtonNarrowWidth,
            animationSpec = transportSpec,
            label = "sideButtonWidth"
        )
        // Formas del spec para un icon button MEDIUM: `ContainerShapeRound` = CornerFull en reposo y
        // `PressedContainerShape` = CornerMedium al pulsar, que es `MaterialTheme.shapes.medium` —
        // se toma del tema y no de un dp escrito a mano. Vuelve al Medium con el tamaño (21 ago):
        // la forma al pulsar la tabula la TALLA del botón, así que dejarla en `large` con un
        // contenedor de 56 redondearía de más.
        val sideShapes = IconButtonShapes(
            shape = RoundedCornerShape(percent = 50),
            pressedShape = MaterialTheme.shapes.medium
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
                    shapes = IconButtonDefaults.shapes(),
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
                            .height(SideButtonHeight)
                            .semantics { contentDescription = prevDesc }
                    ) {
                        MaterialSymbol("skip_previous", size = SideButtonIconSize, fill = true)
                    }
                },
                menuContent = { state ->
                    // Item CLÁSICO (sin `shape`) a propósito: el contenedor de este overflow lo
                    // pone `ButtonGroup`, que usa un `DropdownMenu` normal, y los items que él
                    // mismo genera para `clickableItem`/`toggleableItem` también son clásicos.
                    // Darle forma sólo a los nuestros metería pastillas sueltas en una superficie
                    // que no es un grupo segmentado.
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
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
                    AppSurface(
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
                            .height(PlayButtonHeight)
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
                                label = "playPause"
                            ) { state ->
                                when (state) {
                                    PlaybackState.BUFFERING -> {
                                        // Sonda (solo debug): la primera composición de un LoadingIndicator
                                        // construye 7 Morphs entre MaterialShapes en el hilo principal.
                                        SideEffect { com.qhana.siku.data.util.JankProbe.mark { "LoadingIndicator del play compuesto" } }
                                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                                        LoadingIndicator(color = playButtonContentColor, modifier = Modifier.size(44.dp))
                                    }
                                    PlaybackState.PLAYING ->
                                        MaterialSymbol("pause", size = PlayButtonIconSize, color = playButtonContentColor, fill = true)
                                    else ->
                                        MaterialSymbol("play_arrow", size = PlayButtonIconSize, color = playButtonContentColor, fill = true)
                                }
                            }
                        }
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
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
                            .height(SideButtonHeight)
                            .semantics { contentDescription = nextDesc }
                    ) {
                        MaterialSymbol("skip_next", size = SideButtonIconSize, fill = true)
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
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
    onShareSong: () -> Unit,
    config: List<ToolbarActionState>,
    modifier: Modifier = Modifier
) {
    // FLOATING TOOLBAR M3 Expressive (m3.material.io/components/toolbars), variante VIBRANT:
    // container = `primaryContainer`, content = `onPrimaryContainer` (defaults de
    // vibrantFloatingToolbarColors). Se eligió vibrant porque la standard (surfaceContainer) se
    // perdía contra el fondo del NowPlaying.
    val toolbarContentColor = AppColors.onPrimaryContainer
    // Relleno del toggle ACTIVO: el color DE LA BARRA movido solo en el eje del TONO, lo bastante
    // lejos de ella para VERSE y sin llegar a disputarle el énfasis al play.
    //
    // El porqué, MEDIDO sobre capturas del dispositivo. Con `onPrimaryContainer` (lo que había) el
    // relleno salía a tono 90.2 contra el 80.1 del play en tema oscuro: el elemento más claro —y
    // por tanto más enfático— de todo el reproductor era un toggle encendido, por duplicado. Con
    // `secondary` tampoco basta: en oscuro ese rol vale 80 con TonalSpot, o sea EMPATA con el play;
    // y en claro su croma de 19.1 dentro de una barra de 39.8 convertía el círculo en una mancha
    // gris. El fondo de la barra (tono 34.9 en oscuro) nunca fue el problema.
    //
    // La regla tiene DOS mitades y las dos son necesarias:
    //
    //  1. Cuando hay recorrido hacia el play, el activo se coloca a [ACTIVE_TONE_FRACTION_UP] /
    //     [_DOWN] del camino de la barra al acento: queda visible y por debajo de él en énfasis
    //     sin depender de qué tono devuelva cada rol.
    //  2. Un PISO de contraste contra la propia barra ([ACTIVE_MIN_CONTRAST_VS_BAR]) que el
    //     resultado nunca puede incumplir. Esto es lo que faltaba: la interpolación garantiza
    //     jerarquía, no visibilidad, y con un acento vivo puede no haber camino ninguno. MEDIDO en
    //     el device con un tema fiel a la carátula (Dream Theater, seed naranja de croma alto): la
    //     barra sale a tono 60.0 y el play a 63.9 — 3.99 tonos de recorrido —, y el fallback que
    //     había ahí (llevar el activo AL tono del play) dejaba el disco a 1.14:1 contra su propio
    //     fondo, o sea literalmente invisible. El caso no era exótico: desde que el seed se
    //     persiste crudo, `primary` vive donde el croma es máximo y eso lo pega a
    //     `primaryContainer`. Que el degenerado copiara al play era la causa raíz — play ≈ barra
    //     ES la definición de ese caso.
    //
    // El resultado tiene que cumplir DOS cosas, y el disco se corrige solo si falla alguna:
    //
    //  A. Separarse de la BARRA al menos [ACTIVE_MIN_CONTRAST_VS_BAR], o el disco no se ve.
    //  B. Dejar en pie el GLIFO, o sea que `ensureContrast` no tenga que cambiarlo de lado — porque
    //     el glifo activo debe ser el MISMO color que los inactivos (ver [keepsGlyph]).
    //
    // Las dos son necesarias y cada una la descubrió una captura del device, cada una con un estilo
    // de paleta distinto (Ajustes → Apariencia), que es lo que mueve estos tonos:
    //
    //  - Fiel a la carátula, tema oscuro: barra 60.0, play 63.9, glifo 14.9. Falla A (el candidato
    //    sale a 62.4, o sea 1.09:1 contra su propio fondo: invisible).
    //  - Equilibrado, tema oscuro: barra 35.0, play 63.9, glifo 90.2. Cumple A de sobra (candidato
    //    52.5, 1.90:1) y falla B: el glifo claro no aguanta sobre un disco medio, así que
    //    `ensureContrast` lo mandó a tono 4.6 — casi negro, el único icono NEGRO de una barra de
    //    iconos claros.
    //
    // Mirar solo A arreglaba el primero y dejaba el segundo; mirar solo B, al revés. Y el tema
    // claro/oscuro sale gratis de B: de qué lado cae el glifo ES la firma del tema y del estilo
    // juntos, así que en Equilibrado oscuro la corrección BAJA el disco y en fiel a la carátula lo
    // SUBE, sin una sola rama que pregunte por el tema.
    //
    // Cuando ninguna falla, el resultado es el de siempre TONO A TONO. Esto es load-bearing: una
    // regla que decida la dirección por su cuenta arregla el estilo que se está mirando y estropea
    // otro (con la barra a 70 y el play a 20 la interpolación da 50 con 1.95:1 —correcto— y una
    // dirección impuesta "hacia arriba" lo mandaba a 86). La pregunta correcta no es "¿hacia dónde
    // va el activo?" sino "¿el de siempre cumple? si no, corrígelo".
    //
    // La CORRECCIÓN va siempre alejándose del glifo, que es la única dirección que puede cumplir A y
    // B a la vez: aleja el disco de la barra y se lo acerca al glifo en contraste. Si por ese lado no
    // cabe el piso (barra ya casi blanca), se usa el otro.
    //
    // CONTRAPARTIDA ASUMIDA, y solo dentro de la corrección: ahí el disco puede acabar más claro que
    // el play (75.2 contra 63.9 en el estilo fiel a la carátula) y deja de cumplirse "el activo
    // nunca por encima del acento". Se acepta porque ese caso es exactamente el que se está
    // corrigiendo —play y barra son el mismo color, así que esa jerarquía ya no existía— y porque el
    // disco se mueve SOLO lo justo para el piso.
    //
    // Comprobado contra el estilo que ya estaba aprobado a la vista en tema claro (barra 90.1, play
    // 42.4, glifo 30.0): cumple las dos y sigue dando 71.0, idéntico.
    val toolbarContainerColor = AppColors.primaryContainer
    val checkedBg = remember(playButtonColor, toolbarContainerColor, toolbarContentColor) {
        val bar = Hct.fromInt(toolbarContainerColor.toArgb())
        val playTone = Hct.fromInt(playButtonColor.toArgb()).tone
        val glyphTone = Hct.fromInt(toolbarContentColor.toArgb()).tone
        // Diseño de siempre: una fracción del camino tonal de la barra al play, con su fracción por
        // sentido. Sin fallback: el que había (llevar el activo AL tono del play cuando el recorrido
        // era corto) era la causa raíz, porque play ≈ barra ES ese caso.
        val span = playTone - bar.tone
        val candidate =
            bar.tone + span * (if (span > 0) ACTIVE_TONE_FRACTION_UP else ACTIVE_TONE_FRACTION_DOWN)
        val activeTone = if (
            Contrast.ratioOfTones(candidate, bar.tone) >= ACTIVE_MIN_CONTRAST_VS_BAR &&
            keepsGlyph(discTone = candidate, glyphTone = glyphTone)
        ) {
            candidate
        } else {
            // Las DOS direcciones — no solo "alejarse del glifo" — y, entre las que YA cumplen el
            // piso de contraste contra la barra Y dejan al glifo del lado correcto (`keepsGlyph`),
            // se prefiere la que retiene más croma de la barra (1 sep 2026, mismo criterio que
            // [ensureContrast]). NINGÚN piso se relaja — eso es justo lo que este ajuste corrige de
            // una versión anterior: el 1.6:1 sigue siendo el mínimo real, esto solo decide ENTRE
            // soluciones que YA lo cumplen. Si solo una dirección cumple las dos condiciones, se usa
            // esa igual que antes; si ninguna las cumple las dos, se cae al comportamiento de
            // siempre —alejarse del glifo, con el otro lado de reserva— sin tocarlo.
            val darkTone = Contrast.darkerUnsafe(bar.tone, ACTIVE_MIN_CONTRAST_VS_BAR)
            val lightTone = Contrast.lighterUnsafe(bar.tone, ACTIVE_MIN_CONTRAST_VS_BAR)
            val darkOk = Contrast.ratioOfTones(darkTone, bar.tone) >= ACTIVE_MIN_CONTRAST_VS_BAR &&
                keepsGlyph(discTone = darkTone, glyphTone = glyphTone)
            val lightOk = Contrast.ratioOfTones(lightTone, bar.tone) >= ACTIVE_MIN_CONTRAST_VS_BAR &&
                keepsGlyph(discTone = lightTone, glyphTone = glyphTone)
            when {
                darkOk && lightOk ->
                    if (
                        Hct.from(bar.hue, bar.chroma, darkTone).chroma >=
                        Hct.from(bar.hue, bar.chroma, lightTone).chroma
                    ) {
                        darkTone
                    } else {
                        lightTone
                    }
                darkOk -> darkTone
                lightOk -> lightTone
                else -> {
                    // Ninguna de las dos direcciones deja al glifo del lado correcto: el
                    // comportamiento de siempre. Los `*Unsafe` CLAMPEAN a 100 y a 0 cuando el piso
                    // no es alcanzable, así que la pregunta se le hace al contraste que el tono da
                    // de verdad y no a si la función devolvió algo.
                    val glyphAbove = glyphTone > bar.tone
                    val away = if (glyphAbove) darkTone else lightTone
                    if (Contrast.ratioOfTones(away, bar.tone) >= ACTIVE_MIN_CONTRAST_VS_BAR) away
                    else if (glyphAbove) lightTone else darkTone
                }
            }
        }
        // Matiz y croma DE LA PROPIA BARRA: el activo es "la barra, destacada", así que solo debe
        // moverse en el eje del tono. Antes salían de `secondary` y el resultado, MEDIDO en tema
        // claro, era un croma de 19.1 dentro de una barra de 39.8: a media saturación el círculo
        // se leía como una mancha gris sobre un fondo amarillo. El hue ya coincidía (106° en toda
        // la pantalla), así que lo único que desentonaba era eso.
        //
        // Que use la paleta primaria NO reabre lo de "la primaria es del play": la barra entera ya
        // es `primaryContainer`. Lo que sigue siendo exclusivo del play es el ROL `primary`, y el
        // reparto de énfasis lo hace el tono, no la familia.
        bar.withTone(activeTone.coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)).let { Color(it.toInt()) }
    }
    // Glifo activo: parte del MISMO color que los iconos inactivos de la barra, para que lo único
    // que cambie al encender sea el disco de detrás. `ensureContrast` lo corrige si el relleno se
    // le acerca demasiado — hace falta porque al construir el color a mano se pierde la garantía
    // del par `on*` de M3, y es el mismo mecanismo del toggle de acento del transporte.
    // `remember`ado como `checkedBg`: desde que `ensureContrast` camina el límite de croma
    // (hasta ~100 pasos, cada uno un `Hct.from`), recalcularlo en cada recomposición sin motivo
    // sería trabajo tirado.
    val activeContent = remember(toolbarContentColor, checkedBg) {
        ensureContrast(
            content = toolbarContentColor,
            container = checkedBg,
            minRatio = ACTIVE_GLYPH_MIN_CONTRAST
        )
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = appVibrantFloatingToolbarColors(),
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
                    PlayerToolbarAction.SHARE -> ExpressiveActionIcon(
                        onClick = onShareSong,
                        icon = "share",
                        description = stringResource(R.string.np_share),
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
                        icon = "snooze",
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
                            // El glifo dice lo mismo que la etiqueta de al lado: bajar por primera
                            // vez y traer OTRA COPIA de algo que ya está en disco no son la misma
                            // acción, y con un único icono de descarga había que leer el texto para
                            // saber cuál de las dos se estaba ofreciendo.
                            icon = if (isDownloaded) "sync_arrow_down" else "download",
                            description = if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download),
                            contentColor = toolbarContentColor,
                            morph = false
                        )
                }
            }

            // Descarga EN CURSO: anillo determinado (o indeterminado mientras se resuelve la URL).
            // Independiente de la config: es ESTADO, no una acción reordenable.
            AnimatedVisibility(
                visible = isDownloading,
                // Eje HORIZONTAL: esto entra en una fila y corre a sus vecinos de lado. Con el
                // default (que crece en las dos direcciones) el toolbar daba un tirón vertical.
                enter = appExpandWidthFadeIn(),
                exit = appShrinkWidthFadeOut()
            ) {
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
                        // Onda circular expressive en sus dos variantes: determinada con progreso,
                        // indeterminada al preparar. Es el MISMO lenguaje que los indicadores
                        // lineales de sync y del gestor de descargas (antes acá había un
                        // LoadingIndicator, que morfea formas en vez de ondular).
                        //
                        // Color = `toolbarContentColor` (el del glifo), NO el acento del álbum:
                        // es la MISMA razón por la que el toggle activo dejó de usar
                        // inverseSurface. Con carátula acromática el tema es Monochrome, y ahí
                        // MCU coloca `primary` y `primaryContainer` del mismo lado tonal (oscuro:
                        // T100 sobre T85; claro: T0 sobre T25) → la onda quedaba invisible sobre
                        // el contenedor de la barra. El par container/onContainer tiene contraste
                        // garantizado por construcción sea cual sea el seed.
                        val density = LocalDensity.current
                        val waveStroke = remember(density) {
                            Stroke(
                                width = with(density) { ToolbarDownloadStrokeWidth.toPx() },
                                cap = StrokeCap.Round
                            )
                        }
                        if (downloadProgress != null && downloadProgress > 0f) {
                            CircularWavyProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = toolbarContentColor,
                                trackColor = toolbarContentColor.copy(alpha = TOOLBAR_DOWNLOAD_TRACK_ALPHA),
                                stroke = waveStroke,
                                trackStroke = waveStroke
                            )
                        } else {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = toolbarContentColor,
                                trackColor = toolbarContentColor.copy(alpha = TOOLBAR_DOWNLOAD_TRACK_ALPHA),
                                stroke = waveStroke,
                                trackStroke = waveStroke
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
                    // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota
                    // en SortChip.
                    AppMenuPopup(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                    AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                        // Qué acciones caen aquí lo decide la config del toolbar, así que la forma
                        // de cada item sale de su POSICIÓN en la lista real: el bloque cierra
                        // arriba y abajo aunque el usuario deje una sola acción en el overflow.
                        //
                        // Letras y "mantener pantalla encendida" son TOGGLES y van con la
                        // sobrecarga `checked` (contenedor marcado, morph de forma,
                        // `Role.Checkbox`); antes su estado solo se veía en el relleno del icono,
                        // que un lector de pantalla no anuncia. Repetición NO: cicla entre tres
                        // valores y un checkbox mentiría sobre lo que hace.
                        overflowActions.forEachIndexed { index, action ->
                            val itemShapes = MenuDefaults.itemShape(
                                index = index,
                                count = overflowActions.size
                            )
                            when (action) {
                                PlayerToolbarAction.REPEAT -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onRepeatToggle() },
                                    text = {
                                        Text(when (repeatMode) {
                                            RepeatMode.OFF -> stringResource(R.string.np_repeat_off)
                                            RepeatMode.ONE -> stringResource(R.string.np_repeat_one)
                                            RepeatMode.ALL -> stringResource(R.string.np_repeat_all)
                                        })
                                    },
                                    shape = itemShapes.shape,
                                    leadingIcon = {
                                        MenuItemIcon(
                                            if (repeatMode == RepeatMode.ONE) "repeat_one" else "repeat",
                                            fill = repeatMode != RepeatMode.OFF
                                        )
                                    }
                                )
                                PlayerToolbarAction.LYRICS -> DropdownMenuItem(
                                    colors = appSelectableMenuItemColors(),
                                    checked = showLyrics,
                                    onCheckedChange = { showMenu = false; onLyricsToggle() },
                                    text = { Text(if (showLyrics) stringResource(R.string.np_hide_lyrics) else stringResource(R.string.np_show_lyrics)) },
                                    shapes = itemShapes,
                                    leadingIcon = { MenuItemIcon("lyrics", fill = showLyrics) }
                                )
                                PlayerToolbarAction.QUEUE -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onShowQueue() },
                                    text = { Text(stringResource(R.string.np_view_queue)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = { MenuItemIcon("queue_music") }
                                )
                                PlayerToolbarAction.SHARE -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onShareSong() },
                                    text = { Text(stringResource(R.string.np_share)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = { MenuItemIcon("share") }
                                )
                                PlayerToolbarAction.KEEP_SCREEN_ON -> DropdownMenuItem(
                                    colors = appSelectableMenuItemColors(),
                                    checked = keepScreenOn,
                                    onCheckedChange = { showMenu = false; onToggleKeepScreenOn() },
                                    text = { Text(if (keepScreenOn) stringResource(R.string.np_screen_off) else stringResource(R.string.np_screen_on)) },
                                    shapes = itemShapes,
                                    leadingIcon = { MenuItemIcon("wb_sunny", fill = keepScreenOn) }
                                )
                                PlayerToolbarAction.EQUALIZER -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onOpenEqualizer() },
                                    text = { Text(stringResource(R.string.np_open_eq)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = { MenuItemIcon("graphic_eq") }
                                )
                                PlayerToolbarAction.ADD_TO_PLAYLIST -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onAddToPlaylistClick() },
                                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = { MenuItemIcon("playlist_add") }
                                )
                                // Abre la hoja del temporizador (no lo enciende ni lo apaga), así
                                // que es una ACCIÓN; que haya uno corriendo se sigue contando con
                                // el acento del icono.
                                PlayerToolbarAction.SLEEP_TIMER -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onSleepTimerClick() },
                                    text = { Text(stringResource(R.string.sleep_timer_title)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = {
                                        MenuItemIcon(
                                            "snooze",
                                            fill = sleepTimerActive,
                                            color = if (sleepTimerActive) playButtonColor else LocalContentColor.current
                                        )
                                    }
                                )
                                PlayerToolbarAction.DOWNLOAD -> DropdownMenuItem(
                                    colors = appMenuItemColors(),
                                    onClick = { showMenu = false; onRedownload() },
                                    text = { Text(if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download)) },
                                    shape = itemShapes.shape,
                                    leadingIcon = {
                                        MenuItemIcon(
                                            when {
                                                isDownloading -> "hourglass_top"
                                                isDownloaded -> "sync_arrow_down"
                                                else -> "download"
                                            }
                                        )
                                    },
                                    enabled = !isDownloading
                                )
                            }
                        }
                    } // fin AppMenuGroup
                    }
                }
            }
        }
    }
}
/**
 * Diámetro del indicador de descarga de la barra del NowPlaying. Va dentro del hueco de 48dp
 * de una acción y por debajo rodea al glifo `download` centrado, sin tocarlo.
 *
 * Son 36dp y no los 32 que llevaba con el `LoadingIndicator`: la onda gasta radio en la
 * amplitud, así que con el diámetro viejo los crestones rozaban el glifo y, sobre todo, a menos
 * de ~32dp la ondulación deja de leerse como onda (el spec la dimensiona a 48). Sigue holgado
 * dentro del hueco de 48dp.
 */
private val ToolbarDownloadIndicatorSize = 36.dp


/**
 * Opacidad del track del anillo de descarga. Se deriva del MISMO color del indicador
 * (`onPrimaryContainer`, en vez de un rol suelto del esquema) porque la barra flotante es vibrant:
 * cualquier `surface*` cae encima del `primaryContainer` del contenedor y el track desaparece.
 * Atenuado lo justo para que el recorrido pendiente se lea sin competir con la onda activa ni con
 * el glifo.
 */
private const val TOOLBAR_DOWNLOAD_TRACK_ALPHA = 0.3f

/**
 * Grosor del trazo de la onda de descarga (indicador y track). Por debajo del default del
 * componente (4dp de `activeThickness`), que a este diámetro se veía macizo y comía el hueco
 * interior donde va el glifo. No bajar mucho más: con un trazo demasiado fino la onda se
 * desdibuja sobre el contenedor de la barra.
 */
private val ToolbarDownloadStrokeWidth = 2.5.dp

/**
 * Dónde se coloca el relleno del toggle ACTIVO del toolbar, medido como fracción del camino
 * tonal que va del contenedor de la barra al botón de play (ver [BottomActionBar]).
 *
 * 0 sería invisible (el tono de la propia barra) y 1 lo empataría con el play, que es el defecto
 * que se venía arrastrando.
 *
 * Va SEPARADO por dirección porque la misma fracción no pesa igual subiendo que bajando. Subiendo
 * el recorrido es largo (barra 34.9 → play 80.1, MEDIDO en tema oscuro) y 0.6 deja el activo en
 * ~62: legible y con un 40% de margen por debajo del acento. Bajando es más corto (barra 90.1 →
 * play 42.4, tema claro) y ese mismo 0.6 dejaba el círculo en 61.4 con 2.34:1 contra su fondo —
 * correcto de jerarquía, pero demasiado marcado a la vista. Con 0.4 sube a ~71 y se queda en
 * ~1.75:1.
 */
private const val ACTIVE_TONE_FRACTION_UP = 0.6
private const val ACTIVE_TONE_FRACTION_DOWN = 0.4

/**
 * Separación mínima, en contraste, entre el relleno del toggle activo y el contenedor de la barra
 * que tiene detrás. Es el PISO que la interpolación hacia el play no puede incumplir, y también lo
 * que decide la dirección (ver [BottomActionBar]).
 *
 * Calibrado con los dos extremos que ya se habían juzgado a la vista sobre el dispositivo: 1.75:1
 * (tema claro, el valor que quedó bien) y 2.34:1 (el que se descartó por "demasiado marcado").
 * 1.6:1 se queda por debajo del primero —así que no fuerza a nadie— y arregla el caso roto, que
 * estaba en 1.14:1. No bajarlo a ~1.4: ahí el disco vuelve a fundirse con la barra en cuanto el
 * acento es cromático.
 *
 * Se mide con [Contrast], la misma matemática que usa M3 para su propio sistema de contraste, así
 * que el paso "contraste objetivo → tono" es exacto y no hay que duplicarlo: el tono HCT y la
 * luminancia relativa de WCAG salen de la MISMA Y de CIEXYZ.
 */
private const val ACTIVE_MIN_CONTRAST_VS_BAR = 1.6

/**
 * Contraste mínimo del glifo del toggle activo contra su relleno. 4.5:1 = el mínimo AA para texto,
 * y el mismo listón que M3 garantiza en sus pares `color`/`onColor` — que aquí hay que reponer a
 * mano porque el relleno se construye con un tono propio.
 */
private const val ACTIVE_GLYPH_MIN_CONTRAST = 4.5f

/**
 * ¿Un disco de tono [discTone] deja en pie un glifo de tono [glyphTone], o `ensureContrast` va a
 * tener que CAMBIARLO DE LADO?
 *
 * Es la condición B de [BottomActionBar], y existe porque el glifo activo tiene que ser el mismo
 * color que los inactivos de la barra: si el disco lo obliga a invertirse, el toggle encendido pasa
 * a ser el único icono de otro color y se pierde justo lo que se quería comunicar. MEDIDO en device:
 * con la barra de Equilibrado en tema oscuro (glifo claro, tono 90.2) sobre un disco a 52.5, el
 * glifo acabó en 4.6 — negro dentro de una barra de iconos claros.
 *
 * Dos formas de estar a salvo, y basta una:
 *
 *  - **Contraste suficiente** ([ACTIVE_GLYPH_MIN_CONTRAST]): `ensureContrast` no interviene.
 *  - **Estar del lado bueno**: si interviene, que sea empujando el glifo MÁS hacia su propio lado en
 *    vez de cruzarlo. Eso se decide con la misma pregunta que se hace `ensureContrast` —¿contrasta
 *    más el negro o el blanco contra el disco?— comparada con el lado en el que el glifo ya está.
 *    Duplicar aquí ese criterio es deliberado: la alternativa es una constante con el tono del cruce
 *    (~49.4), que es el mismo dato escrito de una forma que no avisa si `ensureContrast` cambia.
 */
private fun keepsGlyph(discTone: Double, glyphTone: Double): Boolean {
    if (Contrast.ratioOfTones(glyphTone, discTone) >= ACTIVE_GLYPH_MIN_CONTRAST) return true
    val darkWins = Contrast.ratioOfTones(0.0, discTone) >= Contrast.ratioOfTones(100.0, discTone)
    return darkWins == (glyphTone < discTone)
}

/**
 * Medidas del botón de play. **Las tres son PROPIAS, no de la escala de icon button**, y se
 * calibraron mirando la pantalla: el contenedor Large son 96 de alto y el `Wide` 128 de ancho, y esa
 * combinación deja la píldora de pausa rechoncha (1,33:1) además de comerse la carátula, que es
 * `weight(1f)` y vive de lo que sobra en la columna.
 *
 * Lo que cambia con el estado es solo el ANCHO, como en los laterales: **80×80** cuando suena
 * (cuadrado ⇒ la cookie de 9 lados que gira) y **120×80** en pausa (píldora, 1,5:1). El ALTO es fijo
 * — el mismo que el diseño original — y por eso el botón ya no se achata al pausar.
 *
 * Lo único del spec que sobrevive aquí es el ICONO: los 32 del Large, que sobre 80 de contenedor dan
 * un 40 %, la proporción "llena" del Medium. No cambia entre estados — hacerlo haría parpadear el
 * glifo en cada play/pausa.
 *
 * **Ojo con las coincidencias**: 80 y 120 aparecen en otros sitios (destello del doble toque,
 * fundido de las letras) sin ninguna relación con esto. Compartir el dígito no es compartir el
 * concepto.
 */
private val PlayButtonHeight = 80.dp

private val PlayButtonPlayingWidth = 80.dp

private val PlayButtonPausedWidth = 120.dp

/**
 * Prev/next son un icon button **MEDIUM del spec** (21 ago 2026, decisión del usuario): 56 de alto,
 * 56 de ancho sonando y 48 en pausa, con el glifo de 24 de su propia talla.
 *
 * **Es una vuelta atrás deliberada.** El 20 ago se subieron a la talla VISUAL del play (72×72 y
 * 56×72, glifo 32) razonando que tres piezas del mismo rango se leen como un grupo, y en device
 * resultó lo contrario: con los laterales tan grandes, el play deja de ser el centro del transporte.
 * Con Medium el reparto vuelve a ser el del spec —una acción principal y dos secundarias— y de paso
 * la carátula recupera el alto que aquello le quitaba, porque es `weight(1f)` y paga todo lo que
 * crezca en esta columna.
 *
 * Lo que SÍ se conserva de aquella pasada, porque era correcto y es del spec:
 *  - **El alto no cambia con el estado.** Lo único que el spec mueve entre variantes es el ancho;
 *    animar el alto hacía latir la fila entera al pausar.
 *  - **En pausa se estrechan a la variante Narrow** de su talla (48 contra 56 del Uniform).
 *
 * `CookieVisualRatio` —la media de los dos radios de la cookie del play, que es lo que igualaba un
 * círculo a ella a la vista— se ELIMINÓ con este cambio: solo existía para derivar estos dos
 * tamaños. Si alguna vez se vuelve a igualar prev/next al play, está explicado en `LyricsScreen`,
 * donde esa escala sigue viva.
 */
private val SideButtonHeight = 56.dp

private val SideButtonUniformWidth = SideButtonHeight

private val SideButtonNarrowWidth = 48.dp

/**
 * Glifos del transporte, cada uno el de SU talla: el play lleva `LargeIconButtonTokens.IconSize`
 * (32) y prev/next el del Medium (24). Estuvieron los tres en 32 mientras los laterales fueron de
 * la talla del play.
 *
 * En **sp** y no en dp porque un `MaterialSymbol` es tipografía (fuente variable), así que escala
 * con el ajuste de tamaño de texto del sistema — a escala 1 coincide exactamente con el token.
 */
private val PlayButtonIconSize = 32.sp

private val SideButtonIconSize = 24.sp

