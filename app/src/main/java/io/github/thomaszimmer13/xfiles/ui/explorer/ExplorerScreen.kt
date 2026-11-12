package io.github.thomaszimmer13.xfiles.ui.explorer

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

/**
 * Onglet Explorer : une pile de dossiers ouverts.
 * Pile vide = liste des emplacements ; sinon, le dernier dossier de la pile est affiché.
 *
 * @param resetSignal incrémenté quand l'onglet déjà actif est touché à nouveau : retour à la racine.
 */
@Composable
fun ExplorerScreen(resetSignal: Int = 0) {
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf() }

    // Chemin d'où l'on vient lors d'une remontée : le focus TalkBack y sera replacé.
    var cameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val current = stack.lastOrNull()

    fun open(path: String) {
        cameFrom = null
        stack.add(path)
    }

    fun goUp() {
        if (stack.isEmpty()) return
        cameFrom = stack.removeAt(stack.lastIndex)
    }

    /** Saut direct vers un dossier parent (fil d'Ariane) ; null = liste des emplacements. */
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

    // key : chaque dossier a son propre état d'écran (défilement, focus), recréé à chaque visite.
    key(current) {
        if (current == null) {
            ExplorerRootScreen(onOpen = ::open, focusPath = cameFrom)
        } else {
            FolderScreen(
                path = current,
                focusPath = cameFrom,
                onOpenFolder = ::open,
                onBack = ::goUp,
                onJump = ::jumpTo,
            )
        }
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
