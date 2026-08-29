package com.qhana.siku.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.ui.theme.AppSnackbar
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.LibraryTabId
import com.qhana.siku.data.model.LibraryTabsConfig
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.util.FuzzyMatch
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.ui.PlayerArtOrigin
import com.qhana.siku.ui.SharedTransitionGate
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appMenuItemColors
import com.qhana.siku.ui.theme.appTextButtonColors
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import com.qhana.siku.ui.viewmodel.DownloadBannerState
import com.qhana.siku.ui.viewmodel.LibraryBannerState
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appBannerEnter
import com.qhana.siku.ui.theme.appBannerExit
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.qhana.siku.ui.LibraryChrome
import com.qhana.siku.ui.libraryChrome
import com.qhana.siku.ui.navigation.Screen

@Immutable
internal data class TabInfo(
    val tab: LibraryTabId,
    val titleRes: Int,
    val iconName: String
)

/**
 * Movimiento del search view expandido, SIN rebote. Los defaults de
 * [rememberContainedSearchBarState] son `MotionSchemeKeyTokens.FastSpatial`, que con
 * `MotionScheme.expressive()` resuelve a `dampingRatio = 0.6f` — muy subamortiguado, y la píldora
 * oscilaba al abrir y al cerrar. Se conserva la rigidez del token (800) para no alterar la
 * velocidad percibida; solo se lleva el damping a crítico.
 */
private val SearchViewMotionSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f
)

/**
 * Coincidencias de [query] ordenadas por relevancia, tolerando erratas (ver [FuzzyMatch]).
 *
 * El ORDEN es la mitad del asunto: la lista de origen viene alfabética, así que sin reordenar, una
 * coincidencia corregida podría aparecer antes que una literal solo por su inicial. Con esto lo que
 * se escribió tal cual va primero, y detrás lo que se parece.
 */
private inline fun <T> List<T>.rankedByRelevance(
    query: String,
    crossinline name: (T) -> String
): List<T> =
    asSequence()
        .map { it to FuzzyMatch.score(name(it), query) }
        .filter { it.second != FuzzyMatch.NO_MATCH }
        .sortedBy { it.second }
        .map { it.first }
        .toList()

/**
 * Escala de partida/llegada del cruce lupa↔flecha del leading icon. El glifo no nace en 0:
 * se encoge lo justo para que el cambio se lea como un relevo y no como un icono que aparece
 * de la nada. Simétrico entrada/salida a propósito.
 */
private const val IconCrossfadeScale = 0.7f


/**
 * Presentación (etiqueta + glifo) de cada pestaña. El ORDEN y la VISIBILIDAD ya no viven aquí:
 * los decide el usuario en Ajustes → Apariencia → Pestañas y llegan como
 * [com.qhana.siku.data.model.LibraryTabState]. Este mapa solo traduce id → cómo se dibuja.
 */
private val tabInfo: Map<LibraryTabId, TabInfo> = listOf(
    TabInfo(LibraryTabId.HOME, R.string.tab_home, "home"),
    TabInfo(LibraryTabId.SONGS, R.string.tab_all, "library_music"),
    TabInfo(LibraryTabId.ARTISTS, R.string.common_artists, "artist"),
    TabInfo(LibraryTabId.ALBUMS, R.string.common_albums, "album"),
    TabInfo(LibraryTabId.GENRES, R.string.common_genres, "genres"),
    TabInfo(LibraryTabId.PLAYLISTS, R.string.tab_playlists, "playlist_play")
).associateBy { it.tab }

// ExperimentalMaterial3ExpressiveApi: la variante contained del search view expandido.
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun LibraryScreen(
    isLoggedIn: Boolean,
    // Avatar de cuenta del header: foto de perfil cacheada + inicial (fallback). Ambos null en
    // solo-local → el botón muestra el engranaje de ajustes.
    accountPhotoPath: String? = null,
    accountInitial: String? = null,
    onLogoutClick: () -> Unit,
    onDownloadManagerClick: () -> Unit,
    onPlaylistClick: (Long, String) -> Unit,
    onFavoritesClick: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    /** Estadísticas de escucha; se llega solo desde la tarjeta del inicio. */
    onStatsClick: () -> Unit,
    /** "Ver todo" de Recién agregadas; se llega solo desde esa sección del inicio. */
    onSeeAllRecentClick: () -> Unit,
    onNavigateToNowPlaying: (PlayerArtOrigin) -> Unit,
    onNavigateToSettings: () -> Unit,
    // Scopes para los shared elements foto/carátula → header del detalle (artista/álbum).
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
    // Inyección de ViewModels.
    //
    // `libraryViewModel` SIN default a propósito: tiene que llegar el de la Activity. Con
    // `hiltViewModel()` aquí, la resolución cae en el ViewModelStore del `NavBackStackEntry` de
    // esta ruta y nace una SEGUNDA instancia, distinta de la que `AppNavHost` pasa a los detalles
    // de lista/artista/álbum. Eran dos objetos con sus propios colectores de `syncManager.state` y
    // sus propios flujos de paging sobre la misma base de datos, y cualquier estado que no viva en
    // DataStore o en un singleton (favoritos en memoria, banner, query) se veía distinto según la
    // pantalla. Quitar el default es lo que impide que se cuele otra vez sin darse cuenta.
    libraryViewModel: LibraryViewModel,
    // Sin default por el MISMO motivo que [libraryViewModel], y no por precaución: este es el
    // ViewModel del reproductor, o sea el que la Activity entera comparte (el player, la píldora,
    // el tema). Una segunda instancia aquí no solo duplicaría sus ~17 colectores sobre DataStore:
    // sus `MutableStateFlow` locales —los del ecualizador, que se escriben en cada frame de
    // arrastre— dejarían de ser los mismos objetos, que es exactamente el fallo ya documentado en
    // `PlaybackViewModel.eqLimiterEnabled`. `AppNavHost` ya pasa el correcto; quitar el default es
    // lo que impide que un llamador futuro se olvide.
    playbackViewModel: PlaybackViewModel,
    // El banner de progreso de sync lo maneja LibraryViewModel; syncViewModel se usa para el
    // banner PERSISTENTE de descargas pausadas/detenidas (lee flows de singletons).
    syncViewModel: SyncViewModel = hiltViewModel(),
    browseViewModel: BrowseViewModel = hiltViewModel()
) {
    // Sonda (solo debug): cada recomposición del scope de la biblioteca entera.
    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "LibraryScreen recompuesta" } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Mantenimiento de las fotos de artista (backfill + re-disparo al cambiar de red). Se pide
    // AQUÍ y en ningún otro sitio: de esta pantalla cuelgan las superficies que las muestran
    // —pestaña Artistas, inicio, búsqueda— y desde ellas se llega a los detalles. Estaba en el
    // `init` de `BrowseViewModel`, que se resuelve por `NavBackStackEntry`: cada detalle de
    // artista, álbum o género montaba su propia réplica. Ver [BrowseViewModel.startArtistPhotoMaintenance].
    // Y el saneo de los chips de origen de Artistas/Álbumes, que son pestañas de ESTA pantalla:
    // también estaba en un `init` que corría en cada detalle, donde esos filtros ni existen.
    LaunchedEffect(browseViewModel) {
        browseViewModel.startArtistPhotoMaintenance()
        browseViewModel.startSourceFilterSanitizing()
    }

    // Data Collection from ViewModels
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val songCount by libraryViewModel.songCount.collectAsStateWithLifecycle()
    val artists by browseViewModel.artists.collectAsStateWithLifecycle()
    val albums by browseViewModel.albums.collectAsStateWithLifecycle()
    // Visibilidad de los chips de origen (compartida por Todas/Artistas/Álbumes): Local solo
    // si hay AMBAS familias; Descargadas/Nube con que haya nube. Misma regla que SongsScreen.
    // Una sola regla, decidida en el ViewModel (ver [LibraryViewModel.hasSourceSplit]): la
    // comparten los chips de Artistas/Álbumes de aquí, los de Todas (que la leen del mismo sitio)
    // y las dos acciones de origen del inicio.
    val showLocalSourceChip by libraryViewModel.showLocalSourceChip.collectAsStateWithLifecycle()
    val showCloudSourceChips by libraryViewModel.showCloudSourceChips.collectAsStateWithLifecycle()

    // Secciones de la pestaña Inicio (reactivas al historial). Son TRES: las dos fijas y el hueco
    // de descubrimiento, que el ViewModel resuelve a una sola candidata por día.
    val homeRecentlyAdded by libraryViewModel.homeRecentlyAdded.collectAsStateWithLifecycle()
    val recentContexts by libraryViewModel.recentContexts.collectAsStateWithLifecycle()
    val recentGenreArts by libraryViewModel.recentGenreArts.collectAsStateWithLifecycle()
    val favoriteCovers by libraryViewModel.favoriteCovers.collectAsStateWithLifecycle()
    val homeStats by libraryViewModel.homeStats.collectAsStateWithLifecycle()
    val homeDiscover by libraryViewModel.homeDiscover.collectAsStateWithLifecycle()
    val homeTopGenres by libraryViewModel.homeTopGenres.collectAsStateWithLifecycle()

    // Resultados de búsqueda SECCIONADOS: artistas y álbumes que matchean la query se filtran
    // aquí en memoria (las listas ya viven cargadas para las tabs); las canciones las sigue
    // filtrando el paging LIKE. Solo con búsqueda activa en la pestaña Todas.
    val searchQuery = uiState.searchQuery
    val isSearchActive = uiState.showSearch && searchQuery.isNotBlank()
    // SIN tope de resultados, a propósito. Lo hubo (12) y no compraba nada: estas dos secciones son
    // `LazyRow` horizontales —el nº de tarjetas no cambia el alto ni empuja a las canciones, y lo
    // que no se ve no se compone—, así que el límite no era ni de layout ni de rendimiento. Sí
    // hacía daño: cortaba sobre la lista ALFABÉTICA, de modo que con una búsqueda poco específica
    // se quedaba con los doce primeros del abecedario y el artista buscado podía no estar entre
    // ellos. Quien afina la búsqueda ve pocas coincidencias igualmente.
    val searchArtists = remember(artists, searchQuery, isSearchActive) {
        if (!isSearchActive) emptyList()
        else artists.rankedByRelevance(searchQuery) { it.name }
    }
    val searchAlbums = remember(albums, searchQuery, isSearchActive) {
        if (!isSearchActive) emptyList()
        else albums.rankedByRelevance(searchQuery) { it.name }
    }
    
    // El MiniPlayer vive ahora en MainActivity; acá solo se necesita el estado para el
    // acento del álbum en reproducción.
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()

    // Dialog State
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    // Canción retenida cuando el diálogo de crear lista se abre DESDE la hoja "agregar a
    // lista": la lista nueva nace con ella dentro. null = diálogo abierto desde la pestaña
    // Listas → al crear se navega al detalle (ahí vive "añadir canciones").
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // --- PAGER (antes de la búsqueda: el trailing de la píldora depende de currentTab) ---
    // Las pestañas visibles, en el orden del usuario (Ajustes → Apariencia → Pestañas). El
    // fallback al DEFAULT no es defensa de más: `tabInfo` podría no cubrir un id de una config
    // guardada por una versión futura, y esta lista NUNCA puede quedar vacía (el pager exige
    // pageCount ≥ 1 y sin pestañas no habría forma de volver).
    val tabsConfig by libraryViewModel.libraryTabs.collectAsStateWithLifecycle()
    val tabs = remember(tabsConfig) {
        tabsConfig.filter { it.visible }.mapNotNull { tabInfo[it.tab] }
            .ifEmpty { LibraryTabsConfig.DEFAULT.mapNotNull { tabInfo[it.tab] } }
    }
    // Repuesto de lo que solo vive en el inicio: con Inicio oculta, Estadísticas se queda sin
    // ninguna puerta (ver [LibraryViewModel.homeTabHidden]).
    val homeTabHidden by libraryViewModel.homeTabHidden.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val currentTab = tabs.getOrNull(pagerState.currentPage) ?: tabs[0]

    // Ocultar la pestaña activa (o reordenar) deja al pager apuntando a una página que ya no
    // existe o que ahora es otra: se lleva el foco a un índice válido en cuanto cambia la config.
    LaunchedEffect(tabs.size) {
        if (pagerState.currentPage >= tabs.size) pagerState.scrollToPage(tabs.lastIndex)
    }

    // --- CROMO DE NAVEGACIÓN: dónde se dibuja el selector de pestañas ---
    // Las tres presentaciones (fila arriba, barra abajo, rail al lado) son proyecciones del MISMO
    // `pagerState.currentPage`; esto solo decide cuál se pinta. La regla vive en `libraryChrome` y
    // no aquí porque la CAPA del reproductor tiene que leer exactamente la misma para saber cuánto
    // apartarse: dos copias de esta condición serían dos verdades, y la píldora acabaría flotando
    // por encima o por debajo de la barra según qué composición ganara el frame.
    //
    // `LocalConfiguration` y no `BoxWithConstraints`, por el mismo motivo que en `NowPlayingScreen`
    // (allí está el KDoc largo) y por uno más: es un CompositionLocal, o sea el MISMO valor en el
    // mismo frame para los dos lectores, cosa que unos constraints medidos en otro subárbol no
    // puede garantizar.
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val chrome = libraryChrome(
        route = Screen.Library.route,
        bottomTabs = uiState.libraryBottomTabs,
        landscape = isLandscape
    )

    // El COSTADO: en vertical no existe y en horizontal es donde cae todo lo que estorba — la
    // perforación de pantalla y, con navegación de tres botones, la barra del sistema.
    // `safeDrawing` y no `displayCutout` a secas (el precedente del NowPlaying) porque allá el
    // Scaffold ya aporta las barras en su `innerPadding` y aquí sus insets están a CERO: no llega
    // nada, hay que pedir las dos cosas. En vertical valen 0 y no cambia un píxel.
    val layoutDirection = LocalLayoutDirection.current
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val safeEndInset = sideInsets.calculateEndPadding(layoutDirection)
    // El rail no es una barra del Scaffold (ése solo tiene slots arriba y abajo) sino una columna
    // que se descuenta a mano: el contenido, la cabecera y el banner se apartan con este inset y
    // el rail se dibuja en el hueco. Así el pager NO cambia de tamaño ni se re-mide, y ninguna de
    // las seis pantallas se entera — dos de ellas reconstruyen su `PaddingValues` y perderían un
    // `start` puesto ahí.
    //
    // Se le suma el inset de ESE lado porque el rail se pinta hasta el borde y aparta su contenido
    // él solo (`windowInsets` del componente): lo que ocupa es su ancho MÁS la perforación.
    val startInset = sideInsets.calculateStartPadding(layoutDirection)
    val railInset =
        if (chrome == LibraryChrome.Rail) LibraryRailWidth + startInset else startInset

    // --- SCROLL & APPBAR ---
    // TopBar SIEMPRE visible (pinned): se quitó el enterAlways que la ocultaba al deslizar
    // en "Todas" (y con él la lógica de auto-mostrar al parar el scroll).
    val topAppBarState = rememberTopAppBarState()
    val currentScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(state = topAppBarState)

    // El bloque de cabecera (búsqueda + pestañas) es una NAVIGATION BAR puesta arriba, no un top app
    // bar, y de ahí sale su color. Los tokens lo confirman: `LibraryTabsRow` ya pinta el tab activo
    // como `NavigationBarTokens` (`ItemActiveIndicatorColor` = `secondaryContainer`,
    // `ItemActiveIndicatorShape` = `CornerFull`, inactivos en `onSurfaceVariant`). Y una navigation
    // bar de M3 se separa del contenido POR COLOR, en un peldaño por encima de él: su
    // `ContainerElevation = Level2` no es una sombra —la implementación solo expone `tonalElevation`,
    // que además es no-op cuando el container ya no es `surface`, y `NavigationBarDefaults.Elevation`
    // vale Level0—, es el NOMBRE del peldaño de color.
    //
    // Por eso NO va fundido con el fondo, y NO vira con el scroll (eso es el patrón del app bar, que
    // es otro componente: `AppBarTokens` sí tiene `OnScrollContainerColor`). Aquí la barra flota
    // sobre el contenido siempre, así que se distingue siempre.
    //
    // **EL REPARTO NO ES UNA PILA, ES UNA HORQUILLA**, y esto es lo que hay que entender antes de
    // tocar cualquier color de la biblioteca. La referencia es el Dialer de Google (M3 Expressive
    // real), muestreado píxel a píxel el 20 ago 2026:
    //
    //     tarjetas de llamada y search bar   250,249,254   tono ~98   `surface`
    //     FONDO de la página                 238,237,243   tono ~94   `surfaceContainer`
    //     bottom navigation bar              231,232,237   tono ~92   `surfaceContainerHigh`
    //
    // El fondo NO es el extremo de la escala: es el nivel MEDIO. Desde ahí el CONTENIDO sube (98,
    // más claro, flota) y las BARRAS bajan (92, más oscuras, se hunden) — direcciones OPUESTAS. Esta
    // pantalla lo copia: fondo `surfaceContainer`, filas y tarjetas `surface`, cabecera aquí.
    //
    // Antes se apilaba todo hacia un lado desde `surface` (98 / 96 / 94: fondo, cabecera, filas) y
    // por eso "la escala se agotaba": las tres superficies competían por el mismo lado y siempre
    // había una frontera en 2 puntos de tono, invisible. Con la horquilla la frontera crítica
    // —cabecera contra las filas que le pasan por debajo al scrollear— pasa a **6 puntos**, la mayor
    // de la pantalla, mientras que contra el fondo se queda en 2 y la barra deja de pesar. Las dos
    // quejas que llevaron aquí (que no se distinguía de las filas; que era una masa contra el fondo)
    // se resuelven a la vez porque cada una mira una frontera distinta.
    //
    // Historia de lo probado y DESCARTADO en device el mismo día, para no repetirlo: cabecera en el
    // techo (`surfaceContainerHighest`, 90) con el fondo en 96 — se leía como una MASA, ~157dp de
    // banda, el 16 % de la pantalla; y fondo en `surface` (98) con esa misma cabecera — salto máximo
    // en tono Y croma a la vez, "no combinan". El diagnóstico que ordenó todo salió de medir los
    // píxeles de una captura: los tres colores tenían el MISMO hue y la misma calidez (R−G = 10-11),
    // o sea que el problema nunca fue de armonía sino de PESO y de DIRECCIÓN.
    // Color de las BARRAS de navegación (la fila de pestañas, la de abajo y el rail): el peldaño
    // que la horquilla les reserva.
    val chromeColor = AppColors.surfaceContainerHigh
    // **La cabecera solo lleva ese color mientras SEA una barra de navegación.** Con el selector
    // abajo o al lado se queda con la búsqueda y nada más, así que deja de ser una barra y se funde
    // con el fondo de página: una banda tonal a todo el ancho para sostener una píldora pesaría sin
    // decir nada. La píldora de búsqueda no pierde separación — sigue en `surface`, un peldaño por
    // encima del fondo, que es justo lo que hace el Dialer con sus tarjetas.
    val headerColor =
        if (chrome == LibraryChrome.None) chromeColor else AppColors.surfaceContainer
    // Contenedor de la píldora de búsqueda: `surface` (98), el lado del CONTENIDO — 6 puntos por
    // encima de su bloque cuando la cabecera es barra (92), y 4 sobre el fondo de página cuando se
    // funde con él (94). En los dos casos queda del lado del contenido, que es lo que importa. Es lo que hace el Dialer, donde la search bar lleva exactamente el
    // mismo color que las tarjetas de la lista (medido: `250,249,254` en las dos).
    //
    // **Se aparta de `SearchBarTokens.ContainerColor`** (`surfaceContainerHigh`, 92) y **va sin
    // sombra**: el Level 3 de `SearchBarTokens.ContainerElevation` se implementó y se quitó el mismo
    // día (20 ago, en device). Ese token da por hecho que la search bar flota sobre el fondo de la
    // página; aquí vive DENTRO de una barra, y con la horquilla ya se separa de ella por tono. Con
    // el token crudo el campo empataría con su propio bloque.
    // NEUTRO a propósito: el color fuerte (`secondaryContainer`) es el lenguaje del estado
    // SELECCIONADO —el tab activo—, y teñir también la búsqueda le robaba ese protagonismo.
    val headerItemColor = AppColors.surface

    // --- BÚSQUEDA (search as secondary action / focused search) ---
    // Componente REAL de M3, variante CONTAINED: la lupa de la TopBar es el ancla colapsada y los
    // resultados viven en ExpandedFullScreenContainedSearchBar (diálogo edge-to-edge, que trae su
    // propio manejo del back). El TextField manual que había en el slot topBar se borró
    // (ui/components/SearchBar.kt).
    //
    // Contained, NO la variante con divisor (`ExpandedFullScreenSearchBar`): aquella pega el input
    // al borde y dibuja un `HorizontalDivider` bajo él, y su superficie crece geométricamente. La
    // contained deja el input como píldora con márgenes, la superficie entra por alpha
    // (`layerBlock { alpha = state.progress }`) y el contenido tiene su propio fade
    // (`contentProgress`). Es el estilo expressive — el "bouncy" del search view contained.
    val searchBarState = rememberContainedSearchBarState(
        animationSpecForExpand = SearchViewMotionSpec,
        animationSpecForCollapse = SearchViewMotionSpec
    )
    // El override se queda aunque `headerItemColor` COINCIDA hoy con el default del componente
    // (`collapsedContainedSearchBarColor` = `SearchBarTokens.ContainerColor` = `surfaceContainerHigh`):
    // lo que se pasa es la constante de ESTA pantalla, así que el input y el `Surface` que proyecta
    // su sombra siguen al mismo valor si mañana cambia el reparto. Sin él serían dos fuentes para el
    // mismo color, que es como se separan en silencio. El resto de la paleta (contenido, superficie
    // del diálogo expandido) se conserva por defecto.
    val searchBarColors = SearchBarDefaults.containedColors(searchBarState).let { base ->
        base.copy(
            // El `containerColor` va TAMBIÉN, no solo el del input: el componente los usa en dos
            // capas —`SearchBar` pinta un `Surface` con él y el `inputField` pinta la suya dentro—,
            // así que dejarlo en su default (`SearchBarTokens.ContainerColor`, 92) mientras el input
            // baja a `headerItemColor` (94) las descuadraría en un peldaño. Mientras los dos valores
            // coincidían daba igual; desde que la píldora se aparta del token, no.
            //
            // SOLO en colapsado: este mismo objeto lo consume `ExpandedFullScreenContainedSearchBar`,
            // y ahí `containedColors` devuelve OTRO valor (`fullScreenContainedSearchBarColor`) que
            // es el fondo del diálogo a pantalla completa. Pisarlo sin mirar el estado le cambiaría
            // el color a esa pantalla, que no tiene nada que ver con la píldora de la cabecera.
            // `currentValue`, no `isExpanded`: esa extensión existe en material3 pero es PRIVADA de
            // su archivo, y es literalmente esta misma comparación.
            containerColor =
                if (searchBarState.currentValue == SearchBarValue.Expanded) base.containerColor
                else headerItemColor,
            inputFieldColors = base.inputFieldColors.copy(
                focusedContainerColor = headerItemColor,
                unfocusedContainerColor = headerItemColor
            )
        )
    }
    val searchTextFieldState = rememberTextFieldState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // La query sigue siendo dueña del LibraryViewModel (la consume el paging LIKE): el
    // TextFieldState del componente es solo el input, así que se replica hacia el VM.
    LaunchedEffect(Unit) {
        snapshotFlow { searchTextFieldState.text.toString() }
            .collect { libraryViewModel.onSearchQueryChanged(it) }
    }
    // targetValue (no currentValue): al colapsar limpiamos apenas arranca la animación,
    // no al terminarla, para que la lista de atrás ya esté sin filtrar cuando se destapa.
    LaunchedEffect(searchBarState.targetValue) {
        val expanded = searchBarState.targetValue == SearchBarValue.Expanded
        libraryViewModel.onShowSearchChanged(expanded)
        if (!expanded) searchTextFieldState.clearText()
    }

    // El mismo inputField se pasa al contenedor expandido; el componente lo mueve durante el
    // morph. El foco inicial lo pide el propio diálogo (ya no hace falta un FocusRequester).
    val backDesc = stringResource(R.string.common_back)
    val clearDesc = stringResource(R.string.common_clear_search)
    // Mitad de la expansión: antes de eso el leading sigue siendo la lupa (continuidad con el
    // icono del que nace el diálogo), después es "volver".
    val showBackIcon by remember { derivedStateOf { searchBarState.progress > 0.5f } }
    val searchInputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = searchTextFieldState,
            searchBarState = searchBarState,
            // Píldora tonal sobre la superficie del diálogo (el input NO comparte color con el
            // contenedor en la variante contained).
            colors = searchBarColors.inputFieldColors,
            // Los resultados se filtran en vivo: "buscar" en el IME solo baja el teclado.
            onSearch = { keyboardController?.hide() },
            placeholder = {
                // Una línea con elipsis: el texto es largo y en pantallas angostas, sin esto,
                // el decorator lo recorta a mitad de letra en vez de puntearlo.
                Text(
                    text = stringResource(R.string.common_search_songs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                // El icono arranca siendo la MISMA lupa del ancla y se cruza a "volver" pasada
                // la mitad de la expansión. Con `currentValue` no sirve: ese flag salta a
                // Expanded en cuanto progress > 0.02, o sea que la lupa se volvía flecha antes
                // de que la barra creciera — el corte que se veía. derivedStateOf recorta la
                // recomposición a un único cambio, no a un frame por tick del spring.
                IconButton(
                    onClick = { scope.launch { searchBarState.animateToCollapsed() } },
                    modifier = Modifier.semantics { contentDescription = backDesc }
                ) {
                    // Izados: `transitionSpec` NO es un lambda composable, así que los tokens
                    // del MotionScheme (que sí son lectura composable) se resuelven aquí.
                    val iconEnterFade = appEffectsSpec<Float>()
                    val iconEnterScale = appSpatialSpec<Float>()
                    val iconExitFade = appFastEffectsSpec<Float>()
                    val iconExitScale = appFastSpatialSpec<Float>()
                    AnimatedContent(
                        targetState = showBackIcon,
                        // Mismos tokens de motion que el resto de la cabecera: el que entra con
                        // el ritmo "default", el que sale con el "fast".
                        transitionSpec = {
                            (fadeIn(iconEnterFade) +
                                scaleIn(iconEnterScale, initialScale = IconCrossfadeScale))
                                .togetherWith(
                                    fadeOut(iconExitFade) +
                                        scaleOut(iconExitScale, targetScale = IconCrossfadeScale)
                                )
                        },
                        label = "searchLeadingIcon"
                    ) { back ->
                        MaterialSymbol(if (back) "arrow_back" else "search")
                    }
                }
            },
            trailingIcon = {
                when {
                    searchTextFieldState.text.isNotEmpty() -> {
                        IconButton(
                            onClick = { searchTextFieldState.clearText() },
                            modifier = Modifier.semantics { contentDescription = clearDesc }
                        ) { MaterialSymbol("close") }
                    }
                    // Docked (y hasta la mitad del morph, espejo del leading): el overflow vive
                    // DENTRO de la píldora como trailing icon del spec de search bar. El ORDEN
                    // ya no vive aquí: pasó a un chip en la fila sobre la lista (consistente con
                    // Artistas/Álbumes).
                    !showBackIcon -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LibraryOverflowButton(
                            isLoggedIn = isLoggedIn,
                            accountPhotoPath = accountPhotoPath,
                            accountInitial = accountInitial,
                            showStatsEntry = homeTabHidden,
                            onStatsClick = onStatsClick,
                            onSettingsClick = onNavigateToSettings,
                            onLogoutClick = { showLogoutDialog = true }
                        )
                    }
                }
            }
        )
    }

    // Host ÚNICO de la app (MainActivity). Esta pantalla ya no crea el suyo: todo el feedback
    // sale del bus SnackbarManager, que alimenta ese host.
    val snackbarHostState = LocalSnackbarHostState.current

    // --- BANNER LOGIC ---
    // Banner PERSISTENTE de descargas pausadas/detenidas (mismo diseño que el Download Manager).
    // Solo se muestra cuando NO hay un banner de sync en curso (idle), en el mismo slot.
    val downloadBanner by syncViewModel.downloadBanner.collectAsStateWithLifecycle()
    val showBanner = uiState.bannerState !is LibraryBannerState.Hidden || downloadBanner != null
    // Lo ÚLTIMO que hubo que mostrar, retenido mientras el banner se va.
    //
    // **Sin esto, la animación de salida no existe por mucho spec que se le ponga**, y era la causa
    // real del "desaparece de golpe": el contenido del `AnimatedVisibility` se decide con un `when`
    // sobre `uiState.bannerState`, así que en cuanto ese estado pasa a `Hidden` la rama `else` no
    // pinta NADA. `AnimatedVisibility` se queda animando la altura de una caja vacía —la tarjeta ya
    // se esfumó en el primer frame— y lo que se ve es un salto. Reteniendo el último contenido
    // visible, el `exit` tiene algo que apagar y encoger.
    //
    // Contenedor plano y no estado de snapshot, por lo mismo que hacía la retención de paleta: se
    // escribe y se lee en la MISMA composición (la que ya está corriendo porque `showBanner` cambió),
    // así que un `mutableStateOf` solo serviría para invalidarse a sí mismo.
    val bannerContent = remember { LastBannerContent() }
    if (showBanner) {
        bannerContent.state = uiState.bannerState
        bannerContent.download = downloadBanner
    }
    // El indicador del pull-to-refresh es estado del ViewModel: se enciende solo si el scan
    // realmente va a correr (red/batería verificadas) y se apaga cuando el sync publica
    // estado — sin "safety timeout" arbitrario.
    val isManualRefreshing by libraryViewModel.isManualRefreshing.collectAsStateWithLifecycle()

    // El back de la búsqueda lo maneja el diálogo de ExpandedFullScreenSearchBar: no hace falta
    // BackHandler propio en este nivel (el que sí hay vive DENTRO de su content, ver abajo).

    // Decisión de duplicados entre fuentes: el sync la detecta (StateFlow del SyncManager,
    // sobrevive a navegación) y aquí se pregunta — el home es a donde se aterriza tras
    // conectar una fuente en onboarding o Ajustes.
    val duplicateCount by syncViewModel.duplicateDecisionNeeded.collectAsStateWithLifecycle()
    duplicateCount?.let { count ->
        DuplicatePolicyDialog(
            count = count,
            onResolve = syncViewModel::resolveDuplicates,
            onDismiss = syncViewModel::dismissDuplicates
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(currentScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0,0,0,0),
        // Fondo de la biblioteca: `surfaceContainer` (94), el nivel MEDIO de la escala — NO el
        // extremo claro. Desde aquí el contenido sube a `surface` y las barras bajan a
        // `surfaceContainerHigh`; la horquilla completa, con la medición del Dialer de la que sale,
        // está en `headerColor`. Estuvo en `surface` (98) unas horas el 20 ago: con todo apilado
        // hacia abajo desde el extremo, alguna frontera quedaba siempre en 2 puntos de tono.
        containerColor = AppColors.surfaceContainer,
        // Sin snackbarHost: el host único vive en MainActivity, ya posicionado sobre el
        // MiniPlayer flotante. Tener otro acá duplicaba el componente y solo mostraba los
        // snackbars pedidos a mano desde esta pantalla, no los del bus.
        topBar = {
            // Search bar DOCKED como cabecera (sin título: la píldora activa de las tabs ya
            // dice dónde estás — el título era redundante). El componente registra solo sus
            // collapsedCoords como ancla del morph; adiós al hack del Box de 56dp sobre la lupa.
            // Único header posible: al no haber selección múltiple, el AnimatedContent que
            // alternaba con la barra de selección quedó sin segundo estado.
            LibrarySearchHeader(
                searchBarState = searchBarState,
                searchInputField = searchInputField,
                searchBarColors = searchBarColors,
                containerColor = headerColor,
                contentEndPadding = safeEndInset,
                // Aparta la cabecera del rail. Va en el modifier EXTERNO, o sea antes de su
                // `background`, para que el tinte del bloque empiece donde acaba el rail y no
                // por debajo de él.
                modifier = Modifier.padding(start = railInset)
            )

            // Overlay de búsqueda. No ocupa alto en el slot topBar: internamente es un Dialog
            // que sólo se compone con el estado expandido.
            ExpandedFullScreenContainedSearchBar(
                state = searchBarState,
                inputField = searchInputField,
                colors = searchBarColors
                // collapsedShape por defecto (píldora): el ancla ya ES la píldora docked,
                // el morph nace de su forma real (antes era CircleShape por la lupa).
            ) {
                // Cierre UNIFORME: el back del sistema debe animar igual que el icono arrow_back,
                // o sea morph directo a la lupa, y no con el encogimiento propio del diálogo. Este
                // BackHandler se compone DENTRO del content, después del PredictiveBackStateHandler
                // de BasicEdgeToEdgeDialog, así que gana por prioridad LIFO del
                // OnBackPressedDispatcher: consume el back y llama al mismo animateToCollapsed().
                // Es el patrón que el propio componente usa en su variante docked.
                //
                // SIGUE HACIENDO FALTA con el predictive back desactivado (18 ago 2026): lo que se
                // fue con el flag es el preview del gesto, pero el diálogo sigue teniendo su propio
                // cierre y sin esto el back lo usaría en vez del morph a la lupa.
                BackHandler { scope.launch { searchBarState.animateToCollapsed() } }
                Box(modifier = Modifier.fillMaxSize()) {
                    SearchResults(
                        searchQuery = searchQuery,
                        searchArtists = searchArtists,
                        searchAlbums = searchAlbums,
                        libraryViewModel = libraryViewModel,
                        playbackViewModel = playbackViewModel,
                        onAddToPlaylistRequest = { songIdForPlaylist = it },
                        onCollapseAnd = { action -> scope.launch { searchBarState.animateToCollapsed(); action() } },
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick
                    )
                    // Segundo host sobre el MISMO estado que el del root: el diálogo es otra
                    // ventana y tapa aquél. Acá no hay MiniPlayer flotante, así que el snackbar
                    // solo esquiva la navbar.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        snackbar = { AppSnackbar(it) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp)
                    )
                }
            }
        },
        // **Sin slot `bottomBar`, y eso es parte de la decisión** (22 ago 2026): el selector de
        // abajo pasó a ser un toolbar FLOTANTE (`LibraryBottomToolbar`), y ese slot es para barras
        // pegadas al borde — le reserva su alto al body, así que el contenido dejaría de pasar por
        // debajo, que es justo lo que hace que una pieza flotante se lea como flotante. Se dibuja
        // como capa al final del Box, igual que el rail y que el MiniPlayer.
        //
        // Consecuencia a tener presente: `paddingValues.calculateBottomPadding()` ya no trae el
        // alto de nada, así que el colchón de las listas se calcula abajo a mano.
    ) { paddingValues ->
        // El MiniPlayer vive en MainActivity y FLOTA sobre el final de la
        // lista; arriba, espejo: el CONTENIDO PASA POR DEBAJO del header (TopBar + tabs,
        // opacos con tinte on-scroll). El pager ocupa TODA la altura y cada lista reserva
        // el alto del header como contentPadding superior — así el borde del bloque tonal
        // es exactamente donde el contenido desaparece y el header no se lee como un
        // "cuadro" apilado sobre la lista.
        val topBarInset = paddingValues.calculateTopPadding()
        var bannerHeightPx by remember { mutableIntStateOf(0) }
        val bannerHeight = with(LocalDensity.current) { bannerHeightPx.toDp() }
        // Alto que reserva la cabecera. La fila de pestañas solo cuenta cuando de verdad está
        // ARRIBA: con la barra abajo o el rail al lado, esos 54dp serían aire muerto en las seis
        // pantallas a la vez.
        val headerInset =
            topBarInset + (if (chrome == LibraryChrome.None) TabsRowHeight else 0.dp) + bannerHeight
        // El colchón bajo las listas: navbar del sistema + lo que flota encima. `FloatingBarListInset`
        // (margen + mini + aire) es el MISMO en los dos modos, porque el mini flota igual contra un
        // suelo o contra el otro; lo que se suma con el selector abajo es lo que ocupa el toolbar
        // desde el borde (`LibraryBottomBarPillLift`), que es exactamente lo que el mini sube.
        //
        // **La navbar se repone SIEMPRE a mano.** Antes venía del `bottomBar` del Scaffold, que la
        // consumía por dentro; sin ese slot, `paddingValues.calculateBottomPadding()` devuelve CERO
        // en los dos modos y una lista con el selector abajo se metería bajo la barra del sistema.
        val listInsets = PaddingValues(
            // El aire hasta el contenido separa el BLOQUE de cabecera, así que solo hace falta
            // mientras ese bloque exista: con el selector abajo o al lado la cabecera se funde con
            // el fondo y ya no hay frontera de la que despegarse — el contenido arranca donde
            // acaba la búsqueda, con el aire que el propio header ya pone debajo de su píldora.
            top = headerInset + if (chrome == LibraryChrome.None) HeaderContentGap else 0.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                (if (chrome == LibraryChrome.BottomBar) LibraryBottomBarPillLift else 0.dp) +
                ComponentConfig.FloatingBarListInset
        )
        Box(modifier = Modifier.fillMaxSize()) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isManualRefreshing,
                onRefresh = { libraryViewModel.onRefresh() },
                state = pullState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = railInset, end = safeEndInset),
                indicator = {
                    // El contenido arranca en y=0 (detrás del header): bajar el indicador
                    // para que asome bajo las tabs y no quede oculto tras la TopBar. Se suma
                    // el alto del banner para que, cuando hay banner (p.ej. "Descargando"),
                    // aparezca DEBAJO de él y no tapado por la tarjeta.
                    //
                    // `LoadingIndicator` y no `Indicator`: es la variante Expressive, que morfea
                    // entre MaterialShapes en vez de girar un arco. Mismo criterio que el resto de
                    // los circulares de la app (AlbumArt, SongListItem, MiniPlayer, LyricsScreen)
                    // — este era el último que quedaba con el componente clásico.
                    PullToRefreshDefaults.LoadingIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = headerInset),
                        isRefreshing = isManualRefreshing,
                        state = pullState
                    )
                }
            ) {
                HorizontalPager(state = pagerState, key = { it }, beyondViewportPageCount = 1) { page ->
                    val tab = tabs[page]
                    // **Solo la pestaña que se VE declara shared elements.** `beyondViewportPageCount`
                    // deja la vecina compuesta y COLOCADA, y una punta colocada participa en el match
                    // aunque esté fuera del viewport: por eso una canción que salía en un carrusel del
                    // Inicio se abría SIN morph desde "Todas" (dos puntas de la misma key, las dos
                    // declarándose destino). Ver [SharedTransitionGate], donde está el caso completo.
                    //
                    // `derivedStateOf` y no leer la página a pelo: así solo recomponen las dos
                    // páginas que cambian de estado al deslizar, no las cuatro que hay compuestas. La
                    // clave del `remember` es `page`, que es constante para esta ranura — lo que se
                    // observa es el estado del pager (convención 9c).
                    //
                    // **`settledPage` y NO `currentPage`, y el motivo está MEDIDO** (Perfetto, 24 ago
                    // 2026): `currentPage` salta a mitad del cruce, así que el cambio de `visible`
                    // caía en el frame en el que el pager ya estaba midiendo las dos páginas. Cambiar
                    // el valor del local invalida a sus LECTORES — las filas que declaran puntas de
                    // shared element, unas 18 en pantalla, y por partida doble porque al cruzar
                    // cambian de estado DOS páginas —, y ese trabajo entraba dentro del
                    // `measureAndLayout` del propio frame: 29 ms de frame, de los que 23,6 eran la
                    // medida y dentro 18 `Compose:recompose` (9,0 ms) + 18 `Compose:applyChanges`
                    // (6,1 ms). Detrás quedaban tres frames de 18-28 ms y ~3 s con el 85 % de los
                    // frames en buffer stuffing: el tirón al pasar de Inicio a Todas.
                    //
                    // `settledPage` congela el valor mientras `isScrollInProgress` (PagerState.kt:406)
                    // y lo suelta al asentarse, o sea que la re-declaración se paga en un frame
                    // ocioso en vez de en el del cruce. No es menos trabajo: es el mismo trabajo
                    // fuera del camino crítico, que es el criterio de la convención 13.
                    //
                    // Lo que el gate promete sigue en pie: en reposo hay UNA sola página con scope, y
                    // durante el gesto también (la saliente lo conserva), así que el empate de dos
                    // puntas que motivó todo esto no puede volver. El precio es que tocar una fila
                    // MIENTRAS la pestaña aún se desliza la abriría sin morph — la página entrante
                    // todavía no declara puntas—, y eso pide un gesto que el propio pager consume.
                    val isActivePage by remember(page, pagerState) {
                        derivedStateOf { page == pagerState.settledPage }
                    }
                    SharedTransitionGate(visible = isActivePage) {
                    when (tab.tab) {
                        LibraryTabId.HOME -> {
                            HomeScreen(
                                recentlyAdded = homeRecentlyAdded,
                                recentContexts = recentContexts,
                                favoriteCovers = favoriteCovers,
                                genreArts = recentGenreArts,
                                stats = homeStats,
                                discover = homeDiscover,
                                currentSongId = nowPlayingUiState.song?.id,
                                contentPadding = listInsets,
                                // `ROW` y no `NONE`: la tarjeta del carrusel es la punta de origen
                                // del container transform, igual que una fila de "Todas" (ver
                                // `SongRowContainer` en HomeScreen). Con `NONE` el reproductor
                                // aparecía con un fundido, sin morph.
                                onPlaySongs = { songs, index ->
                                    playbackViewModel.playSongs(songs, index)
                                    onNavigateToNowPlaying(PlayerArtOrigin.ROW)
                                },
                                // "Porque escuchaste a X" propone otros ARTISTAS: su tarjeta navega
                                // al detalle, no reproduce.
                                onArtistClick = onArtistClick,
                                onStatsClick = onStatsClick,
                                // "Ver todo" de Recién agregadas → su RUTA PROPIA, no la pestaña
                                // Canciones. Esa primera versión (27 ago 2026) fijaba
                                // `DATE_ADDED_DESC` y saltaba de página, y fallaba por tres lados:
                                // el destino era la biblioteca ENTERA y no la ventana, el orden se
                                // PERSISTE —así que el atajo dejaba la pestaña reordenada para
                                // siempre— y ese cambio de sort invalidaba el `PagingSource` justo
                                // durante la animación de la página. Ver [RecentlyAddedScreen].
                                onSeeAllRecent = { onSeeAllRecentClick() },
                                onResumeContext = { ctx -> resumeContext(
                                    ctx, scope, browseViewModel, libraryViewModel, playbackViewModel,
                                    uiState.favoriteSongs, onNavigateToNowPlaying
                                ) },
                                canPlayAll = songCount > 0,
                                onShuffleAll = {
                                    playbackViewModel.shuffleAllFromLibrary()
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                onPlayAll = {
                                    playbackViewModel.playAllFromLibrary()
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                // Misma puerta que los chips de filtro de las listas: con la
                                // biblioteca entera en el dispositivo, estas dos acciones no
                                // separarían nada y no se pintan.
                                showSourceActions = showCloudSourceChips,
                                onShuffleOffline = {
                                    playbackViewModel.shuffleBySource(
                                        setOf(SongSourceFilter.LOCAL, SongSourceFilter.DOWNLOADED)
                                    )
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                onShuffleNotDownloaded = {
                                    playbackViewModel.shuffleBySource(setOf(SongSourceFilter.STREAMING))
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                hasFavorites = uiState.favoriteSongs.isNotEmpty(),
                                onShuffleFavorites = {
                                    // Mismo contexto que reproducir Favoritos desde su lista: el
                                    // chip es otro atajo a lo mismo, no otra cosa.
                                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                                    playbackViewModel.shufflePlay(uiState.favoriteSongs)
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                genres = homeTopGenres,
                                onShuffleGenre = { genre ->
                                    scope.launch {
                                        val songs = libraryViewModel.getSongsByGenre(genre)
                                        if (songs.isNotEmpty()) {
                                            // Igual que reproducirlo desde la pestaña Géneros: es
                                            // el mismo contexto reanudable. Sin esto, un género
                                            // lanzado desde el chip no llegaba nunca a "Seguir
                                            // escuchando" y la sección contradecía a la fila de
                                            // chips que está justo encima.
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Genre(
                                                    genre,
                                                    songs.firstOrNull()?.albumArtUri?.toString()
                                                )
                                            )
                                            playbackViewModel.shufflePlay(songs)
                                            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                        }
                                    }
                                },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.SONGS -> {
                            // Lista normal: la búsqueda ya no vive acá, sino en el overlay
                            // (ExpandedFullScreenSearchBar), que tapa la pantalla entera.
                            SongsScreen(
                                currentFilter = SongFilter.ALL,
                                contentPadding = listInsets,
                                onNavigateToNowPlaying = { onNavigateToNowPlaying(PlayerArtOrigin.ROW) },
                                onAddToPlaylistRequest = { songIdForPlaylist = it },
                                viewModel = libraryViewModel,
                                playbackViewModel = playbackViewModel,
                                songCount = songCount,
                                sortOrder = uiState.sortOrderAll,
                                onSortOrderChange = { libraryViewModel.onSortOrderChanged(it, SongFilter.ALL) },
                                onToggleSourceFilter = libraryViewModel::toggleSourceFilter,
                                // Las acciones de esta lista (que solo existen sin la pestaña Inicio,
                                // ver `LibraryViewModel.homeTabHidden`) reproducen LA LISTA
                                // QUE SE VE: su orden y sus chips de origen. No es lo que significan
                                // los del inicio, donde no hay ninguna lista delante. `NONE` y no
                                // `ROW`: no se pulsó ninguna fila, así que no hay superficie de la
                                // que crecer.
                                onPlayAll = {
                                    playbackViewModel.playLibraryList(
                                        sortOrder = uiState.sortOrderAll,
                                        sourceFilters = uiState.sourceFilters,
                                        shuffled = false
                                    )
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                },
                                onShuffleAll = {
                                    playbackViewModel.playLibraryList(
                                        sortOrder = uiState.sortOrderAll,
                                        sourceFilters = uiState.sourceFilters,
                                        shuffled = true
                                    )
                                    onNavigateToNowPlaying(PlayerArtOrigin.NONE)
                                }
                            )
                        }
                        LibraryTabId.ARTISTS -> {
                            val artistPhotosPaused by browseViewModel.artistPhotosPausedOnMobile
                                .collectAsStateWithLifecycle()
                            val artistSortOrder by browseViewModel.artistSortOrder
                                .collectAsStateWithLifecycle()
                            val artistSourceFilters by browseViewModel.artistSourceFilters
                                .collectAsStateWithLifecycle()
                            ArtistsScreen(
                                artists = artists,
                                onArtistClick = onArtistClick,
                                sortOrder = artistSortOrder,
                                onSortOrderChange = browseViewModel::setArtistSortOrder,
                                sourceFilters = artistSourceFilters,
                                onToggleSourceFilter = browseViewModel::toggleArtistSourceFilter,
                                showLocalChip = showLocalSourceChip,
                                showCloudChips = showCloudSourceChips,
                                meteredBannerVisible = artistPhotosPaused,
                                onDownloadOnMobile = browseViewModel::downloadArtistPhotosOnMobile,
                                onDismissMeteredBanner = browseViewModel::dismissArtistPhotosBanner,
                                // Quick-play: reproduce todo el artista sin navegar (el
                                // MiniPlayer aparece como feedback), como en Álbumes.
                                onPlayArtist = { name ->
                                    scope.launch {
                                        val artistSongs = browseViewModel.getArtistSongs(name).first()
                                        if (artistSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Artist(name, artistSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(artistSongs, 0)
                                        }
                                    }
                                },
                                // Encolar NO es reproducir: no toca el contexto de "seguir
                                // escuchando" (que describe de dónde salió lo que SUENA) ni abre el
                                // player. El feedback lo da el snackbar de `addToQueue`.
                                onAddArtistToQueue = { name ->
                                    scope.launch {
                                        playbackViewModel.addToQueue(
                                            browseViewModel.getArtistSongs(name).first()
                                        )
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.ALBUMS -> {
                            val albumSortOrder by browseViewModel.albumSortOrder
                                .collectAsStateWithLifecycle()
                            val albumSourceFilters by browseViewModel.albumSourceFilters
                                .collectAsStateWithLifecycle()
                            AlbumsScreen(
                                albums = albums,
                                onAlbumClick = onAlbumClick,
                                sortOrder = albumSortOrder,
                                onSortOrderChange = browseViewModel::setAlbumSortOrder,
                                sourceFilters = albumSourceFilters,
                                onToggleSourceFilter = browseViewModel::toggleAlbumSourceFilter,
                                showLocalChip = showLocalSourceChip,
                                showCloudChips = showCloudSourceChips,
                                // Quick-play: reproduce el álbum sin navegar (el MiniPlayer
                                // aparece como feedback).
                                onPlayAlbum = { name ->
                                    scope.launch {
                                        val albumSongs = browseViewModel.getAlbumSongs(name).first()
                                        if (albumSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Album(name, albumSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(albumSongs, 0)
                                        }
                                    }
                                },
                                onAddAlbumToQueue = { name ->
                                    scope.launch {
                                        playbackViewModel.addToQueue(
                                            browseViewModel.getAlbumSongs(name).first()
                                        )
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.GENRES -> {
                            val genres by browseViewModel.genres.collectAsStateWithLifecycle()
                            val partialMatch by browseViewModel.genrePartialMatch
                                .collectAsStateWithLifecycle()
                            GenresScreen(
                                genres = genres,
                                onGenreClick = onGenreClick,
                                partialMatch = partialMatch,
                                onPartialMatchChange = browseViewModel::setGenrePartialMatch,
                                // Quick-play: reproduce el género sin navegar (el MiniPlayer
                                // aparece como feedback), igual que Artistas/Álbumes.
                                onPlayGenre = { name ->
                                    scope.launch {
                                        val genreSongs = browseViewModel.getGenreSongs(name).first()
                                        if (genreSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Genre(name, genreSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(genreSongs, 0)
                                        }
                                    }
                                },
                                onAddGenreToQueue = { name ->
                                    scope.launch {
                                        playbackViewModel.addToQueue(
                                            browseViewModel.getGenreSongs(name).first()
                                        )
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.PLAYLISTS -> {
                            val coverMeta by libraryViewModel.playlistsCoverMeta.collectAsStateWithLifecycle()
                            PlaylistList(
                                playlists = uiState.playlists,
                                favoritesCount = uiState.favorites.size,
                                coverMeta = coverMeta,
                                onPlaylistClick = { id ->
                                    val pl = uiState.playlists.find { it.id == id }
                                    if (pl != null) onPlaylistClick(id, pl.name)
                                },
                                onPlayPlaylist = { libraryViewModel.playPlaylist(it) },
                                onFavoritesClick = onFavoritesClick,
                                onCreatePlaylist = { showCreatePlaylistDialog = true },
                                onDeletePlaylist = { libraryViewModel.deletePlaylist(it) },
                                onRenamePlaylist = { id, name -> libraryViewModel.renamePlaylist(id, name) },
                                onImportPlaylist = { uri ->
                                    libraryViewModel.importPlaylistFromFile(
                                        uri,
                                        context.getString(R.string.playlist_import_default_name)
                                    )
                                },
                                onExportPlaylist = { id, uri ->
                                    libraryViewModel.exportPlaylistToFile(id, uri)
                                },
                                onExportFavorites = { uri -> libraryViewModel.exportFavoritesToFile(uri) },
                                contentPadding = listInsets
                            )
                        }
                    }
                    } // SharedTransitionGate
                }
            }

            // HEADER como capa SOBRE el contenido: tabs (+ banner) con fondo opaco. La
            // TopBar la dibuja el Scaffold, también por encima del body y del mismo color.
            Column(
                modifier = Modifier.padding(
                    top = topBarInset,
                    start = railInset,
                    end = safeEndInset
                )
            ) {
                // La fila solo se pinta si el selector vive ARRIBA. Con la barra abajo o el rail
                // al lado, el bloque de cabecera se queda en la búsqueda y el banner sube a
                // ocupar su sitio: son tres presentaciones de lo mismo, nunca dos a la vez.
                if (chrome == LibraryChrome.None) {
                    LibraryTabsRow(
                        tabs = tabs,
                        selectedIndex = pagerState.currentPage,
                        onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                        containerColor = headerColor
                    )
                }
                // El banner FLOTA sobre la lista (tarjeta suelta, sin fondo de bloque); su
                // alto medido se suma al contentPadding para que el primer ítem nazca debajo.
                //
                // El `onSizeChanged` mide un Box que SIEMPRE existe, NO el `AnimatedVisibility`:
                // cuando la salida termina, ese composable deja de emitir nodo, así que nunca
                // reporta el 0 final y la ÚLTIMA medida —un fotograma a medio encoger— se quedaba
                // grabada en `bannerHeightPx`. Resultado: tras el primer banner de la sesión, las
                // seis pestañas reservaban un hueco fantasma bajo las tabs para siempre (medido en
                // captura: 22dp de aire muerto entre la fila de pestañas y el contenido, con el
                // banner ya retirado). Con el contenedor de por medio, la medida es la del SLOT y
                // llega a cero sola cuando dentro no queda nada.
                //
                // Es un `Column` y no un `Box` a propósito: `AnimatedVisibility` tiene sobrecarga
                // de `ColumnScope` (la que da el expand/shrink VERTICAL por defecto) y los scopes
                // de layout de Compose están marcados con `@LayoutScopeMarker`, así que desde
                // dentro de un `BoxScope` el receiver de fuera deja de ser accesible y no compila.
                Column(modifier = Modifier.onSizeChanged { bannerHeightPx = it.height }) {
                AnimatedVisibility(
                    visible = showBanner,
                    // Specs del tema: el default de `AnimatedVisibility` es `fadeIn + expandIn`
                    // con springs de compose-animation, que no leen el MotionScheme. Y el par
                    // PROPIO del banner, no el genérico: aparece sin que nadie lo pida y empuja la
                    // lista entera — ver [appBannerEnter].
                    enter = appBannerEnter(),
                    exit = appBannerExit()
                ) {
                    // El banner CAMBIA de contenido sin desaparecer —"Escaneando" releva a
                    // "Biblioteca al día"— y los dos no miden lo mismo: el de progreso lleva la onda
                    // y dos líneas, el resumen solo dos. Sin esto la altura salta de golpe y con ella
                    // toda la lista de debajo. Va DENTRO del `AnimatedVisibility` y no fuera: al
                    // entrar, el tamaño medido no cambia (lo que anima es el recorte de
                    // `expandVertically`), así que no se pisan; solo actúa en el relevo.
                    Box(modifier = Modifier.animateContentSize(appEffectsSpec())) {
                    // Del holder y NO de `uiState` directamente: ver [LastBannerContent].
                    when (val state = bannerContent.state) {
                        is LibraryBannerState.Error -> {
                            ErrorBanner(state.message) { libraryViewModel.onRefresh() }
                        }
                        is LibraryBannerState.Scanning -> {
                            ScanProgressBanner(state.progress, state.message)
                        }
                        is LibraryBannerState.Preparing -> {
                            PrepareProgressBanner(state.current, state.total, state.message)
                        }
                        is LibraryBannerState.Downloading -> {
                            DownloadSummaryBanner(
                                active = 1,
                                completed = state.current,
                                total = state.total,
                                failed = state.failed,
                                onClick = onDownloadManagerClick
                            )
                        }
                        is LibraryBannerState.Complete -> {
                            SyncCompleteBanner(state.newSongs, state.downloaded, state.failed, state.deleted)
                        }
                        is LibraryBannerState.Paused -> {
                            SyncPausedBanner(state.message)
                        }
                        // Idle: si hay descargas pausadas/detenidas con pendientes, mostramos el
                        // banner persistente (con opción de reanudar / cancelar).
                        else -> bannerContent.download?.let { banner ->
                            DownloadStateBanner(
                                control = banner.control,
                                pending = banner.pending,
                                onResume = { syncViewModel.resumeDownloads() },
                                onDismiss = { syncViewModel.dismissDownloadBanner() },
                                onMute = { syncViewModel.muteDownloadBanner() }
                            )
                        }
                    }
                    }
                }
                }
            }

            // El rail va DESPUÉS de la cabecera, o sea por encima de todo lo demás, y ocupa la
            // columna que `railInset` acaba de dejar libre. Puede llenar la pantalla de alto sin
            // pelearse con la barra de estado por dos motivos: el body del Scaffold llega hasta
            // arriba (sus insets están a cero y el contenido pasa por debajo del header), y el
            // inset superior lo consume el propio componente.
            if (chrome == LibraryChrome.Rail) {
                LibraryNavRail(
                    tabs = tabs,
                    selectedIndex = pagerState.currentPage,
                    onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    containerColor = chromeColor,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }

            // El toolbar del selector, cuando vive abajo. Es una CAPA sobre el contenido —como el
            // rail y como el MiniPlayer— y no un `bottomBar`: el contenido tiene que pasar por
            // debajo para que se lea como una pieza flotante, y su alto lo reserva `listInsets`.
            //
            // No lleva `railInset`/`safeEndInset`: los tres modos son excluyentes, así que con el
            // toolbar en pantalla no hay rail del que apartarse, y sus propios márgenes ya cuentan
            // la perforación.
            if (chrome == LibraryChrome.BottomBar) {
                LibraryBottomToolbar(
                    tabs = tabs,
                    selectedIndex = pagerState.currentPage,
                    onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    // El MISMO color que el `containerColor` del Scaffold: el toolbar deriva de él
                    // su contenedor y su indicador, así que tiene que ser el fondo REAL de la
                    // página y no un rol repetido a mano.
                    background = AppColors.surfaceContainer,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // DIALOGS
        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onDismiss = {
                    showCreatePlaylistDialog = false
                    pendingSongForNewPlaylist = null
                },
                onConfirm = { name ->
                    val pendingSong = pendingSongForNewPlaylist
                    val trimmed = name.trim()
                    libraryViewModel.createPlaylist(trimmed) { id ->
                        if (pendingSong != null) {
                            // Venimos de "agregar a lista": la lista nueva nace con la canción.
                            libraryViewModel.addSongToPlaylist(id, pendingSong)
                        } else {
                            // Venimos de la pestaña Listas: directo al detalle recién creado,
                            // que tiene el botón "añadir canciones" (antes quedaba una lista
                            // vacía sin ningún camino evidente para llenarla).
                            onPlaylistClick(id, trimmed)
                        }
                    }
                    pendingSongForNewPlaylist = null
                    showCreatePlaylistDialog = false
                }
            )
        }

        if (songIdForPlaylist != null) {
            AddToPlaylistBottomSheet(
                playlists = uiState.playlists,
                onPlaylistSelected = { playlistId ->
                    libraryViewModel.addSongToPlaylist(playlistId, songIdForPlaylist!!)
                    songIdForPlaylist = null
                },
                onCreateNewPlaylist = {
                    // Retener la canción: la lista nueva debe nacer con ella (antes se
                    // descartaba y "crear lista" desde esta hoja creaba una lista vacía).
                    pendingSongForNewPlaylist = songIdForPlaylist
                    songIdForPlaylist = null
                    showCreatePlaylistDialog = true
                },
                onDismiss = { songIdForPlaylist = null }
            )
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.common_logout)) },
                text = { Text(stringResource(R.string.logout_confirm)) },
                confirmButton = {
                    TextButton(colors = appTextButtonColors(), onClick = {
                        showLogoutDialog = false
                        onLogoutClick()
                    }) {
                        Text(stringResource(R.string.common_logout), color = AppColors.error)
                    }
                },
                dismissButton = {
                    TextButton(colors = appTextButtonColors(), onClick = { showLogoutDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Reanuda un contexto de "Seguir escuchando": obtiene sus canciones y reproduce como tal
 * (álbum/artista/lista/favoritos en cola; biblioteca en orden o aleatoria), refrescando su
 * posición en el historial. Abre el NowPlaying, como un "retomar".
 */
private fun resumeContext(
    ctx: PlaybackContext,
    scope: kotlinx.coroutines.CoroutineScope,
    browseViewModel: BrowseViewModel,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    favoriteSongs: List<Song>,
    onNavigateToNowPlaying: (PlayerArtOrigin) -> Unit
) {
    when (ctx) {
        is PlaybackContext.Album -> scope.launch {
            val songs = browseViewModel.getAlbumSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying(PlayerArtOrigin.NONE)
            }
        }
        is PlaybackContext.Artist -> scope.launch {
            val songs = browseViewModel.getArtistSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying(PlayerArtOrigin.NONE)
            }
        }
        is PlaybackContext.Genre -> scope.launch {
            val songs = browseViewModel.getGenreSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying(PlayerArtOrigin.NONE)
            }
        }
        is PlaybackContext.Playlist -> {
            libraryViewModel.playPlaylist(ctx.id)
            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
        }
        PlaybackContext.Favorites -> {
            if (favoriteSongs.isNotEmpty()) {
                libraryViewModel.recordContext(PlaybackContext.Favorites)
                playbackViewModel.playSongs(favoriteSongs, 0)
                onNavigateToNowPlaying(PlayerArtOrigin.NONE)
            }
        }
        PlaybackContext.LibraryShuffle -> {
            playbackViewModel.shuffleAllFromLibrary()
            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
        }
        PlaybackContext.LibraryAll -> {
            playbackViewModel.playAllFromLibrary()
            onNavigateToNowPlaying(PlayerArtOrigin.NONE)
        }
    }
}

/**
 * Alto de la pestaña en sí: `PrimaryNavigationTabTokens.ContainerHeight`, que es lo que mide un
 * `Tab` de M3 con contenido de una línea (`SmallTabHeight`). Es el ÁREA TÁCTIL; el dibujo de la
 * píldora es más chico ([TabPillHeight]).
 */
private val TabHeight = 48.dp

/**
 * Glifo de cada pestaña: los **24 del spec** de la navigation bar (`IconSize`), que con
 * [TabContentPadding] a los lados dejan la píldora inactiva en **48**. Sale del contenido, no se
 * fuerza ningún ancho.
 *
 * Estuvo en 20sp para que las seis pestañas entraran en 393dp sin desplazar la fila. Desde el 20 ago
 * 2026 **la fila SCROLLEA a propósito** y la geometría vuelve a ser la del spec — ver
 * [TabRowEdgePadding], donde está la decisión y su porqué.
 *
 * En **sp** como el resto de `MaterialSymbol` de la app: crece con la escala tipográfica del sistema
 * y con él el ancho de la píldora. El alto no se mueve, lo fija [TabPillHeight].
 */
internal val TabIconSize = 24.sp

/**
 * El último contenido VISIBLE del banner, retenido para que su salida tenga algo que animar. Ver el
 * sitio donde se escribe, en `LibraryScreen`.
 *
 * Plano y mutable a propósito: no es estado observable, es memoria de un frame anterior.
 */
private class LastBannerContent {
    var state: LibraryBannerState = LibraryBannerState.Hidden
    var download: DownloadBannerState? = null
}

/** Una vuelta del icono del banner en marcha: el ritmo del indicador de sincronización del sistema. */
private const val BANNER_SPIN_PERIOD_MS = 1000

/**
 * Aire entre el glifo y la etiqueta de la pestaña activa: los **4dp** del spec de M3 Expressive para
 * la navigation bar horizontal. Va SOLO delante de la etiqueta — detrás manda [TabContentPadding],
 * el mismo que a la izquierda del glifo, porque el spec de tabs pide padding consistente en cada
 * pestaña y una píldora con 12 a un lado y 16 al otro lo incumple.
 */
private val TabIconGap = 4.dp

/**
 * Padding horizontal DENTRO de la píldora, alrededor del glifo (y de la etiqueta en la activa).
 * No confundir con [TabPillGap], que es la separación ENTRE píldoras, ni con [TabRowEdgePadding],
 * que es el margen contra los bordes de la pantalla.
 *
 * **12dp desde el 22 ago 2026.** Es la palanca REAL para ceñir la fila, y la que resolvió el
 * encargo: entre dos glifos inactivos, lo que se ve es este padding por partida doble (12 + 12 =
 * 24dp, antes 32) más [TabPillGap], que apenas cuenta porque entre inactivas no hay ningún contorno
 * dibujado que se toque. Se probó el camino contrario ese mismo día —vaciar el canal, dejando el
 * padding en 16— y no era ahí: se recuperó el canal y se bajó esto.
 *
 * Los 16 que tuvo hasta hoy eran el número donde coinciden dos guías por caminos distintos: el
 * `HorizontalTextPadding` que el `Tab` de M3 aplica a su etiqueta, y lo que centra un glifo de 24
 * en el indicador de 56dp de la navigation bar ((56 − 24) / 2). A 12 la píldora inactiva pasa a
 * medir **48**, o sea el mínimo táctil exacto: [TabMinWidth] y el dibujo dejan de tener holgura
 * entre sí, y por debajo de este valor el mínimo empieza a mandar — la caja no encogería más y el
 * glifo se quedaría con más área táctil que píldora.
 *
 * De paso, la fila entera baja a ~390dp con las seis pestañas y "Canciones" activa, o sea que
 * **roza el ancho de un teléfono de 393 y casi deja de scrollear**; con una pestaña de nombre corto
 * activa, cabe.
 *
 * **El mismo a los DOS lados, sin excepciones.** El spec de tabs pide padding consistente en cada
 * pestaña; hubo un `Spacer` extra detrás de la etiqueta activa —para replicar los "20dp tras el
 * label" de la navigation bar— que dejaba la píldora con 12 a un lado y 16 al otro, y se quitó.
 *
 * Ya estuvo en 12 antes, cuando el objetivo era que las seis entraran sin scroll; ver
 * [TabRowEdgePadding].
 */
private val TabContentPadding = 12.dp

/**
 * Aire a los lados de la píldora de cada pestaña: la mitad de la separación real entre dos píldoras
 * contiguas, porque cada una pone la suya. Ojo si se toca — este padding recorta el área táctil de
 * la pestaña (va antes del `selectable`), así que en vertical no se pone ninguno, y en horizontal
 * cada dp de más son 6dp de fila con las seis pestañas.
 *
 * **2dp, o sea 4dp de canal** — el valor histórico, restaurado el 22 ago 2026 tras bajarlo a 1 y a 0
 * el mismo día y verlo en device. Lo que resolvió el aire de la fila no fue este número sino
 * [TabContentPadding], que bajó de 16 a 12; con eso hecho, el canal volvió a su sitio.
 *
 * **Lo que se toca al vaciarlo son las CAJAS, y las cajas no se ven.** Una pestaña inactiva no
 * dibuja contorno: su píldora está en alpha 0 y lo único pintado es el glifo, centrado en sus
 * [TabContentPadding] de cada lado. Por eso este canal casi no se nota entre dos inactivas —lo que
 * las separa es el padding, dos veces— y por eso ponerlo a cero no pegaba nada a la vista. **No
 * razonar sobre esta fila como si las seis píldoras estuvieran dibujadas**: solo lo está la activa,
 * y contra ella sí se ve este canal. Lo otro que se roza al vaciarlo es el **ripple**, que se pinta
 * con la forma de la píldora y cubre la caja entera, también en las inactivas.
 *
 * De aquí DERIVAN otros dos números, así que se toca uno y cambian tres: el `edgePadding` de la fila
 * ([TabRowEdgePadding] menos este canal, para que el borde VISIBLE siga cayendo en 16) y
 * [TabMinWidth].
 *
 * **Vale para TODAS las pestañas, no solo para las inactivas**, y eso es deliberado: un canal que
 * dependiera del estado cambiaría el ancho de cada celda al seleccionar, así que la fila entera se
 * recolocaría bajo el dedo — y ya se mueve lo suyo con la etiqueta que aparece.
 */
private val TabPillGap = 2.dp

/**
 * Alto de la píldora: **40dp**, el indicador de la variante HORIZONTAL de la navigation bar de M3
 * Expressive (la de icono y etiqueta en línea, que es la forma de la pestaña activa). La app es un
 * híbrido de las dos variantes del spec: de la horizontal salen este alto, el [TabIconGap] de 4 y
 * los 20 de detrás de la etiqueta; de la VERTICAL, la idea de un indicador ancho para las inactivas,
 * que son solo glifo — ancho que sale del contenido sin forzar nada ([TabIconSize] 24 + 2×
 * [TabContentPadding]).
 *
 * Ese ancho fue **56** —el número que el spec vertical tabula para el indicador— mientras el padding
 * valía 16; desde el 22 ago 2026 son **48**, por decisión en device de ceñir la fila. La IDEA del
 * spec se conserva (un contenedor ancho, no un círculo alrededor del glifo); el número, no.
 *
 * Es solo el DIBUJO: el `Tab` que lo contiene conserva sus 48dp de alto, que son el área táctil.
 * Por eso [TabRowBottomPadding] no vale 12 sino 8 — ver ahí.
 */
private val TabPillHeight = 40.dp

/**
 * Dónde nace la primera píldora (y dónde acaba la última) respecto al borde de la pantalla: **16dp,
 * el margen del RESTO de la columna** (barra de búsqueda, tarjetas, MiniPlayer), o sea la rejilla de
 * la pantalla.
 *
 * **NO los 52 de `ScrollableTabRowEdgeStartPadding`.** Es la keyline que el spec da para una fila
 * scrollable, se probó en device el 20 ago y se descartó: en una fila cuyas pestañas inactivas son
 * píldoras de solo glifo, ese hueco de 52dp no se lee como una keyline sino como un vacío al empezar
 * — y encima desalinea la primera píldora respecto a todo lo que tiene encima y debajo, que va a 16.
 * La keyline está pensada para tabs de TEXTO alineadas a la rejilla tipográfica, no para esto.
 *
 * Se consume como `edgePadding = TabRowEdgePadding - TabPillGap` a propósito: cada píldora pone su
 * propio aire de [TabPillGap], así que al `TabRow` hay que pedirle esa cantidad de MENOS para que el
 * borde VISIBLE quede donde dice la constante.
 */
private val TabRowEdgePadding = 16.dp

/**
 * Aire entre la fila de pestañas y el corte inferior del bloque: **6dp**, para un aire VISIBLE de
 * 10 — los otros 4 los aporta ya el propio `Tab`, que mide [TabHeight] con una píldora de
 * [TabPillHeight] centrada dentro. Es el mismo criterio que [TabRowEdgePadding] en horizontal: la
 * constante describe el margen visible menos lo que el componente ya pone.
 *
 * **Por debajo de los 12 visibles del spec**, y a propósito (device, 20 ago 2026): con el bloque
 * teñido, ese aire de más se lee como una banda vacía bajo las pestañas. El aire que el spec deja
 * ahí dentro se recuperó FUERA del bloque, entre su corte y el contenido — ver [HeaderContentGap].
 *
 * Recorrido en device: 16 → 8 → 0 → 4 → 6 → 8 → 6.
 */
private val TabRowBottomPadding = 6.dp

/**
 * Alto total de [LibraryTabsRow]: la pestaña más el aire teñido de abajo. Las listas del pager lo
 * reservan como contentPadding (el contenido pasa por debajo del bloque).
 *
 * **DERIVADO a propósito.** Estuvo escrito a mano (64dp = 48 + 16) y se quedó desfasado en cuanto
 * el aire inferior bajó de 16: el KDoc seguía diciendo "+16" y las listas reservaban 10dp de más,
 * un hueco fantasma que no fallaba en compilación ni se veía como bug, solo como contenido que
 * empieza más abajo de lo que debe.
 *
 * **Declarado DESPUÉS de [TabRowBottomPadding] a propósito**: las propiedades top-level se
 * inicializan en orden de declaración, así que puesto antes leería un 0 y este alto saldría corto
 * en runtime, sin error de compilación.
 */
internal val TabsRowHeight = TabHeight + TabRowBottomPadding

/**
 * Aire entre el corte del bloque de cabecera y el contenido de la página. Lo reservan las listas del
 * pager como `contentPadding` superior, así que el contenido SIGUE pasando por debajo del bloque al
 * scrollear — esto solo decide dónde arranca en reposo.
 *
 * **16dp, el mismo margen que el resto de la columna** (barra de búsqueda, tarjetas, MiniPlayer), o
 * sea la rejilla de la pantalla y no un número propio. Estuvo en 4dp mientras el bloque llevaba 16
 * de aire interno: entre los dos daban ~20 y el reparto quedaba dentro del área teñida. Al ceñir el
 * bloque a sus pestañas ([TabRowBottomPadding]) ese aire tenía que reaparecer aquí, o el título de
 * la página nace pegado al corte.
 *
 * Ojo con la trampa que hubo en medio: mientras [TabsRowHeight] estuvo escrito a mano en 64 —10dp
 * más de lo que el bloque medía— ese hueco fantasma hacía de aire, así que al corregirlo el
 * contenido subió de golpe. El aire ahora es explícito y el alto es derivado; ninguno de los dos
 * hace el trabajo del otro.
 */
private val HeaderContentGap = 16.dp

/**
 * Ancho mínimo que el `PrimaryScrollableTabRow` reserva por pestaña: los **48dp del mínimo táctil**.
 * Es un piso de ÁREA, no el ancho del dibujo.
 *
 * **Desde el 22 ago 2026 el piso y el dibujo EMPATAN**, y por eso esta derivación es la correcta:
 * con [TabContentPadding] en 12 la píldora inactiva mide exactamente 48 ([TabIconSize] 24 + 2×12),
 * y la CAJA que la contiene mide 48 + 2×[TabPillGap] = 52, que es justo lo que devuelve esta
 * fórmula. El mínimo dejó de tener holgura. Bajar más el padding ya no encoge la fila: la caja se
 * queda en su piso y lo único que pasa es que el glifo gana área táctil por fuera de su píldora.
 */
private val TabMinWidth = 48.dp + TabPillGap * 2

/**
 * Navegación de la biblioteca bajo la cabecera de búsqueda: [PrimaryScrollableTabRow] de Material 3
 * con el look de píldora que tenía la botonera anterior — inactiva = solo glifo, activa = glifo +
 * etiqueta dentro de un contenedor tonal.
 *
 * Sustituyó al connected button group (`ButtonGroup` + `ToggleButton`), que era un componente de
 * SELECCIÓN entre opciones puesto a hacer de navegación. Lo que se gana con el componente real:
 * semántica de pestaña, scroll automático hasta la activa, y que el ancho de cada una lo decida su
 * contenido en vez de un reparto por `weight` que obligaba a recortar el padding interno a 4dp
 * para que el glifo cupiera en 360dp.
 *
 * **Siempre la variante scrollable**, sin caso especial para pocas pestañas: la fija reparte el
 * ancho a partes iguales, así que con las inactivas a solo icono las dejaría como píldoras enormes
 * con un glifo perdido en el centro. Con la scrollable cada pestaña mide lo suyo; si caben todas,
 * simplemente no hay scroll.
 *
 * El slot `indicator` queda VACÍO a propósito y la píldora se pinta en cada pestaña — el porqué
 * está en [LibraryTabs].
 */
// El `indicator` de esta fila recibe un `TabIndicatorScope`, que sigue tras la puerta experimental
// en esta versión de Material 3, aunque aquí se le pase un lambda vacío.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabsRow(
    /** Pestañas visibles, ya en el orden del usuario. Nunca vacía (ver el caller). */
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    // getOrNull + coerceIn: al ocultar la pestaña activa desde Ajustes, el pager recompone con el
    // índice viejo un frame antes de que el LaunchedEffect lo corrija — y `PrimaryTabRow` indexa
    // su lista de posiciones con él, así que un índice fuera de rango revienta.
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Color compartido con la cabecera: las dos son UNA pieza, la navigation bar de la
            // pantalla puesta arriba (ver `headerColor`).
            //
            // Aire bajo la fila: **6dp**, para 10 visibles con los 4 que aporta el propio `Tab`
            // (ver [TabRowBottomPadding]). Los 16dp que tuvo desde el 17 jul venían de cuando el
            // bloque iba del MISMO color que el fondo y ese aire era invisible; al ganar color
            // propio se convirtió en una banda teñida y vacía.
            .background(containerColor)
            // La fila vive SIEMPRE arriba, bajo la búsqueda: el header ya se comió el inset de la
            // barra de estado y el aire propio queda del lado que mira al contenido. Tuvo los dos
            // como parámetros mientras el modo "pestañas abajo" la reusaba en el `bottomBar`; con
            // el toolbar flotante ese caller desapareció y con él la razón de que fueran variables.
            .padding(bottom = TabRowBottomPadding)
    ) {
        // `containerColor = Color.Transparent`: el tinte ya lo pinta el Box de arriba, que es
        // quien comparte el color con la TopBar. Si además lo pintara la fila, el degradado de
        // elevación se aplicaría dos veces.
        PrimaryScrollableTabRow(
            selectedTabIndex = safeIndex,
            containerColor = Color.Transparent,
            // Margen contra los bordes de la pantalla, menos el aire propio de la píldora para que
            // lo que quede alineado sea el borde del CONTENEDOR y no el del glifo.
            edgePadding = TabRowEdgePadding - TabPillGap,
            // OBLIGATORIO bajarlo: el default de la fila scrollable es
            // `TabRowDefaults.ScrollableTabRowMinTabWidth` = 90dp, y con las inactivas a solo
            // icono eso las dejaría como píldoras de 90dp con un glifo perdido en el centro y
            // la fila enorme. Bajándolo, cada pestaña mide lo que mide su contenido y solo la
            // activa se ensancha; el valor sigue ahí como red del mínimo táctil.
            minTabWidth = TabMinWidth,
            indicator = {},
            divider = {}
        ) {
            LibraryTabs(tabs, safeIndex, onTabSelected)
        }
    }
}

/**
 * Las pestañas en sí. Conservan el look de la botonera que hubo antes —**inactiva = solo glifo,
 * activa = glifo + etiqueta dentro de una píldora**— montado sobre `Tab`, así que hay comportamiento
 * de pestaña (selección, semántica, scroll hasta la activa) y no un componente de selección de
 * opciones haciendo de navegación.
 *
 * **Todo el contenido va en el slot `icon`, con `text = null`**, y no repartido entre los dos
 * slots. `Tab` coloca text+icon con `TabBaselineLayout`, que alinea el texto por su BASELINE y
 * reserva su sitio; con la etiqueta apareciendo y desapareciendo eso da saltos verticales. Con
 * solo el slot de icono, el layout centra el contenido y la fila del glifo + etiqueta se gobierna
 * aquí, que es justo lo que hace falta para que la etiqueta crezca en horizontal sin mover nada.
 *
 * **La píldora se pinta en el modifier de cada pestaña, no en el slot `indicator` de la fila**
 * (que por eso queda vacío). No es una preferencia: `TabRow` coloca el indicador DESPUÉS de las
 * pestañas en su `SubcomposeLayout`, o sea que se dibuja ENCIMA — vale para una barrita al borde
 * inferior, pero un contenedor relleno taparía el contenido. Como fondo de la pestaña queda detrás
 * por construcción, y de paso el `clip` que va antes es lo que hace que el **ripple del pressed
 * salga con forma de píldora** también en las inactivas.
 *
 * Consecuencia asumida: la píldora aparece en su sitio en vez de deslizarse, así que el indicador
 * ya no sigue el arrastre del pager (solo salta al soltar).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryTabs(
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    tabs.forEachIndexed { index, tab ->
        val selected = index == selectedIndex
        // OJO con el destino de la animación: NO es `Color.Transparent`, que es negro transparente
        // (0x00000000). Interpolar desde `secondaryContainer` hasta él arrastra el RGB hacia el
        // negro mientras baja el alpha, y eso es el DESTELLO oscuro que se veía al cambiar de
        // pestaña. Con el mismo color a alpha 0 la interpolación se queda en su propio tono y solo
        // se desvanece.
        val pillColor by animateColorAsState(
            targetValue = AppColors.secondaryContainer.copy(alpha = if (selected) 1f else 0f),
            animationSpec = appEffectsSpec(),
            label = "tabPill"
        )
        // Compartido con el ripple que pinta la píldora: el `Tab` recoge las interacciones y la
        // píldora las dibuja. Sin pasarlo, `Tab` crearía el suyo y el ripple de abajo no se enteraría
        // de nada.
        val interactionSource = remember { MutableInteractionSource() }
        // `LocalRippleConfiguration provides null` APAGA el ripple de `Tab` (es el mecanismo que M3
        // documenta para eso). Solo cubre al `Tab`; el de la píldora se declara dentro y no lo lee,
        // porque `indication(...)` recibe la instancia directamente.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            Tab(
                selected = selected,
                onClick = {
                    if (!selected) {
                        // Tick de segmento: el háptico del spec para moverse dentro de un grupo
                        // (LongPress sería un golpe de más).
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onTabSelected(index)
                    }
                },
                // EXPLÍCITOS, no los defaults: `Tab` define `unselectedContentColor` como "lo mismo
                // que el seleccionado", y el contentColor que hereda de `PrimaryTabRow` es `primary`
                // — o sea que sin esto las seis pestañas se pintarían del color de la activa y ninguna
                // se leería como inactiva. La activa va sobre la píldora, así que su color es el `on-`
                // del contenedor, no el acento suelto.
                selectedContentColor = AppColors.onSecondaryContainer,
                unselectedContentColor = AppColors.onSurfaceVariant,
                text = null,
                icon = {
                    // **La píldora se dibuja AQUÍ, en el contenido, no en el modifier del `Tab`.** Eso
                    // es lo que permite que mida [TabPillHeight] (40dp, el indicador de la navigation
                    // bar horizontal del spec) mientras el `Tab` conserva sus 48dp de alto: el
                    // `selectable` lo aplica `Tab` a su propio nodo, así que un `padding` vertical por
                    // fuera recortaría el ÁREA TÁCTIL por debajo del mínimo de 48. Es el mismo reparto
                    // que hace M3 en la navigation bar, donde el touch target es el ITEM entero y el
                    // indicador es solo el dibujo.
                    //
                    // **Y por eso el RIPPLE también se dibuja aquí** (`indication` con el
                    // `interactionSource` que se le pasa al `Tab`, mientras el suyo va apagado con
                    // `LocalRippleConfiguration provides null`). `Tab` pone su `selectable` DESPUÉS del
                    // modifier externo, así que su ripple cubre el nodo entero: 48dp de alto y sin
                    // recortar, o sea una mancha rectangular alrededor de una píldora de 40. Antes no se
                    // veía porque el `clip(CircleShape)` vivía en ese modifier externo y lo recortaba;
                    // al bajar la píldora a 40 el clip se vino aquí y el ripple se quedó suelto. Lo que
                    // se anima es el CHIP, no su contenedor.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(TabPillHeight)
                            .clip(CircleShape)
                            .background(pillColor)
                            .indication(interactionSource, ripple())
                            .padding(horizontal = TabContentPadding)
                    ) {
                        MaterialSymbol(tab.iconName, size = TabIconSize, fill = selected)
                        // La etiqueta solo en la activa.
                        //
                        // El ancho va con un spec de EFFECTS —crítico, sin rebote— y no con el spatial
                        // que tenía, y aquí el motivo no es estético sino de FRAMES. `expandHorizontally`
                        // anima el TAMAÑO, así que cada frame es una pasada de LAYOUT, no un repintado; y
                        // no una local: al cambiar de ancho una pestaña, el `PrimaryScrollableTabRow`
                        // recalcula el reparto de todas, la posición del indicador y el scroll, y el
                        // `Text` de dentro se vuelve a medir. Con `appFastSpatialSpec()` —el token que
                        // MÁS rebota (dampingRatio 0.6)— la píldora oscilaba alrededor de su ancho final
                        // un buen rato, y cada oscilación era otra pasada completa de eso: el rebote
                        // multiplicaba el trabajo caro justo mientras el `HorizontalPager` componía la
                        // página nueva. Se veía como pérdida de fps al cambiar de pestaña.
                        //
                        // Regla general que sale de aquí: **un spec que rebota sobre algo que EMPUJA
                        // LAYOUT sale caro**. Sobre un `graphicsLayer` (posición, escala, alpha) el
                        // rebote es gratis; sobre un tamaño, no.
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(
                                animationSpec = appEffectsSpec()
                            ) + fadeIn(
                                animationSpec = appEffectsSpec()
                            ),
                            exit = shrinkHorizontally(
                                animationSpec = appFastEffectsSpec()
                            ) + fadeOut(
                                animationSpec = appFastEffectsSpec()
                            )
                        ) {
                            // Un solo spacer, ANTES de la etiqueta. Hubo otro detrás —para replicar los
                            // "20dp tras el label" de la navigation bar— y se quitó: dejaba la píldora
                            // con 12dp a la izquierda y 16 a la derecha, y el spec de tabs pide
                            // explícitamente padding CONSISTENTE en cada pestaña. El aire de los dos
                            // lados lo pone [TabContentPadding], igual para todas.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(TabIconGap))
                                Text(
                                    text = stringResource(tab.titleRes),
                                    style = MaterialTheme.typography.labelLargeEmphasized,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                },
                // Solo separación HORIZONTAL: este modifier va ANTES del `selectable` que añade `Tab`,
                // así que cualquier inset vertical recortaría el área táctil. El aire vertical de la
                // píldora sale de que ésta mide 40 dentro de un `Tab` de 48 (ver el slot `icon`).
                interactionSource = interactionSource,
                modifier = Modifier.padding(horizontal = TabPillGap)
            )
        }
    }
}

// --- Helpers (Banner, TopBars) ---

/**
 * Contenedor común de los banners de sync, M3 Expressive: tarjeta tonal SÓLIDA del scheme
 * (nada de colores hardcodeados — así siguen el tema seedeado del álbum), esquinas grandes
 * y padding generoso. El icono va en un círculo del acento para dar jerarquía.
 */
@Composable
private fun BannerCard(
    icon: String,
    iconContainer: Color,
    containerColor: Color,
    contentColor: Color,
    /**
     * ¿El icono GIRA? Para los estados en curso, como el indicador de sincronización del sistema: un
     * glifo quieto no distingue "buscando cambios" de "aquí tienes el resultado", y el banner pasa por
     * los dos. La onda de progreso ya dice que algo pasa, pero está abajo y el ojo va al icono.
     */
    iconSpinning: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val baseModifier = Modifier
        .fillMaxWidth()
        // Asimétrico a propósito: 12dp arriba (aire respecto al bloque del header) y 0 abajo
        // — el hueco inferior lo pone el `+ 12.dp` del listInsets, así el banner queda a
        // 12/12 de header y lista (antes 4 arriba / 16 abajo, se veía descolgado).
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp)
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSurface(shape = CircleShape, color = iconContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    // Giro continuo con `rememberInfiniteTransition`, que en cualquier otro sitio de
                    // esta app sería un error (ver "CERO productores continuos" en la sección Motion
                    // de CLAUDE.md). Aquí es legítimo por las dos razones que hacen legítimo al
                    // shimmer: **solo existe mientras hay trabajo** —el banner se retira al terminar—
                    // y **cada frame mueve algo**. Lo segundo es lo que lo separa de la cookie del
                    // play, que se acotó a publicar solo al desplazarse un píxel: aquélla giraba
                    // 0,17°/frame (un tercio de píxel, invisible), y ésta gira ~3°/frame en un glifo
                    // de 20sp, o sea varios píxeles. Recortar por píxel aquí no ahorraría un solo
                    // frame.
                    val spin = if (iconSpinning) {
                        val transition = rememberInfiniteTransition(label = "bannerIconSpin")
                        transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                // LINEAL y no un token del scheme: una rotación continua no acelera
                                // ni frena, o se vería un tirón en cada vuelta. Un giro por segundo
                                // es el ritmo del indicador de sincronización del sistema, que es la
                                // referencia que el usuario ya conoce.
                                animation = tween(BANNER_SPIN_PERIOD_MS, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "bannerIconAngle"
                        )
                    } else null
                    MaterialSymbol(
                        icon,
                        color = onContainerColor(iconContainer),
                        size = 20.sp,
                        fill = true,
                        // Lectura DIFERIDA dentro del `graphicsLayer`: el ángulo cambia en cada frame
                        // y leerlo en composición recompondría el banner entero a 60fps.
                        modifier = spin?.let { angle ->
                            Modifier.graphicsLayer { rotationZ = angle.value }
                        } ?: Modifier
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
    if (onClick != null) {
        AppSurface(onClick = onClick, color = containerColor, contentColor = contentColor, shape = shape, modifier = baseModifier) { row() }
    } else {
        AppSurface(color = containerColor, contentColor = contentColor, shape = shape, modifier = baseModifier) { row() }
    }
}

// Paletas SEMÁNTICAS fijas de los banners (jerarquía natural: azul = descargando,
// verde = ok, rojo = error). A propósito NO usan el scheme seedeado del álbum: el color
// del banner comunica estado, no estética.
@Immutable
private data class BannerPalette(val container: Color, val accent: Color)

@Composable
private fun bannerBlue() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF1E1E1E), Color(0xFF64B5F6)) else BannerPalette(Color(0xFFE3F2FD), Color(0xFF1976D2))

@Composable
private fun bannerGreen() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF1E1E1E), Color(0xFF81C784)) else BannerPalette(Color(0xFFE8F5E9), Color(0xFF2E7D32))

@Composable
private fun bannerRed() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF3E1E1E), Color(0xFFE57373)) else BannerPalette(Color(0xFFFFEBEE), Color(0xFFC62828))

// Ámbar y no rojo: quedarse sin WiFi no es un fallo de la app ni pide nada al usuario. Con la
// paleta de error, un banner que solo dice "esto sigue solo cuando vuelvas a WiFi" se lee como
// algo que hay que ir a arreglar.
@Composable
private fun bannerAmber() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF2A2114), Color(0xFFFFB74D)) else BannerPalette(Color(0xFFFFF3E0), Color(0xFFE65100))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadSummaryBanner(active: Int, completed: Int, total: Int, failed: Int, onClick: () -> Unit) {
    val palette = bannerBlue()
    BannerCard(
        icon = "download",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent,
        onClick = onClick
    ) {
        val failedText = if (failed > 0) stringResource(R.string.sync_with_errors_suffix, failed) else ""
        Text(
            stringResource(R.string.sync_downloading),
            style = MaterialTheme.typography.titleSmallEmphasized
        )
        Text(
            stringResource(R.string.sync_progress, completed, total, failedText),
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA)
        )
        Spacer(Modifier.height(10.dp))
        // Onda expressive determinada: el progreso "vivo" de la descarga.
        LinearWavyProgressIndicator(
            progress = { if (total > 0) completed.toFloat() / total else 0f },
            color = palette.accent,
            trackColor = palette.accent.copy(alpha = ACCENT_TRACK_ALPHA),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    val palette = bannerRed()
    BannerCard(
        icon = "error",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent,
        onClick = onRetry
    ) {
        Text(
            stringResource(R.string.sync_error),
            style = MaterialTheme.typography.titleSmallEmphasized
        )
        Text(
            stringResource(R.string.sync_error_retry, message),
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA),
            maxLines = 2
        )
    }
}

@Composable
private fun SyncCompleteBanner(newSongs: Int, downloaded: Int, failed: Int, deleted: Int = 0) {
    val newText = stringResource(R.string.sync_new, newSongs)
    val downloadedText = stringResource(R.string.sync_downloaded, downloaded)
    val deletedText = stringResource(R.string.sync_deleted, deleted)
    val noChangesText = stringResource(R.string.sync_no_changes)
    val failedSuffix = stringResource(R.string.sync_failed_suffix, failed)
    val text = buildString {
        val parts = mutableListOf<String>()
        if (newSongs > 0) parts.add(newText)
        if (downloaded > 0) parts.add(downloadedText)
        if (deleted > 0) parts.add(deletedText)
        if (parts.isNotEmpty()) append(parts.joinToString(" · "))
        else append(noChangesText)
        if (failed > 0) append(failedSuffix)
    }
    val hasIssues = failed > 0
    val palette = if (hasIssues) bannerRed() else bannerGreen()
    BannerCard(
        icon = if (hasIssues) "warning" else "check_circle",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_up_to_date),
            style = MaterialTheme.typography.titleSmallEmphasized
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA),
            maxLines = 1
        )
    }
}

/**
 * Fase de preparación: la app está trabajando pero todavía no bajando audio. El TÍTULO es el
 * nombre de la fase (no un rótulo fijo), porque el propósito del banner aquí es justamente decir
 * cuál de todas está corriendo — antes estas fases no publicaban nada y el banner se quedaba en
 * "Escaneando biblioteca" durante minutos, que es indistinguible de haberse colgado.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrepareProgressBanner(current: Int, total: Int, message: String) {
    val palette = bannerGreen()
    BannerCard(
        icon = "info",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1
        )
        if (total > 0) {
            Text(
                stringResource(R.string.sync_progress, current, total, ""),
                style = MaterialTheme.typography.bodySmall,
                color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA)
            )
        }
        Spacer(Modifier.height(10.dp))
        // Determinada cuando la fase sabe cuánto le queda; indeterminada cuando no, en vez de
        // dibujar una barra clavada en cero que parecería otra vez que no avanza.
        if (total > 0) {
            LinearWavyProgressIndicator(
                progress = { current.toFloat() / total },
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = ACCENT_TRACK_ALPHA),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearWavyProgressIndicator(
                color = palette.accent,
                trackColor = palette.accent.copy(alpha = ACCENT_TRACK_ALPHA),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * Cola de descargas detenida por el entorno. SIN acción y sin barra de progreso: no hay nada
 * que reintentar (se reanuda sola en cuanto vuelva la condición) y no hay avance que mostrar.
 */
@Composable
private fun SyncPausedBanner(message: String) {
    val palette = bannerAmber()
    BannerCard(
        icon = "pause_circle",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_paused),
            style = MaterialTheme.typography.titleSmallEmphasized
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA),
            maxLines = 2
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanProgressBanner(count: Int, message: String) {
    val palette = bannerGreen()
    BannerCard(
        // `sync` girando en vez de la lupa quieta: es el glifo que el sistema usa para lo mismo, y
        // el movimiento distingue "buscando" de "terminado" sin leer el texto.
        icon = "sync",
        iconSpinning = true,
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_scanning),
            style = MaterialTheme.typography.titleSmallEmphasized
        )
        Text(
            if (count > 0) stringResource(R.string.sync_found, count, message) else message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = ACCENT_SECONDARY_ALPHA),
            maxLines = 1
        )
        Spacer(Modifier.height(10.dp))
        // Onda expressive indeterminada mientras se recorre el delta.
        LinearWavyProgressIndicator(
            color = palette.accent,
            trackColor = palette.accent.copy(alpha = ACCENT_TRACK_ALPHA),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * Cabecera de la biblioteca: search bar DOCKED real de M3 (la píldora ES el ancla del
 * search view expandido — `SearchBar(state, inputField)` registra sus collapsedCoords y
 * el tap del input dispara la expansión, sin `onSearchClick` manual). Sin título ni
 * TopAppBar: la píldora activa de las tabs ya dice dónde estás. Visible en TODAS las
 * pestañas, incluida Listas. Ordenar (solo Todas) y el overflow van DENTRO de la píldora
 * como trailing icons del spec — el trailing lo arma `searchInputField` en el caller.
 *
 * El color del bloque lo gobierna el caller (el mismo que la fila de tabs: son una sola pieza).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibrarySearchHeader(
    searchBarState: SearchBarState,
    searchInputField: @Composable () -> Unit,
    searchBarColors: SearchBarColors,
    containerColor: Color,
    /**
     * Lo que hay que apartar del borde final: en horizontal, la perforación de pantalla y la barra
     * del sistema. Va como parámetro y no en el [modifier] porque se aplica DESPUÉS del
     * `background`: el tinte del bloque tiene que llegar hasta el borde y es el CONTENIDO el que
     * se aparta. El inset del otro lado sí viaja en el modifier, y a propósito — ahí el fondo no
     * debe invadir la columna del rail, que se dibuja debajo de esta cabecera.
     */
    contentEndPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // background ANTES del statusBarsPadding: el tinte del header pinta también
            // detrás de la barra de estado (antes lo hacía el TopAppBar con sus insets).
            .background(containerColor)
            .statusBarsPadding()
            .padding(end = contentEndPadding)
            // bottom generoso: la píldora quedaba pegada a la píldora activa de las tabs.
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        // SIN `shadowElevation`: se deja el default de Compose (Level0), que se aparta del
        // `SearchBarTokens.ContainerElevation` del spec (Level 3). Se implementó ese Level 3 el
        // 20 ago y se quitó el mismo día en device — el porqué, junto a `headerItemColor`.
        //
        // **La píldora tiene TOPE de ancho** (27 ago 2026): girado se estiraba a los ~790dp que
        // deja el rail, un campo de texto de una línea ocupando toda la pantalla. El tope es el
        // mismo que el del resto del contenido ([ComponentConfig.ContentMaxWidth]).
        //
        // `widthIn(max)` + `fillMaxWidth()` dentro de un `Box`, y no `weight(1f)` en una `Row`:
        // así el ancho es `min(disponible, tope)`, o sea **exactamente el de antes en vertical**
        // (donde disponible < tope) y el tope girado. Con `weight` las constraints llegan FIJAS y
        // el `widthIn` de dentro se pelearía con ellas.
        SearchBar(
            state = searchBarState,
            inputField = searchInputField,
            colors = searchBarColors,
            modifier = Modifier
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
        )
    }
}

/**
 * Overflow del header (Estadísticas / Ajustes / cerrar sesión): trailing icon de la píldora de
 * búsqueda. **Su número de items es variable**, así que las formas salen de [menuItemShapeAt] y no
 * de `leading`/`trailing` escritos a mano.
 */
@Composable
private fun LibraryOverflowButton(
    isLoggedIn: Boolean,
    accountPhotoPath: String?,
    accountInitial: String?,
    showStatsEntry: Boolean,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    // El botón de nueva lista NO va aquí: vive sobre Favoritos, en la cabecera de la pestaña
    // Listas (`PlaylistList`). (Estuvo en un FAB contextual, que ya no existe.)
    Box {
        // Con cuenta de Microsoft: AVATAR (foto de perfil o inicial) que abre el MENÚ. En solo-local
        // no hay cuenta ni logout, así que si tampoco hay que reponer Estadísticas el único destino
        // es Ajustes: el botón muestra el engranaje y NAVEGA DIRECTO — abrir un menú de un solo ítem
        // "Ajustes" (otro engranaje) para llegar a Ajustes era un paso vacío. La condición del menú
        // es por tanto "hay MÁS de un destino", no "hay sesión". Sin color explícito: la píldora es
        // neutra, así que el icono hereda el contenido por defecto del input.
        val hasMenu = isLoggedIn || showStatsEntry
        IconButton(onClick = { if (hasMenu) showOverflowMenu = true else onSettingsClick() }) {
            when {
                isLoggedIn -> AccountAvatar(photoPath = accountPhotoPath, initial = accountInitial)
                // Sin sesión pero CON menú: un engranaje que despliega destinos que no son Ajustes
                // prometería lo que no es, así que el glifo pasa a ser el overflow genérico.
                hasMenu -> MaterialSymbol("more_vert")
                else -> MaterialSymbol("settings")
            }
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        AppMenuPopup(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false }
        ) {
            AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                // Estadísticas PRIMERO y solo con Inicio oculta: es el repuesto de la tarjeta del
                // inicio, que es su única puerta (ver [LibraryViewModel.homeTabHidden]). Con Inicio
                // visible serían dos controles para lo mismo, y éste es el que sobra: aquélla
                // además muestra un DATO.
                val itemCount = 1 + (if (showStatsEntry) 1 else 0) + (if (isLoggedIn) 1 else 0)
                val settingsIndex = if (showStatsEntry) 1 else 0
                if (showStatsEntry) {
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
                        onClick = { showOverflowMenu = false; onStatsClick() },
                        text = { Text(stringResource(R.string.stats_title)) },
                        shape = menuItemShapeAt(0, itemCount),
                        leadingIcon = { MenuItemIcon("bar_chart") }
                    )
                }
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = { showOverflowMenu = false; onSettingsClick() },
                    text = { Text(stringResource(R.string.settings_title)) },
                    shape = menuItemShapeAt(settingsIndex, itemCount),
                    leadingIcon = { MenuItemIcon("settings") }
                )
                if (isLoggedIn) {
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
                        onClick = { showOverflowMenu = false; onLogoutClick() },
                        text = { Text(stringResource(R.string.common_logout)) },
                        shape = menuItemShapeAt(itemCount - 1, itemCount),
                        leadingIcon = { MenuItemIcon("logout") }
                    )
                }
            }
        }
    }
}

/**
 * Contenido del contenedor de búsqueda expandido. Reusa [SongsScreen] en su modo búsqueda
 * (carruseles de artistas/álbumes que matchean + canciones del paging LIKE), que antes se
 * renderizaba dentro de la pestaña "Todas".
 *
 * Con la query vacía no dibuja nada: el diálogo se abre mostrando solo el input, no la
 * biblioteca entera (el paging con query en blanco devuelve TODAS las canciones).
 *
 * Toda navegación colapsa primero ([onCollapseAnd]): el destino y las hojas modales viven en
 * la ventana de abajo y quedarían tapados por el diálogo.
 */
@Composable
private fun SearchResults(
    searchQuery: String,
    searchArtists: List<ArtistSummary>,
    searchAlbums: List<AlbumSummary>,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    onAddToPlaylistRequest: (String) -> Unit,
    onCollapseAnd: (() -> Unit) -> Unit,
    onNavigateToNowPlaying: (PlayerArtOrigin) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    if (searchQuery.isBlank()) return

    SongsScreen(
        currentFilter = SongFilter.ALL,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
        ),
        // NONE y no ROW: la fila que el usuario tocó vive en el diálogo, que se cierra antes de
        // navegar, y la de la lista de abajo solo existe si esa canción cae en su viewport — un
        // origen que puede no estar deja la carátula quieta en el overlay. Sube con el contenido.
        onNavigateToNowPlaying = { onCollapseAnd { onNavigateToNowPlaying(PlayerArtOrigin.NONE) } },
        onAddToPlaylistRequest = { songId -> onCollapseAnd { onAddToPlaylistRequest(songId) } },
        viewModel = libraryViewModel,
        playbackViewModel = playbackViewModel,
        isSearchActive = true,
        searchQuery = searchQuery,
        searchArtists = searchArtists,
        searchAlbums = searchAlbums,
        onSearchArtistClick = { name -> onCollapseAnd { onArtistClick(name) } },
        onSearchAlbumClick = { name -> onCollapseAnd { onAlbumClick(name) } }
    )
}

