package io.github.thomaszimmer13.xfiles.ui.rename

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.rename.BatchRename
import io.github.thomaszimmer13.xfiles.data.rename.RenameDone
import io.github.thomaszimmer13.xfiles.data.rename.RenamePlanItem
import io.github.thomaszimmer13.xfiles.data.rename.RenameRules
import io.github.thomaszimmer13.xfiles.data.rename.RenameSource
import io.github.thomaszimmer13.xfiles.data.rename.RenameStatus
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Éléments à renommer, posés par le dossier avant d'ouvrir l'écran. */
class PendingRename {
    @Volatile var sources: List<RenameSource> = emptyList()
        private set

    /** Numéro de la demande : un écran (et son état) par demande. */
    @Volatile var generation = 0
        private set

    fun set(items: List<RenameSource>): Int {
        sources = items
        return ++generation
    }
}

data class BatchRenameUiState(
    val sources: List<RenameSource> = emptyList(),
    val rules: RenameRules = RenameRules(),
    val plan: List<RenamePlanItem> = emptyList(),
    val working: Boolean = false,
    /** Renommage fait : on peut l'annuler. */
    val done: List<RenameDone>? = null,
)

class BatchRenameViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val _state = MutableStateFlow(BatchRenameUiState())
    val state: StateFlow<BatchRenameUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private var others: List<String> = emptyList()
    private var summaryJob: Job? = null

    init {
        val sources = container.pendingRename.sources
        others = sources.firstOrNull()?.let { File(it.path).parentFile?.list()?.toList() }.orEmpty()
        _state.value = BatchRenameUiState(sources = sources, plan = BatchRename.plan(sources, others, RenameRules()))
    }

    /** Règles modifiées : l'aperçu se met à jour tout de suite, le résumé est annoncé à la fin de la frappe. */
    fun update(rules: RenameRules) {
        val plan = BatchRename.plan(_state.value.sources, others, rules)
        _state.update { it.copy(rules = rules, plan = plan) }
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            delay(900)
            announcer.announce(summary(plan))
        }
    }

    fun summary(plan: List<RenamePlanItem> = _state.value.plan): String {
        val res = getApplication<Application>().resources
        val renamed = plan.count { it.status == RenameStatus.RENAMED }
        val problems = plan.count { it.status != RenameStatus.RENAMED && it.status != RenameStatus.UNCHANGED }
        if (plan.any { it.status == RenameStatus.BAD_PATTERN }) return res.getString(R.string.rename_bad_pattern)
        val parts = buildList {
            add(res.getQuantityString(R.plurals.rename_summary_renamed, renamed, renamed))
            if (problems > 0) add(res.getQuantityString(R.plurals.rename_summary_problems, problems, problems))
        }
        return parts.joinToString(", ") + "."
    }

    fun apply() {
        val s = _state.value
        if (s.working || s.plan.none { it.status == RenameStatus.RENAMED }) return
        _state.update { it.copy(working = true) }
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val done = withContext(Dispatchers.IO) { BatchRename.apply(s.plan) }
                done.forEach { d -> if (File(d.newPath).isDirectory) container.preferences.onPathRenamed(d.oldPath, d.newPath) }
                container.feedback.success()
                _state.update { it.copy(working = false, done = done) }
                announcer.announce(app.resources.getQuantityString(R.plurals.rename_done, done.size, done.size))
            } catch (e: Exception) {
                container.feedback.failure()
                _state.update { it.copy(working = false) }
                announcer.announce(app.getString(R.string.rename_failed, e.message.orEmpty()))
            }
        }
    }

    fun undo() {
        val done = _state.value.done ?: return
        _state.update { it.copy(working = true) }
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { BatchRename.undo(done) }
                done.forEach { d -> if (File(d.oldPath).isDirectory) container.preferences.onPathRenamed(d.newPath, d.oldPath) }
                _state.update { it.copy(working = false, done = null) }
                announcer.announce(app.getString(R.string.rename_undone))
            } catch (e: Exception) {
                _state.update { it.copy(working = false) }
                announcer.announce(app.getString(R.string.rename_failed, e.message.orEmpty()))
            }
        }
    }
}
