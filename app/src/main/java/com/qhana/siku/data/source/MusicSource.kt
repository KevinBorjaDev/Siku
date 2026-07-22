package com.qhana.siku.data.source

import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType

/**
 * Abstracción de un proveedor de música (OneDrive, carpeta local, …). Fase 2: seam para las
 * operaciones específicas del proveedor, de modo que agregar una fuente nueva no obligue a
 * tocar el player ni el pipeline genérico.
 *
 * El descubrimiento (scan/delta) se añade a esta interfaz en el siguiente incremento; por ahora
 * cubre la resolución de reproducción y la extracción de metadata, que es lo que el player
 * consumía directo de `OneDriveRepository`.
 */
interface MusicSource {
    /** Tipo de fuente que maneja esta implementación (se rutea por `song.sourceType`). */
    val type: SourceType

    /**
     * ¿El usuario configuró esta fuente? (OneDrive: hay sesión; local: hay carpeta elegida).
     * El orquestador sólo llama a [discover] sobre las fuentes configuradas — así una app
     * "sólo local" no falla por auth, y una "sólo nube" no escanea carpetas inexistentes.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Descubre el contenido de la fuente y aplica los cambios en la BD (upsert/delete):
     * OneDrive = delta de Graph; local (Fase 3) = walk de la carpeta. Reporta progreso y
     * respeta el stop cooperativo vía [ctx]. Devuelve los contadores de la corrida.
     *
     * Lanza [SourceAuthException] si la fuente requiere auth y esta falla.
     */
    suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult

    /**
     * URL/URI fresca para descargar o hacer stream de [song]. Para cloud resuelve una URL
     * firmada (con caché TTL); para fuentes locales devuelve el path tal cual. `null` si no se
     * pudo resolver. [forceRefresh] invalida cualquier caché previa.
     */
    suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean = false): String?

    /** Completa la metadata de [song] leyendo los tags del archivo local ya descargado. */
    suspend fun extractMetadata(song: Song): Song
}

/**
 * Contexto que el orquestador ([com.qhana.siku.data.coordinator.SyncManager]) pasa a
 * [MusicSource.discover]: cómo reportar progreso de escaneo y cómo consultar el stop cooperativo
 * (logout / pull-to-refresh) — infraestructura compartida que vive en el orquestador, no en la fuente.
 */
class DiscoverContext(
    val reportScanning: (found: Int, message: String) -> Unit,
    val isStopped: () -> Boolean
)

/** Contadores de una corrida de [MusicSource.discover]. */
data class DiscoverResult(val added: Int, val deleted: Int)

/** La fuente requiere autenticación y esta falló (mapea a SyncOutcome.Failed(isAuthError=true)). */
class SourceAuthException(message: String) : Exception(message)
