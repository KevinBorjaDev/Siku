package com.qhana.siku.data.model

/**
 * A qué se acotan unas estadísticas: un artista, un álbum o un género.
 *
 * Existe porque las tres pantallas de estadísticas de una entidad **son la misma pantalla con otro
 * `WHERE`**. Lo único que de verdad cambia entre ellas es (1) qué columna filtra, (2) qué imagen la
 * identifica y (3) qué sub-entidad tiene debajo — y los tres se derivan de aquí, así que no hay
 * forma de añadir un ámbito nuevo y olvidarse de una rama: los `when` son exhaustivos.
 *
 * Es un sealed interface y no un enum + nombre suelto por [Genre.partialMatch]: el ajuste "incluir
 * géneros compuestos" forma parte de QUÉ CONJUNTO de canciones es este género, así que viaja con el
 * ámbito y no como un parámetro extra que los otros dos tendrían que ignorar.
 */
sealed interface StatsScope {
    /** El valor del tag, tal cual está en `songs`. */
    val name: String

    /**
     * Cómo se agrupan los artistas en la app (v29). Lo llevan los TRES ámbitos y no solo el de
     * artista: los artistas de un género y los de un álbum también se cuentan por créditos, y el
     * número tiene que ser el mismo que enseña la biblioteca. Viaja con el ámbito por la misma razón
     * que [Genre.partialMatch]: decide qué conjunto se cuenta.
     */
    val artistRole: ArtistCreditRole

    data class Artist(override val name: String, override val artistRole: ArtistCreditRole) : StatsScope

    /**
     * @param albumArtist la otra mitad de la identidad del álbum (v29): `""` = el disco sin ese tag,
     *   `null` = por nombre, de cualquier artista del álbum (lo que abre quien solo sabe el nombre).
     */
    data class Album(
        override val name: String,
        val albumArtist: String?,
        override val artistRole: ArtistCreditRole
    ) : StatsScope

    /**
     * @param partialMatch el ajuste de la app: con él, "Rock" incluye "Rock/Metal" y "Hard Rock".
     *   Tiene que seguir al detalle de género — dos vistas del mismo género que cuentan conjuntos
     *   distintos serían dos verdades.
     */
    data class Genre(
        override val name: String,
        val partialMatch: Boolean,
        override val artistRole: ArtistCreditRole
    ) : StatsScope
}

/**
 * La sub-entidad que se lista debajo de cada ámbito: de un artista interesan sus ÁLBUMES y de un
 * género sus ARTISTAS.
 *
 * **Un álbum no tiene ninguna** ([StatsScopeChild.NONE]) y eso es la respuesta, no un hueco: por
 * debajo de un disco solo hay canciones, y ésas ya son la lista principal de la pantalla.
 */
enum class StatsScopeChild { ALBUMS, ARTISTS, NONE }

/** Ver [StatsScopeChild]. */
val StatsScope.child: StatsScopeChild
    get() = when (this) {
        is StatsScope.Artist -> StatsScopeChild.ALBUMS
        is StatsScope.Album -> StatsScopeChild.NONE
        is StatsScope.Genre -> StatsScopeChild.ARTISTS
    }

/**
 * La segunda magnitud del resumen: además de cuántas CANCIONES distintas sonaron, de cuántos
 * álbumes o de cuántos artistas salieron.
 *
 * En un álbum es ARTISTAS y no "nada", al revés que [child]: en una recopilación ese número dice
 * algo, y en un disco normal vale 1 — la pantalla se lo calla en ese caso en vez de no calcularlo,
 * porque cuál de los dos casos es solo se sabe con el número en la mano.
 */
val StatsScope.secondaryCount: StatsScopeChild
    get() = when (this) {
        is StatsScope.Artist -> StatsScopeChild.ALBUMS
        is StatsScope.Album -> StatsScopeChild.ARTISTS
        is StatsScope.Genre -> StatsScopeChild.ARTISTS
    }

/**
 * El TIPO de ámbito, sin su nombre. Existe para poder viajar por una ruta de navegación: un
 * [StatsScope] completo no puede, porque `Genre` lleva dentro una preferencia VIVA de la app
 * (`partialMatch`) y meterla en la ruta la congelaría en el valor que tuviera al navegar.
 */
enum class StatsScopeKind { ARTIST, ALBUM, GENRE }

/** Ver [StatsScopeKind]. */
val StatsScope.kind: StatsScopeKind
    get() = when (this) {
        is StatsScope.Artist -> StatsScopeKind.ARTIST
        is StatsScope.Album -> StatsScopeKind.ALBUM
        is StatsScope.Genre -> StatsScopeKind.GENRE
    }

/**
 * Rehace el ámbito completo. [genrePartialMatch] solo lo usa [StatsScopeKind.GENRE]; los otros dos
 * lo ignoran, y por eso es un parámetro con valor y no un `when` en el sitio de llamada — la regla
 * de qué ámbito mira esa preferencia vive aquí.
 */
fun StatsScopeKind.scopeFor(
    name: String,
    albumArtist: String?,
    genrePartialMatch: Boolean,
    artistRole: ArtistCreditRole
): StatsScope = when (this) {
    StatsScopeKind.ARTIST -> StatsScope.Artist(name, artistRole)
    StatsScopeKind.ALBUM -> StatsScope.Album(name, albumArtist, artistRole)
    StatsScopeKind.GENRE -> StatsScope.Genre(name, genrePartialMatch, artistRole)
}
