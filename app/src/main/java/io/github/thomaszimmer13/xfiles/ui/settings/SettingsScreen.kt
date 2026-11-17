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
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as XFilesApplication).container.preferences

    val confirmTrash = preferences.confirmTrash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showHidden = preferences.sortOptions.map { it.showHidden }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val announcePosition = preferences.announcePosition.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val feedbackSounds = preferences.feedbackSounds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val confirmTrash by viewModel.confirmTrash.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val announcePosition by viewModel.announcePosition.collectAsStateWithLifecycle()
    val feedbackSounds by viewModel.feedbackSounds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.settings_title), onBack = onBack, titleFocus = titleFocus)
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
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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
