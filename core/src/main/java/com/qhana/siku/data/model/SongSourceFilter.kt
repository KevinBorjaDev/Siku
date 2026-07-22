package com.qhana.siku.data.model

/**
 * Filtros de ORIGEN de la pestaña Todas (chips sobre la lista). Son combinables: varios
 * seleccionados = UNIÓN (p. ej. LOCAL + DOWNLOADED = todo lo reproducible sin red).
 * El estado "todas" es simplemente el set vacío — por eso no hay valor ALL.
 */
enum class SongSourceFilter {
    /** Música de la fuente local (carpeta del dispositivo, `sourceType == LOCAL`). */
    LOCAL,

    /** Música de nube ya descargada (audio `file://` en disco). */
    DOWNLOADED,

    /** Música de nube SIN descargar (se reproduce por streaming). */
    STREAMING
}
