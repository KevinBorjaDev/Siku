package com.qhana.siku.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qhana.siku.MainActivity
import com.qhana.siku.R
import com.qhana.siku.data.coordinator.IncompleteReason
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncOutcome
import com.qhana.siku.data.coordinator.SyncStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
    private val downloadScheduler: DownloadScheduler
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScanWorker"
        private const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 2002

        // Suelo para la continuación que reintenta las descargas fallidas: si el backoff más
        // próximo ya venció (o vence en segundos), no tiene sentido re-encolar de inmediato —
        // sería otra corrida que vuelve a fallar por la misma causa que aún no se resolvió.
        private const val MIN_RETRY_CONTINUATION_DELAY_MS = 60_000L
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            applicationContext.getString(R.string.notif_syncing_title),
            applicationContext.getString(R.string.notif_checking_changes)
        )

    private fun buildForegroundInfo(title: String, text: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, applicationContext.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sync_anim)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        // API 34+ exige declarar foregroundServiceType explícitamente al crear el FGS,
        // de lo contrario lanza InvalidForegroundServiceTypeException. El manifest ya
        // declara FOREGROUND_SERVICE_DATA_SYNC permission + SystemForegroundService con
        // foregroundServiceType="dataSync".
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext Result.success()

        // Promoción a foreground service: mantiene el proceso vivo cuando la app va a
        // background. Sin esto, MIUI/Xiaomi y otros OEMs agresivos matan el sync.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo promover a foreground: ${e.message}")
        }

        // WifiLock: con pantalla apagada la WiFi entra en ahorro de energía y las descargas
        // se estancan hasta que el watchdog (60s) las mata en cascada. El lock mantiene la
        // radio activa mientras dura el sync; se libera siempre en el finally.
        val wifiLock: WifiManager.WifiLock? = try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "siku:sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir WifiLock: ${e.message}")
            null
        }

        // Observador paralelo del state del sync para actualizar la notificación con
        // progreso real. Se cancela al terminar para no fugar el scope.
        val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notificationJob: Job = notificationScope.launch {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            syncManager.state.collectLatest { state ->
                val (title, text) = when (state) {
                    is SyncStatus.Scanning -> applicationContext.getString(R.string.notif_syncing_title) to
                        (if (state.found > 0) applicationContext.getString(R.string.notif_changes_detected, state.found) else applicationContext.getString(R.string.notif_checking_changes))
                    is SyncStatus.Downloading -> applicationContext.getString(R.string.notif_downloading) to
                        applicationContext.getString(
                            R.string.notif_download_progress,
                            state.current,
                            state.total,
                            if (state.failed > 0) applicationContext.getString(R.string.sync_failed_suffix, state.failed) else ""
                        )
                    is SyncStatus.Complete,
                    is SyncStatus.Error,
                    SyncStatus.Idle -> return@collectLatest
                }
                val notification = buildForegroundInfo(title, text).notification
                try {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Error actualizando notificación: ${e.message}")
                }
            }
        }

        try {
            val forceRefresh = inputData.getBoolean("force_refresh", false)
            when (val outcome = syncManager.startSync(forceRefresh)) {
                SyncOutcome.Skipped -> Result.success()
                is SyncOutcome.Completed -> {
                    // Quedaron fallidas en backoff: programamos la continuación para cuando
                    // venza la más próxima (cola persistente v18). Sin esto, solo se
                    // reintentarían en la próxima apertura de la app.
                    outcome.nextRetryAt?.let { retryAt ->
                        val delayMs = (retryAt - System.currentTimeMillis())
                            .coerceAtLeast(MIN_RETRY_CONTINUATION_DELAY_MS)
                        Log.d(TAG, "Fallidas en backoff: continuación en ${delayMs / 1000}s")
                        downloadScheduler.scheduleRetryScan(initialDelayMs = delayMs)
                    }
                    Result.success()
                }
                is SyncOutcome.Incomplete -> when (outcome.reason) {
                    // Logout/release: no hay nada que reanudar.
                    IncompleteReason.CANCELLED -> Result.success()
                    // Hay red pero no WiFi: el scan ya corrió; encadenamos una continuación
                    // que espera red no medida en vez de reintentar en caliente por datos.
                    IncompleteReason.NO_WIFI -> {
                        Log.d(TAG, "Sin WiFi para descargas: programando continuación UNMETERED")
                        downloadScheduler.scheduleScanContinuationOnWifi()
                        Result.success()
                    }
                    // Batería/red: WorkManager reanuda con backoff + constraints. El progreso
                    // es incremental (lo descargado queda file:// en BD), así que reintentar
                    // solo procesa lo pendiente.
                    IncompleteReason.LOW_BATTERY,
                    IncompleteReason.NETWORK_LOST -> {
                        Log.w(TAG, "Sync incompleto (${outcome.reason}), solicitando retry")
                        if (runAttemptCount < 10) Result.retry()
                        else Result.failure(workDataOf("error" to outcome.reason.name))
                    }
                }
                is SyncOutcome.Failed -> classifyFailure(outcome)
            }
        } finally {
            notificationJob.cancel()
            notificationScope.cancel()
            try { wifiLock?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Traduce un fallo del sync a la decisión de WorkManager. Reproduce la clasificación
     * que antes vivía en catch-blocks muertos (startSync tragaba las excepciones y este
     * worker nunca las veía).
     */
    private fun classifyFailure(outcome: SyncOutcome.Failed): Result {
        val cause = outcome.cause
        return when {
            outcome.isAuthError -> {
                Log.e(TAG, "Auth error, not retrying: ${outcome.message}")
                Result.failure(workDataOf("error" to "auth_error"))
            }
            cause is HttpException -> when (cause.code()) {
                401, 403 -> Result.failure(workDataOf("error" to "auth_error"))
                404 -> Result.failure(workDataOf("error" to "not_found"))
                408, 429, 500, 502, 503, 504 -> {
                    Log.w(TAG, "Temporary error ${cause.code()}, will retry")
                    if (runAttemptCount < 5) Result.retry()
                    else Result.failure(workDataOf("error" to "max_retries"))
                }
                else -> Result.failure(workDataOf("error" to "http_${cause.code()}"))
            }
            cause is IOException -> {
                Log.w(TAG, "Network error, will retry: ${cause.message}")
                if (runAttemptCount < 5) Result.retry()
                else Result.failure(workDataOf("error" to "network"))
            }
            else -> {
                Log.e(TAG, "Unexpected error, not retrying: ${outcome.message}", cause)
                Result.failure(workDataOf("error" to (cause?.javaClass?.simpleName ?: outcome.message)))
            }
        }
    }
}
