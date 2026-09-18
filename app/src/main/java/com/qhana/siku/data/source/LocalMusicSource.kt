package com.qhana.siku.data.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
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
 * Fuente de música LOCAL, en dos modos EXCLUYENTES:
 *
 * - **Dispositivo**: todo lo que el sistema indexa como música (`MediaStore`, filtrado por
 *   `MUSIC_SELECTION`). Es el único modo que necesita permiso de lectura de audio, y el único con
 *   **carpetas excluibles**: ese filtro descarta lo que el sistema MARCA como tono, notificación,
 *   alarma o podcast, pero los archivos fuera de `Music/` suelen tener esos flags sin rellenar —y
 *   se toleran nulos a propósito, o se perderían canciones—, así que audios de mensajería y
 *   grabaciones entran igual. Cuáles sobran no lo puede saber un flag: lo dice el usuario, desde
 *   Ajustes → Fuentes → Carpetas excluidas.
 * - **Carpetas**: una o varias carpetas elegidas vía SAF (`ACTION_OPEN_DOCUMENT_TREE`, permiso
 *   persistido), recorridas recursivamente. No necesita permiso: el árbol se autoriza al elegirlo.
 *
 * Son excluyentes porque no son bibliotecas distintas sino dos formas de mirar el MISMO
 * almacenamiento: activar el escaneo completo con carpetas puestas dejaría cada archivo indexado
 * por dos caminos.
 *
 * Diferencias clave con una fuente cloud:
 * - **No descarga nada**: los archivos ya están en el dispositivo. Sus canciones se excluyen de
 *   la cola de descargas (ver `SongDao`, `sourceType != 'LOCAL'`).
 * - **No hay auth** ni resolución de URL firmada: la reproducción usa el `content://` directo.
 *
 * **El id es portable y relativo al VOLUMEN**: `local:<volumen>/<ruta desde la raíz del volumen>`
 * (p. ej. `local:primary/music/artista/x.flac`). Que no sea relativo a la carpeta elegida es
 * load-bearing por dos motivos: con varias carpetas, dos archivos homónimos en la raíz de cada una
 * producirían el MISMO id y uno pisaría al otro; y así el id que genera SAF coincide con el que
 * genera MediaStore, de modo que cambiar de modo —o añadir una carpeta que ya estaba dentro de
 * otra— no duplica filas ni rompe las playlists que apuntan a ellas.
 */
@Singleton
class LocalMusicSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPreferences: MusicPreferences,
    private val musicRepository: IMusicRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer
) : MusicSource {

    override val type: SourceType = SourceType.LOCAL

    /** Configurada si se escanea el dispositivo entero o si hay al menos una carpeta. */
    override suspend fun isConfigured(): Boolean = scansWholeDevice() || folderUris().isNotEmpty()

    /**
     * La misma condición, observable. Se deriva de los MISMOS dos ajustes que lee [isConfigured]
     * (por sus flows del DataStore), así que las dos formas no pueden discrepar.
     */
    override val isConfiguredFlow: kotlinx.coroutines.flow.Flow<Boolean> =
        kotlinx.coroutines.flow.combine(
            musicPreferences.scanWholeDeviceFlow,
            musicPreferences.localFolderUrisFlow
        ) { wholeDevice, folders -> wholeDevice || folders.isNotEmpty() }

    /** ¿El modo activo es "toda la música del dispositivo"? */
    fun scansWholeDevice(): Boolean = musicPreferences.loadScanWholeDevice()

    /** Tree URIs de las carpetas elegidas (vacío en modo dispositivo). */
    fun folderUris(): Set<String> = musicPreferences.loadLocalFolderUris()

    /**
     * ¿Está concedido el permiso de lectura de audio? Solo lo exige el modo dispositivo; el modo
     * carpetas funciona sin él. La UI lo consulta para pedirlo justo cuando hace falta.
     */
    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Permiso de lectura de audio vigente para esta versión de Android. */
    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    // --- Configuración de las fuentes locales -------------------------------------------------

    /**
     * Añade una carpeta a escanear (el permiso persistido de SAF ya lo tomó la UI). Desactiva el
     * escaneo completo: son excluyentes.
     *
     * NO borra nada. Las canciones que ya estuvieran indexadas bajo esa ruta conservan su id, así
     * que si venían del escaneo completo simplemente siguen ahí, con sus playlists y su historial.
     */
    suspend fun addFolder(uri: String) {
        // Una sola escritura: añadir carpeta y apagar el escaneo completo son el mismo cambio, y
        // dos `update` encadenados podían pisarse en disco (la carpeta nueva no aparecía).
        // clearStash: elegir una carpeta a mano fija el modo carpetas; el stash de "las carpetas de
        // antes del escaneo del dispositivo" deja de tener sentido.
        musicPreferences.saveLocalSources(folderUris() + uri, scanWholeDevice = false, clearStash = true)
    }

    /**
     * Deja de escanear [uri] y retira de la biblioteca las canciones que solo esa carpeta cubría
     * (los archivos NO se tocan: son `content://`, y [IMusicRepository.deleteSongs] solo borra del
     * disco los `file://` que descargó la app).
     *
     * Las que además caen bajo otra carpeta que sigue activa se conservan — el caso de carpetas
     * anidadas, donde quitar la interior no debe vaciar lo que la exterior sigue viendo.
     */
    suspend fun removeFolder(uri: String) {
        val remaining = folderUris() - uri
        musicPreferences.saveLocalFolderUris(remaining)
        releaseFolderPermission(uri, remaining)

        // Era la última: ya no queda fuente local, así que se va TODA la música local. Filtrar por
        // prefijo aquí dejaría atrás las filas que aún arrastren un id del esquema viejo (usuario
        // que actualiza y quita la carpeta antes de que corra el primer escaneo).
        if (remaining.isEmpty() && !scansWholeDevice()) {
            clearLocalSongs()
            return
        }

        val removedPrefix = idPrefixOf(uri) ?: return
        val remainingPrefixes = remaining.mapNotNull { idPrefixOf(it) }
        val orphans = musicRepository.getSongIdsBySourceType(SourceType.LOCAL).filter { id ->
            id.startsWith(removedPrefix) && remainingPrefixes.none { id.startsWith(it) }
        }
        if (orphans.isNotEmpty()) musicRepository.deleteSongs(orphans)
    }

    /**
     * Activa o desactiva el escaneo del dispositivo completo. Los modos son excluyentes, pero
     * activar el dispositivo NO tira las carpetas que había: las guarda en un stash para
     * restaurarlas al apagarlo. Sin eso, un usuario con carpetas que probaba "escanear todo" se
     * quedaba sin ninguna fuente al desactivarlo → biblioteca vacía → de vuelta al onboarding.
     *
     * No borra canciones al activar: los ids coinciden entre ambos modos y el escaneo reconcilia.
     *
     * Al desactivar se devuelven las carpetas guardadas. Si no había ninguna, apagar el dispositivo
     * SÍ vacía la biblioteca local (era la única fuente local que quedaba). Devuelve las carpetas
     * restauradas para que el caller decida si hace falta reescanear (reconciliar lo del dispositivo).
     */
    suspend fun setWholeDeviceScan(enabled: Boolean): Set<String> {
        if (enabled) {
            // Guardar las carpetas actuales (si las hay) y activar el dispositivo con la lista
            // vacía, todo en UNA escritura (evita la race de dos volcados encadenados).
            val current = folderUris()
            musicPreferences.saveLocalSources(
                folderUris = emptySet(),
                scanWholeDevice = true,
                stashFolders = current.takeIf { it.isNotEmpty() }
            )
            return emptySet()
        }
        val restored = musicPreferences.loadStashedFolderUris()
        musicPreferences.saveLocalSources(
            folderUris = restored,
            scanWholeDevice = false,
            clearStash = true
        )
        if (restored.isEmpty()) clearLocalSongs()
        return restored
    }

    private suspend fun clearLocalSongs() = musicRepository.clearSourceData(SourceType.LOCAL)

    /**
     * Devuelve al sistema el permiso persistido de SAF de una carpeta que se deja de escanear.
     * Retenerlo era una fuga silenciosa: el cupo de URIs persistidas por app es limitado, y quien
     * fuera probando carpetas las acumulaba todas para siempre.
     *
     * Tres razones para NO soltarlo, y las tres importan:
     *  - la carpeta sigue en la lista (no debería llegar aquí, pero soltarlo la volvería ilegible);
     *  - está en el STASH — las carpetas guardadas al activar el escaneo del dispositivo, que se
     *    restauran al apagarlo: sin permiso volverían vacías;
     *  - es la carpeta donde se guardan los `.lrc`. Puede ser LA MISMA que la de música (lo normal,
     *    de hecho: las letras van junto al audio), y ese permiso incluye ESCRITURA. Soltarlo aquí
     *    rompería el guardado de letras sin que nada lo relacionara con haber quitado una carpeta.
     */
    private fun releaseFolderPermission(uri: String, remaining: Set<String>) {
        if (uri in remaining) return
        if (uri in musicPreferences.loadStashedFolderUris()) return
        if (uri == musicPreferences.loadLyricsFolderUri()) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // No lo teníamos: revocado desde los ajustes del sistema, o nunca llegó a persistirse.
            Log.w(TAG, "No se pudo devolver el permiso SAF de $uri", e)
        }
    }

    // --- Descubrimiento -----------------------------------------------------------------------

    override suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult =
        withContext(Dispatchers.IO) {
            // Sin fuente local configurada no hay nada que descubrir NI que reconciliar: seguir
            // adelante equivaldría a decir "no encontré nada", y la reconciliación vaciaría la
            // biblioteca local. El registro solo escanea fuentes configuradas, pero esta guardia
            // hace que la clase sea segura por sí sola.
            if (!isConfigured()) return@withContext DiscoverResult(0, 0)

            val listing = try {
                if (scansWholeDevice()) queryDeviceAudio(ctx) else walkFolders(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permiso revocado o proveedor caído: no reventamos el sync ni tocamos la BD.
                Log.w(TAG, "No se pudo listar la música local: ${e.message}")
                return@withContext DiscoverResult(0, 0)
            }

            val existingIds = musicRepository.getSongIdsBySourceType(SourceType.LOCAL).toHashSet()
            val seenIds = HashSet<String>(listing.files.size)
            var added = 0

            // Política de duplicados PREFER_CLOUD: los archivos cuya ruta ya existe en la
            // nube se saltan (evita re-analizar + insertar→fusionar en cada scan).
            val skipPaths: Set<String> =
                if (musicPreferences.loadDuplicatePolicy() == DuplicatePolicy.PREFER_CLOUD)
                    musicRepository.getRelativePathsOfOtherSources(SourceType.LOCAL)
                else emptySet()

            val batch = ArrayList<Song>(UPSERT_BATCH)
            for (file in listing.files) {
                if (ctx.isStopped()) break
                if (skipPaths.isNotEmpty() && file.relativePath in skipPaths) {
                    // Si una copia vieja sigue en la BD, protegerla de la reconciliación:
                    // la retira el dedupe pass del orquestador re-apuntando playlists.
                    if (file.id in existingIds) seenIds.add(file.id)
                    continue
                }
                seenIds.add(file.id)
                if (file.id in existingIds) continue // ya indexada: no re-analizamos

                // Antes de indexarla como nueva: ¿es la MISMA canción con el id del esquema
                // viejo (relativo a la carpeta)? Entonces se migra en vez de duplicarse.
                if (file.legacyId != null && file.legacyId in existingIds) {
                    migrateLegacyId(file, file.legacyId)
                    existingIds.remove(file.legacyId)
                    existingIds.add(file.id)
                    continue
                }

                batch.add(buildSong(file))
                if (batch.size >= UPSERT_BATCH) {
                    added += flush(batch)
                    ctx.reportScanning(
                        added,
                        context.resources.getQuantityString(R.plurals.scan_local_songs, added, added)
                    )
                }
            }
            if (batch.isNotEmpty() && !ctx.isStopped()) added += flush(batch)

            // Reconciliación: lo que ya no está se borra de la BD, pero SOLO dentro del ámbito
            // que de verdad se pudo listar. Sin este acotado, una carpeta con el permiso revocado
            // (o el modo dispositivo sin permiso, que devuelve 0 filas sin lanzar) se llevaría por
            // delante toda la biblioteca local.
            var deleted = 0
            if (!ctx.isStopped()) {
                val orphans = existingIds.filter { it !in seenIds && listing.covers(it) }
                if (orphans.isNotEmpty()) {
                    Log.d(TAG, "Reconciliación local: borrando ${orphans.size} huérfanas")
                    musicRepository.deleteSongs(orphans)
                    deleted = orphans.size
                }
            }

            Log.d(TAG, "discover local: added=$added, deleted=$deleted (archivos=${listing.files.size})")
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
        val artUri = analysis.embeddedArt?.let { audioFileAnalyzer.persistArtwork(it) }
        song.copy(
            title = analysis.title ?: song.title,
            artist = analysis.artist ?: song.artist,
            album = analysis.album ?: song.album,
            genre = analysis.genre ?: song.genre,
            albumArtist = analysis.albumArtist ?: song.albumArtist,
            artistCredits = if (analysis.albumArtist != null) analysis.artistCredits else song.artistCredits,
            trackNumber = analysis.trackNumber.takeIf { it > 0 } ?: song.trackNumber,
            year = analysis.year.takeIf { it > 0 } ?: song.year,
            duration = if (analysis.duration > 0) analysis.duration else song.duration,
            albumArtUri = artUri?.let { Uri.parse(it) } ?: song.albumArtUri
        )
    }

    private suspend fun flush(batch: MutableList<Song>): Int {
        val result = musicRepository.upsertSongs(batch.toList())
        batch.clear()
        return (result as? com.qhana.siku.data.model.AppResult.Success)?.data ?: 0
    }

    /**
     * Reindexa una canción que estaba guardada con el id viejo (relativo a la carpeta elegida)
     * bajo el id nuevo (relativo al volumen), conservando TODO lo que el usuario acumuló:
     * referencias en playlists, historial de reproducción, colores y carátula.
     *
     * Mismo patrón que el dedupe entre fuentes: insertar la fila ganadora, re-apuntar las
     * referencias y borrar la perdedora — en ese orden, que es el que respeta la FK de
     * `playlist_song_cross_ref` (declarada `onUpdate NO ACTION`, así que un UPDATE del id
     * directo violaría la constraint).
     *
     * **CUÁNDO SE PUEDE BORRAR ESTO**: dos releases después de la primera que traiga el esquema de
     * id por volumen (o sea, la que suceda a `versionCode 2` / 1.0.1). No hay Play Console —la app
     * se distribuye como APK en GitHub—, así que no existe forma de medir qué versiones siguen
     * vivas: es una decisión por plazo, no por telemetría. Quien actualice desde 1.0.1 más tarde
     * tendrá que reinstalar limpio.
     *
     * Y reinstalar limpio NO es gratis: se lleva playlists, favoritos, historial (`playCount`/
     * `lastPlayedAt`) y colores manuales. `PlaylistBackupRepository` cubre las playlists, pero solo
     * con OneDrive conectado. Por eso el plazo se cuenta desde la release que introduce el cambio
     * y no desde antes.
     *
     * Cubre DOS cambios de id, con el mismo mecanismo: el modo carpetas migra del esquema relativo
     * a la carpeta elegida al esquema por volumen (`walkAudioFiles`), y el modo dispositivo migra
     * las rutas con la barra duplicada que producía `RELATIVE_PATH` (`queryDeviceAudio`).
     */
    private suspend fun migrateLegacyId(file: LocalAudioFile, legacyId: String) {
        val existing = (musicRepository.getSongById(legacyId)
            as? com.qhana.siku.data.model.AppResult.Success)?.data ?: return

        // La carátula NO se toca: su archivo se llama por el contenido de la imagen, no por el id
        // de la canción, así que renombrar la canción no la afecta. El URI viaja tal cual.
        musicRepository.upsertSongs(
            listOf(
                existing.copy(
                    id = file.id,
                    path = file.uri.toString(),
                    // También cambió de semántica: antes era relativa a la carpeta elegida y ahora
                    // a la raíz del volumen. Sin actualizarla, el dedup entre fuentes compararía
                    // rutas de dos esquemas distintos.
                    relativePath = file.relativePath
                )
            )
        )
        musicRepository.repointSongRefs(legacyId, file.id)
        musicRepository.mergePlayStats(legacyId, file.id)
        musicRepository.deleteSongs(listOf(legacyId))
    }

    private suspend fun buildSong(file: LocalAudioFile): Song {
        val analysis = audioFileAnalyzer.analyzeContentUri(file.uri, file.name)
        val artUri = analysis.embeddedArt?.let { audioFileAnalyzer.persistArtwork(it) }
        return Song(
            id = file.id,
            // Los tags del archivo mandan; lo que traiga el índice del sistema (modo dispositivo)
            // solo rellena huecos, y el nombre del archivo es el último recurso.
            title = analysis.title ?: file.title ?: file.name.substringBeforeLast('.'),
            artist = analysis.artist ?: file.artist ?: AppConfig.UNKNOWN_ARTIST,
            album = analysis.album ?: file.album ?: AppConfig.UNKNOWN_ALBUM,
            genre = analysis.genre,
            // null si el análisis falló: queda pendiente para `ArtistTagsBackfiller`.
            albumArtist = analysis.albumArtist,
            artistCredits = analysis.artistCredits,
            // Mismo criterio que el resto: manda el tag del archivo y el índice del sistema solo
            // rellena el hueco (en modo carpetas el índice no aporta nada y siempre vale 0).
            trackNumber = analysis.trackNumber.takeIf { it > 0 } ?: file.trackNumber,
            year = analysis.year.takeIf { it > 0 } ?: file.year,
            duration = if (analysis.duration > 0) analysis.duration else file.duration,
            path = file.uri.toString(),
            albumArtUri = artUri?.let { Uri.parse(it) },
            dateAdded = file.lastModified / 1000,
            remoteId = null,               // local: no hay handle remoto
            sourceType = SourceType.LOCAL,
            size = file.size,
            relativePath = file.relativePath
        )
    }

    /** Un audio encontrado en el dispositivo, ya identificado con su id definitivo. */
    private data class LocalAudioFile(
        /** `local:<volumen>/<ruta>` */
        val id: String,
        /**
         * Id que ESTA canción tiene guardado si se indexó con un esquema anterior: relativo a la
         * carpeta elegida (modo carpetas) o con la barra duplicada de `RELATIVE_PATH` (modo
         * dispositivo). `null` cuando el id actual coincide con el que ya estaría en la BD.
         */
        val legacyId: String?,
        val name: String,
        val uri: Uri,                // content:// reproducible
        val size: Long,
        val lastModified: Long,      // epoch ms
        /** Ruta desde la raíz del volumen, normalizada: es lo que compara el dedup entre fuentes. */
        val relativePath: String,
        // Metadata que el índice del sistema ya conoce (modo dispositivo); null en modo carpetas.
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** 0 = el índice no lo declara (siempre, en modo carpetas: SAF no indexa tags). */
        val trackNumber: Int = 0,
        val year: Int = 0,
        val duration: Long = 0L
    )

    /**
     * `MediaStore.Audio.Media.TRACK` NO es el número de pista pelado: cuando el archivo declara
     * número de disco, MediaProvider los empaqueta como `disco * 1000 + pista`, así que la pista 4
     * del disco 2 se guarda como 2004. Sin deshacer eso, un álbum doble ordenaría bien por
     * casualidad (el disco 1 va antes que el 2) pero mostraría "2004" como número de pista, y
     * cualquier comparación con el valor leído del tag del archivo —que sí es pelado— fallaría.
     *
     * Se conserva solo la pista: el número de disco no tiene columna en `songs`, y con el orden
     * ya resuelto no aporta nada que se pueda mostrar.
     */
    private fun mediaStoreTrackNumber(raw: Int): Int =
        if (raw >= MEDIASTORE_DISC_MULTIPLIER) raw % MEDIASTORE_DISC_MULTIPLIER else raw.coerceAtLeast(0)

    /**
     * Resultado de listar, con el ÁMBITO de lo listado: [covers] dice si un id de la biblioteca
     * cae dentro de lo que esta pasada pudo mirar, y por tanto si su ausencia significa de verdad
     * "ya no está" o solo "no lo miramos".
     */
    private class Listing(val files: List<LocalAudioFile>, val covers: (String) -> Boolean)

    // --- Modo carpetas (SAF) ------------------------------------------------------------------

    private fun walkFolders(ctx: DiscoverContext): Listing {
        val folders = folderUris()
        val files = ArrayList<LocalAudioFile>(256)
        val scannedPrefixes = ArrayList<String>()
        var failed = false
        for (uri in folders) {
            if (ctx.isStopped()) break
            val treeUri = Uri.parse(uri)
            try {
                files.addAll(walkAudioFiles(treeUri, ctx))
                idPrefixOf(uri)?.let { scannedPrefixes.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Carpeta borrada o permiso revocado: se salta, y el ámbito de la reconciliación
                // se acota para no borrar canciones que probablemente siguen existiendo.
                Log.w(TAG, "No se pudo recorrer $uri: ${e.message}")
                failed = true
            }
        }

        // Un archivo puede aparecer por dos carpetas anidadas: mismo id, una sola fila.
        val unique = files.distinctBy { it.id }

        // Si se pudieron recorrer TODAS las carpetas configuradas, la foto es completa y cualquier
        // canción local ausente sobra: su archivo desapareció, su carpeta ya no está configurada, o
        // arrastra un id del esquema viejo cuyo archivo ya no está. Acotar el ámbito a los prefijos
        // escaneados dejaría esas últimas como fantasmas para siempre, porque su id no empieza por
        // ningún prefijo actual. Con alguna carpeta ilegible sí se acota: ahí no sabemos qué falta.
        if (!failed && !ctx.isStopped()) return Listing(unique) { true }
        return Listing(unique) { id -> scannedPrefixes.any { id.startsWith(it) } }
    }

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
                        val volumePath = volumeRelativePath(docId)
                        val legacyRelPath = docId.removePrefix(rootDocId).trimStart('/', ':')
                        out.add(
                            LocalAudioFile(
                                id = SourceType.LOCAL.buildId(volumePath),
                                legacyId = SourceType.LOCAL.buildId(legacyRelPath),
                                name = name,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                size = cursor.getLong(3),
                                lastModified = cursor.getLong(4),
                                relativePath = pathWithoutVolume(volumePath)
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    // --- Modo dispositivo (MediaStore) --------------------------------------------------------

    /**
     * Toda la música indexada por el sistema, descartando lo que MediaStore marcó explícitamente
     * como tono, notificación, alarma o podcast.
     *
     * El filtro se escribe por EXCLUSIÓN y tolerando `NULL` — nunca como `is_music != 0`. Esos
     * flags los rellena MediaProvider al escanear, y hasta que lo hace la fila puede existir con
     * todos a `NULL`; en SQL `NULL != 0` no es cierto, es `NULL`, así que el filtro estricto
     * escondía esas canciones en vez de mostrarlas. Excluir solo lo que está marcado
     * EXPLÍCITAMENTE como no-música es tolerante a ese estado intermedio y sigue dejando fuera
     * tonos, notificaciones, alarmas y podcasts, que es lo que de verdad importa.
     *
     * Que entre algo de audio que no es música es el precio correcto para este modo: el usuario
     * pidió "todo el dispositivo". Quien quiera control fino tiene el modo carpetas.
     *
     * NO hace falta filtrar `is_pending`/`is_trashed`: MediaProvider ya oculta a cada app las
     * filas pendientes o en la papelera que no le pertenecen.
     *
     * Sin permiso devuelve una lista vacía CON el ámbito vacío, para que la reconciliación no
     * interprete "no puedo ver nada" como "el usuario borró toda su música".
     */
    private fun queryDeviceAudio(ctx: DiscoverContext): Listing {
        if (!hasAudioPermission()) {
            Log.w(TAG, "Escaneo del dispositivo sin permiso de audio: se omite")
            return Listing(emptyList()) { false }
        }

        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            if (useRelativePath) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.VOLUME_NAME)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val out = ArrayList<LocalAudioFile>(256)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            collection,
            projection,
            MUSIC_SELECTION,
            null,
            null
        ) ?: run {
            // Cursor null = el proveedor no respondió. NO es "no hay música": sin acotar el
            // ámbito, la reconciliación se llevaría por delante toda la biblioteca local.
            Log.w(TAG, "MediaStore no devolvió cursor: se omite el escaneo")
            return Listing(emptyList()) { false }
        }

        val excluded = excludedDeviceFolders()

        cursor.use { rows ->
            // Los índices se resuelven UNA vez, no por fila: en una biblioteca de miles de
            // canciones, buscar la columna por nombre en cada vuelta es puro coste repetido.
            val idCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durationCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val titleCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val trackCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val relativeCol =
                if (useRelativePath) rows.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val volumeCol =
                if (useRelativePath) rows.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME) else -1
            @Suppress("DEPRECATION")
            val dataCol =
                if (useRelativePath) -1 else rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (rows.moveToNext()) {
                if (ctx.isStopped()) break
                val name = rows.getString(nameCol) ?: continue
                if (!isAudioFile(name)) continue

                val rawPath = if (useRelativePath) {
                    val relative = rows.getString(relativeCol).orEmpty()
                    val volume = rows.getString(volumeCol).orEmpty()
                    "${canonicalVolume(volume)}/$relative/$name"
                } else {
                    val data = rows.getString(dataCol) ?: continue
                    rawVolumePathFromAbsolutePath(data)
                }
                val volumePath = normalizeRelativePath(rawPath)
                if (isUnderAnyFolder(volumePath, excluded)) continue
                val doubleSlashPath = normalizeKeepingRepeatedSeparators(rawPath)

                out.add(
                    LocalAudioFile(
                        id = SourceType.LOCAL.buildId(volumePath),
                        // Este modo nunca escribió ids relativos a la carpeta (nació con el esquema
                        // por volumen), pero SÍ escribió ids con la barra duplicada que dejaba
                        // `RELATIVE_PATH` al terminar en `/`. Se migran por el mismo camino.
                        legacyId = doubleSlashPath
                            .takeIf { it != volumePath }
                            ?.let { SourceType.LOCAL.buildId(it) },
                        name = name,
                        uri = ContentUris.withAppendedId(collection, rows.getLong(idCol)),
                        size = rows.getLong(sizeCol),
                        lastModified = rows.getLong(modifiedCol) * 1000, // MediaStore da segundos
                        relativePath = pathWithoutVolume(volumePath),
                        title = rows.getString(titleCol),
                        artist = rows.getString(artistCol)?.takeIf { it != MEDIASTORE_UNKNOWN },
                        album = rows.getString(albumCol)?.takeIf { it != MEDIASTORE_UNKNOWN },
                        trackNumber = mediaStoreTrackNumber(rows.getInt(trackCol)),
                        // NULL en la columna se lee como 0, que es justo "no lo declara".
                        year = rows.getInt(yearCol),
                        duration = rows.getLong(durationCol)
                    )
                )
            }
        }
        // Ámbito = los volúmenes MONTADOS AHORA. En este modo "lo que no está en el índice del
        // sistema ya no está" solo vale para el almacenamiento que de verdad se pudo mirar: con
        // una SD desmontada MediaStore deja de devolver sus filas, y un ámbito de "todo" leería
        // ese silencio como un borrado del usuario, llevándose la biblioteca de la tarjeta —con
        // sus playlists e historial— por haber sacado la tarjeta un rato.
        val mounted = mountedVolumeNames()
        return Listing(out) { id -> volumeOfLocalId(id) in mounted }
    }

    // --- Rutas e ids --------------------------------------------------------------------------

    /**
     * `primary:Music/Rock/x.flac` (docId de SAF) → `primary/music/rock/x.flac`.
     *
     * El volumen se conserva como primer segmento para que dos tarjetas/almacenamientos con la
     * misma estructura no colisionen. Si el proveedor no usa el formato `volumen:ruta` (proveedores
     * de terceros con ids opacos), se toma el docId entero: seguirá siendo único, aunque no
     * unifique con MediaStore.
     */
    private fun volumeRelativePath(docId: String): String {
        val separator = docId.indexOf(':')
        if (separator < 0) return normalizeRelativePath(docId)
        val volume = canonicalVolume(docId.substring(0, separator))
        return normalizeRelativePath("$volume/${docId.substring(separator + 1)}")
    }

    /**
     * Igual, partiendo de una ruta absoluta (`MediaStore.DATA`, único camino antes de API 29). Se
     * devuelve SIN normalizar para poder derivar también el id que esta misma ruta tuvo con la
     * normalización anterior (ver [normalizeKeepingRepeatedSeparators]).
     */
    private fun rawVolumePathFromAbsolutePath(path: String): String {
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
        if (externalRoot != null && path.startsWith(externalRoot)) {
            return "${VolumeNames.SAF_PRIMARY}/${path.removePrefix(externalRoot)}"
        }
        // Volumen secundario: /storage/<UUID>/… → el UUID hace de nombre de volumen.
        return path.removePrefix("${VolumeNames.STORAGE_ROOT}/")
    }

    /**
     * La normalización que había ANTES de colapsar las barras repetidas. Existe solo para calcular
     * el id que una canción ya indexada tiene guardado y poder migrarlo; no debe usarse para nada
     * más.
     *
     * **CUÁNDO SE PUEDE BORRAR ESTO**: dos releases después de la primera que colapse las barras
     * (o sea, la que suceda a `versionCode 4` / 1.1.1). Mismo criterio que [migrateLegacyId]: no hay
     * telemetría —la app se distribuye como APK—, así que es una decisión por plazo. Quien
     * actualice más tarde verá sus canciones del modo dispositivo reindexadas como nuevas, con la
     * pérdida de playlists e historial que eso implica.
     */
    private fun normalizeKeepingRepeatedSeparators(raw: String): String =
        raw.replace('\\', '/').trim('/').lowercase()

    /**
     * Nombre de volumen canónico. SAF dice `primary` y MediaStore `external_primary` para el mismo
     * almacenamiento; sin unificarlos, el mismo archivo tendría dos ids según el modo.
     */
    private fun canonicalVolume(volume: String): String = VolumeNames.canonical(volume)

    /** La ruta sin su primer segmento (el volumen): es lo que compara el dedup entre fuentes. */
    private fun pathWithoutVolume(volumePath: String): String = volumePath.substringAfter('/', "")

    /**
     * Prefijo de id que cubre una carpeta: `local:primary/music/`. Sirve para saber qué canciones
     * "pertenecen" a una carpeta sin guardar esa relación en la BD (sería estado redundante: el id
     * YA es la ruta).
     */
    private fun idPrefixOf(treeUriString: String): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUriString))
        SourceType.LOCAL.buildId(volumeRelativePath(docId)) + "/"
    }.getOrNull()

    /** Carpetas excluidas del modo dispositivo (ver [MusicPreferences.loadExcludedDeviceFolders]). */
    fun excludedDeviceFolders(): Set<String> = musicPreferences.loadExcludedDeviceFolders()

    /**
     * ¿La ruta cuelga de alguna de las carpetas dadas? Recursivo por construcción, y con la barra
     * en el prefijo a propósito: sin ella, excluir `primary/music` se llevaría también
     * `primary/music_videos`, que es otra carpeta.
     */
    private fun isUnderAnyFolder(volumePath: String, folders: Set<String>): Boolean =
        folders.any { volumePath.startsWith("$it/") }

    /** El volumen de un id local (`local:primary/music/x.flac` → `primary`). */
    private fun volumeOfLocalId(id: String): String =
        SourceType.LOCAL.stableKeyOf(id).substringBefore('/')

    /**
     * Nombres de los volúmenes montados AHORA, en el mismo espacio de nombres que los ids
     * (ver [canonicalVolume]). Es lo que acota la reconciliación del modo dispositivo.
     *
     * Antes de API 29 no hay catálogo de volúmenes, así que se derivan de los directorios que el
     * sistema nos da en cada almacenamiento: la ruta de cada uno empieza por `/storage/<volumen>/`,
     * que es exactamente de donde [rawVolumePathFromAbsolutePath] saca el nombre.
     */
    private fun mountedVolumeNames(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).map { canonicalVolume(it) }.toSet()
        } else {
            ContextCompat.getExternalFilesDirs(context, null)
                .filterNotNull()
                .mapNotNull { dir ->
                    rawVolumePathFromAbsolutePath(dir.absolutePath)
                        .substringBefore('/')
                        .lowercase(Locale.ROOT)
                        .takeIf { it.isNotBlank() }
                }
                .toSet()
        }

    /**
     * Las carpetas donde el sistema ve música, con cuántos archivos hay en cada una. Alimenta la
     * pantalla de exclusiones.
     *
     * Sale de MediaStore y NO de la biblioteca ya indexada, y eso es load-bearing: una carpeta
     * excluida no tiene ninguna canción en `songs`, así que leyendo de ahí desaparecería de la
     * lista y no habría forma de volver a incluirla. El origen es la única fuente que sigue viendo
     * lo que se está descartando.
     *
     * El conteo NO descuenta las exclusiones (cada fila cuenta en su carpeta): es el tamaño de lo
     * que se gana o se pierde al marcarla, que es justo lo que hay que poder ver para decidir.
     */
    suspend fun deviceAudioFolders(): List<DeviceAudioFolder> = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) return@withContext emptyList()

        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (useRelativePath) {
            arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.VOLUME_NAME
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
        }

        val counts = HashMap<String, Int>()
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            MUSIC_SELECTION,
            null,
            null
        ) ?: return@withContext emptyList()

        cursor.use { rows ->
            val nameCol = rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relativeCol =
                if (useRelativePath) rows.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val volumeCol =
                if (useRelativePath) rows.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME) else -1
            @Suppress("DEPRECATION")
            val dataCol =
                if (useRelativePath) -1 else rows.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (rows.moveToNext()) {
                val name = rows.getString(nameCol) ?: continue
                if (!isAudioFile(name)) continue
                val rawPath = if (useRelativePath) {
                    val relative = rows.getString(relativeCol).orEmpty()
                    val volume = rows.getString(volumeCol).orEmpty()
                    "${canonicalVolume(volume)}/$relative/$name"
                } else {
                    val data = rows.getString(dataCol) ?: continue
                    rawVolumePathFromAbsolutePath(data)
                }
                // La carpeta es la ruta del archivo sin su nombre, ya normalizada: el mismo
                // espacio de nombres en el que se guardan las exclusiones.
                val folder = normalizeRelativePath(rawPath).substringBeforeLast('/', "")
                if (folder.isEmpty()) continue
                counts[folder] = (counts[folder] ?: 0) + 1
            }
        }

        counts.entries
            .map { (path, count) -> DeviceAudioFolder(path = path, songCount = count) }
            // Por tamaño: la carpeta que sobra suele ser una de las grandes (audios de mensajería,
            // grabaciones), así que aparece arriba sin tener que buscarla.
            .sortedWith(compareByDescending<DeviceAudioFolder> { it.songCount }.thenBy { it.path })
    }

    private fun isAudioFile(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") ||
            n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac") || n.endsWith(".opus")
    }

    private companion object {
        private const val TAG = "LocalMusicSource"
        private const val UPSERT_BATCH = 50

        /**
         * Ver [queryDeviceAudio]: por exclusión y tolerando `NULL`, porque los archivos fuera de
         * `Music/` se indexan con todos estos flags sin rellenar y un `is_music != 0` los perdería.
         */
        private val MUSIC_SELECTION = listOf(
            MediaStore.Audio.Media.IS_MUSIC to "!= 0",
            MediaStore.Audio.Media.IS_RINGTONE to "= 0",
            MediaStore.Audio.Media.IS_NOTIFICATION to "= 0",
            MediaStore.Audio.Media.IS_ALARM to "= 0",
            MediaStore.Audio.Media.IS_PODCAST to "= 0"
        ).joinToString(" AND ") { (column, comparison) ->
            "($column IS NULL OR $column $comparison)"
        }

        /** Marcador literal que MediaStore usa cuando el archivo no trae el tag. */
        private const val MEDIASTORE_UNKNOWN = "<unknown>"

        /** Factor con el que MediaProvider empaqueta el disco en TRACK (ver [mediaStoreTrackNumber]). */
        private const val MEDIASTORE_DISC_MULTIPLIER = 1000
    }
}

/**
 * Una carpeta del dispositivo donde el sistema ve música, con cuántos archivos tiene.
 *
 * [path] es la ruta relativa al volumen y normalizada (`primary/whatsapp/media/whatsapp audio`),
 * o sea la MISMA forma en la que se persisten las exclusiones: la pantalla no traduce nada, solo
 * marca y desmarca lo que ya es la clave.
 */
data class DeviceAudioFolder(
    val path: String,
    val songCount: Int
)
