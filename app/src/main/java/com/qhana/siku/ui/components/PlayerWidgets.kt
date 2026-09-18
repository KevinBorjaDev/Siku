package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import kotlin.math.abs

import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.ui.theme.AppColorScheme
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appSpatialSpec

/** `AssistChipTokens.ContainerHeight`: el alto de cualquier chip de M3. */
private val ChipHeight = 32.dp

/** Aire a los lados, el que coincide con el del label de un `AssistChip`. */
private val ChipHorizontalPadding = 16.dp

/**
 * Contador: "777 canciones", "12 álbumes", la duración de una lista.
 *
 * **Es un CHIP, con la anatomía de chip de M3** —rectángulo de esquina pequeña y 32dp de alto
 * (`AssistChipTokens.ContainerShape` y `ContainerHeight`)— y no un badge. Se probó como `Badge` el
 * 29 ago 2026, con el argumento de que un contador no es interactivo y un chip sí: es cierto en el
 * papel, pero en device no funcionó. El badge de M3 está dimensionado para vivir DENTRO de un
 * `BadgedBox`, superpuesto a un icono, y suelto en una fila —al lado del chip de orden, que mide el
 * doble— se lee como una etiqueta huérfana en vez de como el par del otro. **En una fila, las
 * piezas que se leen juntas tienen que compartir escala**, aunque una de ellas no se toque.
 *
 * Lo que SÍ sobrevive de aquel intento es su ASPECTO: píldora y contenedor de acento, o sea que a
 * escala de chip sigue leyéndose como un distintivo y no como un control.
 *
 * **El color es el TECHO de la escala neutra (`surfaceContainerHighest`), y lo que lo separa de sus
 * vecinos es el RELLENO, no el matiz** (7 sep 2026). ⚠️ **PENDIENTE de A/B en device.**
 *
 * El problema real es que esta fila tiene todos sus slots ocupados **y ocupados con significado**:
 *
 *  - sin relleno + `onSurfaceVariant` + borde `outline` = filter chips en reposo y el `SortChip`,
 *    o sea "control apagado";
 *  - `secondaryContainer` + `onSecondaryContainer` = filter chip **seleccionado**, o sea "este
 *    filtro está activo" — y también el relleno de los `QuickActionButton`.
 *
 * Un contador pintado de cualquiera de los dos MIENTE: parece un control apagado o parece un filtro
 * encendido. Eso es lo que llevó al terciario el 29 ago… **por disponibilidad, no por ajuste** —"el
 * único acento que quedaba libre"—, y el precio se vio en device el 7 sep: en TonalSpot la paleta
 * terciaria es el matiz primario **+60°**, así que con una carátula teal este chip era lo ÚNICO
 * morado de toda la biblioteca. El matiz es la señal perceptual más fuerte que hay y se estaba
 * gastando en la pieza más pasiva de la fila, mientras los botones —lo único que hace algo— iban en
 * la familia de todo lo demás. Es el mismo reparto invertido que sacó los pares terciarios de
 * `playerRolePairs` el mismo día.
 *
 * La salida fue **liberar un slot en vez de inventar un color**: el `SortChip` estaba relleno en
 * `surfaceContainerHighest` apartándose del default de `AssistChip` —que en M3 es *outlined* y sin
 * superficie— y volvió a él. Con eso los tres controles de la fila (orden y los dos filtros de
 * origen) comparten contorno, y este contador queda como **la única pieza rellena de la fila**, o
 * sea la única que no se lee como algo que se toca. Que es la verdad: es la única que no se toca.
 *
 * Con esto vuelve a cumplirse la regla general —*el acento marca lo accionable, lo que solo describe
 * va en neutro*— de la que esto era la excepción, y de paso se resuelve el aviso que quedaba escrito
 * de agosto ("el neutro puro desaparecía entre los chips de filtro"): desaparecía porque el chip de
 * orden ocupaba el mismo slot, no porque el neutro no sirviera.
 *
 * ⚠️ **Las dos mitades no se pueden revertir por separado.** Si el `SortChip` vuelve a rellenarse,
 * este contador tiene que salir del neutro — y ahí no queda más que gastar un matiz, que es el
 * `tertiaryContainer` que esto sustituye.
 *
 * Sigue siendo SÓLIDO y no un chip *flat* con borde, aunque eso sea lo que M3 da por defecto: dos de
 * estos viven sobre la foto del header de artista, y encima de una imagen un contenedor transparente
 * no se lee.
 */
@Composable
fun CountChip(
    modifier: Modifier = Modifier,
    containerColor: Color = AppColors.surfaceContainerHighest,
    content: @Composable RowScope.() -> Unit
) {
    AppSurface(
        modifier = modifier,
        // Píldora, que es la forma del badge de M3 (`BadgeTokens.LargeShape` = `CornerFull`) y lo
        // que le conserva ese aspecto. No se confunde con un botón —que también es píldora— porque
        // el color los separa, y no se confunde con el chip de orden —que es rectangular— tampoco.
        shape = CircleShape,
        color = containerColor
    ) {
        Row(
            // Alto FIJO al token en vez de padding vertical: así mide exactamente lo mismo que el
            // `AssistChip` de al lado, que es el punto de todo esto.
            modifier = Modifier
                .height(ChipHeight)
                .padding(horizontal = ChipHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // `content` lleva receptor `RowScope`, así que se invoca DENTRO de la lambda del Row.
            // `ProvideTextStyle` no emite nodo de layout: los `weight` del caller siguen valiendo.
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/**
 * NO reintroducir: reencuadrar el par `on*Container` de M3 para devolverle croma al glifo.
 *
 * Se implementó y se REVIRTIÓ el 29 jul 2026, con la medición hecha. El punto de partida era real:
 * `onSecondaryContainer` pasa de tono 73.7 con saturación 0.39 en el estilo "fiel a la carátula" a
 * **97.5 con 0.05** en "vibrante", así que los iconos de prev/next perdían el color de la paleta y
 * quedaban como lo más brillante del transporte, por encima del play. La función acercaba el glifo al
 * contenedor hasta el tono mínimo que aún cumplía 4.5:1 (97.5 → 86.5), lo que recupera croma porque
 * cerca del blanco el gamut sRGB no admite color.
 *
 * Por qué se cayó: **el glifo de un botón no se juzga contra su contenedor, se juzga contra el resto
 * de la pantalla**. En "vibrante" el texto, los chips y los títulos son blancos —cada uno con su par
 * `on*` de M3—, así que un icono crema entre ellos se lee como apagado, no como más colorido. Y
 * llevar el mismo tratamiento al texto no era opción: en texto pequeño apurar el mínimo AA es peor
 * que perder croma. O se aplicaba a todo o a nada, y la coherencia manda: se deja que M3 decida
 * uniformemente en toda la pantalla.
 */

// ============== ALFAS TABULADAS Y PARES DE APOYO ==============

internal const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * Opacidad del CONTENEDOR de un componente deshabilitado. 0.1 = lo que M3 tabula
 * (`*IconButtonTokens.DisabledContainerOpacity`), distinta del 0.38 del contenido: el relleno solo
 * tiene que insinuar la forma, no leerse.
 *
 * Existe porque los helpers de `AppComponentColors` **tienen que pasar los cuatro roles**: lo que no
 * se pasa se resuelve contra `MaterialTheme.colorScheme`, que es el esquema BASE y no lleva el color
 * del álbum. Ver la convención de la llamada parcial.
 */
internal const val DISABLED_CONTAINER_ALPHA = 0.1f

/**
 * Pista NO recorrida de una barra de progreso teñida con el acento. El mismo color que el tramo
 * recorrido, muy rebajado: es lo que hace que la barra se lea como una pieza —un canal y su
 * relleno— en vez de como dos colores distintos.
 *
 * Estaba repetido en los banners de sync y en las barras del reproductor. Ojo con confundirlo con el
 * `0.2f` que algunos callers pasan al construir `inactiveTrackColor`: ese alpha lo SOBRESCRIBE el
 * componente al pintar (`copy` reemplaza el canal, no lo multiplica), así que no es el que se ve.
 */
internal const val ACCENT_TRACK_ALPHA = 0.25f

/**
 * Par contenedor/contenido de una pieza pequeña que se apoya en otra superficie — hoy, la píldora
 * del ⋮ de una fila y la del tile de un género.
 *
 * Se llamó así cuando los dos colores se DERIVABAN del fondo real; desde el 12 sep 2026 los dos
 * callers pasan pares tabulados (ver `rememberRowActionColors`) y esto es solo el par. El nombre se
 * queda porque el alias `RowActionColors` es el que usan los consumidores.
 */
@Immutable
data class TonalLayerColors(val container: Color, val content: Color)

/**
 * Barra de progreso Expressive estilo Apple Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeek: ((Long) -> Unit)? = null,
    trackColor: Color = AppColors.primary,
    inactiveTrackColor: Color = AppColors.surfaceContainerHighest,
    textColor: Color = AppColors.onSurfaceVariant,
    showThumb: Boolean = true,
    trackHeight: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Cálculo DIRECTO, sin remember{derivedStateOf}: currentPosition/duration son parámetros
    // planos (no State) y el derivedStateOf cacheaba la lambda de la PRIMERA composición —
    // la barra quedaba congelada en la posición inicial y al soltar un drag "rebotaba" ahí
    // aunque el seek de audio sí se ejecutara. La recomposición por cambio de parámetro ya
    // recalcula esto solo.
    val displayPosition = if (isDragging) sliderPosition
    else if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val thumbSize by animateDpAsState(
        targetValue = if (showThumb && (isDragging || isPressed)) 18.dp else 0.dp,
        // Spatial: es un tamaño. El rebote del token es lo que le da el "pop" al agarrar el thumb.
        animationSpec = appSpatialSpec(),
        label = "ThumbSize"
    )

    val formattedDuration = remember(duration) { formatTime(duration) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (onSeek != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = displayPosition,
                    onValueChange = {
                        isDragging = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeek((sliderPosition * duration).toLong())
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(thumbSize)
                                .shadow(if (thumbSize.value > 0f) 4.dp else 0.dp, CircleShape)
                                .background(Color.White, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        val fraction = sliderState.value
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(CircleShape)
                                .background(inactiveTrackColor.copy(alpha = ACCENT_TRACK_ALPHA))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(trackColor)
                            )
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = trackColor,
                        inactiveTrackColor = Color.Transparent
                    )
                )
            }
        } else {
            LinearProgressIndicator(
                progress = { if (duration > 0) currentPosition.toFloat() / duration else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape),
                color = trackColor,
                trackColor = inactiveTrackColor.copy(alpha = ACCENT_TRACK_ALPHA)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
    }
}
