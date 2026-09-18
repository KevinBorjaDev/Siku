package com.qhana.siku.ui.components

import com.qhana.siku.ui.theme.LocalEffectivePaletteStyle
import com.qhana.siku.ui.theme.LocalThemeGeneration
import com.qhana.siku.ui.theme.playButtonRoles
import com.materialkolor.PaletteStyle
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.data.model.Song

import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.appCardColors
import com.qhana.siku.ui.theme.appContainerContentEnter
import com.qhana.siku.ui.theme.appContainerContentExitFast
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.EXPRESSIVE_SLOW_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveSlowEffectsEasing
import com.qhana.siku.ui.theme.appFastEffectsSpec

// ============== MINI PLAYER ==============

// PROGRESO: el mini lo muestra como ANILLO ondulado alrededor de la carátula (ver
// [MiniPlayerContent], donde vive el [WavyProgressRing]).
//
// Historia, porque es el cuarto intento y los tres anteriores se descartaron: (1) una barra de 3dp
// en el borde inferior que la forma de píldora obligaba a recortar 34dp por lado (a esa altura el
// contorno ya se ha metido hacia adentro); (2) un tinte del contenedor entero; (3) un RELLENO de
// altura completa cuyo borde vertical se leía como posición y que la píldora recortaba sola.
//
// El anillo saca el progreso de debajo del texto: el título vuelve a apoyarse en el contenedor
// LIMPIO. Cabe en los 8dp de aire que la carátula (56dp) deja dentro de los 72 de alto, va como
// HERMANO de la portada —no dentro de sus bounds— para no viajar con el shared element hacia el
// NowPlaying, y el tick de posición solo repinta (se lee dentro de la lambda de dibujo), nunca
// recompone.

/**
 * Elevación de la sombra del MiniPlayer: **nivel 4** del spec de M3 (sube del nivel 3 del FAB porque
 * la barra es la superficie más alta). Se dibuja a mano con [pillShadow], NO con la elevación
 * nativa del Card — ver ahí el porqué. En tema claro la sombra es la mitad del trabajo de despegar la
 * barra flotante; en oscuro M3 separa por tono y aporta poco.
 */
private val MiniPlayerShadowElevation = 8.dp

/** Opacidad del negro de la sombra pintada. Calibrada para acercarse a la elevación nativa de M3. */
private const val MINI_PLAYER_SHADOW_ALPHA = 0.28f

/** [pillShadow] sin transición que la module (mini sin scope compartido): sombra entera, constante. */
private val FullShadow: State<Float> = mutableStateOf(1f)

/**
 * Sombra de la píldora, dibujada como PÍXELES (`Paint.setShadowLayer`) en un `drawBehind` — la misma
 * técnica que `MaterialContainerTransform` de Material Components (que también pinta la sombra a mano,
 * porque la elevación nativa no se puede llevar a una transición).
 *
 * **Por qué a mano y no la elevación del Card.** La elevación nativa se dibuja mediante el RenderNode
 * del layer, y el overlay del `SharedTransitionScope` NO lo propaga: durante el vuelo la sombra
 * desaparecía y reaparecía DE GOLPE al asentar. Verificado que ni `Card(elevation)` ni
 * `Modifier.shadow` viajan (ambos usan `shadowElevation`). Pintada como píxeles, y en un Box HERMANO del
 * Card que no es shared element, se dibuja en su sitio desde el primer frame y solo cambia de opacidad.
 *
 * `setShadowLayer` solo funciona con aceleración hardware en **API 28+** (igual que Material
 * Components, que por eso deshabilita la sombra por debajo); en API 26–27 la barra queda sin sombra
 * —degradación elegante, se separa igual por color—.
 *
 * **[alphaFactor] es el PROGRESO del morph** (`shadowFactor`), no un fade elegido a mano: 1 en la
 * píldora, 0 en el player. Es lo que hace `MaterialContainerTransform` al INTERPOLAR la elevación —la
 * sombra solo es prominente cuando el contenedor es chico—.
 *
 * **La sombra NO viaja con el contenedor, y lo hizo (16 ago → 17 ago).** Estuvo montada como un shared
 * element aparte ([PLAYER_CONTAINER_SHARED_KEY] tenía un gemelo "solo-sombra") con `RemeasureToBounds`,
 * para que este Box se re-midiera al tamaño interpolado y pintara la sombra a ESE tamaño. Lo que se
 * pasó por alto es el COSTE DE DIBUJO: `setShadowLayer` es un filtro de desenfoque que HWUI resuelve
 * en un buffer fuera de pantalla del tamaño de la forma, y al arrancar el CIERRE la forma es la
 * PANTALLA ENTERA (el rect parte del player) — un desenfoque gaussiano de 1280×2772 en cada uno de los
 * primeros frames, que son justo los del recorrido visible del morph (~130 ms). Resultado: los frames
 * largos caían al principio, el spring (que va por tiempo) los saltaba y el cierre a la píldora se
 * leía como un parpadeo con bajón de fps — mientras el cierre a una FILA, que no lleva sombra, iba
 * fino. Al ABRIR el orden se invertía (barata al principio, cara al final, ya tapada por el player).
 * Y a tamaños grandes la sombra no aporta NADA que se vea: su halo queda fuera de la pantalla o bajo
 * el player opaco, y el `shadowFactor` la tiene casi a cero. Así que se dibuja siempre al tamaño de la
 * píldora, en su sitio: coste constante y chico, sin re-medir la capa por frame, y a la vista solo
 * cuando el contenedor ES la píldora — que es cuando se dibujaba antes también.
 */
private fun Modifier.pillShadow(shape: Shape, elevation: Dp, alphaFactor: State<Float>): Modifier = drawBehind {
    val blur = elevation.toPx()
    val a = MINI_PLAYER_SHADOW_ALPHA * alphaFactor.value
    if (blur <= 0f || a <= 0f || android.os.Build.VERSION.SDK_INT < 28) return@drawBehind
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.TRANSPARENT
        setShadowLayer(
            blur,                                   // radio de desenfoque
            0f,                                     // dx
            blur * 0.5f,                            // dy: la sombra cae hacia abajo
            Color.Black.copy(alpha = a).toArgb()
        )
    }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> {
                val rr = outline.roundRect
                nc.drawRoundRect(
                    rr.left, rr.top, rr.right, rr.bottom,
                    rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y, paint
                )
            }
            is Outline.Rectangle ->
                nc.drawRect(outline.rect.left, outline.rect.top, outline.rect.right, outline.rect.bottom, paint)
            is Outline.Generic -> nc.drawPath(outline.path.asAndroidPath(), paint)
        }
    }
}

/**
 * Default de los flows de progreso: sin posición ni duración el anillo no se dibuja.
 *
 * Es una instancia COMPARTIDA y no un `MutableStateFlow(0L)` en la firma: un default con
 * constructor crea un objeto nuevo en cada composición del mini que no los pase, y aquí solo hace
 * falta un cero constante. Los parámetros son no-nullables a propósito — con `StateFlow?` el
 * `collectAsStateWithLifecycle` quedaría detrás de un `?.`, o sea una llamada composable dentro de
 * una rama condicional, que es justo lo que conviene no tener en el camino de una recomposición
 * por segundo.
 */
private val ZeroProgressFlow: StateFlow<Long> = MutableStateFlow(0L)

/** Colores del título y el subtítulo del mini. */
@Immutable
private data class MiniPlayerColors(
    val titleColor: Color,
    val contentColor: Color
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    /** Ajustes → Apariencia: rectángulo redondeado en vez de la píldora del diseño original. */
    roundedRect: Boolean = false,
    /** Ajustes → Apariencia: botón de play redondo en vez de squircle. */
    roundPlayButton: Boolean = false,
    /**
     * Posición y duración como FLOWS, igual que el NowPlaying: la posición cambia cada segundo y
     * pasarla como valor recompondría el mini —y con él la carátula y el marquee— en cada tick.
     * Colectados aquí y leídos SOLO dentro de la lambda de dibujo, el tick invalida el dibujo del
     * anillo y nada más.
     */
    currentPositionFlow: StateFlow<Long> = ZeroProgressFlow,
    durationFlow: StateFlow<Long> = ZeroProgressFlow,
    /**
     * ¿La portada de esta barra VIAJA al reproductor ([PLAYER_ART_SHARED_KEY])? Solo cuando el player
     * se abre desde acá; desde una fila o un chip el player se engancha a otra punta (o a ninguna).
     *
     * Es un parámetro y no algo que el mini deduzca porque decide QUÉ SE DIBUJA, no solo qué se anima:
     * la punta se declara SIEMPRE —una que nace en el frame del gesto no tiene bounds y no hay match—,
     * pero un `sharedElementWithCallerManagedVisibility` con `visible = false` y nadie enfrente
     * simplemente NO SE PINTA. Sin este flag, abrir el reproductor desde una fila o un chip hacía
     * desaparecer de golpe la portada del mini en vez de desvanecerla con la barra.
     */
    artTravelsToPlayer: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    if (song == null) return

    // **Background SÓLIDO teñido con el álbum: `secondaryContainer`** (12 sep 2026) — salvo Fiel a la
    // carátula y Fidelidad, que van en `onSecondary` en oscuro y `primaryFixed` en claro (13 sep, ver
    // [miniPlayerRoles]) —, y esta elección
    // es la que GOBIERNA todo el color del mini: es el suelo contra el que se miden las otras cuatro
    // piezas, así que cambiarlo obliga a rehacerlas.
    //
    // **Estuvo en `primaryContainer` y ése era el problema de raíz.** Con la píldora en ese rol, el
    // play NO PUEDE ser `primary`: son el mismo color. Medido sobre las 8 paletas × 5 carátulas ×
    // 2 temas, el peor caso de `primary` contra `primaryContainer` es **4,9 puntos de tono** en
    // oscuro y **9,9 con croma 0** en claro. Por eso el play había acabado en el par invertido
    // (`onPrimaryContainer`), y de ahí salía la discrepancia que se veía al abrir el reproductor: el
    // glifo del play del mini y el del NowPlaying eran roles distintos, a 8 puntos de tono, en las
    // dos puntas del MISMO container transform.
    //
    // Con `secondaryContainer` el play vuelve al par tabulado de una acción llena y se separa
    // **31,1 puntos en oscuro y 41,8 en claro** (peor caso). Las dos puntas del morph quedan
    // idénticas.
    //
    // Se descartaron las superficies neutras (`surfaceContainerHigh`/`Highest`, `surfaceVariant`):
    // dan aún más separación al play, pero **pierden contra el fondo de la biblioteca en tema
    // claro** —7,8 puntos de tono con croma 4— y lavan el tinte del álbum, que es la identidad del
    // mini. Y los `*Fixed` colapsan con `primary` en oscuro (0,0 puntos): son invariantes al tema,
    // así que su peso tampoco lo es.
    //
    // Contra el fondo de la biblioteca esta píldora se separa por CROMA, no por tono (en claro: 2,8
    // puntos de tono y **19 de croma**) — el mismo mecanismo que la fila que suena, y vale por lo
    // mismo: es una superficie grande, donde el tinte se lee.
    val scheme = LocalAppColors.current
    val backgroundColor =
        miniPlayerRoles(LocalEffectivePaletteStyle.current, LocalThemeGeneration.current.isDark).pill

    // Título y artista: los dos en `onSecondaryContainer` CRUDO, el par tabulado de M3 con el fondo.
    val textColors = remember(scheme.onSecondaryContainer) {
        MiniPlayerColors(
            titleColor = scheme.onSecondaryContainer,
            contentColor = scheme.onSecondaryContainer
        )
    }

    // Forma ELEGIBLE en Ajustes → Apariencia. Por defecto píldora completa: el MiniPlayer FLOTA
    // sobre la navbar del home (ya no va edge-to-edge con fondo plano hasta abajo).
    //
    // La alternativa es el token `extraLarge` de M3 (28dp), no un radio inventado. A 72dp de alto
    // la píldora tiene 36dp de radio, así que 28 se lee CLARAMENTE como rectángulo redondeado sin
    // caer en la esquina dura que desentonaría con el resto de contenedores de la app.
    //
    // No se anima entre las dos: es una preferencia que se cambia en otra pantalla, así que nadie
    // ve la transición — animarla solo añadiría un spec que mantener.
    val shape = if (roundedRect) MaterialTheme.shapes.extraLarge else RoundedCornerShape(50)

    // contentDescription se resuelve aquí (contexto @Composable) porque el lambda de semantics {} no lo es.
    val miniPlayerDesc = stringResource(R.string.mini_player_desc, song.title)

    // CONTAINER TRANSFORM: esta superficie y la del reproductor son LA MISMA cambiando de tamaño, no
    // dos pantallas que se relevan (ver [PLAYER_CONTAINER_SHARED_KEY] para las dos reglas del patrón).
    //
    // `scaleToBounds` (no `RemeasureToBounds`) en las DOS puntas: escala el contenido en vez de
    // re-medirlo, que es lo que un container transform necesita — re-medir haría que cada layout se
    // recompusiera a tamaños intermedios absurdos en pleno vuelo. Lo que sí sale de este contenedor por
    // su cuenta es la PORTADA, que viaja como shared element propio ([PLAYER_ART_SHARED_KEY]) y por
    // tanto ya no la escala este Card.
    //
    // **NO se pasa `clipInOverlayDuringTransition`** (queda el default `ParentClip`): el Card (Surface)
    // ya recorta su propio contenido a `shape`, así que sin el clip nada se derrama. El player SÍ lo
    // pasa porque es un Box a pantalla completa sin auto-recorte. La sombra NO va dentro de este
    // contenedor (se escalaría en bloque y se volvía una mancha): es un Box HERMANO, ver [pillShadow].
    // Estado del shared element de contenedor (el Card): morfea con `scaleToBounds` hasta el player.
    val containerState =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) { rememberSharedContentState(key = PLAYER_CONTAINER_SHARED_KEY) }
        } else null
    SideEffect {
        com.qhana.siku.data.util.JankProbe.note {
            "píldora: match=${containerState?.isMatchFound} viajaPortada=$artTravelsToPlayer " +
                "trans=${animatedVisibilityScope?.transition?.currentState}→${animatedVisibilityScope?.transition?.targetState}"
        }
    }

    // Prominencia de la sombra = PROGRESO del morph (1 = píldora, 0 = player), leído de la PROPIA
    // transición del container transform. Es el `setProgress` de MaterialContainerTransform: la sombra
    // acompaña a la contracción y se va con la expansión. En reposo la transición está en `Visible` →
    // factor 1 → sombra normal. Es un `State` que se lee DIFERIDO dentro del `drawBehind` de
    // [pillShadow]: cambia en cada frame del morph y leerlo en composición recompondría el mini por frame.
    //
    // **Dura lo que el fundido del contenido del morph ([EXPRESSIVE_SLOW_EFFECTS_MS], 300 — el mismo
    // token que `appContainerSurfaceExitSpec`), NO `SCREEN_TRANSFORM_MS` (500), y el motivo se midió con
    // la sonda el 17 ago**: esta animación vive en la transición de la CAPA (`AnimatedContent`), y una
    // `Transition` corre hasta que su animación más larga termina. Con 500 ms era, de largo, la más
    // larga —el bounds asienta a ~330— así que sostenía TODA la capa 200 ms de más con la sombra ya
    // invisible (al abrir la tapa el player desde los primeros frames): (1) al abrir, la paleta del
    // NavHost retenida "hasta que asiente" aterrizaba a los ~550 ms, y un cierre rápido la pillaba
    // pendiente y la aplicaba en el PRIMER frame del cierre (25-33 ms de recomposición de la
    // biblioteca justo al arrancar el morph); (2) al cerrar, `KeepUntilTransitionsFinished` mantenía el
    // árbol del NowPlaying hasta los ~530 ms y su desmontaje (~25 ms) caía cuando el usuario ya
    // scrolleaba. Con 300 la capa asienta con el bounds: la paleta aterriza bajo el player y el
    // desmontaje coincide con el final del morph. Sigue siendo un token de EFFECTS (es opacidad).
    val shadowFactor: State<Float> = if (animatedVisibilityScope != null) {
        animatedVisibilityScope.transition.animateFloat(
            transitionSpec = { tween(EXPRESSIVE_SLOW_EFFECTS_MS, easing = ExpressiveSlowEffectsEasing) },
            label = "miniShadowFactor"
        ) { if (it == EnterExitState.Visible) 1f else 0f }
    } else FullShadow

    val containerSharedModifier =
        if (containerState != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                // El que SALE se dibuja ENCIMA (zIndex 1) y se disuelve sobre el que ENTRA, sólido
                // debajo: así al ABRIR se ve el contenedor CRECER (la píldora encima disolviéndose,
                // el player sólido debajo creciendo), no un revelado tipo slide.
                val exiting = animatedVisibilityScope.transition.targetState != EnterExitState.Visible
                Modifier.sharedBounds(
                    sharedContentState = containerState,
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = AppContainerBoundsTransform,
                    enter = appContainerContentEnter(),
                    // La píldora sale RÁPIDO (ver [appContainerContentExitFast]): es la superficie
                    // CHICA, así que al salir (abrir) se escala hacia ARRIBA y se pixela — desvanecerla
                    // en el primer tramo la quita de vista antes de que el mosaico se note. El player,
                    // que es la superficie grande, usa el exit LENTO en su punta.
                    exit = appContainerContentExitFast(),
                    // `Fit` y NO `Crop`. `Crop` toma el MAYOR de los dos ratios, y como las dos
                    // superficies comparten ancho (ratio 1) mientras el de alto es ~34, agrandaba el
                    // contenido de esta barra TREINTA Y CUATRO veces: el "14 Occasions" gigante que se
                    // veía cruzando la pantalla durante el morph. `Fit` toma el MENOR (1), así que el
                    // mini se queda a su tamaño natural y solo se funde, que es lo que se espera de un
                    // contenido que se INTERCAMBIA.
                    //
                    // **La otra punta usa `FillWidth`, y esa asimetría es la regla, no un descuido**
                    // (corregido el 17 ago 2026): lo que las dos comparten es el OBJETIVO —contenido
                    // a tamaño natural durante todo el morph, revelado por el recorte— y el
                    // `ContentScale` que lo consigue depende de si el contenido es más chico o más
                    // grande que el rect. Ver el bloque largo en `NowPlayingScreen`: con `Fit` allá,
                    // el reproductor se dibujaba al 3 % y hacía zoom desde el fondo.
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                        ContentScale.Fit,
                        Alignment.Center
                    ),
                    // Ver la escala en [CONTAINER_SHADOW_OVERLAY_Z].
                    zIndexInOverlay =
                        if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                        else CONTAINER_SURFACE_OVERLAY_Z_ENTERING
                )
            }
        } else Modifier

    // El MiniPlayer son DOS superficies superpuestas en un Box: DETRÁS, la SOMBRA (un Box vacío del
    // tamaño de la barra, en su sitio, que NO es shared element — ver [pillShadow]); DELANTE, el Card
    // con el container transform (`scaleToBounds`). El `modifier` externo (padding, arrastre) va en el
    // Box para que las dos compartan footprint exacto y la sombra quede pegada al borde de la barra.
    // Durante el morph el Card se dibuja en el overlay (encima de todo) y la sombra sigue aquí debajo,
    // fundiéndose con [shadowFactor]: al abrir el player la tapa en los primeros frames; al cerrar
    // aparece bajo la superficie que llega. Es lo que se ve; lo que no se ve ya no se dibuja.
    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ComponentConfig.MiniPlayerHeight)
                .pillShadow(shape, MiniPlayerShadowElevation, shadowFactor)
        )

        // Card: contenedor OFICIAL de M3 para "contenido de un solo tema que se toca para abrir".
        // M3 no tiene componente de mini-player; un toolbar no aplica (no es una fila de acciones y
        // necesita tap en toda la superficie). Card da ese onClick nativo (expandir al NowPlaying).
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                // Alto fijo al spec del floating toolbar M3 Expressive (64.dp).
                .height(ComponentConfig.MiniPlayerHeight)
                // DESPUÉS del tamaño: el container transform parte de los bounds ya medidos de la barra.
                .then(containerSharedModifier)
                .semantics { contentDescription = miniPlayerDesc },
            shape = shape,
            // Contenedor TRANSPARENTE: el tinte animado del álbum se dibuja en el Box interno para que
            // NO reciba el overlay tonal del Card (que teñiría el color según la elevación).
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            // Elevación 0: la sombra la pinta [travelingShadow] en el Box de atrás. La elevación NATIVA
            // del Card no vale aquí porque el overlay del shared element no la propaga.
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            val position = currentPositionFlow.collectAsStateWithLifecycle()
            val duration = durationFlow.collectAsStateWithLifecycle()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            ) {
                // Contenido. El progreso ya no rellena este contenedor: lo dibuja el anillo alrededor
                // de la carátula, así que el fondo queda LIMPIO bajo el texto.
                MiniPlayerContent(
                    song,
                    isPlaying,
                    onPlayPause,
                    onNextClick,
                    textColors,
                    isBuffering,
                    position,
                    duration,
                    roundPlayButton,
                    artTravelsToPlayer,
                    sharedTransitionScope,
                    animatedVisibilityScope
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    textColors: MiniPlayerColors,
    isBuffering: Boolean,
    /** Posición/duración para el anillo de progreso; se leen dentro del dibujo (repaint, no recompose). */
    position: State<Long>,
    duration: State<Long>,
    /** Ver el KDoc del parámetro homónimo en [MiniPlayer]. */
    roundPlayButton: Boolean,
    /** Ver el KDoc del parámetro homónimo en [MiniPlayer]. */
    artTravelsToPlayer: Boolean,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // contentDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playPauseDesc = if (isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play)
    val nextDesc = stringResource(R.string.common_next)
    // El icono muestra "pausa" también durante el buffering (transición de canción,
    // re-buffer): es el mismo criterio que usa NowPlaying (PLAYING || BUFFERING) y evita
    // el parpadeo del flapping de isPlaying sin retrasar el estado con un reloj.
    val showAsPlaying = isPlaying || isBuffering

    // **El play es el MISMO par que el del NowPlaying: `primary` de relleno y `onPrimary` de glifo**
    // (12 sep 2026). Eso es lo que M3 tabula para una acción llena, y es lo que hace que las dos
    // puntas del container transform enseñen el mismo botón: antes el mini usaba el par invertido
    // del contenedor y los dos glifos se llevaban 8 puntos de tono a mitad del morph.
    //
    // Sólo es posible porque la PÍLDORA cambió a `secondaryContainer` (ver el bloque de arriba). El
    // orden causal importa: no se eligió el par y luego el fondo, fue al revés — con la píldora en
    // `primaryContainer` este par es inviable por medición, no por gusto.
    //
    // **El "siguiente" y el anillo dependen de la PALETA y del TEMA** (13 sep 2026). La tabla está en
    // [miniPlayerRoles] y cada fila la eligió el usuario en el banco de roles, validada contra seis
    // carátulas de su propia paleta y su propio tema.
    //
    // Por qué una tabla y no un rol: la búsqueda exhaustiva del 12 sep —los 31 roles del esquema
    // contra las 80 combinaciones— NO encontró ningún relleno para el "siguiente" que sirviera en
    // TODAS las paletas a la vez. Eso sigue siendo cierto. Lo que no es cierto es que no exista uno
    // por paleta: en Neutro, Rainbow y FruitSalad la familia secundaria colapsa en la primaria, así
    // que el rol que separa en Equilibrado es invisible ahí, y al revés. Un rol único tenía que
    // valer para las dos situaciones; una tabla no.
    //
    // Sigue sin haber NADA derivado: la tabla sólo ELIGE entre roles tabulados, y el `when` es
    // exhaustivo sobre [PaletteStyle], así que un estilo nuevo en MaterialKolor no compila hasta
    // que alguien le asigne roles.
    //
    // Antes de todo esto las cuatro piezas se DERIVABAN con `transportLayersOn`. Medido el 11 sep
    // con la paleta azul en Equilibrado, los dos candidatos para el play quedaban a 49,99 y 50,04
    // puntos de la píldora: ganaba `secondary` por CINCO CENTÉSIMAS de tono, así que la familia del
    // botón la decidía un empate. Mismo "margen cero" que dejó las rejillas de la biblioteca en una
    // columna.
    // Play: la MISMA tabla que el NowPlaying ([playButtonRoles]), para que el morph no cambie el botón.
    val playRoles = playButtonRoles()
    val playContainer = playRoles.container
    val playContent = playRoles.content
    // Estilo EFECTIVO, no el de Ajustes: una carátula en blanco y negro pinta en `Monochrome`.
    val roles = miniPlayerRoles(LocalEffectivePaletteStyle.current, LocalThemeGeneration.current.isDark)
    val sideContainer = roles.nextContainer
    val sideContent = roles.nextContent

    // Izados: los `transitionSpec` de los dos `AnimatedContent` del botón de play no son lambdas
    // composables. Mismos tokens que el glifo del transporte del NowPlaying — es el mismo control.
    val bufferFadeIn = appEffectsSpec<Float>()
    val bufferFadeOut = appFastEffectsSpec<Float>()
    val glyphEnterScale = appSpatialSpec<Float>()
    val glyphExitScale = appFastSpatialSpec<Float>()

    Row(
        // fillMaxHeight + CenterVertically: el contenido se centra en los 64.dp fijos del Surface
        // sin depender de un padding vertical calculado a mano. Padding interno = spec del
        // floating toolbar (8dp).
        modifier = Modifier.fillMaxHeight().padding(horizontal = ComponentConfig.FloatingBarInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Punta ORIGEN de la portada compartida ([PLAYER_ART_SHARED_KEY]). El resto del contenido del
        // mini SÍ se intercambia con un cruce de opacidad, como manda el container transform; la
        // portada es la excepción porque existe en las dos puntas.
        //
        // `sharedElementWithCallerManagedVisibility` y no el atado al scope, igual que en el otro
        // extremo (ver el comentario en `AlbumArtSection`): del scope se lee solo la INTENCIÓN
        // (`targetState`), nunca su duración — atar la punta al scope mataba el morph cuando esa
        // transición terminaba y la portada aparecía quieta en su destino. Exactamente una de las dos
        // puntas está `visible` en cada sentido, que es lo que decide cuál es origen y cuál destino.
        //
        // Se declara SIEMPRE, no solo al abrir: una punta que nace en el frame del gesto no tiene
        // bounds que ofrecer y no hay match (la regla del 30 jul). Mientras nadie la empareja es un
        // shared element solitario, inofensivo.
        val artSharedModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    // `|| !artTravelsToPlayer`: si nadie va a recogerla al otro lado, esta punta se
                    // queda VISIBLE y se desvanece con la barra, en vez de desaparecer de golpe.
                    val artVisible =
                        animatedVisibilityScope.transition.targetState == EnterExitState.Visible ||
                            !artTravelsToPlayer
                    Modifier.sharedElementWithCallerManagedVisibility(
                        sharedContentState = rememberSharedContentState(key = PLAYER_ART_SHARED_KEY),
                        visible = artVisible,
                        boundsTransform = AppContainerBoundsTransform,   // el spring del contenedor: aterriza con él (ver NowPlayingArt)
                        zIndexInOverlay = CONTAINER_ART_OVERLAY_Z
                    )
                }
            } else Modifier

        // Carátula + anillo de progreso ONDULADO (Expressive). Llena el Box CONTENEDOR, un poco
        // mayor que la portada (deja sitio al trazo + la amplitud de la onda en el aire que la barra
        // reserva). Es un HERMANO de la carátula y NO
        // entra en los bounds del shared element: la portada viaja sola al NowPlaying y el anillo se
        // queda, desvaneciéndose con el MiniPlayer. `progress` es una lambda que lee la posición → el
        // tick la repinta, no recompone (mismo patrón que el indicador de descarga del toolbar).
        //
        // **Arco y riel salen de la misma tabla que el "siguiente"** ([miniPlayerRoles]): el riel es
        // un trazo de 3dp que tiene que verse sobre la píldora Y quedar por debajo del arco, y el rol
        // que lo consigue cambia con la paleta por el mismo motivo que el relleno del "siguiente".
        //
        // **NO usar un alfa aquí.** Se probó `primary` y `onSecondaryContainer` rebajados con
        // [ACCENT_TRACK_ALPHA] y se revirtió el 12 sep 2026: este riel NO está sobre la carátula
        // —se dibuja alrededor de ella, sobre la píldora—, así que no entra en ninguna de las
        // excepciones de alfa del proyecto (scrims, degradados, pistas sobre carátula y los estados
        // deshabilitados que M3 tabula).
        val arcColor = roles.arc
        val trackColor = roles.track
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize)
        ) {
            // Anillo PROPIO y no `CircularWavyProgressIndicator`: el oficial no expone su fase, así
            // que ondula a la tasa de la pantalla mientras haya música y esa producción continua es
            // lo que impedía que la cola de SurfaceFlinger drenara (36 % de los frames con buffer
            // stuffing sonando, contra 10 % en pausa — medido con Perfetto el 19 ago 2026). El
            // nuestro publica el avance solo cuando la onda se movió un píxel, que es la regla que
            // ya usan la cookie del play y la onda del NowPlaying. Ver [WavyProgressRing].
            WavyProgressRing(
                progress = {
                    val total = duration.value
                    if (total <= 0L) 0f else (position.value.toFloat() / total).coerceIn(0f, 1f)
                },
                // Onda al reproducir, anillo PLANO en pausa (y el reloj parado). `showAsPlaying` y no
                // `isPlaying` a secas por lo mismo que el icono del play: en el cambio de canción con
                // streaming `isPlaying` cae un instante mientras bufferiza, y con el crudo la onda se
                // aplanaba y volvía a levantarse en ese frame — un parpadeo del anillo sin pausar.
                playing = showAsPlaying,
                color = arcColor,
                trackColor = trackColor,
                strokeWidth = ComponentConfig.MiniPlayerRingStroke,
                // Tamaño EXPLÍCITO: el Box contenedor ya lo fija, y así el anillo queda concéntrico
                // con la carátula y dentro del aire que la barra reserva.
                modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize)
            )
            // En el mini la carátula es SIEMPRE un círculo, reproduzca o no (decisión de diseño:
            // a este tamaño el morph a squircle no se leía y solo agregaba ruido). Se usa
            // AlbumArtMorphShape(1f) —el mismo path de círculo que el NowPlaying en pausa— para que la
            // portada se lea igual en las dos puntas del container transform.
            // El tamaño se DERIVA del anillo (que a su vez sale del alto y el padding al pill), no
            // de una constante suelta: si cambia el alto o el padding, la carátula lo sigue.
            AlbumArt(
                albumArtUri = song.albumArtUriString,
                size = ComponentConfig.MiniPlayerArtSize,
                shape = AlbumArtMorphShape(progress = 1f),
                modifier = artSharedModifier
            )
        }

        Spacer(modifier = Modifier.width(ComponentConfig.MiniPlayerTextGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = textColors.titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(
                    // Marquee finito: el MiniPlayer es persistente, un scroll infinito
                    // mantendría una animación viva recomponiéndose siempre. 3 pasadas bastan.
                    iterations = 3,
                    velocity = 30.dp
                )
            )
            Spacer(modifier = Modifier.height(ComponentConfig.MiniPlayerTextLineGap))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = textColors.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // MISMO gap que hay entre la carátula y el texto: el bloque de título/artista queda con el
        // mismo aire a ambos lados. Con el gap de los botones (4dp) el texto llegaba pegado al
        // play. Todo el ancho que sobra se lo sigue quedando la Column de arriba.
        Spacer(modifier = Modifier.width(ComponentConfig.MiniPlayerTextGap))

        // Solo play + siguiente. El "anterior" salió del mini a propósito: con tres controles
        // el texto se quedaba en ~155dp (marquee permanente) y los botones no llegaban al
        // mínimo táctil de 48dp. Sigue disponible en el NowPlaying, la notificación, Android
        // Auto y Wear — que es donde lo ponen YT Music, Spotify y Apple Music.
        //
        // Botón Play/Pause — SQUIRCLE o CÍRCULO según [roundPlayButton] (Ajustes → Apariencia). Las
        // dos son formas TABULADAS del catálogo de icon buttons (`ContainerShapeSquare` /
        // `ContainerShapeRound`), así que el ajuste elige entre dos valores del spec y no entre dos
        // geometrías inventadas. Y las dos son `CornerBasedShape`, que es lo que hace que el morph
        // al pulsar lo interpole M3 solo (`IconButtonShapes.isCornerBasedShape`) — de ahí que aquí
        // no haya ni `Shape` propio ni animación a mano, al revés que con la píldora inclinada de
        // `MaterialShapes.Pill` que ocupó este sitio hasta el 24 ago 2026.
        //
        // **El squircle es el de la talla MEDIUM (`CornerLarge`, 16dp) y no el de la Small
        // (`CornerMedium`, 12dp)**, y ese valor está CERRADO en device (24 ago 2026): se probaron
        // los tres escalones del spec por arriba y por abajo y 12 se veía demasiado cuadrado
        // mientras que 20 (`LargeIncreased`) se veía demasiado redondo. NO volver a moverlo sin
        // mirarlo en device. El techo de este botón es geométrico y está a un paso: mide 48 y
        // `RoundedCornerShape` recorta cada esquina a la mitad del lado, así que **un radio de 24
        // YA ES el círculo** y las dos opciones del ajuste quedarían iguales.
        // Pedirle la forma a la talla de al lado es la MISMA desviación que este botón ya tiene en
        // su TAMAÑO —48 tampoco es talla tabulada, es su mínimo táctil—, así que sigue siendo un
        // valor del spec y no un radio a ojo.
        MiniTransportButton(
            onClick = onPlayPause,
            containerColor = playContainer,
            contentColor = playContent,
            desc = playPauseDesc,
            size = ComponentConfig.MiniPlayerPlayButtonSize,
            shape = if (roundPlayButton) IconButtonDefaults.smallRoundShape
            else IconButtonDefaults.mediumSquareShape,
            // La misma forma pulsada en los dos casos, y es la de la talla del BOTÓN (Small,
            // `CornerSmall`): sigue siendo distinta de la de reposo en los dos modos —que es la
            // condición para que el morph exista, pasar la de reposo lo anula en silencio— y con el
            // squircle a 16 el recorrido 16→8 se lee aún mejor que el 12→8 del primer intento.
            pressedShape = IconButtonDefaults.smallPressedShape
        ) {
            AnimatedContent(
                targetState = isBuffering,
                transitionSpec = { fadeIn(bufferFadeIn) togetherWith fadeOut(bufferFadeOut) },
                label = "bufferingAnimation"
            ) { buffering: Boolean ->
                if (buffering) {
                    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "LoadingIndicator del MINI compuesto" } }
                    // LoadingIndicator expressive (morfea entre MaterialShapes), igual que
                    // el buffering del NowPlaying/Lyrics.
                    LoadingIndicator(
                        // Del tamaño del GLIFO al que sustituye, no del contenedor.
                        modifier = Modifier.size(ComponentConfig.MiniPlayerPlayIndicatorSize),
                        color = playContent
                    )
                } else {
                    AnimatedContent(
                        targetState = showAsPlaying,
                        transitionSpec = {
                            scaleIn(glyphEnterScale) togetherWith scaleOut(glyphExitScale)
                        },
                        label = "playPauseAnimation"
                    ) { playing: Boolean ->
                        if (playing)
                            MaterialSymbol(
                                "pause",
                                color = playContent,
                                size = ComponentConfig.MiniPlayerButtonIconSize,
                                fill = true
                            )
                        else
                            MaterialSymbol(
                                "play_arrow",
                                color = playContent,
                                size = ComponentConfig.MiniPlayerButtonIconSize,
                                fill = true
                            )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))

        // Botón Siguiente — un escalón POR DEBAJO del play (XSmall contra Small): el par se lee
        // como transporte y la TALLA dice cuál es la acción principal, no solo el color. Con los dos
        // en 48 la jerarquía la sostenía el color él solo, y son acciones de rango distinto.
        MiniTransportButton(
            onClick = onNextClick,
            containerColor = sideContainer,
            contentColor = sideContent,
            desc = nextDesc,
            size = ComponentConfig.MiniPlayerNextButtonSize,
            // Redondo: es la acción secundaria, y la forma es el segundo eje que la separa del play
            // (el primero es la talla). Su par también sale de la talla Small, que es la SUYA.
            shape = IconButtonDefaults.smallRoundShape,
            pressedShape = IconButtonDefaults.smallPressedShape
        ) {
            // Icono skip RELLENO (variante fill del símbolo, no el outline).
            MaterialSymbol(
                "skip_next",
                color = sideContent,
                size = ComponentConfig.MiniPlayerButtonIconSize,
                fill = true
            )
        }
    }
}

/**
 * Botón de transporte del MiniPlayer: `FilledIconButton` REAL de M3 Expressive, con el diámetro y la
 * forma que le pase el caller ([ComponentConfig.MiniPlayerPlayButtonSize] o
 * [ComponentConfig.MiniPlayerNextButtonSize]). El siguiente es SIEMPRE círculo y el play es
 * squircle o círculo según el ajuste de Apariencia: son las dos formas que M3 Expressive tabula
 * para un botón de icono (`ContainerShapeSquare` / `ContainerShapeRound`), así que ninguna de
 * las dos opciones inventa geometría — el squircle toma el radio de la talla Medium por el motivo
 * que explica el caller. Con el play en squircle la acción principal se distingue de
 * la secundaria por FORMA además de por talla y color; con los dos redondos esa jerarquía queda en
 * la talla y el color, que es lo que el ajuste deja elegir. Una forma geométrica estática no
 * contradice el descarte de la COOKIE (ver el KDoc de [ComponentConfig.MiniPlayerPlayButtonSize]):
 * aquélla se descartó por MOTION —una forma orgánica quieta al lado de la del NowPlaying, que gira,
 * prometía un movimiento que la píldora persistente no puede permitirse—, y ni el squircle ni el
 * círculo prometen nada. El color del contenedor puede venir animado (play/pausa con el acento
 * del álbum). Trae ripple/state-layer y semántica del componente.
 *
 * **El touch target lo repone ESTE composable, no el componente.** `FilledIconButton` entrega su
 * modifier al `Surface` sin pasar por `minimumInteractiveComponentSize` (ver `SurfaceIconButton` en
 * las fuentes de material3 1.5.0-alpha24), así que con contenedores por debajo de 48 el objetivo del
 * dedo encogería con el dibujo. `minimumInteractiveComponentSize()` va ANTES del `size`: expande el
 * área de entrada sin tocar lo que se pinta.
 *
 * **La forma PRESIONADA nunca puede ser la de reposo.** Hasta el 30 jul se pasaba
 * `pressedShape = CircleShape`, o sea la MISMA que en reposo: con las dos formas iguales no hay nada
 * que interpolar y el shape-morph de M3 Expressive quedaba anulado en silencio — el botón se
 * limitaba al ripple. Se notaba al lado del transporte del NowPlaying, cuyos botones sí se deforman
 * bajo el dedo, que es exactamente lo que reportó el usuario.
 *
 * Desde el 21 ago la pasa el CALLER en vez de heredarse del default. Hoy los dos piden
 * `smallPressedShape` (`CornerSmall`) y el default daría lo mismo
 * (`IconButtonDefaults.shapes()` sale de `SmallIconButtonTokens`), pero
 * heredarla solo funciona mientras los dos botones estén en esa talla: el spec tabula
 * `CornerMedium` para un Medium, y en la pasada en que el play midió 56 el default lo achataba como
 * si fuera de 40. Explícita, el tamaño y su forma al pulsar se mueven juntos.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniTransportButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    desc: String,
    size: Dp,
    shape: Shape,
    pressedShape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = shape, pressedShape = pressedShape),
        // El juego COMPLETO de roles: una llamada PARCIAL deja los estados deshabilitados saliendo
        // del esquema BASE (sin el color del álbum), y M3 les da defaults VISIBLES —`onSurface` al
        // 12 % de contenedor—, así que el fallo se ve pero no falla en compilación.
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = DISABLED_CONTAINER_ALPHA),
            disabledContentColor = contentColor.copy(alpha = DISABLED_CONTENT_ALPHA)
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .semantics { contentDescription = desc }
    ) {
        content()
    }
}

/**
 * Los roles del MiniPlayer que dependen de la paleta: el fondo de la píldora, relleno y glifo del
 * "siguiente", arco y riel. El texto de la píldora (`onSecondaryContainer`) y el play (`primary` +
 * `onPrimary`) son los mismos en todas y no están aquí.
 */
@Immutable
private class MiniPlayerRoles(
    val pill: Color,
    val nextContainer: Color,
    val nextContent: Color,
    val arc: Color,
    val track: Color
)

/**
 * **La tabla de roles por PALETA × TEMA**, elegida fila a fila por el usuario en el banco de roles
 * (13 sep 2026) y validada contra seis carátulas de su paleta y su tema. Es la fuente de verdad: si
 * una fila cambia en el banco, cambia aquí, y viceversa.
 *
 * Los defaults de [miniRoles] son lo que comparten la mayoría: píldora en `secondaryContainer`,
 * glifo del "siguiente" en `onSecondaryContainer` y arco en `primary`. Una fila sólo escribe lo que
 * se aparta.
 *
 * Fallos que el usuario APROBÓ sabiéndolo, para que nadie los "arregle" sin preguntar:
 * Vibrante · oscuro (1: con una carátula teal el "siguiente" queda a 5,5 puntos de tono de la
 * píldora) y Neutro · claro (2).
 */
@Composable
@ReadOnlyComposable
private fun miniPlayerRoles(style: PaletteStyle, isDark: Boolean): MiniPlayerRoles = when (style) {
    PaletteStyle.TonalSpot ->
        if (isDark) miniRoles(next = AppColors.primaryContainer, track = AppColors.inversePrimary)
        else miniRoles(next = AppColors.primaryFixedDim, track = AppColors.outlineVariant, arc = AppColors.onSecondaryContainer)
    PaletteStyle.Vibrant ->
        if (isDark) miniRoles(next = AppColors.onPrimaryContainer, track = AppColors.outline)
        else miniRoles(next = AppColors.onSecondary, track = AppColors.inverseOnSurface, arc = AppColors.onSecondaryContainer)
    // Arco en `onSecondaryContainer` en oscuro: sigue al play, que en estas dos paletas deja `primary`
    // (ver `playButtonRoles`).
    PaletteStyle.Content ->
        if (isDark) miniRoles(pill = AppColors.onSecondary, next = AppColors.secondaryContainer, track = AppColors.inversePrimary, arc = AppColors.onSecondaryContainer)
        else miniRoles(pill = AppColors.primaryFixed, next = AppColors.inversePrimary, track = AppColors.surfaceBright)
    PaletteStyle.Fidelity ->
        if (isDark) miniRoles(pill = AppColors.onSecondary, next = AppColors.secondaryContainer, track = AppColors.inversePrimary, arc = AppColors.onSecondaryContainer)
        else miniRoles(pill = AppColors.primaryFixed, next = AppColors.inversePrimary, track = AppColors.surfaceBright)
    PaletteStyle.Neutral ->
        if (isDark) miniRoles(next = AppColors.inversePrimary, track = AppColors.outline)
        else miniRoles(next = AppColors.outlineVariant, track = AppColors.outlineVariant)
    PaletteStyle.Expressive ->
        if (isDark) miniRoles(next = AppColors.onTertiaryContainer, track = AppColors.outline)
        else miniRoles(next = AppColors.tertiaryContainer, track = AppColors.inverseOnSurface)
    PaletteStyle.Rainbow ->
        if (isDark) miniRoles(next = AppColors.onSecondary, track = AppColors.inversePrimary)
        else miniRoles(next = AppColors.inversePrimary, track = AppColors.secondaryFixedDim)
    PaletteStyle.FruitSalad ->
        if (isDark) miniRoles(next = AppColors.inversePrimary, track = AppColors.outline)
        else miniRoles(next = AppColors.inversePrimary, track = AppColors.inversePrimary)
    // La que se aplica SOLA a una carátula acromática, sea cual sea el estilo de Ajustes.
    PaletteStyle.Monochrome ->
        if (isDark) miniRoles(next = AppColors.outline, track = AppColors.outline, nextContent = AppColors.inverseOnSurface)
        else miniRoles(next = AppColors.surfaceContainerLow, track = AppColors.outline)
}

@Composable
@ReadOnlyComposable
private fun miniRoles(
    next: Color,
    track: Color,
    pill: Color = AppColors.secondaryContainer,
    arc: Color = AppColors.primary,
    nextContent: Color = AppColors.onSecondaryContainer
) = MiniPlayerRoles(pill = pill, nextContainer = next, nextContent = nextContent, arc = arc, track = track)
