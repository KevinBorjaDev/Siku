package com.qhana.siku.di

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.*
import com.qhana.siku.player.MusicController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.qhana.siku.data.remote.OneDriveApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.manager.MusicDownloader
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val TAG = "AppModule"

    // --- Caché de imágenes (Coil) ---
    // Las carátulas son bitmaps grandes y se re-piden constantemente al scrollear listas; un
    // cuarto del heap es el reparto habitual para una app cuya UI es mayormente imágenes.
    private const val IMAGE_MEMORY_CACHE_FRACTION = 0.25
    private const val IMAGE_DISK_CACHE_BYTES = 500L * 1024 * 1024
    private const val IMAGE_CACHE_DIR = "image_cache"

    // --- Caché de ExoPlayer (streaming, distinta de los archivos descargados) ---
    // Se dimensiona sobre el disco LIBRE (no el total) para no competir con la biblioteca
    // descargada, con suelo y techo: sin suelo, un disco lleno dejaría el streaming sin
    // buffer; sin techo, en un disco vacío se reservarían decenas de GB que nunca se usan.
    private const val MEDIA_CACHE_FREE_SPACE_FRACTION = 0.20
    private const val MEDIA_CACHE_MIN_BYTES = 100L * 1024 * 1024
    private const val MEDIA_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024
    /** Si StatFs falla (no debería), un tamaño fijo conservador. */
    private const val MEDIA_CACHE_FALLBACK_BYTES = 500L * 1024 * 1024
    private const val MEDIA_CACHE_DIR = "music_cache"

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @Named("images") okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // MemoryCache.Builder en Coil 3 recibe context para calcular el % del heap disponible
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_CACHE_FRACTION).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            // En Coil 3, el OkHttpClient se conecta como ComponentRegistry via OkHttpNetworkFetcherFactory
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    @Provides
    @Singleton
    @Named("images")
    fun provideImageOkHttpClient(): OkHttpClient {
        // Cliente dedicado para Coil (carátulas locales + fotos de Deezer). NO lleva el
        // AuthRefreshInterceptor de Graph (las imágenes son públicas o file://, adjuntar un
        // Bearer de Microsoft no aporta y ensucia peticiones a terceros) NI el pin HTTP/1.1
        // del cliente de descargas: se deja negociar HTTP/2, que para muchas imágenes chicas
        // multiplexadas contra Deezer rinde mejor que abrir una conexión por request.
        return OkHttpClient.Builder()
            .connectTimeout(AppConfig.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConfig.IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authRefreshInterceptor: com.qhana.siku.data.remote.AuthRefreshInterceptor
    ): OkHttpClient {
        // HTTP/1.1 forzado: este cliente sirve a OneDriveApi (delta scan + getItem para
        // resolver URLs) y a OneDriveRepository. Microsoft Graph/OneDrive negocian HTTP/2
        // con MAX_CONCURRENT_STREAMS muy bajo y ventanas de flujo pequeñas, lo que serializa
        // y ralentiza tanto el escaneo delta como la resolución de URLs. HTTP/1.1 abre una
        // conexión por request con su propia ventana → escaneo y descargas vuelven a ir rápido.
        return OkHttpClient.Builder()
            .connectTimeout(AppConfig.API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConfig.API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConfig.API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(authRefreshInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(): OkHttpClient {
        // El cliente de descargas NO lleva interceptor de auth: las URLs de OneDrive
        // ya vienen pre-firmadas y fallarían la validación si añadimos Authorization.
        //
        // HTTP/1.1 forzado: OneDrive negocia HTTP/2 con MAX_CONCURRENT_STREAMS bajo
        // (1-2 por conexión para descargas grandes). HTTP/2 multiplexaría TODAS las
        // descargas en una sola conexión TCP, serializándolas. Forzando HTTP/1.1 cada
        // descarga abre su propia conexión TCP con su ventana independiente, lo que
        // permite paralelismo real. Microsoft Graph recomienda HTTP/1.1 para file I/O.
        //
        // ConnectionPool dimensionado al paralelismo real: las descargas paralelas reusan
        // sockets keep-alive en lugar de renegociar TLS con OneDrive cada vez. Si el idle
        // pool es menor que el nº de workers, las conexiones sobrantes se cierran al terminar
        // cada archivo y el siguiente paga el handshake. Se toma de SyncManager para que no
        // puedan desincronizarse (antes el 32 estaba duplicado aquí).
        // Los timeouts de lectura/escritura miden SILENCIO DEL SOCKET (no duración total de
        // la descarga), lo mismo que el watchdog anti-stall de MusicDownloader: se derivan de
        // él para que un cuelgue lo diagnostique siempre el watchdog, que sabe clasificarlo.
        return OkHttpClient.Builder()
            .connectTimeout(AppConfig.API_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MusicDownloader.SOCKET_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(MusicDownloader.SOCKET_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(
                ConnectionPool(
                    SyncManager.MAX_PARALLEL_WIFI,
                    AppConfig.CONNECTION_KEEP_ALIVE_MINUTES,
                    TimeUnit.MINUTES
                )
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideOneDriveApi(okHttpClient: OkHttpClient): OneDriveApi {
        return Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/v1.0/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OneDriveApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, MEDIA_CACHE_DIR)
        val maxCacheSize = calculateDynamicCacheSize(context)
        val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSize)
        return SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
    }

    /** Caché de ExoPlayer para streaming: una fracción del disco libre, acotada. */
    private fun calculateDynamicCacheSize(context: Context): Long {
        return try {
            val cacheDir = context.cacheDir
            val stat = StatFs(cacheDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val targetSize = (availableBytes * MEDIA_CACHE_FREE_SPACE_FRACTION).toLong()
            targetSize.coerceIn(MEDIA_CACHE_MIN_BYTES, MEDIA_CACHE_MAX_BYTES)
        } catch (e: Exception) {
            MEDIA_CACHE_FALLBACK_BYTES
        }
    }

    @Provides
    @Singleton
    fun provideDataSourceFactory(
        @ApplicationContext context: Context,
        cache: Cache,
        oneDriveResolver: com.qhana.siku.data.remote.OneDriveResolvingDataSourceFactory
    ): DataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs((AppConfig.API_TIMEOUT_SECONDS * 1000).toInt())
            .setReadTimeoutMs((AppConfig.API_TIMEOUT_SECONDS * 1000).toInt())
        val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        // ResolvingDataSource intercepta URIs onedrive://<remoteId> y las convierte
        // a URLs firmadas reales en el momento de apertura. Ya no pre-fetcheamos URLs.
        val resolvingFactory = oneDriveResolver.wrap(defaultFactory)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Provides
    @Singleton
    fun provideMusicDatabase(@ApplicationContext context: Context): com.qhana.siku.data.local.MusicDatabase {
        return com.qhana.siku.data.local.MusicDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSongDao(db: com.qhana.siku.data.local.MusicDatabase): com.qhana.siku.data.local.SongDao {
        return db.songDao()
    }

    @Provides
    @Singleton
    fun providePlaylistDao(db: com.qhana.siku.data.local.MusicDatabase): com.qhana.siku.data.local.PlaylistDao {
        return db.playlistDao()
    }

    @Provides
    @Singleton
    fun provideArtistDao(db: com.qhana.siku.data.local.MusicDatabase): com.qhana.siku.data.local.ArtistDao {
        return db.artistDao()
    }

    @Provides
    @Singleton
    @Named("lyrics")
    fun provideLyricsOkHttpClient(): OkHttpClient {
        // LrcLib pide explícitamente que los clientes se identifiquen con un User-Agent
        // que incluya nombre + versión + URL/contacto. Esta app es de uso privado.
        val userAgentInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "SikuMusic/1.0")
                .build()
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideLrcLibApi(@Named("lyrics") lyricsClient: OkHttpClient): com.qhana.siku.data.remote.LrcLibApi {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(lyricsClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.qhana.siku.data.remote.LrcLibApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDeezerApi(@Named("lyrics") lyricsClient: OkHttpClient): com.qhana.siku.data.remote.DeezerApi {
        // Reusa el cliente de APIs públicas de terceros (sin auth de Graph, timeouts 10s).
        // NUNCA el cliente default: lleva el AuthRefreshInterceptor de OneDrive.
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(lyricsClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.qhana.siku.data.remote.DeezerApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        songDao: com.qhana.siku.data.local.SongDao,
        database: com.qhana.siku.data.local.MusicDatabase
    ): ISongRepository {
        return SongRepository(context, songDao, database)
    }

    @Provides
    @Singleton
    fun providePlaylistRepository(
        playlistDao: com.qhana.siku.data.local.PlaylistDao
    ): IPlaylistRepository {
        return PlaylistRepository(playlistDao)
    }

    @Provides
    @Singleton
    fun provideLocalMetadataRepository(
        songDao: com.qhana.siku.data.local.SongDao
    ): ILocalMetadataRepository {
        return LocalMetadataRepository(songDao)
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        songRepository: ISongRepository,
        playlistRepository: IPlaylistRepository,
        localMetadataRepository: ILocalMetadataRepository,
        browseRepository: BrowseRepository
    ): IMusicRepository {
        return MusicRepository(songRepository, playlistRepository, localMetadataRepository, browseRepository)
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(
        lrcLibApi: com.qhana.siku.data.remote.LrcLibApi
    ): ILyricsRepository {
        return LyricsRepository(lrcLibApi)
    }

    @Provides
    @Singleton
    fun provideMusicPreferences(@ApplicationContext context: Context): MusicPreferences {
        return MusicPreferences(context)
    }

    @Provides
    @Singleton
    fun provideArtworkRepository(
        @ApplicationContext context: Context,
        musicRepository: IMusicRepository,
        musicPreferences: MusicPreferences,
        appLogger: com.qhana.siku.data.util.AppLogger
    ): ArtworkRepository {
        return ArtworkRepository(context, musicRepository, musicPreferences, appLogger)
    }
}