package com.qhana.siku.ui.viewmodel

import android.content.Context
import com.qhana.siku.R

/**
 * Textos de confirmación de las operaciones sobre listas. Viven aquí y no en cada ViewModel porque
 * las dos que crean listas (`LibraryViewModel` y `PlaybackViewModel`, que guarda la cola) tienen que
 * decir lo mismo con las mismas palabras: dos copias del `when` acabarían diciendo cosas distintas.
 */

/**
 * "Lista X creada", con el número de canciones si nació con alguna. Es UN aviso para las dos cosas:
 * crear y añadir en el mismo gesto son una sola acción para el usuario, y dos snackbars seguidos
 * harían esperar al segundo detrás del primero.
 */
internal fun playlistCreatedMessage(context: Context, name: String, songCount: Int): String =
    if (songCount > 0) {
        context.resources.getQuantityString(R.plurals.playlist_created_with_songs, songCount, songCount, name)
    } else {
        context.getString(R.string.playlist_created, name)
    }

/**
 * Resultado de marcar o desmarcar favorito. Se dice aunque el corazón ya cambie de relleno: desde el
 * menú ⋮ el menú se cierra al tocar y el cambio no se ve en ningún sitio.
 */
internal fun favoriteToggledMessage(context: Context, isFavorite: Boolean): String {
    val favorites = context.getString(R.string.common_favorites)
    return if (isFavorite) {
        context.getString(R.string.playlist_song_added, favorites)
    } else {
        context.getString(R.string.playlist_song_removed, favorites)
    }
}

/**
 * Resultado de añadir [requested] canciones a la lista [playlistName], de las que entraron [added]
 * (las que ya estaban se ignoran). El caso "no entró ninguna" también se dice: callarlo deja al
 * usuario sin saber si el toque hizo algo.
 */
internal fun songsAddedMessage(context: Context, playlistName: String, requested: Int, added: Int): String =
    when {
        added == 0 && requested == 1 -> context.getString(R.string.playlist_song_already_in, playlistName)
        added == 0 -> context.getString(R.string.playlist_songs_all_already_in, playlistName)
        requested == 1 -> context.getString(R.string.playlist_song_added, playlistName)
        else -> context.resources.getQuantityString(R.plurals.playlist_songs_added, added, added, playlistName)
    }
