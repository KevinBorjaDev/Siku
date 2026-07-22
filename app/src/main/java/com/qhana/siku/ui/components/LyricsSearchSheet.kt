package com.qhana.siku.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.repository.LyricsCandidate

/**
 * Bottom sheet para selección manual de letras desde candidatos de LrcLib.
 * Tap = seleccionar. Long-press = preview de la letra completa antes de confirmar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsSearchSheet(
    isLoading: Boolean,
    candidates: List<LyricsCandidate>?,
    errorMessage: String?,
    onCandidateSelected: (LyricsCandidate) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var preview by remember { mutableStateOf<LyricsCandidate?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.lyrics_choose),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = stringResource(R.string.lyrics_choose_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider()

            when {
                isLoading -> CenteredMessage {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                }
                errorMessage != null -> CenteredMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol("error_outline", size = 48.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        // Reintentar: la búsqueda de candidatos suele fallar por timeout de
                        // LrcLib; relanza la misma consulta sin cerrar la hoja.
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) {
                            MaterialSymbol("refresh", size = 18.sp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
                candidates != null && candidates.isEmpty() -> CenteredMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol("search_off", size = 48.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.lyrics_no_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                candidates != null -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(candidates, key = { it.id }) { candidate ->
                        CandidateItem(
                            candidate = candidate,
                            onClick = { onCandidateSelected(candidate) },
                            onLongClick = { preview = candidate }
                        )
                    }
                }
            }
        }
    }

    preview?.let { candidate ->
        LyricsPreviewDialog(
            candidate = candidate,
            onUse = {
                onCandidateSelected(candidate)
                preview = null
            },
            onDismiss = { preview = null }
        )
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateItem(
    candidate: LyricsCandidate,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        headlineContent = {
            Text(candidate.trackName, fontWeight = FontWeight.SemiBold, maxLines = 1)
        },
        supportingContent = {
            Column {
                Text(candidate.artistName, maxLines = 1)
                if (!candidate.albumName.isNullOrBlank()) {
                    Text(
                        candidate.albumName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    candidate.durationSeconds?.let { d ->
                        Text(
                            text = formatDuration(d),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Badge(text = candidate.badgeLabel(), color = candidate.badgeColor())
                }
            }
        }
    )
}

@Composable
private fun LyricsPreviewDialog(
    candidate: LyricsCandidate,
    onUse: () -> Unit,
    onDismiss: () -> Unit
) {
    val previewText = when {
        candidate.instrumental -> "[INSTRUMENTAL]"
        !candidate.syncedLyrics.isNullOrBlank() -> stripTimestamps(candidate.syncedLyrics)
        !candidate.plainLyrics.isNullOrBlank() -> candidate.plainLyrics
        else -> "(Sin contenido)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(candidate.trackName, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(
                    candidate.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUse) { Text(stringResource(R.string.lyrics_use)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LyricsCandidate.badgeLabel(): String = when {
    instrumental -> stringResource(R.string.lyrics_badge_instrumental)
    hasSynced -> stringResource(R.string.lyrics_badge_synced)
    hasPlain -> stringResource(R.string.lyrics_plain)
    else -> stringResource(R.string.lyrics_badge_none)
}

@Composable
private fun LyricsCandidate.badgeColor(): androidx.compose.ui.graphics.Color = when {
    instrumental -> MaterialTheme.colorScheme.tertiary
    hasSynced -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun stripTimestamps(text: String): String =
    text.replace(Regex("""\[\d{2}:\d{2}(\.\d{2,3})?] ?"""), "")

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
