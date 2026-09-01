package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsSelection
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ChoiceGroupWidth
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.CountChip
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.WavyProgressRing
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.components.rememberSecondaryContentColor
import com.qhana.siku.ui.viewmodel.ActivityChart
import com.qhana.siku.ui.viewmodel.ActivityColumn
import com.qhana.siku.ui.viewmodel.StatsUiState
import com.qhana.siku.ui.viewmodel.buildActivityChart
import com.qhana.siku.ui.viewmodel.StatsViewModel
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale

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

/**
 * Forma de las dos tarjetas de la pantalla: la racha y la del periodo.
 *
 * El radio es el MISMO en las dos. Lo fue ya cuando eran seis y una rejilla: con radios distintos se
 * leían como piezas sueltas en vez de una familia, y eso no cambia porque queden dos.
 */
private val StatsCardShape = RoundedCornerShape(28.dp)

/** Margen lateral de las dos tarjetas. */
private val CardSidePadding = 16.dp

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
 * **Cuatro bloques, y cada uno con la forma que pide su dato** (30 ago 2026): la racha, la tarjeta
 * del periodo —titular, comparación con el anterior e histograma de actividad—, las diez canciones
 * numeradas, y artistas y álbumes en carrusel más los géneros en reparto. Antes eran ocho bloques y
 * cuatro de ellos la misma sección repetida; el recorrido está en [PeriodCard] y en
 * `StatsRankingRows`.
 *
 * **Dos fuentes de datos según el periodo** — ver `StatsRepository`. Los periodos de calendario
 * salen del log de eventos (v28); "Siempre" sale de `songs.playCount`, lo único que lleva el pasado
 * anterior a la actualización. De ahí que ahí el tiempo escuchado sea una ESTIMACIÓN
 * (reproducciones x duración), etiquetada como tal, y que no haya ni histograma ni comparación: no
 * se puede fechar lo que no tiene fecha, ni existe un "siempre anterior". **Lo que la pantalla no
 * hace es rellenar con ceros ni inventar una métrica para tapar el hueco.**
 *
 * La RACHA sí se enseña en todos los periodos, "Siempre" incluido: nunca salió de los agregados,
 * sale de `play_events`, así que no depende del selector ni un poco.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBackClick: () -> Unit,
    /** La que suena, para resaltar su fila en el ranking de canciones. */
    currentSong: Song?,
    /**
     * Reproduce el ranking de canciones desde la fila tocada. La cola es el ranking ENTERO: quien
     * toca la tercera espera que sigan las otras siete, no que la música pare.
     *
     * La pantalla volvió a reproducir y a navegar a detalles el 30 ago 2026, cuando las cuatro
     * categorías se trajeron aquí: antes eso vivía al otro lado de una tarjeta, en una pantalla
     * propia que dejó de hacer falta.
     */
    onPlaySongs: (List<Song>, Int) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Por CAMPO y no dentro de `uiState` (convención 9b): los rankings se re-emiten muestreados
    // durante un sync y el histograma cambia con el periodo, mientras que la racha no se entera de
    // ninguna de las dos cosas.
    val rankings by viewModel.rankings.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()

    // El eje del histograma se arma AQUÍ y no en el ViewModel: las etiquetas de un año son nombres
    // de mes, o sea que dependen del idioma, y el idioma vive en la composición mientras que un
    // ViewModel sobrevive al cambio de configuración. Ver [StatsActivity].
    val locale = LocalLocale.current.platformLocale
    val chart = remember(activity, locale) {
        activity?.let { buildActivityChart(it.selection, it.bucketing, it.plays, locale) }
    }

    // Los cuatro acentos de las secciones, en el orden en que se pintan. Ya NO hay tabla de
    // secciones: las cuatro dejaron de ser el mismo bloque con otro texto —una lista numerada, dos
    // carruseles y un reparto— así que abstraerlas escondería justo lo que las distingue.
    val styles = summaryCardStyles()

    val listState = rememberLazyListState()
    // Al CAMBIAR de periodo la lista vuelve arriba: `LazyColumn` conservaría el índice y el
    // desplazamiento del periodo anterior, que en el nuevo no significan nada — viniendo de un mes
    // con rankings largos y scrolleado hacia abajo, al saltar a uno vacío la pantalla se quedaba en
    // una posición donde ya no hay nada y el estado vacío caía fuera de vista, como si no se
    // pintara. `scrollToItem` y no `animateScrollToItem`: el contenido ya no es el mismo, así que
    // animar el recorrido entre dos listas distintas no representa nada.
    //
    // **Pero solo cuando cambia DE VERDAD, no al montarse** (device, 30 ago 2026: *"al volver de un
    // álbum debería regresar a la misma posición"*). Con `LaunchedEffect(state.selection)` a secas
    // el efecto también corría al recomponerse la pantalla, y volver de un detalle es exactamente
    // eso: `StatsScreen` es una ruta del NavHost, así que sale de la composición al navegar y
    // vuelve a entrar al regresar. `rememberLazyListState` ya restauraba la posición él solo —era
    // este efecto el que la tiraba justo después—.
    //
    // La guarda va en un `rememberSaveable` y no en un `remember`: tiene que sobrevivir al mismo
    // viaje que el estado de la lista, o al volver valdría su valor inicial y el efecto dispararía
    // igual. Y es un `String` porque `StatsSelection` no es `Parcelable`; la clave lleva el modo Y
    // el ancla, que son las dos cosas que mueven el periodo.
    val periodKey = "${state.selection.mode}:${state.selection.anchor}"
    var lastPeriodKey by rememberSaveable { mutableStateOf(periodKey) }
    LaunchedEffect(periodKey) {
        if (periodKey != lastPeriodKey) {
            lastPeriodKey = periodKey
            listState.scrollToItem(0)
        }
    }

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
                item(key = "period_card") { PeriodCard(state = state, chart = chart) }

                // **Las cuatro categorías, cada una con la forma que pide su dato.** No es la misma
                // sección repetida cuatro veces, que es lo que había: canciones es un ranking que se
                // lee en orden, artistas y álbumes se navegan MIRANDO (tienen imagen) y el género no
                // tiene imagen ninguna — su dato interesante no es el orden sino el REPARTO.
                //
                // Ninguna se despliega. Con el tope del ranking en diez caben enteras, así que sobra
                // todo el mecanismo de abrir y cerrar: no hay estado, ni chevron, ni un control que
                // prometa enseñar más de lo que hay.
                if (rankings.songs.isNotEmpty()) {
                    item(key = "head_songs") {
                        RankingSectionHeader(
                            icon = "music_note_2",
                            label = stringResource(R.string.stats_section_top_songs),
                            count = state.summary.distinctSongs,
                            style = styles[2]
                        )
                    }
                    // Filas como items HERMANOS de su cabecera y no dentro de un composable único:
                    // así la `LazyColumn` solo compone lo que se ve.
                    songRankingRows(
                        songs = rankings.songs,
                        currentSong = currentSong,
                        onPlay = { songs, index -> onPlaySongs(songs, index) }
                    )
                }

                if (rankings.artists.isNotEmpty()) {
                    item(key = "head_artists") {
                        RankingSectionHeader(
                            icon = "artist",
                            label = stringResource(R.string.stats_section_top_artists),
                            count = state.summary.distinctArtists,
                            style = styles[3]
                        )
                    }
                    item(key = "carousel_artists") {
                        RankCarousel(
                            entries = rankings.artists,
                            fallbackIcon = "artist",
                            onClick = onArtistClick
                        )
                    }
                }

                // Los conteos de álbumes y géneros salen de `counts` y no del resumen porque
                // llevan JOIN con `songs` — ver `StatsCounts`.
                if (rankings.albums.isNotEmpty()) {
                    item(key = "head_albums") {
                        RankingSectionHeader(
                            icon = "album",
                            label = stringResource(R.string.stats_section_top_albums),
                            count = state.counts.albums,
                            style = styles[4]
                        )
                    }
                    item(key = "carousel_albums") {
                        RankCarousel(
                            entries = rankings.albums,
                            fallbackIcon = "album",
                            onClick = onAlbumClick
                        )
                    }
                }

                if (rankings.genres.isNotEmpty()) {
                    item(key = "head_genres") {
                        RankingSectionHeader(
                            icon = "genres",
                            label = stringResource(R.string.stats_section_top_genres),
                            count = state.counts.genres,
                            style = styles[5]
                        )
                    }
                    genreShareRows(
                        entries = rankings.genres,
                        shareOf = rankings::shareOf,
                        onClick = onGenreClick
                    )
                    // El denominador NO es el total del periodo, y eso hay que decirlo: sin la nota,
                    // una biblioteca a medio etiquetar enseña porcentajes que no suman 100 y nada
                    // explica por qué. Se probó y se descartó una fila "Sin género" en su lugar:
                    // invita a tocarla y no lleva a ningún sitio.
                    item(key = "genre_note") {
                        SectionFootnote(stringResource(R.string.stats_genre_share_note))
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

// ==================== TARJETA DEL PERIODO ====================

/**
 * La tarjeta del periodo: **una sola superficie con el titular y la forma**.
 *
 * Sustituye a la rejilla del resumen, que llegó a tener seis tarjetas y acabó en dos. El recorrido lo
 * explica: las cuatro de categoría (canciones, artistas, álbumes, géneros) eran métrica Y puerta a su
 * ranking, y al traerse los rankings a esta misma pantalla el segundo trabajo desapareció — su número
 * vive ahora en la cabecera de su sección. Con dos tarjetas quedaba una fila ancha para dos cifras,
 * o sea una rejilla que ya no reparte nada.
 *
 * **Y sobre todo: la pantalla no decía la FORMA del periodo.** Hay cinco periodos y dos flechas para
 * recorrerlos, pero lo único que cambiaba al pulsarlas eran dos números — julio y agosto se leían
 * distintos y no se VEÍAN distintos, así que las flechas no compraban nada. El histograma y la
 * comparación con el periodo anterior son eso: lo que hace que navegar signifique algo.
 *
 * **En "Siempre" no hay ni una cosa ni la otra, y el hueco no se rellena.** Ese periodo sale de
 * `songs.playCount`, que no sabe cuándo sonó nada: dibujar ahí el histograma del log sería poner una
 * forma sacada de `play_events` debajo de un total sacado de `songs`, y las barras no sumarían el
 * número de encima. Tampoco existe un "siempre anterior" con el que comparar. Se probó y se descartó
 * (30 ago 2026, revisando el mockup) poner en su lugar una barra de *cobertura de biblioteca* —"612
 * de 777 canciones escuchadas"—: quien escucha en aleatorio sobre toda su biblioteca la ve tender al
 * 100 %, o sea decir siempre lo mismo, y quien no, lee un reproche en vez de un dato. Lo que hace
 * completa a esa pantalla son sus cuatro rankings, no una métrica que ocupe el sitio.
 */
@Composable
private fun PeriodCard(state: StatsUiState, chart: ActivityChart?) {
    val summary = state.summary
    val style = summaryCardStyles()[0]
    val secondary = rememberSecondaryContentColor(style.onContainer, style.container)
    AppSurface(
        color = style.container,
        shape = StatsCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardSidePadding)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            // **El TIEMPO es el titular y las reproducciones la línea de apoyo**, al revés que en la
            // rejilla, donde las dos tarjetas pesaban igual. Un rato de escucha es lo que la gente
            // reconoce como "cuánto"; el número de reproducciones depende de lo largas que sean las
            // canciones, así que no se compara ni consigo mismo entre dos meses distintos.
            //
            // Con `listenedMs` nulo el titular pasa a ser el número de reproducciones. Hoy no ocurre
            // —"Siempre" trae una estimación— pero el campo es nullable a propósito (ver
            // [com.qhana.siku.data.repository.StatsSummary]) y una tarjeta sin titular no existe.
            val millis = summary.listenedMs
            Text(
                text = stringResource(
                    when {
                        millis == null -> R.string.stats_summary_plays
                        summary.listenedIsEstimate -> R.string.stats_card_listened_estimate
                        else -> R.string.stats_card_listened
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = millis?.let { listeningTimeText(it) } ?: summary.plays.toString(),
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = style.onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PeriodCardSubtitle(state = state, showPlays = millis != null, color = secondary)
            chart?.let {
                ActivityHistogram(chart = it, color = style.onContainer, axisColor = secondary)
            }
        }
    }
}

/**
 * Bajo el titular: las reproducciones y, si hay con qué compararlas, cuánto cambiaron.
 *
 * **Van en DOS líneas, no en una** (30 ago 2026, pedido en device). Son dos cosas distintas —una
 * cuenta el periodo y la otra lo compara con otro— y en una sola línea el separador las cosía a la
 * fuerza: el resultado era una frase larga que además se quedaba sin ancho en cuanto la comparación
 * traía tres cifras, con el `maxLines = 1` recortando justo la mitad que explica el porcentaje.
 * Partidas, cada una se lee de un vistazo y la de abajo puede crecer sin empujar a la de arriba.
 * Con eso sobra el separador, que solo existía para juntarlas.
 *
 * La comparación **no lleva color semántico**. Un +23 % no es una buena noticia ni un −12 % una mala
 * —esta app no tiene una meta de escucha— así que pintarlo de verde o de rojo le pondría un juicio a
 * un dato que no lo tiene, y de paso estrenaría dos colores fuera de la paleta para decir algo que el
 * glifo ya dice. Lo que cambia es la FLECHA, que señala la dirección sin opinar.
 */
@Composable
private fun PeriodCardSubtitle(state: StatsUiState, showPlays: Boolean, color: Color) {
    val delta = state.listenedDeltaPercent
    if (!showPlays && delta == null) return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (showPlays) {
            Text(
                text = pluralStringResource(
                    R.plurals.stats_plays,
                    state.summary.plays,
                    state.summary.plays
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (delta != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                MaterialSymbol(
                    if (delta >= 0) "trending_up" else "trending_down",
                    size = 16.sp,
                    color = color
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    // El signo lo lleva la flecha, así que el número va en valor absoluto: "−12 %
                    // menos" diría la dirección dos veces, y en una de ellas al revés.
                    //
                    // **Y se NOMBRA contra qué se compara** (device, 30 ago 2026: *"ese % que el
                    // periodo anterior qué significa"*). "El periodo anterior" es el nombre del
                    // MECANISMO, no del dato: obligaba a mirar el selector, deducir en qué unidad
                    // se está y recomponer la frase. Con la unidad puesta —"el día anterior"— se
                    // lee de una vez. Va en RELATIVO y no en absoluto ("que ayer", "que julio")
                    // porque el periodo mostrado se navega con las flechas: "ayer" solo sería
                    // cierto mirando hoy.
                    text = stringResource(
                        if (delta >= 0) R.string.stats_delta_more else R.string.stats_delta_less,
                        abs(delta),
                        stringResource(previousPeriodLabelRes(state.selection.mode))
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    // Dos líneas y no una: ahora la frase tiene el ancho de la tarjeta para ella
                    // sola, pero con la escala tipográfica subida sigue sin caber de una vez.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Alto de las barras del histograma. */
private val HistogramHeight = 72.dp

/** Aire entre dos barras. Fijo: lo que se estira es la barra, que es la que lleva el dato. */
private val HistogramBarSpacing = 3.dp

/**
 * Grosor mínimo de una barra vacía, para que la casilla se vea aunque no tenga escuchas. Sin él, un
 * mes con veinte días en blanco parecería un mes de diez días.
 */
private val HistogramEmptyBar = 2.dp

/**
 * Opacidad de una casilla SIN escuchas. Es una pista de fondo y no una jerarquía de contenido: la
 * excepción que la regla del 29 ago 2026 deja abierta para los raíles de progreso.
 */
private const val HISTOGRAM_EMPTY_ALPHA = 0.22f

/**
 * El histograma de actividad del periodo: una barra por casilla, **incluidas las vacías**.
 *
 * Las vacías son la mitad del mensaje. Un mes con seis días en blanco tiene una forma distinta a uno
 * repartido, y el `GROUP BY` que produce el dato no puede devolver filas de días sin escuchas: las
 * repone [buildActivityChart].
 *
 * **No se anima y no tiene reloj.** Es un dato quieto, y la regla 1 de Motion prohíbe un productor
 * continuo de frames en una superficie que vive en pantalla — el mismo criterio por el que el anillo
 * de la racha lleva relieve de onda pero no la hace viajar.
 *
 * El eje va sin escala de magnitud a propósito: lo que este bloque cuenta es el REPARTO, y el total
 * exacto ya lo dice el titular de la tarjeta, a dos centímetros.
 */
@Composable
private fun ActivityHistogram(chart: ActivityChart, color: Color, axisColor: Color) {
    // Un periodo entero sin escuchas no pinta barras: 31 rayas al mínimo no son un histograma, son
    // ruido. La tarjeta se queda con su titular, que ya dice cero.
    if (chart.isEmpty) return
    val peak = chart.peak
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(HistogramBarSpacing),
            modifier = Modifier
                .fillMaxWidth()
                .height(HistogramHeight)
        ) {
            chart.columns.forEach { column ->
                // El alto se calcula y se fija, en vez de `fillMaxHeight(fracción)` con un
                // `heightIn` de suelo: esos dos se pelean. `fillMaxHeight` deja la restricción
                // entrante en `max = 0` para una casilla vacía y `heightIn` respeta lo que recibe,
                // así que el mínimo NUNCA se aplicaría y la barra vacía mediría cero.
                val barHeight = (HistogramHeight * (column.plays.toFloat() / peak))
                    .coerceAtLeast(HistogramEmptyBar)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .background(
                            // La barra vacía se dibuja igual pero atenuada: es una casilla que
                            // EXISTE y no tuvo escuchas, no una casilla que falta.
                            color = if (column.plays > 0) color
                            else color.copy(alpha = HISTOGRAM_EMPTY_ALPHA),
                            shape = RoundedCornerShape(HistogramBarSpacing)
                        )
                )
            }
        }
        HistogramAxis(columns = chart.columns, color = axisColor)
    }
}

/**
 * El eje del histograma: las etiquetas que llevan las casillas marcadas.
 *
 * **Va en un `Layout` propio y no en un `Row` de pesos iguales**, y ese fue el bug (device, 30 ago
 * 2026: *"mi histograma dice 1 8 1 2 2, no se entiende qué es"*). Con un peso por casilla, la
 * etiqueta hereda el ancho de SU barra — en un mes son ~10dp— y un número de dos cifras pide ~14, así
 * que `1 8 15 22 29` salía recortado a `1 8 1 2 2`. El fallo es silencioso porque el texto no
 * desaparece: se queda en una cifra, que sigue pareciendo un número.
 *
 * La etiqueta no puede medir lo que mide su barra. Aquí cada una se mide LIBRE y se coloca centrada
 * en su casilla, desbordando el hueco por los dos lados, que es lo que hace cualquier eje. Los
 * extremos se meten hacia dentro para que el primero y el último no se salgan de la tarjeta.
 *
 * Que no se solapen lo garantiza quien reparte las marcas ([buildActivityChart]): en un mes van una
 * cada siete días, o sea a ~78dp de distancia.
 */
@Composable
private fun HistogramAxis(columns: List<ActivityColumn>, color: Color) {
    // Solo las que llevan texto: las demás no emiten nodo. Se guarda el índice porque es lo que dice
    // bajo qué barra va cada una.
    val marks = remember(columns) {
        columns.mapIndexedNotNull { index, column ->
            if (column.label.isEmpty()) null else index to column.label
        }
    }
    if (marks.isEmpty()) return
    val total = columns.size
    Layout(
        content = {
            marks.forEach { (_, label) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    // Sin ajuste de línea: el hueco es estrecho a propósito y partir "15" en dos
                    // renglones sería peor que dejarlo desbordar.
                    softWrap = false
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) { measurables, constraints ->
        // Medida LIBRE de ancho: es justo lo que el `Row` de pesos no permitía.
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = constraints.maxWidth
        val spacing = HistogramBarSpacing.roundToPx()
        // El mismo reparto que hace el `Row` de las barras: el sobrante tras los huecos, a partes
        // iguales. Si esto y aquello dejaran de coincidir, las etiquetas señalarían a otra barra.
        val barWidth = (width - spacing * (total - 1)).toFloat() / total
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, placeable ->
                val index = marks[i].first
                val center = index * (barWidth + spacing) + barWidth / 2f
                val x = (center - placeable.width / 2f).toInt().coerceIn(0, width - placeable.width)
                placeable.place(x = x, y = 0)
            }
        }
    }
}

/**
 * Cabecera de una sección: el sello de la categoría, su nombre y su CONTEO del periodo.
 *
 * **El conteo va aquí y no en una tarjeta aparte**, que es lo que era hasta el 30 ago 2026. Las
 * cuatro tarjetas de categoría hacían dos trabajos a la vez —decir cuántas canciones distintas hubo
 * y ser la puerta a su ranking— y, con los rankings ya en esta pantalla, el segundo desapareció.
 * Aquí la fila dice "cuánto" y debajo se ve "cuáles".
 *
 * **Ese conteo no es el tamaño del ranking, y por eso la cabecera no lleva ninguna acción.** Dice
 * cuántas canciones distintas sonaron en el periodo (148, pongamos) mientras debajo hay diez filas.
 * Un "ver todo" al lado prometería las 148; al pie de las filas prometía lo mismo un poco más lejos.
 * Las dos posiciones se probaron el 30 ago 2026 y el problema no era dónde estaba el control sino
 * que existiera: el ranking es de diez y siempre se ve entero, así que no hay nada más que enseñar.
 *
 * **El color vive en el SELLO, no en el contenedor.** No es una elección nueva: es lo que ya hacían
 * tres de las cuatro tarjetas que esto sustituye (`summaryCardStyles`, índices 3..5 — contenedor
 * neutro, distintivo teñido). Cuatro bloques saturados seguidos competirían entre sí y con la
 * tarjeta del periodo, que es la que debe llevar la voz cantante.
 */
@Composable
private fun RankingSectionHeader(
    icon: String,
    label: String,
    count: Int,
    style: SummaryCardStyle
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp)
    ) {
        AppSurface(
            shape = CircleShape,
            color = style.badge,
            modifier = Modifier.size(SectionBadgeSize)
        ) {
            // fillMaxSize: sin él el Box se ciñe al glifo y `Center` no centra nada dentro del
            // sello — el mismo detalle que ya mordió en la píldora de Favoritos.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MaterialSymbol(icon, fill = true, size = 16.sp, color = style.onBadge)
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        CountChip { Text(count.toString()) }
    }
}

/**
 * Nota al pie de una sección: letra pequeña que acota lo que se acaba de leer.
 *
 * Existe por los porcentajes de género, cuyo denominador NO es el total de reproducciones del
 * periodo sino solo las que tienen género etiquetado. Sin decirlo, una biblioteca a medio etiquetar
 * enseña unos porcentajes que no suman 100 y nada explica por qué.
 */
@Composable
private fun SectionFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 8.dp)
    )
}

/** Lado del sello de una cabecera de sección: menor que el de una tarjeta, que manda más. */
private val SectionBadgeSize = 28.dp

/**
 * Paleta de una pieza de la pantalla: su contenedor, el color de su texto y el par del distintivo.
 *
 * Se pasa entera en vez de un enum con un `when` dentro del componente porque el reparto es una
 * decisión de la PANTALLA —qué se lleva cada acento— y no del componente, que solo pinta lo que le
 * den. Hoy la usan la tarjeta del periodo (índice 0) y las cuatro cabeceras de sección (2..5).
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

    // **La tarjeta de la racha es NEUTRA aunque haya racha** (30 ago 2026). Estuvo en
    // `primaryContainer`, y ese color lo necesita ahora la tarjeta del periodo, que es la que lleva
    // la voz cantante: dos bloques saturados uno encima del otro compiten, y el que tiene que ganar
    // es el del periodo — la racha es un dato que no depende siquiera del selector.
    //
    // El acento no desaparece, se concentra: lo llevan el ANILLO y su disco, que es donde se lee sin
    // pelearse con nada. Es el mismo reparto que ya hacen tres de las cuatro `summaryCardStyles`
    // (contenedor neutro, distintivo teñido).
    //
    // La meta cumplida SÍ tiñe la tarjeta entera, y por eso sigue significando algo: es el único
    // estado que estrena color fuera de la paleta, y se gana el sitio precisamente por ser el único.
    val container = if (reached) goalGreen.first else AppColors.surfaceContainerHighest
    val onContainer = if (reached) goalGreen.second else AppColors.onSurface
    // **Todo lo que va ENCIMA de la tarjeta sale de un PAR tabulado**, y esto es un arreglo de
    // contraste (device, 28 ago 2026: "icono sin contraste"). Estuvo con `primary` de relleno y
    // `primaryContainer` de glifo, o sea dos roles VECINOS: con una paleta donde el contenedor sale
    // claro, el disco y su llama quedaban del mismo color y el anillo se perdía sobre la tarjeta.
    //
    // Un par `on*`/`*` es el único con contraste garantizado por definición —M3 los tabula justo
    // para esto—, así que el disco va relleno del color de acento y su glifo del compañero: con
    // racha, `primary` sobre `onPrimary`; sin ella y sobre el verde, la inversión del par de la
    // propia tarjeta. Es además lo que hace la referencia, donde el distintivo es un círculo lleno
    // sobre la tarjeta y no un acento sobre otro acento.
    val ringColor = when {
        reached -> goalGreen.second
        active -> AppColors.primary
        else -> AppColors.onSurface
    }
    val ringGlyphColor = when {
        reached -> goalGreen.first
        active -> AppColors.onPrimary
        else -> AppColors.surfaceContainerHighest
    }
    AppSurface(
        color = container,
        shape = StatsCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardSidePadding, vertical = 8.dp)
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
                    // y un 14 % se lee como "bastante". Es el mismo color del arco, atenuado —del
                    // arco y no del contenido de la tarjeta, para que los dos sean la misma pieza.
                    trackColor = ringColor.copy(alpha = STREAK_TRACK_ALPHA),
                    strokeWidth = StreakRingStroke,
                    modifier = Modifier.fillMaxSize()
                )
                AppSurface(
                    shape = CircleShape,
                    color = ringColor,
                    modifier = Modifier.size(StreakBadgeSize)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // El compañero del relleno del disco: contraste garantizado en cualquier
                        // paleta y en los dos temas. Ver dónde se elige el par, más arriba.
                        MaterialSymbol(
                            "local_fire_department",
                            fill = true,
                            size = 20.sp,
                            color = ringGlyphColor
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
                    color = rememberSecondaryContentColor(onContainer, container),
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
 * Fue `internal` mientras la reusaba la pantalla del ranking completo, que desapareció el 30 ago
 * 2026 al volverse desplegables las categorías. Sigue siendo función aparte —y no texto en línea—
 * porque la consumen el selector y el estado vacío.
 */
@Composable
private fun periodLabel(selection: StatsSelection): String {
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
 * Cómo se nombra el periodo ANTERIOR en la comparación de la tarjeta.
 *
 * **Quién decide si HAY comparación es el ViewModel** (`comparesWithPrevious`), no esto: día y
 * "Siempre" no la traen, así que sus ramas no se alcanzan. Se mapean igual —a la unidad que les
 * correspondería— para que el `when` sea exhaustivo sin un `else` que se tragaría en silencio un
 * modo nuevo.
 */
private fun previousPeriodLabelRes(mode: StatsPeriodMode): Int = when (mode) {
    StatsPeriodMode.WEEK -> R.string.stats_delta_ref_week
    StatsPeriodMode.YEAR -> R.string.stats_delta_ref_year
    StatsPeriodMode.DAY, StatsPeriodMode.MONTH, StatsPeriodMode.ALL ->
        R.string.stats_delta_ref_month
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
