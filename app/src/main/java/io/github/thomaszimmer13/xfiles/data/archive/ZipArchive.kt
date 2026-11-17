package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.data.transfer.TransferCancelledException
import io.github.thomaszimmer13.xfiles.data.transfer.TransferListener
import io.github.thomaszimmer13.xfiles.data.transfer.copyStream
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Un élément d'une archive : [path] relatif, sans « / » final (« Projet/notes.txt »). */
data class ArchiveEntry(val path: String, val isDirectory: Boolean, val size: Long, val time: Long) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

/** Une archive est reconnue à son extension. */
fun isZip(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

/**
 * Lecture, extraction et création d'archives zip (java.util.zip).
 * Opérations bloquantes : à appeler hors du fil principal.
 *
 * Les archives chiffrées (mot de passe) ne sont pas prises en charge par java.util.zip :
 * elles sont signalées par [Reason.ENCRYPTED].
 */
object ZipArchive {

    /** Tous les éléments, dossiers implicites compris (une archive peut ne lister que « a/b/c.txt »). */
    fun list(zipPath: String): List<ArchiveEntry> = open(zipPath) { zip ->
        val entries = LinkedHashMap<String, ArchiveEntry>()
        zip.entries().asSequence().forEach { e ->
            val path = e.name.trimEnd('/')
            if (path.isEmpty() || !isSafe(path)) return@forEach
            entries[path] = ArchiveEntry(path, e.isDirectory, if (e.isDirectory) 0 else e.size.coerceAtLeast(0), e.time)
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && parent !in entries) {
                entries[parent] = ArchiveEntry(parent, true, 0, e.time)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        entries.values.toList()
    }

    /** Éléments directement dans [dir] (« » = racine de l'archive). */
    fun children(entries: List<ArchiveEntry>, dir: String): List<ArchiveEntry> = entries.filter { it.parent == dir }

    /** Taille décompressée de [prefix] (un élément, un dossier et son contenu, ou tout si null). */
    fun uncompressedSize(entries: List<ArchiveEntry>, prefix: String?): Long =
        entries.filter { !it.isDirectory && matches(it.path, prefix) }.sumOf { it.size }

    /**
     * Extrait [prefix] (un élément ou un dossier de l'archive ; null = toute l'archive) dans [targetDir].
     * Le premier niveau reçoit un nom libre (« Projet 2 ») s'il existe déjà : rien n'est écrasé.
     * @return chemin de ce qui a été créé au premier niveau.
     */
    fun extract(zipPath: String, prefix: String?, targetDir: File, listener: TransferListener = TransferListener.NONE): String {
        if (!targetDir.isDirectory || !targetDir.canWrite()) throw FileOperationException(Reason.DENIED, targetDir.path)
        // Toute l'archive : dans un nouveau dossier au nom de l'archive (« Photos.zip » → « Photos »).
        val topName = prefix?.substringAfterLast('/') ?: File(zipPath).name.removeSuffix(".zip").removeSuffix(".ZIP")
        val top = File(targetDir, NameValidator.uniqueName(topName, targetDir.list()?.toList().orEmpty(), keepExtension = true))
        val base = prefix?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""

        try {
            open(zipPath) { zip ->
                val entries = zip.entries().asSequence().filter { e ->
                    val path = e.name.trimEnd('/')
                    path.isNotEmpty() && isSafe(path) && matches(path, prefix)
                }.toList()
                if (prefix == null || entries.any { it.isDirectory } || entries.size > 1) top.mkdirs()
                for (e in entries) {
                    if (listener.isCancelled) throw TransferCancelledException()
                    val relative = e.name.trimEnd('/').removePrefix(base)
                    val dest = when {
                        prefix == null -> File(top, relative)
                        relative == topName -> top
                        else -> File(top, relative.removePrefix("$topName/"))
                    }
                    if (!dest.canonicalPath.startsWith(top.canonicalPath)) continue
                    if (e.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input -> dest.outputStream().use { copyStream(input, it, listener) } }
                    }
                    if (e.time > 0) dest.setLastModified(e.time)
                }
            }
        } catch (e: Exception) {
            top.deleteRecursively()
            throw e
        }
        return top.path
    }

    /**
     * Crée [target] à partir de [sources] (fichiers et dossiers, contenu compris).
     * Les chemins dans l'archive sont relatifs au dossier qui contient chaque source.
     */
    fun compress(sources: List<File>, target: File, listener: TransferListener = TransferListener.NONE) {
        try {
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (source in sources) {
                    val base = source.parentFile ?: continue
                    source.walkTopDown().forEach { file ->
                        if (listener.isCancelled) throw TransferCancelledException()
                        val relative = file.relativeTo(base).invariantSeparatorsPath
                        val entry = ZipEntry(if (file.isDirectory) "$relative/" else relative).apply { time = file.lastModified() }
                        out.putNextEntry(entry)
                        if (file.isFile) file.inputStream().use { copyStream(it, out, listener) }
                        out.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is TransferCancelledException) throw e
            throw FileOperationException(Reason.FAILED, target.path)
        }
    }

    /** Nom de l'archive à créer : « Facture.zip », « Projet.zip » ou « Archive.zip » pour plusieurs éléments. */
    fun archiveNameFor(sources: List<File>, existing: Collection<String>, multipleName: String): String {
        val base = if (sources.size == 1) {
            val f = sources[0]
            if (f.isDirectory) f.name else f.name.substringBeforeLast('.').ifEmpty { f.name }
        } else {
            multipleName
        }
        return NameValidator.uniqueName("$base.zip", existing, keepExtension = true)
    }

    private fun matches(path: String, prefix: String?): Boolean =
        prefix == null || path == prefix || path.startsWith("$prefix/")

    /** Refuse les chemins qui sortiraient du dossier d'extraction (« ../ », chemin absolu). */
    private fun isSafe(path: String): Boolean =
        !path.startsWith("/") && path.split('/').none { it == ".." }

    /** Ouvre l'archive en UTF-8, puis dans l'encodage des anciennes archives Windows si les noms sont illisibles. */
    private fun <T> open(zipPath: String, block: (ZipFile) -> T): T {
        val file = File(zipPath)
        if (!file.exists()) throw FileOperationException(Reason.NOT_FOUND, zipPath)
        var lastError: Exception? = null
        for (charset in listOf(Charsets.UTF_8, Charset.forName("IBM437"))) {
            try {
                return ZipFile(file, charset).use(block)
            } catch (e: TransferCancelledException) {
                throw e
            } catch (e: FileOperationException) {
                throw e
            } catch (e: ZipException) {
                if (e.message?.contains("encrypted", ignoreCase = true) == true) {
                    throw FileOperationException(Reason.ENCRYPTED, zipPath)
                }
                lastError = e
            } catch (e: IllegalArgumentException) {
                // Nom d'entrée mal encodé : on réessaie avec l'autre encodage.
                lastError = e
            }
        }
        throw FileOperationException(Reason.BAD_ARCHIVE, "$zipPath : ${lastError?.message}")
    }
}
