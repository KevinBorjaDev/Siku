package com.qhana.siku.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.worker.WorkerTags
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.work.WorkManager

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val repository: dagger.Lazy<IMusicRepository>,
    private val artworkRepository: dagger.Lazy<ArtworkRepository>,
    private val musicController: dagger.Lazy<MusicController>,
    private val syncManager: dagger.Lazy<com.qhana.siku.data.coordinator.SyncManager>,
    private val workManager: dagger.Lazy<WorkManager>,
    private val musicPreferences: MusicPreferences
) : ViewModel() {

    // null = sesión aún sin resolver (MSAL inicializando/leyendo la cuenta de disco).
    // MainActivity espera a que se resuelva antes de componer el NavHost, para no
    // mostrar el Login un instante y navegar a Library después (flash al reabrir).
    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        checkExistingSession()
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            _isLoading.value = true
            // tryRestoreSession suspende hasta que MSAL esté listo (con timeout propio:
            // AuthManager.MSAL_INIT_TIMEOUT_MS, que es el techo real del splash).
            // El resultado (true/false) resuelve el estado null inicial.
            _isLoggedIn.value = authManager.tryRestoreSession()
            _isLoading.value = false
        }
    }

    fun signIn(activity: android.app.Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            authManager.signIn(activity).collect { result ->
                when (result) {
                    is AuthResult.Success -> {
                        _isLoggedIn.value = true
                        _isLoading.value = false
                    }
                    is AuthResult.Error -> {
                        _error.value = result.message
                        _isLoading.value = false
                    }
                    AuthResult.Cancelled -> {
                        _isLoading.value = false
                    }
                }
            }
        }
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
                    _isLoggedIn.value = false
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

}