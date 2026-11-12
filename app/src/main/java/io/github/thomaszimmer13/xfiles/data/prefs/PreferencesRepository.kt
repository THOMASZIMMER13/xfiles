package io.github.thomaszimmer13.xfiles.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.thomaszimmer13.xfiles.domain.SortKey
import io.github.thomaszimmer13.xfiles.domain.SortOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Réglages persistants : tri, fichiers cachés, dossiers favoris. */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SORT_KEY = stringPreferencesKey("sort_key")
        val SORT_DESCENDING = booleanPreferencesKey("sort_descending")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        /** Chemins séparés par des retours à la ligne, dans l'ordre d'ajout. */
        val FAVORITE_FOLDERS = stringPreferencesKey("favorite_folders")
    }

    val sortOptions: Flow<SortOptions> = context.dataStore.data.map { prefs ->
        SortOptions(
            key = prefs[Keys.SORT_KEY]?.let { runCatching { SortKey.valueOf(it) }.getOrNull() } ?: SortKey.NAME,
            descending = prefs[Keys.SORT_DESCENDING] ?: false,
            showHidden = prefs[Keys.SHOW_HIDDEN] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setSortOptions(options: SortOptions) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SORT_KEY] = options.key.name
            prefs[Keys.SORT_DESCENDING] = options.descending
            prefs[Keys.SHOW_HIDDEN] = options.showHidden
        }
    }

    val favoriteFolders: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_FOLDERS].orEmpty().split('\n').filter { it.isNotEmpty() }
    }.distinctUntilChanged()

    /** Ajoute ou retire un dossier des favoris ; renvoie true s'il est désormais favori. */
    suspend fun toggleFavorite(path: String): Boolean {
        var nowFavorite = false
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_FOLDERS].orEmpty().split('\n').filter { it.isNotEmpty() }
            val updated = if (path in current) {
                current - path
            } else {
                nowFavorite = true
                current + path
            }
            prefs[Keys.FAVORITE_FOLDERS] = updated.joinToString("\n")
        }
        return nowFavorite
    }
}
