package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.repository.PlaylistCoverMeta
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.rememberListItemShape

/**
 * Pestaña Listas del home: Favoritos fijo arriba (contenedor de acento con corazón) y las
 * listas del usuario como filas segmentadas con thumbnail de COLLAGE (carátulas reales),
 * conteo de canciones y overflow — el borrado vive en el menú, no como botón rojo permanente
 * (un destructivo siempre a un toque era ruido y riesgo).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistList(
    playlists: List<Playlist>,
    favoritesCount: Int,
    coverMeta: Map<Long, PlaylistCoverMeta>,
    onPlaylistClick: (Long) -> Unit,
    onPlayPlaylist: (Long) -> Unit,
    onFavoritesClick: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }

    // El margen HORIZONTAL lo ponen las filas (16dp segmentado, como en las demás pestañas);
    // aquí solo vertical — sumarlo también al contenedor duplicaba el margen lateral (32dp).
    val combinedPadding = PaddingValues(
        start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        top = 16.dp + contentPadding.calculateTopPadding(),
        end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        bottom = 16.dp + contentPadding.calculateBottomPadding()
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = combinedPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp) // Reduced spacing for connected look
    ) {
        // Favoritos siempre primero
        item(key = "favorites") {
            FavoritesItem(
                count = favoritesCount,
                onClick = onFavoritesClick
            )
        }

        // Header de sección "Tus listas" + acción de crear ("+" tonal). Reemplazó al FAB
        // flotante de la pestaña Listas (que quedaba "colgando") — la creación ahora vive
        // junto al grupo que puebla. Siempre visible (aún sin listas, invita a crear una).
        item(key = "playlists_header") {
            val createDesc = stringResource(R.string.playlist_create_action)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.playlist_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = onCreatePlaylist,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = createDesc }
                ) {
                    MaterialSymbol("add", size = 22.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        // Resto de playlists
        itemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
            val shape = rememberListItemShape(index = index, count = playlists.size)
            val meta = coverMeta[playlist.id]

            PlaylistItem(
                playlist = playlist,
                songCount = meta?.songCount ?: 0,
                arts = meta?.arts.orEmpty(),
                shape = shape,
                onClick = { onPlaylistClick(playlist.id) },
                onPlay = { onPlayPlaylist(playlist.id) },
                onRename = { playlistToRename = playlist },
                onDelete = { playlistToDelete = playlist }
            )
        }
    }

    playlistToRename?.let { playlist ->
        com.qhana.siku.ui.components.RenamePlaylistDialog(
            currentName = playlist.name,
            onDismiss = { playlistToRename = null },
            onConfirm = { newName ->
                onRenamePlaylist(playlist.id, newName)
                playlistToRename = null
            }
        )
    }

    // Diálogo de confirmación para eliminar playlist
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(playlist.id)
                    playlistToDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoritesItem(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Grupo propio: margen segmentado + aire antes del grupo de listas (M3 Expressive
            // separa grupos con un gap claro, no con los 2dp internos entre filas).
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        onClick = onClick,
        // PÍLDORA (lados totalmente redondos): distingue a Favoritos como acceso especial,
        // separado del grupo segmentado de listas de abajo.
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.secondaryContainer // Emphasis for Favorites
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Un poco más de aire lateral: con lados de píldora, el contenido a 16dp
                // quedaba pegado a la curva.
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Corazón sobre badge con forma M3 Expressive (MaterialShapes): acento dentro
            // de la píldora tonal, misma familia que el shape reveal del NowPlaying.
            Surface(
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MaterialSymbol("favorite", fill = true, size = 24.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.common_favorites),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = pluralStringResource(R.plurals.song_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistItem(
    playlist: Playlist,
    songCount: Int,
    arts: List<String>,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp), // Segmented margins
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow // Standard list item color
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistThumb(arts = arts)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // SIN fondo (IconButton estándar); el glifo `play_circle` (disco relleno) se lee como
            // botón sin píldora que recargue la fila. `iconButtonColors` para que el disabled
            // (lista vacía) atenúe solo. Shape-morph Expressive al presionar.
            IconButton(
                onClick = onPlay,
                enabled = songCount > 0,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(40.dp)
            ) {
                MaterialSymbol("play_circle", size = 30.sp, fill = false)
            }
            Spacer(modifier = Modifier.width(4.dp))
            PlaylistItemMenu(onRename = onRename, onDelete = onDelete)
        }
    }
}

/** Overflow de la fila: renombrar y borrar (el destructivo vive aquí, tras un toque intencional). */
@Composable
private fun PlaylistItemMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            MaterialSymbol("more_vert", color = MaterialTheme.colorScheme.onSurfaceVariant, size = 24.sp)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_rename_title)) },
                leadingIcon = { MaterialSymbol("edit") },
                onClick = {
                    showMenu = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { MaterialSymbol("delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * Thumbnail de 56dp con collage ADAPTATIVO de carátulas reales (misma progresión que el
 * header del detalle): 1 completa, 2 mitades, 3 grande+2, 4 mosaico. Sin carátulas, icono
 * sobre contenedor tonal (el estado de siempre).
 */
@Composable
private fun PlaylistThumb(arts: List<String>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        when (arts.size) {
            0 -> Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.matchParentSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MaterialSymbol("playlist_play", fill = true, size = 24.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            1 -> ThumbTile(arts[0], Modifier.matchParentSize())
            2 -> Row(modifier = Modifier.matchParentSize()) {
                ThumbTile(arts[0], Modifier.weight(1f))
                ThumbTile(arts[1], Modifier.weight(1f))
            }
            3 -> Row(modifier = Modifier.matchParentSize()) {
                ThumbTile(arts[0], Modifier.weight(1f))
                Column(modifier = Modifier.weight(1f)) {
                    ThumbTile(arts[1], Modifier.weight(1f).fillMaxWidth())
                    ThumbTile(arts[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(modifier = Modifier.matchParentSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    ThumbTile(arts[0], Modifier.weight(1f))
                    ThumbTile(arts[1], Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f)) {
                    ThumbTile(arts[2], Modifier.weight(1f))
                    ThumbTile(arts[3], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThumbTile(art: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = art,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxHeight()
    )
}
