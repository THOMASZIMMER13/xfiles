package io.github.thomaszimmer13.xfiles.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.media.Categories
import io.github.thomaszimmer13.xfiles.data.media.PlaylistEntry
import io.github.thomaszimmer13.xfiles.data.media.Playlists
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.ui.explorer.NameDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * « Ajouter à une liste de lecture » : les listes m3u du dossier et du téléphone, puis
 * « Nouvelle liste dans ce dossier ». Chaque liste est un élément : « Rock, dans Musique ».
 * [onDone] reçoit le message à annoncer.
 */
@Composable
fun AddToPlaylistDialog(paths: List<String>, folder: File, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val candidates by produceState<List<File>?>(null, folder) {
        value = withContext(Dispatchers.IO) {
            val here = folder.listFiles().orEmpty().filter { it.isFile && (it.extension.equals("m3u", true) || it.extension.equals("m3u8", true)) }
            val indexed = runCatching { Categories.playlistFiles(context) }.getOrDefault(emptyList()).map(::File).filter { it.isFile }
            (here + indexed).distinctBy { it.canonicalPath }
        }
    }
    var naming by remember { mutableStateOf(false) }
    val addedText = stringResource(R.string.player_added_to_playlist)
    val failedText = stringResource(R.string.player_playlist_save_failed)
    val inText = stringResource(R.string.favorite_in)
    val chooseLabel = stringResource(R.string.player_choose)

    suspend fun addTo(target: File) {
        val ok = withContext(Dispatchers.IO) { runCatching { Playlists.append(target, paths.map { PlaylistEntry(it) }) }.isSuccess }
        onDone(if (ok) addedText.format(paths.size, target.nameWithoutExtension) else failedText)
    }

    if (naming) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        NameDialog(
            title = stringResource(R.string.player_new_playlist),
            confirmLabel = stringResource(R.string.action_create),
            initialName = stringResource(R.string.player_playlist_default_name),
            keepExtension = false,
            check = { NameValidator.check("$it.m3u8", folder.list()?.toList().orEmpty()) },
            onConfirm = { name -> scope.launchAdd { addTo(File(folder, "$name.m3u8")) } },
            onDismiss = onDismiss,
        )
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_add_to_playlist), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = candidates
                when {
                    list == null -> Text(stringResource(R.string.folder_loading))
                    list.isEmpty() -> Text(stringResource(R.string.player_no_playlists))
                    else -> list.forEach { file ->
                        val where = file.parentFile?.name.orEmpty()
                        val spoken = "${file.nameWithoutExtension}, ${inText.format(where)}"
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { scope.launchAdd { addTo(file) } }
                                .clearAndSetSemantics {
                                    contentDescription = spoken
                                    onClick(label = chooseLabel) { scope.launchAdd { addTo(file) }; true }
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge)
                            Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text(stringResource(R.string.player_new_playlist)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun CoroutineScope.launchAdd(block: suspend () -> Unit) {
    launch { block() }
}
