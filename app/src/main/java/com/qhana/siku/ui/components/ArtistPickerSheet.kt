package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import coil3.compose.AsyncImage
import com.qhana.siku.data.repository.DeezerArtistCandidate

/**
 * Sheet de selección manual de artista (resultados de Deezer), por si el auto-match
 * de la foto no corresponde. Molde de [LyricsSearchSheet].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistPickerSheet(
    artistName: String,
    isLoading: Boolean,
    candidates: List<DeezerArtistCandidate>?,
    errorMessage: String?,
    onCandidateSelected: (DeezerArtistCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.artist_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                text = stringResource(R.string.artist_picker_subtitle, artistName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            when {
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                }

                errorMessage != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MaterialSymbol("error_outline", size = 40.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                candidates.isNullOrEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MaterialSymbol("search_off", size = 40.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.artist_picker_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(candidates, key = { it.deezerId }) { candidate ->
                        ListItem(
                            headlineContent = { Text(candidate.name, maxLines = 1) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (candidate.thumbUrl != null) {
                                        AsyncImage(
                                            model = candidate.thumbUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    } else {
                                        MaterialSymbol("person", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onCandidateSelected(candidate) }
                        )
                    }
                }
            }
        }
    }
}
