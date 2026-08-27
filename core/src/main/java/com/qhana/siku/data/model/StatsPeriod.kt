package com.qhana.siku.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Los tres periodos de la pantalla de estadísticas.
 *
 * [MONTH] y [YEAR] son de CALENDARIO y navegables (no ventanas rodantes como las del inicio):
 * "agosto de 2026" es un resumen cerrado que se puede revisitar, y eso es lo que hace que se lea
 * como un resumen y no como un contador. [ALL] no tiene ancla.
 */
enum class StatsPeriodMode { MONTH, YEAR, ALL }

/**
 * Periodo seleccionado: modo + el mes al que está anclado. Produce un rango `[since, until)` en
 * epoch ms, alineado a la medianoche LOCAL — el día es el del que mira, no el de UTC (mismo
 * criterio que los chips de género del día, que descartan `currentTimeMillis / 86_400_000` porque
 * cambiaría a media tarde en Perú).
 *
 * En [StatsPeriodMode.ALL] el rango no se usa: ese periodo no sale del log de eventos sino de los
 * agregados de `songs`, que son los únicos que llevan el pasado anterior a la v28.
 */
data class StatsSelection(
    val mode: StatsPeriodMode,
    val anchor: YearMonth
) {
    /** Inicio del periodo, inclusive. */
    fun sinceMillis(zone: ZoneId): Long = startDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Fin del periodo, EXCLUSIVO: el primer instante del periodo siguiente. */
    fun untilMillis(zone: ZoneId): Long = endDateExclusive().atStartOfDay(zone).toInstant().toEpochMilli()

    /** El periodo anterior, o `null` si ya no queda historial detrás de [floor]. */
    fun previous(floor: YearMonth): StatsSelection? = shifted(-1)?.takeIf { it.containsOrIsAfter(floor) }

    /** El periodo siguiente, o `null` si se saldría del presente marcado por [ceiling]. */
    fun next(ceiling: YearMonth): StatsSelection? = shifted(1)?.takeIf { it.containsOrIsBefore(ceiling) }

    private fun shifted(steps: Long): StatsSelection? = when (mode) {
        StatsPeriodMode.MONTH -> copy(anchor = anchor.plusMonths(steps))
        StatsPeriodMode.YEAR -> copy(anchor = anchor.plusYears(steps))
        // ALL no se navega: no hay un "siempre anterior".
        StatsPeriodMode.ALL -> null
    }

    private fun containsOrIsAfter(month: YearMonth): Boolean = !endMonth().isBefore(month)

    private fun containsOrIsBefore(month: YearMonth): Boolean = !startMonth().isAfter(month)

    private fun startMonth(): YearMonth = when (mode) {
        StatsPeriodMode.YEAR -> YearMonth.of(anchor.year, 1)
        else -> anchor
    }

    private fun endMonth(): YearMonth = when (mode) {
        StatsPeriodMode.YEAR -> YearMonth.of(anchor.year, 12)
        else -> anchor
    }

    private fun startDate(): LocalDate = startMonth().atDay(1)

    private fun endDateExclusive(): LocalDate = endMonth().plusMonths(1).atDay(1)

    companion object {
        /** El periodo con el que abre la pantalla: el mes en curso. */
        fun current(zone: ZoneId, now: Instant): StatsSelection =
            StatsSelection(StatsPeriodMode.MONTH, YearMonth.from(now.atZone(zone)))
    }
}
