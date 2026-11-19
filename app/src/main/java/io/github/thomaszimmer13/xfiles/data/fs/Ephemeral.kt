package io.github.thomaszimmer13.xfiles.data.fs

import java.io.File

/**
 * Copies de passage dans le cache d'XFiles (fichiers ouverts depuis un coffre ou un serveur, aperçus
 * d'archives) : jamais mémorisées dans les Récents ni les positions de lecture, et purgées à la
 * fermeture des coffres (audit N-05).
 */
object Ephemeral {
    /** Aperçus extraits d'une archive (éventuellement chiffrée), dans le cache. */
    const val PREVIEW_DIR = "apercu"

    /** Texte reconnu (OCR) pour un fichier du cache, rangé à part pour être purgé avec les coffres. */
    const val OCR_DIR = "ocr/ephemere"

    /** Vrai si [path] se trouve dans [cacheDir] (chemin canonique suivi du séparateur). */
    fun isEphemeral(path: String, cacheDir: File): Boolean {
        val file = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val root = runCatching { cacheDir.canonicalPath }.getOrNull() ?: return false
        return file == root || file.startsWith(root.trimEnd(File.separatorChar) + File.separator)
    }

    /** Efface les aperçus d'archives et le texte OCR des copies de passage. */
    fun purge(cacheDir: File) {
        deleteRecursivelyNoFollow(File(cacheDir, PREVIEW_DIR))
        deleteRecursivelyNoFollow(File(cacheDir, OCR_DIR))
    }
}
