package com.qhana.siku.ui.screens

import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.components.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.components.pixelPacedClock
import com.qhana.siku.ui.components.stepMillisFor
import kotlinx.coroutines.delay

/*
 * Barra de progreso del NowPlaying en sus dos variantes (píldora plana y onda propia, ver
 * Ajustes → Apariencia), el nivel de búfer, los chips de tiempo y el chip de formato con su
 * ficha técnica.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressSlider(
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    /** Hasta dónde hay audio cargado: tercer nivel del track, solo visible en streaming. */
    bufferedPositionFlow: StateFlow<Long>,
    /**
     * Si esta canción SE ESTÁ STREAMEANDO. El player reporta `bufferedPosition` para cualquier
     * fuente —con un archivo en disco salta a la duración entera en cuanto abre—, así que sin
     * esta puerta el nivel aparecería también en lo local y lo descargado, donde no informa de
     * nada. Es el ORIGEN lo que decide, no el valor del búfer.
     */
    showBuffer: Boolean,
    onSeek: (Long) -> Unit,
    /**
     * El color del fondo a una fracción dada de la altura de la pantalla. De él sale TODO el color
     * de aquí, así que **no vale `AppColors.surface`**: esta pantalla no tiene ese fondo en ninguno
     * de sus dos modos —sólido es `surfaceContainer`, degradado es un punto del degradado— y elegir
     * un rol contra un fondo que no es el de debajo tira abajo la premisa entera.
     *
     * Es una FUNCIÓN y no un color porque dónde cae esta barra lo decide el layout: la carátula es
     * `weight(1f)`, así que la posición depende de la portada, de la orientación y del teléfono.
     */
    groundAt: (Float) -> Color,
    format: AudioFormatInfo,
    /** Chip de formato con ficha técnica en vez de solo el contenedor. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: track ONDULADO (Expressive) en vez de la píldora plana. */
    wavy: Boolean = false,
    /**
     * Grosor de la barra (Ajustes → Apariencia). Gobierna los DOS modos: de él sale toda la
     * geometría que escala (trazo y amplitud de la onda, indicador, alto del palo, zona táctil),
     * ver [progressMetricsFor].
     */
    trackHeight: Dp = ComponentConfig.ProgressTrackHeight,
    /**
     * Ajustes → Apariencia: el HANDLE (palo vertical) se queda puesto. Apagado sigue apareciendo
     * mientras se arrastra —es la affordance que dice dónde va a caer el dedo—, así que este ajuste
     * no es "palo sí/no" sino "permanente o solo al buscar".
     */
    showHandle: Boolean = true,
    /** Solo para [wavy]: en pausa la onda se APLANA (como el reproductor de Android 16). */
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    // `showHandle` entra en la geometría porque decide cuánto alto hay que RESERVAR: con el palo
    // apagado la barra solo dibuja su riel, y guardarle sitio al palo dejaba 16dp de aire muerto
    // contra los chips de tiempo (ver [ProgressMetrics.touchHeight]).
    val metrics = remember(trackHeight, wavy, showHandle) {
        progressMetricsFor(trackHeight, wavy, persistentHandle = showHandle)
    }
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val bufferedPosition by bufferedPositionFlow.collectAsStateWithLifecycle()

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // La duración que lee el detector de gestos al SOLTAR. Por referencia y no capturada, para que
    // el `pointerInput` pueda llevar clave `Unit` y no se relance con cada canción — ver el
    // comentario del detector, donde está el bug que eso provocaba.
    val currentDuration = rememberUpdatedState(duration)
    // Ancho medido de la barra. Lo necesita SOLO la etiqueta del tiempo, que se coloca por layout;
    // los tracks lo reciben del tamaño de su propio canvas.
    var trackWidthPx by remember { mutableIntStateOf(0) }
    // Alto medido de la etiqueta, para colocarla a la distancia que pide el spec sin depender de que
    // el token acierte con cualquier escala de fuente. Se siembra con el token: a escala normal es
    // exacto y no hay ni un frame en el sitio equivocado.
    val density = LocalDensity.current
    var labelHeightPx by remember(density) {
        mutableIntStateOf(with(density) { ComponentConfig.ProgressLabelHeight.roundToPx() })
    }
    // El detector de gestos vive mientras la barra exista (su `pointerInput` no se rearma con cada
    // recomposición), así que capturaría la PRIMERA lambda de búsqueda y se quedaría con ella.
    val seek by rememberUpdatedState(onSeek)

    val displayPosition by remember {
        derivedStateOf {
            if (isDragging) sliderPosition
            else if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
        }
    }

    // Fracción del búfer: solo en streaming (ver [showBuffer]) y mientras quede algo por cargar
    // —una vez descargada entera, el nivel lleno de un tercer color sería ruido permanente.
    //
    // La clave `showBuffer` es OBLIGATORIA: es un Boolean plano, no un State, así que el
    // `derivedStateOf` lo CAPTURA y sin re-crearlo se queda con el valor del primer paso. Ese era
    // el bug de "el búfer no desaparece cuando termina la descarga": la canción pasa de STREAMING
    // a DOWNLOADED en cuanto la fila de BD trae el `file://`, showBuffer llega en false... y el
    // derivado seguía leyendo el true de cuando abrió el reproductor.
    val bufferedFraction by remember(showBuffer) {
        derivedStateOf {
            if (!showBuffer || duration <= 0L) 0f
            else (bufferedPosition.toFloat() / duration.toFloat())
                .coerceIn(0f, 1f)
                .takeIf { it < BUFFER_COMPLETE_FRACTION } ?: 0f
        }
    }

    // Progreso hablado. Sin esto una barra de rango se anuncia en PORCENTAJE ("45 por ciento"), que
    // en una canción no significa nada para quien no ve la pantalla.
    val spokenProgress = stringResource(
        R.string.np_progress_state,
        spokenTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
        spokenTime(duration)
    )

    // Aparición de la ETIQUETA (el value indicator con el tiempo) al arrastrar. Aparece SOLO al
    // buscar en los dos ajustes: enseñar el tiempo exacto todo el rato sería ruido, mientras que el
    // palo solo marca la posición.
    // Effects: es alpha. Además es la garantía de que no rebota, que sobre una opacidad
    // significaría pasarse de 1 y volver.
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = appEffectsSpec(),
        label = "bubbleAlpha"
    )

    // Presencia del THUMB (palo). Con [showHandle] es permanente —como el thumb de un Slider de M3,
    // que es la referencia que se sigue— y el valor queda pinado en 1: el `animateFloatAsState` solo
    // se mueve cuando el ajuste está apagado y el dedo entra o sale, así que no produce frames en
    // reposo (ver la regla de "cero productores continuos" en Motion.kt).
    //
    // De este mismo número sale, interpolada, la geometría del hueco fill↔palo↔riel en los dos
    // tracks: por eso es un Float animado y no un Boolean — conmutar daría un salto en el frame en
    // que el palo aparece, justo cuando el dedo ya está en la barra.
    val handleAlpha by animateFloatAsState(
        targetValue = if (showHandle || isDragging) 1f else 0f,
        animationSpec = appEffectsSpec(),
        label = "handleAlpha"
    )

    // ANCHO del palo: se AFINA a la mitad mientras el dedo está en la barra, que es lo que hace el
    // thumb del `Slider` de M3 Expressive (`thumbSize.width / 2` con cualquier press, drag o foco).
    // Es la única respuesta al tacto que tiene un objeto de 4dp que ni se mueve de su sitio ni
    // cambia de color, y es lo que faltaba para que este palo se comportara como el del spec y no
    // solo se le pareciera.
    //
    // Spatial —es geometría— y del token FAST, que es el que el spec reserva para los gestos
    // pequeños: el rebote de un palito respondiendo al dedo ES el efecto. El Slider oficial lo
    // conmuta de golpe (un `.size()` sin animación, porque ahí el thumb es un layout y no un
    // dibujo); acá se anima por lo mismo que se interpola todo lo demás de esta barra, que el
    // cambio ocurre con el dedo encima y un salto se ve.
    //
    // El rebote puede pasarse por debajo del objetivo, así que el consumidor lo recorta a 0 antes de
    // dibujar (regla de Motion.kt: un spec spatial sobre un valor con suelo hay que acotarlo).
    val handleDrawWidth by animateDpAsState(
        targetValue = if (isDragging) ComponentConfig.ProgressHandlePressedWidth
        else ComponentConfig.ProgressHandleWidth,
        animationSpec = appFastSpatialSpec(),
        label = "handleDrawWidth"
    )

    // DÓNDE cae este bloque dentro de la pantalla, para poder preguntarle a [groundAt] el color que
    // tiene detrás. Se siembra a media pantalla: es el orden de magnitud correcto, así que el primer
    // frame ya sale con un fondo razonable en vez de con un extremo del degradado.
    //
    // `onGloballyPositioned` corre en cada pasada de layout, pero esto solo cambia cuando el bloque
    // se MUEVE de verdad (girar, otra portada): escribir el mismo Float en un `mutableFloatStateOf`
    // no invalida nada.
    var groundFraction by remember { mutableFloatStateOf(0.5f) }
    val ground = groundAt(groundFraction)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val screenHeight = coords.findRootCoordinates().size.height
                if (screenHeight > 0) {
                    val centerY = coords.positionInRoot().y + coords.size.height / 2f
                    groundFraction = (centerY / screenHeight).coerceIn(0f, 1f)
                }
            }
    ) {
        // Barra de grosor ajustable (12dp por defecto) con la geometría del `Slider` de M3: fill y
        // riel separados por un hueco permanente (`ProgressHandleGap`, el `ActiveHandleLeadingSpace`
        // del spec), extremos interiores con `ProgressTrackInsideCorner` y exteriores redondos,
        // stop indicator al final y el handle (thumb) permanente —como en las barras de progreso del
        // spec Expressive; conmutable en Ajustes, ver [showHandle]—. El hueco estuvo descartado un
        // tiempo —"con la canción por terminar se veía raro"— y volvió porque sin él el fill y el
        // riel se tocaban y la barra se leía como una sola pieza de dos colores.
        //
        // NO usa el `Slider` de M3, y no es reimplementar por gusto: de él solo quedaba el gesto y
        // el layout, porque riel, búfer, fill, indicador, onda y handle ya se dibujaban a mano. Eso
        // dejaba el borde del fill gobernado por DOS sistemas de coordenadas —el thumb lo colocaba
        // el LAYOUT, redondeando a píxel entero, mientras el fill se pintaba en FLOAT— y de ahí
        // salía el píxel de punta que asomaba por detrás del palo. Acá la posición sale de UNA
        // función ([progressHandleCenterX]) que usan el gesto y los dos tracks, así que el desfase
        // no puede existir. Lo que el Slider daba gratis —semántica de rango y acción de progreso—
        // se repone explícito.
        val handleWidth = ComponentConfig.ProgressHandleWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.touchHeight)
                .semantics {
                    // Sin esto TalkBack no anuncia el control como una barra con rango, y
                    // `setProgress` es lo que permite moverlo sin poder arrastrar. El texto hablado
                    // va en tiempo, no en porcentaje: "45 por ciento" de una canción no dice nada.
                    progressBarRangeInfo = ProgressBarRangeInfo(displayPosition, 0f..1f)
                    stateDescription = spokenProgress
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
                // UN solo detector para toque y arrastre, no `detectTapGestures` +
                // `detectHorizontalDragGestures`. Dos motivos, los dos de comportamiento:
                //  - los detectores de arrastre esperan al TOUCH SLOP, así que un dedo apoyado sin
                //    mover no mostraría el palo ni la etiqueta; acá aparecen en el `down`, como en
                //    el Slider oficial.
                //  - un toque es un arrastre de longitud cero, así que tratar los dos por el mismo
                //    camino elimina la posibilidad de que difieran (y de que se peleen por el
                //    evento).
                // **Clave `Unit`, y la duración leída por referencia** (20 ago 2026). Con
                // `pointerInput(duration)` el detector se RELANZABA en cada cambio de canción, y
                // relanzar cancela la corrutina: si el dedo estaba en la barra en ese instante, el
                // `isDragging = false` del final no llegaba a ejecutarse nunca y la etiqueta de
                // tiempo se quedaba flotando para siempre, como si hubiera un dedo apoyado (visto
                // en device). Lo agravaba que el reproductor es PERSISTENTE: ese `remember` no se
                // reinicia al cerrar, así que una vez pegado ya no se despegaba ni cambiando de
                // canción. La duración sólo hace falta al SOLTAR, así que se lee del State y el
                // gesto deja de tener motivo para reiniciarse.
                .pointerInput(Unit) {
                    val handleWidthPx = handleWidth.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        // `finally` y no sólo la línea al final del bloque: un `awaitEachGesture`
                        // puede cancelarse en cualquier `await` (el nodo deja de colocarse al
                        // guardar el reproductor, otro gesto se lleva el puntero, cambia una clave),
                        // y en una cancelación el código de después NO corre. Es la garantía de que
                        // este estado no puede quedarse encendido pase lo que pase.
                        try {
                            sliderPosition = progressFractionForX(
                                down.position.x, size.width.toFloat(), handleWidthPx
                            )
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                // El puntero desapareció (otro gesto se lo llevó): no se busca nada.
                                if (change == null) {
                                    cancelled = true
                                    break
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    change.consume()
                                    break
                                }
                                sliderPosition = progressFractionForX(
                                    change.position.x, size.width.toFloat(), handleWidthPx
                                )
                                change.consume()
                            }
                            if (!cancelled) seek((sliderPosition * currentDuration.value).toLong())
                        } finally {
                            isDragging = false
                        }
                    }
                }
                // El ancho lo necesita la ETIQUETA, que se coloca por layout y no en el canvas.
                .onSizeChanged { trackWidthPx = it.width },
            contentAlignment = Alignment.Center
        ) {
            // [contentRoleOn] prueba roles tintados de M3 contra la superficie y solo deriva de
            // `primary` si ninguno llega — role-first, resultado siempre tintado, nunca gris.
            // Activo a 4.5:1 (el más oscuro = el progreso), búfer a 2.5:1 (medio), riel a 1.5:1
            // (recede hacia la superficie).
            //
            // Los roles se leen DENTRO del lambda: leerlos fuera suscribiría a cada uno de ellos, y
            // `version` sube en CADA frame del fundido de color (`AppColorScheme.updateFrom`), así
            // que ese `remember` se recalcula por frame — cuanto más chico sea su scope, mejor. Por
            // eso vive aquí y no en el cuerpo del slider, donde arrastraría el `pointerInput` y el
            // `semantics` en cada uno de esos frames.
            val scheme = LocalAppColors.current
            val (activeColor, bufferColor, inactiveColor) = remember(scheme.version, ground) {
                Triple(
                    contentRoleOn(ground, 4.5f, listOf(scheme.primary, scheme.secondary, scheme.onSecondaryContainer), scheme.primary),
                    contentRoleOn(ground, 2.5f, listOf(scheme.secondary, scheme.tertiary, scheme.primary), scheme.primary),
                    contentRoleOn(ground, 1.5f, listOf(scheme.secondaryContainer, scheme.primaryContainer), scheme.primary),
                )
            }
            if (wavy) {
                WavyTrack(
                    fraction = displayPosition.coerceIn(0f, 1f),
                    bufferedFraction = bufferedFraction,
                    isPlaying = isPlaying,
                    isDragging = isDragging,
                    handleAlpha = handleAlpha,
                    handleDrawWidth = handleDrawWidth,
                    metrics = metrics,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor,
                    bufferColor = bufferColor
                )
            } else {
                FlatTrack(
                    fraction = displayPosition.coerceIn(0f, 1f),
                    bufferedFraction = bufferedFraction,
                    isPlaying = isPlaying,
                    isDragging = isDragging,
                    handleAlpha = handleAlpha,
                    handleDrawWidth = handleDrawWidth,
                    metrics = metrics,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor,
                    bufferColor = bufferColor
                )
            }

            // VALUE INDICATOR: la etiqueta con el tiempo, en PASTILLA (esquina completa) y con el par
            // inverseSurface/inverseOnSurface del spec. Ya no es el pin con punta del Material viejo
            // ni va del color del acento — ver [ComponentConfig.ProgressLabelBottomSpace] y vecinos
            // para los tokens y el porqué del cambio.
            //
            // Se centra con la MISMA función que el palo, así que no puede quedar desalineada de él;
            // el ancho lo pone el texto (de "0:07" a "1:23:45") con el suelo del spec
            // ([ComponentConfig.ProgressLabelMinWidth]), o con un tiempo corto la pastilla
            // degeneraría en un círculo aplastado.
            //
            // La etiqueta se sale de la caja de la barra por arriba A PROPÓSITO, así que se mide SIN
            // las constraints del padre (ver el `Constraints()` de abajo) y el Box no recorta.
            if (bubbleAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            val centerX = progressHandleCenterX(
                                displayPosition, trackWidthPx.toFloat(), handleWidth.toPx()
                            )
                            // Vertical DERIVADA: el borde inferior de la pastilla queda a
                            // `ProgressLabelBottomSpace` del borde superior del palo, así que la
                            // etiqueta sube sola cuando el usuario engorda la barra (el palo crece
                            // con ella). El desplazamiento es desde el CENTRO, de ahí las dos mitades.
                            //
                            // El alto es el MEDIDO y no el token: con la fuente del sistema ampliada
                            // la pastilla crece, y con el número fijo se metería dentro del aire del
                            // spec. `ProgressLabelHeight` es su semilla, así que a escala normal
                            // acierta desde el primer frame.
                            val above = metrics.handleHeight.toPx() / 2f +
                                ComponentConfig.ProgressLabelBottomSpace.toPx() +
                                labelHeightPx / 2f
                            IntOffset(centerX.roundToInt(), -above.roundToInt())
                        }
                        // Dos cosas de una, y las dos por la misma razón (el tamaño no se conoce hasta
                        // medir el texto, de "0:07" a "1:23:45"):
                        //  - se mide con `Constraints()` SIN límites en vez de con las del padre: la
                        //    caja de la barra mide lo que el palo, y con la fuente del sistema
                        //    ampliada la pastilla no cabría y saldría comprimida en vez de asomar.
                        //  - se corre media pastilla a la izquierda, que es lo que la deja CENTRADA
                        //    sobre el palo; en el `offset` de arriba no se puede, porque allí todavía
                        //    no hay ancho que partir.
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints())
                            layout(placeable.width, placeable.height) {
                                placeable.place(-placeable.width / 2, 0)
                            }
                        }
                        .graphicsLayer { alpha = bubbleAlpha }
                        // Contenedor de la etiqueta del spec: 44dp de alto y 48 de ancho, los dos
                        // como MÍNIMO y no fijos — el texto crece con la fuente del sistema y una
                        // medida clavada lo recortaría.
                        .defaultMinSize(
                            minWidth = ComponentConfig.ProgressLabelMinWidth,
                            minHeight = ComponentConfig.ProgressLabelHeight
                        )
                        .onSizeChanged { labelHeightPx = it.height }
                        .background(
                            AppColors.inverseSurface,
                            RoundedCornerShape(percent = 50)
                        )
                        .padding(
                            horizontal = ComponentConfig.ProgressLabelPaddingHorizontal,
                            vertical = ComponentConfig.ProgressLabelPaddingVertical
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatTime((sliderPosition * duration).toLong()),
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.inverseOnSurface,
                        maxLines = 1
                    )
                }
            }
        }

        // Barra y chips de tiempo son la MISMA pieza (los chips leen la barra), así que van al
        // nivel más junto de la escala. Estuvo en 6dp, el único valor de la pantalla que no caía en
        // la rejilla de 4 de Material.
        Spacer(modifier = Modifier.height(NowPlayingConfig.ItemGap))

        ProgressChipsRow(
            elapsed = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
            total = formatTime(duration),
            format = format,
            detailedFormat = detailedFormat,
            onToggleDetailedFormat = onToggleDetailedFormat,
            backgroundColor = ground
        )
    }
}

/**
 * Los tres chips bajo la barra: tiempo transcurrido, formato y duración.
 *
 * **Su color NO sale de [contentRoleOn] aunque el de la barra sí**, y la diferencia no es de gusto:
 * `contentRoleOn` mide con RATIO WCAG, que es la unidad correcta para CONTENIDO sobre un contenedor
 * y la equivocada para una SUPERFICIE sobre otra. La luminancia se comprime cerca del blanco, así
 * que el mismo salto tonal da ratios muy distintos según dónde caiga en la escala: medido,
 * `secondaryContainer` (T90) contra el fondo del reproductor da **1,23:1 en claro y 1,98:1 en
 * oscuro** — con un umbral único, en claro no llega nunca y en oscuro llega siempre.
 *
 * Una píldora sobre un fondo va por [roleLayerOn], que escoge por TONO el par tabulado que mejor se
 * apoya ahí. Es el mismo criterio que la barra —mirar el fondo real— con la unidad que corresponde,
 * y sin derivar: el contenedor sale de la paleta y su etiqueta es el `on…` que M3 le garantiza.
 *
 * Es un composable aparte —y no un `Box` dentro de [ProgressSlider]— por el mismo COLOR: ese
 * `remember` va clavado a `version`, que sube en cada frame del fundido, y en el cuerpo del slider
 * arrastraría también al detector de gestos y a la semántica de la barra en cada uno de ellos.
 */
@Composable
private fun ProgressChipsRow(
    elapsed: String,
    total: String,
    format: AudioFormatInfo,
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Color real de lo que hay debajo; ver el parámetro homónimo de [ProgressSlider]. */
    backgroundColor: Color
) {
    val colors = LocalAppColors.current
    val (containerColor, contentColor) = remember(colors.version, backgroundColor) {
        roleLayerOn(backgroundColor, playerRolePairs(colors))
    }
    // Tiempos a los extremos y FORMATO al medio. Un Box (no una Row con SpaceBetween):
    // así el chip de formato queda centrado con la barra de verdad, sin depender de que
    // los dos tiempos midan lo mismo (no lo hacen: "0:07" vs "12:41").
    Box(modifier = Modifier.fillMaxWidth()) {
        TimeChip(
            text = elapsed,
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        FormatChip(
            format = format,
            detailed = detailedFormat,
            onToggleDetailed = onToggleDetailedFormat,
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier.align(Alignment.Center)
        )
        TimeChip(
            text = total,
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * Vista previa de la barra para Ajustes → Apariencia → Barra de progreso. Dibuja los tracks REALES
 * ([FlatTrack] / [WavyTrack]) con la misma geometría derivada, no una imitación: es la única forma
 * de que lo que se ve al elegir sea lo que se ve al reproducir — un dibujo aparte se desincroniza
 * del de verdad en el primer retoque y nadie se entera.
 *
 * Sin gesto y sin tiempos. El palo se dibuja según el ajuste ([showHandle]) porque la previa enseña
 * la barra EN REPOSO, que es como se ve el 99 % del tiempo: con el palo apagado aparece solo con el
 * dedo encima, y mostrarlo aquí haría elegir mirando algo que luego no está. La onda se anima
 * (`isPlaying = true`) porque el movimiento ES la diferencia entre los dos modos.
 */
@Composable
internal fun ProgressBarPreview(
    wavy: Boolean,
    trackHeight: Dp,
    showHandle: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    // **`persistentHandle = true` SIEMPRE, al revés que la barra real**, y no es un descuido: acá el
    // palo entra y sale INTERPOLANDO cada vez que se toca el interruptor, así que la previa lo
    // dibuja durante toda la animación y necesita su sitio. Atarlo a `showHandle` haría que la caja
    // pegara un salto de 20dp en el mismo frame en que empieza el fundido — justo en la pantalla
    // donde se está mirando. Allá el ajuste está quieto y el aire muerto se ve; acá está cambiando
    // y lo que se vería es el salto.
    val metrics = remember(trackHeight, wavy) {
        progressMetricsFor(trackHeight, wavy, persistentHandle = true)
    }
    // Mismo tratamiento que en la barra real: el palo entra y sale interpolando, así que conmutar
    // el switch se ve como el palo retirándose y los huecos cerrándose, no como un salto.
    val handleAlpha by animateFloatAsState(
        targetValue = if (showHandle) 1f else 0f,
        animationSpec = appEffectsSpec(),
        label = "previewHandleAlpha"
    )
    // Reserva el alto del PALO, igual que la barra real: asoma 14dp del riel por arriba y por abajo
    // (el asomo del spec), así que una caja del alto del riel lo dejaría invadiendo lo que tenga
    // encima y debajo en la pantalla de Ajustes.
    Box(
        modifier = modifier.fillMaxWidth().height(metrics.touchHeight),
        contentAlignment = Alignment.Center
    ) {
        if (wavy) {
            WavyTrack(
                fraction = PREVIEW_FRACTION,
                bufferedFraction = 0f,
                isPlaying = true,
                isDragging = false,
                handleAlpha = handleAlpha,
                // La previa enseña la barra EN REPOSO (ver el KDoc): el palo afinado es la respuesta
                // al dedo, y aquí no hay dedo.
                handleDrawWidth = ComponentConfig.ProgressHandleWidth,
                metrics = metrics,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                // La previa nunca muestra búfer (`bufferedFraction = 0`); el valor es indiferente.
                bufferColor = AppColors.secondary
            )
        } else {
            FlatTrack(
                fraction = PREVIEW_FRACTION,
                bufferedFraction = 0f,
                // La previa no avanza (fracción fija), así que no hay tramo que interpolar; el
                // parámetro va a `true` para que el track sea el MISMO que en reproducción.
                isPlaying = true,
                isDragging = false,
                handleAlpha = handleAlpha,
                handleDrawWidth = ComponentConfig.ProgressHandleWidth,
                metrics = metrics,
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                bufferColor = AppColors.secondary
            )
        }
    }
}

/**
 * Cuánto se mete hacia adentro el stop indicator de la ONDA cuando es más fino que el trazo: un
 * cuarto del trazo, el `trackStroke.width / 4f` de `LinearWavyProgressModifiers.drawStopIndicator`.
 * Es lo que impide que el cap redondo del riel se lo trague por el borde derecho.
 */
private const val WAVE_STOP_INSET_DIVISOR = 4f

/** Avance que enseña la vista previa: lo justo para que se lea como una canción en curso. */
private const val PREVIEW_FRACTION = 0.62f

/**
 * Centro del handle para una [fraction] dada, en píxeles. **Es la ÚNICA definición de "dónde está
 * la posición" en la barra**: la usan el gesto (a través de su inversa [progressFractionForX]), la
 * píldora plana y la onda. Tenerla en un solo sitio es lo que garantiza que el fill, el palo, la
 * etiqueta y el dedo no puedan discrepar ni por un píxel — que es lo que pasaba cuando el
 * palo era el `thumb` de un `Slider` (colocado por layout) y el fill se pintaba aparte.
 *
 * El recorrido va inset media anchura de handle a cada lado, o el palo se saldría del contenedor al
 * principio y al final de la canción.
 */
private fun progressHandleCenterX(fraction: Float, width: Float, handleWidth: Float): Float =
    handleWidth / 2f + (width - handleWidth) * fraction.coerceIn(0f, 1f)

/**
 * Posición dibujada de la barra, INTERPOLADA entre dos ticks de posición. La usan los DOS tracks:
 * la posición real llega a TIRONES —una vez por segundo, el bucle de `MusicPlayerScreen`, lento a
 * propósito para no despertar el main thread en cada frame—, y ese escalón es visible en los dos
 * modos: en la onda estira el trazo de golpe y en la píldora plana el borde del relleno (y el palo,
 * que va permanente por defecto) SALTA un píxel por segundo en vez de avanzar.
 *
 * Vivía dentro de `WavyTrack`, y por eso el modo plano se movía a tirones: dos tracks del mismo
 * control no pueden avanzar con dos relojes distintos. Sacarlo aquí es además lo que garantiza que
 * alternar el ajuste de Apariencia no cambie el RITMO de la barra, igual que [progressHandleCenterX]
 * garantiza que no cambie su posición.
 *
 * Este reloj y el de la fase de la onda son los DOS únicos de la barra que no salen del
 * MotionScheme, por el mismo motivo: tienen que ser LINEALES y durar exactamente lo que dura otra
 * cosa (un tick de posición / un ciclo de onda). Un spring aceleraría y frenaría dentro del tramo,
 * que es justo el tropiezo que se está corrigiendo.
 *
 * **Publica solo cuando el borde del relleno se movió al menos un píxel** de [trackWidthPx]: en una
 * canción de 4 minutos son ~4 publicaciones por segundo y no 120 (un tween invalidaba cada vsync
 * para mover tres centésimas de píxel). El reloj duerme entre píxeles ([pixelPacedClock]), así que
 * entre publicaciones quedan vsyncs vacíos y la cola de SurfaceFlinger drena — ver la regla "cero
 * productores continuos" en CLAUDE.md.
 */
@Composable
private fun rememberSmoothedProgress(
    fraction: Float,
    isPlaying: Boolean,
    /** El usuario está arrastrando: el track debe seguir al dedo, sin un segundo de retraso. */
    isDragging: Boolean,
    /** Ancho medido del track, para el umbral de un píxel. 0 mientras no se haya medido. */
    trackWidthPx: Int
): Float {
    // Con el reproductor GUARDADO (subárbol persistente) no hay nada que suavizar y sí frames que
    // gastar detrás de la biblioteca: se salta al valor bueno, que además es lo que hay que enseñar
    // si se reabre.
    val onScreen = LocalPlayerOnScreen.current
    val smoothFraction = remember { mutableFloatStateOf(fraction) }
    LaunchedEffect(fraction, isPlaying, onScreen, isDragging, trackWidthPx) {
        val from = smoothFraction.floatValue
        val jump = kotlin.math.abs(fraction - from)
        // Un seek (o el cambio de canción) NO se interpola: sería un barrido de un segundo
        // recorriendo toda la barra. Tampoco en pausa, donde no hay avance que suavizar.
        //
        // **Un RETROCESO tampoco, y ése sin umbral que valga** (21 ago 2026). Esto es interpolación
        // entre TICKS de una reproducción, y una reproducción solo AVANZA: cualquier movimiento
        // hacia atrás es un seek, un cambio de pista o un `REPEAT_ONE`, o sea justo lo que tiene que
        // ser instantáneo. Con la condición atada solo a [PROGRESS_SNAP_THRESHOLD] eso se colaba,
        // porque el umbral mira la DISTANCIA y no el SENTIDO: cambiar de canción dentro del primer
        // 5 % de la pista —12 s en una de cuatro minutos, 24 en una de ocho— deja un salto de menos
        // de 0.05, así que la barra se iba para atrás **interpolada durante un segundo entero** en
        // vez de saltar a cero. Bug reportado por el usuario el 21 ago ("retrocede por segundo en
        // lugar de hacerlo de golpe"), difícil de reproducir a voluntad precisamente porque depende
        // de en qué punto de la canción se salte: terminándola sola el salto vale ~1.0 y sí snapea.
        // El mismo agujero se veía al soltar un arrastre corto hacia atrás (con `isDragging` ya en
        // `false`). El umbral se queda para lo que sí puede ser ambiguo: los saltos hacia ADELANTE.
        if (!isPlaying || !onScreen || isDragging || fraction < from || jump > PROGRESS_SNAP_THRESHOLD) {
            smoothFraction.floatValue = fraction
            return@LaunchedEffect
        }
        val stepFraction = if (trackWidthPx > 0) 1f / trackWidthPx else 0f
        // **El reloj que más se ahorra de los cuatro.** Este bucle solo tiene que recorrer el avance
        // de UN tick de posición: con una canción de cuatro minutos y una barra de ~350px, el borde
        // del relleno se mueve 1,5 píxeles por segundo, o sea uno cada ~685 ms. Con `withFrameNanos`
        // eso eran 120 despertares por segundo para publicar 1,5. Ver [pixelPacedClock] para el
        // porqué de no usar el frame clock; aquí el bucle es propio porque tiene condición de salida
        // (`t >= 1`), no es continuo.
        val pixelsPerSecond = jump * trackWidthPx / (POSITION_TICK_MS / 1000f)
        // Sin avance no hay nada que interpolar, y hay que salir ANTES de calcular el intervalo:
        // `stepMillisFor(0)` devuelve `Long.MAX_VALUE` y el bucle se quedaría dormido para siempre
        // en un `delay` del que solo lo sacaría un cambio de key.
        if (pixelsPerSecond <= 0f) {
            smoothFraction.floatValue = fraction
            return@LaunchedEffect
        }
        val step = stepMillisFor(pixelsPerSecond)
        var startNanos = 0L
        var published = from
        var t = 0.0
        while (t < 1.0) {
            val now = System.nanoTime()
            if (startNanos == 0L) startNanos = now
            t = ((now - startNanos) / (POSITION_TICK_MS * 1_000_000.0)).coerceAtMost(1.0)
            val value = (from + (fraction - from) * t).toFloat()
            // La regla del píxel se queda como RED: el intervalo ya está calculado para cumplirla.
            if (kotlin.math.abs(value - published) >= stepFraction || t >= 1.0) {
                published = value
                smoothFraction.floatValue = value
            }
            if (t < 1.0) delay(step)
        }
    }
    return smoothFraction.floatValue
}

/** Inversa exacta de [progressHandleCenterX]: qué fracción representa un toque en [x]. */
private fun progressFractionForX(x: Float, width: Float, handleWidth: Float): Float {
    val usable = width - handleWidth
    return if (usable <= 0f) 0f else ((x - handleWidth / 2f) / usable).coerceIn(0f, 1f)
}

/**
 * Track PLANO del reproductor: píldora de borde redondo superpuesta al riel, con el nivel de búfer,
 * el stop indicator del spec y el handle (thumb) marcando la posición (permanente o solo al
 * arrastrar, según [handleAlpha]).
 *
 * Todo en UN canvas y sin `clip`: el palo mide más que el track (asomar es lo que lo distingue del
 * riel), así que el riel y el búfer se redondean por geometría en vez de apoyarse en la máscara del
 * contenedor, que se lo comería.
 */
@Composable
private fun FlatTrack(
    fraction: Float,
    /** Búfer cargado (0 = no hay nada que contar). */
    bufferedFraction: Float,
    /** Suena: el avance entre ticks se interpola (ver [rememberSmoothedProgress]). */
    isPlaying: Boolean,
    /** El usuario está arrastrando: el relleno va pegado al dedo, sin interpolar. */
    isDragging: Boolean,
    /**
     * Presencia del handle (1 = visible). Vale 1 fijo con el palo permanente —el default, como en
     * las barras del spec Expressive— y va de 0 a 1 con el dedo cuando el usuario lo apagó en
     * Ajustes. De él sale, INTERPOLADA, la geometría del hueco fill↔palo↔riel: en reposo sin palo
     * el fill termina en la posición y el riel arranca un `gap` después; con el palo puesto, los dos
     * se retiran para dejarlo flotando entre dos huecos.
     */
    handleAlpha: Float,
    /**
     * Ancho con el que se DIBUJA el palo: [ComponentConfig.ProgressHandleWidth] en reposo y la mitad
     * mientras el dedo está en la barra, como el thumb del `Slider` de M3. **No entra en la
     * geometría del track** —el hueco, el borde del fill y el arranque del riel se calculan siempre
     * con el ancho de reposo—, exactamente como en el Slider oficial: si el hueco siguiera a este
     * valor, tocar la barra movería el borde del progreso.
     */
    handleDrawWidth: Dp,
    /** Geometría derivada del grosor elegido en Ajustes. */
    metrics: ProgressMetrics,
    activeColor: Color,
    inactiveColor: Color,
    bufferColor: Color
) {
    // Path reutilizado por los tres tramos (fill, riel, búfer): se redibujan en cada tick de
    // posición y en cada frame del arrastre.
    val trackPath = remember { Path() }
    // Avance entre ticks de posición: MISMO reloj que la onda. Sin esto el borde del relleno —y el
    // palo, que va permanente por defecto— daban un salto por segundo en vez de avanzar.
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val drawnFraction = rememberSmoothedProgress(
        fraction = fraction,
        isPlaying = isPlaying,
        isDragging = isDragging,
        trackWidthPx = trackWidthPx
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            // Mismo alto que la onda: si divergen, alternar el ajuste de Apariencia movería el
            // bloque de controles entero.
            .height(metrics.trackHeight)
            // Ancho real del track, para publicar el progreso solo cuando avanza un píxel.
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        val dotRadius = metrics.stopIndicator.toPx() / 2f
        val insideCorner = ComponentConfig.ProgressTrackInsideCorner.toPx()
        // Ancho de REPOSO: es el que define el recorrido y los huecos, pase lo que pase con el dedo.
        val handleW = ComponentConfig.ProgressHandleWidth.toPx()
        // Ancho con el que se PINTA el palo. El recorte es la guardia del rebote del spring spatial.
        val drawnHandleW = handleDrawWidth.toPx().coerceAtLeast(0f)
        val gap = ComponentConfig.ProgressHandleGap.toPx()
        // Radio de esquina TABULADO por el spec, no media altura: solo el tamaño más fino es una
        // píldora perfecta (ver [ProgressMetrics.trackCorner]). Y el centro vertical va aparte —
        // eran el mismo número mientras la barra era siempre una píldora, y confundirlos ahora
        // dejaría el stop indicator flotando fuera del riel.
        val corner = metrics.trackCorner.toPx()
        val centerY = size.height / 2f
        // Ancho de dos esquinas: por debajo, un tramo ya no puede dibujarse con su radio completo.
        // Lo usa el FINAL de la canción (`railFade`) para decidir cuándo al riel no le queda sitio.
        // Con la píldora este umbral era el alto entero; con el radio del spec es menor, así que el
        // riel aguanta hasta más tarde. NO lo usa el relleno: allí el radio se acota y no se omite.
        val minSegment = corner * 2f
        val handleVisible = handleAlpha > 0f
        val handleCenterX = progressHandleCenterX(drawnFraction, size.width, handleW)

        // Con el palo puesto, el fill se DETIENE antes de él y el riel arranca después: el palo
        // queda despegado de los dos por un hueco, que es como lo dibuja el spec. Eso es lo que
        // permite que sea del color del ACENTO en vez de un blanco/negro ajeno a la paleta — se
        // separa por GEOMETRÍA (los huecos y su altura), y eso funciona con cualquier acento.
        // Buscarle un color que contraste a la vez con el fill y con el riel no tiene solución
        // general: contra un acento medio, el blanco se funde con el riel claro y el negro con el
        // fill oscuro. Sin hueco por la izquierda el palo quedaría DENTRO del fill, del mismo color,
        // y solo se leería por las puntas que asoman.
        //
        // En reposo (sin palo) el fill termina en la posición y el riel arranca un `gap` después:
        // el hueco existe SIEMPRE, como en la onda. Antes el riel se dibujaba desde 0 y el fill
        // encima, así que en reposo los dos tramos se tocaban y la barra se leía como una sola
        // pieza de dos colores. El ancho mínimo de un círculo completo evita que la píldora se
        // deforme al inicio de la canción.
        //
        // Las dos medidas se INTERPOLAN con `handleAlpha` en vez de conmutar (mismo motivo que en
        // la onda: un `if` daría un salto en el frame en que aparece el palo). En reposo salen
        // `handleCenterX` y `handleCenterX + gap`; con el palo puesto, exactamente los bordes que
        // lo dejan flotando entre los dos huecos.
        val fillEnd = handleCenterX - (handleW / 2f + gap) * handleAlpha
        // Sin suelo: el relleno se dibuja desde el primer píxel, por corto que sea (los radios se
        // acotan abajo). Tuvo uno —el ancho de dos esquinas— para no pintar una píldora deformada, y
        // ese suelo se COMÍA el principio de la canción: con la barra en 24dp y el hueco de 8 que el
        // fill le cede al palo, a los 12 s de una canción de 3:40 el tramo medía 13dp contra 16 de
        // umbral y NO SE DIBUJABA NADA. Un progreso que no aparece hasta pasado medio minuto es peor
        // que un progreso corto.
        val fillWidth = fillEnd.coerceAtLeast(0f)
        // Sale de `handleCenterX`, **espejo exacto de `fillEnd`**: el hueco de la derecha mide lo
        // mismo que el de la izquierda por construcción, pase lo que pase con el progreso.
        //
        // Salía del fill YA ACOTADO (`fillWidth`), y eso lo rompía al principio de la canción: con
        // el progreso por debajo del hueco, `fillEnd` es negativo y el `coerceAtLeast(0f)` se comía
        // esa parte, así que el riel arrancaba `gap` más a la derecha de lo que le tocaba — 12dp de
        // hueco tras el palo contra los 6 de la izquierda, o sea los dos lados de la barra con
        // distinta forma justo en los primeros segundos, que es cuando se mira el reproductor recién
        // abierto. Álgebra: con `fillEnd ≥ 0` las dos fórmulas dan EXACTAMENTE el mismo número
        // (`hc + gap + handleW/2 · handleAlpha`), así que esto no mueve nada del resto de la canción.
        //
        // El motivo por el que en su día se derivó del fill —que la píldora tenía un ancho MÍNIMO de
        // un círculo entero y el riel se le montaba encima— ya no existe: ese suelo se quitó (ver
        // `fillWidth`).
        val railStart = handleCenterX + gap + (handleW / 2f) * handleAlpha

        // FINAL DE LA CANCIÓN. Con el riel calculado a secas, el último tramo quedaba en un muñón
        // de unos pocos píxeles —del alto ENTERO de la barra, con el stop indicator dentro— pegado
        // al borde: una gota clara suelta al final del progreso. El riel deja de dibujarse en cuanto
        // no le cabe un círculo completo (es el criterio del `Slider` de M3 sin corner shrinking:
        // `inactiveTrackThreshold` le resta el `cornerSize`) y, en vez de dejar el hueco vacío, el
        // FILL se lo come hasta el borde y remata con la punta redonda entera.
        //
        // Los dos remates se interpolan con `railFade` en vez de conmutar, por lo mismo de siempre:
        // el corte cae en el último segundo de la canción, justo donde un salto se ve.
        // `railFade` es una RAMPA sobre lo que le queda al riel, no un interruptor: vale 0 mientras
        // le quepa un círculo entero y llega a 1 cuando se queda sin ancho. El fill se estira con
        // ella y, como se dibuja DESPUÉS, se va comiendo el muñón y el stop indicator en vez de
        // hacerlos desaparecer de golpe. Con el palo puesto no se estira nada (invadiría su sitio),
        // de ahí el factor `1 - handleAlpha`.
        val railWidth = size.width - railStart
        val railFade = (1f - railWidth / minSegment).coerceIn(0f, 1f) * (1f - handleAlpha)
        val fillRight = fillWidth + (size.width - fillWidth) * railFade
        val fillEndCorner = insideCorner + (corner - insideCorner) * railFade
        if (railWidth > 0f) {
            // Extremos ASIMÉTRICOS, como el track del `Slider` de M3: redondo entero por fuera y
            // `insideCorner` por dentro (el lado que da al hueco). Ver [drawTrackSegment].
            drawTrackSegment(
                left = railStart,
                right = size.width,
                color = inactiveColor,
                startCorner = insideCorner,
                endCorner = corner,
                path = trackPath
            )
            // Búfer: entre el riel apagado y el fill, para que se lea como "esto ya está cargado"
            // sin competir con el progreso real. Acotado al riel visible, o invadiría el hueco.
            // Sus DOS extremos son interiores: el izquierdo comparte borde con el hueco y el
            // derecho es un corte dentro del riel (si llegara al final, el búfer ya no se dibuja).
            val bufferedEndX = size.width * bufferedFraction
            if (bufferedFraction > 0f && bufferedEndX > railStart) {
                drawTrackSegment(
                    left = railStart,
                    right = bufferedEndX,
                    color = bufferColor,
                    startCorner = insideCorner,
                    endCorner = insideCorner,
                    path = trackPath
                )
            }
            // Stop indicator del spec, con SU fórmula de inset (`ProgressIndicator.drawStopIndicator`
            // de material3): media altura, pero TOPADA a `ProgressStopIndicatorTrailingSpace`. El
            // `Slider` usa media altura a secas porque su track es fijo; con el grosor ajustable eso
            // hundía el punto 12dp adentro en 24dp de barra. Con 12dp el tope no muerde y sale el
            // mismo píxel de siempre.
            //
            // Va DENTRO del bloque del riel a propósito: en el Slider oficial el stop se dibuja
            // junto al track inactivo y desaparece con él, no cuando el progreso lo alcanza.
            val stopInset = ((size.height - dotRadius * 2f) / 2f)
                .coerceAtMost(ComponentConfig.ProgressStopIndicatorTrailingSpace.toPx())
            val indicatorX = size.width - dotRadius - stopInset
            if (fillWidth < indicatorX && indicatorX > railStart) {
                drawCircle(color = activeColor, radius = dotRadius, center = Offset(indicatorX, centerY))
            }
        }

        // Cuando el tramo es más estrecho que sus dos radios, los escala [drawTrackSegment] —
        // proporcionalmente, no acotando cada uno a la mitad del ancho, que era lo que igualaba los
        // dos extremos de la barra (ver allí). Es lo que sustituye al suelo de ancho (ver
        // `fillWidth`), y encima es más fiel: el Slider de M3 tampoco tiene un mínimo por debajo del
        // cual el track activo desaparece.
        //
        // Su borde derecho es INTERIOR (da al hueco), así que lleva `insideCorner` y no el radio
        // entero: con la punta redonda completa asomaban "hombros" de riel a los lados del palo
        // —radio del track contra 4dp de palo—, que fue lo que llevó a cortarlo recto. 2dp es lo
        // que hace el Slider oficial y no los produce.
        if (fillRight > 0f) {
            drawTrackSegment(
                left = 0f,
                right = fillRight,
                color = activeColor,
                startCorner = corner,
                endCorner = fillEndCorner,
                path = trackPath
            )
        }
        // El fill NO lleva punto de contraste en la punta. Lo tuvo (el espejo del stop indicator) y
        // se RETIRÓ por decisión del usuario: con el palo puesto se pisaban, y sin él no aportaba
        // nada que el borde de la píldora no dijera ya. Con eso desaparece de paso el único sitio
        // de la barra que necesitaba resolver contraste contra el fill.

        // HANDLE del spec: mismo `handleCenterX` y mismo canvas que el fill y el riel, que es lo
        // único que garantiza que los huecos midan lo que dicen y que no asome un píxel por detrás.
        // Del color del ACENTO, flotando entre los dos huecos.
        //
        // La onda NO necesita el hueco izquierdo y por eso no lo tiene: su trazo mide 4dp contra los
        // 26 del palo, así que la punta se distingue sola. Acá el track mide 12 y sin hueco el palo
        // quedaría embebido en el fill.
        //
        // Se dibuja con `drawnHandleW` (afinado al tocar) pero CENTRADO en el mismo `handleCenterX`
        // que gobierna el hueco: el palo adelgaza sin moverse y sin que nada a su alrededor se
        // entere, igual que el thumb del `Slider`, cuyo contenedor de 4dp no cambia de tamaño.
        if (handleVisible) {
            val handleH = metrics.handleHeight.toPx()
            drawRoundRect(
                color = activeColor,
                alpha = handleAlpha,
                topLeft = Offset(handleCenterX - drawnHandleW / 2f, (size.height - handleH) / 2f),
                size = Size(drawnHandleW, handleH),
                cornerRadius = CornerRadius(drawnHandleW / 2f)
            )
        }
    }
}

/**
 * Un tramo del track plano, con los dos extremos redondeados por SEPARADO. Es lo que hace
 * `SliderDefaults.drawTrackPath` en material3 y no se puede con `drawRoundRect`, que solo acepta un
 * radio para las cuatro esquinas: el extremo exterior de cada tramo va redondo entero y el interior
 * —el que da al hueco— con `ProgressTrackInsideCorner`.
 *
 * El [path] se pasa desde fuera y se rebobina: este dibujo corre en cada frame del arrastre, y una
 * `Path` nueva por tramo serían tres asignaciones por frame.
 */
private fun DrawScope.drawTrackSegment(
    left: Float,
    right: Float,
    color: Color,
    startCorner: Float,
    endCorner: Float,
    path: Path
) {
    if (right <= left) return
    val width = right - left
    // Si los dos radios no caben a lo ancho se escalan **PROPORCIONALMENTE**, que es lo que hace
    // Skia con un `RRect` y lo único que conserva la relación entre el extremo exterior (redondo
    // entero) y el interior (`ProgressTrackInsideCorner`).
    //
    // Hasta el 20 ago 2026 el relleno acotaba cada radio a la MITAD DE SU ANCHO en el call site, y
    // eso los IGUALABA: con el tramo corto —o sea al principio de cada canción— el borde izquierdo
    // de la barra, que es el exterior, perdía redondez hasta empatar con el interior, mientras que
    // el riel de la derecha nunca se acota. Resultado: los dos extremos de la barra dejaban de tener
    // la misma forma. Escalando, el de fuera sigue siendo el más redondo por corto que sea el tramo.
    val demand = startCorner + endCorner
    val fit = if (demand > width) width / demand else 1f
    path.rewind()
    path.addRoundRect(
        RoundRect(
            rect = Rect(Offset(left, 0f), Size(width, size.height)),
            topLeft = CornerRadius(startCorner * fit),
            topRight = CornerRadius(endCorner * fit),
            bottomRight = CornerRadius(endCorner * fit),
            bottomLeft = CornerRadius(startCorner * fit)
        )
    )
    drawPath(path, color)
}

/**
 * Track ONDULADO del reproductor (Ajustes → Apariencia). Dibujado a mano A PROPÓSITO: el
 * `LinearWavyProgressIndicator` oficial anima el aplanado con `DecreasingAmplitudeAnimationSpec`,
 * una constante INTERNA FIJA (500 ms) que no expone por parámetro — al pausar terminaba
 * siempre después que el resto de las animaciones del reproductor y el desfase se notaba.
 * Aquí la amplitud usa el MISMO token del MotionScheme que el morph del botón play
 * (`defaultSpatial`), así todo cierra a la vez.
 *
 * La FASE avanza solo mientras suena y se congela al pausar (un `Animatable` cancelado
 * conserva su valor): sin salto al reanudar y sin gastar frames con el audio detenido.
 * Geometría igual que la píldora plana (alto 12dp, stop indicator) para que alternar el
 * ajuste no mueva el layout. El HANDLE se comporta igual que en la píldora plana: permanente por
 * defecto y, si el usuario lo apaga en Ajustes, solo mientras arrastra — en reposo la punta la
 * remata entonces el propio trazo (cap redondo).
 */
@Composable
private fun WavyTrack(
    fraction: Float,
    /** Búfer cargado (0 = no hay nada que contar); se dibuja recto sobre el riel apagado. */
    bufferedFraction: Float,
    isPlaying: Boolean,
    /** El usuario está arrastrando: el track debe seguir al dedo sin interpolar. */
    isDragging: Boolean,
    /**
     * Presencia del handle (1 = visible). 1 fijo con el palo permanente (el default); de 0 a 1 con
     * el dedo cuando el ajuste está apagado. De él sale, interpolado, el ancho del hueco
     * onda↔palo↔riel y dónde TERMINA la onda: sin palo, en la posición misma.
     */
    handleAlpha: Float,
    /**
     * Ancho con el que se DIBUJA el palo: la mitad mientras el dedo está en la barra, como el thumb
     * del `Slider` de M3. No entra en la geometría (ver el mismo parámetro en [FlatTrack]) — aquí
     * eso importa el doble, porque de `handleW` sale además dónde termina la onda.
     */
    handleDrawWidth: Dp,
    /** Geometría derivada del grosor elegido en Ajustes. */
    metrics: ProgressMetrics,
    activeColor: Color,
    inactiveColor: Color,
    bufferColor: Color
) {
    // MISMO token que el morph del botón play y que las dimensiones del transporte
    // (`defaultSpatial`, ver rememberPlayButtonSpin) para que todos cierren a la vez. Antes eran
    // dos `spring(...)` con los mismos números escritos en dos archivos, que es la forma más fácil
    // de que dejen de coincidir.
    //
    // El RECORTE es obligatorio aquí y no es cosmético: los springs spatial del scheme rebotan, y
    // una amplitud negativa no atenúa la onda — la INVIERTE.
    val rawAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = appSpatialSpec(),
        label = "waveAmplitude"
    )
    val amplitudeFraction = rawAmplitude.coerceIn(0f, 1f)
    // Fase en "número de ondas recorridas", acotada a [0,1): entra en un seno, así que es periódica
    // en 1 vuelta y reencuadrarla no produce ningún salto visible (y el Float no pierde precisión
    // tras horas). El LaunchedEffect se cancela al pausar y el estado se queda donde estaba: sin
    // salto al reanudar.
    //
    // **El reloj es continuo (`withFrameNanos`), pero la fase se PUBLICA solo cuando la onda se
    // ha desplazado al menos un píxel** (`1 / wavelengthPx` vueltas). Antes era un `Animatable`
    // en bucle de un ciclo, y un Animatable invalida el dibujo en CADA vsync: con la onda a 22 dp/s
    // eso son 0,6 px por frame a 120 Hz — la app producía 120 frames por segundo para mover medio
    // píxel. Medido con Perfetto (17 ago): además del gasto, esa producción continua es lo que deja
    // a la app "un buffer por delante" de SurfaceFlinger tras cualquier frame tardío (buffer
    // stuffing) — el estado no drena mientras no haya vsyncs vacíos, sobrevive al cierre del
    // reproductor y se come el primer scroll de la lista. Con vsyncs vacíos entre publicaciones la
    // cola drena sola. Misma regla que el giro de la cookie del play: no dibujar lo que no mueve un
    // píxel.
    //
    // **Corrección del 20 ago 2026**: aquí ponía que `withFrameNanos` "no produce frames por sí
    // mismo: sin invalidación no hay buffer". Lo segundo es cierto y lo primero no — un bucle de
    // `withFrameNanos` mantiene un frame callback pendiente, y con él el Choreographer pidiendo
    // vsyncs por binder: 45 ms de cada segundo en el hilo principal, medidos en tres segundos sin
    // una sola invalidación. El reloj es ahora [pixelPacedClock], que duerme entre píxeles.
    //
    // **Y no corre con el reproductor GUARDADO**: su subárbol es persistente (ver
    // [com.qhana.siku.ui.LocalPlayerOnScreen]), así que sin este gate la onda seguiría animándose
    // detrás de la biblioteca — la misma producción continua de frames, con la agravante de que nadie
    // la ve. La fase se conserva donde estaba, igual que al pausar.
    val onScreen = LocalPlayerOnScreen.current
    val density = LocalDensity.current
    val wavelengthPxForStep = with(density) { ComponentConfig.ProgressWaveLength.toPx() }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, onScreen, wavelengthPxForStep) {
        if (!isPlaying || !onScreen) return@LaunchedEffect
        val stepTurns = 1.0 / wavelengthPxForStep
        val basePhase = phase.floatValue.toDouble()
        var startNanos = 0L
        var published = basePhase
        // La onda recorre una longitud de onda cada `WAVE_MS_PER_CYCLE`, así que avanza
        // `wavelengthPx / (WAVE_MS_PER_CYCLE/1000)` píxeles por segundo: ése es el ritmo al que hay
        // un píxel que enseñar y también al que se despierta (ver [pixelPacedClock]).
        val pixelsPerSecond = wavelengthPxForStep / (WAVE_MS_PER_CYCLE / 1000f)
        pixelPacedClock(stepMillisFor(pixelsPerSecond)) { now ->
            if (startNanos == 0L) startNanos = now
            val turns = basePhase + (now - startNanos) / (WAVE_MS_PER_CYCLE * 1_000_000.0)
            // La regla del píxel se queda como RED: el intervalo ya la garantiza.
            if (turns - published >= stepTurns) {
                published = turns
                phase.floatValue = (turns % 1.0).toFloat()
            }
        }
    }

    // Avance entre ticks de posición: MISMO reloj que la píldora plana, ver [rememberSmoothedProgress].
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val drawnFraction = rememberSmoothedProgress(
        fraction = fraction,
        isPlaying = isPlaying,
        isDragging = isDragging,
        trackWidthPx = trackWidthPx
    )

    // Path reutilizado: este bloque se redibuja en cada frame mientras suena.
    val wavePath = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.trackHeight)
            // Ancho real del track, para publicar el progreso solo cuando avanza un píxel (ver arriba).
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        val centerY = size.height / 2f
        val stroke = metrics.waveStroke.toPx()
        val radius = stroke / 2f
        // Ancho de REPOSO: recorrido, huecos y final de la onda salen de él, nunca del animado.
        val handleW = ComponentConfig.ProgressHandleWidth.toPx()
        // Ancho con el que se PINTA el palo. El recorte es la guardia del rebote del spring spatial.
        val drawnHandleW = handleDrawWidth.toPx().coerceAtLeast(0f)
        val handleH = metrics.handleHeight.toPx()
        val handleVisible = handleAlpha > 0f
        // MISMA función de posición que el gesto y que la píldora plana: alternar el ajuste de
        // Apariencia no puede mover el punto donde cae el dedo.
        val startX = handleW / 2f
        val endX = size.width - handleW / 2f
        val activeEndX = progressHandleCenterX(drawnFraction, size.width, handleW)

        // Riel inactivo: recto siempre (solo la parte reproducida ondula, igual que el spec).
        //
        // El hueco se mide entre BORDES, no entre centros, y ese es el arreglo: `drawLine` coloca
        // su cap redondo CENTRADO en el punto, así que separar los dos puntos por un trazo dejaba
        // los caps tocándose (medio trazo cada uno) y el riel parecía la continuación de la onda.
        // Sumando medio trazo a cada lado, el aire visible es exactamente `ProgressHandleGap` — el
        // mismo que el palo deja en el modo plano, así que no hay un número nuevo que calibrar.
        //
        // El hueco se INTERPOLA con la aparición del palo en vez de conmutar: al buscar hay que
        // dejar sitio al palo (más ancho que el trazo). Con un `if` el riel daría un salto justo en
        // el frame en que aparece el handle. Cuando ya no cabe, el riel simplemente no se dibuja
        // (la canción está por terminar).
        val gap = ComponentConfig.ProgressHandleGap.toPx()
        val restGap = radius + gap + radius
        val dragGap = handleW / 2f + gap + radius
        val inactiveStartX = activeEndX + restGap + (dragGap - restGap) * handleAlpha
        // FINAL DE LA CANCIÓN: mismo criterio que la píldora plana. Sin él, el último tramo queda
        // en un cap redondo suelto con el stop indicator encima —un puntito flotando tras el hueco—
        // en vez de terminar. El riel se apaga cuando le queda menos que su propio grosor, y como
        // la onda ya llega hasta `endX` no hay nada que rellenar: aquí el fill no se estira (sería
        // un tramo recto pegado a una onda), simplemente el resto se desvanece.
        val inactiveWidth = endX - inactiveStartX
        val railAlpha = (inactiveWidth / stroke).coerceIn(0f, 1f)
        if (railAlpha > 0f) {
            drawLine(
                color = inactiveColor,
                alpha = railAlpha,
                start = Offset(inactiveStartX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // Búfer, ENCIMA del riel apagado y recto (ondular también este tramo lo confundiría
            // con lo ya reproducido, que es justo lo que el nivel viene a distinguir).
            val bufferedEndX = startX + (endX - startX) * bufferedFraction
            if (bufferedFraction > 0f && bufferedEndX > inactiveStartX) {
                drawLine(
                    color = bufferColor,
                    start = Offset(inactiveStartX, centerY),
                    end = Offset(bufferedEndX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            // Stop indicator del final, con la fórmula de `LinearWavyProgressModifiers` de material3
            // (`drawStopIndicator`): tamaño FIJO del spec acotado al TRAZO —no al grosor de la
            // barra, que es lo que hacía crecer el punto hasta 8dp con la barra en 24— y del color
            // ACTIVO (`ProgressIndicatorTokens.StopColor` = Primary), igual que en la píldora plana.
            // Pintado con el inactivo era invisible: un punto del color del riel, dentro del riel.
            //
            // Su inset también es el del componente oficial: cuando el punto es más FINO que el
            // trazo se mete un cuarto de trazo hacia adentro, para que el cap redondo del riel no se
            // lo coma por la derecha.
            val stopSize = metrics.stopIndicator.toPx()
            val stopOffset = if (stopSize >= stroke) 0f else stroke / WAVE_STOP_INSET_DIVISOR
            // Se desvanece con el riel (misma `railAlpha`): es su remate, no un elemento aparte, y
            // quedarse solo al final era justo lo que se veía mal.
            drawCircle(
                color = activeColor,
                alpha = railAlpha,
                radius = stopSize / 2f,
                center = Offset(endX - stopOffset, centerY)
            )
        }

        // Tramo reproducido: sinusoide muestreada; con amplitud 0 queda una recta perfecta.
        val amplitudePx = metrics.waveAmplitude.toPx() * amplitudeFraction
        val wavelengthPx = ComponentConfig.ProgressWaveLength.toPx()
        val phaseTurns = phase.floatValue
        // Y de la sinusoide en una x dada (x = startX ⇒ solo la fase, así el ARRANQUE también
        // ondula: anclarlo a centerY lo dejaba clavado mientras el resto se movía, además de
        // meter un pico vertical en el primer segmento).
        fun waveY(atX: Float): Float {
            val theta = ((atX - startX) / wavelengthPx + phaseTurns) * FULL_TURN_RADIANS
            return centerY + sin(theta) * amplitudePx
        }
        // La onda TERMINA un `dragGap` antes del thumb, no en su centro: así queda un hueco entre la
        // onda y el palo, SIMÉTRICO con el que el riel deja después (inactiveStartX = activeEndX +
        // dragGap). Como el trazo lleva cap redondo, su borde derecho cae en `waveEndX + radius`, o
        // sea a `gap` del borde izquierdo del palo — el mismo aire medido entre BORDES que en el modo
        // plano y en la referencia del spec. Antes la onda llegaba hasta `activeEndX` (el centro del
        // palo) y se metía por debajo de él sin hueco.
        //
        // Sin palo (el ajuste apagado, en reposo) la onda termina EN la posición: ahí su punta es lo
        // que la marca, igual que el borde del fill en el modo plano, y el riel sigue arrancando un
        // `gap` más allá porque `inactiveStartX` interpola el suyo con el mismo `handleAlpha`. Se
        // interpola en vez de conmutar por lo de siempre: el cambio ocurre con el dedo en la barra.
        val waveEndX = activeEndX - dragGap * handleAlpha
        // ARRANQUE DE LA CANCIÓN: la onda que aún no tiene largo se dibuja como el PUNTO en que
        // consiste su propia punta. No es un elemento nuevo —el trazo lleva cap redondo, así que eso
        // es EXACTAMENTE lo que se ve en cuanto el progreso mide un píxel—, y el hueco al riel ya le
        // reservaba su sitio: `restGap` = radio + gap + radio deja el aire de un punto entero antes
        // del riel. O sea que la barra apartaba sitio para algo que nunca se pintaba, y en 0:00 el
        // reproductor no enseñaba NADA a la izquierda: una barra que parece deshabilitada justo en
        // el estado en que más se la mira (canción cargada, recién abierto el reproductor).
        //
        // Lo que se colaba es que un `drawPath` de un solo `moveTo` no dibuja: Skia necesita un
        // segmento para tener dónde poner los caps. Con `waveEndX == startX` el path quedaba
        // degenerado y la rama del `if` ni siquiera entraba.
        //
        // El punto va en `waveY(startX)` y no en `centerY` porque es la MISMA punta que luego crece:
        // anclarlo al centro daría un salto vertical en el frame en que aparece el primer trazo (el
        // arranque de la onda ondula, ver [waveY]).
        //
        // **Con el palo puesto no se pinta, y eso lo decide la geometría sin ningún factor extra**:
        // a `waveEndX` se le retira un `dragGap`, así que en fracción 0 cae DETRÁS de `startX` y no
        // entra en ninguna de las dos ramas. Ahí el indicador de posición es el palo, y un punto
        // debajo asomaría por los lados — el mismo motivo por el que el fill le cede el hueco.
        val activeLength = waveEndX - startX
        when {
            activeLength >= WAVE_SAMPLE_STEP_PX -> {
                val step = WAVE_SAMPLE_STEP_PX
                wavePath.reset()
                wavePath.moveTo(startX, waveY(startX))
                var x = startX + step
                while (x < waveEndX) {
                    wavePath.lineTo(x, waveY(x))
                    x += step
                }
                // Punto final exacto: con el paso de muestreo la punta quedaría corta.
                wavePath.lineTo(waveEndX, waveY(waveEndX))
                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            // Por debajo de un paso de muestreo el trazo y el punto son el mismo dibujo (dos
            // píxeles de largo con caps redondos ya SON un círculo), así que esta rama cubre el
            // caso degenerado y el primer píxel de progreso con una sola forma y sin salto.
            activeLength >= 0f -> drawCircle(
                color = activeColor,
                radius = radius,
                center = Offset(startX, waveY(startX))
            )
        }

        // HANDLE del spec Expressive (palo vertical), permanente por defecto (como las barras de
        // progreso del spec) y solo-al-arrastrar si el usuario lo apaga en Ajustes → Apariencia.
        // Va CENTRADO en vertical con la onda pasándole por detrás, no rematando la punta.
        //
        // Va dibujado acá dentro y no como `thumb` del Slider a propósito: el thumb del Slider se
        // posiciona con el valor CRUDO —que llega a tirones, una vez por segundo— mientras la onda
        // avanza con la fracción interpolada, así que se verían desincronizados. Aquí el palo se
        // centra en `activeEndX` y la onda termina un `dragGap` antes: los dos salen del MISMO
        // `activeEndX` interpolado, así que el hueco entre ambos no baila.
        //
        // CENTRADO en vertical, NO montado en la cresta: un palo de 26dp subiendo y bajando 3dp por
        // frame se balancea, y en el slider ondulado del spec el handle está quieto y la onda le
        // pasa por detrás.
        if (handleVisible) {
            drawRoundRect(
                color = activeColor,
                alpha = handleAlpha,
                topLeft = Offset(activeEndX - drawnHandleW / 2f, centerY - handleH / 2f),
                size = Size(drawnHandleW, handleH),
                cornerRadius = CornerRadius(drawnHandleW / 2f)
            )
        }
    }
}

// --- Búfer del track (ver [ProgressSlider]) ---

/**
 * A partir de aquí el búfer se considera COMPLETO y deja de dibujarse. Un archivo en disco
 * reporta la canción entera bufferizada, y pintar siempre la barra llena de un tercer color
 * sería ruido permanente en la biblioteca descargada, que es el caso normal. No es 1.0 exacto
 * porque el player suele quedarse unos milisegundos por debajo del total declarado.
 */
private const val BUFFER_COMPLETE_FRACTION = 0.995f

// --- Constantes de la onda del track (ver [WavyTrack]) ---
/**
 * Cuánto tarda la onda en recorrer UN ciclo. Se DERIVA de la longitud de onda y de la velocidad, en
 * vez de ser una constante propia: la magnitud que se percibe es la velocidad (dp/s), y con el
 * periodo fijo cada retoque del ancho de la onda cambiaba también el ritmo, sin que se viera venir.
 */
private val WAVE_MS_PER_CYCLE: Float
    get() = ComponentConfig.ProgressWaveLength.value /
        ComponentConfig.ProgressWaveSpeed.value * MILLIS_PER_SECOND



/**
 * Periodo con el que la UI refresca la posición de reproducción. Lo usan las DOS puntas y por eso
 * es `internal`: el bucle de `MusicPlayerScreen` que llama a `updatePosition()` y la interpolación
 * del track, que dura exactamente un tick para que cada uno llegue justo cuando el anterior terminó
 * de dibujarse y el avance se vea continuo.
 *
 * **Por qué un segundo y no menos**: es la resolución de lo que la pantalla ENSEÑA — los chips de
 * tiempo van en `mm:ss`, así que preguntar más a menudo no cambiaría ni un dígito, y la barra cubre
 * el hueco interpolando entre ticks en vez de sondeando. Menos que un segundo dejaría el contador
 * retrasado respecto al audio. Cada tick es un salto al main thread, y este bucle corre todo el rato
 * que dura la reproducción con la app delante.
 *
 * Estuvo `private` con un `delay(1000L)` literal al otro lado: el KDoc afirmaba que coincidían y
 * nada lo obligaba, así que tocar uno desincronizaba el dibujo del dato en silencio.
 */
internal const val POSITION_TICK_MS = 1000

/**
 * Salto de progreso (fracción de la barra) por encima del cual NO se interpola. Un tick normal
 * avanza `1s / duración` — con la canción más corta de una biblioteca típica (~1 min) eso es
 * ~0.017, así que 0.05 deja pasar el avance natural y ataja los seeks hacia ADELANTE, que deben ser
 * instantáneos.
 *
 * **Solo gobierna los saltos hacia adelante**: un retroceso se snapea siempre, por SENTIDO y no por
 * distancia, y por eso los cambios de pista ya no dependen de este número (ver `rememberSmoothedProgress`).
 *
 * `internal` porque lo comparte el anillo del MiniPlayer, que interpola con la MISMA regla: dos
 * superficies que dibujan el mismo progreso no pueden discrepar en qué es un seek y qué es avance.
 */
internal const val PROGRESS_SNAP_THRESHOLD = 0.05f

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
 * hacían una pastilla larga. Comparte contenedor y contenido con los dos [TimeChip] que lo
 * flanquean (los resuelve [ProgressChipsRow]): son la misma fila, no tres piezas sueltas.
 *
 * SOLO TEXTO, sin icono (decisión del usuario): el formato ya se lee en la etiqueta. Que sea sin
 * pérdida ([LOSSLESS_FORMATS]) solo cambia la descripción de ACCESIBILIDAD — visualmente no se
 * distingue, y el KDoc afirmó lo contrario ("el acento más marcado") desde antes de este cambio.
 *
 * **Tocarlo conmuta la ficha técnica** (`FLAC · 16 bit · 44.1 kHz` / `MP3 · 320 kbps · 44.1 kHz`),
 * que es el mismo ajuste persistido del switch de Ajustes → Apariencia. Si no hay ningún dato que
 * añadir ([AudioFormatInfo.hasDetails] falso, p. ej. en streaming, donde no se abre el archivo) el
 * chip NO es tocable: un toggle que no cambia nada se lee como un fallo.
 */
@Composable
private fun FormatChip(
    format: AudioFormatInfo,
    detailed: Boolean,
    onToggleDetailed: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val isLossless = format.format.uppercase() in LOSSLESS_FORMATS
    val label = formatLabel(format, detailed)
    val desc = stringResource(
        if (isLossless) R.string.np_format_desc_lossless else R.string.np_format_desc,
        label
    )
    val toggleable = format.hasDetails
    AppSurface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        // Surface con onClick para tener ripple/estado táctil de M3; sin detalles que enseñar se
        // vuelve la Surface informativa de siempre.
        onClick = onToggleDetailed,
        enabled = toggleable,
        // El chip cambia de ANCHO al tocarlo ("FLAC" → "FLAC · 16 bit · 44.1 kHz") y hasta ahora
        // ese cambio era un salto seco en medio de la barra de progreso. `animateContentSize` es
        // justo para esto: mide el contenido nuevo y anima el contenedor hasta él.
        modifier = modifier
            .animateContentSize(appSpatialSpec())
            .semantics { contentDescription = desc }
    ) {
        Text(
            text = label,
            // `labelSmallEmphasized` da los dos valores que aquí se ponían a mano: peso Bold y
            // tracking 0.5sp — ese `letterSpacing` ERA ya el del token `LabelSmallTracking`.
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * Etiqueta del chip. En modo detallado se muestra lo que DISTINGUE a cada familia: en un formato
 * sin pérdida el bitrate es una consecuencia del material (y varía por canción), así que manda la
 * profundidad de bits; en uno con pérdida, el bitrate ES la calidad. Cada dato ausente se omite,
 * de modo que el chip nunca enseña un hueco ni un "null".
 */
private fun formatLabel(info: AudioFormatInfo, detailed: Boolean): String {
    if (!detailed || !info.hasDetails) return info.format
    val isLossless = info.format.uppercase() in LOSSLESS_FORMATS
    val parts = mutableListOf(info.format)
    if (isLossless) {
        info.bitsPerSample?.let { parts.add("$it bit") }
    } else {
        info.bitrateKbps?.let { parts.add("$it kbps") }
    }
    info.sampleRateHz?.let { parts.add(formatSampleRate(it)) }
    return parts.joinToString(FORMAT_SEPARATOR)
}

/** 44100 → "44.1 kHz"; 48000 → "48 kHz" (sin decimal inútil). */
private fun formatSampleRate(hz: Int): String {
    val khz = hz / HZ_PER_KHZ.toDouble()
    val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)
    return "$text kHz"
}

/** Separador de la ficha técnica: punto medio con espacios, como en las fichas de audio. */
private const val FORMAT_SEPARATOR = " · "

private const val HZ_PER_KHZ = 1000

/**
 * Duración EN PALABRAS ("3 minutos 45 segundos") para los lectores de pantalla. `formatTime` da
 * "3:45", que TalkBack lee como dos números sueltos y no como un tiempo. Los minutos se omiten
 * cuando no hay, para no anunciar "0 minutos 12 segundos".
 */
@Composable
private fun spokenTime(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0L).toInt()
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val secondsText = pluralStringResource(R.plurals.np_spoken_seconds, seconds, seconds)
    if (minutes == 0) return secondsText
    val minutesText = pluralStringResource(R.plurals.np_spoken_minutes, minutes, minutes)
    return "$minutesText $secondsText"
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60

/** Chip de tiempo del slider. Sus colores los resuelve [ProgressChipsRow], que los comparte. */
@Composable
private fun TimeChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    AppSurface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
