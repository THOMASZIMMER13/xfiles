package io.github.thomaszimmer13.xfiles.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.permission.StorageAccess
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage

/**
 * Affiché tant que l'accès à tous les fichiers n'est pas accordé.
 * Ordre de balayage : titre (barre du haut) → explication → bouton → message d'état.
 */
@Composable
fun PermissionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var settingsOpened by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val notGrantedMessage = stringResource(R.string.permission_not_granted)
    val noSettingsMessage = stringResource(R.string.permission_no_settings)

    // Au retour des réglages : si l'accès est accordé, l'app bascule d'elle-même sur les onglets.
    // Sinon, on l'annonce.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (settingsOpened && !StorageAccess.isGranted()) status = notGrantedMessage
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.permission_explanation),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = {
                // Vider le message d'abord : le même texte réaffiché au retour sera bien relu.
                status = null
                settingsOpened = true
                if (!StorageAccess.openSettings(context)) status = noSettingsMessage
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.permission_button))
        }
        StatusMessage(status)
    }
}
