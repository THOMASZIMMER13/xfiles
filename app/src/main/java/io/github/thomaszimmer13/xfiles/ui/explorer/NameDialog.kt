package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.domain.NameCheck
import io.github.thomaszimmer13.xfiles.domain.NameProblem
import io.github.thomaszimmer13.xfiles.domain.NameValidator

/**
 * Saisie d'un nom (nouveau dossier, renommage).
 * - Le champ reçoit le focus et le clavier s'ouvre.
 * - En renommage d'un fichier, seul le nom sans l'extension est sélectionné (comme sur iOS) :
 *   taper remplace le nom en gardant « .pdf ».
 * - Une erreur est affichée sous le champ, lue automatiquement par TalkBack, et rattachée au champ.
 *
 * @param check vérifie le nom saisi ; [onConfirm] reçoit le nom nettoyé.
 */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    keepExtension: Boolean,
    check: (String) -> NameCheck,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectionEnd = if (keepExtension) {
        initialName.lastIndexOf('.').takeIf { it > 0 } ?: initialName.length
    } else {
        initialName.length
    }
    var value by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, selectionEnd))) }
    var problem by rememberSaveable { mutableStateOf<NameProblem?>(null) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    val (errorVisible, errorSpoken) = problem?.let { describe(it, value.text.trim()) } ?: ("" to "")

    fun submit() {
        when (val result = check(value.text)) {
            is NameCheck.Valid -> onConfirm(result.name)
            NameCheck.Unchanged -> onDismiss()
            is NameCheck.Invalid -> problem = result.problem
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        problem = null
                    },
                    label = { Text(stringResource(R.string.name_label)) },
                    singleLine = true,
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .semantics { if (problem != null) error(errorSpoken) },
                )
                // Toujours présent (vide sans erreur) pour que l'apparition du message soit lue.
                Text(
                    text = errorVisible,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (problem != null) 8.dp else 0.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            if (problem != null) contentDescription = errorSpoken
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Message d'erreur : version affichée et version lue (symboles en toutes lettres). */
@Composable
private fun describe(problem: NameProblem, name: String): Pair<String, String> = when (problem) {
    NameProblem.EMPTY -> stringResource(R.string.name_error_empty).let { it to it }
    NameProblem.RESERVED -> stringResource(R.string.name_error_reserved).let { it to it }
    NameProblem.TOO_LONG -> stringResource(R.string.name_error_too_long).let { it to it }
    NameProblem.ALREADY_EXISTS -> stringResource(R.string.name_error_exists, name).let { it to it }
    NameProblem.FORBIDDEN_CHARACTERS ->
        stringResource(R.string.name_error_characters, NameValidator.FORBIDDEN.joinToString(" ")) to
            stringResource(R.string.name_error_characters_spoken)
}
