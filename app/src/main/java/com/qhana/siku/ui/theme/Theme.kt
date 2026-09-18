package com.qhana.siku.ui.theme

import androidx.compose.runtime.compositionLocalOf
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.qhana.siku.data.repository.ArtworkRepository

// ============== FALLBACK COLORS (NEUTRAL / BLUE ACCENT) ==============
// Solo se usan en Android < 12 donde Dynamic Color no existe.
// Usamos un azul profundo/violeta elegante como fallback, neutro pero moderno.

val PrimaryLight = Color(0xFF6750A4)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFEADDFF)
val OnPrimaryContainerLight = Color(0xFF21005D)

val PrimaryDark = Color(0xFFD0BCFF)
val OnPrimaryDark = Color(0xFF381E72)
val PrimaryContainerDark = Color(0xFF4F378B)
val OnPrimaryContainerDark = Color(0xFFEADDFF)

// Error colors
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)

// Fallback Schemes (Material 3 Baseline)
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // Acento del álbum en reproducción: si llega, genera TODO el ColorScheme desde él
    // (homogeneíza el acento en toda la app). null = dynamic del sistema / baseline.
    seedColor: Color? = null,
    // Carátula ACROMÁTICA (blanco/negro/gris): se pide un esquema NEUTRO en escala de grises
    // (PaletteStyle.Monochrome) que solo sigue el claro/oscuro, en vez de un matiz inventado.
    monochrome: Boolean = false,
    // Estilo con el que MaterialKolor deriva el esquema del seed. Lo elige el usuario en
    // Ajustes → Apariencia. Con el color spec 2021 (ver abajo) el estilo decide el CROMA de cada
    // paleta y el TONO de cada rol lo fija el spec — salvo los contenedores de Fidelidad y Fiel a la
    // carátula, que toman el tono del seed. Por eso el seed se extrae SIN filtrarlo por contraste
    // (lo que no sobrevive no se puede juzgar antes: ver `ArtworkRepository.extractSeeds`) y el
    // aviso se da aquí, sobre el acento ya derivado. `monochrome` gana siempre: sin croma no hay
    // matiz que estilizar.
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    content: @Composable () -> Unit
) {
    // EL ESQUEMA BASE: el que recibe `MaterialTheme`, y que a propósito **NO depende de la
    // carátula**. Su instancia sólo cambia si cambian los ajustes (claro/oscuro, color dinámico del
    // fondo de pantalla), o sea casi nunca.
    //
    // Ésa es LA razón de ser de todo esto. `MaterialTheme` publica su valor por un CompositionLocal
    // ESTÁTICO, así que cada instancia nueva de `ColorScheme` desactivaba el skipping en el árbol
    // entero: 19 ms de recomposición + 9 ms de medida POR CANCIÓN, y no había forma de abaratarlos
    // porque el trabajo no era de nadie en particular, era de todos. El color del álbum viaja ahora
    // por [AppColors], que invalida sólo a quien lee el rol que cambió. Ver el KDoc de
    // [AppColorScheme] para el mecanismo con las líneas de las fuentes.
    //
    // `remember` obligatorio: `dynamicDarkColorScheme` construye una instancia nueva en cada
    // llamada, y sin memorizarla este esquema cambiaría de identidad en cada recomposición del
    // tema — que es exactamente el problema que se está quitando, entrando por la otra puerta.
    val context = LocalContext.current
    val baseScheme = remember(darkTheme, dynamicColor, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }

    // EL ESQUEMA TEÑIDO por la carátula. Va a [AppColors] y a ningún otro sitio.
    val tintedScheme = if (seedColor != null) {
        rememberSeededScheme(seedColor, darkTheme, if (monochrome) PaletteStyle.Monochrome else paletteStyle)
    } else baseScheme

    val generation = remember(paletteStyle, darkTheme) {
        ThemeGeneration(paletteStyle, darkTheme)
    }

    // El estilo con el que está generado el esquema que SE PINTA. No es `generation.paletteStyle`:
    // con una carátula acromática el tema cae a `Monochrome` aunque Ajustes diga otra cosa. Sin seed
    // el esquema es el base y el estilo no interviene; se publica el de Ajustes para no inventar uno.
    val effectivePaletteStyle =
        if (seedColor != null && monochrome) PaletteStyle.Monochrome else paletteStyle

    // El color del álbum llega a la UI propia por [AppColors] y NO por `MaterialTheme`: ése es el
    // que recompone la app entera al cambiar, porque va por un CompositionLocal estático. El
    // porqué completo, con las líneas de las fuentes que lo obligan, en el KDoc de
    // [AppColorScheme].
    CompositionLocalProvider(
        LocalThemeGeneration provides generation,
        LocalEffectivePaletteStyle provides effectivePaletteStyle
    ) {
        // El fundido lo hace ProvideAppColors en una corrutina: aquí solo se le da el DESTINO.
        // SIEMPRE animado, también con el reproductor abierto — ver su KDoc.
        ProvideAppColors(tintedScheme, animationSpec = AppMotionScheme.slowEffectsSpec()) {
        MaterialTheme(
            colorScheme = baseScheme,
            // Shapes DEFAULT de M3 (extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 dp).
            // Antes había un override "expressive" (8/12/16/24/32) que agrandaba todas las esquinas.
            shapes = Shapes(),
            // Motion Expressive: springs físicos en todas las transiciones M3 (button groups,
            // toggles, sheets, etc.) en lugar de los tweens lineales por defecto.
            // La instancia viene de [AppMotionScheme] y no de una llamada suelta a
            // `MotionScheme.expressive()`: es la MISMA que leen los sitios sin composición
            // (transiciones del NavHost, gestos del player, shared elements), así que no pueden
            // desincronizarse del tema. Ver ui/theme/Motion.kt.
            motionScheme = AppMotionScheme,
            typography = rememberAppTypography(),
            content = content
        )
        }
    }
}

// --- Previsualización del acento (qué haría un seed con el tema actual) ---

/**
 * Cómo está generando el tema su esquema AHORA MISMO. Lo publica [MusicPlayerTheme] para que una
 * superficie que necesite anticipar el resultado —el selector de color de la carátula— no tenga que
 * releer preferencias por su cuenta ni reconstruir a mano la llamada a MaterialKolor: dos copias de
 * esa construcción se separan en cuanto una cambia, y la previsualización mentiría sin fallar.
 */
data class ThemeGeneration(
    val paletteStyle: PaletteStyle,
    val isDark: Boolean
)

val LocalThemeGeneration = staticCompositionLocalOf {
    ThemeGeneration(PaletteStyle.TonalSpot, isDark = false)
}

/**
 * El [PaletteStyle] del esquema que se está PINTANDO: el de Ajustes, salvo cuando la carátula es
 * acromática y el tema cae a `Monochrome`. Lo necesita quien elige roles POR PALETA (el MiniPlayer);
 * [LocalThemeGeneration] no sirve para eso porque guarda el estilo elegido, a propósito — la
 * previsualización del selector de color no debe usar nunca `Monochrome`.
 *
 * **`compositionLocalOf` y NO estático**, al revés que [LocalThemeGeneration]: este valor cambia con
 * la CANCIÓN (basta pasar de una carátula en color a una en blanco y negro), y un local estático
 * que cambia desactiva el skipping del subárbol entero — la recomposición total por canción que
 * `AppColors` existe para evitar. Dinámico, sólo recompone a quien lo lee.
 */
val LocalEffectivePaletteStyle = compositionLocalOf { PaletteStyle.TonalSpot }

/**
 * Lo que el selector de color necesita saber de un candidato: el acento que produciría y si ese
 * acento se distinguirá del fondo. Van JUNTOS a propósito — las dos cosas salen del mismo esquema y
 * separarlas invitaría a medir el aviso contra otra superficie que la del acento que se enseña.
 */
data class AccentPreview(
    /** El `primary` que la app pintaría con este seed: exactamente el color del botón play. */
    val accent: Color,
    /** [accent] contrasta contra el `surface` de SU PROPIO esquema. */
    val hasContrast: Boolean
)

/**
 * El acento que la app pintaría si [seedArgb] fuese el color del álbum: el mismo esquema que
 * [MusicPlayerTheme], vía [rememberSeededScheme].
 *
 * Existe porque un color de la carátula NO se aplica tal cual: el estilo de paleta le impone su
 * propio croma y el spec le impone el tono del rol, así que un beige de croma 11 puede acabar como
 * un mostaza de croma 32. El selector enseñaba el color crudo y el usuario veía otro en pantalla.
 *
 * Por lo mismo, **el aviso de contraste se mide aquí y sobre el acento DERIVADO** (contra el
 * `surface` de ese mismo esquema, no contra un tono de superficie supuesto): el color crudo de la
 * portada no se pinta en ningún sitio, así que avisar sobre él era avisar sobre un color que el
 * usuario no va a ver. Un seed oscuro que el spec reencuadra a T80 se ve perfectamente, y al revés,
 * un estilo puede devolver un `primary` blanco o negro desde una semilla que puntuaba bien.
 *
 * **Nunca usa `Monochrome`**, aunque el candidato sea acromático: elegir un color a mano marca la
 * canción como override manual y eso DESACTIVA la guardia de acromático (ver `hasManualColor`), así
 * que el gris se aplicaría como seed igualmente. Previsualizar en Monochrome sería previsualizar un
 * camino que ese toque no va a tomar.
 */
@Composable
fun rememberAccentPreview(seedArgb: Int): AccentPreview {
    val generation = LocalThemeGeneration.current
    val scheme = rememberSeededScheme(Color(seedArgb), generation.isDark, generation.paletteStyle)
    return remember(scheme) {
        AccentPreview(
            accent = scheme.primary,
            hasContrast = ArtworkRepository.accentHasContrast(
                scheme.primary.toArgb(),
                scheme.surface.toArgb()
            )
        )
    }
}

/**
 * La ÚNICA construcción del esquema teñido por un seed. La usan el tema global, el de las pantallas
 * de detalle y la previsualización del selector de color: eran tres copias de la misma llamada a
 * MaterialKolor, y tres copias se separan en cuanto una cambia — la previsualización mentiría sin
 * que nada fallara.
 *
 * **Color spec 2021 y los roles TAL CUAL salen, sin post-proceso** (decisión del usuario, 11 sep
 * 2026). Con el 2025 el tono de cada rol dependía de la paleta —`primary` podía acabar en T90 en
 * tema claro— y encima se acumularon correcciones propias (el "acento vivo", el desempate de
 * `onPrimary`) que rompían los pares que M3 garantiza. En 2021 el tono de cada rol es una constante
 * del spec (`primary` T40 en claro, T80 en oscuro) y el contraste de cada par lo garantiza M3.
 */
@Composable
internal fun rememberSeededScheme(seed: Color, isDark: Boolean, style: PaletteStyle): ColorScheme =
    rememberDynamicColorScheme(
        seedColor = seed,
        isDark = isDark,
        isAmoled = false,
        style = style,
        specVersion = ColorSpec.SpecVersion.SPEC_2021
    )

// --- Tema propio de una pantalla de detalle ---

/**
 * Reemplaza el `ColorScheme` en el subárbol de una pantalla que tiene su PROPIA imagen dominante
 * (detalle de artista o de álbum), generándolo desde [seedArgb] en vez de heredar el del tema
 * global —que sale de la canción en reproducción—.
 *
 * El motivo es de coherencia visual: estas pantallas están dominadas por su header inmersivo
 * edge-to-edge, y que el acento de sus botones venga de OTRA imagen (la que suena, que puede no
 * tener nada que ver) se lee como un descuido. Con esto, la pantalla entera —fondo, tarjetas,
 * textos y controles— se deriva de lo que el usuario está mirando.
 *
 * ## Lo que NO cubre, y es correcto que no lo cubra
 *
 * El **MiniPlayer no hereda** este tema: vive en `PlayerOverlay`, hermano del `NavHost` bajo el
 * `MusicPlayerTheme` global, así que conserva el acento de lo que suena. Es deliberado — el
 * reproductor es su propia entidad y viaja con su color por toda la app.
 *
 * ## El color viaja CON la carátula
 *
 * **El seed nunca está listo en el primer frame**: en el álbum llega con el flow de canciones de
 * Room y en el artista, con la extracción de la foto. Aplicarlo de golpe en cuanto aparece pinta la
 * pantalla de un color y la repinta de otro dos frames después — el "cambia de golpe el color de
 * todo" al abrir el detalle.
 *
 * Se funde con [AppScreenColorSpec], que comparte duración con el slide del `NavHost`: el color
 * termina de cambiar **exactamente cuando la carátula aterriza en el header**, porque son la misma
 * cosa para quien mira — el color SALE de esa portada. Que el fundido arranque uno o dos frames
 * después del gesto no se nota; que acabe en otro momento que la imagen, sí.
 *
 * No hay ninguna espera previa: se probó retrasar el cambio hasta que la entrada terminase y es
 * peor de dos maneras — deja el color moviéndose sobre una pantalla ya quieta, y para saber cuándo
 * empezar hay que inventarse un intervalo, que es justo lo que la convención 13 prohíbe.
 *
 * [seedArgb] nulo significa "esta pantalla no tiene de dónde sacar color": se reinstala el tema
 * global tal cual. Es el caso del artista sin foto NI carátulas.
 */
@Composable
fun DetailContentTheme(seedArgb: Int?, content: @Composable () -> Unit) {
    val generation = LocalThemeGeneration.current
    // El esquema que se reinstala SIN seed sale de [AppColors] y NO de `MaterialTheme.colorScheme`:
    // ése lleva el esquema BASE, que no depende de ninguna carátula, así que caer a él dejaría al
    // detalle de un artista sin foto en gris dentro de una app teñida — y peor, PERDIENDO el color
    // que ya tenía heredado de la pantalla anterior. `version` como clave: `ColorScheme` no tiene
    // `equals`, así que sin ella el `remember` no acertaría nunca (ver [AppColorScheme.version]).
    val appColors = LocalAppColors.current
    val baseScheme = MaterialTheme.colorScheme
    val globalScheme = remember(appColors, baseScheme, appColors.version) {
        appColors.toColorScheme(baseScheme)
    }

    // Misma guardia que el tema global: una imagen sin croma no debe inventarse un matiz. El
    // umbral vive en ArtworkRepository para que extracción y consumo no puedan discrepar.
    val monochrome = seedArgb != null && ArtworkRepository.isAchromatic(seedArgb)
    val seed = seedArgb?.let { Color(it) }

    // El estilo y el tema claro/oscuro salen de [LocalThemeGeneration] — el mismo sitio del que los
    // lee la previsualización del selector de color —, y el esquema, de [rememberSeededScheme].
    val scheme = if (seed != null) {
        rememberSeededScheme(seed, generation.isDark, if (monochrome) PaletteStyle.Monochrome else generation.paletteStyle)
    } else globalScheme

    // `MaterialTheme` se monta SIEMPRE, también sin seed (donde reinstala el esquema global tal
    // cual). Es lo que mantiene la ESTRUCTURA de la composición estable, y no es cosmético: con un
    // `if (seed == null) { content() } else { MaterialTheme { content() } }`, las dos ramas son
    // grupos distintos, así que en el frame en que llega el seed Compose DESCARTA el árbol entero
    // de la pantalla y lo vuelve a componer — perdiendo el estado recordado dentro y pagando un
    // tirón justo cuando la pantalla acaba de asentarse. Y el seed SIEMPRE llega tarde.
    //
    // Solo se pasa el colorScheme: los otros tres parámetros heredan del tema actual por defecto
    // (`MaterialTheme.shapes` / `.typography` / `.motionScheme`), así que el motion Expressive y la
    // tipografía de marca siguen siendo los mismos. `LocalThemeGeneration` tampoco se toca — no
    // cambia cómo se genera un esquema, solo desde qué seed.
    // Sólo [ProvideAppColors]: ya NO hay un `MaterialTheme` anidado aquí. El de la raíz lleva el
    // esquema base —el que no depende de ninguna carátula— y no hay nada que reponer; lo que teñía
    // la pantalla era este esquema, y ahora viaja por [AppColors] sin desactivar el skipping del
    // subárbol. De paso desaparece el motivo del párrafo de arriba: no queda ninguna rama.
    ProvideAppColors(scheme, animationSpec = AppScreenColorSpec, content = content)
}

/**
 * Los dos esquemas mezclados al [fraction], para el fundido de [ProvideAppColors].
 *
 * **Sustituye a `ColorScheme.animatedScheme()`** (31 ago 2026), que hacía lo mismo con 31
 * `animateColorAsState` EN COMPOSICIÓN: como ese valor se lee al componer, cada frame del fundido
 * recomponía `MusicPlayerTheme` —la raíz— y con ella `MusicPlayerScreen` y `NowPlayingScreen`.
 * Medido en un 15T Pro: frames de 33, 41 y 50 ms durante toda la apertura del reproductor. Aquí no
 * hay estado ni composición: es aritmética sobre dos esquemas, y quien la conduce es una corrutina.
 *
 * Se interpolan los MISMOS 31 roles que animaba aquella (los demás llegan de [to] tal cual), así
 * que el resultado visual no cambia. Lo que sí cambia —a mejor— es que ahora todos cruzan con la
 * misma fracción en vez de con 31 springs independientes.
 */
internal fun lerpScheme(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme = to.copy(
    primary = lerp(from.primary, to.primary, fraction),
    onPrimary = lerp(from.onPrimary, to.onPrimary, fraction),
    primaryContainer = lerp(from.primaryContainer, to.primaryContainer, fraction),
    onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, fraction),
    inversePrimary = lerp(from.inversePrimary, to.inversePrimary, fraction),
    secondary = lerp(from.secondary, to.secondary, fraction),
    onSecondary = lerp(from.onSecondary, to.onSecondary, fraction),
    secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, fraction),
    onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, fraction),
    tertiary = lerp(from.tertiary, to.tertiary, fraction),
    onTertiary = lerp(from.onTertiary, to.onTertiary, fraction),
    tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, fraction),
    onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, fraction),
    background = lerp(from.background, to.background, fraction),
    onBackground = lerp(from.onBackground, to.onBackground, fraction),
    surface = lerp(from.surface, to.surface, fraction),
    onSurface = lerp(from.onSurface, to.onSurface, fraction),
    surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, fraction),
    onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, fraction),
    surfaceTint = lerp(from.surfaceTint, to.surfaceTint, fraction),
    inverseSurface = lerp(from.inverseSurface, to.inverseSurface, fraction),
    inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, fraction),
    outline = lerp(from.outline, to.outline, fraction),
    outlineVariant = lerp(from.outlineVariant, to.outlineVariant, fraction),
    surfaceBright = lerp(from.surfaceBright, to.surfaceBright, fraction),
    surfaceDim = lerp(from.surfaceDim, to.surfaceDim, fraction),
    surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, fraction),
    surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, fraction),
    surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, fraction),
    surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, fraction),
    surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, fraction)
)

/**
 * Igualdad POR VALOR de dos esquemas, campo a campo — los 48 roles de la versión pineada de
 * material3 (1.5.0-alpha24). `ColorScheme` no la trae (compara por identidad), y aquí es
 * exactamente lo que se necesita: decidir si el tema cambió de VERDAD antes de dárselo al
 * `MaterialTheme`. Se comparan TODOS los campos y no solo los que [lerpScheme] interpola, porque
 * los que no anima llegan del esquema objetivo y también pueden cambiar con él.
 *
 * Si material3 añade roles, esta función se queda corta EN SILENCIO (dos esquemas que difieren solo
 * en el rol nuevo se declararían iguales): al subir de versión, contrastar contra la lista de
 * campos de `ColorScheme`.
 */
internal fun ColorScheme.hasSameColorsAs(o: ColorScheme): Boolean =
    primary == o.primary && onPrimary == o.onPrimary &&
        primaryContainer == o.primaryContainer && onPrimaryContainer == o.onPrimaryContainer &&
        inversePrimary == o.inversePrimary &&
        secondary == o.secondary && onSecondary == o.onSecondary &&
        secondaryContainer == o.secondaryContainer && onSecondaryContainer == o.onSecondaryContainer &&
        tertiary == o.tertiary && onTertiary == o.onTertiary &&
        tertiaryContainer == o.tertiaryContainer && onTertiaryContainer == o.onTertiaryContainer &&
        background == o.background && onBackground == o.onBackground &&
        surface == o.surface && onSurface == o.onSurface &&
        surfaceVariant == o.surfaceVariant && onSurfaceVariant == o.onSurfaceVariant &&
        surfaceTint == o.surfaceTint &&
        inverseSurface == o.inverseSurface && inverseOnSurface == o.inverseOnSurface &&
        error == o.error && onError == o.onError &&
        errorContainer == o.errorContainer && onErrorContainer == o.onErrorContainer &&
        outline == o.outline && outlineVariant == o.outlineVariant && scrim == o.scrim &&
        surfaceBright == o.surfaceBright && surfaceDim == o.surfaceDim &&
        surfaceContainer == o.surfaceContainer &&
        surfaceContainerHigh == o.surfaceContainerHigh &&
        surfaceContainerHighest == o.surfaceContainerHighest &&
        surfaceContainerLow == o.surfaceContainerLow &&
        surfaceContainerLowest == o.surfaceContainerLowest &&
        primaryFixed == o.primaryFixed && primaryFixedDim == o.primaryFixedDim &&
        onPrimaryFixed == o.onPrimaryFixed && onPrimaryFixedVariant == o.onPrimaryFixedVariant &&
        secondaryFixed == o.secondaryFixed && secondaryFixedDim == o.secondaryFixedDim &&
        onSecondaryFixed == o.onSecondaryFixed && onSecondaryFixedVariant == o.onSecondaryFixedVariant &&
        tertiaryFixed == o.tertiaryFixed && tertiaryFixedDim == o.tertiaryFixedDim &&
        onTertiaryFixed == o.onTertiaryFixed && onTertiaryFixedVariant == o.onTertiaryFixedVariant


/**
 * Traduce el nombre guardado en preferencias al [PaletteStyle] correspondiente.
 *
 * Se persiste el NOMBRE y no el ordinal a propósito: si una versión futura de MaterialKolor
 * reordena el enum, un ordinal guardado pasaría a significar otro estilo sin que nada falle.
 * Un nombre desconocido (estilo retirado de la librería) cae al default en vez de reventar.
 */
fun paletteStyleFromName(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.TonalSpot
