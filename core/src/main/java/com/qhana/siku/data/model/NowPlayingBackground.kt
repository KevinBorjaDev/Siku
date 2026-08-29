package com.qhana.siku.data.model

/**
 * Fondo del reproductor grande. Es UNA elección y no un interruptor: con un booleano, añadir un
 * tercer modo obligaría a inventar un estado imposible y a sanearlo en cada lector.
 *
 * Sustituye a la preferencia booleana `now_playing_solid_bg`, que se sigue LEYENDO para migrar la
 * elección de quien ya tenía el fondo sólido puesto (ver `MusicPreferences.readNowPlayingBackground`).
 *
 * Hubo un tercer modo, `BLUR` (carátula ampliada y desenfocada), retirado el 29 ago 2026 por
 * problemas de contraste: una imagen no da un color de fondo ÚNICO, y toda la pantalla deriva su
 * contraste de uno. El velo tonal que lo compensaba nunca acabó de resolverlo. Un nombre
 * persistido desconocido cae al degradado, así que quien lo tuviera puesto no queda en un estado
 * inválido.
 */
enum class NowPlayingBackground {
    /** Degradado vertical `secondaryContainer → surface`, teñido por el seed del álbum. El default. */
    GRADIENT,

    /** Color tonal sólido (`surfaceContainer`), también teñido por el seed. */
    SOLID
}
