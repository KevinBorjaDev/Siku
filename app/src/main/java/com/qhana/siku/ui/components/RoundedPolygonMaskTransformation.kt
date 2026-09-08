package com.qhana.siku.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
 *
 * **La salida es siempre CUADRADA**, y eso es load-bearing: quien consume una máscara la dibuja
 * en una superficie cuadrada con `ContentScale.Crop` (`AlbumArt` mide `size × size`), mientras
 * que Coil respeta el aspect ratio del ORIGINAL al escalar. Una carátula panorámica llega aquí
 * como un rectángulo ancho, y estirarle el polígono lo deformaba —el cookie salía aplastado— para
 * que después el `Crop` recortara la franja central de esa forma ya deformada: un mordisco con los
 * lados rectos, que es como se veía un álbum de portada no cuadrada. Recortando al cuadrado
 * central ANTES de enmascarar, el `Crop` del composable es la identidad y la forma llega intacta.
 */
class RoundedPolygonMaskTransformation(
    polygon: RoundedPolygon,
    override val cacheKey: String
) : Transformation() {

    // Path en espacio unitario (bounds 0..1), calculado una vez por instancia; se escala
    // al lado del cuadrado de cada bitmap en transform().
    private val unitPath: Path = polygon.normalized().toPath()

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val side = minOf(input.width, input.height)
        // Center-crop: el mismo recorte que haría ContentScale.Crop, hecho aquí para que la
        // máscara se aplique en el espacio en el que la imagen se DIBUJA, no en el del archivo.
        val srcLeft = (input.width - side) / 2
        val srcTop = (input.height - side) / 2

        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path(unitPath).apply {
            transform(Matrix().apply { setScale(side.toFloat(), side.toFloat()) })
        }
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(
            input,
            Rect(srcLeft, srcTop, srcLeft + side, srcTop + side),
            Rect(0, 0, side, side),
            paint
        )
        return output
    }
}
