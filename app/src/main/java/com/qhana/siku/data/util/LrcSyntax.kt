package com.qhana.siku.data.util

import java.util.concurrent.TimeUnit

/**
 * La sintaxis del formato LRC, en UN solo sitio.
 *
 * Hay DOS marcas de tiempo y conviene no confundirlas:
 *  - **De línea**, `[mm:ss.xx]` al principio del verso: cuándo empieza. Es el LRC de toda la vida.
 *  - **De palabra**, `<mm:ss.xx>` DENTRO del verso: cuándo empieza cada tramo. Es la extensión
 *    conocida como *enhanced LRC* o **A2**, y es lo único que permite el karaoke palabra a palabra.
 *
 * Esta regla estaba escrita por DUPLICADO y a medias: [ParseLyricsUseCase] conocía la primera y
 * `PlainLyricsView` tenía su propia copia del regex, y ninguna de las dos conocía la segunda — así
 * que un archivo con tiempos por palabra pintaba los `<00:00.04>` como si fueran letra. Dos copias
 * de una regla son dos verdades; aquí solo hay una y quien la necesite la pide.
 *
 * La tolerancia del parseo es deliberada: estos archivos los escriben decenas de programas
 * distintos y ninguno valida contra una especificación (no existe una formal). Aceptar `[9:05]`,
 * `[09:05.7]` y `[09:05:70]` no cuesta nada y evita que una letra perfectamente buena se pinte como
 * texto plano por un punto y coma.
 */
internal object LrcSyntax {

    /**
     * Marca de línea `[mm:ss.xx]`. Los minutos admiten una o dos cifras y la fracción de segundo es
     * opcional, con `.` o `:` de separador.
     *
     * Exigir dígitos donde van los minutos es lo que deja fuera la metadata del propio formato
     * (`[ar:Artista]`, `[ti:Título]`, `[offset:+500]`) sin necesidad de una lista de claves.
     */
    val LINE_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Marca de palabra `<mm:ss.xx>` (enhanced LRC / A2). Misma tolerancia que [LINE_TAG]. */
    val WORD_TAG = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** ¿El texto trae tiempos por PALABRA? (o sea, ¿se puede hacer karaoke con él). */
    fun hasWordTiming(text: String): Boolean = WORD_TAG.containsMatchIn(text)

    /** ¿El texto trae tiempos por LÍNEA? (o sea, ¿es sincronizado y no texto plano). */
    fun hasLineTiming(text: String): Boolean = LINE_TAG.containsMatchIn(text)

    /**
     * Milisegundos de una marca ya capturada por [LINE_TAG] o [WORD_TAG] — las dos tienen los
     * mismos tres grupos, que es justo por lo que este cálculo puede ser uno solo.
     */
    fun millisOf(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        return TimeUnit.MINUTES.toMillis(minutes.toLong()) +
            TimeUnit.SECONDS.toMillis(seconds.toLong()) +
            fractionToMillis(fraction)
    }

    /**
     * La fracción de segundo se interpreta por su NÚMERO DE CIFRAS, que es lo único que la
     * distingue: `.7` son siete décimas, `.70` setenta centésimas y `.700` setecientos
     * milisegundos — los tres el mismo instante, escritos por tres programas distintos.
     */
    private fun fractionToMillis(fraction: String): Long = when (fraction.length) {
        0 -> 0L
        DECISECOND_DIGITS -> fraction.toLong() * MILLIS_PER_DECISECOND
        CENTISECOND_DIGITS -> fraction.toLong() * MILLIS_PER_CENTISECOND
        else -> fraction.toLong()
    }

    /**
     * El texto sin NINGUNA marca, para leerlo como letra corrida.
     *
     * Se procesa línea a línea y se conservan las vacías: en una letra separan estrofas, y
     * aplastarlas convierte un poema en un párrafo. Los espacios interiores sí se colapsan, porque
     * al quitar las marcas de palabra quedan dobles casi siempre (`<00:01>Y <00:02>tú` deja `Y  tú`).
     */
    fun stripTags(text: String): String = text.lineSequence()
        .joinToString(separator = "\n") { line ->
            LINE_TAG.replace(line, "")
                .let { WORD_TAG.replace(it, "") }
                .replace(WHITESPACE_RUN, " ")
                .trim()
        }

    /** Dos o más espacios/tabuladores seguidos: lo que dejan las marcas al quitarse. */
    val WHITESPACE_RUN = Regex("""[ \t]{2,}""")

    private const val DECISECOND_DIGITS = 1
    private const val CENTISECOND_DIGITS = 2
    private const val MILLIS_PER_DECISECOND = 100L
    private const val MILLIS_PER_CENTISECOND = 10L
}
