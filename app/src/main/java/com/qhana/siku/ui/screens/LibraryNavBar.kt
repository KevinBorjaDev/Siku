package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appLibraryToolbarItemColors
import com.qhana.siku.ui.theme.appNavigationRailItemColors
import com.qhana.siku.ui.theme.appShortNavigationBarItemColors
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
 * componente, con su material, su elevación y sus destinos como `ToggleButton`. Lo que NO se
 * conserva del `ToggleButton` es su motion de fábrica —color que salta y morph al presionar—; ver
 * [LibraryToolbarDestination].
 *
 * **Lo que se pierde y hay que saberlo**: la fila de arriba SCROLLEA (las seis no caben en 393dp) y
 * un toolbar no puede hacerlo — dentro del contenedor flotante todos los destinos tienen que estar
 * a la vista. Por eso aquí el destino es de ancho fijo y la etiqueta del activo aparece **solo si
 * cabe**; ver [LibraryBottomToolbar].
 *
 * El rail es un tercer componente porque su eje es otro: apila en vertical, y ahí no hay píldora
 * con etiqueta al lado que valga. Los colores de los tres salen del MISMO sitio
 * (`appNavigationRailItemColors` / `appLibraryToolbarItemColors` / la píldora de `LibraryTabs`):
 * son proyecciones del MISMO destino del pager (`targetPage`; ver la nota junto al
 * `rememberPagerState` de `LibraryScreen`) y usan el mismo lenguaje de color.
 *
 * **Desde el 7 sep 2026 no divergen en NADA.** El toolbar tuvo su contenedor y su indicador
 * DERIVADOS del fondo mientras el fondo de página fue `surfaceContainer` —o sea el propio token del
 * toolbar, con el que la barra desaparecía—; con la página de vuelta en `surface` los dos vuelven a
 * su rol tabulado y las tres presentaciones usan literalmente los mismos cuatro colores. Ver
 * `appLibraryToolbarColors` para por qué la derivación tampoco podía funcionar: el toolbar flota
 * sobre contenido de distinta naturaleza según la pestaña (filas en Canciones, carátulas en Inicio),
 * así que no existe UN fondo contra el que medir.
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
 * Alto del contenedor = `NavigationBarTokens.ContainerHeight`, que es el `defaultMinSize` que el
 * propio `ShortNavigationBar` se aplica por dentro. Hace falta el número de ESTE lado porque la
 * barra es una CAPA y no un `bottomBar` que reserve su hueco: el colchón de las listas y el
 * desplazamiento del MiniPlayer se calculan a mano.
 *
 * Y da 64 con la anatomía que se usa (glifo arriba, etiqueta debajo), no por casualidad: 6 de aire +
 * 32 del indicador + 4 hasta la etiqueta + su línea + 6 suman justo eso. El
 * `TallContainerHeight` de 80 es de la `NavigationBar` clásica, más holgada.
 *
 * **Es un MÍNIMO, no una medida**: con el tamaño de texto del sistema muy ampliado la etiqueta crece
 * y el ítem con ella. No se persigue con una medición real porque quien necesita el número —la capa
 * del reproductor— vive fuera de esta composición, y pasárselo por estado sería el segundo escritor
 * que `libraryChrome` existe para evitar. El colchón de las listas absorbe la diferencia: ya lleva
 * encima el `FloatingBarListInset` del MiniPlayer.
 */
private val ShortNavigationBarHeight = 64.dp

/**
 * Cuánto SUBE la píldora del MiniPlayer cuando abajo vive la barra de navegación del estilo moderno:
 * **el alto del contenedor y nada más**.
 *
 * No lleva margen —la barra está PEGADA al borde, así que no hay aire debajo del que despegarse— ni
 * la navbar del sistema, aunque la barra sí la consuma: el mini ya la esquiva por su cuenta con su
 * propio inset, y sumarla aquí la contaría dos veces. Es el mismo razonamiento que
 * [LibraryBottomBarPillLift], con los dos sumandos que allí sí aplican quitados.
 */
internal val LibraryNavBarPillLift = ShortNavigationBarHeight

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
 * y hace falta el despegue.
 *
 * Es la MISMA razón por la que el MiniPlayer lleva su sombra: en M3 Expressive las piezas flotantes
 * (FAB, toolbars) SÍ tienen sombra, sutil y poco esparcida.
 *
 * **Level2 (3dp) y no el Level1 del componente** (7 sep 2026, visto en device). 1dp es lo máximo
 * que M3 le da a este toolbar (`ContainerExpandedElevationWithFab`), y ese número está dimensionado
 * para una pieza que flota sobre contenido PLANO: allí la sombra es un refuerzo y el tono hace el
 * trabajo. Aquí hay dos casos en los que el tono no puede hacer nada:
 *
 *  - en **Inicio** la barra flota sobre CARÁTULAS, donde no existe ninguna relación tonal contra la
 *    que separarse: la sombra es el único canal que queda;
 *  - en las listas, el contenido de debajo son tarjetas y no `surface`, así que la distancia tonal
 *    disponible es mucho menor de la que el componente asume (ver `appLibraryToolbarColors`).
 *
 * O sea que la desviación no es de gusto: el token supone un suelo que esta app no tiene.
 */
private val ToolbarShadowElevation = 3.dp

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
    pages: List<LibraryPage>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, pages.lastIndex)
    val haptics = LocalHapticFeedback.current
    val labels = pages.map { stringResource(it.titleRes) }
    val labelStyle = MaterialTheme.typography.labelMedium

    HorizontalFloatingToolbar(
        // Sin `leadingContent`/`trailingContent` no hay nada que colapsar: el toolbar está siempre
        // desplegado. El estado colapsado de este componente esconde esos dos slots, no el
        // contenido — y aquí el contenido son los destinos, que no pueden desaparecer.
        expanded = true,
        colors = appLibraryToolbarColors(),
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
            val showLabel = ToolbarItemSize * pages.size + ToolbarLabelExtra + widestLabel <= maxWidth

            Row(
                modifier = Modifier.fillMaxWidth(),
                // Lo que sobre se reparte como aire ENTRE destinos, no como un bloque centrado.
                // Cuando la etiqueta aparece ya no sobra nada y quedan a tope, que es el mismo
                // comportamiento que una navigation bar cuando se llena.
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.forEachIndexed { index, page ->
                    val selected = index == safeIndex
                    LibraryToolbarDestination(
                        iconName = page.iconName,
                        label = labels[index],
                        labelStyle = labelStyle,
                        selected = selected,
                        showLabel = showLabel,
                        onClick = {
                            if (!selected) {
                                // Tick de segmento: el háptico de moverse dentro de un grupo, el
                                // mismo que la fila de arriba y el rail.
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onTabSelected(index)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Un destino del toolbar de la biblioteca: el `ToggleButton` real, con el motion ALINEADO al de la
 * píldora de `LibraryTabs` y no el que trae de fábrica.
 *
 * **El `ToggleButton` de M3 se sentía duro por tres cosas, las tres de la librería** (leídas en las
 * fuentes de 1.5.0-alpha24, 13 sep 2026):
 *
 *  1. **El color SALTA.** `ToggleButton` elige `checkedContainerColor` o `containerColor` y se lo pasa
 *     crudo al `Surface`, sin animar: el indicador aparecía en un frame y desaparecía en otro.
 *  2. **Morph al presionar hacia `RoundedCornerShape(6.dp)` con `FastSpatial`**, el token que MÁS
 *     rebota (damping 0.6). `ToggleButtonDefaults.shapes(shape, checkedShape)` deja el `pressedShape`
 *     en ese default: del círculo a un casi-cuadrado y de vuelta con rebote, que es el carácter de un
 *     BOTÓN de acción, no de un destino de navegación.
 *  3. **La etiqueta aparecía con un `if`**: el destino cambiaba de ancho de golpe y el
 *     `SpaceBetween` reordenaba a los demás en el mismo frame.
 *
 * Lo que se hace, en el mismo orden:
 *
 *  1. Los dos colores se animan FUERA con `appEffectsSpec()` (effects: son color, damping 1.0) y se
 *     pasan como los DOS estados de `ToggleButtonColors`, así el componente reciba lo que reciba ya
 *     no tiene nada que conmutar. El contenedor inactivo es el MISMO rol a alfa 0 y no
 *     `Color.Transparent`, por el destello oscuro que ya documenta `LibraryTabs`.
 *  2. `pressedShape = CircleShape`: forma fija. Queda el ripple del `Surface`, recortado a la píldora,
 *     que es el mismo acuse de toque que tiene la fila de arriba — y el háptico.
 *  3. La etiqueta entra con `expandHorizontally` + fade, con los MISMOS specs de effects que
 *     `LibraryTabs` (entra con el default, sale con el rápido). El ancho se anima, así que el hueco del
 *     `SpaceBetween` se reparte frame a frame en vez de saltar. Sin rebote porque anima TAMAÑO: un
 *     spec spatial ahí oscila y cada oscilación es una pasada de layout (ver la nota en `LibraryTabs`).
 *
 * Es un composable propio y no el cuerpo del `forEach` para que el scope de recomposición de los dos
 * `animateColorAsState` sea el destino y no la fila: durante el fundido solo recomponen los dos
 * destinos que cambian, no los seis.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryToolbarDestination(
    iconName: String,
    label: String,
    labelStyle: TextStyle,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit
) {
    val baseColors = appLibraryToolbarItemColors()
    val containerColor by animateColorAsState(
        targetValue = baseColors.checkedContainerColor.copy(alpha = if (selected) 1f else 0f),
        animationSpec = appEffectsSpec(),
        label = "toolbarDestinationContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) baseColors.checkedContentColor else baseColors.contentColor,
        animationSpec = appEffectsSpec(),
        label = "toolbarDestinationContent"
    )

    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        // CircleShape en los TRES estados. El `checkedShape` de fábrica es más CUADRADO —"esto quedó
        // encendido"— y aquí el estado marcado no es un ajuste sino dónde estás: la forma la comparte
        // con la píldora de arriba y el indicador del rail. El `pressedShape` también, ver el KDoc.
        shapes = ToggleButtonDefaults.shapes(
            shape = CircleShape,
            pressedShape = CircleShape,
            checkedShape = CircleShape
        ),
        colors = baseColors.copy(
            containerColor = containerColor,
            contentColor = contentColor,
            checkedContainerColor = containerColor,
            checkedContentColor = contentColor
        ),
        // Sin sombra propia: ya está dentro de un contenedor elevado, y una segunda sombra ahí
        // dentro se lee como suciedad.
        elevation = null,
        contentPadding = PaddingValues(horizontal = ToolbarItemPadding),
        modifier = Modifier
            .height(ToolbarItemSize)
            // `widthIn` y no `width`: con solo glifo mide 48 exactos (12+24+12), y al aparecer la
            // etiqueta crece lo que el texto pida. Con el ajuste de tamaño de texto del sistema el
            // glifo también crece —`MaterialSymbol` es una fuente— y el mínimo deja de mandar solo.
            .widthIn(min = ToolbarItemSize)
            // El glifo no lleva descripción, así que sin esto un destino sin etiqueta visible sería
            // un botón mudo para el lector de pantalla.
            .semantics { contentDescription = label }
    ) {
        MaterialSymbol(iconName, size = TabIconSize, fill = selected)
        AnimatedVisibility(
            visible = selected && showLabel,
            enter = expandHorizontally(animationSpec = appEffectsSpec()) +
                fadeIn(animationSpec = appEffectsSpec()),
            exit = shrinkHorizontally(animationSpec = appFastEffectsSpec()) +
                fadeOut(animationSpec = appFastEffectsSpec())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

/**
 * La barra de navegación de abajo del **estilo moderno**: `ShortNavigationBar`, el componente real
 * de M3 Expressive para destinos top-level.
 *
 * **No es el toolbar flotante con otros colores, y esa es la decisión.** El toolbar
 * ([LibraryBottomToolbar]) representa hasta seis PESTAÑAS de un pager, que son vistas de una misma
 * pantalla; esto son tres DESTINOS de primer nivel, que es exactamente el caso que la navigation bar
 * tabula. Con tres, el toolbar flotante quedaba o estirado con dos huecos enormes o ceñido y
 * pequeño en medio de la pantalla — probadas las dos en device y descartadas.
 *
 * Ojo con la historia, porque parece que esto ya se descartó y NO es el mismo caso: el
 * `ShortNavigationBar` propio de julio y la `LibraryTabsRow` puesta en el slot `bottomBar` se
 * quitaron el 22 ago porque llevaban dentro las SEIS pestañas —"una fila de pestañas puesta abajo
 * sigue siendo una fila de pestañas"—. Lo que cambia aquí no es el componente sino lo que contiene.
 *
 * **Va como CAPA y no en el slot `bottomBar`**, igual que el rail y que el toolbar. No es por
 * apariencia —una barra apoyada sí podría reservar su hueco— sino para no partir en dos el reparto
 * de insets: el colchón de las listas ya se compone a mano en `LibraryScreen` para las otras dos
 * presentaciones, y con el slot puesto `paddingValues.calculateBottomPadding()` traería el alto en
 * un modo y cero en los otros, o sea dos aritméticas para lo mismo. El alto lo reserva `listInsets`
 * con [LibraryNavBarPillLift].
 *
 * **Su color es su TOKEN: `NavigationBarTokens.ContainerColor` = `surfaceContainer`** (7 sep 2026).
 * Estuvo derivado de la "horquilla" mientras el fondo de página era `surfaceContainer` y el token
 * habría hecho desaparecer la barra; desde que la página volvió a `surface` (4 sep) el token es un
 * escalón de contenedor limpio y no hay nada que derivar.
 *
 * **No es el mismo color que el rail, y eso NO es una incoherencia**: M3 tabula
 * `NavigationRailCollapsedTokens.ContainerColor` como `Surface`, porque un rail colapsado vive
 * pegado al borde y forma parte de la página, mientras que esta barra se apoya encima del contenido
 * que le pasa por debajo. Lo que sí comparten las tres presentaciones son los colores de sus ÍTEMS
 * (`appShortNavigationBarItemColors` / `appNavigationRailItemColors`, valores idénticos): son
 * proyecciones del mismo `pagerState` y ahí divergir sí sería un fallo. (El toolbar flotante es
 * otra cosa y sí deriva del fondo real, porque FLOTA y tiene que leerse como un objeto separado.)
 *
 * **`EqualWeight` y no `Centered`**: con tres destinos en un teléfono, repartir el ancho a partes
 * iguales es lo que M3 recomienda para pantallas estrechas, y da tres blancos táctiles del mismo
 * tamaño — el centrado deja los extremos más cerca del borde de lo que el pulgar espera.
 *
 * **La etiqueta va SIEMPRE y en los tres**, que es lo que hace una navigation bar normal: aquí no
 * hace falta la maquinaria de "solo si cabe" del toolbar, porque el glifo y el texto van en columna
 * —no compiten por el ancho— y el componente reparte y recorta él mismo si un idioma o una fuente
 * ampliada lo piden.
 */
// Sin `@OptIn`: `ShortNavigationBar`, su item, el arrangement y la posición del icono son API
// ESTABLE en 1.5.0-alpha24 (comprobado en las fuentes de la versión pineada). Un opt-in de más no
// rompe nada, pero dice que esto se apoya en algo provisional cuando no es el caso.
@Composable
internal fun LibraryNavigationBar(
    pages: List<LibraryPage>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, pages.lastIndex)
    val haptics = LocalHapticFeedback.current

    ShortNavigationBar(
        containerColor = containerColor,
        contentColor = AppColors.onSurfaceVariant,
        // `SafeBarInsets` y no el default del componente (`systemBarsForVisualComponents`): girado,
        // lo que hay que esquivar en los costados es también la perforación de pantalla, que ese
        // default no cubre. Es el mismo juego que consumen el rail y el resto del cromo de la app.
        windowInsets = SafeBarInsets.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        ),
        arrangement = ShortNavigationBarArrangement.EqualWeight,
        modifier = modifier
    ) {
        // **Ripple APAGADO** (`LocalRippleConfiguration provides null`, el mecanismo que M3
        // documenta para eso, y el mismo que ya usa `LibraryTabs` en la fila del modo clásico).
        //
        // `NavigationItem` dibuja DOS capas superpuestas: un `IndicatorRipple` con la forma del
        // indicador —gris, porque hereda el `onSurfaceVariant` del contenido de la barra— y el
        // `Indicator` de color. Al tocar un destino se veían las dos moverse a la vez, con ritmos
        // distintos: un borrón gris creciendo bajo el glifo y, encima, el indicador tiñéndose. Dos
        // animaciones para un solo toque (visto en device el 30 ago 2026).
        //
        // De las dos, la que INFORMA es la de color: dice dónde estás. El ripple gris es feedback
        // de pulsación, y aquí es redundante — el indicador aparece en el mismo gesto y en el mismo
        // sitio. Se conserva el háptico, que es el otro acuse de recibo del toque.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            pages.forEachIndexed { index, page ->
                val selected = index == safeIndex
                ShortNavigationBarItem(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            // Tick de segmento: el MISMO háptico que la fila de arriba, el toolbar y el
                            // rail — moverse entre destinos se siente igual se toque donde se toque.
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onTabSelected(index)
                        }
                    },
                    icon = { MaterialSymbol(page.iconName, size = TabIconSize, fill = selected) },
                    label = { Text(text = stringResource(page.titleRes)) },
                    // **`Top`, que es la anatomía de la navigation bar: glifo arriba, etiqueta debajo y
                    // el indicador rodeando SOLO el glifo.** Es el default del componente y se deja
                    // escrito igual, porque es justo la línea que distingue esta pieza del toolbar.
                    //
                    // Estuvo un rato en `Start` —glifo y etiqueta en la misma línea, dentro de un
                    // indicador que envuelve a los dos— y el usuario lo cantó en device: así no se ve
                    // una barra de navegación, se ve el toolbar de antes con el contenedor estirado.
                    // Tenía sentido en el toolbar, donde el destino ES un `ToggleButton` con su
                    // contenido en fila; aquí es lo que hay que no repetir.
                    iconPosition = NavigationItemIconPosition.Top,
                    colors = appShortNavigationBarItemColors()
                )
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
    pages: List<LibraryPage>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, pages.lastIndex)
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
                pages.forEachIndexed { index, page ->
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
                        icon = { MaterialSymbol(page.iconName, size = TabIconSize, fill = selected) },
                        label = {
                            Text(
                                text = stringResource(page.titleRes),
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
