package com.qhana.siku.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct

/**
 * Paleta SEMÁNTICA de un banner de estado. Cuatro colores derivados de UN matiz, y ese es todo el
 * sistema.
 *
 * ## Por qué no sale del tema
 *
 * El color de un banner comunica ESTADO, no estética: "descargando", "todo al día", "falló algo",
 * "en pausa". Por eso no se seedea del álbum ni del wallpaper — un error tiene que verse igual con
 * cualquier carátula — y por eso tampoco tiene rama por tema: **es el mismo color en claro y en
 * oscuro**, que es lo que significa que sea agnóstico. Hasta el 29 ago 2026 sí la tenía, con ocho
 * hexes escritos a mano, y el resultado era que en oscuro dos de las cuatro llevaban un contenedor
 * NEUTRO: de su color solo quedaban el glifo y el texto.
 *
 * ## De dónde salen los colores
 *
 * La referencia son las píldoras Do / Don't / Caution de la documentación de M3, muestreadas píxel
 * a píxel: contenedor muy cromático con texto OSCURO del mismo matiz encima (`#FF6240` con
 * `#490909`, `#FFCE22` con `#3A2900`, `#34BE4D` con `#11371D`). De ahí se toma el MATIZ de cada
 * estado y nada más: aquéllas son alertas dentro de un documento y están a croma máximo a
 * propósito, mientras que aquí un banner ocupa el ancho de la pantalla y puede quedarse minutos —
 * el de descarga, una sincronización entera—. Por eso van al croma que [SOFT_CHROMA] permite: la
 * misma familia, en voz baja.
 *
 * Los cuatro colores son ese matiz a cuatro TONOS fijos, y ahí está lo que hace que el sistema
 * funcione con cualquier matiz sin ajustar nada: **en HCT el tono ES el `L*` de CIELAB, y `L*`
 * determina la luminancia por sí solo**. O sea que el contraste entre dos tonos es el mismo para el
 * rojo que para el amarillo — el matiz no entra en la cuenta. Los tonos se eligieron por el
 * contraste que producen, medido, no por cómo se veían:
 *
 *     contenedor  T86
 *     onContainer T25  → 7,8:1 sobre el contenedor   (título e icono; AAA de sobra)
 *     secondary   T38  → 4,8:1                        (subtítulo, `bodySmall`: AA pide 4,5)
 *     track       T70  → 1,6:1                        (pista no recorrida de la onda)
 *
 * `secondary` es aquí un tono FIJO y no [secondaryContentColor]: esa función existe para un
 * contenedor que no se conoce de antemano (el del tema, el de la carátula) y tiene que calcular; una
 * paleta cuyos cuatro colores se declaran juntos ya sabe dónde cae cada uno, así que calcularlo
 * sería resolver en runtime algo que aquí es una constante.
 */
@Immutable
internal data class BannerPalette(
    val container: Color,
    val onContainer: Color,
    val secondary: Color,
    val track: Color
)

/**
 * Construye la paleta de un estado a partir de un color de referencia, del que solo se usa el
 * MATIZ. El croma se acota a [SOFT_CHROMA] y `Hct.from` ya cede lo que no quepa en sRGB, así que un
 * matiz que no admita ese croma a ese tono devuelve el más cercano que sí — nunca un color
 * recortado.
 */
private fun bannerPalette(referenceArgb: Int): BannerPalette {
    val hue = Hct.fromInt(referenceArgb).hue
    fun at(tone: Double, chroma: Double = SOFT_CHROMA) =
        Color(Hct.from(hue, chroma, tone).toInt())
    return BannerPalette(
        container = at(CONTAINER_TONE),
        onContainer = at(ON_CONTAINER_TONE),
        secondary = at(SECONDARY_TONE),
        track = at(TRACK_TONE)
    )
}

/**
 * Croma de la familia. Un tercio largo del de las píldoras de M3 (C* 75-81 medido): lo justo para
 * que cada estado se reconozca por su color sin que el banner grite. **El amarillo es el que fija
 * el suelo** — por debajo de este croma deja de leerse como amarillo y se vuelve beige, mientras
 * que el azul aguantaría mucho menos.
 */
private const val SOFT_CHROMA = 32.0

private const val CONTAINER_TONE = 86.0
private const val ON_CONTAINER_TONE = 25.0
private const val SECONDARY_TONE = 38.0
private const val TRACK_TONE = 70.0

/** Descargando / trabajando: informa, no alerta. El único de los cuatro que no viene de M3. */
internal val BannerBlue = bannerPalette(0xFFA8D5FF.toInt())

/** Todo al día. Matiz del "Do" de M3. */
internal val BannerGreen = bannerPalette(0xFF34BE4D.toInt())

/** Falló algo. Matiz del "Don't" de M3. */
internal val BannerRed = bannerPalette(0xFFFF6240.toInt())

/**
 * En pausa. Matiz del "Caution" de M3 — ámbar y no rojo: quedarse sin WiFi no es un fallo de la app
 * ni pide nada al usuario, y con la paleta de error un banner que solo dice "esto sigue cuando
 * vuelvas a WiFi" se lee como algo que hay que ir a arreglar.
 */
internal val BannerAmber = bannerPalette(0xFFFFCE22.toInt())
