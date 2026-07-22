package com.qhana.siku.player.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ecualizador gráfico de 5/10 bandas como [AudioProcessor] de Media3, con SALIDA EN FLOAT:
 * acepta PCM de 16 bits (lo que decodifica ExoPlayer para FLAC/MP3 16-bit) o float, procesa
 * los biquads en double y emite `ENCODING_PCM_FLOAT`. Peaking-EQ del cookbook RBJ, uno por
 * banda y canal, en cascada; estado del filtro por canal.
 *
 * Los coeficientes se recalculan perezosamente en el hilo de audio cuando cambian ganancias
 * o formato ([coeffsDirty]); el estado de las bandas que siguen activas se preserva para no
 * meter clicks al mover un slider en vivo. La curva plana es bit-perfect (sin filtros ni
 * limitador, 16-bit solo re-encuadrado a float).
 *
 * SALIDA SIN LIMITADOR ([LIMITER_ENABLED] = false, veredicto de escucha del usuario,
 * 20 jul 2026): los picos que la curva empuja sobre full scale salen >1.0f y la atenuación
 * DIGITAL del volumen de media (aplicada por pista en el mixer float de AudioFlinger, antes
 * del recorte) los devuelve a rango — en la ruta del usuario (cable, volumen bajo el máximo)
 * esto funciona y suena mejor que el gain riding del peak limiter, que comprimía la mezcla
 * con cualquier curva no plana sobre masters modernos ("se sentía rara la canción"). El
 * limiter queda como flag por si aparece clipping real (volumen al 100% o Bluetooth con
 * volumen absoluto, donde los overs sí se recortan).
 *
 * NO REINTRODUCIR sin una investigación seria (probado y descartado, jul 2026 — ver memoria
 * del proyecto): bass/treble boost como filtros aparte (ni siquiera sin limitador
 * convencieron: pendiente investigación exhaustiva), auto-preamp que baja el nivel global,
 * ruteo de ReplayGain dentro de este processor.
 *
 * INTERACCIÓN CON OFFLOAD: en modo offload TODA la cadena de processors se salta, por eso
 * `MusicPlaybackService` desactiva el offload mientras el EQ está activo, y este processor
 * se declara inactivo ([onConfigure] → NOT_SET) cuando está deshabilitado para no forzar
 * la pipeline float (ni impedir el offload) con el EQ apagado.
 *
 * Cambiar [setEnabled] NO reconfigura la pipeline en caliente: el servicio debe
 * reconstruirla (stop/prepare). Las GANANCIAS sí son en vivo.
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    companion object {
        /** Frecuencias centrales clásicas de 5 bandas de Android. */
        private val BANDS_5 = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)

        /** 10 bandas ISO por octava (EQ gráfico clásico). */
        private val BANDS_10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

        /** Layout de bandas del modo [count]. Copia defensiva: los arrays maestros son privados. */
        fun bandsFor(count: Int): FloatArray = (if (count == 10) BANDS_10 else BANDS_5).copyOf()

        const val MAX_GAIN_DB = 12f

        // Q por modo: con 10 bandas por octava los picos deben ser más angostos para no
        // solaparse (Q≈1.41 es el estándar de EQ gráfico de octava); con 5 bandas, más
        // anchos para cubrir el espectro entre centros.
        private const val Q_5_BANDS = 0.9
        private const val Q_10_BANDS = 1.41

        /** Ganancias menores a esto son identidad: la banda ni se calcula. */
        private const val IDENTITY_EPSILON_DB = 0.05f

        /** Techo del limitador (bajo 1.0 para margen de redondeo) y release de la envolvente. */
        private const val LIMITER_THRESHOLD = 0.985
        private const val LIMITER_RELEASE_SECONDS = 0.150

        /**
         * DECISIÓN por A/B de escucha (20 jul 2026): limitador APAGADO. Su gain riding
         * (ataque instantáneo + release 150 ms) comprimía audiblemente con cualquier preset
         * no plano (Jazz: "la canción se sentía rara") y en la ruta real del usuario los
         * overs >1.0f los absorbe la atenuación digital del volumen de media. Reactivar
         * (true) SOLO si aparece distorsión áspera real en pasajes fuertes (volumen al
         * máximo o BT con volumen absoluto); la mejora correcta sería un lookahead.
         */
        private const val LIMITER_ENABLED = false
    }

    /** Frecuencias + ganancias como snapshot ATÓMICO: el hilo de audio lo lee entero. */
    private class EqConfig(
        val frequencies: FloatArray,
        val gainsDb: FloatArray
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var config = EqConfig(BANDS_5, FloatArray(BANDS_5.size))

    @Volatile
    private var coeffsDirty = true

    /** Biquads activos (solo bandas con ganancia != 0), estado por canal. Solo hilo de audio. */
    private var filters: Array<Biquad> = emptyArray()

    /** Layout de bandas del último rebuild (comparado por contenido, ver [rebuildFilters]). */
    private var lastBandLayout: FloatArray? = null

    // Limitador: envolvente de pico compartida entre canales (preserva la imagen estéreo) y
    // coeficiente de release por muestra INTERCALADA (se fija en rebuildFilters).
    private var limiterEnv = 0.0
    private var limiterReleaseCoeff = 0.9999

    fun isEnabled(): Boolean = enabled

    /** Solo cambia el flag; la pipeline debe reconstruirse (ver kdoc de la clase). */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Ganancia en vivo de una banda; audible en el siguiente buffer procesado. */
    fun setBandGain(band: Int, db: Float) {
        val current = config
        if (band !in current.gainsDb.indices) return
        val next = current.gainsDb.copyOf()
        next[band] = db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        config = EqConfig(current.frequencies, next)
        coeffsDirty = true
    }

    /**
     * Cambia bandas + ganancias en un solo swap (también EN VIVO: el formato de salida no
     * depende del nº de bandas, así que alternar 5↔10 no exige reconstruir la pipeline).
     */
    fun setBands(frequencies: FloatArray, gainsDb: FloatArray) {
        val gains = FloatArray(frequencies.size) { i ->
            (gainsDb.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        config = EqConfig(frequencies.copyOf(), gains)
        coeffsDirty = true
    }

    fun setBandGains(db: FloatArray) {
        val current = config
        val next = FloatArray(current.frequencies.size) { i ->
            (db.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        config = EqConfig(current.frequencies, next)
        coeffsDirty = true
    }

    fun getBandGains(): FloatArray = config.gainsDb.copyOf()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        coeffsDirty = true
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (floatInput) 4 else 2

        if (coeffsDirty) rebuildFilters(channels)

        val samples = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(samples * 4)
        val activeFilters = filters
        // Limitador SOLO con filtros activos (y si el experimento lo tiene habilitado);
        // curva plana = bit-perfect.
        val limiting = LIMITER_ENABLED && activeFilters.isNotEmpty()
        var env = limiterEnv
        val release = limiterReleaseCoeff

        var channel = 0
        while (inputBuffer.remaining() >= bytesPerSample) {
            var sample = if (floatInput) {
                inputBuffer.float.toDouble()
            } else {
                inputBuffer.short / 32768.0
            }
            for (filter in activeFilters) {
                sample = filter.process(sample, channel)
            }
            if (limiting) {
                // Peak limiter: la envolvente sigue el pico (ataque instantáneo) y decae con
                // release exponencial; la ganancia (threshold/env) reduce SOLO mientras la
                // señal filtrada superaría el techo. Gain riding suave, no satura la onda.
                val ax = if (sample < 0) -sample else sample
                env = if (ax > env) ax else env * release
                if (env > LIMITER_THRESHOLD) sample *= LIMITER_THRESHOLD / env
            }
            output.putFloat(sample.toFloat())
            channel++
            if (channel == channels) channel = 0
        }
        limiterEnv = env
        output.flip()
    }

    override fun onFlush() {
        // Seek/cambio de tema: el estado del filtro arrastra energía del audio anterior.
        for (filter in filters) filter.clearState()
        limiterEnv = 0.0
    }

    override fun onReset() {
        filters = emptyArray()
        lastBandLayout = null
        limiterEnv = 0.0
        coeffsDirty = true
    }

    /**
     * Reconstruye los biquads para las ganancias actuales. El estado de las bandas que siguen
     * presentes se PRESERVA cuando el layout de bandas no cambió (comparado por contenido —
     * [bandsFor] devuelve copias), para no meter un click al mover un slider; tras un cambio
     * 5↔10 los índices apuntan a frecuencias distintas y se arranca de cero (arrastrar estado
     * ajeno mete un transitorio peor).
     */
    private fun rebuildFilters(channels: Int) {
        coeffsDirty = false
        val snapshot = config
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        // Release por muestra INTERCALADA (la envolvente avanza una vez por muestra de cada
        // canal → sampleRate * channels pasos por segundo).
        limiterReleaseCoeff = exp(-1.0 / (LIMITER_RELEASE_SECONDS * sampleRate * channels))

        val q = if (snapshot.frequencies.size == 10) Q_10_BANDS else Q_5_BANDS
        val next = mutableListOf<Biquad>()
        for (band in snapshot.frequencies.indices) {
            addPeak(next, snapshot.gainsDb[band], snapshot.frequencies[band].toDouble(), q, sampleRate, channels, band)
        }

        if (lastBandLayout?.contentEquals(snapshot.frequencies) == true) {
            val previous = filters.associateBy { it.band }
            for (filter in next) previous[filter.band]?.let { filter.copyStateFrom(it) }
        }
        lastBandLayout = snapshot.frequencies
        filters = next.toTypedArray()
    }

    /** Añade un biquad peaking-EQ RBJ (una banda del EQ). */
    private fun addPeak(
        into: MutableList<Biquad>,
        gainDb: Float,
        freq: Double,
        q: Double,
        sampleRate: Double,
        channels: Int,
        band: Int
    ) {
        if (abs(gainDb) < IDENTITY_EPSILON_DB) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a
        into.add(
            Biquad(
                band = band,
                b0 = (1.0 + alpha * a) / a0,
                b1 = (-2.0 * cosW0) / a0,
                b2 = (1.0 - alpha * a) / a0,
                a1 = (-2.0 * cosW0) / a0,
                a2 = (1.0 - alpha / a) / a0,
                channels = channels
            )
        )
    }

    /** Biquad Direct Form I con estado por canal. */
    private class Biquad(
        val band: Int,
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
        channels: Int
    ) {
        private val x1 = DoubleArray(channels)
        private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels)
        private val y2 = DoubleArray(channels)

        fun process(x: Double, ch: Int): Double {
            if (ch >= x1.size) return x
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
            x2[ch] = x1[ch]
            x1[ch] = x
            y2[ch] = y1[ch]
            y1[ch] = y
            return y
        }

        fun clearState() {
            x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        }

        fun copyStateFrom(other: Biquad) {
            val n = minOf(x1.size, other.x1.size)
            for (i in 0 until n) {
                x1[i] = other.x1[i]; x2[i] = other.x2[i]
                y1[i] = other.y1[i]; y2[i] = other.y2[i]
            }
        }
    }
}

/**
 * Cadena de processors del sink: [EqualizerAudioProcessor] + Sonic (velocidad/pitch, acepta
 * float). NO se usa [androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain]
 * porque mete [SilenceSkippingAudioProcessor] DESPUÉS de los custom y su onConfigure lanza
 * con cualquier input que no sea 16-bit AUNQUE esté desactivado — rompería la pipeline en
 * cuanto el EQ emite float. El skip-silence no se usa en la app.
 */
@UnstableApi
class EqAudioProcessorChain(
    private val equalizer: EqualizerAudioProcessor
) : AudioProcessorChain {

    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(equalizer, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
