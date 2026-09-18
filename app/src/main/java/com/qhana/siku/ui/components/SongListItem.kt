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
 * Fondo de una fila de canción: [base] normalmente, y **`secondaryContainer` la que SUENA**.
 *
 * Es el token que M3 da a un list item SELECCIONADO (`ListTokens.ItemSelectedContainerColor`, y el
 * mismo en la variante segmented vía `ListItemDefaults.segmentedColors`), así que su contenido sale
 * tabulado en `onSecondaryContainer` — ver [rememberActiveRowContentColor].
 *
 * **Antes se DERIVABA** con `tonalLayerContainer`: el hue y el croma de `primaryContainer` llevados
 * al tono del fondo movido 8 puntos. Hacía falta porque las filas vivían en `surfaceContainerHigh`,
 * y ahí el rol seleccionado no se despega: medido el 11 sep 2026 sobre los cuatro esquemas, **1,8
 * puntos de tono en tema claro**. Con las filas un escalón más abajo (`AppColors.contentSurface` =
 * `surfaceContainer`) el par tabulado ya sirve.
 *
 * **Y en claro lo que separa es el CROMA, no el tono**, que es el dato que casi hace descartar esto:
 * quedan 3,9 puntos de tono, pero la fila pasa de croma ~6 —casi neutra— a ~16 con Equilibrado y
 * hasta ~29 con Fidelidad. La fila que suena no se lee como "más oscura" sino como TEÑIDA. Medir
 * solo el tono aquí es la trampa: esa unidad vale entre dos superficies que comparten croma —el caso
 * de una capa derivada— y no entre dos roles distintos.
 */
@Composable
fun songRowBackground(base: Color, isPlaying: Boolean): Color =
    if (isPlaying) AppColors.secondaryContainer else base
/**
 * Contenido de la fila que SUENA: `onSecondaryContainer`, el `on…` tabulado del relleno que le pone
 * [songRowBackground]. `Color.Unspecified` en las demás, que dejan pasar los roles por defecto.
 *
 * M3 lo tabula pieza a pieza —etiqueta, overline, texto de apoyo e iconos, todos
 * `onSecondaryContainer` (`ListTokens.ItemSelected*`)—, así que aquí no se calcula nada. Estuvo en
 * `ensureContrast(onPrimaryContainer, …)` porque el relleno era un color derivado y con él se perdía
 * la garantía del par.
 */
@Composable
fun rememberActiveRowContentColor(rowBackground: Color, isPlaying: Boolean): Color =
    if (isPlaying) AppColors.onSecondaryContainer else Color.Unspecified

typealias RowActionColors = TonalLayerColors

/**
 * Colores de la píldora de overflow (⋮) de una fila: el par TONAL de M3 para un icon button
 * (`filled tonal` = `secondaryContainer` / `onSecondaryContainer`), **y el neutro cuando la fila ya
 * es ese rol**.
 *
 * La referencia es el propio spec de listas, donde el slot final lleva un botón —no un icono
 * suelto, que es lo que tabula `ListTokens.ItemTrailingIconColor`— y ese botón es un disco tonal.
 *
 * **Se invierte en la fila que SUENA**, y no es un caso raro sino la mitad del problema: esa fila se
 * rellena de `secondaryContainer` ([songRowBackground]), así que una píldora del mismo rol mide
 * distancia CERO — un rol no se separa de sí mismo. Sobre fila neutra, píldora teñida; sobre fila
 * teñida, píldora neutra. Medido sobre los cuatro esquemas: 4 puntos de tono más 10 de croma en el
 * primer caso (lo que separa ahí es el CROMA, igual que en la propia fila activa) y 8 puntos en
 * claro / 24 en oscuro en el segundo.
 *
 * **Estuvo DERIVADO del fondo real** con `rememberTonalLayerColors`, y el motivo está medido en
 * device con tres estilos de paleta sobre la misma canción: con `secondaryContainer` a secas,
 * vibrante daba una píldora saturada que competía con el play, monocromo un bloque plano y "fiel"
 * una mancha fundida con la fila. Ese defecto venía de que las filas vivían en
 * `surfaceContainerHigh`, donde no queda banda libre por encima; con las filas en
 * `surfaceContainer` el par tabulado ya se apoya.
 */
@Composable
fun rememberRowActionColors(rowBackground: Color): RowActionColors =
    if (rowBackground == AppColors.secondaryContainer) {
        TonalLayerColors(container = AppColors.surface, content = AppColors.onSurface)
    } else {
        TonalLayerColors(
            container = AppColors.secondaryContainer,
            content = AppColors.onSecondaryContainer
        )
    }

/**
 * Acento de CONTENIDO de una fila —el corazón del detalle de Favoritos, el número del ranking—,
 * que cambia con el fondo de SU fila: `onSecondaryContainer` en la que suena y `primary` en las
 * demás. Los dos son pares tabulados contra el relleno que [songRowBackground] pone debajo.
 *
 * La comparación con el rol es exacta y no una heurística: [songRowBackground] devuelve
 * literalmente ese color o la base, nunca uno derivado.
 *
 * Estuvo en `ensureContrast(primary, …)`, y hacía falta: con el relleno activo derivado de
 * `primaryContainer`, el corazón en `primary` caía en el mismo matiz y casi el mismo tono y se
 * perdía dentro (visto en device el 22 ago 2026). Lo que no cambia es el principio que lo motivó —
 * **en una fila que cambia de fondo, ningún contenido puede fijar su color por rol**—, solo que
 * ahora los dos fondos posibles son roles conocidos y cada uno trae su `on…`.
 */
@Composable
fun rememberRowAccentColor(rowBackground: Color): Color =
    if (rowBackground == AppColors.secondaryContainer) AppColors.onSecondaryContainer
    else AppColors.primary

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
    // Acento del glifo "descargada": **`primary`**, y con el spec 2021 eso está garantizado. Este
    // color solo gobierna las filas NORMALES —la que suena entra por [contrast]/[contrastColor] con
    // el `on…` de su relleno—, o sea que el fondo es siempre `AppColors.contentSurface`
    // (`surfaceContainer`). Ahí el tono de los dos roles es constante y el peor caso medido sobre
    // los cuatro esquemas da **5,51:1**, muy por encima del 3:1 de un icono.
    //
    // Estuvo en `contentRoleOn` con una lista de acentos y un piso de 3:1, que hacía falta con el
    // spec 2025: allí `primary` vivía en el tono de croma máximo y podía acabar en T90. Con los
    // tonos fijos del 2021 esa búsqueda devuelve SIEMPRE el primer candidato, que es `primary`.
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
    leadingContent: @Composable (() -> Unit)? = null,
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
        leadingContent = leadingContent,
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
    // Contenido ANTES de la carátula, dentro del mismo slot leading (un número de puesto en el
    // ranking de estadísticas). Slot GENÉRICO y simétrico a `trailingContent`: el componente lo
    // comparten media docena de listas y ninguna otra numera sus filas, así que lo que no entra aquí
    // es el concepto "ranking" — solo la posición donde ponerlo.
    leadingContent: @Composable (() -> Unit)? = null,
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
            // Con algo delante de la carátula, el slot entero es una fila: así el margen de inicio
            // lo sigue poniendo el `ListItem` (su `ListItemStartPadding`) y no el caller. Cosido por
            // fuera —una columna hermana del `ListItem`— ese margen se lo salta, y lo que se pusiera
            // ahí queda pegado al borde de la fila con todo el aire a su derecha.
            //
            // **Sin `leadingContent` NO se envuelve en nada**, igual que [SongItemArt] se sale por el
            // camino corto cuando no hay shared scope: la fila numerada es una sola pantalla y el
            // resto de listas de la app —las que más presión de scroll tienen— no pagan un nodo de
            // layout de más por una rama que no usan.
            val art: @Composable () -> Unit = {
                SongItemArt(
                    song = song,
                    downloadProgress = downloadProgress,
                    isDownloading = isDownloading,
                    useRingProgress = useRingProgress
                )
            }
            if (leadingContent == null) {
                art()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leadingContent()
                    art()
                }
            }
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
