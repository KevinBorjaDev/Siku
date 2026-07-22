package com.qhana.siku.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R

/**
 * Tarjeta de una fuente de música. La comparten el onboarding de primer arranque y la sección
 * "Fuentes" de Ajustes, para que ambas pantallas no puedan divergir.
 *
 * Contenedor tonal sólido M3 (sin translucidez): el icono va en un `primaryContainer` cuando la
 * fuente está configurada, y en un `surfaceContainerHighest` neutro cuando no lo está.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SourceCard(
    icon: String,
    title: String,
    description: String,
    isConfigured: Boolean,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    isLoading: Boolean = false,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isConfigured) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MaterialSymbol(
                            icon = icon,
                            size = 26.sp,
                            fill = isConfigured,
                            color = if (isConfigured) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = statusText ?: description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isConfigured) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MaterialSymbol(
                        icon = "check_circle",
                        size = 22.sp,
                        fill = true,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) {
                            Text(secondaryActionLabel, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (isConfigured) {
                        OutlinedButton(onClick = onPrimaryAction) { Text(primaryActionLabel) }
                    } else {
                        Button(onClick = onPrimaryAction) { Text(primaryActionLabel) }
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de OneDrive. La sesión es propiedad de `AuthViewModel` (única instancia, en la
 * Activity), así que el estado y las acciones llegan por parámetro en vez de por `hiltViewModel()`:
 * una segunda instancia del ViewModel no vería el logout de la primera.
 */
@Composable
fun OneDriveSourceCard(
    isConnected: Boolean,
    isLoading: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    SourceCard(
        modifier = modifier,
        icon = "cloud",
        title = stringResource(R.string.source_onedrive_title),
        description = stringResource(R.string.source_onedrive_desc),
        statusText = if (isConnected) stringResource(R.string.source_onedrive_connected) else null,
        isConfigured = isConnected,
        isLoading = isLoading,
        primaryActionLabel = stringResource(
            if (isConnected) R.string.source_onedrive_disconnect else R.string.source_onedrive_connect
        ),
        onPrimaryAction = if (isConnected) onDisconnect else onConnect
    )
}

/**
 * Tarjeta de la carpeta local. Lanza el picker SAF y persiste el permiso de lectura, para que
 * la carpeta siga siendo legible tras reiniciar la app.
 */
@Composable
fun LocalSourceCard(
    folderUri: String?,
    onFolderPicked: (String) -> Unit,
    onRemoveFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val folderPicker: ManagedActivityResultLauncher<Uri?, Uri?> = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onFolderPicked(uri.toString())
        }
    }

    SourceCard(
        modifier = modifier,
        icon = "folder",
        title = stringResource(R.string.source_local_title),
        description = stringResource(R.string.source_local_desc),
        statusText = folderUri?.let { displayFolderName(it) },
        isConfigured = folderUri != null,
        primaryActionLabel = stringResource(
            if (folderUri == null) R.string.settings_local_pick else R.string.settings_local_change
        ),
        onPrimaryAction = { folderPicker.launch(null) },
        secondaryActionLabel = if (folderUri != null) stringResource(R.string.settings_local_remove) else null,
        onSecondaryAction = if (folderUri != null) onRemoveFolder else null
    )
}

/**
 * Nombre legible de un tree URI de SAF: se queda con lo que hay tras el último `%3A` (el ":"
 * codificado que separa el id del volumen de la ruta), p. ej. `.../tree/primary%3AMusic` → "Music".
 */
private fun displayFolderName(treeUri: String): String =
    Uri.decode(treeUri.substringAfterLast("%3A", treeUri))

/** Confirmación previa a desconectar OneDrive: enumera qué se borra y qué se conserva. */
@Composable
fun DisconnectOneDriveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_disconnect_title)) },
        text = { Text(stringResource(R.string.source_disconnect_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.source_disconnect_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
