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

    /**
     * Marca de compensación global `[offset:+500]` / `[offset:-2000]`, en milisegundos.
     *
     * **El signo es al revés de lo que parece**: por el formato, `+` hace que la letra aparezca
     * ANTES, o sea `tiempo_efectivo = marca − offset`. Escribirlo al revés no rompe nada visible —
     * la letra sigue saliendo, solo que desplazada al otro lado— y por eso está anotado aquí y no
     * en el sitio donde se aplica.
     *
     * Toda la app razona con [shiftMillisOf], que es este valor NEGADO: cuánto se RETRASA la letra.
     * Se eligió así porque es el único sentido que se puede etiquetar en una pantalla sin que el
     * usuario tenga que saber nada del formato — "va adelantada, súbelo" —, y la conversión pasa
     * por un solo sitio.
     */
    val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * Cuánto hay que RETRASAR la letra, en ms. Positivo = los versos salen más tarde.
     *
     * Es [OFFSET_TAG] negado; ver allí por qué. Sin marca, 0.
     */
    fun shiftMillisOf(text: String): Long =
        OFFSET_TAG.find(text)?.groupValues?.get(1)?.toLongOrNull()?.unaryMinus() ?: 0L

    /**
     * Devuelve [text] con el retraso puesto a [shiftMillis], reemplazando la marca que hubiera.
     *
     * Se guarda en el PROPIO texto de la letra y no en una columna aparte, y eso es deliberado: es
     * el mecanismo que el formato ya tiene para esto, así que un ajuste hecho en la app viaja solo
     * cuando el usuario guarda la letra en su `.lrc` o en los tags, y otro reproductor lo respeta.
     * Una columna en Room habría necesitado además leer igualmente esta marca de los archivos
     * ajenos: dos mecanismos para el mismo dato, y la pregunta sin respuesta de cuál gana.
     *
     * Con 0 la marca se QUITA en vez de escribir `[offset:+0]`: un archivo sin compensación no debe
     * distinguirse de uno que la tuvo y se reseteó.
     */
    fun withShift(text: String, shiftMillis: Long): String {
        val stripped = LEADING_BLANK_LINES.replace(OFFSET_TAG.replace(text, ""), "")
        if (shiftMillis == 0L) return stripped
        // El signo que se ESCRIBE es el del formato, o sea el contrario al que maneja la app.
        val tag = -shiftMillis
        val sign = if (tag >= 0) "+" else ""
        return "[offset:$sign$tag]\n$stripped"
    }

    /**
     * Los saltos que deja quitar la marca cuando estaba en su propia línea.
     *
     * Se limpian para que reajustar cien veces no vaya dejando cien líneas en blanco arriba: el
     * texto es lo que se PERSISTE en `songs.lyrics` y lo que acaba escrito en el `.lrc` del usuario.
     */
    private val LEADING_BLANK_LINES = Regex("^[\\s\\n]+")

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
     * ¿Esta línea es metadata del formato (`[ar:…]`, `[ti:…]`, `[offset:…]`) en vez de letra?
     *
     * **Se pregunta sobre la línea YA SIN MARCAS DE TIEMPO**, nunca sobre la cruda: un verso
     * sincronizado empieza por corchete igual que un `[ar:]`, así que preguntándolo antes se
     * borraría la letra entera. Los dos llamantes ya están en ese punto — el parser porque llegó a
     * esa rama justo por no encontrar marcas al principio, y [stripTags] porque las quita antes de
     * filtrar.
     *
     * El criterio es "empieza por corchete" y no una lista de claves conocidas: los archivos traen
     * claves que no están en ninguna especificación (`[re:]`, `[ve:]`, `[tool:]`) y una lista las
     * dejaría pasar como si fueran versos. Se lleva por delante también los rótulos tipo
     * `[Chorus]`, que es lo que el parser ya hacía — y que las dos vistas de la misma letra
     * coincidan es justo el motivo de que la regla viva aquí y no en cada una.
     */
    fun isMetadataLine(line: String): Boolean = line.trimStart().startsWith(METADATA_PREFIX)

    /**
     * El texto sin NINGUNA marca, para leerlo como letra corrida.
     *
     * Se procesa línea a línea y se conservan las vacías: en una letra separan estrofas, y
     * aplastarlas convierte un poema en un párrafo. Los espacios interiores sí se colapsan, porque
     * al quitar las marcas de palabra quedan dobles casi siempre (`<00:01>Y <00:02>tú` deja `Y  tú`).
     *
     * **Las líneas de metadata se DESCARTAN enteras** ([isMetadataLine]), y no vale con quitarles
     * el tag: dejarían un renglón en blanco donde el archivo no tenía ninguno, o sea una estrofa
     * partida. Faltaba hacerlo, y desde que la app escribe su propio `[offset:]` al ajustar la
     * sincronía dejó de ser cosa de archivos ajenos — la vista PLANA estrenaba la compensación
     * como si fuera el primer verso de la canción.
     *
     * Es la tercera vuelta del mismo error: esta función conocía media sintaxis (las marcas de
     * línea) y el parser la otra media (la metadata), cada una ciega a lo que sabía la otra.
     */
    fun stripTags(text: String): String = text.lineSequence()
        .map { line -> WORD_TAG.replace(LINE_TAG.replace(line, ""), "") }
        .filterNot { isMetadataLine(it) }
        .joinToString(separator = "\n") { line ->
            line.replace(WHITESPACE_RUN, " ").trim()
        }

    /** Dos o más espacios/tabuladores seguidos: lo que dejan las marcas al quitarse. */
    val WHITESPACE_RUN = Regex("""[ \t]{2,}""")

    /** Con qué empieza una línea de metadata del formato. Ver [isMetadataLine]. */
    private const val METADATA_PREFIX = "["

    private const val DECISECOND_DIGITS = 1
    private const val CENTISECOND_DIGITS = 2
    private const val MILLIS_PER_DECISECOND = 100L
    private const val MILLIS_PER_CENTISECOND = 10L
}
