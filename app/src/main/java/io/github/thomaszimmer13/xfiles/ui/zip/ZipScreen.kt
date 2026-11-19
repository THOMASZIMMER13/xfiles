package io.github.thomaszimmer13.xfiles.ui.zip

import io.github.thomaszimmer13.xfiles.data.fs.Ephemeral
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.FileDescriptions
import io.github.thomaszimmer13.xfiles.data.archive.ArchiveEntry
import io.github.thomaszimmer13.xfiles.data.archive.Archives
import io.github.thomaszimmer13.xfiles.data.archive.ZipArchive
import io.github.thomaszimmer13.xfiles.data.source.FileItem
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.PasswordGate
import io.github.thomaszimmer13.xfiles.ui.components.PasswordGateDialog
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.itemIcon
import io.github.thomaszimmer13.xfiles.ui.components.rememberFileLauncher
import io.github.thomaszimmer13.xfiles.ui.components.rememberNotificationPermission
import io.github.thomaszimmer13.xfiles.ui.explorer.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ZipUiState(
    val zipPath: String? = null,
    val entries: List<ArchiveEntry> = emptyList(),
    val loading: Boolean = true,
    /** Message d'erreur (archive chiffrée, abîmée…), ou null. */
    val error: String? = null,
)

class ZipViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val transfers = container.transfers
    private val volumes = container.volumeRepository

    private val _state = MutableStateFlow(ZipUiState())
    val state: StateFlow<ZipUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    val passwordGate = PasswordGate(viewModelScope)

    /** Archive refermée (écran quitté) : ses mots de passe sont oubliés (audit F-04). */
    override fun onCleared() {
        passwordGate.clear()
        super.onCleared()
    }

    /** Lit l'archive (une seule fois tant qu'on reste dans la même). */
    fun load(zipPath: String) {
        if (_state.value.zipPath == zipPath && _state.value.error == null && !_state.value.loading) return
        // Autre archive : le mot de passe de la précédente n'est plus gardé (audit F-04).
        if (_state.value.zipPath != null && _state.value.zipPath != zipPath) passwordGate.clear()
        _state.value = ZipUiState(zipPath = zipPath)
        list(zipPath, passwordGate.knownPassword(zipPath))
    }

    private fun list(zipPath: String, password: String?) {
        viewModelScope.launch {
            val name = File(zipPath).name
            _state.value = try {
                ZipUiState(zipPath, withContext(Dispatchers.IO) { Archives.list(zipPath, password) }, loading = false)
            } catch (e: FileOperationException) {
                if (e.reason == FileOperationException.Reason.ENCRYPTED && password == null) {
                    // Noms chiffrés (7z, RAR) : il faut le mot de passe pour voir le contenu.
                    passwordGate.run(zipPath, name, force = true) { list(zipPath, it) }
                }
                ZipUiState(zipPath, loading = false, error = getApplication<Application>().getString(e.reason.message(), name))
            }
        }
    }

    /** Réessaie après une erreur (par exemple mot de passe refusé puis dialogue fermé). */
    fun retry(zipPath: String) {
        _state.value = ZipUiState(zipPath = zipPath)
        list(zipPath, passwordGate.knownPassword(zipPath))
    }

    /** Nom du dossier qui contient l'archive (destination des extractions). */
    fun parentName(zipPath: String): String {
        val parent = File(zipPath).parent.orEmpty()
        return Breadcrumbs.of(parent, volumes.volumeRoots()).lastOrNull()?.name ?: File(parent).name
    }

    /** Extrait [entry] (null = toute l'archive) à côté de l'archive. */
    fun extract(zipPath: String, entry: ArchiveEntry?) {
        val parent = File(zipPath).parent ?: return
        passwordGate.run(zipPath, File(zipPath).name) { password ->
            if (!transfers.extract(zipPath, entry?.path, parent, parentName(zipPath), password)) {
                announcer.announce(getApplication<Application>().getString(R.string.transfer_busy))
            }
        }
    }

    /** Extrait un fichier dans le cache de l'app pour l'ouvrir dans une autre app (aperçu). */
    fun preview(zipPath: String, entry: ArchiveEntry, open: (String) -> Unit) {
        passwordGate.run(zipPath, File(zipPath).name) { password -> previewWith(zipPath, entry, password, open) }
    }

    private fun previewWith(zipPath: String, entry: ArchiveEntry, password: String?, open: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, Ephemeral.PREVIEW_DIR).apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    Archives.extract(zipPath, entry.path, dir, password = password)
                }
                open(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileOperationException) {
                announcer.announce(getApplication<Application>().getString(e.reason.message(), entry.name))
            } catch (_: Exception) {
                announcer.announce(getApplication<Application>().getString(R.string.op_failed, entry.name))
            }
        }
    }
}

/**
 * Contenu d'une archive (zip, 7z, tar, RAR…), en lecture seule, parcouru comme un dossier.
 * Ordre de balayage : Retour → titre (« Photos.zip, archive, 12 éléments ») → Tout extraire → éléments.
 * Fichier : appui double = ouvrir (aperçu) ; actions : ouvrir avec, extraire. Dossier : appui double = entrer.
 *
 * @param inner dossier interne affiché (« » = racine) ; [focusRoute] : route d'où l'on revient.
 */
@Composable
fun ZipScreen(
    zipPath: String,
    inner: String,
    focusRoute: String?,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ZipViewModel = viewModel(),
) {
    LaunchedEffect(zipPath) { viewModel.load(zipPath) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val launcher = rememberFileLauncher(onRoute = onOpen, onProblem = { viewModel.announcer.announce(it) })
    val withNotifications = rememberNotificationPermission()
    val passwordRequest by viewModel.passwordGate.request.collectAsStateWithLifecycle()

    val ready = state.zipPath == zipPath && !state.loading
    val children = if (ready) ZipArchive.children(state.entries, inner).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) else emptyList()
    val zipName = File(zipPath).name
    val title = if (inner.isEmpty()) zipName else inner.substringAfterLast('/')
    val kind = if (inner.isEmpty()) stringResource(R.string.archive_kind) else null
    val spokenTitle = listOfNotNull(title, kind, if (ready && state.error == null) FileDescriptions.itemCount(children.size) else null).joinToString(", ")

    // Retour depuis un sous-dossier de l'archive : focus sur ce sous-dossier.
    val focusEntry = (focusRoute?.let(Routes::parse) as? Routes.Route.Zip)?.takeIf { it.path == zipPath }?.inner
        ?.let { inner2 -> children.firstOrNull { inner2 == it.path || inner2.startsWith(it.path + "/") }?.path }
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = ready) {
        val index = children.indexOfFirst { it.path == focusEntry }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            rowFocus.requestFocus()
        } else {
            titleFocus.requestFocus()
        }
    }

    val openLabel = stringResource(R.string.action_open)
    val openWithLabel = stringResource(R.string.action_open_with)
    val extractLabel = stringResource(R.string.action_extract)

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = title,
            spokenTitle = spokenTitle,
            onBack = onBack,
            titleFocus = titleFocus,
            actions = {
                if (ready && state.error == null) {
                    IconButton(onClick = { withNotifications { viewModel.extract(zipPath, null) } }) {
                        Icon(painterResource(R.drawable.ic_unarchive), contentDescription = stringResource(R.string.action_extract_all))
                    }
                }
            },
        )
        Box(Modifier.weight(1f)) {
            val error = state.error
            when {
                !ready -> Centered(stringResource(R.string.folder_loading))
                error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    TextButton(onClick = { viewModel.retry(zipPath) }) { Text(stringResource(R.string.action_retry)) }
                }
                children.isEmpty() -> Centered(stringResource(R.string.folder_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(children, key = { _, it -> it.path }) { index, entry ->
                        val item = FileItem(
                            path = entry.path,
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            sizeBytes = entry.size,
                            lastModified = entry.time,
                            childCount = if (entry.isDirectory) ZipArchive.children(state.entries, entry.path).size else null,
                        )
                        ItemRow(
                            icon = itemIcon(entry.name, entry.isDirectory),
                            title = entry.name,
                            detail = FileDescriptions.visibleDetail(item),
                            spoken = positioned(FileDescriptions.spoken(item), index, children.size),
                            clickLabel = openLabel,
                            onClick = {
                                if (entry.isDirectory) {
                                    onOpen(Routes.zip(zipPath, entry.path))
                                } else {
                                    viewModel.preview(zipPath, entry) { launcher.openTemporary(it, entry.name, chooser = false) }
                                }
                            },
                            focusRequester = if (entry.path == focusEntry) rowFocus else null,
                            actions = buildList {
                                if (!entry.isDirectory) {
                                    add(RowAction(openWithLabel) { viewModel.preview(zipPath, entry) { launcher.openTemporary(it, entry.name, chooser = true) } })
                                }
                                add(RowAction(extractLabel) { withNotifications { viewModel.extract(zipPath, entry) } })
                            },
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
    }
    PasswordGateDialog(passwordRequest, onSubmit = viewModel.passwordGate::submit, onDismiss = viewModel.passwordGate::dismiss)
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
