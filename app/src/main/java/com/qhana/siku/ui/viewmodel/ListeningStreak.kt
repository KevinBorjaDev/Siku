package com.qhana.siku.ui.viewmodel

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Días seguidos escuchando, contando hacia atrás desde el último día con actividad.
 *
 * Va en Kotlin y no en SQL por una razón dura: minSdk 26 ⇒ el SQLite del sistema en Android 8.0 es
 * la 3.19, y las window functions llegaron en la 3.25, así que un gaps-and-islands honesto no se
 * puede escribir en la consulta.
 *
 * **Una racha está VIVA si su último día es hoy o ayer.** Si solo valiera "hoy", toda racha se
 * rompería sola a las 00:00, antes de que al usuario le dé tiempo a escuchar nada — y vería el
 * contador a cero cada mañana pese a no haber fallado ningún día.
 *
 * @param daysDescending días `yyyy-MM-dd` con actividad, del más reciente al más antiguo. Se toleran
 *   repetidos y desorden dentro de la lista: se normaliza aquí, porque quien la produce es una
 *   consulta que puede cambiar de `ORDER BY` sin que nadie recuerde esta dependencia.
 * @return días consecutivos, o 0 si no hay racha viva.
 */
fun computeStreak(daysDescending: List<String>, today: LocalDate): Int {
    val days = daysDescending.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .distinct()
        .sortedDescending()
    val last = days.firstOrNull() ?: return 0

    // Negativo = el último día está en el futuro (reloj movido hacia atrás): no es una racha.
    val gap = ChronoUnit.DAYS.between(last, today)
    if (gap < 0L || gap > 1L) return 0

    var streak = 1
    var expected = last.minusDays(1)
    for (day in days.drop(1)) {
        if (day != expected) break
        streak++
        expected = expected.minusDays(1)
    }
    return streak
}
