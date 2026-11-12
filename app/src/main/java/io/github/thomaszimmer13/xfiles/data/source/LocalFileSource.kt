package io.github.thomaszimmer13.xfiles.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stockage local (interne, carte SD, clé USB) via les chemins de fichiers classiques. */
class LocalFileSource : FileSource {

    override suspend fun list(path: String, includeHidden: Boolean): List<FileItem> =
        withContext(Dispatchers.IO) {
            val children = File(path).listFiles() ?: throw FolderNotReadableException(path)
            children
                .filter { includeHidden || !it.isHidden() }
                .map { file -> file.toItem(includeHidden) }
        }

    private fun File.toItem(includeHidden: Boolean): FileItem {
        val directory = isDirectory
        return FileItem(
            path = path,
            name = name,
            isDirectory = directory,
            sizeBytes = if (directory) 0 else length(),
            lastModified = lastModified(),
            childCount = if (directory) list()?.count { includeHidden || !it.startsWith(".") } else null,
        )
    }

    /** Convention Unix : un nom commençant par un point est caché. */
    private fun File.isHidden(): Boolean = name.startsWith(".")
}
