package com.qhana.siku.data.coordinator

import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.AudioFileAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recupera carátulas perdidas tras una limpieza de caché del sistema (o cualquier otro
 * escenario que deje el URI de portada apuntando a un archivo inexistente).
 *
 * Estrategia:
 *  - Si el audio está local: re-extrae la carátula embebida del archivo. Si no tiene
 *    arte embebido, deja el URI en null (placeholder limpio).
 *  - Si el audio aún es remoto: deja el URI en null. `finalizeDownload` la repondrá
 *    cuando la canción se descargue.
 *  - En ambos casos invalida los colores asociados para que `ArtworkWorker` los
 *    regenere en su próximo ciclo.
 *
 * Idempotente: re-correrlo no produce trabajo cuando todos los URIs son válidos.
 */
@Singleton
class ArtworkHealingManager @Inject constructor(
    private val musicRepository: IMusicRepository,
    private val artworkRepository: ArtworkRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    private val appLogger: AppLogger
) {
    companion object { private const val TAG = "ArtworkHealing" }

    private val mutex = Mutex()

    suspend fun heal() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val songs = musicRepository.getSongsWithLocalArt()
            if (songs.isEmpty()) return@withContext

            val orphans = songs.filter { song ->
                val artPath = song.albumArtUri?.toString()?.removePrefix("file://") ?: return@filter false
                !File(artPath).exists()
            }
            if (orphans.isEmpty()) return@withContext

            appLogger.lifecycle("$TAG: ${orphans.size} carátula(s) huérfana(s) detectada(s)")
            var recovered = 0
            var cleared = 0

            for (song in orphans) {
                try {
                    val audioPath = song.path
                    val newArtUri: String? = if (audioPath.startsWith("file://")) {
                        val audioFile = File(audioPath.removePrefix("file://"))
                        if (audioFile.exists()) {
                            audioFileAnalyzer.reExtractEmbeddedArt(song.id, audioFile)
                        } else null
                    } else null

                    musicRepository.updateAlbumArtUri(song.id, newArtUri)
                    artworkRepository.invalidateCache(song.id)
                    if (newArtUri != null) recovered++ else cleared++
                } catch (e: Exception) {
                    appLogger.error("$TAG: error en ${song.id}: ${e.message}")
                }
            }
            appLogger.lifecycle("$TAG: recuperadas=$recovered, limpiadas=$cleared")
        }
    }
}
