package com.qhana.siku.ui.screens

import com.qhana.siku.data.local.identityKey
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.compose.ui.res.pluralStringResource
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.model.SortSpec
import com.qhana.siku.ui.components.AdaptiveGridCells
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.AlbumTileCard
import com.qhana.siku.ui.components.FilteredEmptyHint
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SortChip
import com.qhana.siku.ui.components.SourceFilterChips
import com.qhana.siku.ui.components.CountChip
import com.qhana.siku.ui.components.PAGE_CHROME_OVERLAY_Z
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.theme.AppColors

/**
 * Pestaña "Álbumes": cuadrícula de 2 columnas con carátula representativa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumsScreen(
    albums: List<AlbumSummary>,
    onAlbumClick: (String, String?) -> Unit,
    onPlayAlbum: (AlbumSummary) -> Unit,
    /** Encola TODAS las canciones del álbum al final de la cola, desde el overflow de la tarjeta. */
    onAddAlbumToQueue: (AlbumSummary) -> Unit,
    contentPadding: PaddingValues,
    sortOrder: SortSpec<AlbumSortOrder>,
    onSortOrderChange: (SortSpec<AlbumSortOrder>) -> Unit,
    sourceFilters: Set<SongSourceFilter>,
    onToggleSourceFilter: (SongSourceFilter) -> Unit,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Solo el vacío REAL sale por aquí; con filtro activo el toolbar se conserva (ver Artistas).
    if (albums.isEmpty() && sourceFilters.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaterialSymbol(
                    "album",
                    size = 64.sp,
                    color = AppColors.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.album_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        return
    }

    val layoutDirection = LocalLayoutDirection.current
    val sideStart = 16.dp + contentPadding.calculateStartPadding(layoutDirection)
    val sideEnd = 16.dp + contentPadding.calculateEndPadding(layoutDirection)
    val contentTop = contentPadding.calculateTopPadding()

    val groupByYear = sortOrder.criterion == AlbumSortOrder.YEAR
    val unknownYearLabel = stringResource(R.string.album_year_unknown)
    // Secciones en el orden que trae SQL: `groupBy` devuelve un LinkedHashMap, así que aquí no se
    // reordena nada. Si se volviera a ordenar en Kotlin habría dos criterios para lo mismo y
    // bastaría con que uno cambiara (el desempate, los sin año) para que las cabeceras dejaran de
    // describir sus propias secciones.
    val sections = remember(albums, groupByYear) {
        if (groupByYear) albums.groupBy { it.year } else emptyMap()
    }
    // Etiqueta de cada cabecera, por su key.
    val headerLabels = remember(sections, unknownYearLabel) {
        sections.keys.associate { year -> yearHeaderKey(year) to (year?.toString() ?: unknownYearLabel) }
    }
    // De la key de CUALQUIER item de una sección (cabecera o tarjeta) a la key de su cabecera: la
    // capa fija tiene que saber a qué sección pertenece lo primero que se ve, sea lo que sea.
    // `Any` como clave porque las keys de un lazy layout son `Any`.
    val sectionHeaderOf = remember(sections) {
        buildMap<Any, String> {
            sections.forEach { (year, albumsOfYear) ->
                val headerKey = yearHeaderKey(year)
                put(headerKey, headerKey)
                // La MISMA key que la tarjeta lleva en el grid (`identityKey`, v29). Con el nombre a
                // secas, las tarjetas de álbumes con ALBUMARTIST no se encontraban en el mapa y la
                // capa fija desaparecía en cuanto una de ellas era lo primero visible.
                albumsOfYear.forEach { put(it.identityKey, headerKey) }
            }
        }
    }

    val gridState = rememberLazyGridState()
    // La cabecera fija NO puede ser `stickyHeader`: esa API ancla el header en
    // `-beforeContentPadding` (ver `StickyItemsPlacement.StickToTopPlacement`), o sea contra el
    // borde FÍSICO del viewport — y en esta pantalla la cuadrícula pasa a propósito por debajo del
    // chrome opaco (topbar + búsqueda + pestañas), así que el header se pegaba detrás de él y no se
    // veía nunca. Lo que la sustituye es una capa propia (ver [PinnedYearHeader]).
    //
    // Aquí solo se decide QUÉ sección está fija. `derivedStateOf` porque `layoutInfo` cambia en
    // CADA frame de scroll: así solo se recompone al cambiar de sección. DÓNDE se coloca la capa
    // (el empuje) cambia por frame y por eso no va aquí, sino en la fase de layout.
    // **El 0 de `offset` es el inicio del CONTENIDO, no el del viewport**:
    // `viewportStartOffset = -beforeContentPadding` (ver `LazyGridMeasure`).
    //
    // **Dónde se fija el año: con su TEXTO en el 0, donde descansa el contenido** — o sea la cabecera
    // colocada su padding superior por encima. NO en el borde del chrome: esta pantalla no lo conoce
    // (solo recibe un `contentPadding`, que mezcla chrome y aire en proporciones distintas según el
    // modo de navegación), y cualquier cuenta para deducirlo es una estimación que acierta en un
    // modo o en un dispositivo y falla en otro — se probó y dejaba el año pegado en uno y con hueco
    // en otro. El punto de reposo, en cambio, es un CONTRATO que cada modo ya fija: es donde el
    // diseño dice que empieza el contenido, así que alinear ahí no depende de ninguna medida.
    val pinLinePx = with(LocalDensity.current) { -YearHeaderTopPadding.roundToPx() }
    val pinnedHeaderKey = remember(sectionHeaderOf, pinLinePx) {
        derivedStateOf {
            // La sección fija es la del primer item que asoma por debajo de esa línea. El toolbar y
            // el aviso de filtro vacío no están en el mapa: sobre ellos no hay capa.
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.offset.y + it.size.height > pinLinePx }
                ?.let { sectionHeaderOf[it.key] }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 2 columnas de tarjetas (Card): carátula con badge de nº de canciones arriba, y en el
        // contenido el nombre/artista + botón de play.
        LazyVerticalGrid(
            // Adaptativa CON SUELO de dos columnas, no `GridCells.Adaptive` a secas: en vertical
            // salen siempre las dos de siempre —en cualquier teléfono, no solo en los de 393dp— y
            // solo se añaden columnas cuando la ventana da de sobra. Ver [AdaptiveGridCells].
            columns = AdaptiveGridCells(ComponentConfig.GridTileMinWidth),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Se SUMA lo que llega en vez de descartarlo: si el contenedor inyecta un inset
                // lateral (la perforación en horizontal), un 16 fijo lo tiraría.
                start = sideStart,
                end = sideEnd,
                // El top que llega incluye el alto del header (el contenido pasa por debajo
                // de TopBar+tabs): NO descartarlo o la cuadrícula nace tapada por las tabs.
                top = contentTop + GridTopGap,
                bottom = contentPadding.calculateBottomPadding() + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Toolbar de la cuadrícula: conteo + orden + chips de origen, mismo FlowRow (envuelve,
            // sin scroll horizontal) que Artistas y la pestaña Todas. El conteo refleja lo filtrado.
            item(key = "album_toolbar", span = { GridItemSpan(maxLineSpan) }, contentType = "listToolbar") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    // Centrar cada fila: los chips ya miden todos 32dp (ver `CountChip`), pero el
                    // `SortChip` crece con el ajuste de tamaño de texto del sistema y el de
                    // conteo no tiene por qué acompañarlo — alineados arriba, se descuadran.
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CountChip {
                        Text(
                            text = pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                        )
                    }
                    SortChip(
                        current = sortOrder,
                        options = listOf(
                            R.string.sort_by_name to AlbumSortOrder.NAME,
                            R.string.sort_by_artist to AlbumSortOrder.ARTIST,
                            R.string.sort_by_year to AlbumSortOrder.YEAR,
                            R.string.sort_by_date_added to AlbumSortOrder.DATE_ADDED
                        ),
                        onChange = onSortOrderChange
                    )
                    SourceFilterChips(sourceFilters, showLocalChip, showCloudChips, onToggleSourceFilter)
                }
            }
            // Filtro de origen sin resultados: aviso que conserva el toolbar.
            if (albums.isEmpty()) {
                item(key = "album_filtered_empty", span = { GridItemSpan(maxLineSpan) }) {
                    FilteredEmptyHint()
                }
            }
            // La tarjeta, escrita UNA vez para las dos ramas de abajo (agrupada y plana):
            // duplicarla sería garantizar que un cambio futuro se aplique solo a una de las dos.
            val tile: @Composable LazyGridItemScope.(AlbumSummary) -> Unit = { album ->
                AlbumTileCard(
                    album = album,
                    // La rejilla se reordena al cambiar el orden o el filtro de origen: sin esto
                    // las celdas saltan de sitio en un frame.
                    modifier = Modifier.animateItem(),
                    onClick = { onAlbumClick(album.name, album.albumArtist) },
                    onPlayClick = { onPlayAlbum(album) },
                    onAddToQueue = { onAddAlbumToQueue(album) },
                    // Carátula = shared element hacia el header del detalle del álbum.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }

            if (groupByYear) {
                sections.forEach { (year, albumsOfYear) ->
                    item(
                        key = yearHeaderKey(year),
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = YEAR_HEADER_CONTENT_TYPE
                    ) {
                        // Sin padding lateral: dentro del grid ya lo pone su `contentPadding`.
                        YearHeaderText(label = headerLabels[yearHeaderKey(year)].orEmpty())
                    }
                    items(albumsOfYear, key = { it.identityKey }, contentType = { "album" }, itemContent = tile)
                }
            } else {
                items(albums, key = { it.identityKey }, contentType = { "album" }, itemContent = tile)
            }
        }

        if (groupByYear) {
            // Se lee el estado DENTRO de este composable (no en el cuerpo de la pantalla) para que
            // cambiar de sección recomponga solo la capa y no el grid entero.
            PinnedYearHeader(
                gridState = gridState,
                pinnedHeaderKey = { pinnedHeaderKey.value },
                headerLabels = headerLabels,
                contentOrigin = contentTop + GridTopGap,
                pinLinePx = pinLinePx,
                bandHeight = contentTop + GridTopGap - YearHeaderTopPadding,
                startPadding = sideStart,
                endPadding = sideEnd,
                // La capa fija es CHROME: las tarjetas pasan por debajo de ella, así que una carátula
                // medio tapada por el año despegaría ENTERA por encima al abrir el álbum y aterrizaría
                // sobre él al volver. Pero es chrome DE PÁGINA, un peldaño bajo el de la búsqueda:
                // con el mismo z, su banda tapaba la cabecera toda la transición (ver
                // [PAGE_CHROME_OVERLAY_Z]).
                overOverlay = overSharedElementsModifier(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    zIndexInOverlay = PAGE_CHROME_OVERLAY_Z
                )
            )
        }
    }
}

/** La key del item de cabecera de una sección. Una función porque la comparten el grid y el mapa. */
private fun yearHeaderKey(year: Int?): String = "album_year_${year ?: "unknown"}"

/** Lo comparten el item del grid y el derivado de la capa fija, que distingue cabeceras por él. */
private const val YEAR_HEADER_CONTENT_TYPE = "yearHeader"

/**
 * Hueco entre el `contentPadding.top` que llega y el primer item. Constante porque lo usan dos
 * sitios que tienen que coincidir: el padding del grid y la línea donde se fija el año.
 */
private val GridTopGap = 4.dp

/**
 * Aire sobre el año dentro de su cabecera. Constante porque la capa fija se coloca exactamente esto
 * por encima del reposo del contenido, para que su TEXTO caiga donde cae el de la cabecera real.
 */
private val YearHeaderTopPadding = 12.dp

/**
 * La cabecera de sección, con el MISMO aspecto dentro de la cuadrícula y en la capa fija: es la
 * pieza que una pasa por detrás de la otra, así que una diferencia de tipografía o de fondo se
 * vería como un salto justo al relevarse.
 */
@Composable
private fun YearHeaderText(
    label: String,
    modifier: Modifier = Modifier,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = AppColors.onSurfaceVariant,
        // **Fondo OPACO y del color del Scaffold** (`AppColors.surface`, ver `LibraryScreen`): la
        // capa fija tiene tarjetas pasando por debajo, y sin fondo el año se leería sobre las
        // carátulas. El `background` va ANTES del padding para que el color cubra también ese
        // respiro; al revés, las tarjetas asomarían por el hueco de arriba y abajo.
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surface)
            .padding(start = startPadding, end = endPadding, top = YearHeaderTopPadding, bottom = 6.dp)
    )
}

/**
 * La capa fija: la cabecera de la sección en curso, más una BANDA que tapa todo lo que queda por
 * encima de ella. Emite las dos piezas en el `Box` de quien la llama, y la banda va DESPUÉS para
 * quedar encima: es donde se esconde la cabecera cuando la empuja la siguiente, y lo que impide
 * que asomen tarjetas por el aire que hay entre el chrome y el año.
 *
 * [pinnedHeaderKey] es una lambda para que su lectura no recomponga a quien la coloca.
 *
 * @param contentOrigin dónde cae el 0 de coordenadas de contenido del grid (su padding superior).
 * @param pinLinePx la línea donde se fija la cabecera, en coordenadas de contenido.
 * @param bandHeight esa misma línea en coordenadas de la pantalla: todo lo de encima se tapa.
 * @param overOverlay lo que sube las DOS piezas por encima de los shared elements. Van las dos: la
 *   banda también tapa tarjetas, y dejarla fuera haría asomar la carátula por el aire sobre el año.
 */
@Composable
private fun PinnedYearHeader(
    gridState: LazyGridState,
    pinnedHeaderKey: () -> String?,
    headerLabels: Map<String, String>,
    contentOrigin: Dp,
    pinLinePx: Int,
    bandHeight: Dp,
    startPadding: Dp,
    endPadding: Dp,
    overOverlay: Modifier
) {
    val headerKey = pinnedHeaderKey() ?: return
    val label = headerLabels[headerKey] ?: return
    YearHeaderText(
        label = label,
        // La posición cambia en CADA frame mientras la empuja la siguiente cabecera, así que se
        // resuelve en la fase de LAYOUT: `layoutInfo` se lee dentro del lambda de `offset`, que
        // vuelve a colocar sin recomponer. Leerlo en composición sería recomponer por frame.
        modifier = Modifier.offset {
            IntOffset(
                0,
                contentOrigin.roundToPx() +
                    pinnedHeaderPosition(gridState.layoutInfo, headerKey, pinLinePx)
            )
        }.then(overOverlay),
        startPadding = startPadding,
        endPadding = endPadding
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bandHeight)
            .then(overOverlay)
            .background(AppColors.surface)
    )
}

/**
 * Dónde va la capa fija, en coordenadas de CONTENIDO. Es la misma regla que `StickToTopPlacement`,
 * que aquí no se puede usar (ver `AlbumsScreen`), con la línea de anclaje en [pinLinePx]:
 *
 * - En reposo, en la línea.
 * - Si la cabecera REAL de la sección fija está todavía por debajo de la línea (acaba de entrar),
 *   la capa baja con ella y la tapa exacta: sin esto habría un salto en el relevo.
 * - Si la cabecera de la sección SIGUIENTE se le acerca por debajo, la capa sube lo que haga falta
 *   para no pisarla. Sin este empuje la siguiente se metía por debajo de la capa y quedaba oculta
 *   hasta el cambio de golpe: dos años en el mismo sitio.
 *
 * Dos `firstOrNull` y no un `filter`: corre en cada pasada de layout durante el scroll, y un
 * `filter` crearía una lista por frame.
 */
private fun pinnedHeaderPosition(
    layoutInfo: LazyGridLayoutInfo,
    pinnedKey: String,
    pinLinePx: Int
): Int {
    val items = layoutInfo.visibleItemsInfo
    val current = items.firstOrNull { it.key == pinnedKey }
    val resting = if (current != null && current.offset.y > pinLinePx) current.offset.y else pinLinePx
    // Una cabecera por debajo de la línea es posterior a la fija: la de la sección fija, si se ve,
    // o está por encima de la línea o es `current`, que se excluye por key.
    val next = items.firstOrNull {
        it.contentType == YEAR_HEADER_CONTENT_TYPE && it.key != pinnedKey && it.offset.y > pinLinePx
    } ?: return resting
    return minOf(resting, next.offset.y - next.size.height)
}
