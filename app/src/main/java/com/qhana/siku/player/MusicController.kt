package com.qhana.siku.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.remote.OneDriveResolvingDataSourceFactory
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.LogLevel
import com.qhana.siku.player.manager.PlaylistManager
import com.qhana.siku.player.manager.SessionStateManager
import com.qhana.siku.player.manager.SongCacheManager
import com.qhana.siku.service.MusicPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controlador de música orquestador.
 * Delega la gestión de estado, playlist y caché a managers especializados.
 */
@Singleton
class MusicController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val appLogger: AppLogger,
    private val playlistManager: PlaylistManager,
    private val sessionStateManager: SessionStateManager,
    private val songCacheManager: SongCacheManager,
    private val musicPreferences: com.qhana.siku.data.preferences.MusicPreferences,
    private val musicRepository: com.qhana.siku.data.repository.IMusicRepository
) {

    companion object {
        private const val TAG = "MusicController"

        // --- Criterio de "escucha registrada" (historial, `songs.playCount`) ---
        // Regla de scrobbling clásica: cuenta como reproducida al llegar a la mitad de la
        // canción, con un tope absoluto para que los temas largos no exijan escucharse medio
        // disco. Sin duración conocida (streaming aún sin metadata) se usa un mínimo fijo.
        private const val PLAY_HALF_DIVISOR = 2
        private const val PLAY_ABSOLUTE_THRESHOLD_MS = 4 * 60_000L
        private const val PLAY_UNKNOWN_DURATION_THRESHOLD_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val initLock = Any()
    @Volatile private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var mediaController: MediaController? = null

    // Estados observables delegados o locales
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackError = MutableSharedFlow<PlaybackErrorInfo>(extraBufferCapacity = 1)
    val playbackError = _playbackError.asSharedFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** Conexión viva con el MediaController (para consumidores fuera de la UI, p. ej. widgets). */
    val isConnected: Boolean get() = mediaController?.isConnected == true

    // La conexión también como ESTADO observable: quien necesite esperarla (widgets en
    // proceso frío) suspende sobre la señal en vez de polear `isConnected` con delays.
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    // Delegados a PlaylistManager
    val playlist: StateFlow<List<Song>> = playlistManager.playlist
    val currentIndex: StateFlow<Int> = playlistManager.currentIndex
    val isShuffleEnabled: StateFlow<Boolean> = playlistManager.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = playlistManager.repeatMode

    private var audioSessionId = 0

    // Jobs para manejar carga y callbacks
    private var loadJob: Job? = null
    private var listenerJob: Job? = null
    private var networkRetryCount = 0
    private val maxNetworkRetries = 5

    // === Sleep timer ===

    /**
     * Estado del temporizador de apagado. [endAtMs] es wall-clock (la UI deriva el restante);
     * [awaitingSongEnd] indica que el plazo ya venció pero se espera el final de la canción
     * en curso ([finishSong]) para pausar.
     */
    data class SleepTimerState(
        val endAtMs: Long,
        val finishSong: Boolean,
        val awaitingSongEnd: Boolean = false
    )

    private val _sleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleepTimer.asStateFlow()
    private var sleepTimerJob: Job? = null

    private fun fetchAudioSessionId() {
        val controller = mediaController ?: return
        val command = SessionCommand(MusicPlaybackService.CMD_GET_SESSION_ID, Bundle())
        val future = controller.sendCustomCommand(command, Bundle())
        
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    audioSessionId = result.extras.getInt(MusicPlaybackService.KEY_SESSION_ID)
                }
            } catch (e: Exception) {
                appLogger.error("Error fetching session ID: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    fun initialize() {
        synchronized(initLock) {
            if (mediaController != null && mediaController?.isConnected == true) {
                appLogger.controller("initialize() skipped: MediaController already connected")
                return
            }

            if (controllerFuture != null) {
                appLogger.controller("initialize() skipped: connection already in progress")
                return
            }

            appLogger.controller("initialize() called - building MediaController. Scope active: ${scope.isActive}")
            try {
                val intent = android.content.Intent(context, MusicPlaybackService::class.java)
                intent.action = androidx.media3.session.MediaSessionService.SERVICE_INTERFACE
                context.startService(intent)
            } catch (e: Exception) {
                appLogger.error("Error starting service: ${e.message}")
            }

            val sessionToken = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
            val future = MediaController.Builder(context, sessionToken)
                .buildAsync()
            controllerFuture = future

            future.addListener({
                try {
                    val controller = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    synchronized(initLock) {
                        mediaController = controller
                    }
                    controller.addListener(playerListener)
                    _connectionState.value = true
                    appLogger.controller("MediaController CONNECTED")
                    fetchAudioSessionId()
                    syncCurrentState()
                } catch (e: Exception) {
                    appLogger.controller("MediaController connection FAILED: ${e.message}", LogLevel.ERROR)
                    _connectionState.value = false
                    synchronized(initLock) {
                        if (controllerFuture == future) {
                            controllerFuture = null
                        }
                    }
                    try {
                        MediaController.releaseFuture(future)
                    } catch (_: Exception) {}
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private fun syncCurrentState() {
        mediaController?.let { controller ->
            // Reconciliar repeat: el flag nativo es la fuente de verdad si el servicio siguió
            // vivo (p.ej. proceso de la UI recreado con música sonando). Sin esto, PlaylistManager
            // arranca en OFF y decisiones locales basadas en él divergen del player real.
            playlistManager.setRepeatMode(when (controller.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            })

            // Shuffle nativo activado desde fuera (Auto/Assistant) mientras no estábamos:
            // traducirlo al shuffle propio (que reordena la cola de verdad) y apagar el flag,
            // igual que hace el listener con los cambios en vivo. Si la lista lógica aún no
            // está restaurada, la traducción se difiere a después del restore (toggleShuffle
            // sobre una lista vacía no puede reordenar nada).
            val hadNativeShuffle = controller.shuffleModeEnabled
            if (hadNativeShuffle) controller.shuffleModeEnabled = false

            if (controller.mediaItemCount > 0) {
                if (playlistManager.getCurrentPlaylist().isEmpty()) {
                    // El servicio sigue vivo con su cola: reconstruimos SOLO la lista lógica
                    // desde la BD respetando el índice actual del player, sin re-preparar
                    // (el player ya tiene la cola cargada).
                    scope.launch(Dispatchers.IO) {
                        val restored = sessionStateManager.restoreSessionFromDb() ?: return@launch
                        withContext(Dispatchers.Main) {
                            val idx = (mediaController?.currentMediaItemIndex ?: 0)
                                .coerceIn(0, restored.playlist.lastIndex)
                            playlistManager.setPlaylist(restored.playlist, idx)
                            // setPlaylist resetea el aleatorio: reponerlo con el estado guardado
                            // SIN re-barajar (la cola restaurada ya viene en el orden barajado).
                            playlistManager.restoreShuffleState(restored.shuffleEnabled, restored.originalPlaylist)
                            if (hadNativeShuffle && !playlistManager.isShuffleEnabled.value) toggleShuffle()
                        }
                    }
                } else if (hadNativeShuffle && !playlistManager.isShuffleEnabled.value) {
                    toggleShuffle()
                }

                _playbackState.value = if (controller.isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                _currentPosition.value = controller.currentPosition
                updateDurationSafe(controller.duration)
            } else {
                // Cola vacía: restaurar la sesión guardada resolviendo los IDs contra la BD
                // y preparando el player (autoPlay=false).
                scope.launch(Dispatchers.IO) {
                    val restored = sessionStateManager.restoreSessionFromDb() ?: return@launch
                    withContext(Dispatchers.Main) {
                        playlistManager.setPlaylist(restored.playlist, restored.index)
                        playlistManager.restoreShuffleState(restored.shuffleEnabled, restored.originalPlaylist)
                        playAt(restored.index, startPosition = restored.position, autoPlay = false)
                    }
                }
            }
        }
    }

    // === Navegación y Control ===

    /**
     * next/previous delegan a ExoPlayer. Como ahora toda la cola está cargada en el
     * player, `seekToNextMediaItem()` funciona nativamente y respeta `repeatMode`
     * (REPEAT_MODE_ALL envuelve al inicio automáticamente).
     */
    fun next() {
        val controller = mediaController ?: return
        // Con repeat ALL, hasNextMediaItem() ya envuelve al inicio: el flag nativo se
        // mantiene sincronizado en toggleRepeatMode y se reconcilia en syncCurrentState.
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun previous() {
        val controller = mediaController ?: return
        val pos = controller.currentPosition
        if (pos > 3000L) {
            controller.seekTo(0L)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        }
    }

    /**
     * Salta al índice `index` de la cola. Si la cola de ExoPlayer está desincronizada
     * con `PlaylistManager` (p.ej. restore de sesión), la recarga completa antes de saltar.
     */
    fun playAt(index: Int, startPosition: Long = 0, autoPlay: Boolean = true) {
        if (index < 0) {
            Log.w(TAG, "playAt ignored: negative index $index")
            return
        }
        val songs = playlistManager.getCurrentPlaylist()
        if (index >= songs.size) {
            Log.w(TAG, "playAt ignored: invalid index $index (playlist size: ${songs.size})")
            return
        }

        val song = songs[index]

        // UI optimista
        _currentSong.value = song
        playlistManager.setCurrentIndex(index)

        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Main) {
            val controller = mediaController ?: return@launch
            if (!controller.isConnected) {
                Log.w(TAG, "playAt: mediaController no conectado, abortando")
                return@launch
            }

            try {
                // Si la cola de ExoPlayer está vacía o desincronizada con PlaylistManager, recargarla.
                // OJO: cortocircuito importante — `getMediaItemAt(index)` lanza IndexOutOfBoundsException
                // si la cola está vacía, por eso primero validamos el count y el rango del índice.
                val queueCount = controller.mediaItemCount
                val queueInSync = queueCount > 0 &&
                    queueCount == songs.size &&
                    index < queueCount &&
                    controller.getMediaItemAt(index).mediaId == song.id

                if (!queueInSync) {
                    // Historial: recargar la cola descarta el item en curso SIN discontinuidad
                    // AUTO/SEEK (reason REMOVE), así que la escucha interrumpida se cuenta acá.
                    // Misma canción (retry, restore de sesión) se excluye: esa escucha sigue
                    // viva y se contará al salir de verdad. La rama seekTo de abajo NO cuenta
                    // aquí: su discontinuidad SEEK ya pasa por onPositionDiscontinuity.
                    val leavingId = controller.currentMediaItem?.mediaId
                    if (leavingId != null && leavingId != song.id) {
                        maybeRecordPlay(leavingId, controller.currentPosition)
                    }
                    val mediaItems = songs.map { it.toMediaItem() }
                    controller.setMediaItems(mediaItems, index, startPosition)
                } else if (controller.currentMediaItemIndex != index || startPosition > 0) {
                    controller.seekTo(index, startPosition)
                }
                controller.prepare()
                applyReplayGain(song)

                syncManager.prioritizeSong(song.id)
                networkRetryCount = 0

                if (autoPlay) {
                    controller.play()
                    _playbackState.value = PlaybackState.BUFFERING
                } else {
                    controller.pause()
                    _playbackState.value = PlaybackState.PAUSED
                }

                _currentPosition.value = startPosition
                Log.d(TAG, "Cargando: ${song.title} (AutoPlay=$autoPlay, queueSynced=$queueInSync)")
            } catch (e: Exception) {
                Log.e(TAG, "Error crítico al cargar canción en ExoPlayer", e)
                appLogger.error("PlayAt error: ${e.message}")
                // Un solo camino de recuperación: PlaybackErrorRecoveryUseCase (vía
                // PlaybackViewModel) decide retry/skip. Saltar de canción aquí además
                // competía con esa decisión (doble skip / skip durante un retry).
                _playbackState.value = PlaybackState.PAUSED
                _playbackError.tryEmit(PlaybackErrorInfo(-1, e.message ?: context.getString(R.string.error_playback_load)))
            }
        }
    }

    // === Gestión de Playlist ===

    fun setPlaylistAndPlay(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        songs.getOrNull(safeIndex)?.let { syncManager.prioritizeSong(it.id) }
        songCacheManager.cacheSongs(songs)
        playlistManager.setPlaylist(songs, safeIndex)
        playAt(safeIndex)
        saveSessionState()
    }

    /**
     * Reproduce [songs] en orden aleatorio DEJANDO el modo shuffle activado y conservando
     * el orden original. A diferencia de barajar la lista a mano (`songs.shuffled()`), esto
     * mantiene `isShuffleEnabled = true` (el botón de NowPlaying refleja el estado real) y
     * preserva `originalPlaylist`, para que al desactivar shuffle se restaure el orden real.
     * [startIndex] es el tema que sonará primero (setShuffle lo lleva al índice 0).
     */
    fun setPlaylistAndPlayShuffled(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        syncManager.prioritizeSong(songs[safeIndex].id)
        songCacheManager.cacheSongs(songs)
        playlistManager.setPlaylist(songs, safeIndex)
        playlistManager.setShuffle(true) // lleva songs[safeIndex] al índice 0 y baraja el resto
        playAt(playlistManager.currentIndex.value)
        saveSessionState()
    }

    fun setPlaylist(songs: List<Song>, startSong: Song, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        syncManager.prioritizeSong(startSong.id)
        songCacheManager.cacheSong(startSong)
        songCacheManager.cacheSongs(songs)
        playlistManager.setPlaylist(songs, safeIndex)
        playAt(safeIndex)
        saveSessionState()
    }

    fun cacheSongs(songs: List<Song>) {
        songCacheManager.cacheSongs(songs)
    }

    fun updateSong(newSong: Song) {
        val updated = playlistManager.updateSong(newSong)
        if (!updated) return

        val isCurrent = newSong.id == _currentSong.value?.id
        songCacheManager.cacheSong(newSong)

        if (isCurrent) {
            _currentSong.value = newSong
            // Logic for remote->local transition or URL refresh
            // Simplified: if current song path changed significantly, reload might be needed
            // But for now keeping it simple as per original logic's intent but cleaner
        }
    }

    /**
     * Pasa [songId] a STREAMING en la cola de ExoPlayer si es la canción en curso.
     *
     * Se llama justo ANTES de una re-descarga forzada: esa descarga BORRA el archivo local, y
     * si el player seguía apuntando al `file://` la reproducción se quedaba sin fuente (el
     * MediaItem no se recarga solo — `updateSong` solo toca el estado lógico). Al reemplazar
     * el item por su URI remota (`onedrive://<remoteId>`, que resuelve
     * `OneDriveResolvingDataSourceFactory` al abrir el stream), la canción sigue sonando por
     * red mientras se re-descarga.
     *
     * `replaceMediaItem` sobre el índice actual CONSERVA la posición de reproducción, así que
     * no hay salto ni corte. No hace nada si la canción no es la actual o no tiene `remoteId`
     * (una fuente LOCAL no se puede streamear: no hay de dónde).
     */
    fun switchCurrentToStreaming(songId: String): Boolean {
        val controller = mediaController ?: return false
        val current = _currentSong.value ?: return false
        if (current.id != songId) return false
        if (current.remoteId.isNullOrBlank()) return false
        if (!current.isLocalAudio) return false // ya está en streaming

        val streamingSong = current.copy(path = "")
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount) return false

        return try {
            controller.replaceMediaItem(index, streamingSong.toMediaItem())
            updateSong(streamingSong)
            true
        } catch (e: Exception) {
            appLogger.error("switchCurrentToStreaming falló: ${e.message}")
            false
        }
    }

    // === Control de Reproducción ===

    // El audio offload NO se configura acá. Su política (activarlo, detectar que el DSP del
    // dispositivo lo implementa mal y desactivarlo para siempre) vive ENTERA en
    // MusicPlaybackService: es el dueño del ExoPlayer real y el único que puede escuchar
    // `AudioOffloadListener` (el MediaController de acá habla por IPC y no lo expone).
    // Reafirmarlo desde acá en cada canción —como se hacía antes— PISARÍA el fallback del
    // watchdog en el siguiente tema, y el cuelgue volvería.

    fun playPause() {
        appLogger.playback("playPause() called")

        mediaController?.let { controller ->
            when {
                controller.isPlaying -> controller.pause()
                controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0 -> {
                    controller.prepare()
                    controller.play()
                }
                else -> controller.play()
            }
        }
    }

    fun seekTo(position: Long) {
        mediaController?.seekTo(position)
        _currentPosition.value = position
    }
    fun play() = mediaController?.play()
    fun pause() = mediaController?.pause()

    fun stop() {
        cancelSleepTimer()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _currentSong.value = null
        _playbackState.value = PlaybackState.IDLE
        _currentPosition.value = 0L
        _duration.value = 0L

        playlistManager.clear()
        sessionStateManager.clearSession()
    }

    /**
     * Saca de la cola (lógica y de ExoPlayer) todas las canciones de [sourceType]. Se usa al
     * desconectar una fuente: sus temas ya se borraron de la BD y dejarlos en la cola solo
     * difiere el fallo a cuando les llegue el turno. La reproducción de otras fuentes sigue
     * sin glitch; si lo que sonaba era de la fuente purgada, la cola queda EN PAUSA sobre el
     * siguiente superviviente (no se arranca otra música sin que el usuario lo pida). Sin
     * supervivientes equivale a [stop].
     */
    fun purgeSource(sourceType: SourceType) {
        val queue = playlistManager.getCurrentPlaylist()
        if (queue.none { it.sourceType == sourceType }) return
        if (queue.all { it.sourceType == sourceType }) {
            stop()
            return
        }

        val currentWasPurged = _currentSong.value?.sourceType == sourceType
        val removeIndices = queue.indices.filter { queue[it].sourceType == sourceType }

        // Primero la lista lógica (fuente de verdad): el listener de transición de ExoPlayer
        // resolverá los índices nuevos contra la lista ya purgada.
        playlistManager.removeSongs { it.sourceType == sourceType }

        val controller = mediaController
        if (controller != null && controller.isConnected && controller.mediaItemCount == queue.size) {
            // Pausar ANTES de quitar el item actual: al removerlo, ExoPlayer avanza solo al
            // siguiente y seguiría reproduciendo.
            if (currentWasPurged) controller.pause()
            // Espejo quirúrgico en la cola nativa (regla de oro: toda mutación de
            // PlaylistManager se replica en ExoPlayer), de mayor a menor índice.
            removeIndices.asReversed().forEach { controller.removeMediaItem(it) }
        } else if (controller != null && controller.isConnected) {
            // Cola nativa desincronizada con la lógica: recargarla entera ya purgada.
            playAt(playlistManager.currentIndex.value, autoPlay = false)
        }

        if (currentWasPurged) {
            _currentSong.value = playlistManager.getSongAt(playlistManager.currentIndex.value)
            _playbackState.value = PlaybackState.PAUSED
        }
        saveSessionState()
    }

    /**
     * Activa/desactiva shuffle re-ordenando la cola de ExoPlayer en consecuencia.
     * `PlaylistManager.setShuffle` deja la canción actual en index 0 (al activar) o
     * en su índice original (al desactivar); replicamos ese reordenamiento en
     * ExoPlayer manipulando `addMediaItems`/`removeMediaItems` sin tocar el item
     * que se está reproduciendo, para que no haya glitch de audio.
     */
    fun toggleShuffle() {
        playlistManager.setShuffle(!playlistManager.isShuffleEnabled.value)
        // Persistir: el flag y el nuevo orden de la cola son parte de la sesión (si no, al
        // reabrir la app el aleatorio volvía apagado aunque la cola siguiera barajada).
        saveSessionState()
        val controller = mediaController ?: return
        val songs = playlistManager.getCurrentPlaylist()
        val newIndex = playlistManager.currentIndex.value
        if (songs.isEmpty() || newIndex !in songs.indices) return

        val currentExoIndex = controller.currentMediaItemIndex
        val currentMediaId = controller.currentMediaItem?.mediaId
        val targetCurrentId = songs[newIndex].id

        if (currentMediaId != targetCurrentId || controller.mediaItemCount == 0) {
            // Caso poco probable: el item actual del player no coincide con el
            // current de PlaylistManager. Recarga completa segura.
            val pos = controller.currentPosition
            val wasPlaying = controller.isPlaying
            controller.setMediaItems(songs.map { it.toMediaItem() }, newIndex, pos)
            controller.prepare()
            if (wasPlaying) controller.play()
            return
        }

        // Reemplazamos los items DESPUÉS del actual con el nuevo orden.
        if (currentExoIndex + 1 < controller.mediaItemCount) {
            controller.removeMediaItems(currentExoIndex + 1, controller.mediaItemCount)
        }
        val after = songs.subList(newIndex + 1, songs.size).map { it.toMediaItem() }
        if (after.isNotEmpty()) controller.addMediaItems(after)

        // Reemplazamos los items ANTES del actual con el nuevo orden.
        if (currentExoIndex > 0) {
            controller.removeMediaItems(0, currentExoIndex)
        }
        val before = songs.subList(0, newIndex).map { it.toMediaItem() }
        if (before.isNotEmpty()) controller.addMediaItems(0, before)
    }
    
    fun toggleRepeatMode() {
        mediaController?.let { controller ->
            val nextMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = nextMode
            playlistManager.setRepeatMode(when (nextMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            })
        }
    }

    /**
     * Quita una canción de la cola por posición. Actualiza el orden lógico (`PlaylistManager`) y
     * replica el borrado en la cola nativa de ExoPlayer (`removeMediaItem` reajusta el
     * currentMediaItemIndex y, si cae la actual, avanza a la siguiente sin cortar la reproducción).
     */
    fun removeFromQueue(index: Int) {
        val queue = playlistManager.getCurrentPlaylist()
        if (index !in queue.indices) return
        playlistManager.removeAt(index)
        mediaController?.let { controller ->
            if (index in 0 until controller.mediaItemCount) {
                controller.removeMediaItem(index)
            }
        }
        saveSessionState()
    }

    fun reorderQueue(from: Int, to: Int) {
        val currentId = _currentSong.value?.id
        playlistManager.moveItem(from, to, currentId)

        // Replicar el movimiento en la cola nativa de ExoPlayer. Sin esto el
        // reorden es solo visual/lógico y next()/previous() (seekToNext/PrevMediaItem
        // nativos) iterarían el orden viejo. moveMediaItem reajusta el
        // currentMediaItemIndex automáticamente sin interrumpir la reproducción.
        mediaController?.let { controller ->
            val count = controller.mediaItemCount
            if (from in 0 until count && to in 0 until count) {
                controller.moveMediaItem(from, to)
            }
        }

        saveSessionState()
    }

    // === Sleep timer ===

    /**
     * Arranca (o reinicia) el temporizador de apagado. Al vencer, pausa la reproducción;
     * con [finishSong] deja terminar la canción en curso y pausa en la transición a la
     * siguiente. El delay corre en el scope Main del controller — si el proceso muere,
     * el timer muere con él (comportamiento aceptado: sin proceso no hay música que parar).
     */
    fun startSleepTimer(durationMs: Long, finishSong: Boolean) {
        if (durationMs <= 0) return
        sleepTimerJob?.cancel()
        val endAt = System.currentTimeMillis() + durationMs
        _sleepTimer.value = SleepTimerState(endAt, finishSong)
        sleepTimerJob = scope.launch {
            delay(durationMs)
            val controller = mediaController
            if (finishSong && controller?.isPlaying == true) {
                _sleepTimer.value = SleepTimerState(endAt, finishSong = true, awaitingSongEnd = true)
            } else {
                pauseFromSleepTimer()
            }
        }
        appLogger.playback("Sleep timer armado: ${durationMs / 60000} min (finishSong=$finishSong)")
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = null
    }

    private fun pauseFromSleepTimer() {
        appLogger.playback("Sleep timer vencido: pausando")
        mediaController?.pause()
        cancelSleepTimer()
    }

    // === ReplayGain ===

    /**
     * Ajusta `player.volume` según los tags ReplayGain de la canción dada, el modo
     * (OFF/TRACK/ALBUM) y el pre-amp configurados. `volume` solo atenúa (0.0–1.0),
     * que es justo lo que necesita ReplayGain (ganancias casi siempre negativas).
     * Sin tags → volumen 1.0 (no se toca).
     */
    private fun applyReplayGain(song: Song?) {
        val controller = mediaController ?: return
        val mode = musicPreferences.loadReplayGainMode()
        val preamp = musicPreferences.loadReplayGainPreamp()
        controller.volume = com.qhana.siku.data.model.ReplayGainCalculator
            .volumeFor(song?.replayGain, mode, preamp)
    }

    /** Recalcula el volumen del item actual. Llamar al cambiar el modo/pre-amp en Ajustes. */
    fun refreshReplayGain() = applyReplayGain(_currentSong.value)

    // === Listeners & Internals ===

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
            mediaController?.let { 
                _currentPosition.value = it.currentPosition 
                updateDurationSafe(it.duration)
                if (!isPlaying) saveSessionPosition()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Con cola nativa, REPEAT_MODE_ONE lo maneja el Player; aquí solo
                // atendemos el caso de final de cola sin repeat.
                if (playlistManager.repeatMode.value == RepeatMode.OFF) {
                    updatePlaybackState()
                }
                // Sleep timer en "terminar la canción" y la cola terminó sola: ya no hay
                // nada que pausar, solo limpiar el estado.
                if (_sleepTimer.value?.awaitingSongEnd == true) cancelSleepTimer()
                // Historial: el ÚLTIMO tema de la cola no dispara discontinuity al acabar
                // (no hay item siguiente) — se cuenta aquí. Si el usuario re-reproduce y
                // vuelve a llegar al final, cuenta otra vez: son dos escuchas reales.
                mediaController?.let { c ->
                    c.currentMediaItem?.mediaId?.let { maybeRecordPlay(it, c.currentPosition) }
                }
            }
            updatePlaybackState()
            if (playbackState == Player.STATE_READY) {
                mediaController?.let {
                    updateDurationSafe(it.duration)
                    _currentPosition.value = it.currentPosition
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Sincronizar estado interno con la cola nativa del Player.
            // Esta es la fuente de verdad ahora: cambios por end-of-song, notificación,
            // auto-advance, etc. son capturados aquí.
            val controller = mediaController ?: return
            val newIndex = controller.currentMediaItemIndex
            val songId = mediaItem?.mediaId ?: return

            // Sleep timer esperando el final de la canción: esta transición ES ese final
            // (auto-avance o skip manual, da igual — el usuario pidió parar aquí).
            if (_sleepTimer.value?.awaitingSongEnd == true) pauseFromSleepTimer()

            playlistManager.setCurrentIndex(newIndex)

            val song = songCacheManager.getSongSync(songId)
                ?: playlistManager.getSongAt(newIndex)
            if (song != null) {
                _currentSong.value = song
                applyReplayGain(song)
            }

            syncManager.prioritizeSong(songId)

            // Persistir índice+posición del nuevo tema: el auto-avance (fin de canción,
            // botón next de la notificación, etc.) NO pasa por setPlaylist*/reorder, así que
            // sin esto la sesión solo se guardaba al pausar y un kill del proceso durante la
            // reproducción restauraba un tema viejo. Es barato: no re-serializa la cola.
            saveSessionPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Historial de reproducción: una "escucha" se cuenta al SALIR de un item —
            // auto-avance (incluye repeat-one: mismo índice, posición = duración) o skip
            // manual a otro item. El umbral es la mitad de la canción o 4 minutos, lo que
            // llegue primero (regla estilo scrobble: un tema de 20 min no exige 10).
            val leftItem = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                (reason == Player.DISCONTINUITY_REASON_SEEK &&
                    oldPosition.mediaItemIndex != newPosition.mediaItemIndex)
            if (!leftItem) return
            val songId = oldPosition.mediaItem?.mediaId ?: return
            maybeRecordPlay(songId, oldPosition.positionMs)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Shuffle pedido desde un controller externo (Android Auto, Assistant, Wear).
            // Nuestro shuffle es un reordenamiento físico de la cola (PlaylistManager), no el
            // flag nativo: lo traducimos y devolvemos el flag a false. El false que seteamos
            // nosotros re-entra aquí y se ignora (early return), sin loop.
            if (!shuffleModeEnabled) return
            val controller = mediaController ?: return
            controller.shuffleModeEnabled = false
            if (!playlistManager.isShuffleEnabled.value) toggleShuffle()
        }

        override fun onPlayerError(error: PlaybackException) {
            appLogger.error("Player Error: code=${error.errorCode}, msg=${error.message}")

            listenerJob?.cancel()
            listenerJob = scope.launch(Dispatchers.Main) {
                when (error.errorCode) {
                    // Errores de red - reintentar con backoff exponencial
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        if (networkRetryCount < maxNetworkRetries) {
                            val delayMs = 2000L * (1L shl networkRetryCount.coerceAtMost(4))
                            Log.w(TAG, "Network error, retry ${networkRetryCount + 1}/$maxNetworkRetries in ${delayMs}ms")
                            networkRetryCount++
                            delay(delayMs)
                            retry()
                        } else {
                            Log.e(TAG, "Max network retries reached, skipping")
                            _playbackError.tryEmit(PlaybackErrorInfo(error.errorCode, "Error de red persistente"))
                            next()
                        }
                    }

                    // Archivo no encontrado - emite con código para que ErrorRecovery decida
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                        _playbackError.tryEmit(PlaybackErrorInfo(error.errorCode, "Archivo no encontrado"))
                    }

                    // Decoder error - emite con código para que ErrorRecovery marque corrupto
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> {
                        _playbackError.tryEmit(PlaybackErrorInfo(error.errorCode, "No se puede reproducir este formato"))
                    }

                    // Otros errores - emite y deja decidir al recovery
                    else -> {
                        _playbackError.tryEmit(PlaybackErrorInfo(error.errorCode, error.message ?: context.getString(R.string.error_playback_generic)))
                    }
                }
            }
        }
    }

    /**
     * Registra la escucha de [songId] si [playedMs] supera el umbral: mitad de la canción
     * o [PLAY_ABSOLUTE_THRESHOLD_MS], lo que llegue primero (criterio scrobbling clásico).
     * Con duración desconocida, [PLAY_UNKNOWN_DURATION_THRESHOLD_MS].
     */
    private fun maybeRecordPlay(songId: String, playedMs: Long) {
        val durationMs = songCacheManager.getSongSync(songId)?.duration ?: 0L
        val threshold = if (durationMs > 0) {
            minOf(durationMs / PLAY_HALF_DIVISOR, PLAY_ABSOLUTE_THRESHOLD_MS)
        } else {
            PLAY_UNKNOWN_DURATION_THRESHOLD_MS
        }
        if (playedMs < threshold) return
        scope.launch(Dispatchers.IO) {
            runCatching { musicRepository.recordPlay(songId, System.currentTimeMillis()) }
                .onFailure { appLogger.error("recordPlay falló: ${it.message}") }
        }
    }

    private fun retry() {
        val current = currentIndex.value
        if (current >= 0) {
            playAt(current, currentPosition.value, autoPlay = true)
        }
    }

    private fun updatePlaybackState() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            _playbackState.value = PlaybackState.PLAYING
            return
        }
        _playbackState.value = when (controller.playbackState) {
            Player.STATE_BUFFERING -> if (controller.playWhenReady) PlaybackState.BUFFERING else PlaybackState.PAUSED
            Player.STATE_IDLE -> PlaybackState.IDLE
            else -> PlaybackState.PAUSED
        }
    }

    /**
     * Guarda el estado de la sesión en background para no bloquear UI.
     * Captura los datos necesarios del mediaController en Main thread,
     * luego ejecuta la escritura en IO.
     */
    private fun saveSessionState() {
        // Capturar datos en Main thread (mediaController debe accederse aquí)
        val playlist = playlistManager.getCurrentPlaylist()
        val index = playlistManager.currentIndex.value
        val position = mediaController?.currentPosition ?: _currentPosition.value
        val shuffle = playlistManager.isShuffleEnabled.value
        val original = playlistManager.getOriginalPlaylist()

        // Ejecutar escritura en IO para no bloquear UI con listas grandes
        scope.launch(Dispatchers.IO) {
            sessionStateManager.saveSessionState(
                internalPlaylist = playlist,
                currentIndex = index,
                position = position,
                shuffleEnabled = shuffle,
                originalPlaylist = original
            )
        }
    }

    /**
     * Guarda SOLO índice+posición (sin re-serializar la cola). Para cuando la cola no
     * cambió, p.ej. al pausar: reescribir todos los IDs cada vez es trabajo inútil.
     */
    private fun saveSessionPosition() {
        val index = playlistManager.currentIndex.value
        val songId = playlistManager.getSongAt(index)?.id
        val position = mediaController?.currentPosition ?: _currentPosition.value
        scope.launch(Dispatchers.IO) {
            sessionStateManager.savePosition(index, position, songId)
        }
    }

    fun updatePosition() {
        mediaController?.let {
            if (it.isPlaying) {
                _currentPosition.value = it.currentPosition
                updateDurationSafe(it.duration)
            }
        }
    }
    
    fun release() {
        // Cancelar jobs en curso ANTES de tocar el controller para evitar callbacks
        // ejecutándose sobre estado liberado.
        loadJob?.cancel()
        listenerJob?.cancel()

        synchronized(initLock) {
            // Snapshot sincrónico de datos necesarios para persistir sesión
            val controller = mediaController
            val snapshotPlaylist = playlistManager.getCurrentPlaylist().toList()
            val snapshotIndex = playlistManager.currentIndex.value
            val snapshotPos = controller?.currentPosition ?: _currentPosition.value

            // Desuscribir listener antes de release para que no dispare callbacks tardíos
            controller?.removeListener(playerListener)

            if (controller?.isPlaying == true) {
                try { controller.pause() } catch (_: Exception) {}
            }

            // saveSessionState es sync (SharedPreferences.apply es async a disco),
            // así que podemos llamarlo directamente sin bloquear el release.
            sessionStateManager.saveSessionState(
                internalPlaylist = snapshotPlaylist,
                currentIndex = snapshotIndex,
                position = snapshotPos,
                shuffleEnabled = playlistManager.isShuffleEnabled.value,
                originalPlaylist = playlistManager.getOriginalPlaylist()
            )

            controllerFuture?.let {
                try { MediaController.releaseFuture(it) } catch (_: Exception) {}
            }
            mediaController = null
            controllerFuture = null
            _connectionState.value = false
        }
        // Cancelar scope al final; cualquier cosa pendiente fue cancelada arriba.
        scope.cancel()
    }

    fun getAudioSessionId(): Int = audioSessionId
    fun retryCurrentWithFreshUrl(updatedSong: Song) = updateSong(updatedSong)
    fun setBuffering() { _playbackState.value = PlaybackState.BUFFERING }

    private fun updateDurationSafe(newDuration: Long) {
        if (newDuration > 0) _duration.value = newDuration
    }

    /**
     * Convierte una `Song` a `MediaItem`.
     *
     * Para canciones remotas (streaming), usa el URI opaco `onedrive://<remoteId>`
     * que `OneDriveResolvingDataSourceFactory` interpreta al abrir el stream,
     * resolviendo la URL firmada real en ese instante. Esto evita el problema de
     * URLs expiradas que teníamos con el pre-fetch.
     *
     * Para canciones locales (file://, content://), usa el path tal cual.
     *
     * `setCustomCacheKey(id)` permite a `CacheDataSource` reusar el archivo cacheado
     * aunque la URL real cambie entre sesiones.
     */
    private fun Song.toMediaItem(): MediaItem {
        val rId = remoteId // Variable local para smart cast (Song está en :core)
        val targetUri = when {
            path.startsWith("file://") || path.startsWith("content://") -> uri
            !rId.isNullOrBlank() -> OneDriveResolvingDataSourceFactory.buildUri(rId)
            else -> uri
        }
        return MediaItem.Builder()
            .setUri(targetUri)
            .setMediaId(this.id)
            .setCustomCacheKey(this.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(this.title)
                    .setArtist(this.artist)
                    .setAlbumTitle(this.album)
                    .setArtworkUri(this.albumArtUri)
                    .build()
            )
            .build()
    }

}
