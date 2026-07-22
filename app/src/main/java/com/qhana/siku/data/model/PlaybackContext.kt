package com.qhana.siku.data.model

import android.util.Base64

/**
 * Un "lugar" reanudable que el usuario reprodujo: un álbum, un artista completo, una lista (o
 * favoritos) o la biblioteca entera (en orden o aleatoria). Alimenta la sección "Seguir
 * escuchando" del inicio, que recuerda los ÚLTIMOS CONTEXTOS reproducidos —no canciones sueltas
 * del historial—, de más reciente a más antiguo, cada uno reanudable como tal.
 *
 * [key] es la identidad para deduplicar: reproducir de nuevo el mismo contexto lo sube al frente,
 * no lo duplica. La etiqueta/carátula son un SNAPSHOT de visualización tomado al grabar (se
 * persisten para pintar la tarjeta sin joins; si el nombre/carátula cambian, se refrescan la
 * próxima vez que se reproduzca ese contexto).
 */
sealed class PlaybackContext {
    abstract val key: String

    data class Album(val name: String, val coverUri: String?) : PlaybackContext() {
        override val key get() = "album:$name"
    }

    data class Artist(val name: String, val coverUri: String?) : PlaybackContext() {
        override val key get() = "artist:$name"
    }

    data class Playlist(val id: Long, val name: String, val coverUri: String?) : PlaybackContext() {
        override val key get() = "playlist:$id"
    }

    data object Favorites : PlaybackContext() {
        override val key get() = "favorites"
    }

    data object LibraryShuffle : PlaybackContext() {
        override val key get() = "shuffle"
    }

    data object LibraryAll : PlaybackContext() {
        override val key get() = "all"
    }

    companion object {
        /** Tope de contextos recordados en "Seguir escuchando". */
        const val MAX_RECENTS = 12

        // Persistencia: una línea por contexto; los campos de texto libre (nombre, URI de
        // carátula) van en Base64 URL-safe (sin '|' ni '\n') para neutralizar separadores.
        //   A|<b64 name>|<b64 cover>       álbum
        //   R|<b64 name>|<b64 cover>       artista
        //   P|<id>|<b64 name>|<b64 cover>  lista
        //   F / S / L                      favoritos / aleatorio / biblioteca
        fun encode(list: List<PlaybackContext>): String =
            list.joinToString("\n") { ctx ->
                when (ctx) {
                    is Album -> "A|${b64(ctx.name)}|${b64(ctx.coverUri)}"
                    is Artist -> "R|${b64(ctx.name)}|${b64(ctx.coverUri)}"
                    is Playlist -> "P|${ctx.id}|${b64(ctx.name)}|${b64(ctx.coverUri)}"
                    Favorites -> "F"
                    LibraryShuffle -> "S"
                    LibraryAll -> "L"
                }
            }

        fun decode(raw: String?): List<PlaybackContext> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split("\n").mapNotNull { line ->
                val parts = line.split("|")
                when (parts.getOrNull(0)) {
                    "A" -> Album(unb64(parts.getOrNull(1)) ?: return@mapNotNull null, unb64(parts.getOrNull(2)))
                    "R" -> Artist(unb64(parts.getOrNull(1)) ?: return@mapNotNull null, unb64(parts.getOrNull(2)))
                    "P" -> {
                        val id = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                        val name = unb64(parts.getOrNull(2)) ?: return@mapNotNull null
                        Playlist(id, name, unb64(parts.getOrNull(3)))
                    }
                    "F" -> Favorites
                    "S" -> LibraryShuffle
                    "L" -> LibraryAll
                    else -> null
                }
            }
        }

        /** Antepone [ctx] al historial deduplicando por [key] y recorta a [MAX_RECENTS]. */
        fun prepend(current: List<PlaybackContext>, ctx: PlaybackContext): List<PlaybackContext> =
            (listOf(ctx) + current.filterNot { it.key == ctx.key }).take(MAX_RECENTS)

        private val FLAGS = Base64.NO_WRAP or Base64.URL_SAFE

        private fun b64(s: String?): String =
            if (s.isNullOrEmpty()) "" else Base64.encodeToString(s.toByteArray(), FLAGS)

        private fun unb64(s: String?): String? =
            if (s.isNullOrEmpty()) null
            else runCatching { String(Base64.decode(s, FLAGS)) }.getOrNull()
    }
}
