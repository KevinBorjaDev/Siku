package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.source.LocalMusicSource
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.worker.DownloadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gestión de las fuentes de música (Fase 4). Deliberadamente ligero: lo usan tanto el
 * onboarding de primer arranque como la sección "Fuentes" de Ajustes, y el onboarding no
 * debe instanciar `LibraryViewModel` (paging, restore de sesión, colectores de sync).
 *
 * La sesión de OneDrive NO vive aquí: su fuente de verdad es `AuthViewModel`, que MainActivity
 * comparte con toda la app. Las pantallas reciben ese estado por parámetro.
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMusicSource: LocalMusicSource,
    private val sourceRegistry: MusicSourceRegistry,
    private val downloadScheduler: DownloadScheduler,
    private val snackbarManager: SnackbarManager
) : ViewModel() {

    /** Carpeta de música local elegida (tree URI de SAF), o null si no hay ninguna. */
    private val _localFolderUri = MutableStateFlow(localMusicSource.folderUri())
    val localFolderUri: StateFlow<String?> = _localFolderUri.asStateFlow()

    /**
     * ¿Hay alguna fuente de NUBE configurada? Sale del registro ([MusicSourceRegistry]), no de
     * la sesión de OneDrive en particular: un proveedor cloud futuro cuenta solo. Gobierna las
     * features de descarga en Ajustes (tope de GB) — sin nube no hay nada que descargar.
     * `isConfigured()` es suspend y no reactivo, por eso se refresca bajo demanda
     * ([refreshCloudPresence]) desde la pantalla que lo muestra.
     */
    private val _hasCloudSource = MutableStateFlow(false)
    val hasCloudSource: StateFlow<Boolean> = _hasCloudSource.asStateFlow()

    fun refreshCloudPresence() {
        viewModelScope.launch {
            _hasCloudSource.value = sourceRegistry.activeSources().any { it.type.isCloud }
        }
    }

    /**
     * Fija la carpeta local (el permiso de lectura ya lo persistió la UI) y lanza un escaneo.
     * Si la carpeta cambió, [LocalMusicSource.setFolder] borra las canciones LOCAL previas:
     * sus `content://` apuntaban al árbol viejo. Los ids son por ruta relativa, así que si la
     * estructura coincide se recrean iguales y las playlists sobreviven.
     */
    fun setLocalFolder(uri: String) {
        viewModelScope.launch {
            localMusicSource.setFolder(uri)
            _localFolderUri.value = uri
            snackbarManager.show(context.getString(R.string.local_folder_set))
            // Sin constraint de red: el escaneo local debe correr aunque el usuario esté offline.
            downloadScheduler.scheduleScan(force = false, requiresNetwork = false)
        }
    }

    /** Quita la carpeta local y borra sus canciones de la biblioteca. */
    fun clearLocalFolder() {
        viewModelScope.launch {
            localMusicSource.clearFolder()
            _localFolderUri.value = null
            snackbarManager.show(context.getString(R.string.local_folder_removed))
        }
    }

    /**
     * Vuelve a escanear todas las fuentes configuradas desde cero (equivale al pull-to-refresh):
     * limpia el delta token, hace full scan y reconcilia las bajas.
     *
     * @param requiresNetwork false cuando la única fuente es la local — así el re-escaneo corre
     * aunque el usuario esté offline. Con OneDrive conectado hace falta red.
     */
    fun rescanSources(requiresNetwork: Boolean) {
        downloadScheduler.scheduleScan(force = true, requiresNetwork = requiresNetwork)
        snackbarManager.show(context.getString(R.string.sources_rescan_started))
    }
}
