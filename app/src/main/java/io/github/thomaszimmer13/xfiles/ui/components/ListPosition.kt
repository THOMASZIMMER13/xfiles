package io.github.thomaszimmer13.xfiles.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.thomaszimmer13.xfiles.R

/** Réglage « Annoncer la position dans les listes », fourni à tout l'écran par XFilesApp. */
val LocalAnnouncePosition = compositionLocalOf { true }

/** Ajoute « 3 sur 12 » à la fin de [spoken] si le réglage est activé et que la liste a plusieurs éléments. */
@Composable
fun positioned(spoken: String, index: Int, count: Int): String =
    if (LocalAnnouncePosition.current && count > 1) stringResource(R.string.list_position, spoken, index + 1, count) else spoken
