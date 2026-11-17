package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.open.FileOpener
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.transfer.ClipContent
import io.github.thomaszimmer13.xfiles.data.transfer.TransferMode
import io.github.thomaszimmer13.xfiles.domain.Crumb
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Contenu d'un dossier.
 * Ordre de balayage : Retour → titre (« Factures, 12 éléments ») → Nouveau dossier → Sélectionner
 * → Trier et afficher → fil d'Ariane → fichiers → message d'état.
 *
 * En mode sélection : Terminer la sélection → titre (« Sélection : 3 éléments sélectionnés, sur 12 éléments »)
 * → Tout sélectionner → fil d'Ariane → fichiers (cases à cocher) → Partager, Mettre à la corbeille → message.
 *
 * @param focusPath chemin d'où l'on revient : le focus est replacé sur le dossier correspondant ;
 * sinon il est placé sur le titre.
 */
@Composable
fun FolderScreen(
    path: String,
    focusPath: String?,
    onOpenFolder: (String) -> Unit,
    onBack: () -> Unit,
    onJump: (String?) -> Unit,
    viewModel: FolderViewModel = viewModel(),
) {
    LaunchedEffect(path) { viewModel.load(path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    // Chemin de l'élément dont les détails sont affichés (null = dialogue fermé).
    var detailsPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }
    // Chemin de l'élément en cours de renommage (null = dialogue fermé).
    var renamePath by rememberSaveable { mutableStateOf<String?>(null) }
    // Chemin de l'élément dont la mise à la corbeille est à confirmer.
    var trashPath by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSelectionTrash by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val openWithTitle = stringResource(R.string.chooser_open_with)
    val shareTitle = stringResource(R.string.chooser_share)
    val noAppMessage = stringResource(R.string.open_no_app)
    val failedMessage = stringResource(R.string.open_failed)
    val pathCopiedMessage = stringResource(R.string.path_copied)
    val pathLabel = stringResource(R.string.details_path)
    val foldersNotSharedMessage = stringResource(R.string.selection_share_folders)

    /** Annonce le résultat d'une transmission à une autre app, s'il y a un problème. */
    fun report(result: FileOpener.Result) {
        when (result) {
            FileOpener.Result.OK -> Unit
            FileOpener.Result.NO_APP -> viewModel.announcer.announce(noAppMessage)
            FileOpener.Result.FAILED -> viewModel.announcer.announce(failedMessage)
        }
    }

    val actions = FileRowActions(
        open = { item ->
            if (item.isDirectory) onOpenFolder(item.path) else report(FileOpener.open(context, item.path, item.name))
        },
        openWith = { item -> report(FileOpener.open(context, item.path, item.name, chooserTitle = openWithTitle)) },
        share = { item -> report(FileOpener.share(context, item.path, item.name, shareTitle)) },
        rename = { item -> renamePath = item.path },
        toggleFavorite = viewModel::toggleFavorite,
        details = { item -> detailsPath = item.path },
        moveToTrash = { item -> trashPath = item.path },
        copy = { item -> viewModel.putInClipboard(listOf(item), TransferMode.COPY) },
        move = { item -> viewModel.putInClipboard(listOf(item), TransferMode.MOVE) },
        select = { item -> viewModel.startSelection(item) },
        toggle = viewModel::toggleSelection,
    )

    // Retour arrière en mode sélection : on quitte seulement la sélection, pas le dossier.
    BackHandler(enabled = state.selecting) { viewModel.endSelection(moveFocusToTitle = false) }

    // Tant que le ViewModel n'a pas basculé sur ce chemin, on n'affiche rien de l'ancien dossier.
    val ready = state.path == path
    val loaded = ready && !state.loading

    val title = if (ready) state.crumbs.lastOrNull()?.name.orEmpty() else ""
    val selectionTitle = FileDescriptions.selectedCount(state.selected.size).replaceFirstChar { it.uppercase() }
    val spokenTitle = when {
        state.selecting -> stringResource(
            R.string.selection_spoken,
            FileDescriptions.selectedCount(state.selected.size),
            FileDescriptions.itemCount(state.items.size),
        )
        loaded && !state.error -> "$title, ${FileDescriptions.itemCount(state.items.size)}"
        else -> title
    }
    val allSelected = state.items.isNotEmpty() && state.selected.size == state.items.size

    val listState = rememberLazyListState()
    val titleFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    val focusTarget = directChildToward(path, focusPath)

    FocusOnceEffect(ready = loaded) {
        val index = state.items.indexOfFirst { it.path == focusTarget }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            itemFocus.requestFocus()
        } else {
            titleFocus.requestFocus()
        }
    }

    // Après une création, un renommage ou une suppression : focus sur l'élément concerné.
    val focusRequest = state.focusRequest
    val focusRequestPath = (focusRequest as? FocusRequest.Item)?.path
    LaunchedEffect(focusRequest, state.items) {
        when (focusRequest) {
            null -> return@LaunchedEffect
            FocusRequest.Title -> {
                withFrameNanos { }
                runCatching { titleFocus.requestFocus() }
            }
            is FocusRequest.Item -> {
                val index = state.items.indexOfFirst { it.path == focusRequest.path }
                if (index < 0) return@LaunchedEffect
                listState.scrollToItem(index)
                withFrameNanos { }
                runCatching { itemFocus.requestFocus() }
            }
        }
        viewModel.consumeFocusRequest()
    }

    Column(Modifier.fillMaxSize()) {
        if (state.selecting) {
            XFilesTopBar(
                title = selectionTitle,
                spokenTitle = spokenTitle,
                onBack = { viewModel.endSelection(moveFocusToTitle = true) },
                backIcon = R.drawable.ic_close,
                backLabel = stringResource(R.string.action_end_selection),
                titleFocus = titleFocus,
                actions = {
                    IconButton(onClick = viewModel::toggleSelectAll) {
                        Icon(
                            painterResource(R.drawable.ic_select_all),
                            contentDescription = stringResource(if (allSelected) R.string.action_unselect_all else R.string.action_select_all),
                        )
                    }
                },
            )
        } else {
            XFilesTopBar(
                title = title,
                spokenTitle = spokenTitle,
                onBack = onBack,
                titleFocus = titleFocus,
                actions = {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(painterResource(R.drawable.ic_new_folder), contentDescription = stringResource(R.string.new_folder_title))
                    }
                    if (loaded && state.items.isNotEmpty()) {
                        IconButton(onClick = { viewModel.startSelection() }) {
                            Icon(painterResource(R.drawable.ic_select), contentDescription = stringResource(R.string.action_select))
                        }
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort_title))
                    }
                },
            )
        }
        if (ready) Breadcrumb(state.crumbs, onJump)
        val clip = state.clipboard
        if (clip != null && !state.selecting && loaded && !state.error) {
            ClipboardBanner(
                clip = clip,
                pasting = state.pasting,
                onPaste = viewModel::paste,
                onClear = viewModel::clearClipboard,
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                !loaded -> CenteredMessage(stringResource(R.string.folder_loading))
                state.error -> CenteredMessage(stringResource(R.string.folder_error))
                state.items.isEmpty() -> CenteredMessage(stringResource(R.string.folder_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.path }) { item ->
                        FileRow(
                            item = item,
                            isFavorite = item.path in state.favorites,
                            selecting = state.selecting,
                            selected = item.path in state.selected,
                            focusRequester = if (item.path == (focusRequestPath ?: focusTarget)) itemFocus else null,
                            actions = actions,
                        )
                    }
                }
            }
        }
        if (state.selecting) {
            SelectionBar(
                enabled = state.selected.isNotEmpty(),
                onCopy = { viewModel.putSelectionInClipboard(TransferMode.COPY) },
                onMove = { viewModel.putSelectionInClipboard(TransferMode.MOVE) },
                onShare = {
                    val items = viewModel.selectedItems()
                    if (items.any { it.isDirectory }) {
                        viewModel.announcer.announce(foldersNotSharedMessage)
                    } else {
                        report(FileOpener.shareMany(context, items.map { it.path to it.name }, shareTitle))
                    }
                },
                onTrash = { confirmSelectionTrash = true },
            )
        }
        StatusMessage(announcement)
    }

    state.conflict?.let { prompt -> ConflictDialog(prompt, onAnswer = viewModel::answerConflict) }

    if (confirmSelectionTrash && state.selected.isNotEmpty()) {
        val count = state.selected.size
        TrashConfirmDialog(
            trashMessage = pluralStringResource(R.plurals.confirm_trash_many, count, count),
            deleteMessage = pluralStringResource(R.plurals.confirm_delete_many, count, count),
            onMoveToTrash = {
                confirmSelectionTrash = false
                viewModel.moveSelectionToTrash()
            },
            onDeleteForever = {
                confirmSelectionTrash = false
                viewModel.deleteSelectionForever()
            },
            onDismiss = { confirmSelectionTrash = false },
        )
    }

    if (showSortDialog) {
        SortDialog(
            initial = state.sort,
            onApply = {
                showSortDialog = false
                viewModel.applySort(it)
            },
            onDismiss = { showSortDialog = false },
        )
    }

    if (showNewFolderDialog) {
        val defaultName = stringResource(R.string.new_folder_default)
        NameDialog(
            title = stringResource(R.string.new_folder_title),
            confirmLabel = stringResource(R.string.action_create),
            initialName = remember(defaultName) { viewModel.suggestedFolderName(defaultName) },
            keepExtension = false,
            check = { viewModel.checkName(it) },
            onConfirm = {
                showNewFolderDialog = false
                viewModel.createFolder(it)
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    val renameItem = renamePath?.let { p -> state.items.firstOrNull { it.path == p } }
    if (renameItem != null) {
        NameDialog(
            title = stringResource(R.string.rename_title, renameItem.name),
            confirmLabel = stringResource(R.string.action_rename),
            initialName = renameItem.name,
            keepExtension = !renameItem.isDirectory,
            check = { viewModel.checkName(it, current = renameItem.name) },
            onConfirm = {
                renamePath = null
                viewModel.rename(renameItem, it)
            },
            onDismiss = { renamePath = null },
        )
    }

    val trashItem = trashPath?.let { p -> state.items.firstOrNull { it.path == p } }
    if (trashItem != null) {
        TrashConfirmDialog(
            trashMessage = stringResource(R.string.confirm_trash_message, trashItem.name),
            deleteMessage = stringResource(R.string.confirm_delete_message, trashItem.name),
            onMoveToTrash = {
                trashPath = null
                viewModel.moveToTrash(trashItem)
            },
            onDeleteForever = {
                trashPath = null
                viewModel.deleteForever(trashItem)
            },
            onDismiss = { trashPath = null },
        )
    }

    val detailsItem = detailsPath?.let { p -> state.items.firstOrNull { it.path == p } }
    if (detailsItem != null) {
        DetailsDialog(
            item = detailsItem,
            parentCrumbs = state.crumbs,
            onCopyPath = {
                FileOpener.copyToClipboard(context, pathLabel, detailsItem.path)
                detailsPath = null
                viewModel.announcer.announce(pathCopiedMessage)
            },
            onDismiss = { detailsPath = null },
        )
    }
}

/** Actions possibles sur une ligne, regroupées pour alléger les paramètres. */
private class FileRowActions(
    val open: (FileItem) -> Unit,
    val openWith: (FileItem) -> Unit,
    val share: (FileItem) -> Unit,
    val rename: (FileItem) -> Unit,
    val toggleFavorite: (FileItem) -> Unit,
    val details: (FileItem) -> Unit,
    val moveToTrash: (FileItem) -> Unit,
    val copy: (FileItem) -> Unit,
    val move: (FileItem) -> Unit,
    /** Entre en mode sélection en cochant cet élément. */
    val select: (FileItem) -> Unit,
    /** Coche ou décoche (mode sélection). */
    val toggle: (FileItem) -> Unit,
)

/** Actions sur la sélection, en bas de l'écran (lues après la liste) : Copier, Déplacer, Partager, Corbeille. */
@Composable
private fun SelectionBar(
    enabled: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
) {
    Column {
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            ToolButton(R.drawable.ic_copy, stringResource(R.string.action_copy), enabled, onCopy)
            ToolButton(R.drawable.ic_move, stringResource(R.string.action_move), enabled, onMove)
            ToolButton(R.drawable.ic_share, stringResource(R.string.action_share), enabled, onShare)
            ToolButton(R.drawable.ic_delete, stringResource(R.string.action_trash_short), enabled, onTrash)
        }
    }
}

/** Bouton de barre d'outils : icône et libellé court dessous ; un seul élément TalkBack (« Copier, bouton »). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * Presse-papiers non vide : « Presse-papiers : 3 éléments à copier » puis « Coller ici » (ou « Déplacer ici »)
 * et « Vider le presse-papiers ». Placé juste après le fil d'Ariane pour être trouvé sans parcourir la liste.
 */
@Composable
private fun ClipboardBanner(clip: ClipContent, pasting: Boolean, onPaste: () -> Unit, onClear: () -> Unit) {
    val count = clip.items.size
    val copy = clip.mode == TransferMode.COPY
    val text = if (pasting) {
        stringResource(if (copy) R.string.paste_in_progress_copy else R.string.paste_in_progress_move)
    } else {
        pluralStringResource(
            if (copy) R.plurals.clipboard_banner_copy else R.plurals.clipboard_banner_move,
            count,
            count,
            clip.items.first().name,
        )
    }
    Column(Modifier.background(MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = onPaste, enabled = !pasting) {
                Icon(painterResource(R.drawable.ic_paste), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (copy) R.string.action_paste_here else R.string.action_move_here))
            }
            TextButton(onClick = onClear, enabled = !pasting) {
                Text(stringResource(R.string.action_clear_clipboard))
            }
        }
    }
}

/**
 * Fil d'Ariane : un seul élément TalkBack (« Emplacement : Mémoire interne, Documents, Factures »).
 * Ses actions personnalisées remontent directement vers un dossier parent.
 */
@Composable
private fun Breadcrumb(crumbs: List<Crumb>, onJump: (String?) -> Unit) {
    if (crumbs.isEmpty()) return
    val spoken = stringResource(R.string.breadcrumb_spoken, crumbs.joinToString(", ") { it.name })
    // Du parent le plus proche au plus lointain, puis la liste des emplacements.
    val targets = crumbs.dropLast(1).asReversed()
    val labels = targets.map { stringResource(R.string.breadcrumb_go_to, it.name) }
    val rootLabel = stringResource(R.string.breadcrumb_go_root)

    Text(
        text = crumbs.joinToString(" › ") { it.name },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                customActions = targets.mapIndexed { i, crumb ->
                    CustomAccessibilityAction(labels[i]) { onJump(crumb.path); true }
                } + CustomAccessibilityAction(rootLabel) { onJump(null); true }
            },
    )
}

/**
 * Une ligne = un seul élément TalkBack : nom, type, taille, date.
 * Appui double : ouvrir. Les autres actions sont des actions personnalisées TalkBack,
 * dans l'ordre : ouvrir avec, partager, renommer, copier, déplacer, favori, sélectionner, détails,
 * et en dernier, comme sur iOS, mettre à la corbeille (moins de risque de le déclencher par erreur).
 *
 * En mode sélection, la ligne devient une case à cocher : « …, case à cocher, cochée » ;
 * appui double = cocher ou décocher, sans autre action.
 */
@Composable
private fun FileRow(
    item: FileItem,
    isFavorite: Boolean,
    selecting: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester?,
    actions: FileRowActions,
) {
    val spoken = FileDescriptions.spoken(item)
    val openLabel = stringResource(R.string.action_open)
    val openWithLabel = stringResource(R.string.action_open_with)
    val shareLabel = stringResource(R.string.action_share)
    val renameLabel = stringResource(R.string.action_rename)
    val favoriteLabel = stringResource(if (isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite)
    val detailsLabel = stringResource(R.string.action_details)
    val trashLabel = stringResource(R.string.action_move_to_trash)
    val selectLabel = stringResource(R.string.action_select)
    val copyLabel = stringResource(R.string.action_copy)
    val moveLabel = stringResource(R.string.action_move)
    val checkLabel = stringResource(if (selected) R.string.toggle_unselect else R.string.toggle_select)

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (selecting) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(selected)
                        onClick(label = checkLabel) { actions.toggle(item); true }
                        return@clearAndSetSemantics
                    }
                    onClick(label = openLabel) { actions.open(item); true }
                    customActions = buildList {
                        if (!item.isDirectory) {
                            add(CustomAccessibilityAction(openWithLabel) { actions.openWith(item); true })
                            add(CustomAccessibilityAction(shareLabel) { actions.share(item); true })
                        }
                        add(CustomAccessibilityAction(renameLabel) { actions.rename(item); true })
                        add(CustomAccessibilityAction(copyLabel) { actions.copy(item); true })
                        add(CustomAccessibilityAction(moveLabel) { actions.move(item); true })
                        if (item.isDirectory) {
                            // Les favoris concernent les dossiers ; les fichiers favoris viendront avec l'onglet Favoris.
                            add(CustomAccessibilityAction(favoriteLabel) { actions.toggleFavorite(item); true })
                        }
                        add(CustomAccessibilityAction(selectLabel) { actions.select(item); true })
                        add(CustomAccessibilityAction(detailsLabel) { actions.details(item); true })
                        add(CustomAccessibilityAction(trashLabel) { actions.moveToTrash(item); true })
                    }
                }
                .clickable { if (selecting) actions.toggle(item) else actions.open(item) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (selecting) {
                // Décoratif : l'état est déjà porté par la ligne (case à cocher pour TalkBack).
                Checkbox(checked = selected, onCheckedChange = null)
            } else Icon(
                painter = painterResource(
                    when {
                        !item.isDirectory -> R.drawable.ic_file
                        isFavorite -> R.drawable.ic_favorite
                        else -> R.drawable.ic_tab_explorer
                    },
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    FileDescriptions.visibleDetail(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
