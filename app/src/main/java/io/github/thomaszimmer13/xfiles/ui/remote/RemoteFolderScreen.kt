package io.github.thomaszimmer13.xfiles.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.remote.RemoteClient
import io.github.thomaszimmer13.xfiles.data.remote.RemoteEntry
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import io.github.thomaszimmer13.xfiles.ui.explorer.NameDialog

private fun RemoteEntry.asItem() = FileItem(path, name, isDirectory, size, lastModified, null)

/**
 * Dossier d'un serveur : une ligne par élément (nom, taille, date), actions Télécharger, Renommer,
 * Supprimer ; « Envoyer ici » envoie le presse-papiers d'XFiles. Ouvrir un fichier le copie d'abord
 * dans le téléphone (cache), puis l'ouvre comme un fichier local.
 */
@Composable
fun RemoteFolderScreen(serverId: String, path: String, onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: RemoteFolderViewModel = viewModel(key = "remote:$serverId:$path")) {
    LaunchedEffect(serverId, path) { if (viewModel.state.value.server == null) viewModel.load(serverId, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = !state.loading) { titleFocus.requestFocus() }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }

    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.consumeOpened()
            launcher.open(it.path, it.name, false)
        }
    }

    val server = state.server
    val folderName = if (path == "/") server?.name.orEmpty() else RemoteClient.nameOf(path)
    val spokenTitle = when {
        server == null -> folderName
        path == "/" -> stringResource(R.string.remote_title_root, server.name, state.entries.size)
        else -> stringResource(R.string.remote_title, folderName, server.name, state.entries.size)
    }
    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = folderName,
            spokenTitle = spokenTitle,
            onBack = onBack,
            titleFocus = titleFocus,
            actions = {
                if (server != null) {
                    IconButton(onClick = viewModel::uploadClipboard) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = stringResource(R.string.remote_upload_here))
                    }
                    IconButton(onClick = { newFolder = true }) {
                        Icon(painterResource(R.drawable.ic_new_folder), contentDescription = stringResource(R.string.new_folder_title))
                    }
                }
            },
        )
        state.transfer?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                val percent = t.fraction?.let { (it * 100).toInt() }
                val label = if (percent != null) "${t.label} : $percent %" else t.label
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = label
                        t.fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it, 0f..1f) }
                    },
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (t.fraction != null) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = viewModel::cancelTransfer) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        StatusMessage(announcement)
        when {
            state.loading && state.entries.isEmpty() -> Text(stringResource(R.string.remote_connecting), modifier = Modifier.padding(16.dp))
            state.error != null && state.entries.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.error!!, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.remote_retry)) }
                if (server != null) TextButton(onClick = { onOpen(Routes.serverEdit(server.id)) }) { Text(stringResource(R.string.remote_edit_server)) }
            }
            state.entries.isEmpty() -> Text(stringResource(R.string.folder_empty), modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                items(state.entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        onOpen = { if (entry.isDirectory) onOpen(Routes.remote(serverId, entry.path)) else viewModel.open(entry) },
                        onDownload = { viewModel.download(entry) },
                        onRename = { renaming = entry },
                        onDelete = { deleting = entry },
                    )
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.new_folder_title),
            confirmLabel = stringResource(R.string.action_create),
            initialName = NameValidator.uniqueName(stringResource(R.string.new_folder_default), viewModel.names()),
            keepExtension = false,
            check = { NameValidator.check(it, viewModel.names()) },
            onConfirm = { newFolder = false; viewModel.mkdir(it) },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { entry ->
        NameDialog(
            title = stringResource(R.string.rename_title, entry.name),
            confirmLabel = stringResource(R.string.action_rename),
            initialName = entry.name,
            keepExtension = !entry.isDirectory,
            check = { NameValidator.check(it, viewModel.names(), entry.name) },
            onConfirm = { renaming = null; viewModel.rename(entry, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remote_delete_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(if (entry.isDirectory) R.string.remote_delete_folder_message else R.string.remote_delete_message, entry.name)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(entry) }) { Text(stringResource(R.string.remote_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.hostKey?.let { key ->
        AlertDialog(
            onDismissRequest = viewModel::rejectHostKey,
            title = { Text(stringResource(if (key.changed) R.string.remote_host_key_changed_title else R.string.remote_host_key_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(if (key.changed) R.string.remote_host_key_changed_message else R.string.remote_host_key_message, key.fingerprint)) },
            confirmButton = { TextButton(onClick = viewModel::trustHostKey) { Text(stringResource(R.string.remote_host_key_trust)) } },
            dismissButton = { TextButton(onClick = viewModel::rejectHostKey) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onOpen: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val item = entry.asItem()
    val spoken = FileDescriptions.spoken(item)
    val openLabel = stringResource(R.string.action_open)
    val downloadLabel = stringResource(R.string.remote_download)
    val renameLabel = stringResource(R.string.action_rename)
    val deleteLabel = stringResource(R.string.remote_delete)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    onClick(label = openLabel) { onOpen(); true }
                    customActions = listOf(
                        CustomAccessibilityAction(downloadLabel) { onDownload(); true },
                        CustomAccessibilityAction(renameLabel) { onRename(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(if (entry.isDirectory) R.drawable.ic_tab_explorer else itemIcon(entry.name, false)), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(FileDescriptions.visibleDetail(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}
