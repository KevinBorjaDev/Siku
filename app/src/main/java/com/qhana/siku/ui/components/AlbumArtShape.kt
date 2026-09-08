package com.qhana.siku.ui.components

import androidx.compose.material3.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import kotlin.math.min

/**
 * Radio de las esquinas del squircle: token `extraExtraLarge` de M3, la esquina más grande que
 * define el sistema. Se probó 32dp (`extraLargeIncreased`) y se descartó por escucha visual — a
 * ese radio la carátula se lee como tarjeta sobria y pierde el carácter Expressive, que es
 * justamente lo que aporta el token grande.
 *
 * Es un valor ABSOLUTO en dp y NO una fracción del lado, a propósito: con una fracción el radio
 * depende del tamaño de la carátula, así que la MISMA forma se leía distinta en portrait (~363dp
 * de lado) que en landscape, donde la carátula es bastante menor. Material especifica esquinas en
 * dp por esa razón. El valor anterior (28% del lado) daba ~102dp en portrait, más del doble de
 * este token, y se veía excesivamente redondo.
 */
private val SQUIRCLE_CORNER_RADIUS = 48.dp

/**
 * Key de la carátula compartida entre la píldora y el reproductor. **Acompaña** al container
 * transform ([PLAYER_CONTAINER_SHARED_KEY]) en vez de competir con él, que es lo que la distingue de
 * la versión que se eliminó el 15 ago.
 *
 * **Por qué vuelve** (16 ago): el container transform anima el CONTENEDOR y la portada existe en las
 * dos puntas, así que es un elemento compartido de libro. Sin ella, la portada quedaba a merced del
 * `resizeMode` del contenedor, y ninguna de las dos opciones sirve para una imagen que tiene que
 * cruzar la pantalla: escalada con el resto llega al sitio equivocado, y re-medida colapsa (es el
 * único `weight(1f)` de la columna, así que absorbe TODA la holgura del layout y se queda en cero
 * durante los primeros dos tercios del morph — la "doble animación" del 16 ago).
 *
 * **Cómo acompaña sin competir**: es un `sharedElement` ANIDADO dentro del `sharedBounds` del
 * contenedor, así que Compose lo eleva al overlay con SUS propios bounds interpolados y lo dibuja UNA
 * sola vez, al margen de la escala del padre. Es el patrón que la propia API espera (una imagen
 * compartida dentro de un contenedor compartido). El intento del 15 ago lo descartó por
 * "competir con el contenedor"; lo que competía en realidad era el `ContentScale.Crop` del padre, que
 * con dos superficies del mismo ancho da factor 1 y convertía el morph entero en una traslación.
 *
 * Es **constante y NO lleva el id de la canción**, al revés que [rowArtSharedKey]: las dos puntas
 * pueden estar mostrando temas distintos en el frame del tap (bug del 30 jul — la píldora aún tenía
 * la portada anterior, cada punta pedía una key diferente, no había match, y sin match no hay
 * animación sino un salto). Aquí no hace falta el id porque la píldora es ÚNICA: no hay diez
 * candidatas peleándose por la key, que es el motivo por el que las filas sí lo llevan.
 */
const val PLAYER_ART_SHARED_KEY = "player_art"

/**
 * Key de la carátula de UNA FILA de lista, la que viaja hasta el centro del reproductor cuando éste
 * se abre tocando una canción. Es un `sharedElement` **anidado** dentro del container transform de la
 * fila ([rowContainerSharedKey]), el mismo reparto que la píldora y su portada: el contenedor escala,
 * la portada viaja.
 *
 * Lleva el id de la canción porque es lo que le dice al reproductor DE QUÉ FILA sale: la fila que
 * participa declara su punta con su propia key y el player pide la key de la canción que va a sonar
 * (`NowPlayingLayer.artSharedKey`). Hasta el 16 ago era además una necesidad: una punta solo sirve de
 * ORIGEN si ya estaba compuesta y colocada antes de que aparezca su pareja (bug del 30 jul: la portada
 * aparecía quieta en su destino mientras el reproductor subía), así que TODAS las filas visibles
 * declaraban la suya siempre y con una key común habría habido diez destinos peleándose. Ahora solo
 * declara la fila con papel (`ContainerOriginRole`), preparada un frame antes; la key por canción
 * sigue siendo lo que identifica la pareja.
 */
fun rowArtSharedKey(songId: String): String = "album_art_row_$songId"

/**
 * Key del **container transform** de una FILA de canción hacia el reproductor: la superficie de la
 * fila crece hasta ser el player, con su portada viajando anidada ([rowArtSharedKey]).
 *
 * Es la misma coreografía que [PLAYER_CONTAINER_SHARED_KEY] con otra punta de origen, así que
 * comparte toda su configuración (`scaleToBounds(Fit)`, [com.qhana.siku.ui.theme.AppContainerBoundsTransform],
 * el z-order de [CONTAINER_SHADOW_OVERLAY_Z]); lo único propio es de dónde sale.
 *
 * Lleva el id **por el mismo motivo que [rowArtSharedKey]**: identifica de qué fila sale el player.
 * La familia es distinta de la de la portada porque son dos elementos compartidos ANIDADOS, no uno:
 * el contenedor escala, la portada viaja.
 */
fun rowContainerSharedKey(songId: String): String = "row_container_$songId"

/**
 * Key del **container transform** píldora ↔ reproductor: la superficie de la barra CRECE hasta ser el
 * player al abrir y se CONTRAE al cerrar. Es el patrón que Material define para "un contenedor se
 * convierte en una pantalla" (card/list item/FAB → detalle), y el de la referencia de M3 Expressive.
 *
 * **Las dos reglas del patrón, sacadas de los docs de Material, porque las dos se incumplieron en el
 * primer intento (15 ago) y por eso no funcionaba:**
 *
 *  1. *"Neither the incoming nor outgoing screens slide during a container transform."* — la pantalla
 *     del player NO se desliza; el único que mueve algo es el contenedor. Sumar el slide del NavHost
 *     hacía que se pelearan dos animaciones por lo mismo.
 *  2. *"Content is swapped rather than transitioned spatially."* — los contenidos se INTERCAMBIAN con
 *     un cruce de opacidad mientras ESCALAN con su contenedor; no viajan de una punta a la otra. La
 *     ÚNICA excepción es la portada ([PLAYER_ART_SHARED_KEY]), que existe en las dos puntas y sí
 *     viaja, anidada como `sharedElement` dentro de este `sharedBounds`.
 *
 * **El `ContentScale` del `scaleToBounds` es la pieza que hace o rompe el patrón**, y costó tres
 * intentos: tiene que ser `Fit` (el MENOR de los dos ratios) para que el contenido se achique con su
 * contenedor. `Crop` toma el MAYOR, y como las dos superficies comparten ancho eso vale 1 en la punta
 * del player — o sea el contenido no encogía nada y el morph se leía como un slide, dejando restos a
 * tamaño completo sobre la píldora al cerrar.
 *
 * Esta key es la de la PÍLDORA. La otra superficie que hace el mismo morph es la fila de canción
 * ([rowContainerSharedKey]); desde un chip o la notificación no hay superficie de origen en pantalla
 * y el reproductor entra fundiéndose.
 */
const val PLAYER_CONTAINER_SHARED_KEY = "player_container"

/**
 * ## La escala de z del morph, de abajo arriba
 *
 * Todo lo que participa en un container transform se dibuja en el **overlay** del
 * `SharedTransitionScope`, que va ENCIMA del árbol entero. Por eso el orden entre ellos no lo decide
 * la jerarquía de composición sino este `zIndexInOverlay`, y por eso los cuatro valores viven juntos:
 * son una sola escala y solo tienen sentido comparados entre sí.
 *
 *  1. **superficie ENTRANTE** ([CONTAINER_SURFACE_OVERLAY_Z_ENTERING], 0) — crece sólida por debajo.
 *  2. **superficie SALIENTE** ([CONTAINER_SURFACE_OVERLAY_Z_EXITING], 1) — se disuelve encima de la
 *     que entra, que es lo que hace que abrir y cerrar se vean iguales.
 *  3. **portada** ([CONTAINER_ART_OVERLAY_Z], 2) — por encima de las dos superficies, porque es lo
 *     único que VIAJA de una a la otra en vez de escalar con una de ellas.
 *  4. **chrome PERSISTENTE** ([CHROME_OVERLAY_Z], 3) — la píldora y el selector de pestañas, que
 *     existen ANTES y DESPUÉS de la transición y por tanto no pueden quedar tapados por algo que
 *     está de paso. No participan del morph: se suben al overlay solo para poder ordenarse frente
 *     a él (ver `overSharedElementsModifier`), porque el overlay dibuja sobre el árbol ENTERO y
 *     desde el árbol no hay z que alcance. Es el mismo motivo por el que ya se elevaba el título
 *     viajero de los detalles, un peldaño más arriba porque aquí lo que hay debajo es la portada.
 *

 * Los nombres son de CONTENEDOR y no del player porque la escala la comparten todos los container
 * transform de la app —píldora, fila de canción y (más adelante) tarjetas—: son la misma coreografía
 * con distinta punta de origen, y tener una escala por origen sería garantizar que se desincronicen.
 *
 * La SOMBRA de la píldora NO está en la escala porque ya no viaja: hubo un shared element solo-sombra
 * (`RemeasureToBounds`, z −1) del 16 al 17 ago y se quitó porque pintar un desenfoque al tamaño
 * interpolado costaba un blur de pantalla entera por frame al arrancar el cierre — ver `pillShadow` en
 * MiniPlayer.kt. Ahora se dibuja en su sitio, bajo el overlay, y solo se funde.
 *
 * El contenido de cada superficie no aparece en la escala porque no se ordena por su cuenta: va
 * DENTRO de su contenedor (`scaleToBounds` escala el nodo entero) y hereda su z. Sacarlo fuera obliga
 * a darle un z propio, y a que sea RELATIVO al de su superficie — se probó el 16 ago y se descartó
 * junto con el resto de esa variante.
 */
const val CONTAINER_SURFACE_OVERLAY_Z_ENTERING = 0f

/** Ver la escala completa en [CONTAINER_SURFACE_OVERLAY_Z_ENTERING]. */
const val CONTAINER_SURFACE_OVERLAY_Z_EXITING = 1f

/** Ver la escala completa en [CONTAINER_SURFACE_OVERLAY_Z_ENTERING]. */
const val CONTAINER_ART_OVERLAY_Z = 2f

/** Ver la escala completa en [CONTAINER_SURFACE_OVERLAY_Z_ENTERING]. */
const val CHROME_OVERLAY_Z = 3f

/**
 * Forma de la carátula, común al MiniPlayer y al NowPlaying: morph continuo entre un SQUIRCLE
 * (reproduciendo, progress 0) y un círculo (en pausa, progress 1; el mini lo usa fijo).
 *
 * El squircle es un cuadrado propio con [SQUIRCLE_CORNER_RADIUS] en vez de [MaterialShapes.Square]:
 * la forma del sistema apenas redondea las esquinas y en una carátula grande se leía como un
 * cuadro plano. Bajar el radio alarga el camino hasta el círculo, así que el morph al pausar se
 * nota más — lo absorbe el spring suave de `AlbumArtSection`, pero es el trade-off a vigilar si
 * se sigue bajando.
 *
 * Vive en `ui.components` (no en la pantalla) porque los DOS extremos del shared element de la
 * carátula la necesitan con el MISMO valor: si el mini usara un círculo fijo y el NowPlaying un
 * squircle, la transición saltaría de forma. Compartiéndola, ambos extremos coinciden para cada
 * estado de reproducción y el shared element solo interpola bounds.
 *
 * ## Rendimiento: esta forma vive en el camino más caliente de la app
 *
 * `createOutline` corre en cada frame de la apertura del reproductor (el shared element cambia
 * los bounds de la carátula frame a frame, en el mini Y en el player a la vez), encima del slide,
 * de la primera composición del NowPlaying y de la animación de los 31 colores del tema. La
 * versión inicial construía DOS `RoundedPolygon` + un [Morph] (matching de curvas, caro) en cada
 * llamada y devolvía siempre `Outline.Generic` (clip por Path, sin la vía rápida de hardware) —
 * medible como parte del tartamudeo al abrir una canción desde una lista. De ahí las tres reglas
 * de esta clase:
 *
 *  1. **En los extremos no hay Morph**: `p == 0` es un rectángulo redondeado y `p == 1` un
 *     círculo, y ambos se devuelven como [Outline.Rounded] — geometría idéntica a la del polígono
 *     (las esquinas de `RoundedPolygon` sin smoothing son arcos circulares) y clip acelerado por
 *     hardware. Son los DOS estados de reposo: el mini vive siempre en 1 y el player en 0 o 1, así
 *     que el camino caro queda solo para el tramo intermedio del morph al pausar/reanudar.
 *  2. **El Morph intermedio se cachea por tamaño** ([MorphCache]): mientras la forma morfea, el
 *     tamaño de la carátula no cambia (la "respiración" escala por graphicsLayer, no por layout),
 *     así que por frame solo se paga `toPath(p)`, no el matching.
 *  3. **Igualdad por valor** ([equals]/[hashCode]): el modifier de clip compara la forma nueva con
 *     la anterior, y una clase sin equals hacía que CADA recomposición (p. ej. los 31 colores del
 *     tema animándose al cambiar de canción) invalidara el outline aunque ni el progreso ni el
 *     tamaño hubieran cambiado.
 */
// El @OptIn cubre la extensión `Morph.toPath` de material3 (experimental, igual que MaterialShapes).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class AlbumArtMorphShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // El spring rebota fuera de [0,1]; Morph solo acepta ese rango.
        val p = progress.coerceIn(0f, 1f)
        // `minDimension` puede ser 0 en la primera medición: sin la guarda, las divisiones de
        // abajo darían NaN y el path saldría vacío.
        val side = size.minDimension
        if (side <= 0f) return Outline.Rectangle(size.toRect())
        // El radio en dp no puede superar la mitad del lado (= fracción 0.5 del espacio
        // normalizado del polígono, el tope que ya imponía la versión anterior).
        val radiusPx = min(with(density) { SQUIRCLE_CORNER_RADIUS.toPx() }, side / 2f)
        if (p == 0f) {
            return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radiusPx)))
        }
        if (p == 1f) {
            // Radios = semiejes: círculo en la carátula cuadrada, elipse si algún día no lo fuera
            // (que es lo mismo que dibuja el polígono círculo escalado por ancho/alto).
            return Outline.Rounded(
                RoundRect(size.toRect(), CornerRadius(size.width / 2f, size.height / 2f))
            )
        }
        return Outline.Generic(MorphCache.get(size, radiusPx / side).toPath(p))
    }

    override fun equals(other: Any?): Boolean =
        other is AlbumArtMorphShape && other.progress == progress

    override fun hashCode(): Int = progress.hashCode()
}

/**
 * Último [Morph] construido, con su clave. Un solo hueco basta: el único consumidor que llega al
 * tramo intermedio es la carátula del NowPlaying (el mini vive en el fast path de `p == 1`), y
 * durante su morph el tamaño es constante. Sin sincronización a propósito — `createOutline` corre
 * solo en el hilo de UI.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object MorphCache {
    private var lastSize: Size = Size.Unspecified
    private var lastRounding: Float = -1f
    private var lastMorph: Morph? = null

    fun get(size: Size, rounding: Float): Morph {
        lastMorph?.let { if (size == lastSize && rounding == lastRounding) return it }
        // Espacio normalizado 0..1 (igual que las MaterialShapes) y luego a píxeles.
        val squircle = RoundedPolygon.rectangle(
            width = 1f,
            height = 1f,
            centerX = 0.5f,
            centerY = 0.5f,
            rounding = CornerRounding(rounding)
        ).transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        val circle = MaterialShapes.Circle.transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        return Morph(squircle, circle).also {
            lastSize = size
            lastRounding = rounding
            lastMorph = it
        }
    }
}
