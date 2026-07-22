package com.qhana.siku.data.model

import androidx.media3.common.PlaybackException

/**
 * Tipo semántico de error de reproducción, derivado del código de Media3.
 * Permite manejar el error sin depender de strings frágiles del mensaje.
 */
enum class PlaybackErrorType {
    NETWORK,
    FILE_NOT_FOUND,
    DECODER,
    LOOP_DETECTED,
    UNKNOWN
}

/**
 * Información estructurada de error de reproducción emitida por MusicController.
 *
 * @property errorCode código de Media3 (`PlaybackException.ERROR_CODE_*`), o `-1` si es genérico.
 * @property message mensaje original para logs y UI.
 */
data class PlaybackErrorInfo(
    val errorCode: Int,
    val message: String
) {
    val type: PlaybackErrorType
        get() = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackErrorType.NETWORK

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorType.FILE_NOT_FOUND

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackErrorType.DECODER

            else -> if (message.contains("loop detected", ignoreCase = true))
                PlaybackErrorType.LOOP_DETECTED
            else PlaybackErrorType.UNKNOWN
        }

    companion object {
        val UNKNOWN = PlaybackErrorInfo(-1, "Unknown playback error")
    }
}
