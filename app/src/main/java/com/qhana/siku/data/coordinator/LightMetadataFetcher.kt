package com.qhana.siku.data.coordinator

import android.util.Log
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.encodeMultiValue
import com.qhana.siku.data.local.albumIdentityKey
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rellena artista/álbum/título/género y carátula de las canciones de NUBE **sin descargar el
 * audio**, leyendo solo la cabecera del archivo remoto (ver `MusicSource.fetchLightMetadata`).
 *
 * Es lo que hace que la biblioteca se vea entera —y con portadas— en minutos: corre ANTES del
 * pipeline de descargas, que es lento y está acotado por el tope de almacenamiento. Sin esta
 * fase, las canciones que el tope deja sin descargar se quedan como "Unknown Artist" y colapsan
 * todas en un mismo grupo de la pestaña Artistas (el síntoma que la motivó).
 *
 * Genérico por diseño: no conoce OneDrive ni formatos. Pregunta a la fuente vía el registro y
 * escribe en Room. Un proveedor que no implemente `fetchLightMetadata` (devuelve null) hace que
 * sus canciones se salten esta fase sin ruido — la metadata llegará al descargar, como siempre.
 */
@Singleton
class LightMetadataFetcher @Inject constructor(
    private val musicRepository: IMusicRepository,
    private val sourceRegistry: MusicSourceRegistry,
    private val audioFileAnalyzer: AudioFileAnalyzer
) {

    /**
     * @param isStopped stop cooperativo (logout / pull-to-refresh): se consulta por canción.
     * @param onProgress (procesadas, total) tras CADA canción. Esta fase puede durar varios
     *        minutos —dos peticiones por canción, y las de Graph van serializadas por el rate
     *        limiter—, así que sin este aviso el banner se queda en el paso anterior todo ese
     *        rato y la app aparenta estar colgada. Cuenta canciones MIRADAS, no actualizadas:
     *        es lo que hace que el número avance de forma pareja aunque muchas no aporten nada.
     * @return cuántas filas se actualizaron con texto.
     */
    suspend fun run(
        isStopped: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int = coroutineScope {
        val pending = musicRepository.getSongsNeedingLightMetadata()
        if (pending.isEmpty()) return@coroutineScope 0

        Log.i(TAG, "Metadata ligera: ${pending.size} canciones pendientes")
        // Portada YA conocida de cada álbum en esta corrida: evita que 12 canciones del mismo
        // disco disparen 12 peticiones de la misma imagen. Guarda el URI y no solo el nombre
        // porque también sirve para REPARTIRLO a las hermanas que aún no lo tengan. La BD es la
        // otra mitad del gate (getAlbumArtUri), para álbumes resueltos en un scan anterior.
        val albumArtThisRun = java.util.concurrent.ConcurrentHashMap<String, String>()
        val updated = java.util.concurrent.atomic.AtomicInteger(0)
        val processed = java.util.concurrent.atomic.AtomicInteger(0)
        // Canciones cuya cabecera se leyó ENTERA y no traía imagen. Se sellan al final, en una
        // sola escritura, para que dejen de aparecer como pendientes en cada sync.
        val noArtwork = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Lo mismo para los TAGS de texto: la cabecera contestó y no traía ninguno. Sin este sello,
        // un archivo sin tags vuelve en cada sync mientras siga sin descargar (ver
        // `SongEntity.lightTagsAttemptedAt`) — era el banner "Leyendo datos 1 de 4" de cada arranque.
        val noTags = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(PARALLELISM)

        onProgress(0, pending.size)

        val jobs = pending.map { song ->
            launch(Dispatchers.IO) {
                if (isStopped()) return@launch
                // El contador va en un `finally` que envuelve el permiso entero, no al final del
                // camino feliz: muchas canciones salen antes de tiempo (atajo sin red, fuente sin
                // tags, stop), y contarlas solo al terminar bien dejaba el progreso parado justo
                // cuando más deprisa se estaba avanzando.
                try {
                    semaphore.withPermit {
                        if (isStopped()) return@withPermit
                        processSong(song, albumArtThisRun, noArtwork, noTags, updated, isStopped)
                    }
                } finally {
                    onProgress(processed.incrementAndGet(), pending.size)
                }
            }
        }
        jobs.forEach { it.join() }
        if (noArtwork.isNotEmpty()) musicRepository.markArtworkAttempted(noArtwork.toList())
        if (noTags.isNotEmpty()) musicRepository.markLightTagsAttempted(noTags.toList())
        val total = updated.get()
        Log.i(TAG, "Metadata ligera: $total actualizadas, ${noArtwork.size} sin portada, ${noTags.size} sin tags")
        total
    }

    /**
     * Resuelve UNA canción. Extraída del bucle a propósito: dentro del `launch` solo queda el
     * permiso y el contador, así que se ve de un vistazo que el progreso avanza pase lo que pase.
     *
     * Traga sus excepciones (salvo la cancelación): el fallo de una canción no debe llevarse por
     * delante la fase entera, y lo que no se resuelva sigue pendiente para el próximo sync.
     */
    private suspend fun processSong(
        song: Song,
        albumArtThisRun: MutableMap<String, String>,
        noArtwork: MutableSet<String>,
        noTags: MutableSet<String>,
        updated: java.util.concurrent.atomic.AtomicInteger,
        isStopped: () -> Boolean
    ) {
        try {
            // Atajo SIN red: a esta fila solo le falta la portada y su álbum ya tiene una
            // conocida, así que basta con repartirla. Importa porque es el caso mayoritario de
            // las que entran aquí por carátula pendiente y no por falta de tags — sin él, pedir
            // la cabecera para acabar ejecutando un UPDATE sería una petición HTTP tirada.
            if (song.artist != AppConfig.UNKNOWN_ARTIST && !AppConfig.isUnknownAlbum(song.album)) {
                val albumKey = song.albumArtist.orEmpty()
                val runKey = albumIdentityKey(song.album, albumKey)
                val known = albumArtThisRun[runKey] ?: musicRepository.getAlbumArtUri(song.album, albumKey)
                if (known != null) {
                    musicRepository.setAlbumArt(song.album, albumKey, known)
                    albumArtThisRun[runKey] = known
                    return
                }
            }

            val meta = sourceRegistry.fetchLightMetadata(song) ?: return
            val album = meta.album?.takeIf { it.isNotBlank() } ?: song.album

            // Los tags van PRIMERO: son lo que hace la biblioteca legible, y no deben quedar
            // rehenes de que la portada (que a menudo cuesta una segunda petición) llegue bien.
            // Que una fila se quede con sus tags y sin imagen ya no la condena: sigue siendo
            // candidata de esta fase por la carátula pendiente, no solo por el artista.
            // El artista del álbum que la fila tendrá tras esta lectura: es la mitad de la identidad del
            // disco con la que se reparte la portada. Sin texto, la fila no se toca y conserva el suyo.
            val albumArtistKey = if (meta.hasText) encodeMultiValue(meta.albumArtists) ?: "" else song.albumArtist.orEmpty()
            if (meta.hasText) {
                musicRepository.updateLightMetadata(
                    songId = song.id,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: song.title,
                    artist = meta.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                    album = album,
                    genre = meta.genre?.takeIf { it.isNotBlank() },
                    trackNumber = meta.trackNumber,
                    year = meta.year,
                    durationMs = meta.durationMs,
                    // `""` sella "cabecera leída, sin ALBUMARTIST": la canción sale así de la lista
                    // de `ArtistTagsBackfiller` sin pedir la misma cabecera otra vez.
                    albumArtist = albumArtistKey,
                    artistCredits = encodeMultiValue(meta.artists)
                )
                updated.incrementAndGet()
            } else if (song.artist == AppConfig.UNKNOWN_ARTIST) {
                // La cabecera CONTESTÓ y no traía ni un tag de texto: hecho definitivo para esta
                // fase, así que se sella y deja de entrar en la lista. Solo importa cuando la fila
                // está aquí POR los tags (artista centinela); si vino solo por la carátula, la
                // primera condición de la consulta no la mira y el UPDATE sería inútil.
                //
                // El otro sello (`needsMetadata = 0`) no cubre este caso porque solo llega con el
                // análisis del archivo completo, o sea tras DESCARGARLO: hasta entonces esta misma
                // canción volvía a pedirse en cada arranque en frío para no encontrar nada.
                noTags.add(song.id)
            }

            when (applyArtwork(song.id, album, albumArtistKey, meta, albumArtThisRun, isStopped)) {
                // La cabecera contestó: este archivo no tiene portada. Sellarlo es lo que
                // mantiene barata la fase — sin esto, cada sync volvería a pedir la cabecera de
                // todo álbum que legítimamente no trae imagen.
                ArtOutcome.NO_ART -> noArtwork.add(song.id)
                // Resuelta (la fila ya tiene URI y sale sola de la lista) o no se pudo mirar:
                // en ninguno de los dos casos hay nada que sellar.
                ArtOutcome.RESOLVED, ArtOutcome.UNAVAILABLE -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Metadata ligera falló para ${song.title}: ${e.message}")
        }
    }

    /**
     * Veredicto de la portada, con la misma distinción de tres estados que
     * `ArtworkHealingManager.Extraction`: sellar "ya lo sé" exige que la fuente haya CONTESTADO,
     * no solo que se le haya preguntado.
     */
    private enum class ArtOutcome {
        /** La fila quedó con carátula. */
        RESOLVED,
        /** La cabecera se leyó entera y el archivo no trae imagen: hecho definitivo. */
        NO_ART,
        /** No se pudo averiguar (petición fallida, escritura fallida, stop): se reintenta. */
        UNAVAILABLE
    }

    /**
     * Persiste la portada del álbum UNA sola vez. Si vino en el mismo trozo que los tags, coste
     * cero; si no cabía, [com.qhana.siku.data.source.LightMetadata.fetchArtwork] hace la segunda
     * petición del rango exacto — pero solo cuando el álbum aún no tiene imagen (ni en BD ni en
     * esta corrida), que es lo que la vuelve una petición por álbum en vez de por canción.
     *
     * Una canción SIN álbum no comparte con nadie (ver [AppConfig.isUnknownAlbum]): su portada
     * se escribe solo en su fila. Cuesta una petición por canción en vez de una por álbum, y es
     * el precio correcto — agruparlas por el centinela repartía la portada del primer archivo
     * sin tags entre todos los demás.
     */
    private suspend fun applyArtwork(
        songId: String,
        album: String,
        albumArtist: String,
        meta: com.qhana.siku.data.source.LightMetadata,
        albumArtThisRun: MutableMap<String, String>,
        isStopped: () -> Boolean
    ): ArtOutcome {
        val groupable = !AppConfig.isUnknownAlbum(album)
        val runKey = albumIdentityKey(album, albumArtist)

        // El álbum ya tiene portada conocida: se REPARTE, no se da por hecho que esta fila la
        // tenga. Antes, saber que el disco tenía imagen solo servía para ahorrarse la petición
        // y la canción se quedaba sin nada — el ahorro estaba bien, la conclusión no.
        if (groupable) {
            val known = albumArtThisRun[runKey] ?: musicRepository.getAlbumArtUri(album, albumArtist)
            if (known != null) {
                musicRepository.setAlbumArt(album, albumArtist, known)
                albumArtThisRun[runKey] = known
                return ArtOutcome.RESOLVED
            }
        }

        // if/else explícito y no un `?: run { }`: esta clase tiene su propio método `run`, que
        // como miembro gana a `kotlin.run` en la resolución — el lambda se pasaría como el
        // `isStopped: () -> Boolean` de ese método y todo lo de dentro (returns no locales,
        // llamada suspend) dejaría de compilar por motivos que no se leen en el error.
        val embedded = meta.artwork
        val artBytes = if (embedded != null) {
            embedded
        } else {
            // `fetchArtwork == null` es la fuente diciendo que leyó la cabecera y no había
            // bloque de imagen; que la petición devuelva null es que no se pudo traer. Lo
            // primero es concluyente y lo segundo no, y por eso no comparten camino.
            val fetch = meta.fetchArtwork ?: return ArtOutcome.NO_ART
            if (isStopped()) return ArtOutcome.UNAVAILABLE
            fetch.invoke() ?: return ArtOutcome.UNAVAILABLE
        }

        val uri = withContext(Dispatchers.IO) {
            audioFileAnalyzer.persistArtwork(artBytes)
        } ?: return ArtOutcome.UNAVAILABLE

        if (groupable) {
            // Compartir con todo el álbum, sin pisar las que ya tuvieran carátula propia.
            musicRepository.setAlbumArt(album, albumArtist, uri)
            albumArtThisRun[runKey] = uri
        } else {
            musicRepository.updateAlbumArtUri(songId, uri)
        }
        return ArtOutcome.RESOLVED
    }

    private companion object {
        const val TAG = "LightMetadataFetcher"
        /**
         * Peticiones concurrentes. Igual que las descargas, OneDrive limita el ancho de banda
         * POR CONEXIÓN, pero aquí cada petición es de ~256 KB (no de 28 MB), así que el cuello no
         * es el ancho de banda sino la latencia de ida y vuelta: 6 en paralelo satura bien el
         * enlace sin acercarse a los límites de throttling de Graph.
         */
        const val PARALLELISM = 6
    }
}
