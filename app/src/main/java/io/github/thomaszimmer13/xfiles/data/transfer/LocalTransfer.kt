package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.fs.childrenNoLinks
import io.github.thomaszimmer13.xfiles.data.fs.walkNoLinks
import io.github.thomaszimmer13.xfiles.data.fs.deleteRecursivelyNoFollow
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
    if (file.isDirectory) file.walkNoLinks().filter { it.isFile }.sumOf { it.length() } else file.length()

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
class LocalTransfer(
    /** Dossiers où un remplacement est en cours, pour réparer après un arrêt brutal (null : aucun). */
    private val journal: File? = null,
    /**
     * Renommage des éléments temporaires d'un remplacement. Sur la mémoire interne (FUSE), les cartes
     * SD et clés USB (FAT, exFAT) montées en accès direct, renommer dans un même dossier fonctionne ;
     * s'il est refusé (volume particulier, lecture seule partielle), on se replie sur l'ancienne
     * méthode plutôt que de bloquer le remplacement. Paramètre remplaçable pour les tests.
     */
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {

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
        val replace = !sameParent && clash != null && choice == ConflictChoice.REPLACE
        val name = when {
            // Copier dans le même dossier : on duplique, comme sur iOS.
            sameParent -> NameValidator.uniqueName(src.name, names, keepExtension)
            clash == null -> src.name
            choice == null -> fail(Reason.ALREADY_EXISTS, source)
            choice == ConflictChoice.SKIP -> return TransferResult.Skipped
            choice == ConflictChoice.KEEP_BOTH -> NameValidator.uniqueName(src.name, names, keepExtension)
            else -> src.name
        }
        if (!dir.canWrite()) fail(Reason.DENIED, targetDir)
        val target = File(dir, name)

        if (replace) {
            replace(src, File(dir, clash!!), target, mode, listener)
        } else {
            place(src, target, mode, listener)
        }
        return TransferResult.Done(target.path)
    }

    /** Copie ou déplacement vers [target], qui n'existe pas encore. */
    private fun place(src: File, target: File, mode: TransferMode, listener: TransferListener) {
        when (mode) {
            TransferMode.COPY -> copy(src, target, listener)
            TransferMode.MOVE -> if (src.renameTo(target)) {
                listener.onBytes(sizeOf(target))
            } else {
                // Autre volume : copie puis suppression de l'original.
                copy(src, target, listener)
                if (!deleteRecursivelyNoFollow(src)) fail(Reason.FAILED, src.path)
            }
        }
    }

    /**
     * Remplace [old] par [src] sans jamais perdre les deux (H-03) :
     * 1. le nouvel élément est d'abord copié sous un nom temporaire caché (« -n- ») ;
     * 2. l'ancien est mis de côté (« -a- ») ;
     * 3. le nouveau prend le nom définitif, puis l'ancien est supprimé.
     * Une annulation ou un échec avant l'étape 3 laisse l'ancien intact. Un arrêt brutal est réparé au
     * démarrage par [recover]. Si le volume refuse les renommages, on revient à l'ancienne méthode
     * (supprimer puis copier), seule possible.
     */
    private fun replace(src: File, old: File, target: File, mode: TransferMode, listener: TransferListener) {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val aside = File(target.parentFile, "$REPLACE_PREFIX$id-a-${target.name}")
        val fresh = File(target.parentFile, "$REPLACE_PREFIX$id-n-${target.name}")
        if (aside.name.length > MAX_NAME) return replaceDirectly(src, old, target, mode, listener)
        note(target.parentFile!!, add = true)
        try {
            // Déplacement sur le même volume : simple renommage, rien à copier.
            val renamed = mode == TransferMode.MOVE && rename(src, fresh)
            if (!renamed) copy(src, fresh, listener)
            if (!rename(old, aside)) {
                // Volume sans renommage possible : nouveau en place après suppression de l'ancien.
                if (renamed) rename(fresh, src)
                deleteRecursivelyNoFollow(fresh)
                return replaceDirectly(src, old, target, mode, listener)
            }
            if (!rename(fresh, target)) {
                rename(aside, old)
                if (renamed) rename(fresh, src)
                deleteRecursivelyNoFollow(fresh)
                fail(Reason.FAILED, src.path)
            }
            deleteRecursivelyNoFollow(aside)
            if (renamed) listener.onBytes(sizeOf(target))
            else if (mode == TransferMode.MOVE && !deleteRecursivelyNoFollow(src)) fail(Reason.FAILED, src.path)
        } catch (e: Exception) {
            deleteRecursivelyNoFollow(fresh)
            if (aside.exists() && !old.exists()) rename(aside, old)
            throw e
        } finally {
            note(target.parentFile!!, add = false)
        }
    }

    private fun replaceDirectly(src: File, old: File, target: File, mode: TransferMode, listener: TransferListener) {
        if (!deleteRecursivelyNoFollow(old)) fail(Reason.DENIED, src.path)
        place(src, target, mode, listener)
    }

    @Synchronized
    private fun note(dir: File, add: Boolean) {
        val file = journal ?: return
        runCatching {
            val lines = (if (file.exists()) file.readLines() else emptyList()).toMutableList()
            if (add) lines += dir.path else lines.remove(dir.path)
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n"))
        }
    }

    /**
     * Après un arrêt brutal pendant un remplacement : la copie temporaire (« -n- ») est supprimée ;
     * l'ancien élément mis de côté (« -a- ») reprend son nom s'il est libre, sinon (le nouveau est en
     * place) il est supprimé. À appeler au démarrage, avant tout transfert.
     */
    @Synchronized
    fun recover() {
        val file = journal ?: return
        val dirs = runCatching { file.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct()
        for (path in dirs) recoverIn(File(path))
        file.delete()
    }

    internal fun recoverIn(dir: File) {
        dir.listFiles { f -> isReplaceTemp(f.name) }?.forEach { f ->
            val rest = f.name.removePrefix(REPLACE_PREFIX)
            val kind = rest.drop(9).take(2) // « a- » ou « n- » après l'identifiant
            val original = rest.drop(11)
            when {
                kind == "n-" -> deleteRecursivelyNoFollow(f)
                kind == "a-" && original.isNotEmpty() && !File(dir, original).exists() -> f.renameTo(File(dir, original))
                else -> deleteRecursivelyNoFollow(f)
            }
        }
    }

    private fun copy(src: File, target: File, listener: TransferListener) {
        try {
            copyTree(src, target, listener)
        } catch (e: Exception) {
            // Pas de copie à moitié faite : on retire ce qui a déjà été écrit.
            deleteRecursivelyNoFollow(target)
            if (e is TransferCancelledException) throw e
            // Disque plein ou fichier trop gros pour ce support : on le dit (N-08).
            fail(DiskLimits.reasonOf(e) ?: Reason.FAILED, src.path)
        }
    }

    private fun copyTree(src: File, target: File, listener: TransferListener) {
        if (src.isDirectory) {
            if (!target.mkdir()) throw IOException("mkdir ${target.path}")
            // Liens symboliques ignorés (N-11) : ni boucle, ni contenu pris hors de la sélection.
            src.childrenNoLinks().forEach { child -> copyTree(child, File(target, child.name), listener) }
        } else {
            if (target.exists()) throw IOException("existe déjà ${target.path}")
            src.inputStream().use { input -> target.outputStream().use { output -> copyStream(input, output, listener) } }
        }
        target.setLastModified(src.lastModified())
    }

    private fun fail(reason: Reason, what: String): Nothing =
        throw FileOperationException(reason, "$reason : $what")

    companion object {
        /** Préfixe des éléments temporaires d'un remplacement (jamais montrés : Recherche, Récents…). */
        const val REPLACE_PREFIX = ".xfiles-remplace-"
        private const val MAX_NAME = 255

        fun isReplaceTemp(name: String) = name.startsWith(REPLACE_PREFIX)
    }
}
