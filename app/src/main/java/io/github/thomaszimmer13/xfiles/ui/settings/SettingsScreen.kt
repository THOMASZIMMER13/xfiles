package io.github.thomaszimmer13.xfiles.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.ocr.OcrModelState
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as XFilesApplication).container.preferences

    val confirmTrash = preferences.confirmTrash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showHidden = preferences.sortOptions.map { it.showHidden }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val announcePosition = preferences.announcePosition.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val feedbackSounds = preferences.feedbackSounds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val player = preferences.playerSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreferencesRepository.PlayerSettings())

    val builtinDocuments = preferences.builtinDocuments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val explorerTabs = preferences.explorerTabs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val dualPane = preferences.dualPane.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setExplorerTabs(value: Boolean) {
        viewModelScope.launch { preferences.setExplorerTabs(value) }
    }

    fun setDualPane(value: Boolean) {
        viewModelScope.launch { preferences.setDualPane(value) }
    }

    private val ocr = (application as XFilesApplication).container.ocr
    val ocrState = ocr.state
    val ocrSize = ocr.downloadSize

    fun downloadOcr() = ocr.download()

    fun cancelOcrDownload() = ocr.cancelDownload()

    fun deleteOcr() {
        viewModelScope.launch { ocr.deleteModels() }
    }

    fun setBuiltinDocuments(value: Boolean) {
        viewModelScope.launch { preferences.setBuiltinDocuments(value) }
    }

    fun setBuiltinPlayer(value: Boolean) {
        viewModelScope.launch { preferences.setBuiltinPlayer(value) }
    }

    fun setReadSubtitles(value: Boolean) {
        viewModelScope.launch { preferences.setReadSubtitles(value) }
    }

    fun setSkipBack(seconds: Int) {
        viewModelScope.launch { preferences.setSkipBack(seconds) }
    }

    fun setSkipForward(seconds: Int) {
        viewModelScope.launch { preferences.setSkipForward(seconds) }
    }

    fun setAnnouncePosition(value: Boolean) {
        viewModelScope.launch { preferences.setAnnouncePosition(value) }
    }

    fun setFeedbackSounds(value: Boolean) {
        viewModelScope.launch { preferences.setFeedbackSounds(value) }
    }

    fun setConfirmTrash(value: Boolean) {
        viewModelScope.launch { preferences.setConfirmTrash(value) }
    }

    fun setShowHidden(value: Boolean) {
        viewModelScope.launch { preferences.setShowHidden(value) }
    }
}

/**
 * Réglages. Ordre de balayage : Retour → titre → interrupteurs → « À propos ».
 * Chaque interrupteur est un seul élément TalkBack : « Afficher les fichiers cachés, désactivé, interrupteur ».
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}, viewModel: SettingsViewModel = viewModel()) {
    val confirmTrash by viewModel.confirmTrash.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val announcePosition by viewModel.announcePosition.collectAsStateWithLifecycle()
    val feedbackSounds by viewModel.feedbackSounds.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val builtinDocuments by viewModel.builtinDocuments.collectAsStateWithLifecycle()
    val explorerTabs by viewModel.explorerTabs.collectAsStateWithLifecycle()
    val dualPane by viewModel.dualPane.collectAsStateWithLifecycle()
    val ocrState by viewModel.ocrState.collectAsStateWithLifecycle()
    var confirmDeleteOcr by remember { mutableStateOf(false) }
    if (confirmDeleteOcr) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOcr = false },
            title = { Text(stringResource(R.string.ocr_delete_title), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.ocr_delete_message)) },
            confirmButton = { TextButton(onClick = { confirmDeleteOcr = false; viewModel.deleteOcr() }) { Text(stringResource(R.string.ocr_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDeleteOcr = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    var skipDialog by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.settings_title), onBack = onBack, titleFocus = titleFocus)
        val securityScope = androidx.compose.runtime.rememberCoroutineScope()
        val securityAnnouncer = remember(securityScope) { io.github.thomaszimmer13.xfiles.ui.components.Announcer(securityScope) }
        val securityMessage by securityAnnouncer.message.collectAsStateWithLifecycle()
        StatusMessage(securityMessage)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_behavior))
            SwitchRow(
                label = stringResource(R.string.settings_confirm_trash),
                detail = stringResource(R.string.settings_confirm_trash_detail),
                checked = confirmTrash,
                onChange = viewModel::setConfirmTrash,
            )
            SwitchRow(
                label = stringResource(R.string.show_hidden),
                detail = null,
                checked = showHidden,
                onChange = viewModel::setShowHidden,
            )

            SectionHeader(stringResource(R.string.settings_section_explorer))
            SwitchRow(
                label = stringResource(R.string.settings_explorer_tabs),
                detail = stringResource(R.string.settings_explorer_tabs_detail),
                checked = explorerTabs,
                onChange = viewModel::setExplorerTabs,
            )
            SwitchRow(
                label = stringResource(R.string.settings_dual_pane),
                detail = stringResource(R.string.settings_dual_pane_detail),
                checked = dualPane,
                onChange = viewModel::setDualPane,
            )

            SecuritySection(onOpen, securityAnnouncer)

            SectionHeader(stringResource(R.string.settings_section_accessibility))
            SwitchRow(
                label = stringResource(R.string.settings_announce_position),
                detail = stringResource(R.string.settings_announce_position_detail),
                checked = announcePosition,
                onChange = viewModel::setAnnouncePosition,
            )
            SwitchRow(
                label = stringResource(R.string.settings_sounds),
                detail = stringResource(R.string.settings_sounds_detail),
                checked = feedbackSounds,
                onChange = viewModel::setFeedbackSounds,
            )

            SectionHeader(stringResource(R.string.settings_section_player))
            SwitchRow(
                label = stringResource(R.string.settings_builtin_player),
                detail = stringResource(R.string.settings_builtin_player_detail),
                checked = player.builtin,
                onChange = viewModel::setBuiltinPlayer,
            )
            ChoiceRow(
                label = stringResource(R.string.settings_skip_back),
                value = stringResource(R.string.settings_seconds, player.skipBackSeconds),
                onClick = { skipDialog = false },
            )
            ChoiceRow(
                label = stringResource(R.string.settings_skip_forward),
                value = stringResource(R.string.settings_seconds, player.skipForwardSeconds),
                onClick = { skipDialog = true },
            )
            SwitchRow(
                label = stringResource(R.string.settings_read_subtitles),
                detail = stringResource(R.string.settings_read_subtitles_detail),
                checked = player.readSubtitles,
                onChange = viewModel::setReadSubtitles,
            )

            SectionHeader(stringResource(R.string.settings_section_documents))
            SwitchRow(
                label = stringResource(R.string.settings_builtin_documents),
                detail = stringResource(R.string.settings_builtin_documents_detail),
                checked = builtinDocuments,
                onChange = viewModel::setBuiltinDocuments,
            )
            OcrModelRow(
                state = ocrState,
                size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, viewModel.ocrSize),
                onDownload = viewModel::downloadOcr,
                onCancel = viewModel::cancelOcrDownload,
                onDelete = { confirmDeleteOcr = true },
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            Text(
                stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.settings_license),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.settings_source))
            }
        }
    }

    when (skipDialog) {
        false -> SkipDialog(
            stringResource(R.string.settings_skip_back),
            listOf(5, 10, 15, 30),
            player.skipBackSeconds,
            onPick = { viewModel.setSkipBack(it); skipDialog = null },
            onDismiss = { skipDialog = null },
        )
        true -> SkipDialog(
            stringResource(R.string.settings_skip_forward),
            listOf(10, 15, 30, 60),
            player.skipForwardSeconds,
            onPick = { viewModel.setSkipForward(it); skipDialog = null },
            onDismiss = { skipDialog = null },
        )
        null -> Unit
    }
}

/** Modèles de reconnaissance : état, et une seule action selon l'état (télécharger, annuler, supprimer). */
@Composable
private fun OcrModelRow(state: OcrModelState, size: String, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val label = stringResource(R.string.settings_ocr)
    val value = when (state) {
        OcrModelState.Missing -> stringResource(R.string.settings_ocr_missing, size)
        is OcrModelState.Downloading -> stringResource(R.string.ocr_downloading, (state.fraction * 100).toInt())
        OcrModelState.Ready -> stringResource(R.string.settings_ocr_ready, size)
        is OcrModelState.Failed -> stringResource(R.string.settings_ocr_failed)
    }
    val (actionLabel, action) = when (state) {
        is OcrModelState.Downloading -> stringResource(R.string.action_cancel) to onCancel
        OcrModelState.Ready -> stringResource(R.string.ocr_delete) to onDelete
        else -> stringResource(R.string.ocr_models_download) to onDownload
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = action)
            .clearAndSetSemantics {
                contentDescription = "$label, $value"
                onClick(label = actionLabel.replaceFirstChar { it.lowercase() }) { action(); true }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit) {
    val change = stringResource(R.string.player_change)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$label, $value"
                onClick(label = change) { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Choix d'une durée de saut en boutons radio. */
@Composable
private fun SkipDialog(title: String, choices: List<Int>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.selectableGroup()) {
                choices.forEach { seconds ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = seconds == current, role = Role.RadioButton, onClick = { onPick(seconds) }),
                    ) {
                        RadioButton(selected = seconds == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_seconds, seconds))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private const val SOURCE_URL = "https://github.com/THOMASZIMMER13/xfiles"

/**
 * Sécurité : verrouillage d'XFiles (et délai), confirmation avant les serveurs, accès aux données
 * des applications par Shizuku. Activer ou désactiver demande l'identité (pas de contournement).
 */
@Composable
private fun SecuritySection(onOpen: (String) -> Unit, announcer: io.github.thomaszimmer13.xfiles.ui.components.Announcer) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as io.github.thomaszimmer13.xfiles.XFilesApplication).container }
    val lock = container.appLock
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val delay by lock.delay.collectAsStateWithLifecycle()
    val protect by lock.protectServers.collectAsStateWithLifecycle()
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var delayDialog by remember { mutableStateOf(false) }
    val confirmTitle = stringResource(R.string.security_confirm_change)
    val noMethod = stringResource(R.string.security_no_method)

    fun confirmThen(action: () -> Unit) {
        val Bio = io.github.thomaszimmer13.xfiles.ui.security.Biometrics
        if (!Bio.deviceSecure(context)) return announcer.announce(noMethod)
        val activity = Bio.activity(context) ?: return
        scope.launch { if (Bio.prompt(activity, confirmTitle) != null) action() }
    }

    val delayLabels = io.github.thomaszimmer13.xfiles.data.security.AppLock.DELAYS.associateWith { seconds ->
        if (seconds == 0) stringResource(R.string.security_delay_now)
        else androidx.compose.ui.res.pluralStringResource(R.plurals.security_delay_minutes, seconds / 60, seconds / 60)
    }
    fun delayLabel(seconds: Int) = delayLabels[seconds].orEmpty()
    val lockOn = stringResource(R.string.security_lock_on)
    val lockOff = stringResource(R.string.security_lock_off)

    SectionHeader(stringResource(R.string.settings_section_security))
    SwitchRow(
        label = stringResource(R.string.security_lock),
        detail = stringResource(R.string.security_lock_detail),
        checked = enabled,
        onChange = { value -> confirmThen { lock.setEnabled(value); announcer.announce(if (value) lockOn else lockOff) } },
    )
    if (enabled) {
        ChoiceRow(stringResource(R.string.security_delay), delayLabel(delay)) { delayDialog = true }
    }
    SwitchRow(
        label = stringResource(R.string.security_servers),
        detail = stringResource(R.string.security_servers_detail),
        checked = protect,
        onChange = { value -> confirmThen { lock.setProtectServers(value) } },
    )
    val shizukuValue = stringResource(
        when (shizukuState) {
            io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuState.READY -> R.string.shizuku_short_ready
            io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuState.NOT_INSTALLED -> R.string.shizuku_short_not_installed
            else -> R.string.shizuku_short_todo
        },
    )
    ChoiceRow(stringResource(R.string.shizuku_title), shizukuValue) { onOpen(io.github.thomaszimmer13.xfiles.ui.browser.Routes.SHIZUKU) }

    if (delayDialog) {
        AlertDialog(
            onDismissRequest = { delayDialog = false },
            title = { Text(stringResource(R.string.security_delay), modifier = Modifier.semantics { heading() }) },
            text = {
                Column(Modifier.selectableGroup()) {
                    io.github.thomaszimmer13.xfiles.data.security.AppLock.DELAYS.forEach { seconds ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = seconds == delay, role = Role.RadioButton, onClick = { lock.setDelay(seconds); delayDialog = false }),
                        ) {
                            RadioButton(selected = seconds == delay, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(delayLabel(seconds))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { delayDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
