package io.github.thomaszimmer13.xfiles.domain

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Audit N-07 : tris simultanés sur plusieurs fils, sans exception ni ordre incohérent. */
class FileSorterConcurrencyTest {
    @Test fun parallelSortsAgree() {
        val names = listOf("Été", "ete", "Zèbre", "abc", "Élan", "zoo", "Œuvre", "oeil", "Ça", "cab", "10", "2", "Ärger", "arbre")
        val items = (0 until 20_000).map { i ->
            val n = names[i % names.size] + " " + (i * 7919 % 20_000)
            FileItem("/s/$n", n, i % 9 == 0, i.toLong(), i.toLong() * 1000, null)
        }.shuffled(java.util.Random(42))
        val expected = FileSorter.sort(items, SortOptions())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 32).map { k ->
                Callable { FileSorter.sort(items.shuffled(java.util.Random(k.toLong())), SortOptions()) }
            }
            pool.invokeAll(tasks, 2, TimeUnit.MINUTES).forEach { assertEquals(expected.map { it.path }, it.get().map { it.path }) }
        } finally {
            pool.shutdownNow()
        }
        // Dossiers d'abord, puis l'ordre français (accents secondaires ignorés à la casse près).
        assertEquals(true, expected.takeWhile { it.isDirectory }.size == items.count { it.isDirectory })
    }
}
