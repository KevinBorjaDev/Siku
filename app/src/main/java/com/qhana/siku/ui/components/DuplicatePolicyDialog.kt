package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.data.model.DuplicatePolicy

/**
 * Decisión de DUPLICADOS entre fuentes (spec dedup v23): el sync detectó [count] canciones
 * presentes a la vez en la nube y en la carpeta local (típico: la carpeta local es un
 * espejo sincronizado). La elección se persiste como política y no se vuelve a preguntar;
 * "Ahora no" solo pospone (el próximo scan vuelve a detectar) y mientras tanto las copias
 * de nube en disputa no se descargan.
 */
@Composable
fun DuplicatePolicyDialog(
    count: Int,
    onResolve: (DuplicatePolicy) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { MaterialSymbol("content_copy") },
        title = { Text(stringResource(R.string.duplicates_title)) },
        text = { Text(pluralStringResource(R.plurals.duplicates_message, count, count)) },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { onResolve(DuplicatePolicy.KEEP_BOTH) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.duplicates_keep_both)) }
                OutlinedButton(
                    onClick = { onResolve(DuplicatePolicy.PREFER_CLOUD) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.duplicates_prefer_cloud)) }
                OutlinedButton(
                    onClick = { onResolve(DuplicatePolicy.PREFER_LOCAL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.duplicates_prefer_local)) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.duplicates_later)) }
            }
        }
    )
}
