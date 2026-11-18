package io.github.thomaszimmer13.xfiles.ui.vault

import android.app.Application
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.remote.Protocol
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.PasswordField
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.ShowPasswordRow
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.remote.remoteErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultCreateViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    val announcer = Announcer(viewModelScope)
    val clouds = container.servers.servers
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done = _done.asStateFlow()

    private fun text(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    init {
        viewModelScope.launch { container.servers.load() }
    }

    /** [cloud] null : sur le téléphone. */
    fun create(name: String, cloud: RemoteServer?, password: String, confirm: String) {
        val clean = name.trim()
        val existing = container.servers.servers.value.filter { it.protocol == Protocol.VAULT }.map { it.name }
        if (NameValidator.check(clean, existing) !is io.github.thomaszimmer13.xfiles.domain.NameCheck.Valid) return announcer.announce(text(R.string.vault_name_invalid))
        if (password.length < MIN_PASSWORD) return announcer.announce(text(R.string.vault_password_short, MIN_PASSWORD))
        if (password != confirm) return announcer.announce(text(R.string.vault_password_mismatch))
        if (_busy.value) return
        _busy.value = true
        announcer.announce(text(R.string.vault_creating))
        viewModelScope.launch {
            try {
                val location = if (cloud == null) container.vaults.phoneLocation(clean) else container.vaults.cloudLocation(cloud.basePath, clean)
                container.vaults.create(clean, location, cloud?.id.orEmpty(), password)
                container.feedback.success()
                announcer.announce(text(R.string.vault_created, clean))
                _done.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.feedback.failure()
                announcer.announce(
                    if (e is RemoteException && e.reason == RemoteException.Reason.EXISTS) text(R.string.vault_exists)
                    else remoteErrorText(getApplication(), e, cloud != null),
                )
            } finally {
                _busy.update { false }
            }
        }
    }

    companion object {
        const val MIN_PASSWORD = 8
    }
}

/**
 * Nouveau coffre : nom, emplacement (téléphone ou compte en ligne), mot de passe deux fois.
 * Le mot de passe ne peut pas être retrouvé : c'est dit avant de créer.
 */
@Composable
fun VaultCreateScreen(onBack: () -> Unit, viewModel: VaultCreateViewModel = viewModel()) {
    val servers by viewModel.clouds.collectAsStateWithLifecycle()
    val clouds = servers.filter { it.protocol == Protocol.CLOUD }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var cloudId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
    LaunchedEffect(done) { if (done) onBack() }
    val submit = { viewModel.create(name, clouds.firstOrNull { it.id == cloudId }, password, confirm) }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.vault_new), onBack = onBack, titleFocus = titleFocus)
        StatusMessage(announcement)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.vault_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.vault_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            SectionHeader(stringResource(R.string.vault_location))
            Column(Modifier.selectableGroup()) {
                LocationChoice(stringResource(R.string.vault_location_phone), stringResource(R.string.vault_location_phone_detail), cloudId.isEmpty()) { cloudId = "" }
                clouds.forEach { c ->
                    LocationChoice(c.name, stringResource(R.string.vault_location_cloud_detail), cloudId == c.id) { cloudId = c.id }
                }
            }
            SectionHeader(stringResource(R.string.vault_password_header))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(password, { password = it }, visible, null, label = stringResource(R.string.vault_password))
                PasswordField(confirm, { confirm = it }, visible, null, label = stringResource(R.string.vault_password_confirm), onDone = submit)
                ShowPasswordRow(visible) { visible = it }
                Text(stringResource(R.string.vault_password_warning), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vault_create))
                }
            }
        }
    }
}

@Composable
private fun LocationChoice(label: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
