package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.appSelectableMenuItemColors
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appMenuItemColors

/**
 * Alto de TODOS los botones de [DetailPlayButtons] —las dos mitades del split button incluidas— y
 * lado del que es cuadrado (el squircle); solo la píldora vertical estrecha su ancho, hasta el
 * mínimo táctil. Que salga de un único sitio no es cosmética: la fila los alinea por su borde, así
 * que si uno midiera distinto la botonera quedaría descuadrada.
 *
 * **Es la escala MEDIUM del spec, no un número propio.** Estuvo en 64 dp —un valor entre dos
 * tamaños de M3, que no es ninguno— hasta que con las cuatro acciones la fila dejó de caber a lo
 * ancho de un teléfono en vertical. `ButtonMediumTokens.ContainerHeight` y
 * `MediumIconButtonTokens.ContainerHeight` valen los dos 56 dp, así que con este valor el `Button`
 * y los `FilledTonalIconButton` caen en la MISMA escala del spec — la misma de la que ya salían las
 * formas (`mediumSquareShape`) y los tamaños de glifo, y a la que `SplitButtonDefaults` también
 * tabula sus esquinas interiores, paddings y glifos. De ahí que todo eso se pueda derivar de aquí
 * con las funciones `…For(buttonHeight)` en vez de escribirse a ojo.
 */
private val DetailActionSize = ButtonDefaults.MediumContainerHeight

/** Aire entre los botones de la botonera. */
private val DetailActionGap = 12.dp

/**
 * Media vuelta: lo que gira el chevron del split button al desplegarse su menú, para que apunte
 * hacia donde el menú se ha abierto. Es la convención del componente en el spec —el chevron no es
 * un icono fijo, sino el indicador del estado del desplegable— y por eso se anima en vez de
 * conmutarse: al girar, la flecha ES la transición.
 */
private const val ChevronOpenRotation = 180f

/**
 * Variantes de ORDEN que el menú del split button ofrece además de "reproducir" y "aleatorio".
 * Nacieron para las playlists, la única pantalla con un orden PROPIO que el usuario construyó
 * (ahí [REVERSED] es el inverso del orden de AGREGADO —la lista tal y como está—, no un criterio
 * alfabético: "lo último que metí primero" es una forma de escucharla que ningún orden por título
 * da). El detalle de Género las suma después: su orden de partida ES alfabético (viene del
 * catálogo), así que ahí [REVERSED] equivale a Z-A, pero invertir o rehacer el orden sigue siendo
 * útil igual. En un artista o un álbum el orden lo dicta el catálogo por OTRO campo (pista, disco)
 * y esas pantallas siguen dejando [DetailPlayButtons.onPlayInOrder] en `null` — su menú se queda
 * en dos entradas.
 */
enum class DetailPlayOrder { REVERSED, TITLE_ASC, TITLE_DESC }

/**
 * Aplica el orden a la lista que va a sonar. Vive junto al enum y no en la pantalla para que el
 * criterio de cada variante esté escrito UNA vez, al lado de lo que lo nombra.
 *
 * Los alfabéticos van por [String.CASE_INSENSITIVE_ORDER], que es el equivalente en Kotlin del
 * `COLLATE NOCASE` con el que `SongDao` ordena por título: la app ya tiene un criterio de "orden
 * alfabético" y esto no puede ser un segundo distinto —el mismo par de canciones saldría en un
 * orden desde la biblioteca y en otro desde aquí—.
 */
fun DetailPlayOrder.sort(songs: List<Song>): List<Song> = when (this) {
    DetailPlayOrder.REVERSED -> songs.reversed()
    DetailPlayOrder.TITLE_ASC -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    DetailPlayOrder.TITLE_DESC -> songs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
}

/**
 * Una entrada del menú del split button. Existe para que el menú se declare como LISTA: su número
 * de items depende de si hay [DetailPlayButtons.onPlayInOrder], y de la posición en la lista sale
 * la forma de cada uno (`MenuDefaults.itemShape`).
 */
private data class PlayMenuEntry(
    val label: String,
    val icon: String,
    val fillIcon: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Botonera de las pantallas de detalle (artista/álbum/género/playlist), estilo M3 Expressive:
 * **una forma por acción**, que es lo que las distingue sin ponerles etiqueta:
 *
 * | Acción | Forma |
 * |---|---|
 * | Reproducir **+** su menú (aleatorio) | SPLIT BUTTON de acento: píldora partida (icono + etiqueta / chevron) |
 * | Encolar todo | squircle tonal |
 * | Añadir canciones (solo listas) | píldora VERTICAL tonal |
 *
 * **Reproducir y aleatorio son UNA pieza**, no dos botones: las dos arrancan el detalle entero y
 * solo cambia el orden, que es exactamente lo que un split button representa —una acción principal
 * y sus variantes—. Antes eran una píldora de acento y un círculo tonal, o sea dos rangos distintos
 * para dos maneras de hacer lo mismo.
 *
 * El reparto es el CANÓNICO del componente: **el leading ejecuta la acción por defecto y el
 * trailing es un CHEVRON que despliega un menú**, donde viven el aleatorio y —con
 * [onPlayInOrder]— las tres variantes de orden de [DetailPlayOrder]. El trailing NO es una
 * segunda acción suelta — para eso existe la sobrecarga de `onClick`, y usarla deja al aleatorio
 * disfrazado de mitad de botón sin serlo. La primera entrada del menú repite la del leading a
 * propósito: es lo que lo hace leer como "las formas de reproducir esto", y es también lo que
 * deja que el menú CREZCA sin que ninguna de esas formas quede en peor sitio que las otras.
 *
 * El orden va de más a menos usado, y la última es además la única de EDICIÓN: las tres primeras
 * arrancan o extienden la reproducción, la cuarta cambia el contenido de la lista. Ninguna se
 * estira al ancho del padre. Componentes REALES de material3 (SplitButtonLayout /
 * FilledTonalIconButton / AppMenuGroup) con sus `shapes()` Expressive: shape-morph al
 * presionar —y en el trailing también al quedar SOSTENIDO mientras su menú está abierto—, en vez
 * de Surface+Box artesanal.
 *
 * **La fila se DESPLAZA en horizontal** como red, no como diseño: con [DetailActionSize] en la
 * escala medium las cuatro acciones caben en un teléfono normal en vertical, pero el ancho del play
 * depende de la longitud de su etiqueta —que cambia con el idioma— y de `fontScale`, que el usuario
 * puede subir por accesibilidad. Recortar contra el borde escondería una acción sin que nada lo
 * insinúe; con el scroll, cuando cabe no pasa nada y cuando no, se alcanza arrastrando. La
 * alternativa —estirar el play a todo el ancho— ya se probó y el usuario la descartó.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailPlayButtons(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    // Solo donde el orden de la lista es del USUARIO (playlists): añade al menú del split button
    // las tres variantes de [DetailPlayOrder]. En `null` el menú se queda en reproducir + aleatorio.
    onPlayInOrder: ((DetailPlayOrder) -> Unit)? = null,
    // Encolar TODO el detalle al final de la cola actual. Squircle tonal (ver abajo el porqué).
    onAddToQueue: (() -> Unit)? = null,
    // Solo en listas editables (playlist): "añadir canciones", como píldora vertical (ver abajo).
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val playAllDesc = stringResource(R.string.detail_play_all)
    val shuffleLabel = stringResource(R.string.detail_shuffle)
    val playOptionsDesc = stringResource(R.string.detail_play_options)
    val addToQueueDesc = stringResource(R.string.detail_add_all_to_queue)
    val addSongsDesc = stringResource(R.string.playlist_add_songs)

    // Glifo de todos los botones de la fila menos el chevron (que tiene el suyo, ver abajo),
    // derivado del alto de la fila con la función del spec en vez de escrito a ojo. El play llevaba
    // 28 sp y los demás 24 sin que nada lo justificara; el token de este tamaño son 24 para el
    // `Button` y para el `IconButton`, así que ahora es el mismo valor y sale de un sitio. Va en sp
    // porque [MaterialSymbol] es una fuente variable, no un vectorial: la conversión se hace con la
    // densidad y no escribiendo el número en sp, o con `fontScale` distinto de 1 las dos cosas
    // dejarían de medir lo mismo (ver [MenuItemIcon]).
    val actionIconSize = with(LocalDensity.current) {
        ButtonDefaults.iconSizeFor(DetailActionSize).toSp()
    }

    // Glifo del CHEVRON, que el spec del split button tabula aparte (y más grande) que el del
    // leading; misma conversión a sp y por el mismo motivo.
    val trailingIconSize = with(LocalDensity.current) {
        SplitButtonDefaults.trailingButtonIconSizeFor(DetailActionSize).toSp()
    }

    // Estado del desplegable del split button. Vive AQUÍ y no en cada pantalla porque no es
    // información de la pantalla: es el estado visual del propio control, y las cuatro que usan
    // esta botonera lo tendrían idéntico.
    var playMenuOpen by remember { mutableStateOf(false) }
    // Entradas del menú, en el orden en que se usan: primero las dos de siempre —reproducir (que
    // repite el leading a propósito) y aleatorio— y detrás las variantes de orden, que son
    // deliberadas y ocasionales. Los dos alfabéticos comparten glifo: `sort_by_alpha` dice
    // "alfabético", que es lo que tienen en común, y el sentido lo dice la etiqueta (A-Z / Z-A);
    // inventarles dos iconos distintos haría que el par se leyera como dos criterios sin relación.
    //
    // Las etiquetas se resuelven ANTES y no dentro del `buildList`: `stringResource` es
    // `@Composable` y su lambda lleva `@BuilderInference`, combinación que no hay por qué poner a
    // prueba para ahorrar tres líneas.
    val playLabel = stringResource(R.string.common_play)
    val reversedLabel = stringResource(R.string.detail_play_reversed)
    val titleAscLabel = stringResource(R.string.detail_play_title_asc)
    val titleDescLabel = stringResource(R.string.detail_play_title_desc)
    val playMenuEntries = buildList {
        add(PlayMenuEntry(playLabel, "play_arrow", fillIcon = true, onClick = onPlayAll))
        add(PlayMenuEntry(shuffleLabel, "shuffle", onClick = onShuffle))
        onPlayInOrder?.let { playInOrder ->
            add(PlayMenuEntry(reversedLabel, "swap_vert") { playInOrder(DetailPlayOrder.REVERSED) })
            add(PlayMenuEntry(titleAscLabel, "sort_by_alpha") { playInOrder(DetailPlayOrder.TITLE_ASC) })
            add(PlayMenuEntry(titleDescLabel, "sort_by_alpha") { playInOrder(DetailPlayOrder.TITLE_DESC) })
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (playMenuOpen) ChevronOpenRotation else 0f,
        // Rotación = geometría, o sea SPATIAL; el `fast` porque es un gesto pequeño de un control,
        // no una transición de región. Su rebote aquí no molesta: una flecha que se pasa un grado
        // y vuelve es exactamente lo que se espera de un chevron Expressive.
        animationSpec = appFastSpatialSpec(),
        label = "chevron"
    )

    // Padding de la mitad REPRODUCIR, con la misma compensación que M3 aplica a un `Button` con
    // icono: un glifo no llena su caja por el lado de fuera como sí lo hace una letra, así que con
    // el mismo aire a los dos lados el conjunto icono+etiqueta se ve corrido hacia la derecha (era
    // el desalineado que ya se corrigió cuando esto era un `Button` suelto). `SplitButtonDefaults`
    // no tabula variante con icono, así que la compensación se DERIVA de la que sí existe —la
    // diferencia entre el padding normal de un `Button` y el que M3 define para uno con icono— en
    // lugar de escribir el número resultante.
    val layoutDirection = LocalLayoutDirection.current
    val leadingPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(DetailActionSize)
    val leadingContentPadding = PaddingValues(
        start = leadingPadding.calculateStartPadding(layoutDirection) -
            (ButtonDefaults.ContentPadding.calculateStartPadding(layoutDirection) -
                ButtonDefaults.ButtonWithIconContentPadding.calculateStartPadding(layoutDirection)),
        end = leadingPadding.calculateEndPadding(layoutDirection)
    )

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DetailActionGap)
    ) {
        // Reproducir y aleatorio son UN SOLO split button, no dos botones sueltos: las dos hacen
        // lo mismo —arrancar el detalle entero— y lo único que cambia es el orden, que es
        // exactamente la relación que este componente representa (una acción principal y sus
        // variantes). Antes eran una píldora de acento y un círculo tonal: dos rangos distintos
        // para dos maneras de hacer lo mismo.
        //
        // El reparto es el CANÓNICO del componente y no una interpretación: el leading ejecuta la
        // acción por defecto y el trailing es un CHEVRON que despliega el menú donde viven todas
        // las variantes, el aleatorio incluido. La primera entrada del menú repite la del leading
        // a propósito — es lo que deja leer el menú como "las formas de reproducir esto" en vez de
        // como "lo otro que también se puede hacer".
        Box {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        colors = appButtonColors(),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayAll()
                        },
                        shapes = SplitButtonDefaults.leadingButtonShapesFor(DetailActionSize),
                        contentPadding = leadingContentPadding,
                        modifier = Modifier.semantics { contentDescription = playAllDesc }
                    ) {
                        // Sin color explícito en el icono ni en la etiqueta: el botón ya publica el
                        // suyo por `LocalContentColor`, que es de donde lo toman [MaterialSymbol] y
                        // [Text] por defecto. Escribirlo a mano obligaría a acertar aquí el par de
                        // la variante cada vez que ésta cambie.
                        //
                        // Sin alineado especial: el `Row` del botón centra a sus hijos y con eso
                        // basta, porque desde el `Trim.Both` de [MaterialSymbol] la caja del glifo
                        // mide su tamaño nominal y lo tiene centrado dentro. Aquí se probó
                        // `alignByBaseline` en los dos —siendo ambos texto, parecía lo natural— y
                        // deja el símbolo ALTO: el glifo se asienta en la línea base ocupándola
                        // entera hacia arriba, mientras que la etiqueta reparte su altura entre
                        // mayúsculas y descendentes. El arreglo iba en la caja del icono, no en la
                        // referencia.
                        MaterialSymbol("play_arrow", size = actionIconSize, fill = true)
                        Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(DetailActionSize)))
                        Text(
                            text = stringResource(R.string.common_play),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 1
                        )
                    }
                },
                trailingButton = {
                    // Sobrecarga `checked`/`onCheckedChange`, que es la del desplegable: mientras
                    // el menú está abierto, el componente redondea su esquina INTERIOR a full y
                    // pinta un state layer, o sea que la mitad se queda visiblemente "sostenida"
                    // mientras dura el menú. Con la sobrecarga de `onClick` eso no ocurre y el
                    // botón se leería como una segunda acción suelta.
                    SplitButtonDefaults.TrailingButton(
                        colors = appButtonColors(),
                        checked = playMenuOpen,
                        onCheckedChange = { playMenuOpen = it },
                        shapes = SplitButtonDefaults.trailingButtonShapesFor(DetailActionSize),
                        contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(DetailActionSize),
                        modifier = Modifier.semantics { contentDescription = playOptionsDesc }
                    ) {
                        // El glifo del trailing es MÁS GRANDE que el del leading (26 contra 24 en
                        // esta escala) y eso lo tabula el spec, no es un descuido: es el único
                        // contenido de una mitad estrecha, mientras que el del leading acompaña a
                        // una etiqueta.
                        //
                        // Gira por `graphicsLayer` y no cambiando de icono a `keyboard_arrow_up`:
                        // así el cambio de estado es UN movimiento continuo, y además no cuesta
                        // recomposición ni una segunda cara de la fuente variable.
                        MaterialSymbol(
                            "keyboard_arrow_down",
                            size = trailingIconSize,
                            modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                        )
                    }
                },
                // El alto va en el LAYOUT y no en cada mitad: su `measurePolicy` mide a los dos
                // hijos con `minHeight = maxHeight`, así que fijándolo aquí una sola vez quedan
                // iguales por construcción. El aire ENTRE ellas es el default del spec (2 dp, el
                // mismo en todas las escalas), que es lo que las hace leerse como una pieza
                // partida en vez de como dos botones pegados; por eso no lleva el
                // [DetailActionGap] de la fila.
                modifier = Modifier.height(DetailActionSize)
            )
            // Menú SEGMENTADO (popup + grupo), el mismo idiom que los overflow de fila: ver la
            // nota en SortChip. Ancla en este `Box`, que es el que envuelve al split button.
            AppMenuPopup(
                expanded = playMenuOpen,
                onDismissRequest = { playMenuOpen = false }
            ) {
                AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                    // La forma de cada entrada sale de su POSICIÓN en la lista
                    // ([menuItemShapeAt]) y no de `leading`/`trailing` escritos a mano: el menú
                    // dejó de tener un número fijo de items en cuanto [onPlayInOrder] pudo añadir
                    // tres, y con las constantes sueltas habría que acordarse de mover el
                    // `trailing` cada vez que cambie el juego de entradas.
                    playMenuEntries.forEachIndexed { index, entry ->
                        DropdownMenuItem(
                            colors = appMenuItemColors(),
                            onClick = {
                                playMenuOpen = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                entry.onClick()
                            },
                            text = { Text(entry.label) },
                            shape = menuItemShapeAt(index = index, count = playMenuEntries.size),
                            leadingIcon = { MenuItemIcon(entry.icon, fill = entry.fillIcon) }
                        )
                    }
                }
            }
        }
        // Encolar todo el detalle. Va a la VISTA y no escondido en un overflow porque es del mismo
        // orden que las otras dos: las tres son "qué hago con estas canciones" y las tres actúan
        // sobre el conjunto entero. Tonal y no de acento porque es la única que NO arranca nada —
        // extiende lo que ya suena—, que es justo la línea que el split button dejó marcada. El
        // feedback lo da el snackbar: sin él la acción es invisible, porque la cola no está en
        // pantalla al pulsar.
        //
        // SQUIRCLE, con las formas SQUARE del spec de icon button y no una inventada. Se toma la
        // escala **medium** (`CornerLarge` → `CornerMedium` al presionar) y no la large: el radio
        // de la large es `CornerExtraLarge`, que a [DetailActionSize] queda a un paso de ser un
        // círculo y no se leería como otra forma. El contraste de formas es como esta fila
        // distingue sus acciones, y aquí separa "encolar" de la píldora partida que reproduce.
        if (onAddToQueue != null) {
            FilledTonalIconButton(
                colors = appFilledTonalIconButtonColors(),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddToQueue()
                },
                shapes = IconButtonDefaults.shapes(
                    shape = IconButtonDefaults.mediumSquareShape,
                    pressedShape = IconButtonDefaults.mediumPressedShape
                ),
                modifier = Modifier
                    .size(DetailActionSize)
                    .semantics { contentDescription = addToQueueDesc }
            ) {
                MaterialSymbol("low_priority", size = actionIconSize, color = AppColors.onSecondaryContainer)
            }
        }
        // Añadir canciones (solo playlists editables). PÍLDORA VERTICAL, la tercera forma de la
        // fila: es la única acción de EDICIÓN entre tres verbos de reproducción, y darle su propia
        // silueta lo dice sin una etiqueta. Mismo idiom que el "guardar la cola como lista" de la
        // hoja de cola y que el overflow ⋮ de las filas, por el mismo motivo que allí: comparte la
        // familia tonal con el encolado sin ser otro cuadrado más.
        //
        // La forma sale sola del default `shapes()` (`CornerFull`) al ser el contenedor más ALTO
        // que ancho — no hay que dibujar ninguna píldora a mano. Y el ancho es el mínimo táctil del
        // sistema, que es lo más estrecho que puede ser sin dejar de ser tocable: exactamente lo
        // que se le pide a una forma que debe leerse vertical, y de paso sigue la configuración de
        // accesibilidad en vez de un valor clavado.
        if (onAddSongs != null) {
            FilledTonalIconButton(
                colors = appFilledTonalIconButtonColors(),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddSongs()
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .width(LocalMinimumInteractiveComponentSize.current)
                    .height(DetailActionSize)
                    .semantics { contentDescription = addSongsDesc }
            ) {
                MaterialSymbol("playlist_add", size = actionIconSize, color = AppColors.onSecondaryContainer)
            }
        }
    }
}

/**
 * Botón de overflow por CANCIÓN (píldora vertical tonal con ⋮) con menú: favorito, añadir a lista
 * de reproducción y encolar.
 *
 * [onToggleFavorite] es OPCIONAL, y en `null` la entrada de favorito no se pinta: es para las
 * filas que ya llevan el corazón FUERA, como acción rápida propia (el detalle de Favoritos). Ahí
 * repetirlo dentro del menú serían dos controles para lo mismo a un centímetro uno del otro.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongOverflowButton(
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    // Fondo REAL de la fila (tinte del ítem activo ya compuesto, vía `songRowBackground`): de él se
    // derivan los colores de la píldora, ver `rememberRowActionColors`.
    rowBackground: Color,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val optionsDesc = stringResource(R.string.common_song_options)
    val colors = rememberRowActionColors(rowBackground)
    Box(modifier = modifier) {
        // Píldora VERTICAL (M3 Expressive) como FilledIconButton real: shape-morph al presionar.
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
                .semantics { contentDescription = optionsDesc }
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        AppMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                // El favorito es opcional, así que las formas salen de la POSICIÓN
                // ([menuItemShapeAt]) y no de `leading`/`middle`/`trailing` escritos a mano: sin
                // esa entrada, "añadir a lista" pasa a ser la primera y con las constantes
                // sueltas el grupo se quedaría con dos esquinas rectas arriba.
                val itemCount = if (onToggleFavorite != null) 3 else 2
                // Favorito es un TOGGLE, no una acción: va con la sobrecarga `checked`
                // (contenedor marcado, morph de forma y `Role.Checkbox`). El texto sigue diciendo
                // qué pasa al tocarlo — el estado lo cuenta el item, no solo el relleno del
                // corazón, que un lector de pantalla no anuncia.
                onToggleFavorite?.let { toggleFavorite ->
                    DropdownMenuItem(
                        colors = appSelectableMenuItemColors(),
                        checked = isFavorite,
                        onCheckedChange = {
                            showMenu = false
                            toggleFavorite()
                        },
                        text = { Text(if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)) },
                        shapes = MenuDefaults.itemShapes(shape = menuItemShapeAt(0, itemCount)),
                        // El glifo dice el VERBO, igual que la etiqueta de al lado: en un menú
                        // la entrada es una acción, no un indicador de estado (ese es el corazón
                        // de la fila, que sí se queda en "favorite" con su relleno).
                        leadingIcon = {
                            MenuItemIcon(if (isFavorite) "heart_minus" else "heart_plus")
                        }
                    )
                }
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                    shape = menuItemShapeAt(itemCount - 2, itemCount),
                    leadingIcon = { MenuItemIcon("playlist_add") }
                )
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    },
                    text = { Text(stringResource(R.string.common_add_to_queue)) },
                    shape = menuItemShapeAt(itemCount - 1, itemCount),
                    leadingIcon = { MenuItemIcon("low_priority") }
                )
            }
        }
    }
}

/**
 * Overflow (⋮) con una ÚNICA acción: "añadir a la cola". Misma píldora vertical que
 * [SongOverflowButton], para que las dos se lean como el mismo control.
 *
 * Los colores llegan HECHOS en vez de derivarse aquí de un fondo, porque sus consumidores no
 * comparten el mismo tipo de superficie: una fila de lista tiene un color plano detrás
 * ([rememberRowActionColors] sobre el contenedor de la fila), pero en la tarjeta de álbum la píldora
 * se apoya sobre la CARÁTULA, donde no hay fondo del que derivar nada — ahí se pinta un contenedor
 * tonal sólido, igual que ya hace el badge de conteo que vive en esa misma imagen.
 *
 * [menuLabel] existe porque la acción no dice lo mismo según sobre qué se aplique: en una canción es
 * "añadir a la cola" y en un artista/álbum/género son N canciones ("añadir todo a la cola").
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueOverflowButton(
    onAddToQueue: () -> Unit,
    colors: RowActionColors,
    contentDescription: String,
    modifier: Modifier = Modifier,
    menuLabel: String = stringResource(R.string.common_add_to_queue)
) {
    var showMenu by remember { mutableStateOf(false) }
    // Copia local: dentro de `semantics {}` el nombre `contentDescription` es la propiedad del
    // receiver, no el parámetro.
    val optionsDesc = contentDescription
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
                .semantics { this.contentDescription = optionsDesc }
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        AppMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            // Un solo item: el clip redondeado del grupo ya le da forma de píldora, así que no
            // hace falta repartir leading/middle/trailing como en los menús de varias entradas.
            AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                DropdownMenuItem(
                    colors = appMenuItemColors(),
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    },
                    text = { Text(menuLabel) },
                    shape = MenuDefaults.standaloneItemShape,
                    leadingIcon = { MenuItemIcon("low_priority") }
                )
            }
        }
    }
}
