package com.qhana.siku.ui.viewmodel

import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Resolución del histograma de actividad: en qué casilla cae cada escucha.
 *
 * **[sqlFormat] y [keyOf] son el MISMO formato escrito dos veces, y por eso viven pegados.** El
 * primero lo aplica SQLite dentro del `GROUP BY` (`PlayEventDao.activityFlow`) y el segundo lo aplica
 * Kotlin para generar las claves que la consulta NO puede devolver: las de las casillas vacías. Si
 * los dos dejaran de coincidir, el histograma no fallaría — dibujaría un mes entero a cero, que es
 * exactamente el fallo silencioso que hay que evitar. Están juntos para que nadie toque uno solo.
 *
 * Es el mismo pacto que ya tienen `EqCurve` con el processor del EQ, o el `nextRetryAt` de las fotos
 * de artista con su SQL.
 *
 * **Hubo un tercer sitio y era el peligroso**: un campo `pattern` (`"yyyy-MM-dd"`) que este KDoc
 * presentaba como la mitad Kotlin del pacto y que **no leía nadie** — las claves se componían con
 * literales dentro de [buildActivityChart] y, por tercera vez, en [keyOf]. O sea que el pacto que
 * esto prometía no existía donde decía, y el test validaba la copia que la pantalla no usaba.
 * Ahora [keyOf] es la única, y la usan la pantalla y el test.
 */
enum class ActivityBucketing(val sqlFormat: String) {
    /** Los días de una semana o de un mes. */
    DAY("%Y-%m-%d"),

    /** Los meses de un año. */
    MONTH("%Y-%m");

    companion object {
        /**
         * La resolución de un periodo, o `null` si ese periodo no tiene histograma. **Dos de los
         * cinco no lo tienen, y por motivos distintos.**
         *
         * "Siempre" no puede: sus totales salen de `songs.playCount`, que no sabe cuándo sonó nada.
         *
         * **"Día" sí podría —por horas— y aun así no lo lleva** (device, 30 ago 2026: *"en el día
         * no va el histograma, es ruido"*). Un histograma sirve para ver una FORMA, y la de un día
         * de escucha no dice nada que el usuario no sepa: se escucha cuando se está despierto y
         * libre, así que sale el mismo perfil todos los días. Además una jornada normal deja dos o
         * tres horas con datos y veintiuna vacías, o sea un gráfico que es casi todo hueco. Se
         * quitó con la resolución por hora entera: sin periodo que la pida, `HOUR` era código que
         * no llamaba nadie.
         */
        fun forMode(mode: StatsPeriodMode): ActivityBucketing? = when (mode) {
            StatsPeriodMode.WEEK, StatsPeriodMode.MONTH -> DAY
            StatsPeriodMode.YEAR -> MONTH
            StatsPeriodMode.DAY, StatsPeriodMode.ALL -> null
        }
    }
}

/**
 * Lo que la capa de datos sabe del histograma: qué periodo, con qué resolución y cuántas escuchas
 * cayeron en cada clave.
 *
 * **Se pasa así de crudo a la pantalla, y el eje se arma allí** ([buildActivityChart]): las
 * etiquetas de un año son nombres de mes, o sea que dependen del idioma, y el idioma vive en la
 * composición (`LocalLocale`) mientras que un ViewModel sobrevive al cambio de configuración. Armado
 * aquí, un cambio de idioma dejaría el eje en el anterior hasta reabrir la app.
 */
data class StatsActivity(
    val selection: StatsSelection,
    val bucketing: ActivityBucketing,
    val plays: Map<String, Int>
)

/**
 * Una casilla del histograma ya colocada en el eje.
 *
 * @param label texto bajo la barra, o `""` si esa casilla no lleva ninguno. No todas lo llevan: 31
 *   números en 345dp no se leen.
 */
data class ActivityColumn(
    val plays: Int,
    val label: String
)

/**
 * El histograma de un periodo: una casilla por unidad de tiempo, INCLUIDAS las vacías.
 *
 * Las vacías son la mitad del mensaje —un mes con seis días en blanco tiene una forma distinta a uno
 * repartido, y el `GROUP BY` no puede devolver filas de días sin escuchas—, así que se reponen aquí.
 */
data class ActivityChart(
    val columns: List<ActivityColumn>
) {
    /** Escuchas de la casilla más alta; es contra lo que se escalan las barras. 0 = todo vacío. */
    val peak: Int get() = columns.maxOfOrNull { it.plays } ?: 0

    val isEmpty: Boolean get() = peak == 0
}

/** Cada cuántos días se etiqueta una barra en un mes: 1, 8, 15, 22, 29. */
private const val MONTH_LABEL_STEP = 7

/**
 * Arma el histograma de [selection] a partir de lo que devolvió la consulta.
 *
 * @param plays escuchas por clave, tal cual las agrupó SQLite; las claves ausentes valen cero.
 * @param locale para los nombres de mes del eje de un año. Los demás ejes son numéricos, así que no
 *   dependen del idioma — y la semana se etiqueta con el día del MES y no con la inicial del día de
 *   la semana, que en español colisiona (martes y miércoles comparten la M en varias tablas CLDR).
 */
fun buildActivityChart(
    selection: StatsSelection,
    bucketing: ActivityBucketing,
    plays: Map<String, Int>,
    locale: Locale
): ActivityChart = when (bucketing) {
    ActivityBucketing.DAY -> {
        val start = selection.startDate()
        val end = selection.endDateExclusive()
        // Se recorre por FECHA y no por un contador de días: un periodo que cruza un cambio de
        // horario de verano no tiene 24 h por día, y `plusDays` sí sabe de eso.
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.toList()
        // En una semana caben las siete etiquetas; en un mes no, y se pone una cada siete días
        // empezando por el 1 — así la primera y la última barra etiquetadas enmarcan el mes.
        val everyDay = days.size <= MONTH_LABEL_STEP
        ActivityChart(
            days.mapIndexed { index, day ->
                ActivityColumn(
                    plays = plays[bucketing.keyOf(day)] ?: 0,
                    label = if (everyDay || index % MONTH_LABEL_STEP == 0) {
                        day.dayOfMonth.toString()
                    } else ""
                )
            }
        )
    }

    ActivityBucketing.MONTH -> {
        val year = selection.startDate().year
        ActivityChart(
            (1..12).map { month ->
                ActivityColumn(
                    plays = plays[bucketing.keyOf(LocalDate.of(year, month, 1))] ?: 0,
                    label = Month.of(month).getDisplayName(TextStyle.NARROW, locale)
                )
            }
        )
    }
}

/**
 * La clave que produce SQLite para [date] con esta resolución: la mitad Kotlin del pacto con
 * [ActivityBucketing.sqlFormat].
 *
 * La usan [buildActivityChart] —para preguntar por cada casilla, incluidas las vacías— y el test.
 * Que sea la MISMA función en los dos sitios es lo que hace que el test signifique algo: mientras
 * las claves se componían con literales inline, se validaba una copia que la pantalla no usaba.
 */
internal fun ActivityBucketing.keyOf(date: LocalDate): String = when (this) {
    ActivityBucketing.DAY -> date.toString()
    ActivityBucketing.MONTH -> "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
}
