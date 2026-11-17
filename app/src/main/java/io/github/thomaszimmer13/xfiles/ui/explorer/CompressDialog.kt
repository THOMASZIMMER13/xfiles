package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.archive.CreateFormat
import io.github.thomaszimmer13.xfiles.domain.NameCheck
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.ui.components.PasswordField
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.ShowPasswordRow

/**
 * « Compresser » : nom de l'archive, format (groupe de boutons radio), mot de passe facultatif (zip seulement).
 * Ordre de balayage : titre → nom → « Format », Zip, 7z, tar.gz → mot de passe → Compresser, Annuler.
 */
@Composable
fun CompressDialog(
    subject: String,
    suggestedName: String,
    onConfirm: (name: String, format: CreateFormat, password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(suggestedName, TextRange(0, suggestedName.length))) }
    var format by remember { mutableStateOf(CreateFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    val nameErrorText = stringResource(R.string.compress_name_invalid)

    fun submit() {
        val check = NameValidator.check(name.text + format.extension, emptyList())
        if (check !is NameCheck.Valid) {
            nameError = true
            return
        }
        onConfirm(name.text.trim(), format, password.takeIf { format == CreateFormat.ZIP && it.isNotEmpty() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title, subject), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    label = { Text(stringResource(R.string.compress_name_label)) },
                    suffix = { Text(format.extension) },
                    singleLine = true,
                    isError = nameError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .semantics { if (nameError) error(nameErrorText) },
                )
                Text(
                    text = if (nameError) nameErrorText else "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )

                SectionHeader(stringResource(R.string.compress_format))
                Column(Modifier.selectableGroup()) {
                    CreateFormat.entries.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = format == f, role = Role.RadioButton, onClick = { format = f }),
                        ) {
                            RadioButton(selected = format == f, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(formatLabel(f)))
                        }
                    }
                }

                if (format == CreateFormat.ZIP) {
                    Spacer(Modifier.padding(top = 8.dp))
                    PasswordField(
                        value = password,
                        onChange = { password = it },
                        visible = showPassword,
                        error = null,
                        label = stringResource(R.string.compress_password_label),
                        onDone = { submit() },
                    )
                    ShowPasswordRow(showPassword) { showPassword = it }
                } else {
                    Text(
                        stringResource(R.string.compress_no_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text(stringResource(R.string.action_compress)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun formatLabel(format: CreateFormat): Int = when (format) {
    CreateFormat.ZIP -> R.string.format_zip
    CreateFormat.SEVEN_Z -> R.string.format_7z
    CreateFormat.TAR_GZ -> R.string.format_tar_gz
}
