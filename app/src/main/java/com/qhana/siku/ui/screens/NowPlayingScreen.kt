package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import android.media.MediaMetadataRetriever
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.state.NowPlayingUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal object NowPlayingConfig {
    val DefaultBackgroundColor = Color(0xFF252525)
    val PlayButtonElevation = 6.dp
    // Grupo de TRANSPORTE (prev/play/next), ajustado a la referencia visual del spec
    // Expressive: separación visible entre miembros y esquinas interiores generosas que se
    // encogen al presionar. La barra de acciones es un STANDARD button group (formas
    // individuales, separación 12.dp de spec) — sus corners viven en GlassActionButton.
    val GroupSpacing = 8.dp
    val GroupInnerCorner = 16.dp
    val GroupInnerCornerPressed = 8.dp
    // Hueco MÍNIMO garantizado entre el grupo de transporte y los toggles laterales
    // (aleatorio/repetir): el play se ensancha en pausa y sin este colchón se tocaban.
    val TransportSideGap = 12.dp
}

@Immutable
data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onShuffleToggle: () -> Unit,
    val onRepeatToggle: () -> Unit,
    val onSkipToIndex: (Int) -> Unit,
    val onReorder: (Int, Int) -> Unit,
    val onRemoveFromQueue: (Int) -> Unit,
    val onSaveQueueAsPlaylist: (String) -> Unit,
    val onToggleFavorite: () -> Unit,
    val onToggleDownload: () -> Unit,
    val onToggleKeepScreenOn: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onFetchLyrics: (Boolean) -> Unit,
    val onSearchLyricsManually: () -> Unit,
    val onSelectLyricsCandidate: (com.qhana.siku.data.repository.LyricsCandidate) -> Unit,
    val onDismissLyricsSearch: () -> Unit,
    val onUpdatePosition: () -> Unit,
    val onAddToPlaylist: (playlistId: Long, songId: String) -> Unit,
    /** Crear lista desde la hoja "agregar a lista": la lista nace CON la canción en curso. */
    val onCreatePlaylist: (name: String) -> Unit,
    val onStartSleepTimer: (minutes: Int, finishSong: Boolean) -> Unit,
    val onCancelSleepTimer: () -> Unit
)

@Immutable
data class NavigationActions(
    val onBackClick: () -> Unit,
    val onLaunchAmbientMode: (timeoutMinutes: Int) -> Unit,
    val onShowDebugInfo: () -> Unit,
    val onClearDebugInfo: () -> Unit,
    val onSelectColor: (Int) -> Unit,
    val onArtistClick: (String) -> Unit,
    val onAlbumClick: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(
    uiState: NowPlayingUiState,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    playlist: List<Song>,
    currentIndex: Int,
    isFavorite: Boolean,
    keepScreenOn: Boolean,
    solidBackground: Boolean,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive) en vez de la píldora. */
    wavyProgress: Boolean,
    playlists: List<com.qhana.siku.data.model.Playlist>,
    sleepTimer: com.qhana.siku.player.MusicController.SleepTimerState?,
    /** Estado del EQ propio para el fondo activo de su botón en el toolbar. */
    eqEnabled: Boolean,
    playerActions: PlayerActions,
    navigationActions: NavigationActions,
    toolbarConfig: List<ToolbarActionState> = PlayerToolbarConfig.DEFAULT,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val isDarkTheme = isSystemInDarkTheme()
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showAmbientModeDialog by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Atrás cierra los overlays de ESTA pantalla (lyrics/cola). SOLO se habilita cuando hay uno
    // abierto: si estuviera `enabled = true` siempre, robaría el back a otros overlays del player
    // (p. ej. el ecualizador, que se abre desde acá) porque este handler se registra DESPUÉS
    // (NowPlaying se compone al expandir el player) → gana por LIFO y su `else` colapsaba el player.
    // El colapso del player lo maneja el BackHandler dedicado de PlayerOverlay.
    androidx.activity.compose.BackHandler(enabled = showLyrics || showQueueSheet) {
        if (showLyrics) showLyrics = false
        else if (showQueueSheet) showQueueSheet = false
    }

    val isPlayingOrBuffering by remember(playbackState) {
        derivedStateOf {
            playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
        }
    }

    // Auto-fetch lyrics (force = false) cuando se abre el overlay.
    // Claves mínimas: sólo el par (showLyrics, song.id). Los campos de estado de loading
    // se consultan dentro del efecto y NO deben ser key — causarían re-triggers en cascada.
    LaunchedEffect(showLyrics, uiState.song?.id) {
        if (showLyrics && uiState.lyrics == null && uiState.lyricLines.isEmpty() && !uiState.isLyricsLoading) {
            playerActions.onFetchLyrics(false)
        }
    }

    // Keep screen on while lyrics are visible
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(showLyrics, keepScreenOn) {
        view.keepScreenOn = showLyrics || keepScreenOn
        onDispose { view.keepScreenOn = keepScreenOn }
    }

    // Transformar playlist a modelos UI estables (ASYNC para evitar ANR en listas grandes)
    val uiPlaylist by produceState(initialValue = emptyList<com.qhana.siku.ui.model.SongUiModel>(), key1 = playlist) {
        value = withContext(Dispatchers.Default) {
            playlist.map { it.toUiModel(isActive = false) }
        }
    }

    val song = uiState.song
    if (song == null) {
        NowPlayingSkeleton(modifier = modifier, isDarkTheme = isDarkTheme)
        return
    }

    // Colores Adaptativos (del histograma, sin postprocesamiento)
    val albumColors = uiState.albumColors

    // Fondo del gradiente = `secondaryContainer` → `surface` (rol del esquema, SEED-ONLY): antes era
    // el color CRUDO del álbum mezclado 50%. Sigue teñido del álbum (tema seedeado) y el tema anima
    // `secondaryContainer` al cambiar de canción (animatedScheme).
    val albumPrimary = MaterialTheme.colorScheme.secondaryContainer

    // Play button + TODOS los acentos que derivan de esto = rol PRIMARY del esquema. El color
    // elegido/extraído es SOLO el SEED (el tema está seedeado de él vía MusicPlayerTheme), NO se usa
    // 1:1 — eso causaba las inconsistencias/parches (ensureContrast, onAccentContentColor, albumAccent).
    // `onPrimary` da el contraste del icono por diseño M3. El tema anima primary al cambiar de canción
    // (animatedScheme), así que no hace falta el animateColorAsState local.
    val playButtonColor = MaterialTheme.colorScheme.primary
    val playButtonContentColor = MaterialTheme.colorScheme.onPrimary

    val surfaceColor = MaterialTheme.colorScheme.surface
    val solidBackgroundColor = MaterialTheme.colorScheme.surfaceContainer

    // Fondo según el ajuste del usuario: color SÓLIDO tonal M3 Expressive (surfaceContainer,
    // que ya viene teñido por el seed del álbum vía theme) o el degradado vertical clásico
    // (Color Profundo -> Fondo Neutro).
    val backgroundBrush = remember(albumPrimary, surfaceColor, solidBackgroundColor, solidBackground) {
        if (solidBackground) {
            SolidColor(solidBackgroundColor)
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    albumPrimary,
                    surfaceColor
                )
            )
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    val variantColor = MaterialTheme.colorScheme.onSurfaceVariant

    // `song` viene del player; `uiState.isDownloaded` sale de la fila de la BD y se adelanta a él
    // en cuanto termina una descarga, así que gana sobre el `path` del MediaItem en curso.
    val origin = if (uiState.isDownloaded && song.sourceType != SourceType.LOCAL) {
        PlaybackOrigin.DOWNLOADED
    } else {
        song.playbackOrigin
    }
    val formatText = rememberAudioFormat(song.path, song.title)

    val onAlbumArtLongPress = { navigationActions.onShowDebugInfo() }
    val onLyricsToggle = { showLyrics = !showLyrics }
    val onShowQueue = { showQueueSheet = true }
    val onAmbientMode = { showAmbientModeDialog = true }
    val onAddToPlaylistClick = { showAddToPlaylist = true }
    val onSleepTimerClick = { showSleepTimerSheet = true }

    // Estado de Haze: la capa de fondo (gradiente) es el `hazeSource`; el chip y la barra de
    // acciones lo desenfocan con `hazeEffect` (ver GlassSurface). El velo lleva algo del color
    // del álbum: base del tema (negro/blanco) TEÑIDA con el acento, semitransparente — así el
    // vidrio toma el matiz de la carátula sin perder legibilidad del texto (onSurface).
    val hazeState = remember { HazeState() }
    val glassTint = run {
        val base = if (isDarkTheme) {
            // Un velo de base negra desaparece sobre surface (casi negro, es lo que hay detrás
            // de la barra) cuando el acento del álbum es apagado. Se LEVANTA la base hacia
            // blanco de forma adaptativa: cuanto más oscuro el surface del tema, más lift,
            // garantizando que el vidrio siempre quede un paso más claro que el fondo.
            val surfaceLum = ColorUtils.calculateLuminance(surfaceColor.toArgb()).toFloat()
            val lift = (0.22f - surfaceLum * 0.6f).coerceIn(0.08f, 0.22f)
            Color(ColorUtils.blendARGB(Color.Black.toArgb(), Color.White.toArgb(), lift))
        } else Color.White
        val tinted = Color(ColorUtils.blendARGB(base.toArgb(), playButtonColor.toArgb(), 0.40f))
        tinted.copy(alpha = if (isDarkTheme) 0.38f else 0.42f)
    }

    // Blur de vidrio: DESACTIVADO mientras la transición de apertura (slide del AnimatedContent)
    // no se ha asentado — el RenderEffect por-frame es el causante del bajón de frames al abrir.
    // Se reactiva al terminar la animación (currentState == targetState).
    val glassBlurEnabled = animatedVisibilityScope?.transition?.let { it.currentState == it.targetState } ?: true

    CompositionLocalProvider(LocalGlassBlurEnabled provides glassBlurEnabled) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight

        // Capa 1 (FONDO = hazeSource): el fondo gradiente en una capa DEDICADA detrás de todo.
        // Los contenedores de vidrio (chip, barra de acciones) viven en la capa de contenido de
        // ENCIMA (hermana de ésta) y la difuminan con hazeEffect. Haze sólo exige que el effect
        // se dibuje SOBRE el source, no que sea hermano en un Scaffold — por eso el layout
        // interno (Portrait/Landscape) no necesita reestructurarse.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .hazeSource(state = hazeState)
        )

        // Capa 2 (CONTENIDO): layout normal, encima del fondo y transparente.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                if (!isLandscape) {
                    NowPlayingTopBar(
                        onBackClick = navigationActions.onBackClick,
                        contentColor = contentColor,
                        accentColor = playButtonColor,
                        hazeState = hazeState,
                        glassTint = glassTint,
                        origin = origin,
                        onAmbientMode = onAmbientMode
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isLandscape) {
                    NowPlayingLandscape(
                        song = song,
                        uiState = uiState,
                        variantColor = variantColor,
                        contentColor = contentColor,
                        playButtonColor = playButtonColor,
                        playButtonContentColor = playButtonContentColor,
                        revealAccent = playButtonColor,
                        isFavorite = isFavorite,
                        isShuffleEnabled = isShuffleEnabled,
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        origin = origin,
                        formatText = formatText,
                        wavyProgress = wavyProgress,
                        playerActions = playerActions,
                        onBackClick = navigationActions.onBackClick,
                        onArtistClick = navigationActions.onArtistClick,
                        onAlbumClick = navigationActions.onAlbumClick,
                        onAmbientMode = onAmbientMode,
                        onLyricsToggle = onLyricsToggle,
                        onShowQueue = onShowQueue,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        eqEnabled = eqEnabled,
                        sleepTimerActive = sleepTimer != null,
                        onSleepTimerClick = onSleepTimerClick,
                        toolbarConfig = toolbarConfig,
                        hazeState = hazeState,
                        glassTint = glassTint,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAlbumArtLongPress = onAlbumArtLongPress
                    )
                } else {
                    NowPlayingPortrait(
                        song = song,
                        uiState = uiState,
                        variantColor = variantColor,
                        contentColor = contentColor,
                        playButtonColor = playButtonColor,
                        playButtonContentColor = playButtonContentColor,
                        revealAccent = playButtonColor,
                        isFavorite = isFavorite,
                        isShuffleEnabled = isShuffleEnabled,
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        formatText = formatText,
                        wavyProgress = wavyProgress,
                        playerActions = playerActions,
                        onArtistClick = navigationActions.onArtistClick,
                        onAlbumClick = navigationActions.onAlbumClick,
                        onLyricsToggle = onLyricsToggle,
                        onShowQueue = onShowQueue,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        eqEnabled = eqEnabled,
                        sleepTimerActive = sleepTimer != null,
                        onSleepTimerClick = onSleepTimerClick,
                        toolbarConfig = toolbarConfig,
                        hazeState = hazeState,
                        glassTint = glassTint,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAlbumArtLongPress = onAlbumArtLongPress
                    )
                }
            }
        }
    }
    } // CompositionLocalProvider(LocalGlassBlurEnabled)

    // Polling removed: ProgressSlider observes currentPositionFlow directly.

    if (showAmbientModeDialog) {
        AlertDialog(
            onDismissRequest = { showAmbientModeDialog = false },
            title = { Text(stringResource(R.string.ambient_title)) },
            text = { Text(stringResource(R.string.ambient_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showAmbientModeDialog = false
                    navigationActions.onLaunchAmbientMode(-1)
                }) { Text(stringResource(R.string.common_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showAmbientModeDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    AnimatedVisibility(
        visible = showQueueSheet,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(200))
            ) {
            QueueBottomSheet(
                playlist = uiPlaylist,
                currentIndex = currentIndex,
                onSongClick = playerActions.onSkipToIndex,
                onReorder = playerActions.onReorder,
                onDismiss = { showQueueSheet = false },
                isShuffleEnabled = isShuffleEnabled,
                onShuffleToggle = playerActions.onShuffleToggle,
                onRemoveSong = playerActions.onRemoveFromQueue,
                onSaveAsPlaylist = playerActions.onSaveQueueAsPlaylist,
                accentColor = albumPrimary
            )
        }

        // --- Full Screen Lyrics Overlay ---
        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(400, easing = FastOutSlowInEasing)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(400, easing = FastOutSlowInEasing)
            )
        ) {
            // `song` ya es no-null acá (early-return arriba si uiState.song == null).
            LyricsScreen(
                                        lyrics = uiState.lyrics,
                                        lyricLines = uiState.lyricLines,
                                        isLyricsLoading = uiState.isLyricsLoading,
                                        lyricsFailure = uiState.lyricsFailure,
                                        lyricsError = uiState.lyricsError,
                                        songId = song.id,
                                        currentPositionFlow = currentPositionFlow,
                                        playbackState = playbackState,
                                        lyricsCandidates = uiState.lyricsCandidates,
                                        isSearchingCandidates = uiState.isSearchingCandidates,
                                        lyricsSearchError = uiState.lyricsSearchError,
                                        onSeek = playerActions.onSeek,
                                        onClose = { showLyrics = false },
                                        onFetchLyrics = { playerActions.onFetchLyrics(true) },
                                        onGoogleSearch = {
                                            val query = "${song.artist} ${song.title} lyrics"
                                            uriHandler.openUri("https://www.google.com/search?q=${android.net.Uri.encode(query)}")
                                        },
                                        onSearchManually = playerActions.onSearchLyricsManually,
                                        onSelectCandidate = playerActions.onSelectLyricsCandidate,
                                        onDismissSearch = playerActions.onDismissLyricsSearch,
                                        onPlayPause = playerActions.onPlayPause,
                                        onNext = playerActions.onNext,
                                        onPrevious = playerActions.onPrevious,
                                        // Fondo SÓLIDO surfaceContainer (el mismo del ajuste "fondo
                                        // sólido"), no el degradado del álbum: el overlay de letras se
                                        // lee como una superficie neutra estable. Contraste = onSurface.
                                        backgroundColor = solidBackgroundColor,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        accentColor = playButtonColor
                                    )
    }

    if (uiState.debugInfo != null) {
        ColorPickerDialog(
            info = uiState.debugInfo,
            onColorSelected = { color ->
                navigationActions.onSelectColor(color)
            },
            onDismiss = navigationActions.onClearDebugInfo
        )
    }

    // Añadir la canción actual a una lista de reproducción (desde el overflow de la barra).
    if (showAddToPlaylist) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                playerActions.onAddToPlaylist(playlistId, song.id)
                showAddToPlaylist = false
            },
            onCreateNewPlaylist = {
                showAddToPlaylist = false
                showCreatePlaylistDialog = true
            },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            state = sleepTimer,
            onStart = playerActions.onStartSleepTimer,
            onCancel = playerActions.onCancelSleepTimer,
            onDismiss = { showSleepTimerSheet = false }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                playerActions.onCreatePlaylist(name)
                showCreatePlaylistDialog = false
            }
        )
    }
}

@Composable
private fun rememberAudioFormat(path: String, title: String): String {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val format by produceState(initialValue = "AUDIO", key1 = path, key2 = title) {
        value = withContext(Dispatchers.IO) {
            try {
                // 0. Fuente LOCAL (SAF): el content:// no tiene extensión, hay que leer el MIME
                // con la sobrecarga Context+Uri.
                if (path.startsWith("content://")) {
                    val retriever = MediaMetadataRetriever()
                    return@withContext try {
                        retriever.setDataSource(appContext, android.net.Uri.parse(path))
                        mimeToFormat(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
                    } catch (_: Exception) {
                        "AUDIO"
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                // 1. Para archivos locales: leer MIME real del archivo
                if (path.startsWith("file://")) {
                    val filePath = path.removePrefix("file://")
                    val retriever = MediaMetadataRetriever()
                    try {
                        // FD, no setDataSource(String): esa sobrecarga hace Uri.parse sin validar
                        // y los ':' de los ids namespaced en el nombre la rompen (EINVAL).
                        java.io.FileInputStream(filePath).use { retriever.setDataSource(it.fd) }
                        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        mimeToFormat(mime)
                    } catch (_: Exception) {
                        // Fallback a extensión del archivo
                        java.io.File(filePath).extension.uppercase().ifEmpty { "AUDIO" }
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                // 2. Para streaming: extensión del path, luego del título (nombre original del archivo)
                else {
                    detectFormatByExtension(path) ?: detectFormatByExtension(title) ?: "AUDIO"
                }
            } catch (_: Exception) {
                "AUDIO"
            }
        }
    }
    return format
}

private fun mimeToFormat(mime: String?): String = when {
    mime == null -> "AUDIO"
    "flac" in mime || "x-flac" in mime -> "FLAC"
    "mpeg" in mime -> "MP3"
    "mp4" in mime || "m4a" in mime || "x-m4a" in mime -> "M4A"
    "wav" in mime || "x-wav" in mime -> "WAV"
    "ogg" in mime || "vorbis" in mime -> "OGG"
    "aac" in mime || "x-aac" in mime -> "AAC"
    "opus" in mime -> "OPUS"
    else -> "AUDIO"
}

private fun detectFormatByExtension(text: String): String? {
    val lower = text.lowercase()
    return when {
        lower.endsWith(".flac") -> "FLAC"
        lower.endsWith(".mp3") -> "MP3"
        lower.endsWith(".wav") -> "WAV"
        lower.endsWith(".m4a") -> "M4A"
        lower.endsWith(".aac") -> "AAC"
        lower.endsWith(".ogg") -> "OGG"
        else -> null
    }
}
