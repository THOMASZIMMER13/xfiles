package io.github.thomaszimmer13.xfiles.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequest
import io.github.thomaszimmer13.xfiles.ui.components.LocalAnnouncePosition
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.player.MiniPlayer
import io.github.thomaszimmer13.xfiles.ui.player.PlayerScreen
import io.github.thomaszimmer13.xfiles.ui.player.rememberPlayerHub
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.thomaszimmer13.xfiles.data.permission.StorageAccess
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.browser.BrowserStack
import io.github.thomaszimmer13.xfiles.ui.explorer.ExplorerScreen
import io.github.thomaszimmer13.xfiles.ui.favorites.FavoritesScreen
import io.github.thomaszimmer13.xfiles.ui.recents.RecentsScreen
import io.github.thomaszimmer13.xfiles.ui.transfer.TransferPanel
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

    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as XFilesApplication).container }
    val announcePosition by container.preferences.announcePosition.collectAsStateWithLifecycle(initialValue = true)
    CompositionLocalProvider(LocalAnnouncePosition provides announcePosition) {
        if (hasAccess) MainTabs() else PermissionGate()
    }
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
    // Toucher l'onglet déjà actif ramène à sa racine, comme sur iOS.
    var recentsReset by rememberSaveable { mutableIntStateOf(0) }
    var favoritesReset by rememberSaveable { mutableIntStateOf(0) }
    var explorerReset by rememberSaveable { mutableIntStateOf(0) }
    var explorerOpen by remember { mutableStateOf<String?>(null) }

    // Demande d'une autre app (partage, choix de fichier) ou raccourci : on va dans l'Explorer.
    val context = LocalContext.current
    val incoming = remember(context) { (context.applicationContext as XFilesApplication).container.incoming }
    val request by incoming.current.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request) {
            is IncomingRequest.Save, is IncomingRequest.Pick -> currentTab = AppTab.EXPLORER
            is IncomingRequest.OpenFolder -> {
                currentTab = AppTab.EXPLORER
                explorerOpen = r.path
                incoming.clear()
            }
            null -> Unit
        }
    }

    val playerHub = rememberPlayerHub()
    val playerOpen by playerHub.screenOpen.collectAsStateWithLifecycle()
    val playerMessage by playerHub.announcer.message.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // Lecteur ouvert par-dessus : les onglets restent en mémoire mais sont cachés à TalkBack.
        modifier = if (playerOpen) Modifier.clearAndSetSemantics { } else Modifier,
        bottomBar = {
          Column {
            // Lecture en cours : mini-lecteur au-dessus des onglets.
            MiniPlayer(playerHub)
            // Opération en cours, annonces et conflits : visibles depuis n'importe quel onglet.
            TransferPanel()
            // collectionInfo : permet à TalkBack d'annoncer la position (« 1 sur 3 »).
            NavigationBar(
                modifier = Modifier.semantics {
                    collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
                },
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = {
                            if (tab == currentTab) {
                                when (tab) {
                                    AppTab.RECENTS -> recentsReset++
                                    AppTab.FAVORITES -> favoritesReset++
                                    AppTab.EXPLORER -> explorerReset++
                                }
                            } else {
                                currentTab = tab
                            }
                        },
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
                    AppTab.RECENTS -> BrowserStack(recentsReset, rootLabel = stringResource(R.string.breadcrumb_go_recents)) { open, reveal, focusPath ->
                        RecentsScreen(onOpen = open, onReveal = reveal, focusPath = focusPath)
                    }
                    AppTab.FAVORITES -> BrowserStack(favoritesReset, rootLabel = stringResource(R.string.breadcrumb_go_favorites)) { open, reveal, focusPath ->
                        FavoritesScreen(onOpen = open, onReveal = reveal, focusPath = focusPath)
                    }
                    AppTab.EXPLORER -> ExplorerScreen(
                        resetSignal = explorerReset,
                        openRequest = explorerOpen,
                        onOpenRequestHandled = { explorerOpen = null },
                    )
                }
            }
        }
    }
    if (playerOpen) {
        Surface(Modifier.fillMaxSize()) { PlayerScreen(playerHub) }
    }
    // Annonces du lecteur (piste suivante, fin de liste…), lues où que l'on soit.
    // Zone d'annonce invisible à l'écran (les messages sont visibles dans le lecteur et le mini-lecteur).
    StatusMessage(playerMessage, Modifier.size(1.dp))
    }
}
