package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import coil3.request.transformations
import com.qhana.siku.R
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.ui.components.ACCENT_SECONDARY_ALPHA
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.FilteredEmptyHint
import com.qhana.siku.ui.components.GroupedListRow
import com.qhana.siku.ui.components.entityImageSharedBounds
import com.qhana.siku.ui.components.SortChip
import com.qhana.siku.ui.components.SourceFilterChips
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.QueueOverflowButton
import com.qhana.siku.ui.components.RoundedPolygonMaskTransformation
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.onContainerColor
import com.qhana.siku.ui.components.rememberRowActionColors
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface

/**
 * Máscara COMPARTIDA por todas las filas: una sola instancia (el path unitario se calcula una vez)
 * y un solo cacheKey para el memory cache de Coil.
 *
 * La forma es **`MaterialShapes.Cookie7Sided`** desde el 21 ago 2026; antes fue un heptágono propio
 * (`HeptagonShape`, eliminado al quedarse sin consumidores) y, un rato ese mismo día, `Gem`.
 *
 * **Sale de [ArtistPhotoShape] y de ningún otro sitio.** La forma de esta foto vive en TRES
 * consumidores —esta máscara, la silueta que se declara para el vuelo del shared element, y el
 * fondo del placeholder— y con la forma escrita a mano en cada uno es posible cambiar unos y no
 * otros. Pasó: al pasar de `Gem` a `Cookie7Sided` se cambió solo la máscara, así que el bitmap
 * llevaba una cookie horneada mientras el overlay del vuelo lo recortaba con una gema. El síntoma
 * no parecía de forma sino de MOVIMIENTO —al volver del detalle la imagen se contraía hacia
 * adentro—, porque lo que se veía era una silueta cerrándose sobre otra distinta.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ArtistPhotoShape: RoundedPolygon get() = MaterialShapes.Cookie7Sided

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ArtistPhotoMask by lazy {
    RoundedPolygonMaskTransformation(ArtistPhotoShape, cacheKey = ARTIST_PHOTO_CACHE_KEY)
}

/**
 * Identifica la TRANSFORMACIÓN dentro de la clave del memory cache de Coil. **Cambiar la forma
 * obliga a cambiar esto**, o los recortes ya cacheados con la forma vieja se sirven como si fueran
 * los nuevos.
 */
private const val ARTIST_PHOTO_CACHE_KEY = "cookie7"

/**
 * Pestaña "Artistas": lista con foto Deezer, nombre y contadores. Las fotos NO se fetchean
 * desde aquí: las resuelve el backfill en background de [ArtistImageRepository] (init de
 * BrowseViewModel + fin de cada sync); esta lista solo pinta lo que haya en la BD.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistsScreen(
    artists: List<ArtistSummary>,
    onArtistClick: (String) -> Unit,
    onPlayArtist: (String) -> Unit,
    /** Encola TODAS las canciones del artista al final de la cola, desde el overflow de la fila. */
    onAddArtistToQueue: (String) -> Unit,
    contentPadding: PaddingValues,
    sortOrder: ArtistSortOrder,
    onSortOrderChange: (ArtistSortOrder) -> Unit,
    sourceFilters: Set<SongSourceFilter>,
    onToggleSourceFilter: (SongSourceFilter) -> Unit,
    showLocalChip: Boolean,
    showCloudChips: Boolean,
    modifier: Modifier = Modifier,
    /** Backfill de fotos en pausa por red móvil: muestra el banner con la decisión. */
    meteredBannerVisible: Boolean = false,
    onDownloadOnMobile: () -> Unit = {},
    onDismissMeteredBanner: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Solo el vacío REAL (biblioteca sin artistas) sale por aquí. Con un filtro de origen
    // activo, un resultado vacío conserva el toolbar (si no, no habría cómo quitar el filtro).
    if (artists.isEmpty() && sourceFilters.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaterialSymbol(
                    "artist",
                    size = 64.sp,
                    color = AppColors.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.artist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        // Toolbar de la lista: conteo + orden + chips de origen, mismo FlowRow (envuelve, sin
        // scroll horizontal) que la pestaña Todas (SongsScreen.SourceFilterRow). Conteo filtrado.
        item(key = "artist_toolbar", contentType = "listToolbar") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Centrar cada fila: el TonalChip de conteo es más alto que los demás chips.
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp)
            ) {
                TonalChip {
                    Text(
                        text = pluralStringResource(R.plurals.artist_count, artists.size, artists.size),
                        color = AppColors.onSecondaryContainer
                    )
                }
                SortChip(
                    current = sortOrder,
                    options = listOf(
                        R.string.sort_name_asc to ArtistSortOrder.NAME,
                        R.string.sort_song_count to ArtistSortOrder.SONG_COUNT,
                        R.string.sort_recent_first to ArtistSortOrder.RECENTLY_ADDED
                    ),
                    onChange = onSortOrderChange
                )
                SourceFilterChips(sourceFilters, showLocalChip, showCloudChips, onToggleSourceFilter)
            }
        }
        // Filtro de origen sin resultados: aviso que conserva el toolbar (para poder quitarlo).
        if (artists.isEmpty()) {
            item(key = "artist_filtered_empty") { FilteredEmptyHint() }
        }
        if (meteredBannerVisible) {
            item(key = "metered_banner") {
                ArtistPhotosMeteredBanner(
                    onDownload = onDownloadOnMobile,
                    onDismiss = onDismissMeteredBanner
                )
            }
        }
        itemsIndexed(
            artists,
            key = { _, artist -> artist.name },
            // contentType homogéneo (como "song" en la lista de Todas): permite a Lazy
            // reutilizar nodos de composición entre filas al scrollear.
            contentType = { _, _ -> "artist" }
        ) { index, artist ->
            ArtistRow(
                artist = artist,
                modifier = Modifier.animateItem(),
                // Mismo agrupado que la lista de "Todas": primera/última fila con
                // esquinas pronunciadas, intermedias casi rectas. El reparto lo hace el
                // `SegmentedListItem` de dentro (`ListItemDefaults.segmentedShapes`).
                index = index,
                count = artists.size,
                onClick = { onArtistClick(artist.name) },
                onPlayClick = { onPlayArtist(artist.name) },
                onAddToQueue = { onAddArtistToQueue(artist.name) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

/**
 * Key de caché en memoria de la foto de un artista, ESTABLE por nombre. La comparten la fila de la
 * pestaña Artistas (que la escribe con [ImageRequest.Builder.memoryCacheKey]) y el header del
 * detalle (que la lee como `placeholderMemoryCacheKey`), para que pasar de la lista al detalle no
 * dispare una nueva descarga de la foto. Por NOMBRE y no por URL porque las dos superficies usan
 * resoluciones distintas de Deezer (thumb `pictureMedium` vs `pictureXl`); al refrescar la foto la
 * fila reescribe el bitmap bajo la misma key, así que el placeholder nunca se queda viejo.
 */
internal fun artistPhotoCacheKey(name: String): String = "artist_photo_$name"

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ArtistRow(
    artist: ArtistSummary,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // La foto es shared element hacia el header del detalle del artista (misma familia
    // de morph que carátula MiniPlayer→NowPlaying). sharedBounds (no sharedElement):
    // el contenido difiere (thumb chico vs foto grande) y así cross-fadea.
    // La silueta se declara para el vuelo aunque el bitmap ya la traiga HORNEADA (ver
    // [ArtistPhotoMask]): el placeholder no la lleva, y el overlay del `SharedTransitionScope` se
    // salta los recortes de los padres, así que sin esto la punta despega como un cuadrado.
    // Sin `remember` alrededor: `RoundedPolygon.toShape()` es `@Composable` (no una función normal)
    // y ya cachea por dentro con `remember(this, startAngle)`, así que envolverlo era a la vez
    // ilegal —invocar un composable desde la lambda de `remember`— y redundante.
    val photoShape = ArtistPhotoShape.toShape()
    val sharedModifier = entityImageSharedBounds(
        key = "artist_image_${artist.name}",
        shape = photoShape,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    // Fila agrupada COMPARTIDA (GroupedListRow es el `SegmentedListItem` de M3): padding, anatomía,
    // gap y morph al presionar salen del spec, los mismos que Listas — ya no es un Row copiado a
    // mano. La foto va en el slot leading con forma M3 Expressive en vez de círculo.
    GroupedListRow(
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
        leadingContent = {
            // Sin clip en la fila: la forma cookie va HORNEADA en el bitmap (transformación de
            // Coil, cacheada) — el clip de un Path arbitrario por fila se pagaba en cada frame
            // del scroll y era el jank de esta pestaña. El placeholder (pocas filas) dibuja la
            // forma como background fill, que es más barato que clipear una capa.
            Box(
                modifier = Modifier
                    .size(ComponentConfig.SongItemIconSize)
                    .then(sharedModifier),
                contentAlignment = Alignment.Center
            ) {
                // Misma cascada que el detalle: foto del artista → carátula de alguno de sus álbumes
                // → placeholder. Sin el paso intermedio, decir "ninguno de estos" en el picker dejaba
                // la fila con el icono genérico, que se lee como un fallo de carga y no como la
                // decisión que fue.
                val artistArt = artist.thumbUrl ?: artist.fallbackArtUri
                if (artistArt != null) {
                    val context = LocalContext.current
                    val request = remember(artistArt, artist.name) {
                        ImageRequest.Builder(context)
                            .data(artistArt)
                            // Thumbnail fijo (patrón de AlbumArt): decode chico y hit de caché
                            // determinista, en vez de decodificar los 250px de Deezer.
                            .size(ComponentConfig.ThumbnailSize)
                            .transformations(ArtistPhotoMask)
                            // Key estable por artista: el header del detalle la reusa como
                            // placeholder, así al abrir muestra al instante ESTE thumb ya decodificado
                            // en vez de parpadear bajando la foto grande. Ver [artistPhotoCacheKey].
                            .memoryCacheKey(artistPhotoCacheKey(artist.name))
                            .crossfade(200)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AppColors.surfaceContainerHighest, photoShape),
                        contentAlignment = Alignment.Center
                    ) {
                        MaterialSymbol("artist", color = AppColors.onSurfaceVariant)
                    }
                }
            }
        },
        headlineContent = {
            Text(
                text = artist.name.ifBlank { stringResource(R.string.common_unknown_artist) },
                // Mismo rol que el headline de `SongItem` en reposo.
                style = MaterialTheme.typography.bodyLargeEmphasized,
                // Roles del tema, NO grises fijos: hasta el 10 ago 2026 esto era
                // `Color.White`/`0xFF1A1A1A` y el subtítulo `0xFFB3B3B3`/`0xFF666666`, o sea la
                // única lista de la app cuyos labels no se enteraban del color dinámico —
                // `onSurface`/`onSurfaceVariant` llevan el tinte del seed de la carátula y esos
                // hexes no, así que la pestaña Artistas se veía gris al lado de Todas y Álbumes.
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val albumsText = pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount)
            val songsText = pluralStringResource(R.plurals.song_count, artist.songCount, artist.songCount)
            Text(
                text = "$albumsText · $songsText",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Quick-play de todo el artista. SIN fondo (IconButton estándar); el glifo
                // `play_circle` (disco relleno) ya se lee como botón sin recargar la fila con una
                // píldora. En primary.
                IconButton(
                    onClick = onPlayClick,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(40.dp)
                ) {
                    MaterialSymbol(
                        "play_circle",
                        size = 30.sp,
                        color = AppColors.primary,
                        fill = true
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Overflow: encolar el artista entero. Misma píldora vertical que el ⋮ de las filas
                // de canción y con los colores derivados del fondo REAL de esta fila, para que las
                // dos listas se lean igual. Va en el menú y no como cuarto control suelto porque
                // encolar es una acción de segundo orden frente a "reproducir".
                QueueOverflowButton(
                    onAddToQueue = onAddToQueue,
                    colors = rememberRowActionColors(AppColors.contentSurface),
                    contentDescription = stringResource(R.string.common_artist_options),
                    menuLabel = stringResource(R.string.detail_add_all_to_queue)
                )
            }
        }
    )
}

/**
 * Aviso de backfill de fotos en pausa por red medida, con la decisión en manos del usuario.
 * Mismo lenguaje visual ámbar que DownloadStateBanner (el aviso de descargas pausadas).
 */
@Composable
private fun ArtistPhotosMeteredBanner(
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF2A2016) else Color(0xFFFFF3E0)
    val accent = if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)

    AppSurface(
        color = container,
        contentColor = accent,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSurface(shape = CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    MaterialSymbol(
                        "signal_cellular_alt",
                        color = onContainerColor(accent),
                        size = 20.sp,
                        fill = true
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.artist_photos_metered_title),
                    style = MaterialTheme.typography.titleSmallEmphasized
                )
                Text(
                    stringResource(R.string.artist_photos_metered_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = ACCENT_SECONDARY_ALPHA)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = accent)
                    ) {
                        Text(stringResource(R.string.artist_photos_metered_not_now))
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onDownload,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = onContainerColor(accent)
                        )
                    ) {
                        Text(stringResource(R.string.artist_photos_metered_download))
                    }
                }
            }
        }
    }
}
