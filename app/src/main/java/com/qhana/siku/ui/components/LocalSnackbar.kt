package com.qhana.siku.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * `SnackbarHostState` del host ÚNICO de la app (el que MainActivity monta sobre el NavHost y
 * alimenta desde el bus `SnackbarManager`).
 *
 * Se expone como CompositionLocal porque un `Dialog` (p. ej. el contenedor de búsqueda expandido,
 * `ExpandedFullScreenSearchBar`) vive en SU PROPIA ventana y tapa el host del root: el snackbar se
 * mostraría detrás. La solución es montar ahí dentro otro `SnackbarHost` sobre el MISMO estado —
 * ambos dibujan el mismo `currentSnackbarData` y descartarlo en uno lo cierra en los dos.
 *
 * NO crear `SnackbarHostState` por pantalla: todo feedback va por `SnackbarManager`.
 */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHostState sin proveer: solo disponible dentro de MusicPlayerScreen")
}
