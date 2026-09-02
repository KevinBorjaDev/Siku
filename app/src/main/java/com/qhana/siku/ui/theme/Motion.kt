package com.qhana.siku.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry

/**
 * Motion de la app: **una sola declaración** del [MotionScheme] y los envoltorios que hacen falta
 * para consumirlo desde donde no hay composición.
 *
 * ## Por qué existe este archivo
 *
 * `MusicPlayerTheme` ya instalaba `MotionScheme.expressive()` en el `MaterialTheme`, así que todo
 * componente M3 de fábrica se movía con springs — pero el código PROPIO de la app no lo leía casi
 * nunca: había ~55 `tween(...)` con duraciones a mano y ~15 `spring(stiffness = ...)` con
 * constantes clavadas. Consecuencia práctica: cambiar el scheme del tema no cambiaba casi nada,
 * porque las pantallas no preguntaban. Cada duración suelta era además un número mágico.
 *
 * ## DOS sistemas de motion, no uno
 *
 * M3 no unificó todo en springs, y confundirlo fue el primer error de esta migración:
 *
 *  - **Componentes** (un botón que morfea, un color que cambia, una fila que entra en una lista) →
 *    **springs**, los del [MotionScheme]. Es lo que introdujo Expressive en mayo de 2025 y lo que
 *    cubre el resto de este archivo.
 *  - **Transiciones de pantalla o de destino** (enter/exit, shared axis, container transform, fade
 *    through) → **easing + duración**, y concretamente los beziers Expressive de la sección
 *    "TRANSICIONES DE PANTALLA": son los MISMOS seis tokens de arriba, publicados por el spec en la
 *    forma que hace falta cuando un spring no sirve.
 *
 * No es una formalidad, y la diferencia es de mecánica, no de gusto: una transición necesita
 * **duración nominal** porque hay piezas que solo pueden sincronizarse contra un número — la
 * píldora del reproductor espera exactamente lo que dura el slide antes de desmontarse
 * (`snap(delayMillis = …)`), y un shared element tiene que aterrizar a la vez que la pantalla que
 * lo lleva. Un spring no ofrece ninguna de las dos cosas.
 *
 * Había un segundo motivo —el **predictive back** recorría la transición con el dedo
 * (`SeekableTransitionState`), y una duración nominal es más predecible de recorrer que la duración
 * calculada de un spring— que dejó de aplicar el 18 ago 2026, cuando el gesto se desactivó (ver el
 * manifest). El primero basta por sí solo, así que aquí no cambia nada.
 *
 * OJO con el sobrepaso al llevar un token spatial a pantalla completa: es ~1,4 % del recorrido, o
 * sea ~2 px en un botón (donde ES el efecto) y ~34 px en un slide de 2400 px, donde asoma una
 * franja de lo que hay detrás por el borde contrario. Para eso están [ScreenSlideEasing] y
 * [ScreenEnterEasing], que conservan el timing y recortan solo el exceso.
 *
 * ## Cómo elegir el spec de un COMPONENTE (la regla, no el gusto)
 *
 * La distinción **spatial vs. effects** no es de velocidad, es de qué se anima:
 *  - **spatial** → posición, tamaño, forma, rotación, escala. Los springs expressive están
 *    SUBAMORTIGUADOS a propósito (`dampingRatio` 0.8 el default, 0.6 el fast), o sea REBOTAN.
 *  - **effects** → color y alpha. `dampingRatio` 1.0: sin rebote, porque un color que sobrepasa su
 *    destino y vuelve se lee como un parpadeo, no como física.
 *
 * De ahí sale una consecuencia que hay que respetar: **nunca un spec spatial sobre un valor acotado
 * a [0,1]** que el consumidor no recorte (alpha, fracciones de progreso, `Morph.progress`). El
 * rebote se sale del rango. `AlbumArtMorphShape` recorta a propósito, y por eso ahí sí se puede.
 *
 * Y una segunda: la rigidez ya la decide el token, así que la elección real es *default* / *fast* /
 * *slow* según el peso del elemento, no un número de milisegundos.
 *
 * ## Dónde se lee cada cosa
 *
 *  - **Dentro de composición** → [appSpatialSpec] y sus cinco hermanos. Leen el scheme del
 *    `MaterialTheme`, así que respetan un tema anidado con otro scheme.
 *  - **Fuera de composición** (objetos `object`, `Shape`s, gestos, transiciones de `NavHost`) →
 *    [AppMotionScheme]. Es la MISMA instancia que recibe el `MaterialTheme`, no una copia de sus
 *    valores: por eso no se pueden desincronizar.
 *
 * Ojo con un caso intermedio que ya morció una vez: `transitionSpec` de `AnimatedContent` y las
 * lambdas de transición del `NavHost` **no son composables**, así que dentro no se puede leer el
 * tema. O se iza el spec a una `val` antes de entrar (lo que hacen `PlaybackControls` y
 * `DownloadManagerScreen`), o se usa [AppMotionScheme].
 *
 * Y un aviso que vale para todo lo de aquí: **omitir `animationSpec` NO es usar el motion del
 * tema.** `animateFloatAsState`, `animateDpAsState`, `animateColorAsState`, `Crossfade` y
 * `AnimatedVisibility` tienen defaults propios de `compose-animation` que no miran el
 * `MaterialTheme`. Había dos comentarios en el repo afirmando lo contrario.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppMotionScheme: MotionScheme = MotionScheme.expressive()

// ============================== ACCESO DESDE COMPOSICIÓN ==============================

/*
 * Los seis tokens, envueltos para que el caller no tenga que declarar
 * `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
 *
 * No es azúcar sintáctico: `MotionScheme` sigue siendo API experimental, y sin estos envoltorios
 * cada composable que quisiera un spec tenía que anotarse. Eso convertía "usar el motion del tema"
 * en un trámite de tres líneas y es parte de por qué el código propio seguía escribiendo `tween`.
 * Aquí la frontera con la API experimental se cruza UNA vez.
 *
 * Leen `MaterialTheme.motionScheme` y no [AppMotionScheme] directamente: si algún día se anida un
 * tema con otro scheme, esto lo respeta y la constante no.
 */

/** Posición, tamaño, forma. **Rebota** (dampingRatio 0.8) — ver el aviso del encabezado. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Como [appSpatialSpec] pero más rígido y con MÁS rebote (0.6): gestos pequeños, "pops". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appFastSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

/** Como [appSpatialSpec] pero blando: elementos grandes que no deben ir deprisa. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSlowSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

/** Color y alpha. Sin rebote (dampingRatio 1.0), que es lo que los hace seguros en [0,1]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

/** El más rápido del scheme. Para lo que sale de pantalla y para lo que debe estar ya visible. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appFastEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

/** El más lento sin rebote. Cambios de ambiente (el acento del álbum, el foco del karaoke). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSlowEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowEffectsSpec()

/**
 * El spec de POSICIÓN para `Modifier.animateItem`: el spatial del tema **más el umbral de un
 * píxel** (`IntOffset.VisibilityThreshold`).
 *
 * El umbral no es un adorno, y es el mismo motivo que ya está escrito para
 * [AppContainerBoundsTransform]: el `SpringSpec` que devuelve el `MotionScheme` no trae ninguno, y
 * sin él la animación no termina hasta que cada componente baja de 0.01
 * (`Spring.DefaultDisplacementThreshold`) — sobre un desplazamiento de varios cientos de píxeles
 * eso es una cola larga que no mueve NADA visible pero mantiene el item invalidando frames. El
 * default de `animateItem` sí lo trae, así que sustituirlo por el spec del tema a secas sería
 * cambiar el motion **y** perder eso por el camino.
 *
 * Los valores (damping/stiffness) NO se copian: se leen del spec del scheme y solo se le añade el
 * umbral. Si el scheme dejara de devolver un `SpringSpec`, se usa tal cual.
 */
@Composable
fun appItemPlacementSpec(): FiniteAnimationSpec<IntOffset> {
    val spec = appSpatialSpec<IntOffset>()
    return remember(spec) {
        (spec as? SpringSpec<*>)
            ?.let { spring<IntOffset>(it.dampingRatio, it.stiffness, IntOffset.VisibilityThreshold) }
            ?: spec
    }
}

// ============================== TRANSICIONES DE PANTALLA ==============================

/*
 * Los SEIS tokens del motion Expressive expresados como **easing + duración**, que es la forma en
 * que el spec los publica para las plataformas (y los sitios) donde un spring no sirve. Son el
 * MISMO movimiento que los springs de [AppMotionScheme]: cada bezier es el equivalente publicado
 * de su spring, no una aproximación inventada aquí.
 *
 * ## Por qué un token spatial es RÁPIDO aunque su duración diga 500 ms
 *
 * Los tres *spatial* tienen el segundo coeficiente **por encima de 1** (1.67 / 1.21 / 1.29): la
 * curva SOBREPASA el destino y luego asienta, que es la traducción exacta de un spring
 * subamortiguado. La consecuencia práctica —y es la que resuelve la queja de "la apertura se
 * siente lenta"— es que el recorrido visible se despacha en la primera tercera parte del tiempo
 * nominal: `ExpressiveDefaultSpatialEasing` ya está al **99,6 % a los 175 ms** de sus 500, y el
 * resto es un asentamiento de ~1,4 % que casi no se ve.
 *
 * Eso es lo contrario de `Emphasized` (0.2, 0, 0, 1), la curva del M3 **anterior** a Expressive,
 * que estuvo aquí hasta el 30 jul: esa llega al 87 % a mitad de camino y arrastra el 13 % restante
 * durante 250 ms. Misma duración nominal, y una se siente ágil y la otra perezosa. Por eso el
 * cambio a estos valores NO es cosmético: es lo que permite mantener duraciones ACOPLADAS entre el
 * player y su carátula (ver [AppBoundsTransform]) sin que la apertura se sienta lenta.
 *
 * Los tres *effects* no sobrepasan (todos sus coeficientes ≤ 1), igual que los springs de effects:
 * son para color y alpha, donde pasarse del destino se lee como un parpadeo.
 */

/** Gestos pequeños y "pops". El que más sobrepasa. */
val ExpressiveFastSpatialEasing = CubicBezierEasing(0.42f, 1.67f, 0.21f, 0.90f)
const val EXPRESSIVE_FAST_SPATIAL_MS = 350

/** El de referencia para lo que se mueve: posición, tamaño, forma. */
val ExpressiveDefaultSpatialEasing = CubicBezierEasing(0.38f, 1.21f, 0.22f, 1.00f)
const val EXPRESSIVE_DEFAULT_SPATIAL_MS = 500

/** Elementos grandes que no deben ir deprisa. */
val ExpressiveSlowSpatialEasing = CubicBezierEasing(0.39f, 1.29f, 0.35f, 0.98f)
const val EXPRESSIVE_SLOW_SPATIAL_MS = 650

/** Lo que debe estar ya visible (o ya ido) cuando el usuario levanta el dedo. */
val ExpressiveFastEffectsEasing = CubicBezierEasing(0.31f, 0.94f, 0.34f, 1.00f)
const val EXPRESSIVE_FAST_EFFECTS_MS = 150

/** Color y alpha, caso normal. */
val ExpressiveDefaultEffectsEasing = CubicBezierEasing(0.34f, 0.80f, 0.34f, 1.00f)
const val EXPRESSIVE_DEFAULT_EFFECTS_MS = 200

/** Cambios de ambiente (el acento del álbum). */
val ExpressiveSlowEffectsEasing = CubicBezierEasing(0.34f, 0.88f, 0.34f, 1.00f)
const val EXPRESSIVE_SLOW_EFFECTS_MS = 300

/**
 * [ExpressiveDefaultSpatialEasing] **recortado a 1**, para lo que se desplaza a PANTALLA COMPLETA:
 * el slide del reproductor y las cuatro transiciones del `NavHost`.
 *
 * El sobrepaso de los tokens spatial es ~1,4 % del recorrido. En un botón eso son 2 px y ES el
 * efecto; en un slide de pantalla completa son ~34 px de la pantalla saliéndose por su borde, o sea
 * una franja de lo que hay detrás asomando por el lado contrario (y en el reproductor, justo la
 * píldora que el slide acaba de tapar). Recortar conserva el TIMING —que es lo que hace que se
 * sienta rápido— y elimina ese efecto secundario.
 *
 * **Aquí el recorte vale y en [ScreenEnterEasing] no valía**, y la diferencia está en un solo
 * número: el *default* spatial termina en (0.22, **1.00**), así que se pasa y baja hasta el destino
 * SIN cruzarlo — saturar a 1 solo le quita el exceso. El *fast* termina en (0.21, **0.90**): cruza
 * por debajo y vuelve, y eso un `coerceAtMost` no lo puede tocar. Por eso las hojas usan una curva
 * de effects y esto no. Antes de recortar un easing, mirar el segundo punto de control.
 *
 * No aplica a los shared elements: esos viajan en el overlay, no dejan hueco detrás, y su pequeño
 * rebote al aterrizar es justamente lo que los hace ver vivos.
 */
val ScreenSlideEasing = Easing { fraction ->
    ExpressiveDefaultSpatialEasing.transform(fraction).coerceAtMost(1f)
}

/**
 * Duración de lo que empieza Y termina en pantalla: el token *default spatial*.
 *
 * La comparten las TRES cosas que se mueven coordinadas: las transiciones del `NavHost`, el slide
 * del reproductor ([com.qhana.siku.ui.PlayerOverlay]) y [AppBoundsTransform]. Que sea una sola
 * constante es load-bearing y está MEDIDO en device: un shared element viaja MIENTRAS su pantalla
 * se desplaza, y si las dos duraciones no coinciden el elemento llega a su destino antes que el
 * fondo que lo acompaña — se queda quieto en su sitio final mientras el resto sigue subiendo, que
 * es exactamente el síntoma que el usuario describió como "la carátula no sube con el resto del
 * contenido, simplemente aparece".
 *
 * El intento de desacoplarlos (30 jul: player a 450 ms con la carátula en el spring default de la
 * API, ~300 ms) venía de que la versión acoplada con `Emphasized` se sentía lenta. La lentitud era
 * de la CURVA, no del acople — ver el encabezado de esta sección.
 */
const val SCREEN_TRANSFORM_MS = EXPRESSIVE_DEFAULT_SPATIAL_MS

/** Lo que solo ENTRA (hojas a pantalla completa, cambio de pestaña): no persiste nada suyo. */
const val SCREEN_ENTER_MS = EXPRESSIVE_FAST_SPATIAL_MS

/**
 * Lo que solo SALE. Sigue siendo más corto que la entrada (350) a propósito: lo que se va no se hace
 * esperar.
 *
 * **300 ms y no 200**, que es donde estuvo y se leía como un corte: lo que recorre esta duración es
 * el ALTO COMPLETO de la pantalla, y a esa distancia el ojo necesita ver salir la hoja o parece que
 * simplemente ha dejado de estar. La escala del token es la misma para un chip de 32 dp que para una
 * hoja de 2400 px, así que en los recorridos largos hay que subir un peldaño. Las OPACIDADES se
 * quedan en el token *fast* (150): una hoja debe volverse transparente antes de terminar de bajar, o
 * se la ve arrastrarse.
 */
const val SCREEN_EXIT_MS = EXPRESSIVE_SLOW_EFFECTS_MS

/**
 * Easing de lo que ENTRA y sale del todo: hojas modales a pantalla completa, cambio de paso del
 * onboarding, cambio de pestaña del gestor.
 *
 * **Es una curva de EFFECTS, no de spatial, y no es un descuido.** Los tres tokens spatial
 * SOBREPASAN, y el *fast spatial* además **retrocede**: sus puntos de control son
 * (0.42, 1.67, 0.21, **0.90**) — el segundo está por DEBAJO de 1, así que la curva se pasa del
 * destino y vuelve desde el otro lado. Eso en un botón es el efecto buscado; en una hoja que ocupa
 * la pantalla es un rebote, y el usuario lo reportó en las tres del reproductor (letras, cola,
 * ecualizador). Los tokens de effects son la única familia del spec con todos los puntos de control
 * ≤ 1: llegan al destino y se quedan.
 *
 * **Recortar el spatial NO servía**, y estuvo así medio día: un `coerceAtMost(1f)` satura el
 * sobrepaso —congelando la animación mientras la curva va por encima de 1— pero no puede hacer nada
 * con el retroceso posterior, que es justo la parte que se ve como rebote. Salía peor que el
 * original: mismo rebote, con una parada artificial delante.
 */
val ScreenEnterEasing = ExpressiveDefaultEffectsEasing

/** Easing de lo que sale. Effects: no sobrepasa, así que no hace falta recortarlo. */
val ScreenExitEasing = ExpressiveFastEffectsEasing

// ============================== REVEALS DE CAMBIO DE CANCIÓN ==============================

/**
 * Progreso de los dos *reveals* de cambio de canción: la ventana cookie que descubre la carátula
 * nueva (`NowPlayingArt`) y la que descubre el acento nuevo en los controles (`AccentRevealGroup`).
 *
 * **Es una excepción DELIBERADA a "todo componente va por un token del scheme", y se probó al
 * revés**: el 30 jul se pasaron a `appSlowEffectsSpec()` y el reveal dejó de verse — 800 de rigidez
 * lo despachan en ~140 ms y estas dos animaciones necesitan durar para leerse como un descubrimiento
 * y no como un parpadeo. Ningún token sirve:
 *  - los de **effects** no rebotan (que es lo que hace falta: el progreso alimenta una escala y un
 *    clip, y pasarse de 1 agranda la ventana más de la cuenta), pero el más blando ya es demasiado
 *    rápido;
 *  - los **spatial** tienen la rigidez adecuada pero están subamortiguados.
 *
 * Así que el valor se queda escrito: crítico (sin rebote) y `StiffnessMediumLow`, que es lo que
 * tenían desde siempre y lo que el usuario reconoce como el efecto correcto.
 */
val AppRevealSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

// ============================== SHARED ELEMENTS ==============================

/**
 * Motion de los shared elements que viajan CON una transición del `NavHost` (foto de artista,
 * portada de álbum, tarjetas del home → detalle): mismo easing y duración que la pantalla que los
 * transporta, o el elemento aterriza en otro momento que su destino.
 *
 * Sin esto la API de shared transitions aplica su propio default (`spring(StiffnessMediumLow)`),
 * ajeno a la duración del `NavHost`. **Es un container transform, o sea una TRANSICIÓN**: por eso
 * lleva easing y duración y no un spring del `MotionScheme`.
 *
 * **La carátula del reproductor (píldora/fila ↔ NowPlaying) YA NO lo usa** (16 ago): desde que el
 * player es un container transform con [AppContainerBoundsTransform], la portada viaja con ESE mismo
 * spring, para aterrizar con la superficie que la lleva. La lección que dejó esta constante sigue
 * valiendo: un shared element tiene que asentar A LA VEZ que lo que lo transporta — cuando el player
 * era un slide de 500 ms y la portada iba con el spring default de la API (~300 ms) aterrizaba primero
 * y se quedaba flotando quieta, el "flash" del 30 jul; con el contenedor asentando a ~350 y la portada
 * a 500 pasaba lo contrario. Aquí NO se recorta el sobrepaso (ver [ScreenSlideEasing]): un shared
 * element viaja en el overlay y no destapa nada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AppBoundsTransform = BoundsTransform { _, _ ->
    tween(SCREEN_TRANSFORM_MS, easing = ExpressiveDefaultSpatialEasing)
}

/**
 * BoundsTransform del *container transform* píldora/fila ↔ player (ver [com.qhana.siku.ui.PlayerOverlay]).
 * Es el **spring SPATIAL *default* del [MotionScheme] STANDARD** de M3 — el recurso que la propia
 * librería define para "animaciones que cambian la FORMA o los BOUNDS de un componente" (su KDoc,
 * literal), que es exactamente este caso — **el MISMO en las dos direcciones**, con el umbral de
 * visibilidad de `Rect` que la API de shared elements usa en su propio spring por defecto.
 *
 * **Por qué STANDARD y no el EXPRESSIVE que usa el resto de la app** ([AppBoundsTransform] /
 * [AppMotionScheme]). Los dos son springs; la diferencia está en el damping (medido en las fuentes de
 * material3 1.5.0-alpha24, `ExpressiveMotionTokens`/`StandardMotionTokens`):
 *  - expressive default spatial: damping **0.8**, stiffness 380 → sobrepasa ~1.5 %: el REBOTE hero. En
 *    una punta SÓLIDA que queda a la vista al final (el contenido de la píldora, entrante al CERRAR)
 *    ese sobrepaso se ve como el contenido "rebotando" al asentarse.
 *  - standard default spatial: damping **0.9**, stiffness **700** → sobrepaso ~0.15 % (imperceptible).
 *    Constante de tiempo 1/(0.9·√700) ≈ 42 ms: el recorrido VISIBLE (al 95 %, 3τ) dura ~130 ms y
 *    asienta a un píxel sobre ~2500 px (`ln(2500)` ≈ 7.8 constantes) en ~330 ms — MÁS ÁGIL que un
 *    tween de [SCREEN_TRANSFORM_MS] sin sentirse lento, que es lo que un container transform pide.
 *
 * **El cierre NO va con el *fast*, y estuvo (17 ago, madrugada) — no reintroducirlo.** Se puso como
 * remedio a un "lag al scrollear tras cerrar" que se atribuyó a la cola del morph pisada por el primer
 * frame de scroll: acortar el cierre parecía sacarla del camino. Horas después Perfetto enseñó la causa
 * REAL —buffer stuffing por productores continuos de frames (ver la sección Motion de CLAUDE.md)— y se
 * arregló en su sitio, así que aquel acorte quedó como un parche sin enfermedad… y con un coste que sí
 * se veía: con stiffness 1400 la constante de tiempo baja a ~30 ms y el recorrido visible a ~90 ms —
 * once frames a 120 Hz—, o sea que el player se ESFUMABA en la píldora ("se perdió la animación de
 * cierre", 17 ago). El "los cierres son más cortos" de la guía de Material (MDC 300/250, un 17 %) no se
 * traduce con el token *fast* del scheme, que es el DOBLE de rigidez (el scheme no tiene un escalón
 * intermedio, y fabricar uno sería un número a mano); con un solo spring standard el morph es
 * simétrico, como el de la propia API (`sharedBounds` usa UN spring para ir y volver), y es la
 * configuración que se validó en device el 15-16 ago antes de aquel desvío.
 *
 * **`Rect.VisibilityThreshold` (un píxel) es load-bearing y no un detalle**: el spec que devuelve el
 * `MotionScheme` no trae umbral, y sin él un `SpringSpec` termina cuando cada componente baja de 0.01
 * (`Spring.DefaultDisplacementThreshold`): sobre un rect de 2500 px eso son 12.4 constantes de tiempo,
 * ~520 ms de transición de los cuales los últimos ~190 no mueven NI UN PÍXEL. Como el bounds es lo que
 * mantiene viva la rama saliente del `AnimatedContent` (`KeepUntilTransitionsFinished`) y el overlay
 * del `SharedTransitionScope` se redibuja en cada frame mientras corre, ese tramo era coste puro —
 * frames de overlay para nada y el desmontaje del NowPlaying ~190 ms más tarde, más cerca del primer
 * scroll. Es el mismo umbral que Compose pone en `DefaultBoundsAnimation` (`BoundsAnimation.kt`).
 *
 * Lo usan las tres puntas de cada morph —contenedor, sombra y carátula anidada— para que aterricen
 * JUNTAS: es la lección de [AppBoundsTransform] (un shared element asienta a la vez que lo que lo
 * transporta) aplicada dentro del container transform. Los fades del CONTENIDO
 * ([appContainerSurfaceExitSpec], 300 / [appContainerContentExitFast], 150) sí son más cortos que el
 * asentado del bounds a propósito: la superficie se disuelve antes de llegar y no deja residuo.
 *
 * Mismo criterio que [com.qhana.siku.ui.components.AppModalSheet], que ya usa `MotionScheme.standard()`
 * para que las hojas no reboten al llegar. **Lección que costó dos intentos** (effects easing = sin
 * rebote pero LENTO; luego spatial clampeado): "se siente lento" es la CURVA antes que la duración, y
 * antes de fabricar una curva a mano conviene mirar si el `MotionScheme` ya trae el spec correcto.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AppContainerBoundsTransform = BoundsTransform { _, _ -> ContainerBoundsSpec }

/**
 * El spring de [AppContainerBoundsTransform], construido UNA vez: los valores (damping/stiffness) son
 * los del token y no se copian — se leen del `SpringSpec` que devuelve el scheme y solo se le añade el
 * umbral de visibilidad de `Rect`. Si el scheme dejara de devolver un `SpringSpec`, se usa tal cual.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ContainerBoundsSpec: FiniteAnimationSpec<Rect> =
    MotionScheme.standard().defaultSpatialSpec<Rect>().let { spec ->
        (spec as? SpringSpec<*>)
            ?.let { spring<Rect>(it.dampingRatio, it.stiffness, Rect.VisibilityThreshold) }
            ?: spec
    }

/**
 * Cambio de COLOR que acompaña a una transición de pantalla — hoy, el `ColorScheme` propio de un
 * detalle de artista o de álbum, que se deriva de la imagen de SU header (ver `DetailContentTheme`).
 *
 * Comparte [SCREEN_TRANSFORM_MS] con [AppBoundsTransform] y con el slide del `NavHost` por el mismo
 * motivo que ellos entre sí: la carátula que viaja hacia el header y el color que sale de esa misma
 * carátula son **una sola cosa** para quien mira. Si el color acabara antes, la pantalla ya estaría
 * teñida mientras la portada todavía va por el aire; si acabara después, la portada aterrizaría y el
 * color seguiría moviéndose por detrás.
 *
 * **La curva sí es distinta, y tiene que serlo**: los tokens *spatial* sobrepasan (y1 > 1) y eso
 * sobre un color EXTRAPOLA fuera del segmento entre los dos valores — la regla de la sección de
 * arriba. Se usa la curva de *effects* de la misma familia: misma duración, sin rebote.
 *
 * **Es de `Float` y no de `Color` desde el 31 ago 2026**: el fundido de tema ya no anima 31 colores
 * en paralelo sino UNA fracción de la que se interpolan todos (ver `ProvideAppColors`), así que lo
 * que recibe el spec es ese 0→1. La curva y la duración son las mismas.
 */
val AppScreenColorSpec: FiniteAnimationSpec<Float> =
    tween(SCREEN_TRANSFORM_MS, easing = ExpressiveDefaultEffectsEasing)

// ============================== APARICIÓN VERTICAL ==============================

/*
 * Los cuatro helpers de abajo animan un TAMAÑO, y por eso todos usan specs de **effects** —los
 * críticos, sin rebote— aunque lo que cambie sea geometría. No es una excepción caprichosa a la
 * regla spatial/effects: es la consecuencia de CÓMO se anima un tamaño en Compose.
 *
 * `expandVertically`/`expandHorizontally` no mueven un `graphicsLayer`, cambian la MEDIDA del nodo:
 * cada frame es una pasada de layout que reacomoda a todos los vecinos. Un spring subamortiguado
 * oscila alrededor del tamaño final antes de asentarse, así que **el rebote multiplica el número de
 * pasadas caras** — y encima se ve como un temblor en el contenido de al lado, que no es lo mismo
 * que el "pop" simpático de un botón. Con el banner de sincronización el coste se nota entero: su
 * alto alimenta el `contentPadding` de la lista, así que cada frame de rebote reacomodaba la
 * biblioteca. Medido como pérdida de fps en las pestañas de la biblioteca (30 jul), mismo defecto.
 *
 * **Regla: un spec que rebota sobre algo que EMPUJA LAYOUT sale caro; sobre un `graphicsLayer`
 * (posición, escala, alpha) es gratis.**
 */

/**
 * Aparición de un bloque que EMPUJA al contenido de abajo (banner de sincronización, ficha técnica
 * del formato, secciones que se despliegan): crece en alto y entra por alpha.
 *
 * Existe como función y no como dos parámetros sueltos en cada llamada porque son dos specs con
 * duraciones distintas y es fácil equivocarse: el alto entra con el token *default* y el alpha
 * también, para que el bloque no se lea antes de haber terminado de abrirse.
 */
fun appExpandFadeIn(): EnterTransition =
    expandVertically(AppMotionScheme.defaultEffectsSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandFadeIn]. Sale con los tokens *fast*: lo que se va no se hace esperar. */
fun appShrinkFadeOut(): ExitTransition =
    shrinkVertically(AppMotionScheme.fastEffectsSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

/**
 * Entrada del BANNER de la biblioteca (sincronización, descargas, error): como [appExpandFadeIn] pero
 * con el token **slow** de effects.
 *
 * No es "lo mismo más lento por gusto". El banner es lo único de la app que aparece **sin que el
 * usuario haya pedido nada** y que además **empuja la lista entera hacia abajo**: con el token
 * *default* el bloque se plantaba de golpe y el contenido daba un salto que se lee como un fallo de
 * layout, no como algo que llega. Un elemento que interrumpe y desplaza necesita más recorrido que
 * uno que el usuario acaba de invocar.
 *
 * **Crecer y aparecer van A LA VEZ, y aquí sí tiene que ser así** — al revés que en [appBannerExit],
 * que encadena las dos fases. Si el hueco se abriera primero y la tarjeta se revelara después, habría
 * un par de décimas de agujero VACÍO creciendo bajo las pestañas; al entrar, el bloque tiene que
 * traerse su superficie desde el primer frame. La asimetría entre entrada y salida es deliberada.
 */
fun appBannerEnter(): EnterTransition =
    expandVertically(AppMotionScheme.slowEffectsSpec()) +
        fadeIn(AppMotionScheme.slowEffectsSpec())

/**
 * Salida del banner: **primero se apaga, DESPUÉS se cierra el hueco**. Dos fases encadenadas por el
 * `delayMillis` del segundo tween, no dos animaciones a la vez.
 *
 * Es lo que arregla el "no queda al desaparecer". Con las dos cosas simultáneas —que es lo que hace
 * [appShrinkFadeOut] y lo que hacía este banner— la tarjeta se ENCOGE MIENTRAS TODAVÍA SE VE: durante
 * ~150 ms hay un banner de media altura con el texto y la onda recortados a la mitad, y la lista
 * entera subiendo detrás. Se lee como un glitch de layout, no como algo que se retira. Apagándolo
 * primero, el movimiento del contenido de abajo ocurre cuando ya no hay nada que mirar ahí.
 *
 * **La entrada NO es simétrica y no debe serlo** (ver [appBannerEnter]): invertir el orden al entrar
 * —abrir el hueco y luego revelar— dejaría 200 ms de agujero vacío creciendo bajo las pestañas, que
 * es peor que el recorte. Al aparecer, el bloque tiene que traer su superficie desde el primer frame.
 *
 * Tweens y no los springs del scheme por una razón mecánica: un spring no admite `delayMillis`, y el
 * encadenado es justo lo que se busca. Las duraciones y curvas son los tokens de effects.
 */
fun appBannerExit(): ExitTransition =
    fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)) +
        shrinkVertically(
            tween(
                EXPRESSIVE_DEFAULT_EFFECTS_MS,
                delayMillis = EXPRESSIVE_FAST_EFFECTS_MS,
                easing = ExpressiveDefaultEffectsEasing
            )
        )

/**
 * Versión horizontal de [appExpandFadeIn], para lo que aparece DENTRO de una fila y desplaza a sus
 * vecinos (el anillo de descarga del toolbar).
 *
 * Existe como par aparte y no como parámetro porque elegir mal el eje no es un detalle: un bloque
 * que crece en el eje equivocado empuja el layout hacia donde no debe y se lee como un salto.
 */
fun appExpandWidthFadeIn(): EnterTransition =
    expandHorizontally(AppMotionScheme.defaultEffectsSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandWidthFadeIn]. */
fun appShrinkWidthFadeOut(): ExitTransition =
    shrinkHorizontally(AppMotionScheme.fastEffectsSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

// ============================== HOJAS A PANTALLA COMPLETA ==============================

/*
 * Las hojas propias que ocupan la pantalla y suben desde abajo (ecualizador, letras, cola) NO tienen
 * aquí un par enter/exit: las monta [com.qhana.siku.ui.components.SheetOverlay], que gobierna su
 * deslizamiento con un `graphicsLayer` y usa [SCREEN_ENTER_MS]/[SCREEN_EXIT_MS] con
 * [ScreenEnterEasing]/[ScreenExitEasing] — los mismos valores que tenía el par `appSheetEnter/Exit`
 * que vivió aquí hasta el 19 ago 2026.
 *
 * Se fueron por las dos cosas que ese par no podía dar y que están explicadas en el KDoc de
 * `SheetOverlay`: un `AnimatedVisibility` compone su contenido en el mismo frame en que arranca la
 * animación —y una animación por TIEMPO se salta lo que ese frame tarde—, y el `fadeIn`/`fadeOut`
 * que llevaban costaba un `saveLayer` de pantalla completa por frame. Sigue siendo el patrón
 * **enter and exit** del spec (una superficie que se pone ENCIMA de la UI, no un nivel del grafo) y
 * sigue yendo por easing + duración y nunca por springs; lo que cambió es quién lo aplica.
 *
 * Las hojas `ModalBottomSheet` son otra cosa y siguen yendo por
 * [com.qhana.siku.ui.components.AppModalSheet], que les quita el rebote a través del tema.
 */

/**
 * Enter/exit de una superficie que HOSPEDA UNA PUNTA DE SHARED ELEMENT (hoy: la píldora, que morfa
 * hacia el reproductor). Dos propiedades, y las dos son load-bearing:
 *
 * **1. Solo fundido, nunca slide.** Un slide del CONTENEDOR compite con el morph, que ya gobierna la
 * posición — y peor, desplaza los bounds que el `SharedTransitionScope` lee, que es lo que hacía
 * aterrizar la carátula desde el borde inferior al cerrar.
 *
 * **2. Dura [SCREEN_TRANSFORM_MS], igual que [AppBoundsTransform].** Un shared element vive atado a su
 * `AnimatedVisibilityScope`: **cuando esa transición termina, la punta deja de participar**. Con el
 * fundido en la duración de *effects* (200/150 ms) contra los 500 del morph, el shared element perdía
 * una de sus dos puntas a un tercio del camino — la píldora ya se había asentado (o descompuesto)
 * mientras la portada seguía viajando. Ese desajuste llevaba ahí desde siempre (la píldora se fundía
 * con las duraciones de las hojas, 350/300) y es la razón de fondo de que el morph "no se viera": no
 * es que estuviera mal configurado, es que se le cortaba el suelo.
 *
 * O sea que estas duraciones NO son de effects aunque lo único que se anime sea una opacidad: quien
 * manda aquí es el morph al que acompañan. Si cambia [SCREEN_TRANSFORM_MS], cambian con él.
 */
fun appFadeEnter(): EnterTransition =
    fadeIn(tween(SCREEN_TRANSFORM_MS, easing = ExpressiveDefaultEffectsEasing))

/** Contrario de [appFadeEnter]. Misma duración: ver el porqué en su KDoc. */
fun appFadeExit(): ExitTransition =
    fadeOut(tween(SCREEN_TRANSFORM_MS, easing = ExpressiveDefaultEffectsEasing))

/**
 * Cruce de contenidos de un *container transform* (`sharedBounds`), variante **solo el saliente se
 * disuelve** (el `FADE_MODE_OUT` de `MaterialContainerTransform`): el ENTRANTE aparece SÓLIDO
 * ([EnterTransition.None]) y el SALIENTE se funde encima. El entrante no se funde porque el
 * `sharedBounds` envuelve la superficie ENTERA (fondo incluido); un `fadeIn` la volvería translúcida y
 * se vería "aparecer transparente y luego sólida" en vez de crecer sólida.
 *
 * **Las dos puntas usan exit con DURACIÓN DISTINTA, y no es capricho** — es lo que evita el mosaico de
 * upscaling sin dejar nada vacío. Las dos usan `scaleToBounds`, que ESCALA el contenido en vez de
 * re-medirlo, así que al agrandarse se pixela:
 *  - **Píldora** (superficie chica) → [appContainerContentExitFast]: al ABRIR es la saliente y su rect
 *    crece, así que se desvanece en el primer tramo, antes de que el mosaico se note.
 *  - **Player** (superficie grande) → [appContainerSurfaceExitSpec] con el token *slow* de effects
 *    ([EXPRESSIVE_SLOW_EFFECTS_MS], 300): al CERRAR es el saliente y su rect ENCOGE, así que no se
 *    pixela y puede quedarse cubriendo a la píldora entrante —que arranca escalada— hasta que ésta
 *    casi se asienta. **La duración va atada al bounds** ([AppContainerBoundsTransform], que asienta
 *    a ~330 ms): apagarse un poco ANTES de que el bounds asiente, no después — con un fade más largo
 *    (500) el player seguía visible encogido con el bounds ya quieto y su contenido (el chip de origen)
 *    se veía como un RESIDUO sobre la píldora ya puesta; y más corto tampoco: estuvo en 200 (17 ago,
 *    madrugada) acompañando al cierre acortado que se revirtió (ver ese KDoc), y a esa duración el
 *    player se había disuelto cuando el morph apenas iba por la mitad — el cierre se leía como un
 *    parpadeo, no como una superficie que encoge.
 *
 * El exit del saliente es además lo que MANTIENE VIVA su punta esos ms; con exit 0 el morph de bounds
 * no tendría ventana y saltaría (por eso el rápido no es instantáneo). Eso vale para la punta atada a
 * un `AnimatedVisibility` (la píldora); la del player ya no depende de su `exit` para vivir —es
 * persistente— y por eso aplica el suyo a mano, ver [appContainerSurfaceExitSpec].
 */
fun appContainerContentEnter(): EnterTransition = EnterTransition.None

/**
 * Fundido de la superficie GRANDE (el player) al salir: dura casi lo que el bounds. Ver el KDoc de
 * arriba para el porqué de la duración.
 *
 * **Es un SPEC y no una `ExitTransition`, y ahí está el detalle que cuesta caro olvidar**: la punta
 * del player tiene la visibilidad gestionada a mano (el subárbol es persistente, ver `PlayerOverlay`)
 * y en ese modo el estado de reposo es `PostExit`, así que un `exit` con fade dejaría el alfa en 0 en
 * reposo y la vuelta `PostExit → Visible` la animaría la librería con su spring por defecto —no hay
 * parámetro para eso—: el reproductor CRECIENDO translúcido, justo lo contrario del patrón. La punta
 * va con `ExitTransition.None` y `NowPlayingScreen` aplica este fundido desde su propio
 * `graphicsLayer`, en la dirección que toca y con `snap` en la otra.
 */
fun appContainerSurfaceExitSpec(): FiniteAnimationSpec<Float> =
    tween(EXPRESSIVE_SLOW_EFFECTS_MS, easing = ExpressiveSlowEffectsEasing)

/** Exit RÁPIDO del container transform, para la superficie CHICA (la píldora) — ver [appContainerSurfaceExitSpec]. */
fun appContainerContentExitFast(): ExitTransition =
    fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))

/*
 * La RAMA saliente del `AnimatedContent` de la capa del reproductor NO lleva exit propio: usa
 * `ExitTransition.KeepUntilTransitionsFinished` (ver `PlayerOverlay`), que la mantiene viva hasta que
 * el bounds del `sharedBounds` asienta, por construcción y sin ninguna duración elegida a mano. Hubo
 * aquí dos fades para eso (350 abriendo / 300 cerrando, derivados de los springs) y se quitaron el
 * 17 ago: un fundido sobre una Box a pantalla completa obliga a HWUI a un `saveLayer` offscreen de la
 * pantalla ENTERA por frame ("alpha caused saveLayer 1080x2400" en atrace), que era buena parte del
 * coste del RenderThread durante el morph. Lo único que se funde es el CONTENIDO de la superficie.
 */


// ============================== NAVEGACIÓN ==============================

/**
 * Divisor del recorrido horizontal de AMBAS caras de un *shared axis X*: cada una se desplaza
 * `ancho / 3`. Hoy lo usa el ONBOARDING (sus pasos son un flujo lateral, que es para lo que el spec
 * reserva el eje X); el NavHost dejó de usarlo el 16 ago al pasar al eje Z (ver abajo).
 *
 * **Ninguna recorre el ancho completo, y eso es del spec, no una economía**: Material acompaña el
 * deslizamiento con un FADE precisamente para no tener que mover las caras de un borde al otro. El
 * desplazamiento comunica la dirección y la opacidad hace el trabajo de sustituir una por otra; con
 * el ancho completo el gesto se lee como dos hojas independientes cruzándose y se pierde la
 * jerarquía. Que las dos recorran LO MISMO es lo que las convierte en un *shared axis* de verdad: se
 * mueven como una sola pieza en un eje, en vez de una tapando a la otra.
 */
const val SCREEN_SLIDE_DIVISOR = 3

/**
 * Escala de la que LLEGA al arrancar (adelante) y de la que se VA al terminar (atrás) en el shared
 * axis Z: el hijo nace un poco más chico que la pantalla y crece hasta ocuparla. Es el
 * `incomingStartScale` de `ScaleProvider` en MDC-Android (`MaterialSharedAxis` con eje Z), la
 * implementación de referencia del patrón.
 */
const val SHARED_AXIS_Z_NEAR_SCALE = 0.8f

/**
 * Escala de la que se VA al terminar (adelante) y de la que LLEGA al arrancar (atrás): el padre se
 * hunde "hacia el usuario", ligeramente más grande, mientras se funde. `outgoingEndScale` de ese
 * mismo `ScaleProvider`.
 */
const val SHARED_AXIS_Z_FAR_SCALE = 1.1f

/*
 * Patrón **shared axis Z** del spec: padre → hijo. Es el eje que Material asigna a la navegación
 * jerárquica ("a parent-child navigation transitions along the z-axis"); el X es para flujos
 * laterales (onboarding, pasos) y el Y para steppers. Hasta el 16 ago el NavHost usaba X — dos caras
 * deslizándose un tercio— y con la fase 2 del container transform se corrigió el eje en vez de
 * añadir otro patrón: abrir un artista, un álbum o un ajuste es bajar un nivel, no ir hacia un lado.
 * Consecuencia práctica de elegir Z sobre un container transform por tile: **cero maquinaria por
 * ítem** en grillas y carruseles (lo que acaba de costar el lag de scroll en "Todas").
 *
 * Al ir ADELANTE la que llega crece de [SHARED_AXIS_Z_NEAR_SCALE] a 1 y la que se va se hunde de 1 a
 * [SHARED_AXIS_Z_FAR_SCALE]; al volver, exactamente al revés (el hijo se encoge hacia
 * [SHARED_AXIS_Z_NEAR_SCALE], el padre vuelve desde [SHARED_AXIS_Z_FAR_SCALE]). El MOVIMIENTO —la
 * escala— va con [SCREEN_TRANSFORM_MS] y [ScreenSlideEasing] (default spatial con el sobrepaso
 * recortado: sobre una escala, un 1,4 % de exceso sería un latido de la pantalla entera) porque las
 * rutas de detalle llevan un shared element —la foto del artista, la portada del álbum— y su
 * [AppBoundsTransform] tiene que durar exactamente lo mismo que la pantalla que lo transporta.
 *
 * La OPACIDAD va aparte y más corta (tokens de effects, 200 al entrar / 150 al salir), como en el X
 * de antes: la que llega se hace opaca mucho antes de terminar de crecer, así que el usuario lee
 * contenido y no un fantasma escalando media pantalla; la que se va se apaga rápido para que las dos
 * no convivan semitransparentes una encima de la otra (en Z ocupan el mismo sitio, no lados
 * distintos).
 */

/** Adelante: el hijo nace chico y crece hasta ocupar la pantalla. */
val appNavForwardEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    scaleIn(
        animationSpec = tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing),
        initialScale = SHARED_AXIS_Z_NEAR_SCALE
    ) + fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Adelante: el padre se hunde hacia el usuario mientras se funde. */
val appNavForwardExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    scaleOut(
        animationSpec = tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing),
        targetScale = SHARED_AXIS_Z_FAR_SCALE
    ) + fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

/** Atrás: el padre vuelve desde donde se había hundido. */
val appNavBackEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    scaleIn(
        animationSpec = tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing),
        initialScale = SHARED_AXIS_Z_FAR_SCALE
    ) + fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Atrás: el hijo se encoge hacia donde nació y se funde. */
val appNavBackExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    scaleOut(
        animationSpec = tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing),
        targetScale = SHARED_AXIS_Z_NEAR_SCALE
    ) + fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

// El reproductor NO es una ruta del NavHost: es una capa que hace *container transform* con la
// píldora (ver [com.qhana.siku.ui.PlayerOverlay]). Su morph lo pinta el `sharedBounds` de contenedor,
// no una transición de este archivo; abrir/cerrar el player no es navegación.

/**
 * Rutas sin dirección propia (hub ↔ hub): solo cruce de opacidad, el patrón *fade through* del spec.
 * Puro effects — no se mueve nada, así que no hay ningún token spatial que aplicar aquí.
 */
val appNavFadeEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Contrario de [appNavFadeEnter]. Sale antes de que entre la otra, como pide el *fade through*. */
val appNavFadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

/**
 * Duración del *fade through* de un cambio de destino: `motionDurationLong1` del spec.
 *
 * No sale de los tokens Expressive de este archivo porque no es una transición de ruta ni un
 * componente: es el patrón tabulado de M3 para cambiar de destino, y su reparto interno
 * ([FADE_THROUGH_THRESHOLD]) está definido sobre ESTA duración. Partirla en dos tokens sueltos
 * rompería la proporción, que es lo único que hace que el patrón se reconozca.
 */
const val FADE_THROUGH_MS = 300

/**
 * Dónde se cruzan las dos mitades: el saliente se apaga en el primer 35 % y el entrante aparece en
 * el 65 % restante (`FadeThroughProvider.FADE_THROUGH_THRESHOLD` en material-components-android).
 *
 * **Es lo que lo distingue de un crossfade**, y por eso no es negociable: en un crossfade las dos
 * vistas conviven semitransparentes y se ve el fondo a través de las dos; aquí nunca coinciden.
 */
const val FADE_THROUGH_THRESHOLD = 0.35f

/**
 * Escala inicial del entrante (`MaterialFadeThrough.DEFAULT_START_SCALE`).
 *
 * **La escala corre la duración COMPLETA, no solo mientras se ve el alfa**, y ese desfase es la
 * firma del patrón: el contenido ya está creciendo antes de hacerse visible, así que cuando aparece
 * ya viene en movimiento en vez de arrancar parado. Encogerla al tramo visible lo convierte en un
 * "zoom al aparecer", que es otra cosa.
 *
 * El saliente NO escala (`setScaleOnDisappear(false)`): solo se apaga.
 */
const val FADE_THROUGH_START_SCALE = 0.92f

/** Cuánto dura el apagado del saliente. */
private const val FADE_THROUGH_OUT_MS = (FADE_THROUGH_MS * FADE_THROUGH_THRESHOLD).toInt()

/**
 * El *fade through* del spec, para el `AnimatedContent` que cambia de destino.
 *
 * Traducción directa de `MaterialFadeThrough`: alfa del saliente en `[0, 35 %]`, alfa del entrante
 * en `[35 %, 100 %]` y escala del entrante de [FADE_THROUGH_START_SCALE] a 1 durante el 100 %.
 *
 * **Necesita las DOS vistas a la vez**, y de ahí que el contenido del destino lo pinte un
 * `AnimatedContent` y no las páginas del pager: con `beyondViewportPageCount = 0` el pager descarta
 * la saliente en el frame del cambio, así que el primer 35 % se quedaría sin nada que apagar — 105 ms
 * de fondo a la vista, que se leen como lentitud. Un patrón cuyo reparto depende de que haya un
 * saliente no se puede aplicar donde no lo hay.
 *
 * La curva es la de este archivo y no la `emphasized` del spec clásico: son la misma decisión en dos
 * generaciones (aquélla es pre-Expressive), y mezclar sistemas de curvas es justo lo que la sección
 * Motion prohíbe. Lo que se respeta del spec son las PROPORCIONES, que es lo que hace reconocible al
 * patrón.
 */
fun appFadeThrough(): ContentTransform =
    (
        fadeIn(
            tween(
                durationMillis = FADE_THROUGH_MS - FADE_THROUGH_OUT_MS,
                delayMillis = FADE_THROUGH_OUT_MS,
                easing = ExpressiveDefaultEffectsEasing
            )
        ) + scaleIn(
            initialScale = FADE_THROUGH_START_SCALE,
            animationSpec = tween(FADE_THROUGH_MS, easing = ExpressiveDefaultEffectsEasing)
        )
    ) togetherWith fadeOut(
        tween(FADE_THROUGH_OUT_MS, easing = ExpressiveDefaultEffectsEasing)
    )
