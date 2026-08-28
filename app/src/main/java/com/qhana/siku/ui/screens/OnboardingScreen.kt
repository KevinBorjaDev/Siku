package com.qhana.siku.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.ui.components.safeSidePadding
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.OneDriveSourceCard
import com.qhana.siku.ui.components.rememberOneDriveFolderChanger
import com.qhana.siku.ui.components.PhoneMusicSourceCard
import com.qhana.siku.ui.components.StorageLimitCard
import com.qhana.siku.ui.components.rememberDeviceScanActivator
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appFilledTonalButtonColors
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appTextButtonColors
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import com.qhana.siku.ui.theme.SCREEN_ENTER_MS
import com.qhana.siku.ui.theme.SCREEN_SLIDE_DIVISOR
import com.qhana.siku.ui.theme.ScreenEnterEasing
import androidx.compose.animation.core.tween


/** Música del propio teléfono: dispositivo entero o carpetas. Se puede omitir. */
private const val STEP_LOCAL = 0

/** OneDrive. También se puede omitir, pero no si el paso anterior quedó vacío. */
private const val STEP_CLOUD = 1

/** Tope de descargas. Solo existe si se conectó una fuente de NUBE. */
private const val STEP_STORAGE = 2

/** Notificaciones: explica PARA QUÉ antes de pedirlas. Solo si faltan (Android 13+). */
private const val STEP_NOTIFICATIONS = 3

/**
 * Primer arranque (Fase 4): el usuario conecta las fuentes que quiera —OneDrive, una carpeta
 * local, o ambas— antes de entrar en la biblioteca. Ninguna es obligatoria por separado, pero
 * hace falta al menos una: sin fuentes la biblioteca estaría vacía.
 *
 * **Los pasos son DINÁMICOS**: música del teléfono y nube van en pantallas SEPARADAS (tres
 * tarjetas juntas eran demasiado para lo primero que se ve de la app, y mezclaban "lo que ya
 * tengo aquí" con "conectar una cuenta"), más el tope de descargas (solo con nube) y las
 * notificaciones (solo si faltan). **No hay indicador de progreso**: contar pasos sobre una lista
 * que cambia según el usuario anunciaba pantallas que para muchos no existen, y el flujo es corto.
 *
 * Cada paso de fuente se puede OMITIR por separado, pero no se puede terminar sin ninguna: el
 * botón del último paso de fuentes se bloquea si no se eligió nada en ninguno de los dos.
 *
 * Los pasos son estado INTERNO (no rutas del NavHost): son un flujo cerrado que solo tiene
 * sentido completo, y sacarlos al grafo obligaría a cada ruta a conocer las precondiciones de
 * la otra. El back del sistema retrocede de paso mientras haya paso al que volver.
 *
 * **Nada de sync durante el onboarding**: ni conectar OneDrive ni elegir carpeta lanzan escaneo.
 * El único scan del primer arranque lo dispara [onFinish], ya con las fuentes elegidas y el tope
 * decidido (ver `MusicPlayerScreen` y `SourcesViewModel.addLocalFolder`).
 *
 * Lenguaje M3 Expressive: hero con forma `MaterialShapes` (misma familia que el shape reveal
 * del NowPlaying y el badge de Favoritos), tipografía display y botón Expressive real.
 *
 * El estado de la sesión de OneDrive llega por parámetro desde MainActivity (única instancia de
 * `AuthViewModel`); lo local lo maneja [SourcesViewModel].
 */
// ExperimentalMaterial3ExpressiveApi: los specs del MotionScheme que animan el cambio de paso.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(
    isLoggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    onFinish: () -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel()
) {
    val localFolderUris by viewModel.localFolderUris.collectAsStateWithLifecycle()
    val scanWholeDevice by viewModel.scanWholeDevice.collectAsStateWithLifecycle()
    val oneDriveFolder by viewModel.oneDriveFolderPath.collectAsStateWithLifecycle()

    // "Hay nube" = la sesión de OneDrive. Aquí SÍ se usa la sesión y no el registro de fuentes
    // (`SourcesViewModel.hasCloudSource`, hoy también reactivo) porque son cosas distintas en
    // esta pantalla: lo que gobierna los pasos es la cuenta que el usuario acaba de conectar
    // AQUÍ, y `isLoggedIn` además distingue el "todavía no lo sé" del arranque.
    val hasCloudSource = isLoggedIn

    // Cada paso de fuente se puede omitir, pero terminar sin NINGUNA dejaría una biblioteca
    // vacía sin salida (y `MusicPlayerScreen` devolvería al usuario aquí mismo).
    val hasAnySource = isLoggedIn || scanWholeDevice || localFolderUris.isNotEmpty()

    // Volver directo al paso del tope si una sesión anterior lo dejó pendiente: el usuario ya
    // conectó su nube y lo único que falta es esa decisión. `rememberSaveable` cubre la rotación;
    // el valor inicial cubre la muerte del proceso, que es justo el caso que persiste el flag.
    var step by rememberSaveable {
        mutableIntStateOf(if (hasCloudSource && viewModel.storageStepPending) STEP_STORAGE else STEP_LOCAL)
    }

    // ¿Hace falta el paso de notificaciones? Se decide UNA vez, al entrar: si se reevaluara,
    // conceder el permiso desde ese mismo paso haría desaparecer la pantalla bajo los pies del
    // usuario. Antes de Android 13 el permiso no existe y el paso no aparece nunca.
    val context = LocalContext.current
    val needsNotificationStep = rememberSaveable {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    // Pasos que de verdad aplican a ESTE usuario, en orden. De aquí salen tanto la navegación
    // como la numeración, así que no pueden desincronizarse.
    val activeSteps = buildList {
        add(STEP_LOCAL)
        add(STEP_CLOUD)
        if (hasCloudSource) add(STEP_STORAGE)
        if (needsNotificationStep) add(STEP_NOTIFICATIONS)
    }
    val stepIndex = activeSteps.indexOf(step).coerceAtLeast(0)

    // Conectar la nube deja el tope pendiente HASTA que se pulse el botón final. Si el proceso
    // muere en medio, el próximo arranque vuelve acá en vez de entrar a la biblioteca.
    LaunchedEffect(hasCloudSource) {
        if (hasCloudSource) {
            viewModel.markStorageStepPending()
        } else {
            // Sin nube, el paso del tope no tiene objeto: deja de pender y, si el usuario estaba
            // en él (acaba de desconectar), se vuelve a fuentes.
            viewModel.clearStorageStepPending()
            if (step == STEP_STORAGE) step = STEP_CLOUD
        }
    }
    BackHandler(enabled = stepIndex > 0) { step = activeSteps[stepIndex - 1] }

    // Terminar el onboarding cierra la decisión pendiente, venga del botón del paso que venga.
    val finishOnboarding = {
        viewModel.clearStorageStepPending()
        onFinish()
    }

    // Avanzar al siguiente paso aplicable; si no queda ninguno, este ERA el final.
    val advance = {
        val next = activeSteps.getOrNull(stepIndex + 1)
        if (next != null) step = next else finishOnboarding()
    }

    // Cambiar de paso mueve TODA la región de contenido: es una transición (shared axis), no un
    // componente, así que va con easing + duración del spec y no con springs. Ver Motion.kt.
    // El movimiento va por el token SPATIAL y las opacidades por los de EFFECTS, cada uno con su
    // duración: es como el spec define el shared axis y la razón de que sean tres specs y no uno.
    val forwardSpatial = tween<IntOffset>(SCREEN_ENTER_MS, easing = ScreenEnterEasing)
    val enterFade = tween<Float>(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
    val exitFade = tween<Float>(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)

    AnimatedContent(
        targetState = step,
        // Patrón *forward and backward* del spec, el mismo que el `NavHost`: el botón de "siguiente"
        // navega entre niveles consecutivos del flujo, así que se comporta igual que abrir un
        // detalle. Avanzar entra desde la derecha y retroceder desde la izquierda.
        //
        // Las DOS caras se desplazan el mismo tercio ([SCREEN_SLIDE_DIVISOR], compartido con la
        // navegación) y en el mismo sentido: es lo que lo convierte en un shared axis en vez de una
        // capa tapando a otra. Hasta el 30 jul la saliente solo se DESVANECÍA, sin moverse, así que
        // el eje lo recorría una sola de las dos.
        transitionSpec = {
            val forward = targetState > initialState
            val enterFrom: (Int) -> Int = { w ->
                if (forward) w / SCREEN_SLIDE_DIVISOR else -w / SCREEN_SLIDE_DIVISOR
            }
            val exitTo: (Int) -> Int = { w ->
                if (forward) -w / SCREEN_SLIDE_DIVISOR else w / SCREEN_SLIDE_DIVISOR
            }
            (slideInHorizontally(forwardSpatial, enterFrom) + fadeIn(enterFade))
                .togetherWith(slideOutHorizontally(forwardSpatial, exitTo) + fadeOut(exitFade))
        },
        label = "onboarding_step"
    ) { currentStep ->
        // Posición del paso que se está COMPONIENDO, no del actual: durante la transición
        // conviven ambas pantallas, y con el índice de fuera la saliente resolvería su "atrás"
        // y su "¿soy el último?" con los datos de la entrante mientras se va.
        val index = activeSteps.indexOf(currentStep).coerceAtLeast(0)
        val isLast = index == activeSteps.lastIndex

        when (currentStep) {
            STEP_NOTIFICATIONS -> NotificationsStep(
                hasCloudSource = hasCloudSource,
                onFinish = finishOnboarding,
                onBack = { activeSteps.getOrNull(index - 1)?.let { step = it } }
            )
            STEP_STORAGE -> StorageStep(
                syncViewModel = syncViewModel,
                isLastStep = isLast,
                onBack = { step = STEP_CLOUD },
                onContinue = advance
            )
            STEP_CLOUD -> CloudSourceStep(
                isLoggedIn = isLoggedIn,
                authLoading = authLoading,
                authError = authError,
                // Con nube conectada siempre viene el paso del tope, así que aquí nunca es el
                // final; sin ella, este paso cierra el bloque de fuentes y por eso exige que se
                // haya elegido algo en alguno de los dos.
                hasAnySource = hasAnySource,
                isLastStep = isLast,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                oneDriveFolder = oneDriveFolder,
                // scanNow = false: en el onboarding el escaneo lo dispara "Empezar" con todo ya
                // configurado, igual que al añadir una carpeta local.
                onChangeOneDriveFolder = { viewModel.setOneDriveFolder(it, scanNow = false) },
                onBack = { step = STEP_LOCAL },
                onContinue = advance
            )
            else -> LocalSourcesStep(
                localFolderUris = localFolderUris,
                scanWholeDevice = scanWholeDevice,
                audioPermission = viewModel.audioPermission,
                hasAudioPermission = viewModel::hasAudioPermission,
                onFolderPicked = { viewModel.addLocalFolder(it, scanNow = false) },
                onRemoveFolder = viewModel::removeLocalFolder,
                onScanWholeDeviceChange = { viewModel.setScanWholeDevice(it, scanNow = false) },
                onContinue = advance
            )
        }
    }
}

/**
 * Primer paso: la música que YA está en el teléfono, en sus dos formas. Va antes que la nube
 * porque la app es local-first y porque no exige cuenta, red ni esperar a nada.
 *
 * No pide permisos por adelantado: solo el escaneo del dispositivo necesita uno, y lo pide su
 * propia tarjeta al activarse.
 *
 * Siempre se puede seguir: quien no tenga música en el teléfono continúa a la nube igual, y por
 * eso el botón dice "Siguiente" haya elegido algo o no —ni bloquea ni cambia de etiqueta, así que
 * nada sugiere que falte rellenar algo.
 */
@Composable
private fun LocalSourcesStep(
    localFolderUris: Set<String>,
    scanWholeDevice: Boolean,
    audioPermission: String,
    hasAudioPermission: () -> Boolean,
    onFolderPicked: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onScanWholeDeviceChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val activateDeviceScan = rememberDeviceScanActivator(
        permission = audioPermission,
        hasPermission = hasAudioPermission,
        onActivate = { onScanWholeDeviceChange(true) }
    )

    OnboardingStep(
        icon = "library_music",
        title = stringResource(R.string.onboarding_local_title),
        subtitle = stringResource(R.string.onboarding_local_subtitle)
    ) {
        // Una sola tarjeta con las dos formas de la música local (escanear todo · añadir carpeta):
        // en Ajustes van separadas, pero para el primer arranque son una única decisión.
        PhoneMusicSourceCard(
            scanWholeDevice = scanWholeDevice,
            folderUris = localFolderUris,
            onScanWholeDevice = activateDeviceScan,
            onDisableScan = { onScanWholeDeviceChange(false) },
            onFolderPicked = onFolderPicked,
            onRemoveFolder = onRemoveFolder
        )

        // Aire mínimo con la tarjeta y, a continuación, todo el sitio que sobre: el botón cae
        // al borde inferior porque el paso mide lo que mide la pantalla (ver [OnboardingStep]).
        // Dos spacers y no uno con `heightIn`: un hijo con peso se mide con alto EXACTO, así que
        // un mínimo puesto encima no sobreviviría cuando no quedara sitio, que es justo el caso
        // en el que hace falta.
        Spacer(modifier = Modifier.height(36.dp))
        Spacer(modifier = Modifier.weight(1f))

        // Sin "volver": es el primer paso. Y nunca es el último (detrás viene siempre la nube),
        // así que avanzar aquí es siempre la flecha.
        OnboardingStepNav(onContinue = onContinue, isLastStep = false)
    }
}

/**
 * Segundo paso: OneDrive, también opcional.
 *
 * Es el que cierra el bloque de fuentes, así que aquí sí se exige haber elegido ALGO —en este
 * paso o en el anterior—: terminar sin ninguna fuente dejaría una biblioteca vacía y
 * `MusicPlayerScreen` devolvería al usuario a este mismo onboarding.
 */
@Composable
private fun CloudSourceStep(
    isLoggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    hasAnySource: Boolean,
    isLastStep: Boolean,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    oneDriveFolder: String,
    onChangeOneDriveFolder: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Sin advertencia: en el onboarding no hay biblioteca que perder todavía.
    val changeFolder = rememberOneDriveFolderChanger(warnBeforeChange = false, onApply = onChangeOneDriveFolder)

    OnboardingStep(
        icon = "cloud",
        title = stringResource(R.string.onboarding_cloud_title),
        subtitle = stringResource(R.string.onboarding_cloud_subtitle)
    ) {
        OneDriveSourceCard(
            isConnected = isLoggedIn,
            isLoading = authLoading,
            onConnect = { activity?.let(onConnectOneDrive) },
            onDisconnect = onDisconnectOneDrive,
            folderPath = oneDriveFolder,
            // Solo tiene sentido elegir carpeta con la cuenta ya conectada: el explorador
            // necesita el token para listar nada.
            onChangeFolder = if (isLoggedIn) changeFolder else null
        )

        // El aviso de "falta una fuente" va pegado a la TARJETA y no al botón: lo que hay que
        // hacer se hace aquí arriba (conectar) o volviendo al paso anterior (carpetas), así que
        // se lee donde se actúa. Bajo el botón describía un control apagado sin decir por qué.
        if (!hasAnySource) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onboarding_need_source),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (authError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            AppSurface(
                color = AppColors.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    MaterialSymbol("error", color = AppColors.onErrorContainer, size = 20.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_error_format, authError),
                        color = AppColors.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Aire mínimo y el resto del alto libre: la fila inferior se apoya en el borde de la
        // pantalla porque el paso mide lo que mide el dispositivo (ver [OnboardingStep]).
        Spacer(modifier = Modifier.height(36.dp))
        Spacer(modifier = Modifier.weight(1f))

        OnboardingStepNav(
            onBack = onBack,
            onContinue = onContinue,
            // "Empezar" manda sobre todo lo demás: si este es el final, avanzar no es navegar y
            // por eso lleva palabra (ver [OnboardingStepNav]). Con nube conectada nunca lo es,
            // porque detrás viene el tope de descargas.
            isLastStep = isLastStep,
            continueEnabled = hasAnySource
        )
    }
}

/** Paso solo con nube: cuánto espacio puede ocupar el audio descargado. */
@Composable
private fun StorageStep(
    syncViewModel: SyncViewModel,
    isLastStep: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    // El tope se persiste en DataStore, así que esta instancia de ViewModel (propia del
    // backstack entry) y la de Ajustes leen y escriben exactamente el mismo valor.
    val storageLimitGb by syncViewModel.storageLimitGb.collectAsStateWithLifecycle()

    OnboardingStep(
        icon = "storage",
        title = stringResource(R.string.onboarding_storage_title),
        subtitle = stringResource(R.string.onboarding_storage_subtitle)
    ) {
        StorageLimitCard(
            limitGb = storageLimitGb,
            onLimitChangeFinished = syncViewModel::setStorageLimitGb,
            // El hero dice de qué va la pantalla (el disco) y la tarjeta, qué lo llena.
            icon = "download"
        )

        Spacer(modifier = Modifier.height(36.dp))
        Spacer(modifier = Modifier.weight(1f))

        // Volver lleva a fuentes, el mismo destino que el back del sistema: visible para quien no
        // usa gestos y para quien llega aquí y se acuerda de que le falta la carpeta local.
        OnboardingStepNav(onBack = onBack, onContinue = onContinue, isLastStep = isLastStep)
    }
}

/**
 * Último paso: explica PARA QUÉ sirven las notificaciones antes de que aparezca el diálogo del
 * sistema, que por sí solo no dice nada ("¿Permitir notificaciones?" invita a decir que no).
 *
 * Son dos usos distintos y conviene separarlos, porque el primero sorprende: la notificación del
 * reproductor NO es cosa de la nube — es la que trae los controles a la barra de estado y a la
 * pantalla de bloqueo, y la quiere igual quien solo escucha música del teléfono. El aviso de
 * progreso sí es de la nube, y por eso solo se menciona si hay una conectada.
 *
 * Se puede saltar: el permiso no condiciona nada de la app, así que negarlo (o posponerlo) tiene
 * que dejar terminar el onboarding igual. Y al revés, CONCEDERLO tampoco termina el onboarding:
 * el botón está dentro de la tarjeta y su trabajo es ese permiso; de salir se encarga "Empezar".
 */
@Composable
private fun NotificationsStep(
    hasCloudSource: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    // Conceder el permiso NO termina el onboarding: el botón vive DENTRO de la tarjeta, así que
    // su acción es "dar este permiso" y no "entrar en la app" —y menos aún cuando esta pantalla
    // pueda listar un segundo permiso—. Lo que hace la respuesta del sistema es marcar la
    // tarjeta; salir sigue siendo cosa de la fila de abajo, la haya concedido o no.
    var granted by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    OnboardingStep(
        // "shield" y no la campana: el paso ya no va de notificaciones sino de PERMISOS, y quien
        // nombra el permiso concreto es la tarjeta.
        icon = "shield",
        title = stringResource(R.string.onboarding_notifications_title),
        subtitle = stringResource(R.string.onboarding_notifications_subtitle)
    ) {
        // El permiso, su explicación y el botón que lo pide van en UNA tarjeta: con el título de
        // la pantalla ya genérico, es la tarjeta la que tiene que decir de qué permiso se habla, y
        // el botón tiene que estar donde está esa explicación. Es además la forma en la que esto
        // crece bien si algún día hay un segundo permiso.
        NotificationPermissionCard(
            hasCloudSource = hasCloudSource,
            granted = granted,
            onAllow = {
                // TIRAMISU garantizado: sin él este paso ni siquiera está en la lista.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_notif_note),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))
        Spacer(modifier = Modifier.weight(1f))

        // El único que termina el onboarding, se haya concedido el permiso o no: ninguno de los
        // dos condiciona nada de la app.
        OnboardingStepNav(onBack = onBack, onContinue = onFinish, isLastStep = true)
    }
}

/**
 * Tarjeta del permiso de notificaciones: qué es, para qué lo usa la app y el botón que lo pide.
 *
 * Los dos usos van dentro y separados porque el primero SORPRENDE: la notificación del reproductor
 * no es cosa de la nube —es la que trae los controles a la barra de estado y a la pantalla de
 * bloqueo— y la quiere igual quien solo escucha música del teléfono. El de progreso sí es de la
 * nube, y por eso solo aparece si hay una conectada.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationPermissionCard(
    hasCloudSource: Boolean,
    granted: Boolean,
    onAllow: () -> Unit
) {
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Misma cabecera que las tarjetas de fuente (icono de 48 en tonal + título y línea de
            // apoyo), para que "una tarjeta del onboarding" se lea siempre igual.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Concedido = acento pleno y glifo relleno, el mismo par que usan las tarjetas
                // de fuente para decir "esto ya está".
                AppSurface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (granted) AppColors.primaryContainer else AppColors.secondaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MaterialSymbol(
                            icon = "notifications_active",
                            size = 26.sp,
                            fill = granted,
                            color = if (granted) AppColors.onPrimaryContainer
                            else AppColors.onSecondaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_notif_card_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.onboarding_notif_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.onSurfaceVariant
                    )
                }

                if (granted) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MaterialSymbol(
                        icon = "check_circle",
                        size = 22.sp,
                        fill = true,
                        color = AppColors.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            NotificationUseRow(
                icon = "play_circle",
                title = stringResource(R.string.onboarding_notif_player_title),
                description = stringResource(R.string.onboarding_notif_player_desc)
            )

            if (hasCloudSource) {
                Spacer(modifier = Modifier.height(12.dp))
                NotificationUseRow(
                    icon = "cloud_download",
                    title = stringResource(R.string.onboarding_notif_sync_title),
                    description = stringResource(R.string.onboarding_notif_sync_desc)
                )
            }

            // Concedido, el botón DESAPARECE en vez de quedarse apagado: no hay nada que volver
            // a pedir —el permiso solo se retira desde los ajustes del sistema, que es lo que
            // dice la nota de debajo— y el check de la cabecera ya lo cuenta.
            if (!granted) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Text button, como la acción de las tarjetas de fuente: el énfasis pleno de
                    // la pantalla lo tiene la fila de navegación.
                    TextButton(
                        colors = appTextButtonColors(),
                        onClick = onAllow,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.onboarding_notif_allow))
                    }
                }
            }
        }
    }
}

/**
 * Una fila "icono + qué hace" DENTRO de la tarjeta del permiso. Va plana, sin superficie propia:
 * un contenedor del mismo color dentro de la tarjeta no se vería, y la tarjeta ya es el recuadro.
 */
@Composable
private fun NotificationUseRow(icon: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MaterialSymbol(
            icon = icon,
            size = 22.sp,
            fill = true,
            color = AppColors.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.onSurfaceVariant
            )
        }
    }
}

/**
 * Andamiaje común de un paso: hero con forma Expressive, título, subtítulo y el contenido propio
 * del paso. Los pasos solo aportan lo que cambia.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingStep(
    icon: String,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // El paso reserva EL ALTO DEL DISPOSITIVO (`heightIn(min = maxHeight)`), y eso es lo que
    // permite que un paso ancle su botón al borde inferior con un `weight` en vez de dejarlo
    // flotando a media pantalla. Dentro de un `verticalScroll` el alto MÁXIMO es infinito, así
    // que `Column` reparte los pesos sobre el alto MÍNIMO (`RowColumnMeasurePolicy`: con
    // `mainAxisMax` infinito el objetivo pasa a ser `mainAxisMin`) — sin ese mínimo, un hijo
    // con peso mediría cero. Y sigue siendo scroll: si el contenido no cabe, el mínimo no
    // estorba.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // `systemBarsPadding` cubre las barras pero NO la perforación, que al girar cae en un
            // costado: sin esto el texto del paso se mete debajo.
            .safeSidePadding()
    ) {
        Column(
            modifier = Modifier
                // Tope de ancho: girado, el título y el subtítulo se centraban sobre líneas de
                // ~825dp. Es el mismo tope que el resto del contenido y en vertical no toca nada.
                .align(Alignment.TopCenter)
                .widthIn(max = ComponentConfig.ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Hero: icono sobre una cookie Expressive (la misma familia de formas que el shape
            // reveal de la carátula del NowPlaying — es lo primero que se ve de la app).
            AppSurface(
                shape = MaterialShapes.Cookie12Sided.toShape(),
                color = AppColors.primaryContainer,
                modifier = Modifier.size(120.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MaterialSymbol(
                        icon = icon,
                        color = AppColors.onPrimaryContainer,
                        size = 56.sp,
                        opticalSize = 48,
                        fill = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.displaySmallEmphasized,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            content()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Fila inferior de un paso: volver a la izquierda, avanzar a la derecha.
 *
 * **Los dos son TONALES y del mismo tamaño**: son las dos direcciones del mismo flujo, no una
 * acción y su nota al pie, así que lo que las distingue es la POSICIÓN y no el énfasis. Se
 * probaron en device y se descartaron el "volver" a todo el ancho debajo del botón (le daba el
 * mismo peso que a la acción que mueve el onboarding) y el par con etiqueta.
 *
 * **Navegar va en ICONO; TERMINAR va con palabra.** Una flecha dice "hay otra pantalla", que es
 * justo lo que el último paso no debe prometer: ahí el botón de la derecha es "Empezar" y se lee.
 * El tamaño MEDIUM del icon button (56dp de contenedor, glifo de 24) coincide con el
 * `MediumContainerHeight` del botón con etiqueta, así que el cambio de forma no mueve la fila.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingStepNav(
    onContinue: () -> Unit,
    isLastStep: Boolean,
    onBack: (() -> Unit)? = null,
    continueEnabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // Sin "volver" (primer paso) el avance se queda solo, y su sitio sigue siendo la derecha.
        horizontalArrangement = if (onBack != null) Arrangement.SpaceBetween else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            OnboardingNavIconButton(icon = "arrow_back", onClick = onBack)
        }

        if (isLastStep) {
            OnboardingStepButton(
                label = stringResource(R.string.onboarding_start),
                enabled = continueEnabled,
                onClick = onContinue
            )
        } else {
            OnboardingNavIconButton(
                icon = "arrow_forward",
                onClick = onContinue,
                enabled = continueEnabled
            )
        }
    }
}

/** Una de las dos flechas de [OnboardingStepNav]: icon button tonal de tamaño MEDIUM. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingNavIconButton(
    icon: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = appFilledTonalIconButtonColors(),
        // Shape-morph al presionar, como el botón con etiqueta: las dos formas salen del tamaño
        // medium, no de un dp escrito a mano.
        shapes = IconButtonDefaults.shapes(
            shape = IconButtonDefaults.mediumRoundShape,
            pressedShape = IconButtonDefaults.mediumPressedShape
        ),
        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize())
    ) {
        // `mediumIconSize` es Dp y `MaterialSymbol` mide en sp (convención de la app: sus glifos
        // escalan con la tipografía del sistema). El número es el mismo.
        MaterialSymbol(icon, size = IconButtonDefaults.mediumIconSize.value.sp)
    }
}

/**
 * El botón CON PALABRA de [OnboardingStepNav]: el que termina el onboarding. Tonal, como las dos
 * flechas, y al ancho de su etiqueta.
 *
 * **Es un botón de tamaño MEDIUM del spec, no un botón normal estirado a un alto inventado.**
 * M3 Expressive define cinco tamaños (32 / 40 / 56 / 96 / 136 dp) y de cada uno DERIVA su forma,
 * su forma al presionar y su padding; aquí había un `.height(60.dp)` a mano, que no es ninguno de
 * los cinco y dejaba el resto de las medidas en las del tamaño por defecto (40dp) dentro de una
 * caja más alta. Ahora el alto es el único valor que se elige y `shapesFor` / `contentPaddingFor`
 * sacan de él todo lo demás — el mismo 56dp que el contenedor medium de las flechas.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingStepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val height = ButtonDefaults.MediumContainerHeight
    FilledTonalButton(
        colors = appFilledTonalButtonColors(),
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.heightIn(min = height)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMediumEmphasized
        )
    }
}
