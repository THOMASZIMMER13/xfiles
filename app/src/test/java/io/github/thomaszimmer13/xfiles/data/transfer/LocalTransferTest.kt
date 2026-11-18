package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTransferTest {

    @get:Rule val tmp = TemporaryFolder()
    private val transfer = LocalTransfer()

    private fun file(relative: String, content: String = "x") =
        File(tmp.root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    private fun dir(relative: String) = File(tmp.root, relative).apply { mkdirs() }

    private fun done(r: TransferResult) = (r as TransferResult.Done).path

    @Test fun copyFileAndFolder() {
        val f = file("src/Facture.pdf", "12345")
        file("src/Projet/a.txt", "a")
        val dest = dir("dest")
        assertEquals(File(dest, "Facture.pdf").path, done(transfer.transfer(f.path, dest.path, TransferMode.COPY, null)))
        transfer.transfer(File(tmp.root, "src/Projet").path, dest.path, TransferMode.COPY, null)
        assertTrue(f.exists())
        assertEquals("12345", File(dest, "Facture.pdf").readText())
        assertEquals("a", File(dest, "Projet/a.txt").readText())
    }

    @Test fun copyInSameFolderDuplicates() {
        val f = file("src/Facture.pdf")
        assertTrue(transfer.conflicts(listOf(f.path), f.parent).isEmpty())
        assertEquals(File(f.parentFile, "Facture 2.pdf").path, done(transfer.transfer(f.path, f.parent, TransferMode.COPY, null)))
    }

    @Test fun move() {
        val f = file("src/Facture.pdf")
        val dest = dir("dest")
        transfer.transfer(f.path, dest.path, TransferMode.MOVE, null)
        assertFalse(f.exists())
        assertTrue(File(dest, "Facture.pdf").exists())
        assertEquals(TransferResult.AlreadyHere, transfer.transfer(File(dest, "Facture.pdf").path, dest.path, TransferMode.MOVE, null))
    }

    @Test fun conflicts() {
        val f = file("src/Facture.pdf", "nouveau")
        file("dest/facture.PDF", "ancien")
        val dest = File(tmp.root, "dest")
        assertEquals(setOf(f.path), transfer.conflicts(listOf(f.path), dest.path))

        assertEquals(TransferResult.Skipped, transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.SKIP))
        assertEquals(File(dest, "Facture 2.pdf").path, done(transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.KEEP_BOTH)))
        transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertEquals("nouveau", File(dest, "Facture.pdf").readText())
        assertFalse(dest.list()!!.contains("facture.PDF"))
    }

    @Test(expected = FileOperationException::class)
    fun conflictWithoutChoiceFails() {
        val f = file("src/a.txt")
        file("dest/a.txt")
        transfer.transfer(f.path, File(tmp.root, "dest").path, TransferMode.COPY, null)
    }

    @Test fun folderIntoItselfIsRefused() {
        val d = dir("Projet/sous")
        val reason = runCatching { transfer.transfer(d.parent, d.path, TransferMode.MOVE, null) }
            .exceptionOrNull() as FileOperationException
        assertEquals(FileOperationException.Reason.INTO_ITSELF, reason.reason)
    }

    // --- H-03 : « Remplacer » ne perd jamais les deux versions ---

    private fun residues(dir: File) = dir.list()!!.filter { LocalTransfer.isReplaceTemp(it) }

    @Test fun cancelledReplaceKeepsTheOldItem() {
        val big = File(tmp.root, "src/Rapport.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1_000_000) { 7 }) }
        val dest = dir("dest")
        val old = File(dest, "Rapport.bin").apply { writeText("ancien") }
        val cancelling = object : TransferListener {
            var written = 0L
            override fun onBytes(count: Long) { written += count }
            override val isCancelled: Boolean get() = written > 0
        }
        try {
            transfer.transfer(big.path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE, cancelling)
            throw AssertionError("annulation attendue")
        } catch (_: TransferCancelledException) {
        }
        assertEquals("ancien", old.readText())
        assertTrue(residues(dest).isEmpty())
        assertTrue(big.exists())
    }

    @Test fun replaceSwapsFileAndFolderAndMove() {
        val dest = dir("dest")
        File(dest, "a.txt").writeText("ancien")
        transfer.transfer(file("src/a.txt", "nouveau").path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertEquals("nouveau", File(dest, "a.txt").readText())

        file("dest/Projet/vieux.txt")
        file("src/Projet/neuf.txt")
        transfer.transfer(File(tmp.root, "src/Projet").path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertEquals(listOf("neuf.txt"), File(dest, "Projet").list()!!.toList())

        val moved = file("src2/b.txt", "déplacé")
        File(dest, "B.TXT").writeText("ancien") // conflit sans tenir compte des majuscules
        transfer.transfer(moved.path, dest.path, TransferMode.MOVE, ConflictChoice.REPLACE)
        assertFalse(moved.exists())
        assertEquals("déplacé", File(dest, "b.txt").readText())
        assertTrue(residues(dest).isEmpty())
    }

    @Test fun recoveryAfterCrashRestoresOrCleans() {
        val journal = File(tmp.root, "journal.txt")
        val d1 = dir("d1") // arrêt après la mise de côté : l'ancien revient
        File(d1, ".xfiles-remplace-1234abcd-a-Facture.pdf").writeText("ancien")
        File(d1, ".xfiles-remplace-1234abcd-n-Facture.pdf").writeText("nouveau à moitié")
        val d2 = dir("d2") // arrêt après la mise en place : le nouveau reste
        File(d2, "Facture.pdf").writeText("nouveau")
        File(d2, ".xfiles-remplace-5678abcd-a-Facture.pdf").writeText("ancien")
        journal.writeText(d1.path + "\n" + d2.path)

        LocalTransfer(journal).recover()
        assertEquals("ancien", File(d1, "Facture.pdf").readText())
        assertEquals("nouveau", File(d2, "Facture.pdf").readText())
        assertTrue(residues(d1).isEmpty() && residues(d2).isEmpty())
        assertFalse(journal.exists())
    }

    @Test fun journalIsEmptiedAfterAReplace() {
        val journal = File(tmp.root, "j.txt")
        val dest = dir("dest")
        File(dest, "a.txt").writeText("ancien")
        LocalTransfer(journal).transfer(file("src/a.txt", "nouveau").path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertTrue(journal.readText().isBlank())
    }

    @Test fun replaceFallsBackWhenVolumeRefusesTemporaryRenames() {
        val dest = dir("dest")
        File(dest, "a.txt").writeText("ancien")
        val noTempRename = LocalTransfer(rename = { from, to -> !LocalTransfer.isReplaceTemp(to.name) && from.renameTo(to) })
        noTempRename.transfer(file("src/a.txt", "nouveau").path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertEquals("nouveau", File(dest, "a.txt").readText())
        assertTrue(residues(dest).isEmpty())
    }
}
