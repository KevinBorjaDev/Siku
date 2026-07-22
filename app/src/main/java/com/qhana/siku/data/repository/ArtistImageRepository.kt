package com.qhana.siku.data.repository

import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.local.ArtistDao
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.remote.DeezerApi
import com.qhana.siku.data.remote.DeezerArtistDto
import com.qhana.siku.data.util.NetworkManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *   pisa una selección manual y cachea los not-found con TTL (patrón lyricsAttemptedAt).
 * - Selección manual ([setManualArtist]): elegida en el picker, marcada `manuallySet`.
 * - Los binarios los cachea el ImageLoader global de Coil (DiskCache 500MB); aquí solo
 *   se persisten URLs.
 */
@Singleton
class ArtistImageRepository @Inject constructor(
    private val artistDao: ArtistDao,
    private val deezerApi: DeezerApi,
    private val networkManager: NetworkManager,
    private val musicPreferences: MusicPreferences
) {
    companion object {
        /** TTL del cache de not-found: igual que el de letras (14 días). */
        private const val NOT_FOUND_TTL_MS = 14L * 24 * 60 * 60 * 1000

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
        if (artistName.isBlank() || artistName == AppConfig.UNKNOWN_ARTIST) return
        if (!attemptedThisSession.add(artistName)) return

        val existing = artistDao.getArtist(artistName)
        if (existing != null) {
            if (existing.manuallySet || existing.imageUrl != null) return
            val fetchedAt = existing.fetchedAt
            if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < NOT_FOUND_TTL_MS) return
        }

        fetchSemaphore.withPermit {
            try {
                val match = deezerApi.searchArtists(artistName, limit = 1).data?.firstOrNull()
                if (match != null) {
                    artistDao.upsertArtist(
                        ArtistEntity(
                            name = artistName,
                            deezerId = match.id,
                            imageUrl = match.pictureXl ?: match.pictureBig,
                            thumbUrl = match.pictureMedium,
                            manuallySet = false,
                            fetchedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    // Not-found: persistir el intento para no re-consultar por 14 días.
                    artistDao.upsertArtist(
                        ArtistEntity(name = artistName, fetchedAt = System.currentTimeMillis())
                    )
                }
            } catch (_: Exception) {
                // Error de red: NO escribir fetchedAt — se reintentará en otra sesión.
                attemptedThisSession.remove(artistName)
            }
        }
    }

    /**
     * Backfill data-driven de TODAS las fotos pendientes de la biblioteca: la lista sale de
     * la BD, no de qué filas llegue a mostrar la UI. Reutiliza [ensureArtistImage], así que
     * hereda idempotencia, TTL de not-found y rate-limit — es seguro dispararlo varias veces
     * y desde varios sitios (init de BrowseViewModel, fin de cada sync). El [Semaphore] hace
     * de límite de concurrencia real: se lanza una corrutina por artista pero solo
     * [MAX_CONCURRENT_FETCHES] tocan red a la vez.
     */
    suspend fun backfillMissingImages(): Unit = coroutineScope {
        val expiredBefore = System.currentTimeMillis() - NOT_FOUND_TTL_MS
        val pending = artistDao.getArtistNamesNeedingImage(expiredBefore)
        if (pending.isEmpty()) {
            _meteredBackfillPending.value = false
            return@coroutineScope
        }
        // Sin red no hay banner (el mensaje "estás en red móvil" sería mentira): se
        // reintenta en el próximo disparo. En red MEDIDA la pasada masiva (cientos de
        // JSONs la primera vez) no corre sin permiso: puntual del banner ("Descargar",
        // sesión) o permanente del ajuste "descargar con datos" (Ajustes → Descargas).
        if (!networkManager.isAvailable()) return@coroutineScope
        val meteredAllowed = meteredAllowedThisSession || musicPreferences.loadArtistPhotosOnMetered()
        if (!networkManager.isWifi() && !meteredAllowed) {
            if (!meteredBannerDismissed && musicPreferences.loadArtistPhotosBannerEnabled()) {
                _meteredBackfillPending.value = true
            }
            return@coroutineScope
        }
        _meteredBackfillPending.value = false
        pending.forEach { name ->
            launch { ensureArtistImage(name) }
        }
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

    private fun DeezerArtistDto.toCandidate() = DeezerArtistCandidate(
        deezerId = id,
        name = name.orEmpty(),
        imageUrl = pictureXl ?: pictureBig,
        thumbUrl = pictureMedium
    )
}
