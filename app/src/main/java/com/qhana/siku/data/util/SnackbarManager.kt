package com.qhana.siku.data.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Duración del snackbar, agnóstica de Compose (se mapea a SnackbarDuration en el host). */
enum class SnackbarLength { SHORT, LONG, INDEFINITE }

/**
 * Un mensaje de feedback para mostrar en el SnackbarHost único de la app.
 * [actionLabel]/[onAction] son opcionales (p.ej. "Reintentar").
 *
 * [withDismissAction] añade la "x" de cerrar. [replaceCurrent] descarta el snackbar en pantalla
 * en vez de encolarse detrás: para mensajes de estado que se disparan a repetición (tocar el
 * chip de varias canciones seguidas), donde ver la cola entera no aporta nada.
 */
data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val length: SnackbarLength = SnackbarLength.SHORT,
    val onAction: (() -> Unit)? = null,
    val withDismissAction: Boolean = false,
    val replaceCurrent: Boolean = false
)

/**
 * Bus de feedback CENTRALIZADO de la app (snackbars). Es `@Singleton`, así que cualquier
 * ViewModel —sin importar en qué scope de navegación viva su instancia— emite al MISMO bus,
 * y un único `SnackbarHost` en el root los muestra.
 *
 * Motivación: antes cada `LibraryViewModel` de un destino del NavHost tenía su propio
 * `SharedFlow` de mensajes y `MainActivity` colectaba el de OTRA instancia → los toasts
 * (p.ej. de redescarga) nunca aparecían. Un singleton elimina esa dependencia de identidad.
 */
@Singleton
class SnackbarManager @Inject constructor() {

    // extraBufferCapacity + tryEmit: emisión no suspendida desde ViewModels; si no hay host
    // colectando aún, el evento se bufferiza (o se descarta si el buffer se llena, muy raro).
    private val _events = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SnackbarEvent> = _events.asSharedFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        length: SnackbarLength = SnackbarLength.SHORT,
        onAction: (() -> Unit)? = null,
        withDismissAction: Boolean = false,
        replaceCurrent: Boolean = false
    ) {
        _events.tryEmit(
            SnackbarEvent(message, actionLabel, length, onAction, withDismissAction, replaceCurrent)
        )
    }
}
