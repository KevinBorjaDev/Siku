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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.data.util.LrcSyntax
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appTextButtonColors

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

    AppModalSheet(onDismissRequest = onDismiss) {
        // Elegir un candidato cierra la hoja desde dentro. Ver [LocalSheetCloser].
        val close = LocalSheetCloser.current
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
                color = AppColors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider(color = AppColors.outlineVariant)

            when {
                isLoading -> CenteredMessage {
                    LoadingIndicator(color = AppColors.primary, modifier = Modifier.size(48.dp))
                }
                errorMessage != null -> CenteredMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol("error", size = 48.sp, color = AppColors.error)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            errorMessage,
                            color = AppColors.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        // Reintentar: la búsqueda de candidatos suele fallar por timeout de
                        // LrcLib; relanza la misma consulta sin cerrar la hoja.
                        Spacer(Modifier.height(16.dp))
                        val retryButtonHeight = ButtonDefaults.MinHeight
                        Button(colors = appButtonColors(), onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                            // Sin color explícito: hereda el LocalContentColor del Button (así se
                            // atenúa solo si alguna vez se deshabilita, en vez de quedar brillante
                            // sobre un texto apagado — mismo criterio que el botón del onboarding).
                            // Tamaño y separación derivados de la altura, no literales.
                            MaterialSymbol(
                                "refresh",
                                size = ButtonDefaults.iconSizeFor(retryButtonHeight).value.sp
                            )
                            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(retryButtonHeight)))
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
                candidates != null && candidates.isEmpty() -> CenteredMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialSymbol("search_off", size = 48.sp, color = AppColors.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.lyrics_no_matches), color = AppColors.onSurfaceVariant)
                    }
                }
                candidates != null -> LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(candidates, key = { it.id }) { candidate ->
                        CandidateItem(
                            candidate = candidate,
                            onClick = { close { onCandidateSelected(candidate) } },
                            onLongClick = { preview = candidate },
                            modifier = Modifier.animateItem()
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
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        // Contenedor TRANSPARENTE: el default de `ListItem` es `surface` (casi blanco en el tema
        // claro), que pintaba un bloque blanco sobre el crema de la hoja (`surfaceContainerLow`).
        // Transparente deja ver el color real de la hoja y las filas dejan de "cortarse".
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = {
            Text(
                candidate.trackName,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                maxLines = 1
            )
        },
        supportingContent = {
            Column {
                Text(candidate.artistName, maxLines = 1)
                if (!candidate.albumName.isNullOrBlank()) {
                    Text(
                        candidate.albumName,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    candidate.durationSeconds?.let { d ->
                        Text(
                            text = formatDuration(d),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Badge(text = candidate.badgeLabel(), colors = candidate.badgeColors())
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
        !candidate.syncedLyrics.isNullOrBlank() -> LrcSyntax.stripTags(candidate.syncedLyrics)
        !candidate.plainLyrics.isNullOrBlank() -> candidate.plainLyrics
        else -> "(Sin contenido)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                // Sin peso propio: el slot `title` del AlertDialog ya aplica el rol del spec.
                Text(candidate.trackName, maxLines = 2)
                Text(
                    candidate.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.onSurfaceVariant
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
            TextButton(colors = appTextButtonColors(), onClick = onUse) { Text(stringResource(R.string.lyrics_use)) }
        },
        dismissButton = {
            TextButton(colors = appTextButtonColors(), onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

@Composable
private fun Badge(text: String, colors: BadgeColors) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            // Contenedor tonal por ROL (par container/on-container), no un alpha sobre el contenido.
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = colors.content,
            style = MaterialTheme.typography.labelSmall
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

/** Par contenedor/contenido de un badge, para que el tinte salga de roles del scheme y no de un alpha. */
private data class BadgeColors(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

@Composable
private fun LyricsCandidate.badgeColors(): BadgeColors = when {
    instrumental -> BadgeColors(
        AppColors.tertiaryContainer,
        AppColors.onTertiaryContainer
    )
    hasSynced -> BadgeColors(
        AppColors.primaryContainer,
        AppColors.onPrimaryContainer
    )
    // `onSurfaceVariant` no tiene par de contenedor; el neutro va sobre `surfaceContainerHighest`.
    else -> BadgeColors(
        AppColors.surfaceContainerHighest,
        AppColors.onSurfaceVariant
    )
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
