package com.qhana.siku.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider
import androidx.glance.layout.ContentScale
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.qhana.siku.MainActivity
import com.qhana.siku.R

class QueueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QueueWidget()
}

/**
 * Widget de COLA, lenguaje M3 Expressive: contenedor tonal grande, cabecera con carátula +
 * canción actual + me gusta; SEGUNDA FILA con la botonera anterior/play/siguiente como
 * PÍLDORAS (la central con etiqueta, estilo button group Expressive); y debajo la etiqueta
 * "A continuación" con la cola como TARJETAS tonales redondeadas — cada una salta a esa
 * canción ([PlayFromQueueAction] con su índice absoluto).
 *
 * El play/pause y la etiqueta llevan el ACENTO extraído de la carátula (convención del
 * NowPlaying: `primary` en claro / `secondary` en oscuro, vía [ColorProvider] day/night);
 * sin colores en el snapshot caen a los tonos Material You del sistema.
 */
class QueueWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                // Snapshot vía currentState: la recomposición por cambio de estado es lo
                // único que refresca una sesión viva (ver PlayerWidget/WidgetUpdater).
                val ctx = LocalContext.current
                val json = currentState(WidgetUpdater.KEY_SNAPSHOT)
                val snapshot = remember(json) {
                    if (json != null) WidgetSnapshotStore.fromJson(json)
                    else WidgetSnapshotStore.read(ctx) // sesión nueva: rehidratar de disco
                }
                val artwork = remember(snapshot.artPath) {
                    loadWidgetArtwork(ctx, snapshot.artPath)?.toRoundedBitmap()
                }
                QueueWidgetContent(snapshot, artwork)
            }
        }
    }
}

/**
 * Acento carátula → (fondo, contenido) como providers day/night, o null si el snapshot aún
 * no trae colores. El contenido se decide por luminancia (blanco sobre acentos oscuros).
 */
private fun accentColors(snapshot: WidgetSnapshot): Pair<androidx.glance.unit.ColorProvider, androidx.glance.unit.ColorProvider>? {
    val light = snapshot.accentLight ?: return null
    val dark = snapshot.accentDark ?: return null
    val day = Color(light)
    val night = Color(dark)
    val onDay = if (day.luminance() > 0.5f) Color.Black else Color.White
    val onNight = if (night.luminance() > 0.5f) Color.Black else Color.White
    return ColorProvider(day = day, night = night) to ColorProvider(day = onDay, night = onNight)
}

@Composable
private fun QueueWidgetContent(snapshot: WidgetSnapshot, artwork: Bitmap?) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(28.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
    ) {
        if (snapshot.songId == null) {
            // Empty state tonal (no una línea suelta): nota musical en círculo
            // secondaryContainer + título + pista de acción. Todo el área abre la app.
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = GlanceModifier
                            .size(56.dp)
                            .cornerRadius(28.dp)
                            .background(GlanceTheme.colors.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_music_note),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                            modifier = GlanceModifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(10.dp))
                    Text(
                        text = context.getString(R.string.widget_nothing_playing),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = context.getString(R.string.widget_empty_hint),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1
                    )
                }
            }
        } else {
            QueueWidgetBody(snapshot, artwork)
        }
    }
}

@Composable
private fun ColumnScope.QueueWidgetBody(snapshot: WidgetSnapshot, artwork: Bitmap?) {
    val context = LocalContext.current
    val accent = accentColors(snapshot)

    // Cabecera: carátula + canción actual (abren la app) + anterior/play-pause/siguiente.
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = snapshot.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier
                    .size(48.dp)
                    .clickable(actionStartActivity<MainActivity>())
            )
        } else {
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .cornerRadius(12.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music_note),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(10.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Text(
                text = snapshot.title.orEmpty(),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Text(
                text = snapshot.artist.orEmpty(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        // Me gusta de la canción ACTUAL al final de la cabecera (los controles viven en la
        // botonera de la segunda fila): icono plano con área de toque de 40dp.
        // ToggleFavoriteAction flipea el snapshot → re-render inmediato.
        val songId = snapshot.songId
        if (songId != null) {
            Box(
                modifier = GlanceModifier
                    .size(40.dp)
                    .cornerRadius(20.dp)
                    .clickable(
                        actionRunCallback<ToggleFavoriteAction>(
                            actionParametersOf(ToggleFavoriteAction.KEY_SONG_ID to songId)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(
                        if (snapshot.isFavorite) R.drawable.ic_widget_favorite_filled
                        else R.drawable.ic_widget_favorite
                    ),
                    contentDescription = context.getString(
                        if (snapshot.isFavorite) R.string.common_remove_from_favorites
                        else R.string.common_add_to_favorites
                    ),
                    colorFilter = ColorFilter.tint(
                        if (snapshot.isFavorite) accent?.first ?: GlanceTheme.colors.primary
                        else GlanceTheme.colors.onSurfaceVariant
                    ),
                    modifier = GlanceModifier.size(24.dp)
                )
            }
        }
    }

    Spacer(modifier = GlanceModifier.height(10.dp))

    // Segunda fila: anterior / play / siguiente como PÍLDORAS (estilo button group
    // Expressive). La central es más ancha, con icono + etiqueta corta y el acento de la
    // carátula; las laterales son stadiums tonales. cornerRadius clipea en S+ (minSdk del
    // widget en la práctica) igual que el contenedor de 28dp de arriba.
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .width(60.dp)
                .height(44.dp)
                .cornerRadius(22.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .clickable(actionRunCallback<PreviousAction>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_skip_previous),
                contentDescription = context.getString(R.string.np_previous),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(22.dp)
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Row(
            modifier = GlanceModifier
                .height(44.dp)
                .cornerRadius(22.dp)
                .background(accent?.first ?: GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<PlayPauseAction>())
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(
                    if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(accent?.second ?: GlanceTheme.colors.onPrimaryContainer),
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = context.getString(
                    if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play
                ),
                style = TextStyle(
                    color = accent?.second ?: GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Box(
            modifier = GlanceModifier
                .width(60.dp)
                .height(44.dp)
                .cornerRadius(22.dp)
                .background(GlanceTheme.colors.secondaryContainer)
                .clickable(actionRunCallback<NextAction>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_skip_next),
                contentDescription = context.getString(R.string.np_next),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(22.dp)
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(10.dp))

    if (snapshot.upNext.isEmpty()) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                // No "cola vacía": acá SIEMPRE hay una canción sonando (songId != null arriba);
                // upNext vacío solo significa que es la última (o única, p.ej. álbum de 1 tema).
                text = context.getString(R.string.widget_queue_no_next),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
            )
        }
    } else {
        Text(
            text = context.getString(R.string.widget_queue_up_next),
            style = TextStyle(
                // Mismo acento de carátula que el play (fallback: primary del sistema).
                color = accent?.first ?: GlanceTheme.colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp)
        )
        val count = snapshot.upNext.size
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            itemsIndexed(snapshot.upNext, itemId = { _, item -> item.index.toLong() }) { position, item ->
                // Lista AGRUPADA como la del home (rememberListItemShape): primera fila con
                // esquinas superiores pronunciadas, última con las inferiores, 4dp el resto.
                // Glance solo clipea uniforme → la forma va en el drawable de fondo.
                val rowBackground = when {
                    count == 1 -> R.drawable.widget_row_single
                    position == 0 -> R.drawable.widget_row_top
                    position == count - 1 -> R.drawable.widget_row_bottom
                    else -> R.drawable.widget_row_mid
                }
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(ImageProvider(rowBackground))
                            .clickable(
                                actionRunCallback<PlayFromQueueAction>(
                                    actionParametersOf(PlayFromQueueAction.KEY_INDEX to item.index)
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = item.title,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                        Text(
                            text = item.artist,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(4.dp))
                }
            }
        }
    }

}
