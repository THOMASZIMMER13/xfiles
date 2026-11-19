package io.github.thomaszimmer13.xfiles.data.incoming

import java.io.File

/**
 * Dossier demandé par un raccourci de l'écran d'accueil (intent exporté, donc envoyable par n'importe
 * quelle app). Audit N-04 : jamais une route brute (« xfiles:editeur:… », « xfiles:serveur:… ») ;
 * seulement un chemin absolu, canonique, d'un dossier existant situé dans un volume de stockage partagé
 * monté, et hors des dossiers propres à XFiles (données privées, cache, coffres déchiffrés).
 */
object IncomingFolders {
    /** Chemin canonique à ouvrir, ou null si la demande est refusée. */
    fun safe(path: String, volumeRoots: List<String>, privateDirs: List<File>): String? {
        val raw = path.trim()
        if (!raw.startsWith("/") || '\u0000' in raw) return null
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        val roots = volumeRoots.mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }
        if (roots.none { file.isWithin(it) }) return null
        val own = privateDirs.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (own.any { file.isWithin(it) }) return null
        if (!file.isDirectory) return null
        return file.path
    }

    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
}
