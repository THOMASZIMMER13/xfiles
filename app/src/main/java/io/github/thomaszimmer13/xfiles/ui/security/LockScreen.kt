package io.github.thomaszimmer13.xfiles.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import kotlinx.coroutines.launch

/**
 * Écran de verrouillage d'XFiles : seul élément affiché (rien de l'app n'est lisible derrière).
 * La fenêtre d'identification s'ouvre d'elle-même ; « Déverrouiller » la rouvre.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit, onDisable: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_prompt_title)
    // Verrouillage d'écran du téléphone : la fenêtre d'Android peut toujours demander le code.
    val available = remember { Biometrics.deviceSecure(context) }
    val headingFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { headingFocus.requestFocus() }

    fun ask() {
        val activity = Biometrics.activity(context) ?: return
        scope.launch { if (Biometrics.prompt(activity, title) != null) onUnlocked() }
    }
    LaunchedEffect(Unit) { if (available) ask() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.focusRequester(headingFocus).focusable(),
            )
            if (available) {
                Text(stringResource(R.string.lock_message), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = ::ask, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_unlock)) }
            } else {
                // Plus aucun verrouillage sur le téléphone : impossible de vérifier l'identité.
                Text(stringResource(R.string.lock_no_method), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDisable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_disable_open)) }
            }
        }
    }
}

/** Confirmation d'identité avant d'ouvrir un serveur ou un compte. */
@Composable
fun IdentityGate(title: String, onConfirmed: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promptTitle = stringResource(R.string.security_servers_prompt)
    var failed by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }

    fun ask() {
        val activity = Biometrics.activity(context) ?: return
        // Pas de verrouillage d'écran : aucune identité vérifiable (un capteur indisponible ne suffit pas).
        if (!Biometrics.deviceSecure(context)) return onConfirmed()
        scope.launch {
            if (Biometrics.prompt(activity, promptTitle) != null) onConfirmed() else failed = true
        }
    }
    LaunchedEffect(Unit) { ask() }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = title, onBack = onBack, titleFocus = titleFocus)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (failed) R.string.security_servers_failed else R.string.security_servers_gate_message), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = ::ask, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.security_confirm)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
