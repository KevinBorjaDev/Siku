package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.ui.components.*

@Composable
internal fun ColorPickerDialog(
    info: Any,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val debugInfo = info as? com.qhana.siku.data.repository.ArtworkRepository.DebugColorInfo ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Text(stringResource(R.string.color_picker_prompt), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))

                debugInfo.candidates.forEach { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onColorSelected(candidate.color) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        // Color Swatch
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(candidate.color), CircleShape)
                                .border(
                                    width = if (candidate.isWinner) 2.dp else 1.dp,
                                    color = if (candidate.isWinner) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                        ) {
                            if (candidate.isWinner) {
                                MaterialSymbol(
                                    "check",
                                    size = 18.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Info
                        Column(modifier = Modifier.weight(1f)) {
                            val hex = String.format("#%06X", 0xFFFFFF and candidate.color)
                            val label = if (candidate.isWinner) "$hex (actual)" else hex
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        // Preview: mini bar showing how the color would look
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .height(24.dp)
                                .background(Color(candidate.color), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    )
}
