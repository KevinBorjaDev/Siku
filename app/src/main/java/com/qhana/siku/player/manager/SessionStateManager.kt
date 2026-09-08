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

/**
 * Snapshot CONGELADO de un contexto reanudable (una tarjeta de "Seguir escuchando"): dónde se
 * dejó ese álbum/artista/lista/etc. la última vez que se dejó de reproducir.
 *
 * Es la misma información que la sesión global, pero por contexto: se guarda en el instante en que
 * la reproducción cambia de contexto (o para), y se lee al tocar la tarjeta. La cola se persiste
 * como ids namespaced; [originalQueueIds] solo cuando había aleatorio (si no, el orden natural se
 * re-deriva de la consulta del contexto), igual que hace la sesión global.
 */
data class ContextResumeSlot(
    val queueIds: List<String>,
    val originalQueueIds: List<String>,
    val shuffleEnabled: Boolean,
    val index: Int,
    val position: Long,
    val songId: String?
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
        // Una cola vacía NO se persiste, pero tampoco BORRA la sesión guardada: esto es un
        // "guardar lo que hay", y en el arranque se llama con la lista todavía sin restaurar
        // (`restoreSessionFromDb` es asíncrono), así que borrar aquí tiraría la sesión que se
        // estaba a punto de recuperar. Quien VACÍA la cola de verdad lo dice con [clearSession]
        // (ver `MusicController.stop`, al que llega tanto la purga total como quitar el último
        // item de la cola).
        if (snapshot.isEmpty()) return

        // UNA escritura y no tres encadenadas: las tres partes describen el mismo instante, y cada
        // `update` cuesta una reescritura del archivo con su `fsync` (ver `MusicPreferences.Store`).
        // Se guarda también el ID de la canción actual: al restaurar, la cola puede encoger
        // (canciones borradas de la BD) y el índice solo señalaría otro tema.
        musicPreferences.saveSession(
            queueIds = snapshot.map { it.id },
            shuffleEnabled = shuffleEnabled,
            originalQueueIds = originalPlaylist.map { it.id },
            currentIndex = currentIndex,
            position = position,
            currentSongId = snapshot.getOrNull(currentIndex)?.id
        )
    }

    /**
     * Guarda SOLO índice+posición+id actual, sin re-serializar los IDs de la cola. Para el
     * guardado al pausar, donde la cola no cambió y volver a escribir la lista completa es
     * trabajo inútil (con colas grandes, un map+escritura por cada pausa).
     *
     * **Ese ahorro no fue real hasta el 30 ago 2026**, y merece quedar escrito porque el KDoc
     * prometía algo que la capa de abajo anulaba: la sesión vivía en el mismo archivo de DataStore
     * que las 82 claves de configuración, y DataStore reescribe el archivo ENTERO en cada
     * escritura, así que la cola se volvía a serializar igual. Lo único que se ahorraba era el
     * `map` en memoria. Ahora la sesión tiene archivo propio (ver `MusicPreferences.Store`) y esto
     * escribe de verdad tres valores.
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
        val (savedIndex, savedPos, savedSongId) = musicPreferences.loadLastState()
        return resolveSession(
            savedQueueIds = savedQueueIds,
            shuffleEnabled = musicPreferences.loadShuffleEnabled(),
            savedOriginalIds = musicPreferences.loadOriginalQueueIds(),
            savedIndex = savedIndex,
            savedPos = savedPos,
            savedSongId = savedSongId
        )
    }

    // ---- Reanudación por contexto ("Seguir escuchando") ----

    /**
     * Congela el estado vivo del contexto [key] para que su tarjeta lo reanude tal cual. No-op si
     * [key] está en blanco o la cola está vacía (no hay nada que reanudar).
     *
     * Se llama justo cuando la reproducción va a cambiar de contexto (o a pararse): en ese instante
     * [internalPlaylist]/[currentIndex]/[position] son todavía los del contexto SALIENTE.
     */
    fun snapshotContext(
        key: String?,
        internalPlaylist: List<Song>,
        currentIndex: Int,
        position: Long,
        shuffleEnabled: Boolean,
        originalPlaylist: List<Song>
    ) {
        if (key.isNullOrBlank()) return
        val snapshot = internalPlaylist.toList()
        if (snapshot.isEmpty()) return
        musicPreferences.saveContextResume(
            key,
            ContextResumeSlot(
                queueIds = snapshot.map { it.id },
                // Igual que la sesión global: el orden natural se re-deriva al reanudar, así que
                // solo se guarda cuando NO coincide con la cola (aleatorio activo).
                originalQueueIds = if (shuffleEnabled) originalPlaylist.map { it.id } else emptyList(),
                shuffleEnabled = shuffleEnabled,
                index = currentIndex,
                position = position,
                songId = snapshot.getOrNull(currentIndex)?.id
            )
        )
    }

    /**
     * Reanuda el contexto [key] resolviendo su slot guardado contra la BD. `null` si no hay slot
     * o ninguna de sus canciones sigue en la BD (el caller reproduce el contexto desde el inicio).
     */
    suspend fun restoreContext(key: String): RestoredSession? {
        val slot = musicPreferences.loadContextResume(key) ?: return null
        if (slot.queueIds.isEmpty()) return null
        return resolveSession(
            savedQueueIds = slot.queueIds,
            shuffleEnabled = slot.shuffleEnabled,
            savedOriginalIds = slot.originalQueueIds,
            savedIndex = slot.index,
            savedPos = slot.position,
            savedSongId = slot.songId
        )
    }

    /** Borra los slots de contextos que ya no están en "Seguir escuchando" (quedaron huérfanos). */
    fun pruneContexts(keepKeys: Set<String>) = musicPreferences.pruneContextResume(keepKeys)

    /**
     * Resuelve un conjunto de ids guardados (cola + orden original) contra la BD.
     *
     * El índice se resuelve por el ID guardado de la canción actual (robusto ante canciones
     * borradas: la lista restaurada encoge y el índice numérico apuntaría a otro tema);
     * el índice numérico queda solo como fallback de sesiones sin ID.
     */
    private suspend fun resolveSession(
        savedQueueIds: List<String>,
        shuffleEnabled: Boolean,
        savedOriginalIds: List<String>,
        savedIndex: Int,
        savedPos: Long,
        savedSongId: String?
    ): RestoredSession? {
        val byId = musicRepository.getSongsByIds(savedQueueIds).associateBy { it.id }
        // Reordenar según el orden guardado de la cola (getSongsByIds no garantiza orden).
        val restoredPlaylist = savedQueueIds.mapNotNull { byId[it] }
        if (restoredPlaylist.isEmpty()) return null

        // Aleatorio: el orden original se resuelve con el MISMO mapa (son las mismas canciones,
        // otro orden), así que no cuesta una segunda consulta. Si quedó vacío —sin orden
        // guardado— el aleatorio se restaura apagado antes que dejar el botón encendido sin
        // poder volver al orden real.
        val originalPlaylist = if (shuffleEnabled) {
            savedOriginalIds.mapNotNull { byId[it] }
        } else emptyList()

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
