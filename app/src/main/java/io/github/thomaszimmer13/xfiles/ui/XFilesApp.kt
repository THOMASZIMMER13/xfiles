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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.permission.StorageAccess
import io.github.thomaszimmer13.xfiles.ui.explorer.ExplorerRootScreen
import io.github.thomaszimmer13.xfiles.ui.permission.PermissionScreen

/**
 * Structure générale : titre en haut, contenu, barre d'onglets en bas.
 * Ordre de balayage TalkBack : titre (titre sémantique) → contenu → onglets.
 *
 * Sans l'accès à tous les fichiers, seul l'écran d'autorisation est affiché.
 */
@Composable
fun XFilesApp() {
    var hasAccess by remember { mutableStateOf(StorageAccess.isGranted()) }
    // Vérifié à chaque retour dans l'app : l'utilisateur a pu accorder ou retirer l'accès.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = StorageAccess.isGranted() }

    if (hasAccess) MainTabs() else PermissionGate()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionGate() {
    Scaffold(
        topBar = { TopAppBar(title = { ScreenTitle(stringResource(R.string.permission_title)) }) },
    ) { innerPadding ->
        PermissionScreen(Modifier.padding(innerPadding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.EXPLORER) }
    val tabs = AppTab.entries

    Scaffold(
        topBar = { TopAppBar(title = { ScreenTitle(stringResource(currentTab.label)) }) },
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
        // Pas de paneTitle ici : TalkBack annonçait le titre en double avec l'onglet sélectionné.
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (currentTab) {
                AppTab.RECENTS -> PlaceholderTab(stringResource(R.string.empty_recents))
                AppTab.FAVORITES -> PlaceholderTab(stringResource(R.string.empty_favorites))
                AppTab.EXPLORER -> ExplorerRootScreen()
            }
        }
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text = text, modifier = Modifier.semantics { heading() })
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
