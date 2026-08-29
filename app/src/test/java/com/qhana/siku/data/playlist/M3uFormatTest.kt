package com.qhana.siku.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El M3U es un formato SIN estándar: lo que existe son las variantes que escriben Winamp, foobar,
 * VLC, Poweramp y cualquiera que haya volcado rutas a un archivo. Estos casos fijan la política del
 * parser ante esa realidad — tolerar lo raro y no perder ninguna entrada — que es justo lo que se
 * rompe al "limpiar" el código más tarde.
 */
class M3uFormatTest {

    @Test
    fun `una ruta por linea, sin ninguna directiva, sigue siendo una lista`() {
        // La forma ORIGINAL del formato, y la que escribe cualquier script casero. Un parser que
        // exigiera #EXTM3U o #EXTINF la leería como un archivo vacío.
        val parsed = M3uFormat.parse("Music/a.flac\nMusic/b.flac\n")

        assertEquals(listOf("Music/a.flac", "Music/b.flac"), parsed.entries.map { it.reference })
        assertNull(parsed.name)
    }

    @Test
    fun `el EXTINF aporta artista, titulo y duracion a la entrada siguiente`() {
        val parsed = M3uFormat.parse(
            """
            #EXTM3U
            #PLAYLIST:Para correr
            #EXTINF:245,Dream Theater - Pull Me Under
            /storage/emulated/0/Music/pull.flac
            """.trimIndent()
        )

        assertEquals("Para correr", parsed.name)
        val entry = parsed.entries.single()
        assertEquals("/storage/emulated/0/Music/pull.flac", entry.reference)
        assertEquals("Dream Theater", entry.artist)
        assertEquals("Pull Me Under", entry.title)
        assertEquals(245L, entry.durationSeconds)
    }

    @Test
    fun `sin el separador de artista, la etiqueta entera es el titulo`() {
        // " - " es una CONVENCIÓN, no parte del formato. Partir por el primer guion rompería
        // "Jay-Z" y cualquier título con guion, así que ante la duda no se parte.
        val entry = M3uFormat.parse("#EXTINF:100,Bohemian Rhapsody\na.mp3").entries.single()

        assertNull(entry.artist)
        assertEquals("Bohemian Rhapsody", entry.title)
    }

    @Test
    fun `una duracion negativa o ilegible no invalida la entrada`() {
        // Los streams se escriben con -1, y hay taggers que dejan la duración en blanco.
        val entries = M3uFormat.parse("#EXTINF:-1,X - Y\na.mp3\n#EXTINF:,Z\nb.mp3").entries

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.durationSeconds == null })
    }

    @Test
    fun `una directiva desconocida entre el EXTINF y su ruta no rompe el par`() {
        // #EXTGRP y compañía aparecen en archivos reales. Si limpiaran el #EXTINF pendiente, la
        // canción perdería sus tags justo cuando la ruta no resuelve y son lo único que queda.
        val entry = M3uFormat.parse("#EXTINF:10,A - B\n#EXTGRP:Rock\na.mp3").entries.single()

        assertEquals("A", entry.artist)
        assertEquals("a.mp3", entry.reference)
    }

    @Test
    fun `el BOM de la primera linea no se pega a la primera directiva`() {
        // Un editor de Windows lo escribe al guardar UTF-8. Pegado al "#EXTM3U" daba igual, pero
        // pegado a una RUTA la vuelve inencontrable, y no se ve al mirar el archivo.
        val parsed = M3uFormat.parse("\uFEFFMusic/a.flac")

        assertEquals("Music/a.flac", parsed.entries.single().reference)
    }

    @Test
    fun `lo escrito se vuelve a leer igual`() {
        // La propiedad que de verdad importa: exportar e importar en el mismo teléfono no puede
        // perder ni cambiar nada.
        val original = M3uPlaylist(
            name = "Mi lista",
            entries = listOf(
                M3uEntry("/storage/emulated/0/Music/a.flac", "Título", "Artista", 200L),
                M3uEntry("Music/b.mp3", "Otro", "Otra", null)
            )
        )

        val reparsed = M3uFormat.parse(M3uFormat.write(original))

        assertEquals(original.name, reparsed.name)
        assertEquals(original.entries.map { it.reference }, reparsed.entries.map { it.reference })
        assertEquals(original.entries.map { it.artist }, reparsed.entries.map { it.artist })
        assertEquals(original.entries.map { it.title }, reparsed.entries.map { it.title })
        // La duración ausente se escribe como -1 y vuelve a leerse como ausente.
        assertEquals(listOf(200L, null), reparsed.entries.map { it.durationSeconds })
    }
}
