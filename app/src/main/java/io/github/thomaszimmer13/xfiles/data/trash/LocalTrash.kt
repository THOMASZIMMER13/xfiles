package io.github.thomaszimmer13.xfiles.data.trash

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Un élément de la corbeille. */
data class TrashEntry(
    val id: String,
    /** Dossier de la corbeille du volume concerné. */
    val trashDir: String,
    val originalPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    /** Nombre d'éléments pour un dossier, null pour un fichier. */
    val childCount: Int?,
    val deletedAt: Long,
) {
    /** Emplacement actuel de l'élément dans la corbeille. */
    val storedPath: String get() = "$trashDir/$FILES/$id"
}

private const val FILES = "files"
private const val INFO = "info"

/**
 * Corbeille du stockage local, une par volume (mémoire interne, carte SD, clé USB) :
 * un élément supprimé est simplement déplacé dans le dossier caché « .xfiles-corbeille »
 * à la racine de son volume. C'est instantané, même pour un gros dossier, et l'élément ne quitte
 * jamais son volume.
 *
 * Structure : files/<id> contient l'élément, info/<id>.properties son chemin d'origine et sa date.
 * Les opérations sont bloquantes : à appeler hors du fil principal.
 *
 * @param volumeRoots racines des volumes montés.
 */
class LocalTrash(
    private val volumeRoots: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Met [path] à la corbeille de son volume. */
    fun moveToTrash(path: String): TrashEntry {
        val source = File(path)
        if (!source.exists()) fail(Reason.NOT_FOUND, path)
        val trashDir = trashDirFor(path) ?: fail(Reason.FAILED, path)
        val files = File(trashDir, FILES)
        val info = File(trashDir, INFO)
        if (!files.isDirectory && !files.mkdirs()) fail(Reason.DENIED, path)
        if (!info.isDirectory && !info.mkdirs()) fail(Reason.DENIED, path)
        // Empêche la galerie et les lecteurs de musique d'indexer le contenu de la corbeille.
        runCatching { File(trashDir, ".nomedia").createNewFile() }

        val deletedAt = clock()
        val id = "$deletedAt-${UUID.randomUUID().toString().take(8)}"
        val infoFile = File(info, "$id.properties")
        val props = Properties().apply {
            setProperty(KEY_PATH, source.path)
            setProperty(KEY_DELETED_AT, deletedAt.toString())
        }
        try {
            infoFile.outputStream().use { props.store(it, null) }
        } catch (_: Exception) {
            fail(Reason.DENIED, path)
        }
        val stored = File(files, id)
        if (!source.renameTo(stored)) {
            infoFile.delete()
            fail(if (source.parentFile?.canWrite() == false) Reason.DENIED else Reason.FAILED, path)
        }
        return entryOf(trashDir, id, source.path, deletedAt)
    }

    /** Tous les éléments des corbeilles des volumes montés, du plus récent au plus ancien. */
    fun list(): List<TrashEntry> =
        trashDirs().flatMap { dir -> entriesIn(dir) }.sortedByDescending { it.deletedAt }

    fun count(): Int = trashDirs().sumOf { dir ->
        File(dir, INFO).list()?.count { it.endsWith(".properties") } ?: 0
    }

    /**
     * Remet l'élément à son emplacement d'origine, en recréant les dossiers manquants.
     * Si le nom est déjà pris, un numéro est ajouté (« Facture 2.pdf »).
     * @return le chemin de l'élément restauré.
     */
    fun restore(entry: TrashEntry): String {
        val stored = File(entry.storedPath)
        if (!stored.exists()) {
            infoFile(entry).delete()
            fail(Reason.NOT_FOUND, entry.name)
        }
        // Audit F-06 : un fichier d'informations modifié ne doit pas faire sortir l'élément de son
        // volume ni le renvoyer dans la corbeille elle-même.
        if (!sameVolume(entry)) fail(Reason.DENIED, entry.name)
        val parent = File(entry.originalPath).parentFile ?: fail(Reason.FAILED, entry.name)
        if (!parent.isDirectory && !parent.mkdirs()) fail(Reason.DENIED, entry.name)
        val name = NameValidator.uniqueName(entry.name, parent.list()?.toList().orEmpty(), keepExtension = !entry.isDirectory)
        val target = File(parent, name)
        if (!stored.renameTo(target)) {
            fail(if (!parent.canWrite()) Reason.DENIED else Reason.FAILED, entry.name)
        }
        infoFile(entry).delete()
        return target.path
    }

    /** Supprime définitivement un élément de la corbeille. */
    fun deleteForever(entry: TrashEntry) {
        val stored = File(entry.storedPath)
        if (stored.exists() && !stored.deleteRecursively()) fail(Reason.FAILED, entry.name)
        infoFile(entry).delete()
    }

    /** Vide toutes les corbeilles ; renvoie le nombre d'éléments supprimés. */
    fun empty(): Int {
        val entries = list()
        var deleted = 0
        entries.forEach { entry ->
            if (runCatching { deleteForever(entry) }.isSuccess) deleted++
        }
        if (deleted < entries.size) fail(Reason.FAILED, "corbeille")
        return deleted
    }

    /** Supprime les éléments mis à la corbeille il y a plus de 30 jours ; renvoie leur nombre. */
    fun purgeExpired(): Int {
        val limit = clock() - RETENTION_MS
        return list().filter { it.deletedAt < limit }.count { runCatching { deleteForever(it) }.isSuccess }
    }

    /** Dossier de la corbeille du volume qui contient [path] ; null si aucun volume ne le contient. */
    /** Vrai si le chemin d'origine de [entry] est sur le volume de sa corbeille, hors de celle-ci. */
    internal fun sameVolume(entry: TrashEntry): Boolean = runCatching {
        val original = File(entry.originalPath).canonicalFile
        val trash = File(entry.trashDir).canonicalFile
        val volume = trash.parentFile ?: return false
        val inVolume = original.path.startsWith(volume.path.trimEnd(File.separatorChar) + File.separator)
        val inTrash = original == trash || original.path.startsWith(trash.path + File.separator)
        inVolume && !inTrash && trashDirFor(original.path)?.canonicalFile == trash
    }.getOrDefault(false)

    fun trashDirFor(path: String): File? = volumeRoots()
        .filter { path.startsWith("$it/") }
        .maxByOrNull { it.length }
        ?.let { File(it, DIR_NAME) }

    private fun trashDirs(): List<File> = volumeRoots().map { File(it, DIR_NAME) }.filter { it.isDirectory }

    private fun entriesIn(dir: File): List<TrashEntry> {
        val infos = File(dir, INFO).listFiles { f -> f.name.endsWith(".properties") } ?: return emptyList()
        return infos.mapNotNull { infoFile ->
            val id = infoFile.name.removeSuffix(".properties")
            val props = Properties()
            runCatching { infoFile.inputStream().use { props.load(it) } }.getOrNull() ?: return@mapNotNull null
            val original = props.getProperty(KEY_PATH) ?: return@mapNotNull null
            val deletedAt = props.getProperty(KEY_DELETED_AT)?.toLongOrNull() ?: infoFile.lastModified()
            if (!File(dir, "$FILES/$id").exists()) {
                // L'élément a disparu (supprimé par une autre app) : on oublie sa fiche.
                infoFile.delete()
                return@mapNotNull null
            }
            entryOf(dir, id, original, deletedAt)
        }
    }

    private fun entryOf(trashDir: File, id: String, originalPath: String, deletedAt: Long): TrashEntry {
        val stored = File(trashDir, "$FILES/$id")
        val directory = stored.isDirectory
        return TrashEntry(
            id = id,
            trashDir = trashDir.path,
            originalPath = originalPath,
            name = File(originalPath).name,
            isDirectory = directory,
            sizeBytes = if (directory) 0 else stored.length(),
            childCount = if (directory) stored.list()?.count { !it.startsWith(".") } else null,
            deletedAt = deletedAt,
        )
    }

    private fun infoFile(entry: TrashEntry) = File(entry.trashDir, "$INFO/${entry.id}.properties")

    private fun fail(reason: Reason, what: String): Nothing =
        throw FileOperationException(reason, "$reason : $what")

    companion object {
        /** Nom du dossier caché, à la racine de chaque volume. Il n'apparaît jamais dans Explorer. */
        const val DIR_NAME = ".xfiles-corbeille"
        const val RETENTION_DAYS = 30
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
        private const val KEY_PATH = "path"
        private const val KEY_DELETED_AT = "deletedAt"
    }
}
