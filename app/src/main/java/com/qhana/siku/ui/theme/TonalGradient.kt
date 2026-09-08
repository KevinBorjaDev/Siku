package com.qhana.siku.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.pow

/**
 * Degradado vertical entre dos colores del esquema, interpolado en **HCT** y no en sRGB.
 *
 * `Brush.verticalGradient(listOf(a, b))` interpola los dos extremos componente a componente en
 * sRGB, y ese camino recto en el cubo RGB **no es recto en percepción**: entre un color con croma y
 * una superficie casi neutra, el tramo del medio pierde más croma del que le tocaría y el degradado
 * se ve lavado justo donde ocupa más pantalla. Es el mismo error de fondo que ya costó caro un nivel
 * más abajo, cuando el seed se guardaba proyectado con `Hct.withTone` y llegaba aplastado al tema.
 *
 * Aquí el brush sigue siendo lineal: lo que cambia es que se le dan [GRADIENT_STOPS] paradas
 * calculadas en HCT, así que el camino ENTRE los extremos mantiene el croma en vez de hundirse.
 * Los extremos se respetan tal cual llegan — este helper no decide colores, solo cómo se va de uno
 * al otro, y por eso el contraste de lo que se pinte encima no cambia.
 *
 * **NO sirve para un SCRIM**, y el fallo sería silencioso: HCT no tiene alpha, así que `Hct.fromInt`
 * lo descarta y `toInt()` devuelve opaco — un degradado a `Color.Transparent` saldría de aquí como
 * una pared sólida que tapa lo que había debajo. Los degradados a transparente de la app (headers de
 * artista/álbum/lista/género, el foco del karaoke) se quedan con `Brush.verticalGradient` y sus
 * propias paradas, que además no tienen este problema: ahí lo que viaja es el alpha, no el croma.
 */
fun tonalVerticalGradient(from: Color, to: Color): Brush =
    Brush.verticalGradient(colorStops = tonalGradientStops(from, to).toTypedArray())

/**
 * Las paradas del degradado: posición en la pantalla y color, ya interpolado en HCT.
 *
 * Separada de [tonalVerticalGradient] para poder razonarla (y probarla) sin un `Brush` de por medio.
 */
fun tonalGradientStops(from: Color, to: Color): List<Pair<Float, Color>> {
    val start = Hct.fromInt(from.toArgb())
    val end = Hct.fromInt(to.toArgb())

    // El MATIZ de un color sin croma no significa nada: `surface` es prácticamente acromático y su
    // hue es el residuo de cómo se generó, así que viajar hacia él haría girar el matiz de forma
    // visible en el tramo del medio — un degradado que cambia de color de camino a ninguna parte.
    // Cuando un extremo es neutro se usa el hue del otro y solo viajan croma y tono. El corte es el
    // mismo que usa `ArtworkRepository.isAchromatic` para decidir si un color tiene acento que dar.
    val startIsNeutral = start.chroma < NEUTRAL_CHROMA_CUTOFF
    val endIsNeutral = end.chroma < NEUTRAL_CHROMA_CUTOFF

    return List(GRADIENT_STOPS) { index ->
        val t = index / (GRADIENT_STOPS - 1.0)
        val hue = when {
            startIsNeutral && endIsNeutral -> start.hue
            startIsNeutral -> end.hue
            endIsNeutral -> start.hue
            else -> lerpHue(start.hue, end.hue, t)
        }
        val color = Color(
            Hct.from(
                hue,
                start.chroma + (end.chroma - start.chroma) * t,
                start.tone + (end.tone - start.tone) * t
            ).toInt()
        )
        // La posición NO es `t`: con el reparto uniforme el color muere en el tercio superior, que
        // es justo el que tapa la carátula, y abajo -donde queda pantalla libre- ya sólo hay
        // superficie. El exponente estira el recorrido de color hacia abajo sin mover los extremos.
        t.pow(COLOR_BIAS).toFloat() to color
    }
}

/**
 * El color que este degradado tiene a [screenFraction] de la pantalla (0 = arriba, 1 = abajo).
 *
 * Lo consume quien se DIBUJA encima y necesita saber sobre qué se apoya —hoy la barra de progreso y
 * sus chips, que eligen su rol contra el fondo real. Sale de la MISMA cuenta que el brush y por eso
 * vive aquí: `surface` no vale como respuesta (es el extremo de abajo, y con [COLOR_BIAS] por debajo
 * de 1 el color se estira hacia abajo, así que a media pantalla el fondo todavía tiene color), y una
 * fracción tabulada a mano se desincronizaría del degradado en cuanto alguien tocara el sesgo.
 *
 * La posición de una parada es `t^COLOR_BIAS`, así que para ir de la pantalla al parámetro hay que
 * invertirlo: `t = screenFraction^(1/COLOR_BIAS)`.
 */
fun tonalGradientColorAt(from: Color, to: Color, screenFraction: Float): Color {
    val start = Hct.fromInt(from.toArgb())
    val end = Hct.fromInt(to.toArgb())
    val t = screenFraction.coerceIn(0f, 1f).toDouble().pow(1.0 / COLOR_BIAS)
    val startIsNeutral = start.chroma < NEUTRAL_CHROMA_CUTOFF
    val endIsNeutral = end.chroma < NEUTRAL_CHROMA_CUTOFF
    val hue = when {
        startIsNeutral && endIsNeutral -> start.hue
        startIsNeutral -> end.hue
        endIsNeutral -> start.hue
        else -> lerpHue(start.hue, end.hue, t)
    }
    return Color(
        Hct.from(
            hue,
            start.chroma + (end.chroma - start.chroma) * t,
            start.tone + (end.tone - start.tone) * t
        ).toInt()
    )
}

/** Interpolación de matiz por el camino CORTO: el hue es circular y 350 a 10 son 20 grados, no 340. */
private fun lerpHue(from: Double, to: Double, t: Double): Double {
    val delta = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0
    return ((from + delta * t) % 360.0 + 360.0) % 360.0
}

/**
 * Cuántas paradas se le dan al brush. Son muestras del camino en HCT, así que con pocas se vuelve a
 * ver el atajo recto entre ellas y con muchas no se gana nada: el brush ya interpola lo que hay
 * entre dos paradas vecinas, y a esta distancia esos tramos son cortos.
 */
private const val GRADIENT_STOPS = 7

/**
 * Exponente que reparte las paradas por la pantalla. Por debajo de 1 el color se estira hacia abajo
 * (1 sería el reparto uniforme de siempre); cuanto más bajo, más tarda en llegar al neutro.
 * Calibrar EN DEVICE, que es lo único que decide cuánto color es demasiado detrás de los controles.
 */
private const val COLOR_BIAS = 0.75

/** Por debajo de este croma el matiz de un color ya no informa de nada. Ver [tonalGradientStops]. */
private const val NEUTRAL_CHROMA_CUTOFF = 5.0
