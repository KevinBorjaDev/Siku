package com.qhana.siku.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.qhana.siku.R
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.normalizeRelativePath
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.AudioFileAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuente de música LOCAL: una carpeta elegida por el usuario vía SAF
 * (`ACTION_OPEN_DOCUMENT_TREE`, con permiso persistido), recorrida recursivamente.
 *
 * Diferencias clave con una fuente cloud:
 * - **No descarga nada**: los archivos ya están en el dispositivo. Sus canciones se excluyen de
 *   la cola de descargas (ver `SongDao`, `sourceType != 'LOCAL'`).
 * - **No hay auth** ni resolución de URL firmada: la reproducción usa el `content://` directo.
 * - El **id es portable**: `local:<ruta relativa a la raíz>` (ver `SourceType.buildId`), así una
 *   playlist respaldada resuelve en otro dispositivo con la misma estructura de carpetas.
 */
@Singleton
class LocalMusicSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPreferences: MusicPreferences,
    private val musicRepository: IMusicRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer
) : MusicSource {

    override val type: SourceType = SourceType.LOCAL

    /** La fuente local está configurada si el usuario ya eligió una carpeta. */
    override suspend fun isConfigured(): Boolean = musicPreferences.loadLocalFolderUri() != null

    /** Tree URI de la carpeta elegida, o null. */
    fun folderUri(): String? = musicPreferences.loadLocalFolderUri()

    /**
     * Fija la carpeta local. Si cambió respecto a la anterior, borra las canciones LOCAL previas:
     * sus `content://` apuntaban al árbol viejo y ya no son reproducibles. Los ids son por ruta
     * relativa, así que si la estructura coincide se recrean iguales y las playlists sobreviven.
     */
    suspend fun setFolder(uri: String) {
        val previous = musicPreferences.loadLocalFolderUri()
        if (previous != null && previous != uri) deleteAllLocalSongs()
        musicPreferences.saveLocalFolderUri(uri)
    }

    /** Quita la carpeta local y borra sus canciones de la biblioteca. */
    suspend fun clearFolder() {
        deleteAllLocalSongs()
        musicPreferences.clearLocalFolderUri()
    }

    private suspend fun deleteAllLocalSongs() = musicRepository.clearSourceData(SourceType.LOCAL)

    override suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult =
        withContext(Dispatchers.IO) {
            val treeUriStr = musicPreferences.loadLocalFolderUri()
                ?: return@withContext DiscoverResult(0, 0)
            val treeUri = Uri.parse(treeUriStr)

            val found = try {
                walkAudioFiles(treeUri, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Carpeta borrada o permiso revocado: no reventamos el sync, sólo avisamos.
                Log.w(TAG, "No se pudo recorrer la carpeta local: ${e.message}")
                return@withContext DiscoverResult(0, 0)
            }

            val existingIds = musicRepository.getSongIdsBySourceType(SourceType.LOCAL).toHashSet()
            val seenIds = HashSet<String>(found.size)
            var added = 0

            // Política de duplicados PREFER_CLOUD: los archivos cuya ruta ya existe en la
            // nube se saltan (evita re-analizar + insertar→fusionar en cada scan).
            val skipPaths: Set<String> =
                if (musicPreferences.loadDuplicatePolicy() == DuplicatePolicy.PREFER_CLOUD)
                    musicRepository.getRelativePathsOfOtherSources(SourceType.LOCAL)
                else emptySet()

            val batch = ArrayList<Song>(UPSERT_BATCH)
            for (file in found) {
                if (ctx.isStopped()) break
                if (skipPaths.isNotEmpty() && relativePathOf(file) in skipPaths) {
                    // Si una copia vieja sigue en la BD, protegerla de la reconciliación:
                    // la retira el dedupe pass del orquestador re-apuntando playlists.
                    if (file.id in existingIds) seenIds.add(file.id)
                    continue
                }
                seenIds.add(file.id)
                if (file.id in existingIds) continue // ya indexada: no re-analizamos

                val song = buildSong(file)
                batch.add(song)
                if (batch.size >= UPSERT_BATCH) {
                    added += flush(batch)
                    ctx.reportScanning(
                        added,
                        context.resources.getQuantityString(R.plurals.scan_local_songs, added, added)
                    )
                }
            }
            if (batch.isNotEmpty() && !ctx.isStopped()) added += flush(batch)

            // Reconciliación: lo que ya no está en la carpeta se borra de la BD.
            var deleted = 0
            if (!ctx.isStopped()) {
                val orphans = existingIds.filter { it !in seenIds }
                if (orphans.isNotEmpty()) {
                    Log.d(TAG, "Reconciliación local: borrando ${orphans.size} huérfanas")
                    musicRepository.deleteSongs(orphans)
                    deleted = orphans.size
                }
            }

            Log.d(TAG, "discover local: added=$added, deleted=$deleted (archivos=${found.size})")
            DiscoverResult(added, deleted)
        }

    /** Los archivos locales ya son reproducibles: la "URL" es su propio `content://`. */
    override suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean): String? =
        song.path.takeIf { it.isNotBlank() }

    /** Relee los tags del `content://` (usado por la cadena de preparación si faltaba metadata). */
    override suspend fun extractMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        val uri = Uri.parse(song.path)
        val fileName = song.id.substringAfterLast('/')
        val analysis = audioFileAnalyzer.analyzeContentUri(uri, fileName)
        if (!analysis.isValid) return@withContext song
        val artUri = analysis.embeddedArt?.let { audioFileAnalyzer.persistEmbeddedArt(song.id, it) }
        song.copy(
            title = analysis.title ?: song.title,
            artist = analysis.artist ?: song.artist,
            album = analysis.album ?: song.album,
            genre = analysis.genre ?: song.genre,
            duration = if (analysis.duration > 0) analysis.duration else song.duration,
            albumArtUri = artUri?.let { Uri.parse(it) } ?: song.albumArtUri
        )
    }

    private suspend fun flush(batch: MutableList<Song>): Int {
        val result = musicRepository.upsertSongs(batch.toList())
        batch.clear()
        return (result as? com.qhana.siku.data.model.AppResult.Success)?.data ?: 0
    }

    private suspend fun buildSong(file: LocalAudioFile): Song {
        val analysis = audioFileAnalyzer.analyzeContentUri(file.uri, file.name)
        val artUri = analysis.embeddedArt?.let { audioFileAnalyzer.persistEmbeddedArt(file.id, it) }
        return Song(
            id = file.id,
            title = analysis.title ?: file.name.substringBeforeLast('.'),
            artist = analysis.artist ?: AppConfig.UNKNOWN_ARTIST,
            album = analysis.album ?: AppConfig.UNKNOWN_ALBUM,
            genre = analysis.genre,
            duration = analysis.duration,
            path = file.uri.toString(),
            albumArtUri = artUri?.let { Uri.parse(it) },
            dateAdded = file.lastModified / 1000,
            remoteId = null,               // local: no hay handle remoto
            sourceType = SourceType.LOCAL,
            size = file.size,
            relativePath = relativePathOf(file)
        )
    }

    /** Ruta relativa normalizada del archivo (su id ES "local:<relpath>"). */
    private fun relativePathOf(file: LocalAudioFile): String =
        normalizeRelativePath(file.id.substringAfter(':'))

    /** Un audio encontrado en el árbol SAF. */
    private data class LocalAudioFile(
        val id: String,          // `local:<ruta relativa>`
        val name: String,
        val uri: Uri,            // content:// reproducible
        val size: Long,
        val lastModified: Long
    )

    /**
     * Recorre el árbol SAF sin recursión (pila explícita) consultando `ContentResolver`.
     * `DocumentFile.listFiles()` haría una query por hijo y es notoriamente lento.
     */
    private fun walkAudioFiles(treeUri: Uri, ctx: DiscoverContext): List<LocalAudioFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<LocalAudioFile>(256)
        val stack = ArrayDeque<String>().apply { addLast(rootDocId) }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (stack.isNotEmpty()) {
            if (ctx.isStopped()) break
            val parentDocId = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (ctx.isStopped()) return@use
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.addLast(docId)
                    } else if (isAudioFile(name)) {
                        val relPath = docId.removePrefix(rootDocId).trimStart('/', ':')
                        out.add(
                            LocalAudioFile(
                                id = SourceType.LOCAL.buildId(relPath),
                                name = name,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                size = cursor.getLong(3),
                                lastModified = cursor.getLong(4)
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    private fun isAudioFile(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") ||
            n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac") || n.endsWith(".opus")
    }

    private companion object {
        private const val TAG = "LocalMusicSource"
        private const val UPSERT_BATCH = 50
    }
}
