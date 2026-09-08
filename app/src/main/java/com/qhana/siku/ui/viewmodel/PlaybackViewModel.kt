package com.qhana.siku.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.imageLoader
import com.qhana.siku.R
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.coordinator.WorkerStatus
import com.qhana.siku.data.lyrics.FailureReason
import com.qhana.siku.data.lyrics.LyricsSaveResult
import com.qhana.siku.data.model.EqProfile
import com.qhana.siku.data.model.EqSettings
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.ILyricsRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.data.repository.LyricsCandidatesResult
import com.qhana.siku.data.repository.LyricsResult
import com.qhana.siku.data.util.NetworkManager
import com.qhana.siku.data.util.LrcSyntax
import com.qhana.siku.domain.usecase.ParseLyricsUseCase
import com.qhana.siku.domain.usecase.PlaybackErrorRecoveryUseCase
import com.qhana.siku.domain.usecase.MusicPlaybackUseCase
import com.qhana.siku.player.MusicController
import com.qhana.siku.player.PlaybackCoordinator
import com.qhana.siku.player.audio.EqCurve
import com.qhana.siku.player.audio.EqualizerAudioProcessor
import java.util.concurrent.TimeUnit
import com.qhana.siku.ui.components.EqPresets
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.state.LyricsSaveUiState
import com.qhana.siku.ui.state.NowPlayingUiState
import com.qhana.siku.worker.DownloadScheduler
import com.qhana.siku.worker.WorkerTags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * ViewModel dedicado a la reproducción y UI de NowPlaying.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    val musicController: MusicController,
    private val artworkRepository: ArtworkRepository,
    private val lyricsRepository: ILyricsRepository,
    private val localLyricsReader: com.qhana.siku.data.lyrics.LocalLyricsReader,
    private val lyricsWriter: com.qhana.siku.data.lyrics.LyricsWriter,
    private val repository: IMusicRepository,
    private val parseLyricsUseCase: ParseLyricsUseCase,
    private val playbackErrorRecoveryUseCase: PlaybackErrorRecoveryUseCase,
    private val playbackUseCase: MusicPlaybackUseCase,
    private val musicPreferences: MusicPreferences,
    private val playbackCoordinator: PlaybackCoordinator,
    private val requestCoordinator: RequestCoordinator,
    private val networkManager: NetworkManager,
    private val authManager: AuthManager,
    private val downloadScheduler: DownloadScheduler,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager,
    private val syncManager: com.qhana.siku.data.coordinator.SyncManager,
    private val equalizerProcessor: com.qhana.siku.player.audio.EqualizerAudioProcessor,
    private val audioRouteMonitor: com.qhana.siku.player.audio.AudioRouteMonitor,
    private val eqProfileManager: com.qhana.siku.player.audio.EqProfileManager,
    private val clarity: com.qhana.siku.player.audio.clarity.Clarity,
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager
) : ViewModel() {

    companion object {
        private const val TAG = "PlaybackViewModel"
        private const val MAX_RETRIES = 1

        /**
         * Muestreo del medidor de reducción del limitador. 60 ms ≈ 16 lecturas/s: suficiente para
         * que el medidor se vea vivo y por debajo de lo que el ojo distingue como saltos. No tiene
         * nada que ver con el hilo de audio, que actualiza el valor por bloque.
         */
        private const val GAIN_REDUCTION_POLL_MS = 60L
        // TTL para reintentar buscar letras tras un NotFound persistido. Pasado este
        // tiempo asumimos que algún colaborador pudo subirlas a LrcLib.
        private const val LYRICS_NOT_FOUND_RETRY_TTL_MS = 14L * 24 * 60 * 60 * 1000


        /**
         * Cadencia máxima de los badges de descarga ([getDownloadStatusFlow]).
         *
         * Es `sample` y no `debounce` a propósito: durante un sync estas fuentes NO callan
         * (el `workerStatus` alterna por petición, los `WorkInfo` transicionan), y `debounce`
         * se reinicia con cada emisión → inanición. Un badge que llega hasta 200 ms tarde no
         * tiene coste visual.
         */
        private const val DOWNLOAD_STATUS_SAMPLE_MS = 200L

        /**
         * Margen que se le da a un reintento para EMPEZAR a sonar ([monitorPlaybackRecovery]).
         *
         * Si se agota se salta la canción, pero NO se marca corrupta: "no arrancó a tiempo"
         * puede ser red lenta o un búfer largo, y un diagnóstico permanente por un síntoma
         * transitorio dejaba canciones sanas marcadas para siempre. Generoso por eso mismo
         * (ver la convención 12: los timeouts defensivos no son estimaciones de duración).
         */
        private const val RECOVERY_START_TIMEOUT_MS = 15_000L
    }

    private val retryCount = AtomicInteger(0)
    /** Canción a la que pertenece [retryCount] (ver [nextRetryFor]). */
    private var lastErrorSongId: String? = null
    private var preloadJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null

    // Resolución del acento del tema (ver [resolveAlbumColors]). Único escritor de
    // `albumColors` fuera de la siembra por cambio de canción y del override manual.
    private var colorJob: kotlinx.coroutines.Job? = null
    private var colorSongId: String? = null

    private val _nowPlayingUiState = MutableStateFlow(NowPlayingUiState())
    val nowPlayingUiState: StateFlow<NowPlayingUiState> = _nowPlayingUiState.asStateFlow()

    // El feedback de red y del refresh de letras ahora va por el SnackbarManager centralizado
    // (bus singleton), no por eventos por-instancia colectados en MainActivity.

    // Los fallos de reproducción van por el SnackbarManager ([showPlaybackError]). Antes vivían
    // en dos StateFlow (`error` y `loadingStatus`) que NINGUNA pantalla observaba: se escribían
    // en catorce sitios y no se leían en ninguno, así que saltarse una canción dañada era
    // completamente silencioso. `loadingStatus` además arrastraba el mismo defecto que el
    // "cargando" del player —la rama de healing lo dejaba puesto sin nada que lo cerrara—, listo
    // para reaparecer el día que alguien lo conectara a la UI.

    private val _keepScreenOn = MutableStateFlow(musicPreferences.loadKeepScreenOn())
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    // Fondo del NowPlaying (degradado, sólido o carátula desenfocada). Observado desde DataStore
    // porque el ajuste se cambia en Ajustes (LibraryViewModel, otra instancia) y debe reflejarse
    // en vivo aquí.
    val nowPlayingBackground: StateFlow<NowPlayingBackground> =
        musicPreferences.nowPlayingBackgroundFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingBackground())

    /** Barra de progreso ondulada del NowPlaying (mismo motivo de observación que el fondo). */
    val nowPlayingWavyProgress: StateFlow<Boolean> =
        musicPreferences.nowPlayingWavyProgressFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingWavyProgress())

    /** Grosor de la barra de progreso, en dp (mismo motivo de observación que el fondo). */
    val nowPlayingProgressThickness: StateFlow<Int> =
        musicPreferences.nowPlayingProgressThicknessFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingProgressThickness())

    /** Handle permanente en la barra de progreso (mismo motivo de observación que el fondo). */
    val nowPlayingProgressHandle: StateFlow<Boolean> =
        musicPreferences.nowPlayingProgressHandleFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingProgressHandle())

    /** Forma del MiniPlayer: rectángulo redondeado vs píldora (mismo motivo de observación). */
    val miniPlayerRoundedRect: StateFlow<Boolean> =
        musicPreferences.miniPlayerRoundedRectFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadMiniPlayerRoundedRect())

    /** Forma del botón de play del MiniPlayer: círculo vs squircle (mismo motivo de observación). */
    val miniPlayerRoundPlayButton: StateFlow<Boolean> =
        musicPreferences.miniPlayerRoundPlayButtonFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadMiniPlayerRoundPlayButton())

    /**
     * Pestañas de la biblioteca abajo. Aquí no se usa para dibujar nada: lo lee la CAPA del
     * reproductor para saber cuánto tiene que apartarse la píldora sobre la barra.
     */
    val libraryBottomTabs: StateFlow<Boolean> =
        musicPreferences.libraryBottomTabsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadLibraryBottomTabs())

    /**
     * Estilo moderno de navegación. Aquí tampoco se dibuja nada con él: lo lee la CAPA del
     * reproductor porque con el estilo moderno SIEMPRE hay barra abajo, o sea que entra en el
     * mismo cálculo de cuánto se aparta la píldora (ver `libraryChrome`).
     */
    val libraryModernNav: StateFlow<Boolean> =
        musicPreferences.libraryModernNavFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadLibraryModernNav())

    /**
     * Ficha técnica en el chip de formato. Se observa del DataStore (no un MutableStateFlow local)
     * porque el ajuste tiene DOS escritores: este chip y el switch de Ajustes → Apariencia.
     */
    val nowPlayingDetailedFormat: StateFlow<Boolean> =
        musicPreferences.nowPlayingDetailedFormatFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadNowPlayingDetailedFormat())

    /** Tap sobre el chip de formato: conmuta la ficha técnica y lo persiste. */
    fun toggleDetailedFormat() =
        musicPreferences.saveNowPlayingDetailedFormat(!nowPlayingDetailedFormat.value)

    /** Gestos del reproductor (mismo motivo de observación que el fondo: se cambian en Ajustes). */
    val playerGestures: StateFlow<Boolean> =
        musicPreferences.playerGesturesFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadPlayerGestures())

    /**
     * Estilo de paleta del tema (nombre del enum `PaletteStyle`). Lo consume MainActivity, que
     * es quien monta `MusicPlayerTheme`; se observa del DataStore para que el cambio hecho en
     * Ajustes se vea al instante y sin recrear la Activity.
     */
    val themePaletteStyle: StateFlow<String> =
        musicPreferences.themePaletteStyleFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadThemePaletteStyle())

    val currentSong: StateFlow<Song?> = musicController.currentSong

    /**
     * Estado de reproducción PARA LA UI: como el del controller, salvo que **BUFFERING solo se
     * enseña cuando el audio viene por STREAMING**; con el archivo en el dispositivo (descargado o
     * de la fuente local) se muestra como PLAYING.
     *
     * Es el MISMO criterio que ya aplica la barra de progreso al nivel de búfer (`showBuffer` en
     * NowPlayingProgress: solo con `PlaybackOrigin.STREAMING`, "con un archivo en disco no informa de
     * nada"), llevado al glifo de carga: ese indicador existe por la red. Un FLAC local que ExoPlayer
     * tarda en abrir no está "cargando" en el sentido que el spinner comunica — **medido con la sonda
     * (16 ago), 170-240 ms de BUFFERING**, y en ese lapso —justo dentro del container transform de
     * apertura— la UI componía DOS `LoadingIndicator` (píldora y botón de play; cada uno construye
     * siete `Morph` entre MaterialShapes en su primera composición), arrancaba sus dos
     * `AnimatedContent` y recomponía el NowPlaying entero dos veces (BUFFERING y luego PLAYING). Todo
     * para un spinner que parpadeaba ~100 ms sin decir nada.
     *
     * Mostrar PLAYING en ese lapso es FIEL: `MusicController.updatePlaybackState` solo reporta
     * BUFFERING con `playWhenReady`, o sea que la intención es reproducir; y la UI ya trataba
     * BUFFERING como "sonando" en todo lo que no es el glifo (`isPlayingOrBuffering`). No hay umbral
     * de tiempo: el criterio es el ORIGEN, no la duración, así que con streaming el spinner aparece
     * exactamente como antes.
     *
     * `isLocalAudio` de la canción anunciada, y no `PlaybackOrigin`, porque lo que decide si hay red
     * de por medio es dónde está el archivo, no de qué fuente vino (una de OneDrive ya descargada
     * suena desde disco igual que una local).
     */
    val playbackState: StateFlow<PlaybackState> =
        combine(musicController.playbackState, musicController.currentSong) { state, song ->
            if (state == PlaybackState.BUFFERING && song?.isLocalAudio == true) PlaybackState.PLAYING
            else state
        }.stateIn(viewModelScope, SharingStarted.Eagerly, musicController.playbackState.value)
    val isShuffleEnabled: StateFlow<Boolean> = musicController.isShuffleEnabled
    val repeatMode: StateFlow<RepeatMode> = musicController.repeatMode
    val currentPosition: StateFlow<Long> = musicController.currentPosition
    val duration: StateFlow<Long> = musicController.duration
    val bufferedPosition: StateFlow<Long> = musicController.bufferedPosition
    val playlist: StateFlow<List<Song>> = musicController.playlist
    val currentIndex: StateFlow<Int> = musicController.currentIndex
    val sleepTimer: StateFlow<MusicController.SleepTimerState?> = musicController.sleepTimer

    fun startSleepTimer(minutes: Int, finishSong: Boolean) =
        musicController.startSleepTimer(TimeUnit.MINUTES.toMillis(minutes.toLong()), finishSong)

    fun cancelSleepTimer() = musicController.cancelSleepTimer()

    // --- Ecualizador ---
    // El toggle se PERSISTE y lo aplica MusicPlaybackService (observa eqEnabledFlow y
    // reconstruye la pipeline). Las ganancias y el nº de bandas van EN VIVO al processor
    // singleton (audibles al instante; cambiar 5↔10 no exige rebuild) y se persisten al
    // soltar el slider (commitEqGains) o al alternar el modo.

    // Observado del flow (no un MutableStateFlow local): Ajustes puede apagar el EQ propio
    // desde OTRA instancia de ViewModel (al activar "usar EQ del sistema") y la hoja debe
    // reflejarlo.
    val eqEnabled: StateFlow<Boolean> = musicPreferences.eqEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadEqEnabled())

    private val _eqBandCount = MutableStateFlow(musicPreferences.loadEqBandCount())
    val eqBandCount: StateFlow<Int> = _eqBandCount.asStateFlow()

    private val _eqGains = MutableStateFlow(
        musicPreferences.loadEqBandGains(musicPreferences.loadEqBandCount()).toList()
    )
    val eqGains: StateFlow<List<Float>> = _eqGains.asStateFlow()

    // Refuerzos de graves/agudos: dos peakings anchos ADITIVOS sobre la curva del EQ (no se
    // compensan a propósito). Ver el kdoc de EqualizerAudioProcessor.
    private val _eqBassBoost = MutableStateFlow(musicPreferences.loadEqBassBoost())
    val eqBassBoost: StateFlow<Float> = _eqBassBoost.asStateFlow()

    private val _eqTrebleBoost = MutableStateFlow(musicPreferences.loadEqTrebleBoost())
    val eqTrebleBoost: StateFlow<Float> = _eqTrebleBoost.asStateFlow()

    // Centro de cada refuerzo, elegible por el usuario dentro de los rangos del processor. Mover
    // el centro cambia el pico de la curva, así que alimentan el headroom igual que las ganancias.
    private val _eqBassFreq = MutableStateFlow(
        musicPreferences.loadEqBassFreq() ?: EqualizerAudioProcessor.BASS_BOOST_FREQ_DEFAULT_HZ
    )
    val eqBassFreq: StateFlow<Double> = _eqBassFreq.asStateFlow()

    private val _eqTrebleFreq = MutableStateFlow(
        musicPreferences.loadEqTrebleFreq() ?: EqualizerAudioProcessor.TREBLE_BOOST_FREQ_DEFAULT_HZ
    )
    val eqTrebleFreq: StateFlow<Double> = _eqTrebleFreq.asStateFlow()

    // Preamp: ganancia global (solo negativa) previa a los filtros. Es la alternativa LINEAL al
    // limitador — distorsión cero, pero cuesta volumen siempre. Sigue siendo MANUAL: lo que se
    // descartó dos veces es el auto-preamp que corrige por detrás, no el control.
    private val _eqPreamp = MutableStateFlow(musicPreferences.loadEqPreamp())
    val eqPreamp: StateFlow<Float> = _eqPreamp.asStateFlow()

    // Limitador, default APAGADO (ver el kdoc de MusicPreferences.saveEqLimiterEnabled: la app
    // informa y no corrige por detrás).
    //
    // Observado del flow, como [eqEnabled] y por lo mismo: es un toggle que se persiste en el
    // acto, así que las preferencias PUEDEN ser su única verdad. Con un MutableStateFlow por
    // instancia de ViewModel, la instancia que no recibió el toque conservaba el valor viejo.
    val eqLimiterEnabled: StateFlow<Boolean> = musicPreferences.eqLimiterEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadEqLimiterEnabled())

    // Umbral MANUAL en dBFS (la posición del slider). El que se aplica de verdad puede ser otro:
    // ver [eqLimiterThresholdDb].
    private val _eqLimiterThreshold = MutableStateFlow(
        musicPreferences.loadEqLimiterThreshold()
            ?: EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB
    )

    /** Ver [eqLimiterEnabled]: toggle de persistencia inmediata, así que sale del flow. */
    val eqLimiterThresholdAuto: StateFlow<Boolean> = musicPreferences.eqLimiterThresholdAutoFlow
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            musicPreferences.loadEqLimiterThresholdAuto()
        )

    /**
     * Reducción de ganancia del limitador para el medidor de la hoja, muestreada.
     *
     * Flow FRÍO a propósito: solo corre mientras alguien lo colecta (la hoja del EQ abierta), así
     * que no gasta nada durante la reproducción normal. Es la excepción legítima a "esperar por
     * señal, no por intervalo": el hilo de audio escribe un `@Volatile` miles de veces por segundo
     * y no hay ninguna señal que esperar — un medidor es por definición un muestreo.
     *
     * Ese mismo hecho —saber cuándo hay alguien mirando— es lo que se le comunica al processor con
     * `addMeterObserver`/`removeMeterObserver`: con el limitador apagado, el detector true-peak que
     * alimenta este número no tiene otro cliente, y en el hilo de audio cuesta del orden de la
     * cascada de biquads entera. El `onCompletion` cubre también la cancelación (cerrar la hoja
     * cancela el colector, no lo completa), que es como termina este flow siempre.
     */
    val eqGainReductionDb: Flow<Float> = flow {
        while (true) {
            emit(equalizerProcessor.gainReductionDb)
            kotlinx.coroutines.delay(GAIN_REDUCTION_POLL_MS)
        }
    }
        .onStart { equalizerProcessor.addMeterObserver() }
        .onCompletion { equalizerProcessor.removeMeterObserver() }

    /**
     * Los dos centros en un solo flow. Existe por una restricción del lenguaje, no de diseño:
     * `combine` solo tiene sobrecarga tipada hasta 5 flows y el headroom necesita 6. Agrupar aquí
     * es más legible que anidar dos `combine` o caer en la variante `vararg`, que colapsaría los
     * tipos a `Array<Any>`.
     */
    private val eqBoostFreqs: Flow<Pair<Double, Double>> =
        combine(_eqBassFreq, _eqTrebleFreq) { bass, treble -> bass to treble }

    /**
     * Pico en dB de la CURVA (bandas + refuerzos), SIN el preamp. Se separa del headroom que se
     * muestra porque de este número sale el preamp sugerido, y meter el preamp aquí lo volvería
     * circular (sugerir −pico sobre un pico que ya incluye el preamp).
     */
    private val eqCurvePeakDb: StateFlow<Float> = combine(
        _eqGains, _eqBandCount, _eqBassBoost, _eqTrebleBoost, eqBoostFreqs
    ) { gains, bandCount, bass, treble, freqs ->
        EqCurve.peakGainDb(
            gains.toFloatArray(),
            EqualizerAudioProcessor.bandsFor(bandCount),
            bass,
            treble,
            freqs.first,
            freqs.second
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * Umbral EFECTIVO del limitador: el que se aplica al processor y el que muestra el slider.
     *
     * En AUTOMÁTICO es 0 dBFS FIJO ([EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB]), o sea el
     * limitador como pura red de seguridad: no toca nada hasta que la señal de verdad llegaría al
     * tope, y con el detector true-peak eso protege también del recorte inter-muestra del códec.
     *
     * Estuvo atado al pico de la curva (−pico) hasta el 29 jul, y ese era el error: convertía el
     * limitador en un compresor que engancha SIEMPRE que hay curva, o sea corregir el nivel por
     * detrás — exactamente lo que el proyecto rechazó dos veces en el preamp. Si alguien quiere ese
     * comportamiento, el slider manual sigue ahí y la decisión se ve. Como efecto secundario, este
     * flow ya no depende de [eqCurvePeakDb]: el umbral dejó de moverse solo al tocar una banda.
     */
    val eqLimiterThresholdDb: StateFlow<Float> = combine(
        eqLimiterThresholdAuto, _eqLimiterThreshold
    ) { auto, manual ->
        EqualizerAudioProcessor.effectiveLimiterThresholdDb(auto, manual)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB
    )

    /**
     * Headroom REAL que se muestra: lo que la curva gana MENOS lo que el preamp baja. Es
     * INFORMATIVO — no se corrige por detrás; la hoja lo enseña para que el usuario vea cuándo se
     * está pasando, que es justo lo que faltaba cuando los refuerzos de julio sonaron a ruido.
     *
     * Sigue midiendo la ganancia de la CURVA y no el nivel de salida: no sabe a qué nivel está
     * masterizada la canción (ver la limitación anotada en EqCurve.peakGainDb). Lo que sí cambió
     * es que ahora hay dos formas de bajarlo — el preamp, que se ve aquí, y el limitador, que
     * actúa solo cuando de verdad haría falta.
     */
    val eqHeadroomDb: StateFlow<Float> = combine(eqCurvePeakDb, _eqPreamp) { peak, preamp ->
        peak + preamp
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * Ruta de salida activa. La hoja del EQ la necesita para no prometer un headroom que en
     * Bluetooth puede no existir: ahí el volumen absoluto anula la atenuación digital del mixer,
     * que es de donde salía todo el margen en el que se apoyaba el diseño anterior.
     */
    val audioRoute: StateFlow<com.qhana.siku.player.audio.AudioRoute> = audioRouteMonitor.route

    // --- Clarity (exciter armonico) ---

    val clarityEnabled: StateFlow<Boolean> = musicPreferences.clarityEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadClarityEnabled())

    /**
     * La cantidad es el unico que lleva copia local: es un slider y se escribe en cada frame de
     * arrastre, asi que persistir por frame seria absurdo. Se confirma al soltar.
     */
    private val _clarityGain = MutableStateFlow(musicPreferences.loadClarityGain())
    val clarityGain: StateFlow<Float> = _clarityGain.asStateFlow()

    /**
     * Enciende o apaga el exciter: se aplica y se persiste de una, igual que el limitador. No pasa
     * por el servicio (al reves que `setEqEnabled`) porque no cambia si la pipeline esta activa —
     * Clarity vive DENTRO del processor y solo suena con el ecualizador encendido.
     */
    fun setClarityEnabled(enabled: Boolean) {
        clarity.setEnabled(enabled)
        musicPreferences.saveClarityEnabled(enabled)
    }

    fun setClarityGain(db: Float) {
        clarity.setGainDb(db)
        _clarityGain.value = clarity.getGainDb()
    }

    fun commitClarityGain() = musicPreferences.saveClarityGain(_clarityGain.value)

    /** Preferencia de Ajustes: el botón EQ del NowPlaying abre el panel del sistema. */
    val useSystemEq: StateFlow<Boolean> = musicPreferences.useSystemEqFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadUseSystemEq())

    /** Config del toolbar (orden + barra/overflow). La barra del NowPlaying la observa en vivo. */
    val toolbarConfig: StateFlow<List<com.qhana.siku.data.model.ToolbarActionState>> =
        musicPreferences.toolbarConfigFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadToolbarConfig())

    fun setEqEnabled(enabled: Boolean) = musicPreferences.saveEqEnabled(enabled)

    // "No volver a mostrar" del aviso de doble ecualización al encender el EQ propio.
    // MutableStateFlow respaldado por la preferencia: la hoja debe reaccionar en la misma
    // sesión si el usuario lo marca (no basta con leer la preferencia al componer).
    private val _eqConflictWarningSuppressed =
        MutableStateFlow(musicPreferences.loadEqConflictWarningSuppressed())
    val eqConflictWarningSuppressed: StateFlow<Boolean> = _eqConflictWarningSuppressed.asStateFlow()

    fun suppressEqConflictWarning() {
        _eqConflictWarningSuppressed.value = true
        musicPreferences.saveEqConflictWarningSuppressed(true)
    }

    /**
     * Alterna 5↔10 bandas. Si la curva ACTUAL coincide con un preset (de fábrica o propio), ese
     * preset "sigue" al nuevo modo re-interpolado (y se persiste como curva de ese modo), para que
     * el selector siga marcándolo. Si es una curva manual, se recupera la propia guardada del modo
     * destino (cada modo recuerda la suya: las frecuencias centrales no se corresponden).
     */
    fun setEqBandCount(count: Int) {
        if (count == _eqBandCount.value) return
        val followed = followPresetGains(count)
        val gains = followed ?: musicPreferences.loadEqBandGains(count)
        equalizerProcessor.setBands(EqualizerAudioProcessor.bandsFor(count), gains)
        _eqBandCount.value = count
        _eqGains.value = gains.toList()
        musicPreferences.saveEqBandCount(count)
        // Solo persistimos si venimos siguiendo un preset (así no pisamos la curva manual del modo).
        if (followed != null) musicPreferences.saveEqBandGains(count, gains)
    }

    /**
     * Si la curva actual coincide con alguna guardada, la devuelve re-interpolada a [targetCount];
     * si no, null (curva manual). Cubre los presets de fábrica y los perfiles propios — y aquí SÍ vale
     * mirar solo la curva de un perfil, porque lo único que se está resolviendo es cómo llevar la
     * forma al otro modo de bandas: el resto de sus parámetros no dependen del número de bandas.
     */
    private fun followPresetGains(targetCount: Int): FloatArray? {
        val current = _eqGains.value
        val oldCount = _eqBandCount.value
        fun matches(g: FloatArray) = current.size == g.size &&
            current.indices.all {
                kotlin.math.abs(current[it] - g[it]) < EqualizerAudioProcessor.GAIN_MATCH_EPSILON_DB
            }

        EqPresets.ALL.forEach { p ->
            if (matches(EqPresets.gainsFor(p, oldCount))) return EqPresets.gainsFor(p, targetCount)
        }
        eqPresets.profiles.value.forEach { p ->
            val freqs = EqualizerAudioProcessor.bandsFor(p.bandCount)
            if (matches(EqPresets.resample(p.gains, freqs, oldCount)))
                return EqPresets.resample(p.gains, freqs, targetCount)
        }
        return null
    }

    fun setEqBand(band: Int, db: Float) {
        equalizerProcessor.setBandGain(band, db)
        _eqGains.update { gains -> gains.mapIndexed { i, g -> if (i == band) db else g } }
    }

    /** Curva completa de una vez (presets): en vivo + persistida. */
    fun setEqGains(gains: FloatArray) {
        equalizerProcessor.setBandGains(gains)
        _eqGains.value = gains.toList()
        musicPreferences.saveEqBandGains(_eqBandCount.value, gains)
    }

    fun commitEqGains() =
        musicPreferences.saveEqBandGains(_eqBandCount.value, _eqGains.value.toFloatArray())

    /** Refuerzo de graves EN VIVO; se persiste al soltar ([commitEqBoosts]). */
    fun setEqBassBoost(db: Float) {
        equalizerProcessor.setBassBoost(db)
        _eqBassBoost.value = db
    }

    /** Refuerzo de agudos EN VIVO; se persiste al soltar ([commitEqBoosts]). */
    fun setEqTrebleBoost(db: Float) {
        equalizerProcessor.setTrebleBoost(db)
        _eqTrebleBoost.value = db
    }

    /** Centro del refuerzo de graves EN VIVO; se persiste al soltar ([commitEqBoosts]). */
    fun setEqBassFreq(hz: Double) {
        equalizerProcessor.setBassBoostFreq(hz)
        _eqBassFreq.value = equalizerProcessor.getBassBoostFreq()
    }

    /** Centro del refuerzo de agudos EN VIVO; se persiste al soltar ([commitEqBoosts]). */
    fun setEqTrebleFreq(hz: Double) {
        equalizerProcessor.setTrebleBoostFreq(hz)
        _eqTrebleFreq.value = equalizerProcessor.getTrebleBoostFreq()
    }

    /** Preamp EN VIVO; se persiste al soltar ([commitEqPreamp]), como los refuerzos. */
    fun setEqPreamp(db: Float) {
        equalizerProcessor.setPreamp(db)
        _eqPreamp.value = equalizerProcessor.getPreamp()
    }

    fun commitEqPreamp() = musicPreferences.saveEqPreamp(_eqPreamp.value)

    /** Limitador on/off: se aplica y se persiste de una — no es un slider, no hay "soltar". */
    fun setEqLimiterEnabled(enabled: Boolean) {
        equalizerProcessor.setLimiterEnabled(enabled)
        // Sin copia local: [eqLimiterEnabled] sale del flow de preferencias, y `update()` refresca
        // el estado en memoria de forma SÍNCRONA, así que la UI ve el cambio en el acto.
        musicPreferences.saveEqLimiterEnabled(enabled)
    }

    /**
     * Umbral MANUAL en vivo; se persiste al soltar. NO escribe en el processor a propósito: el
     * único escritor de ese parámetro es el colector de [eqLimiterThresholdDb] (ver el init).
     * Con el automático hay dos orígenes posibles para el mismo valor, y dos escritores acabarían
     * pisándose — es la convención 14 aplicada a un parámetro del processor.
     */
    fun setEqLimiterThreshold(db: Float) {
        _eqLimiterThreshold.value = db.coerceIn(
            EqualizerAudioProcessor.LIMITER_THRESHOLD_MIN_DB,
            EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB
        )
    }

    fun commitEqLimiterThreshold() =
        musicPreferences.saveEqLimiterThreshold(_eqLimiterThreshold.value)

    /** Umbral automático on/off. Al desmarcarlo vuelve el valor manual que el usuario tenía. */
    fun setEqLimiterThresholdAuto(auto: Boolean) {
        musicPreferences.saveEqLimiterThresholdAuto(auto)
    }

    /** Persiste los cuatro parámetros de refuerzo (ganancias y centros) de una vez. */
    fun commitEqBoosts() {
        musicPreferences.saveEqBassBoost(_eqBassBoost.value)
        musicPreferences.saveEqTrebleBoost(_eqTrebleBoost.value)
        musicPreferences.saveEqBassFreq(_eqBassFreq.value)
        musicPreferences.saveEqTrebleFreq(_eqTrebleFreq.value)
    }

    fun resetEq() {
        val count = _eqBandCount.value
        val flat = FloatArray(count)
        val bassFreq = EqualizerAudioProcessor.BASS_BOOST_FREQ_DEFAULT_HZ
        val trebleFreq = EqualizerAudioProcessor.TREBLE_BOOST_FREQ_DEFAULT_HZ

        // Efecto EN VIVO. "Restablecer" = EQ neutro de verdad: curva plana, refuerzos a 0, preamp a
        // 0 y Clarity apagado (todo eso es TIMBRE y colorea el sonido). El limitador NO se toca a
        // propósito: no es parte del sonido, es una red de seguridad, y apagarlo desde un botón de
        // reset sería lo contrario de lo que espera quien lo pulsa.
        equalizerProcessor.setBandGains(flat)
        equalizerProcessor.setBassBoost(0f)
        equalizerProcessor.setTrebleBoost(0f)
        equalizerProcessor.setPreamp(0f)
        // Los CENTROS vuelven al default, no a cero: con la ganancia en 0 el filtro ni se calcula,
        // pero es lo que verá el usuario en los sliders la próxima vez que suba el refuerzo.
        equalizerProcessor.setBassBoostFreq(bassFreq)
        equalizerProcessor.setTrebleBoostFreq(trebleFreq)
        clarity.setEnabled(false)
        clarity.setGainDb(0f)

        // Estado local de la hoja.
        _eqGains.value = flat.toList()
        _eqBassBoost.value = 0f
        _eqTrebleBoost.value = 0f
        _eqPreamp.value = 0f
        _eqBassFreq.value = bassFreq
        _eqTrebleFreq.value = trebleFreq
        _clarityGain.value = 0f

        // Persistencia: el modo visible en UN volcado atómico (conserva el limitador tal como está).
        musicPreferences.saveEqSettings(
            EqSettings(
                bandCount = count,
                gains = flat,
                bassBoostDb = 0f,
                trebleBoostDb = 0f,
                bassFreqHz = bassFreq,
                trebleFreqHz = trebleFreq,
                preampDb = 0f,
                limiterEnabled = eqLimiterEnabled.value,
                limiterThresholdDb = _eqLimiterThreshold.value,
                limiterThresholdAuto = eqLimiterThresholdAuto.value,
                clarityEnabled = false,
                clarityGainDb = 0f
            )
        )
        // Aplana también el OTRO modo (una escritura más): que conserve una curva escondida
        // sorprendería al alternar 5↔10 después. saveEqSettings ya cubrió el modo visible.
        val otherCount = EqualizerAudioProcessor.otherBandCount(count)
        musicPreferences.saveEqBandGains(otherCount, FloatArray(otherCount))
    }

    // --- Perfiles y presets del EQ ---
    // Fachada compartida con LibraryViewModel (perfiles, ocultos, perfiles por ruta): un solo sitio
    // con la lógica, cada ViewModel la instancia con su scope. `Eagerly` porque la hoja del EQ del
    // reproductor debe tener el dato listo sin esperar a un colector.
    val eqPresets = EqPresetLibrary(musicPreferences, viewModelScope, SharingStarted.Eagerly)

    /**
     * Guarda el estado actual del EQ como perfil con [name]: la configuración COMPLETA (curva,
     * refuerzos, preamp y limitador).
     *
     * Lo que se guarda es SIEMPRE un perfil. Guardar solo las bandas dejaría fuera media pantalla
     * de controles —quien ajusta un preamp para que su curva no recorte espera que eso forme parte
     * del sonido que guarda— y ofrecer las dos cosas obligaría a decidir el alcance cada vez. Los
     * *presets* (curva sola) son los de fábrica: se eligen, no se crean.
     */
    fun saveCurrentAsEqProfile(name: String) {
        val trimmed = name.trim().ifBlank { return }
        val profile = EqProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = trimmed,
            settings = currentEqSettings()
        )
        // upsert (no add): guardar con un nombre ya usado SOBRESCRIBE ese perfil en vez de duplicarlo.
        // La confirmación de sobrescritura la hace el diálogo antes de llegar aquí (SaveProfileDialog).
        eqPresets.upsertProfile(profile)
    }

    /** Estado en vivo del EQ como [EqSettings] (lo que se captura en un perfil). */
    private fun currentEqSettings() = EqSettings(
        bandCount = _eqBandCount.value,
        gains = _eqGains.value.toFloatArray(),
        bassBoostDb = _eqBassBoost.value,
        trebleBoostDb = _eqTrebleBoost.value,
        bassFreqHz = _eqBassFreq.value,
        trebleFreqHz = _eqTrebleFreq.value,
        preampDb = _eqPreamp.value,
        limiterEnabled = eqLimiterEnabled.value,
        limiterThresholdDb = _eqLimiterThreshold.value,
        limiterThresholdAuto = eqLimiterThresholdAuto.value,
        clarityEnabled = clarityEnabled.value,
        clarityGainDb = _clarityGain.value
    )

    /**
     * Aplica un perfil ENTERO: curva, refuerzos, preamp y limitador. Ahí está la diferencia con un
     * preset de fábrica, que solo toca las ganancias y deja la protección de nivel como estuviera —
     * y por eso el selector los pinta en grupos rotulados, para que se sepa antes de tocarlos.
     *
     * La curva se remuestrea al modo de bandas ACTUAL y el modo NO se cambia: es una propiedad de
     * la pantalla, no del sonido guardado.
     */
    fun applyEqProfile(profile: EqProfile) {
        val s = profile.settings
        val gains = EqPresets.resample(
            s.gains,
            EqualizerAudioProcessor.bandsFor(s.bandCount),
            _eqBandCount.value
        )
        val bassFreq = s.bassFreqHz ?: EqualizerAudioProcessor.BASS_BOOST_FREQ_DEFAULT_HZ
        val trebleFreq = s.trebleFreqHz ?: EqualizerAudioProcessor.TREBLE_BOOST_FREQ_DEFAULT_HZ
        val threshold = s.limiterThresholdDb ?: EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB

        // Efecto EN VIVO (processor + Clarity). El umbral efectivo del limitador NO se escribe aquí:
        // lo pone el colector de [eqLimiterThresholdDb] cuando cambian _eqLimiterThreshold y el flow
        // de "auto" (ver el init) — su único escritor, para que no se pisen (convención 14).
        equalizerProcessor.setBandGains(gains)
        equalizerProcessor.setBassBoost(s.bassBoostDb)
        equalizerProcessor.setTrebleBoost(s.trebleBoostDb)
        equalizerProcessor.setBassBoostFreq(bassFreq)
        equalizerProcessor.setTrebleBoostFreq(trebleFreq)
        equalizerProcessor.setPreamp(s.preampDb)
        equalizerProcessor.setLimiterEnabled(s.limiterEnabled)
        clarity.setEnabled(s.clarityEnabled)
        clarity.setGainDb(s.clarityGainDb)

        // Estado local de la hoja (los sliders leen de aquí; freqs/gain saneados por el processor).
        _eqGains.value = gains.toList()
        _eqBassBoost.value = s.bassBoostDb
        _eqTrebleBoost.value = s.trebleBoostDb
        _eqBassFreq.value = equalizerProcessor.getBassBoostFreq()
        _eqTrebleFreq.value = equalizerProcessor.getTrebleBoostFreq()
        _eqPreamp.value = s.preampDb
        _eqLimiterThreshold.value = threshold
        _clarityGain.value = clarity.getGainDb()

        // Persistencia ATÓMICA: un solo volcado en vez de ~9. eqLimiterEnabled/…Auto/clarityEnabled
        // se observan de sus flows, que esta misma escritura actualiza. El modo de bandas NO se
        // cambia: es propiedad de la pantalla, no del sonido guardado.
        musicPreferences.saveEqSettings(
            EqSettings(
                bandCount = _eqBandCount.value,
                gains = gains,
                bassBoostDb = s.bassBoostDb,
                trebleBoostDb = s.trebleBoostDb,
                bassFreqHz = bassFreq,
                trebleFreqHz = trebleFreq,
                preampDb = s.preampDb,
                limiterEnabled = s.limiterEnabled,
                limiterThresholdDb = threshold,
                limiterThresholdAuto = s.limiterThresholdAuto,
                clarityEnabled = s.clarityEnabled,
                clarityGainDb = clarity.getGainDb()
            )
        )
    }

    // Los perfiles guardados y los presets ocultos viven en [eqPresets] (compartido con
    // LibraryViewModel). La hoja del EQ los lee como `eqPresets.profiles` / `eqPresets.hidden`. La
    // memoria por ruta de salida ya no es un ajuste: el [EqProfileManager] la aplica siempre.

    init {
        // ÚNICO escritor del umbral del processor, venga del slider o del automático (0 dBFS
        // fijo desde el 29 jul): un solo colector para los dos orígenes es lo que impide que se
        // pisen (convención 14 aplicada a un parámetro del processor).
        viewModelScope.launch {
            eqLimiterThresholdDb.collect { equalizerProcessor.setLimiterThreshold(it) }
        }
        // Resincronización cuando un cambio de RUTA DE SALIDA aplica otro perfil. Los flows de
        // bandas/refuerzos/preamp son MutableStateFlow locales por necesidad —se escriben en cada
        // frame de arrastre de un slider, y persistir por frame sería absurdo—, así que no se
        // enteran de un escritor externo. Sin esto, conectar los cascos con la hoja del EQ abierta
        // dejaba la curva anterior dibujada sobre un sonido que ya era otro.
        //
        // Aquí NO se toca el processor: el manager ya lo hizo. Esto solo pone la UI al día.
        viewModelScope.launch {
            eqProfileManager.applied.collect { s ->
                _eqBandCount.value = s.bandCount
                _eqGains.value = s.gains.toList()
                _eqBassBoost.value = s.bassBoostDb
                _eqTrebleBoost.value = s.trebleBoostDb
                _eqBassFreq.value = s.bassFreqHz
                    ?: EqualizerAudioProcessor.BASS_BOOST_FREQ_DEFAULT_HZ
                _eqTrebleFreq.value = s.trebleFreqHz
                    ?: EqualizerAudioProcessor.TREBLE_BOOST_FREQ_DEFAULT_HZ
                _eqPreamp.value = s.preampDb
                _eqLimiterThreshold.value = s.limiterThresholdDb
                    ?: EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB
                // clarityEnabled se observa del flow (lo escribe el manager en preferencias); el gain
                // es local por el mismo motivo que los sliders y necesita resync explícito.
                _clarityGain.value = s.clarityGainDb
            }
        }
        musicController.initialize()
        observeCurrentSong()
        observePlaybackErrors()
    }

    /**
     * El estado del NowPlaying se alimenta de TRES dominios con cadencia y criticidad
     * distintas. Fusionarlos en un solo combine+debounce fue la causa del skeleton de
     * varios segundos tras un arranque frío: el churn del scan reseteaba el debounce y
     * retenía también la identidad del tema.
     *  1. Identidad — qué canción suena. La sabe el controller EN MEMORIA: se pinta al
     *     instante, sin esperar a Room ni a WorkManager.
     *  2. Enriquecimiento — la fila de BD del tema (letras, colores, path local) y los
     *     side-effects por-canción. Cadencia baja, sin coalescing.
     *  3. Badges de descarga — WorkManager/coordinator/progreso. Churnea durante un sync;
     *     puede llegar tarde sin costo visual, así que se muestrea.
     *
     * El acento del tema NO es un cuarto colector sino un job por canción ([resolveAlbumColors]):
     * lo dispara el dominio 2 pero no puede vivir DENTRO de él, porque su `collectLatest` cancela
     * con cada escritura sobre la fila y extraer los colores tarda más que eso.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeCurrentSong() {
        // Dominio 1: identidad. Al cambiar el id se siembra el estado por-canción completo
        // con lo que ya hay en memoria (la sesión restaurada sale de la BD, así que trae
        // letras/colores reales); los otros dominios lo refinan después.
        viewModelScope.launch {
            currentSong.collect { song ->
                val previousId = _nowPlayingUiState.value.song?.id
                when {
                    song == null -> _nowPlayingUiState.value = NowPlayingUiState()
                    song.id != previousId -> _nowPlayingUiState.value = NowPlayingUiState(
                        song = song,
                        albumColors = song.colors,
                        hasManualColor = song.id in musicPreferences.loadManualColorIds(),
                        lyrics = song.lyrics,
                        lyricLines = parseLyricsUseCase(song.lyrics),
                        isDownloaded = song.isLocalAudio
                    )
                }
            }
        }

        // Dominio 2: la verdad de la BD para el tema actual. `distinctUntilChanged` es
        // obligatorio: Room invalida por TABLA y re-emite la misma fila con cada escritura
        // a `songs` (upserts del scan, letras/colores de otros temas).
        viewModelScope.launch {
            var previousDbSong: Song? = null
            currentSong
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId == null) flowOf(null)
                    else repository.getSongByIdFlow(songId).filterNotNull().distinctUntilChanged()
                }
                .collectLatest { dbSong ->
                    if (dbSong == null) {
                        previousDbSong = null
                    } else {
                        val previous = previousDbSong
                        previousDbSong = dbSong
                        onDbSongChanged(dbSong, previous)
                    }
                }
        }

        // Dominio 3: badges de descarga. Solo parchea sus campos; nunca toca `song`.
        viewModelScope.launch {
            currentSong
                .map { it?.id }
                .distinctUntilChanged()
                .flatMapLatest { songId ->
                    if (songId == null) flowOf(null) else getDownloadStatusFlow(songId)
                }
                .collect { status ->
                    _nowPlayingUiState.update {
                        // Redescarga (worker por-canción) cuenta como "descargando" AUNQUE el
                        // archivo local siga existiendo. El scan global solo cuenta como
                        // descargando si el tema aún NO está local.
                        it.copy(
                            isDownloading = status != null &&
                                (status.isSingleActive || (!it.isDownloaded && status.isGlobalActive)),
                            downloadProgress = status?.progress,
                            downloadStatusMessage = status?.statusMessage
                        )
                    }
                }
        }
    }

    private fun getDownloadStatusFlow(songId: String): Flow<DownloadStatus> {
        val downloadTag = WorkerTags.downloadTag(songId)
        val globalWorkFlow = workManager.getWorkInfosForUniqueWorkFlow(WorkerTags.SCAN_WORK_NAME)
        val singleDownloadFlow = workManager.getWorkInfosByTagFlow(downloadTag)
        val coordinatorStatusFlow = requestCoordinator.workerStatus
        // Progreso REAL: lo publica `SyncManager.activeDownloads` (el downloader lo alimenta
        // byte a byte). NO se lee de `WorkInfo.progress`: el SingleSongDownloadWorker delega
        // toda la descarga en SyncManager y nunca llama a `setProgress`, así que ese dato es
        // siempre 0f — por eso la barra de progreso no aparecía nunca.
        val activeDownloadsFlow = syncManager.activeDownloads
            .map { list -> list.firstOrNull { it.song.id == songId }?.progress }
            .distinctUntilChanged()

        return combine(
            globalWorkFlow, singleDownloadFlow, coordinatorStatusFlow, activeDownloadsFlow
        ) { globalInfos, singleInfos, coordinatorStatus, liveProgress ->
            val isGlobalRunning = globalInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            val activeSingleWork = singleInfos.find { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

            val statusMessage = when {
                activeSingleWork?.state == WorkInfo.State.ENQUEUED -> when (coordinatorStatus) {
                    is WorkerStatus.ThrottledByOneDrive -> context.getString(R.string.download_status_throttled)
                    is WorkerStatus.PausedForPriorityDownload -> context.getString(R.string.download_status_waiting_other)
                    else -> context.getString(R.string.download_status_queued)
                }
                activeSingleWork?.state == WorkInfo.State.RUNNING && liveProgress == null -> when (coordinatorStatus) {
                    is WorkerStatus.ThrottledByOneDrive -> context.getString(R.string.download_status_throttled)
                    else -> context.getString(R.string.download_status_preparing)
                }
                else -> null
            }
            DownloadStatus(isGlobalRunning, activeSingleWork != null, liveProgress, statusMessage)
        }
            // sample, NO debounce: estas fuentes no callan durante un sync (workerStatus
            // alterna por request, los WorkInfos transicionan) y debounce se resetea con
            // cada emisión → inanición. sample garantiza a lo sumo una emisión cada 200ms
            // sin poder retener el estado indefinidamente.
            .sample(DOWNLOAD_STATUS_SAMPLE_MS)
            .distinctUntilChanged()
    }

    /**
     * Dominio 2: la fila de BD del tema actual cambió (o es la primera emisión de un tema
     * nuevo). [previous] es la fila de BD anterior — null en la primera emisión — y sirve
     * para detectar transiciones REALES (carátula nueva, descarga completada) sin depender
     * del uiState, que el dominio 1 ya sembró con el mismo id.
     */
    private suspend fun onDbSongChanged(dbSong: Song, previous: Song?) {
        val isNewSong = dbSong.id != previous?.id
        // "Disponible offline": descargada de la nube (file://) o de la fuente local (content://).
        val isDownloaded = dbSong.isLocalAudio

        if (isNewSong && !dbSong.path.startsWith("file://") && dbSong.remoteId != null) {
            startAutoDownload(dbSong)
        }

        val parsedLines = parseLyricsUseCase(dbSong.lyrics)
        _nowPlayingUiState.update {
            it.copy(
                song = dbSong,
                lyrics = dbSong.lyrics,
                lyricLines = parsedLines,
                isDownloaded = isDownloaded
            )
        }

        if (isNewSong && shouldAttemptLyricsFetch(dbSong)) {
            prefetchLyrics(dbSong)
        }

        val artUriChanged = !isNewSong && dbSong.albumArtUri != null && dbSong.albumArtUri != previous?.albumArtUri
        val downloadCompleted = !isNewSong && previous?.isLocalAudio == false && isDownloaded
        val missingColors = _nowPlayingUiState.value.albumColors == null

        if (isNewSong || artUriChanged || downloadCompleted || missingColors) {
            if (downloadCompleted) {
                artworkRepository.invalidateCache(dbSong.id)
                dbSong.albumArtUri?.let { uri ->
                    val request = ImageRequest.Builder(context).data(uri).memoryCachePolicy(CachePolicy.WRITE_ONLY).diskCachePolicy(CachePolicy.WRITE_ONLY).build()
                    context.imageLoader.enqueue(request)
                }
            }
            // La carátula cambió (o es otra canción): lo que hubiera extraído ya no vale.
            resolveAlbumColors(dbSong, restart = isNewSong || artUriChanged || downloadCompleted)
            // Aquí había un `preloadVisualArt(dbSong)` que precalentaba la carátula grande. Se quitó
            // el 19 ago 2026 porque desde el reproductor PERSISTENTE es redundante: su árbol está
            // compuesto siempre que hay canción y pide esa misma imagen en cuanto se MIDE, ocurra o
            // no la colocación. Las dos peticiones salían casi a la vez y **Coil no fusiona
            // peticiones concurrentes**, así que la segunda no encontraba nada en el caché y
            // decodificaba otra vez: medido en un trace de Perfetto, el mismo JPEG de 1500×1500 a
            // 800×800 dos veces con 29 ms de diferencia, 162 y 136 ms. Igualar la clave de caché
            // (ver [nowPlayingArtRequest]) era necesario pero no suficiente — lo que sobraba era el
            // segundo peticionario.
            if (isNewSong) preloadNextSong(dbSong)
        }
    }

    /**
     * Resuelve el acento del tema para [dbSong] en un job PROPIO, no dentro del colector de la
     * fila.
     *
     * Extraer los colores es lento (decodificar la carátula + cuantizarla) y vivía dentro del
     * `collectLatest` del dominio 2, así que CUALQUIER escritura sobre `songs` que tocara esta
     * fila lo cancelaba a mitad. Y las hay a montones sin que el usuario haga nada: el healing de
     * carátulas locales corre en CADA vuelta a primer plano y escribe en ráfaga (la portada del
     * álbum, la invalidación de colores, el sellado del intento). Cuando la última de esas
     * escrituras cancelaba la extracción y no venía otra emisión detrás, el tema se quedaba sin
     * color hasta cambiar de canción — el "no coge los colores de la carátula" aleatorio, y solo
     * con música del dispositivo, que es la única que dispara ese healing en primer plano.
     *
     * Aquí la única razón para abandonar la extracción es que cambie la canción, que es la única
     * que de verdad la invalida.
     */
    private fun resolveAlbumColors(dbSong: Song, restart: Boolean) {
        // Ya hay una resolución viva para esta misma canción y nada la ha invalidado: dejarla
        // terminar en vez de reiniciar el trabajo desde cero en cada emisión de la fila.
        if (!restart && colorSongId == dbSong.id && colorJob?.isActive == true) return
        colorJob?.cancel()
        colorSongId = dbSong.id
        colorJob = viewModelScope.launch {
            // Sin chequeo de "color centinela": las extracciones fallidas ya no se persisten
            // (ArtworkRepository), así que un color guardado siempre es un resultado real —
            // incluido el negro legítimo de carátulas monocromas.
            val colors = dbSong.colors ?: artworkRepository.getAlbumColors(dbSong)
            // Un fallo de extracción NO se pinta: dejar el estado como está conserva lo que el
            // dominio 1 sembró y deja vivo el reintento (`missingColors` en la próxima emisión).
            // Escribir el null convertía un fallo puntual en fallback para toda la canción.
            if (colors == null) return@launch
            // La canción pudo cambiar mientras se extraía: el color solo se aplica a la suya.
            _nowPlayingUiState.update {
                if (it.song?.id == dbSong.id) it.copy(albumColors = colors) else it
            }
        }
    }

    fun toggleDownload() {
        val song = currentSong.value ?: return
        // LOCAL nunca se (re)descarga: ya vive en el dispositivo y no hay copia en la nube.
        // La UI lo filtra, pero el guard cubre cualquier camino que llegue igual.
        // OJO: por sourceType, NO por isLocalAudio — isLocalAudio también es true para una
        // canción de NUBE ya descargada (file://), y ahí "Redescargar" sí tiene que funcionar
        // (con isLocalAudio el botón quedaba visible pero muerto: la rama de abajo era código
        // inalcanzable).
        if (song.sourceType == SourceType.LOCAL) return
        val state = _nowPlayingUiState.value
        if (state.isDownloaded) {
            if (!isNetworkAvailable()) { snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG); return }
            // La re-descarga BORRA el archivo local: la canción que suena pasa a streaming ANTES
            // de encolarla, o se quedaría sin fuente a mitad de reproducción.
            musicController.switchCurrentToStreaming(song.id)
            viewModelScope.launch(Dispatchers.IO) {
                _nowPlayingUiState.update { it.copy(isDownloaded = false, isDownloading = true, downloadStatusMessage = context.getString(R.string.download_status_restarting)) }
                downloadScheduler.scheduleDownload(song.id, forceRedownload = true)
            }
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        } else if (state.isDownloading) {
            // Solo cancelamos el work: el finally del SingleSongDownloadWorker cancelado ya
            // llama endPriorityDownload(). Llamarlo también acá decrementaba el contador dos
            // veces y reanudaba el scan masivo aunque otra descarga prioritaria siguiera viva.
            workManager.cancelAllWorkByTag(WorkerTags.downloadTag(song.id))
            snackbarManager.show(context.getString(R.string.download_msg_cancelled, song.title))
        } else {
            if (!isNetworkAvailable()) { snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG); return }
            downloadScheduler.scheduleDownload(song.id)
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        }
    }

    private fun observePlaybackErrors() {
        viewModelScope.launch {
            musicController.playbackError.collect { errorInfo -> handlePlaybackError(errorInfo) }
        }
        // El presupuesto de reintentos se devuelve ante ÉXITO REAL. Sin esto solo bajaba
        // cuando el usuario pulsaba play en algo: un tema que fallaba, se recuperaba y volvía
        // a fallar más tarde entraba directo al fail-fast con el contador heredado de la
        // recuperación anterior — y ese camino llegaba a marcar corrupta una canción sana.
        viewModelScope.launch {
            musicController.playbackState.collect { state ->
                if (state == PlaybackState.PLAYING) resetRetryBudget()
            }
        }
    }

    /**
     * Número de intento para [songId]. El presupuesto es POR CANCIÓN: si el error es de otra
     * distinta a la última que falló, empieza de cero. Un contador global hacía que los fallos
     * de un tema gastaran los reintentos del siguiente.
     */
    private fun nextRetryFor(songId: String): Int {
        if (songId != lastErrorSongId) {
            lastErrorSongId = songId
            retryCount.set(0)
        }
        return retryCount.incrementAndGet()
    }

    private fun resetRetryBudget() {
        lastErrorSongId = null
        retryCount.set(0)
    }

    private fun preloadNextSong(currentSong: Song) {
        val currentList = playlist.value
        if (currentList.isEmpty()) return
        val nextIdx = (currentIndex.value + 1) % currentList.size
        val nextSong = currentList[nextIdx]
        if (nextSong.id == currentSong.id) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = playbackCoordinator.prepareSongForPlayback(nextSong)
                if (result is PlaybackCoordinator.PrepareResult.Success && result.urlRefreshed) {
                    withContext(Dispatchers.Main) { musicController.updateSong(result.song) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error preloading next song ${nextSong.id}", e)
            }
        }
    }


    private suspend fun handlePlaybackError(errorInfo: PlaybackErrorInfo) {
        // La canción que falló viaja EN el evento. Leerla de `currentSong` era una carrera:
        // entre que el player emite el error y llega aquí puede haber una transición de item,
        // y entonces se reparaba (marcar corrupta, borrar el audio, forzar descarga) la
        // canción EQUIVOCADA. `currentSong` queda solo como respaldo para errores sin item.
        val song = errorInfo.songId?.let { id ->
            playlist.value.firstOrNull { it.id == id } ?: currentSong.value?.takeIf { it.id == id }
        } ?: currentSong.value ?: return
        val currentRetry = nextRetryFor(song.id)

        // Se avisa del DESENLACE, no del intento: healing y retry son trabajo interno que acaba
        // bien (no hay nada que contar) o mal (y entonces cae en Skip, que sí avisa). Anunciar
        // cada paso convertiría un bache de red en una ristra de mensajes.
        when (val result = playbackErrorRecoveryUseCase(song, errorInfo, currentRetry, MAX_RETRIES)) {
            is PlaybackErrorRecoveryUseCase.Result.Skip -> {
                // El motivo de skip ya viene localizado desde el UseCase (antes se decidía por
                // prefijo del texto, frágil con i18n).
                showPlaybackError(result.reason)
                // Solo se salta si la canción que falló SIGUE siendo la que suena: si el
                // usuario ya se movió a otra, saltar aquí le arrebataría la que acaba de elegir.
                withContext(Dispatchers.Main) {
                    if (musicController.currentSong.value?.id == song.id) musicController.next()
                }
                resetRetryBudget()
            }
            is PlaybackErrorRecoveryUseCase.Result.Healing -> Unit
            is PlaybackErrorRecoveryUseCase.Result.Retry -> {
                withContext(Dispatchers.Main) { musicController.retryCurrentWithFreshUrl(result.song) }
                monitorPlaybackRecovery(song.id)
            }
            is PlaybackErrorRecoveryUseCase.Result.Ignore -> showPlaybackError(errorInfo.message)
        }
    }

    /**
     * Comunica un fallo de reproducción.
     *
     * Va por el `SnackbarManager` (el bus singleton que ya usa el resto de la app) y no por un
     * StateFlow propio: los dos que había —`error` y `loadingStatus`— no los observaba nadie, así
     * que la app se saltaba canciones dañadas en absoluto silencio.
     *
     * `replaceCurrent` es lo que impide la pila de avisos cuando los fallos llegan en RÁFAGA (una
     * cola entera sin red falla canción a canción en segundos): cada uno descarta al anterior y
     * queda el último, que es el informativo. Es el mecanismo que el propio `SnackbarManager`
     * ofrece para esto, y evita tener que inventar una ventana de deduplicación por tiempo —un
     * umbral arbitrario que además solo habría filtrado mensajes de texto IDÉNTICO.
     */
    private fun showPlaybackError(message: String) {
        snackbarManager.show(
            message,
            length = com.qhana.siku.data.util.SnackbarLength.LONG,
            replaceCurrent = true
        )
    }

    /** Sobrecarga para los `PlayResult.Error`, que ya traen el texto como `@StringRes`. */
    private fun showPlaybackError(@androidx.annotation.StringRes messageRes: Int) =
        showPlaybackError(context.getString(messageRes))

    private fun monitorPlaybackRecovery(songId: String) {
        viewModelScope.launch {
            // Espera acotada a que el retry arranque (ver [RECOVERY_START_TIMEOUT_MS]). La
            // corrupción real la detecta el recovery use case por código de error del decoder,
            // nunca este timeout.
            val started = kotlinx.coroutines.withTimeoutOrNull(RECOVERY_START_TIMEOUT_MS) {
                musicController.playbackState.first { it == PlaybackState.PLAYING }
            } != null
            if (!started && musicController.currentSong.value?.id == songId) {
                withContext(Dispatchers.Main) { musicController.next() }
            }
        }
    }

    fun playSongs(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        resetRetryBudget()
        // ANTES de lanzar la corrutina, no dentro: el caller expande el reproductor en este mismo
        // handler y `MusicAppState.openPlayer` anota de qué fila nace leyendo la canción anunciada.
        // Dentro del `launch`, el anuncio queda detrás de `oldJob?.cancelAndJoin()`, que SUSPENDE si
        // el tema anterior todavía se está preparando (dos toques seguidos) — y entonces el player
        // se abría con la canción anterior y el morph salía de la fila equivocada. El use case lo
        // repite (mismo valor): es idempotente y sigue cubriendo a sus otros llamadores.
        musicController.announceSelection(songs[index.coerceIn(0, songs.lastIndex)])

        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()

            val errorMsg = playbackUseCase.playSongs(songs, index)
            if (errorMsg != null) {
                showPlaybackError(errorMsg)
            } else {
                val targetSong = songs.getOrNull(index)
                if (targetSong != null && !targetSong.path.startsWith("file://") && targetSong.remoteId != null) {
                    startAutoDownload(targetSong)
                }
            }
        }
    }

    fun playSongFromLibrary(
        clickedSong: Song,
        filter: SongFilter,
        query: String,
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter> = emptySet()
    ) {
        resetRetryBudget()
        // Síncrono y antes del `launch`, por el mismo motivo que en [playSongs].
        musicController.announceSelection(clickedSong)

        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()

            when (val result = playbackUseCase.playFromLibrary(clickedSong, filter, query, sortOrder, sourceFilters)) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                is MusicPlaybackUseCase.PlayResult.RetryWithSingle -> {
                    // playSongs reemplaza este job a propósito: se aborta el intento de
                    // reproducir la biblioteca y se reproduce solo este tema.
                    playSongs(listOf(result.song), 0)
                }
            }
        }
    }

    private suspend fun startAutoDownload(song: Song, forcePriority: Boolean = false, allowLocal: Boolean = false, forceRedownload: Boolean = false) {
        if (!forceRedownload && !allowLocal && song.path.startsWith("file://")) return
        if (!isNetworkAvailable()) return
        if (!forcePriority && !forceRedownload && !networkManager.isWifi()) return
        // getWorkInfosForUniqueWork(...).get() es bloqueante; fuera del main thread para no
        // congelarlo en cada cambio de canción (esta función corre desde viewModelScope).
        val existingWork = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(WorkerTags.downloadTag(song.id)).get()
        }
        if (existingWork.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } && !forceRedownload) return
        // Unique name por canción (downloadTag/repairTag por defecto). El viejo nombre global
        // compartido con KEEP hacía que, mientras se auto-descargaba la canción A, el auto-
        // download de la canción B se ignorara silenciosamente.
        //
        // isUserInitiated=false: es un prefetch de FONDO, no una descarga pedida. Marca la work con
        // AUTO_DOWNLOAD_TAG para que su fallo NO dispare el snackbar "Fallo al descargar" —la canción
        // suena por streaming pase lo que pase con la descarga (era el bug reportado)—.
        downloadScheduler.scheduleDownload(
            song.id, forceRedownload = forceRedownload, isUserInitiated = false
        )
    }

    fun shufflePlay(songs: List<Song>) {
        if (songs.isEmpty()) return
        resetRetryBudget()
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            when (val result = playbackUseCase.playShuffled(songs)) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                else -> {}
            }
        }
    }

    /**
     * Aleatorio sobre un subconjunto por ORIGEN: las acciones "Sin conexión" / "Sin descargar" del
     * inicio, que solo existen mientras la biblioteca esté partida en esas dos mitades.
     *
     * NO registra contexto en "Seguir escuchando", al revés que los chips de género o Favoritos, y
     * es deliberado: aquéllos son un lugar de la biblioteca y éste es un ESTADO del dispositivo.
     * "Lo que suena sin red" no significa lo mismo dentro de una semana —cambia con cada descarga
     * y con el tope LRU—, así que una tarjeta para reanudarlo prometería volver a algo que ya no
     * existe.
     */
    fun shuffleBySource(sourceFilters: Set<SongSourceFilter>) {
        if (sourceFilters.isEmpty()) return
        resetRetryBudget()
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            // Mismo snapshot que arma la cola de la pestaña Todas: el orden da igual (se mezcla),
            // pero así el subconjunto lo define el ÚNICO sitio que sabe qué es "descargada".
            val songs = repository.getSongsSnapshot("", SortOrder.TITLE_ASC, sourceFilters)
            if (songs.isEmpty()) return@launch
            when (val result = playbackUseCase.playShuffled(songs)) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                else -> {}
            }
        }
    }

    /**
     * Registra un contexto en "Seguir escuchando" y avisa al player para que congele el saliente
     * en su slot. Mismo par que `LibraryViewModel.recordContext`, para los contextos que nacen
     * dentro de este ViewModel (biblioteca entera / aleatoria).
     */
    private fun recordContext(ctx: PlaybackContext) {
        musicPreferences.recordContext(ctx)
        musicController.onContextStarting(ctx.key)
    }

    /** Reproduce TODA la biblioteca en su orden natural (contraparte de [shuffleAllFromLibrary]). */
    fun playAllFromLibrary() {
        viewModelScope.launch {
            val songs = repository.getAllSongs()
            if (songs.isNotEmpty()) {
                recordContext(PlaybackContext.LibraryAll)
                playSongs(songs, 0)
            }
        }
    }

    /**
     * Reanuda un contexto de "Seguir escuchando" donde se dejó: si hay slot guardado, restaura su
     * cola/índice/posición (y el orden barajado); si no, reproduce [songs] entero desde el inicio.
     * [songs] llega ya resuelto por el caller (álbum/artista/género/lista/favoritos).
     */
    fun resumeContext(ctx: PlaybackContext, songs: List<Song>) {
        if (songs.isEmpty()) return
        musicPreferences.recordContext(ctx)
        // Si ese contexto ES lo que suena, la tarjeta solo abre el NowPlaying: no se reinicia nada.
        if (musicController.isActiveContext(ctx.key)) return
        resetRetryBudget()
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            musicController.onContextStarting(ctx.key)
            val restored = musicController.restoreContext(ctx.key)
            if (restored != null) {
                musicController.playRestored(restored)
                autoDownloadFirst(restored.playlist.getOrNull(restored.index))
            } else {
                musicController.announceSelection(songs[0])
                val errorMsg = playbackUseCase.playSongs(songs, 0)
                if (errorMsg != null) showPlaybackError(errorMsg) else autoDownloadFirst(songs.firstOrNull())
            }
        }
    }

    /** Reanuda "Toda la biblioteca" / "Aleatorio" desde "Seguir escuchando". */
    fun resumeLibraryContext(shuffled: Boolean) {
        val ctx = if (shuffled) PlaybackContext.LibraryShuffle else PlaybackContext.LibraryAll
        musicPreferences.recordContext(ctx)
        if (musicController.isActiveContext(ctx.key)) return
        resetRetryBudget()
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()
            musicController.onContextStarting(ctx.key)
            val restored = musicController.restoreContext(ctx.key)
            if (restored != null) {
                musicController.playRestored(restored)
                autoDownloadFirst(restored.playlist.getOrNull(restored.index))
                return@launch
            }
            val songs = repository.getAllSongs()
            if (songs.isEmpty()) return@launch
            if (shuffled) {
                when (val result = playbackUseCase.playShuffled(songs)) {
                    is MusicPlaybackUseCase.PlayResult.Success -> autoDownloadFirst(result.song, result.willStream)
                    is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                    else -> {}
                }
            } else {
                val errorMsg = playbackUseCase.playSongs(songs, 0)
                if (errorMsg != null) showPlaybackError(errorMsg) else autoDownloadFirst(songs.firstOrNull())
            }
        }
    }

    /** Prefetch de la primera canción de una cola recién puesta, si es remota. */
    private suspend fun autoDownloadFirst(song: Song?, forcePriority: Boolean = false) {
        if (song == null) return
        if (!song.path.startsWith("file://") && song.remoteId != null) {
            startAutoDownload(song, forcePriority = forcePriority)
        }
    }

    /**
     * Reproduce la lista de la pestaña **Todas tal como se ve**: su orden visible y sus chips de
     * origen. Es lo que hay detrás de la botonera de esa pestaña.
     *
     * Existe aparte de [playAllFromLibrary]/[shuffleAllFromLibrary] porque significa otra cosa, y la
     * diferencia es justo el sitio desde el que se pulsa: desde el inicio no hay ninguna lista en
     * pantalla, así que "toda la biblioteca" es literal; desde la pestaña, el botón está bajo el
     * chip de orden y los de origen, y reproducir algo distinto de lo que esos chips dejaron a la
     * vista sería mentir. Con "Sin descargar" puesto, ese play no puede arrancar por una canción
     * descargada.
     *
     * El snapshot es el MISMO que arma la cola al tocar una fila de esa lista, así que "reproducir
     * todo" y "tocar la primera" dan la misma cola. Registra los mismos contextos que las dos de
     * arriba —es la biblioteca, un lugar—, al revés que [shuffleBySource], que va por un estado del
     * dispositivo.
     */
    fun playLibraryList(
        sortOrder: SortOrder,
        sourceFilters: Set<SongSourceFilter>,
        shuffled: Boolean
    ) {
        resetRetryBudget()
        recordContext(
            if (shuffled) PlaybackContext.LibraryShuffle else PlaybackContext.LibraryAll
        )
        // El snapshot hay que traerlo antes de decidir, así que las dos ramas empiezan igual; lo que
        // cambia es a quién se le entrega. La de ORDEN va por [playSongs] —el mismo camino que un
        // toque en una fila, con su `announceSelection` y su cancelación— y la de AZAR por
        // `playShuffled`, que es quien sabe barajar sin que la primera canción dependa del orden.
        viewModelScope.launch {
            val songs = repository.getSongsSnapshot("", sortOrder, sourceFilters)
            if (songs.isEmpty()) return@launch
            if (!shuffled) {
                playSongs(songs, 0)
                return@launch
            }
            // `cancelAndJoin` DENTRO del job nuevo, como los otros puntos de entrada: ver el
            // comentario de [shuffleAllFromLibrary].
            val oldJob = playJob
            playJob = launch {
                oldJob?.cancelAndJoin()
                when (val result = playbackUseCase.playShuffled(songs)) {
                    is MusicPlaybackUseCase.PlayResult.Success -> {
                        if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                            startAutoDownload(result.song, forcePriority = result.willStream)
                        }
                    }
                    is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                    else -> {}
                }
            }
        }
    }

    fun shuffleAllFromLibrary() {
        resetRetryBudget()
        recordContext(PlaybackContext.LibraryShuffle)

        // `cancelAndJoin` DENTRO del job nuevo, como los otros tres puntos de entrada: con un
        // `cancel()` suelto la cancelación no se espera, así que un `playSongs` anterior podía
        // terminar su `setPlaylistAndPlay` DESPUÉS de que este armara la suya y dejar sonando la
        // cola vieja.
        val oldJob = playJob
        playJob = viewModelScope.launch {
            oldJob?.cancelAndJoin()

            when (val result = playbackUseCase.shuffleAllFromLibrary()) {
                is MusicPlaybackUseCase.PlayResult.Success -> {
                    if (!result.song.path.startsWith("file://") && result.song.remoteId != null) {
                        startAutoDownload(result.song, forcePriority = result.willStream)
                    }
                }
                is MusicPlaybackUseCase.PlayResult.Error -> showPlaybackError(result.messageRes)
                else -> {}
            }
        }
    }

    private var fetchLyricsJob: kotlinx.coroutines.Job? = null
    private var prefetchLyricsJob: kotlinx.coroutines.Job? = null
    private var searchCandidatesJob: kotlinx.coroutines.Job? = null


    /**
     * Ajuste hecho y todavía sin escribir: `songId to texto`.
     *
     * Es UNO solo, y por eso [shiftLyrics] vuelca el anterior si era de otra canción. Lo consume
     * [flushLyricsShift] al salir de la pantalla; no hay temporizador que lo persiga.
     */
    private var pendingLyricsShift: Pair<String, String>? = null

    /**
     * Decide si vale la pena buscar las letras de esta canción:
     * - Si ya tenemos lyrics (incluyendo el sentinel "[INSTRUMENTAL]"), no.
     * - Si nunca intentamos, sí.
     * - Si intentamos hace menos de TTL, no (cacheamos el NotFound).
     * - Si pasaron más de TTL desde el último intento, reintentar.
     *
     * La exigencia de metadata REAL ya no vive aquí sino dentro de [prefetchLyrics], justo antes
     * de la llamada a LrcLib: leer el `.lrc` o el tag del propio archivo no necesita tags buenos
     * —no se busca por título/artista, se abre el archivo— y una canción sin metadata no debe
     * quedarse sin su letra local por eso.
     */
    private fun shouldAttemptLyricsFetch(song: Song): Boolean {
        if (song.lyrics != null) return false
        val attempted = song.lyricsAttemptedAt ?: return true
        return (System.currentTimeMillis() - attempted) > LYRICS_NOT_FOUND_RETRY_TTL_MS
    }

    /**
     * La búsqueda de letras solo tiene sentido con los TAGS del archivo. Antes de extraerlos
     * (canción de nube recién escaneada) el título es el NOMBRE DE ARCHIVO y el artista el
     * placeholder [AppConfig.UNKNOWN_ARTIST]: consultar LrcLib con eso es basura y, peor,
     * sellaba `lyricsAttemptedAt` (NotFound, TTL 14 días) bloqueando la búsqueda con la
     * metadata real que llegaba minutos después. Sin tags (el placeholder persiste tras la
     * extracción) no se busca nada automáticamente; queda la búsqueda manual.
     */
    private fun hasRealMetadata(song: Song): Boolean =
        song.artist.isNotBlank() && song.artist != AppConfig.UNKNOWN_ARTIST

    /**
     * Prefetch silencioso: busca lyrics en background sin mostrar loading en la UI.
     * Si el usuario abre el panel de lyrics después, las encuentra ya cacheadas.
     */
    private fun prefetchLyrics(song: Song) {
        prefetchLyricsJob?.cancel()
        prefetchLyricsJob = viewModelScope.launch {
            try {
                // La letra que ya viene con el archivo gana: es la que el usuario eligió (a veces
                // corregida a mano) y se lee sin red. Solo lo que está en el dispositivo — lo
                // remoto costaría peticiones y aquí nadie está esperando.
                localLyricsReader.read(song)?.let { local ->
                    if (currentSong.value?.id != song.id) return@launch
                    repository.saveLyrics(song.id, local)
                    if (!_nowPlayingUiState.value.isLyricsLoading) {
                        _nowPlayingUiState.update { it.copy(lyrics = local, lyricLines = parseLyricsUseCase(local)) }
                    }
                    return@launch
                }
                // Sin tags reales el título es el nombre de archivo: preguntarle eso a LrcLib es
                // basura y además sella el NotFound por 14 días (ver [hasRealMetadata]).
                if (!hasRealMetadata(song)) return@launch
                if (!isNetworkAvailable()) return@launch
                val result = lyricsRepository.getLyricsWithResult(song.title, song.artist, song.album, song.duration.toDouble() / TimeUnit.SECONDS.toMillis(1))
                if (currentSong.value?.id != song.id) return@launch
                when (result) {
                    is LyricsResult.Found -> {
                        repository.saveLyrics(song.id, result.lyrics)
                        // Update UI state only if no explicit fetch is in progress
                        if (!_nowPlayingUiState.value.isLyricsLoading) {
                            _nowPlayingUiState.update { it.copy(lyrics = result.lyrics, lyricLines = parseLyricsUseCase(result.lyrics)) }
                        }
                    }
                    is LyricsResult.NotFound -> {
                        // Persistir el NotFound para evitar re-pegarle a LrcLib en cada reproducción.
                        repository.markLyricsNotFound(song.id)
                    }
                    is LyricsResult.Error -> { /* Silencio: errores transitorios no se persisten */ }
                }
            } catch (_: Exception) { /* Silent — prefetch failures are not user-visible */ }
        }
    }

    fun fetchLyrics(force: Boolean = false) {
        val song = currentSong.value ?: return
        val currentState = _nowPlayingUiState.value
        // Solo NOT_FOUND corta el prefetch: un fallo de red o del proveedor SÍ debe reintentarse
        // solo (al volver a la canción), porque no dice nada sobre si la letra existe.
        if (!force && (currentState.isLyricsLoading || currentState.lyrics != null ||
                currentState.lyricsFailure == LyricsFailure.NOT_FOUND)) return
        // Snapshot de la letra anterior (antes del reset a null) para que un refresh manual
        // pueda decirle al usuario si LrcLib devolvió contenido distinto o el mismo.
        val previousLyrics = currentState.lyrics ?: song.lyrics
        // Cancel prefetch and wait for it to finish to avoid race conditions
        val prefetchToCancel = prefetchLyricsJob
        fetchLyricsJob?.cancel()
        fetchLyricsJob = viewModelScope.launch {
            try {
                prefetchToCancel?.cancelAndJoin()
                _nowPlayingUiState.update { it.copy(isLyricsLoading = true, lyricsFailure = null, lyricsError = null, lyrics = null, lyricLines = emptyList()) }
                if (!force) {
                    val cached = song.lyrics ?: repository.getSongById(song.id).getOrNull()?.lyrics
                    if (!cached.isNullOrBlank()) {
                        _nowPlayingUiState.update { it.copy(lyrics = cached, lyricLines = parseLyricsUseCase(cached), isLyricsLoading = false) }
                        return@launch
                    }
                }
                // Aquí SÍ se mira la nube: la espera es del usuario y explícita.
                localLyricsReader.read(song, includeRemote = isNetworkAvailable())?.let { local ->
                    if (currentSong.value?.id != song.id) return@launch
                    _nowPlayingUiState.update {
                        it.copy(lyrics = local, lyricLines = parseLyricsUseCase(local), isLyricsLoading = false)
                    }
                    repository.saveLyrics(song.id, local)
                    return@launch
                }
                // Sin tags reales no hay nada que preguntarle a LrcLib (el título sería el nombre
                // de archivo). Empty state de "no encontrada" — desde ahí queda la búsqueda
                // manual — y SIN persistir el intento: cuando lleguen los tags, se buscará normal.
                if (!hasRealMetadata(song)) {
                    _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NOT_FOUND, isLyricsLoading = false) }
                    if (force) snackbarManager.show(context.getString(R.string.lyrics_no_metadata))
                    return@launch
                }
                if (!isNetworkAvailable()) {
                    val offlineMsg = context.getString(R.string.common_no_offline_connection)
                    _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NO_NETWORK, lyricsError = offlineMsg) }
                    snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG)
                    if (force) snackbarManager.show(context.getString(R.string.common_error_format, offlineMsg))
                    return@launch
                }
                when (val result = lyricsRepository.getLyricsWithResult(song.title, song.artist, song.album, song.duration.toDouble() / TimeUnit.SECONDS.toMillis(1))) {
                    is LyricsResult.Found -> {
                        if (currentSong.value?.id != song.id) return@launch
                        _nowPlayingUiState.update { it.copy(lyrics = result.lyrics, lyricLines = parseLyricsUseCase(result.lyrics)) }
                        repository.saveLyrics(song.id, result.lyrics)
                        if (force) {
                            snackbarManager.show(context.getString(
                                if (result.lyrics == previousLyrics) R.string.lyrics_refresh_unchanged
                                else R.string.lyrics_refresh_updated
                            ))
                        }
                    }
                    is LyricsResult.NotFound -> {
                        if (currentSong.value?.id != song.id) return@launch
                        _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.NOT_FOUND) }
                        repository.markLyricsNotFound(song.id)
                        if (force) snackbarManager.show(context.getString(R.string.lyrics_refresh_notfound))
                    }
                    is LyricsResult.Error -> {
                        if (currentSong.value?.id != song.id) return@launch
                        // No se pudo ni llegar a LrcLib: eso es "sin conexión", con su propio
                        // estado vacío. Mostrar el mensaje del proveedor aquí significaba pintarle
                        // al usuario un `Unable to resolve host "lrclib.net"` en la pantalla.
                        if (result.isOffline) {
                            val offlineMsg = context.getString(R.string.common_no_offline_connection)
                            _nowPlayingUiState.update {
                                it.copy(lyricsFailure = LyricsFailure.NO_NETWORK, lyricsError = offlineMsg)
                            }
                            if (force) snackbarManager.show(offlineMsg)
                        } else {
                            val msg = lyricsErrorMessage(result.reason)
                            _nowPlayingUiState.update { it.copy(lyricsFailure = LyricsFailure.PROVIDER_ERROR, lyricsError = msg) }
                            if (force) snackbarManager.show(msg)
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallo inesperado (los del proveedor ya vienen tipados arriba): detalle al Log,
                // al usuario un mensaje localizado genérico en vez del `e.message` crudo en inglés.
                Log.w(TAG, "Fallo inesperado al refrescar letras de ${song.title}", e)
                val msg = context.getString(R.string.lyrics_error_unknown)
                if (currentSong.value?.id == song.id) {
                    _nowPlayingUiState.update {
                        it.copy(lyricsFailure = LyricsFailure.PROVIDER_ERROR, lyricsError = msg)
                    }
                }
                if (force) snackbarManager.show(msg)
            } finally {
                if (currentSong.value?.id == song.id) {
                    _nowPlayingUiState.update { it.copy(isLyricsLoading = false) }
                }
            }
        }
    }

    /**
     * Traduce el motivo tipado de un fallo del proveedor de letras a un texto localizado. La capa
     * de datos ya no fabrica mensajes (antes llegaban a la pantalla literales en inglés como
     * "Timeout" o "Network error", iguales en los dos idiomas); aquí, con el `Context`, se resuelven
     * los strings. El caso "sin conexión" (`isOffline`) se resuelve aparte, con su propio estado.
     */
    private fun lyricsErrorMessage(reason: com.qhana.siku.data.repository.LyricsErrorReason): String {
        val res = when (reason) {
            com.qhana.siku.data.repository.LyricsErrorReason.TIMEOUT -> R.string.lyrics_error_timeout
            com.qhana.siku.data.repository.LyricsErrorReason.NETWORK -> R.string.lyrics_error_network
            com.qhana.siku.data.repository.LyricsErrorReason.SERVER -> R.string.lyrics_error_server
            com.qhana.siku.data.repository.LyricsErrorReason.UNKNOWN -> R.string.lyrics_error_unknown
        }
        return context.getString(res)
    }

    /**
     * Búsqueda manual: pide candidatos a LrcLib y los expone en el UI state para
     * que el usuario elija. No modifica las lyrics actuales hasta que se selecciona uno.
     */
    fun searchLyricsCandidates() {
        val song = currentSong.value ?: return
        searchCandidatesJob?.cancel()
        searchCandidatesJob = viewModelScope.launch {
            _nowPlayingUiState.update { it.copy(isSearchingCandidates = true, lyricsSearchError = null, lyricsCandidates = null) }
            if (!isNetworkAvailable()) {
                _nowPlayingUiState.update { it.copy(isSearchingCandidates = false, lyricsSearchError = context.getString(R.string.common_no_offline_connection)) }
                snackbarManager.show(context.getString(R.string.common_connection_error), length = com.qhana.siku.data.util.SnackbarLength.LONG)
                return@launch
            }
            val result = lyricsRepository.searchCandidates(song.title, song.artist)
            if (currentSong.value?.id != song.id) return@launch
            when (result) {
                is LyricsCandidatesResult.Found -> _nowPlayingUiState.update {
                    it.copy(isSearchingCandidates = false, lyricsCandidates = result.candidates)
                }
                is LyricsCandidatesResult.Empty -> _nowPlayingUiState.update {
                    it.copy(isSearchingCandidates = false, lyricsCandidates = emptyList())
                }
                // Sin poder alcanzar el servidor, el mensaje es "sin conexión" y no el texto crudo
                // de la excepción: la comprobación de red de arriba da `true` con una red
                // conectada que no llega a internet, así que este es el filtro que de verdad
                // atrapa el caso (ver [LyricsResult.Error.isOffline]).
                is LyricsCandidatesResult.Error -> _nowPlayingUiState.update {
                    it.copy(
                        isSearchingCandidates = false,
                        lyricsSearchError = if (result.isOffline) {
                            context.getString(R.string.common_no_offline_connection)
                        } else {
                            lyricsErrorMessage(result.reason)
                        }
                    )
                }
            }
        }
    }

    fun selectLyricsFromCandidate(candidate: LyricsCandidate) {
        val song = currentSong.value ?: return
        val resolved = candidate.resolvedLyrics ?: return
        viewModelScope.launch {
            repository.saveLyrics(song.id, resolved)
            if (currentSong.value?.id != song.id) return@launch
            _nowPlayingUiState.update {
                it.copy(
                    lyrics = resolved,
                    lyricLines = parseLyricsUseCase(resolved),
                    lyricsFailure = null,
                    lyricsError = null,
                    lyricsCandidates = null,
                    lyricsSearchError = null
                )
            }
        }
    }

    /**
     * Corre la letra [deltaMs] milisegundos (positivo = más tarde), o la deja a cero con `null`.
     *
     * **Hace falta porque el dato que decidiría esto no existe en ningún metadato.** LrcLib tiene
     * varias transcripciones de la misma canción, cada una hecha sobre un máster distinto, y
     * `/api/get` empareja con ±2 s de tolerancia: un remaster que arranca dos segundos más tarde
     * que el original casa igual y devuelve tiempos corridos. No se puede elegir mejor filtrando
     * por duración —las dos duran lo mismo— ni por ninguna otra cosa que la app sepa; lo único que
     * lo sabe es el oído del usuario. Caso real: *The Zephyr Song* del remaster de 2014 contra la
     * transcripción del original, 2,03 s (31 ago 2026).
     *
     * **Se guarda dentro del propio texto**, como marca `[offset:]` del formato, no en una columna
     * aparte: ver [LrcSyntax.withShift] para por qué. Consecuencia buscada: si el usuario guarda
     * después la letra en su `.lrc` o en los tags, el ajuste viaja con ella y otro reproductor lo
     * respeta.
     *
     * El estado se recalcula solo al releer el texto ([NowPlayingUiState.lyricsShiftMs] es
     * derivado), así que aquí no hay ningún contador que mantener.
     *
     * **La letra se corre EN EL ACTO y el disco se escribe al SALIR**, y esa asimetría es el punto.
     * Cuadrar la sincronía son diez o treinta toques seguidos —el usuario aprieta, escucha medio
     * verso y vuelve a apretar—, y cada uno escribía una fila de `songs`. Room invalida por TABLA,
     * así que cada toque re-ejecutaba TODAS las consultas suscritas de `LibraryViewModel`, que está
     * vivo detrás y trae varios `GROUP BY` de tabla completa (`getArtists`, `getAlbums`,
     * `getTopGenresFlow`…). Es el mismo coste por el que existe `sampledDuringSync`, provocado aquí
     * por un botón.
     *
     * **No hay debounce, y no lo hay a propósito.** La primera versión de esto llevaba un plazo de
     * 700 ms, o sea un número inventado para adivinar cuándo termina una ráfaga de toques. El final
     * de la interacción no hay que adivinarlo: **es salir de la pantalla**, y eso es un evento
     * ([flushLyricsShift] desde el `onDispose` de `LyricsScreen`). Un temporizador solo habría
     * añadido una ventana en la que cerrar la app pierde el último toque — el que el usuario acaba
     * de decidir que era el bueno.
     */
    fun shiftLyrics(deltaMs: Long?) {
        val song = currentSong.value ?: return
        val current = _nowPlayingUiState.value.lyrics ?: return
        val shifted = LrcSyntax.withShift(
            current,
            if (deltaMs == null) 0L else LrcSyntax.shiftMillisOf(current) + deltaMs
        )
        if (shifted == current) return
        // Síncrono: el siguiente toque acumula sobre ESTE texto, así que el estado tiene que ser la
        // verdad antes de que la mano vuelva a bajar.
        _nowPlayingUiState.update {
            it.copy(lyrics = shifted, lyricLines = parseLyricsUseCase(shifted))
        }
        // El pendiente es UNO. Si el que había era de otra canción —cambió de pista con las letras
        // abiertas— se escribe antes de pisarlo, o su ajuste se perdería en silencio. Es el caso que
        // el temporizador cubría a medias y que un evento cubre entero.
        pendingLyricsShift?.let { (id, text) -> if (id != song.id) writeLyrics(id, text) }
        pendingLyricsShift = song.id to shifted
    }

    /**
     * Escribe el ajuste pendiente. Lo llama la pantalla de letras al salir de la composición, que
     * es el final real de la interacción — mismo patrón que `flushExcludedFolderChanges`, y el
     * mismo motivo: marcar cinco veces seguidas encadenaba cinco trabajos de los que solo el último
     * dice la verdad (convención 13, esperar por SEÑAL y no por intervalo).
     *
     * Va en el `onDispose` y no colgado del botón de cerrar porque hay más salidas —el gesto de
     * atrás, cerrar el reproductor, un cambio de configuración— y todas tienen que volcar.
     */
    fun flushLyricsShift() {
        val (songId, text) = pendingLyricsShift ?: return
        pendingLyricsShift = null
        writeLyrics(songId, text)
    }

    /**
     * Un solo sitio escribe la letra ajustada, con el `songId` EXPLÍCITO y no releído del estado:
     * lo que se guarda tiene que ser de la canción que lo produjo, aunque para cuando la corrutina
     * corra ya esté sonando otra.
     */
    private fun writeLyrics(songId: String, text: String) {
        viewModelScope.launch { repository.saveLyrics(songId, text) }
    }

    fun dismissLyricsSearch() {
        searchCandidatesJob?.cancel()
        _nowPlayingUiState.update {
            it.copy(isSearchingCandidates = false, lyricsCandidates = null, lyricsSearchError = null)
        }
    }

    // --- Guardar la letra en el archivo -------------------------------------------------------

    private val _lyricsSaveState = MutableStateFlow(LyricsSaveUiState())
    val lyricsSaveState: StateFlow<LyricsSaveUiState> = _lyricsSaveState.asStateFlow()

    /** Modo pendiente de reintento tras conceder un permiso (nube o sistema). */
    private var pendingSaveMode: LyricsSaveMode? = null

    /**
     * Punto de entrada del botón de guardar. Con un modo ya elegido guarda directo; en
     * [LyricsSaveMode.ASK] abre el diálogo, que necesita saber qué se le puede ofrecer a ESTA
     * canción (no es lo mismo un FLAC local que un WAV o una canción que solo está en la nube).
     */
    fun requestSaveLyrics() {
        val song = currentSong.value ?: return
        val lyrics = _nowPlayingUiState.value.lyrics ?: song.lyrics ?: return
        viewModelScope.launch {
            when (val mode = musicPreferences.loadLyricsSaveMode()) {
                LyricsSaveMode.ASK -> {
                    val options = lyricsWriter.optionsFor(song)
                    _lyricsSaveState.update { it.copy(options = options) }
                }
                else -> performSave(song, lyrics, mode)
            }
        }
    }

    /** Confirmación del diálogo. [remember] fija el modo y deja de preguntar. */
    fun confirmSaveLyrics(mode: LyricsSaveMode, remember: Boolean) {
        val song = currentSong.value ?: return
        val lyrics = _nowPlayingUiState.value.lyrics ?: song.lyrics ?: return
        if (remember) musicPreferences.saveLyricsSaveMode(mode)
        _lyricsSaveState.update { it.copy(options = null) }
        viewModelScope.launch { performSave(song, lyrics, mode) }
    }

    fun dismissSaveLyricsDialog() {
        _lyricsSaveState.update { it.copy(options = null) }
    }

    /**
     * Reintenta el guardado después de que el usuario conceda el permiso que faltaba. Se llama
     * tanto al volver del consentimiento de OneDrive como del diálogo de escritura del sistema.
     */
    fun retryPendingSave() {
        val mode = pendingSaveMode ?: return
        val song = currentSong.value ?: return
        val lyrics = _nowPlayingUiState.value.lyrics ?: song.lyrics ?: return
        pendingSaveMode = null
        viewModelScope.launch { performSave(song, lyrics, mode) }
    }

    /**
     * Limpia el `IntentSender` en cuanto la UI lo lanza. Sin esto seguiría en el estado y cada
     * recomposición volvería a abrir el diálogo del sistema.
     */
    fun consumePendingPermission() {
        _lyricsSaveState.update { it.copy(pendingPermission = null) }
    }

    fun cancelPendingSave() {
        pendingSaveMode = null
        _lyricsSaveState.update { it.copy(pendingPermission = null, needsCloudConsent = false) }
    }

    /** Consentimiento de escritura sobre OneDrive. Interactivo: exige la Activity visible. */
    fun grantCloudWriteConsent(activity: android.app.Activity) {
        viewModelScope.launch {
            _lyricsSaveState.update { it.copy(needsCloudConsent = false) }
            when (authManager.requestWriteConsent(activity).firstOrNull()) {
                is AuthResult.Success -> retryPendingSave()
                else -> {
                    pendingSaveMode = null
                    snackbarManager.show(context.getString(R.string.lyrics_save_consent_denied))
                }
            }
        }
    }

    private suspend fun performSave(song: Song, lyrics: String, mode: LyricsSaveMode) {
        _lyricsSaveState.update { it.copy(isSaving = true) }
        // `finally`: escribir tags o un .lrc toca el sistema de archivos y OneDrive, y una
        // excepción dejaba el diálogo con el spinner puesto para siempre.
        val result = try {
            lyricsWriter.save(song, lyrics, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsSaveResult.Failed(FailureReason.UNEXPECTED)
        } finally {
            _lyricsSaveState.update { it.copy(isSaving = false) }
        }

        when (result) {
            is LyricsSaveResult.Success -> snackbarManager.show(
                context.getString(
                    if (mode == LyricsSaveMode.EMBEDDED) R.string.lyrics_save_ok_embedded
                    else R.string.lyrics_save_ok_lrc
                )
            )
            is LyricsSaveResult.NeedsCloudConsent -> {
                pendingSaveMode = mode
                _lyricsSaveState.update { it.copy(needsCloudConsent = true) }
            }
            is LyricsSaveResult.NeedsSystemPermission -> {
                pendingSaveMode = mode
                _lyricsSaveState.update { it.copy(pendingPermission = result.intentSender) }
            }
            is LyricsSaveResult.Failed -> snackbarManager.show(messageFor(result))
        }
    }

    private fun messageFor(failure: LyricsSaveResult.Failed): String = when (failure.reason) {
        FailureReason.NOTHING_TO_SAVE -> context.getString(R.string.lyrics_save_err_empty)
        FailureReason.FORMAT_UNSUPPORTED -> context.getString(R.string.lyrics_save_err_format)
        FailureReason.NOT_DOWNLOADED -> context.getString(R.string.lyrics_save_err_not_downloaded)
        FailureReason.NO_LYRICS_FOLDER -> context.getString(R.string.lyrics_save_err_no_folder)
        FailureReason.NO_PERMISSION -> context.getString(R.string.lyrics_save_err_permission)
        FailureReason.NEEDS_WIFI -> context.getString(R.string.lyrics_save_err_wifi)
        FailureReason.UNEXPECTED -> context.getString(
            R.string.common_error_format,
            failure.detail ?: context.getString(R.string.common_error)
        )
    }

    fun playPause() = musicController.playPause()
    fun next() = musicController.next()
    fun previous() = musicController.previous()
    fun seekTo(position: Long) = musicController.seekTo(position)

    /**
     * Salto RELATIVO del doble toque en la carátula. Se acota aquí y no en la UI porque el
     * destino depende de la posición y la duración reales, que son de este ViewModel: pasarle un
     * negativo o un valor más allá del final al player deja la reproducción en un estado raro.
     * Con duración desconocida (streaming aún sin preparar) solo se protege el extremo inferior.
     */
    fun seekBy(deltaMs: Long) {
        val total = duration.value
        val target = (currentPosition.value + deltaMs).coerceAtLeast(0L)
        seekTo(if (total > 0L) target.coerceAtMost(total) else target)
    }
    fun toggleShuffle() = musicController.toggleShuffle()
    fun toggleRepeatMode() = musicController.toggleRepeatMode()
    fun skipToIndex(index: Int) = musicController.playAt(index)
    fun reorderQueue(from: Int, to: Int) = musicController.reorderQueue(from, to)
    fun removeFromQueue(index: Int) = musicController.removeFromQueue(index)

    /**
     * Encola [song] al final de la cola (o arranca la reproducción si no hay cola). El feedback va
     * por snackbar: la acción es invisible sin ella (la cola no está en pantalla al pulsar desde una
     * lista), y "ya está en la cola" evita el silencio de un duplicado descartado.
     */
    fun addToQueue(song: Song) {
        val added = musicController.addToQueue(listOf(song))
        snackbarManager.show(
            if (added > 0) context.getString(R.string.queue_added)
            else context.getString(R.string.queue_already_in)
        )
    }

    /**
     * Encola un detalle ENTERO (artista, álbum, género, lista). Mismo mecanismo que [addToQueue],
     * pero el snackbar dice **cuántas entraron de verdad** y no cuántas se pidieron: el controller
     * descarta por id las que ya estaban, así que encolar un álbum del que ya tenías tres pistas
     * añade menos de las que ves en pantalla. Anunciar el total sería mentir sobre lo que pasó, y
     * es justo el caso en el que el usuario va a mirar la cola a comprobarlo.
     */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val added = musicController.addToQueue(songs)
        snackbarManager.show(
            if (added > 0) context.resources.getQuantityString(R.plurals.queue_added_count, added, added)
            else context.getString(R.string.queue_all_already_in)
        )
    }

    /**
     * Vacía la cola entera. **Detiene la reproducción**, porque en esta app una cola vacía es no
     * tener nada que sonar: `MusicController.stop()` limpia los items de ExoPlayer, el
     * `PlaylistManager` y la sesión persistida de una vez.
     *
     * Va con DESHACER en vez de un diálogo de confirmación. El diálogo cobraría un paso extra
     * siempre —a esta acción se llega a propósito, desde la propia hoja de la cola— para cubrir un
     * error que es raro; el deshacer solo aparece cuando ya pasó. Y restaurar sale prácticamente
     * gratis: los tres datos que hacen falta (la lista, en qué tema iba y por dónde) están en
     * memoria justo antes de parar, así que la cola vuelve al segundo exacto y respetando si
     * estaba sonando o en pausa.
     */
    fun clearQueue() = stopWithUndo(R.string.queue_cleared)

    /**
     * Para la reproducción y descarta la barra. Es la MISMA operación que [clearQueue] —en esta app
     * parar es quedarse sin cola— y por eso comparte el deshacer; lo único que cambia es cómo se
     * nombra, porque quien desliza la píldora hacia abajo no vació una cola: descartó el
     * reproductor.
     *
     * El deshacer pesa MÁS acá que en la hoja de la cola, no menos: a "vaciar la cola" se llega a
     * propósito, atravesando dos pantallas, mientras que un gesto se dispara sin querer.
     */
    fun stopPlayback() = stopWithUndo(R.string.playback_stopped)

    private fun stopWithUndo(@StringRes message: Int) {
        val songs = musicController.playlist.value
        if (songs.isEmpty()) return
        val index = musicController.currentIndex.value
        val position = musicController.currentPosition.value
        val wasPlaying = musicController.playbackState.value == PlaybackState.PLAYING

        musicController.stop()

        snackbarManager.show(
            message = context.getString(message),
            actionLabel = context.getString(R.string.common_undo),
            onAction = {
                musicController.playQueue(
                    songs = songs,
                    startIndex = index,
                    startPosition = position,
                    autoPlay = wasPlaying
                )
            }
        )
    }

    /** Guarda la cola actual como una lista nueva (crea + agrega todas las canciones). */
    fun saveQueueAsPlaylist(name: String) {
        val trimmed = name.trim()
        val ids = musicController.playlist.value.map { it.id }
        if (trimmed.isEmpty() || ids.isEmpty()) return
        viewModelScope.launch {
            val playlistId = repository.createPlaylist(trimmed)
            val added = repository.addSongsToPlaylist(playlistId, ids)
            snackbarManager.show(
                context.resources.getQuantityString(R.plurals.playlist_songs_added, added, added)
            )
        }
    }
    fun getAudioSessionId(): Int = musicController.getAudioSessionId()
    fun updatePosition() = musicController.updatePosition()
    fun toggleKeepScreenOn() { val v = !_keepScreenOn.value; _keepScreenOn.value = v; musicPreferences.saveKeepScreenOn(v) }
    fun toggleFavorite(id: String) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }
    private fun isNetworkAvailable(): Boolean = networkManager.isAvailable()

    fun refreshCurrentSongColors() {
        val id = currentSong.value?.id ?: return
        // Una extracción en vuelo terminaría DESPUÉS y repondría el color viejo (lo tiene
        // capturado en su `dbSong`), deshaciendo justo lo que el usuario acaba de pedir.
        colorJob?.cancel()
        colorSongId = null
        viewModelScope.launch {
            // force: esto solo corre tras "regenerar colores" (Ajustes) — acción explícita,
            // así que también pisa un color manual (y limpia su marca).
            artworkRepository.invalidateCache(id, force = true)
            val song = repository.getSongById(id).getOrNull() ?: return@launch
            val colors = artworkRepository.getAlbumColors(song)
            // force borró también la marca de manual: el color vuelve a ser una lectura del
            // análisis y el tema puede volver a aplicarle el criterio de acromático.
            _nowPlayingUiState.update { it.copy(albumColors = colors, hasManualColor = false) }
        }
    }

    /**
     * El tema en curso con TODO lo que llegó DESPUÉS de encolarlo. `currentSong` es el snapshot en
     * MEMORIA del player: se congeló al armar la cola, así que no ve la carátula que reparó el
     * healing ni la que dejó la descarga al terminar. La fila del uiState sí (dominio 2 = Room).
     *
     * Importa sobre todo en STREAMING, que es justo donde la carátula suele llegar tarde: con el
     * snapshot del player, `albumArtUriString` era null y el long-press de la carátula salía por
     * el `?: return` — el selector de color no abría NUNCA en una canción sin descargar.
     * Es el mismo criterio con el que el MiniPlayer elige qué `Song` pintar (ver PlayerOverlay).
     */
    private fun activeSong(): Song? {
        val current = currentSong.value ?: return null
        return _nowPlayingUiState.value.song?.takeIf { it.id == current.id } ?: current
    }

    fun showDebugInfo(isDarkTheme: Boolean) {
        val uri = activeSong()?.albumArtUriString
        if (uri == null) {
            // Sin carátula no hay nada que analizar, y un long-press que no hace NADA se lee como
            // que la app se colgó. Se dice en voz alta.
            snackbarManager.show(context.getString(R.string.color_picker_no_art))
            return
        }
        val savedColors = _nowPlayingUiState.value.albumColors
        viewModelScope.launch {
            artworkRepository.debugExtractColors(uri, isDarkTheme, savedColors)?.let { d ->
                _nowPlayingUiState.update { it.copy(debugInfo = d) }
            }
        }
    }
    fun clearDebugInfo() { _nowPlayingUiState.update { it.copy(debugInfo = null) } }

    fun overrideSongColor(color: Int, isDarkTheme: Boolean) {
        val current = activeSong() ?: return
        // Mismo motivo que en [refreshCurrentSongColors]: la elección manual no puede quedar
        // pisada por una extracción que arrancó antes.
        colorJob?.cancel()
        colorSongId = null
        viewModelScope.launch {
            artworkRepository.saveManualColor(current.id, current.album, color, isDarkTheme)
            val song = activeSong() ?: return@launch
            val colors = artworkRepository.getAlbumColors(song)
            // hasManualColor: el tema debe aplicar ESTE color aunque tenga poca saturación —
            // es una elección explícita, no una lectura dudosa de la carátula.
            _nowPlayingUiState.update {
                it.copy(albumColors = colors, hasManualColor = true, debugInfo = null)
            }
        }
    }
    override fun onCleared() {
        super.onCleared()
        preloadJob?.cancel()
        colorJob?.cancel()
        fetchLyricsJob?.cancel()
        prefetchLyricsJob?.cancel()
        searchCandidatesJob?.cancel()
        playJob?.cancel()
    }
}

private data class DownloadStatus(val isGlobalActive: Boolean, val isSingleActive: Boolean, val progress: Float?, val statusMessage: String?)