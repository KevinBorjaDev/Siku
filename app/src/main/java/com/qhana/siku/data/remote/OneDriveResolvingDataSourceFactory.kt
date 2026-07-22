package com.qhana.siku.data.remote

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.qhana.siku.data.cache.UrlCache
import com.qhana.siku.data.repository.OneDriveRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper para un upstream `DataSource.Factory` que resuelve URIs del esquema
 * `onedrive://<remoteId>` a URLs firmadas reales en el momento de apertura del stream.
 *
 * Esto elimina la necesidad de pre-fetchar URLs en la capa de dominio: ExoPlayer
 * pide la URL solo cuando realmente va a abrir el media source, con la URL vigente
 * (no una cacheada que pudo haber expirado).
 *
 * Las URIs locales (`file://`, `content://`) o HTTPS directas pasan sin tocar.
 *
 * La resolución usa `runBlocking` porque `ResolvingDataSource.Resolver` es síncrono.
 * Esto es seguro porque el resolver corre en el thread de carga de ExoPlayer, no en Main.
 */
@Singleton
class OneDriveResolvingDataSourceFactory @Inject constructor(
    private val oneDriveRepository: OneDriveRepository,
    private val urlCache: UrlCache
) {
    companion object {
        const val SCHEME = "onedrive"
        private const val TAG = "OneDriveResolver"

        /**
         * Construye `onedrive://<remoteId>` para usar como URI de un `MediaItem`.
         */
        fun buildUri(remoteId: String): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(remoteId)
            .build()
    }

    fun wrap(upstream: DataSource.Factory): DataSource.Factory {
        return ResolvingDataSource.Factory(upstream, object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                if (dataSpec.uri.scheme != SCHEME) return dataSpec
                val remoteId = dataSpec.uri.host
                    ?: throw IOException("URI onedrive sin remoteId: ${dataSpec.uri}")

                Log.d(TAG, "Resolviendo URL para remoteId=$remoteId")
                // Cacheamos la URL resuelta: ExoPlayer reabre el data source en cada seek y
                // reconexión, y sin caché cada uno pegaba un getItem a Graph. El TTL corto del
                // urlCache mantiene frescura sin repetir round-trips dentro de la misma sesión.
                val resolved = try {
                    runBlocking { urlCache.getOrFetch(remoteId) { oneDriveRepository.getDownloadUrl(remoteId) } }
                } catch (e: Exception) {
                    throw IOException("Error resolviendo URL OneDrive: ${e.message}", e)
                } ?: throw IOException("OneDrive devolvió URL nula para $remoteId")

                return dataSpec.withUri(Uri.parse(resolved))
            }
        })
    }
}
