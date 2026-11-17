package io.github.thomaszimmer13.xfiles.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(relative: String, content: String = "x") =
        File(tmp.root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    @Test fun compressThenListAndExtract() {
        file("src/Projet/a.txt", "a")
        file("src/Projet/sous/b.txt", "bb")
        val single = file("src/Facture.pdf", "pdf")
        val zip = File(tmp.root, "Archive.zip")
        ZipArchive.compress(listOf(File(tmp.root, "src/Projet"), single), zip)

        val entries = ZipArchive.list(zip.path)
        assertEquals(setOf("Projet", "Facture.pdf"), ZipArchive.children(entries, "").map { it.name }.toSet())
        assertEquals(setOf("a.txt", "sous"), ZipArchive.children(entries, "Projet").map { it.name }.toSet())
        assertEquals(6L, ZipArchive.uncompressedSize(entries, null))

        val out = File(tmp.root, "out").apply { mkdirs() }
        val all = File(ZipArchive.extract(zip.path, null, out))
        assertEquals("Archive", all.name)
        assertEquals("bb", File(all, "Projet/sous/b.txt").readText())

        // Un sous-dossier seul, puis une deuxième fois : nom libre, rien d'écrasé.
        assertEquals("Projet", File(ZipArchive.extract(zip.path, "Projet", out)).name)
        assertEquals("a", File(out, "Projet/a.txt").readText())
        assertEquals("Projet 2", File(ZipArchive.extract(zip.path, "Projet", out)).name)
        assertEquals("pdf", File(ZipArchive.extract(zip.path, "Facture.pdf", out)).readText())
    }

    @Test fun implicitFoldersAndUnsafePaths() {
        val zip = File(tmp.root, "x.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a/b/c.txt")); out.write("c".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("../evil.txt")); out.write("!".toByteArray()); out.closeEntry()
        }
        val entries = ZipArchive.list(zip.path)
        assertTrue(entries.any { it.path == "a/b" && it.isDirectory })
        assertFalse(entries.any { it.name == "evil.txt" })

        val out = File(tmp.root, "out").apply { mkdirs() }
        ZipArchive.extract(zip.path, null, out)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertEquals("c", File(out, "x/a/b/c.txt").readText())
    }

    @Test fun archiveNames() {
        val f = file("Facture.pdf")
        val d = File(tmp.root, "Projet").apply { mkdirs() }
        assertEquals("Facture.zip", ZipArchive.archiveNameFor(listOf(f), emptyList(), "Archive"))
        assertEquals("Projet 2.zip", ZipArchive.archiveNameFor(listOf(d), listOf("Projet.zip"), "Archive"))
        assertEquals("Archive.zip", ZipArchive.archiveNameFor(listOf(f, d), emptyList(), "Archive"))
    }
}
