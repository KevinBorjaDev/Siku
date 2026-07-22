package com.qhana.siku.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qhana.siku.R
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.manager.MusicDownloader
import com.qhana.siku.data.repository.IMusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper fino sobre `SyncManager.downloadSong`. Aporta:
 *  - ForegroundInfo con `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (requerido en API 34+).
 *  - Notificación visible mientras la descarga prioritaria corre.
 *  - Coordinación con `RequestCoordinator` para pausar el scan masivo si está corriendo.
 *
 * Toda la lógica de descarga (resolución de URL, transferencia, watchdog, finalize, BD,
 * panel `activeDownloads`) vive en `SyncManager.downloadSong`. Aquí solo se invoca.
 */
@HiltWorker
class SingleSongDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: IMusicRepository,
    private val syncManager: SyncManager,
    private val requestCoordinator: RequestCoordinator
) : CoroutineWorker(context, params) {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "download_priority_channel"
        private const val NOTIFICATION_ID = 2001
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, applicationContext.getString(R.string.notif_channel_priority), NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val intent = android.content.Intent(applicationContext, com.qhana.siku.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(applicationContext, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_downloading_song))
            .setContentText(applicationContext.getString(R.string.notif_priority_processing))
            .setSmallIcon(R.drawable.ic_stat_sync_anim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        // API 34+ exige foregroundServiceType explícito; sin esto el FGS lanza
        // InvalidForegroundServiceTypeException. Manifest ya tiene DATA_SYNC.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString("songId")
        val forceRedownload = inputData.getBoolean("forceRedownload", false)
        if (songId.isNullOrBlank()) return@withContext Result.failure(workDataOf("error" to "Invalid song ID"))

        // Título para el outputData (lo usa el observador de toasts en MainActivity).
        val title = musicRepository.getSongById(songId).getOrNull()?.title ?: songId

        requestCoordinator.startPriorityDownload()
        try {
            val res = syncManager.downloadSong(songId, force = forceRedownload)
            when (res) {
                is MusicDownloader.Result.Success -> Result.success(workDataOf("title" to (res.song.title.ifBlank { title })))
                is MusicDownloader.Result.Error -> {
                    // Errores transitorios (red, stall, 5xx): dejar que WorkManager
                    // reintente con backoff en vez de fallar definitivo al primer blip.
                    if (res.transient && runAttemptCount < 3) Result.retry()
                    else Result.failure(workDataOf("error" to res.message, "title" to title))
                }
                MusicDownloader.Result.Cancelled -> Result.failure(workDataOf("error" to "cancelled", "title" to title))
                MusicDownloader.Result.SkippedLowBattery -> Result.retry()
            }
        } finally {
            requestCoordinator.endPriorityDownload()
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }
}
