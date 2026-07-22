package com.qhana.siku.ui.screens

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.components.UnifiedProgressBar
import com.qhana.siku.ui.theme.MusicPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.basicMarquee
import com.qhana.siku.ui.components.MaterialSymbol

@AndroidEntryPoint
class AmbientPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TIMEOUT_MINUTES = "timeout_minutes"
        const val NO_TIMEOUT = -1
    }

    @Inject
    lateinit var musicController: MusicController

    @Inject
    lateinit var repository: IMusicRepository

    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        val timeoutMinutes = intent.getIntExtra(EXTRA_TIMEOUT_MINUTES, NO_TIMEOUT)
        if (timeoutMinutes > 0) startTimeout(timeoutMinutes)
        setContent {
            MusicPlayerTheme(darkTheme = true) {
                AmbientPlayerScreen(musicController = musicController, repository = repository, onDoubleTap = { finish() })
            }
        }
    }

    private fun startTimeout(minutes: Int) {
        countDownTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() { finish() }
        }.start()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}

@Composable
fun AmbientPlayerScreen(
    musicController: MusicController,
    repository: IMusicRepository,
    onDoubleTap: () -> Unit
) {
    val controllerSong by musicController.currentSong.collectAsStateWithLifecycle()
    val controllerSongId = controllerSong?.id
    val currentSong by remember(controllerSongId) {
        if (controllerSongId != null) repository.getSongByIdFlow(controllerSongId) else flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = controllerSong)
    val playbackState by musicController.playbackState.collectAsStateWithLifecycle()
    val currentPosition by musicController.currentPosition.collectAsStateWithLifecycle()
    val duration by musicController.duration.collectAsStateWithLifecycle()
    // Cache del Flow: repository.getFavoritesIds() crea un nuevo Flow en cada recomposición
    // (por el .map interno). Sin remember, collectAsStateWithLifecycle resuscribe en cada frame
    // y el valor cae a emptySet entre emisiones, por eso el ícono nunca se veía relleno.
    val favoritesFlow = remember(repository) { repository.getFavoritesIds() }
    val favorites by favoritesFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavorite = currentSong?.let { it.id in favorites } ?: false
    val isPlaying = playbackState == PlaybackState.PLAYING
    val isBuffering = playbackState == PlaybackState.BUFFERING
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }) {
        Row(modifier = Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            AmbientAlbumArt(song = currentSong, modifier = Modifier.weight(0.45f).aspectRatio(1f))
            Column(modifier = Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(text = currentSong?.title ?: stringResource(R.string.ambient_no_playback), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Visible, modifier = Modifier.basicMarquee(repeatDelayMillis = 10000, initialDelayMillis = 1000))
                Text(text = currentSong?.artist ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(32.dp))
                UnifiedProgressBar(currentPosition = currentPosition, duration = duration, onSeek = { musicController.seekTo(it) }, trackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f), textColor = Color.White.copy(alpha = 0.5f), showThumb = true, trackHeight = 4.dp, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(32.dp))
                AmbientControls(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isFavorite = isFavorite,
                    onPrevious = { musicController.previous() },
                    onPlayPause = { musicController.playPause() },
                    onNext = { musicController.next() },
                    onToggleFavorite = {
                        currentSong?.let { song -> scope.launch { repository.toggleFavorite(song.id) } }
                    },
                    onExit = onDoubleTap
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
    LaunchedEffect(isPlaying) { while (isPlaying) { musicController.updatePosition(); delay(1000) } }
}

@Composable
private fun AmbientAlbumArt(song: Song?, modifier: Modifier = Modifier) {
    var isImageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(song) { isImageLoaded = false }
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), shadowElevation = 16.dp, color = if (isImageLoaded) Color.Transparent else Color(0xFF2A2A2A)) {
        Box(contentAlignment = Alignment.Center) {
            if (!isImageLoaded) MaterialSymbol("music_note", color = Color.White.copy(alpha = 0.2f), modifier = Modifier.fillMaxSize(0.5f))
            if (song?.albumArtUri != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(song.albumArtUri).crossfade(true).build(), contentDescription = stringResource(R.string.common_album_art), contentScale = ContentScale.Crop, onSuccess = { isImageLoaded = true }, onError = { isImageLoaded = false }, modifier = Modifier.fillMaxSize().scale(1.02f))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AmbientControls(isPlaying: Boolean, isBuffering: Boolean, isFavorite: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onToggleFavorite: () -> Unit, onExit: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onExit, modifier = Modifier.size(64.dp)) { MaterialSymbol(icon = "exit_to_app", color = Color.White, modifier = Modifier.size(32.dp), size = 32.sp) }
        IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) { MaterialSymbol(icon = "skip_previous", color = Color.White, modifier = Modifier.size(40.dp), size = 40.sp) }
        // OutlinedIconButton real (M3 Expressive: shape-morph al presionar) con el borde blanco.
        OutlinedIconButton(
            onClick = onPlayPause,
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(5.dp, Color.White),
            modifier = Modifier.size(80.dp)
        ) {
            if (isBuffering) LoadingIndicator(modifier = Modifier.size(36.dp), color = Color.White)
            else MaterialSymbol(icon = if (isPlaying) "pause" else "play_arrow", color = Color.White, size = 48.sp, fill = true)
        }
        IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) { MaterialSymbol(icon = "skip_next", color = Color.White, modifier = Modifier.size(40.dp), size = 40.sp) }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(64.dp)) { MaterialSymbol(icon = "favorite", fill = isFavorite, color = Color.White, modifier = Modifier.size(28.dp), size = 28.sp) }
    }
}