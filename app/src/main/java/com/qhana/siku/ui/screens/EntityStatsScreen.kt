package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.StatsPeriodMode
import com.qhana.siku.data.model.StatsScopeKind
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.AlbumArt
import com.qhana.siku.ui.components.ArtistAvatar
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.viewmodel.ActivityChart
import com.qhana.siku.ui.viewmodel.EntityStatsUiState
import com.qhana.siku.ui.viewmodel.EntityStatsViewModel
import com.qhana.siku.ui.viewmodel.buildActivityChart

/** Lado de la imagen de identidad. El mismo para las tres, que solo cambian de forma. */
private val EntityImageSize = 56.dp

/** Esquina de la carátula y del collage. La foto de artista lleva la suya (cookie). */
private val EntityImageShape = RoundedCornerShape(12.dp)

/**
 * Estadísticas de UNA entidad —artista, álbum o género—, con el mismo selector de periodo que la
 * pantalla general.
 *
 * **Su puerta son los rankings de [StatsScreen]**, y ese es todo el motivo por el que existe: allí
 * se ve QUIÉN o QUÉ, y lo siguiente que uno se pregunta es qué le escucha dentro — una pregunta que
 * la pantalla general no puede responder sin volverse una lista de listas. El periodo se hereda por
 * la ruta ([com.qhana.siku.ui.navigation.Screen.EntityStats]): entrar desde "agosto de 2026" y
 * aterrizar en otro periodo rompería la pregunta a mitad.
 *
 * **Es UNA pantalla para los tres ámbitos.** Lo único que cambia es la imagen de identidad, cómo se
 * llama la sección de canciones y qué sub-entidad cuelga debajo, y las tres cosas se derivan del
 * ámbito (ver `StatsScope`). Tres pantallas serían tres copias con otro `WHERE`.
 *
 * **Y NO es lo mismo que el chip "Más reproducidas" del detalle**, aunque respondan parecido. Aquél
 * ordena la biblioteca por el contador histórico de `songs.playCount` y sirve para REPRODUCIR; esto
 * sale de `play_events`, se acota a un periodo y trae las cifras. Las dos fuentes conviven a
 * propósito y no se unifican (ver `StatsRepository`).
 *
 * **La regla de navegación dentro de estadísticas, escrita una vez**: tocar una entidad abre SUS
 * estadísticas, y la fila de identidad de arriba es siempre la salida a la biblioteca. Así no hay
 * que recordar qué toca en cada carrusel.
 *
 * La estructura sigue a la pantalla general: barra pequeña (esta tampoco tiene imagen de cabecera
 * que justifique un header inmersivo — la identidad es un dato de 56dp, no una portada de 380), fila
 * de identidad arriba del selector porque no depende de él, tarjeta del periodo con su histograma, y
 * debajo los rankings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityStatsScreen(
    onBackClick: () -> Unit,
    /** Puerta a la biblioteca: la fila de identidad de arriba. */
    onOpenEntity: (kind: StatsScopeKind, name: String, albumArtist: String?) -> Unit,
    /** Una sub-entidad del carrusel: abre SUS estadísticas. Ver la regla en el KDoc. */
    onChildClick: (kind: StatsScopeKind, name: String, albumArtist: String?) -> Unit,
    /** La que suena, para resaltar su fila en el ranking. */
    currentSong: Song?,
    /** Reproduce el ranking desde la fila tocada; la cola es el ranking entero. */
    onPlaySongs: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EntityStatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Por CAMPO (convención 9b): los rankings se re-emiten muestreados durante un sync y el
    // histograma cambia con el periodo, mientras que la imagen no se entera de ninguna de las dos.
    val topSongs by viewModel.topSongs.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val artUri by viewModel.artUri.collectAsStateWithLifecycle()
    val collage by viewModel.collage.collectAsStateWithLifecycle()
    val kind = viewModel.kind
    val entityName = viewModel.entityName

    // El eje se arma aquí y no en el ViewModel: sus etiquetas dependen del idioma, que vive en la
    // composición. Mismo motivo que en `StatsScreen`.
    val locale = LocalLocale.current.platformLocale
    val chart = remember(activity, locale) {
        activity?.let { buildActivityChart(it.selection, it.bucketing, it.plays, locale) }
    }

    val styles = summaryCardStyles()
    val listState = rememberLazyListState()

    // Al cambiar de periodo la lista vuelve arriba, pero NO al recomponerse la pantalla: volver de
    // otra entidad es salir y entrar de la composición, y ahí `rememberLazyListState` ya restaura la
    // posición. Mismo mecanismo —y mismo `rememberSaveable`, porque tiene que sobrevivir al mismo
    // viaje— que en `StatsScreen`.
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
        topBar = {
            TopAppBar(
                // El título es "Estadísticas" y no el nombre de la entidad: ése lo lleva la fila de
                // identidad, que además es la que se toca para ir a su música. Escrito en los dos
                // sitios, el de la barra sería el que no hace nada.
                title = { Text(stringResource(R.string.stats_title)) },
                colors = appTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = AppColors.surface
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
                    .safeSidePadding()
                    .widthIn(max = ComponentConfig.ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = StatsHeaderContentGap, bottom = listBottomInset)
            ) {
                // Arriba del selector, igual que la racha en la pantalla general y por el mismo
                // motivo: es lo único que NO depende del periodo. Debajo se leería como parte de lo
                // que ese control filtra.
                item(key = "entity") {
                    EntityIdentityRow(
                        kind = kind,
                        entityName = entityName,
                        artUri = artUri,
                        collage = collage,
                        onClick = { onOpenEntity(kind, entityName, viewModel.albumArtist) }
                    )
                }

                item(key = "period_selector") {
                    PeriodSelector(
                        selection = state.selection,
                        canGoPrevious = state.canGoPrevious,
                        canGoNext = state.canGoNext,
                        onSelectMode = viewModel::selectMode,
                        onPrevious = viewModel::goToPrevious,
                        onNext = viewModel::goToNext
                    )
                }

                if (state.isEmpty) {
                    item(key = "empty") {
                        StatsEmptyState(
                            // El nombre de la entidad NO va aquí: ya lo dice la fila de identidad,
                            // que sigue en pantalla justo encima.
                            title = stringResource(R.string.stats_empty_title),
                            // Un periodo vacío no es lo mismo que no haberla escuchado nunca: en el
                            // primero hay otro mes al que ir.
                            subtitle = stringResource(
                                if (state.selection.mode == StatsPeriodMode.ALL) {
                                    R.string.entity_stats_empty_all
                                } else {
                                    R.string.entity_stats_empty_period
                                }
                            )
                        )
                    }
                } else {
                    item(key = "period_card") {
                        EntityPeriodCard(
                            state = state,
                            kind = kind,
                            chart = chart,
                            style = styles[0]
                        )
                    }

                    if (topSongs.isNotEmpty()) {
                        item(key = "head_songs") {
                            RankingSectionHeader(
                                icon = "music_note_2",
                                label = stringResource(songsSectionLabel(kind)),
                                count = state.summary.distinctSongs,
                                style = styles[2]
                            )
                        }
                        // Filas hermanas de su cabecera: así la `LazyColumn` solo compone lo que se
                        // ve. Es el MISMO componente que el ranking de la pantalla general.
                        songRankingRows(
                            songs = topSongs,
                            currentSong = currentSong,
                            onPlay = onPlaySongs
                        )
                    }

                    // Vacío en un ÁLBUM, que no tiene sub-entidades: por debajo de un disco solo hay
                    // canciones, y ésas son la lista de arriba. Lo decide `StatsScope.child`.
                    val childKind = viewModel.childKind
                    if (children.isNotEmpty() && childKind != null) {
                        item(key = "head_children") {
                            RankingSectionHeader(
                                icon = if (childKind == StatsScopeKind.ALBUM) "album" else "artist",
                                label = stringResource(childrenSectionLabel(kind)),
                                count = state.summary.distinctSecondary,
                                style = styles[4]
                            )
                        }
                        item(key = "carousel_children") {
                            RankCarousel(
                                entries = children,
                                fallbackIcon = if (childKind == StatsScopeKind.ALBUM) "album" else "artist",
                                onClick = { entry -> onChildClick(childKind, entry.name, entry.albumArtist) }
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

/**
 * Cómo se llama la lista de canciones en cada ámbito.
 *
 * Tres textos y no uno genérico ("Tus canciones") porque la frase natural es distinta y la pantalla
 * ya se lee tres veces igual: de un artista se dice "lo que más le escuchaste", de un disco y de un
 * género, "lo que más escuchaste de él".
 */
private fun songsSectionLabel(kind: StatsScopeKind): Int = when (kind) {
    StatsScopeKind.ARTIST -> R.string.entity_stats_songs_artist
    StatsScopeKind.ALBUM -> R.string.entity_stats_songs_album
    StatsScopeKind.GENRE -> R.string.entity_stats_songs_genre
}

/** Cómo se llama el carrusel de sub-entidades. En un álbum no se pinta, así que no se usa. */
private fun childrenSectionLabel(kind: StatsScopeKind): Int = when (kind) {
    StatsScopeKind.ARTIST -> R.string.entity_stats_children_albums
    StatsScopeKind.ALBUM, StatsScopeKind.GENRE -> R.string.entity_stats_children_artists
}

/**
 * Qué entidad es ésta, y la puerta a su música.
 *
 * La fila ENTERA es el control —no un botón al final— porque no hay una segunda acción que pudiera
 * confundirse con ella: aquí solo se puede hacer una cosa, ir a la entidad. El chevron es lo que
 * dice que lleva a algún sitio.
 *
 * **La imagen es lo único que cambia con el ámbito**, y cada una lleva la forma que ya tiene en el
 * resto de la app: la foto de artista su silueta cookie (horneada en el bitmap, ver [ArtistAvatar]),
 * la carátula su esquina redondeada, y el género un collage — que no es una imagen, sino hasta
 * cuatro, porque un género no tiene una propia.
 */
@Composable
private fun EntityIdentityRow(
    kind: StatsScopeKind,
    entityName: String,
    artUri: String?,
    collage: List<String>,
    onClick: () -> Unit
) {
    val label = entityName.ifBlank { stringResource(unknownNameFor(kind)) }
    val openDescription = stringResource(openLabelFor(kind))
    AppSurface(
        onClick = onClick,
        color = AppColors.surfaceContainerHighest,
        shape = StatsCardShape,
        // Sin `contentDescription` propio: la fila ya lleva el nombre y "Ver …" como TEXTO, y el
        // `Surface` con `onClick` fusiona a sus hijos — poner uno aquí SUSTITUIRÍA ese anuncio y el
        // lector de pantalla dejaría de decir de quién es la fila.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardSidePadding, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            when (kind) {
                StatsScopeKind.ARTIST -> ArtistAvatar(
                    art = artUri,
                    modifier = Modifier.size(EntityImageSize),
                    placeholderIconSize = 26.sp
                )

                StatsScopeKind.ALBUM -> AlbumArt(
                    albumArtUri = artUri,
                    size = EntityImageSize,
                    shape = EntityImageShape
                )

                StatsScopeKind.GENRE -> Box(
                    modifier = Modifier
                        .size(EntityImageSize)
                        .clip(EntityImageShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Con la lista vacía el collage no dibuja nada y queda el glifo debajo: es el
                    // placeholder, y el componente deja esa decisión a cada pantalla a propósito.
                    MaterialSymbol("genres", size = 26.sp, color = AppColors.onSurfaceVariant)
                    AdaptiveCollage(arts = collage, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = openDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    // `onSurfaceVariant`, el par que M3 tabula para el contenido SECUNDARIO de la
                    // escala neutra. Estuvo derivado con `rememberSecondaryContentColor`, que
                    // existe para los contenedores de ACENTO —donde no hay `onXContainerVariant`—;
                    // aquí el fondo es `surfaceContainerHighest` y el rol sí existe.
                    color = AppColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MaterialSymbol("chevron_right", color = AppColors.onSurfaceVariant)
        }
    }
}

private fun openLabelFor(kind: StatsScopeKind): Int = when (kind) {
    StatsScopeKind.ARTIST -> R.string.entity_stats_open_artist
    StatsScopeKind.ALBUM -> R.string.entity_stats_open_album
    StatsScopeKind.GENRE -> R.string.entity_stats_open_genre
}

/** Un tag vacío tiene nombre propio en cada categoría, el mismo que usa el resto de la app. */
private fun unknownNameFor(kind: StatsScopeKind): Int = when (kind) {
    StatsScopeKind.ARTIST -> R.string.common_unknown_artist
    StatsScopeKind.ALBUM -> R.string.common_unknown_album
    StatsScopeKind.GENRE -> R.string.common_unknown_genre
}

/**
 * La tarjeta del periodo, gemela de la de la pantalla general: el TIEMPO de titular, las
 * reproducciones de apoyo y el histograma que le da forma al periodo.
 *
 * Lo que cambia es la línea de apoyo: aquí no hay comparación con el periodo anterior —dentro de una
 * sola entidad un disco nuevo da un +400 % que no describe ninguna tendencia— y en su lugar va de
 * cuántas canciones y de cuántos álbumes o artistas salieron esas escuchas, que es lo que dice si se
 * escucha ENTERA o siempre lo mismo.
 */
@Composable
private fun EntityPeriodCard(
    state: EntityStatsUiState,
    kind: StatsScopeKind,
    chart: ActivityChart?,
    style: SummaryCardStyle
) {
    val summary = state.summary
    // Ver el bloque largo en `StatsScreen · PeriodCard`: misma tarjeta, misma decisión.
    val secondary = style.onContainer
    AppSurface(
        color = style.container,
        shape = StatsCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardSidePadding)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(
                    if (summary.listenedIsEstimate) R.string.stats_card_listened_estimate
                    else R.string.stats_card_listened
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listeningTimeText(summary.listenedMs),
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = style.onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = pluralStringResource(R.plurals.stats_plays, summary.plays, summary.plays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.song_count,
                            summary.distinctSongs,
                            summary.distinctSongs
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // **El segundo número solo cuando dice algo.** En un artista o un género es
                    // cuántos álbumes o artistas distintos sonaron; en un ÁLBUM son sus artistas, y
                    // ahí un "1 artista" es la norma y no informa de nada — solo en una recopilación
                    // el número interesa, y cuál de los dos casos es no se sabe hasta tenerlo.
                    // El 0 también se calla: en una biblioteca sin ese tag, "0 álbumes" no dice nada
                    // de la entidad, dice algo de los tags.
                    val showSecondary = summary.distinctSecondary > 0 &&
                        (kind != StatsScopeKind.ALBUM || summary.distinctSecondary > 1)
                    if (showSecondary) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondary
                        )
                        Text(
                            text = pluralStringResource(
                                if (kind == StatsScopeKind.ARTIST) R.plurals.album_count
                                else R.plurals.artist_count,
                                summary.distinctSecondary,
                                summary.distinctSecondary
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            chart?.let {
                ActivityHistogram(chart = it, color = style.onContainer, axisColor = secondary)
            }
        }
    }
}
