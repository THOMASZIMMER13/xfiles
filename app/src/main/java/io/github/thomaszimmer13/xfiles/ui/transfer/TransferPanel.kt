package io.github.thomaszimmer13.xfiles.ui.transfer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.transfer.ConflictAnswer
import io.github.thomaszimmer13.xfiles.data.transfer.TransferProgress
import io.github.thomaszimmer13.xfiles.data.transfer.TransferService
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.explorer.ConflictDialog
import kotlinx.coroutines.launch

class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = (application as XFilesApplication).container.transfers
    val progress = manager.progress
    val conflict = manager.conflict
    val announcer = Announcer(viewModelScope)

    init {
        viewModelScope.launch { manager.messages.collect { announcer.announce(it) } }
    }

    fun cancel() = manager.cancel()
    fun answerConflict(answer: ConflictAnswer) = manager.answerConflict(answer)
}

/**
 * Bandeau commun à tous les onglets, juste au-dessus de la barre d'onglets :
 * opération en cours (« Copie de 3 éléments vers Documents, 45 %, Facture.pdf ») et bouton « Annuler »,
 * annonces de début et de bilan, et question en cas de conflit de nom.
 *
 * La progression n'est pas lue toute seule (pas de bavardage) : on la consulte en y plaçant le focus.
 */
@Composable
fun TransferPanel(viewModel: TransferViewModel = viewModel()) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()

    progress?.let { ProgressBanner(it, onCancel = viewModel::cancel) }
    StatusMessage(announcement)
    conflict?.let { ConflictDialog(it, onAnswer = viewModel::answerConflict) }
}

@Composable
private fun ProgressBanner(progress: TransferProgress, onCancel: () -> Unit) {
    val title = stringResource(TransferService.titleFor(progress.kind), progress.subject, progress.folder)
    val percent = stringResource(R.string.progress_percent, progress.percent)
    val known = progress.totalBytes > 0
    val spoken = listOfNotNull(title, if (known) percent else null, progress.currentName.ifEmpty { null }).joinToString(", ")
    Column(Modifier.background(MaterialTheme.colorScheme.secondaryContainer)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .clearAndSetSemantics { contentDescription = spoken },
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (known) {
                    LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(
                    listOfNotNull(if (known) percent else null, progress.currentName.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
