package com.qhana.siku.data.remote

import android.util.Log
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import dagger.Lazy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepta respuestas 401 de la API Microsoft Graph: refresca el token silenciosamente
 * vía MSAL y reintenta la request una sola vez con el nuevo token.
 *
 * Requisitos:
 * - La request original debe llevar un header `Authorization` (de lo contrario el interceptor no hace nada).
 * - Solo se reintenta UNA vez; se marca con `X-Retry-Auth: 1` para evitar loops.
 *
 * Nota: usa `dagger.Lazy<AuthManager>` para romper cualquier dependencia circular potencial
 * entre Hilt → OkHttp → Interceptor → AuthManager. `runBlocking` es aceptable aquí porque
 * los interceptors de OkHttp ya son bloqueantes por diseño.
 */
@Singleton
class AuthRefreshInterceptor @Inject constructor(
    private val authManagerProvider: Lazy<AuthManager>
) : Interceptor {

    companion object {
        private const val TAG = "AuthRefreshInterceptor"
        private const val HEADER_RETRY_MARKER = "X-Retry-Auth"

        /**
         * Las rutas de la carpeta de la app (backup de playlists) necesitan el scope de escritura.
         * Refrescar con los scopes de lectura devolvería un token válido pero sin permiso: el
         * reintento moriría en 403.
         */
        private const val APP_FOLDER_SEGMENT = "approot"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 401) return response
        // Solo intentamos refrescar si la request original traía auth
        if (request.header("Authorization") == null) return response
        // Evitamos loops infinitos si el retry también devuelve 401
        if (request.header(HEADER_RETRY_MARKER) != null) return response

        Log.w(TAG, "401 recibido en ${request.url.encodedPath}, refrescando token")
        response.close()

        val needsWriteScope = request.url.encodedPath.contains(APP_FOLDER_SEGMENT)
        val newToken = try {
            runBlocking {
                val authManager = authManagerProvider.get()
                if (needsWriteScope) authManager.getBackupAccessToken().firstOrNull()
                else authManager.getAccessToken().firstOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refrescando token", e)
            null
        }

        if (newToken !is AuthResult.Success || newToken.token.isBlank()) {
            Log.e(TAG, "Token refresh falló, devolviendo 401 original")
            return chain.proceed(request)
        }

        val retryRequest = request.newBuilder()
            .header("Authorization", "Bearer ${newToken.token}")
            .header(HEADER_RETRY_MARKER, "1")
            .build()
        return chain.proceed(retryRequest)
    }
}
