package com.qhana.siku.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistCreditRole
import com.qhana.siku.data.model.ArtistSeparator
import com.qhana.siku.data.model.ArtistSplitConfig
import com.qhana.siku.data.model.DetailSongSort
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.EqProfile
import com.qhana.siku.data.model.EqSettings
import com.qhana.siku.data.model.LibraryTabState
import com.qhana.siku.player.audio.EqualizerAudioProcessor
import com.qhana.siku.player.audio.clarity.Clarity
import com.qhana.siku.data.model.LibraryTabsConfig
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SortOrder
import com.qhana.siku.data.model.SortSpec
import com.qhana.siku.data.model.parseSortSpec
import com.qhana.siku.player.manager.ContextResumeSlot
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Preferencias de usuario persistidas con DataStore.
 *
 * Mantiene API síncrona via caché en memoria para callers que no pueden suspender
 * (p.ej. `init` de ViewModels). La caché se llena en el constructor con `runBlocking`
 * una sola vez por instancia (y MusicPreferences es `@Singleton`).
 *
 * Los datos existentes en SharedPreferences `music_player_prefs` se migran
 * automáticamente en el primer acceso gracias a `SharedPreferencesMigration`.
 */
class MusicPreferences(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Un archivo de DataStore con su caché en memoria delante.
     *
     * Existe porque hay DOS archivos y toda esta mecánica —caché como única verdad,
     * [Store.diskStateKnown], la cola FIFO de un solo consumidor— es delicada y se ganó a base de
     * bugs: tenerla escrita dos veces sería garantizar que un arreglo se aplique a una copia y no a
     * la otra.
     *
     * **Por qué dos archivos**: DataStore **reescribe el archivo ENTERO en cada escritura**
     * (`FileStorage.writeScope` serializa todo a un `.tmp`, hace `fsync` y lo renombra), así que el
     * coste de guardar un dato no depende de ese dato sino de **con quién comparte archivo**. La
     * posición de reproducción se guarda en cada cambio de canción y en cada pausa, y vivía junto a
     * `last_queue_ids` y `original_queue_ids`, que son la cola ENTERA y la cola original de shuffle
     * serializadas como ids namespaced y sin tope: con la biblioteca en aleatorio, cientos de ids de
     * ~80 caracteres. Cada pausa reescribía todo eso —y las otras 82 claves— para actualizar tres
     * números.
     *
     * Eso además dejaba sin efecto una optimización que el código creía tener:
     * `SessionStateManager.savePosition` existe para "no re-serializar los IDs de la cola… con colas
     * grandes, un map+escritura por cada pausa", y solo se ahorraba el `map` en memoria — el volcado
     * escribía la cola igual. Con la sesión en su propio archivo ese ahorro es real, y de paso la
     * configuración deja de reescribirse por algo que no es configuración.
     */
    private inner class Store(
        private val dataStore: DataStore<Preferences>,
        private val tag: String
    ) {
        /**
         * ESTADO EN MEMORIA = la única fuente de verdad, y también lo que emiten los flows
         * reactivos. Se llena sync al construir (una vez; la clase es `@Singleton`) y a partir de
         * ahí solo lo escribe [update]. El disco es un destino, no una fuente.
         *
         * Antes había DOS verdades: `loadX()` leía este caché (síncrono, ya actualizado) mientras
         * los `xFlow` leían `dataStore.data` (asíncrono, va por detrás). Un `collect` de
         * `dataStore.data` reasignaba además el caché, así que el disco podía hacer RETROCEDER el
         * estado en RAM — dos escritores para el mismo estado, justo lo que la app tiene prohibido
         * en otro sitio por haber costado ya un bug. Con esto, escribir una preferencia se ve en el
         * mismo frame en todo lo que la observe, sin esperar al disco ni depender de su orden.
         */
        val prefs = MutableStateFlow(emptyPreferences())

        val cache: Preferences get() = prefs.value

        /**
         * ¿La carga inicial leyó el disco de verdad?
         *
         * Es la diferencia entre "el usuario no tiene nada guardado" y "no pude leer lo que hay",
         * que es la MISMA `emptyPreferences()` y hasta la auditoría del 30 jul 2026 no se
         * distinguían. Con un error transitorio de IO al arrancar, el caché nacía vacío y como cada
         * volcado escribe el snapshot COMPLETO (`clear()` + volcado), la primera preferencia que
         * tocara el usuario persistía ese vacío: se iban de golpe las carpetas locales (la app
         * volvía al onboarding), el delta token, las curvas del EQ y los colores manuales. Un fallo
         * pasajero se convertía en pérdida definitiva.
         *
         * Mientras esto sea `false` el estado en RAM es una suposición, no la verdad, y [update] se
         * niega a volcarlo (ver allí).
         */
        @Volatile
        var diskStateKnown = false
            private set

        /**
         * Cola FIFO de volcados a disco con UN SOLO consumidor. Cada escritura vuelca el snapshot
         * COMPLETO (clear + volcado), así que el orden importa: con un `launch` por escritura sobre
         * `Dispatchers.IO` —lo que había antes— dos volcados casi simultáneos corren en hilos
         * distintos y nada garantiza que el más nuevo llegue el último. Cuando se invertían, el
         * snapshot viejo pisaba al nuevo y la preferencia recién guardada desaparecía del disco (y,
         * cuando los flows leían de ahí, también de la UI). Ya había mordido con las carpetas
         * locales y se parcheó fusionando esas dos escrituras a mano; esto lo arregla para todas.
         */
        private val diskWrites = Channel<Preferences>(Channel.UNLIMITED)

        /**
         * Lee el disco reintentando: el primer acceso a DataStore también corre la migración desde
         * SharedPreferences, así que un fallo aquí no es necesariamente permanente.
         */
        suspend fun loadInitialSnapshot() {
            repeat(INITIAL_READ_ATTEMPTS) { attempt ->
                try {
                    prefs.value = dataStore.data.first()
                    diskStateKnown = true
                    return
                } catch (e: Exception) {
                    android.util.Log.e(
                        tag,
                        "Error leyendo preferencias (intento ${attempt + 1}/$INITIAL_READ_ATTEMPTS)",
                        e
                    )
                }
            }
            // Se sigue adelante con el caché vacío —la app tiene que arrancar— pero SIN permiso para
            // escribirlo encima de lo que haya en disco.
            android.util.Log.e(
                tag,
                "Preferencias ilegibles: se arranca con valores por defecto y NO se persistirá nada " +
                    "hasta poder leer el disco"
            )
        }

        fun startDiskWriter() {
            scope.launch {
                for (snapshot in diskWrites) {
                    try {
                        dataStore.edit { target ->
                            target.clear()
                            @Suppress("UNCHECKED_CAST")
                            snapshot.asMap().forEach { (k, v) -> target[k as Preferences.Key<Any>] = v }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(tag, "Error persistiendo preferencias", e)
                    }
                }
            }
        }

        fun update(block: (MutablePreferences) -> Unit) {
            // Aplicar en memoria de forma SÍNCRONA para que `loadX()` posteriores vean el cambio
            // inmediatamente. Sin esto hay race condition: por ejemplo `clearDeltaToken()` seguido
            // de `loadDeltaToken()` en la misma función devolvería el token viejo, porque la
            // escritura a disco corre en background.
            val mutated = cache.toMutablePreferences()
            block(mutated)
            val newCache = mutated.toPreferences()
            prefs.value = newCache
            // Sin una lectura buena del disco, este snapshot NO describe lo que el usuario tiene
            // guardado: le faltaría todo lo que no se pudo leer. Y como el volcado hace `clear()` +
            // snapshot completo, persistirlo BORRARÍA esas preferencias en vez de dejarlas intactas.
            // La app funciona en memoria con los valores por defecto; el disco se queda como está.
            if (!diskStateKnown) return
            // Se persiste el SNAPSHOT (no se re-ejecuta `block`): así un `block` no idempotente
            // —incremento, append— no se aplica dos veces sobre bases distintas (memoria vs. disco)
            // divergiendo. `trySend` sobre un canal UNLIMITED nunca falla ni bloquea.
            diskWrites.trySend(newCache)
        }
    }

    /** Configuración: todo lo que el usuario elige y espera encontrar igual la próxima vez. */
    private val main = Store(context.musicPrefsDataStore, "MusicPreferences")

    /**
     * Estado de la SESIÓN de reproducción (cola, índice, posición, shuffle).
     *
     * Archivo aparte porque es lo único que se escribe con frecuencia y a la vez lo más pesado que
     * se guarda; ver el KDoc de [Store]. No lleva `SharedPreferencesMigration`: aquella migración ya
     * la hizo el archivo principal, y estas claves se traen de allí en [migrateSessionKeysFromMain].
     */
    private val session = Store(context.sessionDataStore, "PlaybackSession")

    /**
     * Slots de reanudación por contexto (una tarjeta de "Seguir escuchando" = un slot). Archivo
     * aparte por el mismo motivo que [session]: es cola serializada, lo más pesado que se guarda.
     * A diferencia de [session] solo se escribe al CAMBIAR de contexto o al parar —nunca en cada
     * tick de posición—, así que su peso no molesta a nadie. Ver [saveContextResume].
     */
    private val contextSlots = Store(context.contextSlotsDataStore, "PlaybackContextSlots")

    private val cache: Preferences get() = main.cache

    init {
        // Los dos snapshots dentro del MISMO `runBlocking`, y en paralelo: este bloqueo lo paga el
        // arranque de la app, así que el segundo archivo no puede costar una segunda espera.
        runBlocking {
            val sessionLoad = scope.launch { session.loadInitialSnapshot() }
            val slotsLoad = scope.launch { contextSlots.loadInitialSnapshot() }
            main.loadInitialSnapshot()
            sessionLoad.join()
            slotsLoad.join()
        }
        main.startDiskWriter()
        session.startDiskWriter()
        contextSlots.startDiskWriter()
        migrateSessionKeysFromMain()
    }

    /**
     * Trae la sesión guardada del archivo principal al suyo, una sola vez.
     *
     * Un usuario que actualiza tiene su cola en `music_player_prefs`; sin esto perdería lo que
     * estaba escuchando al abrir la versión nueva. Se copia solo si el archivo de sesión está vacío
     * —o sea, la primera vez— y se borra del principal en la misma pasada, porque dejarla ahí
     * mantendría exactamente el peso que todo esto viene a quitar.
     *
     * Si el disco no se pudo leer no se hace nada: con `diskStateKnown` en `false` el caché es una
     * suposición, y migrar desde una suposición borraría la sesión de verdad.
     */
    private fun migrateSessionKeysFromMain() {
        if (!main.diskStateKnown || !session.diskStateKnown) return
        if (session.cache.asMap().isNotEmpty()) return
        val pending = SESSION_KEYS.filter { main.cache.contains(it) }
        if (pending.isEmpty()) return
        session.update { target ->
            pending.forEach { key ->
                // El tipo se declara `Any` a propósito: sin él, leer con una `Key<*>` deja el valor
                // con el tipo CAPTURADO de la proyección y no casa con la clave ya casteada.
                val value: Any = main.cache[key] ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                target[key as Preferences.Key<Any>] = value
            }
        }
        main.update { target -> pending.forEach { target.remove(it) } }
    }

    private fun update(block: (MutablePreferences) -> Unit) = main.update(block)

    /**
     * Flow derivado del estado en memoria. `distinctUntilChanged` porque `prefs` emite en CADA
     * escritura de cualquier preferencia y un observador de una sola clave no debe recomponer
     * porque se guardó la posición de reproducción.
     */
    private fun <T> prefFlow(read: (Preferences) -> T): Flow<T> =
        main.prefs.map(read).distinctUntilChanged()

    // --- Sort order ---

    // Criterio y dirección van en UNA sola clave (`CRITERIO:asc|desc`, ver [SortSpec.serialize]) y
    // no en dos: cada volcado reescribe el archivo ENTERO, así que separarlas serían dos escrituras
    // por cada toque del chip — el mismo problema que ya obligó a fusionar las dos de las carpetas
    // locales. `fromLegacy` conserva lo que el usuario tenía elegido cuando la dirección viajaba
    // dentro del nombre del criterio (`TITLE_DESC`, `RECENTLY_ADDED`): sin él la preferencia se
    // perdería en silencio al actualizar, porque un nombre desconocido cae al default sin fallar.

    fun saveSortOrder(order: SortSpec<SortOrder>, filter: SongFilter) = update {
        it[stringPreferencesKey(KEY_SORT_ORDER + filter.name)] = order.serialize()
    }

    fun loadSortOrder(filter: SongFilter): SortSpec<SortOrder> = parseSortSpec(
        cache[stringPreferencesKey(KEY_SORT_ORDER + filter.name)],
        SortOrder.DEFAULT,
        SortOrder::fromLegacy
    )

    fun saveArtistSortOrder(order: SortSpec<ArtistSortOrder>) =
        update { it[KEY_ARTIST_SORT] = order.serialize() }

    fun loadArtistSortOrder(): SortSpec<ArtistSortOrder> = parseSortSpec(
        cache[KEY_ARTIST_SORT],
        ArtistSortOrder.DEFAULT,
        ArtistSortOrder::fromLegacy
    )

    fun saveAlbumSortOrder(order: SortSpec<AlbumSortOrder>) =
        update { it[KEY_ALBUM_SORT] = order.serialize() }

    fun loadAlbumSortOrder(): SortSpec<AlbumSortOrder> = parseSortSpec(
        cache[KEY_ALBUM_SORT],
        AlbumSortOrder.DEFAULT,
        AlbumSortOrder::fromLegacy
    )

    /**
     * Orden de la lista de canciones en los detalles de artista, álbum y género.
     *
     * **UNO para las tres pantallas y no uno por entidad**: por artista serían N claves en
     * DataStore, que reescribe el archivo ENTERO en cada volcado. Eso obliga a que `DEFAULT` siga
     * siendo válido en las tres pantallas — ver [DetailSongSort].
     */
    fun saveDetailSongSort(order: SortSpec<DetailSongSort>) =
        update { it[KEY_DETAIL_SONG_SORT] = order.serialize() }

    fun loadDetailSongSort(): SortSpec<DetailSongSort> = parseSortSpec(
        cache[KEY_DETAIL_SONG_SORT],
        DEFAULT_DETAIL_SONG_SORT,
        DetailSongSort::fromLegacy
    )

    // --- Keep screen on ---

    fun saveKeepScreenOn(enabled: Boolean) = update {
        it[KEY_KEEP_SCREEN_ON] = enabled
    }

    fun loadKeepScreenOn(): Boolean = cache[KEY_KEEP_SCREEN_ON] ?: false

    // --- Carátulas: cuántos archivos había en el directorio la última vez que se comprobó ---
    // Lo usa ArtworkHealingManager para saltarse el barrido de huérfanas cuando nada desapareció.
    // −1 = nunca medido (primer arranque tras la actualización), y entonces sí se barre.

    fun loadCoverFileCount(): Int = cache[KEY_COVER_FILE_COUNT] ?: -1

    fun saveCoverFileCount(count: Int) = update { it[KEY_COVER_FILE_COUNT] = count }

    // --- Colores manuales ---
    // Ids de canciones cuyo color eligió el USUARIO (picker del NowPlaying). Vive en DataStore,
    // no como columna de `songs`, por dos razones: no fuerza un bump destructivo del schema, y
    // sobrevive a la recreación de la BD. Es solo la MARCA; el color en sí sigue en la BD.

    fun loadManualColorIds(): Set<String> = cache[KEY_MANUAL_COLOR_IDS] ?: emptySet()

    fun addManualColorId(songId: String) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) + songId
    }

    fun addManualColorIds(songIds: Collection<String>) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) + songIds
    }

    fun removeManualColorId(songId: String) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) - songId
    }

    fun clearManualColorIds() = update { it.remove(KEY_MANUAL_COLOR_IDS) }

    // --- Session (queue + position) ---
    // Estos accesores van contra `session`, NO contra `update`/`cache`, que son el
    // archivo de configuración. Es lo que separa la escritura frecuente y pesada del resto; ver el
    // KDoc de [Store]. Si se añade una clave de sesión, va aquí Y en [SESSION_KEYS].

    fun loadQueueIds(): List<String> {
        val raw = session.cache[KEY_LAST_QUEUE] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLastState(index: Int, position: Long, songId: String? = null) = session.update {
        it[KEY_LAST_INDEX] = index
        it[KEY_LAST_POSITION] = position
        // ID de la canción actual: la restauración lo prefiere sobre el índice, que se
        // desplaza si alguna canción de la cola guardada fue borrada de la BD.
        if (songId != null) it[KEY_LAST_SONG_ID] = songId else it.remove(KEY_LAST_SONG_ID)
    }

    fun loadLastState(): Triple<Int, Long, String?> {
        val index = session.cache[KEY_LAST_INDEX] ?: 0
        val position = session.cache[KEY_LAST_POSITION] ?: 0L
        return Triple(index, position, session.cache[KEY_LAST_SONG_ID])
    }

    /**
     * Estado de aleatorio de la sesión. Se guarda junto al ORDEN ORIGINAL de la cola porque la
     * cola persistida ya está barajada: sin el orden original, al restaurar no habría a dónde
     * volver al apagar el aleatorio (y el botón quedaría encendido mintiendo).
     */
    /**
     * Guarda la sesión ENTERA —cola, aleatorio e índice/posición— en UNA sola escritura.
     *
     * `SessionStateManager.saveSessionState` encadenaba `saveQueueIds` + `saveShuffleState` +
     * `saveLastState`, y como cada `update` encola su propio volcado y DataStore reescribe el
     * archivo entero, eran TRES serializaciones con sus tres `fsync` para un solo guardado — con
     * ocho call sites en `MusicController`. Las tres describen el mismo instante, así que se
     * escriben juntas: además de costar un tercio, deja de existir la ventana en la que el disco
     * tiene la cola nueva con el índice viejo.
     *
     * [currentIndex] negativo = no hay item actual y no se toca esa parte, igual que hacía el
     * `if (currentIndex >= 0)` del caller.
     */
    fun saveSession(
        queueIds: List<String>,
        shuffleEnabled: Boolean,
        originalQueueIds: List<String>,
        currentIndex: Int,
        position: Long,
        currentSongId: String?
    ) = session.update {
        it[KEY_LAST_QUEUE] = queueIds.joinToString(QUEUE_DELIMITER)
        it[KEY_SHUFFLE_ENABLED] = shuffleEnabled
        if (shuffleEnabled && originalQueueIds.isNotEmpty()) {
            it[KEY_ORIGINAL_QUEUE] = originalQueueIds.joinToString(QUEUE_DELIMITER)
        } else {
            it.remove(KEY_ORIGINAL_QUEUE)
        }
        if (currentIndex >= 0) {
            it[KEY_LAST_INDEX] = currentIndex
            it[KEY_LAST_POSITION] = position
            if (currentSongId != null) it[KEY_LAST_SONG_ID] = currentSongId
            else it.remove(KEY_LAST_SONG_ID)
        }
    }

    fun loadShuffleEnabled(): Boolean = session.cache[KEY_SHUFFLE_ENABLED] ?: false

    fun loadOriginalQueueIds(): List<String> {
        val raw = session.cache[KEY_ORIGINAL_QUEUE] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }
    }

    // --- Audio offload (batería) ---

    /**
     * "El offload de audio está ROTO en este dispositivo". Lo marca el watchdog de
     * `MusicPlaybackService` la primera (y única) vez que detecta el cuelgue.
     *
     * El offload manda los bytes comprimidos al DSP y deja dormir la CPU (buen ahorro de
     * batería), pero algunos DSP (visto en Xiaomi/MIUI con MP3) ACEPTAN el AudioTrack y luego
     * no consumen ni reportan nada → el player se queda en BUFFERING para siempre. Media3 no
     * puede detectarlo (su fallback solo cubre "el device declara no soportar el formato").
     *
     * Persistir el veredicto es lo que hace viable tener offload activo por defecto: el
     * dispositivo roto tropieza UNA vez y nunca más; el sano conserva el ahorro.
     */
    fun saveOffloadBroken(broken: Boolean) = update { it[KEY_OFFLOAD_BROKEN] = broken }

    fun loadOffloadBroken(): Boolean = cache[KEY_OFFLOAD_BROKEN] ?: false

    // --- Scan / sync state ---

    fun saveDeltaToken(token: String) = update {
        it[KEY_DELTA_TOKEN] = token
    }

    fun loadDeltaToken(): String? = cache[KEY_DELTA_TOKEN]

    fun clearDeltaToken() = update {
        it.remove(KEY_DELTA_TOKEN)
    }

    // --- Fuente local: o TODO el dispositivo (MediaStore), o N carpetas SAF. Excluyentes ---

    /**
     * Tree URIs (`ACTION_OPEN_DOCUMENT_TREE`, con permiso persistido) de las carpetas de música.
     * Vacío = no hay carpetas configuradas.
     *
     * Migra en lectura la clave de carpeta ÚNICA de las versiones anteriores: mientras el set no
     * exista, el valor viejo se presenta como un set de un elemento. La reescritura ocurre sola en
     * el primer [saveLocalFolderUris] y, hasta entonces, un usuario que actualice y no toque nada
     * conserva su carpeta.
     */
    fun loadLocalFolderUris(): Set<String> = readLocalFolderUris(cache)

    /**
     * Reactivo. La configuración de fuentes se toca desde DOS instancias distintas de
     * `SourcesViewModel` (el onboarding vive en su propio `NavBackStackEntry`), así que un
     * `MutableStateFlow` local dejaría a una sin enterarse de lo que hizo la otra — el mismo
     * motivo por el que el toggle del ecualizador se observa por flow.
     */
    val localFolderUrisFlow: Flow<Set<String>> = prefFlow { readLocalFolderUris(it) }

    private fun readLocalFolderUris(prefs: Preferences): Set<String> =
        prefs[KEY_LOCAL_FOLDER_URIS] ?: prefs[KEY_LOCAL_FOLDER_URI]?.let { setOf(it) } ?: emptySet()

    /** Fija el conjunto completo de carpetas (añadir y quitar son operaciones de la capa de arriba). */
    fun saveLocalFolderUris(uris: Set<String>) = update {
        it[KEY_LOCAL_FOLDER_URIS] = uris
        // La clave vieja deja de tener sentido en cuanto se escribe el set: si sobreviviera,
        // vaciar las carpetas haría "reaparecer" la carpeta original en la siguiente lectura.
        it.remove(KEY_LOCAL_FOLDER_URI)
    }

    /**
     * Fija carpetas y modo dispositivo en UNA sola escritura. Ambos ajustes son excluyentes y
     * cambian juntos (añadir carpeta apaga el escaneo completo; activarlo vacía las carpetas).
     *
     * Partirlo en dos [update] encadenados hacía que sus volcados a disco —cada uno un snapshot
     * completo del caché— compitieran en el scope de IO: al no garantizarse el orden, el snapshot
     * más viejo (con la lista de carpetas de ANTES) podía pisar al nuevo, y la carpeta recién
     * añadida "desaparecía" del flujo reactivo aunque el caché en memoria ya la tuviera.
     *
     * El "stash" (carpetas guardadas para restaurar tras un episodio de escaneo del dispositivo) se
     * fija/limpia en la MISMA transacción por el mismo motivo: dos escrituras separadas competirían.
     *
     * @param stashFolders si != null, fija el stash; usar al activar el dispositivo para no perder
     *        las carpetas que había.
     * @param clearStash true al restaurar: vacía el stash una vez devueltas las carpetas.
     */
    fun saveLocalSources(
        folderUris: Set<String>,
        scanWholeDevice: Boolean,
        stashFolders: Set<String>? = null,
        clearStash: Boolean = false
    ) = update {
        it[KEY_LOCAL_FOLDER_URIS] = folderUris
        it.remove(KEY_LOCAL_FOLDER_URI)
        it[KEY_SCAN_WHOLE_DEVICE] = scanWholeDevice
        if (stashFolders != null) it[KEY_LOCAL_FOLDER_URIS_STASH] = stashFolders
        if (clearStash) it.remove(KEY_LOCAL_FOLDER_URIS_STASH)
    }

    /** Carpetas guardadas al activar el escaneo del dispositivo (vacío si no hay nada que restaurar). */
    fun loadStashedFolderUris(): Set<String> = cache[KEY_LOCAL_FOLDER_URIS_STASH] ?: emptySet()

    /** Reactivo: gobierna si en Ajustes se puede apagar el escaneo del dispositivo (habría a dónde volver). */
    val localFolderStashFlow: Flow<Set<String>> =
        prefFlow { it[KEY_LOCAL_FOLDER_URIS_STASH] ?: emptySet() }

    /**
     * ¿Indexar TODA la música del dispositivo (MediaStore) en vez de carpetas concretas?
     * Es el único modo local que necesita permiso de lectura de audio.
     */
    fun loadScanWholeDevice(): Boolean = cache[KEY_SCAN_WHOLE_DEVICE] ?: DEFAULT_SCAN_WHOLE_DEVICE

    /** Reactivo, por el mismo motivo que [localFolderUrisFlow]. */
    val scanWholeDeviceFlow: Flow<Boolean> =
        prefFlow { it[KEY_SCAN_WHOLE_DEVICE] ?: DEFAULT_SCAN_WHOLE_DEVICE }

    fun saveScanWholeDevice(enabled: Boolean) = update {
        it[KEY_SCAN_WHOLE_DEVICE] = enabled
    }

    /**
     * Carpetas EXCLUIDAS del escaneo del dispositivo, como rutas relativas al volumen y
     * normalizadas igual que el id de una canción local, pero sin el prefijo `local:` y sin barra
     * final (`primary/whatsapp/media/whatsapp audio`).
     *
     * Solo aplica al modo dispositivo: en el modo carpetas la inclusión ya es explícita, así que
     * una lista de exclusiones sería un segundo mecanismo para lo mismo.
     *
     * La exclusión es RECURSIVA (una carpeta se lleva sus subcarpetas), que es lo que se espera al
     * excluir `Android/media`: lo que molesta no es esa carpeta sino todo lo que cuelga de ella.
     */
    fun loadExcludedDeviceFolders(): Set<String> = cache[KEY_LOCAL_EXCLUDED_FOLDERS] ?: emptySet()

    /** Reactivo, por el mismo motivo que [localFolderUrisFlow]: se edita desde su propia pantalla. */
    val excludedDeviceFoldersFlow: Flow<Set<String>> =
        prefFlow { it[KEY_LOCAL_EXCLUDED_FOLDERS] ?: emptySet() }

    /** Fija el conjunto completo (marcar y desmarcar son operaciones de la capa de arriba). */
    fun saveExcludedDeviceFolders(folders: Set<String>) = update {
        it[KEY_LOCAL_EXCLUDED_FOLDERS] = folders
    }

    /**
     * Borra la sesión de REPRODUCCIÓN (cola, índice, posición, aleatorio).
     *
     * NO toca el delta token, aunque lo hizo hasta la auditoría del 30 jul 2026: ese token es
     * estado del SYNC y no de la sesión, y esto lo llama `SessionStateManager.clearSession()`
     * desde `MusicController.stop()`, o sea cada vez que se purga la cola entera. Quitar una
     * carpeta local mientras sonaba música local tiraba el token de OneDrive y el siguiente
     * auto-scan re-listaba TODO Graph sin que la nube hubiera cambiado. El logout —el único sitio
     * donde sí hay que limpiarlo— llama a [clearDeltaToken] por su cuenta.
     */
    fun clearQueue() = session.update { target -> SESSION_KEYS.forEach { target.remove(it) } }

    // Los 7 tunables del extractor de color (quantization, objetivos de luminancia, saturación
    // máxima, factor de peso, stiffness, población mínima) se eliminaron al pasar a
    // QuantizerCelebi + Score: ese pipeline no tiene parámetros que ajustar. Las claves viejas
    // quedan huérfanas en el DataStore y se ignoran (no hace falta migrarlas).

    // --- ReplayGain ---

    fun saveReplayGainMode(mode: ReplayGainMode) = update { it[KEY_REPLAYGAIN_MODE] = mode.name }
    fun loadReplayGainMode(): ReplayGainMode = try {
        ReplayGainMode.valueOf(cache[KEY_REPLAYGAIN_MODE] ?: ReplayGainMode.TRACK.name)
    } catch (_: Exception) {
        ReplayGainMode.TRACK
    }

    fun saveReplayGainPreamp(db: Float) = update { it[KEY_REPLAYGAIN_PREAMP] = db }
    fun loadReplayGainPreamp(): Float = cache[KEY_REPLAYGAIN_PREAMP] ?: 0f

    // --- Ecualizador ---

    fun saveEqEnabled(enabled: Boolean) = update { it[KEY_EQ_ENABLED] = enabled }
    fun loadEqEnabled(): Boolean = cache[KEY_EQ_ENABLED] ?: DEFAULT_EQ_ENABLED

    /**
     * Flow reactivo del toggle del EQ: lo cambia PlaybackViewModel (hoja del NowPlaying) y lo
     * observa MusicPlaybackService, que necesita reconstruir la pipeline de audio (y ceder o
     * recuperar el offload) cuando cambia.
     */
    val eqEnabledFlow: Flow<Boolean> =
        prefFlow { it[KEY_EQ_ENABLED] ?: DEFAULT_EQ_ENABLED }

    // --- Toolbar del NowPlaying (orden + barra/overflow de cada acción) ---
    // Reactivo: el NowPlaying observa el flow y la barra se reordena en vivo al guardar en Ajustes.
    val toolbarConfigFlow: Flow<List<ToolbarActionState>> =
        prefFlow { PlayerToolbarConfig.decode(it[KEY_TOOLBAR_CONFIG]) }

    fun loadToolbarConfig(): List<ToolbarActionState> =
        PlayerToolbarConfig.decode(cache[KEY_TOOLBAR_CONFIG])

    fun saveToolbarConfig(list: List<ToolbarActionState>) = update {
        it[KEY_TOOLBAR_CONFIG] = PlayerToolbarConfig.encode(list)
    }

    // --- Contextos reproducidos recientes (sección "Seguir escuchando" del home) ---
    // Historial de "lugares" reanudables (álbum/artista/lista/favoritos/aleatorio/biblioteca), de
    // más reciente a más antiguo. Reactivo: la home se actualiza sola al grabar un contexto.
    val recentContextsFlow: Flow<List<PlaybackContext>> =
        prefFlow { PlaybackContext.decode(it[KEY_RECENT_CONTEXTS]) }

    /** Antepone un contexto al historial (dedup por identidad + tope). Read-modify-write atómico. */
    fun recordContext(ctx: PlaybackContext) {
        var keptKeys: Set<String> = emptySet()
        update {
            val next = PlaybackContext.prepend(PlaybackContext.decode(it[KEY_RECENT_CONTEXTS]), ctx)
            it[KEY_RECENT_CONTEXTS] = PlaybackContext.encode(next)
            keptKeys = next.mapTo(HashSet()) { c -> c.key }
        }
        // Un contexto que se cae del carrusel no volverá a ser reanudable, así que su slot es peso
        // muerto: se poda aquí, que es el único sitio que sabe qué contextos siguen vivos.
        pruneContextResume(keptKeys)
    }

    fun clearRecentContexts() {
        update { it.remove(KEY_RECENT_CONTEXTS) }
        clearContextResume()
    }

    // --- Reanudación por contexto ("Seguir escuchando"): slots + contexto activo ---
    // Todos los slots en UN JSON (como KEY_EQ_ROUTE_PROFILES). El contexto ACTIVO no lleva slot:
    // ese vive en la sesión global, que ya se restaura al reabrir. Un slot es un contexto del que
    // la reproducción se ALEJÓ (o que se paró), congelado en ese instante.

    /** Clave del contexto cuya cola es la que está viva ahora (`null` = reproducción sin contexto). */
    fun loadActiveContextKey(): String? = contextSlots.cache[KEY_ACTIVE_CONTEXT]

    fun saveActiveContextKey(key: String?) = contextSlots.update {
        if (key.isNullOrBlank()) it.remove(KEY_ACTIVE_CONTEXT) else it[KEY_ACTIVE_CONTEXT] = key
    }

    fun loadContextResume(key: String): ContextResumeSlot? {
        val raw = contextSlots.cache[KEY_CONTEXT_SLOTS] ?: return null
        return try {
            val root = JSONObject(raw)
            if (!root.has(key)) return null
            val o = root.getJSONObject(key)
            ContextResumeSlot(
                queueIds = splitQueue(o.optString("q")),
                originalQueueIds = splitQueue(o.optString("o")),
                shuffleEnabled = o.optBoolean("s", false),
                index = o.optInt("i", 0),
                position = o.optLong("p", 0L),
                songId = o.optString("sid").ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Read-modify-write atómico: conserva los slots de los demás contextos. */
    fun saveContextResume(key: String, slot: ContextResumeSlot) = contextSlots.update { mutable ->
        val root = try {
            JSONObject(mutable[KEY_CONTEXT_SLOTS] ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        root.put(
            key,
            JSONObject()
                .put("q", slot.queueIds.joinToString(QUEUE_DELIMITER))
                .put("o", slot.originalQueueIds.joinToString(QUEUE_DELIMITER))
                .put("s", slot.shuffleEnabled)
                .put("i", slot.index)
                .put("p", slot.position)
                .apply { slot.songId?.let { put("sid", it) } }
        )
        mutable[KEY_CONTEXT_SLOTS] = root.toString()
    }

    fun pruneContextResume(keepKeys: Set<String>) = contextSlots.update { mutable ->
        val raw = mutable[KEY_CONTEXT_SLOTS] ?: return@update
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            mutable.remove(KEY_CONTEXT_SLOTS); return@update
        }
        val stale = root.keys().asSequence().filterNot { it in keepKeys }.toList()
        if (stale.isEmpty()) return@update
        stale.forEach { root.remove(it) }
        if (root.length() == 0) mutable.remove(KEY_CONTEXT_SLOTS)
        else mutable[KEY_CONTEXT_SLOTS] = root.toString()
    }

    /** Borra los slots congelados. NO toca [KEY_ACTIVE_CONTEXT]: eso es estado vivo, no historial. */
    fun clearContextResume() = contextSlots.update { it.remove(KEY_CONTEXT_SLOTS) }

    private fun splitQueue(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList()
        else raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }

    /** Nº de bandas del EQ propio (5 o 10). */
    fun saveEqBandCount(count: Int) = update { it[KEY_EQ_BAND_COUNT] = count }
    fun loadEqBandCount(): Int = EqualizerAudioProcessor.normalizedBandCount(
        cache[KEY_EQ_BAND_COUNT] ?: EqualizerAudioProcessor.BANDS_5_COUNT
    )

    // Refuerzos de graves/agudos: NO van por modo de bandas (a diferencia de las ganancias), son
    // dos peakings anchos que se suman a cualquier curva, así que alternar 5↔10 los conserva.
    fun saveEqBassBoost(db: Float) = update { it[KEY_EQ_BASS_BOOST] = db }
    fun loadEqBassBoost(): Float = cache[KEY_EQ_BASS_BOOST] ?: 0f

    fun saveEqTrebleBoost(db: Float) = update { it[KEY_EQ_TREBLE_BOOST] = db }
    fun loadEqTrebleBoost(): Float = cache[KEY_EQ_TREBLE_BOOST] ?: 0f

    // Centro de cada refuerzo en Hz. Devuelven null cuando el usuario nunca lo tocó, y el DEFAULT
    // lo pone el consumidor: los valores por defecto y los rangos válidos son del processor, y
    // esta clase es de la capa de datos — no debe importar `player`. El processor hace `coerceIn`
    // al rango de todos modos, así que un valor fuera de rango en disco se sanea al aplicarlo.
    fun saveEqBassFreq(hz: Double) = update { it[KEY_EQ_BASS_FREQ] = hz }
    fun loadEqBassFreq(): Double? = cache[KEY_EQ_BASS_FREQ]

    fun saveEqTrebleFreq(hz: Double) = update { it[KEY_EQ_TREBLE_FREQ] = hz }
    fun loadEqTrebleFreq(): Double? = cache[KEY_EQ_TREBLE_FREQ]

    // Preamp del EQ: ganancia global (solo negativa) que devuelve el headroom que consume la
    // curva. Como los refuerzos, es independiente del modo de bandas.
    fun saveEqPreamp(db: Float) = update { it[KEY_EQ_PREAMP] = db }
    fun loadEqPreamp(): Float = cache[KEY_EQ_PREAMP] ?: 0f

    /**
     * Limitador del EQ. Default APAGADO, y el default ES la decisión de diseño: la app informa y
     * no corrige por detrás, así que nadie recibe una no linealidad en su cadena sin haberla
     * pedido. El aviso de headroom dice cuándo haría falta y el medidor enseña cuánto reduciría
     * ANTES de encenderlo, de modo que la decisión se toma con el dato delante.
     *
     * Se valoró y descartó el default ENCENDIDO, que protegería a quien escucha por Bluetooth con
     * volumen absoluto (donde la atenuación digital del mixer no existe) sin abrir nunca esta
     * pantalla. Coste asumido: ese usuario se queda sin red hasta que lea el aviso.
     */
    fun saveEqLimiterEnabled(enabled: Boolean) = update { it[KEY_EQ_LIMITER] = enabled }
    fun loadEqLimiterEnabled(): Boolean = cache[KEY_EQ_LIMITER] ?: DEFAULT_EQ_LIMITER_ENABLED

    /**
     * Reactivo por el mismo motivo que [eqEnabledFlow]: conviven varias instancias de
     * `PlaybackViewModel` (el overlay del player y la ruta `now_playing`) y un `MutableStateFlow`
     * por instancia son dos verdades para el mismo ajuste — la que no recibió el toque se queda
     * con el valor viejo y lo reimpone al reconstruirse.
     */
    val eqLimiterEnabledFlow: Flow<Boolean> = prefFlow { it[KEY_EQ_LIMITER] ?: DEFAULT_EQ_LIMITER_ENABLED }

    /**
     * Umbral del limitador en dBFS. 0 = fondo de escala (pura protección); por debajo se convierte
     * en un compresor de picos. El rango y el saneado son del processor (capa `player`), como con
     * los centros de los refuerzos.
     */
    fun saveEqLimiterThreshold(db: Float) = update { it[KEY_EQ_LIMITER_THRESHOLD] = db }
    fun loadEqLimiterThreshold(): Float? = cache[KEY_EQ_LIMITER_THRESHOLD]

    /**
     * Umbral en AUTOMÁTICO: 0 dBFS fijo, o sea el limitador como pura protección contra el recorte
     * (true-peak, ver `EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB`) y nada más. Estuvo atado
     * al pico de la curva hasta el 29 jul 2026, y eso lo convertía en un compresor que engancha en
     * cuanto hay curva: corregir el nivel por detrás, que es justo lo que este proyecto ya había
     * rechazado dos veces en el preamp. Quien quiera comprimir picos tiene el slider manual.
     *
     * Default true porque es la opción que no exige entender nada; el valor manual se guarda
     * aparte, así que desmarcarlo devuelve el que el usuario tenía puesto.
     */
    fun saveEqLimiterThresholdAuto(auto: Boolean) =
        update { it[KEY_EQ_LIMITER_THRESHOLD_AUTO] = auto }

    fun loadEqLimiterThresholdAuto(): Boolean =
        cache[KEY_EQ_LIMITER_THRESHOLD_AUTO] ?: DEFAULT_EQ_LIMITER_THRESHOLD_AUTO

    /** Reactivo por el mismo motivo que [eqLimiterEnabledFlow]. */
    val eqLimiterThresholdAutoFlow: Flow<Boolean> =
        prefFlow { it[KEY_EQ_LIMITER_THRESHOLD_AUTO] ?: DEFAULT_EQ_LIMITER_THRESHOLD_AUTO }


    // Las ganancias se guardan POR MODO (clave distinta para 5 y 10 bandas): al alternar
    // el nº de bandas se recupera la curva que el usuario tenía en ese modo, en vez de
    // truncar/estirar una a la otra (las frecuencias centrales no se corresponden).
    private fun eqGainsKey(bandCount: Int) =
        if (bandCount == EqualizerAudioProcessor.BANDS_10_COUNT) KEY_EQ_GAINS_10 else KEY_EQ_GAINS

    fun saveEqBandGains(bandCount: Int, gains: FloatArray) = update {
        it[eqGainsKey(bandCount)] = gains.joinToString(",")
    }

    /**
     * Persiste una config COMPLETA del EQ en UNA sola escritura (un único volcado a disco).
     *
     * Sustituye a la cascada de ~10 `saveEqX` que hacían `applyEqProfile`/`resetEq`/el manager por
     * ruta: como cada volcado reescribe el snapshot ENTERO (ver la cola FIFO), diez `saveX` seguidos
     * eran diez `clear()`+dump y, peor, NO atómicos — un crash a mitad dejaba un perfil aplicado a
     * medias en disco. Aquí el estado del EQ pasa de un config a otro de golpe.
     *
     * Las ganancias se guardan bajo la clave del modo de [EqSettings.bandCount] (ver [eqGainsKey]).
     * Los campos nullable (centros de refuerzo, umbral) se escriben solo si vienen resueltos: los
     * llamadores pasan valores no nulos cuando quieren un default determinista — igual que hacían al
     * llamar a los setters individuales, que tampoco aceptan null.
     */
    fun saveEqSettings(s: EqSettings) = update { m ->
        m[eqGainsKey(s.bandCount)] = s.gains.joinToString(",")
        m[KEY_EQ_BAND_COUNT] = s.bandCount
        m[KEY_EQ_BASS_BOOST] = s.bassBoostDb
        m[KEY_EQ_TREBLE_BOOST] = s.trebleBoostDb
        s.bassFreqHz?.let { m[KEY_EQ_BASS_FREQ] = it }
        s.trebleFreqHz?.let { m[KEY_EQ_TREBLE_FREQ] = it }
        m[KEY_EQ_PREAMP] = s.preampDb
        m[KEY_EQ_LIMITER] = s.limiterEnabled
        s.limiterThresholdDb?.let { m[KEY_EQ_LIMITER_THRESHOLD] = it }
        m[KEY_EQ_LIMITER_THRESHOLD_AUTO] = s.limiterThresholdAuto
        m[KEY_CLARITY_ENABLED] = s.clarityEnabled
        m[KEY_CLARITY_GAIN] = s.clarityGainDb
    }

    /** Ganancias (dB) del modo de [bandCount] bandas; ceros si nunca se configuró. */
    fun loadEqBandGains(bandCount: Int): FloatArray {
        val raw = cache[eqGainsKey(bandCount)] ?: return FloatArray(bandCount)
        return try {
            val parsed = raw.split(",").map { it.toFloat() }
            FloatArray(bandCount) { i -> parsed.getOrNull(i) ?: 0f }
        } catch (_: Exception) {
            FloatArray(bandCount)
        }
    }

    // --- Perfiles del EQ guardados por el usuario ---
    // Se serializan como un JSON array en una sola clave (org.json, sin Gson → sin regla
    // ProGuard). Cada perfil guarda su [EqSettings] COMPLETO (curva cruda, modo de bandas de
    // captura, refuerzos, preamp y limitador); el nombre puede contener comas/saltos, por eso NO se
    // usa el encoding delimitado de las ganancias.
    //
    // Solo hay perfiles: los *presets* (curva sola) son los de fábrica y no se guardan nunca, así
    // que no hace falta ningún campo que distinga el tipo — lo que hay aquí es de una sola clase.
    //
    // La CLAVE de DataStore no cambia con el renombrado: es el formato en disco de una app
    // publicada, y tocarla habría dejado sin sus perfiles a quien actualice.

    fun loadEqProfiles(): List<EqProfile> = parseEqProfiles(cache[KEY_EQ_CUSTOM_PRESETS])

    fun saveEqProfiles(profiles: List<EqProfile>) = update {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(encodeEqSettings(p.settings).put("id", p.id).put("name", p.name))
        }
        it[KEY_EQ_CUSTOM_PRESETS] = arr.toString()
    }

    /** Reactivo: la hoja del EQ edita/aplica perfiles; puede haber varias instancias del ViewModel. */
    val eqProfilesFlow: Flow<List<EqProfile>> =
        prefFlow { parseEqProfiles(it[KEY_EQ_CUSTOM_PRESETS]) }

    private fun parseEqProfiles(raw: String?): List<EqProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EqProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    settings = decodeEqSettings(o)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Serializa una config completa del EQ. Los campos nullable se OMITEN cuando son null en vez
     * de escribir `JSONObject.NULL`: al leer, "ausente" y "null" significan lo mismo (usa el
     * default del processor), y así un preset guardado antes de que existieran estos campos se lee
     * exactamente igual que uno nuevo sin ellos.
     */
    private fun encodeEqSettings(s: EqSettings): JSONObject {
        val o = JSONObject()
            .put("bandCount", s.bandCount)
            .put("gains", JSONArray().apply { s.gains.forEach { put(it.toDouble()) } })
            .put("bass", s.bassBoostDb.toDouble())
            .put("treble", s.trebleBoostDb.toDouble())
            .put("preamp", s.preampDb.toDouble())
            .put("lim", s.limiterEnabled)
            .put("limAuto", s.limiterThresholdAuto)
            // Clarity: por defecto apagado/0, y en ese caso se OMITE igual que los nullables, de modo
            // que un perfil guardado antes de que Clarity formara parte del sonido se lee idéntico.
            .apply {
                if (s.clarityEnabled) put("clarity", true)
                if (s.clarityGainDb != 0f) put("clarityDb", s.clarityGainDb.toDouble())
            }
        s.bassFreqHz?.let { o.put("bassHz", it) }
        s.trebleFreqHz?.let { o.put("trebleHz", it) }
        s.limiterThresholdDb?.let { o.put("limDb", it.toDouble()) }
        return o
    }

    /**
     * Lee una config completa. TODO lo que no sea la curva tiene default, que es lo que hace
     * retrocompatible el formato: los presets guardados cuando un preset era solo `bandCount` +
     * `gains` se leen sin refuerzos, sin preamp y con el limitador apagado — o sea exactamente el
     * sonido que tenían.
     */
    private fun decodeEqSettings(o: JSONObject): EqSettings {
        val g = o.getJSONArray("gains")
        return EqSettings(
            bandCount = EqualizerAudioProcessor.normalizedBandCount(o.getInt("bandCount")),
            gains = FloatArray(g.length()) { g.getDouble(it).toFloat() },
            bassBoostDb = o.optDouble("bass", 0.0).toFloat(),
            trebleBoostDb = o.optDouble("treble", 0.0).toFloat(),
            bassFreqHz = if (o.has("bassHz")) o.getDouble("bassHz") else null,
            trebleFreqHz = if (o.has("trebleHz")) o.getDouble("trebleHz") else null,
            preampDb = o.optDouble("preamp", 0.0).toFloat(),
            limiterEnabled = o.optBoolean("lim", false),
            limiterThresholdDb = if (o.has("limDb")) o.getDouble("limDb").toFloat() else null,
            limiterThresholdAuto = o.optBoolean("limAuto", true),
            clarityEnabled = o.optBoolean("clarity", false),
            clarityGainDb = o.optDouble("clarityDb", 0.0).toFloat()
        )
    }

    // --- Entradas ocultas ---

    /**
     * Ids de lo que NO se lista en el selector del EQ. Un solo conjunto para presets de fábrica y
     * perfiles propios, porque el criterio de "no me lo muestres" es el mismo y el selector los
     * pinta en un solo menú aunque los agrupe; conviven sin ambigüedad porque los presets van
     * NAMESPACED (`EqPresets.hideKey`, prefijo `builtin:`) y los perfiles son UUIDs.
     *
     * Ocultar NO es borrar, y esa es toda la razón de que exista: la entrada sigue guardada y se
     * restaura desde Ajustes. Por eso los de fábrica necesitaron una clave propia — su `labelRes`
     * es un id de recurso que AAPT/R8 pueden reasignar entre builds, así que persistirlo habría
     * hecho que una actualización ocultara un preset distinto sin fallar en compilación.
     */
    fun loadHiddenEqPresets(): Set<String> = cache[KEY_EQ_HIDDEN_PRESETS] ?: emptySet()

    fun saveHiddenEqPresets(ids: Set<String>) = update { it[KEY_EQ_HIDDEN_PRESETS] = ids }

    val hiddenEqPresetsFlow: Flow<Set<String>> =
        prefFlow { it[KEY_EQ_HIDDEN_PRESETS] ?: emptySet() }

    // --- Perfil del EQ por ruta de salida ---
    // Un snapshot de [EqSettings] por CATEGORÍA de ruta (cable, Bluetooth, USB, altavoz…),
    // serializados juntos en una sola clave: `{"WIRED": {...}, "BLUETOOTH": {...}}`.
    //
    // Se guarda el estado REAL del EQ, coincida o no con un preset guardado: al volver a una ruta
    // vuelve exactamente lo que dejaste, retoques sueltos incluidos. Guardar solo "qué preset
    // estaba aplicado" perdería sin avisar el trabajo de quien mueve un slider y no lo guarda,
    // que es el uso normal del ecualizador.

    fun loadEqRouteProfile(routeKey: String): EqSettings? {
        val raw = cache[KEY_EQ_ROUTE_PROFILES] ?: return null
        return try {
            val root = JSONObject(raw)
            if (!root.has(routeKey)) null else decodeEqSettings(root.getJSONObject(routeKey))
        } catch (_: Exception) {
            null
        }
    }

    /** Read-modify-write atómico: conserva los perfiles de las demás rutas. */
    fun saveEqRouteProfile(routeKey: String, settings: EqSettings) = update { mutable ->
        val root = try {
            JSONObject(mutable[KEY_EQ_ROUTE_PROFILES] ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        root.put(routeKey, encodeEqSettings(settings))
        mutable[KEY_EQ_ROUTE_PROFILES] = root.toString()
    }

    /**
     * Última ruta cuyo perfil está reflejado en el estado global del EQ. Persistida porque el
     * proceso muere: sin ella, arrancar con unos cascos distintos a los de la última sesión haría
     * que el estado actual (que es el de la ruta ANTERIOR) se guardara bajo la ruta nueva,
     * pisándole su perfil.
     */
    fun loadLastEqRoute(): String? = cache[KEY_EQ_LAST_ROUTE]

    fun saveLastEqRoute(routeKey: String) = update { it[KEY_EQ_LAST_ROUTE] = routeKey }

    // La memoria por ruta ya no es opcional (antes: `eq_route_profiles_enabled`, default OFF). El
    // `EqProfileManager` observa siempre, así que no hay flag que consultar ni ruta que "olvidar" al
    // desactivar. La clave vieja en disco queda huérfana y es inocua.

    // --- Clarity (realce de agudos) ---
    //
    // Clarity SÍ forma parte de [EqSettings] (ver su kdoc): entra en los perfiles del usuario y en
    // los perfiles por ruta de salida, porque es un ajuste de TIMBRE y por tanto parte del sonido
    // que se recuerda por dispositivo. Estos setters sueltos siguen existiendo para el control en
    // vivo de la hoja (toggle y slider); la persistencia dentro de un perfil pasa por
    // [encodeEqSettings]/[saveEqSettings], que escriben las MISMAS claves (`KEY_CLARITY_*`).

    fun saveClarityEnabled(enabled: Boolean) = update { it[KEY_CLARITY_ENABLED] = enabled }

    fun loadClarityEnabled(): Boolean = cache[KEY_CLARITY_ENABLED] ?: DEFAULT_CLARITY_ENABLED

    val clarityEnabledFlow: Flow<Boolean> = prefFlow { it[KEY_CLARITY_ENABLED] ?: DEFAULT_CLARITY_ENABLED }

    fun saveClarityGain(db: Float) = update { it[KEY_CLARITY_GAIN] = db }

    fun loadClarityGain(): Float = cache[KEY_CLARITY_GAIN] ?: Clarity.DEFAULT_GAIN_DB

    /**
     * Preferir el ecualizador DEL SISTEMA (MIUI/panel estándar): el botón EQ del NowPlaying
     * lo abre directamente en vez de la hoja propia. Activarlo apaga el EQ propio (evita
     * ecualizar dos veces) — eso lo hace el setter del ViewModel, no esta capa.
     */
    fun saveUseSystemEq(enabled: Boolean) = update { it[KEY_USE_SYSTEM_EQ] = enabled }
    fun loadUseSystemEq(): Boolean = cache[KEY_USE_SYSTEM_EQ] ?: DEFAULT_USE_SYSTEM_EQ

    /** "No volver a mostrar" del aviso de doble ecualización al encender el EQ propio. */
    fun saveEqConflictWarningSuppressed(suppressed: Boolean) =
        update { it[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] = suppressed }
    fun loadEqConflictWarningSuppressed(): Boolean = cache[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] ?: false

    // --- Onboarding: paso del tope de descargas a medias ---

    /**
     * true mientras el usuario conectó una fuente de NUBE durante el onboarding pero todavía no
     * llegó a fijar el tope de descargas. Sobrevive a un cierre forzado, así que al reabrir se
     * vuelve a ese paso en vez de caer en la biblioteca con el onboarding a medias (y sin haber
     * decidido cuánto espacio puede ocupar el audio).
     *
     * NO es un flag "onboarding completado" (ese sería estado redundante frente a "¿hay alguna
     * fuente?", capaz de quedar en true sin fuentes = biblioteca vacía sin salida). Este solo
     * puede RETENER en el onboarding, nunca sacar de él, y por defecto es false: quien ya tenía
     * la app instalada no ve ningún cambio.
     */
    fun saveOnboardingStoragePending(pending: Boolean) =
        update { it[KEY_ONBOARDING_STORAGE_PENDING] = pending }
    fun loadOnboardingStoragePending(): Boolean = cache[KEY_ONBOARDING_STORAGE_PENDING] ?: false

    /** null = aún no se preguntó (el sync detecta y dispara el diálogo de decisión). */
    fun saveDuplicatePolicy(policy: DuplicatePolicy) = update { it[KEY_DUPLICATE_POLICY] = policy.name }
    fun loadDuplicatePolicy(): DuplicatePolicy? =
        cache[KEY_DUPLICATE_POLICY]?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }

    // --- Carpeta de OneDrive que se escanea ---

    /**
     * Ruta relativa a la raíz del OneDrive del usuario (`Music`, `Documentos/Música`…). Cadena
     * vacía = la cuenta entera.
     *
     * **Cambiarla invalida el delta token**: el token describe el estado de UN subárbol concreto,
     * y reutilizarlo apuntando a otra carpeta daría un incremental sobre cambios que no son los de
     * esa carpeta. Por eso [saveOneDriveFolderPath] lo limpia en la misma escritura y no en el
     * llamador — un olvido ahí produciría una biblioteca incoherente muy difícil de diagnosticar.
     */
    fun loadOneDriveFolderPath(): String = readOneDriveFolderPath(cache)
    val oneDriveFolderPathFlow: Flow<String> = prefFlow { readOneDriveFolderPath(it) }

    private fun readOneDriveFolderPath(prefs: Preferences): String =
        prefs[KEY_ONEDRIVE_FOLDER] ?: AppConfig.ONEDRIVE_DEFAULT_FOLDER

    fun saveOneDriveFolderPath(path: String) = update {
        it[KEY_ONEDRIVE_FOLDER] = path.trim().trim('/')
        it.remove(KEY_DELTA_TOKEN)
    }

    // --- Guardado de letras (pantalla de letras + Ajustes → Reproducción) ---

    /**
     * Cómo guardar las letras. [LyricsSaveMode.ASK] = preguntar cada vez; el diálogo lo cambia
     * cuando el usuario marca "no volver a preguntar", y Ajustes puede devolverlo a ASK.
     */
    fun saveLyricsSaveMode(mode: LyricsSaveMode) = update { it[KEY_LYRICS_SAVE_MODE] = mode.name }
    fun loadLyricsSaveMode(): LyricsSaveMode = readLyricsSaveMode(cache)
    val lyricsSaveModeFlow: Flow<LyricsSaveMode> = prefFlow { readLyricsSaveMode(it) }

    private fun readLyricsSaveMode(prefs: Preferences): LyricsSaveMode =
        prefs[KEY_LYRICS_SAVE_MODE]?.let { runCatching { LyricsSaveMode.valueOf(it) }.getOrNull() }
            ?: LyricsSaveMode.ASK

    /**
     * Carpeta (árbol SAF) donde dejar los `.lrc` de las canciones a las que no se les puede
     * escribir al lado: las indexadas por MediaStore, donde el permiso de audio no autoriza a
     * crear archivos no-media. Vacío = no configurada.
     */
    fun saveLyricsFolderUri(uri: String?) = update {
        if (uri == null) it.remove(KEY_LYRICS_FOLDER_URI) else it[KEY_LYRICS_FOLDER_URI] = uri
    }
    fun loadLyricsFolderUri(): String? = cache[KEY_LYRICS_FOLDER_URI]
    val lyricsFolderUriFlow: Flow<String?> = prefFlow { it[KEY_LYRICS_FOLDER_URI] }

    // --- Fotos de artistas (Deezer): política de red (Ajustes → Descargas) ---

    /** Backfill masivo también en red medida (datos móviles), sin preguntar. */
    fun saveArtistPhotosOnMetered(enabled: Boolean) = update { it[KEY_ARTIST_PHOTOS_METERED] = enabled }
    fun loadArtistPhotosOnMetered(): Boolean = cache[KEY_ARTIST_PHOTOS_METERED] ?: false

    /** Mostrar el banner "fotos en pausa" en la pestaña Artistas al estar en datos. */
    fun saveArtistPhotosBannerEnabled(enabled: Boolean) = update { it[KEY_ARTIST_PHOTOS_BANNER] = enabled }
    fun loadArtistPhotosBannerEnabled(): Boolean = cache[KEY_ARTIST_PHOTOS_BANNER] ?: true

    /** Fetch de la foto al abrir el DETALLE de un artista aunque la red sea medida. */
    fun saveArtistPhotoDetailOnMetered(enabled: Boolean) = update { it[KEY_ARTIST_PHOTO_DETAIL_METERED] = enabled }
    fun loadArtistPhotoDetailOnMetered(): Boolean = cache[KEY_ARTIST_PHOTO_DETAIL_METERED] ?: true

    /** Reactivo: lo cambia LibraryViewModel (Ajustes) y lo observa PlaybackViewModel (botón EQ). */
    val useSystemEqFlow: Flow<Boolean> =
        prefFlow { it[KEY_USE_SYSTEM_EQ] ?: DEFAULT_USE_SYSTEM_EQ }

    // --- Control de descargas (pausa / stop persistentes) ---

    /**
     * Estado de control de las descargas masivas. Se persiste para que el gate del sync lo
     * respete incluso tras reiniciar el proceso (un ScanWorker que arranca en frío lee esto).
     */
    fun saveDownloadControlState(state: DownloadControlState) = update {
        it[KEY_DOWNLOAD_CONTROL_STATE] = state.name
    }

    fun loadDownloadControlState(): DownloadControlState = try {
        DownloadControlState.valueOf(cache[KEY_DOWNLOAD_CONTROL_STATE] ?: DownloadControlState.ACTIVE.name)
    } catch (_: Exception) {
        DownloadControlState.ACTIVE
    }

    /** Reactivo: lo cambia el Download Manager y lo observan el banner (Library) y el propio panel. */
    val downloadControlStateFlow: Flow<DownloadControlState> =
        prefFlow {
            try {
                DownloadControlState.valueOf(it[KEY_DOWNLOAD_CONTROL_STATE] ?: DownloadControlState.ACTIVE.name)
            } catch (_: Exception) {
                DownloadControlState.ACTIVE
            }
        }

    /**
     * Cierre "una vez" del banner de descargas pausadas/detenidas: lo oculta sin reanudar y se
     * resetea ante la próxima pausa/detención/reanudación (SyncManager). Es distinto del estado
     * de control (que sigue en PAUSED/STOPPED) y del silencio permanente (banner muted).
     */
    fun saveStopBannerDismissed(dismissed: Boolean) = update { it[KEY_STOP_BANNER_DISMISSED] = dismissed }
    fun loadStopBannerDismissed(): Boolean = cache[KEY_STOP_BANNER_DISMISSED] ?: false
    val stopBannerDismissedFlow: Flow<Boolean> =
        prefFlow { it[KEY_STOP_BANNER_DISMISSED] ?: false }

    /**
     * "No volver a mostrar": silencia el banner de descargas PARA SIEMPRE. Nadie lo resetea
     * (a diferencia del cierre "una vez"); el estado de descargas sigue visible y controlable
     * desde el Download Manager, que tiene sus propios botones en la top bar.
     */
    fun saveDownloadBannerMuted(muted: Boolean) = update { it[KEY_DOWNLOAD_BANNER_MUTED] = muted }
    fun loadDownloadBannerMuted(): Boolean = cache[KEY_DOWNLOAD_BANNER_MUTED] ?: false
    val downloadBannerMutedFlow: Flow<Boolean> =
        prefFlow { it[KEY_DOWNLOAD_BANNER_MUTED] ?: false }

    /**
     * ¿Ya se rellenaron número de pista y año en la biblioteca que existía antes de que se
     * leyeran esos tags? Es una MIGRACIÓN DE DATOS de una sola pasada, no un estado del usuario.
     *
     * Va en preferencias y no en una columna centinela porque el trabajo es finito y ocurre una
     * vez: las columnas `trackNumber`/`year` existen desde la v12 pero nunca se escribieron, así
     * que toda fila anterior vale 0. Una query "las que están a 0" no se auto-vacía —un archivo
     * sin el tag sigue a 0 después de mirarlo— y sin este cierre se re-analizaría la biblioteca
     * entera en CADA sync. Las canciones nuevas no lo necesitan: entran ya con el dato.
     */
    fun saveTrackInfoBackfilled(done: Boolean) = update { it[KEY_TRACK_INFO_BACKFILLED] = done }
    fun loadTrackInfoBackfilled(): Boolean = cache[KEY_TRACK_INFO_BACKFILLED] ?: false

    // --- Tope de almacenamiento para descargas (caché LRU) ---

    /**
     * Límite de bytes que puede ocupar el audio descargado de la nube. 0 = sin límite.
     * Al superarse, el sync masivo frena y las descargas por reproducción desalojan las
     * canciones menos/menos-recientemente reproducidas para hacer sitio.
     */
    fun saveStorageLimitBytes(bytes: Long) = update { it[KEY_STORAGE_LIMIT_BYTES] = bytes.coerceAtLeast(0L) }
    fun loadStorageLimitBytes(): Long = cache[KEY_STORAGE_LIMIT_BYTES] ?: 0L
    val storageLimitBytesFlow: Flow<Long> =
        prefFlow { it[KEY_STORAGE_LIMIT_BYTES] ?: 0L }

    /**
     * Motivo por el que el ÚLTIMO escaneo se dio por perdido, o `null` si el último terminó bien.
     *
     * Se persiste porque quien se rinde es un worker de fondo, minutos después y probablemente con
     * la app cerrada: el estado en memoria de `SyncManager` se habría ido con el proceso y la
     * biblioteca aparecería desactualizada sin decir por qué. Es el equivalente, a nivel de
     * proceso, de lo que `songs.lastDownloadError` guarda por canción.
     *
     * Lo limpia el primer escaneo que vuelva a terminar bien.
     */
    fun saveLastSyncFailure(reason: String?) = update {
        if (reason == null) it.remove(KEY_LAST_SYNC_FAILURE) else it[KEY_LAST_SYNC_FAILURE] = reason
    }
    fun loadLastSyncFailure(): String? = cache[KEY_LAST_SYNC_FAILURE]
    val lastSyncFailureFlow: Flow<String?> = prefFlow { it[KEY_LAST_SYNC_FAILURE] }

    /**
     * Throughput MEDIDO por conexión contra OneDrive (MB/s), del que sale el número de descargas en
     * paralelo. `0` = nunca se ha podido medir, y entonces manda la estimación de fábrica.
     *
     * Se persiste porque cada corrida aporta UNA muestra y el dato tiene que sobrevivir al proceso:
     * un sync es lo bastante raro como para que aprender de cero cada vez no sirva de nada. Es del
     * dispositivo y de la cuenta, no del catálogo, así que no se toca al borrar la biblioteca.
     */
    fun saveOneDriveThroughputMBps(value: Float) =
        update { it[KEY_ONEDRIVE_THROUGHPUT_MBPS] = value.coerceAtLeast(0f) }
    fun loadOneDriveThroughputMBps(): Float =
        cache[KEY_ONEDRIVE_THROUGHPUT_MBPS] ?: DEFAULT_ONEDRIVE_THROUGHPUT_MBPS

    // --- Now Playing ---

    fun saveNowPlayingBackground(mode: NowPlayingBackground) = update {
        it[KEY_NOW_PLAYING_BACKGROUND] = mode.name
    }

    fun loadNowPlayingBackground(): NowPlayingBackground = readNowPlayingBackground(cache)

    /**
     * Flow reactivo del ajuste de fondo: lo cambia LibraryViewModel (Ajustes) y lo observa
     * PlaybackViewModel (NowPlaying), que son instancias distintas — la caché síncrona no
     * alcanza para propagar el cambio en vivo.
     */
    val nowPlayingBackgroundFlow: Flow<NowPlayingBackground> =
        prefFlow { readNowPlayingBackground(it) }

    /**
     * El default no es una constante suelta porque tiene que MIGRAR: hasta la 1.2.0 el fondo era el
     * booleano `now_playing_solid_bg`, y quien dejó puesto el sólido debe seguir viéndolo. La key
     * vieja solo se LEE, y solo mientras no exista la nueva — mismo patrón que
     * `local_folder_uri` → `local_folder_uris`. Un nombre desconocido cae al degradado en vez de
     * reventar, y eso es lo que recoge al que tenía guardado el modo `BLUR` retirado el 29 ago 2026
     * (además de una preferencia de una versión futura, tras un downgrade).
     */
    private fun readNowPlayingBackground(prefs: Preferences): NowPlayingBackground {
        prefs[KEY_NOW_PLAYING_BACKGROUND]
            ?.let { name -> runCatching { NowPlayingBackground.valueOf(name) }.getOrNull() }
            ?.let { return it }
        return if (prefs[KEY_NOW_PLAYING_SOLID_BG] == true) {
            NowPlayingBackground.SOLID
        } else {
            NowPlayingBackground.GRADIENT
        }
    }

    /**
     * Barra de progreso ONDULADA (M3 Expressive) en el NowPlaying, en vez de la píldora plana.
     * Solo aplica al reproductor grande: en el MiniPlayer la barra mide 3dp y la onda no se
     * leería. Default false (la píldora es el diseño actual).
     */
    fun saveNowPlayingWavyProgress(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_WAVY] = enabled
    }

    fun loadNowPlayingWavyProgress(): Boolean = cache[KEY_NOW_PLAYING_WAVY] ?: false

    /** Reactivo por el mismo motivo que [nowPlayingBackgroundFlow] (Ajustes ↔ NowPlaying). */
    val nowPlayingWavyProgressFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_WAVY] ?: false }

    /**
     * GROSOR de la barra de progreso del NowPlaying, en dp. Vale para los DOS modos (píldora plana
     * y onda): la UI deriva de él el trazo, la amplitud, el indicador y el alto del palo, de modo
     * que el ajuste es un solo número. 0 (o cualquier valor fuera de rango) = el default, que lo
     * acota la propia UI.
     *
     * Se guarda en dp y no como factor de escala porque es lo que el ajuste enseña y lo que hay
     * que poder volver a leer para saber qué está dibujado.
     */
    fun saveNowPlayingProgressThickness(dp: Int) = update {
        it[KEY_NOW_PLAYING_PROGRESS_THICKNESS] = dp
    }

    fun loadNowPlayingProgressThickness(): Int =
        cache[KEY_NOW_PLAYING_PROGRESS_THICKNESS] ?: DEFAULT_PROGRESS_THICKNESS_DP

    /** Reactivo por el mismo motivo que [nowPlayingWavyProgressFlow] (Ajustes ↔ NowPlaying). */
    val nowPlayingProgressThicknessFlow: Flow<Int> =
        prefFlow { it[KEY_NOW_PLAYING_PROGRESS_THICKNESS] ?: DEFAULT_PROGRESS_THICKNESS_DP }

    /**
     * HANDLE (el palo vertical) de la barra de progreso del NowPlaying, en los dos modos.
     * `true` = permanente, como en las barras del spec Expressive (y el diseño actual, de ahí el
     * default); `false` = aparece solo mientras se arrastra, que es la otra forma que la barra ya
     * sabía dibujar — no desaparece del todo porque al buscar hace falta ver dónde va a caer el
     * dedo, y la geometría del hueco fill↔palo↔riel está construida para interpolar entre las dos.
     */
    fun saveNowPlayingProgressHandle(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_PROGRESS_HANDLE] = enabled
    }

    fun loadNowPlayingProgressHandle(): Boolean =
        cache[KEY_NOW_PLAYING_PROGRESS_HANDLE] ?: DEFAULT_PROGRESS_HANDLE

    /** Reactivo por el mismo motivo que [nowPlayingWavyProgressFlow] (Ajustes ↔ NowPlaying). */
    val nowPlayingProgressHandleFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_PROGRESS_HANDLE] ?: DEFAULT_PROGRESS_HANDLE }

    /**
     * Forma del MiniPlayer: `true` = rectángulo redondeado, `false` = píldora (el diseño actual y
     * el default, para no cambiarle la app a nadie que ya la tenga instalada).
     *
     * Reactivo y no `load` a secas: lo escribe Ajustes (`LibraryViewModel`) y lo lee la capa del
     * reproductor (`PlaybackViewModel`), que son instancias distintas — el mismo motivo que el
     * fondo sólido y la barra ondulada.
     */
    fun saveMiniPlayerRoundedRect(enabled: Boolean) = update {
        it[KEY_MINI_PLAYER_ROUNDED_RECT] = enabled
    }

    fun loadMiniPlayerRoundedRect(): Boolean = cache[KEY_MINI_PLAYER_ROUNDED_RECT] ?: false

    val miniPlayerRoundedRectFlow: Flow<Boolean> =
        prefFlow { it[KEY_MINI_PLAYER_ROUNDED_RECT] ?: false }

    /**
     * Forma del botón de play del MiniPlayer: `true` = círculo, `false` = squircle (el default).
     * Son las DOS formas que M3 tabula para un botón de icono (`ContainerShapeRound` /
     * `ContainerShapeSquare`), así que el ajuste elige entre dos valores del spec y no entre dos
     * geometrías inventadas. El radio concreto del squircle lo fija `MiniPlayer`.
     *
     * Reactivo por el mismo motivo que [miniPlayerRoundedRectFlow]: lo escribe Ajustes
     * (`LibraryViewModel`) y lo lee la capa del reproductor (`PlaybackViewModel`).
     */
    fun saveMiniPlayerRoundPlayButton(enabled: Boolean) = update {
        it[KEY_MINI_PLAYER_ROUND_PLAY] = enabled
    }

    fun loadMiniPlayerRoundPlayButton(): Boolean =
        cache[KEY_MINI_PLAYER_ROUND_PLAY] ?: DEFAULT_MINI_PLAYER_ROUND_PLAY

    val miniPlayerRoundPlayButtonFlow: Flow<Boolean> =
        prefFlow { it[KEY_MINI_PLAYER_ROUND_PLAY] ?: DEFAULT_MINI_PLAYER_ROUND_PLAY }

    /**
     * Pestañas de la biblioteca ABAJO, en una navigation bar, en vez de en la fila bajo la
     * búsqueda. Default `false`: el modo de siempre no le cambia a nadie que ya tenga la app.
     *
     * Solo gobierna la orientación VERTICAL. Girado hay rail SIEMPRE, con esto encendido o no —
     * ver `libraryChrome` en `MusicAppState`.
     *
     * Reactivo por el mismo motivo que [miniPlayerRoundedRectFlow]: lo escribe Ajustes
     * (`LibraryViewModel`) y lo lee también la capa del reproductor (`PlaybackViewModel`), para
     * saber cuánto tiene que apartarse la píldora. Son instancias distintas.
     */
    fun saveLibraryBottomTabs(enabled: Boolean) = update {
        it[KEY_LIBRARY_BOTTOM_TABS] = enabled
    }

    fun loadLibraryBottomTabs(): Boolean =
        cache[KEY_LIBRARY_BOTTOM_TABS] ?: DEFAULT_LIBRARY_BOTTOM_TABS

    val libraryBottomTabsFlow: Flow<Boolean> =
        prefFlow { it[KEY_LIBRARY_BOTTOM_TABS] ?: DEFAULT_LIBRARY_BOTTOM_TABS }

    /**
     * **Estilo moderno de navegación**: las seis pestañas configurables se colapsan en TRES
     * destinos top-level —Inicio · Biblioteca · Listas—, donde Biblioteca es un hub que abre cada
     * categoría (canciones, artistas, álbumes, géneros) a pantalla completa.
     *
     * Es un interruptor APARTE y no un valor más de [libraryBottomTabsFlow], aunque las dos hablen
     * de dónde se navega, porque no son la misma decisión: aquélla dice DÓNDE se dibuja el selector
     * y ésta CUÁNTOS destinos hay. Mientras está encendido, la personalización de pestañas (orden y
     * visibilidad) y la propia [libraryBottomTabsFlow] quedan inhabilitadas en Ajustes — pero
     * **su valor persistido no se toca**, así que apagarlo devuelve exactamente lo que había.
     *
     * Reactivo por el mismo motivo que [libraryBottomTabsFlow]: lo escribe Ajustes
     * (`LibraryViewModel`) y lo lee también la capa del reproductor (`PlaybackViewModel`), que
     * necesita saber si hay barra abajo de la que apartar la píldora.
     */
    fun saveLibraryModernNav(enabled: Boolean) = update {
        it[KEY_LIBRARY_MODERN_NAV] = enabled
    }

    fun loadLibraryModernNav(): Boolean =
        cache[KEY_LIBRARY_MODERN_NAV] ?: DEFAULT_LIBRARY_MODERN_NAV

    val libraryModernNavFlow: Flow<Boolean> =
        prefFlow { it[KEY_LIBRARY_MODERN_NAV] ?: DEFAULT_LIBRARY_MODERN_NAV }

    /**
     * Chip de formato del NowPlaying EXTENDIDO (`FLAC · 16 bit · 44.1 kHz`) en vez de solo el
     * contenedor. Se escribe desde dos sitios —el switch de Ajustes → Apariencia y el tap sobre
     * el propio chip—, de ahí que sea reactivo: son instancias de ViewModel distintas.
     */
    fun saveNowPlayingDetailedFormat(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_DETAILED_FORMAT] = enabled
    }

    fun loadNowPlayingDetailedFormat(): Boolean = cache[KEY_NOW_PLAYING_DETAILED_FORMAT] ?: false

    val nowPlayingDetailedFormatFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_DETAILED_FORMAT] ?: false }

    /**
     * Gestos del reproductor: deslizar la carátula para cambiar de canción, deslizar hacia abajo
     * para cerrar el player, deslizar hacia arriba en el mini para abrirlo y doble toque en los
     * laterales de la carátula para saltar unos segundos.
     *
     * Es UN solo ajuste para los cuatro a propósito: son el mismo contrato ("la carátula y el
     * player responden al dedo"), y trocearlo en cuatro switches obligaría al usuario a razonar
     * sobre gestos que aún no descubrió. Encendido por defecto —es lo que todo reproductor hace—
     * y apagable porque conviven con el long-press del selector de color y con el arrastre del
     * slider, y a quien le estorben tiene que poder quitarlos.
     */
    fun savePlayerGestures(enabled: Boolean) = update {
        it[KEY_PLAYER_GESTURES] = enabled
    }

    fun loadPlayerGestures(): Boolean = cache[KEY_PLAYER_GESTURES] ?: DEFAULT_PLAYER_GESTURES

    /** Reactivo por el mismo motivo que [nowPlayingBackgroundFlow] (Ajustes ↔ reproductor). */
    val playerGesturesFlow: Flow<Boolean> =
        prefFlow { it[KEY_PLAYER_GESTURES] ?: DEFAULT_PLAYER_GESTURES }

    // --- Biblioteca ---

    /**
     * Pestañas de la biblioteca: orden + cuáles se muestran. Reactivo porque lo edita Ajustes y
     * lo consume `LibraryScreen` (el pager se reconstruye en vivo al guardar).
     */
    val libraryTabsConfigFlow: Flow<List<LibraryTabState>> =
        prefFlow { LibraryTabsConfig.decode(it[KEY_LIBRARY_TABS_CONFIG]) }

    fun loadLibraryTabsConfig(): List<LibraryTabState> =
        LibraryTabsConfig.decode(cache[KEY_LIBRARY_TABS_CONFIG])

    fun saveLibraryTabsConfig(list: List<LibraryTabState>) = update {
        it[KEY_LIBRARY_TABS_CONFIG] = LibraryTabsConfig.encode(list)
    }

    /**
     * Géneros por coincidencia PARCIAL: con esto activo, abrir "Rock" trae también las canciones
     * etiquetadas "Rock/Metal" o "Hard Rock" (LIKE), no solo las de tag exacto. Los tags GENRE
     * reales son un desastre y no hay forma de normalizarlos sin perder información, así que la
     * decisión es del usuario. Default apagado = lo que se ve es lo que dice el tag.
     */
    fun saveGenrePartialMatch(enabled: Boolean) = update {
        it[KEY_GENRE_PARTIAL_MATCH] = enabled
    }

    fun loadGenrePartialMatch(): Boolean = cache[KEY_GENRE_PARTIAL_MATCH] ?: false

    val genrePartialMatchFlow: Flow<Boolean> =
        prefFlow { it[KEY_GENRE_PARTIAL_MATCH] ?: false }

    /**
     * Con qué rol se agrupan los artistas: el de la pista (lo de siempre) o el del álbum. Se guarda
     * el NOMBRE del enum. Cambiarlo no recalcula nada — `song_artists` guarda los dos roles — así que
     * es una preferencia VIVA que las consultas leen en cada emisión.
     */
    fun saveArtistCreditRole(role: ArtistCreditRole) = update {
        it[KEY_ARTIST_CREDIT_ROLE] = role.name
    }

    fun loadArtistCreditRole(): ArtistCreditRole = readArtistCreditRole(cache)

    val artistCreditRoleFlow: Flow<ArtistCreditRole> = prefFlow { readArtistCreditRole(it) }

    private fun readArtistCreditRole(prefs: Preferences): ArtistCreditRole =
        prefs[KEY_ARTIST_CREDIT_ROLE]
            ?.let { name -> ArtistCreditRole.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_ARTIST_CREDIT_ROLE

    /**
     * Separación de colaboraciones ("A feat. B" cuenta para A y para B). Los tres valores se escriben
     * y se leen JUNTOS porque son un solo ajuste: el indexador de créditos los consume como una
     * [ArtistSplitConfig], y un cambio en cualquiera re-encola la biblioteca.
     *
     * Los separadores se guardan por NOMBRE del enum; uno desconocido (de una versión futura) se
     * ignora en vez de invalidar el conjunto.
     */
    fun saveArtistSplitConfig(config: ArtistSplitConfig) = update {
        it[KEY_ARTIST_SPLIT_ENABLED] = config.enabled
        it[KEY_ARTIST_SEPARATORS] = config.separators.map { separator -> separator.name }.toSet()
        it[KEY_ARTIST_SPLIT_EXCEPTIONS] = config.exceptions.map(String::trim).filter(String::isNotEmpty).toSet()
    }

    fun loadArtistSplitConfig(): ArtistSplitConfig = readArtistSplitConfig(cache)

    val artistSplitConfigFlow: Flow<ArtistSplitConfig> = prefFlow { readArtistSplitConfig(it) }

    private fun readArtistSplitConfig(prefs: Preferences): ArtistSplitConfig = ArtistSplitConfig(
        enabled = prefs[KEY_ARTIST_SPLIT_ENABLED] ?: DEFAULT_ARTIST_SPLIT_ENABLED,
        separators = prefs[KEY_ARTIST_SEPARATORS]
            ?.mapNotNull { name -> ArtistSeparator.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: ArtistSeparator.DEFAULT,
        exceptions = prefs[KEY_ARTIST_SPLIT_EXCEPTIONS]
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
    )

    /**
     * Huella de la [ArtistSplitConfig] con la que se calcularon los créditos que hay en la BD. La
     * escribe SOLO `ArtistCreditIndexer`, después de re-encolar: si el proceso muere entre guardar el
     * ajuste y re-encolar, la huella sigue siendo la vieja y el siguiente arranque lo termina. Con un
     * re-encolado disparado desde la pantalla, ese hueco dejaría la biblioteca calculada con el ajuste
     * anterior para siempre.
     */
    fun loadAppliedArtistSplitFingerprint(): String? = cache[KEY_ARTIST_SPLIT_APPLIED]

    fun saveAppliedArtistSplitFingerprint(fingerprint: String) = update {
        it[KEY_ARTIST_SPLIT_APPLIED] = fingerprint
    }

    // --- Tema ---

    /**
     * Estilo de paleta con el que MaterialKolor genera el ColorScheme a partir del color del
     * álbum. Se guarda el NOMBRE del enum (no el ordinal): reordenar `PaletteStyle` en una
     * futura versión de la librería cambiaría los ordinales y el ajuste guardado pasaría a
     * significar otro estilo en silencio.
     *
     * Default `TonalSpot`, que es el de Material You en Android y el de la propia librería.
     */
    fun saveThemePaletteStyle(styleName: String) = update {
        it[KEY_THEME_PALETTE_STYLE] = styleName
    }

    fun loadThemePaletteStyle(): String = cache[KEY_THEME_PALETTE_STYLE] ?: DEFAULT_PALETTE_STYLE

    /** Reactivo: lo cambia Ajustes y lo observa el tema en MainActivity (instancias distintas). */
    val themePaletteStyleFlow: Flow<String> =
        prefFlow { it[KEY_THEME_PALETTE_STYLE] ?: DEFAULT_PALETTE_STYLE }

    companion object {
        // ==================== DEFAULTS ====================
        //
        // Toda preferencia que se lee por DOS caminos —`loadX()` síncrono y `xFlow` reactivo—
        // tiene aquí su valor por defecto, UNA vez. No es ceremonia: los dos caminos existen
        // por diseño (uno para arrancar un ViewModel, otro para reaccionar), y mientras el
        // default estuvo escrito literalmente en cada uno, cambiarlo obligaba a acordarse de
        // los dos. Nada avisaba si se olvidaba: el valor efectivo pasaba a depender de cuál de
        // los dos caminos leyera primero, que es una divergencia silenciosa entre "lo que la
        // pantalla muestra al abrirse" y "lo que muestra al cambiar". Es la misma clase de
        // fallo que ya obligó a unificar caché y disco como fuente de verdad, un nivel más
        // abajo. Las preferencias cuyo valor sale de un parseo compartido
        // (`readLocalFolderUris`, `readLyricsSaveMode`, `parseEqProfiles`…) ya no lo necesitan:
        // ahí el default vive dentro de esa función, que también es un solo sitio.
        private const val DEFAULT_EQ_ENABLED = false
        private const val DEFAULT_EQ_LIMITER_ENABLED = false
        /** Ver el KDoc de `loadEqLimiterThresholdAuto`: automático porque no exige entender nada. */
        private const val DEFAULT_EQ_LIMITER_THRESHOLD_AUTO = true
        private const val DEFAULT_CLARITY_ENABLED = false
        private const val DEFAULT_USE_SYSTEM_EQ = false
        private const val DEFAULT_SCAN_WHOLE_DEVICE = false

        private const val DATASTORE_NAME = "music_player_prefs"

        /**
         * Archivo de la sesión de reproducción, aparte del de configuración. El nombre es formato
         * en disco de una app publicada: cambiarlo dejaría la sesión huérfana en el archivo viejo.
         */
        private const val SESSION_DATASTORE_NAME = "playback_session"

        /**
         * Archivo de los slots de reanudación por contexto ("Seguir escuchando"). Aparte del de
         * sesión porque tiene otro ciclo de vida (no se borra al parar) y otra cadencia de
         * escritura (solo al cambiar de contexto), aunque su contenido sea igual de pesado.
         */
        private const val CONTEXT_SLOTS_DATASTORE_NAME = "playback_context_slots"
        private const val KEY_SORT_ORDER = "sort_order"
        private val KEY_ARTIST_SORT = stringPreferencesKey("artist_sort_order")
        private val KEY_ALBUM_SORT = stringPreferencesKey("album_sort_order")
        private val KEY_DETAIL_SONG_SORT = stringPreferencesKey("detail_song_sort")
        private val DEFAULT_DETAIL_SONG_SORT: SortSpec<DetailSongSort> = DetailSongSort.DEFAULT
        private val KEY_DUPLICATE_POLICY = stringPreferencesKey("duplicate_policy")
        private val KEY_ONBOARDING_STORAGE_PENDING =
            booleanPreferencesKey("onboarding_storage_step_pending")
        private const val QUEUE_DELIMITER = "" // Unit Separator

        // DataStore keys tipadas
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_COVER_FILE_COUNT = intPreferencesKey("cover_file_count")

        private val KEY_REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode")
        private val KEY_REPLAYGAIN_PREAMP = floatPreferencesKey("replaygain_preamp")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_GAINS = stringPreferencesKey("eq_band_gains")
        private val KEY_EQ_GAINS_10 = stringPreferencesKey("eq_band_gains_10")
        private val KEY_EQ_BAND_COUNT = intPreferencesKey("eq_band_count")
        private val KEY_EQ_BASS_BOOST = floatPreferencesKey("eq_bass_boost")
        private val KEY_EQ_TREBLE_BOOST = floatPreferencesKey("eq_treble_boost")
        private val KEY_EQ_BASS_FREQ = doublePreferencesKey("eq_bass_freq")
        private val KEY_EQ_TREBLE_FREQ = doublePreferencesKey("eq_treble_freq")
        private val KEY_EQ_PREAMP = floatPreferencesKey("eq_preamp")
        private val KEY_EQ_LIMITER = booleanPreferencesKey("eq_limiter")
        private val KEY_EQ_LIMITER_THRESHOLD = floatPreferencesKey("eq_limiter_threshold")
        private val KEY_EQ_LIMITER_THRESHOLD_AUTO = booleanPreferencesKey("eq_limiter_threshold_auto")
        private val KEY_EQ_CUSTOM_PRESETS = stringPreferencesKey("eq_custom_presets")
        private val KEY_EQ_HIDDEN_PRESETS = stringSetPreferencesKey("eq_hidden_presets")
        private val KEY_EQ_ROUTE_PROFILES = stringPreferencesKey("eq_route_profiles")
        private val KEY_EQ_LAST_ROUTE = stringPreferencesKey("eq_last_route")
        private val KEY_EQ_CONFLICT_WARNING_SUPPRESSED = booleanPreferencesKey("eq_conflict_warning_suppressed")
        private val KEY_USE_SYSTEM_EQ = booleanPreferencesKey("use_system_eq")
        private val KEY_CLARITY_ENABLED = booleanPreferencesKey("clarity_enabled")
        private val KEY_CLARITY_GAIN = floatPreferencesKey("clarity_gain")
        private val KEY_ARTIST_PHOTOS_METERED = booleanPreferencesKey("artist_photos_metered")
        private val KEY_ARTIST_PHOTOS_BANNER = booleanPreferencesKey("artist_photos_banner_enabled")
        private val KEY_ARTIST_PHOTO_DETAIL_METERED = booleanPreferencesKey("artist_photo_detail_metered")
        private val KEY_TOOLBAR_CONFIG = stringPreferencesKey("player_toolbar_config")
        private val KEY_LIBRARY_TABS_CONFIG = stringPreferencesKey("library_tabs_config")
        private val KEY_NOW_PLAYING_DETAILED_FORMAT = booleanPreferencesKey("now_playing_detailed_format")
        private val KEY_GENRE_PARTIAL_MATCH = booleanPreferencesKey("genre_partial_match")
        private val KEY_ARTIST_CREDIT_ROLE = stringPreferencesKey("artist_credit_role")
        private val KEY_ARTIST_SPLIT_ENABLED = booleanPreferencesKey("artist_split_enabled")
        private val KEY_ARTIST_SEPARATORS = stringSetPreferencesKey("artist_split_separators")
        private val KEY_ARTIST_SPLIT_EXCEPTIONS = stringSetPreferencesKey("artist_split_exceptions")
        private val KEY_ARTIST_SPLIT_APPLIED = stringPreferencesKey("artist_split_applied")

        /** El de siempre: agrupar por el artista de la pista. */
        val DEFAULT_ARTIST_CREDIT_ROLE = ArtistCreditRole.TRACK

        /** Apagada: lo que se ve es lo que dice el tag, igual que los géneros compuestos. */
        const val DEFAULT_ARTIST_SPLIT_ENABLED = false
        private val KEY_RECENT_CONTEXTS = stringPreferencesKey("recent_playback_contexts")
        /** JSON `{ "<contextKey>": {q,o,s,i,p,sid}, ... }` — vive en [contextSlotsDataStore]. */
        private val KEY_CONTEXT_SLOTS = stringPreferencesKey("context_resume_slots")
        /** Clave del contexto cuya cola está viva — vive en [contextSlotsDataStore]. */
        private val KEY_ACTIVE_CONTEXT = stringPreferencesKey("active_context_key")
        // Se conserva SOLO para leer: `readNowPlayingBackground` migra desde ella. No se escribe más.
        private val KEY_NOW_PLAYING_SOLID_BG = booleanPreferencesKey("now_playing_solid_bg")
        private val KEY_NOW_PLAYING_BACKGROUND = stringPreferencesKey("now_playing_background")
        private val KEY_THEME_PALETTE_STYLE = stringPreferencesKey("theme_palette_style")

        /** Igual que el default de Material You y el de MaterialKolor. */
        const val DEFAULT_PALETTE_STYLE = "TonalSpot"

        /**
         * Intentos de la lectura inicial del disco. Más de uno porque el primer acceso también
         * corre la migración desde SharedPreferences y un fallo ahí puede ser pasajero; pocos
         * porque esto corre en `runBlocking` durante el arranque.
         */
        private const val INITIAL_READ_ATTEMPTS = 3
        private val KEY_NOW_PLAYING_WAVY = booleanPreferencesKey("now_playing_wavy_progress")
        private val KEY_NOW_PLAYING_PROGRESS_THICKNESS =
            intPreferencesKey("now_playing_progress_thickness_dp")

        /**
         * Grosor por defecto de la barra de progreso, en dp. Duplica a propósito el valor de
         * `ComponentConfig.ProgressTrackHeight`: la capa de datos no depende de la de UI, y este
         * módulo no puede importar un token de Compose. Si uno cambia, cambian los dos.
         */
        const val DEFAULT_PROGRESS_THICKNESS_DP = 12
        private val KEY_NOW_PLAYING_PROGRESS_HANDLE =
            booleanPreferencesKey("now_playing_progress_handle")

        /**
         * El palo viene PUESTO: es el diseño que ya tiene la app publicada y el de las barras de
         * progreso del spec Expressive. Apagarlo lo deja como affordance de búsqueda.
         */
        const val DEFAULT_PROGRESS_HANDLE = true
        private val KEY_MINI_PLAYER_ROUNDED_RECT = booleanPreferencesKey("mini_player_rounded_rect")
        private val KEY_MINI_PLAYER_ROUND_PLAY = booleanPreferencesKey("mini_player_round_play")

        /**
         * El play del mini nace SQUIRCLE: es lo que separa por forma la acción principal del
         * "siguiente", que es redondo — con los dos redondos la jerarquía queda solo en la talla y
         * el color.
         */
        private const val DEFAULT_MINI_PLAYER_ROUND_PLAY = false
        private val KEY_LIBRARY_BOTTOM_TABS = booleanPreferencesKey("library_bottom_tabs")

        /** Las pestañas siguen ARRIBA salvo que se pida lo contrario: es la app que ya está publicada. */
        private const val DEFAULT_LIBRARY_BOTTOM_TABS = false
        private val KEY_LIBRARY_MODERN_NAV = booleanPreferencesKey("library_modern_nav")

        /**
         * El estilo moderno nace APAGADO: es una forma distinta de recorrer la biblioteca, no una
         * corrección de la que hay, y a quien ya tiene la app no se le cambia la navegación debajo
         * de los pies. Se activa desde Ajustes → Apariencia → Navegación.
         */
        const val DEFAULT_LIBRARY_MODERN_NAV = false
        private val KEY_PLAYER_GESTURES = booleanPreferencesKey("player_gestures")

        /** Los gestos vienen ENCENDIDOS: es el comportamiento que espera cualquiera. */
        private const val DEFAULT_PLAYER_GESTURES = true
        private val KEY_DOWNLOAD_CONTROL_STATE = stringPreferencesKey("download_control_state")
        private val KEY_STOP_BANNER_DISMISSED = booleanPreferencesKey("download_stop_banner_dismissed")
        private val KEY_DOWNLOAD_BANNER_MUTED = booleanPreferencesKey("download_banner_muted")
        private val KEY_STORAGE_LIMIT_BYTES = longPreferencesKey("download_storage_limit_bytes")
        private val KEY_ONEDRIVE_THROUGHPUT_MBPS = floatPreferencesKey("onedrive_throughput_mbps")
        private val KEY_LAST_SYNC_FAILURE = stringPreferencesKey("last_sync_failure")

        /** Sin medición todavía; `SyncManager` cae a su estimación de fábrica. */
        private const val DEFAULT_ONEDRIVE_THROUGHPUT_MBPS = 0f
        private val KEY_TRACK_INFO_BACKFILLED = booleanPreferencesKey("track_info_backfilled")

        private val KEY_LAST_QUEUE = stringPreferencesKey("last_queue_ids")
        private val KEY_LAST_INDEX = intPreferencesKey("last_index")
        private val KEY_LAST_POSITION = longPreferencesKey("last_position")
        private val KEY_LAST_SONG_ID = stringPreferencesKey("last_song_id")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_OFFLOAD_BROKEN = booleanPreferencesKey("audio_offload_broken")
        private val KEY_ORIGINAL_QUEUE = stringPreferencesKey("original_queue_ids")

        /**
         * Las claves que viven en el archivo de SESIÓN, no en el de configuración.
         *
         * Es la lista que consume la migración de quien actualiza y `clearQueue`, y por eso está
         * escrita UNA vez: `clearQueue` las enumeraba a mano y una clave nueva olvidada ahí se
         * habría quedado sin borrar al purgar la cola — el tipo de olvido que no falla en
         * compilación. **Una clave de sesión nueva se añade aquí.**
         *
         * `KEY_OFFLOAD_BROKEN` NO está y no debe estar: describe el DSP del dispositivo, no la
         * sesión, así que no se borra al vaciar la cola y se escribe una vez cada mucho.
         */
        private val SESSION_KEYS: List<Preferences.Key<*>> = listOf(
            KEY_LAST_QUEUE,
            KEY_LAST_INDEX,
            KEY_LAST_POSITION,
            KEY_LAST_SONG_ID,
            KEY_SHUFFLE_ENABLED,
            KEY_ORIGINAL_QUEUE
        )

        private val KEY_DELTA_TOKEN = stringPreferencesKey("delta_token")
        /** Legacy: carpeta local ÚNICA. Solo se LEE, para migrar al set (ver loadLocalFolderUris). */
        private val KEY_LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")
        private val KEY_LOCAL_FOLDER_URIS = stringSetPreferencesKey("local_folder_uris")
        /** Carpetas guardadas al activar el escaneo del dispositivo, para restaurarlas al apagarlo. */
        private val KEY_LOCAL_FOLDER_URIS_STASH = stringSetPreferencesKey("local_folder_uris_stash")
        private val KEY_SCAN_WHOLE_DEVICE = booleanPreferencesKey("scan_whole_device")
        private val KEY_LOCAL_EXCLUDED_FOLDERS = stringSetPreferencesKey("local_excluded_folders")
        private val KEY_MANUAL_COLOR_IDS = stringSetPreferencesKey("manual_color_song_ids")
        private val KEY_ONEDRIVE_FOLDER = stringPreferencesKey("onedrive_folder_path")
        private val KEY_LYRICS_SAVE_MODE = stringPreferencesKey("lyrics_save_mode")
        private val KEY_LYRICS_FOLDER_URI = stringPreferencesKey("lyrics_folder_uri")

        /**
         * DataStore de preferencias con migración automática desde SharedPreferences.
         * Los usuarios existentes conservarán sort order, delta token, cola, etc.
         */
        private val Context.musicPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = DATASTORE_NAME,
            produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, DATASTORE_NAME)) }
        )

        /**
         * Archivo de la SESIÓN de reproducción. Ver el KDoc de [Store] para por qué está separado.
         *
         * Sin `produceMigrations`: nunca existió un SharedPreferences con este nombre. Lo que sí hay
         * que traer es la sesión del archivo principal de quien actualiza, y de eso se encarga
         * [migrateSessionKeysFromMain].
         */
        private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
            name = SESSION_DATASTORE_NAME
        )

        /**
         * Archivo de los slots de reanudación por contexto. Sin `produceMigrations`: es estado
         * nuevo, nunca existió antes. Un usuario que actualiza empieza sin slots y los va ganando
         * al reproducir.
         */
        private val Context.contextSlotsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = CONTEXT_SLOTS_DATASTORE_NAME
        )
    }
}
