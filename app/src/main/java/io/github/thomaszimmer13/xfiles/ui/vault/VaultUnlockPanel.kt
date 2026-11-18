package io.github.thomaszimmer13.xfiles.ui.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.PasswordField
import io.github.thomaszimmer13.xfiles.ui.components.ShowPasswordRow
import io.github.thomaszimmer13.xfiles.ui.remote.RemoteFolderViewModel
import io.github.thomaszimmer13.xfiles.ui.security.Biometrics
import kotlinx.coroutines.launch

/**
 * Coffre verrouillé : mot de passe, ou empreinte si elle a été activée (proposée d'elle-même).
 * Case « Utiliser l'empreinte la prochaine fois » tant qu'elle ne l'est pas.
 */
@Composable
fun VaultUnlockPanel(server: RemoteServer, unlocking: Boolean, viewModel: RemoteFolderViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var rememberBio by remember { mutableStateOf(false) }
    val strong = remember { Biometrics.available(context, Biometrics.STRONG) }
    val hasSeal = server.password.isNotEmpty()
    val fieldFocus = remember { FocusRequester() }
    val bioTitle = stringResource(R.string.vault_bio_prompt, server.name)
    val usePassword = stringResource(R.string.vault_bio_use_password)
    val sealTitle = stringResource(R.string.vault_bio_seal_prompt)
    val cancelLabel = stringResource(R.string.action_cancel)

    fun askBio() {
        val activity = Biometrics.activity(context) ?: return
        val cipher = try {
            viewModel.state.value.server?.let { s -> (context.applicationContext as io.github.thomaszimmer13.xfiles.XFilesApplication).container.vaults.openCipher(s) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
        if (cipher == null) return viewModel.bioInvalidated()
        scope.launch {
            val result = Biometrics.prompt(activity, bioTitle, authenticators = Biometrics.STRONG, crypto = BiometricPrompt.CryptoObject(cipher), negative = usePassword)
            result?.cryptoObject?.cipher?.let(viewModel::unlockWithBio)
        }
    }

    FocusOnceEffect(ready = true) { if (!(hasSeal && strong)) fieldFocus.requestFocus() }
    LaunchedEffect(Unit) { if (hasSeal && strong) askBio() }

    // Coffre ouvert avec « la prochaine fois » coché : l'empreinte scelle le mot de passe.
    LaunchedEffect(state.sealRequest) {
        if (!state.sealRequest) return@LaunchedEffect
        val activity = Biometrics.activity(context)
        val cipher = viewModel.sealCipher()
        if (activity == null || cipher == null) return@LaunchedEffect viewModel.saveSeal(null)
        val result = Biometrics.prompt(activity, sealTitle, authenticators = Biometrics.STRONG, crypto = BiometricPrompt.CryptoObject(cipher), negative = cancelLabel)
        viewModel.saveSeal(result?.cryptoObject?.cipher)
    }

    val submit = { viewModel.unlock(password, rememberBio && strong && !hasSeal) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.vault_locked_message, server.name), style = MaterialTheme.typography.bodyLarge)
        PasswordField(password, { password = it }, visible, null, focusRequester = fieldFocus, label = stringResource(R.string.vault_password), onDone = submit)
        ShowPasswordRow(visible) { visible = it }
        if (strong && !hasSeal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = rememberBio, role = Role.Checkbox) { rememberBio = it },
            ) {
                Checkbox(checked = rememberBio, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vault_bio_next_time))
            }
        }
        Button(onClick = submit, enabled = !unlocking, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_unlock)) }
        if (hasSeal && strong) {
            OutlinedButton(onClick = ::askBio, enabled = !unlocking, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_unlock_bio)) }
        }
    }
}
