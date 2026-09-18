package com.qhana.siku.data.model

/**
 * Separador de los tags MULTIVALOR guardados en una sola columna de `songs` (`albumArtist`,
 * `artistCredits`): U+001F, el UNIT SEPARATOR de ASCII.
 *
 * No es `\u0000` —que es lo que usa el propio ID3v2.4— porque un NUL dentro de un TEXT de SQLite se
 * trunca al leerlo por el cursor de Android. Y no es ningún carácter imprimible porque cualquiera de
 * ellos (`;`, `/`, `,`) aparece dentro de nombres de artista reales: ése es justamente el problema que
 * los tags multivalor resuelven y que un separador visible volvería a crear.
 */
const val MULTI_VALUE_SEPARATOR = '\u001F'

/**
 * Codifica una lista de valores para su columna. `null` si no queda ningún valor útil — quien llama
 * decide si eso significa "no leído" (NULL) o "leído y vacío" (`""`), porque aquí no se sabe.
 */
fun encodeMultiValue(values: List<String>): String? =
    values.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(MULTI_VALUE_SEPARATOR.toString())

/** Inversa de [encodeMultiValue]. `null` y `""` dan la lista vacía. */
fun decodeMultiValue(raw: String?): List<String> =
    if (raw.isNullOrEmpty()) emptyList()
    else raw.split(MULTI_VALUE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

/** Un valor multivalor para mostrarlo: los valores separados por coma. */
fun displayMultiValue(raw: String?): String = decodeMultiValue(raw).joinToString(", ")

/**
 * Con qué criterio se agrupan los artistas en la biblioteca.
 *
 * - [TRACK]: el artista de cada pista (el tag ARTIST, o los valores de ARTISTS). Es lo que la app
 *   hacía siempre, y sigue siendo el default.
 * - [ALBUM]: el artista del ÁLBUM (ALBUMARTIST / TPE2), cayendo al de la pista si el archivo no lo
 *   trae. Un disco lleno de colaboraciones queda bajo un solo artista.
 *
 * Es un enum y no un booleano para que viaje igual que cualquier otra preferencia de la biblioteca,
 * y porque sus dos valores son dos ROLES de la misma tabla (`song_artists.role`): el `ordinal` NO se
 * usa para eso — se usa [role], que es lo que se persiste en la BD.
 */
enum class ArtistCreditRole(val role: Int) {
    TRACK(0),
    ALBUM(1)
}

/**
 * Los separadores que el usuario puede activar para partir un campo de artista en varios.
 *
 * **Es una lista CERRADA y no texto libre**, y es deliberado: un separador escrito a mano con un
 * espacio de más o sin él partiría cosas distintas sin que se viera por qué. Los de palabra
 * ([isWord]) exigen estar separados por espacios, así que `feat.` no corta "Defeat." ni `x` corta
 * "Malcolm X".
 *
 * [pattern] es un fragmento de regex, no el texto a buscar: `feat\.?` acepta "feat" y "feat.".
 */
enum class ArtistSeparator(val label: String, val pattern: String, val isWord: Boolean) {
    SEMICOLON(";", ";", false),
    SLASH("/", "/", false),
    AMPERSAND("&", "&", false),
    COMMA(",", ",", false),
    FEAT("feat.", "feat\\.?", true),
    FT("ft.", "ft\\.?", true),
    FEATURING("featuring", "featuring", true),
    WITH("with", "with", true),
    VS("vs.", "vs\\.?", true),
    X("x", "x", true);

    companion object {
        /**
         * Los que casi nunca forman parte de un nombre real. `&`, `,`, `/` y `x` quedan fuera a
         * propósito: "Simon & Garfunkel", "Earth, Wind & Fire", "AC/DC", "Malcolm X".
         */
        val DEFAULT: Set<ArtistSeparator> = setOf(SEMICOLON, FEAT, FT, FEATURING)
    }
}

/**
 * Configuración de la separación de colaboraciones. [exceptions] son nombres que NUNCA se parten,
 * aunque contengan un separador activo (comparados sin distinguir mayúsculas).
 */
data class ArtistSplitConfig(
    val enabled: Boolean,
    val separators: Set<ArtistSeparator>,
    val exceptions: List<String>
) {
    companion object {
        val DISABLED = ArtistSplitConfig(enabled = false, separators = emptySet(), exceptions = emptyList())
    }
}

/**
 * Quién firma cada canción, en los dos roles que la biblioteca sabe agrupar.
 *
 * **Es la ÚNICA definición** de a qué artistas pertenece una canción: la consume el indexador que
 * mantiene `song_artists` y nada más. Las pantallas no parten nombres por su cuenta — preguntan a esa
 * tabla. Si esto se copiara, la pestaña Artistas y el detalle podrían no estar de acuerdo sobre qué
 * canciones son de quién.
 *
 * Orden de fuentes, de más a menos fiable:
 *  1. los valores MULTIVALOR reales del archivo (`artistCredits`: ARTISTS de Picard, ID3v2.4 con NUL,
 *     claves Vorbis repetidas) — el dato, sin interpretación;
 *  2. el campo de artista de siempre;
 *  3. y encima de cualquiera de los dos, la separación por texto, SOLO si el usuario la activó.
 */
object ArtistCredits {

    /** Artistas de la pista. Nunca vacío: una canción siempre pertenece al menos a su [artist]. */
    fun trackCredits(artist: String, artistCredits: String?, config: ArtistSplitConfig): List<String> {
        val base = decodeMultiValue(artistCredits).ifEmpty { listOf(artist) }
        return base.flatMap { split(it, config) }.distinctIgnoreCase().ifEmpty { listOf(artist) }
    }

    /**
     * Artistas del álbum. Sin tag ALBUMARTIST caen a los de la pista: así, en el modo "artista del
     * álbum", un archivo sin ese tag sigue donde estaba en vez de desaparecer de la pestaña.
     */
    fun albumCredits(albumArtist: String?, trackCredits: List<String>, config: ArtistSplitConfig): List<String> {
        val tagged = decodeMultiValue(albumArtist)
        if (tagged.isEmpty()) return trackCredits
        return tagged.flatMap { split(it, config) }.distinctIgnoreCase().ifEmpty { trackCredits }
    }

    /**
     * Parte UN valor por los separadores activos, respetando las excepciones.
     *
     * Las excepciones se protegen ANTES de partir, sustituyéndolas por un marcador que ningún
     * separador puede casar, y se restauran después: así "Earth, Wind & Fire" sobrevive aunque `,` y
     * `&` estén activos, y sigue sobreviviendo dentro de "Earth, Wind & Fire feat. X".
     */
    fun split(value: String, config: ArtistSplitConfig): List<String> {
        val trimmed = value.trim()
        if (!config.enabled || config.separators.isEmpty() || trimmed.isEmpty()) return listOf(trimmed)

        val protected = mutableListOf<String>()
        var working = trimmed
        for (exception in config.exceptions.map { it.trim() }.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            val regex = Regex(Regex.escape(exception), RegexOption.IGNORE_CASE)
            working = regex.replace(working) { match ->
                protected.add(match.value)
                "$PLACEHOLDER_START${protected.size - 1}$PLACEHOLDER_END"
            }
        }

        val pieces = separatorRegex(config.separators).split(working)
        return pieces.asSequence()
            .map { restore(it, protected) }
            .map { stripUnbalancedClosing(it.trim()) }
            .filter { it.isNotEmpty() }
            .toList()
            .distinctIgnoreCase()
            .ifEmpty { listOf(trimmed) }
    }

    /**
     * Un separador de palabra solo cuenta rodeado de espacios (o abriendo un paréntesis: "A (feat. B)").
     * Los de símbolo cortan donde estén, con los espacios de alrededor absorbidos.
     */
    private fun separatorRegex(separators: Set<ArtistSeparator>): Regex {
        val symbols = separators.filter { !it.isWord }
        val words = separators.filter { it.isWord }
        val alternatives = buildList {
            if (symbols.isNotEmpty()) add("\\s*(?:${symbols.joinToString("|") { it.pattern }})\\s*")
            if (words.isNotEmpty()) add("(?:\\s*[(\\[]\\s*|\\s+)(?:${words.joinToString("|") { it.pattern }})\\s+")
        }
        return Regex(alternatives.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    /** "B)" de "A (feat. B)": el paréntesis que abría quedó en la otra mitad. */
    private fun stripUnbalancedClosing(piece: String): String {
        var result = piece
        while (result.isNotEmpty() && (result.last() == ')' || result.last() == ']')) {
            val open = if (result.last() == ')') '(' else '['
            if (result.count { it == open } >= result.count { it == result.last() }) break
            result = result.dropLast(1).trimEnd()
        }
        return result
    }

    private fun restore(piece: String, protected: List<String>): String =
        if (protected.isEmpty()) piece
        else PLACEHOLDER_REGEX.replace(piece) { match ->
            protected.getOrNull(match.groupValues[1].toInt()) ?: match.value
        }

    private fun List<String>.distinctIgnoreCase(): List<String> = distinctBy { it.lowercase() }

    // Área de uso privado de Unicode: no aparece en un tag real ni la casa ningún separador.
    private const val PLACEHOLDER_START = '\uE000'
    private const val PLACEHOLDER_END = '\uE001'
    private val PLACEHOLDER_REGEX = Regex("$PLACEHOLDER_START(\\d+)$PLACEHOLDER_END")
}
