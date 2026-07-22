package com.qhana.siku.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Recorta el bitmap a un [RoundedPolygon] EN LA CARGA (una vez por imagen, cacheada por
 * Coil vía [cacheKey]) en vez de clipear la composición con un Path por fila: el clip de
 * path arbitrario no tiene fast-path de hardware (a diferencia del rounded-rect) y en una
 * lista se paga en CADA frame del scroll — el jank de la pestaña Artistas. Con la forma
 * horneada, la fila dibuja un bitmap plano sin clip.
 */
class RoundedPolygonMaskTransformation(
    polygon: RoundedPolygon,
    override val cacheKey: String
) : Transformation() {

    // Path en espacio unitario (bounds 0..1), calculado una vez por instancia; se escala
    // al tamaño real de cada bitmap en transform().
    private val unitPath: Path = polygon.normalized().toPath()

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path(unitPath).apply {
            transform(Matrix().apply {
                setScale(input.width.toFloat(), input.height.toFloat())
            })
        }
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }
}
