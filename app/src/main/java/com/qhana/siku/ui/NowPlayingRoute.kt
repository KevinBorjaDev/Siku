package com.qhana.siku.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.state.NowPlayingUiState
import com.qhana.siku.ui.components.EqualizerSheet
import com.qhana.siku.ui.components.PLAYER_ART_SHARED_KEY
import com.qhana.siku.ui.components.SheetOverlay
import com.qhana.siku.ui.components.PLAYER_CONTAINER_SHARED_KEY
import com.qhana.siku.ui.components.playerContainerSharedBounds
import com.qhana.siku.ui.components.rowArtSharedKey
import com.qhana.siku.ui.components.rowContainerSharedKey
import com.qhana.siku.ui.screens.AmbientPlayerActivity
import com.qhana.siku.ui.screens.NavigationActions
import com.qhana.siku.ui.screens.NowPlayingScreen
import com.qhana.siku.ui.screens.PlayerActions
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import com.qhana.siku.ui.theme.EXPRESSIVE_SLOW_EFFECTS_MS
import com.qhana.siku.ui.theme.SHARED_AXIS_Z_NEAR_SCALE
import com.qhana.siku.ui.theme.ScreenSlideEasing
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel

/**
 * El NowPlaying a pantalla completa: el hermano **PERSISTENTE** del `AnimatedContent` de
 * [PlayerOverlay]. Se compone una sola vez —en cuanto hay canción— y se queda compuesto; lo que
 * cambia al abrir y cerrar es [onScreen], no su vida.
 *
 * El player NO es una ruta del NavHost: es una capa que hace *container transform* con la píldora
 * (ver [MusicAppState.playerExpanded]). El `animatedVisibilityScope` que recibe es un
 * `CallerManagedVisibilityScope` cuya `Transition` gobierna [PlayerOverlay] desde `playerExpanded`:
 * `sharedBounds` solo necesita eso para saber si esta punta es el destino y con qué enter/exit funde
 * su contenido. El `sharedTransitionScope` sigue eligiendo la key según el origen CONGELADO del morph
 * ([PlayerMorphOrigin]: píldora / fila = container transform con su carátula anidada; ninguna = fundido).
 *
 * "Atrás" (botón o gesto) llama a [MusicAppState.collapsePlayer]; navegar a un artista/álbum lo
 * colapsa recordando volver ([MusicAppState.navigateToArtist] → `navigateFromPlayer`).
 *
 * La hoja del ECUALIZADOR vive aquí (se abre desde el toolbar del player). Los diálogos de guardar
 * letra y su `ActivityResultLauncher` NO: siguen en [PlayerOverlay], un nivel estable — un launcher
 * dentro de una capa que se desmonta a mitad del permiso del sistema se destruiría.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingLayer(
    appState: MusicAppState,
    /**
     * ¿Está EN PANTALLA? `true` con el reproductor abierto y durante todo el morph; `false` mientras
     * está guardado, que es cuando [PlayerOverlay] deja de COLOCAR este subárbol.
     *
     * Se publica en [LocalPlayerOnScreen] para los relojes de dentro (ver su KDoc) y gobierna aquí lo
     * que no puede sobrevivir a un cierre: las hojas abiertas se cierran y el `BackHandler` de
     * colapsar se apaga. Lo segundo NO es cosmético — un `BackHandler` habilitado en un subárbol que
     * ya no se ve interceptaría el "atrás" de la biblioteca.
     */
    onScreen: Boolean,
    /**
     * De qué superficie nace (o a cuál vuelve) esta capa, CONGELADO mientras su transición corre.
     * De aquí, y de nada más, salen las keys de sus shared elements: leerlas de `currentSong` o de
     * `appState.playerArtOrigin` en vivo las cambiaba a media transición cuando `AnimatedContent`
     * reutilizaba esta misma instancia (retocar una fila antes de que el cierre asentara) y dejaba la
     * fila anterior varada en el overlay — ver [rememberPlayerMorphOrigin].
     */
    morphOrigin: PlayerMorphOrigin,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    // Sonda (solo debug): primera composición de la capa y cada recomposición de este scope. Con el
    // reproductor persistente "COMPUESTA (1ª vez)" tiene que aparecer UNA sola vez en toda la sesión
    // —no una por apertura—: es la forma de comprobar que la permanencia sigue en pie.
    val firstComposition = remember { booleanArrayOf(true) }
    SideEffect {
        JankProbe.mark { if (firstComposition[0]) "NowPlayingLayer COMPUESTA (1ª vez)" else "NowPlayingLayer recompuesta" }
        firstComposition[0] = false
    }

    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val nowPlayingBackground by playbackViewModel.nowPlayingBackground.collectAsStateWithLifecycle()
    val nowPlayingWavyProgress by playbackViewModel.nowPlayingWavyProgress.collectAsStateWithLifecycle()
    val nowPlayingProgressThickness by playbackViewModel.nowPlayingProgressThickness.collectAsStateWithLifecycle()
    val nowPlayingProgressHandle by playbackViewModel.nowPlayingProgressHandle.collectAsStateWithLifecycle()
    val nowPlayingDetailedFormat by playbackViewModel.nowPlayingDetailedFormat.collectAsStateWithLifecycle()
    val playerGestures by playbackViewModel.playerGestures.collectAsStateWithLifecycle()
    val lyricsSaveState by playbackViewModel.lyricsSaveState.collectAsStateWithLifecycle()
    // Vistas de UN campo, no el `uiState` entero. Este árbol es PERSISTENTE (ver [onScreen]), así
    // que con la raíz recomponía por cada tecla de la búsqueda de la biblioteca y por cada tick del
    // banner de sync — con el reproductor guardado y sin nada que mostrar. Ver el bloque de vistas
    // en `LibraryViewModel`.
    val favorites by libraryViewModel.favorites.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val useSystemEq by playbackViewModel.useSystemEq.collectAsStateWithLifecycle()

    var showEqualizerSheet by remember { mutableStateOf(false) }
    // La hoja del ecualizador tapa el reproductor del todo (lo publica [SheetOverlay] al asentar la
    // animación). Baja a `NowPlayingScreen` como `obscured`: es quien apaga sus relojes y deja de
    // colocar su layout mientras no se ve.
    var equalizerCovering by remember { mutableStateOf(false) }

    // Sonda (solo con la propiedad de sistema): QUÉ cambió cada vez que esta capa recompone — para
    // separar las fuentes de las recomposiciones del NowPlaying en la ventana del morph.
    //
    // **Va DENTRO de `if (JankProbe.isEnabled)` y no en tres `LaunchedEffect` sueltos**, que es como
    // estaba. `JankProbe` está construida para que apagada cueste un `if`: sus métodos son `inline`
    // y salen antes de construir el string. Un `LaunchedEffect` con clave no respeta ese contrato —
    // cancela y relanza una corrutina cada vez que la clave cambia, con la sonda encendida o no—, y
    // aquí eso ocurría en cada canción, cada play/pause y cada emisión del `uiState`, en el árbol
    // PERSISTENTE del reproductor, que no se descompone nunca.
    //
    // Con el gate delante, apagada no se registra ni un efecto; encendida los tres siguen marcando
    // lo mismo. Que la condición sea segura en composición lo explica el KDoc de `isEnabled`.
    if (JankProbe.isEnabled) {
        LaunchedEffect(nowPlayingUiState) {
            JankProbe.mark { "uiState cambió: song=${nowPlayingUiState.song?.title?.take(12)} down=${nowPlayingUiState.isDownloaded} colors=${nowPlayingUiState.albumColors != null} lyrics=${nowPlayingUiState.lyrics != null}" }
        }
        LaunchedEffect(playbackState) { JankProbe.mark { "playbackState(UI)=$playbackState" } }
        LaunchedEffect(currentSong) { JankProbe.mark { "currentSong instancia nueva" } }
    }

    // Ecualizador del sistema (MIUI primero, panel estándar como fallback). Feedback de fallo por
    // TOAST y no por el SnackbarManager: se invoca también desde el botón dentro de EqualizerSheet
    // (ModalBottomSheet = ventana propia) y el snackbar del host central quedaría tapado.
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

    // A qué punta se engancha el reproductor. La píldora y las filas usan familias de key
    // DISTINTAS a propósito (constante vs. id), porque las dos tienen que estar declaradas de ANTES
    // para servir de origen sin pisarse; elegir la key aquí decide cuál recibe la superficie.
    //
    // Sale de `morphOrigin` —el origen CONGELADO durante la transición— y NUNCA de `currentSong` en
    // vivo: la key de una punta no puede cambiar mientras su transición corre (deja a la otra punta
    // varada en el overlay). Cuando el usuario cambia de canción con el player abierto y asentado la
    // key NO lo sigue; se actualiza al arrancar el cierre, que es cuando importa (la superficie
    // aterriza en la fila del tema que suena entonces). Ver [rememberPlayerMorphOrigin].
    //
    // DOS PATRONES DISTINTOS, según de dónde nazca el reproductor:
    //
    //  · PÍLDORA → **container transform** ([PLAYER_CONTAINER_SHARED_KEY]) MÁS la portada compartida
    //    ([PLAYER_ART_SHARED_KEY]): la superficie de la barra crece hasta ser el fondo de esta pantalla
    //    y la portada viaja por encima. El resto del contenido sí se INTERCAMBIA con un cruce de
    //    opacidad, como manda el patrón.
    //  · FILA → **el MISMO container transform** con otra punta de origen ([rowContainerSharedKey] +
    //    [rowArtSharedKey]): la superficie de la fila crece hasta ser esta pantalla, con su portada
    //    viajando anidada. Es literalmente la misma coreografía —lo único que cambia es de dónde sale—,
    //    por eso comparte configuración y no tiene una propia.
    //  · CHIP/NOTIFICACIÓN → nada que compartir; la pantalla entra por su cuenta (fundido).
    //
    // Las dos claves son de FAMILIAS distintas, y la de fila lleva además el id: la píldora es única,
    // pero las filas visibles declaran su punta SIEMPRE —una que nace en el frame del tap no tiene
    // bounds y no hay match— así que sin el id habría diez peleándose por la misma.
    // **Y solo mientras el reproductor está EN PANTALLA.** Que la composición sea persistente no
    // significa que sus puntas lo sean: una punta declarada en reposo emparejaría con la de la
    // PÍLDORA —que está siempre puesta y siempre visible— y el par quedaría con match permanente, o
    // sea la barra dibujándose en el overlay del `SharedTransitionScope` para siempre y con nada que
    // animar. Es la misma razón por la que una fila no declara la suya salvo cuando le toca el papel
    // (ver `ContainerTransformOrigin`), y deja el ciclo de vida de las puntas EXACTAMENTE como estaba
    // antes del reproductor persistente: nacen al arrancar el morph y mueren al asentar.
    //
    // No choca con la regla del 30 jul ("una punta solo sirve de ORIGEN si ya estaba compuesta y
    // colocada"): abriendo, esta punta es el DESTINO —el origen es la píldora o la fila, puestas de
    // antes—, y cerrando, cuando sí es el origen, el reproductor lleva rato en pantalla.
    val containerSharedKey: Any? = if (!onScreen) null else when (morphOrigin.kind) {
        PlayerArtOrigin.PILL -> PLAYER_CONTAINER_SHARED_KEY
        PlayerArtOrigin.ROW -> morphOrigin.songId?.let { rowContainerSharedKey(it) }
        PlayerArtOrigin.NONE -> null
    }
    val artSharedKey: Any? = if (!onScreen) null else when (morphOrigin.kind) {
        PlayerArtOrigin.PILL -> PLAYER_ART_SHARED_KEY
        PlayerArtOrigin.ROW -> morphOrigin.songId?.let { rowArtSharedKey(it) }
        PlayerArtOrigin.NONE -> null
    }


    // Memoizadas: construidas inline, cada recomposición (posición 1/s, letras, descargas) crearía
    // lambdas nuevas y recompondría NowPlayingScreen entero.
    val playerActions = remember(playbackViewModel, libraryViewModel, context, currentSong?.id, useSystemEq) {
        PlayerActions(
            onPlayPause = { playbackViewModel.playPause() },
            onNext = { playbackViewModel.next() },
            onPrevious = { playbackViewModel.previous() },
            onSeek = { playbackViewModel.seekTo(it) },
            onSeekBy = { playbackViewModel.seekBy(it) },
            onShuffleToggle = { playbackViewModel.toggleShuffle() },
            onRepeatToggle = { playbackViewModel.toggleRepeatMode() },
            onSkipToIndex = { playbackViewModel.skipToIndex(it) },
            onReorder = { from, to -> playbackViewModel.reorderQueue(from, to) },
            onRemoveFromQueue = { playbackViewModel.removeFromQueue(it) },
            onSaveQueueAsPlaylist = { playbackViewModel.saveQueueAsPlaylist(it) },
            // Vaciar la cola CIERRA el reproductor en el mismo gesto y en la misma vuelta que el
            // `stop` (que anula la canción de forma síncrona): así la capa ve "sin canción" y "sin
            // expandir" en UNA composición y sale con una sola transición hacia `Hidden`, en vez de
            // reaccionar un frame después a la canción nula desde el `LaunchedEffect` de arriba.
            // Con la cola vacía no hay nada que mostrar ni píldora en la que aterrizar: lo que
            // corresponde es que el reproductor se vaya, no que se quede esperando.
            onClearQueue = {
                playbackViewModel.clearQueue()
                appState.closePlayerIfEmpty()
            },
            onToggleFavorite = { currentSong?.let { libraryViewModel.toggleFavorite(it.id) } },
            onToggleDownload = { playbackViewModel.toggleDownload() },
            onToggleKeepScreenOn = { playbackViewModel.toggleKeepScreenOn() },
            onOpenEqualizer = {
                if (useSystemEq) openSystemEqualizer() else showEqualizerSheet = true
            },
            onFetchLyrics = { force -> playbackViewModel.fetchLyrics(force) },
            onSearchLyricsManually = { playbackViewModel.searchLyricsCandidates() },
            onShiftLyrics = { playbackViewModel.shiftLyrics(it) },
            onFlushLyricsShift = { playbackViewModel.flushLyricsShift() },
            onSaveLyrics = { playbackViewModel.requestSaveLyrics() },
            onSelectLyricsCandidate = { candidate ->
                playbackViewModel.selectLyricsFromCandidate(candidate)
                snackbarManager.show(context.getString(R.string.lyrics_refresh_updated))
            },
            onDismissLyricsSearch = { playbackViewModel.dismissLyricsSearch() },
            onUpdatePosition = { playbackViewModel.updatePosition() },
            onAddToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
            onCreatePlaylist = { name, songId ->
                libraryViewModel.createPlaylist(name, listOfNotNull(songId))
            },
            onStartSleepTimer = { minutes, finishSong -> playbackViewModel.startSleepTimer(minutes, finishSong) },
            onCancelSleepTimer = { playbackViewModel.cancelSleepTimer() }
        )
    }
    val navigationActions = remember(playbackViewModel, appState, context, isDarkTheme) {
        NavigationActions(
            // Back del player = colapsar la capa (el back del sistema y el gesto hacen lo mismo).
            onBackClick = { appState.collapsePlayer() },
            onLaunchAmbientMode = { timeout ->
                context.startActivity(Intent(context, AmbientPlayerActivity::class.java).apply {
                    putExtra(AmbientPlayerActivity.EXTRA_TIMEOUT_MINUTES, timeout)
                })
            },
            onShowDebugInfo = { playbackViewModel.showDebugInfo(isDarkTheme) },
            onClearDebugInfo = { playbackViewModel.clearDebugInfo() },
            onSelectColor = { color -> playbackViewModel.overrideSongColor(color, isDarkTheme) },
            // `navigateToArtist/Album` detectan que el player está expandido y pasan por
            // `navigateFromPlayer`: colapsan el player recordando volver a él, así que "atrás" del
            // detalle devuelve al reproductor (no a la biblioteca).
            onArtistClick = { name -> appState.navigateToArtist(name) },
            onAlbumClick = { name, albumArtist -> appState.navigateToAlbum(name, albumArtist) }
        )
    }

    // Sin canción actual la capa no tiene nada que mostrar (vaciar la cola llama a
    // `MusicController.stop()`, que anula la canción). Antes esto dejaba el player atascado en el
    // esqueleto shimmer, sin salida salvo varios "atrás". Ahora se colapsa sola: sin canción que
    // sonar, no hay player que enseñar. Es la RED para los caminos que anulan la canción sin pasar
    // por un gesto del reproductor (logout, corrupción, purga de una fuente); el de vaciar la cola
    // cierra en el propio gesto (`onClearQueue`), en la misma vuelta que el `stop`.
    val hasSong = nowPlayingUiState.song != null
    LaunchedEffect(hasSong) {
        if (!hasSong) appState.closePlayerIfEmpty()
    }

    // **Lo que se pinta mientras el reproductor se CIERRA es su último contenido, no una superficie
    // lisa.** Al quedarse sin canción, la capa tarda lo que dure su transición en guardarse, y
    // durante ese tramo aquí se dibujaba un Box del color del fondo a pantalla completa: visto en
    // device el 20 ago, vaciar la cola era un fundido a negro sin ninguna relación con lo que había
    // un frame antes. La salida correcta es la de cualquier cierre (shared axis Z cuando no hay
    // superficie donde aterrizar, ver `detachedFactor`), y eso necesita que el contenido siga ahí
    // mientras se va. Se retiene el ÚLTIMO estado con canción mientras la capa siga en pantalla y se
    // suelta al guardarse, que es cuando el próximo estado empieza de cero. Memoria plana y no
    // snapshot state: nadie depende de ella y escribirla durante la composición invalidaría el
    // scope que la acaba de escribir (mismo patrón que `MorphOriginHolder`).
    val lastShown = remember { arrayOfNulls<NowPlayingUiState>(1) }
    if (hasSong) lastShown[0] = nowPlayingUiState
    LaunchedEffect(onScreen) {
        if (!onScreen) lastShown[0] = null
    }
    val shownState = if (hasSong) nowPlayingUiState else lastShown[0]?.takeIf { onScreen }

    // Entrada y salida SIN superficie de origen ni destino (`morphOrigin` = NONE: chips del inicio,
    // aleatorio, notificación). Sin key de contenedor no hay `sharedBounds` que anime nada, así que
    // el reproductor aparecía y desaparecía DE GOLPE; lo que corresponde es el **shared axis Z** del
    // resto de la navegación, con la misma escala, duración y curvas que los cuatro helpers del
    // `NavHost` (`appNavForwardEnter` y compañía).
    //
    // **Va AQUÍ, en la capa, y no dentro de `NowPlayingScreen`** (donde estuvo primero, 20 ago 2026):
    // esa pantalla no se compone hasta que hay canción, y al arrancar una lista desde un chip con la
    // cola vacía la canción tarda ~43 ms en llegar (medido en logcat: la capa pasa a `Expanded` con
    // `song=null`). Durante esos frames se pintaba el Box liso del early-return a pantalla completa,
    // opaco y sin `graphicsLayer` ninguno — o sea la mitad de la animación ocurría sobre algo que no
    // se veía y el resto aparecía ya puesto. En la capa cubre los DOS contenidos, con canción y sin
    // ella.
    //
    // **Dos factores y no uno**: el token spatial mueve la escala y el de EFFECTS las opacidades (la
    // regla general del scheme), y es además lo que arregla que el cierre se sintiera lento — un solo
    // float ataba el fundido al movimiento, cuando el `NavHost` funde en 150 al salir.
    //
    // ## Las DOS reglas que este bloque tuvo que aprender a la mala (20 ago 2026)
    //
    // **1. Se declaran SIEMPRE, nunca dentro de un `if`.** La primera versión las creaba solo con
    // `containerSharedKey == null`… y esa condición sale de `onScreen`, que sale del `currentState` de
    // esta misma transición. O sea: la transición decidía si la animación existe, y la animación
    // decide cuándo la transición termina. Con el conjunto de animaciones cambiando a mitad de vuelo,
    // la `Transition` puede no adoptar nunca su `targetState`, y entonces la capa se queda VARADA:
    // `currentState` en `Expanded` —así que `PlayerOverlay` la sigue COLOCANDO y se traga los
    // toques— con su hija ya en `PostExit` —así que este alpha vale 0 y no se dibuja—. El síntoma es
    // demoledor y no parece un problema de animación: la app "se cuelga", responde a los clics
    // abriendo hojas invisibles, y "atrás" cierra la app porque `playerExpanded` ya es `false`.
    // **Regla general: una animación de una `Transition` no puede existir condicionalmente si la
    // condición depende del estado de esa `Transition`.** Ahora existen siempre y lo único
    // condicional es si se APLICAN, que es una decisión de dibujo.
    //
    // **2. Ninguna dura más que el bounds (~330 ms).** Es la regla que ya estaba escrita en CLAUDE.md
    // —"de ella cuelgan la retención de la paleta, la vida de la punta de la píldora y el momento en
    // que se deja de COLOCAR el player"— y la escala la violaba con los 500 de `SCREEN_TRANSFORM_MS`.
    // Aquí NO se comparte esa duración con el `NavHost` a propósito: allí la manda la coordinación con
    // los shared elements, y acá la manda el presupuesto de la capa. Se queda en el token *slow* de
    // effects (300), con la MISMA curva del slide — lo que hace reconocible al shared axis es el
    // easing, no el número.
    val detachedScale = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(EXPRESSIVE_SLOW_EFFECTS_MS, easing = ScreenSlideEasing) },
        label = "playerDetachedScale"
    ) { if (it == EnterExitState.Visible) 1f else 0f }

    val detachedAlpha = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
            } else {
                tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)
            }
        },
        label = "playerDetachedAlpha"
    ) { if (it == EnterExitState.Visible) 1f else 0f }

    // Con superficie de origen manda el container transform (`surfaceFactor`/`contentFactor` dentro de
    // `NowPlayingScreen`) y este layer tiene que ser TRANSPARENTE al asunto: se lee DIFERIDO, como los
    // dos factores, para que cambiar de origen no reconstruya el modifier ni recomponga la capa.
    val detachedInUse = rememberUpdatedState(containerSharedKey == null)

    // Sonda: el estado que hace falta para reconocer una capa VARADA (ver la regla 1 de arriba) —
    // colocada pero apagada. Sin esto, el síntoma se investiga a ciegas porque no parece un problema
    // de animación: la app "no responde", y en realidad responde perfectamente a alpha 0. Va dentro
    // de `if (JankProbe.isEnabled)` por la convención 9d: apagada, un `SideEffect` puesto solo para
    // sondear cuesta igual.
    if (JankProbe.isEnabled) {
        SideEffect {
            JankProbe.note {
                val t = animatedVisibilityScope.transition
                "player: expanded=${appState.playerExpanded} onScreen=$onScreen " +
                    "detached=${detachedInUse.value} vis=${t.currentState}→${t.targetState} " +
                    "alpha=${"%.2f".format(detachedAlpha.value)} scale=${"%.2f".format(detachedScale.value)}"
            }
        }
    }

    // Lectura DIFERIDA dentro del bloque: cambia en cada frame de la transición y leerla en
    // composición recompondría la capa entera por frame. `ModulateAlpha` mientras corre, porque un
    // alpha < 1 sobre una capa a pantalla completa es un `saveLayer` de ese tamaño por frame (ver el
    // KDoc de `contentFactor` en NowPlayingScreen).
    val detachedModifier = remember {
        Modifier.graphicsLayer {
            if (!detachedInUse.value) {
                // Sin tocar nada: el morph ya gobierna escala y opacidad.
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
                compositingStrategy = CompositingStrategy.Auto
                return@graphicsLayer
            }
            val s = lerp(SHARED_AXIS_Z_NEAR_SCALE, 1f, detachedScale.value)
            scaleX = s
            scaleY = s
            alpha = detachedAlpha.value
            compositingStrategy =
                if (alpha < 1f) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
        }
    }

    // **La punta del container transform: la superficie que crece hasta ser el reproductor.**
    //
    // Vive AQUÍ, en la capa, y no dentro de `NowPlayingScreen`, que es donde estuvo hasta el 21 ago
    // 2026. Esa pantalla solo se compone con algo que mostrar (ver [shownState]), o sea que la
    // existencia de la punta dependía de que hubieran LLEGADO LOS DATOS: con la cola vacía la canción
    // tarda en llegar y durante esos frames el morph no tenía destino al que emparejar; cuando
    // aparecía, la punta nacía a mitad de vuelo. La capa está compuesta siempre, así que la superficie
    // crece desde el primer frame aunque el contenido todavía no esté — que es justamente lo que hace
    // un container transform. Toda la configuración del morph está en el helper.
    //
    // Va por FUERA del `graphicsLayer` de abajo (el shared axis Z) y por fuera del gesto de cierre,
    // que vive dentro de la pantalla: el morph mide la pantalla en su sitio, no arrastrada por el dedo
    // ni escalada por la otra animación. Las dos nunca están activas a la vez —con key manda el
    // morph, sin key manda el eje Z— pero el orden importa igual para los bounds.
    val containerSharedModifier = playerContainerSharedBounds(
        key = containerSharedKey,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )

    // El reproductor ya no muere al cerrarse, así que lo que había abierto DENTRO tampoco: sin esto,
    // volver a abrirlo lo mostraría con la hoja del ecualizador puesta, y el morph aterrizaría sobre
    // ella. Se limpia cuando el reproductor ya está guardado del todo (`!onScreen`) y no al empezar a
    // cerrarse: cerrar la hoja a mitad del morph se vería.
    LaunchedEffect(onScreen) {
        if (!onScreen) showEqualizerSheet = false
    }

    // ATRÁS colapsa la capa del player. Va PRIMERO en la composición a propósito: los `BackHandler`
    // se atienden en LIFO, así que los de las hojas de esta pantalla (letras, cola, ecualizador) se
    // registran después y ganan; éste es el último recurso.
    //
    // El player ya no es una ruta del NavHost, así que el back del sistema no lo cierra solo: este
    // `BackHandler` lo intercepta y llama a `collapsePlayer`, que dispara el container transform de
    // vuelta a la píldora.
    //
    // **`enabled` es obligatorio ahora que el subárbol es persistente**: antes se registraba al
    // expandir y se iba al colapsar, así que su sola existencia significaba "el player está abierto".
    // Compuesto para siempre y siempre habilitado, se tragaría el "atrás" de la biblioteca.
    BackHandler(enabled = appState.playerExpanded) { appState.collapsePlayer() }

    // [LocalPlayerOnScreen] envuelve TODO el reproductor —hoja del ecualizador incluida— porque lo que
    // publica es "este subárbol se ve", y eso vale para cualquier animación que cuelgue de él.
    CompositionLocalProvider(LocalPlayerOnScreen provides onScreen) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(containerSharedModifier)
            .then(detachedModifier)
    ) {
        // Sin NADA que mostrar —ni canción ni un último estado retenido (ver [shownState])— una
        // superficie lisa, NO el esqueleto shimmer. Solo se llega aquí en reposo (reproductor guardado
        // y sin canción), donde no se coloca; mientras se cierra, manda el contenido retenido.
        if (shownState == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.surface)
            )
            return@Box
        }
        // Las filas de la hoja de la cola (dentro del player) no deben declarar el shared element de
        // su carátula: repetirían la key por-canción de las filas de la lista de atrás y de la propia
        // carátula del player. Anular el scope aquí las desactiva; NowPlayingScreen recibe el suyo
        // por PARÁMETRO, no por este local.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            NowPlayingScreen(
                // La carátula se engancha a la punta que corresponda ELIGIENDO SU KEY; sin origen no
                // declara shared element y sube con el contenido. Se anula el `sharedTransitionScope`,
                // NO el `animatedVisibilityScope` (ese alimenta el gate de "player asentado").
                sharedTransitionScope = sharedTransitionScope
                    .takeIf { artSharedKey != null || containerSharedKey != null },
                artSharedKey = artSharedKey,
                // La punta del CONTENEDOR la declara esta capa (ver `containerSharedModifier`); la
                // pantalla solo necesita saber si el morph manda sobre sus dos alfas.
                morphActive = containerSharedKey != null,
                animatedVisibilityScope = animatedVisibilityScope,
                uiState = shownState,
                playbackState = playbackState,
                currentPositionFlow = playbackViewModel.currentPosition,
                durationFlow = playbackViewModel.duration,
                bufferedPositionFlow = playbackViewModel.bufferedPosition,
                isShuffleEnabled = playbackViewModel.isShuffleEnabled.collectAsStateWithLifecycle().value,
                repeatMode = playbackViewModel.repeatMode.collectAsStateWithLifecycle().value,
                playlistFlow = playbackViewModel.playlist,
                currentIndexFlow = playbackViewModel.currentIndex,
                isFavorite = currentSong?.let { it.id in favorites } ?: false,
                keepScreenOn = keepScreenOn,
                background = nowPlayingBackground,
                wavyProgress = nowPlayingWavyProgress,
                progressThickness = nowPlayingProgressThickness.dp,
                progressHandle = nowPlayingProgressHandle,
                detailedFormat = nowPlayingDetailedFormat,
                onToggleDetailedFormat = playbackViewModel::toggleDetailedFormat,
                gesturesEnabled = playerGestures,
                playlists = playlists,
                sleepTimer = playbackViewModel.sleepTimer.collectAsStateWithLifecycle().value,
                eqEnabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
                isSavingLyrics = lyricsSaveState.isSaving,
                playerActions = playerActions,
                navigationActions = navigationActions,
                toolbarConfig = playbackViewModel.toolbarConfig.collectAsStateWithLifecycle().value,
                obscured = equalizerCovering,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Ecualizador: overlay FULL-SCREEN que slide desde abajo, POR ENCIMA del player dentro de la
        // ruta. BackHandler para que el back del sistema cierre PRIMERO la hoja (y solo un segundo
        // back cierre el player).
        BackHandler(enabled = showEqualizerSheet) { showEqualizerSheet = false }
        // [SheetOverlay] y no `AnimatedVisibility`: esta hoja es la más cara de montar de las tres
        // —2260 líneas dentro de un `verticalScroll`, o sea que se compone y mide ENTERA, con dos
        // docenas de flows suscribiéndose a la vez— y era la que peor se veía arrancar. Ver su KDoc.
        SheetOverlay(
            visible = showEqualizerSheet,
            label = "ecualizador",
            onCoveringChange = { equalizerCovering = it }
        ) {
            EqualizerSheet(
                enabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
                bandCount = playbackViewModel.eqBandCount.collectAsStateWithLifecycle().value,
                gains = playbackViewModel.eqGains.collectAsStateWithLifecycle().value,
                bassBoost = playbackViewModel.eqBassBoost.collectAsStateWithLifecycle().value,
                trebleBoost = playbackViewModel.eqTrebleBoost.collectAsStateWithLifecycle().value,
                bassFreq = playbackViewModel.eqBassFreq.collectAsStateWithLifecycle().value,
                trebleFreq = playbackViewModel.eqTrebleFreq.collectAsStateWithLifecycle().value,
                headroomDb = playbackViewModel.eqHeadroomDb.collectAsStateWithLifecycle().value,
                preamp = playbackViewModel.eqPreamp.collectAsStateWithLifecycle().value,
                limiterEnabled = playbackViewModel.eqLimiterEnabled.collectAsStateWithLifecycle().value,
                limiterThresholdDb = playbackViewModel.eqLimiterThresholdDb.collectAsStateWithLifecycle().value,
                limiterThresholdAuto = playbackViewModel.eqLimiterThresholdAuto.collectAsStateWithLifecycle().value,
                gainReductionDb = playbackViewModel.eqGainReductionDb
                    .collectAsStateWithLifecycle(initialValue = 0f).value,
                audioRoute = playbackViewModel.audioRoute.collectAsStateWithLifecycle().value,
                profiles = playbackViewModel.eqPresets.profiles.collectAsStateWithLifecycle().value,
                hiddenPresets = playbackViewModel.eqPresets.hidden.collectAsStateWithLifecycle().value,
                clarityEnabled = playbackViewModel.clarityEnabled.collectAsStateWithLifecycle().value,
                clarityGain = playbackViewModel.clarityGain.collectAsStateWithLifecycle().value,
                conflictWarningSuppressed = playbackViewModel.eqConflictWarningSuppressed.collectAsStateWithLifecycle().value,
                onSuppressConflictWarning = { playbackViewModel.suppressEqConflictWarning() },
                onEnabledChange = { playbackViewModel.setEqEnabled(it) },
                onBandCountChange = { playbackViewModel.setEqBandCount(it) },
                onApplyPreset = { playbackViewModel.setEqGains(it) },
                onApplyProfile = { playbackViewModel.applyEqProfile(it) },
                onSaveCurrentAsProfile = { playbackViewModel.saveCurrentAsEqProfile(it) },
                onDeleteProfile = { playbackViewModel.eqPresets.deleteProfile(it) },
                onBandChange = { band, db -> playbackViewModel.setEqBand(band, db) },
                onBandChangeFinished = { playbackViewModel.commitEqGains() },
                onBassBoostChange = { playbackViewModel.setEqBassBoost(it) },
                onTrebleBoostChange = { playbackViewModel.setEqTrebleBoost(it) },
                onBassFreqChange = { playbackViewModel.setEqBassFreq(it) },
                onTrebleFreqChange = { playbackViewModel.setEqTrebleFreq(it) },
                onBoostChangeFinished = { playbackViewModel.commitEqBoosts() },
                onPreampChange = { playbackViewModel.setEqPreamp(it) },
                onPreampChangeFinished = { playbackViewModel.commitEqPreamp() },
                onLimiterEnabledChange = { playbackViewModel.setEqLimiterEnabled(it) },
                onLimiterThresholdChange = { playbackViewModel.setEqLimiterThreshold(it) },
                onLimiterThresholdChangeFinished = { playbackViewModel.commitEqLimiterThreshold() },
                onLimiterThresholdAutoChange = { playbackViewModel.setEqLimiterThresholdAuto(it) },
                onClarityEnabledChange = { playbackViewModel.setClarityEnabled(it) },
                onClarityGainChange = { playbackViewModel.setClarityGain(it) },
                onClarityGainChangeFinished = { playbackViewModel.commitClarityGain() },
                onReset = { playbackViewModel.resetEq() },
                onOpenSystemEq = openSystemEqualizer,
                onDismiss = { showEqualizerSheet = false }
            )
        }
    }
    } // CompositionLocalProvider(LocalPlayerOnScreen)
}
