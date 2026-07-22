package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.state.NowPlayingUiState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.StateFlow

    // --- Orientation Layouts ---

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingPortrait(
    song: Song,
    uiState: NowPlayingUiState,
    variantColor: Color,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    // Acento TARGET sin animar: lo consume AccentRevealGroup (la ventana es la transición).
    revealAccent: Color,
    isFavorite: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    keepScreenOn: Boolean,
    showLyrics: Boolean,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    formatText: String,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive). */
    wavyProgress: Boolean,
    playerActions: PlayerActions,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    eqEnabled: Boolean,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    toolbarConfig: List<ToolbarActionState>,
    hazeState: HazeState,
    glassTint: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onAlbumArtLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArtSection(
            song = song,
            variantColor = variantColor,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            isPlaying = isPlayingOrBuffering,
            onTap = onAlbumArtLongPress,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // La carátula es cuadrada dentro de este hueco, así que normalmente el ALTO es
                // el que manda: cada dp de padding vertical la encoge por los cuatro lados.
                // 16/8 es el punto medio calibrado a ojo (24/8 la dejaba chica, 8/0 la llevaba
                // a tocar los márgenes de la pantalla).
                .padding(vertical = 16.dp, horizontal = 8.dp)
        )

        SongInfoSection(
            song = song,
            contentColor = contentColor,
            variantColor = variantColor,
            isFavorite = isFavorite,
            playButtonColor = playButtonColor,
            onToggleFavorite = playerActions.onToggleFavorite,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        ProgressSlider(
            currentPositionFlow = currentPositionFlow,
            durationFlow = durationFlow,
            onSeek = playerActions.onSeek,
            trackColor = playButtonColor,
            inactiveTrackColor = playButtonColor.copy(alpha = 0.2f),
            textColor = variantColor,
            formatText = formatText,
            wavy = wavyProgress,
            isPlaying = isPlayingOrBuffering
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Controles + action bar comparten UN solo shape reveal de acento al cambiar de
        // canción (misma coreografía cookie que la carátula). El estado del giro del play
        // va HOISTED aquí: las dos capas del reveal deben compartirlo (ver PlayButtonSpinState).
        val playSpin = rememberPlayButtonSpin(isPlayingOrBuffering)
        AccentRevealGroup(
            songId = song.id,
            accent = revealAccent,
            accentContent = playButtonContentColor,
            modifier = Modifier.fillMaxWidth()
        ) { accent, accentContent ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlaybackControls(
                    onPrevious = playerActions.onPrevious,
                    onPlayPause = playerActions.onPlayPause,
                    onNext = playerActions.onNext,
                    contentColor = contentColor,
                    playButtonColor = accent,
                    playButtonContentColor = accentContent,
                    isPlayingOrBuffering = isPlayingOrBuffering,
                    playbackState = playbackState,
                    spin = playSpin
                )

                Spacer(modifier = Modifier.height(32.dp))

                BottomActionBar(
                    showLyrics = showLyrics,
                    isLyricsLoading = uiState.isLyricsLoading,
                    keepScreenOn = keepScreenOn,
                    playButtonColor = accent,
                    songRemoteId = song.remoteId,
                    isDownloaded = uiState.isDownloaded,
                    isDownloading = uiState.isDownloading,
                    downloadProgress = uiState.downloadProgress,
                    onLyricsToggle = onLyricsToggle,
                    onShowQueue = onShowQueue,
                    onToggleKeepScreenOn = playerActions.onToggleKeepScreenOn,
                    onOpenEqualizer = playerActions.onOpenEqualizer,
                    onRedownload = playerActions.onToggleDownload,
                    onAddToPlaylistClick = onAddToPlaylistClick,
                    eqEnabled = eqEnabled,
                    sleepTimerActive = sleepTimerActive,
                    onSleepTimerClick = onSleepTimerClick,
                    repeatMode = repeatMode,
                    onRepeatToggle = playerActions.onRepeatToggle,
                    config = toolbarConfig,
                    // Mismo margen sobre la navbar que la capa flotante del home (16dp). El Scaffold
                    // ya mete el inset de la navbar en innerPadding, así que esto queda 16dp por
                    // encima de ella (antes 32).
                    modifier = Modifier.padding(bottom = ComponentConfig.FloatingBarBottomMargin)
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingLandscape(
    song: Song,
    uiState: NowPlayingUiState,
    variantColor: Color,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    isFavorite: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    keepScreenOn: Boolean,
    showLyrics: Boolean,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    // Acento TARGET sin animar: lo consume AccentRevealGroup (la ventana es la transición).
    revealAccent: Color,
    origin: PlaybackOrigin,
    formatText: String,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive). */
    wavyProgress: Boolean,
    playerActions: PlayerActions,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onAmbientMode: () -> Unit,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    eqEnabled: Boolean,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    toolbarConfig: List<ToolbarActionState>,
    hazeState: HazeState,
    glassTint: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onAlbumArtLongPress: () -> Unit
) {
    // Sin statusBarsPadding/navigationBarsPadding: el Scaffold de NowPlayingScreen ya mete los
    // insets de las barras del sistema en innerPadding (aplicado por el Box padre). Aplicarlos de
    // nuevo acá duplicaba el espacio superior (la franja vacía bajo el status bar).
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: info bar arriba (minimizar | chip | ambient) + carátula + toolbar de acciones.
        // El toolbar vive ACÁ, no en la columna de controles: en landscape esa columna no tenía
        // altura para todo y el BottomActionBar (último hijo) se medía a ≈0 y se aplastaba.
        // Repartido así, cada columna cabe sin scroll.
        // Sin verticalArrangement=Center: el info bar se ancla arriba y el toolbar abajo; la
        // carátula se centra en el Box(weight) intermedio. Antes, weight(1f, fill=false) en la
        // carátula + Center dejaba TODO el aire sobrante como padding superior.
        Column(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Info bar arriba: minimizar | chip | ambient.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBackClick) {
                    MaterialSymbol("keyboard_arrow_down", size = 28.sp, color = contentColor)
                }

                PlaybackSourceChip(
                    origin = origin,
                    contentColor = contentColor,
                    accentColor = playButtonColor,
                    hazeState = hazeState,
                    glassTint = glassTint,
                    compact = true
                )

                IconButton(onClick = onAmbientMode) {
                    MaterialSymbol("expand_content", size = 24.sp, color = contentColor)
                }
            }

            // Carátula: ocupa el alto sobrante entre info bar y toolbar, cuadrada y centrada.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtSection(
                    song = song,
                    variantColor = variantColor,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isPlaying = isPlayingOrBuffering,
                    onTap = onAlbumArtLongPress,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Toolbar de acciones. Usa playButtonColor directo (sin el reveal cookie, que queda
            // con los controles de la derecha): igual sigue el acento del álbum vía el theme.
            BottomActionBar(
                showLyrics = showLyrics,
                isLyricsLoading = uiState.isLyricsLoading,
                keepScreenOn = keepScreenOn,
                playButtonColor = playButtonColor,
                songRemoteId = song.remoteId,
                isDownloaded = uiState.isDownloaded,
                isDownloading = uiState.isDownloading,
                downloadProgress = uiState.downloadProgress,
                onLyricsToggle = onLyricsToggle,
                onShowQueue = onShowQueue,
                onToggleKeepScreenOn = playerActions.onToggleKeepScreenOn,
                onOpenEqualizer = playerActions.onOpenEqualizer,
                onRedownload = playerActions.onToggleDownload,
                onAddToPlaylistClick = onAddToPlaylistClick,
                eqEnabled = eqEnabled,
                sleepTimerActive = sleepTimerActive,
                onSleepTimerClick = onSleepTimerClick,
                repeatMode = repeatMode,
                onRepeatToggle = playerActions.onRepeatToggle,
                config = toolbarConfig
            )
        }

        // RIGHT: info de la canción, slider y transporte (sin toolbar, ahora en la izquierda).
        Column(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            SongInfoSection(
                song = song,
                contentColor = contentColor,
                variantColor = variantColor,
                isFavorite = isFavorite,
                playButtonColor = playButtonColor,
                onToggleFavorite = playerActions.onToggleFavorite,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProgressSlider(
                currentPositionFlow = currentPositionFlow,
                durationFlow = durationFlow,
                onSeek = playerActions.onSeek,
                trackColor = playButtonColor,
                inactiveTrackColor = playButtonColor.copy(alpha = 0.2f),
                textColor = variantColor,
                formatText = formatText,
                wavy = wavyProgress,
                isPlaying = isPlayingOrBuffering
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transporte con su shape reveal de acento al cambiar de canción. El estado del giro
            // del play va HOISTED (ver PlayButtonSpinState).
            val playSpin = rememberPlayButtonSpin(isPlayingOrBuffering)
            AccentRevealGroup(
                songId = song.id,
                accent = revealAccent,
                accentContent = playButtonContentColor,
                modifier = Modifier.fillMaxWidth()
            ) { accent, accentContent ->
                PlaybackControls(
                    onPrevious = playerActions.onPrevious,
                    onPlayPause = playerActions.onPlayPause,
                    onNext = playerActions.onNext,
                    contentColor = contentColor,
                    playButtonColor = accent,
                    playButtonContentColor = accentContent,
                    isPlayingOrBuffering = isPlayingOrBuffering,
                    playbackState = playbackState,
                    spin = playSpin
                )
            }
        }
    }
}
