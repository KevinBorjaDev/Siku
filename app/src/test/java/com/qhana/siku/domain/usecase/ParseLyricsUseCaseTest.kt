package com.qhana.siku.domain.usecase

import com.qhana.siku.ui.viewmodel.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parser de letras, que es lógica pura y por tanto lo único de este subsistema que se puede
 * verificar sin un dispositivo.
 *
 * Lo que más se comprueba aquí es la ALINEACIÓN entre `LyricLine.text` y los rangos de sus palabras:
 * el karaoke recorta por índice de carácter, así que un desfase de uno no rompe nada visible en
 * compilación ni en ejecución — simplemente el resaltado va corrido, que es un fallo que solo se ve
 * mirando la pantalla con la canción sonando.
 */
class ParseLyricsUseCaseTest {

    private val parse = ParseLyricsUseCase()

    /** Cada palabra tiene que poder recortarse de su propio verso: es la invariante del karaoke. */
    private fun assertWordsAlign(line: LyricLine) {
        line.words.forEach { word ->
            assertTrue(
                "rango fuera del verso: ${word.startIndex}..${word.endIndex} en '${line.text}'",
                word.startIndex in 0..line.text.length && word.endIndex in 0..line.text.length
            )
            assertTrue("rango vacío o invertido", word.startIndex < word.endIndex)
        }
        // Y en orden, sin solaparse.
        line.words.zipWithNext().forEach { (previous, next) ->
            assertTrue("palabras solapadas", previous.endIndex <= next.startIndex)
        }
    }

    // --- Texto plano ---------------------------------------------------------------------------

    @Test
    fun `texto sin marcas da versos sin tiempo`() {
        val lines = parse("Primera\nSegunda")

        assertEquals(listOf("Primera", "Segunda"), lines.map { it.text })
        assertTrue(lines.all { it.startTime == 0L })
        assertTrue(lines.none { it.hasWordTiming })
    }

    @Test
    fun `las lineas en blanco no son versos`() {
        assertEquals(2, parse("Primera\n\n\nSegunda").size)
    }

    // --- LRC sincronizado por verso ------------------------------------------------------------

    @Test
    fun `lrc clasico da tiempos por verso y texto limpio`() {
        val lines = parse("[00:12.34]Uno\n[01:02.50]Dos")

        assertEquals(listOf(12_340L, 62_500L), lines.map { it.startTime })
        assertEquals(listOf("Uno", "Dos"), lines.map { it.text })
        assertTrue(lines.none { it.hasWordTiming })
    }

    @Test
    fun `la fraccion se interpreta por su numero de cifras`() {
        val lines = parse("[00:01.5]a\n[00:01.50]b\n[00:01.500]c")

        // Las tres escrituras son el MISMO instante: décimas, centésimas y milésimas.
        assertEquals(listOf(1_500L, 1_500L, 1_500L), lines.map { it.startTime })
    }

    @Test
    fun `la metadata del formato no es letra`() {
        val lines = parse("[ar:Artista]\n[ti:Titulo]\n[offset:+500]\n[00:01.00]Verso")

        assertEquals(listOf("Verso"), lines.map { it.text })
    }

    @Test
    fun `un verso repetido en varias marcas sale una vez por marca`() {
        val lines = parse("[00:42.10][01:58.30]Y asi")

        assertEquals(listOf(42_100L, 118_300L), lines.map { it.startTime })
        assertEquals(listOf("Y asi", "Y asi"), lines.map { it.text })
    }

    // --- Enhanced LRC (A2), que es lo que permite el karaoke -------------------------------------

    @Test
    fun `las marcas de palabra nunca acaban dentro del texto`() {
        val lines = parse("[00:00.00]<00:00.04>When <00:00.16>the <00:00.82>truth")

        assertEquals("When the truth", lines.single().text)
        assertFalse(lines.single().text.contains('<'))
    }

    @Test
    fun `cada palabra conserva su tiempo y su sitio en el verso`() {
        val line = parse("[00:00.00]<00:00.04>When <00:00.16>the <00:00.82>truth").single()

        assertTrue(line.hasWordTiming)
        assertEquals(listOf(40L, 160L, 820L), line.words.map { it.startTime })
        assertEquals(
            listOf("When ", "the ", "truth"),
            line.words.map { line.text.substring(it.startIndex, it.endIndex) }
        )
        assertWordsAlign(line)
    }

    @Test
    fun `el espacio que sigue a la marca no duplica el hueco`() {
        // Escritura con espacio DETRÁS de cada marca: la del ejemplo canónico del formato.
        val line = parse("[00:00.00] <00:00.04> When <00:00.16> the").single()

        assertEquals("When the", line.text)
        assertWordsAlign(line)
    }

    @Test
    fun `el texto anterior a la primera marca empieza con el verso`() {
        val line = parse("[00:10.00]Hola <00:10.50>mundo").single()

        assertEquals("Hola mundo", line.text)
        assertEquals(listOf(10_000L, 10_500L), line.words.map { it.startTime })
        assertWordsAlign(line)
    }

    @Test
    fun `una marca final sin texto cierra la ultima palabra`() {
        val line = parse("[00:10.00]<00:10.00>uno <00:10.40>dos<00:11.00>").single()

        assertEquals("uno dos", line.text)
        assertEquals(2, line.words.size)
        assertEquals(11_000L, line.words.last().endTime)
        assertWordsAlign(line)
    }

    @Test
    fun `sin marca de cierre la ultima palabra dura hasta el verso siguiente`() {
        val lines = parse("[00:00.00]<00:00.10>uno\n[00:02.00]dos")

        assertEquals(2_000L, lines.first().words.last().endTime)
    }

    @Test
    fun `la ultima palabra de la cancion dura lo que dura una palabra en esa letra`() {
        // El archivo cierra dos palabras por sí mismo: "uno" (500 ms) y "tres" (400 ms). La única
        // que se queda abierta sin verso detrás hereda esa media, 450, y no un número fijo.
        // "dos" NO cuenta como muestra: la cierra el verso siguiente, o sea que se lleva dentro el
        // hueco entre versos.
        val lines = parse(
            "[00:00.00]<00:00.00>uno <00:00.50>dos\n" +
                "[00:10.00]<00:10.00>tres <00:10.40>cuatro"
        )

        val last = lines.last().words.last()
        assertEquals(10_400L, last.startTime)
        assertEquals(10_400L + 450L, last.endTime)
    }

    @Test
    fun `sin ninguna palabra cerrada la ultima se enciende al llegar`() {
        // Caso degenerado: no hay de dónde aprender cuánto dura una palabra en esta letra.
        val word = parse("[00:05.00]<00:05.00>sola").single().words.single()

        assertEquals(word.startTime, word.endTime)
        assertEquals(1f, word.progressAt(5_000L), 0.001f)
    }

    @Test
    fun `en un verso repetido solo la primera aparicion conserva el karaoke`() {
        // Los tiempos por palabra son absolutos: en la repetición no significarían nada.
        val lines = parse("[00:10.00][00:20.00]<00:10.00>Hola <00:10.50>mundo")

        assertEquals(2, lines.size)
        assertTrue(lines.first().hasWordTiming)
        assertFalse(lines.last().hasWordTiming)
        assertEquals(lines.first().text, lines.last().text)
    }

    @Test
    fun `el progreso de una palabra va de cero a uno dentro de su tramo`() {
        val word = parse("[00:10.00]<00:10.00>uno<00:11.00>").single().words.single()

        assertEquals(0f, word.progressAt(10_000L), 0.001f)
        assertEquals(0.5f, word.progressAt(10_500L), 0.001f)
        assertEquals(1f, word.progressAt(11_000L), 0.001f)
        // Fuera del tramo se satura: el barrido no puede salirse de la palabra.
        assertEquals(0f, word.progressAt(0L), 0.001f)
        assertEquals(1f, word.progressAt(99_000L), 0.001f)
    }

    // --- Mezclas y bordes ----------------------------------------------------------------------

    @Test
    fun `un archivo con versos por palabra y por linea conserva los dos`() {
        val lines = parse("[00:00.00]<00:00.10>uno\n[00:02.00]dos entero")

        assertTrue(lines.first().hasWordTiming)
        assertFalse(lines.last().hasWordTiming)
        assertEquals("dos entero", lines.last().text)
    }

    @Test
    fun `los versos salen en orden de tiempo aunque el archivo no lo este`() {
        val lines = parse("[00:20.00]tarde\n[00:10.00]pronto")

        assertEquals(listOf("pronto", "tarde"), lines.map { it.text })
    }

    @Test
    fun `una letra vacia no da versos`() {
        assertTrue(parse(null).isEmpty())
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n  ").isEmpty())
    }
}
