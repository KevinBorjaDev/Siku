package com.qhana.siku.data.util.tags

import com.qhana.siku.data.util.LrcSyntax

/**
 * Lectura de tags SIN traerse el archivo completo.
 *
 * La idea: en todos los contenedores de audio de uso común la metadata (título, artista, álbum,
 * género y la carátula) vive en una cabecera al principio del archivo, delante del audio, que es
 * el 99% de los bytes. Pidiendo unos cientos de KB por HTTP `Range` se obtiene TODO lo que la
 * biblioteca necesita mostrar, a un coste ~200 veces menor que descargar la canción.
 *
 * Medido sobre una biblioteca FLAC real de 765 archivos: el audio empieza de media en el KB 140,
 * y un fragmento de 256 KB cubre el 93% de los archivos (el resto son portadas de varios MB, que
 * se completan con una segunda petición del rango exacto — ver [TagFragment.pictureRange]).
 *
 * Cada implementación conoce UN contenedor. Si el formato no se reconoce o los tags no están donde
 * se esperaba, se devuelve `null` y el llamador cae al camino universal
 * (`AudioFileAnalyzer.analyzeUrl`) o simplemente espera a la descarga: nunca se degrada nada.
 */
interface PartialTagReader {

    /** ¿Este lector entiende esa extensión? (en minúsculas, sin punto). */
    fun supports(extension: String): Boolean

    /**
     * Cuántos bytes iniciales pedir para tener una probabilidad alta de leer todo de una vez.
     * Es una petición, no una garantía: si la carátula no cabe, [read] lo indica en
     * [TagFragment.pictureRange] y el llamador decide si pide el resto.
     */
    val preferredFragmentBytes: Int get() = DEFAULT_FRAGMENT_BYTES

    /**
     * Lee lo que pueda del [fragment] (los primeros bytes del archivo). Devuelve `null` si el
     * formato no cuadra; un [TagFragment] con campos vacíos es válido y significa "leí la
     * cabecera y no había tags".
     */
    fun read(fragment: ByteArray): TagFragment?

    /**
     * Convierte en imagen los bytes que trajo el rango de [TagFragment.pictureRange].
     *
     * Ese rango apunta al BLOQUE del contenedor —no a la imagen—, porque los offsets internos que
     * lo permitirían acotar viven justamente en la parte que no cabía en el fragmento. Quien lo
     * pida tiene que devolvérselo al MISMO lector que lo produjo para que le quite la envoltura:
     * escribir esos bytes tal cual como `.jpg` da un archivo indecodificable.
     *
     * Devuelve `null` si el bloque no se puede interpretar (rango truncado, datos corruptos), y el
     * llamador lo trata como "no se pudo traer" — o sea, se reintenta más adelante en vez de
     * persistir basura.
     *
     * Default: identidad, para contenedores cuyo rango ya sean los bytes de la imagen.
     */
    fun decodePictureRange(bytes: ByteArray): ByteArray? = bytes

    companion object {
        /**
         * 256 KB: en la biblioteca de referencia cubre el 93% de los archivos de una sola
         * petición, y es ~1% de lo que pesa una canción FLAC típica (28 MB). Subirlo a 512 KB
         * solo ganaba 5 puntos de cobertura al doble de coste, y esos casos se resuelven con el
         * rango exacto de la carátula, que sale gratis de la propia cabecera.
         */
        const val DEFAULT_FRAGMENT_BYTES = 256 * 1024
    }
}

/**
 * Lo que se pudo leer de la cabecera.
 *
 * @param pictureRange dónde está la carátula si NO cabía en el fragmento: (offset, longitud) para
 *        una segunda petición exacta. `null` si ya vino en [pictureData] o si no hay portada.
 */
data class TagFragment(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    /** Número de pista; 0 = la cabecera no lo declara (ver [parseTrackNumber]). */
    val trackNumber: Int = 0,
    /** Año de publicación; 0 = la cabecera no lo declara (ver [parseYear]). */
    val year: Int = 0,
    /** Duración si la cabecera la declara (FLAC sí, en STREAMINFO; ID3 no). 0 = desconocida. */
    val durationMs: Long = 0L,
    /**
     * Letra embebida, tal cual está en el archivo: puede venir en formato LRC (con marcas de
     * tiempo) o como texto plano. Quien la consuma decide — [ParseLyricsUseCase] ya distingue.
     */
    val lyrics: String? = null,
    val pictureData: ByteArray? = null,
    val pictureRange: LongRange? = null
) {
    val hasText: Boolean get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()

    // equals/hashCode a mano: data class + ByteArray compara por referencia y hace ruido en tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagFragment) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && genre == other.genre &&
            trackNumber == other.trackNumber && year == other.year &&
            lyrics == other.lyrics && pictureRange == other.pictureRange &&
            (pictureData?.contentEquals(other.pictureData) ?: (other.pictureData == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + (lyrics?.hashCode() ?: 0)
        result = 31 * result + (pictureRange?.hashCode() ?: 0)
        result = 31 * result + (pictureData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Elige la mejor letra entre las claves que un mismo archivo puede traer (`LYRICS`,
 * `UNSYNCEDLYRICS`, …).
 *
 * Gana la MÁS sincronizada, y eso son TRES escalones y no dos: tiempos por palabra > tiempos por
 * verso > texto plano. El escalón de arriba es lo que hace posible el karaoke, y hace falta
 * ordenarlos porque quien trae una letra palabra a palabra suele traer TAMBIÉN la misma por líneas
 * en otra clave del mismo archivo — sin ordenar por riqueza, tener karaoke o no dependía de cuál
 * hubiera escrito primero su tagger. A igualdad, la primera de la lista.
 *
 * La sintaxis la conoce [LrcSyntax] y solo él: aquí vivía una CUARTA copia del regex de la marca de
 * tiempo, que además no sabía nada de las marcas de palabra.
 */
internal fun pickBestLyrics(vararg candidates: String?): String? =
    candidates.filterNotNull()
        .filter { it.isNotBlank() }
        .maxByOrNull { it.syncRichness() }

/** Cuánta sincronización trae la letra. Es el criterio de [pickBestLyrics] y no significa nada más. */
private fun String.syncRichness(): Int = when {
    LrcSyntax.hasWordTiming(this) -> RICHNESS_WORD_TIMED
    LrcSyntax.hasLineTiming(this) -> RICHNESS_LINE_TIMED
    else -> RICHNESS_PLAIN
}

private const val RICHNESS_PLAIN = 0
private const val RICHNESS_LINE_TIMED = 1
private const val RICHNESS_WORD_TIMED = 2

/** Registro de lectores: rutea por extensión. Sin coincidencia, `null` (el llamador hace fallback). */
class PartialTagReaders(private val readers: List<PartialTagReader>) {
    fun forExtension(extension: String): PartialTagReader? {
        val ext = extension.lowercase().removePrefix(".")
        return readers.firstOrNull { it.supports(ext) }
    }
}
