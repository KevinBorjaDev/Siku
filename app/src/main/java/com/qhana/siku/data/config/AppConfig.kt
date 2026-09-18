package com.qhana.siku.data.config

object AppConfig {
    /**
     * Tiempo para ESTABLECER la conexión (TCP + TLS) con Graph, el host de descargas y el
     * `DataSource` de ExoPlayer. Es el default de OkHttp, y se usa precisamente por eso: un valor
     * con autoridad de quien escribió la librería, en un parámetro donde no tenemos nada que medir.
     *
     * Separado de [API_READ_TIMEOUT_SECONDS] porque son preguntas distintas con costes distintos, y
     * durante un tiempo compartieron un único valor de 30 s. **Una conexión que no se establece en
     * diez segundos casi nunca se establece**, así que ahí el margen extra no compra nada y sí se
     * paga: se multiplica por los reintentos y lo acaba esperando quien acaba de pulsar play.
     */
    const val API_CONNECT_TIMEOUT_SECONDS = 10L

    /**
     * Tiempo máximo sin recibir datos de una respuesta ya en curso (read/write).
     *
     * Aquí sí es DEFENSIVO y por eso triplica el default de OkHttp: durante un sync masivo el
     * dispositivo va saturado con decenas de conexiones compitiendo, y un servidor que tarda en
     * empezar a responder es normal en esas condiciones. Cortar antes convertiría una racha de
     * lentitud en fallos, que generan reintentos, que añaden más carga.
     *
     * **Ojo al componerlo con reintentos**: `OneDriveRepository.executeWithRetry` hace hasta tres
     * intentos, así que un cuelgue tarda `3 × esto` más las esperas del backoff antes de rendirse.
     * La latencia del camino de reproducción NO se acota bajando este valor —que protege al sync—
     * sino con el presupuesto explícito de la resolución prioritaria.
     */
    const val API_READ_TIMEOUT_SECONDS = 30L

    // Cuánto se mantienen vivos los sockets ociosos del pool de descargas: cubre el hueco
    // entre que termina un archivo y el worker toma el siguiente sin renegociar TLS.
    // (El timeout de lectura del cliente "download" NO vive aquí: se deriva del watchdog
    // anti-stall, ver MusicDownloader.SOCKET_IDLE_TIMEOUT_MS.)
    const val CONNECTION_KEEP_ALIVE_MINUTES = 5L

    // Cliente "images" (Coil): las carátulas/fotos de artista son peticiones chicas contra
    // CDNs públicos; si una tarda más que esto, la UI ya mostró su placeholder.
    const val IMAGE_TIMEOUT_SECONDS = 20L

    /**
     * Cliente "lyrics" (LrcLib + Deezer): APIs públicas de terceros que devuelven JSON pequeño.
     * Más corto que [API_READ_TIMEOUT_SECONDS] a propósito — Graph sostiene el sync y la reproducción
     * y merece paciencia, mientras que una letra o una foto de artista que tarda es una mejora
     * opcional: rendirse pronto libera el hilo y la UI ya tiene su estado de "no encontrado".
     */
    const val THIRD_PARTY_API_TIMEOUT_SECONDS = 10L

    // Conversión bytes↔GB del tope de descargas. Vive acá porque la usan tanto el ViewModel
    // (persistir el tope) como la UI del slider (derivar su máximo del disco real).
    const val BYTES_PER_GB = 1024f * 1024f * 1024f

    /**
     * Margen al comparar el tamaño de un archivo descargado con el que declara el proveedor.
     * Los tags que se reescriben en local (ReplayGain, carátula, letras) mueven el tamaño unos
     * bytes, así que la igualdad exacta no sirve como criterio de integridad.
     */
    const val FILE_SIZE_TOLERANCE_BYTES = 1024L

    /**
     * true si [length] se queda notablemente CORTO frente a [expectedSize] (descarga truncada).
     * Con tamaño esperado desconocido (<= 0) no se puede juzgar: false.
     *
     * Única definición de "¿están todos los bytes?" en la app. La usan el downloader (para
     * descartar restos truncados) y el recovery de reproducción (para distinguir un archivo
     * completo que el decoder no traga —fallo de formato/hardware— de uno a medio bajar). Cada
     * uno tenía su propia copia del umbral, y con distinta forma: una comparaba la diferencia
     * con signo y la otra su valor absoluto, de modo que un archivo MÁS grande que lo declarado
     * —lo normal tras reescribir tags— contaba como íntegro en un sitio y como sospechoso en el
     * otro. La comparación con signo es la correcta: sobrar bytes no es estar incompleto.
     */
    fun looksTruncated(length: Long, expectedSize: Long): Boolean =
        expectedSize > 0 && expectedSize - length > FILE_SIZE_TOLERANCE_BYTES

    /**
     * Carpeta de OneDrive que se escanea mientras el usuario no elija otra. `Music` es la que
     * OneDrive crea por defecto en las cuentas personales, así que acierta en la mayoría — pero
     * NO en las que tienen la música en `Música`, `Documentos/…` o cualquier otra: por eso la
     * ruta es configurable (ver `MusicPreferences.loadOneDriveFolderPath`).
     *
     * Cadena vacía = raíz del drive (escanear la cuenta entera).
     */
    const val ONEDRIVE_DEFAULT_FOLDER = "Music"

    // UUID fijo para identificar la playlist "Favoritos" (favoritos = playlist con UUID fijo).
    const val FAVORITES_PLAYLIST_UUID = "00000000-0000-0000-0000-000000000001"
    const val FAVORITES_PLAYLIST_NAME = "Favoritos"

    // Centinelas de DATOS (no son texto de UI): valor que se escribe en `songs.artist/album`
    // cuando una canción aún no tiene metadata extraída. Se comparan en la lógica para decidir
    // si falta metadata (p.ej. PreparationChain, SongRepository). El texto VISIBLE de fallback
    // ("Artista desconocido") vive en strings.xml y es independiente de esto.
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"

    /**
     * "Sin álbum" NO es un álbum, y por tanto no sirve como clave para agrupar nada.
     *
     * [UNKNOWN_ALBUM] es un string normal, así que una guardia `album.isBlank()` no lo filtra:
     * cualquier código que reparta carátulas por álbum (herencia en `ArtworkHealingManager`,
     * `setAlbumArt` desde `LightMetadataFetcher` o desde el análisis post-descarga) acabaría
     * estampando la portada de un archivo sin tags en TODAS las canciones sin tags de la
     * biblioteca, cruzando incluso fuentes distintas. Y el error no se corrige solo, porque
     * `setAlbumArt` respeta las portadas ya escritas.
     */
    /**
     * Nombres que NO son un artista sino "varios": el ALBUMARTIST de las recopilaciones. En la
     * biblioteca sí se agrupan —es lo que se espera en el modo "por álbum"—, pero buscarles foto en
     * Deezer devuelve una imagen genérica que no identifica nada. Van en minúsculas porque se
     * comparan así (aquí y en el SQL de `ArtistDao`, que recibe esta misma lista).
     */
    val VARIOUS_ARTISTS_NAMES: List<String> = listOf(
        "various artists", "various", "va", "v.a.", "varios artistas", "varios", "artistas varios"
    )

    fun isVariousArtists(name: String): Boolean = name.trim().lowercase() in VARIOUS_ARTISTS_NAMES

    fun isUnknownAlbum(album: String): Boolean =
        album.isBlank() || album.equals(UNKNOWN_ALBUM, ignoreCase = true)
}
