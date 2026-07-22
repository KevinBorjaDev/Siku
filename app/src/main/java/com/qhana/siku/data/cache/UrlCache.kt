package com.qhana.siku.data.cache

import android.util.Log
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
    }

    private data class CachedUrl(
        val url: String,
        val expiresAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAt
    }

    private val cache = ConcurrentHashMap<String, CachedUrl>()
    @Volatile private var lastCleanupTime = System.currentTimeMillis()

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
        val cached = cache[remoteId]
        if (cached != null && !cached.isExpired()) {
            return cached.url
        }

        // Cache miss o expirado - obtener URL fresca
        val freshUrl = fetcher()

        if (freshUrl != null) {
            val expiresAt = System.currentTimeMillis() + ttlMs
            cache[remoteId] = CachedUrl(freshUrl, expiresAt)
        }

        return freshUrl
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
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) return

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

        if (removed > 0) {
            Log.d(TAG, "Limpieza automática: $removed entradas expiradas eliminadas")
        }
    }

}
