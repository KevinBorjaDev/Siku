package com.qhana.siku.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.PlaylistDao
import com.qhana.siku.data.local.PlaylistEntity
import com.qhana.siku.data.local.PlaylistSongCrossRef
import com.qhana.siku.data.local.SongDao
import com.qhana.siku.data.local.SongPathKey
import com.qhana.siku.data.model.AppError
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.model.normalizeRelativePath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de importar: qué lista se creó y qué se pudo hacer con cada línea del archivo. */
data class M3uImportSummary(
    val playlistId: Long,
    val playlistName: String,
    val added: Int,
    val missing: Int
)

/**
 * Resultado de exportar. [relativePaths] son las canciones que salieron con ruta RELATIVA porque no
 * tienen una en el dispositivo (las de nube): el archivo es válido, pero solo resuelve si el
 * destino tiene esa estructura de carpetas. Quien llama debe decirlo — un M3U que funciona a medias
 * sin avisar es peor que uno que no se escribe.
 */
data class M3uExportSummary(
    val written: Int,
    val relativePaths: Int
)

/**
 * Importar y exportar playlists como archivos M3U.
 *
 * **Es lo contrario del backup JSON** ([com.qhana.siku.data.backup.PlaylistBackupRepository]) y por
 * eso convive con él en vez de sustituirlo: aquél guarda la clave PORTABLE de cada canción y
 * restaura la biblioteca exacta en otro teléfono; éste habla el idioma que entienden los demás
 * reproductores, que son RUTAS DE ARCHIVO. Un M3U no puede representar una canción de nube ni
 * distinguir dos archivos idénticos en carpetas distintas; a cambio, es el único formato que el
 * resto del mundo lee.
 *
 * El acceso al archivo va SIEMPRE por un `Uri` que el usuario eligió en el selector del sistema, no
 * por rutas: un `.m3u` no es audio, así que MediaStore no lo indexa y `READ_MEDIA_AUDIO` no da
 * acceso a él. El selector funciona igual en modo dispositivo y en modo carpetas SAF, y no pide
 * ningún permiso nuevo.
 */
@Singleton
class M3uPlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val pathResolver: MediaFilePathResolver
) {

    /**
     * Lee el M3U de [uri] y crea una playlist nueva con las canciones que estén en la biblioteca.
     *
     * **Siempre crea, nunca fusiona**: dos archivos pueden llamarse igual sin ser la misma lista, y
     * fusionar por nombre convertiría un import repetido en un montón de duplicados dentro de una
     * lista que el usuario creía intacta. El nombre sale del `#PLAYLIST:` si lo hay, y si no del
     * nombre del ARCHIVO —que es como el usuario llama a su lista— sin su extensión; [fallbackName]
     * es el último recurso, para un proveedor que no publique el nombre.
     */
    suspend fun import(uri: Uri, fallbackName: String): AppResult<M3uImportSummary> =
        withContext(Dispatchers.IO) {
            runCatchingFile {
                val parsed = M3uFormat.parse(readText(uri))
                if (parsed.entries.isEmpty()) {
                    throw NothingToDoException("M3U sin entradas")
                }

                val index = LibraryPathIndex(songDao.getPathKeys())
                val name = parsed.name ?: displayNameOf(uri) ?: fallbackName
                val playlistId = playlistDao.upsertPlaylist(
                    PlaylistEntity(uuid = UUID.randomUUID().toString(), name = name)
                )

                var missing = 0
                val seen = HashSet<String>()
                // Se ARMA la lista de referencias y se escribe UNA vez, en vez de una inserción por
                // canción. Cada `addSongToPlaylist` suelto es su propia transacción, o sea su
                // propia invalidación de `playlist_song_cross_ref` — que la UI observa, así que
                // importar mil canciones repintaba las pantallas de listas mil veces sobre una
                // lista a medio construir. Es el mismo criterio con el que este mismo método ya
                // resolvía la BÚSQUEDA (un `getPathKeys` y un índice en memoria, no una consulta
                // por línea): la mitad cara estaba resuelta y la otra no.
                val crossRefs = ArrayList<PlaylistSongCrossRef>(parsed.entries.size)
                for (entry in parsed.entries) {
                    val songId = index.resolve(entry)
                    if (songId == null) {
                        missing++
                        continue
                    }
                    // Un M3U puede repetir la misma canción; la playlist no puede (el cross-ref
                    // tiene clave compuesta). Se conserva la PRIMERA aparición, que es la que fija
                    // su sitio en el orden.
                    if (!seen.add(songId)) continue
                    crossRefs.add(PlaylistSongCrossRef(playlistId, songId, crossRefs.size))
                }
                playlistDao.addSongsToPlaylist(crossRefs)

                M3uImportSummary(playlistId, name, crossRefs.size, missing)
            }
        }

    /**
     * Exporta FAVORITOS, que es una playlist como cualquier otra —la del UUID fijo— y por eso solo
     * hace falta resolver su id. La resolución vive aquí y no en el ViewModel porque el UUID es un
     * detalle del almacenamiento, igual que en el resto de la API de favoritos.
     *
     * Si la lista todavía no existe (`ensureFavoritesPlaylist` corre en el arranque, pero nada
     * garantiza el orden) el resultado es el mismo "no hay nada que exportar" que una lista borrada.
     */
    suspend fun exportFavorites(uri: Uri): AppResult<M3uExportSummary> = withContext(Dispatchers.IO) {
        val playlistId = playlistDao.getPlaylistIdByUuid(AppConfig.FAVORITES_PLAYLIST_UUID)
            ?: return@withContext AppResult.Error(AppError.NotFound("Todavía no hay favoritos"))
        export(playlistId, uri)
    }

    /** Escribe la playlist [playlistId] como M3U8 en [uri] (un documento que el usuario creó). */
    suspend fun export(playlistId: Long, uri: Uri): AppResult<M3uExportSummary> =
        withContext(Dispatchers.IO) {
            runCatchingFile {
                val playlist = playlistDao.getPlaylistById(playlistId)
                    ?: throw NothingToDoException("La lista ya no existe")
                val songs = playlistDao.getSongsForPlaylistList(playlistId).map { it.toSong() }

                var relativePaths = 0
                val entries = songs.mapNotNull { song ->
                    val absolute = pathResolver.absolutePathOf(song)
                    // Sin ruta en el dispositivo se cae al `relativePath`, que es la misma clave con
                    // la que la app deduplica entre fuentes: en el PC de quien sincroniza su música
                    // con OneDrive, esa ruta relativa es exactamente donde está el archivo.
                    val reference = absolute
                        ?: song.relativePath?.takeIf { it.isNotBlank() }?.also { relativePaths++ }
                    reference?.let { path ->
                        M3uEntry(
                            reference = path,
                            title = song.title,
                            artist = song.artist,
                            durationSeconds = (song.duration / MILLIS_PER_SECOND).takeIf { it > 0 }
                        )
                    }
                }

                writeText(uri, M3uFormat.write(M3uPlaylist(playlist.name, entries)))
                M3uExportSummary(written = entries.size, relativePaths = relativePaths)
            }
        }

    /**
     * El nombre visible del documento, sin extensión. `OpenableColumns.DISPLAY_NAME` es la única vía:
     * el `Uri` de un `content://` no contiene el nombre del archivo, y el último segmento de su ruta
     * es un id del proveedor.
     */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { row ->
                if (!row.moveToFirst()) return@use null
                row.getString(row.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    ?.substringBeforeLast('.')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
    }.getOrNull()

    /**
     * Lee el archivo como texto. **La codificación hay que ADIVINARLA**: un `.m3u8` es UTF-8 por
     * definición, pero un `.m3u` a secas es "la página de códigos del sistema que lo escribió", que
     * en la práctica es UTF-8 o Latin-1. Se intenta UTF-8 en modo ESTRICTO —que falla ante bytes
     * inválidos en vez de sembrar el texto de caracteres de reemplazo— y solo entonces se cae a
     * Latin-1, que acepta cualquier byte. Al revés no funcionaría: Latin-1 nunca falla, así que
     * jamás se probaría UTF-8 y todo acento saldría partido en dos.
     */
    private fun readText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("No se pudo abrir el archivo")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    /**
     * `"wt"` y no `"w"`: sin la `t` de truncate, sobrescribir un archivo más largo deja la cola del
     * anterior pegada al final. Es el mismo modo con el que la app reescribe los `.lrc`.
     */
    private fun writeText(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, WRITE_TRUNCATE)?.use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
        } ?: throw IOException("No se pudo escribir el archivo")
    }

    private inline fun <T> runCatchingFile(block: () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (e: NothingToDoException) {
        // `NotFound` y no `Data`: el archivo se leyó perfectamente, lo que no hay es contenido. Es
        // la distinción que deja al ViewModel dar un mensaje útil en vez del error genérico.
        AppResult.Error(AppError.NotFound(e.message.orEmpty()))
    } catch (e: SecurityException) {
        // Permiso del selector caducado: pasa si el Uri se guardó y se reusa más tarde.
        AppResult.Error(AppError.Data("Sin acceso al archivo", e))
    } catch (e: IOException) {
        AppResult.Error(AppError.Data("No se pudo leer el archivo", e))
    } catch (e: Exception) {
        AppResult.Error(AppError.fromException(e))
    }

    /**
     * "No hay nada que hacer", que no es un fallo: un M3U sin entradas, o una lista que se borró
     * mientras el selector estaba abierto.
     */
    private class NothingToDoException(message: String) : IllegalStateException(message)

    private companion object {
        const val WRITE_TRUNCATE = "wt"
        const val MILLIS_PER_SECOND = 1000L
    }
}

/**
 * Encuentra la canción de la biblioteca a la que apunta una línea de M3U.
 *
 * **El problema es que las rutas del archivo casi nunca coinciden con las de la biblioteca**: el
 * M3U puede venir de un PC (`D:\Musica\Rock\x.flac`), de otro teléfono (`/storage/emulated/0/...`)
 * o traer rutas relativas a su propia carpeta (`Rock/x.flac`). Lo único estable entre todos esos
 * casos es el TRAMO FINAL de la ruta, así que la comparación se hace por SUFIJOS DE SEGMENTOS por
 * los dos lados: la biblioteca se indexa por todos los sufijos de cada ruta, y de la referencia se
 * prueban los suyos, del más largo al más corto.
 *
 * **Gana el sufijo más largo que sea ÚNICO, y la ambigüedad no se resuelve acortando**: si
 * `rock/01.flac` ya lo tienen dos canciones, `01.flac` lo tendrán esas dos y alguna más. Cuando el
 * candidato no es único se abandona la ruta y se pasa a los tags del `#EXTINF`, que es la otra
 * información que trae el archivo — elegir "la primera" sería meter en la lista una canción que el
 * usuario no puso.
 *
 * El índice se construye una vez por importación con [SongDao.getPathKeys] y se tira al terminar:
 * son unos pocos MB en una biblioteca grande, a cambio de no hacer una consulta por línea.
 */
private class LibraryPathIndex(keys: List<SongPathKey>) {

    /** Cada sufijo de segmentos de una ruta -> los ids que lo tienen. */
    private val bySuffix = HashMap<String, MutableList<String>>(keys.size * SUFFIXES_PER_PATH_HINT)

    /** `artista` + salto + `titulo`, en minúsculas -> id. Para el rescate por `#EXTINF`. */
    private val byTags = HashMap<String, String>(keys.size)

    init {
        for (key in keys) {
            val relative = key.relativePath?.takeIf { it.isNotBlank() }?.let(::normalizeRelativePath)
            if (relative != null) {
                // El tipo va EXPLÍCITO: la inferencia de un `var` usa el tipo DECLARADO del
                // inicializador (`String?`) y no el del smart cast, así que sin él este `suffix`
                // nace nullable dentro de la rama que ya descartó el null.
                var suffix: String = relative
                while (true) {
                    bySuffix.getOrPut(suffix) { mutableListOf() }.add(key.id)
                    val cut = suffix.indexOf('/')
                    if (cut < 0) break
                    suffix = suffix.substring(cut + 1)
                }
            }
            byTags.putIfAbsent(tagKey(key.artist, key.title), key.id)
        }
    }

    fun resolve(entry: M3uEntry): String? = resolveByPath(entry.reference) ?: resolveByTags(entry)

    private fun resolveByPath(reference: String): String? {
        // Una URL remota no es un archivo de la biblioteca y no hay nada que buscar. Se descarta
        // antes de normalizar para que `http://host/a.mp3` no acabe buscándose como `a.mp3`.
        if (REMOTE_SCHEMES.any { reference.startsWith(it, ignoreCase = true) }) return null

        // `normalizeRelativePath` pasa a minúsculas y convierte las barras de Windows: es lo que
        // hace comparable una ruta escrita en un PC con la que produjo el escaneo del teléfono.
        val path = normalizeRelativePath(decode(reference).removePrefix(FILE_SCHEME))
        if (path.isEmpty()) return null

        var candidate = path
        while (true) {
            bySuffix[candidate]?.let { return it.singleOrNull() }
            val cut = candidate.indexOf('/')
            if (cut < 0) return null
            candidate = candidate.substring(cut + 1)
        }
    }

    private fun resolveByTags(entry: M3uEntry): String? {
        val title = entry.title?.takeIf { it.isNotBlank() } ?: return null
        val artist = entry.artist?.takeIf { it.isNotBlank() } ?: return null
        return byTags[tagKey(artist, title)]
    }

    /**
     * Las rutas de un M3U pueden venir percent-encoded (las escribe así quien las trata como URLs).
     * Un `%` suelto —legítimo en un nombre de archivo— hace fallar al decodificador, y en ese caso
     * la ruta cruda ya era la buena.
     */
    private fun decode(reference: String): String =
        runCatching { URLDecoder.decode(reference, StandardCharsets.UTF_8.name()) }
            .getOrDefault(reference)

    private fun tagKey(artist: String, title: String): String =
        artist.trim().lowercase(Locale.ROOT) + "\n" + title.trim().lowercase(Locale.ROOT)

    private companion object {
        const val FILE_SCHEME = "file://"

        /** Solo para dimensionar el mapa: una ruta de música típica es artista/álbum/archivo. */
        const val SUFFIXES_PER_PATH_HINT = 3

        val REMOTE_SCHEMES = listOf("http://", "https://", "rtsp://", "mms://")
    }
}
