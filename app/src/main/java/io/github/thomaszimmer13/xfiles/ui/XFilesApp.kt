package io.github.thomaszimmer13.xfiles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.permission.StorageAccess
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.explorer.ExplorerScreen
import io.github.thomaszimmer13.xfiles.ui.permission.PermissionScreen

/**
 * Structure générale : chaque écran affiche sa propre barre du haut, la barre d'onglets est en bas.
 * Ordre de balayage TalkBack : barre du haut → contenu → onglets.
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

@Composable
private fun PermissionGate() {
    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            XFilesTopBar(title = stringResource(R.string.permission_title))
            PermissionScreen()
        }
    }
}

@Composable
private fun MainTabs() {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.EXPLORER) }
    val tabs = AppTab.entries
    // Chaque onglet garde son état (dossier ouvert, défilement) quand on passe à un autre, comme sur iOS.
    val tabStates = rememberSaveableStateHolder()

    Scaffold(
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
            tabStates.SaveableStateProvider(currentTab.name) {
                when (currentTab) {
                    AppTab.RECENTS -> PlaceholderTab(R.string.tab_recents, R.string.empty_recents)
                    AppTab.FAVORITES -> PlaceholderTab(R.string.tab_favorites, R.string.empty_favorites)
                    AppTab.EXPLORER -> ExplorerScreen()
                }
            }
        }
    }
}

/** Contenu provisoire d'un onglet : sa barre de titre et un simple message lisible. */
@Composable
private fun PlaceholderTab(title: Int, message: Int) {
    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(title))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
