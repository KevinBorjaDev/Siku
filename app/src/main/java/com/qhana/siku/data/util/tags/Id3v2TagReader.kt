package com.qhana.siku.data.util.tags

import javax.inject.Inject

/**
 * MP3 (y cualquier archivo con ID3v2 delante): `ID3` + versión + flags + tamaño, y luego frames
 * `TIT2` (título), `TPE1` (artista), `TALB` (álbum), `TPE2` (artista del álbum), `TCON` (género),
 * `USLT` (letra) y `APIC` (carátula).
 *
 * Dos trampas propias del formato, ambas contempladas aquí:
 * - **Syncsafe**: el tamaño del tag usa 7 bits por byte (el bit alto siempre 0, para que no se
 *   confunda con una cabecera de frame MPEG). Leerlo como un uint32 normal da un tamaño enorme.
 * - **ID3v2.2**: frames de 3 letras (`TT2`, `TP1`, `PIC`) y cabecera de 6 bytes en vez de 10.
 *   Es viejo pero sigue apareciendo en bibliotecas rippeadas hace 20 años.
 *
 * ID3v1 (128 bytes al FINAL del archivo) no se soporta: exigiría una segunda petición al final
 * para 30 caracteres truncados y sin carátula. Esos archivos caen al camino universal.
 */
class Id3v2TagReader @Inject constructor() : PartialTagReader {

    override fun supports(extension: String): Boolean = extension in SUPPORTED

    override fun read(fragment: ByteArray): TagFragment? {
        if (fragment.size < HEADER_BYTES) return null
        if (fragment[0] != 'I'.code.toByte() || fragment[1] != 'D'.code.toByte() ||
            fragment[2] != '3'.code.toByte()
        ) return null

        val majorVersion = fragment[3].toInt() and 0xFF
        val flags = fragment[FLAGS_OFFSET].toInt() and 0xFF
        val tagSize = readSyncsafe(fragment, 6)
        if (tagSize <= 0) return null

        val isV22 = majorVersion == 2

        // Unsynchronisation: el tag viene con un 0x00 metido detrás de cada 0xFF y habría que
        // des-escaparlo ENTERO antes de leer un solo frame. En vez de devolver campos corruptos en
        // silencio, se declara "no reconocido" y el llamador cae al camino universal, que sí lo
        // entiende — es exactamente para lo que existe ese contrato en [PartialTagReader].
        if (flags and FLAG_UNSYNCHRONISATION != 0) return null
        // El mismo bit significa otra cosa en v2.2: compresión, sin esquema definido por la spec
        // (que manda ignorar el tag). Tampoco es nuestro.
        if (isV22 && flags and FLAG_EXTENDED_OR_COMPRESSED != 0) return null

        val frameHeaderBytes = if (isV22) V22_FRAME_HEADER_BYTES else FRAME_HEADER_BYTES
        val idLength = if (isV22) 3 else 4
        // El tag puede exceder el fragmento (carátula grande): se lee hasta donde haya bytes.
        val tagEnd = minOf(HEADER_BYTES + tagSize, fragment.size)

        // El extended header se interpone entre la cabecera y el primer frame. Sin saltarlo, el
        // primer "id de frame" son en realidad sus bytes: sale basura, el bucle corta al momento y
        // el archivo se queda sin tags sin que nadie se entere.
        var pos = HEADER_BYTES
        if (flags and FLAG_EXTENDED_OR_COMPRESSED != 0) {
            pos += extendedHeaderBytes(fragment, majorVersion) ?: return null
        }
        val text = HashMap<String, String>()
        // Los frames que pueden llevar VARIOS valores (ver [decodeValues]) se guardan aparte, en lista.
        var artistValues: List<String> = emptyList()
        var albumArtistValues: List<String> = emptyList()
        var dedicatedArtists: List<String> = emptyList()
        var dedicatedAlbumArtists: List<String> = emptyList()
        var pictureData: ByteArray? = null

        while (pos + frameHeaderBytes <= tagEnd) {
            val id = String(fragment, pos, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break // zona de padding: se acabaron los frames
            val size = if (isV22) {
                readUInt24BE(fragment, pos + 3)
            } else if (majorVersion >= 4) {
                // v2.4 también usa syncsafe para el tamaño de cada frame; v2.3 no.
                readSyncsafe(fragment, pos + 4)
            } else {
                readUInt32BE(fragment, pos + 4)
            }
            val bodyStart = pos + frameHeaderBytes
            if (size <= 0 || bodyStart + size > tagEnd) break

            when (id) {
                "TIT2", "TT2" -> text["TITLE"] = decodeText(fragment, bodyStart, size)
                "TPE1", "TP1" -> artistValues = decodeValues(fragment, bodyStart, size, majorVersion)
                "TALB", "TAL" -> text["ALBUM"] = decodeText(fragment, bodyStart, size)
                "TPE2", "TP2" -> albumArtistValues = decodeValues(fragment, bodyStart, size, majorVersion)
                // Texto libre con descripción: es donde Picard escribe ARTISTS (los créditos uno a uno).
                "TXXX", "TXX" -> extractUserText(fragment, bodyStart, size)?.let { (description, values) ->
                    when (description.uppercase()) {
                        "ARTISTS" -> dedicatedArtists = values
                        "ALBUMARTISTS", "ALBUM ARTISTS" -> dedicatedAlbumArtists = values
                        else -> Unit
                    }
                }
                "TCON", "TCO" -> text["GENRE"] = normalizeGenre(decodeText(fragment, bodyStart, size))
                "TRCK", "TRK" -> text["TRACK"] = decodeText(fragment, bodyStart, size)
                // El año cambió de frame entre versiones: `TYER` (v2.3, 4 dígitos) y `TDRC`
                // (v2.4, timestamp ISO). La PRIMERA que aparezca gana — un archivo convertido de
                // v2.3 a v2.4 puede arrastrar las dos y la segunda no aporta nada nuevo.
                "TDRC", "TYER", "TYE" -> if ("YEAR" !in text) {
                    text["YEAR"] = decodeText(fragment, bodyStart, size)
                }
                // Un archivo puede traer un USLT por idioma; nos quedamos con el primero.
                "USLT", "ULT" -> if ("LYRICS" !in text) {
                    extractLyrics(fragment, bodyStart, size)?.let { text["LYRICS"] = it }
                }
                "APIC", "PIC" -> if (pictureData == null) {
                    pictureData = extractPicture(fragment, bodyStart, size, isV22)
                }
            }
            pos = bodyStart + size
        }

        return TagFragment(
            title = text["TITLE"],
            // Lo que se MUESTRA: todos los intérpretes del frame, no solo el primero.
            artist = artistValues.takeIf { it.isNotEmpty() }?.joinToString(", "),
            album = text["ALBUM"],
            albumArtists = dedicatedAlbumArtists.ifEmpty { albumArtistValues },
            artists = artistCreditsFrom(dedicatedArtists, artistValues),
            genre = text["GENRE"],
            trackNumber = parseTrackNumber(text["TRACK"]),
            year = parseYear(text["YEAR"]),
            lyrics = pickBestLyrics(text["LYRICS"]),
            pictureData = pictureData,
            // El offset del APIC dentro del tag no es estable si el frame quedó cortado a medias:
            // no se ofrece rango exacto. Sin carátula en el fragmento, el álbum la conseguirá de
            // otra de sus canciones o al descargarse.
            pictureRange = null
        )
    }

    /**
     * Primer byte = codificación: 0 ISO-8859-1, 1 UTF-16 con BOM, 2 UTF-16BE, 3 UTF-8.
     * Se recorta en el primer NUL: muchos taggers rellenan el resto del frame.
     */
    private fun decodeText(buf: ByteArray, start: Int, size: Int): String {
        if (size <= 1) return ""
        val charset = charsetFor(buf[start].toInt())
        return String(buf, start + 1, size - 1, charset).substringBefore('\u0000').trim()
    }

    /**
     * Como [decodeText], pero conservando TODOS los valores del frame.
     *
     * **Solo en v2.4**, que es la versión que define el NUL como separador de valores. En v2.3 un NUL
     * es relleno (muchos taggers lo dejan al final), y su "separador" oficial para intérpretes es la
     * barra `/` —que parte "AC/DC"—, así que ahí se toma un único valor y partirlo queda en manos del
     * ajuste de separadores del usuario.
     *
     * En UTF-16 cada valor puede traer su propio BOM; el decodificador solo consume el primero, y los
     * siguientes llegan como U+FEFF dentro del texto — de ahí el `replace`.
     */
    private fun decodeValues(buf: ByteArray, start: Int, size: Int, majorVersion: Int): List<String> {
        if (size <= 1) return emptyList()
        val raw = String(buf, start + 1, size - 1, charsetFor(buf[start].toInt()))
        return splitValues(raw, multiValue = majorVersion >= 4)
    }

    private fun splitValues(raw: String, multiValue: Boolean): List<String> =
        (if (multiValue) raw.split('\u0000') else listOf(raw.substringBefore('\u0000')))
            .map { it.replace("\uFEFF", "").trim() }
            .filter { it.isNotEmpty() }

    /**
     * `TXXX`: codificación, una descripción terminada en NUL (con el ancho de la codificación) y el
     * valor, que en v2.4 también puede ser multivalor. `null` si el frame está truncado.
     */
    private fun extractUserText(buf: ByteArray, start: Int, size: Int): Pair<String, List<String>>? {
        val end = start + size
        if (size <= 1) return null
        val encoding = buf[start].toInt()
        val wide = encoding == ENCODING_UTF16_BOM || encoding == ENCODING_UTF16_BE
        val descriptionStart = start + 1
        val valueStart = skipPastNul(buf, descriptionStart, end, wide)
        if (valueStart > end) return null
        val nulWidth = if (wide) 2 else 1
        val charset = charsetFor(encoding)
        val description = String(buf, descriptionStart, (valueStart - nulWidth - descriptionStart).coerceAtLeast(0), charset)
            .replace("\uFEFF", "").trim()
        val values = if (valueStart >= end) emptyList()
        else splitValues(String(buf, valueStart, end - valueStart, charset), multiValue = true)
        return description to values
    }

    private fun charsetFor(encoding: Int) = when (encoding) {
        ENCODING_UTF16_BOM -> Charsets.UTF_16
        ENCODING_UTF16_BE -> Charsets.UTF_16BE
        ENCODING_UTF8 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /**
     * `USLT`: codificación, idioma (3 bytes), un descriptor terminado en NUL y, desde ahí, la
     * letra hasta el final del frame.
     *
     * A diferencia de los frames de texto normales, aquí NO se puede cortar en el primer NUL: la
     * letra es multilínea y ese corte la dejaría en el primer renglón. Solo se limpian los bordes.
     */
    private fun extractLyrics(buf: ByteArray, start: Int, size: Int): String? {
        val end = start + size
        var p = start
        if (p >= end) return null
        val encoding = buf[p].toInt(); p++
        p += LANGUAGE_BYTES
        if (p >= end) return null
        val wide = encoding == ENCODING_UTF16_BOM || encoding == ENCODING_UTF16_BE
        p = skipPastNul(buf, p, end, wide)
        if (p >= end) return null
        return String(buf, p, end - p, charsetFor(encoding)).trim().takeIf { it.isNotBlank() }
    }

    /** `(17)` o `(17)Rock` son códigos ID3v1 heredados; nos quedamos con el texto si lo hay. */
    private fun normalizeGenre(raw: String): String {
        if (!raw.startsWith("(")) return raw
        val afterCode = raw.substringAfter(')', "").trim()
        return afterCode.ifBlank { raw }
    }

    /**
     * APIC: codificación, mime (terminado en NUL; en v2.2 son 3 bytes fijos), tipo de imagen,
     * descripción (terminada en NUL con el ancho de la codificación) y los bytes de la imagen.
     */
    private fun extractPicture(buf: ByteArray, start: Int, size: Int, isV22: Boolean): ByteArray? {
        val end = start + size
        var p = start
        val encoding = buf[p].toInt(); p++
        p = if (isV22) p + 3 else skipPastNul(buf, p, end, wide = false)
        if (p >= end) return null
        p++ // tipo de imagen (portada, contraportada, …)
        p = skipPastNul(buf, p, end, wide = encoding == ENCODING_UTF16_BOM || encoding == ENCODING_UTF16_BE)
        if (p >= end) return null
        return buf.copyOfRange(p, end)
    }

    /**
     * Bytes que ocupa el extended header, para saltarlo entero. `null` = no se puede saber (el
     * fragmento se corta antes de su campo de tamaño, o el tamaño es absurdo), y entonces el
     * llamador declara el tag no reconocido en vez de leer frames desde una posición inventada.
     *
     * Las dos versiones NO cuentan lo mismo, y confundirlas desplaza la lectura por unos pocos
     * bytes —lo peor que puede pasar, porque el resultado parece un tag válido con basura dentro:
     * - **v2.3**: campo normal (no syncsafe) que mide el extended header SIN contarse a sí mismo.
     * - **v2.4**: campo syncsafe que SÍ se cuenta, así que el total es el propio valor.
     */
    private fun extendedHeaderBytes(buf: ByteArray, majorVersion: Int): Int? {
        val sizeAt = HEADER_BYTES
        if (sizeAt + EXTENDED_SIZE_BYTES > buf.size) return null
        val declared = if (majorVersion >= 4) {
            readSyncsafe(buf, sizeAt)
        } else {
            readUInt32BE(buf, sizeAt)
        }
        if (declared <= 0) return null
        val total = if (majorVersion >= 4) declared else declared + EXTENDED_SIZE_BYTES
        // Un tamaño mayor que lo que hay leído no es recuperable: los frames caen fuera del trozo.
        if (total > buf.size - HEADER_BYTES) return null
        return total
    }

    private fun skipPastNul(buf: ByteArray, from: Int, end: Int, wide: Boolean): Int {
        var p = from
        if (wide) {
            while (p + 1 < end && !(buf[p] == 0.toByte() && buf[p + 1] == 0.toByte())) p += 2
            return p + 2
        }
        while (p < end && buf[p] != 0.toByte()) p++
        return p + 1
    }

    /** 7 bits por byte: el bit alto de cada uno es siempre 0 (ver KDoc de la clase). */
    private fun readSyncsafe(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0x7F) shl 21) or
            ((buf[at + 1].toInt() and 0x7F) shl 14) or
            ((buf[at + 2].toInt() and 0x7F) shl 7) or
            (buf[at + 3].toInt() and 0x7F)

    private fun readUInt24BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 16) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            (buf[at + 2].toInt() and 0xFF)

    private fun readUInt32BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)

    private companion object {
        val SUPPORTED = setOf("mp3", "aac")
        const val HEADER_BYTES = 10
        const val FRAME_HEADER_BYTES = 10
        const val V22_FRAME_HEADER_BYTES = 6
        /** Códigos de codificación de texto del primer byte de un frame (spec ID3v2). */
        const val ENCODING_UTF16_BOM = 1
        const val ENCODING_UTF16_BE = 2
        const val ENCODING_UTF8 = 3
        /** `USLT`/`SYLT` llevan el idioma ISO-639-2 en 3 bytes, entre codificación y descriptor. */
        const val LANGUAGE_BYTES = 3
        /** Byte de flags de la cabecera del tag: `ID3` + versión (2) + flags (1) + tamaño (4). */
        const val FLAGS_OFFSET = 5
        const val FLAG_UNSYNCHRONISATION = 0x80
        /** Extended header en v2.3/v2.4; en v2.2 el mismo bit significa compresión. */
        const val FLAG_EXTENDED_OR_COMPRESSED = 0x40
        /** Campo de tamaño que abre el extended header, en las dos versiones que lo tienen. */
        const val EXTENDED_SIZE_BYTES = 4
    }
}
