package io.github.thomaszimmer13.xfiles.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.trash.TrashEntry
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.explorer.FocusRequest
import io.github.thomaszimmer13.xfiles.ui.explorer.message
import io.github.thomaszimmer13.xfiles.ui.explorer.neighborFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Élément de la corbeille, avec son dossier d'origine prêt à afficher (« Mémoire interne, Documents »). */
data class TrashItem(val entry: TrashEntry, val origin: List<String>) {
    /** Identifiant stable dans la liste (deux éléments peuvent porter le même nom). */
    val key: String get() = entry.storedPath
}

data class TrashUiState(
    val items: List<TrashItem> = emptyList(),
    val loading: Boolean = true,
    val focusRequest: FocusRequest? = null,
)

class TrashViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val trash = container.trash
    private val volumes = container.volumeRepository

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    val announcer = Announcer(viewModelScope)

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { trash.purgeExpired() } }
            refresh()
        }
    }

    /** Relit la corbeille (au retour dans l'app, une autre app a pu y toucher). */
    fun reload() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val items = withContext(Dispatchers.IO) {
            val roots = volumes.volumeRoots()
            runCatching { trash.list() }.getOrDefault(emptyList()).map { entry ->
                val parent = File(entry.originalPath).parent.orEmpty()
                TrashItem(entry, Breadcrumbs.of(parent, roots).map { it.name })
            }
        }
        _state.update { it.copy(items = items, loading = false) }
    }

    fun consumeFocusRequest() {
        _state.update { it.copy(focusRequest = null) }
    }

    fun restore(item: TrashItem) = act(item) {
        val restoredPath = trash.restore(item.entry)
        val app = getApplication<Application>()
        val restoredName = File(restoredPath).name
        val where = item.origin.lastOrNull().orEmpty()
        if (restoredName == item.entry.name) {
            app.getString(R.string.restored, item.entry.name, where)
        } else {
            app.getString(R.string.restored_renamed, item.entry.name, where, restoredName)
        }
    }

    fun deleteForever(item: TrashItem) = act(item) {
        trash.deleteForever(item.entry)
        getApplication<Application>().getString(R.string.deleted_forever, item.entry.name)
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val message = try {
                withContext(Dispatchers.IO) { trash.empty() }
                app.getString(R.string.trash_emptied)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                app.getString(R.string.trash_empty_failed)
            }
            refresh()
            _state.update { it.copy(focusRequest = FocusRequest.Title) }
            announcer.announce(message)
        }
    }

    /** Exécute une opération sur un élément, puis relit la corbeille et place le focus sur son voisin. */
    private fun act(item: TrashItem, operation: () -> String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // En cas d'échec l'élément reste là : le focus y revient.
            var focus: FocusRequest = FocusRequest.Item(item.key)
            val message = try {
                val neighbor = neighborFocus(_state.value.items, item.key) { it.key }
                withContext(Dispatchers.IO) { operation() }.also { focus = neighbor }
            } catch (e: FileOperationException) {
                app.getString(e.reason.message(), item.entry.name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                app.getString(R.string.op_failed, item.entry.name)
            }
            refresh()
            _state.update { it.copy(focusRequest = focus) }
            announcer.announce(message)
        }
    }
}
