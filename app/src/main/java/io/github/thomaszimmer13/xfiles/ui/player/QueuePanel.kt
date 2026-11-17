package io.github.thomaszimmer13.xfiles.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.TimeFormatter
import io.github.thomaszimmer13.xfiles.data.media.MediaKinds
import io.github.thomaszimmer13.xfiles.data.media.Playlists
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import io.github.thomaszimmer13.xfiles.player.PlayerHub
import io.github.thomaszimmer13.xfiles.player.PlayerUi
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.RowAction
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import io.github.thomaszimmer13.xfiles.ui.explorer.NameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File de lecture. Chaque piste est un élément : « Titre, en lecture, 4 sur 12 ».
 * Appui double : lire. Actions : monter, descendre, retirer.
 * En haut : « Enregistrer la liste de lecture » (fichier .m3u8 dans le dossier de la première piste).
 */
@Composable
fun QueuePanel(hub: PlayerHub, state: PlayerUi, onClose: () -> Unit) {
    val titleFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusIndex by remember { mutableStateOf(state.index) }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    FocusOnceEffect(ready = true) {
        listState.scrollToItem(state.index.coerceAtLeast(0))
        withFrameNanos { }
        runCatching { rowFocus.requestFocus() }
    }
    var pendingFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingFocus, state.queue) {
        if (!pendingFocus) return@LaunchedEffect
        listState.scrollToItem(focusIndex.coerceAtLeast(0))
        withFrameNanos { }
        runCatching { rowFocus.requestFocus() }
        pendingFocus = false
    }

    val playLabel = stringResource(R.string.player_play_this)
    val upLabel = stringResource(R.string.action_move_up)
    val downLabel = stringResource(R.string.action_move_down)
    val removeLabel = stringResource(R.string.player_remove_from_queue)
    val nowLabel = stringResource(R.string.player_now)
    val movedUp = stringResource(R.string.player_moved_up)
    val movedDown = stringResource(R.string.player_moved_down)
    val removed = stringResource(R.string.player_removed)

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(
            title = stringResource(R.string.player_queue),
            spokenTitle = stringResource(R.string.player_queue_heading, state.count),
            onBack = onClose,
            titleFocus = titleFocus,
        )
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.player_save_playlist)) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(state.queue, key = { i, item -> "$i:${item.id}" }) { i, item ->
                val current = i == state.index
                val duration = item.durationMs.takeIf { it > 0 }
                val spoken = listOfNotNull(
                    item.title,
                    if (current) nowLabel else null,
                    duration?.let(TimeFormatter::spoken),
                ).joinToString(", ")
                ItemRow(
                    icon = if (MediaKinds.isVideo(item.id)) R.drawable.ic_video else R.drawable.ic_music,
                    title = item.title,
                    detail = listOfNotNull(if (current) nowLabel else null, duration?.let(TimeFormatter::short)).joinToString(" · "),
                    spoken = positioned(spoken, i, state.queue.size),
                    clickLabel = playLabel,
                    onClick = { hub.playAt(i) },
                    focusRequester = if (i == focusIndex) rowFocus else null,
                    actions = buildList {
                        if (i > 0) add(RowAction(upLabel) {
                            hub.move(i, up = true)?.let { focusIndex = it; pendingFocus = true; hub.announcer.announce(movedUp.format(it + 1, state.count)) }
                        })
                        if (i < state.queue.lastIndex) add(RowAction(downLabel) {
                            hub.move(i, up = false)?.let { focusIndex = it; pendingFocus = true; hub.announcer.announce(movedDown.format(it + 1, state.count)) }
                        })
                        add(RowAction(removeLabel) {
                            hub.removeAt(i)
                            focusIndex = i.coerceAtMost(state.queue.size - 2).coerceAtLeast(0)
                            pendingFocus = true
                            hub.announcer.announce(removed.format(item.title))
                        })
                    },
                )
            }
        }
    }

    if (naming) {
        val firstLocal = state.queue.firstOrNull { !it.id.startsWith("http", true) }?.id
        val dir = firstLocal?.let { File(it).parentFile }
        val savedText = stringResource(R.string.player_playlist_saved)
        val failedText = stringResource(R.string.player_playlist_save_failed)
        NameDialog(
            title = stringResource(R.string.player_save_playlist),
            confirmLabel = stringResource(R.string.action_save),
            initialName = stringResource(R.string.player_playlist_default_name),
            keepExtension = false,
            check = { NameValidator.check("$it.m3u8", dir?.list()?.toList().orEmpty()) },
            onConfirm = { name ->
                naming = false
                scope.launch {
                    val target = dir?.let { File(it, "$name.m3u8") }
                    val ok = target != null && withContext(Dispatchers.IO) {
                        runCatching { target.writeText(Playlists.toM3u(hub.queueEntries(), dir)) }.isSuccess
                    }
                    hub.announcer.announce(if (ok) savedText.format(target!!.name, dir.name) else failedText)
                }
            },
            onDismiss = { naming = false },
        )
    }
}
