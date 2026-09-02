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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
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
import com.qhana.siku.ui.components.rememberSecondaryContentColor
import com.qhana.siku.ui.components.ArtCarouselCard
import com.qhana.siku.ui.components.COLLAGE_MAX_TILES
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.QuickActionButton
import com.qhana.siku.ui.components.TONAL_LAYER_DELTA
import com.qhana.siku.ui.components.rememberTonalLayerColors
import com.qhana.siku.ui.components.formatListeningTime
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.viewmodel.HomeDiscover
import com.qhana.siku.ui.viewmodel.HomeDiscoverKind
import com.qhana.siku.ui.viewmodel.RelatedArtistUi
import com.qhana.siku.ui.viewmodel.HomeStats
import com.qhana.siku.data.model.PlaybackContext
import java.time.LocalTime

// Tarjeta grande de los carruseles Expressive del inicio (item "grande" del multi-browse; los
// medianos/chicos los deriva el propio carrusel). Cuadrada con etiqueta superpuesta.
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
 * Lado del botón circular del chevron. Menor que el distintivo de la izquierda ([StatsBadgeSize]) a
 * propósito: aquél es la identidad de la fila y éste solo su affordance, así que no deben pesar
 * igual.
 */
/**
 * Cuánto se separa el chevron del contenedor de la tarjeta: **el DOBLE del salto tonal normal**.
 *
 * Los 8 puntos de `TONAL_LAYER_DELTA` están calibrados sobre las superficies NEUTRAS de la app —la
 * escala de `surfaceContainer`, donde 8 puntos son todo el recorrido de la escala de contenedores—.
 * Medido a ojo en device dos veces sobre `primaryContainer` crudo (vívido, con el croma dominando la
 * percepción) y las dos el círculo se desvanecía con un solo salto.
 *
 * **Se queda así (1 sep 2026) aunque la tarjeta ya no sea `primaryContainer` crudo** —ahora es una
 * capa derivada (`cardColors` en [StatsEntry]), MENOS saturada, que hereda su croma del fondo
 * neutro de la página— porque el chevron sigue siendo una capa SOBRE otra capa, no sobre el fondo
 * llano: es el
 * mismo caso por el que el indicador activo del toolbar de la biblioteca
 * (`LIBRARY_TOOLBAR_ACTIVE_TONE_DELTA`) también usa el doble. Si en device se ve exagerado ahora que
 * la tarjeta bajó de intensidad, esta es la primera constante a bajar — no se tocó a ciegas porque
 * "menos saturado" no es lo mismo que "vale el mismo delta que una superficie neutra".
 */
private const val StatsChevronToneDelta = TONAL_LAYER_DELTA * 2

private val StatsChevronSize = 40.dp

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
 * Entrada a Estadísticas, con la anatomía de la **píldora de Favoritos** de la pestaña Listas:
 * superficie tonal de lados totalmente redondos, distintivo `Cookie9Sided` de acento a la
 * izquierda, y dos renglones de texto.
 *
 * **Recorrido hasta aquí, para no repetirlo.** Empezó como una tarjeta de 78dp bajo el saludo; se
 * comprimió a un glifo tonal de 48dp al lado del titular y en device "no parecía un botón" (la
 * tarjeta decía *esto lleva a algún sitio* con tres señales —etiqueta, resumen y chevron— y de las
 * tres solo quedaba el relleno); volvió como barra a todo el ancho con etiqueta y chevron, y esa
 * "no parecía M3 Expressive" — con razón, porque era de los pocos controles de la app SIN morph de
 * forma al pulsar y con un alto y un radio escritos a mano en vez de las tallas tabuladas.
 *
 * Lo que lo arregla no es el tamaño sino el IDIOM: una barra a todo el ancho con un chevron pegado
 * al borde es el patrón de una fila de lista, y ninguna cantidad de morph la saca de ahí. La
 * píldora de Favoritos ya resolvió este mismo problema —"un acceso destacado que no es una fila
 * más"— así que esto es su segunda instancia, no un diseño nuevo.
 *
 * **El chevron va en un botón CIRCULAR** y no como glifo suelto: una superficie propia dice "esto
 * se toca" sin gastar una etiqueta. Se probó como píldora vertical (el idiom de "añadir canciones"
 * en la botonera de un detalle) y se descartó en device.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatsEntry(stats: HomeStats, onClick: () -> Unit) {
    // La tarjeta entera se DERIVA del fondo de página (1 sep 2026), no toma `primaryContainer`
    // tal cual: ese rol es, por definición, el contenedor MÁS saturado que tiene la app, y con un
    // estilo de paleta vívido se comía la pantalla — se veía igual de fuerte con Fiel a la carátula
    // y con Vívido, o sea que no era un problema de ESE estilo sino del rol en sí (visto en device,
    // dos capturas comparadas). `role`/`onRole` = `primaryContainer`/`onPrimaryContainer` para que
    // siga siendo la familia PRIMARY —y por tanto distinta de los `QuickActionButton` de abajo, que
    // son `secondaryContainer`, ver más abajo—, pero el TONO sale a una distancia medida del fondo
    // real de la página en vez de heredar el tono que M3 le fija al rol completo.
    val cardColors = rememberTonalLayerColors(
        background = AppColors.surfaceContainer,
        role = AppColors.primaryContainer,
        onRole = AppColors.onPrimaryContainer
    )
    // El fondo sobre el que se apoya el chevron es el contenedor de la FILA, no el de la página.
    //
    // **Y el ROL del que deriva tiene que ser el de esa misma fila** (29 ago 2026): el default de
    // `rememberTonalLayerColors` es `secondaryContainer`, o sea que tomaba su hue y su CROMA —bajos,
    // porque es un contenedor pálido— y solo movía el tono 8 puntos respecto al fondo. Sobre el
    // `secondaryContainer` de antes eso funcionaba porque los cromas coincidían; sobre el fondo real
    // de la fila (ahora [cardColors], antes el `primaryContainer` crudo) un círculo de croma bajo a
    // 8 puntos de tono se desvanece: la separación tonal estaba, pero la de croma jugaba en contra.
    // Pasando el rol de la propia fila, hue y croma coinciden y **lo único que los separa es el
    // tono**, que es justo lo que la regla promete. Regla general: al derivar una capa sobre un
    // contenedor, el rol que se pasa es el de ESE contenedor, no el default.
    val chevronColors = rememberTonalLayerColors(
        background = cardColors.container,
        role = cardColors.container,
        onRole = cardColors.content,
        delta = StatsChevronToneDelta
    )
    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeContentPadding),
        onClick = onClick,
        // Un peldaño por debajo de la píldora completa (`RoundedCornerShape(50)`, que es lo que
        // lleva la de Favoritos): con el alto de esta fila el radio pleno son ~36dp y se comía
        // demasiado los extremos. `extraLarge` son 28 y es TOKEN, no un número a ojo — el MiniPlayer
        // usa este mismo par para conmutar entre píldora y rectángulo redondeado.
        shape = MaterialTheme.shapes.extraLarge,
        // Familia PRIMARY y no `secondaryContainer` (29 ago 2026, visto en device): con el
        // secundario llevaba EXACTAMENTE el mismo color que los `QuickActionButton` de justo
        // debajo, y no se distinguía de ellos. Y no son lo mismo: esto NAVEGA a otra pantalla,
        // aquéllos arrancan reproducción sin moverte de aquí. El acento principal para lo que es un
        // destino, el secundario para las acciones — la jerarquía completa de la zona está en el
        // KDoc de `CountChip`, que es el escalón de más abajo. Ver [cardColors] arriba para por qué
        // ya no es el rol `primaryContainer` crudo.
        color = cardColors.container
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
                shape = MaterialShapes.Cookie9Sided.toShape(),
                // El content color de [cardColors] como CONTENEDOR del distintivo, no el container:
                // sobre la tarjeta esos dos comparten matiz y croma (son el mismo par), así que el
                // distintivo se fundiría con su fondo si usara el mismo lado. El par `content` está,
                // por definición, al otro extremo de tono — que es justo lo que hace falta para que
                // un sello se lea. Su glifo va del color del contenedor, cerrando el par.
                color = cardColors.content,
                modifier = Modifier.size(StatsBadgeSize)
            ) {
                // fillMaxSize: sin él el Box se ciñe al glifo y `Center` no centra nada dentro del
                // distintivo (el mismo detalle que ya mordió en la píldora de Favoritos).
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol(
                        "bar_chart",
                        fill = true,
                        size = 24.sp,
                        color = cardColors.container
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_home_entry_title),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    // El content de [cardColors], el par de su propio container — no un `on-` de
                    // rol fijo, que dejaría de prometer contraste en cuanto el container se recalcula.
                    color = cardColors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (stats.monthPlays > 0) {
                        homeListeningTimeText(stats.monthListenedMs)
                    } else {
                        // Sin nada escuchado no se pinta un "0 min", que no invita a entrar.
                        stringResource(R.string.stats_home_entry_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = rememberSecondaryContentColor(cardColors.content, cardColors.container),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // NO lleva `onClick` propio: la acción es la de la fila entera, y dos objetivos táctiles
            // para el mismo destino serían dos formas de hacer lo mismo a un centímetro. Es un
            // AFFORDANCE, así que va sin semántica propia para que el lector de pantalla anuncie una
            // sola acción (y por eso tampoco le aplica el mínimo táctil de 48dp).
            //
            // Su color se DERIVA del contenedor real de la fila y no de un rol: dos roles de la
            // misma banda tonal pueden fundirse según lo que el estilo de paleta activo haya hecho
            // con esa banda, mientras que fijar el tono a una distancia MEDIDA del fondo da la misma
            // separación en cualquier paleta y en los dos temas. Ver [rememberTonalLayerColors].
            Box(
                modifier = Modifier
                    .size(StatsChevronSize)
                    .clip(CircleShape)
                    .background(chevronColors.container),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("chevron_right", size = 20.sp, color = chevronColors.content)
            }
        }
    }
}

/**
 * ACCIONES RÁPIDAS del inicio en GRILLA que envuelve (máx. 3 por fila, `FlowRow`). Botones tonales
 * XSmall de M3 Expressive ([QuickActionButton]). Las dos primeras (aleatorio / en orden) se
 * deshabilitan si no hay canciones. Todas se ven a la vez (sin scroll lateral).
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
        QuickActionButton("shuffle", stringResource(R.string.home_action_shuffle), enabled = canPlayAll, onClick = onShuffleAll)
        QuickActionButton("play_arrow", stringResource(R.string.home_action_play_order), enabled = canPlayAll, fill = true, onClick = onPlayAll)
        QuickActionButton("favorite", stringResource(R.string.home_action_favorites), enabled = hasFavorites, fill = true, onClick = onShuffleFavorites)
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
        // Géneros (selección del día). El label ES el nombre del género (no un string res).
        genres.forEach { genre ->
            QuickActionButton("genres", label = genre, onClick = { onShuffleGenre(genre) })
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

/** Carrusel Expressive de canciones. Recortado/parallaxeado por el multi-browse. */
@OptIn(ExperimentalMaterial3Api::class)
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
            // **Se identifica por el id de CANCIÓN, así que dos tarjetas de la misma canción serían
            // dos orígenes y no morfaría ninguna** (el mismo empate que `SharedTransitionGate`
            // resuelve entre páginas del pager). Estuvo abierto desde el 17 ago 2026, asumido con un
            // "da igual de cuál de las dos salga" que era FALSO; se cerró el 27 ago por construcción,
            // cuando el inicio pasó a tener DOS secciones de canciones y la rotativa empezó a
            // descartar lo que ya sale en "Recién agregadas" (ver el dedup de
            // `LibraryViewModel.homeDiscover`).
            SongRowContainer(
                songId = song.id,
                modifier = Modifier.maskClip(HomeCardShape).fillMaxSize()
            ) {
                ArtCarouselCard(
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
 * que más escuchas (ver `LibraryViewModel.homeDiscover`).
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
