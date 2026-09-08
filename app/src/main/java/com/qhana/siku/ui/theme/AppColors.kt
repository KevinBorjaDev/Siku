package com.qhana.siku.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.qhana.siku.data.util.JankProbe
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Los colores de la app, respaldados por **snapshot state** y con una **instancia estable**.
 *
 * ## Por qué existe (medido, 20 ago 2026)
 *
 * El acento de esta app sale de la carátula, así que cambia con CADA canción. Mientras ese cambio
 * viajó por `MaterialTheme`, cada canción costaba una recomposición del árbol ENTERO: 19 ms de
 * `Recomposer:recompose` en 67 bloques + 9 ms de `AndroidOwner:measureAndLayout`, o sea unos 28 ms
 * —tres frames a 120 Hz— en el frame en que el reproductor terminaba de abrirse. Eso NO era un bug
 * de la app: es la forma de la API. Las dos mitades, leídas de las fuentes de la versión pineada:
 *
 * ```kotlin
 * // material3 1.5.0-alpha24, MaterialTheme.kt:307
 * private val _localMaterialTheme = staticCompositionLocalOf { MaterialTheme.Values() }
 * // ColorScheme.kt:148 — @Immutable class SIN equals ⇒ Values.equals lo compara por IDENTIDAD
 * ```
 *
 * ```kotlin
 * // compose runtime 1.12.0-beta01, GapComposer.kt:428 — la condición de "puedo saltarme esto"
 * return !skipping || providersInvalid || currentRecomposeScope?.defaultsInvalid == true
 * ```
 *
 * Un `CompositionLocal` **estático** no rastrea lectores: cuando su valor cambia pone
 * `providersInvalid = true` y con eso **desactiva el skipping en todo el subárbol**. Da igual que
 * un composable lea colores o no; da igual que 47 de los 48 roles valgan lo mismo. Instancia nueva
 * de `ColorScheme` ⇒ la app entera se vuelve a ejecutar.
 *
 * Un `CompositionLocal` **dinámico** conserva el mismo holder y solo invalida a quien lee — que es
 * exactamente el comportamiento que hace falta aquí, y el que da el snapshot state.
 *
 * ## Cómo lo resuelve
 *
 * La instancia de [AppColorScheme] se crea UNA vez y no se sustituye nunca; lo que cambia son sus
 * 48 propiedades, cada una un `mutableStateOf`. Por eso [LocalAppColors] puede ser ESTÁTICO sin
 * coste: lo que el local provee (el objeto) es constante, así que nunca invalida nada; la
 * invalidación fina la hace el snapshot al escribir cada color, y alcanza SOLO a los composables
 * que leyeron ESE rol.
 *
 * Consecuencia práctica: cambiar de canción deja de repintar la app y pasa a repintar lo que de
 * verdad usa el acento. Y como deja de ser caro, el fundido de color quedó ENCENDIDO en todas
 * partes: el parámetro `animateColors` de [MusicPlayerTheme] —que lo snapeaba con el reproductor
 * abierto— existía sólo por este coste y se eliminó el mismo día, junto con la paleta retenida
 * (`rememberUnderlayColorScheme`), que era el otro parche alrededor de lo mismo.
 *
 * ## La regla al escribir UI nueva
 *
 * **Los colores se leen de [AppColors], NUNCA de `MaterialTheme.colorScheme`.** El segundo sigue
 * existiendo y sigue siendo correcto para los componentes de M3 que resuelven sus colores por
 * dentro, pero es un esquema que ya no acompaña a la carátula: lo que se lea de ahí se queda
 * quieto. Un componente de M3 que deba llevar el color del álbum recibe sus colores EXPLÍCITOS
 * (`colors = ...`) desde [AppColors].
 */
@Stable
class AppColorScheme internal constructor(initial: ColorScheme) {
    var primary: Color by mutableStateOf(initial.primary)
        internal set
    var onPrimary: Color by mutableStateOf(initial.onPrimary)
        internal set
    var primaryContainer: Color by mutableStateOf(initial.primaryContainer)
        internal set
    var onPrimaryContainer: Color by mutableStateOf(initial.onPrimaryContainer)
        internal set
    var inversePrimary: Color by mutableStateOf(initial.inversePrimary)
        internal set
    var secondary: Color by mutableStateOf(initial.secondary)
        internal set
    var onSecondary: Color by mutableStateOf(initial.onSecondary)
        internal set
    var secondaryContainer: Color by mutableStateOf(initial.secondaryContainer)
        internal set
    var onSecondaryContainer: Color by mutableStateOf(initial.onSecondaryContainer)
        internal set
    var tertiary: Color by mutableStateOf(initial.tertiary)
        internal set
    var onTertiary: Color by mutableStateOf(initial.onTertiary)
        internal set
    var tertiaryContainer: Color by mutableStateOf(initial.tertiaryContainer)
        internal set
    var onTertiaryContainer: Color by mutableStateOf(initial.onTertiaryContainer)
        internal set
    var background: Color by mutableStateOf(initial.background)
        internal set
    var onBackground: Color by mutableStateOf(initial.onBackground)
        internal set
    var surface: Color by mutableStateOf(initial.surface)
        internal set
    var onSurface: Color by mutableStateOf(initial.onSurface)
        internal set
    var surfaceVariant: Color by mutableStateOf(initial.surfaceVariant)
        internal set
    var onSurfaceVariant: Color by mutableStateOf(initial.onSurfaceVariant)
        internal set
    var surfaceTint: Color by mutableStateOf(initial.surfaceTint)
        internal set
    var inverseSurface: Color by mutableStateOf(initial.inverseSurface)
        internal set
    var inverseOnSurface: Color by mutableStateOf(initial.inverseOnSurface)
        internal set
    var error: Color by mutableStateOf(initial.error)
        internal set
    var onError: Color by mutableStateOf(initial.onError)
        internal set
    var errorContainer: Color by mutableStateOf(initial.errorContainer)
        internal set
    var onErrorContainer: Color by mutableStateOf(initial.onErrorContainer)
        internal set
    var outline: Color by mutableStateOf(initial.outline)
        internal set
    var outlineVariant: Color by mutableStateOf(initial.outlineVariant)
        internal set
    var scrim: Color by mutableStateOf(initial.scrim)
        internal set
    var surfaceBright: Color by mutableStateOf(initial.surfaceBright)
        internal set
    var surfaceDim: Color by mutableStateOf(initial.surfaceDim)
        internal set
    var surfaceContainer: Color by mutableStateOf(initial.surfaceContainer)
        internal set
    var surfaceContainerHigh: Color by mutableStateOf(initial.surfaceContainerHigh)
        internal set
    var surfaceContainerHighest: Color by mutableStateOf(initial.surfaceContainerHighest)
        internal set
    var surfaceContainerLow: Color by mutableStateOf(initial.surfaceContainerLow)
        internal set
    var surfaceContainerLowest: Color by mutableStateOf(initial.surfaceContainerLowest)
        internal set
    var primaryFixed: Color by mutableStateOf(initial.primaryFixed)
        internal set
    var primaryFixedDim: Color by mutableStateOf(initial.primaryFixedDim)
        internal set
    var onPrimaryFixed: Color by mutableStateOf(initial.onPrimaryFixed)
        internal set
    var onPrimaryFixedVariant: Color by mutableStateOf(initial.onPrimaryFixedVariant)
        internal set
    var secondaryFixed: Color by mutableStateOf(initial.secondaryFixed)
        internal set
    var secondaryFixedDim: Color by mutableStateOf(initial.secondaryFixedDim)
        internal set
    var onSecondaryFixed: Color by mutableStateOf(initial.onSecondaryFixed)
        internal set
    var onSecondaryFixedVariant: Color by mutableStateOf(initial.onSecondaryFixedVariant)
        internal set
    var tertiaryFixed: Color by mutableStateOf(initial.tertiaryFixed)
        internal set
    var tertiaryFixedDim: Color by mutableStateOf(initial.tertiaryFixedDim)
        internal set
    var onTertiaryFixed: Color by mutableStateOf(initial.onTertiaryFixed)
        internal set
    var onTertiaryFixedVariant: Color by mutableStateOf(initial.onTertiaryFixedVariant)
        internal set

    /**
     * Vuelca [scheme] sobre el estado, campo a campo.
     *
     * La comparación previa NO es una micro-optimización: escribir un `mutableStateOf` con el mismo
     * valor igualmente notifica al snapshot e invalida a sus lectores. Sin ella, cambiar de canción
     * invalidaría a los lectores de los 48 roles aunque solo se hubieran movido tres — que es una
     * versión más barata del mismo error que veníamos de arreglar.
     */
    /**
     * Cuántas veces cambió ALGÚN color desde que arrancó el proceso.
     *
     * Es la clave de `remember` para lo que tenga que derivarse del esquema entero — un
     * `MaterialTheme` anidado, unos `*Colors` de componente. Hace falta porque `ColorScheme` no
     * tiene `equals`: un `remember(scheme)` con un esquema recién construido compara por IDENTIDAD,
     * así que la clave nunca acierta y el valor se reconstruye en cada recomposición, que es
     * exactamente el fallo que se quería evitar. Con esto la clave cambia cuando cambió un color y
     * no cuando recompuso el padre.
     */
    /**
     * ¿La escala de contenedores sube en luminancia al subir de nivel? Es la definición del tema
     * OSCURO en M3 (`surface` es el extremo más oscuro y los contenedores se van aclarando; en
     * claro ocurre al revés), y es lo que consultan [AppColors.contentSurface] y
     * [AppColors.chromeSurface] para elegir el rol de cada lado de la horquilla.
     *
     * Se deriva de los propios colores y no de un `isSystemInDarkTheme()` ni de un umbral de
     * luminancia: el esquema puede venir de la carátula, del wallpaper o de un ajuste manual, y la
     * única fuente honesta de "hacia dónde va la escala" es la escala misma.
     */
    internal val containersGoLighter: Boolean
        get() = surface.luminance() < surfaceContainerHigh.luminance()

    var version: Int by mutableStateOf(0)
        private set

    internal fun updateFrom(scheme: ColorScheme) {
        var changed = false
        if (primary != scheme.primary) { primary = scheme.primary; changed = true }
        if (onPrimary != scheme.onPrimary) { onPrimary = scheme.onPrimary; changed = true }
        if (primaryContainer != scheme.primaryContainer) { primaryContainer = scheme.primaryContainer; changed = true }
        if (onPrimaryContainer != scheme.onPrimaryContainer) { onPrimaryContainer = scheme.onPrimaryContainer; changed = true }
        if (inversePrimary != scheme.inversePrimary) { inversePrimary = scheme.inversePrimary; changed = true }
        if (secondary != scheme.secondary) { secondary = scheme.secondary; changed = true }
        if (onSecondary != scheme.onSecondary) { onSecondary = scheme.onSecondary; changed = true }
        if (secondaryContainer != scheme.secondaryContainer) { secondaryContainer = scheme.secondaryContainer; changed = true }
        if (onSecondaryContainer != scheme.onSecondaryContainer) { onSecondaryContainer = scheme.onSecondaryContainer; changed = true }
        if (tertiary != scheme.tertiary) { tertiary = scheme.tertiary; changed = true }
        if (onTertiary != scheme.onTertiary) { onTertiary = scheme.onTertiary; changed = true }
        if (tertiaryContainer != scheme.tertiaryContainer) { tertiaryContainer = scheme.tertiaryContainer; changed = true }
        if (onTertiaryContainer != scheme.onTertiaryContainer) { onTertiaryContainer = scheme.onTertiaryContainer; changed = true }
        if (background != scheme.background) { background = scheme.background; changed = true }
        if (onBackground != scheme.onBackground) { onBackground = scheme.onBackground; changed = true }
        if (surface != scheme.surface) { surface = scheme.surface; changed = true }
        if (onSurface != scheme.onSurface) { onSurface = scheme.onSurface; changed = true }
        if (surfaceVariant != scheme.surfaceVariant) { surfaceVariant = scheme.surfaceVariant; changed = true }
        if (onSurfaceVariant != scheme.onSurfaceVariant) { onSurfaceVariant = scheme.onSurfaceVariant; changed = true }
        if (surfaceTint != scheme.surfaceTint) { surfaceTint = scheme.surfaceTint; changed = true }
        if (inverseSurface != scheme.inverseSurface) { inverseSurface = scheme.inverseSurface; changed = true }
        if (inverseOnSurface != scheme.inverseOnSurface) { inverseOnSurface = scheme.inverseOnSurface; changed = true }
        if (error != scheme.error) { error = scheme.error; changed = true }
        if (onError != scheme.onError) { onError = scheme.onError; changed = true }
        if (errorContainer != scheme.errorContainer) { errorContainer = scheme.errorContainer; changed = true }
        if (onErrorContainer != scheme.onErrorContainer) { onErrorContainer = scheme.onErrorContainer; changed = true }
        if (outline != scheme.outline) { outline = scheme.outline; changed = true }
        if (outlineVariant != scheme.outlineVariant) { outlineVariant = scheme.outlineVariant; changed = true }
        if (scrim != scheme.scrim) { scrim = scheme.scrim; changed = true }
        if (surfaceBright != scheme.surfaceBright) { surfaceBright = scheme.surfaceBright; changed = true }
        if (surfaceDim != scheme.surfaceDim) { surfaceDim = scheme.surfaceDim; changed = true }
        if (surfaceContainer != scheme.surfaceContainer) { surfaceContainer = scheme.surfaceContainer; changed = true }
        if (surfaceContainerHigh != scheme.surfaceContainerHigh) { surfaceContainerHigh = scheme.surfaceContainerHigh; changed = true }
        if (surfaceContainerHighest != scheme.surfaceContainerHighest) { surfaceContainerHighest = scheme.surfaceContainerHighest; changed = true }
        if (surfaceContainerLow != scheme.surfaceContainerLow) { surfaceContainerLow = scheme.surfaceContainerLow; changed = true }
        if (surfaceContainerLowest != scheme.surfaceContainerLowest) { surfaceContainerLowest = scheme.surfaceContainerLowest; changed = true }
        if (primaryFixed != scheme.primaryFixed) { primaryFixed = scheme.primaryFixed; changed = true }
        if (primaryFixedDim != scheme.primaryFixedDim) { primaryFixedDim = scheme.primaryFixedDim; changed = true }
        if (onPrimaryFixed != scheme.onPrimaryFixed) { onPrimaryFixed = scheme.onPrimaryFixed; changed = true }
        if (onPrimaryFixedVariant != scheme.onPrimaryFixedVariant) { onPrimaryFixedVariant = scheme.onPrimaryFixedVariant; changed = true }
        if (secondaryFixed != scheme.secondaryFixed) { secondaryFixed = scheme.secondaryFixed; changed = true }
        if (secondaryFixedDim != scheme.secondaryFixedDim) { secondaryFixedDim = scheme.secondaryFixedDim; changed = true }
        if (onSecondaryFixed != scheme.onSecondaryFixed) { onSecondaryFixed = scheme.onSecondaryFixed; changed = true }
        if (onSecondaryFixedVariant != scheme.onSecondaryFixedVariant) { onSecondaryFixedVariant = scheme.onSecondaryFixedVariant; changed = true }
        if (tertiaryFixed != scheme.tertiaryFixed) { tertiaryFixed = scheme.tertiaryFixed; changed = true }
        if (tertiaryFixedDim != scheme.tertiaryFixedDim) { tertiaryFixedDim = scheme.tertiaryFixedDim; changed = true }
        if (onTertiaryFixed != scheme.onTertiaryFixed) { onTertiaryFixed = scheme.onTertiaryFixed; changed = true }
        if (onTertiaryFixedVariant != scheme.onTertiaryFixedVariant) { onTertiaryFixedVariant = scheme.onTertiaryFixedVariant; changed = true }
        if (changed) version++
    }

    /**
     * El estado actual como `ColorScheme` de M3.
     *
     * Existe para las fronteras donde M3 exige el objeto entero (un `MaterialTheme` anidado, unos
     * `*Colors` de componente). Ojo: el resultado es una FOTO, no una vista viva — quien lo llame
     * durante la composición se suscribe a los 48 roles y se recompone con cualquiera de ellos.
     * Para leer un color, [AppColors].
     */
    fun toColorScheme(base: ColorScheme): ColorScheme = base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = primaryFixed,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = onPrimaryFixed,
        onPrimaryFixedVariant = onPrimaryFixedVariant,
        secondaryFixed = secondaryFixed,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = onSecondaryFixed,
        onSecondaryFixedVariant = onSecondaryFixedVariant,
        tertiaryFixed = tertiaryFixed,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = onTertiaryFixed,
        onTertiaryFixedVariant = onTertiaryFixedVariant,
    )
}

/**
 * Estático A PROPÓSITO: lo que provee es una instancia que no cambia nunca, así que la penalización
 * del local estático —invalidar el subárbol al cambiar de valor— no puede dispararse, y a cambio la
 * lectura es la barata. Si algún día alguien sustituye la instancia en caliente, esta decisión deja
 * de ser correcta y hay que pasarlo a `compositionLocalOf`.
 */
val LocalAppColors = staticCompositionLocalOf<AppColorScheme> {
    error("No hay AppColorScheme: falta envolver en MusicPlayerTheme")
}

/**
 * Publica [colorScheme] como los [AppColors] de este subárbol.
 *
 * Va SIEMPRE pegado a un `MaterialTheme` que reciba ese mismo esquema, y ésa es la regla: **un
 * `MaterialTheme` anidado que cambie de colores necesita este envoltorio, o su subárbol seguirá
 * leyendo los colores de la raíz**. Los dos casos vivos son las pantallas de detalle (que se tiñen
 * con el seed del artista o del álbum) y la paleta retenida de debajo del reproductor.
 *
 * `AppMenuPopup` es la excepción a propósito: su esquema anidado solo remapea
 * `surfaceContainerLow` para los componentes de M3 que lo leen POR DENTRO (el contenedor del
 * `DropdownMenuItem`), no para el código propio, así que no necesita —ni debe— publicar colores
 * distintos aquí.
 *
 * La instancia se `remember`a sin claves, igual que en la raíz: lo que cambia son sus campos.
 */
@Composable
fun ProvideAppColors(
    colorScheme: ColorScheme,
    /**
     * Con qué se FUNDE hacia [colorScheme]. `null` = sin fundido, el color entra de golpe.
     *
     * El fundido vive AQUÍ y no en quien construye el esquema, y esa es la corrección del 31 ago
     * 2026. Antes lo hacía `ColorScheme.animatedScheme()`: **31 `animateColorAsState` más un
     * `copy()` de 48 campos, en la COMPOSICIÓN de `MusicPlayerTheme`**. `animateColorAsState` lee su
     * valor en composición, así que cada frame del fundido recomponía la raíz de la app entera —
     * medido en un 15T Pro con la sonda: frames de 33, 41 y **50 ms** durante toda la apertura del
     * reproductor, cada uno con `tema: ColorScheme nuevo` y `MusicPlayerScreen recompuesta` dentro.
     *
     * El KDoc de aquella función afirmaba que el fundido ya no costaba porque "un frame sólo
     * invalida a quien lee el rol que se movió". Eso es cierto del CONSUMO —[AppColors] invalida
     * fino— y ciego a la PRODUCCIÓN: daba igual lo barato que fuera repartir el color si generarlo
     * recomponía la raíz.
     *
     * Ahora la animación corre en una corrutina y escribe directamente en los `mutableStateOf` de
     * [AppColorScheme]. Ni un frame del fundido recompone nada que no lea un rol que se movió, que
     * es lo que la arquitectura prometía desde el principio.
     */
    animationSpec: AnimationSpec<Float>? = null,
    content: @Composable () -> Unit
) {
    val appColors = remember { AppColorScheme(colorScheme) }
    val target by rememberUpdatedState(colorScheme)
    LaunchedEffect(appColors, animationSpec) {
        snapshotFlow { target }
            // `ColorScheme` compara por IDENTIDAD, así que sin esto una instancia nueva con los
            // mismos 48 colores relanzaría el fundido. Es la misma comparación por VALOR que ya
            // usaba `animatedScheme` para no emitir de más.
            .distinctUntilChanged { a, b -> a.hasSameColorsAs(b) }
            .collectLatest { goal ->
                val spec = animationSpec
                if (spec == null) {
                    appColors.updateFrom(goal)
                    return@collectLatest
                }
                // El punto de partida es lo que se está PINTANDO, no el objetivo anterior: si
                // llega un color nuevo a mitad del fundido, `collectLatest` cancela éste y el
                // siguiente arranca de donde se quedó, sin salto.
                val from = appColors.toColorScheme(goal)
                if (from.hasSameColorsAs(goal)) return@collectLatest
                // Una marca por FUNDIDO, no por frame. La que había en `animatedScheme` saltaba en
                // cada paso, y eso era justo el síntoma: si el tema aparece muchas veces dentro de
                // una animación, es que se está reconstruyendo en composición.
                JankProbe.mark { "tema: fundido de color arrancado (fuera de composición)" }
                // UNA animación de fracción en vez de 31 de color: los roles cruzan a la vez, que
                // además es lo que se quiere de un cambio de acento (antes cada uno iba por su
                // cuenta con su propio spring).
                animate(0f, 1f, animationSpec = spec) { t, _ ->
                    appColors.updateFrom(lerpScheme(from, goal, t))
                }
                // El objetivo EXACTO al cerrar: un spring puede quedarse a una milésima.
                appColors.updateFrom(goal)
            }
    }
    CompositionLocalProvider(LocalAppColors provides appColors, content = content)
}

/**
 * Fachada de lectura, con la misma forma que `MaterialTheme.colorScheme` para que migrar un sitio
 * sea cambiar el prefijo y nada más.
 *
 * Cada getter es `@ReadOnlyComposable`: no abre grupo de recomposición propio, así que la
 * suscripción al color queda en el scope de quien llama, que es justo el que tiene que invalidarse.
 */
object AppColors {
    val primary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.primary

    val onPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimary

    val primaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryContainer

    val onPrimaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimaryContainer

    val inversePrimary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.inversePrimary

    val secondary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.secondary

    val onSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSecondary

    val secondaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.secondaryContainer

    val onSecondaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSecondaryContainer

    val tertiary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.tertiary

    val onTertiary: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onTertiary

    val tertiaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.tertiaryContainer

    val onTertiaryContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onTertiaryContainer

    val background: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.background

    val onBackground: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onBackground

    val surface: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surface

    /**
     * Superficie del CONTENIDO —filas de canción, tarjetas, la píldora de búsqueda— sobre el fondo
     * de página, que desde el 4 sep 2026 es `surface`.
     *
     * **`surfaceContainerHigh`, el token de una TARJETA, y NO el de un list item** (7 sep 2026,
     * decisión del usuario tras leer las fuentes pineadas). El dato que lo decide está en el jar:
     *
     * ```
     * ListTokens.ItemContainerColor          = Surface   // ListTokens.kt:32
     * ListTokens.ItemSegmentedContainerColor = Surface   // :87  (la variante agrupada)
     * ListTokens.ItemContainerElevation      = Level0
     * ColorLightTokens.Background = Neutral98   ==   ColorLightTokens.Surface = Neutral98
     * ColorDarkTokens.Background  = Neutral6    ==   ColorDarkTokens.Surface  = Neutral6
     * ```
     *
     * O sea que **M3 pinta su list item del MISMO color que la página** (`Scaffold` arranca en
     * `background`, Scaffold.kt:94). No es un descuido: en M3 un list item **no es una tarjeta** —
     * elevación 0, y la estructura la dan el espaciado, los divisores y el thumbnail. Un ítem flat
     * no necesita despegarse del fondo porque no pretende flotar sobre él.
     *
     * **Las filas de esta app sí son tarjetas**: esquinas redondeadas, 2dp de gap y —sobre todo— son
     * la punta ORIGEN del container transform hacia el reproductor, que necesita una superficie que
     * crecer. Para esa anatomía M3 tabula otro nivel: `FilledCardTokens.ContainerColor` =
     * `SurfaceContainerHighest`, `ElevatedCardTokens.ContainerColor` = `SurfaceContainerLow`.
     *
     * **Se elige `surfaceContainerHigh` y no el `Highest` de la tarjeta rellena** porque el techo de
     * la escala neutra ya tiene dueño: `AppMenuPopup`, cuyo KDoc apoya su elección justamente en que
     * "las superficies sobre las que un menú puede caer van de `surface` a `surfaceContainerHigh`".
     * Subir las filas al techo dejaría al menú del mismo color que la fila que tapa. Da 6 puntos de
     * tono en claro (98 → 92) y 11 en oscuro (6 → 17).
     *
     * **El mismo rol en los DOS temas**, al revés que la versión anterior. Esto reemplaza a la
     * "horquilla" —fondo en el nivel medio, contenido hacia el lado claro y barras hacia el oscuro,
     * medida sobre el Dialer— que quedó a medias cuando el fondo de página volvió a `surface` el
     * 4 sep: con la página en el EXTREMO de la escala ya no hay "hacia el lado claro" al que subir en
     * tema claro, así que en claro las filas salían exactamente del color del fondo y las tarjetas
     * no existían. Su gemela [chromeSurface] se quedó **sin un solo consumidor** en ese mismo pase.
     *
     * **NO reintroducir un rol por tema aquí.** La relación que la horquilla quería conservar la
     * conserva ahora un nivel de contenedor fijo, porque el fondo también es fijo.
     */
    val contentSurface: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainerHigh

    /**
     * Superficie de las BARRAS de navegación (fila de pestañas, rail): un peldaño hacia el lado
     * oscuro desde el fondo de página. La otra mitad de la horquilla — ver [contentSurface].
     *
     * ⚠️ **SIN CONSUMIDORES desde el 4 sep 2026**: ese pase llevó las barras a `surface` como el
     * resto y esta propiedad quedó viva sin pintar nada. Se deja para decidirlo aparte, no porque
     * haga falta — es exactamente la forma de subsistema muerto que ya dejó atrás el vidrio Haze.
     */
    val chromeSurface: Color
        @Composable @ReadOnlyComposable get() = with(LocalAppColors.current) {
            if (containersGoLighter) surfaceContainerLow else surfaceContainerHigh
        }

    val onSurface: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSurface

    val surfaceVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceVariant

    val onSurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSurfaceVariant

    val surfaceTint: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceTint

    val inverseSurface: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.inverseSurface

    val inverseOnSurface: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.inverseOnSurface

    val error: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.error

    val onError: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onError

    val errorContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.errorContainer

    val onErrorContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onErrorContainer

    val outline: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.outline

    val outlineVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.outlineVariant

    val scrim: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.scrim

    val surfaceBright: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceBright

    val surfaceDim: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceDim

    val surfaceContainer: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainer

    val surfaceContainerHigh: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainerHigh

    val surfaceContainerHighest: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainerHighest

    val surfaceContainerLow: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainerLow

    val surfaceContainerLowest: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceContainerLowest

    val primaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryFixed

    val primaryFixedDim: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryFixedDim

    val onPrimaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimaryFixed

    val onPrimaryFixedVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimaryFixedVariant

    val secondaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.secondaryFixed

    val secondaryFixedDim: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.secondaryFixedDim

    val onSecondaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSecondaryFixed

    val onSecondaryFixedVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onSecondaryFixedVariant

    val tertiaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.tertiaryFixed

    val tertiaryFixedDim: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.tertiaryFixedDim

    val onTertiaryFixed: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onTertiaryFixed

    val onTertiaryFixedVariant: Color
        @Composable @ReadOnlyComposable get() = LocalAppColors.current.onTertiaryFixedVariant
}
