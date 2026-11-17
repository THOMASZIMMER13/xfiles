package io.github.thomaszimmer13.xfiles.ui.storage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.media.Categories
import io.github.thomaszimmer13.xfiles.data.media.Category
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.fileItemOf
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.IncomingBanner
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import io.github.thomaszimmer13.xfiles.ui.explorer.TrashConfirmDialog
import io.github.thomaszimmer13.xfiles.ui.explorer.focusAfterRemoval
import io.github.thomaszimmer13.xfiles.ui.explorer.FocusRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un fichier de la catégorie, avec son emplacement lisible (« Mémoire interne, DCIM, Camera »). */
data class CategoryEntry(val item: FileItem, val parents: List<String>)

data class CategoryUiState(
    val category: Category? = null,
    val entries: List<CategoryEntry> = emptyList(),
    val loading: Boolean = true,
    val focusRequest: FocusRequest? = null,
)

class CategoryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    val confirmTrash = container.preferences.confirmTrash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun load(category: Category, force: Boolean = false) {
        if (!force && _state.value.category == category && !_state.value.loading) return
        if (_state.value.category != category) _state.value = CategoryUiState(category)
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val roots = container.volumeRepository.volumeRoots()
                runCatching { Categories.paths(getApplication(), category) }.getOrDefault(emptyList())
                    .map(::File).filter { it.isFile }
                    .map { CategoryEntry(fileItemOf(it), Breadcrumbs.of(it.parent.orEmpty(), roots).map { c -> c.name }) }
            }
            _state.update { it.copy(entries = entries, loading = false) }
        }
    }

    fun remove(entry: CategoryEntry, permanent: Boolean) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val before = _state.value.entries.map { it.item.path }
            val result = removeAll(listOf(entry.item.path), permanent, container.trash, container.fileSource)
            if (result.removed.isEmpty()) {
                container.feedback.failure()
                announcer.announce(app.getString(R.string.op_failed, entry.item.name))
                return@launch
            }
            container.feedback.success()
            _state.update {
                it.copy(
                    entries = it.entries.filter { e -> e.item.path !in result.removed },
                    focusRequest = focusAfterRemoval(before, result.removed),
                )
            }
            announcer.announce(app.getString(if (permanent) R.string.deleted_forever else R.string.moved_to_trash, entry.item.name))
        }
    }

    fun consumeFocus() = _state.update { it.copy(focusRequest = null) }
}

/**
 * Une catégorie (Images, Vidéos, Audio, Documents, Archives, APK), du plus récent au plus ancien.
 * Ordre de balayage : Retour → titre (« Images, 245 éléments ») → fichiers.
 * Appui double : ouvrir ; actions : ouvrir avec, partager, afficher dans le dossier, mettre à la corbeille.
 */
@Composable
fun CategoryScreen(
    category: Category,
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CategoryViewModel = viewModel(),
) {
    LaunchedEffect(category) { viewModel.load(category) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    var trashPath by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmTrash by viewModel.confirmTrash.collectAsStateWithLifecycle()

    val ready = state.category == category && !state.loading
    val title = stringResource(categoryLabel(category))
    val spokenTitle = if (ready) "$title, ${FileDescriptions.itemCount(state.entries.size)}" else title
    val titleFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    val focusRequest = state.focusRequest
    val focusPath = (focusRequest as? FocusRequest.Item)?.path
    LaunchedEffect(focusRequest, state.entries) {
        when (focusRequest) {
            null -> return@LaunchedEffect
            FocusRequest.Title -> {
                withFrameNanos { }
                runCatching { titleFocus.requestFocus() }
            }
            is FocusRequest.Item -> {
                val index = state.entries.indexOfFirst { it.item.path == focusRequest.path }
                if (index < 0) return@LaunchedEffect
                listState.scrollToItem(index)
                withFrameNanos { }
                runCatching { rowFocus.requestFocus() }
            }
        }
        viewModel.consumeFocus()
    }

    val openLabel = stringResource(R.string.action_open)
    val openWithLabel = stringResource(R.string.action_open_with)
    val shareLabel = stringResource(R.string.action_share)
    val revealLabel = stringResource(R.string.action_reveal)
    val trashLabel = stringResource(R.string.action_move_to_trash)

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = title, spokenTitle = spokenTitle, onBack = onBack, titleFocus = titleFocus)
        IncomingBanner(onSaveHere = null)
        Box(Modifier.weight(1f)) {
            when {
                !ready -> Centered(stringResource(R.string.folder_loading))
                state.entries.isEmpty() -> Centered(stringResource(R.string.category_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.entries, key = { _, it -> it.item.path }) { index, entry ->
                        val item = entry.item
                        val where = entry.parents.joinToString(", ")
                        ItemRow(
                            icon = itemIcon(item.name, false),
                            title = item.name,
                            detail = listOf(FileDescriptions.visibleDetail(item), entry.parents.joinToString(" › ")).filter { it.isNotEmpty() }.joinToString(" · "),
                            spoken = positioned(
                                FileDescriptions.spoken(item) + if (where.isNotEmpty()) ", " + stringResource(R.string.favorite_in, where) else "",
                                index,
                                state.entries.size,
                            ),
                            clickLabel = openLabel,
                            onClick = { launcher.open(item.path, item.name, false) },
                            focusRequester = if (item.path == focusPath) rowFocus else null,
                            actions = listOf(
                                RowAction(openWithLabel) { launcher.openWith(item.path, item.name) },
                                RowAction(shareLabel) { launcher.share(item.path, item.name) },
                                RowAction(revealLabel) { onReveal(item.path) },
                                RowAction(trashLabel) { if (confirmTrash) trashPath = item.path else viewModel.remove(entry, permanent = false) },
                            ),
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
    }

    val trashEntry = trashPath?.let { p -> state.entries.firstOrNull { it.item.path == p } }
    if (trashEntry != null) {
        TrashConfirmDialog(
            trashMessage = stringResource(R.string.confirm_trash_message, trashEntry.item.name),
            deleteMessage = stringResource(R.string.confirm_delete_message, trashEntry.item.name),
            onMoveToTrash = {
                trashPath = null
                viewModel.remove(trashEntry, permanent = false)
            },
            onDeleteForever = {
                trashPath = null
                viewModel.remove(trashEntry, permanent = true)
            },
            onDismiss = { trashPath = null },
        )
    }
}

fun categoryLabel(category: Category): Int = when (category) {
    Category.IMAGES -> R.string.category_images
    Category.VIDEOS -> R.string.category_videos
    Category.AUDIO -> R.string.category_audio
    Category.DOCUMENTS -> R.string.category_documents
    Category.ARCHIVES -> R.string.category_archives
    Category.APK -> R.string.category_apk
}

@Composable
internal fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
