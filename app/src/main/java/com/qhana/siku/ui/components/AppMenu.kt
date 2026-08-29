package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.PopupProperties

/**
 * El `DropdownMenuPopup` de la app. Es el punto de entrada ÚNICO para cualquier menú desplegable —
 * `DropdownMenuPopup` crudo no debe aparecer en el repo, igual que `ModalBottomSheet` crudo tampoco
 * (ver [AppModalSheet]) — y va SIEMPRE con [AppMenuGroup] dentro, que es quien pone el color.
 *
 * **La decisión de color, que ahora vive en [AppMenuGroup]**: M3 pinta el menú con
 * `surfaceContainerLow`, que está a UN peldaño de `surface` —el fondo de casi todas las pantallas— y
 * a dos del `surfaceContainer` de las barras. Con un tema dinámico, cuya escala neutra apenas tiene
 * croma, esa distancia no se ve: el menú se fundía con lo que tapaba y solo lo delataba su sombra.
 * Es el mismo agotamiento de la escala neutra que ya obligó a separar el MiniPlayer por croma; aquí
 * la salida es la contraria —quedarse en la escala, pero en su TECHO— porque un menú de varias
 * entradas teñido de acento pesa demasiado para algo que se abre y se cierra en un segundo. (M3
 * tabula esa otra opción: `groupVibrantContainerColor`, sobre `tertiaryContainer`. Su propio doc
 * pide usarla "sparingly", y aquí serían los nueve menús.)
 *
 * **Por qué el TECHO y no un peldaño intermedio**: las superficies sobre las que un menú puede caer
 * en esta app van de `surface` a `surfaceContainerHigh` — la biblioteca y los detalles reparten en
 * HORQUILLA (contenido `surface` 98, página `surfaceContainer` 94, barras `surfaceContainerHigh`
 * 92; ver `headerColor` en LibraryScreen). Cualquier valor por debajo del techo empataría con alguna
 * de ellas en alguna pantalla; `surfaceContainerHighest` es el único que no puede, y por eso es una
 * decisión y no un gusto calibrado a ojo.
 *
 * Ojo al tocar ese reparto: el 20 ago 2026 la cabecera de la biblioteca estuvo unas horas en este
 * mismo `surfaceContainerHighest` y el menú del ⋮ de la búsqueda nacía del color de la barra que lo
 * abría. Se revirtió por otro motivo, pero deja la lección: **este techo solo es un techo mientras
 * nadie más lo ocupe.**
 *
 * **Aquí hubo, hasta el 20 ago 2026, un `MaterialTheme` anidado que REMAPEABA `surfaceContainerLow`
 * a `surfaceContainerHighest`**, para que el grupo y los 37 items heredaran el techo sin tocar cada
 * caller. Se cayó con el refactor de color: el remapeo depende de que el default de M3 se resuelva
 * dentro del subárbol, y en cuanto `MaterialTheme` dejó de seguir a la carátula el menú apareció
 * GRIS dentro de una app teñida (visto en device). La lección general del refactor vale también
 * aquí: **el color se ENTREGA, no se hereda por un remapeo** — [AppMenuGroup] y [appMenuItemColors]
 * lo pasan explícito, que son los mismos dos sitios que el remapeo cubría. NO reintroducirlo.
 */
@Composable
fun AppMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    popupPositionProvider: DropdownMenuPopupPositionProvider =
        MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below),
    properties: PopupProperties = MenuDefaults.DefaultMenuProperties,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        popupPositionProvider = popupPositionProvider,
        properties = properties
    ) {
        content()
    }
}

/**
 * Forma de una entrada de menú según su POSICIÓN en el grupo, para los menús cuyo número de items
 * no es fijo. Es lo que hace `MenuDefaults.itemShape(index, count)`, pero devolviendo el `Shape`
 * suelto: esa función entrega un `MenuItemShapes`, que solo aceptan las sobrecargas de
 * `DropdownMenuItem` con estado (`selected`/`checked`) — la de acción simple recibe `shape`.
 *
 * Vive aquí y no en un menú concreto porque ya son dos los que cambian de tamaño: el del
 * `SplitButton` de los detalles (dos o cinco entradas, según la pantalla tenga orden propio) y el
 * overflow del header de la biblioteca (Estadísticas y Cerrar sesión son condicionales).
 */
@Composable
internal fun menuItemShapeAt(index: Int, count: Int): Shape = when {
    count <= 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}
