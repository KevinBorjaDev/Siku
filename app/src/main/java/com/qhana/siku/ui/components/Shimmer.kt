package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.theme.AppColors

/**
 * Aplica un efecto de brillo (shimmer) animado al componente.
 *
 * El `tween` lineal de aquí NO sale del `MotionScheme` a propósito, por el mismo motivo que el giro
 * de la cookie del play: los tokens del scheme son springs finitos (se asientan en un objetivo) y
 * esto es un barrido que se repite sin fin a velocidad constante.
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ),
        label = "ShimmerOffset"
    )

    // Los tres colores salen de la paleta y no de tres hexes fijos (`#B8B5B5`/`#8F8B8B`, que era lo
    // que había): es un esqueleto de carga, o sea la superficie de una fila que todavía no está, y
    // pintado con grises constantes se veía claro sobre el tema oscuro y sin relación con el color
    // del álbum en el claro. `surfaceVariant` es el escalón que la app ya usa para "hueco", y el
    // brillo que lo recorre es `surfaceContainerHighest`, el techo de la escala neutra — el mismo
    // criterio que `AppMenuPopup` para separarse de cualquier fondo sobre el que caiga.
    val base = AppColors.surfaceVariant
    val highlight = AppColors.surfaceContainerHighest

    background(
        brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
        )
    )
    .onGloballyPositioned {
        size = it.size
    }
}

@Composable
fun LoadingSongItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Carátula
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .shimmerEffect()
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            // Título
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Subtítulo
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
        }
    }
}
