package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** Un élément du fil d'Ariane : nom affiché et chemin. */
data class Crumb(val name: String, val path: String)

data class FolderUiState(
    val path: String? = null,
    val crumbs: List<Crumb> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
)

/** Contenu du dossier affiché dans l'onglet Explorer. */
class FolderViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val fileSource = container.fileSource
    private val volumes = container.volumeRepository

    private val _state = MutableStateFlow(FolderUiState())
    val state: StateFlow<FolderUiState> = _state.asStateFlow()

    // Tri alphabétique français, insensible à la casse, dossiers d'abord.
    private val collator = Collator.getInstance(Locale.FRANCE).apply { strength = Collator.SECONDARY }
    private val order = compareBy<FileItem> { !it.isDirectory }.thenComparator { a, b -> collator.compare(a.name, b.name) }

    private var loadJob: Job? = null

    fun load(path: String) {
        loadJob?.cancel()
        _state.value = FolderUiState(path = path, crumbs = crumbsFor(path), loading = true)
        loadJob = viewModelScope.launch {
            try {
                val items = fileSource.list(path).sortedWith(order)
                _state.update { it.copy(items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** « Stockage interne › Documents › Factures » à partir de « /storage/emulated/0/Documents/Factures ». */
    private fun crumbsFor(path: String): List<Crumb> {
        val volume = volumes.volumeRoots()
            .filter { (_, root) -> path == root || path.startsWith("$root/") }
            .maxByOrNull { (_, root) -> root.length }

        val crumbs = mutableListOf<Crumb>()
        var base = ""
        if (volume != null) {
            crumbs += Crumb(volume.first, volume.second)
            base = volume.second
        }
        path.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { segment ->
            base = "$base/$segment"
            crumbs += Crumb(segment, base)
        }
        return crumbs
    }
}
