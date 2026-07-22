package com.qhana.siku.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized helper for scheduling downloads and maintenance tasks.
 * Removes duplication of Worker construction logic across ViewModels.
 */
@Singleton
class DownloadScheduler @Inject constructor(
    private val workManager: WorkManager
) {

    /**
     * Schedules a single song download.
     * @param songId Song ID.
     * @param forceRedownload If true, forces download even if file exists locally.
     * @param isUserInitiated If true, uses expedited policy to start ASAP.
     * @param customWorkName Optional unique work name (e.g. "auto_download").
     */
    fun scheduleDownload(
        songId: String,
        forceRedownload: Boolean = false,
        isUserInitiated: Boolean = true,
        customWorkName: String? = null
    ) {
        val requestBuilder = OneTimeWorkRequestBuilder<SingleSongDownloadWorker>()
            .setInputData(workDataOf(
                "songId" to songId,
                "forceRedownload" to forceRedownload
            ))
            .addTag(WorkerTags.downloadTag(songId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )

        if (isUserInitiated) {
            requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            requestBuilder.addTag(WorkerTags.DOWNLOAD_TRACKING_TAG)
        }
        
        // If it's a repair, add the repair tag
        if (forceRedownload) {
            requestBuilder.addTag(WorkerTags.REPAIR_TAG)
        }

        val uniqueWorkName = customWorkName ?: if (forceRedownload) WorkerTags.repairTag(songId) else WorkerTags.downloadTag(songId)
        
        // REPLACE ensures the new config (e.g. forceRedownload) takes effect
        // KEEP avoids interrupting a valid in-progress download
        val policy = if (forceRedownload) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            policy,
            requestBuilder.build()
        )
    }

    /**
     * Schedules a full library scan.
     * Uses BATTERY_NOT_LOW constraint to avoid running during low battery.
     *
     * @param requiresNetwork false para un escaneo que debe correr aunque no haya red (p.ej.
     *        justo tras elegir la carpeta LOCAL). Las fuentes cloud fallarán su `discover` y el
     *        orquestador seguirá con las demás (resiliencia multi-fuente).
     */
    fun scheduleScan(force: Boolean = false, requiresNetwork: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf("force_refresh" to force))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )
            .build()

        workManager.enqueueUniqueWork(
            WorkerTags.SCAN_WORK_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Continuación del sync cuando quedaron descargas pendientes por falta de WiFi:
     * misma cadena de unique work, pero con constraint UNMETERED para que WorkManager
     * la dispare solo cuando haya red no medida (sin reintentos en caliente por datos).
     */
    fun scheduleScanContinuationOnWifi() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf("force_refresh" to false))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        // APPEND_OR_REPLACE: si el ScanWorker actual sigue corriendo (este método se llama
        // desde su propio doWork), la continuación se encadena al terminar; si la cadena
        // anterior falló, se reemplaza limpia.
        workManager.enqueueUniqueWork(WorkerTags.SCAN_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Reintento de descargas fallidas cuando no hay un productor activo que pueda
     * consumirlas inline (ver SyncManager.retryFailedDownloads). Corre como ScanWorker
     * normal — CON foreground service, a diferencia del viejo startSync en scope desnudo.
     *
     * @param initialDelayMs delay opcional: lo usa ScanWorker para programar la
     *        continuación cuando venza el backoff de las fallidas (cola persistente v18).
     */
    fun scheduleRetryScan(initialDelayMs: Long = 0L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf("force_refresh" to false))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .apply { if (initialDelayMs > 0) setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS) }
            .build()

        // APPEND_OR_REPLACE: si el sync sigue drenando descargas en vuelo, el retry se
        // encadena justo después en vez de perderse (la ventana de carrera del botón).
        workManager.enqueueUniqueWork(WorkerTags.SCAN_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}