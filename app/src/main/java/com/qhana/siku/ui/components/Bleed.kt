package com.qhana.siku.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Saca el contenido fuera del padding de su padre: mide al hijo con el ancho ENTERO (el disponible
 * más los márgenes que se le pasan), lo coloca desplazado y **reporta el ancho de antes**, así que
 * la columna sigue midiendo y alineando igual que si nada — lo único que se sale es el dibujo.
 *
 * **Por qué no se reparte el padding entre los hijos**, que sería la otra forma de conseguirlo: la
 * keyline se quedaría sin dueño y el próximo bloque que alguien añada nacería pegado al borde de la
 * pantalla, sin error de compilación y sin más síntoma que un texto descuadrado. Con la keyline en
 * el contenedor, alinearse es lo que pasa por defecto y **salirse es lo que hay que pedir
 * explícitamente**, que es el reparto correcto cuando la excepción es UNA pieza.
 *
 * Va por `Modifier.layout` y no por `offset`/`graphicsLayer` porque sus dos consumidores son puntas
 * de shared element —la portada del reproductor y el header inmersivo de los detalles—: sus bounds
 * salen del LOOKAHEAD, pasada en la que una traslación de dibujo no existe (mismo motivo que
 * `chromeShift` y el arrastre de la píldora).
 *
 * Los dos lados van SEPARADOS porque el segundo consumidor los necesita distintos: en horizontal la
 * perforación de pantalla cae en UN costado, así que el inset del que hay que salirse no es
 * simétrico. Vivía en `NowPlayingLayouts` como `private` con un solo valor.
 */
internal fun Modifier.bleedHorizontally(start: Dp, end: Dp = start): Modifier =
    layout { measurable, constraints ->
        // Sin ancho acotado no hay margen del que salirse: medir "todo + 32" contra infinito no
        // significa nada, así que el modificador se aparta y deja pasar las constraints tal cual.
        val extraStart = if (constraints.hasBoundedWidth) start.roundToPx() else 0
        val extraEnd = if (constraints.hasBoundedWidth) end.roundToPx() else 0
        val placeable = measurable.measure(
            constraints.copy(maxWidth = constraints.maxWidth + extraStart + extraEnd)
        )
        val reportedWidth = (placeable.width - extraStart - extraEnd)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(reportedWidth, placeable.height) {
            placeable.place(-extraStart, 0)
        }
    }
