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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Contenu d'un dossier.
 * Ordre de balayage : Retour → titre (« Factures, 12 éléments ») → fil d'Ariane → fichiers.
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
            androidx.compose.runtime.withFrameNanos { }
            itemFocus.requestFocus()
        } else {
            titleFocus.requestFocus()
        }
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = title, spokenTitle = spokenTitle, onBack = onBack, titleFocus = titleFocus)
        if (ready) Breadcrumb(state.crumbs, onJump)

        when {
            !loaded -> CenteredMessage(stringResource(R.string.folder_loading))
            state.error -> CenteredMessage(stringResource(R.string.folder_error))
            state.items.isEmpty() -> CenteredMessage(stringResource(R.string.folder_empty))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.path }) { item ->
                    FileRow(
                        item = item,
                        focusRequester = if (item.path == focusTarget) itemFocus else null,
                        onOpenFolder = onOpenFolder,
                    )
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

/**
 * Fil d'Ariane : un seul élément TalkBack (« Emplacement : Stockage interne, Documents, Factures »).
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

/** Une ligne = un seul élément TalkBack : nom, type, taille, date. */
@Composable
private fun FileRow(item: FileItem, focusRequester: FocusRequester?, onOpenFolder: (String) -> Unit) {
    val spoken = FileDescriptions.spoken(item)
    val openLabel = stringResource(R.string.action_open)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (item.isDirectory) onClick(label = openLabel) { onOpenFolder(item.path); true }
            }
            // Les fichiers s'ouvriront à l'étape 4 ; pour l'instant seuls les dossiers réagissent.
            .then(if (item.isDirectory) Modifier.clickable { onOpenFolder(item.path) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(if (item.isDirectory) R.drawable.ic_tab_explorer else R.drawable.ic_file),
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
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
