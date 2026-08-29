package com.qhana.siku.ui

import android.view.View
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.createChildTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qhana.siku.ui.theme.AppColorScheme
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.navigation.Screen
import kotlinx.coroutines.flow.StateFlow

/**
 * De dónde sale la carátula al abrir el reproductor. Son tres coreografías distintas y cada una es
 * correcta en su caso; lo que no vale es aplicar la de otro, porque un shared element **sin pareja
 * se pinta en el overlay del `SharedTransitionScope`** —que cuelga de la raíz y NO recibe el
 * `graphicsLayer` del slide— o sea quieto en su posición final mientras el reproductor sube.
 */
enum class PlayerArtOrigin {
    /**
     * Sin origen: la carátula sube CON el resto del contenido, como una pieza más de la pantalla.
     * Es lo correcto cuando lo que disparó la reproducción no enseña la portada (chips del inicio,
     * deep link de la notificación).
     */
    NONE,

    /**
     * Desde la PÍLDORA (el MiniPlayer): la portada ya está en la barra y viaja de ahí al centro.
     * *Container transform* clásico — "esta barra se convierte en el reproductor".
     */
    PILL,

    /**
     * Desde la FILA de la canción en una lista: *container transform* igual que el de la píldora, con
     * otra punta de origen — la superficie de la fila CRECE hasta ser el reproductor y su portada
     * viaja anidada dentro. La fila origen es siempre la de la canción ACTIVA
     * (`MusicController.announceSelection` la fija en el frame del tap), así que no hace falta pasar
     * ningún id: cada fila sabe si es el origen preguntando si es la que suena.
     *
     * La fila **se oculta conservando su hueco** mientras dura el viaje, y eso no es un efecto: es lo
     * que la convierte en ORIGEN. Compose exige que de las dos puntas de una key solo UNA sea destino,
     * y una fila que se queda visible siempre lo es — con ella visible habría dos destinos y ninguna
     * animación. Es además lo que hace `MaterialContainerTransform`, que esconde la vista de origen
     * (con `INVISIBLE`, de ahí lo del hueco). Ver `ContainerTransformOrigin`.
     */
    ROW
}

/**
 * Papel de una superficie de lista (una fila) en el container transform del reproductor. `null` —el
 * caso de todas las filas casi todo el tiempo— es "ninguno": la fila no declara shared element y cuesta
 * lo que un `Box`.
 *
 * ## Por qué hay un papel VISIBLE además del oculto
 *
 * Una punta de shared element solo sirve de ORIGEN si ya estaba compuesta y **colocada** antes de que
 * aparezca su pareja: los bounds iniciales del morph salen de la última colocación de la punta que era
 * destino hasta ese momento (`obtainBoundsFromLastTarget` en `compose-animation`), y una punta que
 * nace en el mismo frame que el reproductor no tiene ninguna. Hasta el 16 ago eso se resolvía
 * declarando la punta de TODAS las filas SIEMPRE, y el precio se pagaba en cada frame de scroll (ver
 * `ContainerTransformOrigin`). Ahora se paga UN frame, y solo al abrir: al tocar una fila,
 * `MusicAppState.openPlayer` no expande el reproductor sino que le da a esa fila el papel [VISIBLE]
 * —declara su punta como destino, sola, y queda colocada en ese frame—; cuando la fila avisa de que
 * está colocada (`onRowPlaced`), el reproductor se expande y la fila pasa a [HIDDEN] sin pasar por
 * `null` en medio, que es lo que conserva la entrada ya medida. Es exactamente la mecánica de la
 * píldora —que está declarada y medida desde mucho antes—, comprimida a un frame para una punta que
 * no puede estar declarada de antemano sin costar por fila.
 *
 * El mismo papel [VISIBLE] vale para el cierre: la fila a la que vuelve el reproductor declara su punta
 * visible mientras la capa se contrae (un DESTINO no necesita bounds previos: los toma de su
 * colocación en ese mismo frame) y la suelta al asentar.
 */
enum class ContainerOriginRole {
    /** Declara su punta y se ve: preparándose para ser origen, o recibiendo el cierre. */
    VISIBLE,

    /** Declara su punta y está oculta: el morph nació de ella y el reproductor la tapa. */
    HIDDEN
}

/**
 * La superficie que participa en el morph del reproductor AHORA MISMO: qué canción y si está oculta.
 * Lo calcula [com.qhana.siku.ui.MusicPlayerScreen] a partir del origen congelado del morph, la
 * transición de la capa y la superficie en preparación (`MusicAppState.pendingRowOrigin`).
 *
 * La identidad es la CANCIÓN. En una lista eso es exacto (una canción, una fila); en el inicio admite
 * un empate entre carruseles, asumido a propósito — ver `SongRowContainer`.
 */
data class RowOrigin(val songId: String, val hidden: Boolean)

/**
 * Lo que una fila de lista necesita para poder ser punta del morph del reproductor: preguntar qué
 * papel le toca ([roleOf]) y avisar de que existe y de que quedó colocada. Lo consumen las dos puntas
 * anidadas de la fila —el CONTENEDOR (`SongRowContainer`) y su CARÁTULA (`SongItem`)— que están a
 * ocho pantallas del sitio donde se decide, de ahí que viaje por [LocalRowOrigin].
 *
 * **El origen va en un `State` y las filas lo leen dentro de un `derivedStateOf`, por rendimiento
 * medido en el sitio exacto donde dolía**: con un valor suelto, al abrir y al cerrar el reproductor
 * recomponían TODAS las filas visibles a la vez —unas diez, justo en el frame que arranca la
 * transición y en el que la termina—. Así solo recompone aquella cuyo papel cambia de verdad.
 *
 * Los avisos son plumbing hacia [MusicAppState] (null en el host por defecto, fuera de la app).
 */
@Stable
class RowOriginHost internal constructor(
    private val current: State<RowOrigin?>,
    private val appState: MusicAppState?,
    /**
     * La transición de la CAPA del reproductor, para que la punta de la fila cuelgue de ella
     * ([rowVisibility]). Null fuera de la app (host por defecto): entonces la fila no puede ser punta.
     */
    val layerTransition: Transition<PlayerLayerState>? = null
) {
    fun roleOf(songId: String): ContainerOriginRole? {
        val origin = current.value ?: return null
        if (origin.songId != songId) return null
        return if (origin.hidden) ContainerOriginRole.HIDDEN else ContainerOriginRole.VISIBLE
    }

    fun onRowPlaced(songId: String, instance: Any) { appState?.onRowPlaced(songId, instance) }
    fun onRowDisposed(songId: String, instance: Any) { appState?.onRowDisposed(songId, instance) }

    /**
     * La visibilidad de la punta de una fila, **como transición HIJA de la de la capa** — y ese
     * parentesco es el arreglo entero de "la fila se queda en blanco al cerrar" (19 ago 2026).
     *
     * La animación de bounds de un `sharedBounds` solo corre mientras
     * `SharedTransitionScope.isTransitionActive`, que a su vez pregunta a `BoundsAnimation.isRunning`;
     * y ése **sube por `parentTransition` hasta la Transition RAÍZ** de la punta que es DESTINO y
     * compara su `currentState` con su `targetState`. De ahí salía una asimetría que no se veía
     * leyendo el código:
     *
     *  - **Abriendo**, el destino es el REPRODUCTOR, cuya raíz es la transición de la capa: dura lo
     *    que el morph, así que había ventana y el container transform se veía.
     *  - **Cerrando**, el destino es la FILA. Con un `updateTransition` propio —que es lo que había—
     *    su raíz era ella misma, sin relación con la capa, y la ventana no daba: **el bounds SALTABA**.
     *    Medido cuadro a cuadro: el reproductor pasaba de pantalla completa a nada en UN frame (16 ms)
     *    y solo viajaba la carátula (que va por otro camino, `sharedElementWithCallerManagedVisibility`,
     *    con su propia transición interna). El hueco de la fila era el reproductor ya encogido a su
     *    tamaño, tapándola mientras se disolvía.
     *
     * La prueba que lo aisló: **desde la PÍLDORA funcionaba en los dos sentidos**, y la píldora es la
     * otra punta de origen — la única diferencia con la fila es que la suya cuelga del
     * `AnimatedContent` de la capa, o sea de la misma raíz. Colgar aquí la de la fila borra esa
     * asimetría: las dos puntas de origen corren contra el mismo reloj.
     *
     * El mapeo es el INVERSO del que usa el reproductor (`PlayerOverlay.playerVisibility`) y no una
     * traducción de [ContainerOriginRole], a propósito: es la MISMA regla que ya escribía
     * `RowOrigin.hidden` (`hidden = playerExpanded`), leída del sitio del que ya salía. El frame de
     * PREPARACIÓN encaja solo: la capa todavía es `Collapsed`, que mapea a `Visible`, que es el papel
     * que le toca.
     *
     * Devuelve `null` sin capa (fuera de la app): sin reloj no hay punta que declarar.
     */
    @OptIn(ExperimentalTransitionApi::class)
    @Composable
    fun rowVisibility(): Transition<EnterExitState>? =
        layerTransition?.createChildTransition(label = "rowOrigin") { layer ->
            if (layer == PlayerLayerState.Expanded) EnterExitState.PostExit else EnterExitState.Visible
        }
}

/** Host vacío por defecto: una instancia estable, para que el local nunca cambie de objeto. */
private val NoRowOrigin = RowOriginHost(mutableStateOf(null), appState = null)

/** Ver [RowOriginHost]. Estático: su valor es un objeto estable que no cambia; lo que cambia va dentro en `State`. */
val LocalRowOrigin = staticCompositionLocalOf { NoRowOrigin }

/**
 * ¿El subárbol del REPRODUCTOR está EN PANTALLA ahora mismo?
 *
 * El NowPlaying se compone UNA vez —cuando aparece la primera canción— y se queda compuesto para
 * siempre (ver `PlayerOverlay`): componerlo en cada apertura costaba 25-58 ms en el frame del tap,
 * justo el que arranca el *container transform*. El precio de esa permanencia es que todo lo que el
 * subárbol pone en marcha sigue en marcha con el reproductor cerrado, y la regla **"cero productores
 * continuos de frames en superficies persistentes"** (ver la sección Motion de CLAUDE.md, y el buffer
 * stuffing que costó la saga del 17 ago) vale IGUAL para lo que no se ve: un giro de cookie o una onda
 * animándose detrás de la biblioteca gastan lo mismo y ensucian la misma cola de SurfaceFlinger.
 *
 * Por eso vale `false` mientras el reproductor está guardado, y lo leen los tres relojes del player
 * (el giro del botón de play, la fase de la onda y su interpolación del progreso) y el marquee del
 * título. **Sigue valiendo `true` durante el morph**: lo que se está encogiendo hacia la píldora tiene
 * que seguir vivo hasta que aterrice.
 *
 * **Se REPUBLICA en `NowPlayingScreen` con un `false` extra: cuando una de sus hojas a pantalla
 * completa lo tapa** (letras, cola, ecualizador — ver [com.qhana.siku.ui.components.SheetOverlay]).
 * Es el mismo razonamiento llevado al otro caso en que el reproductor no se ve, y el que más dura:
 * una hoja se deja abierta minutos. Ese provider envuelve SOLO el layout del reproductor, así que lo
 * que se lee fuera de él —el `keepScreenOn` de las letras, que con la hoja abierta tiene que seguir
 * encendido; el reseteo del estado al guardarse; `rememberAudioFormat`— sigue significando "el
 * reproductor está abierto" y no cambia de valor por una hoja.
 *
 * Va por CompositionLocal —y `compositionLocalOf`, no `static`, para que solo recompongan sus
 * lectores— por el mismo motivo que [LocalRowOrigin]: los tres relojes están a cinco firmas de
 * distancia y atravesarlas todas por un detalle de la animación no compensa. Default `true`: quien no
 * viva bajo el reproductor (el modo ambiental, cualquier pantalla suelta) se comporta como siempre.
 */
val LocalPlayerOnScreen = compositionLocalOf { true }

/**
 * El [SharedTransitionScope] de la app, publicado por la misma razón que [LocalRowOrigin]:
 * las filas de lista lo necesitan para declarar el shared element de su carátula y están demasiado
 * abajo como para recibirlo por parámetro.
 *
 * **No se lee directamente: se lee con [appSharedTransitionScope]**, que lo anula fuera de la
 * ventana en la que nació.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalAppSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * La `View` raíz de la ventana donde se montó el [LocalAppSharedTransitionScope]. La publica el
 * mismo sitio que el scope y solo la consume [appSharedTransitionScope].
 */
private val LocalSharedTransitionOwnerView = staticCompositionLocalOf<View?> { null }

/**
 * El scope compartido, o `null` si quien pregunta vive en OTRA ventana.
 *
 * **Un `SharedTransitionScope` no cruza ventanas.** Su overlay y sus medidas cuelgan del árbol de
 * layout de la ventana donde se declaró el `SharedTransitionLayout`; un `Dialog`, un `Popup` o un
 * `ModalBottomSheet` crean su PROPIO `AndroidComposeView`, así que un shared element declarado ahí
 * dentro le entrega coordenadas de otro árbol y Compose lanza
 * `IllegalArgumentException: layouts are not part of the same hierarchy` — un FC, no una animación
 * fea. Pasó con el overlay de búsqueda (`ExpandedFullScreenContainedSearchBar` es un diálogo
 * edge-to-edge y sus resultados son las mismas filas `SongItem` de la biblioteca).
 *
 * El gate va AQUÍ, en la lectura, y no en cada ventana que se abra: el local viaja a todas por
 * construcción, así que confiar en que cada `Dialog`/sheet nuevo se acuerde de anularlo es dejar la
 * misma trampa armada para la próxima. `LocalView` cambia de valor en cada ventana, que es
 * exactamente la pregunta que hay que hacer.
 *
 * **Ojo, esto NO cubre el otro motivo para anular el scope**: dos listas de la MISMA ventana que
 * repitan la key por-canción (la hoja de la cola sobre la lista de detrás). Ese caso sigue
 * necesitando su `CompositionLocalProvider(LocalAppSharedTransitionScope provides null)` explícito
 * — ver `PlayerOverlay`. Son problemas distintos con soluciones distintas.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun appSharedTransitionScope(): SharedTransitionScope? {
    val scope = LocalAppSharedTransitionScope.current ?: return null
    return scope.takeIf { LocalSharedTransitionOwnerView.current === LocalView.current }
}

/**
 * Publica el scope compartido junto con la ventana a la que pertenece. Único punto de entrada:
 * proveer el local a mano dejaría el gate de [appSharedTransitionScope] sin la referencia con la
 * que comparar y anularía el shared element en TODAS partes (fallo silencioso: las animaciones
 * simplemente dejarían de ocurrir).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProvideAppSharedTransitionScope(
    scope: SharedTransitionScope,
    rowOrigin: RowOriginHost,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppSharedTransitionScope provides scope,
        LocalSharedTransitionOwnerView provides LocalView.current,
        LocalRowOrigin provides rowOrigin,
        content = content
    )
}

/**
 * Deja fuera de los shared elements un subárbol que está compuesto y COLOCADO pero que el usuario **no
 * está viendo**. Es la contrapartida de [ProvideAppSharedTransitionScope]: aquél publica el scope,
 * éste lo retira donde no toca.
 *
 * ## El bug que lo trajo (19 ago 2026): "el morph no ocurre, pero solo en ciertas canciones"
 *
 * Reporte: abrir «16» de Allison o «A Light That Never Comes» desde "Todas" abría el reproductor sin
 * animación, y las canciones vecinas iban bien — **independiente de su posición en la lista**. Eso
 * descarta el scroll, la fila y el reciclado: lo que tenían de especial esas dos es que además salían
 * en el carrusel **"Lo que más escuchas" de la pestaña INICIO**.
 *
 * El `HorizontalPager` de la biblioteca va con `beyondViewportPageCount = 1`, así que la pestaña
 * vecina no solo está compuesta: está **colocada** (fuera del viewport, pero colocada, que es lo que
 * cuenta para un shared element). Y el papel de punta se decide por CANCIÓN
 * ([RowOriginHost.roleOf]), no por superficie — así que al tocar la fila, la tarjeta invisible del
 * Inicio tomaba el papel `VISIBLE` **a la vez** que la fila. Dos puntas de la misma key declarándose
 * las dos como destino, y ahí Compose no anima nada: el contenido aparece quieto en su posición
 * final. Es exactamente el fallo que `ContainerTransformOrigin` ya documenta ("de las dos puntas de
 * una key solo UNA puede ser destino"), entrando por una puerta que nadie había mirado.
 *
 * El empate ENTRE CARRUSELES del inicio está asumido a propósito (ver `SongRowContainer`), pero aquél
 * es entre dos superficies que el usuario VE; éste es contra una que no existe para él, y encima
 * gana a veces. No es lo mismo y no estaba decidido.
 *
 * ## La regla
 *
 * **Una superficie que no se ve no puede ser punta de un shared element.** Es la misma familia que
 * las otras dos anulaciones del repo —las filas de las hojas del reproductor, y el gate por VENTANA
 * de [appSharedTransitionScope]— y se resuelve igual: retirando el scope, que es el interruptor
 * general (sin él, ni el contenedor ni la carátula declaran nada, y las filas ni preguntan por su
 * papel).
 *
 * Cubre de paso lo que habría pasado con artista y álbum: el carrusel de artistas del Inicio y la
 * grilla de la pestaña Artistas declaran la MISMA key (`artist_photo_<nombre>`) y son vecinas de
 * pestaña, así que el mismo empate esperaba ahí para el día que alguien navegara a un detalle.
 *
 * Retirar el scope invalida solo a sus LECTORES (es `compositionLocalOf`, no static), o sea las
 * superficies que declaran puntas — no la página entera.
 *
 * ## Una SOLA llamada a `content()`, y eso es load-bearing
 *
 * La primera versión (19 ago) era `if (visible) content() else CompositionLocalProvider(…) { content() }`,
 * y eso **recreaba la pestaña entera en cada cambio**: las dos ramas de un `if` son grupos DISTINTOS
 * del slot table, así que cambiar de rama no cambia un valor — descarta el subárbol y lo compone
 * desde cero. Medido con Perfetto en el frame de un cambio de pestaña: 22 `Compose:onForgotten`,
 * 20 `Composer.dispose`, 94 `Constructing StaticLayout` (todos los textos de la página medidos de
 * nuevo) y 16 `AsyncImagePainter.onRemembered` (las carátulas visibles vueltas a pedir), con
 * `measureAndLayout` en 21 ms de los 34 del frame. Y al cambiar de página cambian de estado DOS
 * páginas, así que se pagaba dos veces. Ver [[project_aug19_jank_cambio_pestana]].
 *
 * Con el provider incondicional la estructura no se mueve: lo único que cambia es el VALOR del local,
 * que es justo lo que la nota de arriba prometía.
 *
 * ## CUÁNDO cambia `visible` importa tanto como el valor
 *
 * Los lectores del local son las filas que declaran puntas, o sea decenas en pantalla, y al cruzar
 * de pestaña cambian de estado DOS páginas. Si `visible` cambia a mitad del cruce, esa
 * re-declaración cae dentro del `measureAndLayout` del frame que el pager ya estaba pagando: 29 ms
 * de frame medidos el 24 ago 2026, 23,6 de ellos en la medida. Por eso el callsite de `LibraryScreen`
 * lo deriva de **`settledPage`** y no de `currentPage` — el trabajo es el mismo, pero en un frame
 * ocioso. Un gate nuevo sobre un pager se cablea igual.
 *
 * @param visible si este subárbol es el que el usuario está viendo. Con `false` no declara nada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionGate(visible: Boolean, content: @Composable () -> Unit) {
    val scope = LocalAppSharedTransitionScope.current
    CompositionLocalProvider(
        LocalAppSharedTransitionScope provides scope.takeIf { visible },
        content = content
    )
}

/**
 * El [RowOriginHost] de la app: el origen de fila calculado por `MusicPlayerScreen` en un `State`, con
 * los avisos cableados a [appState]. Único constructor público — así el host y el estado que publica
 * nacen juntos.
 */
@Composable
fun rememberRowOriginHost(
    rowOrigin: State<RowOrigin?>,
    appState: MusicAppState,
    layerTransition: Transition<PlayerLayerState>
): RowOriginHost =
    remember(rowOrigin, appState, layerTransition) {
        RowOriginHost(rowOrigin, appState, layerTransition)
    }

/**
 * State holder de la app (patrón `rememberAppState` de Now in Android): agrupa el NavController,
 * el estado de la capa flotante (píldora ↔ reproductor, hojas globales) y los flags derivados de
 * la ruta actual.
 *
 * Este estado vive AQUÍ y no en las rutas del NavHost a propósito: la capa del reproductor se
 * pinta SOBRE el NavHost (una sola instancia compartida entre pantallas), y un `hiltViewModel()`
 * dentro de una ruta resolvería al scope del NavBackStackEntry — una instancia nueva por pantalla
 * que no puede ser dueña de estado global.
 */
@Stable
class MusicAppState(
    val navController: NavHostController,
    /**
     * Estado de "player abierto", IZADO desde `MainActivity`.
     *
     * Se recibe el `MutableState` en vez de exponer una copia y espejarla, y eso sigue siendo
     * load-bearing aunque su motivo original —que el TEMA lo leía para congelar el fundido del
     * esquema— desapareciera el 20 ago 2026: el espejo se sincronizaba con un `LaunchedEffect`, que
     * corre DESPUÉS de la composición, así que quien lo leyera se enteraba **un frame tarde**. En
     * ese frame el seed de la canción nueva ya había cambiado, y el repintado caía justo encima del
     * container transform que acababa de arrancar. Es exactamente la lección del 15 ago: no gatear
     * una animación con un estado que se actualiza en otro frame que el que la dispara.
     */
    private val playerExpandedState: MutableState<Boolean>,
    /**
     * La fila en PREPARACIÓN (ver [pendingRowOrigin]), izada a `MainActivity` por la misma razón que
     * [playerExpandedState]. (Hasta el 20 ago 2026 el TEMA se congelaba también en este frame: el
     * seed de la canción tocada cambia aquí y, con el fundido pasando por `MaterialTheme`, el árbol
     * entero se recomponía dos veces. Con el color viajando por `AppColors` eso ya no ocurre.)
     */
    private val pendingRowOriginState: MutableState<String?>,
    /**
     * La canción en curso, para anotar de QUÉ FILA nace el reproductor en [openPlayer]. Se lee
     * `.value` en el instante del tap: `MusicController.announceSelection` la fija de forma síncrona
     * en el mismo handler, ANTES de que el caller llame a [openPlayer], así que aquí ya es la tocada.
     * Leerla de un colector de Compose la dejaría a merced de en qué frame llega la emisión.
     */
    private val currentSong: StateFlow<Song?>
) {
    /**
     * Reproductor expandido: la capa del player cubre la pantalla. Es **estado PROPIO**, no la ruta.
     *
     * El player ya NO es una ruta del `NavHost`: es una capa en [com.qhana.siku.ui.PlayerOverlay] que
     * hace `AnimatedContent` con la píldora. Las dos puntas del morph comparten así un **único**
     * `AnimatedContentScope`, que es lo que hace NATIVO el *container transform* (la barra crece hasta
     * ser el player). Como ruta, cada punta vivía en un dueño de transición distinto —el player en el
     * NavHost, la píldora en el overlay— y el `sharedBounds` no cruzaba: se leía como un slide.
     *
     * Se abre con [openPlayer] y se cierra con [collapsePlayer] (o el back del sistema / el gesto de
     * arrastre, que llaman a `onBackClick`). Navegar a un detalle DESDE el player lo colapsa
     * recordando a dónde volver (ver [navigateFromPlayer]).
     *
     * Vive en [playerExpandedState] (izado a `MainActivity`) y no en un `mutableStateOf` propio: hay
     * UN solo escritor y una sola verdad, que es lo que garantiza que el tema y la capa del player
     * cambien EN EL MISMO frame.
     */
    var playerExpanded: Boolean
        get() = playerExpandedState.value
        private set(value) { playerExpandedState.value = value }

    /**
     * De dónde nace la carátula del reproductor en la última apertura. Ver [PlayerArtOrigin].
     * La capa del player lo lee para elegir con qué key declara su shared element.
     */
    var playerArtOrigin by mutableStateOf(PlayerArtOrigin.NONE)
        private set

    /**
     * Id de la FILA de la que nació el reproductor la última vez que se abrió con
     * [PlayerArtOrigin.ROW] (null con cualquier otro origen). Es la canción que estaba anunciada en
     * el instante del tap — ver el KDoc de [currentSong]—, anotada AQUÍ y no leída después de un
     * colector: la fila que se oculta y la key que declara el player tienen que ser la misma desde el
     * primer frame, sin depender de cuándo llegue una emisión.
     *
     * Es la PETICIÓN. Lo que las puntas del morph usan de verdad es [PlayerMorphOrigin], que congela
     * esta petición mientras la transición de la capa está en vuelo — ver [rememberPlayerMorphOrigin].
     */
    var playerOriginSongId: String? by mutableStateOf(null)
        private set

    /**
     * Fila que se está PREPARANDO para ser origen del morph: declara su punta visible y sola durante un
     * frame, y en cuanto avisa de que quedó colocada ([onRowPlaced]) el reproductor se expande.
     * `null` = ninguna. Ver [ContainerOriginRole] para el porqué de este frame.
     */
    var pendingRowOrigin: String?
        get() = pendingRowOriginState.value
        private set(value) {
            pendingRowOriginState.value = value
            pendingRowOriginPlain = value
        }

    /**
     * Copia PLANA de [pendingRowOrigin] para leerla desde el LAYOUT ([onRowPlaced] corre dentro de un
     * `onPlaced`, en cada colocación de cada fila). Leer ahí el `MutableState` registraría el layout de
     * cada fila colocada como dependiente de él, y cada tap (que lo escribe dos veces: al preparar y
     * al expandir) relayoutaría la lista entera — justo en el frame que compone el reproductor.
     */
    private var pendingRowOriginPlain: String? = null

    /**
     * Superficies de canción COLOCADAS ahora mismo en la ventana de la app, por id de canción. Las
     * mantienen ellas mismas (`SongRowContainer`: alta en cada `onPlaced`, baja al descomponerse) y lo
     * consulta [openPlayer] para decidir, en el instante del tap y sin esperar a nada, si hay una que
     * pueda ser origen. Colocadas y no compuestas: el prefetch de `LazyColumn` compone filas que no se
     * ven, y una que no se coloca nunca daría la señal de [onRowPlaced] con la que se abre. Estructura
     * plana y no estado de snapshot: nadie reacciona a ella, se consulta.
     *
     * **Guarda un CONJUNTO DE INSTANCIAS por canción, no un booleano, y eso importa desde que las
     * tarjetas del inicio son origen** (17 ago 2026). Los carruseles no deduplican entre sí, así que la
     * misma canción puede tener DOS superficies vivas —"Lo que más escuchas" y el carrusel del artista
     * solapan por construcción—. Con un `HashSet<String>` plano, reciclar una de ellas al scrollear
     * borraba el id aunque la otra siguiera en pantalla, y el siguiente tap sobre la superviviente
     * abría SIN animación (la comprobación de [openPlayer] decía que no había origen colocado). Con un
     * token por instancia el alta es idempotente y la baja solo cuenta cuando se va la última.
     */
    private val placedRows = HashMap<String, MutableSet<Any>>()

    /**
     * Entrada del back stack a la que volver para RE-ABRIR el player, cuando se navegó a un detalle
     * ESTANDO el player abierto. `null` = no hay reapertura pendiente. Ver [navigateFromPlayer].
     */
    private var reopenPlayerAtEntryId: String? = null

    /**
     * Abre el reproductor: expande la capa del player, declarando de DÓNDE sale la carátula (ver
     * [PlayerArtOrigin]) para que la capa elija su shared element. El default es [PlayerArtOrigin.NONE]:
     * quien no sabe ofrecer un origen real (chip, notificación) no lo inventa y la carátula sube con el
     * player sin morph.
     *
     * **Con [PlayerArtOrigin.ROW] no expande en el acto: primero PREPARA la fila.** Una punta de shared
     * element solo sirve de origen si ya estaba colocada antes de que aparezca su pareja, y las filas ya
     * no declaran su punta de antemano (costaba en cada frame de scroll — ver [ContainerOriginRole]).
     * Así que se anota la fila en [pendingRowOrigin] —lo que hace que declare su punta, visible y sola—
     * y se espera su aviso de "colocada" ([onRowPlaced]) para expandir: un frame después, por SEÑAL y
     * no por un retardo elegido. Si la fila de la canción no está en pantalla (el play de una cabecera
     * arranca por la primera canción, que puede estar fuera de la vista), no hay a quién esperar y se
     * abre sin origen en el acto.
     *
     * Ya no navega: el player es una capa sobre el NavHost, no una ruta. "Atrás" lo colapsa
     * ([collapsePlayer]); navegar a un artista/álbum DESDE él pasa por [navigateFromPlayer].
     */
    fun openPlayer(origin: PlayerArtOrigin = PlayerArtOrigin.NONE) {
        JankProbe.arm { "openPlayer($origin) song=${currentSong.value?.title}" }
        if (origin == PlayerArtOrigin.ROW) {
            // La superficie de origen es la de la canción ANUNCIADA en este mismo handler
            // (announceSelection corre antes que este método en todos los call sites de ROW).
            // `.value` y no un colector: es lo que garantiza que sea la tocada y no la anterior.
            // Ver [playerOriginSongId].
            val rowId = currentSong.value?.id
            if (rowId != null && placedRows.containsKey(rowId)) {
                playerArtOrigin = PlayerArtOrigin.ROW
                playerOriginSongId = rowId
                pendingRowOrigin = rowId
                return
            }
        }
        pendingRowOrigin = null
        playerArtOrigin = if (origin == PlayerArtOrigin.ROW) PlayerArtOrigin.NONE else origin
        playerOriginSongId = null
        playerExpanded = true
    }

    /**
     * Una fila acaba de quedar COLOCADA (llega desde el layout, en cada colocación: también en cada
     * frame de scroll, así que aquí no se hace más que una inserción en un conjunto). Dos usos:
     * mantener [placedRows], y —si es la fila en preparación— dar por lista su punta: ya tiene bounds
     * que ofrecer y el reproductor puede expandirse. Esas dos escrituras van juntas para que la fila
     * pase de VISIBLE a HIDDEN en la misma composición, sin un frame en `null` que la desdeclare.
     */
    fun onRowPlaced(songId: String, instance: Any) {
        placedRows.getOrPut(songId) { HashSet() }.add(instance)
        // La copia plana, no el State: esto corre en el layout de cada fila, en cada colocación.
        if (pendingRowOriginPlain != songId) return
        JankProbe.mark { "fila origen colocada → expandir" }
        pendingRowOrigin = null
        playerExpanded = true
    }

    /** Ver [placedRows]: ¿hay una fila de esta canción colocada en pantalla ahora mismo? */
    fun isRowPlaced(songId: String): Boolean = placedRows.containsKey(songId)

    /**
     * Ver [placedRows]. Si la ÚLTIMA superficie de la canción que se estaba preparando desaparece
     * antes de avisar (la lista se recompuso justo en ese frame), no hay a quién esperar: se abre sin
     * origen en vez de quedarse esperando a una punta que ya no existe. Mientras quede otra viva no
     * pasa nada — de eso va el conjunto de instancias.
     */
    fun onRowDisposed(songId: String, instance: Any) {
        val instances = placedRows[songId] ?: return
        instances.remove(instance)
        // Solo cuando se va la ÚLTIMA superficie de esa canción: ver [placedRows].
        if (instances.isNotEmpty()) return
        placedRows.remove(songId)
        if (pendingRowOrigin == songId) {
            pendingRowOrigin = null
            playerArtOrigin = PlayerArtOrigin.NONE
            playerOriginSongId = null
            playerExpanded = true
        }
    }

    /** Colapsa el player. Cierre MANUAL: descarta cualquier reapertura pendiente. */
    fun collapsePlayer() {
        JankProbe.arm { "collapsePlayer song=${currentSong.value?.title}" }
        reopenPlayerAtEntryId = null
        playerExpanded = false
    }

    /**
     * Hoja "añadir canciones" del detalle de playlist/Favoritos. La dispara un botón de la propia
     * pantalla —la cabecera si hay canciones, el empty state si no—, NO un FAB: el FAB flotante ya
     * no existe.
     */
    var showAddSongsSheet by mutableStateOf(false)

    /**
     * **OJO: getters `@Composable` con ESTADO en el sitio de llamada.** `currentBackStackEntryAsState()`
     * es un `produceState(initialValue = null)` que se recuerda donde se llama: si la llamada está en
     * una rama condicional (`if`, `when`, `&&`), sale de la composición cuando la rama no corre y al
     * volver NACE DE NUEVO con `null` durante una composición, hasta que el flow emite en el frame
     * siguiente. Leerlos SIEMPRE una vez, incondicionalmente, al principio de la pantalla, y usar la
     * variable — así se cayó la capa del reproductor a `Hidden` un frame en cada cierre (17 ago, ver
     * `MusicPlayerScreen`).
     */
    val currentBackStackEntry: NavBackStackEntry?
        @Composable get() = navController.currentBackStackEntryAsState().value

    /** Ver la advertencia de [currentBackStackEntry]: nunca dentro de una condición. */
    val currentRoute: String?
        @Composable get() = currentBackStackEntry?.destination?.route

    // --- Navegación (helpers no composables, seguros desde callbacks) ---

    /**
     * Cierra el player si estaba expandido. Lo llama la capa del player cuando la canción actual pasa
     * a `null` (vaciar la cola, logout, corrupción): sin canción no hay nada que mostrar, así que en
     * vez de quedarse en el esqueleto —sin salida salvo varios "atrás"— se cierra sola.
     */
    fun closePlayerIfEmpty() {
        if (playerExpanded) collapsePlayer()
    }

    // Navegar a un detalle: si el player está expandido se colapsa recordando volver a él
    // ([navigateFromPlayer]); si no, es una navegación normal. El gate por [playerExpanded] evita
    // tocar los call sites —el player es el ÚNICO que puede llamar esto estando expandido, porque
    // tapa todo lo demás—.
    fun navigateToArtist(name: String) = navigateMaybeFromPlayer(Screen.ArtistDetail.createRoute(name))
    fun navigateToAlbum(name: String) = navigateMaybeFromPlayer(Screen.AlbumDetail.createRoute(name))
    fun navigateToGenre(name: String) = navigateMaybeFromPlayer(Screen.GenreDetail.createRoute(name))

    private fun navigateMaybeFromPlayer(route: String) {
        if (playerExpanded) navigateFromPlayer(route)
        else navController.navigate(route) { launchSingleTop = true }
    }

    /**
     * Navegar a un detalle DESDE el player: colapsa el player SIN container transform (`origin = NONE`,
     * o sea solo se funde) para no competir con la transición del NavHost por el overlay del
     * `SharedTransitionScope` —el bug de shared elements huérfanos que motivó el refactor del 11 ago—,
     * y anota a qué entrada del back stack volver para RE-ABRIR el player. Al regresar (back),
     * [reopenPlayerIfReturningFrom] lo expande de nuevo CON EL MISMO fundido: "atrás" del detalle
     * devuelve al reproductor, no a la biblioteca (era el bug del 7 ago).
     */
    private fun navigateFromPlayer(route: String) {
        reopenPlayerAtEntryId = navController.currentBackStackEntry?.id
        playerArtOrigin = PlayerArtOrigin.NONE
        playerExpanded = false
        navController.navigate(route) { launchSingleTop = true }
    }

    /**
     * Reabre el player si [entry] es la entrada desde la que se navegó a un detalle estando el player
     * abierto. Lo llama el observador de ruta de [com.qhana.siku.ui.MusicPlayerScreen] en cada cambio
     * de destino. Se compara por **id** de entrada (no por ruta): un detalle de artista puede navegar
     * a otro detalle de artista —misma ruta— y el id sobrevive a la rotación.
     *
     * **Vuelve con [PlayerArtOrigin.NONE], o sea fundido y NO container transform**, que es la misma
     * transición con la que se fue ([navigateFromPlayer]). Este viaje es de IDA Y VUELTA y no una
     * apertura: el player no se cerró, se apartó para dejar ver un detalle suyo, así que volver es
     * DESTAPARLO. Reabrir con `PILL` lo reconstruía desde una píldora que el usuario nunca tocó y
     * ponía el morph a competir con el pop del `NavHost` por el overlay del `SharedTransitionScope`
     * — exactamente lo que la ida ya evitaba haciéndose fundido. La asimetría venía de tratar el
     * regreso como "abrir el reproductor" en vez de como el otro tramo del mismo viaje.
     */
    fun reopenPlayerIfReturningFrom(entry: NavBackStackEntry?) {
        val target = reopenPlayerAtEntryId ?: return
        if (entry?.id == target) {
            reopenPlayerAtEntryId = null
            openPlayer(PlayerArtOrigin.NONE)
        }
    }

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }
}

@Composable
fun rememberMusicAppState(
    playerExpandedState: MutableState<Boolean>,
    pendingRowOriginState: MutableState<String?>,
    currentSong: StateFlow<Song?>,
    navController: NavHostController = rememberNavController()
): MusicAppState {
    // "Player abierto" es estado PROPIO de MusicAppState ([MusicAppState.playerExpanded]), no la ruta:
    // el player es una capa que hace container transform con la píldora, no una ruta del NavHost.
    // Los dos `MutableState` los pone MainActivity porque el TEMA también los lee; ver los KDoc de los
    // parámetros en [MusicAppState]. Antes había un espejo sincronizado por `LaunchedEffect` y llegaba
    // tarde.
    return remember(navController, playerExpandedState, pendingRowOriginState, currentSong) {
        MusicAppState(navController, playerExpandedState, pendingRowOriginState, currentSong)
    }
}

// ============================== LA CAPA DEL REPRODUCTOR Y SU MORPH ==============================

/**
 * Estados de la capa del reproductor (`PlayerOverlay`). Son las tres ramas de su `AnimatedContent`:
 * - [Expanded]: el player a pantalla completa.
 * - [Collapsed]: la píldora (MiniPlayer) flotando abajo.
 * - [Hidden]: nada (rutas sin reproducción, o sin canción).
 *
 * Collapsed↔Expanded es el *container transform*; Hidden↔Collapsed es un fundido de la píldora.
 *
 * Vive aquí, y no como `private` de `PlayerOverlay`, porque la `Transition` que los recorre se crea en
 * `MusicPlayerScreen` (que también decide qué fila es el origen del morph) y la capa solo la CONSUME.
 */
enum class PlayerLayerState { Hidden, Collapsed, Expanded }

/** Rutas en las que la píldora puede aparecer. Fuera de ellas —onboarding, ajustes— la capa queda [PlayerLayerState.Hidden]. */
fun isPillRoute(route: String?): Boolean = when (route) {
    Screen.Library.route, Screen.PlaylistDetail.route, Screen.Favorites.route,
    Screen.ArtistDetail.route, Screen.AlbumDetail.route, Screen.GenreDetail.route,
    // Estadísticas es contenido SOBRE la biblioteca y sus filas reproducen, del mismo género que
    // artista/álbum/género — no una pantalla de configuración como Ajustes o el gestor de descargas.
    // Lo mismo "Recién agregadas": es una lista de canciones de la biblioteca, aunque sea virtual.
    Screen.Stats.route, Screen.RecentlyAdded.route, Screen.StatsList.route -> true
    else -> false
}

/**
 * Chrome de navegación que añade la BIBLIOTECA, y que la capa del reproductor tiene que esquivar.
 *
 * Es una función PURA de (ruta, preferencia, orientación) por el mismo motivo que [isPillRoute]:
 * si fuera un estado escrito por `LibraryScreen` y leído por `PlayerOverlay` habría DOS escritores
 * del mismo valor (convención 14) y, peor, la píldora llegaría un frame tarde a cada navegación —
 * justo el frame en el que se ve el salto.
 *
 * El rail va en horizontal SIEMPRE, con el modo de pestañas abajo encendido o no: girado, lo
 * escaso es el alto y una fila más bajo la búsqueda nunca fue lo correcto. La barra inferior, en
 * cambio, es opcional. Y ninguno de los dos existe fuera de la biblioteca: son vistas de ella, no
 * destinos globales de la app, así que un detalle usa la pantalla entera.
 */
enum class LibraryChrome { None, BottomBar, Rail }

fun libraryChrome(route: String?, bottomTabs: Boolean, landscape: Boolean): LibraryChrome = when {
    route != Screen.Library.route -> LibraryChrome.None
    landscape -> LibraryChrome.Rail
    bottomTabs -> LibraryChrome.BottomBar
    else -> LibraryChrome.None
}

/**
 * De qué superficie sale (o a cuál vuelve) el reproductor en el morph EN VUELO — o en el último, si
 * está asentado. Es lo que consumen las DOS puntas: las filas de lista (para saber cuál se oculta y
 * cede sus bounds) y la capa del player (para elegir con qué keys declara sus shared elements).
 *
 * @param kind PÍLDORA / FILA / ninguna. Ver [PlayerArtOrigin].
 * @param songId la fila, solo con [PlayerArtOrigin.ROW]; null en los demás casos.
 */
data class PlayerMorphOrigin(val kind: PlayerArtOrigin, val songId: String?) {
    companion object {
        val None = PlayerMorphOrigin(PlayerArtOrigin.NONE, null)
    }
}

/**
 * El origen del morph, **congelado mientras la transición de la capa corre**.
 *
 * ## Por qué congelarlo — la regla que sale de las fuentes de `compose-animation`
 *
 * Un shared element cuya key cambia mientras su transición está en vuelo deja a su contraparte
 * VARADA en el overlay: al quitar la entrada, la máquina de estados de esa key queda en
 * `ActiveMatchRemovedDuringTransition` (o directamente sin nadie que la reinicie), y
 * `shouldRenderInOverlay` la sigue pintando **por encima de todo** hasta que el `SharedTransitionScope`
 * entero se queda quieto. Es exactamente lo que se vio el 16 ago: al volver a tocar una fila antes de
 * que el cierre anterior asentara, `AnimatedContent` REUTILIZA la rama Expanded que aún estaba
 * saliendo —misma instancia de `NowPlayingLayer`, con sus keys de la canción ANTERIOR—, la canción
 * nueva le cambiaba las keys a media transición, y la fila anterior quedaba dibujada sobre el player
 * hasta que todo se detenía. Regla: **una punta de shared element no cambia de identidad mientras su
 * transición corre**; si la petición cambia en vuelo, se atiende cuando la capa asiente.
 *
 * ## Cuándo se toma la petición
 *
 * Con la transición PARADA (`!isRunning`), que incluye el frame en que ARRANCA (`updateTarget`
 * cambia `targetState` en la composición y `startTimeNanos` recién se fija en el frame siguiente)
 * **o con el origen vigente en `NONE`** (ver más abajo, "el congelado protege puntas, no frames"):
 *  - **Arrancando una apertura** → la petición de `MusicAppState` (origen + fila anotada en el tap).
 *    Es lo que hace que la fila oculta y la key del player sean la misma desde el primer frame, sin
 *    depender de en qué frame llegue la emisión de un colector.
 *  - **Arrancando un cierre** → el origen pedido (que `navigateFromPlayer` puede haber cambiado a
 *    NONE para no competir con el NavHost) y, si es una fila, la canción que suena AHORA: si el usuario
 *    cambió de tema dentro del reproductor, la superficie aterriza en la fila actual, no en la que la
 *    abrió — y si esa fila NO está en pantalla, **aterriza en la PÍLDORA** (origen PILL), que en una
 *    ruta con píldora está siempre a la vista y es adonde "se va" la música al cerrar. Hasta el 17 ago
 *    ese caso degradaba a un fundido (NONE) y se leía como que el cierre "no ocurría": tocar una fila,
 *    saltar de tema dentro del reproductor y volver es de lo más común, y cada vez perdía el morph.
 *    Solo si el destino de la capa no es la píldora (`Hidden`: cerrar en una ruta sin píldora) queda
 *    el fundido, porque no hay superficie que recoja nada — sin pareja la rama saliente moriría en el
 *    acto (`KeepUntilTransitionsFinished`). Ese cambio de key ocurre con la transición parada, así que
 *    la fila que deja de ser origen (ya descompuesta: su `AnimatedVisibility` terminó de salir hace
 *    rato) no deja nada varado.
 *  - **Asentado** → conserva. Mientras el player está abierto y quieto la key NO sigue a la canción a
 *    propósito: si lo hiciera, cada "siguiente" con la fila del tema nuevo a la vista detrás dispararía
 *    un morph espurio desde esa fila hasta la pantalla completa (esa fila recibiría el papel de origen,
 *    declararía su punta y Compose la emparejaría con la del player en el acto).
 *
 * ## El congelado protege PUNTAS, no frames (21 ago 2026)
 *
 * `!isRunning` a secas protegía de más, y esa protección de más costaba el morph Y dejaba la capa
 * VARADA. Lo que el gate evita es cambiar la IDENTIDAD de un shared element **con match activo**;
 * cuando el origen vigente es `NONE` no hay ninguna punta declarada —ni la capa
 * (`containerSharedKey == null`) ni ninguna fila (`rowOrigin` es null salvo con `ROW` o con una fila
 * en preparación)—, así que no hay nada que varar y la petición se puede atender aunque la
 * transición corra. Con `PILL` o `ROW` en vuelo el congelado sigue intacto, que es donde protege.
 *
 * El caso que lo obligó: **tocar una fila mientras la píldora está en una transición
 * `Hidden↔Collapsed`** — sin cola (la primera canción de la sesión: `announceSelection` hace aparecer
 * la píldora en el mismo frame del tap) o volviendo de Ajustes y tocando dentro del fundido de 500 ms.
 * La petición `ROW` se descartaba, la apertura perdía el container transform… y al cerrar, con la
 * transición ya parada, se volvía a leer `playerArtOrigin` —que seguía valiendo `ROW`— y el cierre
 * ESTRENABA un morph cuyas dos puntas nacían en el frame que lo arranca, con el player haciendo de
 * origen sin bounds previos para esa key. Resultado: el bounds no emparejaba, la capa no adoptaba
 * nunca `Collapsed`, `PlayerOverlay` la seguía COLOCANDO —o sea tragándose los toques— y
 * `surfaceFactor` la había dejado a alpha 0. Síntoma: "volví de la canción pero el reproductor sigue
 * respondiendo, invisible".
 *
 * ## Un cierre no ESTRENA un morph que la apertura no tuvo
 *
 * De ahí la segunda regla, que es la que hace ese varado IMPOSIBLE aunque algo vuelva a fallar arriba:
 * si el reproductor se abrió sin superficie, el cierre tampoco se la inventa. Las dos puntas nacen
 * juntas o no nace ninguna. No toca ningún caso legítimo: un chip abre y cierra en `NONE` (ya era
 * así), `navigateFromPlayer` sigue forzando el fundido, y una apertura desde fila sigue pudiendo
 * aterrizar en otra fila o en la píldora si el usuario cambió de tema dentro del reproductor.
 *
 * Contenedor plano y no `mutableStateOf`: se lee y se escribe en la misma composición, y ya recompone
 * por lo que lee (`isRunning`, `currentState`, `targetState` son estado de snapshot).
 */
@Composable
fun rememberPlayerMorphOrigin(
    layerTransition: Transition<PlayerLayerState>,
    appState: MusicAppState,
    currentSongId: String?
): PlayerMorphOrigin {
    val holder = remember { MorphOriginHolder() }
    // Sin superficie vigente no hay ninguna punta declarada, así que no hay identidad que proteger y
    // la petición se atiende aunque la transición corra. Ver "el congelado protege PUNTAS" en el KDoc.
    val noSurfaceInFlight = holder.value.kind == PlayerArtOrigin.NONE
    if (!layerTransition.isRunning || noSurfaceInFlight) {
        val toExpanded = layerTransition.targetState == PlayerLayerState.Expanded
        val fromExpanded = layerTransition.currentState == PlayerLayerState.Expanded
        val requestedKind = appState.playerArtOrigin
        // Con qué superficie está ABIERTO el reproductor ahora mismo: el holder conserva el origen de
        // la apertura mientras dure. Es lo que consulta la rama de cierre para no estrenar un morph.
        val openedWith = holder.value.kind
        holder.value = when {
            // Abriendo con lo que se pidió. **`NONE` se queda en `NONE` aunque la píldora esté en
            // pantalla** (decisión del usuario, 20 ago 2026): un chip del inicio no es la píldora, así
            // que no hay superficie que compartir y lo que corresponde es un shared axis, no un
            // container transform prestado. Se probó caer a `PILL` en ese caso y se descartó. Quien
            // anima `NONE` es `detachedFactor` (NowPlayingScreen).
            toExpanded && !fromExpanded ->
                PlayerMorphOrigin(requestedKind, appState.playerOriginSongId)
            // Un cierre no ESTRENA un morph: si el reproductor se abrió sin superficie, la salida
            // también es sin superficie. Va ANTES de todo lo demás de la rama de cierre porque no es
            // un caso particular suyo sino su precondición — ver el KDoc. Sin esto, una petición que
            // el congelado no pudo atender al abrir reaparece al cerrar y hace nacer las dos puntas
            // en el frame que arranca el cierre, que es exactamente el varado que esto arregla.
            fromExpanded && !toExpanded && openedWith == PlayerArtOrigin.NONE -> PlayerMorphOrigin.None
            fromExpanded && !toExpanded -> {
                // Cerrando hacia una FILA, la fila tiene que estar EN PANTALLA: si el usuario cambió de
                // tema dentro del reproductor y la fila del actual no está colocada, no hay punta que
                // recoja la superficie y el morph no tendría pareja — con `KeepUntilTransitionsFinished`
                // (ver PlayerOverlay) eso significa que la rama saliente moriría EN EL ACTO, sin morph
                // ni fundido. Sin fila a la vista, la superficie aterriza en la PÍLDORA (que la capa
                // compone en ese mismo frame como destino, igual que en un cierre desde la píldora);
                // solo sin píldora (destino `Hidden`) queda el fundido. Ver el KDoc.
                val rowId = if (requestedKind == PlayerArtOrigin.ROW) currentSongId ?: holder.value.songId else null
                val toPill = layerTransition.targetState == PlayerLayerState.Collapsed
                when {
                    requestedKind == PlayerArtOrigin.ROW && (rowId == null || !appState.isRowPlaced(rowId)) ->
                        if (toPill) PlayerMorphOrigin(PlayerArtOrigin.PILL, null) else PlayerMorphOrigin.None
                    else -> PlayerMorphOrigin(requestedKind, rowId)
                }
            }
            else -> holder.value
        }
        JankProbe.note {
            "morphOrigin → ${holder.value.kind}/${holder.value.songId?.takeLast(8)} " +
                "(pedido=$requestedKind, ${layerTransition.currentState}→${layerTransition.targetState})"
        }
    } else if (JankProbe.isEnabled) {
        // La DISCREPANCIA entre lo que se pidió y lo que está vigente es el dato con el que se
        // reconoce este fallo, y hasta el 21 ago no aparecía en ningún log: la petición decía `ROW`,
        // el origen congelado decía `NONE` y el síntoma (una capa varada) no parece de animación.
        // Va detrás de `isEnabled` por la convención 9d y también para no suscribir esta función a
        // `playerArtOrigin` con la sonda apagada.
        val requested = appState.playerArtOrigin
        if (requested != holder.value.kind) {
            JankProbe.note {
                "morphOrigin CONGELADO: petición=$requested vigente=${holder.value.kind} " +
                    "(${layerTransition.currentState}→${layerTransition.targetState}) — el cierre NO estrenará morph"
            }
        }
    }
    return holder.value
}

/** Ver [rememberPlayerMorphOrigin]. */
private class MorphOriginHolder {
    var value: PlayerMorphOrigin = PlayerMorphOrigin.None
}

/**
 * Los [AppColors] de lo que queda DEBAJO del reproductor (NavHost y píldora), **retenidos mientras
 * la capa se abre**.
 *
 * ## Por qué existe, medido dos veces
 *
 * El seed del tema es la canción que suena, así que al abrir una canción de la biblioteca cambia el
 * color justo en el frame del tap — el que arranca el morph. Repintar la biblioteca visible ahí
 * costaba, en el trace del 20 ago a las 19:46, **26 bloques de recomposición y 11,7 ms** de un frame
 * de 20. Se retiene mientras el reproductor crece y se aplica cuando asienta: con el player tapando
 * la biblioteca y nada en movimiento.
 *
 * **La prueba de que el problema es éste y no "abrir el reproductor" en general**: abriendo desde el
 * detalle de un ÁLBUM no hay tirón, porque allí `DetailContentTheme` provee un [AppColorScheme]
 * seedeado por el álbum, que no cambia al reproducir una canción. La diferencia entre las dos
 * pantallas es exactamente esta retención.
 *
 * Esto vivió como `rememberUnderlayColorScheme` (sobre `ColorScheme`) hasta que el refactor de
 * `AppColors` lo dio por innecesario — el argumento fue que el coste "ya no existía", y era falso:
 * había bajado de recomponer el árbol ENTERO sin skipping a invalidar sólo a los lectores de color,
 * que sigue siendo el frame más caro de la apertura. **Que un coste baje no es que desaparezca.**
 *
 * ## Se retiene SOLO mientras se ABRE
 *
 * Cerrando se aplica en el acto, corra o no la transición. Si el usuario cierra antes de que la
 * apertura asiente —tocar y bajar enseguida—, "cuando asiente" pasaría a ser el final del cierre: la
 * biblioteca a la vista, quieta, y el usuario probablemente ya scrolleando. Medido el 16 ago: el
 * repintado caía ~530-550 ms después del `collapsePlayer`, dentro del scroll siguiente, y el
 * MiniPlayer aparecía con el color viejo y lo cambiaba medio segundo después. Por eso la regla es
 * por DIRECCIÓN y no por "¿está corriendo?".
 *
 * Devuelve una instancia ESTABLE, que es lo que hace gratis proveerla por un local estático: lo que
 * cambia son sus campos, y sólo cuando toca.
 *
 * @param preparingOrigin hay una fila PREPARÁNDOSE para abrir el player: es el frame anterior a que
 *   la capa cambie de target y el seed ya cambió, así que cuenta como apertura en vuelo.
 */
@Composable
fun rememberUnderlayAppColors(
    layerTransition: Transition<PlayerLayerState>,
    preparingOrigin: Boolean
): AppColorScheme {
    val source = LocalAppColors.current
    val base = MaterialTheme.colorScheme
    val holder = remember { AppColorScheme(source.toColorScheme(base)) }
    // Última versión ya volcada. Contenedor PLANO y no snapshot state: es memoria de esta función,
    // nadie depende de él, y como snapshot invalidaría en el mismo frame en que se escribe.
    val applied = remember { IntArray(1) { source.version } }
    // Leer `version` AQUÍ es la suscripción: `SideEffect` corre tras cada recomposición, pero si
    // nadie recompone no corre nunca, y sin esta lectura un cambio de color no despertaría a esta
    // función — la paleta de debajo se quedaría congelada para siempre.
    val version = source.version
    val settled = layerTransition.currentState == layerTransition.targetState && !layerTransition.isRunning
    val opening = layerTransition.targetState == PlayerLayerState.Expanded && !settled
    val hold = preparingOrigin || opening
    SideEffect {
        // La comparación con `applied` no es cosmética: este efecto corre tras CADA recomposición de
        // la pantalla, y sin ella se recorrerían los 48 roles por gusto en cada tick de posición.
        if (!hold && applied[0] != version) {
            applied[0] = version
            holder.updateFrom(source.toColorScheme(base))
            JankProbe.mark { "underlay: paleta nueva → repinta lo de debajo" }
        }
    }
    return holder
}
