package com.qhana.siku.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * La racha de días seguidos.
 *
 * Existe porque es aritmética de calendario pura: se escribe una vez, nadie vuelve a mirarla, y su
 * modo de fallo es que el número se ve razonable pero está mal. En SQL no se puede comprobar (ni
 * escribir: sin window functions por debajo de SQLite 3.25), así que aquí es donde se verifica.
 */
class ListeningStreakTest {

    private val today = LocalDate.of(2026, 8, 26)

    @Test
    fun `racha viva terminando hoy`() {
        val days = listOf("2026-08-26", "2026-08-25", "2026-08-24")
        assertEquals(3, computeStreak(days, today))
    }

    @Test
    fun `racha viva terminando ayer`() {
        // Ayer cuenta a propósito: si solo valiera hoy, toda racha se rompería a las 00:00 antes de
        // que dé tiempo a escuchar nada.
        val days = listOf("2026-08-25", "2026-08-24")
        assertEquals(2, computeStreak(days, today))
    }

    @Test
    fun `racha rota si el ultimo dia es anteayer`() {
        val days = listOf("2026-08-24", "2026-08-23", "2026-08-22")
        assertEquals(0, computeStreak(days, today))
    }

    @Test
    fun `un hueco corta la cuenta`() {
        val days = listOf("2026-08-26", "2026-08-25", "2026-08-23", "2026-08-22")
        assertEquals(2, computeStreak(days, today))
    }

    @Test
    fun `dias repetidos no inflan la racha`() {
        val days = listOf("2026-08-26", "2026-08-26", "2026-08-25")
        assertEquals(2, computeStreak(days, today))
    }

    @Test
    fun `desorden en la entrada no cambia el resultado`() {
        // Quien produce la lista es una consulta que puede cambiar de ORDER BY sin que nadie
        // recuerde esta dependencia.
        val days = listOf("2026-08-24", "2026-08-26", "2026-08-25")
        assertEquals(3, computeStreak(days, today))
    }

    @Test
    fun `un solo dia es una racha de uno`() {
        assertEquals(1, computeStreak(listOf("2026-08-26"), today))
    }

    @Test
    fun `sin dias no hay racha`() {
        assertEquals(0, computeStreak(emptyList(), today))
    }

    @Test
    fun `un dia en el futuro no cuenta como racha`() {
        // Reloj movido hacia atrás por el usuario: no se puede afirmar nada.
        assertEquals(0, computeStreak(listOf("2026-08-30"), today))
    }

    @Test
    fun `una fecha ilegible se descarta sin tumbar el calculo`() {
        val days = listOf("2026-08-26", "no-es-una-fecha", "2026-08-25")
        assertEquals(2, computeStreak(days, today))
    }

    @Test
    fun `la racha cruza el cambio de mes`() {
        val firstOfMonth = LocalDate.of(2026, 9, 1)
        val days = listOf("2026-09-01", "2026-08-31", "2026-08-30")
        assertEquals(3, computeStreak(days, firstOfMonth))
    }
}
