package com.qhana.siku.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
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
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.ui.components.AlbumArt
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.components.groupedListItemShapes
import com.qhana.siku.ui.components.rememberActiveRowContentColor
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appSegmentedListItemColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.viewmodel.StatsUiState
import com.qhana.siku.ui.viewmodel.StatsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale

/** Lado de la miniatura de una fila de ranking: la talla de thumbnail del resto de listas. */
private val RankArtSize = 48.dp

/** Ancho mínimo de una tarjeta del resumen antes de que `FlowRow` la baje de línea. */
private val SummaryCardMinWidth = 140.dp

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
 * actualización, y por eso ahí no hay tiempo escuchado ni racha. La pantalla no los rellena con
 * ceros: no los pinta y lo explica.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    currentSong: Song?,
    onBackClick: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
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
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Fondo en el nivel MEDIO de la escala: el contenido sube a `surface` y las barras bajan.
        // Mismo reparto que los detalles.
        containerColor = AppColors.surfaceContainer
    ) { paddingValues ->
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            ComponentConfig.FloatingBarListInset
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = listBottomInset)
        ) {
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
                item(key = "summary") { SummaryGrid(state) }

                state.streakDays?.let { days ->
                    item(key = "streak") { StreakRow(days) }
                }

                if (state.selection.mode == StatsPeriodMode.ALL) {
                    item(key = "all_time_note") { AllTimeNote(state.historyStartedAt) }
                }

                songRankingSection(
                    songs = state.tops.songs,
                    currentSong = currentSong,
                    onPlay = onPlaySongs
                )
                nameRankingSection(
                    sectionKey = "artists",
                    titleRes = R.string.stats_section_top_artists,
                    entries = state.tops.artists,
                    fallbackIcon = "artist",
                    circularArt = true,
                    onClick = onArtistClick
                )
                nameRankingSection(
                    sectionKey = "albums",
                    titleRes = R.string.stats_section_top_albums,
                    entries = state.tops.albums,
                    fallbackIcon = "album",
                    circularArt = false,
                    onClick = onAlbumClick
                )
                nameRankingSection(
                    sectionKey = "genres",
                    titleRes = R.string.stats_section_top_genres,
                    entries = state.tops.genres,
                    fallbackIcon = "genres",
                    circularArt = false,
                    onClick = onGenreClick
                )
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
        // `fillWidth` en su default (true) pese al aviso del KDoc sobre etiquetas desiguales: el
        // caso que lo motivó era "Desactivado" (11 caracteres) repartido entre tres botones en
        // 360dp. Aquí la más larga es "Siempre" (7) y en 393dp cada botón se lleva ~117dp, de sobra
        // incluso subiendo la escala tipográfica. A ancho completo se lee como lo que es: el control
        // principal de la pantalla.
        ConnectedChoiceGroup(
            options = StatsPeriodMode.entries.toList(),
            selected = state.selection.mode,
            onSelect = onSelectMode,
            labelFor = { mode ->
                stringResource(
                    when (mode) {
                        StatsPeriodMode.MONTH -> R.string.stats_period_month
                        StatsPeriodMode.YEAR -> R.string.stats_period_year
                        StatsPeriodMode.ALL -> R.string.stats_period_all
                    }
                )
            },
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
                    text = periodLabel(state),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryGrid(state: StatsUiState) {
    val summary = state.summary
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2
    ) {
        SummaryCard(
            icon = "play_circle",
            value = summary.plays.toString(),
            label = stringResource(R.string.stats_summary_plays),
            modifier = Modifier.weight(1f)
        )
        // El tiempo solo existe donde hay log de eventos. En "Siempre" la tarjeta NO se pinta: un
        // "0 min" al lado de miles de reproducciones sería una contradicción, no un dato.
        summary.listenedMs?.let { millis ->
            SummaryCard(
                icon = "schedule",
                value = listeningTimeText(millis),
                label = stringResource(R.string.stats_summary_time),
                modifier = Modifier.weight(1f)
            )
        }
        SummaryCard(
            icon = "music_note",
            value = summary.distinctSongs.toString(),
            label = stringResource(R.string.stats_summary_songs),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            icon = "artist",
            value = summary.distinctArtists.toString(),
            label = stringResource(R.string.stats_summary_artists),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    icon: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    AppSurface(
        color = AppColors.surface,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.widthIn(min = SummaryCardMinWidth)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            MaterialSymbol(icon, size = 20.sp, color = AppColors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StreakRow(days: Int) {
    AppSurface(
        color = AppColors.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            MaterialSymbol(
                "local_fire_department",
                size = 24.sp,
                // Apagado cuando no hay racha viva: el glifo sigue explicando de qué habla la fila,
                // pero sin celebrar un cero.
                color = if (days > 0) AppColors.primary else AppColors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (days > 0) {
                        pluralStringResource(R.plurals.stats_streak_days, days, days)
                    } else {
                        stringResource(R.string.stats_streak_none)
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = AppColors.onSurface
                )
                Text(
                    text = stringResource(R.string.stats_streak_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
    }
}

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

// ==================== RANKINGS ====================

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = AppColors.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp)
    )
}

/**
 * Ranking de canciones. Las filas van dentro de [SongRowContainer] porque son la punta ORIGEN del
 * container transform hacia el reproductor, igual que en cualquier lista tocable de la app.
 */
private fun LazyListScope.songRankingSection(
    songs: List<StatsSongEntry>,
    currentSong: Song?,
    onPlay: (List<Song>, Int) -> Unit
) {
    if (songs.isEmpty()) return
    item(key = "songs_header") { SectionHeader(stringResource(R.string.stats_section_top_songs)) }
    itemsIndexed(
        items = songs,
        key = { _, entry -> "song_${entry.song.id}" }
    ) { index, entry ->
        // La canción actual, esté sonando o en pausa: el resaltado marca "cargada", no "sonando".
        val isPlaying = currentSong?.id == entry.song.id
        val rowBackground = songRowBackground(AppColors.surface, isPlaying)
        SongRowContainer(
            songId = entry.song.id,
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 1.dp)
        ) {
            AppSurface(
                color = AppColors.surface,
                shape = rememberListItemShape(index, songs.size, isActive = isPlaying),
                modifier = Modifier.fillMaxWidth()
            ) {
                SongItem(
                    song = entry.song,
                    isPlaying = isPlaying,
                    modifier = Modifier.clickable { onPlay(songs.map { it.song }, index) },
                    trailingContent = { PlayCountLabel(entry.plays, rowBackground, isPlaying) }
                )
            }
        }
    }
}

/** Ranking de artistas, álbumes o géneros: la misma fila, cambiando el glifo de respaldo. */
private fun LazyListScope.nameRankingSection(
    sectionKey: String,
    @StringRes titleRes: Int,
    entries: List<StatsNameEntry>,
    fallbackIcon: String,
    circularArt: Boolean,
    onClick: (String) -> Unit
) {
    if (entries.isEmpty()) return
    item(key = "${sectionKey}_header") { SectionHeader(stringResource(titleRes)) }
    itemsIndexed(
        items = entries,
        key = { _, entry -> "${sectionKey}_${entry.name}" }
    ) { index, entry ->
        SegmentedListItem(
            onClick = { onClick(entry.name) },
            // `groupedListItemShapes` y no `segmentedShapes` a pelo: un grupo de UNA fila necesita
            // las cuatro esquinas EXTERIORES, y con un solo top eso pasa de verdad.
            shapes = groupedListItemShapes(index = index, count = entries.size),
            colors = appSegmentedListItemColors(AppColors.surface),
            leadingContent = {
                RankArt(artUri = entry.artUri, fallbackIcon = fallbackIcon, circular = circularArt)
            },
            trailingContent = {
                Text(
                    text = pluralStringResource(R.plurals.stats_plays, entry.plays, entry.plays),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.onSurfaceVariant
                )
            },
            content = {
                Column {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    entry.secondary?.let { secondary ->
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun RankArt(artUri: String?, fallbackIcon: String, circular: Boolean) {
    if (artUri != null) {
        AlbumArt(
            albumArtUri = artUri,
            size = RankArtSize,
            shape = if (circular) CircleShape else MaterialTheme.shapes.small
        )
    } else {
        Box(
            modifier = Modifier
                .size(RankArtSize)
                .background(
                    color = AppColors.surfaceContainerHighest,
                    shape = if (circular) CircleShape else MaterialTheme.shapes.small
                ),
            contentAlignment = Alignment.Center
        ) {
            MaterialSymbol(fallbackIcon, size = 24.sp, color = AppColors.onSurfaceVariant)
        }
    }
}

/** El contador de la fila de canción: mismo lenguaje que el resto de trailing de las listas. */
@Composable
private fun PlayCountLabel(plays: Int, rowBackground: Color, isPlaying: Boolean) {
    // Deriva del fondo REAL de la fila: sobre el relleno de acento de la que suena, un color de rol
    // fijo se pierde. `Unspecified` = fila normal, donde el trailing va en `onSurfaceVariant`.
    val activeColor = rememberActiveRowContentColor(rowBackground, isPlaying)
    Text(
        text = pluralStringResource(R.plurals.stats_plays, plays, plays),
        style = MaterialTheme.typography.labelLarge,
        color = if (activeColor.isSpecified) activeColor else AppColors.onSurfaceVariant,
        maxLines = 1
    )
}

// ==================== FORMATO ====================

/**
 * Etiqueta del periodo: "agosto de 2026", "2026" o "Siempre".
 *
 * Va por `DateTimeFormatter` localizado y no por un patrón a mano: el orden y la preposición
 * cambian con el idioma, y la app se traduce.
 */
@Composable
private fun periodLabel(state: StatsUiState): String {
    val locale = LocalLocale.current.platformLocale
    return when (state.selection.mode) {
        StatsPeriodMode.ALL -> stringResource(R.string.stats_period_all)
        StatsPeriodMode.YEAR -> state.selection.anchor.year.toString()
        StatsPeriodMode.MONTH -> {
            val raw = state.selection.anchor.format(
                DateTimeFormatter.ofPattern("LLLL yyyy", locale)
            )
            raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}

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
