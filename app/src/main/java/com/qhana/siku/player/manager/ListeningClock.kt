package com.qhana.siku.player.manager

import android.os.SystemClock

/**
 * Cuánto se ESCUCHÓ de verdad de la canción en curso.
 *
 * **Existe porque la posición del reproductor no es tiempo escuchado**, y hasta el 30 ago 2026 se
 * usaba como si lo fuera: `MusicController.maybeRecordPlay` recibía `currentPosition` /
 * `oldPosition.positionMs`, o sea DÓNDE ESTABA LA AGUJA al salir del item. Con eso, arrastrar la
 * barra al final para saltar una canción la registraba entera —superaba el umbral y sumaba su
 * duración completa al log—, y escucharla entera y rebobinar antes de salir registraba casi nada.
 * Daba igual mientras el número solo decidía SI contar la escucha; desde la v28 se guarda en
 * `play_events.playedMs` y es el titular de la pantalla de estadísticas y la base del "% contra el
 * periodo anterior".
 *
 * Mide acumulando los tramos en que el reproductor estuvo sonando, así que **un seek no suma nada
 * por definición**: no hay que detectarlo, ni clasificarlo, ni distinguir un salto adelante de uno
 * atrás.
 *
 * El acumulador va atado al ID de la canción y no suelto ([of] devuelve 0 si le preguntan por otra):
 * los eventos llegan por la capa de sesión (`MediaController`), y aunque `ExoPlayerImpl` notifique
 * la discontinuidad antes que la transición de item, no conviene que el tiempo de una canción pueda
 * atribuirse a la siguiente si ese orden cambiara.
 *
 * **Vive fuera de `MusicController` para poder probarlo**: allí dentro haría falta un `MediaController`
 * simulado para ejercer cuatro transiciones de estado, y esto es justo la lógica cuya exactitud
 * decide lo que el usuario ve en sus estadísticas.
 *
 * No es thread-safe, y no le hace falta: lo conducen los callbacks del player y `updatePlaybackState`,
 * todos en el hilo principal.
 *
 * @param now fuente de tiempo MONÓTONA. Por defecto `elapsedRealtime`, que no la mueve un cambio de
 *   hora del sistema ni el horario de verano — con `currentTimeMillis`, un ajuste de reloj a mitad
 *   de canción inventaría (o borraría) minutos escuchados. Se inyecta para el test.
 */
class ListeningClock(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {

    /** Canción a la que pertenece lo acumulado. */
    private var songId: String? = null

    /** Tramos ya cerrados. */
    private var accumulatedMs = 0L

    /** Arranque del tramo ABIERTO, o `null` si ahora mismo no está sonando. */
    private var openedAt: Long? = null

    /**
     * Empieza a contar para [id]. Idempotente: `MusicController.updatePlaybackState` la llama en
     * CADA transición a PLAYING, y volver a llamarla mientras suena lo mismo no reabre el tramo ni
     * duplica nada.
     *
     * **No borra lo acumulado de otra canción, y eso es load-bearing.** Quien reinicia es [restart],
     * porque es el único que sabe que un item EMPEZÓ; esto solo sabe que el player está sonando. La
     * diferencia importa por el ORDEN de los callbacks: la escucha de la canción que sale se cuenta
     * desde `onPositionDiscontinuity`, y `updatePlaybackState()` corre incondicionalmente en
     * `onPlaybackStateChanged`. Si un evento de estado llegara con el id NUEVO antes de que la
     * discontinuidad contara el viejo, un `start` destructivo habría puesto a cero justo el tiempo
     * que estaba a punto de leerse: la canción dejaría de contar como escuchada, que es una
     * regresión peor que el bug que esta clase arregla, y silenciosa.
     *
     * Adopta [id] si no había ninguna canción (arranque en frío, o un camino que no pase por
     * [restart]); mientras haya otra en curso, no hace nada — lo que llegue después de un cambio de
     * item lo endereza el [restart] de `onMediaItemTransition`, que llega en el mismo lote.
     */
    fun start(id: String?) {
        if (id == null) return
        if (songId == null) songId = id
        if (songId != id) return
        if (openedAt == null) openedAt = now()
    }

    /** Cierra el tramo abierto, si lo hay. Idempotente: llamarlo dos veces no suma dos veces. */
    fun pause() {
        val opened = openedAt ?: return
        accumulatedMs += now() - opened
        openedAt = null
    }

    /**
     * El item empieza DE NUEVO (otro, o el mismo con repeat-one o rebobinado al inicio): su tiempo
     * vuelve a cero.
     *
     * @param playing si el reproductor ya está sonando, para abrir el tramo en el acto. En un
     *   auto-avance no llega ninguna transición de estado después que lo haga —el player nunca dejó
     *   de sonar—, así que sin esto el primer tramo de cada canción de una cola seguida no se
     *   contaría.
     */
    fun restart(id: String?, playing: Boolean) {
        songId = id
        accumulatedMs = 0L
        openedAt = if (id != null && playing) now() else null
    }

    /**
     * Lo escuchado de [id], incluido el tramo aún abierto.
     *
     * Cero si lo acumulado es de otra canción, que es lo que corresponde: si nunca llegó a sonar,
     * nadie la escuchó.
     */
    fun of(id: String): Long {
        if (songId != id) return 0L
        val opened = openedAt ?: return accumulatedMs
        return accumulatedMs + (now() - opened)
    }
}
