package io.github.thomaszimmer13.xfiles.ui.recents

import android.app.Application
import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
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
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.FileTypeDescriber
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.fileItemOf
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.ConfirmDialog
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Fichier récent ; [openedAt] est renseigné pour les fichiers ouverts depuis XFiles. */
data class RecentFile(val item: FileItem, val parents: List<String>, val openedAt: Long?)

data class RecentsUiState(
    val opened: List<RecentFile> = emptyList(),
    val modified: List<RecentFile> = emptyList(),
    val loaded: Boolean = false,
)

class RecentsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val preferences = container.preferences
    private val volumes = container.volumeRepository

    private val _state = MutableStateFlow(RecentsUiState())
    val state: StateFlow<RecentsUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private var history: List<io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository.RecentEntry> = emptyList()

    init {
        viewModelScope.launch {
            preferences.recentFiles.collect {
                history = it
                refresh()
            }
        }
    }

    fun reload() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val (opened, modified) = withContext(Dispatchers.IO) {
            val roots = volumes.volumeRoots()
            fun describe(file: File, openedAt: Long?) =
                RecentFile(fileItemOf(file), Breadcrumbs.of(file.parent.orEmpty(), roots).map { it.name }, openedAt)

            val opened = history.map { File(it.path) to it.openedAt }.filter { it.first.isFile }.take(MAX_SHOWN).map { describe(it.first, it.second) }
            val openedPaths = opened.mapTo(HashSet()) { it.item.path }
            val modified = runCatching { recentlyModified() }.getOrDefault(emptyList())
                .filter { it !in openedPaths }
                .map(::File)
                .filter { it.isFile }
                .take(MAX_SHOWN)
                .map { describe(it, null) }
            opened to modified
        }
        _state.value = RecentsUiState(opened, modified, loaded = true)
    }

    /**
     * Fichiers modifiés ces 7 derniers jours, d'après l'index des médias d'Android (photos, téléchargements,
     * documents…). Les dossiers cachés, la corbeille et les données des apps sont exclus.
     */
    private fun recentlyModified(): List<String> {
        val since = (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)) / 1000
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 150)
        }
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val paths = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val column = MediaStore.Files.FileColumns.DATA
        getApplication<Application>().contentResolver.query(uri, arrayOf(column), args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                val segments = path.split('/')
                if (segments.any { it.startsWith(".") }) continue
                if ("/Android/data/" in path || "/Android/obb/" in path || "/${LocalTrash.DIR_NAME}/" in path) continue
                paths += path
            }
        }
        return paths
    }

    fun removeFromHistory(file: RecentFile) {
        viewModelScope.launch {
            preferences.removeRecent(file.item.path)
            announcer.announce(getApplication<Application>().getString(R.string.recent_removed, file.item.name))
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            preferences.clearRecents()
            announcer.announce(getApplication<Application>().getString(R.string.recents_cleared))
        }
    }

    private companion object {
        const val MAX_SHOWN = 30
    }
}

/**
 * Onglet Récents : « Ouverts récemment » (depuis XFiles) puis « Modifiés récemment » (7 derniers jours).
 * Ordre de balayage : titre → Effacer l'historique → sections → message d'état.
 */
@Composable
fun RecentsScreen(
    onOpen: (String) -> Unit,
    onReveal: (String) -> Unit,
    focusPath: String?,
    viewModel: RecentsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val rowFocus = remember { FocusRequester() }
    val focusTarget = focusPath?.let { target ->
        (state.opened + state.modified).firstOrNull { target == it.item.path || it.item.path.startsWith("$target/") }?.item?.path
    }
    FocusOnceEffect(ready = state.loaded && focusTarget != null) {
        withFrameNanos { }
        rowFocus.requestFocus()
    }

    val openLabel = stringResource(R.string.action_open)
    val openWithLabel = stringResource(R.string.action_open_with)
    val shareLabel = stringResource(R.string.action_share)
    val revealLabel = stringResource(R.string.action_reveal)
    val removeLabel = stringResource(R.string.action_remove_recent)

    @Composable
    fun Row(file: RecentFile) {
        val item = file.item
        val where = file.parents.joinToString(", ")
        val date = file.openedAt?.let { DateFormatter.spoken(it, verb = "ouvert") } ?: DateFormatter.spoken(item.lastModified)
        val spoken = listOfNotNull(
            item.name,
            FileTypeDescriber.describe(item.name, false),
            SizeFormatter.spoken(item.sizeBytes),
            date,
            if (where.isEmpty()) null else stringResource(R.string.favorite_in, where),
        ).joinToString(", ")
        ItemRow(
            icon = itemIcon(item.name, false),
            title = item.name,
            detail = listOf(
                SizeFormatter.short(item.sizeBytes),
                DateFormatter.short(file.openedAt ?: item.lastModified),
                file.parents.joinToString(" › "),
            ).filter { it.isNotEmpty() }.joinToString(" · "),
            spoken = spoken,
            clickLabel = openLabel,
            onClick = { launcher.open(item.path, item.name, false) },
            focusRequester = if (item.path == focusTarget) rowFocus else null,
            actions = buildList {
                add(RowAction(openWithLabel) { launcher.openWith(item.path, item.name) })
                add(RowAction(shareLabel) { launcher.share(item.path, item.name) })
                add(RowAction(revealLabel) { onReveal(item.path) })
                if (file.openedAt != null) add(RowAction(removeLabel) { viewModel.removeFromHistory(file) })
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = stringResource(R.string.tab_recents),
            actions = {
                IconButton(onClick = { confirmClear = true }, enabled = state.opened.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_clear_recents))
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (state.loaded && state.opened.isEmpty() && state.modified.isEmpty()) {
                Text(
                    stringResource(R.string.empty_recents),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(24.dp).align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (state.opened.isNotEmpty()) {
                        item(key = "h-opened") { SectionHeader(stringResource(R.string.section_recent_opened)) }
                        items(state.opened, key = { "o:" + it.item.path }) { Row(it) }
                    }
                    if (state.modified.isNotEmpty()) {
                        item(key = "h-modified") { SectionHeader(stringResource(R.string.section_recent_modified)) }
                        items(state.modified, key = { "m:" + it.item.path }) { Row(it) }
                    }
                }
            }
        }
        StatusMessage(announcement)
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_clear_recents_title),
            message = stringResource(R.string.confirm_clear_recents_message),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                confirmClear = false
                viewModel.clearHistory()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
