package com.qhana.siku.ui

import com.qhana.siku.ui.theme.LocalAppColors
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qhana.siku.ui.theme.AppSnackbar
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.SnackbarLength
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.LocalSnackbarHostState
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.navigation.AppNavHost
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.screens.LibraryBottomBarPillLift
import com.qhana.siku.ui.screens.LibraryRailWidth
import com.qhana.siku.ui.screens.POSITION_TICK_MS
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
 * y compone las tres capas — [AppNavHost] (pantallas), [PlayerOverlay] (píldora ↔ reproductor)
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
    onKeepScreenOnChanged: (Boolean) -> Unit = {},
    // Izado desde MainActivity: el tema (que envuelve a esta pantalla) necesita saber si el
    // reproductor está abierto para congelar la animación del esquema (ver MusicPlayerTheme).
    playerExpandedState: androidx.compose.runtime.MutableState<Boolean> =
        rememberSaveable { mutableStateOf(false) },
    // Izado por el mismo motivo: la fila en PREPARACIÓN (el frame anterior a expandir) también
    // congela el tema — ver el comentario en MainActivity y `MusicAppState.pendingRowOrigin`.
    pendingRowOriginState: androidx.compose.runtime.MutableState<String?> =
        remember { mutableStateOf<String?>(null) }
) {
    val context = LocalContext.current

    val isLoggedIn by authViewModel.isLoggedIn.collectAsStateWithLifecycle()
    val authLoading by authViewModel.isLoading.collectAsStateWithLifecycle()
    val authError by authViewModel.error.collectAsStateWithLifecycle()
    // Avatar de cuenta para el header de la biblioteca (foto de perfil + inicial de fallback).
    val accountPhotoPath by authViewModel.accountPhotoPath.collectAsStateWithLifecycle()
    val accountInitial by authViewModel.accountInitial.collectAsStateWithLifecycle()
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()

    // --- Efectos globales ---

    // 1. Keep Screen On: el efecto vive más abajo, junto a `playerExpanded` (necesita ese estado).

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

    // 4. Download Toasts (Worker). Reglas por tipo de descarga:
    //  - REPAIR_TAG (redescargas): NO se tocan aquí, las gestiona LibraryViewModel.redownloadSong
    //    (si no, doble toast).
    //  - AUTO_DOWNLOAD_TAG (prefetch de fondo al reproducir por streaming): ÉXITO → "ahora offline";
    //    FALLO → mensaje SUAVE ("seguirá en streaming"), no el alarmante "Fallo al descargar", porque
    //    la canción suena igual por streaming. (El falso fallo que se veía —chip "Descargado" +
    //    snackbar "Fallo"— era otro bug, ya cerrado en MusicDownloader.finalizeDownload.)
    //  - Iniciadas por el usuario (botón de descargar): ÉXITO y FALLO directo.
    // Sin pruneWork() (causaba carrera con redownloadSong al borrar el WorkInfo que estaba esperando)
    // — un Set local evita repetir. WorkManager persiste los WorkInfo terminados entre procesos, así
    // que la PRIMERA emisión trae descargas de sesiones anteriores: se siembran en el Set sin
    // notificar (si no, cada arranque repetiría "Descarga completa" de la última descarga en stream).
    val workManager = remember { WorkManager.getInstance(context) }
    LaunchedEffect(Unit) {
        val notified = mutableSetOf<UUID>()
        var isInitialSnapshot = true
        workManager.getWorkInfosByTagFlow("download_tracking")
            // `collect`, NO `collectLatest`: este bloque tiene EFECTOS (marca ids como avisados y
            // muestra el snackbar). Con `collectLatest`, una emisión nueva —y WorkManager emite
            // en ráfaga durante un sync— lo cancelaba a mitad del recorrido, dejando ids ya
            // marcados en `notified` cuyo aviso nunca llegó a mostrarse: descargas que terminan
            // en silencio. El trabajo es corto y no se puede abandonar a medias.
            .collect { workInfos ->
                if (isInitialSnapshot) {
                    isInitialSnapshot = false
                    notified += workInfos.filter { it.state.isFinished }.map { it.id }
                    return@collect
                }
                workInfos
                    // REPAIR se gestiona en LibraryViewModel (doble toast si no). Las demás pasan
                    // el filtro y deciden ABAJO si notifican, según sean auto o no.
                    .filter {
                        it.state.isFinished &&
                            it.id !in notified &&
                            WorkerTags.REPAIR_TAG !in it.tags
                    }
                    .forEach { workInfo ->
                        notified += workInfo.id
                        val succeeded = workInfo.state == WorkInfo.State.SUCCEEDED
                        val isAuto = WorkerTags.AUTO_DOWNLOAD_TAG in workInfo.tags
                        val title = workInfo.outputData.getString("title")
                        if (title != null) {
                            val msg = when {
                                succeeded -> context.getString(R.string.download_msg_complete, title)
                                // Fallo de una AUTO-descarga (prefetch de fondo): mensaje suave y
                                // exacto —la canción sigue sonando por streaming— en vez del alarmante
                                // "Fallo al descargar". Ya no puede ser un FALSO fallo: finalizeDownload
                                // garantiza que una descarga commiteada devuelve éxito.
                                isAuto -> context.getString(R.string.download_msg_auto_failed, title)
                                // Fallo de una descarga PEDIDA por el usuario: mensaje directo.
                                else -> context.getString(R.string.download_msg_failed, title)
                            }
                            snackbarManager.show(msg)
                        }
                    }
            }
    }

    // --- Navegación ---
    val hasLocalSource by sourcesViewModel.hasLocalSource.collectAsStateWithLifecycle()

    // isLoggedIn == null: la sesión aún se está resolviendo (MSAL lee la cuenta de disco tras un
    // cold start). No componemos el NavHost todavía — si lo hiciéramos con Onboarding como
    // startDestination, al resolverse la sesión navegaríamos a Library y el usuario vería el
    // Onboarding un instante (flash al reabrir la app).
    //
    // Con una fuente LOCAL configurada no hay nada que esperar: el destino es la biblioteca diga lo
    // que diga MSAL, así que se sigue con `false` y la sesión se incorpora cuando llegue (el
    // `LaunchedEffect(loggedIn)` de abajo dispara entonces el sync de la nube, como en un login
    // normal). Esto es lo que evita que un usuario solo-local mire el splash esperando a una
    // librería de autenticación que no va a usar; la misma condición gobierna el splash del sistema
    // en MainActivity, y las dos tienen que decir lo mismo o volvería el flash que se quiso evitar.
    val loggedIn = isLoggedIn ?: if (hasLocalSource) false else {
        // Sin fuente local sí hay que esperar: el destino depende de la respuesta. Pasado un rato
        // (AuthViewModel.sessionRestoreSlow) se deja caer el splash y se dice qué está pasando, en
        // vez de sostener una pantalla muda que se lee como app colgada. No hay botón de reintentar
        // porque no hay nada que reintentar: la espera sigue viva por debajo y esto desaparece solo
        // en cuanto MSAL responda.
        val restoreSlow by authViewModel.sessionRestoreSlow.collectAsStateWithLifecycle()
        if (restoreSlow) SessionRestoreSlowScreen()
        return
    }

    // Una biblioteca necesita al menos una fuente. OneDrive ya NO es obligatorio: un usuario
    // solo-local nunca ve la pantalla de cuenta de Microsoft.
    //
    // "Tener alguna fuente" es la única condición: sustituye a un flag `onboardingCompleted`
    // persistido, que sería estado redundante capaz de desincronizarse (flag a true sin fuentes =
    // biblioteca vacía sin salida) y que además haría pasar por el onboarding a quien ya tenía la
    // cuenta conectada de antes.
    val hasAnySource = loggedIn || hasLocalSource
    // remember: el destino inicial se decide una vez. Que el usuario conecte una fuente durante
    // el onboarding no debe recomponer el NavHost por debajo ni sacarlo de la pantalla.
    //
    // El onboarding a medias también manda: si se conectó la nube pero el proceso murió antes de
    // fijar el tope de descargas, se vuelve al onboarding (que arranca en ese paso) en vez de
    // caer en la biblioteca con la decisión sin tomar. El flag solo cuenta CON sesión activa,
    // así que nunca puede exiliar a un usuario solo-local de su biblioteca.
    val storageStepPending = sourcesViewModel.storageStepPending
    val startDestination = remember {
        if (hasAnySource && !(loggedIn && storageStepPending)) Screen.Library.route
        else Screen.Onboarding.route
    }

    // El estado "player abierto" lo POSEE el booleano izado a MainActivity y lo gobierna
    // `MusicAppState` — no hay copia ni espejo, para que quien lo lea se entere EN EL MISMO frame en
    // que la capa se expande. (El lector que lo exigía era el TEMA, hasta el 20 ago 2026; ver el
    // KDoc de `MusicAppState.playerExpandedState`, donde vive el motivo completo.)
    // Sonda (solo debug): cada recomposición de la raíz de la app.
    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "MusicPlayerScreen recompuesta" } }
    val appState = rememberMusicAppState(
        playerExpandedState = playerExpandedState,
        pendingRowOriginState = pendingRowOriginState,
        currentSong = playbackViewModel.currentSong
    )
    val playerExpanded = appState.playerExpanded

    // --- La capa del reproductor: su estado, su Transition y el ORIGEN de su morph ---
    //
    // Se decide AQUÍ y no dentro de PlayerOverlay porque tiene dos consumidores que no comparten
    // padre: la capa (que hace el AnimatedContent) y las FILAS de las listas del NavHost (que tienen
    // que ocultarse cuando son el origen). Ambos leen el mismo valor, en el mismo frame.
    //
    // **La ruta se lee UNA vez, INCONDICIONALMENTE, y de esta variable** — nunca `appState.currentRoute`
    // dentro del `when`. Ese getter es `@Composable` y por dentro es `currentBackStackEntryAsState()`,
    // o sea un `produceState(initialValue = null)` que vive EN EL SITIO DE LLAMADA: dentro de una rama
    // del `when` que el `playerExpanded -> Expanded` cortocircuita mientras el reproductor está abierto,
    // esa llamada sale de la composición al abrir y VUELVE A NACER al cerrar — con su `null` inicial
    // durante una composición, hasta que el flow del NavController emite en el frame siguiente. Un
    // frame con ruta `null` = `isPillRoute(null) == false` = la capa pasa por **`Hidden`** entre
    // `Expanded` y `Collapsed`. Consecuencia (medida con la sonda el 17 ago, y visible en video): el
    // `AnimatedContent` recibe Expanded→Hidden y al frame siguiente Hidden→Collapsed; en esa segunda
    // interrupción `Transition.updateTarget` fija `currentState = Hidden`, la rama del reproductor
    // deja de ser visible para su transición y se DESCOMPONE EN EL ACTO, y la píldora entra con el
    // spec de Hidden→Collapsed (`appFadeEnter`, un fundido de 500 ms sin morph). Era "el cierre a la
    // píldora no ocurre": el reproductor desaparecía en un frame y la píldora se fundía sola. El
    // cierre a una FILA disimulaba lo mismo porque el bounds de la fila corre en SU propia transición
    // y seguía animando la superficie hacia la fila aunque la pareja hubiera muerto.
    val currentEntry = appState.currentBackStackEntry
    val currentRoute = currentEntry?.destination?.route

    // Cuánto tiene que apartarse lo que FLOTA abajo (la píldora y el snackbar) del cromo que pone
    // la biblioteca. Sale de `libraryChrome`, la misma función que consumen `LibraryScreen` —para
    // decidir cuál dibuja— y `PlayerOverlay`. Aquí no se anima: un snackbar aparece y desaparece,
    // así que no hay nada que ver moverse.
    //
    // **Las DOS ramas, no solo la de abajo** (27 ago 2026): hasta esa fecha esto solo miraba
    // `BottomBar`, así que en horizontal el snackbar se extendía por debajo del rail mientras la
    // píldora sí se apartaba de él (`PlayerOverlay` deriva su `chromeRailInset` del mismo `when`).
    // Dos piezas flotando en la misma capa leyendo media condición cada una es exactamente lo que
    // esta función existe para evitar.
    val libraryBottomTabs by playbackViewModel.libraryBottomTabs.collectAsStateWithLifecycle()
    val libraryChromeMode = libraryChrome(
        route = currentRoute,
        bottomTabs = libraryBottomTabs,
        landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    )
    val chromeLift =
        if (libraryChromeMode == LibraryChrome.BottomBar) LibraryBottomBarPillLift else 0.dp
    val chromeRailInset =
        if (libraryChromeMode == LibraryChrome.Rail) LibraryRailWidth else 0.dp
    val requestedLayer = when {
        playerExpanded -> PlayerLayerState.Expanded
        currentSong != null && isPillRoute(currentRoute) -> PlayerLayerState.Collapsed
        else -> PlayerLayerState.Hidden
    }
    // **Durante el FRAME DE PREPARACIÓN de una fila la capa NO se mueve** (21 ago 2026).
    //
    // Ese frame es, por definición, "el reproductor ya se está abriendo": `openPlayer(ROW)` anotó la
    // fila y espera su `onRowPlaced` para expandir (ver `ContainerOriginRole`). La capa no tiene nada
    // que decidir ahí, y sin embargo hasta hoy podía cambiar de estado — **sin cola de reproducción**,
    // `announceSelection` fija `currentSong` en el mismo handler del tap, así que `Collapsed` pasaba a
    // ser cierto y la capa arrancaba una transición `Hidden→Collapsed` que nadie llega a ver.
    //
    // Dos daños, y el segundo es el que rompía la app:
    //  1. Componía el MiniPlayer entero (la rama `Collapsed`) **en el frame del tap**, que es el frame
    //     más caro de la app y justo el que este diseño lleva meses intentando aligerar.
    //  2. Al frame siguiente la fila avisa y se pide `Expanded`, pero la transición ya está `isRunning`
    //     → `rememberPlayerMorphOrigin` descartaba la petición `ROW`: apertura sin container transform
    //     y, al cerrar, un morph estrenado con las dos puntas naciendo en el mismo frame → capa VARADA
    //     (colocada, tragándose los toques, y a alpha 0). Ver el KDoc de `rememberPlayerMorphOrigin`.
    //
    // El gate de allá cubre la clase entera (también cuando la transición venía corriendo de antes);
    // éste quita el caso que la app se provocaba sola, y de paso el frame del tap vuelve a ser barato.
    //
    // Contenedor PLANO y no `mutableStateOf`: se lee y se escribe en la misma composición, igual que
    // `MorphOriginHolder` — como estado de snapshot invalidaría el scope que lo acaba de escribir.
    // Congelar aquí no descoloca a la fila: `rowVisibility` mapea a `Visible` todo lo que no sea
    // `Expanded`, así que da igual quedarse en `Hidden` (sin cola) o en `Collapsed` (con ella).
    val layerHolder = remember { arrayOf(requestedLayer) }
    val playerLayer = if (appState.pendingRowOrigin != null) layerHolder[0]
        else requestedLayer.also { layerHolder[0] = it }
    val playerLayerTransition = updateTransition(playerLayer, label = "playerLayer")
    // Sonda: el ESTADO de la capa en cada composición (qué target pide `playerLayer`, dónde está la
    // Transition). Es lo que hace falta cuando el síntoma es "la animación no ocurre" y no un frame lento.
    SideEffect {
        com.qhana.siku.data.util.JankProbe.note {
            "capa: layer=$playerLayer current=${playerLayerTransition.currentState} " +
                "target=${playerLayerTransition.targetState} running=${playerLayerTransition.isRunning} " +
                "expanded=$playerExpanded song=${currentSong?.title?.take(12)} route=$currentRoute"
        }
    }
    // Congelado mientras la transición corre — es la regla que evita filas varadas en el overlay
    // (ver el KDoc de rememberPlayerMorphOrigin). Va DESPUÉS de updateTransition a propósito: en el
    // frame que arranca una apertura o un cierre necesita ver ya el targetState nuevo.
    val morphOrigin = rememberPlayerMorphOrigin(playerLayerTransition, appState, currentSong?.id)
    // Los colores de lo que queda DEBAJO del reproductor, retenidos mientras la capa se abre. Ver el
    // KDoc de [rememberUnderlayAppColors]: es lo que evita que el cambio de seed de la canción
    // repinte la biblioteca en el mismo frame en que arranca el morph.
    val underlayColors = rememberUnderlayAppColors(
        layerTransition = playerLayerTransition,
        preparingOrigin = appState.pendingRowOrigin != null
    )
    // Reabrir el player al VOLVER de un detalle al que se navegó estando el player abierto (ver
    // `MusicAppState.navigateFromPlayer`). Se evalúa en cada cambio de destino: si la entrada actual
    // es la que se anotó al salir, el player se expande de nuevo. (`currentEntry` se lee arriba, junto
    // con la ruta de la capa: una sola suscripción al NavController para los dos.)
    LaunchedEffect(currentEntry) {
        appState.reopenPlayerIfReturningFrom(currentEntry)
    }

    // Keep Screen On, ACOTADO A CUANDO EL PLAYER ESTÁ ABIERTO.
    //
    // El flag se ponía desde aquí sin más condición que la preferencia, así que un ajuste que se
    // ofrece DENTRO del NowPlaying (el sol del toolbar) mantenía la pantalla encendida en la
    // biblioteca, en Ajustes o en el gestor de descargas — y, como se persiste, también en el
    // siguiente arranque de la app, sin que nada en pantalla recordara que estaba puesto. La
    // pantalla es el mayor consumo del teléfono con diferencia (un orden de magnitud sobre
    // reproducir audio), así que el alcance del flag es una decisión de batería, no de detalle.
    //
    // NO se condiciona además a que esté sonando: el caso de uso es mirar el reproductor —la letra,
    // la carátula— y ahí una pausa no significa que el usuario haya dejado de mirar.
    val screenOnActive = keepScreenOn && playerExpanded
    LaunchedEffect(screenOnActive) {
        onKeepScreenOnChanged(screenOnActive)
    }

    // Sincronización al conectar sesión (primer arranque de la composición o login posterior).
    //
    // NUNCA durante el onboarding: ahí el usuario todavía está eligiendo fuentes y fijando el
    // tope de descargas, así que arrancar el sync al conectar OneDrive empezaría a bajar audio
    // contra un tope que aún no ha decidido. El scan del primer arranque lo dispara `onFinish`
    // del onboarding (ver AppNavHost), una vez, con todas las fuentes ya configuradas.
    var previousLoginState by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var hasInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (!hasInitialized) {
            hasInitialized = true
            previousLoginState = loggedIn
            // En el arranque se compara contra `startDestination`, NO contra la ruta actual del
            // navController: este efecto puede correr antes de que el NavHost registre su
            // destino, y un `currentDestination` nulo se leería como "no estoy en onboarding".
            val startsInOnboarding = startDestination == Screen.Onboarding.route
            // Arrancamos FUERA del onboarding: si quedó un "tope pendiente" de una sesión
            // anterior (p. ej. el usuario desconectó la nube después), ya no aplica y se limpia
            // para que no reviva en el próximo arranque.
            if (!startsInOnboarding && storageStepPending) sourcesViewModel.clearStorageStepPending()
            // Cualquier fuente, no solo OneDrive: una biblioteca solo-local también necesita
            // su escaneo de arranque para ver los archivos que se copiaron desde el PC.
            if (hasAnySource && !startsInOnboarding) syncViewModel.refreshSongs(force = false)
            return@LaunchedEffect
        }
        if (previousLoginState != loggedIn) {
            previousLoginState = loggedIn
            // Acá sí vale la ruta actual: el cambio de sesión ocurre en caliente, con el NavHost
            // ya compuesto. Conectar OneDrive no navega: durante el onboarding el usuario sigue
            // eligiendo fuentes, y desde Ajustes se queda donde estaba. Solo sincronizamos.
            val onboarding = appState.navController.currentDestination?.route == Screen.Onboarding.route
            if (loggedIn && !onboarding) syncViewModel.refreshSongs(force = false)
        }
    }

    // Volver a la app re-lista las fuentes LOCALES: es justo cuando el usuario acaba de copiar
    // canciones al teléfono desde el PC.
    //
    // Hace falta porque el escaneo de arranque de arriba corre UNA vez por proceso, y una app de
    // música casi nunca vuelve a arrancar en frío: el servicio de reproducción mantiene el proceso
    // vivo durante días, así que sin esto la biblioteca se quedaba vieja hasta un pull-to-refresh
    // manual. La señal es el ciclo de vida, no un temporizador: no hay nada que sondear mientras
    // el usuario está fuera de la app.
    //
    // Solo lo LOCAL, y SOLO si hay una fuente local: una biblioteca de pura nube no tiene nada
    // que re-listar al volver a la app (su delta va en el scan completo, y pedirle a Graph en
    // cada alt-tab sería tráfico por nada). Por eso el observer se monta condicionado a
    // `hasLocalSource` y no a `hasAnySource`: sin música del dispositivo ni siquiera se registra.
    //
    // Registrarse dispara un ON_START de arranque además de los de vuelta, y ESE se descarta:
    // ver [skipStartupLocalRefresh]. Se dejaba correr por ser idempotente, pero idempotente no
    // es gratis y además bloqueaba al ScanWorker.
    // Compartido con el polling de posición de más abajo.
    val lifecycleOwner = LocalLifecycleOwner.current

    /**
     * El PRIMER ON_START del proceso se ignora: el escaneo de arranque de arriba ya cubre lo
     * local y hace EXACTAMENTE el mismo `discover`.
     *
     * Correr los dos no solo duplicaba el trabajo —medido en un Poco F5: los mismos 777 archivos
     * listados dos veces, ~5,6 s de I/O—, sino que el `ScanWorker` se quedaba **bloqueado en el
     * `syncMutex` que tenía este refresco**, así que el banner de sincronización no aparecía
     * hasta 3,7 s después de que la UI estuviera en pantalla. El refresco usa `tryLock` para
     * apartarse cuando ya hay un sync en marcha, pero la protección solo cubría esa dirección:
     * al revés, el sync espera al refresco y luego repite su trabajo.
     *
     * Sobrevive a la rotación (`rememberSaveable`) para que solo se salte el ON_START que de
     * verdad coincide con el escaneo de arranque, no los de una Activity recreada.
     */
    var skipStartupLocalRefresh by rememberSaveable { mutableStateOf(true) }

    if (hasLocalSource) {
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                // ON_START y no ON_RESUME: ON_RESUME también llega al cerrar un diálogo o al
                // volver del selector de carpetas, y eso no es "el usuario volvió a la app".
                //
                // La ruta se consulta AQUÍ, no al montar: durante el onboarding no debe
                // escanearse nada (el único scan del primer arranque lo dispara "Empezar"), pero
                // al terminarlo el usuario sigue en el mismo proceso y sí debe refrescarse.
                val inOnboarding =
                    appState.navController.currentDestination?.route == Screen.Onboarding.route
                if (event == Lifecycle.Event.ON_START) {
                    // La bandera marca el ON_START DEL ARRANQUE, así que se consume aquí aunque
                    // la ruta descarte el refresco: si solo se consumiera al refrescar, un primer
                    // arranque que empieza en el onboarding se la dejaría intacta y se la comería
                    // el primer regreso REAL a la app, perdiendo ese refresco legítimo.
                    val isStartupEvent = skipStartupLocalRefresh
                    skipStartupLocalRefresh = false
                    if (!isStartupEvent && !inOnboarding) {
                        syncViewModel.refreshLocalLibrary()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Sin ninguna fuente configurada (logout de OneDrive sin carpeta local, o sesión caducada)
    // no hay biblioteca posible: volvemos a pedir una fuente. Con música local, en cambio, el
    // usuario se queda donde está — desconectar la nube no lo expulsa de su biblioteca offline.
    LaunchedEffect(hasAnySource) {
        if (!hasAnySource && appState.navController.currentDestination?.route != Screen.Onboarding.route) {
            // `popUpTo(0){inclusive}` limpia TODO el back stack —el player incluido si estaba
            // abierto— así que no hace falta cerrarlo aparte.
            appState.navController.navigate(Screen.Onboarding.route) { popUpTo(0) { inclusive = true } }
        }
    }

    // Deep link del NowPlaying (notificación → abrir player).
    LaunchedEffect(pendingNowPlayingNavigation, currentSong) {
        if (pendingNowPlayingNavigation && currentSong != null) {
            appState.openPlayer()
            onNavigationHandled()
        }
    }

    // Position Updates — lifecycle-aware: el polling se detiene con la app en background
    // (repeatOnLifecycle cancela el bucle en onStop y lo reanuda en onStart), evitando
    // despertar el main thread cada segundo con la pantalla apagada toda la noche.
    // BUFFERING cuenta además de PLAYING: es el estado en el que la barra tiene algo que contar
    // (el búfer llenándose) y era justo cuando el bucle estaba parado, así que el indicador se
    // habría quedado congelado exactamente en el caso para el que existe.
    //
    // El estado se COLECTA dentro del efecto, no se lee en composición: la RAÍZ de la app no tiene
    // por qué recomponerse entera con cada BUFFERING/READY/PAUSED solo para (re)armar este bucle
    // — medido con la sonda, eran dos recomposiciones de `MusicPlayerScreen` por apertura, en plena
    // ventana del morph.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playbackViewModel.playbackState.collectLatest { state ->
                if (state != PlaybackState.PLAYING && state != PlaybackState.BUFFERING) return@collectLatest
                // Refresh INMEDIATO al (re)entrar en primer plano: sin él, el primer tick
                // llegaba 1s tarde y el progreso quedaba congelado en el valor
                // pre-background durante ese segundo.
                playbackViewModel.updatePosition()
                while (isActive) {
                    // MISMO periodo que la interpolación del track del NowPlaying, y por eso sale
                    // de su constante: la barra estira cada tick durante exactamente un tick, así
                    // que si los dos números se separan el dibujo va por delante o por detrás del
                    // dato (ver [POSITION_TICK_MS]).
                    kotlinx.coroutines.delay(POSITION_TICK_MS.toLong())
                    playbackViewModel.updatePosition()
                }
            }
        }
    }

    // --- Composición de capas ---
    // El snackbar solo reserva el alto del MiniPlayer cuando la píldora está REALMENTE en pantalla
    // (el estado Collapsed de la capa; en onboarding, ajustes, o con el player abierto, el inset fijo
    // dejaría el snackbar flotando sobre una barra que no existe).
    val miniPlayerShown = playerLayer == PlayerLayerState.Collapsed

    // El hostState viaja por CompositionLocal para que los diálogos full-screen (ventana propia,
    // que tapa el host de abajo) puedan montar su propio SnackbarHost sobre el mismo estado.
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        SharedTransitionLayout {
            // Las filas de lista están a ocho pantallas de aquí y necesitan DOS cosas para poder
            // ser el origen del morph del reproductor: el scope compartido y saber si les toca
            // serlo AHORA. Van por CompositionLocal en vez de por parámetro porque atravesar todas
            // las firmas intermedias por un detalle de la animación no compensa.
            //
            // La fila que participa en el morph AHORA, en tres momentos y solo en ellos (ver
            // [ContainerOriginRole]): la que se está PREPARANDO (visible, un frame, antes de que el
            // player exista); la del morph en vigor mientras el player está abierto o abriéndose
            // (oculta); y la que recibe el CIERRE mientras la capa se contrae (visible). En cualquier
            // otro caso es null y las filas no declaran nada. Sale del origen CONGELADO
            // (`morphOrigin`), el mismo que usa la capa para sus keys: las dos puntas del morph se
            // deciden en un solo sitio y no pueden discrepar ni un frame. `hidden = playerExpanded`
            // porque ese booleano cambia en el frame que arranca cada sentido: true desde el primer
            // frame de la apertura, false desde el primero del cierre.
            //
            // Va en un `State` (ver [RowOriginHost]) y no como valor suelto: así las filas lo leen
            // dentro de un `derivedStateOf` y solo recompone la que cambia de papel, en vez de las
            // diez visibles a la vez justo en el frame que arranca la transición.
            val layerInFlight = playerLayerTransition.isRunning ||
                playerLayerTransition.currentState != playerLayerTransition.targetState
            val rowOrigin = rememberUpdatedState(
                appState.pendingRowOrigin?.let { RowOrigin(it, hidden = false) }
                    ?: morphOrigin.songId
                        ?.takeIf { morphOrigin.kind == PlayerArtOrigin.ROW && (playerExpanded || layerInFlight) }
                        ?.let { RowOrigin(it, hidden = playerExpanded) }
            )
            ProvideAppSharedTransitionScope(
                scope = this@SharedTransitionLayout,
                rowOrigin = rememberRowOriginHost(rowOrigin, appState, playerLayerTransition)
            ) {
            // Box: permite montar el PlayerOverlay como capa flotante SOBRE el NavHost.
            Box(modifier = Modifier.fillMaxSize()) {
                // (Aquí vivió, del 16 al 20 ago, la paleta RETENIDA: el NavHost iba bajo un
                // `MaterialTheme` anidado que conservaba los colores viejos mientras el reproductor
                // crecía, para que el repintado de la biblioteca cayera con el player tapándola.
                // Existía porque un cambio de esquema recomponía el árbol ENTERO; desde que el color
                // viaja por [AppColors] eso ya no ocurre, así que no hay nada que esconder ni que
                // retrasar y la biblioteca se tiñe en el acto. Ver el KDoc de `AppColorScheme`.)
                CompositionLocalProvider(LocalAppColors provides underlayColors) {
                AppNavHost(
                    appState = appState,
                    startDestination = startDestination,
                    loggedIn = loggedIn,
                    accountPhotoPath = accountPhotoPath,
                    accountInitial = accountInitial,
                    authLoading = authLoading,
                    authError = authError,
                    onConnectOneDrive = { activity -> authViewModel.signIn(activity) },
                    onDisconnectOneDrive = { authViewModel.logout() },
                    onRequestSync = { syncViewModel.refreshSongs(force = false) },
                    playbackViewModel = playbackViewModel,
                    libraryViewModel = libraryViewModel,
                    sourcesViewModel = sourcesViewModel,
                    syncViewModel = syncViewModel,
                    snackbarManager = snackbarManager,
                    sharedTransitionScope = this@SharedTransitionLayout
                )
                }

                PlayerOverlay(
                    appState = appState,
                    layerTransition = playerLayerTransition,
                    morphOrigin = morphOrigin,
                    underlayColors = underlayColors,
                    playbackViewModel = playbackViewModel,
                    libraryViewModel = libraryViewModel,
                    snackbarManager = snackbarManager,
                    sharedTransitionScope = this@SharedTransitionLayout
                )

                // Host de snackbars ÚNICO de la app (sobre el NavHost y por encima del
                // PlayerOverlay flotante).
                SnackbarHost(
                    hostState = snackbarHostState,
                    snackbar = { AppSnackbar(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Mismo criterio que la píldora: barras del sistema Y perforación, que en
                        // horizontal caen en un costado.
                        .windowInsetsPadding(
                            SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        )
                        .padding(
                            // Con el rail al lado, el snackbar arranca donde acaba el rail: si no,
                            // se centraría respecto de la pantalla entera y su borde quedaría por
                            // debajo de los destinos. Es lo mismo que hace la píldora.
                            start = chromeRailInset,
                            // Con la barra de pestañas puesta, el mini se apoya sobre ELLA, así
                            // que el snackbar tiene que subir lo mismo o saldría por detrás.
                            // Misma regla que usa la píldora, no una copia de la condición.
                            bottom = chromeLift +
                                if (miniPlayerShown) ComponentConfig.FloatingBarListInset
                                else ComponentConfig.FloatingBarBottomMargin
                        )
                )
            }
            } // ProvideAppSharedTransitionScope
        }
    }
}

/**
 * Pantalla de espera cuando restaurar la sesión se alarga (ver `AuthViewModel.sessionRestoreSlow`).
 *
 * Sustituye a lo que hacía el timeout viejo: dar la sesión por inexistente y soltar al usuario en el
 * onboarding con su cuenta intacta en disco. Aquí no se decide nada — se sigue esperando y solo se
 * cuenta lo que ocurre, así que cuando MSAL responda la app continúa al destino correcto por sí
 * sola. De ahí que no haya botón: no hay ninguna acción que ofrecer que mejore la situación.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionRestoreSlowScreen() {
    AppSurface(
        modifier = Modifier.fillMaxSize(),
        color = AppColors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingIndicator(color = AppColors.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.session_restore_slow_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.session_restore_slow_body),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
