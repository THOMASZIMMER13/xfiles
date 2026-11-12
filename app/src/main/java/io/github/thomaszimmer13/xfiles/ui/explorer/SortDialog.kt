package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.domain.SortKey
import io.github.thomaszimmer13.xfiles.domain.SortOptions

/**
 * Dialogue « Trier et afficher ».
 * Chaque choix est un seul élément TalkBack (« Date de modification, case d'option, sélectionnée »),
 * groupé avec ses voisins ; les sous-titres « Trier par » et « Ordre » sont des titres.
 */
@Composable
fun SortDialog(initial: SortOptions, onApply: (SortOptions) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                DialogSubtitle(stringResource(R.string.sort_by))
                Column(Modifier.selectableGroup()) {
                    SortKey.entries.forEach { key ->
                        RadioRow(
                            label = stringResource(key.label()),
                            selected = options.key == key,
                            // En changeant de critère, on repart du sens le plus naturel pour lui.
                            onSelect = { options = options.copy(key = key, descending = SortOptions.defaultDescending(key)) },
                        )
                    }
                }

                DialogSubtitle(stringResource(R.string.sort_order))
                Column(Modifier.selectableGroup()) {
                    // Le sens le plus naturel pour le critère choisi est proposé en premier.
                    val natural = SortOptions.defaultDescending(options.key)
                    listOf(natural, !natural).forEach { descending ->
                        RadioRow(
                            label = stringResource(orderLabel(options.key, descending)),
                            selected = options.descending == descending,
                            onSelect = { options = options.copy(descending = descending) },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = options.showHidden,
                            role = Role.Checkbox,
                            onValueChange = { options = options.copy(showHidden = it) },
                        )
                        .padding(top = 8.dp),
                ) {
                    Checkbox(checked = options.showHidden, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.show_hidden))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(options) }) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DialogSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
