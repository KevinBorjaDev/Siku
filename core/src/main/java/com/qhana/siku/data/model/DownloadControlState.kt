package com.qhana.siku.data.model

/**
 * Estado de control de las descargas masivas, elegido por el usuario desde el Download
 * Manager y persistido (sobrevive al proceso). Las descargas por reproducción
 * (prioritarias) NO se ven afectadas por este estado — solo el pipeline masivo del sync.
 *
 * - [ACTIVE]  El sync masivo descarga normalmente (por defecto).
 * - [PAUSED]  Pausa temporal: el productor deja de encolar trabajo nuevo. Toggle rápido
 *             "Reanudar" en el Download Manager. No muestra banner global.
 * - [STOPPED] Detenido explícito: además de frenar, expone un banner global de
 *             "descargas pendientes" con la opción de reanudar o cancelar definitivamente.
 */
enum class DownloadControlState {
    ACTIVE,
    PAUSED,
    STOPPED;

    /** Solo [ACTIVE] permite que corra la descarga masiva. */
    val allowsMassDownload: Boolean get() = this == ACTIVE
}
