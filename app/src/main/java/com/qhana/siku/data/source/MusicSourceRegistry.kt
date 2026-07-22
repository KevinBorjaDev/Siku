package com.qhana.siku.data.source

import com.qhana.siku.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registro de las fuentes de música. Rutea cada operación a la [MusicSource] correcta según
 * `song.sourceType`, y expone las fuentes **configuradas** para que el orquestador de sync
 * las escanee.
 *
 * Agregar un proveedor nuevo = implementar [MusicSource] y sumarlo a [sources].
 */
@Singleton
class MusicSourceRegistry @Inject constructor(
    oneDriveSource: OneDriveMusicSource,
    localSource: LocalMusicSource
) {
    private val sources: List<MusicSource> = listOf(oneDriveSource, localSource)

    /**
     * Fuentes que el usuario configuró (OneDrive con sesión, local con carpeta elegida).
     * Las no configuradas se saltan: así una biblioteca "sólo local" no falla por auth.
     */
    suspend fun activeSources(): List<MusicSource> = sources.filter { it.isConfigured() }

    /** Fuente que maneja [song]; cae a la primera si el sourceType no matchea (compat). */
    fun sourceFor(song: Song): MusicSource =
        sources.firstOrNull { it.type == song.sourceType } ?: sources.first()

    suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean = false): String? =
        sourceFor(song).resolveDownloadUrl(song, forceRefresh)

    suspend fun extractMetadata(song: Song): Song = sourceFor(song).extractMetadata(song)
}
