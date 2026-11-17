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

/** Réglages persistants : tri, fichiers cachés, favoris (dossiers et fichiers), fichiers ouverts récemment. */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SORT_KEY = stringPreferencesKey("sort_key")
        val SORT_DESCENDING = booleanPreferencesKey("sort_descending")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        /** Chemins séparés par des retours à la ligne, dans l'ordre d'ajout. */
        val FAVORITE_FOLDERS = stringPreferencesKey("favorite_folders")
        /** Fichiers ouverts depuis XFiles : « chemin<TAB>date » par ligne, le plus récent d'abord. */
        val RECENT_FILES = stringPreferencesKey("recent_files")
    }

    /** Fichier ouvert récemment depuis XFiles. */
    data class RecentEntry(val path: String, val openedAt: Long)

    val recentFiles: Flow<List<RecentEntry>> = context.dataStore.data.map { prefs ->
        parseRecents(prefs[Keys.RECENT_FILES])
    }.distinctUntilChanged()

    suspend fun recordOpened(path: String, now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            val list = listOf(RecentEntry(path, now)) + parseRecents(prefs[Keys.RECENT_FILES]).filter { it.path != path }
            prefs[Keys.RECENT_FILES] = formatRecents(list.take(MAX_RECENTS))
        }
    }

    suspend fun removeRecent(path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RECENT_FILES] = formatRecents(parseRecents(prefs[Keys.RECENT_FILES]).filter { it.path != path })
        }
    }

    suspend fun clearRecents() {
        context.dataStore.edit { it.remove(Keys.RECENT_FILES) }
    }

    private fun parseRecents(raw: String?): List<RecentEntry> = raw.orEmpty().split('\n').mapNotNull { line ->
        val tab = line.lastIndexOf('\t')
        if (tab <= 0) null else line.substring(tab + 1).toLongOrNull()?.let { RecentEntry(line.substring(0, tab), it) }
    }

    private fun formatRecents(list: List<RecentEntry>) = list.joinToString("\n") { "${it.path}\t${it.openedAt}" }

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

    /** Après un renommage ou un déplacement : favoris et récents situés à [oldPath] ou en dessous suivent. */
    suspend fun onPathRenamed(oldPath: String, newPath: String) {
        fun follow(path: String) = when {
            path == oldPath -> newPath
            path.startsWith("$oldPath/") -> newPath + path.removePrefix(oldPath)
            else -> path
        }
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_FOLDERS].orEmpty().split('\n').filter { it.isNotEmpty() }
            prefs[Keys.FAVORITE_FOLDERS] = current.joinToString("\n") { follow(it) }
            prefs[Keys.RECENT_FILES] = formatRecents(parseRecents(prefs[Keys.RECENT_FILES]).map { it.copy(path = follow(it.path)) })
        }
    }

    private companion object {
        const val MAX_RECENTS = 50
    }
}
