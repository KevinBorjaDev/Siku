package com.qhana.siku.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.util.AudioFileAnalyzer

@Singleton
class OneDriveRepository @Inject constructor(
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager,
    private val requestCoordinator: RequestCoordinator,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OneDriveRepo"
    }

    
    /**
     * Helper para reintentos con backoff exponencial
     */
    private suspend fun <T> executeWithRetry(
        times: Int = 3,
        initialDelay: Long = 1000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                // No reintentar errores fatales de HTTP (401, 403, 404)
                if (e is retrofit2.HttpException) {
                    val code = e.code()
                    if (code == 401 || code == 403 || code == 404) throw e
                }
                
                android.util.Log.w(TAG, "Reintento fallido (${attempt + 1}/$times). Error: ${e.message}")
                
                if (attempt < times - 1) {
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }
        
        android.util.Log.e(TAG, "Todos los $times intentos fallaron")
        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    private suspend fun fetchDownloadUrl(remoteId: String, usePriorityPermit: Boolean): String? {
        val authResult = authManager.getAccessToken().first()
        if (authResult !is AuthResult.Success) return null
        
        val tokenStr = authResult.token
        if (tokenStr.isBlank()) {
            android.util.Log.e(TAG, "Token de acceso vacío. Abortando solicitud.")
            return null
        }
        
        val token = "Bearer $tokenStr"

        return try {
            executeWithRetry {
                if (usePriorityPermit) {
                    requestCoordinator.acquirePriorityPermit()
                } else {
                    requestCoordinator.acquireRequestPermit()
                }
                
                val item = oneDriveApi.getItem(token, remoteId)
                item.downloadUrl
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 60
                android.util.Log.w(TAG, "Throttled by OneDrive. Retry-After: ${retryAfter}s")
                requestCoordinator.notifyThrottled(retryAfter)
            }
            android.util.Log.e(TAG, "HTTP error refrescando URL ($remoteId): ${e.code()}", e)
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error refrescando URL ($remoteId)", e)
            null
        }
    }

    suspend fun getDownloadUrl(remoteId: String): String? {
        return fetchDownloadUrl(remoteId, usePriorityPermit = false)
    }

    /**
     * Función para worker en segundo plano: Lee metadata real del archivo y extrae carátula
     */
    suspend fun extractMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        val analysis = try {
            executeWithRetry(times = 2) { 
                if (song.path.startsWith("file://")) {
                    val file = File(song.path.removePrefix("file://"))
                    audioFileAnalyzer.analyzeFile(file)
                } else {
                    audioFileAnalyzer.analyzeUrl(song.path)
                }
            }
        } catch (e: Exception) {
            null
        }

        if (analysis != null && analysis.isValid) {
            // Guardar cover art si existe
            var artUri = song.albumArtUri
            if (analysis.embeddedArt != null) {
                try {
                    val coversDir = File(context.filesDir, "covers")
                    if (!coversDir.exists()) coversDir.mkdirs()
                    val file = File(coversDir, "${song.id}.jpg")
                    FileOutputStream(file).use { fos ->
                        fos.write(analysis.embeddedArt)
                    }
                    artUri = Uri.fromFile(file)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Error guardando carátula: ${e.message}")
                }
            }

            song.copy(
                title = analysis.title ?: song.title,
                artist = analysis.artist ?: song.artist,
                album = analysis.album ?: song.album,
                genre = analysis.genre ?: song.genre,
                duration = if (song.duration == 0L) analysis.duration else song.duration,
                albumArtUri = artUri
            )
        } else {
            song
        }
    }

}