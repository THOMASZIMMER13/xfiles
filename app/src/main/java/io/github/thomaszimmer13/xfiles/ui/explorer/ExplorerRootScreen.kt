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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
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
 * Racine de l'onglet Explorer, façon « Parcourir » d'iOS :
 * « Sur l'appareil » (mémoire interne, carte SD, clé USB) puis « Favoris » (dossiers épinglés).
 *
 * @param focusPath chemin d'où l'on revient : le focus est replacé sur la ligne correspondante.
 */
@Composable
fun ExplorerRootScreen(
    onOpen: (String) -> Unit,
    focusPath: String?,
    viewModel: ExplorerRootViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()

    // L'espace libre et l'existence des favoris ont pu changer pendant que l'app était en arrière-plan.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // Indices dans la liste : 0 = titre « Sur l'appareil », puis les volumes,
    // puis le titre « Favoris » et les favoris.
    val favoritesStart = 1 + state.locations.size + 1
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }

    // Un favori exact (ex. Documents) a priorité sur le volume qui le contient.
    val focusIndex: Int? = focusPath?.let { target ->
        state.favorites.indexOfFirst { it.path == target }.takeIf { it >= 0 }?.let { favoritesStart + it }
            ?: state.locations.indexOfFirst { it.path == target }.takeIf { it >= 0 }?.let { 1 + it }
            ?: state.locations.withIndex()
                .filter { target.startsWith("${it.value.path}/") }
                .maxByOrNull { it.value.path.length }?.let { 1 + it.index }
    }

    FocusOnceEffect(ready = focusPath != null && state.locations.isNotEmpty()) {
        val index = focusIndex ?: return@FocusOnceEffect
        listState.scrollToItem(index)
        withFrameNanos { }
        rowFocus.requestFocus()
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
            items(state.locations, key = { "loc:" + it.path }) { location ->
                LocationRow(
                    location = location,
                    focusRequester = if (focusIndex == 1 + state.locations.indexOf(location)) rowFocus else null,
                    onOpen = onOpen,
                )
            }
            if (state.favorites.isNotEmpty()) {
                item(key = "header-favorites") {
                    SectionHeader(stringResource(R.string.section_favorites))
                }
                items(state.favorites, key = { "fav:" + it.path }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        focusRequester = if (focusIndex == favoritesStart + state.favorites.indexOf(favorite)) rowFocus else null,
                        onOpen = onOpen,
                        onRemove = viewModel::removeFavorite,
                    )
                }
            }
        }
        // Hors de la liste pour être toujours présent, même si la liste défile.
        StatusMessage(announcement)
    }
}

@Composable
private fun LocationRow(location: StorageLocation, focusRequester: FocusRequester?, onOpen: (String) -> Unit) {
    val free = location.freeBytes
    val total = location.totalBytes
    val hasSpace = free != null && total != null
    val visibleDetail = if (hasSpace) {
        stringResource(R.string.location_space, SizeFormatter.short(free!!), SizeFormatter.short(total!!))
    } else {
        null
    }
    val spoken = if (hasSpace) {
        stringResource(R.string.location_spoken_with_space, location.name, SizeFormatter.spoken(free!!), SizeFormatter.spoken(total!!))
    } else {
        location.name
    }
    val openLabel = stringResource(R.string.action_open)

    RootRow(
        icon = if (location.kind == LocationKind.INTERNAL) R.drawable.ic_location_internal else R.drawable.ic_location_removable,
        title = location.name,
        detail = visibleDetail,
        focusRequester = focusRequester,
        onClick = { onOpen(location.path) },
        semantics = {
            contentDescription = spoken
            onClick(label = openLabel) { onOpen(location.path); true }
        },
    )
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteFolder,
    focusRequester: FocusRequester?,
    onOpen: (String) -> Unit,
    onRemove: (FavoriteFolder) -> Unit,
) {
    val where = favorite.parents.joinToString(", ")
    val missing = stringResource(R.string.favorite_missing)
    val spoken = buildString {
        append(favorite.name)
        if (where.isNotEmpty()) append(", ").append(stringResource(R.string.favorite_in, where))
        if (!favorite.exists) append(", ").append(missing)
    }
    val openLabel = stringResource(R.string.action_open)
    val removeLabel = stringResource(R.string.action_remove_favorite)

    RootRow(
        icon = R.drawable.ic_favorite,
        title = favorite.name,
        detail = if (favorite.exists) favorite.parents.joinToString(" › ") else missing,
        focusRequester = focusRequester,
        onClick = { if (favorite.exists) onOpen(favorite.path) },
        semantics = {
            contentDescription = spoken
            if (favorite.exists) onClick(label = openLabel) { onOpen(favorite.path); true }
            customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(favorite); true })
        },
    )
}

/** Ligne de la racine : un seul élément TalkBack, dont le texte et les actions sont fournis par [semantics]. */
@Composable
private fun RootRow(
    icon: Int,
    title: String,
    detail: String?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    semantics: SemanticsPropertyReceiver.() -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clearAndSetSemantics(semantics)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}
