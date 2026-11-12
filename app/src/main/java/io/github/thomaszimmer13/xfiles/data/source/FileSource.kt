package io.github.thomaszimmer13.xfiles.data.source

import java.io.IOException

/** Un fichier ou un dossier, quelle que soit sa source (stockage local, Shizuku, archive, cloud…). */
data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    /** Taille en octets (0 pour un dossier). */
    val sizeBytes: Long,
    /** Date de modification, en millisecondes depuis 1970. */
    val lastModified: Long,
    /** Nombre d'éléments visibles d'un dossier ; null pour un fichier ou si illisible. */
    val childCount: Int?,
)

/**
 * Une source de fichiers. L'interface ne contient que la lecture pour l'instant ;
 * les opérations (créer, renommer, copier…) viendront s'y ajouter aux étapes suivantes.
 *
 * Plus tard : ShizukuFileSource (Android/data), ZipFileSource, WebDavFileSource…
 */
interface FileSource {
    /** Liste le contenu d'un dossier. Lance [FolderNotReadableException] si c'est impossible. */
    suspend fun list(path: String, includeHidden: Boolean = false): List<FileItem>
}

class FolderNotReadableException(path: String) : IOException("Dossier illisible : $path")
