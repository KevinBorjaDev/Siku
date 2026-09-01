package com.qhana.siku.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Los cinco periodos de la pantalla de estadísticas.
 *
 * [DAY], [WEEK], [MONTH] y [YEAR] son de CALENDARIO y navegables (no ventanas rodantes como las del
 * inicio): "28 de agosto" o "agosto de 2026" son resúmenes cerrados que se pueden revisitar, y eso
 * es lo que hace que se lean como un resumen y no como un contador. [ALL] no tiene ancla.
 *
 * **Día y semana llegaron el 28 ago 2026** y con ellos el ancla dejó de ser un `YearMonth`: un mes
 * no puede señalar a un día. El log de eventos ya guardaba `playedAt` con precisión de milisegundos,
 * así que el dato estaba desde la v28 — lo que faltaba era poder pedirlo.
 */
enum class StatsPeriodMode { DAY, WEEK, MONTH, YEAR, ALL }

/**
 * Periodo seleccionado: modo + el DÍA al que está anclado. Produce un rango `[since, until)` en
 * epoch ms, alineado a la medianoche LOCAL — el día es el del que mira, no el de UTC (mismo
 * criterio que los chips de género del día, que descartan `currentTimeMillis / 86_400_000` porque
 * cambiaría a media tarde en Perú).
 *
 * **El ancla es un `LocalDate` y no un `YearMonth`**, aunque para mes y año sobre la precisión: con
 * el ancla en meses no hay forma de expresar "el martes 25", y mantener dos anclas según el modo
 * sería un estado que hay que sanear en cada cambio. Cada modo normaliza lo que le sobra en
 * [startDate] / [endDateExclusive], así que dos anclas del mismo mes producen el mismo rango.
 *
 * En [StatsPeriodMode.ALL] el rango no se usa: ese periodo no sale del log de eventos sino de los
 * agregados de `songs`, que son los únicos que llevan el pasado anterior a la v28.
 */
data class StatsSelection(
    val mode: StatsPeriodMode,
    val anchor: LocalDate
) {
    /** Inicio del periodo, inclusive. */
    fun sinceMillis(zone: ZoneId): Long = startDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Fin del periodo, EXCLUSIVO: el primer instante del periodo siguiente. */
    fun untilMillis(zone: ZoneId): Long = endDateExclusive().atStartOfDay(zone).toInstant().toEpochMilli()

    /** El periodo anterior, o `null` si ya no queda historial detrás de [floor]. */
    fun previous(floor: LocalDate): StatsSelection? =
        shifted(-1)?.takeIf { !it.endDateExclusive().minusDays(1).isBefore(floor) }

    /** El periodo siguiente, o `null` si se saldría del presente marcado por [ceiling]. */
    fun next(ceiling: LocalDate): StatsSelection? =
        shifted(1)?.takeIf { !it.startDate().isAfter(ceiling) }

    private fun shifted(steps: Long): StatsSelection? = when (mode) {
        StatsPeriodMode.DAY -> copy(anchor = anchor.plusDays(steps))
        StatsPeriodMode.WEEK -> copy(anchor = anchor.plusWeeks(steps))
        StatsPeriodMode.MONTH -> copy(anchor = anchor.plusMonths(steps))
        StatsPeriodMode.YEAR -> copy(anchor = anchor.plusYears(steps))
        // ALL no se navega: no hay un "siempre anterior".
        StatsPeriodMode.ALL -> null
    }

    /**
     * Primer día del periodo.
     *
     * La semana empieza en LUNES y no en domingo: `TemporalAdjusters.previousOrSame(MONDAY)` es fijo
     * a propósito, en vez de `WeekFields.of(locale).firstDayOfWeek`. El día de inicio decide qué
     * escuchas caen en "esta semana", así que con el criterio dependiente del idioma el MISMO
     * historial daría dos resúmenes distintos según el móvil — y peor, cambiarían al cambiar de
     * idioma. Se elige uno y se documenta.
     */
    fun startDate(): LocalDate = when (mode) {
        StatsPeriodMode.DAY -> anchor
        StatsPeriodMode.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        StatsPeriodMode.MONTH -> anchor.withDayOfMonth(1)
        StatsPeriodMode.YEAR -> anchor.withDayOfYear(1)
        // Sin significado: ALL no consulta por rango. Se devuelve algo válido para no obligar a
        // cada caller a un `when` que no le aporta nada.
        StatsPeriodMode.ALL -> anchor
    }

    /** Primer día del periodo SIGUIENTE (el rango es `[since, until)`). */
    fun endDateExclusive(): LocalDate = when (mode) {
        StatsPeriodMode.DAY -> anchor.plusDays(1)
        StatsPeriodMode.WEEK -> startDate().plusWeeks(1)
        StatsPeriodMode.MONTH -> startDate().plusMonths(1)
        StatsPeriodMode.YEAR -> startDate().plusYears(1)
        StatsPeriodMode.ALL -> anchor.plusDays(1)
    }

    companion object {
        /**
         * El periodo con el que abre la PANTALLA de estadísticas: el día en curso.
         *
         * **Solo sirve para eso.** Quien necesite un periodo CONCRETO lo construye nombrándolo
         * (`StatsSelection(StatsPeriodMode.MONTH, hoy)`) y no llamando aquí: este valor cambia
         * cuando cambia con qué abre la pantalla, y arrastró en silencio a la tarjeta del inicio
         * —que dice "Tu mes en música" y acabó enseñando el tiempo del DÍA— cuando entraron los
         * modos día y semana y esto pasó de MONTH a DAY. Nada falla al cambiarlo: el número sigue
         * saliendo, solo que es otro.
         *
         * **`atZone().toLocalDate()` y NUNCA `LocalDate.ofInstant(now, zone)`**, que hace justo
         * esto y se lee mejor: ese método es de Java 9 y en Android es **API 34**, aunque la clase
         * `LocalDate` exista desde la 26. Con `minSdk 26` y sin desugaring, compila, pasa la
         * release y lanza `NoSuchMethodError` al abrir esta pantalla en cualquier Android 8–13.
         * Estuvo así del 29 al 30 ago 2026. Lo cubre `verifyMinSdkApis` en `app/build.gradle.kts`.
         */
        fun current(zone: ZoneId, now: Instant): StatsSelection =
            StatsSelection(StatsPeriodMode.DAY, now.atZone(zone).toLocalDate())
    }
}
