package com.qhana.siku.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import com.qhana.siku.MainActivity
import com.qhana.siku.R
import java.io.File

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/**
 * Widget estilo YouTube Music: SIN contenedor (fondo transparente) — flotan la carátula
 * CIRCULAR grande en el centro, el me gusta (círculo tonal) solapado arriba-derecha y
 * play/pause (SQUIRCLE tonal) solapado abajo-izquierda. Tocar la carátula abre la app.
 * Play/pause funciona con la app cerrada: la acción levanta el proceso y el
 * MusicController restaura la última sesión.
 */
class PlayerWidget : GlanceAppWidget() {

    // Exact: el tamaño real de celda dimensiona círculo y botones proporcionalmente.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                // El snapshot viaja por el currentState de Glance: provideGlance corre UNA
                // vez por sesión y solo el cambio de estado dispara recomposición con datos
                // frescos (leerlo fuera dejaba el widget congelado hasta rebind del launcher).
                val ctx = LocalContext.current
                val json = currentState(WidgetUpdater.KEY_SNAPSHOT)
                val snapshot = remember(json) {
                    if (json != null) WidgetSnapshotStore.fromJson(json)
                    else WidgetSnapshotStore.read(ctx) // sesión nueva: rehidratar de disco
                }
                val artwork = remember(snapshot.artPath) {
                    loadWidgetArtwork(ctx, snapshot.artPath)?.toCircularBitmap()
                }
                PlayerWidgetContent(snapshot, artwork)
            }
        }
    }
}

@Composable
private fun PlayerWidgetContent(snapshot: WidgetSnapshot, artwork: Bitmap?) {
    val context = LocalContext.current
    val widgetSize = LocalSize.current
    val side = minOf(widgetSize.width, widgetSize.height)
    val artSize = side * 0.9f
    val playSize = (side * 0.34f).coerceIn(48.dp, 72.dp)
    val likeSize = (side * 0.32f).coerceIn(44.dp, 64.dp)

    // Lienzo CUADRADO centrado: los botones se anclan a las esquinas del cuadrado que
    // circunscribe al círculo, no a las del widget — si la celda es rectangular, anclarlos
    // al widget los dejaba lejos de la carátula (el corazón "no se superponía").
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PlayerWidgetCanvas(snapshot, artwork, side, artSize, playSize, likeSize, context)
    }
}

@Composable
private fun PlayerWidgetCanvas(
    snapshot: WidgetSnapshot,
    artwork: Bitmap?,
    side: androidx.compose.ui.unit.Dp,
    artSize: androidx.compose.ui.unit.Dp,
    playSize: androidx.compose.ui.unit.Dp,
    likeSize: androidx.compose.ui.unit.Dp,
    context: Context
) {
    Box(modifier = GlanceModifier.size(side)) {
        // Carátula circular centrada (abre la app).
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (artwork != null) {
                Image(
                    provider = ImageProvider(artwork),
                    contentDescription = snapshot.title,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier
                        .size(artSize)
                        .clickable(actionStartActivity<MainActivity>())
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .size(artSize)
                        .cornerRadius(artSize / 2)
                        .background(GlanceTheme.colors.surfaceVariant)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_music_note),
                        contentDescription = context.getString(R.string.widget_nothing_playing),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(artSize / 3)
                    )
                }
            }
        }

        // Me gusta — círculo tonal solapado en la esquina superior derecha.
        val songId = snapshot.songId
        if (songId != null) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.TopEnd
            ) {
                CircleIconButton(
                    imageProvider = ImageProvider(
                        if (snapshot.isFavorite) R.drawable.ic_widget_favorite_filled
                        else R.drawable.ic_widget_favorite
                    ),
                    contentDescription = context.getString(
                        if (snapshot.isFavorite) R.string.common_remove_from_favorites
                        else R.string.common_add_to_favorites
                    ),
                    onClick = actionRunCallback<ToggleFavoriteAction>(
                        actionParametersOf(ToggleFavoriteAction.KEY_SONG_ID to songId)
                    ),
                    backgroundColor = GlanceTheme.colors.secondaryContainer,
                    contentColor = GlanceTheme.colors.onSecondaryContainer,
                    modifier = GlanceModifier.size(likeSize)
                )
            }
        }

        // Play/pause — SQUIRCLE tonal solapado en la esquina inferior izquierda.
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.BottomStart
        ) {
            SquareIconButton(
                imageProvider = ImageProvider(
                    if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                ),
                contentDescription = context.getString(
                    if (snapshot.isPlaying) R.string.common_pause else R.string.common_play
                ),
                onClick = actionRunCallback<PlayPauseAction>(),
                backgroundColor = GlanceTheme.colors.primaryContainer,
                contentColor = GlanceTheme.colors.onPrimaryContainer,
                modifier = GlanceModifier.size(playSize)
            )
        }
    }
}

/** Recorte cuadrado con esquinas redondeadas (centro-crop); todas las APIs, como el circular. */
internal fun Bitmap.toRoundedBitmap(cornerFraction: Float = 0.25f): Bitmap {
    val side = minOf(width, height)
    val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = side * cornerFraction
    canvas.drawRoundRect(android.graphics.RectF(0f, 0f, side.toFloat(), side.toFloat()), radius, radius, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    val srcLeft = (width - side) / 2
    val srcTop = (height - side) / 2
    canvas.drawBitmap(this, Rect(srcLeft, srcTop, srcLeft + side, srcTop + side), Rect(0, 0, side, side), paint)
    return output
}

/** Recorte circular con centro-crop; funciona en TODAS las APIs (cornerRadius solo S+). */
internal fun Bitmap.toCircularBitmap(): Bitmap {
    val diameter = minOf(width, height)
    val output = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    val srcLeft = (width - diameter) / 2
    val srcTop = (height - diameter) / 2
    canvas.drawBitmap(
        this,
        Rect(srcLeft, srcTop, srcLeft + diameter, srcTop + diameter),
        Rect(0, 0, diameter, diameter),
        paint
    )
    return output
}

/**
 * Decodifica la carátula a un bitmap acotado (los RemoteViews tienen límite de memoria).
 * Soporta file://, rutas absolutas y content:// (carpeta local SAF).
 */
internal fun loadWidgetArtwork(context: Context, uriString: String?, maxDim: Int = 512): Bitmap? {
    if (uriString.isNullOrBlank()) return null
    return runCatching {
        val bytes: ByteArray? = when {
            uriString.startsWith("content://") ->
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            uriString.startsWith("file://") ->
                Uri.parse(uriString).path?.let { File(it).takeIf(File::exists)?.readBytes() }
            uriString.startsWith("/") -> File(uriString).takeIf(File::exists)?.readBytes()
            else -> null
        }
        if (bytes == null || bytes.isEmpty()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
