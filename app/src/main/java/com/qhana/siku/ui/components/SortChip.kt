package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.qhana.siku.ui.theme.appAssistChipBorder
import com.qhana.siku.ui.theme.appSelectableMenuItemColors
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.R
import com.qhana.siku.ui.theme.AppColors

/**
 * Chip de ORDEN con menú desplegable, genérico sobre el tipo de orden. Sustituyó a un icono suelto
 * (`SortMenuIconButton`, ya eliminado — sin corchetes, que sugieren un símbolo al que se puede ir)
 * para unificar Todas/Artistas/Álbumes: las tres
 * pantallas llevan sus controles como chips en la fila sobre el contenido. Muestra el
 * criterio activo ("Nombre ▾") y abre el mismo menú al tocarlo. **Outlined sin relleno**, el
 * default de `AssistChip` en M3 y el mismo aspecto que los chips de origen en reposo.
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
    val currentLabel = if (currentLabelRes != null) stringResource(currentLabelRes) else ""
    // El chip muestra SOLO el criterio: el "Ordenar:" ya lo dice el leadingIcon `sort`, y sumado al
    // criterio se pasaba de los 20 caracteres que Material fija como tope del label de un chip
    // ("Ordenar: Escuchadas recientemente" = 33) — dentro de la FlowRow eso es un chip que se lleva
    // una fila entera para él solo. El prefijo SOBREVIVE como contentDescription: lo que en
    // pantalla resuelve el icono, para un lector de pantalla no lo resuelve nadie.
    val chipDescription = stringResource(R.string.sort_chip_label, currentLabel)
    Box {
        AssistChip(
            onClick = { showMenu = true },
            modifier = Modifier.semantics { contentDescription = chipDescription },
            label = { Text(currentLabel) },
            leadingIcon = { MaterialSymbol("sort", size = 18.sp) },
            trailingIcon = { MaterialSymbol("arrow_drop_down", size = 18.sp) },
            // NEUTRO y no `secondaryContainer` (29 ago 2026): ése es el color de un botón tonal, y
            // este chip convive con los `QuickActionButton` del inicio y con los de la pestaña
            // Todas. **El acento marca lo ACCIONABLE; lo que describe o acota va en neutro** — la
            // misma regla que dejó neutra la píldora de búsqueda.
            //
            // **SIN RELLENO, que es el default de `AssistChip` en M3** (7 sep 2026, decisión del
            // usuario). Estuvo relleno en `surfaceContainerHighest` y sin borde, y eso ocupaba el
            // único slot neutro que quedaba en la fila: en device, "777 canciones" y "Recientes
            // primero" se leían como la misma pieza. El comentario que lo justificaba —"con borde
            // sería la única pieza de la fila con contorno"— solo era cierto en una biblioteca sin
            // split de origen; con los filtros visibles ya hay tres contornos y este chip PERTENECE
            // a ese grupo, porque es lo mismo que ellos: un control que acota la lista.
            //
            // Al soltarlo, el relleno neutro queda libre para `CountChip`, que así pasa a ser la
            // única pieza rellena-y-neutra de la fila y la única que no se lee como un control —
            // que es la verdad, porque es la única que no se toca. **Ninguna de las dos cosas se
            // puede revertir sola**: si este chip vuelve a rellenarse, el contador tiene que salir
            // del neutro, y ahí no queda más que gastar un matiz (el `tertiaryContainer` morado que
            // esto sustituye).
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Color.Transparent,
                labelColor = AppColors.onSurface,
                leadingIconContentColor = AppColors.onSurfaceVariant,
                trailingIconContentColor = AppColors.onSurfaceVariant
            ),
            border = appAssistChipBorder()
        )
        // Menú SEGMENTADO de M3 Expressive = `AppMenuPopup` (nuestro `DropdownMenuPopup`, ver su
        // kdoc) + `AppMenuGroup`. No basta
        // con dar forma a los items: `DropdownMenu` es el contenedor CLÁSICO (`MenuTokens`) y
        // mete los items en una superficie única, así que items con forma dentro de él no son ni
        // una cosa ni la otra. El grupo es quien aporta el contenedor `SegmentedMenuTokens`.
        //
        // Elegir un orden es además una SELECCIÓN EXCLUSIVA, de ahí la sobrecarga `selected`: el
        // componente marca el activo con contenedor y morph de forma, anima la entrada del check
        // y declara `Role.RadioButton`. Antes se simulaba pintando un check en `leadingIcon` — el
        // item no sabía que estaba seleccionado y esa era la única señal.
        //
        // ANCLA de tamaño CERO en la esquina del chip: es lo que despliega el menú del lado del
        // control en vez de dejarlo colgando desde el borde opuesto. `MenuAnchorPosition` no tiene
        // "debajo del ancla y alineado a su final" —`Below` alinea inicio con inicio y `End` lo
        // pone AL LADO—, y `Custom` no sirve porque exige `MenuPosition`, que es `internal` en la
        // librería. Con el ancla reducida a un punto, `Start` sí lo da EXACTO y por su PRIMER
        // candidato de cada eje (`endToAnchorStart` → el menú termina donde termina el chip;
        // `topToAnchorTop` → arranca justo debajo), sin depender de que otro no quepa. `Start` y
        // `BottomEnd` se espejan juntos en RTL, así que el menú sigue saliendo del lado del chip.
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            AppMenuPopup(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(
                    MenuAnchorPosition.Start
                )
            ) {
                AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                    options.forEachIndexed { index, (labelRes, value) ->
                        DropdownMenuItem(
                            colors = appSelectableMenuItemColors(),
                            selected = current == value,
                            onClick = { onChange(value); showMenu = false },
                            text = { Text(stringResource(labelRes)) },
                            // La forma sale de la POSICIÓN dentro del grupo: el primero redondea
                            // arriba, el último abajo y los de en medio van rectos.
                            shapes = MenuDefaults.itemShape(index = index, count = options.size),
                            selectedLeadingIcon = { MenuItemIcon("check") }
                        )
                    }
                }
            }
        }
    }
}
