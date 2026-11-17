package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.transfer.ConflictAnswer
import io.github.thomaszimmer13.xfiles.data.transfer.ConflictChoice
import io.github.thomaszimmer13.xfiles.data.transfer.ConflictPrompt

/**
 * « Ce nom existe déjà » pendant un collage.
 * Ordre de lecture : titre → « Facture.pdf existe déjà dans Documents. »
 * → (case « Appliquer aussi aux 2 autres conflits », à cocher avant de choisir)
 * → Remplacer → Garder les deux → Ignorer → Annuler le collage.
 *
 * Le geste Retour vaut « Ignorer » pour ce seul élément, jamais « Remplacer ».
 */
@Composable
fun ConflictDialog(prompt: ConflictPrompt, onAnswer: (ConflictAnswer) -> Unit) {
    var applyToAll by rememberSaveable(prompt) { mutableStateOf(false) }
    fun answer(choice: ConflictChoice?) = onAnswer(ConflictAnswer(choice, applyToAll))

    AlertDialog(
        onDismissRequest = { answer(ConflictChoice.SKIP) },
        title = { Text(stringResource(R.string.conflict_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_message, prompt.name, prompt.folder))
                Spacer(Modifier.heightIn(min = 8.dp))
                if (prompt.othersRemaining > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .toggleable(value = applyToAll, role = Role.Checkbox, onValueChange = { applyToAll = it }),
                    ) {
                        Checkbox(checked = applyToAll, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pluralStringResource(R.plurals.conflict_apply_all, prompt.othersRemaining, prompt.othersRemaining),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                ChoiceButton(stringResource(R.string.conflict_replace), error = true) { answer(ConflictChoice.REPLACE) }
                ChoiceButton(stringResource(R.string.conflict_keep_both)) { answer(ConflictChoice.KEEP_BOTH) }
                ChoiceButton(stringResource(R.string.conflict_skip)) { answer(ConflictChoice.SKIP) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { answer(null) }) { Text(stringResource(R.string.action_cancel_paste)) }
        },
    )
}

@Composable
private fun ChoiceButton(label: String, error: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
