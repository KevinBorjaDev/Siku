package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsRepository
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.data.util.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Qué ranking se está listando. El nombre del enum viaja en la ruta. */
enum class StatsListKind { SONGS, ARTISTS, ALBUMS, GENRES }

/**
 * Contenido de un ranking completo: solo una de las dos listas está llena, según [StatsListKind].
 *
 * Son dos tipos distintos porque una canción se REPRODUCE (y su fila necesita la entidad viva para
 * poder hacerlo) mientras que un artista, un álbum o un género NAVEGAN a su detalle. Unificarlos en
 * un modelo con campos nullables escondería esa diferencia justo donde importa.
 */
data class StatsListUiState(
    val kind: StatsListKind,
    val songs: List<StatsSongEntry> = emptyList(),
    val names: List<StatsNameEntry> = emptyList()
) {
    val isEmpty: Boolean get() = songs.isEmpty() && names.isEmpty()
}

/**
 * El ranking completo de una categoría, en su propia pantalla.
 *
 * Tiene ViewModel propio y no comparte el de estadísticas porque un `hiltViewModel()` dentro de un
 * `composable(...)` resuelve al scope del `NavBackStackEntry` (convención 9): pedirle el suyo a la
 * otra pantalla daría una instancia nueva igualmente, y con ella todas sus consultas. Aquí solo
 * corre la que se está mirando.
 *
 * **El periodo llega por la RUTA, no por un estado compartido.** Es lo que hace que la pantalla sea
 * reconstruible desde el back stack (o desde un `savedInstanceState`) sin depender de que la de
 * estadísticas siga viva detrás.
 */
@HiltViewModel
class StatsListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    statsRepository: StatsRepository,
    syncManager: SyncManager
) : ViewModel() {

    private val kind: StatsListKind = runCatching {
        StatsListKind.valueOf(savedStateHandle.get<String>(ARG_KIND).orEmpty())
    }.getOrDefault(StatsListKind.SONGS)

    /** El periodo que llegó por la ruta. La pantalla lo usa para su subtítulo. */
    val selection: StatsSelection = run {
        val mode = runCatching {
            StatsPeriodMode.valueOf(savedStateHandle.get<String>(ARG_MODE).orEmpty())
        }.getOrDefault(StatsPeriodMode.ALL)
        // En "Siempre" el ancla da igual, pero se parsea con el mismo cuidado para que una ruta
        // manipulada no tumbe la pantalla.
        val anchor = runCatching {
            LocalDate.parse(savedStateHandle.get<String>(ARG_ANCHOR).orEmpty())
        }.getOrDefault(LocalDate.now())
        StatsSelection(mode, anchor)
    }

    private val libraryIsSettling: Flow<Boolean> = syncManager.settlingFlow()

    val uiState: StateFlow<StatsListUiState> = run {
        val zone = ZoneId.systemDefault()
        val since = selection.sinceMillis(zone)
        val until = selection.untilMillis(zone)
        val all = selection.mode == StatsPeriodMode.ALL
        when (kind) {
            StatsListKind.SONGS ->
                (if (all) statsRepository.allTimeTopSongs(FULL_LIMIT)
                else statsRepository.topSongs(since, until, FULL_LIMIT))
                    .map { StatsListUiState(kind, songs = it) }
            StatsListKind.ARTISTS ->
                (if (all) statsRepository.allTimeTopArtists(FULL_LIMIT)
                else statsRepository.topArtists(since, until, FULL_LIMIT))
                    .map { StatsListUiState(kind, names = it) }
            StatsListKind.ALBUMS ->
                (if (all) statsRepository.allTimeTopAlbums(FULL_LIMIT)
                else statsRepository.topAlbums(since, until, FULL_LIMIT))
                    .map { StatsListUiState(kind, names = it) }
            StatsListKind.GENRES ->
                (if (all) statsRepository.allTimeTopGenres(FULL_LIMIT)
                else statsRepository.topGenres(since, until, FULL_LIMIT))
                    .map { StatsListUiState(kind, names = it) }
        }
    }
        // Todas estas consultas llevan JOIN con `songs` (o salen de ella), así que se re-ejecutan
        // con cada escritura de un sync: mismo muestreo que los rankings de la pantalla anterior.
        .sampledDuringSync(libraryIsSettling)
        .stateIn(viewModelScope, WhileUiSubscribed, StatsListUiState(kind))

    companion object {
        const val ARG_KIND = "kind"
        const val ARG_MODE = "mode"
        const val ARG_ANCHOR = "anchor"

        /**
         * Tope de la lista completa. No es "sin límite" a propósito: las consultas devuelven filas
         * a memoria y un `LIMIT` alto acota el peor caso sin recortar ningún uso real — con 500
         * entradas ya no se está leyendo un ranking, se está auditando una base de datos.
         */
        private const val FULL_LIMIT = 500
    }
}
