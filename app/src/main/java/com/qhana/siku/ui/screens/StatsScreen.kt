package com.qhana.siku.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ChoiceGroupWidth
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.WavyProgressRing
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.components.secondaryContentColor
import com.qhana.siku.ui.viewmodel.StatsUiState
import com.qhana.siku.ui.viewmodel.StatsListKind
import com.qhana.siku.ui.viewmodel.StatsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale

/**
 * Lado del distintivo de una tarjeta del resumen. Menor que los 48 de los distintivos que
 * identifican una FILA entera (Favoritos, la entrada a Estadísticas del inicio): aquí hay cuatro en
 * pantalla a la vez y el protagonista de la tarjeta es su cifra, no su icono.
 */
/**
 * Distintivo de una tarjeta del resumen: **píldora VERTICAL**, no un círculo.
 *
 * Es la forma de la referencia, y no es un capricho: más alta que ancha ocupa menos ancho —que es
 * lo escaso en una rejilla de dos columnas— dejando más sitio a la cifra, y de paso repite el idiom
 * que la app ya usa para "esto es una pieza de una familia" (el chevron de la entrada del inicio,
 * "añadir canciones" en la botonera de un detalle).
 *
 * La forma sale sola de `RoundedCornerShape(percent = 50)` al ser el contenedor más alto que ancho.
 */
private val SummaryBadgeWidth = 28.dp
private val SummaryBadgeHeight = 40.dp

/** Lado del distintivo CIRCULAR de la racha, que va dentro de su anillo. Ver [StreakRow]. */
private val StreakBadgeSize = 36.dp

/** Lado del anillo de la racha: el distintivo más el aire que necesitan trazo y cresta de la onda. */
private val StreakRingSize = 56.dp

/** Trazo del anillo de la racha. */
private val StreakRingStroke = 4.dp

/**
 * Los hitos de la racha, en orden.
 *
 * **Es una ESCALERA y no un tope único.** Estuvo un rato con un solo objetivo de 30 días, y la
 * pregunta que lo tumbó fue la obvia: ¿y a partir del día 31? El anillo se quedaba lleno para
 * siempre y la etiqueta repetía "¡Mes completo!" el día 31 y el 300. Un progreso sin nada que
 * perseguir deja de ser un progreso.
 *
 * Con la escalera, el anillo apunta SIEMPRE al siguiente hito y la etiqueta nombra el último
 * conseguido. Pasado el último, el anillo se queda lleno de verdad — a 365 días seguidos ya no hace
 * falta motivar a nadie.
 *
 * Los números son los que la gente se pone sola (una semana, un mes, cien días, un año), no una
 * progresión matemática: 7 · 30 · 100 · 365.
 */
private val StreakMilestones = listOf(
    7 to R.string.stats_streak_milestone_week,
    30 to R.string.stats_streak_milestone_month,
    100 to R.string.stats_streak_milestone_100,
    365 to R.string.stats_streak_milestone_year
)

/**
 * Meta del anillo: el último hito, un año.
 *
 * **Es FIJA y no sube con la escalera**, y eso es lo importante. Con la meta moviéndose al
 * siguiente hito, el anillo se llenaba el día 7 (`7/7`) y al día siguiente caía al 27 % (`8/30`):
 * técnicamente correcto —la meta había cambiado— pero se lee como que el progreso se RESETEÓ o
 * bajó, que es justo lo contrario de lo que una racha quiere transmitir (device, 28 ago 2026).
 *
 * Con una sola meta el arco solo crece en todo el año. Lo que marca los hitos deja de ser el
 * anillo y pasa a ser el TEXTO, que es donde no molesta: el arco cuenta la historia larga y el
 * mensaje celebra los tramos.
 *
 * Con la meta fija tampoco tiene sentido enseñar la fracción `x/y` que hubo un rato: `8/365` no
 * motiva a nadie, y el arco ya dice lo suyo sin números.
 */
private val StreakRingGoal: Int get() = StreakMilestones.last().first

/**
 * A partir de qué hito la tarjeta se pone VERDE.
 *
 * El mes, que era el tope cuando solo había uno. Se queda como umbral de "esto ya es una racha de
 * verdad" en vez de moverse con la escalera: si el verde subiera con cada hito, la celebración se
 * apagaría justo después de conseguirla.
 */
private const val StreakGreenFromDays = 30

/**
 * Aire entre la barra superior y el primer contenido.
 *
 * La lista arrancaba pegada a la barra (device, 28 ago 2026): el `contentPadding` solo reservaba
 * abajo, así que lo único que separaba la tarjeta de la racha del borde eran sus propios 8dp de
 * margen vertical, que son para el hueco ENTRE tarjetas, no para el remate de la pantalla.
 *
 * Mismo valor que `HeaderContentGap` en la biblioteca — es el mismo problema (dónde empieza el
 * contenido bajo el cromo) y no debería resolverse con dos números distintos.
 */
private val HeaderContentGap = 16.dp

/** Columnas de la rejilla del resumen. Dos: seis tarjetas en tres filas parejas. */
private const val SummaryColumns = 2

/**
 * Forma de TODAS las tarjetas del resumen.
 *
 * El radio es el mismo en las seis (y en la racha): con radios distintos la rejilla se leía como
 * seis piezas sueltas en vez de una familia. 28dp sobre un alto de ~68 deja los extremos casi
 * semicirculares, que es la proporción de la referencia sin llegar a la píldora.
 */
private val SummaryCardShape = RoundedCornerShape(28.dp)

/** Margen lateral y aire entre las tarjetas del resumen; nombrados porque el reparto por fila los lee. */
private val SummaryGridPadding = 16.dp
private val SummaryGridSpacing = 8.dp

/**
 * Estadísticas de escucha.
 *
 * **No usa el header inmersivo de los detalles**, y no es un descuido: allí los 380dp se los gana
 * una foto o un collage, y aquí no hay imagen que enseñar — se probó con un collage de las portadas
 * del top y el usuario lo descartó en device (26 ago 2026). Lleva la app bar PEQUEÑA, el contenido
 * empieza en el selector de periodo, y con ello desaparece toda la maquinaria del título viajero.
 * Tampoco es punta de ningún morph: se llega desde una tarjeta del inicio que no tiene imagen que
 * hacer volar.
 *
 * **Dos fuentes de datos según el periodo** — ver `StatsRepository`. Mes y año salen del log de
 * eventos (v28); "Siempre" sale de `songs.playCount`, lo único que lleva el pasado anterior a la
 * actualización. De ahí que ahí la RACHA no exista —no se puede fechar lo que no tiene fecha— y que
 * el tiempo escuchado sea una ESTIMACIÓN (reproducciones x duración), etiquetada como tal en su
 * tarjeta y explicada en la nota del pie. Lo que la pantalla no hace es rellenar con ceros.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBackClick: () -> Unit,
    /**
     * Abre el ranking completo de una categoría. La pantalla ya NO reproduce ni navega a detalles:
     * eso vive ahora en [StatsListScreen], al otro lado de cada tarjeta.
     *
     * Se le pasa el PERIODO (modo + ancla ISO) porque el destino lo necesita en su ruta y el
     * NavHost no lo conoce: vive en el ViewModel de ESTA pantalla, que está scopeado a su
     * `NavBackStackEntry`.
     */
    onOpenList: (kind: StatsListKind, mode: String, anchor: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // Al cambiar de periodo la lista vuelve ARRIBA. Sin esto, `LazyColumn` conserva el índice y el
    // desplazamiento del periodo anterior — que en el nuevo no significan nada—: viniendo de un mes
    // con rankings largos y scrolleado hacia abajo, al saltar a uno vacío la pantalla se quedaba en
    // una posición donde ya no hay nada y el estado vacío caía fuera de vista, como si no se
    // pintara. La clave es el PERIODO y no `Unit` (convención 18): es de lo que depende de verdad.
    // `scrollToItem` y no `animateScrollToItem`: el contenido ya no es el mismo, así que animar el
    // recorrido entre dos listas distintas no representa nada.
    LaunchedEffect(state.selection) { listState.scrollToItem(0) }

    Scaffold(
        modifier = modifier,
        // App bar PEQUEÑA, la de `DownloadManagerScreen`, y no la grande de los detalles ni la
        // `LargeFlexibleTopAppBar` de Ajustes: esta pantalla no tiene imagen de cabecera ni un
        // nombre de entidad que presentar — su contenido empieza en el selector de periodo. Con la
        // barra en el slot `topBar`, el contenido ya no pasa por debajo y desaparece toda la
        // maquinaria del título viajero (anclas, `topBarAlpha`, overlay), que existía para
        // acompañar a un header inmersivo que aquí ya no hay.
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                // La sobrecarga SIN `subtitle`, igual que el gestor de descargas. La variante
                // Expressive de la barra pequeña es precisamente la que añade subtítulo (no existe
                // una "SmallFlexibleTopAppBar"), y aquí el subtítulo natural sería el periodo — que
                // es justo lo que ya dice, y de forma interactiva, la fila de las flechas.
                // `surfaceContainerHigh` y no el `surface` por defecto del helper: esta pantalla
                // tiene el fondo en `surfaceContainer` (el nivel MEDIO), y la horquilla de color
                // dice que el contenido SUBE y las barras BAJAN. Con el default, la barra quedaba
                // POR ENCIMA del fondo y se leía como una banda clara pegada arriba. Es el mismo
                // valor que usa la topbar overlay de los cuatro detalles, que comparten fondo.
                // Ver `SettingsScaffold`: una app bar sin `colors` se los pide al tema BASE.
                colors = appTopAppBarColors(
                    containerColor = AppColors.surfaceContainerHigh,
                    scrolledContainerColor = AppColors.surfaceContainerHigh
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                // El default de la barra (`systemBarsForVisualComponents`) IGNORA la perforación,
                // que en horizontal cae en un costado: sin esto la flecha de volver queda debajo.
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Fondo en el nivel MEDIO de la escala: el contenido sube a `surface` y las barras bajan.
        // Mismo reparto que los detalles.
        containerColor = AppColors.surfaceContainer
    ) { paddingValues ->
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            ComponentConfig.FloatingBarListInset
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .padding(top = paddingValues.calculateTopPadding())
                // Girado, esta pantalla estiraba sus rankings a ~873dp de línea. El tope es el
                // mismo que el de las hojas ([ComponentConfig.ContentMaxWidth]) y en vertical no
                // toca nada; `safeSidePadding` repone la perforación, que el `contentWindowInsets`
                // a cero de arriba descarta a propósito para que el contenido pase bajo la barra.
                // `widthIn` ANTES de `fillMaxWidth`: el ancho es el mínimo entre el tope y lo que
                // haya. Al revés, el mínimo exacto que impone `fillMaxWidth` se pelearía con él.
                .safeSidePadding()
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = HeaderContentGap, bottom = listBottomInset)
        ) {
            // La RACHA va ARRIBA DEL TODO, por encima del propio selector de periodo. Es el único
            // dato de la pantalla que NO depende del periodo elegido —una racha es del presente, no
            // de agosto— así que ponerla debajo del selector la hacía parecer parte de lo que ese
            // control filtra. Encima, además, es lo primero que se ve al entrar.
            state.streakDays?.let { days ->
                item(key = "streak") { StreakRow(days) }
            }

            item(key = "period_selector") {
                PeriodSelector(
                    state = state,
                    onSelectMode = viewModel::selectMode,
                    onPrevious = viewModel::goToPrevious,
                    onNext = viewModel::goToNext
                )
            }

            if (state.isCompletelyEmpty) {
                item(key = "empty") { StatsEmptyState(state.selection.mode) }
            } else {
                item(key = "summary") {
                    SummaryGrid(state = state) { kind ->
                        onOpenList(
                            kind,
                            state.selection.mode.name,
                            state.selection.anchor.toString()
                        )
                    }
                }

                if (state.selection.mode == StatsPeriodMode.ALL) {
                    item(key = "all_time_note") { AllTimeNote(state.historyStartedAt) }
                }
            }
        }
        }
    }
}

// ==================== SELECTOR DE PERIODO ====================

@Composable
private fun PeriodSelector(
    state: StatsUiState,
    onSelectMode: (StatsPeriodMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // **Cinco opciones y etiquetas CORTAS**: con "Mes"/"Año"/"Siempre" cabían de sobra, pero al
        // entrar día y semana el reparto pasa a ~70dp por botón en 393dp y "Semana" ya no entra con
        // la escala tipográfica subida. De ahí las abreviaturas de una letra para las cuatro de
        // calendario (D · S · M · A), que es además lo que hacen las apps de salud con este mismo
        // control — el recorrido lo explica la fila de abajo, que dice el periodo completo.
        // "Siempre" conserva su palabra: no es una unidad de calendario y una "S" chocaría con la
        // de semana.
        //
        // **`ChoiceGroupWidth.Proportional` PRECISAMENTE por eso** (29 ago 2026): cuatro etiquetas
        // de UNA letra y una de siete es el juego más desigual de la app, y el reparto a partes
        // iguales le daba a "Siempre" los mismos ~70dp que a la "D" —de los que el padding deja ~46
        // de texto para una palabra que pide ~55—, así que salía como "Siem…". Es el caso exacto que
        // documenta el KDoc de [ConnectedChoiceGroup] y que ya había mordido con "Desactivado" en el
        // selector de ReplayGain.
        //
        // Se pasó primero por `Content` (cada botón ceñido a lo suyo, el grupo centrado): arreglaba
        // el recorte pero dejaba el control encogido en medio de una pantalla cuyas tarjetas sí
        // llegan a los bordes. `Proportional` da las dos cosas — llega a los bordes Y respeta lo que
        // cada etiqueta pide—: las cuatro letras salen iguales entre sí y estrechas, que es lo
        // correcto porque no tienen nada que enseñar, y el sobrante se estira sin recortar a nadie.
        ConnectedChoiceGroup(
            options = StatsPeriodMode.entries.toList(),
            selected = state.selection.mode,
            onSelect = onSelectMode,
            labelFor = { mode ->
                stringResource(
                    when (mode) {
                        StatsPeriodMode.DAY -> R.string.stats_period_day_short
                        StatsPeriodMode.WEEK -> R.string.stats_period_week_short
                        StatsPeriodMode.MONTH -> R.string.stats_period_month_short
                        StatsPeriodMode.YEAR -> R.string.stats_period_year_short
                        StatsPeriodMode.ALL -> R.string.stats_period_all
                    }
                )
            },
            width = ChoiceGroupWidth.Proportional,
            modifier = Modifier.fillMaxWidth()
        )

        // "Siempre" no se navega: no existe un "siempre anterior". La fila desaparece entera en vez
        // de quedarse con las dos flechas apagadas, que prometerían un recorrido inexistente.
        if (state.selection.mode != StatsPeriodMode.ALL) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PeriodArrow(
                    icon = "arrow_back",
                    description = stringResource(R.string.stats_period_previous),
                    enabled = state.canGoPrevious,
                    onClick = onPrevious
                )
                Text(
                    text = periodLabel(state.selection),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = AppColors.onSurface,
                    // Centrado entre las dos flechas: es la etiqueta de un control de navegación,
                    // así que su sitio es el medio del recorrido y no el borde de una de ellas.
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                PeriodArrow(
                    icon = "arrow_forward",
                    description = stringResource(R.string.stats_period_next),
                    enabled = state.canGoNext,
                    onClick = onNext
                )
            }
        }
    }
}

@Composable
private fun PeriodArrow(
    icon: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        // `shapes` = el morph de M3 Expressive al pulsar (círculo → squircle), el mismo que llevan
        // los botones de volver de los detalles. Sin él resuelve al overload estable y se queda
        // quieto bajo el dedo.
        shapes = IconButtonDefaults.shapes(),
        colors = appFilledTonalIconButtonColors(),
        // El glifo es una flecha desnuda: sin esto, un lector de pantalla solo diría "botón".
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        MaterialSymbol(icon)
    }
}

// ==================== RESUMEN ====================

/**
 * Rejilla del resumen: **dos columnas iguales**.
 *
 * Estuvo unas horas en BENTO —anchos distintos por fila, 2f/1.4f arriba, 1f/1.6f en medio— y se
 * descartó en device (28 ago 2026). El problema de una rejilla irregular con datos HOMOGÉNEOS es
 * que el ancho promete una jerarquía que no existe: las seis métricas valen lo mismo, así que
 * darle a "reproducciones" el doble de caja sugiere que es más importante que "artistas" sin que
 * lo sea. Un bento funciona cuando las piezas son de distinta naturaleza (un gráfico, un número,
 * una foto); aquí son seis veces el mismo par etiqueta/cifra.
 *
 * Lo que las distingue sigue siendo el COLOR, que es la variable que no miente sobre importancia.
 *
 * **Cuatro de las seis NAVEGAN** a su ranking completo ([StatsListScreen]) y dos no: reproducciones
 * y tiempo son magnitudes, no colecciones — no hay "lista de las 148 reproducciones" que abrir. Las
 * que navegan llevan chevron; las que no, nada. Una tarjeta que parece pulsable y no lo es es peor
 * que una que no lo parece.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryGrid(state: StatsUiState, onOpenList: (StatsListKind) -> Unit) {
    val summary = state.summary
    val styles = summaryCardStyles()
    // `FlowRow` y no tres `Row` a mano: con la tarjeta de tiempo ausente (nunca lo está hoy, pero
    // `listenedMs` es nullable) las demás se recolocan solas en vez de dejar un hueco.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SummaryGridPadding),
        horizontalArrangement = Arrangement.spacedBy(SummaryGridSpacing),
        verticalArrangement = Arrangement.spacedBy(SummaryGridSpacing),
        maxItemsInEachRow = SummaryColumns
    ) {
        // `weight(1f)` en TODAS: dos columnas exactas. El `FlowRow` reparte el ancho sobrante entre
        // las de cada fila, así que una fila impar (la última, si alguna tarjeta falta) deja su
        // única tarjeta a todo el ancho — que es lo correcto: media fila vacía se ve como un error.
        SummaryCard(
            icon = "music_cast",
            value = summary.plays.toString(),
            label = stringResource(R.string.stats_summary_plays),
            style = styles[0],
            modifier = Modifier.weight(1f)
        )
        // En "Siempre" el tiempo es una ESTIMACIÓN (reproducciones x duración): el medido solo
        // existe donde hay log de eventos. Se pinta igual, pero la ETIQUETA lo dice — dar un
        // estimado con el mismo rótulo que un dato exacto sería peor que no darlo.
        summary.listenedMs?.let { millis ->
            SummaryCard(
                icon = "timer_play",
                value = listeningTimeText(millis),
                label = stringResource(
                    if (summary.listenedIsEstimate) R.string.stats_summary_time_estimate
                    else R.string.stats_summary_time
                ),
                style = styles[1],
                modifier = Modifier.weight(1f)
            )
        }
        SummaryCard(
            icon = "music_note_2",
            value = summary.distinctSongs.toString(),
            label = stringResource(R.string.stats_summary_songs),
            style = styles[2],
            onClick = { onOpenList(StatsListKind.SONGS) },
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            icon = "artist",
            value = summary.distinctArtists.toString(),
            label = stringResource(R.string.stats_summary_artists),
            style = styles[3],
            onClick = { onOpenList(StatsListKind.ARTISTS) },
            modifier = Modifier.weight(1f)
        )
        // Los conteos de álbumes y géneros salen de `counts` y no del resumen porque llevan JOIN
        // con `songs` — ver `StatsCounts`.
        SummaryCard(
            icon = "album",
            value = state.counts.albums.toString(),
            label = stringResource(R.string.stats_summary_albums),
            style = styles[4],
            onClick = { onOpenList(StatsListKind.ALBUMS) },
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            icon = "genres",
            value = state.counts.genres.toString(),
            label = stringResource(R.string.stats_summary_genres),
            style = styles[5],
            onClick = { onOpenList(StatsListKind.GENRES) },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Paleta de una tarjeta del resumen: su contenedor, el color de su texto y el par del distintivo.
 *
 * Se pasa entero en vez de un enum con un `when` dentro del componente porque el reparto es una
 * decisión de la REJILLA —qué métrica se lleva cada acento— y no del componente, que solo pinta lo
 * que le den.
 */
private data class SummaryCardStyle(
    val container: Color,
    val onContainer: Color,
    val badge: Color,
    val onBadge: Color
)

/**
 * Los seis acentos del resumen, en el orden en que se pintan.
 *
 * **Uno por métrica**: las tarjetas eran rectángulos `surface` con un glifo gris de 20sp, o sea
 * indistinguibles entre sí salvo por su texto. Con contenedor propio, cada dato se reconoce antes
 * de leerlo. La silueta del distintivo NO participa: es un círculo en las seis (ver [SummaryCard]),
 * y lo que las separa es el color y el ancho de su caja.
 *
 * M3 solo tabula TRES familias de acento, así que las tres últimas no estrenan color: repiten
 * familia en su variante tenue (el par `container` sobre el techo de la escala neutra). Inventar un
 * cuarto acento fuera de la paleta rompería el tema dinámico, que es de donde sale todo esto. Lo
 * que las sigue separando es el color de su distintivo y el ancho de su caja.
 */
@Composable
private fun summaryCardStyles(): List<SummaryCardStyle> = listOf(
    SummaryCardStyle(
        container = AppColors.primaryContainer,
        onContainer = AppColors.onPrimaryContainer,
        badge = AppColors.primary,
        onBadge = AppColors.onPrimary
    ),
    SummaryCardStyle(
        container = AppColors.secondaryContainer,
        onContainer = AppColors.onSecondaryContainer,
        badge = AppColors.secondary,
        onBadge = AppColors.onSecondary
    ),
    SummaryCardStyle(
        container = AppColors.tertiaryContainer,
        onContainer = AppColors.onTertiaryContainer,
        badge = AppColors.tertiary,
        onBadge = AppColors.onTertiary
    ),
    SummaryCardStyle(
        container = AppColors.surfaceContainerHighest,
        onContainer = AppColors.onSurface,
        badge = AppColors.primaryContainer,
        onBadge = AppColors.onPrimaryContainer
    ),
    SummaryCardStyle(
        container = AppColors.surfaceContainerHighest,
        onContainer = AppColors.onSurface,
        badge = AppColors.secondaryContainer,
        onBadge = AppColors.onSecondaryContainer
    ),
    SummaryCardStyle(
        container = AppColors.surfaceContainerHighest,
        onContainer = AppColors.onSurface,
        badge = AppColors.tertiaryContainer,
        onBadge = AppColors.onTertiaryContainer
    )
)

/**
 * Tarjeta de una métrica del resumen.
 *
 * **Anatomía en FILA y no apilada** (28 ago 2026, con la referencia de Fitbit en la mano): un
 * distintivo circular a la izquierda y, a su derecha, la etiqueta arriba y la cifra debajo. La
 * versión anterior apilaba distintivo, cifra y etiqueta en tres pisos, y eso es lo que hacía la
 * tarjeta alta — con seis en pantalla, la rejilla ocupaba media pantalla de más. Así el alto lo
 * marca el distintivo (40 + 2×16 = 72dp) y la rejilla entera cabe de una vez.
 *
 * **La etiqueta va ARRIBA de la cifra**, al revés que antes: la palabra dice de qué se está
 * hablando y el número es la respuesta, así que en ese orden la tarjeta se lee como una frase. Es
 * además lo que hace la referencia.
 *
 * **El distintivo es una PÍLDORA VERTICAL** y no una `MaterialShape`. Las cookies se quedan donde
 * identifican una entidad y hay una sola en pantalla (Favoritos, la entrada del inicio); aquí hay
 * seis a la vez, y seis siluetas distintas convertían la rejilla en un muestrario. Lo que distingue
 * una tarjeta de otra es su COLOR. Ver [SummaryBadgeWidth].
 */
@Composable
private fun SummaryCard(
    icon: String,
    value: String,
    label: String,
    style: SummaryCardStyle,
    modifier: Modifier = Modifier,
    /** `null` = la tarjeta es solo un dato. Con callback aparece el chevron y se vuelve pulsable. */
    onClick: (() -> Unit)? = null
) {
    val content = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            AppSurface(
                shape = RoundedCornerShape(percent = 50),
                color = style.badge,
                modifier = Modifier.size(width = SummaryBadgeWidth, height = SummaryBadgeHeight)
            ) {
                // fillMaxSize: sin él el Box se ciñe al glifo y `Center` no centra nada dentro del
                // distintivo — el mismo detalle que ya mordió en la píldora de Favoritos.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol(icon, fill = true, size = 18.sp, color = style.onBadge)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    // El secundario de la familia del contenedor, no `onSurfaceVariant`: sobre
                    // color, un neutro no pertenece a la familia y se lee como suciedad.
                    color = secondaryContentColor(style.onContainer, style.container),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = style.onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClick != null) {
                MaterialSymbol(
                    "chevron_right",
                    size = 20.sp,
                    color = secondaryContentColor(style.onContainer, style.container)
                )
            }
        }
    }
    // Dos sobrecargas distintas de `AppSurface` y no un `clickable` condicional: la de `onClick`
    // trae el state layer y el morph al pulsar, y ponerlo a mano sobre la otra los perdería.
    if (onClick != null) {
        AppSurface(
            onClick = onClick,
            color = style.container,
            shape = SummaryCardShape,
            modifier = modifier,
            content = content
        )
    } else {
        AppSurface(
            color = style.container,
            shape = SummaryCardShape,
            modifier = modifier,
            content = content
        )
    }
}

/**
 * Verde de META CUMPLIDA, el par contenedor/contenido.
 *
 * **Es un color fijo y no sale de la paleta**, que es justo lo contrario de lo que hace el resto de
 * la app. El motivo: aquí el verde no decora, SIGNIFICA "lo conseguiste" — es semántico, como el
 * rojo de `error`, que M3 también saca del tema dinámico por la misma razón. Con un rol derivado de
 * la carátula, la celebración saldría azul o morada según el disco que estuviera sonando, y
 * entonces no diría nada.
 *
 * Dos parejas porque el tono útil cambia con el tema, y la rama se decide por la LUMINANCIA de la
 * superficie real y no por `isSystemInDarkTheme()`, que miente con el tema forzado desde Ajustes
 * (mismo criterio que el velo del fondo desenfocado del reproductor).
 */
private val StreakGoalGreenDark = Color(0xFF1E4620) to Color(0xFFB6F2B8)
private val StreakGoalGreenLight = Color(0xFFC7F2C8) to Color(0xFF12331A)

/**
 * La racha, arriba del todo.
 *
 * **Comparte el lenguaje de las tarjetas del resumen** —misma forma, etiqueta arriba y cifra
 * debajo— con dos diferencias deliberadas: va a todo el ancho (no es una métrica del periodo: es el
 * único dato de la pantalla que no depende del selector, ni siquiera en "Siempre") y su distintivo
 * es CIRCULAR, porque lleva un anillo alrededor y un anillo alrededor de una píldora no existe.
 *
 * **El anillo NO se anima.** Tiene relieve de onda —es lo que lo distingue de un aro cualquiera—
 * pero su onda no viaja: es un dato quieto, y un reloj por frame en una pantalla que no lo necesita
 * es exactamente lo que prohíbe la regla 1 de Motion. De ahí el parámetro `animated` de
 * [WavyProgressRing], que se separó de `playing` para este caso.
 */
@Composable
private fun StreakRow(days: Int) {
    val active = days > 0
    // El hito ya conseguido, que es lo ÚNICO que la escalera decide: el texto. La meta del anillo
    // no se mueve con ella (ver [StreakRingGoal]).
    val achieved = StreakMilestones.lastOrNull { days >= it.first }
    val reached = days >= StreakGreenFromDays
    val dark = AppColors.surface.luminance() < LUMINANCE_MIDPOINT
    val goalGreen = if (dark) StreakGoalGreenDark else StreakGoalGreenLight

    // Tres estados, no dos: sin racha (neutro), con racha (acento) y meta cumplida (verde). El
    // tercero es el único que estrena color fuera de la paleta, y por eso se gana el sitio.
    val container = when {
        reached -> goalGreen.first
        active -> AppColors.primaryContainer
        else -> AppColors.surfaceContainerHighest
    }
    val onContainer = when {
        reached -> goalGreen.second
        active -> AppColors.onPrimaryContainer
        else -> AppColors.onSurface
    }
    // **Todo lo que va ENCIMA de la tarjeta sale de su propio par**, y esto es un arreglo de
    // contraste (device, 28 ago 2026: "icono sin contraste"). Estaba con `primary` de relleno y
    // `primaryContainer` de glifo, o sea dos roles VECINOS: con una paleta donde el contenedor sale
    // claro, el disco y su llama quedaban del mismo azul y el anillo se perdía sobre la tarjeta.
    //
    // `onContainer` sobre `container` es el ÚNICO par con contraste garantizado por definición —
    // M3 lo tabula justo para esto—, así que el distintivo va INVERTIDO: disco relleno del color de
    // contenido y glifo del color de la tarjeta. Es además lo que hace la referencia, donde el
    // badge es un círculo claro sobre una tarjeta de color, no un acento sobre otro acento.
    val ringColor = onContainer
    AppSurface(
        color = container,
        shape = SummaryCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SummaryGridPadding, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier.size(StreakRingSize),
                contentAlignment = Alignment.Center
            ) {
                WavyProgressRing(
                    // Contra una meta FIJA, así que el anillo solo crece.
                    progress = { (days.toFloat() / StreakRingGoal).coerceIn(0f, 1f) },
                    // Con relieve pero SIN viajar: ver el kdoc de arriba.
                    playing = true,
                    animated = false,
                    color = ringColor,
                    // La pista tiene que VERSE: con ella invisible no hay contra qué medir el arco
                    // y un 14 % se lee como "bastante". Es el mismo color del progreso, atenuado.
                    trackColor = onContainer.copy(alpha = STREAK_TRACK_ALPHA),
                    strokeWidth = StreakRingStroke,
                    modifier = Modifier.fillMaxSize()
                )
                AppSurface(
                    shape = CircleShape,
                    color = ringColor,
                    modifier = Modifier.size(StreakBadgeSize)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // El color de la TARJETA sobre el disco de contenido: la inversión del
                        // par, o sea contraste garantizado en cualquier paleta y en los dos temas.
                        MaterialSymbol(
                            "local_fire_department",
                            fill = true,
                            size = 20.sp,
                            color = container
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // La etiqueta nombra el último hito conseguido; sin ninguno, dice qué es
                    // esta tarjeta. Así el día 31 no repite lo del día 30.
                    text = stringResource(achieved?.second ?: R.string.stats_streak_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryContentColor(onContainer, container),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (active) {
                        pluralStringResource(R.plurals.stats_streak_days, days, days)
                    } else {
                        stringResource(R.string.stats_streak_none)
                    },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Alfa de la pista del anillo de la racha. Sale del color de CONTENIDO de la tarjeta y no de un rol
 * neutro: sobre un contenedor de acento (o sobre el verde) un gris no pertenece a la familia.
 */
private const val STREAK_TRACK_ALPHA = 0.28f

/** Punto medio de la escala de luminancia: por encima, la superficie es clara. */
private const val LUMINANCE_MIDPOINT = 0.5f

@Composable
private fun AllTimeNote(historyStartedAt: Long?) {
    val text = if (historyStartedAt != null) {
        stringResource(R.string.stats_all_time_note, formatDate(historyStartedAt))
    } else {
        stringResource(R.string.stats_all_time_note_pending)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun StatsEmptyState(mode: StatsPeriodMode) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MaterialSymbol("bar_chart", size = 64.sp, color = AppColors.outline)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.stats_empty_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = AppColors.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            // Un periodo vacío no es lo mismo que no haber escuchado nunca: en el primero hay otro
            // mes al que ir, y decirle "escucha algo" a quien lleva meses usando la app sobra.
            text = stringResource(
                if (mode == StatsPeriodMode.ALL) R.string.stats_empty_subtitle
                else R.string.stats_empty_period
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.onSurfaceVariant
        )
    }
}

// ==================== FORMATO ====================

/**
 * Etiqueta del periodo: "agosto de 2026", "2026" o "Siempre". Va por `DateTimeFormatter` localizado
 * y no por un patrón a mano: el orden y la preposición cambian con el idioma, y la app se traduce.
 *
 * `internal` porque [StatsListScreen] la reusa para su subtítulo: la etiqueta del periodo tiene que
 * decir lo mismo a los dos lados del tap, y dos copias se separarían en cuanto una cambiara.
 */
@Composable
internal fun periodLabel(selection: StatsSelection): String {
    val locale = LocalLocale.current.platformLocale
    return when (selection.mode) {
        StatsPeriodMode.ALL -> stringResource(R.string.stats_period_all)
        StatsPeriodMode.YEAR -> selection.startDate().year.toString()
        StatsPeriodMode.MONTH ->
            selection.startDate().format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).capitalized(locale)
        // "25 – 31 de agosto": el rango completo, con el fin INCLUSIVO (el modelo lo guarda
        // exclusivo). Sin él, una semana se leería como un día suelto.
        StatsPeriodMode.WEEK -> {
            val start = selection.startDate()
            val end = selection.endDateExclusive().minusDays(1)
            val dayOnly = DateTimeFormatter.ofPattern("d", locale)
            val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
            // Dentro del mismo mes no se repite el nombre del mes; a caballo entre dos, sí.
            if (start.month == end.month) {
                "${start.format(dayOnly)} – ${end.format(dayMonth)}"
            } else {
                "${start.format(dayMonth)} – ${end.format(dayMonth)}"
            }.capitalized(locale)
        }
        StatsPeriodMode.DAY ->
            selection.anchor.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).capitalized(locale)
    }
}

/**
 * Primera letra en mayúscula. Hace falta porque `DateTimeFormatter` da los nombres de mes y día en
 * minúscula en español (y en otros idiomas), y aquí encabezan una etiqueta.
 */
private fun String.capitalized(locale: Locale): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

@Composable
private fun listeningTimeText(millis: Long): String {
    val hoursAndMinutes = stringResource(R.string.stats_time_hours_minutes)
    val minutesOnly = stringResource(R.string.stats_time_minutes)
    return formatListeningTime(
        millis,
        hoursAndMinutes = { hours, minutes -> hoursAndMinutes.format(hours, minutes) },
        minutesOnly = { minutes -> minutesOnly.format(minutes) }
    )
}

private fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate())
