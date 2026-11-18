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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import io.github.thomaszimmer13.xfiles.data.analysis.FileStats
import io.github.thomaszimmer13.xfiles.data.analysis.FolderSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Dossier : taille totale calculée en arrière-plan dès l'ouverture.
    val folderSize by produceState<FolderSize?>(null, item.path) {
        if (item.isDirectory) value = withContext(Dispatchers.IO) { runCatching { FileStats.folderSize(File(item.path)) }.getOrNull() }
    }
    // Fichier : empreintes calculées à la demande (lecture complète du fichier).
    var hashes by remember(item.path) { mutableStateOf<Pair<String, String>?>(null) }
    var hashing by remember(item.path) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val hashingText = stringResource(R.string.hash_computing)
    val hashedText = stringResource(R.string.hash_done)
    val hashFailedText = stringResource(R.string.hash_failed)
    val copiedText = stringResource(R.string.hash_copied)

    val rows = buildList {
        add(DetailRow(stringResource(R.string.details_name), item.name))
        add(DetailRow(stringResource(R.string.details_type), FileTypeDescriber.describe(item.name, item.isDirectory).replaceFirstChar { it.uppercase() }))
        if (item.isDirectory) {
            item.childCount?.let { add(DetailRow(stringResource(R.string.details_content), FileDescriptions.itemCount(it).replaceFirstChar { c -> c.uppercase() })) }
            val total = folderSize
            if (total == null) {
                add(DetailRow(stringResource(R.string.details_total_size), stringResource(R.string.details_computing)))
            } else {
                val files = pluralStringResource(R.plurals.files_count, total.files, total.files)
                val folders = pluralStringResource(R.plurals.folders_count, total.folders, total.folders)
                add(
                    DetailRow(
                        label = stringResource(R.string.details_total_size),
                        value = "${SizeFormatter.short(total.bytes)} · $files · $folders",
                        spokenValue = "${SizeFormatter.spoken(total.bytes)}, $files, $folders",
                    ),
                )
            }
        } else if (item.sizeBytes >= 0) {
            val grouped = NumberFormat.getIntegerInstance(Locale.FRANCE).format(item.sizeBytes)
            add(
                DetailRow(
                    label = stringResource(R.string.details_size),
                    value = "${SizeFormatter.short(item.sizeBytes)} ($grouped octets)",
                    // Sans séparateur de milliers : TalkBack lit « 245312 » comme un seul nombre.
                    spokenValue = "${SizeFormatter.spoken(item.sizeBytes)}, ${item.sizeBytes} octets",
                ),
            )
        }
        if (!item.isDirectory) add(DetailRow(stringResource(R.string.details_mime), FileOpener.mimeType(item.name)))
        if (item.lastModified > 0) add(DetailRow(stringResource(R.string.details_modified), DateFormatter.full(item.lastModified)))
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
                if (!item.isDirectory) {
                    val current = hashes
                    if (current == null) {
                        TextButton(
                            enabled = !hashing,
                            onClick = {
                                hashing = true
                                status = hashingText
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { FileStats.hashes(File(item.path)) }.getOrNull() }
                                    hashing = false
                                    hashes = result
                                    status = if (result != null) hashedText else hashFailedText
                                }
                            },
                        ) { Text(stringResource(R.string.action_compute_hashes)) }
                    } else {
                        val copyLabel = stringResource(R.string.action_copy)
                        listOf("MD5" to current.first, "SHA-256" to current.second).forEach { (label, value) ->
                            DetailLine(
                                DetailRow(label, value),
                                clickLabel = copyLabel,
                                onClick = {
                                    FileOpener.copyToClipboard(context, label, value)
                                    status = copiedText.format(label)
                                },
                            )
                        }
                    }
                }
                // Annonces : calcul en cours, terminé, empreinte copiée.
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
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

internal data class DetailRow(val label: String, val value: String, val spokenValue: String = value)

@Composable
internal fun DetailLine(row: DetailRow, clickLabel: String? = null, onClick: (() -> Unit)? = null) {
    val spoken = stringResource(R.string.details_row_spoken, row.label, row.spokenValue)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) onClick(label = clickLabel) { onClick(); true }
            },
    ) {
        Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(row.value, style = MaterialTheme.typography.bodyLarge)
    }
}
