package com.qhana.siku.data.config

object AppConfig {
    const val API_TIMEOUT_SECONDS = 30L

    // Cuánto se mantienen vivos los sockets ociosos del pool de descargas: cubre el hueco
    // entre que termina un archivo y el worker toma el siguiente sin renegociar TLS.
    // (El timeout de lectura del cliente "download" NO vive aquí: se deriva del watchdog
    // anti-stall, ver MusicDownloader.SOCKET_IDLE_TIMEOUT_MS.)
    const val CONNECTION_KEEP_ALIVE_MINUTES = 5L

    // Cliente "images" (Coil): las carátulas/fotos de artista son peticiones chicas contra
    // CDNs públicos; si una tarda más que esto, la UI ya mostró su placeholder.
    const val IMAGE_TIMEOUT_SECONDS = 20L

    // UUID fijo para identificar la playlist "Favoritos" (favoritos = playlist con UUID fijo).
    const val FAVORITES_PLAYLIST_UUID = "00000000-0000-0000-0000-000000000001"
    const val FAVORITES_PLAYLIST_NAME = "Favoritos"

    // Centinelas de DATOS (no son texto de UI): valor que se escribe en `songs.artist/album`
    // cuando una canción aún no tiene metadata extraída. Se comparan en la lógica para decidir
    // si falta metadata (p.ej. PreparationChain, SongRepository). El texto VISIBLE de fallback
    // ("Artista desconocido") vive en strings.xml y es independiente de esto.
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
}
