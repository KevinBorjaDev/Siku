package com.qhana.siku.data.playlist

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.qhana.siku.data.lyrics.AudioLocation
import com.qhana.siku.data.lyrics.AudioLocationResolver
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.source.VolumeNames
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La ruta con la que OTRO programa encontraría el archivo de una canción: lo que hace falta para
 * escribir un M3U y lo único que un M3U sabe decir.
 *
 * **No se puede derivar del `song.id`**, aunque el id local ES una ruta: `normalizeRelativePath` lo
 * pasa a minúsculas, y una ruta en minúsculas no existe en un sistema de archivos sensible a
 * mayúsculas (el `/storage` de Android lo es para todo lo que no pase por FUSE). Ese lowercase es
 * correcto donde vive —hace que el id no dependa de cómo escribiera el nombre cada fuente— pero
 * convierte al id en una clave, no en una dirección. La dirección real solo la tiene `song.path`.
 */
@Singleton
class MediaFilePathResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioLocationResolver: AudioLocationResolver
) {

    /**
     * Ruta ABSOLUTA en el almacenamiento compartido, o `null` si la canción no vive ahí.
     *
     * Devuelve `null` a propósito para las canciones de nube, **incluidas las ya descargadas**: su
     * archivo está en el sandbox de la app (`filesDir/music`), donde ningún otro reproductor puede
     * leerlo y cuyo nombre es el id namespaced y no el del archivo original. Escribir esa ruta en
     * un M3U sería una línea que no resuelve en ningún sitio; quien exporta debe caer al
     * `relativePath`, que sí significa algo en el otro dispositivo.
     */
    fun absolutePathOf(song: Song): String? = when (val location = audioLocationResolver.resolve(song)) {
        is AudioLocation.SafDocument -> absolutePathOfDocument(location.uri)
        is AudioLocation.MediaStoreItem -> absolutePathOfMediaStoreItem(location.uri)
        // `AppFile` cubre dos casos que aquí se comportan igual: la descarga de nube en el sandbox
        // y —si alguna vez volviera a haberla— una canción local indexada como `file://`. La
        // segunda sí tendría ruta compartida, así que se comprueba en vez de descartarla.
        is AudioLocation.AppFile ->
            location.file.absolutePath.takeIf { it.startsWith("${VolumeNames.STORAGE_ROOT}/") }
        AudioLocation.Cloud -> null
    }

    /**
     * `primary:Music/Rock/x.flac` → `/storage/emulated/0/Music/Rock/x.flac`.
     *
     * El docId conserva las mayúsculas del nombre real, que es justo lo que el id de la biblioteca
     * perdió. Un proveedor de terceros con ids opacos (sin el `volumen:ruta` de
     * `ExternalStorageProvider`) no tiene ruta que dar y devuelve `null`.
     */
    private fun absolutePathOfDocument(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1).trim('/')
        if (relative.isEmpty()) return null
        return "${rootOfVolume(volume)}/$relative"
    }

    /**
     * La ruta de una fila de MediaStore. Se pide `DATA` —que está DEPRECADA— porque es la única
     * columna que da la ruta completa tal cual: `RELATIVE_PATH` (API 29+) no incluye la raíz del
     * volumen y hay que recomponerla, así que sirve de respaldo y no de primera opción. La
     * deprecación no la retira: el sistema la sigue rellenando para los archivos que tienen una.
     */
    private fun absolutePathOfMediaStoreItem(uri: Uri): String? {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(DATA_COLUMN, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.VOLUME_NAME)
        } else {
            arrayOf(DATA_COLUMN)
        }
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { row ->
                if (!row.moveToFirst()) return@use null
                row.getString(row.getColumnIndexOrThrow(DATA_COLUMN))
                    ?.takeIf { it.isNotBlank() }
                    ?: recomposeMediaStorePath(row)
            }
        }.getOrNull()
    }

    private fun recomposeMediaStorePath(row: android.database.Cursor): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relative = row.getString(row.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH))
            ?.trim('/') ?: return null
        val name = row.getString(row.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
            ?: return null
        val volume = row.getString(row.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME))
            ?: VolumeNames.MEDIASTORE_PRIMARY
        return "${rootOfVolume(volume)}/$relative/$name"
    }

    /**
     * Raíz de un volumen. `primary` (SAF) y `external_primary` (MediaStore) son el MISMO
     * almacenamiento con dos nombres — la misma unificación que hace `canonicalVolume` al construir
     * los ids, escrita aquí otra vez porque aquélla es `private` y va en la dirección contraria.
     * **Si una cambia, la otra también.**
     */
    private fun rootOfVolume(volume: String): String {
        val canonical = VolumeNames.canonical(volume)
        if (canonical == VolumeNames.SAF_PRIMARY) {
            return Environment.getExternalStorageDirectory()?.absolutePath ?: PRIMARY_FALLBACK_ROOT
        }
        // Volumen secundario (tarjeta SD): su nombre ES el identificador bajo /storage. Se usa el
        // nombre CRUDO y no el canónico, que va en minúsculas: el UUID de una tarjeta lleva
        // mayúsculas (`1A2B-3C4D`) y en minúsculas ese directorio no existe.
        return "${VolumeNames.STORAGE_ROOT}/$volume"
    }

    private companion object {
        @Suppress("DEPRECATION")
        val DATA_COLUMN: String = MediaStore.Audio.Media.DATA

        /** Solo si `getExternalStorageDirectory()` fallara: es la ruta que monta todo Android. */
        const val PRIMARY_FALLBACK_ROOT = "/storage/emulated/0"
    }
}
