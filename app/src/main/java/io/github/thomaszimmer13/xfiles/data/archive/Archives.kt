package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.data.transfer.TransferCancelledException
import io.github.thomaszimmer13.xfiles.data.transfer.TransferListener
import io.github.thomaszimmer13.xfiles.data.transfer.copyStream
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import net.lingala.zip4j.io.inputstream.ZipInputStream as Zip4jInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.PasswordRequiredException
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Formats d'archive reconnus, d'après l'extension. */
enum class ArchiveFormat(val extensions: List<String>) {
    ZIP(listOf(".zip")),
    SEVEN_Z(listOf(".7z")),
    TAR(listOf(".tar")),
    TAR_GZ(listOf(".tar.gz", ".tgz")),
    TAR_BZ2(listOf(".tar.bz2", ".tbz2", ".tbz")),
    TAR_XZ(listOf(".tar.xz", ".txz")),
    GZ(listOf(".gz")),
    BZ2(listOf(".bz2")),
    XZ(listOf(".xz")),
    /** RAR 4 et RAR 5, par libarchive (lecture seule, sans déchiffrement). */
    RAR(listOf(".rar")),

    /** Image de disque ISO 9660 (Joliet, Rock Ridge), par libarchive. */
    ISO(listOf(".iso")),
    ;

    /** Nom sans l'extension de l'archive (« Photos.tar.gz » → « Photos »). */
    fun baseName(name: String): String {
        val ext = extensions.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return name
        return name.dropLast(ext.length).ifEmpty { name }
    }
}

/** Formats qu'XFiles sait créer. */
enum class CreateFormat(val extension: String) { ZIP(".zip"), SEVEN_Z(".7z"), TAR_GZ(".tar.gz") }

/** Format de l'archive [name] ; les extensions doubles (« .tar.gz ») passent avant les simples (« .gz »). */
fun archiveFormatOf(name: String): ArchiveFormat? = ArchiveFormat.entries
    .flatMap { f -> f.extensions.map { it to f } }
    .sortedByDescending { it.first.length }
    .firstOrNull { name.endsWith(it.first, ignoreCase = true) }?.second

fun isArchive(name: String): Boolean = archiveFormatOf(name) != null

/** Un élément lu dans une archive, avec son contenu (null pour un dossier). */
private class StreamEntry(
    val name: String,
    val isDirectory: Boolean,
    val time: Long,
    val size: Long,
    /** Lien physique : contenu identique à celui de cet élément (déjà extrait plus haut). */
    val linkTo: String? = null,
    val open: () -> InputStream?,
)

/**
 * Lecture, extraction et création de toutes les archives prises en charge :
 * zip (avec ou sans mot de passe), 7z (mot de passe en lecture), tar, tar.gz, tar.bz2, tar.xz, gz, bz2, xz,
 * RAR 4 et RAR 5, images ISO (libarchive ; les RAR chiffrés ne sont pas pris en charge).
 * Opérations bloquantes : à appeler hors du fil principal.
 */
object Archives {

    /** Tous les éléments, dossiers implicites compris. */
    fun list(path: String, password: String? = null): List<ArchiveEntry> {
        val format = formatOf(path)
        // Les noms et tailles d'un zip, même chiffré, se lisent dans son répertoire central.
        if (format == ArchiveFormat.ZIP) {
            try {
                return ZipArchive.list(path)
            } catch (e: FileOperationException) {
                if (e.reason != Reason.ENCRYPTED) throw e
            }
            // Zip chiffré : java.util.zip refuse de l'ouvrir, zip4j lit son répertoire central sans mot de passe.
            return try {
                withImplicitFolders(
                    net.lingala.zip4j.ZipFile(path).fileHeaders.mapNotNull { h ->
                        val name = h.fileName.replace('\\', '/').trimEnd('/')
                        if (name.isEmpty()) null else ArchiveEntry(name, h.isDirectory, if (h.isDirectory) 0 else h.uncompressedSize, h.lastModifiedTimeEpoch)
                    },
                )
            } catch (e: Exception) {
                throw translate(e, password)
            }
        }
        val raw = mutableListOf<ArchiveEntry>()
        read(path, format, password, withContent = false) { e ->
            raw += ArchiveEntry(e.name, e.isDirectory, if (e.isDirectory) 0 else e.size, e.time)
        }
        return withImplicitFolders(raw)
    }

    /**
     * Vrai si l'archive demande un mot de passe pour être lue ou extraite.
     * (Les noms d'un zip chiffré restent lisibles : la question se pose à l'extraction.)
     */
    fun needsPassword(path: String): Boolean = when (formatOf(path)) {
        ArchiveFormat.ZIP -> runCatching { net.lingala.zip4j.ZipFile(path).isEncrypted }.getOrDefault(false)
        ArchiveFormat.SEVEN_Z -> try {
            SevenZFile.builder().setFile(File(path)).get().use { z ->
                z.entries.any { e -> e.hasStream() && e.contentMethods.any { it.method == SevenZMethod.AES256SHA256 } }
            }
        } catch (_: PasswordRequiredException) {
            true
        } catch (_: Exception) {
            false
        }
        // RAR chiffré : libarchive ne sait pas le déchiffrer, inutile de demander le mot de passe.
        else -> false
    }

    /** Vérifie un mot de passe en lisant le début du premier fichier chiffré. Lance [Reason.WRONG_PASSWORD] sinon. */
    fun checkPassword(path: String, password: String) {
        var checked = false
        read(path, formatOf(path), password, withContent = true) { e ->
            if (checked || e.isDirectory) return@read
            e.open()?.use { it.read(ByteArray(1024)) }
            checked = true
        }
    }

    /** Taille décompressée de [prefix] (null = tout) ; 0 si inconnue. */
    fun uncompressedSize(entries: List<ArchiveEntry>, prefix: String?): Long =
        entries.filter { !it.isDirectory && it.size > 0 && matches(it.path, prefix) }.sumOf { it.size }

    /**
     * Extrait [prefix] (un élément ou un dossier ; null = toute l'archive) dans [targetDir], sans rien écraser.
     * @return chemin de ce qui a été créé au premier niveau.
     */
    fun extract(path: String, prefix: String?, targetDir: File, listener: TransferListener = TransferListener.NONE, password: String? = null): String {
        // F-02 : espace libre surveillé pendant toute extraction (plafond éventuel posé par l'appelant).
        @Suppress("NAME_SHADOWING")
        val listener = listener as? ExtractGuard ?: ExtractGuard(targetDir, listener)
        val format = formatOf(path)
        if (format == ArchiveFormat.ZIP && password == null) return ZipArchive.extract(path, prefix, targetDir, listener)
        if (!targetDir.isDirectory || !targetDir.canWrite()) throw FileOperationException(Reason.DENIED, targetDir.path)

        // Fichier seul compressé (« notes.txt.gz ») : on le décompresse à côté.
        if (format == ArchiveFormat.GZ || format == ArchiveFormat.BZ2 || format == ArchiveFormat.XZ) {
            val name = format.baseName(File(path).name)
            val target = File(targetDir, NameValidator.uniqueName(name, targetDir.list()?.toList().orEmpty(), keepExtension = true))
            try {
                singleStream(path, format).use { input -> target.outputStream().use { copyStream(input, it, listener) } }
            } catch (e: Exception) {
                target.delete()
                throw translate(e, password)
            }
            return target.path
        }

        val topName = prefix?.substringAfterLast('/') ?: format.baseName(File(path).name)
        val top = File(targetDir, NameValidator.uniqueName(topName, targetDir.list()?.toList().orEmpty(), keepExtension = true))
        val base = prefix?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
        var made = false
        try {
            read(path, format, password, withContent = true) { e ->
                if (listener.isCancelled) throw TransferCancelledException()
                if (!isSafe(e.name) || !matches(e.name, prefix)) return@read
                val relative = e.name.removePrefix(base)
                val dest = when {
                    prefix == null -> File(top, relative)
                    relative == topName -> top
                    else -> File(top, relative.removePrefix("$topName/"))
                }
                if (dest != top && !made) {
                    top.mkdirs()
                }
                made = true
                // F-01 : la cible canonique doit être le dossier créé ou se trouver dessous (séparateur compris).
                if (!isInside(top, dest)) return@read
                val linked = e.linkTo
                if (linked != null) {
                    // Lien physique : copie de l'élément déjà extrait, s'il est bien dans ce même dossier.
                    if (!isSafe(linked)) return@read
                    val rel = linked.removePrefix(base)
                    val source = if (prefix == null) File(top, rel) else File(top, rel.removePrefix("$topName/"))
                    if (isInside(top, source) && source.isFile && source != dest) {
                        dest.parentFile?.mkdirs()
                        source.inputStream().use { input -> dest.outputStream().use { copyStream(input, it, listener) } }
                    }
                    return@read
                }
                if (e.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    e.open()?.use { input -> dest.outputStream().use { copyStream(input, it, listener) } }
                }
                if (e.time > 0) dest.setLastModified(e.time)
            }
            if (prefix == null && !top.exists()) top.mkdirs()
        } catch (e: Exception) {
            top.deleteRecursively()
            throw translate(e, password)
        }
        return top.path
    }

    /**
     * Crée [target] au format [format] avec [sources] (fichiers et dossiers, contenu compris).
     * [password] : chiffrement AES-256, pour le zip uniquement.
     */
    fun compress(
        format: CreateFormat,
        sources: List<File>,
        target: File,
        listener: TransferListener = TransferListener.NONE,
        password: String? = null,
    ) {
        if (format == CreateFormat.ZIP && password.isNullOrEmpty()) {
            ZipArchive.compress(sources, target, listener)
            return
        }
        try {
            when (format) {
                CreateFormat.ZIP -> Zip4jOutputStream(target.outputStream().buffered(), password!!.toCharArray()).use { out ->
                    forEachSource(sources) { file, relative ->
                        if (listener.isCancelled) throw TransferCancelledException()
                        val params = ZipParameters().apply {
                            fileNameInZip = if (file.isDirectory) "$relative/" else relative
                            isEncryptFiles = file.isFile
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                            lastModifiedFileTime = file.lastModified()
                        }
                        out.putNextEntry(params)
                        if (file.isFile) file.inputStream().use { copyStream(it, out, listener) }
                        out.closeEntry()
                    }
                }
                CreateFormat.SEVEN_Z -> SevenZOutputFile(target).use { out ->
                    forEachSource(sources) { file, relative ->
                        if (listener.isCancelled) throw TransferCancelledException()
                        out.putArchiveEntry(out.createArchiveEntry(file, relative))
                        if (file.isFile) {
                            val buffer = ByteArray(256 * 1024)
                            file.inputStream().use { input ->
                                while (true) {
                                    if (listener.isCancelled) throw TransferCancelledException()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    listener.onBytes(read.toLong())
                                }
                            }
                        }
                        out.closeArchiveEntry()
                    }
                }
                CreateFormat.TAR_GZ -> TarArchiveOutputStream(GzipCompressorOutputStream(target.outputStream().buffered())).use { out ->
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    forEachSource(sources) { file, relative ->
                        if (listener.isCancelled) throw TransferCancelledException()
                        out.putArchiveEntry(TarArchiveEntry(file, if (file.isDirectory) "$relative/" else relative))
                        if (file.isFile) file.inputStream().use { copyStream(it, out, listener) }
                        out.closeArchiveEntry()
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is TransferCancelledException) throw e
            throw FileOperationException(Reason.FAILED, target.path)
        }
    }

    /** Nom de l'archive à créer : « Facture.7z », « Projet.tar.gz », « Archive.zip »… sans doublon. */
    fun archiveNameFor(sources: List<File>, existing: Collection<String>, multipleName: String, format: CreateFormat): String {
        val base = if (sources.size == 1) {
            val f = sources[0]
            if (f.isDirectory) f.name else f.name.substringBeforeLast('.').ifEmpty { f.name }
        } else {
            multipleName
        }
        return uniqueArchiveName("$base${format.extension}", existing, format)
    }

    /** Numérote avant l'extension complète : « Projet 2.tar.gz », pas « Projet.tar 2.gz ». */
    fun uniqueArchiveName(name: String, existing: Collection<String>, format: CreateFormat): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        val base = name.dropLast(format.extension.length)
        var n = 2
        while ("$base $n${format.extension}".lowercase() in taken) n++
        return "$base $n${format.extension}"
    }

    // --- Lecture commune ---

    private fun formatOf(path: String): ArchiveFormat {
        if (!File(path).exists()) throw FileOperationException(Reason.NOT_FOUND, path)
        return archiveFormatOf(path) ?: throw FileOperationException(Reason.UNSUPPORTED, path)
    }

    /** Parcourt les éléments de l'archive ; [withContent] : ouvrir le contenu (extraction) ou seulement les noms. */
    private fun read(path: String, format: ArchiveFormat, password: String?, withContent: Boolean, onEntry: (StreamEntry) -> Unit) {
        val file = File(path)
        try {
            when (format) {
                ArchiveFormat.ZIP -> readZip4j(file, password, onEntry)
                ArchiveFormat.SEVEN_Z -> {
                    val builder = SevenZFile.builder().setFile(file)
                    if (password != null) builder.setPassword(password.toCharArray())
                    builder.get().use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val name = e.name.orEmpty().replace('\\', '/').trimEnd('/')
                            if (name.isEmpty()) continue
                            val time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L
                            onEntry(StreamEntry(name, e.isDirectory, time, e.size) { if (e.isDirectory) null else z.getInputStream(e) })
                        }
                    }
                }
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> {
                    val raw = file.inputStream().buffered()
                    val decompressed: InputStream = when (format) {
                        ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw)
                        ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
                        ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw)
                        else -> raw
                    }
                    TarArchiveInputStream(decompressed).use { tar -> readSequential(tar, onEntry) }
                }
                ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> {
                    val name = format.baseName(file.name)
                    onEntry(StreamEntry(name, false, file.lastModified(), -1) { singleStream(path, format) })
                }
                ArchiveFormat.RAR, ArchiveFormat.ISO -> {
                    val formats = if (format == ArchiveFormat.RAR) LibArchive.RAR else LibArchive.ISO
                    val sizes = HashMap<String, Long>()
                    LibArchive.read(path, password, formats) { e, input ->
                        // Liens et fichiers spéciaux : jamais extraits (un lien pourrait mener hors du dossier).
                        if (e.type == LibArchive.TYPE_OTHER) return@read
                        val name = e.name.replace('\\', '/').trimEnd('/')
                        if (name.isEmpty()) return@read
                        if (withContent && e.encrypted && !e.isDirectory) throw FileOperationException(Reason.ENCRYPTED_UNSUPPORTED, path)
                        if (e.type == LibArchive.TYPE_HARDLINK) {
                            val target = e.link?.replace('\\', '/')?.trimEnd('/') ?: return@read
                            onEntry(StreamEntry(name, false, e.time, sizes[target] ?: 0, linkTo = target) { null })
                            return@read
                        }
                        if (!e.isDirectory) sizes[name] = e.size
                        onEntry(StreamEntry(name, e.isDirectory, e.time, if (e.isDirectory) 0 else e.size) { if (e.isDirectory) null else input })
                    }
                }
            }
        } catch (e: Exception) {
            throw translate(e, password)
        }
    }

    private fun readSequential(input: ArchiveInputStream<*>, onEntry: (StreamEntry) -> Unit) {
        while (true) {
            val e = input.nextEntry ?: break
            val name = e.name.replace('\\', '/').trimEnd('/')
            if (name.isEmpty()) continue
            // Le contenu se lit directement dans le flux de l'archive ; on ne le ferme pas ici.
            onEntry(StreamEntry(name, e.isDirectory, e.lastModifiedDate?.time ?: 0L, e.size) { if (e.isDirectory) null else NonClosing(input) })
        }
    }

    private fun readZip4j(file: File, password: String?, onEntry: (StreamEntry) -> Unit) {
        Zip4jInputStream(file.inputStream().buffered(), password?.toCharArray()).use { zip ->
            while (true) {
                val h = zip.nextEntry ?: break
                val name = h.fileName.replace('\\', '/').trimEnd('/')
                if (name.isEmpty()) continue
                if (h.isEncrypted && password == null) throw FileOperationException(Reason.ENCRYPTED, file.path)
                onEntry(StreamEntry(name, h.isDirectory, h.lastModifiedTimeEpoch, h.uncompressedSize) { if (h.isDirectory) null else NonClosing(zip) })
            }
        }
    }

    private fun singleStream(path: String, format: ArchiveFormat): InputStream {
        val raw = File(path).inputStream().buffered()
        return when (format) {
            ArchiveFormat.GZ -> GzipCompressorInputStream(raw, true)
            ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
            else -> XZCompressorInputStream(raw, true)
        }
    }

    /** Erreurs des bibliothèques → raisons que l'interface sait annoncer. */
    private fun translate(e: Exception, password: String?): Exception = when {
        e is TransferCancelledException || e is FileOperationException -> e
        e is PasswordRequiredException -> FileOperationException(Reason.ENCRYPTED, e.message.orEmpty())
        // Messages de libarchive pour un RAR chiffré (contenu ou en-têtes).
        e.message?.contains("ncrypt", ignoreCase = false) == true && e.message?.contains("unsupported", ignoreCase = true) == true ->
            FileOperationException(Reason.ENCRYPTED_UNSUPPORTED, e.message.orEmpty())
        e.message?.contains("passphrase", ignoreCase = true) == true -> FileOperationException(Reason.ENCRYPTED_UNSUPPORTED, e.message.orEmpty())
        e is net.lingala.zip4j.exception.ZipException && e.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD ->
            FileOperationException(Reason.WRONG_PASSWORD, e.message.orEmpty())
        password != null && (e.message?.contains("password", ignoreCase = true) == true ||
            e.message?.contains("checksum", ignoreCase = true) == true || e is org.tukaani.xz.CorruptedInputException) ->
            FileOperationException(Reason.WRONG_PASSWORD, e.message.orEmpty())
        e is IOException && e.message?.contains("encrypted", ignoreCase = true) == true -> FileOperationException(Reason.ENCRYPTED, e.message.orEmpty())
        else -> FileOperationException(Reason.BAD_ARCHIVE, "${e.javaClass.simpleName} : ${e.message}")
    }

    private fun withImplicitFolders(raw: List<ArchiveEntry>): List<ArchiveEntry> {
        val entries = LinkedHashMap<String, ArchiveEntry>()
        for (e in raw) {
            if (!isSafe(e.path)) continue
            entries[e.path] = e
            var parent = e.parent
            while (parent.isNotEmpty() && parent !in entries) {
                entries[parent] = ArchiveEntry(parent, true, 0, e.time)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return entries.values.toList()
    }

    private fun forEachSource(sources: List<File>, block: (File, String) -> Unit) {
        for (source in sources) {
            val base = source.parentFile ?: continue
            source.walkTopDown().forEach { file -> block(file, file.relativeTo(base).invariantSeparatorsPath) }
        }
    }

    private fun matches(path: String, prefix: String?): Boolean =
        prefix == null || path == prefix || path.startsWith("$prefix/")

    private fun isSafe(path: String): Boolean = !path.startsWith("/") && path.split('/').none { it == ".." }

    /** [file] est [root] ou se trouve dessous, chemins canoniques (liens résolus) et séparateur compris. */
    internal fun isInside(root: File, file: File): Boolean {
        val r = root.canonicalPath
        val p = file.canonicalPath
        return p == r || p.startsWith(r + File.separator)
    }

    /** Flux d'un élément à l'intérieur d'une archive lue en continu : le fermer ne ferme pas l'archive. */
    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() = Unit
    }
}

