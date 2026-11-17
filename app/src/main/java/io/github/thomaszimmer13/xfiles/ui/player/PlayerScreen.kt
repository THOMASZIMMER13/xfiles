package io.github.thomaszimmer13.xfiles.ui.player

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.TimeFormatter
import io.github.thomaszimmer13.xfiles.player.PlayerHub
import io.github.thomaszimmer13.xfiles.player.PlayerUi
import io.github.thomaszimmer13.xfiles.player.RepeatChoice
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.delay

/** Lecteur ouvert : quel panneau est affiché par-dessus. */
private enum class Panel { NONE, QUEUE, SPEED, SLEEP, AUDIO, SUBTITLES, GOTO }

/**
 * Écran du lecteur, au-dessus des onglets. Ordre de balayage :
 * Réduire → titre (en-tête) → artiste → [Depuis le début] → position → Précédent, Reculer, Lecture,
 * Avancer, Suivant → vitesse, répétition, aléatoire, minuteur, liste de lecture, [pistes, sous-titres] → Arrêter.
 * Vidéo : l'image d'abord (un seul élément), et avec TalkBack les commandes restent affichées.
 */
@Composable
fun PlayerScreen(hub: PlayerHub) {
    val state by hub.state.collectAsStateWithLifecycle()
    val pip by hub.pictureInPicture.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    val talkBack = rememberTalkBackOn()

    BackHandler {
        if (panel != Panel.NONE) panel = Panel.NONE else hub.closeScreen()
    }
    // Vidéo : l'écran reste allumé pendant la lecture.
    val view = LocalView.current
    DisposableEffect(state.isVideo, state.isPlaying) {
        view.keepScreenOn = state.isVideo && state.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    if (!state.active) {
        LaunchedEffect(Unit) { hub.closeScreen() }
        return
    }
    if (pip) {
        VideoSurface(hub, Modifier.fillMaxSize())
        return
    }
    if (panel == Panel.QUEUE) {
        QueuePanel(hub, state, onClose = { panel = Panel.NONE })
        return
    }

    // Vidéo sans TalkBack : commandes masquées après 3 secondes, un toucher les rappelle.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val autoHide = state.isVideo && state.isPlaying && !talkBack && panel == Panel.NONE
    LaunchedEffect(autoHide, lastTouch) {
        controlsVisible = true
        if (autoHide) {
            delay(3_000)
            controlsVisible = false
        }
    }

    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(if (state.isVideo) Color.Black else MaterialTheme.colorScheme.surface),
    ) {
        if (controlsVisible || !state.isVideo) {
            XFilesTopBar(
                title = state.title,
                spokenTitle = trackHeading(state),
                onBack = hub::closeScreen,
                titleFocus = titleFocus,
                backIcon = R.drawable.ic_back,
                backLabel = stringResource(R.string.player_minimize),
            )
        }
        if (state.isVideo) {
            val playing = state.isPlaying
            val videoLabel = stringResource(if (playing) R.string.player_video_playing else R.string.player_video_paused)
            val toggleLabel = stringResource(if (playing) R.string.player_pause else R.string.player_play)
            val backLabel = stringResource(R.string.player_skip_back, hub.settings.skipBackSeconds)
            val fwdLabel = stringResource(R.string.player_skip_forward, hub.settings.skipForwardSeconds)
            VideoSurface(
                hub,
                Modifier
                    .fillMaxWidth()
                    .weight(if (controlsVisible) 0.45f else 1f)
                    .clickable {
                        lastTouch = System.currentTimeMillis()
                        if (!controlsVisible) controlsVisible = true else hub.togglePlay()
                    }
                    .clearAndSetSemantics {
                        contentDescription = videoLabel
                        onClick(label = toggleLabel) { hub.togglePlay(); true }
                        customActions = listOf(
                            CustomAccessibilityAction(backLabel) { hub.skipBack(); true },
                            CustomAccessibilityAction(fwdLabel) { hub.skipForward(); true },
                        )
                    },
            )
        }
        if (controlsVisible || !state.isVideo) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(if (state.isVideo) 0.55f else 1f)
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState()),
            ) {
                Controls(hub, state, onPanel = { panel = it; lastTouch = System.currentTimeMillis() })
            }
        }
    }

    when (panel) {
        Panel.SPEED -> SpeedDialog(state.speed, onPick = { hub.setSpeed(it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
        Panel.SLEEP -> SleepDialog(
            state,
            onMinutes = { hub.sleepIn(it); panel = Panel.NONE },
            onEndOfTrack = { hub.sleepAtEndOfTrack(); panel = Panel.NONE },
            onCancel = { hub.cancelSleep(); panel = Panel.NONE },
            onDismiss = { panel = Panel.NONE },
        )
        Panel.AUDIO -> TracksDialog(
            title = stringResource(R.string.player_audio_tracks),
            options = state.audioTracks,
            offLabel = null,
            offSelected = false,
            onPick = { hub.selectTrack(C.TRACK_TYPE_AUDIO, it); panel = Panel.NONE },
            onOff = {},
            onDismiss = { panel = Panel.NONE },
        )
        Panel.SUBTITLES -> TracksDialog(
            title = stringResource(R.string.player_subtitles),
            options = state.textTracks,
            offLabel = stringResource(R.string.player_subtitles_off),
            offSelected = state.subtitlesOff,
            onPick = { hub.selectTrack(C.TRACK_TYPE_TEXT, it); panel = Panel.NONE },
            onOff = { hub.disableSubtitles(); panel = Panel.NONE },
            onDismiss = { panel = Panel.NONE },
        )
        Panel.GOTO -> GoToDialog(state.durationMs, onGo = { hub.seekTo(it); panel = Panel.NONE }, onDismiss = { panel = Panel.NONE })
        else -> Unit
    }
}

/** « Chanson, piste 3 sur 12 » : lu sur le titre de l'écran. */
@Composable
private fun trackHeading(state: PlayerUi): String =
    if (state.count > 1) stringResource(R.string.player_heading_n, state.title, state.index + 1, state.count) else state.title

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoSurface(hub: PlayerHub, modifier: Modifier) {
    val player = hub.player ?: return
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f
        PlayerSurface(player = player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.aspectRatio(ratio))
    }
}

@Composable
private fun Controls(hub: PlayerHub, state: PlayerUi, onPanel: (Panel) -> Unit) {
    val artistLine = listOfNotNull(state.artist, state.album).joinToString(", ")
    if (artistLine.isNotEmpty()) {
        Text(
            artistLine,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val resumed = state.resumedFromMs
    if (resumed != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.player_resumed_banner, TimeFormatter.short(resumed)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clearAndSetSemantics { },
            )
            TextButton(onClick = hub::restartFromBeginning) { Text(stringResource(R.string.player_from_start)) }
        }
    }

    SeekBar(hub, state, onGoTo = { onPanel(Panel.GOTO) })

    // Commandes de transport : un bouton par action, nommé selon l'état (« Lecture » / « Pause »).
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Transport(R.drawable.ic_skip_previous, stringResource(R.string.player_previous), hub::previous)
        Transport(R.drawable.ic_rewind, stringResource(R.string.player_skip_back, hub.settings.skipBackSeconds), hub::skipBack)
        FilledIconButton(onClick = hub::togglePlay, modifier = Modifier.size(72.dp)) {
            Icon(
                painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                modifier = Modifier.size(40.dp),
            )
        }
        Transport(R.drawable.ic_forward, stringResource(R.string.player_skip_forward, hub.settings.skipForwardSeconds), hub::skipForward)
        Transport(R.drawable.ic_skip_next, stringResource(R.string.player_next), hub::next)
    }

    HorizontalDivider()
    val changeLabel = stringResource(R.string.player_change)
    OptionRow(stringResource(R.string.player_speed), speedLabel(state.speed), changeLabel) { onPanel(Panel.SPEED) }
    OptionRow(
        stringResource(R.string.player_repeat),
        stringResource(
            when (state.repeat) {
                RepeatChoice.OFF -> R.string.player_repeat_off
                RepeatChoice.ALL -> R.string.player_repeat_all
                RepeatChoice.ONE -> R.string.player_repeat_one
            },
        ),
        changeLabel,
        hub::cycleRepeat,
    )
    SwitchOption(stringResource(R.string.player_shuffle), state.shuffle, hub::setShuffle)
    OptionRow(stringResource(R.string.player_sleep), sleepLabel(state), changeLabel) { onPanel(Panel.SLEEP) }
    OptionRow(
        stringResource(R.string.player_queue),
        stringResource(R.string.player_queue_detail, state.count),
        stringResource(R.string.action_open),
    ) { onPanel(Panel.QUEUE) }
    if (state.audioTracks.size > 1) {
        OptionRow(
            stringResource(R.string.player_audio_tracks),
            state.audioTracks.firstOrNull { it.selected }?.label ?: "",
            changeLabel,
        ) { onPanel(Panel.AUDIO) }
    }
    if (state.textTracks.isNotEmpty()) {
        OptionRow(
            stringResource(R.string.player_subtitles),
            if (state.subtitlesOff) stringResource(R.string.player_subtitles_off) else state.textTracks.firstOrNull { it.selected }?.label ?: "",
            changeLabel,
        ) { onPanel(Panel.SUBTITLES) }
    }
    OptionRow(stringResource(R.string.player_stop), null, stringResource(R.string.player_stop), hub::stop)
    Spacer(Modifier.heightIn(min = 16.dp))
}

/**
 * Position : un seul élément TalkBack, de type curseur.
 * Ce qui est lu est figé entre deux actions (avancer, reculer, changer de piste, pause) pour que
 * TalkBack ne relise pas la position à chaque seconde. Appui double : lire l'instant exact.
 * Glisser vers le haut ou le bas : avancer ou reculer de 10 secondes.
 */
@Composable
private fun SeekBar(hub: PlayerHub, state: PlayerUi, onGoTo: () -> Unit) {
    val duration = state.durationMs.takeIf { state.hasDuration } ?: 0L
    // Position « figée » pour TalkBack : mise à jour seulement sur un événement.
    var spokenPosition by remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.index, state.isPlaying, state.speed, duration) { spokenPosition = state.positionMs }
    var lastSeen by remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs) {
        // Un saut de plus de 2 s n'est pas la lecture normale : c'est un déplacement, on actualise.
        if (kotlin.math.abs(state.positionMs - lastSeen) > 2_000) spokenPosition = state.positionMs
        lastSeen = state.positionMs
    }

    val label = stringResource(R.string.player_position)
    val value = if (duration > 0) {
        stringResource(R.string.player_position_value, TimeFormatter.spoken(spokenPosition), TimeFormatter.spoken(duration))
    } else {
        TimeFormatter.spoken(spokenPosition)
    }
    val readNow = stringResource(R.string.player_read_position)
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.player_back_10)) { hub.seekBy(-10_000); true },
        CustomAccessibilityAction(stringResource(R.string.player_forward_10)) { hub.seekBy(10_000); true },
        CustomAccessibilityAction(stringResource(R.string.player_back_60)) { hub.seekBy(-60_000); true },
        CustomAccessibilityAction(stringResource(R.string.player_forward_60)) { hub.seekBy(60_000); true },
        CustomAccessibilityAction(stringResource(R.string.player_goto)) { onGoTo(); true },
    )
    val announceNow = {
        spokenPosition = state.positionMs
        hub.announcer.announce(
            if (duration > 0) {
                hub.announceText(R.string.player_position_value, TimeFormatter.spoken(state.positionMs), TimeFormatter.spoken(duration))
            } else {
                TimeFormatter.spoken(state.positionMs)
            },
        )
    }
    val seconds = (duration / 1000).toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value
                if (duration > 0) {
                    // Pas de 10 secondes pour les gestes haut et bas de TalkBack.
                    val steps = ((seconds / 10f).toInt() - 1).coerceIn(0, 10_000)
                    progressBarRangeInfo = ProgressBarRangeInfo(spokenPosition / 1000f, 0f..seconds, steps)
                    setProgress { target ->
                        hub.seekTo((target * 1000).toLong())
                        true
                    }
                }
                onClick(label = readNow) { announceNow(); true }
                customActions = actions
            },
    ) {
        Slider(
            value = (state.positionMs / 1000f).coerceIn(0f, seconds.coerceAtLeast(0.1f)),
            onValueChange = { hub.seekTo((it * 1000).toLong()) },
            valueRange = 0f..seconds.coerceAtLeast(0.1f),
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(TimeFormatter.short(state.positionMs), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) TimeFormatter.short(duration) else "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Transport(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
    }
}

/** Ligne « Vitesse, 1,25 fois » : un seul élément ; appui double = changer. */
@Composable
private fun OptionRow(label: String, value: String?, actionLabel: String, onClick: () -> Unit) {
    val spoken = if (value.isNullOrEmpty()) label else "$label, $value"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick(label = actionLabel) { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!value.isNullOrEmpty()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SwitchOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0').replace('.', ',')
    return stringResource(R.string.player_speed_value, text)
}

@Composable
private fun sleepLabel(state: PlayerUi): String {
    val at = state.sleepAt
    return when {
        state.sleepAtEndOfTrack -> stringResource(R.string.player_sleep_end_label)
        at != null -> {
            val minutes = ((at - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1).toInt()
            stringResource(R.string.player_sleep_remaining, minutes)
        }
        else -> stringResource(R.string.player_sleep_off)
    }
}

/** TalkBack (exploration au toucher) est-il actif ? Suivi en direct. */
@Composable
fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var on by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Le pilote du lecteur de l'app. */
@Composable
fun rememberPlayerHub(): PlayerHub {
    val context = LocalContext.current
    return remember { (context.applicationContext as XFilesApplication).container.player }
}
