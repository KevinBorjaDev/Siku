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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
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
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.FilteredEmptyHint
import com.qhana.siku.ui.components.SortChip
import com.qhana.siku.ui.components.SourceFilterChips
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.RoundedPolygonMaskTransformation
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.onContainerColor
import com.qhana.siku.ui.components.rememberListItemShape

/**
 * Máscara cookie de 6 lados COMPARTIDA por todas las filas: una sola instancia (el path
 * unitario se calcula una vez) y un solo cacheKey para el memory cache de Coil.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val CookieMask by lazy {
    RoundedPolygonMaskTransformation(MaterialShapes.Cookie6Sided, cacheKey = "cookie6")
}

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
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.artist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant
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
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSecondaryContainer
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
                // Mismo agrupado que la lista de "Todas": primera/última fila con
                // esquinas pronunciadas, intermedias casi rectas.
                shape = rememberListItemShape(index = index, count = artists.size),
                onClick = { onArtistClick(artist.name) },
                onPlayClick = { onPlayArtist(artist.name) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun ArtistRow(
    artist: ArtistSummary,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // La foto es shared element hacia el header del detalle del artista (misma familia
    // de morph que carátula MiniPlayer→NowPlaying). sharedBounds (no sharedElement):
    // el contenido difiere (thumb chico vs foto grande) y así cross-fadea.
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "artist_image_${artist.name}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else Modifier
    // Mismo lenguaje visual que la lista de "Todas" (contenedor surfaceContainerHigh con
    // esquinas agrupadas, espaciado, tipografía y colores del SongItem), con la foto en
    // forma M3 Expressive en vez de círculo.
    val isDarkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Envoltorio idéntico al de SongItemOptimized: margen lateral + 2dp de gap entre
            // filas, recorte agrupado y fondo de tarjeta.
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            if (artist.thumbUrl != null) {
                val context = LocalContext.current
                val request = remember(artist.thumbUrl) {
                    ImageRequest.Builder(context)
                        .data(artist.thumbUrl)
                        // Thumbnail fijo (patrón de AlbumArt): decode chico y hit de caché
                        // determinista, en vez de decodificar los 250px de Deezer.
                        .size(ComponentConfig.ThumbnailSize)
                        .transformations(CookieMask)
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
                        .background(colorScheme.surfaceContainerHighest, MaterialShapes.Cookie6Sided.toShape()),
                    contentAlignment = Alignment.Center
                ) {
                    MaterialSymbol("artist", color = colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDarkTheme) Color.White else Color(0xFF1A1A1A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val albumsText = pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount)
            val songsText = pluralStringResource(R.plurals.song_count, artist.songCount, artist.songCount)
            Text(
                text = "$albumsText · $songsText",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF666666),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Quick-play de todo el artista. SIN fondo (IconButton estándar); el glifo `play_circle`
        // (disco relleno) ya se lee como botón sin recargar la fila con una píldora. En primary.
        IconButton(
            onClick = onPlayClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(40.dp)
        ) {
            MaterialSymbol(
                "play_circle",
                size = 30.sp,
                color = colorScheme.primary,
                fill = false
            )
        }
    }
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

    Surface(
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
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    stringResource(R.string.artist_photos_metered_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.8f)
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
