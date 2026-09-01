package com.qhana.siku.widget

import android.content.Context
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IPlaylistRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puente reproductor → widgets: observa el estado del [MusicController] y, ante cualquier
 * cambio relevante (canción, play/pausa, cola, favorito), persiste un [WidgetSnapshot]
 * y pide el re-render de ambos widgets. Vive mientras viva el proceso (lo arranca
 * MusicPlayerApp); si el proceso muere, los widgets conservan su último render y el
 * snapshot en disco los rehidrata en el siguiente arranque.
 */
@Singleton
class WidgetBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicController: MusicController,
    private val playlistRepository: IPlaylistRepository,
    private val artworkRepository: ArtworkRepository
) {

    private companion object {
        const val UP_NEXT_LIMIT = 30
    }

    /**
     * Lo que el snapshot necesita saber, sin construirlo todavía.
     *
     * Existe para que el coalescido vaya ANTES del trabajo y no después: el snapshot cuesta una
     * consulta de colores más el recorte de la cola, y el `combine` emite por cosas que no son un
     * cambio de canción (cada transición de estado del player, cada avance de índice). Con el
     * coalescido detrás, una ráfaga —restaurar la sesión, saltar cinco pistas seguidas— pagaba el
     * cómputo entero N veces para tirar N−1 resultados; delante, se paga una.
     */
    private data class WidgetSignals(
        val song: Song?,
        val state: PlaybackState,
        val playlist: List<Song>,
        val index: Int,
        val favorites: Set<String>
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            var hadSession = false
            // Solo observamos el estado del reproductor cuando hay al menos un widget colocado. La
            // fuente `getFavoritesIds` está suscrita a `songs` (JOIN), así que un sync masivo la
            // re-ejecuta decenas de veces por segundo; sostener eso en un proceso sin widgets —el
            // que levanta un worker— era CPU y consultas para nadie. `WidgetPresence.changes` reemite
            // al añadirse/quitarse un widget y `flatMapLatest` engancha o suelta el `combine` en
            // consecuencia; su semilla evalúa la presencia al arrancar. Ver [WidgetPresence].
            WidgetPresence.changes
                .map { WidgetPresence.hasAnyWidget(context) }
                .distinctUntilChanged()
                .flatMapLatest { hasWidgets ->
                    if (!hasWidgets) emptyFlow()
                    else playerStateFlow()
                }
                .collect { snapshot ->
                    when {
                        snapshot.songId != null -> {
                            hadSession = true
                            WidgetUpdater.push(context, snapshot)
                        }
                        // Null DESPUÉS de haber tenido sesión = stop() real (logout): limpiar
                        // los widgets en vez de dejarlos mostrando una canción que ya no existe.
                        hadSession -> {
                            hadSession = false
                            WidgetUpdater.push(context, WidgetSnapshot.EMPTY)
                        }
                        // Null SIN sesión previa = proceso recién levantado (currentSong aún
                        // null hasta que el restore termina): no pisar el snapshot del disco.
                    }
                }
        }
    }

    /**
     * El estado del reproductor convertido en [WidgetSnapshot], listo para renderizar. Se separa del
     * gate de presencia para que [flatMapLatest] pueda engancharlo y soltarlo entero.
     *
     * **El coalescido es `conflate()` y NO un `debounce`, y ahí había un número mágico.** Hasta el
     * 31 ago 2026 esto llevaba `debounce(150)`: un plazo elegido a ojo para adivinar cuándo termina
     * una ráfaga. `conflate` responde a la misma pregunta sin plazo — mientras el `map` + el `push`
     * están en vuelo (que es lo caro: una escritura de DataStore del snapshot, otra por widget y un
     * `update()` de Glance con su binder y su repintado en el launcher), lo que llegue se colapsa en
     * el último valor. **El ritmo lo marca lo que cuesta renderizar, que es el presupuesto honesto**,
     * y se auto-ajusta al teléfono en vez de necesitar recalibrado.
     *
     * De paso desaparecen los 150 ms de retardo que el debounce añadía SIEMPRE, incluso a un cambio
     * suelto: el widget es lo que se mira sin abrir la app.
     *
     * **Lo que NO hace falta es proteger la coherencia**, aunque el `combine` observe cinco flows
     * que se escriben por separado (`setCurrentIndex` y `_currentSong` son dos escrituras dentro
     * del mismo bloque síncrono de `onMediaItemTransition`). Un `StateFlow` es CONFLADO y este
     * colector vive en `Dispatchers.IO`, así que no puede interrumpir al hilo principal a media
     * función: cuando despierta lee el último valor de cada fuente y los estados intermedios ni se
     * observan. Esa garantía la da el tipo, no el operador — un `debounce` no añadía nada ahí, y
     * creer que sí fue lo que hizo pensar que el número era irremplazable.
     */
    private fun playerStateFlow() =
            combine(
                musicController.currentSong,
                musicController.playbackState,
                musicController.playlist,
                musicController.currentIndex,
                playlistRepository.getFavoritesIds()
            ) { song, state, playlist, index, favorites ->
                WidgetSignals(song, state, playlist, index, favorites)
            }
                // Coalesce de ráfagas (restore de sesión, saltos rápidos) SIN plazo: ver
                // [playerStateFlow]. `conflate` va antes del `.map` por lo mismo que estaba el
                // debounce — el trabajo caro es lo que hay que ahorrarse, no las emisiones.
                .conflate()
                .distinctUntilChanged()
                .map { s ->
                    // Acento de la carátula, como el NowPlaying (caché RAM→BD→extracción; para la
                    // canción en reproducción casi siempre es un hit de RAM porque el player ya
                    // la pidió). runCatching: un fallo de extracción no debe tumbar el snapshot.
                    val song = s.song
                    val colors = song?.let {
                        runCatching { artworkRepository.getAlbumColors(it) }.getOrNull()
                    }
                    WidgetSnapshot(
                        songId = song?.id,
                        title = song?.title,
                        artist = song?.artist,
                        artPath = song?.albumArtUriString,
                        isPlaying = s.state == PlaybackState.PLAYING ||
                            s.state == PlaybackState.BUFFERING,
                        isFavorite = song?.id != null && song.id in s.favorites,
                        currentIndex = s.index,
                        upNext = s.playlist
                            .drop((s.index + 1).coerceAtLeast(0))
                            .take(UP_NEXT_LIMIT)
                            .mapIndexed { offset, item ->
                                WidgetQueueItem(s.index + 1 + offset, item.title, item.artist)
                            },
                        accentLight = colors?.primary,
                        accentDark = colors?.secondary
                    )
                }
                .distinctUntilChanged()
}
