package com.qhana.siku.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La matemática de [KeylineArrangement]. Existe porque **la propiedad que justifica el componente
 * entero se puede afirmar aquí**: que el sitio donde una tarjeta se dibuja es el sitio donde el layout
 * dice que está. En el carrusel de M3 eso es falso para todo lo que no esté en foco, y por eso una
 * punta de shared element aterriza corrida.
 *
 * Los números del teléfono del usuario: ancho útil ≈395 dp, tarjeta preferida 196 dp, separación 8.
 */
class KeylineArrangementTest {

    private val viewport = 395f
    private val preferred = 196f
    private val spacing = 8f

    private fun arrangement() = KeylineArrangement.of(viewport, preferred, spacing)!!

    @Test
    fun `sin ancho todavia no hay arreglo`() {
        assertNull(KeylineArrangement.of(viewport = 0f, preferred = preferred, spacing = spacing))
        assertNull(KeylineArrangement.of(viewport = viewport, preferred = 0f, spacing = spacing))
    }

    @Test
    fun `el pivote no se desplaza — es lo que M3 no cumple`() {
        assertEquals(0f, arrangement().pivotDrift(), 0.001f)
    }

    @Test
    fun `el reparto llena la fila exacta`() {
        val a = arrangement()
        val sizes = a.keylineSizes()
        // Sin contar las dos anclas, que caen fuera a propósito.
        val visible = sizes.copyOfRange(1, sizes.size - 1)
        val total = visible.sum() + spacing * (visible.size - 1)
        assertEquals(viewport, total, 0.01f)
        assertEquals(a.anchor, sizes.first(), 0.01f)
    }

    @Test
    fun `reproduce las proporciones del multi-browse de M3`() {
        val a = arrangement()
        val sizes = a.keylineSizes()
        val medium = sizes[sizes.size - 3]
        val small = sizes[sizes.size - 2]
        // Medido sobre una captura del inicio del usuario: 452 : 237 : 92 px.
        assertEquals(0.52f, medium / a.large, 0.01f)
        assertEquals(0.20f, small / a.large, 0.01f)
    }

    @Test
    fun `la fila enseña TRES tarjetas, no dos`() {
        val sizes = arrangement().keylineSizes()
        // Sin las dos anclas: grande(s) + mediana + pequeña.
        assertEquals(3, sizes.size - 2)
    }

    @Test
    fun `una tarjeta en foco mide lo grande y no se mueve`() {
        val a = arrangement()
        // El pivote está en `large / 2`: es donde el pager deja la página tras el snap.
        val geometry = a.geometryAt(a.large / 2f)
        assertEquals(a.large, geometry.size, 0.01f)
        assertEquals(a.large / 2f, geometry.center, 0.01f)
    }

    @Test
    fun `la siguiente esta apretada y TIRADA hacia la izquierda`() {
        val a = arrangement()
        val unadjusted = a.large / 2f + a.step
        val geometry = a.geometryAt(unadjusted)

        assertTrue("deberia estar apretada", geometry.size < a.large)
        // Justo el desfase que en M3 se aplica con una capa gráfica y el lookahead no ve.
        assertTrue("deberia tirar hacia la izquierda", geometry.center < unadjusted)
    }

    @Test
    fun `encoge de forma continua al alejarse del foco`() {
        val a = arrangement()
        var previous = Float.MAX_VALUE
        var x = a.large / 2f
        while (x < a.large / 2f + 3f * a.step) {
            val size = a.geometryAt(x).size
            assertTrue("el tamaño no puede crecer al alejarse", size <= previous + 0.01f)
            previous = size
            x += a.step / 16f
        }
    }

    @Test
    fun `fuera de rango se queda en el ancla, sin desbordar`() {
        val a = arrangement()
        val sizes = a.keylineSizes()
        val farLeft = a.geometryAt(-100f * a.step)
        val farRight = a.geometryAt(100f * a.step)
        assertEquals(sizes.first(), farLeft.size, 0.01f)
        assertEquals(sizes.last(), farRight.size, 0.01f)
    }

    @Test
    fun `en una fila ancha caben mas grandes y sigue llenando exacto`() {
        val a = KeylineArrangement.of(1200f, preferred, spacing)!!
        val sizes = a.keylineSizes()
        val visible = sizes.copyOfRange(1, sizes.size - 1)
        assertTrue("deberia haber mas de una grande", visible.size > 3)
        assertEquals(1200f, visible.sum() + spacing * (visible.size - 1), 0.01f)
        assertEquals(0f, a.pivotDrift(), 0.001f)
    }

    @Test
    fun `ItemGeometry sobrevive el empaquetado`() {
        val geometry = ItemGeometry.of(size = 123.456f, center = -78.9f)
        assertEquals(123.456f, geometry.size, 0.0001f)
        assertEquals(-78.9f, geometry.center, 0.0001f)
    }
}
