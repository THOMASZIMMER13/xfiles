package io.github.thomaszimmer13.xfiles.ui.storage

import io.github.thomaszimmer13.xfiles.data.source.FileSource
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bilan d'une suppression groupée. */
data class RemoveResult(val removed: Set<String>, val failed: Int)

/** Met à la corbeille (ou supprime définitivement) chacun des [paths] ; continue après un échec. */
suspend fun removeAll(paths: Collection<String>, permanent: Boolean, trash: LocalTrash, fileSource: FileSource): RemoveResult =
    withContext(Dispatchers.IO) {
        val removed = mutableSetOf<String>()
        var failed = 0
        for (path in paths) {
            try {
                if (permanent) fileSource.delete(path) else trash.moveToTrash(path)
                removed += path
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        RemoveResult(removed, failed)
    }
