package com.qhana.siku.ui.theme

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
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.rememberDynamicColorScheme
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.ui.components.ensureContrast

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
    // Ajustes → Apariencia. Con el color spec 2025 (ver abajo) cada estilo decide DOS cosas, no
    // solo el croma: también el TONO del rol. En teléfono:
    //  - TonalSpot (default): croma 26 en oscuro / 32 en claro. El tono se queda CLAVADO en 80 en
    //    oscuro, así que el acento sale pastel por construcción — a esa claridad el gamut sRGB no
    //    admite croma alto, y ningún ajuste lo cambia. En claro sí usa el tono de máximo croma.
    //  - Expressive: croma 36/48 y tono de máximo croma. En el spec viejo ROTABA el matiz +240°
    //    (pintaba un color que no estaba en la carátula); en el 2025 respeta el matiz del seed,
    //    que es lo que lo vuelve utilizable aquí.
    //  - Vibrant: croma 74 y tono de máximo croma. Es el más parecido a lo que System UI pinta en
    //    la notificación de media. Sigue siendo el que más puede exagerar un seed apagado.
    //  - Content/Fidelity siguen el croma del seed pero dejan los neutros casi grises.
    // `monochrome` gana siempre: sin croma no hay matiz que estilizar.
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    // Acento VIVO, SOLO EN TEMA OSCURO: reencuadra `primary` al tono donde el matiz del álbum
    // alcanza su croma máximo, en vez del 80 fijo que le da el spec (ver [withVividPrimary]). Es lo
    // que acerca el acento al que System UI pinta en la notificación de media sin cambiar de estilo
    // de paleta — con Equilibrado, las superficies siguen siendo moderadas.
    vividAccent: Boolean = true,
    content: @Composable () -> Unit
) {
    // Post-proceso del esquema ya generado (palanca oficial de MaterialKolor). Se memoriza porque
    // `rememberDynamicColorScheme` mete esta lambda entre las claves de su `remember`: una lambda
    // nueva por recomposición tiraría el caché del esquema en cada frame.
    // SOLO en tema oscuro. Ahí el problema es real: el spec clava `primary` en tono 80 y a esa
    // claridad el gamut sRGB no admite croma alto, así que el acento sale pastel hagas lo que
    // hagas. En claro no hace falta — con el spec 2025 TonalSpot ya coloca `primary` en el tono de
    // máximo croma y luego DynamicColor le aplica su corrección de contraste, que un color escrito
    // a mano se salta. Se probó intervenir también en claro (28 jul) y todo lo que salió de ahí
    // fueron problemas nuevos: el play gris de croma 9.3, el botón oscuro con el glifo oscuro, y
    // una ventana de tonos que había que recalibrar a ojo. Revertido a propósito.
    val vividAccents = remember(seedColor, darkTheme, monochrome, vividAccent) {
        if (seedColor == null || monochrome || !vividAccent || !darkTheme) null
        else { scheme: ColorScheme -> scheme.withVividPrimary(seedColor).withVividSecondary(seedColor) }
    }

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
    val tintedScheme = if (seedColor != null) rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = if (monochrome) PaletteStyle.Monochrome else paletteStyle,
            modifyColorScheme = vividAccents,
            // Color spec de M3 EXPRESSIVE. El default de MaterialKolor sigue siendo `SPEC_2021`
            // (`ColorSpec.SpecVersion.Default`), que es el Material You de 2021: ahí el tono de
            // cada rol es una CONSTANTE — `primary` vale 80 en oscuro y 40 en claro pase lo que
            // pase (`ColorSpec2021.primary()`), y el estilo de paleta solo decide el croma. Como a
            // tono 80 el gamut sRGB no admite croma alto, el acento salía pastel con CUALQUIER
            // estilo (ni Vibrant, que pide croma 200, llegaba al color que System UI pinta en la
            // notificación de la sesión de media).
            //
            // En el spec 2025, para las variantes Vibrant y Expressive en teléfono el tono de
            // `primary` pasa a ser `tMaxC(primaryPalette)` = el tono donde ESE matiz alcanza su
            // croma máximo. Es lo mismo que hace System UI, y por eso ahora el acento del álbum
            // puede ser tan vivo como el de la notificación. Con TonalSpot el tono sigue clavado
            // en 80 en oscuro: el spec habilita el color vivo, pero quien lo pide es el estilo.
            specVersion = ColorSpec.SpecVersion.SPEC_2025
        )
    else baseScheme

    val generation = remember(paletteStyle, darkTheme, vividAccent) {
        ThemeGeneration(paletteStyle, darkTheme, vividAccent)
    }

    // El color del álbum llega a la UI propia por [AppColors] y NO por `MaterialTheme`: ése es el
    // que recompone la app entera al cambiar, porque va por un CompositionLocal estático. El
    // porqué completo, con las líneas de las fuentes que lo obligan, en el KDoc de
    // [AppColorScheme].
    CompositionLocalProvider(LocalThemeGeneration provides generation) {
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
    val isDark: Boolean,
    val vividAccent: Boolean
)

val LocalThemeGeneration = staticCompositionLocalOf {
    ThemeGeneration(PaletteStyle.TonalSpot, isDark = false, vividAccent = true)
}

/**
 * El `primary` que la app pintaría si [seedArgb] fuese el acento del álbum: mismo estilo de paleta,
 * mismo color spec y mismo reencuadre vivo que [MusicPlayerTheme].
 *
 * Existe porque un color de la carátula NO se aplica tal cual: el estilo de paleta le impone su
 * propio croma y el spec le impone el tono del rol, así que un beige de croma 11 puede acabar como
 * un mostaza de croma 32. El selector enseñaba el color crudo y el usuario veía otro en pantalla.
 *
 * **Nunca usa `Monochrome`**, aunque el candidato sea acromático: elegir un color a mano marca la
 * canción como override manual y eso DESACTIVA la guardia de acromático (ver `hasManualColor`), así
 * que el gris se aplicaría como seed igualmente. Previsualizar en Monochrome sería previsualizar un
 * camino que ese toque no va a tomar.
 */
@Composable
fun rememberAccentPreview(seedArgb: Int): Color {
    val generation = LocalThemeGeneration.current
    val seed = Color(seedArgb)
    // Misma razón que en [MusicPlayerTheme]: la lambda entra en las claves del `remember` interno
    // de MaterialKolor, y una nueva por recomposición tiraría el esquema cacheado en cada frame.
    val vividPrimary = remember(seed, generation) {
        if (!generation.vividAccent || !generation.isDark) null
        else { scheme: ColorScheme -> scheme.withVividPrimary(seed) }
    }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = generation.isDark,
        isAmoled = false,
        style = generation.paletteStyle,
        modifyColorScheme = vividPrimary,
        specVersion = ColorSpec.SpecVersion.SPEC_2025
    ).primary
}

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
 * Se funde con [AppScreenColorSpec], que comparte duración con [AppBoundsTransform]: el color
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

    // Misma razón que en [MusicPlayerTheme] y [rememberAccentPreview]: la lambda entra en las
    // claves del `remember` interno de MaterialKolor, y una nueva por recomposición tiraría el
    // esquema cacheado en cada frame.
    val vividAccents = remember(seed, generation, monochrome) {
        if (seed == null || monochrome || !generation.vividAccent || !generation.isDark) null
        else { scheme: ColorScheme -> scheme.withVividPrimary(seed).withVividSecondary(seed) }
    }

    // El estilo, el tema claro/oscuro y el acento vivo salen de [LocalThemeGeneration] — el mismo
    // sitio del que los lee la previsualización del selector de color. Reconstruir aquí la llamada
    // a MaterialKolor con preferencias releídas por nuestra cuenta sería la tercera copia de esa
    // construcción, y la primera en separarse del resto sin que nada fallara.
    val scheme = if (seed != null) {
        rememberDynamicColorScheme(
            seedColor = seed,
            isDark = generation.isDark,
            isAmoled = false,
            style = if (monochrome) PaletteStyle.Monochrome else generation.paletteStyle,
            modifyColorScheme = vividAccents,
            specVersion = ColorSpec.SpecVersion.SPEC_2025
        )
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

// --- Acento vivo (reencuadre tonal de `primary`) ---

/**
 * Croma de sondeo para preguntarle al gamut "¿cuánto color admite este matiz a este tono?".
 * Cualquier valor por encima del máximo real de sRGB sirve: [Hct.from] recorta a lo alcanzable y
 * devuelve el croma que de verdad cupo. Es el mismo 200 que el spec 2021 le pide a Vibrant.
 */
private const val CHROMA_PROBE = 200.0

/** Resolución del barrido de tonos. 2 puntos de L* son imperceptibles y halvan el coste. */
private const val TONE_STEP = 2.0

/**
 * Ventana de tonos donde puede aterrizar el acento. No es estética sino de legibilidad: el acento
 * vive sobre superficies de tono ~6-22, y salirse de estos límites lo acercaría demasiado a su
 * propio fondo. El tono de máximo croma de un matiz suele caer dentro (los naranjas ~65, los azules
 * ~45), así que en la práctica casi nunca recorta.
 */
private const val VIVID_TONE_MIN = 50.0
private const val VIVID_TONE_MAX = 90.0

/** Contraste mínimo del contenido sobre el acento reencuadrado (AA de texto). */
private const val ON_PRIMARY_MIN_CONTRAST = 4.5f

/**
 * Croma del acento SECUNDARIO respecto al primario. `secondary` colorea artista/álbum del NowPlaying
 * (y el estado activo del aleatorio, el favorito): sin avivar, MaterialKolor lo deja casi gris en
 * carátulas de croma bajo, que es el defecto que motivó esto. Se le da el mismo tono de máximo croma
 * que a `primary` pero con el croma acotado a esta fracción del suyo, para que el rol siga siendo
 * SUBORDINADO —un acento más suave que el título— en vez de un duplicado exacto de `primary`.
 */
private const val SECONDARY_CHROMA_FACTOR = 0.75

/**
 * Tono, dentro de la ventana legible [VIVID_TONE_MIN]..[VIVID_TONE_MAX], donde [hue] admite MÁS croma
 * en el gamut sRGB. Se sondea con un croma imposible ([CHROMA_PROBE]) para leer el techo real en cada
 * tono. Compartido por [withVividPrimary] y [withVividSecondary] — el mismo tono para los dos acentos
 * los mantiene coherentes en luminosidad, distinguiéndose solo por el croma.
 */
private fun bestVividTone(hue: Double): Double {
    var bestTone = VIVID_TONE_MIN
    var bestChroma = -1.0
    var tone = VIVID_TONE_MIN
    while (tone <= VIVID_TONE_MAX) {
        val available = Hct.from(hue, CHROMA_PROBE, tone).chroma
        if (available > bestChroma) {
            bestChroma = available
            bestTone = tone
        }
        tone += TONE_STEP
    }
    return bestTone
}

/**
 * Reencuadra `primary` (y su `onPrimary`) al tono donde el MATIZ del álbum alcanza su croma
 * máximo. **Solo se llama en tema oscuro** (ver la condición en [MusicPlayerTheme]).
 *
 * El problema que resuelve: el tono de cada rol es una CONSTANTE del color spec, no algo que salga
 * de la carátula — `primary` vale 80 en oscuro con Equilibrado, pase lo que pase. Y a tono 80 el
 * gamut sRGB no admite croma alto, así que el acento sale pastel por construcción y no hay ajuste
 * que lo cambie. Es la razón de que ningún estilo alcanzara el color que System UI pinta en la
 * notificación de media: System UI no usa un rol M3, coloca el color extraído en su tono de máximo
 * croma. Esto hace lo mismo, pero SOLO para este rol y sin cambiar de estilo de paleta: con
 * Equilibrado las superficies siguen siendo moderadas y solo el acento se aviva.
 *
 * Conserva el croma del SEED en vez de saturar al máximo (que es lo que hace Vibrant con su croma
 * 74, y el Vibrant del spec viejo con 200): así una portada apagada da un acento apagado. Ese fue
 * el motivo de abandonar Vibrant como default el 21 jul 2026 — con un seed de croma ~10 pintaba un
 * cian fluorescente que no estaba en la carátula. Aquí eso no puede pasar: el techo es la portada.
 *
 * `onPrimary` se repone con [ensureContrast] porque al construir el color a mano se pierde la
 * garantía del par `color`/`onColor` que M3 sí da.
 *
 * No toca nada si el seed es acromático: sin matiz no hay croma que maximizar y el tono de máximo
 * croma sería un punto arbitrario de la escala de grises.
 */
private fun ColorScheme.withVividPrimary(seed: Color): ColorScheme {
    // El mismo veredicto de "esto es un gris" que usa el resto de la app, no un umbral paralelo:
    // vale para el caso que `monochrome` no cubre — un color elegido A MANO que resulta acromático
    // (ahí `hasManualColor` desactiva la guardia y el tema sí se seedea con él).
    val seedHct = Hct.fromInt(seed.toArgb())
    if (ArtworkRepository.isAchromatic(seed.toArgb())) return this

    // Tono donde ESTE matiz admite más color. Se sondea con un croma imposible para leer el techo
    // real del gamut en cada tono; el color final se construye después con el croma del seed.
    val bestTone = bestVividTone(seedHct.hue)

    // Croma: NUNCA por debajo del que el esquema ya le había dado a este rol. `primary` aquí es
    // todavía el original (`modifyColorScheme` corre al final), así que su croma es el de la
    // variante — 26 en TonalSpot oscuro.
    //
    // Tomar solo el del seed fue un error MEDIDO (28 jul): con una portada casi en blanco y negro
    // el acento salía a croma 9.3 mientras la barra de acciones iba a 39.8, o sea el play era el
    // elemento MENOS colorido de la pantalla.
    //
    // El techo sigue siendo el seed cuando este es MÁS cromático que la paleta (una portada vívida
    // da un acento vívido), y el suelo es la variante, así que esto no puede exagerar un seed
    // apagado más de lo que el propio estilo ya lo hace en el resto de los roles. Que es lo que
    // separa esto de Vibrant, cuyo croma fijo de 74 fue lo que arruinó Parasomnia.
    val targetChroma = maxOf(seedHct.chroma, Hct.fromInt(primary.toArgb()).chroma)
    val vivid = Color(Hct.from(seedHct.hue, targetChroma, bestTone).toInt())
    return copy(
        primary = vivid,
        onPrimary = ensureContrast(onPrimary, vivid, ON_PRIMARY_MIN_CONTRAST)
    )
}

/**
 * Aviva `secondary` (y repone su `onSecondary`) con el mismo criterio que [withVividPrimary]: el tono
 * de máximo croma del matiz del álbum, en vez del que le clava el color spec. **Solo en tema oscuro**
 * (ver la condición en [MusicPlayerTheme]), y **DESPUÉS** de [withVividPrimary] en la cadena, porque
 * lee el `primary` ya avivado para acotarse por debajo de él.
 *
 * El problema es el mismo que el de `primary` pero un escalón más callado: `secondary` colorea el
 * artista y el álbum del NowPlaying, el estado activo del aleatorio de la cola y la píldora de
 * favorito, y en una carátula de croma bajo MaterialKolor lo entregaba casi gris. La diferencia con
 * `primary` es a propósito: el croma se acota a [SECONDARY_CHROMA_FACTOR] del suyo para que estos
 * elementos sigan siendo un acento SUBORDINADO al título, no un segundo `primary`. La jerarquía entre
 * el título (primary) y los labels (secondary) sobrevive; lo que se recupera es que dejen de ser grises.
 *
 * No toca nada si el seed es acromático, igual que [withVividPrimary]: sin matiz no hay croma que
 * maximizar (el caso B&N ya va por `Monochrome`, y un gris elegido a mano no debe teñirse).
 */
private fun ColorScheme.withVividSecondary(seed: Color): ColorScheme {
    if (ArtworkRepository.isAchromatic(seed.toArgb())) return this
    val seedHct = Hct.fromInt(seed.toArgb())
    val bestTone = bestVividTone(seedHct.hue)

    // Suelo: el croma que el esquema ya le daba a `secondary` (nunca lo empeoramos). Objetivo: una
    // fracción del croma del ACENTO PRIMARIO ya avivado —no del seed crudo—, así el techo de secondary
    // sube y baja con el del título y la relación entre ambos es estable sea cual sea la portada.
    val primaryChroma = Hct.fromInt(primary.toArgb()).chroma
    val targetChroma = maxOf(
        Hct.fromInt(secondary.toArgb()).chroma,
        primaryChroma * SECONDARY_CHROMA_FACTOR
    )
    val vivid = Color(Hct.from(seedHct.hue, targetChroma, bestTone).toInt())
    return copy(
        secondary = vivid,
        onSecondary = ensureContrast(onSecondary, vivid, ON_PRIMARY_MIN_CONTRAST)
    )
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
