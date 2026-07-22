package com.qhana.siku.data.model

import kotlin.math.pow

/**
 * Datos de ReplayGain leídos de los tags del archivo (Vorbis comments en FLAC).
 *
 * - *gainDb*: ganancia recomendada en dB para alcanzar el target (ReplayGain 2.0 = -18 LUFS).
 *   Casi siempre NEGATIVA (atenúa).
 * - *peak*: pico de muestra LINEAL (1.0 = 0 dBFS). Solo se usa si algún día se aplica
 *   pre-amp positivo y se quiere limitar para no clipear; atenuando nunca clippea.
 *
 * Todos nullable: un archivo sin tags ReplayGain deja todo en null → no se toca el volumen.
 */
data class ReplayGainData(
    val trackGainDb: Float?,
    val trackPeak: Float?,
    val albumGainDb: Float?,
    val albumPeak: Float?,
) {
    /** true si no hay ninguna ganancia utilizable (track ni album). */
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null
}

/**
 * Modo de aplicación de ReplayGain.
 * - OFF: sin igualación (volumen = 1.0).
 * - TRACK: iguala pista a pista (ideal para shuffle mezclando artistas).
 * - ALBUM: usa la ganancia del álbum (preserva la relación de volumen entre pistas
 *   de un mismo disco); cae a TRACK si el álbum no tiene ganancia.
 */
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/**
 * Convierte ganancias ReplayGain (dB) al `volume` de ExoPlayer (factor lineal 0.0–1.0).
 *
 * `ExoPlayer.setVolume` solo ATENÚA (no sube por encima de 1.0), que es justo lo que
 * necesita ReplayGain: las ganancias son casi siempre negativas. Si la suma gain+preamp
 * diera un factor > 1.0 se capa a 1.0 (esa pista pierde igualación, pero no se distorsiona).
 */
object ReplayGainCalculator {

    /** dB → factor lineal de volumen, capado a [0, 1]. null → 1.0 (no tocar). */
    fun gainToVolume(gainDb: Float?, preampDb: Float): Float {
        if (gainDb == null) return 1f
        val factor = 10.0.pow((gainDb + preampDb) / 20.0).toFloat()
        return factor.coerceIn(0f, 1f)
    }

    /** Calcula el volumen del player para una canción según el modo y el pre-amp. */
    fun volumeFor(rg: ReplayGainData?, mode: ReplayGainMode, preampDb: Float): Float {
        val gain = when (mode) {
            ReplayGainMode.OFF -> return 1f
            ReplayGainMode.ALBUM -> rg?.albumGainDb ?: rg?.trackGainDb // fallback a track
            ReplayGainMode.TRACK -> rg?.trackGainDb
        }
        return gainToVolume(gain, preampDb)
    }
}
