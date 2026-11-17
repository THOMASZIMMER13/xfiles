package io.github.thomaszimmer13.xfiles.ui.storage

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.FileTypeDescriber
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.analysis.FoundFile
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.LocalAnnouncePosition
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.explorer.TrashConfirmDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class CleanupKind { BIG, DUPLICATES, OLD_DOWNLOADS }

class CleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val analyzer = container.analyzer
    val analysis = analyzer.state
    val confirmTrash = container.preferences.confirmTrash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private val roots by lazy { container.volumeRepository.volumeRoots() }

    /** « Mémoire interne, Download » : où se trouve le fichier. */
    fun parents(path: String): List<String> = Breadcrumbs.of(File(path).parent.orEmpty(), roots).map { it.name }

    fun toggle(path: String) = _selected.update { if (path in it) it - path else it + path }

    fun setAll(paths: Collection<String>, select: Boolean) = _selected.update { if (select) it + paths else it - paths.toSet() }

    /** Doublons : coche toutes les copies sauf la plus ancienne de chaque groupe. */
    fun keepOldest() {
        val groups = analysis.value.result?.duplicates.orEmpty()
        _selected.value = groups.flatMap { g -> g.files.drop(1).map { it.path } }.toSet()
        val count = _selected.value.size
        announcer.announce(getApplication<Application>().resources.getQuantityString(R.plurals.cleanup_selected, count, count))
    }

    /** Supprime [paths] (les fichiers cochés de la liste affichée). */
    fun removeSelected(paths: Set<String>, sizes: Map<String, Long>, permanent: Boolean) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = removeAll(paths, permanent, container.trash, container.fileSource)
            analyzer.forget(result.removed)
            _selected.value = _selected.value - result.removed
            val freed = result.removed.sumOf { sizes[it] ?: 0L }
            val n = result.removed.size
            val message = buildList {
                if (n > 0) {
                    add(app.resources.getQuantityString(if (permanent) R.plurals.deleted_forever_many else R.plurals.moved_to_trash_many, n, n))
                    add(app.getString(R.string.cleanup_freed, SizeFormatter.spoken(freed)))
                }
                if (result.failed > 0) add(app.resources.getQuantityString(R.plurals.op_failures, result.failed, result.failed))
            }.joinToString(" ")
            if (result.failed > 0 && n == 0) container.feedback.failure() else container.feedback.success()
            announcer.announce(message)
        }
    }
}

/**
 * Liste à cocher d'une catégorie de nettoyage. Chaque fichier est une case à cocher TalkBack :
 * « Film.mkv, vidéo, 2,1 gigaoctets, modifié le 3 mars, dans Mémoire interne, Movies, 1 sur 12, case cochée ».
 * Doublons : un titre par groupe (« Groupe 2 : 3 copies de 45 mégaoctets »), navigable par titres.
 * En bas : « Mettre à la corbeille (3 fichiers, 1,2 gigaoctet) ».
 */
@Composable
fun CleanupScreen(kind: CleanupKind, onReveal: (String) -> Unit, onBack: () -> Unit, viewModel: CleanupViewModel = viewModel()) {
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val confirmTrash by viewModel.confirmTrash.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    val result = analysis.result
    val files: List<FoundFile> = when (kind) {
        CleanupKind.BIG -> result?.bigFiles.orEmpty()
        CleanupKind.OLD_DOWNLOADS -> result?.oldDownloads.orEmpty()
        CleanupKind.DUPLICATES -> result?.duplicates.orEmpty().flatMap { it.files }
    }
    val sizes = remember(files) { files.associate { it.path to it.size } }
    val chosen = selected.filter { it in sizes }
    val chosenBytes = chosen.sumOf { sizes[it] ?: 0L }
    val allSelected = files.isNotEmpty() && chosen.size == files.size

    val title = stringResource(
        when (kind) {
            CleanupKind.BIG -> R.string.cleanup_big
            CleanupKind.DUPLICATES -> R.string.cleanup_duplicates
            CleanupKind.OLD_DOWNLOADS -> R.string.cleanup_downloads
        },
    )
    val selectedText = pluralStringResource(R.plurals.cleanup_selected_size, chosen.size, chosen.size, SizeFormatter.short(chosenBytes))
    val selectedSpoken = pluralStringResource(R.plurals.cleanup_selected_size, chosen.size, chosen.size, SizeFormatter.spoken(chosenBytes))

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = title, spokenTitle = "$title, ${files.size}", onBack = onBack, titleFocus = titleFocus)
        if (files.isEmpty()) {
            Centered(stringResource(if (result == null) R.string.cleanup_no_analysis else R.string.cleanup_nothing))
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { viewModel.setAll(files.map { it.path }, !allSelected) }) {
                Text(stringResource(if (allSelected) R.string.action_unselect_all else R.string.action_select_all))
            }
            if (kind == CleanupKind.DUPLICATES) {
                TextButton(onClick = viewModel::keepOldest) { Text(stringResource(R.string.action_keep_oldest)) }
            }
        }
        val position = LocalAnnouncePosition.current
        val revealLabel = stringResource(R.string.action_reveal)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (kind == CleanupKind.DUPLICATES) {
                result?.duplicates.orEmpty().forEachIndexed { g, group ->
                    item(key = "g$g") {
                        SectionHeader(
                            stringResource(R.string.cleanup_group, g + 1, group.files.size, SizeFormatter.spoken(group.size)),
                        )
                    }
                    itemsIndexed(group.files, key = { _, it -> it.path }) { i, file ->
                        val copy = stringResource(R.string.cleanup_copy, i + 1, group.files.size) +
                            if (i == 0) ", " + stringResource(R.string.cleanup_oldest) else ""
                        CheckRow(file, viewModel.parents(file.path), file.path in selected, copy, revealLabel, onReveal) { viewModel.toggle(file.path) }
                    }
                }
            } else {
                itemsIndexed(files, key = { _, it -> it.path }) { i, file ->
                    val pos = if (position && files.size > 1) stringResource(R.string.position_only, i + 1, files.size) else null
                    CheckRow(file, viewModel.parents(file.path), file.path in selected, pos, revealLabel, onReveal) { viewModel.toggle(file.path) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(selectedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { contentDescription = selectedSpoken })
            Button(
                onClick = { if (confirmTrash) confirm = true else viewModel.removeSelected(chosen.toSet(), sizes, permanent = false) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.action_move_to_trash)) }
        }
        StatusMessage(announcement)
    }

    if (confirm && chosen.isNotEmpty()) {
        val n = chosen.size
        TrashConfirmDialog(
            trashMessage = pluralStringResource(R.plurals.confirm_trash_many, n, n),
            deleteMessage = pluralStringResource(R.plurals.confirm_delete_many, n, n),
            onMoveToTrash = {
                confirm = false
                viewModel.removeSelected(chosen.toSet(), sizes, permanent = false)
            },
            onDeleteForever = {
                confirm = false
                viewModel.removeSelected(chosen.toSet(), sizes, permanent = true)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Un fichier à cocher : un seul élément TalkBack, rôle case à cocher, action « Afficher dans le dossier ». */
@Composable
private fun CheckRow(
    file: FoundFile,
    parents: List<String>,
    checked: Boolean,
    extra: String?,
    revealLabel: String,
    onReveal: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val where = parents.joinToString(", ")
    val spoken = listOfNotNull(
        file.name,
        FileTypeDescriber.describe(file.name, false),
        SizeFormatter.spoken(file.size),
        DateFormatter.spoken(file.lastModified),
        if (where.isEmpty()) null else stringResource(R.string.favorite_in, where),
        extra,
    ).joinToString(", ")
    val toggleLabel = stringResource(if (checked) R.string.toggle_unselect else R.string.toggle_select)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusable()
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Checkbox
                    toggleableState = ToggleableState(checked)
                    onClick(label = toggleLabel) { onToggle(); true }
                    customActions = listOf(CustomAccessibilityAction(revealLabel) { onReveal(file.path); true })
                }
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(SizeFormatter.short(file.size), DateFormatter.short(file.lastModified), parents.joinToString(" › ")).filter { it.isNotEmpty() }.joinToString(" · "),
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
