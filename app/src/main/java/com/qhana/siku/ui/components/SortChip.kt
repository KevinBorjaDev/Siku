package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R

/**
 * Chip de ORDEN con menú desplegable, genérico sobre el tipo de orden. Reemplazó al
 * [SortMenuIconButton] (icono suelto) para unificar Todas/Artistas/Álbumes: las tres
 * pantallas llevan sus controles como chips en la fila sobre el contenido. Muestra el
 * criterio activo ("Ordenar: Nombre ▾") y abre el mismo menú al tocarlo. Relleno tonal
 * (secondaryContainer), igual que el chip de conteo y los de origen.
 *
 * @param options pares (string resource del label, valor) en el orden del menú.
 */
@Composable
fun <T> SortChip(
    current: T,
    options: List<Pair<Int, T>>,
    onChange: (T) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val currentLabelRes = options.firstOrNull { it.second == current }?.first
    Box {
        AssistChip(
            onClick = { showMenu = true },
            label = {
                Text(
                    if (currentLabelRes != null)
                        stringResource(R.string.sort_chip_label, stringResource(currentLabelRes))
                    else stringResource(R.string.sort_chip_label, "")
                )
            },
            leadingIcon = { MaterialSymbol("sort", size = 18.sp) },
            trailingIcon = { MaterialSymbol("arrow_drop_down", size = 18.sp) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            border = null
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            options.forEach { (labelRes, value) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onChange(value); showMenu = false },
                    leadingIcon = { if (current == value) MaterialSymbol("check") }
                )
            }
        }
    }
}
