package io.github.thomaszimmer13.xfiles.ui.explorer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.ui.browser.BrowserStack
import io.github.thomaszimmer13.xfiles.ui.browser.Routes

/** Route de la corbeille dans la pile de l'onglet. */
const val TRASH_ROUTE = Routes.TRASH

/**
 * Onglet Explorer : racine (emplacements, corbeille, favoris), puis les dossiers ouverts.
 * @param resetSignal incrémenté quand l'onglet déjà actif est touché à nouveau : retour à la racine.
 */
@Composable
fun ExplorerScreen(resetSignal: Int = 0) {
    BrowserStack(resetSignal, rootLabel = stringResource(R.string.breadcrumb_go_root)) { open, _, focusPath ->
        ExplorerRootScreen(onOpen = open, focusPath = focusPath)
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
