package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.volumes.LocationKind
import io.github.thomaszimmer13.xfiles.data.volumes.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExplorerRootUiState(
    val locations: List<StorageLocation> = emptyList(),
    /** Message à annoncer (branchement / retrait d'un volume), effacé après quelques secondes. */
    val announcement: String? = null,
)

class ExplorerRootViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as XFilesApplication).container.volumeRepository

    private val _state = MutableStateFlow(ExplorerRootUiState())
    val state: StateFlow<ExplorerRootUiState> = _state.asStateFlow()

    /** Volumes amovibles connus (chemin → nom) ; null tant que la première liste n'est pas arrivée. */
    private var knownRemovable: Map<String, String>? = null
    private var clearJob: Job? = null

    init {
        viewModelScope.launch {
            repository.locations().collect { apply(it) }
        }
    }

    /** À appeler au retour dans l'app : met à jour l'espace libre. */
    fun refresh() {
        viewModelScope.launch {
            apply(withContext(Dispatchers.IO) { repository.currentLocations() })
        }
    }

    private fun apply(locations: List<StorageLocation>) {
        val removable = locations.filter { it.kind == LocationKind.REMOVABLE }.associate { it.path to it.name }
        val previous = knownRemovable
        knownRemovable = removable

        val messages = mutableListOf<String>()
        if (previous != null) {
            val app = getApplication<Application>()
            (removable.keys - previous.keys).forEach {
                messages += app.getString(R.string.location_added, removable.getValue(it))
            }
            (previous.keys - removable.keys).forEach {
                messages += app.getString(R.string.location_removed, previous.getValue(it))
            }
        }

        _state.update { it.copy(locations = locations) }
        if (messages.isNotEmpty()) announce(messages.joinToString(" "))
    }

    private fun announce(message: String) {
        clearJob?.cancel()
        _state.update { it.copy(announcement = message) }
        clearJob = viewModelScope.launch {
            delay(ANNOUNCEMENT_DURATION_MS)
            _state.update { it.copy(announcement = null) }
        }
    }

    private companion object {
        const val ANNOUNCEMENT_DURATION_MS = 10_000L
    }
}
