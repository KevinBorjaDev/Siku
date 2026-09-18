package com.qhana.siku.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.ui.theme.AppColors
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations

// ============== CONSTANTES ==============

// Anillo de progreso (modo cola): traza y holgura entre el aro y la carátula.
private val RingStroke = 3.dp
private val RingGap = 2.dp

/**
 * Velo sobre una carátula para que un glifo se lea encima de ella.
 *
 * **Vive aquí y lo consumen los dos sitios que lo necesitan** —el indicador de descarga de esta
 * misma función y el emblema de `ArtCarouselCard`—, porque es UNA decisión: cuánto hay que tapar
 * una portada cualquiera para que un símbolo blanco encima se lea tanto sobre una carátula negra
 * como sobre una blanca, sin que el collage deje de reconocerse. Estaba escrito con el mismo valor
 * en los dos archivos y con un comentario en uno apuntando al otro, que es la señal de que era la
 * misma regla partida en dos.
 *
 * Es translucidez de verdad y no jerarquía de contenido: lo que hay debajo es una imagen
 * arbitraria, así que no existe el contenedor del que `secondaryContentColor` derivaría un tono.
 * Es la excepción declarada a "la jerarquía va por TONO".
 */
internal const val ARTWORK_SCRIM_ALPHA = 0.45f

// ============== ALBUM ART ==============

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumArt(
    albumArtUri: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    shape: Shape? = null, // Si se pasa, tiene prioridad sobre cornerRadius
    // Máscara de la carátula HORNEADA en el bitmap por Coil en la carga (una vez por imagen,
    // cacheada) en vez de clipar la composición con [shape] en cada frame. OBLIGATORIO para formas
    // CÓNCAVAS (cookie): su clip de path no tiene fast-path de hardware y en una lista se paga en
    // cada frame del scroll. Cuando llega, la fila dibuja un bitmap plano SIN clip y [shape] se usa
    // solo para el placeholder/velo (relleno `drawOutline`, barato). Debe corresponder a [shape].
    maskTransformation: coil3.transform.Transformation? = null,
    // Resolución PEDIDA a Coil. El default (thumbnail de 156px) es correcto para list items
    // chicos, pero pixela en tarjetas grandes (p.ej. carruseles del home de 150dp ≈ 410px):
    // pasar `null` deja que Coil pida el tamaño MEDIDO del composable → nítido sin desperdiciar.
    requestSizePx: Int? = ComponentConfig.ThumbnailSize,
    downloadProgress: Float? = null,
    isDownloading: Boolean = false,
    /**
     * Modo ANILLO: durante la descarga la carátula se deja INTACTA (levemente encogida) y un aro
     * de progreso la rodea, en vez de dibujar el indicador ENCIMA. Desde la metadata ligera la
     * portada existe antes de bajar el audio, y superponerle un icono la ensuciaba. Lo usa la cola
     * de descargas; el resto de la app mantiene el indicador centrado (velo + spinner).
     */
    useRingProgress: Boolean = false
) {
    val primaryColor = AppColors.primary
    // Velo sobre la carátula durante la descarga (M3 scrim token, no negro fijo).
    val scrimColor = AppColors.scrim
    // Riel NO recorrido del aro: el rol que M3 TABULA para esto —`ProgressIndicatorTokens`:
    // `ActiveIndicatorColor = Primary`, `TrackColor = SecondaryContainer`—, o sea el mismo par que
    // el aro de descarga del toolbar del reproductor y el del MiniPlayer.
    //
    // Era `primary @ ACCENT_TRACK_ALPHA`, el único de los tres aros que se quedó derivando. Ese
    // alpha existía para que el aro se leyera como UNA pieza, canal y relleno; el par tabulado da lo
    // mismo sin depender de lo que haya debajo.
    //
    // ⚠️ **Éste es el aro que peor lo tiene en tema CLARO**: cae sobre una fila
    // `surfaceContainerHigh` y ahí `secondaryContainer` está a solo **2 puntos de tono** (medido
    // sobre 4 seeds, TonalSpot y spec 2021) — se separa por CROMA, +8/+10. En una fila entera eso
    // basta; en un trazo de 3dp no hay superficie donde leer el tinte. En oscuro son 13 puntos y no
    // hay duda. Si en device no se ve, el reemplazo es `outlineVariant`, que da 12-13 puntos
    // constantes en los dos temas y los dos estilos de paleta.
    val ringTrackColor = AppColors.secondaryContainer

    val isDownloadState = isDownloading ||
        (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f)
    // En modo anillo, la carátula cede sitio al aro exterior (traza + holgura). Solo mientras
    // descarga; como los ítems de la cola están SIEMPRE descargando, no hay salto visible.
    val ringActive = useRingProgress && isDownloadState && albumArtUri != null
    val artInset = if (ringActive) RingStroke + RingGap else 0.dp

    // Par contenedor/contenido del TEMA, no dos grises fijos por modo. El hueco de una carátula
    // que falta aparece en TODAS las listas de la app, así que era la superficie que más veces
    // rompía el color dinámico: `#E0E0E0`/`#2A2A2A` son grises neutros y `surfaceContainerHighest`
    // lleva el tinte del seed, igual que el contenedor de la fila sobre el que se apoya. Se leen
    // sueltos y sin `remember`: leer dos roles del colorScheme no asigna nada, mientras que el
    // objeto que los agrupaba sí lo hacía en cada recomposición desde que dejó de cachearse.
    val placeholderColor = AppColors.surfaceContainerHighest
    val iconTint = AppColors.onSurfaceVariant

    val density = LocalDensity.current
    val iconSizeSp = remember(size, density) { with(density) { (size / 2).toSp() } }

    // Contenedor transparente del tamaño pedido. La carátula vive en un Box INTERNO que se
    // encoge en modo anillo para dejar sitio al aro; así el footprint total no cambia y el aro
    // no se dibuja sobre la imagen.
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val effShape = shape ?: RoundedCornerShape(cornerRadius)
        Box(
            modifier = Modifier
                .size(size - artInset * 2)
                // Con máscara horneada NO se clipa: el bitmap ya llega con la forma y un clip de path
                // cóncavo se pagaría por frame en el scroll. El placeholder se pinta con la forma como
                // RELLENO (barato). Sin máscara, el comportamiento de siempre: clip + fondo liso.
                .then(
                    if (maskTransformation == null) Modifier.clip(effShape).background(placeholderColor)
                    else Modifier.background(placeholderColor, effShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (albumArtUri != null) {
                val context = LocalContext.current
                // SIN memoryCacheKey/diskCacheKey propios: la clave la deriva Coil de la data (el
                // URI) más el tamaño pedido y las transformaciones, que es EXACTAMENTE lo que
                // distingue un bitmap de otro. Hasta el 9 ago 2026 aquí se pasaba el id de la
                // CANCIÓN, y eso fragmentaba por canción una imagen que es del ÁLBUM: las 12 pistas
                // de un disco metían 12 copias idénticas en el caché de memoria (el 25 % del heap),
                // expulsando carátulas legítimas y forzando a redecodificar al scrollear. Desde la
                // v25 el archivo se llama por su CONTENIDO (`covers/<sha1>.jpg`), así que el URI ya
                // es la clave correcta: mismas portadas colapsan solas y una portada reparada
                // cambia de nombre, o sea que la invalidación también sale gratis.
                val imageRequest = remember(albumArtUri, requestSizePx, maskTransformation) {
                    ImageRequest.Builder(context)
                        .data(albumArtUri)
                        .apply {
                            // requestSizePx != null → tamaño fijo (thumbnails de listas). null → sin
                            // .size(), Coil resuelve al tamaño medido del composable (tarjetas grandes).
                            if (requestSizePx != null) size(requestSizePx)
                            // Máscara (cookie) horneada en el bitmap: la fila dibuja plano, sin clip.
                            if (maskTransformation != null) transformations(maskTransformation)
                        }
                        .crossfade(200)
                        .build()
                }

                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.common_album_art),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            when {
                // MODO ANILLO: la carátula queda intacta; el progreso va en el aro exterior (ver
                // abajo). Solo mientras "prepara" (sin progreso aún) se muestra un spinner breve
                // centrado, para no dejar la portada sin ninguna señal de actividad.
                ringActive -> {
                    if (downloadProgress == null || downloadProgress <= 0f) {
                        LoadingIndicator(modifier = Modifier.size(size / 3), color = primaryColor)
                    }
                }
                // Resto de la app: indicador CENTRADO sobre la carátula, con velo para contraste.
                isDownloadState -> {
                    if (albumArtUri != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // Con la forma como relleno: sin clip del contenedor, el velo debe
                                // seguir la cookie por su cuenta (con clip es inocuo, ya va acotado).
                                .background(scrimColor.copy(alpha = ARTWORK_SCRIM_ALPHA), effShape)
                        )
                    }
                    // LoadingIndicator expressive (morfea entre MaterialShapes): determinado con
                    // progreso, indeterminado mientras prepara. Mismo lenguaje que el resto de la app.
                    if (downloadProgress != null && downloadProgress > 0f) {
                        LoadingIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(size / 2),
                            color = primaryColor
                        )
                    } else {
                        LoadingIndicator(modifier = Modifier.size(size / 2), color = primaryColor)
                    }
                }
                // Sin descarga y sin carátula: nota musical de relleno.
                albumArtUri == null -> {
                    MaterialSymbol(icon = "music_note", size = iconSizeSp, color = iconTint)
                }
            }
        }

        // ANILLO DE PROGRESO alrededor de la carátula (modo cola). Traza tenue completa + arco
        // primario = progreso, empezando arriba (−90°) en sentido horario. Circular aunque la
        // carátula sea cookie: el aro circunscribe la forma sin tocarla.
        if (ringActive) {
            val stroke = with(density) { RingStroke.toPx() }
            Canvas(modifier = Modifier.size(size)) {
                val inset = stroke / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    this.size.width - stroke, this.size.height - stroke
                )
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(
                    color = ringTrackColor,
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                val progress = downloadProgress
                if (progress != null && progress > 0f) {
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }
    }
}

// ============== AVATAR DE CUENTA ==============

/**
 * Avatar de la cuenta para el header de la biblioteca. Muestra la foto de perfil de Microsoft si
 * está cacheada ([photoPath]); si no, un círculo con la inicial del nombre; y si tampoco hay
 * nombre, un glifo de persona. El caso SIN cuenta (solo-local) NO llega aquí — el header pinta un
 * engranaje de ajustes en su lugar.
 *
 * El círculo de la inicial va en `primary`/`onPrimary` a propósito: el header lo aloja sobre la
 * píldora de búsqueda, que es `secondaryContainer`, así que un fondo `secondary*` se fundiría con
 * ella; `primary` destaca como un chip de avatar.
 */
@Composable
fun AccountAvatar(
    photoPath: String?,
    initial: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    if (photoPath != null) {
        val context = LocalContext.current
        val request = remember(photoPath) {
            val file = java.io.File(photoPath)
            // El archivo SIEMPRE se llama igual (account/photo.jpg), así que el URI no distingue una
            // foto de otra: sin una clave propia, cambiar de cuenta serviría la foto anterior desde
            // el caché de Coil. La clave incluye el `lastModified`, que cambia al reescribir el
            // archivo tras un re-login (el mismo cuidado que las carátulas por contenido).
            val cacheKey = "account_photo_${file.lastModified()}"
            ImageRequest.Builder(context)
                // Mismo formato probado que las carátulas: URI `file://` (String), que Coil3 resuelve
                // aquí; un `java.io.File` crudo no está garantizado como dato de coil3.
                .data(android.net.Uri.fromFile(file).toString())
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(AppColors.primary),
            contentAlignment = Alignment.Center
        ) {
            if (!initial.isNullOrBlank()) {
                Text(
                    text = initial,
                    color = AppColors.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                MaterialSymbol(
                    icon = "person",
                    size = (size.value * 0.6f).sp,
                    color = AppColors.onPrimary
                )
            }
        }
    }
}
