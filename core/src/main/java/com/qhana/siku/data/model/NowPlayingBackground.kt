package com.qhana.siku.data.model

/**
 * Fondo del reproductor grande. Es UNA elección entre tres, no dos interruptores: [SOLID] y [BLUR]
 * a la vez no significa nada, y con dos booleanos ese estado imposible existiría y habría que
 * sanearlo en cada lector.
 *
 * Sustituye a la preferencia booleana `now_playing_solid_bg`, que se sigue LEYENDO para migrar la
 * elección de quien ya tenía el fondo sólido puesto (ver `MusicPreferences.readNowPlayingBackground`).
 */
enum class NowPlayingBackground {
    /** Degradado vertical `secondaryContainer → surface`, teñido por el seed del álbum. El default. */
    GRADIENT,

    /** Color tonal sólido (`surfaceContainer`), también teñido por el seed. */
    SOLID,

    /**
     * Carátula ampliada y desenfocada, con un velo tonal encima.
     *
     * El velo NO es decoración: toda la pantalla deriva su contraste de un color de fondo CONOCIDO
     * (el chip de origen del `topBackgroundColor`, el glifo del play de `maxContrastOn`), y una
     * imagen no da uno — da uno por píxel. El velo devuelve esa base, y por eso el modo se dibuja
     * como base sólida + imagen + velo, nunca como imagen suelta.
     */
    BLUR
}
