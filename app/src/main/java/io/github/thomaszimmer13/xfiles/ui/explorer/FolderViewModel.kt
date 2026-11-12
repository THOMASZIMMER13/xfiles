package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.domain.Crumb
import io.github.thomaszimmer13.xfiles.domain.FileSorter
import io.github.thomaszimmer13.xfiles.domain.NameCheck
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.domain.SortKey
import io.github.thomaszimmer13.xfiles.domain.SortOptions
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderUiState(
    val path: String? = null,
    val crumbs: List<Crumb> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val sort: SortOptions = SortOptions(),
    /** Élément sur lequel placer le focus TalkBack (après une création ou un renommage). */
    val focusRequest: String? = null,
)

/** Contenu du dossier affiché dans l'onglet Explorer. */
class FolderViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val fileSource = container.fileSource
    private val volumes = container.volumeRepository
    private val preferences = container.preferences

    private val _state = MutableStateFlow(FolderUiState())
    val state: StateFlow<FolderUiState> = _state.asStateFlow()

    val announcer = Announcer(viewModelScope)

    /** Contenu brut du dossier, avant tri. */
    private var rawItems: List<FileItem> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.sortOptions.collect { options ->
                val previous = _state.value.sort
                _state.update { it.copy(sort = options, items = FileSorter.sort(rawItems, options)) }
                // Afficher ou masquer les fichiers cachés demande de relire le dossier.
                val path = _state.value.path
                if (path != null && previous.showHidden != options.showHidden) reload(path)
            }
        }
        viewModelScope.launch {
            preferences.favoriteFolders.collect { paths -> _state.update { it.copy(favorites = paths.toSet()) } }
        }
    }

    fun load(path: String) {
        _state.update {
            FolderUiState(
                path = path,
                crumbs = Breadcrumbs.of(path, volumes.volumeRoots()),
                loading = true,
                favorites = it.favorites,
                sort = it.sort,
            )
        }
        reload(path)
    }

    private fun reload(path: String) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = false) }
        loadJob = viewModelScope.launch {
            try {
                rawItems = fileSource.list(path, includeHidden = _state.value.sort.showHidden)
                _state.update { it.copy(items = FileSorter.sort(rawItems, it.sort), loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                rawItems = emptyList()
                _state.update { it.copy(items = emptyList(), loading = false, error = true) }
            }
        }
    }

    /** Contenu relu sans passer par l'état « Chargement… », pour ne pas faire perdre le focus. */
    private suspend fun refreshQuietly(path: String) {
        rawItems = fileSource.list(path, includeHidden = _state.value.sort.showHidden)
        _state.update { it.copy(items = FileSorter.sort(rawItems, it.sort), error = false) }
    }

    fun consumeFocusRequest() {
        _state.update { it.copy(focusRequest = null) }
    }

    /** Vérifie un nom avant création ou renommage ([current] = nom actuel en cas de renommage). */
    fun checkName(name: String, current: String? = null): NameCheck =
        NameValidator.check(name, rawItems.map { it.name }, current)

    /** Nom proposé par défaut pour un nouveau dossier, sans doublon. */
    fun suggestedFolderName(base: String): String = NameValidator.uniqueName(base, rawItems.map { it.name })

    fun createFolder(name: String) {
        val parent = _state.value.path ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val created = fileSource.createDirectory(parent, name)
                refreshQuietly(parent)
                _state.update { it.copy(focusRequest = created) }
                announcer.announce(app.getString(R.string.folder_created, name))
            } catch (e: FileOperationException) {
                announcer.announce(app.getString(e.reason.message(), name))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announcer.announce(app.getString(R.string.op_failed, name))
            }
        }
    }

    fun rename(item: FileItem, newName: String) {
        val parent = _state.value.path ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val renamed = fileSource.rename(item.path, newName)
                if (item.isDirectory) preferences.onPathRenamed(item.path, renamed)
                refreshQuietly(parent)
                _state.update { it.copy(focusRequest = renamed) }
                announcer.announce(app.getString(R.string.renamed, newName))
            } catch (e: FileOperationException) {
                announcer.announce(app.getString(e.reason.message(), item.name))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announcer.announce(app.getString(R.string.op_failed, item.name))
            }
        }
    }

    /** Appelé par le dialogue « Trier et afficher » ; annonce le résultat. */
    fun applySort(options: SortOptions) {
        val previous = _state.value.sort
        viewModelScope.launch {
            preferences.setSortOptions(options)
            val app = getApplication<Application>()
            val messages = mutableListOf(
                // « Tri : date de modification, plus récents d'abord. » (« de A à Z » garde ses majuscules)
                app.getString(
                    R.string.sort_applied,
                    app.getString(options.key.label()).lowercase(),
                    app.getString(orderLabel(options)).replaceFirstChar { it.lowercase() },
                ),
            )
            if (previous.showHidden != options.showHidden) {
                messages += app.getString(if (options.showHidden) R.string.hidden_shown else R.string.hidden_hidden)
            }
            announcer.announce(messages.joinToString(" "))
        }
    }

    fun toggleFavorite(item: FileItem) {
        viewModelScope.launch {
            val nowFavorite = preferences.toggleFavorite(item.path)
            val app = getApplication<Application>()
            announcer.announce(
                app.getString(if (nowFavorite) R.string.favorite_added else R.string.favorite_removed, item.name),
            )
        }
    }
}

/** Message annoncé pour un échec d'opération (%1$s : nom concerné). */
private fun FileOperationException.Reason.message(): Int = when (this) {
    FileOperationException.Reason.ALREADY_EXISTS -> R.string.op_already_exists
    FileOperationException.Reason.NOT_FOUND -> R.string.op_not_found
    FileOperationException.Reason.DENIED -> R.string.op_denied
    FileOperationException.Reason.FAILED -> R.string.op_failed
}

/** Libellé d'un critère de tri. */
fun SortKey.label(): Int = when (this) {
    SortKey.NAME -> R.string.sort_name
    SortKey.DATE -> R.string.sort_date
    SortKey.SIZE -> R.string.sort_size
    SortKey.TYPE -> R.string.sort_type
}

/** Libellé du sens de tri, adapté au critère (« Plus récents d'abord », « De A à Z »…). */
fun orderLabel(options: SortOptions): Int = orderLabel(options.key, options.descending)

fun orderLabel(key: SortKey, descending: Boolean): Int = when (key) {
    SortKey.NAME, SortKey.TYPE -> if (descending) R.string.order_za else R.string.order_az
    SortKey.DATE -> if (descending) R.string.order_recent_first else R.string.order_oldest_first
    SortKey.SIZE -> if (descending) R.string.order_largest_first else R.string.order_smallest_first
}
