package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.data.repository.BrowseRepository
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsRepository
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.data.repository.StatsSummary
import com.qhana.siku.data.util.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Cuántos álbumes y géneros DISTINTOS hay en el periodo.
 *
 * **No incluye los rankings**, que van en [StatsRankings] y por su propio camino: éstos son dos
 * enteros que encabezan una sección y aquéllos son cuatro listas. Se separan porque se consumen
 * distinto —el conteo lo lee la cabecera y las listas las filas— y porque así una sola de las dos
 * consultas invalidada por el sync no arrastra a la otra.
 *
 * Los conteos de canciones y artistas NO están: el resumen ya los trae de una consulta de una sola
 * tabla. Estos dos necesitan JOIN con `songs`, y por eso este flujo es el que se muestrea durante
 * el sync.
 */
data class StatsCounts(
    val albums: Int = 0,
    val genres: Int = 0
)

/**
 * Los CUATRO rankings del periodo, cada uno con sus diez entradas, más el denominador de los
 * porcentajes de género.
 *
 * Las canciones van en su propio tipo y las otras tres comparten uno: una canción se REPRODUCE (y su
 * fila necesita la entidad viva para poder hacerlo) mientras que un artista, un álbum o un género
 * NAVEGAN a su detalle. Unificarlos en un modelo con campos nullables escondería esa diferencia
 * justo donde importa.
 */
data class StatsRankings(
    val songs: List<StatsSongEntry> = emptyList(),
    val artists: List<StatsNameEntry> = emptyList(),
    val albums: List<StatsNameEntry> = emptyList(),
    val genres: List<StatsNameEntry> = emptyList(),
    /**
     * Reproducciones del periodo cuya canción tiene género: el DENOMINADOR de los porcentajes de
     * [genres], no el total del periodo. Ver `PlayEventDao.classifiedPlaysFlow`.
     */
    val classifiedPlays: Int = 0
) {
    /**
     * Qué fracción de lo etiquetado se lleva ese género, en `[0,1]`.
     *
     * Va aquí y no en la pantalla porque el denominador correcto es la parte fácil de equivocar: con
     * `summary.plays` los porcentajes de una biblioteca a medio etiquetar sumarían bastante menos de
     * 100 y nada diría por qué.
     */
    fun shareOf(genre: StatsNameEntry): Float =
        if (classifiedPlays <= 0) 0f else genre.plays.toFloat() / classifiedPlays
}

/**
 * Los totales de un periodo junto al tiempo escuchado en el anterior. Interno del ViewModel: a la
 * pantalla llegan por separado dentro de [StatsUiState].
 */
private data class SelectionTotals(
    val summary: StatsSummary,
    val previousListenedMs: Long?
)

data class StatsUiState(
    val selection: StatsSelection,
    val summary: StatsSummary = StatsSummary(0, null, 0, 0),
    val counts: StatsCounts = StatsCounts(),
    /** Días seguidos escuchando; 0 = sin racha viva. */
    val streakDays: Int? = null,
    /** Epoch ms del primer evento del log, o `null` si aún no hay ninguno. */
    val historyStartedAt: Long? = null,
    /**
     * Tiempo escuchado en el periodo ANTERIOR, para la comparación de la tarjeta.
     *
     * **Es tiempo y no reproducciones porque el titular de la tarjeta es el tiempo**: comparar una
     * magnitud distinta de la que encabeza la tarjeta hace que el porcentaje parezca referirse al
     * número grande cuando no lo hace. Además el número de reproducciones depende de lo largas que
     * sean las canciones, así que dos meses con el mismo rato de escucha pueden diferir un 40 % en
     * reproducciones sin que haya pasado nada.
     *
     * `null` cuando no hay con qué comparar, que son tres casos: el DÍA (ver
     * `StatsViewModel.comparesWithPrevious` — entre dos días manda la varianza), "Siempre" (no
     * existe un siempre anterior) y cuando el periodo previo cae entero antes del primer evento
     * registrado — ahí un "100 % más" solo diría que el historial empieza ahí, no que se escuchó
     * más.
     */
    val previousListenedMs: Long? = null,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
) {
    /**
     * Variación del tiempo escuchado contra el periodo anterior, en porcentaje entero. `null` si no
     * hay comparación posible o si el anterior fue CERO: partir por cero no da un porcentaje, y
     * "infinito por ciento" no es un dato que enseñar.
     */
    val listenedDeltaPercent: Int?
        get() {
            val current = summary.listenedMs ?: return null
            val previous = previousListenedMs ?: return null
            if (previous <= 0L) return null
            return Math.round((current - previous) * 100.0 / previous).toInt()
        }

    /**
     * Sin historial y sin agregados: no hay absolutamente nada que contar todavía.
     *
     * Basta con el resumen: sus `plays` salen de la misma fuente que todo lo demás del periodo, así
     * que cero reproducciones implica cero de todo. Antes se le sumaba `tops.isEmpty`, que era
     * redundante y desapareció con los rankings.
     */
    val isCompletelyEmpty: Boolean get() = summary.isEmpty
}

/**
 * Estado de la pantalla de estadísticas.
 *
 * Es de UNA sola pantalla, así que va con default `= hiltViewModel()` en el composable (el caso
 * explícito de `BackupViewModel` en la convención 9). Corolario de esa misma convención: **nada
 * caro en el `init`** — todo cuelga de `WhileUiSubscribed`, y las consultas solo corren mientras la
 * pantalla se ve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val browseRepository: BrowseRepository,
    // Solo para la agrupación de artistas (v29): el ranking cuenta por los mismos créditos que la
    // pestaña Artistas, y sigue en vivo el ajuste.
    musicPreferences: MusicPreferences,
    syncManager: SyncManager
) : ViewModel() {

    private val artistRole: Flow<ArtistCreditRole> = musicPreferences.artistCreditRoleFlow

    private companion object {
        /**
         * Entradas por ranking: **diez**, que es el tamaño en que un "top" se lee de un vistazo —con
         * cinco se queda en anécdota y con veinte ya es una lista en la que no destaca nada—.
         *
         * Es el criterio que gobernaba estos mismos rankings antes de su viaje a una pantalla
         * propia; allí pasó a 500 "para acotar el peor caso", que es una razón de CONSULTA y no de
         * producto, y con 500 la pantalla dejaba de ser un top para ser la biblioteca reordenada por
         * reproducciones. **Y con diez es con lo que las cuatro categorías caben en la pantalla sin
         * desplegar nada**, que es lo que hizo posible quitar primero la pantalla intermedia y
         * después el propio desplegable.
         */
        const val TOP_ENTRIES = 10

        /**
         * Tope de días que se traen para calcular la racha. Una racha de más de un año no se va a
         * dar, y el tope mantiene acotada la consulta en el peor caso.
         */
        const val STREAK_SCAN_DAYS = 400
    }

    /**
     * Ver `SyncAwareSampling`: solo gobierna los RANKINGS, que llevan `JOIN songs`. El resumen y la
     * racha miran únicamente `play_events` —tabla en la que el sync no escribe—, así que Room no
     * las invalida y muestrearlas sería un operador que no hace nada.
     */
    private val libraryIsSettling: Flow<Boolean> = syncManager.settlingFlow()

    private val _selection = MutableStateFlow(
        StatsSelection.current(ZoneId.systemDefault(), Instant.now())
    )
    val selection: StateFlow<StatsSelection> = _selection

    /** Cambia de modo conservando el ancla, para que "año" abra en el año del mes que se miraba. */
    fun selectMode(mode: StatsPeriodMode) {
        _selection.value = _selection.value.copy(mode = mode)
    }

    fun goToPrevious() {
        val floor = historyFloor() ?: return
        _selection.value = _selection.value.previous(floor) ?: return
    }

    fun goToNext() {
        _selection.value = _selection.value.next(today()) ?: return
    }

    private val firstEventAt: Flow<Long?> = statsRepository.firstEventAt()

    /**
     * Los totales del periodo y, si existe, el tiempo escuchado en el ANTERIOR para compararlos.
     *
     * Van en el mismo flujo y no en dos porque el `combine` que arma [uiState] ya usa las cinco
     * posiciones de la sobrecarga tipada de `combine`, y la que queda por encima devuelve un
     * `Array<Any?>` que hay que castear a mano — un `when` de tipos perdidos a cambio de nada.
     * Además el par tiene sentido junto: sin el actual, el anterior no significa nada.
     */
    private val totals: Flow<SelectionTotals> =
        combine(_selection, firstEventAt) { selection, firstEvent -> selection to firstEvent }
            .flatMapLatest { (selection, firstEvent) ->
                // El rango se recalcula en CADA cambio de selector y no una vez al construir el VM:
                // la pantalla puede estar abierta cuando pasa la medianoche.
                val zone = ZoneId.systemDefault()
                val current =
                    if (selection.mode == StatsPeriodMode.ALL) statsRepository.allTimeSummary()
                    else statsRepository.summary(
                        selection.sinceMillis(zone),
                        selection.untilMillis(zone)
                    )
                // `previous` ya devuelve null en "Siempre" y cuando el periodo anterior cae entero
                // antes del primer evento: comparar contra un tramo sin historial daría siempre un
                // "+100 %" que solo describe dónde empieza el log.
                //
                // `comparesWithPrevious` quita además el DÍA. Ver su KDoc: es la regla de PRODUCTO,
                // y de paso ahorra la consulta del rango anterior en el periodo con el que la
                // pantalla abre.
                val previous = firstEvent
                    ?.takeIf { comparesWithPrevious(selection.mode) }
                    ?.let { selection.previous(dateOf(it)) }
                if (previous == null) current.map { SelectionTotals(it, previousListenedMs = null) }
                else combine(
                    current,
                    statsRepository.summary(
                        previous.sinceMillis(zone),
                        previous.untilMillis(zone)
                    )
                ) { now, before -> SelectionTotals(now, before.listenedMs) }
            }

    /**
     * El histograma de actividad del periodo, en crudo. `null` donde no lo hay: ver
     * [ActivityBucketing.forMode].
     *
     * Es la única consulta nueva de este rediseño y **no lleva `sampledDuringSync`** a propósito:
     * menciona solo `play_events`, tabla en la que el sync no escribe, así que Room no la invalida
     * y muestrearla sería un operador que no hace nada (mismo criterio que el resumen y la racha).
     */
    val activity: StateFlow<StatsActivity?> = _selection.flatMapLatest { selection ->
        // El tipo va EXPLÍCITO: sin él, la rama del `null` infiere `Flow<Nothing?>` y la del dato
        // `Flow<StatsActivity>`, y el lambda se queda con lo que vea primero.
        val bucketing = ActivityBucketing.forMode(selection.mode)
            ?: return@flatMapLatest flowOf<StatsActivity?>(null)
        val zone = ZoneId.systemDefault()
        statsRepository
            .activity(selection.sinceMillis(zone), selection.untilMillis(zone), bucketing.sqlFormat)
            .map { plays -> StatsActivity(selection, bucketing, plays) }
    }.stateIn(viewModelScope, WhileUiSubscribed, null)

    private val counts: Flow<StatsCounts> = _selection.flatMapLatest { selection ->
        val zone = ZoneId.systemDefault()
        val since = selection.sinceMillis(zone)
        val until = selection.untilMillis(zone)
        val all = selection.mode == StatsPeriodMode.ALL
        combine(
            if (all) statsRepository.allTimeDistinctAlbums()
            else statsRepository.distinctAlbums(since, until),
            if (all) statsRepository.allTimeDistinctGenres()
            else statsRepository.distinctGenres(since, until)
        ) { albums, genres -> StatsCounts(albums, genres) }
            // Llevan JOIN con `songs`, así que se re-ejecutan con cada escritura de un sync.
            .sampledDuringSync(libraryIsSettling)
    }

    private val streakDays: Flow<Int> = statsRepository.recentDays(STREAK_SCAN_DAYS)
        .map { computeStreak(it, LocalDate.now(ZoneId.systemDefault())) }

    /**
     * Los cuatro rankings del periodo, cada uno completo, más el denominador de los porcentajes de
     * género.
     *
     * **Se traen los cuatro a la vez y enteros.** Hubo una versión que solo pedía el de la sección
     * abierta, y otra que traía diez para enseñar tres: las dos existían por el desplegable, que ya
     * no está — las cuatro categorías se ven siempre, así que las cuatro consultas pintan siempre
     * algo. Correr una consulta para mostrar su resultado es el trato normal; lo que no lo era es
     * correrla para nada, o traer siete filas que nadie iba a ver.
     *
     * `sampledDuringSync` porque llevan `JOIN songs` y Room invalida por TABLA: durante un sync
     * masivo se re-ejecutarían decenas de veces por segundo.
     */
    val rankings: StateFlow<StatsRankings> = combine(_selection, artistRole) { s, r -> s to r }.flatMapLatest { (selection, role) ->
        val zone = ZoneId.systemDefault()
        val since = selection.sinceMillis(zone)
        val until = selection.untilMillis(zone)
        val all = selection.mode == StatsPeriodMode.ALL
        combine(
            if (all) statsRepository.allTimeTopSongs(TOP_ENTRIES)
            else statsRepository.topSongs(since, until, TOP_ENTRIES),
            if (all) statsRepository.allTimeTopArtists(role, TOP_ENTRIES)
            else statsRepository.topArtists(role, since, until, TOP_ENTRIES),
            if (all) statsRepository.allTimeTopAlbums(TOP_ENTRIES)
            else statsRepository.topAlbums(since, until, TOP_ENTRIES),
            if (all) statsRepository.allTimeTopGenres(TOP_ENTRIES)
            else statsRepository.topGenres(since, until, TOP_ENTRIES),
            if (all) statsRepository.allTimeClassifiedPlays()
            else statsRepository.classifiedPlays(since, until)
        ) { songs, artists, albums, genres, classified ->
            StatsRankings(songs, artists, albums, genres, classified)
        }.sampledDuringSync(libraryIsSettling)
    }
        // DESPUÉS del muestreo, no dentro: lo que hay que espaciar durante un sync son los cuatro
        // JOIN de arriba. Esta consulta mira solo `artists`, así que ni es cara ni la invalidan las
        // escrituras en `songs`.
        .flatMapLatest { ranking -> withArtistPhotos(ranking) }
        .stateIn(viewModelScope, WhileUiSubscribed, StatsRankings())

    /**
     * Cambia la carátula de cada artista por su FOTO, cuando la haya.
     *
     * El ranking sale de `PlayEventDao.topArtistsFlow`, cuyo `fallbackArtUri` es un
     * `MAX(albumArtUriString)`: la portada de alguno de sus álbumes. Sirve de respaldo pero no es lo
     * que la tarjeta representa — y en el podio se notaba doble, porque el mismo cuadro salía en el
     * carrusel de artistas y en el de álbumes.
     *
     * **Se resuelve AQUÍ y no con un JOIN en la consulta**, que es el mismo criterio que ya siguen
     * "Seguir escuchando" y "Porque escuchaste a X" (`LibraryViewModel`): la foto es TARDÍA —la trae
     * el backfill de Deezer, que corre al final de cada sync— y MUTABLE —el picker la cambia, y
     * "ninguno de estos" la quita a propósito—. Un JOIN la congelaría en lo que hubiera en el
     * instante de la consulta; observando `artists` aparte, la tarjeta se actualiza sola cuando
     * llega.
     *
     * Con esto el podio completa la MISMA cascada que las otras cuatro superficies de artista de la
     * app: foto → carátula de alguno de sus álbumes → placeholder.
     */
    private fun withArtistPhotos(ranking: StatsRankings): Flow<StatsRankings> {
        val names = ranking.artists.map { it.name }
        // Sin artistas no hay nada que preguntar, y `getArtistPhotos(emptyList())` sería una consulta
        // con un `IN ()` vacío por cada emisión del ranking.
        if (names.isEmpty()) return flowOf(ranking)
        return browseRepository.getArtistPhotos(names).map { photos ->
            ranking.copy(
                artists = ranking.artists.map { entry ->
                    // Sin foto se queda con lo que traía: la carátula es el segundo escalón de la
                    // cascada, no un error.
                    photos[entry.name]?.let { entry.copy(artUri = it) } ?: entry
                }
            )
        }
    }

    val uiState: StateFlow<StatsUiState> = combine(
        _selection, totals, counts, streakDays, firstEventAt
    ) { selection, totals, counts, streak, firstEvent ->
        val floor = firstEvent?.let { dateOf(it) }
        StatsUiState(
            selection = selection,
            summary = totals.summary,
            counts = counts,
            previousListenedMs = totals.previousListenedMs,
            // La racha se enseña en TODOS los periodos, "Siempre" incluido.
            //
            // Estuvo oculta ahí con este argumento: "ese periodo sale de `songs.playCount`, que
            // nunca supo en qué días se escuchó". El argumento confundía dos cosas — la racha NUNCA
            // salió de los agregados, sale de `play_events` (ver `recentDays`), así que no depende
            // del periodo elegido ni un poco. Se notó al subirla por encima del selector (28 ago
            // 2026): un dato que vive fuera de ese control no puede desaparecer al moverlo.
            streakDays = streak,
            historyStartedAt = firstEvent,
            canGoPrevious = floor != null && selection.previous(floor) != null,
            canGoNext = selection.next(today()) != null
        )
    }.stateIn(
        viewModelScope,
        WhileUiSubscribed,
        StatsUiState(selection = _selection.value)
    )

    /**
     * Si el periodo se compara con el anterior.
     *
     * **El DÍA no se compara** (device, 30 ago 2026, decisión del usuario: *"ese dato podría estar
     * en semana y en mes e incluso hasta año pero no en día"*). Entre dos días la varianza es el
     * dato dominante: una tarde larga contra una noche corta da un −83 % que no describe ninguna
     * tendencia, solo que un día se escuchó y el otro no tanto — y un porcentaje enorme que no
     * significa nada enseña a ignorar la línea entera. A partir de la semana el ruido se promedia y
     * la comparación empieza a decir algo.
     *
     * "Siempre" tampoco, pero por otro motivo: no existe un siempre anterior.
     */
    private fun comparesWithPrevious(mode: StatsPeriodMode): Boolean = when (mode) {
        StatsPeriodMode.WEEK, StatsPeriodMode.MONTH, StatsPeriodMode.YEAR -> true
        StatsPeriodMode.DAY, StatsPeriodMode.ALL -> false
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun dateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    /** El mes del primer evento: por debajo de él no hay nada que enseñar. */
    private fun historyFloor(): LocalDate? = uiState.value.historyStartedAt?.let { dateOf(it) }
}
