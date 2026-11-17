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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R

/**
 * Confirmation de « Mettre à la corbeille » (un élément ou une sélection),
 * avec une case facultative pour supprimer définitivement.
 *
 * @param trashMessage message affiché tant que la case n'est pas cochée ;
 * @param deleteMessage message affiché quand elle l'est.
 *
 * Ordre de lecture : titre → message → case à cocher → Annuler → bouton de confirmation.
 * Quand la case change, le message (zone lue automatiquement) annonce la conséquence,
 * et le bouton de confirmation change de libellé.
 */
@Composable
fun TrashConfirmDialog(
    trashMessage: String,
    deleteMessage: String,
    onMoveToTrash: () -> Unit,
    onDeleteForever: () -> Unit,
    onDismiss: () -> Unit,
) {
    var permanent by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (permanent) R.string.confirm_delete_title else R.string.confirm_trash_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column {
                Text(
                    text = if (permanent) deleteMessage else trashMessage,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                // Une seule zone TalkBack : « Supprimer définitivement…, case à cocher, non cochée ».
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .heightIn(min = 48.dp)
                        .toggleable(value = permanent, role = Role.Checkbox, onValueChange = { permanent = it }),
                ) {
                    Checkbox(checked = permanent, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.delete_permanently_option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (permanent) onDeleteForever else onMoveToTrash) {
                Text(
                    stringResource(if (permanent) R.string.action_delete_forever else R.string.action_move_to_trash),
                    color = if (permanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
