package com.qhana.siku.data.source

import java.util.Locale

/**
 * Los nombres de volumen de almacenamiento, en UN solo sitio.
 *
 * Existe porque el mismo almacenamiento tiene DOS nombres según quién lo diga —SAF lo llama
 * `primary` y MediaStore `external_primary`— y esa equivalencia se necesita en dos direcciones
 * opuestas: al construir el id de una canción (`LocalMusicSource`, ruta → clave) y al reconstruir
 * su ruta para escribirla en un M3U (`MediaFilePathResolver`, clave → ruta). Escrita dos veces,
 * bastaba con arreglar una para que el mismo archivo dejara de encontrarse por el otro camino.
 */
internal object VolumeNames {

    /** Como lo nombra SAF, y el nombre CANÓNICO dentro de la app (el que va en los ids). */
    const val SAF_PRIMARY = "primary"

    /** Como lo nombra MediaStore (`MediaStore.Audio.Media.VOLUME_NAME`). */
    const val MEDIASTORE_PRIMARY = "external_primary"

    /** Raíz bajo la que el sistema monta los volúmenes secundarios (tarjetas SD). */
    const val STORAGE_ROOT = "/storage"

    /**
     * Nombre canónico: unifica los dos alias del almacenamiento principal. Sin esto, el mismo
     * archivo tendría dos ids según el modo de escaneo.
     */
    fun canonical(volume: String): String {
        val lower = volume.lowercase(Locale.ROOT)
        return if (lower == MEDIASTORE_PRIMARY) SAF_PRIMARY else lower
    }
}
