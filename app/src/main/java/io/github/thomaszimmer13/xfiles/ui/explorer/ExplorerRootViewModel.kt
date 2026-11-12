package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.volumes.LocationKind
import io.github.thomaszimmer13.xfiles.data.volumes.StorageLocation
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dossier favori affiché à la racine d'Explorer. */
data class FavoriteFolder(
    val path: String,
    val name: String,
    /** Dossiers parents, ex. « Mémoire interne, Documents ». */
    val parents: List<String>,
    val exists: Boolean,
)

data class ExplorerRootUiState(
    val locations: List<StorageLocation> = emptyList(),
    val favorites: List<FavoriteFolder> = emptyList(),
)

class ExplorerRootViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val volumes = container.volumeRepository
    private val preferences = container.preferences

    private val _state = MutableStateFlow(ExplorerRootUiState())
    val state: StateFlow<ExplorerRootUiState> = _state.asStateFlow()

    val announcer = Announcer(viewModelScope)

    /** Volumes amovibles connus (chemin → nom) ; null tant que la première liste n'est pas arrivée. */
    private var knownRemovable: Map<String, String>? = null
    private var favoritePaths: List<String> = emptyList()

    init {
        viewModelScope.launch {
            volumes.locations().collect { applyLocations(it) }
        }
        viewModelScope.launch {
            preferences.favoriteFolders.collect { paths ->
                favoritePaths = paths
                refreshFavorites()
            }
        }
    }

    /** À appeler au retour dans l'app : espace libre et existence des favoris ont pu changer. */
    fun refresh() {
        viewModelScope.launch {
            applyLocations(withContext(Dispatchers.IO) { volumes.currentLocations() })
            refreshFavorites()
        }
    }

    fun removeFavorite(favorite: FavoriteFolder) {
        viewModelScope.launch {
            preferences.toggleFavorite(favorite.path)
            announcer.announce(getApplication<Application>().getString(R.string.favorite_removed, favorite.name))
        }
    }

    private suspend fun refreshFavorites() {
        val paths = favoritePaths
        val favorites = withContext(Dispatchers.IO) {
            val roots = volumes.volumeRoots()
            paths.map { path ->
                val crumbs = Breadcrumbs.of(path, roots)
                FavoriteFolder(
                    path = path,
                    name = crumbs.lastOrNull()?.name ?: path,
                    parents = crumbs.dropLast(1).map { it.name },
                    exists = File(path).isDirectory,
                )
            }
        }
        _state.update { it.copy(favorites = favorites) }
    }

    private fun applyLocations(locations: List<StorageLocation>) {
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
        if (messages.isNotEmpty()) announcer.announce(messages.joinToString(" "))
    }
}
