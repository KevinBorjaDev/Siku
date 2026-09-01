package com.qhana.siku.data.manager

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.qhana.siku.R
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.remote.HttpStatus
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.NetworkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Componente especializado en la descarga y procesamiento de archivos de música.
 * Encapsula la lógica de:
 * 1. Descarga HTTP reanudable y divisible por tramos (`Range`).
 * 2. Gestión de archivos parciales y finales.
 * 3. Extracción de Metadatos (Duración, Artista, Album).
 * 4. Extracción de Carátulas incrustadas.
 * 5. Actualización de la Base de Datos.
 */
@Singleton
class MusicDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: IMusicRepository,
    @Named("download") private val okHttpClient: OkHttpClient,
    private val networkManager: NetworkManager,
    private val audioFileAnalyzer: com.qhana.siku.data.util.AudioFileAnalyzer
) {

    companion object {
        private const val TAG = "MusicDownloader"
        private const val IO_BUFFER_SIZE = 64 * 1024 // 64KB: mejor throughput en WiFi moderno (menos llamadas read/write).

        /**
         * Cadencia de los avisos de progreso: se emite al superar este salto de fracción O al
         * pasar [PROGRESS_EMIT_INTERVAL_MS] desde el último, y SIEMPRE al terminar.
         *
         * Sin el acotado, un FLAC entero emite miles de veces por segundo y con N descargas en
         * paralelo eso satura el StateFlow que alimenta la UI. Los dos criterios se necesitan: el
         * de porcentaje solo dejaría muda una descarga lenta, y el de tiempo solo haría trabajar
         * de más a una rápida.
         */
        private const val PROGRESS_EMIT_STEP = 0.05f
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val MIN_BATTERY_LEVEL = 20
        private const val MIN_DISK_SPACE_BYTES = 50L * 1024 * 1024 // 50MB mínimo

        /**
         * Nombres de los dos archivos de una descarga a medias: los bytes y el estado.
         *
         * NO llevan la extensión del audio a propósito (antes era `<id>.<ext>.tmp`): la extensión
         * sale del `Content-Type` de la respuesta, así que no se conoce hasta haber pedido el
         * archivo — y para buscar un parcial que reanudar hay que saber su nombre ANTES de pedir
         * nada. Con la extensión dentro, además, un `Content-Type` distinto entre dos intentos
         * dejaba huérfano el parcial del anterior.
         */
        private const val PART_SUFFIX = ".part"
        private const val META_SUFFIX = ".meta"

        // Stall detector: si no llegan bytes nuevos en este tiempo, abortar la descarga.
        // Cubre TCP half-open (FIN packet perdido), URL OneDrive expirada mid-stream,
        // o cualquier estancamiento que el `readTimeout(5min)` de OkHttp tarda en detectar.
        //
        // 60s (no 20s): los FLAC grandes (50-250 MB) a la velocidad por-conexión que OneDrive
        // permite (~0.5 MB/s) tardan minutos en bajar, y son normales pausas de >20s por
        // throttling/re-transmisión TCP sin que la conexión esté realmente muerta. 20s
        // mataba descargas válidas. 60s sigue cazando cuelgues reales (un FIN perdido no se
        // arregla solo) y deja margen para la lentitud legítima.
        internal const val STALL_TIMEOUT_MS = 60_000L
        private const val STALL_CHECK_INTERVAL_MS = 10_000L

        /**
         * Techo de silencio del socket para el cliente "download" (lo consume AppModule).
         * Mide LO MISMO que el watchdog —tiempo sin recibir bytes, no duración total de la
         * descarga— así que ambos deben venir del mismo número en vez de fijarse por separado.
         *
         * Se le da el DOBLE de margen a propósito: el watchdog debe ganar siempre la carrera
         * porque su diagnóstico es mejor (marca `wasStalled`, lo que clasifica el fallo como
         * TRANSIENT y programa reintento con backoff). Si cortara antes OkHttp, el mismo
         * cuelgue llegaría como una IOException genérica.
         */
        internal const val SOCKET_IDLE_TIMEOUT_MS = STALL_TIMEOUT_MS * 2

        /**
         * Tramo mínimo que se le deja a una conexión nueva al partir una descarga en dos.
         *
         * Sale de la tasa por conexión que OneDrive concede (~0.5 MB/s): por debajo de esto el
         * tramo se acaba en menos de diez segundos, o sea del orden de lo que cuesta abrir la
         * conexión y su handshake TLS, y partir deja de compensar.
         */
        private const val MIN_SPLIT_BYTES = 4L * 1024 * 1024

        /**
         * Tope de conexiones simultáneas contra el MISMO archivo. No es el presupuesto global —de
         * ese se encarga [ExtraConnectionBudget]— sino el reconocimiento de que el reparto tiene
         * rendimientos decrecientes: a partir de aquí el cuello de botella deja de ser el cap por
         * conexión y pasa a ser el enlace, y lo único que se gana son más sockets que vigilar.
         */
        private const val MAX_SEGMENTS_PER_DOWNLOAD = 8

        /** Cada cuánto se mira si sobra una conexión que sumar a esta descarga. */
        private const val SEGMENT_CHECK_INTERVAL_MS = 2_000L

        /**
         * Cada cuánto se vuelca a disco el estado de la descarga.
         *
         * Es el tamaño del paso atrás que se da si el PROCESO muere sin avisar (a las descargas que
         * terminan por error las salva el volcado del `catch`, que es exacto). Cinco segundos son
         * ~2,5 MB por conexión: barato de rehacer y lo bastante espaciado como para que el `force`
         * del canal no compita con la escritura de los bytes.
         */
        private const val META_FLUSH_INTERVAL_MS = 5_000L

        private val KNOWN_AUDIO_EXTENSIONS = setOf(
            "flac", "mp3", "m4a", "wav", "ogg", "opus", "aac", "wma", "aif", "aiff", "ape", "wv"
        )
    }

    /**
     * Canciones que acaban de quedar descargadas, con la fila ya actualizada (`path` = `file://`).
     *
     * Existe porque la descarga es lo ÚNICO que cambia una canción por debajo de quien la está
     * mostrando: la cola del reproductor guarda objetos `Song` capturados al armarla, y sin este
     * aviso se queda anunciando "se transmitirá" para un archivo que ya está en el dispositivo.
     * El `NowPlaying` no lo notaba porque su canción actual sí sale de un flujo de la BD.
     *
     * `extraBufferCapacity` generoso: un sync masivo finaliza muchas canciones seguidas y las
     * emisiones no deben bloquear el pipeline (`tryEmit` descarta si no cabe, y perder un aviso
     * solo significa que esa fila se corrige la próxima vez que se cargue la cola).
     */
    private val _downloadedSongs = MutableSharedFlow<Song>(extraBufferCapacity = 64)
    val downloadedSongs: SharedFlow<Song> = _downloadedSongs.asSharedFlow()

    /**
     * Clasificación del error de descarga. TRANSIENT = vale la pena reintentar
     * (red caída, timeout, stall, 5xx/429, página de error de OneDrive).
     * PERMANENT = reintentar de inmediato no va a cambiar nada (4xx tras refresh
     * de URL, path inválido, disco lleno).
     */
    enum class ErrorKind { TRANSIENT, PERMANENT }

    /**
     * Resultado de una operación completa (descarga + análisis).
     */
    sealed class Result {
        data class Success(val song: Song) : Result()
        data class Error(val message: String, val exception: Exception? = null, val transient: Boolean = false) : Result()
        object Cancelled : Result()
        object SkippedLowBattery : Result()
    }

    /**
     * Resultado de la fase de red (descarga del archivo). Permite que el caller
     * suelte el slot de paralelismo antes de la fase pesada (analysis + BD).
     */
    sealed class DownloadStage {
        data class Success(val targetFile: File) : DownloadStage()
        data class Error(
            val message: String,
            val httpCode: Int? = null,
            val exception: Exception? = null,
            val kind: ErrorKind = ErrorKind.TRANSIENT
        ) : DownloadStage()
        object Cancelled : DownloadStage()
        object SkippedLowBattery : DownloadStage()
    }

    /**
     * Fase 1: descarga el archivo a disco. No analiza ni toca la BD.
     *
     * **La descarga es reanudable y divisible**, y las dos cosas salen de lo mismo: los bytes van a
     * `<id>.part` y lo que falta se anota en `<id>.part.meta` ([PartialDownloadState]). Antes se
     * bajaba de un tirón a un `.tmp` que el `catch` borraba, así que un corte al 99 % de un FLAC de
     * 600 MB tiraba los 600 MB y volvía a empezar de cero — con el watchdog anti-stall disparándose
     * otra vez en el mismo sitio, que es exactamente lo que se observó en un sync masivo real
     * (558 MB perdidos en una sola canción). Ahora ese mismo corte cuesta lo que va del último
     * tramo.
     *
     * [budget] permite ADEMÁS traerse el archivo por varios tramos a la vez cuando sobran
     * conexiones (ver [ExtraConnectionBudget]); sin él, o si el servidor no honra `Range`, se
     * comporta como una descarga secuencial normal.
     */
    suspend fun downloadFile(
        song: Song,
        downloadUrl: String? = null,
        isPriority: Boolean = false,
        budget: ExtraConnectionBudget? = null,
        onProgress: (Float) -> Unit = {}
    ): DownloadStage = withContext(Dispatchers.IO) {
        if (!networkManager.isAvailable()) {
            return@withContext DownloadStage.Error(context.getString(R.string.dl_err_no_internet))
        }
        if (!isPriority && isLowBattery()) {
            Log.w(TAG, "Download postponed: low battery (<$MIN_BATTERY_LEVEL%)")
            return@withContext DownloadStage.SkippedLowBattery
        }
        if (!hasEnoughDiskSpace()) {
            Log.w(TAG, "Download postponed: insufficient disk space (<${MIN_DISK_SPACE_BYTES / 1024 / 1024}MB)")
            return@withContext DownloadStage.Error(context.getString(R.string.dl_err_no_space), kind = ErrorKind.PERMANENT)
        }

        val url = downloadUrl ?: song.path
        if (url.isEmpty()) return@withContext DownloadStage.Error(context.getString(R.string.dl_err_empty_url))

        // Ya está bajado (con cualquier extensión): ni red ni re-análisis. Cubre además la
        // idempotencia que antes se comprobaba a mitad de la descarga, cuando ya se conocía el
        // nombre final — aquí no hace falta esperar al Content-Type para saberlo.
        findExistingDownload(song.id, song.size)?.let { existing ->
            onProgress(1f)
            return@withContext DownloadStage.Success(existing)
        }

        val baseName = song.id
        val musicDir = File(context.filesDir, "music")
        if (!musicDir.exists()) musicDir.mkdirs()
        val partFile = File(musicDir, "$baseName$PART_SUFFIX")
        val metaFile = File(musicDir, "$baseName$PART_SUFFIX$META_SUFFIX")

        // Path-traversal check: defensa en profundidad. Aunque baseName=song.id es controlado por
        // nosotros, validamos que la ruta resuelta no escape del sandbox antes de abrir nada.
        try {
            val safeDir = context.filesDir.canonicalPath
            if (!partFile.canonicalPath.startsWith(safeDir)) {
                return@withContext DownloadStage.Error("Path traversal detectado: ${partFile.path}", kind = ErrorKind.PERMANENT)
            }
        } catch (e: Exception) {
            return@withContext DownloadStage.Error(
                context.getString(R.string.dl_err_path_validation, e.message), exception = e, kind = ErrorKind.PERMANENT
            )
        }

        runSegmentedDownload(song, url, isPriority, budget, partFile, metaFile, onProgress)
    }

    /**
     * El cuerpo de [downloadFile] una vez resueltas las precondiciones y las rutas.
     *
     * Va aparte porque aquí conviven cuatro corrutinas auxiliares (watchdog, guardia de red,
     * volcado del sidecar y expansor de conexiones) más los tramos en vuelo; mezclarlo con los
     * chequeos previos hacía imposible seguir dónde empieza y acaba cada una.
     */
    private suspend fun runSegmentedDownload(
        song: Song,
        url: String,
        isPriority: Boolean,
        budget: ExtraConnectionBudget?,
        partFile: File,
        metaFile: File,
        onProgress: (Float) -> Unit
    ): DownloadStage {
        val wasStalled = AtomicBoolean(false)
        // La red pasó a medida a mitad de esta descarga (solo aplica al pipeline masivo).
        val wentMetered = AtomicBoolean(false)

        // Todas las llamadas vivas de esta descarga. Cancelarlas es lo ÚNICO que rompe un `read`
        // bloqueado EN EL ACTO: cerrar el InputStream desde otro hilo NO lo hace, aunque el
        // comentario de la versión anterior lo diera por hecho. Medido en logcat durante un sync
        // masivo: stall detectado a las 13:29:27 y error reportado a las 13:30:26, o sea los 60 s
        // que tardó el `readTimeout` del socket en resolver lo que el watchdog ya sabía.
        val calls = CopyOnWriteArrayList<Call>()
        fun abortAllCalls() = calls.forEach { runCatching { it.cancel() } }

        // Qué conexión lleva cada tramo, para que el watchdog pueda cortar SOLO la que se colgó.
        // Por IDENTIDAD, igual que `inProgress`: tras un split dos tramos pueden compartir números.
        val segmentCalls = java.util.IdentityHashMap<DownloadSegment, Call>()
        val segmentCallsLock = Any()
        fun bindCall(segment: DownloadSegment, call: Call) = synchronized(segmentCallsLock) {
            segmentCalls[segment] = call
        }
        fun cancelCallOf(segment: DownloadSegment) {
            val call = synchronized(segmentCallsLock) { segmentCalls[segment] } ?: return
            runCatching { call.cancel() }
        }

        var borrowedConnections = 0
        var state: PartialDownloadState? = null
        var randomAccess: RandomAccessFile? = null

        /**
         * Deja el sidecar al día con lo que hay escrito de verdad. El `force` va ANTES de escribirlo
         * para que el sidecar nunca prometa más bytes de los que están en disco: al revés, un corte
         * de corriente dejaría un hueco de basura en mitad del audio, y un hueco no lo detecta el
         * control de tamaño del final.
         */
        fun flushPartial() {
            val snapshot = state ?: return
            if (snapshot.totalBytes <= 0L || snapshot.validator == null) return
            runCatching { randomAccess?.channel?.force(false) }
            runCatching { metaFile.writeText(snapshot.serialize()) }
        }

        try {
            // --- Apertura: descubre tamaño, validador y tipo, y deja lista la primera conexión ---
            // Sin validador NO se reanuda: sin `ETag`/`Last-Modified` que poner en `If-Range` no hay
            // forma de saber si los bytes del disco pertenecen al mismo archivo que el servidor va a
            // seguir mandando, y pegar dos versiones distintas da un audio corrupto que ningún
            // control de tamaño detecta.
            val resumable = PartialDownloadState.load(metaFile, partFile)?.takeIf { it.validator != null }
            val firstGap = resumable?.segments?.firstOrNull { !it.isComplete }

            // La conexión de apertura acaba sirviendo al tramo BASE, pero ese tramo todavía no
            // existe aquí (depende de lo que conteste el servidor), así que se guarda para atarla
            // en cuanto se sepa. Sin el vínculo, el watchdog no podría cortar esa conexión sola.
            var openingCall: Call? = null
            var opening = openRange(
                url = url,
                from = firstGap?.nextByte ?: 0L,
                toInclusive = firstGap?.let { it.endExclusive - 1 },
                ifRange = resumable?.validator,
                onCall = { calls.add(it); openingCall = it }
            )

            if (!opening.isSuccessful) {
                val code = opening.code
                val message = opening.message
                opening.close()
                // 408/5xx (transporte) y 429 (throttle) son recuperables; el resto de 4xx solo
                // se arregla con URL fresca (lo maneja runDownloadWithRetry) — si reincide, es
                // permanente. El 429 se compone EXPLÍCITO porque `isRetriableTransport` lo deja
                // fuera a propósito (ver [HttpStatus]): aquí sí entra, porque no se reintenta en
                // caliente sino que se reprograma con el `nextRetryAt` de la cola persistente,
                // que es justo esperar en vez de insistir.
                //
                // Aquí NO hace falta distinguir el 503-throttle del 503-caído como en la ruta de
                // Graph: los dos son transitorios y acaban en la misma cola. Y esta petición va
                // contra el host de CONTENIDO con una URL firmada, que no comparte el throttling
                // de la API.
                //
                // El parcial NO se toca: un 4xx suele ser la URL firmada caducada, y los bytes ya
                // bajados siguen valiendo para cuando el caller vuelva con una URL fresca.
                val kind = if (HttpStatus.isRetriableTransport(code) || code == HttpStatus.TOO_MANY_REQUESTS) {
                    ErrorKind.TRANSIENT
                } else {
                    ErrorKind.PERMANENT
                }
                return DownloadStage.Error("HTTP $code: $message", httpCode = code, kind = kind)
            }

            val contentType = opening.header("Content-Type")
            // Content-Type validation: si OneDrive devuelve una página de error en vez
            // del audio (HTML/JSON), abortamos antes de escribir basura a disco.
            // Locale.ROOT: normalización de datos de protocolo. Sin él, en locale turco/azerí
            // "APPLICATION/JSON".lowercase() da "applıcatıon/json" (i sin punto) y el startsWith falla.
            val ctLower = contentType?.lowercase(Locale.ROOT)
            if (ctLower != null && (ctLower.startsWith("text/") || ctLower.startsWith("application/json"))) {
                opening.close()
                return DownloadStage.Error(context.getString(R.string.dl_err_content_type, contentType))
            }
            val extension = determineExtension(contentType, url, song.title)

            // Se reanuda solo si TODO encaja: el servidor honró el rango, el archivo mide lo mismo
            // que cuando se guardó el sidecar y el cuerpo empieza justo donde se quedó. Cualquier
            // discrepancia se trata como archivo nuevo, que siempre es correcto (solo cuesta red).
            val resumed = resumable != null && firstGap != null &&
                opening.code == HttpStatus.PARTIAL_CONTENT &&
                parseContentRangeTotal(opening.header("Content-Range")) == resumable.totalBytes &&
                parseContentRangeStart(opening.header("Content-Range")) == firstGap.nextByte

            val current: PartialDownloadState
            val baseSegment: DownloadSegment
            if (resumed) {
                current = resumable!!
                baseSegment = firstGap!!
                Log.i(TAG, "Reanudando ${song.title}: ${current.downloadedBytes}/${current.totalBytes} bytes ya en disco")
            } else {
                if (resumable != null) {
                    Log.i(TAG, "El parcial de ${song.title} ya no encaja (HTTP ${opening.code}); se baja desde cero")
                }
                PartialDownloadState.discard(partFile, metaFile)
                // Si se pidió un rango que arranca a mitad y se decidió NO reanudar, este cuerpo no
                // sirve: empieza donde iba el parcial y el plan ahora es llenar el archivo desde el
                // byte 0. Escribirlo tal cual metería el trozo equivocado al principio, y el
                // resultado pasaría todos los controles de tamaño. Se cierra y se vuelve a pedir.
                if (parseContentRangeStart(opening.header("Content-Range")) > 0L) {
                    opening.close()
                    opening = openRange(url, from = 0L, toInclusive = null, ifRange = null, onCall = { calls.add(it); openingCall = it })
                    if (!opening.isSuccessful) {
                        val code = opening.code
                        opening.close()
                        return DownloadStage.Error("HTTP $code al reabrir desde cero", httpCode = code)
                    }
                }
                val total = if (opening.code == HttpStatus.PARTIAL_CONTENT) {
                    parseContentRangeTotal(opening.header("Content-Range"))
                } else {
                    opening.body?.contentLength() ?: -1L
                }
                // Sin tamaño no hay tramos que repartir ni sidecar que escribir: se copia el cuerpo
                // tal cual. `Long.MAX_VALUE` no es un centinela caprichoso — es "acepta todo lo que
                // venga", que es literalmente el contrato de un cuerpo sin longitud declarada.
                baseSegment = DownloadSegment(0L, if (total > 0L) total else Long.MAX_VALUE)
                current = PartialDownloadState(
                    validator = opening.header("ETag") ?: opening.header("Last-Modified"),
                    totalBytes = total,
                    segments = listOf(baseSegment)
                )
            }
            // El servidor sirve rangos: es lo que habilita reanudar y repartir en tramos.
            val servedRange = opening.code == HttpStatus.PARTIAL_CONTENT
            state = current
            val totalBytes = current.totalBytes
            // Partir el archivo exige las tres cosas: que el servidor sirva rangos, saber cuánto
            // mide y tener con qué validar que sigue siendo el mismo archivo en cada conexión.
            val canSegment = servedRange && totalBytes > 0L && current.validator != null

            val file = RandomAccessFile(partFile, "rw")
            randomAccess = file
            val fileChannel: FileChannel = file.channel

            // --- Progreso ---
            // Dos hilos pueden cruzarse aquí y emitir dos veces el mismo valor; es inofensivo (el
            // consumidor solo pinta una barra) y evita un lock en el camino de cada buffer.
            val lastEmitAt = AtomicLong(0L)
            val lastEmitted = AtomicLong(current.downloadedBytes)
            fun emitProgress(force: Boolean) {
                if (totalBytes <= 0L) return
                val done = current.downloadedBytes
                // Monótono: es un intervalo entre dos avisos, no una hora. Ver
                // [DownloadSegment.lastProgressAtMs].
                val now = SystemClock.elapsedRealtime()
                val byPercent = (done - lastEmitted.get()).toFloat() / totalBytes >= PROGRESS_EMIT_STEP
                val byTime = now - lastEmitAt.get() >= PROGRESS_EMIT_INTERVAL_MS
                if (force || byPercent || byTime) {
                    lastEmitted.set(done)
                    lastEmitAt.set(now)
                    onProgress((done.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
            }
            // El primer aviso sale ya: al reanudar, la barra debe aparecer donde se quedó y no
            // volver a cero (que es justo el síntoma que este rediseño elimina).
            emitProgress(force = true)

            // --- Reparto de tramos ---
            // `inProgress` va por IDENTIDAD: dos tramos distintos pueden tener los mismos números
            // tras un split y aun así ser objetos diferentes con dueños diferentes.
            val inProgress = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<DownloadSegment, Boolean>()
            )
            val schedulerLock = Any()

            fun claimNext(): DownloadSegment? = synchronized(schedulerLock) {
                // Primero lo que ya está pendiente y sin dueño: son los huecos que dejó el intento
                // anterior, y bajarlos no cuesta abrir nada nuevo.
                val idle = current.segments.firstOrNull { !it.isComplete && it !in inProgress }
                if (idle != null) {
                    inProgress.add(idle)
                    return@synchronized idle
                }
                if (!canSegment || current.segments.size >= MAX_SEGMENTS_PER_DOWNLOAD) {
                    return@synchronized null
                }
                val victim = current.segments
                    .filter { it in inProgress }
                    .maxByOrNull { it.pending } ?: return@synchronized null
                val tail = victim.split(MIN_SPLIT_BYTES) ?: return@synchronized null
                current.addSegment(tail)
                inProgress.add(tail)
                tail
            }

            fun releaseSegment(segment: DownloadSegment) = synchronized(schedulerLock) {
                inProgress.remove(segment)
            }

            fun hasSplittableWork(): Boolean = synchronized(schedulerLock) {
                canSegment && current.segments.size < MAX_SEGMENTS_PER_DOWNLOAD &&
                    current.segments.any { it.pending >= MIN_SPLIT_BYTES * 2 }
            }

            suspend fun runSegment(segment: DownloadSegment, preOpened: Response?) {
                // El tramo es de este worker y está parado: alinear la marca de reserva con lo
                // escrito de verdad es lo que garantiza que los bytes que lleguen se coloquen justo
                // donde se van a pedir (ver `rewindToConfirmed`).
                segment.rewindToConfirmed()
                val response = preOpened ?: run {
                    val fresh = openRange(url, segment.nextByte, segment.endExclusive - 1, current.validator) { call ->
                        calls.add(call)
                        bindCall(segment, call)
                    }
                    // Un 200 aquí sería el archivo entero desde el byte 0: no encaja en un tramo
                    // que empieza más adelante, así que se descarta en vez de escribirlo torcido.
                    if (!fresh.isSuccessful || fresh.code != HttpStatus.PARTIAL_CONTENT) {
                        val code = fresh.code
                        fresh.close()
                        throw IOException("Tramo ${segment.start} rechazado: HTTP $code")
                    }
                    fresh
                }
                try {
                    val input = response.body?.byteStream()
                        ?: throw IOException(context.getString(R.string.dl_err_empty_body))
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var trimmed = false
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val (position, accepted) = segment.reserve(read)
                        if (accepted <= 0) {
                            trimmed = true
                            break
                        }
                        val slice = ByteBuffer.wrap(buffer, 0, accepted)
                        var written = 0
                        while (slice.hasRemaining()) {
                            written += fileChannel.write(slice, position + written)
                        }
                        // `confirm` sella además la marca de avance del tramo, que es lo que mira
                        // el watchdog (ver su bloque más abajo).
                        segment.confirm(accepted)
                        emitProgress(force = false)
                        // Lo que sobra ya pertenece a otro tramo (un split concurrente recortó
                        // este): se descarta y lo pedirá su nuevo dueño.
                        if (accepted < read) {
                            trimmed = true
                            break
                        }
                    }
                    if (totalBytes <= 0L) {
                        // Sin longitud declarada, el fin del cuerpo ES el fin del archivo.
                        segment.sealAtWritten()
                    } else if (!trimmed && !segment.isComplete) {
                        // El cuerpo terminó "limpio" antes de tiempo: es el truncado silencioso que
                        // de otro modo se consagraría como archivo bueno.
                        throw IOException("Tramo incompleto $segment")
                    }
                } finally {
                    response.close()
                }
            }

            suspend fun worker(firstResponse: Response?) {
                var pendingResponse = firstResponse
                var next: DownloadSegment? = if (firstResponse != null) baseSegment else claimNext()
                while (true) {
                    val segment = next ?: break
                    try {
                        runSegment(segment, pendingResponse)
                    } finally {
                        pendingResponse = null
                        releaseSegment(segment)
                    }
                    next = claimNext()
                }
            }

            synchronized(schedulerLock) { inProgress.add(baseSegment) }
            openingCall?.let { bindCall(baseSegment, it) }

            coroutineScope {
                val scope = this

                // Watchdog anti-stall, POR TRAMO.
                //
                // Vigilaba el caudal AGREGADO de la descarga (`bytesThisRun`), y con los tramos
                // paralelos eso dejó de significar lo que decía: mientras cualquiera de las cuatro
                // conexiones avance, el total sube y una colgada pasa inadvertida. Solo se detectaba
                // al quedarse sola, hasta un minuto tarde, y el corte se llevaba entonces a las tres
                // que sí iban bien. Cada tramo lleva ahora su propia marca de avance
                // ([DownloadSegment.lastProgressAtMs]) y se vigilan uno a uno.
                //
                // Qué se hace al encontrar uno depende de si queda con quién seguir:
                //
                //  · Con OTROS tramos vivos se corta SOLO su conexión. Si el colgado era un tramo
                //    EXTRA, su `runCatching` absorbe el corte, el trozo queda sin dueño —un estado que
                //    `claimNext` ya sabe recoger— y la descarga sigue por las demás conexiones. Si era
                //    el tramo BASE, su excepción sube y termina la descarga igual que cualquier fallo
                //    suyo; la ganancia entonces no es seguir, sino enterarse a tiempo en vez de un
                //    minuto después. En los dos casos lo escrito se conserva en el `.part`.
                //  · Si era el ÚLTIMO, la descarga entera está parada: eso sí es el stall de siempre,
                //    con su `wasStalled` y su corte general.
                //
                // `markActive()` tras cortar no es un parche: entre `cancel()` y el `finally` que lo
                // saca de `inProgress` pasan unos milisegundos, y sin refrescar la marca la vuelta
                // siguiente lo vería igual de parado y podría contarlo como "el último".
                val watchdog = launch {
                    while (isActive) {
                        delay(STALL_CHECK_INTERVAL_MS)
                        // El MISMO reloj con el que los tramos marcan su avance, y monótono por lo
                        // que dice [DownloadSegment.lastProgressAtMs]: aquí se decide abortar.
                        val now = SystemClock.elapsedRealtime()
                        val stalled = synchronized(schedulerLock) {
                            val victim = inProgress.firstOrNull {
                                now - it.lastProgressAtMs > STALL_TIMEOUT_MS
                            }
                            victim?.let { it to inProgress.size }
                        } ?: continue
                        val (segment, activeSegments) = stalled
                        if (activeSegments > 1) {
                            Log.w(TAG, "Tramo colgado en ${song.title} (${segment.start}): se corta esa conexión, siguen ${activeSegments - 1}")
                            cancelCallOf(segment)
                            segment.markActive()
                        } else {
                            Log.w(TAG, "Stall detected for ${song.title}: no progress in ${STALL_TIMEOUT_MS / 1000}s at ${current.downloadedBytes} bytes")
                            wasStalled.set(true)
                            abortAllCalls()
                            break
                        }
                    }
                }

                // Corte por cambio de red, con el MISMO mecanismo que el watchdog. Cancelar la
                // corrutina no bastaría: la lectura no es interrumpible y seguiría consumiendo
                // datos hasta que el socket muriera por timeout.
                //
                // Solo para el pipeline masivo: una descarga prioritaria es la canción que el
                // usuario está escuchando, y esa sí está permitida con datos móviles.
                val networkGuard = if (isPriority) null else launch {
                    networkManager.status.first { !it.isUnmetered }
                    Log.i(TAG, "Red medida durante la descarga de ${song.title}, abortando")
                    wentMetered.set(true)
                    abortAllCalls()
                }

                val flusher = launch {
                    while (isActive) {
                        delay(META_FLUSH_INTERVAL_MS)
                        flushPartial()
                    }
                }

                val extraJobs = CopyOnWriteArrayList<Job>()
                // Los tramos extra se lanzan en el scope PADRE, no dentro del expansor: colgados de
                // él, pararlo (que es lo primero que se hace al terminar) se los llevaría por
                // delante a mitad de descarga.
                val expander = if (budget == null || !canSegment) null else launch {
                    while (isActive) {
                        delay(SEGMENT_CHECK_INTERVAL_MS)
                        if (!hasSplittableWork()) continue
                        // El permiso se pide DESPUÉS de comprobar que hay algo que partir, pero
                        // entre las dos cosas otro tramo puede terminar y dejar sin trabajo a éste.
                        // Si eso pasa, el worker no encuentra nada y se va: el permiso se devuelve
                        // al acabar la descarga, que en el tramo final no le hace falta a nadie.
                        if (!budget.tryAcquire()) continue
                        borrowedConnections++
                        extraJobs.add(
                            scope.launch {
                                // Un tramo extra que falla NO tumba la descarga: su trozo queda sin
                                // dueño y lo recoge el worker base en su siguiente `claimNext` (o,
                                // si ya no queda nadie, el reintento, que reanuda por ahí). Sin
                                // este `runCatching` la excepción subiría al scope y se llevaría por
                                // delante los tramos que sí iban bien.
                                runCatching { worker(null) }.onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w(TAG, "Tramo extra de ${song.title} abortado: ${e.message}")
                                }
                            }
                        )
                    }
                }

                try {
                    worker(opening)
                    // El expansor se para ANTES del join, o podría añadir un tramo justo mientras
                    // se espera y la espera no lo cubriría. Mismo motivo (y mismo patrón) que el
                    // supervisor de paralelismo de SyncManager.
                    expander?.cancelAndJoin()
                    while (true) {
                        val snapshot = extraJobs.toList()
                        snapshot.joinAll()
                        if (extraJobs.size == snapshot.size) break
                    }
                } finally {
                    expander?.cancel()
                    watchdog.cancel()
                    networkGuard?.cancel()
                    flusher.cancel()
                }
            }

            // --- Cierre ---
            if (totalBytes > 0L && file.length() > totalBytes) {
                // Defensa: un servidor que mande de más no debe dejar cola de basura tras el último
                // byte válido (el análisis de tags leería un archivo que no cuadra con su cabecera).
                file.setLength(totalBytes)
            }
            runCatching { fileChannel.force(false) }
            file.close()
            randomAccess = null

            if (!partFile.exists() || partFile.length() == 0L) {
                return DownloadStage.Error(context.getString(R.string.dl_err_empty_file))
            }
            if (totalBytes > 0L && (!current.isComplete || partFile.length() < totalBytes)) {
                // Se CONSERVA el parcial: lo que falta es justo lo que el próximo intento pedirá.
                flushPartial()
                return DownloadStage.Error(
                    context.getString(R.string.dl_err_truncated, partFile.length(), totalBytes),
                    kind = ErrorKind.TRANSIENT
                )
            }

            val targetFile = File(partFile.parentFile, "${song.id}.$extension")
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            metaFile.delete()
            onProgress(1f)
            return DownloadStage.Success(targetFile)
        } catch (e: Exception) {
            // Antes que nada: cortamos NOSOTROS por un cambio de red, así que no es un fallo de
            // la canción. `Cancelled` no gasta un intento ni deja error en la cola persistente;
            // la fila sigue "needing work" y la retoma la continuación que espera WiFi.
            //
            // En los tres casos de corte el parcial se GUARDA, que es el cambio de fondo: el
            // próximo intento sigue por donde iba en vez de repetir lo ya bajado.
            if (wentMetered.get()) {
                flushPartial()
                return DownloadStage.Cancelled
            }
            if (wasStalled.get()) {
                flushPartial()
                Log.w(TAG, "Download stalled for ${song.title}, marking as failed and continuing queue")
                return DownloadStage.Error(context.getString(R.string.dl_err_stalled, (STALL_TIMEOUT_MS / 1000).toInt()))
            }
            if (e is kotlinx.coroutines.CancellationException) {
                flushPartial()
                Log.d(TAG, "Download cancelled: ${song.title}")
                return DownloadStage.Cancelled
            }
            Log.e(TAG, "Error downloading ${song.title}", e)
            // IOException (DNS, socket, timeout) es recuperable; el resto (estado inválido,
            // bugs) no se arregla reintentando — y entonces el parcial tampoco sirve de nada.
            return if (e is IOException) {
                flushPartial()
                DownloadStage.Error(context.getString(R.string.dl_err_exception, e.message), exception = e, kind = ErrorKind.TRANSIENT)
            } else {
                PartialDownloadState.discard(partFile, metaFile)
                DownloadStage.Error(context.getString(R.string.dl_err_exception, e.message), exception = e, kind = ErrorKind.PERMANENT)
            }
        } finally {
            runCatching { randomAccess?.close() }
            if (borrowedConnections > 0) budget?.release(borrowedConnections)
        }
    }

    /**
     * Abre una petición por un tramo del archivo y la registra en [calls] para que el watchdog
     * pueda cancelarla.
     *
     * El `Range` va SIEMPRE, incluso pidiendo el archivo entero (`bytes=0-`): es lo que hace que la
     * respuesta diga con un 206 si este servidor honra rangos, que es el dato del que dependen la
     * reanudación y el reparto en tramos. Un 200 es una respuesta válida y significa "no los honro,
     * aquí va todo desde el principio".
     */
    private fun openRange(
        url: String,
        from: Long,
        toInclusive: Long?,
        ifRange: String?,
        /**
         * Recibe la [Call] recién creada. Es una lambda y no la lista de llamadas porque quien la
         * abre sabe además a qué TRAMO pertenece, y el watchdog necesita ese vínculo para cortar
         * solo la conexión colgada en vez de las cuatro.
         */
        onCall: (Call) -> Unit
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-${toInclusive?.toString().orEmpty()}")
        // `If-Range` convierte dos peticiones en una: si el archivo cambió, en vez de un error el
        // servidor manda el contenido entero desde cero, que es exactamente el plan B.
        if (ifRange != null) builder.header("If-Range", ifRange)
        val call = okHttpClient.newCall(builder.build())
        onCall(call)
        return call.execute()
    }

    /** Tamaño total declarado en `Content-Range: bytes <ini>-<fin>/<total>`, o -1 si no lo dice. */
    private fun parseContentRangeTotal(header: String?): Long =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L

    /** Primer byte que trae el cuerpo según `Content-Range`, o -1 si no se puede leer. */
    private fun parseContentRangeStart(header: String?): Long =
        header?.substringAfter("bytes ", "")?.substringBefore('-', "")?.trim()?.toLongOrNull() ?: -1L

    /**
     * Fase 2: analiza el archivo descargado, corrige extensión y actualiza la BD.
     * Sin red. Pensado para correr fuera del semáforo de paralelismo.
     */
    suspend fun finalizeDownload(song: Song, targetFile: File): Result = withContext(Dispatchers.IO) {
        try {
            val baseName = song.id
            val analysis = audioFileAnalyzer.analyzeFile(targetFile)
            val correctExt = analysis.extension
            val currentExt = targetFile.extension
            val finalFile = if (correctExt.isNotEmpty() && !correctExt.equals(currentExt, ignoreCase = true)) {
                val correctedFile = File(targetFile.parent, "$baseName.$correctExt")
                if (targetFile.renameTo(correctedFile)) correctedFile else targetFile
            } else targetFile

            val newPath = "file://${finalFile.absolutePath}"
            musicRepository.updateSongUrl(song.id, newPath)

            // A PARTIR DE AQUÍ LA CANCIÓN YA ESTÁ DESCARGADA: el archivo está en disco y el path en
            // BD es `file://`. Todo lo que sigue es ENRIQUECIMIENTO (metadata, género, ReplayGain), y
            // su fallo NO puede convertir una descarga buena en un `Result.Error`. Antes iba en el
            // try exterior, así que un throw de `ReplayGainReader.read` (JAudioTagger abre el archivo,
            // lanza en algunos) devolvía Error DESPUÉS de haber commiteado el path — el chip decía
            // "Descargado" y el snackbar "Fallo" sobre la MISMA descarga. `clearCorrupted` va primero
            // (crítico: sin él, una canción marcada corrupta se redescargaría en cada arranque).
            runCatching {
                musicRepository.clearCorrupted(song.id)
                audioFileAnalyzer.updateSongWithAnalysis(song, newPath, analysis, musicRepository)
                musicRepository.updateGenre(song.id, analysis.genre ?: "") // "" = analizado sin género
                // ReplayGain: leer los tags del archivo local ya descargado y persistirlos.
                // Solo se hace una vez (al indexar); la reproducción luego solo lee de la DB.
                val rg = com.qhana.siku.data.util.ReplayGainReader.read(finalFile.absolutePath)
                if (!rg.isEmpty || rg.trackPeak != null || rg.albumPeak != null) {
                    musicRepository.updateReplayGain(song.id, rg.trackGainDb, rg.trackPeak, rg.albumGainDb, rg.albumPeak)
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Enriquecimiento tras descargar ${song.title} falló; la canción está descargada y es reproducible", e)
            }

            val finalSong = musicRepository.getSongById(song.id).getOrNull() ?: song.copy(path = newPath)
            // Punto ÚNICO en el que una canción pasa a estar descargada, así que es el único
            // sitio donde este aviso no se puede olvidar: los dos caminos de descarga (cola
            // masiva y prioritaria/individual) terminan aquí.
            _downloadedSongs.tryEmit(finalSong)
            Result.Success(finalSong)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error finalizing ${song.title}", e)
            Result.Error("Finalize exception: ${e.message}", e)
        }
    }

    /**
     * Hay hueco para descargar. Si el propio `StatFs` falla no se puede saber, y se deja pasar
     * a propósito: bloquear aquí convertiría un fallo al MEDIR en "ninguna descarga funciona",
     * que es mucho peor que el caso que evita — con el disco lleno de verdad la escritura falla
     * igual, ya clasificada y con reintento. Lo que sí cambia es que deje rastro: antes se
     * tragaba la excepción en silencio y ese "sí, adelante" era indistinguible de una medición
     * real, así que un dispositivo donde StatFs fallara siempre no habría dado ninguna pista.
     */
    private fun hasEnoughDiskSpace(): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes > MIN_DISK_SPACE_BYTES
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo medir el espacio libre; se permite la descarga", e)
            true
        }
    }

    private fun isLowBattery(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging // API 23+ (MinSdk 26 OK)
        return pct < MIN_BATTERY_LEVEL && !isCharging
    }

    /**
     * Extensión inicial del archivo. OneDrive suele responder `application/octet-stream`
     * para FLAC, así que tras el Content-Type se intenta la extensión del TÍTULO (el scan
     * guarda el nombre de archivo original, p. ej. "05. War.flac") y solo después la de la
     * URL — y únicamente si es una extensión de audio conocida. Antes el fallback tomaba
     * lo que hubiera tras el último '.' de la URL firmada de Graph, que producía basura
     * como `.0`. Sea cual sea el resultado, `finalizeDownload` la corrige tras el análisis.
     */
    private fun determineExtension(contentType: String?, url: String, songTitle: String): String {
        if (contentType != null) {
            when {
                contentType.contains("flac", ignoreCase = true) -> return "flac"
                contentType.contains("audio/mp4", ignoreCase = true) ||
                contentType.contains("audio/m4a", ignoreCase = true) -> return "m4a"
                contentType.contains("audio/mpeg", ignoreCase = true) -> return "mp3"
                contentType.contains("audio/wav", ignoreCase = true) -> return "wav"
                contentType.contains("audio/ogg", ignoreCase = true) -> return "ogg"
            }
        }
        val fromTitle = songTitle.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromTitle in KNOWN_AUDIO_EXTENSIONS) return fromTitle
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromUrl in KNOWN_AUDIO_EXTENSIONS) return fromUrl
        return "mp3"
    }

    /**
     * Archivo ya descargado para esta canción, con CUALQUIER extensión — incluida la basura
     * histórica `.0` del bug de octet-stream. El contenido manda; la extensión la corrige
     * `finalizeDownload` al re-analizar. Permite al pipeline saltarse red y re-descarga
     * cuando los bytes ya están en disco.
     *
     * Los archivos de trabajo (`.tmp` histórico, `.part` y su sidecar) quedan fuera por
     * definición: son descargas A MEDIAS, y darlas por buenas dejaría media canción en la
     * biblioteca sin que nada volviera a intentarlo.
     */
    fun findExistingDownload(songId: String, expectedSize: Long = 0L): File? {
        val musicDir = File(context.filesDir, "music")
        val prefix = "$songId."
        val existing = musicDir.listFiles { f: File ->
            f.name.startsWith(prefix) && !isWorkFile(f.name) && f.length() > 0L
        }?.firstOrNull() ?: return null
        // Un resto truncado no cuenta como descarga: se borra para que el pipeline
        // vuelva a bajar los bytes en vez de darlo por bueno indefinidamente.
        if (looksTruncated(existing.length(), expectedSize)) {
            Log.w(TAG, "Descarga previa truncada (${existing.length()}/$expectedSize bytes): ${existing.name}")
            try { existing.delete() } catch (_: Exception) {}
            return null
        }
        return existing
    }

    private fun isWorkFile(name: String): Boolean =
        name.endsWith(".tmp") || name.endsWith(PART_SUFFIX) || name.endsWith("$PART_SUFFIX$META_SUFFIX")

    /**
     * Borra las descargas a medias que ya no tienen dueño: las de canciones que desaparecieron de la
     * biblioteca y las de canciones que entretanto quedaron descargadas por otra vía.
     *
     * **Hace falta desde que los parciales sobreviven a un fallo.** El `.tmp` de antes lo borraba el
     * `catch` siempre, así que no podía acumularse nada; ahora se conserva a propósito, y un parcial
     * de 600 MB cuyo dueño se borró de la biblioteca se quedaría en el dispositivo para siempre. Lo
     * que NO se toca es el parcial de una canción que sigue pendiente: ESO es exactamente el trabajo
     * que el próximo intento va a reanudar.
     *
     * [protectedIds] son las descargas en vuelo. Su parcial está siendo escrito ahora mismo y podría
     * pertenecer a una canción que aún no consta como descargada.
     *
     * @return cuántos archivos se borraron.
     */
    fun prunePartialDownloads(knownIds: Set<String>, protectedIds: Set<String>): Int {
        val musicDir = File(context.filesDir, "music")
        val files = musicDir.listFiles() ?: return 0
        // El archivo final ya en disco se busca por PREFIJO y no derivando el id del nombre: un id
        // de proveedor es una cadena opaca y partirla por el último punto sería una suposición
        // sobre su forma, justo la clase de atajo que ya rompió `covers/<songId>.jpg`.
        fun alreadyFinished(songId: String): Boolean = files.any {
            it.name.startsWith("$songId.") && !isWorkFile(it.name) && it.length() > 0L
        }
        var removed = 0
        for (file in files) {
            val name = file.name
            if (!name.endsWith(PART_SUFFIX) && !name.endsWith("$PART_SUFFIX$META_SUFFIX")) continue
            val songId = name.removeSuffix(META_SUFFIX).removeSuffix(PART_SUFFIX)
            if (songId in protectedIds) continue
            if (songId in knownIds && !alreadyFinished(songId)) continue
            if (file.delete()) removed++
        }
        if (removed > 0) Log.i(TAG, "Descargas parciales sin dueño borradas: $removed archivos")
        return removed
    }

    /**
     * true si [length] es notablemente menor que [expectedSize] (tamaño del proveedor).
     * Delega en [AppConfig.looksTruncated]: el criterio de integridad es uno solo y compartido
     * con el recovery de reproducción. Se mantiene como método para los llamadores (y tests)
     * que ya lo usaban a través del downloader.
     */
    fun looksTruncated(length: Long, expectedSize: Long): Boolean =
        AppConfig.looksTruncated(length, expectedSize)

    /** Borra TODOS los archivos de esta canción (cualquier extensión). Para re-descarga forzada. */
    fun deleteExistingDownloads(songId: String) {
        val musicDir = File(context.filesDir, "music")
        val prefix = "$songId."
        musicDir.listFiles { f: File -> f.name.startsWith(prefix) }?.forEach { f ->
            try { f.delete() } catch (_: Exception) {}
        }
    }

}
