package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appSegmentedListItemColors

/**
 * Fila de una LISTA AGRUPADA de M3, canónica y COMPARTIDA por las listas de navegación de la app.
 *
 * Es un **`SegmentedListItem`**, que es el componente REAL del spec para esto desde Material 3
 * 1.5. Antes esta función montaba a mano lo que aquél hace por dentro —`Row` con `clip` + fondo
 * tonal envolviendo un `ListItem` transparente—, con los radios de grupo calculados en
 * [rememberListItemShape]. Los valores coincidían con los tokens (`ItemContainerExpressiveShape`
 * 4dp en el interior, `ContainerShape` 16dp en los extremos), así que en reposo se ve igual; lo
 * que la copia no tenía era el resto de la máquina de estados del componente:
 *
 *  - **morph al PRESIONAR** (`pressedShape` = CornerLarge), interpolado con el token `fastSpatial`
 *    del scheme de motion — el sello Expressive de las listas, y lo único que hacía que estas
 *    filas se sintieran distintas de los botones de la app, que sí morfean desde hace tiempo;
 *  - estados `selected`, `focused` y `hovered`, que antes no existían;
 *  - el gap entre filas como TOKEN (`ListItemDefaults.SegmentedGap`) en vez de un 1dp por lado
 *    escrito a mano — que resultaba ser ese mismo valor, pero sin quedar atado a él.
 *
 * El reparto de esquinas lo hace `groupedListItemShapes(index, count)` —`segmentedShapes` de M3 con
 * el caso de UNA fila corregido—, que es la misma
 * regla (extremos pronunciados, interior pequeño) resuelta por la librería.
 *
 * Las acciones (play, overflow) van en [trailingContent], el slot trailing del ítem, no como
 * hermanos sueltos del texto — así heredan el margen al borde del spec.
 *
 * @param containerColor el default es `surface`, que resulta ser el mismo valor que el token del
 *        componente (`ItemSegmentedContainerColor`) pero por un motivo propio: en el reparto de la
 *        app las páginas van en `surfaceContainer` y el CONTENIDO sube a `surface` para flotar sobre
 *        ellas (ver `headerColor` en LibraryScreen). Ojo con la coincidencia — si la página cambia,
 *        este default se mueve con ella, no con el token.
 */
@Composable
fun GroupedListRow(
    index: Int,
    count: Int,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    containerColor: Color = AppColors.contentSurface
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = groupedListItemShapes(index = index, count = count),
        colors = appSegmentedListItemColors(containerColor),
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        content = headlineContent,
        modifier = modifier
            .fillMaxWidth()
            // Margen lateral de la app + la MITAD del gap por fila (el componente separa filas
            // vecinas, así que cada una aporta su parte), igual que el envoltorio de SongItem en
            // Todas/Cola.
            .padding(horizontal = 16.dp, vertical = ListItemDefaults.SegmentedGap / 2)
    )
}
