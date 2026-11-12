package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.volumes.LocationKind
import io.github.thomaszimmer13.xfiles.data.volumes.StorageLocation
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar

/**
 * Racine de l'onglet Explorer : la liste des emplacements, façon « Parcourir » d'iOS.
 *
 * @param focusPath chemin d'où l'on revient : le focus est replacé sur l'emplacement correspondant.
 */
@Composable
fun ExplorerRootScreen(
    onOpen: (String) -> Unit,
    focusPath: String?,
    viewModel: ExplorerRootViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // L'espace libre a pu changer pendant que l'app était en arrière-plan.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val listState = rememberLazyListState()
    val itemFocus = remember { FocusRequester() }
    // L'emplacement exact (ex. Téléchargements) a priorité sur le volume qui le contient.
    val focusTarget = focusPath?.let { target ->
        state.locations.firstOrNull { it.path == target }
            ?: state.locations.filter { target.startsWith("${it.path}/") }.maxByOrNull { it.path.length }
    }

    FocusOnceEffect(ready = focusPath != null && state.locations.isNotEmpty()) {
        val index = state.locations.indexOf(focusTarget)
        if (index >= 0) {
            listState.scrollToItem(index + 1) // +1 : le titre de section est le premier élément
            withFrameNanos { }
            itemFocus.requestFocus()
        }
    }

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.tab_explorer))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            item(key = "header-device") {
                SectionHeader(stringResource(R.string.section_on_device))
            }
            items(state.locations, key = { it.path }) { location ->
                LocationRow(
                    location = location,
                    focusRequester = if (location == focusTarget) itemFocus else null,
                    onOpen = onOpen,
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
        // Hors de la liste pour être toujours présent, même si la liste défile.
        StatusMessage(state.announcement)
    }
}

@Composable
private fun LocationRow(location: StorageLocation, focusRequester: FocusRequester?, onOpen: (String) -> Unit) {
    val free = location.freeBytes
    val total = location.totalBytes
    val visibleDetail = if (free != null && total != null) {
        stringResource(R.string.location_space, SizeFormatter.short(free), SizeFormatter.short(total))
    } else {
        null
    }
    val spoken = if (free != null && total != null) {
        stringResource(
            R.string.location_spoken_with_space,
            location.name,
            SizeFormatter.spoken(free),
            SizeFormatter.spoken(total),
        )
    } else {
        location.name
    }
    val openLabel = stringResource(R.string.action_open)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
            // Un seul élément TalkBack par emplacement, avec un texte entièrement maîtrisé.
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick(label = openLabel) { onOpen(location.path); true }
            }
            .clickable { onOpen(location.path) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(location.kind.icon()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(location.name, style = MaterialTheme.typography.bodyLarge)
            if (visibleDetail != null) {
                Text(
                    visibleDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LocationKind.icon(): Int = when (this) {
    LocationKind.INTERNAL -> R.drawable.ic_location_internal
    LocationKind.DOWNLOADS -> R.drawable.ic_location_downloads
    LocationKind.DOCUMENTS -> R.drawable.ic_location_documents
    LocationKind.REMOVABLE -> R.drawable.ic_location_removable
}
