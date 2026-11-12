package io.github.thomaszimmer13.xfiles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thomaszimmer13.xfiles.R

/**
 * Structure générale : titre en haut, contenu de l'onglet, barre d'onglets en bas.
 *
 * Ordre de balayage TalkBack : titre (titre sémantique) → contenu → onglets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XFilesApp() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.EXPLORER) }
    val tabs = AppTab.entries
    val currentTitle = stringResource(currentTab.label)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentTitle,
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
        bottomBar = {
            // collectionInfo : permet à TalkBack d'annoncer la position (« 1 sur 3 »).
            NavigationBar(
                modifier = Modifier.semantics {
                    collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
                },
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = { currentTab = tab },
                        // L'icône est décorative : le libellé suffit.
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                        modifier = Modifier.semantics {
                            collectionItemInfo = CollectionItemInfo(
                                rowIndex = 0,
                                rowSpan = 1,
                                columnIndex = index,
                                columnSpan = 1,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        // paneTitle : signale à TalkBack que le contenu principal a changé d'écran.
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .semantics { paneTitle = currentTitle },
        ) {
            when (currentTab) {
                AppTab.RECENTS -> PlaceholderTab(stringResource(R.string.empty_recents))
                AppTab.FAVORITES -> PlaceholderTab(stringResource(R.string.empty_favorites))
                AppTab.EXPLORER -> PlaceholderTab(stringResource(R.string.placeholder_explorer))
            }
        }
    }
}

/** Contenu provisoire d'un onglet : un simple message lisible. */
@Composable
private fun PlaceholderTab(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
