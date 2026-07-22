package com.qhana.siku.widget

import android.content.Context
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IPlaylistRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            var hadSession = false
            combine(
                musicController.currentSong,
                musicController.playbackState,
                musicController.playlist,
                musicController.currentIndex,
                playlistRepository.getFavoritesIds()
            ) { song, state, playlist, index, favorites ->
                // Acento de la carátula, como el NowPlaying (caché RAM→BD→extracción; para la
                // canción en reproducción casi siempre es un hit de RAM porque el player ya
                // la pidió). runCatching: un fallo de extracción no debe tumbar el snapshot.
                val colors = song?.let { runCatching { artworkRepository.getAlbumColors(it) }.getOrNull() }
                WidgetSnapshot(
                    songId = song?.id,
                    title = song?.title,
                    artist = song?.artist,
                    artPath = song?.albumArtUriString,
                    isPlaying = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING,
                    isFavorite = song?.id != null && song.id in favorites,
                    currentIndex = index,
                    upNext = playlist
                        .drop((index + 1).coerceAtLeast(0))
                        .take(UP_NEXT_LIMIT)
                        .mapIndexed { offset, s ->
                            WidgetQueueItem(index + 1 + offset, s.title, s.artist)
                        },
                    accentLight = colors?.primary,
                    accentDark = colors?.secondary
                )
            }
                // Coalesce de ráfagas (restore de sesión, saltos rápidos) en un solo render.
                .debounce(150)
                .distinctUntilChanged()
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
}
