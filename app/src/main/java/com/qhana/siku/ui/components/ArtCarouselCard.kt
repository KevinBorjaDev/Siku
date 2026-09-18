package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.qhana.siku.ui.theme.AppColors

/** El emblema es la marca de la tarjeta, así que va por encima del glifo de relleno (44sp). */
private val EmblemGlyphSize = 52.sp

/**
 * Dónde empieza a oscurecerse el pie de la tarjeta, y cuánto llega a oscurecerse, para que la
 * etiqueta se lea sobre CUALQUIER portada.
 *
 * Translucidez de verdad (un degradado sobre una imagen), no jerarquía de contenido: eso en esta
 * app se hace por tono. Aquí no hay contenedor conocido del que derivar nada — lo que hay debajo es
 * la portada, y cambia con cada tarjeta.
 */
private const val LabelScrimStart = 0.45f
private const val LabelScrimAlpha = 0.72f

/**
 * Cuánto se acerca el subtítulo al velo respecto del título.
 *
 * Es la excepción explícita a "la jerarquía va por tono": el título y el subtítulo se apoyan sobre
 * el degradado de arriba, cuyo color efectivo depende de la portada que haya debajo, así que no hay
 * un contenedor del que sacar el tono secundario. Es el mismo caso que las pistas de progreso sobre
 * carátula.
 */
private const val LabelSubtitleAlpha = 0.8f

/**
 * Tarjeta de carrusel con arte a sangre: la carátula ocupa toda la caja, un degradado inferior la
 * oscurece y la etiqueta va ENCIMA.
 *
 * **Esa anatomía es la que resuelve el contraste**, y por eso vale para cualquier carrusel de la
 * app: el texto no se apoya en un rol que pueda quedar cerca del fondo, se apoya en un degradado que
 * la tarjeta pinta ella misma sobre una imagen arbitraria. Es la salida a la que se llegó tras
 * cuatro intentos de colocar una píldora suelta bajo el nombre en el ranking de estadísticas
 * (30 ago 2026): el problema no era dónde ponerla, era que estuviera fuera de la imagen.
 *
 * **Se probó y se DESCARTÓ la anatomía de `AlbumTileCard`** (portada arriba y texto en un contenedor
 * debajo, 27 ago 2026): sobre el papel unificaba el inicio con la pestaña Álbumes, pero en device el
 * contenedor bajo cada portada se veía FEO — un carrusel es una tira de imágenes, y ponerle una
 * banda de texto a cada una le quita justo lo que la hace una tira. En una REJILLA sigue siendo lo
 * correcto, que es donde ya vive.
 *
 * Vivió como `private` en `HomeScreen` hasta el 30 ago 2026, cuando el ranking de estadísticas
 * necesitó la misma tarjeta.
 *
 * @param modifier **trae el recorte**: la tarjeta no se lo pone ella. En los carruseles de la app lo
 *   pone el llamador (`.clip(...)`), porque `KeylineCarousel` —al revés que el de M3— no enmascara
 *   nada desde fuera.
 * @param artModifier para el shared element de la carátula (álbumes, artistas).
 * @param badgeIcon distintivo de tipo, esquina superior derecha.
 * @param isCurrent indicador de "en reproducción", también superior derecha.
 * @param labelAlpha alfa de la etiqueta y su degradado. `1f` fijo cuando TODAS las tarjetas deben
 *   leerse a la vez —un ranking, donde se compara—; en un carrusel de keylines se pasa el del ítem
 *   (`KeylineItemScope.revealFraction`), para que solo el que está en foco muestre su texto.
 */
@Composable
fun ArtCarouselCard(
    art: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    badgeIcon: String? = null,
    placeholderIcon: String = "music_note",
    // Collage de carátulas (contextos sin carátula propia: aleatorio / toda la biblioteca).
    // Con al menos una URI reemplaza a [art]/[placeholderIcon]; se adapta a cuántas haya.
    collage: List<String>? = null,
    /**
     * Glifo de identidad sobre la imagen, para una tarjeta cuya portada NO dice de qué es. Se pinta
     * relleno y sobre un velo, y solo cuando hay algo debajo: con el placeholder ya se está
     * dibujando ese mismo símbolo en grande, y encimarle otro sería el mismo icono dos veces.
     */
    emblemIcon: String? = null,
    // Alfa de la etiqueta (título/subtítulo + degradado). Se lee en fase de dibujo para que en el
    // multi-browse solo el ítem GRANDE (el foco) muestre su label y los que asoman lo oculten.
    labelAlpha: () -> Float = { 1f }
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            // Sin indication: el ripple rectangular gris se hacía muy visible al presionar
            // (no hay long-press que lo amerite).
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .background(AppColors.surfaceContainerHighest)
    ) {
        when {
            !collage.isNullOrEmpty() -> AdaptiveCollage(collage, Modifier.fillMaxSize())
            art != null -> {
                val context = LocalContext.current
                // Sin clave propia: la de Coil (data + tamaño + transformaciones) ya es la
                // correcta. La versión anterior componía `<identidad de la tarjeta>@<uri>` para que
                // un cambio de imagen invalidara la entrada —la foto del artista que llega del
                // backfill, "ninguno de estos", una carátula reparada—, pero el URI SOLO consigue
                // eso mismo (si la imagen cambia, cambia la clave) sin el efecto secundario que
                // traía el prefijo: dos tarjetas que muestran la misma portada guardaban dos copias
                // del mismo bitmap. Ver el kdoc de la petición en [AlbumArt].
                val request = ImageRequest.Builder(context)
                    .data(art)
                    .crossfade(200)
                    .build()
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = artModifier.fillMaxSize()
                )
            }
            else -> {
                Box(modifier = artModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol(placeholderIcon, size = 44.sp, color = AppColors.onSurfaceVariant)
                }
            }
        }

        // Emblema: velo + glifo RELLENO. El velo no es decoración — un corazón blanco sobre un
        // collage de portadas claras desaparece, y es el mismo motivo por el que existe el
        // degradado de la etiqueta de abajo. Va con el alfa del label para que en el multi-browse
        // los ítems que solo asoman enseñen su portada limpia, igual que hacen con el título.
        //
        // **El velo es el rol `scrim`** (antes `Color.Black`): es el que M3 define para exactamente
        // esto. El alfa se queda —aquí la opacidad ES el efecto, no un color derivado—, y el
        // contenido encima sigue en `Color.White` porque **lo que hay debajo es una IMAGEN y no un
        // rol**: M3 no tabula ningún `on…` para eso, y blanco sobre scrim es el patrón. Es el mismo
        // criterio del texto de la etiqueta, unas líneas más abajo.
        val emblem = emblemIcon
        if (emblem != null && !collage.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = labelAlpha() }
                    .background(AppColors.scrim.copy(alpha = ARTWORK_SCRIM_ALPHA)),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol(emblem, fill = true, size = EmblemGlyphSize, color = Color.White)
            }
        }

        // Degradado inferior para legibilidad de la etiqueta sobre cualquier carátula. Se
        // desvanece junto con el label en los ítems que asoman (no-foco).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = labelAlpha() }
                .background(
                    Brush.verticalGradient(
                        LabelScrimStart to Color.Transparent,
                        1f to AppColors.scrim.copy(alpha = LabelScrimAlpha)
                    )
                )
        )

        // Etiqueta (título + subtítulo) sobre el degradado. Solo el ítem grande (foco) la muestra.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer { alpha = labelAlpha() }
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = LabelSubtitleAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // **UN SOLO distintivo en la esquina superior derecha, y "en reproducción" GANA.**
        //
        // Los dos se dibujaban en `Alignment.TopEnd` con el mismo padding, cada uno bajo su propio
        // `if`: con `badgeIcon` e `isCurrent` a la vez quedaban UNO ENCIMA DEL OTRO. Hoy ningún
        // caller pasa los dos —el carrusel de canciones manda `isCurrent` y los otros dos
        // `badgeIcon`—, así que el solape estaba a un parámetro de distancia y no lo impedía nada.
        // Con un `when` la posición tiene un solo dueño y el orden es una decisión escrita: qué
        // suena AHORA es más urgente que de qué tipo es la tarjeta.
        //
        // **Color: el par tabulado `primary`/`onPrimary`.** Lo que hay que garantizar es el glifo
        // contra SU PROPIO relleno, y de eso se encarga el par; que el sello se despegue de la
        // portada lo hace por ser un bloque sólido, que es como M3 pinta un badge sobre una imagen.
        //
        // Estuvo en `vividAccentColor(primary)` —saturación y brillo forzados a una banda vívida—
        // con el glifo en `onContainerColor`, que elige blanco o negro por luminancia: dos
        // derivaciones encadenadas. Hacían falta con el spec 2025, donde `primary` podía salir
        // lavado y el sello se fundía con la portada; con el 2021 es T40 en claro y T80 en oscuro,
        // un tono medio macizo que se lee como pieza deliberada sobre cualquier imagen.
        val badge = when {
            isCurrent -> "graphic_eq"
            else -> badgeIcon
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.primary)
                    .padding(4.dp)
            ) {
                MaterialSymbol(badge, size = 16.sp, color = AppColors.onPrimary, fill = isCurrent)
            }
        }
    }
}
