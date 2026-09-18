package com.qhana.siku.data.coordinator

import android.util.Log
import com.qhana.siku.data.local.ArtistCreditDao
import com.qhana.siku.data.local.ArtistCreditEntity
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.ArtistCredits
import com.qhana.siku.data.model.ArtistSplitConfig
import com.qhana.siku.data.preferences.MusicPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El ÚNICO escritor de `song_artists` (ver [ArtistCreditEntity]).
 *
 * Trabaja por SEÑAL, no por intervalo (convención 13), y las señales son dos:
 *  - la **cola** que llenan los triggers de `songs`: cualquier canción que entra o cambia de
 *    artista, venga del camino que venga;
 *  - el **ajuste de separación de colaboraciones**: cambiarlo cambia los créditos de toda la
 *    biblioteca, así que re-encola todo.
 *
 * Vive lo que vive el proceso —lo arranca `MusicPlayerApp`—, porque un escaneo puede correr en un
 * worker sin ninguna pantalla abierta y sus canciones tienen que quedar bien agrupadas igual.
 */
@Singleton
class ArtistCreditIndexer @Inject constructor(
    private val dao: ArtistCreditDao,
    private val musicPreferences: MusicPreferences
) {
    private val started = AtomicBoolean(false)
    private val drainLock = Mutex()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            // Sin `drop(1)`: la primera emisión es la que detecta un ajuste cambiado cuyo re-encolado
            // no llegó a hacerse (el proceso murió entre guardar y encolar). Ver la huella en
            // `MusicPreferences.saveAppliedArtistSplitFingerprint`.
            musicPreferences.artistSplitConfigFlow.collect { config ->
                val fingerprint = fingerprintOf(config)
                if (fingerprint != musicPreferences.loadAppliedArtistSplitFingerprint()) {
                    dao.enqueueAll()
                    musicPreferences.saveAppliedArtistSplitFingerprint(fingerprint)
                    Log.i(TAG, "Separación de artistas cambiada: biblioteca re-encolada")
                }
            }
        }

        scope.launch {
            // `conflate`: mientras se vacía la cola llegan más invalidaciones (las del propio vaciado
            // incluidas). No hace falta atender cada una, basta con volver a mirar al terminar.
            dao.pendingCountFlow()
                .conflate()
                .filter { it > 0 }
                .collect { drain() }
        }
    }

    /**
     * Vacía la cola por lotes. La configuración se relee en cada lote: si cambia a mitad, el
     * re-encolado ya puso toda la biblioteca de vuelta con un `seq` mayor, y lo que se calculó con la
     * vieja se vuelve a calcular.
     */
    private suspend fun drain() = drainLock.withLock {
        var total = 0
        while (true) {
            val batch = dao.nextBatch(BATCH_SIZE)
            if (batch.isEmpty()) break
            val config = musicPreferences.loadArtistSplitConfig()
            val credits = dao.sources(batch.map { it.songId }).flatMap { source ->
                creditsFor(source.id, source.artist, source.albumArtist, source.artistCredits, config)
            }
            dao.applyBatch(batch, credits)
            total += batch.size
            // Cede entre lotes: al actualizar una biblioteca grande esto son unos segundos de trabajo
            // de BD, y no deben acaparar el dispatcher del arranque.
            yield()
        }
        if (total > 0) Log.i(TAG, "Créditos de artista calculados: $total canciones")
    }

    private fun creditsFor(
        songId: String,
        artist: String,
        albumArtist: String?,
        artistCredits: String?,
        config: ArtistSplitConfig
    ): List<ArtistCreditEntity> {
        val track = ArtistCredits.trackCredits(artist, artistCredits, config)
        val album = ArtistCredits.albumCredits(albumArtist, track, config)
        return track.map { ArtistCreditEntity(songId, ArtistCreditRole.TRACK.role, it) } +
            album.map { ArtistCreditEntity(songId, ArtistCreditRole.ALBUM.role, it) }
    }

    private fun fingerprintOf(config: ArtistSplitConfig): String =
        if (!config.enabled) "off"
        else buildString {
            append("on|")
            append(config.separators.map { it.name }.sorted().joinToString(","))
            append('|')
            append(config.exceptions.map { it.lowercase() }.sorted().joinToString("\u001F"))
        }

    private companion object {
        const val TAG = "ArtistCreditIndexer"

        /**
         * Por debajo del límite de variables de SQLite (999 en las versiones de minSdk 26): el lote va
         * entero en el `IN (...)` de [ArtistCreditDao.sources] y de `deleteCredits`.
         */
        const val BATCH_SIZE = 400
    }
}
