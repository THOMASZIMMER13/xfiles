package io.github.thomaszimmer13.xfiles.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Titre de section, annoncé comme titre par TalkBack (navigation par titres possible). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * Ligne d'état lue automatiquement par TalkBack quand son texte change (zone « polie » :
 * TalkBack termine sa phrase en cours avant de la lire).
 *
 * Le composant reste toujours présent : vide, TalkBack l'ignore ; dès qu'un message
 * apparaît, c'est un changement de texte, ce qui déclenche l'annonce de façon fiable.
 */
@Composable
fun StatusMessage(message: String?, modifier: Modifier = Modifier) {
    Text(
        text = message.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (message.isNullOrEmpty()) 0.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
