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
import com.qhana.siku.data.model.DetailSongSort
import com.qhana.siku.data.model.SortCriterion
import com.qhana.siku.data.model.SortSpec
import com.qhana.siku.ui.theme.AppColors

/**
 * Chip de ORDEN con menú desplegable, genérico sobre el tipo de criterio. Sustituyó a un icono
 * suelto (`SortMenuIconButton`, ya eliminado — sin corchetes, que sugieren un símbolo al que se
 * puede ir) para unificar Todas/Artistas/Álbumes: las tres pantallas llevan sus controles como
 * chips en la fila sobre el contenido. Muestra el criterio activo ("Título") y abre el mismo menú
 * al tocarlo. **Outlined sin relleno**, el default de `AssistChip` en M3 y el mismo aspecto que los
 * chips de origen en reposo.
 *
 * **La DIRECCIÓN se invierte tocando el criterio que ya está activo** (16 sep 2026), en vez de
 * ofrecer dos entradas por criterio. El menú deja de crecer por multiplicación y, de paso, todos
 * los criterios ganan sus dos sentidos: antes solo los tenían título y fecha, y "menos
 * reproducidas" o "hace más que no la escucho" no existían porque no cabían. La lógica del gesto
 * NO vive aquí sino en [SortSpec.selecting], que es lo que hace que las seis pantallas con chip se
 * comporten igual sin repetir la condición.
 *
 * Y **la dirección tiene que VERSE**, o el segundo toque es un huevo de pascua: va en el icono
 * leading del chip —que así pasa de decorativo (`sort`, que no informaba de nada) a informativo— y
 * en el icono del item activo del menú, donde sustituye al check. El `arrow_drop_down` del trailing
 * se queda: es lo que sigue diciendo que esto abre un menú.
 *
 * @param options pares (string resource del label, criterio) en el orden del menú. Las etiquetas
 *   nombran SOLO el criterio ("Título", "Reproducciones"): meter la dirección en el texto —"Más
 *   reproducidas"— choca con la flecha en cuanto se invierte.
 */
@Composable
fun <T> SortChip(
    current: SortSpec<T>,
    options: List<Pair<Int, T>>,
    onChange: (SortSpec<T>) -> Unit
) where T : Enum<T>, T : SortCriterion {
    var showMenu by remember { mutableStateOf(false) }
    val currentLabelRes = options.firstOrNull { it.second == current.criterion }?.first
    val currentLabel = if (currentLabelRes != null) stringResource(currentLabelRes) else ""
    val directionIcon = if (current.ascending) "arrow_upward" else "arrow_downward"
    // El chip muestra SOLO el criterio: el "Ordenar:" ya lo dice el icono, y sumado al criterio se
    // pasaba de los 20 caracteres que Material fija como tope del label de un chip — dentro de la
    // FlowRow eso es un chip que se lleva una fila entera para él solo. El prefijo SOBREVIVE como
    // contentDescription, junto con la dirección: lo que en pantalla resuelve la flecha, para un
    // lector de pantalla no lo resuelve nadie.
    val chipDescription = stringResource(
        R.string.sort_chip_label,
        currentLabel,
        stringResource(
            if (current.ascending) R.string.sort_direction_ascending
            else R.string.sort_direction_descending
        )
    )
    Box {
        AssistChip(
            onClick = { showMenu = true },
            modifier = Modifier.semantics { contentDescription = chipDescription },
            label = { Text(currentLabel) },
            leadingIcon = { MaterialSymbol(directionIcon, size = 18.sp) },
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
        // componente marca el activo con contenedor y morph de forma, anima la entrada del icono
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
                            selected = current.criterion == value,
                            onClick = { onChange(current.selecting(value)); showMenu = false },
                            text = { Text(stringResource(labelRes)) },
                            // La forma sale de la POSICIÓN dentro del grupo: el primero redondea
                            // arriba, el último abajo y los de en medio van rectos.
                            shapes = MenuDefaults.itemShape(index = index, count = options.size),
                            // La flecha en vez del check: dice en qué sentido está ordenando ESTE
                            // criterio, que es justo lo que el siguiente toque va a invertir.
                            selectedLeadingIcon = { MenuItemIcon(directionIcon) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * El [SortChip] de las tres pantallas de DETALLE (artista, álbum, género), con su catálogo de
 * opciones escrito una sola vez.
 *
 * Existe por eso último: las tres ofrecen los MISMOS criterios y solo cambia cómo se llama el orden
 * del catálogo, así que repetir la lista en cada pantalla sería garantizar que la cuarta se olvide
 * una — el mismo error que ya obligó a centralizar `actionAccentOn`.
 *
 * @param catalogLabel cómo se llama en ESTA pantalla [DetailSongSort.CATALOG] ("Álbum y pista" en
 *   un artista, "Nº de pista" en un álbum, "Título" en un género, que no impone ninguno).
 */
@Composable
fun DetailSortChip(
    current: SortSpec<DetailSongSort>,
    catalogLabel: Int,
    onChange: (SortSpec<DetailSongSort>) -> Unit
) {
    // ¿Esta pantalla tiene un orden de catálogo propio? El detalle de género no: el suyo ES el
    // título, así que ahí CATALOG y TITLE producen la MISMA lista.
    val catalogIsTitle = catalogLabel == R.string.sort_by_title
    val options = remember(catalogLabel) {
        buildList {
            add(catalogLabel to DetailSongSort.CATALOG)
            // Sin esta guarda, el menú del género tendría dos formas de elegir lo mismo.
            if (!catalogIsTitle) add(R.string.sort_by_title to DetailSongSort.TITLE)
            add(R.string.sort_by_play_count to DetailSongSort.PLAY_COUNT)
            add(R.string.sort_by_last_played to DetailSongSort.LAST_PLAYED)
            add(R.string.sort_by_date_added to DetailSongSort.DATE_ADDED)
        }
    }
    // **Y la entrada que se quitó hay que ABSORBERLA, no ignorarla**: el criterio es uno solo para
    // las tres pantallas, así que se puede llegar al género con `TITLE` elegido en un artista.
    // [SortChip] busca la etiqueta del criterio activo en la lista, y sin encontrarlo el chip se
    // quedaría MUDO —con el label vacío— aunque la lista estuviera bien ordenada: un fallo que no
    // rompe nada y solo se ve en device. La DIRECCIÓN se conserva al absorberlo: en esta pantalla
    // los dos criterios son el mismo orden, así que invertir uno tiene que seguir invirtiendo.
    val effective = if (catalogIsTitle && current.criterion == DetailSongSort.TITLE) {
        SortSpec(DetailSongSort.CATALOG, current.ascending)
    } else {
        current
    }
    SortChip(current = effective, options = options, onChange = onChange)
}
