package com.qhana.siku.data.auth

import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
// El wildcard `client.*` de arriba NO cubre el subpaquete `.exception`: va explícito.
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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

    /**
     * Escritura sobre TODO el drive del usuario. Es el único scope que permite tocar archivos
     * fuera de la carpeta de la app: Microsoft no ofrece uno acotado a una carpeta concreta, así
     * que guardar la letra junto a la canción cuesta este permiso o no se puede hacer.
     *
     * Deliberadamente FUERA de [scopes]: si el login lo pidiera, cualquiera que conecte su cuenta
     * consentiría escritura total aunque nunca vaya a guardar una letra. Se pide aparte, la
     * primera vez que el usuario activa esa función ([requestWriteConsent]).
     */
    private val writeScopes = readScopes + "Files.ReadWrite"

    /** El login pide todo de una: un usuario nuevo consiente los tres scopes en una pantalla. */
    private val scopes = backupScopes.toTypedArray()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val msalInitialized = CompletableDeferred<Boolean>()

    /**
     * ¿Hay sesión de Microsoft? OBSERVABLE, y el dueño del hecho es esta clase.
     *
     * MSAL solo ofrece una consulta puntual y suspend ([hasAccount]), así que hasta ahora cada
     * consumidor se guardaba su propia copia y la refrescaba a mano: `AuthViewModel._isLoggedIn`,
     * `SourcesViewModel._hasCloudSource` (con un `refreshCloudPresence()` que había que acordarse
     * de llamar desde la pantalla) y, como esa segunda copia no era reactiva, `OnboardingScreen`
     * terminó puenteándola con `val hasCloudSource = isLoggedIn`. Tres verdades para un solo
     * hecho, que es justo lo que la app tiene prohibido desde el bug del banner de sync.
     *
     * Arranca en `false` —antes de resolver la sesión no hay ninguna— y solo lo escriben los tres
     * puntos donde la sesión CAMBIA de verdad (restaurar, entrar, salir) más la propia consulta
     * autoritativa, que lo refresca de paso para que no pueda divergir de la realidad.
     */
    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

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


    }

    fun signIn(activity: android.app.Activity): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            Log.w(TAG, "MSAL no inicializado al intentar login")
            trySend(AuthResult.Error(AuthErrorReason.SERVICE_UNAVAILABLE))
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
                    _hasSession.value = true
                    trySend(AuthResult.Success(authenticationResult.accessToken))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Error en login", exception)
                    trySend(AuthResult.Error(AuthErrorReason.SIGN_IN_FAILED))
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

    /**
     * Token para escribir en la carpeta de música del usuario (guardar letras junto a la canción).
     * Devuelve error mientras `Files.ReadWrite` no esté consentido — el llamador debe entonces
     * pasar por [requestWriteConsent], que es interactivo y necesita una Activity.
     */
    fun getWriteAccessToken(): Flow<AuthResult> = acquireTokenSilent(writeScopes)

    /**
     * Pide el consentimiento de escritura sobre el drive. Es un **consentimiento incremental**
     * sobre la cuenta ya conectada: MSAL muestra la pantalla de Microsoft con el permiso nuevo y,
     * al aceptarlo, queda en su caché para los refrescos silenciosos posteriores.
     *
     * Solo debe llamarse desde una acción explícita del usuario, nunca en un arranque o un sync.
     */
    fun requestWriteConsent(activity: android.app.Activity): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            trySend(AuthResult.Error(AuthErrorReason.SERVICE_UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(writeScopes)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Consentimiento de escritura concedido")
                    trySend(AuthResult.Success(authenticationResult.accessToken))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Consentimiento de escritura rechazado o fallido", exception)
                    trySend(AuthResult.Error(AuthErrorReason.CONSENT_FAILED))
                    close()
                }

                override fun onCancel() {
                    trySend(AuthResult.Cancelled)
                    close()
                }
            })
            .build()

        app.acquireToken(params)
        awaitClose()
    }

    /**
     * Vuelve a autenticar la cuenta que YA está conectada, con la pantalla de Microsoft.
     *
     * Es la salida de [AuthErrorReason.INTERACTION_REQUIRED]: la sesión sigue existiendo —la cuenta,
     * sus scopes consentidos y toda la biblioteca sincronizada— y lo único muerto es el refresh
     * token, así que **no hay que desconectar nada**. Un `signOut` aquí sería destructivo de verdad:
     * el logout de la app borra las canciones de esa fuente (convención 8), o sea que cambiar la
     * contraseña acabaría costando la biblioteca de nube entera.
     *
     * **Va por `signInAgain` y NO por `signIn`**, que es lo que usa el onboarding: en modo
     * single-account, `signIn` comprueba si ya hay cuenta y, si la hay, falla en el acto con
     * `INVALID_PARAMETER "An account is already signed in."` — o sea que reusarlo aquí habría dado
     * otro error, esta vez sin arreglo posible. `signInAgain` es justo su gemelo para este caso:
     * pide el token interactivo PARA la cuenta persistida.
     *
     * Pide [scopes] (los tres, como el login) para no volver del re-login con menos permisos de los
     * que la sesión tenía.
     */
    fun reauthenticate(activity: android.app.Activity): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            trySend(AuthResult.Error(AuthErrorReason.SERVICE_UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val params = SignInParameters.builder()
            .withActivity(activity)
            .withScopes(scopes.toList())
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    Log.d(TAG, "Reautenticación correcta")
                    // La cuenta ya estaba, pero el hecho se reafirma: es el mismo punto por el que
                    // pasa `signIn`, y dejarlo sin tocar haría depender el estado de quién entró.
                    _hasSession.value = true
                    trySend(AuthResult.Success(authenticationResult.accessToken))
                    close()
                }

                override fun onError(exception: MsalException) {
                    Log.e(TAG, "Reautenticación fallida", exception)
                    trySend(AuthResult.Error(AuthErrorReason.SIGN_IN_FAILED))
                    close()
                }

                override fun onCancel() {
                    trySend(AuthResult.Cancelled)
                    close()
                }
            })
            .build()

        app.signInAgain(params)
        awaitClose()
    }

    private fun acquireTokenSilent(requestedScopes: List<String>): Flow<AuthResult> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            trySend(AuthResult.Error(AuthErrorReason.SERVICE_UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val account = withContext(Dispatchers.IO) {
            app.currentAccount?.currentAccount
        }
        
        if (account == null) {
            trySend(AuthResult.Error(AuthErrorReason.NO_ACCOUNT))
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
                    // **El TIPO de la excepción es el dato, no su mensaje.** `MsalUiRequiredException`
                    // significa exactamente "esto no se arregla en silencio, hace falta la pantalla
                    // de Microsoft": el refresh token está revocado o caducado (el caso típico es un
                    // cambio de contraseña de la cuenta). Colapsarlo con el resto de fallos dejaba a
                    // la UI ofreciendo un reintento que no podía funcionar nunca.
                    val reason = if (exception is MsalUiRequiredException) {
                        AuthErrorReason.INTERACTION_REQUIRED
                    } else {
                        AuthErrorReason.TOKEN_REFRESH_FAILED
                    }
                    Log.w(TAG, "Refresco silencioso falló ($reason): ${exception.errorCode}", exception)
                    trySend(AuthResult.Error(reason))
                    close()
                }
            })
            .build()

        app.acquireTokenSilentAsync(params)
        awaitClose()
    }

    fun signOut(): Flow<Boolean> = callbackFlow {
        val app = publicClientApplication
        if (app == null) {
            // Hay que RESPONDER aunque no haya nada que cerrar: con el `?.` de antes no se
            // ejecutaba ningún callback y el flow se quedaba en `awaitClose()` sin emitir jamás,
            // así que el colector del logout esperaba indefinidamente. Mismo trato que
            // `signIn`/`acquireTokenSilent`, que sí señalan el fallo.
            Log.w(TAG, "signOut con MSAL sin inicializar")
            trySend(false)
            close()
            return@callbackFlow
        }
        app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                _hasSession.value = false
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

    /**
     * Resuelve si hay una cuenta conectada, esperando a que MSAL termine de inicializarse.
     *
     * **No lleva timeout, y es deliberado.** Lo llevó (10 s) y el problema no era el valor sino lo
     * que hacía al agotarse: devolvía `false`, o sea que un plazo de espera acababa DICTANDO un
     * hecho. Con la sesión perfectamente válida en disco, un arranque lento —almacenamiento al
     * límite, el sistema ocupado tras un reinicio— mandaba al usuario al onboarding y ahí se
     * quedaba: nadie volvía a preguntar en toda la ejecución, porque este método solo se llama una
     * vez.
     *
     * Ahora la espera la acota quien MUESTRA algo (`AuthViewModel` avisa de que está tardando y la
     * UI lo dice), que es donde esa decisión pertenece: cuánto se espera es una cuestión de
     * interfaz, mientras que "¿hay sesión?" es un hecho que MSAL acaba respondiendo igual. La
     * respuesta llega cuando llega y siempre es la de verdad.
     */
    suspend fun tryRestoreSession(): Boolean {
        val initialized = msalInitialized.await()
        if (!initialized) {
            Log.w(TAG, "MSAL falló al inicializarse")
            return false
        }

        val app = publicClientApplication ?: return false

        val restored = withContext(Dispatchers.IO) {
            try {
                val hasAccount = app.currentAccount?.currentAccount != null
                Log.d(TAG, "Sesión restaurada: $hasAccount")
                hasAccount
            } catch (e: Exception) {
                Log.e(TAG, "Error al restaurar sesión", e)
                false
            }
        }
        // Siembra [hasSession] con el primer valor REAL del proceso: hasta aquí valía `false` por
        // defecto, que es lo correcto (aún no se sabía), pero quien observe necesita el de verdad.
        _hasSession.value = restored
        return restored
    }

    /**
     * ¿Hay una cuenta de Microsoft conectada? Lo usa el registro de fuentes para decidir si
     * OneDrive está "configurado" (si no, el sync lo salta en vez de fallar por auth).
     *
     * Es la consulta AUTORITATIVA: pregunta a MSAL en el momento, y refresca [hasSession] **solo
     * si obtiene una respuesta CONCLUYENTE**.
     *
     * Esa distinción es load-bearing y no una sutileza: "no hay cuenta" y "no he podido
     * preguntar" (MSAL todavía inicializando, o lanzando) son cosas distintas, y esta función se
     * llama a menudo desde `activeSources()` — incluso desde un `ScanWorker` que arrancó el
     * proceso en frío, antes de que MSAL esté listo. Degradar [hasSession] a `false` en ese caso
     * propagaba la mentira hasta `isLoggedIn` → `hasAnySource` → y `MusicPlayerScreen` **expulsaba
     * al onboarding a un usuario de solo-nube con su sesión perfectamente válida**.
     *
     * Devolver `false` cuando no se puede preguntar sí es correcto para el sync (se salta la
     * fuente en esa pasada y la reintenta en la siguiente), que es el comportamiento de siempre.
     * Lo que no puede hacer es reescribir el estado global de sesión.
     */
    suspend fun hasAccount(): Boolean {
        val app = publicClientApplication ?: return false
        val present: Boolean? = withContext(Dispatchers.IO) {
            try { app.currentAccount?.currentAccount != null } catch (e: Exception) { null }
        }
        if (present != null) _hasSession.value = present
        return present == true
    }

    /**
     * Nombre a mostrar de la cuenta conectada (para el avatar del header: su inicial, y como
     * fallback si Graph no devuelve foto). Prefiere el claim `name` (nombre completo) y cae al
     * `username` (normalmente el email). `null` si no hay cuenta o MSAL aún no está listo.
     */
    suspend fun getAccountName(): String? {
        val app = publicClientApplication ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val account = app.currentAccount?.currentAccount ?: return@withContext null
                (account.claims?.get("name") as? String)?.takeIf { it.isNotBlank() }
                    ?: account.username?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Motivo TIPADO de un fallo de autenticación. La capa de datos no fabrica texto de UI: los mensajes
 * literales de MSAL (`exception.message`) y los literales sueltos que había aquí ("Login failed",
 * "MSAL not initialized"…) llegaban a la pantalla de onboarding SIN traducir, iguales en los dos
 * idiomas. La UI lo mapea a un string localizado (`AuthViewModel.authErrorMessage`); el detalle
 * técnico de MSAL sigue yendo al `Log`.
 */
enum class AuthErrorReason {
    /** MSAL aún no está inicializado (o falló al inicializarse). */
    SERVICE_UNAVAILABLE,
    /** El login interactivo falló. */
    SIGN_IN_FAILED,
    /** El consentimiento incremental (permiso de escritura) falló. */
    CONSENT_FAILED,
    /** No hay ninguna cuenta conectada para un refresco silencioso. */
    NO_ACCOUNT,
    /**
     * El refresco silencioso falló y **NINGÚN reintento silencioso puede arreglarlo**: hace falta
     * que el usuario pase por la pantalla de Microsoft. Es lo que MSAL señala con
     * `MsalUiRequiredException` — típicamente `invalid_grant`, o sea el refresh token revocado
     * porque el usuario **cambió la contraseña**, o `no_tokens_found`.
     *
     * Se distingue de [TOKEN_REFRESH_FAILED] porque la acción que le toca al usuario es OTRA:
     * volver a entrar, no reintentar. Mientras los dos casos fueron el mismo motivo, el banner de
     * la biblioteca ofrecía "toca para reintentar" y el reintento repetía el silencioso que acababa
     * de fallar — un bucle del que no se salía sin desconectar la cuenta (device, 16 sep 2026).
     */
    INTERACTION_REQUIRED,
    /**
     * El refresco silencioso falló por una causa que SÍ puede pasar sola: red, servicio caído, un
     * error transitorio de MSAL. Aquí reintentar es lo correcto.
     */
    TOKEN_REFRESH_FAILED
}

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Error(val reason: AuthErrorReason) : AuthResult()
    object Cancelled : AuthResult()
}