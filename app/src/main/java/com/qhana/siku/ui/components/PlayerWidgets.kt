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

import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import com.qhana.siku.ui.theme.AppColors
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
 * **El color es `tertiaryContainer`, el tercer acento**, y su elección es una posición en una escala
 * de tres, no un gusto:
 *
 *  - **destaca sobre los chips de FILTRO y de ORDEN**, que van en neutro porque su color ya
 *    significa otra cosa —en un `FilterChip` el relleno de acento ES su estado seleccionado, así que
 *    teñirlo en reposo sería mentir sobre si está activo—;
 *  - **pesa menos que los BOTONES**, que se quedan con `secondaryContainer`. Con el mismo relleno
 *    que ellos era indistinguible de un `QuickActionButton` a centímetros de distancia (visto en
 *    device), y esa confusión es la que abrió todo este hilo.
 *
 * `tertiary` es además el único acento que quedaba libre: en esta app `primary` es lo que NAVEGA (la
 * tarjeta de estadísticas del inicio) y `secondary` lo que EJECUTA. Su otro uso es el contenedor de
 * una fila mientras se arrastra, un estado transitorio con el que no se puede confundir.
 *
 * De la regla general —*el acento marca lo accionable, lo que solo describe va en neutro*— esto es
 * la excepción medida: el neutro puro se probó y desaparecía entre los chips de filtro, que son
 * justo las piezas de las que tiene que distinguirse.
 *
 * Sigue siendo SÓLIDO y no un chip *flat* con borde, aunque eso sea lo que M3 da por defecto: dos de
 * estos viven sobre la foto del header de artista, y encima de una imagen un contenedor transparente
 * no se lee.
 */
@Composable
fun CountChip(
    modifier: Modifier = Modifier,
    containerColor: Color = AppColors.tertiaryContainer,
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
 * **Sirve para UNA capa sobre otra, no para apilar tres.** Vale para una píldora sobre un fondo,
 * pero no para el MiniPlayer, donde el botón tiene que verse a la vez sobre el contenedor y sobre el
 * relleno de progreso: al derivar dos veces seguidas con "aléjate del extremo", un fondo de tono
 * medio hace que la segunda invierta el sentido y vuelva al color del primero (probado el 31 jul,
 * ver `MiniPlayerSurfaces`, que apila en una sola dirección).
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
