package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.os.Build
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.LocalPlayerOnScreen
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.tonalVerticalGradient
import com.qhana.siku.ui.theme.appTextButtonColors
import com.qhana.siku.ui.util.shareSong
import com.qhana.siku.ui.state.NowPlayingUiState
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_SLOW_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.appContainerSurfaceExitSpec
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    /**
     * **Escala de separación vertical del reproductor.** Los tres niveles se llaman por la RELACIÓN
     * que expresan, no por su valor: qué tan juntas están dos cosas es lo que dice si son la misma
     * pieza, dos bloques vecinos o dos regiones distintas de la pantalla.
     *
     * Antes eran literales sueltos repartidos por los dos layouts —16, 24, 32 y un 6 huérfano—, sin
     * que nada dijera qué nivel representaba cada uno: info→barra iban 24 y barra→transporte 32, sin
     * criterio que explicara la diferencia. Mismo patrón que `SettingsTokens.GroupGap`/`SectionGap`
     * en Ajustes.
     */
    val ItemGap = 8.dp

    /** Bloques vecinos de la misma región: barra superior ↔ carátula ↔ título. */
    val BlockGap = 16.dp

    /**
     * Regiones funcionales: info+barra ↔ transporte ↔ toolbar. **Vale para portrait y landscape.**
     *
     * El valor se calibra en device y ha bajado dos veces (32 → 28 → **20 el 24 ago 2026**), porque
     * en esta columna todo lo que crece se lo quita a la CARÁTULA: es `weight(1f)` y CUADRADA, así
     * que 4dp aquí son 4 de lado por cada uno de los tres saltos. De paso hace innecesaria la
     * variante compacta que el landscape tenía aparte: los dos layouts usan la misma escala entera.
     *
     * **El último recorte solo rinde por ir acompañado**, y conviene saberlo antes de tocar este
     * número en cualquier dirección: la portada mide `min(ancho disponible, alto sobrante)`, y
     * mientras el ancho fue el que mandaba —la keyline y un margen propio dejaban el techo en
     * ~345dp— aflojar los saltos no la agrandaba ni un dp, solo repartía el sobrante como AIRE
     * alrededor de ella (la caja la centra). Recortar aquí devuelve lado únicamente porque la
     * portada pasó a sangrar la keyline el mismo día (ver `bleedHorizontally` en NowPlayingLayouts),
     * lo que subió ese techo al ancho ENTERO de la pantalla y devolvió el mando al alto.
     */
    val SectionGap = 20.dp

    val GroupSpacing = 8.dp
    // Las esquinas del grupo YA NO viven aquí: las de prev/next salen de las formas del icon button
    // Medium del spec (`CornerFull` en reposo, `MaterialTheme.shapes.medium` al pulsar) y las del
    // play, de su morph. Estaban en 16/8 dp escritos a mano — 16 es el corner de un botón Large, o
    // sea el tamaño equivocado, y el de 8 no lo leía nadie.
    // Hueco MÍNIMO garantizado entre el grupo de transporte y los toggles laterales
    // (aleatorio/repetir): el play se ensancha en pausa y sin este colchón se tocaban.
    val TransportSideGap = 12.dp

    /**
     * Margen lateral del contenido en PORTRAIT: la línea vertical con la que se alinean el título,
     * la barra de progreso y el toolbar. En landscape es [LandscapeContentKeyline].
     *
     * Estaba escrito como un `16.dp` suelto en el layout, y por eso la barra superior no podía
     * alinearse con él sin repetir el número.
     */
    val ContentKeyline = 16.dp

    /** El mismo margen en LANDSCAPE, donde la columna es más estrecha y respira más. */
    val LandscapeContentKeyline = 24.dp

    /**
     * Lado del área táctil de un icon button de la barra superior (ver `ExpressiveActionIcon`).
     * De aquí sale la compensación óptica: el GLIFO va centrado dentro de este cuadrado, así que
     * un botón pegado a la keyline deja su dibujo metido hacia adentro la mitad de lo que sobra.
     */
    val ActionIconTouchSize = 48.dp
}

@Immutable
data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    /** Salto RELATIVO (doble toque en la carátula); el destino lo acota el ViewModel. */
    val onSeekBy: (Long) -> Unit,
    val onShuffleToggle: () -> Unit,
    val onRepeatToggle: () -> Unit,
    val onSkipToIndex: (Int) -> Unit,
    val onReorder: (Int, Int) -> Unit,
    val onRemoveFromQueue: (Int) -> Unit,
    val onSaveQueueAsPlaylist: (String) -> Unit,
    val onClearQueue: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onToggleDownload: () -> Unit,
    val onToggleKeepScreenOn: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onFetchLyrics: (Boolean) -> Unit,
    val onSearchLyricsManually: () -> Unit,
    /** Guardar la letra en el archivo (`.lrc` o embebida, según la preferencia). */
    val onSaveLyrics: () -> Unit,
    val onSelectLyricsCandidate: (com.qhana.siku.data.repository.LyricsCandidate) -> Unit,
    /** Corre la letra N ms (positivo = más tarde); `null` la deja sin compensación. */
    val onShiftLyrics: (Long?) -> Unit,
    /** Escribe ya el ajuste de sincronía pendiente. Lo dispara la pantalla de letras al salir. */
    val onFlushLyricsShift: () -> Unit,
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
    /** Búfer cargado: tercer nivel de la barra, solo con sentido en streaming. */
    bufferedPositionFlow: StateFlow<Long>,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    /**
     * Cola e índice actual como FLOWS, no como valores: los consume SOLO la hoja de la cola, que los
     * colecta dentro de su propia rama. Como valores, cada cambio de canción (índice nuevo, y la
     * lista entera si cualquier fila cambió) recomponía este composable completo —medido con la
     * sonda (16 ago): eran 1-2 de las 3-4 recomposiciones del NowPlaying en los 30 ms siguientes al
     * `playAt`, en plena ventana del container transform— para una hoja que casi nunca está abierta.
     * Mismo patrón que `currentPositionFlow`.
     */
    playlistFlow: StateFlow<List<Song>>,
    currentIndexFlow: StateFlow<Int>,
    isFavorite: Boolean,
    keepScreenOn: Boolean,
    /** Ajustes -> Apariencia: degradado del album, color solido o caratula desenfocada. */
    background: NowPlayingBackground,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive) en vez de la píldora. */
    wavyProgress: Boolean,
    /** Ajustes -> Apariencia: grosor de la barra de progreso (vale para los dos modos). */
    progressThickness: Dp,
    /** Ajustes → Apariencia: palo del handle permanente; false = solo mientras se arrastra. */
    progressHandle: Boolean,
    /** Chip de formato con ficha técnica; se conmuta desde Ajustes O tocando el propio chip. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: deslizar para cambiar/cerrar y doble toque para saltar. */
    gesturesEnabled: Boolean,
    playlists: List<com.qhana.siku.data.model.Playlist>,
    sleepTimer: com.qhana.siku.player.MusicController.SleepTimerState?,
    /** Estado del EQ propio para el fondo activo de su botón en el toolbar. */
    eqEnabled: Boolean,
    /** Guardado de letra en curso: apaga el botón para no dispararlo dos veces. */
    isSavingLyrics: Boolean = false,
    playerActions: PlayerActions,
    navigationActions: NavigationActions,
    toolbarConfig: List<ToolbarActionState> = PlayerToolbarConfig.DEFAULT,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    /** Key del shared element de la carátula; null = sin morph (ver PlayerArtOrigin). */
    artSharedKey: Any? = null,
    /**
     * ¿Hay *container transform* en marcha, o sea una superficie de la que esta pantalla CRECE (la
     * píldora o la fila tocada)? `false` = el reproductor entra y sale por su cuenta, con el shared
     * axis Z que pinta la capa (`detachedAlpha`/`detachedScale` en `NowPlayingRoute`).
     *
     * **Es un booleano y no la key** desde el 21 ago 2026: la punta la declara la CAPA (ver el bloque
     * CONTAINER TRANSFORM más abajo), así que aquí no hay a qué engancharse — lo único que esta
     * pantalla necesita saber es quién manda sobre sus dos alfas ([morphInUse]). Cuál sea la
     * superficie de origen no llega hasta aquí, ni hace falta: la coreografía es la misma.
     */
    morphActive: Boolean = false,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    /**
     * Una superficie opaca a pantalla completa tapa el reproductor AHORA MISMO. Hoy solo la hoja del
     * ecualizador, que se monta un nivel más arriba ([com.qhana.siku.ui.NowPlayingRoute]); las de esta
     * pantalla (letras, cola) lo dicen por su cuenta.
     *
     * Es lo que apaga los relojes de dentro mientras no se ven — ver el `CompositionLocalProvider` del
     * layout. NO es lo mismo que `LocalPlayerOnScreen`: aquél significa "el reproductor está abierto"
     * y gobierna además el `keepScreenOn` y el reseteo del estado al guardarse, que con una hoja
     * encima tienen que seguir valiendo.
     */
    obscured: Boolean = false
) {
    // Sonda (solo debug): cada recomposición del scope de esta pantalla (es la función grande).
    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "NowPlayingScreen recompuesta" } }
    // ¿El reproductor se ve? Es persistente (ver `PlayerOverlay`), así que este árbol sigue vivo con
    // el reproductor guardado y todo lo que arrastre estado hasta la próxima apertura hay que
    // reponerlo a mano. Ver [LocalPlayerOnScreen].
    val onScreen = LocalPlayerOnScreen.current
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    // ¿Alguna de las hojas de ESTA pantalla tapa el reproductor del todo? Lo publica [SheetOverlay]
    // cuando su animación ha ASENTADO en abierto, así que vale `false` durante toda la subida y desde
    // el primer frame de la bajada — o sea, siempre que el reproductor pueda verse. Ver el uso más
    // abajo, en el `CompositionLocalProvider` que envuelve el layout.
    var queueCovering by remember { mutableStateOf(false) }
    var lyricsCovering by remember { mutableStateOf(false) }
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

    // Un `val` y no un `derivedStateOf`: `playbackState` es un PARÁMETRO plano (un enum), no un
    // `State`, así que el derivado no observaba nada y no podía filtrar ninguna emisión. Lo único
    // que aportaba era un `DerivedSnapshotState` que se reconstruía en cada cambio de estado, más
    // una indirección de snapshot en cada una de sus dos lecturas. `derivedStateOf` sirve cuando se
    // LEE estado observable y se quiere emitir menos que él; comparar dos enums no es ese caso.
    val isPlayingOrBuffering =
        playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING

    // Auto-fetch lyrics (force = false) cuando se abre el overlay.
    // Claves mínimas: sólo el par (showLyrics, song.id). Los campos de estado de loading
    // se consultan dentro del efecto y NO deben ser key — causarían re-triggers en cascada.
    LaunchedEffect(showLyrics, uiState.song?.id) {
        if (showLyrics && uiState.lyrics == null && uiState.lyricLines.isEmpty() && !uiState.isLyricsLoading) {
            playerActions.onFetchLyrics(false)
        }
    }

    // Mantener la pantalla encendida mientras se ven las letras. **Gateado por [onScreen]**: este
    // efecto ya no se desmonta al cerrar el reproductor, así que sin el gate la preferencia
    // `keepScreenOn` —que se ofrece DENTRO del player— dejaría la pantalla encendida en la biblioteca
    // y en Ajustes, que es justo el alcance que se acotó por batería (ver `screenOnActive` en
    // `MusicPlayerScreen`, que hace lo propio sobre la ventana).
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(onScreen, showLyrics, keepScreenOn) {
        view.keepScreenOn = onScreen && (showLyrics || keepScreenOn)
        onDispose { view.keepScreenOn = false }
    }

    val song = uiState.song
    if (song == null) {
        // Sin canción no hay nada que mostrar. La capa del player (NowPlayingLayer) ya detecta este
        // estado y COLAPSA el player, así que en la práctica no se llega aquí; es solo red de seguridad. Una
        // superficie lisa, NUNCA el layout viejo del reproductor (el antiguo NowPlayingSkeleton, ya
        // eliminado, era la PRIMERA versión de esta pantalla y no reflejaba el diseño actual).
        Box(modifier = modifier.fillMaxSize().background(AppColors.surface))
        return
    }

    // Fondo del gradiente = `secondaryContainer` → `surface` (rol del esquema, SEED-ONLY): antes era
    // el color CRUDO del álbum mezclado 50%. Sigue teñido del álbum (tema seedeado) y el tema anima
    // `secondaryContainer` al cambiar de canción (animatedScheme).
    val albumPrimary = AppColors.secondaryContainer

    // Play button + TODOS los acentos que derivan de esto = rol PRIMARY del esquema. El color
    // elegido/extraído es SOLO el SEED (el tema está seedeado de él vía MusicPlayerTheme), NO se usa
    // 1:1 — eso causaba las inconsistencias/parches (ensureContrast, onAccentContentColor y el viejo
    // `albumAccent`, ya eliminados). El tema anima primary al cambiar de canción (animatedScheme),
    // así que no hace falta el animateColorAsState local.
    val playButtonColor = AppColors.primary
    // Glifo del play por CONTRASTE real (`maxContrastOn`), NO `onPrimary`: en la mayoría de temas
    // coinciden, pero en Fidelity `onPrimary` puede ser un par de bajo contraste (gris azulado sobre
    // un primary casi negro) y el triángulo salía apagado. Mismo criterio que el play del MiniPlayer,
    // el chip de origen y el toggle de letras: contenido sobre un acento = maxContrastOn.
    val playButtonContentColor = maxContrastOn(playButtonColor)

    val surfaceColor = AppColors.surface
    val solidBackgroundColor = AppColors.surfaceContainer

    // Fondo según el ajuste del usuario: color SÓLIDO tonal M3 Expressive (surfaceContainer,
    // que ya viene teñido por el seed del álbum vía theme) o el degradado vertical clásico
    // (Color Profundo -> Fondo Neutro).
    val backgroundBrush = remember(albumPrimary, surfaceColor, solidBackgroundColor, background) {
        if (background == NowPlayingBackground.GRADIENT) {
            // Por `tonalVerticalGradient` y NO por `Brush.verticalGradient` con los dos extremos: ese
            // interpola en sRGB y el tramo del medio -el que ocupa media pantalla- se lava. Los
            // extremos son los mismos de siempre, así que el contraste de lo que va encima no cambia.
            tonalVerticalGradient(from = albumPrimary, to = surfaceColor)
        } else {
            SolidColor(solidBackgroundColor)
        }
    }

    // Color que hay REALMENTE bajo la barra superior, sea cual sea el modo de fondo: en degradado es
    // su primera parada (arriba es justo donde vive la barra). Lo consume el chip de origen, que
    // deriva de él su relleno. Se calcula AQUÍ, pegado al brush, porque son la misma decisión: si
    // algún día el degradado arranca en otro color, el chip lo sigue solo.
    val topBackgroundColor = when (background) {
        NowPlayingBackground.GRADIENT -> albumPrimary
        NowPlayingBackground.SOLID -> solidBackgroundColor
    }

    val contentColor = AppColors.onSurface
    val variantColor = AppColors.onSurfaceVariant

    // `song` viene del player; `uiState.isDownloaded` sale de la fila de la BD y se adelanta a él
    // en cuanto termina una descarga, así que gana sobre el `path` del MediaItem en curso.
    val origin = if (uiState.isDownloaded && song.sourceType != SourceType.LOCAL) {
        PlaybackOrigin.DOWNLOADED
    } else {
        song.playbackOrigin
    }
    // El nivel de búfer solo se dibuja aquí: en local y en descargadas el player reporta la
    // canción entera bufferizada desde el primer instante, así que no informaría de nada.
    val isStreaming = origin == PlaybackOrigin.STREAMING
    val formatInfo = rememberAudioFormat(song.path, song.title)

    val onAlbumArtLongPress = { navigationActions.onShowDebugInfo() }
    // Compartir: el texto se arma acá porque los recursos solo se leen desde la composición, y
    // el artista en blanco tiene que caer en la misma etiqueta que muestra la UI.
    val shareChooserTitle = stringResource(R.string.np_share)
    val shareText = stringResource(
        R.string.np_share_text,
        song.title,
        song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }
    )
    val onShareSong = { shareSong(context, song, shareText, shareChooserTitle) }
    val onLyricsToggle = { showLyrics = !showLyrics }
    val onShowQueue = { showQueueSheet = true }
    val onAmbientMode = { showAmbientModeDialog = true }
    val onAddToPlaylistClick = { showAddToPlaylist = true }
    val onSleepTimerClick = { showSleepTimerSheet = true }

    // Arrastre hacia abajo para cerrar. El estado se crea SIEMPRE (crearlo dentro de un `if`
    // ataría su `remember` a la rama y perdería el arrastre en curso si el ajuste cambiara a
    // mitad del gesto); lo que se apaga con el ajuste es quién lo alimenta.
    val dismissState = rememberPlayerDismissState { navigationActions.onBackClick() }
    val activeDismiss = dismissState.takeIf { gesturesEnabled }

    // Al GUARDARSE, el reproductor vuelve a su estado de reposo. Antes lo hacía la descomposición;
    // ahora que sobrevive hay que reponerlo, o reabrirlo mostraría lo último que quedó abierto —una
    // hoja, un diálogo— y, peor, el desplazamiento del gesto de cierre: el arrastre no vuelve a cero
    // al soltar (ver [PlayerDismissState]), así que el reproductor reaparecería caído y translúcido.
    //
    // Se dispara con `!onScreen`, o sea con el morph YA terminado: cerrar las hojas al empezar el
    // cierre se vería en el propio morph.
    LaunchedEffect(onScreen) {
        if (onScreen) return@LaunchedEffect
        showLyrics = false
        showQueueSheet = false
        showAmbientModeDialog = false
        showAddToPlaylist = false
        showCreatePlaylistDialog = false
        showSleepTimerSheet = false
        dismissState.reset()
    }

    // CONTAINER TRANSFORM: la superficie de origen y esta pantalla son LA MISMA cambiando de tamaño.
    //
    // **La punta ya no se declara aquí** (21 ago 2026): vive en la CAPA
    // (`NowPlayingLayer` → [com.qhana.siku.ui.components.playerContainerSharedBounds]), porque esta
    // pantalla solo se compone cuando hay algo que mostrar y una punta de shared element no puede
    // depender de datos asíncronos — abriendo con la cola vacía no había destino al que emparejar
    // durante los primeros frames y la punta nacía a mitad de vuelo. Toda la configuración del morph
    // (el `ContentScale` asimétrico, el recorte, la escala de z, por qué `exit` es `None`) está
    // documentada allí.
    //
    // Lo que SÍ sigue aquí son los dos alfas, porque son del CONTENIDO de esta pantalla y no de la
    // superficie que viaja: [surfaceFactor] (fundido de salida) y [contentFactor] (fundido de
    // entrada), gobernados por [morphInUse].

    // Fundido de SALIDA de la superficie ENTERA (fondo incluido) al CERRAR: el player se disuelve
    // encima de la píldora que llega, mientras encoge con ella. Es el mismo fundido que hasta ahora
    // ponía el `exit` del `sharedBounds` —por eso comparte spec, [appContainerSurfaceExitSpec]—,
    // traído aquí porque la punta no puede usar `exit`: su visibilidad la gestiona quien la compone y
    // el estado de reposo es `PostExit` (ver `playerContainerSharedBounds`, en la capa).
    //
    // **Solo se APLICA cerrando, y eso hay que decirlo en el sitio del dibujo, no solo en el spec.**
    // Abriendo, la superficie tiene que estar SÓLIDA desde el primer frame o crecer se leería como un
    // revelado — y un `animateFloat` no puede garantizarlo por sí solo: nace con el valor del estado
    // ACTUAL, que en el frame del tap todavía es `PostExit`, o sea 0, y el `snap` no lo corrige hasta
    // el tick siguiente. Un frame entero de reproductor transparente justo al arrancar el morph. Por
    // eso el `graphicsLayer` mira la DIRECCIÓN (`targetState`) y solo consulta este valor cuando el
    // reproductor va de salida; el `snap` de la otra dirección sigue haciendo falta para que una
    // apertura interrumpida a mitad se cierre desde 1 y no desde donde fuera. Lo que sí aparece
    // gradualmente al abrir es el CONTENIDO, y de eso se encarga [contentFactor], que actúa sobre otra
    // capa (el Scaffold) y no sobre el fondo.
    //
    // `State` y lectura diferida por el mismo motivo que [contentFactor]; se multiplica con el alfa
    // del gesto de cierre en UN solo `graphicsLayer` para no apilar dos capas offscreen.
    // **Se declara con `animatedVisibilityScope != null` y NADA MÁS** (21 ago 2026). Antes la
    // condición llevaba también la key del contenedor (y `sharedTransitionScope`, que la
    // ruta anula justo cuando no hay key), y eso es un LAZO: esa key sale de `onScreen`,
    // que sale del `currentState` de esta misma `Transition`. Una animación cuya existencia depende
    // del estado de su propia transición puede impedir que ésta adopte nunca su destino, y entonces
    // la capa queda VARADA —colocada, así que se traga los toques, y con su hija en `PostExit`, así
    // que se dibuja a alpha 0—. Se vio en device con el factor gemelo de `NowPlayingRoute`: la app
    // "se colgaba" respondiendo a hojas invisibles y "atrás" la cerraba. Ver la sección Motion de
    // CLAUDE.md. `animatedVisibilityScope` SÍ puede condicionar: es un parámetro, no estado animado.
    //
    // Lo que sí depende del morph es si se APLICA, y eso se decide en el `graphicsLayer` por lectura
    // diferida ([morphInUse]), que es una decisión de dibujo y no cambia el árbol.
    val surfaceFactor: State<Float>? =
        animatedVisibilityScope?.transition?.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) snap<Float>()
                else appContainerSurfaceExitSpec()
            },
            label = "playerSurfaceFactor"
        ) { if (it == EnterExitState.Visible) 1f else 0f }

    // Fade-in del CONTENIDO al ABRIR, gobernado por el PROGRESO del morph (mismo patrón que
    // `shadowFactor` en MiniPlayer): la superficie entra SÓLIDA (ver [surfaceFactor], o crecería
    // translúcida) y el contenido aparece encima mientras ella crece — el *"content is swapped"* del
    // spec. Existe con cualquier origen que tenga superficie (píldora o fila); sin origen el contenido
    // va opaco desde el primer frame.
    //
    // **Dura el token *default* de effects (200 ms), NO el del morph (500)**: es un fundido de contenido
    // —el spec lo hace en el primer tramo del container transform— y con 200 el contenido está opaco
    // antes de que la superficie termine de crecer. Tuvo además un coste que no se veía: un alpha < 1
    // sobre el Scaffold a pantalla completa obligaba a HWUI a un `saveLayer` offscreen de la pantalla
    // ENTERA en cada frame ("alpha caused saveLayer 1080x2400", atrace del 17 ago; 14-17 `drawLayer
    // 1280×2772` por apertura y ~190 MB de memoria de GPU la primera vez, Perfetto del 20 ago). Acortar
    // el fundido solo acortaba la capa; la quita `CompositingStrategy.ModulateAlpha` en el
    // `graphicsLayer` del Scaffold (ver ahí el precio asumido).
    //
    // **Solo la ENTRADA se anima**: al cerrar, el contenido se apaga con SU superficie ([surfaceFactor],
    // 300 ms) mientras ENCOGE con ella; sumarle acá un segundo fade multiplicaría los dos alphas y lo
    // apagaría antes que a la superficie que lo lleva. Cuando el contenido estuvo FUERA del contenedor
    // sí hubo que bajarlo a mano, igualando la duración para no dejar restos del player sobre la
    // píldora ya puesta — ese apaño se fue con el contenido de vuelta adentro.
    //
    // De ahí el `snap` RETRASADO del sentido de cierre, que es lo único raro de esta declaración: la
    // transición del reproductor persistente solo tiene dos estados (`Visible` / `PostExit`, ver
    // `PlayerOverlay`) y no el `PreEnter` que traía un `AnimatedVisibility`, así que "en reposo,
    // apagado" y "cerrando" son EL MISMO estado. Se resuelve por tiempo en vez de por estado: se queda
    // en 1 exactamente lo que dura el fundido de la superficie —de ahí que comparta su token— y recién
    // entonces cae a 0, que es donde tiene que estar para la PRÓXIMA apertura.
    //
    // Es un `State` y NO un valor con `by` A PROPÓSITO: se lee DIFERIDO dentro del `graphicsLayer` del
    // Scaffold — cambia en cada frame del morph y leerlo en composición recompondría la pantalla entera
    // por frame (el mismo criterio que las lecturas diferidas del progreso de reproducción).
    // Se declara igual que [surfaceFactor] —solo con `animatedVisibilityScope`— y por el mismo motivo:
    // ver allí.
    val contentFactor: State<Float>? =
        animatedVisibilityScope?.transition?.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
                } else {
                    snap<Float>(delayMillis = EXPRESSIVE_SLOW_EFFECTS_MS)
                }
            },
            label = "playerContentFactor"
        ) { if (it == EnterExitState.Visible) 1f else 0f }

    // Si hay CONTAINER TRANSFORM en marcha, o sea si estos dos factores mandan. Lectura diferida (ver
    // [surfaceFactor]): el morph puede empezar y terminar con el estado de la capa, y eso no debe
    // reconstruir modifiers ni recomponer la pantalla.
    val morphInUse = rememberUpdatedState(morphActive && animatedVisibilityScope != null)

    // ¿Hay una hoja a pantalla completa TAPANDO el reproductor ahora mismo? (Las de esta pantalla o
    // la del ecualizador, que llega por parámetro.) Mientras la hay, el reproductor no se ve, y eso
    // gobierna las dos cosas de abajo.
    val covered = queueCovering || lyricsCovering || obscured
    val coveredState = rememberUpdatedState(covered)
    // **No se COLOCA mientras está tapado**, el mismo gate con el que `PlayerOverlay` guarda el
    // reproductor entero: un nodo sin colocar no se dibuja, así que dejan de repintarse por frame el
    // degradado del álbum, la carátula de 800 px y la barra de progreso debajo de una superficie
    // opaca. Se conserva la composición y la medida, que es lo que hace que reaparezca sin coste al
    // cerrar la hoja. Lectura DIFERIDA, dentro del bloque de colocación, para que tapar y destapar
    // invaliden el layout de este subárbol y nada más.
    //
    // La condición lleva además el morph: **mientras la capa del reproductor transiciona se coloca
    // igual, tapado o no**. Es un blindaje, no un caso que se dé hoy —con una hoja encima no hay
    // forma de colapsar el reproductor: el "atrás" lo intercepta la hoja y el resto de salidas quedan
    // debajo de ella—, y desde el 21 ago la punta del morph ya no cuelga de este gate (subió a la
    // capa, ver el bloque CONTAINER TRANSFORM de arriba), así que ya no puede quedarse sin bounds por
    // esta vía. Se conserva por lo otro que sigue siendo cierto: durante el morph esta pantalla es lo
    // que se ve crecer o encoger, así que tiene que estar COLOCADA aunque algo la tape.
    val morphTransition = animatedVisibilityScope?.transition
    val placementGate = remember(coveredState, morphTransition) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (!coveredState.value || morphTransition?.isRunning == true) placeable.place(0, 0)
            }
        }
    }
    // Y los relojes de dentro —el giro de la cookie del play, la fase de la onda, el marquee del
    // título— se apagan por la misma razón por la que se apagan con el reproductor guardado: animar
    // lo que nadie ve gasta batería y, sobre todo, produce un frame en cada vsync, que es lo que
    // impide a la cola de SurfaceFlinger drenar el atasco que deja la propia apertura de la hoja (ver
    // "CERO productores continuos" en CLAUDE.md).
    //
    // Se REPUBLICA aquí y no arriba a propósito: envuelve solo el LAYOUT del reproductor, no los
    // efectos de la pantalla ni las hojas. `keepScreenOn` (que con las letras abiertas tiene que
    // seguir encendido), el reseteo del estado al guardarse y `rememberAudioFormat` leen el valor de
    // fuera, que sigue significando "el reproductor está abierto".
    CompositionLocalProvider(LocalPlayerOnScreen provides (onScreen && !covered)) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(placementGate)
            // Se traslada y atenúa TODO el reproductor —fondo incluido— con el dedo. Mover solo
            // el contenido dejaría el degradado quieto detrás y se vería el hueco por abajo.
            .graphicsLayer {
                translationY = dismissState.offsetY
                // Ver [surfaceFactor]: solo se consulta yendo de salida, y solo si el morph manda
                // (sin key de contenedor la entrada/salida la pinta `detachedAlpha` en la capa).
                val closing = animatedVisibilityScope != null &&
                    animatedVisibilityScope.transition.targetState != EnterExitState.Visible
                val surfaceAlpha =
                    if (morphInUse.value && closing) surfaceFactor?.value ?: 1f else 1f
                alpha = (1f - (1f - PlayerGestureConfig.DismissMinAlpha) * dismissState.progress) *
                    surfaceAlpha
            }
    ) {
        // Por la CONFIGURACIÓN de la ventana y no por los constraints medidos: `BoxWithConstraints`
        // subcompone, y decidir la maquetación por aspect ratio es lo que metería el layout landscape
        // en pleno vuelo si el contenedor volviera a re-medirse. La orientación de la ventana es
        // estable durante el morph; el tamaño no tiene por qué serlo.
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Capa 1 (FONDO): el gradiente en una capa DEDICADA detrás de todo, hermana del contenido.
        // Sigue separada del Scaffold —y no como su `containerColor`— porque el gesto de cierre
        // traslada y atenúa el reproductor ENTERO desde el `graphicsLayer` de arriba, fondo
        // incluido: pintarlo dentro del Scaffold lo dejaría quieto y se vería el hueco al arrastrar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        )

        // Capa 2 (CONTENIDO): layout normal, encima del fondo y transparente. El alpha del morph va
        // AQUÍ —topBar incluida— y NO en la Capa 1: esa es la superficie del contenedor y tiene que
        // crecer SÓLIDA, no aparecer translúcida.
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    contentFactor?.let { f ->
                        // `ModulateAlpha` y no la estrategia por defecto: con un alpha < 1 sobre un
                        // subárbol con varios hijos HWUI renderiza el subárbol a una capa offscreen
                        // de la PANTALLA ENTERA y la compone después, en cada frame del fundido.
                        // Medido en Perfetto el 20 ago: 14-17 `drawLayer 1280×2772` por apertura,
                        // ~190 MB de `allocateImageMemory` la primera vez (la reserva del pool de
                        // Skia dispara `kswapd0`) y 25-60 ms de espera a la GPU — el peor frame de
                        // cada apertura, fría o no. Modulando, el alpha se aplica primitiva a
                        // primitiva, sin capa. El precio es que los hijos que se SOLAPAN se
                        // transparentan entre sí durante los 200 ms (un glifo sobre su botón deja
                        // ver el contenedor debajo): asumido, porque esta capa no tiene fondo —el
                        // degradado es la Capa 1— y el fundido es corto.
                        //
                        // El `morphInUse` de dentro es la otra mitad de la regla de [surfaceFactor]:
                        // el factor existe siempre, pero solo PINTA cuando hay container transform.
                        // Sin key, quien funde es `detachedAlpha` en la capa y aquí no hay que tocar
                        // nada — y desde luego no multiplicar los dos alfas.
                        Modifier.graphicsLayer {
                            if (!morphInUse.value) {
                                alpha = 1f
                                compositingStrategy = CompositingStrategy.Auto
                                return@graphicsLayer
                            }
                            alpha = f.value
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    } ?: Modifier
                ),
            containerColor = Color.Transparent,
            topBar = {
                if (!isLandscape) {
                    NowPlayingTopBar(
                        onBackClick = navigationActions.onBackClick,
                        contentColor = contentColor,
                        accentColor = playButtonColor,
                        origin = origin,
                        backgroundColor = topBackgroundColor,
                        onAmbientMode = onAmbientMode
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Arrastre de cierre desde CUALQUIER punto del reproductor. Va en el
                    // contenedor, así que los hijos que gestionan su propio gesto (el slider, la
                    // carátula) reciben antes y este solo ve lo que ninguno quiso.
                    .playerDismissDrag(activeDismiss, gesturesEnabled)
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
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        bufferedPositionFlow = bufferedPositionFlow,
                        showBuffer = isStreaming,
                        origin = origin,
                        backgroundColor = topBackgroundColor,
                        gesturesEnabled = gesturesEnabled,
                        dismiss = activeDismiss,
                        format = formatInfo,
                        detailedFormat = detailedFormat,
                        onToggleDetailedFormat = onToggleDetailedFormat,
                        wavyProgress = wavyProgress,
                        progressThickness = progressThickness,
                        progressHandle = progressHandle,
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
                        onShareSong = onShareSong,
                        toolbarConfig = toolbarConfig,
                        sharedTransitionScope = sharedTransitionScope,
                        artSharedKey = artSharedKey,
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
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        bufferedPositionFlow = bufferedPositionFlow,
                        showBuffer = isStreaming,
                        gesturesEnabled = gesturesEnabled,
                        dismiss = activeDismiss,
                        format = formatInfo,
                        detailedFormat = detailedFormat,
                        onToggleDetailedFormat = onToggleDetailedFormat,
                        wavyProgress = wavyProgress,
                        progressThickness = progressThickness,
                        progressHandle = progressHandle,
                        playerActions = playerActions,
                        onArtistClick = navigationActions.onArtistClick,
                        onAlbumClick = navigationActions.onAlbumClick,
                        onLyricsToggle = onLyricsToggle,
                        onShowQueue = onShowQueue,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        eqEnabled = eqEnabled,
                        sleepTimerActive = sleepTimer != null,
                        onSleepTimerClick = onSleepTimerClick,
                        onShareSong = onShareSong,
                        toolbarConfig = toolbarConfig,
                        sharedTransitionScope = sharedTransitionScope,
                        artSharedKey = artSharedKey,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAlbumArtLongPress = onAlbumArtLongPress
                    )
                }
            }
        }
    }
    } // CompositionLocalProvider(LocalPlayerOnScreen) — solo el layout del reproductor

    // Polling removed: ProgressSlider observes currentPositionFlow directly.

    if (showAmbientModeDialog) {
        AlertDialog(
            onDismissRequest = { showAmbientModeDialog = false },
            title = { Text(stringResource(R.string.ambient_title)) },
            text = { Text(stringResource(R.string.ambient_desc)) },
            confirmButton = {
                TextButton(colors = appTextButtonColors(), onClick = {
                    showAmbientModeDialog = false
                    navigationActions.onLaunchAmbientMode(-1)
                }) { Text(stringResource(R.string.common_start)) }
            },
            dismissButton = {
                TextButton(colors = appTextButtonColors(), onClick = { showAmbientModeDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // Mismo componente que las otras dos hojas a pantalla completa (letras, ecualizador): la
    // coreografía es una sola y el frame de preparación que la mantiene fluida también. Ver
    // [SheetOverlay].
    SheetOverlay(
        visible = showQueueSheet,
        label = "cola",
        onCoveringChange = { queueCovering = it }
            ) {
            // La cola y el índice se colectan AQUÍ, dentro de la hoja: solo esta rama depende de
            // ellos, así que un cambio de canción no recompone la pantalla entera (ver el KDoc de
            // `playlistFlow`). Y los 777 modelos se calculan solo mientras la hoja está compuesta —
            // antes se calculaban al ABRIR el reproductor, en plena ventana del container transform.
            val playlist by playlistFlow.collectAsStateWithLifecycle()
            val currentIndex by currentIndexFlow.collectAsStateWithLifecycle()
            // Transformar playlist a modelos UI estables (ASYNC para evitar ANR en listas grandes).
            val uiPlaylist by produceState(
                initialValue = emptyList<com.qhana.siku.ui.model.SongUiModel>(),
                key1 = playlist
            ) {
                value = withContext(Dispatchers.Default) {
                    playlist.map { it.toUiModel(isActive = false) }
                }
            }
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
                // Vaciar CIERRA la hoja en el mismo gesto, antes de que la cola quede vacía. Sin esto
                // la hoja se quedaba puesta enseñando su estado "La cola está vacía" durante todo el
                // cierre del reproductor: un cartel que informa de lo que el usuario acaba de hacer, y
                // que además sobrevive a la pantalla que lo contiene. La hoja es hermana del layout del
                // reproductor (no cuelga de su `graphicsLayer`), así que no se va con él: hay que
                // retirarla explícitamente.
                onClearQueue = {
                    showQueueSheet = false
                    playerActions.onClearQueue()
                },
                accentColor = albumPrimary
            )
        }

        // --- Full Screen Lyrics Overlay ---
        SheetOverlay(
            visible = showLyrics,
            label = "letras",
            onCoveringChange = { lyricsCovering = it }
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
                                        onSaveLyrics = playerActions.onSaveLyrics,
                                        isSavingLyrics = isSavingLyrics,
                                        onSearchManually = playerActions.onSearchLyricsManually,
                                        onSelectCandidate = playerActions.onSelectLyricsCandidate,
                                        lyricsShiftMs = uiState.lyricsShiftMs,
                                        onShiftLyrics = playerActions.onShiftLyrics,
                                        onFlushLyricsShift = playerActions.onFlushLyricsShift,
                                        onDismissSearch = playerActions.onDismissLyricsSearch,
                                        onPlayPause = playerActions.onPlayPause,
                                        onNext = playerActions.onNext,
                                        onPrevious = playerActions.onPrevious,
                                        // Fondo SÓLIDO surfaceContainer (el mismo del ajuste "fondo
                                        // sólido"), no el degradado del álbum: el overlay de letras se
                                        // lee como una superficie neutra estable. Contraste = onSurface.
                                        backgroundColor = solidBackgroundColor,
                                        contentColor = AppColors.onSurface,
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

/**
 * Ficha del audio que suena: contenedor + los datos que distinguen una copia buena de una mala.
 * Los tres numéricos son OPCIONALES porque no siempre se pueden saber: en streaming no se abre el
 * archivo (costaría red), y `SAMPLERATE`/`BITS_PER_SAMPLE` de `MediaMetadataRetriever` existen
 * desde API 31 — por debajo, o si el contenedor no los declara, el chip simplemente muestra menos.
 */
@Immutable
internal data class AudioFormatInfo(
    val format: String,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null
) {
    /** ¿Hay algo que enseñar además del contenedor? Si no, el modo detallado no cambia nada. */
    val hasDetails: Boolean get() = bitrateKbps != null || sampleRateHz != null || bitsPerSample != null
}

/**
 * Abre el archivo para leer contenedor, bitrate y profundidad. **Solo con el reproductor a la vista**
 * ([LocalPlayerOnScreen]): desde que su árbol es persistente, esto correría en CADA cambio de canción
 * aunque nadie tenga el reproductor abierto — un `MediaMetadataRetriever` por pista, en disco, para un
 * chip que no se ve.
 *
 * **Lo leído se CACHEA por ruta** ([audioFormatCache]) y por eso "guardado" ya no significa "olvidar".
 * El motivo de olvidarlo era real —la canción pudo cambiar mientras el reproductor estaba guardado, y
 * enseñar el bitrate de la anterior es peor que no enseñar nada—, pero se resolvía tirando el dato
 * cuando en realidad bastaba con guardarlo BAJO SU CLAVE: con la ruta delante, lo que se recupera al
 * abrir es siempre lo de la canción que suena, y lo de otra canción no puede colarse. Lo que se
 * ahorra es una relectura del archivo POR APERTURA (el reproductor se abre y se cierra decenas de
 * veces por sesión) y, con ella, la recomposición que ese valor tardío provocaba a mitad del morph:
 * cacheado llega en la primera composición, no unos frames después.
 *
 * El formato de un archivo no cambia mientras su ruta sea la misma; una descarga que convierte un
 * `https://` en `file://` cambia la ruta, así que se vuelve a leer sola.
 */
@Composable
private fun rememberAudioFormat(path: String, title: String): AudioFormatInfo {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val onScreen = LocalPlayerOnScreen.current
    val format by produceState(
        initialValue = audioFormatCache[path] ?: AudioFormatInfo(UNKNOWN_FORMAT),
        key1 = path,
        key2 = title,
        key3 = onScreen
    ) {
        // El `initialValue` solo se aplica en la PRIMERA composición del `produceState`: al cambiar
        // de canción con el reproductor abierto hay que releer el caché aquí, o el chip se quedaría
        // con el formato anterior hasta que terminara la lectura.
        audioFormatCache[path]?.let {
            value = it
            return@produceState
        }
        if (!onScreen) {
            value = AudioFormatInfo(UNKNOWN_FORMAT)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                // 0. Fuente LOCAL (SAF): el content:// no tiene extensión, hay que leer el MIME
                // con la sobrecarga Context+Uri.
                if (path.startsWith("content://")) {
                    val retriever = MediaMetadataRetriever()
                    return@withContext try {
                        retriever.setDataSource(appContext, android.net.Uri.parse(path))
                        retriever.readFormatInfo()
                    } catch (_: Exception) {
                        AudioFormatInfo(UNKNOWN_FORMAT)
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
                        retriever.readFormatInfo()
                    } catch (_: Exception) {
                        // Fallback a extensión del archivo
                        AudioFormatInfo(java.io.File(filePath).extension.uppercase().ifEmpty { UNKNOWN_FORMAT })
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                // 2. Para streaming: extensión del path, luego del título (nombre original del
                // archivo). SIN abrir el recurso: el retriever sobre http descargaría cabeceras
                // por red cada vez que cambia la canción, y el detalle no vale ese precio.
                else {
                    AudioFormatInfo(
                        detectFormatByExtension(path) ?: detectFormatByExtension(title) ?: UNKNOWN_FORMAT
                    )
                }
            } catch (_: Exception) {
                AudioFormatInfo(UNKNOWN_FORMAT)
            }
            // Solo se cachea un resultado CONCLUYENTE: un archivo que todavía no estaba en el
            // dispositivo (o un retriever que falló) devuelve el genérico, y sellarlo dejaría el
            // chip en "AUDIO" para siempre. Mismo criterio que `songs.artworkAttemptedAt`: se
            // recuerda lo que se sabe de cierto, no lo que se intentó.
        }.also { if (it.format != UNKNOWN_FORMAT) audioFormatCache.put(path, it) }
    }
    return format
}

/**
 * Ficha técnica ya leída, por RUTA del archivo (ver [rememberAudioFormat]).
 *
 * Acotado porque no hay ningún momento en que convenga vaciarlo: vive lo que el proceso, y una
 * biblioteca grande lo llenaría entrada a entrada con solo escuchar. [AUDIO_FORMAT_CACHE_ENTRIES]
 * cubre de sobra una sesión de escucha —lo que se reabre es la canción en curso y sus vecinas de
 * cola— y cada entrada son cuatro campos, así que el techo es despreciable frente a lo que ahorra.
 */
private val audioFormatCache = android.util.LruCache<String, AudioFormatInfo>(AUDIO_FORMAT_CACHE_ENTRIES)

/**
 * Lee contenedor + ficha técnica de un retriever YA posicionado sobre el recurso. Las claves de
 * frecuencia y profundidad son API 31+; por debajo se devuelven nulas y el chip enseña lo que hay.
 */
private fun MediaMetadataRetriever.readFormatInfo(): AudioFormatInfo {
    val format = mimeToFormat(extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
    // El bitrate viene en bits por segundo; el chip habla en kbps.
    val bitrateKbps = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { (it + BPS_PER_KBPS / 2) / BPS_PER_KBPS }
    val sampleRate: Int?
    val bitsPerSample: Int?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        sampleRate = extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull()?.takeIf { it > 0 }
        bitsPerSample = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    } else {
        sampleRate = null
        bitsPerSample = null
    }
    return AudioFormatInfo(format, bitrateKbps, sampleRate, bitsPerSample)
}

/** Etiqueta cuando no se pudo determinar el contenedor. */
private const val UNKNOWN_FORMAT = "AUDIO"

/** Entradas del caché de fichas técnicas. Ver [audioFormatCache]. */
private const val AUDIO_FORMAT_CACHE_ENTRIES = 64

private const val BPS_PER_KBPS = 1000

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
