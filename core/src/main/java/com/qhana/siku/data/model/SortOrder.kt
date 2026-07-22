package com.qhana.siku.data.model

/**
 * Opciones de ordenamiento
 */
enum class SortOrder(val displayName: String) {
    TITLE_ASC("Título A-Z"),
    TITLE_DESC("Título Z-A"),
    DATE_ADDED_DESC("Más recientes"),
    DATE_ADDED_ASC("Más antiguos"),
    MOST_PLAYED("Más reproducidas"),
    RECENTLY_PLAYED("Escuchadas recientemente")
}

