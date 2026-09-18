package com.qhana.siku.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ArtistCredits] es la ÚNICA definición de a qué artistas pertenece una canción: lo que diga aquí es
 * lo que cuenta la pestaña Artistas, lo que lista el detalle y lo que suman las estadísticas.
 */
class ArtistCreditsTest {

    private val defaults = ArtistSplitConfig(enabled = true, separators = ArtistSeparator.DEFAULT, exceptions = emptyList())

    @Test
    fun `apagado no parte nada`() {
        assertEquals(
            listOf("A feat. B"),
            ArtistCredits.trackCredits("A feat. B", artistCredits = null, config = ArtistSplitConfig.DISABLED)
        )
    }

    @Test
    fun `los separadores por defecto parten feat ft y punto y coma`() {
        assertEquals(listOf("A", "B"), ArtistCredits.split("A feat. B", defaults))
        assertEquals(listOf("A", "B"), ArtistCredits.split("A ft B", defaults))
        assertEquals(listOf("A", "B", "C"), ArtistCredits.split("A; B;C", defaults))
    }

    @Test
    fun `feat entre parentesis no deja el parentesis colgando`() {
        assertEquals(listOf("A", "B"), ArtistCredits.split("A (feat. B)", defaults))
        assertEquals(listOf("A", "B"), ArtistCredits.split("A [ft. B]", defaults))
    }

    @Test
    fun `los separadores de palabra exigen espacios alrededor`() {
        val conX = defaults.copy(separators = defaults.separators + ArtistSeparator.X)
        assertEquals(listOf("Malcolm X"), ArtistCredits.split("Malcolm X", conX))
        assertEquals(listOf("Defeat. Tour"), ArtistCredits.split("Defeat. Tour", defaults))
        assertEquals(listOf("A", "B"), ArtistCredits.split("A x B", conX))
    }

    @Test
    fun `los simbolos de nombres reales no estan en el default`() {
        assertEquals(listOf("Simon & Garfunkel"), ArtistCredits.split("Simon & Garfunkel", defaults))
        assertEquals(listOf("AC/DC"), ArtistCredits.split("AC/DC", defaults))
    }

    @Test
    fun `las excepciones sobreviven aunque su separador este activo`() {
        val config = ArtistSplitConfig(
            enabled = true,
            separators = setOf(ArtistSeparator.AMPERSAND, ArtistSeparator.COMMA, ArtistSeparator.FEAT),
            exceptions = listOf("earth, wind & fire")
        )
        assertEquals(
            listOf("Earth, Wind & Fire", "Chaka Khan"),
            ArtistCredits.split("Earth, Wind & Fire feat. Chaka Khan", config)
        )
    }

    @Test
    fun `los valores multivalor del archivo mandan sobre el campo artista`() {
        val credits = encodeMultiValue(listOf("A", "B"))
        assertEquals(listOf("A", "B"), ArtistCredits.trackCredits("A feat. B", credits, ArtistSplitConfig.DISABLED))
    }

    @Test
    fun `sin ALBUMARTIST el album cae a los artistas de la pista`() {
        val track = listOf("A", "B")
        assertEquals(track, ArtistCredits.albumCredits(albumArtist = "", trackCredits = track, config = defaults))
        assertEquals(track, ArtistCredits.albumCredits(albumArtist = null, trackCredits = track, config = defaults))
        assertEquals(listOf("A"), ArtistCredits.albumCredits(albumArtist = "A", trackCredits = track, config = defaults))
    }

    @Test
    fun `los duplicados se colapsan sin distinguir mayusculas`() {
        assertEquals(listOf("A"), ArtistCredits.split("A; a", defaults))
    }

    @Test
    fun `codificar y decodificar es simetrico e ignora los vacios`() {
        val encoded = encodeMultiValue(listOf(" A ", "", "B"))
        assertEquals(listOf("A", "B"), decodeMultiValue(encoded))
        assertEquals(null, encodeMultiValue(listOf("", " ")))
        assertEquals("A, B", displayMultiValue(encoded))
    }
}
