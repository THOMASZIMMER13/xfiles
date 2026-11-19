package io.github.thomaszimmer13.xfiles.data.fs

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Suppressions et parcours qui ne suivent jamais les liens symboliques (audit N-01, N-03, N-11).
 *
 * `File.deleteRecursively()` et `walkTopDown()` de Kotlin entrent dans la cible d'un lien vers un
 * dossier : supprimer un dossier contenant un lien vers DCIM effacerait les photos, et une boucle de
 * liens ferait copier le même contenu des dizaines de fois.
 */
fun File.isSymlink(): Boolean = Files.isSymbolicLink(toPath())

/**
 * Emplacement réel d'un élément sans résoudre l'élément lui-même : pour un lien, c'est l'endroit où
 * se trouve le lien, pas sa cible.
 */
private fun File.location(): File {
    val parent = absoluteFile.parentFile ?: return absoluteFile
    return File(parent.canonicalFile, name)
}

private fun File.within(boundary: File): Boolean {
    val p = path
    val b = boundary.path
    return p == b || p.startsWith(b.trimEnd(File.separatorChar) + File.separator)
}

/**
 * Supprime [target] et son contenu sans jamais entrer dans un lien symbolique : un lien est supprimé
 * lui-même (Files.delete), sa cible reste intacte. Si [boundary] est donné, chaque élément doit se
 * trouver dans ce dossier (chemin canonique suivi du séparateur) avant d'être supprimé ; sinon il est
 * laissé et la fonction renvoie faux.
 *
 * @return vrai si [target] n'existe plus.
 */
fun deleteRecursivelyNoFollow(target: File, boundary: File? = null): Boolean {
    val root = target.toPath()
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
    val limit = boundary?.canonicalFile
    var ok = true

    fun allowed(path: Path): Boolean = limit == null || runCatching { path.toFile().location().within(limit) }.getOrDefault(false)

    try {
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java), // jamais FOLLOW_LINKS
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!allowed(dir)) {
                        ok = false
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                // Fichiers et liens (un lien n'est jamais suivi, il arrive ici comme un fichier).
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!allowed(file) || !runCatching { Files.delete(file) }.isSuccess) ok = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // Élément illisible : on tente quand même de le retirer (lien cassé, droits).
                    if (!allowed(file) || !runCatching { Files.delete(file) }.isSuccess) ok = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (!allowed(dir) || !runCatching { Files.delete(dir) }.isSuccess) ok = false
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (_: IOException) {
        ok = false
    }
    return ok && !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
}

/**
 * Parcours de [this] et de son contenu, liens symboliques exclus (ni listés, ni traversés). Le dossier
 * de départ fait partie du parcours, sauf s'il est lui-même un lien.
 */
fun File.walkNoLinks(): Sequence<File> = walkTopDown().onEnter { !it.isSymlink() }.filter { !it.isSymlink() }

/** Enfants directs de [this], liens symboliques exclus. */
fun File.childrenNoLinks(): List<File> = listFiles()?.filter { !it.isSymlink() }.orEmpty()
