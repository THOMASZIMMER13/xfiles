package io.github.thomaszimmer13.xfiles.ui.remote

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.remote.HostKeyException
import io.github.thomaszimmer13.xfiles.data.remote.RemoteClient
import io.github.thomaszimmer13.xfiles.data.remote.RemoteEntry
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.Collator
import java.util.Locale

/** Transfert en cours : libellé (« Téléchargement de rapport.pdf ») et avancement (null : inconnu). */
data class RemoteTransfer(val label: String, val fraction: Float?)

data class RemoteFolderUiState(
    val server: RemoteServer? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val transfer: RemoteTransfer? = null,
    /** Clé SFTP à accepter (première connexion) ou changée (danger). */
    val hostKey: HostKeyException? = null,
    /** Fichier téléchargé dans le cache, à ouvrir. */
    val opened: File? = null,
    /** Coffre fermé : mot de passe (ou empreinte) à donner. */
    val locked: Boolean = false,
    val unlocking: Boolean = false,
    /** Coffre tout juste ouvert : sceller son mot de passe par l'empreinte (fenêtre à afficher). */
    val sealRequest: Boolean = false,
)

class RemoteFolderViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val sessions = container.remote
    private val store = container.servers
    private val _state = MutableStateFlow(RemoteFolderUiState())
    val state: StateFlow<RemoteFolderUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private var transferJob: Job? = null
    private val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.SECONDARY }

    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private val vaults = container.vaults
    private var pendingSeal: String? = null

    init {
        // Coffre refermé (délai, verrouillage) pendant que l'écran est ouvert.
        viewModelScope.launch {
            container.vaultKeys.open.collect { open ->
                val server = _state.value.server ?: return@collect
                if (server.protocol == io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT && server.id !in open && !_state.value.locked && !_state.value.unlocking) {
                    transferJob?.cancel()
                    _state.update { it.copy(locked = true, entries = emptyList(), loading = false, error = null) }
                }
            }
        }
    }

    fun load(serverId: String, path: String) {
        viewModelScope.launch {
            if (store.servers.value.isEmpty()) store.load()
            val server = store.get(serverId)
            if (server == null) {
                _state.update { it.copy(loading = false, error = text(R.string.remote_server_missing)) }
                return@launch
            }
            _state.update { it.copy(server = server, path = path) }
            if (server.protocol == io.github.thomaszimmer13.xfiles.data.remote.Protocol.VAULT && !vaults.isOpen(server.id)) {
                _state.update { it.copy(locked = true, loading = false) }
                return@launch
            }
            refresh()
        }
    }

    /** Ouvre le coffre ; [rememberBio] : proposer ensuite l'empreinte pour la prochaine fois. */
    fun unlock(password: String, rememberBio: Boolean) {
        val server = _state.value.server ?: return
        if (password.isEmpty() || _state.value.unlocking) return
        _state.update { it.copy(unlocking = true) }
        announcer.announce(text(R.string.vault_unlocking))
        viewModelScope.launch {
            try {
                if (vaults.unlock(server, password)) {
                    container.feedback.success()
                    _state.update { it.copy(locked = false, unlocking = false) }
                    announcer.announce(text(R.string.vault_unlocked, server.name))
                    if (rememberBio) {
                        pendingSeal = password
                        _state.update { it.copy(sealRequest = true) }
                    }
                    refresh()
                } else {
                    container.feedback.failure()
                    _state.update { it.copy(unlocking = false) }
                    announcer.announce(text(R.string.vault_wrong_password))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.feedback.failure()
                _state.update { it.copy(unlocking = false) }
                announcer.announce(errorText(e))
            }
        }
    }

    /** Empreinte acceptée : le mot de passe scellé est relu, puis le coffre ouvert. */
    fun unlockWithBio(cipher: javax.crypto.Cipher) {
        val server = _state.value.server ?: return
        val password = vaults.openSeal(server, cipher) ?: return announcer.announce(text(R.string.vault_bio_lost))
        unlock(password, false)
    }

    /** Empreintes du téléphone changées : le mot de passe scellé est perdu, on l'oublie. */
    fun bioInvalidated() {
        val server = _state.value.server ?: return
        viewModelScope.launch {
            val updated = vaults.forgetSeal(server)
            _state.update { it.copy(server = updated) }
            announcer.announce(text(R.string.vault_bio_lost))
        }
    }

    fun sealCipher(): javax.crypto.Cipher? = _state.value.server?.let { runCatching { vaults.sealCipher(it.id) }.getOrNull() }

    fun saveSeal(cipher: javax.crypto.Cipher?) {
        val server = _state.value.server
        val password = pendingSeal
        pendingSeal = null
        _state.update { it.copy(sealRequest = false) }
        if (server == null || password == null || cipher == null) return
        viewModelScope.launch {
            runCatching { vaults.saveSeal(server, cipher, password) }
                .onSuccess { updated ->
                    _state.update { it.copy(server = updated) }
                    announcer.announce(text(R.string.vault_bio_on))
                }
                .onFailure { announcer.announce(text(R.string.vault_bio_failed)) }
        }
    }

    fun lockVault() {
        val server = _state.value.server ?: return
        transferJob?.cancel()
        vaults.lock(server.id)
        _state.update { it.copy(locked = true, entries = emptyList()) }
        announcer.announce(text(R.string.vault_locked_now, server.name))
    }

    fun refresh() {
        val server = _state.value.server ?: return
        val path = _state.value.path
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = sessions.use(server) { it.list(path) }
                    .filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy<RemoteEntry> { !it.isDirectory }.thenComparator { a, b -> collator.compare(a.name, b.name) })
                _state.update { it.copy(loading = false, entries = entries) }
            } catch (e: HostKeyException) {
                _state.update { it.copy(loading = false, hostKey = e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = errorText(e)
                _state.update { it.copy(loading = false, error = message) }
                announcer.announce(message)
            }
        }
    }

    /** Clé SFTP acceptée : enregistrée avec le serveur, puis connexion. */
    fun trustHostKey() {
        val key = _state.value.hostKey ?: return
        val server = _state.value.server ?: return
        viewModelScope.launch {
            val updated = server.copy(hostKey = key.fingerprint)
            store.save(updated)
            sessions.forget(server.id)
            _state.update { it.copy(server = updated, hostKey = null) }
            refresh()
        }
    }

    fun rejectHostKey() = _state.update { it.copy(hostKey = null, loading = false, error = text(R.string.remote_host_key_rejected)) }

    /** Fichier : copié dans le cache (réutilisé s'il n'a pas changé), puis ouvert. */
    fun open(entry: RemoteEntry) {
        val server = _state.value.server ?: return
        val root = File(getApplication<Application>().cacheDir, "remote/${server.id}")
        val target = File(root, entry.path.trimStart('/'))
        // Nom piégé venu du serveur : jamais d'écriture hors du cache de ce serveur (C-01).
        if (!RemoteClient.inside(root, target) || target == root) return announcer.announce(text(R.string.remote_error_protocol))
        if (target.exists() && target.length() == entry.size && (entry.lastModified == 0L || target.lastModified() == entry.lastModified)) {
            _state.update { it.copy(opened = target) }
            return
        }
        transfer(text(R.string.remote_opening, entry.name)) { onProgress ->
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            sessions.use(server) { c -> tmp.outputStream().use { out -> c.download(entry.path, out) { done, total -> onProgress(done, if (total > 0) total else entry.size) } } }
            tmp.renameTo(target)
            if (entry.lastModified > 0) target.setLastModified(entry.lastModified)
            _state.update { it.copy(opened = target) }
            null
        }
    }

    fun consumeOpened() = _state.update { it.copy(opened = null) }

    /** Copie dans Téléchargements (dossier entier compris). */
    fun download(entry: RemoteEntry) {
        val server = _state.value.server ?: return
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        transfer(text(R.string.remote_downloading, entry.name)) { onProgress ->
            val dest = unique(File(downloads, entry.name))
            if (!RemoteClient.inside(downloads, dest) || dest == downloads) throw RemoteException(RemoteException.Reason.PROTOCOL, entry.name)
            sessions.use(server) { c -> fetch(c, entry, dest, onProgress) }
            text(R.string.remote_downloaded, dest.name)
        }
    }

    private fun fetch(c: RemoteClient, entry: RemoteEntry, dest: File, onProgress: (Long, Long) -> Unit) {
        if (entry.isDirectory) {
            dest.mkdirs()
            for (child in c.list(entry.path)) {
                val next = File(dest, child.name)
                if (!RemoteClient.inside(dest, next) || next == dest) throw RemoteException(RemoteException.Reason.PROTOCOL, child.name)
                fetch(c, child, next, onProgress)
            }
        } else {
            val tmp = File(dest.path + ".part")
            tmp.outputStream().use { out -> c.download(entry.path, out) { done, total -> onProgress(done, if (total > 0) total else entry.size) } }
            if (!tmp.renameTo(dest)) throw RemoteException(RemoteException.Reason.DENIED, dest.path)
        }
    }

    /** Envoie ici les éléments du presse-papiers d'XFiles (fichiers et dossiers locaux). */
    fun uploadClipboard() {
        val server = _state.value.server ?: return
        val clip = container.clipboard.content.value
        val items = clip?.items.orEmpty()
        val moving = clip?.mode == io.github.thomaszimmer13.xfiles.data.transfer.TransferMode.MOVE
        if (items.isEmpty()) {
            announcer.announce(text(R.string.remote_clipboard_empty))
            return
        }
        val path = _state.value.path
        val label = if (items.size == 1) text(R.string.remote_uploading, items.first().name)
        else getApplication<Application>().resources.getQuantityString(R.plurals.remote_uploading_many, items.size, items.size)
        transfer(label) { onProgress ->
            val existing = sessions.use(server) { it.list(path) }.map { it.name }.toMutableList()
            sessions.use(server) { c ->
                for (item in items) {
                    val name = NameValidator.uniqueName(item.name, existing, keepExtension = !item.isDirectory)
                    existing += name
                    send(c, File(item.path), RemoteClient.child(path, name), onProgress)
                    // Déplacer : l'original n'est supprimé qu'une fois envoyé (coffre : il ne reste que la copie chiffrée).
                    if (moving) File(item.path).deleteRecursively()
                }
            }
            container.clipboard.clear()
            refresh()
            if (items.size == 1) text(R.string.remote_uploaded, items.first().name)
            else getApplication<Application>().resources.getQuantityString(R.plurals.remote_uploaded_many, items.size, items.size)
        }
    }

    private fun send(c: RemoteClient, file: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        if (file.isDirectory) {
            c.mkdir(remotePath)
            file.listFiles()?.forEach { send(c, it, RemoteClient.child(remotePath, it.name), onProgress) }
        } else {
            file.inputStream().use { c.upload(it, file.length(), remotePath) { done, total -> onProgress(done, total) } }
        }
    }

    fun mkdir(name: String) = simple(text(R.string.folder_created, name)) { c -> c.mkdir(RemoteClient.child(_state.value.path, name)) }

    fun rename(entry: RemoteEntry, newName: String) = simple(text(R.string.renamed, newName)) { c ->
        c.rename(entry.path, RemoteClient.child(RemoteClient.parent(entry.path), newName))
    }

    fun delete(entry: RemoteEntry) = simple(text(R.string.remote_deleted, entry.name)) { c -> c.delete(entry.path, entry.isDirectory) }

    fun names(): List<String> = _state.value.entries.map { it.name }

    private fun simple(done: String, block: (RemoteClient) -> Unit) {
        val server = _state.value.server ?: return
        viewModelScope.launch {
            try {
                sessions.use(server, block)
                container.feedback.success()
                announcer.announce(done)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.feedback.failure()
                announcer.announce(errorText(e))
            }
        }
    }

    /** Transfert long, annulable, avec avancement ; [block] renvoie le message de fin (ou null). */
    private fun transfer(label: String, block: suspend ((Long, Long) -> Unit) -> String?) {
        if (transferJob?.isActive == true) {
            announcer.announce(text(R.string.remote_busy))
            return
        }
        _state.update { it.copy(transfer = RemoteTransfer(label, null)) }
        announcer.announce(label)
        transferJob = viewModelScope.launch {
            try {
                val message = block { done, total ->
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    _state.update { s -> s.copy(transfer = s.transfer?.copy(fraction = fraction)) }
                }
                _state.update { it.copy(transfer = null) }
                if (message != null) {
                    container.feedback.success()
                    announcer.announce(message)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(transfer = null) }
                announcer.announce(text(R.string.remote_cancelled))
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(transfer = null) }
                container.feedback.failure()
                announcer.announce(errorText(e))
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    private fun unique(file: File): File {
        if (!file.exists()) return file
        val name = NameValidator.uniqueName(file.name, file.parentFile?.list()?.toList().orEmpty(), keepExtension = true)
        return File(file.parentFile, name)
    }

    fun errorText(e: Throwable): String = remoteErrorText(getApplication(), e, _state.value.server?.protocol?.cloud == true)
}

/** Message d'erreur annoncé pour une opération distante. */
fun remoteErrorText(app: Application, e: Throwable, cloud: Boolean = false): String = when (e) {
    is HostKeyException -> app.getString(R.string.remote_host_key_changed)
    is RemoteException -> app.getString(
        when (e.reason) {
            RemoteException.Reason.UNREACHABLE -> R.string.remote_error_unreachable
            RemoteException.Reason.AUTH -> if (cloud) R.string.remote_error_auth_cloud else R.string.remote_error_auth
            RemoteException.Reason.NOT_FOUND -> R.string.remote_error_not_found
            RemoteException.Reason.DENIED -> R.string.remote_error_denied
            RemoteException.Reason.EXISTS -> R.string.remote_error_exists
            RemoteException.Reason.TLS -> R.string.remote_error_tls
            RemoteException.Reason.PROTOCOL -> R.string.remote_error_protocol
        },
    )
    else -> app.getString(R.string.remote_error_protocol)
}
