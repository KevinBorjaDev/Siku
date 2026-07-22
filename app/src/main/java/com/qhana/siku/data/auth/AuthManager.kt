package com.qhana.siku.data.auth

import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var publicClientApplication: ISingleAccountPublicClientApplication? = null
    /**
     * Scopes de la operación normal (delta scan, descargas). Se piden en CADA token silencioso,
     * así que NO deben incluir el de escritura: si lo hicieran, una sesión consentida antes de
     * que ese scope existiera fallaría el refresco y se caería toda la sincronización, no solo
     * el backup.
     */
    private val readScopes = listOf("Files.Read", "User.Read")

    /**
     * Escritura limitada a la carpeta propia de la app (`/drive/special/approot`, visible como
     * `Apps/<app>/` en OneDrive), donde vive el backup de playlists. Se prefiere a
     * `Files.ReadWrite`, que daría permiso sobre TODOS los archivos del usuario por un solo JSON.
     */
    private val backupScopes = readScopes + "Files.ReadWrite.AppFolder"

    /** El login pide todo de una: un usuario nuevo consiente los tres scopes en una pantalla. */
    private val scopes = backupScopes.toTypedArray()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val msalInitialized = CompletableDeferred<Boolean>()

    init {
        // MSAL realiza I/O en disco durante la inicialización, por lo que no debe bloquear el Main Thread.
        // Especialmente crítico tras restauración de proceso o actualizaciones de caché.
        scope.launch {
            try {
                Log.d(TAG, "Inicializando MSAL en Dispatchers.IO...")
                PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    com.qhana.siku.R.raw.auth_config_single_account,
                    object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                        override fun onCreated(application: ISingleAccountPublicClientApplication) {
                            Log.d(TAG, "MSAL inicializado correctamente")
                            publicClientApplication = application
                            msalInitialized.complete(true)
                        }

                        override fun onError(exception: MsalException) {
                            Log.e(TAG, "Error inicializando MSAL", exception)
                            msalInitialized.complete(false)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Excepción fatal en init de AuthManager", e)
                msalInitialized.complete(false)
            }
        }
    }

    companion object {
        private const val TAG = "AuthManager"

        // Techo para la inicialización de MSAL (lee su config y credenciales de disco). Si se
        // agota se asume "sin sesión": es preferible mandar al onboarding que dejar el arranque
        // colgado esperando a una librería que quedó en mal estado.
        private const val MSAL_INIT_TIMEOUT_MS = 10_000L
    }

    fun signIn(activity: android.app.Activity): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            Log.w(TAG, "MSAL no inicializado al intentar login")
            trySend(AuthResult.Error("MSAL not initialized"))
            close()
            return@callbackFlow
        }

        // API no deprecada (MSAL 8.x): SignInParameters en vez de la sobrecarga
        // signIn(Activity, String?, Array<String>, callback). Mismo comportamiento.
        val signInParameters = SignInParameters.builder()
            .withActivity(activity)
            .withLoginHint(null)
            .withScopes(scopes.toList())
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Login exitoso")
                    trySend(AuthResult.Success(authenticationResult.accessToken))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Error en login", exception)
                    trySend(AuthResult.Error(exception.message ?: "Login failed"))
                    close()
                }

                override fun onCancel() {
                    Log.d(TAG, "Login cancelado por usuario")
                    trySend(AuthResult.Cancelled)
                    close()
                }
            })
            .build()
        app.signIn(signInParameters)
        awaitClose()
    }

    /**
     * Token para escribir el backup en la carpeta de la app. Falla (sin romper nada más) si la
     * sesión guardada se consintió antes de que existiera `Files.ReadWrite.AppFolder`: la UI del
     * backup lo traduce a "vuelve a conectar OneDrive".
     */
    fun getBackupAccessToken(): Flow<AuthResult> = acquireTokenSilent(backupScopes)

    /** Token de la operación normal (scan, descargas). */
    fun getAccessToken(): Flow<AuthResult> = acquireTokenSilent(readScopes)

    private fun acquireTokenSilent(requestedScopes: List<String>): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            trySend(AuthResult.Error("MSAL not initialized"))
            close()
            return@callbackFlow
        }

        val account = withContext(Dispatchers.IO) {
            app.currentAccount?.currentAccount
        }
        
        if (account == null) {
            trySend(AuthResult.Error("No account signed in"))
            close()
            return@callbackFlow
        }

        val params = AcquireTokenSilentParameters.Builder()
            .withScopes(requestedScopes)
            .forAccount(account)
            .fromAuthority(account.authority)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    trySend(AuthResult.Success(authenticationResult.accessToken))
                    close()
                }

                override fun onError(exception: MsalException) {
                    trySend(AuthResult.Error(exception.message ?: "Token refresh failed"))
                    close()
                }
            })
            .build()

        app.acquireTokenSilentAsync(params)
        awaitClose()
    }

    fun signOut(): Flow<Boolean> = callbackFlow {
        publicClientApplication?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                trySend(true)
                close()
            }

            override fun onError(exception: MsalException) {
                trySend(false)
                close()
            }
        })
        awaitClose()
    }

    suspend fun tryRestoreSession(): Boolean {
        // MSAL puede tardar en init (I/O a disco). Damos un máximo para no colgar
        // indefinidamente si la inicialización falla silenciosa (p.ej. tras crash).
        val initialized = withTimeoutOrNull(MSAL_INIT_TIMEOUT_MS) { msalInitialized.await() }

        if (initialized == null) {
            Log.w(TAG, "MSAL no inicializó en ${MSAL_INIT_TIMEOUT_MS}ms (timeout). Asumiendo sin sesión.")
            return false
        }
        if (!initialized) {
            Log.w(TAG, "MSAL falló al inicializarse")
            return false
        }

        val app = publicClientApplication ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val hasAccount = app.currentAccount?.currentAccount != null
                Log.d(TAG, "Sesión restaurada: $hasAccount")
                hasAccount
            } catch (e: Exception) {
                Log.e(TAG, "Error al restaurar sesión", e)
                false
            }
        }
    }

    /**
     * ¿Hay una cuenta de Microsoft conectada? Lo usa el registro de fuentes para decidir si
     * OneDrive está "configurado" (si no, el sync lo salta en vez de fallar por auth).
     */
    suspend fun hasAccount(): Boolean {
        val app = publicClientApplication ?: return false
        return withContext(Dispatchers.IO) {
            try { app.currentAccount?.currentAccount != null } catch (e: Exception) { false }
        }
    }
}

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
    object Cancelled : AuthResult()
}