package com.qhana.siku.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.hct.Hct
import com.qhana.siku.ui.theme.appSelectableMenuItemColors
import com.qhana.siku.ui.theme.AppMenuGroup
import com.qhana.siku.R
import com.qhana.siku.data.lyrics.safFolderDisplayName
import com.qhana.siku.data.model.LibraryTabId
import com.qhana.siku.data.model.LibraryTabState
import com.qhana.siku.data.model.LibraryTabsConfig
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.NowPlayingBackground
import com.qhana.siku.data.model.PlayerToolbarAction
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.data.source.DeviceAudioFolder
import com.qhana.siku.data.util.CrashReporter
import com.qhana.siku.ui.components.AppMenuPopup
import com.qhana.siku.ui.components.DISABLED_CONTENT_ALPHA
import com.qhana.siku.ui.components.groupedListItemShapes
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ChoiceGroupWidth
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.EqPresets
import com.qhana.siku.ui.components.DisconnectOneDriveDialog
import com.qhana.siku.ui.components.DeviceScanSourceCard
import com.qhana.siku.ui.components.LocalFoldersSourceCard
import com.qhana.siku.ui.components.rememberDeviceScanActivator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Box
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.MenuItemIcon
import com.qhana.siku.ui.components.PlayerGestureConfig
import com.qhana.siku.ui.components.progressTrackHeightMax
import com.qhana.siku.ui.components.OneDriveSourceCard
import com.qhana.siku.ui.components.rememberOneDriveFolderChanger
import com.qhana.siku.ui.components.StorageLimitCard
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appOutlinedButtonBorder
import com.qhana.siku.ui.theme.appOutlinedButtonColors
import com.qhana.siku.ui.theme.appRadioButtonColors
import com.qhana.siku.ui.theme.appSegmentedListItemColors
import com.qhana.siku.ui.theme.appSliderColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.theme.appSwitchColors
import com.qhana.siku.ui.theme.appTextButtonColors
import com.qhana.siku.ui.viewmodel.BackupViewModel
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import kotlin.math.roundToInt
import com.qhana.siku.ui.viewmodel.SyncViewModel

/*
 * NINGUNA pantalla de este archivo declara `= hiltViewModel()` para un ViewModel COMPARTIDO, y eso
 * no es estilo: cada una es un `composable(...)` del NavHost, así que el default resuelve al
 * ViewModelStore del `NavBackStackEntry` y nace una instancia NUEVA por pantalla (convención 9).
 *
 * Con `LibraryViewModel` eso costaba caro y en silencio. Su `init` arranca el `getFavoritesSongs()`
 * —el JOIN sobre `songs` que sus propios comentarios llaman el más caro de re-ejecutar—, el combine
 * de playlists+favoritos, el saneo de filtros y el colector del banner de sync: tres suscripciones
 * a una tabla que Room invalida ENTERA en cada escritura. Eran siete pantallas creando cada una la
 * suya, y con el back stack normal (Ajustes → Apariencia → Barra de progreso) había tres vivas a la
 * vez además de la de la Activity.
 *
 * Ya había mordido tres veces por el lado funcional, y cada vez se parcheó el síntoma en lugar de
 * la causa: los dos colectores del `init` de `LibraryViewModel` que "OBSERVAN en vez de cargar" el
 * formato detallado y el modo de guardado de letras existen porque los conmuta otra instancia, y el
 * `SnackbarManager` singleton nació del mismo choque. Esos tres siguen siendo correctos y se quedan;
 * lo que cambia es que ya no hay una segunda instancia que los haga falta.
 *
 * Quitar el default es la parte load-bearing: convierte el fallo —silencioso, de runtime, visible
 * solo como estado desincronizado— en un error de COMPILACIÓN si alguien añade una pantalla y no
 * pasa el ViewModel. Es la misma técnica que `LibraryScreen` ya aplicaba desde antes (ver el KDoc de
 * su parámetro `libraryViewModel`, que explica el caso original); aquí solo se extiende a Ajustes.
 *
 * `BackupViewModel` SÍ conserva su `hiltViewModel()`: lo usa una sola pantalla y nada más, así que
 * atarlo al ciclo de vida de esa ruta es lo correcto — no hay segunda instancia posible.
 */

/** Modos de ReplayGain, en el orden en que se ofrecen. */
private val REPLAY_GAIN_MODES = listOf(
    ReplayGainMode.OFF,
    ReplayGainMode.TRACK,
    ReplayGainMode.ALBUM
)

/**
 * Medidas compartidas por todas las pantallas de Ajustes. Existen porque el radio de las tarjetas
 * y las dos separaciones estaban repetidos literalmente en ~15 sitios del archivo: cambiar el
 * lenguaje visual obligaba a un buscar-y-reemplazar y cualquier olvido quedaba como una tarjeta
 * con otra forma.
 */
private object SettingsTokens {
    /** Esquina de un bloque SUELTO (los agrupados la reciben de `ListItemDefaults.segmentedShapes`). */
    val BlockCorner = 12.dp
    /**
     * Separación DENTRO de un grupo: la del spec (`ListItemDefaults.SegmentedGap`), para que se
     * lea como un bloque continuo. Se declara aquí en vez de leerse allí porque también separa
     * bloques que NO son list items (el selector de estilo de paleta, la tarjeta de tope de
     * descargas), y el valor tiene que ser el mismo o la columna se lee irregular.
     */
    val GroupGap = 2.dp
    /** Separación ENTRE grupos o bloques independientes. */
    val SectionGap = 8.dp
}

/**
 * Grupo de ajustes con el patrón de LISTA AGRUPADA de M3 Expressive: los ítems forman un bloque
 * continuo — esquinas pronunciadas arriba del primero y abajo del último, pequeñas en los del
 * medio — separados por [SettingsTokens.GroupGap].
 *
 * El reparto de esquinas lo hace `groupedListItemShapes` (la librería, con el caso de UNA fila
 * corregido), y lo que
 * viaja a cada ítem es un `ListItemShapes` ENTERO y no un `Shape` suelto: ahí van también la forma
 * al presionar y la de seleccionado, que son las que dan el morph de las listas Expressive. Con un
 * único `Shape` no había dónde ponerlas y estas filas eran lo último de Ajustes que no acusaba el
 * dedo.
 *
 * Recibe la LISTA de ítems y no un slot `content` porque el reparto necesita saber cuántos hay, y
 * en Compose no se pueden contar los hijos de un slot antes de componerlos.
 */
@Composable
private fun SettingsGroup(items: List<@Composable (ListItemShapes) -> Unit>) {
    items.forEachIndexed { index, item ->
        item(groupedListItemShapes(index = index, count = items.size))
        if (index < items.lastIndex) Spacer(modifier = Modifier.height(SettingsTokens.GroupGap))
    }
}

/**
 * Título + descripción de una fila de Ajustes, EN EL MISMO SLOT `content`.
 *
 * Parece un detalle de composición y no lo es: mientras la descripción viajaba en
 * `supportingContent`, `ListItem` clasificaba la fila como **three-line** en cuanto ese texto
 * ocupaba dos líneas (`ListItemType`, con `isSupportingMultiline`), y en ese tipo el spec coloca
 * leading y trailing en `topPadding` en vez de centrarlos (`ListItem.kt:1181` y `:1209`). El
 * resultado era el chevron pegado a la altura del título, con media fila vacía debajo — y encima
 * INCONSISTENTE, porque las filas cuya descripción sí cabía en una línea (Estilo de color) sí lo
 * centraban. Con los dos textos en `content` la fila es one-line, el alto lo sigue dando el
 * contenido, y M3 centra el icono y el chevron sin que haya que alinear nada a mano.
 */
@Composable
private fun SettingsTileText(title: String, description: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.onSurfaceVariant
        )
    }
}

/**
 * Fila de switch con icono, pensada para ir dentro de un [SettingsGroup] (de ahí que la forma
 * venga de fuera). Sustituye a tres bloques de ~33 líneas idénticos que solo se diferenciaban en
 * el icono, los textos y el estado.
 *
 * No confundir con [SettingsSwitchRow], que es la fila DESNUDA (sin superficie propia ni icono)
 * que se usa dentro de una tarjeta que ya agrupa varios switches bajo un encabezado.
 */
@Composable
private fun SettingsSwitchTile(
    icon: String,
    title: String,
    description: String,
    checked: Boolean,
    shapes: ListItemShapes,
    onCheckedChange: (Boolean) -> Unit,
    /**
     * false = el ajuste sigue ahí pero no responde, porque OTRO ajuste lo gobierna (hoy: el estilo
     * moderno manda sobre dónde van las pestañas). Se inhabilita en vez de ocultarse para que se
     * vea que la configuración sigue guardada y por qué no se aplica.
     *
     * Basta con dárselo al `SegmentedListItem`: los colores del estado deshabilitado ya vienen
     * completos de `appSegmentedListItemColors`, y el `Switch` es display-only —el gesto lo atiende
     * la fila—, así que apagar la fila lo apaga a él.
     */
    enabled: Boolean = true
) {
    // `SegmentedListItem` en su sobrecarga TOGGLEABLE: es el componente real de M3 para una fila de
    // lista que conmuta. Antes esto era un `Surface` + `Row` + `Modifier.toggleable` que reponía a
    // mano la anatomía del `ListItem` (padding, alto, alineación) y la semántica de switch; lo
    // único que no podía reponer era el morph de la forma al presionar, que llega gratis con el
    // componente. El `role = Switch` que se ponía a mano lo pone él, y la fila entera sigue siendo
    // el blanco táctil —que era el motivo de haberlo montado así— porque `onCheckedChange` vive en
    // el ítem, no en el `Switch`.
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = shapes,
        // El token del componente es `surface`; estas listas se apilan SOBRE `surface`, así que la
        // fila tiene que subir un escalón tonal o desaparece contra el fondo. Mismo criterio (y mismo
        // valor) que `GroupedListRow`. Por el helper y no por `segmentedColors` a pelo: los estados
        // SELECCIONADO y ARRASTRADO también tienen que venir de la carátula.
        colors = appSegmentedListItemColors(AppColors.surfaceContainerHigh),
        leadingContent = { MaterialSymbol(icon, size = 24.sp) },
        // Display-only: quien maneja el gesto es la fila. Con su propio `onCheckedChange` el tap se
        // atendería dos veces.
        trailingContent = {
            Switch(
                colors = appSwitchColors(),
                checked = checked,
                enabled = enabled,
                onCheckedChange = null
            )
        },
        content = { SettingsTileText(title, description) },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Resumen de UNA línea de qué fuentes hay configuradas, como el estado bajo cada entrada de
 * Ajustes de Android.
 *
 * Vive en una sola función porque lo consumen DOS sitios con el mismo significado: la fila del
 * hub y el subtítulo de la app bar de la propia pantalla de Fuentes. Escrito dos veces, nada
 * obligaría a que coincidieran y la barra podría contradecir a la fila desde la que se entró.
 */
@Composable
private fun sourcesSummary(isLoggedIn: Boolean, hasLocalSource: Boolean): String = when {
    isLoggedIn && hasLocalSource ->
        stringResource(R.string.settings_source_onedrive) + " · " + stringResource(R.string.settings_source_local)
    isLoggedIn -> stringResource(R.string.settings_source_onedrive)
    hasLocalSource -> stringResource(R.string.settings_source_local)
    else -> stringResource(R.string.settings_cat_sources_empty)
}

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
    sourcesViewModel: SourcesViewModel
) {
    val hasLocalSource by sourcesViewModel.hasLocalSource.collectAsStateWithLifecycle()

    // Un `listFiles` sobre un directorio de como mucho cinco archivos, una vez por entrada a la
    // pantalla: no merece un ViewModel ni un flujo. Y se consulta al COMPONER a propósito — si la
    // app crasheó, esta composición es posterior al crash.
    val context = LocalContext.current
    val hasCrashReport = remember { CrashReporter.hasReport(context) }

    // Subtítulo dinámico de Fuentes, como el resumen de estado de Ajustes de Android.
    val sourcesSubtitle = sourcesSummary(isLoggedIn = isLoggedIn, hasLocalSource = hasLocalSource)

    // Cada categoría lleva una forma orgánica de MaterialShapes (sello M3 Expressive) y su
    // propio color de acento FIJO (no el scheme seedeado del álbum, que en Ajustes queda gris
    // oscuro), del que se DERIVA el par tonal del badge (contenedor tenue + glifo saturado, estilo
    // Ajustes de Android) en [rememberBadgeColors] — así el hub es colorido y no una lista de
    // blobs grises iguales.
    val categories = listOfNotNull(
        SettingsCategory(
            icon = "cloud",
            title = stringResource(R.string.sources_header),
            subtitle = sourcesSubtitle,
            onClick = { onNavigate(Screen.SettingsSources.route) },
            iconShape = MaterialShapes.Cookie9Sided.toShape(),
            seed = Color(0xFF1976D2) // azul
        ),
        // SIN gate de sesion, al contrario que "Descargas": desde el 29 ago 2026 esta pantalla
        // aloja tambien importar/exportar listas en M3U, que es una operacion LOCAL. Gatearla por
        // la sesion de OneDrive la dejaria inalcanzable justo para el usuario solo-local -- el
        // mismo hueco que ya dejo a Estadisticas sin puerta. Lo que se oculta sin sesion es el
        // bloque de OneDrive de dentro, no el destino.
        SettingsCategory(
            icon = "cloud_upload",
            title = stringResource(R.string.backup_header),
            subtitle = stringResource(R.string.settings_cat_backup_desc),
            onClick = { onNavigate(Screen.SettingsBackup.route) },
            iconShape = MaterialShapes.Sunny.toShape(),
            seed = Color(0xFF2E7D32) // verde
        ),
        SettingsCategory(
            icon = "brand_awareness",
            title = stringResource(R.string.settings_volume_header),
            subtitle = stringResource(R.string.settings_cat_playback_desc),
            onClick = { onNavigate(Screen.SettingsPlayback.route) },
            iconShape = MaterialShapes.Cookie7Sided.toShape(),
            seed = Color(0xFFE65100) // naranja
        ),
        // Descargas: siempre visible — además del tope de GB (que solo aplica con nube y
        // dentro se muestra deshabilitado con su aviso) contiene la política de red de las
        // fotos de artistas, que aplica también a bibliotecas puramente locales.
        SettingsCategory(
            icon = "download",
            title = stringResource(R.string.settings_downloads_header),
            subtitle = stringResource(R.string.settings_cat_downloads_desc),
            onClick = { onNavigate(Screen.SettingsDownloads.route) },
            iconShape = MaterialShapes.Cookie12Sided.toShape(),
            seed = Color(0xFF6A1B9A) // violeta
        ),
        SettingsCategory(
            icon = "palette",
            title = stringResource(R.string.settings_appearance_header),
            subtitle = stringResource(R.string.settings_cat_appearance_desc),
            onClick = { onNavigate(Screen.SettingsAppearance.route) },
            iconShape = MaterialShapes.Cookie6Sided.toShape(),
            seed = Color(0xFFC2185B) // rosa
        ),
        SettingsCategory(
            icon = "swipe",
            title = stringResource(R.string.settings_gestures_header),
            subtitle = stringResource(R.string.settings_cat_gestures_desc),
            onClick = { onNavigate(Screen.SettingsGestures.route) },
            iconShape = MaterialShapes.Cookie4Sided.toShape(),
            seed = Color(0xFF00838F) // turquesa
        ),
        // Informe de fallo: SOLO si hay uno guardado, y por eso va al final. Es la segunda puerta
        // del informe —la primera es la pantalla que el propio fallo levanta—, y existe para el
        // crash que sí dejó volver a entrar: ahí la pantalla ya se cerró y sin esto el informe
        // quedaría en disco sin forma de mandarlo. Permanente sería una categoría que casi siempre
        // dice "no pasó nada".
        if (hasCrashReport) {
            SettingsCategory(
                icon = "bug_report",
                title = stringResource(R.string.settings_crash_header),
                subtitle = stringResource(R.string.settings_crash_desc),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(context, CrashReportActivity::class.java))
                    }
                },
                iconShape = MaterialShapes.Cookie4Sided.toShape(),
                seed = Color(0xFFB71C1C) // rojo
            )
        } else null
    )

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBackClick = onBackClick
    ) {
        categories.forEachIndexed { index, category ->
            // `SegmentedListItem` como el resto de Ajustes: el hub era la lista que ESTRENÓ el
            // patrón agrupado en esta pantalla y la última en montarlo a mano. El badge de forma
            // orgánica va en el slot leading, que es donde le corresponde.
            SegmentedListItem(
                onClick = category.onClick,
                shapes = groupedListItemShapes(index = index, count = categories.size),
                colors = appSegmentedListItemColors(AppColors.surfaceContainerHigh),
                leadingContent = {
                    val (badgeContainer, badgeContent) = rememberBadgeColors(category.seed)
                    AppSurface(
                        shape = category.iconShape,
                        color = badgeContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MaterialSymbol(
                                category.icon,
                                size = 22.sp,
                                fill = true,
                                color = badgeContent
                            )
                        }
                    }
                },
                supportingContent = {
                    Text(
                        text = category.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                },
                trailingContent = {
                    MaterialSymbol(
                        "chevron_right",
                        size = 24.sp,
                        color = AppColors.onSurfaceVariant
                    )
                },
                content = {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.bodyLargeEmphasized
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (index < categories.lastIndex) {
                Spacer(modifier = Modifier.height(SettingsTokens.GroupGap))
            }
        }
    }
}

private data class SettingsCategory(
    val icon: String,
    val title: String,
    val subtitle: String,
    /**
     * Qué hace la fila. Era una RUTA, y dejó de serlo al entrar el informe de fallo: esa entrada no
     * navega dentro del grafo, abre [CrashReportActivity]. Con una acción el hub sirve para las dos
     * sin un segundo mecanismo en paralelo.
     */
    val onClick: () -> Unit,
    val iconShape: Shape,
    /** Color de acento de la categoría; el par del badge se deriva vía [rememberBadgeColors]. */
    val seed: Color
)

/**
 * Par (contenedor, glifo) del badge de una categoría, al estilo de Ajustes de Android: contenedor
 * TONAL tenue del color con el glifo en el color SATURADO —en vez de un círculo saturado con el
 * icono blanco—. Se deriva del [seed] por tono HCT, igual que Material genera sus pares
 * `x-container`/`on-x-container`, así que funciona en tema claro y oscuro sin colores a mano.
 */
@Composable
private fun rememberBadgeColors(seed: Color): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return remember(seed, dark) {
        val hct = Hct.fromInt(seed.toArgb())
        // Contenedor tenue / glifo brillante en oscuro; contenedor pastel / glifo saturado en claro.
        val container = Hct.from(hct.hue, hct.chroma, if (dark) 30.0 else 90.0).toInt()
        val content = Hct.from(hct.hue, hct.chroma, if (dark) 90.0 else 40.0).toInt()
        Color(container) to Color(content)
    }
}

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
    onNavigate: (String) -> Unit,
    sourcesViewModel: SourcesViewModel
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val localFolderUris by sourcesViewModel.localFolderUris.collectAsStateWithLifecycle()
    val scanWholeDevice by sourcesViewModel.scanWholeDevice.collectAsStateWithLifecycle()
    val hasLocalSource by sourcesViewModel.hasLocalSource.collectAsStateWithLifecycle()
    // ¿Hay carpetas (o guardadas al activar el dispositivo) a las que volver? Con nube conectada
    // (isLoggedIn) tampoco hay riesgo de quedarse sin biblioteca. De aquí sale si se puede apagar el
    // escaneo del dispositivo o quitar la última carpeta sin caer en el onboarding.
    val hasLocalFallback by sourcesViewModel.hasLocalFallback.collectAsStateWithLifecycle()

    val activateDeviceScan = rememberDeviceScanActivator(
        permission = sourcesViewModel.audioPermission,
        hasPermission = sourcesViewModel::hasAudioPermission,
        onActivate = { sourcesViewModel.setScanWholeDevice(true) }
    )

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
        // El MISMO resumen que enseña la fila del hub desde la que se llega aquí (ver
        // [sourcesSummary]): la línea de estado ya no se pierde al entrar.
        subtitle = sourcesSummary(isLoggedIn = isLoggedIn, hasLocalSource = hasLocalSource),
        onBackClick = onBackClick
    ) {
        // Local primero (la app es local-first) y la nube después, mismo orden que el onboarding.
        val canDisableDeviceScan = isLoggedIn || hasLocalFallback
        DeviceScanSourceCard(
            isEnabled = scanWholeDevice,
            onEnable = activateDeviceScan,
            onDisable = { sourcesViewModel.setScanWholeDevice(false) },
            // Sin carpetas a las que volver ni nube, apagarlo dejaría la biblioteca vacía.
            canDisable = canDisableDeviceScan
        )

        // Solo con el escaneo del dispositivo activo: en modo carpetas la inclusión ya es
        // explícita, así que una lista de exclusiones al lado no significaría nada.
        if (scanWholeDevice) {
            Spacer(modifier = Modifier.height(12.dp))
            val excludedCount by sourcesViewModel.excludedDeviceFolders.collectAsStateWithLifecycle()
            SettingsActionRow(
                icon = "folder_off",
                title = stringResource(R.string.settings_excluded_folders),
                description = if (excludedCount.isEmpty()) {
                    stringResource(R.string.settings_excluded_folders_desc)
                } else {
                    pluralStringResource(
                        R.plurals.settings_excluded_folders_count,
                        excludedCount.size,
                        excludedCount.size
                    )
                },
                enabled = true,
                navigates = true,
                onClick = { onNavigate(Screen.SettingsExcludedFolders.route) }
            )
        }

        // Explica por qué el botón "Desactivar" está en gris: no dejar al usuario sin biblioteca.
        if (scanWholeDevice && !canDisableDeviceScan) {
            Text(
                text = stringResource(R.string.source_device_disable_locked),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LocalFoldersSourceCard(
            folderUris = localFolderUris,
            onFolderPicked = { sourcesViewModel.addLocalFolder(it) },
            onRemoveFolder = sourcesViewModel::removeLocalFolder,
            onScanWholeDevice = activateDeviceScan,
            // Con nube conectada, quitar la última carpeta es seguro (queda OneDrive).
            hasOtherSource = isLoggedIn
        )

        Spacer(modifier = Modifier.height(12.dp))

        val oneDriveFolder by sourcesViewModel.oneDriveFolderPath.collectAsStateWithLifecycle()
        // En Ajustes SÍ se advierte: aquí ya hay biblioteca, y cambiar de carpeta se lleva por
        // delante las canciones que queden fuera.
        val changeOneDriveFolder = rememberOneDriveFolderChanger(warnBeforeChange = true) { path ->
            sourcesViewModel.setOneDriveFolder(path)
        }

        OneDriveSourceCard(
            isConnected = isLoggedIn,
            isLoading = authLoading,
            onConnect = { activity?.let(onConnectOneDrive) },
            onDisconnect = { showDisconnectDialog = true },
            folderPath = oneDriveFolder,
            onChangeFolder = changeOneDriveFolder
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Re-escaneo manual de todas las fuentes. Sin OneDrive conectado no necesita red.
        SettingsActionRow(
            icon = "sync",
            title = stringResource(R.string.sources_rescan),
            description = stringResource(R.string.sources_rescan_desc),
            enabled = isLoggedIn || hasLocalSource,
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
    // COMPARTIDO y por eso SIN default (convencion 9): de el salen las listas que ofrece el
    // exportador y las operaciones de M3U, que escriben en la misma biblioteca que ve la pestana
    // Listas. Un `= hiltViewModel()` aqui crearia una instancia propia del scope de esta ruta.
    libraryViewModel: LibraryViewModel,
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val backupBusy by backupViewModel.isBusy.collectAsStateWithLifecycle()
    val playlists by libraryViewModel.playlists.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsScaffold(
        title = stringResource(R.string.backup_header),
        onBackClick = onBackClick
    ) {
        // La copia en OneDrive solo se PINTA con sesion: sin ella sus dos botones no pueden hacer
        // nada, y un bloque entero deshabilitado es ruido. La pantalla, en cambio, ya no depende de
        // la sesion -- el bloque de M3U de abajo es puramente local y es justo el usuario
        // solo-local quien mas lo necesita.
        if (isLoggedIn) {
            AppSurface(
                shape = RoundedCornerShape(SettingsTokens.BlockCorner),
                color = AppColors.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.backup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    // Los dos botones REPARTEN el ancho por `weight`, y sus etiquetas son el VERBO a
                    // secas ("Exportar" / "Restaurar") en vez de repetir "playlists" en los dos.
                    // Antes el `Row` no repartía nada: el primero tomaba su ancho intrínseco (~186dp
                    // con icono y etiqueta larga) y al segundo le quedaban 134 — 48 de padding y 86
                    // para un texto que pedía ~120, así que "Restaurar playlists" caía a dos líneas y
                    // los dos botones quedaban de distinto alto. QUÉ se exporta ya lo dice la
                    // descripción de encima ("Guarda tus playlists y favoritos…"), que es su sitio.
                    val backupButtonHeight = ButtonDefaults.MinHeight
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (backupBusy) {
                            LoadingIndicator(color = AppColors.primary, modifier = Modifier.size(24.dp))
                        } else {
                            Button(
                                colors = appButtonColors(),
                                onClick = { backupViewModel.exportPlaylists() },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f)
                            ) {
                                MaterialSymbol(
                                    "cloud_upload",
                                    size = ButtonDefaults.iconSizeFor(backupButtonHeight).value.sp
                                )
                                Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(backupButtonHeight)))
                                Text(
                                    text = stringResource(R.string.backup_export),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                colors = appOutlinedButtonColors(),
                                border = appOutlinedButtonBorder(),
                                onClick = { backupViewModel.importPlaylists() },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.backup_import),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))
        }

        M3uPlaylistsBlock(
            playlists = playlists,
            onImport = { uri ->
                libraryViewModel.importPlaylistFromFile(
                    uri,
                    context.getString(R.string.playlist_import_default_name)
                )
            },
            onExportPlaylist = { id, uri -> libraryViewModel.exportPlaylistToFile(id, uri) },
            onExportFavorites = { uri -> libraryViewModel.exportFavoritesToFile(uri) }
        )
    }
}

/** Reproducción: normalización de volumen ReplayGain + pre-amplificación. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlaybackScreen(
    onBackClick: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: LibraryViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val replayGainMode = uiState.replayGainMode
    val replayGainPreamp = uiState.replayGainPreamp

    SettingsScaffold(
        title = stringResource(R.string.settings_volume_header),
        onBackClick = onBackClick
    ) {
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Encabezado propio del bloque: antes lo hacía el título de la pantalla, que ahora es
                // "Audio" (más amplio que solo ReplayGain), así que la normalización necesita el suyo
                // —igual que el bloque del ecualizador tiene el suyo (settings_eq_header)—.
                Text(
                    text = stringResource(R.string.settings_replaygain_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.settings_volume_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Selector de modo OFF / TRACK / ALBUM. Connected button group, no segmentado:
                // el spec de M3 Expressive retiró `SegmentedButton` de las recomendaciones y este
                // es su reemplazo directo.
                //
                // `ChoiceGroupWidth.Content` porque las tres etiquetas son de longitud MUY desigual:
                // "Desactivado" contra "Por pista" y "Por álbum". Repartiendo el ancho a partes
                // iguales, la primera se quedaba sin sitio y salía cortada ("Desactivad"). Ceñidos
                // al contenido los tres ocupan casi el mismo ancho total que antes —el grupo llena
                // la tarjeta igual— y ninguno recorta.
                ConnectedChoiceGroup(
                    options = REPLAY_GAIN_MODES,
                    selected = replayGainMode,
                    onSelect = { viewModel.setReplayGainMode(it) },
                    width = ChoiceGroupWidth.Content,
                    labelFor = {
                        when (it) {
                            ReplayGainMode.OFF -> stringResource(R.string.settings_rg_off)
                            ReplayGainMode.TRACK -> stringResource(R.string.settings_rg_track)
                            ReplayGainMode.ALBUM -> stringResource(R.string.settings_rg_album)
                        }
                    }
                )

                val modeHint = when (replayGainMode) {
                    ReplayGainMode.OFF -> stringResource(R.string.settings_rg_off_desc)
                    ReplayGainMode.TRACK -> stringResource(R.string.settings_rg_track_desc)
                    ReplayGainMode.ALBUM -> stringResource(R.string.settings_rg_album_desc)
                }
                Text(
                    text = modeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // Pre-amp (solo relevante si el modo no es OFF)
                if (replayGainMode != ReplayGainMode.OFF) {
                    HorizontalDivider(color = AppColors.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))
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
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_eq_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_use_system_eq),
                    description = stringResource(R.string.settings_use_system_eq_desc),
                    checked = uiState.useSystemEq,
                    onCheckedChange = { viewModel.setUseSystemEq(it) }
                )

                HorizontalDivider(color = AppColors.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(Screen.SettingsEqPresets.route) }
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_eq_presets),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_eq_presets_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    MaterialSymbol("chevron_right", size = 24.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LyricsSaveSetting(
            mode = uiState.playbackSettings.lyricsSaveMode,
            folderUri = uiState.playbackSettings.lyricsFolderUri,
            onModeChange = { viewModel.setLyricsSaveMode(it) },
            onFolderChange = { viewModel.setLyricsFolder(it) }
        )
    }
}

/**
 * Qué presets y perfiles del ecualizador se listan en el selector del reproductor.
 *
 * Los dos grupos son los mismos del selector, y por el mismo motivo: un preset (los de fábrica)
 * aplica solo la curva de bandas y un perfil (los guardados) el sonido completo. Mezclarlos aquí
 * desharía la distinción justo en la pantalla donde se decide qué conservar a la vista.
 *
 * Vive en Ajustes y no en la hoja del EQ a propósito: es una tarea de mantenimiento que se hace
 * una vez y luego no se vuelve a tocar, mientras que esa hoja es una pantalla de uso constante
 * donde cada control extra compite con los sliders. Meter ahí una lista de diez interruptores
 * habría sido pagar todos los días por algo que se usa una tarde.
 *
 * Ocultar y borrar son acciones DISTINTAS y por eso están separadas: los de fábrica solo se pueden
 * ocultar (no se pueden recrear si te arrepentís) y los propios además se pueden borrar. Un icono
 * de basura sobre "Rock" habría sido una puerta sin vuelta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsEqPresetsScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel
) {
    val hidden by viewModel.eqPresets.hidden.collectAsStateWithLifecycle()
    // Los mismos dos grupos que el selector del EQ, y por el mismo motivo: un preset aplica solo la
    // curva y un perfil el sonido completo. Si aquí aparecieran mezclados, esta pantalla desharía
    // la distinción justo donde el usuario decide cuáles conservar a la vista.
    val profiles by viewModel.eqPresets.profiles.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_eq_presets),
        onBackClick = onBackClick
    ) {
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_eq_presets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                EqPresets.ALL.forEach { preset ->
                    val key = EqPresets.hideKey(preset)
                    PresetVisibilityRow(
                        name = stringResource(preset.labelRes),
                        visible = key !in hidden,
                        onVisibleChange = { viewModel.eqPresets.setEntryHidden(key, !it) },
                        onDelete = null
                    )
                }

                if (profiles.isNotEmpty()) {
                    HorizontalDivider(color = AppColors.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = stringResource(R.string.settings_eq_profiles_own),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    profiles.forEach { profile ->
                        PresetVisibilityRow(
                            name = profile.name,
                            visible = profile.id !in hidden,
                            onVisibleChange = { viewModel.eqPresets.setEntryHidden(profile.id, !it) },
                            onDelete = { viewModel.eqPresets.deleteProfile(profile.id) }
                        )
                    }
                }

                // Solo cuando hay algo que restaurar: un botón permanentemente inútil enseña al
                // usuario a ignorar esa zona de la pantalla.
                if (hidden.isNotEmpty()) {
                    HorizontalDivider(color = AppColors.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))
                    TextButton(colors = appTextButtonColors(), onClick = { viewModel.eqPresets.restoreAll() }) {
                        Text(stringResource(R.string.settings_eq_presets_restore_all, hidden.size))
                    }
                }
            }
        }
    }
}

/**
 * Una fila de la gestión de presets. El interruptor dice VISIBLE (no "oculto"): un switch en
 * posición de encendido tiene que significar que la cosa está, o hay que leer la etiqueta dos
 * veces para saber qué hace el gesto obvio.
 */
@Composable
private fun PresetVisibilityRow(
    name: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (visible) {
                AppColors.onSurface
            } else {
                AppColors.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                MaterialSymbol(
                    icon = "delete",
                    size = 20.sp,
                    color = AppColors.onSurfaceVariant
                )
            }
        }
        Switch(colors = appSwitchColors(), checked = visible, onCheckedChange = onVisibleChange)
    }
}

/**
 * Carpetas que el escaneo del dispositivo debe IGNORAR.
 *
 * Existe porque el modo dispositivo indexa todo lo que el sistema marca como música, y ahí caen
 * cosas que nadie considera su biblioteca: audios de mensajería, grabaciones de voz, pistas de
 * juegos. El filtro por flags de MediaStore (`IS_MUSIC` y compañía) no las distingue —los archivos
 * fuera de `Music/` suelen tener todos esos flags sin rellenar—, así que la única forma honesta de
 * separarlas es que lo diga quien las tiene.
 *
 * **La lista sale del ORIGEN (MediaStore) y no de la biblioteca ya indexada**: una carpeta excluida
 * no deja ninguna canción en `songs`, así que leyéndola de ahí desaparecería de esta pantalla y no
 * habría manera de volver a incluirla.
 *
 * Solo aplica al modo dispositivo. Con carpetas elegidas a mano la inclusión ya es explícita, y una
 * lista de exclusiones al lado sería un segundo mecanismo para decir lo mismo.
 */
@Composable
fun SettingsExcludedFoldersScreen(
    onBackClick: () -> Unit,
    sourcesViewModel: SourcesViewModel
) {
    val folders by sourcesViewModel.deviceAudioFolders.collectAsStateWithLifecycle()
    val excluded by sourcesViewModel.excludedDeviceFolders.collectAsStateWithLifecycle()

    // La lista se consulta al abrir: puede haber cambiado desde la última vez (música copiada,
    // una carpeta nueva) y es una consulta puntual, no algo que merezca una suscripción viva.
    LaunchedEffect(Unit) { sourcesViewModel.refreshDeviceAudioFolders() }

    // El re-escaneo se aplica AL SALIR, no en cada toque: marcar cinco carpetas encadenaría cinco
    // escaneos de los que solo el último dice la verdad.
    DisposableEffect(Unit) {
        onDispose { sourcesViewModel.flushExcludedFolderChanges() }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_excluded_folders),
        onBackClick = onBackClick
    ) {
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_excluded_folders_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val known = folders
                when {
                    known == null -> Text(
                        text = stringResource(R.string.settings_excluded_folders_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.onSurfaceVariant
                    )

                    known.isEmpty() -> Text(
                        text = stringResource(R.string.settings_excluded_folders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.onSurfaceVariant
                    )

                    else -> known.forEach { folder ->
                        ExcludedFolderRow(
                            folder = folder,
                            included = excluded.none { folder.path == it || folder.path.startsWith("$it/") },
                            // Solo se puede conmutar la carpeta EXACTA que está en la lista: una
                            // que quedó fuera porque su padre está excluido se muestra apagada y
                            // bloqueada, porque encenderla no haría nada mientras el padre siga
                            // excluido, y mentir con un switch que no manda es peor que no ofrecerlo.
                            locked = folder.path !in excluded &&
                                excluded.any { folder.path.startsWith("$it/") },
                            onIncludedChange = { include ->
                                sourcesViewModel.setDeviceFolderExcluded(folder.path, !include)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una carpeta con su interruptor. El switch dice INCLUIDA (encendido = se escanea), no excluida:
 * el estado positivo del control tiene que ser el estado positivo de la cosa, o cada lectura pide
 * una doble negación.
 */
@Composable
private fun ExcludedFolderRow(
    folder: DeviceAudioFolder,
    included: Boolean,
    locked: Boolean,
    onIncludedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // El último segmento es el nombre de la carpeta, que es como el usuario la conoce.
                text = folder.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyLarge,
                color = if (included) AppColors.onSurface else AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // La ruta entera desambigua dos carpetas con el mismo nombre, y el conteo dice
                // cuánto se gana o se pierde al conmutarla.
                text = stringResource(
                    R.string.settings_excluded_folder_detail,
                    folder.path,
                    folder.songCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            colors = appSwitchColors(),
            checked = included,
            enabled = !locked,
            onCheckedChange = onIncludedChange
        )
    }
}

/**
 * Gestos: cómo se maneja el reproductor con el dedo. Categoría propia — vivía dentro de
 * Reproducción, cuyo encabezado es "Volumen (ReplayGain)", y ahí no tenía nada que ver con lo
 * que la rodeaba ni había forma de encontrarlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGesturesScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_gestures_header),
        onBackClick = onBackClick
    ) {
        // UN solo switch para los cuatro gestos (ver MusicPreferences): son el mismo contrato y
        // trocearlo obligaría a razonar sobre gestos aún no descubiertos. La descripción los
        // ENUMERA porque, apagados, no hay forma de que el usuario sepa qué se está perdiendo —
        // un gesto que nadie te contó no existe.
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fila entera alterna (mismo criterio que SettingsSwitchTile).
                    .toggleable(
                        value = uiState.playerGestures,
                        onValueChange = { viewModel.setPlayerGestures(it) },
                        role = Role.Switch
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaterialSymbol("swipe", size = 24.sp)

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_player_gestures),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_player_gestures_desc,
                            PlayerGestureConfig.SeekStepSeconds
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Switch(
                    colors = appSwitchColors(),
                    checked = uiState.playerGestures,
                    onCheckedChange = null
                )
            }
        }
    }
}

/**
 * Dónde guardar las letras y, para la música del dispositivo, en qué carpeta dejar los `.lrc`.
 *
 * La opción "preguntar cada vez" tiene que seguir estando: el diálogo del reproductor la puede
 * apagar con un check, y sin una vuelta atrás quedaría atrapado en la elección de aquel día.
 */
@Composable
private fun LyricsSaveSetting(
    mode: LyricsSaveMode,
    folderUri: String?,
    onModeChange: (LyricsSaveMode) -> Unit,
    onFolderChange: (String?) -> Unit
) {
    val context = LocalContext.current
    // El árbol se retiene con permiso persistente: sin esto el acceso muere al reiniciar la app
    // y el usuario vería "sin carpeta" sin haber tocado nada.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onFolderChange(uri.toString())
        }
    }

    AppSurface(
        shape = RoundedCornerShape(SettingsTokens.BlockCorner),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_lyrics_save_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val modes = listOf(
                LyricsSaveMode.ASK to stringResource(R.string.settings_lyrics_save_ask),
                LyricsSaveMode.LRC_FILE to stringResource(R.string.settings_lyrics_save_lrc),
                LyricsSaveMode.EMBEDDED to stringResource(R.string.settings_lyrics_save_embedded)
            )
            modes.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == value,
                            role = Role.RadioButton,
                            onClick = { onModeChange(value) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(colors = appRadioButtonColors(), selected = mode == value, onClick = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider(color = AppColors.outlineVariant, modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.settings_lyrics_folder_title),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.settings_lyrics_folder_summary),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant
            )
            Text(
                text = folderUri?.let { safFolderDisplayName(it) }
                    ?: stringResource(R.string.settings_lyrics_folder_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(colors = appTextButtonColors(), onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.settings_lyrics_folder_choose))
                }
                if (folderUri != null) {
                    TextButton(colors = appTextButtonColors(), onClick = { onFolderChange(null) }) {
                        Text(stringResource(R.string.settings_lyrics_folder_clear))
                    }
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
    PlayerToolbarAction.SHARE -> R.string.np_share
}

private fun PlayerToolbarAction.iconName(): String = when (this) {
    PlayerToolbarAction.REPEAT -> "repeat"
    PlayerToolbarAction.LYRICS -> "lyrics"
    PlayerToolbarAction.QUEUE -> "queue_music"
    PlayerToolbarAction.KEEP_SCREEN_ON -> "visibility"
    PlayerToolbarAction.EQUALIZER -> "graphic_eq"
    PlayerToolbarAction.SLEEP_TIMER -> "snooze"
    PlayerToolbarAction.ADD_TO_PLAYLIST -> "playlist_add"
    PlayerToolbarAction.DOWNLOAD -> "download"
    PlayerToolbarAction.SHARE -> "share"
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
                AppSurface(
                    tonalElevation = if (isDragging) 6.dp else 0.dp,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Box(modifier = with(itemScope) { Modifier.draggableHandle() }.padding(8.dp)) {
                            MaterialSymbol("drag_indicator", color = AppColors.onSurfaceVariant)
                        }
                    MaterialSymbol(
                        item.action.iconName(),
                        color = AppColors.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = stringResource(item.action.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        colors = appSwitchColors(),
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
            color = AppColors.onSurfaceVariant,
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
    viewModel: SyncViewModel,
    sourcesViewModel: SourcesViewModel
) {
    val storageLimitGb by viewModel.storageLimitGb.collectAsStateWithLifecycle()

    // ¿Hay alguna fuente de NUBE? Del registro de fuentes, no de OneDrive en particular:
    // un proveedor cloud futuro cuenta sin tocar este gate.
    val hasCloudSource by sourcesViewModel.hasCloudSource.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_downloads_header),
        onBackClick = onBackClick
    ) {
        // Mismo componente que el onboarding (ver StorageLimitCard): acá con el radio de las
        // tarjetas de Ajustes y con el aviso de "solo con nube" como descripción al estar
        // deshabilitado.
        StorageLimitCard(
            limitGb = storageLimitGb,
            onLimitChangeFinished = viewModel::setStorageLimitGb,
            enabled = hasCloudSource,
            description = if (hasCloudSource) stringResource(R.string.download_storage_limit_desc)
            else stringResource(R.string.settings_downloads_cloud_only),
            shape = RoundedCornerShape(SettingsTokens.BlockCorner)
        )

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        // Fotos de artistas (Deezer): política de red del backfill y del fetch del detalle.
        // El estado reactivo sale de un BrowseViewModel propio de esta pantalla; las acciones
        // van al repo singleton, así que el banner de la pestaña Artistas (otra instancia de
        // VM) reacciona igual.
        //
        // Esta instancia es barata desde que el backfill dejó de vivir en el `init` del ViewModel
        // (ver [BrowseViewModel.startArtistPhotoMaintenance]): antes, abrir esta pantalla —que solo
        // quiere leer tres preferencias— lanzaba una pasada completa de fotos y dejaba un colector
        // de red registrado. El resto de sus flujos son `WhileUiSubscribed` y no arrancan si nadie
        // los colecta, así que aquí solo corren estos tres.
        val browseViewModel: BrowseViewModel = hiltViewModel()
        val photosOnMetered by browseViewModel.artistPhotosOnMetered.collectAsStateWithLifecycle()
        val photosBannerEnabled by browseViewModel.artistPhotosBannerEnabled.collectAsStateWithLifecycle()
        val photoDetailOnMetered by browseViewModel.artistPhotoDetailOnMetered.collectAsStateWithLifecycle()
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
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
    /** Navega a las sub-pantallas de personalización (pestañas, barra del reproductor). */
    onNavigate: (String) -> Unit,
    viewModel: LibraryViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRegenerating = uiState.isRegeneratingColors
    val nowPlayingBackground = uiState.nowPlayingBackground
    val miniPlayerRoundedRect = uiState.miniPlayerRoundedRect
    val miniPlayerRoundPlayButton = uiState.miniPlayerRoundPlayButton
    val nowPlayingDetailedFormat = uiState.nowPlayingDetailedFormat

    // Regenerar es DESTRUCTIVO e irreversible: además de vaciar los colores extraídos —que los
    // vuelve a calcular el ArtworkWorker— borra los que el usuario eligió a mano
    // (`clearManualColorIds`), y eso no lo reconstruye nadie. Estaba a un solo toque, sin aviso, en
    // una lista donde las filas vecinas son switches inocuos; se perdió una biblioteca entera de
    // overrides por tocarlo de pasada.
    var showRegenerateConfirm by remember { mutableStateOf(false) }

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
            onClick = { showRegenerateConfirm = true }
        )

        if (showRegenerateConfirm) {
            AlertDialog(
                onDismissRequest = { showRegenerateConfirm = false },
                title = { Text(stringResource(R.string.settings_regenerate_confirm_title)) },
                text = { Text(stringResource(R.string.settings_regenerate_confirm_message)) },
                confirmButton = {
                    TextButton(colors = appTextButtonColors(), onClick = {
                        showRegenerateConfirm = false
                        viewModel.regenerateColors()
                    }) { Text(stringResource(R.string.settings_regenerate_confirm_action)) }
                },
                dismissButton = {
                    TextButton(colors = appTextButtonColors(), onClick = { showRegenerateConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        // Los conmutadores de forma y presentación del reproductor van JUNTOS en un grupo. Antes
        // estaban sueltos y separados entre sí por el estilo de paleta, así que ajustes del mismo
        // sitio se leían como cosas sin relación. Los dos del MiniPlayer van seguidos: primero el
        // contenedor, después el botón de play.
        SettingsGroup(
            listOf<@Composable (ListItemShapes) -> Unit>(
                // Fondo del reproductor. Dejó de ser un switch al aparecer el tercer modo: son
                // tres presentaciones EXCLUSIVAS de la misma superficie, no dos interruptores.
                { shapes ->
                    NowPlayingBackgroundTile(
                        selected = nowPlayingBackground,
                        shapes = shapes,
                        onSelect = { viewModel.setNowPlayingBackground(it) }
                    )
                },
                // Forma de la barra flotante: píldora (diseño original) o rectángulo redondeado.
                // Solo afecta al contenedor; la carátula del mini sigue siendo circular porque es
                // el extremo del shared element hacia el reproductor.
                { shapes ->
                    SettingsSwitchTile(
                        icon = "rounded_corner",
                        title = stringResource(R.string.settings_mini_player_rect),
                        description = stringResource(R.string.settings_mini_player_rect_desc),
                        checked = miniPlayerRoundedRect,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setMiniPlayerRoundedRect(it) }
                    )
                },
                // Forma del botón de play del mini: círculo o squircle, las dos que M3 tabula para
                // esa talla. Va pegado al ajuste de la barra porque los dos son la forma de la
                // misma pieza, de fuera hacia adentro.
                { shapes ->
                    SettingsSwitchTile(
                        icon = "play_circle",
                        title = stringResource(R.string.settings_mini_player_round_play),
                        description = stringResource(R.string.settings_mini_player_round_play_desc),
                        checked = miniPlayerRoundPlayButton,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setMiniPlayerRoundPlayButton(it) }
                    )
                },
                // Chip de formato: solo el contenedor (FLAC) o la ficha técnica
                // (FLAC · 16 bit · 44.1 kHz). El mismo ajuste se conmuta tocando el chip en el
                // reproductor; ambos escriben la misma preferencia.
                { shapes ->
                    SettingsSwitchTile(
                        icon = "graphic_eq",
                        title = stringResource(R.string.settings_detailed_format),
                        description = stringResource(R.string.settings_detailed_format_desc),
                        checked = nowPlayingDetailedFormat,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setNowPlayingDetailedFormat(it) }
                    )
                }
            )
        )

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        // Estilo de paleta: cuánto croma se aplica al generar el tema desde el color del álbum.
        // Cambia el tema EN VIVO (no hace falta regenerar colores: el estilo actúa sobre el seed
        // ya guardado, no sobre la extracción). Va SUELTO: no es una fila, es un bloque con su
        // propio selector, y meterlo en el grupo rompería la continuidad de los switches.
        ThemePaletteStyleSetting(
            selected = uiState.themePaletteStyle,
            onSelect = { viewModel.setThemePaletteStyle(it) }
        )

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        // Las personalizaciones que necesitan más de una fila viven en su propia pantalla: metidas
        // aquí ocupaban Apariencia entera y enterraban el resto de ajustes.
        SettingsGroup(
            listOf<@Composable (ListItemShapes) -> Unit>(
                // Barra de progreso: modo (plana/ondulada) y grosor, con vista previa en vivo. Los
                // dos ajustes se eligen MIRANDO la barra, así que van donde se la puede enseñar —
                // el switch suelto en esta lista obligaba a salir al reproductor para ver el
                // efecto, y el grosor no se juzga leyendo "12 dp".
                { shapes ->
                    SettingsActionRow(
                        icon = "linear_scale",
                        title = stringResource(R.string.settings_progress_bar_header),
                        description = stringResource(R.string.settings_progress_bar_desc),
                        enabled = true,
                        onClick = { onNavigate(Screen.SettingsProgressBar.route) },
                        navigates = true,
                        shapes = shapes
                    )
                },
                { shapes ->
                    SettingsActionRow(
                        icon = "tab",
                        title = stringResource(R.string.settings_nav_header),
                        description = stringResource(R.string.settings_nav_desc),
                        enabled = true,
                        onClick = { onNavigate(Screen.SettingsTabs.route) },
                        navigates = true,
                        shapes = shapes
                    )
                },
                { shapes ->
                    SettingsActionRow(
                        icon = "bottom_navigation",
                        title = stringResource(R.string.settings_player_bar_header),
                        description = stringResource(R.string.settings_player_bar_desc),
                        enabled = true,
                        onClick = { onNavigate(Screen.SettingsPlayerBar.route) },
                        navigates = true,
                        shapes = shapes
                    )
                }
            )
        )

        // El "laboratorio de color" (7 sliders de tuning del extractor) se eliminó con el
        // cambio a QuantizerCelebi + Score: ese pipeline no tiene parámetros que ajustar. El
        // visor de candidatos, que sí sigue siendo útil, vive donde se usa: el diálogo de
        // long-press sobre la carátula del NowPlaying (NowPlayingColorPicker).
    }
}

/**
 * Barra de progreso del reproductor: modo (plana u ondulada) y grosor, **con la barra de verdad
 * arriba**. Es una pantalla propia y no dos filas sueltas en Apariencia porque los dos ajustes se
 * eligen mirando el resultado: el switch obligaba a salir al reproductor para ver qué hacía, y un
 * grosor no se juzga leyendo "12 dp".
 *
 * La vista previa dibuja los tracks REALES ([ProgressBarPreview]), así que el modo se ve moverse y
 * el grosor cambia bajo el dedo mientras se arrastra el slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProgressBarScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val wavy = uiState.nowPlayingWavyProgress
    val thickness = uiState.nowPlayingProgressThickness
    val handle = uiState.nowPlayingProgressHandle

    SettingsScaffold(
        title = stringResource(R.string.settings_progress_bar_header),
        onBackClick = onBackClick
    ) {
        // La previa va PRIMERO y en su propio bloque: es el objeto sobre el que actúan los dos
        // controles de abajo, no una decoración de uno de ellos.
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Mismos roles que en el reproductor: el activo es el acento (allí, el color del
                // álbum) y el riel es ese mismo color muy atenuado.
                ProgressBarPreview(
                    wavy = wavy,
                    trackHeight = thickness.dp,
                    showHandle = handle,
                    activeColor = AppColors.primary,
                    inactiveColor = AppColors.primary.copy(alpha = PREVIEW_RAIL_ALPHA)
                )
            }
        }

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        SettingsGroup(
            listOf<@Composable (ListItemShapes) -> Unit>(
                { shapes ->
                    SettingsSwitchTile(
                        icon = "waves",
                        title = stringResource(R.string.settings_wavy_progress),
                        description = stringResource(R.string.settings_wavy_progress_desc),
                        checked = wavy,
                        shapes = shapes,
                        onCheckedChange = { checked ->
                            viewModel.setNowPlayingWavyProgress(checked)
                            // El tope del grosor depende del modo (la onda admite menos que la
                            // píldora, ver `progressTrackHeightMax`), así que al encender la onda
                            // con un grosor que ella no admite se BAJA el valor guardado. El dibujo
                            // ya lo acota de todos modos; lo que esto evita es que el slider de
                            // abajo y la previa de arriba enseñen 24 mientras en disco hay 44 —y que
                            // apagar la onda devolviera un grosor que el usuario no volvió a elegir.
                            val max = progressTrackHeightMax(checked).value.toInt()
                            if (thickness > max) viewModel.setNowPlayingProgressThickness(max)
                        }
                    )
                },
                { shapes ->
                    // Va en el MISMO bloque que el modo: los dos dicen cómo se DIBUJA la barra que
                    // está arriba, mientras el grosor tiene su propio bloque porque es un slider.
                    SettingsSwitchTile(
                        icon = "tune",
                        title = stringResource(R.string.settings_progress_handle),
                        description = stringResource(R.string.settings_progress_handle_desc),
                        checked = handle,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setNowPlayingProgressHandle(it) }
                    )
                }
            )
        )

        Spacer(modifier = Modifier.height(SettingsTokens.SectionGap))

        ProgressThicknessSetting(
            thicknessDp = thickness,
            wavy = wavy,
            onChange = { viewModel.setNowPlayingProgressThickness(it) }
        )
    }
}

/**
 * Opacidad del riel en la vista previa: la MISMA que el track recibe en el reproductor. Allí llega
 * como `playButtonColor.copy(0.2f)` y `ProgressSlider` lo vuelve a pasar por `copy(alpha = 0.25f)`
 * — que REEMPLAZA el alpha, no lo multiplica—, así que el valor que pinta es este.
 */
private const val PREVIEW_RAIL_ALPHA = 0.25f

/** Pestañas de la biblioteca: orden (drag & drop) + cuáles se muestran. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabsScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel
) {
    val tabs by viewModel.libraryTabs.collectAsStateWithLifecycle()
    val bottomTabs by viewModel.libraryBottomTabs.collectAsStateWithLifecycle()
    val modernNav by viewModel.libraryModernNav.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_nav_header),
        onBackClick = onBackClick
    ) {
        // El ESTILO primero, y luego dónde van las pestañas y cuáles hay: es la decisión que
        // enmarca a las otras dos, igual que "dónde" enmarcaba a "cuáles" cuando eran las únicas.
        //
        // Los dos controles de pestañas se INHABILITAN con el estilo moderno, no se ocultan: si
        // desaparecieran, el usuario no tendría forma de entender por qué su configuración dejó de
        // aplicarse. Su valor persistido NO se toca, así que apagar el estilo moderno devuelve
        // exactamente lo que tenía. En horizontal el ajuste de abajo no manda en ningún caso —
        // allí las pestañas van al lado siempre.
        SettingsGroup(
            listOf<@Composable (ListItemShapes) -> Unit>(
                { shapes ->
                    SettingsSwitchTile(
                        icon = "explore",
                        title = stringResource(R.string.settings_modern_nav),
                        description = stringResource(R.string.settings_modern_nav_desc),
                        checked = modernNav,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setLibraryModernNav(it) }
                    )
                },
                { shapes ->
                    SettingsSwitchTile(
                        icon = "bottom_navigation",
                        title = stringResource(R.string.settings_bottom_tabs),
                        description = stringResource(R.string.settings_bottom_tabs_desc),
                        checked = bottomTabs,
                        enabled = !modernNav,
                        shapes = shapes,
                        onCheckedChange = { viewModel.setLibraryBottomTabs(it) }
                    )
                }
            )
        )
        Spacer(Modifier.height(SettingsTokens.SectionGap))
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_tabs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant.copy(
                        alpha = if (modernNav) DISABLED_CONTENT_ALPHA else 1f
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LibraryTabsCustomizer(
                    config = tabs,
                    enabled = !modernNav,
                    onConfigChange = { viewModel.setLibraryTabs(it) }
                )
            }
        }
    }
}

/** Barra del reproductor: orden (drag & drop) + barra flotante vs overflow de cada acción. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlayerBarScreen(
    onBackClick: () -> Unit,
    viewModel: LibraryViewModel
) {
    val toolbarConfig by viewModel.toolbarConfig.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_player_bar_header),
        onBackClick = onBackClick
    ) {
        AppSurface(
            shape = RoundedCornerShape(SettingsTokens.BlockCorner),
            color = AppColors.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_player_bar_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                PlayerBarCustomizer(
                    config = toolbarConfig,
                    onConfigChange = { viewModel.setToolbarConfig(it) }
                )
            }
        }
    }
}

/** Etiqueta legible de cada pestaña para la lista de personalización. */
private fun LibraryTabId.labelRes(): Int = when (this) {
    LibraryTabId.HOME -> R.string.tab_home
    LibraryTabId.SONGS -> R.string.tab_all
    LibraryTabId.ARTISTS -> R.string.common_artists
    LibraryTabId.ALBUMS -> R.string.common_albums
    LibraryTabId.GENRES -> R.string.common_genres
    LibraryTabId.PLAYLISTS -> R.string.tab_playlists
}

private fun LibraryTabId.iconName(): String = when (this) {
    LibraryTabId.HOME -> "home"
    LibraryTabId.SONGS -> "library_music"
    LibraryTabId.ARTISTS -> "artist"
    LibraryTabId.ALBUMS -> "album"
    LibraryTabId.GENRES -> "genres"
    LibraryTabId.PLAYLISTS -> "playlist_play"
}

/**
 * Lista reordenable (drag & drop) de las pestañas de la biblioteca, con un switch de visibilidad
 * por fila. Gemela de [PlayerBarCustomizer] —mismo gotcha de `ReorderableItem` para recuperar el
 * `draggableHandle`— con un tope invertido: en vez de un MÁXIMO de acciones en barra, aquí hay un
 * MÍNIMO de pestañas visibles ([LibraryTabsConfig.MIN_VISIBLE]), porque quedarse sin ninguna
 * dejaría la biblioteca vacía y sin forma de recuperarla desde el propio pager.
 *
 * Estado local para que el arrastre sea fluido (sin round-trip a DataStore por frame); se
 * re-siembra si [config] cambia desde fuera, y cada cambio persiste vía [onConfigChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabsCustomizer(
    config: List<LibraryTabState>,
    /**
     * false con el estilo moderno encendido: ahí no hay pestañas que ordenar ni ocultar. Se
     * INHABILITA en vez de ocultarse para que se vea que la configuración sigue guardada y por
     * qué no responde. El alpha de atenuación es el que M3 tabula para el contenido deshabilitado
     * —la excepción legítima a "la jerarquía se hace con tono, no con alpha"—, y aquí hay que
     * aplicarlo a mano porque estas filas son una `Row` propia y no un componente de la librería.
     */
    enabled: Boolean = true,
    onConfigChange: (List<LibraryTabState>) -> Unit
) {
    var items by remember { mutableStateOf(config) }
    LaunchedEffect(config) { items = config }

    val visibleCount = items.count { it.visible }
    val atMin = visibleCount <= LibraryTabsConfig.MIN_VISIBLE
    val contentAlpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA

    sh.calvin.reorderable.ReorderableColumn(
        list = items,
        onSettle = { from, to ->
            val newList = items.toMutableList().apply { add(to, removeAt(from)) }
            items = newList
            onConfigChange(newList)
        },
        modifier = Modifier.fillMaxWidth()
    ) { _, item, isDragging ->
        key(item.tab) {
            ReorderableItem {
                val itemScope = this
                AppSurface(
                    tonalElevation = if (isDragging) 6.dp else 0.dp,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Box(
                            modifier = with(itemScope) { Modifier.draggableHandle(enabled = enabled) }
                                .padding(8.dp)
                        ) {
                            MaterialSymbol(
                                "drag_indicator",
                                color = AppColors.onSurfaceVariant.copy(alpha = contentAlpha)
                            )
                        }
                        MaterialSymbol(
                            item.tab.iconName(),
                            color = AppColors.onSurfaceVariant.copy(alpha = contentAlpha),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = stringResource(item.tab.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.onSurface.copy(alpha = contentAlpha),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            colors = appSwitchColors(),
                            checked = item.visible,
                            // Deshabilitar SOLO la última encendida: apagarla dejaría la
                            // biblioteca sin pestañas. Y todas con el estilo moderno.
                            enabled = enabled && (!item.visible || !atMin),
                            onCheckedChange = { checked ->
                                val newList = items.map {
                                    if (it.tab == item.tab) it.copy(visible = checked) else it
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
    if (atMin && enabled) {
        Text(
            text = stringResource(R.string.settings_tabs_min),
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
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

/**
 * Grosor de la barra de progreso del reproductor. UN solo slider para los DOS modos (píldora plana
 * y onda): de este número la barra deriva el trazo, la amplitud, el indicador y el alto del palo
 * (ver `progressMetricsFor`), así que no hay nada más que ajustar ni forma de dejar la geometría
 * incoherente.
 *
 * No dibuja ninguna muestra: vive en [SettingsProgressBarScreen], que ya enseña la barra REAL
 * encima y la actualiza mientras se arrastra este slider. Eso es lo que justifica que el ajuste
 * tenga pantalla propia — "12 dp" no se juzga leyéndolo.
 */
@Composable
private fun ProgressThicknessSetting(
    thicknessDp: Int,
    /** Modo activo: la onda admite menos grosor que la píldora (ver `progressTrackHeightMax`). */
    wavy: Boolean,
    onChange: (Int) -> Unit
) {
    val min = ComponentConfig.ProgressTrackHeightMin
    val max = progressTrackHeightMax(wavy)
    val step = ComponentConfig.ProgressTrackHeightStep
    // Paradas INTERMEDIAS (el Slider no cuenta los extremos), de ahí el −1.
    val steps = ((max - min) / step).toInt() - 1
    val current = thicknessDp.dp.coerceIn(min, max)

    AppSurface(
        shape = RoundedCornerShape(SettingsTokens.BlockCorner),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialSymbol("line_weight", size = 24.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_progress_thickness),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_progress_thickness_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.settings_progress_thickness_value, current.value.toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.primary
                )
            }

            // Sin muestra propia: la barra REAL está arriba, en la misma pantalla
            // ([SettingsProgressBarScreen]), y responde a este slider mientras se arrastra. Dos
            // dibujos del mismo objeto solo pueden acabar contándose cosas distintas.
            Slider(
                colors = appSliderColors(),
                value = current.value,
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = min.value..max.value,
                steps = steps
            )
        }
    }
}

/**
 * Los fondos del reproductor, en el orden en que se muestran: valor persistido, etiqueta y
 * descripción de qué hace.
 */
private val NowPlayingBackgroundOptions = listOf(
    NowPlayingBackground.GRADIENT to
        (R.string.settings_np_bg_gradient to R.string.settings_np_bg_gradient_desc),
    NowPlayingBackground.SOLID to
        (R.string.settings_np_bg_solid to R.string.settings_np_bg_solid_desc)
)

/**
 * Fondo del reproductor: fila de grupo que despliega los modos disponibles.
 *
 * Mismo idiom de menú que [ThemePaletteStyleSetting] —selección EXCLUSIVA, con `selected`, el
 * check en `selectedLeadingIcon` y la explicación en `supportingText`— pero como ítem de
 * [SettingsGroup], porque esto vive junto al resto de la presentación del reproductor y la forma
 * se la da el grupo.
 */
@Composable
private fun NowPlayingBackgroundTile(
    selected: NowPlayingBackground,
    shapes: ListItemShapes,
    onSelect: (NowPlayingBackground) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = NowPlayingBackgroundOptions.firstOrNull { it.first == selected }
        ?: NowPlayingBackgroundOptions.first()

    SegmentedListItem(
        onClick = { expanded = true },
        shapes = shapes,
        colors = appSegmentedListItemColors(AppColors.surfaceContainerHigh),
        leadingContent = { MaterialSymbol("gradient", size = 24.sp) },
        trailingContent = {
            Box {
                MaterialSymbol("expand_more", size = 24.sp, color = AppColors.onSurfaceVariant)
                AppMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                        NowPlayingBackgroundOptions.forEachIndexed { index, (mode, labels) ->
                            DropdownMenuItem(
                                colors = appSelectableMenuItemColors(),
                                selected = mode == current.first,
                                onClick = {
                                    expanded = false
                                    onSelect(mode)
                                },
                                text = { Text(stringResource(labels.first)) },
                                shapes = MenuDefaults.itemShape(
                                    index = index,
                                    count = NowPlayingBackgroundOptions.size
                                ),
                                selectedLeadingIcon = { MenuItemIcon("check") },
                                supportingText = { Text(stringResource(labels.second)) }
                            )
                        }
                    }
                }
            }
        },
        content = {
            SettingsTileText(
                title = stringResource(R.string.settings_np_bg),
                description = stringResource(current.second.first)
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Selector del estilo de paleta: fila con el valor actual que despliega el menú de opciones. */
@Composable
private fun ThemePaletteStyleSetting(
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Un estilo retirado de la librería (o una preferencia vieja) no debe dejar la fila vacía.
    val current = PaletteStyleOptions.firstOrNull { it.first == selected } ?: PaletteStyleOptions.first()

    AppSurface(
        shape = RoundedCornerShape(SettingsTokens.BlockCorner),
        color = AppColors.surfaceContainerHigh,
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
                    color = AppColors.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Box {
                MaterialSymbol(
                    "expand_more",
                    size = 24.sp,
                    color = AppColors.onSurfaceVariant
                )
                // Elegir estilo de paleta es una SELECCIÓN EXCLUSIVA: sobrecarga `selected`, que
                // marca el activo con contenedor y morph de forma. La descripción va en el slot
                // `supportingText` del propio item y no en una `Column` metida dentro de `text`
                // —el componente ya sabe maquetar título + apoyo, con su tipografía y su color— y
                // el check pasa a `selectedLeadingIcon`, que además lo anima al entrar.
                AppMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppMenuGroup(shapes = MenuDefaults.groupShapes()) {
                        PaletteStyleOptions.forEachIndexed { index, (name, labels) ->
                            DropdownMenuItem(
                                colors = appSelectableMenuItemColors(),
                                selected = name == current.first,
                                onClick = {
                                    expanded = false
                                    onSelect(name)
                                },
                                text = { Text(stringResource(labels.first)) },
                                shapes = MenuDefaults.itemShape(
                                    index = index,
                                    count = PaletteStyleOptions.size
                                ),
                                selectedLeadingIcon = { MenuItemIcon("check") },
                                supportingText = { Text(stringResource(labels.second)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== Piezas compartidas ====================

/**
 * Andamiaje común de Ajustes: app bar grande colapsable + columna scrolleable con los
 * márgenes estándar. Lo usan el hub y todas las sub-pantallas.
 *
 * La barra es `LargeFlexibleTopAppBar`, la variante de M3 Expressive, y no la `LargeTopAppBar`
 * clásica —que es la generación anterior del componente—. Lo que se gana es el slot [subtitle]:
 * el hub ya enseña una línea de estado bajo cada categoría (estilo Ajustes de Android) y hasta
 * ahora esa información se PERDÍA al entrar en la pantalla, que es justo donde se está actuando
 * sobre ella. El alto lo decide `TopAppBarDefaults` según haya subtítulo o no, así que ninguna
 * pantalla fija una altura.
 *
 * @param subtitle línea de estado bajo el título. Solo tiene sentido cuando resume algo que la
 *        pantalla ya sabe y el usuario querría comprobar de un vistazo; null en las que son una
 *        lista de ajustes independientes, donde un resumen sería inventado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBackClick: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        containerColor = AppColors.background,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                // EXPLÍCITOS: sin ellos la barra resuelve sus colores contra `MaterialTheme` —el
                // esquema BASE— y la banda superior de TODA la pantalla se quedaba con el tinte del
                // wallpaper mientras el contenido llevaba el de la carátula.
                colors = appTopAppBarColors(),
                // El default de la barra (`systemBarsForVisualComponents`) IGNORA la perforación,
                // que en horizontal cae en un costado: sin esto la flecha de volver queda debajo.
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        // La columna de ajustes NO se estira a lo ancho de la ventana (27 ago 2026): girado, cada
        // fila medía ~873dp y dejaba el título de un ajuste a un lado y su interruptor a 840dp de
        // distancia, o sea que había que barrer la pantalla con la vista para saber qué se estaba
        // encendiendo. El tope es [ComponentConfig.ContentMaxWidth], el mismo que las hojas.
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // `widthIn` ANTES de `fillMaxWidth`: el ancho acaba siendo el mínimo entre el
                    // tope y lo disponible, así que en vertical no cambia nada.
                    .widthIn(max = ComponentConfig.ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .safeSidePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                content = content
            )
        }
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
            // Fila entera alterna el switch (ver [SettingsSwitchTile]). `enabled` gobierna también
            // el gesto: deshabilitada, ni el tap de la fila ni el del switch hacen nada.
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
                role = Role.Switch
            )
            .padding(vertical = 6.dp)
    ) {
        // El 0.38 es el alpha de CONTENIDO DESHABILITADO del spec de M3, el mismo que aplican por
        // dentro `Button`, `TextField` y compañía; por eso se escribe aquí en vez de derivar un rol.
        //
        // Es la ÚNICA opacidad que sigue viva sobre un color del tema en toda la UI. M3 usa alpha
        // solo para deshabilitado, state layers y scrims: la jerarquía de texto se hace con ROLES
        // (`onSurface` vs `onSurfaceVariant`), que llevan su contraste medido contra la superficie.
        // Había quince atenuaciones más que sí eran jerarquía —incluidas varias sobre
        // `onSurfaceVariant`, o sea atenuar lo ya atenuado, rompiendo justo la garantía por la que
        // ese rol existe— y se sustituyeron por el rol que les tocaba (`outline` para los glifos
        // decorativos de los estados vacíos, `onSurfaceVariant` a secas para los subtítulos).
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.onSurface
                else AppColors.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) AppColors.onSurfaceVariant
                else AppColors.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(colors = appSwitchColors(), checked = checked, enabled = enabled, onCheckedChange = null)
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
    loading: Boolean = false,
    /**
     * true = la fila NAVEGA a otra pantalla → muestra el chevron ">" (misma pista visual que el hub
     * de Ajustes y que la app de Ajustes de Android). false = acción in-place (reescanear, regenerar
     * colores), donde un chevron mentiría prometiendo una pantalla que no hay.
     */
    navigates: Boolean = false,
    /**
     * Las pasa [SettingsGroup] cuando la fila va dentro de un grupo; suelta, es un bloque con la
     * esquina de bloque en todos sus estados salvo el presionado, que sigue siendo el del spec.
     */
    shapes: ListItemShapes = ListItemDefaults.shapes(
        shape = RoundedCornerShape(SettingsTokens.BlockCorner)
    )
) {
    // Mismo cambio que [SettingsSwitchTile]: `SegmentedListItem` real en vez de `Surface` + `Row`
    // replicando la anatomía del list item, para ganar el morph al presionar.
    //
    // El slot leading se arma en un `val` tipado y no en un `when` de lambdas dentro de la llamada:
    // ahí las llaves de la rama y las de la lambda se confunden a la vista, y el tipo esperado
    // tendría que propagarse a través del `when` para que el plugin de Compose marque el lambda.
    val leading: (@Composable () -> Unit)? = if (loading) {
        { LoadingIndicator(color = AppColors.primary, modifier = Modifier.size(24.dp)) }
    } else if (icon != null) {
        { MaterialSymbol(icon, size = 24.sp) }
    } else {
        null
    }
    val trailing: (@Composable () -> Unit)? = if (navigates) {
        {
            MaterialSymbol(
                "chevron_right",
                size = 24.sp,
                color = AppColors.onSurfaceVariant
            )
        }
    } else {
        null
    }

    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = shapes,
        colors = appSegmentedListItemColors(AppColors.surfaceContainerHigh),
        leadingContent = leading,
        trailingContent = trailing,
        content = { SettingsTileText(title, description) },
        modifier = Modifier.fillMaxWidth()
    )
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
            style = MaterialTheme.typography.bodyMediumEmphasized
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.onSurfaceVariant
        )
        Slider(
            colors = appSliderColors(),
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
