package com.qhana.siku.ui.components

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El arreglo de *keylines* de [KeylineCarousel]: dónde y de qué tamaño se dibuja cada tarjeta según
 * lo desplazada que esté la fila.
 *
 * Está aquí, en Kotlin puro y sin Compose, porque **es la única parte con matemática de verdad** y
 * porque la propiedad que hace que el componente exista se puede afirmar con un test en vez de con el
 * ojo: ver `KeylineArrangementTest`.
 *
 * ## Los dos sistemas de coordenadas, que es de lo que va todo esto
 *
 * - **Sin apretar**: todas las tarjetas miden [large] y van una detrás de otra. Es el espacio en el
 *   que se mueve el scroll, y por eso el pager lleva `PageSize` = [large] para TODAS las páginas.
 * - **Apretado**: lo que se ve. Las de los bordes encogen y sus bordes quedan pegados.
 *
 * Proyectar del primero al segundo es lo que hace [geometryAt]. **No hay circularidad** —que es el
 * problema que hunde el intento ingenuo de "que cada tarjeta mida lo que se ve"— porque el tamaño
 * sale de la posición SIN APRETAR, que no depende de ningún tamaño.
 *
 * ## El arreglo
 *
 * `n` tarjetas grandes + 1 mediana + 1 pequeña, llenando el ancho exacto:
 *
 * ```
 * n·L + M + S + (n+1)·g = V   ⇒   L = (V − (n+1)·g) / (n + [MEDIUM_RATIO] + [SMALL_RATIO])
 * ```
 *
 * **Las dos proporciones están MEDIDAS sobre el multi-browse de M3, no elegidas.** En una captura del
 * inicio del usuario, la fila de "Recién agregadas" reparte 452 : 237 : 92 px, o sea 1 : 0,52 : 0,20.
 * Su solver (`Arrangement.findLowestCostArrangement`) llega ahí minimizando un coste contra unos
 * tamaños objetivo, y reproducir ESO exige portar `Arrangement.kt` entero; copiar el resultado da la
 * misma fila con quince líneas de aritmética. Si algún día se ve distinto, es aquí donde se calibra.
 *
 * **La pequeña no es decorativa: es la tercera tarjeta.** Sin ella la fila enseña dos y se nota — fue
 * lo primero que el usuario vio al comparar con las filas de arriba.
 *
 * Las dos *anclas* de los extremos existen para que una tarjeta que sale de la fila no desaparezca de
 * golpe: encoge hasta [anchor] y se va por el borde.
 */
internal class KeylineArrangement private constructor(
    /** Tamaño de una tarjeta en foco. Es también el paso del scroll sin apretar (con [spacing]). */
    val large: Float,
    val spacing: Float,
    private val sizes: FloatArray,
    private val pinnedCenters: FloatArray,
    private val unadjustedCenters: FloatArray
) {
    /** Lo que avanza el scroll de una tarjeta a la siguiente, en coordenadas SIN apretar. */
    val step: Float get() = large + spacing

    /** Tamaño al que encoge una tarjeta antes de irse por el borde. El suelo de la escala. */
    val anchor: Float get() = sizes[0]

    /** Índice del keyline PIVOTE: la primera grande, la única cuyo centro no se desplaza. */
    private val pivot: Int get() = 1

    /**
     * Geometría de una tarjeta cuyo centro SIN APRETAR está en [unadjustedCenter] (px relativos al
     * borde izquierdo de la fila): qué tamaño tiene y dónde cae su centro ya apretado.
     *
     * Interpola entre los dos keylines que la rodean, igual que hace M3 al elegir su máscara. Fuera
     * del rango se queda con el keyline del extremo.
     */
    fun geometryAt(unadjustedCenter: Float): ItemGeometry {
        val last = unadjustedCenters.lastIndex
        if (unadjustedCenter <= unadjustedCenters[0]) {
            return ItemGeometry.of(sizes[0], pinnedCenters[0])
        }
        if (unadjustedCenter >= unadjustedCenters[last]) {
            return ItemGeometry.of(sizes[last], pinnedCenters[last])
        }
        var k = 0
        while (k < last && unadjustedCenters[k + 1] < unadjustedCenter) k++
        val span = unadjustedCenters[k + 1] - unadjustedCenters[k]
        val t = if (span <= 0f) 0f else (unadjustedCenter - unadjustedCenters[k]) / span
        return ItemGeometry.of(
            size = sizes[k] + (sizes[k + 1] - sizes[k]) * t,
            center = pinnedCenters[k] + (pinnedCenters[k + 1] - pinnedCenters[k]) * t
        )
    }

    /**
     * **La propiedad que justifica todo el componente**, expuesta para que el test la afirme: en el
     * pivote, el centro apretado y el sin apretar coinciden. Es exactamente lo que NO cumple el
     * carrusel de M3 para nada que no esté en foco, y de ahí que sus bounds de layout no sean los
     * dibujados. Aquí no hace falta que se cumpla para todos: se cumple **por construcción para
     * todos**, porque la posición apretada se aplica COLOCANDO, no con una capa gráfica.
     */
    internal fun pivotDrift(): Float = pinnedCenters[pivot] - unadjustedCenters[pivot]

    /** Solo para el test: el reparto crudo. */
    internal fun keylineSizes(): FloatArray = sizes.copyOf()

    companion object {
        /** Mediana respecto de la grande. MEDIDO sobre M3 — ver el bloque del encabezado. */
        private const val MEDIUM_RATIO = 0.52f

        /** Pequeña respecto de la grande. También medido. El ancla usa este mismo tamaño. */
        private const val SMALL_RATIO = 0.20f

        /**
         * Construye el arreglo para un ancho de fila, o `null` si todavía no hay ancho (primer pase
         * de medida) o los parámetros no dan para nada.
         *
         * [preferred] es una PREFERENCIA, no una medida: [large] sale de repartir el ancho exacto,
         * igual que en M3. Pedir 196 dp en un teléfono da ~220.
         */
        fun of(viewport: Float, preferred: Float, spacing: Float): KeylineArrangement? {
            if (viewport <= 0f || preferred <= 0f) return null

            val n = max(1, ((viewport + spacing) / (preferred + spacing)).roundToInt() - 1)
            val large = ((viewport - (n + 1) * spacing) / (n + MEDIUM_RATIO + SMALL_RATIO))
                .coerceIn(1f, viewport)
            val medium = large * MEDIUM_RATIO
            val small = large * SMALL_RATIO

            // Keylines de izquierda a derecha: ancla, n grandes, mediana, pequeña, ancla. Las anclas
            // caen FUERA de la fila a propósito — son el sitio al que encoge lo que se va.
            val count = n + 4
            val sizes = FloatArray(count)
            val pinned = FloatArray(count)

            sizes[0] = small
            for (j in 0 until n) sizes[1 + j] = large
            sizes[n + 1] = medium
            sizes[n + 2] = small
            sizes[n + 3] = small

            // Bordes acumulados, empezando por el ancla izquierda para que la primera GRANDE
            // arranque justo en 0 (que es donde el pager deja la página en la que ha hecho snap).
            var x = -(small + spacing)
            for (k in 0 until count) {
                pinned[k] = x + sizes[k] / 2f
                x += sizes[k] + spacing
            }

            // Sin apretar todo avanza [step], y el pivote ancla los dos sistemas: su centro es el
            // mismo en ambos. De ahí sale que la deriva del pivote sea cero.
            val step = large + spacing
            val unadjusted = FloatArray(count) { k -> pinned[1] + (k - 1) * step }

            return KeylineArrangement(large, spacing, sizes, pinned, unadjusted)
        }
    }
}

/**
 * Tamaño y centro ya apretados de una tarjeta. **Value class con los dos `Float` empaquetados en un
 * `Long`**: esto se calcula en la fase de LAYOUT, una vez por tarjeta visible y por frame de scroll,
 * y un `data class` ahí serían asignaciones en el camino caliente por nada.
 */
@JvmInline
internal value class ItemGeometry private constructor(private val packed: Long) {
    val size: Float get() = Float.fromBits((packed ushr Int.SIZE_BITS).toInt())
    val center: Float get() = Float.fromBits(packed.toInt())

    companion object {
        fun of(size: Float, center: Float): ItemGeometry = ItemGeometry(
            (size.toRawBits().toLong() shl Int.SIZE_BITS) or
                (center.toRawBits().toLong() and 0xFFFF_FFFFL)
        )
    }
}
