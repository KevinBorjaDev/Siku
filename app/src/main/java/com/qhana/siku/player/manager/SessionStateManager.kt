package com.qhana.siku.player.manager

import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sesión restaurada desde la BD: cola resuelta + índice y posición guardados.
 *
 * [playlist] viene en el orden en que se reproducía (barajado si [shuffleEnabled]);
 * [originalPlaylist] es el orden real al que se vuelve al apagar el aleatorio (vacía si no
 * había aleatorio activo).
 */
data class RestoredSession(
    val playlist: List<Song>,
    val index: Int,
    val position: Long,
    val shuffleEnabled: Boolean,
    val originalPlaylist: List<Song>
)

@Singleton
class SessionStateManager @Inject constructor(
    private val musicPreferences: MusicPreferences,
    private val musicRepository: IMusicRepository
) {

    fun saveSessionState(
        internalPlaylist: List<Song>,
        currentIndex: Int,
        position: Long,
        shuffleEnabled: Boolean,
        originalPlaylist: List<Song>
    ) {
        // Snapshot defensivo: si el caller pasa una lista mutable que cambia mientras
        // serializamos, podríamos grabar IDs inconsistentes con el índice guardado.
        val snapshot = internalPlaylist.toList()
        if (snapshot.isEmpty()) return

        val queueIds = snapshot.map { it.id }
        musicPreferences.saveQueueIds(queueIds)
        musicPreferences.saveShuffleState(shuffleEnabled, originalPlaylist.map { it.id })
        if (currentIndex >= 0) {
            // Se guarda también el ID de la canción actual: al restaurar, la cola puede
            // encoger (canciones borradas de la BD) y el índice solo señalaría otro tema.
            musicPreferences.saveLastState(currentIndex, position, snapshot.getOrNull(currentIndex)?.id)
        }
    }

    /**
     * Guarda SOLO índice+posición+id actual, sin re-serializar los IDs de la cola. Para el
     * guardado al pausar, donde la cola no cambió y volver a escribir la lista completa es
     * trabajo inútil (con colas grandes, un map+escritura por cada pausa).
     */
    fun savePosition(currentIndex: Int, position: Long, currentSongId: String?) {
        if (currentIndex >= 0) {
            musicPreferences.saveLastState(currentIndex, position, currentSongId)
        }
    }

    /**
     * Restaura la sesión resolviendo los IDs guardados contra la BD. `null` si no hay sesión
     * guardada o ninguna de las canciones sigue en la BD.
     *
     * El índice se resuelve por el ID guardado de la canción actual (robusto ante canciones
     * borradas: la lista restaurada encoge y el índice numérico apuntaría a otro tema);
     * el índice numérico queda solo como fallback de sesiones viejas sin ID.
     */
    suspend fun restoreSessionFromDb(): RestoredSession? {
        val savedQueueIds = musicPreferences.loadQueueIds()
        if (savedQueueIds.isEmpty()) return null

        val byId = musicRepository.getSongsByIds(savedQueueIds).associateBy { it.id }
        // Reordenar según el orden guardado de la cola (getSongsByIds no garantiza orden).
        val restoredPlaylist = savedQueueIds.mapNotNull { byId[it] }
        if (restoredPlaylist.isEmpty()) return null

        // Aleatorio: el orden original se resuelve con el MISMO mapa (son las mismas canciones,
        // otro orden), así que no cuesta una segunda consulta. Si quedó vacío —sesión vieja sin
        // orden guardado— el aleatorio se restaura apagado antes que dejar el botón encendido
        // sin poder volver al orden real.
        val shuffleEnabled = musicPreferences.loadShuffleEnabled()
        val originalPlaylist = if (shuffleEnabled) {
            musicPreferences.loadOriginalQueueIds().mapNotNull { byId[it] }
        } else emptyList()

        val (savedIndex, savedPos, savedSongId) = musicPreferences.loadLastState()
        val indexById = savedSongId?.let { id -> restoredPlaylist.indexOfFirst { it.id == id } } ?: -1
        val targetIndex = when {
            indexById >= 0 -> indexById
            savedIndex in restoredPlaylist.indices -> savedIndex
            else -> 0
        }
        // Si la canción guardada ya no existe, retomarla desde el inicio de otra sería raro:
        // la posición solo vale para el tema que realmente estaba sonando.
        val position = if (indexById >= 0 || savedSongId == null) savedPos else 0L
        return RestoredSession(
            playlist = restoredPlaylist,
            index = targetIndex,
            position = position,
            shuffleEnabled = shuffleEnabled && originalPlaylist.isNotEmpty(),
            originalPlaylist = originalPlaylist
        )
    }

    fun clearSession() {
        musicPreferences.clearQueue()
    }
}
