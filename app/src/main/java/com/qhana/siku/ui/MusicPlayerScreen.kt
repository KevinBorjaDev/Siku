package com.qhana.siku.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.SnackbarLength
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.LocalSnackbarHostState
import com.qhana.siku.ui.navigation.AppNavHost
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.viewmodel.AuthViewModel
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel
import com.qhana.siku.worker.WorkerTags
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Raíz de composición de la app: colecta los ViewModels de la Activity, corre los efectos
 * globales (snackbar bus, toasts de descargas, polling de posición, navegación por sesión)
 * y compone las tres capas — [AppNavHost] (pantallas), [PlayerOverlay] (pill ↔ player + FAB)
 * y el host único de snackbars — dentro de un [SharedTransitionLayout] compartido.
 *
 * Los ViewModels se resuelven AQUÍ (scope de la Activity) y bajan por parámetro: dentro de
 * una ruta del NavHost, `hiltViewModel()` daría una instancia nueva por pantalla.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MusicPlayerScreen(
    snackbarManager: SnackbarManager,
    authViewModel: AuthViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    // Fuentes de música: instancia de la Activity, compartida entre Onboarding y Ajustes para
    // que ambas vean el mismo estado de la carpeta local.
    sourcesViewModel: SourcesViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    pendingNowPlayingNavigation: Boolean = false,
    onNavigationHandled: () -> Unit = {},
    onKeepScreenOnChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current

    val isLoggedIn by authViewModel.isLoggedIn.collectAsStateWithLifecycle()
    val authLoading by authViewModel.isLoading.collectAsStateWithLifecycle()
    val authError by authViewModel.error.collectAsStateWithLifecycle()
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()

    // --- Efectos globales ---

    // 1. Keep Screen On (la Activity pone/quita el window flag).
    LaunchedEffect(keepScreenOn) {
        onKeepScreenOnChanged(keepScreenOn)
    }

    // 2. Feedback CENTRALIZADO: un único host escucha el bus singleton (SnackbarManager).
    // Cualquier ViewModel emite ahí sin importar su instancia (fix del desfase de instancias).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        snackbarManager.events.collect { event ->
            val duration = when (event.length) {
                SnackbarLength.SHORT -> SnackbarDuration.Short
                SnackbarLength.LONG -> SnackbarDuration.Long
                SnackbarLength.INDEFINITE -> SnackbarDuration.Indefinite
            }
            // El dismiss tiene que ocurrir FUERA de la llamada que espera al snackbar anterior:
            // por eso showSnackbar va en su propio coroutine y el colector nunca se suspende
            // (si lo hiciera, no habría quién descartara al de pantalla y el bus se atascaría).
            if (event.replaceCurrent) snackbarHostState.currentSnackbarData?.dismiss()
            launch {
                val result = snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                    withDismissAction = event.withDismissAction,
                    duration = duration
                )
                if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
            }
        }
    }

    // 3. Refresco de colores tras regenerar: evento tipado (desacoplado del texto del mensaje).
    LaunchedEffect(Unit) {
        libraryViewModel.colorsRegeneratedEvent.collectLatest {
            playbackViewModel.refreshCurrentSongColors()
        }
    }

    // 4. Download Toasts (Worker) — para descargas no-repair (ej. auto-download al reproducir).
    // Las redescargas (REPAIR_TAG) las gestiona LibraryViewModel.redownloadSong: si las
    // notificáramos aquí también habría doble toast. Sin pruneWork() (causaba carrera con
    // redownloadSong al borrar el WorkInfo que estaba esperando) — un Set local evita repetir.
    // WorkManager persiste los WorkInfo terminados entre procesos, así que la PRIMERA emisión
    // trae descargas de sesiones anteriores: se siembran en el Set sin notificar (si no, cada
    // arranque repetiría "Descarga completa" de la última descarga en stream).
    val workManager = remember { WorkManager.getInstance(context) }
    LaunchedEffect(Unit) {
        val notified = mutableSetOf<UUID>()
        var isInitialSnapshot = true
        workManager.getWorkInfosByTagFlow("download_tracking")
            .collectLatest { workInfos ->
                if (isInitialSnapshot) {
                    isInitialSnapshot = false
                    notified += workInfos.filter { it.state.isFinished }.map { it.id }
                    return@collectLatest
                }
                workInfos
                    .filter {
                        it.state.isFinished &&
                            it.id !in notified &&
                            WorkerTags.REPAIR_TAG !in it.tags
                    }
                    .forEach { workInfo ->
                        notified += workInfo.id
                        val title = workInfo.outputData.getString("title")
                        if (title != null) {
                            val msg = if (workInfo.state == WorkInfo.State.SUCCEEDED)
                                context.getString(R.string.download_msg_complete, title)
                            else context.getString(R.string.download_msg_failed, title)
                            snackbarManager.show(msg)
                        }
                    }
            }
    }

    // --- Navegación ---
    // isLoggedIn == null: la sesión aún se está resolviendo (MSAL lee la cuenta de
    // disco tras un cold start). No componemos el NavHost todavía — si lo hiciéramos
    // con Onboarding como startDestination, al resolverse la sesión navegaríamos a Library
    // y el usuario vería el Onboarding un instante (flash al reabrir la app).
    val loggedIn = isLoggedIn ?: return
    val localFolderUri by sourcesViewModel.localFolderUri.collectAsStateWithLifecycle()

    // Una biblioteca necesita al menos una fuente. OneDrive ya NO es obligatorio: un usuario
    // solo-local nunca ve la pantalla de cuenta de Microsoft.
    //
    // "Tener alguna fuente" es la única condición: sustituye a un flag `onboardingCompleted`
    // persistido, que sería estado redundante capaz de desincronizarse (flag a true sin fuentes =
    // biblioteca vacía sin salida) y que además haría pasar por el onboarding a quien ya tenía la
    // cuenta conectada de antes.
    val hasAnySource = loggedIn || localFolderUri != null
    // remember: el destino inicial se decide una vez. Que el usuario conecte una fuente durante
    // el onboarding no debe recomponer el NavHost por debajo ni sacarlo de la pantalla.
    val startDestination = remember {
        if (hasAnySource) Screen.Library.route else Screen.Onboarding.route
    }

    val appState = rememberMusicAppState()

    // Sincronización al conectar sesión (primer arranque de la composición o login posterior).
    var previousLoginState by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (!hasInitialized) {
            hasInitialized = true
            previousLoginState = loggedIn
            if (loggedIn) syncViewModel.refreshSongs(force = false)
            return@LaunchedEffect
        }
        if (previousLoginState != loggedIn) {
            previousLoginState = loggedIn
            // Conectar OneDrive ya no implica navegar: durante el onboarding el usuario sigue
            // eligiendo fuentes, y desde Ajustes se queda donde estaba. Solo sincronizamos.
            if (loggedIn) syncViewModel.refreshSongs(force = false)
        }
    }

    // Sin ninguna fuente configurada (logout de OneDrive sin carpeta local, o sesión caducada)
    // no hay biblioteca posible: volvemos a pedir una fuente. Con música local, en cambio, el
    // usuario se queda donde está — desconectar la nube no lo expulsa de su biblioteca offline.
    LaunchedEffect(hasAnySource) {
        if (!hasAnySource && appState.navController.currentDestination?.route != Screen.Onboarding.route) {
            appState.playerExpanded = false
            appState.navController.navigate(Screen.Onboarding.route) { popUpTo(0) { inclusive = true } }
        }
    }

    // Deep link del NowPlaying (notificación → abrir player).
    LaunchedEffect(pendingNowPlayingNavigation, currentSong) {
        if (pendingNowPlayingNavigation && currentSong != null) {
            appState.playerExpanded = true
            onNavigationHandled()
        }
    }

    // Position Updates — lifecycle-aware: el polling se detiene con la app en background
    // (repeatOnLifecycle cancela el bucle en onStop y lo reanuda en onStart), evitando
    // despertar el main thread cada segundo con la pantalla apagada toda la noche.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playbackState, lifecycleOwner) {
        if (playbackState == PlaybackState.PLAYING) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Refresh INMEDIATO al (re)entrar en primer plano: sin él, el primer tick
                // llegaba 1s tarde y el progreso quedaba congelado en el valor
                // pre-background durante ese segundo.
                playbackViewModel.updatePosition()
                while (isActive) {
                    kotlinx.coroutines.delay(1000L)
                    playbackViewModel.updatePosition()
                }
            }
        }
    }

    // --- Composición de capas ---
    // El hostState viaja por CompositionLocal para que los diálogos full-screen (ventana propia,
    // que tapa el host de abajo) puedan montar su propio SnackbarHost sobre el mismo estado.
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        SharedTransitionLayout {
            // Box: permite montar el PlayerOverlay como capa flotante SOBRE el NavHost.
            Box(modifier = Modifier.fillMaxSize()) {
                AppNavHost(
                    appState = appState,
                    startDestination = startDestination,
                    loggedIn = loggedIn,
                    authLoading = authLoading,
                    authError = authError,
                    onConnectOneDrive = { activity -> authViewModel.signIn(activity) },
                    onDisconnectOneDrive = { authViewModel.logout() },
                    onRequestSync = { syncViewModel.refreshSongs(force = false) },
                    playbackViewModel = playbackViewModel,
                    libraryViewModel = libraryViewModel,
                    sourcesViewModel = sourcesViewModel,
                    sharedTransitionScope = this@SharedTransitionLayout
                )

                PlayerOverlay(
                    appState = appState,
                    playbackViewModel = playbackViewModel,
                    libraryViewModel = libraryViewModel,
                    snackbarManager = snackbarManager,
                    sharedTransitionScope = this@SharedTransitionLayout
                )

                // Host de snackbars ÚNICO de la app (sobre el NavHost y por encima del
                // PlayerOverlay flotante).
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = ComponentConfig.FloatingBarListInset)
                )
            }
        }
    }
}
