package com.qhana.siku.data.repository

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.ArtistDao
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.remote.DeezerApi
import com.qhana.siku.data.remote.DeezerArtistDto
import com.qhana.siku.data.util.NetworkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Candidato de artista devuelto por la búsqueda de Deezer (para el picker manual).
 */
data class DeezerArtistCandidate(
    val deezerId: Long,
    val name: String,
    val imageUrl: String?,
    val thumbUrl: String?
)

/**
 * Fotos de artista vía Deezer con cache persistente en la tabla `artists`.
 *
 * - Auto-match perezoso ([ensureArtistImage]): primer resultado de la búsqueda; nunca
 *   pisa una selección manual y cachea los not-found con espera CRECIENTE (14 días el
 *   primero, el doble en cada fallo siguiente hasta ~7 meses).
 * - Selección manual ([setManualArtist]): elegida en el picker, marcada `manuallySet`.
 * - Los binarios los cachea el ImageLoader global de Coil (DiskCache 500MB); aquí solo
 *   se persisten URLs.
 *
 * **Los tres estados de "sin foto" son distintos y confundirlos fue el bug del banner**:
 * nunca preguntado (hay una foto por ganar), preguntado y ausente del catálogo (no la hay,
 * y reintentarlo es tráfico tirado) y rechazado a mano en el picker (`manuallySet` sin URL:
 * no la hay POR DECISIÓN y nadie debe tocarla). El banner de red medida solo puede hablar
 * del primero — ver [shouldOfferMeteredBackfill].
 */
@Singleton
class ArtistImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artistDao: ArtistDao,
    private val deezerApi: DeezerApi,
    private val networkManager: NetworkManager,
    private val musicPreferences: MusicPreferences
) {
    companion object {
        /** Espera tras el PRIMER not-found: igual que el TTL de letras (14 días). */
        private const val NOT_FOUND_BASE_TTL_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Tope del backoff: `base · 2^shift`, o sea 14 · 16 ≈ 224 días entre reintentos de un
         * artista que Deezer nunca ha tenido. No es infinito a propósito — el catálogo crece y
         * un artista pequeño puede aparecer más adelante —, pero pasa de ~26 consultas inútiles
         * al año a menos de dos.
         */
        private const val MAX_BACKOFF_SHIFT = 4

        /** Deezer limita ~50 req/5s por IP; 3 fetches concurrentes es más que suficiente. */
        private const val MAX_CONCURRENT_FETCHES = 3
    }

    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    /** Artistas ya intentados en esta sesión de proceso (evita re-consultas al scrollear). */
    private val attemptedThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Sesión: el usuario aceptó gastar datos móviles en fotos ("Descargar" del banner). */
    @Volatile
    private var meteredAllowedThisSession = false

    /** Sesión: el usuario descartó el banner ("Ahora no") — no volver a ofrecerlo. */
    @Volatile
    private var meteredBannerDismissed = false

    private val _meteredBackfillPending = MutableStateFlow(false)

    /**
     * true = hay fotos por resolver pero el backfill se abstuvo por estar en red medida
     * (datos móviles). Alimenta el banner de la pestaña Artistas, que ofrece
     * [resumeBackfillOnMetered] / [dismissMeteredBackfillBanner].
     */
    val meteredBackfillPending: StateFlow<Boolean> = _meteredBackfillPending.asStateFlow()

    /**
     * Fetch PEREZOSO e idempotente de la foto de un artista. Pensado para llamarse
     * fire-and-forget desde la UI cuando el artista se muestra; nunca lanza.
     */
    suspend fun ensureArtistImage(artistName: String) {
        // El placeholder de "sin tag de artista" NO es un artista: buscarlo en Deezer
        // devuelve un primer match arbitrario que quedaba persistido como su foto.
        // "Various Artists" tampoco: es el artista de una recopilación, no alguien con cara. El mismo
        // criterio va en el SQL de pendientes (`ArtistDao`) — si uno cambia, cambia el otro.
        if (artistName.isBlank() || artistName == AppConfig.UNKNOWN_ARTIST) return
        if (AppConfig.isVariousArtists(artistName)) return
        if (!attemptedThisSession.add(artistName)) return

        val existing = artistDao.getArtist(artistName)
        if (existing != null) {
            if (existing.manuallySet || existing.imageUrl != null) return
            val fetchedAt = existing.fetchedAt
            if (fetchedAt != null &&
                System.currentTimeMillis() < nextRetryAt(fetchedAt, existing.notFoundAttempts)
            ) return
        }

        fetchSemaphore.withPermit {
            try {
                val match = deezerApi.searchArtists(artistName, limit = 1).data?.firstOrNull()
                if (match != null) {
                    val fullUrl = match.pictureXl ?: match.pictureBig
                    val thumbUrl = match.pictureMedium
                    artistDao.upsertArtist(
                        ArtistEntity(
                            name = artistName,
                            deezerId = match.id,
                            imageUrl = fullUrl,
                            thumbUrl = thumbUrl,
                            manuallySet = false,
                            fetchedAt = System.currentTimeMillis(),
                            // Encontrado: el contador vuelve a cero. Si más adelante la foto se
                            // borrase a mano, el conteo empieza limpio en vez de heredar fallos
                            // viejos y saltar directo a una espera de meses.
                            notFoundAttempts = 0
                        )
                    )
                    // Prebajar los BINARIOS a la caché de Coil, no solo las URLs. El gate de red
                    // medida ya se pasó ARRIBA (backfill / on-demand son los únicos que llegan
                    // aquí), así que si estamos en este punto se puede gastar. Sin esto, el gate
                    // protegía solo el JSON de URLs (unos KB) mientras la imagen —lo caro— se bajaba
                    // igual, perezosa y SIN gate, al mostrarla. Se prebajan las DOS resoluciones que
                    // usa la UI: thumb (listas) y grande (header del detalle).
                    prefetchArtistImage(thumbUrl)
                    prefetchArtistImage(fullUrl)
                } else {
                    // Not-found: persistir el intento y ALARGAR la espera. Es lo que hace que un
                    // artista ausente del catálogo deje de consultarse cada 14 días de por vida.
                    artistDao.upsertArtist(
                        ArtistEntity(
                            name = artistName,
                            fetchedAt = System.currentTimeMillis(),
                            notFoundAttempts = (existing?.notFoundAttempts ?: 0) + 1
                        )
                    )
                }
            } catch (_: Exception) {
                // Error de red: NO escribir fetchedAt — se reintentará en otra sesión. Que la
                // fila siga sin `fetchedAt` es además lo que mantiene a este artista contando
                // como "nunca preguntado" para el banner: hay una foto que sigue por ganar.
                attemptedThisSession.remove(artistName)
            }
        }
    }

    /**
     * Encola la descarga del binario a la caché de Coil (la MISMA que lee `AsyncImage`: el
     * singleton de Coil es el `ImageLoader` inyectado — ver `MusicPlayerApp.newImageLoader`). Es
     * fire-and-forget: `enqueue` vuelve al instante y la descarga corre en los dispatchers de Coil,
     * con su propia concurrencia (no retiene el permiso del [fetchSemaphore], que es solo para las
     * consultas a Deezer). Si falla, la imagen se bajará perezosa al mostrarla, como antes.
     *
     * Sin tamaño ni transformación: la caché de DISCO guarda el binario ORIGINAL por URL, del que
     * luego cada superficie deriva su bitmap (el thumb con su forma cookie, el header a pantalla).
     */
    private fun prefetchArtistImage(url: String?) {
        if (url.isNullOrBlank()) return
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .build()
        )
    }

    /**
     * Instante a partir del cual vuelve a tocar preguntar por un artista sin foto.
     *
     * Duplica a propósito la fórmula de `ArtistDao.getArtistNamesNeedingImage`, porque las dos
     * rutas son distintas: aquella filtra el lote del backfill DENTRO de SQL (traerse la tabla
     * entera para decidirlo en Kotlin sería absurdo) y ésta gobierna la petición suelta de
     * [ensureArtistImageOnDemand], que ni pasa por esa consulta. **Si se toca una, hay que
     * tocar la otra** — igual que `EqCurve` y el processor.
     */
    private fun nextRetryAt(fetchedAt: Long, notFoundAttempts: Int): Long =
        fetchedAt + NOT_FOUND_BASE_TTL_MS * (1L shl (notFoundAttempts - 1).coerceIn(0, MAX_BACKOFF_SHIFT))

    /**
     * Backfill data-driven de TODAS las fotos pendientes de la biblioteca: la lista sale de
     * la BD, no de qué filas llegue a mostrar la UI. Reutiliza [ensureArtistImage], así que
     * hereda idempotencia, espera de not-found y rate-limit — es seguro dispararlo varias veces
     * y desde varios sitios (la biblioteca al componerse, el fin de cada sync, un cambio de red).
     * El [Semaphore] hace de límite de concurrencia real: se lanza una corrutina por artista pero
     * solo [MAX_CONCURRENT_FETCHES] tocan red a la vez.
     *
     * **Una pasada a la vez** ([backfillRunning]): las llamadas que caen con otra en marcha se van
     * en el acto. `ensureArtistImage` ya impedía que se duplicaran las PETICIONES —tiene su propio
     * `attemptedThisSession`—, pero no el trabajo de alrededor: dos disparos casi simultáneos hacían
     * dos veces la consulta de pendientes y lanzaban dos veces una corrutina por artista, que en una
     * biblioteca grande son cientos que solo sirven para descubrir que no hay nada que hacer. No se
     * pierde nada por saltar: quien llega segundo quiere exactamente lo que el primero está
     * haciendo, y los disparos posteriores (red, sync) siguen cubriendo lo que quede.
     */
    suspend fun backfillMissingImages(): Unit = coroutineScope {
        if (!backfillRunning.compareAndSet(false, true)) return@coroutineScope
        try {
            runBackfill()
        } finally {
            backfillRunning.set(false)
        }
    }

    /** Ver [backfillMissingImages]: una pasada a la vez. */
    private val backfillRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun runBackfill(): Unit = coroutineScope {
        val pending = artistDao.getArtistNamesNeedingImage(
            now = System.currentTimeMillis(),
            baseTtlMs = NOT_FOUND_BASE_TTL_MS,
            maxBackoffShift = MAX_BACKOFF_SHIFT,
            unknownArtist = AppConfig.UNKNOWN_ARTIST,
            variousArtists = AppConfig.VARIOUS_ARTISTS_NAMES
        )
        if (pending.isEmpty()) {
            _meteredBackfillPending.value = false
            return@coroutineScope
        }
        // Sin red no hay banner (el mensaje "estás en red móvil" sería mentira): se
        // reintenta en el próximo disparo. En red MEDIDA la pasada masiva (cientos de
        // JSONs la primera vez) no corre sin permiso: puntual del banner ("Descargar",
        // sesión) o permanente del ajuste "descargar con datos" (Ajustes → Descargas).
        if (!networkManager.isAvailable()) {
            _meteredBackfillPending.value = false
            return@coroutineScope
        }
        val meteredAllowed = meteredAllowedThisSession || musicPreferences.loadArtistPhotosOnMetered()
        if (!networkManager.isWifi() && !meteredAllowed) {
            _meteredBackfillPending.value = shouldOfferMeteredBackfill()
            return@coroutineScope
        }
        _meteredBackfillPending.value = false
        pending.forEach { name ->
            launch { ensureArtistImage(name) }
        }
    }

    /**
     * ¿Vale la pena pedirle datos móviles al usuario?
     *
     * Solo si hay artistas a los que NUNCA se preguntó — los únicos por los que se puede
     * prometer una foto. Que haya "pendientes" no basta: la lista incluye los reintentos de
     * artistas que Deezer ya dijo no tener, y ofrecer megas por ellos es cobrar por nada. Ese
     * matiz es lo que separa "faltan fotos" de "faltan fotos CONSEGUIBLES", y sin él bastaba un
     * artista fuera del catálogo para que el banner reapareciera cada vez que vencía su TTL.
     */
    private suspend fun shouldOfferMeteredBackfill(): Boolean {
        if (meteredBannerDismissed || !musicPreferences.loadArtistPhotosBannerEnabled()) return false
        return artistDao.countArtistsNeverAttempted(AppConfig.UNKNOWN_ARTIST, AppConfig.VARIOUS_ARTISTS_NAMES) > 0
    }

    /** "Descargar" del banner: habilita la red medida para esta sesión y corre el backfill. */
    suspend fun resumeBackfillOnMetered() {
        meteredAllowedThisSession = true
        _meteredBackfillPending.value = false
        backfillMissingImages()
    }

    /** "Ahora no" del banner: lo oculta el resto de la sesión (un WiFi futuro resuelve solo). */
    fun dismissMeteredBackfillBanner() {
        meteredBannerDismissed = true
        _meteredBackfillPending.value = false
    }

    /**
     * Oculta un banner ya visible SIN marcar el dismiss de sesión (para cuando Ajustes
     * desactiva el banner o habilita los datos: si el usuario revierte el ajuste, el
     * próximo disparo del backfill re-evalúa y puede volver a mostrarlo).
     */
    fun clearMeteredBannerPending() {
        _meteredBackfillPending.value = false
    }

    /**
     * Fetch de UN artista con intención directa del usuario (abrir su detalle). Gateado
     * por el ajuste "foto al abrir un artista" cuando la red es medida; en WiFi siempre.
     */
    suspend fun ensureArtistImageOnDemand(artistName: String) {
        if (!networkManager.isWifi() &&
            !musicPreferences.loadArtistPhotoDetailOnMetered() &&
            !meteredAllowedThisSession
        ) return
        ensureArtistImage(artistName)
    }

    /** Candidatos para el picker manual (no toca la BD). */
    suspend fun searchCandidates(artistName: String): Result<List<DeezerArtistCandidate>> {
        return try {
            val results = deezerApi.searchArtists(artistName).data.orEmpty()
                .map { it.toCandidate() }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Guarda la elección manual del picker; el auto-match nunca la pisa. */
    suspend fun setManualArtist(artistName: String, candidate: DeezerArtistCandidate) {
        artistDao.upsertArtist(
            ArtistEntity(
                name = artistName,
                deezerId = candidate.deezerId,
                imageUrl = candidate.imageUrl,
                thumbUrl = candidate.thumbUrl,
                manuallySet = true,
                fetchedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * "Ninguno de estos" del picker: el artista se queda SIN foto, y a propósito. Se guarda como
     * `manuallySet` sin URL, que es lo que hace que la decisión aguante: tanto [ensureArtistImage]
     * como el backfill saltan las filas manuales, así que el auto-match no volverá a ponerle la
     * foto equivocada que el usuario acaba de rechazar. Sin esa marca, una fila con `imageUrl`
     * nula es indistinguible de un not-found y se reintentaría a los 14 días.
     */
    suspend fun clearArtistImage(artistName: String) {
        artistDao.upsertArtist(
            ArtistEntity(
                name = artistName,
                manuallySet = true,
                fetchedAt = System.currentTimeMillis()
            )
        )
    }

    private fun DeezerArtistDto.toCandidate() = DeezerArtistCandidate(
        deezerId = id,
        name = name.orEmpty(),
        imageUrl = pictureXl ?: pictureBig,
        thumbUrl = pictureMedium
    )

    /**
     * Re-dispara el backfill cada vez que cambia la red, MIENTRAS alguien lo colecte.
     *
     * Convención "esperar por SEÑAL, no por intervalo": el estado del backfill depende de la red, así
     * que se re-evalúa cuando la red cambia. Las dos direcciones hacían falta: al llegar el WiFi las
     * fotos en pausa se resuelven solas (antes había que esperar al siguiente sync o a reabrir la
     * app, con el banner mintiendo mientras tanto), y al pasar a datos el banner se decide con el
     * estado real en vez de quedarse en el que dejó la red anterior. `drop(1)` porque el valor
     * inicial no es un cambio: ese arranque ya lo cubren el init de BrowseViewModel y el fin de sync.
     *
     * **Colgado de quien navega, no del singleton.** Antes esto vivía en un `init` con un scope
     * propio de por vida: en un proceso levantado por un worker —sin UI, sin que nadie haya abierto
     * jamás la pestaña Artistas— seguía saliendo a Deezer en cada salto WiFi↔datos, de por vida. Al
     * exponerlo como flow que colecta [BrowseViewModel] en su `viewModelScope`, el observador de red
     * existe exactamente mientras hay una pantalla de browse viva; el caso legítimo de fondo (fotos
     * pendientes tras copiar música) ya lo cubre el hook de fin de sync.
     */
    fun backfillOnNetworkChanges(): Flow<Unit> =
        networkManager.status.drop(1).distinctUntilChanged().map {
            runCatching { backfillMissingImages() }
            Unit
        }
}
