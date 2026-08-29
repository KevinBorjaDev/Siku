package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.ui.theme.AppSurface

/**
 * Banner reutilizable del estado de las descargas cuando NO están activas (pausadas o
 * detenidas) y quedan pendientes. Se usa igual en el Download Manager y en el home (dentro
 * del slot de banners de la biblioteca), por eso replica el estilo de los demás banners de
 * la biblioteca: tarjeta 24dp, icono en círculo, paleta SEMÁNTICA FIJA (ámbar = atención, no
 * el scheme seedeado del álbum que suele quedar gris) y márgenes `top=12/bottom=0` (el hueco
 * inferior lo pone el contentPadding de la lista, así queda a 12/12 de header y contenido).
 *
 * Cierres del aviso (ambos sin reanudar):
 * - [onDismiss] (X): cierra ESTE aviso; reaparece ante la próxima pausa/detención.
 * - [onMute] ("No volver a mostrar"): lo silencia para siempre.
 */
@Composable
fun DownloadStateBanner(
    control: DownloadControlState,
    pending: Int,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onMute: (() -> Unit)? = null
) {
    val stopped = control == DownloadControlState.STOPPED
    // La MISMA paleta que el banner de "en pausa" de la biblioteca, no una copia: aquí vivían sus
    // dos colores escritos a mano, así que teñir los banners el 29 ago 2026 habría dejado este
    // ámbar solo, con el mismo significado y otro color. Ver [BannerPalette].
    val palette = BannerAmber

    AppSurface(
        color = palette.container,
        contentColor = palette.onContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Glifo suelto y caja de 40dp, igual que `BannerCard`: con el contenedor teñido, un
            // círculo de acento detrás sería una segunda superficie diciendo lo mismo.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                MaterialSymbol(
                    if (stopped) "stop_circle" else "pause_circle",
                    color = palette.onContainer,
                    size = 24.sp,
                    fill = true
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (stopped) R.string.download_banner_pending_title
                        else R.string.download_banner_paused_title
                    ),
                    style = MaterialTheme.typography.titleSmallEmphasized
                )
                Text(
                    stringResource(
                        if (stopped) R.string.download_banner_pending_desc
                        else R.string.download_banner_paused_desc,
                        pending
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Silencio permanente: nadie lo resetea; el estado sigue en el D. Manager.
                    if (onMute != null) {
                        TextButton(
                            onClick = onMute,
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.onContainer)
                        ) {
                            Text(stringResource(R.string.download_banner_never))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Button(
                        onClick = onResume,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.onContainer,
                            contentColor = palette.container
                        )
                    ) {
                        Text(stringResource(R.string.download_banner_resume))
                    }
                }
            }
            // X = cierre "una vez": alineada arriba, como los banners descartables de Android.
            if (onDismiss != null) {
                val closeDesc = stringResource(R.string.download_banner_close)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.Top)
                        .size(32.dp)
                        .semantics { contentDescription = closeDesc }
                ) {
                    MaterialSymbol("close", size = 18.sp, color = palette.onContainer)
                }
            }
        }
    }
}
