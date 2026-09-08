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
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.components.DISABLED_CONTENT_ALPHA
import com.qhana.siku.ui.components.TEXT_ACCENT_MIN_CONTRAST
import com.qhana.siku.ui.components.actionAccentOn
import com.qhana.siku.ui.components.tonalLayerContainer

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

/**
 * De texto: SIN contenedor, etiqueta en acento.
 *
 * **El default ya no es `primary` a secas** (8 sep 2026). `TextButtonTokens.LabelTextColor` dice
 * `primary`, y apartarse del spec aquí es deliberado: un `TextButton` **no tiene contenedor**, así
 * que su etiqueta cae sobre la superficie que haya debajo — y M3 no promete NINGÚN contraste de un
 * acento contra una superficie neutra. Con `PaletteStyle.Fidelity` y una carátula lavada, la
 * etiqueta se acerca al fondo y el botón desaparece; es el mismo defecto que ya se corrigió en el
 * botón "Ver" de estadísticas, en el play de la fila de artista y en el de `AlbumTileCard`.
 *
 * Es de LEJOS el de más alcance de esa familia: **64 llamadas, 51 `TextButton`**, o sea que una
 * línea gobierna casi todas las acciones de texto de la app.
 *
 * A [actionAccentOn] se le pide [TEXT_ACCENT_MIN_CONTRAST] (4.5:1) y no el 3:1 de un glifo: esto es
 * TEXTO de 14sp. Sigue siendo role-first — prueba los acentos tabulados y solo re-tonaliza `primary`
 * si ninguno llega.
 *
 * **La superficie asumida es `AppColors.surface`**, que es sobre la que vive la enorme mayoría de
 * estos botones (diálogos, hojas y pantallas). Un `TextButton` sobre un contenedor TINTADO tiene que
 * pasar su propio `contentColor`, igual que antes.
 */
@Composable
fun appTextButtonColors(
    /**
     * Se pasa un acento propio cuando el botón PERTENECE a un bloque con color propio —el "ver
     * todas" al pie de una sección de estadísticas, que va del color de su sección— para que se lea
     * como parte de él y no como una acción suelta de la pantalla.
     */
    contentColor: Color = actionAccentOn(AppColors.surface, TEXT_ACCENT_MIN_CONTRAST)
) = ButtonDefaults.textButtonColors(contentColor = contentColor)

/**
 * Con borde: etiqueta `onSurfaceVariant` (`OutlinedButtonTokens.LabelTextColor`).
 *
 * **El borde NO va aquí**: en M3 es un parámetro aparte del componente (`border`), no parte de
 * `ButtonColors`. Lo pone [appOutlinedButtonBorder], y hay que pasarlo — este helper por sí solo
 * deja el trazo saliendo del tema base. El KDoc de esta función decía "el borde sale de `outline`",
 * que era falso por partida doble: ni lo controlaba ni el rol es ése.
 *
 * @param contentColor se pasa otro cuando el botón vive en una superficie con color propio y tiene
 *   que leerse como parte de ella (el "buscar en Google" del error de letras, que va del acento del
 *   álbum). Mismo criterio que [appTextButtonColors].
 */
@Composable
fun appOutlinedButtonColors(contentColor: Color = AppColors.onSurfaceVariant) =
    ButtonDefaults.outlinedButtonColors(contentColor = contentColor)

/**
 * El borde de un `OutlinedButton`, con el `outlineVariant` de [AppColors].
 *
 * **Existe porque el default de M3 no puede acertar en esta app**: `ButtonDefaults.outlinedButtonBorder`
 * resuelve `OutlinedButtonTokens.OutlineColor` contra `MaterialTheme.colorScheme`, que aquí es el
 * esquema BASE —el del wallpaper— y no el sembrado con la carátula. Los siete `OutlinedButton` de la
 * app lo dejaban al default, así que su trazo era el único píxel de esos botones que no seguía al
 * álbum. Es el mismo caso que documenta la cabecera de este archivo: *un componente al que no se le
 * pasan colores va a buscarlos al tema*.
 *
 * Los dos números salen de los TOKENS de M3 leídos en las fuentes de la versión pineada, no a ojo:
 * el ancho es `ButtonSmallTokens.OutlinedOutlineWidth` y la opacidad del deshabilitado es
 * `OutlinedButtonTokens.DisabledContainerOpacity`. Se copian porque los dos objetos son `internal`
 * a material3 y no se pueden referenciar desde aquí.
 */
@Composable
fun appOutlinedButtonBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    width = OUTLINED_BUTTON_OUTLINE_WIDTH,
    color = if (enabled) AppColors.outlineVariant
    else AppColors.outlineVariant.copy(alpha = OUTLINED_BUTTON_DISABLED_OUTLINE_ALPHA)
)

/** `ButtonSmallTokens.OutlinedOutlineWidth` (material3 1.5.0-alpha24). */
private val OUTLINED_BUTTON_OUTLINE_WIDTH = 1.dp

/** `OutlinedButtonTokens.DisabledContainerOpacity` (material3 1.5.0-alpha24). */
private const val OUTLINED_BUTTON_DISABLED_OUTLINE_ALPHA = 0.1f

/** Tonal: `secondaryContainer` / `onSecondaryContainer` (`FilledTonalButtonTokens`). */
@Composable
fun appFilledTonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer
)

/**
 * Tonal de ACENTO fijo: `primaryFixedDim` / `onPrimaryFixed` — para un botón que necesita
 * el peso de `primary` (no el de `secondary`, que es lo que da [appFilledTonalButtonColors]) pero
 * SIN pedirle a un texto suelto que sea legible por su cuenta contra un contenedor cualquiera.
 *
 * **Por qué Fixed y no `primary` a secas** (5 sep 2026, `StatsEntry`): M3 solo garantiza contraste
 * DENTRO de un par `on-X`/`X` — nunca de `primary` contra un contenedor NEUTRO ajeno. Con
 * `PaletteStyle.Fidelity` (croma del seed sin imponer un tono propio) y una carátula de tono claro,
 * `primary` como texto sobre `surfaceContainerHighest` podía caer casi al mismo tono que el fondo:
 * dos superficies de la escala neutra distinta, un acento de la escala de color, sin ninguna
 * garantía entre ellos. `onPrimaryFixed`/`onPrimaryFixedVariant` SÍ tienen garantía — los dos se
 * calculan por `contrastCurve` contra `primaryFixedDim`, su propio fondo, así que el texto nunca
 * depende de con qué se encuentre alrededor. `primaryFixedDim` (no `primaryFixed`) porque es el
 * tono MÁS EMPEZADO/oscuro de los dos ("stronger, more emphasized tone" del spec de Fixed), el que
 * de verdad reemplaza el peso que tenía `primary`.
 *
 * **`onPrimaryFixed` (7:1) y no `onPrimaryFixedVariant` (4.5:1)**: el spec reserva el `Variant`
 * para texto SECUNDARIO junto a un título en `onPrimaryFixed` (un patrón título+subtítulo sobre la
 * misma superficie Fixed). Acá "Ver" es el ÚNICO contenido del chip — no hay ningún título del que
 * sea secundario — así que le toca el rol de mayor énfasis, que además da más margen de contraste.
 *
 * NO reintroducir `ensureContrast`/ventanas de tono a mano para este caso: el par ya viene
 * calculado por M3, cero derivación nuestra.
 *
 * **Parametrizado** (5 sep 2026): el default es la familia PRIMARY (acciones rápidas del inicio, el
 * ancla del cluster). El botón "Ver" de `StatsEntry` pasa la familia SECONDARY
 * (`secondaryFixedDim`/`onSecondaryFixed`) — mismo `*FixedDim` a T80, pero MCU le da a la paleta
 * secundaria menos croma, así que resalta un escalón menos sobre la tarjeta. Cualquier par
 * `*FixedDim`/`on*Fixed` mantiene la garantía de contraste; mezclar familias (p. ej. contenedor
 * primary con contenido secondary) la rompe.
 *
 * **Hoy no lo llama nadie** (8 sep 2026): su único consumidor era el botón "Ver" de `StatsEntry`,
 * que pasó a `primary` LLENO — ver el comentario allí, donde está por qué el riesgo no es el mismo
 * con `primary` de contenedor que de contenido. Se conserva porque el razonamiento de arriba sigue
 * VIVO: el anillo del distintivo de `StatsScreen` se apoya en él (con los roles directos, no con
 * este helper) y `CLAUDE.md` lo cita como el ejemplo canónico de la regla. Si algún día sobra de
 * verdad, lo que hay que mover antes es el razonamiento, no borrarlo con él.
 */
@Composable
fun appFixedAccentButtonColors(
    containerColor: Color = AppColors.primaryFixedDim,
    contentColor: Color = AppColors.onPrimaryFixed
) = ButtonDefaults.filledTonalButtonColors(
    containerColor = containerColor,
    contentColor = contentColor
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

/**
 * Contorno de un chip *flat*, en `outlineVariant` a 1dp — los dos valores que tabulan
 * `AssistChipTokens.FlatOutline*` y `FilterChipTokens.FlatUnselectedOutline*`, que son los mismos.
 *
 * **El borde NO viaja en `ChipColors`**, igual que no viaja en `ButtonColors` (ver
 * [appOutlinedButtonBorder]): es un parámetro aparte, y su default resuelve el token con `.value`,
 * o sea contra `MaterialTheme.colorScheme` — el esquema BASE del wallpaper, no la carátula. Sin
 * pasarlo, el contorno era el único píxel de estos chips que no seguía al álbum.
 *
 * El ancho se escribe aquí porque los objetos de tokens son `internal`.
 */
private val ChipOutlineWidth = 1.dp

/** Contorno del `AssistChip` flat (el `SortChip`). */
@Composable
fun appAssistChipBorder(): BorderStroke = BorderStroke(ChipOutlineWidth, AppColors.outlineVariant)

/**
 * Contorno del `FilterChip`: solo en reposo. Seleccionado no lleva —el relleno
 * `secondaryContainer` ya es la señal—, que es lo que hace M3 por dentro devolviendo un trazo
 * transparente.
 */
@Composable
fun appFilterChipBorder(selected: Boolean): BorderStroke? =
    if (selected) null else BorderStroke(ChipOutlineWidth, AppColors.outlineVariant)

/** Card rellena: `surfaceContainerHighest` (`FilledCardTokens.ContainerColor`). */
@Composable
fun appCardColors() = CardDefaults.cardColors(
    containerColor = AppColors.surfaceContainerHighest,
    contentColor = AppColors.onSurface
)

/** Fila de lista de M3 (`ListTokens`): contenedor `surface`, apoyos `onSurfaceVariant`. */
@Composable
fun appListItemColors() = ListItemDefaults.colors(
    containerColor = AppColors.contentSurface,
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
    // DESHABILITADO: el contenedor no cambia (una fila apagada sigue siendo parte del grupo) y el
    // contenido se atenúa con el alpha que M3 tabula. **Los cuatro de contenido hay que pasarlos**:
    // `segmentedColors` copia sobre `MaterialTheme.colorScheme.defaultSegmentedListItemColors`, así
    // que cada rol que no se pase se queda con el del tema BASE — y una fila apagada saldría con el
    // gris del wallpaper dentro de una superficie teñida por la carátula. Es la llamada PARCIAL que
    // ya mordió cuatro veces (el toggle de la cola, el slider del EQ, los switches de Ajustes y las
    // app bars), entrando esta vez por el estado que casi nunca se mira.
    disabledContainerColor = containerColor,
    disabledContentColor = AppColors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
    disabledLeadingContentColor = AppColors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
    disabledTrailingContentColor = AppColors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
    disabledOverlineContentColor = AppColors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
    disabledSupportingContentColor = AppColors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA),
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
 * Top app bar: **el par TABULADO de `AppBarTokens`, sin desviación**.
 *
 * ```
 * AppBarTokens.ContainerColor         = Surface           // AppBarTokens.kt:26
 * AppBarTokens.OnScrollContainerColor = SurfaceContainer  // :33
 * ```
 *
 * Los dos valores solo tienen sentido JUNTOS, y de paso explican cuál es el fondo de página
 * correcto: la barra vale `surface` **en reposo** —o sea que es invisible, porque *es* la página— y
 * se materializa en `surfaceContainer` **cuando hay contenido pasándole por debajo**. Con la página
 * en un nivel de contenedor la barra ya estaría flotando en reposo y ese efecto no significaría
 * nada. Por eso los parámetros existen pero **ninguna pantalla los sobrescribe**.
 *
 * Hasta el 7 sep 2026 este KDoc pedía que los detalles y estadísticas pasaran `surfaceContainerHigh`
 * "porque las barras BAJAN desde el fondo": eso era la horquilla, y valía mientras esas pantallas
 * tenían el fondo en `surfaceContainer`. Desde que todas son `surface` (4 sep) el par tabulado vale
 * tal cual y el override sobra. **No reponerlo sin cambiar antes el fondo de página.**
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
 * Hoy lo lleva SOLO el toolbar de la hoja de la cola: el del NowPlaying pasó a la variante standard
 * el 7 sep 2026 (ver [appPlayerToolbarColors]). Ahí sigue valiendo el vibrant porque esa barra es el
 * único control de una hoja cuyo cuerpo es una lista, sin un play que le dispute el énfasis. Fue
 * además **el primer sitio donde se notó** que congelar el tema dejaba componentes atrás: su contenido ya salía de [AppColors] —está
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
 * Separación tonal (HCT) del **contenedor** del toolbar del NowPlaying respecto del fondo REAL que
 * tiene debajo: el [TONAL_LAYER_DELTA] de siempre.
 *
 * Constante propia por el mismo motivo que `ROW_ACTIVE_TONE_DELTA`: calibrar cuánto se despega esta
 * barra no puede mover el resaltado de la fila que suena.
 */
private const val PLAYER_TOOLBAR_TONE_DELTA = 8.0

/**
 * Lo mismo para el relleno del toggle ACTIVO, que se deriva del MISMO fondo con el DOBLE de
 * separación en vez de derivarse del contenedor. Encadenar fondo → barra → activo sería apilar tres
 * capas con la regla "aléjate del extremo", y un fondo de tono medio hace que el segundo paso
 * invierta el sentido y devuelva el activo al color de la barra (lo que documenta
 * `rememberTonalLayerColors`); midiendo los dos contra el MISMO fondo, la dirección se decide una
 * sola vez.
 */
private const val PLAYER_TOOLBAR_ACTIVE_TONE_DELTA = PLAYER_TOOLBAR_TONE_DELTA * 2

/**
 * Floating toolbar de acciones del NowPlaying: variante **STANDARD**, con el contenedor DERIVADO de
 * [background] (el color real del fondo del reproductor a la altura de la barra).
 *
 * **Por qué standard y no [appVibrantFloatingToolbarColors]** (7 sep 2026, decisión del usuario). El
 * spec reparte las dos variantes por ÉNFASIS: *standard* es "a low-emphasis color scheme best used
 * for focusing attention on the body content" y *vibrant* "a high-emphasis color scheme that draws
 * attention to the controls". En esta pantalla el contenido es la carátula y el control enfático es
 * el PLAY, así que una barra de acciones secundarias en `primaryContainer` —la misma familia que el
 * play, y la superficie de color más grande de la mitad de abajo— le disputaba el énfasis al único
 * botón que debía tenerlo.
 *
 * La prueba de que competía es lo que costaba sostenerlo: el relleno del toggle activo se calculaba
 * interpolando el camino tonal de la barra al play con dos fracciones por sentido, un piso de
 * contraste contra la barra y una condición extra para que el glifo no se invirtiera — cien líneas
 * cuyo único trabajo era impedir que un toggle encendido pesara más que el play. Con la barra fuera
 * de la familia primaria ese problema no existe y el activo es, como en la biblioteca, un escalón
 * más del mismo fondo. **NO reintroducir esa interpolación.**
 *
 * **Pero el standard TAMPOCO puede ser su token tal cual**, y ése fue el motivo real por el que en
 * su día se eligió vibrant: `FloatingToolbarTokens.StandardContainerColor` es `surfaceContainer`,
 * que es EXACTAMENTE el fondo del reproductor en modo sólido, y en modo degradado el extremo de
 * abajo es `surface`, a un peldaño. La barra desaparecía. Es el mismo agotamiento de la escala
 * neutra que ya obligó a derivar el toolbar de la biblioteca, y la salida es la misma: un rol fijo
 * no puede prometer separación porque su distancia al fondo la decide el ESTILO DE PALETA; derivar
 * del fondo REAL sí, y da lo mismo en los dos temas y en los dos modos de fondo sin una rama por
 * caso.
 *
 * Hue y croma salen de `secondaryContainer` —la familia del fondo del reproductor, no la del play—,
 * así que la barra se lee como "la página, un escalón por encima". El contenido es `onSurface`, que
 * es lo que el propio M3 tabula para el standard (`contentColorFor(surfaceContainer)`) y de paso el
 * mismo color que prev/next: los iconos del toolbar y los del transporte dejan de ser dos familias.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appPlayerToolbarColors(background: Color) = FloatingToolbarDefaults.standardFloatingToolbarColors(
    toolbarContainerColor = playerToolbarLayer(background, PLAYER_TOOLBAR_TONE_DELTA),
    toolbarContentColor = AppColors.onSurface,
    fabContainerColor = AppColors.primaryContainer,
    fabContentColor = AppColors.onPrimaryContainer
)

/**
 * Relleno del toggle ACTIVO de esa barra.
 *
 * Sale del MISMO [background] que el contenedor, no del contenedor: así la distancia
 * barra ↔ activo es exactamente la misma que fondo ↔ barra en cualquier paleta, que es lo que
 * `rememberTonalLayerColors` documenta y lo que el toolbar de la biblioteca ya hace.
 *
 * Coincide además con lo que M3 tabula para el caso simétrico: en la barra vibrant el botón
 * seleccionado es `surfaceContainer`/`onSurface` (`FloatingToolbarTokens.VibrantButtonSelected*`),
 * o sea un escalón de SUPERFICIE que se sale de la familia de la barra — nunca un acento más vivo.
 */
@Composable
fun appPlayerToolbarActiveContainer(background: Color): Color =
    playerToolbarLayer(background, PLAYER_TOOLBAR_ACTIVE_TONE_DELTA)

/** Las dos capas del toolbar del reproductor: el mismo fondo, dos separaciones. */
@Composable
private fun playerToolbarLayer(background: Color, delta: Double): Color {
    val role = AppColors.secondaryContainer
    return remember(background, role, delta) { tonalLayerContainer(background, role, delta) }
}

/**
 * Toggle button tonal: apagado `secondaryContainer` / `onSecondaryContainer` (el default de
 * `TonalButtonTokens`, el estado recesivo), encendido **`primary` / `onPrimary`**.
 *
 * **El marcado se aparta del spec** (5 sep 2026, device: selector de periodo de Estadísticas). M3
 * tabula el encendido como `secondary` / `onSecondary`, un salto que confía en la distancia
 * `secondary` ↔ `secondaryContainer` — y esa distancia la fija el ESTILO DE PALETA: con una carátula
 * casi acromática los dos caen a pocos tonos y el activo no se leía ("casi ni se ve").
 *
 * **`primary` y no `primaryFixedDim`** (probado y descartado en device el mismo día): el toggle no
 * se compara contra una superficie neutra —donde `primary` puede empalidecer con Fidelity y por eso
 * el disco de la racha usa Fixed— sino contra sus HERMANOS `secondaryContainer` (~T90, poco croma).
 * `primaryFixedDim` (T80) queda a ~10 pasos de tono de ellos y con croma parecido → seguía sin
 * leerse. `primary` (T40, croma del seed) diverge de un contenedor T90 poco cromático en tono Y en
 * croma, en cualquier estilo de paleta, Monochrome incluido (gris oscuro vs gris claro). El par
 * `primary`/`onPrimary` tiene contraste tabulado por M3.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appTonalToggleButtonColors() = ToggleButtonDefaults.tonalToggleButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer,
    checkedContainerColor = AppColors.primary,
    checkedContentColor = AppColors.onPrimary
)

/**
 * Floating toolbar del selector de la biblioteca cuando vive abajo (`LibraryBottomToolbar`):
 * **el token tal cual, `FloatingToolbarTokens.StandardContainerColor` = `surfaceContainer`**
 * (7 sep 2026, decisión del usuario en device).
 *
 * **No es el vibrant** ([appVibrantFloatingToolbarColors], hoy solo el de la hoja de la cola):
 * allí el toolbar es la pieza protagonista de una hoja teñida, y aquí flota a un palmo del
 * MiniPlayer, que ya lleva `primaryContainer` — dos píldoras del mismo color apiladas se leen como
 * una sola pieza partida por la mitad.
 *
 * **Y `surfaceContainerHighest`, NO el `surfaceContainer` del token** (7 sep 2026, visto en device:
 * *"no se ve que haya una barra ahí"*). El token está bien en el mundo de M3 y mal en el nuestro, y
 * la cuenta lo dice — tema oscuro: página 6, **toolbar 12**, filas 17. La barra salía **más oscura
 * que las filas sobre las que flota**, y en M3 la elevación se expresa con el NIVEL de contenedor,
 * así que una pieza flotante tiene que estar un nivel POR ENCIMA de lo que tiene debajo. Con el
 * token quedaba un nivel por debajo: en la escala de elevación el toolbar estaba *bajo* el contenido
 * que debía sobrevolar, y por eso se leía como un hueco. El token asume el list item plano de M3
 * (`surface`, el nivel más bajo); nuestras filas son tarjetas en `surfaceContainerHigh`. Misma clase
 * de error que la horquilla invertida del 29 ago: una regla escrita con nombres de rol que se da
 * vuelta cuando cambia el suelo.
 *
 * **Estuvo DERIVADO del fondo con `tonalLayerContainer` y ya no lo está.** El motivo de derivar era
 * real mientras duró: hasta el 4 sep el fondo de página ERA `surfaceContainer`, o sea exactamente el
 * token, y la barra desaparecía contra la pantalla. Con la página de vuelta en `surface` la escala
 * neutra vuelve a tener niveles que usar y no hay nada que derivar.
 *
 * ⚠️ **Colisión asumida**: `surfaceContainerHighest` es el color de `AppMenuPopup`, cuyo KDoc apoya
 * su elección en que "las superficies sobre las que un menú puede caer van de `surface` a
 * `surfaceContainerHigh`". El toolbar rompe esa premisa; en la práctica no se abre un menú sobre la
 * barra de abajo, pero si algún día se abre, ése es el conflicto.
 *
 * **Lo que la derivación NO podía resolver, y por eso se abandonó**: el toolbar flota sobre
 * contenido distinto en cada pestaña —filas en Canciones, carátulas de carrusel en Inicio—, así que
 * no existe UN fondo contra el que medir. Derivar contra la página lo acercaba a las filas; derivar
 * contra las filas no significa nada en Inicio, donde debajo hay imágenes. Un rol fijo no promete
 * separación en todos los casos, pero tampoco lo hace una derivación cuando el suelo cambia de
 * naturaleza: al menos el rol es tabulado, estable y el mismo que la barra de navegación.
 *
 * De ahí que este helper y [appLibraryToolbarItemColors] ya **no reciban `background`**.
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
 * **Contenedor del destino ACTIVO del selector de la biblioteca: `primary`** (con
 * [onActiveDestination] de contenido), en las CUATRO presentaciones — fila de pestañas, toolbar de
 * abajo, rail y barra del estilo moderno.
 *
 * Es una desviación DELIBERADA del spec, que tabula `secondaryContainer`/`onSecondaryContainer` en
 * `NavigationBarTokens` y `NavigationRailColorTokens`. Dos motivos, los dos vistos en device el
 * 7 sep 2026:
 *
 *  1. **Con 6 destinos no cabe ninguna etiqueta** (la regla del toolbar mide con la más larga: en
 *     393dp quedan ~41dp y no entra ninguna). El glifo ya se rellena al activarse, así que de los
 *     tres canales que dicen "estás aquí" —etiqueta, forma, color— quedan dos, y el color carga el
 *     peso. `secondaryContainer` es un rol de croma bajo: en una paleta apagada el indicador era un
 *     borrón apenas más claro que la barra.
 *  2. **Desde que el toolbar subió a `surfaceContainerHighest`, en tema CLARO los dos caen en tono
 *     ~90**, así que el indicador solo se separaría por CROMA — y con una carátula acromática, por
 *     nada. `primary` diverge en tono Y en croma en cualquier estilo de paleta.
 *
 * Mismo razonamiento y mismo desenlace que el selector de periodo de Estadísticas el 5 sep 2026
 * (ver [appTonalToggleButtonColors]): allí M3 también tabulaba el par secundario y en device no se
 * leía. Encaja además con la regla de la app: **`primary` es lo que NAVEGA**.
 *
 * **Las cuatro van juntas o ninguna**, y por eso esto es UNA propiedad y no cuatro literales: son
 * proyecciones del mismo `pagerState.currentPage`, y que el activo sea de un color arriba y de otro
 * abajo es justo el fallo que esto impide. El par `primary`/`onPrimary` trae su contraste tabulado,
 * así que ninguno de los cuatro necesita `ensureContrast`.
 */
internal val activeDestinationContainer: Color
    @Composable @ReadOnlyComposable get() = AppColors.primary

/** Contenido sobre [activeDestinationContainer]: el `on…` de su par, sin nada que calcular. */
internal val onActiveDestination: Color
    @Composable @ReadOnlyComposable get() = AppColors.onPrimary

/**
 * Destino del toolbar de la biblioteca (`LibraryBottomToolbar`), montado sobre `ToggleButton`.
 *
 * **Exactamente los mismos cuatro roles que la fila de pestañas, el rail y la barra de navegación**
 * —activo [ACTIVE_DESTINATION_ROLE], inactivo `onSurfaceVariant` sobre contenedor TRANSPARENTE, que
 * es lo que deja ver el material del toolbar—. Las cuatro presentaciones del selector son
 * proyecciones del mismo `pagerState.currentPage` y no pueden divergir en color.
 *
 * **El indicador dejó de derivarse el 7 sep 2026, junto con el contenedor.** Mientras el contenedor
 * salía del fondo, el indicador tenía que salir del MISMO fondo con el doble de separación —pintarlo
 * con el rol crudo lo habría dejado a la distancia que la paleta quisiera— y su contenido necesitaba
 * un `ensureContrast` porque al mover el tono se perdía el par `on*` de M3. Con el contenedor en un
 * rol tabulado esas dos muletas sobran: el par `primary`/`onPrimary` trae su contraste del spec.
 *
 * **NO se usa `tonalToggleButtonColors`** aunque el nombre suene al caso: ése pinta el contenedor
 * también en reposo (`secondaryContainer` apagado → `secondary` encendido), o sea seis píldoras
 * tonales dentro de otra píldora tonal.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appLibraryToolbarItemColors(): ToggleButtonColors = ToggleButtonDefaults.toggleButtonColors(
    containerColor = Color.Transparent,
    contentColor = AppColors.onSurfaceVariant,
    checkedContainerColor = activeDestinationContainer,
    checkedContentColor = onActiveDestination
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
    selectedIconColor = onActiveDestination,
    // La etiqueta va DEBAJO del indicador, sobre la barra, así que lleva el color del indicador y no
    // su `on…`. El token dice `secondary` porque el indicador tabulado es `secondaryContainer`; al
    // moverlo a `primary` la etiqueta lo sigue (ver [activeDestinationContainer]).
    selectedTextColor = activeDestinationContainer,
    indicatorColor = activeDestinationContainer,
    unselectedIconColor = AppColors.onSurfaceVariant,
    unselectedTextColor = AppColors.onSurfaceVariant
)

/**
 * Ítem de la barra de navegación de abajo (los tres destinos del estilo moderno).
 *
 * **Los MISMOS valores que [appNavigationRailItemColors]**, y no por comodidad: `NavigationBarTokens`
 * y `NavigationRailColorTokens` tabulan exactamente los mismos roles para los mismos papeles
 * —`onSecondaryContainer` al glifo activo, `secondary` a su etiqueta, `secondaryContainer` al
 * indicador y `onSurfaceVariant` a lo inactivo—, que es lo esperable: la barra y el rail son el
 * mismo control en dos ejes, y en esta app además son proyecciones del mismo `pagerState`. Si un día
 * divergen, tendrán que divergir los dos a la vez.
 *
 * Hay que pasarlos: sin esto los toma de `MaterialTheme.colorScheme`, que en esta app NO lleva el
 * color del álbum, y la barra quedaría gris dentro de una app teñida.
 */
@Composable
fun appShortNavigationBarItemColors() = ShortNavigationBarItemDefaults.colors(
    selectedIconColor = onActiveDestination,
    selectedTextColor = activeDestinationContainer,
    selectedIndicatorColor = activeDestinationContainer,
    unselectedIconColor = AppColors.onSurfaceVariant,
    unselectedTextColor = AppColors.onSurfaceVariant
)
