package io.github.thomaszimmer13.xfiles.ui.shizuku

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess
import io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuState
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * Données des applications (Android/data, obb) par Shizuku : état annoncé, bouton de l'étape
 * suivante, réglage d'affichage dans l'Explorer, puis guide pas à pas (sans root, avec root).
 */
@Composable
fun ShizukuScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as XFilesApplication).container }
    val shizuku = container.shizuku
    val state by shizuku.state.collectAsStateWithLifecycle()
    val shown by container.preferences.shizukuInExplorer.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val announcer = remember(scope) { Announcer(scope) }
    val announcement by announcer.message.collectAsStateWithLifecycle()
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { shizuku.refresh() }

    val stateText = stringResource(
        when (state) {
            ShizukuState.NOT_INSTALLED -> R.string.shizuku_state_not_installed
            ShizukuState.NOT_RUNNING -> R.string.shizuku_state_not_running
            ShizukuState.TOO_OLD -> R.string.shizuku_state_too_old
            ShizukuState.NO_PERMISSION -> R.string.shizuku_state_no_permission
            ShizukuState.DENIED -> R.string.shizuku_state_denied
            ShizukuState.READY -> R.string.shizuku_state_ready
        },
    )
    // Changement d'état pendant que l'écran est ouvert (Shizuku démarré, autorisation donnée).
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(state) {
        if (first[0]) first[0] = false else announcer.announce(stateText)
    }
    val notFound = stringResource(R.string.shizuku_no_app)

    fun start(vararg intents: Intent?) {
        for (intent in intents.filterNotNull()) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        announcer.announce(notFound)
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.shizuku_title), onBack = onBack, titleFocus = titleFocus)
        StatusMessage(announcement)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.shizuku_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            SectionHeader(stringResource(R.string.shizuku_state_header))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stateText, style = MaterialTheme.typography.bodyLarge)
                when (state) {
                    ShizukuState.NOT_INSTALLED, ShizukuState.TOO_OLD -> Button(onClick = { start(*shizuku.installIntents().toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state == ShizukuState.TOO_OLD) R.string.shizuku_update else R.string.shizuku_install))
                    }
                    ShizukuState.NOT_RUNNING, ShizukuState.DENIED -> Button(onClick = { start(shizuku.launchIntent()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shizuku_open_app))
                    }
                    ShizukuState.NO_PERMISSION -> Button(onClick = shizuku::requestPermission, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shizuku_allow))
                    }
                    ShizukuState.READY -> Button(onClick = { onOpen(Routes.remote(ShizukuAccess.SERVER_ID)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shizuku_browse))
                    }
                }
            }
            SwitchRow(
                label = stringResource(R.string.shizuku_show_in_explorer),
                detail = stringResource(R.string.shizuku_show_in_explorer_detail),
                checked = shown,
                onChange = { scope.launch { container.preferences.setShizukuInExplorer(it) } },
            )

            SectionHeader(stringResource(R.string.shizuku_guide_header))
            GuideText(R.string.shizuku_step_1)
            GuideText(R.string.shizuku_step_2)
            OutlinedButton(onClick = { start(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.shizuku_open_about))
            }
            GuideText(R.string.shizuku_step_3)
            OutlinedButton(onClick = { start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.shizuku_open_developer))
            }
            GuideText(R.string.shizuku_step_4)
            GuideText(R.string.shizuku_step_5)
            GuideText(R.string.shizuku_step_6)

            SectionHeader(stringResource(R.string.shizuku_restart_header))
            GuideText(R.string.shizuku_restart)
            SectionHeader(stringResource(R.string.shizuku_root_header))
            GuideText(R.string.shizuku_root)
            SectionHeader(stringResource(R.string.shizuku_safety_header))
            GuideText(R.string.shizuku_safety)
        }
    }
}

@Composable
private fun GuideText(id: Int) {
    Text(stringResource(id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}
