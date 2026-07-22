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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.onContainerColor
import com.qhana.siku.ui.components.vividAccentColor
import com.qhana.siku.ui.viewmodel.HomeArtistPick
import com.qhana.siku.ui.viewmodel.HomeStats
import com.qhana.siku.data.model.PlaybackContext
import java.time.LocalTime

// Tarjeta grande de los carruseles Expressive del inicio (item "grande" del multi-browse; los
// medianos/chicos los deriva el propio carrusel). Cuadrada con etiqueta superpuesta.
private val HomeCardWidth = 196.dp
private val HomeCardHeight = 196.dp
private val HomeCardShape = RoundedCornerShape(24.dp)

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
    stats: HomeStats,
    artistPick: HomeArtistPick?,
    rediscover: List<Song>,
    currentSongId: String?,
    contentPadding: PaddingValues,
    onPlaySongs: (List<Song>, Int) -> Unit,
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
    // Géneros (chips extra tras los fijos): cada uno reproduce ese género en aleatorio.
    genres: List<String>,
    onShuffleGenre: (String) -> Unit,
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
            .take(4)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // El top que llega incluye el alto del header (el contenido pasa por debajo de
            // TopBar + tabs): conservarlo o el saludo nace tapado por las pestañas.
            top = contentPadding.calculateTopPadding() + 4.dp,
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
                genres = genres,
                onShuffleGenre = onShuffleGenre
            )
        }

        if (recentContexts.isNotEmpty()) {
            item(key = "continue") {
                ContextCarousel(
                    title = stringResource(R.string.home_section_continue),
                    contexts = recentContexts,
                    collageCovers = collageCovers,
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
        // Sección generada: el catálogo del artista más escuchado.
        artistPick?.let { pick ->
            item(key = "artist_pick") {
                SongCarousel(
                    title = stringResource(R.string.home_section_because_you_listened, pick.artist),
                    songs = pick.songs,
                    currentSongId = currentSongId,
                    onPlaySongs = onPlaySongs
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
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(greetingRes),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
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
    genres: List<String>,
    onShuffleGenre: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.home_section_quick_actions))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 3
        ) {
            QuickActionChip("shuffle", stringResource(R.string.home_action_shuffle), enabled = canPlayAll, onClick = onShuffleAll)
            QuickActionChip("play_arrow", stringResource(R.string.home_action_play_order), enabled = canPlayAll, fill = true, onClick = onPlayAll)
            QuickActionChip("favorite", stringResource(R.string.home_action_favorites), enabled = hasFavorites, fill = true, onClick = onShuffleFavorites)
            // Chips de género (top por cantidad). El label ES el nombre del género (no un string res).
            genres.forEach { genre ->
                QuickActionChip("genres", label = genre, onClick = { onShuffleGenre(genre) })
            }
        }
    }
}

/** Chip de una acción rápida: `AssistChip` real de M3 con relleno tonal y sin borde. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionChip(
    icon: String,
    label: String,
    enabled: Boolean = true,
    fill: Boolean = false,
    onClick: () -> Unit
) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        // Sin color explícito: el icono hereda el leadingIconContentColor del chip (y se
        // atenúa solo cuando está deshabilitado).
        leadingIcon = { MaterialSymbol(icon, size = 18.sp, fill = fill) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = colorScheme.secondaryContainer,
            labelColor = colorScheme.onSecondaryContainer,
            leadingIconContentColor = colorScheme.onSecondaryContainer
        ),
        border = null
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeCardHeight)
        ) { i ->
            val song = songs[i]
            HomeCarouselCard(
                art = song.albumArtUri?.toString(),
                cacheKey = song.id,
                title = song.title,
                subtitle = song.artist,
                isCurrent = song.id == currentSongId,
                onClick = { onPlaySongs(songs, i) },
                labelAlpha = labelAlpha(),
                modifier = Modifier.maskClip(HomeCardShape)
            )
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
    onResumeContext: (PlaybackContext) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        val state = rememberCarouselState { contexts.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = HomeCardWidth,
            itemSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeCardHeight)
        ) { i ->
            val ctx = contexts[i]
            // Los contextos de biblioteca (aleatorio / toda la biblioteca) no tienen carátula
            // propia → collage de una muestra de la biblioteca.
            val collage = if (ctx is PlaybackContext.LibraryShuffle || ctx is PlaybackContext.LibraryAll) {
                collageCovers
            } else null
            HomeCarouselCard(
                art = ctx.coverUri(),
                cacheKey = ctx.key,
                title = ctx.displayTitle(),
                subtitle = ctx.displaySubtitle(),
                badgeIcon = ctx.typeIcon(),
                placeholderIcon = ctx.typeIcon(),
                collage = collage,
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
    is PlaybackContext.Playlist -> coverUri
    else -> null
}

@Composable
private fun PlaybackContext.displayTitle(): String = when (this) {
    is PlaybackContext.Album -> name.ifBlank { stringResource(R.string.common_unknown_album) }
    is PlaybackContext.Artist -> name
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
    is PlaybackContext.Playlist -> stringResource(R.string.home_ctx_playlist)
    else -> ""
}

private fun PlaybackContext.typeIcon(): String = when (this) {
    is PlaybackContext.Album -> "album"
    is PlaybackContext.Artist -> "artist"
    is PlaybackContext.Playlist -> "playlist_play"
    PlaybackContext.Favorites -> "favorite"
    PlaybackContext.LibraryShuffle -> "shuffle"
    PlaybackContext.LibraryAll -> "library_music"
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(HomeCardHeight)
        ) { i ->
            val album = albums[i]
            // Shared element de la carátula → header del detalle de álbum (misma key que la
            // celda de la pestaña Álbumes), preservado dentro del ítem recortado del carrusel.
            val sharedArtModifier =
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "album_image_${album.name}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            HomeCarouselCard(
                art = album.albumArtUri,
                cacheKey = "album_${album.name}",
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

/** Collage 2×2 de carátulas (contextos de biblioteca sin carátula propia: aleatorio / biblioteca). */
@Composable
private fun CollageArt(covers: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CollageCell(covers[0], Modifier.weight(1f).fillMaxHeight())
            CollageCell(covers[1], Modifier.weight(1f).fillMaxHeight())
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CollageCell(covers[2], Modifier.weight(1f).fillMaxHeight())
            CollageCell(covers[3], Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun CollageCell(uri: String, modifier: Modifier) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(colorScheme.surfaceContainerHighest)
    )
}

/**
 * Tarjeta de carrusel del inicio: carátula a sangre con degradado inferior y etiqueta superpuesta.
 * [modifier] llega con el recorte del carrusel ([maskClip]); [artModifier] es para el shared element
 * de la carátula (álbumes). Opcionalmente pinta un [badgeIcon] (esquina sup-der, distintivo de tipo)
 * y un indicador de "en reproducción" ([isCurrent], sup-der).
 */
@Composable
private fun HomeCarouselCard(
    art: String?,
    cacheKey: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    badgeIcon: String? = null,
    placeholderIcon: String = "music_note",
    // Collage 2×2 de carátulas (contextos sin carátula propia: aleatorio / toda la biblioteca).
    // Si trae 4+ URIs reemplaza a [art]/[placeholderIcon]; con menos cae al ícono.
    collage: List<String>? = null,
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
            .background(colorScheme.surfaceContainerHighest)
    ) {
        when {
            collage != null && collage.size >= 4 -> CollageArt(collage, Modifier.fillMaxSize())
            art != null -> {
                val context = LocalContext.current
                val request = ImageRequest.Builder(context)
                    .data(art)
                    .apply {
                        if (cacheKey != null) {
                            memoryCacheKey(cacheKey)
                            diskCacheKey(cacheKey)
                        }
                    }
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
                    MaterialSymbol(placeholderIcon, size = 44.sp, color = colorScheme.onSurfaceVariant)
                }
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
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
            val badge = vividAccentColor(colorScheme.primary)
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
            val accent = vividAccentColor(colorScheme.primary)
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
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
