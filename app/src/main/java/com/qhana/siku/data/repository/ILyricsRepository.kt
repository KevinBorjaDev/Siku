package com.qhana.siku.data.repository

/**
 * Resultado de búsqueda de letras.
 */
sealed class LyricsResult {
    data class Found(val lyrics: String) : LyricsResult()
    object NotFound : LyricsResult()
    data class Error(val message: String) : LyricsResult()
}

/**
 * Candidato devuelto por la búsqueda manual (`/api/search` de LrcLib).
 * Contiene los datos suficientes para mostrar en la UI y, al elegirlo,
 * obtener las letras directamente sin un segundo request.
 */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSeconds: Double?,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean
) {
    val hasSynced: Boolean get() = !syncedLyrics.isNullOrBlank()
    val hasPlain: Boolean get() = !plainLyrics.isNullOrBlank()

    /** Texto de letras a guardar al elegir este candidato. */
    val resolvedLyrics: String?
        get() = when {
            instrumental -> "[INSTRUMENTAL]"
            !syncedLyrics.isNullOrBlank() -> syncedLyrics
            !plainLyrics.isNullOrBlank() -> plainLyrics
            else -> null
        }
}

sealed class LyricsCandidatesResult {
    data class Found(val candidates: List<LyricsCandidate>) : LyricsCandidatesResult()
    object Empty : LyricsCandidatesResult()
    data class Error(val message: String) : LyricsCandidatesResult()
}

/**
 * Interfaz para el repositorio de letras, permitiendo mocking en tests unitarios.
 */
interface ILyricsRepository {
    suspend fun getLyricsWithResult(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Double?
    ): LyricsResult

    /** Búsqueda manual: devuelve candidatos para que el usuario elija. */
    suspend fun searchCandidates(title: String, artist: String): LyricsCandidatesResult
}
