package io.github.thomaszimmer13.xfiles.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.archive.Archives
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Demande de mot de passe en cours : archive concernée, et erreur du dernier essai. */
data class PasswordRequest(val archiveName: String, val wrong: Boolean = false, val checking: Boolean = false)

/**
 * Avant d'ouvrir ou d'extraire une archive : demande son mot de passe si elle en a un,
 * le vérifie, puis lance l'action. Le mot de passe juste est gardé pour la même archive
 * tant que l'écran reste ouvert.
 */
class PasswordGate(private val scope: CoroutineScope) {
    private val _request = MutableStateFlow<PasswordRequest?>(null)
    val request: StateFlow<PasswordRequest?> = _request.asStateFlow()

    private val known = mutableMapOf<String, String>()
    private var pendingPath: String? = null
    private var pendingAction: ((String?) -> Unit)? = null

    /** Mot de passe déjà validé pour [path], ou null. */
    fun knownPassword(path: String): String? = known[path]

    /**
     * Lance [action] avec le mot de passe de l'archive (null si elle n'en a pas).
     * [force] : demander même si l'archive ne semble pas chiffrée (noms chiffrés d'un 7z ou d'un RAR).
     */
    fun run(path: String, name: String, force: Boolean = false, action: (String?) -> Unit) {
        known[path]?.let { return action(it) }
        scope.launch {
            val needs = force || withContext(Dispatchers.IO) { Archives.needsPassword(path) }
            if (!needs) {
                action(null)
            } else {
                pendingPath = path
                pendingAction = action
                _request.value = PasswordRequest(name)
            }
        }
    }

    fun submit(password: String) {
        val path = pendingPath ?: return
        val current = _request.value ?: return
        _request.value = current.copy(checking = true, wrong = false)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    Archives.checkPassword(path, password)
                    true
                } catch (e: FileOperationException) {
                    // Une archive abîmée n'est pas une erreur de mot de passe : l'action dira ce qui ne va pas.
                    e.reason != FileOperationException.Reason.WRONG_PASSWORD && e.reason != FileOperationException.Reason.ENCRYPTED
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                known[path] = password
                val action = pendingAction
                dismiss()
                action?.invoke(password)
            } else {
                _request.value = current.copy(checking = false, wrong = true)
            }
        }
    }

    fun dismiss() {
        _request.value = null
        pendingPath = null
        pendingAction = null
    }
}

/** Affiche le dialogue de [gate] quand il demande un mot de passe. */
@Composable
fun PasswordGateDialog(request: PasswordRequest?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    if (request == null) return
    PasswordDialog(
        title = stringResource(R.string.password_title),
        message = stringResource(R.string.password_message, request.archiveName),
        confirmLabel = stringResource(R.string.action_ok),
        error = if (request.wrong) stringResource(R.string.password_wrong) else null,
        busy = request.checking,
        onConfirm = onSubmit,
        onDismiss = onDismiss,
    )
}

/**
 * Saisie d'un mot de passe. Le champ reçoit le focus ; une case « Afficher le mot de passe »
 * permet de le faire lire en clair par TalkBack ; une erreur est annoncée dès qu'elle apparaît.
 */
@Composable
fun PasswordDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    error: String?,
    busy: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    fun submit() {
        if (value.isNotEmpty() && !busy) onConfirm(value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                if (message != null) Text(message, modifier = Modifier.padding(bottom = 8.dp))
                PasswordField(value, { value = it }, visible, error, fieldFocus, onDone = { submit() })
                ShowPasswordRow(visible) { visible = it }
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = value.isNotEmpty() && !busy) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    error: String?,
    focusRequester: FocusRequester? = null,
    label: String = stringResource(R.string.password_label),
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { if (error != null) error(error) },
    )
}

/** « Afficher le mot de passe » : un seul élément TalkBack, case à cocher. */
@Composable
fun ShowPasswordRow(visible: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = visible, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = visible, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.password_show))
    }
}
