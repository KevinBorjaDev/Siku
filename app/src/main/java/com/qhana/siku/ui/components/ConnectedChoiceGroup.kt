package com.qhana.siku.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.appTonalToggleButtonColors
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appMenuItemColors

/**
 * Cómo reparte el ancho un [ConnectedChoiceGroup]. Ver el `@param width` de esa función: elegir mal
 * el modo es lo que recorta etiquetas, así que es la primera palanca que hay que mirar cuando una
 * sale con elipsis.
 */
enum class ChoiceGroupWidth {
    /** Ocupa el ancho del padre y lo reparte **a partes IGUALES**. Solo vale con etiquetas de
     *  longitud parecida: la más larga recibe el mismo hueco que la más corta y se recorta. */
    Equal,

    /** Cada botón se ciñe a su CONTENIDO y el grupo mide lo que sumen. Nunca recorta; a cambio no
     *  llega a los bordes, así que el caller decide dónde lo pone. */
    Content,

    /** Ocupa el ancho del padre y lo reparte **en PROPORCIÓN a lo que cada etiqueta necesita**:
     *  nadie baja de su ancho natural relativo, y el sobrante se estira sin recortar a nadie. Es el
     *  modo para un juego de etiquetas DESIGUAL que además tenga que llegar a los bordes. */
    Proportional
}

/**
 * Selección exclusiva de una opción entre varias: **connected button group**.
 *
 * Sustituye a `SegmentedButton` / `SingleChoiceSegmentedButtonRow` en toda la app. El spec de M3
 * Expressive los retiró de las recomendaciones y señala este grupo como su reemplazo — misma
 * función, diseño actualizado: el seleccionado morfea de píldora a rectángulo redondeado
 * (`checkedShape`), los extremos redondean hacia afuera y los vecinos se comprimen al presionar
 * (`animateWidth`), cosas que el segmentado no hace.
 *
 * Existe como componente y no copiado en cada pantalla porque el boilerplate del `ButtonGroup` en
 * alpha24 no es trivial —`overflowIndicator` obligatorio, `content` que es un `ButtonGroupScope` y
 * no un `@Composable`, y las shapes distintas para primero / medio / último— y estaba a punto de
 * repetirse en los cuatro sitios que usaban el segmentado.
 *
 * @param labelFor etiqueta de cada opción; se usa igual en el botón y en el menú de overflow.
 * @param iconFor símbolo opcional. Sin icono el grupo queda más compacto, que es lo que conviene
 *        cuando es un ajuste secundario dentro de una pantalla que ya tiene otro grupo.
 * @param width cómo se reparte el ancho — ver [ChoiceGroupWidth]. Es la palanca principal para que
 *        DOS grupos en la misma pantalla no se lean como el mismo control (ver el EQ: el conmutador
 *        de sección va a ancho completo y el selector de bandas, ceñido y bajo).
 *
 *        **Con etiquetas de longitud DESIGUAL, [ChoiceGroupWidth.Equal] NO vale**: le da a la más
 *        larga el mismo hueco que a la más corta, y la larga se recorta. Pasó con el selector de
 *        ReplayGain ("Desactivado" contra "Por pista" y "Por álbum"): en 360dp cada botón se
 *        quedaba en ~76dp de texto útil y la primera etiqueta necesitaba ~82. Y con las cinco del
 *        periodo de estadísticas (D · S · M · A · Siempre), el juego más desigual de la app.
 *        Ahí se elige entre [ChoiceGroupWidth.Content] —cada uno mide lo suyo— y
 *        [ChoiceGroupWidth.Proportional], que además llega a los bordes.
 *
 *        **`Proportional` mide el texto de verdad** (`rememberTextMeasurer` con
 *        `MaterialTheme.typography.labelLarge`, que es el estilo que el propio `ToggleButton`
 *        aplica a su etiqueta — ToggleButton.kt:150 de material3 1.5.0-alpha24) y le suma el
 *        padding y, si lo hay, el icono. O sea que el peso NO es una proporción escrita a mano: es
 *        el ancho que ese botón pediría si se ciñera. Sube solo con la escala tipográfica del
 *        sistema y con una traducción más larga.
 *
 *        Ojo con lo que se pierde en los dos modos de ancho completo: el `overflowIndicator` que el
 *        `ButtonGroup` ya trae solo puede entrar cuando los botones se ciñen — el `weight` lo
 *        anula, porque con weight siempre "caben".
 * @param buttonHeight alto de cada botón; null = el del componente.
 * @param colors override para superficies que NO son del scheme. Lo necesita la pantalla de letras,
 *        que se pinta sobre el color del álbum y calcula el contenido por luminancia; en el resto
 *        de la app el default tonal es el correcto y no hay que tocarlo.
 * @param iconOnly oculta la etiqueta y deja SOLO el icono — la versión compacta para donde no hay
 *        lugar para texto (una topbar, junto a otro botón de icono). Exige [iconFor]: sin icono no
 *        queda nada que mostrar. La etiqueta de [labelFor] se sigue usando, pero como
 *        `contentDescription` del botón y como texto del ítem si el grupo llega a desbordar al
 *        menú — ahí SIEMPRE hay lugar para texto.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelFor: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconFor: ((T) -> String)? = null,
    width: ChoiceGroupWidth = ChoiceGroupWidth.Equal,
    buttonHeight: Dp? = null,
    contentPadding: PaddingValues? = null,
    colors: ToggleButtonColors? = null,
    iconOnly: Boolean = false
) {
    ButtonGroup(
        // Con los dos modos de ancho completo las opciones reparten por weight y no desbordan
        // nunca; ceñidas, el overflow sí puede entrar en juego con etiquetas largas o fontScale
        // alto. En cualquier caso el API lo exige.
        overflowIndicator = { menuState ->
            FilledTonalIconButton(colors = appFilledTonalIconButtonColors(), onClick = { menuState.show() }) {
                MaterialSymbol(icon = "more_horiz", size = OverflowIconSize)
            }
        },
        modifier = if (width == ChoiceGroupWidth.Content) modifier else modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            // `iconFor` se puede resolver aquí porque NO es composable; [labelFor] sí lo es y se
            // resuelve DENTRO de cada lambda. El `content` de `ButtonGroup` es un
            // `ButtonGroupScope.() -> Unit` normal —no un `@Composable`—, así que este bloque no
            // es contexto composable; `buttonGroupContent` y `menuContent` sí lo son.
            val icon = iconFor?.invoke(option)
            customItem(
                buttonGroupContent = {
                    val label = labelFor(option)
                    val source = remember { MutableInteractionSource() }
                    val padding = contentPadding ?: (
                        if (iconOnly) IconOnlyContentPadding else ToggleButtonDefaults.ContentPadding
                    )
                    // Peso del reparto PROPORCIONAL: el ancho que este botón pediría si se ciñera,
                    // o sea etiqueta + padding + (icono y su hueco, si lleva). Se mide con el mismo
                    // estilo que el `ToggleButton` aplica a su `Text`, así que no es una estimación.
                    // Se recalcula solo cuando cambia algo de lo que depende — un `remember` sin
                    // claves congelaría el reparto al subir la escala tipográfica del sistema.
                    // Los cuatro se leen SIEMPRE, nunca dentro del `if`: son lecturas con estado
                    // propio y meterlas en una rama las haría nacer y morir con el modo
                    // (convención 15). Lo condicional es el CÁLCULO, que sí puede vivir dentro.
                    val measurer = rememberTextMeasurer()
                    val labelStyle = MaterialTheme.typography.labelLarge
                    val density = LocalDensity.current
                    val direction = LocalLayoutDirection.current
                    val proportion = remember(width, label, labelStyle, padding, density, direction, icon, iconOnly) {
                        if (width != ChoiceGroupWidth.Proportional) 1f
                        else {
                            val extras = padding.calculateLeftPadding(direction) +
                                padding.calculateRightPadding(direction) +
                                if (icon != null && !iconOnly) IconGap else 0.dp
                            val iconPx = if (icon != null) with(density) { IconSize.toPx() } else 0f
                            // Sin etiqueta visible, el ancho que este botón pediría es solo icono +
                            // padding — medir el texto igual lo sobre-ensancharía. `.toFloat()`
                            // explícito: las dos ramas tienen que dar el MISMO tipo (Int y Float no
                            // se unifican solos en un `if`, a diferencia de `+`, que sí los mezcla).
                            val labelPx = if (iconOnly) 0f else measurer.measure(label, labelStyle).size.width.toFloat()
                            labelPx + with(density) { extras.toPx() } + iconPx
                        }
                    }
                    ToggleButton(
                        checked = selected == option,
                        onCheckedChange = { onSelect(option) },
                        enabled = enabled,
                        interactionSource = source,
                        colors = colors ?: appTonalToggleButtonColors(),
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = padding,
                        modifier = Modifier
                            .then(
                                if (width == ChoiceGroupWidth.Content) Modifier
                                else Modifier.weight(proportion)
                            )
                            .then(if (buttonHeight != null) Modifier.height(buttonHeight) else Modifier)
                            .animateWidth(source)
                            // Sin texto visible no hay nada que anuncie el botón — la etiqueta de
                            // [labelFor] pasa a describirlo.
                            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier)
                    ) {
                        if (icon != null) {
                            MaterialSymbol(icon = icon, size = IconSize)
                            if (!iconOnly) Spacer(Modifier.width(IconGap))
                        }
                        if (!iconOnly) {
                            // `Ellipsis` como RED DE SEGURIDAD, no como solución: con `maxLines = 1` a
                            // secas el texto se cortaba a hachazo —"Desactivado" salía "Desactivad"—,
                            // que se lee como una errata y no como un truncamiento. Si una etiqueta
                            // llega aquí recortada es que el grupo está mal dimensionado (ver
                            // [fillWidth]); esto solo evita que el fallo parezca otra cosa.
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                menuContent = { state ->
                    val label = labelFor(option)
                    // Items CLÁSICOS (sin `shapes`) a propósito: el contenedor del overflow lo pone
                    // `ButtonGroup` con un `DropdownMenu` normal, y los items que él mismo genera
                    // tampoco llevan forma.
                    DropdownMenuItem(
                        colors = appMenuItemColors(),
                        text = { Text(label) },
                        leadingIcon = icon?.let { { MaterialSymbol(icon = it) } },
                        enabled = enabled,
                        onClick = {
                            onSelect(option)
                            state.dismiss()
                        }
                    )
                }
            )
        }
    }
}

private val IconSize = 18.sp
private val OverflowIconSize = 18.sp
private val IconGap = 8.dp

/**
 * Padding de un botón [ConnectedChoiceGroup] con `iconOnly = true`: CUADRADO (mismo valor en los
 * cuatro lados), porque sin etiqueta el botón deja de ser una píldora horizontal y pasa a
 * comportarse como un icon button — igual criterio que `IconButtonDefaults`, que tampoco reparte
 * distinto el padding horizontal y vertical.
 */
private val IconOnlyContentPadding = PaddingValues(12.dp)
