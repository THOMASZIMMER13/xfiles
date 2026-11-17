package io.github.thomaszimmer13.xfiles.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val CONFIRM_TRASH = booleanPreferencesKey("confirm_trash")
        /** Dossiers parcourus : même format que [RECENT_FILES]. */
        val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
        val ANNOUNCE_POSITION = booleanPreferencesKey("announce_position")
        val FEEDBACK_SOUNDS = booleanPreferencesKey("feedback_sounds")
        val BUILTIN_PLAYER = booleanPreferencesKey("builtin_player")
        val SKIP_BACK = intPreferencesKey("skip_back_seconds")
        val SKIP_FORWARD = intPreferencesKey("skip_forward_seconds")
        val READ_SUBTITLES = booleanPreferencesKey("read_subtitles")
        /** Positions de reprise : lignes « chemin\tposition ». */
        val RESUME_POSITIONS = stringPreferencesKey("resume_positions")
    }

    /** Réglages du lecteur intégré. */
    data class PlayerSettings(
        val builtin: Boolean = true,
        val skipBackSeconds: Int = 10,
        val skipForwardSeconds: Int = 30,
        val readSubtitles: Boolean = false,
    )

    val playerSettings: Flow<PlayerSettings> = context.dataStore.data.map {
        PlayerSettings(
            builtin = it[Keys.BUILTIN_PLAYER] ?: true,
            skipBackSeconds = it[Keys.SKIP_BACK] ?: 10,
            skipForwardSeconds = it[Keys.SKIP_FORWARD] ?: 30,
            readSubtitles = it[Keys.READ_SUBTITLES] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setBuiltinPlayer(value: Boolean) = context.dataStore.edit { it[Keys.BUILTIN_PLAYER] = value }.let { }
    suspend fun setSkipBack(seconds: Int) = context.dataStore.edit { it[Keys.SKIP_BACK] = seconds }.let { }
    suspend fun setSkipForward(seconds: Int) = context.dataStore.edit { it[Keys.SKIP_FORWARD] = seconds }.let { }
    suspend fun setReadSubtitles(value: Boolean) = context.dataStore.edit { it[Keys.READ_SUBTITLES] = value }.let { }

    /** Position où reprendre [path] (millisecondes), ou null. */
    val resumePositions: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RESUME_POSITIONS].orEmpty().lines().mapNotNull { line ->
            val path = line.substringBefore('\t', "")
            val pos = line.substringAfter('\t', "").toLongOrNull()
            if (path.isEmpty() || pos == null) null else path to pos
        }.toMap()
    }.distinctUntilChanged()

    /** Mémorise (ou oublie, si [positionMs] est null) la position de reprise de [path]. */
    suspend fun setResumePosition(path: String, positionMs: Long?) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RESUME_POSITIONS].orEmpty().lines().filter { it.isNotEmpty() && it.substringBefore('\t') != path }
            val updated = (if (positionMs != null) listOf("$path\t$positionMs") else emptyList()) + current
            prefs[Keys.RESUME_POSITIONS] = updated.take(MAX_RESUME).joinToString("\n")
        }
    }

    /** Ajouter « 3 sur 12 » à la lecture de chaque élément de liste (activé par défaut). */
    val announcePosition: Flow<Boolean> = context.dataStore.data.map { it[Keys.ANNOUNCE_POSITION] ?: true }.distinctUntilChanged()

    suspend fun setAnnouncePosition(value: Boolean) {
        context.dataStore.edit { it[Keys.ANNOUNCE_POSITION] = value }
    }

    /** Petits sons à la fin des opérations : réussite, échec (activé par défaut). */
    val feedbackSounds: Flow<Boolean> = context.dataStore.data.map { it[Keys.FEEDBACK_SOUNDS] ?: true }.distinctUntilChanged()

    suspend fun setFeedbackSounds(value: Boolean) {
        context.dataStore.edit { it[Keys.FEEDBACK_SOUNDS] = value }
    }

    /** Dossiers ouverts récemment, le plus récent d'abord. */
    val recentFolders: Flow<List<RecentEntry>> = context.dataStore.data.map { prefs ->
        parseRecents(prefs[Keys.RECENT_FOLDERS])
    }.distinctUntilChanged()

    suspend fun recordFolder(path: String, now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            val list = listOf(RecentEntry(path, now)) + parseRecents(prefs[Keys.RECENT_FOLDERS]).filter { it.path != path }
            prefs[Keys.RECENT_FOLDERS] = formatRecents(list.take(MAX_RECENT_FOLDERS))
        }
    }

    suspend fun removeRecentFolder(path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RECENT_FOLDERS] = formatRecents(parseRecents(prefs[Keys.RECENT_FOLDERS]).filter { it.path != path })
        }
    }

    /** Demander confirmation avant de mettre à la corbeille (activé par défaut). */
    val confirmTrash: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONFIRM_TRASH] ?: true }.distinctUntilChanged()

    suspend fun setConfirmTrash(value: Boolean) {
        context.dataStore.edit { it[Keys.CONFIRM_TRASH] = value }
    }

    suspend fun setShowHidden(value: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_HIDDEN] = value }
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
        context.dataStore.edit {
            it.remove(Keys.RECENT_FILES)
            it.remove(Keys.RECENT_FOLDERS)
        }
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

    /**
     * Monte ([up]) ou descend un favori d'une place parmi ceux du même genre (dossiers entre eux, fichiers entre eux).
     * @return nouvelle position (0 = premier) parmi ce genre, ou null s'il ne peut pas bouger.
     */
    suspend fun moveFavorite(path: String, up: Boolean, isDirectory: (String) -> Boolean): Int? {
        var result: Int? = null
        context.dataStore.edit { prefs ->
            val list = prefs[Keys.FAVORITE_FOLDERS].orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableList()
            val index = list.indexOf(path)
            if (index < 0) return@edit
            val kind = isDirectory(path)
            val sameKind = list.indices.filter { isDirectory(list[it]) == kind }
            val position = sameKind.indexOf(index)
            val target = sameKind.getOrNull(if (up) position - 1 else position + 1) ?: return@edit
            list[index] = list[target]
            list[target] = path
            prefs[Keys.FAVORITE_FOLDERS] = list.joinToString("\n")
            result = if (up) position - 1 else position + 1
        }
        return result
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
            prefs[Keys.RECENT_FOLDERS] = formatRecents(parseRecents(prefs[Keys.RECENT_FOLDERS]).map { it.copy(path = follow(it.path)) })
        }
    }

    private companion object {
        const val MAX_RECENTS = 50
        const val MAX_RECENT_FOLDERS = 20
        const val MAX_RESUME = 100
    }
}
