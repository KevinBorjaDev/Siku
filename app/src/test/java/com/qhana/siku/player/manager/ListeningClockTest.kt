package com.qhana.siku.player.manager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El reloj de escucha, que es de donde sale `play_events.playedMs`.
 *
 * Lo que estos tests protegen es la diferencia entre **tiempo escuchado** y **posición del
 * reproductor**, que es el bug que motivó la clase: hasta el 30 ago 2026 se guardaba la posición, y
 * con eso saltar una canción arrastrando la barra al final la registraba entera. Ese fallo es
 * SILENCIOSO —el número sigue saliendo, solo que es otro— y el único sitio donde se nota es una
 * pantalla de estadísticas que nadie puede verificar a ojo.
 */
class ListeningClockTest {

    /** Reloj manual: la clase recibe la fuente de tiempo justo para poder hacer esto. */
    private var now = 1_000L
    private val clock = ListeningClock { now }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun `acumula solo lo que sono`() {
        clock.start("a")
        advance(30_000)
        assertEquals(30_000L, clock.of("a"))
    }

    @Test
    fun `un seek no suma nada`() {
        // EL caso que motivó la clase: el usuario arrastra la barra hasta el final para saltar la
        // canción. La POSICIÓN queda en la duración entera; lo escuchado son cinco segundos.
        clock.start("a")
        advance(5_000)
        // Un seek no toca el reloj — por eso no hay ninguna llamada que hacer aquí, y es justo lo
        // que hace que no haya que detectarlo ni distinguir adelante de atrás.
        assertEquals(5_000L, clock.of("a"))
    }

    @Test
    fun `el tiempo en pausa no cuenta`() {
        clock.start("a")
        advance(10_000)
        clock.pause()
        advance(60_000) // pausado: una hora aquí no cambiaría nada
        assertEquals(10_000L, clock.of("a"))
        clock.start("a")
        advance(5_000)
        assertEquals(15_000L, clock.of("a"))
    }

    @Test
    fun `pause es idempotente`() {
        // `updatePlaybackState` la llama en cada transición que no sea PLAYING (PAUSED, BUFFERING,
        // IDLE llegan seguidas), así que no puede sumar dos veces el mismo tramo.
        clock.start("a")
        advance(10_000)
        clock.pause()
        clock.pause()
        clock.pause()
        assertEquals(10_000L, clock.of("a"))
    }

    @Test
    fun `start es idempotente mientras suene lo mismo`() {
        clock.start("a")
        advance(10_000)
        clock.start("a") // no reabre el tramo ni reinicia nada
        advance(5_000)
        assertEquals(15_000L, clock.of("a"))
    }

    @Test
    fun `el tiempo de una cancion no se le atribuye a la siguiente`() {
        clock.start("a")
        advance(200_000)
        // Auto-avance: el player nunca dejó de sonar, así que no llega ninguna pausa.
        clock.restart("b", playing = true)
        advance(3_000)
        assertEquals(3_000L, clock.of("b"))
        assertEquals(0L, clock.of("a"))
    }

    @Test
    fun `restart con el player parado no empieza a contar`() {
        clock.start("a")
        advance(10_000)
        clock.pause()
        clock.restart("b", playing = false)
        advance(60_000)
        assertEquals(0L, clock.of("b"))
        clock.start("b")
        advance(4_000)
        assertEquals(4_000L, clock.of("b"))
    }

    @Test
    fun `rebobinar al inicio empieza una escucha nueva`() {
        // Es lo que hace `onPositionDiscontinuity` al aterrizar dentro del primer segundo: la
        // segunda escucha no puede arrancar con lo de la primera ya sumado, o se contaría de
        // inmediato al pasar el umbral sin haber sonado.
        clock.start("a")
        advance(200_000)
        clock.restart("a", playing = true)
        advance(1_000)
        assertEquals(1_000L, clock.of("a"))
    }

    @Test
    fun `un start con el id nuevo no borra lo que falta contar de la anterior`() {
        // El orden de los callbacks es el riesgo: la escucha de la canción que SALE se cuenta desde
        // `onPositionDiscontinuity`, y `updatePlaybackState()` —que llama a `start`— corre
        // incondicionalmente en `onPlaybackStateChanged`. Si un evento de estado llega con el id
        // nuevo antes de que la discontinuidad lea el viejo, borrarlo aquí haría que la canción
        // dejara de contar como escuchada. Solo `restart` reinicia.
        clock.start("a")
        advance(200_000)
        clock.start("b") // llega "antes de tiempo": no debe tocar nada de "a"
        assertEquals(200_000L, clock.of("a"))
        // Y cuando por fin llega la transición, "b" empieza de cero.
        clock.restart("b", playing = true)
        advance(1_000)
        assertEquals(1_000L, clock.of("b"))
    }

    @Test
    fun `adopta la cancion si no habia ninguna`() {
        // Arranque en frío o un camino que no pase por `restart`: sin esto el reloj no arrancaría
        // nunca y ninguna escucha se contaría.
        clock.start("a")
        advance(4_000)
        assertEquals(4_000L, clock.of("a"))
    }

    @Test
    fun `preguntar por una cancion que nunca sono da cero`() {
        clock.start("a")
        advance(10_000)
        assertEquals(0L, clock.of("otra"))
    }

    @Test
    fun `sin haber arrancado nunca da cero`() {
        assertEquals(0L, clock.of("a"))
    }

    @Test
    fun `start con id nulo no abre nada`() {
        // `currentMediaItem?.mediaId` puede ser null (player sin item); no debe romper ni empezar a
        // contar contra la canción anterior.
        clock.start("a")
        advance(10_000)
        clock.pause()
        clock.start(null)
        advance(60_000)
        assertEquals(10_000L, clock.of("a"))
    }
}
