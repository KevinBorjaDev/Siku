package com.qhana.siku.data.util

import android.util.Log
import com.qhana.siku.data.model.ReplayGainData
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Lee los tags ReplayGain de un archivo de audio local usando JAudioTagger.
 *
 * JAudioTagger lee los Vorbis comments de FLAC correctamente (a diferencia de
 * MediaMetadataRetriever, que no expone tags arbitrarios). Los archivos vienen
 * etiquetados con rsgain (ReplayGain 2.0, target -18 LUFS).
 *
 * Formato de los tags (como texto):
 * - REPLAYGAIN_TRACK_GAIN → "-3.12 dB"   (hay que parsear el número)
 * - REPLAYGAIN_TRACK_PEAK → "0.954839"   (float lineal, 1.0 = 0 dBFS)
 * - REPLAYGAIN_ALBUM_GAIN / REPLAYGAIN_ALBUM_PEAK → igual formato
 *
 * (Si el archivo fuera Opus traería R128_TRACK_GAIN/R128_ALBUM_GAIN como entero Q7.8
 * referido a -23 LUFS → dB = valor/256 + 5 para alinear con el -18 de los FLAC. Caso raro
 * porque la biblioteca es casi toda FLAC, pero lo soportamos.)
 *
 * Robusto: cualquier error de lectura devuelve ReplayGainData con todo en null.
 */
object ReplayGainReader {

    private const val TAG = "ReplayGainReader"
    private val EMPTY = ReplayGainData(null, null, null, null)

    init {
        // JAudioTagger es muy verboso por java.util.logging; lo silenciamos.
        try {
            Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
        } catch (_: Exception) { /* no-op */ }
    }

    fun read(filePath: String): ReplayGainData {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return EMPTY
        return try {
            val tag = AudioFileIO.read(file).tag ?: return EMPTY

            fun first(key: String): String? = try {
                tag.getFirst(key)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }

            // Tags ReplayGain estándar (FLAC / Vorbis)
            var trackGain = parseGainDb(first("REPLAYGAIN_TRACK_GAIN"))
            var trackPeak = first("REPLAYGAIN_TRACK_PEAK")?.toFloatOrNull()
            var albumGain = parseGainDb(first("REPLAYGAIN_ALBUM_GAIN"))
            var albumPeak = first("REPLAYGAIN_ALBUM_PEAK")?.toFloatOrNull()

            // Fallback Opus (R128): entero Q7.8 referido a -23 LUFS. +5 dB para alinear con -18.
            if (trackGain == null) trackGain = parseR128(first("R128_TRACK_GAIN"))
            if (albumGain == null) albumGain = parseR128(first("R128_ALBUM_GAIN"))

            ReplayGainData(trackGain, trackPeak, albumGain, albumPeak)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer tags ReplayGain de $filePath: ${e.message}")
            EMPTY
        }
    }

    /** "-3.12 dB" → -3.12f. Extrae el primer número con signo. */
    private fun parseGainDb(raw: String?): Float? =
        raw?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toFloatOrNull() }

    /** R128 (Opus): entero Q7.8 referido a -23 LUFS → dB = valor/256 + 5 (alinear a -18). */
    private fun parseR128(raw: String?): Float? =
        raw?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
}
