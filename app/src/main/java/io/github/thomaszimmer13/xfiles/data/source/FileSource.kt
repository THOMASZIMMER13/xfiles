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
 * Une source de fichiers : stockage local aujourd'hui ;
 * plus tard ShizukuFileSource (Android/data), ZipFileSource, WebDavFileSource…
 *
 * Les opérations lancent [FolderNotReadableException] ou [FileOperationException] en cas d'échec.
 */
interface FileSource {
    /** Liste le contenu d'un dossier. */
    suspend fun list(path: String, includeHidden: Boolean = false): List<FileItem>

    /** Crée un dossier [name] dans [parent] ; renvoie son chemin. */
    suspend fun createDirectory(parent: String, name: String): String

    /** Renomme un fichier ou un dossier ; renvoie le nouveau chemin. */
    suspend fun rename(path: String, newName: String): String
}

class FolderNotReadableException(path: String) : IOException("Dossier illisible : $path")

/** Échec d'une opération, avec une raison que l'interface sait annoncer. */
class FileOperationException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { ALREADY_EXISTS, NOT_FOUND, DENIED, FAILED }
}
