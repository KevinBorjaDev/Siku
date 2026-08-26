package com.qhana.siku.ui.viewmodel

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.components.COLLAGE_MAX_TILES
import com.qhana.siku.ui.state.LibraryUiState
import com.qhana.siku.data.coordinator.IncompleteReason
import com.qhana.siku.worker.DownloadScheduler
import com.qhana.siku.worker.ScanWorker
import com.qhana.siku.worker.WorkerTags
import com.qhana.siku.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.qhana.siku.data.util.WhileUiSubscribed
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.random.Random
import javax.inject.Inject

/** Dato de bienvenida del inicio: escuchas de la semana + tamaño de biblioteca (respaldo). */
data class HomeStats(val playedThisWeek: Int, val librarySize: Int)

/** Sección generada del inicio: catálogo del artista más escuchado. */
/**
 * Sección "Porque escuchaste a X": el artista que más escuchas ([seed]) y OTROS artistas de tu
 * biblioteca que comparten género con él.
 *
 * Antes eran canciones DEL PROPIO artista, y eso prometía descubrimiento para entregar la
 * discografía que ya tenías a mano (ordenada por álbum, además, así que salían siempre las mismas).
 * Proponer vecinos es lo que la sección decía hacer. Ver `SongDao.getRelatedArtistsFlow` para lo que
 * este criterio puede y NO puede relacionar.
 */
data class HomeArtistPick(val seed: String, val related: List<RelatedArtistUi>)

/** Un artista propuesto, ya con su foto resuelta (o la carátula de respaldo). Ver [HomeArtistPick]. */
data class RelatedArtistUi(val name: String, val songCount: Int, val artUri: String?)

sealed class LibraryBannerState {
    data class Scanning(val progress: Int, val message: String) : LibraryBannerState()
    /** Fase de preparación (tags remotos, carátulas). [total] = 0 ⇒ progreso indeterminado. */
    data class Preparing(val current: Int, val total: Int, val message: String) : LibraryBannerState()
    data class Downloading(val current: Int, val total: Int, val failed: Int, val status: String) : LibraryBannerState()
    data class Complete(val newSongs: Int, val downloaded: Int, val failed: Int, val deleted: Int = 0) : LibraryBannerState()
    /** Cola detenida por el entorno (sin WiFi, sin red, batería): informativo, se reanuda sola. */
    data class Paused(val message: String) : LibraryBannerState()
    data class Error(val message: String, val canRetry: Boolean = true) : LibraryBannerState()
    object Hidden : LibraryBannerState()
}

/**
 * Las dos señales con las que `SyncManager` gobierna el banner, unificadas para que un ÚNICO
 * colector las consuma: el estado en curso ([Progress]) y el aviso de que terminó ([Finished],
 * un evento sin replay, para que un ViewModel recién nacido no reviva un "Complete" retenido).
 *
 * Existen como un tipo común precisamente para que no puedan tener consumidores separados: en
 * cuanto dos coroutines escriben el mismo estado de UI, cada una acaba con una rama que delega
 * en la otra y el banner se queda colgado cuando ambas se callan.
 */
private sealed interface SyncSignal {
    data class Progress(val status: SyncStatus) : SyncSignal
    data class Finished(val status: SyncStatus.Complete) : SyncSignal

    /**
     * Se cumplió la espera de cortesía de [BANNER_GRACE_MS] con un escaneo todavía en marcha: a
     * partir de aquí el banner SÍ se pinta. Es una señal más y no un temporizador aparte a propósito
     * — la convención 14 (un solo escritor por estado de UI) se cumple fusionando señales, no
     * añadiendo coroutines que escriban el mismo campo.
     */
    data object GraceElapsed : SyncSignal
}

/**
 * Cuánto tiene que llevar corriendo un ESCANEO antes de que el banner se moleste en aparecer.
 *
 * La app dispara pasadas de sincronización a menudo —el escaneo de arranque, el refresco al volver a
 * primer plano, el encadenado tras un worker— y la mayoría no encuentra nada: terminan en unos pocos
 * cientos de ms. Sin esta espera, cada una pintaba el banner y lo retiraba, y eso no es solo ruido
 * visual: mientras dice "Escaneando" hay DOS animaciones continuas encima (el icono giratorio con su
 * `rememberInfiniteTransition` y un `LinearWavyProgressIndicator` INDETERMINADO, que anima fase y
 * barrido a tasa de pantalla). O sea que cada aparición fugaz deja a la app produciendo un frame por
 * vsync, que es justo lo que impide a la cola de SurfaceFlinger drenar un atasco (ver "CERO
 * productores continuos" en CLAUDE.md). Medido con Perfetto el 21 ago 2026: en el segundo del
 * arranque, 121 frames con la composición parada y 120 de ellos con *buffer stuffing*.
 *
 * No se gatea nada más: `Downloading`, `Preparing`, `Paused` y `Error` significan que hay trabajo o
 * un problema de verdad, y ésos se anuncian en el acto.
 */
private const val BANNER_GRACE_MS = 400L

/**
 * Duración de un snackbar CORTO de Material 3 (`SnackbarDuration.Short`), en ms.
 *
 * Se replica en vez de leerse porque `SnackbarDuration.toMillis` es `internal` en
 * compose-material3. No es un valor elegido a ojo: el resumen del sync es exactamente lo mismo
 * que un snackbar informativo sin acción —un mensaje corto que se retira solo— así que hereda su
 * duración en lugar de inventarse una propia.
 */
private const val SNACKBAR_SHORT_MS = 4000L

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager,
    private val repository: IMusicRepository,
    private val artworkRepository: ArtworkRepository,
    // Solo para lo que "Seguir escuchando" resuelve EN VIVO (fotos de artista y carátulas de
    // género); el resto del browse vive en BrowseViewModel.
    private val browseRepository: com.qhana.siku.data.repository.BrowseRepository,
    private val musicPreferences: MusicPreferences,
    private val musicController: MusicController,
    private val syncManager: SyncManager,
    private val downloadScheduler: DownloadScheduler,
    private val workManager: WorkManager,
    private val networkManager: com.qhana.siku.data.util.NetworkManager,
    private val sourceRegistry: com.qhana.siku.data.source.MusicSourceRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /*
     * --- Vistas de UN campo para las capas persistentes -----------------------------------------
     *
     * [LibraryUiState] está partido en cinco sub-estados justamente para acotar el alcance de la
     * recomposición, pero eso solo sirve si el consumidor colecta la parte que le importa. Las tres
     * capas RAÍZ de la app —`AppNavHost`, `PlayerOverlay` y `NowPlayingRoute`— lo colectaban entero
     * para leer entre uno y tres campos, todos de `data`: ninguna toca `searchFilter`, `sorting`,
     * `colorTuning` ni `playbackSettings`. Al colectar la raíz, cualquier emisión las recomponía.
     *
     * Lo que emite: cada TECLA de la barra de búsqueda, cada cambio de orden o de chip de origen,
     * el slider de ReplayGain de Ajustes, la regeneración de colores y cada actualización del banner
     * de sync. Escribir una palabra en la búsqueda recomponía las tres capas una vez por letra — el
     * reproductor persistente incluido, que en ese momento está guardado y no se ve.
     *
     * `distinctUntilChanged` es lo que hace el trabajo: `favorites` es un `Set` y `playlists` una
     * `List`, así que la comparación estructural corta la emisión cuando el campo no cambió, que es
     * casi siempre. Con `WhileSubscribed` no arrancan si nadie los colecta y el `replay = 1` deja el
     * valor listo para el siguiente suscriptor.
     *
     * `LibraryScreen` sigue colectando [uiState] entero, que es donde de verdad se usa completo.
     */

    /** Ids de las canciones favoritas. Ver el bloque de arriba. */
    val favorites: StateFlow<Set<String>> = _uiState
        .map { it.data.favorites }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, emptySet())

    /**
     * Modo "pestañas abajo". Vista de UN campo por el mismo motivo que las de arriba: lo lee la
     * pantalla de Ajustes → Pestañas, que no tiene nada que hacer con el resto del `uiState`.
     */
    val libraryBottomTabs: StateFlow<Boolean> = _uiState
        .map { it.playbackSettings.libraryBottomTabs }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    /** Listas del usuario. Ver el bloque de arriba. */
    val playlists: StateFlow<List<Playlist>> = _uiState
        .map { it.data.playlists }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /** Canciones favoritas resueltas (lista pesada). Ver el bloque de arriba. */
    val favoriteSongs: StateFlow<List<Song>> = _uiState
        .map { it.data.favoriteSongs }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * Hasta cuatro carátulas distintas de las favoritas, para el collage de su tarjeta en el
     * inicio. Deriva de [favoriteSongs], que ya está materializada, así que no cuesta una consulta
     * nueva; y se corta aquí y no en la pantalla para no llevar la lista entera —que puede ser de
     * miles— a un componente que usa cuatro imágenes.
     */
    val favoriteCovers: StateFlow<List<String>> = favoriteSongs
        .map { songs ->
            songs.asSequence()
                .mapNotNull { it.albumArtUriString }
                .distinct()
                .take(COLLAGE_MAX_TILES)
                .toList()
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    private var bannerDismissJob: Job? = null

    // Evento tipado "colores regenerados" (antes MainActivity lo detectaba por el texto del
    // mensaje con `contains("regenerados")`, frágil con i18n).
    private val _colorsRegeneratedEvent = MutableSharedFlow<Unit>()
    val colorsRegeneratedEvent: SharedFlow<Unit> = _colorsRegeneratedEvent.asSharedFlow()

    // Canciones con re-descarga en curso (para deshabilitar el botón en la UI).
    private val _redownloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val redownloadingIds: StateFlow<Set<String>> = _redownloadingIds.asStateFlow()

    /**
     * Progreso por canción de las descargas EN VUELO (0..1), para pintarlo en la lista.
     * La fuente es `SyncManager.activeDownloads` —el downloader lo alimenta byte a byte—, NO
     * `WorkInfo.progress`: el worker delega la descarga en SyncManager y nunca llama a
     * `setProgress`, así que ese dato es siempre 0 (por eso no se veía ningún progreso).
     *
     * Solo descargas INDIVIDUALES (pedidas por el usuario / prioritarias): durante un sync
     * masivo hasta 32 filas animándose a la vez era ruido redundante con el banner y el
     * Download Manager, que ya muestran ese avance.
     */
    val downloadProgressById: StateFlow<Map<String, Float>> = syncManager.activeDownloads
        .map { active -> active.filter { it.individual }.associate { it.song.id to it.progress } }
        .stateIn(viewModelScope, WhileUiSubscribed, emptyMap())

    // Indicador del pull-to-refresh. Vive acá (no en la pantalla) porque quien sabe
    // cuándo el sync realmente arrancó es el colector de syncManager.state.
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // --- Paging Flows (Derived from UiState) ---
    // IMPORTANT: .catch must be INSIDE flatMapLatest, not outside.
    // If placed outside, catching an error terminates the entire flow permanently,
    // making it impossible to recover (e.g., clearing search query has no effect).
    val pagedSongs: Flow<PagingData<Song>> = _uiState
        .map { Triple(it.searchQuery, it.sortOrderAll, it.sourceFilters) }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder, sourceFilters) ->
            flow {
                // Rescate por APROXIMACIÓN, y solo cuando hace falta: si la búsqueda literal ya
                // devuelve algo, esos son los resultados que el usuario espera y no se toca nada.
                // Cuando no devuelve NADA es cuando una errata deja la pantalla en blanco, y ahí sí
                // compensa recorrer el texto de la biblioteca comparando con tolerancia.
                //
                // Atarlo a "no hubo resultados" es lo que lo hace barato: el caso corriente no paga
                // ni una consulta de más, y el costoso ocurre una vez, con el usuario mirando una
                // lista vacía. Sin esa condición habría que recorrer la biblioteca en cada tecla.
                val approximateIds =
                    if (query.isNotBlank() && repository.countSongsMatching(query, sourceFilters) == 0) {
                        repository.findApproximateSongIds(query)
                    } else {
                        emptyList()
                    }
                emitAll(repository.getSongsPaging(query, sortOrder, sourceFilters, approximateIds))
            }.catch { e ->
                snackbarManager.show(context.getString(R.string.library_error_loading, e.message ?: e.javaClass.simpleName))
                emit(PagingData.empty())
            }
        }
        .cachedIn(viewModelScope)

    // Búsqueda DESACOPLADA de los chips de origen: el overlay busca SIEMPRE en toda la
    // biblioteca. Compartiendo pagedSongs heredaba los filtros activos y su "sin
    // resultados" podía ser mentira (las canciones existían, las escondía un chip que el
    // overlay ni muestra).
    val pagedSearchSongs: Flow<PagingData<Song>> = _uiState
        .map { it.searchQuery to it.sortOrderAll }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder) ->
            repository.getSongsPaging(query, sortOrder)
                .catch { e ->
                    snackbarManager.show(context.getString(R.string.library_error_loading, e.message ?: e.javaClass.simpleName))
                    emit(PagingData.empty())
                }
        }
        .cachedIn(viewModelScope)

    val pagedFavorites: Flow<PagingData<Song>> = _uiState
        .map { Pair(it.searchQuery, it.sortOrderFavorites) }
        .distinctUntilChanged()
        .flatMapLatest { (query, sortOrder) ->
            repository.getFavoritesPaging(query, sortOrder)
                .catch { e ->
                    snackbarManager.show(context.getString(R.string.library_error_loading_favorites, e.message ?: e.javaClass.simpleName))
                    emit(PagingData.empty())
                }
        }
        .cachedIn(viewModelScope)

    // Reactivo a los chips de origen: es el número del chip "N canciones" sobre la lista,
    // así que debe contar lo mismo que la lista muestra (sin filtros = total).
    val songCount: StateFlow<Int> = _uiState
        .map { it.sourceFilters }
        .distinctUntilChanged()
        .flatMapLatest { repository.getSongCountFlow(it) }
        .stateIn(viewModelScope, WhileUiSubscribed, 0)

    /** Chip de origen de la pestaña Todas: alterna su presencia en el set (unión). */
    fun toggleSourceFilter(filter: SongSourceFilter) {
        _uiState.update { state ->
            val current = state.searchFilter.sourceFilters
            val updated = if (filter in current) current - filter else current + filter
            state.copy(searchFilter = state.searchFilter.copy(sourceFilters = updated))
        }
    }

    /**
     * ¿La biblioteca está partida entre lo que suena sin red y lo que la necesita? PUERTA ÚNICA de
     * todo lo que habla de origen: los chips de Todas/Artistas/Álbumes y las dos acciones del
     * inicio ("Sin conexión" / "Sin descargar"), que aparecen y desaparecen juntas.
     *
     * Antes bastaba con que hubiera canciones de nube, y ese criterio dejaba tres chips
     * permanentes en tres barras para una biblioteca completamente descargada, donde separan
     * 777 de 0. La regla honesta es la que ya usaba el chip "Local" —visibilidad por CONTENIDO—
     * llevada hasta el final: si una de las dos mitades está vacía, elegir origen no dice nada.
     *
     * Vale la pena que sea REACTIVO y no una consulta al abrir: en una biblioteca de nube los
     * chips nacen útiles y se apagan solos cuando la última descarga termina, sin reinicios.
     */
    val hasSourceSplit: StateFlow<Boolean> = repository.hasSourceSplitFlow()
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    private val hasLocalSongs: StateFlow<Boolean> = repository.hasLocalSongsFlow()
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    /**
     * Los chips de nube (Descargadas / Nube) SON el reparto, así que su visibilidad es la puerta
     * tal cual. La decisión vive aquí y no en cada pantalla: la calculaban por separado
     * `LibraryScreen` (para Artistas/Álbumes) y `SongsScreen` (para Todas) con la misma expresión
     * copiada, o sea dos sitios que podían discrepar sobre cuándo se ve un control.
     */
    val showCloudSourceChips: StateFlow<Boolean> = hasSourceSplit

    /** El chip "Local" añade su propia condición: que además HAYA canciones locales. */
    val showLocalSourceChip: StateFlow<Boolean> =
        combine(hasSourceSplit, hasLocalSongs) { split, local -> split && local }
            .stateIn(viewModelScope, WhileUiSubscribed, false)

    // --- Secciones de la pantalla de inicio ---
    // Reactivas al historial (v22): cada escucha contada actualiza estas listas sin recargar
    // la biblioteca. Se mantienen vivas 5s tras perder el último suscriptor (cambio de tab).
    // "Seguir escuchando" = últimos CONTEXTOS reproducidos (álbum/artista/lista/favoritos/
    // aleatorio/biblioteca), no canciones sueltas del historial. Cada uno reanudable como tal.
    //
    // Un contexto de ARTISTA se pinta con la FOTO del artista, no con la carátula que quedó
    // guardada al reproducirlo: la tarjeta representa al artista y esa carátula es la de su
    // primer álbum, que ya sale en el carrusel de álbumes. Se resuelve aquí y no al grabar el
    // contexto porque la foto es tardía y mutable (la trae el backfill de Deezer, y el picker
    // la cambia o la quita); un snapshot se quedaría con lo que hubiera en ese instante.
    // El coverUri guardado sigue siendo el fallback: artista sin foto → su carátula.
    val recentContexts: StateFlow<List<PlaybackContext>> = musicPreferences.recentContextsFlow
        .flatMapLatest { contexts ->
            val artistNames = contexts.filterIsInstance<PlaybackContext.Artist>().map { it.name }
            if (artistNames.isEmpty()) {
                flowOf(contexts)
            } else {
                browseRepository.getArtistPhotos(artistNames).map { photos ->
                    contexts.map { ctx ->
                        if (ctx is PlaybackContext.Artist) {
                            photos[ctx.name]?.let { ctx.copy(coverUri = it) } ?: ctx
                        } else ctx
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * Carátulas del collage de los contextos de GÉNERO de "Seguir escuchando", por nombre en
     * minúsculas. Mismo criterio que la foto del artista: un género no tiene UNA carátula, así
     * que la tarjeta no puede depender del snapshot que se guardó al reproducirlo (el arte de
     * su primera canción, `null` si esa canción no tenía) — se resuelve en vivo contra la
     * biblioteca, igual que hace su tarjeta en la pestaña Géneros.
     */
    val recentGenreArts: StateFlow<Map<String, List<String>>> = musicPreferences.recentContextsFlow
        .map { contexts ->
            contexts.filterIsInstance<PlaybackContext.Genre>().map { it.name }.toSet()
        }
        .distinctUntilChanged()
        .flatMapLatest { names ->
            if (names.isEmpty()) flowOf(emptyMap()) else browseRepository.getGenreArts(names)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, emptyMap())

    /** Antepone un contexto al historial de "Seguir escuchando" (dedup + tope). */
    fun recordContext(ctx: PlaybackContext) = musicPreferences.recordContext(ctx)

    /**
     * Ver [settlingFlow] y [sampledDuringSync]: gobierna el muestreo de las consultas del inicio
     * mientras el sync reescribe la tabla `songs`.
     */
    private val libraryIsSettling: Flow<Boolean> = syncManager.settlingFlow()

    val homeMostPlayed: StateFlow<List<Song>> = repository.getMostPlayed(HOME_SECTION_LIMIT)
        .sampledDuringSync(libraryIsSettling)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    val homeRecentlyAdded: StateFlow<List<Song>> = repository.getRecentlyAdded(HOME_SECTION_LIMIT)
        .sampledDuringSync(libraryIsSettling)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    // Datos del saludo del inicio: escuchas de la última semana + tamaño de biblioteca (respaldo
    // cuando la semana está vacía). La ventana se fija al construir el ViewModel (se refresca al
    // reiniciar el proceso), suficiente para un dato de bienvenida.
    val homeStats: StateFlow<HomeStats> = combine(
        repository.getPlayedSinceCount(System.currentTimeMillis() - WEEK_MILLIS),
        repository.getLibrarySize()
    ) { week, size -> HomeStats(week, size) }
        .sampledDuringSync(libraryIsSettling)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, HomeStats(0, 0))

    // Sección generada "Porque escuchaste a X": OTROS artistas que comparten género con el que más
    // escuchas. Reactiva en tres niveles — cambia de artista semilla cuando el historial lo hace,
    // refleja altas/bajas en la biblioteca, y recoge las fotos de Deezer según van llegando.
    // Ver [HomeArtistPick] para por qué ya no son canciones del propio artista.
    val homeArtistPick: StateFlow<HomeArtistPick?> = repository.getTopPlayedArtist(MIN_ARTIST_PICK_SONGS)
        .distinctUntilChanged()
        .flatMapLatest { artist ->
            if (artist.isNullOrBlank()) flowOf(null)
            else browseRepository.getRelatedArtists(artist, HOME_SECTION_LIMIT)
                .flatMapLatest { related ->
                    // Menos de dos propuestas no es un carrusel, es una tarjeta suelta: la sección
                    // no se pinta. Pasa con una biblioteca sin géneros analizados (`songs.genre` es
                    // null hasta que se lee el tag) o muy monotemática.
                    if (related.size < MIN_RELATED_ARTISTS) return@flatMapLatest flowOf(null)
                    // La foto se resuelve AQUÍ y no en la consulta, por lo mismo que en "Seguir
                    // escuchando": es tardía (la trae el backfill de Deezer) y mutable (el picker la
                    // cambia o la quita), así que un JOIN la congelaría. El respaldo es la carátula
                    // de alguno de sus álbumes.
                    browseRepository.getArtistPhotos(related.map { it.name }).map { photos ->
                        HomeArtistPick(
                            seed = artist,
                            related = related.map {
                                RelatedArtistUi(it.name, it.songCount, photos[it.name] ?: it.fallbackArtUri)
                            }
                        )
                    }
                }
        }
        .sampledDuringSync(libraryIsSettling)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, WhileUiSubscribed, null)

    // "Vuelve a escucharlas": escuchadas alguna vez pero no en las últimas ~2 semanas.
    val homeRediscover: StateFlow<List<Song>> =
        repository.getRediscover(System.currentTimeMillis() - REDISCOVER_MILLIS, HOME_SECTION_LIMIT)
            .sampledDuringSync(libraryIsSettling)
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    // Géneros para los chips de acciones rápidas del inicio (≥ GENRE_MIN_COUNT canciones).
    // Reactivo: aparecen solos a medida que el backfill puebla la columna.
    //
    // **No son el TOP fijo, son una selección del DÍA** (17 ago 2026). Con `ORDER BY songCount DESC
    // LIMIT 5` y una biblioteca quieta salían los mismos cinco para siempre, que es exactamente lo
    // contrario de lo que una fila de atajos debería hacer: los géneros grandes ya los tienes a mano
    // en su pestaña, y el chip vale justo para lo que no se te habría ocurrido abrir. Así que la
    // consulta trae un CANDIDATERO más ancho ([GENRE_POOL_LIMIT]) y de ahí se eligen los del día.
    //
    // El top SOLO se publica con la biblioteca QUIETA: durante un scan/descargas la tabla
    // `songs` cambia miles de veces y Room reemite en cada cambio; como el orden es por
    // cantidad y los géneros entran al cruzar el umbral, la fila de chips se reordenaba y
    // saltaba de línea sin parar (parpadeo). Gatearlo por el estado real del sync evita
    // inventar un intervalo de refresco: mientras la biblioteca se asienta no se toca nada, y
    // al terminar se publica el top definitivo de una sola vez.
    //
    // Se emiten solo los NOMBRES, que es lo único que pinta el chip: el `songCount` de
    // GenreSummary sube con cada canción analizada, así que comparando el modelo completo el
    // distinctUntilChanged no filtraba NADA (misma fila en pantalla, emisión nueva).
    val homeTopGenres: StateFlow<List<String>> =
        combine(
            repository.getTopGenres(GENRE_MIN_COUNT, GENRE_POOL_LIMIT).sampledDuringSync(libraryIsSettling),
            libraryIsSettling
        ) { genres, settling ->
            if (settling) null else genres.map { it.name }.pickGenreChipsOfTheDay()
        }
            .filterNotNull()
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * Los chips de género de HOY, elegidos entre los candidatos que trae la consulta.
     *
     * **La aleatoriedad va sembrada con el DÍA, y eso es la mitad del diseño.** Un `shuffled()` a
     * secas reordenaría la fila en cada emisión —y la consulta reemite con cada canción analizada,
     * cada descarga y cada cambio de la tabla—, así que los chips bailarían delante del usuario; es el
     * mismo parpadeo que ya obligó a gatear esta sección por `sampledDuringSync`, entrando por otra
     * puerta. Con la semilla del día la selección es ESTABLE mientras el día lo sea (misma lista tras
     * reabrir la app, tras un scan y tras cambiar de pestaña) y cambia sola al día siguiente, sin
     * ningún temporizador que mantener.
     *
     * Se usa el día LOCAL y no `currentTimeMillis / 86_400_000`: ese cambiaría a medianoche UTC, o sea
     * a media tarde en Perú, y "los géneros de hoy" tiene que cambiar cuando cambia el día de quien
     * mira. Con menos candidatos que huecos no hay nada que elegir y se devuelven todos.
     */
    private fun List<String>.pickGenreChipsOfTheDay(): List<String> {
        if (size <= GENRE_CHIP_LIMIT) return this
        return shuffled(Random(LocalDate.now().toEpochDay())).take(GENRE_CHIP_LIMIT)
    }

    /**
     * Canciones de un género (para el chip: se reproducen en aleatorio). Respeta el ajuste
     * "incluir géneros compuestos" de la pestaña Géneros: el mismo género tiene que dar la misma
     * lista se toque en el chip del inicio o en su tarjeta.
     */
    suspend fun getSongsByGenre(genre: String): List<Song> =
        repository.getSongsByGenre(genre, musicPreferences.loadGenrePartialMatch())

    init {
        // Saneo: si un chip deja de verse con su filtro seleccionado, el filtro quedaría ACTIVO
        // pero INVISIBLE — una lista filtrada sin forma de quitar el filtro. Se limpia solo.
        //
        // Va contra la VISIBILIDAD y no contra las familias de canciones, que es lo que hacía
        // antes: desde que la puerta es el reparto ([hasSourceSplit]), el caso que más se va a dar
        // no es un logout sino que la última canción sin descargar TERMINE de bajarse con el chip
        // "Nube" puesto — los chips desaparecen y, sin esto, la lista se quedaba en cero.
        viewModelScope.launch {
            combine(showLocalSourceChip, showCloudSourceChips) { local, cloud -> local to cloud }
                .collect { (local, cloud) ->
                    _uiState.update { state ->
                        val sanitized = state.searchFilter.sourceFilters.filterTo(mutableSetOf()) { f ->
                            if (f == SongSourceFilter.LOCAL) local else cloud
                        }
                        if (sanitized == state.searchFilter.sourceFilters) state
                        else state.copy(searchFilter = state.searchFilter.copy(sourceFilters = sanitized))
                    }
                }
        }

        // Initial Prefs Load
        _uiState.update {
            it.copy(
                sorting = it.sorting.copy(
                    sortOrderAll = musicPreferences.loadSortOrder(SongFilter.ALL),
                    sortOrderFavorites = musicPreferences.loadSortOrder(SongFilter.FAVORITES)
                ),
                playbackSettings = it.playbackSettings.copy(
                    replayGainMode = musicPreferences.loadReplayGainMode(),
                    replayGainPreamp = musicPreferences.loadReplayGainPreamp(),
                    nowPlayingBackground = musicPreferences.loadNowPlayingBackground(),
                    nowPlayingWavyProgress = musicPreferences.loadNowPlayingWavyProgress(),
                    nowPlayingProgressThickness = musicPreferences.loadNowPlayingProgressThickness(),
                    nowPlayingProgressHandle = musicPreferences.loadNowPlayingProgressHandle(),
                    miniPlayerRoundedRect = musicPreferences.loadMiniPlayerRoundedRect(),
                    miniPlayerRoundPlayButton = musicPreferences.loadMiniPlayerRoundPlayButton(),
                    libraryBottomTabs = musicPreferences.loadLibraryBottomTabs(),
                    playerGestures = musicPreferences.loadPlayerGestures(),
                    themePaletteStyle = musicPreferences.loadThemePaletteStyle(),
                    useSystemEq = musicPreferences.loadUseSystemEq()
                )
            )
        }

        // El chip de formato detallado se conmuta TAMBIÉN desde el reproductor (otra instancia
        // de ViewModel), así que se OBSERVA en vez de cargarse una vez: si no, el switch de
        // Ajustes mostraría el valor con el que nació esta instancia.
        viewModelScope.launch {
            musicPreferences.nowPlayingDetailedFormatFlow.collect { enabled ->
                _uiState.update {
                    it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingDetailedFormat = enabled))
                }
            }
        }

        // Mismo caso: el modo de guardado de letras lo fija también el diálogo del reproductor
        // (con "no volver a preguntar"), desde otra instancia de ViewModel. Observado, no cargado.
        viewModelScope.launch {
            musicPreferences.lyricsSaveModeFlow.collect { mode ->
                _uiState.update {
                    it.copy(playbackSettings = it.playbackSettings.copy(lyricsSaveMode = mode))
                }
            }
        }
        viewModelScope.launch {
            musicPreferences.lyricsFolderUriFlow.collect { uri ->
                _uiState.update {
                    it.copy(playbackSettings = it.playbackSettings.copy(lyricsFolderUri = uri))
                }
            }
        }

        // Collect Repository Flows (Combined) — flowOn IO to avoid main thread work.
        // `sampledDuringSync`: `getFavoritesIds` lee canciones favoritas, así que Room la re-ejecuta
        // en cada escritura de `songs` (invalidación por TABLA). Sin el muestreo, un sync masivo la
        // dispara decenas de veces por segundo aunque el resultado no cambie. Igual que las consultas
        // del inicio; ver [sampledDuringSync].
        viewModelScope.launch {
            combine(
                repository.getUserPlaylists(),
                repository.getFavoritesIds()
            ) { playlists, favorites ->
                playlists to favorites
            }
                .sampledDuringSync(libraryIsSettling)
                .flowOn(Dispatchers.IO)
                .collect { (playlists, favorites) ->
                    _uiState.update {
                        it.copy(data = it.data.copy(
                            playlists = playlists,
                            favorites = favorites
                        ))
                    }
                }
        }

        // Collect Favorite Songs separately (heavy list, ensure IO thread). Es el `SELECT s.* … JOIN`
        // sobre `songs` del hallazgo: el más caro de re-ejecutar y el que más se beneficia del muestreo.
        viewModelScope.launch {
            repository.getFavoritesSongs()
                .sampledDuringSync(libraryIsSettling)
                .flowOn(Dispatchers.IO)
                .collect { list ->
                    _uiState.update { it.copy(data = it.data.copy(favoriteSongs = list)) }
                }
        }

        // El banner del sync tiene UN SOLO dueño: este colector. Las dos señales que lo
        // gobiernan —el estado en curso y el evento de "terminó"— se funden en un flujo y cada
        // una SIEMPRE produce un `LibraryBannerState`.
        //
        // Antes eran dos coroutines escribiendo el mismo campo, y cada una tenía una rama que
        // salía sin tocarlo delegando en la otra ("de esto se encarga el otro colector"). Basta
        // con que ambas decidan callarse para que el banner anterior se quede congelado: pasó
        // exactamente eso, "Escaneando biblioteca" para siempre en una biblioteca local sin
        // novedades. Con un único consumidor y un `when` que cubre todos los casos, ese camino
        // ni siquiera se puede escribir.
        //
        // El orden entre ambas señales está garantizado porque `SyncManager` publica el estado
        // y emite el evento en la misma secuencia (`_state.value = complete` y luego `tryEmit`).
        viewModelScope.launch {
            // El tercer flujo emite UNA vez por escaneo, [BANNER_GRACE_MS] después de que arranque.
            // Si el escaneo termina antes, `flatMapLatest` cancela la espera y el tick no llega:
            // el banner no llega a pintarse nunca, que es justo lo que se busca en las pasadas en
            // vacío. Ver [BANNER_GRACE_MS].
            // Tipo EXPLÍCITO: sin él, `merge` tiene que inferir el supertipo común de tres flujos de
            // subtipos distintos y `emptyFlow()` se queda sin nada de donde sacarlo.
            val graceTicks: Flow<SyncSignal> = syncManager.state
                .map { it is SyncStatus.Scanning }
                .distinctUntilChanged()
                .flatMapLatest { scanning ->
                    if (!scanning) emptyFlow()
                    else flow {
                        delay(BANNER_GRACE_MS)
                        emit(SyncSignal.GraceElapsed)
                    }
                }

            // `false` mientras el escaneo esté dentro de su espera de cortesía. Es estado LOCAL del
            // colector —no de la UI—, así que no compite con nadie por [_uiState].
            var graceElapsed = false

            merge(
                syncManager.state.map { SyncSignal.Progress(it) },
                syncManager.completedEvents.map { SyncSignal.Finished(it) },
                graceTicks
            ).collect { signal ->
                // El sync publicó algo: el pull-to-refresh ya no espera nada. El tick de cortesía
                // NO cuenta — no dice nada del sync, así que no debe apagar el indicador.
                if (signal is SyncSignal.Finished ||
                    (signal is SyncSignal.Progress && signal.status !is SyncStatus.Idle)
                ) {
                    _isManualRefreshing.value = false
                }

                // `null` = "no toques el banner". Lo necesita el caso Complete (abajo): no es lo
                // mismo "poner Hidden" que "dejar lo que hay", y confundirlos era el parpadeo.
                val banner: LibraryBannerState? = when (signal) {
                    // **El escaneo espera su turno** ([BANNER_GRACE_MS]): mientras no haya pasado la
                    // cortesía no se pinta nada, y si la pasada termina antes, el banner no llega a
                    // existir. Lo demás se anuncia en el acto: significa trabajo o problema real.
                    is SyncSignal.Progress -> when (val status = signal.status) {
                        // Fin de la corrida: se rearma la cortesía para la siguiente.
                        is SyncStatus.Idle, is SyncStatus.Complete -> {
                            graceElapsed = false
                            progressBanner(status)
                        }
                        is SyncStatus.Scanning -> if (graceElapsed) progressBanner(status) else null
                        else -> {
                            graceElapsed = true
                            progressBanner(status)
                        }
                    }
                    // Se cumplió la espera y el escaneo sigue: ahora sí, con el estado ACTUAL.
                    is SyncSignal.GraceElapsed -> {
                        graceElapsed = true
                        progressBanner(syncManager.state.value)
                    }
                    // "Biblioteca al día" (sync sin cambio alguno) solo aporta con una fuente de
                    // NUBE (confirma que se consultó el servidor). Con biblioteca 100% local el
                    // re-escaneo es silencioso: sin novedades no hay nada que anunciar. Si SÍ
                    // hubo cambios (canciones nuevas/borradas de la carpeta), se muestra igual.
                    is SyncSignal.Finished -> signal.status.let { status ->
                        // La corrida acabó: la siguiente vuelve a tener su espera de cortesía.
                        graceElapsed = false
                        val nothingToReport = status.newSongs == 0 && status.downloaded == 0 &&
                            status.failed == 0 && status.deleted == 0
                        val onlyLocal = sourceRegistry.activeSources()
                            .none { it.type != SourceType.LOCAL }
                        if (nothingToReport && onlyLocal) {
                            LibraryBannerState.Hidden
                        } else {
                            LibraryBannerState.Complete(
                                status.newSongs, status.downloaded, status.failed, status.deleted
                            )
                        }
                    }
                }

                // Con `null` no se toca NADA, ni el estado ni el job de auto-retirada: cancelarlo
                // dejaría el resumen en pantalla para siempre si esta señal llega después de él.
                if (banner == null) return@collect
                bannerDismissJob?.cancel()
                _uiState.update { it.copy(data = it.data.copy(bannerState = banner)) }
                // El resumen es lo único que se retira solo: los estados en curso los releva la
                // siguiente señal, y un error se queda hasta que el usuario reintente.
                if (banner is LibraryBannerState.Complete) {
                    bannerDismissJob = viewModelScope.launch {
                        delay(summaryBannerTimeoutMs())
                        _uiState.update {
                            it.copy(data = it.data.copy(bannerState = LibraryBannerState.Hidden))
                        }
                    }
                }
            }
        }

        // Escaneo que se dio por perdido en segundo plano. Va en un colector APARTE del anterior
        // —que es el dueño del banner mientras el sync habla— porque estas dos cosas nunca compiten:
        // esta señal solo existe cuando NO hay sync corriendo, y cualquier corrida posterior que
        // termine bien la borra. El de arriba manda en cuanto vuelve a haber actividad.
        //
        // Se lee de disco y no de `SyncManager` a propósito: quien se rindió fue un worker, minutos
        // antes y con la app probablemente cerrada, así que el estado en memoria ya no existe. Es lo
        // que evita que la biblioteca aparezca desactualizada sin decir por qué (ver
        // `ScanWorker.recordingFailure`).
        viewModelScope.launch {
            combine(
                musicPreferences.lastSyncFailureFlow,
                syncManager.state
            ) { failure, status -> failure.takeIf { !status.isRunning } }
                .distinctUntilChanged()
                .collect { failure ->
                    if (failure == null) return@collect
                    // Solo si no hay nada más que contar: un banner en curso o un resumen recién
                    // publicado son más actuales que un fallo anterior.
                    val current = _uiState.value.data.bannerState
                    if (current != LibraryBannerState.Hidden) return@collect
                    _uiState.update {
                        it.copy(
                            data = it.data.copy(
                                bannerState = LibraryBannerState.Error(syncFailureMessage(failure))
                            )
                        )
                    }
                }
        }

        // La restauración de sesión vive en MusicController.syncCurrentState (al conectar el
        // MediaController, resolviendo SOLO los IDs de la cola contra la BD). El viejo restore
        // desde aquí cargaba la biblioteca ENTERA en memoria para lo mismo.
    }

    /**
     * Traduce el motivo persistido de un escaneo perdido al texto que ve el usuario. Las categorías
     * las fija `ScanWorker`; lo desconocido cae al mensaje genérico, que es preferible a enseñar un
     * código interno.
     */
    private fun syncFailureMessage(reason: String): String = context.getString(
        when (reason) {
            ScanWorker.FAILURE_AUTH -> R.string.sync_failed_auth
            ScanWorker.FAILURE_NETWORK, IncompleteReason.NETWORK_LOST.name -> R.string.sync_failed_network
            ScanWorker.FAILURE_SERVER -> R.string.sync_failed_server
            else -> R.string.sync_failed_generic
        }
    )

    /**
     * Cuánto permanece el resumen del sync, con el MISMO criterio que un snackbar de Material 3:
     * su duración corta, pasada por el ajuste de accesibilidad del sistema.
     *
     * Ese ajuste no es un adorno: es lo que hace `SnackbarHostState` internamente, y respeta la
     * preferencia "tiempo para realizar acciones" de quien necesita más rato para leer. Sin él,
     * el mismo mensaje se le escaparía. Antes de API 29 no existe la preferencia y no hay nada
     * que ajustar.
     */
    private fun summaryBannerTimeoutMs(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SNACKBAR_SHORT_MS
        val manager = context.getSystemService(AccessibilityManager::class.java)
            ?: return SNACKBAR_SHORT_MS
        return manager.getRecommendedTimeoutMillis(
            SNACKBAR_SHORT_MS.toInt(),
            AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT
        ).toLong()
    }

    // --- Actions ---

    /**
     * Pull-to-refresh. El indicador se apaga por SEÑALES reales, no por timer:
     * - el colector de `syncManager.state` lo baja en cuanto el sync publica cualquier estado;
     * - las precondiciones que impedirían que el scan arranque (sin red, batería baja — las
     *   mismas constraints del ScanWorker) se detectan ACÁ y se comunican con snackbar,
     *   en vez de dejar el spinner girando contra un worker que WorkManager no va a correr.
     */
    fun onRefresh() {
        viewModelScope.launch {
            // La red solo es requisito si hay alguna fuente de NUBE configurada: re-escanear
            // una carpeta local es 100% offline y no tiene por qué fallar en modo avión.
            val needsNetwork = sourceRegistry.activeSources().any { it.type != SourceType.LOCAL }
            if (needsNetwork && !networkManager.isAvailable()) {
                snackbarManager.show(context.getString(R.string.common_connection_error))
                return@launch
            }
            if (isBatteryLow()) {
                // Se agenda igual (WorkManager lo correrá al recuperar batería), pero sin
                // spinner: no hay sync inminente que esperar.
                downloadScheduler.scheduleScan(force = true, requiresNetwork = needsNetwork)
                snackbarManager.show(context.getString(R.string.sync_postponed_low_battery))
                return@launch
            }
            _isManualRefreshing.value = true
            downloadScheduler.scheduleScan(force = true, requiresNetwork = needsNetwork)
        }
    }

    /**
     * Espejo de la constraint `BATTERY_NOT_LOW` del ScanWorker (ver [BATTERY_LOW_PERCENT]).
     *
     * Existe porque la UI necesita ANTICIPAR lo que WorkManager va a decidir —explicar por qué el
     * escaneo no arranca en vez de dejar un botón que no hace nada—, y no hay API que lo pregunte.
     */
    private fun isBatteryLow(): Boolean = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) <= BATTERY_LOW_PERCENT &&
            !bm.isCharging
    } catch (_: Exception) {
        false
    }

    fun redownloadSong(song: Song) {
        // LOCAL nunca se re-descarga: no hay copia en la nube y switchCurrentToStreaming
        // le quitaría la fuente a la canción sonando. La UI oculta la opción; esto es la red.
        // Por sourceType, NO por isLocalAudio: una canción de NUBE ya descargada (file://)
        // también es isLocalAudio, y re-descargarla (reparar un archivo dañado) SÍ aplica.
        if (song.sourceType == SourceType.LOCAL) return
        // Idempotente: si ya hay una re-descarga en curso para esta canción, no hacer nada.
        var added = false
        _redownloadingIds.update { current ->
            if (song.id in current) current
            else { added = true; current + song.id }
        }
        if (!added) return
        // Si es la canción que está sonando, pasarla a streaming ANTES de encolar: la
        // re-descarga borra su archivo local y el player se quedaría sin fuente. No-op si no
        // es la actual.
        musicController.switchCurrentToStreaming(song.id)
        viewModelScope.launch {
            snackbarManager.show(context.getString(R.string.download_msg_starting, song.title))
        }
        downloadScheduler.scheduleDownload(songId = song.id, forceRedownload = true, isUserInitiated = true)
        viewModelScope.launch {
            try {
                // Espera a que el trabajo TERMINE o DESAPAREZCA, y suelta el indicador en cuanto
                // ocurra lo primero de las dos.
                //
                // Que la desaparición cuente como final es el punto: WorkManager poda los WorkInfo
                // terminados, y con `mapNotNull` esa lista vacía se filtraba, así que el flow no
                // volvía a emitir jamás y solo el timeout rescataba el caso — cinco minutos de
                // spinner para acabar diciendo "estado desconocido".
                //
                // `dropWhile` cubre el otro lado: `enqueueUniqueWork` registra de forma asíncrona,
                // así que las primeras emisiones pueden llegar vacías ANTES de que el trabajo
                // exista. Sin descartarlas, ese hueco inicial se leería como "ya desapareció" y el
                // mensaje saldría al instante en el caso normal.
                val finalInfo = kotlinx.coroutines.withTimeoutOrNull(REDOWNLOAD_WAIT_TIMEOUT_MS) {
                    workManager
                        .getWorkInfosForUniqueWorkFlow(WorkerTags.repairTag(song.id))
                        .map { it.firstOrNull() }
                        .dropWhile { it == null }
                        .first { it == null || it.state.isFinished }
                }
                val message = when (finalInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> context.getString(R.string.download_msg_complete, song.title)
                    WorkInfo.State.CANCELLED -> context.getString(R.string.download_msg_cancelled, song.title)
                    null -> context.getString(R.string.download_msg_unknown, song.title)
                    else -> {
                        val err = finalInfo.outputData.getString("error")
                        if (err != null) context.getString(R.string.download_msg_failed_reason, song.title, err)
                        else context.getString(R.string.download_msg_failed, song.title)
                    }
                }
                snackbarManager.show(message)
            } finally {
                _redownloadingIds.update { it - song.id }
            }
        }
    }

    /**
     * Feedback puntual desde la UI (p.ej. el estado de descarga al tocar una canción). Va por el
     * bus singleton, no por un `SnackbarHostState` de pantalla: el host único vive en el root y
     * así el mensaje sobrevive a overlays y cambios de destino.
     */
    fun showMessage(message: String) =
        snackbarManager.show(message, withDismissAction = true, replaceCurrent = true)

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchFilter = it.searchFilter.copy(searchQuery = query)) }
    }

    fun onShowSearchChanged(show: Boolean) {
        _uiState.update { it.copy(searchFilter = it.searchFilter.copy(showSearch = show)) }
    }

    fun onSortOrderChanged(order: SortOrder, filter: SongFilter) {
        when (filter) {
            SongFilter.ALL -> {
                _uiState.update { it.copy(sorting = it.sorting.copy(sortOrderAll = order)) }
                musicPreferences.saveSortOrder(order, SongFilter.ALL)
            }
            SongFilter.FAVORITES -> {
                _uiState.update { it.copy(sorting = it.sorting.copy(sortOrderFavorites = order)) }
                musicPreferences.saveSortOrder(order, SongFilter.FAVORITES)
            }
            else -> {}
        }
    }

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            repository.toggleFavorite(songId)
        }
    }

    // --- Settings Updates ---

    // --- ReplayGain ---

    /**
     * Preferir el ecualizador del sistema. Al activarlo se APAGA el EQ propio (ecualizar dos
     * veces suma coloración sin control); MusicPlaybackService observa eqEnabledFlow, deshace
     * la pipeline float y recupera el offload solo.
     */
    fun setUseSystemEq(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(useSystemEq = enabled)) }
        musicPreferences.saveUseSystemEq(enabled)
        if (enabled) musicPreferences.saveEqEnabled(false)
    }

    // --- Ecualizador: perfiles, presets ocultos y perfiles por ruta ---
    //
    // Misma fachada compartida que en PlaybackViewModel (la hoja del EQ vive en el reproductor y la
    // pantalla de gestión en Ajustes). La lógica está una sola vez en [EqPresetLibrary]; aquí solo
    // cambia la política de sharing (`WhileSubscribed`: Ajustes puede soltar el colector).
    val eqPresets = EqPresetLibrary(
        musicPreferences, viewModelScope, WhileUiSubscribed
    )

    // --- Pestañas de la biblioteca (orden + visibilidad) ---
    val libraryTabs: StateFlow<List<com.qhana.siku.data.model.LibraryTabState>> =
        musicPreferences.libraryTabsConfigFlow
            .stateIn(viewModelScope, WhileUiSubscribed, musicPreferences.loadLibraryTabsConfig())

    fun setLibraryTabs(list: List<com.qhana.siku.data.model.LibraryTabState>) =
        musicPreferences.saveLibraryTabsConfig(list)

    /**
     * Chips de "reproducir la biblioteca" en la pestaña **Todas**: se ven SOLO cuando la pestaña
     * **Inicio** no está, porque son el repuesto de sus acciones rápidas y no una segunda copia.
     *
     * Con Inicio oculta, esas dos acciones —aleatorio y en orden sobre toda la biblioteca— no
     * existían en ningún otro sitio de la app, y `LibraryTabsConfig.MIN_VISIBLE = 1` permite
     * quedarse sin ella. Las demás del inicio sí tienen otra puerta: Favoritos es una lista (con su
     * botonera) y los géneros tienen pestaña propia.
     *
     * La regla vive AQUÍ y no en la pantalla por lo mismo que [hasSourceSplit]: es una decisión
     * sobre cuándo existe un control, y escrita en el sitio de uso nada impediría que otra
     * superficie la interpretara distinto.
     */
    val showLibraryPlayChips: StateFlow<Boolean> = libraryTabs
        .map { tabs ->
            tabs.none { it.tab == com.qhana.siku.data.model.LibraryTabId.HOME && it.visible }
        }
        .distinctUntilChanged()
        // `false` de arranque: se asume que Inicio está (es el default de la app), así que los chips
        // no pueden asomar un frame en la configuración normal.
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    // --- Toolbar del NowPlaying ---
    val toolbarConfig: StateFlow<List<com.qhana.siku.data.model.ToolbarActionState>> =
        musicPreferences.toolbarConfigFlow
            .stateIn(viewModelScope, WhileUiSubscribed, musicPreferences.loadToolbarConfig())

    fun setToolbarConfig(list: List<com.qhana.siku.data.model.ToolbarActionState>) =
        musicPreferences.saveToolbarConfig(list)

    fun setReplayGainMode(mode: com.qhana.siku.data.model.ReplayGainMode) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(replayGainMode = mode)) }
        musicPreferences.saveReplayGainMode(mode)
        // Recalcular el volumen del item actual de inmediato (no solo en la próxima transición).
        musicController.refreshReplayGain()
    }

    fun setReplayGainPreamp(db: Float) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(replayGainPreamp = db)) }
        musicPreferences.saveReplayGainPreamp(db)
        musicController.refreshReplayGain()
    }

    // --- Apariencia ---

    fun setNowPlayingBackground(mode: NowPlayingBackground) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingBackground = mode)) }
        // PlaybackViewModel lo observa vía nowPlayingBackgroundFlow (DataStore).
        musicPreferences.saveNowPlayingBackground(mode)
    }

    /**
     * Dónde guardar las letras. El estado no se toca aquí: lo refresca el colector de
     * `lyricsSaveModeFlow`, que es el único escritor de ese campo (si además se actualizara a
     * mano, habría dos y el valor podría quedar congelado por el que no escribe).
     */
    fun setLyricsSaveMode(mode: LyricsSaveMode) = musicPreferences.saveLyricsSaveMode(mode)

    /** Carpeta SAF para los `.lrc` de la música del dispositivo. null = quitarla. */
    fun setLyricsFolder(uri: String?) = musicPreferences.saveLyricsFolderUri(uri)

    /** Gestos del reproductor (deslizar para cambiar/cerrar, doble toque para saltar). */
    fun setPlayerGestures(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(playerGestures = enabled)) }
        musicPreferences.savePlayerGestures(enabled)
    }

    /** Barra ondulada (Expressive) en el NowPlaying; el MiniPlayer no se toca (ver prefs). */
    fun setNowPlayingWavyProgress(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingWavyProgress = enabled)) }
        musicPreferences.saveNowPlayingWavyProgress(enabled)
    }

    /**
     * Grosor de la barra de progreso del NowPlaying, en dp. Se escribe en cada frame del arrastre
     * del slider, y por eso el estado se actualiza aquí en memoria: `MusicPreferences.update`
     * refresca su caché de forma síncrona y encola el volcado, así que la barra sigue al dedo sin
     * esperar al disco.
     */
    fun setNowPlayingProgressThickness(dp: Int) {
        // El slider avisa en CADA frame del arrastre, y con paradas discretas casi todos esos avisos
        // traen el valor que ya está puesto: sin esta guarda, un arrastre encola decenas de volcados
        // idénticos a disco (la cola FIFO de MusicPreferences vuelca el snapshot ENTERO por
        // escritura).
        if (dp == _uiState.value.nowPlayingProgressThickness) return
        _uiState.update {
            it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingProgressThickness = dp))
        }
        musicPreferences.saveNowPlayingProgressThickness(dp)
    }

    /** Palo del handle siempre visible en la barra del NowPlaying (false = solo al arrastrar). */
    fun setNowPlayingProgressHandle(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingProgressHandle = enabled)) }
        musicPreferences.saveNowPlayingProgressHandle(enabled)
    }

    /** Forma del MiniPlayer: rectángulo redondeado (true) o píldora (false, el diseño actual). */
    fun setMiniPlayerRoundedRect(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(miniPlayerRoundedRect = enabled)) }
        musicPreferences.saveMiniPlayerRoundedRect(enabled)
    }

    /** Forma del botón de play del MiniPlayer: círculo (true) o squircle (false, el default). */
    fun setMiniPlayerRoundPlayButton(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(miniPlayerRoundPlayButton = enabled)) }
        musicPreferences.saveMiniPlayerRoundPlayButton(enabled)
    }

    /**
     * Pestañas de la biblioteca abajo, en una navigation bar. Solo aplica en VERTICAL: girado hay
     * rail siempre, encendido o no (ver `libraryChrome`).
     */
    fun setLibraryBottomTabs(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(libraryBottomTabs = enabled)) }
        musicPreferences.saveLibraryBottomTabs(enabled)
    }

    /**
     * Ficha técnica en el chip de formato del NowPlaying. El OTRO escritor es el propio chip
     * (PlaybackViewModel), así que aquí se refleja además el valor de DataStore en el uiState:
     * si se conmuta desde el reproductor, este switch tiene que verse ya cambiado al entrar.
     */
    fun setNowPlayingDetailedFormat(enabled: Boolean) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(nowPlayingDetailedFormat = enabled)) }
        musicPreferences.saveNowPlayingDetailedFormat(enabled)
    }

    /**
     * Estilo de paleta del tema. NO hace falta regenerar colores al cambiarlo: el estilo actúa
     * sobre el seed ya guardado, así que el tema se repinta solo (MainActivity observa el flow).
     */
    fun setThemePaletteStyle(styleName: String) {
        _uiState.update { it.copy(playbackSettings = it.playbackSettings.copy(themePaletteStyle = styleName)) }
        musicPreferences.saveThemePaletteStyle(styleName)
    }

    fun regenerateColors() {
        viewModelScope.launch {
            if (_uiState.value.isRegeneratingColors) return@launch
            _uiState.update { it.copy(colorTuning = it.colorTuning.copy(isRegeneratingColors = true)) }
            try {
                repository.resetAllColors()
                artworkRepository.clearCache()
                // Regenerar es explícito: las marcas de color manual también se van (si no,
                // quedarían huérfanas apuntando a colores que acaban de resetearse a NULL).
                musicPreferences.clearManualColorIds()
                snackbarManager.show(context.getString(R.string.colors_regenerated))
                _colorsRegeneratedEvent.emit(Unit)
            } catch (e: Exception) {
                snackbarManager.show(context.getString(R.string.colors_regenerate_error, e.message ?: ""))
            } finally {
                _uiState.update { it.copy(colorTuning = it.colorTuning.copy(isRegeneratingColors = false)) }
            }
        }
    }

    // --- Playlists ---
    // (Eliminado PlaylistUseCase: era pass-through puro al repositorio; ahora se llama directo).

    /**
     * Crea la lista y entrega su id por [onCreated] (en Main): la pestaña Listas lo usa para
     * navegar directo al detalle recién creado (donde vive "añadir canciones") y la hoja
     * "agregar a lista" para meter la canción pendiente en la lista nueva.
     */
    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repository.createPlaylist(name.trim())
        onCreated(id)
    }
    fun deletePlaylist(playlistId: Long) = viewModelScope.launch {
        repository.deletePlaylist(playlistId)
    }
    fun renamePlaylist(playlistId: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.renamePlaylist(playlistId, name.trim())
    }
    fun addSongToPlaylist(playlistId: Long, songId: String) = viewModelScope.launch {
        repository.addSongToPlaylist(playlistId, songId)
    }
    fun addSongsToPlaylist(playlistId: Long, songIds: List<String>) = viewModelScope.launch {
        notifySongsAdded(repository.addSongsToPlaylist(playlistId, songIds))
    }
    fun addSongsToFavorites(songIds: List<String>) = viewModelScope.launch {
        notifySongsAdded(repository.addSongsToFavorites(songIds))
    }
    private fun notifySongsAdded(added: Int) {
        if (added > 0) {
            snackbarManager.show(
                context.resources.getQuantityString(R.plurals.playlist_songs_added, added, added)
            )
        }
    }
    fun removeSongFromPlaylist(playlistId: Long, songId: String) = viewModelScope.launch {
        repository.removeSongFromPlaylist(playlistId, songId)
    }
    fun reorderPlaylistSongs(playlistId: Long, songIds: List<String>) = viewModelScope.launch {
        repository.reorderPlaylistSongs(playlistId, songIds)
    }
    fun getPlaylistSongs(playlistId: Long) = repository.getSongsForPlaylist(playlistId)

    /** Conteo + carátulas por playlist para los thumbnails de la pestaña Listas. */
    val playlistsCoverMeta = repository.getPlaylistsCoverMeta()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyMap())

    // --- Selector "añadir canciones a la lista" ---
    // Búsqueda propia, independiente de la de la biblioteca: la hoja se abre desde el detalle
    // de una lista y no debe alterar lo que el usuario tenía filtrado en la pantalla de inicio.

    private val _songPickerQuery = MutableStateFlow("")
    val songPickerQuery: StateFlow<String> = _songPickerQuery.asStateFlow()

    val songPickerResults: StateFlow<List<Song>> = _songPickerQuery
        .debounce(SONG_PICKER_DEBOUNCE_MS)
        .flatMapLatest { query ->
            flow { emit(repository.getSongsSnapshot(query, SortOrder.TITLE_ASC)) }
                // El catch va DENTRO del flatMapLatest: fuera mataría el flow para siempre
                // tras el primer error y la búsqueda dejaría de responder.
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    fun setSongPickerQuery(query: String) {
        _songPickerQuery.value = query
    }

    fun playPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val songs = repository.getSongsForPlaylist(playlistId).first()
            if (songs.isNotEmpty()) {
                // Graba el contexto (nombre de la lista viva + carátula de la primera canción)
                // para "Seguir escuchando".
                val name = _uiState.value.data.playlists.firstOrNull { it.id == playlistId }?.name
                if (name != null) {
                    recordContext(
                        PlaybackContext.Playlist(playlistId, name, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                }
                withContext(Dispatchers.Main) {
                    musicController.setPlaylistAndPlay(songs, 0)
                }
            }
        }
    }

    private companion object {
        // Tope de ítems por carrusel de la home (canciones). Suficiente para llenar la fila
        // horizontal sin traer listas grandes a memoria.
        const val HOME_SECTION_LIMIT = 15
        // Mínimo de canciones para que un artista alimente "Porque escuchaste a X": con menos no
        // justifica un carrusel de "más de este artista" (1 sola = tarjeta suelta absurda).
        private const val MIN_ARTIST_PICK_SONGS = 3

        /** Menos de esto no da un carrusel de propuestas. Ver [HomeArtistPick]. */
        private const val MIN_RELATED_ARTISTS = 2
        // Chips de género del inicio: hasta 5 géneros con al menos 5 canciones cada uno.
        private const val GENRE_MIN_COUNT = 5
        private const val GENRE_CHIP_LIMIT = 5

        /**
         * Candidatos entre los que se eligen los [GENRE_CHIP_LIMIT] chips del día. Tres veces los
         * huecos: suficiente para que la fila cambie de verdad de un día a otro sin bajar a géneros
         * marginales (el suelo lo sigue poniendo [GENRE_MIN_COUNT]). Si tu biblioteca no llega a
         * tantos géneros, la rotación no tiene de dónde tirar y la fila se queda fija — eso se
         * ensancha bajando el umbral, no subiendo esto.
         */
        private const val GENRE_POOL_LIMIT = GENRE_CHIP_LIMIT * 3
        // Ventanas de tiempo del inicio: "esta semana" para el stat del saludo, "no escuchada
        // en ~2 semanas" para la sección de redescubrimiento.
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val REDISCOVER_MILLIS = 14L * 24 * 60 * 60 * 1000

        /**
         * Umbral con el que se considera "batería baja". NO es una elección de esta pantalla: es el
         * valor con el que Android evalúa la constraint `BATTERY_NOT_LOW` que el `ScanWorker` ya
         * declara, y que el framework no expone por ninguna API pública. Se replica aquí porque la
         * UI tiene que explicar por adelantado una decisión que toma WorkManager; si alguna vez
         * discrepan, la que manda es la constraint y esto solo mentiría al usuario.
         */
        private const val BATTERY_LOW_PERCENT = 15

        /**
         * Cuánto se mantiene girando el indicador de una re-descarga manual sin noticias del trabajo.
         *
         * **Ya no cubre la desaparición del `WorkInfo`**: eso se detecta en el propio flow y suelta
         * el indicador en el acto. Lo único que queda debajo de este techo es que el trabajo NO
         * LLEGUE A REGISTRARSE nunca, que desde fuera es indistinguible de "todavía no se registró"
         * — y por eso hace falta un plazo y no una condición.
         *
         * O sea que es una decisión de INTERFAZ y no una estimación de descarga: un trabajo esperando
         * su constraint de red puede tardar horas legítimamente, y pasado cierto punto un spinner
         * deja de informar y empieza a parecer que la app se colgó. Cinco minutos es holgado frente a
         * una descarga normal —así casi nunca corta una que iba bien— y corto frente a "esto se quedó
         * ahí". Soltarlo NO cancela nada: el trabajo sigue su curso y la canción aparecerá descargada
         * cuando termine.
         */
        const val REDOWNLOAD_WAIT_TIMEOUT_MS = 5 * 60_000L

        /**
         * Espera antes de consultar el buscador del selector de canciones. Absorbe la ráfaga de
         * teclas de quien escribe seguido: cada emisión es una consulta a Room sobre la biblioteca
         * entera, y sin esto se lanzaba una por letra para tirar todas menos la última.
         */
        const val SONG_PICKER_DEBOUNCE_MS = 200L
    }

    /**
     * Traduce el estado EN CURSO del sync a banner. `null` = "no toques el banner".
     *
     * Vive fuera del colector porque la consulta el tick de cortesía además de la señal de progreso
     * (ver [BANNER_GRACE_MS]): cuando el tick llega, hay que volver a mapear el estado ACTUAL, y
     * tener la traducción en un solo sitio es lo que impide que las dos rutas diverjan.
     */
    private fun progressBanner(status: SyncStatus): LibraryBannerState? = when (status) {
        is SyncStatus.Scanning ->
            LibraryBannerState.Scanning(status.found, status.message)
        is SyncStatus.Downloading -> LibraryBannerState.Downloading(
            status.current, status.total, status.failed, status.message
        )
        is SyncStatus.Preparing -> LibraryBannerState.Preparing(
            status.current, status.total, status.message
        )
        is SyncStatus.Error -> LibraryBannerState.Error(status.message)
        // Detenido por el entorno: se anuncia igual mientras se espera que
        // como estado final, porque para quien mira es la misma situación.
        // NO lleva acción: reanudar con datos móviles ya se decide en Ajustes,
        // y para escuchar ahora mismo está el streaming.
        is SyncStatus.Paused -> LibraryBannerState.Paused(status.message)
        // **Terminó: NO se toca el banner, se deja lo que haya.** `SyncManager`
        // publica `_state.value = complete` y en la línea siguiente emite el evento
        // `Finished`, así que son DOS emisiones seguidas del flujo fusionado. Poner
        // Hidden en la primera hacía que el banner se plegara del todo —la lista
        // saltaba hacia arriba— para volver a desplegarse un frame después con el
        // resumen. Visible en vídeo (17 ago 2026): "Escaneando" → nada → "Biblioteca
        // al día". Dejándolo intacto, el resumen RELEVA al de progreso sin hueco.
        //
        // No hay riesgo de que se quede colgado: `Complete` es un estado retenido, y
        // si el evento no llegara (nadie suscrito cuando se emitió) lo que hay es
        // Hidden de todos modos. El camino que sí necesita limpiar es Idle.
        is SyncStatus.Complete -> null
        // Idle es "no hay corrida": cancelación, logout, `release()`. Ahí sí se
        // retira lo que estuviera puesto, y es la red que impide que un "Escaneando"
        // sobreviva a un sync abortado.
        is SyncStatus.Idle -> LibraryBannerState.Hidden
    }

}
