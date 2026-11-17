package io.github.thomaszimmer13.xfiles.ui.components

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequest

/**
 * Bandeau affiché quand une autre app attend quelque chose d'XFiles, sous la barre de titre :
 * - partage reçu : « 3 fichiers à enregistrer » + « Enregistrer ici » (dans un dossier) + « Annuler » ;
 * - choix d'un fichier : « Choisissez un fichier à envoyer » + « Annuler ».
 * Le texte se lit d'abord, puis les boutons, dans l'ordre de balayage.
 *
 * @param onSaveHere null quand on n'est pas dans un dossier (racine, récents…) : le bandeau explique quoi faire.
 */
@Composable
fun IncomingBanner(onSaveHere: (() -> Unit)?, busy: Boolean = false) {
    val context = LocalContext.current
    val incoming = (context.applicationContext as XFilesApplication).container.incoming
    val request by incoming.current.collectAsStateWithLifecycle()

    val text: String
    when (val r = request) {
        is IncomingRequest.Save -> {
            val count = r.uris.size
            val names = r.names.take(2).joinToString(", ") + if (r.names.size > 2) "…" else ""
            text = pluralStringResource(R.plurals.incoming_save, count, count, names) + " " +
                stringResource(if (onSaveHere != null) R.string.incoming_save_here_hint else R.string.incoming_save_choose_hint)
        }
        is IncomingRequest.Pick -> text = stringResource(R.string.incoming_pick)
        else -> return
    }
    val isSave = request is IncomingRequest.Save

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (isSave && onSaveHere != null) {
                TextButton(onClick = onSaveHere, enabled = !busy) { Text(stringResource(R.string.action_save_here)) }
            }
            TextButton(onClick = {
                incoming.clear()
                // L'autre app attendait une réponse : on la lui rend (annulé) et on revient vers elle.
                context.findActivity()?.let { activity ->
                    activity.setResult(Activity.RESULT_CANCELED)
                    activity.finish()
                }
            }) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
