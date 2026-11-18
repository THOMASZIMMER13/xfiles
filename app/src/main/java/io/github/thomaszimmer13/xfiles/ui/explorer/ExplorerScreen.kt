package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.remote.RemoteClient
import io.github.thomaszimmer13.xfiles.domain.Breadcrumbs
import io.github.thomaszimmer13.xfiles.ui.browser.BrowserStack
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import java.io.File

/** Route de la corbeille dans la pile de l'onglet. */
const val TRASH_ROUTE = Routes.TRASH

/** Dossier affiché par l'autre panneau (null : il n'affiche pas un dossier du téléphone). */
class OtherPane(val dir: String?, val name: String)

/**
 * Ce que les écrans d'un panneau peuvent faire en plus : nouvel onglet, envoi vers l'autre panneau.
 * [isActive] : faux pour le panneau inactif du double panneau, qui ne prend alors jamais le focus
 * initial (audit M-10, voir FocusOnceEffect).
 */
class PaneActions(val openInNewTab: ((String) -> Unit)?, val otherPane: OtherPane?, val isActive: Boolean = true)

val LocalPaneActions = staticCompositionLocalOf { PaneActions(null, null) }

/** Magasins de ViewModel du second panneau : gardés pendant la rotation de l'écran. */
class PaneStores : ViewModel() {
    val second = ViewModelStore()

    override fun onCleared() = second.clear()
}

/**
 * Onglet Explorer : racine (emplacements, corbeille, favoris), puis les dossiers ouverts.
 * Réglages facultatifs : onglets (plusieurs dossiers ouverts, comme dans un navigateur) et double
 * panneau (deux dossiers ensemble, côte à côte sur grand écran, l'un au-dessus de l'autre sinon).
 * @param resetSignal incrémenté quand l'onglet déjà actif est touché à nouveau : retour à la racine.
 */
@Composable
fun ExplorerScreen(resetSignal: Int = 0, openRequest: String? = null, onOpenRequestHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as XFilesApplication).container }
    val tabsOn by container.preferences.explorerTabs.collectAsStateWithLifecycle(initialValue = false)
    val dualOn by container.preferences.dualPane.collectAsStateWithLifecycle(initialValue = false)

    // État de chaque panneau gardé par clé : il survit au passage côte à côte ↔ l'un sous l'autre.
    val paneStates = rememberSaveableStateHolder()
    if (!dualOn) {
        paneStates.SaveableStateProvider("pane0") {
            ExplorerPane(index = 0, tabsOn = tabsOn, resetSignal = resetSignal, openRequest = openRequest, onOpenRequestHandled = onOpenRequestHandled)
        }
        return
    }

    var active by rememberSaveable { mutableIntStateOf(0) }
    val currentDirs = remember { mutableStateMapOf<Int, String?>() }
    val names = remember { mutableStateMapOf<Int, String>() }
    val stores: PaneStores = viewModel()
    val activityOwner = LocalViewModelStoreOwner.current
    val secondOwner = remember(stores, activityOwner) { PaneOwner(stores.second, activityOwner, context.applicationContext as android.app.Application) }

    @Composable
    fun pane(index: Int, modifier: Modifier) {
        val other = 1 - index
        Column(modifier) { paneStates.SaveableStateProvider("pane$index") {
            Text(
                stringResource(if (index == 0) R.string.pane_first else R.string.pane_second, names[index].orEmpty()),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (active == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { heading() },
            )
            CompositionLocalProvider(LocalViewModelStoreOwner provides (if (index == 0) activityOwner!! else secondOwner)) {
                ExplorerPane(
                    index = index,
                    tabsOn = tabsOn,
                    resetSignal = if (index == 0) resetSignal else 0,
                    openRequest = if (index == 0) openRequest else null,
                    onOpenRequestHandled = onOpenRequestHandled,
                    backEnabled = active == index,
                    onActive = { active = index },
                    otherPane = OtherPane(currentDirs[other], names[other].orEmpty()),
                    onLocation = { dir, name ->
                        currentDirs[index] = dir
                        names[index] = name
                    },
                )
            }
        } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                pane(0, Modifier.weight(1f))
                VerticalDivider()
                pane(1, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                pane(0, Modifier.weight(1f))
                HorizontalDivider(thickness = 2.dp)
                pane(1, Modifier.weight(1f))
            }
        }
    }
}

/** Propriétaire de ViewModel du second panneau : ses écrans ne partagent pas l'état du premier. */
private class PaneOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner?,
    private val app: android.app.Application,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.AndroidViewModelFactory.getInstance(app)

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras((parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty).apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
        }
}

/**
 * Un panneau : une pile de navigation, ou plusieurs (une par onglet) avec la barre d'onglets en haut.
 * Chaque onglet garde son dossier et son défilement.
 */
@Composable
private fun ExplorerPane(
    index: Int,
    tabsOn: Boolean,
    resetSignal: Int,
    openRequest: String?,
    onOpenRequestHandled: () -> Unit,
    backEnabled: Boolean = true,
    onActive: () -> Unit = {},
    otherPane: OtherPane? = null,
    onLocation: (dir: String?, name: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as XFilesApplication).container }
    val tabIds = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf(1) }
    var currentId by rememberSaveable { mutableIntStateOf(1) }
    var nextId by rememberSaveable { mutableIntStateOf(2) }
    // Route affichée par chaque onglet (pour son nom) ; route à ouvrir dans un onglet tout juste créé.
    val routes = rememberSaveable(saver = mapSaver()) { mutableStateMapOf() }
    val pendingOpen = remember { mutableStateMapOf<Int, String>() }
    val holder = rememberSaveableStateHolder()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val announcer = remember(scope) { Announcer(scope) }
    val announcement by announcer.message.collectAsStateWithLifecycle()

    // Onglets désactivés : seul l'onglet actif reste.
    LaunchedEffect(tabsOn) {
        if (!tabsOn && tabIds.size > 1) {
            tabIds.removeAll { it != currentId }
        }
    }
    if (currentId !in tabIds) currentId = tabIds.first()

    val explorerName = stringResource(R.string.tab_explorer)
    fun label(id: Int): String = routeLabel(routes[id], container, explorerName)

    LaunchedEffect(routes[currentId]) {
        val route = routes[currentId]
        val dir = (route?.let(Routes::parse) as? Routes.Route.Folder)?.path
        onLocation(dir, label(currentId))
    }

    val newTabOpened = stringResource(R.string.tab_new_opened)
    val tabClosed = stringResource(R.string.tab_closed)
    fun openTab(route: String?) {
        val id = nextId++
        tabIds.add(tabIds.indexOf(currentId) + 1, id)
        if (route != null) pendingOpen[id] = route
        currentId = id
        onActive()
        announcer.announce(newTabOpened.format(tabIds.indexOf(id) + 1, tabIds.size))
    }

    fun closeTab(id: Int) {
        if (tabIds.size <= 1) return
        val position = tabIds.indexOf(id)
        tabIds.remove(id)
        routes.remove(id)
        holder.removeState(id)
        if (currentId == id) currentId = tabIds[(position).coerceAtMost(tabIds.lastIndex)]
        announcer.announce(tabClosed.format(label(currentId)))
    }

    val actions = PaneActions(openInNewTab = if (tabsOn) { route -> openTab(route) } else null, otherPane = otherPane, isActive = backEnabled)
    Column(Modifier.fillMaxSize()) {
        if (tabsOn) {
            TabStrip(
                ids = tabIds,
                current = currentId,
                label = ::label,
                onSelect = { id ->
                    onActive()
                    currentId = id
                },
                onClose = ::closeTab,
                onNew = { openTab(null) },
            )
            StatusMessage(announcement)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalPaneActions provides actions) {
                val id = currentId
                holder.SaveableStateProvider(id) {
                    BrowserStack(
                        if (id == tabIds.first()) resetSignal else 0,
                        rootLabel = stringResource(R.string.breadcrumb_go_root),
                        openRequest = pendingOpen[id] ?: if (id == tabIds.first()) openRequest else null,
                        onOpenRequestHandled = { if (pendingOpen.remove(id) == null) onOpenRequestHandled() },
                        onCurrentChange = { routes[id] = it },
                        backEnabled = backEnabled,
                        onActive = onActive,
                    ) { open, _, focusPath ->
                        ExplorerRootScreen(onOpen = open, focusPath = focusPath)
                    }
                }
            }
        }
    }
}

private fun mapSaver() = listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<Int, String?>, String>(
    save = { map -> map.flatMap { (k, v) -> listOf(k.toString(), v ?: "\u0000") } },
    restore = { list ->
        mutableStateMapOf<Int, String?>().apply {
            list.chunked(2).forEach { (k, v) -> put(k.toInt(), v.takeIf { it != "\u0000" }) }
        }
    },
)

/** Nom d'un onglet : dossier affiché (« Documents »), archive, serveur, ou « Explorer » à la racine. */
private fun routeLabel(route: String?, container: io.github.thomaszimmer13.xfiles.AppContainer, root: String): String =
    when (val r = route?.let(Routes::parse)) {
        null -> root
        is Routes.Route.Folder -> runCatching { Breadcrumbs.of(r.path, container.volumeRepository.volumeRoots()).last().name }.getOrNull() ?: File(r.path).name
        is Routes.Route.Zip -> File(r.path).name
        is Routes.Route.DocumentView -> File(r.path).name
        is Routes.Route.Editor -> File(r.path).name
        is Routes.Route.Remote -> if (r.path == "/") container.servers.get(r.serverId)?.name ?: root else RemoteClient.nameOf(r.path)
        else -> root
    }

/**
 * Barre d'onglets : un élément TalkBack par onglet (« Documents, onglet, sélectionné, 2 sur 3 »),
 * action « Fermer l'onglet » ; puis le bouton « Nouvel onglet ».
 */
@Composable
internal fun TabStrip(ids: List<Int>, current: Int, label: (Int) -> String, onSelect: (Int) -> Unit, onClose: (Int) -> Unit, onNew: () -> Unit) {
    val closeLabel = stringResource(R.string.tab_close)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = ids.size) },
            ) {
                ids.forEachIndexed { i, id ->
                    val selected = id == current
                    val name = label(id)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 72.dp, max = 200.dp)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .selectable(selected = selected, role = Role.Tab) { onSelect(id) }
                            .semantics {
                                collectionItemInfo = CollectionItemInfo(0, 1, i, 1)
                                if (ids.size > 1) customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(id); true })
                            }
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            IconButton(onClick = onNew) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.tab_new))
            }
        }
        HorizontalDivider()
    }
}

/**
 * Enfant direct de [parent] qui mène à [target], ou null.
 * Ex. parent = /a, target = /a/b/c → /a/b.
 */
internal fun directChildToward(parent: String, target: String?): String? {
    if (target == null || !target.startsWith("$parent/")) return null
    return "$parent/" + target.removePrefix("$parent/").substringBefore('/')
}
