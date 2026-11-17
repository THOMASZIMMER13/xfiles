package io.github.thomaszimmer13.xfiles.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.TimeFormatter
import io.github.thomaszimmer13.xfiles.player.PlayerUi
import io.github.thomaszimmer13.xfiles.player.TrackOption

/** Choix unique dans une liste : chaque ligne est un bouton radio TalkBack. */
@Composable
private fun RadioList(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
        items.forEachIndexed { i, (label, selected) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(i) }),
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, content: @Composable () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = content,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    val labels = SPEEDS.map { speedLabel(it) to (it == current) }
    ChoiceDialog(stringResource(R.string.player_speed), { RadioList(labels) { onPick(SPEEDS[it]) } }, onDismiss)
}

private val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

@Composable
fun SleepDialog(state: PlayerUi, onMinutes: (Int) -> Unit, onEndOfTrack: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val off = state.sleepAt == null && !state.sleepAtEndOfTrack
    val items = buildList {
        add(stringResource(R.string.player_sleep_off) to off)
        SLEEP_MINUTES.forEach { add(pluralStringResource(R.plurals.player_minutes, it, it) to false) }
        add(stringResource(R.string.player_sleep_end_label) to state.sleepAtEndOfTrack)
    }
    ChoiceDialog(stringResource(R.string.player_sleep), {
        RadioList(items) { i ->
            when (i) {
                0 -> onCancel()
                items.lastIndex -> onEndOfTrack()
                else -> onMinutes(SLEEP_MINUTES[i - 1])
            }
        }
    }, onDismiss)
}

@Composable
fun TracksDialog(
    title: String,
    options: List<TrackOption>,
    offLabel: String?,
    offSelected: Boolean,
    onPick: (TrackOption) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val items = buildList {
        if (offLabel != null) add(offLabel to offSelected)
        options.forEach { add(it.label to (it.selected && !(offLabel != null && offSelected))) }
    }
    ChoiceDialog(title, {
        RadioList(items) { i ->
            if (offLabel != null && i == 0) onOff() else onPick(options[if (offLabel != null) i - 1 else i])
        }
    }, onDismiss)
}

/** « Aller à » : saisir un temps (« 12:30 » ou « 1:02:05 »). */
@Composable
fun GoToDialog(durationMs: Long, onGo: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val errorText = stringResource(R.string.player_goto_invalid)
    fun submit() {
        val ms = TimeFormatter.parse(text)
        if (ms == null || (durationMs > 0 && ms > durationMs)) invalid = true else onGo(ms)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_goto), modifier = Modifier.semantics { heading() }) },
        text = {
            Column {
                if (durationMs > 0) Text(stringResource(R.string.player_goto_hint, TimeFormatter.spoken(durationMs)))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    label = { Text(stringResource(R.string.player_goto_label)) },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { if (invalid) error(errorText) },
                )
                Text(
                    if (invalid) errorText else "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text(stringResource(R.string.player_goto_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
