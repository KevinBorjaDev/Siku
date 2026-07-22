package com.qhana.siku.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.EqCustomPreset
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    private val dataStore: DataStore<Preferences> = context.musicPrefsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Caché en memoria: llena sync al construir, luego se mantiene al día con un collect.
    @Volatile
    private var cache: Preferences = runBlocking {
        try {
            dataStore.data.first()
        } catch (_: Exception) {
            emptyPreferences()
        }
    }

    init {
        scope.launch {
            dataStore.data
                .catch { /* propagaremos si aparece */ }
                .collect { cache = it }
        }
    }

    private fun update(block: (MutablePreferences) -> Unit) {
        // Aplicar al caché en memoria de forma SÍNCRONA para que `loadX()` posteriores
        // vean el cambio inmediatamente. Sin esto hay race condition: por ejemplo
        // `clearDeltaToken()` seguido de `loadDeltaToken()` en la misma función devuelve
        // el token viejo porque `dataStore.edit` corre en background y el `collect` que
        // refresca el caché aún no recibió el evento.
        val mutated = cache.toMutablePreferences()
        block(mutated)
        val newCache = mutated.toPreferences()
        cache = newCache
        // Persistir a disco el SNAPSHOT del caché (no re-ejecutar `block`): así un `block`
        // no idempotente —incremento, append— no se aplica dos veces sobre bases distintas
        // (caché vs. disco) divergiendo. Como todas las escrituras pasan por aquí y el caché
        // es la fuente de verdad sincronizada, clear()+volcado del caché es consistente.
        scope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs.clear()
                    @Suppress("UNCHECKED_CAST")
                    newCache.asMap().forEach { (k, v) -> prefs[k as Preferences.Key<Any>] = v }
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicPreferences", "Error persistiendo preferencias", e)
            }
        }
    }

    // --- Sort order ---

    fun saveSortOrder(order: SortOrder, filter: SongFilter) = update {
        it[stringPreferencesKey(KEY_SORT_ORDER + filter.name)] = order.name
    }

    fun loadSortOrder(filter: SongFilter): SortOrder {
        val name = cache[stringPreferencesKey(KEY_SORT_ORDER + filter.name)]
        return try {
            SortOrder.valueOf(name ?: SortOrder.TITLE_ASC.name)
        } catch (_: Exception) {
            SortOrder.TITLE_ASC
        }
    }

    fun saveArtistSortOrder(order: ArtistSortOrder) = update { it[KEY_ARTIST_SORT] = order.name }
    fun loadArtistSortOrder(): ArtistSortOrder = try {
        ArtistSortOrder.valueOf(cache[KEY_ARTIST_SORT] ?: ArtistSortOrder.NAME.name)
    } catch (_: Exception) {
        ArtistSortOrder.NAME
    }

    fun saveAlbumSortOrder(order: AlbumSortOrder) = update { it[KEY_ALBUM_SORT] = order.name }
    fun loadAlbumSortOrder(): AlbumSortOrder = try {
        AlbumSortOrder.valueOf(cache[KEY_ALBUM_SORT] ?: AlbumSortOrder.NAME.name)
    } catch (_: Exception) {
        AlbumSortOrder.NAME
    }

    // --- Keep screen on ---

    fun saveKeepScreenOn(enabled: Boolean) = update {
        it[KEY_KEEP_SCREEN_ON] = enabled
    }

    fun loadKeepScreenOn(): Boolean = cache[KEY_KEEP_SCREEN_ON] ?: false

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

    /**
     * Guarda los IDs de la cola (String desde v13). Se serializa usando ``
     * (Unit Separator) como delimitador: inofensivo dentro de IDs remotos de OneDrive.
     */
    fun saveQueueIds(queueIds: List<String>) = update {
        it[KEY_LAST_QUEUE] = queueIds.joinToString(QUEUE_DELIMITER)
    }

    fun loadQueueIds(): List<String> {
        val raw = cache[KEY_LAST_QUEUE] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLastState(index: Int, position: Long, songId: String? = null) = update {
        it[KEY_LAST_INDEX] = index
        it[KEY_LAST_POSITION] = position
        // ID de la canción actual: la restauración lo prefiere sobre el índice, que se
        // desplaza si alguna canción de la cola guardada fue borrada de la BD.
        if (songId != null) it[KEY_LAST_SONG_ID] = songId else it.remove(KEY_LAST_SONG_ID)
    }

    fun loadLastState(): Triple<Int, Long, String?> {
        val index = cache[KEY_LAST_INDEX] ?: 0
        val position = cache[KEY_LAST_POSITION] ?: 0L
        return Triple(index, position, cache[KEY_LAST_SONG_ID])
    }

    /**
     * Estado de aleatorio de la sesión. Se guarda junto al ORDEN ORIGINAL de la cola porque la
     * cola persistida ya está barajada: sin el orden original, al restaurar no habría a dónde
     * volver al apagar el aleatorio (y el botón quedaría encendido mintiendo).
     */
    fun saveShuffleState(enabled: Boolean, originalQueueIds: List<String>) = update {
        it[KEY_SHUFFLE_ENABLED] = enabled
        if (enabled && originalQueueIds.isNotEmpty()) {
            it[KEY_ORIGINAL_QUEUE] = originalQueueIds.joinToString(QUEUE_DELIMITER)
        } else {
            it.remove(KEY_ORIGINAL_QUEUE)
        }
    }

    fun loadShuffleEnabled(): Boolean = cache[KEY_SHUFFLE_ENABLED] ?: false

    fun loadOriginalQueueIds(): List<String> {
        val raw = cache[KEY_ORIGINAL_QUEUE] ?: return emptyList()
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

    // --- Fuente local (Fase 3): tree URI de SAF con permiso persistido ---

    /** Guarda el tree URI (`ACTION_OPEN_DOCUMENT_TREE`) de la carpeta de música local. */
    fun saveLocalFolderUri(uri: String) = update {
        it[KEY_LOCAL_FOLDER_URI] = uri
    }

    /** Tree URI de la carpeta local elegida, o null si el usuario no configuró ninguna. */
    fun loadLocalFolderUri(): String? = cache[KEY_LOCAL_FOLDER_URI]

    fun clearLocalFolderUri() = update {
        it.remove(KEY_LOCAL_FOLDER_URI)
    }

    fun clearQueue() = update {
        it.remove(KEY_LAST_QUEUE)
        it.remove(KEY_LAST_INDEX)
        it.remove(KEY_LAST_POSITION)
        it.remove(KEY_LAST_SONG_ID)
        it.remove(KEY_DELTA_TOKEN)
        it.remove(KEY_SHUFFLE_ENABLED)
        it.remove(KEY_ORIGINAL_QUEUE)
    }

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
    fun loadEqEnabled(): Boolean = cache[KEY_EQ_ENABLED] ?: false

    /**
     * Flow reactivo del toggle del EQ: lo cambia PlaybackViewModel (hoja del NowPlaying) y lo
     * observa MusicPlaybackService, que necesita reconstruir la pipeline de audio (y ceder o
     * recuperar el offload) cuando cambia.
     */
    val eqEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[KEY_EQ_ENABLED] ?: false }

    // --- Toolbar del NowPlaying (orden + barra/overflow de cada acción) ---
    // Reactivo: el NowPlaying observa el flow y la barra se reordena en vivo al guardar en Ajustes.
    val toolbarConfigFlow: Flow<List<ToolbarActionState>> =
        dataStore.data.map { PlayerToolbarConfig.decode(it[KEY_TOOLBAR_CONFIG]) }

    fun loadToolbarConfig(): List<ToolbarActionState> =
        PlayerToolbarConfig.decode(cache[KEY_TOOLBAR_CONFIG])

    fun saveToolbarConfig(list: List<ToolbarActionState>) = update {
        it[KEY_TOOLBAR_CONFIG] = PlayerToolbarConfig.encode(list)
    }

    // --- Contextos reproducidos recientes (sección "Seguir escuchando" del home) ---
    // Historial de "lugares" reanudables (álbum/artista/lista/favoritos/aleatorio/biblioteca), de
    // más reciente a más antiguo. Reactivo: la home se actualiza sola al grabar un contexto.
    val recentContextsFlow: Flow<List<PlaybackContext>> =
        dataStore.data.map { PlaybackContext.decode(it[KEY_RECENT_CONTEXTS]) }

    /** Antepone un contexto al historial (dedup por identidad + tope). Read-modify-write atómico. */
    fun recordContext(ctx: PlaybackContext) = update {
        val current = PlaybackContext.decode(it[KEY_RECENT_CONTEXTS])
        it[KEY_RECENT_CONTEXTS] = PlaybackContext.encode(PlaybackContext.prepend(current, ctx))
    }

    fun clearRecentContexts() = update { it.remove(KEY_RECENT_CONTEXTS) }

    /** Nº de bandas del EQ propio (5 o 10). */
    fun saveEqBandCount(count: Int) = update { it[KEY_EQ_BAND_COUNT] = count }
    fun loadEqBandCount(): Int = (cache[KEY_EQ_BAND_COUNT] ?: 5).let { if (it == 10) 10 else 5 }

    // Las ganancias se guardan POR MODO (clave distinta para 5 y 10 bandas): al alternar
    // el nº de bandas se recupera la curva que el usuario tenía en ese modo, en vez de
    // truncar/estirar una a la otra (las frecuencias centrales no se corresponden).
    private fun eqGainsKey(bandCount: Int) =
        if (bandCount == 10) KEY_EQ_GAINS_10 else KEY_EQ_GAINS

    fun saveEqBandGains(bandCount: Int, gains: FloatArray) = update {
        it[eqGainsKey(bandCount)] = gains.joinToString(",")
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

    // --- Presets personalizados del EQ ---
    // Se serializan como un JSON array en una sola clave (org.json, sin Gson → sin regla
    // ProGuard). Cada preset guarda su curva cruda y el modo de bandas de captura; el nombre
    // puede contener comas/saltos, por eso NO se usa el encoding delimitado de las ganancias.

    fun loadCustomEqPresets(): List<EqCustomPreset> =
        parseCustomPresets(cache[KEY_EQ_CUSTOM_PRESETS])

    fun saveCustomEqPresets(presets: List<EqCustomPreset>) = update {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("bandCount", p.bandCount)
                    .put("gains", JSONArray().apply { p.gains.forEach { put(it.toDouble()) } })
            )
        }
        it[KEY_EQ_CUSTOM_PRESETS] = arr.toString()
    }

    /** Reactivo: la hoja del EQ edita/aplica presets; puede haber varias instancias del ViewModel. */
    val customEqPresetsFlow: Flow<List<EqCustomPreset>> =
        dataStore.data.map { parseCustomPresets(it[KEY_EQ_CUSTOM_PRESETS]) }

    private fun parseCustomPresets(raw: String?): List<EqCustomPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val g = o.getJSONArray("gains")
                EqCustomPreset(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    bandCount = if (o.getInt("bandCount") == 10) 10 else 5,
                    gains = FloatArray(g.length()) { g.getDouble(it).toFloat() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Preferir el ecualizador DEL SISTEMA (MIUI/panel estándar): el botón EQ del NowPlaying
     * lo abre directamente en vez de la hoja propia. Activarlo apaga el EQ propio (evita
     * ecualizar dos veces) — eso lo hace el setter del ViewModel, no esta capa.
     */
    fun saveUseSystemEq(enabled: Boolean) = update { it[KEY_USE_SYSTEM_EQ] = enabled }
    fun loadUseSystemEq(): Boolean = cache[KEY_USE_SYSTEM_EQ] ?: false

    /** "No volver a mostrar" del aviso de doble ecualización al encender el EQ propio. */
    fun saveEqConflictWarningSuppressed(suppressed: Boolean) =
        update { it[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] = suppressed }
    fun loadEqConflictWarningSuppressed(): Boolean = cache[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] ?: false

    // --- Duplicados entre fuentes (v23) ---

    /**
     * Bootstrap v23 (una sola vez): el delta incremental nunca re-envía items sin cambios,
     * así que las filas de nube pre-migración jamás recibirían su relativePath — el primer
     * sync tras actualizar limpia el delta token para forzar UN full scan de backfill.
     */
    fun saveRelPathBackfillDone() = update { it[KEY_RELPATH_BACKFILL] = true }
    fun loadRelPathBackfillDone(): Boolean = cache[KEY_RELPATH_BACKFILL] ?: false

    /**
     * Bootstrap v24 (una sola vez): re-lee el tag GENRE de las canciones ya descargadas para
     * poblar los chips de género del inicio (las nuevas lo reciben en el pipeline de análisis).
     */
    fun saveGenreBackfillDone() = update { it[KEY_GENRE_BACKFILL] = true }
    fun loadGenreBackfillDone(): Boolean = cache[KEY_GENRE_BACKFILL] ?: false

    /** null = aún no se preguntó (el sync detecta y dispara el diálogo de decisión). */
    fun saveDuplicatePolicy(policy: DuplicatePolicy) = update { it[KEY_DUPLICATE_POLICY] = policy.name }
    fun loadDuplicatePolicy(): DuplicatePolicy? =
        cache[KEY_DUPLICATE_POLICY]?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }

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
        dataStore.data.map { it[KEY_USE_SYSTEM_EQ] ?: false }

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
        dataStore.data.map {
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
        dataStore.data.map { it[KEY_STOP_BANNER_DISMISSED] ?: false }

    /**
     * "No volver a mostrar": silencia el banner de descargas PARA SIEMPRE. Nadie lo resetea
     * (a diferencia del cierre "una vez"); el estado de descargas sigue visible y controlable
     * desde el Download Manager, que tiene sus propios botones en la top bar.
     */
    fun saveDownloadBannerMuted(muted: Boolean) = update { it[KEY_DOWNLOAD_BANNER_MUTED] = muted }
    fun loadDownloadBannerMuted(): Boolean = cache[KEY_DOWNLOAD_BANNER_MUTED] ?: false
    val downloadBannerMutedFlow: Flow<Boolean> =
        dataStore.data.map { it[KEY_DOWNLOAD_BANNER_MUTED] ?: false }

    // --- Tope de almacenamiento para descargas (caché LRU) ---

    /**
     * Límite de bytes que puede ocupar el audio descargado de la nube. 0 = sin límite.
     * Al superarse, el sync masivo frena y las descargas por reproducción desalojan las
     * canciones menos/menos-recientemente reproducidas para hacer sitio.
     */
    fun saveStorageLimitBytes(bytes: Long) = update { it[KEY_STORAGE_LIMIT_BYTES] = bytes.coerceAtLeast(0L) }
    fun loadStorageLimitBytes(): Long = cache[KEY_STORAGE_LIMIT_BYTES] ?: 0L
    val storageLimitBytesFlow: Flow<Long> =
        dataStore.data.map { it[KEY_STORAGE_LIMIT_BYTES] ?: 0L }

    // --- Now Playing ---

    fun saveNowPlayingSolidBackground(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_SOLID_BG] = enabled
    }

    fun loadNowPlayingSolidBackground(): Boolean = cache[KEY_NOW_PLAYING_SOLID_BG] ?: false

    /**
     * Flow reactivo del ajuste de fondo: lo cambia LibraryViewModel (Ajustes) y lo observa
     * PlaybackViewModel (NowPlaying), que son instancias distintas — la caché síncrona no
     * alcanza para propagar el cambio en vivo.
     */
    val nowPlayingSolidBackgroundFlow: Flow<Boolean> =
        dataStore.data.map { it[KEY_NOW_PLAYING_SOLID_BG] ?: false }

    /**
     * Barra de progreso ONDULADA (M3 Expressive) en el NowPlaying, en vez de la píldora plana.
     * Solo aplica al reproductor grande: en el MiniPlayer la barra mide 3dp y la onda no se
     * leería. Default false (la píldora es el diseño actual).
     */
    fun saveNowPlayingWavyProgress(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_WAVY] = enabled
    }

    fun loadNowPlayingWavyProgress(): Boolean = cache[KEY_NOW_PLAYING_WAVY] ?: false

    /** Reactivo por el mismo motivo que [nowPlayingSolidBackgroundFlow] (Ajustes ↔ NowPlaying). */
    val nowPlayingWavyProgressFlow: Flow<Boolean> =
        dataStore.data.map { it[KEY_NOW_PLAYING_WAVY] ?: false }

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
        dataStore.data.map { it[KEY_THEME_PALETTE_STYLE] ?: DEFAULT_PALETTE_STYLE }

    companion object {
        private const val DATASTORE_NAME = "music_player_prefs"
        private const val KEY_SORT_ORDER = "sort_order"
        private val KEY_ARTIST_SORT = stringPreferencesKey("artist_sort_order")
        private val KEY_ALBUM_SORT = stringPreferencesKey("album_sort_order")
        private val KEY_DUPLICATE_POLICY = stringPreferencesKey("duplicate_policy")
        private val KEY_RELPATH_BACKFILL = booleanPreferencesKey("relpath_backfill_done")
        private val KEY_GENRE_BACKFILL = booleanPreferencesKey("genre_backfill_done")
        private const val QUEUE_DELIMITER = "" // Unit Separator

        // DataStore keys tipadas
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        private val KEY_REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode")
        private val KEY_REPLAYGAIN_PREAMP = floatPreferencesKey("replaygain_preamp")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_GAINS = stringPreferencesKey("eq_band_gains")
        private val KEY_EQ_GAINS_10 = stringPreferencesKey("eq_band_gains_10")
        private val KEY_EQ_BAND_COUNT = intPreferencesKey("eq_band_count")
        private val KEY_EQ_CUSTOM_PRESETS = stringPreferencesKey("eq_custom_presets")
        private val KEY_EQ_CONFLICT_WARNING_SUPPRESSED = booleanPreferencesKey("eq_conflict_warning_suppressed")
        private val KEY_USE_SYSTEM_EQ = booleanPreferencesKey("use_system_eq")
        private val KEY_ARTIST_PHOTOS_METERED = booleanPreferencesKey("artist_photos_metered")
        private val KEY_ARTIST_PHOTOS_BANNER = booleanPreferencesKey("artist_photos_banner_enabled")
        private val KEY_ARTIST_PHOTO_DETAIL_METERED = booleanPreferencesKey("artist_photo_detail_metered")
        private val KEY_TOOLBAR_CONFIG = stringPreferencesKey("player_toolbar_config")
        private val KEY_RECENT_CONTEXTS = stringPreferencesKey("recent_playback_contexts")
        private val KEY_NOW_PLAYING_SOLID_BG = booleanPreferencesKey("now_playing_solid_bg")
        private val KEY_THEME_PALETTE_STYLE = stringPreferencesKey("theme_palette_style")

        /** Igual que el default de Material You y el de MaterialKolor. */
        const val DEFAULT_PALETTE_STYLE = "TonalSpot"
        private val KEY_NOW_PLAYING_WAVY = booleanPreferencesKey("now_playing_wavy_progress")
        private val KEY_DOWNLOAD_CONTROL_STATE = stringPreferencesKey("download_control_state")
        private val KEY_STOP_BANNER_DISMISSED = booleanPreferencesKey("download_stop_banner_dismissed")
        private val KEY_DOWNLOAD_BANNER_MUTED = booleanPreferencesKey("download_banner_muted")
        private val KEY_STORAGE_LIMIT_BYTES = longPreferencesKey("download_storage_limit_bytes")

        private val KEY_LAST_QUEUE = stringPreferencesKey("last_queue_ids")
        private val KEY_LAST_INDEX = intPreferencesKey("last_index")
        private val KEY_LAST_POSITION = longPreferencesKey("last_position")
        private val KEY_LAST_SONG_ID = stringPreferencesKey("last_song_id")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_OFFLOAD_BROKEN = booleanPreferencesKey("audio_offload_broken")
        private val KEY_ORIGINAL_QUEUE = stringPreferencesKey("original_queue_ids")
        private val KEY_DELTA_TOKEN = stringPreferencesKey("delta_token")
        private val KEY_LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")
        private val KEY_MANUAL_COLOR_IDS = stringSetPreferencesKey("manual_color_song_ids")

        /**
         * DataStore de preferencias con migración automática desde SharedPreferences.
         * Los usuarios existentes conservarán sort order, delta token, cola, etc.
         */
        private val Context.musicPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = DATASTORE_NAME,
            produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, DATASTORE_NAME)) }
        )
    }
}
