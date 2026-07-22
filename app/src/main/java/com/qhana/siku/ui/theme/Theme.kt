package com.qhana.siku.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.ColorScheme
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

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
    // Ajustes → Apariencia; cada uno decide CUÁNTO croma se aplica a los roles:
    // TonalSpot (default, el de Material You) es moderado y constante; Vibrant lleva el croma
    // al máximo —y por eso puede convertir un seed apagado en un color fluorescente que no
    // está en la carátula—; Content/Fidelity siguen el croma del seed pero dejan los neutros
    // casi grises. `monochrome` gana siempre: sin croma no hay matiz que estilizar.
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        seedColor != null -> rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = if (monochrome) PaletteStyle.Monochrome else paletteStyle
        ).animatedScheme()
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // Shapes DEFAULT de M3 (extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 dp).
        // Antes había un override "expressive" (8/12/16/24/32) que agrandaba todas las esquinas.
        shapes = Shapes(),
        // Motion Expressive: springs físicos en todas las transiciones M3 (button groups,
        // toggles, sheets, etc.) en lugar de los tweens lineales por defecto.
        motionScheme = MotionScheme.expressive(),
        typography = rememberAppTypography(),
        content = content
    )
}

/**
 * Anima la transición entre ColorSchemes (al cambiar de canción cambia el seed) para que
 * el cambio de acento global sea suave en toda la app, en lugar de un salto brusco.
 */
@Composable
private fun ColorScheme.animatedScheme(
    // Sin rebote (un color que "rebota" se ve raro). La rigidez subió de StiffnessLow a
    // MediumLow (21 jul 2026): con Low el esquema tardaba más de un segundo en asentarse y la
    // canción ya sonaba mientras la app seguía con el color de la anterior. MediumLow es
    // además la rigidez que usan el morph del botón play y la amplitud de la onda, así que
    // ahora el cambio de canción cierra a la vez en todo el reproductor.
    spec: AnimationSpec<Color> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
): ColorScheme {
    @Composable
    fun anim(target: Color, label: String): Color =
        animateColorAsState(targetValue = target, animationSpec = spec, label = label).value
    return copy(
        primary = anim(primary, "primary"),
        onPrimary = anim(onPrimary, "onPrimary"),
        primaryContainer = anim(primaryContainer, "primaryContainer"),
        onPrimaryContainer = anim(onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = anim(inversePrimary, "inversePrimary"),
        secondary = anim(secondary, "secondary"),
        onSecondary = anim(onSecondary, "onSecondary"),
        secondaryContainer = anim(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = anim(onSecondaryContainer, "onSecondaryContainer"),
        tertiary = anim(tertiary, "tertiary"),
        onTertiary = anim(onTertiary, "onTertiary"),
        tertiaryContainer = anim(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = anim(onTertiaryContainer, "onTertiaryContainer"),
        background = anim(background, "background"),
        onBackground = anim(onBackground, "onBackground"),
        surface = anim(surface, "surface"),
        onSurface = anim(onSurface, "onSurface"),
        surfaceVariant = anim(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = anim(onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = anim(surfaceTint, "surfaceTint"),
        inverseSurface = anim(inverseSurface, "inverseSurface"),
        inverseOnSurface = anim(inverseOnSurface, "inverseOnSurface"),
        outline = anim(outline, "outline"),
        outlineVariant = anim(outlineVariant, "outlineVariant"),
        surfaceBright = anim(surfaceBright, "surfaceBright"),
        surfaceDim = anim(surfaceDim, "surfaceDim"),
        surfaceContainer = anim(surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = anim(surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = anim(surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = anim(surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = anim(surfaceContainerLowest, "surfaceContainerLowest")
    )
}


/**
 * Traduce el nombre guardado en preferencias al [PaletteStyle] correspondiente.
 *
 * Se persiste el NOMBRE y no el ordinal a propósito: si una versión futura de MaterialKolor
 * reordena el enum, un ordinal guardado pasaría a significar otro estilo sin que nada falle.
 * Un nombre desconocido (estilo retirado de la librería) cae al default en vez de reventar.
 */
fun paletteStyleFromName(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.TonalSpot
