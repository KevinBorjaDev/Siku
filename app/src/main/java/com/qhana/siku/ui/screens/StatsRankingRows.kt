package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.StatsNameEntry
import com.qhana.siku.data.repository.StatsSongEntry
import com.qhana.siku.ui.components.ArtCarouselCard
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.rememberRowAccentColor
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface

/*
 * Las piezas de los cuatro rankings de la pantalla de estadísticas.
 *
 * Vivían dentro de `StatsListScreen`, la pantalla (ya borrada) a la que se llegaba tocando una
 * tarjeta del resumen. Esa pantalla desapareció el 30 ago 2026: con el tope del ranking en diez
 * entradas, las cuatro categorías caben dentro de la propia pantalla de estadísticas, así que un
 * nivel de navegación entero —y su ViewModel, y una ruta con tres argumentos— dejó de hacer falta.
 *
 * **Y ya no es la misma fila cuatro veces.** Hubo una versión con una única `nameRankingRows` para
 * artistas, álbumes y géneros, y el resultado era una pantalla que se repetía: cuarenta filas de la
 * misma anatomía distinguidas solo por su texto. Cada categoría pide otra cosa —
 * [songRankingRows] es un ranking que se lee en orden, [RankCarousel] se navega MIRANDO porque
 * artistas y álbumes tienen imagen, y [genreShareRows] enseña un REPARTO porque el género no tiene
 * imagen y su dato interesante no es el orden sino la proporción.
 */

/**
 * Ancho de la columna del número de posición.
 *
 * Fijo y con cifras tabulares: si la columna se ciñera a su contenido, el "10" empujaría su fila y
 * las carátulas dejarían de estar alineadas justo en la última.
 */
private val RankNumberWidth = 24.dp

/**
 * Aire entre el número y la carátula, y por qué existe la constante.
 *
 * El número quedaba pegado al borde de la fila con todo el aire a su derecha (device, 16 sep 2026).
 * La causa era ESTRUCTURAL: iba en una columna hermana del `ListItem` de [SongItem], o sea por fuera
 * del margen de contenido que ese componente aplica por dentro. Ahora viaja en su slot leading, así
 * que **el margen izquierdo lo pone el propio `ListItem`** y aquí solo queda igualarlo por el otro
 * lado — que es lo que deja el número centrado en su hueco.
 *
 * Se repone a mano el valor, no el criterio: `ListItemStartPadding` es `internal` en M3, así que
 * copiarlo para compensar por fuera habría quedado desincronizado en silencio si cambiara. Como gap
 * entre dos piezas de contenido, un desajuste solo correría el número unos dp.
 */
private val RankNumberGap = 16.dp

/**
 * Lado de la tarjeta de un carrusel: **132dp, no los 196 del inicio**.
 *
 * Los 196 son la medida del multi-browse, donde solo una tarjeta está en foco y las demás asoman. En
 * un `LazyRow` de ranking las diez pesan lo mismo, así que la medida la decide otra cosa: cuántas
 * caben legibles a la vez. Con 132 entran casi tres en un teléfono de 393dp y "2 reproducciones"
 * sigue cabiendo en el subtítulo — a partir de tres cifras elide, que es aceptable para el
 * subtítulo y no lo sería para el nombre.
 */
private val CarouselCardSize = 132.dp

/** Aire entre dos tarjetas del carrusel. El mismo que el del inicio. */
private val CarouselSpacing = 8.dp

/**
 * Esquina de la tarjeta: menor que los 24dp del inicio, en proporción a que la tarjeta es más chica.
 * Un radio fijo sobre una caja más pequeña se come el encuadre de la portada.
 */
private val CarouselCardShape = RoundedCornerShape(18.dp)

/**
 * Las diez canciones del periodo, **numeradas**.
 *
 * El número no es decoración: las filas están ordenadas por reproducciones, pero sin él el orden hay
 * que inferirlo comparando contadores — y un ranking cuyo primer puesto no se ve no se lee como un
 * ranking. Los tres primeros van en acento; el resto, en el color secundario.
 */
internal fun LazyListScope.songRankingRows(
    songs: List<StatsSongEntry>,
    currentSong: Song?,
    onPlay: (List<Song>, Int) -> Unit
) {
    itemsIndexed(items = songs, key = { _, entry -> "song_${entry.song.id}" }) { index, entry ->
        // La canción actual, esté sonando o en pausa: el resaltado marca "cargada", no "sonando".
        val isPlaying = currentSong?.id == entry.song.id
        val rowBackground = songRowBackground(AppColors.contentSurface, isPlaying)
        SongRowContainer(
            songId = entry.song.id,
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 1.dp)
        ) {
            AppSurface(
                color = AppColors.contentSurface,
                shape = rememberListItemShape(index, songs.size, isActive = isPlaying),
                modifier = Modifier.fillMaxWidth()
            ) {
                // El número va por el slot LEADING de [SongItem], delante de la carátula, y no en
                // una columna hermana cosida por fuera: así su margen de inicio lo pone el
                // `ListItem` que hay dentro, que es quien sabe cuánto mide (ver [RankNumberGap]).
                // Lo que se le pasa a ese componente compartido es una POSICIÓN, no el concepto de
                // ranking — el slot es genérico y simétrico a `trailingContent`, que esta misma
                // fila ya usaba.
                //
                // El `clickable` va en la fila ENTERA para que tocar el número también reproduzca.
                SongItem(
                    song = entry.song,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(songs.map { it.song }, index) },
                    // **Sin indicador de origen** (device, 30 ago 2026). Ese icono dice si la
                    // canción está en el dispositivo, descargada o en streaming, y es útil en la
                    // biblioteca —donde se decide qué reproducir sin datos— pero aquí no: esto
                    // es un resumen de lo que YA se escuchó, y de dónde salieron los bytes no
                    // cambia nada de lo que la pantalla cuenta. En una biblioteca de pura nube
                    // además sale el mismo glifo en las diez filas, o sea una columna que no
                    // distingue nada mientras roba ancho al título.
                    showStatusIcon = false,
                    leadingContent = {
                        RankNumber(position = index + 1, rowBackground = rowBackground)
                    },
                    trailingContent = {
                        PlaysBadge(
                            plays = entry.plays,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }
    }
}

/**
 * El puesto de una fila del ranking.
 *
 * El acento de los tres primeros sale del fondo REAL de la fila y no de un rol fijo: sobre el
 * relleno de la que suena —del mismo matiz que `primary`— un `primary` a secas se pierde. Es el
 * mismo helper que usa el corazón de Favoritos.
 */
@Composable
private fun RankNumber(position: Int, rowBackground: Color) {
    val accent = rememberRowAccentColor(rowBackground)
    Text(
        text = position.toString(),
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = if (position <= RANK_ACCENT_POSITIONS) accent else AppColors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        // El padding va FUERA del ancho: el número sigue midiendo [RankNumberWidth] —que es lo que
        // mantiene las carátulas alineadas entre una fila de una cifra y otra de dos— y el gap se
        // suma por detrás. Al revés, el aire se comería la columna.
        modifier = Modifier
            .padding(end = RankNumberGap)
            .width(RankNumberWidth)
    )
}

/**
 * Cuántos puestos van en acento: **el podio**. Con más, el acento deja de señalar nada; con menos,
 * un solo número teñido parece un estado y no una posición.
 */
private const val RANK_ACCENT_POSITIONS = 3

/**
 * Artistas o álbumes: **un carrusel horizontal con la tarjeta del inicio**.
 *
 * En fila vertical la imagen queda reducida a un cuadradito de 48dp al lado de un texto, o sea la
 * misma anatomía que la lista de canciones de arriba — y apilar tres listas idénticas era lo que
 * hacía que la pantalla se leyera como la misma sección repetida. Estos dos tienen imagen, así que
 * se navegan MIRANDO.
 *
 * **Usa [ArtCarouselCard], la del inicio, y eso cierra cuatro rondas de device** (30 ago 2026). El
 * conteo empezó siendo un número suelto bajo el nombre ("¿qué significa?"), luego llevó un glifo,
 * luego subió de tono, luego se metió en una píldora — y seguía "flotando", porque el problema no
 * era el color ni el sitio: **era estar FUERA de la imagen**, colgando de un fondo del que no puede
 * derivar contraste. Con la carátula a sangre, su degradado y la etiqueta encima, no hay nada que
 * apoyar. Idea del usuario: reusar los carruseles del inicio, donde eso ya estaba resuelto.
 *
 * Dos cosas que se van con el cambio y conviene no reponer por reflejo:
 * - **Las siluetas cookie.** Esta tarjeta es un rectángulo redondeado porque la carátula llega a los
 *   bordes; recortarla en lóbulos y encima pintarle un degradado abajo sería pelear dos formas.
 * - **El número de puesto**, que ya se había quitado: en una tira horizontal el orden lo dice la
 *   posición.
 *
 * **Y NO es el `HorizontalMultiBrowseCarousel` del inicio, es un `LazyRow`** (D2, elegido en device
 * frente a D1). Aquel reparte el foco: su `labelAlpha` apaga el nombre y el conteo de todo lo que no
 * está en el centro, lo cual es correcto para SUGERIR —que es lo que hace el inicio— y no para un
 * RANKING, donde el trabajo del usuario es comparar. Aquí las tarjetas son más chicas y todas se
 * leen a la vez, así que `labelAlpha` se queda en su default de `1f`.
 *
 * A cambio se pierde el morphing del multi-browse. Es el precio de que las diez digan su nombre.
 */
@Composable
internal fun RankCarousel(
    entries: List<StatsNameEntry>,
    fallbackIcon: String,
    onClick: (StatsNameEntry) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(CarouselSpacing),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items = entries, key = { entry -> entry.key }) { entry ->
            ArtCarouselCard(
                art = entry.artUri,
                title = entry.name,
                // **Aquí la palabra SÍ cabe**, y por eso el glifo `play_arrow` que hacía de etiqueta
                // en la versión anterior sobra: a 132dp entra "2 reproducciones". El glifo existía
                // porque bajo una tarjeta de 100dp no cabía nada más.
                subtitle = pluralStringResource(
                    R.plurals.stats_plays,
                    entry.plays,
                    entry.plays
                ),
                placeholderIcon = fallbackIcon,
                onClick = { onClick(entry) },
                modifier = Modifier
                    // Con `key` puesta, la convención de la app es acompañarla de `animateItem`:
                    // cambiar de periodo reordena el ranking entero, que es justo lo que este
                    // modificador existe para que no salte de golpe.
                    .animateItem()
                    .size(CarouselCardSize)
                    // La tarjeta se recorta a sí misma, igual que en los carruseles del inicio: ni
                    // este `LazyRow` ni `KeylineCarousel` enmascaran nada desde fuera.
                    .clip(CarouselCardShape)
            )
        }
    }
}

/**
 * Los géneros del periodo, con **su proporción** y no solo su puesto.
 *
 * Es la única de las cuatro categorías sin imagen, así que un carrusel de tarjetas de color con un
 * glifo repetido no aportaría nada. Pero además su dato interesante es otro: de un artista importa
 * el orden y de un género importa el REPARTO — "este mes escuchaste más cumbia que rock, aunque en
 * global escuches más metal que cumbia" es justo lo que un top ordenado no dice y una proporción sí.
 * Por eso esta misma pieza aparece en todos los periodos: comparar un mes con "Siempre" es mirar dos
 * veces la misma barra.
 *
 * @param shareOf fracción `[0,1]` de una entrada. Sale del ViewModel y no se calcula aquí porque el
 *   denominador correcto —solo las reproducciones CON género— es la parte fácil de equivocar.
 */
internal fun LazyListScope.genreShareRows(
    entries: List<StatsNameEntry>,
    shareOf: (StatsNameEntry) -> Float,
    onClick: (String) -> Unit
) {
    itemsIndexed(items = entries, key = { _, entry -> "genre_${entry.name}" }) { index, entry ->
        GenreShareRow(
            entry = entry,
            share = shareOf(entry),
            // El mismo helper que las filas de canción, y no `SegmentedListItem`: esta fila pinta
            // un relleno propio por debajo del contenido, y ese componente no deja meter nada entre
            // su superficie y su texto.
            shape = rememberListItemShape(index, entries.size),
            onClick = { onClick(entry.name) },
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun GenreShareRow(
    entry: StatsNameEntry,
    share: Float,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppSurface(
        onClick = onClick,
        color = AppColors.contentSurface,
        shape = shape,
        modifier = modifier
    ) {
        // **El relleno es un color SÓLIDO y no el acento con alfa** (regla del 29 ago 2026): el
        // contenedor de debajo se conoce, así que hay un color tabulado que se lee sobre él sin
        // depender de con qué se componga.
        //
        // Y se dibuja con `drawBehind` en vez de con una caja de fondo: una caja pediría a la vez
        // el alto de la fila (que lo pone el contenido) y una fracción del ancho, y `matchParentSize`
        // y `fillMaxWidth` no pueden gobernar la misma medida. Dibujándolo, el relleno no participa
        // en el layout — que es justo lo que es: pintura detrás del texto.
        //
        // **`secondaryContainer` CRUDO**, el mismo relleno que la fila que suena: las dos son "esta
        // fila está marcada", una por estado y otra por magnitud.
        //
        // El texto cruza la frontera del relleno —el nombre empieza dentro y el porcentaje suele
        // caer fuera—, así que la tinta tiene que leerse sobre DOS fondos. Medido sobre los cuatro
        // esquemas y los dos temas: `onSurface` da **7,02:1 en el peor caso** sobre el relleno y
        // 12,6 fuera, contra el 4,5 que pide un texto.
        //
        // **Estuvo DERIVADO** con `tonalLayerContainer` (device, 30 ago 2026: *"no tiene
        // contraste"*), y era correcto entonces: las filas vivían en `surfaceContainerHigh` y la
        // distancia hasta `secondaryContainer` la decidía el estilo de paleta. Al bajar las filas un
        // escalón (11 sep, ver `songRowBackground`) el par tabulado ya se apoya.
        val fillColor = AppColors.secondaryContainer
        val fraction = share.coerceIn(0f, 1f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GenreRowHeight)
                .drawBehind {
                    drawRect(color = fillColor, size = Size(size.width * fraction, size.height))
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // La misma píldora que en las filas de canción, y ahora también el mismo color: el par
            // lleno se ve igual sobre el relleno de la barra que sobre el hueco.
            PlaysBadge(
                plays = entry.plays,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                // Redondeado al entero: un decimal en una lista de siete no se compara mejor y
                // alarga la columna. La barra ya da la precisión que importa.
                text = stringResource(R.string.stats_genre_percent, Math.round(share * 100)),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = AppColors.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(GenrePercentWidth)
            )
        }
    }
}

/** Alto mínimo de una fila de género: más baja que una de canción, que lleva carátula. */
private val GenreRowHeight = 52.dp

/**
 * Ancho de la columna del porcentaje. Fijo para que los porcentajes queden alineados entre sí: con
 * la columna ceñida, "9 %" y "28 %" empezarían en sitios distintos y la lista dejaría de compararse
 * de un vistazo, que es para lo único que existe.
 */
private val GenrePercentWidth = 44.dp

/**
 * **Un número de reproducciones SUELTO lleva siempre su glifo.** Vale donde no cabe la palabra: la
 * fila de canción y la de género, que la tienen a un lado en una columna estrecha.
 *
 * **El carrusel ya NO lo usa.** Sus tarjetas pasaron a `ArtCarouselCard` (30 ago 2026) y a 132dp
 * cabe "2 reproducciones" entera, así que ahí el glifo dejó de hacer falta: era una etiqueta de
 * emergencia para una caja de 100dp donde no entraba nada más.
 *
 * **Por qué un glifo y no la palabra** (device, 30 ago 2026: *"los badges tienen que ser más
 * descriptivos"*). Escribir "42 reproducciones" ya se probó y se descartó el 28 ago: en una lista
 * donde TODAS las filas dicen lo mismo salvo la cifra, la palabra es ruido repetido diez veces que
 * además empuja el nombre de la canción a elipsar antes. Ese argumento sigue en pie — lo que no
 * valía era la conclusión, dejar el número DESNUDO: un `13` bajo "Dream Theater" puede ser canciones,
 * álbumes o escuchas, y las tres son plausibles aquí. El glifo etiqueta sin repetir y cuesta 13dp.
 * Es el mismo recurso con el que `SortChip` se quitó su prefijo "Ordenar:".
 *
 * Para un lector de pantalla el glifo no dice nada, así que el conjunto se anuncia como UNA cosa y
 * con la palabra puesta — que es justo lo que la pantalla no puede permitirse escribir.
 */
@Composable
private fun PlaysLabel(
    plays: Int,
    color: Color,
    style: TextStyle,
    glyphSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val label = pluralStringResource(R.plurals.stats_plays, plays, plays)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { contentDescription = label }
    ) {
        MaterialSymbol("play_arrow", fill = true, size = glyphSize, color = color)
        Spacer(modifier = Modifier.width(PlaysGlyphGap))
        Text(text = plays.toString(), style = style, color = color, maxLines = 1)
    }
}

/** Aire entre el glifo y su cifra. Estrecho: son una sola pieza, no dos datos. */
private val PlaysGlyphGap = 3.dp

/**
 * Cuánto MÁS grande que su cifra se dibuja el glifo.
 *
 * **Un punto por encima y no a la par** (device, 30 ago 2026: *"con más contraste"*). Un triángulo
 * lleno tiene mucha menos tinta que una cifra del mismo cuerpo, así que a igual tamaño se lee más
 * flojo — y era lo primero que se perdía. Lo que NO se toca es el `weight`: cada peso es un
 * `Typeface` distinto, o sea un parseo entero del archivo de iconos (ver `MaterialSymbolFont`), y
 * eso no se paga por un triángulo.
 *
 * Se aplica como INCREMENTO sobre el estilo y no como un tamaño fijo: las dos píldoras que quedan
 * usan cuerpos distintos —`labelLarge` en la fila de canción, `labelMedium` en la de género—, así
 * que un valor absoluto acertaría en una y desproporcionaría el glifo en la otra.
 */
private const val PLAYS_GLYPH_BUMP = 1f

/**
 * **Un conteo de reproducciones va SIEMPRE en una píldora**, en los tres sitios donde aparece.
 *
 * Hasta el 30 ago 2026 solo la llevaba la fila de canción, y el carrusel y la fila de género
 * pintaban el par glifo+cifra suelto sobre el fondo. (El carrusel acabó yéndose por otro camino: su
 * texto vive DENTRO de la imagen, ver [RankCarousel].) En device eso se leyó como *"no veo el
 * contraste, además se ve raro flotando el número ahí"*, y las dos mitades de esa frase son el mismo
 * problema: **un texto sin contenedor no tiene de dónde sacar contraste** — hereda el fondo que le
 * toque— y sin caja no se lee como un dato, se lee como algo que sobró. Subirle el tono no lo
 * arreglaba: el número seguía flotando, y el glifo, que es fino, seguía siendo lo primero que se
 * perdía.
 *
 * La píldora resuelve las dos a la vez: es una superficie con su par contenedor/contenido, así que
 * el contraste está garantizado por construcción y el conjunto se lee como una unidad.
 *
 * **El color es el par lleno `secondary`/`onSecondary`, y no se deriva del fondo** (12 sep 2026).
 *
 * La píldora cae sobre DOS fondos y tiene que verse en los dos: en el ranking, `surfaceContainer` en
 * una fila normal y `secondaryContainer` en la que suena; en la fila de género, el hueco y el relleno
 * de la barra de proporción, que son esos mismos dos roles. Medido sobre los cuatro esquemas y los
 * dos temas, **la familia neutra entera está apilada en esa banda** —`surfaceContainerHighest` cae
 * en T90, exactamente donde `secondaryContainer`— así que ningún contenedor de superficie se separa
 * de ambos. Los únicos que lo consiguen son los de ACENTO, y de ellos `secondary` es el
 * subordinado: `primary` es el color de la acción en toda la app y aquí esto es un dato. Peor caso
 * medido: 37 puntos de tono.
 *
 * Estuvo DERIVADO con `rememberTonalLayerColors` desde el fondo real, con un delta por caller. Eso
 * resolvía el contraste pero dejaba la píldora a un escalón del fondo: **es un dato dentro de una
 * fila, no una capa apoyada en ella**, y el par lleno lo dice mejor.
 *
 * Con un par tabulado desaparecen los tres parámetros que hacían falta para derivar: el fondo, el
 * delta y el `contentOverride` con el que la fila que suena reponía su contenido.
 */
@Composable
private fun PlaysBadge(
    plays: Int,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(AppColors.secondary, CircleShape)
            // Horizontal más justo que los 10dp de una píldora de texto: el glifo ya da presencia, y
            // con más aire el conjunto le quitaba al título el ancho que ganó al retirarse el
            // indicador de origen.
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        PlaysLabel(
            plays = plays,
            color = AppColors.onSecondary,
            style = style,
            glyphSize = (style.fontSize.value + PLAYS_GLYPH_BUMP).sp
        )
    }
}

