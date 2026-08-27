package com.qhana.siku.ui.viewmodel

/**
 * Un tramo de verso con tiempo propio: lo que hace posible el karaoke PALABRA A PALABRA.
 *
 * No guarda su texto sino el RANGO que ocupa dentro de [LyricLine.text], y eso es load-bearing: el
 * barrido del karaoke se dibuja midiendo el verso ya compuesto y preguntándole al layout dónde cae
 * un carácter. Con el texto duplicado aquí, cualquier normalización (los espacios dobles que dejan
 * las marcas al quitarse) desalinearía el barrido de las letras que se ven, y el fallo sería
 * silencioso: el resaltado corriendo medio carácter por delante sin que nada falle.
 *
 * @param startTime cuándo empieza a sonar, en ms desde el inicio de la canción.
 * @param endTime cuándo termina. Sale de la marca siguiente; si el archivo no la da, del verso
 *        siguiente. Nunca es menor que [startTime].
 * @param startIndex primer carácter del tramo dentro de [LyricLine.text].
 * @param endIndex primero que YA NO le pertenece (rango semiabierto, como `substring`).
 */
data class LyricWord(
    val startTime: Long,
    val endTime: Long,
    val startIndex: Int,
    val endIndex: Int
) {
    /** Cuánto del tramo se ha cantado ya en [positionMillis], de 0 a 1. */
    fun progressAt(positionMillis: Long): Float {
        val span = endTime - startTime
        if (span <= 0L) return if (positionMillis >= startTime) 1f else 0f
        return ((positionMillis - startTime).toFloat() / span).coerceIn(0f, 1f)
    }
}

/**
 * Un verso de la letra.
 *
 * @param words tramos con tiempo propio, vacío si el archivo solo sincroniza por línea — que es el
 *        caso de casi todo lo que llega de LrcLib. Nunca se rellena a la fuerza partiendo el verso
 *        por espacios: inventar tiempos daría un karaoke que va a su aire, peor que no tenerlo.
 */
data class LyricLine(
    val startTime: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
) {
    /** ¿Este verso se puede barrer palabra a palabra? */
    val hasWordTiming: Boolean get() = words.isNotEmpty()
}
