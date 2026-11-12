package io.github.thomaszimmer13.xfiles.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.thomaszimmer13.xfiles.R

/** Les trois onglets du bas, dans l'ordre d'affichage (comme l'app Fichiers d'iOS). */
enum class AppTab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    RECENTS(R.string.tab_recents, R.drawable.ic_tab_recents),
    FAVORITES(R.string.tab_favorites, R.drawable.ic_tab_favorites),
    EXPLORER(R.string.tab_explorer, R.drawable.ic_tab_explorer),
}
