package com.qhana.siku.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.Formatter
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appOutlinedButtonBorder
import com.qhana.siku.ui.theme.appOutlinedButtonColors
import com.qhana.siku.ui.theme.appSliderColors
import com.qhana.siku.ui.theme.appTextButtonColors

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
    primaryActionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    /** El rojo señala lo que BORRA (quitar una carpeta). Una acción neutra no debe llevarlo. */
    secondaryActionDestructive: Boolean = true
) {
    AppSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SourceCardHeader(
                icon = icon,
                title = title,
                subtitle = statusText ?: description,
                isConfigured = isConfigured
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    LoadingIndicator(color = AppColors.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                } else {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        TextButton(colors = appTextButtonColors(), onClick = onSecondaryAction) {
                            Text(
                                text = secondaryActionLabel,
                                color = if (secondaryActionDestructive) AppColors.error
                                else AppColors.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (isConfigured) {
                        OutlinedButton(
                            colors = appOutlinedButtonColors(),
                            // El `enabled` va también al borde: es el único de los siete que se
                            // deshabilita, y M3 atenúa el trazo en ese estado (lo hace su default,
                            // que aquí se sustituye).
                            border = appOutlinedButtonBorder(primaryActionEnabled),
                            onClick = onPrimaryAction,
                            enabled = primaryActionEnabled,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(primaryActionLabel)
                        }
                    } else {
                        // Sin configurar la acción va en TEXT BUTTON, no en botón relleno: la
                        // tarjeta es UNA opción entre varias (nube, carpetas, dispositivo), así
                        // que ninguna es la recomendada, y en el onboarding el énfasis pleno lo
                        // tiene el botón que avanza de paso.
                        TextButton(
                            colors = appTextButtonColors(),
                            onClick = onPrimaryAction,
                            enabled = primaryActionEnabled,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(primaryActionLabel)
                        }
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
    modifier: Modifier = Modifier,
    /** Carpeta que se escanea; cadena vacía = toda la cuenta. */
    folderPath: String = "",
    onChangeFolder: (() -> Unit)? = null
) {
    val folderLabel = if (folderPath.isBlank()) stringResource(R.string.source_onedrive_folder_root)
    else folderPath
    SourceCard(
        modifier = modifier,
        icon = "cloud",
        title = stringResource(R.string.source_onedrive_title),
        description = stringResource(R.string.source_onedrive_desc),
        // Conectado, lo que importa ya no es "hay cuenta" sino QUÉ se está escaneando: una
        // biblioteca vacía casi siempre es la carpeta equivocada, y así se ve sin buscarla.
        statusText = if (isConnected) {
            stringResource(R.string.source_onedrive_connected) + " · " +
                stringResource(R.string.source_onedrive_folder, folderLabel)
        } else null,
        isConfigured = isConnected,
        isLoading = isLoading,
        primaryActionLabel = stringResource(
            if (isConnected) R.string.source_onedrive_disconnect else R.string.source_onedrive_connect
        ),
        onPrimaryAction = if (isConnected) onDisconnect else onConnect,
        secondaryActionLabel = if (isConnected && onChangeFolder != null) {
            stringResource(R.string.source_onedrive_change_folder)
        } else null,
        onSecondaryAction = if (isConnected) onChangeFolder else null,
        // Cambiar de carpeta no es "quitar": el rojo de la acción secundaria está reservado
        // para lo que borra (ver LocalFoldersSourceCard).
        secondaryActionDestructive = false
    )
}

/**
 * Devuelve la acción "activar el escaneo del dispositivo", ya resuelto el permiso de lectura de
 * audio: si falta, lo pide; si el usuario lo deniega —o el sistema dejó de preguntar tras dos
 * negativas—, emite el diálogo que lleva a los ajustes de la app, en vez de dejar la opción
 * muerta sin explicación.
 *
 * Existe como gate reutilizable porque hay DOS caminos hacia el mismo modo (la tarjeta del
 * dispositivo y el diálogo de quitar la última carpeta), y uno de ellos se saltaba el permiso.
 *
 * Este es el ÚNICO sitio de la app que pide el permiso de audio: es el único modo que lo necesita
 * (las carpetas SAF se autorizan al elegirlas y la nube no lo usa para nada), así que quien no lo
 * active nunca ve el diálogo del sistema.
 */
@Composable
fun rememberDeviceScanActivator(
    permission: String,
    hasPermission: () -> Boolean,
    onActivate: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    var showPermissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onActivate() else showPermissionDenied = true
    }

    if (showPermissionDenied) {
        AlertDialog(
            onDismissRequest = { showPermissionDenied = false },
            title = { Text(stringResource(R.string.source_device_permission_title)) },
            text = { Text(stringResource(R.string.source_device_permission_body)) },
            confirmButton = {
                TextButton(colors = appTextButtonColors(), onClick = {
                    showPermissionDenied = false
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }) { Text(stringResource(R.string.source_device_permission_settings)) }
            },
            dismissButton = {
                TextButton(colors = appTextButtonColors(), onClick = { showPermissionDenied = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    return { if (hasPermission()) onActivate() else permissionLauncher.launch(permission) }
}

/**
 * Tarjeta del escaneo COMPLETO del dispositivo: toda la música que el sistema indexa, sin elegir
 * carpetas. El permiso lo resuelve [rememberDeviceScanActivator] antes de llegar a [onEnable].
 */
@Composable
fun DeviceScanSourceCard(
    isEnabled: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
    canDisable: Boolean = true
) {
    SourceCard(
        modifier = modifier,
        icon = "mobile",
        title = stringResource(R.string.source_device_title),
        description = stringResource(R.string.source_device_desc),
        statusText = if (isEnabled) stringResource(R.string.source_device_active) else null,
        isConfigured = isEnabled,
        // Sin carpetas (ni guardadas) ni nube a las que volver, apagar el escaneo dejaría la
        // biblioteca vacía y expulsaría al onboarding: se bloquea el botón mientras es el caso.
        primaryActionEnabled = !isEnabled || canDisable,
        primaryActionLabel = stringResource(
            if (isEnabled) R.string.source_device_disable else R.string.source_device_enable
        ),
        onPrimaryAction = { if (isEnabled) onDisable() else onEnable() }
    )
}

/**
 * Tarjeta de las carpetas de música. A diferencia del resto de fuentes admite VARIAS, así que
 * lleva su propia lista en vez de la acción única de [SourceCard]: cada carpeta se puede quitar
 * por separado, que es lo que permite dejar de escanear "Descargas" conservando "Music".
 *
 * Quitar una carpeta NO borra archivos — solo salen del índice de la app — pero eso no es obvio
 * desde el botón, así que se confirma diciéndolo.
 */
@Composable
fun LocalFoldersSourceCard(
    folderUris: Set<String>,
    onFolderPicked: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onScanWholeDevice: () -> Unit,
    modifier: Modifier = Modifier,
    hasOtherSource: Boolean = false
) {
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf<String?>(null) }

    val folderPicker: ManagedActivityResultLauncher<Uri?, Uri?> = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeMusicFolderPermission(context, uri)
            onFolderPicked(uri.toString())
        }
    }

    // Carpetas concedidas solo con lectura: las elegidas con la 1.0.1, donde no se pueden guardar
    // letras. Se avisa aquí, que es donde el usuario puede arreglarlo, en vez de dejar que lo
    // descubra al fallar un guardado. `permissionRefresh` entra en la clave porque re-autorizar la
    // MISMA carpeta no cambia el Set: sin él, el aviso seguiría en pantalla ya resuelto.
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val readOnlyFolders = remember(folderUris, permissionRefresh) {
        readOnlyFoldersOf(context, folderUris)
    }

    // Re-autorizar: el selector se abre POSICIONADO en la carpeta afectada, y al volver se retiene
    // con escritura. Un permiso persistido no se puede ampliar de otro modo.
    val reauthorizePicker: ManagedActivityResultLauncher<Uri?, Uri?> = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeMusicFolderPermission(context, uri)
            permissionRefresh++
            // Si eligió otra carpeta distinta, se trata como añadir: `onFolderPicked` deduplica
            // contra las que ya están (es un Set), así que re-elegir la misma no la duplica.
            onFolderPicked(uri.toString())
        }
    }

    AppSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SourceCardHeader(
                icon = "folder",
                title = stringResource(R.string.source_local_title),
                subtitle = if (folderUris.isEmpty()) stringResource(R.string.source_local_desc)
                else pluralStringResource(R.plurals.source_local_count, folderUris.size, folderUris.size),
                isConfigured = folderUris.isNotEmpty()
            )

            if (folderUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                // Ordenadas por nombre: el Set del DataStore no garantiza orden, y sin esto las
                // carpetas se reordenarían solas entre recomposiciones.
                folderUris.sortedBy { displayFolderName(it).lowercase() }.forEach { uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialSymbol(
                            icon = "folder_open",
                            size = 20.sp,
                            color = AppColors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = displayFolderName(uri),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { pendingRemoval = uri }) {
                            MaterialSymbol(
                                icon = "close",
                                size = 20.sp,
                                color = AppColors.onSurfaceVariant
                            )
                        }
                    }

                    if (uri in readOnlyFolders) {
                        ReadOnlyFolderNotice(
                            onReauthorize = { reauthorizePicker.launch(Uri.parse(uri)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (folderUris.isEmpty()) {
                    Button(colors = appButtonColors(), onClick = { folderPicker.launch(null) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.settings_local_pick))
                    }
                } else {
                    OutlinedButton(colors = appOutlinedButtonColors(), border = appOutlinedButtonBorder(), onClick = { folderPicker.launch(null) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.settings_local_add))
                    }
                }
            }
        }
    }

    val removing = pendingRemoval
    if (removing != null) {
        val isLast = folderUris.size == 1
        // Es la última carpeta Y no hay otra fuente (nube): quitarla dejaría al usuario sin música
        // y lo expulsaría al onboarding. En ese caso NO se ofrece "Quitar": solo cambiar a escanear
        // todo el dispositivo (conserva una fuente) o cancelar (conserva la carpeta). Con nube
        // conectada, o si no es la última, quitarla es seguro y sí se ofrece.
        val lockedLast = isLast && !hasOtherSource
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.local_folder_remove_title, displayFolderName(removing))) },
            text = {
                Text(
                    stringResource(
                        when {
                            lockedLast -> R.string.local_folder_remove_last_locked_body
                            isLast -> R.string.local_folder_remove_last_body
                            else -> R.string.local_folder_remove_body
                        }
                    )
                )
            },
            confirmButton = {
                Row {
                    // Con la última carpeta se ofrece cambiar a escanear todo ahí mismo, en vez de
                    // obligar a buscarlo después (y es la ÚNICA salida cuando no hay otra fuente).
                    if (isLast) {
                        TextButton(colors = appTextButtonColors(), onClick = {
                            pendingRemoval = null
                            onScanWholeDevice()
                        }) { Text(stringResource(R.string.local_folder_remove_scan_all)) }
                    }
                    if (!lockedLast) {
                        TextButton(colors = appTextButtonColors(), onClick = {
                            pendingRemoval = null
                            onRemoveFolder(removing)
                        }) {
                            Text(
                                stringResource(R.string.settings_local_remove),
                                color = AppColors.error
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(colors = appTextButtonColors(), onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * Tarjeta ÚNICA de "música del teléfono" para el ONBOARDING: reúne en una sola tarjeta las dos
 * formas de la música local —escanear todo el dispositivo o elegir carpetas— que en Ajustes van en
 * tarjetas separadas ([DeviceScanSourceCard] + [LocalFoldersSourceCard]). En el primer arranque son
 * demasiada superficie para lo primero que se ve, y presentar "todo el dispositivo" y "unas
 * carpetas" como dos opciones de la MISMA decisión es más claro que como dos fuentes distintas.
 *
 * Los modos son EXCLUYENTES y lo resuelve la capa de datos: activar el escaneo completo vacía las
 * carpetas, y elegir una carpeta desactiva el escaneo completo. Por eso aquí basta con mostrar el
 * estado activo (chip del dispositivo o lista de carpetas) y ofrecer ambas acciones siempre.
 *
 * A diferencia de [LocalFoldersSourceCard] (Ajustes), quitar una carpeta es DIRECTO, sin diálogo:
 * en el onboarding nada se ha escaneado todavía y la verificación de "estás quitando la última
 * carpeta" (con su oferta de escanear todo) solo tiene sentido sobre una biblioteca ya poblada.
 *
 * @param onScanWholeDevice acción ya resuelto el permiso (ver [rememberDeviceScanActivator]).
 */
@Composable
fun PhoneMusicSourceCard(
    scanWholeDevice: Boolean,
    folderUris: Set<String>,
    onScanWholeDevice: () -> Unit,
    onDisableScan: () -> Unit,
    onFolderPicked: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val folderPicker: ManagedActivityResultLauncher<Uri?, Uri?> = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeMusicFolderPermission(context, uri)
            onFolderPicked(uri.toString())
        }
    }

    val isConfigured = scanWholeDevice || folderUris.isNotEmpty()
    val subtitle = when {
        scanWholeDevice -> stringResource(R.string.source_device_active)
        folderUris.isNotEmpty() ->
            pluralStringResource(R.plurals.source_local_count, folderUris.size, folderUris.size)
        else -> stringResource(R.string.source_phone_music_desc)
    }

    AppSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SourceCardHeader(
                // "mobile" y no "library_music": lo que la tarjeta ofrece es el DISPOSITIVO como
                // fuente, y la nota musical la repiten el hero del paso y la tarjeta de la nube.
                icon = "mobile",
                title = stringResource(R.string.source_phone_music_title),
                subtitle = subtitle,
                isConfigured = isConfigured
            )

            // Estado activo: o el chip de "todo el dispositivo" (con su quitar), o la lista de
            // carpetas. Nunca ambos: son modos excluyentes.
            if (scanWholeDevice) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MaterialSymbol(
                        icon = "mobile",
                        size = 20.sp,
                        color = AppColors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.source_device_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDisableScan) {
                        MaterialSymbol(
                            icon = "close",
                            size = 20.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }
                }
            } else if (folderUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                // Ordenadas por nombre: el Set del DataStore no garantiza orden.
                folderUris.sortedBy { displayFolderName(it).lowercase() }.forEach { uri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MaterialSymbol(
                            icon = "folder_open",
                            size = 20.sp,
                            color = AppColors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = displayFolderName(uri),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // Sin diálogo: en el onboarding quitar es reversible y de bajo riesgo.
                        IconButton(onClick = { onRemoveFolder(uri) }) {
                            MaterialSymbol(
                                icon = "close",
                                size = 20.sp,
                                color = AppColors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Con el escaneo completo activo NO se ofrece nada más: "escanear todo" y "elegir
            // carpetas" son modos excluyentes, así que dejar el botón "Añadir carpeta" sería
            // contradictorio. La única salida es el aspa del chip de arriba (desactivar). Con el
            // dispositivo apagado sí conviven las dos acciones de la misma decisión.
            if (!scanWholeDevice) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Las DOS en text button: son las dos mitades de la MISMA decisión ("de dónde
                    // sale la música del teléfono"), así que ninguna es la recomendada, y en el
                    // onboarding el énfasis lo tiene el botón de avance del paso.
                    TextButton(colors = appTextButtonColors(), onClick = onScanWholeDevice) {
                        Text(stringResource(R.string.source_device_enable))
                    }
                    TextButton(colors = appTextButtonColors(), onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.settings_local_add))
                    }
                }
            }
        }
    }
}

/** Cabecera compartida por [SourceCard] y la tarjeta de carpetas (que necesita su propio cuerpo). */
@Composable
private fun SourceCardHeader(
    icon: String,
    title: String,
    subtitle: String,
    isConfigured: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Sin configurar va TONAL (`secondaryContainer`), no neutro: sobre una tarjeta que ya es
        // `surfaceContainerHigh`, el `surfaceContainerHighest` de antes quedaba a UN peldaño de su
        // fondo —invisible con un tema dinámico, cuya escala neutra apenas tiene croma—, así que el
        // icono no se leía como una pieza. El acento pleno se reserva para el estado configurado,
        // que además rellena el glifo y añade el check.
        AppSurface(
            shape = RoundedCornerShape(14.dp),
            color = if (isConfigured) AppColors.primaryContainer
            else AppColors.secondaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                MaterialSymbol(
                    icon = icon,
                    size = 26.sp,
                    fill = isConfigured,
                    color = if (isConfigured) AppColors.onPrimaryContainer
                    else AppColors.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
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
                color = AppColors.primary
            )
        }
    }
}

/**
 * Nombre legible de un tree URI de SAF: se queda con lo que hay tras el último `%3A` (el ":"
 * codificado que separa el id del volumen de la ruta), p. ej. `.../tree/primary%3AMusic` → "Music".
 */
private fun displayFolderName(treeUri: String): String =
    Uri.decode(treeUri.substringAfterLast("%3A", treeUri))

/**
 * Tope de almacenamiento para el audio descargado de la nube (0 = sin límite). Igual que
 * [SourceCard], lo comparten el onboarding y Ajustes → Descargas: la decisión se ofrece al
 * conectar la nube (antes del primer sync masivo, que es cuando se llena el teléfono) y se
 * puede cambiar después sin que las dos pantallas diverjan.
 *
 * El máximo del slider NO es una constante: se deriva del tamaño real del volumen donde la app
 * guarda el audio. Ofrecer un tope mayor que el disco sería ruido (equivale a "sin límite", que
 * ya es el 0), y una constante fija se queda corta o larga según el dispositivo.
 *
 * @param limitGb valor persistido; siembra el slider y se muestra mientras no se arrastre.
 * @param onLimitChangeFinished se llama al SOLTAR, no en cada frame del arrastre: fijar el tope
 *        dispara el desalojo LRU del excedente, que no debe correr en cada píxel.
 * @param icon con un glifo, la tarjeta lleva la cabecera con icono de las demás del onboarding
 *        (donde una pantalla ES una tarjeta y todas tienen que leerse igual). En Ajustes va sin
 *        él: allí la tarjeta vive en una lista de ajustes y el icono sería un adorno.
 */
@Composable
fun StorageLimitCard(
    limitGb: Float,
    onLimitChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = stringResource(R.string.download_storage_limit_desc),
    shape: Shape = RoundedCornerShape(20.dp),
    icon: String? = null
) {
    val context = LocalContext.current
    // Volumen donde viven los archivos de la app: total para el techo del slider, libre para
    // el aviso. `remember` sin claves: el tamaño del disco no cambia mientras la app vive.
    val (maxGb, freeSpaceText) = remember {
        val filesDir = context.filesDir
        (filesDir.totalSpace / AppConfig.BYTES_PER_GB) to
            Formatter.formatShortFileSize(context, filesDir.usableSpace)
    }
    var sliderValue by remember(limitGb, maxGb) { mutableFloatStateOf(limitGb.coerceIn(0f, maxGb)) }
    val gb = sliderValue.toInt()

    AppSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (icon != null) {
                // Cabecera con icono, como las demás tarjetas del onboarding. NO se reutiliza
                // [SourceCardHeader]: ahí el subtítulo es una línea de estado y va a `maxLines = 2`
                // con elipsis, y esta descripción es el párrafo que explica qué pasa al llegar al
                // tope (que se borran las canciones menos escuchadas), o sea justo lo que no se
                // puede truncar. Por eso va debajo y entera, como en la tarjeta del permiso.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppSurface(
                        shape = RoundedCornerShape(14.dp),
                        color = AppColors.secondaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            MaterialSymbol(
                                icon = icon,
                                size = 26.sp,
                                color = AppColors.onSecondaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = stringResource(R.string.download_storage_limit_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.download_storage_limit_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Número grande SIEMPRE visible: es el valor persistido, así al volver a entrar se
            // ve el tope actual sin tener que arrastrar (el tooltip del thumb solo sale al
            // arrastrar, por eso NO alcanza por sí solo).
            Text(
                text = if (gb <= 0) stringResource(R.string.download_storage_limit_unlimited)
                else stringResource(R.string.download_storage_limit_value, gb.toString()),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = if (enabled) AppColors.primary
                else AppColors.primary.copy(alpha = DISABLED_CONTENT_ALPHA)
            )
            // Slider Expressive (thumb de barra fina). El track ondulado NO existe en el Slider
            // de esta versión de material3 (solo en WavyProgressIndicator).
            Slider(
                colors = appSliderColors(),
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 0f..maxGb,
                onValueChangeFinished = { onLimitChangeFinished(gb.toFloat()) },
                enabled = enabled
            )
            // Referencia para decidir: un tope mayor que el espacio libre no cabe hoy.
            Text(
                text = stringResource(R.string.download_storage_free_space, freeSpaceText),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant
            )
        }
    }
}

/** Confirmación previa a desconectar OneDrive: enumera qué se borra y qué se conserva. */
@Composable
fun DisconnectOneDriveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_disconnect_title)) },
        text = { Text(stringResource(R.string.source_disconnect_message)) },
        confirmButton = {
            TextButton(colors = appTextButtonColors(), onClick = onConfirm) {
                Text(
                    stringResource(R.string.source_disconnect_confirm),
                    color = AppColors.error
                )
            }
        },
        dismissButton = {
            TextButton(colors = appTextButtonColors(), onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * Retiene una carpeta de música concedida por el selector, con lectura Y ESCRITURA.
 *
 * La escritura no es un extra: el `.lrc` de una canción de esa carpeta se crea JUNTO a ella
 * (`LyricsWriter`, rama `SafDocument`) y el tag incrustado reescribe el propio archivo. Hasta la
 * 1.0.1 solo se persistía la lectura — bastaba, porque no existía el guardado de letras—, así que
 * las carpetas heredadas de esa versión se quedan sin escritura: el selector concede ambos
 * permisos durante su sesión, pero solo sobrevive al reinicio lo que se persiste aquí. Ampliarlo
 * después es imposible sin volver a pasar por el selector, y de eso se encarga la re-autorización
 * que ofrece la tarjeta de carpetas.
 */
internal fun takeMusicFolderPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.onFailure {
        // Un proveedor que no ofrezca escritura no debe impedir usar la carpeta para ESCUCHAR:
        // se reintenta con lo mínimo imprescindible y el guardado de letras avisará si falta.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}

/**
 * Aviso bajo una carpeta que solo se concedió para lectura, con la acción que lo arregla.
 *
 * Es informativo, no un error: la carpeta se escucha perfectamente; lo único que no se puede es
 * escribir letras en ella. Por eso va en tono secundario y no en `error`.
 */
@Composable
private fun ReadOnlyFolderNotice(onReauthorize: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.source_local_folder_read_only),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(colors = appTextButtonColors(), onClick = onReauthorize) {
            Text(stringResource(R.string.source_local_folder_reauthorize))
        }
    }
}

/**
 * De [folderUris], las que NO tienen permiso de escritura persistido.
 *
 * Se compara por autoridad + id del árbol, no por igualdad de cadenas: el URI que devuelve el
 * sistema en `persistedUriPermissions` no tiene por qué coincidir carácter a carácter con el que
 * se guardó en preferencias. Una carpeta que ya no figure entre los permisos persistidos (el
 * usuario los revocó desde los ajustes del sistema) también sale como solo-lectura, que es
 * exactamente lo que le conviene saber: hay que volver a autorizarla.
 */
private fun readOnlyFoldersOf(context: Context, folderUris: Set<String>): Set<String> {
    if (folderUris.isEmpty()) return emptySet()
    val writable = context.contentResolver.persistedUriPermissions
        .filter { it.isWritePermission }
        .mapNotNull { permission ->
            runCatching {
                permission.uri.authority to DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull()
        }
        .toSet()
    return folderUris.filterNot { uriString ->
        val uri = Uri.parse(uriString)
        val key = runCatching { uri.authority to DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        key != null && key in writable
    }.toSet()
}
