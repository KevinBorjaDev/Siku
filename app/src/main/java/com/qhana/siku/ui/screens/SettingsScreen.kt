package com.qhana.siku.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlayerToolbarAction
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.DisconnectOneDriveDialog
import com.qhana.siku.ui.components.LocalSourceCard
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.OneDriveSourceCard
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.viewmodel.BackupViewModel
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel

/**
 * Ajustes estilo Ajustes de Android: HUB de categorías (icono + título + estado, en lista
 * segmentada) donde cada una navega a su propia pantalla — Fuentes, Copia de seguridad,
 * Reproducción, Apariencia y el laboratorio de color como "Avanzado".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    isLoggedIn: Boolean,
    onNavigate: (String) -> Unit,
    sourcesViewModel: SourcesViewModel = hiltViewModel()
) {
    val localFolderUri by sourcesViewModel.localFolderUri.collectAsStateWithLifecycle()

    // Subtítulo dinámico de Fuentes, como el resumen de estado de Ajustes de Android.
    val sourcesSubtitle = when {
        isLoggedIn && localFolderUri != null ->
            stringResource(R.string.settings_source_onedrive) + " · " + stringResource(R.string.settings_source_local)
        isLoggedIn -> stringResource(R.string.settings_source_onedrive)
        localFolderUri != null -> stringResource(R.string.settings_source_local)
        else -> stringResource(R.string.settings_cat_sources_empty)
    }

    // Cada categoría lleva una forma orgánica de MaterialShapes (sello M3 Expressive) y su
    // propio color FIJO (no el scheme seedeado del álbum, que en Ajustes queda gris oscuro),
    // con icono blanco encima — así el hub es colorido y no una lista de blobs grises iguales.
    val onBlob = Color.White
    val categories = listOfNotNull(
        SettingsCategory(
            icon = "cloud",
            title = stringResource(R.string.sources_header),
            subtitle = sourcesSubtitle,
            route = Screen.SettingsSources.route,
            iconShape = MaterialShapes.Cookie9Sided.toShape(),
            container = Color(0xFF1976D2), // azul
            content = onBlob
        ),
        // La copia de seguridad vive en el approot de OneDrive: sin sesión, exportar/importar
        // solo puede fallar en runtime — mismo criterio de gating que "Descargas" más abajo.
        if (isLoggedIn) SettingsCategory(
            icon = "cloud_upload",
            title = stringResource(R.string.backup_header),
            subtitle = stringResource(R.string.settings_cat_backup_desc),
            route = Screen.SettingsBackup.route,
            iconShape = MaterialShapes.Sunny.toShape(),
            container = Color(0xFF2E7D32), // verde
            content = onBlob
        ) else null,
        SettingsCategory(
            icon = "volume_up",
            title = stringResource(R.string.settings_volume_header),
            subtitle = stringResource(R.string.settings_cat_playback_desc),
            route = Screen.SettingsPlayback.route,
            iconShape = MaterialShapes.Cookie7Sided.toShape(),
            container = Color(0xFFE65100), // naranja
            content = onBlob
        ),
        // Descargas: siempre visible — además del tope de GB (que solo aplica con nube y
        // dentro se muestra deshabilitado con su aviso) contiene la política de red de las
        // fotos de artistas, que aplica también a bibliotecas puramente locales.
        SettingsCategory(
            icon = "download",
            title = stringResource(R.string.settings_downloads_header),
            subtitle = stringResource(R.string.settings_cat_downloads_desc),
            route = Screen.SettingsDownloads.route,
            iconShape = MaterialShapes.Cookie12Sided.toShape(),
            container = Color(0xFF6A1B9A), // violeta
            content = onBlob
        ),
        SettingsCategory(
            icon = "palette",
            title = stringResource(R.string.settings_appearance_header),
            subtitle = stringResource(R.string.settings_cat_appearance_desc),
            route = Screen.SettingsAppearance.route,
            iconShape = MaterialShapes.Cookie6Sided.toShape(),
            container = Color(0xFFC2185B), // rosa
            content = onBlob
        )
    )

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBackClick = onBackClick
    ) {
        categories.forEachIndexed { index, category ->
            Surface(
                shape = rememberListItemShape(index, categories.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { onNavigate(category.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = category.iconShape,
                        color = category.container,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            MaterialSymbol(
                                category.icon,
                                size = 22.sp,
                                fill = true,
                                color = category.content
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = category.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MaterialSymbol(
                        "chevron_right",
                        size = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index < categories.lastIndex) Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

private data class SettingsCategory(
    val icon: String,
    val title: String,
    val subtitle: String,
    val route: String,
    val iconShape: Shape,
    val container: Color,
    val content: Color
)

// ==================== Sub-pantallas ====================

/** Fuentes de música: OneDrive + carpeta local, añadir/quitar/re-escanear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSourcesScreen(
    onBackClick: () -> Unit,
    isLoggedIn: Boolean,
    authLoading: Boolean,
    onConnectOneDrive: (android.app.Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    sourcesViewModel: SourcesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val localFolderUri by sourcesViewModel.localFolderUri.collectAsStateWithLifecycle()

    // Desconectar borra las canciones de OneDrive y sus descargas: confirmar antes.
    var showDisconnectDialog by remember { mutableStateOf(false) }
    if (showDisconnectDialog) {
        DisconnectOneDriveDialog(
            onConfirm = {
                showDisconnectDialog = false
                onDisconnectOneDrive()
            },
            onDismiss = { showDisconnectDialog = false }
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.sources_header),
        onBackClick = onBackClick
    ) {
        OneDriveSourceCard(
            isConnected = isLoggedIn,
            isLoading = authLoading,
            onConnect = { activity?.let(onConnectOneDrive) },
            onDisconnect = { showDisconnectDialog = true }
        )

        Spacer(modifier = Modifier.height(12.dp))

        LocalSourceCard(
            folderUri = localFolderUri,
            onFolderPicked = sourcesViewModel::setLocalFolder,
            onRemoveFolder = sourcesViewModel::clearLocalFolder
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Re-escaneo manual de todas las fuentes. Sin OneDrive conectado no necesita red.
        SettingsActionRow(
            icon = "sync",
            title = stringResource(R.string.sources_rescan),
            description = stringResource(R.string.sources_rescan_desc),
            enabled = isLoggedIn || localFolderUri != null,
            onClick = { sourcesViewModel.rescanSources(requiresNetwork = isLoggedIn) }
        )
    }
}

/** Copia de seguridad de playlists en OneDrive (approot). Requiere sesión. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsBackupScreen(
    onBackClick: () -> Unit,
    isLoggedIn: Boolean,
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val backupBusy by backupViewModel.isBusy.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.backup_header),
        onBackClick = onBackClick
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (backupBusy) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Button(
                            onClick = { backupViewModel.exportPlaylists() },
                            enabled = isLoggedIn
                        ) {
                            MaterialSymbol("cloud_upload", size = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_export))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { backupViewModel.importPlaylists() },
                            enabled = isLoggedIn
                        ) {
                            Text(stringResource(R.string.backup_import))
                        }
                    }
                }
            }
        }
    }
}

/** Reproducción: normalización de volumen ReplayGain + pre-amplificación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlaybackScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val replayGainMode = uiState.replayGainMode
    val replayGainPreamp = uiState.replayGainPreamp

    SettingsScaffold(
        title = stringResource(R.string.settings_volume_header),
        onBackClick = onBackClick
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_volume_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Selector de modo OFF / TRACK / ALBUM
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        ReplayGainMode.OFF to stringResource(R.string.settings_rg_off),
                        ReplayGainMode.TRACK to stringResource(R.string.settings_rg_track),
                        ReplayGainMode.ALBUM to stringResource(R.string.settings_rg_album)
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = replayGainMode == mode,
                            onClick = { viewModel.setReplayGainMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                val modeHint = when (replayGainMode) {
                    ReplayGainMode.OFF -> stringResource(R.string.settings_rg_off_desc)
                    ReplayGainMode.TRACK -> stringResource(R.string.settings_rg_track_desc)
                    ReplayGainMode.ALBUM -> stringResource(R.string.settings_rg_album_desc)
                }
                Text(
                    text = modeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // Pre-amp (solo relevante si el modo no es OFF)
                if (replayGainMode != ReplayGainMode.OFF) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    TunableSlider(
                        title = stringResource(R.string.settings_preamp_title, String.format("%+.1f", replayGainPreamp)),
                        description = stringResource(R.string.settings_preamp_desc),
                        value = replayGainPreamp,
                        range = -6f..6f,
                        steps = 23, // pasos de 0.5 dB en [-6, 6]
                        onValueChange = { viewModel.setReplayGainPreamp(it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ecualizador: elegir entre el propio (hoja del NowPlaying) y el del sistema.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_eq_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_use_system_eq),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_use_system_eq_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = uiState.useSystemEq,
                        onCheckedChange = { viewModel.setUseSystemEq(it) }
                    )
                }
            }
        }

    }
}

/** Etiqueta legible de cada acción del toolbar para la lista de personalización. */
private fun PlayerToolbarAction.labelRes(): Int = when (this) {
    PlayerToolbarAction.REPEAT -> R.string.toolbar_action_repeat
    PlayerToolbarAction.LYRICS -> R.string.toolbar_action_lyrics
    PlayerToolbarAction.QUEUE -> R.string.toolbar_action_queue
    PlayerToolbarAction.KEEP_SCREEN_ON -> R.string.toolbar_action_screen
    PlayerToolbarAction.EQUALIZER -> R.string.toolbar_action_equalizer
    PlayerToolbarAction.SLEEP_TIMER -> R.string.toolbar_action_sleep
    PlayerToolbarAction.ADD_TO_PLAYLIST -> R.string.toolbar_action_add_playlist
    PlayerToolbarAction.DOWNLOAD -> R.string.toolbar_action_download
}

private fun PlayerToolbarAction.iconName(): String = when (this) {
    PlayerToolbarAction.REPEAT -> "repeat"
    PlayerToolbarAction.LYRICS -> "lyrics"
    PlayerToolbarAction.QUEUE -> "queue_music"
    PlayerToolbarAction.KEEP_SCREEN_ON -> "visibility"
    PlayerToolbarAction.EQUALIZER -> "graphic_eq"
    PlayerToolbarAction.SLEEP_TIMER -> "bedtime"
    PlayerToolbarAction.ADD_TO_PLAYLIST -> "playlist_add"
    PlayerToolbarAction.DOWNLOAD -> "download"
}

/**
 * Lista reordenable (drag & drop) de las acciones del toolbar. El orden es global; el switch "En
 * la barra" decide si cada acción va a la barra flotante o al overflow. Se hace cumplir el tope
 * [PlayerToolbarConfig.MAX_IN_BAR]: al alcanzarlo, los switches apagados quedan deshabilitados.
 *
 * Estado local para que el arrastre sea fluido (sin round-trip a DataStore por frame); se
 * re-siembra si [config] cambia desde fuera, y cada cambio persiste vía [onConfigChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBarCustomizer(
    config: List<ToolbarActionState>,
    onConfigChange: (List<ToolbarActionState>) -> Unit
) {
    var items by remember { mutableStateOf(config) }
    LaunchedEffect(config) { items = config }

    val barCount = items.count { it.inBar }
    val atMax = barCount >= PlayerToolbarConfig.MAX_IN_BAR

    sh.calvin.reorderable.ReorderableColumn(
        list = items,
        onSettle = { from, to ->
            val newList = items.toMutableList().apply { add(to, removeAt(from)) }
            items = newList
            onConfigChange(newList)
        },
        modifier = Modifier.fillMaxWidth()
    ) { _, item, isDragging ->
        key(item.action) {
            // ReorderableItem expone el ReorderableListItemScope (dueño de draggableHandle). Ese
            // receiver se pierde al entrar al Row (pasa a RowScope): se captura y se re-aplica.
            ReorderableItem {
                val itemScope = this
                Surface(
                    tonalElevation = if (isDragging) 6.dp else 0.dp,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Box(modifier = with(itemScope) { Modifier.draggableHandle() }.padding(8.dp)) {
                            MaterialSymbol("drag_indicator", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    MaterialSymbol(
                        item.action.iconName(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = stringResource(item.action.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = item.inBar,
                        // Deshabilitar SOLO los apagados cuando ya se llegó al tope (los encendidos
                        // siempre se pueden apagar).
                        enabled = item.inBar || !atMax,
                        onCheckedChange = { checked ->
                            val newList = items.map {
                                if (it.action == item.action) it.copy(inBar = checked) else it
                            }
                            items = newList
                            onConfigChange(newList)
                        }
                    )
                    }
                }
            }
        }
    }
    if (atMax) {
        Text(
            text = stringResource(R.string.settings_player_bar_max, PlayerToolbarConfig.MAX_IN_BAR),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * Descargas: tope de almacenamiento (caché LRU) para el audio de la nube + política de red
 * de las fotos de artistas. La pantalla es visible SIEMPRE (las fotos aplican también a
 * biblioteca local); el tope de GB se deshabilita con su aviso cuando no hay proveedor cloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDownloadsScreen(
    onBackClick: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
    sourcesViewModel: SourcesViewModel = hiltViewModel()
) {
    val storageLimitGb by viewModel.storageLimitGb.collectAsStateWithLifecycle()
    // Estado local del slider: se siembra del valor persistido y se guarda al soltar.
    var sliderValue by remember(storageLimitGb) { mutableStateOf(storageLimitGb.coerceIn(0f, 200f)) }
    val gb = sliderValue.toInt()

    // ¿Hay alguna fuente de NUBE? Del registro de fuentes, no de OneDrive en particular:
    // un proveedor cloud futuro cuenta sin tocar este gate.
    val hasCloudSource by sourcesViewModel.hasCloudSource.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { sourcesViewModel.refreshCloudPresence() }

    SettingsScaffold(
        title = stringResource(R.string.settings_downloads_header),
        onBackClick = onBackClick
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.download_storage_limit_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (hasCloudSource) stringResource(R.string.download_storage_limit_desc)
                    else stringResource(R.string.settings_downloads_cloud_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                // Número grande SIEMPRE visible: es el valor persistido, así al volver a entrar
                // se ve el tope actual sin tener que arrastrar (el tooltip del thumb solo sale al
                // arrastrar, por eso NO alcanza por sí solo).
                Text(
                    text = if (gb <= 0) stringResource(R.string.download_storage_limit_unlimited)
                    else stringResource(R.string.download_storage_limit_value, gb.toString()),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hasCloudSource) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                )
                // Slider Expressive (thumb de barra fina). El track ondulado NO existe en el
                // Slider de esta versión de material3 (solo en WavyProgressIndicator).
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..200f,
                    onValueChangeFinished = { viewModel.setStorageLimitGb(gb.toFloat()) },
                    enabled = hasCloudSource
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Fotos de artistas (Deezer): política de red del backfill y del fetch del detalle.
        // El estado reactivo sale de un BrowseViewModel propio de esta pantalla; las acciones
        // van al repo singleton, así que el banner de la pestaña Artistas (otra instancia de
        // VM) reacciona igual.
        val browseViewModel: BrowseViewModel = hiltViewModel()
        val photosOnMetered by browseViewModel.artistPhotosOnMetered.collectAsStateWithLifecycle()
        val photosBannerEnabled by browseViewModel.artistPhotosBannerEnabled.collectAsStateWithLifecycle()
        val photoDetailOnMetered by browseViewModel.artistPhotoDetailOnMetered.collectAsStateWithLifecycle()
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_artist_photos_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_artist_photos_metered),
                    description = stringResource(R.string.settings_artist_photos_metered_desc),
                    checked = photosOnMetered,
                    onCheckedChange = { browseViewModel.setArtistPhotosOnMetered(it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_artist_photos_banner),
                    description = stringResource(R.string.settings_artist_photos_banner_desc),
                    checked = photosBannerEnabled,
                    // Con "descargar con datos" activo nunca hay pausa que avisar.
                    enabled = !photosOnMetered,
                    onCheckedChange = { browseViewModel.setArtistPhotosBannerEnabled(it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_artist_photo_detail),
                    description = stringResource(R.string.settings_artist_photo_detail_desc),
                    checked = photoDetailOnMetered,
                    onCheckedChange = { browseViewModel.setArtistPhotoDetailOnMetered(it) }
                )
            }
        }
    }
}

/** Apariencia: regenerar colores y fondo/barra del reproductor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRegenerating = uiState.isRegeneratingColors
    val nowPlayingSolidBackground = uiState.nowPlayingSolidBackground
    val nowPlayingWavyProgress = uiState.nowPlayingWavyProgress

    SettingsScaffold(
        title = stringResource(R.string.settings_appearance_header),
        onBackClick = onBackClick
    ) {
        SettingsActionRow(
            icon = if (isRegenerating) null else "palette",
            title = if (isRegenerating) stringResource(R.string.settings_regenerating)
            else stringResource(R.string.settings_regenerate_colors),
            description = stringResource(R.string.settings_regenerate_desc),
            enabled = !isRegenerating,
            loading = isRegenerating,
            onClick = { viewModel.regenerateColors() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Fondo del reproductor: color sólido tonal vs degradado según la carátula.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaterialSymbol("gradient", size = 24.sp)

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_solid_bg),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_solid_bg_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Switch(
                    checked = nowPlayingSolidBackground,
                    onCheckedChange = { viewModel.setNowPlayingSolidBackground(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Barra de progreso del reproductor: píldora plana (diseño propio) vs onda Expressive.
        // Solo afecta al NowPlaying — en el MiniPlayer la barra mide 3dp y la onda no se leería.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaterialSymbol("waves", size = 24.sp)

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_wavy_progress),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_wavy_progress_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Switch(
                    checked = nowPlayingWavyProgress,
                    onCheckedChange = { viewModel.setNowPlayingWavyProgress(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Estilo de paleta: cuánto croma se aplica al generar el tema desde el color del álbum.
        // Cambia el tema EN VIVO (no hace falta regenerar colores: el estilo actúa sobre el seed
        // ya guardado, no sobre la extracción).
        ThemePaletteStyleSetting(
            selected = uiState.themePaletteStyle,
            onSelect = { viewModel.setThemePaletteStyle(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Personalización de la barra del reproductor (orden + barra/overflow de cada acción).
        val toolbarConfig by viewModel.toolbarConfig.collectAsStateWithLifecycle()
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_player_bar_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.settings_player_bar_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                PlayerBarCustomizer(
                    config = toolbarConfig,
                    onConfigChange = { viewModel.setToolbarConfig(it) }
                )
            }
        }

        // El "laboratorio de color" (7 sliders de tuning del extractor) se eliminó con el
        // cambio a QuantizerCelebi + Score: ese pipeline no tiene parámetros que ajustar. El
        // visor de candidatos, que sí sigue siendo útil, vive donde se usa: el diálogo de
        // long-press sobre la carátula del NowPlaying (NowPlayingColorPicker).
    }
}

/**
 * Estilos de paleta ofrecidos, en el orden en que se muestran. Son un SUBCONJUNTO de
 * `PaletteStyle`: se listan por nombre de enum (lo que se persiste) junto a su etiqueta y a una
 * descripción de qué le hace al color del álbum.
 *
 * `Monochrome` no está: el tema ya lo aplica solo cuando la carátula no tiene color, y como
 * ajuste global dejaría la app en gris permanente por accidente.
 */
private val PaletteStyleOptions = listOf(
    "TonalSpot" to (R.string.settings_palette_tonalspot to R.string.settings_palette_tonalspot_desc),
    "Vibrant" to (R.string.settings_palette_vibrant to R.string.settings_palette_vibrant_desc),
    "Content" to (R.string.settings_palette_content to R.string.settings_palette_content_desc),
    "Fidelity" to (R.string.settings_palette_fidelity to R.string.settings_palette_fidelity_desc),
    "Neutral" to (R.string.settings_palette_neutral to R.string.settings_palette_neutral_desc),
    "Expressive" to (R.string.settings_palette_expressive to R.string.settings_palette_expressive_desc),
    "Rainbow" to (R.string.settings_palette_rainbow to R.string.settings_palette_rainbow_desc),
    "FruitSalad" to (R.string.settings_palette_fruitsalad to R.string.settings_palette_fruitsalad_desc)
)

/** Selector del estilo de paleta: fila con el valor actual que despliega el menú de opciones. */
@Composable
private fun ThemePaletteStyleSetting(
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Un estilo retirado de la librería (o una preferencia vieja) no debe dejar la fila vacía.
    val current = PaletteStyleOptions.firstOrNull { it.first == selected } ?: PaletteStyleOptions.first()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { expanded = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MaterialSymbol("colorize", size = 24.sp)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_palette_style),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(current.second.first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Box {
                MaterialSymbol(
                    "expand_more",
                    size = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    PaletteStyleOptions.forEach { (name, labels) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(labels.first))
                                    Text(
                                        text = stringResource(labels.second),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingIcon = if (name == current.first) {
                                { MaterialSymbol("check", size = 20.sp) }
                            } else null,
                            onClick = {
                                expanded = false
                                onSelect(name)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ==================== Piezas compartidas ====================

/**
 * Andamiaje común de Ajustes: LargeTopAppBar colapsable + columna scrolleable con los
 * márgenes estándar. Lo usan el hub y todas las sub-pantallas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBackClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            content = content
        )
    }
}

/** Fila de switch estándar dentro de una tarjeta de Ajustes: título + descripción + Switch. */
@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** Fila de acción estándar de Ajustes: icono (o spinner), título y descripción. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsActionRow(
    icon: String?,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        enabled = enabled,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
            } else if (icon != null) {
                MaterialSymbol(icon, size = 24.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TunableSlider(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
