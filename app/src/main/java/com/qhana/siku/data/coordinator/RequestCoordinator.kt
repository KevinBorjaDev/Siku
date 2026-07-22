package com.qhana.siku.data.coordinator

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val MIN_REQUEST_DELAY_MS = 200L
    }

    // Estado de prioridad
    private val activePriorityDownloads = AtomicInteger(0)

    // Rate limiting
    private var lastRequestTime = 0L
    private val rateLimitMutex = Mutex()
    @Volatile private var throttledUntil = 0L

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
            val now = System.currentTimeMillis()
            val throttleRemaining = throttledUntil - now
            if (throttleRemaining > 0) {
                _workerStatus.value = WorkerStatus.ThrottledByOneDrive(throttleRemaining)
                Log.w(TAG, "Throttled by OneDrive (429), waiting ${throttleRemaining}ms")
                delay(throttleRemaining)
                throttledUntil = 0L
                _workerStatus.value = WorkerStatus.Running
            }

            // Ensure minimum delay between requests
            val nowAfterThrottle = System.currentTimeMillis()
            val elapsed = nowAfterThrottle - lastRequestTime
            if (elapsed < MIN_REQUEST_DELAY_MS) {
                val waitTime = MIN_REQUEST_DELAY_MS - elapsed
                _workerStatus.value = WorkerStatus.RateLimited(waitTime)
                delay(waitTime)
            }
            lastRequestTime = System.currentTimeMillis()
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
            val now = System.currentTimeMillis()
            val throttleRemaining = throttledUntil - now
            if (throttleRemaining > 0) {
                Log.w(TAG, "Priority request waiting for throttle backoff (${throttleRemaining}ms)")
                delay(throttleRemaining)
                throttledUntil = 0L
            }
            // Skip minimum delay for priority (user waiting)
            lastRequestTime = System.currentTimeMillis()
        }
    }

    /**
     * Notifica que recibimos un error 429 (Too Many Requests)
     */
    fun notifyThrottled(retryAfterSeconds: Long = 5) {
        val waitTimeMs = retryAfterSeconds * 1000L
        throttledUntil = System.currentTimeMillis() + waitTimeMs
        _workerStatus.value = WorkerStatus.ThrottledByOneDrive(waitTimeMs)
        Log.w(TAG, "Received 429 from OneDrive, throttle backoff for ${retryAfterSeconds}s")
    }

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
