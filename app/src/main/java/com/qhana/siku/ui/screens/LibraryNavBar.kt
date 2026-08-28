package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appLibraryToolbarItemColors
import com.qhana.siku.ui.theme.appNavigationRailItemColors
import com.qhana.siku.ui.theme.appLibraryToolbarColors

/*
 * El navigation RAIL de la biblioteca (horizontal, siempre), el TOOLBAR flotante de abajo y las
 * medidas que la capa del reproductor necesita saber de él.
 *
 * **El selector de abajo es un `HorizontalFloatingToolbar`, no una barra pegada al borde**
 * (22 ago 2026). Antes de esto reusó la MISMA `LibraryTabsRow` de arriba puesta en el slot
 * `bottomBar`, y antes de eso hubo una imitación con `ShortNavigationBar`; el motivo del cambio es
 * el que el usuario dio al verlas las dos en device: ninguna se siente Expressive. Una fila de
 * pestañas puesta abajo sigue siendo una fila de pestañas — el toolbar flotante es otro
 * componente, con su material, su elevación y sus destinos como `ToggleButton`, que morfean de
 * forma al presionarse.
 *
 * **Lo que se pierde y hay que saberlo**: la fila de arriba SCROLLEA (las seis no caben en 393dp) y
 * un toolbar no puede hacerlo — dentro del contenedor flotante todos los destinos tienen que estar
 * a la vista. Por eso aquí el destino es de ancho fijo y la etiqueta del activo aparece **solo si
 * cabe**; ver [LibraryBottomToolbar].
 *
 * El rail es un tercer componente porque su eje es otro: apila en vertical, y ahí no hay píldora
 * con etiqueta al lado que valga. Los colores de los tres salen del MISMO sitio
 * (`appNavigationRailItemColors` / `appLibraryToolbarItemColors` / la píldora de `LibraryTabs`):
 * son proyecciones del MISMO `pagerState.currentPage` y usan el mismo lenguaje de color.
 *
 * **La ÚNICA divergencia es el TONO del indicador activo del toolbar, y es obligada** (27 ago 2026):
 * la fila y el rail se apoyan en `surfaceContainerHigh`, doce puntos por encima de
 * `secondaryContainer`, así que ahí el rol crudo se lee. El toolbar FLOTA sobre el fondo de página y
 * por eso su contenedor se derivó de ese fondo —la escala neutra no le daba separación, ver
 * `appLibraryToolbarColors`—, de modo que su indicador tiene que derivarse del mismo sitio o queda
 * fundido en su propia barra. El rol y la forma son los mismos; lo que cambia es contra qué se mide.
 */

/**
 * Cuánto SUBE la píldora del MiniPlayer cuando el selector vive abajo: **el margen del toolbar más
 * su alto**.
 *
 * El mini conserva su margen de siempre y solo cambia contra QUÉ se apoya — `FloatingBarBottomMargin`
 * contra el borde de la pantalla, o contra el toolbar—, así que el desplazamiento es exactamente lo
 * que el toolbar ocupa desde ese borde. Hubo una versión con un margen propio de 8dp (22 ago 2026) y
 * en device se veía pegado: una pieza flotante no puede separarse de su suelo menos de lo que se
 * separaba del borde.
 *
 * **Ojo con la diferencia contra la barra que hubo antes**: aquélla iba en el slot `bottomBar` y
 * CONSUMÍA la navbar del sistema, así que su alto ya la incluía. El toolbar FLOTA: la navbar la
 * esquiva él con su propio inset y la esquiva el mini con el suyo, de modo que aquí no entra — si
 * se sumara, se contaría dos veces.
 */
internal val LibraryBottomBarPillLift =
    ComponentConfig.FloatingBarBottomMargin + ComponentConfig.FloatingToolbarHeight

/**
 * El glifo en dp. `TabIconSize` está en **sp** porque `MaterialSymbol` es una fuente variable y el
 * icono crece con el ajuste de tamaño de texto del sistema; para calcular anchos de caja hace falta
 * su valor nominal en dp, que es el que M3 tabula (`IconSize`).
 *
 * **Estas cuatro constantes van en ESTE orden y no en otro**: las propiedades top-level se
 * inicializan en orden de declaración, así que una que lea a otra declarada más abajo se queda con
 * un 0 en runtime, sin error de compilación. Ya pasó con `TabsRowHeight`.
 */
private val TabIconSizeDp = 24.dp

/**
 * Lado del destino del toolbar: **48dp**, el mínimo táctil, que es también lo que deja libre el
 * contenedor (`FloatingToolbarHeight` 64 − 2× `FloatingBarInnerPadding` 8). Mismo diámetro que los
 * botones del toolbar del NowPlaying, que es el otro sitio donde la app usa este componente.
 */
private val ToolbarItemSize = 48.dp

/**
 * Aire a cada lado del contenido del destino: lo que sobra del lado ([ToolbarItemSize]) una vez
 * puesto el glifo, o sea **12dp**. DERIVADO y no escrito: es lo que hace que un destino de solo
 * glifo mida exactamente 48 y que, al aparecer la etiqueta, el texto quede a la misma distancia del
 * borde que el icono del suyo.
 */
private val ToolbarItemPadding = (ToolbarItemSize - TabIconSizeDp) / 2

/** Gap entre el glifo y su etiqueta: el mismo [TabIconGap] de la píldora de arriba. */
private val ToolbarLabelGap = 4.dp

/**
 * Ancho que necesita la etiqueta más larga para que el toolbar la pueda mostrar, aparte del texto:
 * el gap glifo→texto más el aire del otro extremo.
 */
private val ToolbarLabelExtra = ToolbarLabelGap + ToolbarItemPadding

/**
 * Sombra del toolbar: **1dp**, o sea `ElevationTokens.Level1`.
 *
 * Hay que pasarla porque **el default de `HorizontalFloatingToolbar` sin FAB es `Level0`, cero**
 * (`FloatingToolbarDefaults.ContainerExpandedElevation`): tal cual sale de la caja, el toolbar se
 * apoya solo en su color, y acá el color no basta — flota sobre listas cuyo contenido es `surface`
 * y hace falta el despegue. 1dp no es un número al azar: es lo que M3 le da a este mismo componente
 * cuando lleva FAB (`ContainerExpandedElevationWithFab`), o sea el escalón que la propia librería
 * considera suficiente para separarlo del fondo sin convertirlo en una tarjeta.
 *
 * Es la MISMA razón por la que el MiniPlayer lleva su sombra: en M3 Expressive las piezas flotantes
 * (FAB, toolbars) SÍ tienen sombra, sutil y poco esparcida.
 */
private val ToolbarShadowElevation = 1.dp

/**
 * El selector de la biblioteca cuando vive ABAJO: un `HorizontalFloatingToolbar` con los destinos
 * como `ToggleButton`.
 *
 * **La etiqueta del destino activo aparece SOLO SI CABE, y la decisión se toma con la etiqueta más
 * LARGA de las visibles, no con la activa.** Las dos mitades de esa regla son deliberadas:
 *
 * - *Solo si cabe*, porque un toolbar no scrollea. La fila de arriba se permite seis destinos con
 *   etiqueta porque lo que no entra se alcanza deslizando; acá, un destino fuera del contenedor no
 *   existe. Antes que recortar el texto con una elipsis —"Cancione…" no es un nombre— o bajar el
 *   destino por debajo del mínimo táctil, se cae a solo glifos.
 * - *Con la más larga*, porque si la decisión se tomara con la activa, la barra mostraría etiqueta
 *   en "Inicio" y "Listas" y no en "Canciones": el mismo control cambiando de forma según dónde
 *   estés. Así, o la lleva siempre o no la lleva nunca.
 *
 * La aritmética, con el ancho útil que deja el componente (pantalla − 2× `FloatingBarSideMargin` −
 * 2× `FloatingBarInnerPadding`): cada destino ocupa [ToolbarItemSize] y el activo suma
 * [ToolbarLabelExtra] más su texto. Con seis destinos en un teléfono de 393dp quedan **~49dp** para
 * la etiqueta, que es el filo exacto de "Canciones" en `labelMedium`; con cinco sobran ~97 y entra
 * cualquiera. Por eso el cálculo se hace con `TextMeasurer` en runtime y no con una tabla: depende
 * de la fuente, del idioma y del ajuste de tamaño de texto del sistema.
 *
 * La etiqueta va en **`labelMedium`**, que es `NavigationBarTokens.LabelTextFont` — el token del
 * componente de navegación equivalente, no un tamaño elegido para que quepa.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryBottomToolbar(
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    /**
     * Fondo de PÁGINA sobre el que flota el toolbar, del que se derivan su contenedor y el
     * indicador del destino activo (ver `appLibraryToolbarColors`). Va como parámetro y no leído
     * aquí porque el fondo lo decide la pantalla —es el `containerColor` de su `Scaffold`—, y dos
     * copias de ese color serían dos verdades.
     */
    background: Color,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val haptics = LocalHapticFeedback.current
    val labels = tabs.map { stringResource(it.titleRes) }
    val labelStyle = MaterialTheme.typography.labelMedium

    HorizontalFloatingToolbar(
        // Sin `leadingContent`/`trailingContent` no hay nada que colapsar: el toolbar está siempre
        // desplegado. El estado colapsado de este componente esconde esos dos slots, no el
        // contenido — y aquí el contenido son los destinos, que no pueden desaparecer.
        expanded = true,
        colors = appLibraryToolbarColors(background),
        // Mismo padding que el toolbar del NowPlaying: es el token del componente
        // (`FloatingToolbarTokens.ContainerLeadingSpace`), y con él los destinos de 48 caben
        // exactos en los 64 de alto.
        contentPadding = PaddingValues(ComponentConfig.FloatingBarInnerPadding),
        // Las DOS, aunque con `expanded = true` solo se alcance la primera: el componente interpola
        // entre ellas con un `animateDpAsState`, así que dejar la otra en el default sería plantar
        // un salto de sombra para el día que alguien toque `expanded`.
        expandedShadowElevation = ToolbarShadowElevation,
        collapsedShadowElevation = ToolbarShadowElevation,
        modifier = modifier
            .fillMaxWidth()
            // El toolbar FLOTA, así que esquiva la navbar del sistema y la perforación en vez de
            // consumirlas: no llega a ningún borde. Es lo contrario de la barra que hubo antes, que
            // teñía hasta el borde y apartaba solo su contenido.
            .windowInsetsPadding(
                SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .padding(
                start = ComponentConfig.FloatingBarSideMargin,
                end = ComponentConfig.FloatingBarSideMargin,
                bottom = ComponentConfig.FloatingBarBottomMargin
            )
            // Alto FORZADO al token: el componente acolcha sus touch targets por dentro
            // (`minimumInteractiveBalancedPadding`) y sin esto mide ~72dp aunque se le pase el
            // contentPadding correcto. Medido en device con el toolbar del NowPlaying, que lleva
            // esta misma línea por el mismo motivo.
            .height(ComponentConfig.FloatingToolbarHeight)
    ) {
        // `weight(1f)` dentro del RowScope del toolbar: el contenido ocupa todo el ancho útil, que
        // es lo que pide el reparto "a lo ancho". Sin esto el Row del componente centra el bloque y
        // los destinos quedarían apiñados en el medio.
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            // La etiqueta MÁS LARGA de las visibles. En `remember` porque medir seis textos en cada
            // recomposición sería trabajo repetido para un resultado que solo cambia si cambian las
            // pestañas, la tipografía o la densidad.
            val widestLabel: Dp = remember(labels, labelStyle, density, textMeasurer) {
                with(density) {
                    labels.maxOf { textMeasurer.measure(it, labelStyle).size.width }.toDp()
                }
            }
            val showLabel = ToolbarItemSize * tabs.size + ToolbarLabelExtra + widestLabel <= maxWidth

            Row(
                modifier = Modifier.fillMaxWidth(),
                // Lo que sobre se reparte como aire ENTRE destinos, no como un bloque centrado.
                // Cuando la etiqueta aparece ya no sobra nada y quedan a tope, que es el mismo
                // comportamiento que una navigation bar cuando se llena.
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == safeIndex
                    val label = labels[index]
                    ToggleButton(
                        checked = selected,
                        onCheckedChange = {
                            if (!selected) {
                                // Tick de segmento: el háptico de moverse dentro de un grupo, el
                                // mismo que la fila de arriba y el rail.
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onTabSelected(index)
                            }
                        },
                        // CircleShape en los tres estados. `ToggleButtonDefaults.shapes()` trae un
                        // `checkedShape` más CUADRADO —el morph que M3 usa para "esto quedó
                        // encendido"—, y aquí el estado marcado no es un ajuste sino dónde estás:
                        // la forma la comparte con la píldora de arriba y el indicador del rail. Lo
                        // que sí se conserva es el morph del PRESSED, que es el carácter del toque.
                        shapes = ToggleButtonDefaults.shapes(
                            shape = CircleShape,
                            checkedShape = CircleShape
                        ),
                        colors = appLibraryToolbarItemColors(background),
                        // Sin sombra propia: ya está dentro de un contenedor elevado, y una segunda
                        // sombra ahí dentro se lee como suciedad.
                        elevation = null,
                        contentPadding = PaddingValues(horizontal = ToolbarItemPadding),
                        modifier = Modifier
                            .height(ToolbarItemSize)
                            // `widthIn` y no `width`: con solo glifo mide 48 exactos (12+24+12), y
                            // al aparecer la etiqueta crece lo que el texto pida. Con el ajuste de
                            // tamaño de texto del sistema el glifo también crece —`MaterialSymbol`
                            // es una fuente— y el mínimo deja de mandar solo.
                            .widthIn(min = ToolbarItemSize)
                            // El glifo no lleva descripción, así que sin esto un destino sin
                            // etiqueta visible sería un botón mudo para el lector de pantalla.
                            .semantics { contentDescription = label }
                    ) {
                        MaterialSymbol(tab.iconName, size = TabIconSize, fill = selected)
                        if (selected && showLabel) {
                            Spacer(modifier = Modifier.width(ToolbarLabelGap))
                            Text(
                                text = label,
                                style = labelStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Ancho del rail = `NavigationRailCollapsedTokens.NarrowContainerWidth`, también `internal` allá.
 *
 * **Es la variante NARROW a propósito, y no el `WideNavigationRail` de 96dp.** El ítem del rail
 * ancho mide 64dp de alto AUNQUE no lleve etiqueta (`TopIconItemMinHeight`) y su `contentPadding`
 * reserva 44dp arriba y otros 44 abajo para un `header` (menú o FAB) que aquí no existe: seis
 * destinos pasan de 490dp, muy por encima de lo que deja un teléfono girado, y ni con el padding a
 * cero entran. El narrow ronda los 56 por ítem, y `alwaysShowLabel = false` da de fábrica lo que
 * aquí se quiere: etiqueta solo en la activa.
 *
 * **Lo que NO hay que volver a escribir es una CUENTA de cuántos destinos caben** (27 ago 2026).
 * Este KDoc decía "6x56 + 5x4 + 8 = 364 y cabe, con el ítem a alto FIJO", y las dos mitades eran
 * falsas: (1) el presupuesto no descontaba los insets VERTICALES, que el propio `NavigationRail`
 * consume con su `windowInsetsPadding` — barra de estado más barra o gesto de navegación, entre
 * 24 y 48dp —, ni contaba con que el alto útil baja al subir el tamaño de pantalla del sistema
 * (medido en device: **~360dp**, no los ~369 que suponía); y (2) los 56 son un MÍNIMO, no un alto
 * fijo — `NavigationRailItem` aplica `defaultMinSize(minHeight = NavigationRailItemHeight)` y,
 * como aquí la `label` NUNCA es null (`alwaysShowLabel` decide si se DIBUJA, no si existe), todos
 * los ítems se miden con `placeLabelAndIcon`, cuyo alto es `icono + 8 + etiqueta + 2x padding`.
 * El glifo va en **sp** ([TabIconSize]) y la etiqueta es `labelMedium`, así que el ítem CRECE con
 * la escala tipográfica del sistema: a fontScale 1.3 ronda los 68dp y seis pasan de 430.
 *
 * Y el desbordamiento no degrada solo: `placeLabelAndIcon` hace su `layout` sin `constrainHeight`,
 * de modo que el ítem reporta su alto aunque le pasen menos, y un `Arrangement.spacedBy` centrado
 * con espacio libre negativo coloca con offset negativo — los destinos se salen por arriba y por
 * abajo y el `Surface` del rail los recorta. En la 1.2.0 eso dejaba la sexta pestaña **cortada e
 * inalcanzable**, porque el rail es la UNICA presentación del selector en horizontal.
 *
 * Por eso cuántos caben lo decide el LAYOUT y no una constante: ver [LibraryNavRail].
 */
internal val LibraryRailWidth = 80.dp

/**
 * Navigation rail de la biblioteca, para cuando el teléfono está girado. Va SIEMPRE en horizontal,
 * con el modo de pestañas abajo encendido o no: allí lo escaso es el alto, y una fila más bajo la
 * búsqueda se lo come sin necesidad.
 *
 * `alwaysShowLabel = false` es exactamente el comportamiento que la barra de abajo tiene que
 * emular a mano, aquí de fábrica: la etiqueta aparece solo en la activa y el componente centra el
 * glifo cuando no la hay.
 *
 * **Centrado si cabe, con scroll si no**, y la decisión la toma el LAYOUT — no una cuenta. El
 * `BoxWithConstraints` va DENTRO del `NavigationRail`, o sea después de que el componente haya
 * aplicado sus `windowInsets` y su padding vertical, así que su `maxHeight` es el alto útil REAL
 * del rail en este device, con esta barra de estado, esta perforación y este tamaño de pantalla.
 * Desde ahí, `heightIn(min = ...)` dentro de un `verticalScroll` hace las dos cosas con un solo
 * layout: si los destinos ocupan menos que el viewport, la columna se estira hasta él y el
 * `Arrangement.spacedBy(_, CenterVertically)` los CENTRA igual que antes (y no hay scroll posible,
 * porque contenido y viewport miden lo mismo); si ocupan más, la columna crece y el rail scrollea.
 * Es el mismo recurso que ya usa `OnboardingStep` para anclar contenido dentro de un scroll.
 *
 * **No es un adorno defensivo: hasta el 27 ago 2026 la sexta pestaña quedaba cortada fuera del
 * `Surface` y no había forma de alcanzarla** — el rail es la única presentación del selector en
 * horizontal, así que un destino recortado es un destino que desaparece de la app. Por qué la
 * cuenta tabulada no podía funcionar está en el KDoc de [LibraryRailWidth].
 *
 * Un ítem NO se puede encoger para ganar espacio: 48dp es el mínimo táctil y su alto lo pone el
 * contenido, que crece con la escala tipográfica del sistema. La palanca legítima sigue siendo el
 * número de destinos, que el usuario ya controla desde Ajustes → Apariencia → Pestañas.
 */
@Composable
internal fun LibraryNavRail(
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    NavigationRail(
        modifier = modifier,
        containerColor = containerColor,
        // Explícito por el mismo motivo que en la barra de abajo.
        contentColor = AppColors.onSurfaceVariant,
        // En horizontal la perforación cae en un COSTADO, o sea justo donde vive el rail: el
        // contenedor llega al borde y son los ítems los que se apartan.
        windowInsets = SafeBarInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            // El alto útil con los insets del componente YA descontados: es lo que permite que
            // aquí no haga falta saber cuánto mide un ítem ni cuántos hay.
            val viewportHeight = maxHeight
            // Los destinos CENTRADOS en la altura del rail. Sin esto se apilan arriba y, con pocas
            // pestañas visibles, la mitad de abajo queda vacía frente a una lista que sí llega al
            // borde. `NavigationRail` no expone `verticalArrangement`, de ahí la Column de por medio.
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    // `min` y no `height`: con los destinos cabiendo, la columna mide exactamente
                    // el viewport y el arrangement los centra —el comportamiento de siempre—; al
                    // no caber, crece y el scroll es lo que impide que se pierda ninguno.
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.spacedBy(
                    NavigationRailItemGap,
                    Alignment.CenterVertically
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == safeIndex
                    // Al cambiar de pestaña deslizando el pager, la activa puede quedar fuera del
                    // scroll: el rail es un ESPEJO del pager y tiene que enseñar dónde estás. Con
                    // todo cabiendo es no-op (no hay nada que desplazar), así que no se condiciona
                    // — y condicionarlo leyendo el estado del propio scroll sería cerrar el lazo
                    // que la regla 4 de motion prohíbe.
                    val bringIntoView = remember { BringIntoViewRequester() }
                    LaunchedEffect(selected) {
                        if (selected) bringIntoView.bringIntoView()
                    }
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onTabSelected(index)
                            }
                        },
                        icon = { MaterialSymbol(tab.iconName, size = TabIconSize, fill = selected) },
                        label = {
                            Text(
                                text = stringResource(tab.titleRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        alwaysShowLabel = false,
                        colors = appNavigationRailItemColors(),
                        modifier = Modifier.bringIntoViewRequester(bringIntoView)
                    )
                }
            }
        }
    }
}

/**
 * Aire entre ítems del rail: `NavigationRailVerticalPadding`, el mismo que aplica el componente
 * cuando coloca su contenido él solo (también `internal` en material3). Al meter la Column de por
 * medio para centrar, hay que reponerlo a mano.
 */
private val NavigationRailItemGap = 4.dp
