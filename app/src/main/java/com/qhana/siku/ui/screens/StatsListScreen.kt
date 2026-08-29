package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.ui.components.AlbumArt
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.groupedListItemShapes
import com.qhana.siku.ui.components.rememberActiveRowContentColor
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.rememberTonalLayerColors
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appSegmentedListItemColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.viewmodel.StatsListKind
import com.qhana.siku.ui.viewmodel.StatsListViewModel

/** Lado de la miniatura de una fila de ranking: la talla de thumbnail del resto de listas. */
private val RankArtSize = 48.dp

/**
 * El ranking completo de una categoría (canciones, artistas, álbumes o géneros) en su propia
 * pantalla.
 *
 * **Vive aquí porque las estadísticas dejaron de ser una lista** (28 ago 2026): esa pantalla
 * apilaba cuatro rankings de diez filas bajo el resumen, o sea ~2.000px de scroll a los que casi
 * nadie llegaba. Ahora cada categoría es una TARJETA con su cifra y esta pantalla es lo que hay
 * detrás — el mismo movimiento que ya se hizo en el inicio, donde una sección corta con salida
 * informa más que una lista larga que nadie recorre.
 *
 * El periodo llega por la ruta (ver `StatsListViewModel`), así que el título dice qué se está
 * mirando y la lista no depende de que la pantalla anterior siga viva detrás.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsListScreen(
    currentSong: Song?,
    onBackClick: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleResFor(state.kind))) },
                // Aquí SÍ se usa la sobrecarga con subtítulo —la variante Expressive de la barra
                // pequeña— al revés que en estadísticas: allí el periodo ya lo dice, de forma
                // interactiva, la fila de flechas; aquí no hay selector y sin él la lista no diría
                // de cuándo es.
                // `periodLabel` es la MISMA función que titula el selector de estadísticas: la
                // etiqueta tiene que decir lo mismo a los dos lados del tap.
                subtitle = { Text(periodLabel(viewModel.selection)) },
                colors = appTopAppBarColors(
                    containerColor = AppColors.surfaceContainerHigh,
                    scrolledContainerColor = AppColors.surfaceContainerHigh
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) { MaterialSymbol("arrow_back") }
                },
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = AppColors.surfaceContainer
    ) { paddingValues ->
        val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            ComponentConfig.FloatingBarListInset

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .padding(top = paddingValues.calculateTopPadding())
                    .safeSidePadding()
                    .widthIn(max = ComponentConfig.ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 16.dp, bottom = listBottomInset)
            ) {
                when (state.kind) {
                    StatsListKind.SONGS -> songRankingRows(
                        songs = state.songs,
                        currentSong = currentSong,
                        onPlay = onPlaySongs
                    )
                    StatsListKind.ARTISTS -> nameRankingRows(
                        sectionKey = "artists",
                        entries = state.names,
                        fallbackIcon = "artist",
                        circularArt = true,
                        onClick = onArtistClick
                    )
                    StatsListKind.ALBUMS -> nameRankingRows(
                        sectionKey = "albums",
                        entries = state.names,
                        fallbackIcon = "album",
                        circularArt = false,
                        onClick = onAlbumClick
                    )
                    StatsListKind.GENRES -> nameRankingRows(
                        sectionKey = "genres",
                        entries = state.names,
                        fallbackIcon = "genres",
                        circularArt = false,
                        onClick = onGenreClick
                    )
                }
            }
        }
    }
}

private fun titleResFor(kind: StatsListKind): Int = when (kind) {
    StatsListKind.SONGS -> R.string.stats_section_top_songs
    StatsListKind.ARTISTS -> R.string.stats_section_top_artists
    StatsListKind.ALBUMS -> R.string.stats_section_top_albums
    StatsListKind.GENRES -> R.string.stats_section_top_genres
}

private fun LazyListScope.songRankingRows(
    songs: List<StatsSongEntry>,
    currentSong: Song?,
    onPlay: (List<Song>, Int) -> Unit
) {
    itemsIndexed(items = songs, key = { _, entry -> "song_${entry.song.id}" }) { index, entry ->
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
                    trailingContent = { PlayCountBadge(entry.plays, rowBackground, isPlaying) }
                )
            }
        }
    }
}

/** Ranking de artistas, álbumes o géneros: la misma fila, cambiando el glifo de respaldo. */
private fun LazyListScope.nameRankingRows(
    sectionKey: String,
    entries: List<StatsNameEntry>,
    fallbackIcon: String,
    circularArt: Boolean,
    onClick: (String) -> Unit
) {
    itemsIndexed(
        items = entries,
        key = { _, entry -> "${sectionKey}_${entry.name}" }
    ) { index, entry ->
        SegmentedListItem(
            onClick = { onClick(entry.name) },
            // `groupedListItemShapes` y no `segmentedShapes` a pelo: un grupo de UNA fila necesita
            // las cuatro esquinas EXTERIORES, y con un solo resultado eso pasa de verdad.
            shapes = groupedListItemShapes(index = index, count = entries.size),
            colors = appSegmentedListItemColors(AppColors.surface),
            leadingContent = {
                RankArt(artUri = entry.artUri, fallbackIcon = fallbackIcon, circular = circularArt)
            },
            trailingContent = { PlayCountBadge(entry.plays, AppColors.surface, isPlaying = false) },
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

/**
 * El contador de una fila de ranking: **un badge con el número y nada más**.
 *
 * Era el texto "42 reproducciones" (28 ago 2026, en device). En una lista donde TODAS las filas
 * dicen lo mismo salvo la cifra, la palabra es ruido repetido diez veces que además empuja el
 * nombre de la canción a elipsar antes; la columna ya se entiende por su contexto. La forma de
 * píldora es lo que lo distingue de un texto suelto y lo alinea con el resto de trailing de la app.
 *
 * Su color se DERIVA del fondo real de la fila y no de un rol: sobre el relleno de acento de la que
 * suena, un rol fijo se pierde. Es el mismo helper que usa la píldora del ⋮.
 */
@Composable
private fun PlayCountBadge(plays: Int, rowBackground: Color, isPlaying: Boolean) {
    val colors = rememberTonalLayerColors(background = rowBackground)
    // En la fila que suena manda el color derivado del relleno de acento; en una normal, el par
    // tonal de siempre. `rememberActiveRowContentColor` devuelve `Unspecified` fuera de ese caso.
    val activeColor = rememberActiveRowContentColor(rowBackground, isPlaying)
    Box(
        modifier = Modifier
            .background(colors.container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = plays.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (activeColor.isSpecified) activeColor else colors.content,
            maxLines = 1
        )
    }
}
