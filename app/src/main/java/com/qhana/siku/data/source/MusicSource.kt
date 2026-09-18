package com.qhana.siku.data.source

import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import kotlinx.coroutines.flow.Flow

/**
 * Abstracción de un proveedor de música (OneDrive, carpeta local, …): el seam con las operaciones
 * específicas del proveedor, de modo que agregar una fuente nueva no obligue a tocar el player ni
 * el pipeline genérico.
 *
 * Cubre el ciclo completo: si la fuente está configurada ([isConfigured] / [isConfiguredFlow]),
 * el DESCUBRIMIENTO ([discover] — delta en la nube, walk en local), la resolución de reproducción
 * ([resolveDownloadUrl]) y la extracción de metadata. `SyncManager` orquesta sin conocer a
 * ninguna implementación concreta.
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
     * Lo mismo que [isConfigured], pero OBSERVABLE: emite en cuanto la fuente se configura o se
     * desconfigura.
     *
     * Las dos formas conviven a propósito y no son redundantes:
     *  - [isConfigured] es la consulta AUTORITATIVA (pregunta a MSAL, lee las preferencias) y es
     *    la que usa el sync, que necesita la verdad del instante y no puede arrancar sobre un
     *    valor por defecto — si el flow aún no se ha sembrado, saltarse OneDrive dejaría la
     *    biblioteca de nube sin escanear.
     *  - Esto es para la UI, que no puede suspender ni debe acordarse de refrescar nada. Antes
     *    solo existía la primera, así que `SourcesViewModel` guardaba un snapshot y lo refrescaba
     *    a mano desde la pantalla: el primer frame de Ajustes → Descargas pintaba el tope de
     *    almacenamiento deshabilitado y con el texto de "solo para nube" hasta que llegaba el
     *    refresco, y cualquier cambio de sesión posterior lo dejaba obsoleto.
     *
     * Cada implementación lo deriva de la MISMA fuente que consulta [isConfigured], así que no
     * pueden divergir.
     */
    val isConfiguredFlow: Flow<Boolean>

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

    /**
     * Metadata (y carátula) SIN traerse el audio: es lo que permite que la biblioteca se vea
     * entera —artista, álbum, portada— en minutos, mucho antes de que las descargas terminen.
     *
     * Cada proveedor decide CÓMO: OneDrive pide los primeros KB del archivo con `Range` y lee la
     * cabecera de tags; otro proveedor podría tener un endpoint de metadata. Devolver `null`
     * significa "esta fuente no sabe hacerlo" y NO es un error: el orquestador sigue con el camino
     * de siempre (los tags se leen al descargar o al reproducir), así que ninguna fuente se rompe
     * por no implementarlo — de ahí el default.
     */
    suspend fun fetchLightMetadata(song: Song): LightMetadata? = null
}

/**
 * Metadata leída sin descargar la canción.
 *
 * @param artwork portada que vino en el mismo trozo que los tags (coste cero).
 * @param fetchArtwork petición aparte para la portada cuando NO cabía en ese trozo. Se expone como
 *        función y no como offset para que el orquestador no tenga que saber nada del formato: la
 *        llama solo si de verdad quiere esa imagen (p. ej. si el álbum aún no tiene ninguna), y
 *        así una portada se pide UNA vez por álbum en lugar de una por canción.
 */
data class LightMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Ver `TagFragment.albumArtists`. */
    val albumArtists: List<String> = emptyList(),
    /** Ver `TagFragment.artists`. */
    val artists: List<String> = emptyList(),
    val genre: String? = null,
    /** 0 si la cabecera no lo declara; entonces la fila conserva el que ya tuviera. */
    val trackNumber: Int = 0,
    /** 0 si la cabecera no lo declara; entonces la fila conserva el que ya tuviera. */
    val year: Int = 0,
    /** 0 si la cabecera no la declara; entonces la fila conserva la duración que ya tuviera. */
    val durationMs: Long = 0L,
    val artwork: ByteArray? = null,
    val fetchArtwork: (suspend () -> ByteArray?)? = null
) {
    /** Sin texto útil no vale la pena escribir en BD (se seguiría viendo "Unknown Artist"). */
    val hasText: Boolean get() = !artist.isNullOrBlank() || !album.isNullOrBlank() || !title.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LightMetadata) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtists == other.albumArtists && artists == other.artists && genre == other.genre &&
            trackNumber == other.trackNumber && year == other.year &&
            (artwork?.contentEquals(other.artwork) ?: (other.artwork == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + albumArtists.hashCode()
        result = 31 * result + artists.hashCode()
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
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

/**
 * La fuente requiere autenticación y esta falló (mapea a SyncOutcome.Failed(isAuthError=true)).
 *
 * @param requiresInteraction el fallo NO se arregla reintentando: la sesión necesita que el usuario
 *   pase por la pantalla del proveedor (refresh token revocado, típicamente tras un cambio de
 *   contraseña). Viaja hasta el banner de la biblioteca porque decide qué ACCIÓN se le ofrece al
 *   usuario — reintentar o volver a entrar—, y ofrecer la primera cuando solo vale la segunda deja
 *   un bucle de error del que no se sale.
 */
class SourceAuthException(
    message: String,
    val requiresInteraction: Boolean = false
) : Exception(message)

/**
 * La carpeta configurada de la fuente no existe. Se distingue de un error de red cualquiera para
 * poder decir QUÉ carpeta falta: el usuario tiene que poder relacionarlo con la que eligió, y un
 * "HTTP 404" no lleva a ninguna parte.
 *
 * El nombre de la carpeta viaja en [folder] y el `message` es SOLO para el log: el texto que ve el
 * usuario sale de `R.string.sync_err_folder_missing`, que está traducido. Cuando el `message` se
 * colaba en la UI (banner de error crítico del sync), en inglés quedaba medio traducido:
 * "Critical error: No se encontró la carpeta …".
 */
class SourceFolderMissingException(val folder: String) : Exception("Folder not found: $folder")
