package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Los cuatro rankings de un periodo. Viajan juntos porque se piden y se pintan juntos. */
data class StatsTops(
    val songs: List<StatsSongEntry> = emptyList(),
    val artists: List<StatsNameEntry> = emptyList(),
    val albums: List<StatsNameEntry> = emptyList(),
    val genres: List<StatsNameEntry> = emptyList()
) {
    val isEmpty: Boolean get() =
        songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && genres.isEmpty()
}

data class StatsUiState(
    val selection: StatsSelection,
    val summary: StatsSummary = StatsSummary(0, null, 0, 0),
    val tops: StatsTops = StatsTops(),
    /** Días seguidos escuchando; 0 = sin racha viva. `null` en "Siempre": ese dato no existe ahí. */
    val streakDays: Int? = null,
    /** Epoch ms del primer evento del log, o `null` si aún no hay ninguno. */
    val historyStartedAt: Long? = null,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
) {
    /** Sin historial y sin agregados: no hay absolutamente nada que contar todavía. */
    val isCompletelyEmpty: Boolean get() = summary.isEmpty && tops.isEmpty
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
        const val TOP_LIMIT = 10

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
        _selection.value = _selection.value.next(currentMonth()) ?: return
    }

    private val firstEventAt: Flow<Long?> = statsRepository.firstEventAt()

    private val summary: Flow<StatsSummary> = _selection.flatMapLatest { selection ->
        // El rango se recalcula en CADA cambio de selector y no una vez al construir el VM: la
        // pantalla puede estar abierta cuando pasa la medianoche.
        val zone = ZoneId.systemDefault()
        if (selection.mode == StatsPeriodMode.ALL) statsRepository.allTimeSummary()
        else statsRepository.summary(selection.sinceMillis(zone), selection.untilMillis(zone))
    }

    private val tops: Flow<StatsTops> = _selection.flatMapLatest { selection ->
        val zone = ZoneId.systemDefault()
        val since = selection.sinceMillis(zone)
        val until = selection.untilMillis(zone)
        val all = selection.mode == StatsPeriodMode.ALL
        combine(
            if (all) statsRepository.allTimeTopSongs(TOP_LIMIT)
            else statsRepository.topSongs(since, until, TOP_LIMIT),
            if (all) statsRepository.allTimeTopArtists(TOP_LIMIT)
            else statsRepository.topArtists(since, until, TOP_LIMIT),
            if (all) statsRepository.allTimeTopAlbums(TOP_LIMIT)
            else statsRepository.topAlbums(since, until, TOP_LIMIT),
            if (all) statsRepository.allTimeTopGenres(TOP_LIMIT)
            else statsRepository.topGenres(since, until, TOP_LIMIT)
        ) { songs, artists, albums, genres -> StatsTops(songs, artists, albums, genres) }
            .sampledDuringSync(libraryIsSettling)
    }

    private val streakDays: Flow<Int> = statsRepository.recentDays(STREAK_SCAN_DAYS)
        .map { computeStreak(it, LocalDate.now(ZoneId.systemDefault())) }

    val uiState: StateFlow<StatsUiState> = combine(
        _selection, summary, tops, streakDays, firstEventAt
    ) { selection, summary, tops, streak, firstEvent ->
        val floor = firstEvent?.let { monthOf(it) }
        StatsUiState(
            selection = selection,
            summary = summary,
            tops = tops,
            // En "Siempre" la racha no se oculta por decisión de diseño: es que no existe. Ese
            // periodo sale de `songs.playCount`, que nunca supo en qué días se escuchó.
            streakDays = streak.takeIf { selection.mode != StatsPeriodMode.ALL },
            historyStartedAt = firstEvent,
            canGoPrevious = floor != null && selection.previous(floor) != null,
            canGoNext = selection.next(currentMonth()) != null
        )
    }.stateIn(
        viewModelScope,
        WhileUiSubscribed,
        StatsUiState(selection = _selection.value)
    )

    private fun currentMonth(): YearMonth = YearMonth.from(Instant.now().atZone(ZoneId.systemDefault()))

    private fun monthOf(epochMs: Long): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** El mes del primer evento: por debajo de él no hay nada que enseñar. */
    private fun historyFloor(): YearMonth? = uiState.value.historyStartedAt?.let { monthOf(it) }
}
