package com.qhana.siku.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.EqCustomPreset
import com.qhana.siku.player.audio.EqualizerAudioProcessor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Presets del EQ. Cada curva se define con 5 ANCLAS en las frecuencias del modo de 5 bandas
 * (valores tomados de los presets clásicos del AudioFx de Android); para el modo de 10
 * bandas se interpola linealmente en espacio log-frecuencia (fuera del rango de anclas se
 * extiende el extremo). Así un mismo preset suena consistente en ambos modos.
 */
internal object EqPresets {

    internal class Preset(val labelRes: Int, val anchors: FloatArray)

    val ALL = listOf(
        Preset(R.string.eq_preset_flat, floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Preset(R.string.eq_preset_rock, floatArrayOf(5f, 3f, -1f, 3f, 5f)),
        Preset(R.string.eq_preset_pop, floatArrayOf(-1f, 2f, 5f, 1f, -2f)),
        Preset(R.string.eq_preset_jazz, floatArrayOf(4f, 2f, -2f, 2f, 5f)),
        Preset(R.string.eq_preset_classical, floatArrayOf(5f, 3f, -2f, 4f, 4f)),
        Preset(R.string.eq_preset_dance, floatArrayOf(6f, 0f, 2f, 4f, 1f)),
        Preset(R.string.eq_preset_hiphop, floatArrayOf(5f, 3f, 0f, 1f, 3f)),
        Preset(R.string.eq_preset_bass, floatArrayOf(6f, 4f, 1f, 0f, 0f)),
        Preset(R.string.eq_preset_treble, floatArrayOf(0f, 0f, 1f, 4f, 6f)),
        Preset(R.string.eq_preset_vocal, floatArrayOf(-2f, 1f, 4f, 3f, -1f))
    )

    fun gainsFor(preset: Preset, bandCount: Int): FloatArray =
        resample(preset.anchors, EqualizerAudioProcessor.bandsFor(5), bandCount)

    /**
     * Remuestrea una curva [anchors] (definida en las frecuencias [anchorFreqs]) al modo de
     * [bandCount] bandas, interpolando en espacio log-frecuencia. Si las frecuencias destino
     * coinciden con las de origen la curva se conserva exacta. Lo usan tanto los presets de
     * fábrica como los personalizados (guardados en 5 o 10 bandas).
     */
    fun resample(anchors: FloatArray, anchorFreqs: FloatArray, bandCount: Int): FloatArray {
        val target = EqualizerAudioProcessor.bandsFor(bandCount)
        if (target.contentEquals(anchorFreqs)) return anchors.copyOf()
        return FloatArray(target.size) { i -> interpolateLog(target[i], anchorFreqs, anchors) }
    }

    private fun interpolateLog(freq: Float, xs: FloatArray, ys: FloatArray): Float {
        if (freq <= xs.first()) return ys.first()
        if (freq >= xs.last()) return ys.last()
        for (i in 0 until xs.size - 1) {
            if (freq <= xs[i + 1]) {
                val t = (ln(freq) - ln(xs[i])) / (ln(xs[i + 1]) - ln(xs[i]))
                return ys[i] + (ys[i + 1] - ys[i]) * t
            }
        }
        return ys.last()
    }
}

/**
 * Hoja del ecualizador propio: toggle maestro + un slider por banda (±12 dB) + reset.
 * Las ganancias se aplican EN VIVO al processor (onBandChange) y se persisten al soltar
 * (onBandChangeFinished). Mantiene el acceso al ecualizador del sistema como alternativa
 * (algunos usuarios prefieren el de MIUI/fabricante, que además aplica a todo el sistema).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EqualizerSheet(
    enabled: Boolean,
    bandCount: Int,
    gains: List<Float>,
    customPresets: List<EqCustomPreset>,
    conflictWarningSuppressed: Boolean,
    onSuppressConflictWarning: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBandCountChange: (Int) -> Unit,
    onApplyPreset: (FloatArray) -> Unit,
    onApplyCustomPreset: (EqCustomPreset) -> Unit,
    onSaveCurrentAsPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onBandChange: (band: Int, db: Float) -> Unit,
    onBandChangeFinished: () -> Unit,
    onReset: () -> Unit,
    onOpenSystemEq: () -> Unit,
    onDismiss: () -> Unit
) {
    val frequencies = EqualizerAudioProcessor.bandsFor(bandCount)
    var showSaveDialog by remember { mutableStateOf(false) }
    // Aviso (no bloqueante) al ENCENDER el EQ propio: Android no permite saber de forma fiable
    // si hay un EQ del sistema/fabricante activo (Xiaomi misound vive fuera de la API pública),
    // así que no se puede bloquear el toggle; en su lugar recordamos que ambos se sumarían.
    // Suprimible con "No volver a mostrar" (persistido).
    var showSystemEqWarning by remember { mutableStateOf(false) }
    // Aviso simétrico al ABRIR el EQ del sistema con el propio encendido (mismo conflicto,
    // sentido contrario): ofrece apagar el propio antes de abrir el panel.
    var showOpenSystemWarning by remember { mutableStateOf(false) }
    // Overlay FULL-SCREEN (ya no es bottom sheet): el EQ es denso (5–10 bandas + shelves + acciones),
    // así respira y queda consistente con lyrics/cola. El slide-up + BackHandler los maneja el caller.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { MaterialSymbol("arrow_back") }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.eq_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Switch(
                    checked = enabled,
                    // Al encender pedimos confirmación (posible doble ecualización con un EQ
                    // del sistema), salvo "No volver a mostrar" ya marcado; al apagar no hay
                    // conflicto posible → pasa directo.
                    onCheckedChange = { checked ->
                        if (checked && !conflictWarningSuppressed) showSystemEqWarning = true
                        else onEnabledChange(checked)
                    }
                )
            }
            Text(
                text = stringResource(R.string.eq_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Selector 5/10 bandas: cambia EN VIVO (cada modo recuerda su propia curva).
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(5, 10).forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = bandCount == count,
                        onClick = { if (bandCount != count) onBandCountChange(count) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        enabled = enabled
                    ) {
                        Text(
                            stringResource(R.string.eq_bands_label, count),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Selector de preset (desplegable): colapsa fábrica + personalizados en un solo
            // control. Marca el preset actual (o "Personalizado" si la curva no coincide con
            // ninguno). Los personalizados llevan una X para borrarlos sin aplicarlos.
            PresetSelector(
                bandCount = bandCount,
                gains = gains,
                customPresets = customPresets,
                enabled = enabled,
                onApplyPreset = onApplyPreset,
                onApplyCustomPreset = onApplyCustomPreset,
                onDeleteCustomPreset = onDeleteCustomPreset
            )

            Spacer(modifier = Modifier.height(8.dp))

            repeat(frequencies.size) { band ->
                val gain = gains.getOrNull(band) ?: 0f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatBandLabel(frequencies[band]),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp)
                    )
                    Slider(
                        value = gain,
                        onValueChange = { onBandChange(band, it) },
                        onValueChangeFinished = onBandChangeFinished,
                        valueRange = -EqualizerAudioProcessor.MAX_GAIN_DB..EqualizerAudioProcessor.MAX_GAIN_DB,
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%+.1f", gain),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Acciones en ButtonGroup Expressive CONECTADO (overload SIN overflow) a ancho
            // completo → respeta el padding horizontal del sheet. Formas asimétricas: extremos
            // redondeados hacia afuera + esquinas internas pequeñas (8dp), separación 2dp, squish
            // al presionar. "Restablecer" lleva más peso para no truncarse. NO usar el overload de
            // overflow con fillMaxWidth (su measure copia un maxWidth menor dejando minWidth fijo
            // → crash); este overload pone minWidth 0/childSize, así que fillMaxWidth es seguro.
            val leadingShape = ButtonGroupDefaults.connectedLeadingButtonShape
            val trailingShape = ButtonGroupDefaults.connectedTrailingButtonShape
            val compactPadding = PaddingValues(horizontal = 12.dp)
            ButtonGroup(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                val saveSource = remember { MutableInteractionSource() }
                val resetSource = remember { MutableInteractionSource() }
                val systemSource = remember { MutableInteractionSource() }
                Button(
                    onClick = { showSaveDialog = true },
                    enabled = enabled,
                    interactionSource = saveSource,
                    shape = leadingShape,
                    contentPadding = compactPadding,
                    modifier = Modifier.weight(1f).animateWidth(saveSource)
                ) {
                    MaterialSymbol(icon = "save", size = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.eq_preset_save_short), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onReset,
                    enabled = enabled,
                    interactionSource = resetSource,
                    shape = ShapeDefaults.Small,
                    contentPadding = compactPadding,
                    modifier = Modifier.weight(1.25f).animateWidth(resetSource)
                ) {
                    MaterialSymbol(icon = "restart_alt", size = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.eq_reset), maxLines = 1)
                }
                FilledTonalButton(
                    // Con el EQ propio encendido, abrir el del sistema es el mismo conflicto de
                    // doble ecualización que cubre el aviso del toggle: se ofrece apagar antes.
                    onClick = { if (enabled) showOpenSystemWarning = true else onOpenSystemEq() },
                    interactionSource = systemSource,
                    shape = trailingShape,
                    contentPadding = compactPadding,
                    modifier = Modifier.weight(1f).animateWidth(systemSource)
                ) {
                    MaterialSymbol(icon = "tune", size = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.eq_system_short), maxLines = 1)
                }
            }
        }
    }

    if (showSystemEqWarning) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSystemEqWarning = false },
            icon = { MaterialSymbol(icon = "warning", size = 24.sp) },
            title = { Text(stringResource(R.string.eq_system_conflict_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.eq_system_conflict_message))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                        Text(
                            text = stringResource(R.string.eq_warning_dont_show),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowAgain) onSuppressConflictWarning()
                    onEnabledChange(true)
                    showSystemEqWarning = false
                }) {
                    Text(stringResource(R.string.eq_enable_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemEqWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Conflicto en sentido contrario: abrir el EQ del sistema con el propio ENCENDIDO.
    // Confirmar apaga el propio y abre; el botón secundario abre sin apagar (el usuario
    // puede querer solo mirar el panel); cancelar = tocar fuera o back.
    if (showOpenSystemWarning) {
        AlertDialog(
            onDismissRequest = { showOpenSystemWarning = false },
            icon = { MaterialSymbol(icon = "warning", size = 24.sp) },
            title = { Text(stringResource(R.string.eq_open_system_conflict_title)) },
            text = { Text(stringResource(R.string.eq_open_system_conflict_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onEnabledChange(false)
                    showOpenSystemWarning = false
                    onOpenSystemEq()
                }) {
                    Text(stringResource(R.string.eq_open_system_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOpenSystemWarning = false
                    onOpenSystemEq()
                }) {
                    Text(stringResource(R.string.eq_open_system_anyway))
                }
            }
        )
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onConfirm = {
                onSaveCurrentAsPreset(it)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

/**
 * Selector de preset desplegable. Detecta si la curva actual coincide (con tolerancia) con un
 * preset de fábrica o propio para marcarlo; si no, muestra "Personalizado". Los presets propios
 * llevan un icono de borrar que no dispara la aplicación (clic aparte).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSelector(
    bandCount: Int,
    gains: List<Float>,
    customPresets: List<EqCustomPreset>,
    enabled: Boolean,
    onApplyPreset: (FloatArray) -> Unit,
    onApplyCustomPreset: (EqCustomPreset) -> Unit,
    onDeleteCustomPreset: (String) -> Unit
) {
    fun matches(g: FloatArray) = gains.size == g.size &&
        gains.indices.all { abs(gains[it] - g[it]) < 0.1f }

    val builtInMatch = EqPresets.ALL.firstOrNull { matches(EqPresets.gainsFor(it, bandCount)) }
    val customMatch = customPresets.firstOrNull {
        matches(EqPresets.resample(it.gains, EqualizerAudioProcessor.bandsFor(it.bandCount), bandCount))
    }
    val currentLabel = when {
        builtInMatch != null -> stringResource(builtInMatch.labelRes)
        customMatch != null -> customMatch.name
        else -> stringResource(R.string.eq_preset_custom)
    }

    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.eq_preset_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            EqPresets.ALL.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(stringResource(preset.labelRes)) },
                    onClick = {
                        onApplyPreset(EqPresets.gainsFor(preset, bandCount))
                        expanded = false
                    },
                    trailingIcon = if (builtInMatch == preset) {
                        { MaterialSymbol(icon = "check", size = 20.sp) }
                    } else null
                )
            }
            if (customPresets.isNotEmpty()) {
                HorizontalDivider()
                customPresets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            onApplyCustomPreset(preset)
                            expanded = false
                        },
                        leadingIcon = if (customMatch?.id == preset.id) {
                            { MaterialSymbol(icon = "check", size = 20.sp) }
                        } else null,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onDeleteCustomPreset(preset.id)
                                    expanded = false
                                }
                            ) {
                                MaterialSymbol(
                                    icon = "delete",
                                    size = 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_preset_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.eq_preset_name_label)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.eq_preset_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

private fun formatBandLabel(hz: Float): String = if (hz >= 1000f) {
    val k = hz / 1000f
    if (k % 1f == 0f) String.format(Locale.getDefault(), "%.0f kHz", k)
    else String.format(Locale.getDefault(), "%.1f kHz", k)
} else {
    String.format(Locale.getDefault(), "%.0f Hz", hz)
}
