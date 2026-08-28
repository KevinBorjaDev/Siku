package com.qhana.siku.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale

// ============== CONFIGURACIÓN ==============

internal object ComponentConfig {
    // 20dp = tamaño de icono de list item del spec de M3, el MISMO con el que la cola dibuja su
    // indicador de descarga (QueueRowActionIconSize). Estuvo en 18 ("compacto de biblioteca") y el
    // mismo glifo `offline_pin` salía más pequeño en la lista que en la cola — dos tamaños para el
    // mismo indicador en dos pantallas.
    val StatusIconSize = 20.dp

    /**
     * Alto del header inmersivo de las pantallas de detalle (artista, álbum, género, lista) y
     * recorrido de scroll en el que el título viaja de ahí a la topbar.
     *
     * Viven juntos y compartidos porque son UNA decisión de diseño replicada en cuatro pantallas —
     * el patrón documentado en CLAUDE.md—, y estaban escritos a mano en tres de ellas. La cuarta
     * (género) sí los tenía nombrados, con un KDoc que decía "igual que en artista/álbum": una
     * relación afirmada en un comentario que nada obligaba a cumplir, que es como se desincronizan.
     *
     * El fade es MENOR que el alto a propósito: el título termina de pasar a la topbar antes de que
     * el header salga del todo, así que nunca hay un instante sin título en pantalla.
     *
     * **Los dos son el TOPE, no el valor efectivo** (27 ago 2026). Girado, el alto útil de un
     * teléfono ronda los 360dp, así que un header de 380 era la pantalla ENTERA: al abrir un
     * artista no se veía ni una canción ni la botonera de reproducción. Quien manda es
     * [detailHeaderMetrics], que los acota contra el alto de la ventana; estas dos constantes
     * siguen siendo lo que se ve en vertical.
     */
    val DetailHeaderHeight = 380.dp
    val DetailTitleFadeRange = 300.dp

    /**
     * Fracción MÁXIMA de la ventana que puede ocupar el header inmersivo.
     *
     * No es un número elegido: en vertical el header ya vale ~45 % de la pantalla
     * ([DetailHeaderHeight] contra los ~850dp de un teléfono), así que a la mitad este tope **no
     * toca nada de lo que hoy se ve** — existe solo para el caso en el que la ventana es más baja
     * que el header, o sea horizontal y multiventana, donde deja ~180dp y el contenido vuelve a
     * caber debajo.
     */
    const val DetailHeaderMaxFraction = 0.5f
    /**
     * Ancho MÁXIMO de una columna de contenido en una ventana ancha (horizontal, multiventana).
     *
     * No es un número nuevo: es el que la app ya acepta para sus hojas, porque `AppModalSheet`
     * hereda `BottomSheetDefaults.SheetMaxWidth` de M3 y con él las hojas quedan centradas en vez
     * de estirarse. Era el ÚNICO tope de ancho de toda la app, y llegaba gratis; el resto de las
     * pantallas estiraba sus filas a los ~873dp de un teléfono girado, dejando el título de un
     * ajuste a un lado y su interruptor a 840dp de distancia.
     *
     * Derivado y no copiado: si M3 mueve ese ancho, esto lo sigue, y las hojas y el resto del
     * contenido no se pueden separar en silencio.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    val ContentMaxWidth = BottomSheetDefaults.SheetMaxWidth

    /**
     * Lado MÍNIMO de una tarjeta de rejilla (álbumes, géneros) A PARTIR DE LA TERCERA COLUMNA.
     *
     * **No decide si hay dos columnas: eso es un SUELO** ([GridMinColumns]), y la diferencia es la
     * corrección del 27 ago 2026. Este valor salió de despejar el lado que dan dos columnas en un
     * teléfono de 393dp —(393 − 2×16 − 12)/2 ≈ 174—, o sea calibrado al ANCHO EXACTO de un
     * teléfono concreto y con margen CERO. En un teléfono de 386dp (densidad 3.31, medido en device
     * sobre una captura del usuario) la cuenta da 1.97 y `GridCells.Adaptive` caía a **una sola
     * columna**: álbumes y géneros a pantalla completa, uno por fila. Falló por el 1,5 %.
     *
     * La lección, que vale más que el número: **un umbral con margen cero no puede gobernar el caso
     * que nunca debe fallar.** "Dos columnas en un teléfono" no es algo que se espera que emerja de
     * un mínimo bien elegido — se garantiza, y el mínimo se queda para lo que sí es una decisión
     * blanda: cuándo aparece la tercera al ensanchar la ventana.
     */
    val GridTileMinWidth = 174.dp

    /**
     * Columnas que una rejilla de la biblioteca tiene SIEMPRE, quepan o no en el mínimo.
     *
     * Es exactamente el `GridCells.Fixed(2)` que estas rejillas tuvieron desde siempre, conservado
     * como piso: en cualquier teléfono en vertical el resultado es el de antes, píxel por píxel, y
     * lo adaptativo solo actúa hacia ARRIBA cuando la ventana da de sobra.
     */
    const val GridMinColumns = 2

    val SongItemCornerRadius = 8.dp
    val SongItemIconSize = 56.dp
    val SongItemIconCorner = 28.dp
    val ThumbnailSize = 156

    /**
     * Lado en píxeles al que se decodifica la carátula GRANDE del reproductor (`NowPlayingArtImage`),
     * y el MISMO al que `PlaybackViewModel.preloadVisualArt` la precalienta al cambiar de canción.
     *
     * Un solo número a propósito: la precarga y la petición del player pedían tamaños DISTINTOS
     * (900 y 800), así que en el mismo instante corrían DOS decodificaciones del mismo JPEG en
     * paralelo —una por petición, Coil no fusiona peticiones concurrentes— y ninguna servía a la
     * otra hasta terminar, justo mientras arranca el container transform. Con el mismo tamaño la
     * precarga es exactamente lo que el player pide.
     */
    val NowPlayingArtDecodePx = 800
    // --- Capa flotante del bottom (solo el MiniPlayer), specs floating toolbar M3 Expressive ---
    // ContainerHeight del floating toolbar del spec (FloatingToolbarTokens). Lo usa la barra de
    // acciones del NowPlaying, que DEBE forzarlo (la alpha18 no lo respeta sola). Vivía pegado a
    // MiniPlayerHeight por coincidencia de valor: son cosas distintas y ya divergen.
    val FloatingToolbarHeight = 64.dp
    // 80dp: subió de 72 para que el anillo de progreso ONDULADO que rodea la carátula tenga banda
    // donde respirar. Sigue leyéndose como barra y no como panel, y los botones de 48dp caben de sobra.
    val MiniPlayerHeight = 80.dp
    // --- Anillo ondulado de progreso alrededor de la carátula (ver MiniPlayer.kt) ---
    // La geometría se define de AFUERA hacia adentro para FIJAR el respiro con el contenedor:
    // padding al pill → tamaño del anillo → carátula. Así el padding superior/inferior queda EXACTO
    // sea cual sea la banda del anillo (antes se derivaba al revés —carátula → anillo— y el margen
    // al pill era lo que sobraba, distinto según el trazo/separación).
    // Respiro visible del anillo al contenedor (pill), arriba y abajo.
    val MiniPlayerRingContainerPadding = 8.dp
    // Lado del anillo (y de su Box): el alto menos el padding al contenedor a cada lado.
    val MiniPlayerRingSize = MiniPlayerHeight - MiniPlayerRingContainerPadding * 2
    // Trazo del anillo y separación anillo→carátula = la "banda" del anillo. Como el tamaño del
    // anillo y el padding al pill están FIJOS, subir el gap encoge la carátula (más aire entre
    // portada y onda a cambio de portada más chica).
    val MiniPlayerRingStroke = 4.dp
    val MiniPlayerRingGap = 10.dp
    // Carátula: el anillo menos su banda (trazo + separación) a cada lado.
    val MiniPlayerArtSize = MiniPlayerRingSize - (MiniPlayerRingStroke + MiniPlayerRingGap) * 2
    /**
     * Diámetro de los controles de transporte: el play **56** y el siguiente un icon button `Small`
     * (**40**). Estuvieron los dos en 48 hasta el 21 ago 2026, lo que los dejaba en el mismo rango
     * visual pese a no ser la misma acción — la jerarquía la sostenía el COLOR él solo.
     *
     * **Los dos pares del spec se probaron el 21 ago y los DESCARTÓ el ojo en device**:
     *  - `Small`/`XSmall` (40/32): un contenedor de 32 se falla al pulsar aunque su área táctil sean
     *    48 —el dedo apunta a lo que VE— y el play a 40 perdía presencia contra el anillo de la
     *    carátula, que mide 64.
     *  - `Medium`/`Small` (56/40): el play dominaba la barra. En 80dp de alto, un círculo de 56 deja
     *    12 arriba y abajo y pesa más que la propia portada con su anillo, que es lo que la píldora
     *    tiene que enseñar.
     *
     * Por eso el play se queda en **48, que no es una talla tabulada** y aun así no es un número
     * arbitrario: es su propio mínimo táctil, o sea el botón dibujado exactamente sobre el área que
     * responde al dedo. El spec da el punto de partida; el que decide es el device.
     *
     * **También se probó y se revirtió darle al play una COOKIE de 4 lados** (que se relajaba a
     * círculo bajo el dedo). Se descartó por MOTION y no por forma: el play del NowPlaying ya se
     * mueve —su cookie gira mientras suena— y este no puede hacerlo, porque la píldora es
     * persistente y un giro continuo sería un frame por vsync durante horas (ver la sección Motion);
     * una forma orgánica que se queda quieta al lado de la que sí se mueve no aporta, solo promete
     * algo que no cumple. **De ahí que el play midiera 56 un rato**: no era una talla elegida, era
     * la compensación de que una cookie pesa **0,799 de su caja** (la media entre el radio de su
     * punta y el de su valle, el mismo criterio del `CookieVisualRatio` que hubo en el NowPlaying),
     * así que a 48 se veía pequeña. Sin la cookie esa compensación sobra y el 56 vuelve a ser el
     * círculo que dominaba.
     *
     * **El área TÁCTIL es 48 y hay que reponerla a mano** cuando el contenedor queda por debajo:
     * `FilledIconButton` pasa su modifier directo al `Surface` (ver `SurfaceIconButton` en las
     * fuentes de material3 1.5.0-alpha24) y **NO aplica `minimumInteractiveComponentSize`**, así que
     * un `.size(40.dp)` a secas encogería el objetivo real del dedo. Lo repone [MiniTransportButton].
     * En la píldora se toca en movimiento y sin mirar, que es el mismo criterio de 48 que justificó
     * quitar el botón "anterior".
     *
     * **Ojo con el hueco que eso añade**: `minimumInteractiveComponentSize` agranda el NODO, no solo
     * la zona sensible, así que un botón dibujado a N < 48 aporta `(48 − N) / 2` de aire a cada lado
     * ADEMÁS del [FloatingBarItemGap]. Aquí solo se expande el siguiente (4dp por lado), de modo
     * que entre los dos círculos quedan 8dp. Con el par 40/32 eran 16 y se
     * leían como dos botones sueltos en vez de un transporte.
     */
    val MiniPlayerPlayButtonSize = 48.dp

    val MiniPlayerNextButtonSize = 40.dp

    /**
     * Glifo de los dos: `MediumIconButtonTokens.IconSize` y `SmallIconButtonTokens.IconSize` valen
     * lo MISMO (24) — entre esas dos tallas el spec cambia el contenedor y el aire interior, no el
     * dibujo. Es una sola constante y no dos porque son el mismo número por definición, no por
     * casualidad.
     */
    val MiniPlayerButtonIconSize = 24.sp

    /**
     * El indicador de buffering ocupa el sitio DEL GLIFO del play, así que va a su medida. En dp y
     * no en sp porque no es tipografía: un spinner no debe crecer con el ajuste de tamaño de texto
     * del sistema, o se saldría de su contenedor de 40.
     */
    val MiniPlayerPlayIndicatorSize = 24.dp
    // Aire A AMBOS LADOS del bloque de texto (carátula→texto y texto→transporte): el gap
    // leading→texto de los ítems de lista, para que el mini se lea como una fila más de la
    // biblioteca y no como otro componente. Se usa también a la derecha para que el título no
    // llegue pegado al play — el gap de los botones (4dp) es demasiado poco ahí. El texto NO lleva
    // ancho propio: la Column va con weight(1f) y se come todo lo que sobre entre ambos gaps.
    val MiniPlayerTextGap = 12.dp
    // Separación entre las dos líneas del bloque de texto (título / artista).
    val MiniPlayerTextLineGap = 2.dp
    // Padding interno del container y gap entre sus botones de acción.
    val FloatingBarInnerPadding = 8.dp
    val FloatingBarItemGap = 4.dp
    // Márgenes de la capa respecto a la pantalla: 16dp sobre la navbar del sistema y a los lados.
    val FloatingBarBottomMargin = 16.dp
    val FloatingBarSideMargin = 16.dp
    // Espacio total que la capa reserva bajo las listas (sobre la navbar): margen + alto + colchón.
    // Se usa como contentPadding inferior para que el último ítem no quede tapado. DERIVADO del
    // alto real de la barra: si el MiniPlayer crece y esto no, la capa flotante se come el
    // último ítem de cada lista.
    val FloatingBarListInset = FloatingBarBottomMargin + MiniPlayerHeight + 16.dp
    // Con la barra de pestañas puesta el mini deja de apoyarse en la navbar del SISTEMA y pasa a
    // apoyarse en ELLA — pero su margen no cambia: **el mini flota a `FloatingBarBottomMargin` de
    // lo que tenga debajo, sea el borde de la pantalla o una barra**. Por eso aquí no hay una
    // segunda constante: hubo una de 8dp durante una hora del 22 ago 2026 y en device se veía
    // pegado, que es lo que pasa cuando una pieza flotante se separa de su suelo menos de lo que se
    // separaba del borde. Lo que sube la píldora es entonces el alto exacto de la barra.

    val SearchBarHeight = 56.dp

    // --- Barra de progreso del NowPlaying (ProgressSlider) ---
    // El GROSOR es ajustable (Ajustes → Apariencia) y de él sale toda la geometría que escala con
    // él: ver [ProgressMetrics]. Aquí quedan el default y lo que NO depende del grosor.
    //
    // El alto lo comparten los DOS modos (píldora plana y onda): si divergen, alternar el
    // ajuste de Apariencia movería el layout de todo el bloque de controles.
    val ProgressTrackHeight = 12.dp
    // Extremos del ajuste de grosor. Abajo, 6dp deja el trazo de la onda en 2dp (el mínimo que se
    // sigue leyendo a densidad baja).
    val ProgressTrackHeightMin = 6.dp

    /**
     * Tope del grosor en modo ONDA. La onda no es un riel más grueso sino una FORMA, y todo lo que
     * la dibuja escala con el alto: a 24dp el trazo ya mide 8dp, el DOBLE del
     * `LinearProgressIndicatorTokens.ActiveThickness` (4dp) con el que M3 dibuja cualquier barra
     * ondulada, y la amplitud 6 contra los 3 del token. Más allá deja de leerse como onda y pasa a
     * ser una serpiente.
     *
     * Los tokens no declaran ningún máximo (el `stroke` del `LinearWavyProgressIndicator` es un
     * parámetro libre y no hay tamaños S/M/L para este componente en material3 1.5.0-alpha24/25),
     * así que este número es perceptual y se deja anclado a "el doble del trazo del spec".
     */
    val ProgressTrackHeightMaxWavy = 24.dp

    /**
     * Tope del grosor en modo PLANO, donde no hay forma que se deforme: la píldora sigue siendo una
     * píldora a cualquier alto y el único límite real es cuánto sitio le quita a la carátula.
     *
     * 56dp = el track del tamaño **L** de la tabla del slider Expressive (ver [SliderSizeTable]). El
     * spec llega a 96 (XL), pero ese es el tamaño de un slider que ES la pantalla; aquí la barra
     * comparte el reproductor con la carátula, los tiempos y el transporte. Subirlo a 96 es cambiar
     * este número y nada más: la geometría ya sale de la tabla y el rango la cubre entera.
     *
     * Con la altura del palo tomada del spec (que a 56 son 68dp, no 84) el bloque entero mide MENOS
     * que lo que medía el tope anterior de 44 con la fórmula vieja de asomo fijo (72dp).
     */
    val ProgressTrackHeightMaxFlat = 56.dp
    // Paso del ajuste. 2dp: por debajo, dos valores contiguos no se distinguen en pantalla y el
    // slider tendría paradas que no cambian nada.
    val ProgressTrackHeightStep = 2.dp
    // Distancia entre crestas. Más corta = onda más "nerviosa"; más larga = casi recta. Subirla NO
    // acelera la onda: la velocidad de desplazamiento es su propio token (ProgressWaveSpeed) y el
    // periodo se deriva de los dos. NO escala con el grosor: es una longitud HORIZONTAL, y atarla
    // al grosor cambiaría el ritmo de la onda al engordarla.
    val ProgressWaveLength = 30.dp
    // Velocidad a la que se desplaza la onda, en dp por segundo. Fijar ESTO en vez del periodo del
    // ciclo es lo que permite retocar la longitud de onda sin tocar de paso el ritmo: con el periodo
    // fijo en 1 s, ensanchar la onda le hacía recorrer más distancia en el mismo tiempo.
    val ProgressWaveSpeed = 22.dp
    // Handle (el "palito" vertical del slider Expressive), COMPARTIDO por los dos modos y con el
    // MISMO comportamiento en ambos: permanente por defecto —como las barras de progreso del spec—
    // y solo-al-arrastrar si el usuario lo apaga en Ajustes → Apariencia → Barra de progreso.
    // El ancho es el del spec (4dp) y NO escala con el grosor: es el objeto de M3, no una parte del
    // riel. Su alto sí (ver [ProgressMetrics.handleHeight]).
    val ProgressHandleWidth = 4.dp
    // Ancho del palo MIENTRAS el dedo está en la barra. Es `SliderTokens.PressedHandleWidth` (2dp),
    // que resulta ser exactamente la mitad del ancho en reposo — y así lo implementa el `Slider` de
    // material3, que no lee el token sino que pinta `thumbSize.width / 2` mientras haya press, drag
    // o foco. El palo se AFINA al tocarlo, que es la forma que tiene un objeto de 4dp de acusar
    // recibo del dedo sin moverse ni cambiar de color.
    //
    // OJO: solo cambia el DIBUJO del palo. El hueco que el fill y el riel le dejan sigue midiendo
    // [ProgressHandleWidth], igual que en el Slider oficial —donde el hueco sale del contenedor de
    // 4dp y no del rectángulo interior que se encoge—: si el hueco siguiera al ancho animado, tocar
    // la barra movería el borde del progreso, que es lo único que el usuario está mirando.
    val ProgressHandlePressedWidth = 2.dp
    // Hueco entre el progreso y lo que venga después (el palo al buscar, el riel apagado siempre).
    // ES lo que hace legible el handle, y por eso el palo puede ser del color del ACENTO en vez de
    // un blanco/negro ajeno a la paleta: se separa por geometría (este hueco + su altura), no por
    // contraste de color. Mismo valor en los dos modos, para que se lean idénticos.
    //
    // 6dp = `SliderTokens.ActiveHandleLeadingSpace`, el hueco que el spec da a un SLIDER. Estuvo en
    // 4 (el `TrackActiveSpace` del progress indicator, que es otro componente: sin thumb y más
    // fino) y ahí el aire se leía corto.
    val ProgressHandleGap = 6.dp
    // Radio del extremo INTERIOR de cada tramo: el borde del fill que da al hueco y el arranque del
    // riel. `SliderTokens`/`TrackInsideCornerSize` de material3. No es 0 (un corte recto) ni el
    // radio completo: con la punta redonda entera el fill deja "hombros" de riel asomando a los
    // lados del palo —6dp de radio contra 4 de palo—, y ese fue el motivo de cortarlo recto; 2dp es
    // lo que hace el Slider oficial y no produce hombros.
    val ProgressTrackInsideCorner = 2.dp
    // Cuánto se mete el stop indicator desde el borde derecho, COMO MÁXIMO. Es el
    // `StopIndicatorTrailingSpace` de material3 (6dp, el mismo valor en SliderTokens), y existe por
    // el grosor ajustable: sin tope, el inset es media altura del track —lo que hace el `Slider`,
    // cuyo track es fijo— y con la barra en 24dp el punto se hundiría 12dp adentro. Con 12dp de
    // grosor el tope no muerde y el resultado es idéntico al de antes.
    val ProgressStopIndicatorTrailingSpace = 6.dp

    /**
     * Lado del **stop indicator** (el punto del final de la barra), token `StopSize` de
     * `LinearProgressIndicatorTokens`.
     *
     * **Es un tamaño FIJO acotado al track, no una proporción**, y vale para los DOS modos. Las dos
     * implementaciones de material3 hacen lo mismo con el track de cada una:
     * `ProgressIndicator.drawStopIndicator` calcula `min(stopSize, size.height)` ("Stop can't be
     * bigger than track") y la del ondulado, `LinearWavyProgressModifiers.drawStopIndicator`,
     * `min(trackStroke.width, maxStopIndicatorSize)` — o sea el TRAZO, que es su track.
     *
     * Aquí se derivaba del grosor (`alto / 3`) en la onda, que con los 12 dp del diseño original
     * daba estos mismos 4 dp por casualidad y se separaba en los dos extremos del ajuste de
     * Apariencia: a 24 dp pintaba un punto de 8 —el doble de lo que M3 pinta en cualquier barra— y a
     * 6 dp uno de 2, que se pierde. El punto es un REMATE del riel y su tamaño es del spec, no del
     * grosor que elija el usuario.
     */
    val ProgressStopIndicatorSize = 4.dp
    // Alto MÍNIMO de la zona de gesto de la barra (el bloque que responde al arrastre y al toque).
    // Es MAYOR que el track por defecto: el objetivo táctil de un control no es su dibujo. Con un
    // grosor mayor manda el track (ver [ProgressMetrics.touchHeight]).
    val ProgressTouchHeight = 24.dp
    // El ALTO del palo ya no es "el track más un asomo fijo": sale de [SliderSizeTable], porque el
    // asomo del spec NO es constante — decrece según el slider engorda (14, 10, 6, 6, 6 dp de XS a
    // XL). Ver `progressHandleHeightFor`.
    // VALUE INDICATOR (la etiqueta con el tiempo que flota sobre el handle al arrastrar).
    //
    // Es una PASTILLA, no el pin con punta que había antes: el pin —un cuadrado rotado 45° con tres
    // esquinas redondas— es el value indicator del Material viejo; Expressive lo sustituyó por una
    // etiqueta flotante de esquina completa. Compose todavía no trae el componente (en material3
    // 1.5.0-alpha24/25 `ValueIndicator` existe SOLO como tokens, no hay composable), así que lo que
    // se sigue son los cuatro tokens que sí están: `ValueIndicatorContainerColor` = inverseSurface,
    // `ValueIndicatorLabelTextColor` = inverseOnSurface, `ValueIndicatorLabelTextFont` = labelLarge y
    // `ValueIndicatorActiveBottomSpace` = 12dp. El alto y el padding NO están tokenizados en esta
    // versión y son nuestros, ver abajo.
    //
    // El cambio de color no es cosmético: la etiqueta era del color del ACENTO con el texto resuelto
    // por `maxContrastOn`, o sea el único sitio de la barra que seguía teniendo que pelear el
    // contraste contra un acento arbitrario (el mismo problema que ya obligó a separar el handle por
    // geometría en vez de por color). El par inverseSurface/inverseOnSurface lo resuelve el sistema y
    // vale para cualquier carátula.

    /**
     * Aire entre el borde superior del HANDLE y el borde inferior de la etiqueta.
     * `SliderTokens.ValueIndicatorActiveBottomSpace`. Se mide contra el palo, que es la pieza activa
     * más alta: contra el riel, el palo —que asoma por encima de él— la atravesaría.
     */
    val ProgressLabelBottomSpace = 12.dp

    /**
     * Alto de la pastilla: **44dp, el "label container height" del spec** (es el mismo en los cinco
     * tamaños del slider, ver [SliderSizeTable]). Estuvo en 32 —derivado a mano de `labelLarge`, 14sp
     * con 20 de interlineado más el padding— mientras la tabla no estaba a mano, y se quedaba corto.
     *
     * Es un alto MÍNIMO y a la vez la SEMILLA de la medida real: con la fuente del sistema ampliada
     * la pastilla crece y la posición usa el alto medido, no éste. Que exista un número fijo es lo
     * que permite colocarla desde el primer frame sin esperar a medir, y lo que sustituyó al offset
     * absoluto de 52dp que no seguía al grosor ajustable (a 24dp el palo ya mide 52 y la etiqueta le
     * caía encima).
     */
    val ProgressLabelHeight = 44.dp

    /**
     * Ancho mínimo de la pastilla: **48dp, el "label container width" del spec**. El ancho real lo
     * pone el texto (de "0:07" a "1:23:45"); esto es el suelo, y sin él un tiempo corto degeneraba
     * la pastilla en un círculo aplastado. Antes ese suelo era el propio alto, que es lo que se hace
     * cuando no se tiene la tabla delante.
     */
    val ProgressLabelMinWidth = 48.dp
    val ProgressLabelPaddingHorizontal = 12.dp
    val ProgressLabelPaddingVertical = 6.dp
}

/**
 * Geometría de la barra de progreso del NowPlaying DERIVADA del grosor elegido en Ajustes →
 * Apariencia. Existe para que el ajuste sea un solo número: todo lo que tiene que crecer con la
 * barra sale de proporciones fijas sobre él, en vez de ser media docena de tokens que el usuario
 * tendría que mantener coherentes a mano (o que quedarían clavados en su valor de 12dp y
 * desproporcionados en cuanto la barra engorda).
 *
 * Las proporciones son las del diseño con 12dp: trazo 4, amplitud 3. Lo que el spec TABULA no sale
 * de ninguna proporción sino de [SliderSizeTable]: el alto del palo (44dp con el riel por defecto) y
 * el radio de esquina del track. El stop indicator es un tamaño fijo del spec, acotado al track.
 */
internal data class ProgressMetrics(
    /** Alto de la píldora plana / alto útil de la onda. Es el valor que elige el usuario. */
    val trackHeight: Dp,
    /** Grosor del trazo de la onda; el mismo que el diámetro del stop indicator. */
    val waveStroke: Dp,
    /** Desviación máxima de la onda respecto al centro. */
    val waveAmplitude: Dp,
    /**
     * Diámetro del stop indicator del final del riel. FIJO
     * ([ComponentConfig.ProgressStopIndicatorSize]) y solo acotado por el track del modo, que es lo
     * que hacen las dos implementaciones de material3: el alto de la píldora en el plano, el TRAZO
     * en la onda. Por eso [progressMetricsFor] necesita saber el modo — con la fórmula del plano, un
     * punto de 4 dp dentro de un trazo de 8 quedaría escondido, y con la de la onda a secas el punto
     * de la píldora crecía con el grosor.
     */
    val stopIndicator: Dp,
    /**
     * Radio de esquina del track PLANO. **No es media altura**: el spec lo tabula aparte (8, 8, 12,
     * 16, 28 dp de XS a XL, ver [SliderSizeTable]), así que solo el tamaño más fino es una píldora
     * perfecta y a partir de ahí la barra es un rectángulo cada vez más redondeado. Dibujarlo
     * siempre como píldora convertía una barra gruesa en una cápsula. La onda no lo usa.
     */
    val trackCorner: Dp,
    /**
     * Alto del palo del handle, TABULADO por el spec (44, 44, 52, 68, 108 dp de XS a XL, ver
     * [SliderSizeTable]) e interpolado para los grosores intermedios. Antes era `track + 28`, o sea
     * el asomo de XS aplicado a cualquier tamaño: correcto en los finos y falso en cuanto la barra
     * crece —a 40 dp de track el spec pone un palo de 52 y esa fórmula pedía 68—.
     */
    val handleHeight: Dp,
    /**
     * Alto que OCUPA la barra: nunca menor que el mínimo de gesto ni que el palo **cuando el palo
     * está puesto**, que es entonces la pieza más alta que se dibuja. Reservarle su sitio es lo que hace el `Slider` de M3 —su componente
     * mide 48dp y contiene el thumb de 44—, y aquí es obligatorio desde que el palo tiene la altura
     * del spec: con la caja pegada al mínimo de gesto se salía por debajo y a mitad de canción se
     * montaba sobre el chip de formato.
     */
    val touchHeight: Dp
)

/**
 * Grosor MÁXIMO que admite cada modo de la barra. La onda tiene su propio tope porque es una FORMA
 * que se deforma al engordar, mientras que la píldora plana solo se hace más alta: ver
 * [ComponentConfig.ProgressTrackHeightMaxWavy] y [ComponentConfig.ProgressTrackHeightMaxFlat].
 *
 * Vive aquí y no en la pantalla de Ajustes porque lo necesitan los DOS lados: el slider, para saber
 * hasta dónde llega, y [progressMetricsFor], para que un valor guardado con el otro modo no pueda
 * dibujar una onda de 56dp.
 */
internal fun progressTrackHeightMax(wavy: Boolean): Dp =
    if (wavy) ComponentConfig.ProgressTrackHeightMaxWavy else ComponentConfig.ProgressTrackHeightMaxFlat

/**
 * Construye la geometría de la barra para un [trackHeight] dado, acotándolo al rango del ajuste
 * (una preferencia vieja o corrupta no debe poder dibujar una barra de 0dp ni de 200).
 *
 * El modo entra como parámetro por DOS motivos, los dos load-bearing: el tope del grosor es distinto
 * (ver [progressTrackHeightMax]) y el stop indicator se acota contra el track de cada modo (ver
 * [ProgressMetrics.stopIndicator]).
 *
 * **Lo que escala con el grosor sale de DOS sitios distintos, y no es lo mismo**: lo que es NUESTRO
 * (trazo y amplitud de la onda, que M3 no tabula para un indicador de grosor variable) se deriva por
 * proporción, y lo que el spec TABULA (alto del palo, radio del track) se lee de [SliderSizeTable].
 * Derivar por proporción algo que el spec tabula fue exactamente el error del asomo fijo.
 *
 * Amplitud = alto/4 y trazo = alto/3, así que las crestas caben siempre dentro del contenedor:
 * el margen disponible es (alto − trazo)/2 = alto/3, mayor que la amplitud para cualquier alto.
 */
internal fun progressMetricsFor(
    trackHeight: Dp,
    wavy: Boolean,
    /**
     * El palo se queda PUESTO (Ajustes → Apariencia). Solo gobierna [ProgressMetrics.touchHeight]:
     * ver ahí por qué reservarle su alto cuando no está deja aire muerto.
     */
    persistentHandle: Boolean
): ProgressMetrics {
    val height = trackHeight.coerceIn(
        ComponentConfig.ProgressTrackHeightMin,
        progressTrackHeightMax(wavy)
    )
    val stroke = height / WAVE_STROKE_DIVISOR
    return ProgressMetrics(
        trackHeight = height,
        waveStroke = stroke,
        waveAmplitude = height / WAVE_AMPLITUDE_DIVISOR,
        // Tamaño del spec acotado al TRACK DE ESTE MODO, que es lo que hacen las dos
        // implementaciones de material3: ver [ComponentConfig.ProgressStopIndicatorSize].
        stopIndicator = minOf(
            ComponentConfig.ProgressStopIndicatorSize,
            if (wavy) stroke else height
        ),
        trackCorner = SliderSizeTable.trackCornerFor(height),
        handleHeight = SliderSizeTable.handleHeightFor(height),
        // La caja reserva sitio para **lo que se va a DIBUJAR**, y con el palo apagado eso es el
        // riel y nada más. Reservarle sus 44dp igualmente dejaba (44 − 12) / 2 = **16dp de aire
        // muerto por arriba y por abajo** con el grosor por defecto: el hueco a los chips de tiempo
        // se leía como 24dp cuando la escala pide 8 (medido el 27 ago 2026 sobre una captura, 35dp
        // de borde a borde contando los 12 que aporta el objetivo táctil del chip de formato).
        // Con el palo puesto ese espacio no está vacío —lo llena él—, que es justo por lo que el
        // fallo solo se ve con el ajuste apagado.
        //
        // **El suelo de gesto NO se toca**: la zona táctil de un control no es su dibujo, así que
        // con el palo apagado la caja no baja de [ComponentConfig.ProgressTouchHeight].
        //
        // Que el palo siga apareciendo al ARRASTRAR y se salga de la caja es deliberado y está
        // comprobado que no choca: asoma (44 − 24) / 2 = 10dp por lado y tiene 20 de aire a cada
        // uno —`SectionGap` arriba, `ItemGap` más el objetivo táctil del chip de formato abajo—. La
        // alternativa, crecer la caja al tocarla, movería los chips de sitio con el dedo encima.
        touchHeight = maxOf(
            if (persistentHandle) SliderSizeTable.handleHeightFor(height) else height,
            ComponentConfig.ProgressTouchHeight
        )
    )
}

/**
 * **La tabla de tamaños del `Slider` de M3 Expressive** (XS, S, M, L, XL), que es lo que gobierna la
 * geometría de la barra que no es nuestra.
 *
 * | | XS | S | M | L | XL |
 * |---|---|---|---|---|---|
 * | Track height | 16 | 24 | 40 | 56 | 96 |
 * | Handle height | 44 | 44 | 52 | 68 | 108 |
 * | Track shape (radio) | 8 | 8 | 12 | 16 | 28 |
 *
 * (El handle width es 4dp en los cinco, de ahí que [ComponentConfig.ProgressHandleWidth] no escale.)
 *
 * **NO está en material3**: `SliderTokens` de 1.5.0-alpha24/25 trae un solo juego de valores —el de
 * XS: track 16, handle 44— y ni tokens ni API por tamaño, así que la tabla se transcribe del spec.
 * Si una versión futura la publica, esto se sustituye por sus tokens.
 *
 * Nuestro grosor es CONTINUO (6..56 en pasos de 2), así que los valores se **interpolan** entre los
 * cinco puntos. Las dos lecciones que la tabla dejó, y que ninguna proporción da:
 *  - el **asomo del palo DECRECE** (14, 10, 6, 6, 6 dp): un palo que asome 14 sobre un track de 56
 *    sería un poste. Por eso se interpola el ALTO tabulado en vez de sumar un asomo.
 *  - el **track deja de ser píldora**: solo XS tiene radio = media altura. A 56 el spec pide 16 de
 *    radio, no 28.
 */
internal object SliderSizeTable {
    /** Track heights de los cinco tamaños, en dp. Es el eje de las dos interpolaciones. */
    private val trackHeights = floatArrayOf(16f, 24f, 40f, 56f, 96f)

    private val handleHeights = floatArrayOf(44f, 44f, 52f, 68f, 108f)

    private val trackCorners = floatArrayOf(8f, 8f, 12f, 16f, 28f)

    /** Alto del palo para un grosor dado. Por debajo de XS se queda en los 44dp del spec. */
    fun handleHeightFor(trackHeight: Dp): Dp = interpolate(trackHeight, handleHeights).dp

    /**
     * Radio de esquina del track plano. Por debajo de XS se extiende la recta hasta el origen, o sea
     * media altura: una barra fina sigue siendo una píldora perfecta y el valor EMPALMA con el del
     * spec en 16dp (8 = 16/2), así que no hay salto en la frontera.
     */
    fun trackCornerFor(trackHeight: Dp): Dp =
        if (trackHeight.value <= trackHeights[0]) trackHeight / 2f
        else interpolate(trackHeight, trackCorners).dp

    /** Interpolación lineal por tramos sobre [trackHeights]; fuera de rango, el extremo. */
    private fun interpolate(trackHeight: Dp, values: FloatArray): Float {
        val h = trackHeight.value
        if (h <= trackHeights.first()) return values.first()
        if (h >= trackHeights.last()) return values.last()
        for (i in 0 until trackHeights.size - 1) {
            val lo = trackHeights[i]
            val hi = trackHeights[i + 1]
            if (h <= hi) {
                val t = (h - lo) / (hi - lo)
                return values[i] + (values[i + 1] - values[i]) * t
            }
        }
        return values.last()
    }
}

/** Trazo de la onda = un tercio del alto (4dp sobre los 12 del diseño original). */
private const val WAVE_STROKE_DIVISOR = 3f

/** Amplitud de la onda = un cuarto del alto (3dp sobre los 12 del diseño original). */
private const val WAVE_AMPLITUDE_DIVISOR = 4f

// ============== EXTENSIONES DE COLOR ==============
// Movidas a ColorExtensions.kt para mejor separación de responsabilidades

// ============== UTILIDADES ==============

/**
 * Contenido que debe verse ENCIMA de un shared element durante la transición de navegación
 * (título viajero, topbar pineada de los detalles): un shared element vuela en el OVERLAY
 * del [SharedTransitionScope], que dibuja sobre la capa normal — sin esto, el contenido
 * queda tapado por la imagen voladora y "aparece de golpe" cuando la transición termina.
 * Lo eleva al mismo overlay (por encima, zIndex 1) y le da entrada/salida con fade.
 * No-op si no hay scopes (pantalla montada sin transición compartida).
 *
 * La elevación se ata a la transición de ESTA pantalla, no a la del scope: el
 * [SharedTransitionScope] es ÚNICO para toda la app (MainActivity), así que el default
 * `renderInOverlay = { isTransitionActive }` también se enciende cuando la carátula morfa de la
 * píldora al NowPlaying — y entonces el título del detalle se dibujaba en el overlay, es decir
 * SOBRE el player que estaba subiendo, quedándose flotando hasta que la animación terminaba.
 *
 * **Solo eleva si la cabecera realmente MORFA** ([headerState]`.isMatchFound`). Elevar reparenta el
 * contenido al overlay de la raíz, que NO recibe el slide de la pantalla entrante: queda PINCHADO en
 * su posición final. Eso es correcto cuando hay carátula voladora (venís de un tile/lista): la motion
 * focal es el morph de la portada y el título sentado en su destino acompaña. Pero **entrando al
 * detalle DESDE el player NO hay tile de origen**, así que la cabecera no morfa y no hay match — si
 * igual se elevara, el título grande flotaría semitransparente y clavado sobre la pantalla que
 * desliza, detached (el bug). Sin match no se eleva: el título se queda como hijo de la pantalla y
 * entra CON ella (el slide lo arrastra, sus anclas dan el offset relativo correcto). `?: true`
 * conserva el comportamiento viejo para cualquier caller que no pase el estado.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun overSharedElementsModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    headerState: SharedTransitionScope.SharedContentState? = null
): Modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
    with(sharedTransitionScope) {
        Modifier
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    val transition = animatedVisibilityScope.transition
                    (transition.currentState != transition.targetState) &&
                        (headerState?.isMatchFound ?: true)
                },
                zIndexInOverlay = 1f
            )
            .then(with(animatedVisibilityScope) {
                Modifier.animateEnterExit(
                    // Specs de EFFECTS del tema, NO los defaults de compose (un spring rápido): así el
                    // título se funde AL RITMO de la transición de pantalla y del morph del shared
                    // element (los mismos tweens que `appNavForwardEnter`/`Exit`), en vez de "popear"
                    // antes de tiempo. El spring por defecto llegaba a alpha alto tan rápido que
                    // destapaba el primer frame —cuando las anclas del título viajero aún valen
                    // `Offset.Zero` y salta a su sitio—; con el tween el título está casi invisible
                    // esos frames y el salto no se ve.
                    enter = fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)),
                    exit = fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
                )
            })
    }
} else Modifier

/**
 * `m:ss` para una duración en milisegundos. ÚNICA implementación: `Song.toUiModel` tenía una copia
 * literal (mismos `/ 1000` y `/ 60`, mismo formato) que no compartía nada con ésta.
 *
 * Las conversiones van por `TimeUnit` y no por divisiones a mano: así no queda ningún factor suelto
 * que haya que reconocer de memoria, y la unidad de cada paso se lee en la propia llamada.
 */
fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
    val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Tiempo escuchado ACUMULADO, para las estadísticas: "12 h 30 min" o "45 min".
 *
 * No sirve [formatTime] aquí: un total de escucha llega a decenas de horas y `m:ss` las escribiría
 * como "2400:00". Y al revés, los segundos sobran — nadie lee un acumulado al segundo.
 *
 * Redondea a minuto hacia abajo, así que por debajo de un minuto devuelve "0 min" en lugar de
 * fingir precisión que no aporta.
 */
fun formatListeningTime(
    millis: Long,
    hoursAndMinutes: (hours: Long, minutes: Long) -> String,
    minutesOnly: (minutes: Long) -> String
): String {
    val safeMillis = millis.coerceAtLeast(0L)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(safeMillis)
    val hours = TimeUnit.MINUTES.toHours(totalMinutes)
    val minutes = totalMinutes - TimeUnit.HOURS.toMinutes(hours)
    return if (hours > 0) hoursAndMinutes(hours, minutes) else minutesOnly(totalMinutes)
}


/**
 * La **única** petición de la carátula grande del reproductor: la que hace el `AsyncImage` del
 * NowPlaying y la que usa `PlaybackViewModel.preloadVisualArt` para precalentarla al cambiar de
 * canción.
 *
 * La clave de caché de Coil es `data + size + SCALE + transformaciones`, así que **igualar el
 * tamaño no basta**: hasta el 19 ago 2026 las dos peticiones pedían 800 px pero con escalas
 * distintas —el precalentamiento sin `scale` (el default de Coil es `Scale.FIT`) y el `AsyncImage`
 * con `ContentScale.Crop`, que le pasa `Scale.FILL`—. Sobre una portada que no es exactamente
 * cuadrada eso da DOS destinos distintos y por tanto dos entradas y dos decodificaciones del mismo
 * JPEG. Medido en un trace de Perfetto (release, Xiaomi klimt): el mismo archivo decodificado a
 * `794×800` (35 ms), `800×806` (62 ms) y otra vez `794×800` (33 ms) — 130 ms de CPU y tres bitmaps
 * para una sola imagen, justo mientras corre el container transform y con un GC cada ~600 ms.
 *
 * O sea que el precalentamiento no es que no ahorrara: **duplicaba** el trabajo que decía evitar.
 * Es la segunda vuelta del mismo error —la primera fue pedir 900 y 800, ver [ComponentConfig.NowPlayingArtDecodePx]—
 * y por eso ahora la petición se construye en UN solo sitio en vez de repetir sus parámetros: dos
 * llamantes que tienen que "acordarse" de coincidir en tres campos vuelven a divergir tarde o
 * temprano, y el síntoma no falla en compilación ni se ve en pantalla.
 */
fun nowPlayingArtRequest(context: Context, data: Any): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .size(ComponentConfig.NowPlayingArtDecodePx)
        // EXPLÍCITA, aunque `Scale.FILL` sea lo que `ContentScale.Crop` deduciría: es justo el campo
        // cuyo default silencioso partía el caché en dos.
        .scale(Scale.FILL)
        .crossfade(false)
        .build()


/**
 * Geometría del header inmersivo PARA ESTA VENTANA: su alto y el recorrido de scroll en el que el
 * título viaja a la topbar.
 *
 * Las cuatro pantallas de detalle aplicaban `ComponentConfig.DetailHeaderHeight` a pelo, y ese
 * número está pensado para vertical: en horizontal el header tapaba el 97 % de la pantalla. El
 * tope sale de [ComponentConfig.DetailHeaderMaxFraction], que en vertical es no-op.
 *
 * **El fade se DERIVA del alto real y no se lee de su constante**: con un header de 180dp, un
 * recorrido de 300 significaría que el título no llega nunca a la barra. Lo que la constante
 * describe es una PROPORCIÓN (el título termina de pasar antes de que el header salga del todo), y
 * es esa proporción la que se conserva.
 */
@Immutable
internal data class DetailHeaderMetrics(val headerHeight: Dp, val titleFadeRange: Dp)

/** La proporción que las dos constantes ya tenían entre sí, para conservarla a cualquier alto. */
private val DetailTitleFadeRatio =
    ComponentConfig.DetailTitleFadeRange / ComponentConfig.DetailHeaderHeight

/** Pura y por tanto testeable; la envuelve [rememberDetailHeaderMetrics]. */
internal fun detailHeaderMetrics(windowHeight: Dp): DetailHeaderMetrics {
    val height = min(ComponentConfig.DetailHeaderHeight, windowHeight * ComponentConfig.DetailHeaderMaxFraction)
    return DetailHeaderMetrics(headerHeight = height, titleFadeRange = height * DetailTitleFadeRatio)
}

/**
 * El alto de ventana sale de `LocalConfiguration` y **no de un `BoxWithConstraints`**, que es el
 * idiom que el repo ya eligió para las decisiones de layout por orientación (`LibraryScreen`,
 * `NowPlayingScreen`): es un CompositionLocal, o sea el MISMO valor en el mismo frame para todos
 * los lectores —aquí son dos, el header y el fade del título— y no subcompone, cosa que importa
 * porque estas pantallas se abren con un container transform en vuelo.
 */
@Composable
internal fun rememberDetailHeaderMetrics(): DetailHeaderMetrics {
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    return remember(windowHeight) { detailHeaderMetrics(windowHeight) }
}


/**
 * `GridCells` de las rejillas de la biblioteca: **como `GridCells.Adaptive`, pero con un SUELO de
 * columnas** ([ComponentConfig.GridMinColumns]).
 *
 * El reparto de anchos es el mismo que hace `GridCells.Fixed` —el sobrante en píxeles se reparte de
 * a uno entre las primeras columnas, para que la fila cierre exacta y no quede una raya sin pintar
 * a la derecha—; lo único propio es cómo sale el número de columnas.
 *
 * **Por qué existe en vez de un `Adaptive` con el mínimo bien elegido**: con `Adaptive` a secas, el
 * caso que no puede fallar —dos columnas en un teléfono— depende de que el ancho de la ventana esté
 * por encima de un umbral, y ese umbral es una división entera, o sea un acantilado. Un teléfono
 * 6dp más estrecho que aquél con el que se calibró el mínimo pierde una columna entera y la
 * pantalla cambia de aspecto (medido: pasó el 27 ago 2026). Como suelo, el mínimo deja de gobernar
 * ese caso y solo decide cuándo aparece la tercera columna, que sí es una decisión blanda.
 *
 * No es `@Immutable` por decreto: `equals`/`hashCode` están escritos porque `LazyVerticalGrid` usa
 * la instancia como clave de sus cálculos, igual que los `GridCells` de la librería.
 */
internal class AdaptiveGridCells(
    private val minSize: Dp,
    private val minColumns: Int = ComponentConfig.GridMinColumns
) : GridCells {
    init {
        require(minSize > 0.dp) { "minSize debe ser > 0" }
        require(minColumns > 0) { "minColumns debe ser > 0" }
    }

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        // `+ spacing` a los dos lados: n columnas de `minSize` llevan n−1 huecos, así que sumando
        // un hueco imaginario al final la cuenta es una división limpia. Es la misma que hace
        // `GridCells.Adaptive`.
        val fitting = (availableSize + spacing) / (minSize.roundToPx() + spacing).coerceAtLeast(1)
        val count = maxOf(fitting, minColumns)
        // Acotado a 0: al forzar el suelo, una ventana más estrecha que los huecos daría un ancho
        // NEGATIVO y la medida revienta. `Adaptive` no necesita esta guarda porque su cuenta nunca
        // pide más columnas de las que caben; el suelo sí puede.
        val gridSize = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val slotSize = gridSize / count
        val remainder = gridSize % count
        return List(count) { slotSize + if (it < remainder) 1 else 0 }
    }

    override fun hashCode(): Int = minSize.hashCode() * 31 + minColumns

    override fun equals(other: Any?): Boolean =
        other is AdaptiveGridCells && other.minSize == minSize && other.minColumns == minColumns
}
