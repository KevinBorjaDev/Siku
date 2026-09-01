package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.repository.PlaylistCoverMeta
import com.qhana.siku.ui.components.AppMenuPopup
import com.qhana.siku.ui.components.rememberSecondaryContentColor
import com.qhana.siku.ui.components.GroupedListRow
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.MenuItemIcon
import com.qhana.siku.ui.components.menuItemShapeAt
import com.qhana.siku.ui.components.rememberRowActionColors
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appMenuItemColors
import com.qhana.siku.ui.theme.appTextButtonColors

/**
 * Margen lateral de TODO lo que hay en esta pestaña: la píldora de Favoritos, el encabezado con su
 * acción, y las filas del grupo (que lo traen de `GroupedListRow`, con el mismo valor).
 *
 * Existe como constante porque el borde derecho es el que delata cualquier desajuste: los tres
 * bloques se apilan verticalmente y el ojo los lee como una columna, así que 4dp de diferencia en uno
 * se ven. **Si cambia el de `GroupedListRow`, hay que cambiar este** — es la pareja sincronizada de
 * siempre; no se puede leer de allí porque es el padding interno de un componente compartido por
 * varias pestañas.
 */
private val ListHorizontalMargin = 16.dp

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
        contentPadding = combinedPadding
        // El gap entre filas lo pone GroupedListRow desde el token del spec
        // (`ListItemDefaults.SegmentedGap`, media parte por fila), igual que Todas/Artistas;
        // sumar aquí un `spacedBy` lo duplicaría solo en esta lista.
    ) {
        // Favoritos siempre primero
        item(key = "favorites") {
            FavoritesItem(
                count = favoritesCount,
                onClick = onFavoritesClick
            )
        }

        // Header de sección "Tus listas" + acción de crear. Reemplazó al FAB flotante de la pestaña
        // Listas (que quedaba "colgando") — la creación vive junto al grupo que puebla. Siempre
        // visible (aún sin listas, invita a crear una).
        //
        // **Botón con TEXTO y no un "+" pelado** (17 ago 2026): un glifo suelto al final de un
        // encabezado obliga a deducir qué añade, y en la única pantalla donde crear algo es la acción
        // principal eso es pedir de más. El texto lo dice y de paso le da área táctil de sobra. Se
        // reutiliza `playlist_create_action`, que ya existía como descripción de accesibilidad: al
        // pasar a ser la etiqueta visible, esa `semantics` sobra y se fue.
        item(key = "playlists_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // MISMO margen lateral que las tarjetas de abajo ([ListHorizontalMargin]): el
                    // encabezado y su acción tienen que caer sobre los mismos dos bordes verticales
                    // que Favoritos y el grupo de listas, o la columna se lee torcida. Estuvo en
                    // `start = 20.dp` y el título sobresalía 4dp hacia dentro respecto de las
                    // tarjetas.
                    .padding(
                        start = ListHorizontalMargin,
                        end = ListHorizontalMargin,
                        top = 4.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.playlist_section_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = AppColors.onSurface,
                    // Una línea y elipsis, con `weight` para que sea el TÍTULO el que ceda: hoy es
                    // un literal corto, pero una traducción larga o una pantalla chica lo envolverían
                    // a dos líneas y estirarían el encabezado. Que se recorte el rótulo es correcto;
                    // que se recorte la acción, no.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Tamaño de glifo y separación DERIVADOS de la altura del botón, no literales: el
                // spec de M3 saca los dos de ella (`iconSizeFor` / `iconSpacingFor`), igual que el
                // botón primario del onboarding.
                val headerButtonHeight = ButtonDefaults.MinHeight
                // **TextButton y no `FilledTonalButton`** (19 ago 2026): es la acción de un
                // ENCABEZADO DE SECCIÓN, que en M3 va sin contenedor. Con relleno
                // tonal, esta pantalla tenía TRES superficies `secondaryContainer` a la vez —la
                // píldora de la pestaña activa (navegación), la tarjeta de Favoritos (contenido) y
                // el botón (acción)—, o sea el mismo color diciendo tres cosas distintas y ninguna
                // jerarquía entre ellas. Sin contenedor quedan dos, y cada una conserva su
                // significado.
                //
                // La acción NO pierde alcance: sigue en el encabezado, a la vista, con el mismo
                // área táctil; lo que pierde es la mancha de color con la que competía contra el
                // contenido de la pestaña.
                TextButton(
                    colors = appTextButtonColors(),
                    onClick = onCreatePlaylist,
                    // Shape-morph al presionar, como el resto de botones de la app.
                    shapes = ButtonDefaults.shapes()
                ) {
                    MaterialSymbol(
                        // `playlist_add` y no `add` a secas: el glifo dice QUÉ se añade, que es lo
                        // mismo que hace la etiqueta de al lado. Es el que ya usa "añadir canciones"
                        // en el detalle de lista.
                        "playlist_add",
                        // Sin `color` explícito: lo hereda del `LocalContentColor` que ya provee el
                        // botón —el mismo que usa el `Text` de al lado—. Escrito a mano
                        // (`onSecondaryContainer`) era el valor correcto pero DUPLICADO, así que un
                        // cambio de `colors` en el botón dejaría el glifo con el color viejo y el
                        // texto con el nuevo. Mismo criterio que la flecha del onboarding.
                        size = ButtonDefaults.iconSizeFor(headerButtonHeight).value.sp
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(headerButtonHeight)))
                    Text(
                        text = stringResource(R.string.playlist_create_action),
                        style = MaterialTheme.typography.labelLargeEmphasized
                    )
                }
            }
        }

        // Sin listas propias, la sección quedaba en BLANCO bajo su encabezado — con Favoritos
        // arriba, la pantalla se leía como si algo hubiera fallado al cargar. El mensaje no repite
        // la acción: "Crear lista" está a la vista justo encima, en el encabezado, y un segundo
        // botón a dos dedos del primero es la misma decisión ofrecida dos veces.
        if (playlists.isEmpty()) {
            item(key = "playlists_empty", contentType = "emptyState") {
                NoPlaylistsState()
            }
        }

        // Resto de playlists
        itemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
            val meta = coverMeta[playlist.id]

            PlaylistItem(
                playlist = playlist,
                songCount = meta?.songCount ?: 0,
                arts = meta?.arts.orEmpty(),
                // El reparto de esquinas del grupo lo hace el `SegmentedListItem` de dentro
                // (`ListItemDefaults.segmentedShapes`), que además aporta el morph al presionar.
                index = index,
                count = playlists.size,
                onClick = { onPlaylistClick(playlist.id) },
                onPlay = { onPlayPlaylist(playlist.id) },
                onRename = { playlistToRename = playlist },
                onDelete = { playlistToDelete = playlist },
                modifier = Modifier.animateItem()
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
                TextButton(colors = appTextButtonColors(), onClick = {
                    onDeletePlaylist(playlist.id)
                    playlistToDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = AppColors.error)
                }
            },
            dismissButton = {
                TextButton(colors = appTextButtonColors(), onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * Estado vacío de la sección "Tus listas": glifo, título y una línea que dice para qué sirven.
 *
 * **Sin botón**, al revés que [EmptyPlaylistState] en el detalle de lista. Allí es obligatorio —
 * con la lista vacía no se dibuja la cabecera con sus acciones, así que sin él no habría NINGUNA
 * forma de añadir canciones—; aquí "Crear lista" vive en el encabezado que este bloque tiene
 * inmediatamente encima, y repetirla sería ofrecer la misma decisión dos veces separadas por unos
 * pocos dp.
 *
 * Mismas medidas y roles que los otros estados vacíos de la app (glifo de 64sp en `outline`,
 * título `titleMedium`, apoyo `bodyMedium` en `onSurfaceVariant`): son el lenguaje de "aquí no hay
 * nada" y tienen que verse iguales en toda la biblioteca.
 */
@Composable
private fun NoPlaylistsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ListHorizontalMargin, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MaterialSymbol(
            "queue_music",
            fill = true,
            color = AppColors.outline,
            size = 64.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.playlist_none_created),
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.playlist_none_created_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoritesItem(
    count: Int,
    onClick: () -> Unit
) {
    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            // Grupo propio: margen segmentado + aire antes del grupo de listas (M3 Expressive
            // separa grupos con un gap claro, no con los 2dp internos entre filas).
            .padding(start = ListHorizontalMargin, end = ListHorizontalMargin, bottom = 12.dp),
        onClick = onClick,
        // PÍLDORA (lados totalmente redondos): distingue a Favoritos como acceso especial,
        // separado del grupo segmentado de listas de abajo.
        shape = RoundedCornerShape(percent = 50),
        color = AppColors.secondaryContainer // Emphasis for Favorites
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
            AppSurface(
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = AppColors.primary,
                modifier = Modifier.size(48.dp)
            ) {
                // fillMaxSize: sin él, el Box se ciñe al glifo y `Center` no centra nada dentro
                // del badge de 48dp — el corazón quedaba pegado a la esquina superior izquierda.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol("favorite", fill = true, size = 24.sp, color = AppColors.onPrimary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.common_favorites),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = AppColors.onSecondaryContainer
                )
                Text(
                    text = pluralStringResource(R.plurals.song_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = rememberSecondaryContentColor(
                        AppColors.onSecondaryContainer,
                        AppColors.secondaryContainer
                    )
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
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fila agrupada COMPARTIDA (GroupedListRow es el `SegmentedListItem` de M3): padding, anatomía,
    // gap y morph al presionar salen del spec, los mismos que Artistas. El collage de carátulas va
    // en el slot leading.
    GroupedListRow(
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { PlaylistThumb(arts = arts) },
        headlineContent = {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // SIN fondo (IconButton estándar); el glifo `play_circle` (disco relleno) se lee como
                // botón sin píldora que recargue la fila. `iconButtonColors` para que el disabled
                // (lista vacía) atenúe solo. Shape-morph Expressive al presionar.
                IconButton(
                    onClick = onPlay,
                    enabled = songCount > 0,
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = AppColors.primary),
                    modifier = Modifier.size(40.dp)
                ) {
                    MaterialSymbol("play_circle", size = 30.sp, fill = true)
                }
                Spacer(modifier = Modifier.width(4.dp))
                PlaylistItemMenu(onRename = onRename, onDelete = onDelete)
            }
        }
    )
}

/** Overflow de la fila: renombrar y borrar (el destructivo vive aquí, tras un toque intencional). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistItemMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    // Píldora vertical tonal, MISMO componente y colores que el ⋮ de "Todas"/Artistas
    // (rememberRowActionColors sobre el fondo real de la fila): las listas de navegación comparten
    // trailing. Antes era un IconButton pelado de 24sp sin contenedor, que rompía esa consistencia.
    val colors = rememberRowActionColors(AppColors.contentSurface)
    Box {
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        AppMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                    text = { Text(stringResource(R.string.playlist_rename_title)) },
                    shape = menuItemShapeAt(0, MENU_ITEM_COUNT),
                    leadingIcon = { MenuItemIcon("edit") }
                )
                // El destructivo se tiñe con `error` en los propios slots y NO con
                // `MenuDefaults.itemColors().copy(...)`: `MenuItemColors` expone dos sobrecargas
                // de `copy` que comparten `textColor`/`leadingIconColor`, así que esa llamada no
                // resuelve.
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    text = { Text(stringResource(R.string.common_delete), color = AppColors.error) },
                    shape = menuItemShapeAt(MENU_ITEM_COUNT - 1, MENU_ITEM_COUNT),
                    leadingIcon = { MenuItemIcon("delete", color = AppColors.error) }
                )
            }
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
            0 -> AppSurface(
                color = AppColors.surfaceContainerHigh,
                modifier = Modifier.matchParentSize()
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol("playlist_play", fill = true, size = 24.sp, color = AppColors.primary)
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

/**
 * Entradas del menú de una lista (renombrar, borrar). Las formas de un grupo de menú dependen de su
 * POSICIÓN, así que el número vive en una constante: escribir `leading`/`trailing` a mano fue lo que
 * dejó el item del medio con las esquinas de un extremo cuando entró (y luego salió) "exportar".
 */
private const val MENU_ITEM_COUNT = 2
