package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.media.Categories
import io.github.thomaszimmer13.xfiles.data.media.Category
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
    /** Nombre d'éléments dans la corbeille ; null tant qu'il n'est pas connu. */
    val trashCount: Int? = null,
    /** Nombre de fichiers par catégorie ; absent tant qu'il n'est pas connu. */
    val categoryCounts: Map<Category, Int> = emptyMap(),
    /** Serveurs réseau enregistrés. */
    val servers: List<io.github.thomaszimmer13.xfiles.data.remote.RemoteServer> = emptyList(),
    /** Coffres chiffrés, et ceux qui sont ouverts. */
    val vaults: List<io.github.thomaszimmer13.xfiles.data.remote.RemoteServer> = emptyList(),
    val openVaults: Set<String> = emptySet(),
    /** « Données des applications » (Shizuku) affiché, et son état. */
    val shizukuShown: Boolean = false,
    val shizukuReady: Boolean = false,
)

class ExplorerRootViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val volumes = container.volumeRepository
    private val preferences = container.preferences
    private val trash = container.trash

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
            container.servers.load()
            container.servers.servers.collect { list ->
                val (vaults, servers) = list.partition { it.protocol == io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT }
                _state.update { it.copy(servers = servers, vaults = vaults) }
            }
        }
        viewModelScope.launch {
            container.vaultKeys.open.collect { open -> _state.update { it.copy(openVaults = open.keys) } }
        }
        viewModelScope.launch {
            preferences.shizukuInExplorer.collect { shown -> _state.update { it.copy(shizukuShown = shown) } }
        }
        viewModelScope.launch {
            container.shizuku.state.collect { s -> _state.update { it.copy(shizukuReady = s == io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuState.READY) } }
        }
        viewModelScope.launch {
            preferences.favoriteFolders.collect { paths ->
                favoritePaths = paths
                refreshFavorites()
            }
        }
        viewModelScope.launch {
            // Une fois par lancement : suppression des éléments de plus de 30 jours.
            withContext(Dispatchers.IO) { runCatching { trash.purgeExpired() } }
            refreshTrashCount()
        }
    }

    fun lockVault(server: io.github.thomaszimmer13.xfiles.data.remote.RemoteServer) {
        container.vaults.lock(server.id)
        announcer.announce(getApplication<Application>().getString(io.github.thomaszimmer13.xfiles.R.string.vault_locked_now, server.name))
    }

    fun forgetVaultBio(server: io.github.thomaszimmer13.xfiles.data.remote.RemoteServer) {
        viewModelScope.launch {
            container.vaults.forgetSeal(server)
            announcer.announce(getApplication<Application>().getString(io.github.thomaszimmer13.xfiles.R.string.vault_bio_off))
        }
    }

    fun removeVault(server: io.github.thomaszimmer13.xfiles.data.remote.RemoteServer) {
        viewModelScope.launch {
            container.vaults.remove(server)
            announcer.announce(getApplication<Application>().getString(io.github.thomaszimmer13.xfiles.R.string.vault_removed, server.name))
        }
    }

    /** À appeler au retour dans l'app : espace libre et existence des favoris ont pu changer. */
    fun refresh() {
        viewModelScope.launch {
            applyLocations(withContext(Dispatchers.IO) { volumes.currentLocations() })
            refreshFavorites()
            refreshTrashCount()
            refreshCategories()
        }
    }

    private suspend fun refreshCategories() {
        val counts = withContext(Dispatchers.IO) {
            Category.entries.associateWith { c -> runCatching { Categories.count(getApplication(), c) }.getOrDefault(0) }
        }
        _state.update { it.copy(categoryCounts = counts) }
    }

    private suspend fun refreshTrashCount() {
        val count = withContext(Dispatchers.IO) { runCatching { trash.count() }.getOrDefault(0) }
        _state.update { it.copy(trashCount = count) }
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
            // Ici, seulement les dossiers ; les fichiers favoris sont dans l'onglet Favoris.
            paths.filterNot { File(it).isFile }.map { path ->
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
