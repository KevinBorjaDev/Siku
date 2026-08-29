package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFilledTonalButtonColors
import com.qhana.siku.ui.theme.appSwitchColors
import kotlinx.coroutines.delay

/**
 * Cadencia con la que se repinta la cuenta regresiva. Un segundo porque es la resolución de lo que
 * se lee (`mm:ss`): más rápido no cambiaría ningún dígito y más lento dejaría el número parado a la
 * vista. No sale de la constante del reloj de reproducción aunque coincida el valor — aquello mide
 * el avance de la canción y esto una cuenta atrás; que los dos quieran segundos es una coincidencia,
 * no un acoplamiento, y atarlos haría que tocar uno moviera el otro sin motivo.
 */
private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * Hoja del temporizador de apagado: presets de duración + opción de terminar la canción en
 * curso antes de pausar. Con un temporizador activo muestra la cuenta regresiva (tick de
 * [COUNTDOWN_TICK_MS] solo mientras la hoja está abierta) y el botón de cancelar; elegir otro
 * preset lo re-arma.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerSheet(
    state: MusicController.SleepTimerState?,
    onStart: (minutes: Int, finishSong: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AppModalSheet(onDismissRequest = onDismiss) {
        // Cerrar ANIMANDO antes de ejecutar la acción: `onDismiss` apaga el flag que monta la hoja
        // y llamarlo directo la arranca del árbol sin salida. Ver [LocalSheetCloser].
        val close = LocalSheetCloser.current
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
                        delay(COUNTDOWN_TICK_MS)
                    }
                }
                val remainingMs = (state.endAtMs - now).coerceAtLeast(0L)
                AppSurface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = AppColors.primaryContainer,
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
                                color = AppColors.onPrimaryContainer
                            )
                        } else {
                            Text(
                                text = formatTime(remainingMs),
                                style = MaterialTheme.typography.displaySmallEmphasized,
                                color = AppColors.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.sleep_timer_remaining),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.onPrimaryContainer
                            )
                            if (state.finishSong) {
                                Text(
                                    text = stringResource(R.string.sleep_timer_then_finish_song),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryContentColor(
                                        AppColors.onPrimaryContainer,
                                        AppColors.primaryContainer
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(colors = appFilledTonalButtonColors(), onClick = {
                            close { onCancel(); onDismiss() }
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
                        colors = appFilledTonalButtonColors(),
                        onClick = {
                            close { onStart(minutes, finishSong); onDismiss() }
                        },
                        // Shape-morph Expressive al presionar, como el resto de botones prominentes.
                        shapes = ButtonDefaults.shapes()
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
                Switch(colors = appSwitchColors(), checked = finishSong, onCheckedChange = { finishSong = it })
            }
        }
    }
}
