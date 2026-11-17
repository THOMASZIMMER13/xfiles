package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.domain.NameValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

enum class TransferMode { COPY, MOVE }

/** Réponse de l'utilisateur quand le nom existe déjà dans le dossier de destination. */
enum class ConflictChoice { REPLACE, KEEP_BOTH, SKIP }

sealed interface TransferResult {
    data class Done(val path: String) : TransferResult
    data object Skipped : TransferResult

    /** Déplacement vers le dossier où l'élément se trouve déjà : rien à faire. */
    data object AlreadyHere : TransferResult
}

data class ClipItem(val path: String, val name: String, val isDirectory: Boolean)

data class ClipContent(val items: List<ClipItem>, val mode: TransferMode)

/** Suivi d'un transfert : octets écrits, et demande d'annulation vérifiée à chaque bloc. */
interface TransferListener {
    fun onBytes(count: Long) {}
    val isCancelled: Boolean get() = false

    companion object {
        val NONE = object : TransferListener {}
    }
}

/** Levée quand l'utilisateur annule ; ce qui était en cours d'écriture a déjà été retiré. */
class TransferCancelledException : IOException("Transfert annulé")

/** Taille totale d'un fichier ou d'un dossier, contenu compris. */
fun sizeOf(file: File): Long =
    if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

/** Copie un flux par blocs, en signalant la progression et en s'arrêtant si l'utilisateur annule. */
fun copyStream(input: InputStream, output: OutputStream, listener: TransferListener) {
    val buffer = ByteArray(256 * 1024)
    while (true) {
        if (listener.isCancelled) throw TransferCancelledException()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        listener.onBytes(read.toLong())
    }
}

/**
 * Presse-papiers de fichiers de l'app (distinct du presse-papiers texte d'Android) :
 * « Copier » ou « Déplacer » le remplit, « Coller ici » le vide après un déplacement.
 * Il est partagé entre tous les dossiers et tous les onglets.
 */
class FileClipboard {
    private val _content = MutableStateFlow<ClipContent?>(null)
    val content: StateFlow<ClipContent?> = _content.asStateFlow()

    fun set(items: List<ClipItem>, mode: TransferMode) {
        _content.value = if (items.isEmpty()) null else ClipContent(items, mode)
    }

    fun clear() {
        _content.value = null
    }
}

/**
 * Copie et déplacement sur le stockage local. Opérations bloquantes : à appeler hors du fil principal.
 *
 * Un déplacement sur le même volume est un simple renommage, instantané ; entre deux volumes
 * (mémoire interne → carte SD), c'est une copie suivie de la suppression de l'original.
 */
class LocalTransfer {

    /**
     * Éléments de [sources] dont le nom existe déjà dans [targetDir] et qui demanderont un choix.
     * Ne compte pas la copie dans son propre dossier (dupliquée automatiquement en « Facture 2.pdf »)
     * ni le déplacement vers le dossier actuel (rien à faire).
     */
    fun conflicts(sources: List<String>, targetDir: String): Set<String> {
        val dir = File(targetDir)
        val existing = dir.list()?.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } ?: return emptySet()
        return sources.filterTo(HashSet()) { path ->
            val src = File(path)
            src.parentFile?.path != dir.path && src.name.lowercase(Locale.ROOT) in existing
        }
    }

    /**
     * Copie ou déplace [source] dans [targetDir].
     * @param choice réponse en cas de conflit de nom ; obligatoire si [conflicts] l'a signalé.
     */
    fun transfer(
        source: String,
        targetDir: String,
        mode: TransferMode,
        choice: ConflictChoice?,
        listener: TransferListener = TransferListener.NONE,
    ): TransferResult {
        val src = File(source)
        val dir = File(targetDir)
        if (!src.exists()) fail(Reason.NOT_FOUND, source)
        if (!dir.isDirectory) fail(Reason.NOT_FOUND, targetDir)
        if (dir.path == src.path || dir.path.startsWith(src.path + "/")) fail(Reason.INTO_ITSELF, source)

        val sameParent = src.parentFile?.path == dir.path
        if (sameParent && mode == TransferMode.MOVE) return TransferResult.AlreadyHere

        val names = dir.list()?.toList().orEmpty()
        val clash = names.firstOrNull { it.equals(src.name, ignoreCase = true) }
        val keepExtension = !src.isDirectory
        val name = when {
            // Copier dans le même dossier : on duplique, comme sur iOS.
            sameParent -> NameValidator.uniqueName(src.name, names, keepExtension)
            clash == null -> src.name
            choice == null -> fail(Reason.ALREADY_EXISTS, source)
            choice == ConflictChoice.SKIP -> return TransferResult.Skipped
            choice == ConflictChoice.KEEP_BOTH -> NameValidator.uniqueName(src.name, names, keepExtension)
            else -> {
                // Remplacer : l'élément existant est supprimé avant de placer le nouveau.
                if (!File(dir, clash).deleteRecursively()) fail(Reason.DENIED, source)
                src.name
            }
        }
        if (!dir.canWrite()) fail(Reason.DENIED, targetDir)
        val target = File(dir, name)

        when (mode) {
            TransferMode.COPY -> copy(src, target, listener)
            TransferMode.MOVE -> if (src.renameTo(target)) {
                listener.onBytes(sizeOf(target))
            } else {
                // Autre volume : copie puis suppression de l'original.
                copy(src, target, listener)
                if (!src.deleteRecursively()) fail(Reason.FAILED, source)
            }
        }
        return TransferResult.Done(target.path)
    }

    private fun copy(src: File, target: File, listener: TransferListener) {
        try {
            copyTree(src, target, listener)
        } catch (e: Exception) {
            // Pas de copie à moitié faite : on retire ce qui a déjà été écrit.
            target.deleteRecursively()
            if (e is TransferCancelledException) throw e
            fail(Reason.FAILED, src.path)
        }
    }

    private fun copyTree(src: File, target: File, listener: TransferListener) {
        if (src.isDirectory) {
            if (!target.mkdir()) throw IOException("mkdir ${target.path}")
            src.listFiles()?.forEach { child -> copyTree(child, File(target, child.name), listener) }
        } else {
            if (target.exists()) throw IOException("existe déjà ${target.path}")
            src.inputStream().use { input -> target.outputStream().use { output -> copyStream(input, output, listener) } }
        }
        target.setLastModified(src.lastModified())
    }

    private fun fail(reason: Reason, what: String): Nothing =
        throw FileOperationException(reason, "$reason : $what")
}
