package com.qhana.siku.data.playlist

/**
 * Una entrada de un M3U: la referencia al archivo tal como está escrita, más lo que declare su
 * `#EXTINF` (si lo hay).
 *
 * La referencia se guarda **CRUDA**, sin normalizar: quien la resuelve necesita saber si era
 * absoluta o relativa, y normalizar aquí perdería esa diferencia. La normalización vive en el
 * resolutor, que es quien conoce la biblioteca.
 */
data class M3uEntry(
    val reference: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSeconds: Long? = null
)

/** Un M3U leído: su nombre declarado (`#PLAYLIST:`, casi nunca presente) y sus entradas en orden. */
data class M3uPlaylist(
    val name: String?,
    val entries: List<M3uEntry>
)

/**
 * Lectura y escritura del formato M3U/M3U8.
 *
 * **No hay un estándar**: M3U es una convención que empezó como "una ruta por línea" y a la que
 * Winamp añadió las directivas `#EXT*`. De ahí las dos reglas de este parser: **cualquier línea que
 * no empiece por `#` es una entrada** (aunque no la preceda ningún `#EXTINF`), y **toda directiva
 * desconocida se ignora en silencio** en vez de invalidar el archivo. Un lector estricto rechazaría
 * la mitad de los M3U que existen.
 *
 * Lo mismo vale al ESCRIBIR: se emite el subconjunto que entiende todo el mundo (`#EXTM3U`,
 * `#PLAYLIST`, `#EXTINF`) y nada más.
 */
object M3uFormat {

    const val EXTENSION_UTF8 = "m3u8"
    const val EXTENSION = "m3u"

    /** El MIME que declaran los `.m3u`; el `.m3u8` usa el mismo con `; charset=utf-8`. */
    const val MIME_TYPE = "audio/x-mpegurl"

    private const val HEADER = "#EXTM3U"
    private const val NAME_PREFIX = "#PLAYLIST:"
    private const val INFO_PREFIX = "#EXTINF:"

    /**
     * Separador entre artista y título dentro de un `#EXTINF`. Es una CONVENCIÓN y no parte del
     * formato: lo único que el formato define es "duración , texto libre". Por eso, si no aparece,
     * el texto entero se toma como TÍTULO en vez de intentar adivinar dónde parte —partir por el
     * primer guion rompería "Jay-Z" y todos los títulos con guion.
     */
    private const val ARTIST_SEPARATOR = " - "

    /**
     * Fin de línea al escribir. **LF y no CRLF**: es lo que producen los reproductores de Android y
     * de escritorio modernos, y todo lector tolera ambos (este mismo parte por `\n` y recorta el
     * `\r` que sobre).
     */
    private const val LINE_BREAK = "\n"

    fun parse(text: String): M3uPlaylist {
        var name: String? = null
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDuration: Long? = null
        val entries = mutableListOf<M3uEntry>()

        // El BOM solo se quita de la PRIMERA línea: en medio del archivo sería contenido.
        for ((index, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim().let { if (index == 0) it.removePrefix(BOM) else it }
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                when {
                    line.startsWith(NAME_PREFIX) ->
                        name = line.removePrefix(NAME_PREFIX).trim().takeIf { it.isNotEmpty() }
                    line.startsWith(INFO_PREFIX) -> {
                        val info = parseExtInf(line.removePrefix(INFO_PREFIX))
                        pendingDuration = info.durationSeconds
                        pendingArtist = info.artist
                        pendingTitle = info.title
                    }
                    // `#EXTM3U` y cualquier otra directiva (#EXTALB, #EXTGRP, comentarios sueltos):
                    // se ignoran, pero NO limpian el #EXTINF pendiente — una directiva entre el
                    // #EXTINF y su ruta es legal y el par sigue siendo el mismo.
                    else -> Unit
                }
                continue
            }

            entries += M3uEntry(
                reference = line,
                title = pendingTitle,
                artist = pendingArtist,
                durationSeconds = pendingDuration
            )
            pendingTitle = null
            pendingArtist = null
            pendingDuration = null
        }

        return M3uPlaylist(name = name, entries = entries)
    }

    /**
     * `123,Artista - Título` → sus tres partes. La duración es **negativa o ausente** en muchos
     * archivos (streams, o taggers que no la calculan), así que no puede ser obligatoria: si no
     * parsea, la entrada sigue siendo válida y se queda sin duración.
     */
    private fun parseExtInf(payload: String): M3uEntry {
        val comma = payload.indexOf(',')
        val duration = (if (comma < 0) payload else payload.substring(0, comma))
            .trim()
            // Algunos escriben atributos antes de la coma (`123 tvg-id="x"`): la duración es el
            // primer token.
            .substringBefore(' ')
            .toLongOrNull()
            ?.takeIf { it > 0 }
        val label = if (comma < 0) "" else payload.substring(comma + 1).trim()
        val separator = label.indexOf(ARTIST_SEPARATOR)
        return M3uEntry(
            reference = "",
            title = (if (separator < 0) label else label.substring(separator + ARTIST_SEPARATOR.length))
                .trim().takeIf { it.isNotEmpty() },
            artist = (if (separator < 0) null else label.substring(0, separator).trim())
                ?.takeIf { it.isNotEmpty() },
            durationSeconds = duration
        )
    }

    /**
     * Serializa a M3U8. Las referencias se escriben tal cual las entregue quien llama — decidir si
     * una canción va con ruta absoluta o relativa es cosa suya, no del formato.
     */
    fun write(playlist: M3uPlaylist): String = buildString {
        append(HEADER).append(LINE_BREAK)
        playlist.name?.takeIf { it.isNotBlank() }?.let {
            append(NAME_PREFIX).append(it).append(LINE_BREAK)
        }
        for (entry in playlist.entries) {
            val label = listOfNotNull(entry.artist, entry.title).joinToString(ARTIST_SEPARATOR)
            append(INFO_PREFIX)
                .append(entry.durationSeconds ?: -1)
                .append(',')
                .append(label)
                .append(LINE_BREAK)
            append(entry.reference).append(LINE_BREAK)
        }
    }

    /** Marca de orden de bytes: la escriben muchos editores de Windows al guardar UTF-8. */
    private const val BOM = "\uFEFF"
}
