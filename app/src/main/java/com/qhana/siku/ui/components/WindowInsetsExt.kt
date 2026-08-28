package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * Los insets que la app esquiva, en UN solo sitio.
 *
 * Vivían escritos a mano en el reproductor (`MusicPlayerScreen`, `PlayerOverlay`,
 * `NowPlayingLayouts`) y en el rail de la biblioteca (`LibraryNavBar`), y NO existían en ninguna
 * otra pantalla: el 27 ago 2026, girado, el contenido de Estadísticas, Ajustes, el gestor de
 * descargas, las letras y los cuatro detalles se metía debajo de la perforación y de la barra
 * lateral del sistema. Con la definición compartida, cubrir una pantalla nueva es una línea.
 */

/**
 * Barras del sistema **MÁS** la perforación de pantalla.
 *
 * Es lo que de verdad hay que esquivar y lo que ningún componente trae por defecto: los
 * `windowInsets` de las barras de M3 salen de `systemBarsForVisualComponents`, que **ignora el
 * recorte**, y `ScaffoldDefaults.contentWindowInsets` hace lo mismo. En vertical da igual —el
 * recorte cae arriba, donde ya está la barra de estado—; en horizontal el recorte pasa a un
 * COSTADO, que es justo el lado que nadie estaba reponiendo.
 *
 * **No es `safeDrawing`** aunque se le parezca: ése incluye además el teclado, y una barra de
 * navegación que salta al escribir no es lo que nadie espera.
 */
internal val SafeBarInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Aparta el contenido de los COSTADOS ([SafeBarInsets], solo horizontal).
 *
 * Para una pantalla cuyo fondo debe llegar al borde y cuyo CONTENIDO se aparta, que es la regla que
 * la biblioteca ya seguía ("fondo hasta el borde, contenido apartado"). Va después del `background`.
 */
@Composable
internal fun Modifier.safeSidePadding(): Modifier =
    windowInsetsPadding(SafeBarInsets.only(WindowInsetsSides.Horizontal))

/**
 * Los costados como `PaddingValues`, para sumarlos a un `contentPadding` que ya existe.
 *
 * Lo pide una lista cuyo primer ítem debe seguir siendo edge-to-edge (el header inmersivo de los
 * cuatro detalles): ahí el inset NO puede ir en el `contentPadding` de la lista —le quitaría el
 * sangrado justo a la pieza que tiene que sangrar—, sino fila a fila.
 */
@Composable
internal fun safeSideInsets(): PaddingValues =
    SafeBarInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
