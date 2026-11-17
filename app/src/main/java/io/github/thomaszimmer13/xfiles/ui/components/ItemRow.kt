package io.github.thomaszimmer13.xfiles.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Action personnalisée TalkBack d'une ligne. */
class RowAction(val label: String, val run: () -> Unit)

/**
 * Ligne de liste : un seul élément TalkBack qui lit [spoken] ; appui double = [onClick] (« Appuyez deux fois
 * pour [clickLabel] ») ; les autres actions sont des actions personnalisées, dans l'ordre de [actions].
 * Utilisée par la recherche, les récents, les favoris et les archives.
 */
@Composable
fun ItemRow(
    icon: Int,
    title: String,
    detail: String?,
    spoken: String,
    clickLabel: String?,
    onClick: (() -> Unit)?,
    actions: List<RowAction> = emptyList(),
    focusRequester: FocusRequester? = null,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (onClick != null) onClick(label = clickLabel) { onClick(); true }
                    if (actions.isNotEmpty()) customActions = actions.map { a -> CustomAccessibilityAction(a.label) { a.run(); true } }
                }
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
    }
}

/** Icône d'un élément : dossier, archive ou fichier. */
fun itemIcon(name: String, isDirectory: Boolean): Int = when {
    isDirectory -> io.github.thomaszimmer13.xfiles.R.drawable.ic_tab_explorer
    io.github.thomaszimmer13.xfiles.data.archive.isArchive(name) -> io.github.thomaszimmer13.xfiles.R.drawable.ic_archive
    else -> io.github.thomaszimmer13.xfiles.R.drawable.ic_file
}
