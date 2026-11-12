package io.github.thomaszimmer13.xfiles.ui.explorer

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.open.FileOpener
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.domain.Crumb
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Contenu d'un dossier.
 * Ordre de balayage : Retour → titre (« Factures, 12 éléments ») → Trier et afficher
 * → fil d'Ariane → fichiers → message d'état.
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

    val context = LocalContext.current
    val openWithTitle = stringResource(R.string.chooser_open_with)
    val shareTitle = stringResource(R.string.chooser_share)
    val noAppMessage = stringResource(R.string.open_no_app)
    val failedMessage = stringResource(R.string.open_failed)
    val pathCopiedMessage = stringResource(R.string.path_copied)
    val pathLabel = stringResource(R.string.details_path)

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
    )

    // Tant que le ViewModel n'a pas basculé sur ce chemin, on n'affiche rien de l'ancien dossier.
    val ready = state.path == path
    val loaded = ready && !state.loading

    val title = if (ready) state.crumbs.lastOrNull()?.name.orEmpty() else ""
    val spokenTitle = if (loaded && !state.error) "$title, ${FileDescriptions.itemCount(state.items.size)}" else title

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

    // Après une création ou un renommage : focus sur l'élément concerné.
    val focusRequest = state.focusRequest
    LaunchedEffect(focusRequest, state.items) {
        val target = focusRequest ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.path == target }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
        withFrameNanos { }
        runCatching { itemFocus.requestFocus() }
        viewModel.consumeFocusRequest()
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = spokenTitle,
            onBack = onBack,
            titleFocus = titleFocus,
            actions = {
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(painterResource(R.drawable.ic_new_folder), contentDescription = stringResource(R.string.new_folder_title))
                }
                IconButton(onClick = { showSortDialog = true }) {
                    Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort_title))
                }
            },
        )
        if (ready) Breadcrumb(state.crumbs, onJump)

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
                            focusRequester = if (item.path == (focusRequest ?: focusTarget)) itemFocus else null,
                            actions = actions,
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
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
)

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
 * dans l'ordre : ouvrir avec, partager, renommer, (copier, déplacer, supprimer à venir), favori, détails.
 */
@Composable
private fun FileRow(
    item: FileItem,
    isFavorite: Boolean,
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

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    onClick(label = openLabel) { actions.open(item); true }
                    customActions = buildList {
                        if (!item.isDirectory) {
                            add(CustomAccessibilityAction(openWithLabel) { actions.openWith(item); true })
                            add(CustomAccessibilityAction(shareLabel) { actions.share(item); true })
                        }
                        add(CustomAccessibilityAction(renameLabel) { actions.rename(item); true })
                        if (item.isDirectory) {
                            // Les favoris concernent les dossiers ; les fichiers favoris viendront avec l'onglet Favoris.
                            add(CustomAccessibilityAction(favoriteLabel) { actions.toggleFavorite(item); true })
                        }
                        add(CustomAccessibilityAction(detailsLabel) { actions.details(item); true })
                    }
                }
                .clickable { actions.open(item) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
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
