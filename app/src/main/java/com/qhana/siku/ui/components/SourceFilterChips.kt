package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.SongSourceFilter

/**
 * Chips de origen combinables por UNIÓN (Local + Descargadas = todo lo offline), compartidos
 * por la pestaña Todas y las de Artistas/Álbumes. Se emiten como hermanos dentro de la Row
 * del llamador (que aporta el espaciado). Visibilidad por CONTENIDO: Local solo si hay ambas
 * familias; Descargadas/Nube solo si hay nube. NO renderiza el chip de conteo ni el de orden:
 * esos los pone cada pantalla (plural distinto / criterio distinto).
 */
@Composable
fun SourceFilterChips(
    filters: Set<SongSourceFilter>,
    showLocal: Boolean,
    showCloud: Boolean,
    onToggle: (SongSourceFilter) -> Unit
) {
    if (showLocal) {
        SourceFilterChip(
            selected = SongSourceFilter.LOCAL in filters,
            label = stringResource(R.string.filter_source_local),
            icon = "folder",
            onClick = { onToggle(SongSourceFilter.LOCAL) }
        )
    }
    if (showCloud) {
        SourceFilterChip(
            selected = SongSourceFilter.DOWNLOADED in filters,
            label = stringResource(R.string.filter_source_downloaded),
            icon = "download_done",
            onClick = { onToggle(SongSourceFilter.DOWNLOADED) }
        )
        SourceFilterChip(
            selected = SongSourceFilter.STREAMING in filters,
            label = stringResource(R.string.filter_source_streaming),
            icon = "cloud",
            onClick = { onToggle(SongSourceFilter.STREAMING) }
        )
    }
}

@Composable
fun SourceFilterChip(
    selected: Boolean,
    label: String,
    icon: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        // Check al seleccionar, icono del filtro en reposo (patrón M3 de filter chip).
        leadingIcon = {
            MaterialSymbol(if (selected) "check" else icon, size = 18.sp)
        }
    )
}

/**
 * Aviso "el filtro de origen no dejó resultados" para las pestañas Artistas/Álbumes, pensado
 * para ir DEBAJO del toolbar (no ocupa la pantalla entera, así el toolbar con los chips sigue
 * accesible para quitar el filtro). Mismas cadenas que el vacío filtrado de la pestaña Todas.
 */
@Composable
fun FilteredEmptyHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 32.dp, end = 32.dp, bottom = 32.dp)
    ) {
        MaterialSymbol(
            "filter_alt_off",
            size = 64.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.filter_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.filter_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}
