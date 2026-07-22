package com.qhana.siku.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

// --- LrcLib ---

/**
 * Respuesta de `/api/get` (match exacto). También se usa como elemento de la lista
 * que devuelve `/api/search`, donde `id`/`trackName`/`artistName` siempre vienen
 * y `syncedLyrics`/`plainLyrics` pueden ser nulos.
 */
data class LrcLibResponse(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean? = null
)

interface LrcLibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artist: String,
        @Query("track_name") title: String,
        @Query("album_name") album: String? = null,
        @Query("duration") duration: Double? = null
    ): LrcLibResponse

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") title: String,
        @Query("artist_name") artist: String? = null
    ): List<LrcLibResponse>
}
