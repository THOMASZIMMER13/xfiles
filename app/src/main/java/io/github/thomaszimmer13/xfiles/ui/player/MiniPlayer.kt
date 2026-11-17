package io.github.thomaszimmer13.xfiles.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.player.PlayerHub

/**
 * Barre au-dessus des onglets pendant une lecture. Un seul élément TalkBack :
 * « En lecture : Chanson, piste 3 sur 12 » (sans le temps, pour ne jamais être relu en boucle).
 * Appui double : ouvrir le lecteur. Actions : Pause ou Lecture, Suivant, Arrêter.
 */
@Composable
fun MiniPlayer(hub: PlayerHub) {
    val state by hub.state.collectAsStateWithLifecycle()
    val open by hub.screenOpen.collectAsStateWithLifecycle()
    if (!state.active || open) return

    val stateText = stringResource(if (state.isPlaying) R.string.player_mini_playing else R.string.player_mini_paused)
    val spoken = buildString {
        append(stateText).append(" : ").append(state.title)
        if (state.count > 1) append(", ").append(stringResource(R.string.player_track_of, state.index + 1, state.count))
    }
    val openLabel = stringResource(R.string.player_open)
    val toggleLabel = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play)
    val nextLabel = stringResource(R.string.player_next)
    val stopLabel = stringResource(R.string.player_stop)

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)) {
        if (state.hasDuration) {
            LinearProgressIndicator(
                progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = hub::openScreen)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    onClick(label = openLabel) { hub.openScreen(); true }
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) { hub.togglePlay(); true },
                        CustomAccessibilityAction(nextLabel) { hub.next(); true },
                        CustomAccessibilityAction(stopLabel) { hub.stop(); true },
                    )
                }
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(painterResource(if (state.isVideo) R.drawable.ic_video else R.drawable.ic_music), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                state.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = hub::togglePlay) {
                Icon(
                    painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = hub::next) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = null)
            }
        }
    }
}
