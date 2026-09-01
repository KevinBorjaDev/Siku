package com.qhana.siku.domain.usecase

import com.qhana.siku.data.util.LrcSyntax
import com.qhana.siku.ui.viewmodel.LyricLine
import com.qhana.siku.ui.viewmodel.LyricWord
import javax.inject.Inject

/**
 * Convierte el texto crudo de una letra en versos con tiempo.
 *
 * Entiende los tres formatos que llegan en la práctica, y lo que los separa es cuánta
 * sincronización traen:
 *  1. **Texto plano** — sin marcas. Todos los versos con tiempo 0.
 *  2. **LRC sincronizado** — `[mm:ss.xx]` por verso. Es lo que devuelve LrcLib.
 *  3. **Enhanced LRC (A2)** — además `<mm:ss.xx>` por palabra. Es lo único que permite el karaoke
 *     palabra a palabra, y hoy solo llega en archivos del propio usuario (un `.lrc` al lado de la
 *     canción o la letra embebida en sus tags): ningún proveedor gratuito lo sirve todavía.
 *
 * **Los tres se parsean SIEMPRE, sin ajuste que valga.** Una letra con tiempos por palabra se
 * dibuja palabra a palabra y una sin ellos, verso a verso; nada se degrada y no hay nada que
 * encender. La sintaxis vive en [LrcSyntax], no aquí.
 */
class ParseLyricsUseCase @Inject constructor() {

    operator fun invoke(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()

        val timed = mutableListOf<LyricLine>()
        val untimed = mutableListOf<String>()

        text.lineSequence().forEach { raw ->
            val times = raw.leadingLineTimes()
            if (times.millis.isEmpty()) {
                // Una línea suelta entre versos con marca es basura del archivo, no letra; se
                // recoge aparte y solo se usa si NINGUNA línea tenía marca (o sea, texto plano).
                // Cuál es metadata del formato (`[ar:…]`, `[offset:…]`) lo decide [LrcSyntax] y no
                // este archivo: aquí vivía una copia de la regla, y `LrcSyntax.stripTags` —que
                // pinta la MISMA letra en la vista plana— no la tenía, así que el `[offset:]` que
                // la app escribe al ajustar la sincronía salía ahí como si fuera el primer verso.
                if (raw.isNotBlank() && !LrcSyntax.isMetadataLine(raw)) {
                    untimed += raw.trim()
                }
                return@forEach
            }
            timed += raw.substring(times.consumedUntil).toVerses(times.millis)
        }

        // Texto plano: no hay tiempos, así que no hay nada que compensar.
        if (timed.isEmpty()) return untimed.map { LyricLine(NO_TIMESTAMP, it) }
        return timed.sortedBy { it.startTime }.closeOpenWords().shiftedBy(LrcSyntax.shiftMillisOf(text))
    }

    // --- Marcas de línea -----------------------------------------------------------------------

    /**
     * Las marcas `[mm:ss.xx]` del principio del verso. Son VARIAS a propósito: el formato permite
     * repetirlas para un estribillo que suena igual tres veces (`[00:42.10][01:58.30] Y así`), y
     * hasta hoy solo se leía la primera — la segunda se quedaba dentro del texto y se pintaba tal
     * cual, que es el mismo fallo que tenían las marcas de palabra.
     */
    private fun String.leadingLineTimes(): LineTimes {
        val millis = mutableListOf<Long>()
        var cursor = 0
        while (true) {
            // Se prueba desde el primer carácter con contenido, pero el cursor solo avanza si de
            // verdad había marca: así una línea de letra que empiece por espacios no se toca.
            var probe = cursor
            while (probe < length && this[probe].isWhitespace()) probe++
            val match = LrcSyntax.LINE_TAG.matchAt(this, probe) ?: break
            millis += LrcSyntax.millisOf(match)
            cursor = match.range.last + 1
        }
        return LineTimes(millis, cursor)
    }

    private class LineTimes(val millis: List<Long>, val consumedUntil: Int)

    /**
     * Un verso por cada marca de las que llevaba la línea.
     *
     * Los tiempos por palabra son ABSOLUTOS, así que solo valen para la primera aparición: en las
     * repeticiones se conserva el texto y se pierde el karaoke. Desplazarlos por la diferencia
     * entre marcas sería inventar una sincronización que el archivo no afirma.
     */
    private fun String.toVerses(times: List<Long>): List<LyricLine> {
        val first = parseContent(this, times.first())
        if (first.text.isBlank() || first.text in FILLER_TEXTS) return emptyList()
        return times.mapIndexed { index, millis ->
            if (index == 0) LyricLine(millis, first.text, first.words)
            else LyricLine(millis, first.text)
        }
    }

    /**
     * Aplica la compensación global del archivo (`[offset:]`, ver [LrcSyntax.shiftMillisOf]).
     *
     * **Se aplica AQUÍ, sobre el resultado ya armado, y no al leer cada marca**: es una sola resta
     * en un sitio en vez de tres —tiempos de verso, de inicio de palabra y de fin— y así no puede
     * quedarse a medias, que es como se vería si alguien añadiera después otra fuente de tiempos.
     *
     * No se reordena tras desplazar: un offset global mueve todo lo mismo, así que el orden que ya
     * tenían no cambia.
     *
     * Los tiempos NO se acotan a cero. Un desplazamiento grande puede dejar los primeros versos en
     * negativo, y eso es lo correcto: significa que ya habían pasado cuando la canción empezó, y
     * quien compara posición contra tiempo los da por vistos sin ningún caso especial. Acotarlos
     * los amontonaría todos en el 0 y el primer verso "empezaría" con la canción.
     */
    private fun List<LyricLine>.shiftedBy(millis: Long): List<LyricLine> {
        if (millis == 0L) return this
        return map { line ->
            line.copy(
                startTime = line.startTime + millis,
                words = line.words.map {
                    it.copy(startTime = it.startTime + millis, endTime = it.endTime + millis)
                }
            )
        }
    }

    // --- Contenido del verso -------------------------------------------------------------------

    private class ParsedContent(val text: String, val words: List<LyricWord>)

    /**
     * Parte el verso por sus marcas `<mm:ss.xx>`.
     *
     * Cada marca dice cuándo empieza el texto que la SIGUE, hasta la marca siguiente o el final. Una
     * marca al final sin texto detrás no es un tramo vacío: es el cierre del verso, y su valor es
     * cuándo termina la última palabra.
     */
    private fun parseContent(raw: String, lineStart: Long): ParsedContent {
        // Una marca de línea perdida en mitad del verso no es sintaxis de nada, pero existe en
        // archivos reales; se quita aquí para que el texto NUNCA pueda acabar mostrando una marca.
        val content = LrcSyntax.LINE_TAG.replace(raw, "")
        val tags = LrcSyntax.WORD_TAG.findAll(content).toList()
        if (tags.isEmpty()) return ParsedContent(content.normalizeSpacing(), emptyList())

        val entries = mutableListOf<Entry>()
        // El texto ANTERIOR a la primera marca empieza a sonar con el verso.
        content.substring(0, tags.first().range.first)
            .takeIf { it.isNotBlank() }
            ?.let { entries += Entry(lineStart, it) }
        tags.forEachIndexed { index, tag ->
            val from = tag.range.last + 1
            val to = if (index + 1 < tags.size) tags[index + 1].range.first else content.length
            entries += Entry(LrcSyntax.millisOf(tag), content.substring(from, to))
        }

        val builder = StringBuilder()
        val words = mutableListOf<LyricWord>()
        entries.forEachIndexed { index, entry ->
            if (entry.raw.isBlank()) return@forEachIndexed
            val startIndex = builder.appendSegment(entry.raw) ?: return@forEachIndexed
            words += LyricWord(
                startTime = entry.time,
                // La marca siguiente cierra ésta, sea texto o el cierre del verso. La última queda
                // ABIERTA y la cierra [closeOpenWords] cuando ya se conoce el verso que viene.
                endTime = entries.getOrNull(index + 1)?.time ?: OPEN_END,
                startIndex = startIndex,
                endIndex = builder.length
            )
        }

        // El texto se recorta por la derecha y los rangos se recortan CON él: son índices dentro de
        // esta misma cadena y un solo carácter de desfase corre el barrido del karaoke.
        val text = builder.toString()
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return ParsedContent(
            text = text.substring(0, end),
            words = words.mapNotNull { word ->
                val clamped = word.endIndex.coerceAtMost(end)
                if (word.startIndex >= clamped) null else word.copy(endIndex = clamped)
            }
        )
    }

    private class Entry(val time: Long, val raw: String)

    /**
     * Añade un tramo y devuelve dónde empezó, o `null` si no aportaba texto.
     *
     * Los espacios se colapsan al vuelo y no al final: el rango de cada palabra apunta a esta
     * cadena, así que normalizarla después los desalinearía a todos. El `trimStart` cuando ya
     * termina en espacio es lo que evita el hueco doble de `<00:01> Y <00:02> tú`, sin tocar los
     * idiomas que no separan con espacios.
     */
    private fun StringBuilder.appendSegment(raw: String): Int? {
        var segment = raw.replace(LrcSyntax.WHITESPACE_RUN, " ")
        if (isEmpty() || last().isWhitespace()) segment = segment.trimStart()
        if (segment.isEmpty()) return null
        val startIndex = length
        append(segment)
        return startIndex
    }

    private fun String.normalizeSpacing(): String = replace(LrcSyntax.WHITESPACE_RUN, " ").trim()

    // --- Cierre de la última palabra -----------------------------------------------------------

    /**
     * Da fin a la última palabra de cada verso, que el archivo puede no cerrar.
     *
     * Lo natural es que dure hasta que empiece el verso siguiente. Para el ÚLTIMO verso de la
     * canción no hay siguiente, y ahí dura **lo que dura una palabra en esta misma letra**, medido
     * de las que el archivo sí cierra. Un número fijo estaría mal por definición: el barrido de una
     * balada y el de un rap no se parecen en nada, y el valor que le quedara bien a uno le quedaría
     * absurdo al otro.
     *
     * Si la letra no cierra NINGUNA palabra no hay de dónde aprenderlo, y entonces la última se
     * enciende al llegar (duración cero, que [LyricWord.progressAt] resuelve como "ya está"). Es un
     * caso degenerado —una letra de un solo verso y sin marca de cierre— y encenderla es una
     * decisión explícita, no una constante disfrazada.
     */
    private fun List<LyricLine>.closeOpenWords(): List<LyricLine> {
        // Solo valen las que cierra el ARCHIVO. Una palabra cerrada por el verso siguiente se lleva
        // dentro el silencio que haya entre versos, así que un intermedio instrumental de veinte
        // segundos bastaría para que esta media dejara de significar nada.
        val authoredDurations = flatMap(LyricLine::words)
            .filter { it.endTime != OPEN_END }
            .map { it.endTime - it.startTime }
        val typicalWordMillis =
            if (authoredDurations.isEmpty()) INSTANT else authoredDurations.average().toLong()

        return mapIndexed { index, line ->
            if (!line.hasWordTiming) return@mapIndexed line
            val nextStart = getOrNull(index + 1)?.startTime
            line.copy(
                words = line.words.map { word ->
                    if (word.endTime != OPEN_END) word
                    else word.copy(endTime = nextStart ?: (word.startTime + typicalWordMillis))
                }
            )
        }
    }

    private companion object {
        /** Tiempo de un verso sin marca: la letra es plana y todos los versos empiezan "ya". */
        const val NO_TIMESTAMP = 0L

        /** Fin aún desconocido: lo resuelve [closeOpenWords] con el verso siguiente. */
        const val OPEN_END = -1L

        /** Duración cero: la palabra se enciende entera en cuanto el barrido llega a ella. */
        const val INSTANT = 0L

        /** Versos que algunos generadores dejan como relleno donde no se canta nada. */
        val FILLER_TEXTS = setOf(".", "-")
    }
}
