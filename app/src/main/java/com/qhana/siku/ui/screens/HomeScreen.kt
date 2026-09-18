package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.ArtCarouselCard
import com.qhana.siku.ui.components.COLLAGE_MAX_TILES
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.KeylineCarousel
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.QuickActionButton
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.LocalEffectivePaletteStyle
import com.qhana.siku.ui.theme.LocalThemeGeneration
import com.materialkolor.PaletteStyle
import com.qhana.siku.ui.viewmodel.HomeDiscover
import com.qhana.siku.ui.viewmodel.HomeDiscoverKind
import com.qhana.siku.ui.viewmodel.RelatedArtistUi
import com.qhana.siku.ui.viewmodel.HomeStats
import com.qhana.siku.data.model.PlaybackContext
import java.time.LocalTime

// Tarjeta grande de los carruseles Expressive del inicio (el item "grande" del arreglo de
// keylines; los medianos/chicos los deriva el propio carrusel). Cuadrada con etiqueta superpuesta.
//
// **Se probó y se DESCARTÓ la anatomía de `AlbumTileCard`** (portada arriba y texto en un
// contenedor debajo, 27 ago 2026): sobre el papel unificaba el inicio con la pestaña Álbumes y
// entraba una tarjeta más por pantalla, pero en device el contenedor bajo cada portada se veía
// FEO — un carrusel es una tira de imágenes, y ponerle una banda de texto a cada una le quita
// justo lo que la hace una tira. La etiqueta superpuesta se queda. NO reintroducir el contenedor
// de abajo aquí; en una REJILLA (Álbumes) sigue siendo lo correcto, que es donde ya vive.
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

/** Lado del distintivo de la entrada a Estadísticas. El mismo que el corazón de Favoritos. */
private val StatsBadgeSize = 48.dp

/**
 * Si este carrusel tiene algo que desplazar. Load-bearing para el GESTO, no para el aspecto:
 * [KeylineCarousel] es por dentro un `HorizontalPager` y queda ANIDADO dentro del pager de las
 * pestañas de la biblioteca, así que con el scroll habilitado se queda con el arrastre horizontal
 * aunque tenga una sola página — y cambiar de pestaña deslizando sobre el carrusel dejaba de
 * funcionar. Con un item no hay nada que desplazar y el gesto tiene que pasar de largo.
 *
 * Es `size > 1` y no una medida de si el contenido cabe: la tarjeta ocupa [HomeCardWidth] y con dos
 * o más el carrusel siempre tiene recorrido en un teléfono. La condición se queda del lado
 * conservador — como mucho un carrusel de dos items sigue capturando el gesto, que es el
 * comportamiento normal de un carrusel con contenido fuera de pantalla.
 */
private val Collection<*>.isScrollable: Boolean
    get() = size > 1

/**
 * Pantalla de inicio (primera pestaña de la biblioteca). **Tres secciones y no siete** (27 ago 2026).
 *
 * El criterio que las eligió: la biblioteca es del USUARIO y ya la conoce entera, así que un carrusel
 * que la reordena no descubre nada — para reordenar están las pestañas y sus sorts. Sobrevive lo que
 * quita toques hasta la música (los chips y "Seguir escuchando"), lo único que todavía no conoce de
 * su propia biblioteca ("Recién agregadas") y UN hueco de descubrimiento que se turna entre tres
 * candidatas, una por día (ver `LibraryViewModel.homeDiscover`).
 *
 * **Los rankings NO se movieron: se BORRARON porque ya existían.** "Más reproducidas" y "Álbumes del
 * momento" eran Estadísticas → Siempre con la cifra quitada — literalmente la misma consulta, hasta
 * el punto de que `StatsRepository` llama al mismo `SongDao.getMostPlayedFlow`.
 *
 * Tocar una tarjeta de canción reproduce ESA sección completa como cola, empezando en la tocada
 * (misma semántica que tocar una canción en la biblioteca: reproduce y abre el NowPlaying).
 */
@Composable
fun HomeScreen(
    recentlyAdded: List<Song>,
    recentContexts: List<PlaybackContext>,
    /** Carátulas para el collage de la tarjeta de Favoritos (ver [ContextCarousel]). */
    favoriteCovers: List<String>,
    // Carátulas del collage de los contextos de género, por nombre en minúsculas: un género no
    // tiene carátula propia (ver LibraryViewModel.recentGenreArts).
    genreArts: Map<String, List<String>>,
    stats: HomeStats,
    /** La sección de descubrimiento de HOY, o null si no hay ninguna con contenido. */
    discover: HomeDiscover?,
    currentSongId: String?,
    contentPadding: PaddingValues,
    onPlaySongs: (List<Song>, Int) -> Unit,
    // Un contexto de descubrimiento por artistas propone OTROS artistas, así que su tarjeta NAVEGA a
    // un detalle en vez de reproducir: descubrir a alguien es entrar a ver qué tiene.
    onArtistClick: (String) -> Unit,
    onResumeContext: (PlaybackContext) -> Unit,
    /**
     * "Ver todo" de Recién agregadas → `Screen.RecentlyAdded`, su ruta propia.
     *
     * Fue nullable mientras el destino era la pestaña Canciones, que se puede ocultar desde
     * Ajustes; con una ruta propia el destino existe siempre.
     */
    onSeeAllRecent: () -> Unit,
    // Acciones rápidas de la fila de chips (arriba de las secciones). Todas ARRANCAN música al
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
    val isEmpty = recentContexts.isEmpty() && recentlyAdded.isEmpty() && discover == null

    if (isEmpty) {
        HomeEmptyState(modifier = modifier, contentPadding = contentPadding)
        return
    }

    // Carátulas para el collage de los contextos de biblioteca ("Aleatorio"/"Toda la biblioteca":
    // no tienen una carátula propia): una muestra de lo que hay a mano en esta pantalla. Salía de
    // "Álbumes del momento" + "Recién agregadas"; al desaparecer aquélla, la muestra la completan
    // las canciones de la sección rotativa — con COLLAGE_MAX_TILES = 4 y dos secciones de 5, sobra.
    // Con menos de 4 el card cae al ícono de tipo.
    val collageCovers = remember(recentlyAdded, discover) {
        val discoverSongs = (discover as? HomeDiscover.Songs)?.songs.orEmpty()
        (recentlyAdded + discoverSongs)
            .mapNotNull { it.albumArtUri?.toString() }
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

        // Justo tras el saludo y ANTES de las acciones rápidas: es un DESTINO, no contenido, y es
        // la ÚNICA puerta a esa pantalla en toda la app — bajo las secciones sería indescubrible.
        item(key = "stats_entry") { StatsEntry(stats = stats, onClick = onStatsClick) }

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
                    onPlaySongs = onPlaySongs,
                    onSeeAll = onSeeAllRecent
                )
            }
        }
        // El hueco rotativo: una de las tres candidatas, la que le toque hoy.
        when (discover) {
            null -> Unit
            is HomeDiscover.Artists -> item(key = "discover_artists") {
                RelatedArtistCarousel(
                    title = stringResource(R.string.home_section_because_you_listened, discover.seed),
                    artists = discover.related,
                    onArtistClick = onArtistClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
            is HomeDiscover.Songs -> item(key = "discover_songs") {
                SongCarousel(
                    title = stringResource(
                        when (discover.kind) {
                            HomeDiscoverKind.NEVER_PLAYED -> R.string.home_section_never_played
                            HomeDiscoverKind.REDISCOVER -> R.string.home_section_rediscover
                        }
                    ),
                    songs = discover.songs,
                    currentSongId = currentSongId,
                    onPlaySongs = onPlaySongs,
                    onSeeAll = null
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
    // El subtítulo NO repite el dato del mes: ese lo dice la fila de abajo, y dos veces en el mismo
    // bloque serían dos formas de decir lo mismo a dos centímetros una de otra.
    val subtitle = stats.librarySize
        .takeIf { it > 0 }
        ?.let { pluralStringResource(R.plurals.home_stats_library, it, it) }
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
                color = AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Entrada a Estadísticas: una `Card` con un distintivo `Cookie4Sided` (girado 90°) a la izquierda,
 * dos renglones de texto, y un botón "Ver" a la derecha que es el ÚNICO objetivo táctil (la fila
 * entera no navega).
 *
 * Todos sus colores salen de [statsEntryRoles], la tabla por paleta × tema.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatsEntry(stats: HomeStats, onClick: () -> Unit) {
    val roles = statsEntryRoles(LocalEffectivePaletteStyle.current, LocalThemeGeneration.current.isDark)
    val cardColors = CardDefaults.cardColors(containerColor = roles.card, contentColor = roles.title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeContentPadding),
        // SIN `onClick` en la tarjeta (2 sep 2026): la acción la lleva el botón "Ver". Antes
        // toda la fila era el objetivo táctil y el chevron un affordance mudo.
        // Un peldaño por debajo de la píldora completa (`RoundedCornerShape(50)`, que es lo que
        // lleva la de Favoritos): con el alto de esta fila el radio pleno son ~36dp y se comía
        // demasiado los extremos. `extraLarge` son 28 y es TOKEN, no un número a ojo — el MiniPlayer
        // usa este mismo par para conmutar entre píldora y rectángulo redondeado.
        shape = MaterialTheme.shapes.extraLarge,
        colors = cardColors
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 16 a la izquierda, el margen normal de la app: los 20 de la píldora de
                // Favoritos existen porque allí los lados son curva completa y el contenido se le
                // pegaba; con `extraLarge` el lado recto empieza mucho antes y ese extra sobra.
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSurface(
                // `Cookie4Sided` girado 90° (`startAngle` rota la geometría del `Shape`, no el
                // contenido).
                shape = MaterialShapes.Cookie4Sided.toShape(startAngle = 90),
                color = roles.badge,
                modifier = Modifier.size(StatsBadgeSize)
            ) {
                // fillMaxSize: sin él el Box se ciñe al glifo y `Center` no centra nada dentro del
                // distintivo (el mismo detalle que ya mordió en la píldora de Favoritos).
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol(
                        "bar_chart",
                        fill = true,
                        size = 24.sp,
                        color = roles.onBadge
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_home_entry_title),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = roles.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val hasData = stats.monthPlays > 0
                Text(
                    text = if (hasData) {
                        homeListeningTimeText(stats.monthListenedMs)
                    } else {
                        // Sin nada escuchado no se pinta un "0 min", que no invita a entrar.
                        stringResource(R.string.stats_home_entry_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = roles.subtitle,
                    // El dato ("2h 15m") es compacto en CUALQUIER idioma y a 1 línea le sobra sitio;
                    // la ayuda del estado vacío es una frase y no un dato, así que su longitud varía
                    // con el idioma y la escala tipográfica — a 1 línea se cortaba a la mitad
                    // ("Play something and your summar…", visto en device en inglés). 2 líneas es
                    // el techo real del sitio que deja la fila (icono + botón "Ver" a los lados).
                    maxLines = if (hasData) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // El ÚNICO objetivo táctil de la tarjeta: la acción vive aquí, no en la fila. Un VERBO
            // ("Ver"), no un botón de icono.
            //
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = roles.button,
                    contentColor = roles.onButton
                )
            ) {
                // Sin `style`: `Button` ya pone `labelLarge` en su slot.
                Text(text = stringResource(R.string.stats_home_entry_action))
            }
        }
    }
}

/** Los colores de [StatsEntry] que dependen de la paleta. */
@Immutable
private class StatsEntryRoles(
    val card: Color,
    val title: Color,
    val subtitle: Color,
    val badge: Color,
    val onBadge: Color,
    val button: Color,
    val onButton: Color
)

/**
 * **La tabla de roles de la tarjeta de estadísticas por PALETA × TEMA**, elegida fila a fila por el
 * usuario en el banco del inicio (13 sep 2026) y validada contra siete carátulas. Es la fuente de
 * verdad: si una fila cambia en el banco, cambia aquí, y viceversa. Mismo patrón que
 * `miniPlayerRoles`; el estilo es el EFECTIVO (una carátula acromática pinta en `Monochrome`).
 *
 * Los defaults de [statsRoles] son lo que comparten la mayoría —la Filled Card de M3
 * (`surfaceContainerHighest`), el distintivo en el par Fixed secundario y el botón lleno en
 * `primary`—, y cada fila sólo escribe lo que se aparta.
 *
 * El banco marcaba fallos al aprobar (recuento de tarjeta y toolbar juntos, sobre 7 carátulas):
 * Neutro · claro 20, Equilibrado · oscuro 15, Equilibrado · claro 14, Fiel y Fidelidad · claro 7.
 * Aprobados sabiéndolo: no "arreglarlos" sin preguntar.
 */
@Composable
@ReadOnlyComposable
private fun statsEntryRoles(style: PaletteStyle, isDark: Boolean): StatsEntryRoles = when (style) {
    PaletteStyle.TonalSpot ->
        if (isDark) statsRoles(card = AppColors.inversePrimary)
        else statsRoles(
            card = AppColors.primaryContainer,
            title = AppColors.onPrimaryContainer,
            subtitle = AppColors.onPrimaryContainer,
            badge = AppColors.primary,
            onBadge = AppColors.onPrimary
        )
    PaletteStyle.Content, PaletteStyle.Fidelity ->
        if (isDark) statsRoles(card = AppColors.inversePrimary)
        else statsRoles(
            card = AppColors.primaryFixedDim,
            title = AppColors.onPrimaryFixedVariant,
            subtitle = AppColors.onPrimaryFixedVariant,
            badge = AppColors.primary,
            onBadge = AppColors.onPrimary
        )
    PaletteStyle.Rainbow ->
        if (isDark) statsRoles(card = AppColors.inversePrimary, badge = AppColors.primary)
        else statsRoles(
            card = AppColors.onPrimaryContainer,
            title = AppColors.inverseOnSurface,
            subtitle = AppColors.inverseOnSurface,
            badge = AppColors.inversePrimary,
            onBadge = AppColors.onPrimaryContainer,
            button = AppColors.inversePrimary,
            onButton = AppColors.onPrimaryContainer
        )
    PaletteStyle.Vibrant, PaletteStyle.Neutral, PaletteStyle.Expressive,
    PaletteStyle.FruitSalad, PaletteStyle.Monochrome -> statsRoles()
}

@Composable
@ReadOnlyComposable
private fun statsRoles(
    card: Color = AppColors.surfaceContainerHighest,
    title: Color = AppColors.onSurface,
    subtitle: Color = AppColors.onSurfaceVariant,
    badge: Color = AppColors.secondaryFixedDim,
    onBadge: Color = AppColors.onSecondaryFixed,
    button: Color = AppColors.primary,
    onButton: Color = AppColors.onPrimary
) = StatsEntryRoles(card, title, subtitle, badge, onBadge, button, onButton)

/**
 * ACCIONES RÁPIDAS del inicio en GRILLA que envuelve (máx. 3 por fila, `FlowRow`). Botones XSmall de
 * M3 Expressive ([QuickActionButton]), todos con el mismo color; las 3 principales (aleatorio, en
 * orden, favoritos) van con `anchor = true` — rectángulo redondeado en vez de píldora. Aleatorio /
 * en orden se deshabilitan si no hay canciones. Todas se ven a la vez (sin scroll lateral).
 *
 * **Sin cabecera de sección** (27 ago 2026): era el único título del inicio que no nombraba un
 * contenido sino una categoría de control, y unos botones con su glifo y su verbo se explican solos.
 * Cuarenta píxeles de la primera pantalla por un rótulo que nadie necesitaba leer.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HomeContentPadding, end = HomeContentPadding, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3
    ) {
        QuickActionButton("shuffle", stringResource(R.string.home_action_shuffle), enabled = canPlayAll, anchor = true, onClick = onShuffleAll)
        QuickActionButton("play_arrow", stringResource(R.string.home_action_play_order), enabled = canPlayAll, fill = true, anchor = true, onClick = onPlayAll)
        QuickActionButton("favorite", stringResource(R.string.home_action_favorites), enabled = hasFavorites, fill = true, anchor = true, onClick = onShuffleFavorites)
        // Acciones por ORIGEN. Solo existen mientras la biblioteca esté partida entre lo que
        // suena sin red y lo que la necesita (misma puerta que los chips de filtro de las
        // listas, ver [LibraryViewModel.hasSourceSplit]): con todo descargado, "Sin conexión"
        // sería un segundo botón de "Aleatorio" y "Sin descargar" no tendría qué reproducir.
        //
        // `offline_pin` es el MISMO glifo con el que la fila de una canción y el chip del
        // reproductor dicen "esto ya está en el dispositivo": tres superficies, un solo signo.
        if (showSourceActions) {
            QuickActionButton("offline_pin", stringResource(R.string.home_action_offline), onClick = onShuffleOffline)
            QuickActionButton("cloud", stringResource(R.string.home_action_not_downloaded), onClick = onShuffleNotDownloaded)
        }
        // Géneros (selección del día). El label es solo el PRIMER segmento del tag
        // ("Progressive Metal, Fusion" → "Progressive Metal"): los tags multi-género en coma hacían
        // píldoras larguísimas —truncadas y solas en su fila, con medio ancho de pantalla vacío al
        // lado (device, 5 sep 2026)—. El tag CRUDO se sigue pasando a `onShuffleGenre`: es la clave
        // de la consulta. El tope de 20 caracteres de [QuickActionButton] sigue cubriendo un primer
        // segmento largo suelto.
        genres.forEach { genre ->
            QuickActionButton(
                "genres",
                label = genre.substringBefore(',').trim(),
                onClick = { onShuffleGenre(genre) }
            )
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

/**
 * Cabecera de sección, con su salida opcional.
 *
 * **"Ver todo" solo donde hay un destino que es la MISMA lista completa**, y hoy eso es una sola
 * sección: "Recién agregadas" → la pestaña Canciones por fecha de alta. Las generadas no lo llevan
 * porque no hay a dónde ir, y el ranking de álbumes —que parecía el candidato obvio— no lo tenía
 * nunca: la pestaña Álbumes es otro conjunto (incluye los que nunca sonaron) en un orden que ni
 * siquiera existe en `AlbumSortOrder`. Un "ver todo" que cambia de lista no es una salida, es un
 * salto.
 */
@Composable
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HomeContentPadding, end = HomeContentPadding, top = 8.dp, bottom = 10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = AppColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // El título se queda con el ancho sobrante y la salida se pega al final. Con un título
            // largo el que se recorta es él, que es lo correcto: "Ver todo" es un control y no se
            // puede leer a medias ("Porque escuchaste a …" sí).
            modifier = Modifier.weight(1f)
        )
        if (onSeeAll != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.primary
                )
                MaterialSymbol("chevron_right", size = 18.sp, color = AppColors.primary)
            }
        }
    }
}

/**
 * Carrusel Expressive de canciones, con el apretón de keylines de [KeylineCarousel].
 *
 * **Sus tarjetas son punta de origen del *container transform* hacia el reproductor**, y con el
 * `HorizontalMultiBrowseCarousel` de M3 arrastraban el desfase de geometría que cuenta el KDoc de
 * [KeylineCarousel]: los bounds de layout no eran los dibujados. Aquí **no llegaba a percibirse** —al
 * cerrar, la superficie aterriza en la fila de la canción que suena AHORA o, si no está en pantalla,
 * en la PÍLDORA (ver el KDoc de `rememberPlayerMorphOrigin`), y con el contenido ya cambiado no hay
 * una vecina al lado que sirva de referencia—, así que esta fila se quedó con el de M3 mientras solo
 * "Porque escuchaste a X" lo necesitaba. Ya no: las tres filas del inicio van con el mismo motor, con
 * el mismo reparto de keylines y el mismo encaje, y ninguna finge su geometría.
 */
@Composable
private fun SongCarousel(
    title: String,
    songs: List<Song>,
    currentSongId: String?,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onSeeAll: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title, onSeeAll)
        val state = rememberPagerState { songs.size }
        KeylineCarousel(
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
            // Cuánto está revelada esta tarjeta; se resuelve en fase de DIBUJO.
            val reveal = revealFraction()
            // La tarjeta ES la punta de origen del morph: crece hasta ser el reproductor, igual que
            // una fila de "Todas", y con la misma maquinaria (`SongRowContainer`). Alrededor de la
            // punta NO hay recorte de carrusel que valga: el carrusel la mide a su ancho visible y la
            // coloca en su sitio, así que sus bounds de layout son los dibujados. Durante el morph la
            // superficie se dibuja en el overlay del `SharedTransitionScope`, que cuelga de la raíz, y
            // por eso puede crecer más allá de su casilla.
            //
            // **Se identifica por el id de CANCIÓN, así que dos tarjetas de la misma canción serían
            // dos orígenes y no morfaría ninguna** (el mismo empate que `SharedTransitionGate`
            // resuelve entre páginas del pager). Estuvo abierto desde el 17 ago 2026, asumido con un
            // "da igual de cuál de las dos salga" que era FALSO; se cerró el 27 ago por construcción,
            // cuando el inicio pasó a tener DOS secciones de canciones y la rotativa empezó a
            // descartar lo que ya sale en "Recién agregadas" (ver el dedup de
            // `LibraryViewModel.homeDiscover`).
            SongRowContainer(
                songId = song.id,
                modifier = Modifier.fillMaxSize()
            ) {
                ArtCarouselCard(
                    art = song.albumArtUri?.toString(),
                    title = song.title,
                    subtitle = song.artist,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlaySongs(songs, i) },
                    // La MISMA curva que las otras dos filas del inicio; ver [labelAlphaFor].
                    labelAlpha = { labelAlphaFor(reveal()) },
                    // La tarjeta se RECORTA A SÍ MISMA, y ahora es el ÚNICO recorte que tiene: sin el
                    // `maskClip` del carrusel de M3 no hay nadie más que le dé su forma. Y aunque lo
                    // hubiera seguiría haciendo falta — mientras vuela, el shared element se dibuja en
                    // el overlay del `SharedTransitionScope`, que cuelga de la raíz y por tanto se
                    // salta los recortes de sus padres. Sin este clip, la superficie que crece salía
                    // con las ESQUINAS RECTAS mientras que la tarjeta parada es un cuadrado
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
 *
 * Sus tarjetas no son punta de ningún shared element —reanudar un contexto no morfea nada—, así que
 * va con [KeylineCarousel] por HOMOGENEIDAD y no por necesidad: es la fila de en medio de tres, y con
 * el carrusel de M3 tenía otro reparto de keylines y otro encaje que las de arriba y abajo.
 */
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
        val state = rememberPagerState { contexts.size }
        KeylineCarousel(
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
            // Cuánto está revelada esta tarjeta; se resuelve en fase de DIBUJO.
            val reveal = revealFraction()
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
            ArtCarouselCard(
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
                // La MISMA curva que las otras dos filas del inicio; ver [labelAlphaFor].
                labelAlpha = { labelAlphaFor(reveal()) },
                // El recorte lo pone la tarjeta: sin el `maskClip` del carrusel de M3 no hay ningún
                // otro que le dé su forma.
                modifier = Modifier.fillMaxSize().clip(HomeCardShape)
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
 * que más escuchas (ver `LibraryViewModel.homeDiscover`).
 *
 * A diferencia de los carruseles de canciones, sus tarjetas **navegan** al detalle del artista en vez
 * de reproducir: la sección es de descubrimiento, y descubrir a alguien es ir a ver qué tiene. Por eso
 * tampoco es punta del *container transform* hacia el reproductor — su transición es la del NavHost,
 * con la foto viajando al header del detalle (misma key que la celda de la pestaña Artistas).
 *
 * **Fue la primera fila que pasó a [KeylineCarousel]**, y la única que lo exigía: es la única cuya
 * tarjeta es punta de un shared element hacia una RUTA, o sea la única donde el vuelo aterriza sobre
 * la tarjeta con todo parado y una vecina al lado de referencia. El carrusel de M3 finge su geometría
 * al dibujar y al colocar, y el lookahead no ve ninguna de las dos, así que la foto aterrizaba a
 * tamaño completo y corrida — todo el porqué está en el KDoc de [KeylineCarousel]. Las otras dos
 * filas del inicio fueron detrás, por homogeneidad: ver el bloque de [SongCarousel].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
        val state = rememberPagerState { artists.size }
        KeylineCarousel(
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
            // Shared element → header del detalle de artista (misma key que la fila de la pestaña
            // Artistas). Aquí la punta NO necesita nada alrededor: la tarjeta se mide a su ancho
            // visible y se coloca en su sitio, así que sus bounds de layout son los dibujados.
            //
            // **Va en el `modifier` de la TARJETA y no en su `artModifier`**, o sea que lo que vuela
            // es la tarjeta entera y no la imagen de dentro. Con la punta en la imagen, al despegar
            // se quedaba en el hueco todo lo demás de `ArtCarouselCard` —su `background`, que se veía
            // como un rectángulo GRIS, y el distintivo flotando— hasta que el vuelo terminaba. Es
            // además lo simétrico: el otro extremo (`ArtistImmersiveHeader`) también declara su punta
            // sobre el `Box` entero, no sobre su `AsyncImage`.
            val sharedCardModifier = entityImageSharedBounds(
                key = "artist_image_${artist.name}",
                shape = HomeCardShape,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
            // Cuánto está revelada esta tarjeta; se resuelve en fase de DIBUJO.
            val reveal = revealFraction()
            ArtCarouselCard(
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
                // La MISMA curva que las otras dos filas del inicio; ver [labelAlphaFor].
                labelAlpha = { labelAlphaFor(reveal()) },
                // El recorte va DENTRO de la punta para que viaje con ella; aquí no hay `maskClip`
                // de carrusel que lo ponga desde fuera, igual que en `RankCarousel`.
                modifier = Modifier
                    .fillMaxSize()
                    .then(sharedCardModifier)
                    .clip(HomeCardShape)
            )
        }
    }
}

/**
 * Por debajo de esta fracción de revelado, una tarjeta NO enseña su etiqueta.
 *
 * En un carrusel de keylines la tarjeta "mediana" ya es bastante grande (fracción ~0,5-0,7), así que
 * una rampa lineal la dejaba **casi legible**, que es el peor de los dos mundos. Con este suelo, y
 * subiendo suave (t²) hasta 1 en el foco, solo la tarjeta en foco dice su nombre — que es lo que hace
 * el inicio, donde se SUGIERE. Donde hay que comparar (el ranking de estadísticas) las tarjetas van a
 * `1f` fijo; ver el KDoc de [com.qhana.siku.ui.components.ArtCarouselCard].
 */
private const val LabelRevealFloor = 0.65f

/**
 * La curva de la etiqueta, **compartida por las tres filas del inicio**. Todas sacan su fracción del
 * mismo sitio (`KeylineItemScope.revealFraction`), pero la función sigue siendo una sola y explícita
 * porque el fallo que evita no es de código repetido: mientras convivieron los dos carruseles, cada
 * uno atenuaba con su propia rampa y se veía — la fila de artistas enseñaba la etiqueta de la mediana
 * al ~40 % mientras las de arriba la ocultaban del todo.
 */
private fun labelAlphaFor(revealFraction: Float): Float {
    val t = ((revealFraction - LabelRevealFloor) / (1f - LabelRevealFloor)).coerceIn(0f, 1f)
    return t * t
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
