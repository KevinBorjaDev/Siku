package com.qhana.siku.data.model

/**
 * Resultado genérico unificado para operaciones que pueden fallar.
 * Reemplaza múltiples implementaciones dispersas de Result/Error en la app.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val error: AppError) : AppResult<Nothing>()
    data object Loading : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = (this as? Success)?.data

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }

    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (AppError) -> Unit): AppResult<T> {
        if (this is Error) action(error)
        return this
    }
}

/**
 * Tipos de error unificados para toda la app.
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {

    data class Network(
        override val message: String = "Error de conexión",
        override val cause: Throwable? = null,
        val isTimeout: Boolean = false
    ) : AppError(message, cause)

    data class Auth(
        override val message: String = "Error de autenticación",
        val needsRelogin: Boolean = false
    ) : AppError(message)

    data class Data(
        override val message: String = "Error de datos",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    data class NotFound(
        override val message: String = "No encontrado"
    ) : AppError(message)

    data class Playback(
        override val message: String = "Error de reproducción",
        val errorCode: Int = 0,
        val canRetry: Boolean = true
    ) : AppError(message)

    data class Unknown(
        override val message: String = "Error desconocido",
        override val cause: Throwable? = null
    ) : AppError(message, cause)

    companion object {
        fun fromException(e: Throwable): AppError = when (e) {
            is java.net.SocketTimeoutException -> Network("Timeout de conexión", e, isTimeout = true)
            is java.net.UnknownHostException -> Network("Sin conexión a internet", e)
            is java.io.IOException -> Network("Error de red", e)
            is SecurityException -> Auth("Sin permisos", needsRelogin = false)
            else -> Unknown(e.message ?: "Error desconocido", e)
        }
    }
}

/**
 * Extension para convertir resultados de Kotlin Result a AppResult.
 */
fun <T> kotlin.Result<T>.toAppResult(): AppResult<T> = fold(
    onSuccess = { AppResult.Success(it) },
    onFailure = { AppResult.Error(AppError.fromException(it)) }
)

/**
 * Helper para ejecutar código y capturar excepciones como AppResult.
 */
inline fun <T> runCatchingAsAppResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: Exception) {
    AppResult.Error(AppError.fromException(e))
}
