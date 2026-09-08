package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import com.qhana.siku.ui.theme.appFilledTonalButtonColors

/**
 * Tope de caracteres del label. Las etiquetas FIJAS lo cumplen escritas; el que no puede
 * garantizarlo es el de GÉNERO, cuyo texto es el tag crudo del archivo ("Progressive Metal/Fusion")
 * y no lo elige nadie de este lado.
 */
private const val LABEL_MAX_CHARS = 20

/**
 * Ancho medio de un glifo en fracción del tamaño de fuente. Aproximación (la Google Sans Flex no
 * es monoespaciada), suficiente porque lo que se busca es el punto donde CORTAR, no medir el texto.
 */
private const val LABEL_AVG_CHAR_EM = 0.5f

/**
 * `opticalSize` del glifo. Coincide con su tamaño visual (20dp, [ButtonDefaults.ExtraSmallIconSize])
 * y **es a propósito el mismo que el de [MenuItemIcon]**: cada combinación de ejes es un `Typeface`
 * distinto, o sea un parseo del archivo, y reusar el 20 aprovecha las variantes que
 * `MaterialSymbolFont.preload` ya deja construidas al arrancar.
 */
private const val QUICK_ACTION_ICON_OPTICAL_SIZE = 20

/**
 * Acción rápida del inicio / pestaña Todas: **botón tonal XSmall de M3 Expressive**, con glifo y
 * etiqueta.
 *
 * **Fue un `AssistChip` hasta el 28 ago 2026**, y el cambio lo pidió el usuario en device. Los dos
 * miden 32dp de alto, así que no es una cuestión de tamaño sino de QUÉ ES la pieza: un chip
 * *describe o acota* (el conteo, el orden, los filtros de origen — todo eso sigue siendo chip en la
 * fila de Todas), mientras que estas entradas **arrancan reproducción**, que es un verbo. Y la
 * anatomía lo dice sola: la píldora (`CornerFull`) en vez del rectángulo de 8dp del chip, y el
 * **morph al presionar** hacia `CornerSmall`, que un `AssistChip` no tiene.
 *
 * Todas van con el MISMO color (`FilledTonalButton` / `secondaryContainer`). Las principales
 * (`anchor = true`) se distinguen por la FORMA: rectángulo redondeado (`shapes.medium`, 12dp) en vez
 * de la píldora (`CornerFull`) que llevan los géneros y las acciones por origen. El color no separa
 * las dos capas —se probó y se descartó, ver `CLAUDE.md`—; la forma sí, sin depender de la paleta.
 *
 * Todo lo dimensionable sale de [ButtonDefaults] a partir de UNA altura
 * ([ButtonDefaults.ExtraSmallContainerHeight]) — relleno interior, glifo y separación —, así que
 * cambiar de talla es cambiar esa línea y no cinco números sueltos.
 *
 * Vive en `components` y no en `HomeScreen` desde el 22 ago 2026, cuando la pestaña **Todas**
 * estrenó los suyos: son la MISMA acción con el mismo aspecto en dos superficies, y escritos dos
 * veces nada obligaría a que el relleno, el tamaño del glifo o el recorte del label coincidieran.
 */
@Composable
fun QuickActionButton(
    icon: String,
    label: String,
    enabled: Boolean = true,
    fill: Boolean = false,
    anchor: Boolean = false,
    onClick: () -> Unit
) {
    val size = ButtonDefaults.ExtraSmallContainerHeight
    // El tope se calcula en ANCHO y a partir del tamaño de fuente VIVO, no como un dp fijo: así
    // sube con la escala tipográfica del sistema y el botón sigue mostrando los mismos ~20
    // caracteres en vez de recortar antes. Recortar el String sería peor —el corte debe caer donde
    // la fuente diga, y la elipsis es cosa del layout.
    val maxLabelWidth = with(LocalDensity.current) {
        (MaterialTheme.typography.labelLarge.fontSize * LABEL_MAX_CHARS * LABEL_AVG_CHAR_EM).toDp()
    }
    // El token viene en dp y [MaterialSymbol] dimensiona en sp —es una fuente variable, no un
    // vectorial—, así que se convierte con la densidad en vez de escribir el número a ojo.
    val iconSize = with(LocalDensity.current) { ButtonDefaults.iconSizeFor(size).toSp() }
    // Píldora + morph a `CornerSmall` bajo el dedo (el default de esta talla). Las principales parten
    // de un rectángulo redondeado y el morph las lleva un paso más cuadradas.
    val shapes = if (anchor) {
        ButtonDefaults.shapes(
            shape = MaterialTheme.shapes.medium,
            pressedShape = MaterialTheme.shapes.small
        )
    } else {
        ButtonDefaults.shapesFor(size)
    }
    FilledTonalButton(
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(size),
        enabled = enabled,
        colors = appFilledTonalButtonColors(),
        contentPadding = ButtonDefaults.contentPaddingFor(size, hasStartIcon = true)
    ) {
        // Sin color explícito: el glifo hereda el contentColor del botón (y se atenúa solo cuando
        // está deshabilitado).
        MaterialSymbol(
            icon,
            size = iconSize,
            fill = fill,
            opticalSize = QUICK_ACTION_ICON_OPTICAL_SIZE
        )
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(size)))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxLabelWidth)
        )
    }
}
