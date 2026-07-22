package com.qhana.siku.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.ui.components.LocalSourceCard
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.OneDriveSourceCard
import com.qhana.siku.ui.viewmodel.SourcesViewModel

/**
 * Primer arranque (Fase 4): el usuario conecta las fuentes que quiera —OneDrive, una carpeta
 * local, o ambas— antes de entrar en la biblioteca. Ninguna es obligatoria por separado, pero
 * hace falta al menos una: sin fuentes la biblioteca estaría vacía.
 *
 * Una sola pantalla en vez de un pager por pasos: las dos fuentes son independientes y se
 * configuran en cualquier orden, así que enseñarlas juntas evita navegación innecesaria y deja
 * ver de un vistazo qué queda por conectar.
 *
 * Lenguaje M3 Expressive: hero con forma `MaterialShapes` (misma familia que el shape reveal
 * del NowPlaying y el badge de Favoritos), tipografía display y botón Expressive real.
 *
 * El estado de la sesión de OneDrive llega por parámetro desde MainActivity (única instancia de
 * `AuthViewModel`); lo local lo maneja [SourcesViewModel].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(
    isLoggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    onFinish: () -> Unit,
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val localFolderUri by viewModel.localFolderUri.collectAsStateWithLifecycle()

    val hasAnySource = isLoggedIn || localFolderUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Hero: nota musical sobre una cookie Expressive (la misma familia de formas que el
        // shape reveal de la carátula del NowPlaying — es lo primero que se ve de la app).
        Surface(
            shape = MaterialShapes.Cookie12Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                MaterialSymbol(
                    icon = "library_music",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 56.sp,
                    opticalSize = 48,
                    fill = true
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        OneDriveSourceCard(
            isConnected = isLoggedIn,
            isLoading = authLoading,
            onConnect = { activity?.let(onConnectOneDrive) },
            onDisconnect = onDisconnectOneDrive
        )

        Spacer(modifier = Modifier.height(12.dp))

        LocalSourceCard(
            folderUri = localFolderUri,
            onFolderPicked = viewModel::setLocalFolder,
            onRemoveFolder = viewModel::clearLocalFolder
        )

        if (authError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    MaterialSymbol("error", color = MaterialTheme.colorScheme.onErrorContainer, size = 20.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_error_format, authError),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Botón Expressive REAL (shape-morph al presionar), como los de las pantallas de detalle.
        Button(
            onClick = onFinish,
            enabled = hasAnySource,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_start),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.width(8.dp))
            MaterialSymbol("arrow_forward", size = 22.sp, color = MaterialTheme.colorScheme.onPrimary)
        }

        if (!hasAnySource) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.onboarding_need_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
