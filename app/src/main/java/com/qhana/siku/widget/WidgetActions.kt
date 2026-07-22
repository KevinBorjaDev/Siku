package com.qhana.siku.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.qhana.siku.data.repository.IPlaylistRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Acciones de los widgets. Corren en el proceso de la app (broadcast de Glance), así que
 * acceden a los singletons de Hilt vía EntryPoint. Con el proceso en frío, [ensureReady]
 * dispara `initialize()` (que además restaura la última sesión desde la BD con
 * autoPlay=false) y espera la conexión antes de mandar el comando.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun musicController(): MusicController
    fun playlistRepository(): IPlaylistRepository
}

internal fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)

private suspend fun ensureReady(controller: MusicController): Boolean {
    if (controller.isConnected && controller.playlist.value.isNotEmpty()) return true
    controller.initialize()
    // Espera por SEÑAL (conexión y cola restaurada son estado observable), no por poll.
    // Sin sesión guardada la cola nunca se llena: el timeout corta y el fallback manda el
    // comando igual si al menos hay conexión (no-op inofensivo sobre cola vacía).
    return withTimeoutOrNull(6_000) {
        controller.connectionState.first { it }
        controller.playlist.first { it.isNotEmpty() }
        true
    } ?: controller.isConnected
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = widgetEntryPoint(context).musicController()
        // MediaController (Media3) exige el hilo PRINCIPAL; los ActionCallback de Glance
        // corren en un worker → sin esto el comando lanza IllegalStateException y el botón
        // parece muerto.
        val sent = withContext(Dispatchers.Main) {
            ensureReady(controller).also { if (it) controller.playPause() }
        }
        // Flip OPTIMISTA del icono: feedback inmediato y simétrico (play y pausa por igual);
        // el bridge confirma ~150ms después con el estado real del player.
        if (sent) {
            val snapshot = WidgetSnapshotStore.read(context)
            if (snapshot.songId != null) {
                WidgetUpdater.push(context, snapshot.copy(isPlaying = !snapshot.isPlaying))
            }
        }
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = widgetEntryPoint(context).musicController()
        withContext(Dispatchers.Main) {
            if (ensureReady(controller)) controller.next()
        }
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = widgetEntryPoint(context).musicController()
        withContext(Dispatchers.Main) {
            // Misma semántica que en la app: >3s reinicia la canción, si no salta a la anterior.
            if (ensureReady(controller)) controller.previous()
        }
    }
}

class PlayFromQueueAction : ActionCallback {
    companion object {
        val KEY_INDEX = ActionParameters.Key<Int>("queue_index")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[KEY_INDEX] ?: return
        val controller = widgetEntryPoint(context).musicController()
        withContext(Dispatchers.Main) {
            if (ensureReady(controller)) controller.playAt(index)
        }
    }
}

class ToggleFavoriteAction : ActionCallback {
    companion object {
        val KEY_SONG_ID = ActionParameters.Key<String>("song_id")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val songId = parameters[KEY_SONG_ID] ?: return
        // No necesita el player: opera directo sobre el repositorio.
        widgetEntryPoint(context).playlistRepository().toggleFavorite(songId)
        // Feedback inmediato también en frío (el bridge solo re-renderiza si hay sesión
        // viva): reflejar el flip en el snapshot y empujarlo a los widgets.
        val snapshot = WidgetSnapshotStore.read(context)
        if (snapshot.songId == songId) {
            WidgetUpdater.push(context, snapshot.copy(isFavorite = !snapshot.isFavorite))
        }
    }
}
