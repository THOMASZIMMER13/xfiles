package io.github.thomaszimmer13.xfiles.domain

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import java.text.Collator
import java.util.Locale

enum class SortKey { NAME, DATE, SIZE, TYPE }

/**
 * Options d'affichage d'un dossier.
 * @param descending pour la date : plus récents d'abord ; pour la taille : plus grands d'abord ;
 * pour le nom et le type : de Z à A.
 */
data class SortOptions(
    val key: SortKey = SortKey.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
) {
    companion object {
        /** Ordre le plus naturel pour chaque critère, proposé en premier. */
        fun defaultDescending(key: SortKey): Boolean = key == SortKey.DATE || key == SortKey.SIZE
    }
}

/** Tri des fichiers : les dossiers restent toujours en premier, comme sur iOS. */
object FileSorter {

    // Ordre alphabétique français, insensible à la casse et aux accents secondaires.
    private val collator: Collator = Collator.getInstance(Locale.FRANCE).apply { strength = Collator.SECONDARY }
    private val byName = Comparator<FileItem> { a, b -> collator.compare(a.name, b.name) }

    fun sort(items: List<FileItem>, options: SortOptions): List<FileItem> {
        val criterion: Comparator<FileItem> = when (options.key) {
            SortKey.NAME -> byName
            SortKey.DATE -> compareBy<FileItem> { it.lastModified }.then(byName)
            // Pour un dossier, la « taille » est son nombre d'éléments.
            SortKey.SIZE -> compareBy<FileItem> { if (it.isDirectory) (it.childCount ?: 0).toLong() else it.sizeBytes }.then(byName)
            SortKey.TYPE -> compareBy<FileItem> { extension(it) }.then(byName)
        }
        val ordered = if (options.descending) criterion.reversed() else criterion
        return items.sortedWith(compareBy<FileItem> { !it.isDirectory }.then(ordered))
    }

    private fun extension(item: FileItem): String =
        if (item.isDirectory) "" else item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
