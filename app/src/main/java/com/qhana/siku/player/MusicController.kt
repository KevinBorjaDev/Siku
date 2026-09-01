package com.qhana.siku.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
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
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.LogLevel
import com.qhana.siku.player.manager.ListeningClock
import com.qhana.siku.player.manager.PlaylistManager
import com.qhana.siku.player.manager.SessionStateManager
import com.qhana.siku.player.manager.SongCacheManager
import com.qhana.siku.service.MusicPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
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

        /**
         * Aterrizar dentro de este margen desde el principio de un item significa que EMPEZÓ de
         * nuevo, y por tanto que su escucha vuelve a estar por contar (ver [playRecordedForSongId]).
         * Un segundo: cualquier reinicio real cae dentro y ningún avance normal lo hace.
         */
        private const val PLAY_RESTART_POSITION_MS = 1_000L

        // --- Reintentos ante error de red (ver [nextNetworkRetryDelayMs]) ---
        private const val MAX_NETWORK_RETRIES = 5
        private const val NETWORK_RETRY_BASE_MS = 2_000L
        // Tope del desplazamiento del backoff: la espera se dobla hasta BASE << este valor
        // (2s, 4s, 8s, 16s, 32s) y ahí se queda, aunque suban los reintentos máximos.
        private const val NETWORK_RETRY_MAX_SHIFT = 4

        /**
         * Capacidad del búfer de [playbackError]. Se emite con `tryEmit` (no suspende: sale de
         * callbacks del player), así que lo que no cabe se DESCARTA. Con capacidad 1 bastaba con
         * que el colector estuviera ocupado —y lo está: la recuperación hace red— para perder en
         * silencio el segundo error de una cascada, que es justo cuando hace falta. Con margen
         * para una cola entera fallando en ráfaga, ningún error se pierde.
         */
        private const val PLAYBACK_ERROR_BUFFER = 16

        /**
         * Espera máxima a que el `MediaController` se conecte al servicio. Defensivo: el
         * `Future` normalmente resuelve en milisegundos y solo se agota si el servicio no
         * llega a arrancar, caso en el que la conexión se da por fallida y se libera.
         */
        private const val CONTROLLER_CONNECT_TIMEOUT_SECONDS = 30L

        /**
         * Por debajo de esta posición, "anterior" salta al tema previo; por encima, reinicia el
         * actual. Es la convención de todos los reproductores: una vez entrado en la canción, lo
         * que se espera de "anterior" es volver a empezarla.
         */
        private const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val initLock = Any()
    @Volatile private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var mediaController: MediaController? = null

    // Estados observables delegados o locales
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    /**
     * Lo que el player dice de sí mismo. ÚNICO escritor: [updatePlaybackState], que lo LEE del
     * `MediaController` — nunca se dicta un estado a ciegas después de dar una orden.
     *
     * Que esto sea la única fuente es load-bearing. Antes convivían tres escritores (este, un
     * BUFFERING optimista escrito tras `play()` y otro que ponía el UseCase al empezar), y el
     * `MediaController` aplica los comandos de forma OPTIMISTA notificando en el acto: con el
     * player ya listo —archivo LOCAL, o un seek dentro de una cola ya cargada— el estado real
     * llegaba ANTES de la escritura optimista que lo pisaba, y después no volvía a haber ningún
     * evento que la desmintiera. Resultado: "cargando" congelado sobre una canción que sonaba,
     * hasta que el usuario tocaba pausa. Con streaming el buffering real siempre generaba un
     * evento posterior, y por eso el síntoma solo salía —y al azar— con música del dispositivo.
     */
    private val _playerState = MutableStateFlow(PlaybackState.IDLE)

    /**
     * Peticiones de reproducción en vuelo (ver [withPlaybackRequest]). Es un CONTADOR, no un
     * flag: las peticiones se anidan (`shuffleAllFromLibrary` envuelve a `playShuffled`).
     */
    private val _pendingPlayRequests = MutableStateFlow(0)

    /**
     * Estado que ve la UI: el del player, más el "cargando" de una petición que aún no llegó a
     * moverlo (resolver la URL, leer la cola de la BD — con streaming son segundos).
     *
     * El optimismo vive AQUÍ y no como una escritura suelta, y por eso no se puede quedar
     * pegado: dura exactamente lo que dura la petición que lo levantó ([withPlaybackRequest] lo
     * cierra en un `finally`), salga esta por donde salga. Si el player ya está sonando gana él,
     * así que pulsar la canción que ya suena no parpadea a "cargando".
     */
    val playbackState: StateFlow<PlaybackState> =
        combine(_playerState, _pendingPlayRequests) { state, pending ->
            if (pending > 0 && state != PlaybackState.PLAYING) PlaybackState.BUFFERING else state
        }.stateIn(scope, SharingStarted.Eagerly, PlaybackState.IDLE)

    private val _playbackError = MutableSharedFlow<PlaybackErrorInfo>(
        extraBufferCapacity = PLAYBACK_ERROR_BUFFER
    )
    val playbackError = _playbackError.asSharedFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /**
     * Hasta dónde tiene audio ya cargado el player. Solo dice algo en STREAMING (una canción en
     * disco está bufferizada por definición, y ExoPlayer devuelve la duración entera); la UI lo
     * pinta como tercer nivel de la barra para distinguir "cargando" de "colgado", que era
     * indistinguible con mala conexión.
     */
    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

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

    /**
     * Reintentos de red consumidos, y la canción a la que pertenecen. El presupuesto es POR
     * CANCIÓN: el contador se reinicia al fallar una distinta ([nextNetworkRetryDelayMs]) y al
     * empezar a sonar ([resetNetworkRetries]).
     *
     * Antes era un contador global reseteado ÚNICAMENTE en `playAt`, y el auto-avance no pasa
     * por ahí (va por `seekToNextMediaItem`, ver [next]): un bache de red que agotara los 5
     * reintentos lo dejaba saturado, así que el resto de la cola se saltaba al primer error sin
     * reintentar ni una vez —recorriendo la lista entera en segundos— hasta que el usuario
     * tocara una canción a mano. Atarlo a la canción y al éxito real lo cierra por construcción.
     */
    private var networkRetrySongId: String? = null
    private var networkRetryCount = 0

    /**
     * Canción cuya escucha YA se contó en la pasada actual, para que no se cuente dos veces.
     *
     * El último tema de la cola llega a contarse por DOS caminos que describen el mismo final:
     * `STATE_ENDED` (que existe porque ahí no hay item siguiente y por tanto no hay
     * discontinuidad) y, si después el usuario elige otra canción, la discontinuidad SEEK que
     * sale de él con `oldPosition.positionMs` = duración. Sin esta marca, cada fin de cola
     * seguido de una selección manual inflaba `playCount` en +1, que es justo lo que alimenta el
     * orden "Más escuchadas".
     *
     * Se limpia cuando el item EMPIEZA de nuevo (transición, o aterrizar dentro de
     * [PLAY_RESTART_POSITION_MS]), así que dos escuchas de verdad siguen contando dos veces.
     */
    private var playRecordedForSongId: String? = null

    /**
     * Cuánto se escuchó DE VERDAD de la canción en curso. Ver [ListeningClock]: la posición del
     * reproductor no sirve para esto, y hasta el 30 ago 2026 era lo que se guardaba.
     */
    private val listeningClock = ListeningClock()

    // === Sleep timer ===

    /**
     * Estado del temporizador de apagado. [awaitingSongEnd] indica que el plazo ya venció pero se
     * espera el final de la canción en curso ([finishSong]) para pausar.
     *
     * **[endAtMs] está en el reloj MONÓTONO** (`SystemClock.elapsedRealtime`), no en wall-clock, y
     * por eso no se puede formatear como una hora ni persistir: solo sirve para restarle otra
     * lectura del mismo reloj, que es lo único que hace la UI. Con `currentTimeMillis` —lo que
     * había— una corrección de hora del sistema a mitad de la cuenta (un NTP tras un rato sin red,
     * lo normal al volver de un vuelo) movía el restante que se enseña sin mover el `delay` que de
     * verdad pausa: la hoja anunciaba veinte minutos que ya no quedaban, o llegaba a cero con la
     * música sonando. Mismo criterio que [com.qhana.siku.player.manager.ListeningClock].
     */
    data class SleepTimerState(
        val endAtMs: Long,
        val finishSong: Boolean,
        val awaitingSongEnd: Boolean = false
    )

    private val _sleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleepTimer.asStateFlow()
    private var sleepTimerJob: Job? = null

    // === Canciones descargadas mientras están en la cola ===

    /**
     * Ids cuyo `MediaItem` sigue apuntando al origen REMOTO porque la canción terminó de
     * descargarse mientras sonaba. Se corrigen al salir de ellas (ver [onMediaItemTransition]):
     * hacerlo en caliente cambia el URI del item, lo que obliga a ExoPlayer a construir un
     * `MediaSource` nuevo — corta el audio y lo reinicia desde cero, que es precisamente lo que
     * no puede pasar por una mejora invisible para quien está escuchando.
     */
    private val pendingItemRefresh = ConcurrentHashMap.newKeySet<String>()

    init {
        // La descarga es lo único que cambia una canción por debajo de la cola: `PlaylistManager`
        // guarda los `Song` capturados al armarla, así que sin esto la hoja de cola sigue diciendo
        // "se transmitirá" para un archivo que ya está en el dispositivo — mientras el NowPlaying,
        // que sí lee la fila de la BD, dice lo contrario. Ver un estado y su opuesto en la misma
        // pantalla es lo que empuja a reintentar acciones que ya no hacen falta.
        scope.launch {
            syncManager.downloadedSongs.collect { song -> onSongDownloaded(song) }
        }
        // Canciones que salen de la biblioteca: fuera de la cola también. La cola guarda los
        // `Song` capturados al armarla, así que sin esto una canción borrada seguía sonando —y
        // en local ni siquiera falla, porque el archivo sigue en el dispositivo.
        scope.launch {
            musicRepository.songsDeleted.collect { ids -> purgeSongs(ids) }
        }
    }

    /**
     * Una canción de la cola acabó de descargarse: su fila ya apunta al archivo local.
     *
     * Se hacen DOS cosas distintas, y la separación importa. Actualizar la lista lógica es
     * gratis y arregla la UI de la cola al instante para todas, incluida la que suena. Reemplazar
     * el `MediaItem` es lo que evita que ExoPlayer vuelva a transmitirla por red al llegarle el
     * turno (el resolver decide por el esquema del URI, no consulta la BD), pero no es gratis
     * sobre el item en curso — por eso ese se aplaza.
     */
    private fun onSongDownloaded(song: Song) {
        val index = updateSong(song)
        if (index < 0) return // no está en la cola actual: nada que sincronizar

        val controller = mediaController ?: return
        if (!controller.isConnected) return

        if (controller.currentMediaItemIndex == index) {
            pendingItemRefresh.add(song.id)
            return
        }
        replaceQueueItem(controller, index, song)
    }

    /**
     * Sustituye el `MediaItem` de [index] por el de [song]. Comprueba el `mediaId` antes de
     * escribir: entre el aviso de descarga y este punto la cola pudo reordenarse o recargarse, y
     * escribir a ciegas en una posición pondría la canción equivocada en mitad de la cola.
     */
    private fun replaceQueueItem(controller: MediaController, index: Int, song: Song) {
        try {
            if (index >= controller.mediaItemCount) return
            if (controller.getMediaItemAt(index).mediaId != song.id) return
            controller.replaceMediaItem(index, song.toMediaItem())
        } catch (e: Exception) {
            // Cosmético para el player: si falla, la canción se sigue reproduciendo por su URI
            // remoto y la cola se corrige la próxima vez que se cargue entera.
            appLogger.error("No se pudo actualizar el item ${song.id} de la cola: ${e.message}")
        }
    }

    /**
     * Aplica los reemplazos aplazados de [pendingItemRefresh] ahora que la canción ya no está en
     * curso. [nowPlayingId] se salta: con repeat-one la transición vuelve al mismo item, que
     * sigue sonando.
     */
    private fun flushPendingItemRefresh(controller: MediaController, nowPlayingId: String) {
        if (pendingItemRefresh.isEmpty()) return
        val songs = playlistManager.getCurrentPlaylist()
        for (id in pendingItemRefresh.toList()) {
            if (id == nowPlayingId) continue
            pendingItemRefresh.remove(id)
            val idx = songs.indexOfFirst { it.id == id }
            if (idx >= 0) replaceQueueItem(controller, idx, songs[idx])
        }
    }

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

    /**
     * La sesión se cayó: el servicio murió (`onTaskRemoved`, o el sistema lo mató por memoria)
     * mientras el proceso de la UI sigue vivo.
     *
     * Sin esto la conexión quedaba muerta PARA SIEMPRE: `controllerFuture` no volvía a `null` en
     * el camino de éxito, así que [initialize] se salía por "conexión ya en curso" y
     * `connectionState` seguía diciendo `true` — el player no volvía a sonar (y los widgets, que
     * esperan esa señal, pasaban la barrera con un controller muerto) hasta matar el proceso.
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            appLogger.controller("MediaController DESCONECTADO: la sesión se cayó")
            synchronized(initLock) {
                // Solo si es el vigente: una desconexión tardía del anterior no puede tirar la
                // conexión nueva que ya se levantó en su lugar.
                if (mediaController !== controller) return
                discardControllerLocked()
            }
        }
    }

    /**
     * Suelta el `MediaController` actual y deja el estado listo para reconectar. Debe llamarse
     * con [initLock] tomado.
     */
    private fun discardControllerLocked() {
        mediaController?.removeListener(playerListener)
        mediaController = null
        controllerFuture?.let {
            try { MediaController.releaseFuture(it) } catch (_: Exception) {}
        }
        controllerFuture = null
        _connectionState.value = false
    }

    fun initialize() {
        synchronized(initLock) {
            val existing = mediaController
            if (existing != null) {
                if (existing.isConnected) {
                    appLogger.controller("initialize() skipped: MediaController already connected")
                    return
                }
                // Controller presente pero muerto: se descarta para poder reconstruirlo. La
                // guarda de abajo daría por buena una conexión que ya no existe.
                discardControllerLocked()
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
                .setListener(controllerListener)
                .buildAsync()
            controllerFuture = future

            future.addListener({
                try {
                    val controller = future.get(
                        CONTROLLER_CONNECT_TIMEOUT_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
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

                updatePlaybackState()
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
            resumeIfIdle(controller)
        }
    }

    fun previous() {
        val controller = mediaController ?: return
        val pos = controller.currentPosition
        if (pos > PREVIOUS_RESTARTS_AFTER_MS) {
            controller.seekTo(0L)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        }
        resumeIfIdle(controller)
    }

    /**
     * Devuelve el player a la vida si un error lo dejó en `STATE_IDLE`.
     *
     * `onPlayerError` deja al player IDLE pero CONSERVANDO la cola, y en ese estado un seek solo
     * mueve el índice: no se prepara ni suena nada. Sin esto, el salto del recovery (y el botón
     * "siguiente" de la notificación tras un fallo) cambiaba de canción en la pantalla y dejaba
     * silencio, porque la única rama del reproductor que preparaba era la de [playPause].
     *
     * IDLE con cola solo se da tras un error o antes del primer `prepare()`: un player pausado
     * está en READY, así que esto no arranca música que el usuario hubiera parado.
     */
    private fun resumeIfIdle(controller: MediaController) {
        if (controller.playbackState != Player.STATE_IDLE) return
        if (controller.mediaItemCount == 0) return
        controller.prepare()
        controller.play()
        updatePlaybackState()
    }

    /**
     * UI optimista del TAP: publica la canción elegida en el MISMO frame del gesto, ANTES de la
     * preparación asíncrona (caché → URL → cola) que corre el use case. Es el mismo "UI optimista"
     * que ya hace [playAt], adelantado al único instante que el usuario ve.
     *
     * Sin esto, tocar una canción en una lista abría el reproductor (el caller expande en ese
     * mismo frame) con la canción ANTERIOR, y el cambio llegaba a mitad del slide cuando la
     * preparación terminaba: la key del shared element de la carátula lleva el id, así que el
     * morph abortaba su match en pleno vuelo y la portada nueva aparecía como un flash.
     *
     * Como los colectores del uiState corren con `Main.immediate`, el estado del NowPlaying queda
     * sembrado de forma síncrona antes de que el caller llegue a expandir el reproductor. Escribir
     * aquí no rompe el principio de un solo escritor: `_currentSong` es del controller y todos los
     * caminos de reproducción ([playAt] incluido) convergen después en el mismo valor.
     */
    fun announceSelection(song: Song) {
        _currentSong.value = song
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
        // `immediate`: llamado desde el hilo principal —que es de donde viene siempre, ver los
        // `withContext(Main)` del UseCase— el cuerpo corre YA, no en el siguiente mensaje del
        // looper. Así, cuando el llamador recupera el control, el player tiene la orden dada y
        // el estado ya está reconciliado; posponerlo dejaba una ventana en la que el estado
        // "real" todavía era el de la canción anterior.
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            val controller = mediaController?.takeIf { it.isConnected } ?: run {
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

                // Ya estamos exactamente aquí (pulsar en la cola la canción que suena). Se sale
                // ANTES de tocar `_currentPosition`: ninguna de las ramas de abajo movería el
                // audio —no hay seek que hacer— pero la posición se reseteaba a 0 igual, así que
                // la barra saltaba al inicio mientras la canción seguía sonando tan tranquila.
                JankProbe.mark { "playAt: inSync=$queueInSync cola=${songs.size} idx=$index" }
                if (queueInSync && controller.currentMediaItemIndex == index && startPosition == 0L) {
                    if (autoPlay && !controller.isPlaying) controller.play()
                    updatePlaybackState()
                    syncManager.prioritizeSong(song.id)
                    return@launch
                }

                if (!queueInSync) {
                    // Historial: recargar la cola descarta el item en curso SIN discontinuidad
                    // AUTO/SEEK (reason REMOVE), así que la escucha interrumpida se cuenta acá.
                    // Misma canción (retry, restore de sesión) se excluye: esa escucha sigue
                    // viva y se contará al salir de verdad. La rama seekTo de abajo NO cuenta
                    // aquí: su discontinuidad SEEK ya pasa por onPositionDiscontinuity.
                    val leavingId = controller.currentMediaItem?.mediaId
                    if (leavingId != null && leavingId != song.id) {
                        maybeRecordPlay(leavingId)
                    }
                    val mediaItems = songs.map { it.toMediaItem() }
                    controller.setMediaItems(mediaItems, index, startPosition)
                    JankProbe.mark { "playAt: setMediaItems(${mediaItems.size}) hecho" }
                } else if (controller.currentMediaItemIndex != index || startPosition > 0) {
                    controller.seekTo(index, startPosition)
                    JankProbe.mark { "playAt: seekTo hecho" }
                }
                controller.prepare()
                applyReplayGain(song)

                syncManager.prioritizeSong(song.id)
                resetNetworkRetries()

                if (autoPlay) controller.play() else controller.pause()
                // Se LEE lo que quedó, no se dicta (ver [_playerState]).
                updatePlaybackState()

                _currentPosition.value = startPosition
                // Sin este reset, la barra de la canción nueva arranca enseñando el búfer de la
                // anterior hasta el primer tick.
                _bufferedPosition.value = startPosition
                Log.d(TAG, "Cargando: ${song.title} (AutoPlay=$autoPlay, queueSynced=$queueInSync)")
            } catch (e: Exception) {
                Log.e(TAG, "Error crítico al cargar canción en ExoPlayer", e)
                appLogger.error("PlayAt error: ${e.message}")
                // Un solo camino de recuperación: PlaybackErrorRecoveryUseCase (vía
                // PlaybackViewModel) decide retry/skip. Saltar de canción aquí además
                // competía con esa decisión (doble skip / skip durante un retry).
                updatePlaybackState()
                // El detalle de la excepción ya se logueó; al usuario, mensaje localizado genérico.
                _playbackError.tryEmit(
                    PlaybackErrorInfo(-1, context.getString(R.string.error_playback_load), song.id)
                )
            }
        }
    }

    // === Gestión de Playlist ===

    /**
     * [startPosition] y [autoPlay] existen para RESTAURAR una cola tal y como estaba, no para
     * reproducir algo nuevo: los usa el "deshacer" de vaciar la cola, que tiene que devolver
     * también el punto de la canción y si estaba sonando o en pausa. Con los defaults (0 y `true`)
     * se comporta como siempre, que es lo que quiere cualquier "reproducir esto".
     */
    fun setPlaylistAndPlay(
        songs: List<Song>,
        startIndex: Int = 0,
        startPosition: Long = 0,
        autoPlay: Boolean = true
    ) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        songs.getOrNull(safeIndex)?.let { syncManager.prioritizeSong(it.id) }
        songCacheManager.cacheSongs(songs)
        playlistManager.setPlaylist(songs, safeIndex)
        playAt(safeIndex, startPosition, autoPlay)
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

    /**
     * Reemplaza una canción en el estado LÓGICO (cola, caché y, si es la actual, `currentSong`).
     * No toca la cola de ExoPlayer: quien necesite eso debe replicarlo explícitamente, porque
     * cambiar el URI del item en curso reinicia la reproducción.
     *
     * [locationChanged] lo pone quien SABE que el archivo local dejó de existir (re-descarga
     * forzada, reparación de un error): sin él, el caché conserva el `file://` que ya conocía y
     * resucita el path de un archivo borrado. Ver [SongCacheManager.cacheSongLocation].
     *
     * @return su posición en la cola, o -1 si no está.
     */
    fun updateSong(newSong: Song, locationChanged: Boolean = false): Int {
        val index = playlistManager.updateSong(newSong)
        if (index < 0) return -1

        if (locationChanged) {
            songCacheManager.cacheSongLocation(newSong)
        } else {
            songCacheManager.cacheSong(newSong)
        }
        if (newSong.id == _currentSong.value?.id) _currentSong.value = newSong
        return index
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
            // El archivo local está a punto de borrarlo la re-descarga: el caché NO debe
            // conservar su `file://`.
            updateSong(streamingSong, locationChanged = true)
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
        _playerState.value = PlaybackState.IDLE
        _currentPosition.value = 0L
        _duration.value = 0L
        _bufferedPosition.value = 0L

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
    fun purgeSource(sourceType: SourceType) = purgeWhere { it.sourceType == sourceType }

    /**
     * Saca de la cola las canciones [ids] que acaban de borrarse de la biblioteca.
     *
     * Lo dispara el aviso de borrado del repositorio ([ISongRepository.songsDeleted]), así que
     * cubre por igual quitar una carpeta local, apagar el escaneo del dispositivo, la
     * reconciliación de un scan o desconectar la nube. Hace falta especialmente en LOCAL: ahí
     * los archivos no se borran (son `content://` del dispositivo), así que una canción retirada
     * de la biblioteca seguía sonando tan tranquila desde la cola —y el MiniPlayer dejaba
     * escucharla entera— en vez de fallar como haría una de la nube.
     */
    fun purgeSongs(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val doomed = ids.toHashSet()
        purgeWhere { it.id in doomed }
    }

    private fun purgeWhere(shouldPurge: (Song) -> Boolean) {
        val queue = playlistManager.getCurrentPlaylist()
        if (queue.none(shouldPurge)) return
        if (queue.all(shouldPurge)) {
            stop()
            return
        }

        val currentWasPurged = _currentSong.value?.let(shouldPurge) == true
        val removeIndices = queue.indices.filter { shouldPurge(queue[it]) }

        // Primero la lista lógica (fuente de verdad): el listener de transición de ExoPlayer
        // resolverá los índices nuevos contra la lista ya purgada.
        playlistManager.removeSongs(shouldPurge)

        val controller = mediaController
        if (controller != null && controller.isConnected && controller.mediaItemCount == queue.size) {
            // Pausar ANTES de quitar el item actual: al removerlo, ExoPlayer avanza solo al
            // siguiente y seguiría reproduciendo.
            if (currentWasPurged) controller.pause()
            // Espejo quirúrgico en la cola nativa (regla de oro: toda mutación de
            // PlaylistManager se replica en ExoPlayer), de mayor a menor índice.
            removeIndices.asReversed().forEach { controller.removeMediaItem(it) }
        } else if (controller != null && controller.isConnected) {
            // Cola nativa desincronizada con la lógica: recargarla entera ya purgada. Si lo que
            // suena NO se purgó, la recarga debe ser transparente —misma posición y sin cortar el
            // audio—, como hace la rama equivalente de [toggleShuffle]; forzar pausa y volver al
            // segundo 0 detenía la música por un borrado que no le incumbía.
            val resumePosition = if (currentWasPurged) 0L else controller.currentPosition
            val wasPlaying = !currentWasPurged && controller.isPlaying
            playAt(
                playlistManager.currentIndex.value,
                startPosition = resumePosition,
                autoPlay = wasPlaying
            )
        }

        if (currentWasPurged) {
            _currentSong.value = playlistManager.getSongAt(playlistManager.currentIndex.value)
            updatePlaybackState()
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

        // Quitar el ÚLTIMO item deja la cola vacía, que es exactamente lo que significa [stop]
        // (mismo desenlace que purgar la cola entera). Antes se seguía por el camino normal y
        // `saveSessionState` se saltaba la escritura por lista vacía, así que en disco quedaba la
        // sesión ANTERIOR: al reiniciar el proceso resucitaba una cola que el usuario había
        // vaciado, con `currentSong` señalando una canción que ya no estaba.
        if (queue.size == 1) {
            stop()
            return
        }

        playlistManager.removeAt(index)
        mediaController?.let { controller ->
            if (index in 0 until controller.mediaItemCount) {
                controller.removeMediaItem(index)
            }
        }
        saveSessionState()
    }

    /**
     * Encola [songs] al FINAL de la cola actual. Dos casos:
     *  - **Cola vacía** (nada sonando): no hay a qué "añadir", así que equivale a reproducir esos
     *    temas ([setPlaylistAndPlay] arranca la cola).
     *  - **Cola con contenido**: anexa detrás de todo, saltando lo que ya está por id (el id es la
     *    clave única de la cola). Replica el anexado en la cola nativa de ExoPlayer para que
     *    `next()`/`seekToNextMediaItem` los alcancen, y persiste la sesión.
     *
     * Devuelve cuántas se añadieron de verdad (0 = todas estaban ya en la cola).
     */
    fun addToQueue(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0

        // Cola vacía: no hay dónde "encolar" — arranca la reproducción con estos temas.
        if (playlistManager.getCurrentPlaylist().isEmpty()) {
            setPlaylistAndPlay(songs)
            return songs.size
        }

        val added = playlistManager.addToQueue(songs)
        if (added.isEmpty()) return 0

        songCacheManager.cacheSongs(added)

        // Espejo en ExoPlayer (regla de oro: toda mutación de PlaylistManager se replica en la cola
        // nativa). Se anexa al final, así que no toca el item en curso ni corta el audio.
        val controller = mediaController
        if (controller != null && controller.isConnected) {
            val sizeAfter = playlistManager.getCurrentPlaylist().size
            if (controller.mediaItemCount == sizeAfter - added.size) {
                controller.addMediaItems(added.map { it.toMediaItem() })
            } else {
                // Cola nativa desincronizada con la lógica: recargarla entera ya actualizada,
                // conservando posición y sin cortar (misma técnica que la rama equivalente de
                // [toggleShuffle]).
                playAt(
                    playlistManager.currentIndex.value,
                    startPosition = controller.currentPosition,
                    autoPlay = controller.isPlaying
                )
            }
        }

        saveSessionState()
        return added.size
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
        // Reloj MONÓTONO: es el mismo del que sale el `delay` de abajo, así que lo que se enseña y
        // lo que ocurre no pueden separarse. Ver [SleepTimerState.endAtMs].
        val endAt = SystemClock.elapsedRealtime() + durationMs
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

    /**
     * Espera del siguiente reintento de red para [songId], o `null` si esa canción ya agotó su
     * presupuesto. Cambiar de canción reinicia el presupuesto: un tema no hereda los fallos del
     * anterior. Backoff exponencial desde [NETWORK_RETRY_BASE_MS] con tope [NETWORK_RETRY_MAX_SHIFT].
     */
    private fun nextNetworkRetryDelayMs(songId: String?): Long? {
        if (songId != networkRetrySongId) {
            networkRetrySongId = songId
            networkRetryCount = 0
        }
        if (networkRetryCount >= MAX_NETWORK_RETRIES) return null
        val delayMs = NETWORK_RETRY_BASE_MS * (1L shl networkRetryCount.coerceAtMost(NETWORK_RETRY_MAX_SHIFT))
        networkRetryCount++
        return delayMs
    }

    /**
     * Devuelve el presupuesto de reintentos a cero. Se llama ante ÉXITO REAL (el player llegó a
     * STATE_READY) y al cargar por [playAt]: un contador que solo baja cuando el usuario toca
     * algo acaba saturado y deja de proteger.
     */
    private fun resetNetworkRetries() {
        networkRetrySongId = null
        networkRetryCount = 0
    }

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
            JankProbe.mark { "player state=$playbackState (2=BUFFERING 3=READY)" }
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
                    c.currentMediaItem?.mediaId?.let { maybeRecordPlay(it) }
                }
            }
            updatePlaybackState()
            if (playbackState == Player.STATE_READY) {
                // El player tiene audio listo: esto es el ÉXITO que devuelve el presupuesto de
                // reintentos a cero. Sin un reset atado al éxito, el contador solo bajaba con
                // una acción explícita del usuario y quedaba saturado indefinidamente.
                resetNetworkRetries()
                mediaController?.let {
                    updateDurationSafe(it.duration)
                    _currentPosition.value = it.currentPosition
                    _bufferedPosition.value = it.bufferedPosition
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            JankProbe.mark { "onMediaItemTransition reason=$reason" }
            // Sincronizar estado interno con la cola nativa del Player.
            // Esta es la fuente de verdad ahora: cambios por end-of-song, notificación,
            // auto-advance, etc. son capturados aquí.
            val controller = mediaController ?: return
            val newIndex = controller.currentMediaItemIndex
            val songId = mediaItem?.mediaId ?: return

            // Sleep timer esperando el final de la canción: esta transición ES ese final
            // (auto-avance o skip manual, da igual — el usuario pidió parar aquí).
            if (_sleepTimer.value?.awaitingSongEnd == true) pauseFromSleepTimer()

            // El búfer del tema anterior no dice nada del nuevo: se pone a cero y el primer
            // tick lo vuelve a llenar (ver [bufferedPosition]).
            _bufferedPosition.value = 0L

            // La POSICIÓN tampoco, y hasta ahora se quedaba con la del tema anterior. El bucle
            // que la refresca (`MusicPlayerScreen`) es un `delay(1000)` de FASE LIBRE, sin
            // relación con los eventos del player, así que en un auto-avance el título, la
            // carátula y el color del álbum cambiaban en ESTE frame mientras el anillo del mini
            // (y la barra del NowPlaying) seguían enseñando el final de la canción anterior
            // hasta el siguiente tick: hasta un segundo de desfase, el que reportó el usuario.
            //
            // No lo tapaba ninguno de los dos caminos que sí escriben posición: `playAt` no
            // participa en un auto-avance (la cola entera vive en ExoPlayer) y `STATE_READY` no
            // se vuelve a emitir cuando el item siguiente ya estaba bufferizado, que es el caso
            // normal. Se LEE del controller en vez de escribir 0 porque un cambio de item
            // también puede aterrizar en mitad de la pista (repeat-one, seek entre items).
            _currentPosition.value = controller.currentPosition.coerceAtLeast(0L)

            // Empieza un item (otro, o el mismo con repeat-one): su escucha vuelve a estar por
            // contar. Ver [playRecordedForSongId].
            playRecordedForSongId = null
            // Y su tiempo escuchado vuelve a cero. Va DESPUÉS de que la discontinuidad haya
            // contado la canción saliente (ExoPlayerImpl notifica en ese orden), y aun así el
            // acumulador va atado al id para no depender de ello. Ver [ListeningClock].
            listeningClock.restart(songId, playing = controller.isPlaying)

            playlistManager.setCurrentIndex(newIndex)

            // Canciones que se descargaron mientras sonaban: ya no están en curso, así que
            // ahora su MediaItem puede apuntar al archivo local sin interrumpir nada.
            flushPendingItemRefresh(controller, nowPlayingId = songId)

            val song = songCacheManager.getSongSync(songId)
                ?: playlistManager.getSongAt(newIndex)
            if (song != null) {
                _currentSong.value = song
                applyReplayGain(song)
            }

            // La duración es el DENOMINADOR de la fracción que pinta el anillo y la barra, así
            // que arrastrar la del tema anterior desplaza el progreso aunque la posición ya sea
            // correcta: tras una canción larga, una corta arrancaría con el arco casi vacío y al
            // revés se pasaría de vuelta. `controller.duration` puede ser TIME_UNSET si el item
            // todavía no está preparado (streaming), y por eso `updateDurationSafe` ignora lo que
            // no sea positivo; en ese caso vale la de la BD, que ya está en la mano y es la misma
            // que la UI lleva enseñando en la lista.
            updateDurationSafe(controller.duration.takeIf { it > 0 } ?: song?.duration ?: 0L)

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
            if (leftItem) {
                oldPosition.mediaItem?.mediaId?.let { maybeRecordPlay(it) }
            }
            // Aterrizar al principio de un item = ese item empieza de nuevo, así que su escucha
            // vuelve a estar por contar. Cubre el caso que `onMediaItemTransition` no ve: volver a
            // dar play sobre la MISMA canción que acaba de terminar (o rebobinarla al inicio).
            if (newPosition.positionMs < PLAY_RESTART_POSITION_MS) {
                playRecordedForSongId = null
                // Su tiempo también: si no, rebobinar al inicio empezaría la segunda escucha con
                // lo de la primera ya sumado y la contaría de inmediato.
                listeningClock.restart(newPosition.mediaItem?.mediaId, playing = mediaController?.isPlaying == true)
            }
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

            // La canción que falló se captura AHORA, con el error en la mano: para cuando el
            // colector procese el evento, el item en curso puede ser otro.
            val failedSongId = mediaController?.currentMediaItem?.mediaId ?: _currentSong.value?.id

            listenerJob?.cancel()
            listenerJob = scope.launch(Dispatchers.Main) {
                when (error.errorCode) {
                    // Errores de red - reintentar con backoff exponencial
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        val delayMs = nextNetworkRetryDelayMs(failedSongId)
                        if (delayMs != null) {
                            Log.w(TAG, "Network error, retry $networkRetryCount/$MAX_NETWORK_RETRIES in ${delayMs}ms")
                            delay(delayMs)
                            retry()
                        } else {
                            // Solo se EMITE: quien salta es el recovery (vía PlaybackViewModel),
                            // que además decide si antes toca sanar. Llamar aquí a next() sumaba
                            // un segundo salto al del recovery —se perdían dos canciones— y
                            // movía el item en curso antes de que el colector leyera el evento,
                            // así que la reparación caía sobre la canción siguiente.
                            Log.e(TAG, "Max network retries reached for $failedSongId")
                            _playbackError.tryEmit(
                                PlaybackErrorInfo(
                                    error.errorCode,
                                    context.getString(R.string.error_playback_network_persistent),
                                    failedSongId
                                )
                            )
                        }
                    }

                    // Archivo no encontrado - emite con código para que ErrorRecovery decida
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                        _playbackError.tryEmit(
                            PlaybackErrorInfo(
                                error.errorCode,
                                context.getString(R.string.error_playback_file_not_found),
                                failedSongId
                            )
                        )
                    }

                    // Decoder error - emite con código para que ErrorRecovery marque corrupto
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> {
                        _playbackError.tryEmit(
                            PlaybackErrorInfo(
                                error.errorCode,
                                context.getString(R.string.error_playback_format_unsupported),
                                failedSongId
                            )
                        )
                    }

                    // Otros errores - emite y deja decidir al recovery
                    else -> {
                        // El `error.message` de ExoPlayer es texto técnico en inglés; al usuario le
                        // llega un mensaje genérico localizado (el detalle ya se logueó arriba).
                        _playbackError.tryEmit(
                            PlaybackErrorInfo(
                                error.errorCode,
                                context.getString(R.string.error_playback_generic),
                                failedSongId
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Registra la escucha de [songId] si lo ESCUCHADO supera el umbral: mitad de la canción
     * o [PLAY_ABSOLUTE_THRESHOLD_MS], lo que llegue primero (criterio scrobbling clásico).
     * Con duración desconocida, [PLAY_UNKNOWN_DURATION_THRESHOLD_MS].
     *
     * **Ya no recibe la posición**: la mide [listeningClock], que acumula solo lo
     * que de verdad sonó. Antes cada call site pasaba la posición del reproductor, y con eso saltar
     * una canción arrastrando la barra hasta el final la contaba como escuchada entera. El umbral
     * no cambió; lo que cambió es que ahora se compara contra lo escuchado y no contra dónde quedó
     * la aguja, que es lo que el criterio scrobbling siempre quiso decir.
     */
    private fun maybeRecordPlay(songId: String) {
        if (playRecordedForSongId == songId) return // ya contada en esta pasada
        val playedMs = listeningClock.of(songId)
        val durationMs = songCacheManager.getSongSync(songId)?.duration ?: 0L
        val threshold = if (durationMs > 0) {
            minOf(durationMs / PLAY_HALF_DIVISOR, PLAY_ABSOLUTE_THRESHOLD_MS)
        } else {
            PLAY_UNKNOWN_DURATION_THRESHOLD_MS
        }
        if (playedMs < threshold) return
        playRecordedForSongId = songId
        scope.launch(Dispatchers.IO) {
            // `playedMs` viaja hasta la BD desde la v28: es el tiempo escuchado del log de eventos.
            // Hasta entonces se usaba solo para comparar con el umbral y se descartaba.
            runCatching { musicRepository.recordPlay(songId, System.currentTimeMillis(), playedMs) }
                .onFailure { appLogger.error("recordPlay falló: ${it.message}") }
        }
    }

    private fun retry() {
        val current = currentIndex.value
        if (current >= 0) {
            playAt(current, currentPosition.value, autoPlay = true)
        }
    }

    /** Único escritor de [_playerState]: lo LEE del player. Ver el KDoc de [_playerState]. */
    private fun updatePlaybackState() {
        val controller = mediaController ?: return
        // El reloj de escucha se gobierna AQUÍ y no en cada sitio que pausa o reanuda, por lo
        // mismo que este método es el único escritor del estado: "¿está sonando?" es una sola
        // pregunta, y contestarla en varios sitios es cómo un tramo se queda abierto para siempre.
        // Cubre también lo que no es un toque del usuario (foco de audio perdido, auriculares
        // desenchufados, un error), que es justo lo que nadie se acuerda de instrumentar.
        if (controller.isPlaying) {
            listeningClock.start(controller.currentMediaItem?.mediaId)
            _playerState.value = PlaybackState.PLAYING
            return
        }
        // BUFFERING no cuenta como escuchado: la canción no avanza y no se oye nada.
        listeningClock.pause()
        _playerState.value = when (controller.playbackState) {
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
            // El búfer se refresca AUNQUE no esté sonando: el momento en que más importa verlo
            // avanzar es justo el que no cuenta como reproducción (parado esperando datos).
            _bufferedPosition.value = it.bufferedPosition
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

            discardControllerLocked()
        }
        // Cancelar scope al final; cualquier cosa pendiente fue cancelada arriba.
        scope.cancel()
    }

    fun getAudioSessionId(): Int = audioSessionId

    /**
     * Reintenta [updatedSong] después de un fallo de reproducción, con su origen ya refrescado
     * (URL firmada nueva, o `file://` que pasó a streaming porque el archivo no estaba).
     *
     * Hace las TRES cosas que hacen falta, y antes solo hacía la primera:
     *
     * 1. Estado lógico ([updateSong]), para que la cola y el NowPlaying vean el origen nuevo.
     * 2. Reconstruir el `MediaItem`. El `mediaId` es el id de la canción y NO cambia al cambiar
     *    el origen, así que el item de la cola sigue apuntando al URI muerto y [playAt] lo daría
     *    por sincronizado: hay que reemplazarlo a mano.
     * 3. `prepare()`. Tras `onPlayerError` el player queda en `STATE_IDLE` CONSERVANDO la cola, y
     *    en ese estado no hay seek ni transición que lo despierte. Sin esto el "reintento" se
     *    quedaba en cambiar el estado lógico: la pantalla decía otra cosa y el audio seguía mudo
     *    hasta que el usuario pulsaba play (la única rama que preparaba).
     */
    fun retryCurrentWithFreshUrl(updatedSong: Song) {
        // `locationChanged`: la reparación pudo BORRAR el archivo local (`cleanupLocalFile`), así
        // que el path que llega es la verdad y no se puede fusionar con el `file://` viejo.
        val index = updateSong(updatedSong, locationChanged = true)
        if (index < 0) return

        val controller = mediaController?.takeIf { it.isConnected } ?: return
        if (index >= controller.mediaItemCount) return
        if (controller.getMediaItemAt(index).mediaId != updatedSong.id) return

        try {
            controller.replaceMediaItem(index, updatedSong.toMediaItem())

            // Solo se reanuda si lo que falló SIGUE siendo el item en curso: si el usuario ya se
            // movió a otra canción, la cola queda con el origen fresco para cuando le toque, pero
            // arrancar audio aquí le arrebataría la que acaba de elegir.
            if (controller.currentMediaItemIndex != index) return

            controller.prepare()
            controller.play()
            updatePlaybackState()
        } catch (e: Exception) {
            appLogger.error("retryCurrentWithFreshUrl falló para ${updatedSong.id}: ${e.message}")
        }
    }
    /**
     * Ejecuta [block] como una PETICIÓN DE REPRODUCCIÓN: mientras dura, la UI ve "cargando"
     * (salvo que el player ya esté sonando). Cubre el trabajo previo a la orden —resolver la URL
     * firmada, leer la cola de la BD—, que con streaming son segundos de pantalla muerta.
     *
     * Sustituye a un `setBuffering()` suelto que había que acordarse de cerrar en cada salida.
     * No se cerraba: bastaba con pulsar la canción que ya sonaba —donde no hay nada que ordenarle
     * al player, así que este no emite NADA— para dejar el "cargando" clavado hasta que el
     * usuario tocara pausa. Aquí el cierre está en el `finally`, así que vale para toda salida
     * presente y futura: return temprano, excepción o cancelación.
     */
    suspend fun <T> withPlaybackRequest(block: suspend () -> T): T {
        _pendingPlayRequests.update { it + 1 }
        try {
            return block()
        } finally {
            _pendingPlayRequests.update { it - 1 }
        }
    }

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
