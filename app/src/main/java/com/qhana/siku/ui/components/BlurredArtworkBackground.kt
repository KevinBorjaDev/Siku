package com.qhana.siku.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carátula ya desenfocada y lista para pintar, junto al color que de verdad queda ARRIBA.
 *
 * [topColor] no es un extra: con una imagen de fondo deja de haber un color de fondo único, y toda
 * la pantalla del reproductor deriva su contraste de uno (el chip de origen, el glifo del play).
 * Se calcula aquí -promediando la banda superior del bitmap ya desenfocado, que son unas pocas
 * decenas de píxeles- porque es el único sitio donde ese bitmap existe en CPU.
 */
@Immutable
data class BlurredArtwork(
    val image: ImageBitmap,
    /** Color medio de la banda superior, ANTES del velo. */
    val topColor: Color
)

/**
 * Decodifica la carátula pequeña, la desenfoca y la deja lista. Devuelve `null` mientras no haya
 * nada que pintar (modo apagado, canción sin carátula, o la primera vez que se pide una).
 *
 * **El desenfoque se hace UNA vez por canción, en CPU y sobre un bitmap minúsculo, no con
 * `RenderEffect`/`Modifier.blur`.** Tres motivos, y ninguno es opcional: `RenderEffect` es API 31+
 * (minSdk 26) y así no habría fondo en Android 8-11; desenfocar a pantalla completa es una capa
 * offscreen que se recompone en cada frame de la apertura del reproductor, que es exactamente el
 * frame que más caro salió de arreglar (ver `ModulateAlpha` en NowPlayingScreen); y el resultado
 * de ampliar un bitmap de ~100 px a pantalla completa ya ES un desenfoque - lo único que falta es
 * suavizar las bandas que deja la interpolación bilineal, que es lo que hace el box blur.
 *
 * **NO va gateado por `LocalPlayerOnScreen`**, al revés que los relojes de la pantalla. Esto no es
 * un productor continuo de frames sino un trabajo puntual por canción, y hacerlo con el
 * reproductor guardado es justo lo que evita que caiga en el frame del tap que lo abre. El coste
 * es una fracción del que ya paga la extracción de colores (256 px + Celebi) en ese mismo momento.
 */
@Composable
fun rememberBlurredArtwork(artUri: String?, enabled: Boolean): State<BlurredArtwork?> {
    val context = LocalContext.current
    val result = remember { mutableStateOf<BlurredArtwork?>(null) }

    LaunchedEffect(artUri, enabled) {
        if (!enabled || artUri.isNullOrBlank()) {
            result.value = null
            return@LaunchedEffect
        }
        // El anterior se conserva hasta que el nuevo esté RESUELTO, y entonces se reemplaza sea cual
        // sea el resultado. Las dos mitades importan: vaciarlo antes de decodificar sería un
        // parpadeo al color base en cada cambio de canción, y conservarlo cuando la carátula nueva
        // no se pudo leer dejaría el reproductor pintado con la portada de la canción anterior.
        //
        // No hace falta comprobar que la canción siga siendo la misma al terminar: cambiarla cambia
        // la clave del efecto, y eso ya cancela esta corrutina antes de que llegue a asignar.
        result.value = loadBlurredArtwork(context, artUri)
    }

    return result
}

/**
 * Pinta el fondo desenfocado: **color base sólido, imagen, velo**, en ese orden y siempre los tres.
 *
 * La base no es redundante. Esta capa crece SÓLIDA durante el *container transform* que abre el
 * reproductor, así que no puede depender de una imagen que llega asíncrona: sin base habría un
 * agujero en el primer frame de la apertura, y un agujero justo ahí es lo más visible que hay.
 * También es lo que se ve en una canción sin carátula, sin ninguna rama aparte.
 */
@Composable
fun BlurredArtworkBackground(
    artwork: BlurredArtwork?,
    baseColor: Color,
    scrim: ScrimSpec,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(baseColor)) {
        if (artwork != null) {
            Image(
                bitmap = artwork.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop y no Fit: el fondo tiene que llenar la pantalla entera, y como la carátula es
                // cuadrada eso significa recortar por los lados. La banda de arriba del bitmap sigue
                // siendo la de arriba de la pantalla, que es de lo que informa `topColor`.
                contentScale = ContentScale.Crop
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(scrim.brush()))
    }
}

/**
 * El velo tonal que va sobre la imagen: un mismo color del tema (ya teñido por el álbum) con dos
 * opacidades, más tapado abajo, que es donde viven el texto pequeño y los controles.
 *
 * Las opacidades no son las mismas en claro y en oscuro **porque el problema no es simétrico**: en
 * cualquiera de los dos temas lo que arruina el contraste es una portada del signo contrario al
 * texto, y las portadas tienden a ser oscuras y saturadas - así que el tema claro, cuyo texto es
 * oscuro, necesita tapar más para recuperar la misma legibilidad.
 */
@Immutable
data class ScrimSpec(
    val color: Color,
    val alphaTop: Float,
    val alphaBottom: Float
) {
    fun brush(): Brush = Brush.verticalGradient(
        colors = listOf(color.copy(alpha = alphaTop), color.copy(alpha = alphaBottom))
    )

    /**
     * El color que queda REALMENTE arriba del todo: el velo compuesto sobre la banda superior de la
     * carátula. Es lo que consume el chip de origen para derivar su relleno, igual que en los otros
     * dos modos consume la primera parada del degradado o el color sólido.
     */
    fun topColorOver(artwork: BlurredArtwork?, baseColor: Color): Color =
        color.copy(alpha = alphaTop).compositeOver(artwork?.topColor ?: baseColor)
}

/**
 * El claro/oscuro sale de la LUMINANCIA del propio [color] del velo, no de un parámetro ni de
 * `isSystemInDarkTheme()`. Ese color es una superficie del tema vivo, así que la respuesta es
 * exacta por construcción y sigue siendo correcta con el tema forzado desde los ajustes de la app,
 * que es donde un `isSystemInDarkTheme()` mentiría.
 */
@Composable
fun rememberScrimSpec(color: Color): ScrimSpec = remember(color) {
    val dark = color.luminance() < DARK_SURFACE_LUMINANCE
    ScrimSpec(
        color = color,
        alphaTop = if (dark) SCRIM_ALPHA_TOP_DARK else SCRIM_ALPHA_TOP_LIGHT,
        alphaBottom = if (dark) SCRIM_ALPHA_BOTTOM_DARK else SCRIM_ALPHA_BOTTOM_LIGHT
    )
}

// ==================== Interno ====================

private suspend fun loadBlurredArtwork(context: Context, uri: String): BlurredArtwork? {
    val bitmap = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(BLUR_SOURCE_PX)
                .allowHardware(false)
                // Mismo criterio que `ArtworkRepository.extractColorsOptimized`: este bitmap es un
                // insumo que nadie más pide, y guardarlo solo serviría para expulsar carátulas de
                // las listas del pool de memoria compartido. El de disco sí, que evita volver a
                // bajar la imagen.
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
        } catch (e: CancellationException) {
            // Se relanza: este trabajo vive en un efecto que se cancela al cambiar de canción, y
            // tragarse la cancelación lo volvería indistinguible de "esta carátula no se pudo leer".
            throw e
        } catch (_: Exception) {
            null
        }
    } ?: return null

    return withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return@withContext null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        boxBlur(pixels, width, height, BLUR_RADIUS_PX, BLUR_PASSES)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)

        BlurredArtwork(
            image = output.asImageBitmap(),
            topColor = averageTopColor(pixels, width, height)
        )
    }
}

/**
 * Box blur separable aplicado [passes] veces. Tres pasadas de una caja aproximan una gaussiana lo
 * bastante bien como para que no quede rastro de la caja, que es lo que se nota al ampliar.
 *
 * Sobre un bitmap de este tamaño es menos de un millón de operaciones enteras: no necesita ventana
 * deslizante ni ninguna otra astucia, y escrito directo se lee.
 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurAxis(pixels, scratch, width, height, radius, horizontal = true)
        blurAxis(scratch, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurAxis(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
) {
    val lineLength = if (horizontal) width else height
    val lineCount = if (horizontal) height else width

    for (line in 0 until lineCount) {
        for (i in 0 until lineLength) {
            var r = 0
            var g = 0
            var b = 0
            var samples = 0
            for (offset in -radius..radius) {
                // Se recorta contra el borde en vez de envolver: envolver mezclaría el borde
                // izquierdo de la portada con el derecho, y eso sí se ve al ampliar.
                val j = (i + offset).coerceIn(0, lineLength - 1)
                val pixel = if (horizontal) src[line * width + j] else src[j * width + line]
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                samples++
            }
            // Alpha forzado a opaco: una portada con transparencia dejaría ver la base a través del
            // fondo, que no es lo que este modo dibuja.
            val blended = (0xFF shl 24) or
                ((r / samples) shl 16) or
                ((g / samples) shl 8) or
                (b / samples)
            if (horizontal) dst[line * width + i] = blended else dst[i * width + line] = blended
        }
    }
}

/** Color medio de la banda superior, que es la que queda bajo la barra del reproductor. */
private fun averageTopColor(pixels: IntArray, width: Int, height: Int): Color {
    val rows = (height * TOP_SAMPLE_FRACTION).toInt().coerceIn(1, height)
    val count = rows * width
    var r = 0L
    var g = 0L
    var b = 0L
    for (i in 0 until count) {
        val pixel = pixels[i]
        r += (pixel shr 16) and 0xFF
        g += (pixel shr 8) and 0xFF
        b += pixel and 0xFF
    }
    return Color(
        red = (r / count).toInt(),
        green = (g / count).toInt(),
        blue = (b / count).toInt()
    )
}

/**
 * Lado del bitmap de partida, JUNTO CON [BLUR_RADIUS_PX]. Son un par calibrado en device (25 ago
 * 2026) y no dos números independientes: lo que se ve es cuánta portada queda reconocible, y eso
 * sale de la relación entre los dos, no del valor de ninguno.
 *
 * Aquí el desenfoque de verdad lo hace la AMPLIACIÓN —cada píxel del bitmap acaba cubriendo unos
 * 10 dp de pantalla— y el box blur solo suaviza las bandas que deja la interpolación. Se probó
 * primero con 64: desenfocaba más, pero perdía el color local de la portada y el fondo quedaba casi
 * liso. Con 108 se reconoce la disposición del color sin que el detalle ensucie el texto de encima.
 *
 * Si se toca uno, mirar el otro: subir el lado sin subir el radio devuelve detalle (que es
 * exactamente lo que se hizo al pasar de 64 a 108), y bajarlo sin bajar el radio aplana.
 */
private const val BLUR_SOURCE_PX = 108

/** Radio del box blur, en píxeles del bitmap pequeño. Va emparejado con [BLUR_SOURCE_PX]. */
private const val BLUR_RADIUS_PX = 4

/** Tres cajas aproximan una gaussiana; con menos se nota la caja al ampliar. */
private const val BLUR_PASSES = 3

/** Fracción de alto que se promedia para [BlurredArtwork.topColor]: la zona de la barra superior. */
private const val TOP_SAMPLE_FRACTION = 0.2f

/**
 * Corte claro/oscuro por luminancia de la superficie. Va en el medio a propósito: las dos escalas
 * neutras de M3 caen lejísimos de aquí (un `surfaceContainer` claro ronda 0.85 y uno oscuro 0.03),
 * así que no hay ningún tema real cerca del umbral.
 */
private const val DARK_SURFACE_LUMINANCE = 0.5f

// Opacidades del velo. Punto de partida para calibrar EN DEVICE, que es lo único que decide: el
// caso duro del tema claro es una portada oscura, y el del oscuro una portada clara y saturada.
private const val SCRIM_ALPHA_TOP_DARK = 0.55f
private const val SCRIM_ALPHA_BOTTOM_DARK = 0.78f
private const val SCRIM_ALPHA_TOP_LIGHT = 0.68f
private const val SCRIM_ALPHA_BOTTOM_LIGHT = 0.88f
