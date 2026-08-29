package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.data.repository.StatsRepository
import com.qhana.siku.data.repository.StatsSummary
import com.qhana.siku.data.util.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Cuántos álbumes y géneros DISTINTOS hay en el periodo.
 *
 * **Aquí vivían también los cuatro rankings** (top canciones / artistas / álbumes / géneros), y se
 * fueron el 28 ago 2026 con las listas que los pintaban: ahora cada categoría es una tarjeta que
 * abre su ranking completo en [StatsListViewModel], que lo consulta cuando se entra. Dejarlos aquí
 * habría sido correr cuatro consultas con JOIN en cada cambio de periodo para no enseñar ninguna.
 *
 * Los conteos de canciones y artistas NO están: el resumen ya los trae de una consulta de una sola
 * tabla. Estos dos necesitan JOIN con `songs`, y por eso este flujo es el que se muestrea durante
 * el sync.
 */
data class StatsCounts(
    val albums: Int = 0,
    val genres: Int = 0
)

data class StatsUiState(
    val selection: StatsSelection,
    val summary: StatsSummary = StatsSummary(0, null, 0, 0),
    val counts: StatsCounts = StatsCounts(),
    /** Días seguidos escuchando; 0 = sin racha viva. `null` en "Siempre": ese dato no existe ahí. */
    val streakDays: Int? = null,
    /** Epoch ms del primer evento del log, o `null` si aún no hay ninguno. */
    val historyStartedAt: Long? = null,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
) {
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
    syncManager: SyncManager
) : ViewModel() {

    private companion object {
        /**
         * Entradas por ranking. Diez es el tamaño en que un "top" se lee de un vistazo: con cinco
         * se queda en anécdota y con veinte, multiplicado por cuatro categorías, la pantalla se
         * vuelve una lista larga en la que ya no destaca nada.
         */

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

    private val summary: Flow<StatsSummary> = _selection.flatMapLatest { selection ->
        // El rango se recalcula en CADA cambio de selector y no una vez al construir el VM: la
        // pantalla puede estar abierta cuando pasa la medianoche.
        val zone = ZoneId.systemDefault()
        if (selection.mode == StatsPeriodMode.ALL) statsRepository.allTimeSummary()
        else statsRepository.summary(selection.sinceMillis(zone), selection.untilMillis(zone))
    }

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

    val uiState: StateFlow<StatsUiState> = combine(
        _selection, summary, counts, streakDays, firstEventAt
    ) { selection, summary, counts, streak, firstEvent ->
        val floor = firstEvent?.let { dateOf(it) }
        StatsUiState(
            selection = selection,
            summary = summary,
            counts = counts,
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

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun dateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    /** El mes del primer evento: por debajo de él no hay nada que enseñar. */
    private fun historyFloor(): LocalDate? = uiState.value.historyStartedAt?.let { dateOf(it) }
}
