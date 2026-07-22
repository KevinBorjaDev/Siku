package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.player.MusicController
import kotlinx.coroutines.delay

/**
 * Hoja del temporizador de apagado: presets de duración + opción de terminar la canción en
 * curso antes de pausar. Con un temporizador activo muestra la cuenta regresiva (tick de 1s
 * solo mientras la hoja está abierta) y el botón de cancelar; elegir otro preset lo re-arma.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    state: MusicController.SleepTimerState?,
    onStart: (minutes: Int, finishSong: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge
            )

            if (state != null) {
                // Cuenta regresiva viva: el tick existe solo mientras la hoja está en
                // composición — cerrarla lo mata, el timer real vive en MusicController.
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.endAtMs) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                val remainingMs = (state.endAtMs - now).coerceAtLeast(0L)
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.awaitingSongEnd) {
                            Text(
                                text = stringResource(R.string.sleep_timer_awaiting_song_end),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Text(
                                text = formatTime(remainingMs),
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.sleep_timer_remaining),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (state.finishSong) {
                                Text(
                                    text = stringResource(R.string.sleep_timer_then_finish_song),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = {
                            onCancel()
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.sleep_timer_cancel))
                        }
                    }
                }
            }

            var finishSong by rememberSaveable { mutableStateOf(state?.finishSong ?: false) }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(10, 15, 30, 45, 60, 90).forEach { minutes ->
                    FilledTonalButton(
                        onClick = {
                            onStart(minutes, finishSong)
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.sleep_timer_minutes, minutes))
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.sleep_timer_finish_song),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 16.dp)
                )
                Switch(checked = finishSong, onCheckedChange = { finishSong = it })
            }
        }
    }
}
