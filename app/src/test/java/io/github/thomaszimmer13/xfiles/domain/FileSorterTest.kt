package io.github.thomaszimmer13.xfiles.domain

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSorterTest {

    private fun file(name: String, size: Long = 0, date: Long = 0) = FileItem("/d/$name", name, false, size, date, null)
    private fun folder(name: String, count: Int = 0, date: Long = 0) = FileItem("/d/$name", name, true, 0, date, count)

    private val items = listOf(
        file("zèbre.txt", size = 10, date = 3),
        file("Âne.pdf", size = 300, date = 1),
        folder("musique", count = 5, date = 2),
        file("abeille.mp3", size = 20, date = 5),
        folder("Documents", count = 1, date = 4),
    )

    private fun names(options: SortOptions) = FileSorter.sort(items, options).map { it.name }

    @Test fun byNameFoldersFirstFrenchOrder() {
        assertEquals(listOf("Documents", "musique", "abeille.mp3", "Âne.pdf", "zèbre.txt"), names(SortOptions(SortKey.NAME)))
        assertEquals(listOf("musique", "Documents", "zèbre.txt", "Âne.pdf", "abeille.mp3"), names(SortOptions(SortKey.NAME, descending = true)))
    }

    @Test fun byDateMostRecentFirst() {
        assertEquals(listOf("Documents", "musique", "abeille.mp3", "zèbre.txt", "Âne.pdf"), names(SortOptions(SortKey.DATE, descending = true)))
    }

    @Test fun bySizeLargestFirst() {
        assertEquals(listOf("musique", "Documents", "Âne.pdf", "abeille.mp3", "zèbre.txt"), names(SortOptions(SortKey.SIZE, descending = true)))
    }

    @Test fun byType() {
        assertEquals(listOf("Documents", "musique", "abeille.mp3", "Âne.pdf", "zèbre.txt"), names(SortOptions(SortKey.TYPE)))
    }

    @Test fun breadcrumbs() {
        val roots = listOf("Mémoire interne" to "/storage/emulated/0", "Carte SD" to "/storage/1234-5678")
        assertEquals(
            listOf("Mémoire interne", "Documents", "Factures"),
            Breadcrumbs.of("/storage/emulated/0/Documents/Factures", roots).map { it.name },
        )
        assertEquals(listOf("Carte SD"), Breadcrumbs.of("/storage/1234-5678", roots).map { it.name })
    }
}
