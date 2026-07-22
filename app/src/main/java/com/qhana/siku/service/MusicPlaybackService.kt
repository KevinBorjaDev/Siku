package com.qhana.siku.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.qhana.siku.MainActivity
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.LogLevel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Servicio de reproducción de música con Media3 MediaSession.
 *
 * La cola completa vive en ExoPlayer (ver MusicController), por lo que la notificación
 * y los controles de auto/wear usan la API estándar del player (`seekToNextMediaItem()`,
 * `hasNextMediaItem()`, etc.) sin intermediarios.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaSessionService() {

    @Volatile private var mediaSession: MediaSession? = null
    @Volatile private var player: ExoPlayer? = null

    @Inject
    lateinit var dataSourceFactory: DataSource.Factory

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var musicPreferences: MusicPreferences

    @Inject
    lateinit var equalizerProcessor: com.qhana.siku.player.audio.EqualizerAudioProcessor

    /** Scope del watchdog de offload; se cancela en onDestroy. */
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** El DSP aceptó de verdad el offload para el track en curso (lo dice Media3). */
    @Volatile private var offloadActive = false

    /** Ya se hizo el fallback en esta sesión: no repetirlo ni re-preparar en bucle. */
    @Volatile private var offloadDisabled = false

    private var offloadWatchdogJob: Job? = null

    companion object {
        const val ACTION_SHOW_NOW_PLAYING = "com.qhana.siku.SHOW_NOW_PLAYING"
        const val CMD_GET_SESSION_ID = "GET_AUDIO_SESSION_ID"
        const val KEY_SESSION_ID = "AUDIO_SESSION_ID"

        /**
         * Cuánto puede estar el player en BUFFERING —con el offload ACTIVO, queriendo sonar y
         * sin que la posición avance— antes de declarar roto el DSP.
         *
         * El plazo depende de la FUENTE, y la diferencia es enorme para el usuario: un archivo
         * LOCAL no bufferiza jamás (abrirlo e iniciar el decoder son decenas de ms), así que
         * 1,5 s ya es un orden de magnitud de margen y no hay otra explicación posible que el
         * cuelgue del DSP. En STREAMING un buffering de varios segundos es legítimo (red mala),
         * y ahí hay que esperar más para no confundirlo.
         *
         * Un falso positivo solo cuesta perder el ahorro de batería, nunca el audio
         * (degradación segura): por eso se puede ser agresivo en el caso local.
         */
        private const val OFFLOAD_STALL_TIMEOUT_LOCAL_MS = 1_500L
        private const val OFFLOAD_STALL_TIMEOUT_REMOTE_MS = 6_000L
    }

    private val customCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_GET_SESSION_ID, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_GET_SESSION_ID) {
                val resultBundle = Bundle()
                val sessionId = player?.audioSessionId ?: 0
                resultBundle.putInt(KEY_SESSION_ID, sessionId)
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, resultBundle)
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            appLogger.log("SERVICE", "ExoPlayer.onIsPlayingChanged: isPlaying=$isPlaying")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onPlaybackStateChanged: state=$stateName")
            evaluateOffloadWatchdog()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            evaluateOffloadWatchdog()
            val reasonName = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                else -> "UNKNOWN($reason)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reasonName")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reasonName = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                else -> "UNKNOWN($reason)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reasonName")
        }

        override fun onPlayerError(error: PlaybackException) {
            appLogger.log("SERVICE", "ExoPlayer.onPlayerError: code=${error.errorCode}, msg=${error.message}", LogLevel.ERROR)
        }
        // onTracksChanged y onEvents (posición en cada discontinuidad) se quitaron:
        // logueaban en alta frecuencia durante toda la reproducción sin aportar diagnóstico útil.
    }

    /**
     * Media3 avisa cuando la reproducción offload realmente EMPIEZA en el DSP. Solo se usa
     * para loguear: NO puede ser condición del watchdog, porque en el DSP roto la reproducción
     * no empieza nunca y este callback JAMÁS llega — gatear con él dejaba el watchdog sin
     * armar y el cuelgue seguía intacto (bug encontrado en la primera prueba real).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val offloadListener = object : ExoPlayer.AudioOffloadListener {
        override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
            offloadActive = isOffloadedPlayback
            appLogger.log("SERVICE", "Audio offload activo en el DSP: $isOffloadedPlayback")
            evaluateOffloadWatchdog()
        }
    }

    /**
     * WATCHDOG DEL OFFLOAD. Arranca un temporizador cuando se dan a la vez: offload HABILITADO
     * por preferencia (no hace falta confirmación del DSP — ver [offloadListener]), estado
     * BUFFERING y el usuario queriendo escuchar. Si al vencer el plazo el player SIGUE en
     * BUFFERING y la posición NO avanzó ni un milisegundo, el DSP se tragó el audio: es el
     * cuelgue silencioso (ver [disableOffloadPermanently]).
     *
     * Cualquier otra transición (READY, pausa) cancela el temporizador, así que un buffering
     * normal —por corto que sea— nunca dispara el fallback.
     */
    private fun evaluateOffloadWatchdog() {
        val p = player ?: return
        val suspicious = !offloadDisabled &&
            p.playbackState == Player.STATE_BUFFERING &&
            p.playWhenReady

        if (!suspicious) {
            offloadWatchdogJob?.cancel()
            offloadWatchdogJob = null
            return
        }
        if (offloadWatchdogJob?.isActive == true) return // ya hay uno vigilando

        val positionAtStart = p.currentPosition
        val isLocal = isCurrentItemLocal(p)
        val timeout = if (isLocal) OFFLOAD_STALL_TIMEOUT_LOCAL_MS else OFFLOAD_STALL_TIMEOUT_REMOTE_MS
        offloadWatchdogJob = serviceScope.launch {
            delay(timeout)
            val player = this@MusicPlaybackService.player ?: return@launch
            val stillStalled = player.playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady &&
                player.currentPosition == positionAtStart
            if (stillStalled && !offloadDisabled) {
                disableOffloadAndRecover(player, timeout, persistVerdict = isLocal)
            }
        }
    }

    /** `file://` o `content://` = los bytes están en el dispositivo: no existe buffering de red. */
    private fun isCurrentItemLocal(player: ExoPlayer): Boolean {
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme ?: return false
        return scheme == "file" || scheme == "content"
    }

    /**
     * El offload de ESTE dispositivo se tragó el audio: se desactiva y se re-prepara la
     * canción por el camino de software, retomando en la misma posición (el usuario ve un
     * tirón de ~1,5 s, una única vez en la vida del dispositivo).
     *
     * [persistVerdict] distingue la certeza: con un archivo LOCAL colgado no existe otra
     * explicación (no hay red) → el veredicto se PERSISTE y en los próximos arranques el
     * offload ni se intenta. Con STREAMING el estancamiento podría ser la red, así que solo
     * se desactiva para esta sesión — un dispositivo sano no queda marcado para siempre por
     * un mal momento de WiFi.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun disableOffloadAndRecover(player: ExoPlayer, stalledMs: Long, persistVerdict: Boolean) {
        offloadDisabled = true
        appLogger.log(
            "SERVICE",
            "Offload COLGADO (BUFFERING ${stalledMs}ms sin avanzar): desactivando " +
                (if (persistVerdict) "PARA SIEMPRE (archivo local: veredicto inequívoco)" else "para esta sesión (streaming: pudo ser la red)") +
                " y re-preparando.",
            LogLevel.ERROR
        )
        if (persistVerdict) musicPreferences.saveOffloadBroken(true)

        val index = player.currentMediaItemIndex
        val position = player.currentPosition

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()

        // El AudioSink en modo offload está muerto: hay que rehacer la pipeline. stop() NO
        // borra la cola, así que se re-prepara y se retoma donde estaba.
        player.stop()
        player.prepare()
        player.seekTo(index, position)
        player.play()
    }

    /**
     * Aplica el modo de offload que corresponde al estado actual: DESACTIVADO si el DSP está
     * marcado como roto ([offloadDisabled]) o si el EQ está activo (los processors se saltan
     * en offload); ACTIVADO en cualquier otro caso.
     */
    private fun applyOffloadPreference(player: ExoPlayer) {
        val disable = offloadDisabled || equalizerProcessor.isEnabled()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (disable) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    )
                    .build()
            )
            .build()
    }

    /**
     * Toggle del ecualizador en caliente. isActive del processor solo se consulta al
     * (re)configurar el sink, así que hay que rehacer la pipeline: stop() no borra la cola
     * y se retoma en la misma posición (tirón de <1s, solo al alternar el EQ).
     */
    private fun applyEqEnabled(enabled: Boolean) {
        val p = player ?: return
        if (equalizerProcessor.isEnabled() == enabled) return
        appLogger.log("SERVICE", "Ecualizador ${if (enabled) "ACTIVADO" else "DESACTIVADO"}: reconstruyendo pipeline de audio")
        equalizerProcessor.setEnabled(enabled)
        applyOffloadPreference(p)

        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) return
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        val wasPlaying = p.playWhenReady
        p.stop()
        p.prepare()
        p.seekTo(index, position)
        if (wasPlaying) p.play()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        appLogger.log("SERVICE", "MusicPlaybackService.onCreate()")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        // Estado inicial del EQ desde preferencias, ANTES de construir el player: la pipeline
        // del sink consulta isActive del processor al configurarse.
        equalizerProcessor.setEnabled(musicPreferences.loadEqEnabled())
        val eqBandCount = musicPreferences.loadEqBandCount()
        equalizerProcessor.setBands(
            com.qhana.siku.player.audio.EqualizerAudioProcessor.bandsFor(eqBandCount),
            musicPreferences.loadEqBandGains(eqBandCount)
        )

        // setExtensionRendererMode se quitó: no hay renderers de extensión empaquetados,
        // así que no tenía efecto. setEnableDecoderFallback sí importa (cae a otro decoder
        // si el preferido falla al inicializar).
        // buildAudioSink se sobreescribe para colar el EQ propio en la cadena del sink
        // (EqAudioProcessorChain: EQ float + sonic, SIN silence-skipping — ver su kdoc).
        // Con el EQ apagado el processor está inactivo y la pipeline es idéntica a la stock.
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        com.qhana.siku.player.audio.EqAudioProcessorChain(equalizerProcessor)
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
            }
        }
            .setEnableDecoderFallback(true)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                50_000,
                500,
                2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val playerBuilder = ExoPlayer.Builder(this)
        playerBuilder.setRenderersFactory(renderersFactory)
        playerBuilder.setMediaSourceFactory(mediaSourceFactory)
        playerBuilder.setLoadControl(loadControl)
        playerBuilder.setAudioAttributes(audioAttributes, true)
        playerBuilder.setHandleAudioBecomingNoisy(true)

        player = playerBuilder.build()
        player?.addListener(playerListener)
        player?.addAudioOffloadListener(offloadListener)

        // Offload: el DSP decodifica y la CPU duerme (ahorro de batería real con la pantalla
        // apagada). Se intenta SIEMPRE, salvo: (a) dispositivos donde el watchdog ya comprobó
        // que el DSP lo implementa mal y se cuelga (veredicto persistido), o (b) mientras el
        // ECUALIZADOR está activo — en offload el audio comprimido va directo al DSP y toda
        // la cadena de AudioProcessors (el EQ incluido) se salta: sonaría sin ecualizar.
        val offloadBroken = musicPreferences.loadOffloadBroken()
        offloadDisabled = offloadBroken
        applyOffloadPreference(player!!)

        appLogger.log(
            "SERVICE",
            when {
                offloadBroken -> "ExoPlayer creado con audio offload DESACTIVADO (DSP marcado como roto en este dispositivo)"
                equalizerProcessor.isEnabled() -> "ExoPlayer creado con audio offload cedido al ECUALIZADOR (pipeline float)"
                else -> "ExoPlayer creado con audio offload ACTIVO (ahorro de batería; watchdog vigilando)"
            }
        )

        // Toggle del EQ en caliente (desde la hoja del NowPlaying): reconstruir la pipeline
        // del sink conservando posición — mismo patrón probado que el fallback del offload.
        // dataStore emite el valor actual al suscribirse; el distinctUntilChanged + comparación
        // con el estado real del processor evita un rebuild espurio al arrancar.
        serviceScope.launch {
            musicPreferences.eqEnabledFlow
                .distinctUntilChanged()
                .collect { enabled -> applyEqEnabled(enabled) }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Conectamos el player DIRECTO (sin ForwardingPlayer); la cola nativa ya expone
        // next/prev correctamente al cliente de notificación.
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(pendingIntent)
            .setCallback(customCallback)
            .build()

        appLogger.log("SERVICE", "MediaSession created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Swipe de recientes con música sonando = SEGUIR sonando (comportamiento estándar de las
     * apps de música: Spotify/YT Music hacen lo mismo; es el patrón recomendado de Media3 y
     * NO es un bug). Solo se detiene el servicio si ya estaba en pausa o sin cola.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        appLogger.log("SERVICE", "onTaskRemoved() called")
        val player = mediaSession?.player
        if (player != null) {
            val shouldStop = !player.playWhenReady || player.mediaItemCount == 0
            if (shouldStop) {
                stopSelf()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        appLogger.log("SERVICE", "onDestroy() called")

        offloadWatchdogJob?.cancel()
        serviceScope.cancel()
        try { this.player?.removeAudioOffloadListener(offloadListener) } catch (_: Exception) {}

        mediaSession?.let { session ->
            try { session.release() } catch (e: Exception) {
                appLogger.log("SERVICE", "Error releasing mediaSession: ${e.message}", LogLevel.ERROR)
            }
            mediaSession = null
        }

        this.player?.let { exoPlayer ->
            try { exoPlayer.removeListener(playerListener) } catch (_: Exception) {}
            try { exoPlayer.release() } catch (e: Exception) {
                appLogger.log("SERVICE", "Error releasing exoPlayer: ${e.message}", LogLevel.ERROR)
            }
        }
        this.player = null

        super.onDestroy()
    }
}
