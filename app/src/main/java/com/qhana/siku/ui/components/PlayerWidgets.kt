package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import kotlin.math.abs

import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.ui.theme.AppColorScheme
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appSpatialSpec

/** `AssistChipTokens.ContainerHeight`: el alto de cualquier chip de M3. */
private val ChipHeight = 32.dp

/** Aire a los lados, el que coincide con el del label de un `AssistChip`. */
private val ChipHorizontalPadding = 16.dp

/**
 * Contador: "777 canciones", "12 álbumes", la duración de una lista.
 *
 * **Es un CHIP, con la anatomía de chip de M3** —rectángulo de esquina pequeña y 32dp de alto
 * (`AssistChipTokens.ContainerShape` y `ContainerHeight`)— y no un badge. Se probó como `Badge` el
 * 29 ago 2026, con el argumento de que un contador no es interactivo y un chip sí: es cierto en el
 * papel, pero en device no funcionó. El badge de M3 está dimensionado para vivir DENTRO de un
 * `BadgedBox`, superpuesto a un icono, y suelto en una fila —al lado del chip de orden, que mide el
 * doble— se lee como una etiqueta huérfana en vez de como el par del otro. **En una fila, las
 * piezas que se leen juntas tienen que compartir escala**, aunque una de ellas no se toque.
 *
 * Lo que SÍ sobrevive de aquel intento es su ASPECTO: píldora y contenedor de acento, o sea que a
 * escala de chip sigue leyéndose como un distintivo y no como un control.
 *
 * **El color es el TECHO de la escala neutra (`surfaceContainerHighest`), y lo que lo separa de sus
 * vecinos es el RELLENO, no el matiz** (7 sep 2026). ⚠️ **PENDIENTE de A/B en device.**
 *
 * El problema real es que esta fila tiene todos sus slots ocupados **y ocupados con significado**:
 *
 *  - sin relleno + `onSurfaceVariant` + borde `outline` = filter chips en reposo y el `SortChip`,
 *    o sea "control apagado";
 *  - `secondaryContainer` + `onSecondaryContainer` = filter chip **seleccionado**, o sea "este
 *    filtro está activo" — y también el relleno de los `QuickActionButton`.
 *
 * Un contador pintado de cualquiera de los dos MIENTE: parece un control apagado o parece un filtro
 * encendido. Eso es lo que llevó al terciario el 29 ago… **por disponibilidad, no por ajuste** —"el
 * único acento que quedaba libre"—, y el precio se vio en device el 7 sep: en TonalSpot la paleta
 * terciaria es el matiz primario **+60°**, así que con una carátula teal este chip era lo ÚNICO
 * morado de toda la biblioteca. El matiz es la señal perceptual más fuerte que hay y se estaba
 * gastando en la pieza más pasiva de la fila, mientras los botones —lo único que hace algo— iban en
 * la familia de todo lo demás. Es el mismo reparto invertido que sacó los pares terciarios de
 * [playerRolePairs] el mismo día.
 *
 * La salida fue **liberar un slot en vez de inventar un color**: el `SortChip` estaba relleno en
 * `surfaceContainerHighest` apartándose del default de `AssistChip` —que en M3 es *outlined* y sin
 * superficie— y volvió a él. Con eso los tres controles de la fila (orden y los dos filtros de
 * origen) comparten contorno, y este contador queda como **la única pieza rellena de la fila**, o
 * sea la única que no se lee como algo que se toca. Que es la verdad: es la única que no se toca.
 *
 * Con esto vuelve a cumplirse la regla general —*el acento marca lo accionable, lo que solo describe
 * va en neutro*— de la que esto era la excepción, y de paso se resuelve el aviso que quedaba escrito
 * de agosto ("el neutro puro desaparecía entre los chips de filtro"): desaparecía porque el chip de
 * orden ocupaba el mismo slot, no porque el neutro no sirviera.
 *
 * ⚠️ **Las dos mitades no se pueden revertir por separado.** Si el `SortChip` vuelve a rellenarse,
 * este contador tiene que salir del neutro — y ahí no queda más que gastar un matiz, que es el
 * `tertiaryContainer` que esto sustituye.
 *
 * Sigue siendo SÓLIDO y no un chip *flat* con borde, aunque eso sea lo que M3 da por defecto: dos de
 * estos viven sobre la foto del header de artista, y encima de una imagen un contenedor transparente
 * no se lee.
 */
@Composable
fun CountChip(
    modifier: Modifier = Modifier,
    containerColor: Color = AppColors.surfaceContainerHighest,
    content: @Composable RowScope.() -> Unit
) {
    AppSurface(
        modifier = modifier,
        // Píldora, que es la forma del badge de M3 (`BadgeTokens.LargeShape` = `CornerFull`) y lo
        // que le conserva ese aspecto. No se confunde con un botón —que también es píldora— porque
        // el color los separa, y no se confunde con el chip de orden —que es rectangular— tampoco.
        shape = CircleShape,
        color = containerColor
    ) {
        Row(
            // Alto FIJO al token en vez de padding vertical: así mide exactamente lo mismo que el
            // `AssistChip` de al lado, que es el punto de todo esto.
            modifier = Modifier
                .height(ChipHeight)
                .padding(horizontal = ChipHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // `content` lleva receptor `RowScope`, así que se invoca DENTRO de la lambda del Row.
            // `ProvideTextStyle` no emite nodo de layout: los `weight` del caller siguen valiendo.
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/**
 * Color de contenido (texto/iconos) con contraste garantizado sobre un
 * contenedor sólido [container]: negro o blanco según su luminancia.
 */
fun onContainerColor(container: Color): Color =
    if (androidx.core.graphics.ColorUtils.calculateLuminance(container.toArgb()) > 0.5) Color.Black else Color.White

/**
 * Blanco o negro, el que MÁS contraste tenga sobre [container], medido con el ratio WCAG real.
 *
 * No es lo mismo que [onContainerColor], que decide por umbral de luminancia 0.5: el cruce de
 * contraste entre blanco y negro está en L ≈ 0.179, así que para todo acento medio (0.18 < L < 0.5)
 * aquel umbral elige BLANCO cuando el negro contrasta más del doble — medido sobre el acento marrón
 * de la captura (L ≈ 0.30): blanco 2.6:1, negro 7.0:1. Sobre texto se nota poco; sobre un elemento
 * FINO como el handle de la barra de progreso es la diferencia entre verlo y no verlo.
 *
 * Se añade en vez de corregir [onContainerColor] porque ese umbral gobierna ya muchas superficies
 * (chips, glifos) y cambiarlo repintaría media app sin haberlo pedido.
 */
fun maxContrastOn(container: Color): Color =
    if (contrastRatio(Color.Black, container) >= contrastRatio(Color.White, container)) Color.Black
    else Color.White

/**
 * Tono del MISMO matiz de [base] con la luminosidad fijada a [lightness] (HSL: conserva
 * H y S). ES la manera de derivar variantes oscuras/claras de un acento — un lerp hacia
 * negro mata la saturación junto con la luz y el resultado se LEE negro (verificado en
 * device: triángulo del play "negro" sobre cyan con lerp 0.80).
 */
fun accentTone(base: Color, lightness: Float): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(base.toArgb(), hsl)
    hsl[2] = lightness
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/**
 * Contenido sobre un contenedor de ACENTO conservando su matiz: en vez del negro/blanco
 * puros de [onContainerColor], el tono oscuro (o claro) del PROPIO color — el triángulo
 * del play sobre el acento del álbum se ve "del álbum", no negro genérico.
 */
fun onAccentContentColor(container: Color): Color {
    // Elige el tono (oscuro 0.15 o claro 0.92, MISMO matiz) que MÁS contrasta con el fondo. NO por
    // umbral de luminancia 0.5 (heurística vieja que fallaba en acentos medios): el cruce real de
    // contraste está en ~0.18, así que para casi todo acento con algo de brillo (L > ~0.18) gana el
    // tono OSCURO. Por eso en tema oscuro el icono va OSCURO sobre el botón claro, como el spec del
    // Filled (onPrimary es oscuro en dark).
    val dark = accentTone(container, 0.15f)
    val light = accentTone(container, 0.92f)
    return if (contrastRatio(dark, container) >= contrastRatio(light, container)) dark else light
}

/**
 * NO reintroducir: reencuadrar el par `on*Container` de M3 para devolverle croma al glifo.
 *
 * Se implementó y se REVIRTIÓ el 29 jul 2026, con la medición hecha. El punto de partida era real:
 * `onSecondaryContainer` pasa de tono 73.7 con saturación 0.39 en el estilo "fiel a la carátula" a
 * **97.5 con 0.05** en "vibrante", así que los iconos de prev/next perdían el color de la paleta y
 * quedaban como lo más brillante del transporte, por encima del play. La función acercaba el glifo al
 * contenedor hasta el tono mínimo que aún cumplía 4.5:1 (97.5 → 86.5), lo que recupera croma porque
 * cerca del blanco el gamut sRGB no admite color.
 *
 * Por qué se cayó: **el glifo de un botón no se juzga contra su contenedor, se juzga contra el resto
 * de la pantalla**. En "vibrante" el texto, los chips y los títulos son blancos —cada uno con su par
 * `on*` de M3—, así que un icono crema entre ellos se lee como apagado, no como más colorido. Y
 * llevar el mismo tratamiento al texto no era opción: en texto pequeño apurar el mínimo AA es peor
 * que perder croma. O se aplicaba a todo o a nada, y la coherencia manda: se deja que M3 decida
 * uniformemente en toda la pantalla.
 */

// ============== SUPERFICIE DERIVADA DEL FONDO REAL ==============

/**
 * Separación tonal (HCT) por DEFECTO entre una superficie y el fondo sobre el que se apoya. 8 = el
 * recorrido COMPLETO de la escala de contenedores de M3 en tema claro (`surface` 98 →
 * `surfaceContainerHighest` 90): la mayor distancia que el spec sigue leyendo como "otra superficie"
 * sin llegar al salto de un botón de acción. Por debajo de ~4 (dos niveles) la capa se funde con el
 * fondo.
 *
 * Es el valor de las capas PEQUEÑAS que se apoyan en algo (la píldora del ⋮, el chip de origen), y
 * por eso [tonalLayerContainer] lo recibe como parámetro: una superficie con otro trabajo —el
 * resaltado de la fila que suena, que hay que encontrar de un vistazo scrolleando— necesita poder
 * calibrar su escalón sin arrastrar a las otras dos.
 */
const val TONAL_LAYER_DELTA = 8.0

/** Centro de la escala de tono HCT: decide si la capa se aleja del fondo hacia abajo o hacia arriba. */
private const val TONAL_LAYER_MID_TONE = 50.0

/**
 * Opacidad del contenido DESHABILITADO: el valor del spec de M3, el mismo que aplican por dentro
 * `Button`, `TextField` y compañía. Es de los pocos alphas legítimos del sistema —junto a los state
 * layers y los scrims—; la jerarquía de texto NO se hace así, sino con roles (`onSurface` vs
 * `onSurfaceVariant`), que llevan su contraste medido contra la superficie.
 *
 * Vive aquí, compartida, porque estaba declarada TRES veces con dos nombres distintos
 * (`DisabledAlpha`, `DISABLED_ALPHA`) y una cuarta suelta en el código: cuatro copias del mismo
 * token del spec que nada obligaba a mantener iguales.
 */
internal const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * Contenido SECUNDARIO sobre un contenedor: el subtítulo de una tarjeta teñida, la etiqueta de un
 * dato, el chevron que acompaña a un título.
 *
 * **Devuelve un COLOR, no una opacidad** (29 ago 2026). Hasta ese día esto era
 * `ACCENT_SECONDARY_ALPHA = 0.8f`, y su justificación era que M3 ofrece el par
 * `onSurface`/`onSurfaceVariant` para la escala neutra pero NO un `onPrimaryContainerVariant` para
 * los contenedores de acento — sin rol al que recurrir, atenuar parecía la única herramienta. El
 * error de ese razonamiento es que **un rol no es más que un color a un tono concreto**: teniendo
 * el contenido y su contenedor se puede calcular, que es lo que hace esta función, y entonces la
 * excepción deja de existir.
 *
 * No era solo cuestión de principio. Un alpha fijo produce un contraste que depende de lo que haya
 * debajo, así que el mismo 80 % daba 5,2:1 sobre un contenedor y 2,8:1 sobre otro — por debajo del
 * 4,5 que pide AA para `bodySmall`, medido en los banners del tema claro. Un tono, en cambio, se
 * elige POR el contraste que produce.
 *
 * Cómo: se toma el tono del contenido, se acerca [SECONDARY_TONE_SHIFT] puntos al del contenedor
 * —conservando matiz y croma, así que sigue siendo el mismo color y no un gris— y el resultado pasa
 * por [ensureContrast] con el suelo de AA, que lo devuelve si ya lo cumple y lo reproyecta si no. El
 * suelo es lo que hace segura la fórmula sobre un contenedor cualquiera: acercarse al fondo tiene un
 * límite, y ese límite es el mismo en todas partes.
 *
 * Ojo con el orden de los argumentos: [content] es el color del texto PRIMARIO (el `on…` del
 * contenedor), no el contenedor.
 */
fun secondaryContentColor(content: Color, container: Color): Color {
    val contentHct = Hct.fromInt(content.toArgb())
    val containerTone = Hct.fromInt(container.toArgb()).tone
    // Hacia el contenedor, sea cual sea el lado: sobre un contenedor claro el secundario se aclara
    // y sobre uno oscuro se oscurece. Es lo que mantiene la regla válida en los dos temas sin una
    // rama por tema.
    val toward = if (containerTone > contentHct.tone) SECONDARY_TONE_SHIFT else -SECONDARY_TONE_SHIFT
    val shifted = (contentHct.tone + toward).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    val dimmed = Color(Hct.from(contentHct.hue, contentHct.chroma, shifted).toInt())
    return ensureContrast(dimmed, container, SECONDARY_MIN_CONTRAST)
}

/**
 * [secondaryContentColor] memorizado, que es como debe consumirse desde composición.
 *
 * La cuenta no es barata: cuatro conversiones a CAM16 más uno o dos `HctSolver`, que es búsqueda
 * iterativa contra el gamut sRGB. Se llamaba cruda en la composición de los seis sitios que la
 * usan, y uno de ellos es la rejilla de géneros, o sea **una vez por celda visible en cada
 * recomposición**. Las otras derivaciones HCT de la app ya van memorizadas
 * ([rememberTonalLayerColors], `rememberRowAccentColor`, `rememberActiveRowContentColor`); ésta se
 * quedó fuera del patrón sin ningún motivo.
 *
 * Se queda la versión no-`@Composable` porque hay callers que no lo son (el widget, que no tiene
 * composición de Compose UI detrás).
 */
@Composable
fun rememberSecondaryContentColor(content: Color, container: Color): Color =
    remember(content, container) { secondaryContentColor(content, container) }

/**
 * Cuánto se acerca el secundario a su contenedor, en puntos de tono HCT. Suficiente para que se lea
 * como segundo nivel de un vistazo, y no más: por encima de esto el suelo de contraste entra en
 * juego en casi todos los contenedores y el ajuste dejaría de notarse donde sí cabía.
 */
private const val SECONDARY_TONE_SHIFT = 14.0

/** Suelo del secundario: AA para texto pequeño, que es lo que casi siempre es. */
private const val SECONDARY_MIN_CONTRAST = 4.5f

/**
 * Pista NO recorrida de una barra de progreso teñida con el acento. El mismo color que el tramo
 * recorrido, muy rebajado: es lo que hace que la barra se lea como una pieza —un canal y su
 * relleno— en vez de como dos colores distintos.
 *
 * Estaba repetido en los banners de sync y en las barras del reproductor. Ojo con confundirlo con el
 * `0.2f` que algunos callers pasan al construir `inactiveTrackColor`: ese alpha lo SOBRESCRIBE el
 * componente al pintar (`copy` reemplaza el canal, no lo multiplica), así que no es el que se ve.
 */
internal const val ACCENT_TRACK_ALPHA = 0.25f

/** Contraste mínimo por defecto del contenido sobre la capa: AA de texto. */
private const val TONAL_LAYER_MIN_CONTRAST = 4.5f

/**
 * Extremos de la escala de tono HCT (negro y blanco). No es una decisión de esta app sino el rango
 * que define HCT, y hay que acotar a él cada vez que se DESPLAZA un tono: `Hct.from` con un tono
 * fuera de rango no falla, devuelve otro color. Estaba escrito como `coerceIn(0.0, 100.0)` en las
 * cuatro superficies que derivan tono (aquí, el MiniPlayer ×2 y el toolbar del NowPlaying).
 */
internal const val HCT_TONE_MIN = 0.0
internal const val HCT_TONE_MAX = 100.0

/** Par contenedor/contenido de una superficie derivada del fondo que tiene debajo. */
@Immutable
data class TonalLayerColors(val container: Color, val content: Color)

/**
 * Colores de una superficie que se apoya sobre [background], DERIVADOS de ese fondo real en vez de
 * un rol fijo de la paleta.
 *
 * Por qué no un rol a secas: los roles de contenedor viven en la MISMA banda tonal que las
 * superficies sobre las que se apoyan (`secondaryContainer` ≈ `surfaceContainerHigh`, ~90 vs 92), así
 * que la capa se ve o no según lo que la paleta activa haya hecho con esa banda — y sobre un fondo
 * que ES ese mismo rol desaparece del todo. Los parches habituales (un borde, una sombra, interpolar
 * hacia blanco o negro) no pueden arreglarlo: no GARANTIZAN separación, que es lo único que se pide.
 *
 * La regla: se conservan hue y croma de [role] (sigue siendo un color de la paleta, no un gris
 * inventado) y se le fija el TONO a [TONAL_LAYER_DELTA] puntos del fondo, alejándose del extremo de
 * la escala. Es como M3 construye sus propios niveles de superficie, así que la separación sale
 * idéntica en cualquier paleta y en cualquiera de los dos temas: la dirección la decide el tono
 * MEDIDO del fondo, no una rama `isSystemInDarkTheme()`.
 *
 * **Sirve para UNA capa sobre otra, no para apilar tres.** Al derivar dos veces seguidas con
 * "aléjate del extremo", un fondo de tono medio hace que la segunda invierta el sentido y vuelva al
 * color de la primera (probado el 31 jul en el MiniPlayer, cuyo botón tenía que verse a la vez sobre
 * el contenedor y sobre el relleno de progreso). Quien apila mide las DOS capas contra el MISMO
 * fondo, para que la dirección se decida una sola vez — ver `appLibraryToolbarColors`.
 */
@Composable
fun rememberTonalLayerColors(
    background: Color,
    role: Color = AppColors.secondaryContainer,
    onRole: Color = AppColors.onSecondaryContainer,
    minContrast: Float = TONAL_LAYER_MIN_CONTRAST,
    /**
     * Cuánto se separa la capa. Lo elige el caller porque no es la REGLA sino el trabajo de esa capa
     * concreta —lo mismo que ya hacía [tonalLayerContainer], al que solo faltaba dejarlo pasar desde
     * aquí—. Un fondo vívido necesita más que uno neutro: sobre un color saturado el croma domina la
     * percepción y no cambia entre las dos capas, así que el salto tonal se lee menos.
     *
     * **La DIRECCIÓN sigue sin ser elegible**: la decide el tono medido del fondo.
     */
    delta: Double = TONAL_LAYER_DELTA
): TonalLayerColors = remember(background, role, onRole, minContrast, delta) {
    val container = tonalLayerContainer(background, role, delta)
    TonalLayerColors(
        container = container,
        content = ensureContrast(onRole, container, minContrast)
    )
}

/**
 * Solo el CONTENEDOR de la regla de [rememberTonalLayerColors]: el hue y el croma de [role] llevados
 * al tono que queda a [delta] puntos de [background], alejándose del extremo de la escala.
 *
 * Existe aparte porque hay capas que no necesitan el par completo —el resaltado de la fila que suena
 * ya resuelve su contenido con `rememberActiveRowContentColor`—, y la regla tiene que estar escrita
 * UNA sola vez: es lo único que garantiza que dos superficies derivadas del mismo fondo caigan en el
 * mismo sitio. No es `@Composable` a propósito: los callers la envuelven en su propio `remember`.
 *
 * Lo ÚNICO que cada caller elige es [delta] —cuánto se separa—, porque eso no es la regla sino el
 * trabajo de esa capa concreta. La DIRECCIÓN nunca se elige: la decide el tono medido del fondo.
 */
fun tonalLayerContainer(
    background: Color,
    role: Color,
    delta: Double = TONAL_LAYER_DELTA
): Color {
    val backgroundTone = Hct.fromInt(background.toArgb()).tone
    val roleHct = Hct.fromInt(role.toArgb())
    val tone = (
        if (backgroundTone > TONAL_LAYER_MID_TONE) backgroundTone - delta
        else backgroundTone + delta
    ).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    return Color(Hct.from(roleHct.hue, roleHct.chroma, tone).toInt())
}

/**
 * El PAR de roles de M3 —contenedor y su `on…`— que mejor se apoya sobre [background]: de los pares
 * cuyo contenedor esté a [minToneDelta] puntos de TONO o más del fondo, **el MÁS CERCANO**; si
 * ninguno llega, el más lejano de todos.
 *
 * **Que sea el más cercano y no el primero es lo que impide que grite** (corregido el 6 sep, tras
 * tres builds). Buscando "el primero que pase" el resultado depende de un orden de preferencia que
 * yo escribo a mano, y quedándose con el más lejano cuando ninguno pasa se elige a propósito el
 * salto MÁS GRANDE que existe: en un fondo donde los candidatos están a 0 o a 50 puntos, eso es
 * siempre 50. Una píldora informativa quiere el escalón mínimo que todavía se distingue, no el
 * máximo — de ahí que [pairs] sea un catálogo y no una lista de preferencia.
 *
 * **SELECCIONA, no deriva.** Lo que devuelve es siempre un par que M3 tabula, así que el contraste
 * de la etiqueta sobre la píldora lo garantiza el spec y aquí no se calcula: la única decisión es
 * CUÁL de los pares se apoya mejor en el fondo que hay debajo.
 *
 * **El criterio es el TONO y no el ratio WCAG**, y esa es la diferencia con [contentRoleOn]. Un ratio
 * mide CONTENIDO sobre un contenedor; lo de aquí son dos SUPERFICIES, y la luminancia se comprime
 * cerca del blanco, así que el mismo salto tonal da ratios muy distintos según dónde caiga en la
 * escala — medido: `secondaryContainer` contra el fondo del reproductor da 1,23:1 en tema claro y
 * 1,98:1 en oscuro, o sea que un umbral único no llega nunca de un lado y llega siempre del otro. En
 * HCT el tono ES el `L*` de CIELAB, así que un salto de N puntos vale lo mismo en cualquier matiz y
 * en los dos temas; es la misma razón por la que `BannerPalette` fija tonos y no ratios.
 *
 * Consecuencia buscada: el par elegido CAMBIA con el tema, porque el fondo cambia con el tema. Sobre
 * un fondo oscuro gana un contenedor de tono medio; sobre uno claro, uno que se hunda. Eso es lo que
 * un rol fijo no puede hacer — y es lo que dejaba a `secondaryFixedDim` discreto en claro y gritando
 * en oscuro, porque los `*Fixed` son invariantes al tema y su PESO no lo es.
 *
 * No promete separación, la busca: sin derivar no hay garantía posible, y el respaldo honesto es
 * quedarse con el par que más se aleja en vez de inventar un color.
 */
/**
 * Los pares candidatos de una superficie del REPRODUCTOR que se apoya en otra: el chip de origen, los
 * chips de la barra de progreso y los dos botones del transporte del MiniPlayer, vía [roleLayerOn] y
 * [transportLayersOn]. Se llamó `chipRolePairs` hasta el 8 sep 2026, cuando dejó de ser solo de chips.
 *
 * **No es una lista de preferencia: es el CATÁLOGO de escalones disponibles**, y [roleLayerOn] elige
 * el más pequeño que aún se vea. Por eso incluye cada par en las DOS orientaciones — la banda tonal
 * que hay libre depende del fondo, y el contraste que M3 garantiza entre un contenedor y su `on…`
 * vale en los dos sentidos, así que usar el `on…` como superficie sigue siendo un par tabulado.
 *
 * Sin las dos orientaciones esto NO cubre el caso en que el fondo **es** un rol de contenedor, que es
 * justo el del chip de origen: se apoya en `topBackgroundColor`, que en modo degradado es
 * `secondaryContainer` mismo. Ahí los pares normales miden distancia CERO —un rol no se separa de sí
 * mismo— y toda su banda queda descartada de una vez. Medido en device el 6 sep: en esa paleta
 * tampoco llegaba `onSecondary`, así que lo único que quedaba en pie eran los `*FixedDim` (T80) y la
 * píldora salía clara y gritona tres builds seguidas.
 *
 * **SOLO familias `primary` y `secondary`: los pares TERCIARIOS se sacaron el 7 sep 2026** (visto en
 * device, carátula teal). En TonalSpot la paleta terciaria es el matiz primario **+60°**, así que con
 * un `primary` mint es lo ÚNICO morado que ese esquema puede producir — y salía: los cuatro chips del
 * reproductor (origen, los dos tiempos y el formato) eran las únicas piezas con matiz propio de una
 * pantalla entera teal, o sea el mayor peso visual para la información más periférica.
 *
 * **La causa no era el rol, era que [roleLayerOn] mide SOLO tono y trata el salto de FAMILIA como
 * gratis.** No lo es: el matiz es la diferencia perceptual más fuerte que existe, así que un
 * candidato terciario a 8 puntos le ganaba a uno secundario a 10. Y como en el spec 2025 los tonos de
 * `secondary` y `tertiary` para el mismo rol difieren por décimas, la FAMILIA del chip la decidía un
 * desempate de fracciones de tono — el mismo "margen cero" que dejó las rejillas en una columna.
 *
 * Se arregla en el CATÁLOGO y no en el criterio a propósito: quien se apoya en esto son piezas del
 * reproductor, y el reproductor ya tiene sus dos familias —`primary` el play, `secondary` el fondo—.
 * Una pieza del reproductor no estrena una tercera. Si algún día hiciera falta un catálogo con
 * terciarios para otra superficie, es otro catálogo, no un tercer parámetro aquí.
 */
fun playerRolePairs(scheme: AppColorScheme): List<Pair<Color, Color>> = listOf(
    scheme.secondaryContainer to scheme.onSecondaryContainer,
    scheme.primaryContainer to scheme.onPrimaryContainer,
    scheme.onSecondary to scheme.secondary,
    scheme.secondaryFixedDim to scheme.onSecondaryFixed,
    scheme.primaryFixedDim to scheme.onPrimaryFixed,
    // Los `*Fixed` invertidos: el escalón hacia ABAJO desde un fondo de contenedor.
    scheme.onSecondaryFixed to scheme.secondaryFixedDim,
    scheme.onPrimaryFixed to scheme.primaryFixedDim,
)

fun roleLayerOn(
    background: Color,
    pairs: List<Pair<Color, Color>>,
    minToneDelta: Double = TONAL_LAYER_DELTA
): Pair<Color, Color> {
    val backgroundTone = Hct.fromInt(background.toArgb()).tone
    fun toneDistance(color: Color) = abs(Hct.fromInt(color.toArgb()).tone - backgroundTone)
    // El escalón MÁS PEQUEÑO que todavía se ve, no el primero que pasa ni el más grande.
    return pairs.filter { toneDistance(it.first) >= minToneDelta }.minByOrNull { toneDistance(it.first) }
        ?: pairs.maxBy { toneDistance(it.first) }
}

/**
 * Separación en puntos de tono ENTRE los dos rangos del MiniPlayer, y suelo del rango de apoyo
 * contra la barra.
 *
 * **El DOBLE del escalón de una capa pasiva, y su propia constante.** El doble por el mismo motivo
 * que el relleno del aleatorio en la hoja de la cola: [TONAL_LAYER_DELTA] es lo que hace falta para
 * que una capa no se FUNDA con su fondo, y aquí no basta con no fundirse — hay que leer un RANGO
 * entre dos piezas del mismo tamaño puestas una al lado de la otra. Propia y no [TONAL_LAYER_DELTA]
 * porque calibrar la jerarquía del transporte no puede mover la píldora del ⋮ ni el chip de origen,
 * igual que el resaltado de la fila que suena tiene su `ROW_ACTIVE_TONE_DELTA`.
 */
const val TRANSPORT_TONE_STEP = TONAL_LAYER_DELTA * 2

/**
 * Los DOS rangos del MiniPlayer, con la separación entre ellos GARANTIZADA.
 *
 * - **[accent] / [onAccent]** — lo principal: el botón de play y el ARCO del anillo de progreso.
 * - **[support] / [onSupport]** — lo de apoyo: el botón "siguiente" y el RIEL del anillo.
 *
 * Que las cuatro piezas salgan de aquí es el punto: cada una elegía su color por separado, con un
 * umbral absoluto contra la barra y listas de candidatos que se solapaban, y **nada decía que dos de
 * ellas tuvieran que distinguirse ENTRE SÍ**. Medido en device el 8 sep 2026 sobre una paleta roja
 * (barra `primaryContainer` en T35,8): las cuatro convergieron a DOS colores separados por **1,9
 * puntos de tono** —arco y play en `#F4BEB2` (T81,5), riel y siguiente en `#F3B7AC` (T79,6)—, o sea
 * el mini entero pintado en una sola capa plana. El play encima llevaba glifo NEGRO PURO, la única
 * pieza acromática de una barra teñida por la carátula.
 */
data class TransportLayers(
    val accent: Color,
    val onAccent: Color,
    val support: Color,
    val onSupport: Color
)

/**
 * Reparte las piezas del MiniPlayer en los dos rangos de [TransportLayers] sobre [background].
 *
 * **El principal sale del catálogo TABULADO; el de apoyo se DERIVA de él.** No al revés y no los dos
 * del catálogo, y el motivo está medido: sobre la barra de la captura del 8 sep, los contenedores
 * candidatos del lado claro caen en un racimo de **40,7 / 43,8 / 44,1** puntos —3,4 de ancho, o sea
 * el mismo color tres veces— y el siguiente salta a `onSecondary`, a ~64, que en tema claro es
 * prácticamente BLANCO. Buscar los dos rangos en esa lista solo puede devolver dos vecinos del
 * racimo (el defecto que esto viene a arreglar) o mandar el play al extremo acromático, que es el
 * "si no, el más lejano" que [roleLayerOn] documenta como la opción a propósito más gritona.
 *
 * Derivar es aquí el ÚLTIMO recurso, no el atajo: el catálogo se agotó de verdad. Y se deriva el
 * APOYO y no el principal porque hacia el fondo es donde queda sitio — el principal ya está a ~44
 * puntos de la barra y subirlo más solo lo acerca al blanco. El apoyo conserva matiz y croma del par
 * tabulado, así que sigue siendo el mismo color un escalón más cerca de la barra, nunca un gris.
 *
 * Reglas que no son libres:
 * - **Un solo lado**, el que se aleja del extremo de la escala que la barra tenga más cerca (mismo
 *   criterio y misma constante que [tonalLayerContainer]). Mezclar direcciones dejaría una pieza más
 *   clara y otra más oscura que la barra, que no es jerarquía sino un error. **Por eso NO se delega
 *   en [roleLayerOn]**, que busca en los dos lados: sobre esta barra su escalón mínimo global cae en
 *   el lado OSCURO (`onSecondaryFixed`, T9,7) y devolvería piezas marrón oscuro sobre rojo oscuro.
 *   Su criterio está calibrado para un chip que debe RECEDER; el transporte es la acción de la barra.
 * - **El principal es el escalón más pequeño que aún se ve** de ese lado — dentro del racimo, el más
 *   cercano es además el más CROMÁTICO, que es el que mejor lleva el color de la carátula.
 * - **El apoyo nunca baja de [TONAL_LAYER_DELTA] respecto de la barra.** Si al bajarlo [rankStep] no
 *   cabe, el que SUBE es el principal: lo que se conserva pase lo que pase es la distancia ENTRE los
 *   dos rangos, que es lo único que hace que el play resalte.
 */
/**
 * El catálogo del TRANSPORTE: [playerRolePairs] **más los dos pares de ACCIÓN LLENA**
 * (`primary`/`onPrimary` y `secondary`/`onSecondary`).
 *
 * **Existe porque [playerRolePairs] es ASIMÉTRICO** (visto en device el 8 sep 2026 con la paleta
 * Equilibrada: *"parece que está agarrando neutros"*). Hacia ARRIBA ofrece roles de CONTENEDOR
 * —`secondaryContainer`, `primaryContainer`, los `*FixedDim`—, que llevan el croma de la paleta;
 * hacia ABAJO ofrece solo los pares invertidos `on*Fixed`, que son roles de CONTENIDO: están
 * construidos para ser LEGIBLES, no para llevar color. Con una barra CLARA —lo que da TonalSpot en
 * tema claro— el transporte quedaba encerrado en la mitad neutra. MEDIDO: barra `#AFC7F3` (T79,9)
 * con 68 puntos de separación R↔B, y el play cayendo en `#37404F` (T26,9) con **24**. La mecánica
 * era correcta —16,0 puntos exactos entre rangos, dirección correcta— pero el color de partida ya
 * venía lavado. En esa misma paleta `primary` es `#486086` (T40,4, R↔B 62) y está a 39,5 puntos de
 * la barra, o sea que gana como escalón MÁS PEQUEÑO hacia abajo frente a los `on*Fixed` a 53.
 *
 * **Se arregla en el CATÁLOGO y no en el criterio**, que es lo que [playerRolePairs] ya documenta
 * para el caso de los terciarios. Y es OTRO catálogo, no una entrada nueva en aquél: un relleno
 * `primary` en un chip informativo GRITA —por eso no está ahí— mientras que en el botón de
 * transporte es exactamente el par que M3 tabula para una acción llena. La diferencia no es de
 * color sino de qué ES la pieza.
 *
 * Los `on*` de estos pares son claros (blanco o casi), así que llegan al ratio de un glifo sobre su
 * propio contenedor por construcción; el rango de apoyo, que se DERIVA, los pasa igualmente por
 * [ensureContrast].
 */
/**
 * Suelo de contraste de un glifo de ACCIÓN. 3:1 = el mínimo WCAG para un icono, no para texto.
 */
const val ACTION_ACCENT_MIN_CONTRAST = 3f

/**
 * Suelo de contraste de una etiqueta de TEXTO en acento. **4.5:1 y no los 3:1 de un icono**: el
 * `labelLarge` de un botón mide 14sp, que no llega al umbral de "texto grande" de WCAG (18pt, o
 * 14pt en negrita), así que le toca el criterio estricto.
 */
const val TEXT_ACCENT_MIN_CONTRAST = 4.5f

/**
 * El color de un glifo de ACCIÓN tintado —el play de una fila de artista, el de una card de álbum—
 * sobre una superficie de CONTENIDO.
 *
 * **`primary` a secas NO sirve aquí**: un acento no tiene garantía de contraste contra una
 * superficie NEUTRA, así que con una carátula lavada el disco desaparece. Es el mismo defecto que ya
 * se arregló el 7 sep 2026 en la fila de artista, en el botón "Ver" de estadísticas y en el glifo de
 * descarga de las filas de canción — **y `AlbumTileCard` se quedó atrás**, con el acento fijo y el
 * comentario "En primary" al lado. MEDIDO el 8 sep en device sobre una paleta tostada, con las dos
 * piezas apoyadas en la MISMA `AppColors.contentSurface`: la fila daba `#745840` a **5,31:1** y la
 * card `#DA9E62` a **1,89:1**, o sea 30 puntos de tono de diferencia entre dos controles idénticos.
 *
 * Existe como helper y no copiado en cada sitio justo por eso: era una regla escrita una vez por
 * consumidor, y el cuarto consumidor no se enteró. Mismo motivo que `detailHeaderScrim()`.
 *
 * [contentRoleOn] es role-first: prueba los acentos tabulados contra el fondo REAL y solo
 * re-tonaliza `primary` si ninguno llega.
 */
@Composable
fun actionAccentOn(
    surface: Color,
    /** [ACTION_ACCENT_MIN_CONTRAST] para un glifo; [TEXT_ACCENT_MIN_CONTRAST] para una etiqueta. */
    minRatio: Float = ACTION_ACCENT_MIN_CONTRAST
): Color {
    val scheme = LocalAppColors.current
    return remember(scheme.version, surface, minRatio) {
        contentRoleOn(
            surface, minRatio,
            listOf(scheme.primary, scheme.secondary, scheme.onSecondaryContainer),
            scheme.primary,
        )
    }
}

fun transportRolePairs(scheme: AppColorScheme): List<Pair<Color, Color>> =
    playerRolePairs(scheme) + listOf(
        scheme.primary to scheme.onPrimary,
        scheme.secondary to scheme.onSecondary,
    )

fun transportLayersOn(
    background: Color,
    pairs: List<Pair<Color, Color>>,
    minToneDelta: Double = TONAL_LAYER_DELTA * 2,
    rankStep: Double = TRANSPORT_TONE_STEP
): TransportLayers {
    val backgroundTone = Hct.fromInt(background.toArgb()).tone
    fun tone(color: Color) = Hct.fromInt(color.toArgb()).tone
    fun distance(color: Color) = abs(tone(color) - backgroundTone)
    val goesLighter = backgroundTone < TONAL_LAYER_MID_TONE

    val visible = pairs.filter { distance(it.first) >= minToneDelta }
    // Si el lado que toca no ofrece nada visible se acepta el otro antes que rendirse; y si no hay
    // ninguno, el más lejano, que es el mismo escape que [roleLayerOn].
    val base = visible.filter { (tone(it.first) >= backgroundTone) == goesLighter }
        .ifEmpty { visible }
        .ifEmpty { listOf(pairs.maxBy { distance(it.first) }) }
        .minBy { distance(it.first) }

    val baseHct = Hct.fromInt(base.first.toArgb())
    // Cuánto se puede acercar el apoyo a la barra; lo que falte se lo aleja el principal, de modo
    // que `accentTone - supportTone` sea SIEMPRE `rankStep`.
    //
    // El suelo del apoyo es el MISMO [minToneDelta] que filtra a los candidatos, y no
    // [TONAL_LAYER_DELTA]: los dos rangos son botones, y el motivo por el que el filtro pide el
    // doble del escalón de una capa pasiva —un botón no puede recederse hasta el límite de lo
    // perceptible— vale igual para el de apoyo. Con el suelo de 8 había un caso REAL en el que el
    // "siguiente" quedaba pegado a la barra: un `primary` que cae a ~16 puntos de ella gana como
    // escalón más pequeño y empuja al apoyo justo al mínimo (simulado el 8 sep 2026 sobre los tonos
    // medidos en device).
    val lift = (rankStep - (distance(base.first) - minToneDelta)).coerceAtLeast(0.0)
    val away = if (goesLighter) 1.0 else -1.0
    val accentTone = (baseHct.tone + away * lift).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    val supportTone = (accentTone - away * rankStep).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    fun shade(toneValue: Double) = Color(Hct.from(baseHct.hue, baseHct.chroma, toneValue).toInt())

    // El principal se queda con el color TABULADO mientras no haya hecho falta subirlo.
    val accent = if (lift == 0.0) base.first else shade(accentTone)
    val support = shade(supportTone)
    return TransportLayers(
        accent = accent,
        onAccent = ensureContrast(base.second, accent),
        support = support,
        onSupport = ensureContrast(base.second, support)
    )
}

/**
 * CONTENIDO sobre un fondo: el PRIMER rol de [roles] que se lee sobre [background] con contraste
 * >= [minRatio] y, solo si ninguno llega, [accent] con el TONO HCT movido hasta ese ratio
 * ([ensureContrast]) — conservando su matiz y su croma.
 *
 * **Role-first, derivación como ÚLTIMO recurso**: se prueban roles TABULADOS de M3 en orden de
 * preferencia y solo se fabrica un color cuando ninguno sirve. Existe porque la distancia entre dos
 * roles la decide el ESTILO DE PALETA y el seed, así que ningún rol fijo se ve en TODAS las
 * carátulas — con `Fidelity` y una portada lavada los tres acentos palidecen a la vez.
 *
 * [roles] no lleva neutros a propósito: el resultado siempre es tintado (un rol de acento o un
 * acento re-tonalizado), nunca un gris.
 *
 * El gemelo para SUPERFICIES es [roleLayerOn], que escoge por TONO y nunca deriva. La unidad no es
 * intercambiable: aquí se mide con ratio WCAG porque esto es contenido sobre un fondo.
 */
fun contentRoleOn(background: Color, minRatio: Float, roles: List<Color>, accent: Color): Color =
    roles.firstOrNull { contrastRatio(it, background) >= minRatio }
        ?: ensureContrast(accent, background, minRatio)

/**
 * Ratio de contraste WCAG entre dos colores: (L_claro + 0.05) / (L_oscuro + 0.05), en [1, 21].
 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = androidx.core.graphics.ColorUtils.calculateLuminance(a.toArgb())
    val lb = androidx.core.graphics.ColorUtils.calculateLuminance(b.toArgb())
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return ((hi + 0.05) / (lo + 0.05)).toFloat()
}

/**
 * Garantiza contraste WCAG >= [minRatio] de [content] sobre [container] CONSERVANDO el matiz del
 * acento: si el par ya cumple, se devuelve tal cual; si no, se le fija a [content] el TONO HCT que
 * da el ratio pedido contra el fondo, hacia el extremo que ese fondo admita.
 *
 * **Va en HCT y no en HSL, y esa es la corrección del 17 ago.** Hasta esa fecha barría la luminosidad
 * HSL conservando `h` y `s`, y eso se rompe con cualquier color CERCA DE BLANCO —que es justo lo que
 * suelen ser los roles `on*` que entran aquí—: en HSL la saturación se calcula sobre `2 − max − min`,
 * así que un color casi blanco tiene saturación **1.0** y su matiz lo decide una diferencia de 1/255
 * entre canales. El `onPrimaryContainer` típico de Material You a tono alto es `#FFFBFF` (el azul a
 * tope lo pone el gamut mapping de HCT), o sea HSL `h=300, s=1.0` — matiz MAGENTA con saturación
 * máxima donde el ojo ve blanco. Bajar la L materializaba ese matiz fantasma: medido, el glifo del
 * botón "siguiente" del MiniPlayer salía `#4E004E` (morado saturado) sobre una barra MARRÓN.
 * En HCT ese mismo `#FFFBFF` tiene croma ~4, que es la verdad, y mover el tono devuelve un neutro.
 *
 * Por qué no basta [accentTone] con L fija (0.15/0.92): la luminosidad HSL NO es la luminancia
 * perceptual, así que un mismo L da ratios distintos por matiz/saturación — no garantiza 3:1. El tono
 * HCT sí es luminancia perceptual, y `Contrast` resuelve el tono exacto sin barrer.
 * 3:1 = mínimo WCAG para iconos/componentes y texto grande.
 *
 * **No siempre puede cumplir, y no lo finge:** contra un fondo de tono medio el ratio es inalcanzable
 * por los dos lados (ni negro ni blanco llegan a 4.5:1), y entonces se devuelve el extremo, o sea el
 * máximo que ese lado da. Es la misma acotación que ya hacen `rememberMiniPlayerColors` y
 * `rememberTonalLayerColors`.
 *
 * **Prueba las DOS direcciones y, entre las que YA cumplen [minRatio], se queda con la de MÁS
 * croma** (1 sep 2026, en vez de probar solo la de más margen — [contrastRatio] entre
 * [Color.Black]/[Color.White] contra [container] ya no decide nada por su cuenta). El piso NO se
 * relaja NUNCA —eso es justo lo que este ajuste corrige de una versión anterior—, esto solo decide
 * ENTRE soluciones que YA lo cumplen. Antes, cuando una sola dirección alcanzaba el margen más
 * grande, era la ÚNICA que se probaba: MEDIDO en el toggle "always on" del NowPlaying con Fiel a la
 * carátula, eso mandaba el glifo a `RGB(240,244,255)` (prácticamente blanco) sobre un disco
 * claramente azul, cuando la dirección oscura TAMBIÉN llegaba a 4.5:1 con mucho más matiz — nunca
 * se llegaba a comparar.
 */
fun ensureContrast(content: Color, container: Color, minRatio: Float = 3f): Color {
    if (contrastRatio(content, container) >= minRatio) return content
    val hct = Hct.fromInt(content.toArgb())
    val containerTone = Hct.fromInt(container.toArgb()).tone
    // `*Unsafe` devuelve un tono fuera de [0,100] cuando el ratio es inalcanzable por ese lado; ese
    // valor sirve igual, porque lo único que se hace con él es acotarlo.
    val darkTone = Contrast.darkerUnsafe(containerTone, minRatio.toDouble()).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    val lightTone = Contrast.lighterUnsafe(containerTone, minRatio.toDouble()).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
    val darkColor = Color(Hct.from(hct.hue, hct.chroma, darkTone).toInt())
    val lightColor = Color(Hct.from(hct.hue, hct.chroma, lightTone).toInt())
    val darkOk = contrastRatio(darkColor, container) >= minRatio
    val lightOk = contrastRatio(lightColor, container) >= minRatio
    val (tone, toned) = when {
        darkOk && lightOk ->
            if (Hct.fromInt(darkColor.toArgb()).chroma >= Hct.fromInt(lightColor.toArgb()).chroma) {
                darkTone to darkColor
            } else {
                lightTone to lightColor
            }
        darkOk -> darkTone to darkColor
        lightOk -> lightTone to lightColor
        // Ninguna llega exacta (redondeo de `*Unsafe` cerca del extremo, ver abajo): se queda la
        // de más contraste real, como antes de este ajuste.
        else -> if (contrastRatio(darkColor, container) >= contrastRatio(lightColor, container)) {
            darkTone to darkColor
        } else {
            lightTone to lightColor
        }
    }
    // `Hct.from` prioriza el TONO y cede croma cuando el matiz no cabe en sRGB, así que la luminancia
    // sale la del tono pedido; el redondeo de esa proyección puede dejarlo unas milésimas corto y por
    // eso se MIDE. Al ceder el croma (gris puro del mismo tono) el ratio es exacto por construcción.
    return if (contrastRatio(toned, container) >= minRatio || tone <= HCT_TONE_MIN || tone >= HCT_TONE_MAX) toned
    else Color(Hct.from(hct.hue, 0.0, tone).toInt())
}

/**
 * Versión VIVA de un acento para botones SOBRE carátulas (quick-play de álbum, badge del
 * home): el `primary` seedeado con PaletteStyle.Fidelity puede salir apagado u oscuro y
 * perderse contra el arte — aquí se fuerza saturación y brillo a una banda vibrante.
 * GUARDA: un acento casi gris (carátula monocroma) NO se satura — colorToHSV le da hue 0
 * y "avivarlo" lo teñiría de rojo inventado; en ese caso solo se ajusta el brillo.
 * El contenido encima debe usar [onContainerColor].
 */
fun vividAccentColor(base: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    if (hsv[SATURATION] >= VIVID_MIN_SATURATION) {
        hsv[SATURATION] = hsv[SATURATION].coerceAtLeast(VIVID_TARGET_SATURATION)
    }
    hsv[VALUE] = hsv[VALUE].coerceIn(VIVID_MIN_VALUE, VIVID_MAX_VALUE)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Índices del array que devuelve `Color.colorToHSV`. */
private const val SATURATION = 1
private const val VALUE = 2

/**
 * Saturación por debajo de la cual un color se considera ACROMÁTICO y no se aviva. Es la guarda del
 * kdoc de [vividAccentColor]: `colorToHSV` le da hue 0 a un gris, así que saturarlo lo teñiría de
 * un rojo que no está en la carátula. Solo decide si el color entra o no en el ajuste — el juicio
 * de "¿es gris?" que gobierna el TEMA se hace en croma HCT (`ArtworkRepository.isAchromatic`), que
 * es independiente del tono; aquí basta con una criba sobre el color ya elegido.
 */
private const val VIVID_MIN_SATURATION = 0.15f

/** Saturación mínima a la que se lleva un color cromático para que no se pierda sobre la carátula. */
private const val VIVID_TARGET_SATURATION = 0.65f

/**
 * Banda de brillo del acento.
 *
 * **El suelo es el que trabaja**: estos distintivos son cuadrados de ~24 dp sobre una CARÁTULA
 * arbitraria, y con el estilo "fiel a la carátula" el `primary` puede venir muy oscuro, en cuyo caso
 * el distintivo se pierde contra una portada oscura. Subirlo garantiza que se despegue de la imagen
 * pase lo que pase.
 *
 * El techo es una guarda menor: evita que quede tan claro que se lea como blanco en vez de como
 * color. NO hace falta para que el icono de dentro se vea —de eso se ocupa `onContainerColor`, que
 * elige negro o blanco según la luminancia del distintivo— y por eso puede ir tan alto.
 *
 * Ninguno de los dos está medido: son una banda elegida para que el resultado sea siempre un color
 * vivo y visible, y su acierto se juzga MIRANDO las tarjetas del inicio sobre carátulas distintas,
 * no razonando sobre los números.
 */
private const val VIVID_MIN_VALUE = 0.75f
private const val VIVID_MAX_VALUE = 0.95f

/**
 * Barra de progreso Expressive estilo Apple Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeek: ((Long) -> Unit)? = null,
    trackColor: Color = AppColors.primary,
    inactiveTrackColor: Color = AppColors.surfaceContainerHighest,
    textColor: Color = AppColors.onSurfaceVariant,
    showThumb: Boolean = true,
    trackHeight: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Cálculo DIRECTO, sin remember{derivedStateOf}: currentPosition/duration son parámetros
    // planos (no State) y el derivedStateOf cacheaba la lambda de la PRIMERA composición —
    // la barra quedaba congelada en la posición inicial y al soltar un drag "rebotaba" ahí
    // aunque el seek de audio sí se ejecutara. La recomposición por cambio de parámetro ya
    // recalcula esto solo.
    val displayPosition = if (isDragging) sliderPosition
    else if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val thumbSize by animateDpAsState(
        targetValue = if (showThumb && (isDragging || isPressed)) 18.dp else 0.dp,
        // Spatial: es un tamaño. El rebote del token es lo que le da el "pop" al agarrar el thumb.
        animationSpec = appSpatialSpec(),
        label = "ThumbSize"
    )

    val formattedDuration = remember(duration) { formatTime(duration) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (onSeek != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = displayPosition,
                    onValueChange = {
                        isDragging = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeek((sliderPosition * duration).toLong())
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(thumbSize)
                                .shadow(if (thumbSize.value > 0f) 4.dp else 0.dp, CircleShape)
                                .background(Color.White, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        val fraction = sliderState.value
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(CircleShape)
                                .background(inactiveTrackColor.copy(alpha = ACCENT_TRACK_ALPHA))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(trackColor)
                            )
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = trackColor,
                        inactiveTrackColor = Color.Transparent
                    )
                )
            }
        } else {
            LinearProgressIndicator(
                progress = { if (duration > 0) currentPosition.toFloat() / duration else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape),
                color = trackColor,
                trackColor = inactiveTrackColor.copy(alpha = ACCENT_TRACK_ALPHA)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
    }
}
