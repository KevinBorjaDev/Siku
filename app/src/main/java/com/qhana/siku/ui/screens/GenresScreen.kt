package com.qhana.siku.ui.screens

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.R
import com.qhana.siku.data.local.GenreSummary
import com.qhana.siku.ui.components.AdaptiveGridCells
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.secondaryContentColor
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.QueueOverflowButton
import com.qhana.siku.ui.components.CountChip
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.rememberRowActionColors
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appFilterChipColors

/**
 * Pestaña "Géneros": cuadrícula de 2 columnas. Como un género no tiene carátula propia, cada tile es
 * una forma M3 Expressive (cookie/sunny) rellena con un color de la PALETA del tema, forma y color
 * deterministas por el nombre — así el mismo género sale siempre igual, sin depender de que sus
 * canciones tengan portada y heredando el color dinámico de la app.
 *
 * El toolbar lleva, además del conteo, el chip "géneros compuestos": los tags GENRE reales vienen
 * sucios ("Rock", "Rock/Metal", "Hard Rock") y no hay normalización que no pierda información, así
 * que el usuario decide si al abrir un género entra también lo que lo CONTIENE. Es un ajuste
 * persistido, no un filtro de sesión: cambia también lo que reproducen los chips del inicio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenresScreen(
    genres: List<GenreSummary>,
    onGenreClick: (String) -> Unit,
    onPlayGenre: (String) -> Unit,
    /** Encola TODAS las canciones del género al final de la cola, desde el overflow del tile. */
    onAddGenreToQueue: (String) -> Unit,
    partialMatch: Boolean,
    onPartialMatchChange: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Vacío REAL: no hay ninguna canción con tag de género. No es lo mismo que "no hay
    // resultados" — aquí no hay filtro que aflojar, así que el texto explica de dónde sale el dato.
    if (genres.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                MaterialSymbol(
                    "genres",
                    size = 64.sp,
                    color = AppColors.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.genre_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.genre_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        // Adaptativa CON SUELO de dos columnas, no `GridCells.Adaptive` a secas: en vertical
        // salen siempre las dos de siempre —en cualquier teléfono, no solo en los de 393dp— y solo
        // se añaden columnas cuando la ventana da de sobra. Ver [AdaptiveGridCells].
        columns = AdaptiveGridCells(ComponentConfig.GridTileMinWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // Se SUMA lo que llega en vez de descartarlo: si el contenedor inyecta un inset lateral
            // (la perforación en horizontal), un 16 fijo lo tiraría. Es lo que ya hace PlaylistList.
            start = 16.dp + contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = 16.dp + contentPadding.calculateEndPadding(LocalLayoutDirection.current),
            // El top que llega incluye el alto del header (el contenido pasa por debajo de
            // TopBar+tabs): NO descartarlo o la cuadrícula nace tapada por las tabs.
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "genre_toolbar", span = { GridItemSpan(maxLineSpan) }, contentType = "listToolbar") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Centrar cada fila: el CountChip de conteo es más alto que los demás chips.
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CountChip {
                    Text(
                        text = pluralStringResource(R.plurals.genre_count, genres.size, genres.size),
                    )
                }
                FilterChip(
                    colors = appFilterChipColors(),
                    selected = partialMatch,
                    onClick = { onPartialMatchChange(!partialMatch) },
                    label = { Text(stringResource(R.string.genre_partial_match)) },
                    leadingIcon = if (partialMatch) {
                        { MaterialSymbol("check", size = 18.sp) }
                    } else null
                )
            }
        }
        items(genres, key = { it.name }) { genre ->
            GenreTileCard(
                genre = genre,
                modifier = Modifier.animateItem(),
                onClick = { onGenreClick(genre.name) },
                onPlayClick = { onPlayGenre(genre.name) },
                onAddToQueue = { onAddGenreToQueue(genre.name) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

/**
 * Tile de género: forma M3 Expressive (cookie/sunny) rellena con un par `x`/`onX` del tema, ambos
 * deterministas por el nombre, con el nombre, el conteo y el play CENTRADOS encima. La forma + el
 * color propio es lo que da identidad a cada género sin depender de que sus canciones tengan
 * carátula, y lo distingue de un vistazo del tile cuadrado de un álbum.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GenreTileCard(
    genre: GenreSummary,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // SIN Card detrás: la forma ES el tile. Una superficie rectangular por debajo asomaría por las
    // esquinas —que la forma no llena— y anularía justo la silueta que le da identidad al género.
    val shape = genreShape(genre.name).toShape()

    // La MISMA silueta viaja con la punta: el `clip` de abajo la pone en reposo (y recorta a los
    // hijos), pero mientras vuela el shared element se dibuja en el overlay del
    // `SharedTransitionScope`, fuera de esos recortes. Ver [entityImageSharedBounds].
    val sharedModifier = entityImageSharedBounds(
        key = "genre_image_${genre.name}",
        shape = shape,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val (containerColor, onColor) = genrePalette(genre.name)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(sharedModifier)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Contenido CENTRADO, dentro de la zona sólida de la forma: los festones muerden el borde y
        // el centro queda limpio sea cual sea la forma sorteada. El play estuvo en `BottomEnd` y
        // era INVISIBLE — el `clip` recorta también a los hijos, y en una cookie esa esquina está
        // vacía, así que el botón caía entero fuera de la silueta.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = onColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = pluralStringResource(R.plurals.song_count, genre.songCount, genre.songCount),
                style = MaterialTheme.typography.labelSmall,
                color = secondaryContentColor(onColor, containerColor),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Play + overflow, los dos DENTRO de la zona sólida de la forma (por eso van en la
            // columna centrada y no en una esquina: el `clip` del tile recorta a los hijos y en una
            // cookie las esquinas están vacías — ver la nota de arriba, que ya costó un botón
            // invisible una vez).
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPlayClick,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(40.dp)
                ) {
                    MaterialSymbol("play_circle", size = 32.sp, color = onColor, fill = true)
                }
                Spacer(modifier = Modifier.width(4.dp))
                // Colores derivados del color REAL del tile (que cambia con cada género y con la
                // paleta del tema), no de un rol fijo: sobre un tile que ya ES `primary` una
                // píldora `secondaryContainer` podría desaparecer.
                QueueOverflowButton(
                    onAddToQueue = onAddToQueue,
                    colors = rememberRowActionColors(containerColor),
                    contentDescription = stringResource(R.string.common_genre_options),
                    menuLabel = stringResource(R.string.detail_add_all_to_queue)
                )
            }
        }
    }
}

/**
 * Constantes de avalancha de `lowbias32` (variante de `fmix32`/MurmurHash3). No son un número
 * mágico elegible: son los multiplicadores con los que ese finalizador consigue que cambiar UN bit
 * de la entrada altere la mitad de los bits de la salida.
 */
private const val MIX_MULTIPLIER_A = 0x7feb352d
private val MIX_MULTIPLIER_B = 0x846ca68b.toInt()

/** Sales para derivar forma y color de la misma semilla sin que queden correlacionadas. */
private const val SHAPE_SALT = 0x5bf03635
private const val COLOR_SALT = 0x2f1e9d43

/**
 * Finalizador de avalancha. Es lo que hace que el reparto se vea ALEATORIO en vez de por orden.
 *
 * El defecto MEDIDO del `% n` directo sobre `String.hashCode`: el ÚLTIMO carácter entra con peso 1,
 * así que nombres con el mismo prefijo que difieren en el final marchan por las formas en fila —
 * `Rocka..Rocke` daban 3, 4, 0, 1, 2, un +1 por letra, que es justo el patrón que se veía en la
 * cuadrícula. Con la mezcla dan 3, 2, 0, 4, 0.
 *
 * OJO con lo que NO era: el reparto agregado del hash crudo ya estaba bien (χ²=2.75 sobre 40
 * géneros reales, contra 4.00 del mezclado — ninguno de los dos está sesgado). Esto no se hace por
 * uniformidad, sino contra la marcha secuencial y la correlación de [genreShape].
 */
private fun mix32(seed: Int): Int {
    var h = seed
    h = h xor (h ushr 16)
    h *= MIX_MULTIPLIER_A
    h = h xor (h ushr 15)
    h *= MIX_MULTIPLIER_B
    h = h xor (h ushr 16)
    return h
}

/**
 * Semilla del género: hash ESTABLE del nombre normalizado (`String.hashCode` es determinista por
 * especificación) ya mezclado. Pseudoaleatorio pero REPRODUCIBLE — "Rock" sale siempre igual entre
 * sesiones y dispositivos, que es lo que un `Random` de verdad no daría (cambiaría la forma en cada
 * recomposición, parpadeando al hacer scroll).
 */
private fun genreSeed(name: String): Int = mix32(name.trim().lowercase().hashCode())

/** Número de pares de [genrePalette]. Coprimo con `GENRE_SHAPES.size` (5), que ayuda a que forma y
 *  color no vuelvan a caer en fase aunque alguien toque las sales. */
private const val GENRE_PALETTE_COUNT = 6

/**
 * Color del tile y su color de contenido, sacados de la PALETA del tema (que a su vez se siembra de
 * la carátula): así los géneros heredan el color dinámico y el `PaletteStyle` elegido en Ajustes, en
 * vez de un HSV inventado que se vería igual con cualquier tema. El contraste no se calcula — lo
 * garantiza el par `x`/`onX` de M3.
 *
 * Solo entran roles que GIRAN con el tema. Los `xFixed` quedaron fuera a propósito: valen T90 en
 * claro y en oscuro (existen justo para no cambiar), así que mezclados con los `xContainer` —T90 en
 * claro pero T30 en oscuro— habrían dejado en tema oscuro media cuadrícula de tiles claros y media
 * de oscuros.
 *
 * Se lee en cada llamada, sin `remember`: los colores de esta app cambian con la carátula, y desde
 * el 20 ago 2026 lo hacen MUTANDO la misma instancia ([AppColorScheme]), así que cachear por
 * instancia serviría colores viejos para siempre. (La frase anterior decía esto mismo de
 * `ColorScheme`, donde era falso: ahí cada cambio construía una instancia nueva. Ahora es cierto.)
 */
@Composable
private fun genrePalette(name: String): Pair<Color, Color> {
    val cs = LocalAppColors.current
    return when (Math.floorMod(mix32(genreSeed(name) xor COLOR_SALT), GENRE_PALETTE_COUNT)) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        2 -> cs.tertiaryContainer to cs.onTertiaryContainer
        3 -> cs.primary to cs.onPrimary
        4 -> cs.secondary to cs.onSecondary
        else -> cs.tertiary to cs.onTertiary
    }
}

/**
 * Formas M3 Expressive candidatas para los tiles de género: convexas/redondeadas a propósito porque
 * el nombre va CENTRADO encima (las de concavidad profunda —tréboles— se lo comerían). Todas están
 * verificadas en uso en la app (NowPlaying / Artistas).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val GENRE_SHAPES: List<RoundedPolygon> = listOf(
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie6Sided
)

/**
 * Forma determinista del género. Se RE-MEZCLA con [SHAPE_SALT] en vez de reusar [genreSeed] tal
 * cual: como `GENRE_SHAPES.size` divide a 360, `seed % 5` quedaría determinado por `seed % 360`, o
 * sea la forma sería una función del color y dos géneros de matiz parecido saldrían siempre con la
 * misma silueta.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun genreShape(name: String): RoundedPolygon =
    GENRE_SHAPES[Math.floorMod(mix32(genreSeed(name) xor SHAPE_SALT), GENRE_SHAPES.size)]
