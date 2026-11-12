package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.a11y.FileTypeDescriber
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.open.FileOpener
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.domain.Crumb
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Détails d'un fichier ou d'un dossier.
 * Chaque information est un seul élément TalkBack : « Taille : 245 kilooctets, 245312 octets ».
 *
 * @param parentCrumbs fil d'Ariane du dossier qui contient l'élément.
 */
@Composable
fun DetailsDialog(
    item: FileItem,
    parentCrumbs: List<Crumb>,
    onCopyPath: () -> Unit,
    onDismiss: () -> Unit,
) {
    val access = remember(item.path) {
        val file = File(item.path)
        when {
            file.canRead() && file.canWrite() -> R.string.access_read_write
            file.canRead() -> R.string.access_read_only
            else -> R.string.access_none
        }
    }

    val rows = buildList {
        add(DetailRow(stringResource(R.string.details_name), item.name))
        add(DetailRow(stringResource(R.string.details_type), FileTypeDescriber.describe(item.name, item.isDirectory).replaceFirstChar { it.uppercase() }))
        if (item.isDirectory) {
            item.childCount?.let { add(DetailRow(stringResource(R.string.details_content), FileDescriptions.itemCount(it).replaceFirstChar { c -> c.uppercase() })) }
        } else {
            val grouped = NumberFormat.getIntegerInstance(Locale.FRANCE).format(item.sizeBytes)
            add(
                DetailRow(
                    label = stringResource(R.string.details_size),
                    value = "${SizeFormatter.short(item.sizeBytes)} ($grouped octets)",
                    // Sans séparateur de milliers : TalkBack lit « 245312 » comme un seul nombre.
                    spokenValue = "${SizeFormatter.spoken(item.sizeBytes)}, ${item.sizeBytes} octets",
                ),
            )
            add(DetailRow(stringResource(R.string.details_mime), FileOpener.mimeType(item.name)))
        }
        add(DetailRow(stringResource(R.string.details_modified), DateFormatter.full(item.lastModified)))
        add(
            DetailRow(
                label = stringResource(R.string.details_location),
                value = parentCrumbs.joinToString(" › ") { it.name },
                spokenValue = parentCrumbs.joinToString(", ") { it.name },
            ),
        )
        add(DetailRow(stringResource(R.string.details_access), stringResource(access)))
        add(DetailRow(stringResource(R.string.details_path), item.path))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row -> DetailLine(row) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = onCopyPath) { Text(stringResource(R.string.action_copy_path)) }
        },
    )
}

private data class DetailRow(val label: String, val value: String, val spokenValue: String = value)

@Composable
private fun DetailLine(row: DetailRow) {
    val spoken = stringResource(R.string.details_row_spoken, row.label, row.spokenValue)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.value, style = MaterialTheme.typography.bodyLarge)
    }
}
