package com.qhana.siku.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.repository.FailedDownload
import com.qhana.siku.ui.components.SafeBarInsets
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.DownloadStateBanner
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appTopAppBarColors
import com.qhana.siku.ui.viewmodel.StorageUsage
import com.qhana.siku.ui.viewmodel.SyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import com.qhana.siku.ui.theme.SCREEN_ENTER_MS
import com.qhana.siku.ui.theme.ScreenEnterEasing
import androidx.compose.animation.core.tween

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadManagerScreen(
    onBackClick: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val failedDownloads by viewModel.failedDownloads.collectAsStateWithLifecycle()
    val controlState by viewModel.downloadControlState.collectAsStateWithLifecycle()
    val downloadBanner by viewModel.downloadBanner.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.download_tab_active, activeDownloads.size),
        stringResource(R.string.download_tab_failed, failedDownloads.size)
    )

    // Resueltos AQUÍ y no en el sitio de uso: el `content` de `AppBarRow` es un
    // `AppBarRowScope.() -> Unit` normal, no un `@Composable`, así que dentro no se puede llamar a
    // `stringResource` (mismo trato que ya recibe `labelFor` en `ConnectedChoiceGroup`).
    val pauseLabel = stringResource(R.string.download_pause)
    val stopLabel = stringResource(R.string.download_stop)
    val resumeLabel = stringResource(R.string.download_resume)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        // Fondo de página: `surface` (M3 spec), el rol de toda pantalla principal — ver `SettingsScaffold`.
        containerColor = AppColors.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Barra de UNA fila. La `Medium` que había es de dos y su fila alta existe para dar
            // aire a un título largo o a un subtítulo: aquí el título es una palabra y el subtitle
            // está descartado a propósito (el progreso de la cola vive en la tarjeta de resumen,
            // con su barra y el consumo frente al tope; repetirlo arriba era el mismo dato dos
            // veces). Sin nada que poner en esa fila, solo comía alto en una pantalla que es una
            // lista. Sigue siendo la de M3 Expressive —`SingleRowTopAppBar` por dentro, con el alto
            // del token y no fijo—; lo que cambia es el número de filas.
            TopAppBar(
                title = { Text(stringResource(R.string.download_title)) },
                // EXPLÍCITOS o la barra los pide al tema BASE (el del wallpaper). El par tabulado
                // sin override: `surface` en reposo —invisible, porque ES la página— y
                // `surfaceContainer` cuando el contenido le pasa por debajo. Estuvo en
                // `chromeSurface` fijo mientras existió la horquilla.
                colors = appTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                actions = {
                    // `AppBarRow` en vez de los `IconButton` sueltos: es el contenedor de acciones
                    // del spec, y lo que aporta es que el `label` de cada ítem hace DOS trabajos
                    // —contentDescription del icono y texto del menú de overflow— en vez del
                    // `Modifier.semantics { contentDescription = … }` a mano que había por acción.
                    // Con dos iconos no habrá overflow hoy; lo habrá solo si crece la botonera o
                    // con una escala de fuente alta, y entonces se resuelve solo.
                    AppBarRow(
                        overflowIndicator = { menuState ->
                            IconButton(onClick = { menuState.show() }) {
                                MaterialSymbol("more_vert")
                            }
                        }
                    ) {
                        // Pausar/Reanudar según el estado; Detener solo cuando está activo.
                        if (controlState == com.qhana.siku.data.model.DownloadControlState.ACTIVE) {
                            clickableItem(
                                onClick = { viewModel.pauseDownloads() },
                                icon = { MaterialSymbol("pause") },
                                label = pauseLabel
                            )
                            clickableItem(
                                onClick = { viewModel.stopDownloads() },
                                icon = { MaterialSymbol("stop") },
                                label = stopLabel
                            )
                        } else {
                            clickableItem(
                                onClick = { viewModel.resumeDownloads() },
                                icon = { MaterialSymbol("play_arrow") },
                                label = resumeLabel
                            )
                        }
                    }
                },
                // El default de la barra (`systemBarsForVisualComponents`) IGNORA la perforación,
                // que en horizontal cae en un costado: sin esto la flecha de volver queda debajo.
                windowInsets = SafeBarInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        // Girado, esta pantalla repartía dos pestañas y unas filas de descarga entre ~873dp. Se
        // acota al mismo ancho que las hojas ([ComponentConfig.ContentMaxWidth]) y se centra; en
        // vertical el tope no toca nada. `safeSidePadding` repone la perforación, que el default
        // del Scaffold no incluye.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .safeSidePadding()
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
        ) {
            // Aviso de pausa/detenido (persistente, mismo banner que el home): explica por qué
            // no se descarga y ofrece reanudar / cancelar.
            downloadBanner?.let { banner ->
                DownloadStateBanner(
                    control = banner.control,
                    pending = banner.pending,
                    onResume = { viewModel.resumeDownloads() },
                    onDismiss = { viewModel.dismissDownloadBanner() },
                    onMute = { viewModel.muteDownloadBanner() }
                )
            }

            // PESTAÑAS REALES, no un connected button group. "Activas" y "Fallidas" no son dos
            // valores de un ajuste: son dos regiones de contenido entre las que se NAVEGA, y para
            // eso el componente es `PrimaryTabRow` — con él llegan la semántica de pestaña, el
            // indicador que se desplaza y el ancho repartido, que un grupo de selección no da.
            // Es el mismo cambio que ya se hizo en la biblioteca, donde un `ButtonGroup` estaba
            // haciendo de navegación entre las cinco secciones.
            //
            // Fija y no scrollable, al revés que la de la biblioteca: aquí son dos pestañas con
            // etiqueta de texto (nunca solo glifo), así que repartir el ancho a partes iguales es
            // exactamente lo que se quiere.
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                // **Los colores van EXPLÍCITOS, también los del contenedor y el INDICADOR.** Los
                // `Tab` de abajo ya los pasaban, pero la fila no, y sus defaults salen de
                // `TabRowDefaults` → `MaterialTheme.colorScheme`, que desde la migración a
                // [AppColors] (20 ago 2026) NO lleva el color de la carátula: el indicador de la
                // pestaña activa se quedaba con el acento base mientras el resto de la pantalla iba
                // teñido. Es el modo de fallo que describe CLAUDE.md — "un componente al que no se
                // le pasan colores no los recibe: los va a buscar al tema".
                //
                // Los valores son los de los tokens (`PrimaryNavigationTabTokens`: contenedor
                // `Surface`, indicador y etiqueta activa `Primary`), leídos del jar y traducidos a
                // [AppColors]; del indicador solo se sobrescribe el COLOR, porque su alto (3dp) y su
                // forma son constantes del token y no dependen del esquema.
                containerColor = AppColors.contentSurface,
                contentColor = AppColors.primary,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                        width = Dp.Unspecified,
                        color = AppColors.primary
                    )
                },
                // El divisor del default marca un borde a todo lo ancho bajo la fila; aquí debajo
                // viene contenido que ya trae sus propias tarjetas y el corte sobraba.
                divider = {}
            ) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onClick = { selectedTabIndex = index },
                        // EXPLÍCITOS los dos: el default de `Tab` es
                        // `unselectedContentColor = selectedContentColor`, o sea que sin esto las
                        // inactivas se pintan igual que la activa y la fila deja de decir dónde
                        // estás. Mismo cuidado que en la fila de la biblioteca.
                        selectedContentColor = AppColors.primary,
                        unselectedContentColor = AppColors.onSurfaceVariant,
                        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // Cambiar de pestaña reemplaza toda la región: transición (shared axis), así que
                // easing + duración del spec y no springs. Antes iba con los defaults de
                // compose-animation, que no leen nada del tema.
                //
                // El eje es HORIZONTAL desde que la fila es un `PrimaryTabRow` real: las pestañas
                // están una al lado de la otra y su indicador se desplaza en horizontal, así que el
                // contenido tiene que acompañar ese movimiento. Era vertical cuando el control era
                // un connected button group, que no sugiere dirección — con tabs, deslizar hacia
                // arriba lo que el indicador acaba de mover hacia la derecha se lee al revés.
                val tabSlide = tween<IntOffset>(SCREEN_ENTER_MS, easing = ScreenEnterEasing)
                val tabFadeIn = tween<Float>(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
                val tabFadeOut = tween<Float>(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)
                AnimatedContent(
                    targetState = selectedTabIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tabSlide) { width -> width } + fadeIn(tabFadeIn)) togetherWith
                                    (slideOutHorizontally(tabSlide) { width -> -width } + fadeOut(tabFadeOut))
                        } else {
                            (slideInHorizontally(tabSlide) { width -> -width } + fadeIn(tabFadeIn)) togetherWith
                                    (slideOutHorizontally(tabSlide) { width -> width } + fadeOut(tabFadeOut))
                        }
                    },
                    label = "TabTransition"
                ) { targetIndex ->
                    when (targetIndex) {
                        0 -> ActiveDownloadsTab(syncStatus, activeDownloads, storageUsage)
                        1 -> FailedDownloadsTab(
                            failedDownloads = failedDownloads,
                            onRetryAll = { viewModel.retryFailedDownloads() },
                            onRetryOne = { songId -> viewModel.retryDownload(songId) }
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Resumen ÚNICO de la pestaña Activas: progreso de la cola (si hay descarga en curso) y consumo
 * frente al tope, en la misma tarjeta.
 *
 * **PERSISTENTE a propósito.** Antes la tarjeta entera colgaba de `syncStatus is Downloading`, y
 * ese estado solo lo publica el pipeline mientras corre: al terminar el sync queda en `Complete`
 * (nunca vuelve a `Idle`), así que abrir el gestor con la cola ya drenada —o pausada, o esperando
 * WiFi— dejaba la pantalla sin ningún resumen. El progreso de cola sigue siendo condicional (sin
 * descarga no hay nada que medir), pero el almacenamiento se ve siempre.
 *
 * Los indicadores son LINEALES, así que van con `LinearWavyProgressIndicator` — criterio unificado
 * de la app (los circulares de descarga usan `LoadingIndicator`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverallProgressCard(syncStatus: SyncStatus, usage: StorageUsage?) {
    val downloading = syncStatus as? SyncStatus.Downloading
    // Una biblioteca 100% local no descarga nada: sin cola ni bytes ni tope no hay nada que decir.
    val hasStorageInfo = usage != null && (usage.usedBytes > 0L || usage.hasCap)
    if (downloading == null && !hasStorageInfo) return

    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialSymbol("cloud_download", color = AppColors.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.download_global_progress),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (downloading != null) {
                Spacer(modifier = Modifier.height(12.dp))
                // Onda expressive determinada: MISMO indicador que los banners de progreso de la
                // biblioteca (antes aquí era una barra plana clásica).
                LinearWavyProgressIndicator(
                    progress = {
                        if (downloading.total > 0) downloading.current.toFloat() / downloading.total.toFloat()
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppColors.primary,
                    trackColor = AppColors.surfaceContainerHighest
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.download_completed_progress,
                        downloading.current,
                        downloading.total
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.onSurfaceVariant
                )
            }

            if (usage != null && hasStorageInfo) {
                if (downloading != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = AppColors.outlineVariant)
                }
                Spacer(modifier = Modifier.height(16.dp))

                val used = Formatter.formatShortFileSize(context, usage.usedBytes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.download_storage_section),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (usage.hasCap) {
                            stringResource(
                                R.string.download_storage_used_of,
                                used,
                                Formatter.formatShortFileSize(context, usage.capBytes)
                            )
                        } else {
                            stringResource(R.string.download_storage_used_only, used)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.onSurfaceVariant
                    )
                }

                if (usage.hasCap) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearWavyProgressIndicator(
                        progress = { usage.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.primary,
                        trackColor = AppColors.surfaceContainerHighest
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Por encima del tope no se dice "quedan -2 GB": se nombra el exceso, que es
                    // lo que el desalojo LRU irá liberando.
                    val remaining = usage.remainingBytes
                    Text(
                        text = if (remaining >= 0L) {
                            stringResource(
                                R.string.download_storage_remaining,
                                Formatter.formatShortFileSize(context, remaining)
                            )
                        } else {
                            stringResource(
                                R.string.download_storage_over_cap,
                                Formatter.formatShortFileSize(context, -remaining)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// `LoadingIndicator` pasó a exigir opt-in explícito en material3 1.5.0-alpha24.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActiveDownloadsTab(
    syncStatus: SyncStatus,
    activeDownloads: List<com.qhana.siku.data.coordinator.ActiveDownload>,
    storageUsage: StorageUsage?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        // Key estable y item SIEMPRE presente: antes la tarjeta se insertaba en la posición 0 solo
        // al empezar a descargar, y LazyColumn ancla el viewport al ítem visible — así que el
        // nuevo item 0 nacía POR ENCIMA del scroll y había que subir a mano para verlo. Estando
        // siempre, al entrar se ve sin tocar nada (y sale de vista solo si el usuario scrollea).
        item(key = "overall_progress") { OverallProgressCard(syncStatus, storageUsage) }

        if (activeDownloads.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp), 
                    contentAlignment = Alignment.Center
                ) {
                    if (syncStatus is SyncStatus.Downloading || syncStatus is SyncStatus.Scanning ||
                        syncStatus is SyncStatus.Preparing
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(color = AppColors.primary, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.download_syncing), color = AppColors.onSurfaceVariant)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Sin atenuar, al revés que los glifos de estado VACÍO (que van en `outline`): este
                            // anuncia un éxito —"todo al día"— y apagarlo lo dejaba pareciendo deshabilitado.
                            MaterialSymbol("check_circle", size = 64.sp, color = AppColors.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.download_up_to_date), style = MaterialTheme.typography.titleMedium, color = AppColors.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            // Updated List using rememberListItemShape
            itemsIndexed(activeDownloads, key = { _, item -> item.song.id }) { index, download ->
                val uiModel = remember(download.song) { download.song.toUiModel() }
                
                // Forma dinámica basada en posición
                val shape = rememberListItemShape(index = index, count = activeDownloads.size)

                AppSurface(
                    shape = shape,
                    color = AppColors.surfaceContainerHigh,
                    // Esta lista se vacía sola conforme terminan las descargas: es donde el
                    // jump-cut más se notaba.
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 1.dp)
                ) {
                                                    SongItem(
                                                        song = uiModel,
                                                        isPlaying = false,
                                                        isDownloading = true, // Enable for AlbumArt spinner
                                                        downloadProgress = download.progress,
                                                        showDuration = false,
                                                        showStatusIcon = false, // Keep side spinner hidden
                                                        useRingProgress = true, // aro de progreso alrededor de la carátula
                                                        trailingContent = {                            Text(
                                "${(download.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmallEmphasized,
                                color = AppColors.primary
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FailedDownloadsTab(
    failedDownloads: List<FailedDownload>,
    onRetryAll: () -> Unit,
    onRetryOne: (String) -> Unit
) {
    if (failedDownloads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaterialSymbol("check", size = 64.sp, color = AppColors.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.download_no_errors), color = AppColors.onSurfaceVariant)
            }
        }
    } else {
        Column {
            Button(
                colors = appButtonColors(),
                onClick = onRetryAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                MaterialSymbol("refresh")
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.download_retry_all))
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(failedDownloads, key = { _, item -> item.song.id }) { index, failed ->
                    val shape = rememberListItemShape(index = index, count = failedDownloads.size)

                    AppSurface(
                        shape = shape,
                        color = AppColors.surfaceContainerHigh,
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        Column {
                            SongItem(
                                song = failed.song.toUiModel(),
                                isPlaying = false,
                                showStatusIcon = false,
                                trailingContent = {
                                    val retryDesc = stringResource(R.string.download_retry_one)
                                    FilledTonalIconButton(
                                        colors = appFilledTonalIconButtonColors(),
                                        onClick = { onRetryOne(failed.song.id) },
                                        shapes = IconButtonDefaults.shapes(),
                                        modifier = Modifier
                                            .size(40.dp)
                                            .semantics { contentDescription = retryDesc }
                                    ) {
                                        MaterialSymbol("refresh", size = 20.sp)
                                    }
                                }
                            )
                            FailedDownloadCause(failed)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diagnóstico de la fila fallida, alineado con el texto del SongItem de arriba:
 * clase de error + causa cruda, y la hora del próximo reintento automático si hay backoff.
 */
@Composable
private fun FailedDownloadCause(failed: FailedDownload) {
    // 12 (padding fila) + 56 (carátula) + 16 (gap) = misma columna de texto que el SongItem.
    val textIndent = 12.dp + ComponentConfig.SongItemIconSize + 16.dp
    val now = System.currentTimeMillis()

    Column(modifier = Modifier.padding(start = textIndent, end = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbol(
                if (failed.isTransient) "schedule" else "error",
                size = 14.sp,
                color = AppColors.error
            )
            Spacer(modifier = Modifier.width(6.dp))
            val kindLabel = stringResource(
                if (failed.isTransient) R.string.download_error_transient else R.string.download_error_permanent
            )
            val attempts = pluralStringResource(R.plurals.download_attempts, failed.attempts, failed.attempts)
            Text(
                text = "$kindLabel · $attempts",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.error
            )
        }
        val retryAt = failed.nextRetryAt?.takeIf { it > now }
        val retryText = if (retryAt != null) {
            val time = remember(retryAt) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(retryAt))
            }
            stringResource(R.string.download_retry_scheduled, time)
        } else null
        val detail = listOfNotNull(failed.error, retryText).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}