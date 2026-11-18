package io.github.thomaszimmer13.xfiles.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.fileItemOf
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Résultat de recherche : l'élément et les dossiers qui le contiennent (« Mémoire interne, Documents »). */
data class SearchResult(val item: FileItem, val parents: List<String>)

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    /** Null tant qu'aucune recherche n'a abouti. */
    val results: List<SearchResult>? = null,
    val truncated: Boolean = false,
)

/** Recherche par nom dans un dossier et ses sous-dossiers (ou dans tous les emplacements). */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val volumes = container.volumeRepository
    private val preferences = container.preferences

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)

    private var scope: String? = null
    private var job: Job? = null

    fun setScope(newScope: String?) {
        if (newScope == scope && _state.value.query.isNotEmpty()) return
        scope = newScope
        job?.cancel()
        _state.value = SearchUiState()
    }

    /**
     * Nouvelle saisie : la recherche part après une courte pause de frappe, et son résultat est annoncé
     * une seule fois (« 12 résultats »), pas à chaque lettre.
     */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searching = false, results = null, truncated = false) }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            val showHidden = preferences.sortOptions.first().showHidden
            val (results, truncated) = withContext(Dispatchers.IO) { search(query, showHidden) }
            _state.update { it.copy(searching = false, results = results, truncated = truncated) }
            val res = getApplication<Application>().resources
            announcer.announce(
                when {
                    truncated -> res.getString(R.string.search_truncated, MAX_RESULTS)
                    results.isEmpty() -> res.getString(R.string.search_none)
                    else -> res.getQuantityString(R.plurals.search_count, results.size, results.size)
                },
            )
        }
    }

    private suspend fun search(query: String, showHidden: Boolean): Pair<List<SearchResult>, Boolean> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        val roots = volumes.volumeRoots()
        val starts = scope?.let { listOf(File(it)) } ?: roots.map { File(it.second) }
        val results = mutableListOf<SearchResult>()
        for (start in starts) {
            // Audit F-05 : les liens symboliques ne sont jamais suivis (boucles sur le stockage externe).
            val walk = start.walkTopDown().onEnter { dir ->
                dir == start || (!java.nio.file.Files.isSymbolicLink(dir.toPath()) && dir.name != LocalTrash.DIR_NAME && !io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer.isReplaceTemp(dir.name) && (showHidden || !dir.name.startsWith(".")))
            }
            for (file in walk) {
                coroutineContext.ensureActive()
                if (file == start || file.name == LocalTrash.DIR_NAME) continue
                // Éléments temporaires d'un remplacement en cours : jamais proposés, même cachés affichés.
                if (io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer.isReplaceTemp(file.name)) continue
                if (!showHidden && file.name.startsWith(".")) continue
                val name = normalize(file.name)
                if (words.all { name.contains(it) }) {
                    val parents = Breadcrumbs.of(file.parent.orEmpty(), roots).map { it.name }
                    results += SearchResult(fileItemOf(file, showHidden), parents)
                    if (results.size >= MAX_RESULTS) return results to true
                }
            }
        }
        return results to false
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val MAX_RESULTS = 300

        /** Sans majuscules ni accents : « éte » trouve « Été 2024 ». */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    }
}
