package com.qhana.siku.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * API pública de Deezer (sin auth) — solo se usa para buscar FOTOS de artista.
 * https://api.deezer.com/search/artist?q=...
 */
data class DeezerArtistDto(
    val id: Long,
    val name: String? = null,
    @SerializedName("picture_medium") val pictureMedium: String? = null,
    @SerializedName("picture_big") val pictureBig: String? = null,
    @SerializedName("picture_xl") val pictureXl: String? = null
)

data class DeezerArtistSearchResponse(
    val data: List<DeezerArtistDto>? = null
)

interface DeezerApi {

    @GET("search/artist")
    suspend fun searchArtists(
        @Query("q") query: String,
        @Query("limit") limit: Int = 15
    ): DeezerArtistSearchResponse
}
