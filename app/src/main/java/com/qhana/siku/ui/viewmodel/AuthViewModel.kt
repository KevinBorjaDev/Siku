package com.qhana.siku.ui.viewmodel

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.auth.AuthErrorReason
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import dagger.hilt.android.qualifiers.ApplicationContext
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.worker.WorkerTags
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.work.WorkManager
import java.io.File

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val repository: dagger.Lazy<IMusicRepository>,
    private val artworkRepository: dagger.Lazy<ArtworkRepository>,
    private val musicController: dagger.Lazy<MusicController>,
    private val syncManager: dagger.Lazy<com.qhana.siku.data.coordinator.SyncManager>,
    private val workManager: dagger.Lazy<WorkManager>,
    private val oneDriveRepository: dagger.Lazy<com.qhana.siku.data.repository.OneDriveRepository>,
    private val musicPreferences: MusicPreferences
) : ViewModel() {

    /**
     * ¿Se resolvió ya la sesión al arrancar? Es lo ÚNICO que este ViewModel sabe y `AuthManager`
     * no: la diferencia entre "no hay sesión" y "todavía no lo sé".
     */
    private val _sessionResolved = MutableStateFlow(false)

    /**
     * `null` = sesión aún sin resolver (MSAL inicializando/leyendo la cuenta de disco).
     * MainActivity retiene el splash mientras valga null, para no mostrar el onboarding un
     * instante y navegar a la biblioteca después (flash al reabrir).
     *
     * Se DERIVA de `AuthManager.hasSession`, que es el dueño del hecho. Antes era un
     * `MutableStateFlow` propio escrito en tres sitios (restaurar, entrar, salir), en paralelo a
     * la copia que mantenía `SourcesViewModel`: dos estados que representaban lo mismo y que solo
     * coincidían mientras nadie olvidara actualizar uno de ellos.
     */
    val isLoggedIn: StateFlow<Boolean?> =
        combine(_sessionResolved, authManager.hasSession) { resolved, hasSession ->
            if (resolved) hasSession else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * La restauración de sesión está tardando más de lo normal y todavía no ha terminado.
     *
     * Existe para que la espera se pueda CONTAR en vez de resolverse por decreto. Antes, pasado un
     * plazo, se daba por hecho que no había sesión y el usuario aterrizaba en el onboarding con su
     * cuenta intacta en disco; ahora el plazo solo cambia lo que se muestra —un aviso de que se
     * sigue intentando— y la respuesta llega cuando MSAL la dé, siempre la de verdad.
     *
     * Vuelve a `false` al resolverse, así que la UI que dependa de esto desaparece sola.
     */
    private val _sessionRestoreSlow = MutableStateFlow(false)
    val sessionRestoreSlow: StateFlow<Boolean> = _sessionRestoreSlow.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Avatar de la cuenta para el header de la biblioteca. `accountPhotoPath` = ruta al archivo de
     * la foto de perfil cacheada (o `null` si la cuenta no tiene foto / aún no se bajó); en ese caso
     * el avatar usa `accountInitial` (la inicial del nombre). Sin sesión, ambos son `null` y el
     * header muestra el engranaje de ajustes.
     */
    private val _accountPhotoPath = MutableStateFlow<String?>(null)
    val accountPhotoPath: StateFlow<String?> = _accountPhotoPath.asStateFlow()
    private val _accountInitial = MutableStateFlow<String?>(null)
    val accountInitial: StateFlow<String?> = _accountInitial.asStateFlow()

    /** Foto de perfil cacheada en disco: sobrevive a reinicios, así no se pide a Graph cada arranque. */
    private val profileImageFile: File get() = File(context.filesDir, "account/photo.jpg")

    init {
        checkExistingSession()
        observeAccountProfile()
    }

    /**
     * Sigue a la sesión: al conectar OneDrive carga el perfil (inicial + foto), al desconectar lo
     * limpia. NO borra el archivo cacheado en el `false` de arranque (la sesión aún sin resolver es
     * `false` por defecto) — solo pone los estados en `null`; el borrado del archivo lo hace
     * [logout] de forma explícita.
     */
    private fun observeAccountProfile() {
        viewModelScope.launch {
            authManager.hasSession.collect { hasSession ->
                if (hasSession) {
                    loadAccountProfile()
                } else {
                    _accountPhotoPath.value = null
                    _accountInitial.value = null
                }
            }
        }
    }

    private suspend fun loadAccountProfile() {
        // Inicial primero (barata, sin red): el avatar tiene algo que mostrar al instante.
        val name = authManager.getAccountName()
        _accountInitial.value = name?.trim()?.firstOrNull()?.uppercaseChar()?.toString()
        // Foto: el archivo cacheado si existe; si no, se pide a Graph una vez y se guarda.
        val cached = profileImageFile
        if (cached.exists() && cached.length() > 0) {
            _accountPhotoPath.value = cached.absolutePath
            return
        }
        val bytes = runCatching { oneDriveRepository.get().downloadProfilePhoto() }.getOrNull()
        if (bytes != null) {
            withContext(Dispatchers.IO) {
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
            }
            _accountPhotoPath.value = cached.absolutePath
        } else {
            // Sin foto (cuenta sin imagen o fallo de red): el avatar cae a la inicial.
            _accountPhotoPath.value = null
        }
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            _isLoading.value = true
            // Aviso de lentitud: no interrumpe ni decide nada, solo cambia lo que la UI enseña
            // mientras se sigue esperando (ver [sessionRestoreSlow]).
            val slowNotice = launch {
                delay(SESSION_RESTORE_SLOW_MS)
                _sessionRestoreSlow.value = true
            }
            // Monótono: es la DURACIÓN de la restauración, el dato que ancla SESSION_RESTORE_SLOW_MS.
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // Suspende hasta que MSAL esté listo y siembra `hasSession` con el resultado REAL:
                // ya no hay timeout que pueda contestar por él. Aquí solo hace falta marcar que se
                // resolvió.
                authManager.tryRestoreSession()
                // Cuánto tardó de verdad. Es el dato que le falta a [SESSION_RESTORE_SLOW_MS] para
                // estar anclado por abajo: el umbral debe quedar holgadamente por encima de lo
                // normal, y "lo normal" solo se sabe midiéndolo en dispositivos reales.
                Log.i(TAG, "Sesión resuelta en ${SystemClock.elapsedRealtime() - startedAt}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un fallo al restaurar significa exactamente "no hay sesión" — que es el valor
                // con el que `hasSession` ya arranca—, y es recuperable: el usuario entra desde
                // Ajustes. Lo que NO puede pasar es quedarse sin resolver.
                Log.w(TAG, "No se pudo restaurar la sesión: ${e.message}")
            } finally {
                // En el `finally`: mientras esto no se marque, `isLoggedIn` vale null y el splash
                // del sistema sigue en pantalla (MainActivity.setKeepOnScreenCondition). Si MSAL
                // lanza —cuenta corrupta, config inválida, error del broker— y no se marcara, la
                // app se quedaría colgada en el splash sin pantalla que mostrar ni forma de salir.
                slowNotice.cancel()
                _sessionRestoreSlow.value = false
                _sessionResolved.value = true
                _isLoading.value = false
            }
        }
    }

    fun signIn(activity: android.app.Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            // El error es de ESTE intento: se limpia al empezar uno nuevo. Sin esto, un fallo
            // (red, cancelación de MSAL) dejaba el banner de error para siempre — el reintento
            // podía tener éxito y el usuario seguía viendo el mensaje viejo bajo las tarjetas.
            _error.value = null
            // El `finally` es la única garantía de que el spinner se apaga: si `signIn` LANZA en
            // vez de emitir un `AuthResult.Error` —o si la corrutina se cancela—, apagarlo en
            // cada rama del `when` no sirve de nada y el botón de entrar se queda girando para
            // siempre, sin error visible y sin forma de reintentar.
            try {
                authManager.signIn(activity).collect { result ->
                    // El éxito NO se anota aquí: `AuthManager` ya publica la sesión nueva en
                    // `hasSession`, de donde sale `isLoggedIn`. Este colector solo se ocupa del
                    // error, que sí es de este intento y de nadie más.
                    when (result) {
                        is AuthResult.Error -> _error.value = authErrorMessage(result.reason)
                        is AuthResult.Success, AuthResult.Cancelled -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El detalle técnico va al Log; al usuario, un mensaje localizado y genérico.
                Log.e("AuthViewModel", "Fallo inesperado en signIn", e)
                _error.value = context.getString(R.string.error_auth_generic)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Traduce el motivo tipado de un fallo de autenticación a un texto localizado. Antes llegaban a
     * la pantalla de onboarding literales en inglés de MSAL ("Login failed", "MSAL not initialized")
     * iguales en los dos idiomas; ahora la capa de datos entrega [AuthErrorReason] y aquí se resuelve.
     */
    private fun authErrorMessage(reason: AuthErrorReason): String {
        val res = when (reason) {
            AuthErrorReason.SERVICE_UNAVAILABLE -> R.string.error_auth_unavailable
            AuthErrorReason.SIGN_IN_FAILED -> R.string.error_auth_signin_failed
            AuthErrorReason.CONSENT_FAILED -> R.string.error_auth_consent_failed
            AuthErrorReason.NO_ACCOUNT -> R.string.error_auth_no_account
            AuthErrorReason.TOKEN_REFRESH_FAILED -> R.string.error_auth_token_refresh
        }
        return context.getString(res)
    }

    /**
     * Cierra la sesión de OneDrive. Desconecta SOLO esa fuente: borra sus canciones (y el audio
     * descargado en disco) pero conserva la música local, las playlists y los favoritos — con
     * varias fuentes, un logout ya no puede ser un borrado total de la biblioteca.
     *
     * Las playlists conservan las referencias a las canciones de OneDrive (no hay CASCADE desde
     * `songs`), así que al reconectar la cuenta se rellenan solas: los ids son portables.
     */
    fun logout() {
        viewModelScope.launch {
            authManager.signOut().collect { success ->
                if (success) {
                    // La sesión ya la dio de baja `AuthManager` en su propio callback; aquí solo
                    // queda la limpieza de datos que depende de ella.
                    // Un error de un login anterior no describe el estado actual: sin sesión no
                    // hay nada que hubiera fallado.
                    _error.value = null
                    // Foto de perfil cacheada: el observer ya puso los estados en null (hasSession
                    // pasó a false en el callback de signOut), pero el ARCHIVO hay que borrarlo a
                    // mano — si no, al reconectar OTRA cuenta se mostraría la foto de la anterior.
                    runCatching { if (profileImageFile.exists()) profileImageFile.delete() }
                    // Accedemos a las dependencias Lazy solo aquí, cuando son necesarias.
                    syncManager.get().release() // cancela sync/descargas en curso antes de borrar datos
                    // El delta token sobrevive al borrado (vive en DataStore): sin limpiarlo, al
                    // reconectar la cuenta el primer scan sería incremental sobre una tabla vacía
                    // y la biblioteca quedaría en blanco hasta un pull-to-refresh manual.
                    musicPreferences.clearDeltaToken()
                    repository.get().clearSourceData(SourceType.ONEDRIVE)
                    artworkRepository.get().clearCache()
                    // Purgar de la cola TODOS los temas de OneDrive (no solo el actual): ya no
                    // existen en la BD y dejarlos solo difiere el fallo a cuando les llegue el
                    // turno. La música local sigue sonando; si sonaba OneDrive, la cola queda
                    // en pausa sobre el siguiente tema local (o parada si no queda nada).
                    musicController.get().purgeSource(SourceType.ONEDRIVE)
                    // Cancelar todo el trabajo pendiente con el token que va a morir: scan,
                    // descargas individuales en vuelo y la extracción de color de carátulas.
                    val wm = workManager.get()
                    wm.cancelUniqueWork(WorkerTags.SCAN_WORK_NAME)
                    wm.cancelAllWorkByTag(WorkerTags.DOWNLOAD_TRACKING_TAG)
                    wm.cancelUniqueWork(WorkerTags.ARTWORK_WORK_NAME)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AuthViewModel"

        /**
         * A partir de cuándo se avisa de que restaurar la sesión está tardando.
         *
         * **Es un umbral de PRESENTACIÓN**: no decide si hay sesión ni corta nada, solo elige entre
         * seguir enseñando el splash o poner el aviso con el indicador de carga. El plazo que vivía
         * aquí antes SÍ decidía —daba la sesión por inexistente y mandaba al onboarding— y por eso
         * entonces cualquier valor era arriesgado. Ahora la respuesta llega siempre, tarde o
         * temprano, y esto solo gobierna qué se dibuja mientras tanto.
         *
         * **De dónde sale el valor: de ningún sitio, y no lo hay.** Este umbral no depende de
         * ninguna magnitud estable de la app ni del sistema — no es un timeout de red, ni una
         * conversión, ni algo derivable de otra constante. Lo único con lo que se relaciona es la
         * PERCEPCIÓN de quien mira la pantalla, así que se elige el único punto de referencia que
         * describe lo que el mensaje afirma: los diez segundos en que una espera muda deja de
         * parecer "está cargando" y pasa a parecer "algo va mal". El texto habla de un problema, y a
         * los diez segundos ya lo es.
         *
         * Se elige por el lado que NO miente: adelantarlo daría la alarma en arranques sanos —el
         * caso corriente, donde MSAL resuelve en una fracción de esto— y llegar tarde solo alarga un
         * splash que de todos modos estaba ahí. `checkExistingSession` loguea "Sesión resuelta en
         * Xms" por si alguna vez interesa ver cuánto tarda de verdad; ese dato ajustaría el margen,
         * no el criterio.
         *
         * **Coincide en valor con el `MSAL_INIT_TIMEOUT_MS` que se eliminó, y no son lo mismo.**
         * Aquel cortaba la espera y contestaba `false` por su cuenta; éste no interrumpe nada.
         */
        const val SESSION_RESTORE_SLOW_MS = 10_000L
    }
}