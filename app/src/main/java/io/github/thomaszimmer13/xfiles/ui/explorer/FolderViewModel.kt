package io.github.thomaszimmer13.xfiles.ui.explorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.domain.Crumb
import io.github.thomaszimmer13.xfiles.domain.FileSorter
import io.github.thomaszimmer13.xfiles.domain.NameCheck
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.domain.SortKey
import io.github.thomaszimmer13.xfiles.domain.SortOptions
import io.github.thomaszimmer13.xfiles.data.archive.CreateFormat
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequest
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.PasswordGate
import io.github.thomaszimmer13.xfiles.data.transfer.ClipContent
import io.github.thomaszimmer13.xfiles.data.transfer.ClipItem
import io.github.thomaszimmer13.xfiles.data.transfer.TransferMode
import io.github.thomaszimmer13.xfiles.data.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderUiState(
    val path: String? = null,
    val crumbs: List<Crumb> = emptyList(),
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val sort: SortOptions = SortOptions(),
    /** Où placer le focus TalkBack après une création, un renommage ou une suppression. */
    val focusRequest: FocusRequest? = null,
    /** Mode sélection : un appui double coche ou décoche au lieu d'ouvrir. */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** Contenu du presse-papiers de fichiers (Copier / Déplacer), commun à tous les dossiers. */
    val clipboard: ClipContent? = null,
    /** Opération longue en cours (dans n'importe quel dossier). */
    val transfer: TransferProgress? = null,
    /** Réglage : confirmation avant de mettre à la corbeille. */
    val confirmTrash: Boolean = true,
    /** Éléments à compresser : le dialogue « Compresser » est ouvert. */
    val compressItems: List<FileItem>? = null,
)

sealed interface FocusRequest {
    data class Item(val path: String) : FocusRequest

    /** Titre de l'écran (la liste est devenue vide). */
    data object Title : FocusRequest
}

/** Élément voisin de [path] dans [items] (le suivant, sinon le précédent), pour y poser le focus après sa disparition. */
internal fun <T> neighborFocus(items: List<T>, path: String, pathOf: (T) -> String): FocusRequest =
    focusAfterRemoval(items.map(pathOf), setOf(path))

/**
 * Où placer le focus quand les éléments [removed] disparaissent de la liste [before] :
 * sur le premier élément restant après le premier supprimé, sinon sur le dernier restant avant,
 * sinon sur le titre.
 */
internal fun focusAfterRemoval(before: List<String>, removed: Set<String>): FocusRequest {
    val first = before.indexOfFirst { it in removed }
    if (first < 0) return FocusRequest.Title
    val after = before.drop(first).firstOrNull { it !in removed }
    val previous = before.take(first).lastOrNull()
    return (after ?: previous)?.let { FocusRequest.Item(it) } ?: FocusRequest.Title
}

/** Contenu du dossier affiché dans l'onglet Explorer. */
class FolderViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as XFilesApplication).container
    private val fileSource = container.fileSource
    private val volumes = container.volumeRepository
    private val preferences = container.preferences
    private val trash = container.trash
    private val clipboard = container.clipboard
    private val transfers = container.transfers

    private val _state = MutableStateFlow(FolderUiState())
    val state: StateFlow<FolderUiState> = _state.asStateFlow()

    val announcer = Announcer(viewModelScope)
    val passwordGate = PasswordGate(viewModelScope)
    private val incoming = container.incoming

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
            clipboard.content.collect { clip -> _state.update { it.copy(clipboard = clip) } }
        }
        viewModelScope.launch {
            preferences.confirmTrash.collect { value -> _state.update { it.copy(confirmTrash = value) } }
        }
        viewModelScope.launch {
            transfers.progress.collect { p -> _state.update { it.copy(transfer = p) } }
        }
        viewModelScope.launch {
            // Fin d'une opération : le dossier affiché a pu changer ; focus sur l'élément créé s'il est ici.
            transfers.outcomes.collect { outcome ->
                val path = _state.value.path ?: return@collect
                runCatching { refreshQuietly(path) }
                if (outcome.targetDir == path && outcome.focusPath != null) {
                    _state.update { it.copy(focusRequest = FocusRequest.Item(outcome.focusPath)) }
                }
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
                clipboard = it.clipboard,
                transfer = it.transfer,
                confirmTrash = it.confirmTrash,
            )
        }
        reload(path)
        viewModelScope.launch { preferences.recordFolder(path) }
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

    /** Relit le dossier affiché sans perdre le focus (après l'ajout d'un fichier, par exemple). */
    fun refreshNow() {
        val path = _state.value.path ?: return
        viewModelScope.launch { runCatching { refreshQuietly(path) } }
    }

    /** Contenu relu sans passer par l'état « Chargement… », pour ne pas faire perdre le focus. */
    private suspend fun refreshQuietly(path: String) {
        rawItems = fileSource.list(path, includeHidden = _state.value.sort.showHidden)
        val present = rawItems.mapTo(HashSet()) { it.path }
        _state.update {
            it.copy(items = FileSorter.sort(rawItems, it.sort), error = false, selected = it.selected intersect present)
        }
    }

    // --- Sélection multiple ---

    /**
     * Entre en mode sélection.
     * @param first élément à cocher d'emblée (action « Sélectionner » sur une ligne : le focus y reste) ;
     * null depuis le bouton de la barre du haut, qui disparaît : le focus va alors sur le titre.
     */
    fun startSelection(first: FileItem? = null) {
        _state.update {
            it.copy(
                selecting = true,
                selected = setOfNotNull(first?.path),
                focusRequest = if (first == null) FocusRequest.Title else it.focusRequest,
            )
        }
        if (first != null) {
            val app = getApplication<Application>()
            announcer.announce(app.getString(R.string.selection_started_with, FileDescriptions.selectedCount(1)))
        }
    }

    /** @param moveFocusToTitle vrai quand le bouton « Terminer la sélection », qui disparaît, avait le focus. */
    fun endSelection(moveFocusToTitle: Boolean) {
        if (!_state.value.selecting) return
        _state.update {
            it.copy(
                selecting = false,
                selected = emptySet(),
                focusRequest = if (moveFocusToTitle) FocusRequest.Title else it.focusRequest,
            )
        }
        announcer.announce(getApplication<Application>().getString(R.string.selection_ended))
    }

    /** Coche ou décoche ; TalkBack dit « coché », puis le nouveau total est annoncé. */
    fun toggleSelection(item: FileItem) {
        _state.update {
            it.copy(selected = if (item.path in it.selected) it.selected - item.path else it.selected + item.path)
        }
        announcer.announce(FileDescriptions.selectedCount(_state.value.selected.size).replaceFirstChar { it.uppercase() } + ".")
    }

    fun toggleSelectAll() {
        _state.update {
            val all = it.items.mapTo(HashSet()) { item -> item.path }
            it.copy(selected = if (it.selected.containsAll(all)) emptySet() else all)
        }
        announcer.announce(FileDescriptions.selectedCount(_state.value.selected.size).replaceFirstChar { it.uppercase() } + ".")
    }

    fun selectedItems(): List<FileItem> = _state.value.let { s -> s.items.filter { it.path in s.selected } }

    fun moveSelectionToTrash() = runOnSelection(R.plurals.moved_to_trash_many) {
        withContext(Dispatchers.IO) { trash.moveToTrash(it.path) }
    }

    // --- Copier, déplacer, coller ---

    /** Met des éléments dans le presse-papiers ; quitte le mode sélection et explique la suite. */
    fun putInClipboard(items: List<FileItem>, mode: TransferMode) {
        if (items.isEmpty()) return
        clipboard.set(items.map { ClipItem(it.path, it.name, it.isDirectory) }, mode)
        _state.update { it.copy(selecting = false, selected = emptySet()) }
        val res = getApplication<Application>().resources
        val plural = if (mode == TransferMode.COPY) R.plurals.clipboard_copy_ready else R.plurals.clipboard_move_ready
        announcer.announce(res.getQuantityString(plural, items.size, items.size, items.first().name))
    }

    /** Double panneau : copie ou déplace [items] directement dans le dossier de l'autre panneau. */
    fun sendTo(items: List<FileItem>, mode: TransferMode, dir: String) {
        if (items.isEmpty()) return
        if (dir == _state.value.path) return announcer.announce(getApplication<Application>().getString(R.string.pane_same_folder))
        if (items.any { it.isDirectory && (dir == it.path || dir.startsWith(it.path + "/")) }) {
            return announcer.announce(getApplication<Application>().getString(R.string.pane_into_itself))
        }
        _state.update { it.copy(selecting = false, selected = emptySet()) }
        val clip = ClipContent(items.map { ClipItem(it.path, it.name, it.isDirectory) }, mode)
        startOrBusy { transfers.paste(clip, dir, java.io.File(dir).name, focusResult = false) }
    }

    fun sendSelectionTo(mode: TransferMode, dir: String) = sendTo(selectedItems(), mode, dir)

    /** Pose la sélection (dans l'ordre affiché) pour le renommage par lot et quitte la sélection. */
    fun prepareBatchRename(): Int {
        val items = selectedItems().map { io.github.thomaszimmer13.xfiles.data.rename.RenameSource(it.path, it.name, it.lastModified, it.isDirectory) }
        val generation = (getApplication<Application>() as io.github.thomaszimmer13.xfiles.XFilesApplication).container.pendingRename.set(items)
        endSelection(moveFocusToTitle = false)
        return generation
    }

    fun putSelectionInClipboard(mode: TransferMode) = putInClipboard(selectedItems(), mode)

    fun clearClipboard() {
        clipboard.clear()
        announcer.announce(getApplication<Application>().getString(R.string.clipboard_cleared))
    }

    /** Colle le presse-papiers ici ; l'opération continue même si l'on quitte le dossier ou l'app. */
    fun paste() {
        val clip = clipboard.content.value ?: return
        val dir = _state.value.path ?: return
        startOrBusy { transfers.paste(clip, dir, folderName()) }
    }

    /** Extrait toute l'archive dans le dossier affiché (« Photos.zip » → dossier « Photos »), mot de passe demandé si besoin. */
    fun extractHere(item: FileItem) {
        val dir = _state.value.path ?: return
        val folder = folderName()
        passwordGate.run(item.path, item.name) { password ->
            startOrBusy { transfers.extract(item.path, null, dir, folder, password) }
        }
    }

    /** Ouvre le dialogue « Compresser » pour [items]. */
    fun compress(items: List<FileItem>) {
        if (items.isEmpty()) return
        _state.update { it.copy(selecting = false, selected = emptySet(), compressItems = items) }
    }

    fun dismissCompress() {
        _state.update { it.copy(compressItems = null) }
    }

    /** Nom proposé (sans extension) : celui de l'élément, ou « Archive » pour plusieurs. */
    fun suggestedArchiveName(items: List<FileItem>): String = if (items.size == 1) {
        val item = items[0]
        if (item.isDirectory) item.name else item.name.substringBeforeLast('.').ifEmpty { item.name }
    } else {
        getApplication<Application>().getString(R.string.archive_default_name)
    }

    fun compressWith(name: String, format: CreateFormat, password: String?) {
        val items = _state.value.compressItems ?: return
        val dir = _state.value.path ?: return
        _state.update { it.copy(compressItems = null) }
        startOrBusy { transfers.compress(items.map { it.path }, dir, folderName(), format, password, name) }
    }

    /** « Dupliquer » : copie à côté de l'original (« Rapport 2.pdf »), focus sur la copie. */
    fun duplicate(item: FileItem) {
        val dir = _state.value.path ?: return
        startOrBusy { transfers.duplicate(listOf(ClipItem(item.path, item.name, item.isDirectory)), dir, folderName()) }
    }

    /** Partage vers XFiles : enregistre les fichiers reçus dans le dossier affiché. */
    fun saveIncomingHere() {
        val request = incoming.current.value as? IncomingRequest.Save ?: return
        val dir = _state.value.path ?: return
        if (transfers.import(request.uris, dir, folderName())) {
            incoming.clear()
        } else {
            announcer.announce(getApplication<Application>().getString(R.string.transfer_busy))
        }
    }

    fun compressSelection() = compress(selectedItems())

    private fun folderName() = _state.value.crumbs.lastOrNull()?.name.orEmpty()

    private fun startOrBusy(start: () -> Boolean) {
        if (!start()) announcer.announce(getApplication<Application>().getString(R.string.transfer_busy))
    }

    fun deleteSelectionForever() = runOnSelection(R.plurals.deleted_forever_many) { fileSource.delete(it.path) }

    /**
     * Applique [operation] à chaque élément sélectionné, puis quitte le mode sélection
     * et annonce le bilan en une seule phrase (« 3 éléments mis à la corbeille. »).
     */
    private fun runOnSelection(doneMessage: Int, operation: suspend (FileItem) -> Unit) {
        val parent = _state.value.path ?: return
        val targets = selectedItems()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val done = mutableSetOf<String>()
            for (item in targets) {
                try {
                    operation(item)
                    done += item.path
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Compté dans le bilan ; on continue avec les autres.
                }
            }
            val before = _state.value.items.map { it.path }
            runCatching { refreshQuietly(parent) }
            _state.update {
                it.copy(selecting = false, selected = emptySet(), focusRequest = focusAfterRemoval(before, done))
            }
            val failed = targets.size - done.size
            val res = app.resources
            val message = buildList {
                if (done.isNotEmpty()) add(res.getQuantityString(doneMessage, done.size, done.size))
                if (failed > 0) add(res.getQuantityString(R.plurals.op_failures, failed, failed))
            }.joinToString(" ")
            announcer.announce(message)
        }
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
                _state.update { it.copy(focusRequest = FocusRequest.Item(created)) }
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

    /** Crée un fichier texte vide puis l'ouvre dans l'éditeur ([then] reçoit son chemin). */
    fun suggestedFileName(base: String): String = NameValidator.uniqueName(base, rawItems.map { it.name }, keepExtension = true)

    fun createTextFile(name: String, then: (String) -> Unit) {
        val parent = _state.value.path ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = java.io.File(parent, name)
            val ok = withContext(Dispatchers.IO) { runCatching { file.createNewFile() }.getOrDefault(false) }
            if (ok) {
                refreshQuietly(parent)
                announcer.announce(app.getString(R.string.text_file_created, name))
                then(file.path)
            } else {
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
                _state.update { it.copy(focusRequest = FocusRequest.Item(renamed)) }
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

    /** Met l'élément à la corbeille de son volume ; le focus passe à l'élément voisin. */
    fun moveToTrash(item: FileItem) {
        val parent = _state.value.path ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val focus = neighborFocus(_state.value.items, item.path) { it.path }
            try {
                withContext(Dispatchers.IO) { trash.moveToTrash(item.path) }
                refreshQuietly(parent)
                _state.update { it.copy(focusRequest = focus) }
                announcer.announce(app.getString(R.string.moved_to_trash, item.name))
            } catch (e: FileOperationException) {
                announcer.announce(app.getString(e.reason.message(), item.name))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                announcer.announce(app.getString(R.string.op_failed, item.name))
            }
        }
    }

    /** Supprime définitivement l'élément, sans passer par la corbeille. */
    fun deleteForever(item: FileItem) {
        val parent = _state.value.path ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val focus = neighborFocus(_state.value.items, item.path) { it.path }
            try {
                fileSource.delete(item.path)
                refreshQuietly(parent)
                _state.update { it.copy(focusRequest = focus) }
                announcer.announce(app.getString(R.string.deleted_forever, item.name))
            } catch (e: FileOperationException) {
                // Une suppression partielle a pu avoir lieu : on relit le dossier.
                runCatching { refreshQuietly(parent) }
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
internal fun FileOperationException.Reason.message(): Int = when (this) {
    FileOperationException.Reason.ALREADY_EXISTS -> R.string.op_already_exists
    FileOperationException.Reason.NOT_FOUND -> R.string.op_not_found
    FileOperationException.Reason.DENIED -> R.string.op_denied
    FileOperationException.Reason.FAILED -> R.string.op_failed
    FileOperationException.Reason.INTO_ITSELF -> R.string.op_into_itself
    FileOperationException.Reason.ENCRYPTED -> R.string.op_encrypted
    FileOperationException.Reason.BAD_ARCHIVE -> R.string.op_bad_archive
    FileOperationException.Reason.WRONG_PASSWORD -> R.string.op_wrong_password
    FileOperationException.Reason.UNSUPPORTED -> R.string.op_unsupported
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
