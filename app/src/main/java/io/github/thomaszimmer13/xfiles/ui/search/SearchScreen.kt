package io.github.thomaszimmer13.xfiles.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import java.io.File

/**
 * Recherche par nom. Ordre de balayage : Retour → titre (« Rechercher dans Documents ») → champ de recherche
 * → nombre de résultats → résultats → message d'état.
 * Le champ reçoit le focus à l'ouverture ; le résultat est annoncé après une pause de frappe.
 *
 * @param scope dossier où chercher (sous-dossiers compris) ; null = tous les emplacements.
 */
@Composable
fun SearchScreen(
    scope: String?,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    LaunchedEffect(scope) { viewModel.setScope(scope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    val keyboard = LocalSoftwareKeyboardController.current

    val title = stringResource(R.string.action_search)
    val spokenTitle = if (scope == null) stringResource(R.string.search_everywhere) else stringResource(R.string.search_in, File(scope).name)
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    val revealLabel = stringResource(R.string.action_reveal)
    val openWithLabel = stringResource(R.string.action_open_with)
    val shareLabel = stringResource(R.string.action_share)
    val openLabel = stringResource(R.string.action_open)

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = title, spokenTitle = spokenTitle, onBack = onBack)
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.search_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(fieldFocus),
        )
        val results = state.results
        val summary = when {
            state.searching -> stringResource(R.string.search_running)
            results == null -> null
            state.truncated -> stringResource(R.string.search_truncated, results.size)
            results.isEmpty() -> stringResource(R.string.search_none)
            else -> pluralStringResource(R.plurals.search_count, results.size, results.size)
        }
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            if (results.isNullOrEmpty()) {
                if (results == null && !state.searching) {
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxSize().padding(24.dp).align(Alignment.Center),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.item.path }) { result ->
                        val item = result.item
                        val where = result.parents.joinToString(", ")
                        ItemRow(
                            icon = itemIcon(item.name, item.isDirectory),
                            title = item.name,
                            detail = listOf(FileDescriptions.visibleDetail(item), result.parents.joinToString(" › ")).filter { it.isNotEmpty() }.joinToString(" · "),
                            spoken = FileDescriptions.spoken(item) + if (where.isNotEmpty()) ", " + stringResource(R.string.favorite_in, where) else "",
                            clickLabel = openLabel,
                            onClick = { launcher.open(item.path, item.name, item.isDirectory) },
                            actions = buildList {
                                add(RowAction(revealLabel) { onReveal(item.path) })
                                if (!item.isDirectory) {
                                    add(RowAction(openWithLabel) { launcher.openWith(item.path, item.name) })
                                    add(RowAction(shareLabel) { launcher.share(item.path, item.name) })
                                }
                            },
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
    }
}
