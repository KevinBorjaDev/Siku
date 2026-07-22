package com.qhana.siku.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.AddSongsToPlaylistSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.EqualizerSheet
import com.qhana.siku.ui.components.MiniPlayer
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.screens.AmbientPlayerActivity
import com.qhana.siku.ui.screens.NavigationActions
import com.qhana.siku.ui.screens.NowPlayingScreen
import com.qhana.siku.ui.screens.PlayerActions
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel

/**
 * Capa de reproductor ÚNICA sobre el NavHost (pill ↔ player), más el FAB contextual y las
 * hojas/diálogos que dispara. Una sola instancia compartida entre home y detalles: al navegar
 * no se recrea (marquee, progreso y animaciones continúan). Expandida, es el NowPlaying a
 * pantalla completa: el AnimatedContent hace el container transform (la superficie crece desde
 * la píldora) y la carátula morfa como shared element DENTRO de la misma transición — el
 * NavHost de abajo nunca se entera.
 *
 * Receptor [BoxScope]: se monta en el Box raíz, alineada abajo.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BoxScope.PlayerOverlay(
    appState: MusicAppState,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope
) {
    val context = LocalContext.current

    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val nowPlayingSolidBackground by playbackViewModel.nowPlayingSolidBackground.collectAsStateWithLifecycle()
    val nowPlayingWavyProgress by playbackViewModel.nowPlayingWavyProgress.collectAsStateWithLifecycle()
    val duration by playbackViewModel.duration.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val favorites = libraryUiState.favorites

    val navBackStackEntry = appState.currentBackStackEntry
    val currentRoute = navBackStackEntry?.destination?.route
    val onLibraryRoute = currentRoute == Screen.Library.route
    val onPlaylistDetailRoute = currentRoute == Screen.PlaylistDetail.route
    val onFavoritesRoute = currentRoute == Screen.Favorites.route
    // Rutas de lista donde aplica la hoja de "añadir canciones" (botón en el detalle).
    val onAddSongsRoute = onPlaylistDetailRoute || onFavoritesRoute
    val miniPlayerVisible = when (currentRoute) {
        // Rutas con capa flotante habilitada; la píldora se cae sola si no hay canción
        // (el viejo FAB de "añadir canciones" ya no existe: es un botón del detalle).
        Screen.Library.route, Screen.PlaylistDetail.route, Screen.Favorites.route -> true
        Screen.ArtistDetail.route, Screen.AlbumDetail.route -> currentSong != null
        else -> false
    }

    // Si la ruta deja de ser un detalle de lista (back, navegación), la hoja muere con ella.
    LaunchedEffect(onAddSongsRoute) {
        if (!onAddSongsRoute) appState.showAddSongsSheet = false
    }
    // Atrás cierra el player (se compone DESPUÉS del NavHost para tener prioridad).
    BackHandler(enabled = appState.playerExpanded) { appState.playerExpanded = false }
    // Si la ruta actual no muestra reproductor (settings, onboarding…), colapsar. La guarda
    // `currentRoute != null` es CLAVE: al rotar, currentBackStackEntryAsState emite null un
    // instante mientras el NavController se restaura → miniPlayerVisible caía a false (rama
    // else) y este efecto cerraba el NowPlaying restaurado, tirándote al home. Con ruta nula
    // (transición) no se toca nada; se decide solo cuando hay una ruta real sin reproductor.
    LaunchedEffect(miniPlayerVisible, currentRoute) {
        if (currentRoute != null && !miniPlayerVisible) appState.playerExpanded = false
    }

    // Hoja del ECUALIZADOR PROPIO (5/10 bandas + float): el botón de la barra del NowPlaying
    // la abre — salvo que en Ajustes se prefiera el EQ del sistema, en cuyo caso el botón
    // lanza el panel del sistema directamente.
    var showEqualizerSheet by remember { mutableStateOf(false) }
    val useSystemEq by playbackViewModel.useSystemEq.collectAsStateWithLifecycle()

    // Ecualizador del sistema (MIUI primero, panel estándar como fallback). El feedback de
    // fallo va por TOAST, no por el SnackbarManager: se invoca también desde el botón dentro
    // de EqualizerSheet (ModalBottomSheet = ventana propia encima de la de MainActivity) y
    // el snackbar del host central quedaría tapado por la hoja; el Toast flota sobre todo.
    val openSystemEqualizer: () -> Unit = remember {
        {
            try {
                val xiaomiIntent = Intent().apply {
                    setClassName("com.miui.misound", "com.miui.misound.HeadsetSettingsActivity")
                }
                if (xiaomiIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(xiaomiIntent)
                } else {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackViewModel.getAudioSessionId())
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, R.string.eq_none_available, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.eq_open_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnimatedVisibility(
        visible = miniPlayerVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        AnimatedContent(
            targetState = appState.playerExpanded && currentSong != null,
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                // Lenguaje de la ruta original: el player DESLIZA a pantalla completa
                // (sube al abrir, baja al cerrar) mientras la carátula morfa como
                // shared element en la MISMA transición. El tamaño del contenedor
                // SNAPea sin clip — el slide es la transición, no el contenedor.
                val size = SizeTransform(clip = false) { _, _ -> snap() }
                if (targetState) {
                    // Abrir: el player sube cubriendo el home; la píldora queda debajo
                    // y se esfuma apenas la tapa.
                    (slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } togetherWith
                        fadeOut(tween(120))).using(size)
                } else {
                    // Cerrar: el player baja; la píldora reaparece al final, cuando el
                    // slide ya despejó la zona inferior.
                    (fadeIn(tween(150, delayMillis = 300)) togetherWith
                        slideOutVertically(tween(450, easing = FastOutSlowInEasing)) { it }).using(size)
                }
            },
            label = "playerContainer"
        ) { expanded ->
            if (expanded) {
                val isDarkTheme = isSystemInDarkTheme()
                // Memoizamos las acciones: si se construyen inline, cada recomposición
                // (posición cada 1s, lyrics, descargas…) crea lambdas nuevas →
                // NowPlayingScreen se recompone entero.
                val playerActions = remember(playbackViewModel, libraryViewModel, context, currentSong?.id, useSystemEq) {
                    PlayerActions(
                        onPlayPause = { playbackViewModel.playPause() },
                        onNext = { playbackViewModel.next() },
                        onPrevious = { playbackViewModel.previous() },
                        onSeek = { playbackViewModel.seekTo(it) },
                        onShuffleToggle = { playbackViewModel.toggleShuffle() },
                        onRepeatToggle = { playbackViewModel.toggleRepeatMode() },
                        onSkipToIndex = { playbackViewModel.skipToIndex(it) },
                        onReorder = { from, to -> playbackViewModel.reorderQueue(from, to) },
                        onRemoveFromQueue = { playbackViewModel.removeFromQueue(it) },
                        onSaveQueueAsPlaylist = { playbackViewModel.saveQueueAsPlaylist(it) },
                        onToggleFavorite = { currentSong?.let { libraryViewModel.toggleFavorite(it.id) } },
                        onToggleDownload = { playbackViewModel.toggleDownload() },
                        onToggleKeepScreenOn = { playbackViewModel.toggleKeepScreenOn() },
                        onOpenEqualizer = {
                            if (useSystemEq) openSystemEqualizer() else showEqualizerSheet = true
                        },
                        onFetchLyrics = { force -> playbackViewModel.fetchLyrics(force) },
                        onSearchLyricsManually = { playbackViewModel.searchLyricsCandidates() },
                        onSelectLyricsCandidate = { candidate ->
                            playbackViewModel.selectLyricsFromCandidate(candidate)
                            snackbarManager.show(context.getString(R.string.lyrics_refresh_updated))
                        },
                        onDismissLyricsSearch = { playbackViewModel.dismissLyricsSearch() },
                        onUpdatePosition = { playbackViewModel.updatePosition() },
                        onAddToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                        // El diálogo de crear lista del NowPlaying solo se abre desde la hoja
                        // "agregar a lista" de la canción en curso: la lista nueva nace con ella
                        // (antes se creaba vacía y la canción se perdía).
                        onCreatePlaylist = { name ->
                            libraryViewModel.createPlaylist(name) { id ->
                                currentSong?.id?.let { songId -> libraryViewModel.addSongToPlaylist(id, songId) }
                            }
                        },
                        onStartSleepTimer = { minutes, finishSong -> playbackViewModel.startSleepTimer(minutes, finishSong) },
                        onCancelSleepTimer = { playbackViewModel.cancelSleepTimer() }
                    )
                }
                val navigationActions = remember(playbackViewModel, appState, context, isDarkTheme) {
                    NavigationActions(
                        onBackClick = { appState.playerExpanded = false },
                        onLaunchAmbientMode = { timeout ->
                            context.startActivity(Intent(context, AmbientPlayerActivity::class.java).apply {
                                putExtra(AmbientPlayerActivity.EXTRA_TIMEOUT_MINUTES, timeout)
                            })
                        },
                        onShowDebugInfo = { playbackViewModel.showDebugInfo(isDarkTheme) },
                        onClearDebugInfo = { playbackViewModel.clearDebugInfo() },
                        onSelectColor = { color -> playbackViewModel.overrideSongColor(color, isDarkTheme) },
                        onArtistClick = { name ->
                            appState.playerExpanded = false
                            appState.navigateToArtist(name)
                        },
                        onAlbumClick = { name ->
                            appState.playerExpanded = false
                            appState.navigateToAlbum(name)
                        }
                    )
                }
                NowPlayingScreen(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this@AnimatedContent,
                    uiState = nowPlayingUiState,
                    playbackState = playbackState,
                    currentPositionFlow = playbackViewModel.currentPosition,
                    durationFlow = playbackViewModel.duration,
                    isShuffleEnabled = playbackViewModel.isShuffleEnabled.collectAsStateWithLifecycle().value,
                    repeatMode = playbackViewModel.repeatMode.collectAsStateWithLifecycle().value,
                    playlist = playbackViewModel.playlist.collectAsStateWithLifecycle().value,
                    currentIndex = playbackViewModel.currentIndex.collectAsStateWithLifecycle().value,
                    isFavorite = currentSong?.let { it.id in favorites } ?: false,
                    keepScreenOn = keepScreenOn,
                    solidBackground = nowPlayingSolidBackground,
                    wavyProgress = nowPlayingWavyProgress,
                    playlists = libraryUiState.playlists,
                    sleepTimer = playbackViewModel.sleepTimer.collectAsStateWithLifecycle().value,
                    eqEnabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
                    playerActions = playerActions,
                    navigationActions = navigationActions,
                    toolbarConfig = playbackViewModel.toolbarConfig.collectAsStateWithLifecycle().value,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Capa flotante del bottom: solo el MiniPlayer a TODO EL ANCHO (ya no hay FAB
                // encima). Sin padding lateral en el Column: el MiniPlayer recibe su margen
                // explícito, alineando al borde sin offsets.
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 10.dp, bottom = ComponentConfig.FloatingBarBottomMargin)
                ) {
                    // Ya NO hay FAB flotante: "crear lista" es un botón sobre Favoritos
                    // (PlaylistList) y "añadir canciones" es un botón redondo junto al aleatorio
                    // en el detalle (DetailPlayButtons). La capa flotante es solo el MiniPlayer.

                    // MiniPlayer a todo el ancho. currentSong directo: solo es null
                    // sin sesión (arranque antes del restore) o tras stop() — en ambos casos la
                    // píldora NO debe mostrarse (retenerla dejaba una píldora fantasma tras logout).
                    val song = currentSong
                    if (song != null && !(onLibraryRoute && libraryUiState.isSelectionMode)) {
                        MiniPlayer(
                            song = song,
                            isPlaying = playbackState == PlaybackState.PLAYING,
                            isBuffering = playbackState == PlaybackState.BUFFERING,
                            currentPositionFlow = playbackViewModel.currentPosition,
                            duration = duration,
                            albumColors = nowPlayingUiState.albumColors,
                            onPlayPause = { playbackViewModel.playPause() },
                            onNextClick = { playbackViewModel.next() },
                            onClick = { appState.playerExpanded = true },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = this@AnimatedContent,
                            // Margen lateral del spec (el Column ya no lo aplica).
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ComponentConfig.FloatingBarSideMargin)
                        )
                    }
                }
            }
        }
    }

    // Ecualizador: overlay FULL-SCREEN (ya no es ModalBottomSheet) que slide desde abajo, mismo
    // patrón que lyrics/cola. Vive FUERA del AnimatedContent para sobrevivir al colapso del player.
    // BackHandler para el back del sistema (el header también tiene su flecha).
    androidx.activity.compose.BackHandler(enabled = showEqualizerSheet) { showEqualizerSheet = false }
    androidx.compose.animation.AnimatedVisibility(
        visible = showEqualizerSheet,
        enter = androidx.compose.animation.slideInVertically(
            initialOffsetY = { it },
            animationSpec = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)),
        exit = androidx.compose.animation.slideOutVertically(
            targetOffsetY = { it },
            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
    ) {
        EqualizerSheet(
            enabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
            bandCount = playbackViewModel.eqBandCount.collectAsStateWithLifecycle().value,
            gains = playbackViewModel.eqGains.collectAsStateWithLifecycle().value,
            customPresets = playbackViewModel.customEqPresets.collectAsStateWithLifecycle().value,
            conflictWarningSuppressed = playbackViewModel.eqConflictWarningSuppressed.collectAsStateWithLifecycle().value,
            onSuppressConflictWarning = { playbackViewModel.suppressEqConflictWarning() },
            onEnabledChange = { playbackViewModel.setEqEnabled(it) },
            onBandCountChange = { playbackViewModel.setEqBandCount(it) },
            onApplyPreset = { playbackViewModel.setEqGains(it) },
            onApplyCustomPreset = { playbackViewModel.applyCustomEqPreset(it) },
            onSaveCurrentAsPreset = { playbackViewModel.saveCurrentAsEqPreset(it) },
            onDeleteCustomPreset = { playbackViewModel.deleteEqPreset(it) },
            onBandChange = { band, db -> playbackViewModel.setEqBand(band, db) },
            onBandChangeFinished = { playbackViewModel.commitEqGains() },
            onReset = { playbackViewModel.resetEq() },
            onOpenSystemEq = openSystemEqualizer,
            onDismiss = { showEqualizerSheet = false }
        )
    }

    // --- Hojas/diálogos disparados por el FAB (estado en MusicAppState) ---

    // (El "crear lista" ya no vive en el FAB: es un botón en PlaylistList con su propio
    // CreatePlaylistDialog vía LibraryScreen.showCreatePlaylistDialog.)

    // Selector "añadir canciones": en una playlist el id/nombre salen de la ruta activa;
    // en Favoritos el destino es la lista fija.
    val sheetPlaylistId = if (onPlaylistDetailRoute) navBackStackEntry?.arguments
        ?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_ID)?.toLongOrNull() else null
    if (appState.showAddSongsSheet && (sheetPlaylistId != null || onFavoritesRoute)) {
        val sheetPlaylistName = if (sheetPlaylistId != null) {
            Screen.PlaylistDetail.decodeName(
                navBackStackEntry?.arguments?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_NAME)
            )
        } else stringResource(R.string.common_favorites)
        val existingIds: Set<String> = if (sheetPlaylistId != null) {
            val sheetPlaylistSongs by libraryViewModel.getPlaylistSongs(sheetPlaylistId)
                .collectAsStateWithLifecycle(emptyList())
            remember(sheetPlaylistSongs) { sheetPlaylistSongs.map { it.id }.toSet() }
        } else favorites
        val pickerQuery by libraryViewModel.songPickerQuery.collectAsStateWithLifecycle()
        val pickerResults by libraryViewModel.songPickerResults.collectAsStateWithLifecycle()
        val candidates = remember(pickerResults, existingIds) {
            pickerResults.filterNot { it.id in existingIds }
        }

        AddSongsToPlaylistSheet(
            playlistName = sheetPlaylistName,
            candidates = candidates,
            query = pickerQuery,
            // Con búsqueda activa y cero resultados el mensaje debe ser "ningún
            // resultado", no "no hay canciones que añadir".
            hasSongsAvailable = pickerQuery.isNotBlank() || candidates.isNotEmpty(),
            onQueryChange = { libraryViewModel.setSongPickerQuery(it) },
            onConfirm = { ids ->
                if (sheetPlaylistId != null) {
                    libraryViewModel.addSongsToPlaylist(sheetPlaylistId, ids)
                } else {
                    libraryViewModel.addSongsToFavorites(ids)
                }
                libraryViewModel.setSongPickerQuery("")
                appState.showAddSongsSheet = false
            },
            onDismiss = {
                libraryViewModel.setSongPickerQuery("")
                appState.showAddSongsSheet = false
            }
        )
    }
}
