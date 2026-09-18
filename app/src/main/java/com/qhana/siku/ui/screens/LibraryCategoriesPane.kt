package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qhana.siku.data.model.LibraryTabId
import com.qhana.siku.ui.SharedTransitionGate
import com.qhana.siku.ui.theme.AppColors
import kotlinx.coroutines.launch

/*
 * El destino **Biblioteca** del estilo moderno: la fila de pestañas ([LibraryCategoryTabs]) y el
 * pager con las listas ([LibraryCategoriesPane]).
 *
 * **Las dos viven DENTRO de la página**, y por eso entran y salen con el deslizamiento del pager de
 * los destinos, sin animación propia. Hubo media hora en que la fila se subió al bloque de cabecera
 * de `LibraryScreen` para quedar por encima del banner de sincronización, que si no partía en dos la
 * navegación (búsqueda → banner → pestañas). Aquello salió caro y mal: obligaba a izar el
 * `PagerState`, a medir el alto de la fila para que el contenido la acompañara, y a un
 * `AnimatedVisibility` cuya condición se leía de `settledPage` — o sea que las pestañas tardaban en
 * irse todo lo que durara el deslizamiento. La solución buena estaba un nivel más arriba: **el
 * banner ya no se muestra fuera de Inicio**, así que no hay nada de lo que quedar por encima.
 *
 * Sustituyen al hub de cuatro filas que hubo ese mismo día y que no llegó a device, por el motivo
 * que el usuario dio y que ya había descartado la idea en julio: **gasta una pantalla entera para
 * ser un menú de cuatro líneas**, y las categorías no son destinos a los que se entra y se sale —
 * son sitios entre los que se va y se viene. Con el hub, comparar Artistas y Álbumes eran cuatro
 * toques; aquí es un swipe.
 */

/**
 * Alto de la fila de pestañas = `TabRowDefaults`, el mismo 48dp del `Tab` de la fila clásica. Se
 * necesita como CONSTANTE —y no medido— porque la fila se dibuja encima del pager y el colchón de
 * las listas tiene que reservarlo: aquí no hay animación de entrada que seguir, la fila está o no
 * está según el destino.
 */
private val CategoryTabsHeight = 48.dp

/**
 * Aire entre la fila y el contenido: el mismo `HeaderContentGap` que separa el bloque de cabecera
 * del contenido en el modo clásico. Es la misma frontera, un nivel más abajo.
 */
private val CategoryTabsGap = 16.dp

/**
 * Margen de la fila contra los bordes de la pantalla: el mismo `TabRowEdgePadding` de la fila del
 * modo clásico, que a su vez es el margen de contenido de la app.
 */
private val CategoryTabsEdgePadding = 16.dp

/**
 * Ancho mínimo de una pestaña: el mínimo táctil. **Hay que bajarlo**, porque el default de la fila
 * scrollable (`TabRowDefaults.ScrollableTabRowMinTabWidth`) son 90dp y con cuatro etiquetas cortas
 * eso vuelve a estirar unas para dejar a la larga sin sitio — justo lo que la fila scrollable venía
 * a evitar. Se queda como red del mínimo táctil, no como reparto.
 */
private val CategoryTabMinWidth = 48.dp

/**
 * La fila de pestañas del destino Biblioteca.
 *
 * **Tabs SECUNDARIOS y de solo texto**: es el segundo nivel de navegación —el primero es la barra de
 * abajo— y eso es exactamente lo que el spec asigna a las secondary tabs. Sin glifo: los cuatro
 * nombres se leen solos, y un icono más por pestaña competiría con los tres de la barra de abajo.
 */
@Composable
private fun LibraryCategoryTabs(
    categories: List<LibraryPage.Tab>,
    pagerState: PagerState,
    /** Color del bloque; el caller lo comparte con la cabecera de búsqueda, que va pegada encima. */
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // `targetPage` y NO `currentPage`, por lo mismo que las cuatro presentaciones del selector de
    // `LibraryScreen` (ver la nota junto a su `rememberPagerState`): `animateScrollToPage` salta a
    // un viewport del destino cuando está a 3 o más páginas, y con cuatro categorías ir de la
    // última a la primera cumple esa condición — la pestaña activa parpadearía en la segunda.
    val selectedIndex = pagerState.targetPage.coerceIn(0, categories.lastIndex)

    // **SCROLLABLE y no la fija**, aunque sean solo cuatro (29 ago 2026). La fija reparte el ancho
    // a partes IGUALES: en un teléfono de 393dp toca a ~98 por pestaña, y descontando el padding
    // del `Tab` quedan ~66 para el texto — "Canciones" pide unos 70 en `titleSmall` y salía
    // "Cancion…". Es lo mismo que le pasó al selector de ReplayGain con `ChoiceGroupWidth.Equal`:
    // un reparto igualitario con etiquetas desiguales recorta a la más larga. Con la scrollable
    // cada pestaña mide lo suyo y, si caben todas, simplemente no hay scroll.
    //
    // `minTabWidth` bajado al mínimo táctil por el mismo motivo que en la fila del modo clásico: el
    // default de la scrollable son 90dp, que aquí volvería a estirar las cortas y a dejar sin sitio
    // a la larga.
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = containerColor,
        contentColor = AppColors.onSurface,
        edgePadding = CategoryTabsEdgePadding,
        minTabWidth = CategoryTabMinWidth,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                color = AppColors.primary,
                modifier = Modifier.tabIndicatorOffset(selectedIndex)
            )
        },
        // Sin divisor: la fila continúa el bloque de la búsqueda, y una línea ahí lo partiría en
        // dos. Es el mismo criterio que la fila del modo clásico.
        divider = {},
        modifier = modifier.fillMaxWidth()
    ) {
        categories.forEachIndexed { index, category ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = {
                    if (!selected) {
                        // El MISMO háptico que el resto de los selectores de la app: moverse entre
                        // hermanos se siente igual se toque donde se toque.
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    }
                },
                // EXPLÍCITOS: `Tab` define el color no seleccionado como "el mismo que el
                // seleccionado" y hereda el contentColor de la fila, así que sin esto las cuatro se
                // pintarían del color de la activa y ninguna se leería como inactiva.
                selectedContentColor = AppColors.primary,
                unselectedContentColor = AppColors.onSurfaceVariant,
                text = {
                    Text(
                        text = stringResource(category.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/**
 * El pager de las categorías.
 *
 * **Es el único pager que consume el gesto horizontal.** El de fuera —el de los tres destinos— va
 * con `userScrollEnabled = false`, y ésa es la condición que permite anidarlos: dos pagers
 * horizontales activos se pelean por el arrastre y gana el que primero lo reconoce. De paso es lo
 * que pide M3 (entre destinos de una barra de navegación no se desliza) y lo que el usuario
 * decidió: el swipe pasa a significar "moverme por la biblioteca".
 */
@Composable
internal fun LibraryCategoriesPane(
    categories: List<LibraryPage.Tab>,
    /** El colchón de la PÁGINA; aquí se le suma el alto de la fila antes de pasarlo a cada lista. */
    contentPadding: PaddingValues,
    /** Color del bloque de la fila; el caller lo comparte con la cabecera de búsqueda. */
    containerColor: Color,
    content: @Composable (LibraryTabId, PaddingValues) -> Unit
) {
    if (categories.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { categories.size })

    // Red por si la lista de categorías cambiara de tamaño en caliente: dejaría al pager apuntando
    // a una página que ya no existe. Hoy llegan FIJAS (ver `CategoryPages`: el estilo moderno no lee
    // la config de pestañas), así que esto no se dispara — se queda porque el componente recibe la
    // lista por parámetro y no puede prometer nada sobre ella.
    LaunchedEffect(categories.size) {
        if (pagerState.currentPage >= categories.size) {
            pagerState.scrollToPage(categories.lastIndex)
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val listInsets = remember(contentPadding, layoutDirection) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            top = contentPadding.calculateTopPadding() + CategoryTabsHeight + CategoryTabsGap,
            bottom = contentPadding.calculateBottomPadding()
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    HorizontalPager(
        state = pagerState,
        key = { it },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        // El MISMO gate que el pager de fuera, y por el mismo motivo: `beyondViewportPageCount` deja
        // la vecina compuesta y COLOCADA, y una punta colocada participa en el match aunque no se
        // vea. Anidado con el de fuera, que solo da scope al destino asentado — así que aquí solo
        // llega scope de verdad cuando Biblioteca está en pantalla.
        //
        // `settledPage` y no `currentPage`: con `currentPage` la re-declaración de las puntas cae
        // dentro del `measureAndLayout` que el pager ya está pagando.
        val isActivePage by remember(page, pagerState) {
            derivedStateOf { page == pagerState.settledPage }
        }
        SharedTransitionGate(visible = isActivePage) {
            content(categories[page].tab, listInsets)
        }
    }

        // La fila va ENCIMA del pager y no en una `Column` con él: así el contenido pasa por debajo
        // (lo reserva `listInsets`) y la fila se lee como continuación del bloque de cabecera, que
        // es lo que ya hace la fila del modo clásico.
        LibraryCategoryTabs(
            categories = categories,
            pagerState = pagerState,
            containerColor = containerColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = contentPadding.calculateTopPadding())
        )
    }
}
