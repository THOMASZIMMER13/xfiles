package io.github.thomaszimmer13.xfiles.data.trash

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LocalTrashTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private val internal by lazy { tmp.newFolder("emulated") }
    private val sd by lazy { tmp.newFolder("sd") }
    private val trash by lazy { LocalTrash({ listOf(internal.path, sd.path) }, { now }) }

    private fun file(root: File, relative: String, content: String = "x") =
        File(root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    @Test fun moveAndRestore() {
        val f = file(internal, "Documents/Facture.pdf", "12345")
        val entry = trash.moveToTrash(f.path)

        assertFalse(f.exists())
        assertEquals("Facture.pdf", entry.name)
        assertEquals(5L, entry.sizeBytes)
        assertTrue(File(internal, ".xfiles-corbeille/.nomedia").exists())
        assertEquals(listOf(entry), trash.list())

        assertEquals(f.path, trash.restore(entry))
        assertEquals("12345", f.readText())
        assertTrue(trash.list().isEmpty())
    }

    @Test fun eachVolumeHasItsOwnTrash() {
        trash.moveToTrash(file(internal, "a.txt").path)
        trash.moveToTrash(file(sd, "Musique/b.mp3").path)
        assertTrue(File(internal, ".xfiles-corbeille/files").list()!!.size == 1)
        assertTrue(File(sd, ".xfiles-corbeille/files").list()!!.size == 1)
        assertEquals(2, trash.count())
    }

    @Test fun listIsNewestFirst() {
        trash.moveToTrash(file(internal, "ancien.txt").path)
        now += 1000
        trash.moveToTrash(file(internal, "recent.txt").path)
        assertEquals(listOf("recent.txt", "ancien.txt"), trash.list().map { it.name })
    }

    @Test fun restoreRecreatesFoldersAndAvoidsConflicts() {
        val f = file(internal, "Documents/Factures/Facture.pdf")
        val entry = trash.moveToTrash(f.path)
        File(internal, "Documents").deleteRecursively()
        assertEquals(f.path, trash.restore(entry))

        val again = trash.moveToTrash(f.path)
        file(internal, "Documents/Factures/Facture.pdf", "nouveau")
        assertEquals(File(f.parentFile, "Facture 2.pdf").path, trash.restore(again))
    }

    @Test fun folders() {
        file(internal, "Projet/a.txt")
        file(internal, "Projet/b.txt")
        val entry = trash.moveToTrash(File(internal, "Projet").path)
        assertTrue(entry.isDirectory)
        assertEquals(2, entry.childCount)
        trash.deleteForever(entry)
        assertTrue(trash.list().isEmpty())
        assertTrue(File(internal, ".xfiles-corbeille/files").list()!!.isEmpty())
    }

    @Test fun purgeAfterThirtyDays() {
        trash.moveToTrash(file(internal, "vieux.txt").path)
        now += TimeUnit.DAYS.toMillis(20)
        trash.moveToTrash(file(internal, "jeune.txt").path)
        now += TimeUnit.DAYS.toMillis(11)
        assertEquals(1, trash.purgeExpired())
        assertEquals(listOf("jeune.txt"), trash.list().map { it.name })
    }

    @Test fun empty() {
        trash.moveToTrash(file(internal, "a.txt").path)
        trash.moveToTrash(file(sd, "b.txt").path)
        assertEquals(2, trash.empty())
        assertEquals(0, trash.count())
    }

    @Test(expected = FileOperationException::class)
    fun missingFile() {
        trash.moveToTrash(File(internal, "absent.txt").path)
    }

    /** Audit F-06 : un chemin d'origine trafiqué ne fait pas sortir l'élément de son volume. */
    @Test fun restoreStaysOnTheSameVolume() {
        val f = file(internal, "Documents/Note.txt")
        val entry = trash.moveToTrash(f.path)
        assertTrue(trash.sameVolume(entry))
        for (bad in listOf(File(sd, "Note.txt").path, "${internal.path}/../sd/Note.txt", "${entry.trashDir}/files/Note.txt", "/etc/Note.txt")) {
            val forged = entry.copy(originalPath = bad)
            assertFalse(bad, trash.sameVolume(forged))
            try {
                trash.restore(forged)
                org.junit.Assert.fail("restauré vers $bad")
            } catch (e: FileOperationException) {
                assertEquals(FileOperationException.Reason.DENIED, e.reason)
            }
        }
        // L'élément est toujours dans la corbeille et se restaure normalement.
        assertEquals(f.path, trash.restore(entry))
    }
}
