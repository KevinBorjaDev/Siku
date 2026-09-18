package com.qhana.siku.data.source

import android.content.Context
import android.util.Log
import com.qhana.siku.R
import com.qhana.siku.data.auth.AuthErrorReason
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
    private val urlCache: UrlCache,
    private val rangeFetcher: com.qhana.siku.data.util.tags.HttpRangeFetcher,
    private val tagReaders: com.qhana.siku.data.util.tags.PartialTagReaders
) : MusicSource {

    override val type: SourceType = SourceType.ONEDRIVE

    /** OneDrive está configurado si hay una cuenta de Microsoft conectada. */
    override suspend fun isConfigured(): Boolean = authManager.hasAccount()

    /** La misma sesión, observable. Su dueño es `AuthManager`; aquí solo se reexpone. */
    override val isConfiguredFlow: kotlinx.coroutines.flow.Flow<Boolean> = authManager.hasSession

    override suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult {
        // Token: si la auth falla, señalamos con SourceAuthException para que el orquestador
        // devuelva Failed(isAuthError=true) — antes esto lo hacía SyncManager.startSync.
        val token = when (val result = authManager.getAccessToken().firstOrNull()) {
            is AuthResult.Success -> result.token
            // El MOTIVO viaja como flag y no como texto: aguas arriba hay que decidir qué acción
            // ofrecer, y eso no se puede leer de un mensaje.
            is AuthResult.Error -> throw SourceAuthException(
                "Auth error: ${result.reason}",
                requiresInteraction = result.reason == AuthErrorReason.INTERACTION_REQUIRED
            )
            else -> throw SourceAuthException("Authentication failed")
        }

        if (force) {
            Log.d(TAG, "Force refresh: clearing delta token for full rescan")
            musicPreferences.clearDeltaToken()
            // Pull-to-refresh recupera también carátulas huérfanas si las hay. `force`: el barrido
            // del arranque va gateado por el conteo del directorio, y este gesto es precisamente la
            // vía para pedirlo entero (ver ArtworkHealingManager.heal).
            try { artworkHealingManager.heal(force = true) } catch (e: CancellationException) {
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
     * Lee los tags pidiendo solo la cabecera del archivo con `Range` (ver [PartialTagReader]).
     * Devuelve `null` —y el orquestador sigue como siempre— si el formato no tiene lector, si no
     * se pudo resolver la URL o si la respuesta no trajo tags reconocibles.
     */
    override suspend fun fetchLightMetadata(song: Song): com.qhana.siku.data.source.LightMetadata? {
        val extension = (song.relativePath ?: song.title).substringAfterLast('.', "")
        val reader = tagReaders.forExtension(extension) ?: return null
        val url = resolveDownloadUrl(song) ?: return null

        val fragment = rangeFetcher.fetch(url, 0L, reader.preferredFragmentBytes) ?: return null
        val tags = reader.read(fragment) ?: return null
        if (!tags.hasText && tags.pictureData == null && tags.pictureRange == null) return null

        // La portada que no cabía en el fragmento se deja como FUNCIÓN: el orquestador la invoca
        // solo si ese álbum aún no tiene imagen, así una portada cuesta una petición por álbum y
        // no una por canción. La URL se resuelve de nuevo dentro por si expiró (viven ~1h).
        val pictureRange = tags.pictureRange
        val fetchArtwork: (suspend () -> ByteArray?)? = if (pictureRange == null) null else {
            {
                val fresh = resolveDownloadUrl(song, forceRefresh = true)
                if (fresh == null) null
                else rangeFetcher.fetch(
                    fresh,
                    pictureRange.first,
                    (pictureRange.last - pictureRange.first + 1).toInt()
                )?.let {
                    // El rango trae el BLOQUE del contenedor, no la imagen: lo desenvuelve el
                    // mismo lector que lo produjo. Sin esto se persistía un "jpg" con la cabecera
                    // del bloque delante —indecodificable— y encima se repartía al álbum entero.
                    reader.decodePictureRange(it)
                }
            }
        }

        return com.qhana.siku.data.source.LightMetadata(
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtists = tags.albumArtists,
            artists = tags.artists,
            genre = tags.genre,
            trackNumber = tags.trackNumber,
            year = tags.year,
            durationMs = tags.durationMs,
            artwork = tags.pictureData,
            fetchArtwork = fetchArtwork
        )
    }

    /**
     * Incremental delta sync: detecta altas, modificaciones y bajas usando el delta token.
     * Devuelve (added, deleted). Movido tal cual desde SyncManager (Paso 2 de la abstracción).
     */
    private suspend fun syncWithDelta(token: String, ctx: DiscoverContext): DiscoverResult {
        // $top=999 reduce roundtrips en bibliotecas grandes (default ~200 items/página).
        // parentReference: carpeta de cada item, para la ruta relativa de duplicados (v23).
        val folder = musicPreferences.loadOneDriveFolderPath()
        val initialUrl = deltaUrlFor(folder)
        val savedToken = musicPreferences.loadDeltaToken()
        // `var` y no `val`: un 410 a mitad del recorrido reinicia la enumeración desde cero y eso
        // CONVIERTE el scan incremental en completo. Sin actualizarlo, la re-enumeración corría sin
        // reconciliación y los archivos borrados en OneDrive durante la ventana en que el token
        // caducó no llegaban ni como `deleted` (el delta se reinició) ni como huérfanos.
        var isFullScan = savedToken == null
        var nextLink = savedToken ?: initialUrl
        var changesCount = 0
        var deletedCount = 0
        var hasMore = true
        // Ids remotos vistos, para la reconciliación del scan completo. Se acumulan SIEMPRE: si un
        // 410 promueve el scan a completo a mitad de camino, ya se han visto páginas que no se
        // volverían a recorrer, y reconciliar con una foto incompleta borraría canciones vivas.
        // Por eso `enumerationComplete` (abajo) es lo que decide si la foto sirve.
        val allRemoteIds = HashSet<String>(512)
        // La enumeración llegó hasta el deltaLink final sin cortes: solo entonces "no lo vi" es
        // "ya no está". Un `isStopped`, una excepción o un 410 a mitad la invalidan.
        var enumerationComplete = false

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
                when (e.code()) {
                    410 -> {
                        musicPreferences.clearDeltaToken()
                        // El delta caducó: la enumeración vuelve a empezar de cero, así que este
                        // scan PASA a ser completo y con ello recupera la reconciliación. Lo visto
                        // hasta aquí se descarta: la foto válida es la del recorrido nuevo, no una
                        // mezcla de los dos.
                        isFullScan = true
                        allRemoteIds.clear()
                        oneDriveApi.getDelta(token, initialUrl)
                    }
                    // La carpeta configurada no existe (o la renombraron). Sin esto el usuario
                    // veía un "HTTP 404" sin pista de que el problema es la carpeta elegida.
                    404 -> throw SourceFolderMissingException(folder)
                    else -> throw e
                }
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
                    allRemoteIds.add(id)
                    val relPath = relativePathOf(item, folder)
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
            // Llegar al deltaLink es la ÚNICA señal de que se recorrió todo: es el final que
            // declara Graph. Salir por `nextLink` vacío o por `isStopped` deja la foto a medias.
            if (response.deltaLink != null) {
                musicPreferences.saveDeltaToken(response.deltaLink)
                hasMore = false
                enumerationComplete = true
            }
            if (nextLink.isEmpty()) hasMore = false
        }

        // Full scan reconciliation: borrar las canciones de ESTA fuente que ya no existen
        // en OneDrive. Por sourceType, NUNCA getAllSongIds(): con la fuente local
        // configurada, comparar TODOS los ids contra los remotos marcaba las canciones
        // locales como huérfanas y las borraba en cada pull-to-refresh.
        //
        // La condición es que la ENUMERACIÓN COMPLETARA, no que haya visto algo: "no pude listar"
        // y "no hay nada" son cosas distintas, y exigir `isNotEmpty()` confundía las dos — quien
        // vaciaba su carpeta de OneDrive se quedaba con toda la biblioteca de nube como fantasma,
        // sin más salida que cerrar sesión. Mismo criterio que `Listing.covers` en la fuente local.
        if (isFullScan && enumerationComplete && !ctx.isStopped()) {
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
     * URL del delta para la carpeta elegida. Vacía = raíz del drive, que tiene otra sintaxis en
     * Graph (`/root/delta`, sin los `:` del path). La ruta se codifica preservando las barras:
     * un `Uri.encode` a secas convertiría `/` en `%2F` y Graph dejaría de ver la jerarquía.
     */
    private fun deltaUrlFor(folder: String): String {
        val clean = folder.trim().trim('/')
        val root = if (clean.isEmpty()) "root" else "root:/${android.net.Uri.encode(clean, "/")}:"
        return "$GRAPH_DRIVE/$root/delta?select=id,name,file,deleted,size,parentReference,@microsoft.graph.downloadUrl&\$top=999"
    }

    /**
     * Ruta relativa a la raíz del scan, normalizada (ver [normalizeRelativePath]).
     * parentReference.path llega como "/drive/root:/Music/Sub"; se recorta hasta "root:" y se
     * descuenta la carpeta raíz configurada. null si el item no trae carpeta.
     */
    private fun relativePathOf(item: com.qhana.siku.data.remote.OneDriveItem, rootFolder: String): String? {
        val name = item.name ?: return null
        val parent = item.parentReference?.path ?: return null
        val fromRoot = parent.substringAfter("root:", "")
        val normalized = normalizeRelativePath("$fromRoot/$name")
        // El prefijo se calcula con la MISMA normalización que la ruta (minúsculas, sin bordes):
        // comparar contra la carpeta cruda fallaría con "Música" o con mayúsculas distintas.
        val prefix = normalizeRelativePath(rootFolder)
        val relative = if (prefix.isEmpty()) normalized else normalized.removePrefix("$prefix/")
        return relative.takeIf { it.isNotBlank() }
    }

    /**
     * Las extensiones tienen que ser LAS MISMAS que en `LocalMusicSource`: el dedup entre fuentes
     * compara `relativePath`, así que un formato que una fuente indexa y la otra no deja el mismo
     * archivo duplicado sin que nadie lo detecte. `.opus` faltaba justo aquí.
     */
    private fun isAudioFile(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") ||
            n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac") || n.endsWith(".opus")
    }

    private companion object {
        private const val TAG = "OneDriveMusicSource"
        private const val GRAPH_DRIVE = "https://graph.microsoft.com/v1.0/me/drive"
    }
}
