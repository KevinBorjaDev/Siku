package com.qhana.siku.data.util

import com.qhana.siku.domain.usecase.ParseLyricsUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La compensación global `[offset:]` del LRC.
 *
 * **Lo que protegen estos tests es el SIGNO**, que es lo único aquí que puede equivocarse sin que
 * nada falle: la letra sigue saliendo, solo que desplazada al otro lado, y quien lo mire pensará que
 * el archivo estaba mal. Por el formato, `+` hace que la letra aparezca ANTES
 * (`tiempo_efectivo = marca − offset`), o sea al revés del `shiftMillis` con el que razona la app.
 *
 * El caso que trajo todo esto: *The Zephyr Song*, remaster de 2014 contra la transcripción del
 * original, 2,03 s de desfase (31 ago 2026).
 */
class LrcOffsetTest {

    private val parse = ParseLyricsUseCase()

    private val synced = """
        [00:16.86]Can I get your hand to write on?
        [00:18.88]Just a piece of lead to bite on
    """.trimIndent()

    // --- Lectura del tag ---------------------------------------------------------------------

    @Test
    fun `sin marca no hay desplazamiento`() {
        assertEquals(0L, LrcSyntax.shiftMillisOf(synced))
    }

    @Test
    fun `un offset positivo ADELANTA la letra, o sea desplaza en negativo`() {
        assertEquals(-500L, LrcSyntax.shiftMillisOf("[offset:+500]\n$synced"))
    }

    @Test
    fun `un offset negativo RETRASA la letra`() {
        assertEquals(2000L, LrcSyntax.shiftMillisOf("[offset:-2000]\n$synced"))
    }

    @Test
    fun `el signo mas es opcional y da igual donde este la marca`() {
        assertEquals(-300L, LrcSyntax.shiftMillisOf("[ti:X]\n[offset:300]\n$synced"))
    }

    // --- Escritura ---------------------------------------------------------------------------

    @Test
    fun `escribir el desplazamiento usa el signo del FORMATO, que es el contrario`() {
        // Retrasar la letra 2 s se escribe como offset NEGATIVO.
        assertTrue(LrcSyntax.withShift(synced, 2000L).startsWith("[offset:-2000]"))
        assertTrue(LrcSyntax.withShift(synced, -500L).startsWith("[offset:+500]"))
    }

    @Test
    fun `escribir y volver a leer da el mismo valor`() {
        listOf(2000L, -2000L, 100L, -3500L).forEach { shift ->
            assertEquals(shift, LrcSyntax.shiftMillisOf(LrcSyntax.withShift(synced, shift)))
        }
    }

    @Test
    fun `reajustar no acumula marcas ni lineas en blanco`() {
        // El texto es lo que se PERSISTE y lo que acaba en el `.lrc` del usuario: veinte toques
        // seguidos no pueden dejar veinte marcas ni veinte líneas vacías arriba.
        var text = synced
        repeat(20) { text = LrcSyntax.withShift(text, 1000L) }
        assertEquals(1, LrcSyntax.OFFSET_TAG.findAll(text).count())
        assertTrue(text.startsWith("[offset:-1000]"))
        assertFalse("no debe quedar una línea vacía tras la marca", text.contains("\n\n"))
    }

    @Test
    fun `poner el desplazamiento a cero QUITA la marca`() {
        val cleared = LrcSyntax.withShift(LrcSyntax.withShift(synced, 2000L), 0L)
        assertEquals(0, LrcSyntax.OFFSET_TAG.findAll(cleared).count())
        assertEquals(synced, cleared)
    }

    // --- Aplicación al parseo -----------------------------------------------------------------

    @Test
    fun `el parser retrasa los versos con un offset negativo`() {
        val lines = parse("[offset:-2000]\n$synced")
        assertEquals(2, lines.size)
        assertEquals(16_860L + 2_000L, lines[0].startTime)
        assertEquals(18_880L + 2_000L, lines[1].startTime)
    }

    @Test
    fun `el parser adelanta los versos con un offset positivo`() {
        val lines = parse("[offset:+500]\n$synced")
        assertEquals(16_860L - 500L, lines[0].startTime)
    }

    @Test
    fun `el desplazamiento tambien mueve los tiempos por palabra`() {
        // Sin esto el karaoke quedaría barriendo sobre los tiempos originales mientras el verso ya
        // está corrido: el resaltado iría por su cuenta y sería peor que no ajustar nada.
        val a2 = "[offset:-1000]\n[00:10.00]<00:10.00>Fly <00:11.00>away<00:12.00>"
        val line = parse(a2).single()
        assertEquals(11_000L, line.startTime)
        assertEquals(11_000L, line.words.first().startTime)
        assertEquals(13_000L, line.words.last().endTime)
    }

    @Test
    fun `un desplazamiento grande puede dejar tiempos negativos y no se acota`() {
        // Negativo significa "ya había pasado cuando la canción empezó", y quien compara posición
        // contra tiempo lo da por visto sin ningún caso especial. Acotarlos a cero amontonaría los
        // primeros versos en el arranque.
        val lines = parse("[offset:+30000]\n$synced")
        assertTrue(lines[0].startTime < 0L)
    }

    @Test
    fun `la marca de offset no se pinta como letra`() {
        val lines = parse("[offset:-2000]\n$synced")
        assertFalse(lines.any { it.text.contains("offset", ignoreCase = true) })
    }

    @Test
    fun `en texto plano el offset no rompe nada`() {
        // Sin marcas de tiempo no hay nada que desplazar, y la línea de metadata sigue sin pintarse.
        val lines = parse("[offset:-2000]\nUna letra sin sincronizar")
        assertEquals(1, lines.size)
        assertEquals("Una letra sin sincronizar", lines[0].text)
    }

    // --- La MISMA letra en la vista PLANA ------------------------------------------------------
    //
    // `stripTags` es el otro camino por el que la misma letra llega a la pantalla, y conocía media
    // sintaxis: quitaba las marcas de tiempo pero no la metadata. Desde que la app escribe su
    // propio `[offset:]`, eso significaba estrenar la compensación como primer verso.

    @Test
    fun `la vista plana no pinta la marca de offset`() {
        val plain = LrcSyntax.stripTags("[offset:-2000]\n$synced")
        assertFalse(plain.contains("offset", ignoreCase = true))
        assertTrue(plain.startsWith("Can I get your hand to write on?"))
    }

    @Test
    fun `la vista plana no pinta el resto de la metadata`() {
        val plain = LrcSyntax.stripTags("[ar:Red Hot Chili Peppers]\n[ti:The Zephyr Song]\n$synced")
        assertFalse(plain.contains("["))
    }

    @Test
    fun `quitar una linea de metadata no deja un renglon en blanco`() {
        // Quitarle solo el TAG dejaría la línea vacía, o sea una estrofa partida donde el archivo
        // no tenía ninguna separación. Por eso se descarta la línea entera.
        assertEquals(2, LrcSyntax.stripTags("[offset:+500]\n$synced").lines().size)
    }

    @Test
    fun `la vista plana conserva las lineas en blanco de verdad`() {
        // Las vacías separan estrofas: aplastarlas convierte un poema en un párrafo. El filtro de
        // metadata no puede llevárselas de paso.
        val plain = LrcSyntax.stripTags("Primera\n\nSegunda")
        assertEquals(listOf("Primera", "", "Segunda"), plain.lines())
    }

    @Test
    fun `la vista plana conserva los versos sincronizados`() {
        // La guarda que importa: un verso con marca empieza por corchete igual que un `[ar:]`, así
        // que preguntar por la metadata ANTES de quitar los tiempos borraría la letra entera.
        assertEquals(
            listOf("Can I get your hand to write on?", "Just a piece of lead to bite on"),
            LrcSyntax.stripTags(synced).lines()
        )
    }
}
