package com.qhana.siku.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.playlist.M3uFormat
import com.qhana.siku.ui.components.AppModalSheet
import com.qhana.siku.ui.components.LocalSheetCloser
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appListItemColors
import com.qhana.siku.ui.theme.appOutlinedButtonBorder
import com.qhana.siku.ui.theme.appOutlinedButtonColors

/**
 * Bloque de **Ajustes → Copia de seguridad** para las listas en M3U: importar un archivo de otro
 * reproductor y exportar una lista propia a uno.
 *
 * **Vive en Ajustes y no en la pestaña Listas** (29 ago 2026, decisión del usuario). Nació allí
 * —importar como botón del encabezado "Tus listas", exportar en el ⋮ de cada fila— porque así cada
 * acción quedaba pegada a su objeto, pero es una operación de TRASIEGO de archivos, de la misma
 * familia que la copia de seguridad de al lado, y como tal se busca en Ajustes. En la pestaña
 * costaba además un botón permanente en el encabezado y una entrada en el menú de CADA lista, para
 * algo que se usa una vez cada muchos meses.
 *
 * Consecuencia buscada: exportar ya no sabe de qué lista se trata por el sitio desde el que se
 * tocó, así que hay que preguntarlo — de ahí [ExportPlaylistSheet], que es también donde Favoritos
 * deja de ser un caso aparte (aquí es una fila más de la misma lista, como ya lo es en la capa de
 * datos).
 */
@Composable
internal fun M3uPlaylistsBlock(
    playlists: List<Playlist>,
    onImport: (Uri) -> Unit,
    onExportPlaylist: (Long, Uri) -> Unit,
    onExportFavorites: (Uri) -> Unit
) {
    val context = LocalContext.current

    // Qué se está exportando: el selector es asíncrono, así que hay que recordar para qué se abrió.
    // Es un TIPO y no el id de la lista porque Favoritos no tiene uno a mano —su playlist se
    // resuelve por UUID en la capa de datos—, y un centinela (`-1`) sería un id que no lo es. Se
    // limpia SIEMPRE al volver, también si el usuario cancela, o el siguiente export heredaría el
    // destino del anterior.
    var exportTarget by remember { mutableStateOf<ExportTarget?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }

    // Los dos selectores del SISTEMA. Es el único camino: un `.m3u` no es audio, así que MediaStore
    // no lo indexa y `READ_MEDIA_AUDIO` no llega a él; el selector, en cambio, funciona igual en
    // modo dispositivo y en modo carpetas, y no pide ningún permiso.
    val importPicker = rememberLauncherForActivityResult(
        // `OpenDocument` y no `GetContent`: da un permiso de lectura sobre ESE documento y respeta
        // el proveedor que el usuario elija (Drive, Descargas, una tarjeta SD).
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(M3uFormat.MIME_TYPE)
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null) {
            when (target) {
                is ExportTarget.UserPlaylist -> onExportPlaylist(target.id, uri)
                ExportTarget.Favorites -> onExportFavorites(uri)
                null -> Unit
            }
        }
    }

    AppSurface(
        shape = RoundedCornerShape(M3uBlockCorner),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_m3u_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_m3u_desc),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            // Mismo reparto que el bloque de OneDrive de encima: los dos botones se llevan el ancho
            // por `weight` y sus etiquetas son el VERBO a secas. QUÉ se importa o exporta ya lo dice
            // la descripción, que es su sitio.
            val buttonHeight = ButtonDefaults.MinHeight
            Row {
                Button(
                    colors = appButtonColors(),
                    onClick = { importPicker.launch(M3U_PICKER_MIME_TYPES) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    MaterialSymbol(
                        "folder_open",
                        size = ButtonDefaults.iconSizeFor(buttonHeight).value.sp
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                    Text(
                        text = stringResource(R.string.playlist_import_action),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    colors = appOutlinedButtonColors(),
                    border = appOutlinedButtonBorder(),
                    onClick = { showExportSheet = true },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.playlist_export_action),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showExportSheet) {
        ExportPlaylistSheet(
            playlists = playlists,
            onDismiss = { showExportSheet = false },
            onPick = { target, suggestedName ->
                showExportSheet = false
                exportTarget = target
                exportPicker.launch(
                    context.getString(R.string.playlist_export_file_name, suggestedName)
                )
            }
        )
    }
}

/**
 * Qué lista exportar. Favoritos va PRIMERO y fuera del grupo, como en la pestaña Listas: es la
 * única que existe siempre, y separarla evita que se lea como una lista más del usuario.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportPlaylistSheet(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (ExportTarget, String) -> Unit
) {
    val favoritesLabel = stringResource(R.string.common_favorites)
    AppModalSheet(onDismissRequest = onDismiss) {
        // Elegir cierra la hoja desde dentro: hay que animar la salida antes de que el caller la
        // desmonte, y ADEMÁS antes de lanzar el selector del sistema. Ver [LocalSheetCloser].
        val close = LocalSheetCloser.current
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.playlist_export_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                colors = appListItemColors(),
                headlineContent = { Text(favoritesLabel) },
                leadingContent = { MaterialSymbol("favorite", fill = true) },
                modifier = Modifier.clickable {
                    close { onPick(ExportTarget.Favorites, favoritesLabel) }
                }
            )

            HorizontalDivider(color = AppColors.outlineVariant)

            LazyColumn {
                // `key` explícita: sin ella Lazy identifica los items por posición y `animateItem`
                // no tendría a quién seguir cuando la lista cambia por detrás.
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        colors = appListItemColors(),
                        headlineContent = { Text(playlist.name) },
                        leadingContent = { MaterialSymbol("queue_music") },
                        modifier = Modifier
                            .animateItem()
                            .clickable {
                                close {
                                    onPick(ExportTarget.UserPlaylist(playlist.id), playlist.name)
                                }
                            }
                    )
                }
            }
        }
    }
}

/** Esquina del bloque, la misma que la de los demás bloques sueltos de Ajustes. */
private val M3uBlockCorner = 12.dp

/**
 * Con qué MIME se ofrece el selector al importar. Son varios porque **el proveedor decide el tipo,
 * casi siempre por la extensión**, y no hay uno solo para M3U: `ExternalStorageProvider` reporta
 * `audio/x-mpegurl` para un `.m3u` y `application/vnd.apple.mpegurl` para un `.m3u8`, mientras que
 * un archivo que bajó otra app suele llegar como `application/octet-stream`. Un filtro más estrecho
 * dejaría el archivo del usuario en gris sin decir por qué, que es el peor de los fallos posibles
 * para una pantalla de importación.
 */
private val M3U_PICKER_MIME_TYPES = arrayOf(
    M3uFormat.MIME_TYPE,
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "application/octet-stream",
    "text/plain"
)

/**
 * Qué se está exportando mientras el selector del sistema está abierto. Favoritos no es un caso
 * especial de "lista con id": desde la UI su id no se conoce (lo resuelve la capa de datos por el
 * UUID fijo), así que es una rama propia y no un número mágico.
 */
private sealed interface ExportTarget {
    data object Favorites : ExportTarget
    data class UserPlaylist(val id: Long) : ExportTarget
}
