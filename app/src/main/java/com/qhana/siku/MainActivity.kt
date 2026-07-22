package com.qhana.siku

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.service.MusicPlaybackService
import com.qhana.siku.ui.MusicPlayerScreen
import com.qhana.siku.ui.screens.PermissionScreen
import com.qhana.siku.ui.theme.MusicPlayerTheme
import com.qhana.siku.ui.theme.paletteStyleFromName
import com.qhana.siku.ui.viewmodel.AuthViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Única Activity de la app. Se limita a lo que SOLO una Activity puede hacer: splash,
 * edge-to-edge, permisos de runtime, window flags (keep-screen-on) y deep link del
 * NowPlaying desde la notificación. Toda la composición vive en [MusicPlayerScreen]
 * (ui/MusicPlayerScreen.kt) — el NavHost en ui/navigation/AppNavHost.kt y la capa
 * flotante del reproductor en ui/PlayerOverlay.kt, coordinadas por MusicAppState.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var snackbarManager: SnackbarManager

    // Misma instancia que resuelve hiltViewModel() dentro de MusicPlayerScreen
    // (ambos usan el ViewModelStore de la activity). Se necesita aquí para la
    // condición de retención del splash.
    private val authViewModel: AuthViewModel by viewModels()

    private var pendingNowPlayingNavigation by mutableStateOf(false)
    private var hasPermission by mutableStateOf(false)
    private var userWantsScreenOn = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Retiene el splash del sistema hasta que MSAL resuelva la sesión (isLoggedIn
        // deja de ser null); el NavHost compone entonces directo en el destino correcto
        // sin flash del Login. tryRestoreSession tiene su propio timeout, no cuelga.
        splashScreen.setKeepOnScreenCondition { authViewModel.isLoggedIn.value == null }
        appLogger.lifecycle("onCreate() - savedInstanceState=${savedInstanceState != null}")

        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )

        pendingNowPlayingNavigation = savedInstanceState == null &&
            intent?.action == MusicPlaybackService.ACTION_SHOW_NOW_PLAYING
        hasPermission = checkAudioPermission()

        setContent {
            // Acento del álbum en reproducción como seed del ColorScheme global, para
            // homogeneizar el acento en toda la app (fallback al sistema sin reproducción).
            // Igual que el NowPlaying: secondary en modo oscuro, primary en claro (respeta
            // el override manual de color, que es granular por modo).
            val themePlaybackViewModel: PlaybackViewModel = hiltViewModel()
            val themeNowPlaying by themePlaybackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
            val isThemeDark = isSystemInDarkTheme()
            val chosenArgb = themeNowPlaying.albumColors?.let { if (isThemeDark) it.secondary else it.primary }
            // Carátula ACROMÁTICA (saturación ~0: negro/blanco/gris, p. ej. Stream of Consciousness):
            // NO hereda el acento anterior ni deja que el PaletteStyle le invente un matiz (rosa)
            // — usa un esquema NEUTRO en grises (monochrome) que solo sigue el claro/oscuro.
            //
            // NO aplica a un color elegido A MANO: ahí el usuario ya decidió, y muchos colores
            // legítimos de una carátula (un verde grisáceo como #A7BBB2 = saturación 0.11) caen
            // por debajo del umbral. Filtrarlos hacía que elegirlos en el selector no cambiara
            // NADA del tema, que es como se detectó esto.
            // Paréntesis obligatorios: `?:` liga MENOS que `&&`, así que sin ellos el compilador
            // lee `(!manual && Boolean?) ?: false` y no tipa.
            val isAchromatic = !themeNowPlaying.hasManualColor && (chosenArgb?.let { argb ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(argb, hsv)
                hsv[1] < 0.15f
            } ?: false)
            // Seed CROMÁTICO: color del álbum solo si tiene croma. Se mantiene el ÚLTIMO cromático
            // mientras se extraen los colores de la nueva canción — al cambiar de canción el UiState
            // nace con albumColors=null un instante y, sin esto, el tema saltaba al dynamic del
            // sistema (color ajeno, p. ej. celeste del wallpaper) antes del color real.
            val rawSeed = if (chosenArgb != null && !isAchromatic) Color(chosenArgb) else null
            var lastSeed by remember { mutableStateOf<Color?>(null) }
            LaunchedEffect(rawSeed) { if (rawSeed != null) lastSeed = rawSeed }
            // Acromática → seedeamos el gris con estilo Monochrome (neutro, sin heredar nada).
            // Cromática → el color (o el último mientras extrae). Cold start sin nada → dynamic/baseline.
            val seedColor = if (isAchromatic) chosenArgb?.let { Color(it) } else (rawSeed ?: lastSeed)
            // Estilo elegido en Ajustes → Apariencia. Se observa del DataStore: cambiarlo
            // repinta el tema en vivo, sin recrear la Activity ni recompilar para probar otro.
            val paletteStyleName by themePlaybackViewModel.themePaletteStyle.collectAsStateWithLifecycle()
            MusicPlayerTheme(
                seedColor = seedColor,
                monochrome = isAchromatic,
                paletteStyle = paletteStyleFromName(paletteStyleName)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (hasPermission) {
                        MusicPlayerScreen(
                            snackbarManager = snackbarManager,
                            pendingNowPlayingNavigation = pendingNowPlayingNavigation,
                            onNavigationHandled = { pendingNowPlayingNavigation = false },
                            onKeepScreenOnChanged = { enabled ->
                                userWantsScreenOn = enabled
                                updateKeepScreenOn(enabled)
                            }
                        )
                    } else {
                        PermissionScreen(
                            onRequestPermission = {
                                permissionLauncher.launch(getRequiredPermissions())
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MusicPlaybackService.ACTION_SHOW_NOW_PLAYING) {
            pendingNowPlayingNavigation = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (userWantsScreenOn) updateKeepScreenOn(true)
        restoreSystemBars()
    }

    private fun restoreSystemBars() {
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        super.onStop()
        updateKeepScreenOn(false)
    }

    private fun updateKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun checkAudioPermission(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
