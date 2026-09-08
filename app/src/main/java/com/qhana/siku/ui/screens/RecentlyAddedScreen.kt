package com.qhana.siku.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.viewmodel.LibraryViewModel

/**
 * "Recién agregadas" completa: el destino del "Ver todo" de esa sección del inicio.
 *
 * **Existe porque el destino anterior era la lista equivocada** (27 ago 2026). Ese botón llevaba a
 * la pestaña Canciones fijando el sort `DATE_ADDED_DESC`, y eso fallaba por tres lados a la vez:
 * el conjunto es otro (la biblioteca ENTERA, no las de la ventana — la sección prometía cinco
 * novedades y el destino enseñaba las 765), el orden se PERSISTE, así que el atajo dejaba la
 * pestaña reordenada para siempre, y ese cambio de sort invalidaba el `PagingSource` justo mientras
 * la página se animaba hacia ella. Una ruta propia mata los tres: mismo conjunto, mismo orden, y
 * nada que persistir.
 *
 * **No es una playlist de Room y no debe serlo**: una playlist es dato del usuario —se reordena, se
 * le quitan canciones— y una que se reescribiera sola pelearía con eso, sin respuesta para "¿qué
 * pasa cuando una canción cumple 30 días?". Es una lista VIRTUAL, como un género.
 *
 * **Lleva la app bar PEQUEÑA y no el header inmersivo de los detalles**, por el mismo motivo que
 * `StatsScreen`: allí los 380dp se los gana una imagen, y aquí no hay ninguna que enseñar — un
 * conjunto de canciones sin entidad detrás no tiene portada propia, y un collage sería el mismo
 * error que ya se descartó en device para las estadísticas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyAddedScreen(
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    // Sin default `hiltViewModel()` a propósito (convención 9): es el ViewModel COMPARTIDO, y un
    // default aquí crearía una instancia nueva por ruta sin que nada fallara en compilación.
    libraryViewModel: LibraryViewModel,
    modifier: Modifier = Modifier
) {
    val songs by libraryViewModel.recentlyAddedAll.collectAsStateWithLifecycle()

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_section_recently_added)) },
                // Default: `surface` en reposo, tinte `surfaceContainer` al scrollear (M3 spec —
                // 4 sep 2026). Estuvo en `surfaceContainerHigh` fijo por la horquilla.
                colors = appTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                // El default de la barra (`systemBarsForVisualComponents`) IGNORA la perforación,
                // que en horizontal cae en un costado: sin esto la flecha de volver queda debajo.
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Fondo de página: `surface` (M3 spec — 4 sep 2026). Las filas se separan de él porque son
        // TARJETAS (`AppColors.contentSurface`), igual que en los detalles.
        containerColor = AppColors.surface
    ) { paddingValues ->
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            ComponentConfig.FloatingBarListInset

        if (songs.isEmpty()) {
            // La ventana puede vaciarse sola (todo lo de la biblioteca cumplió 30 días) y entonces
            // la sección del inicio desaparece, así que a esta pantalla no se llega... salvo por el
            // back stack, volviendo a ella con la ventana ya cerrada. El estado vacío es ese caso.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MaterialSymbol("library_music", size = 64.sp, color = AppColors.outline)
                    Text(
                        text = stringResource(R.string.recently_added_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .padding(top = paddingValues.calculateTopPadding())
                    // Mismo tope y mismo orden de modificadores que las demás pantallas de
                    // contenido: `widthIn` ANTES de `fillMaxWidth`, o el mínimo exacto que impone
                    // el segundo se pelearía con el tope del primero.
                    .safeSidePadding()
                    .widthIn(max = ComponentConfig.ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = listBottomInset)
            ) {
                // SIN conteo de canciones arriba (27 ago 2026, en device): en un detalle de artista
                // o de álbum el número es parte de la ficha de la entidad, pero aquí no hay entidad
                // que fichar — la lista se ve entera al scrollear y el dato no ayuda a decidir nada.
                item(key = "actions") {
                    DetailPlayButtons(
                        onPlayAll = { onPlaySongs(songs, 0) },
                        onShuffle = { onShufflePlay(songs) },
                        onAddToQueue = { onAddAllToQueue(songs) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
                itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                    // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y la
                    // lista de canciones (el resaltado marca "cargada", no "sonando ahora").
                    val isPlaying = currentSong?.id == song.id
                    val rowBackground = songRowBackground(AppColors.contentSurface, isPlaying)
                    // Punta ORIGEN del container transform hacia el reproductor. Fuera del
                    // envoltorio va lo que la coloca en la lista; dentro, la superficie que morfa.
                    SongRowContainer(
                        songId = song.id,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        AppSurface(
                            color = AppColors.contentSurface,
                            shape = rememberListItemShape(index, songs.size, isActive = isPlaying),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SongItem(
                                song = song,
                                isPlaying = isPlaying,
                                modifier = Modifier.clickable { onPlaySongs(songs, index) },
                                trailingContent = {
                                    SongOverflowButton(
                                        isFavorite = song.id in favorites,
                                        onToggleFavorite = { onToggleFavorite(song.id) },
                                        onAddToPlaylist = { songIdForPlaylist = song.id },
                                        onAddToQueue = { onAddToQueue(song) },
                                        rowBackground = rowBackground
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val pendingSongId = songIdForPlaylist
    if (pendingSongId != null) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onDismiss = { songIdForPlaylist = null },
            onPlaylistSelected = { playlistId ->
                onAddSongToPlaylist(playlistId, pendingSongId)
                songIdForPlaylist = null
            },
            onCreateNewPlaylist = { showCreatePlaylist = true }
        )
    }
    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            onConfirm = { name ->
                onCreatePlaylist(name, songIdForPlaylist)
                showCreatePlaylist = false
                songIdForPlaylist = null
            }
        )
    }
}
