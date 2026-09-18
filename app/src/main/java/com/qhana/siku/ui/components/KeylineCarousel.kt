package com.qhana.siku.ui.components

import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Carrusel con el apretón de keylines de M3 Expressive, **pero con geometría honesta**: los bounds de
 * LAYOUT de cada tarjeta son los que se ven.
 *
 * ## Por qué existe (y por qué no se usa el de M3)
 *
 * `HorizontalMultiBrowseCarousel` finge su geometría **dos veces, en dos fases distintas**:
 *
 *  1. **Al medir**: su `PageSize` devuelve `strategy.itemMainAxisSize` para TODAS las páginas, o sea
 *     que todas miden lo mismo que la que está en foco.
 *  2. **Al dibujar**: el apretón es un recorte — `CarouselItemScope.maskClip` es un
 *     `clip(GenericShape)` contra `carouselItemDrawInfo.maskRect`.
 *  3. **Al colocar**: `placeWithLayer(layerBlock = { translationX = … })` corre la tarjeta para pegar
 *     los bordes enmascarados entre sí. Es una propiedad de capa gráfica.
 *
 * Un shared element toma sus bounds destino del pase de **lookahead**
 * (`SharedElement.onLookaheadPlaced` → `localLookaheadPositionOf`), que no ve ni el recorte ni el
 * transform de capa. Consecuencia: **una punta dentro de un `Carousel` de M3 vuela hacia un rectángulo
 * que no está en pantalla** — aterriza a tamaño completo y corrida por la traslación de pinning. Solo
 * el ítem en foco se libra, porque es el pivote y ahí las dos proyecciones son la identidad.
 *
 * Esto hace lo mismo con **una sola proyección, y en la fase de LAYOUT**: mide cada tarjeta a su ancho
 * visible y la COLOCA en su sitio apretado. Sin máscara y sin capa, así que el lookahead lo ve todo y
 * `entityImageSharedBounds` funciona sin nada alrededor.
 *
 * ## Cómo evita la circularidad
 *
 * El truco es de M3 y se lo copiamos entero: **el scroll vive en coordenadas SIN APRETAR**. El pager
 * lleva todas sus páginas a [KeylineArrangement.large], así que la posición sin apretar de una tarjeta
 * se calcula sola, y de ahí sale su tamaño. Si el tamaño saliera de la posición ya apretada —que es lo
 * que pasa si uno intenta esto con un `LazyRow`— la cuenta se muerde la cola.
 *
 * ## El coste, que hay que medir y no suponer
 *
 * M3 finge la geometría **a propósito**: enmascarar al dibujar le ahorra volver a medir la tarjeta en
 * cada frame de scroll. Aquí se mide de verdad, y eso es re-medir las ~4 tarjetas visibles por frame
 * mientras la fila se arrastra. En una tarjeta de imagen + dos textos debería ser barato, pero
 * **"debería" no es una medición**: si el scroll del inicio se nota, mirar con Perfetto antes de tocar
 * nada. La salida conocida, si hiciera falta, es medir el contenido a [KeylineArrangement.large] fijo
 * y recortarlo a la ventana — pero eso devuelve el problema de los bounds a lo que haya DENTRO de la
 * tarjeta, así que solo vale si la punta se sube a la ventana.
 *
 * Ver [[project_sep08_carrusel_m3_no_hospeda_puntas]] en la memoria para la crónica.
 *
 * @param state el mismo `PagerState` de siempre; el paso de scroll es una tarjeta en foco.
 * @param preferredItemWidth PREFERENCIA de ancho para la tarjeta en foco. El ancho real sale de
 *   repartir la fila exacta, igual que en M3: pedir 196 dp en un teléfono da ~232.
 * @param flingBehavior por defecto [keylineFlingBehavior], que deja recorrer la fila de un gesto.
 * @param content contenido de la tarjeta. Recibe su índice y un [KeylineItemScope] con
 *   [KeylineItemScope.revealFraction], para lo que deba atenuarse en las tarjetas apretadas.
 */
@Composable
fun KeylineCarousel(
    state: PagerState,
    preferredItemWidth: Dp,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = 8.dp,
    userScrollEnabled: Boolean = true,
    flingBehavior: TargetedFlingBehavior = keylineFlingBehavior(state),
    content: @Composable KeylineItemScope.(index: Int) -> Unit
) {
    val pageSize = remember(preferredItemWidth) { KeylinePageSize(preferredItemWidth) }
    HorizontalPager(
        state = state,
        pageSize = pageSize,
        pageSpacing = itemSpacing,
        // Una casilla de margen a cada lado: la tarjeta del borde está APRETADA y su casilla sin
        // apretar puede haber salido de la vista antes que ella. Sin esto, asoma de golpe.
        beyondViewportPageCount = 1,
        userScrollEnabled = userScrollEnabled,
        flingBehavior = flingBehavior,
        modifier = modifier
    ) { page ->
        // El scope se lee en fase de DIBUJO, nunca en composición: el equivalente de por qué
        // `carouselItemDrawInfo` avisa de lo mismo en su KDoc.
        val scope = remember(pageSize, state, page) { KeylineItemScope(pageSize, state, page) }
        Box(modifier = Modifier.keylineWindow(pageSize, state, page)) {
            scope.content(page)
        }
    }
}

/**
 * Lanzamiento SIN tope de distancia: un gesto rápido recorre la fila entera y luego encaja en la
 * tarjeta a la que llegue por velocidad.
 *
 * **Es una elección, y M3 publica las dos.** El default de `HorizontalMultiBrowseCarousel` es
 * `CarouselDefaults.singleAdvanceFlingBehavior` —una tarjeta por gesto— y al lado está
 * `multiBrowseFlingBehavior`, que es esto. El `HorizontalPager` de debajo trae el mismo tope por
 * defecto (`PagerSnapDistance.atMost(1)`), así que sin esto la fila se recorre de una en una: con
 * diez artistas eso son diez gestos.
 *
 * Lo ÚNICO que cambia respecto al default es el tope. La decadencia y el spec del encaje se dejan en
 * los del pager a propósito: son los que ya gobiernan el resto de los deslizamientos de la app, y
 * fabricar aquí unos propios sería inventar un motion que nadie pidió.
 */
@Composable
fun keylineFlingBehavior(state: PagerState): TargetedFlingBehavior = PagerDefaults.flingBehavior(
    state = state,
    pagerSnapDistance = remember {
        object : PagerSnapDistance {
            override fun calculateTargetPage(
                startPage: Int,
                suggestedTargetPage: Int,
                velocity: Float,
                pageSize: Int,
                pageSpacing: Int
            ): Int = suggestedTargetPage
        }
    }
)

/**
 * Lo que una tarjeta puede preguntar sobre sí misma. Todo se resuelve en fase de DIBUJO —de ahí que
 * devuelva lambdas y no valores—: leerlo en composición recompondría la tarjeta en cada frame de
 * scroll, que es justo lo que estamos intentando no pagar.
 */
@Stable
class KeylineItemScope internal constructor(
    private val pageSize: KeylinePageSize,
    private val state: PagerState,
    private val page: Int
) {
    /**
     * Cuánto está esta tarjeta "revelada": 1 en foco, 0 encogida al ancla. Es el reemplazo del
     * `labelAlpha` que en M3 se derivaba de `carouselItemDrawInfo`.
     */
    fun revealFraction(): () -> Float = {
        val arrangement = pageSize.arrangement
        if (arrangement == null) 1f else {
            val size = arrangement.geometryAt(unadjustedCenterOf(arrangement, state, page)).size
            val anchor = arrangement.anchor
            val range = arrangement.large - anchor
            if (range <= 0f) 1f else ((size - anchor) / range).coerceIn(0f, 1f)
        }
    }
}

/**
 * Reparte el ancho disponible y publica el arreglo resultante. Escribe estado durante la MEDIDA —lo
 * mismo que hace `CarouselPageSize` de M3, y por el mismo motivo: es el único sitio donde se conoce el
 * ancho de la fila.
 */
@Stable
class KeylinePageSize internal constructor(private val preferredItemWidth: Dp) : PageSize {
    internal var arrangement: KeylineArrangement? by mutableStateOf(null)
        private set

    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int {
        val next = KeylineArrangement.of(
            viewport = availableSpace.toFloat(),
            preferred = preferredItemWidth.toPx(),
            spacing = pageSpacing.toFloat()
        )
        arrangement = next
        // La PÁGINA mide lo que la tarjeta en foco: es lo que pone el scroll en coordenadas sin
        // apretar, que es de lo que depende todo lo demás.
        return next?.large?.roundToInt() ?: availableSpace
    }
}

/**
 * La proyección, y el corazón del componente: mide la tarjeta a su ancho VISIBLE y la coloca en su
 * sitio apretado, todo dentro de la fase de layout.
 *
 * El nodo ocupa la página entera (que es lo que el pager le reserva) y dentro coloca la tarjeta, que
 * puede salirse de su casilla hacia la izquierda — es exactamente lo que hace el pinning, y por eso el
 * pager no recorta página a página.
 *
 * `currentPage` y `currentPageOffsetFraction` se leen AQUÍ, en el bloque de medida: eso invalida el
 * layout en cada frame de scroll, que es lo que queremos, y no la composición, que no.
 */
private fun Modifier.keylineWindow(
    pageSize: KeylinePageSize,
    state: PagerState,
    page: Int
): Modifier = layout { measurable, constraints ->
    val arrangement = pageSize.arrangement
    if (arrangement == null || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    val height = constraints.maxHeight
    val unadjustedCenter = unadjustedCenterOf(arrangement, state, page)
    val geometry = arrangement.geometryAt(unadjustedCenter)

    val width = geometry.size.roundToInt().coerceIn(0, constraints.maxWidth)
    val placeable = measurable.measure(Constraints.fixed(width, height))

    // Diferencia entre dónde caería la tarjeta sin apretar (su casilla en el pager) y dónde va de
    // verdad. En M3 esto es el `translationX` de la capa; aquí es una posición de colocación, y esa
    // es toda la diferencia.
    val pageLeft = unadjustedCenter - arrangement.large / 2f
    val dx = (geometry.center - geometry.size / 2f - pageLeft).roundToInt()

    layout(constraints.maxWidth, height) { placeable.place(dx, 0) }
}

/**
 * Centro de la tarjeta [page] en coordenadas SIN APRETAR, relativo al borde izquierdo de la fila.
 *
 * Sale del pager: con `SnapPosition.Start` la página actual arranca en 0, así que todo lo demás son
 * pasos de [KeylineArrangement.step] a un lado y a otro. `currentPageOffsetFraction` es lo que hace
 * que el apretón sea continuo y no a saltos.
 */
private fun unadjustedCenterOf(
    arrangement: KeylineArrangement,
    state: PagerState,
    page: Int
): Float = (page - state.currentPage - state.currentPageOffsetFraction) * arrangement.step +
    arrangement.large / 2f
