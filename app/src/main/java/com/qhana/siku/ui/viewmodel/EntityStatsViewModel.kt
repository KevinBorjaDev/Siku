package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsScope
import com.qhana.siku.data.model.StatsScopeChild
import com.qhana.siku.data.model.StatsScopeKind
import com.qhana.siku.data.model.child
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.data.model.scopeFor
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.BrowseRepository
import com.qhana.siku.data.repository.ScopeStatsSummary
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsRepository
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.data.util.WhileUiSubscribed
import com.qhana.siku.ui.navigation.Screen
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

/** Lo que la pantalla necesita del periodo y sus totales. Los rankings van aparte (convención 9b). */
data class EntityStatsUiState(
    val selection: StatsSelection,
    val summary: ScopeStatsSummary = ScopeStatsSummary(0, 0, 0, 0),
    val historyStartedAt: Long? = null,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
) {
    val isEmpty: Boolean get() = summary.isEmpty
}

/**
 * Estadísticas de UNA entidad —artista, álbum o género—: el mismo par de fuentes que
 * [StatsViewModel] (log de eventos para los periodos de calendario, agregados de `songs` para
 * "Siempre") acotado por un `StatsScope`.
 *
 * **Es UN ViewModel para los tres ámbitos y no tres**: lo único que cambia entre ellos es el
 * predicado de la consulta y qué sub-entidad cuelga debajo, y las dos cosas salen del ámbito. Ver
 * `StatsScope`, donde vive todo el reparto.
 *
 * **No repite lo que la pantalla general ya hace bien y aquí no aporta**: ni racha (no depende del
 * periodo NI de la entidad, así que enseñarla dos veces no dice nada nuevo), ni comparación con el
 * periodo anterior (dentro de una sola entidad la varianza domina: basta un disco nuevo para un
 * +400 % que no describe ninguna tendencia), ni conteo de géneros.
 *
 * La entidad y el periodo INICIAL llegan por la ruta; ver [Screen.EntityStats].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EntityStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val statsRepository: StatsRepository,
    private val browseRepository: BrowseRepository,
    musicPreferences: MusicPreferences,
    syncManager: SyncManager
) : ViewModel() {

    private companion object {
        /**
         * Entradas del ranking de canciones: **veinticinco, no las diez de la pantalla general**.
         *
         * Allí diez es lo que hace que las cuatro categorías quepan sin desplegar nada. Aquí solo
         * hay una lista y el universo es mucho más chico —las canciones de una entidad, no las de
         * la biblioteca—, así que con diez el "top" de alguien del que se tiene la discografía
         * entera se corta justo donde empieza a ser interesante.
         */
        const val TOP_SONGS = 25

        /** Sub-entidades del carrusel. Menos que canciones: un artista tiene pocos discos. */
        const val TOP_CHILDREN = 10
    }

    /** Ver `SyncAwareSampling`: TODAS las consultas de esta pantalla llevan `JOIN songs`. */
    private val libraryIsSettling: Flow<Boolean> = syncManager.settlingFlow()

    val kind: StatsScopeKind = Screen.EntityStats.decodeKind(
        savedStateHandle.get<String>(Screen.EntityStats.ARG_KIND)
    )

    val entityName: String = Screen.EntityStats.decodeName(
        savedStateHandle.get<String>(Screen.EntityStats.ARG_ENTITY_NAME)
    )

    /** Solo en un ÁLBUM: la otra mitad de su identidad (v29). `null` = por nombre. */
    val albumArtist: String? = Screen.EntityStats.decodeAlbumArtist(
        savedStateHandle.get<String>(Screen.EntityStats.ARG_ALBUM_ARTIST)
    )

    /**
     * El ámbito, que en un GÉNERO es REACTIVO: el ajuste "incluir géneros compuestos" decide qué
     * canciones son de este género, y tiene que seguir al detalle — dos vistas del mismo género que
     * cuentan conjuntos distintos serían dos verdades. En los otros dos ámbitos la preferencia no
     * pinta nada y el flujo emite una sola vez.
     */
    //
    // La agrupación de artistas (v29) también es reactiva, y en los TRES ámbitos: decide qué canciones
    // son de un artista y cómo se cuentan los artistas de un álbum o de un género.
    private val scopeFlow: Flow<StatsScope> =
        combine(musicPreferences.genrePartialMatchFlow, musicPreferences.artistCreditRoleFlow) { partial, role ->
            kind.scopeFor(entityName, albumArtist, genrePartialMatch = partial, artistRole = role)
        }

    /**
     * De qué ámbito son las sub-entidades —a dónde lleva tocar una tarjeta del carrusel—, o `null`
     * si esta entidad no tiene ninguna.
     *
     * **Se DERIVA de `StatsScope.child`** y no de un `when` sobre [kind] aquí: el reparto de qué
     * cuelga de qué está escrito una sola vez, en `StatsScope`, y esto solo lo traduce a un tipo de
     * ruta. El ámbito de conveniencia que construye para preguntar da igual cuál sea —la respuesta
     * no depende del nombre ni de la coincidencia parcial.
     */
    val childKind: StatsScopeKind? =
        when (kind.scopeFor(entityName, albumArtist, genrePartialMatch = false, artistRole = ArtistCreditRole.TRACK).child) {
            StatsScopeChild.ALBUMS -> StatsScopeKind.ALBUM
            StatsScopeChild.ARTISTS -> StatsScopeKind.ARTIST
            StatsScopeChild.NONE -> null
        }

    private val _selection = MutableStateFlow(
        Screen.EntityStats.decodeSelection(
            savedStateHandle.get<String>(Screen.EntityStats.ARG_PERIOD_MODE),
            savedStateHandle.get<String>(Screen.EntityStats.ARG_PERIOD_ANCHOR)
        )
    )

    /** Ámbito y periodo juntos: es lo que decide CADA consulta de la pantalla. */
    private val query: Flow<Pair<StatsScope, StatsSelection>> =
        combine(scopeFlow, _selection) { s, selection -> s to selection }

    /** Cambia de modo conservando el ancla, igual que la pantalla general. */
    fun selectMode(mode: StatsPeriodMode) {
        _selection.value = _selection.value.copy(mode = mode)
    }

    fun goToPrevious() {
        val floor = uiState.value.historyStartedAt?.let { dateOf(it) } ?: return
        _selection.value = _selection.value.previous(floor) ?: return
    }

    fun goToNext() {
        _selection.value = _selection.value.next(today()) ?: return
    }

    private val firstEventAt: Flow<Long?> = statsRepository.firstEventAt()

    private val summary: Flow<ScopeStatsSummary> = query.flatMapLatest { (scope, selection) ->
        val zone = ZoneId.systemDefault()
        if (selection.mode == StatsPeriodMode.ALL) {
            statsRepository.allTimeScopeSummary(scope)
        } else {
            statsRepository.scopeSummary(scope, selection.sinceMillis(zone), selection.untilMillis(zone))
        }.sampledDuringSync(libraryIsSettling)
    }

    val uiState: StateFlow<EntityStatsUiState> =
        combine(_selection, summary, firstEventAt) { selection, totals, firstEvent ->
            val floor = firstEvent?.let { dateOf(it) }
            EntityStatsUiState(
                selection = selection,
                summary = totals,
                historyStartedAt = firstEvent,
                canGoPrevious = floor != null && selection.previous(floor) != null,
                canGoNext = selection.next(today()) != null
            )
        }.stateIn(
            viewModelScope,
            WhileUiSubscribed,
            EntityStatsUiState(selection = _selection.value)
        )

    val topSongs: StateFlow<List<StatsSongEntry>> = query.flatMapLatest { (scope, selection) ->
        val zone = ZoneId.systemDefault()
        if (selection.mode == StatsPeriodMode.ALL) {
            statsRepository.allTimeScopeTopSongs(scope, TOP_SONGS)
        } else {
            statsRepository.scopeTopSongs(
                scope,
                selection.sinceMillis(zone),
                selection.untilMillis(zone),
                TOP_SONGS
            )
        }.sampledDuringSync(libraryIsSettling)
    }.stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * Las sub-entidades: los álbumes de un artista, los artistas de un género. En un ÁLBUM la lista
     * llega vacía y la pantalla no pinta la sección — por debajo de un disco solo hay canciones, y
     * ésas ya son la lista principal.
     */
    val children: StateFlow<List<StatsNameEntry>> = query.flatMapLatest { (scope, selection) ->
        val zone = ZoneId.systemDefault()
        if (selection.mode == StatsPeriodMode.ALL) {
            statsRepository.allTimeScopeChildren(scope, TOP_CHILDREN)
        } else {
            statsRepository.scopeChildren(
                scope,
                selection.sinceMillis(zone),
                selection.untilMillis(zone),
                TOP_CHILDREN
            )
        }.sampledDuringSync(libraryIsSettling)
    }
        // DESPUÉS del muestreo, no dentro: lo que hay que espaciar durante un sync es el JOIN de
        // arriba. Esta consulta mira solo `artists`.
        .flatMapLatest { entries -> withArtistPhotos(entries) }
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * El histograma del periodo, acotado a la entidad. `null` donde ese periodo no lo tiene (ver
     * [ActivityBucketing.forMode]).
     *
     * **Sí lleva muestreo, al revés que el de la pantalla general**: aquélla mira solo
     * `play_events`, donde el sync no escribe, y ésta necesita el `JOIN songs` para saber de quién
     * es cada escucha.
     */
    val activity: StateFlow<StatsActivity?> = query.flatMapLatest { (scope, selection) ->
        // Tipo EXPLÍCITO por el mismo motivo que en `StatsViewModel`: sin él la rama del `null`
        // infiere `Flow<Nothing?>`.
        val bucketing = ActivityBucketing.forMode(selection.mode)
            ?: return@flatMapLatest flowOf<StatsActivity?>(null)
        val zone = ZoneId.systemDefault()
        statsRepository.scopeActivity(
            scope,
            selection.sinceMillis(zone),
            selection.untilMillis(zone),
            bucketing.sqlFormat
        )
            .sampledDuringSync(libraryIsSettling)
            .map { plays -> StatsActivity(selection, bucketing, plays) }
    }.stateIn(viewModelScope, WhileUiSubscribed, null)

    /**
     * La imagen que identifica a la entidad. Qué es depende del ámbito, y por eso son dos campos y
     * no uno: un artista se dibuja con su foto en forma cookie, un álbum con su carátula cuadrada y
     * un género con un COLLAGE, que no es una imagen sino hasta cuatro.
     *
     * - **Artista**: la misma cascada que las otras superficies —foto de Deezer → carátula de
     *   alguno de sus álbumes → placeholder—. La foto se observa aparte y no se resuelve con un
     *   JOIN por lo mismo que en `StatsViewModel`: es tardía (la baja el backfill) y mutable (el
     *   picker la cambia, y "ninguno de estos" la quita).
     * - **Álbum**: su carátula, reactiva por si el healing la repone con la pantalla abierta.
     */
    val artUri: StateFlow<String?> = when (kind) {
        StatsScopeKind.ARTIST ->
            combine(browseRepository.getArtistInfo(entityName), children) { info, albums ->
                info?.thumbUrl ?: info?.imageUrl ?: albums.firstNotNullOfOrNull { it.artUri }
            }
        StatsScopeKind.ALBUM -> browseRepository.getAlbumArt(entityName, albumArtist)
        StatsScopeKind.GENRE -> flowOf<String?>(null)
    }.stateIn(viewModelScope, WhileUiSubscribed, null)

    /** El collage de un GÉNERO; vacío en los otros dos ámbitos. Ver [artUri]. */
    val collage: StateFlow<List<String>> = when (kind) {
        StatsScopeKind.GENRE ->
            browseRepository.getGenreArts(setOf(entityName))
                .map { arts -> arts[entityName.lowercase()].orEmpty() }
        else -> flowOf(emptyList<String>())
    }.stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /**
     * Cambia la carátula de cada artista por su FOTO, cuando la haya, en el carrusel de un GÉNERO.
     * Es el mismo tratamiento —y por los mismos motivos— que hace `StatsViewModel` con su podio de
     * artistas: la foto es tardía y mutable, así que se observa aparte en vez de con un JOIN.
     *
     * En los otros dos ámbitos las sub-entidades son álbumes, que sí tienen carátula propia.
     */
    private fun withArtistPhotos(entries: List<StatsNameEntry>): Flow<List<StatsNameEntry>> {
        if (kind != StatsScopeKind.GENRE || entries.isEmpty()) return flowOf(entries)
        return browseRepository.getArtistPhotos(entries.map { it.name }).map { photos ->
            // Sin foto se queda con lo que traía: la carátula es el segundo escalón de la cascada,
            // no un error.
            entries.map { entry -> photos[entry.name]?.let { entry.copy(artUri = it) } ?: entry }
        }
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun dateOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
}
