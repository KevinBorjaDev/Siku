package com.qhana.siku.data.model

/**
 * Criterios de orden de la pestaña **Todas** (y de Favoritos).
 *
 * Cada valor nombra una COLUMNA y nada más: la dirección viaja aparte, en [SortSpec] — ver su KDoc
 * para por qué. Cada uno declara con qué sentido se estrena: por título se empieza de la A a la Z,
 * y por cualquier medida (cuántas veces, cuándo) de mayor a menor, que es lo que se busca al
 * pedirla.
 *
 * El orden en el que se declaran es el del menú del chip.
 */
enum class SortOrder(override val defaultAscending: Boolean) : SortCriterion {
    TITLE(defaultAscending = true),
    DATE_ADDED(defaultAscending = false),
    PLAY_COUNT(defaultAscending = false),
    LAST_PLAYED(defaultAscending = false);

    companion object {
        val DEFAULT: SortSpec<SortOrder> = SortSpec.of(TITLE)

        /**
         * Traduce lo guardado antes de separar criterio y dirección. Ver [parseSortSpec].
         */
        fun fromLegacy(name: String): SortSpec<SortOrder>? = when (name) {
            "TITLE_ASC" -> SortSpec(TITLE, true)
            "TITLE_DESC" -> SortSpec(TITLE, false)
            "DATE_ADDED_ASC" -> SortSpec(DATE_ADDED, true)
            "DATE_ADDED_DESC" -> SortSpec(DATE_ADDED, false)
            "MOST_PLAYED" -> SortSpec(PLAY_COUNT, false)
            "RECENTLY_PLAYED" -> SortSpec(LAST_PLAYED, false)
            else -> null
        }
    }
}
