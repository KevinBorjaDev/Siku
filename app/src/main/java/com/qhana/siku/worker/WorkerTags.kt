package com.qhana.siku.worker

/**
 * Constantes centralizadas para etiquetas y nombres únicos de Workers.
 * Evita el uso de "magic strings" dispersos en la app.
 */
object WorkerTags {
    // Escaneo
    const val SCAN_WORK_NAME = "OneDriveScan"

    // Extracción de colores de carátulas (unique work)
    const val ARTWORK_WORK_NAME = "ArtworkColorExtraction"

    // Reparación
    const val REPAIR_TAG = "repair_corrupt"
    
    // Descargas
    const val DOWNLOAD_TRACKING_TAG = "download_tracking"

    // Helper para generar tags dinámicos
    fun downloadTag(songId: String) = "download_$songId"
    fun repairTag(songId: String) = "repair_$songId"
}