package com.qhana.siku.ui.viewmodel

import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * El histograma de actividad.
 *
 * Lo que estos tests protegen de verdad es **el pacto entre el patrón de `strftime` y el de Kotlin**
 * ([ActivityBucketing]): la consulta agrupa con el primero y las claves esperadas se generan con el
 * segundo, así que si dejan de coincidir el histograma no falla — se dibuja entero a cero. Un fallo
 * silencioso no lo caza nadie mirando la pantalla, porque un mes sin escuchas también se ve así.
 */
class ActivityChartTest {

    private val spanish = Locale("es", "PE")

    @Test
    fun `dia y siempre no tienen histograma`() {
        // "Siempre" no puede (sus totales no llevan fecha) y "Día" no debe (la forma de una jornada
        // no dice nada y son 21 casillas vacías de 24). Ver `ActivityBucketing.forMode`.
        assertNull(ActivityBucketing.forMode(StatsPeriodMode.DAY))
        assertNull(ActivityBucketing.forMode(StatsPeriodMode.ALL))
    }

    @Test
    fun `los tres periodos que si lo tienen`() {
        assertEquals(ActivityBucketing.DAY, ActivityBucketing.forMode(StatsPeriodMode.WEEK))
        assertEquals(ActivityBucketing.DAY, ActivityBucketing.forMode(StatsPeriodMode.MONTH))
        assertEquals(ActivityBucketing.MONTH, ActivityBucketing.forMode(StatsPeriodMode.YEAR))
    }

    @Test
    fun `un mes trae sus dias reales, no 30 fijos`() {
        // Febrero de 2028 es bisiesto: 29 días. Un contador fijo se equivocaría aquí y en cada mes
        // de 31.
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2028, 2, 15)),
            ActivityBucketing.DAY,
            mapOf("2028-02-29" to 7),
            spanish
        )
        assertEquals(29, chart.columns.size)
        assertEquals(7, chart.columns.last().plays)
    }

    @Test
    fun `las claves de un mes son las que produce strftime`() {
        val selection = StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2026, 7, 9))
        // Con día de un solo dígito, que es donde un formato a mano se olvida del cero delante.
        val chart = buildActivityChart(
            selection,
            ActivityBucketing.DAY,
            mapOf("2026-07-01" to 3, "2026-07-09" to 5),
            spanish
        )
        assertEquals(31, chart.columns.size)
        assertEquals(3, chart.columns[0].plays)
        assertEquals(5, chart.columns[8].plays)
        assertEquals("2026-07-09", ActivityBucketing.DAY.keyOf(LocalDate.of(2026, 7, 9)))
    }

    @Test
    fun `el eje de un mes se etiqueta cada siete dias, empezando por el 1`() {
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2026, 7, 1)),
            ActivityBucketing.DAY,
            emptyMap(),
            spanish
        )
        assertEquals(
            listOf("1", "8", "15", "22", "29"),
            chart.columns.map { it.label }.filter { it.isNotEmpty() }
        )
    }

    @Test
    fun `las etiquetas de un mes son las cinco completas, no una cifra suelta`() {
        // El bug del 30 ago 2026: con las etiquetas repartidas por PESO —el mismo que sus barras—
        // cada una heredaba ~10dp y "15" salía como "1". Aquí se comprueba el dato; que quepan es
        // trabajo de `HistogramAxis`, que las mide libres.
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2026, 8, 1)),
            ActivityBucketing.DAY,
            emptyMap(),
            spanish
        )
        val labels = chart.columns.map { it.label }.filter { it.isNotEmpty() }
        assertEquals(listOf("1", "8", "15", "22", "29"), labels)
        // Dos cifras de verdad: si alguna llegara con una sola, el recorte estaría en el dato y no
        // en el layout.
        assertTrue(labels.count { it.length == 2 } == 3)
    }

    @Test
    fun `una semana son siete dias desde el lunes y todos llevan etiqueta`() {
        // El ancla cae en jueves; la semana tiene que empezar el lunes 27.
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.WEEK, LocalDate.of(2026, 7, 30)),
            ActivityBucketing.DAY,
            mapOf("2026-07-27" to 2),
            spanish
        )
        assertEquals(7, chart.columns.size)
        assertEquals(2, chart.columns.first().plays)
        assertEquals(listOf("27", "28", "29", "30", "31", "1", "2"), chart.columns.map { it.label })
    }

    @Test
    fun `un ano son doce meses con la clave de strftime`() {
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.YEAR, LocalDate.of(2026, 5, 3)),
            ActivityBucketing.MONTH,
            mapOf("2026-01" to 12, "2026-12" to 30),
            spanish
        )
        assertEquals(12, chart.columns.size)
        assertEquals(12, chart.columns.first().plays)
        assertEquals(30, chart.columns.last().plays)
        assertEquals(30, chart.peak)
        // Todos los meses llevan etiqueta; que sean iniciales repetidas (M de marzo y de mayo) es lo
        // convencional en un eje de doce.
        assertTrue(chart.columns.all { it.label.isNotEmpty() })
    }

    @Test
    fun `un periodo sin escuchas se declara vacio`() {
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2026, 3, 1)),
            ActivityBucketing.DAY,
            emptyMap(),
            spanish
        )
        assertEquals(0, chart.peak)
        assertTrue(chart.isEmpty)
    }

    @Test
    fun `una clave que no cae en el periodo se ignora en vez de romper`() {
        // Puede pasar en el filo de un cambio de horario: la consulta agrupa en hora local y el
        // rango se calculó con la zona del sistema. Lo que no puede pasar es que el histograma
        // desaparezca por una clave sobrante.
        val chart = buildActivityChart(
            StatsSelection(StatsPeriodMode.MONTH, LocalDate.of(2026, 7, 1)),
            ActivityBucketing.DAY,
            mapOf("2026-06-30" to 99, "2026-07-15" to 4),
            spanish
        )
        assertEquals(31, chart.columns.size)
        assertEquals(4, chart.peak)
    }
}
