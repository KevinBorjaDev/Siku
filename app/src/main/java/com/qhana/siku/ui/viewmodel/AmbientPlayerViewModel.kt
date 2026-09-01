package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.WhileUiSubscribed
import com.qhana.siku.player.MusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado del modo ambiente (`AmbientPlayerActivity`).
 *
 * **Existe porque su pantalla era el único `@Composable` de la app que recibía un repositorio.**
 * Con `IMusicRepository` en la firma, la vista armaba sus propios flows —y ya había pagado el
 * precio: `getFavoritesIds()` fabrica un flow nuevo en cada llamada por el `map` de dentro, así que
 * sin un `remember` encima se resuscribía en cada recomposición y el corazón nunca llegaba a verse
 * relleno. Ese `remember` era el parche; la causa era que la vista tuviera de dónde sacar el flow.
 *
 * La suscripción vive aquí, atada al ciclo de vida de la actividad y no a la composición, así que el
 * modo ambiente —que se deja puesto horas, con la pantalla encendida— no rearma nada al recomponer.
 *
 * El transporte sigue yendo directo a [MusicController] desde la vista: es un singleton de proceso y
 * no hay estado que mediar, así que envolverlo aquí solo añadiría una capa de reenvío.
 */
@HiltViewModel
class AmbientPlayerViewModel @Inject constructor(
    musicController: MusicController,
    private val repository: IMusicRepository
) : ViewModel() {

    /**
     * La canción en curso, releída de la BD.
     *
     * El controller da la que está sonando, pero su copia se congeló al encolarla: la carátula y los
     * colores llegan después (backfill), y esta pantalla es justo la que se queda a la vista mientras
     * eso ocurre. De ahí el `flatMapLatest` sobre el id — se re-observa al cambiar de canción, no en
     * cada emisión — con la del controller como valor inicial para que no haya un frame en blanco.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSong: StateFlow<Song?> = musicController.currentSong
        .flatMapLatest { song ->
            if (song == null) flowOf(null) else repository.getSongByIdFlow(song.id)
        }
        .stateIn(viewModelScope, WhileUiSubscribed, musicController.currentSong.value)

    /**
     * ¿La canción en curso está en favoritos?
     *
     * Se resuelve aquí y no en la vista para que ésta reciba un booleano y no un `Set` entero: lo
     * único que hace con él es preguntar por un id.
     */
    val isFavorite: StateFlow<Boolean> = combine(
        currentSong.map { it?.id },
        repository.getFavoritesIds()
    ) { songId, favorites -> songId != null && songId in favorites }
        .stateIn(viewModelScope, WhileUiSubscribed, false)

    fun toggleFavorite() {
        val songId = currentSong.value?.id ?: return
        viewModelScope.launch { repository.toggleFavorite(songId) }
    }
}
