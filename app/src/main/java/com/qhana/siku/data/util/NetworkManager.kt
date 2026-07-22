package com.qhana.siku.data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centraliza las verificaciones de estado de red.
 * Cachea el estado por un breve período para evitar llamadas repetidas al sistema.
 */
@Singleton
class NetworkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // Cache del estado de red (válido por 1 segundo)
    private var cachedNetworkState: NetworkState? = null
    private var cacheTimestamp: Long = 0L
    private val cacheDurationMs = 1000L

    private data class NetworkState(
        val isAvailable: Boolean,
        val isWifi: Boolean
    )

    /**
     * Obtiene el estado actual de la red, usando cache si está disponible.
     */
    private fun getNetworkState(): NetworkState {
        val now = System.currentTimeMillis()
        val cached = cachedNetworkState

        if (cached != null && (now - cacheTimestamp) < cacheDurationMs) {
            return cached
        }

        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val state = if (capabilities == null) {
            NetworkState(isAvailable = false, isWifi = false)
        } else {
            NetworkState(
                isAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isWifi = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
        }

        cachedNetworkState = state
        cacheTimestamp = now
        return state
    }

    /**
     * Verifica si hay conexión a internet disponible.
     */
    fun isAvailable(): Boolean = getNetworkState().isAvailable

    /**
     * Verifica si está conectado a WiFi (red sin medición de datos).
     */
    fun isWifi(): Boolean = getNetworkState().isWifi

    /**
     * Estimación del ancho de banda de bajada del enlace activo en kbps, según el sistema
     * (en WiFi deriva del link speed negociado con el AP). 0 si no hay red o el sistema
     * no reporta nada. Es una estimación optimista del ENLACE, no del ISP — sirve como
     * techo para dimensionar paralelismo, no como medición real.
     */
    fun downlinkKbps(): Int {
        val network = connectivityManager.activeNetwork ?: return 0
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return 0
        return capabilities.linkDownstreamBandwidthKbps
    }
}
