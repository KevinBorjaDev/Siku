package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.COLLAGE_MAX_TILES
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.onContainerColor
import com.qhana.siku.ui.components.QuickActionChip
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.components.vividAccentColor
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.HomeArtistPick
import com.qhana.siku.ui.viewmodel.RelatedArtistUi
import com.qhana.siku.ui.viewmodel.HomeStats
import com.qhana.siku.data.model.PlaybackContext
import java.time.LocalTime

// Tarjeta grande de los carruseles Expressive del inicio (item "grande" del multi-browse; los
// medianos/chicos los deriva el propio carrusel). Cuadrada con etiqueta superpuesta.
private val HomeCardWidth = 196.dp
private val HomeCardHeight = 196.dp
private val HomeCardShape = RoundedCornerShape(24.dp)

/**
 * Margen lateral de TODO el contenido del inicio: saludo, chips de acciones rápidas, títulos de
 * sección y carruseles. Sale de una constante única porque el trabajo de que las tarjetas queden
 * a plomo con los títulos es exactamente eso — que los cinco sitios usen el MISMO número, no cinco
 * `16.dp` sueltos que se desalinean en cuanto alguien toca uno.
 */
private val HomeContentPadding = 16.dp

/**
 * Aire por encima del saludo, sumado al `contentPadding` que la lista ya reserva bajo el bloque de
 * pestañas. Calibrado en device: es de una sola pantalla, no de la rejilla general (ver
 * `HeaderContentGap` en LibraryScreen para esa).
 */
private val GreetingTopPadding = 8.dp

/**
 * Si este carrusel tiene algo que desplazar. Load-bearing para el GESTO, no para el aspecto:
 * `HorizontalMultiBrowseCarousel` es por dentro un `HorizontalPager` y queda ANIDADO dentro del
 * pager de las pestañas de la biblioteca, así que con el scroll habilitado se queda con el arrastre
 * horizontal aunque tenga una sola página — y cambiar de pestaña deslizando sobre el carrusel
 * dejaba de funcionar. Con un item no hay nada que desplazar y el gesto tiene que pasar de largo.
 *
 * Es `size > 1` y no una medida de si el contenido cabe: la tarjeta ocupa [HomeCardWidth] y con dos
 * o más el multi-browse siempre tiene recorrido en un teléfono. La condición se queda del lado
 * conservador — como mucho un carrusel de dos items sigue capturando el gesto, que es el
 * comportamiento normal de un carrusel con contenido fuera de pantalla.
 */
private val Collection<*>.isScrollable: Boolean
    get() = size > 1

/**
 * Pantalla de inicio (primera pestaña de la biblioteca). Carruseles horizontales Material 3
 * Expressive (multi-browse con recorte/parallax al hacer scroll) derivados del historial v22:
 * seguir escuchando, más reproducidas, recién agregadas y álbumes del momento, intercalados con
 * secciones GENERADAS ("Porque escuchaste a X", "Vuelve a escucharlas"). El saludo lleva un
 * subtítulo con un dato real del historial.
 *
 * Tocar una tarjeta de canción reproduce ESA sección completa como cola, empezando en la tocada
 * (misma semántica que tocar una canción en la biblioteca: reproduce y abre el NowPlaying).
 */
@Composable
fun HomeScreen(
    mostPlayed: List<Song>,
    recentlyAdded: List<Song>,
    topAlbums: List<AlbumSummary>,
    recentContexts: List<PlaybackContext>,
    /** Carátulas para el collage de la tarjeta de Favoritos (ver [ContextCarousel]). */
    favoriteCovers: List<String>,
    // Carátulas del collage de los contextos de género, por nombre en minúsculas: un género no
    // tiene carátula propia (ver LibraryViewModel.recentGenreArts).
    genreArts: Map<String, List<String>>,
    stats: HomeStats,
    artistPick: HomeArtistPick?,
    rediscover: List<Song>,
    currentSongId: String?,
    contentPadding: PaddingValues,
    onPlaySongs: (List<Song>, Int) -> Unit,
    // "Porque escuchaste a X" propone OTROS artistas, así que su tarjeta NAVEGA a un detalle en vez
    // de reproducir: descubrir a alguien es entrar a ver qué tiene, no que empiece a sonar.
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onResumeContext: (PlaybackContext) -> Unit,
    // Acciones rápidas de la fila de chips (arriba de los carruseles). Todas ARRANCAN música al
    // toque; las de una lista (favoritos, géneros) van SIEMPRE en aleatorio (para orden, el usuario
    // navega a la lista).
    canPlayAll: Boolean,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
    hasFavorites: Boolean,
    onShuffleFavorites: () -> Unit,
    /**
     * ¿Tiene sentido hablar de ORIGEN? Solo con la biblioteca partida entre lo que suena sin red y
     * lo que la necesita (ver `LibraryViewModel.hasSourceSplit`). Gobierna las dos acciones de
     * abajo, que aparecen y desaparecen junto con los chips de filtro de las listas.
     */
    showSourceActions: Boolean,
    onShuffleOffline: () -> Unit,
    onShuffleNotDownloaded: () -> Unit,
    // Géneros (chips extra tras los fijos): cada uno reproduce ese género en aleatorio.
    genres: List<String>,
    onShuffleGenre: (String) -> Unit,
    /** Abre la pantalla de estadísticas. Es su ÚNICA puerta en toda la app. */
    onStatsClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val isEmpty = recentContexts.isEmpty() && mostPlayed.isEmpty() &&
        recentlyAdded.isEmpty() && topAlbums.isEmpty() &&
        artistPick == null && rediscover.isEmpty()

    if (isEmpty) {
        HomeEmptyState(modifier = modifier, contentPadding = contentPadding)
        return
    }

    // Carátulas para el collage de los contextos de biblioteca ("Aleatorio"/"Toda la biblioteca":
    // no tienen una carátula propia): una muestra de la biblioteca (álbumes del momento + recién
    // agregadas). Con menos de 4 el card cae al ícono de tipo.
    val collageCovers = remember(topAlbums, recentlyAdded) {
        (topAlbums.mapNotNull { it.albumArtUri } +
            recentlyAdded.mapNotNull { it.albumArtUri?.toString() })
            .distinct()
            .take(COLLAGE_MAX_TILES)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // El top que llega incluye el alto del header (el contenido pasa por debajo de
            // TopBar + tabs): conservarlo o el saludo nace tapado por las pestañas. Y SIN sumarle
            // nada: ese inset ya trae los 4dp de aire tras el bloque de pestañas que reservan todas
            // las listas de la biblioteca, así que un extra aquí solo separaba el inicio del resto.
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "greeting") { GreetingHeader(stats) }

        item(key = "quick_actions") {
            HomeQuickActions(
                canPlayAll = canPlayAll,
                onShuffleAll = onShuffleAll,
                onPlayAll = onPlayAll,
                hasFavorites = hasFavorites,
                onShuffleFavorites = onShuffleFavorites,
                showSourceActions = showSourceActions,
                onShuffleOffline = onShuffleOffline,
                onShuffleNotDownloaded = onShuffleNotDownloaded,
                genres = genres,
                onShuffleGenre = onShuffleGenre
            )
        }

        // Justo tras las acciones rápidas y ANTES de los carruseles: es un DESTINO, no contenido,
        // así que pertenece con las acciones. Y es la única puerta a una pantalla entera — bajo
        // seis carruseles sería indescubrible.
        item(key = "stats_entry") { StatsEntryCard(stats = stats, onClick = onStatsClick) }

        if (recentContexts.isNotEmpty()) {
            item(key = "continue") {
                ContextCarousel(
                    title = stringResource(R.string.home_section_continue),
                    contexts = recentContexts,
                    collageCovers = collageCovers,
                    favoriteCovers = favoriteCovers,
                    genreArts = genreArts,
                    onResumeContext = onResumeContext
                )
            }
        }
        if (recentlyAdded.isNotEmpty()) {
            item(key = "recently_added") {
                SongCarousel(
                    title = stringResource(R.string.home_section_recently_added),
                    songs = recentlyAdded,
                    currentSongId = currentSongId,
                    onPlaySongs = onPlaySongs
                )
            }
        }
        // Sección generada: OTROS artistas que comparten género con el que más escuchas.
        artistPick?.let { pick ->
            item(key = "artist_pick") {
                RelatedArtistCarousel(
                    title = stringResource(R.string.home_section_because_you_listened, pick.seed),
                    artists = pick.related,
                    onArtistClick = onArtistClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }
        if (mostPlayed.isNotEmpty()) {
            item(key = "most_played") {
                SongCarousel(
                    title = stringResource(R.string.home_section_most_played),
                    songs = mostPlayed,
                    currentSongId = currentSongId,
                    onPlaySongs = onPlaySongs
                )
            }
        }
        // Sección generada: música que escuchaste pero tienes olvidada.
        if (rediscover.isNotEmpty()) {
            item(key = "rediscover") {
                SongCarousel(
                    title = stringResource(R.string.home_section_rediscover),
                    songs = rediscover,
                    currentSongId = currentSongId,
                    onPlaySongs = onPlaySongs
                )
            }
        }
        if (topAlbums.isNotEmpty()) {
            item(key = "top_albums") {
                AlbumCarousel(
                    title = stringResource(R.string.home_section_top_albums),
                    albums = topAlbums,
                    onAlbumClick = onAlbumClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        }
    }
}

@Composable
private fun GreetingHeader(stats: HomeStats) {
    // Sin remember: un when sobre la hora es gratis y así el saludo no queda congelado
    // si la app cruza una franja horaria con la composición viva.
    val greetingRes = when (LocalTime.now().hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..19 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
    // Subtítulo con dato real: escuchas de la semana, o el tamaño de la biblioteca de respaldo.
    val subtitle = when {
        stats.playedThisWeek > 0 ->
            pluralStringResource(R.plurals.home_stats_week, stats.playedThisWeek, stats.playedThisWeek)
        stats.librarySize > 0 ->
            pluralStringResource(R.plurals.home_stats_library, stats.librarySize, stats.librarySize)
        else -> null
    }
    Column(
        modifier = Modifier.padding(
            start = HomeContentPadding,
            end = HomeContentPadding,
            // Aire PROPIO del saludo, además del que ya trae el `contentPadding` de la lista.
            //
            // Estuvo en cero, con este razonamiento: el saludo es lo primero de la lista, el aire
            // contra las pestañas ya lo pone el contentPadding (el mismo que en las demás) y la
            // interlínea de `headlineMedium` añade lo suyo, así que sumar aquí era contar el mismo
            // espacio tres veces. La cuenta era correcta y aun así el resultado nacía apretado
            // (device, 20 ago 2026): un titular grande pide más aire por encima que una fila de
            // chips, y las demás pestañas empiezan justo con eso. Es la excepción de UNA pantalla,
            // por eso vive aquí y no en `HeaderContentGap`, que las gobierna a todas.
            top = GreetingTopPadding,
            bottom = 4.dp
        )
    ) {
        Text(
            text = stringResource(greetingRes),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = AppColors.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant
            )
        }
    }
}

/**
 * ACCIONES RÁPIDAS del inicio en GRILLA que envuelve (máx. 3 por fila, `FlowRow`), arriba de los
 * carruseles. Reemplaza al FAB flotante de reproducción del home. Chips tonales (secondaryContainer)
 * con `AssistChip` real de M3. Las dos primeras (aleatorio / en orden) se deshabilitan si no hay
 * canciones. Todas se ven a la vez (sin scroll lateral).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeQuickActions(
    canPlayAll: Boolean,
    onShuffleAll: () -> Unit,
    onPlayAll: () -> Unit,
    hasFavorites: Boolean,
    onShuffleFavorites: () -> Unit,
    showSourceActions: Boolean,
    onShuffleOffline: () -> Unit,
    onShuffleNotDownloaded: () -> Unit,
    genres: List<String>,
    onShuffleGenre: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.home_section_quick_actions))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeContentPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 3
        ) {
            QuickActionChip("shuffle", stringResource(R.string.home_action_shuffle), enabled = canPlayAll, onClick = onShuffleAll)
            QuickActionChip("play_arrow", stringResource(R.string.home_action_play_order), enabled = canPlayAll, fill = true, onClick = onPlayAll)
            QuickActionChip("favorite", stringResource(R.string.home_action_favorites), enabled = hasFavorites, fill = true, onClick = onShuffleFavorites)
            // Acciones por ORIGEN. Solo existen mientras la biblioteca esté partida entre lo que
            // suena sin red y lo que la necesita (misma puerta que los chips de filtro de las
            // listas, ver [LibraryViewModel.hasSourceSplit]): con todo descargado, "Sin conexión"
            // sería un segundo botón de "Aleatorio" y "Sin descargar" no tendría qué reproducir.
            //
            // `offline_pin` es el MISMO glifo con el que la fila de una canción y el chip del
            // reproductor dicen "esto ya está en el dispositivo": tres superficies, un solo signo.
            if (showSourceActions) {
                QuickActionChip("offline_pin", stringResource(R.string.home_action_offline), onClick = onShuffleOffline)
                QuickActionChip("cloud", stringResource(R.string.home_action_not_downloaded), onClick = onShuffleNotDownloaded)
            }
            // Chips de género (top por cantidad). El label ES el nombre del género (no un string res).
            genres.forEach { genre ->
                QuickActionChip("genres", label = genre, onClick = { onShuffleGenre(genre) })
            }
        }
    }
}

/**
 * Entrada a las estadísticas. COMPLEMENTA al saludo, no lo sustituye: reemplazarlo cambiaría la
 * bienvenida por un panel de números.
 *
 * Su número protagonista es el TIEMPO escuchado del mes, que es justo lo que el subtítulo del
 * saludo no dice (ése cuenta canciones de la semana) — dos datos, dos ventanas, ninguna repetición.
 * Sin nada escuchado aún enseña su invitación en vez de un "0 min", que no invita a entrar.
 */
@Composable
private fun StatsEntryCard(stats: HomeStats, onClick: () -> Unit) {
    val hasData = stats.monthPlays > 0
    AppSurface(
        color = AppColors.surface,
        shape = HomeCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeContentPadding, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            MaterialSymbol("bar_chart", size = 24.sp, color = AppColors.primary)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_home_card_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (hasData) {
                        stringResource(
                            R.string.stats_home_card_summary,
                            homeListeningTimeText(stats.monthListenedMs),
                            pluralStringResource(R.plurals.stats_plays, stats.monthPlays, stats.monthPlays)
                        )
                    } else {
                        stringResource(R.string.stats_home_card_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MaterialSymbol("chevron_right", size = 24.sp, color = AppColors.onSurfaceVariant)
        }
    }
}

@Composable
private fun homeListeningTimeText(millis: Long): String {
    val hoursAndMinutes = stringResource(R.string.stats_time_hours_minutes)
    val minutesOnly = stringResource(R.string.stats_time_minutes)
    return formatListeningTime(
        millis,
        hoursAndMinutes = { hours, minutes -> hoursAndMinutes.format(hours, minutes) },
        minutesOnly = { minutes -> minutesOnly.format(minutes) }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = AppColors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            start = HomeContentPadding,
            end = HomeContentPadding,
            top = 8.dp,
            bottom = 10.dp
        )
    )
}

/** Carrusel Expressive de canciones. Recortado/parallaxeado por el multi-browse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongCarousel(
    title: String,
    songs: List<Song>,
    currentSongId: String?,
    onPlaySongs: (List<Song>, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        val state = rememberCarouselState { songs.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = HomeCardWidth,
            itemSpacing = 8.dp,
            userScrollEnabled = songs.isScrollable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeContentPadding)
                .height(HomeCardHeight)
        ) { i ->
            val song = songs[i]
            // La tarjeta ES la punta de origen del morph: crece hasta ser el reproductor, igual que
            // una fila de "Todas", y con la misma maquinaria (`SongRowContainer`). El `maskClip` del
            // carrusel va POR FUERA de la punta compartida: durante el morph la superficie se dibuja
            // en el overlay del `SharedTransitionScope`, fuera del recorte del carrusel, que es lo
            // que le permite crecer más allá de su casilla.
            //
            // **Se identifica por el id de CANCIÓN, igual que una fila, y eso admite un empate**: los
            // carruseles del inicio son consultas independientes que no deduplican entre sí, así que
            // la misma canción puede tener tarjeta en "Añadidas hace poco" y en "Lo que más escuchas"
            // a la vez, y las dos tomarían el papel de origen. Se asumió el 17 ago 2026 creyendo que
            // "da igual de cuál de las dos salga" — **y eso es FALSO**: con dos puntas declarándose
            // destino no sale de ninguna, Compose no anima y el reproductor aparece sin morph (ver
            // `SharedTransitionGate`, donde el mismo empate ENTRE PESTAÑAS sí dio la cara el 19 ago).
            // Aquí sigue abierto: hace falta que una canción caiga en DOS carruseles a la vez. El
            // arreglo, si aparece, es dar el papel a UNA sola instancia —`SongRowContainer` ya tiene
            // identidad por instancia para `placedRows`—, y NO el id por superficie
            // (`home:<sección>:<id>` + `PlayerMorphOrigin` con canción y superficie separadas), que
            // se escribió entero y se descartó por complejidad.
            SongRowContainer(
                songId = song.id,
                modifier = Modifier.maskClip(HomeCardShape).fillMaxSize()
            ) {
                HomeCarouselCard(
                    art = song.albumArtUri?.toString(),
                    title = song.title,
                    subtitle = song.artist,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlaySongs(songs, i) },
                    labelAlpha = labelAlpha(),
                    // La tarjeta se RECORTA A SÍ MISMA, y eso no es redundante con el `maskClip` de
                    // fuera: mientras vuela, el shared element se dibuja en el overlay del
                    // `SharedTransitionScope`, que cuelga de la raíz y por tanto se salta los recortes
                    // de sus padres — incluido el del carrusel. Sin este clip, la superficie que crece
                    // salía con las ESQUINAS RECTAS mientras que la tarjeta parada es un cuadrado
                    // redondeado. Es el mismo motivo por el que la píldora y las filas de lista no
                    // pasan `clipInOverlayDuringTransition`: su propia superficie ya lleva la forma
                    // puesta (ver el Card de `MiniPlayer` y el `.clip(...)` de `SongItem`).
                    modifier = Modifier.fillMaxSize().clip(HomeCardShape)
                )
            }
        }
    }
}

/**
 * Carrusel de "Seguir escuchando": los últimos CONTEXTOS reproducidos (álbum/artista/lista/
 * favoritos/aleatorio/biblioteca), cada uno reanudable como tal. Cada tarjeta muestra la
 * carátula snapshot del contexto, su nombre y un distintivo + subtítulo de tipo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextCarousel(
    title: String,
    contexts: List<PlaybackContext>,
    collageCovers: List<String>,
    favoriteCovers: List<String>,
    genreArts: Map<String, List<String>>,
    onResumeContext: (PlaybackContext) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        val state = rememberCarouselState { contexts.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = HomeCardWidth,
            itemSpacing = 8.dp,
            userScrollEnabled = contexts.isScrollable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeContentPadding)
                .height(HomeCardHeight)
        ) { i ->
            val ctx = contexts[i]
            // Contextos SIN carátula propia → collage. Los de biblioteca (aleatorio / toda la
            // biblioteca) usan una muestra de la biblioteca; un GÉNERO usa las suyas, que es lo
            // que ya muestra su tarjeta en la pestaña Géneros — aquí se quedaba en el glifo de
            // relleno porque dependía del arte de la primera canción con la que se reprodujo.
            // Si el género aún no resolvió sus carátulas, `art` sigue de respaldo.
            val collage = when {
                ctx is PlaybackContext.LibraryShuffle || ctx is PlaybackContext.LibraryAll -> collageCovers
                ctx is PlaybackContext.Genre -> genreArts[ctx.name.lowercase()]
                // Favoritos era el ÚNICO contexto que se quedaba en el glifo de relleno sobre gris,
                // porque no tiene carátula propia y no entraba en ninguna de las dos ramas de
                // arriba: en un carrusel de portadas, la tarjeta se leía como un hueco.
                ctx is PlaybackContext.Favorites -> favoriteCovers
                else -> null
            }
            HomeCarouselCard(
                art = ctx.coverUri(),
                title = ctx.displayTitle(),
                subtitle = ctx.displaySubtitle(),
                badgeIcon = ctx.typeIcon(),
                placeholderIcon = ctx.typeIcon(),
                collage = collage,
                // Solo Favoritos: con el collage puesto, su tarjeta sería indistinguible de la de
                // una lista cualquiera, y el corazón es el símbolo que ya la identifica en toda la
                // app (la píldora del reproductor, el chip de acciones, la fila de Listas). Los
                // otros dos contextos con collage se nombran solos —"Aleatorio", "Toda la
                // biblioteca"— y no arrastran un símbolo propio que reponer.
                emblemIcon = "favorite".takeIf { ctx is PlaybackContext.Favorites },
                onClick = { onResumeContext(ctx) },
                labelAlpha = labelAlpha(),
                modifier = Modifier.maskClip(HomeCardShape)
            )
        }
    }
}

// --- Mapeo de PlaybackContext a su presentación en la tarjeta ---

private fun PlaybackContext.coverUri(): String? = when (this) {
    is PlaybackContext.Album -> coverUri
    is PlaybackContext.Artist -> coverUri
    is PlaybackContext.Genre -> coverUri
    is PlaybackContext.Playlist -> coverUri
    else -> null
}

@Composable
private fun PlaybackContext.displayTitle(): String = when (this) {
    is PlaybackContext.Album -> name.ifBlank { stringResource(R.string.common_unknown_album) }
    is PlaybackContext.Artist -> name
    is PlaybackContext.Genre -> name
    is PlaybackContext.Playlist -> name
    PlaybackContext.Favorites -> stringResource(R.string.home_ctx_favorites)
    PlaybackContext.LibraryShuffle -> stringResource(R.string.home_ctx_shuffle)
    PlaybackContext.LibraryAll -> stringResource(R.string.home_ctx_library)
}

// Los contextos-objeto (Favoritos/Aleatorio/Biblioteca) NO llevan subtítulo: su título YA es el
// tipo, así que repetirlo sería redundante. Los demás muestran el tipo como subtítulo del nombre.
@Composable
private fun PlaybackContext.displaySubtitle(): String = when (this) {
    is PlaybackContext.Album -> stringResource(R.string.home_ctx_album)
    is PlaybackContext.Artist -> stringResource(R.string.home_ctx_artist)
    is PlaybackContext.Genre -> stringResource(R.string.home_ctx_genre)
    is PlaybackContext.Playlist -> stringResource(R.string.home_ctx_playlist)
    else -> ""
}

private fun PlaybackContext.typeIcon(): String = when (this) {
    is PlaybackContext.Album -> "album"
    is PlaybackContext.Artist -> "artist"
    is PlaybackContext.Genre -> "genres"
    is PlaybackContext.Playlist -> "playlist_play"
    PlaybackContext.Favorites -> "favorite"
    PlaybackContext.LibraryShuffle -> "shuffle"
    PlaybackContext.LibraryAll -> "library_music"
}

/**
 * Carrusel de "Porque escuchaste a X": otros artistas de tu biblioteca que comparten género con el
 * que más escuchas (ver `LibraryViewModel.homeArtistPick`).
 *
 * A diferencia de los carruseles de canciones, sus tarjetas **navegan** al detalle del artista en vez
 * de reproducir: la sección es de descubrimiento, y descubrir a alguien es ir a ver qué tiene. Por eso
 * tampoco es punta del *container transform* hacia el reproductor — su transición es la del NavHost,
 * con la foto viajando al header del detalle (misma key que la celda de la pestaña Artistas).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun RelatedArtistCarousel(
    title: String,
    artists: List<RelatedArtistUi>,
    onArtistClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        val state = rememberCarouselState { artists.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = HomeCardWidth,
            itemSpacing = 8.dp,
            userScrollEnabled = artists.isScrollable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeContentPadding)
                .height(HomeCardHeight)
        ) { i ->
            val artist = artists[i]
            // Shared element de la foto → header del detalle de artista (misma key que la fila de la
            // pestaña Artistas). La FORMA de la tarjeta viaja con ella: el recorte que se ve aquí lo
            // pone el `maskClip` del carrusel, o sea un ANCESTRO, y el overlay del
            // `SharedTransitionScope` cuelga de la raíz — ver [entityImageSharedBounds].
            val sharedArtModifier = entityImageSharedBounds(
                key = "artist_image_${artist.name}",
                shape = HomeCardShape,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            HomeCarouselCard(
                art = artist.artUri,
                title = artist.name,
                subtitle = pluralStringResource(
                    R.plurals.song_count,
                    artist.songCount,
                    artist.songCount
                ),
                badgeIcon = "artist",
                placeholderIcon = "artist",
                onClick = { onArtistClick(artist.name) },
                artModifier = sharedArtModifier,
                labelAlpha = labelAlpha(),
                modifier = Modifier.maskClip(HomeCardShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumCarousel(
    title: String,
    albums: List<AlbumSummary>,
    onAlbumClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        val state = rememberCarouselState { albums.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = HomeCardWidth,
            itemSpacing = 8.dp,
            userScrollEnabled = albums.isScrollable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeContentPadding)
                .height(HomeCardHeight)
        ) { i ->
            val album = albums[i]
            // Shared element de la carátula → header del detalle de álbum (misma key que la celda de
            // la pestaña Álbumes). Con la FORMA de la tarjeta declarada para el vuelo: el recorte que
            // se ve aquí es el `maskClip` del carrusel —un ANCESTRO— y el overlay del
            // `SharedTransitionScope` se los salta todos. Ver [entityImageSharedBounds].
            val sharedArtModifier = entityImageSharedBounds(
                key = "album_image_${album.name}",
                shape = HomeCardShape,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            HomeCarouselCard(
                art = album.albumArtUri,
                title = album.name.ifBlank { stringResource(R.string.common_unknown_album) },
                subtitle = album.artist,
                onClick = { onAlbumClick(album.name) },
                artModifier = sharedArtModifier,
                labelAlpha = labelAlpha(),
                modifier = Modifier.maskClip(HomeCardShape)
            )
        }
    }
}

/**
 * Provee el alfa de la etiqueta de un ítem del carrusel según cuán "revelado" esté (0 = asomando
 * al borde, 1 = grande/foco). Devuelve un lambda que se lee en fase de dibujo (graphicsLayer), no
 * en composición, para no recomponer en cada frame del scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun CarouselItemScope.labelAlpha(): () -> Float = {
    val info = carouselItemDrawInfo
    val range = info.maxSize - info.minSize
    val fraction = if (range <= 0f) 1f else ((info.size - info.minSize) / range).coerceIn(0f, 1f)
    // Solo el ítem cercano al tamaño MÁXIMO (foco) muestra el label. En el multi-browse el ítem
    // "mediano" ya es bastante grande (fracción ~0.5-0.7), así que una rampa lineal lo dejaba casi
    // legible: remapeamos para que por debajo de ~0.65 sea 0 y suba suave (t²) hasta 1 en el foco.
    val t = ((fraction - 0.65f) / 0.35f).coerceIn(0f, 1f)
    t * t
}

/**
 * Velo bajo el emblema. Suficiente para que un glifo blanco se lea sobre una portada clara, y no
 * tanto como para que el collage deje de reconocerse: la tarjeta tiene que seguir enseñando de qué
 * está hecha la lista.
 */
private const val EmblemScrimAlpha = 0.45f

/** El emblema es la marca de la tarjeta, así que va por encima del glifo de relleno (44sp). */
private val EmblemGlyphSize = 52.sp

/**
 * Tarjeta de carrusel del inicio: carátula a sangre con degradado inferior y etiqueta superpuesta.
 * [modifier] llega con el recorte del carrusel ([maskClip]); [artModifier] es para el shared element
 * de la carátula (álbumes). Opcionalmente pinta un [badgeIcon] (esquina sup-der, distintivo de tipo)
 * y un indicador de "en reproducción" ([isCurrent], sup-der).
 */
@Composable
private fun HomeCarouselCard(
    art: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    badgeIcon: String? = null,
    placeholderIcon: String = "music_note",
    // Collage de carátulas (contextos sin carátula propia: aleatorio / toda la biblioteca).
    // Con al menos una URI reemplaza a [art]/[placeholderIcon]; se adapta a cuántas haya.
    collage: List<String>? = null,
    /**
     * Glifo de identidad sobre la imagen, para una tarjeta cuya portada NO dice de qué es. Se pinta
     * relleno y sobre un velo, y solo cuando hay algo debajo: con el placeholder ya se está
     * dibujando ese mismo símbolo en grande, y encimarle otro sería el mismo icono dos veces.
     */
    emblemIcon: String? = null,
    // Alfa de la etiqueta (título/subtítulo + degradado). Se lee en fase de dibujo para que en el
    // multi-browse solo el ítem GRANDE (el foco) muestre su label y los que asoman lo oculten.
    labelAlpha: () -> Float = { 1f }
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            // Sin indication: el ripple rectangular gris se hacía muy visible al presionar
            // (no hay long-press que lo amerite).
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .background(AppColors.surfaceContainerHighest)
    ) {
        when {
            !collage.isNullOrEmpty() -> AdaptiveCollage(collage, Modifier.fillMaxSize())
            art != null -> {
                val context = LocalContext.current
                // Sin clave propia: la de Coil (data + tamaño + transformaciones) ya es la
                // correcta. La versión anterior componía `<identidad de la tarjeta>@<uri>` para que
                // un cambio de imagen invalidara la entrada —la foto del artista que llega del
                // backfill, "ninguno de estos", una carátula reparada—, pero el URI SOLO consigue
                // eso mismo (si la imagen cambia, cambia la clave) sin el efecto secundario que
                // traía el prefijo: dos tarjetas que muestran la misma portada guardaban dos copias
                // del mismo bitmap. Ver el kdoc de la petición en [AlbumArt].
                val request = ImageRequest.Builder(context)
                    .data(art)
                    .crossfade(200)
                    .build()
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = artModifier.fillMaxSize()
                )
            }
            else -> {
                Box(modifier = artModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol(placeholderIcon, size = 44.sp, color = AppColors.onSurfaceVariant)
                }
            }
        }

        // Emblema: velo + glifo RELLENO. El velo no es decoración — un corazón blanco sobre un
        // collage de portadas claras desaparece, y es el mismo motivo por el que existe el
        // degradado de la etiqueta de abajo. Va con el alfa del label para que en el multi-browse
        // los ítems que solo asoman enseñen su portada limpia, igual que hacen con el título.
        val emblem = emblemIcon
        if (emblem != null && !collage.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = labelAlpha() }
                    .background(Color.Black.copy(alpha = EmblemScrimAlpha)),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol(emblem, fill = true, size = EmblemGlyphSize, color = Color.White)
            }
        }

        // Degradado inferior para legibilidad de la etiqueta sobre cualquier carátula. Se
        // desvanece junto con el label en los ítems que asoman (no-foco).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = labelAlpha() }
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
        )

        // Etiqueta (título + subtítulo) sobre el degradado. Solo el ítem grande (foco) la muestra.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer { alpha = labelAlpha() }
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Distintivo de tipo (esquina superior DERECHA).
        if (badgeIcon != null) {
            val badge = vividAccentColor(AppColors.primary)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badge)
                    .padding(4.dp)
            ) {
                MaterialSymbol(badgeIcon, size = 16.sp, color = onContainerColor(badge))
            }
        }

        // Indicador de "en reproducción" (esquina superior derecha).
        if (isCurrent) {
            val accent = vividAccentColor(AppColors.primary)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent)
                    .padding(4.dp)
            ) {
                MaterialSymbol("graphic_eq", size = 16.sp, color = onContainerColor(accent), fill = true)
            }
        }
    }
}

@Composable
private fun HomeEmptyState(modifier: Modifier, contentPadding: PaddingValues) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MaterialSymbol(
                "music_note",
                size = 64.sp,
                color = AppColors.outline
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant
            )
        }
    }
}
