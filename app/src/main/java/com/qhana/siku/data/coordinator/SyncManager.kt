package com.qhana.siku.data.coordinator

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qhana.siku.R
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    private val artistImageRepository: com.qhana.siku.data.repository.ArtistImageRepository,
    private val lightMetadataFetcher: LightMetadataFetcher,
    private val trackInfoBackfiller: TrackInfoBackfiller,
    private val artworkHealingManager: ArtworkHealingManager,
    private val localLibraryChangeMonitor: LocalLibraryChangeMonitor,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager,
    private val downloadScheduler: com.qhana.siku.worker.DownloadScheduler
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
        // initialParallelism() según el enlace, y se reajusta en caliente durante la corrida.
        //
        // NO es private: el ConnectionPool del cliente "download" (AppModule) se dimensiona
        // con este mismo valor — si el pool ocioso fuera menor que el nº de workers, las
        // conexiones sobrantes se cerrarían al terminar cada archivo y el siguiente pagaría
        // el handshake TLS. Antes estaba copiado a mano allí y podían desincronizarse.
        internal const val MAX_PARALLEL_WIFI = 32
        private const val MIN_PARALLEL_WIFI = 4

        // NO hay constante de "throughput por conexión de OneDrive". La hubo (0.5 MB/s) y era una
        // medición hecha en UN teléfono, UNA cuenta y UNA conexión, de la que salía el reparto de
        // conexiones de todo el mundo. Ahora ese número lo mide el aparato del usuario mientras
        // descarga (ver [adjustParallelism]): se arranca por el MÍNIMO y se sube en cuanto se
        // comprueba que sobra enlace.
        //
        // Cadencia con la que se remide y se redimensiona la corrida. La ventana no necesita que
        // ninguna descarga TERMINE —mide caudal, no piezas—, así que basta con que sea holgada
        // frente al arranque de una conexión (handshake TLS y primeros bloques, del orden de
        // décimas de segundo) y corta frente a lo que dura un sync. Diez segundos deja el
        // paralelismo ajustado en el primer medio minuto y sin remedir a cada frame.
        private const val RESIZE_WINDOW_MS = 10_000L

        // Peso de la corrida nueva al promediarla con lo aprendido: a partes iguales. NO es una
        // medición de nada —es la mezcla más simple que existe— y lo que consigue es que ninguna
        // corrida mande por sí sola: se llega a un valor nuevo en dos o tres syncs, que es el orden
        // en el que cambia una red de verdad.
        private const val THROUGHPUT_SMOOTHING = 0.5f

        // Fracción del enlace a partir de la cual se da por saturado y la corrida deja de servir
        // como medición del cap por conexión (ver [launchParallelismSupervisor]). No hay una frontera nítida
        // que descubrir: `downlinkKbps()` es la CAPACIDAD que estima el sistema, no una medida, y
        // suele venir redondeada al alza, así que exigir holgura evita concluir "OneDrive me está
        // capando" a partir del ruido de esa estimación. Se elige por el lado que no aprende: si se
        // sube demasiado, entran muestras saturadas y el ajuste se autoconfirma; si se baja, solo se
        // desaprovechan corridas y manda la semilla, que es el comportamiento de siempre.
        private const val SATURATED_LINK_FRACTION = 0.8

        // El enlace lo reporta el sistema en kbps y todo lo demás de este archivo va en MB/s.
        private const val KBPS_PER_MBPS = 8_000.0

        // Conversiones de las cuentas de caudal, para que no queden factores sueltos en medio de
        // una división.
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0

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
        // como fallidas. Son TECHOS de espera, no intervalos de sondeo: `awaitNetwork`
        // despierta en el instante en que la red vuelve (ver NetworkManager.status), así que
        // el margen solo decide cuánto se tolera antes de ceder el turno a WorkManager.
        //
        // Generoso a propósito: rendirse cuesta terminar el sync y reprogramar un worker,
        // mientras que esperar de más no consume nada —no hay descargas en curso— y absorbe
        // los cortes cotidianos (un ascensor, un cambio de AP, salir y volver a casa).
        private const val NETWORK_WAIT_TIMEOUT_MS = 2 * 60_000L
        private const val WIFI_WAIT_TIMEOUT_MS = 60_000L

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
        val details = exception.message ?: context.getString(R.string.sync_error_no_details)
        _state.value = SyncStatus.Error(context.getString(R.string.sync_error_unexpected, className, details))
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

    /**
     * Veces que la cola se ha quedado esperando a que vuelva la red o el WiFi. Igual que
     * `RequestCoordinator.throttleEvents`, solo sirve para descartar mediciones de throughput: son
     * segundos de reloj sin bajar un byte, y meterlos en la cuenta haría parecer lenta una conexión
     * que estuvo ausente, no lenta.
     */
    private val networkWaits = AtomicInteger(0)

    // Lista de fallidas respaldada por BD (v18): sobrevive a la muerte del proceso, a
    // diferencia del viejo MutableStateFlow en memoria que se vaciaba con cada restart.
    val failedDownloads: kotlinx.coroutines.flow.Flow<List<com.qhana.siku.data.repository.FailedDownload>> =
        musicRepository.getFailedDownloadsFlow()

    // Canciones recién descargadas (ver MusicDownloader.downloadedSongs). Se re-expone aquí para
    // que el reproductor no tenga que conocer al downloader: habla con el coordinador, igual que
    // con `activeDownloads` y `failedDownloads`.
    val downloadedSongs: kotlinx.coroutines.flow.SharedFlow<Song> = musicDownloader.downloadedSongs

    // IDs marcados para reintento. processQueue los saca de su set local `attempted`
    // al inicio de cada iteración, permitiendo re-procesar fallidas sin reiniciar el sync.
    private val retryRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * Canciones con bytes EN VUELO ahora mismo, por id, sea cual sea la vía (pipeline masivo,
     * descarga individual del worker, prioritaria por reproducción).
     *
     * Hace falta porque el archivo de trabajo se deriva del id (`<id>.part`): dos descargas
     * simultáneas de la misma canción escriben el MISMO archivo y el renombrado final puede
     * consagrar una mezcla de las dos que pase el control de tamaño. Con los tramos por `Range`
     * el riesgo es todavía mayor: cada una tendría su propio reparto del archivo y su propio
     * sidecar, y el último en escribirlo describiría bytes que no puso. Ninguno de los dedupes que
     * ya existían lo cubría — `attempted` es local a una corrida del productor y `priorityInFlight`
     * solo mira las prioritarias—, así que bastaba con pulsar "descargar" sobre una canción que el
     * sync masivo estaba bajando.
     *
     * Lo lee además la poda de carátulas: ver el porqué en [executeSync].
     */
    private val downloadsInFlight = ConcurrentHashMap.newKeySet<String>()

    private val prioritySongId = MutableStateFlow<String?>(null)
    // Dedupe de descargas prioritarias disparadas por reproducción cuando NO hay un productor
    // de sync activo (ver prioritizeSong): evita lanzar dos descargas individuales del mismo id.
    private val priorityInFlight = ConcurrentHashMap.newKeySet<String>()
    private val syncMutex = Mutex()
    private val isScanning = AtomicBoolean(false)

    /**
     * Corrutina del refresco local oportunista en curso ([refreshLocalSources]), o `null`.
     *
     * Existe para que un sync completo pueda ABORTARLO en vez de esperarlo: los dos listan las
     * mismas fuentes locales y el sync las va a recorrer igual, así que esperar solo sirve para
     * hacer el trabajo dos veces seguidas.
     */
    private val localRefreshJob = AtomicReference<Job?>(null)

    /**
     * Aborta el refresco local en curso, si lo hay. Idempotente y no suspende: se puede llamar
     * desde cualquier punto antes de tomar [syncMutex].
     */
    private fun cancelLocalRefresh() {
        localRefreshJob.getAndSet(null)?.let {
            if (it.isActive) {
                Log.d(TAG, "Refresco local abortado: llega un sync completo que ya lo cubre")
                it.cancel()
            }
        }
    }
    // StateFlow y no AtomicBoolean: además de leerse en los chequeos cooperativos, es una de
    // las señales que despiertan a `awaitNetwork`. Con una bandera opaca, un logout durante la
    // espera de red dejaba al worker (y a su foreground service) vivo hasta agotar el timeout.
    private val stopSignal = MutableStateFlow(false)
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

        // El refresco local oportunista, si lo hay, se ABORTA: este sync hace un SUPERCONJUNTO
        // de su trabajo, así que dejarlo terminar es listar la biblioteca dos veces seguidas.
        // Esperarlo era exactamente lo que retrasaba el arranque: medido en un Poco F5, el
        // ScanWorker pasaba 2,9 s bloqueado en el mutex mientras el refresco recorría los mismos
        // 777 archivos que él iba a recorrer a continuación, y el banner de sincronización no
        // aparecía hasta 3,7 s después de que la UI estuviera en pantalla.
        //
        // Cancelar es seguro y es lo correcto por jerarquía: el refresco es descartable por
        // definición (oportunista, silencioso, sin red) y su `finally` suelta el mutex. Lo que no
        // se puede es al revés — el refresco ya cede ante un sync en marcha con su `tryLock`.
        cancelLocalRefresh()

        // Mutex prevents two concurrent startSync calls from both proceeding
        syncMutex.withLock {
            if (isScanning.get()) return SyncOutcome.Skipped

            isScanning.set(true)
            stopSignal.value = false
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
     * Refresco de las fuentes LOCALES solamente: re-lista carpetas/dispositivo y aplica altas y
     * bajas. Nada de red, descargas, healing ni delta de nube.
     *
     * Existe porque el escaneo completo solo corre al ARRANCAR la app, y una app de música
     * prácticamente no vuelve a arrancar en frío: el servicio de reproducción mantiene vivo el
     * proceso, así que volver a ella tras copiar canciones nuevas no disparaba nada y la
     * biblioteca se quedaba vieja hasta un pull-to-refresh manual.
     *
     * Es barato justo porque el descubrimiento local NO es incremental: listar es un walk de
     * directorios (o una consulta a MediaStore) y solo se ANALIZAN los archivos cuyo id no estaba
     * ya en la BD, que es el trabajo caro. Sin novedades, esto no toca la BD.
     *
     * **Nunca corre a la vez que un sync completo, en ninguna de las dos direcciones**, porque
     * ese hace un superconjunto de este trabajo y solaparlos significa listar la biblioteca dos
     * veces seguidas:
     *  - sync YA en marcha → este refresco se salta (`tryLock`, abajo);
     *  - sync que llega DESPUÉS → aborta este refresco ([cancelLocalRefresh] en `startSync`).
     *
     * La segunda dirección faltaba, y era la que se notaba: el sync se quedaba esperando el mutex
     * y luego repetía el listado entero.
     *
     * @return canciones añadidas (0 también si no había fuentes locales o si se saltó).
     */
    suspend fun refreshLocalSources(): Int {
        val localSources = sourceRegistry.activeSources().filter { !it.type.isCloud }
        if (localSources.isEmpty()) return 0
        // ¿Cambió algo desde el último listado? El disparador de este método es "el usuario volvió
        // a la app", que ocurre muchísimas veces por sesión y casi nunca coincide con haber
        // copiado música. Sin esta pregunta, cada alt-tab pagaba el walk entero (ver
        // [LocalLibraryChangeMonitor], que también explica por qué no basta con la señal).
        if (!localLibraryChangeMonitor.shouldRefresh()) {
            Log.d(TAG, "Refresco local omitido: sin cambios en el almacenamiento")
            return 0
        }
        if (!syncMutex.tryLock()) {
            Log.d(TAG, "Refresco local omitido: ya hay un sync en marcha")
            return 0
        }
        // Publica la corrutina en curso para que un `startSync` posterior pueda abortarla en vez
        // de esperarla. Se registra DESPUÉS de tomar el mutex: antes de eso no hay nada que valga
        // la pena cancelar.
        localRefreshJob.set(currentCoroutineContext()[Job])

        return try {
            // Silencioso a propósito: no toca `_state`, así que no levanta el banner de sync.
            // Lo normal es que no encuentre nada, y anunciar un escaneo en cada vuelta a la app
            // sería ruido; lo que sí aparece —porque Room y Paging lo emiten solos— son las
            // canciones nuevas en la lista.
            // isStopped fijo en false, NO `stopSignal`: esa bandera la deja encendida `release()`
            // (logout) hasta el siguiente `startSync`, así que un usuario que desconecta OneDrive
            // y sigue con su música local se quedaría sin refrescos para siempre. Este trabajo se
            // corta por cancelación de su corrutina, que es lo que le corresponde: dura lo que
            // dura un listado.
            // El token se toma ANTES de listar: lo que este walk podrá dar por atendido es lo que
            // hubiera cambiado hasta aquí. Una señal que llegue mientras corre (copiar un archivo
            // durante los ~2,8 s del listado) queda pendiente para el siguiente regreso a la app.
            val listingToken = localLibraryChangeMonitor.beginListing()
            val ctx = com.qhana.siku.data.source.DiscoverContext(
                reportScanning = { _, _ -> },
                isStopped = { false }
            )
            var added = 0
            for (source in localSources) {
                try {
                    added += source.discover(force = false, ctx).added
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Refresco local de ${source.type} falló: ${e.message}")
                }
            }
            if (added > 0) Log.d(TAG, "Refresco local: $added canciones nuevas")

            // Carátulas locales pendientes. Sin esto, una biblioteca SOLO local solo las repararía
            // en el escaneo de arranque (una vez por proceso) o si el usuario hace pull-to-refresh
            // — y este proceso vive días. Es justo el caso que arrastra el fallo de escritura de
            // la 1.0.1, así que dejarlo fuera equivalía a no repararlo.
            //
            // Acotado a lo local (`localOnly`) porque este refresco es offline: las pendientes de
            // nube no se pueden resolver aquí y solo se cargarían para nada en cada vuelta a la
            // app. Va después del discover, que es lo que exige la migración de ids locales.
            artworkHealingManager.resolvePendingArtwork(localOnly = true)
            // Se consume la señal SOLO aquí, con el listado ya hecho: si esto se hubiera saltado
            // por el mutex o cancelado a medias, la marca sigue puesta para el siguiente intento.
            localLibraryChangeMonitor.markRefreshed(listingToken)
            added
        } finally {
            localRefreshJob.set(null)
            syncMutex.unlock()
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
        // Sesión caducada de UNA fuente teniendo otras que sí funcionan. Se guarda aquí y se
        // aplica al final de la corrida (ver `finally`): el resto del sync debe correr igual,
        // pero el desenlace NO puede ser "Biblioteca al día".
        var authFailure: Exception? = null
        queueStopReason.set(null)
        // ANTES del discover, por lo mismo que en `refreshLocalSources`: este sync solo puede dar
        // por atendido lo que había cambiado al empezar. Un sync masivo dura minutos, así que la
        // ventana en la que se perdería una señal tomándolo al final es enorme.
        val listingToken = localLibraryChangeMonitor.beginListing()
        try {
            _state.value = SyncStatus.Scanning(0, context.getString(R.string.sync_looking_for_changes))
            val discoverCtx = com.qhana.siku.data.source.DiscoverContext(
                reportScanning = { found, message -> _state.value = SyncStatus.Scanning(found, message) },
                isStopped = { stopSignal.value }
            )
            // Sólo las fuentes CONFIGURADAS (OneDrive con sesión, local con carpeta elegida).
            // Resiliencia multi-fuente: si una falla (p.ej. OneDrive sin red), las demás siguen.
            // Si TODAS fallan, propagamos el primer error (equivale al comportamiento anterior
            // con una sola fuente: auth/red terminan en Failed).
            var succeeded = 0
            var sourceFailure: Exception? = null
            for (source in sourceRegistry.activeSources()) {
                if (stopSignal.value) break
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
                    // Una carpeta que no existe es un error de CONFIGURACIÓN, no un tropiezo de
                    // red: hay que decirlo aunque otra fuente haya funcionado. Si no, quien tenga
                    // música local además de la nube ve "sincronizado" para siempre mientras su
                    // OneDrive no aporta una sola canción.
                    if (e is com.qhana.siku.data.source.SourceFolderMissingException) {
                        snackbarManager.show(
                            context.getString(R.string.sync_err_folder_missing, e.folder),
                            length = com.qhana.siku.data.util.SnackbarLength.LONG
                        )
                    }
                    // Una sesión caducada es de la MISMA categoría: no se arregla sola y exige
                    // volver a entrar. Se recuerda para aplicarla al final (ver el `finally`) en
                    // vez de relanzarla aquí, porque el resto del sync —lo local, el healing— sí
                    // debe correr; lo que no puede pasar es que la corrida termine en "Biblioteca
                    // al día" con la nube entera sin sincronizar y sin un solo aviso.
                    if (e is com.qhana.siku.data.source.SourceAuthException) {
                        authFailure = e
                        snackbarManager.show(
                            context.getString(R.string.sync_error_auth),
                            length = com.qhana.siku.data.util.SnackbarLength.LONG
                        )
                    }
                }
            }
            if (succeeded == 0 && sourceFailure != null) throw sourceFailure

            if (!stopSignal.value) {
                // Healing: canciones ya descargadas cuyo análisis de metadata falló en su
                // momento (duration=0 con needsMetadata=0 — p. ej. el bug de setDataSource
                // con ':' en el nombre). Se re-encolan y el pipeline las repara EN LOCAL:
                // el check de archivo-existente evita re-descargar.
                val requeued = musicRepository.requeueDownloadedSongsWithoutMetadata()
                if (requeued > 0) Log.i(TAG, "Healing: $requeued canciones descargadas sin metadata re-encoladas")

                // Duplicados entre fuentes (v23, política del usuario): ANTES de descargar.
                // Con política elegida se fusionan las perdedoras (re-apunte incluido); sin
                // política y con duplicados presentes, se dispara el diálogo de decisión y
                // las copias de nube en disputa no se descargan todavía.
                handleCrossSourceDuplicates()

                // Metadata ligera: rellena artista/álbum/portada leyendo solo la cabecera remota,
                // ANTES de descargar. Así la biblioteca se ve entera y con carátulas en minutos,
                // sin esperar a que el pipeline de audio (lento, acotado por el tope) termine.
                // Solo en WiFi: pedir cabeceras de cientos de canciones no debe gastar datos.
                if (!stopSignal.value && networkManager.isWifi()) {
                    try {
                        lightMetadataFetcher.run(
                            isStopped = { stopSignal.value },
                            onProgress = { done, total ->
                                _state.value = SyncStatus.Preparing(
                                    done, total, context.getString(R.string.sync_reading_tags)
                                )
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Metadata ligera falló: ${e.message}")
                    }
                }

                val (dl, fl) = processQueue()
                downloaded = dl
                failed = fl

                // Migración de datos de una sola pasada: número de pista y año de la biblioteca
                // que se escaneó antes de que esos tags se leyeran (ver TrackInfoBackfiller).
                // DESPUÉS de las descargas: las que se acaban de bajar ya escribieron su pista al
                // analizarse, así que no vuelven a mirarse; y las que el tope dejó en la nube no
                // tienen archivo que abrir, que es justo lo que esta fase exige. Reusa el banner
                // de "leyendo etiquetas": es literalmente lo que hace y evita otro string.
                if (!stopSignal.value) {
                    try {
                        _state.value = SyncStatus.Preparing(0, 0, context.getString(R.string.sync_reading_tags))
                        trackInfoBackfiller.run(isStopped = { stopSignal.value })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Cosmético: sin pista se sigue ordenando por título, como hasta ahora.
                        Log.w(TAG, "Backfill de pista/año falló: ${e.message}")
                    }
                }

                // Carátulas pendientes: las que la indexación dejó sin portada. Normalmente no
                // hay ninguna y esto es una consulta vacía.
                //
                // AQUÍ, y no justo después del discover: antes de la metadata ligera las
                // canciones de nube recién descubiertas llevan todavía el centinela de álbum y
                // su audio no está en el dispositivo, así que no habría nada que mirar y el
                // único efecto sería sellarlas sin haberlas podido intentar. Después de las
                // descargas, en cambio, cada fila tiene su álbum real y las que se bajaron
                // traen su portada — que es justo lo que las demás pueden heredar.
                // Sin cifras: estas dos fases no saben de antemano cuánto trabajo tienen (lo
                // habitual es que sea nada), pero pueden abrir y analizar archivos uno a uno en
                // una biblioteca recién descargada. Anunciarlas evita el mismo malentendido que
                // la metadata ligera: silencio prolongado con el banner del paso anterior puesto.
                if (!stopSignal.value) {
                    _state.value = SyncStatus.Preparing(0, 0, context.getString(R.string.sync_organizing_art))
                    artworkHealingManager.resolvePendingArtwork()

                    // Poda de carátulas huérfanas: AQUÍ y no antes. Es el primer punto en el que
                    // ya no queda nada escribiendo portadas (el escaneo flusheó sus lotes, la
                    // metadata ligera terminó y la cola de descargas también), así que "sin
                    // referencias" significa de verdad "sobra". Ver ArtworkHealingManager.pruneCovers.
                    //
                    // La excepción es una descarga PRIORITARIA por reproducción: corre fuera del
                    // `syncMutex` y en esta fase el productor ya no está activo, así que puede
                    // arrancar justo ahora. Su ventana `saveArtwork` → `updateSongMetadata` es
                    // precisamente la que el orden de lectura de la poda no puede cubrir (el
                    // archivo ya está en la foto del directorio y todavía no lo referencia nadie),
                    // así que con descargas en vuelo se pospone al próximo sync — no cuesta nada.
                    if (!stopSignal.value && downloadsInFlight.isEmpty()) {
                        artworkHealingManager.pruneCovers()
                    }

                    // Descargas a medias sin dueño. Desde que los parciales SOBREVIVEN a un fallo
                    // —que es lo que permite reanudarlos en vez de volver a empezar— pueden
                    // quedarse huérfanos: basta con que el usuario borre de la biblioteca una
                    // canción que se estaba bajando, y un parcial de un FLAC grande son cientos de
                    // MB. El de una canción que sigue pendiente NO se toca: ese es trabajo por
                    // terminar, no basura.
                    if (!stopSignal.value) {
                        musicDownloader.prunePartialDownloads(
                            knownIds = musicRepository.getAllSongIds().toHashSet(),
                            protectedIds = downloadsInFlight.toHashSet()
                        )
                    }
                }

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
            // El detalle técnico ya quedó en el log; al banner va el texto localizado.
            _state.value = SyncStatus.Error(context.getString(R.string.sync_error_auth))
        } catch (e: CancellationException) {
            // Cancelación cooperativa (worker reemplazado por pull-to-refresh, logout,
            // restricción de batería/red por WorkManager). No es un error visible.
            wasCancelled = true
            Log.d(TAG, "Sync cancelled cooperatively: ${e.message}")
            throw e
        } catch (e: Exception) {
            fatal = e
            _state.value = SyncStatus.Error(
                context.getString(
                    R.string.sync_error_critical,
                    e.message ?: context.getString(R.string.sync_error_no_details)
                )
            )
        } finally {
            // Una fuente con la sesión caducada convierte la corrida en fallida AUNQUE las demás
            // hayan ido bien: su biblioteca no se sincronizó y no volverá a hacerlo sin que el
            // usuario entre otra vez. Se resuelve aquí, al final, porque las fases posteriores
            // (metadata, descargas, healing) sobrescriben `_state` y borrarían el aviso.
            if (authFailure != null && !wasCancelled && !stopSignal.value) {
                authError = true
                if (fatal == null) fatal = authFailure
            }
            if (wasCancelled || stopSignal.value) {
                _state.value = SyncStatus.Idle
            } else if (authError) {
                _state.value = SyncStatus.Error(context.getString(R.string.sync_error_auth))
            } else if (_state.value !is SyncStatus.Error) {
                // Una cola detenida NO es un sync terminado. Antes este bloque publicaba
                // `Complete` mirando solo si hubo excepción, así que un sync que se quedó a
                // medias esperando WiFi se anunciaba con el mismo "Biblioteca al día" que uno
                // que agotó su trabajo — y el único sitio donde constaba el motivo era el
                // `SyncOutcome`, que lo lee `ScanWorker` y no llega jamás a la pantalla.
                val stoppedBy = queueStopReason.get()
                val pausedMessage = stoppedBy?.let { pausedMessageRes(it) }
                if (stoppedBy != null && pausedMessage != null) {
                    _state.value = SyncStatus.Paused(stoppedBy, context.getString(pausedMessage))
                } else {
                    val complete = SyncStatus.Complete(changesCount, downloaded, failed, deletedCount)
                    _state.value = complete
                    _completedEvents.tryEmit(complete)
                }
            }
            isScanning.set(false)
            // Un sync completo hace un superconjunto del refresco local, así que cuenta como
            // listado a todos los efectos. Sin esto, el primer regreso a la app después de un
            // scan volvía a listar el almacenamiento entero por tener la cuenta del intervalo
            // parada en el arranque del proceso — la misma duplicación que ya obligó a inventar
            // `skipStartupLocalRefresh` en la UI, colándose por la otra puerta.
            localLibraryChangeMonitor.markRefreshed(listingToken)
        }

        val reason = queueStopReason.get()
        return when {
            authError -> SyncOutcome.Failed(fatal?.message ?: "Authentication failed", fatal, isAuthError = true)
            fatal != null -> SyncOutcome.Failed(fatal.message ?: fatal.javaClass.simpleName, fatal)
            stopSignal.value -> SyncOutcome.Incomplete(IncompleteReason.CANCELLED)
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
     * "Ahora no" del diálogo: oculta la pregunta SIN persistir política — el próximo scan
     * vuelve a detectar. Las copias en disputa siguen retenidas (no se descargan) esta corrida.
     */
    fun dismissDuplicateDecision() {
        _duplicateDecisionNeeded.value = null
    }

    /**
     * Respuesta del usuario al diálogo de duplicados: persiste la política, la aplica ya y
     * encola un scan para completar lo que quedó en espera de la decisión (p. ej. descargas de
     * nube retenidas por [undecidedDuplicateIds]).
     */
    fun resolveDuplicateDecision(policy: DuplicatePolicy) {
        musicPreferences.saveDuplicatePolicy(policy)
        undecidedDuplicateIds = emptySet()
        _duplicateDecisionNeeded.value = null
        scope.launch {
            try {
                // La fusión sí corre aquí: son unas pocas queries locales, es idempotente y así
                // los duplicados desaparecen de la biblioteca en el acto. Lo que NO puede correr
                // en este scope es el sync completo — sin foreground service, backgroundear la app
                // lo mata a mitad (mismo motivo que documenta el kdoc de `retryFailedDownloads`).
                applyDuplicatePolicy(policy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "applyDuplicatePolicy tras la decisión falló: ${e.message}")
            }
            // Lo que quedó retenido en espera de la decisión (descargas de nube en disputa) lo
            // completa un ScanWorker, que además vuelve a aplicar la política por su cuenta
            // (`handleCrossSourceDuplicates`) si la fusión de arriba no llegó a terminar.
            downloadScheduler.scheduleScan()
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
                // Sin nadie suscrito no se construye el snapshot: el único consumidor de esta lista
                // es UI (el panel de descargas y el progreso de las filas), y durante un sync
                // masivo con la app cerrada esto era un `toList()` de hasta 32 elementos tres veces
                // por segundo durante toda la corrida, para un valor que nadie iba a leer. La marca
                // NO se consume en ese caso, así que al volver la UI ve el estado real en la
                // siguiente vuelta en lugar de esperar al próximo cambio.
                if (_activeDownloads.subscriptionCount.value > 0 && downloadsDirty.getAndSet(false)) {
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
        stopSignal.value = true
        scope.coroutineContext.cancelChildren()
        // El publicador se canceló junto con el scope; garantizamos el estado final vacío.
        activeDownloadsMap.clear()
        downloadsInFlight.clear()
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
            is AuthResult.Error -> return MusicDownloader.Result.Error("Auth error: ${tokenResult.reason}")
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

        // Ya la está bajando otra vía (el pipeline masivo, o una petición individual anterior):
        // duplicarla haría que las dos escribieran el mismo `.part`. Cancelled y no Error porque
        // no ha fallado nada — hay una descarga en curso que va a dejar el archivo en su sitio.
        if (!downloadsInFlight.add(song.id)) {
            Log.d(TAG, "Descarga de '${song.title}' ya en vuelo; no se duplica")
            return MusicDownloader.Result.Cancelled
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
            downloadsInFlight.remove(song.id)
            activeDownloadsMap.remove(song.id)
            markDownloadsDirty()
        }
    }

    /**
     * Pipeline real productor-consumidor: el productor mete canciones en un Channel a
     * medida que hay espacio, y un número VARIABLE de workers las consume en paralelo — arranca
     * prudente y se redimensiona en caliente según lo que dé la red (ver [initialParallelism] y
     * [launchParallelismSupervisor]). A diferencia del
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
        val parallelism = initialParallelism()

        // Emitimos Downloading(0, total) ANTES de arrancar los workers. Si no, el estado se
        // queda en Scanning hasta que la PRIMERA descarga complete (~50s con FLAC grandes en
        // paralelo), y la UI muestra "Escaneando" durante casi un minuto aunque ya esté
        // bajando 16 canciones. Con esto el banner pasa a "Descargando 0/N" de inmediato.
        updateProgress(0, total)

        val current = AtomicInteger(0)
        val downloadedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val bytesDownloaded = java.util.concurrent.atomic.AtomicLong(0L)
        // Monótono: es una DURACIÓN para el log de throughput, no una hora. Ver ListeningClock.
        val startedAt = SystemClock.elapsedRealtime()
        val attempted = java.util.Collections.synchronizedSet(LinkedHashSet<String>(256))

        // Buffer del channel dimensionado con el MÁXIMO y no con el paralelismo inicial: las
        // conexiones se ajustan en caliente (ver launchParallelismSupervisor), así que el buffer
        // tiene que dar de comer también al techo. Sigue siendo el doble de conexiones, para que un
        // worker que termina encuentre trabajo sin esperar al productor.
        val workChannel = Channel<Song>(capacity = MAX_PARALLEL_WIFI * 2)

        // Pipeline de finalize desacoplado: el análisis post-descarga (metadata, ReplayGain,
        // carátula, BD) es trabajo local que toma segundos por canción. Dentro del worker de
        // descarga dejaba la conexión OneDrive ociosa ese tiempo — con N workers, varios
        // MB/s perdidos. Los bytes ya están en disco: aunque la cola se detenga por
        // batería/red, finalizar lo ya descargado es gratis y evita re-descargas.
        val finalizeChannel = Channel<Pair<Song, java.io.File>>(capacity = MAX_PARALLEL_WIFI * 2)
        val finalizers = List(FINALIZE_PARALLELISM) {
            launch {
                for ((song, file) in finalizeChannel) {
                    if (stopSignal.value) continue // logout: drenar sin tocar BD
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

        // Paralelismo VIVO: el supervisor sube el objetivo lanzando workers nuevos y lo baja
        // dejando que se retiren al terminar su canción — nunca cancelándolos, que tiraría una
        // descarga a medias. `workerJobs` crece durante la corrida, así que el join del final se
        // hace sobre una foto y se repite hasta que no aparezcan más (ver más abajo).
        val parallelismState = ParallelismState(parallelism)
        val workerJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())

        // Descargas BAJANDO BYTES ahora mismo. No es lo mismo que workers vivos: al final de la
        // cola quedan decenas de workers esperando un canal que ya no da nada. Confundirlos era lo
        // que rompía la medición del supervisor (ver [launchParallelismSupervisor]) y lo que hace
        // falta para saber cuántas conexiones sobran.
        val downloadingNow = AtomicInteger(0)
        val finalStretchBudget = FinalStretchBudget()

        fun spawnWorker() {
            val job = launch {
                var retired = false
                try {
                    for (song in workChannel) {
                        // Drenaje: si la cola se detuvo (logout, batería, red), seguimos
                        // consumiendo sin descargar para no dejar al productor bloqueado en
                        // send(). Las canciones drenadas siguen "needing work" en BD y las
                        // retoma el próximo sync.
                        if (stopSignal.value || queueStopReason.get() != null) continue

                        // Pausa/stop del usuario: drenar los temas YA en el buffer del canal sin
                        // descargarlos (siguen "needing work"; se retoman al reanudar). Sin este
                        // check, la pausa solo frenaba al productor y los ~parallelism*2 temas ya
                        // bufferizados seguían bajando ("le puse pausa y sigue descargando").
                        if (!musicPreferences.loadDownloadControlState().allowsMassDownload) continue

                        // Mismo razonamiento para la red medida, y por el mismo motivo: el gate de
                        // WiFi vivía SOLO en el productor, así que al salir de casa las canciones ya
                        // bufferizadas (hasta parallelism*2) se seguían bajando con datos móviles
                        // durante todo el plazo de gracia. Aquí no hay prioritarias que respetar:
                        // esas nunca pasan por el canal (ver handlePrioritySong).
                        if (!networkManager.isWifi()) continue

                        // Otra vía ya la está bajando (el usuario pulsó "descargar" sobre una canción
                        // que el masivo tenía encolada): se salta. Sigue "needing work" en BD si esa
                        // descarga fallara, así que no se pierde — lo que no puede pasar es que las
                        // dos escriban el mismo archivo temporal.
                        if (!downloadsInFlight.add(song.id)) continue

                        val onProgress: (Float) -> Unit = {
                            activeDownloadsMap[song.id] = ActiveDownload(song, it)
                            markDownloadsDirty()
                        }
                        activeDownloadsMap[song.id] = ActiveDownload(song, 0f)
                        markDownloadsDirty()

                        // El remove va en finally: si el worker se cancela (logout/pull-to-refresh)
                        // durante la descarga, hay que sacar la canción del mapa igual, o el
                        // publicador lo vería no-vacío para siempre y no publicaría el estado final.
                        downloadingNow.incrementAndGet()
                        val stage = try {
                            downloadWithTransientRetry(song, finalStretchBudget, onProgress)
                        } finally {
                            downloadingNow.decrementAndGet()
                            downloadsInFlight.remove(song.id)
                            activeDownloadsMap.remove(song.id)
                            markDownloadsDirty()
                        }

                        when (stage) {
                            is MusicDownloader.DownloadStage.Success -> {
                                bytesDownloaded.addAndGet(stage.targetFile.length())
                                finalizeChannel.send(song to stage.targetFile)
                            }
                            is MusicDownloader.DownloadStage.Error -> {
                                // Un fallo causado por que la RED SE CAYÓ a mitad del sync no es de la
                                // canción: registrarlo le pone `nextRetryAt` y el sync que WorkManager
                                // reanuda en cuanto vuelve la red se salta justo a las que el corte
                                // interrumpió. Se drenan sin penalizar, como ya hace el corte por red
                                // medida (que sale por `Cancelled`); siguen "needing work" en BD.
                                if (queueStopReason.get() == IncompleteReason.NETWORK_LOST) {
                                    Log.d(TAG, "Descarga de ${song.title} abortada por corte de red: sin backoff")
                                } else {
                                    recordDownloadFailure(song, stage.message, stage.kind == MusicDownloader.ErrorKind.TRANSIENT)
                                    failedCount.incrementAndGet()
                                    updateProgress(current.incrementAndGet(), total, failedCount.get())
                                }
                            }
                            MusicDownloader.DownloadStage.Cancelled -> { /* drenada, no cuenta */ }
                            // Batería baja: detener la cola con motivo explícito (NO stopSignal:
                            // eso es para logout). ScanWorker devuelve retry() y WorkManager
                            // reanuda cuando la constraint de batería lo permita.
                            MusicDownloader.DownloadStage.SkippedLowBattery ->
                                queueStopReason.compareAndSet(null, IncompleteReason.LOW_BATTERY)
                        }

                        // Sobran conexiones porque el supervisor bajó el objetivo: este worker se
                        // retira AQUÍ, con su canción ya terminada. Cancelarlo a mitad tiraría una
                        // descarga entera, y salir antes de procesar perdería la que ya sacó del
                        // canal.
                        if (parallelismState.claimRetirement()) {
                            retired = true
                            break
                        }
                    }
                } finally {
                    // Quien se retiró ya se descontó al reclamar el hueco; el resto llega aquí al
                    // cerrarse el canal (fin normal) o por cancelación.
                    if (!retired) {
                        parallelismState.active.decrementAndGet()
                        // Su conexión queda libre: en el tramo final se la reparten los archivos
                        // que siguen bajando. Quien se RETIRÓ no la cede — su hueco existía justo
                        // porque el supervisor decidió que sobraban conexiones.
                        finalStretchBudget.release(1)
                    }
                }
            }
            workerJobs.add(job)
        }

        repeat(parallelism) {
            parallelismState.active.incrementAndGet()
            spawnWorker()
        }
        val parallelismSupervisor =
            launchParallelismSupervisor(parallelismState, bytesDownloaded, downloadingNow, ::spawnWorker)

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
            while (!stopSignal.value && queueStopReason.get() == null) {
                // Espera por SEÑAL (StateFlow), no polling: despierta en cuanto termina la
                // descarga prioritaria. El `continue` re-evalúa stopSignal/queueStopReason.
                if (requestCoordinator.shouldPauseScan()) { requestCoordinator.awaitScanResumed(); continue }

                // Red caída: pausar y esperar reconexión en vez de quemar la cola.
                if (!networkManager.isAvailable()) {
                    if (!awaitPaused(IncompleteReason.NETWORK_LOST, NETWORK_WAIT_TIMEOUT_MS,
                            current, total, failedCount) { networkManager.isAvailable() }
                    ) {
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
                    // Una PRIORITARIA que llegue durante esta espera también la termina: aquí sí
                    // hay red (solo que medida) y las prioritarias están permitidas en datos, así
                    // que seguir esperando al WiFi dejaría la canción que el usuario acaba de
                    // pulsar sin atender hasta un minuto. Al salir por esta vía, el `continue`
                    // vuelve arriba y la atiende (`handlePrioritySong`), que además la limpia —
                    // por eso no se convierte en un bucle ocupado.
                    if (!awaitPaused(IncompleteReason.NO_WIFI, WIFI_WAIT_TIMEOUT_MS,
                            current, total, failedCount) {
                            networkManager.isWifi() || prioritySongId.value != null
                        }
                    ) {
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
                    if (stopSignal.value || queueStopReason.get() != null || prioritySongId.value != null) break
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
            // TRAMO FINAL: ya no entrarán canciones nuevas, así que las conexiones que no estén
            // bajando nada se pueden repartir DENTRO de los archivos que quedan (ver
            // [FinalStretchBudget]). Es el momento exacto en que abrirlo es seguro: mientras el
            // productor podía servir trabajo, una conexión prestada habría que reclamarla de vuelta
            // —abandonando un tramo a medias— para no dejar sin nada a la canción que entra.
            finalStretchBudget.open(parallelismState.target.get() - downloadingNow.get())
        }

        // El supervisor se para ANTES de esperar a los workers: si siguiera vivo podría lanzar uno
        // nuevo justo mientras se hace el join y la espera no lo cubriría.
        parallelismSupervisor.cancelAndJoin()
        // Join sobre una FOTO de la lista, repetido hasta que no aparezcan jobs nuevos: `spawnWorker`
        // añade durante la corrida, así que un `joinAll` único sobre la lista viva podría perderse
        // al último refuerzo. Con el supervisor ya parado esto converge en una o dos vueltas.
        while (true) {
            val snapshot = workerJobs.toList()
            snapshot.joinAll()
            if (workerJobs.size == snapshot.size) break
        }
        finalizeChannel.close()
        finalizers.joinAll()

        val elapsedSec = (SystemClock.elapsedRealtime() - startedAt) / MILLIS_PER_SECOND
        val mb = bytesDownloaded.get() / BYTES_PER_MB
        if (mb > 0 && elapsedSec > 0) {
            Log.i(TAG, "Throughput: %.1f MB en %.0fs -> %.2f MB/s (%d conexiones al final)"
                .format(mb, elapsedSec, mb / elapsedSec, parallelismState.active.get()))
        }
        persistLearnedThroughput(parallelismState)

        Pair(downloadedCount.get(), failedCount.get())
    }

    /**
     * Con cuántas conexiones ARRANCA la corrida. A partir de ahí manda [adjustParallelism], que
     * remide cada [RESIZE_WINDOW_MS] y sube o baja en caliente.
     *
     * Tres casos, y ninguno usa un número traído de fuera:
     * - **Ya se aprendió** el throughput por conexión en corridas anteriores: se dimensiona con él
     *   y con el enlace de AHORA, así que cambiar de 2,4 a 5 GHz se nota en el acto y no hay que
     *   volver a descubrir nada.
     * - **Todavía no**: se arranca por el MÍNIMO. Es deliberadamente prudente — cuatro conexiones
     *   no congestionan ningún enlace, y si sobra ancho de banda la primera ventana lo detecta y
     *   sube en diez segundos. Al revés no funcionaría: empezar alto en un WiFi débil provoca la
     *   contención que este cálculo existe para evitar, y encima la medición saldría contaminada.
     * - **El sistema no reporta enlace** (0): sin esa referencia no hay forma de saber si sobra o
     *   falta, así que el ajuste automático no puede operar y se conserva el comportamiento
     *   histórico de asumir línea rápida.
     */
    private fun initialParallelism(): Int {
        val kbps = networkManager.downlinkKbps()
        if (kbps <= 0) {
            Log.i(TAG, "Paralelismo: sin dato de enlace -> $MAX_PARALLEL_WIFI conexiones (sin ajuste)")
            return MAX_PARALLEL_WIFI
        }
        val linkMBps = kbps / KBPS_PER_MBPS
        val learned = musicPreferences.loadOneDriveThroughputMBps().toDouble()
        val computed = if (learned > 0.0) {
            kotlin.math.ceil(linkMBps / learned).toInt().coerceIn(MIN_PARALLEL_WIFI, MAX_PARALLEL_WIFI)
        } else {
            MIN_PARALLEL_WIFI
        }
        Log.i(
            TAG,
            "Paralelismo inicial: enlace %.1f MB/s, %s -> %d conexiones"
                .format(linkMBps, if (learned > 0.0) "%.2f MB/s aprendidos".format(learned) else "sin medición previa", computed)
        )
        return computed
    }

    /**
     * Estado del ajuste de paralelismo de UNA corrida. Vive en un objeto y no en variables sueltas
     * porque lo comparten el supervisor (que mide y decide) y los workers (que se retiran solos).
     *
     * @property target conexiones que se quieren ahora mismo.
     * @property active conexiones vivas; se reserva ANTES de lanzar la corrutina, o dos vueltas
     *   seguidas del supervisor lanzarían el mismo refuerzo dos veces.
     * @property learnedPerConnMBps último throughput por conexión que se pudo medir de verdad, o 0.
     */
    private class ParallelismState(initial: Int) {
        val target = AtomicInteger(initial)
        val active = AtomicInteger(0)
        @Volatile var learnedPerConnMBps = 0.0

        /**
         * ¿Le toca a ESTE worker retirarse? Solo cuando sobran, y solo uno por hueco: el CAS es lo
         * que impide que todos los workers lean el mismo exceso y se vayan todos a la vez.
         */
        fun claimRetirement(): Boolean {
            while (true) {
                val current = active.get()
                if (current <= target.get()) return false
                if (active.compareAndSet(current, current - 1)) return true
            }
        }
    }

    /**
     * Las conexiones que sobran cuando la cola ya no tiene canciones nuevas que servir, para que se
     * las repartan por tramos los archivos que siguen bajando.
     *
     * **El problema que resuelve.** OneDrive limita por CONEXIÓN, no por cuenta, así que el caudal
     * del sync es proporcional al número de descargas simultáneas. Con la cola llena eso da ~16-28
     * MB/s; cuando quedan dos archivos grandes quedan dos conexiones y treinta paradas, y el final
     * de cada sync se arrastra a ~1 MB/s con el enlace ocioso. Repartir esas conexiones DENTRO de
     * lo que queda mantiene el caudal hasta el último byte.
     *
     * **Solo se abre al cerrarse el canal**, y esa restricción es la que lo mantiene simple: sin
     * canciones nuevas entrando, un permiso prestado no hay que reclamarlo de vuelta, así que nadie
     * tiene que abandonar un tramo a medias.
     *
     * `open` FIJA el valor en vez de sumarlo: los workers que terminaron antes de abrirse ya están
     * contados en el `target - descargando` con el que se abre, y sumarlos otra vez repartiría
     * conexiones que no existen.
     */
    private class FinalStretchBudget : com.qhana.siku.data.manager.ExtraConnectionBudget {
        private val available = AtomicInteger(0)
        private val opened = AtomicBoolean(false)

        fun open(spare: Int) {
            available.set(spare.coerceAtLeast(0))
            opened.set(true)
        }

        override fun tryAcquire(): Boolean {
            if (!opened.get()) return false
            while (true) {
                val current = available.get()
                if (current <= 0) return false
                if (available.compareAndSet(current, current - 1)) return true
            }
        }

        override fun release(count: Int) {
            if (count > 0) available.addAndGet(count)
        }
    }

    /**
     * Remide la corrida y cambia el número de descargas en paralelo EN CALIENTE.
     *
     * **Por qué en caliente y no entre corridas**: el paralelismo se fijaba una vez al empezar, así
     * que aprender de una corrida solo servía para la siguiente — y la corrida que más importa es
     * justo la primera, la de estrenar la app con la biblioteca entera. Ajustando por ventanas, el
     * arranque puede ser prudente (cuatro conexiones) sin castigar ese sync: si sobra enlace, la
     * primera ventana lo ve y sube en diez segundos.
     *
     * **Qué se mide y qué se deduce.** De la ventana sale el caudal agregado. Comparado con el
     * enlace que reporta el sistema:
     * - si el agregado está por DEBAJO de [SATURATED_LINK_FRACTION] del enlace, sobra ancho de banda
     *   y el límite lo pone OneDrive por conexión: `agregado / conexiones` es entonces una medida
     *   legítima de ese cap, y de ella sale cuántas conexiones harían falta para llenar el enlace;
     * - si el agregado ronda el enlace, la red es el límite y la división **no** mide el cap sino el
     *   ancho de banda repartido. Esa muestra se descarta: usarla haría que el ajuste se
     *   AUTOCONFIRMARA (mide `enlace/P`, recalcula `P`, se queda donde estaba), congelando para
     *   siempre un exceso de conexiones que es justo la contención a evitar.
     *
     * Con el cap ya aprendido, el objetivo se recalcula contra el enlace de cada ventana, y por eso
     * el sistema sabe **bajar** además de subir: si la señal empeora a mitad del sync, el enlace cae
     * y el objetivo baja con él.
     *
     * **Descartes.** Una ventana no enseña nada si no bajó bytes, si hubo throttling (segundos de
     * reloj esperando a OneDrive que harían parecer lenta la conexión, subiendo conexiones justo
     * cuando el servidor pedía menos), o si es la ventana INMEDIATAMENTE posterior a un cambio: ahí
     * conviven conexiones a medio arrancar con otras en régimen y el caudal no representa a ninguna
     * de las dos configuraciones.
     */
    private fun CoroutineScope.launchParallelismSupervisor(
        state: ParallelismState,
        bytesDownloaded: java.util.concurrent.atomic.AtomicLong,
        downloadingNow: AtomicInteger,
        spawnWorker: () -> Unit
    ): Job = launch {
        // Monótono: la ventana del supervisor mide caudal por segundo, no instantes.
        var windowStartMs = SystemClock.elapsedRealtime()
        var windowStartBytes = bytesDownloaded.get()
        var windowThrottles = requestCoordinator.throttleEvents
        var windowNetworkWaits = networkWaits.get()
        var skipWindow = false

        while (isActive) {
            delay(RESIZE_WINDOW_MS)

            val now = SystemClock.elapsedRealtime()
            val bytes = bytesDownloaded.get()
            val windowSec = (now - windowStartMs) / MILLIS_PER_SECOND
            val windowMB = (bytes - windowStartBytes) / BYTES_PER_MB
            // Conexiones que de verdad estuvieron bajando, NO workers vivos. Con la cola vaciándose
            // conviven decenas de workers ociosos con dos descargas grandes, y dividir el caudal
            // entre todos daba un "por conexión" ridículo: el objetivo saltaba al techo, se
            // lanzaban workers para un canal ya cerrado y —lo peor— ese número se PERSISTÍA, así
            // que lo aprendido convergía siempre a "arrancá con el máximo" y el arranque prudente
            // de initialParallelism() no llegaba a existir.
            val workers = downloadingNow.get()
            val throttled = requestCoordinator.throttleEvents > windowThrottles
            // Una espera de red DENTRO de la ventana infla el reloj sin bajar bytes. Una espera
            // larga se descarta sola (la ventana siguiente no tiene bytes), pero la que la parte por
            // la mitad sí cuela, y haría parecer lenta una conexión que estuvo ausente, no lenta.
            val waitedForNetwork = networkWaits.get() > windowNetworkWaits
            val measuredNow = !skipWindow && !throttled && !waitedForNetwork &&
                windowMB > 0.0 && windowSec > 0.0 && workers > 0

            windowStartMs = now
            windowStartBytes = bytes
            windowThrottles = requestCoordinator.throttleEvents
            windowNetworkWaits = networkWaits.get()
            skipWindow = false
            if (!measuredNow) continue

            val linkMBps = networkManager.downlinkKbps() / KBPS_PER_MBPS
            if (linkMBps <= 0.0) continue
            val aggregateMBps = windowMB / windowSec

            if (aggregateMBps < linkMBps * SATURATED_LINK_FRACTION) {
                state.learnedPerConnMBps = aggregateMBps / workers
            }
            val perConn = state.learnedPerConnMBps
            if (perConn <= 0.0) continue

            val desired = kotlin.math.ceil(linkMBps / perConn).toInt()
                .coerceIn(MIN_PARALLEL_WIFI, MAX_PARALLEL_WIFI)
            if (desired == state.target.get()) continue
            // Con el canal cerrado no hay canciones que repartir: lanzar workers solo crearía
            // corrutinas que mueren en el acto y engordan la lista del join. Lo que sí sigue
            // sirviendo es lo aprendido arriba, que es de lo que vive la próxima corrida — y lo
            // que aprovecha el tramo final es el reparto por tramos, no más workers.
            if (!producerActive.get()) continue

            Log.i(
                TAG,
                ("Redimensionando descargas: %.2f MB/s con %d conexiones, enlace %.1f MB/s, " +
                    "%.2f MB/s por conexión -> %d conexiones")
                    .format(aggregateMBps, workers, linkMBps, perConn, desired)
            )
            state.target.set(desired)
            // Subir es lanzar; bajar lo resuelven los propios workers al terminar su canción (ver
            // ParallelismState.claimRetirement), porque cancelarlos a mitad tiraría una descarga.
            while (state.active.get() < desired) {
                state.active.incrementAndGet()
                spawnWorker()
            }
            skipWindow = true
        }
    }

    /**
     * Guarda para las próximas corridas el throughput por conexión aprendido en ésta.
     *
     * Se promedia a partes iguales con lo que hubiera: cada corrida pesa tanto como toda la historia
     * previa, así que un cambio real de red se refleja en dos o tres syncs pero una tarde rara no
     * reconfigura nada por sí sola. Si no se pudo medir (enlace saturado todo el rato, que es lo
     * normal con archivos grandes y WiFi modesto), no se toca lo guardado.
     */
    private fun persistLearnedThroughput(state: ParallelismState) {
        val measured = state.learnedPerConnMBps.toFloat()
        if (!measured.isFinite() || measured <= 0f) return
        val previous = musicPreferences.loadOneDriveThroughputMBps()
        val blended =
            if (previous > 0f) previous + (measured - previous) * THROUGHPUT_SMOOTHING else measured
        musicPreferences.saveOneDriveThroughputMBps(blended)
        Log.i(
            TAG,
            "Throughput por conexión: %.2f MB/s medido, %.2f MB/s tras suavizar (antes %.2f)"
                .format(measured, blended, previous)
        )
    }

    /**
     * Descarga con reintento ante errores TRANSITORIOS (red, stall, 5xx): hasta
     * MAX_SONG_ATTEMPTS intentos con backoff lineal. Si durante el backoff la red
     * desaparece y no vuelve, marca la cola para detenerse en vez de seguir fallando.
     * Envuelve a [runDownloadWithRetry] (que ya cubre el caso URL expirada con 4xx).
     */
    private suspend fun downloadWithTransientRetry(
        song: Song,
        budget: com.qhana.siku.data.manager.ExtraConnectionBudget?,
        onProgress: (Float) -> Unit
    ): MusicDownloader.DownloadStage {
        var stage = runDownloadWithRetry(song, isPriority = false, onProgress = onProgress, budget = budget)
        var attempt = 1
        while (attempt < MAX_SONG_ATTEMPTS &&
            stage is MusicDownloader.DownloadStage.Error &&
            stage.kind == MusicDownloader.ErrorKind.TRANSIENT &&
            !stopSignal.value && queueStopReason.get() == null
        ) {
            Log.w(TAG, "Transient error for ${song.title} (attempt $attempt/$MAX_SONG_ATTEMPTS): ${stage.message}")
            delay(SONG_RETRY_BACKOFF_MS * attempt)
            if (!networkManager.isAvailable() &&
                !awaitNetwork(NETWORK_WAIT_TIMEOUT_MS) { networkManager.isAvailable() }
            ) {
                queueStopReason.compareAndSet(null, IncompleteReason.NETWORK_LOST)
                return stage
            }
            stage = runDownloadWithRetry(song, isPriority = false, onProgress = onProgress, budget = budget)
            attempt++
        }
        return stage
    }

    /**
     * Espera hasta que [condition] se cumpla, con [timeoutMs] como techo. Retorna false si se
     * agota el plazo o si llega el stop cooperativo.
     *
     * Espera por SEÑAL y no por intervalo: despierta con cada cambio real de la red y con el
     * stop, en vez de sondear. La diferencia se nota en las dos direcciones — al recuperar el
     * WiFi la cola reanuda en el acto (sondeando se perdía hasta un ciclo entero), y al pasar
     * a datos el productor deja de encolar de inmediato en lugar de seguir sirviendo canciones
     * hasta el siguiente sondeo.
     *
     * La condición se evalúa contra `NetworkManager`, no contra el valor emitido: las dos
     * señales tienen tipos distintos y lo que interesa es el estado resultante, no cuál de
     * ellas despertó la espera.
     */
    private suspend fun awaitNetwork(timeoutMs: Long, condition: () -> Boolean): Boolean {
        if (condition()) return true
        // Se va a esperar de verdad: el reloj de la corrida seguirá corriendo sin descargar nada,
        // así que su throughput ya no mide la conexión (ver [launchParallelismSupervisor]).
        networkWaits.incrementAndGet()
        withTimeoutOrNull(timeoutMs) {
            merge(
                networkManager.status.map { },
                stopSignal.map { },
                // Una petición prioritaria también es una señal: hay esperas (el gate de WiFi)
                // cuya condición la incluye. Para las que no —la de red caída, donde no se puede
                // descargar nada— es inocua: `first` solo termina si el predicado se cumple, así
                // que una emisión que no lo cumple se limita a re-evaluarlo.
                prioritySongId.map { }
            ).first { condition() || stopSignal.value }
        }
        return condition() && !stopSignal.value
    }

    /**
     * [awaitNetwork] con estado visible: anuncia el motivo mientras dura la espera y repone el
     * progreso de descarga si la red vuelve.
     *
     * Anunciar AL ENTRAR, y no al terminar, es el punto entero. El contador de progreso solo
     * avanza cuando una descarga completa o falla, así que al perder la red se quedaba clavado
     * en "Descargando X de N" durante minutos —los que tardan las conexiones muertas en morir
     * por el watchdog más el plazo de gracia— sin una sola pista de lo que estaba pasando. El
     * usuario lo leía como que la app se había colgado, y tenía razón en leerlo así: el banner
     * afirmaba un progreso que ya no existía.
     */
    private suspend fun awaitPaused(
        reason: IncompleteReason,
        timeoutMs: Long,
        current: AtomicInteger,
        total: Int,
        failed: AtomicInteger,
        condition: () -> Boolean
    ): Boolean {
        publishPaused(reason)
        val resumed = awaitNetwork(timeoutMs, condition)
        if (resumed) updateProgress(current.get(), total, failed.get())
        return resumed
    }

    private fun publishPaused(reason: IncompleteReason) {
        val messageRes = pausedMessageRes(reason) ?: return
        _state.value = SyncStatus.Paused(reason, context.getString(messageRes))
    }

    /**
     * Texto del banner para una cola detenida. null = no hay nada que anunciar:
     * [IncompleteReason.CANCELLED] es un logout o un pull-to-refresh que reemplaza al sync, y
     * ahí el estado correcto es `Idle` —lo pone el `finally` de [executeSync]—, no un aviso.
     */
    private fun pausedMessageRes(reason: IncompleteReason): Int? = when (reason) {
        IncompleteReason.NO_WIFI -> R.string.sync_paused_no_wifi
        IncompleteReason.NETWORK_LOST -> R.string.sync_paused_no_network
        IncompleteReason.LOW_BATTERY -> R.string.sync_paused_low_battery
        IncompleteReason.CANCELLED -> null
    }

    private fun updateProgress(current: Int, total: Int, failed: Int = 0) {
        _state.value = SyncStatus.Downloading(
            min(current, total), total, failed, context.getString(R.string.notif_syncing_title)
        )
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
            // `currentTimeMillis` a propósito, y es la excepción a la regla del reloj monótono que
            // siguen los plazos de este archivo: `nextRetryAt` se PERSISTE en Room y lo compara el
            // gestor de descargas contra la hora del reintento, así que necesita una época. Un reloj
            // sin ella daría un instante que no significa nada tras reiniciar el teléfono.
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
        // Solo se limpia si sigue siendo LA MISMA canción. El `join` de arriba dura lo que dura
        // una descarga (minutos con un FLAC) y en ese rato el usuario puede haber saltado a otra:
        // un `clearPriority()` incondicional borraba esa petición nueva sin que nadie la hubiera
        // atendido, y la canción que estaba sonando se quedaba en streaming hasta que el masivo
        // llegara a ella por orden alfabético — o hasta nunca, si la corrida terminaba antes.
        prioritySongId.compareAndSet(id, null)
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
        // Ver `downloadsInFlight`: nadie más puede estar escribiendo el `.part` de esta canción.
        if (!downloadsInFlight.add(song.id)) return MusicDownloader.Result.Cancelled
        return try {
            activeDownloadsMap[song.id] = ActiveDownload(song, 0f, individual = isPriority)
            markDownloadsDirty()
            if (!networkManager.isWifi() && !isPriority) return MusicDownloader.Result.Success(song)
            val onProgress: (Float) -> Unit = {
                activeDownloadsMap[song.id] = ActiveDownload(song, it, individual = isPriority)
                markDownloadsDirty()
            }
            val stage = runDownloadWithRetry(song, isPriority, onProgress)
            val result = when (stage) {
                is MusicDownloader.DownloadStage.Success -> musicDownloader.finalizeDownload(song, stage.targetFile)
                is MusicDownloader.DownloadStage.Error -> MusicDownloader.Result.Error(
                    stage.message, stage.exception,
                    transient = stage.kind == MusicDownloader.ErrorKind.TRANSIENT
                )
                MusicDownloader.DownloadStage.Cancelled -> MusicDownloader.Result.Cancelled
                MusicDownloader.DownloadStage.SkippedLowBattery -> MusicDownloader.Result.SkippedLowBattery
            }
            // Cola persistente (v18), igual que `downloadSong` y que el pipeline masivo: esta vía
            // no dejaba rastro en BD, así que una canción cuyo item de OneDrive ya no existe
            // fallaba en silencio en CADA reproducción — sin backoff que la frenara y sin
            // aparecer en la lista de descargas fallidas.
            when (result) {
                is MusicDownloader.Result.Success -> musicRepository.clearDownloadError(song.id)
                is MusicDownloader.Result.Error -> recordDownloadFailure(song, result.message, result.transient)
                else -> { /* Cancelled / low battery: no son fallos de la canción */ }
            }
            result
        } catch (e: Exception) { MusicDownloader.Result.Error(e.message ?: "Error") }
        finally {
            downloadsInFlight.remove(song.id)
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
        forceFreshUrl: Boolean = false,
        budget: com.qhana.siku.data.manager.ExtraConnectionBudget? = null
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
        val first = musicDownloader.downloadFile(song, firstUrl, isPriority, budget, onProgress)
        if (first is MusicDownloader.DownloadStage.Error && first.httpCode != null && first.httpCode in 400..499) {
            Log.w(TAG, "URL expired (HTTP ${first.httpCode}) for ${song.title}, refreshing once")
            val freshUrl = resolveDownloadUrl(song, forceRefresh = true) ?: return first
            if (freshUrl == firstUrl) return first
            return musicDownloader.downloadFile(song, freshUrl, isPriority, budget, onProgress)
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
    // Sin defaults a propósito: `currentMessage` acaba en el banner de la biblioteca, así que
    // debe venir SIEMPRE de recursos (un default literal se colaba en inglés en la UI en español).
    data class Scanning(val found: Int, val currentMessage: String) : SyncStatus(currentMessage, true)
    /**
     * Trabajo previo o posterior a las descargas que NO es escanear ni bajar audio: leer los tags
     * remotos, resolver carátulas, podar sobrantes.
     *
     * Existe porque esas fases se hacían en silencio y el banner conservaba el último estado del
     * escaneo. La metadata ligera puede tardar varios minutos en una biblioteca grande (dos
     * peticiones por canción, y las de Graph están serializadas por el rate limiter), así que
     * "Escaneando biblioteca" quieto ese rato se lee, con toda la razón, como que la app se colgó.
     *
     * [total] = 0 cuando la fase no sabe cuántos elementos tiene por delante; el banner muestra
     * entonces progreso indeterminado en vez de un "0 de 0".
     */
    data class Preparing(val current: Int, val total: Int, val currentMessage: String) : SyncStatus(currentMessage, true)
    data class Downloading(val current: Int, val total: Int, val failed: Int, val currentMessage: String) : SyncStatus(currentMessage, true)
    /**
     * La cola dejó de avanzar por una condición del entorno (sin WiFi, sin red, batería baja)
     * y no por un error. Se publica DOS veces con el mismo aspecto y distinto significado:
     * mientras se espera a que la condición se resuelva, y como estado final si no se resolvió
     * —ahí el trabajo pendiente queda en manos de la continuación que encadena `ScanWorker`.
     *
     * Que ambos casos se vean igual es deliberado: para quien mira la pantalla la situación es
     * la misma (las descargas están detenidas y se reanudarán solas), y distinguirlas obligaría
     * a explicar una diferencia que solo existe dentro de WorkManager.
     */
    data class Paused(val reason: IncompleteReason, val currentMessage: String) : SyncStatus(currentMessage, false)
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