package com.qhana.siku.data.util.tags

import javax.inject.Inject

/**
 * FLAC: `fLaC` + una cadena de METADATA_BLOCKs, y solo después el audio.
 *
 * ```
 * fLaC │ STREAMINFO │ VORBIS_COMMENT │ PICTURE │ PADDING │ audio…
 *      └─ 34 B ─────┴─ ~300 B ───────┴ 12KB-3MB┴─ 8 KB ──┘
 * ```
 *
 * Cabecera de bloque = 4 bytes: bit 7 del primero marca "último bloque", los 7 restantes el tipo,
 * y los 3 siguientes la longitud en BIG-endian. Ojo: dentro de VORBIS_COMMENT las longitudes son
 * LITTLE-endian (herencia de Vorbis) — mezclar los dos órdenes es el error clásico de este formato.
 */
class FlacTagReader @Inject constructor() : PartialTagReader {

    override fun supports(extension: String): Boolean = extension == "flac"

    override fun read(fragment: ByteArray): TagFragment? {
        if (fragment.size < 8) return null
        if (fragment[0] != 'f'.code.toByte() || fragment[1] != 'L'.code.toByte() ||
            fragment[2] != 'a'.code.toByte() || fragment[3] != 'C'.code.toByte()
        ) return null

        var pos = 4
        var tags: Map<String, List<String>> = emptyMap()
        var pictureData: ByteArray? = null
        var pictureRange: LongRange? = null
        var durationMs = 0L

        while (pos + BLOCK_HEADER_BYTES <= fragment.size) {
            val flagsAndType = fragment[pos].toInt() and 0xFF
            val isLast = (flagsAndType and 0x80) != 0
            val type = flagsAndType and 0x7F
            val length = readUInt24BE(fragment, pos + 1)
            val bodyStart = pos + BLOCK_HEADER_BYTES

            when (type) {
                TYPE_STREAMINFO ->
                    if (bodyStart + length <= fragment.size) {
                        durationMs = parseDurationMs(fragment, bodyStart)
                    }
                TYPE_VORBIS_COMMENT ->
                    if (bodyStart + length <= fragment.size) {
                        tags = parseVorbisComment(fragment, bodyStart, length)
                    }
                TYPE_PICTURE ->
                    if (bodyStart + length <= fragment.size) {
                        pictureData = extractPictureData(fragment, bodyStart, length)
                    } else {
                        // No cabía en el fragmento: se devuelve dónde está para una segunda
                        // petición EXACTA, en vez de pedir a ciegas un fragmento más grande.
                        // OJO: el rango cubre el BLOQUE entero, no la imagen — quien lo pida
                        // tiene que pasarlo por [decodePictureRange]. Acotar aquí el rango a los
                        // bytes de la imagen es imposible: sus offsets viven en la parte del
                        // bloque que precisamente NO se llegó a leer.
                        pictureRange = bodyStart.toLong() until (bodyStart.toLong() + length)
                    }
            }

            if (isLast) break
            pos = bodyStart + length
            // Un length corrupto podría desbordar el Int y volver atrás: cortamos.
            if (length < 0 || pos <= bodyStart) break
        }

        // Una clave Vorbis puede repetirse, y para los artistas eso es un dato: el formato no tiene
        // otra forma de decir "dos intérpretes". Para el resto de claves vale la primera.
        fun first(key: String): String? = tags[key]?.firstOrNull()
        val artistValues = tags["ARTIST"].orEmpty()

        return TagFragment(
            title = first("TITLE"),
            // Lo que se MUESTRA: todos los intérpretes, no solo el primero. Los créditos por separado
            // van en `artists`.
            artist = artistValues.takeIf { it.isNotEmpty() }?.joinToString(", "),
            album = first("ALBUM"),
            // Tres grafías de facto para la misma clave, según el tagger: la de Picard/MusicBee, la de
            // foobar2000 y la de algunos rippers.
            albumArtists = tags["ALBUMARTISTS"].orEmpty().ifEmpty {
                tags["ALBUMARTIST"] ?: tags["ALBUM ARTIST"] ?: tags["ALBUM_ARTIST"] ?: emptyList()
            },
            artists = artistCreditsFrom(tags["ARTISTS"].orEmpty(), artistValues),
            genre = first("GENRE"),
            trackNumber = parseTrackNumber(first("TRACKNUMBER")),
            // `DATE` es la clave estándar de Vorbis y admite ISO-8601 completo; `YEAR` es una
            // extensión de facto que escriben algunos taggers viejos. [parseYear] acepta ambas.
            year = parseYear(first("DATE") ?: first("YEAR")),
            durationMs = durationMs,
            // No hay clave estándar para la letra en Vorbis: `LYRICS` es la de MusicBee/foobar
            // (y donde suele ir el LRC con marcas de tiempo), `UNSYNCEDLYRICS` la de Picard.
            lyrics = pickBestLyrics(first("LYRICS"), first("UNSYNCEDLYRICS"), first("SYNCEDLYRICS")),
            pictureData = pictureData,
            pictureRange = pictureRange
        )
    }

    /**
     * Los bytes que trajo el rango de [TagFragment.pictureRange] son el bloque PICTURE COMPLETO
     * (tipo, mime, descripción, dimensiones… y al final la imagen), así que hay que quitarles la
     * envoltura con la MISMA lógica que cuando el bloque sí cabe en el fragmento.
     */
    override fun decodePictureRange(bytes: ByteArray): ByteArray? =
        extractPictureData(bytes, 0, bytes.size)

    /**
     * STREAMINFO empaqueta los campos a nivel de BIT, no de byte: a partir del offset 10 vienen
     * 20 bits de frecuencia de muestreo, 3 de canales, 5 de bits por muestra y 36 del total de
     * muestras. Duración = muestras / frecuencia.
     */
    private fun parseDurationMs(buf: ByteArray, start: Int): Long {
        if (start + STREAMINFO_BYTES > buf.size) return 0L
        val b10 = buf[start + 10].toInt() and 0xFF
        val b11 = buf[start + 11].toInt() and 0xFF
        val b12 = buf[start + 12].toInt() and 0xFF
        val b13 = buf[start + 13].toInt() and 0xFF

        val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
        if (sampleRate <= 0) return 0L

        val totalSamples = ((b13 and 0x0F).toLong() shl 32) or
            ((buf[start + 14].toLong() and 0xFF) shl 24) or
            ((buf[start + 15].toLong() and 0xFF) shl 16) or
            ((buf[start + 16].toLong() and 0xFF) shl 8) or
            (buf[start + 17].toLong() and 0xFF)
        if (totalSamples <= 0L) return 0L

        return totalSamples * 1000L / sampleRate
    }

    /**
     * VORBIS_COMMENT: vendor string + N entradas `CLAVE=valor` en UTF-8, todas las longitudes
     * uint32 LITTLE-endian. Las claves son case-insensitive por spec, se normalizan a mayúsculas.
     */
    private fun parseVorbisComment(buf: ByteArray, start: Int, length: Int): Map<String, List<String>> {
        val end = start + length
        var p = start
        if (p + 4 > end) return emptyMap()
        val vendorLength = readUInt32LE(buf, p)
        // La longitud se valida ANTES de avanzar: un uint32 corrupto cercano a 2³¹ desbordaba el
        // Int al sumarlo, dejaba `p` NEGATIVO —con lo que `p + 4 > end` ya no era cierto— y el
        // siguiente acceso reventaba con AIOOBE. Restar es inmune: `end` y `p` están en rango.
        if (vendorLength < 0 || vendorLength > end - p - 4) return emptyMap()
        p += 4 + vendorLength
        if (p + 4 > end) return emptyMap()
        val count = readUInt32LE(buf, p)
        p += 4

        val out = HashMap<String, MutableList<String>>(count.coerceIn(0, 64))
        repeat(count.coerceIn(0, MAX_COMMENTS)) {
            if (p + 4 > end) return out
            val entryLength = readUInt32LE(buf, p)
            p += 4
            if (entryLength < 0 || entryLength > end - p) return out
            val entry = String(buf, p, entryLength, Charsets.UTF_8)
            p += entryLength
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val key = entry.substring(0, separator).uppercase()
                // Se guardan TODOS los valores, en orden: algunos archivos repiten ARTIST por cada
                // intérprete, y eso es un dato. Quien quiera uno solo toma el primero.
                val value = entry.substring(separator + 1)
                if (value.isNotBlank()) out.getOrPut(key) { mutableListOf() }.add(value.trim())
            }
        }
        return out
    }

    /**
     * Bloque PICTURE: campos de longitud fija BIG-endian (tipo, mime, descripción, ancho, alto,
     * profundidad, colores) y al final los bytes de la imagen, que es lo único que interesa.
     */
    private fun extractPictureData(buf: ByteArray, start: Int, length: Int): ByteArray? {
        val end = start + length
        var p = start + 4 // picture type
        if (p + 4 > end) return null
        // Cada longitud se valida ANTES de avanzar el puntero, por el desbordamiento de Int que
        // se explica en [parseVorbisComment].
        val mimeLength = readUInt32BE(buf, p)
        if (mimeLength < 0 || mimeLength > end - p - 4) return null
        p += 4 + mimeLength
        if (p + 4 > end) return null
        val descLength = readUInt32BE(buf, p)
        if (descLength < 0 || descLength > end - p - 4) return null
        p += 4 + descLength
        if (p + PICTURE_DIMENSIONS_BYTES + 4 > end) return null
        p += PICTURE_DIMENSIONS_BYTES // ancho, alto, profundidad de color, nº de colores indexados
        val dataLength = readUInt32BE(buf, p); p += 4
        if (dataLength <= 0 || dataLength > end - p) return null
        return buf.copyOfRange(p, p + dataLength)
    }

    private fun readUInt24BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 16) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            (buf[at + 2].toInt() and 0xFF)

    private fun readUInt32BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)

    private fun readUInt32LE(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            ((buf[at + 2].toInt() and 0xFF) shl 16) or
            ((buf[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val BLOCK_HEADER_BYTES = 4
        const val TYPE_STREAMINFO = 0
        const val TYPE_VORBIS_COMMENT = 4
        const val TYPE_PICTURE = 6
        /** STREAMINFO es de tamaño fijo por spec. */
        const val STREAMINFO_BYTES = 34
        /** Ancho, alto, profundidad de color y nº de colores indexados: 4 uint32 en el PICTURE. */
        const val PICTURE_DIMENSIONS_BYTES = 16
        /** Tope de sanidad: un count corrupto no debe hacernos iterar miles de millones de veces. */
        const val MAX_COMMENTS = 512
    }
}
