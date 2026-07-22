package com.qhana.siku

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import com.qhana.siku.worker.ArtworkWorker
import com.qhana.siku.worker.WorkerTags
import androidx.work.Constraints
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.qhana.siku.data.coordinator.ArtworkHealingManager
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.repository.IPlaylistRepository
import androidx.glance.appwidget.updateAll
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.widget.PlayerWidget
import com.qhana.siku.widget.QueueWidget
import com.qhana.siku.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class con Coil 3 para carga de imágenes.
 * Implementa SingletonImageLoader.Factory (Coil 3) en vez del antiguo ImageLoaderFactory.
 */
@HiltAndroidApp
class MusicPlayerApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var playlistRepository: IPlaylistRepository
    @Inject lateinit var musicRepository: IMusicRepository
    @Inject lateinit var artworkHealingManager: ArtworkHealingManager
    @Inject lateinit var widgetBridge: WidgetBridge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastNightMode = -1

    override fun onCreate() {
        super.onCreate()

        lastNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK

        // Puente reproductor → widgets de pantalla de inicio: vive todo el proceso.
        widgetBridge.start(appScope)

        // Garantizar que la playlist de favoritos exista (idempotente).
        appScope.launch { playlistRepository.ensureFavoritesPlaylist() }

        // Migración one-time: filesDir/music_cache/ → filesDir/music/. Tras unificar las
        // descargas (un solo MusicDownloader / SyncManager.downloadSong), todos los archivos
        // viven en music/ con el patrón "${id}.${ext}". Movemos lo que quedó en music_cache/
        // (con patrón "${id}_${titulo_saneado}.${ext}" del worker antiguo) renombrándolo.
        appScope.launch { migrateMusicCacheToMusic() }

        // Healing de carátulas huérfanas: tras "limpiar caché" del sistema (o cualquier
        // pérdida del directorio de covers), re-extrae del audio local o limpia el URI.
        // No bloquea el arranque; corre en background al iniciar el proceso.
        appScope.launch { artworkHealingManager.heal() }

        // Programar worker de colores en background con restricciones de batería
        // Solo ejecutar cuando: batería OK, dispositivo idle (para no interferir con uso activo)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true) // Solo cuando el dispositivo está inactivo
            .build()

        val request = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setConstraints(constraints)
            .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS) // Esperar 30s después de inicio
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            WorkerTags.ARTWORK_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // Los widgets hornean sus colores al traducirse a RemoteViews: sin este re-render,
    // el cambio claro↔oscuro del sistema no se refleja hasta el siguiente cambio de canción.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            appScope.launch {
                runCatching { PlayerWidget().updateAll(this@MusicPlayerApp) }
                runCatching { QueueWidget().updateAll(this@MusicPlayerApp) }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Usar el ImageLoader inyectado (configurado en AppModule)
    override fun newImageLoader(context: Context): ImageLoader = imageLoader

    /**
     * Migración idempotente de archivos descargados desde la convención vieja
     * (`filesDir/music_cache/${id}_${titulo}.${ext}`) a la nueva
     * (`filesDir/music/${id}.${ext}`). Actualiza `uriString` en BD para cada movido.
     */
    private suspend fun migrateMusicCacheToMusic() {
        try {
            val oldDir = java.io.File(filesDir, "music_cache")
            if (!oldDir.exists() || !oldDir.isDirectory) return
            val files = oldDir.listFiles() ?: return
            if (files.isEmpty()) { oldDir.delete(); return }

            val newDir = java.io.File(filesDir, "music").also { if (!it.exists()) it.mkdirs() }
            var moved = 0
            for (file in files) {
                try {
                    if (!file.isFile || file.length() == 0L) { file.delete(); continue }
                    val nameWithoutExt = file.nameWithoutExtension
                    // Patrones: "<id>" (nuevo) o "<id>_<titulo_saneado>" (worker antiguo).
                    val id = if ('_' in nameWithoutExt) nameWithoutExt.substringBefore('_') else nameWithoutExt
                    val ext = file.extension.ifBlank { "mp3" }
                    val target = java.io.File(newDir, "$id.$ext")
                    if (target.exists() && target.length() > 0L) {
                        // Ya existe en el nuevo lugar — el viejo es duplicado.
                        file.delete()
                        continue
                    }
                    val ok = file.renameTo(target) || run {
                        // renameTo puede fallar entre filesystems → fallback copy + delete.
                        runCatching { file.copyTo(target, overwrite = true); file.delete() }.isSuccess
                    }
                    if (ok) {
                        musicRepository.updateSongUrl(id, "file://${target.absolutePath}")
                        moved++
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MusicPlayerApp", "Migración: error procesando ${file.name}: ${e.message}")
                }
            }
            if (oldDir.listFiles()?.isEmpty() == true) oldDir.delete()
            if (moved > 0) android.util.Log.i("MusicPlayerApp", "Migración music_cache → music: $moved archivos movidos")
        } catch (e: Exception) {
            android.util.Log.w("MusicPlayerApp", "Migración: error general", e)
        }
    }
}