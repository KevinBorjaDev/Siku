package com.qhana.siku.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarColors
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.LocalSnackbarHostState
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.theme.AppSnackbar
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appSpatialSpec
import kotlinx.coroutines.launch

/*
 * La búsqueda de la biblioteca, extraída de `LibraryScreen` para que la puedan montar TAMBIÉN las
 * pantallas de categoría del estilo moderno (Canciones, Artistas, Álbumes y Géneros a pantalla
 * completa). Sin esto, entrar en una categoría dejaría al usuario sin buscador justo en la lista
 * donde más se busca.
 *
 * **Es un MOVIMIENTO, no una reescritura.** Todo lo que había allí es load-bearing y se conserva
 * palabra por palabra: el spec de motion sin rebote, el `containerColor` pisado SOLO en colapsado,
 * el cruce lupa↔flecha por `progress > 0.5f`, el `BackHandler` dentro del contenido del diálogo y
 * el segundo `SnackbarHost`. Los tres últimos "compilan igual" si se pierden y solo se ven en
 * device, así que van con su porqué al lado.
 */

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
 * Escala de partida/llegada del cruce lupa↔flecha del leading icon. El glifo no nace en 0:
 * se encoge lo justo para que el cambio se lea como un relevo y no como un icono que aparece
 * de la nada. Simétrico entrada/salida a propósito.
 */
private const val IconCrossfadeScale = 0.7f

/**
 * Aire entre el botón de atrás y la píldora cuando la cabecera lleva navegación: el mismo que M3
 * deja entre dos `IconButton` contiguos. No es cero porque el botón ya trae su área táctil de 48dp
 * y sin separación la píldora se lee pegada a ella.
 */
private val BackButtonGap = 4.dp

/**
 * Todo el estado de la búsqueda de la biblioteca en un objeto, para que una pantalla lo cree con
 * una línea y se lo pase a la cabecera y al overlay — que son dos composables distintos y tienen
 * que hablar del MISMO estado (el componente mueve el input de uno a otro durante el morph).
 */
@Stable
internal class LibrarySearchState(
    val barState: SearchBarState,
    val textFieldState: TextFieldState,
    val colors: SearchBarColors
)

/**
 * Crea el estado de la búsqueda y lo cablea al ViewModel.
 *
 * **La query vive en `LibraryViewModel`, o sea que ahora hay DOS pantallas que pueden replicársela**
 * (la biblioteca y una categoría). Durante el shared axis Z las dos están compuestas un momento y
 * no hay conflicto: la que se va no vuelve a emitir —al colapsar ya se limpió, así que su texto no
 * cambia— y la que entra emite `""`, el mismo valor. Lo que NO hay que añadir es un
 * `DisposableEffect { onDispose { onQueryChange("") } }`: eso sí sería la pantalla saliente pisando
 * a la entrante.
 *
 * @param itemColor color de la PÍLDORA. Se pasa desde fuera para que salga de `AppColors` (el
 *   esquema teñido por la CARÁTULA) y no del `MaterialTheme` base —del wallpaper— que
 *   `SearchBarDefaults.containedColors` consultaría por su cuenta. Es lo que mantiene la píldora
 *   dentro de la app teñida en vez de gris ajeno. Valor: `AppColors.surfaceContainerHigh` (el rol
 *   canónico del search bar, `SearchBarTokens.ContainerColor`, en los dos temas).
 * @param fullScreenColor color de la SUPERFICIE del search view expandido (el `Dialog` a pantalla
 *   completa). Mismo motivo que [itemColor]: `containedColors` lo resolvería contra el
 *   `MaterialTheme` base. Valor: `AppColors.surfaceContainerLow` — el MISMO rol que M3 usa para el
 *   fondo del full-screen contained (`fullScreenContainedSearchBarColor`, hoy un hardcode a
 *   `surfaceContainerLow` con `// TODO` en la librería), solo que seedeado por la carátula. Un
 *   peldaño por debajo de la píldora, como en el spec.
 * @param onQueryChange se le replica el texto del input; la query sigue siendo dueña del
 *   `LibraryViewModel`, que es quien alimenta el paging LIKE.
 * @param onExpandedChange se avisa al ARRANCAR la animación, no al terminarla.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberLibrarySearch(
    itemColor: Color,
    fullScreenColor: Color,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit
): LibrarySearchState {
    // Contained, NO la variante con divisor (`ExpandedFullScreenSearchBar`): aquélla pega el input
    // al borde y dibuja un `HorizontalDivider` bajo él, y su superficie crece geométricamente. La
    // contained deja el input como píldora con márgenes, la superficie entra por alpha
    // (`layerBlock { alpha = state.progress }`) y el contenido tiene su propio fade
    // (`contentProgress`). Es el estilo expressive — el "bouncy" del search view contained.
    val barState = rememberContainedSearchBarState(
        animationSpecForExpand = SearchViewMotionSpec,
        animationSpecForCollapse = SearchViewMotionSpec
    )
    // `SearchBarDefaults.containedColors` resuelve su paleta contra `MaterialTheme.colorScheme` —el
    // esquema BASE, seedeado del WALLPAPER—, así que sin este override la píldora se sale de la app
    // teñida por la carátula (regla del rediseño de color: ningún componente M3 cae al
    // `MaterialTheme`). Se pisan `containerColor` (colapsado) y el contenedor del input con
    // [itemColor], que la pantalla saca de `AppColors` (esquema teñido por la carátula). Valor y
    // rol: `surfaceContainerHigh` = `SearchBarTokens.ContainerColor`, así que solo cambia la
    // FUENTE del color, no el tono.
    val colors = SearchBarDefaults.containedColors(barState).let { base ->
        base.copy(
            // El `containerColor` va TAMBIÉN, no solo el del input: el componente los usa en dos
            // capas —`SearchBar` pinta un `Surface` con él y el `inputField` pinta la suya dentro—,
            // así que los dos se atan a un color de `AppColors` para que salgan de la misma fuente.
            //
            // Los DOS estados se pisan, cada uno con su rol: este mismo objeto lo consume
            // `ExpandedFullScreenContainedSearchBar`, donde `containedColors` devolvería
            // `fullScreenContainedSearchBarColor` (`surfaceContainerLow`) — el rol correcto, pero
            // resuelto contra el `MaterialTheme` BASE, o sea del wallpaper. [fullScreenColor] es ese
            // MISMO rol tomado de `AppColors`, así que solo cambia la FUENTE del color, no el tono.
            // `currentValue`, no `isExpanded`: esa extensión existe en material3 pero es PRIVADA de
            // su archivo, y es literalmente esta misma comparación.
            containerColor =
                if (barState.currentValue == SearchBarValue.Expanded) fullScreenColor
                else itemColor,
            inputFieldColors = base.inputFieldColors.copy(
                focusedContainerColor = itemColor,
                unfocusedContainerColor = itemColor
            )
        )
    }
    val textFieldState = rememberTextFieldState()

    // La query sigue siendo dueña del LibraryViewModel (la consume el paging LIKE): el
    // TextFieldState del componente es solo el input, así que se replica hacia el VM.
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text.toString() }.collect { onQueryChange(it) }
    }
    // targetValue (no currentValue): al colapsar limpiamos apenas arranca la animación,
    // no al terminarla, para que la lista de atrás ya esté sin filtrar cuando se destapa.
    LaunchedEffect(barState.targetValue) {
        val expanded = barState.targetValue == SearchBarValue.Expanded
        onExpandedChange(expanded)
        if (!expanded) textFieldState.clearText()
    }

    return remember(barState, textFieldState, colors) {
        LibrarySearchState(barState, textFieldState, colors)
    }
}

/**
 * El `inputField` que comparten la píldora y el diálogo — **el mismo objeto**, porque el componente
 * lo MUEVE de una ventana a otra durante el morph. El foco inicial lo pide el propio diálogo (ya no
 * hace falta un `FocusRequester`).
 *
 * @param trailing lo que ocupa el hueco del trailing mientras la barra está docked (el overflow de
 *   la biblioteca). Vacío en las pantallas de categoría: Ajustes está a un "atrás" de distancia, y
 *   multiplicar su puerta por cinco es justo lo que la app ya evitó al quitar el engranaje
 *   redundante.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberLibrarySearchInputField(
    search: LibrarySearchState,
    trailing: @Composable () -> Unit = {}
): @Composable () -> Unit {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val backDesc = stringResource(R.string.common_back)
    val clearDesc = stringResource(R.string.common_clear_search)
    // Mitad de la expansión: antes de eso el leading sigue siendo la lupa (continuidad con el
    // icono del que nace el diálogo), después es "volver".
    val showBackIcon by remember(search) { derivedStateOf { search.barState.progress > 0.5f } }
    return {
        SearchBarDefaults.InputField(
            textFieldState = search.textFieldState,
            searchBarState = search.barState,
            // Píldora tonal sobre la superficie del diálogo (el input NO comparte color con el
            // contenedor en la variante contained).
            colors = search.colors.inputFieldColors,
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
                    onClick = { scope.launch { search.barState.animateToCollapsed() } },
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
                    search.textFieldState.text.isNotEmpty() -> {
                        IconButton(
                            onClick = { search.textFieldState.clearText() },
                            modifier = Modifier.semantics { contentDescription = clearDesc }
                        ) { MaterialSymbol("close") }
                    }
                    // Docked (y hasta la mitad del morph, espejo del leading): lo que ponga el
                    // caller vive DENTRO de la píldora como trailing icon del spec de search bar.
                    !showBackIcon -> Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
                }
            }
        )
    }
}

/**
 * Cabecera de la biblioteca: search bar DOCKED real de M3 (la píldora ES el ancla del
 * search view expandido — `SearchBar(state, inputField)` registra sus collapsedCoords y
 * el tap del input dispara la expansión, sin `onSearchClick` manual). Sin título ni
 * TopAppBar: en la biblioteca la píldora activa de las tabs ya dice dónde estás, y en una
 * categoría lo dice la lista.
 *
 * El color del bloque lo gobierna el caller: en la biblioteca es el mismo que la fila de tabs
 * (son una sola pieza, la barra de navegación puesta arriba); en una categoría es el fondo de
 * página, porque ahí la cabecera no ES una barra de navegación y no debe llevar su color.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibrarySearchHeader(
    search: LibrarySearchState,
    inputField: @Composable () -> Unit,
    containerColor: Color,
    /**
     * Lo que hay que apartar del borde final: en horizontal, la perforación de pantalla y la barra
     * del sistema. Va como parámetro y no en el [modifier] porque se aplica DESPUÉS del
     * `background`: el tinte del bloque tiene que llegar hasta el borde y es el CONTENIDO el que
     * se aparta. El inset del otro lado sí viaja en el modifier, y a propósito — ahí el fondo no
     * debe invadir la columna del rail, que se dibuja debajo de esta cabecera.
     */
    contentEndPadding: Dp = 0.dp,
    /**
     * Con atrás (las pantallas de categoría), el botón va A LA IZQUIERDA de la píldora y dentro del
     * mismo bloque tonal — el patrón de "barra de búsqueda con navegación" del spec. `null` en la
     * biblioteca, que es un destino y no tiene de dónde volver.
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val backDesc = stringResource(R.string.common_back)
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
        // 20 ago y se quitó el mismo día en device: la píldora no flota sobre la página, vive
        // dentro de la cabecera y ya se separa por tono.
        //
        // **La píldora tiene TOPE de ancho** (27 ago 2026): girado se estiraba a los ~790dp que
        // deja el rail, un campo de texto de una línea ocupando toda la pantalla. El tope es el
        // mismo que el del resto del contenido ([ComponentConfig.ContentMaxWidth]).
        //
        // El tope se aplica al `Row` y no a la barra, para que con [onBack] lo que quepa en
        // `ContentMaxWidth` sea el CONJUNTO: el botón es de ancho intrínseco y la barra se lleva
        // el resto. Sin atrás el `Row` tiene un solo hijo y el resultado es idéntico al de antes —
        // `min(disponible, tope)` en vertical, el tope girado.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (onBack != null) BackButtonGap else 0.dp),
            modifier = Modifier
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = backDesc }
                ) { MaterialSymbol("arrow_back") }
            }
            SearchBar(
                state = search.barState,
                inputField = inputField,
                colors = search.colors,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * El search view EXPANDIDO. No ocupa alto en el slot `topBar`: internamente es un `Dialog` que solo
 * se compone con el estado expandido, así que se declara al lado de la cabecera.
 *
 * @param content los resultados. Alrededor se le ponen el `BackHandler` y el `SnackbarHost`
 *   propios, que son los dos detalles fáciles de perder (ver los comentarios de dentro).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibrarySearchOverlay(
    search: LibrarySearchState,
    inputField: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    ExpandedFullScreenContainedSearchBar(
        state = search.barState,
        inputField = inputField,
        colors = search.colors
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
        BackHandler { scope.launch { search.barState.animateToCollapsed() } }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
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
}
