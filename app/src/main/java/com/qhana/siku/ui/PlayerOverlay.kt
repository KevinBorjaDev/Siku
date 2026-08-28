package com.qhana.siku.ui

import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.ui.theme.AppColorScheme
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.AddSongsToPlaylistSheet
import com.qhana.siku.ui.components.CallerManagedVisibilityScope
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MiniPlayer
import com.qhana.siku.ui.components.SaveLyricsDialog
import com.qhana.siku.ui.components.PlayerGestureConfig
import com.qhana.siku.ui.components.miniPlayerVerticalDrag
import com.qhana.siku.ui.components.rememberPlayerDismissState
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.theme.appFadeEnter
import com.qhana.siku.ui.theme.appFadeExit
import com.qhana.siku.ui.theme.appTextButtonColors
import android.content.res.Configuration
import com.qhana.siku.ui.screens.LibraryBottomBarPillLift
import com.qhana.siku.ui.screens.LibraryRailWidth
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import kotlin.math.roundToInt

/**
 * Capa flotante sobre el NavHost: el **`AnimatedContent` píldora ↔ reproductor** más las
 * hojas/diálogos GLOBALES que sobreviven a la navegación.
 *
 * **El reproductor a pantalla completa VIVE aquí** ([NowPlayingLayer]), pero **no como rama del
 * `AnimatedContent`: como hermano PERSISTENTE suyo**, compuesto en cuanto hay canción y oculto
 * —sin colocar— mientras está guardado. Montarlo y desmontarlo por apertura costaba 25-58 ms en el
 * frame del tap y ~25 ms al cerrar (ver el bloque que lo compone, más abajo). El estado lo gobierna
 * [MusicAppState.playerExpanded] (booleano), no la ruta.
 *
 * El `AnimatedContent` se queda con la PÍLDORA y sus TRES estados ([PlayerLayerState]): **Expanded**
 * (retirada, el player la tapa), **Collapsed** (en pantalla) y **Hidden** (nada, en rutas sin
 * reproducción o sin canción). Hidden↔Collapsed es su fundido al cambiar de ruta, y su transición es
 * además de la que cuelga `rememberPlayerMorphOrigin`.
 *
 * Collapsed↔Expanded sigue siendo el *container transform* (`sharedBounds` `PLAYER_CONTAINER_SHARED_KEY`
 * que declaran el Card de la píldora y la raíz del player), y que cada punta cuelgue ahora de una
 * `Transition` distinta no lo cambia: un shared element empareja por KEY, no por dueño de transición
 * — es lo que ya hacía el morph desde una FILA de lista, donde la punta de origen es caller-managed y
 * la del player era la del `AnimatedContent`. Aquí es el mismo par con los papeles cambiados. Lo que
 * el player era una ruta del NavHost sí rompía el morph, pero por otra cosa: cada punta vivía en un
 * `NavBackStackEntry` distinto y el `sharedBounds` ni llegaba a emparejar.
 *
 * También aquí, en un nivel estable:
 *  - Los **diálogos de guardar letra** + su `ActivityResultLauncher` (el permiso del sistema no puede
 *    colgar de una capa que se desmonta a mitad) y el consentimiento de escritura en nube.
 *  - La hoja **"añadir canciones"** del detalle de playlist/Favoritos.
 *
 * Receptor [BoxScope]: se monta en el Box raíz, sobre el NavHost.
 */
/**
 * z de la rama que ENTRA en el container transform de la capa, por encima de la que sale (que queda a
 * 0, el default). Lo pide la propia API: `KeepUntilTransitionsFinished` retiene la rama saliente y, si
 * el sentido se invierte a mitad (abrir y cerrar seguido, la rama reutilizada), sin z explícita la
 * saliente puede pintarse ENCIMA de la entrante. Ordinal, no medida: solo importa que sea mayor que 0.
 */
private const val CONTAINER_TARGET_BRANCH_Z = 1f

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalTransitionApi::class)
@Composable
fun BoxScope.PlayerOverlay(
    appState: MusicAppState,
    /**
     * Transición de la capa (Hidden/Collapsed/Expanded), creada por `MusicPlayerScreen` con
     * `updateTransition`. Se recibe hecha porque de ella se deriva también el origen congelado del
     * morph ([morphOrigin]), que consumen las filas del NavHost — hermanas de esta capa, no hijas.
     */
    layerTransition: Transition<PlayerLayerState>,
    /**
     * De dónde sale / a dónde vuelve el reproductor, CONGELADO mientras [layerTransition] corre. Es la
     * ÚNICA fuente para las keys de los shared elements de la capa y para los gates que dependen del
     * origen; `appState.playerArtOrigin` es la petición y no se lee aquí — ver [rememberPlayerMorphOrigin].
     */
    morphOrigin: PlayerMorphOrigin,
    /**
     * Colores de lo que queda DEBAJO del reproductor (ver [rememberUnderlayAppColors]). La PÍLDORA
     * se pinta con ellos: al abrir desde una fila con otra carátula, la barra que se está
     * desvaneciendo no debe cambiar de color a mitad del fundido — pertenece al mismo mundo que la
     * biblioteca de detrás, no al player que crece.
     */
    underlayColors: AppColorScheme,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope
) {
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val playerGestures by playbackViewModel.playerGestures.collectAsStateWithLifecycle()
    val miniPlayerRoundedRect by playbackViewModel.miniPlayerRoundedRect.collectAsStateWithLifecycle()
    val miniPlayerRoundPlayButton by playbackViewModel.miniPlayerRoundPlayButton.collectAsStateWithLifecycle()
    val lyricsSaveState by playbackViewModel.lyricsSaveState.collectAsStateWithLifecycle()
    // Vista de UN campo, no el `uiState` entero: esta capa es persistente y con la raíz recomponía
    // en cada tecla de la búsqueda de la biblioteca. Ver el bloque de vistas en `LibraryViewModel`.
    val favorites by libraryViewModel.favorites.collectAsStateWithLifecycle()

    val navBackStackEntry = appState.currentBackStackEntry
    val currentRoute = navBackStackEntry?.destination?.route
    val onPlaylistDetailRoute = currentRoute == Screen.PlaylistDetail.route
    val onFavoritesRoute = currentRoute == Screen.Favorites.route
    // Rutas de lista donde aplica la hoja de "añadir canciones" (botón en el detalle).
    val onAddSongsRoute = onPlaylistDetailRoute || onFavoritesRoute

    // --- CROMO DE LA BIBLIOTECA: la píldora se aparta de la barra de pestañas o del rail ---
    //
    // La condición NO se calcula aquí: sale de `libraryChrome`, la misma función que consume
    // `LibraryScreen` para decidir qué dibuja. Dos copias serían dos verdades, y con la píldora
    // en una capa hermana del NavHost el desacuerdo se vería (flotando sobre la barra, o debajo).
    val libraryBottomTabs by playbackViewModel.libraryBottomTabs.collectAsStateWithLifecycle()
    val chrome = libraryChrome(
        route = currentRoute,
        bottomTabs = libraryBottomTabs,
        landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    )
    val chromeRailInset = if (chrome == LibraryChrome.Rail) LibraryRailWidth else 0.dp
    val chromeLiftTarget = if (chrome == LibraryChrome.BottomBar) LibraryBottomBarPillLift else 0.dp
    // `Animatable` y no `animateDpAsState` por el caso raro pero visible: si se abre el
    // reproductor y desde ahí se navega a un detalle, el objetivo cambia con la píldora oculta y
    // la animación correría a ciegas para verse ya empezada al volver. Con la píldora fuera de
    // pantalla se salta al valor final.
    val chromeLift = remember { Animatable(chromeLiftTarget, Dp.VectorConverter) }
    val chromeLiftSpec = appSpatialSpec<Dp>()
    val pillOnScreen = layerTransition.currentState == PlayerLayerState.Collapsed
    LaunchedEffect(chromeLiftTarget, pillOnScreen) {
        if (pillOnScreen) chromeLift.animateTo(chromeLiftTarget, chromeLiftSpec)
        else chromeLift.snapTo(chromeLiftTarget)
    }
    // **`Modifier.layout`, NO `graphicsLayer { translationY }`.** La píldora es una punta de shared
    // element (`PLAYER_CONTAINER_SHARED_KEY`) y sus bounds se calculan con las coordenadas de
    // LOOKAHEAD, pasada en la que los layers de dibujo no se aplican: con `graphicsLayer` el morph
    // arrancaría desde donde la píldora NO está y saltaría estos 56dp en el primer frame. Un
    // `LayoutModifierNode` sí corre en las dos pasadas. El valor se lee DENTRO del bloque de
    // colocación, así que cada frame invalida placement y nada más — ni recomposición ni medida.
    val chromeShift = remember(chromeLift) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val density = this
            layout(placeable.width, placeable.height) {
                placeable.place(0, with(density) { -chromeLift.value.roundToPx() })
            }
        }
    }


    // Si la ruta deja de ser un detalle de lista (back, navegación), la hoja muere con ella.
    LaunchedEffect(onAddSongsRoute) {
        if (!onAddSongsRoute) appState.showAddSongsSheet = false
    }

    // --- Guardar la letra en el archivo -------------------------------------------------------
    //
    // Vive aquí, en un nivel estable, y NO dentro de [NowPlayingLayer]: si colgara de la rama Expanded
    // del `AnimatedContent`, colapsar el reproductor a mitad del permiso del sistema desmontaría el
    // launcher y el guardado quedaría a medias sin que nadie lo cancelara.

    // MediaStore en API 30+: solo la Activity puede lanzar el IntentSender del sistema.
    val systemWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) playbackViewModel.retryPendingSave()
        else playbackViewModel.cancelPendingSave()
    }
    LaunchedEffect(lyricsSaveState.pendingPermission) {
        lyricsSaveState.pendingPermission?.let { sender ->
            systemWriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            playbackViewModel.consumePendingPermission()
        }
    }

    lyricsSaveState.options?.let { options ->
        SaveLyricsDialog(
            options = options,
            onDismiss = { playbackViewModel.dismissSaveLyricsDialog() },
            onConfirm = { mode, remember -> playbackViewModel.confirmSaveLyrics(mode, remember) }
        )
    }

    // El consentimiento de escritura sobre OneDrive se explica ANTES de lanzar la pantalla de
    // Microsoft: es un permiso sobre todo el drive y aparecer de la nada asusta con razón.
    if (lyricsSaveState.needsCloudConsent) {
        val activity = LocalActivity.current
        AlertDialog(
            onDismissRequest = { playbackViewModel.cancelPendingSave() },
            title = { Text(stringResource(R.string.lyrics_save_consent_title)) },
            text = { Text(stringResource(R.string.lyrics_save_consent_message)) },
            confirmButton = {
                TextButton(
                    colors = appTextButtonColors(),
                    onClick = { activity?.let { playbackViewModel.grantCloudWriteConsent(it) } },
                    enabled = activity != null
                ) { Text(stringResource(R.string.lyrics_save_consent_confirm)) }
            },
            dismissButton = {
                TextButton(colors = appTextButtonColors(), onClick = { playbackViewModel.cancelPendingSave() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // --- La CAPA de la PÍLDORA -----------------------------------------------------------------
    //
    // `AnimatedContent` de tres estados que gobierna la barra: en Collapsed está en pantalla, en
    // Expanded se retira (es la punta de ORIGEN del container transform y tiene que seguir compuesta
    // mientras el morph corre: eso lo sostiene `KeepUntilTransitionsFinished`, ver el `transitionSpec`
    // — sin ella la rama moriría en el acto y el shared element se quedaría sin punta; con un fade a
    // pantalla completa costaba un `saveLayer` por frame), y en Hidden se funde con `appFadeEnter/Exit`
    // al cambiar de ruta. La otra punta del morph es el reproductor persistente que se compone
    // DESPUÉS de este bloque. Las áreas VACÍAS de la capa no llevan `pointerInput` ni fondo, así que
    // los toques pasan al NavHost de abajo (solo la píldora y el player expandido interceptan).
    //
    // La `Transition` la POSEE `MusicPlayerScreen` (`updateTransition` sobre el estado de la capa) y
    // aquí solo se recorre: es la misma de la que se deriva el origen CONGELADO del morph, así que
    // "qué fila se oculta", "con qué key declara el player" y "qué rama entra/sale" salen de un único
    // estado, en el mismo frame.
    layerTransition.AnimatedContent(
        transitionSpec = {
            // Collapsed↔Expanded = container transform: el que ENTRA es SÓLIDO (la superficie del
            // player nunca se ve transparente, solo crece), el que SALE se disuelve encima y vive
            // hasta que el bounds asienta. Hidden↔Collapsed = fundido normal de la píldora al cambiar
            // de ruta.
            //
            // **Con la condición del ORIGEN**: el `EnterTransition.None` solo es correcto cuando hay una
            // superficie de la que crecer (píldora o fila). Abriendo desde un chip del inicio o desde
            // la notificación no hay ninguna, así que el player aparecía DE GOLPE — sin morph y sin
            // fundido, que es el único caso en que "sólido" no significa nada.
            val hasOriginSurface = morphOrigin.kind != PlayerArtOrigin.NONE
            val containerMorph = hasOriginSurface && (
                (initialState == PlayerLayerState.Collapsed && targetState == PlayerLayerState.Expanded) ||
                (initialState == PlayerLayerState.Expanded && targetState == PlayerLayerState.Collapsed)
            )
            // La rama saliente NO se funde: `KeepUntilTransitionsFinished` la mantiene viva hasta que
            // TODAS las transiciones del AnimatedContent terminan —el bounds del `sharedBounds`
            // incluido—, o sea exactamente lo que dura el morph, por construcción y sin ninguna
            // duración elegida a mano. Antes era un `fadeOut` de la rama, y eso costaba caro donde no
            // se veía: la rama es una Box a pantalla completa, y un alfa sobre ella obliga a HWUI a un
            // `saveLayer` offscreen de la pantalla ENTERA en cada frame del morph (medido con atrace:
            // "alpha caused saveLayer 1080x2400" en cada frame del cierre) — el RenderThread pasaba de
            // 2-3 a 5-8 ms/frame y el scroll que arrancaba en esa cola perdía frames. Lo que SÍ se funde
            // es el CONTENIDO de la superficie que morfa (`appContainerContentExitFast` en la píldora, `appContainerSurfaceExitSpec` en el player), que es lo que se
            // ve. Ver el KDoc de la función sobre `targetContentZIndex`.
            JankProbe.note { "AnimatedContent spec: $initialState→$targetState morph=$containerMorph origen=${morphOrigin.kind}" }
            if (containerMorph) {
                (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished)
                    .apply { targetContentZIndex = CONTAINER_TARGET_BRANCH_Z }
            } else appFadeEnter() togetherWith appFadeExit()
        },
        modifier = Modifier.fillMaxSize()
    ) { state ->
        // El scope de ESTE AnimatedContent es el `animatedVisibilityScope` de las dos puntas del
        // shared element; se captura antes de entrar en Box/Column (que lo sombrearían con su receptor).
        val layerScope = this
        // Sonda: vida de cada rama del AnimatedContent (compuesta/descompuesta) y su transición.
        DisposableEffect(state) {
            JankProbe.note { "rama $state COMPUESTA" }
            onDispose { JankProbe.note { "rama $state DESCOMPUESTA" } }
        }
        SideEffect {
            JankProbe.note {
                "rama $state: ${layerScope.transition.currentState}→${layerScope.transition.targetState}"
            }
        }
        when (state) {
            // El reproductor NO vive en esta rama: es un hermano PERSISTENTE de este
            // `AnimatedContent` (ver más abajo). Aquí queda un Box vacío porque el estado Expanded
            // sigue haciendo falta para lo otro que gobierna la capa: que la píldora se retire.
            //
            // (Aquí hubo, del 16 al 17 ago, la punta player de un shared element solo-sombra con
            // `RemeasureToBounds`. La sombra de la píldora ya no viaja — ver `pillShadow` en
            // MiniPlayer.kt: al arrancar el cierre pintaba un blur de pantalla entera por frame.)
            PlayerLayerState.Expanded -> Box(Modifier.fillMaxSize())

            PlayerLayerState.Collapsed -> CompositionLocalProvider(LocalAppColors provides underlayColors) { Box(Modifier.fillMaxSize()) {
                // La VISIBILIDAD la decide `currentSong` (solo null sin sesión o tras stop()). Los DATOS
                // salen de `nowPlayingUiState.song` (fila de Room, con carátula/colores/letras/descarga
                // al día), cayendo a `currentSong` mientras el id no coincide (instante del cambio).
                val song = currentSong?.let { current ->
                    nowPlayingUiState.song?.takeIf { it.id == current.id } ?: current
                }
                if (song != null) {
                    // Arrastrar la píldora hacia ABAJO detiene la reproducción (con deshacer, ver
                    // `stopPlayback`). La distancia de salida es lo que le falta a la barra para
                    // salir de la pantalla —su alto, más lo que flota sobre el borde— y no un
                    // número: sin ese tramo la píldora se esfumaría a mitad del gesto, porque parar
                    // anula `currentSong` y su rama se descompone en el acto.
                    val miniDismiss = rememberPlayerDismissState(
                        threshold = PlayerGestureConfig.MiniDismissThreshold,
                        exitDistance = ComponentConfig.MiniPlayerHeight +
                            ComponentConfig.FloatingBarBottomMargin +
                            WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
                    ) { playbackViewModel.stopPlayback() }
                    // Traslación por LAYOUT y no por `graphicsLayer`, exactamente por lo mismo que
                    // [chromeShift] unos bloques más arriba: la píldora es punta de shared element y
                    // sus bounds salen de la pasada de LOOKAHEAD, donde los layers de dibujo no se
                    // aplican. Así, arrastrarla hacia abajo y abrir el reproductor desde ahí arranca
                    // el morph DONDE SE VE la barra, y no donde estaría en reposo. El valor se lee
                    // dentro del bloque de colocación: cada frame del arrastre invalida placement y
                    // nada más.
                    val miniDismissShift = remember(miniDismiss) {
                        Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                placeable.place(0, miniDismiss.offsetY.roundToInt())
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Barras del sistema Y perforación: en horizontal las dos caen en un
                            // COSTADO, que es justo por donde la píldora llega al borde. Sin esto
                            // el mini se metía bajo el notch al girar el teléfono.
                            .windowInsetsPadding(
                                SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                            )
                            .padding(start = chromeRailInset)
                            .then(chromeShift)
                            .padding(top = 10.dp, bottom = ComponentConfig.FloatingBarBottomMargin)
                    ) {
                        MiniPlayer(
                            song = song,
                            isPlaying = playbackState == PlaybackState.PLAYING,
                            isBuffering = playbackState == PlaybackState.BUFFERING,
                            roundedRect = miniPlayerRoundedRect,
                            roundPlayButton = miniPlayerRoundPlayButton,
                            onPlayPause = { playbackViewModel.playPause() },
                            onNextClick = { playbackViewModel.next() },
                            // La carátula ya está en pantalla y viaja al player (container transform).
                            onClick = { appState.openPlayer(PlayerArtOrigin.PILL) },
                            // Como FLOWS: el tick de posición repinta el progreso sin recomponer el mini.
                            currentPositionFlow = playbackViewModel.currentPosition,
                            durationFlow = playbackViewModel.duration,
                            // Solo con origen PÍLDORA hay alguien esperando la portada al otro lado; ver
                            // el KDoc del parámetro. Desde una fila o un chip el mini se limita a
                            // desvanecerse con la suya puesta.
                            artTravelsToPlayer = morphOrigin.kind == PlayerArtOrigin.PILL,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = layerScope,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ComponentConfig.FloatingBarSideMargin)
                                // La barra ENTERA sigue al dedo mientras baja —sombra incluida, por
                                // eso el gesto va acá afuera y no dentro del mini— y se atenúa con
                                // el mismo criterio que el reproductor al cerrarse. En reposo la
                                // alpha es 1 exacta, así que esta capa no pide buffer offscreen.
                                .then(miniDismissShift)
                                .graphicsLayer {
                                    alpha = 1f - (1f - PlayerGestureConfig.DismissMinAlpha) *
                                        miniDismiss.progress
                                }
                                // Arriba abre el reproductor, abajo lo para (gestos inversos a los
                                // del NowPlaying, en un solo detector: ver `miniPlayerVerticalDrag`).
                                .miniPlayerVerticalDrag(playerGestures, miniDismiss) {
                                    appState.openPlayer(PlayerArtOrigin.PILL)
                                }
                        )
                    }
                }
            } }

            // Sin píldora ni player: un Box vacío sin fondo → deja pasar los toques al NavHost.
            PlayerLayerState.Hidden -> Box(Modifier.fillMaxSize())
        }
    }

    // --- El REPRODUCTOR: hermano PERSISTENTE de la capa, no una rama suya --------------------
    //
    // Se compone UNA vez —en cuanto hay canción— y se queda compuesto mientras la haya. Antes vivía
    // en la rama Expanded del `AnimatedContent` de arriba, así que nacía y moría con cada apertura:
    // medido con `JankProbe` el 17 ago, construir su árbol costaba **25-58 ms en el frame del tap**
    // (el que arranca el morph, o sea el peor sitio posible: los specs de Compose avanzan por tiempo
    // y el container transform daba su primer paso ya avanzado) y desmontarlo otros ~25 ms al
    // asentar el cierre. Ninguno de los dos compra nada: el reproductor de una app de música se abre
    // y se cierra decenas de veces por sesión y su árbol es el mismo siempre.
    //
    // Lo que hace posible sacarlo de ahí es que `sharedBounds` no necesita un `AnimatedVisibility`
    // sino solo una `Transition<EnterExitState>` que le diga si esta punta es el destino y con qué
    // enter/exit funde su contenido ([CallerManagedVisibilityScope]). Es EXACTAMENTE la técnica que
    // ya usan las filas desde el 16 ago (`ContainerTransformOrigin`), aplicada a la otra punta: la
    // vida del subárbol deja de depender de la animación.
    //
    // La píldora se queda donde estaba (en el `AnimatedContent`), y la visibilidad del reproductor es
    // una **transición HIJA de la de la capa** en vez de una suelta sobre `playerExpanded`. Los dos
    // valores son el mismo booleano (`playerLayer == Expanded` ⟺ `playerExpanded`, por construcción en
    // `MusicPlayerScreen`), así que no es por conveniencia: **es lo que mantiene el morph dentro de UNA
    // sola `Transition`**. Un `updateTransition` independiente dejaría la animación de bounds del
    // reproductor fuera del árbol de la capa, y de ese árbol dependen tres cosas ya calibradas:
    // `rememberPlayerMorphOrigin` (congela el origen mientras `isRunning`), la retención de paleta
    // (retiene la paleta hasta que asienta) y el `KeepUntilTransitionsFinished` de la píldora, que la
    // sostiene como punta de origen exactamente lo que el bounds tarda. Sueltas, la capa asentaría con
    // el fundido de contenido (300 ms) y soltaría la píldora ~30 ms antes de que la superficie
    // aterrizara — o sea quitarle la pareja al shared element en el último tramo.
    val playerVisibility = layerTransition.createChildTransition(label = "playerVisibility") {
        if (it == PlayerLayerState.Expanded) EnterExitState.Visible else EnterExitState.PostExit
    }
    val playerScope = remember(playerVisibility) { CallerManagedVisibilityScope(playerVisibility) }

    // "EN PANTALLA" = la capa **está en `Expanded`, va hacia él o viene de él**. Y se pregunta por el
    // ESTADO, nunca por "¿hay una transición corriendo?" — ése fue el bug que se vio al reinstalar
    // (17 ago 12:02): la capa transiciona también en `Hidden↔Collapsed`, que es solo la píldora
    // fundiéndose al arrancar la app o al entrar y salir de Ajustes. Con la condición puesta en
    // `isRunning`, esos 500 ms colocaban el reproductor a pantalla completa; y como en ese momento el
    // origen del morph es `NONE`, no hay `sharedBounds` ni `surfaceFactor` que lo atenúen, así que
    // salía OPACO y encima de todo. Síntoma: la app arranca enseñando el NowPlaying, como si se
    // hubiera dejado abierto.
    //
    // `currentState` cubre el cierre entero por construcción: una `Transition` no adopta su
    // `targetState` hasta que TODAS sus animaciones y las de sus hijas terminan —el bounds de la
    // píldora incluido, que es la que lo corre al cerrar—, así que sigue valiendo `Expanded` hasta que
    // la superficie aterriza. Por eso basta con la transición de la capa y no hace falta mirar
    // además la del reproductor, que asienta unos ms antes con su fundido de contenido.
    val playerOnScreen = layerTransition.targetState == PlayerLayerState.Expanded ||
        layerTransition.currentState == PlayerLayerState.Expanded

    // Guardado = **no se COLOCA**, que es lo que de verdad lo saca de la pantalla: un nodo sin
    // colocar no se dibuja y no entra en el hit test, así que ni cuesta un `saveLayer` de pantalla
    // completa por frame ni se traga los toques que van a la píldora de debajo (un `alpha = 0f` no
    // hace ninguna de las dos cosas: en Compose la opacidad no afecta al reparto de punteros). Lo
    // que SÍ se conserva es la composición y la MEDIDA, que es justo lo que se quería ahorrar.
    //
    // Se lee DIFERIDO, dentro del bloque de colocación: así abrir y cerrar invalidan el layout de
    // este subárbol y nada más — leerlo en composición volvería a construir el modifier (y a
    // re-medir el reproductor entero) en cada recomposición de esta capa, que son muchas.
    //
    // **Lo que la permanencia SÍ cuesta**: la carátula grande del reproductor (800 px) se pide en
    // cuanto el nodo se MIDE, que ocurra o no la colocación, así que ahora se decodifica en cada
    // cambio de canción aunque nadie lo abra. Es el precio de tenerla ya lista en el frame del tap —
    // que es medio motivo de este refactor— y se paga en el hilo de Coil, no en el de UI. Se anota
    // porque es el punto por el que empezar si algún día hay que revisar la memoria de imágenes.
    val onScreenState = rememberUpdatedState(playerOnScreen)
    val placementGate = remember(onScreenState) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (onScreenState.value) placeable.place(0, 0)
            }
        }
    }

    // Se compone SIEMPRE, también sin canción, y eso no es descuido: sin canción [NowPlayingLayer]
    // sale por su rama corta (unos colectores y un Box liso — `NowPlayingScreen`, que es lo caro, ni
    // se toca) y es justo esa rama la que se encarga de CERRAR el reproductor cuando la cola se vacía
    // (`closePlayerIfEmpty`). Condicionarlo a `currentSong != null` lo dejaría atascado: al vaciar la
    // cola con el reproductor abierto, la capa desaparecería con `playerExpanded` todavía en `true`,
    // o sea sin reproductor, sin píldora y sin nadie que apagara la bandera.
    NowPlayingLayer(
        appState = appState,
        onScreen = playerOnScreen,
        morphOrigin = morphOrigin,
        playbackViewModel = playbackViewModel,
        libraryViewModel = libraryViewModel,
        snackbarManager = snackbarManager,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = playerScope,
        modifier = Modifier
            .fillMaxSize()
            .then(placementGate)
    )

    // --- Hoja "añadir canciones" (estado en MusicAppState) ------------------------------------
    // Vive aquí, y no en la pantalla que la abre, porque su estado tiene que sobrevivir a la
    // navegación: la dispara un botón del detalle, pero se pinta sobre toda la app.
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

        // Mismo motivo que en la hoja de la cola: sus filas repetirían la key por-canción de las
        // filas de la lista que hay detrás.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            AddSongsToPlaylistSheet(
                playlistName = sheetPlaylistName,
                candidates = candidates,
                query = pickerQuery,
                // Con búsqueda activa y cero resultados el mensaje debe ser "ningún resultado".
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
}
