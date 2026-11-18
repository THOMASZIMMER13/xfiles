package io.github.thomaszimmer13.xfiles.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.thomaszimmer13.xfiles.data.media.Category
import io.github.thomaszimmer13.xfiles.ui.document.DocumentScreen
import io.github.thomaszimmer13.xfiles.ui.document.EditorScreen
import io.github.thomaszimmer13.xfiles.ui.scanner.ScannerScreen
import io.github.thomaszimmer13.xfiles.ui.rename.BatchRenameScreen
import io.github.thomaszimmer13.xfiles.ui.remote.RemoteFolderScreen
import io.github.thomaszimmer13.xfiles.ui.remote.ServerEditScreen
import io.github.thomaszimmer13.xfiles.ui.explorer.FolderScreen
import io.github.thomaszimmer13.xfiles.ui.storage.CategoryScreen
import io.github.thomaszimmer13.xfiles.ui.storage.CleanupKind
import io.github.thomaszimmer13.xfiles.ui.storage.CleanupScreen
import io.github.thomaszimmer13.xfiles.ui.storage.StorageScreen
import io.github.thomaszimmer13.xfiles.ui.search.SearchScreen
import io.github.thomaszimmer13.xfiles.ui.settings.SettingsScreen
import io.github.thomaszimmer13.xfiles.ui.trash.TrashScreen
import io.github.thomaszimmer13.xfiles.ui.zip.ZipScreen
import java.io.File

/**
 * Ce que contient la pile d'un onglet : un dossier (son chemin) ou un écran particulier.
 * Les routes sont de simples chaînes pour être sauvegardées avec l'état de l'onglet.
 */
object Routes {
    const val TRASH = "xfiles:corbeille"
    const val SETTINGS = "xfiles:reglages"
    /** Guide et état de Shizuku (dossiers Android/data). */
    const val SHIZUKU = "xfiles:shizuku"
    /** Création d'un coffre chiffré. */
    const val VAULT_ADD = "xfiles:coffre-nouveau"
    private const val SEARCH = "xfiles:recherche:"
    private const val ZIP = "xfiles:zip:"
    private const val CATEGORY = "xfiles:categorie:"
    private const val CLEANUP = "xfiles:nettoyage:"
    const val STORAGE = "xfiles:stockage"
    private const val DOCUMENT = "xfiles:document:"
    private const val EDITOR = "xfiles:editeur:"
    private const val OCR = "xfiles:ocr:"
    private const val SCANNER = "xfiles:scanner:"
    private const val RENAME = "xfiles:renommage:"
    private const val SERVER = "xfiles:serveur:"
    private const val SERVER_EDIT = "xfiles:serveur-modifier:"

    /** Dossier [path] (« / » : racine) du serveur [serverId]. */
    fun remote(serverId: String, path: String = "/") = SERVER + serverId + '\u0000' + path

    /** Ajout (id null) ou modification d'un serveur. */
    fun serverEdit(serverId: String?) = SERVER_EDIT + serverId.orEmpty()

    /** Ajout d'un compte en ligne (Google Drive, Dropbox, OneDrive). */
    fun cloudAdd() = SERVER_EDIT + NEW_CLOUD
    private const val NEW_CLOUD = "+en-ligne"

    /** Renommage par lot des éléments posés dans PendingRename (demande numéro [generation]). */
    fun batchRename(generation: Int) = RENAME + generation

    /** Scanner de documents ; le PDF est enregistré dans [directory]. */
    fun scanner(directory: String) = SCANNER + directory

    /** Reconnaissance du texte d'une image ou d'un PDF, affichée dans le lecteur. */
    fun ocr(path: String) = OCR + path

    /** Lecteur de documents intégré. */
    fun document(path: String) = DOCUMENT + path

    /** Éditeur de texte intégré. */
    fun editor(path: String) = EDITOR + path

    fun category(category: Category) = CATEGORY + category.name

    fun cleanup(kind: CleanupKind) = CLEANUP + kind.name

    /** Recherche sous [scope] ; null = tous les emplacements. */
    fun search(scope: String?) = SEARCH + scope.orEmpty()

    /** Contenu de l'archive [path], dans son dossier interne [inner] (« » = racine). */
    fun zip(path: String, inner: String = "") = ZIP + path + '\u0000' + inner

    sealed interface Route {
        data class Folder(val path: String) : Route
        data object Trash : Route
        data object Settings : Route
        data object Shizuku : Route
        data object VaultAdd : Route
        data class Search(val scope: String?) : Route
        data class Zip(val path: String, val inner: String) : Route
        data class CategoryList(val category: Category) : Route
        data object Storage : Route
        data class Cleanup(val kind: CleanupKind) : Route
        data class DocumentView(val path: String, val ocr: Boolean = false) : Route
        data class Editor(val path: String) : Route
        data class Scanner(val directory: String) : Route
        data class BatchRename(val generation: Int) : Route
        data class Remote(val serverId: String, val path: String) : Route
        data class ServerEdit(val serverId: String?, val cloud: Boolean = false) : Route
    }

    fun parse(route: String): Route = when {
        route == TRASH -> Route.Trash
        route == SETTINGS -> Route.Settings
        route == SHIZUKU -> Route.Shizuku
        route == VAULT_ADD -> Route.VaultAdd
        route == STORAGE -> Route.Storage
        route.startsWith(CATEGORY) -> runCatching { Route.CategoryList(Category.valueOf(route.removePrefix(CATEGORY))) }.getOrElse { Route.Folder(route) }
        route.startsWith(CLEANUP) -> runCatching { Route.Cleanup(CleanupKind.valueOf(route.removePrefix(CLEANUP))) }.getOrElse { Route.Folder(route) }
        route.startsWith(DOCUMENT) -> Route.DocumentView(route.removePrefix(DOCUMENT))
        route.startsWith(SERVER_EDIT) -> route.removePrefix(SERVER_EDIT).let { id ->
            if (id == NEW_CLOUD) Route.ServerEdit(null, cloud = true) else Route.ServerEdit(id.ifEmpty { null })
        }
        route.startsWith(SERVER) -> route.removePrefix(SERVER).let { Route.Remote(it.substringBefore('\u0000'), it.substringAfter('\u0000', "/")) }
        route.startsWith(RENAME) -> Route.BatchRename(route.removePrefix(RENAME).toIntOrNull() ?: 0)
        route.startsWith(SCANNER) -> Route.Scanner(route.removePrefix(SCANNER))
        route.startsWith(OCR) -> Route.DocumentView(route.removePrefix(OCR), ocr = true)
        route.startsWith(EDITOR) -> Route.Editor(route.removePrefix(EDITOR))
        route.startsWith(SEARCH) -> Route.Search(route.removePrefix(SEARCH).ifEmpty { null })
        route.startsWith(ZIP) -> route.removePrefix(ZIP).let { Route.Zip(it.substringBefore('\u0000'), it.substringAfter('\u0000', "")) }
        else -> Route.Folder(route)
    }

    /** Chemin sur lequel replacer le focus dans un dossier quand on revient de [route]. */
    fun focusPathOf(route: String?): String? = when (val r = route?.let(::parse)) {
        is Route.Folder -> r.path
        is Route.Zip -> r.path
        is Route.DocumentView -> r.path
        is Route.Editor -> r.path
        is Route.Remote -> r.path
        else -> null
    }
}

/**
 * Pile de navigation d'un onglet (Récents, Favoris, Explorer).
 * Pile vide = écran racine de l'onglet ; sinon, la dernière route est affichée.
 *
 * @param resetSignal incrémenté quand l'onglet déjà actif est touché à nouveau : retour à la racine.
 * @param rootLabel action du fil d'Ariane qui revient à la racine de l'onglet (« Aller aux favoris »).
 * @param root écran racine ; reçoit `open` (ouvrir une route), `reveal` (afficher un élément dans son dossier)
 * et le chemin d'où l'on revient, pour y replacer le focus.
 */
@Composable
fun BrowserStack(
    resetSignal: Int,
    rootLabel: String,
    /** Dossier à ouvrir directement (raccourci de l'écran d'accueil), puis [onOpenRequestHandled]. */
    openRequest: String? = null,
    onOpenRequestHandled: () -> Unit = {},
    /** Route affichée (null = racine), pour le nom de l'onglet et le dossier de l'autre panneau. */
    onCurrentChange: (String?) -> Unit = {},
    /** Double panneau : le retour arrière du système ne s'applique qu'au panneau actif. */
    backEnabled: Boolean = true,
    /** Appelé à chaque navigation dans cette pile (le panneau devient le panneau actif). */
    onActive: () -> Unit = {},
    root: @Composable (open: (String) -> Unit, reveal: (String) -> Unit, focusPath: String?) -> Unit,
) {
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf() }

    // Route d'où l'on vient (retour arrière) ou élément à mettre en évidence : le focus TalkBack y sera placé.
    var cameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val current = stack.lastOrNull()

    fun open(route: String) {
        onActive()
        cameFrom = null
        stack.add(route)
    }

    /** Ouvre le dossier qui contient [path], focus sur l'élément. */
    fun reveal(path: String) {
        val parent = File(path).parent ?: return
        onActive()
        cameFrom = path
        stack.add(parent)
    }

    fun goUp() {
        if (stack.isEmpty()) return
        onActive()
        cameFrom = stack.removeAt(stack.lastIndex)
    }

    /** Saut direct vers un dossier parent (fil d'Ariane) ; null = racine de l'onglet. */
    fun jumpTo(target: String?) {
        val from = stack.lastOrNull() ?: return
        onActive()
        cameFrom = from
        if (target == null) {
            stack.clear()
            return
        }
        while (stack.isNotEmpty() && stack.last() != target && stack.last().startsWith("$target/")) {
            stack.removeAt(stack.lastIndex)
        }
        if (stack.lastOrNull() != target) stack.add(target)
    }

    BackHandler(enabled = backEnabled && current != null) { goUp() }
    LaunchedEffect(current) { onCurrentChange(current) }

    // Dernière archive refermée dans cette pile : ses mots de passe sont oubliés (audit F-04).
    val inArchive = stack.any { Routes.parse(it) is Routes.Route.Zip }
    val zipViewModel: io.github.thomaszimmer13.xfiles.ui.zip.ZipViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var wasInArchive by remember { mutableStateOf(inArchive) }
    LaunchedEffect(inArchive) {
        if (wasInArchive && !inArchive) zipViewModel.passwordGate.clear()
        wasInArchive = inArchive
    }

    // Retour à la racine demandé par la barre d'onglets (signal déjà traité mémorisé pour ignorer
    // la valeur initiale et les recompositions).
    var handledReset by rememberSaveable { mutableIntStateOf(resetSignal) }
    LaunchedEffect(resetSignal) {
        if (resetSignal != handledReset) {
            handledReset = resetSignal
            if (stack.isNotEmpty()) {
                cameFrom = null
                stack.clear()
            }
        }
    }

    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            cameFrom = null
            stack.clear()
            stack.add(openRequest)
            onOpenRequestHandled()
        }
    }

    // key : chaque écran a son propre état (défilement, focus), recréé à chaque visite.
    key(current) {
        when (val route = current?.let(Routes::parse)) {
            null -> root(::open, ::reveal, cameFrom)
            Routes.Route.Trash -> TrashScreen(onBack = ::goUp)
            Routes.Route.Settings -> SettingsScreen(onBack = ::goUp, onOpen = ::open)
            Routes.Route.Shizuku -> io.github.thomaszimmer13.xfiles.ui.shizuku.ShizukuScreen(onOpen = ::open, onBack = ::goUp)
            Routes.Route.VaultAdd -> io.github.thomaszimmer13.xfiles.ui.vault.VaultCreateScreen(onBack = ::goUp)
            Routes.Route.Storage -> StorageScreen(onOpen = ::open, onBack = ::goUp)
            is Routes.Route.CategoryList -> CategoryScreen(route.category, onOpen = ::open, onReveal = ::reveal, onBack = ::goUp)
            is Routes.Route.Cleanup -> CleanupScreen(route.kind, onReveal = ::reveal, onBack = ::goUp)
            is Routes.Route.DocumentView -> DocumentScreen(path = route.path, onOpen = ::open, onBack = ::goUp, ocr = route.ocr)
            is Routes.Route.Remote -> RemoteFolderScreen(
                serverId = route.serverId,
                path = route.path,
                focusPath = Routes.focusPathOf(cameFrom),
                onOpen = ::open,
                onBack = ::goUp,
            )
            is Routes.Route.ServerEdit -> ServerEditScreen(serverId = route.serverId, cloud = route.cloud, onBack = ::goUp)
            is Routes.Route.BatchRename -> BatchRenameScreen(generation = route.generation, onBack = ::goUp)
            is Routes.Route.Scanner -> ScannerScreen(directory = route.directory, onOpen = ::open, onBack = ::goUp)
            is Routes.Route.Editor -> EditorScreen(path = route.path, onBack = ::goUp)
            is Routes.Route.Search -> SearchScreen(scope = route.scope, onOpen = ::open, onReveal = ::reveal, onBack = ::goUp)
            is Routes.Route.Zip -> ZipScreen(
                zipPath = route.path,
                inner = route.inner,
                focusRoute = cameFrom,
                onOpen = ::open,
                onBack = ::goUp,
            )
            is Routes.Route.Folder -> FolderScreen(
                path = route.path,
                focusPath = Routes.focusPathOf(cameFrom),
                onOpen = ::open,
                onBack = ::goUp,
                onJump = ::jumpTo,
                rootLabel = rootLabel,
            )
        }
    }
}
