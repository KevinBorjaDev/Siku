package com.qhana.siku.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qhana.siku.ui.navigation.Screen

/**
 * State holder de la app (patrón `rememberAppState` de Now in Android): agrupa el
 * NavController, el estado de la capa flotante (pill ↔ player, FAB contextual, hojas
 * globales) y los flags derivados de la ruta actual.
 *
 * Este estado vive AQUÍ y no en las rutas del NavHost a propósito: la capa del reproductor
 * y el FAB se pintan SOBRE el NavHost (una sola instancia compartida entre pantallas), y un
 * `hiltViewModel()` dentro de una ruta resolvería al scope del NavBackStackEntry — una
 * instancia nueva por pantalla que no puede ser dueña de estado global.
 */
@Stable
class MusicAppState(
    val navController: NavHostController,
    playerExpandedState: MutableState<Boolean>
) {
    /** Reproductor expandido = NowPlaying a pantalla completa sobre el NavHost. */
    var playerExpanded by playerExpandedState

    /** El home está en la pestaña Listas: el FAB de aleatorio muta a "crear lista". */
    var homeOnPlaylistsTab by mutableStateOf(false)

    /** Hoja "añadir canciones" disparada desde el FAB en detalle de playlist/Favoritos. */
    var showAddSongsSheet by mutableStateOf(false)

    val currentBackStackEntry: NavBackStackEntry?
        @Composable get() = navController.currentBackStackEntryAsState().value

    val currentRoute: String?
        @Composable get() = currentBackStackEntry?.destination?.route

    // --- Navegación (helpers no composables, seguros desde callbacks) ---

    fun navigateBack() {
        navController.popBackStack()
    }

    fun navigateToArtist(name: String) {
        navController.navigate(Screen.ArtistDetail.createRoute(name)) { launchSingleTop = true }
    }

    fun navigateToAlbum(name: String) {
        navController.navigate(Screen.AlbumDetail.createRoute(name)) { launchSingleTop = true }
    }

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }
}

@Composable
fun rememberMusicAppState(
    navController: NavHostController = rememberNavController()
): MusicAppState {
    // rememberSaveable: el player abierto sobrevive a rotación/recreación del proceso.
    val playerExpanded = rememberSaveable { mutableStateOf(false) }
    return remember(navController) { MusicAppState(navController, playerExpanded) }
}
