package com.qhana.siku.data.source

import android.content.Context
import android.util.Log
import com.qhana.siku.R
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.cache.UrlCache
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.coordinator.ArtworkHealingManager
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.normalizeRelativePath
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.repository.OneDriveRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import retrofit2.HttpException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de [MusicSource] para OneDrive. Encapsula lo que antes vivía suelto:
 * - El **descubrimiento** (delta de Microsoft Graph) — antes `SyncManager.syncWithDelta`.
 * - La **resolución de URL** firmada (con caché TTL) y la **extracción de metadata** — antes
 *   consumidas directo desde `OneDriveRepository`/`UrlCache` por el player.
 *
 * `SyncManager` ahora es un orquestador genérico que llama a [discover] por cada fuente activa.
 */
@Singleton
class OneDriveMusicSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager,
    private val musicRepository: IMusicRepository,
    private val musicPreferences: MusicPreferences,
    private val artworkHealingManager: ArtworkHealingManager,
    private val oneDriveRepository: OneDriveRepository,
    private val urlCache: UrlCache
) : MusicSource {

    override val type: SourceType = SourceType.ONEDRIVE

    /** OneDrive está configurado si hay una cuenta de Microsoft conectada. */
    override suspend fun isConfigured(): Boolean = authManager.hasAccount()

    override suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult {
        // Token: si la auth falla, señalamos con SourceAuthException para que el orquestador
        // devuelva Failed(isAuthError=true) — antes esto lo hacía SyncManager.startSync.
        val token = when (val result = authManager.getAccessToken().firstOrNull()) {
            is AuthResult.Success -> result.token
            is AuthResult.Error -> throw SourceAuthException("Auth error: ${result.message}")
            else -> throw SourceAuthException("Authentication failed")
        }

        if (force) {
            Log.d(TAG, "Force refresh: clearing delta token for full rescan")
            musicPreferences.clearDeltaToken()
            // Pull-to-refresh recupera también carátulas huérfanas si las hay.
            try { artworkHealingManager.heal() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Artwork healing skipped: ${e.message}")
            }
        }

        return syncWithDelta(token, ctx)
    }

    override suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean): String? {
        // Sin remoteId no hay handle de Graph: devolvemos lo que haya en path (mejor que nada).
        val remoteId = song.remoteId ?: return song.path.takeIf { it.isNotBlank() }
        if (forceRefresh) urlCache.invalidate(remoteId)
        return urlCache.getOrFetch(remoteId) { oneDriveRepository.getDownloadUrl(remoteId) }
    }

    override suspend fun extractMetadata(song: Song): Song = oneDriveRepository.extractMetadata(song)

    /**
     * Incremental delta sync: detecta altas, modificaciones y bajas usando el delta token.
     * Devuelve (added, deleted). Movido tal cual desde SyncManager (Paso 2 de la abstracción).
     */
    private suspend fun syncWithDelta(token: String, ctx: DiscoverContext): DiscoverResult {
        // $top=999 reduce roundtrips en bibliotecas grandes (default ~200 items/página).
        // parentReference: carpeta de cada item, para la ruta relativa de duplicados (v23).
        val initialUrl = "https://graph.microsoft.com/v1.0/me/drive/root:/Music:/delta?select=id,name,file,deleted,size,parentReference,@microsoft.graph.downloadUrl&\$top=999"
        val savedToken = musicPreferences.loadDeltaToken()
        val isFullScan = savedToken == null
        var nextLink = savedToken ?: initialUrl
        var changesCount = 0
        var deletedCount = 0
        var hasMore = true
        // During a full scan, collect all remote IDs for reconciliation
        val allRemoteIds = if (isFullScan) HashSet<String>(512) else null

        Log.d(TAG, "syncWithDelta: ${if (isFullScan) "FULL scan" else "incremental scan"}")

        // Política de duplicados PREFER_LOCAL: las rutas ya presentes en la fuente local se
        // saltan del upsert (evita el ciclo insertar→fusionar en cada scan). El dedupe pass
        // del orquestador se encarga de las que ya estaban en la BD (con re-apunte).
        val skipPaths: Set<String> =
            if (musicPreferences.loadDuplicatePolicy() == DuplicatePolicy.PREFER_LOCAL)
                musicRepository.getRelativePathsOfOtherSources(SourceType.ONEDRIVE)
            else emptySet()

        while (hasMore && !ctx.isStopped()) {
            val response = try { oneDriveApi.getDelta(token, nextLink) } catch (e: HttpException) {
                if (e.code() == 410) {
                    musicPreferences.clearDeltaToken()
                    oneDriveApi.getDelta(token, initialUrl)
                } else throw e
            }
            val upsert = ArrayList<Song>()
            val delete = ArrayList<String>()
            Log.d(TAG, "Delta page: ${response.value.size} items, hasNextLink=${response.nextLink != null}, hasDeltaLink=${response.deltaLink != null}")
            for (item in response.value) {
                // Fase 0: el id es namespaced y portable (`onedrive:<item.id>`). El `remoteId`
                // sigue siendo el item.id CRUDO que usan las llamadas a Graph.
                val id = SourceType.ONEDRIVE.buildId(item.id)
                if (item.deleted != null) {
                    delete.add(id)
                } else if (item.file != null && isAudioFile(item.name ?: "")) {
                    allRemoteIds?.add(id)
                    val relPath = relativePathOf(item)
                    // PREFER_LOCAL: existe copia local de esta ruta → no se importa. Va
                    // DESPUÉS de allRemoteIds.add: si una copia vieja sigue en la BD, la
                    // reconciliación no debe borrarla a lo bruto (el dedupe pass la retira
                    // re-apuntando playlists/historial).
                    if (relPath != null && relPath in skipPaths) continue
                    upsert.add(Song(id = id, title = item.name ?: "Unknown", artist = AppConfig.UNKNOWN_ARTIST, album = AppConfig.UNKNOWN_ALBUM, duration = 0, path = item.downloadUrl ?: "", remoteId = item.id, sourceType = SourceType.ONEDRIVE, dateAdded = System.currentTimeMillis() / 1000, size = item.size ?: 0L, relativePath = relPath))
                }
            }
            if (upsert.isNotEmpty()) {
                val result = musicRepository.upsertSongs(upsert)
                if (result is AppResult.Success) {
                    changesCount += result.data
                } else if (result is AppResult.Error) {
                    Log.e(TAG, "Error upserting songs: ${result.error.message}")
                }
                Log.d(TAG, "Upsert: ${upsert.size} candidates, $changesCount actually new")
            }
            if (delete.isNotEmpty()) {
                Log.d(TAG, "Delta: deleting ${delete.size} songs, ids=$delete")
                musicRepository.deleteSongs(delete)
                deletedCount += delete.size
            }
            val totalChanges = changesCount + deletedCount
            ctx.reportScanning(
                totalChanges,
                if (totalChanges > 0) {
                    context.resources.getQuantityString(R.plurals.scan_changes, totalChanges, totalChanges)
                } else {
                    context.getString(R.string.notif_checking_changes)
                }
            )
            nextLink = response.nextLink ?: response.deltaLink ?: ""
            if (response.deltaLink != null) { musicPreferences.saveDeltaToken(response.deltaLink); hasMore = false }
            if (nextLink.isEmpty()) hasMore = false
        }

        // Full scan reconciliation: borrar las canciones de ESTA fuente que ya no existen
        // en OneDrive. Por sourceType, NUNCA getAllSongIds(): con la fuente local
        // configurada, comparar TODOS los ids contra los remotos marcaba las canciones
        // locales como huérfanas y las borraba en cada pull-to-refresh.
        if (isFullScan && allRemoteIds != null && allRemoteIds.isNotEmpty() && !ctx.isStopped()) {
            val localIds = musicRepository.getSongIdsBySourceType(SourceType.ONEDRIVE)
            val orphanIds = localIds.filter { it !in allRemoteIds }
            if (orphanIds.isNotEmpty()) {
                Log.d(TAG, "Reconciliation: removing ${orphanIds.size} songs no longer in OneDrive")
                musicRepository.deleteSongs(orphanIds)
                deletedCount += orphanIds.size
            }
        }

        Log.d(TAG, "syncWithDelta complete: added=$changesCount, deleted=$deletedCount (fullScan=$isFullScan)")
        return DiscoverResult(changesCount, deletedCount)
    }

    /**
     * Ruta relativa a la raíz del scan (/Music), normalizada (ver [normalizeRelativePath]).
     * parentReference.path llega como "/drive/root:/Music/Sub"; se recorta hasta "root:" y
     * se descuenta el segmento "music" de la raíz. null si el item no trae carpeta.
     */
    private fun relativePathOf(item: com.qhana.siku.data.remote.OneDriveItem): String? {
        val name = item.name ?: return null
        val parent = item.parentReference?.path ?: return null
        val fromRoot = parent.substringAfter("root:", "")
        val normalized = normalizeRelativePath("$fromRoot/$name")
        return normalized.removePrefix("music/").takeIf { it.isNotBlank() }
    }

    private fun isAudioFile(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac")
    }

    private companion object {
        private const val TAG = "OneDriveMusicSource"
    }
}
