package io.github.thomaszimmer13.xfiles.data.source

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stockage local (interne, carte SD, clé USB) via les chemins de fichiers classiques. */
class LocalFileSource : FileSource {

    override suspend fun list(path: String, includeHidden: Boolean): List<FileItem> =
        withContext(Dispatchers.IO) {
            val children = File(path).listFiles() ?: throw FolderNotReadableException(path)
            children
                // La corbeille reste invisible, même quand les fichiers cachés sont affichés.
                // Idem pour les éléments temporaires d'un remplacement en cours.
                .filter { it.name != LocalTrash.DIR_NAME && !io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer.isReplaceTemp(it.name) && (includeHidden || !it.isHidden()) }
                .map { file -> file.toItem(includeHidden) }
        }

    override suspend fun createDirectory(parent: String, name: String): String =
        withContext(Dispatchers.IO) {
            val parentDir = File(parent)
            val dir = File(parentDir, name)
            when {
                dir.exists() -> fail(Reason.ALREADY_EXISTS, dir)
                !parentDir.canWrite() -> fail(Reason.DENIED, parentDir)
                !dir.mkdir() -> fail(Reason.FAILED, dir)
            }
            dir.path
        }

    override suspend fun rename(path: String, newName: String): String =
        withContext(Dispatchers.IO) {
            val source = File(path)
            if (!source.exists()) fail(Reason.NOT_FOUND, source)
            val parent = source.parentFile ?: fail(Reason.FAILED, source)
            if (!parent.canWrite()) fail(Reason.DENIED, parent)

            val target = File(parent, newName)
            // Seule la casse change (« facture » → « Facture ») : sur un stockage insensible à la casse,
            // la cible « existe » déjà puisque c'est le fichier lui-même. On passe par un nom temporaire.
            val caseOnly = target.path != source.path && target.path.equals(source.path, ignoreCase = true)
            if (caseOnly) {
                val temp = File(parent, ".xfiles-renommage-${System.nanoTime()}")
                if (!source.renameTo(temp)) fail(Reason.FAILED, source)
                if (!temp.renameTo(target)) {
                    temp.renameTo(source) // remise en état
                    fail(Reason.FAILED, source)
                }
            } else {
                if (target.exists()) fail(Reason.ALREADY_EXISTS, target)
                if (!source.renameTo(target)) fail(Reason.FAILED, source)
            }
            target.path
        }

    override suspend fun delete(path: String) =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) fail(Reason.NOT_FOUND, file)
            if (file.parentFile?.canWrite() == false) fail(Reason.DENIED, file)
            if (!file.deleteRecursively()) fail(Reason.FAILED, file)
        }

    private fun fail(reason: Reason, file: File): Nothing =
        throw FileOperationException(reason, "$reason : ${file.path}")

    private fun File.toItem(includeHidden: Boolean): FileItem = fileItemOf(this, includeHidden)

    /** Convention Unix : un nom commençant par un point est caché. */
    private fun File.isHidden(): Boolean = name.startsWith(".")
}

/** Description d'un fichier ou d'un dossier du stockage local (recherche, récents, favoris…). */
fun fileItemOf(file: File, includeHidden: Boolean = false): FileItem {
    val directory = file.isDirectory
    return FileItem(
        path = file.path,
        name = file.name,
        isDirectory = directory,
        sizeBytes = if (directory) 0 else file.length(),
        lastModified = file.lastModified(),
        childCount = if (directory) file.list()?.count { it != LocalTrash.DIR_NAME && (includeHidden || !it.startsWith(".")) } else null,
    )
}
