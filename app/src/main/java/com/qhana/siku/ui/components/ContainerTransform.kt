package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.ContainerOriginRole
import com.qhana.siku.ui.LocalRowOrigin
import com.qhana.siku.ui.appSharedTransitionScope
import com.qhana.siku.ui.theme.AppBoundsTransform
import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.appContainerContentEnter
import com.qhana.siku.ui.theme.appContainerContentExitFast
import com.qhana.siku.ui.theme.appEntityContentEnter
import com.qhana.siku.ui.theme.appEntityContentExit

/**
 * Punta **ORIGEN** de un *container transform*: la superficie de la que CRECE una pantalla y a la que
 * vuelve al cerrarse. Envuelve a [content] y no le pide nada — el contenido no sabe que participa.
 *
 * El destino (la pantalla que crece) declara el otro extremo con la misma [key]; hoy lo hace la CAPA
 * del reproductor, con [playerContainerSharedBounds]. La configuración del morph vive AQUÍ y allá.
 * Comparten [AppContainerBoundsTransform] como spec de bounds, `Alignment.Center` y la
 * escala de z de [CONTAINER_SHADOW_OVERLAY_Z] — pero **NO el `ContentScale`, y es deliberado**: aquí
 * `Fit` (contenido CHICO, que así no se amplifica al crecer el rect) y allá `FillWidth` (contenido de
 * PANTALLA COMPLETA, que así nace ya a tamaño natural en vez de dibujarse al 3 % y hacer zoom). El
 * objetivo compartido es que cada contenido se vea a su tamaño natural y que lo que revele sea el
 * RECORTE; ver el bloque largo en `NowPlayingScreen`.
 *
 * ## Sin papel, esto es un [Box] y nada más
 *
 * Con [role] `null` la superficie no declara NADA: ni shared element, ni `Transition`, ni nodo extra.
 * Es el camino caliente de toda lista —las diez filas visibles y las que el scroll recicla— y tiene
 * que costar lo mismo que un `Box`. Hasta el 16 ago cada fila declaraba su punta SIEMPRE (un
 * `AnimatedVisibility` + un `sharedBounds` + la punta de su carátula), porque una punta que nace en el
 * frame del tap no tiene bounds que ofrecer y no hay match; el precio era que el `SharedTransitionScope`
 * llevaba dos entradas por fila visible —cada alta y baja recorre TODAS las entradas
 * (`updateTransitionActiveness`) e invalida el dibujo de la RAÍZ (`renderers` es una lista de
 * snapshot que el overlay lee en cada `draw`)—, y eso se notaba como pérdida de fps al scrollear.
 * La punta ahora se declara **un frame antes de hacer falta**, no siempre: ver [ContainerOriginRole]
 * y `MusicAppState.openPlayer`.
 *
 * ## Con papel: la superficie declara su punta con visibilidad PROPIA, no la de un `AnimatedVisibility`
 *
 * `sharedBounds` pide un `AnimatedVisibilityScope` solo para dos cosas: saber si esta punta es la
 * visible (destino) o la oculta (origen), y hacer el fundido de su contenido. Las dos salen de una
 * `Transition<EnterExitState>` que aquí gobierna [role] directamente ([CallerManagedVisibilityScope]), y eso
 * es lo que permite que la superficie se OCULTE sin salir de la composición:
 *
 * **1. Se oculta mientras vuela** ([ContainerOriginRole.HIDDEN]). No es un efecto: Compose exige que
 * de las dos puntas de una key solo UNA sea destino, y una superficie que se queda visible siempre lo
 * es — con dos destinos no hay animación ninguna, el contenido aparece quieto en su posición final. Es
 * además lo que hace `MaterialContainerTransform`: esconde la vista de origen durante la transición.
 *
 * **2. Pero conserva su ESPACIO.** Material oculta el origen con `View.INVISIBLE`, no con `GONE`: si
 * la fila desapareciera del layout, la lista de debajo saltaría hacia arriba al terminar el morph y
 * volvería a bajar —animada, con `animateItem`— justo mientras la pantalla se contrae y la deja ver
 * otra vez. Aquí el contenido se queda compuesto y medido y solo se FUNDE a alfa cero (el `exit` del
 * contenido, [appContainerContentExitFast]); su `placeholderSize` sigue reportando el tamaño de
 * siempre. Antes esto exigía recordar el alto medido y reponerlo cuando el hijo se descomponía.
 *
 * **3. La punta dura lo que el morph, y ESO DEPENDE DE QUÉ RELOJ SE LE DÉ.** La animación de bounds
 * es una animación diferida de [visibility], y solo corre mientras
 * `SharedTransitionScope.isTransitionActive` — que pregunta a `BoundsAnimation.isRunning`, y ése sube
 * por `parentTransition` hasta la Transition **raíz** de la punta DESTINO. Por eso [visibility] llega
 * de fuera en vez de fabricarse aquí con un `updateTransition`: con una transición propia, cerrar
 * hacia una fila dejaba el bounds sin ventana y SALTABA. Ver `RowOriginHost.rowVisibility`, que es
 * donde está la historia completa y la prueba que lo aisló.
 *
 * La ENTRADA del contenido es [appContainerContentEnter] (`None`) y la SALIDA
 * [appContainerContentExitFast]: el contenido se funde rápido —la superficie chica se escala hacia
 * arriba y se pixela, hay que quitarla de la vista antes de que el mosaico se note— y el bounds sigue
 * hasta el final.
 *
 * Sin `SharedTransitionScope` (hojas y diálogos, donde se anula a propósito) esto es un [Box] pase lo
 * que pase.
 *
 * @param key key del elemento compartido; la misma que declara el destino.
 * @param role papel de esta superficie en el morph AHORA (null = ninguno). Ver [ContainerOriginRole].
 * @param visibility el reloj del morph: `Visible` = destino, `PostExit` = origen oculto. Lo construye
 *   el caller para que cuelgue de la transición correcta (ver arriba); `null` = sin papel.
 * @param modifier lo que posiciona la superficie en su lista (padding, `animateItem`, ancho). Va por
 *   fuera del elemento compartido a propósito: lo que morfa es la superficie, no su hueco.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ContainerTransformOrigin(
    key: Any,
    role: ContainerOriginRole?,
    visibility: Transition<EnterExitState>?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // `appSharedTransitionScope()` y no el local a pelo: dentro de un diálogo o una hoja (que son
    // otra ventana) el scope entrega coordenadas de otro árbol de layout y usarlo CRASHEA.
    val sharedScope = appSharedTransitionScope()
    val originModifier = if (sharedScope == null || role == null || visibility == null) {
        Modifier
    } else {
        // Visible = destino (preparándose o recibiendo el cierre); PostExit = origen oculto. Es la
        // misma máquina de estados que usa AnimatedVisibility por dentro, pero gobernada desde fuera
        // (por la capa del reproductor) en vez de por un `visible` que descompone el contenido.
        val visibilityScope = remember(visibility) { CallerManagedVisibilityScope(visibility) }
        // El que SALE se dibuja ENCIMA y se disuelve sobre el que ENTRA, sólido debajo: es lo que
        // hace que abrir se lea como CRECER y no como un revelado. Ver la escala de z completa.
        val exiting = visibility.targetState != EnterExitState.Visible
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = AppContainerBoundsTransform,
                enter = appContainerContentEnter(),
                exit = appContainerContentExitFast(),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                    ContentScale.Fit,
                    Alignment.Center
                ),
                zIndexInOverlay =
                    if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                    else CONTAINER_SURFACE_OVERLAY_Z_ENTERING
            )
        }
    }
    // Un solo Box, se tenga papel o no: cambiar de papel cambia su cadena de modifiers, NUNCA la
    // estructura. Si el papel envolviera el contenido en otro composable, prepararse recrearía la
    // fila entera —carátula incluida— en el mismo frame en que tiene que ofrecer sus bounds.
    Box(modifier = modifier.then(originModifier)) { content() }
}

/**
 * Un `AnimatedVisibilityScope` cuya `Transition` la gobierna **quien lo construye**, en vez de un
 * `AnimatedVisibility`/`AnimatedContent` que compone y descompone su contenido.
 *
 * Es la pieza que permite que una punta de *container transform* esté SIEMPRE compuesta y solo cambie
 * de papel (destino / origen oculto): `sharedBounds` no necesita nada más de un
 * `AnimatedVisibilityScope` que su `transition` —de ella saca si esta punta es la visible y con qué
 * enter/exit funde su contenido—, así que dándosela hecha se le quita el poder de decidir la VIDA del
 * subárbol. La usan las dos puntas que tienen que sobrevivir a la transición: las filas de lista
 * ([ContainerTransformOrigin]) y el reproductor persistente (`PlayerOverlay`).
 *
 * La interfaz solo tiene abstracto `transition`; `animateEnterExit` trae su implementación y aquí no
 * lo usa nadie.
 */
internal class CallerManagedVisibilityScope(
    override val transition: Transition<EnterExitState>
) : AnimatedVisibilityScope

/**
 * [ContainerTransformOrigin] para una superficie de CANCIÓN: crece hasta ser el reproductor (ver
 * [rowContainerSharedKey]). Lo usan las filas de lista (Todas, detalles) y, desde el 17 ago 2026,
 * las TARJETAS de los carruseles de canciones del inicio.
 *
 * **No hace falta pasarle ningún id de "quién abrió el player"**: la superficie origen es SIEMPRE la
 * de la canción activa, porque `MusicController.announceSelection` la fija en el frame del tap. Así
 * que cada una decide por su cuenta preguntando a [LocalRowOrigin] qué papel le toca, sin que ningún
 * callback cargue con el dato. De regalo, si el usuario cambia de canción DENTRO del reproductor, al
 * cerrar la superficie aterriza en la que AHORA suena, que es lo coherente.
 *
 * **La identidad es la CANCIÓN, y eso admite un EMPATE en el inicio**: sus carruseles son consultas
 * independientes que no deduplican entre sí, así que la misma canción puede tener tarjeta en dos a la
 * vez —"Lo que más escuchas" y el carrusel del artista solapan por construcción— y las dos tomarían
 * el papel de origen. Es una decisión tomada SABIÉNDOLO (usuario, 17 ago 2026): lo que importa es que
 * abra con animación, y de cuál de las dos empatadas salga da igual. La alternativa —un id por
 * SUPERFICIE (`home:<sección>:<id>`), con el origen del morph llevando canción y superficie por
 * separado— se escribió entera y se descartó por no pagar su complejidad. No reintroducirla sin un
 * síntoma real.
 *
 * El `derivedStateOf` no es adorno: el origen cambia al preparar, al abrir y al cerrar, y leerlo a
 * pelo recompondría TODAS las superficies visibles en ese mismo frame —el que arranca la transición y
 * el que la termina, justo donde se notaba el tirón—. Así solo recompone aquella cuyo papel cambia.
 *
 * Además AVISA cada vez que queda COLOCADA (`onRowPlaced`, un `onPlaced` que Compose dispara en cada
 * colocación, no solo cuando cambia la posición — `MeasurePassDelegate.onNodePlaced`) y cuando se va
 * (`onRowDisposed`). Con eso `MusicAppState` mantiene el conjunto de superficies que están DE VERDAD
 * en pantalla, y `openPlayer` sabe en el instante del tap, sin esperar a nada, si hay una que pueda
 * ser origen — el play de una cabecera arranca por la primera canción, que puede estar fuera de
 * pantalla, y entonces el reproductor se abre sin origen en vez de esperar a una fila que no existe.
 * Se registra al COLOCARSE y no al componerse a propósito: el prefetch de `LazyColumn` compone (y
 * corre los efectos de) filas que aún no se ven, y una fila compuesta pero no colocada nunca daría la
 * señal con la que se abre. La misma señal es la que, en la superficie en preparación, dispara la
 * apertura.
 */
@Composable
fun SongRowContainer(
    songId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Sin scope (hojas, diálogos, la ventana de búsqueda) la superficie no puede ser origen de nada:
    // ni se registra ni pregunta por su papel.
    if (appSharedTransitionScope() == null) {
        Box(modifier) { content() }
        return
    }
    val rowOrigin = LocalRowOrigin.current
    val role by remember(songId, rowOrigin) { derivedStateOf { rowOrigin.roleOf(songId) } }
    // Identidad de ESTA superficie, para que el registro de colocadas cuente instancias y no un
    // booleano por canción: dos tarjetas del inicio pueden mostrar la misma canción. Ver `placedRows`.
    val instance = remember { Any() }
    DisposableEffect(songId, rowOrigin, instance) {
        onDispose { rowOrigin.onRowDisposed(songId, instance) }
    }
    // El reloj del morph: una transición HIJA de la de la CAPA del reproductor, no una propia. Es el
    // arreglo de "la fila se queda en blanco al cerrar" — el porqué completo está en
    // `RowOriginHost.rowVisibility`.
    //
    // **Solo con papel**, igual que el resto de la maquinaria de esta superficie: una hija se registra
    // en el padre y se da de baja al descomponerse, y pedirla en las diez filas visibles la pagaría
    // cada frame de scroll — que es justo el coste que se quitó el 16 ago. Que renazca al volver a
    // tomar papel es correcto y no accidental: `createChildTransition` nace con el estado ACTUAL de la
    // capa, o sea el papel que le toca, y sin animación pendiente (por eso no es un caso de la
    // convención 15, donde el problema es un valor inicial que MIENTE durante un frame).
    val visibility = if (role == null) null else rowOrigin.rowVisibility()
    // Sonda (solo con la sonda activa): cuántas superficies se componen antes de un frame largo.
    SideEffect { JankProbe.mark { "fila compuesta ${songId.takeLast(12)}${role?.let { " [ORIGEN $it]" } ?: ""}" } }
    ContainerTransformOrigin(
        key = rowContainerSharedKey(songId),
        role = role,
        visibility = visibility,
        // Por FUERA de la punta compartida: dispara en la colocación de la superficie, en el mismo
        // pase de layout en que la punta de dentro queda colocada. Lo que hace con el aviso es
        // escribir estado, y eso se consume en el frame siguiente — con la punta ya medida y puesta.
        modifier = modifier.onPlaced { rowOrigin.onRowPlaced(songId, instance) },
        content = content
    )
}

/**
 * Punta de un shared element de **imagen de entidad** (carátula de álbum, foto de artista, collage de
 * género) que viaja entre una CELDA de la biblioteca y el HEADER de su pantalla de detalle.
 *
 * Existe para que las ~8 puntas de ese viaje se configuren en UN solo sitio. Estaban copiadas a mano
 * en cada superficie —carruseles del inicio, tile de álbum, fila de artista, tile de género y los tres
 * headers— y solo compartían el `boundsTransform`; el resto (forma, cruce de contenidos) quedaba al
 * default de la API, que es justo lo que se veía mal.
 *
 * ## La FORMA hay que declararla, y es lo que arregla el bug del 19 ago 2026
 *
 * Un shared element se dibuja en el **overlay** del `SharedTransitionScope`, que cuelga de la RAÍZ y
 * por tanto se salta los recortes de sus padres. Las celdas no llevan su forma puesta —la aporta un
 * ancestro: entonces el `maskClip` del carrusel de M3 en el inicio, el `Card` del tile de álbum—, así que en cuanto
 * despegaban se convertían en un RECTÁNGULO DE ESQUINAS VIVAS que además desbordaba sus bounds:
 * la portada entera flotando sobre la pantalla, como se ve en la grabación del usuario.
 *
 * El default `ParentClip` no lo cubre: recorta con el clip del shared element PADRE (para puntas
 * anidadas, como la carátula dentro del reproductor) y devuelve `null` cuando no lo hay. O sea, la
 * regla es: **la punta lleva su forma puesta, o la declara aquí**. Es la misma lección que ya estaba
 * aprendida —y escrita— en las tarjetas de CANCIÓN del inicio (`.clip(HomeCardShape)` dentro de la
 * punta) y en la del reproductor (`clipInOverlayDuringTransition` con el token `extraLarge`), y que
 * nunca se propagó a álbum/artista. Cada punta declara LA SUYA: la celda su forma redondeada y el
 * header un rectángulo, y el cruce de contenidos hace el morph de esquinas.
 *
 * Recortar contra los bounds ANIMADOS (que es lo que hace `OverlayClip`) es además lo que convierte el
 * escalado del contenido en un RECORTE que revela: la regla del container transform del reproductor,
 * aquí gratis porque una imagen cuadrada escalada por ancho y recortada al rect es exactamente lo que
 * pinta el `ContentScale.Crop` del otro extremo.
 *
 * El cruce va con [appEntityContentEnter]/[appEntityContentExit] (el token *fast* de effects) y
 * no con el `fadeIn()`/`fadeOut()` que la API pone por defecto: ese trae specs de `compose-animation`,
 * ajenos al tema, y encima con otra duración que el bounds al que acompañan.
 *
 * @param state estado compartido HOISTADO. Los headers de detalle lo izan porque su título también lo
 *   consulta (`isMatchFound`) para saber si debe elevarse al overlay; crear un segundo con la misma
 *   key sería declarar un target duplicado.
 * @param shape forma de ESTA punta durante el vuelo.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun entityImageSharedBounds(
    state: SharedTransitionScope.SharedContentState?,
    shape: Shape,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null || state == null) return Modifier
    return with(sharedTransitionScope) {
        Modifier.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = animatedVisibilityScope,
            // El spec del TEMA en vez del default de la API: [AppBoundsTransform] es el spring
            // spatial del `MotionScheme`, y es un SPRING justamente porque el destino se mueve —
            // vive dentro de la pantalla que entra, que escala durante toda la transición. Así
            // persigue ese destino y asienta cuando la pantalla asienta.
            boundsTransform = AppBoundsTransform,
            enter = appEntityContentEnter(),
            exit = appEntityContentExit(),
            clipInOverlayDuringTransition = OverlayClip(shape)
        )
    }
}

/**
 * [entityImageSharedBounds] para las puntas que no necesitan izar su estado (todas las celdas): crea
 * el `SharedContentState` a partir de la [key] y delega.
 *
 * La [key] es `String` y no `Any` a propósito: todas las keys de la app lo son, y con `Any` una
 * llamada POSICIONAL que pasara un `SharedContentState` casaría con las dos sobrecargas.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun entityImageSharedBounds(
    key: String,
    shape: Shape,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return Modifier
    val state = with(sharedTransitionScope) { rememberSharedContentState(key = key) }
    return entityImageSharedBounds(state, shape, sharedTransitionScope, animatedVisibilityScope)
}

/**
 * Punta **DESTINO** del *container transform* del reproductor: la superficie que crece hasta ser el
 * NowPlaying, y que al cerrarse encoge de vuelta hacia la píldora o hacia la fila.
 *
 * **Vive en la CAPA (`NowPlayingLayer`) y no dentro de `NowPlayingScreen`**, y ese sitio es el arreglo
 * de un fallo real (21 ago 2026). La pantalla solo se compone cuando hay algo que mostrar
 * (`shownState != null`), o sea que la existencia de la punta dependía de que hubieran LLEGADO LOS
 * DATOS: abriendo con la cola vacía —un chip del inicio, o la primera canción de la sesión— la canción
 * tarda en llegar (~43 ms medidos en logcat), y durante esos frames el morph no tenía destino que
 * emparejar; cuando por fin aparecía, la punta nacía a mitad de vuelo. La capa, en cambio, está
 * compuesta siempre. Con esto la superficie crece desde el primer frame aunque todavía no haya
 * contenido —que es justamente lo que hace un container transform: la superficie viaja y el contenido
 * se intercambia— en vez de no viajar en absoluto.
 *
 * La regla general que sale de aquí: **una punta de shared element no puede depender de datos
 * asíncronos**; su ciclo de vida lo marca la transición, no el repositorio.
 *
 * Configuración (ver también [ContainerTransformOrigin], la punta de ORIGEN):
 *  - `enter = appContainerContentEnter()` (`None`) y **`exit = ExitTransition.None`**: la visibilidad
 *    de esta punta la gobierna quien la compone (el reproductor es persistente), y en ese modo el
 *    estado de reposo es `PostExit` — con un `exit` con fade el alfa de reposo sería 0 y la vuelta la
 *    animaría la librería con su spring por defecto, o sea el reproductor CRECIENDO translúcido. El
 *    fundido de salida lo aplica `surfaceFactor` en `NowPlayingScreen`, con el mismo spec.
 *  - `FillWidth` y **no** `Fit`. **El `ContentScale` NO es el mismo en las dos puntas y no puede
 *    serlo** (17 ago 2026). La regla verdadera es una sola: *el contenido de cada punta se dibuja a su
 *    tamaño NATURAL durante todo el morph, y lo que revela u oculta es el RECORTE del contenedor*; lo
 *    que cambia es qué `ContentScale` consigue eso a cada lado, porque `scaleToBounds` mapea el
 *    contenido dentro del rect animado y los dos contenidos tienen tamaños opuestos. En el ORIGEN
 *    (fila o píldora, contenido CHICO) `Fit` lo deja en 1 mientras el rect es el suyo y no lo agranda
 *    al crecer — con `Crop` allí el contenido de la barra se ampliaba 34×, el "14 Occasions gigante"
 *    del 16 ago. Aquí, contenido de PANTALLA COMPLETA, el ratio de ANCHO vale ~0.96 desde el primer
 *    frame (fila y píldora ocupan casi todo el ancho), así que el contenido nace ya a tamaño natural y
 *    el rect lo va destapando. Con `Fit` en esta punta el menor de los ratios es el de ALTO (~0.03):
 *    el reproductor entero se dibujaba al 3 % —una maqueta en miniatura con sus controles diminutos—
 *    y hacía zoom hasta la pantalla, que es un objeto acercándose desde lejos y no un contenedor que
 *    crece (reportado por el usuario el 17 ago). El bloque afirmaba entonces que las dos puntas debían
 *    configurarse IGUAL, generalizando de más un descuido del 16 ago, y eso mantuvo la miniatura un
 *    mes. **`RemeasureToBounds` sigue DESCARTADO**: re-mide el layout cada frame y la carátula, único
 *    `weight(1f)` de la columna, absorbía toda la holgura (la "doble animación" del 16 ago);
 *    `scaleToBounds` no re-mide nada.
 *  - `Alignment.Center` y no `TopCenter`: el contenido se ancla al CENTRO del rect. Abriendo desde una
 *    fila eso es casi un no-movimiento —el centro de la fila ya está cerca del de la pantalla—, que es
 *    la quietud que se busca; anclarlo arriba lo haría subir media pantalla bajo el recorte. La
 *    PORTADA es la excepción y no viaja escalada con esto: tiene su propio shared element
 *    ([PLAYER_ART_SHARED_KEY]) y se eleva al overlay, así que se dibuja una sola vez en sus propios
 *    bounds interpolados — es lo que la deja hacer un viaje limpio de la píldora al centro.
 *  - El que SALE se dibuja ENCIMA y se disuelve sobre el que ENTRA, sólido debajo: así abrir y cerrar
 *    se ven igual.
 *  - `clipInOverlayDuringTransition` con el TOKEN `extraLarge` —no su valor— porque la otra forma del
 *    MiniPlayer sale de ese mismo token: copiar el número dejaría las dos puntas libres de divergir.
 *
 * @param key la superficie de la que crece; `null` = sin morph (chip, notificación) y devuelve
 *   `Modifier` vacío, con la entrada y la salida a cargo del shared axis Z de la capa.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun playerContainerSharedBounds(
    key: Any?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null || key == null) return Modifier
    return with(sharedTransitionScope) {
        val exiting = animatedVisibilityScope.transition.targetState != EnterExitState.Visible
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = AppContainerBoundsTransform,
            enter = appContainerContentEnter(),
            exit = ExitTransition.None,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                ContentScale.FillWidth,
                Alignment.Center
            ),
            zIndexInOverlay =
                if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                else CONTAINER_SURFACE_OVERLAY_Z_ENTERING,
            clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.extraLarge)
        )
    }
}
