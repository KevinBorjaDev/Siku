package com.qhana.siku.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.qhana.siku.ui.theme.AppColors

/**
 * El velo que funde la imagen de un header inmersivo con el fondo de página y deja el título
 * apoyado sobre superficie casi sólida.
 *
 * **Existe porque estaba copiado y se quedó a medias.** Las cuatro pantallas de detalle —artista,
 * álbum, género y lista— tienen la misma anatomía: imagen o collage a sangre, título abajo a la
 * izquierda encima. Su scrim estaba escrito cuatro veces, y cuando se reforzó (una carátula clara
 * tapaba la parte alta de las letras, porque el degradado de dos paradas solo llegaba a ~0,73 de
 * `surfaceContainer` justo donde EMPIEZA el texto) se cambiaron dos: artista y álbum. Género y
 * lista se quedaron con la versión vieja, con el mismo comentario al lado explicando un arreglo que
 * no tenían. Es el modo de fallo típico de una regla duplicada — no es que alguien la escribiera
 * mal, es que había cuatro sitios donde acertar.
 *
 * **Las paradas dicen qué se está protegiendo, no son una curva bonita**: la imagen queda
 * INMERSIVA en el 70 % de arriba (transparente hasta [IMAGE_CLEAR_STOP], fade suave hasta
 * [HALF_VEIL_STOP]) y solo la banda del título, el 15 % de abajo, queda sólida desde
 * [SOLID_STOP]. Mover una obliga a mirar dónde cae el texto.
 *
 * Va sobre `surface` porque es el fondo de página de los cuatro detalles (M3 spec — 4 sep 2026;
 * antes era `surfaceContainer` por la "horquilla"), así que el degradado muere exactamente en el
 * color de lo que hay debajo del header y no se ve dónde termina. **Este valor SIGUE al
 * `containerColor` del `Scaffold` de los detalles — si uno cambia, el otro también, o hay costura.**
 *
 * **`Brush.verticalGradient` y NO `tonalVerticalGradient`**: esto es un scrim, o sea que necesita
 * alfa, y HCT no lo tiene — interpolar ahí daría un degradado opaco. Es la advertencia que ya está
 * anotada en `TonalGradient.kt`, aplicada aquí.
 */
@Composable
@ReadOnlyComposable
fun detailHeaderScrim(): Brush {
    val ground = AppColors.surface
    return Brush.verticalGradient(
        IMAGE_CLEAR_STOP to Color.Transparent,
        HALF_VEIL_STOP to ground.copy(alpha = HALF_VEIL_ALPHA),
        SOLID_STOP to ground,
        1f to ground
    )
}

/** Hasta aquí la imagen se ve limpia: es el tercio alto del header. */
private const val IMAGE_CLEAR_STOP = 0.3f

/** Donde el velo va por la mitad. Marca el final del fade suave. */
private const val HALF_VEIL_STOP = 0.7f

/** Desde aquí el fondo es sólido: es la banda donde se apoya el título. */
private const val SOLID_STOP = 0.85f

/**
 * Opacidad en [HALF_VEIL_STOP]. Es una parada de un degradado —translucidez de verdad—, no
 * jerarquía de contenido: eso en esta app se hace por tono, nunca por alfa.
 */
private const val HALF_VEIL_ALPHA = 0.5f
