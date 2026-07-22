package com.qhana.siku.ui.components

import androidx.compose.material3.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle

/** Redondeo de las esquinas del squircle, en fracción del lado (28% ≈ squircle rotundo). */
private const val SQUIRCLE_ROUNDING = 0.28f

/**
 * Forma de la carátula, común al MiniPlayer y al NowPlaying: morph continuo entre un SQUIRCLE
 * (reproduciendo, progress 0) y [MaterialShapes.Circle] (en pausa, progress 1).
 *
 * El squircle es un cuadrado propio con [SQUIRCLE_ROUNDING] en vez de [MaterialShapes.Square]:
 * la forma del sistema apenas redondea las esquinas y en una carátula grande se leía como un
 * cuadro plano. Con el 28% del lado la portada NO se recorta y el camino hasta el círculo es
 * más corto, así que el morph al pausar se siente continuo en vez de un salto de esquinas.
 *
 * Vive en `ui.components` (no en la pantalla) porque los DOS extremos del shared element de la
 * carátula la necesitan con el MISMO valor: si el mini usara un círculo fijo y el NowPlaying un
 * squircle, la transición saltaría de forma. Compartiéndola, ambos extremos coinciden para cada
 * estado de reproducción y el shared element solo interpola bounds.
 *
 * Se reconstruye por frame mientras dura la animación; en reposo el composable no recompone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class AlbumArtMorphShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // El spring rebota fuera de [0,1]; Morph solo acepta ese rango.
        val p = progress.coerceIn(0f, 1f)
        // Espacio normalizado 0..1 (igual que las MaterialShapes) y luego a píxeles.
        val squircle = RoundedPolygon.rectangle(
            width = 1f,
            height = 1f,
            centerX = 0.5f,
            centerY = 0.5f,
            rounding = CornerRounding(SQUIRCLE_ROUNDING)
        ).transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        val circle = MaterialShapes.Circle.transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        return Outline.Generic(Morph(squircle, circle).toPath(p))
    }
}
