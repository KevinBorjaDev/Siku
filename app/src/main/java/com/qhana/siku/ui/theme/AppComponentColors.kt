package com.qhana.siku.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuGroupShapes
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * El color de contenido que le toca a [color], resuelto contra [AppColors].
 *
 * ## Por qué existe este archivo entero
 *
 * Un componente de M3 al que no se le pasan colores **no los recibe: los va a buscar él mismo**.
 *
 * ```kotlin
 * // material3 1.5.0-alpha24, Button.kt:1376 y Switch.kt:341
 * @Composable fun textButtonColors() = MaterialTheme.colorScheme.defaultTextButtonColors
 * @Composable fun colors() = MaterialTheme.colorScheme.defaultSwitchColors
 * ```
 *
 * Mientras el tema se regeneró desde la carátula eso funcionaba solo: `primary` ya venía teñido y
 * la etiqueta salía con el color del álbum sin que nadie se lo pidiera. Desde que el tema dejó de
 * moverse —ver el KDoc de [AppColorScheme] para el porqué— esos componentes se quedarían en un
 * color fijo mientras las superficies propias siguen a la carátula. **El problema no sería un botón
 * apagado: sería la MEZCLA**, media app viva y media no, que es peor que cualquiera de las dos.
 *
 * Cada helper de aquí toca **solo los roles que ese componente usa de verdad**, sacados de los
 * tokens de M3 y no a ojo (`FilledButtonTokens`, `SwitchTokens`, `SliderTokens`, `ListTokens`…), y
 * hereda del propio M3 todo lo demás — en particular los estados DESHABILITADOS, que son
 * `onSurface` con alfas tabuladas: replicarlos aquí sería meter media docena de números mágicos
 * para pintar de gris algo que ya está gris.
 *
 * **La regla: un componente de M3 que deba llevar el color del álbum se escribe con su helper de
 * aquí.** Si falta uno, se añade con el mapeo leído de los tokens, nunca inventado.
 *
 * ## Y en particular esta función
 *
 * Es el equivalente de `contentColorFor` de M3 (`ColorScheme.kt:1089`), con el mismo mapeo rol por
 * rol. Existe porque el de M3 busca el color **por VALOR dentro de `MaterialTheme.colorScheme`**
 * (`ColorScheme.kt:1137`) y cae a `LocalContentColor` si no lo encuentra. Con el tema quieto y las
 * superficies viniendo de [AppColors] esa búsqueda no acierta nunca: el texto de encima heredaría
 * el color de quien esté más arriba, **sin fallar en compilación y sin que se note hasta verlo en
 * pantalla**. Por eso [AppSurface] lo aplica por defecto.
 *
 * **Ante un color que no es ninguno de los roles hereda el de fuera, y eso NO es un adorno.**
 * Devolver `Color.Unspecified` —como estuvo hasta el 22 ago 2026, con el argumento de que
 * significaba "no sé, heredá"— es precisamente lo que impide heredar: [AppSurface] entrega ese
 * valor al `Surface`, que lo PROVEE en `LocalContentColor` pisando el de arriba, y un `Text` sin
 * color resuelve `Unspecified` a NEGRO. El síntoma fue texto negro sobre fondo negro en los dos
 * customizers de Ajustes (pestañas y barra del reproductor), que dibujan sus filas sobre
 * `AppSurface(color = Color.Transparent)` — un color legítimo y no tabulado.
 *
 * M3 no tiene el fallo porque su `contentColorFor` remata con `takeOrElse { LocalContentColor }`
 * (ColorScheme.kt:1137). Al portar el mapeo rol por rol se copió todo menos ese remate.
 */
@Composable
@ReadOnlyComposable
fun appContentColorFor(color: Color): Color {
    val c = LocalAppColors.current
    return when (color) {
        c.primary -> c.onPrimary
        c.secondary -> c.onSecondary
        c.tertiary -> c.onTertiary
        c.background -> c.onBackground
        c.error -> c.onError
        c.primaryContainer -> c.onPrimaryContainer
        c.secondaryContainer -> c.onSecondaryContainer
        c.tertiaryContainer -> c.onTertiaryContainer
        c.errorContainer -> c.onErrorContainer
        c.inverseSurface -> c.inverseOnSurface
        c.surface -> c.onSurface
        c.surfaceVariant -> c.onSurfaceVariant
        c.surfaceBright -> c.onSurface
        c.surfaceContainer -> c.onSurface
        c.surfaceContainerHigh -> c.onSurface
        c.surfaceContainerHighest -> c.onSurface
        c.surfaceContainerLow -> c.onSurface
        c.surfaceContainerLowest -> c.onSurface
        c.surfaceDim -> c.onSurface
        // Un color arbitrario (transparente, calculado, de fuera de la escala): se hereda el de
        // quien contiene, que es lo que hace M3 y lo que `Color.Unspecified` NO hace. Ver el KDoc.
        else -> LocalContentColor.current
    }
}

/**
 * `Surface` con el color de contenido derivado de [AppColors] en vez de del tema.
 *
 * Es la única diferencia con el de M3, y es la que evita el fallo silencioso que describe
 * [appContentColorFor]. Lo usan los sitios que pasan `color` sin `contentColor`.
 */
@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = AppColors.surface,
    contentColor: Color = appContentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) = Surface(
    modifier = modifier,
    shape = shape,
    color = color,
    contentColor = contentColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    border = border,
    content = content
)

/**
 * Variante clicable, con la misma corrección del color de contenido.
 *
 * `interactionSource` se expone porque hay callers que lo COMPARTEN: el botón de play del
 * reproductor anima su ancho con el mismo `MutableInteractionSource` con el que se pinta, así que
 * sin este parámetro no hay forma de que el efecto de pulsación y la animación hablen del mismo
 * gesto. Nullable como en M3, que lo sustituye por uno recordado cuando llega `null`.
 */
@Composable
fun AppSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = AppColors.surface,
    contentColor: Color = appContentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = Surface(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    color = color,
    contentColor = contentColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    border = border,
    interactionSource = interactionSource,
    content = content
)

// --- Botones -------------------------------------------------------------------------------

/** Relleno: contenedor `primary`, etiqueta `onPrimary` (`FilledButtonTokens`). */
@Composable
fun appButtonColors() = ButtonDefaults.buttonColors(
    containerColor = AppColors.primary,
    contentColor = AppColors.onPrimary
)

/** De texto: sin contenedor, etiqueta `primary` (`TextButtonTokens`). */
@Composable
fun appTextButtonColors() = ButtonDefaults.textButtonColors(contentColor = AppColors.primary)

/** Con borde: etiqueta `onSurfaceVariant` (`OutlinedButtonTokens`); el borde sale de `outline`. */
@Composable
fun appOutlinedButtonColors() =
    ButtonDefaults.outlinedButtonColors(contentColor = AppColors.onSurfaceVariant)

/** Tonal: `secondaryContainer` / `onSecondaryContainer` (`FilledTonalButtonTokens`). */
@Composable
fun appFilledTonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer
)

// --- Botones de icono ----------------------------------------------------------------------

// NO hay helper para el `IconButton` sin contenedor, y es a propósito. Su color por defecto NO sale
// del token sino de la HERENCIA:
//
// ```kotlin
// // material3 1.5.0-alpha24, IconButtonDefaults.kt:51
// fun iconButtonColors(): IconButtonColors {
//     val contentColor = LocalContentColor.current
//     ...
// ```
//
// O sea que ya se tiñe solo, porque `LocalContentColor` lo pone la superficie que lo contiene y esas
// superficies sí leen [AppColors]. Se llegó a añadir el helper y a aplicarlo en 25 sitios: eso
// FORZABA `onSurfaceVariant` y rompía la herencia justo donde importa —un icono dentro de un
// contenedor de acento dejaba de usar el color de ese contenedor—. Revertido. **Si alguien vuelve a
// echarlo en falta: el que hereda no necesita que le entreguen nada.**

/** Relleno: `primary` / `onPrimary` (`FilledIconButtonTokens`). */
@Composable
fun appFilledIconButtonColors() = IconButtonDefaults.filledIconButtonColors(
    containerColor = AppColors.primary,
    contentColor = AppColors.onPrimary
)

/** Tonal: `secondaryContainer` / `onSecondaryContainer` (`FilledTonalIconButtonTokens`). */
@Composable
fun appFilledTonalIconButtonColors() = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer
)

/**
 * Toggle relleno: apagado `surfaceContainer`/`onSurfaceVariant`, encendido `primary`/`onPrimary`.
 *
 * Es el de los toggles con shape-morph del toolbar del reproductor, así que el estado encendido es
 * justo donde se ve el acento del álbum.
 */
@Composable
fun appFilledIconToggleButtonColors() = IconButtonDefaults.filledIconToggleButtonColors(
    containerColor = AppColors.surfaceContainer,
    contentColor = AppColors.onSurfaceVariant,
    checkedContainerColor = AppColors.primary,
    checkedContentColor = AppColors.onPrimary
)

// --- Controles de ajuste -------------------------------------------------------------------

/** Switch: encendido sobre `primary`, apagado sobre la escala neutra (`SwitchTokens`). */
@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AppColors.onPrimary,
    checkedTrackColor = AppColors.primary,
    checkedBorderColor = AppColors.primary,
    checkedIconColor = AppColors.onPrimaryContainer,
    uncheckedThumbColor = AppColors.outline,
    uncheckedTrackColor = AppColors.surfaceContainerHighest,
    uncheckedBorderColor = AppColors.outline,
    uncheckedIconColor = AppColors.surfaceContainerHighest
)

/**
 * Slider. Ojo con los ticks, que en M3 van CRUZADOS a propósito (`Slider.kt:1668`): el tick activo
 * lleva el color de la pista INACTIVA y viceversa, para que cada uno se vea sobre su propio tramo.
 */
@Composable
fun appSliderColors() = SliderDefaults.colors(
    thumbColor = AppColors.primary,
    activeTrackColor = AppColors.primary,
    activeTickColor = AppColors.secondaryContainer,
    inactiveTrackColor = AppColors.secondaryContainer,
    inactiveTickColor = AppColors.primary
)

/** Checkbox: marcado `primary` con el tilde en `onPrimary` (`CheckboxTokens`). */
@Composable
fun appCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = AppColors.primary,
    uncheckedColor = AppColors.onSurfaceVariant,
    checkmarkColor = AppColors.onPrimary
)

/** Radio: seleccionado `primary` (`RadioButtonTokens`). */
@Composable
fun appRadioButtonColors() = RadioButtonDefaults.colors(
    selectedColor = AppColors.primary,
    unselectedColor = AppColors.onSurfaceVariant
)

// --- Chips, listas, barras y menús ---------------------------------------------------------

/** Filter chip: seleccionado sobre `secondaryContainer` (`FilterChipTokens`). */
@Composable
fun appFilterChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = AppColors.onSurfaceVariant,
    iconColor = AppColors.onSurfaceVariant,
    selectedContainerColor = AppColors.secondaryContainer,
    selectedLabelColor = AppColors.onSecondaryContainer,
    selectedLeadingIconColor = AppColors.onSecondaryContainer
)

/** Card rellena: `surfaceContainerHighest` (`FilledCardTokens.ContainerColor`). */
@Composable
fun appCardColors() = CardDefaults.cardColors(
    containerColor = AppColors.surfaceContainerHighest,
    contentColor = AppColors.onSurface
)

/** Fila de lista de M3 (`ListTokens`): contenedor `surface`, apoyos `onSurfaceVariant`. */
@Composable
fun appListItemColors() = ListItemDefaults.colors(
    containerColor = AppColors.surface,
    headlineColor = AppColors.onSurface,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant,
    overlineColor = AppColors.onSurfaceVariant,
    supportingColor = AppColors.onSurfaceVariant
)

/**
 * Fila de lista SEGMENTADA (`SegmentedListItem`), con sus TRES estados de color.
 *
 * Los tokens (`ListTokens`): contenedor `surface` —los grupos de Ajustes lo suben un escalón, de ahí
 * el parámetro—, contenido `onSurface` con los apoyos en `onSurfaceVariant`; SELECCIONADO en
 * `secondaryContainer` con TODO su contenido en `onSecondaryContainer`; y ARRASTRADO en
 * `tertiaryContainer` (`ReorderListTokens`), que es como M3 marca la fila que se está moviendo.
 *
 * **Los estados eran la mitad que se olvidaba**: `segmentedColors(containerColor = …)` copia sobre el
 * `defaultSegmentedListItemColors` del TEMA (`ListItemDefaults.kt:319`), así que la fila apagada
 * salía teñida por la carátula y la ENCENDIDA azul del wallpaper — visible en cada interruptor
 * activo de Ajustes. Los `disabled*` de CONTENIDO se heredan de M3 a propósito (son alfas tabuladas
 * sobre `onSurface`: replicarlas sería meter números mágicos, y son neutras); el contenedor
 * deshabilitado sí se fija, porque M3 lo resuelve a su token `surface` y no al que se le pasó.
 */
@Composable
fun appSegmentedListItemColors(
    containerColor: Color = AppColors.surface
) = ListItemDefaults.segmentedColors(
    containerColor = containerColor,
    contentColor = AppColors.onSurface,
    leadingContentColor = AppColors.onSurfaceVariant,
    trailingContentColor = AppColors.onSurfaceVariant,
    overlineContentColor = AppColors.onSurfaceVariant,
    supportingContentColor = AppColors.onSurfaceVariant,
    disabledContainerColor = containerColor,
    selectedContainerColor = AppColors.secondaryContainer,
    selectedContentColor = AppColors.onSecondaryContainer,
    selectedLeadingContentColor = AppColors.onSecondaryContainer,
    selectedTrailingContentColor = AppColors.onSecondaryContainer,
    selectedOverlineContentColor = AppColors.onSecondaryContainer,
    selectedSupportingContentColor = AppColors.onSecondaryContainer,
    draggedContainerColor = AppColors.tertiaryContainer,
    draggedContentColor = AppColors.onTertiaryContainer,
    draggedLeadingContentColor = AppColors.onTertiaryContainer,
    draggedTrailingContentColor = AppColors.onTertiaryContainer,
    draggedOverlineContentColor = AppColors.onTertiaryContainer,
    draggedSupportingContentColor = AppColors.onTertiaryContainer
)

/**
 * Top app bar (`AppBarTokens`), incluido el color al que vira con el scroll.
 *
 * Los defaults son los de una pantalla cuyo fondo es `surface` (Ajustes, gestor de descargas). **Una
 * pantalla con el fondo en `surfaceContainer` —el nivel MEDIO, como los detalles y estadísticas—
 * tiene que pasar `surfaceContainerHigh`**: la horquilla de color dice que el contenido SUBE y las
 * barras BAJAN, así que dejar la barra en `surface` la deja por ENCIMA del fondo y se lee como una
 * banda clara pegada arriba. Los dos parámetros van juntos a propósito: si la barra base baja, el
 * color al que vira con el scroll no puede quedarse por encima de ella.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopAppBarColors(
    containerColor: Color = AppColors.surface,
    scrolledContainerColor: Color = AppColors.surfaceContainer
) = TopAppBarDefaults.topAppBarColors(
    containerColor = containerColor,
    scrolledContainerColor = scrolledContainerColor,
    navigationIconContentColor = AppColors.onSurface,
    titleContentColor = AppColors.onSurface,
    actionIconContentColor = AppColors.onSurfaceVariant
)

/**
 * Campo de texto relleno (`FilledTextFieldTokens`).
 *
 * El acento está en tres sitios y sólo en tres: el cursor, la línea inferior CUANDO tiene el foco y
 * la etiqueta flotante con el foco. El resto es escala neutra, foco o no.
 */
@Composable
fun appTextFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = AppColors.onSurface,
    unfocusedTextColor = AppColors.onSurface,
    focusedContainerColor = AppColors.surfaceContainerHighest,
    unfocusedContainerColor = AppColors.surfaceContainerHighest,
    cursorColor = AppColors.primary,
    focusedIndicatorColor = AppColors.primary,
    unfocusedIndicatorColor = AppColors.onSurfaceVariant,
    focusedLabelColor = AppColors.primary,
    unfocusedLabelColor = AppColors.onSurfaceVariant,
    focusedPlaceholderColor = AppColors.onSurfaceVariant,
    unfocusedPlaceholderColor = AppColors.onSurfaceVariant,
    focusedLeadingIconColor = AppColors.onSurfaceVariant,
    unfocusedLeadingIconColor = AppColors.onSurfaceVariant,
    focusedTrailingIconColor = AppColors.onSurfaceVariant,
    unfocusedTrailingIconColor = AppColors.onSurfaceVariant
)

/**
 * Entrada de menú de ACCIÓN (sin estado): texto `onSurface`, iconos `onSurfaceVariant`
 * (`ListTokens`).
 *
 * No lleva contenedor, y eso no es un olvido: `MenuDefaults.itemColors` ni siquiera lo acepta
 * (MenuDefaults.kt:411, seis campos). El item simple no pinta superficie — la pinta el grupo, que
 * por eso es [AppMenuGroup] y no el `DropdownMenuGroup` crudo.
 */
@Composable
fun appMenuItemColors() = MenuDefaults.itemColors(
    textColor = AppColors.onSurface,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant
)

/**
 * Entrada de menú con ESTADO (`selected` / `checked`), que es otra API y otro juego de colores:
 * `MenuDefaults.selectableItemColors` (MenuDefaults.kt:452).
 *
 * Ésta **sí pinta su propio contenedor** —es lo que le permite morfear la forma leading/middle/
 * trailing dentro del grupo— y trae además los cuatro `selected*`. Confundirla con la de acción fue
 * el bug del menú gris del 20 ago: con los campos del contenedor sin entregar, el item seguía
 * sacándolos del tema, que ya no acompaña a la carátula.
 *
 * El estado seleccionado va sobre `tertiaryContainer` (`StandardMenuTokens.ItemSelectedContainerColor`);
 * no es un despiste, es el rol con el que M3 marca la opción activa de un menú.
 */
@Composable
fun appSelectableMenuItemColors() = MenuDefaults.selectableItemColors(
    textColor = AppColors.onSurface,
    containerColor = AppColors.surfaceContainerHighest,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant,
    selectedContainerColor = AppColors.tertiaryContainer,
    selectedTextColor = AppColors.onTertiaryContainer,
    selectedLeadingIconColor = AppColors.onTertiaryContainer,
    selectedTrailingIconColor = AppColors.onTertiaryContainer
)

/**
 * Grupo de un menú desplegable, con el contenedor en el **TECHO de la escala neutra**.
 *
 * Ese techo es la decisión de diseño que describe [com.qhana.siku.ui.components.AppMenuPopup] y
 * hasta el 20 ago 2026 se conseguía REMAPEANDO el rol `surfaceContainerLow` en un `MaterialTheme`
 * anidado. Eso funcionaba mientras el tema seguía a la carátula; cuando dejó de seguirla, el menú se
 * quedó gris —visible en device— porque su color dependía de una cadena de defaults en vez de
 * recibirlo. Ahora se entrega, igual que en el resto de la app, y el remapeo desapareció.
 */
@Composable
fun AppMenuGroup(
    shapes: MenuGroupShapes,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = DropdownMenuGroup(
    shapes = shapes,
    modifier = modifier,
    containerColor = AppColors.surfaceContainerHighest,
    content = content
)

/**
 * Snackbar con el par inverso de [AppColors].
 *
 * Se pasa como `snackbar = { AppSnackbar(it) }` en los `SnackbarHost`: el host no expone colores,
 * los pone el `Snackbar` de dentro, y sus defaults (`inverseSurface` / `inverseOnSurface` /
 * `inversePrimary`) salen del tema, que ya no acompaña a la carátula.
 */
@Composable
fun AppSnackbar(data: SnackbarData) = Snackbar(
    snackbarData = data,
    containerColor = AppColors.inverseSurface,
    contentColor = AppColors.inverseOnSurface,
    actionColor = AppColors.inversePrimary
)

/**
 * Floating toolbar VIBRANT: contenedor `primaryContainer` (`FloatingToolbarTokens`).
 *
 * Es el del toolbar del NowPlaying y el de la hoja de la cola, y fue **el primer sitio donde se
 * notó** que congelar el tema dejaba componentes atrás: su contenido ya salía de [AppColors] —está
 * escrito a mano en el caller— pero el CONTENEDOR lo resolvía M3 por dentro, así que la barra se
 * quedó azul (el `primaryContainer` del esquema base) con los iconos teñidos encima.
 *
 * El FAB adyacente va en `tertiaryContainer`, como en M3; aquí no se usa, pero se pasa igual para
 * que el helper no mienta si alguien lo estrena.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appVibrantFloatingToolbarColors() = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
    toolbarContainerColor = AppColors.primaryContainer,
    toolbarContentColor = AppColors.onPrimaryContainer,
    fabContainerColor = AppColors.tertiaryContainer,
    fabContentColor = AppColors.onTertiaryContainer
)

/**
 * Toggle button tonal (`TonalButtonTokens`): apagado `secondaryContainer`, encendido `secondary`.
 *
 * Ojo con el salto: el estado marcado NO es el contenedor tonal más oscuro sino el rol `secondary`
 * a secas, que es bastante más saturado. Así lo tabula M3 y así se queda.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appTonalToggleButtonColors() = ToggleButtonDefaults.tonalToggleButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer,
    checkedContainerColor = AppColors.secondary,
    checkedContentColor = AppColors.onSecondary
)

/**
 * Floating toolbar NEUTRO: el del selector de la biblioteca cuando vive abajo
 * (`LibraryBottomToolbar`).
 *
 * **No es el vibrant** ([appVibrantFloatingToolbarColors], el del NowPlaying y la hoja de la cola):
 * allí el toolbar es la pieza protagonista de una pantalla teñida, y aquí flota sobre la biblioteca
 * a un palmo del MiniPlayer, que ya lleva `primaryContainer` — dos píldoras del mismo color
 * apiladas se leen como una sola pieza partida por la mitad.
 *
 * **Pero tampoco es el token que M3 tabula para el standard**, que es `surfaceContainer`
 * (`FloatingToolbarTokens.StandardContainerColor`): en esta app ése es el **fondo de página** (ver
 * la horquilla de color en CLAUDE.md — el fondo es el nivel MEDIO de la escala, el contenido sube a
 * `surface` y las barras bajan a `surfaceContainerHigh`), así que el toolbar quedaría del color
 * exacto de lo que tiene detrás y solo lo separaría su sombra. Va al **techo de la escala neutra**,
 * que es el mismo criterio —y por el mismo motivo— que ya obligó a subir los menús en
 * `AppMenuPopup`: una pieza que puede caer sobre cualquier superficie tiene que estar por encima de
 * todas, y las de la biblioteca van de `surface` a `surfaceContainerHigh`.
 *
 * El contenido de cada destino NO sale de aquí — lo pone [appLibraryToolbarItemColors], porque un
 * destino inactivo va en `onSurfaceVariant` (el token de la navigation bar) y no en el `onSurface`
 * del contenedor.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appLibraryToolbarColors() = FloatingToolbarDefaults.standardFloatingToolbarColors(
    toolbarContainerColor = AppColors.surfaceContainerHighest,
    toolbarContentColor = AppColors.onSurface,
    fabContainerColor = AppColors.primaryContainer,
    fabContentColor = AppColors.onPrimaryContainer
)

/**
 * Destino del toolbar de la biblioteca (`LibraryBottomToolbar`), montado sobre `ToggleButton`.
 *
 * **Los mismos cuatro colores que la píldora de `LibraryTabs` y que el rail**: activo
 * `secondaryContainer`/`onSecondaryContainer`, inactivo `onSurfaceVariant` — el token de la
 * navigation bar (`NavigationBarTokens.InactiveIconColor`) — sobre contenedor TRANSPARENTE, que es
 * lo que deja ver el material del toolbar. Las tres presentaciones del selector son proyecciones
 * del mismo `pagerState.currentPage` y no pueden divergir en color.
 *
 * **NO se usa `tonalToggleButtonColors`** aunque el nombre suene al caso: ése pinta el contenedor
 * también en reposo (`secondaryContainer` apagado → `secondary` encendido), o sea seis píldoras
 * tonales dentro de otra píldora tonal.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appLibraryToolbarItemColors() = ToggleButtonDefaults.toggleButtonColors(
    containerColor = Color.Transparent,
    contentColor = AppColors.onSurfaceVariant,
    checkedContainerColor = AppColors.secondaryContainer,
    checkedContentColor = AppColors.onSecondaryContainer
)

/**
 * Ítem del navigation rail de la biblioteca (horizontal).
 *
 * Vale el token de la etiqueta (`NavigationRailColorTokens.ItemActiveLabelText` = `secondary`)
 * porque en el rail el indicador rodea solo el glifo y el texto queda debajo, sobre el contenedor de
 * la barra. Los demás valores son los de la píldora de `LibraryTabs`: el rail y las dos filas son
 * presentaciones de la misma navegación y no pueden divergir en color.
 */
@Composable
fun appNavigationRailItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = AppColors.onSecondaryContainer,
    selectedTextColor = AppColors.secondary,
    indicatorColor = AppColors.secondaryContainer,
    unselectedIconColor = AppColors.onSurfaceVariant,
    unselectedTextColor = AppColors.onSurfaceVariant
)
