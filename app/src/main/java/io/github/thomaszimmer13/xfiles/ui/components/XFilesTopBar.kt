package io.github.thomaszimmer13.xfiles.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import io.github.thomaszimmer13.xfiles.R

/**
 * Barre du haut commune à tous les écrans.
 *
 * @param spokenTitle texte lu par TalkBack, plus riche que le titre affiché (« Factures, 12 éléments »).
 * @param onBack si non nul, affiche un bouton « Retour » avant le titre.
 * @param titleFocus permet de placer le focus TalkBack sur le titre à l'ouverture d'un dossier.
 * @param actions boutons à droite du titre (lus après lui par TalkBack).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XFilesTopBar(
    title: String,
    spokenTitle: String = title,
    onBack: (() -> Unit)? = null,
    titleFocus: FocusRequester? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        actions = actions,
        // Les marges de la barre d'état sont déjà gérées par l'écran parent.
        windowInsets = WindowInsets(0),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (titleFocus != null) Modifier.focusRequester(titleFocus).focusable() else Modifier)
                    .clearAndSetSemantics {
                        heading()
                        contentDescription = spokenTitle
                    },
            )
        },
    )
}

/**
 * Exécute [action] une seule fois, dès que [ready] devient vrai, après l'affichage de l'image suivante
 * (pour que l'élément à focaliser existe bien à l'écran).
 * Sert à replacer le focus TalkBack au retour arrière ou à l'ouverture d'un dossier.
 */
@Composable
fun FocusOnceEffect(ready: Boolean, action: suspend () -> Unit) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !done) {
            done = true
            withFrameNanos { }
            runCatching { action() }
        }
    }
}
