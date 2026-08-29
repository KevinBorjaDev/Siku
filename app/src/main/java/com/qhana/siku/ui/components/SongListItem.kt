package com.qhana.siku.ui.components

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.ContainerOriginRole
import com.qhana.siku.ui.LocalRowOrigin
import com.qhana.siku.ui.appSharedTransitionScope
import com.qhana.siku.ui.model.SongUiModel
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppContainerBoundsTransform

// ============== FONDO Y ACCIONES DE LA FILA ==============

/**
 * Contraste mínimo del glifo sobre la píldora. 4.5:1 (AA de texto, no el 3:1 de icono) porque los
 * tres puntos son trazos de ~2 dp: a efectos de legibilidad se comportan como texto pequeño.
 */
private const val ROW_ACTION_GLYPH_MIN_CONTRAST = 4.5f

/**
 * Separación tonal (HCT) del resaltado de la fila que suena respecto al fondo de las demás filas.
 *
 * **Es SUYA y no la de [TONAL_LAYER_DELTA]**, aunque hoy valgan lo mismo: aquél es el escalón de una
 * capa pequeña apoyada sobre algo (la píldora del ⋮, el chip de origen) y calibrar el resaltado no
 * debe moverlas. Los dos trabajos son distintos: la píldora solo tiene que leerse como control de
 * ESTA fila —el ojo ya está encima—, mientras que la fila activa hay que ENCONTRARLA de un vistazo
 * scrolleando una lista de miles.
 *
 * Arranca en 8 porque es lo que M3 usa para su propia selección de lista (`secondaryContainer` T90
 * sobre `surface` T98) y porque el escalón anterior —el rol `primaryContainer` a pelo— resultó ser
 * de ~35 puntos, o sea un bloque tan pesado como el MiniPlayer. Subirlo aquí es la palanca si en
 * device se queda corto, y ya no arrastra a nadie más.
 */
private const val ROW_ACTIVE_TONE_DELTA = 8.0

/**
 * Fondo EFECTIVO de una fila de canción: el contenedor base en las normales y, en la que SUENA, un
 * ESCALÓN de superficie teñido con el acento del álbum. Es el color que hay REALMENTE debajo del
 * contenido de la fila, y por tanto contra el que se miden las superficies que se apoyan encima
 * ([rememberRowActionColors]) y el propio contenido ([rememberActiveRowContentColor]).
 *
 * El resaltado es el MISMO en toda la app —biblioteca, detalles y cola—. Antes cada superficie lo
 * resolvía distinto (esta con `secondary` al 12%, la lista y la cola con un blend del acento), y el
 * blend quedaba casi invisible en un álbum monocromo; unificarlo en `primaryContainer` arregló eso
 * pero dejó el TAMAÑO del salto en manos de la paleta: ese rol no es un nivel de superficie, así que
 * con el spec 2025 y un estilo vívido cae a tono medio y la fila se leía como un bloque tan pesado
 * como el MiniPlayer —contra el resto de la lista, que desde la horquilla de superficies vive en
 * `surface`—. Ahora se deriva con [tonalLayerContainer] del fondo REAL de la fila: mismo hue y mismo
 * croma que `primaryContainer`, pero el tono a [ROW_ACTIVE_TONE_DELTA] del fondo. El resaltado pasa
 * a ser exactamente lo que es —"esta fila está un nivel por encima"—, se ve por COLOR y no por peso,
 * y la separación sale idéntica en cualquier estilo de paleta y en los dos temas.
 */
@Composable
fun songRowBackground(base: Color, isPlaying: Boolean): Color {
    val role = AppColors.primaryContainer
    // Envuelto aquí porque [tonalLayerContainer] hace conversiones HCT y esto se llama por FILA en
    // cada recomposición del scroll; con `isPlaying` en la clave, las filas normales ni la ejecutan.
    return remember(base, isPlaying, role) {
        if (isPlaying) tonalLayerContainer(base, role, ROW_ACTIVE_TONE_DELTA) else base
    }
}

/**
 * Contraste mínimo del contenido de la fila ACTIVA sobre su relleno de acento. 4.5:1 = AA de TEXTO,
 * porque lo que se apoya ahí es el título y el subtítulo de la canción.
 */
private const val ACTIVE_ROW_CONTENT_MIN_CONTRAST = 4.5f

/**
 * Color del contenido de la fila que SUENA: `onPrimaryContainer`, pero pasado por [ensureContrast]
 * contra el relleno que lo pinta. En teoría el par `on-`/contenedor de M3 ya contrasta, pero con
 * carátulas de croma bajo —y según el estilo de paleta— MaterialKolor entrega el par demasiado cerca
 * en tono y el texto de la fila activa quedaba MÁS apagado que el de las filas normales, justo al
 * revés de lo que el resaltado busca. Conserva el matiz del álbum y es no-op si el par ya cumple.
 *
 * Devuelve `Unspecified` cuando la fila no está activa, que es lo que [SongItem] espera en
 * `activeContentColor` para caer a sus roles normales.
 *
 * Vive AQUÍ, junto a [songRowBackground], porque el resaltado del ítem activo se pinta desde cuatro
 * sitios (biblioteca, cola, detalle de lista y el propio `ListItem` de los demás detalles) y el color
 * de su contenido tiene que salir de UNA sola definición: mientras la cola aplicaba el contraste y el
 * resto usaba el rol a pelo, la misma fila se leía distinta según la pantalla.
 */
@Composable
fun rememberActiveRowContentColor(rowBackground: Color, isPlaying: Boolean): Color {
    val onPrimaryContainer = AppColors.onPrimaryContainer
    // Solo la fila que suena lo necesita, y solo cuando cambian esos colores: el barrido de contraste
    // no debe correr por cada fila en cada recomposición del scroll.
    return remember(isPlaying, onPrimaryContainer, rowBackground) {
        if (isPlaying) {
            ensureContrast(onPrimaryContainer, rowBackground, ACTIVE_ROW_CONTENT_MIN_CONTRAST)
        } else {
            Color.Unspecified
        }
    }
}

/**
 * Par contenedor/contenido de la píldora de acciones de una fila. Es el par genérico de
 * [TonalLayerColors]: la píldora fue el primer caso, pero la regla —derivar del fondo REAL en vez de
 * un rol fijo— vale para cualquier capa sobre otra (ver el chip de origen del NowPlaying).
 */
typealias RowActionColors = TonalLayerColors

/**
 * Colores de la píldora de overflow (⋮) de una fila, DERIVADOS del fondo real de la fila en vez de
 * un rol fijo de la paleta. La regla y su porqué viven en [rememberTonalLayerColors]; aquí solo se
 * fija el contraste que pide ESTE caso.
 *
 * Medido en device con tres estilos de paleta sobre la MISMA canción: con `secondaryContainer` a
 * secas, vibrante daba una píldora saturada que competía con el botón de play, monocromo un bloque
 * plano y "fiel" una mancha fundida con la fila.
 */
@Composable
fun rememberRowActionColors(rowBackground: Color): RowActionColors =
    rememberTonalLayerColors(rowBackground, minContrast = ROW_ACTION_GLYPH_MIN_CONTRAST)


/**
 * Color de un ACENTO puesto sobre una fila (hoy: el corazón de Favoritos), derivado del fondo REAL
 * de esa fila y no del rol a pelo.
 *
 * `primary` a secas funciona mientras la fila esté en `surface` —claro sobre oscuro— y se
 * desvanece justo donde más se mira: la fila que SUENA se rellena de `primaryContainer`, o sea del
 * mismo matiz y casi el mismo tono, y el corazón se perdía dentro (visto en device el 22 ago 2026,
 * con el título y el ⋮ de esa misma fila ya adaptados y él no). Es el mismo principio que sostiene
 * [rememberActiveRowContentColor] y [rememberRowActionColors]: en una fila que cambia de fondo,
 * ningún contenido puede fijar su color por rol.
 *
 * Umbral de TEXTO (4.5:1) y no el 3:1 que le tocaría a un icono: es la acción principal de la fila
 * y tiene que leerse tan bien como su título. Conserva el matiz del acento y es no-op cuando el par
 * ya cumple, así que en las filas normales no cambia nada.
 */
@Composable
fun rememberRowAccentColor(rowBackground: Color): Color {
    val primary = AppColors.primary
    return remember(primary, rowBackground) {
        ensureContrast(primary, rowBackground, ACTIVE_ROW_CONTENT_MIN_CONTRAST)
    }
}
// ============== SONG STATUS ICON ==============

/**
 * Indicador de estado de descarga de una canción.
 * Con [onClick] es un icon button ESTÁNDAR M3 Expressive (sin contenedor): ripple + shape-morph
 * al presionar vía `IconButtonDefaults.shapes()`, mismo patrón que los toggles con
 * `toggleableShapes()`. Sin [onClick] se dibuja como INDICADOR puro: un botón que se ilumina y no
 * responde se lee como un control roto, y en la mayoría de las listas el estado solo informa.
 * El área que ocupa es la misma en ambos casos (mínimo táctil de M3), así que la fila no salta.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongStatusIcon(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = ComponentConfig.StatusIconSize,
    onClick: (() -> Unit)? = null,
    contrast: Boolean = false,
    contrastColor: Color = AppColors.primary
) {
    val primaryColor = if (!contrast) AppColors.primary else contrastColor

    // Cache del tamaño en Sp para evitar recálculos
    val density = LocalDensity.current
    val sizeSp = remember(size, density) { with(density) { size.toSp() } }

    val description = when {
        isDownloaded -> stringResource(R.string.status_downloaded)
        isDownloading -> stringResource(R.string.status_downloading, ((downloadProgress ?: 0f) * 100).toInt())
        else -> stringResource(R.string.status_download_song)
    }

    val content: @Composable () -> Unit = {
        when {
            isDownloaded -> {
                MaterialSymbol("offline_pin", color = primaryColor, size = sizeSp)
            }
            isDownloading -> {
                // LoadingIndicator expressive en AMBOS estados (morfea entre MaterialShapes):
                // la variante DETERMINADA avanza el morph con el progreso. Antes el caso con
                // progreso usaba un CircularProgressIndicator clásico, así que el mismo icono
                // cambiaba de lenguaje visual a mitad de descarga.
                if (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f) {
                    LoadingIndicator(
                        progress = { downloadProgress },
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                } else {
                    LoadingIndicator(
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                }
            }
            else -> {
                // `outline` y no un gris FIJO: `Color.Gray` no se entera del tema, así que en
                // oscuro este indicador quedaba casi invisible sobre el fondo. El rol tiene tono
                // por tema (50 claro / 60 oscuro), que es lo que este glifo pedía.
                MaterialSymbol("cloud_download", color = AppColors.outline, size = sizeSp)
            }
        }
    }

    val described = modifier.semantics { contentDescription = description }
    if (onClick != null) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = described,
            content = { content() }
        )
    } else {
        // `minimumInteractiveComponentSize` es el MISMO mecanismo con el que IconButton reserva su
        // espacio, así que el indicador ocupa lo mismo que el botón y las filas siguen alineadas.
        Box(
            modifier = described.minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    }
}

// ============== CARÁTULA DE LA FILA (y su papel de origen del reproductor) ==============

/**
 * Carátula de una fila de canción. Normalmente es un `AlbumArt` y ya está; lo que la complica es
 * que además **viaja al reproductor** cuando éste se abre desde esta fila.
 *
 * ## Es la portada DENTRO de un container transform, no un morph suelto
 *
 * Al tocar una fila, lo que crece hasta ser el reproductor es la superficie ENTERA de la fila
 * ([SongRowContainer]); esta portada es un `sharedElement` **anidado** dentro de ese contenedor —el
 * mismo reparto de papeles que la píldora y su carátula—. El contenedor escala y se funde; la
 * portada es lo único que VIAJA, porque es lo único que existe igual en las dos puntas.
 *
 * Por eso aquí ya no hay `AnimatedVisibility`: **ocultar el origen es trabajo del contenedor**, que
 * es quien tiene que dejar de ser destino para que Compose acepte el match (de las dos puntas de una
 * key solo UNA puede ser destino; con dos, la portada aparece quieta en su posición final — el bug
 * del 30 jul). Esta capa solo dice si se pinta o no.
 *
 * ## `sharedElementWithCallerManagedVisibility`, no el atado al scope
 *
 * Del scope se leería su DURACIÓN, y una punta atada a él deja de participar en cuanto esa
 * transición termina. Aquí la visibilidad se deriva del estado del origen y punto: exactamente una
 * de las dos puntas está `visible` en cada sentido, que es lo que decide cuál es origen y cuál
 * destino, sin depender de que dos transiciones distintas duren lo mismo.
 *
 * ## Por qué NO hace falta pasar ningún id
 *
 * La fila origen es SIEMPRE la de la canción activa: `MusicController.announceSelection` fija la
 * canción en el frame del tap, antes de que el reproductor se expanda. Así que cada fila decide por
 * su cuenta preguntando a `LocalRowOrigin` qué papel le toca, sin que ningún callback tenga que cargar
 * con el id. De regalo, si el usuario cambia de canción dentro del reproductor, al cerrar la portada
 * aterriza en la fila que AHORA suena, que es lo coherente.
 *
 * ## Lo que hay que vigilar
 *
 * El shared element se declara SOLO mientras la fila tiene papel en el morph, igual que el contenedor
 * (ver `ContainerOriginRole`): con papel VISIBLE está a la vista y ofrece sus bounds —el frame de
 * preparación, o el cierre—; con HIDDEN la portada la dibuja el reproductor al otro lado y pintarla
 * también aquí sería dibujarla dos veces. Sin papel no hay modifier: es el camino caliente del scroll
 * y hasta el 16 ago cada fila visible mantenía aquí una entrada viva en el `SharedTransitionScope`.
 * La key lleva el id ([rowArtSharedKey]) para que la punta del reproductor elija de qué fila sale.
 *
 * Y el scope se pide con `appSharedTransitionScope()`, que devuelve null fuera de su ventana: estas
 * mismas filas se componen dentro de diálogos (el overlay de búsqueda) y de sheets, donde declarar
 * un shared element de la ventana de abajo es un FC.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun SongItemArt(
    song: SongUiModel,
    downloadProgress: Float?,
    isDownloading: Boolean,
    useRingProgress: Boolean
) {
    // `appSharedTransitionScope()` y no el local a pelo: dentro de un diálogo o un sheet (que son
    // otra ventana) el scope no sirve y usarlo CRASHEA — ver su kdoc.
    val sharedScope = appSharedTransitionScope()

    // SIN scope no hay nada que hacer aquí: esta fila no puede ser origen de ningún morph, así que
    // no necesita declarar shared element. Se dibuja la carátula a pelo y se sale. Es el caso de
    // TODAS las filas de las hojas —la cola, "añadir canciones"— donde el scope va anulado a
    // propósito, y de cualquier lista fuera del `SharedTransitionLayout`.
    if (sharedScope == null) {
        SongArtImage(song, downloadProgress, isDownloading, useRingProgress)
        return
    }

    // `derivedStateOf` y no una lectura directa: el origen cambia al preparar, al abrir y al cerrar el
    // reproductor, y leerlo a pelo recompondría TODAS las filas visibles en ese mismo frame — el
    // que arranca la transición y el que la termina. Así solo recompone la fila cuyo papel cambia.
    // Ver [RowOriginHost].
    val rowOrigin = LocalRowOrigin.current
    val role by remember(song.id, rowOrigin) { derivedStateOf { rowOrigin.roleOf(song.id) } }

    // Sin papel, sin modifier: la portada a secas. Cambiar de papel cambia la cadena de modifiers de
    // la MISMA imagen, no su estructura — la carátula ya cargada es la que viaja.
    val artModifier = if (role == null) Modifier else with(sharedScope) {
        Modifier.sharedElementWithCallerManagedVisibility(
            sharedContentState = rememberSharedContentState(key = rowArtSharedKey(song.id)),
            // VISIBLE = a la vista (preparándose como origen, o recibiendo el cierre). HIDDEN = la
            // portada la dibuja el reproductor al otro lado del morph; pintarla también aquí sería
            // dibujarla dos veces.
            visible = role == ContainerOriginRole.VISIBLE,
            boundsTransform = AppContainerBoundsTransform,
            // Por encima de las dos superficies: es lo único que cruza de una a la otra, así que
            // no puede viajar por debajo de la fila que se está fundiendo.
            zIndexInOverlay = CONTAINER_ART_OVERLAY_Z
        )
    }
    SongArtImage(
        song = song,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading,
        useRingProgress = useRingProgress,
        modifier = artModifier
    )
}

/**
 * La carátula de la fila, a secas. Extraída para que [SongItemArt] pueda dibujarla por el camino
 * corto (sin envoltorio) cuando no hay shared element que declarar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SongArtImage(
    song: SongUiModel,
    downloadProgress: Float?,
    isDownloading: Boolean,
    useRingProgress: Boolean,
    modifier: Modifier = Modifier
) {
    // Cookie de 9 lados (M3 Expressive) en vez de círculo, HORNEADA en el bitmap (`SongItemCookieMask`)
    // y NO clipada por frame: un shape cookie es cóncavo y su clip de path no tiene fast-path de
    // hardware, así que clipar la fila costaba fps en cada frame del scroll (el mismo jank que ya se
    // resolvió en Artistas). `shape` se sigue pasando: AlbumArt lo usa solo para el placeholder/velo.
    // toShape() ya es @Composable y memoiza internamente (no envolver en remember).
    AlbumArt(
        albumArtUri = song.imageUrl,
        size = ComponentConfig.SongItemIconSize,
        shape = MaterialShapes.Cookie9Sided.toShape(),
        maskTransformation = SongItemCookieMask,
        modifier = modifier,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading,
        useRingProgress = useRingProgress
    )
}

/**
 * Máscara cookie de 9 lados COMPARTIDA por todas las filas de canción (biblioteca, cola, detalles):
 * una sola instancia (su path unitario se calcula una vez) y un solo `cacheKey`, así el memory cache
 * de Coil reutiliza el bitmap enmascarado entre pistas del mismo álbum. Mismo patrón que `CookieMask`
 * de Artistas.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val SongItemCookieMask by lazy {
    RoundedPolygonMaskTransformation(MaterialShapes.Cookie9Sided, cacheKey = "cookie9")
}

// ============== SONG ITEM ==============

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useRingProgress: Boolean = false, // aro de progreso alrededor de la carátula (cola de descargas)
    showActiveBackground: Boolean = true,
    // null = el estado solo informa (sin ripple). Solo lo pasan las pantallas que reaccionan al tap.
    onStatusClick: (() -> Unit)? = null,
    // Color del contenido (texto + icono de estado) de la fila cuando SUENA. `Unspecified` = los
    // roles por defecto (onSurface / onSurfaceVariant / primary). Se pasa cuando un contenedor
    // externo rellena la fila activa con un color de acento y su contenido tiene que ir en el `on-`
    // de ese contenedor para contrastar (la cola y la lista de canciones, ítem en reproducción).
    activeContentColor: Color = Color.Unspecified,
    trailingContent: @Composable (() -> Unit)? = null
) {
    // Convertir a modelo UI para reutilizar la lógica de renderizado
    val uiModel = remember(song) { song.toUiModel(isActive = isPlaying) }

    SongItem(
        song = uiModel,
        isPlaying = isPlaying,
        modifier = modifier,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        showDuration = showDuration,
        showStatusIcon = showStatusIcon,
        useRingProgress = useRingProgress, // Pass through
        showActiveBackground = showActiveBackground,
        onStatusClick = onStatusClick,
        activeContentColor = activeContentColor,
        trailingContent = trailingContent
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongItem(
    song: SongUiModel,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useRingProgress: Boolean = false, // aro de progreso alrededor de la carátula (cola de descargas)
    // El item activo dibuja su propio tinte de contenedor. Ponerlo en false cuando un CONTENEDOR
    // externo ya pinta el resaltado de TODA la fila (p. ej. la cola), para no duplicar el énfasis.
    showActiveBackground: Boolean = true,
    // null = el estado solo informa (sin ripple). Solo lo pasan las pantallas que reaccionan al tap.
    onStatusClick: (() -> Unit)? = null,
    // Color del contenido (texto + icono de estado) de la fila cuando SUENA. `Unspecified` = los
    // roles por defecto (onSurface / onSurfaceVariant / primary). Se pasa cuando un contenedor
    // externo rellena la fila activa con un color de acento y su contenido tiene que ir en el `on-`
    // de ese contenedor para contrastar (la cola y la lista de canciones, ítem en reproducción).
    activeContentColor: Color = Color.Unspecified,
    trailingContent: @Composable (() -> Unit)? = null
) {
    // `ListItem` REAL de M3 (anatomía + tokens por spec): headline `bodyLarge`/onSurface,
    // supporting `bodyMedium`/onSurfaceVariant, leading/trailing en sus slots. El look de LISTA
    // AGRUPADA (esquinas + gaps + fondo tonal) lo pone el contenedor EXTERNO de cada pantalla; aquí
    // el container es transparente salvo el activo.
    //
    // Resaltado del ítem en reproducción, UNIFICADO en toda la app (biblioteca, detalles, cola):
    // el escalón de superficie teñido de [songRowBackground], con el contenido derivado de él.
    // Dos rutas para el mismo resultado:
    //  · `showActiveBackground = true` (detalles): lo pinta ESTE `ListItem` y deriva su `on-` solo.
    //  · `showActiveBackground = false` + `activeContentColor` (lista, cola): el contenedor EXTERNO
    //    rellena la fila y le pasa el color de contenido; aquí solo se aplica al texto/icono.
    val paintsOwnActive = isPlaying && showActiveBackground
    // El relleno sale del MISMO [songRowBackground] que usa el contenedor externo, con la misma base
    // (`surface`: el color del `AppSurface` que envuelve la fila en los cuatro detalles). Aquí estaba
    // escrito el rol a pelo, o sea una segunda definición del resaltado — invisible mientras fue un
    // rol fijo, y una fila y una píldora derivadas de bases distintas en cuanto pasó a derivarse.
    val ownActiveBackground = songRowBackground(AppColors.contentSurface, paintsOwnActive)
    // Mismo contraste garantizado que cuando el relleno lo pinta un contenedor externo: el color de
    // la fila activa sale de una sola definición, la pinte quien la pinte.
    val ownActiveContent = rememberActiveRowContentColor(
        rowBackground = ownActiveBackground,
        isPlaying = paintsOwnActive
    )
    val effectiveActiveContent = when {
        activeContentColor.isSpecified -> activeContentColor
        else -> ownActiveContent
    }
    val useActiveContent = isPlaying && effectiveActiveContent.isSpecified
    val listColors = ListItemDefaults.colors(
        containerColor = if (paintsOwnActive) ownActiveBackground else Color.Transparent,
        headlineColor = if (useActiveContent) effectiveActiveContent else AppColors.onSurface,
        supportingColor = if (useActiveContent) effectiveActiveContent else AppColors.onSurfaceVariant
    )

    // El indicador de estado del archivo (descargada / se transmitirá) es un concepto de NUBE:
    // una canción del propio dispositivo está siempre ahí, así que el icono no comunicaba nada y
    // su pulsación solo repetía lo obvio. En una biblioteca solo-local desaparece de toda la app.
    val showStatus = showStatusIcon && song.isCloud

    // contentDescription/stateDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playingStateDesc = if (isPlaying) stringResource(R.string.status_playing) else ""
    val durationDesc = stringResource(R.string.common_duration, song.durationText)

    // Trailing (duración + estado de descarga + menú). null si no hay nada → ListItem no reserva slot.
    val trailing: @Composable (() -> Unit)? = if (showDuration || showStatus || trailingContent != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDuration) {
                    Text(
                        text = song.durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = durationDesc }
                    )
                }
                // Estado de descarga junto al menú, como icon button estándar (sin fondo).
                if (showStatus) {
                    SongStatusIcon(
                        isDownloaded = song.isDownloaded,
                        isDownloading = isDownloading,
                        downloadProgress = null, // Hide progress here (moved to AlbumArt)
                        onClick = onStatusClick,
                        // Fila activa rellena de acento: el glifo va en su `on-` en vez de `primary`,
                        // que sobre ese contenedor apenas contrasta.
                        contrast = useActiveContent,
                        contrastColor = effectiveActiveContent
                    )
                }
                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    trailingContent()
                }
            }
        }
    } else null

    ListItem(
        headlineContent = {
            Text(
                text = song.title,
                // ROLES del scale Expressive, no un peso pegado a mano sobre `bodyLarge`. Los dos
                // que se eligen tienen MÉTRICAS IDÉNTICAS —16sp, alto de línea 24, tracking 0.15—
                // y solo difieren en el peso (Medium vs Bold), así que empezar a sonar no mueve el
                // layout de la fila ni un píxel. Leído de `TypeScaleTokens` de la versión pineada:
                // `bodyLarge` a secas trae tracking 0.5, que es el correcto para Regular y queda
                // suelto en cuanto se engorda el peso — ése era el defecto de escribir
                // `bodyLarge + FontWeight.Medium`, que en todo lo demás ya era este mismo rol.
                style = if (isPlaying) MaterialTheme.typography.titleMediumEmphasized
                        else MaterialTheme.typography.bodyLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${song.artist} • ${song.album}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            SongItemArt(
                song = song,
                downloadProgress = downloadProgress,
                isDownloading = isDownloading,
                useRingProgress = useRingProgress
            )
        },
        trailingContent = trailing,
        colors = listColors,
        modifier = modifier
            // Clip para que el tinte del contenedor activo respete la esquina del item (el
            // contenedor externo agrupado recorta a su vez la forma de la lista).
            .clip(RoundedCornerShape(ComponentConfig.SongItemCornerRadius))
            .semantics { stateDescription = playingStateDesc }
    )
}
