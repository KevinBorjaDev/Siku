package com.qhana.siku.data.coordinator

import android.net.Uri
import android.util.Log
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.encodeMultiValue
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import com.qhana.siku.data.util.NetworkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lee los tags de artista de la v29 (ALBUMARTIST y los artistas multivalor) en la biblioteca que ya
 * existía antes de que se leyeran. Lo que entra a partir de ahora los trae de fábrica — el análisis y
 * la metadata ligera los escriben —, así que esto solo tiene trabajo tras actualizar.
 *
 * Mismo reparto que [TrackInfoBackfiller], y por el mismo motivo:
 *  - **audio en el dispositivo** (`file://` o `content://`): se abre el archivo, sin red;
 *  - **nube sin descargar**: solo la cabecera con `Range`, y solo con WiFi.
 *
 * **La diferencia con aquél es el cierre**: allí hacía falta un flag en preferencias porque "las que
 * están a 0" no se vacía sola. Aquí sí: leer SELLA `songs.albumArtist` (`""` si el archivo no trae el
 * tag), así que la lista de pendientes es exactamente lo que falta por mirar. Lo que no se pudo leer
 * —un archivo que ya no está, una cabecera que no respondió, un formato sin lector— se queda en NULL
 * y se reintenta en el siguiente sync, que es lo correcto: no se ha constatado nada.
 */
@Singleton
class ArtistTagsBackfiller @Inject constructor(
    private val musicRepository: IMusicRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    private val sourceRegistry: MusicSourceRegistry,
    private val networkManager: NetworkManager
) {

    private val counterLock = Mutex()

    /** @return cuántas canciones quedaron selladas. */
    suspend fun run(isStopped: () -> Boolean): Int = coroutineScope {
        val canUseNetwork = networkManager.isWifi()
        val pending = musicRepository.getSongsNeedingArtistTags(localOnly = !canUseNetwork)
        if (pending.isEmpty()) return@coroutineScope 0

        Log.i(TAG, "Tags de artista: ${pending.size} canciones pendientes (red=$canUseNetwork)")
        var sealed = 0
        val semaphore = Semaphore(PARALLELISM)
        pending.map { song ->
            launch {
                if (isStopped()) return@launch
                semaphore.withPermit {
                    if (isStopped()) return@withPermit
                    if (backfill(song)) counterLock.withLock { sealed++ }
                }
            }
        }.forEach { it.join() }
        Log.i(TAG, "Tags de artista: $sealed selladas")
        sealed
    }

    private suspend fun backfill(song: Song): Boolean = try {
        if (song.path.startsWith(FILE_SCHEME) || song.path.startsWith(CONTENT_SCHEME)) {
            backfillFromFile(song)
        } else {
            backfillFromHeader(song)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer ${song.id}: ${e.message}")
        false
    }

    private suspend fun backfillFromFile(song: Song): Boolean {
        val analysis = if (song.path.startsWith(FILE_SCHEME)) {
            audioFileAnalyzer.analyzeFile(File(Uri.parse(song.path).path ?: return false))
        } else {
            audioFileAnalyzer.analyzeContentUri(Uri.parse(song.path), song.title)
        }
        val albumArtist = analysis.albumArtist
        if (!analysis.isValid || albumArtist == null) return false
        musicRepository.updateArtistTags(song.id, albumArtist, analysis.artistCredits)
        return true
    }

    /**
     * Nube sin descargar. `fetchLightMetadata == null` NO se sella: igual significa "sin lector para
     * este formato" que "la petición falló", y lo segundo tiene que poder reintentarse. Lo primero se
     * sella solo al descargarse la canción, cuando el análisis completo lee el archivo.
     */
    private suspend fun backfillFromHeader(song: Song): Boolean {
        val meta = sourceRegistry.fetchLightMetadata(song) ?: return false
        musicRepository.updateArtistTags(
            song.id,
            albumArtist = encodeMultiValue(meta.albumArtists) ?: "",
            artistCredits = encodeMultiValue(meta.artists)
        )
        return true
    }

    private companion object {
        const val TAG = "ArtistTagsBackfiller"

        /** Mismo criterio conservador que [TrackInfoBackfiller.PARALLELISM]. */
        const val PARALLELISM = 4

        const val FILE_SCHEME = "file://"
        const val CONTENT_SCHEME = "content://"
    }
}
