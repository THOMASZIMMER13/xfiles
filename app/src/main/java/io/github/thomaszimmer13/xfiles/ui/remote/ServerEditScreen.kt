package io.github.thomaszimmer13.xfiles.ui.remote

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.remote.CloudProvider
import io.github.thomaszimmer13.xfiles.data.remote.CloudSetup
import io.github.thomaszimmer13.xfiles.data.remote.RcloneException
import io.github.thomaszimmer13.xfiles.data.remote.RcloneRemote
import io.github.thomaszimmer13.xfiles.data.remote.HostKeyException
import io.github.thomaszimmer13.xfiles.data.remote.Protocol
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ServerEditUiState(
    val server: RemoteServer = RemoteServer(UUID.randomUUID().toString(), Protocol.SMB, "", ""),
    val isNew: Boolean = true,
    val testing: Boolean = false,
    val hostKey: HostKeyException? = null,
    val saved: Boolean = false,
    /** Compte en ligne (Google Drive, OneDrive, Dropbox…) plutôt que serveur. */
    val cloud: Boolean = false,
    /** Connexion en cours : la page du service est ouverte dans le navigateur. */
    val connecting: Boolean = false,
    /** Page de connexion à ouvrir dans le navigateur. */
    val browserUrl: String? = null,
    /** Espace utilisé et total du compte, après la connexion ou le test. */
    val quota: String? = null,
)

class ServerEditViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val _state = MutableStateFlow(ServerEditUiState())
    val state: StateFlow<ServerEditUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    private var loaded = false

    private var connectJob: kotlinx.coroutines.Job? = null

    fun load(id: String?, cloud: Boolean) {
        if (loaded) return
        loaded = true
        if (id == null) {
            if (cloud) _state.update { it.copy(cloud = true, server = it.server.copy(protocol = Protocol.CLOUD, port = Protocol.CLOUD.defaultPort, host = CloudProvider.GOOGLE_DRIVE.type)) }
            return
        }
        viewModelScope.launch {
            if (container.servers.servers.value.isEmpty()) container.servers.load()
            container.servers.get(id)?.let { s -> _state.update { it.copy(server = s, isNew = false, cloud = s.protocol.cloud) } }
        }
    }

    /** Service du compte en ligne (avant la première connexion). */
    fun setProvider(p: CloudProvider) = _state.update { it.copy(server = it.server.copy(host = p.type)) }

    /**
     * Connexion du compte : rclone crée sa configuration et attend l'accord donné dans le navigateur.
     * L'adresse de la page est lue dès qu'elle existe, puis ouverte dans un onglet.
     */
    fun connect() {
        if (_state.value.connecting) return
        val s = _state.value.server
        val provider = CloudProvider.of(s.host) ?: return
        val label = text(provider.label)
        val remote = CloudProvider.remoteName(s.id)
        _state.update { it.copy(connecting = true, quota = null) }
        announcer.announce(text(R.string.cloud_browser_opened, label))
        connectJob = viewModelScope.launch {
            val rclone = container.rclone
            // Port de connexion déjà occupé par une autre app : on refuse plutôt que de lui livrer le code.
            val taken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { rclone.call("config/oauthstop", org.json.JSONObject()) } // notre propre écoute éventuelle
                CloudSetup.oauthPortTaken()
            }
            if (taken) {
                _state.update { it.copy(connecting = false) }
                container.feedback.failure()
                announcer.announce(text(R.string.cloud_port_busy))
                return@launch
            }
            val watcher = launch(kotlinx.coroutines.Dispatchers.IO) {
                // Page de connexion : connue quand le petit serveur local de rclone est prêt.
                while (true) {
                    val url = runCatching { rclone.call("config/oauthstatus", org.json.JSONObject()) }.getOrNull()?.optString("authUrl").orEmpty()
                    if (url.isNotEmpty()) {
                        _state.update { it.copy(browserUrl = url) }
                        break
                    }
                    kotlinx.coroutines.delay(300)
                }
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Une éventuelle connexion restée en attente est arrêtée d'abord.
                    runCatching { rclone.call("config/oauthstop", org.json.JSONObject()) }
                    val parameters = org.json.JSONObject().put("config_template", doneTemplate())
                    if (provider == CloudProvider.GOOGLE_DRIVE) {
                        val (id, secret) = googleClient(s)
                        if (id.isEmpty()) throw RcloneException(400, "client_id required")
                        parameters.put("client_id", id).put("client_secret", secret)
                    }
                    CloudSetup.configure(rclone, remote, provider.type, parameters)
                }
                watcher.cancel()
                val server = normalized().copy(protocol = Protocol.CLOUD, host = provider.type, basePath = remote)
                container.remote.forget(server.id)
                container.servers.save(server)
                container.feedback.success()
                _state.update { it.copy(server = server, isNew = false, connecting = false, browserUrl = null) }
                announcer.announce(text(R.string.cloud_connected, label))
                quota(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                watcher.cancel()
                _state.update { it.copy(connecting = false, browserUrl = null) }
                container.feedback.failure()
                val message = if ((e as? RcloneException)?.message == "client_id required") {
                    text(R.string.cloud_google_client_needed)
                } else {
                    remoteErrorText(getApplication(), (e as? RcloneException)?.let { RcloneRemote.translate(it, "") } ?: e, cloud = true)
                }
                announcer.announce(text(R.string.cloud_login_failed, message))
            }
        }
    }

    /** Identifiant d'application Google : celui saisi, sinon celui d'XFiles (fourni à la compilation). */
    private fun googleClient(s: RemoteServer): Pair<String, String> =
        if (s.username.isNotBlank()) s.username.trim() to s.password.trim()
        else io.github.thomaszimmer13.xfiles.BuildConfig.GDRIVE_CLIENT_ID to io.github.thomaszimmer13.xfiles.BuildConfig.GDRIVE_CLIENT_SECRET

    /** Arrête la connexion en attente (page fermée sans donner l'accord). */
    fun cancelConnect() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.rclone.call("config/oauthstop", org.json.JSONObject()) } }
        announcer.announce(text(R.string.cloud_login_cancelled))
    }

    fun browserOpened() = _state.update { it.copy(browserUrl = null) }

    fun browserMissing() {
        cancelConnect()
        announcer.announce(text(R.string.cloud_no_browser))
    }

    /** « Utilisé 3,2 Go sur 15 Go », si le service le donne. */
    private suspend fun quota(server: RemoteServer) {
        val about = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.rclone.call("operations/about", org.json.JSONObject().put("fs", server.basePath + ":")) }.getOrNull()
        } ?: return
        val used = about.optLong("used", -1)
        val total = about.optLong("total", -1)
        if (used < 0) return
        val app = getApplication<Application>()
        val text = if (total > 0) {
            app.getString(R.string.cloud_quota, android.text.format.Formatter.formatShortFileSize(app, used), android.text.format.Formatter.formatShortFileSize(app, total))
        } else {
            app.getString(R.string.cloud_quota_used, android.text.format.Formatter.formatShortFileSize(app, used))
        }
        _state.update { it.copy(quota = text) }
    }

    /** Page affichée par le navigateur à la fin de la connexion, avec un lien pour revenir. */
    private fun doneTemplate(): String {
        val ok = text(R.string.cloud_page_ok)
        val failed = text(R.string.cloud_page_failed)
        val back = text(R.string.cloud_page_back)
        val link = io.github.thomaszimmer13.xfiles.BuildConfig.OAUTH_SCHEME + "://retour"
        return """<!DOCTYPE html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">""" +
            """<title>{{ if .OK }}$ok{{ else }}$failed{{ end }}</title></head><body style="font-family:sans-serif;font-size:1.3em;padding:1em">""" +
            """<h1>{{ if .OK }}$ok{{ else }}$failed{{ end }}</h1>{{ if eq .OK false }}<p>{{ .Name }} {{ .Description }}</p>{{ end }}""" +
            """<p><a href="$link" style="font-size:1.4em">$back</a></p></body></html>"""
    }

    fun update(server: RemoteServer) = _state.update { it.copy(server = server) }

    /** Change de protocole : le port suit, sauf s'il avait été modifié. */
    fun setProtocol(p: Protocol) = _state.update { s ->
        val old = s.server
        s.copy(server = old.copy(protocol = p, port = if (old.port == old.protocol.defaultPort) p.defaultPort else old.port, hostKey = if (p == old.protocol) old.hostKey else ""))
    }

    private fun normalized(): RemoteServer {
        val s = _state.value.server
        if (s.protocol.cloud) return s.copy(name = s.name.trim().ifEmpty { CloudProvider.of(s.host)?.let { text(it.label) }.orEmpty() })
        return s.copy(name = s.name.trim().ifEmpty { s.host.trim() }, host = s.host.trim().removePrefix("${s.protocol.scheme}://").trimEnd('/'), basePath = s.basePath.trim(), username = s.username.trim())
    }

    fun test() {
        val server = normalized()
        if (server.protocol.cloud && server.basePath.isEmpty()) return announcer.announce(text(R.string.cloud_not_connected))
        if (!server.protocol.cloud && server.host.isEmpty()) return announcer.announce(text(R.string.server_host_missing))
        _state.update { it.copy(testing = true) }
        announcer.announce(text(R.string.server_testing))
        viewModelScope.launch {
            try {
                container.remote.forget(server.id)
                val count = container.remote.use(server) { it.list("/").size }
                container.feedback.success()
                announcer.announce(getApplication<Application>().resources.getQuantityString(R.plurals.server_test_ok, count, count))
                if (server.protocol.cloud) quota(server)
            } catch (e: HostKeyException) {
                _state.update { it.copy(hostKey = e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.feedback.failure()
                announcer.announce(remoteErrorText(getApplication(), e, server.protocol.cloud))
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    fun trustHostKey() {
        val key = _state.value.hostKey ?: return
        _state.update { it.copy(hostKey = null, server = it.server.copy(hostKey = key.fingerprint)) }
        test()
    }

    fun rejectHostKey() = _state.update { it.copy(hostKey = null) }

    fun save() {
        val server = normalized()
        if (server.protocol.cloud && server.basePath.isEmpty()) return announcer.announce(text(R.string.cloud_not_connected))
        if (!server.protocol.cloud && server.host.isEmpty()) return announcer.announce(text(R.string.server_host_missing))
        if (server.protocol == Protocol.SMB && server.basePath.isEmpty()) return announcer.announce(text(R.string.server_share_missing))
        viewModelScope.launch {
            container.servers.save(server)
            container.remote.forget(server.id)
            announcer.announce(text(if (server.protocol.cloud) R.string.cloud_saved else R.string.server_saved, server.name))
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val server = _state.value.server
        val id = server.id
        viewModelScope.launch {
            if (server.protocol.cloud && server.basePath.isNotEmpty()) {
                // Jetons du compte effacés de la configuration rclone.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { container.rclone.call("config/delete", org.json.JSONObject().put("name", server.basePath)) }
                }
            }
            container.servers.delete(id)
            container.remote.forget(id)
            _state.update { it.copy(saved = true) }
        }
    }

    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)
}

/** Ajout ou modification d'un serveur : protocole, adresse, identifiants, « Tester la connexion ». */
@Composable
fun ServerEditScreen(serverId: String?, cloud: Boolean = false, onBack: () -> Unit, viewModel: ServerEditViewModel = viewModel(key = "server-edit:${serverId ?: if (cloud) "new-cloud" else "new"}")) {
    LaunchedEffect(serverId) { viewModel.load(serverId, cloud) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val s = state.server
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val context = LocalContext.current
    LaunchedEffect(state.browserUrl) {
        state.browserUrl?.let { url ->
            viewModel.browserOpened()
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
            } catch (_: ActivityNotFoundException) {
                viewModel.browserMissing()
            }
        }
    }

    val title = when {
        state.cloud && state.isNew -> R.string.cloud_add
        state.cloud -> R.string.cloud_edit
        state.isNew -> R.string.server_add
        else -> R.string.server_edit
    }
    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(title), onBack = onBack, titleFocus = titleFocus)
        StatusMessage(announcement)
        if (state.cloud) {
            CloudFields(state, viewModel, onDelete = { confirmDelete = true })
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.server_protocol), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() })
            Column(Modifier.selectableGroup()) {
                listOf(
                    Protocol.SMB to R.string.protocol_smb,
                    Protocol.SFTP to R.string.protocol_sftp,
                    Protocol.WEBDAV to R.string.protocol_webdav,
                    Protocol.FTP to R.string.protocol_ftp,
                    Protocol.FTPS to R.string.protocol_ftps,
                ).forEach { (p, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = s.protocol == p, role = Role.RadioButton) { viewModel.setProtocol(p) }.padding(horizontal = 16.dp),
                    ) {
                        RadioButton(selected = s.protocol == p, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Field(stringResource(R.string.server_name), s.name, stringResource(R.string.server_name_help)) { viewModel.update(s.copy(name = it)) }
            Field(stringResource(R.string.server_host), s.host, stringResource(R.string.server_host_help), KeyboardType.Uri) { viewModel.update(s.copy(host = it)) }
            Field(stringResource(R.string.server_port), s.port.toString(), null, KeyboardType.Number) { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { viewModel.update(s.copy(port = it)) } }
            val pathLabel = when (s.protocol) {
                Protocol.SMB -> R.string.server_share
                Protocol.WEBDAV -> R.string.server_dav_path
                else -> R.string.server_start_path
            }
            val pathHelp = when (s.protocol) {
                Protocol.SMB -> R.string.server_share_help
                Protocol.WEBDAV -> R.string.server_dav_path_help
                else -> R.string.server_start_path_help
            }
            Field(stringResource(pathLabel), s.basePath, stringResource(pathHelp), KeyboardType.Uri) { viewModel.update(s.copy(basePath = it)) }
            Field(stringResource(R.string.server_user), s.username, stringResource(R.string.server_user_help)) { viewModel.update(s.copy(username = it)) }
            Field(stringResource(R.string.server_password), s.password, null, KeyboardType.Password, secret = true) { viewModel.update(s.copy(password = it)) }
            if (s.protocol == Protocol.SMB) Field(stringResource(R.string.server_domain), s.domain, stringResource(R.string.server_domain_help)) { viewModel.update(s.copy(domain = it)) }
            if (s.protocol == Protocol.WEBDAV) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = s.secure, role = Role.Switch) {
                        viewModel.update(s.copy(secure = it, port = if (s.port == 443 && !it) 80 else if (s.port == 80 && it) 443 else s.port))
                    }.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.server_https), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = s.secure, onCheckedChange = null)
                }
            }
            if (s.hostKey.isNotEmpty()) {
                Text(stringResource(R.string.server_host_key_known, s.hostKey), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::test, enabled = !state.testing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_test)) }
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.server_save)) }
                if (!state.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.server_delete)) }
            }
        }
    }

    state.hostKey?.let { key ->
        AlertDialog(
            onDismissRequest = viewModel::rejectHostKey,
            title = { Text(stringResource(if (key.changed) R.string.remote_host_key_changed_title else R.string.remote_host_key_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(if (key.changed) R.string.remote_host_key_changed_message else R.string.remote_host_key_message, key.fingerprint)) },
            confirmButton = { TextButton(onClick = viewModel::trustHostKey) { Text(stringResource(R.string.remote_host_key_trust)) } },
            dismissButton = { TextButton(onClick = viewModel::rejectHostKey) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(if (state.cloud) R.string.cloud_delete_title else R.string.server_delete_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(if (state.cloud) R.string.cloud_delete_message else R.string.server_delete_message, s.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text(stringResource(if (state.cloud) R.string.cloud_delete else R.string.server_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Compte en ligne : service (à l'ajout), état, « Se connecter », nom, espace utilisé, test. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CloudFields(state: ServerEditUiState, viewModel: ServerEditViewModel, onDelete: () -> Unit) {
    val s = state.server
    val provider = CloudProvider.of(s.host) ?: CloudProvider.GOOGLE_DRIVE
    val label = stringResource(provider.label)
    val connected = s.basePath.isNotEmpty()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        if (!connected) {
            Text(stringResource(R.string.cloud_service), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() })
            Column(Modifier.selectableGroup()) {
                CloudProvider.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = provider == p, enabled = !state.connecting, role = Role.RadioButton) { viewModel.setProvider(p) }
                            .padding(horizontal = 16.dp),
                    ) {
                        RadioButton(selected = provider == p, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(p.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Text(
            if (connected) stringResource(R.string.cloud_account_connected, label) else stringResource(R.string.cloud_account_none, label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
        state.quota?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (provider == CloudProvider.GOOGLE_DRIVE && !state.connecting) {
            // Identifiant d'application Google personnel : facultatif si XFiles en fournit un.
            Field(stringResource(R.string.cloud_google_client_id), s.username, stringResource(R.string.cloud_google_client_id_help), KeyboardType.Uri) { viewModel.update(s.copy(username = it)) }
            Field(stringResource(R.string.cloud_google_client_secret), s.password, null, KeyboardType.Password, secret = true) { viewModel.update(s.copy(password = it)) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.connecting) {
                Text(stringResource(R.string.cloud_waiting, label), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = viewModel::cancelConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.cloud_cancel_connect)) }
            } else {
                Button(onClick = viewModel::connect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(if (connected) R.string.cloud_reconnect else R.string.cloud_connect, label))
                }
                if (!connected) Text(stringResource(R.string.cloud_connect_help), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (connected) {
            Field(stringResource(R.string.server_name), s.name, stringResource(R.string.cloud_name_help)) { viewModel.update(s.copy(name = it)) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::test, enabled = !state.testing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.server_test)) }
                Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.server_save)) }
                if (!state.isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.cloud_delete)) }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, help: String?, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
