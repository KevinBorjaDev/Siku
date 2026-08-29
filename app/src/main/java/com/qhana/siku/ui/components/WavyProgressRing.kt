package com.qhana.siku.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.screens.POSITION_TICK_MS
import com.qhana.siku.ui.screens.PROGRESS_SNAP_THRESHOLD
import com.qhana.siku.ui.theme.appEffectsSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Anillo de progreso ONDULADO del MiniPlayer: el arco activo ondula mientras suena y el resto es un
 * arco liso, con un hueco a cada lado. Réplica de `CircularWavyProgressIndicator` (mismos tokens de
 * M3: [RING_WAVELENGTH], [RING_AMPLITUDE], [RING_GAP]) con **una diferencia deliberada: aquí se
 * decide CUÁNDO se repinta**.
 *
 * **Por qué no el componente de M3** (19 ago 2026; misma excepción a "componentes M3 reales" que
 * `WavyTrack` y la barra de progreso sin `Slider`, y por el mismo motivo: el oficial no expone lo
 * que hace falta). Sin acceso a su fase renderiza a la tasa de la pantalla mientras haya música, de
 * forma indefinida, y esa producción continua es justo lo que impide que la cola de SurfaceFlinger
 * drene tras un frame tardío: el **buffer stuffing** no se corrige mientras la app entregue un
 * buffer en cada vsync. Medido con Perfetto sobre release: sonando, 478 de 1336 frames con stuffing
 * (**36 %**); en pausa —el anillo quieto—, 101 de 976 (**10 %**), y en esa traza aparecen NUEVE
 * segundos enteros sin un solo dibujo, que es cuando la cola se vacía.
 *
 * **La regla que lo arregla ya existía en esta app**: publicar el avance solo cuando la onda se ha
 * desplazado un píxel entero (la misma de la cookie del play y de la onda del NowPlaying). No hay
 * ninguna cadencia elegida a mano ni ningún supuesto sobre la pantalla: el avance sale del TIEMPO
 * real entre frames, así que a 60 Hz publica casi siempre, a 120 una de cada tres y a 144 una de
 * cada cuatro. El efecto se ve idéntico en todas —viaja una longitud de onda por segundo— y en las
 * pantallas rápidas, que son las que sufren el stuffing, quedan vsyncs libres para que la cola
 * respire.
 *
 * El reloj **ya no es `withFrameNanos`** sino [pixelPacedClock] (20 ago 2026). Aquí se dijo que
 * `withFrameNanos` "no produce frames por sí solo" y que la prueba era una traza en pausa con
 * *"120 `doFrame` por segundo con cero dibujos"* — pero eso NO era la prueba de que estuviera bien,
 * era el problema: 120 despertares por segundo cuestan 45 ms de cada segundo en el hilo principal
 * (medido) aunque no se dibuje, porque cada uno pide el siguiente vsync por binder. Con el reloj
 * pausado por píxel se despierta unas 45 veces por segundo y todas tienen algo que enseñar.
 * Fase, amplitud y progreso se leen DENTRO del bloque de dibujo, así que invalidan el draw y nunca
 * la composición.
 *
 * @param progress lambda y no valor: el tick de posición repinta sin recomponer.
 * @param playing con `false` la onda se aplana a un aro liso. El reloj lo gobierna [animated].
 */
@Composable
internal fun WavyProgressRing(
    progress: () -> Float,
    playing: Boolean,
    color: Color,
    trackColor: Color,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
    /**
     * Si la onda VIAJA. Separado de [playing] porque son dos cosas distintas que hasta el 28 ago
     * 2026 iban juntas: [playing] decide si la onda tiene RELIEVE (en pausa se aplana a un aro
     * liso) y esto decide si además se desplaza, que es lo que cuesta un reloj por frame.
     *
     * El caso que obligó a separarlas es la racha de estadísticas: quiere el relieve —es lo que la
     * distingue de un aro cualquiera— pero es un dato QUIETO, así que animarla sería un productor
     * continuo de frames en una pantalla que no lo necesita (regla 1 de Motion).
     */
    animated: Boolean = playing,
) {
    val density = LocalDensity.current
    val wavelengthPx = with(density) { RING_WAVELENGTH.toPx() }
    val amplitudePx = with(density) { RING_AMPLITUDE.toPx() }
    val gapPx = with(density) { RING_GAP.toPx() }
    val strokePx = with(density) { strokeWidth.toPx() }

    // Desplazamiento de la onda, en píxeles. Estado plano y no `Animatable`: lo escribe el reloj de
    // abajo y lo lee únicamente el `Canvas`.
    val travelPx = remember { mutableFloatStateOf(0f) }
    /**
     * Progreso INTERPOLADO entre ticks de posición, que es lo que dibuja el anillo mientras suena.
     *
     * La posición real llega **una vez por segundo** (el bucle de `MusicPlayerScreen`, lento a
     * propósito para no despertar el main thread en cada frame), así que el arco avanzaba a saltos.
     * Los dos tracks del NowPlaying ya lo suavizaban con `rememberSmoothedProgress` y el anillo era
     * la ÚNICA superficie de progreso de la app que seguía leyendo el valor crudo — no se notaba
     * porque, con el hueco mal medido, la punta del arco tocaba el riel y no había ningún borde
     * suelto al que mirarle el salto. Al abrirse el hueco de verdad (27 ago 2026) el escalón quedó a
     * la vista, y encima multiplicado: desde ese mismo cambio el HUECO también sale del progreso
     * (`gapArcPx`), así que en los primeros segundos de cada canción saltaban a la vez la punta, el
     * arranque del riel y su cola.
     *
     * **No se usa `rememberSmoothedProgress`**: aquél devuelve un `Float` leído en COMPOSICIÓN, o
     * sea que recompone a quien lo llama ~45 veces por segundo. El mini es una superficie
     * persistente montada sobre la biblioteca y su regla es justamente que el progreso se lea
     * DENTRO del bloque de dibujo (ver el KDoc de arriba), así que aquí se publica en un estado
     * plano que solo mira el `Canvas`.
     */
    val shownProgress = remember { mutableFloatStateOf(0f) }
    // La lambda puede cambiar de identidad en cada recomposición y el reloj de abajo vive mientras
    // dure la reproducción: sin esto se quedaría con la PRIMERA (misma trampa que la convención 16).
    val currentProgress by rememberUpdatedState(progress)
    // La amplitud sí se anima (el aplanado al pausar), con un spec de EFFECTS: un spring con rebote
    // se pasaría de 0 y la onda se invertiría al asentar.
    val amplitudeFactor = remember { Animatable(if (playing) 1f else 0f) }

    // El spec se resuelve AQUÍ y no dentro del efecto: `appEffectsSpec` es `@Composable` (lee el
    // `MotionScheme` del tema) y un `LaunchedEffect` ya no está en contexto de composición.
    val flattenSpec = appEffectsSpec<Float>()
    LaunchedEffect(playing) {
        amplitudeFactor.animateTo(if (playing) 1f else 0f, animationSpec = flattenSpec)
    }

    LaunchedEffect(animated, wavelengthPx) {
        if (!animated) {
            // Reencuadre a cero: en pausa la onda está plana, así que el salto no se ve.
            travelPx.floatValue = 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var travel = travelPx.floatValue
        var published = travel
        // Rampa del progreso: al llegar un tick nuevo se recorre lo que separa el valor dibujado del
        // recién llegado, y se tarda EXACTAMENTE un tick, para que cada uno empiece donde el
        // anterior terminó y el avance se vea continuo. Es la misma regla —y la misma constante— que
        // `rememberSmoothedProgress`.
        var rampFrom = currentProgress()
        var rampTarget = rampFrom
        // 0 = no hay rampa en curso. `System.nanoTime()` no vale 0, así que sirve de centinela
        // (mismo idiom que `lastNanos`).
        var rampStartNanos = 0L
        // Sembrado al ENTRAR: el efecto se relanza al empezar a sonar y al cambiar de canción, y sin
        // esto el anillo enseñaría un 0 hasta el primer tick.
        shownProgress.floatValue = rampFrom
        // La onda recorre una longitud de onda por segundo, así que tarda `1 / wavelengthPx`
        // segundos en moverse un píxel: ése es el ritmo al que hay algo que enseñar y, desde el
        // 20 ago 2026, también el ritmo al que se DESPIERTA (ver [pixelPacedClock]). Antes el bucle
        // iba con `withFrameNanos`, o sea 120 despertares por segundo para publicar unos 45.
        pixelPacedClock(stepMillisFor(wavelengthPx)) { now ->
            // Por TIEMPO transcurrido, nunca por número de ticks: `delay` garantiza un mínimo, no
            // una cadencia, así que un tick tardío no puede frenar la onda.
            if (lastNanos != 0L) {
                travel += wavelengthPx * ((now - lastNanos) / NANOS_PER_SECOND)
            }
            lastNanos = now
            // LA regla se queda como RED: el intervalo ya está calculado para que aquí siempre haya
            // un píxel, pero si el sistema entrega un tick antes de tiempo, no se publica de más.
            if (travel - published >= 1f) {
                published = travel
                travelPx.floatValue = travel
            }

            // El progreso se interpola en ESTE MISMO reloj y no en uno propio, y eso es lo que hace
            // que suavizarlo salga gratis: el bucle ya despierta ~45 veces por segundo para mover la
            // onda y el `Canvas` ya se redibuja a ese ritmo, así que publicar aquí no añade ni un
            // despertar ni un frame. Un segundo reloj sí los añadiría, y la regla de Motion.kt sobre
            // productores continuos en superficies persistentes lo prohíbe.
            val target = currentProgress()
            if (target != rampTarget) {
                val from = shownProgress.floatValue
                rampTarget = target
                // Lo que NO se interpola: un RETROCESO (siempre es un seek, un cambio de pista o un
                // `REPEAT_ONE`) y un salto grande hacia adelante. Criterio y umbral son los de la
                // barra del NowPlaying, importados y no recopiados: dos superficies que dibujan el
                // mismo progreso no pueden discrepar en qué es un seek.
                if (target < from || abs(target - from) > PROGRESS_SNAP_THRESHOLD) {
                    rampFrom = target
                    rampStartNanos = 0L
                    shownProgress.floatValue = target
                } else {
                    rampFrom = from
                    rampStartNanos = now
                }
            }
            if (rampStartNanos != 0L) {
                val t = ((now - rampStartNanos) / (POSITION_TICK_MS * NANOS_PER_MILLI))
                    .coerceIn(0f, 1f)
                shownProgress.floatValue = rampFrom + (rampTarget - rampFrom) * t
                if (t >= 1f) rampStartNanos = 0L
            }
        }
    }

    Canvas(modifier) {
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        // El radio deja sitio al trazo Y a la cresta de la onda, o el anillo se saldría de su caja.
        val radius = (size.minDimension - strokePx) / 2f - amplitudePx
        if (radius <= 0f) return@Canvas
        // Sonando se dibuja el valor INTERPOLADO; en pausa, el crudo — no hay avance que suavizar y
        // el reloj está parado, así que un seek con la música detenida tiene que llegar igual.
        // El valor INTERPOLADO solo existe si el reloj corre; sin él se dibuja el crudo, que es
        // exactamente lo que quiere un progreso quieto.
        val fraction = (if (animated) shownProgress.floatValue else progress()).coerceIn(0f, 1f)
        val amp = amplitudePx * amplitudeFactor.value
        val sweep = fraction * FULL_TURN_DEGREES
        // Longitud del arco ya reproducido. Solo se usa para encoger el hueco al principio de la
        // canción (ver [gapArcPx]); el dibujo de la onda mide lo suyo aparte, en [wavePath].
        val progressArcPx = radius * sweep / DEGREES_PER_RADIAN
        // Separación entre los EXTREMOS de los dos arcos, medida sobre el arco. El hueco VISIBLE
        // que se busca es [RING_GAP], pero los dos trazos llevan cap REDONDO y `drawArc`/`drawPath`
        // lo dibujan CENTRADO en el extremo, así que cada punta se come medio trazo del hueco:
        // separando los extremos solo [RING_GAP] el aire real era `gap − trazo`, y con los 4 dp de
        // hueco y los 4 de `MiniPlayerRingStroke` eso daba **cero** — las dos puntas se tocaban y el
        // anillo se leía como un aro continuo de dos colores. Es el MISMO error que ya se corrigió
        // en la barra del NowPlaying (`WavyTrack`: `restGap = radius + gap + radius`) y la regla es
        // la de las dos implementaciones de M3, que la escriben igual:
        //
        //   CircularWavyProgressModifiers.kt:1206  trackSpacing = horizontalInsets * 2 + trackGapSize
        //   LinearWavyProgressModifiers.kt:1112    adaptiveTrackSpacing = adjustedTrackGapSize + currentStrokeCapWidth * 2
        //
        // ...con `currentStrokeCapWidth = max(stroke.width / 2, trackStroke.width / 2)`, o sea medio
        // trazo por punta. Aquí los dos arcos comparten trazo, así que son `strokePx` en total.
        //
        // **Los dos `min` no son una guarda defensiva, son la otra mitad de la regla de M3**
        // (`min(pStop, …)` en las dos líneas de arriba): al empezar la canción el arco activo mide
        // menos que el hueco, y sin acotar quedaría un mordisco en el riel — 16 dp de aro vacío
        // arriba— antes de que haya nada que enseñar. Con el tope, en 0:00 el hueco es 0 y el riel
        // es un círculo entero, que es lo correcto: no hay dos arcos que separar.
        val gapArcPx =
            min(progressArcPx, gapPx) + 2f * min(progressArcPx, strokePx / 2f)
        // El hueco es una distancia VISIBLE sobre el arco, no un ángulo: en un anillo más chico
        // ocupa más grados, que es justo lo que lo mantiene igual de ancho a la vista.
        val gapDegrees = (gapArcPx / radius) * DEGREES_PER_RADIAN

        // TRACK: lo que queda por sonar, liso. Debajo del activo.
        val trackSweep = FULL_TURN_DEGREES - sweep - gapDegrees * 2
        if (trackSweep > 0f) {
            drawArc(
                color = trackColor,
                startAngle = START_ANGLE + sweep + gapDegrees,
                sweepAngle = trackSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = stroke
            )
        }

        if (sweep <= 0f) return@Canvas
        drawPath(
            path = wavePath(radius, sweep, amp, wavelengthPx, travelPx.floatValue),
            color = color,
            style = stroke
        )
    }
}

/**
 * El arco activo como onda: el radio oscila alrededor de [radius] según la distancia YA RECORRIDA
 * sobre el arco, que es lo que mantiene la longitud de onda constante (medirla en ángulo la
 * estiraría o encogería con el tamaño del anillo).
 *
 * La longitud se AJUSTA al divisor entero más cercano de la circunferencia, así la cresta que llega
 * al final empalma con la que sale del principio y el cierre no enseña un escalón al 100 %.
 */
private fun DrawScope.wavePath(
    radius: Float,
    sweepDegrees: Float,
    amplitude: Float,
    wavelengthPx: Float,
    travelPx: Float
): Path {
    val circumference = (2 * PI * radius).toFloat()
    val waves = (circumference / wavelengthPx).roundToInt().coerceAtLeast(1)
    val effectiveWavelength = circumference / waves
    val arcLength = circumference * (sweepDegrees / FULL_TURN_DEGREES)
    // Un punto cada [SAMPLE_STEP_PX] de arco: bastante para que la curva no se vea facetada y
    // acotado para no construir un Path de miles de puntos en cada repintado.
    val steps = (arcLength / SAMPLE_STEP_PX).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
    val path = Path()
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val angleRad = (START_ANGLE + sweepDegrees * t) / DEGREES_PER_RADIAN
        val travelled = arcLength * t + travelPx
        val r = radius + amplitude * sin(2 * PI * travelled / effectiveWavelength).toFloat()
        val x = center.x + cos(angleRad) * r
        val y = center.y + sin(angleRad) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/** Tokens de `CircularProgressIndicatorTokens` (M3 1.5.0-alpha24): el anillo no cambia de aspecto. */
private val RING_WAVELENGTH = 15.dp
private val RING_AMPLITUDE = 1.6.dp
private val RING_GAP = 4.dp

/** Arriba del todo, como el indicador de M3. */
private const val START_ANGLE = -90f
private const val FULL_TURN_DEGREES = 360f
private const val DEGREES_PER_RADIAN = 180f / PI.toFloat()
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val NANOS_PER_MILLI = 1_000_000f
/** Densidad del muestreo de la onda. Ver [wavePath]. */
private const val SAMPLE_STEP_PX = 2f
private const val MIN_SAMPLES = 8
private const val MAX_SAMPLES = 720
