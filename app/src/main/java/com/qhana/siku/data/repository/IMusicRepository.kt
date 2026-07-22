package com.qhana.siku.data.repository

import com.qhana.siku.data.model.SourceType

/**
 * Interfaz para el repositorio de música (Facade).
 * Segregada en interfaces especializadas para cumplir con ISP (Interface Segregation Principle).
 */
interface IMusicRepository : ISongRepository, IPlaylistRepository, ILocalMetadataRepository {
    /**
     * Limpia todos los datos del usuario (Canciones, Playlists, Metadata, etc.)
     */
    suspend fun clearAllUserData()

    /**
     * Desconecta UNA fuente: borra sus canciones y deja intactas las de las demás,
     * además de playlists, favoritos y colores. Usado al cerrar sesión de OneDrive o
     * al quitar la carpeta local — con varias fuentes, un logout no puede ser un
     * borrado total (ver [clearAllUserData], que sí lo es).
     */
    suspend fun clearSourceData(sourceType: SourceType)
}
