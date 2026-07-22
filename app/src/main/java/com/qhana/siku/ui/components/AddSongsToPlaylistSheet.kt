package com.qhana.siku.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.Song

/**
 * Selector de canciones para engordar una lista de reproducción: búsqueda + selección
 * MÚLTIPLE (añadir de una en una obligaba a abrir y cerrar la hoja por cada tema).
 *
 * Las canciones que ya están en la lista se filtran fuera de [candidates] por el llamador:
 * enseñarlas apagadas solo añadía ruido a una lista que puede tener miles de filas.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddSongsToPlaylistSheet(
    playlistName: String,
    candidates: List<Song>,
    query: String,
    hasSongsAvailable: Boolean,
    onQueryChange: (String) -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // La selección sobrevive a los cambios de búsqueda: se puede marcar una canción, buscar
    // otra cosa y seguir sumando antes de confirmar.
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.playlist_add_songs_title, playlistName),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.playlist_add_songs_search)) },
                leadingIcon = { MaterialSymbol("search", color = colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        MaterialSymbol(
                            "close",
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onQueryChange("") }
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (candidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol(
                            if (hasSongsAvailable) "search_off" else "library_music",
                            size = 48.sp,
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(
                                if (hasSongsAvailable) R.string.playlist_add_songs_no_results
                                else R.string.playlist_add_songs_none
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    itemsIndexed(candidates, key = { _, song -> song.id }) { index, song ->
                        val checked = song.id in selectedIds
                        Surface(
                            color = if (checked) colorScheme.secondaryContainer else colorScheme.surfaceContainer,
                            shape = rememberListItemShape(index, candidates.size),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                        ) {
                            SongItem(
                                song = song,
                                isPlaying = false,
                                showStatusIcon = false,
                                modifier = Modifier.clickable {
                                    selectedIds = if (checked) selectedIds - song.id else selectedIds + song.id
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selectedIds = if (checked) selectedIds - song.id else selectedIds + song.id
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onConfirm(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(56.dp)
            ) {
                MaterialSymbol("playlist_add", size = 22.sp, color = colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.playlist_add_songs_confirm, selectedIds.size),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            Spacer(
                modifier = Modifier
                    .height(16.dp)
                    .navigationBarsPadding()
            )
        }
    }
}
