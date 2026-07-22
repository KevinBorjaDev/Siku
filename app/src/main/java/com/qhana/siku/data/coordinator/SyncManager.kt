package com.qhana.siku.data.coordinator

import android.content.Context
import android.util.Log
import com.qhana.siku.data.manager.MusicDownloader
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.NetworkManager
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: IMusicRepository,
    private val musicPreferences: MusicPreferences,
    private val networkManager: NetworkManager,
    private val musicDownloader: MusicDownloader,
    private val requestCoordinator: RequestCoordinator,
    private val authManager: AuthManager,
    private val sourceRegistry: com.qhana.siku.data.source.MusicSourceRegistry,
    private val artistImageRepository: com.qhana.siku.data.repository.ArtistImageRepository
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val BATCH_SIZE = 50
        // OneDrive/SharePoint limita el ancho de banda POR CONEXIÓN TCP (~0.4-0.9 MB/s por
        // stream para descargas de archivos grandes), pero NO capa el total de la cuenta.
        // Por eso el throughput escala casi linealmente con el nº de conexiones: medido a
        // ~3 MB/s con 8 paralelas y ~8-9 MB/s con 16. Combinado con el cliente HTTP/1.1
        // (cada descarga = su propia conexión, sin multiplexar). Con 32 se espera ~16-28
        // MB/s en líneas rápidas; el log de throughput al final de processQueue permite
        // validarlo. Si aparecen 429 del lado de OneDrive, volver a 16-24.
        // MAX es el TECHO: el nº real de workers se calcula por corrida en
        // computeParallelism() según el ancho de banda del enlace WiFi.
        //
        // NO es private: el ConnectionPool del cliente "download" (AppModule) se dimensiona
        // con este mismo valor — si el pool ocioso fuera menor que el nº de workers, las
        // conexiones sobrantes se cerrarían al terminar cada archivo y el siguiente pagaría
        // el handshake TLS. Antes estaba copiado a mano allí y podían desincronizarse.
        internal const val MAX_PARALLEL_WIFI = 32
        private const val MIN_PARALLEL_WIFI = 4

        // Throughput observado POR CONEXIÓN contra OneDrive (~0.4-0.9 MB/s). Se usa como
        // divisor para dimensionar workers: más conexiones que enlace/valor-por-conexión
        // solo añaden contención local (timeouts del watchdog en WiFi débil).
        private const val ONEDRIVE_PER_CONNECTION_MBPS = 0.5

        // Workers de la fase finalize (análisis de audio + tags + BD). Es trabajo CPU/IO
        // local: si corriera dentro del worker de descarga, cada análisis dejaría una
        // conexión OneDrive ociosa varios segundos por canción.
        private const val FINALIZE_PARALLELISM = 4

        // Reintentos por canción ante errores transitorios (red, stall, 5xx). El backoff es
        // lineal (3s, 6s) — suficiente para absorber blips sin frenar el resto de workers.
        private const val MAX_SONG_ATTEMPTS = 3
        private const val SONG_RETRY_BACKOFF_MS = 3_000L

        // Cadencia máxima de publicación del panel de descargas activas.
        private const val ACTIVE_DOWNLOADS_PUBLISH_MS = 300L

        // Espera por reconexión: la cola se PAUSA al perder red en vez de quemar canciones
        // como fallidas. Los waits cuentan iteraciones (no wall-clock) para ser testeables
        // con virtual time. 40 × 3s = ~2 min de gracia; WiFi 20 × 3s = ~1 min.
        private const val NETWORK_POLL_MS = 3_000L
        private const val NETWORK_WAIT_ATTEMPTS = 40
        private const val WIFI_WAIT_ATTEMPTS = 20

        // Backoff persistido entre corridas (cola en BD, v18): una canción fallida queda
        // con nextRetryAt en el futuro y el productor la salta hasta entonces. Transitorio:
        // crece linealmente con los intentos acumulados (5, 10, 15... min, tope 1h).
        // Permanente (4xx tras refresh, archivo inválido): 24h — si OneDrive lo arregla,
        // se recupera solo; si no, deja de quemar red en cada sync.
        private const val TRANSIENT_BACKOFF_STEP_MS = 5 * 60_000L
        private const val TRANSIENT_BACKOFF_MAX_MS = 60 * 60_000L
        private const val PERMANENT_BACKOFF_MS = 24 * 60 * 60_000L
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        if (exception is CancellationException) {
            Log.d(TAG, "Coroutine cancelled in SyncManager: ${exception.message}")
            return@CoroutineExceptionHandler
        }
        Log.e(TAG, "Uncaught exception in SyncManager", exception)
        val className = exception.javaClass.simpleName
        val details = exception.message ?: "no details"
        _state.value = SyncStatus.Error("Unexpected error ($className): $details")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val _state = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    // "Sync terminado" como EVENTO (sin replay): a diferencia de `state` (que retiene el
    // último valor), un suscriptor nuevo no recibe Completes viejos — evita que cada
    // ViewModel que nace tenga que filtrar el Complete retenido a mano.
    private val _completedEvents = MutableSharedFlow<SyncStatus.Complete>(extraBufferCapacity = 1)
    val completedEvents: kotlinx.coroutines.flow.SharedFlow<SyncStatus.Complete> = _completedEvents.asSharedFlow()
    
    private val activeDownloadsMap = ConcurrentHashMap<String, ActiveDownload>()
    private val _activeDownloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val activeDownloads: StateFlow<List<ActiveDownload>> = _activeDownloads.asStateFlow()

    // Muestreo de emisiones: cada tick de progreso de las 32 descargas paralelas marcaba
    // dirty; un único publicador vuelca el snapshot al StateFlow como mucho cada 300ms, en
    // vez de hacer ~128 toList()+emisiones por segundo durante un sync masivo.
    private val downloadsDirty = AtomicBoolean(false)
    private var publisherJob: Job? = null
    private val publisherLock = Any()

    // Lista de fallidas respaldada por BD (v18): sobrevive a la muerte del proceso, a
    // diferencia del viejo MutableStateFlow en memoria que se vaciaba con cada restart.
    val failedDownloads: kotlinx.coroutines.flow.Flow<List<com.qhana.siku.data.repository.FailedDownload>> =
        musicRepository.getFailedDownloadsFlow()

    // IDs marcados para reintento. processQueue los saca de su set local `attempted`
    // al inicio de cada iteración, permitiendo re-procesar fallidas sin reiniciar el sync.
    private val retryRequests = ConcurrentHashMap.newKeySet<String>()

    private val prioritySongId = MutableStateFlow<String?>(null)
    // Dedupe de descargas prioritarias disparadas por reproducción cuando NO hay un productor
    // de sync activo (ver prioritizeSong): evita lanzar dos descargas individuales del mismo id.
    private val priorityInFlight = ConcurrentHashMap.newKeySet<String>()
    private val syncMutex = Mutex()
    private val isScanning = AtomicBoolean(false)
    private val stopSignal = AtomicBoolean(false)
    private var priorityJob: Job? = null
    private val priorityMutex = Mutex()

    // true mientras el productor de processQueue está en su loop. Permite a
    // retryFailedDownloads saber si un sync "corriendo" todavía puede consumir
    // retryRequests o si ya solo está drenando descargas en vuelo.
    private val producerActive = AtomicBoolean(false)

    // Motivo por el que la cola se detuvo antes de terminar (batería, red, sin WiFi).
    // null = la cola corrió hasta agotar el trabajo. Lo consume executeSync para
    // devolver un SyncOutcome honesto que ScanWorker traduce a Result.retry().
    private val queueStopReason = AtomicReference<IncompleteReason?>(null)

    // --- Duplicados entre fuentes (v23) ---
    // Copias de NUBE en disputa (duplicadas y sin política elegida): el productor no las
    // descarga hasta que el usuario decida — "escaneo antes de descargar" del spec dedup.
    @Volatile
    private var undecidedDuplicateIds: Set<String> = emptySet()

    // Conteo de duplicados pendientes de decisión (null = nada que preguntar). Lo consume
    // el diálogo global vía SyncViewModel; sobrevive a la pantalla porque vive aquí.
    private val _duplicateDecisionNeeded = MutableStateFlow<Int?>(null)
    val duplicateDecisionNeeded: StateFlow<Int?> = _duplicateDecisionNeeded.asStateFlow()

    // startSync(token, api) removed to enforce DRY and usage of startSync(force) which handles auth and suspension correctly.

    suspend fun startSync(force: Boolean = false): SyncOutcome {
        if (isScanning.get()) {
            Log.d(TAG, "Scan already in progress, ignoring request.")
            return SyncOutcome.Skipped
        }

        // Mutex prevents two concurrent startSync calls from both proceeding
        syncMutex.withLock {
            if (isScanning.get()) return SyncOutcome.Skipped

            isScanning.set(true)
            stopSignal.set(false)
            return try {
                Log.d(TAG, "Starting sync (force=$force)")
                executeSync(force)
            } catch (e: CancellationException) {
                Log.d(TAG, "Sync cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                // No debería llegar acá (executeSync captura todo), pero si pasa
                // lo reportamos como Failed para que ScanWorker pueda decidir.
                Log.e(TAG, "Sync Fatal Error", e)
                SyncOutcome.Failed(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    /**
     * Orquestador genérico (Fase 2): itera las fuentes activas del [sourceRegistry] llamando a
     * `discover` (OneDrive = delta; local = walk), luego corre el pipeline de descarga genérico
     * (que resuelve URLs vía la fuente). SyncManager ya no conoce OneDrive directamente.
     */
    suspend fun executeSync(force: Boolean = false): SyncOutcome {
        var changesCount = 0
        var deletedCount = 0
        var downloaded = 0
        var failed = 0
        var wasCancelled = false
        var authError = false
        var fatal: Exception? = null
        queueStopReason.set(null)
        try {
            // Bootstrap v23 (una vez): forzar un full scan para que la nube reporte sus
            // relativePath (el delta incremental no re-envía items sin cambios).
            if (!musicPreferences.loadRelPathBackfillDone()) {
                musicPreferences.clearDeltaToken()
                musicPreferences.saveRelPathBackfillDone()
            }

            _state.value = SyncStatus.Scanning(0, "Scanning for changes...")
            val discoverCtx = com.qhana.siku.data.source.DiscoverContext(
                reportScanning = { found, message -> _state.value = SyncStatus.Scanning(found, message) },
                isStopped = { stopSignal.get() }
            )
            // Sólo las fuentes CONFIGURADAS (OneDrive con sesión, local con carpeta elegida).
            // Resiliencia multi-fuente: si una falla (p.ej. OneDrive sin red), las demás siguen.
            // Si TODAS fallan, propagamos el primer error (equivale al comportamiento anterior
            // con una sola fuente: auth/red terminan en Failed).
            var succeeded = 0
            var sourceFailure: Exception? = null
            for (source in sourceRegistry.activeSources()) {
                if (stopSignal.get()) break
                try {
                    val res = source.discover(force, discoverCtx)
                    changesCount += res.added
                    deletedCount += res.deleted
                    succeeded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Fuente ${source.type} falló en discover: ${e.message}")
                    if (sourceFailure == null) sourceFailure = e
                }
            }
            if (succeeded == 0 && sourceFailure != null) throw sourceFailure

            if (!stopSignal.get()) {
                // Healing: canciones ya descargadas cuyo análisis de metadata falló en su
                // momento (duration=0 con needsMetadata=0 — p. ej. el bug de setDataSource
                // con ':' en el nombre). Se re-encolan y el pipeline las repara EN LOCAL:
                // el check de archivo-existente evita re-descargar.
                val requeued = musicRepository.requeueDownloadedSongsWithoutMetadata()
                if (requeued > 0) Log.i(TAG, "Healing: $requeued canciones descargadas sin metadata re-encoladas")

                // Backfill de géneros (v24, una vez): re-lee el tag GENRE de lo ya descargado en
                // local para poblar los chips de género del inicio. No re-descarga nada.
                if (!musicPreferences.loadGenreBackfillDone()) {
                    val tagged = musicDownloader.backfillGenres()
                    musicPreferences.saveGenreBackfillDone()
                    Log.i(TAG, "Backfill de géneros: $tagged canciones con género leído")
                }

                // Duplicados entre fuentes (v23, política del usuario): ANTES de descargar.
                // Con política elegida se fusionan las perdedoras (re-apunte incluido); sin
                // política y con duplicados presentes, se dispara el diálogo de decisión y
                // las copias de nube en disputa no se descargan todavía.
                handleCrossSourceDuplicates()

                val (dl, fl) = processQueue()
                downloaded = dl
                failed = fl

                // Fotos de artista (Deezer) pendientes: mismo rol que los healings de arriba
                // (reparación post-scan), pero fire-and-forget en el scope — no retrasa el
                // SyncOutcome ni puede hacerlo fallar, y release() lo cancela en logout.
                scope.launch {
                    try {
                        artistImageRepository.backfillMissingImages()
                    } catch (_: Exception) {
                        // Cosmético: si falla (red), el próximo sync o sesión lo reintenta.
                    }
                }
            }
        } catch (e: com.qhana.siku.data.source.SourceAuthException) {
            authError = true
            fatal = e
            Log.e(TAG, "Auth token error: ${e.message}")
            _state.value = SyncStatus.Error(e.message ?: "Authentication failed")
        } catch (e: CancellationException) {
            // Cancelación cooperativa (worker reemplazado por pull-to-refresh, logout,
            // restricción de batería/red por WorkManager). No es un error visible.
            wasCancelled = true
            Log.d(TAG, "Sync cancelled cooperatively: ${e.message}")
            throw e
        } catch (e: Exception) {
            fatal = e
            _state.value = SyncStatus.Error("Critical error: ${e.message}")
        } finally {
            if (wasCancelled || stopSignal.get()) {
                _state.value = SyncStatus.Idle
            } else if (_state.value !is SyncStatus.Error) {
                val complete = SyncStatus.Complete(changesCount, downloaded, failed, deletedCount)
                _state.value = complete
                _completedEvents.tryEmit(complete)
            }
            isScanning.set(false)
        }

        val reason = queueStopReason.get()
        return when {
            authError -> SyncOutcome.Failed(fatal?.message ?: "Authentication failed", fatal, isAuthError = true)
            fatal != null -> SyncOutcome.Failed(fatal.message ?: fatal.javaClass.simpleName, fatal)
            stopSignal.get() -> SyncOutcome.Incomplete(IncompleteReason.CANCELLED)
            reason != null -> {
                Log.w(TAG, "Sync incompleto: $reason (downloaded=$downloaded, failed=$failed)")
                SyncOutcome.Incomplete(reason)
            }
            else -> {
                // Si quedaron canciones en backoff (fallidas con nextRetryAt futuro), se lo
                // contamos a ScanWorker para que programe una continuación a esa hora — sin
                // esto, las fallidas solo se reintentarían en el próximo open de la app.
                val pendingRetryAt = try { musicRepository.getEarliestRetryAt() } catch (e: Exception) { null }
                SyncOutcome.Completed(pendingRetryAt)
            }
        }
    }

    /** Detección/aplicación de la política de duplicados; corre en cada sync ANTES de descargar. */
    private suspend fun handleCrossSourceDuplicates() {
        try {
            val policy = musicPreferences.loadDuplicatePolicy()
            if (policy == null) {
                val count = musicRepository.countCrossSourceDuplicates()
                if (count > 0) {
                    undecidedDuplicateIds = musicRepository
                        .findCrossSourceDuplicates(com.qhana.siku.data.model.SourceType.ONEDRIVE)
                        .map { it.loserId }
                        .toHashSet()
                    _duplicateDecisionNeeded.value = count
                } else {
                    undecidedDuplicateIds = emptySet()
                    _duplicateDecisionNeeded.value = null
                }
                return
            }
            undecidedDuplicateIds = emptySet()
            _duplicateDecisionNeeded.value = null
            applyDuplicatePolicy(policy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cosmético/estructural pero no fatal para el sync: se reintenta en el próximo scan.
            Log.w(TAG, "Dedup de fuentes falló: ${e.message}")
        }
    }

    /**
     * Fusión IDEMPOTENTE de la política elegida: por cada par, re-apunta playlists (favoritos
     * incluidos), suma el historial a la ganadora y retira las perdedoras (deleteSongs también
     * borra el audio file:// de una copia de nube descargada; los content:// locales no se tocan).
     */
    private suspend fun applyDuplicatePolicy(policy: DuplicatePolicy) {
        if (policy == DuplicatePolicy.KEEP_BOTH) return
        val loser = if (policy == DuplicatePolicy.PREFER_CLOUD)
            com.qhana.siku.data.model.SourceType.LOCAL
        else
            com.qhana.siku.data.model.SourceType.ONEDRIVE
        val pairs = musicRepository.findCrossSourceDuplicates(loser)
        if (pairs.isEmpty()) return
        Log.i(TAG, "Duplicados: fusionando ${pairs.size} filas de ${loser.name} en su copia de la otra fuente")
        for ((loserId, winnerId) in pairs) {
            musicRepository.repointSongRefs(loserId, winnerId)
            musicRepository.mergePlayStats(loserId, winnerId)
        }
        musicRepository.deleteSongs(pairs.map { it.loserId })
    }

    /**
     * Respuesta del usuario al diálogo de duplicados: persiste la política, la aplica ya y
     * relanza un sync incremental para completar lo que quedó en espera de la decisión
     * (p. ej. descargas de nube retenidas por [undecidedDuplicateIds]).
     */
    /**
     * "Ahora no" del diálogo: oculta la pregunta SIN persistir política — el próximo scan
     * vuelve a detectar. Las copias en disputa siguen retenidas (no se descargan) esta corrida.
     */
    fun dismissDuplicateDecision() {
        _duplicateDecisionNeeded.value = null
    }

    fun resolveDuplicateDecision(policy: DuplicatePolicy) {
        musicPreferences.saveDuplicatePolicy(policy)
        undecidedDuplicateIds = emptySet()
        _duplicateDecisionNeeded.value = null
        scope.launch {
            try {
                applyDuplicatePolicy(policy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "applyDuplicatePolicy tras la decisión falló: ${e.message}")
            }
            startSync(false)
        }
    }

    fun prioritizeSong(songId: String) {
        prioritySongId.value = songId
        // Si hay un productor de sync activo, él consume prioritySongId (vía handlePrioritySong).
        // Si NO lo hay (solo reproduciendo, sin sync), disparamos una descarga individual para
        // que "reproducir → cachear (+ desalojar bajo el tope)" funcione igual. El proceso sigue
        // vivo por el foreground de reproducción, así que la descarga en scope propio completa.
        if (!producerActive.get() && priorityInFlight.add(songId)) {
            scope.launch {
                try {
                    downloadSong(songId, force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Descarga prioritaria de $songId falló: ${e.message}")
                } finally {
                    priorityInFlight.remove(songId)
                }
            }
        }
    }
    fun clearPriority() { prioritySongId.value = null }

    // ==================== CONTROL DE DESCARGAS (pausa / stop) ====================

    /**
     * Pausa las descargas masivas: el productor deja de encolar trabajo nuevo en su próxima
     * iteración; las descargas en vuelo/buffer terminan solas. Persistente (un ScanWorker que
     * arranque en frío también lo respeta). NO usa stopSignal — eso es para logout.
     */
    fun pauseDownloads() {
        musicPreferences.saveDownloadControlState(DownloadControlState.PAUSED)
        // Nueva pausa = nuevo aviso: el cierre "una vez" del banner deja de aplicar
        // (el silencio permanente, saveDownloadBannerMuted, NO se toca nunca).
        musicPreferences.saveStopBannerDismissed(false)
    }

    /** Reanuda las descargas masivas. El caller debe además agendar un scan para retomar. */
    fun resumeDownloads() {
        musicPreferences.saveDownloadControlState(DownloadControlState.ACTIVE)
        musicPreferences.saveStopBannerDismissed(false)
    }

    /**
     * Detiene las descargas masivas (persistente) y arma el banner de "descargas pendientes".
     * Igual que pausa a nivel de gate, pero con aviso global para que el usuario decida.
     */
    fun stopDownloads() {
        musicPreferences.saveDownloadControlState(DownloadControlState.STOPPED)
        musicPreferences.saveStopBannerDismissed(false)
    }

    /** Cierra el banner UNA VEZ: reaparece ante la próxima pausa/detención (sin reanudar). */
    fun dismissStopBanner() = musicPreferences.saveStopBannerDismissed(true)

    /** Silencia el banner PARA SIEMPRE (persistente, nunca se auto-resetea). */
    fun muteDownloadBanner() = musicPreferences.saveDownloadBannerMuted(true)

    /**
     * Desaloja el excedente actual bajo el tope de almacenamiento (sin descargar nada). Se
     * llama al bajar el tope desde Ajustes, para que el efecto sea inmediato.
     */
    suspend fun enforceStorageLimit() {
        try {
            ensureRoomForDownload(incomingSize = 0L, excludeId = "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "enforceStorageLimit falló: ${e.message}")
        }
    }

    /**
     * Garantiza que quepa [incomingSize] bytes bajo el tope, desalojando descargas LRU
     * (nunca reproducidas primero, luego las más antiguas / menos escuchadas) salvo [excludeId].
     * Sin tope (0) no hace nada. Solo lo usan las descargas PRIORITARIAS/individuales: el sync
     * masivo NO desaloja (sería churn: bajar A, borrar A para bajar B...).
     *
     * @return true si hay sitio (o no hay tope); false si ni vaciando cabe (la canción es más
     *         grande que el tope entero) — el caller debe abstenerse de descargar.
     */
    private suspend fun ensureRoomForDownload(incomingSize: Long, excludeId: String): Boolean {
        val cap = musicPreferences.loadStorageLimitBytes()
        if (cap <= 0L) return true
        if (incomingSize > cap) return false
        var total = musicRepository.getTotalDownloadedBytes()
        if (total + incomingSize <= cap) return true
        // La canción en reproducción (última priorizada) tampoco se desaloja: borrarle el
        // archivo bajo los pies obliga a re-streamear lo que ya estaba en disco.
        val playingId = prioritySongId.value
        val candidates = musicRepository.getEvictionCandidates(excludeId)
        for ((id, size) in candidates) {
            if (total + incomingSize <= cap) break
            if (id == playingId) continue
            musicRepository.deleteAudioFileById(id)
            total -= size
            Log.i(TAG, "Desalojo LRU: $id liberó ${size / 1024}KB para respetar el tope de caché")
        }
        return total + incomingSize <= cap
    }
    /**
     * Reintenta las descargas fallidas: limpia su estado de error en BD (attempts,
     * nextRetryAt) y marca sus IDs en `retryRequests` para que el productor activo las
     * saque de `attempted` y las re-procese.
     *
     * @return true si hay un productor activo que las va a consumir en esta corrida;
     *         false si el caller debe agendar un ScanWorker (con el error limpiado en BD,
     *         el próximo sync las reintenta solo). Antes este caso lanzaba `startSync` en
     *         el scope interno — SIN foreground service, con lo que el reintento moría al
     *         backgroundear la app.
     */
    suspend fun retryFailedDownloads(): Boolean {
        val ids = musicRepository.resetDownloadErrors()
        if (ids.isEmpty()) return true
        retryRequests.addAll(ids)
        return isScanning.get() && producerActive.get()
    }
    /**
     * Marca que el mapa de descargas activas cambió y asegura que haya un publicador vivo.
     * NO emite en el acto: el publicador muestrea a [ACTIVE_DOWNLOADS_PUBLISH_MS].
     */
    private fun markDownloadsDirty() {
        downloadsDirty.set(true)
        ensureDownloadsPublisher()
    }

    private fun ensureDownloadsPublisher() {
        synchronized(publisherLock) {
            if (publisherJob?.isActive == true) return
            publisherJob = scope.launch { runDownloadsPublisher() }
        }
    }

    /**
     * Único publicador de `activeDownloads`: mientras haya descargas activas o cambios
     * pendientes, vuelca un snapshot como mucho cada 300ms. El estado final (mapa vacío)
     * SIEMPRE se publica gracias al `finally` — incluso si el scope se cancela (logout).
     */
    private suspend fun runDownloadsPublisher() {
        try {
            while (activeDownloadsMap.isNotEmpty() || downloadsDirty.get()) {
                if (downloadsDirty.getAndSet(false)) {
                    _activeDownloads.value = activeDownloadsMap.values.toList()
                }
                delay(ACTIVE_DOWNLOADS_PUBLISH_MS)
            }
        } finally {
            _activeDownloads.value = activeDownloadsMap.values.toList()
        }
    }

    /**
     * Cancela el trabajo en curso del scope interno (retries, priority jobs) sin matar el
     * scope, de modo que siga siendo usable tras un nuevo login. Llamar en logout para no
     * dejar descargas/sync corriendo con un token que va a invalidarse.
     */
    fun release() {
        stopSignal.set(true)
        scope.coroutineContext.cancelChildren()
        // El publicador se canceló junto con el scope; garantizamos el estado final vacío.
        activeDownloadsMap.clear()
        _activeDownloads.value = emptyList()
    }

    /**
     * Descarga puntual centralizada de una canción. **Único punto de entrada** para
     * descargas individuales (workers, redescarga manual, descarga desde NowPlaying).
     *
     * - Reutiliza `MusicDownloader` (watchdog, validaciones de seguridad, finalize) y la
     *   resolución fresca de URL (`resolveDownloadUrl`) del propio SyncManager.
     * - Alimenta `activeDownloads` igual que el pipeline masivo → la descarga aparece en
     *   el panel sin código adicional.
     * - Idempotente: si la canción ya tiene archivo local válido y `force=false`, retorna
     *   éxito sin red. Con `force=true` invalida caché de URL y borra el archivo previo.
     */
    suspend fun downloadSong(songId: String, force: Boolean = false): MusicDownloader.Result {
        val song = musicRepository.getSongById(songId).getOrNull()
            ?: return MusicDownloader.Result.Error("Song not found: $songId")

        // LOCAL nunca se descarga (ya vive en el dispositivo) ni cuenta contra el tope.
        if (song.sourceType == SourceType.LOCAL) return MusicDownloader.Result.Success(song)

        // Idempotencia: si ya está descargado COMPLETO y no se fuerza, no hacemos nada.
        // Un archivo truncado (corte de conexión limpio) no cuenta como descargado.
        // Va ANTES del token de auth: una canción ya en disco no necesita tocar la nube.
        if (!force && song.path.startsWith("file://")) {
            val file = java.io.File(song.path.removePrefix("file://"))
            if (file.exists() && file.length() > 0L &&
                !musicDownloader.looksTruncated(file.length(), song.size)
            ) return MusicDownloader.Result.Success(song)
        }

        // Fast-fail de auth con mensaje claro (la resolución de URL vive ahora en la fuente).
        when (val tokenResult = authManager.getAccessToken().firstOrNull()) {
            is AuthResult.Success -> { /* ok */ }
            is AuthResult.Error -> return MusicDownloader.Result.Error("Auth error: ${tokenResult.message}")
            else -> return MusicDownloader.Result.Error("Authentication failed")
        }

        // Tope de almacenamiento (caché LRU): hacemos sitio desalojando las descargas menos
        // valiosas (salvo esta canción). Si ni vaciando cabe (canción > tope entero), la
        // dejamos en streaming en vez de reventar el tope. Cancelled no registra fallo.
        if (!ensureRoomForDownload(song.size, excludeId = song.id)) {
            Log.w(TAG, "Canción '${song.title}' (${song.size} bytes) excede el tope de caché; se deja en streaming")
            return MusicDownloader.Result.Cancelled
        }

        // Force: borra TODOS los archivos previos de la canción (cualquier extensión), no solo
        // el de song.path — un huérfano con otra extensión haría que el check de "ya existe"
        // del pipeline se saltara la re-descarga.
        //
        // Al borrarlos, el `file://` de la BD apunta a la nada: se limpia en el acto para que la
        // canción cuente como NO descargada mientras dura la re-descarga (el chip pasa a STREAM
        // y la reproducción puede seguir por red). Si la descarga falla, el estado queda honesto:
        // sin archivo y sin path, y el próximo scan la vuelve a encolar.
        // Solo `file://` (audio descargado): una fuente LOCAL vive en su `content://` de SAF y
        // JAMÁS debe perder su path — ni se descarga ni se re-descarga.
        if (force) {
            musicDownloader.deleteExistingDownloads(song.id)
            if (song.path.startsWith("file://")) musicRepository.updateSongUrl(song.id, "")
        }

        activeDownloadsMap[song.id] = ActiveDownload(song, 0f, individual = true)
        markDownloadsDirty()
        return try {
            val onProgress: (Float) -> Unit = { progress ->
                activeDownloadsMap[song.id] = ActiveDownload(song, progress, individual = true)
                markDownloadsDirty()
            }
            val stage = runDownloadWithRetry(song, isPriority = true, onProgress = onProgress, forceFreshUrl = force)
            val res = when (stage) {
                is MusicDownloader.DownloadStage.Success -> musicDownloader.finalizeDownload(song, stage.targetFile)
                is MusicDownloader.DownloadStage.Error -> MusicDownloader.Result.Error(
                    stage.message, stage.exception,
                    transient = stage.kind == MusicDownloader.ErrorKind.TRANSIENT
                )
                MusicDownloader.DownloadStage.Cancelled -> MusicDownloader.Result.Cancelled
                MusicDownloader.DownloadStage.SkippedLowBattery -> MusicDownloader.Result.SkippedLowBattery
            }
            // Cola persistente: las descargas individuales también dejan rastro en BD.
            when (res) {
                is MusicDownloader.Result.Success -> musicRepository.clearDownloadError(song.id)
                is MusicDownloader.Result.Error -> recordDownloadFailure(song, res.message, res.transient)
                else -> { /* Cancelled / low battery: sin cambios de estado */ }
            }
            res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MusicDownloader.Result.Error("downloadSong exception: ${e.message}", e)
        } finally {
            activeDownloadsMap.remove(song.id)
            markDownloadsDirty()
        }
    }

    /**
     * Pipeline real productor-consumidor: el productor mete canciones en un Channel a
     * medida que hay espacio, y N workers las consumen en paralelo. A diferencia del
     * batch-scope anterior, un worker libre arranca la siguiente canción al instante
     * sin esperar a que otros 4 hermanos del mismo lote terminen. Eso elimina las
     * burbujas de throughput cuando alguna descarga es más lenta que el resto.
     *
     * Genérico (Fase 2): resuelve URLs vía `sourceRegistry` según `song.sourceType`, no vía
     * un `api`/`token` de OneDrive.
     */
    private suspend fun processQueue(): Pair<Int, Int> = coroutineScope {
        val total = musicRepository.countSongsNeedingWork()
        if (total == 0) return@coroutineScope Pair(0, 0)

        // Pausa/stop del usuario: si las descargas masivas están pausadas o detenidas y no hay
        // nada prioritario que atender, no mostramos banner de progreso ni encolamos trabajo.
        // Las descargas por reproducción siguen su propio camino (prioritizeSong → downloadSong).
        if (!musicPreferences.loadDownloadControlState().allowsMassDownload && prioritySongId.value == null) {
            return@coroutineScope Pair(0, 0)
        }

        // Tope de almacenamiento (caché LRU): el productor no encola nada que rompa el tope.
        // A diferencia del path prioritario, el masivo NO desaloja (sería churn). Se llena
        // hasta el tope y frena. `plannedBytes` parte de lo ya descargado y suma lo encolado.
        val capBytes = musicPreferences.loadStorageLimitBytes()
        val capActive = capBytes > 0L
        var plannedBytes = if (capActive) musicRepository.getTotalDownloadedBytes() else 0L

        // Paralelismo adaptativo: en un WiFi débil, 32 conexiones compiten entre sí por un
        // enlace que no da para alimentarlas (descargas lentas → stalls del watchdog de 60s
        // → reintentos que empeoran la congestión). Se dimensiona una vez por corrida; si
        // la calidad cambia a mitad de cola, la corrida siguiente lo recoge.
        val parallelism = computeParallelism()

        // Emitimos Downloading(0, total) ANTES de arrancar los workers. Si no, el estado se
        // queda en Scanning hasta que la PRIMERA descarga complete (~50s con FLAC grandes en
        // paralelo), y la UI muestra "Escaneando" durante casi un minuto aunque ya esté
        // bajando 16 canciones. Con esto el banner pasa a "Descargando 0/N" de inmediato.
        updateProgress(0, total)

        val current = AtomicInteger(0)
        val downloadedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val bytesDownloaded = java.util.concurrent.atomic.AtomicLong(0L)
        val startedAt = System.currentTimeMillis()
        val attempted = java.util.Collections.synchronizedSet(LinkedHashSet<String>(256))

        // Buffer del channel = parallelism*2: cuando un worker termina, ya hay
        // trabajo en el buffer para él sin esperar al productor (que puede estar
        // consultando la BD).
        val workChannel = Channel<Song>(capacity = parallelism * 2)

        // Pipeline de finalize desacoplado: el análisis post-descarga (metadata, ReplayGain,
        // carátula, BD) es trabajo local que toma segundos por canción. Dentro del worker de
        // descarga dejaba la conexión OneDrive ociosa ese tiempo — con N workers, varios
        // MB/s perdidos. Los bytes ya están en disco: aunque la cola se detenga por
        // batería/red, finalizar lo ya descargado es gratis y evita re-descargas.
        val finalizeChannel = Channel<Pair<Song, java.io.File>>(capacity = parallelism * 2)
        val finalizers = List(FINALIZE_PARALLELISM) {
            launch {
                for ((song, file) in finalizeChannel) {
                    if (stopSignal.get()) continue // logout: drenar sin tocar BD
                    val res = musicDownloader.finalizeDownload(song, file)
                    if (res is MusicDownloader.Result.Success) {
                        downloadedCount.incrementAndGet()
                        musicRepository.clearDownloadError(song.id)
                    } else {
                        val msg = (res as? MusicDownloader.Result.Error)?.message ?: "Finalize falló"
                        recordDownloadFailure(song, msg, transient = true)
                        failedCount.incrementAndGet()
                    }
                    updateProgress(current.incrementAndGet(), total, failedCount.get())
                }
            }
        }

        val workers = List(parallelism) {
            launch {
                for (song in workChannel) {
                    // Drenaje: si la cola se detuvo (logout, batería, red), seguimos
                    // consumiendo sin descargar para no dejar al productor bloqueado en
                    // send(). Las canciones drenadas siguen "needing work" en BD y las
                    // retoma el próximo sync.
                    if (stopSignal.get() || queueStopReason.get() != null) continue

                    // Pausa/stop del usuario: drenar los temas YA en el buffer del canal sin
                    // descargarlos (siguen "needing work"; se retoman al reanudar). Sin este
                    // check, la pausa solo frenaba al productor y los ~parallelism*2 temas ya
                    // bufferizados seguían bajando ("le puse pausa y sigue descargando").
                    if (!musicPreferences.loadDownloadControlState().allowsMassDownload) continue

                    val onProgress: (Float) -> Unit = {
                        activeDownloadsMap[song.id] = ActiveDownload(song, it)
                        markDownloadsDirty()
                    }
                    activeDownloadsMap[song.id] = ActiveDownload(song, 0f)
                    markDownloadsDirty()

                    // El remove va en finally: si el worker se cancela (logout/pull-to-refresh)
                    // durante la descarga, hay que sacar la canción del mapa igual, o el
                    // publicador lo vería no-vacío para siempre y no publicaría el estado final.
                    val stage = try {
                        downloadWithTransientRetry(song, onProgress)
                    } finally {
                        activeDownloadsMap.remove(song.id)
                        markDownloadsDirty()
                    }

                    when (stage) {
                        is MusicDownloader.DownloadStage.Success -> {
                            bytesDownloaded.addAndGet(stage.targetFile.length())
                            finalizeChannel.send(song to stage.targetFile)
                        }
                        is MusicDownloader.DownloadStage.Error -> {
                            recordDownloadFailure(song, stage.message, stage.kind == MusicDownloader.ErrorKind.TRANSIENT)
                            failedCount.incrementAndGet()
                            updateProgress(current.incrementAndGet(), total, failedCount.get())
                        }
                        MusicDownloader.DownloadStage.Cancelled -> { /* drenada, no cuenta */ }
                        // Batería baja: detener la cola con motivo explícito (NO stopSignal:
                        // eso es para logout). ScanWorker devuelve retry() y WorkManager
                        // reanuda cuando la constraint de batería lo permita.
                        MusicDownloader.DownloadStage.SkippedLowBattery ->
                            queueStopReason.compareAndSet(null, IncompleteReason.LOW_BATTERY)
                    }
                }
            }
        }

        // Productor
        producerActive.set(true)
        try {
            // Offset de paginación: si la ventana LIMIT está llena de canciones ya
            // intentadas (fallidas que siguen "needing work" al frente del orden
            // alfabético), avanzamos la ventana en vez de romper el loop. Sin esto,
            // ≥BATCH_SIZE fallos acumulados dejaban el resto de la cola sin intentar.
            var offset = 0
            // Los `yield(); continue` de abajo NO son polling: cuando el masivo está frenado
            // (pausa, tope, lote vacío) el bucle solo sigue vivo si quedó una petición
            // PRIORITARIA o un retry, y ambos se atienden y se limpian al principio de la
            // vuelta siguiente (handlePrioritySong → clearPriority; retryRequests.removeAll).
            // Es decir: cada vuelta extra hace trabajo real y luego rompe, así que esperar un
            // intervalo fijo solo añadiría latencia a la canción que el usuario acaba de
            // pulsar. `yield` cede el dispatcher (y es punto de cancelación) sin esa latencia.
            while (!stopSignal.get() && queueStopReason.get() == null) {
                // Espera por SEÑAL (StateFlow), no polling: despierta en cuanto termina la
                // descarga prioritaria. El `continue` re-evalúa stopSignal/queueStopReason.
                if (requestCoordinator.shouldPauseScan()) { requestCoordinator.awaitScanResumed(); continue }

                // Red caída: pausar y esperar reconexión en vez de quemar la cola.
                if (!networkManager.isAvailable()) {
                    if (!waitFor(NETWORK_WAIT_ATTEMPTS) { networkManager.isAvailable() }) {
                        queueStopReason.compareAndSet(null, IncompleteReason.NETWORK_LOST)
                    }
                    continue
                }

                // La prioridad se atiende ANTES del gate de WiFi: una descarga prioritaria
                // (canción sonando) está permitida en datos móviles.
                if (retryRequests.isNotEmpty()) {
                    val toRetry = HashSet(retryRequests)
                    retryRequests.removeAll(toRetry)
                    attempted.removeAll(toRetry)
                }
                prioritySongId.value?.let { handlePrioritySong(it, attempted) }

                // Pausa/stop del usuario en caliente: dejamos de encolar trabajo masivo. La
                // prioridad ya se atendió arriba; si no queda nada prioritario/retry, cerramos.
                if (!musicPreferences.loadDownloadControlState().allowsMassDownload) {
                    if (prioritySongId.value == null && retryRequests.isEmpty()) break
                    yield(); continue
                }

                // Tope alcanzado: no cabe más audio bajo el límite. El masivo no desaloja.
                if (capActive && plannedBytes >= capBytes) {
                    if (prioritySongId.value == null && retryRequests.isEmpty()) break
                    yield(); continue
                }

                // Descargas masivas solo por WiFi: si se pierde, esperamos un rato por si
                // vuelve; si no vuelve, terminamos con NO_WIFI y ScanWorker encadena una
                // continuación con constraint UNMETERED.
                if (!networkManager.isWifi()) {
                    if (!waitFor(WIFI_WAIT_ATTEMPTS) { networkManager.isWifi() }) {
                        queueStopReason.compareAndSet(null, IncompleteReason.NO_WIFI)
                    }
                    continue
                }

                val raw = musicRepository.getSongsNeedingMetadataOrDownload(BATCH_SIZE, offset)
                // Duplicados en DISPUTA (detectados, usuario aún sin decidir): no gastar
                // red descargando copias de nube que quizás se retiren con "solo local".
                val pending = raw.filter { it.id !in attempted && it.id !in undecidedDuplicateIds }
                if (pending.isEmpty()) {
                    if (raw.size >= BATCH_SIZE) { offset += BATCH_SIZE; continue }
                    if (prioritySongId.value == null && retryRequests.isEmpty()) break
                    offset = 0; yield(); continue
                }
                offset = 0
                var budgetExhausted = false
                for (song in pending) {
                    if (stopSignal.get() || queueStopReason.get() != null || prioritySongId.value != null) break
                    // Las YA descargadas (solo les falta metadata, p.ej. healing) no consumen
                    // presupuesto: sus bytes ya están dentro de getTotalDownloadedBytes() y
                    // volver a sumarlos frenaba el productor antes de tiempo ("tope alcanzado"
                    // sin haber encolado nada nuevo).
                    val consumesBudget = capActive && !song.path.startsWith("file://")
                    // Tope: si la próxima canción no cabe, cerramos el productor (los FLAC son
                    // de tamaño parecido, no vale la pena buscar una más chica que quepa).
                    if (consumesBudget && plannedBytes + song.size > capBytes) { budgetExhausted = true; break }
                    // Marcamos attempted antes de send para que un retry concurrente o
                    // una próxima consulta de BD no vuelva a encolar la misma canción.
                    if (attempted.add(song.id)) {
                        workChannel.send(song)
                        if (consumesBudget) plannedBytes += song.size
                    }
                }
                if (budgetExhausted) {
                    if (prioritySongId.value == null && retryRequests.isEmpty()) break
                    yield(); continue
                }
            }
        } finally {
            producerActive.set(false)
            workChannel.close()
        }

        workers.joinAll()
        finalizeChannel.close()
        finalizers.joinAll()

        // Métrica para tunear MAX_PARALLEL_WIFI: buscar "Throughput" en logcat tras un
        // sync masivo. Si no escala respecto a 16 conexiones, OneDrive está capando la
        // cuenta o aparecieron 429.
        val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0
        val mb = bytesDownloaded.get() / (1024.0 * 1024.0)
        if (mb > 0 && elapsedSec > 0) {
            Log.i(TAG, "Throughput: %.1f MB en %.0fs -> %.2f MB/s (%d conexiones)"
                .format(mb, elapsedSec, mb / elapsedSec, parallelism))
        }

        Pair(downloadedCount.get(), failedCount.get())
    }

    /**
     * Dimensiona los workers de descarga según el enlace: conexiones = ancho de banda del
     * enlace / throughput por-conexión de OneDrive, acotado a [MIN_PARALLEL_WIFI,
     * MAX_PARALLEL_WIFI]. Si el sistema no reporta ancho de banda (0), se asume línea
     * rápida y se usa el techo (comportamiento histórico).
     */
    private fun computeParallelism(): Int {
        val kbps = networkManager.downlinkKbps()
        if (kbps <= 0) return MAX_PARALLEL_WIFI
        val linkMBps = kbps / 8_000.0
        val computed = kotlin.math.ceil(linkMBps / ONEDRIVE_PER_CONNECTION_MBPS).toInt()
            .coerceIn(MIN_PARALLEL_WIFI, MAX_PARALLEL_WIFI)
        Log.i(TAG, "Paralelismo adaptativo: enlace %.1f MB/s -> %d conexiones".format(linkMBps, computed))
        return computed
    }

    /**
     * Descarga con reintento ante errores TRANSITORIOS (red, stall, 5xx): hasta
     * MAX_SONG_ATTEMPTS intentos con backoff lineal. Si durante el backoff la red
     * desaparece y no vuelve, marca la cola para detenerse en vez de seguir fallando.
     * Envuelve a [runDownloadWithRetry] (que ya cubre el caso URL expirada con 4xx).
     */
    private suspend fun downloadWithTransientRetry(
        song: Song,
        onProgress: (Float) -> Unit
    ): MusicDownloader.DownloadStage {
        var stage = runDownloadWithRetry(song, isPriority = false, onProgress = onProgress)
        var attempt = 1
        while (attempt < MAX_SONG_ATTEMPTS &&
            stage is MusicDownloader.DownloadStage.Error &&
            stage.kind == MusicDownloader.ErrorKind.TRANSIENT &&
            !stopSignal.get() && queueStopReason.get() == null
        ) {
            Log.w(TAG, "Transient error for ${song.title} (attempt $attempt/$MAX_SONG_ATTEMPTS): ${stage.message}")
            delay(SONG_RETRY_BACKOFF_MS * attempt)
            if (!networkManager.isAvailable() &&
                !waitFor(NETWORK_WAIT_ATTEMPTS) { networkManager.isAvailable() }
            ) {
                queueStopReason.compareAndSet(null, IncompleteReason.NETWORK_LOST)
                return stage
            }
            stage = runDownloadWithRetry(song, isPriority = false, onProgress = onProgress)
            attempt++
        }
        return stage
    }

    /**
     * Espera hasta que [condition] sea true, sondeando cada NETWORK_POLL_MS. Cuenta
     * iteraciones (no wall-clock) a propósito: con virtual time en tests el deadline
     * sigue funcionando. Retorna false si se agotan los intentos o llega stopSignal.
     */
    private suspend fun waitFor(attempts: Int, condition: () -> Boolean): Boolean {
        repeat(attempts) {
            if (stopSignal.get()) return false
            delay(NETWORK_POLL_MS)
            if (condition()) return true
        }
        return condition()
    }

    private fun updateProgress(current: Int, total: Int, failed: Int = 0) {
        _state.value = SyncStatus.Downloading(min(current, total), total, failed, "Syncing library...")
    }

    /**
     * Persiste el fallo en BD (cola persistente v18): incrementa el contador de intentos
     * y fija nextRetryAt según la política de backoff. El productor salta la canción
     * hasta entonces; ScanWorker programa una continuación para cuando venza.
     */
    private suspend fun recordDownloadFailure(song: Song, message: String, transient: Boolean) {
        try {
            val attempts = musicRepository.getDownloadAttempts(song.id) + 1
            val backoffMs = if (transient) {
                min(attempts * TRANSIENT_BACKOFF_STEP_MS, TRANSIENT_BACKOFF_MAX_MS)
            } else {
                PERMANENT_BACKOFF_MS
            }
            musicRepository.markDownloadFailed(song.id, message, transient, attempts, System.currentTimeMillis() + backoffMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo persistir el fallo de ${song.title}: ${e.message}")
        }
    }

    private suspend fun handlePrioritySong(id: String, attempted: MutableSet<String>) {
        musicRepository.getSongById(id).getOrNull()?.let { song ->
            priorityMutex.withLock {
                priorityJob = scope.launch { processSingleSongResult(song, true) }
                priorityJob?.join()
            }
        }
        clearPriority()
        attempted.add(id)
    }

    private suspend fun processSingleSongResult(song: Song, isPriority: Boolean): MusicDownloader.Result {
        // Ya descargada con archivo válido (no truncado): nada que hacer (evita un getItem
        // inútil cuando MusicController prioriza una canción que ya está local).
        if (song.path.startsWith("file://")) {
            val file = java.io.File(song.path.removePrefix("file://"))
            if (file.exists() && file.length() > 0L &&
                !musicDownloader.looksTruncated(file.length(), song.size)
            ) return MusicDownloader.Result.Success(song)
        }
        // Tope de almacenamiento: desalojo LRU para que quepa la prioritaria. Si ni vaciando
        // cabe (canción > tope entero), se deja en streaming (Cancelled no registra fallo).
        if (!ensureRoomForDownload(song.size, excludeId = song.id)) return MusicDownloader.Result.Cancelled
        return try {
            activeDownloadsMap[song.id] = ActiveDownload(song, 0f, individual = isPriority)
            markDownloadsDirty()
            if (!networkManager.isWifi() && !isPriority) return MusicDownloader.Result.Success(song)
            val onProgress: (Float) -> Unit = {
                activeDownloadsMap[song.id] = ActiveDownload(song, it, individual = isPriority)
                markDownloadsDirty()
            }
            val stage = runDownloadWithRetry(song, isPriority, onProgress)
            when (stage) {
                is MusicDownloader.DownloadStage.Success -> musicDownloader.finalizeDownload(song, stage.targetFile)
                is MusicDownloader.DownloadStage.Error -> MusicDownloader.Result.Error(
                    stage.message, stage.exception,
                    transient = stage.kind == MusicDownloader.ErrorKind.TRANSIENT
                )
                MusicDownloader.DownloadStage.Cancelled -> MusicDownloader.Result.Cancelled
                MusicDownloader.DownloadStage.SkippedLowBattery -> MusicDownloader.Result.SkippedLowBattery
            }
        } catch (e: Exception) { MusicDownloader.Result.Error(e.message ?: "Error") }
        finally {
            activeDownloadsMap.remove(song.id)
            markDownloadsDirty()
        }
    }

    /**
     * URL de descarga vía la fuente (rutea por `song.sourceType`). Si no hay resolución
     * fresca, cae al `song.path` que haya. La lógica específica de OneDrive (getItem + caché +
     * el porqué de resolver siempre fresco) vive ahora en `OneDriveMusicSource`.
     */
    private suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean): String? =
        sourceRegistry.resolveDownloadUrl(song, forceRefresh) ?: song.path.takeIf { it.isNotEmpty() }

    /**
     * Descarga con reintento ante URL expirada. Si la primera descarga falla con HTTP 4xx
     * (URL OneDrive expirada o token de path firmado caducado), invalida cache y reintenta
     * con una URL refrescada vía la fuente una sola vez.
     */
    private suspend fun runDownloadWithRetry(
        song: Song,
        isPriority: Boolean,
        onProgress: (Float) -> Unit,
        forceFreshUrl: Boolean = false
    ): MusicDownloader.DownloadStage {
        // Bytes ya en disco (con CUALQUIER extensión, incluida la basura `.0` histórica):
        // no hay nada que pedir a la red — ni getItem ni GET. El finalize re-analiza y
        // corrige nombre/metadata. Es la vía por la que el healing repara la biblioteca
        // sin re-descargar. Con forceFreshUrl (re-descarga forzada) NO aplica: el caller
        // ya borró los archivos y quiere bytes nuevos.
        if (!forceFreshUrl) {
            musicDownloader.findExistingDownload(song.id, expectedSize = song.size)?.let { existing ->
                onProgress(1f)
                return MusicDownloader.DownloadStage.Success(existing)
            }
        }
        val firstUrl = resolveDownloadUrl(song, forceRefresh = forceFreshUrl)
            ?: return MusicDownloader.DownloadStage.Error("No URL resolvable for ${song.title}")
        val first = musicDownloader.downloadFile(song, firstUrl, isPriority, onProgress)
        if (first is MusicDownloader.DownloadStage.Error && first.httpCode != null && first.httpCode in 400..499) {
            Log.w(TAG, "URL expired (HTTP ${first.httpCode}) for ${song.title}, refreshing once")
            val freshUrl = resolveDownloadUrl(song, forceRefresh = true) ?: return first
            if (freshUrl == firstUrl) return first
            return musicDownloader.downloadFile(song, freshUrl, isPriority, onProgress)
        }
        return first
    }
}

/**
 * Resultado real de una corrida de sync. A diferencia de [SyncStatus] (estado para UI),
 * esto es lo que [com.qhana.siku.worker.ScanWorker] usa para decidir si devolver
 * success/retry/failure a WorkManager — antes el worker siempre devolvía success y los
 * syncs interrumpidos en background quedaban muertos hasta reabrir la app.
 */
sealed class SyncOutcome {
    /**
     * Scan + cola de descargas corrieron hasta agotar el trabajo elegible.
     * @param nextRetryAt si quedaron fallidas en backoff, el epoch ms del reintento más
     *        próximo — ScanWorker programa una continuación con ese delay.
     */
    data class Completed(val nextRetryAt: Long? = null) : SyncOutcome()
    /** Ya había un sync corriendo; esta invocación no hizo nada. */
    object Skipped : SyncOutcome()
    /** La corrida terminó antes de agotar el trabajo; el motivo decide si se reintenta. */
    data class Incomplete(val reason: IncompleteReason) : SyncOutcome()
    data class Failed(val message: String, val cause: Exception? = null, val isAuthError: Boolean = false) : SyncOutcome()
}

enum class IncompleteReason {
    /** stopSignal (logout / release): no reintentar. */
    CANCELLED,
    /** Batería <20% sin cargar: retry() — la constraint de batería regula la espera. */
    LOW_BATTERY,
    /** La red se fue y no volvió dentro del deadline: retry() con backoff. */
    NETWORK_LOST,
    /** Hay red pero no WiFi: encadenar continuación con constraint UNMETERED. */
    NO_WIFI
}

sealed class SyncStatus(val message: String, val isRunning: Boolean) {
    object Idle : SyncStatus("Idle", false)
    data class Scanning(val found: Int, val currentMessage: String = "Syncing...") : SyncStatus(currentMessage, true)
    data class Downloading(val current: Int, val total: Int, val failed: Int, val currentMessage: String = "Downloading...") : SyncStatus(currentMessage, true)
    data class Complete(val newSongs: Int, val downloaded: Int, val failed: Int, val deleted: Int = 0) : SyncStatus("Complete", false)
    data class Error(val errorMessage: String) : SyncStatus(errorMessage, false)
}

/**
 * @param individual true si la descarga la pidió el usuario para ESA canción (redescarga
 *        manual, botón de descarga, prioritaria por reproducción); false si viene del
 *        pipeline masivo del sync. La lista del home solo pinta progreso de las
 *        individuales — el avance del sync masivo ya lo cubren el banner y el
 *        Download Manager (que muestra todas).
 */
data class ActiveDownload(val song: Song, val progress: Float, val individual: Boolean = false)