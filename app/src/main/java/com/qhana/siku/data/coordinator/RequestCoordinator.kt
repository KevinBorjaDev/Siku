package com.qhana.siku.data.coordinator

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordina todas las peticiones a OneDrive para evitar saturación de API (429)
 * y dar prioridad a descargas del usuario sobre escaneos en segundo plano.
 */
@Singleton
class RequestCoordinator @Inject constructor() {

    companion object {
        private const val TAG = "RequestCoordinator"

        /**
         * Separación mínima entre dos peticiones a Graph. Su papel NO es respetar un límite
         * publicado —Microsoft no publica ninguno para OneDrive/SharePoint, porque el throttling es
         * dinámico y por tenant— sino **aplanar la ráfaga de arranque**: al empezar un sync el
         * productor quiere llenar de golpe los hasta 32 huecos de descarga, y cada uno necesita
         * resolver su URL antes de bajar nada, así que sin esto salen ~32 peticiones en el mismo
         * instante. Con la separación, ese pico se reparte en poco más de 6 s a caudal constante.
         * Es lo que la guía de throttling pide como "reduce the frequency of calls".
         *
         * **Lo que hace aceptable el valor no es su precisión, es su holgura**, y conviene saber de
         * qué depende. Como `SyncManager` ya dimensiona las conexiones según el
         * enlace, la velocidad del WiFi se cancela y en régimen queda:
         *
         *     resoluciones/s = paralelismo / duración de una descarga ≈ enlace / tamaño de pista
         *
         * o sea "cuántos archivos completos caben por segundo en el enlace", con techo en
         * `16 MB/s ÷ tamaño` cuando el paralelismo topa en 32. Contra los 5/s que permite esto:
         * un FLAC de ~30 MB pide 0,5/s (margen de 10×) y un MP3 de 320 kbps unos 2/s. **El margen se
         * cierra con pistas pequeñas**: por debajo de ~3 MB y con enlace rápido se llega a los 5/s y
         * esto pasa a ser el cuello del arranque. No es motivo para bajarlo a ciegas —el pico que
         * evita es real— pero sí es el dato con el que decidirlo si algún día alguien reporta un
         * sync lento con una biblioteca de MP3.
         *
         * Bajar mucho el valor lo volvería inútil; subirlo hasta rozar esa demanda frenaría el sync.
         *
         * El limitador es CIEGO y preventivo; quien reacciona al throttling real es
         * [notifyThrottled], con el plazo que dicta el servidor.
         */
        private const val MIN_REQUEST_DELAY_MS = 200L

        /**
         * Política para un 429 que llega SIN cabecera `Retry-After`. No es una elección nuestra: son
         * los valores del `RetryHandler` que Microsoft especifica para sus propios SDK de Graph
         * (base 3 s, multiplicador `n²` sobre el número de aviso, techo 180 s), que es lo que su
         * guía de throttling manda hacer cuando el servidor no dicta plazo:
         *
         * > *"If no `Retry-After` header is provided by the response, we recommend implementing an
         * > exponential backoff retry policy."*
         *
         * Antes había un valor plano de 60 s elegido a ojo, y eso falla por los dos lados: si el
         * castigo real era de 10 s regala cincuenta segundos de sync parado, y si era de varios
         * minutos vuelve demasiado pronto y se gana otro — que es justo lo que la guía pide evitar
         * ("all requests accrue against your usage limits"). Creciendo, la primera espera es corta y
         * solo se alarga si el servidor sigue diciendo que no.
         *
         * Es una rama poco transitada: Graph documenta que TODOS sus recursos mandan la cabecera
         * salvo donde se indique lo contrario. Por eso importa que no cueste nada cuando no hace
         * falta, más que afinar el valor.
         */
        private const val THROTTLE_BACKOFF_BASE_SECONDS = 3L
        private const val THROTTLE_BACKOFF_MAX_SECONDS = 180L
    }

    // Estado de prioridad
    private val activePriorityDownloads = AtomicInteger(0)

    // Rate limiting.
    //
    // Las dos marcas están en el reloj MONÓTONO (`SystemClock.elapsedRealtime`) y no en wall-clock,
    // porque las dos se usan SOLO para restarles otra lectura del mismo reloj: nunca se formatean
    // ni se persisten. Con `currentTimeMillis` —lo que había— una corrección de hora del sistema
    // hacia atrás dejaba `throttledUntil` en el futuro y frenaba TODAS las peticiones durante el
    // desfase, y hacia adelante levantaba el castigo de un 429 antes de tiempo, que es justo lo que
    // hace que OneDrive lo alargue. Es el mismo criterio que documenta
    // [com.qhana.siku.player.manager.ListeningClock].
    private var lastRequestTime = 0L
    private val rateLimitMutex = Mutex()
    @Volatile private var throttledUntil = 0L

    /**
     * 429 consecutivos SIN cabecera `Retry-After`. Es el `n` de la política creciente de
     * [notifyThrottled]; lo pone a cero cualquier señal de que se volvió a la normalidad (un plazo
     * dictado por el servidor o una petición que pasó, vía [notifyRequestSucceeded]).
     */
    @Volatile private var unspecifiedThrottles = 0

    /** Avisos de throttle acumulados; se expone como [throttleEvents]. */
    private val throttleCount = AtomicInteger(0)

    private val _scanPaused = MutableStateFlow(false)

    // Estado detallado para la UI
    private val _workerStatus = MutableStateFlow<WorkerStatus>(WorkerStatus.Idle)
    val workerStatus: StateFlow<WorkerStatus> = _workerStatus

    fun startPriorityDownload() {
        val count = activePriorityDownloads.incrementAndGet()
        _scanPaused.value = true
        _workerStatus.value = WorkerStatus.PausedForPriorityDownload
        Log.d(TAG, "Priority download started. Active: $count. Scan paused.")
    }

    fun endPriorityDownload() {
        val count = activePriorityDownloads.decrementAndGet()
        if (count <= 0) {
            activePriorityDownloads.set(0)
            _scanPaused.value = false
            _workerStatus.value = WorkerStatus.Idle
            Log.d(TAG, "Priority download ended. Scan resumed.")
        }
    }

    fun shouldPauseScan(): Boolean = _scanPaused.value

    /**
     * Suspende hasta que el scan pueda continuar (fin de las descargas prioritarias).
     * Espera por señal sobre el StateFlow — sin polling. Cancelación cooperativa:
     * si el caller se cancela (logout, worker reemplazado), la espera muere con él.
     */
    suspend fun awaitScanResumed() {
        _scanPaused.first { !it }
    }

    /**
     * Aplica rate limiting antes de hacer una petición a la API.
     * Llama a esto antes de cada request a OneDrive.
     */
    suspend fun acquireRequestPermit() {
        rateLimitMutex.withLock {
            // Respect actual throttle time from 429 Retry-After header
            val now = SystemClock.elapsedRealtime()
            val throttleRemaining = throttledUntil - now
            if (throttleRemaining > 0) {
                _workerStatus.value = WorkerStatus.ThrottledByOneDrive(throttleRemaining)
                Log.w(TAG, "Throttled by OneDrive (429), waiting ${throttleRemaining}ms")
                delay(throttleRemaining)
                throttledUntil = 0L
                _workerStatus.value = WorkerStatus.Running
            }

            // Ensure minimum delay between requests
            val nowAfterThrottle = SystemClock.elapsedRealtime()
            val elapsed = nowAfterThrottle - lastRequestTime
            if (elapsed < MIN_REQUEST_DELAY_MS) {
                val waitTime = MIN_REQUEST_DELAY_MS - elapsed
                _workerStatus.value = WorkerStatus.RateLimited(waitTime)
                delay(waitTime)
            }
            lastRequestTime = SystemClock.elapsedRealtime()
            _workerStatus.value = WorkerStatus.Running
        }
    }

    /**
     * Permiso prioritario para descargas interactivas.
     * Se salta el delay mínimo (si es seguro) pero respeta el Throttling crítico.
     */
    suspend fun acquirePriorityPermit() {
        rateLimitMutex.withLock {
            // Always respect real throttle (429)
            val now = SystemClock.elapsedRealtime()
            val throttleRemaining = throttledUntil - now
            if (throttleRemaining > 0) {
                Log.w(TAG, "Priority request waiting for throttle backoff (${throttleRemaining}ms)")
                delay(throttleRemaining)
                throttledUntil = 0L
            }
            // Skip minimum delay for priority (user waiting)
            lastRequestTime = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Convierte un 429 puntual en un FRENO GLOBAL: quien se come el error avisa aquí, y a partir de
     * ese momento toda petición que pida permiso duerme hasta que venza el plazo.
     *
     * El sentido no es proteger a las demás peticiones de un 429 —eso no se contagia— sino DEJAR DE
     * GOLPEAR: durante el castigo esas peticiones fallarían igual y, además, seguir insistiendo hace
     * que OneDrive lo alargue. Existe porque quien VE la cabecera (`OneDriveRepository`, que tiene la
     * excepción en la mano) no es quien puede hacerla cumplir: solo este singleton gobierna la puerta
     * por la que salen todas. Las peticiones YA en vuelo no se tocan.
     *
     * @param retryAfterSeconds el plazo que dictó el servidor en su cabecera `Retry-After`, o `null`
     *   si no la mandó. Con valor manda el SERVIDOR y la app no opina; en `null` entra la política
     *   creciente de [THROTTLE_BACKOFF_BASE_SECONDS]. Es nullable justamente para que esa distinción
     *   se vea: antes el llamador tapaba la ausencia con un `?: 60` suyo y aquí llegaba
     *   indistinguible de un plazo real, mientras esta función declaraba un default de 5 s que nunca
     *   llegó a ejecutarse. Dos respuestas a la misma pregunta y ninguna en el sitio que decide.
     */
    fun notifyThrottled(retryAfterSeconds: Long?) {
        throttleCount.incrementAndGet()
        val waitSeconds = if (retryAfterSeconds != null) {
            // El servidor volvió a dictar plazo: la cuenta de avisos a ciegas deja de correr.
            unspecifiedThrottles = 0
            retryAfterSeconds
        } else {
            val n = ++unspecifiedThrottles
            (THROTTLE_BACKOFF_BASE_SECONDS * n * n).coerceAtMost(THROTTLE_BACKOFF_MAX_SECONDS)
        }
        val waitTimeMs = TimeUnit.SECONDS.toMillis(waitSeconds)
        throttledUntil = SystemClock.elapsedRealtime() + waitTimeMs
        _workerStatus.value = WorkerStatus.ThrottledByOneDrive(waitTimeMs)
        Log.w(
            TAG,
            "429 de OneDrive: freno ${waitSeconds}s " +
                if (retryAfterSeconds != null) "(Retry-After)" else "(backoff, aviso #$unspecifiedThrottles)"
        )
    }

    /**
     * Una petición pasó sin throttle. Solo sirve para cerrar la progresión de [notifyThrottled]: sin
     * este aviso el contador no bajaría nunca y un 429 a ciegas de hace horas dejaría la próxima
     * espera en el techo. El coordinador ve las peticiones SALIR (los permisos), no llegar, así que
     * el éxito tiene que contárselo quien lo obtiene.
     */
    fun notifyRequestSucceeded() {
        unspecifiedThrottles = 0
    }

    /**
     * Cuántas veces se ha frenado por throttling desde que arrancó el proceso. Solo sirve para
     * DESCARTAR mediciones: `SyncManager` compara el valor antes y después de una corrida de
     * descargas, y si subió no usa ese throughput para recalcular el paralelismo — el tiempo parado
     * esperando a OneDrive haría parecer lenta una conexión que no lo es, y el ajuste concluiría
     * justo lo contrario de lo que pasó (subir conexiones cuando el servidor pedía menos).
     *
     * Es un contador y no un booleano para que quien mide no tenga que acordarse de rearmarlo: la
     * pregunta que responde es "¿pasó algo entre estos dos instantes?".
     */
    val throttleEvents: Int get() = throttleCount.get()

}

/**
 * Estado detallado del worker para mostrar en la UI
 */
sealed class WorkerStatus {
    object Idle : WorkerStatus()
    object Running : WorkerStatus()
    object PausedForPriorityDownload : WorkerStatus()
    data class ThrottledByOneDrive(val waitTimeMs: Long) : WorkerStatus()
    data class RateLimited(val delayMs: Long) : WorkerStatus()
}
