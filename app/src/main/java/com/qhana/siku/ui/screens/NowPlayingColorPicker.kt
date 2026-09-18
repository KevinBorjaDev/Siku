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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appTextButtonColors
import com.qhana.siku.ui.theme.rememberAccentPreview
import kotlinx.coroutines.launch

/** Tamaños de las dos muestras de cada fila: el color de la carátula y el acento que generaría. */
private val SwatchSize = 36.dp
private val PreviewWidth = 48.dp
private val PreviewHeight = 24.dp
/** Muestra del color SELECCIONADO en la cabecera: la más grande del diálogo, es lo que se viene a ver. */
private val SelectedSwatchSize = 48.dp
/** Punto del acento aplicado, al lado de su hex: acompaña, no compite con el seleccionado. */
private val AppliedDotSize = 12.dp
private val AppliedDotGap = 6.dp
private val WarningGlyphSize = 20.sp

/** Luminancia relativa a partir de la cual el check se pinta en negro en vez de blanco. */
private const val CHECK_LUMINANCE_CUTOFF = 0.5f

private fun hexOf(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)

/**
 * Selector de color de la carátula (long-press sobre el arte).
 *
 * Muestra DOS cosas por candidato porque no son la misma: el círculo es el color tal y como está en
 * la portada, y la barra es el acento que el tema generaría al elegirlo. La distancia entre ambos
 * puede ser enorme —el estilo de paleta impone su propio croma, y con "Equilibrado" un beige de
 * croma 11 sale como un mostaza de croma 32—, y enseñar solo el crudo hacía que el diálogo
 * pareciera no tener nada que ver con lo que aparecía en pantalla.
 *
 * La cabecera dice cuál es el color aplicado AHORA (el `primary` del tema vivo, o sea exactamente el
 * del botón play) junto al seed guardado del que sale. Antes la lista etiquetaba "(actual)" el hex
 * de un candidato, que no es el color aplicado nunca: **el aplicado es un color DERIVADO y no tiene
 * por qué estar en la lista**. Desde el 29 jul 2026 el seed guardado sí es uno de los candidatos —
 * se persiste crudo—, así que de los tres hexes que se ven aquí dos son de la lista y el tercero
 * (el aplicado) es lo que MaterialKolor hace con ellos.
 */
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
            TextButton(colors = appTextButtonColors(), onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                SelectedSeedHeader(savedSeed = debugInfo.winnerColor)

                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.color_picker_prompt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.color_picker_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                debugInfo.candidates.forEach { candidate ->
                    CandidateRow(candidate = candidate, onClick = { onColorSelected(candidate.color) })
                }
            }
        }
    )
}

/**
 * El color SELECCIONADO para el tema en curso (el seed guardado), en grande, con el aviso de
 * contraste al lado si no lo da. Debajo, el acento que la app está pintando de verdad con él: se lee
 * del propio tema (`primary` = color del botón play) en vez de recalcularlo, porque cualquier
 * reconstrucción sería una segunda versión de la misma derivación, capaz de discrepar justo aquí.
 *
 * Y el aviso se mide sobre ESE acento contra el fondo real de la app (`AppColors.surface`), no sobre
 * el seed guardado: aquí no hace falta previsualizar nada, el color en cuestión está pintado en la
 * pantalla de detrás.
 */
@Composable
private fun SelectedSeedHeader(savedSeed: Int) {
    val applied = AppColors.primary
    val appliedContrasts = ArtworkRepository.accentHasContrast(
        applied.toArgb(),
        AppColors.surface.toArgb()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(SelectedSwatchSize)
                .background(Color(savedSeed), CircleShape)
                .border(1.dp, AppColors.outlineVariant, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.color_picker_selected, hexOf(savedSeed)),
                style = MaterialTheme.typography.titleMediumEmphasized
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(AppliedDotSize)
                        .background(applied, CircleShape)
                        .border(1.dp, AppColors.outlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.width(AppliedDotGap))
                Text(
                    text = stringResource(R.string.color_picker_applied, hexOf(applied.toArgb())),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        if (!appliedContrasts) LowContrastBadge()
    }
}

/**
 * Aviso de que el ACENTO que sale de un color no se distinguirá del fondo (ver
 * [ArtworkRepository.accentHasContrast]). Se mide siempre sobre el color derivado —el `primary` del
 * esquema— y nunca sobre el color de la portada, que no se pinta en ningún sitio: el tono del crudo
 * no sobrevive al spec, así que avisar sobre él marcaba en rojo candidatos que se ven perfectamente
 * y dejaba pasar los que de verdad desaparecen. Es un AVISO y no un bloqueo: el color se puede
 * elegir igual, la decisión es del usuario.
 *
 * Botón y no un icono suelto, igual que el indicador de descarga de las filas: al tocarlo explica
 * el motivo con un tooltip. Mismo patrón que el aviso de saturación del ecualizador — el tooltip se
 * abre con el TAP además del long-press, porque un icono no anuncia que haya que mantenerlo pulsado.
 * Dentro de una fila tocable se queda con su propio toque, así que consultarlo no elige el color.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LowContrastBadge() {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.color_picker_low_contrast)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = description }
        ) {
            MaterialSymbol("warning", color = AppColors.error, size = WarningGlyphSize, fill = true)
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: com.qhana.siku.data.repository.ArtworkRepository.ColorCandidate,
    onClick: () -> Unit
) {
    val hex = hexOf(candidate.color)
    // Lo que pasaría al tocar ESTA fila: el color se guarda como override manual y se usa de seed
    // tal cual (sin re-tonalizar), así que el seed de la previsualización es el color crudo. Es la
    // MISMA ruta que sigue el automático desde el 29 jul, así que elegir a mano el candidato que el
    // análisis ya había elegido ahora da el mismo resultado — antes daba uno más vivo, porque el
    // automático guardaba el seed proyectado a T80 y perdía el croma.
    val preview = rememberAccentPreview(candidate.color)
    val note = when {
        candidate.isExact -> stringResource(R.string.color_picker_current)
        candidate.isWinner -> stringResource(R.string.color_picker_hue_source)
        else -> null
    }

    // La fila del color seleccionado lleva el contenedor de SELECCIÓN de M3 (`secondaryContainer`
    // con su par `onSecondaryContainer`): el check del círculo solo no bastaba para encontrarla.
    val selected = candidate.isWinner
    val textColor = if (selected) AppColors.onSecondaryContainer else Color.Unspecified
    val noteColor = if (selected) AppColors.onSecondaryContainer else AppColors.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppColors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        // Color tal cual está en la carátula.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SwatchSize)
                .background(Color(candidate.color), CircleShape)
                .border(
                    width = if (candidate.isWinner) 2.dp else 1.dp,
                    color = if (candidate.isWinner) AppColors.primary
                    else AppColors.outlineVariant,
                    shape = CircleShape
                )
                .semantics { contentDescription = hex }
        ) {
            if (candidate.isWinner) {
                // El check se pinta sobre el color de la carátula, que puede ser casi blanco: el
                // blanco fijo desaparecía justo en los candidatos claros.
                val onSwatch = if (Color(candidate.color).luminance() > CHECK_LUMINANCE_CUTOFF) {
                    Color.Black
                } else {
                    Color.White
                }
                MaterialSymbol("check", size = 18.sp, color = onSwatch)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hex,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = textColor
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = noteColor
                )
            }
        }

        // El aviso es sobre el ACENTO, que es lo que se va a ver, no sobre el color de la portada:
        // por eso sale del mismo `preview` que pinta la barra de al lado y no de un campo del
        // candidato. Ver [rememberAccentPreview].
        if (!preview.hasContrast) LowContrastBadge()

        // Acento que generaría el tema con este color.
        Box(
            modifier = Modifier
                .width(PreviewWidth)
                .height(PreviewHeight)
                .background(preview.accent, RoundedCornerShape(4.dp))
                .semantics { contentDescription = hexOf(preview.accent.toArgb()) }
        )
    }
}
