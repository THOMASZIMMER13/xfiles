package io.github.thomaszimmer13.xfiles.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.thomaszimmer13.xfiles.ui.explorer.FolderScreen
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
    private const val SEARCH = "xfiles:recherche:"
    private const val ZIP = "xfiles:zip:"

    /** Recherche sous [scope] ; null = tous les emplacements. */
    fun search(scope: String?) = SEARCH + scope.orEmpty()

    /** Contenu de l'archive [path], dans son dossier interne [inner] (« » = racine). */
    fun zip(path: String, inner: String = "") = ZIP + path + '\u0000' + inner

    sealed interface Route {
        data class Folder(val path: String) : Route
        data object Trash : Route
        data object Settings : Route
        data class Search(val scope: String?) : Route
        data class Zip(val path: String, val inner: String) : Route
    }

    fun parse(route: String): Route = when {
        route == TRASH -> Route.Trash
        route == SETTINGS -> Route.Settings
        route.startsWith(SEARCH) -> Route.Search(route.removePrefix(SEARCH).ifEmpty { null })
        route.startsWith(ZIP) -> route.removePrefix(ZIP).let { Route.Zip(it.substringBefore('\u0000'), it.substringAfter('\u0000', "")) }
        else -> Route.Folder(route)
    }

    /** Chemin sur lequel replacer le focus dans un dossier quand on revient de [route]. */
    fun focusPathOf(route: String?): String? = when (val r = route?.let(::parse)) {
        is Route.Folder -> r.path
        is Route.Zip -> r.path
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
    root: @Composable (open: (String) -> Unit, reveal: (String) -> Unit, focusPath: String?) -> Unit,
) {
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf() }

    // Route d'où l'on vient (retour arrière) ou élément à mettre en évidence : le focus TalkBack y sera placé.
    var cameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val current = stack.lastOrNull()

    fun open(route: String) {
        cameFrom = null
        stack.add(route)
    }

    /** Ouvre le dossier qui contient [path], focus sur l'élément. */
    fun reveal(path: String) {
        val parent = File(path).parent ?: return
        cameFrom = path
        stack.add(parent)
    }

    fun goUp() {
        if (stack.isEmpty()) return
        cameFrom = stack.removeAt(stack.lastIndex)
    }

    /** Saut direct vers un dossier parent (fil d'Ariane) ; null = racine de l'onglet. */
    fun jumpTo(target: String?) {
        val from = stack.lastOrNull() ?: return
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

    BackHandler(enabled = current != null) { goUp() }

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

    // key : chaque écran a son propre état (défilement, focus), recréé à chaque visite.
    key(current) {
        when (val route = current?.let(Routes::parse)) {
            null -> root(::open, ::reveal, cameFrom)
            Routes.Route.Trash -> TrashScreen(onBack = ::goUp)
            Routes.Route.Settings -> SettingsScreen(onBack = ::goUp)
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
