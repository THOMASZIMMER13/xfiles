package io.github.thomaszimmer13.xfiles.ui.trash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.a11y.FileTypeDescriber
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.ui.components.ConfirmDialog
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.explorer.FocusRequest

/**
 * Corbeille de tous les volumes montés, du plus récent au plus ancien.
 * Ordre de balayage : Retour → titre (« Corbeille, 3 éléments ») → Vider la corbeille
 * → rappel des 30 jours → éléments → message d'état.
 *
 * Chaque élément : appui double = choisir une action ; actions personnalisées « Restaurer »
 * et « Supprimer définitivement » (avec confirmation).
 */
@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    // Clé de l'élément dont le choix d'action est ouvert, ou dont la suppression est à confirmer.
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }

    val title = stringResource(R.string.trash_title)
    val spokenTitle = if (state.loading) title else "$title, ${FileDescriptions.itemCount(state.items.size)}"

    val listState = rememberLazyListState()
    val titleFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }

    FocusOnceEffect(ready = !state.loading) { titleFocus.requestFocus() }

    val focusRequest = state.focusRequest
    val focusKey = (focusRequest as? FocusRequest.Item)?.path
    LaunchedEffect(focusRequest, state.items) {
        when (focusRequest) {
            null -> return@LaunchedEffect
            FocusRequest.Title -> {
                withFrameNanos { }
                runCatching { titleFocus.requestFocus() }
            }
            is FocusRequest.Item -> {
                val index = state.items.indexOfFirst { it.key == focusRequest.path }
                if (index >= 0) {
                    listState.scrollToItem(index)
                    withFrameNanos { }
                    runCatching { itemFocus.requestFocus() }
                }
            }
        }
        viewModel.consumeFocusRequest()
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = spokenTitle,
            onBack = onBack,
            titleFocus = titleFocus,
            actions = {
                IconButton(onClick = { confirmEmpty = true }, enabled = state.items.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_empty_trash))
                }
            },
        )
        Text(
            text = stringResource(R.string.trash_retention),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Box(Modifier.weight(1f)) {
            when {
                state.loading -> CenteredMessage(stringResource(R.string.folder_loading))
                state.items.isEmpty() -> CenteredMessage(stringResource(R.string.trash_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.items, key = { _, it -> it.key }) { index, item ->
                        TrashRow(
                            item = item,
                            index = index,
                            count = state.items.size,
                            focusRequester = if (item.key == focusKey) itemFocus else null,
                            onChoose = { actionsFor = item.key },
                            onRestore = { viewModel.restore(item) },
                            onDelete = { confirmDeleteFor = item.key },
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
    }

    val chosen = actionsFor?.let { key -> state.items.firstOrNull { it.key == key } }
    if (chosen != null) {
        TrashActionsDialog(
            name = chosen.entry.name,
            onRestore = {
                actionsFor = null
                viewModel.restore(chosen)
            },
            onDelete = {
                actionsFor = null
                confirmDeleteFor = chosen.key
            },
            onDismiss = { actionsFor = null },
        )
    }

    val toDelete = confirmDeleteFor?.let { key -> state.items.firstOrNull { it.key == key } }
    if (toDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.confirm_delete_message, toDelete.entry.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDeleteFor = null
                viewModel.deleteForever(toDelete)
            },
            onDismiss = { confirmDeleteFor = null },
        )
    }

    if (confirmEmpty && state.items.isNotEmpty()) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_empty_title),
            message = pluralStringResource(R.plurals.confirm_empty_message, state.items.size, state.items.size),
            confirmLabel = stringResource(R.string.action_empty_trash),
            onConfirm = {
                confirmEmpty = false
                viewModel.emptyTrash()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

/**
 * Une ligne = un seul élément TalkBack :
 * « Facture.pdf, document PDF, 245 kilooctets, supprimé hier à 9 h 05, dans Mémoire interne, Documents ».
 */
@Composable
private fun TrashRow(
    item: TrashItem,
    index: Int,
    count: Int,
    focusRequester: FocusRequester?,
    onChoose: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val entry = item.entry
    val size = if (entry.isDirectory) entry.childCount?.let(FileDescriptions::itemCount) else SizeFormatter.spoken(entry.sizeBytes)
    val where = if (item.origin.isEmpty()) null else stringResource(R.string.favorite_in, item.origin.joinToString(", "))
    val spoken = positioned(
        listOfNotNull(
            entry.name,
            FileTypeDescriber.describe(entry.name, entry.isDirectory),
            size,
            DateFormatter.spoken(entry.deletedAt, verb = "supprimé"),
            where,
        ).joinToString(", "),
        index,
        count,
    )
    val visibleDetail = listOfNotNull(
        if (entry.isDirectory) entry.childCount?.let(FileDescriptions::itemCount) else SizeFormatter.short(entry.sizeBytes),
        DateFormatter.short(entry.deletedAt),
        item.origin.joinToString(" › ").ifEmpty { null },
    ).joinToString(" · ")

    val chooseLabel = stringResource(R.string.action_choose)
    val restoreLabel = stringResource(R.string.action_restore)
    val deleteLabel = stringResource(R.string.action_delete_forever)

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    onClick(label = chooseLabel) { onChoose(); true }
                    customActions = listOf(
                        CustomAccessibilityAction(restoreLabel) { onRestore(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .clickable(onClick = onChoose)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(if (entry.isDirectory) R.drawable.ic_tab_explorer else R.drawable.ic_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    visibleDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/** Choix d'une action pour un élément de la corbeille (accessible aussi sans les actions personnalisées). */
@Composable
private fun TrashActionsDialog(name: String, onRestore: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, modifier = Modifier.semantics { heading() }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_restore), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.action_delete_forever),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
