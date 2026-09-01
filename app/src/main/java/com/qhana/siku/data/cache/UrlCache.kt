package com.qhana.siku.data.cache

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache de URLs de OneDrive con TTL.
 * Las URLs de OneDrive expiran después de varias horas, pero las cacheamos
 * por un tiempo menor (5 minutos por defecto) para evitar llamadas repetidas
 * mientras mantenemos frescura razonable.
 */
@Singleton
class UrlCache @Inject constructor() {

    companion object {
        private const val TAG = "UrlCache"

        // TTL por defecto: 5 minutos
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        // Intervalo de limpieza automática: cada 10 minutos
        private const val CLEANUP_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * Tope de entradas vivas. La limpieza por tiempo solo corre si alguien pide una URL, así
         * que en una biblioteca grande un sync masivo dejaba una entrada por canción sin techo
         * alguno. Pasado el tope se descartan las más próximas a expirar (las que menos vida
         * útil les queda), que es lo más barato de volver a pedir.
         */
        private const val MAX_ENTRIES = 512
    }

    /**
     * @property expiresAt en el reloj MONÓTONO (`SystemClock.elapsedRealtime`), no en wall-clock:
     *   la caché vive y muere con el proceso, así que no hace falta una época — y con
     *   `currentTimeMillis` una corrección de hora hacia adelante caducaba de golpe TODAS las
     *   entradas (32 conexiones saliendo a pedir URLs a la vez contra un proveedor que cobra por
     *   petición), y hacia atrás servía URLs ya muertas hasta que el reloj se pusiera al día. Mismo
     *   criterio que [com.qhana.siku.player.manager.ListeningClock].
     */
    private data class CachedUrl(
        val url: String,
        val expiresAt: Long
    ) {
        fun isExpired(): Boolean = SystemClock.elapsedRealtime() >= expiresAt
    }

    private val cache = ConcurrentHashMap<String, CachedUrl>()

    /**
     * Un cerrojo por clave para que N corrutinas que piden la MISMA URL a la vez hagan UNA
     * petición, no N. Con el paralelismo de descargas (hasta 32 conexiones) y un fallo de URL
     * expirada, todas las que compartían id salían a la red a la vez contra un proveedor que
     * ya cobra por peticiones. Es por clave, no global: dos canciones distintas siguen
     * resolviéndose en paralelo.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var lastCleanupTime = SystemClock.elapsedRealtime()

    /**
     * Obtiene una URL del cache o la genera usando el fetcher proporcionado.
     *
     * @param remoteId ID remoto del archivo (clave del cache)
     * @param ttlMs Tiempo de vida del cache en milisegundos
     * @param fetcher Función suspendida que obtiene la URL si no está en cache
     * @return URL cacheada o recién obtenida, o null si falla
     */
    suspend fun getOrFetch(
        remoteId: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        fetcher: suspend () -> String?
    ): String? {
        // Limpieza periódica de entradas expiradas
        cleanupIfNeeded()

        // Verificar cache
        cache[remoteId]?.takeIf { !it.isExpired() }?.let { return it.url }

        val lock = locks.computeIfAbsent(remoteId) { Mutex() }
        try {
            return lock.withLock {
                // Segunda comprobación DENTRO del cerrojo: mientras se esperaba, el primero en
                // entrar pudo dejar la URL ya resuelta. Sin esto el cerrojo serializaría las
                // peticiones en vez de evitarlas.
                cache[remoteId]?.takeIf { !it.isExpired() }?.let { return@withLock it.url }

                val freshUrl = fetcher()
                if (freshUrl != null) {
                    cache[remoteId] = CachedUrl(freshUrl, SystemClock.elapsedRealtime() + ttlMs)
                }
                freshUrl
            }
        } finally {
            // Se retira solo si nadie más lo tiene ni lo espera; si hay carrera y queda
            // huérfano, lo barre `cleanupIfNeeded` (y el peor caso es una petición de más,
            // que es exactamente el comportamiento anterior).
            if (!lock.isLocked) locks.remove(remoteId, lock)
        }
    }

    /**
     * Invalida una entrada específica del cache.
     * Útil cuando sabemos que una URL ya no es válida (error de reproducción).
     *
     * @param remoteId ID remoto del archivo a invalidar
     */
    fun invalidate(remoteId: String) {
        cache.remove(remoteId)
        Log.d(TAG, "Cache invalidado para remoteId: $remoteId")
    }

    /**
     * Limpia entradas expiradas del cache si ha pasado suficiente tiempo
     * desde la última limpieza.
     */
    private fun cleanupIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        // También se limpia al pasarse de tamaño, no solo por tiempo: si no, entre dos
        // limpiezas la caché podía crecer sin límite.
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS && cache.size <= MAX_ENTRIES) return

        lastCleanupTime = now
        var removed = 0

        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
                removed++
            }
        }

        // Si tras purgar lo expirado se sigue por encima del tope, caen las que antes expiran.
        val excess = cache.size - MAX_ENTRIES
        if (excess > 0) {
            cache.entries
                .sortedBy { it.value.expiresAt }
                .take(excess)
                .forEach { cache.remove(it.key, it.value) }
            removed += excess
        }

        // Cerrojos que quedaron sin dueño por una carrera en el `finally` de getOrFetch.
        locks.entries.removeIf { !it.value.isLocked }

        if (removed > 0) {
            Log.d(TAG, "Limpieza automática: $removed entradas eliminadas (quedan ${cache.size})")
        }
    }

}
