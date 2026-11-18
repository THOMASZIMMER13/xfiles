package io.github.thomaszimmer13.xfiles.ui.favorites

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.fileItemOf
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import io.github.thomaszimmer13.xfiles.ui.components.IncomingBanner
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un favori : [item] est nul si l'élément n'existe plus (déplacé ou supprimé hors de XFiles). */
data class Favorite(val path: String, val name: String, val item: FileItem?, val parents: List<String>)

data class FavoritesUiState(
    val folders: List<Favorite> = emptyList(),
    val files: List<Favorite> = emptyList(),
    val loaded: Boolean = false,
    /** Favori qui vient d'être monté ou descendu : le focus TalkBack reste dessus. */
    val movedPath: String? = null,
)

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val preferences = container.preferences
    private val volumes = container.volumeRepository

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private var paths: List<String> = emptyList()

    init {
        viewModelScope.launch {
            preferences.favoriteFolders.collect {
                paths = it
                refresh()
            }
        }
    }

    fun reload() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val all = withContext(Dispatchers.IO) {
            val roots = volumes.volumeRoots()
            paths.map { path ->
                val file = File(path)
                val crumbs = Breadcrumbs.of(path, roots)
                Favorite(
                    path = path,
                    name = crumbs.lastOrNull()?.name ?: file.name,
                    item = if (file.exists()) fileItemOf(file) else null,
                    parents = crumbs.dropLast(1).map { it.name },
                )
            }
        }
        // Un favori introuvable reste avec les dossiers (les favoris de dossiers sont les plus anciens).
        _state.value = FavoritesUiState(
            folders = all.filter { it.item?.isDirectory != false },
            files = all.filter { it.item?.isDirectory == false },
            loaded = true,
            movedPath = _state.value.movedPath,
        )
    }

    /** Monte ou descend [favorite] d'une place dans sa section, et annonce sa nouvelle position. */
    fun move(favorite: Favorite, up: Boolean) {
        viewModelScope.launch {
            val isFolder = favorite.item?.isDirectory != false
            val position = preferences.moveFavorite(favorite.path, up) { path ->
                val f = File(path)
                f.isDirectory || !f.exists()
            } ?: return@launch
            val count = if (isFolder) _state.value.folders.size else _state.value.files.size
            _state.value = _state.value.copy(movedPath = favorite.path)
            announcer.announce(
                getApplication<Application>().getString(
                    if (up) R.string.favorite_moved_up else R.string.favorite_moved_down,
                    favorite.name, position + 1, count,
                ),
            )
        }
    }

    fun consumeMoved() {
        _state.value = _state.value.copy(movedPath = null)
    }

    fun remove(favorite: Favorite) {
        viewModelScope.launch {
            preferences.toggleFavorite(favorite.path)
            announcer.announce(getApplication<Application>().getString(R.string.favorite_removed, favorite.name))
        }
    }
}

/**
 * Onglet Favoris : sections « Dossiers » et « Fichiers ».
 * Appui double : ouvrir ; actions : afficher dans le dossier, (ouvrir avec, partager), retirer des favoris.
 */
@Composable
fun FavoritesScreen(
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
    focusPath: String?,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    val rowFocus = remember { FocusRequester() }
    val focusTarget = focusPath?.let { target ->
        (state.folders + state.files).firstOrNull { target == it.path || target.startsWith(it.path + "/") }?.path
    }
    FocusOnceEffect(ready = state.loaded && focusTarget != null) {
        withFrameNanos { }
        rowFocus.requestFocus()
    }
    // Après « Monter » ou « Descendre » : le focus suit le favori à sa nouvelle place.
    val moved = state.movedPath
    LaunchedEffect(moved, state.folders, state.files) {
        if (moved == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { rowFocus.requestFocus() }
        viewModel.consumeMoved()
    }
    val focusedPath = moved ?: focusTarget

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.tab_favorites))
        IncomingBanner(onSaveHere = null)
        Box(Modifier.weight(1f)) {
            if (state.loaded && state.folders.isEmpty() && state.files.isEmpty()) {
                Text(
                    stringResource(R.string.empty_favorites),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(24.dp).align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (state.folders.isNotEmpty()) {
                        item(key = "h-folders") { SectionHeader(stringResource(R.string.section_folders)) }
                        itemsIndexed(state.folders, key = { _, it -> "d:" + it.path }) { index, fav ->
                            FavoriteRow(fav, index, state.folders.size, if (fav.path == focusedPath) rowFocus else null, launcher::open, onReveal, viewModel::remove, viewModel::move, launcher)
                        }
                    }
                    if (state.files.isNotEmpty()) {
                        item(key = "h-files") { SectionHeader(stringResource(R.string.section_files)) }
                        itemsIndexed(state.files, key = { _, it -> "f:" + it.path }) { index, fav ->
                            FavoriteRow(fav, index, state.files.size, if (fav.path == focusedPath) rowFocus else null, launcher::open, onReveal, viewModel::remove, viewModel::move, launcher)
                        }
                    }
                }
            }
        }
        StatusMessage(announcement)
    }
}

@Composable
internal fun FavoriteRow(
    fav: Favorite,
    index: Int,
    count: Int,
    focusRequester: FocusRequester?,
    open: (String, String, Boolean) -> Unit,
    onReveal: (String) -> Unit,
    onRemove: (Favorite) -> Unit,
    onMove: (Favorite, Boolean) -> Unit,
    launcher: io.github.thomaszimmer13.xfiles.ui.components.FileLauncher,
) {
    val item = fav.item
    val where = fav.parents.joinToString(", ")
    val inText = if (where.isEmpty()) null else stringResource(R.string.favorite_in, where)
    val missing = stringResource(R.string.favorite_missing)
    val spoken = if (item == null) {
        listOfNotNull(fav.name, inText, missing).joinToString(", ")
    } else {
        listOfNotNull(FileDescriptions.spoken(item).replaceFirst(item.name, fav.name), inText).joinToString(", ")
    }
    val detail = if (item == null) missing else listOf(FileDescriptions.visibleDetail(item), fav.parents.joinToString(" › ")).filter { it.isNotEmpty() }.joinToString(" · ")
    ItemRow(
        icon = itemIcon(fav.name, item?.isDirectory != false),
        title = fav.name,
        detail = detail,
        spoken = positioned(spoken, index, count),
        clickLabel = stringResource(R.string.action_open),
        onClick = item?.let { { open(it.path, it.name, it.isDirectory) } },
        focusRequester = focusRequester,
        actions = buildList {
            if (item != null) {
                add(RowAction(stringResource(R.string.action_reveal)) { onReveal(item.path) })
                if (!item.isDirectory) {
                    add(RowAction(stringResource(R.string.action_open_with)) { launcher.openWith(item.path, item.name) })
                    add(RowAction(stringResource(R.string.action_share)) { launcher.share(item.path, item.name) })
                }
            }
            if (index > 0) add(RowAction(stringResource(R.string.action_move_up)) { onMove(fav, true) })
            if (index < count - 1) add(RowAction(stringResource(R.string.action_move_down)) { onMove(fav, false) })
            add(RowAction(stringResource(R.string.action_remove_favorite)) { onRemove(fav) })
        },
    )
}
