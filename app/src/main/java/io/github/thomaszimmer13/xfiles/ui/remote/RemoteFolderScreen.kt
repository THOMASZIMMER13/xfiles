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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.thomaszimmer13.xfiles.ui.explorer.FocusRequest
import io.github.thomaszimmer13.xfiles.ui.explorer.NameDialog
import io.github.thomaszimmer13.xfiles.ui.explorer.DetailLine
import io.github.thomaszimmer13.xfiles.ui.explorer.DetailRow
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.FileTypeDescriber
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.open.FileOpener
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private fun RemoteEntry.asItem() = FileItem(path, name, isDirectory, size, lastModified, null)

/** Élément de [parent] (chemin distant) qui mène vers [target] : celui d'où l'on revient. */
internal fun remoteChildToward(parent: String, target: String?): String? {
    val prefix = if (parent == "/") "/" else "$parent/"
    if (target == null || !target.startsWith(prefix) || target.length <= prefix.length) return null
    return prefix + target.removePrefix(prefix).substringBefore('/')
}

/**
 * Dossier d'un serveur : une ligne par élément (nom, taille, date), actions Télécharger, Renommer,
 * Supprimer ; « Envoyer ici » envoie le presse-papiers d'XFiles. Ouvrir un fichier le copie d'abord
 * dans le téléphone (cache), puis l'ouvre comme un fichier local.
 */
@Composable
fun RemoteFolderScreen(
    serverId: String,
    path: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    /** Chemin d'où l'on revient : le focus est replacé sur l'élément qui y mène (audit M-06). */
    focusPath: String? = null,
    viewModel: RemoteFolderViewModel = viewModel(key = "remote:$serverId:$path"),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appLock = remember(context) { (context.applicationContext as io.github.thomaszimmer13.xfiles.XFilesApplication).container.appLock }
    val protect by appLock.protectServers.collectAsStateWithLifecycle()
    val confirmed by appLock.serversConfirmed.collectAsStateWithLifecycle()
    // Réglage « Confirmer mon identité » : rien n'est chargé avant (le dossier Android n'est pas concerné).
    if (protect && !confirmed && serverId != io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.SERVER_ID) {
        io.github.thomaszimmer13.xfiles.ui.security.IdentityGate(
            title = stringResource(R.string.security_servers_gate_title),
            onConfirmed = appLock::confirmServers,
            onBack = onBack,
        )
        return
    }
    LaunchedEffect(serverId, path) { if (viewModel.state.value.server == null) viewModel.load(serverId, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    val titleFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusTarget = remoteChildToward(path, focusPath)
    FocusOnceEffect(ready = !state.loading && !state.locked) {
        val index = state.entries.indexOfFirst { it.path == focusTarget }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { itemFocus.requestFocus() }.onFailure { titleFocus.requestFocus() }
        } else {
            titleFocus.requestFocus()
        }
    }
    // Ligne qui porte itemFocus : gardée après la demande, pour que l'élément reste focalisable.
    var focusedPath by remember { mutableStateOf(focusTarget) }
    RemoteFocusEffect(state.entries, state.focusRequest, listState, itemFocus, titleFocus, { focusedPath = it }, viewModel::consumeFocusRequest)
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var details by remember { mutableStateOf<RemoteEntry?>(null) }
    val pathLabel = stringResource(R.string.details_path)
    val pathCopied = stringResource(R.string.path_copied)
    val copyPath: (RemoteEntry) -> Unit = { entry ->
        FileOpener.copyToClipboard(context, pathLabel, viewModel.displayPath(entry))
        viewModel.announcer.announce(pathCopied)
    }

    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.consumeOpened()
            launcher.open(it.path, it.name, false)
        }
    }
    LaunchedEffect(state.sharing) {
        state.sharing?.let {
            viewModel.consumeSharing()
            launcher.share(it.path, it.name)
        }
    }

    val server = state.server
    val folderName = if (path == "/") server?.name.orEmpty() else RemoteClient.nameOf(path)
    val spokenTitle = when {
        server == null -> folderName
        state.locked -> stringResource(R.string.vault_title_locked, server.name)
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
                if (server != null && server.protocol == io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT && !state.locked) {
                    IconButton(onClick = viewModel::lockVault) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.vault_lock))
                    }
                }
                if (server != null && !state.locked) {
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
                val spokenLabel = t.fraction?.let { "${t.label} : ${io.github.thomaszimmer13.xfiles.a11y.ProgressSpeech.tenths(it)} %" } ?: t.label
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = spokenLabel
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
            state.locked && server != null -> io.github.thomaszimmer13.xfiles.ui.vault.VaultUnlockPanel(server, state.unlocking, viewModel)
            state.loading && state.entries.isEmpty() -> Text(stringResource(R.string.remote_connecting), modifier = Modifier.padding(16.dp))
            state.error != null && state.entries.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.error!!, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.remote_retry)) }
                when (server?.protocol) {
                    null, io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT -> Unit
                    io.github.thomaszimmer13.xfiles.data.remote.Protocol.SHIZUKU -> TextButton(onClick = { onOpen(Routes.SHIZUKU) }) { Text(stringResource(R.string.shizuku_open_guide)) }
                    else -> TextButton(onClick = { onOpen(Routes.serverEdit(server.id)) }) { Text(stringResource(R.string.remote_edit_server)) }
                }
            }
            state.entries.isEmpty() -> Text(stringResource(R.string.folder_empty), modifier = Modifier.padding(16.dp))
            else -> RemoteEntryList(
                entries = state.entries,
                listState = listState,
                itemFocus = itemFocus,
                focusedPath = focusedPath,
                modifier = Modifier.weight(1f),
            ) { entry ->
                        EntryActions(
                            open = { if (entry.isDirectory) onOpen(Routes.remote(serverId, entry.path)) else viewModel.open(entry) },
                            download = { viewModel.download(entry) },
                            share = if (entry.isDirectory) null else ({ viewModel.share(entry) }),
                            rename = { renaming = entry },
                            details = { details = entry },
                            copyPath = { copyPath(entry) },
                            delete = { deleting = entry },
                        )
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
    details?.let { entry ->
        RemoteDetailsDialog(
            entry = entry,
            serverName = server?.name.orEmpty(),
            onCopyPath = { details = null; copyPath(entry) },
            onDismiss = { details = null },
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

/**
 * Après une création, un renommage ou une suppression (audit M-06) : focus sur l'élément concerné
 * ou son voisin, sinon sur le titre (dossier devenu vide).
 */
@Composable
internal fun RemoteFocusEffect(
    entries: List<RemoteEntry>,
    focusRequest: FocusRequest?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemFocus: FocusRequester,
    titleFocus: FocusRequester,
    /** Ligne à munir de [itemFocus] avant la demande de focus. */
    onFocusPath: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(focusRequest, entries) {
        when (focusRequest) {
            null -> return@LaunchedEffect
            FocusRequest.Title -> {
                withFrameNanos { }
                runCatching { titleFocus.requestFocus() }
            }
            is FocusRequest.Item -> {
                val index = entries.indexOfFirst { it.path == focusRequest.path }
                if (index < 0) return@LaunchedEffect
                onFocusPath(focusRequest.path)
                listState.scrollToItem(index)
                withFrameNanos { }
                withFrameNanos { }
                runCatching { itemFocus.requestFocus() }
            }
        }
        onConsumed()
    }
}

/** Liste d'un dossier distant : une ligne TalkBack par élément ; [focusedPath] reçoit [itemFocus]. */
@Composable
internal fun RemoteEntryList(
    entries: List<RemoteEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemFocus: FocusRequester,
    focusedPath: String?,
    modifier: Modifier = Modifier,
    actionsFor: (RemoteEntry) -> EntryActions,
) {
    LazyColumn(modifier, state = listState) {
        items(entries, key = { it.path }) { entry ->
            EntryRow(entry = entry, focusRequester = if (entry.path == focusedPath) itemFocus else null, actions = actionsFor(entry))
        }
    }
}

/** Actions d'une ligne ; [share] est null pour un dossier. */
internal class EntryActions(
    val open: () -> Unit,
    val download: () -> Unit,
    val share: (() -> Unit)?,
    val rename: () -> Unit,
    val details: () -> Unit,
    val copyPath: () -> Unit,
    val delete: () -> Unit,
)

@Composable
private fun EntryRow(entry: RemoteEntry, focusRequester: FocusRequester?, actions: EntryActions) {
    val item = entry.asItem()
    val spoken = FileDescriptions.spoken(item)
    val openLabel = stringResource(R.string.action_open)
    val downloadLabel = stringResource(R.string.remote_download)
    val shareLabel = stringResource(R.string.action_share)
    val renameLabel = stringResource(R.string.action_rename)
    val detailsLabel = stringResource(R.string.action_details)
    val copyPathLabel = stringResource(R.string.action_copy_path)
    val deleteLabel = stringResource(R.string.remote_delete)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clickable(onClick = actions.open)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    onClick(label = openLabel) { actions.open(); true }
                    customActions = buildList {
                        add(CustomAccessibilityAction(downloadLabel) { actions.download(); true })
                        actions.share?.let { share -> add(CustomAccessibilityAction(shareLabel) { share(); true }) }
                        add(CustomAccessibilityAction(renameLabel) { actions.rename(); true })
                        add(CustomAccessibilityAction(detailsLabel) { actions.details(); true })
                        add(CustomAccessibilityAction(copyPathLabel) { actions.copyPath(); true })
                        add(CustomAccessibilityAction(deleteLabel) { actions.delete(); true })
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(if (entry.isDirectory) R.drawable.ic_tab_explorer else itemIcon(entry.name, false)), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = FileDescriptions.visibleDetail(item)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/** Détails d'un élément distant : seules les informations connues du serveur sont lues. */
@Composable
private fun RemoteDetailsDialog(entry: RemoteEntry, serverName: String, onCopyPath: () -> Unit, onDismiss: () -> Unit) {
    val rows = buildList {
        add(DetailRow(stringResource(R.string.details_name), entry.name))
        add(DetailRow(stringResource(R.string.details_type), FileTypeDescriber.describe(entry.name, entry.isDirectory).replaceFirstChar { it.uppercase() }))
        if (!entry.isDirectory && entry.size >= 0) {
            add(DetailRow(stringResource(R.string.details_size), SizeFormatter.short(entry.size), "${SizeFormatter.spoken(entry.size)}, ${entry.size} octets"))
        }
        if (entry.lastModified > 0) add(DetailRow(stringResource(R.string.details_modified), DateFormatter.full(entry.lastModified)))
        add(DetailRow(stringResource(R.string.details_location), serverName))
        add(DetailRow(stringResource(R.string.details_path), entry.path))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { DetailLine(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = onCopyPath) { Text(stringResource(R.string.action_copy_path)) } },
    )
}
