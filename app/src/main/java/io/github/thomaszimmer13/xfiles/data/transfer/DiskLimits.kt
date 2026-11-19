package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.fs.walkNoLinks
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import java.io.File

/**
 * Contrôles avant un collage (audit N-08) : place libre sur le volume de destination, et limite de
 * 4 Go par fichier des cartes et clés formatées en FAT32. Sans eux, la copie échouait au milieu avec
 * un simple « échec », sans dire pourquoi.
 */
object DiskLimits {
    /** Plus gros fichier possible en FAT32 : 4 Gio moins un octet. */
    const val FAT32_MAX_FILE = 4_294_967_295L

    /** Marge gardée sur le volume (métadonnées, journal). */
    const val MARGIN = 1L shl 20

    private val fatTypes = setOf("vfat", "msdos", "fat", "fat32")

    /**
     * Vrai si [path] est sur un système de fichiers FAT32. Sous Android 11 et plus, une carte ou une clé
     * vue en /storage/XXXX-XXXX est servie par FUSE ; le vrai type se lit sur /mnt/media_rw/XXXX-XXXX.
     */
    fun isFat32(path: String, mounts: String = readMounts()): Boolean {
        val candidates = listOf(path, path.replaceFirst(Regex("^/storage/(?!emulated/)([^/]+)"), "/mnt/media_rw/$1"))
        val table = mounts.lineSequence().mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size < 3) null else unescape(parts[1]) to parts[2]
        }.toList()
        val best = candidates.flatMap { c -> table.filter { (mount, _) -> c == mount || c.startsWith(mount.trimEnd('/') + "/") } }
            .filter { (_, type) -> type != "fuse" && !type.startsWith("fuse.") }
            .maxByOrNull { it.first.length } ?: return false
        return best.second in fatTypes
    }

    /**
     * Raison de refuser le collage de [sources] dans [targetDir], ou null s'il peut commencer.
     * [needsSpace] : faux pour un déplacement sur le même volume (simple renommage).
     */
    fun check(
        sources: List<File>,
        targetDir: File,
        needsSpace: Boolean,
        fat32: Boolean = isFat32(targetDir.path),
        free: Long = targetDir.usableSpace,
    ): Reason? {
        val files = sources.asSequence().flatMap { if (it.isDirectory) it.walkNoLinks().filter(File::isFile) else sequenceOf(it) }
        if (fat32 && files.any { it.length() > FAT32_MAX_FILE }) return Reason.FILE_TOO_LARGE_FOR_FS
        if (needsSpace && free > 0) {
            val total = files.sumOf { it.length() }
            if (total + MARGIN > free) return Reason.NO_SPACE
        }
        return null
    }

    /** Erreur d'écriture due au disque : plein (ENOSPC) ou fichier trop gros (EFBIG). */
    fun reasonOf(e: Throwable): Reason? {
        var cause: Throwable? = e
        while (cause != null) {
            val m = cause.message.orEmpty()
            if ("ENOSPC" in m || "No space left" in m) return Reason.NO_SPACE
            if ("EFBIG" in m || "File too large" in m) return Reason.FILE_TOO_LARGE_FOR_FS
            cause = cause.cause
        }
        return null
    }

    private fun readMounts(): String = runCatching { File("/proc/mounts").readText() }.getOrDefault("")

    /** /proc/mounts écrit l'espace en « \040 ». */
    private fun unescape(s: String) = s.replace("\\040", " ").replace("\\011", "\t")
}
